package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import HEU.Solution;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.SequenceSignature;

/**
 * 启发式定价器。
 * <p>
 * 2026-05-18: 这一版按旧 VRP `GCTabu` 的框架改写：从当前 RMP 中选择低 reduced-cost seed
 * column，围绕每条 seed 做 remove/add/exchange tabu 搜索，生成本地负 reduced-cost column
 * pool，最后排序取少量列加入 RMP。和旧 VRP 不同的是，TWET 列成本不是简单弧成本之和，所以每条
 * seed 会先建立 forward/backward 分段函数 profile；候选 move 用
 * `merge2Segments/merge3Segments` 快速拼接评价，而不是每个候选都完整重建一条序列。exact pricing
 * 仍在该启发式之后执行，因此这里是加速层，不承担最优性证明。
 */
public class HeuristicPricingEngine implements PricingEngine {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;

	private final Data data;
	private final TWETBPCConfig config;
	private final SegmentProfile[] singletonProfileCache;

	public HeuristicPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.singletonProfileCache = buildSingletonProfileCache();
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

		Utility.resetCurUpperBound(Utility.big_M);
		HashSet<SequenceSignature> activeSignatures = activeSignatures(lp);
		HashSet<SequenceSignature> generatedSignatures = new HashSet<SequenceSignature>();
		ArrayList<ScoredSequence> negativeCandidates = new ArrayList<ScoredSequence>();

		for (TWETColumn seed : seeds) {
			if (isHeuristicPoolFull(negativeCandidates)) {
				break;
			}
			tabuSearch(seed.getSequence(), lp, activeSignatures, generatedSignatures, negativeCandidates);
		}

