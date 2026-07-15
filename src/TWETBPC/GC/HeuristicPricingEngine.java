package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import HEU.Solution;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.TimeLimitChecker;
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
	private TimeLimitChecker timeLimitChecker = TimeLimitChecker.NONE;
	private final TWETColumnEvaluator evaluator;
	private final SegmentProfile[] singletonProfileCache;

	public HeuristicPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
		this.singletonProfileCache = buildSingletonProfileCache();
	}

	@Override
	public PricingResult price(LP lp) {
		return price(lp, TimeLimitChecker.NONE);
	}

	@Override
	public PricingResult price(LP lp, TimeLimitChecker timeLimitChecker) {
		this.timeLimitChecker = timeLimitChecker == null ? TimeLimitChecker.NONE : timeLimitChecker;
		if (!config.enableHeuristicPricing || config.maxHeuristicPricingColumns <= 0) {
			return PricingResult.noImprovement("Heuristic pricing disabled");
		}
		if (this.timeLimitChecker.isTimeLimitReached()) {
			return PricingResult.noImprovement("Time limit reached before heuristic pricing");
		}

		HeuristicPricingStats stats = new HeuristicPricingStats(config.diagnosticHeuristicPricingDetails);
		long phaseStart = stats.start();
		SriPricingContext sriContext = SriPricingContext.from(lp, config, data.n);
		stats.addSriContextNanos(phaseStart);
		phaseStart = stats.start();
		HeuristicWindowContext windowContext = buildHeuristicWindowContext(lp);
		stats.addWindowContextNanos(phaseStart);
		phaseStart = stats.start();
		ArrayList<TWETColumn> seeds = collectBestSeedColumns(lp, sriContext, stats);
		stats.addSeedCollectNanos(phaseStart);
		stats.seedColumns = seeds.size();
		if (seeds.isEmpty()) {
			return PricingResult.noImprovement("No active seed column for heuristic pricing" + stats.summary());
		}

		Utility.resetCurUpperBound(Utility.big_M);
		HashSet<SequenceSignature> generatedSignatures = new HashSet<SequenceSignature>();
		ArrayList<ScoredSequence> negativeCandidates = new ArrayList<ScoredSequence>();
		HeuristicCostAudit costAudit = new HeuristicCostAudit();

		phaseStart = stats.start();
		int seedOrdinal = 0;
		for (TWETColumn seed : seeds) {
			if (this.timeLimitChecker.isTimeLimitReached() || isHeuristicPoolFull(negativeCandidates)) {
				break;
			}
			long seedStart = stats.start();
			int candidatesBeforeSeed = negativeCandidates.size();
			tabuSearch(seed.getSequence(), lp, sriContext, windowContext, generatedSignatures,
					negativeCandidates, costAudit, stats);
			stats.observeSeed(seedOrdinal++, seedStart, negativeCandidates.size() - candidatesBeforeSeed);
		}
		stats.addSearchNanos(phaseStart);

		if (negativeCandidates.isEmpty()) {
			return PricingResult.noImprovement("Tabu heuristic pricing found no negative reduced-cost column"
					+ costAudit.summary() + stats.summary());
		}

		phaseStart = stats.start();
		Collections.sort(negativeCandidates, new Comparator<ScoredSequence>() {
			@Override
			public int compare(ScoredSequence a, ScoredSequence b) {
				int reducedCostCompare = Double.compare(a.reducedCost, b.reducedCost);
				if (reducedCostCompare != 0) {
					return reducedCostCompare;
				}
				return Integer.compare(a.sequence.size(), b.sequence.size());
			}
		});
		stats.addSortNanos(phaseStart);

		phaseStart = stats.start();
		ArrayList<TWETColumn> columns = new ArrayList<TWETColumn>();
		int limit = Math.min(config.maxHeuristicPricingColumns, negativeCandidates.size());
		for (int i = 0; i < limit; i++) {
			ScoredSequence candidate = negativeCandidates.get(i);
			columns.add(new TWETColumn(-1, candidate.sequence, data.n, candidate.cost, ColumnSource.PRICING_HEURISTIC,
					false));
		}
		stats.addBuildColumnsNanos(phaseStart);
		stats.returnedColumns = columns.size();
		stats.negativeCandidates = negativeCandidates.size();
		return new PricingResult(columns, true,
				"Tabu heuristic pricing generated " + columns.size() + " columns from local pool "
						+ negativeCandidates.size() + costAudit.summary() + stats.summary());
	}

	@Override
	public String getName() {
		return "HeuristicPricing";
	}

	@Override
	public boolean repeatFindFeasibleUntilExhausted() {
		return true;
	}

	private ArrayList<TWETColumn> collectBestSeedColumns(final LP lp, SriPricingContext sriContext,
			HeuristicPricingStats stats) {
		int limit = Math.max(0, config.heuristicPricingSeedColumns);
		if (limit == 0) {
			return new ArrayList<TWETColumn>();
		}
		Comparator<ScoredSeed> bestFirst = new Comparator<ScoredSeed>() {
			@Override
			public int compare(ScoredSeed a, ScoredSeed b) {
				return compareScoredSeed(a, b);
			}
		};
		// 2026-07-12: 只保留最优 K 个 seed，避免为固定小 K 排序并保存全部 active columns。
		PriorityQueue<ScoredSeed> bestSeeds = new PriorityQueue<ScoredSeed>(limit,
				Collections.reverseOrder(bestFirst));
		for (int columnId : lp.getRestrictedColumnIds()) {
			if (stats.enabled) { stats.seedScanned++; }
			TWETColumn column = lp.getPool().getColumn(columnId);
			if (!isSequenceCompatible(lp.getNode(), column.getSequence())) {
				if (stats.enabled) { stats.seedIncompatible++; }
				continue;
			}
			if (stats.enabled) { stats.seedCompatible++; }
			double sriPenalty = sriContext.isActive() ? sriContext.penalty(column.getSequence()) : 0.0;
			ScoredSeed candidate = new ScoredSeed(column,
					reducedCost(column.getSequence(), column.getCost(), lp, sriPenalty));
			if (bestSeeds.size() < limit) {
				bestSeeds.add(candidate);
			} else if (compareScoredSeed(candidate, bestSeeds.peek()) < 0) {
				bestSeeds.poll();
				bestSeeds.add(candidate);
			}
		}
		stats.seedHeapSize = bestSeeds.size();
		ArrayList<ScoredSeed> candidates = new ArrayList<ScoredSeed>(bestSeeds);
		Collections.sort(candidates, bestFirst);
		ArrayList<TWETColumn> seeds = new ArrayList<TWETColumn>(candidates.size());
		for (ScoredSeed candidate : candidates) {
			seeds.add(candidate.column);
		}
		return seeds;
	}

	private static int compareScoredSeed(ScoredSeed a, ScoredSeed b) {
		// 2026-07-03: Keep the Comparator strictly transitive; epsilon compare can break TimSort.
		int rcCompare = Double.compare(a.reducedCost, b.reducedCost);
		if (rcCompare != 0) {
			return rcCompare;
		}
		int sizeCompare = Integer.compare(a.column.size(), b.column.size());
		if (sizeCompare != 0) {
			return sizeCompare;
		}
		return Integer.compare(a.column.getId(), b.column.getId());
	}

	private void tabuSearch(List<Integer> seed, LP lp, SriPricingContext sriContext,
			HeuristicWindowContext windowContext, HashSet<SequenceSignature> generatedSignatures,
			ArrayList<ScoredSequence> negativeCandidates, HeuristicCostAudit costAudit,
			HeuristicPricingStats stats) {
		if (stats.enabled) { stats.tabuSearchCalls++; }
		long stateStart = stats.start();
		TabuRouteState state = new TabuRouteState(seed, sriContext, windowContext, stats);
		stats.addStateBuildNanos(stateStart);
		if (!state.isValid() || !isSequenceCompatible(lp.getNode(), state.sequence)) {
			if (stats.enabled) { stats.invalidSeeds++; }
			return;
		}
		if (stats.enabled) { stats.validSeeds++; }
		double bestReducedCost = state.reducedCost(lp);
		tryAddNegative(state.sequence, state.cost, bestReducedCost, lp, sriContext, windowContext,
				generatedSignatures, negativeCandidates, costAudit, stats);

		int iterations = Math.max(1, config.heuristicPricingTabuIterations);
		for (int iter = 0; iter < iterations && !isHeuristicPoolFull(negativeCandidates)
				&& !timeLimitChecker.isTimeLimitReached(); iter++) {
			if (stats.enabled) { stats.tabuIterations++; }
			long iterationStart = stats.start();
			long acceptedBeforeIteration = stats.tryAddAccepted;
			long findStart = stats.start();
			TabuMove bestMove = findBestMove(state, lp, iter, bestReducedCost, stats);
			stats.addFindBestMoveNanos(findStart);
			if (bestMove == null) {
				if (stats.enabled) { stats.noMoveBreaks++; }
				stats.observeTabuIteration(iter, iterationStart, 0L);
				break;
			}
			long applyStart = stats.start();
			state.apply(bestMove, iter + config.heuristicPricingTabuTenure);
			stats.addApplyMoveNanos(applyStart);
			if (Utility.compareLt(state.currentReducedCost, bestReducedCost)) {
				bestReducedCost = state.currentReducedCost;
			}
			tryAddNegative(state.sequence, state.cost, state.currentReducedCost, lp, sriContext, windowContext,
					generatedSignatures, negativeCandidates, costAudit, stats);
			stats.observeTabuIteration(iter, iterationStart, stats.tryAddAccepted - acceptedBeforeIteration);
		}
	}

	private TabuMove findBestMove(TabuRouteState state, LP lp, int iter, double bestReducedCost,
			HeuristicPricingStats stats) {
		if (stats.enabled) { stats.findBestMoveCalls++; }
		TabuMove bestMove = null;
		double bestMoveReducedCost = Double.POSITIVE_INFINITY;
		if (state.sequence.size() > 1) {
			for (int pos = 0; pos < state.sequence.size(); pos++) {
				TabuMove move = state.evaluateRemove(pos, lp, iter, bestReducedCost, bestMoveReducedCost);
				if (move != null) {
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
				TabuMove move = state.evaluateAdd(job, pos, lp, iter, bestReducedCost, bestMoveReducedCost);
				if (move != null) {
					bestMove = move;
					bestMoveReducedCost = move.reducedCost;
				}
			}
			for (int pos = 0; pos < state.sequence.size(); pos++) {
				TabuMove move = state.evaluateExchange(job, pos, lp, iter, bestReducedCost,
						bestMoveReducedCost);
				if (move != null) {
					bestMove = move;
					bestMoveReducedCost = move.reducedCost;
				}
			}
		}
		return bestMove;
	}
	private boolean isAcceptedCandidate(double reducedCost, MoveType type, int primaryJob, int secondaryJob,
			int[] tabuTenure, int iter, double bestReducedCost) {
		boolean tabu = type == MoveType.EXCHANGE
				? iter < tabuTenure[primaryJob] || iter < tabuTenure[secondaryJob]
				: iter < tabuTenure[primaryJob];
		// 旧 GCTabu 的 aspiration：如果候选优于历史最好 reduced cost，即使 tabu 也允许。
		return !tabu || Utility.compareLt(reducedCost, bestReducedCost);
	}

	private void tryAddNegative(List<Integer> sequence, double restrictedCost, double restrictedReducedCost, LP lp,
			SriPricingContext sriContext, HeuristicWindowContext windowContext,
			HashSet<SequenceSignature> generatedSignatures,
			ArrayList<ScoredSequence> negativeCandidates, HeuristicCostAudit costAudit,
			HeuristicPricingStats stats) {
		if (stats.enabled) { stats.tryAddCalls++; }
		if (isHeuristicPoolFull(negativeCandidates)) {
			if (stats.enabled) { stats.tryAddPoolFull++; }
			return;
		}
		if (sequence.isEmpty() || Utility.isBigMValue(restrictedCost)
				|| Utility.compareGe(restrictedReducedCost, REDUCED_COST_TOLERANCE)) {
			if (stats.enabled) { stats.tryAddRejectedByReducedCost++; }
			return;
		}
		SequenceSignature signature = new SequenceSignature(sequence);
		// Pool 对同 signature 原 ID 原地改进，可直接复用 LP 的增量 membership，避免再次扫描全部 active 列。
		int existingColumnId = lp.getPool().getColumnIdBySignature(signature);
		if ((existingColumnId >= 0 && lp.isRestrictedColumnActive(existingColumnId))
				|| generatedSignatures.contains(signature)) {
			if (stats.enabled) { stats.tryAddDuplicate++; }
			return;
		}
		if (!windowContext.requiresTrueCostRecheck()) {
			// compact window 是当前子树的硬窗口；dual profitable window 仍必须走下面的真实成本回刷。
			costAudit.observeSkippedTrueRecheck();
			if (stats.enabled) { stats.tryAddSkippedTrueRecheck++; }
			if (stats.enabled) { stats.tryAddAccepted++; }
			generatedSignatures.add(signature);
			negativeCandidates.add(new ScoredSequence(sequence, restrictedCost, restrictedReducedCost));
			return;
		}

		// dual window 只约束本轮搜索，最终返回列必须回到原始 TWET 目标口径。
		long recheckStart = stats.start();
		double trueCost = trueSequenceCost(sequence);
		stats.addTrueRecheckNanos(recheckStart);
		if (stats.enabled) { stats.trueRecheckCalls++; }
		if (Utility.isBigMValue(trueCost)) {
			if (stats.enabled) { stats.trueRecheckBigM++; }
			return;
		}
		double trueReducedCost = reducedCost(sequence, trueCost, lp, sriContext.penalty(sequence));
		costAudit.observe(restrictedCost, restrictedReducedCost, trueCost, trueReducedCost);
		if (Utility.compareLt(trueReducedCost, REDUCED_COST_TOLERANCE)) {
			if (stats.enabled) { stats.tryAddAccepted++; }
			generatedSignatures.add(signature);
			negativeCandidates.add(new ScoredSequence(sequence, trueCost, trueReducedCost));
		} else {
			if (stats.enabled) { stats.trueRecheckFiltered++; }
		}
	}

	private boolean isHeuristicPoolFull(ArrayList<ScoredSequence> negativeCandidates) {
		return negativeCandidates.size() >= config.heuristicPricingPoolSize;
	}

	private double trueSequenceCost(List<Integer> sequence) {
		return evaluator.evaluate(sequence);
	}

	private HeuristicWindowContext unrestrictedWindowContext() {
		return new HeuristicWindowContext(null, data.penaltyFunction[0], data.CmaxH, singletonProfileCache, false);
	}

	/**
	 * 2026-06-29: 启发式 pricing 使用当前 node 可用的硬时间窗缩小搜索空间。
	 * 基础 hard window 已在 data.penaltyFunction 中；这里额外叠加 root/no-cut 的 dual profitable
	 * window 和 time-indexed arc fixing 传给子树的 compact window。dual window 只用于本次搜索，
	 * 不写入 node，也不进入返回列的永久成本。
	 */
	private HeuristicWindowContext buildHeuristicWindowContext(LP lp) {
		Node node = lp == null ? null : lp.getNode();
		boolean useDualWindow = canUseDualProfitableWindow(lp);
		if (!useDualWindow && (node == null || node.countTimeIndexedPricingWindowTightenedJobs() == 0)) {
			return unrestrictedWindowContext();
		}
		PiecewiseLinearFunction[] penalties = new PiecewiseLinearFunction[data.n + 1];
		double horizon = 0.0;
		boolean restricted = false;
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			if (useDualWindow) {
				double baseline = outsourcingBaseline(job);
				double jobDual = Math.max(0.0, lp.getJobDual(job));
				if (Utility.compareLt(jobDual, baseline)) {
					hStart = Math.max(hStart, hWindowStart(job, jobDual));
					hEnd = Math.min(hEnd, hWindowEnd(job, jobDual));
				}
			}
			if (node.hasTimeIndexedPricingWindow(job)) {
				hStart = Math.max(hStart, node.getTimeIndexedPricingWindowStart(job));
				hEnd = Math.min(hEnd, node.getTimeIndexedPricingWindowEnd(job));
			}
			if (Utility.compareGt(hStart, data.hardWindowStart[job])
					|| Utility.compareLt(hEnd, data.hardWindowEnd[job])) {
				restricted = true;
				penalties[job] = Utility.compareGt(hStart, hEnd) ? null
						: data.penaltyFunction[job].setDomain(hStart, hEnd, true);
			} else {
				penalties[job] = data.penaltyFunction[job];
			}
			if (!Utility.compareGt(hStart, hEnd) && Double.isFinite(hEnd)) {
				horizon = Math.max(horizon, hEnd);
			}
		}
		if (!restricted) {
			return unrestrictedWindowContext();
		}
		if (!Utility.compareGt(horizon, 0.0)) {
			horizon = data.CmaxH;
		}
		horizon = Math.min(horizon, data.CmaxH);
		PiecewiseLinearFunction sourcePenalty = data.penaltyFunction[0].setDomain(0.0, horizon);
		SegmentProfile[] localSingletonProfiles = buildSingletonProfileCache(penalties);
		return new HeuristicWindowContext(penalties, sourcePenalty, horizon, localSingletonProfiles, useDualWindow);
	}

	private boolean canUseDualProfitableWindow(LP lp) {
		if (!config.enableHeuristicDualProfitableWindow) {
			return false;
		}
		return PricingCompatibility.canUseDualProfitableWindow(lp);
	}

	private double hWindowStart(int job, double gamma) {
		if (!Utility.compareGt(data.w_e[job], 0.0)) {
			return 0.0;
		}
		return Math.max(0.0, data.d_e[job] - gamma / data.w_e[job]);
	}

	private double hWindowEnd(int job, double gamma) {
		if (!Utility.compareGt(data.w_t[job], 0.0)) {
			return data.CmaxH;
		}
		return Math.min(data.CmaxH, data.d_l[job] + gamma / data.w_t[job]);
	}

	private double outsourcingBaseline(int job) {
		return Utility.isBigMValue(data.outsourcingCost[job]) ? Utility.big_M
				: Math.max(0.0, data.outsourcingCost[job]);
	}

	private boolean isSequenceCompatible(Node node, List<Integer> sequence) {
		if (sequence.isEmpty()) {
			return false;
		}
		if (node == null) {
			return true;
		}
		if (PricingCompatibility.containsRequiredOutsourcedJob(node, sequence)) {
			return false;
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
		private final HeuristicWindowContext windowContext;
		private int[] sriCounts;
		private byte[][] sriPrefixStates;
		private byte[][] sriSuffixStates;
		private double[] sriPrefixPenalty;
		private double[] sriSuffixPenalty;
		private double sriPenalty;
		private double lastMoveLowerBound = Double.NEGATIVE_INFINITY;
		private final HeuristicPricingStats stats;

		TabuRouteState(List<Integer> seed, SriPricingContext sriContext, HeuristicWindowContext windowContext,
				HeuristicPricingStats stats) {
			this.sequence = new ArrayList<Integer>(seed);
			this.used = new boolean[data.n + 1];
			this.tabuTenure = new int[data.n + 1];
			this.sriContext = sriContext;
			this.windowContext = windowContext;
			this.stats = stats;
			rebuild();
		}

		boolean isValid() {
			return !sequence.isEmpty() && !Utility.isBigMValue(cost);
		}

		double reducedCost(LP lp) {
			currentReducedCost = HeuristicPricingEngine.this.reducedCost(sequence, cost, lp, sriPenalty);
			return currentReducedCost;
		}

		TabuMove evaluateRemove(int pos, LP lp, int iter, double bestReducedCost, double bestMoveReducedCost) {
			if (stats.enabled) { stats.removeAttempts++; }
			long totalStart = stats.start();
			if (sequence.size() <= 1) {
				if (stats.enabled) { stats.removeBigM++; }
				stats.addRemoveTotalNanos(totalStart);
				return null;
			}
			// 分支禁弧检查是 O(1) 的便宜剪枝，先做，避免无效候选进入函数拼接。
			if (!isRemoveCompatible(pos, lp.getNode())) {
				if (stats.enabled) { stats.removeIncompatible++; }
				stats.addRemoveTotalNanos(totalStart);
				return null;
			}
			long costStart = stats.start();
			double candidateCost = removeCost(pos);
			stats.addRemoveCostNanos(costStart);
			int removedJob = sequence.get(pos).intValue();
			if (Utility.isBigMValue(candidateCost)) {
				if (stats.enabled) { stats.removeBigM++; }
				stats.addRemoveTotalNanos(totalStart);
				return null;
			}
			long rcStart = stats.start();
			double rc = reducedCostAfterRemove(pos, removedJob, candidateCost, lp);
			stats.addMoveReducedCostNanos(rcStart);
			if (stats.enabled) { stats.removeValid++; }
			if (!isAcceptedCandidate(rc, MoveType.REMOVE, removedJob, -1, tabuTenure, iter, bestReducedCost)
					|| !Utility.compareLt(rc, bestMoveReducedCost)) {
				if (stats.enabled) { stats.removeNotSelected++; }
				stats.addRemoveTotalNanos(totalStart);
				return null;
			}
			if (stats.enabled) { stats.removeSelected++; }
			stats.addRemoveTotalNanos(totalStart);
			return new TabuMove(candidateCost, rc, MoveType.REMOVE, pos, removedJob, -1);
		}

		TabuMove evaluateAdd(int job, int pos, LP lp, int iter, double bestReducedCost,
				double bestMoveReducedCost) {
			if (stats.enabled) { stats.addAttempts++; }
			long totalStart = stats.start();
			// add/exchange 的主要代价在 PWLF 构造，先用兼容性判断挡掉禁弧候选。
			if (!isInsertCompatible(pos, job, false, lp.getNode())) {
				if (stats.enabled) { stats.addIncompatible++; }
				stats.addAddTotalNanos(totalStart);
				return null;
			}
			double threshold = acceptedMoveThreshold(MoveType.ADD, job, -1, iter, bestReducedCost,
					bestMoveReducedCost);
			if (shouldPruneByMoveLowerBound(pos, job, false, lp, threshold)) {
				if (stats.enabled) { stats.addLowerBoundPruned++; }
				stats.addAddTotalNanos(totalStart);
				return null;
			}
			long costStart = stats.start();
			double candidateCost = insertOrReplaceCost(pos, job, false);
			stats.addAddCostNanos(costStart);
			if (Utility.isBigMValue(candidateCost)) {
				if (stats.enabled) { stats.addBigM++; }
				stats.addAddTotalNanos(totalStart);
				return null;
			}
			long rcStart = stats.start();
			double rc = reducedCostAfterAdd(pos, job, candidateCost, lp);
			stats.addMoveReducedCostNanos(rcStart);
			stats.observeLowerBound(lastMoveLowerBound, rc);
			if (stats.enabled) { stats.addValid++; }
			if (!isAcceptedCandidate(rc, MoveType.ADD, job, -1, tabuTenure, iter, bestReducedCost)
					|| !Utility.compareLt(rc, bestMoveReducedCost)) {
				if (stats.enabled) { stats.addNotSelected++; }
				stats.addAddTotalNanos(totalStart);
				return null;
			}
			if (stats.enabled) { stats.addSelected++; }
			stats.addAddTotalNanos(totalStart);
			return new TabuMove(candidateCost, rc, MoveType.ADD, pos, job, -1);
		}

		TabuMove evaluateExchange(int job, int pos, LP lp, int iter, double bestReducedCost,
				double bestMoveReducedCost) {
			if (stats.enabled) { stats.exchangeAttempts++; }
			long totalStart = stats.start();
			if (!isInsertCompatible(pos, job, true, lp.getNode())) {
				if (stats.enabled) { stats.exchangeIncompatible++; }
				stats.addExchangeTotalNanos(totalStart);
				return null;
			}
			int removedJob = sequence.get(pos).intValue();
			double threshold = acceptedMoveThreshold(MoveType.EXCHANGE, job, removedJob, iter, bestReducedCost,
					bestMoveReducedCost);
			if (shouldPruneByMoveLowerBound(pos, job, true, lp, threshold)) {
				if (stats.enabled) { stats.exchangeLowerBoundPruned++; }
				stats.addExchangeTotalNanos(totalStart);
				return null;
			}
			long costStart = stats.start();
			double candidateCost = insertOrReplaceCost(pos, job, true);
			stats.addExchangeCostNanos(costStart);
			if (Utility.isBigMValue(candidateCost)) {
				if (stats.enabled) { stats.exchangeBigM++; }
				stats.addExchangeTotalNanos(totalStart);
				return null;
			}
			long rcStart = stats.start();
			double rc = reducedCostAfterExchange(pos, job, removedJob, candidateCost, lp);
			stats.addMoveReducedCostNanos(rcStart);
			stats.observeLowerBound(lastMoveLowerBound, rc);
			if (stats.enabled) { stats.exchangeValid++; }
			if (!isAcceptedCandidate(rc, MoveType.EXCHANGE, job, removedJob, tabuTenure, iter, bestReducedCost)
					|| !Utility.compareLt(rc, bestMoveReducedCost)) {
				if (stats.enabled) { stats.exchangeNotSelected++; }
				stats.addExchangeTotalNanos(totalStart);
				return null;
			}
			if (stats.enabled) { stats.exchangeSelected++; }
			stats.addExchangeTotalNanos(totalStart);
			return new TabuMove(candidateCost, rc, MoveType.EXCHANGE, pos, job, removedJob);
		}
		private double acceptedMoveThreshold(MoveType type, int primaryJob, int secondaryJob, int iter,
				double bestReducedCost, double bestMoveReducedCost) {
			boolean tabu = type == MoveType.EXCHANGE
					? iter < tabuTenure[primaryJob] || iter < tabuTenure[secondaryJob]
					: iter < tabuTenure[primaryJob];
			return tabu ? Math.min(bestReducedCost, bestMoveReducedCost) : bestMoveReducedCost;
		}

		private boolean shouldPruneByMoveLowerBound(int pos, int job, boolean replace, LP lp, double threshold) {
			if (sriContext.isActive()) {
				lastMoveLowerBound = Double.NEGATIVE_INFINITY;
				return false;
			}
			long start = stats.start();
			double costLowerBound = insertOrReplaceCostLowerBound(pos, job, replace);
			double reducedCostLowerBound = replace
					? reducedCostAfterExchange(pos, job, sequence.get(pos).intValue(), costLowerBound, lp)
					: reducedCostAfterAdd(pos, job, costLowerBound, lp);
			lastMoveLowerBound = reducedCostLowerBound;
			if (stats.enabled) { stats.moveLowerBoundChecks++; }
			stats.addMoveLowerBoundNanos(start);
			return Utility.compareGt(reducedCostLowerBound, threshold);
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
				rebuildSriProfiles();
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
				rebuildSriProfiles();
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
				rebuildSriProfiles();
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
			PiecewiseLinearFunction f1 = pos == 0 ? windowContext.sourcePenalty : forward[pos - 1];
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
			PiecewiseLinearFunction prefix = prefixEnd < 0 ? windowContext.sourcePenalty : forward[prefixEnd];
			PiecewiseLinearFunction suffix = suffixStart >= sequence.size() ? windowContext.sourcePenalty
					: backward[suffixStart];
			int bridgeFrom = prefixEnd < 0 ? 0 : sequence.get(prefixEnd).intValue();
			double prefixShift = data.s[bridgeFrom][job] + data.p[job];
			int bridgeTo = suffixStart >= sequence.size() ? 0 : sequence.get(suffixStart).intValue();
			double suffixShift = suffixStart >= sequence.size() ? 0.0
					: data.s[job][bridgeTo] + data.p[bridgeTo];
			PiecewiseLinearFunction jobPenalty = windowContext.penalty(job);
			if (!shiftedOverlaps(prefix, prefixShift, jobPenalty, true)) {
				if (stats.enabled) { stats.insertFirstOverlapRejected++; }
				return Utility.big_M;
			}

			// 单 job 插入按固定序列递推：先构造 prefix + job 的前缀包络，再与 suffix 做 merge2。
			// 该口径与 TWETColumnEvaluator 一致；通用 merge3 在 compact-window BigM 边界下不适用。
			double firstBridgeCost = data.getSetupCost(bridgeFrom, job);
			double secondBridgeCost = suffixStart >= sequence.size() ? 0.0 : data.getSetupCost(job, bridgeTo);
			PiecewiseLinearFunction prefixWithJob = PiecewiseLinearFunction.addShifted(prefix, prefixShift,
					jobPenalty);
			prefixWithJob.minimizePrefixInPlace();
			if (prefixWithJob.isEmpty()) {
				prefixWithJob.release();
				return Utility.big_M;
			}
			// 2026-07-15: prefixWithJob 只用于下面这次标量 merge，不再复用。两个 setup 常数直接
			// 合并进最终 yShift，避免为每个 add/exchange 候选再扫描整条临时 PWLF。
			double cost = mergeHelper.merge2Segments(prefixWithJob, suffix, suffixShift,
					firstBridgeCost + secondBridgeCost);
			prefixWithJob.release();
			return cost;
		}
		/**
		 * 三段全局最小值之和是单任务插入成本的安全下界，只用于证明候选不可能改进当前 best move。
		 */
		private double insertOrReplaceCostLowerBound(int pos, int job, boolean replace) {
			int prefixEnd = pos - 1;
			int suffixStart = replace ? pos + 1 : pos;
			PiecewiseLinearFunction prefix = prefixEnd < 0 ? windowContext.sourcePenalty : forward[prefixEnd];
			PiecewiseLinearFunction suffix = suffixStart >= sequence.size() ? windowContext.sourcePenalty
					: backward[suffixStart];
			PiecewiseLinearFunction single = windowContext.singletonProfiles[job].forward;
			if (prefix == null || prefix.isEmpty() || suffix == null || suffix.isEmpty()
					|| single == null || single.isEmpty()) {
				return Double.NEGATIVE_INFINITY;
			}
			int bridgeFrom = prefixEnd < 0 ? 0 : sequence.get(prefixEnd).intValue();
			int bridgeTo = suffixStart >= sequence.size() ? 0 : sequence.get(suffixStart).intValue();
			double prefixMin = prefix.tail.getValue(prefix.tail.end);
			double jobMin = single.tail.getValue(single.tail.end);
			double suffixMin = suffix.head.getValue(suffix.head.start);
			double bridgeCost = data.getSetupCost(bridgeFrom, job)
					+ (suffixStart >= sequence.size() ? 0.0 : data.getSetupCost(job, bridgeTo));
			return prefixMin + jobMin + suffixMin + bridgeCost;
		}

		private boolean shiftedOverlaps(PiecewiseLinearFunction shifted, double delta, PiecewiseLinearFunction other,
				boolean trimShiftedToDomain) {
			if (shifted == null || other == null || shifted.isEmpty() || other.isEmpty()) {
				return false;
			}
			double shiftedStart = shifted.head.start + delta;
			double shiftedEnd = shifted.tail.end + delta;
			if (trimShiftedToDomain) {
				shiftedStart = Math.max(shiftedStart, shifted.domainStart);
				shiftedEnd = Math.min(shiftedEnd, shifted.domainEnd);
			}
			return Utility.compareLe(Math.max(shiftedStart, other.head.start), Math.min(shiftedEnd, other.tail.end));
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
			return sequenceBasedPenalty(pos, pos + 1) - sriPenalty;
		}

		private double sriAddDelta(int pos, int job) {
			if (!sriContext.isSequenceBased()) {
				return sriContext.addDelta(sriCounts, job);
			}
			return sequenceBasedPenalty(pos, pos, job) - sriPenalty;
		}

		private double sriExchangeDelta(int pos, int removedJob, int addedJob) {
			if (!sriContext.isSequenceBased()) {
				return sriContext.exchangeDelta(sriCounts, removedJob, addedJob);
			}
			return sequenceBasedPenalty(pos, pos + 1, addedJob) - sriPenalty;
		}

		private double sequenceBasedPenalty(int prefixLength, int suffixStart, int... middleJobs) {
			byte[] states = sriContext.copyStates(sriPrefixStates[prefixLength]);
			double value = sriPrefixPenalty[prefixLength];
			int previous = prefixLength == 0 ? 0 : sequence.get(prefixLength - 1).intValue();
			for (int job : middleJobs) {
				value += sriContext.applyForwardExtension(states, previous, job);
				previous = job;
			}
			value += sriSuffixPenalty[suffixStart];
			if (suffixStart < sequence.size()) {
				value += sriContext.joinShift(states, previous, sequence.get(suffixStart).intValue(),
						sriSuffixStates[suffixStart]);
			}
			return value;
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
			this.forward = buildForwardProfile(sequence, true, windowContext);
			this.backward = buildBackwardProfile(sequence, windowContext);
			if (sriContext.isSequenceBased()) {
				rebuildSriProfiles();
			} else {
				this.sriCounts = sriContext.initialCounts(sequence);
				this.sriPenalty = sriContext.penalty(sequence);
			}
			updateCost();
		}

		private void rebuildSriProfiles() {
			int size = sequence.size();
			int cutCount = sriContext.cutCount();
			sriPrefixStates = new byte[size + 1][];
			sriSuffixStates = new byte[size + 1][];
			sriPrefixPenalty = new double[size + 1];
			sriSuffixPenalty = new double[size + 1];
			sriPrefixStates[0] = new byte[cutCount];
			for (int i = 0; i < size; i++) {
				byte[] states = sriContext.copyStates(sriPrefixStates[i]);
				int from = i == 0 ? 0 : sequence.get(i - 1).intValue();
				double shift = sriContext.applyForwardExtension(states, from, sequence.get(i).intValue());
				sriPrefixStates[i + 1] = states;
				sriPrefixPenalty[i + 1] = sriPrefixPenalty[i] + shift;
			}
			sriSuffixStates[size] = new byte[cutCount];
			for (int i = size - 1; i >= 0; i--) {
				byte[] states = sriContext.copyStates(sriSuffixStates[i + 1]);
				int to = i == size - 1 ? data.n + 1 : sequence.get(i + 1).intValue();
				double shift = sriContext.applyBackwardPrepend(states, sequence.get(i).intValue(), to);
				sriSuffixStates[i] = states;
				sriSuffixPenalty[i] = sriSuffixPenalty[i + 1] + shift;
			}
			this.sriCounts = new int[0];
			this.sriPenalty = sriPrefixPenalty[size];
		}

		private void recomputeForwardFrom(int start) {
			if (sequence.isEmpty() || start >= sequence.size()) {
				return;
			}
			for (int i = Math.max(0, start); i < sequence.size(); i++) {
				int job = sequence.get(i).intValue();
				PiecewiseLinearFunction cur;
				if (i == 0) {
					PiecewiseLinearFunction penalty = windowContext.penalty(job);
					cur = penalty == null ? emptyFunction() : penalty.copy();
					cur = cur.setDomain(data.p[job] + data.s[0][job], windowContext.horizon);
					cur.shiftYInPlace(data.getSetupCost(0, job));
				} else {
					int prev = sequence.get(i - 1).intValue();
					PiecewiseLinearFunction penalty = windowContext.penalty(job);
					cur = penalty == null ? emptyFunction()
							: forward[i - 1].shiftX(data.s[prev][job] + data.p[job]).add(penalty);
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
					PiecewiseLinearFunction penalty = windowContext.penalty(job);
					cur = penalty == null ? emptyFunction() : penalty.copy();
				} else {
					int next = sequence.get(i + 1).intValue();
					PiecewiseLinearFunction penalty = windowContext.penalty(job);
					cur = penalty == null ? emptyFunction()
							: backward[i + 1].shiftX(-data.s[job][next] - data.p[next]).add(penalty);
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

	private PiecewiseLinearFunction[] buildForwardProfile(List<Integer> jobs, boolean includeDepotStart,
			HeuristicWindowContext windowContext) {
		PiecewiseLinearFunction[] result = new PiecewiseLinearFunction[jobs.size()];
		for (int i = 0; i < jobs.size(); i++) {
			int job = jobs.get(i).intValue();
			PiecewiseLinearFunction cur;
			if (i == 0) {
				PiecewiseLinearFunction penalty = windowContext.penalty(job);
				cur = penalty == null ? emptyFunction() : penalty.copy();
				if (includeDepotStart) {
					cur = cur.setDomain(data.p[job] + data.s[0][job], windowContext.horizon);
					cur.shiftYInPlace(data.getSetupCost(0, job));
				}
			} else {
				int prev = jobs.get(i - 1).intValue();
				PiecewiseLinearFunction penalty = windowContext.penalty(job);
				cur = penalty == null ? emptyFunction()
						: result[i - 1].shiftX(data.s[prev][job] + data.p[job]).add(penalty);
				cur.shiftYInPlace(data.getSetupCost(prev, job));
			}
			cur.minimizePrefixInPlace();
			result[i] = cur;
		}
		return result;
	}

	private PiecewiseLinearFunction[] buildBackwardProfile(List<Integer> jobs, HeuristicWindowContext windowContext) {
		PiecewiseLinearFunction[] result = new PiecewiseLinearFunction[jobs.size()];
		for (int i = jobs.size() - 1; i >= 0; i--) {
			int job = jobs.get(i).intValue();
			PiecewiseLinearFunction cur;
			if (i == jobs.size() - 1) {
				PiecewiseLinearFunction penalty = windowContext.penalty(job);
				cur = penalty == null ? emptyFunction() : penalty.copy();
			} else {
				int next = jobs.get(i + 1).intValue();
				PiecewiseLinearFunction penalty = windowContext.penalty(job);
				cur = penalty == null ? emptyFunction()
						: result[i + 1].shiftX(-data.s[job][next] - data.p[next]).add(penalty);
				cur.shiftYInPlace(data.getSetupCost(job, next));
			}
			cur.minimizeSuffixInPlace();
			result[i] = cur;
		}
		return result;
	}

	private SegmentProfile[] buildSingletonProfileCache() {
		return buildSingletonProfileCache(null);
	}

	private SegmentProfile[] buildSingletonProfileCache(PiecewiseLinearFunction[] penalties) {
		SegmentProfile[] cache = new SegmentProfile[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			cache[job] = buildSingletonProfile(job, penalties);
		}
		return cache;
	}

	private SegmentProfile buildSingletonProfile(int job, PiecewiseLinearFunction[] penalties) {
		PiecewiseLinearFunction penalty = penalties == null ? data.penaltyFunction[job] : penalties[job];
		PiecewiseLinearFunction forward = penalty == null ? emptyFunction() : penalty.copy();
		forward.minimizePrefixInPlace();
		PiecewiseLinearFunction backward = penalty == null ? emptyFunction() : penalty.copy();
		backward.minimizeSuffixInPlace();
		return new SegmentProfile(forward, backward);
	}

	private PiecewiseLinearFunction emptyFunction() {
		return new PiecewiseLinearFunction();
	}

	private final class HeuristicWindowContext {
		private final PiecewiseLinearFunction[] penalties;
		private final PiecewiseLinearFunction sourcePenalty;
		private final double horizon;
		private final SegmentProfile[] singletonProfiles;
		private final boolean requiresTrueCostRecheck;

		HeuristicWindowContext(PiecewiseLinearFunction[] penalties, PiecewiseLinearFunction sourcePenalty,
				double horizon, SegmentProfile[] singletonProfiles, boolean requiresTrueCostRecheck) {
			this.penalties = penalties;
			this.sourcePenalty = sourcePenalty;
			this.horizon = horizon;
			this.singletonProfiles = singletonProfiles;
			this.requiresTrueCostRecheck = requiresTrueCostRecheck;
		}

		PiecewiseLinearFunction penalty(int job) {
			return penalties == null ? data.penaltyFunction[job] : penalties[job];
		}

		SegmentProfile singletonProfile(int job) {
			SegmentProfile cached = singletonProfiles[job];
			return new SegmentProfile(cached.forward.copy(), cached.backward);
		}

		boolean requiresTrueCostRecheck() {
			return requiresTrueCostRecheck;
		}
	}

	/**
	 * 2026-06-13: 启发式 pricing 的 SRI reduced-cost 上下文。
	 * 只在对应 cut pricing 模式启用；否则所有 SRI delta 为 0，保持旧启发式入口。
	 */
	private static final class HeuristicPricingStats {

		final boolean enabled;
		int seedScanned;
		int seedCompatible;
		int seedIncompatible;
		int seedHeapSize;
		int seedColumns;
		int tabuSearchCalls;
		int validSeeds;
		int invalidSeeds;
		int tabuIterations;
		int noMoveBreaks;
		int findBestMoveCalls;
		int appliedMoves;
		int returnedColumns;
		int negativeCandidates;
		long sriContextNanos;
		long windowContextNanos;
		long seedCollectNanos;
		long searchNanos;
		long sortNanos;
		long buildColumnsNanos;
		long stateBuildNanos;
		long findBestMoveNanos;
		long applyMoveNanos;
		long removeTotalNanos;
		long addTotalNanos;
		long exchangeTotalNanos;
		long removeCostNanos;
		long addCostNanos;
		long exchangeCostNanos;
		long moveReducedCostNanos;
		long moveLowerBoundNanos;
		long trueRecheckNanos;
		long removeAttempts;
		long addAttempts;
		long exchangeAttempts;
		long removeIncompatible;
		long addIncompatible;
		long exchangeIncompatible;
		long removeBigM;
		long addBigM;
		long exchangeBigM;
		long removeValid;
		long addValid;
		long exchangeValid;
		long removeNotSelected;
		long addNotSelected;
		long exchangeNotSelected;
		long removeSelected;
		long addSelected;
		long exchangeSelected;
		long moveLowerBoundChecks;
		long addLowerBoundPruned;
		long exchangeLowerBoundPruned;
		long moveLowerBoundViolations;
		double maxMoveLowerBoundViolation;
		long insertFirstOverlapRejected;
		long tryAddCalls;
		long tryAddPoolFull;
		long tryAddRejectedByReducedCost;
		long tryAddDuplicate;
		long tryAddAccepted;
		long tryAddSkippedTrueRecheck;
		long trueRecheckCalls;
		long trueRecheckBigM;
		long trueRecheckFiltered;
		final long[] seedBinCalls = new long[6];
		final long[] seedBinAdded = new long[6];
		final long[] seedBinNanos = new long[6];
		final long[] iterationBinCalls = new long[5];
		final long[] iterationBinAdded = new long[5];
		final long[] iterationBinNanos = new long[5];

		HeuristicPricingStats(boolean enabled) {
			this.enabled = enabled;
		}

		long start() {
			return enabled ? System.nanoTime() : 0L;
		}

		private long elapsed(long start) {
			return enabled && start != 0L ? System.nanoTime() - start : 0L;
		}

		void addSriContextNanos(long start) { if (enabled) sriContextNanos += elapsed(start); }
		void addWindowContextNanos(long start) { if (enabled) windowContextNanos += elapsed(start); }
		void addSeedCollectNanos(long start) { if (enabled) seedCollectNanos += elapsed(start); }
		void addSearchNanos(long start) { if (enabled) searchNanos += elapsed(start); }
		void addSortNanos(long start) { if (enabled) sortNanos += elapsed(start); }
		void addBuildColumnsNanos(long start) { if (enabled) buildColumnsNanos += elapsed(start); }
		void addStateBuildNanos(long start) { if (enabled) stateBuildNanos += elapsed(start); }
		void addFindBestMoveNanos(long start) { if (enabled) findBestMoveNanos += elapsed(start); }
		void addApplyMoveNanos(long start) {
			if (enabled) {
				appliedMoves++;
				applyMoveNanos += elapsed(start);
			}
		}
		void addRemoveTotalNanos(long start) { if (enabled) removeTotalNanos += elapsed(start); }
		void addAddTotalNanos(long start) { if (enabled) addTotalNanos += elapsed(start); }
		void addExchangeTotalNanos(long start) { if (enabled) exchangeTotalNanos += elapsed(start); }
		void addRemoveCostNanos(long start) { if (enabled) removeCostNanos += elapsed(start); }
		void addAddCostNanos(long start) { if (enabled) addCostNanos += elapsed(start); }
		void addExchangeCostNanos(long start) { if (enabled) exchangeCostNanos += elapsed(start); }
		void addMoveReducedCostNanos(long start) { if (enabled) moveReducedCostNanos += elapsed(start); }
		void addMoveLowerBoundNanos(long start) { if (enabled) moveLowerBoundNanos += elapsed(start); }
		void addTrueRecheckNanos(long start) { if (enabled) trueRecheckNanos += elapsed(start); }

		void observeSeed(int seedOrdinal, long start, long added) {
			if (!enabled) {
				return;
			}
			int bin = Math.min(seedOrdinal / 5, seedBinCalls.length - 1);
			seedBinCalls[bin]++;
			seedBinAdded[bin] += added;
			seedBinNanos[bin] += elapsed(start);
		}

		void observeTabuIteration(int iteration, long start, long added) {
			if (!enabled) {
				return;
			}
			int bin = Math.min(iteration / 10, iterationBinCalls.length - 1);
			iterationBinCalls[bin]++;
			iterationBinAdded[bin] += added;
			iterationBinNanos[bin] += elapsed(start);
		}

		void observeLowerBound(double lowerBound, double exactValue) {
			if (!enabled || !Double.isFinite(lowerBound) || !Double.isFinite(exactValue)) {
				return;
			}
			double violation = lowerBound - exactValue;
			if (Utility.compareGt(violation, 0.0)) {
				moveLowerBoundViolations++;
				maxMoveLowerBoundViolation = Math.max(maxMoveLowerBoundViolation, violation);
			}
		}

		String summary() {
			if (!enabled) {
				return "";
			}
			return ", heuristicStats phaseMs sri/window/seed/search/sort/buildCols="
					+ ms(sriContextNanos) + "/" + ms(windowContextNanos) + "/" + ms(seedCollectNanos)
					+ "/" + ms(searchNanos) + "/" + ms(sortNanos) + "/" + ms(buildColumnsNanos)
					+ ", seed scan/compatible/incompat/heap/used=" + seedScanned + "/" + seedCompatible
					+ "/" + seedIncompatible + "/" + seedHeapSize + "/" + seedColumns
					+ ", tabu calls/valid/invalid/iters/noMove/apply=" + tabuSearchCalls + "/" + validSeeds
					+ "/" + invalidSeeds + "/" + tabuIterations + "/" + noMoveBreaks + "/" + appliedMoves

					+ ", seedBins5 calls=" + vector(seedBinCalls) + ", added=" + vector(seedBinAdded)
					+ ", ms=" + millisVector(seedBinNanos)
					+ ", iterBins10 calls=" + vector(iterationBinCalls) + ", added=" + vector(iterationBinAdded)
					+ ", ms=" + millisVector(iterationBinNanos)
					+ ", coreMs state/find/apply/rc=" + ms(stateBuildNanos) + "/" + ms(findBestMoveNanos)
					+ "/" + ms(applyMoveNanos) + "/" + ms(moveReducedCostNanos)
					+ ", moveAttempts rem/add/ex=" + removeAttempts + "/" + addAttempts + "/" + exchangeAttempts
					+ ", moveCompatReject rem/add/ex=" + removeIncompatible + "/" + addIncompatible + "/"
					+ exchangeIncompatible
					+ ", moveBigM rem/add/ex=" + removeBigM + "/" + addBigM + "/" + exchangeBigM
					+ ", moveValid rem/add/ex=" + removeValid + "/" + addValid + "/" + exchangeValid
					+ ", moveNotSelected rem/add/ex=" + removeNotSelected + "/" + addNotSelected + "/"
					+ exchangeNotSelected
					+ ", moveSelected rem/add/ex=" + removeSelected + "/" + addSelected + "/" + exchangeSelected
					+ ", moveLB checks/prunedAdd/prunedEx/ms/viol/max=" + moveLowerBoundChecks + "/"
					+ addLowerBoundPruned + "/" + exchangeLowerBoundPruned + "/" + ms(moveLowerBoundNanos) + "/"
					+ moveLowerBoundViolations + "/" + String.format("%.9f", maxMoveLowerBoundViolation)
					+ ", moveMs total rem/add/ex=" + ms(removeTotalNanos) + "/" + ms(addTotalNanos)
					+ "/" + ms(exchangeTotalNanos)
					+ ", moveMs cost rem/add/ex=" + ms(removeCostNanos) + "/" + ms(addCostNanos)
					+ "/" + ms(exchangeCostNanos)
					+ ", insertOverlapReject=" + insertFirstOverlapRejected
					+ ", tryAdd calls/accepted/dup/rcSkip/poolFull=" + tryAddCalls + "/" + tryAddAccepted
					+ "/" + tryAddDuplicate + "/" + tryAddRejectedByReducedCost + "/" + tryAddPoolFull
					+ ", trueRecheck calls/ms/bigM/filtered/skipped=" + trueRecheckCalls + "/" + ms(trueRecheckNanos)
					+ "/" + trueRecheckBigM + "/" + trueRecheckFiltered + "/" + tryAddSkippedTrueRecheck
					+ ", output candidates/returned=" + negativeCandidates + "/" + returnedColumns;
		}

		private static String ms(long nanos) {
			return String.format("%.3f", nanos / 1_000_000.0);
		}

		private static String vector(long[] values) {
			StringBuilder result = new StringBuilder();
			for (int i = 0; i < values.length; i++) {
				if (i > 0) {
					result.append('/');
				}
				result.append(values[i]);
			}
			return result.toString();
		}

		private static String millisVector(long[] nanos) {
			StringBuilder result = new StringBuilder();
			for (int i = 0; i < nanos.length; i++) {
				if (i > 0) {
					result.append('/');
				}
				result.append(ms(nanos[i]));
			}
			return result.toString();
		}
	}
	private static final class SriPricingContext {
		private static final int[] EMPTY_INDICES = new int[0];
		private static final int[] EMPTY_COUNTS = new int[0];
		private static final SriPricingContext INACTIVE = new SriPricingContext(new double[0], new int[0][],
				new boolean[0][], new boolean[0][], new boolean[0], 0, false);

		private final double[] penalties;
		private final int[][] cutIndicesByJob;
		private final boolean[][] memoryByCut;
		private final boolean[][] arcMemoryByCut;
		private final boolean[] arcMemoryCut;
		private final int jobCount;
		private final boolean sequenceBased;

		private SriPricingContext(double[] penalties, int[][] cutIndicesByJob, boolean[][] memoryByCut,
				boolean[][] arcMemoryByCut, boolean[] arcMemoryCut, int jobCount, boolean sequenceBased) {
			this.penalties = penalties;
			this.cutIndicesByJob = cutIndicesByJob;
			this.memoryByCut = memoryByCut;
			this.arcMemoryByCut = arcMemoryByCut;
			this.arcMemoryCut = arcMemoryCut;
			this.jobCount = jobCount;
			this.sequenceBased = sequenceBased;
		}

		static SriPricingContext from(LP lp, TWETBPCConfig config, int jobCount) {
			List<Integer> cutIds = lp.getActiveSubsetRowPricingCutIds();
			List<Double> duals = lp.getActiveSubsetRowPricingDuals();
			boolean partialNgSri = config.enableSubsetRowCutsForPartialDominance
					&& config.useGCNGBBStyleNgDssrPartialDominancePricing;
			boolean timeIndexedSri = config.enableSubsetRowCutsForTimeIndexedGraph
					&& config.useTimeIndexedGraphPricing
					&& config.useTimeIndexedGraphRank1CutPricing;
			if ((!partialNgSri && !timeIndexedSri) || cutIds.isEmpty()) {
				return INACTIVE;
			}
			double[] penalties = new double[cutIds.size()];
			int[][] scopes = new int[cutIds.size()][];
			boolean[][] memoryByCut = new boolean[cutIds.size()][];
			boolean[][] arcMemoryByCut = new boolean[cutIds.size()][];
			boolean[] arcMemoryCut = new boolean[cutIds.size()];
			boolean sequenceBased = false;
			int[] jobOccurrences = new int[jobCount + 1];
			int arcTableSize = (jobCount + 2) * (jobCount + 2);
			for (int idx = 0; idx < cutIds.size(); idx++) {
				TWETCut cut = lp.getCutPool().getCut(cutIds.get(idx).intValue());
				if (cut.hasLimitedMemory()) {
					sequenceBased = true;
				}
				memoryByCut[idx] = new boolean[jobCount + 1];
				arcMemoryByCut[idx] = new boolean[arcTableSize];
				if (cut.hasMemoryArcs()) {
					arcMemoryCut[idx] = true;
					for (Long encoded : cut.getMemoryArcs()) {
						long key = encoded.longValue();
						int from = (int) (key >> 32);
						int to = (int) key;
						if (from >= 0 && from <= jobCount + 1 && to >= 0 && to <= jobCount + 1) {
							arcMemoryByCut[idx][arcIndex(jobCount, from, to)] = true;
						}
					}
				} else if (cut.hasMemoryJobs()) {
					for (int memoryJob : cut.getMemoryJobs()) {
						if (memoryJob >= 1 && memoryJob <= jobCount) {
							memoryByCut[idx][memoryJob] = true;
						}
					}
				} else {
					for (int job = 1; job <= jobCount; job++) {
						memoryByCut[idx][job] = true;
					}
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
			return new SriPricingContext(penalties, byJob, memoryByCut, arcMemoryByCut, arcMemoryCut, jobCount,
					sequenceBased);
		}

		boolean isActive() {
			return penalties.length > 0;
		}

		boolean isSequenceBased() {
			return sequenceBased;
		}

		int cutCount() {
			return penalties.length;
		}

		byte[] copyStates(byte[] states) {
			return states == null || states.length == 0 ? new byte[penalties.length] : states.clone();
		}

		double applyForwardExtension(byte[] states, int from, int job) {
			if (!sequenceBased || job <= 0 || job > jobCount) {
				return 0.0;
			}
			double shift = 0.0;
			for (int cutIndex = 0; cutIndex < penalties.length; cutIndex++) {
				if (arcMemoryCut[cutIndex]) {
					if (!isMemoryArc(cutIndex, from, job)) {
						states[cutIndex] = 0;
					}
				} else if (!memoryByCut[cutIndex][job]) {
					states[cutIndex] = 0;
				}
			}
			for (int cutIndex : cutIndicesByJob[job]) {
				if (!arcMemoryCut[cutIndex] && !memoryByCut[cutIndex][job]) {
					continue;
				}
				int next = states[cutIndex] + 1;
				if (next >= 2) {
					shift += penalties[cutIndex];
					next -= 2;
				}
				states[cutIndex] = (byte) next;
			}
			return shift;
		}

		double applyBackwardPrepend(byte[] states, int job, int to) {
			return applyForwardExtension(states, job, to);
		}

		double joinShift(byte[] forwardStates, int from, int to, byte[] suffixStates) {
			if (!sequenceBased) {
				return 0.0;
			}
			double shift = 0.0;
			for (int cutIndex = 0; cutIndex < penalties.length; cutIndex++) {
				if (arcMemoryCut[cutIndex] && !isMemoryArc(cutIndex, from, to)) {
					continue;
				}
				if (forwardStates[cutIndex] + suffixStates[cutIndex] >= 2) {
					shift += penalties[cutIndex];
				}
			}
			return shift;
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
				byte[] states = new byte[penalties.length];
				double value = 0.0;
				int previous = 0;
				for (int job : sequence) {
					value += applyForwardExtension(states, previous, job);
					previous = job;
				}
				return value;
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

		private boolean isMemoryArc(int cutIndex, int from, int to) {
			if (from < 0 || from > jobCount + 1 || to < 0 || to > jobCount + 1) {
				return false;
			}
			return arcMemoryByCut[cutIndex][arcIndex(jobCount, from, to)];
		}

		private static int arcIndex(int jobCount, int from, int to) {
			return from * (jobCount + 2) + to;
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
		final double cost;
		final double reducedCost;
		final MoveType type;
		final int position;
		final int primaryJob;
		final int secondaryJob;
		TabuMove(double cost, double reducedCost, MoveType type, int position, int primaryJob,
				int secondaryJob) {
			this.cost = cost;
			this.reducedCost = reducedCost;
			this.type = type;
			this.position = position;
			this.primaryJob = primaryJob;
			this.secondaryJob = secondaryJob;
		}
	}

	/**
	 * 2026-06-30: 诊断启发式窗口口径和真实列成本的差异。
	 * 2026-07-01: compact-only 口径暂时跳过 true-cost recheck，这里记录跳过次数；dual window 仍记录真实差异。
	 */
	private static final class HeuristicCostAudit {
		private int checked;
		private int changedCost;
		private int filteredByTrueReducedCost;
		private int skippedTrueRecheck;
		private double signedDeltaSum;
		private double absDeltaMax;

		void observe(double restrictedCost, double restrictedReducedCost, double trueCost, double trueReducedCost) {
			checked++;
			double delta = trueCost - restrictedCost;
			if (Math.abs(delta) > 1e-7) {
				changedCost++;
				signedDeltaSum += delta;
				absDeltaMax = Math.max(absDeltaMax, Math.abs(delta));
			}
			if (Utility.compareLt(restrictedReducedCost, REDUCED_COST_TOLERANCE)
					&& Utility.compareGe(trueReducedCost, REDUCED_COST_TOLERANCE)) {
				filteredByTrueReducedCost++;
			}
		}

		void observeSkippedTrueRecheck() {
			skippedTrueRecheck++;
		}

		String summary() {
			if (checked == 0) {
				return ", heuristicCostAudit checked=0, skippedTrueRecheck=" + skippedTrueRecheck;
			}
			return ", heuristicCostAudit checked=" + checked
					+ ", changed=" + changedCost
					+ ", filteredByTrueRc=" + filteredByTrueReducedCost
					+ ", skippedTrueRecheck=" + skippedTrueRecheck
					+ ", avgDelta=" + (signedDeltaSum / checked)
					+ ", maxAbsDelta=" + absDeltaMax;
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
