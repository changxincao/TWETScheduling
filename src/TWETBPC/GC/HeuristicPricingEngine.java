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
import TWETBPC.CUT.SubsetRowCutEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
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

		SriPricingContext sriContext = SriPricingContext.from(lp, config, data.n);
		ArrayList<TWETColumn> seeds = collectSeedColumnsBySortedPrefix(lp, sriContext);
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
			tabuSearch(seed.getSequence(), lp, sriContext, activeSignatures, generatedSignatures, negativeCandidates);
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

	private ArrayList<TWETColumn> collectSeedColumnsBySortedPrefix(final LP lp, SriPricingContext sriContext) {
		int limit = Math.max(0, config.heuristicPricingSeedColumns);
		if (limit == 0) {
			return new ArrayList<TWETColumn>();
		}
		ArrayList<ScoredSeed> candidates = new ArrayList<ScoredSeed>(lp.getRestrictedColumnIds().size());
		for (int columnId : lp.getRestrictedColumnIds()) {
			TWETColumn column = lp.getPool().getColumn(columnId);
			double sriPenalty = sriContext.isActive() ? sriContext.penalty(column.getSequence()) : 0.0;
			candidates.add(new ScoredSeed(column, reducedCost(column.getSequence(), column.getCost(), lp, sriPenalty)));
		}
		Collections.sort(candidates, new Comparator<ScoredSeed>() {
			@Override
			public int compare(ScoredSeed a, ScoredSeed b) {
				return compareScoredSeed(a, b);
			}
		});

		ArrayList<TWETColumn> seeds = new ArrayList<TWETColumn>(Math.min(limit, candidates.size()));
		for (int i = 0; i < candidates.size() && seeds.size() < limit; i++) {
			TWETColumn column = candidates.get(i).column;
			if (isSequenceCompatible(lp.getNode(), column.getSequence())) {
				seeds.add(column);
			}
		}
		return seeds;
	}

	private static int compareScoredSeed(ScoredSeed a, ScoredSeed b) {
		if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
			return -1;
		}
		if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
			return 1;
		}
		int sizeCompare = Integer.compare(a.column.size(), b.column.size());
		if (sizeCompare != 0) {
			return sizeCompare;
		}
		return Integer.compare(a.column.getId(), b.column.getId());
	}

	private void tabuSearch(List<Integer> seed, LP lp, SriPricingContext sriContext,
			HashSet<SequenceSignature> activeSignatures, HashSet<SequenceSignature> generatedSignatures,
			ArrayList<ScoredSequence> negativeCandidates) {
		TabuRouteState state = new TabuRouteState(seed, sriContext);
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
		if (sequence.isEmpty() || Utility.isBigMValue(cost)) {
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
		if (node == null) {
			return true;
		}
		if (isPricingArcForbidden(node, 0, sequence.get(0).intValue())) {
			return false;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (isPricingArcForbidden(node, sequence.get(i - 1).intValue(), sequence.get(i).intValue())) {
				return false;
			}
		}
		return !isPricingArcForbidden(node, sequence.get(sequence.size() - 1).intValue(), node.sinkId());
	}

	private boolean isPricingArcForbidden(Node node, int from, int to) {
		return node.isArcForbidden(from, to)
				|| (!ignorePricingOnlyArcsForNode(node) && node.isPricingOnlyArcForbidden(from, to));
	}

	private boolean ignorePricingOnlyArcsForNode(Node node) {
		return node != null && config.debugIgnorePricingOnlyArcsAtNode >= 0
				&& node.id == config.debugIgnorePricingOnlyArcsAtNode;
	}

	private double reducedCost(List<Integer> sequence, double cost, LP lp, double sriPenalty) {
		if (sequence.isEmpty() || Utility.isBigMValue(cost)) {
			return Utility.big_M;
		}
		double reducedCost = cost - lp.getMachineDual() + sriPenalty;
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
		private final SriPricingContext sriContext;
		private int[] sriCounts;
		private double sriPenalty;

		TabuRouteState(List<Integer> seed, SriPricingContext sriContext) {
			this.sequence = new ArrayList<Integer>(seed);
			this.used = new boolean[data.n + 1];
			this.tabuTenure = new int[data.n + 1];
			this.sriContext = sriContext;
			rebuild();
		}

		boolean isValid() {
			return !sequence.isEmpty() && !Utility.isBigMValue(cost);
		}

		double reducedCost(LP lp) {
			currentReducedCost = HeuristicPricingEngine.this.reducedCost(sequence, cost, lp, sriPenalty);
			return currentReducedCost;
		}

		TabuMove evaluateRemove(int pos, LP lp) {
			if (sequence.size() <= 1) {
				return TabuMove.invalid();
			}
			// 2026-05-24: 分支禁弧检查是 O(1) 的便宜剪枝，先做，避免无效候选先走 merge2 评估。
			if (!isRemoveCompatible(pos, lp.getNode())) {
				return TabuMove.invalid();
			}
			double candidateCost = removeCost(pos);
			int removedJob = sequence.get(pos).intValue();
			if (Utility.isBigMValue(candidateCost)) {
				return TabuMove.invalid();
			}
			double rc = reducedCostAfterRemove(pos, removedJob, candidateCost, lp);
			return new TabuMove(candidateCost, rc, MoveType.REMOVE, pos, removedJob, -1, tabuTenure);
		}

		TabuMove evaluateAdd(int job, int pos, LP lp) {
			// 2026-05-24: add/exchange 的真正代价在 merge3Segments，先用兼容性判断挡掉禁弧候选。
			if (!isInsertCompatible(pos, job, false, lp.getNode())) {
				return TabuMove.invalid();
			}
			double candidateCost = insertOrReplaceCost(pos, job, false);
			if (Utility.isBigMValue(candidateCost)) {
				return TabuMove.invalid();
			}
			double rc = reducedCostAfterAdd(pos, job, candidateCost, lp);
			return new TabuMove(candidateCost, rc, MoveType.ADD, pos, job, -1, tabuTenure);
		}

		TabuMove evaluateExchange(int job, int pos, LP lp) {
			if (!isInsertCompatible(pos, job, true, lp.getNode())) {
				return TabuMove.invalid();
			}
			double candidateCost = insertOrReplaceCost(pos, job, true);
			int removedJob = sequence.get(pos).intValue();
			if (Utility.isBigMValue(candidateCost)) {
				return TabuMove.invalid();
			}
			double rc = reducedCostAfterExchange(pos, job, removedJob, candidateCost, lp);
			return new TabuMove(candidateCost, rc, MoveType.EXCHANGE, pos, job, removedJob, tabuTenure);
		}

		void apply(TabuMove move, int tenureUntil) {
			if (move.type == MoveType.REMOVE) {
				applyRemove(move.position, move.primaryJob);
			} else if (move.type == MoveType.ADD) {
				applyAdd(move.position, move.primaryJob);
			} else {
				applyExchange(move.position, move.primaryJob, move.secondaryJob);
			}
			if (move.primaryJob >= 1 && move.primaryJob < tabuTenure.length) {
				tabuTenure[move.primaryJob] = tenureUntil;
			}
			if (move.secondaryJob >= 1 && move.secondaryJob < tabuTenure.length) {
				tabuTenure[move.secondaryJob] = tenureUntil;
			}
			this.currentReducedCost = move.reducedCost;
		}

		private void applyRemove(int pos, int removedJob) {
			PiecewiseLinearFunction[] oldForward = forward;
			PiecewiseLinearFunction[] oldBackward = backward;
			this.sequence.remove(pos);
			if (removedJob >= 1 && removedJob <= data.n) {
				used[removedJob] = false;
			}
			if (sriContext.isSequenceBased()) {
				this.sriCounts = sriContext.initialCounts(sequence);
				this.sriPenalty = sriContext.penalty(sequence);
			} else {
				this.sriPenalty += sriContext.applyRemove(sriCounts, removedJob);
			}
			this.forward = new PiecewiseLinearFunction[sequence.size()];
			this.backward = new PiecewiseLinearFunction[sequence.size()];
			if (pos > 0) {
				System.arraycopy(oldForward, 0, forward, 0, pos);
			}
			if (pos < sequence.size()) {
				System.arraycopy(oldBackward, pos + 1, backward, pos, sequence.size() - pos);
			}
			recomputeForwardFrom(pos);
			recomputeBackwardDownTo(pos - 1);
			updateCost();
		}

		private void applyAdd(int pos, int job) {
			PiecewiseLinearFunction[] oldForward = forward;
			PiecewiseLinearFunction[] oldBackward = backward;
			this.sequence.add(pos, Integer.valueOf(job));
			if (job >= 1 && job <= data.n) {
				used[job] = true;
			}
			if (sriContext.isSequenceBased()) {
				this.sriCounts = sriContext.initialCounts(sequence);
				this.sriPenalty = sriContext.penalty(sequence);
			} else {
				this.sriPenalty += sriContext.applyAdd(sriCounts, job);
			}
			this.forward = new PiecewiseLinearFunction[sequence.size()];
			this.backward = new PiecewiseLinearFunction[sequence.size()];
			if (pos > 0) {
				System.arraycopy(oldForward, 0, forward, 0, pos);
			}
			if (pos < oldBackward.length) {
				System.arraycopy(oldBackward, pos, backward, pos + 1, oldBackward.length - pos);
			}
			recomputeForwardFrom(pos);
			recomputeBackwardDownTo(pos);
			updateCost();
		}

		private void applyExchange(int pos, int addedJob, int removedJob) {
			this.sequence.set(pos, Integer.valueOf(addedJob));
			if (removedJob >= 1 && removedJob <= data.n) {
				used[removedJob] = false;
			}
			if (addedJob >= 1 && addedJob <= data.n) {
				used[addedJob] = true;
			}
			if (sriContext.isSequenceBased()) {
				this.sriCounts = sriContext.initialCounts(sequence);
				this.sriPenalty = sriContext.penalty(sequence);
			} else {
				this.sriPenalty += sriContext.applyRemove(sriCounts, removedJob);
				this.sriPenalty += sriContext.applyAdd(sriCounts, addedJob);
			}
			recomputeForwardFrom(pos);
			recomputeBackwardDownTo(pos);
			updateCost();
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
			return currentReducedCost + candidateCost - cost + sriRemoveDelta(pos)
					+ lp.getJobDual(removedJob) + lp.getArcDual(prev, removedJob)
					+ lp.getArcDual(removedJob, next) - lp.getArcDual(prev, next);
		}

		private double reducedCostAfterAdd(int pos, int job, double candidateCost, LP lp) {
			int prev = pos == 0 ? 0 : sequence.get(pos - 1).intValue();
			int next = pos == sequence.size() ? lp.getNode().sinkId() : sequence.get(pos).intValue();
			return currentReducedCost + candidateCost - cost + sriAddDelta(pos, job) - lp.getJobDual(job)
					- lp.getArcDual(prev, job) - lp.getArcDual(job, next) + lp.getArcDual(prev, next);
		}

		private double reducedCostAfterExchange(int pos, int job, int removedJob, double candidateCost, LP lp) {
			int prev = pos == 0 ? 0 : sequence.get(pos - 1).intValue();
			int next = pos == sequence.size() - 1 ? lp.getNode().sinkId() : sequence.get(pos + 1).intValue();
			return currentReducedCost + candidateCost - cost + sriExchangeDelta(pos, removedJob, job)
					+ lp.getJobDual(removedJob) - lp.getJobDual(job) + lp.getArcDual(prev, removedJob)
					+ lp.getArcDual(removedJob, next) - lp.getArcDual(prev, job) - lp.getArcDual(job, next);
		}

		private double sriRemoveDelta(int pos) {
			if (!sriContext.isSequenceBased()) {
				return sriContext.removeDelta(sriCounts, sequence.get(pos).intValue());
			}
			ArrayList<Integer> candidate = new ArrayList<Integer>(sequence);
			candidate.remove(pos);
			return sriContext.penalty(candidate) - sriPenalty;
		}

		private double sriAddDelta(int pos, int job) {
			if (!sriContext.isSequenceBased()) {
				return sriContext.addDelta(sriCounts, job);
			}
			ArrayList<Integer> candidate = new ArrayList<Integer>(sequence);
			candidate.add(pos, Integer.valueOf(job));
			return sriContext.penalty(candidate) - sriPenalty;
		}

		private double sriExchangeDelta(int pos, int removedJob, int addedJob) {
			if (!sriContext.isSequenceBased()) {
				return sriContext.exchangeDelta(sriCounts, removedJob, addedJob);
			}
			ArrayList<Integer> candidate = new ArrayList<Integer>(sequence);
			candidate.set(pos, Integer.valueOf(addedJob));
			return sriContext.penalty(candidate) - sriPenalty;
		}

		private boolean isRemoveCompatible(int pos, Node node) {
			int prev = pos == 0 ? 0 : sequence.get(pos - 1).intValue();
			int next = pos == sequence.size() - 1 ? node.sinkId() : sequence.get(pos + 1).intValue();
			return !isPricingArcForbidden(node, prev, next);
		}

		private boolean isInsertCompatible(int pos, int job, boolean replace, Node node) {
			int prefixEnd = pos - 1;
			int suffixStart = replace ? pos + 1 : pos;
			int prev = prefixEnd < 0 ? 0 : sequence.get(prefixEnd).intValue();
			int next = suffixStart >= sequence.size() ? node.sinkId() : sequence.get(suffixStart).intValue();
			return !isPricingArcForbidden(node, prev, job) && !isPricingArcForbidden(node, job, next);
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
			this.sriCounts = sriContext.initialCounts(sequence);
			this.sriPenalty = sriContext.penalty(sequence);
			updateCost();
		}

		private void recomputeForwardFrom(int start) {
			if (sequence.isEmpty() || start >= sequence.size()) {
				return;
			}
			for (int i = Math.max(0, start); i < sequence.size(); i++) {
				int job = sequence.get(i).intValue();
				PiecewiseLinearFunction cur;
				if (i == 0) {
					cur = data.penaltyFunction[job].copy();
					cur = cur.setDomain(data.p[job] + data.s[0][job], data.CmaxH);
					cur.shiftYInPlace(data.getSetupCost(0, job));
				} else {
					int prev = sequence.get(i - 1).intValue();
					cur = forward[i - 1].shiftX(data.s[prev][job] + data.p[job]).add(data.penaltyFunction[job]);
					cur.shiftYInPlace(data.getSetupCost(prev, job));
				}
				cur.minimizePrefixInPlace();
				forward[i] = cur;
			}
		}

		private void recomputeBackwardDownTo(int start) {
			if (sequence.isEmpty() || start < 0) {
				return;
			}
			for (int i = Math.min(start, sequence.size() - 1); i >= 0; i--) {
				int job = sequence.get(i).intValue();
				PiecewiseLinearFunction cur;
				if (i == sequence.size() - 1) {
					cur = data.penaltyFunction[job].copy();
				} else {
					int next = sequence.get(i + 1).intValue();
					cur = backward[i + 1].shiftX(-data.s[job][next] - data.p[next]).add(data.penaltyFunction[job]);
					cur.shiftYInPlace(data.getSetupCost(job, next));
				}
				cur.minimizeSuffixInPlace();
				backward[i] = cur;
			}
		}

		private void updateCost() {
			this.cost = sequence.isEmpty() || forward.length == 0 || forward[forward.length - 1] == null
					|| forward[forward.length - 1].isEmpty() ? Utility.big_M
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

	/**
	 * 2026-06-13: 启发式 pricing 的 SRI reduced-cost 上下文。
	 * 只在 partial-list ng-DSSR + subset-row cut active 时启用；否则所有 delta 为 0，保持旧启发式口径。
	 */
	private static final class SriPricingContext {
		private static final int[] EMPTY_INDICES = new int[0];
		private static final int[] EMPTY_COUNTS = new int[0];
		private static final SriPricingContext INACTIVE = new SriPricingContext(new double[0], new int[0][],
				new ArrayList<TWETCut>(), new ArrayList<Double>(), 0, false);

		private final double[] penalties;
		private final int[][] cutIndicesByJob;
		private final ArrayList<TWETCut> cuts;
		private final ArrayList<Double> duals;
		private final int jobCount;
		private final boolean sequenceBased;

		private SriPricingContext(double[] penalties, int[][] cutIndicesByJob, ArrayList<TWETCut> cuts,
				ArrayList<Double> duals, int jobCount, boolean sequenceBased) {
			this.penalties = penalties;
			this.cutIndicesByJob = cutIndicesByJob;
			this.cuts = cuts;
			this.duals = duals;
			this.jobCount = jobCount;
			this.sequenceBased = sequenceBased;
		}

		static SriPricingContext from(LP lp, TWETBPCConfig config, int jobCount) {
			List<Integer> cutIds = lp.getActiveSubsetRowPricingCutIds();
			List<Double> duals = lp.getActiveSubsetRowPricingDuals();
			if (!config.enableSubsetRowCutsForPartialDominance || !config.useGCNGBBStyleNgDssrPartialDominancePricing
					|| cutIds.isEmpty()) {
				return INACTIVE;
			}
			double[] penalties = new double[cutIds.size()];
			int[][] scopes = new int[cutIds.size()][];
			ArrayList<TWETCut> cuts = new ArrayList<TWETCut>();
			ArrayList<Double> activeDuals = new ArrayList<Double>();
			boolean sequenceBased = false;
			int[] jobOccurrences = new int[jobCount + 1];
			for (int idx = 0; idx < cutIds.size(); idx++) {
				TWETCut cut = lp.getCutPool().getCut(cutIds.get(idx).intValue());
				cuts.add(cut);
				activeDuals.add(duals.get(idx));
				if (cut.hasMemoryJobs()) {
					sequenceBased = true;
				}
				List<Integer> jobs = cut.getScopeJobs();
				scopes[idx] = new int[jobs.size()];
				for (int pos = 0; pos < jobs.size(); pos++) {
					int job = jobs.get(pos).intValue();
					scopes[idx][pos] = job;
					if (job >= 1 && job <= jobCount) {
						jobOccurrences[job]++;
					}
				}
				penalties[idx] = -duals.get(idx).doubleValue();
			}

			int[][] byJob = new int[jobCount + 1][];
			for (int job = 1; job <= jobCount; job++) {
				byJob[job] = new int[jobOccurrences[job]];
				jobOccurrences[job] = 0;
			}
			for (int idx = 0; idx < scopes.length; idx++) {
				for (int job : scopes[idx]) {
					if (job >= 1 && job <= jobCount) {
						byJob[job][jobOccurrences[job]++] = idx;
					}
				}
			}
			return new SriPricingContext(penalties, byJob, cuts, activeDuals, jobCount, sequenceBased);
		}

		boolean isActive() {
			return penalties.length > 0;
		}

		boolean isSequenceBased() {
			return sequenceBased;
		}

		int[] initialCounts(List<Integer> sequence) {
			if (penalties.length == 0 || sequenceBased) {
				return EMPTY_COUNTS;
			}
			int[] counts = new int[penalties.length];
			for (int job : sequence) {
				if (job >= 1 && job < cutIndicesByJob.length) {
					for (int cutIndex : cutIndicesByJob[job]) {
						counts[cutIndex]++;
					}
				}
			}
			return counts;
		}

		double penalty(List<Integer> sequence) {
			if (penalties.length == 0) {
				return 0.0;
			}
			if (sequenceBased) {
				return SubsetRowCutEvaluator.penalty(cuts, duals, sequence, jobCount);
			}
			return penalty(initialCounts(sequence));
		}

		double penalty(int[] counts) {
			double value = 0.0;
			for (int idx = 0; idx < counts.length; idx++) {
				if (counts[idx] >= 2) {
					value += penalties[idx];
				}
			}
			return value;
		}

		double removeDelta(int[] counts, int job) {
			if (!hasJobCuts(job)) {
				return 0.0;
			}
			double delta = 0.0;
			for (int cutIndex : cutIndicesByJob[job]) {
				delta += triggeredPenalty(cutIndex, counts[cutIndex] - 1) - triggeredPenalty(cutIndex, counts[cutIndex]);
			}
			return delta;
		}

		double addDelta(int[] counts, int job) {
			if (!hasJobCuts(job)) {
				return 0.0;
			}
			double delta = 0.0;
			for (int cutIndex : cutIndicesByJob[job]) {
				delta += triggeredPenalty(cutIndex, counts[cutIndex] + 1) - triggeredPenalty(cutIndex, counts[cutIndex]);
			}
			return delta;
		}

		double exchangeDelta(int[] counts, int removedJob, int addedJob) {
			if (!hasJobCuts(removedJob) && !hasJobCuts(addedJob)) {
				return 0.0;
			}
			double delta = removeDelta(counts, removedJob);
			if (!hasJobCuts(addedJob)) {
				return delta;
			}
			int[] removedCuts = hasJobCuts(removedJob) ? cutIndicesByJob[removedJob] : EMPTY_INDICES;
			for (int cutIndex : cutIndicesByJob[addedJob]) {
				int countAfterRemove = counts[cutIndex] - (contains(removedCuts, cutIndex) ? 1 : 0);
				delta += triggeredPenalty(cutIndex, countAfterRemove + 1) - triggeredPenalty(cutIndex, countAfterRemove);
			}
			return delta;
		}

		double applyRemove(int[] counts, int job) {
			double delta = removeDelta(counts, job);
			if (hasJobCuts(job)) {
				for (int cutIndex : cutIndicesByJob[job]) {
					counts[cutIndex]--;
				}
			}
			return delta;
		}

		double applyAdd(int[] counts, int job) {
			double delta = addDelta(counts, job);
			if (hasJobCuts(job)) {
				for (int cutIndex : cutIndicesByJob[job]) {
					counts[cutIndex]++;
				}
			}
			return delta;
		}

		private boolean hasJobCuts(int job) {
			return job >= 1 && job < cutIndicesByJob.length && cutIndicesByJob[job].length > 0;
		}

		private double triggeredPenalty(int cutIndex, int count) {
			return count >= 2 ? penalties[cutIndex] : 0.0;
		}

		private static boolean contains(int[] values, int target) {
			for (int value : values) {
				if (value == target) {
					return true;
				}
			}
			return false;
		}
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
