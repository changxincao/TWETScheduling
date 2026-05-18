package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.SequenceSignature;

/**
 * 启发式定价器。
 * <p>
 * 2026-05-18: 旧 VRP 代码里的 GCTabu 会先从当前 RMP 中挑低 reduced cost route，
 * 再通过 remove/add/exchange 邻域快速捞一批负 reduced-cost route。这里先移植这个“工作流”
 * 而不是直接硬搬 VRP 的 O(1) 时间窗数组：TWET 的列成本来自分段线性函数、setup time/cost 和硬窗预处理，
 * 因此第一版对候选序列统一调用 {@link TWETColumnEvaluator} 重算真实成本。它只是 exact pricing 前的加速层；
 * 找不到列时仍由 exact forward labeling 兜底，所以不会影响精确性。
 */
public class HeuristicPricingEngine implements PricingEngine {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;

	public HeuristicPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	@Override
	public PricingResult price(LP lp) {
		if (!config.enableHeuristicPricing || config.maxHeuristicPricingColumns <= 0) {
			return PricingResult.noImprovement("Heuristic pricing disabled");
		}

		ArrayList<TWETColumn> seeds = collectSeedColumns(lp);
		if (seeds.isEmpty()) {
			return PricingResult.noImprovement("No active seed column for heuristic pricing");
		}

		HashSet<SequenceSignature> activeSignatures = activeSignatures(lp);
		HashSet<SequenceSignature> generatedSignatures = new HashSet<SequenceSignature>();
		ArrayList<ScoredSequence> negativeCandidates = new ArrayList<ScoredSequence>();
		int[] scanCount = new int[] { 0 };

		for (TWETColumn seed : seeds) {
			if (scanCount[0] >= config.maxHeuristicPricingCandidateScans) {
				break;
			}
			searchSeedNeighborhood(seed.getSequence(), lp, activeSignatures, generatedSignatures, negativeCandidates,
					scanCount);
		}

		if (negativeCandidates.isEmpty()) {
			return PricingResult.noImprovement("Heuristic pricing found no negative reduced-cost column; scanned "
					+ scanCount[0] + " candidates");
		}

		Collections.sort(negativeCandidates, new Comparator<ScoredSequence>() {
			@Override
			public int compare(ScoredSequence a, ScoredSequence b) {
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return 1;
				}
				return Integer.compare(a.sequence.size(), b.sequence.size());
			}
		});