		if (negativeCandidates.isEmpty()) {
			return PricingResult.noImprovement("Tabu heuristic pricing found no negative reduced-cost column");
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
				"Tabu heuristic pricing generated " + columns.size() + " columns from local pool "
						+ negativeCandidates.size());
	}

	@Override
	public String getName() {
		return "HeuristicPricing";
	}

	@Override
	public boolean repeatFindFeasibleUntilExhausted() {
		return true;
	}

	private ArrayList<TWETColumn> collectSeedColumns(final LP lp) {
		ArrayList<ScoredSeed> scoredSeeds = new ArrayList<ScoredSeed>();
		for (int columnId : lp.getRestrictedColumnIds()) {
			TWETColumn column = lp.getPool().getColumn(columnId);
			if (!column.getSequence().isEmpty()) {
				// 2026-05-21: seed 排序前先缓存 reduced cost，避免 comparator 反复扫描同一条列。
				scoredSeeds.add(new ScoredSeed(column, reducedCost(column.getSequence(), column.getCost(), lp)));
			}
		}
		Collections.sort(scoredSeeds, new Comparator<ScoredSeed>() {
			@Override
			public int compare(ScoredSeed a, ScoredSeed b) {
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return 1;
				}
				return Integer.compare(a.column.size(), b.column.size());
			}
		});
		int limit = Math.min(config.heuristicPricingSeedColumns, scoredSeeds.size());
		ArrayList<TWETColumn> seeds = new ArrayList<TWETColumn>(limit);
		for (int i = 0; i < limit; i++) {
			seeds.add(scoredSeeds.get(i).column);
		}
		return seeds;
	}

	private void tabuSearch(List<Integer> seed, LP lp, HashSet<SequenceSignature> activeSignatures,
			HashSet<SequenceSignature> generatedSignatures, ArrayList<ScoredSequence> negativeCandidates) {
		TabuRouteState state = new TabuRouteState(seed);
		if (!state.isValid() || !isSequenceCompatible(lp.getNode(), state.sequence)) {
			return;
		}
		double bestReducedCost = state.reducedCost(lp);
		tryAddNegative(state.sequence, state.cost, bestReducedCost, activeSignatures, generatedSignatures,
				negativeCandidates);

		int iterations = Math.max(1, config.heuristicPricingTabuIterations);
		for (int iter = 0; iter < iterations && !isHeuristicPoolFull(negativeCandidates); iter++) {
			TabuMove bestMove = findBestMove(state, lp, iter, bestReducedCost);
			if (bestMove == null) {
				break;
			}
			state.apply(bestMove, iter + config.heuristicPricingTabuTenure);
			if (Utility.compareLt(state.currentReducedCost, bestReducedCost)) {
				bestReducedCost = state.currentReducedCost;
			}
			tryAddNegative(state.sequence, state.cost, state.currentReducedCost, activeSignatures, generatedSignatures,
					negativeCandidates);
		}
	}

	private TabuMove findBestMove(TabuRouteState state, LP lp, int iter, double bestReducedCost) {
		TabuMove bestMove = null;
		double bestMoveReducedCost = Double.POSITIVE_INFINITY;

		if (state.sequence.size() > 1) {
			for (int pos = 0; pos < state.sequence.size(); pos++) {
				TabuMove move = state.evaluateRemove(pos, lp);
				if (isAcceptedCandidate(move, iter, bestReducedCost)
						&& Utility.compareLt(move.reducedCost, bestMoveReducedCost)) {
					bestMove = move;
					bestMoveReducedCost = move.reducedCost;
				}
			}
		}

		for (int job = 1; job <= data.n; job++) {
			if (state.used[job]) {
				continue;
			}
			for (int pos = 0; pos <= state.sequence.size(); pos++) {
				TabuMove move = state.evaluateAdd(job, pos, lp);
				if (isAcceptedCandidate(move, iter, bestReducedCost)
						&& Utility.compareLt(move.reducedCost, bestMoveReducedCost)) {
					bestMove = move;
					bestMoveReducedCost = move.reducedCost;
				}
			}
			for (int pos = 0; pos < state.sequence.size(); pos++) {
				TabuMove move = state.evaluateExchange(job, pos, lp);
				if (isAcceptedCandidate(move, iter, bestReducedCost)
						&& Utility.compareLt(move.reducedCost, bestMoveReducedCost)) {
					bestMove = move;
					bestMoveReducedCost = move.reducedCost;
				}
			}
		}
		return bestMove;
	}

	private boolean isAcceptedCandidate(TabuMove move, int iter, double bestReducedCost) {
		if (move == null || !move.valid) {
			return false;
		}
		// 旧 GCTabu 的 aspiration：如果候选优于历史最好 reduced cost，即使 tabu 也允许。
		return !move.isTabu(iter) || Utility.compareLt(move.reducedCost, bestReducedCost);
	}

	private void tryAddNegative(List<Integer> sequence, double cost, double reducedCost,
			HashSet<SequenceSignature> activeSignatures, HashSet<SequenceSignature> generatedSignatures,
			ArrayList<ScoredSequence> negativeCandidates) {
		if (isHeuristicPoolFull(negativeCandidates)) {
			return;
		}
		if (sequence.isEmpty() || Utility.isBigMValue(cost) || hasDuplicateJobs(sequence)) {
			return;
		}
		SequenceSignature signature = new SequenceSignature(sequence);
		if (activeSignatures.contains(signature) || !generatedSignatures.add(signature)) {
			return;
		}
		if (Utility.compareLt(reducedCost, REDUCED_COST_TOLERANCE)) {
			negativeCandidates.add(new ScoredSequence(sequence, cost, reducedCost));
		}
	}

	private boolean isHeuristicPoolFull(ArrayList<ScoredSequence> negativeCandidates) {
		return negativeCandidates.size() >= config.heuristicPricingPoolSize;
	}

	private HashSet<SequenceSignature> activeSignatures(LP lp) {
		HashSet<SequenceSignature> signatures = new HashSet<SequenceSignature>();
		for (int columnId : lp.getRestrictedColumnIds()) {
			signatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		return signatures;
	}

	private boolean isSequenceCompatible(Node node, List<Integer> sequence) {
		if (sequence.isEmpty()) {
			return false;
		}
		if (node.isArcForbidden(0, sequence.get(0).intValue())) {
			return false;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (node.isArcForbidden(sequence.get(i - 1).intValue(), sequence.get(i).intValue())) {
				return false;
			}
		}
		return !node.isArcForbidden(sequence.get(sequence.size() - 1).intValue(), node.sinkId());
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

	private final class TabuRouteState {
		private final Solution mergeHelper = new Solution(data);
		private ArrayList<Integer> sequence;
		private boolean[] used;
		private int[] tabuTenure;
		private PiecewiseLinearFunction[] forward;
		private PiecewiseLinearFunction[] backward;
		private double cost;
		private double currentReducedCost;

		TabuRouteState(List<Integer> seed) {
			this.sequence = new ArrayList<Integer>(seed);
			this.used = new boolean[data.n + 1];
			this.tabuTenure = new int[data.n + 1];
			rebuild();
		}

		boolean isValid() {
			return !sequence.isEmpty() && !Utility.isBigMValue(cost);
		}

		double reducedCost(LP lp) {
			currentReducedCost = HeuristicPricingEngine.this.reducedCost(sequence, cost, lp);
			return currentReducedCost;
		}

		TabuMove evaluateRemove(int pos, LP lp) {
			if (sequence.size() <= 1) {
				return TabuMove.invalid();
			}
			double candidateCost = removeCost(pos);
			int removedJob = sequence.get(pos).intValue();
			if (Utility.isBigMValue(candidateCost) || !isRemoveCompatible(pos, lp.getNode())) {
				return TabuMove.invalid();
			}
			double rc = reducedCostAfterRemove(pos, removedJob, candidateCost, lp);
			return new TabuMove(candidateCost, rc, MoveType.REMOVE, pos, removedJob, -1, tabuTenure);
		}

		TabuMove evaluateAdd(int job, int pos, LP lp) {
			double candidateCost = insertOrReplaceCost(pos, job, false);
			if (Utility.isBigMValue(candidateCost) || !isInsertCompatible(pos, job, false, lp.getNode())) {
				return TabuMove.invalid();
			}
			double rc = reducedCostAfterAdd(pos, job, candidateCost, lp);
			return new TabuMove(candidateCost, rc, MoveType.ADD, pos, job, -1, tabuTenure);
		}

		TabuMove evaluateExchange(int job, int pos, LP lp) {
			double candidateCost = insertOrReplaceCost(pos, job, true);
			int removedJob = sequence.get(pos).intValue();
			if (Utility.isBigMValue(candidateCost) || !isInsertCompatible(pos, job, true, lp.getNode())) {
				return TabuMove.invalid();
			}
			double rc = reducedCostAfterExchange(pos, job, removedJob, candidateCost, lp);
			return new TabuMove(candidateCost, rc, MoveType.EXCHANGE, pos, job, removedJob, tabuTenure);
		}

		void apply(TabuMove move, int tenureUntil) {
			if (move.type == MoveType.REMOVE) {
				this.sequence.remove(move.position);
			} else if (move.type == MoveType.ADD) {
				this.sequence.add(move.position, Integer.valueOf(move.primaryJob));
			} else {
				this.sequence.set(move.position, Integer.valueOf(move.primaryJob));
			}
			if (move.primaryJob >= 1 && move.primaryJob < tabuTenure.length) {
				tabuTenure[move.primaryJob] = tenureUntil;
			}
			if (move.secondaryJob >= 1 && move.secondaryJob < tabuTenure.length) {
				tabuTenure[move.secondaryJob] = tenureUntil;
			}
			rebuild();
			this.currentReducedCost = move.reducedCost;
		}

		private double removeCost(int pos) {
			if (sequence.size() == 1) {
				return 0.0;
			}
			int end = pos;
			PiecewiseLinearFunction f1 = pos == 0 ? data.penaltyFunction[0] : forward[pos - 1];
			if (end == sequence.size() - 1) {
				return f1.tail.getValue(f1.tail.end);
			}
			PiecewiseLinearFunction b2 = backward[end + 1];
			int bridgeFrom = pos == 0 ? 0 : sequence.get(pos - 1).intValue();
			int bridgeTo = sequence.get(end + 1).intValue();
			return mergeHelper.merge2Segments(f1, b2, data.s[bridgeFrom][bridgeTo] + data.p[bridgeTo],
					data.getSetupCost(bridgeFrom, bridgeTo));
		}

		private double insertOrReplaceCost(int pos, int job, boolean replace) {
			int prefixEnd = pos - 1;
			int suffixStart = replace ? pos + 1 : pos;
			PiecewiseLinearFunction f1 = prefixEnd < 0 ? data.penaltyFunction[0] : forward[prefixEnd];
			PiecewiseLinearFunction b3 = suffixStart >= sequence.size() ? data.penaltyFunction[0] : backward[suffixStart];
			SegmentProfile single = singletonProfile(job);
			int bridgeFrom1 = prefixEnd < 0 ? 0 : sequence.get(prefixEnd).intValue();
			double shift1 = data.s[bridgeFrom1][job] + data.p[job];
			int bridgeTo2 = suffixStart >= sequence.size() ? 0 : sequence.get(suffixStart).intValue();
			double shift2 = suffixStart >= sequence.size() ? 0.0 : data.s[job][bridgeTo2] + data.p[bridgeTo2];
			return mergeHelper.merge3Segments(f1, single.forward, single.backward, b3, shift1, shift2, 0.0,
					data.getSetupCost(bridgeFrom1, job),
					suffixStart >= sequence.size() ? 0.0 : data.getSetupCost(job, bridgeTo2));
		}

		private double reducedCostAfterRemove(int pos, int removedJob, double candidateCost, LP lp) {
			int prev = pos == 0 ? 0 : sequence.get(pos - 1).intValue();
			int next = pos == sequence.size() - 1 ? lp.getNode().sinkId() : sequence.get(pos + 1).intValue();
			// 2026-05-21: 对齐旧 VRP GCTabu，候选 move 的 reduced cost 只做局部增量更新。
			// 机器真实成本变化由分段函数拼接给出；dual 部分只需要替换受影响的 job 和两三条弧。
			return currentReducedCost + candidateCost - cost + lp.getJobDual(removedJob)
					+ lp.getArcDual(prev, removedJob) + lp.getArcDual(removedJob, next) - lp.getArcDual(prev, next);
		}

		private double reducedCostAfterAdd(int pos, int job, double candidateCost, LP lp) {
			int prev = pos == 0 ? 0 : sequence.get(pos - 1).intValue();
			int next = pos == sequence.size() ? lp.getNode().sinkId() : sequence.get(pos).intValue();
			return currentReducedCost + candidateCost - cost - lp.getJobDual(job) - lp.getArcDual(prev, job)
					- lp.getArcDual(job, next) + lp.getArcDual(prev, next);
		}

		private double reducedCostAfterExchange(int pos, int job, int removedJob, double candidateCost, LP lp) {
			int prev = pos == 0 ? 0 : sequence.get(pos - 1).intValue();
			int next = pos == sequence.size() - 1 ? lp.getNode().sinkId() : sequence.get(pos + 1).intValue();
			return currentReducedCost + candidateCost - cost + lp.getJobDual(removedJob) - lp.getJobDual(job)
					+ lp.getArcDual(prev, removedJob) + lp.getArcDual(removedJob, next) - lp.getArcDual(prev, job)
					- lp.getArcDual(job, next);
		}

		private boolean isRemoveCompatible(int pos, Node node) {
			int prev = pos == 0 ? 0 : sequence.get(pos - 1).intValue();
			int next = pos == sequence.size() - 1 ? node.sinkId() : sequence.get(pos + 1).intValue();
			return !node.isArcForbidden(prev, next);
		}

		private boolean isInsertCompatible(int pos, int job, boolean replace, Node node) {
			int prefixEnd = pos - 1;
			int suffixStart = replace ? pos + 1 : pos;
			int prev = prefixEnd < 0 ? 0 : sequence.get(prefixEnd).intValue();
			int next = suffixStart >= sequence.size() ? node.sinkId() : sequence.get(suffixStart).intValue();
			return !node.isArcForbidden(prev, job) && !node.isArcForbidden(job, next);
		}

		private void rebuild() {
			this.used = new boolean[data.n + 1];
			for (int job : sequence) {
				if (job >= 1 && job <= data.n) {
					used[job] = true;
				}
			}
			this.forward = buildForwardProfile(sequence, true);
			this.backward = buildBackwardProfile(sequence);
			this.cost = sequence.isEmpty() || forward.length == 0 || forward[forward.length - 1].isEmpty() ? Utility.big_M
					: forward[forward.length - 1].tail.getValue(forward[forward.length - 1].tail.end);
		}
	}

	private PiecewiseLinearFunction[] buildForwardProfile(List<Integer> jobs, boolean includeDepotStart) {
		PiecewiseLinearFunction[] result = new PiecewiseLinearFunction[jobs.size()];
		for (int i = 0; i < jobs.size(); i++) {
			int job = jobs.get(i).intValue();
			PiecewiseLinearFunction cur;
			if (i == 0) {
				cur = data.penaltyFunction[job].copy();
				if (includeDepotStart) {
					cur = cur.setDomain(data.p[job] + data.s[0][job], data.CmaxH);
					cur.shiftYInPlace(data.getSetupCost(0, job));
				}
			} else {
				int prev = jobs.get(i - 1).intValue();
				cur = result[i - 1].shiftX(data.s[prev][job] + data.p[job]).add(data.penaltyFunction[job]);
				cur.shiftYInPlace(data.getSetupCost(prev, job));
			}
			cur.minimizePrefixInPlace();
			result[i] = cur;
		}
		return result;
	}

	private PiecewiseLinearFunction[] buildBackwardProfile(List<Integer> jobs) {
		PiecewiseLinearFunction[] result = new PiecewiseLinearFunction[jobs.size()];
		for (int i = jobs.size() - 1; i >= 0; i--) {
			int job = jobs.get(i).intValue();
			PiecewiseLinearFunction cur;
			if (i == jobs.size() - 1) {
				cur = data.penaltyFunction[job].copy();
			} else {
				int next = jobs.get(i + 1).intValue();
				cur = result[i + 1].shiftX(-data.s[job][next] - data.p[next]).add(data.penaltyFunction[job]);
				cur.shiftYInPlace(data.getSetupCost(job, next));
			}
			cur.minimizeSuffixInPlace();
			result[i] = cur;
		}
		return result;
	}

	private SegmentProfile[] buildSingletonProfileCache() {
		SegmentProfile[] cache = new SegmentProfile[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			cache[job] = buildSingletonProfile(job);
		}
		return cache;
	}

	private SegmentProfile buildSingletonProfile(int job) {
		PiecewiseLinearFunction forward = data.penaltyFunction[job].copy();
		forward.minimizePrefixInPlace();
		PiecewiseLinearFunction backward = data.penaltyFunction[job].copy();
		backward.minimizeSuffixInPlace();
		return new SegmentProfile(forward, backward);
	}

	private SegmentProfile singletonProfile(int job) {
		SegmentProfile cached = singletonProfileCache[job];
		// 2026-05-21: 单 job 的 normalize 结果只和 job 自身有关，先缓存模板。
		// merge3Segments 当前只会改中间 forward 函数；backward 只读复用即可，避免每次多复制一份。
		return new SegmentProfile(cached.forward.copy(), cached.backward);
	}

	private static final class SegmentProfile {
		final PiecewiseLinearFunction forward;
		final PiecewiseLinearFunction backward;

		private SegmentProfile(PiecewiseLinearFunction forward, PiecewiseLinearFunction backward) {
			this.forward = forward;
			this.backward = backward;
		}
	}

	private enum MoveType {
		REMOVE, ADD, EXCHANGE
	}

	private static final class ScoredSeed {
		final TWETColumn column;
		final double reducedCost;

		ScoredSeed(TWETColumn column, double reducedCost) {
			this.column = column;
			this.reducedCost = reducedCost;
		}
	}

	private static final class TabuMove {
		final boolean valid;
		final double cost;
		final double reducedCost;
		final MoveType type;
		final int position;
		final int primaryJob;
		final int secondaryJob;
		final int primaryTenure;
		final int secondaryTenure;

		TabuMove(double cost, double reducedCost, MoveType type, int position, int primaryJob,
				int secondaryJob, int[] tabuTenure) {
			this.valid = true;
			this.cost = cost;
			this.reducedCost = reducedCost;
			this.type = type;
			this.position = position;
			this.primaryJob = primaryJob;
			this.secondaryJob = secondaryJob;
			this.primaryTenure = primaryJob >= 0 && primaryJob < tabuTenure.length ? tabuTenure[primaryJob] : 0;
			this.secondaryTenure = secondaryJob >= 0 && secondaryJob < tabuTenure.length ? tabuTenure[secondaryJob] : 0;
		}

		private TabuMove() {
			this.valid = false;
			this.cost = Utility.big_M;
			this.reducedCost = Utility.big_M;
			this.type = MoveType.ADD;
			this.position = -1;
			this.primaryJob = -1;
			this.secondaryJob = -1;
			this.primaryTenure = 0;
			this.secondaryTenure = 0;
		}

		static TabuMove invalid() {
			return new TabuMove();
		}

		boolean isTabu(int iter) {
			if (type == MoveType.EXCHANGE) {
				return iter < primaryTenure || iter < secondaryTenure;
			}
			return iter < primaryTenure;
		}
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