		ArrayList<TWETColumn> columns = new ArrayList<TWETColumn>();
		int limit = Math.min(config.maxHeuristicPricingColumns, negativeCandidates.size());
		for (int i = 0; i < limit; i++) {
			ScoredSequence candidate = negativeCandidates.get(i);
			columns.add(new TWETColumn(-1, candidate.sequence, data.n, candidate.cost, ColumnSource.PRICING_HEURISTIC,
					false));
		}
		return new PricingResult(columns, true,
				"Heuristic pricing generated " + columns.size() + " columns; scanned " + scanCount[0] + " candidates");
	}

	@Override
	public String getName() {
		return "HeuristicPricing";
	}

	private ArrayList<TWETColumn> collectSeedColumns(final LP lp) {
		ArrayList<TWETColumn> seeds = new ArrayList<TWETColumn>();
		for (int columnId : lp.getRestrictedColumnIds()) {
			TWETColumn column = lp.getPool().getColumn(columnId);
			if (!column.getSequence().isEmpty()) {
				seeds.add(column);
			}
		}
		Collections.sort(seeds, new Comparator<TWETColumn>() {
			@Override
			public int compare(TWETColumn a, TWETColumn b) {
				double rcA = reducedCost(a.getSequence(), a.getCost(), lp);
				double rcB = reducedCost(b.getSequence(), b.getCost(), lp);
				if (Utility.compareLt(rcA, rcB)) {
					return -1;
				}
				if (Utility.compareGt(rcA, rcB)) {
					return 1;
				}
				return Integer.compare(a.size(), b.size());
			}
		});
		int limit = Math.min(config.heuristicPricingSeedColumns, seeds.size());
		return new ArrayList<TWETColumn>(seeds.subList(0, limit));
	}

	private HashSet<SequenceSignature> activeSignatures(LP lp) {
		HashSet<SequenceSignature> signatures = new HashSet<SequenceSignature>();
		for (int columnId : lp.getRestrictedColumnIds()) {
			signatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		return signatures;
	}

	private void searchSeedNeighborhood(List<Integer> seed, LP lp, HashSet<SequenceSignature> activeSignatures,
			HashSet<SequenceSignature> generatedSignatures, ArrayList<ScoredSequence> negativeCandidates,
			int[] scanCount) {
		ArrayList<Integer> base = new ArrayList<Integer>(seed);
		boolean[] used = usedJobs(base);

		// remove: 删除一个任务，类似旧 GCTabu 的 remove 邻域。
		if (base.size() > 1) {
			for (int removePos = 0; removePos < base.size(); removePos++) {
				ArrayList<Integer> candidate = new ArrayList<Integer>(base);
				candidate.remove(removePos);
				tryCandidate(candidate, lp, activeSignatures, generatedSignatures, negativeCandidates, scanCount);
				if (shouldStop(scanCount)) {
					return;
				}
			}
		}

		// relocate: 同一批任务换顺序。TWET 有 setup time/cost，顺序变化本身可能产生负 reduced cost。
		for (int from = 0; from < base.size(); from++) {
			ArrayList<Integer> without = new ArrayList<Integer>(base);
			int job = without.remove(from).intValue();
			for (int to = 0; to <= without.size(); to++) {
				if (to == from) {
					continue;
				}
				ArrayList<Integer> candidate = new ArrayList<Integer>(without);
				candidate.add(to, Integer.valueOf(job));
				tryCandidate(candidate, lp, activeSignatures, generatedSignatures, negativeCandidates, scanCount);
				if (shouldStop(scanCount)) {
					return;
				}
			}
		}

		// add / exchange: 从未访问任务中尝试插入或替换。这里不做完整 tabu tenure，先保留最稳的邻域捞列功能。
		for (int job = 1; job <= data.n; job++) {
			if (used[job]) {
				continue;
			}
			for (int pos = 0; pos <= base.size(); pos++) {
				ArrayList<Integer> candidate = new ArrayList<Integer>(base);
				candidate.add(pos, Integer.valueOf(job));
				tryCandidate(candidate, lp, activeSignatures, generatedSignatures, negativeCandidates, scanCount);
				if (shouldStop(scanCount)) {
					return;
				}
			}
			for (int pos = 0; pos < base.size(); pos++) {
				ArrayList<Integer> candidate = new ArrayList<Integer>(base);
				candidate.set(pos, Integer.valueOf(job));
				tryCandidate(candidate, lp, activeSignatures, generatedSignatures, negativeCandidates, scanCount);
				if (shouldStop(scanCount)) {
					return;
				}
			}
		}
	}

	private boolean shouldStop(int[] scanCount) {
		// 旧 GCTabu 是先形成本地负 reduced-cost route pool，再排序选最好的 addin_size 条；
		// 因此这里不能“找到足够条数就停”，否则早期较弱候选会挤掉后面更好的候选。
		return scanCount[0] >= config.maxHeuristicPricingCandidateScans;
	}

	private void tryCandidate(ArrayList<Integer> sequence, LP lp, HashSet<SequenceSignature> activeSignatures,
			HashSet<SequenceSignature> generatedSignatures, ArrayList<ScoredSequence> negativeCandidates,
			int[] scanCount) {
		if (scanCount[0] >= config.maxHeuristicPricingCandidateScans || sequence.isEmpty()
				|| hasDuplicateJobs(sequence) || !isSequenceCompatible(lp.getNode(), sequence)) {
			return;
		}
		scanCount[0]++;
		SequenceSignature signature = new SequenceSignature(sequence);
		if (activeSignatures.contains(signature) || !generatedSignatures.add(signature)) {
			return;
		}
		double cost = evaluator.evaluate(sequence);
		if (Utility.isBigMValue(cost)) {
			return;
		}
		double reducedCost = reducedCost(sequence, cost, lp);
		if (Utility.compareLt(reducedCost, REDUCED_COST_TOLERANCE)) {
			negativeCandidates.add(new ScoredSequence(sequence, cost, reducedCost));
		}
	}

	private boolean isSequenceCompatible(Node node, ArrayList<Integer> sequence) {
		if (sequence.isEmpty()) {
			return false;
		}
		if (node.getArcState(0, sequence.get(0).intValue()) == Node.ARC_FORBIDDEN) {
			return false;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (node.getArcState(sequence.get(i - 1).intValue(), sequence.get(i).intValue()) == Node.ARC_FORBIDDEN) {
				return false;
			}
		}
		return node.getArcState(sequence.get(sequence.size() - 1).intValue(), node.sinkId()) != Node.ARC_FORBIDDEN;
	}

	private boolean[] usedJobs(List<Integer> sequence) {
		boolean[] used = new boolean[data.n + 1];
		for (int job : sequence) {
			if (job >= 1 && job <= data.n) {
				used[job] = true;
			}
		}
		return used;
	}

	private boolean hasDuplicateJobs(List<Integer> sequence) {
		boolean[] seen = new boolean[data.n + 1];
		for (int job : sequence) {
			if (job < 1 || job > data.n || seen[job]) {
				return true;
			}
			seen[job] = true;
		}
		return false;
	}

	private double reducedCost(List<Integer> sequence, double cost, LP lp) {
		if (sequence.isEmpty() || Utility.isBigMValue(cost)) {
			return Utility.big_M;
		}
		double reducedCost = cost - lp.getMachineDual();
		int prev = 0;
		for (int job : sequence) {
			reducedCost -= lp.getJobDual(job);
			reducedCost -= lp.getArcDual(prev, job);
			prev = job;
		}
		reducedCost -= lp.getArcDual(prev, lp.getNode().sinkId());
		return reducedCost;
	}

	private static final class ScoredSequence {
		final ArrayList<Integer> sequence;
		final double cost;
		final double reducedCost;

		ScoredSequence(List<Integer> sequence, double cost, double reducedCost) {
			this.sequence = new ArrayList<Integer>(sequence);
			this.cost = cost;
			this.reducedCost = reducedCost;
		}
	}

}
