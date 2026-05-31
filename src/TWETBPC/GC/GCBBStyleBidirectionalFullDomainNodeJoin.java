package TWETBPC.GC;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;

import Basic.Data;
import Common.Configure;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.Segment;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.PackedBitSet;
import TWETBPC.Util.SequenceSignature;

/**
 * no-cut 双向 pricing 的 full-domain GCBB-style node-join 实验版。
 * <p>
 * 只有在列数上限未截断、且 forward/backward 队列都被完整耗尽时，本轮结果才可作为 exact pricing
 * certificate；若达到 {@link TWETBPCConfig#maxExactPricingColumns}，这里只表示“最多生成 K 条负列”。
 * <p>
 * 2026-05-29: 本类复制自 full-domain crossing-arc 对照版，但 final join 改成同一 job 上的
 * node join。forward label 额外保存未加入当前 job 成本的 pre-node frontier，用于避免 join node
 * 的 penalty/job dual 被两侧重复计算。该类只作为实验分支，不作为默认正式入口。
 * <p>
 * 当前版本用于 full-domain 诊断：
 * 1. forward/backward label 都覆盖 [0, pricingHorizon]；
 * 2. dynamic window 只影响 job penalty 的有效区间，不把标签裁成 half-domain；
 * 3. final join 按同一 job 拼接 forward pre-node frontier 和 backward suffix-min frontier；
 * 4. 当前性能分支仿照旧双向 join，先用 node-join 乐观下界和函数最小值筛掉非负候选，
 * top-K 候选堆仍按 inferred reduced cost 维护；K 堆固定后再用 evaluator 修正最终列成本。
 */
public class GCBBStyleBidirectionalFullDomainNodeJoin {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;
	private enum LabelQueueOrdering {
		REDUCED_COST, TIME, REACHABLE_SIZE
	}

	private enum JoinBestThresholdMode {
		ZERO,
		BEST_UB,
		// 2026-05-31: 激进 record-only 对照模式；会减少每轮返回列数，默认不作为后续正式路径使用。
		BEST_RECORD
	}

	private enum CompletionBoundRelaxation {
		OFF,
		ALL_CYCLES,
		TWO_CYCLE
	}

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;
	// 2026-05-31: 为避免额外评估所有 inferred 负候选，evaluator 只在 K 堆固定后复核最终候选。

	private PriorityQueue<ForwardLabel> FWUL;
	private PriorityQueue<BackwardLabel> BWUL;
	private ArrayList<DominanceStore> FWTL;
	private ArrayList<DominanceStore> BWTL;
	private ArrayList<ArrayList<ForwardLabel>> activeForwardByLastJob;
	private ArrayList<ArrayList<BackwardLabel>> activeBackwardByFirstJob;
	private ArrayList<SinglePointStore<ForwardLabel>> forwardSinglePointByLastJob;
	private ArrayList<SinglePointStore<BackwardLabel>> backwardSinglePointByFirstJob;
	private PackedBitSet activeForwardTerminalJobs;
	// 2026-05-30: crossing-arc 旧流程曾维护 forward frontier 最小值作为组下界；
	// node join 必须用未加入 join job 成本的 pre-node 标量，旧标量不再维护。
	private double[] minForwardPreNodeReducedCostByLastJob;
	private double[] minForwardEllByLastJob;
	private ArrayList<TWETColumn> generatedColumns;
	private PriorityQueue<PricingColumnCandidate> generatedColumnCandidates;
	private HashMap<SequenceSignature, PricingColumnCandidate> generatedCandidateBySignature;
	private HashSet<SequenceSignature> activeColumnSignatures;
	private boolean[] zeroDualExcludedJobs;
	private int zeroDualExcludedJobCount;
	private int nextLabelId;
	private int nextCandidateId;
	private LabelQueueOrdering queueOrdering;
	private JoinBestThresholdMode joinBestThresholdMode;
	private CompletionBoundRelaxation completionBoundRelaxation;
	private CompletionBounds completionBounds;
	private double bestGeneratedReducedCost;

	// 2026-05-22: 双向 midpoint，只对当前 pricing 轮有效。
	private double tMid;
	// 2026-05-24: 本轮 bidirectional pricing 实际使用的右侧 horizon。
	// 若当前任务右端窗明显小于全局 CmaxH，就用它压住 midpoint 的右移，
	// 避免 backward sink root 因 Tmid 过右而完全长不出真实标签。
	private double pricingHorizon;
	// 2026-05-22: 当前定价轮的 job-level 动态 H_j 缓存。
	private PiecewiseLinearFunction[] dynamicJobPenaltyByJob;
	private double[] dynamicJobHStart;
	private double[] dynamicJobHEnd;
	private double[] effectiveJobHStart;
	private double[] effectiveJobHEnd;
	private PiecewiseLinearFunction[] dynamicBackwardPenaltyByJob;
	private double[] dynamicBackwardHStartByJob;
	private double[] dynamicBackwardHEndByJob;
	private double dynamicMinHStart;
	private double dynamicMaxHEnd;
	private double earliestSourceCompletion;
	// 2026-05-30: half-domain eligibility 只服务于旧半域剪枝。full-domain node join
	// 不再用它裁剪标签或 job；保留 dynamic window 本身即可，避免多做无效统计。
	private PiecewiseLinearFunction[] baseForwardHalfPenaltyByJob;
	private PiecewiseLinearFunction[] baseBackwardHalfPenaltyByJob;
	private double baseHalfPenaltyCacheTMid = Double.NaN;
	private double baseHalfPenaltyCacheHorizon = Double.NaN;
	// 2026-05-24: 只有根节点且没有 cut dual 时，pi_j profitable window 才保留三角不等式依据。
	private boolean dualProfitableWindowEnabled;

	private long forwardLabelsKept;
	private long forwardLabelsDominated;
	private long backwardLabelsKept;
	private long backwardLabelsDominated;
	private long joinTerminalGroupsScanned;
	private long joinTerminalGroupsArcOrVisitPruned;
	private long joinTerminalGroupsTimePruned;
	private long joinTerminalGroupsCostPruned;
	private long joinCandidateLabelsVisited;
	private long joinCandidateLabelsDominated;
	private long joinPairsTried;
	private long joinPairsSetPruned;
	private long joinPairsLowerBoundPruned;
	private long joinPairsBestBoundPruned;
	private long joinPairsTimePruned;
	private long joinFunctionEvaluations;
	private long joinFunctionPruned;
	private long joinFunctionBestRecordPruned;
	private long joinFunctionPositivePruned;
	private double joinPositivePairLBMin;
	private double joinPositivePairLBSum;
	private double joinPositivePairLBMax;
	private double joinPositiveReducedCostMin;
	private double joinPositiveReducedCostSum;
	private double joinPositiveReducedCostMax;
	private double joinPositiveMaxLift;
	private long forwardSinglePointKept;
	private long forwardSinglePointDominatedByStore;
	private long forwardSinglePointDominatedByGraph;
	private long backwardSinglePointKept;
	private long backwardSinglePointDominatedByStore;
	private long backwardSinglePointDominatedByGraph;
	private long generatedCandidateCount;
	private long generatedCandidateDroppedByHeap;
	private long forwardBoundaryTerminalKept;
	private long forwardBoundaryTerminalDominated;
	private long backwardBoundaryTerminalKept;
	private long backwardBoundaryTerminalDominated;
	private long completionForwardLabelsPruned;
	private long completionBackwardLabelsPruned;
	private long completionBoundFunctionEvaluations;
	private long completionBoundBuildNanos;
	private double completionBoundLastEvaluationCutoff;
	private long forwardExtensionNanos;
	private long backwardExtensionNanos;
	private long joinPhaseNanos;

	private String lastMessage = "GCBB-style full-domain node-join bidirectional pricing not executed";

	public GCBBStyleBidirectionalFullDomainNodeJoin(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	public ArrayList<TWETColumn> solve(LP lp) {
		Utility.resetCurUpperBound(Utility.big_M);
		initialize(lp);
		initializeBackwardSink(lp);
		// 2026-05-29: Node-join 实验仍先耗尽两侧可继续扩展的队列；跨过 Tmid 的边界
		// label 只进入 active join table，不再入队继续扩展。
		while (canContinue() && !FWUL.isEmpty()) {
			long phaseStart = System.nanoTime();
			forwardExtend(lp);
			forwardExtensionNanos += System.nanoTime() - phaseStart;
		}
		while (canContinue() && !BWUL.isEmpty()) {
			long phaseStart = System.nanoTime();
			backwardExtend(lp);
			backwardExtensionNanos += System.nanoTime() - phaseStart;
		}
		if (canContinue()) {
			long phaseStart = System.nanoTime();
			compactAndSortActiveLabelListsForJoin();
			joinAllForwardTerminalGroups(lp);
			joinPhaseNanos += System.nanoTime() - phaseStart;
			finalizeGeneratedColumns(lp);
		}
		String completionState = canContinue() ? "queues exhausted" : "column cap disabled";
		lastMessage = "GCBB-style full-domain node-join bidirectional no-cut labeling generated " + generatedColumns.size() + " columns ("
				+ completionState + "); " + statisticsSummary();
		return generatedColumns;
	}

	public String getLastMessage() {
		return lastMessage;
	}

	private LabelQueueOrdering parseQueueOrdering(String value) {
		if (value == null) {
			return LabelQueueOrdering.REDUCED_COST;
		}
		String normalized = value.trim().toLowerCase();
		if ("time".equals(normalized)) {
			return LabelQueueOrdering.TIME;
		}
		if ("reachablesize".equals(normalized) || "reachable_size".equals(normalized)
				|| "reachable".equals(normalized)) {
			return LabelQueueOrdering.REACHABLE_SIZE;
		}
		return LabelQueueOrdering.REDUCED_COST;
	}

	private JoinBestThresholdMode parseJoinBestThresholdMode(String value) {
		if (value == null) {
			return JoinBestThresholdMode.ZERO;
		}
		String normalized = value.trim().toLowerCase();
		if ("bestub".equals(normalized) || "best_ub".equals(normalized) || "best-ub".equals(normalized)) {
			return JoinBestThresholdMode.BEST_UB;
		}
		if ("bestrecord".equals(normalized) || "best_record".equals(normalized)
				|| "best-record".equals(normalized) || "record".equals(normalized)) {
			return JoinBestThresholdMode.BEST_RECORD;
		}
		return JoinBestThresholdMode.ZERO;
	}

	private CompletionBoundRelaxation parseCompletionBoundRelaxation(String value) {
		if (value == null) {
			return CompletionBoundRelaxation.OFF;
		}
		String normalized = value.trim().toLowerCase();
		if ("allcycles".equals(normalized) || "all_cycles".equals(normalized)
				|| "all-cycles".equals(normalized) || "all".equals(normalized)) {
			return CompletionBoundRelaxation.ALL_CYCLES;
		}
		if ("twocycle".equals(normalized) || "two_cycle".equals(normalized)
				|| "two-cycle".equals(normalized) || "2cycle".equals(normalized)
				|| "2-cycle".equals(normalized)) {
			return CompletionBoundRelaxation.TWO_CYCLE;
		}
		return CompletionBoundRelaxation.OFF;
	}

	/**
	 * 2026-05-26: 支持不同 label 出队策略，便于比较“低 reduced cost 优先”与“更可能被后续支配的
	 * label 推后扩展”之间的取舍。
	 */
	private Comparator<ForwardLabel> forwardQueueComparator(LabelQueueOrdering ordering) {
		return new Comparator<ForwardLabel>() {
			@Override
			public int compare(ForwardLabel left, ForwardLabel right) {
				if (ordering == LabelQueueOrdering.TIME) {
					int byTime = compareDoubleAsc(earliestForwardCompletion(left), earliestForwardCompletion(right));
					if (byTime != 0) {
						return byTime;
					}
					int byReachable = compareReachableCardinalityDesc(left, right);
					return byReachable != 0 ? byReachable : compareReducedCost(left, right);
				}
				if (ordering == LabelQueueOrdering.REACHABLE_SIZE) {
					int byReachable = compareReachableCardinalityDesc(left, right);
					return byReachable != 0 ? byReachable : compareReducedCost(left, right);
				}
				return compareReducedCost(left, right);
			}
		};
	}

	private Comparator<BackwardLabel> backwardQueueComparator(LabelQueueOrdering ordering) {
		return new Comparator<BackwardLabel>() {
			@Override
			public int compare(BackwardLabel left, BackwardLabel right) {
				if (ordering == LabelQueueOrdering.TIME) {
					int byTime = compareDoubleDesc(latestBackwardCompletion(left), latestBackwardCompletion(right));
					if (byTime != 0) {
						return byTime;
					}
					int byReachable = compareReachableCardinalityDesc(left, right);
					return byReachable != 0 ? byReachable : compareReducedCost(left, right);
				}
				if (ordering == LabelQueueOrdering.REACHABLE_SIZE) {
					int byReachable = compareReachableCardinalityDesc(left, right);
					return byReachable != 0 ? byReachable : compareReducedCost(left, right);
				}
				return compareReducedCost(left, right);
			}
		};
	}

	private static int compareReducedCost(FunctionLabel left, FunctionLabel right) {
		int byCost = compareDoubleAsc(left.minReducedCost, right.minReducedCost);
		if (byCost != 0) {
			return byCost;
		}
		int byJob = Integer.compare(left.jid, right.jid);
		return byJob != 0 ? byJob : Integer.compare(left.labelId, right.labelId);
	}

	private static Comparator<PricingColumnCandidate> candidateWorstFirstComparator() {
		return new Comparator<PricingColumnCandidate>() {
			@Override
			public int compare(PricingColumnCandidate left, PricingColumnCandidate right) {
				return -compareCandidateBestFirst(left, right);
			}
		};
	}

	private static Comparator<PricingColumnCandidate> candidateBestFirstComparator() {
		return new Comparator<PricingColumnCandidate>() {
			@Override
			public int compare(PricingColumnCandidate left, PricingColumnCandidate right) {
				return compareCandidateBestFirst(left, right);
			}
		};
	}

	private static Comparator<ForwardLabel> forwardJoinLowerBoundComparator() {
		return new Comparator<ForwardLabel>() {
			@Override
			public int compare(ForwardLabel left, ForwardLabel right) {
				int byPreNodeCost = compareDoubleAsc(left.preNodeMinReducedCost, right.preNodeMinReducedCost);
				return byPreNodeCost != 0 ? byPreNodeCost : compareReducedCost(left, right);
			}
		};
	}

	private static int compareCandidateBestFirst(PricingColumnCandidate left, PricingColumnCandidate right) {
		int byCost = compareDoubleAsc(left.reducedCost, right.reducedCost);
		if (byCost != 0) {
			return byCost;
		}
		return Integer.compare(left.candidateId, right.candidateId);
	}

	private static int compareReachableCardinalityDesc(FunctionLabel left, FunctionLabel right) {
		return Integer.compare(right.reachableCardinality, left.reachableCardinality);
	}

	private static int compareDoubleAsc(double left, double right) {
		if (Utility.compareLt(left, right)) {
			return -1;
		}
		if (Utility.compareGt(left, right)) {
			return 1;
		}
		return 0;
	}

	private static int compareDoubleDesc(double left, double right) {
		return compareDoubleAsc(right, left);
	}

	private static double earliestForwardCompletion(ForwardLabel label) {
		return label.frontier == null || label.frontier.head == null ? Utility.big_M : label.frontier.head.start;
	}

	private static double latestBackwardCompletion(BackwardLabel label) {
		return label.frontier == null || label.frontier.tail == null ? -Utility.big_M : label.frontier.tail.end;
	}

	private void initialize(LP lp) {
		resetStatistics();
		PaperDominanceGraph.resetStatistics();
		pricingHorizon = data.CmaxH;
		tMid = Math.min(data.CmaxH * 0.5, pricingHorizon);
		queueOrdering = parseQueueOrdering(config.bidirectionalLabelQueueOrdering);
		joinBestThresholdMode = parseJoinBestThresholdMode(config.bidirectionalJoinBestThresholdMode);
		completionBoundRelaxation = parseCompletionBoundRelaxation(config.bidirectionalCompletionBoundRelaxation);
		completionBounds = null;
		bestGeneratedReducedCost = Utility.big_M;
		FWUL = new PriorityQueue<ForwardLabel>(forwardQueueComparator(queueOrdering));
		BWUL = new PriorityQueue<BackwardLabel>(backwardQueueComparator(queueOrdering));
		FWTL = new ArrayList<DominanceStore>(data.n + 1);
		BWTL = new ArrayList<DominanceStore>(data.n + 1);
		activeForwardByLastJob = new ArrayList<ArrayList<ForwardLabel>>(data.n + 1);
		activeBackwardByFirstJob = new ArrayList<ArrayList<BackwardLabel>>(data.n + 1);
		forwardSinglePointByLastJob = new ArrayList<SinglePointStore<ForwardLabel>>(data.n + 1);
		backwardSinglePointByFirstJob = new ArrayList<SinglePointStore<BackwardLabel>>(data.n + 1);
		activeForwardTerminalJobs = new PackedBitSet(data.n + 2);
		minForwardPreNodeReducedCostByLastJob = new double[data.n + 1];
		minForwardEllByLastJob = new double[data.n + 1];
		for (int i = 0; i <= data.n; i++) {
			FWTL.add(new PaperDominanceGraph(Direction.FORWARD));
			BWTL.add(new PaperDominanceGraph(Direction.BACKWARD));
			activeForwardByLastJob.add(new ArrayList<ForwardLabel>());
			activeBackwardByFirstJob.add(new ArrayList<BackwardLabel>());
			forwardSinglePointByLastJob.add(new SinglePointStore<ForwardLabel>());
			backwardSinglePointByFirstJob.add(new SinglePointStore<BackwardLabel>());
			minForwardPreNodeReducedCostByLastJob[i] = Utility.big_M;
			minForwardEllByLastJob[i] = Utility.big_M;
		}
		generatedColumns = new ArrayList<TWETColumn>();
		generatedColumnCandidates = new PriorityQueue<PricingColumnCandidate>(
				Math.max(1, config.maxExactPricingColumns), candidateWorstFirstComparator());
		generatedCandidateBySignature = new HashMap<SequenceSignature, PricingColumnCandidate>();
		activeColumnSignatures = new HashSet<SequenceSignature>();
		nextLabelId = 0;
		nextCandidateId = 0;
		// 只记录当前 RMP active 列。全局 pool 自身会按 signature 去重；若历史列当前不 active，
		// pricing 仍可把它返回给 PC，让 LP.addColumns() 重新激活已有列。
		for (int columnId : lp.getRestrictedColumnIds()) {
			activeColumnSignatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		precomputeDynamicPricingWindows(lp);
		buildCompletionBounds(lp);

		PackedBitSet sourceVisited = new PackedBitSet(data.n + 2);
		sourceVisited.add(0);
		addZeroDualExcludedJobs(sourceVisited);
		PiecewiseLinearFunction sourceFrontier = cropToInterval(data.penaltyFunction[0].copy(), 0.0,
				pricingHorizon);
		sourceFrontier.shiftYInPlace(-lp.getMachineDual());
		sourceFrontier.normalize(Direction.FORWARD);
		ForwardLabel source = new ForwardLabel(nextLabelId++, 0, null, sourceVisited,
				buildForwardReachableSet(0, sourceVisited, lp.getNode(), sourceFrontier), sourceFrontier,
				sourceFrontier);
		if (insertForward(source, lp) == InsertStatus.STORED_AND_ENQUEUE) {
			FWUL.add(source);
		}
	}

	private void initializeBackwardSink(LP lp) {
		PackedBitSet sinkVisited = new PackedBitSet(data.n + 2);
		sinkVisited.add(lp.getNode().sinkId());
		addZeroDualExcludedJobs(sinkVisited);
		PiecewiseLinearFunction sinkFrontier = new PiecewiseLinearFunction();
		// 2026-05-28: full-domain 对照版本中，sink root 也直接使用完整 [0,pricingHorizon] 定义域。
		sinkFrontier.resetDomain(0.0, pricingHorizon);
		sinkFrontier.addSegment(0.0, pricingHorizon, 0.0, 0.0);
		BackwardLabel sink = BackwardLabel.sink(nextLabelId++, lp.getNode().sinkId(), sinkVisited, sinkFrontier,
				buildBackwardReachableSet(lp.getNode().sinkId(), sinkVisited, lp.getNode(), sinkFrontier));
		BWUL.add(sink);
	}

	private boolean canContinue() {
		return config.maxExactPricingColumns > 0;
	}

	private void forwardExtend(LP lp) {
		ForwardLabel label = FWUL.poll();
		if (label.isDominated) {
			return;
		}

		Node node = lp.getNode();
		for (int nextJob = label.reachableSet.nextSetBit(1); nextJob > 0 && nextJob <= data.n && canContinue();
				nextJob = label.reachableSet.nextSetBit(nextJob + 1)) {
			if (!canExtendForward(label, nextJob, node)) {
				continue;
			}
			extendForwardAndStore(label, nextJob, lp, false);
		}
	}

	private void backwardExtend(LP lp) {
		BackwardLabel label = BWUL.poll();
		if (label.isDominated) {
			return;
		}

		Node node = lp.getNode();
		for (int prevJob = label.reachableSet.nextSetBit(1); prevJob > 0 && prevJob <= data.n && canContinue();
				prevJob = label.reachableSet.nextSetBit(prevJob + 1)) {
			if (!canExtendBackward(label, prevJob, node)) {
				continue;
			}
			extendBackwardAndStore(label, prevJob, lp, false);
		}
	}

	private void extendForwardAndStore(ForwardLabel label, int nextJob, LP lp, boolean terminalCandidate) {
		ForwardLabel child = extendForward(label, nextJob, lp);
		if (child == null || Utility.isBigMValue(child.minReducedCost)) {
			return;
		}
		if (isForwardCompletionBoundPruned(child)) {
			completionForwardLabelsPruned++;
			return;
		}
		// 2026-05-29: node-join 版本的 reachableSet 已经是 full-domain 一跳可达集合；
		// Tmid 只决定 child 是否还能继续扩展。第一次越过 Tmid 的 child 只保留为
		// terminal label 参与同点 join，不入队。
		if (terminalCandidate || Utility.compareGt(earliestForwardCompletion(child), tMid)) {
			insertForwardBoundaryTerminal(child, lp);
			return;
		}
		if (insertForward(child, lp) == InsertStatus.STORED_AND_ENQUEUE) {
			FWUL.add(child);
		}
	}

	private void extendBackwardAndStore(BackwardLabel label, int prevJob, LP lp, boolean terminalCandidate) {
		BackwardLabel child = extendBackward(label, prevJob, lp, false);
		if (child == null || Utility.isBigMValue(child.minReducedCost)) {
			return;
		}
		if (isBackwardCompletionBoundPruned(child)) {
			completionBackwardLabelsPruned++;
			return;
		}
		// 2026-05-29: backward 侧对称处理。先按 full-domain 一跳可行性生成 child；
		// 若它已经跨到 Tmid 左侧，则只作为 terminal suffix 保存，不再继续向左扩展。
		if (terminalCandidate || Utility.compareLt(latestBackwardCompletion(child), tMid)) {
			insertBackwardBoundaryTerminal(child, lp);
			return;
		}
		if (insertBackward(child, lp) == InsertStatus.STORED_AND_ENQUEUE) {
			BWUL.add(child);
		}
	}

	private boolean canExtendForward(ForwardLabel label, int nextJob, Node node) {
		// 2026-05-29: zero-dual 排除统一由 source/sink 的 visited 初始化承载；
		// reachableSet 只保留路径层面的候选收缩，这里只即时检查直连禁弧。
		// 2026-05-29: node-join 类的 reachableSet 表示 full-domain 一跳可达候选；
		// visited、zero-dual 和时间可行性已经在 reachable set 构造时维护。下面旧检查
		// 保留为防御性说明，实际会随节点变化、必须即时检查的是直连禁弧。
		// if (label.visitedSet.contains(nextJob) || !label.reachableSet.contains(nextJob)) {
		// 	return false;
		// }
		return !node.isArcForbidden(label.jid, nextJob);
	}

	private boolean canExtendBackward(BackwardLabel label, int prevJob, Node node) {
		int successor = label.isSinkRoot ? node.sinkId() : label.jid;
		// 2026-05-29: backward 同样只把分支禁弧留到这里即时检查；
		// zero-dual 排除不再在扩展点重复判断。
		// 2026-05-29: reachable set 已维护 full-domain 一跳时间可行性、visited 和 zero-dual；
		// backward 扩展点只需即时检查 prevJob -> successor 这条直连弧是否被禁。
		// if (label.visitedSet.contains(prevJob) || !label.reachableSet.contains(prevJob)) {
		// 	return false;
		// }
		return !node.isArcForbidden(prevJob, successor);
	}

	private ForwardLabel extendForward(ForwardLabel label, int nextJob, LP lp) {
		double delay = data.getSetUp(label.jid, nextJob) + data.getProcessT(nextJob);
		PiecewiseLinearFunction shifted = label.frontier.shiftX(delay);
		if (shifted.head == null) {
			return null;
		}

		PiecewiseLinearFunction jobPenalty = getDynamicForwardJobPenalty(label.jid, nextJob);
		if (jobPenalty == null) {
			return null;
		}
		// 2026-05-29: Node join 需要当前 job 成本之前的 U_state。这里先从父传播函数
		// shift 一次得到 shifted，再原地加 incoming reduced arc cost 形成 preNodeFrontier；
		// 后续 F_state 直接复用它加 job penalty/job dual，避免重复 shiftX。
		PiecewiseLinearFunction preNodeFrontier = shifted;
		preNodeFrontier.shiftYInPlace(data.getSetupCost(label.jid, nextJob) - lp.getArcDual(label.jid, nextJob));
		PiecewiseLinearFunction nextFrontier = preNodeFrontier.add(jobPenalty);
		if (nextFrontier.head == null) {
			return null;
		}
		nextFrontier.shiftYInPlace(-lp.getJobDual(nextJob));
		nextFrontier.normalize(Direction.FORWARD);
		if (nextFrontier.head == null) {
			return null;
		}

		PackedBitSet visited = label.visitedSet.copy();
		visited.add(nextJob);
		PackedBitSet reachable = buildForwardReachableSetFromParent(label, nextJob, visited, lp.getNode(),
				nextFrontier);
		return new ForwardLabel(nextLabelId++, nextJob, label, visited, reachable, nextFrontier, preNodeFrontier);
	}

	private BackwardLabel extendBackward(BackwardLabel label, int prevJob, LP lp) {
		return extendBackward(label, prevJob, lp, true);
	}

	private BackwardLabel extendBackward(BackwardLabel label, int prevJob, LP lp, boolean requireTmid) {
		Node node = lp.getNode();
		PiecewiseLinearFunction nextFrontier;
		double successorHStart = getDynamicBackwardHStart(prevJob, label.isSinkRoot ? node.sinkId() : label.jid);
		double rhoPrime;
		if (label.isSinkRoot) {
			rhoPrime = getDynamicBackwardHEnd(prevJob, node.sinkId());
			double lower = requireTmid ? Math.max(tMid, successorHStart) : successorHStart;
			if (Utility.compareLt(rhoPrime, lower)) {
				return null;
			}
			PiecewiseLinearFunction jobPenalty = getDynamicBackwardJobPenalty(prevJob, node.sinkId());
			if (jobPenalty == null) {
				return null;
			}
			// 2026-05-22: backward 从虚拟终点出发时，第一次加入真实任务不需要按 setup/processing 平移；
			// 当前变量已经是 prevJob 自己的完成时间，这里只扣 job/arc dual。
			nextFrontier = jobPenalty.copy();
			nextFrontier.shiftYInPlace(-lp.getJobDual(prevJob) - lp.getArcDual(prevJob, node.sinkId()));
		} else {
			double delay = data.getSetUp(prevJob, label.jid) + data.getProcessT(label.jid);
			rhoPrime = Math.min(label.frontier.tail.end - delay, getDynamicBackwardHEnd(prevJob, label.jid));
			double lower = requireTmid ? Math.max(tMid, successorHStart) : successorHStart;
			if (Utility.compareLt(rhoPrime, lower)) {
				return null;
			}
			PiecewiseLinearFunction shifted = label.frontier.shiftX(-delay);
			if (shifted.head == null) {
				return null;
			}
			PiecewiseLinearFunction jobPenalty = getDynamicBackwardJobPenalty(prevJob, label.jid);
			if (jobPenalty == null) {
				return null;
			}
			nextFrontier = shifted.add(jobPenalty);
			if (nextFrontier.head == null) {
				return null;
			}
			double fixedReducedCost = data.getSetupCost(prevJob, label.jid) - lp.getJobDual(prevJob)
					- lp.getArcDual(prevJob, label.jid);
			nextFrontier.shiftYInPlace(fixedReducedCost);
		}
		// 2026-05-30: node join 使用 backward 的 suffix-min 传播函数。它表示当前 job
		// 完成时间不早于 t 时的最优后缀成本，正好对应 arc join 等价变换后的同点拼接口径。
		nextFrontier.normalize(Direction.BACKWARD);
		if (nextFrontier.head == null) {
			return null;
		}

		PackedBitSet visited = label.visitedSet.copy();
		visited.add(prevJob);
		PackedBitSet reachable = buildBackwardReachableSetFromParent(label, prevJob, visited, lp.getNode(),
				nextFrontier);
		return new BackwardLabel(nextLabelId++, prevJob, label, visited, reachable, nextFrontier, false);
	}

	private InsertStatus insertForward(ForwardLabel label, LP lp) {
		if (isSinglePointFrontier(label.frontier)) {
			return insertForwardSinglePoint(label, lp);
		}
		boolean dominated = FWTL.get(label.jid).insertOrDominate(label);
		if (!dominated) {
			forwardLabelsKept++;
			activeForwardByLastJob.get(label.jid).add(label);
			activeForwardTerminalJobs.add(label.jid);
			updateForwardScalarInfo(label);
			return InsertStatus.STORED_AND_ENQUEUE;
		}
		forwardLabelsDominated++;
		return InsertStatus.DOMINATED;
	}

	private InsertStatus insertBackward(BackwardLabel label, LP lp) {
		if (isSinglePointFrontier(label.frontier)) {
			return insertBackwardSinglePoint(label, lp);
		}
		boolean dominated = BWTL.get(label.jid).insertOrDominate(label);
		if (dominated) {
			backwardLabelsDominated++;
			return InsertStatus.DOMINATED;
		}
		backwardLabelsKept++;
		activeBackwardByFirstJob.get(label.jid).add(label);
		return InsertStatus.STORED_AND_ENQUEUE;
	}

	private void insertForwardBoundaryTerminal(ForwardLabel label, LP lp) {
		// 2026-05-30: terminal label 仍按普通 label 进入 dominance table；
		// 与普通 label 的唯一区别是保留后只参与 node join，不再入 FWUL 继续扩展。
		InsertStatus status = insertForward(label, lp);
		if (status == InsertStatus.DOMINATED) {
			forwardBoundaryTerminalDominated++;
			return;
		}
		forwardBoundaryTerminalKept++;
	}

	private void insertBackwardBoundaryTerminal(BackwardLabel label, LP lp) {
		// 2026-05-30: backward terminal suffix 同样走 BWTL 占优；
		// 被保留后只进入 active list，不进入 BWUL。
		InsertStatus status = insertBackward(label, lp);
		if (status == InsertStatus.DOMINATED) {
			backwardBoundaryTerminalDominated++;
			return;
		}
		backwardBoundaryTerminalKept++;
	}

	/**
	 * 2026-05-25: Tmid 单点 forward label 不再进入普通 dominance graph，也不再入扩展队列；
	 * 但仍要保留给 sink 收尾和后续 backward join。
	 */
	private InsertStatus insertForwardSinglePoint(ForwardLabel label, LP lp) {
		SinglePointStore<ForwardLabel> store = forwardSinglePointByLastJob.get(label.jid);
		if (isDominatedBySinglePointStore(store, label)) {
			label.isDominated = true;
			forwardLabelsDominated++;
			forwardSinglePointDominatedByStore++;
			return InsertStatus.DOMINATED;
		}
		if (FWTL.get(label.jid).dominatesSinglePoint(label.reachableSet, tMid, label.minReducedCost)) {
			label.isDominated = true;
			forwardLabelsDominated++;
			forwardSinglePointDominatedByGraph++;
			return InsertStatus.DOMINATED;
		}
		removeSinglePointsDominatedBy(store, label);
		addSinglePointLabel(store, label);
		forwardLabelsKept++;
		forwardSinglePointKept++;
		activeForwardByLastJob.get(label.jid).add(label);
		activeForwardTerminalJobs.add(label.jid);
		updateForwardScalarInfo(label);
		return InsertStatus.STORED_NO_EXPAND;
	}

	/**
	 * 2026-05-25: Tmid 单点 backward label 只保留给 single-point store；
	 * 2026-05-26: 在 GCBB-style 流程下不立即 join，而是在最后统一扫描 join。
	 */
	private InsertStatus insertBackwardSinglePoint(BackwardLabel label, LP lp) {
		SinglePointStore<BackwardLabel> store = backwardSinglePointByFirstJob.get(label.jid);
		if (isDominatedBySinglePointStore(store, label)) {
			label.isDominated = true;
			backwardLabelsDominated++;
			backwardSinglePointDominatedByStore++;
			return InsertStatus.DOMINATED;
		}
		if (BWTL.get(label.jid).dominatesSinglePoint(label.reachableSet, tMid, label.minReducedCost)) {
			label.isDominated = true;
			backwardLabelsDominated++;
			backwardSinglePointDominatedByGraph++;
			return InsertStatus.DOMINATED;
		}
		removeSinglePointsDominatedBy(store, label);
		addSinglePointLabel(store, label);
		backwardLabelsKept++;
		backwardSinglePointKept++;
		return InsertStatus.STORED_NO_EXPAND;
	}

	private boolean isSinglePointFrontier(PiecewiseLinearFunction frontier) {
		return frontier != null && frontier.head != null && frontier.tail != null
				&& Utility.compareEq(frontier.head.start, frontier.tail.end)
				&& Utility.compareEq(frontier.head.start, tMid);
	}

	private <L extends FunctionLabel> boolean isDominatedBySinglePointStore(SinglePointStore<L> store, L label) {
		L exact = store.bestByReachable.get(label.reachableSet);
		if (exact != null) {
			if (exact.isDominated) {
				store.bestByReachable.remove(label.reachableSet);
			} else if (!Utility.compareLt(label.minReducedCost, exact.minReducedCost)) {
				return true;
			}
		}
		int labelCardinality = label.reachableCardinality;
		for (int cardinality = labelCardinality; cardinality < store.liveLabelsByCardinality.size(); cardinality++) {
			ArrayList<L> bucket = store.liveLabelsByCardinality.get(cardinality);
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int i = 0; i < bucket.size(); i++) {
				L existing = bucket.get(i);
				if (existing.isDominated) {
					continue;
				}
				if (existing.reachableSet.isSupersetOf(label.reachableSet)
						&& !Utility.compareGt(existing.minReducedCost, label.minReducedCost)) {
					return true;
				}
			}
		}
		return false;
	}

	private <L extends FunctionLabel> void removeSinglePointsDominatedBy(SinglePointStore<L> store, L label) {
		int labelCardinality = label.reachableCardinality;
		int maxCardinality = Math.min(labelCardinality, store.liveLabelsByCardinality.size() - 1);
		for (int cardinality = maxCardinality; cardinality >= 0; cardinality--) {
			ArrayList<L> bucket = store.liveLabelsByCardinality.get(cardinality);
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int i = bucket.size() - 1; i >= 0; i--) {
				L existing = bucket.get(i);
				if (existing.isDominated) {
					bucket.remove(i);
					continue;
				}
				if (label.reachableSet.isSupersetOf(existing.reachableSet)
						&& !Utility.compareGt(label.minReducedCost, existing.minReducedCost)) {
					existing.isDominated = true;
					bucket.remove(i);
					L mapped = store.bestByReachable.get(existing.reachableSet);
					if (mapped == existing) {
						store.bestByReachable.remove(existing.reachableSet);
					}
				}
			}
		}
	}

	private <L extends FunctionLabel> void addSinglePointLabel(SinglePointStore<L> store, L label) {
		store.bestByReachable.put(label.reachableSet, label);
		ensureSinglePointBucket(store, label.reachableCardinality).add(label);
	}

	private <L extends FunctionLabel> ArrayList<L> ensureSinglePointBucket(SinglePointStore<L> store, int cardinality) {
		while (store.liveLabelsByCardinality.size() <= cardinality) {
			store.liveLabelsByCardinality.add(null);
		}
		ArrayList<L> bucket = store.liveLabelsByCardinality.get(cardinality);
		if (bucket == null) {
			bucket = new ArrayList<L>();
			store.liveLabelsByCardinality.set(cardinality, bucket);
		}
		return bucket;
	}

	private void updateForwardScalarInfo(ForwardLabel label) {
		int lastJob = label.jid;
		if (Utility.compareLt(label.preNodeMinReducedCost, minForwardPreNodeReducedCostByLastJob[lastJob])) {
			minForwardPreNodeReducedCostByLastJob[lastJob] = label.preNodeMinReducedCost;
		}
		if (label.frontier != null && label.frontier.head != null
				&& Utility.compareLt(label.frontier.head.start, minForwardEllByLastJob[lastJob])) {
			minForwardEllByLastJob[lastJob] = label.frontier.head.start;
		}
	}

	private void tryGenerateForwardColumn(ForwardLabel label, LP lp) {
		if (label.jid == 0 || config.maxExactPricingColumns <= 0) {
			return;
		}
		Node node = lp.getNode();
		int sink = node.sinkId();
		if (node.isArcForbidden(label.jid, sink)) {
			return;
		}
		double reducedCost = label.minReducedCost - lp.getArcDual(label.jid, sink);
		ArrayList<Integer> sequence = recoverForwardSequence(label);
		tryGenerateColumn(sequence, lp, reducedCost);
	}

	/**
	 * 2026-05-28: final join 前统一清掉已被后续 label 支配的旧条目，再排序。
	 * 这样完整列只从最终仍存活的 label table 里生成，不受早期出队顺序影响。
	 */
	private void compactAndSortActiveLabelListsForJoin() {
		for (int job = 1; job <= data.n; job++) {
			compactForwardLabelsForJoin(job);
			compactBackwardLabelsForJoin(job);
		}
	}

	private void compactForwardLabelsForJoin(int job) {
		ArrayList<ForwardLabel> labels = activeForwardByLastJob.get(job);
		int liveCount = 0;
		double liveMinPreNodeReducedCost = Utility.big_M;
		double liveMinEll = Utility.big_M;
		for (int i = 0; i < labels.size(); i++) {
			ForwardLabel label = labels.get(i);
			if (label.isDominated) {
				continue;
			}
			labels.set(liveCount++, label);
			if (Utility.compareLt(label.preNodeMinReducedCost, liveMinPreNodeReducedCost)) {
				liveMinPreNodeReducedCost = label.preNodeMinReducedCost;
			}
			if (label.frontier != null && label.frontier.head != null
					&& Utility.compareLt(label.frontier.head.start, liveMinEll)) {
				liveMinEll = label.frontier.head.start;
			}
		}
		if (liveCount < labels.size()) {
			labels.subList(liveCount, labels.size()).clear();
		}
		if (liveCount == 0) {
			activeForwardTerminalJobs.remove(job);
			minForwardPreNodeReducedCostByLastJob[job] = Utility.big_M;
			minForwardEllByLastJob[job] = Utility.big_M;
			return;
		}
		Collections.sort(labels, forwardJoinLowerBoundComparator());
		minForwardPreNodeReducedCostByLastJob[job] = liveMinPreNodeReducedCost;
		minForwardEllByLastJob[job] = liveMinEll;
		activeForwardTerminalJobs.add(job);
	}

	private void compactBackwardLabelsForJoin(int job) {
		ArrayList<BackwardLabel> labels = activeBackwardByFirstJob.get(job);
		int liveCount = 0;
		for (int i = 0; i < labels.size(); i++) {
			BackwardLabel label = labels.get(i);
			if (!label.isDominated) {
				labels.set(liveCount++, label);
			}
		}
		if (liveCount < labels.size()) {
			labels.subList(liveCount, labels.size()).clear();
		}
		Collections.sort(labels);
	}

	/**
	 * 2026-05-29: Node join 按同一个真实 job 拼接两侧 label。forward->sink
	 * 收尾仍统一在最后处理，避免它绕过 top-K 候选池。
	 */
	private void joinAllForwardTerminalGroups(LP lp) {
		for (int lastJob = activeForwardTerminalJobs.nextSetBit(0); lastJob >= 0 && lastJob <= data.n && canContinue();
				lastJob = activeForwardTerminalJobs.nextSetBit(lastJob + 1)) {
			if (lastJob == 0) {
				continue;
			}
			ArrayList<ForwardLabel> candidates = activeForwardByLastJob.get(lastJob);
			if (candidates.isEmpty()) {
				continue;
			}
			joinForwardGroupToSameNodeBackwardLabels(lastJob, candidates, lp);
			joinForwardGroupToSink(candidates, lp);
		}
	}

	private void joinForwardGroupToSameNodeBackwardLabels(int joinJob, ArrayList<ForwardLabel> candidates, LP lp) {
		ArrayList<BackwardLabel> labels = activeBackwardByFirstJob.get(joinJob);
		for (int i = 0; i < labels.size() && canContinue(); i++) {
			BackwardLabel backward = labels.get(i);
			if (!backward.isDominated && !backward.isSinkRoot) {
				joinForwardGroupWithBackward(joinJob, candidates, backward, lp);
			}
		}
		joinForwardGroupWithBackwardSinglePoints(joinJob, candidates, backwardSinglePointByFirstJob.get(joinJob), lp);
	}

	private void joinForwardGroupWithBackwardSinglePoints(int lastJob, ArrayList<ForwardLabel> candidates,
			SinglePointStore<BackwardLabel> store, LP lp) {
		for (int cardinality = 0; cardinality < store.liveLabelsByCardinality.size() && canContinue(); cardinality++) {
			ArrayList<BackwardLabel> bucket = store.liveLabelsByCardinality.get(cardinality);
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int i = 0; i < bucket.size() && canContinue(); i++) {
				BackwardLabel backward = bucket.get(i);
				if (!backward.isDominated && !backward.isSinkRoot) {
					joinForwardGroupWithBackward(lastJob, candidates, backward, lp);
				}
			}
		}
	}

	private void joinForwardGroupToSink(ArrayList<ForwardLabel> candidates, LP lp) {
		for (int i = 0; i < candidates.size() && canContinue(); i++) {
			ForwardLabel label = candidates.get(i);
			if (!label.isDominated) {
				tryGenerateForwardColumn(label, lp);
			}
		}
	}

	private void joinForwardGroupWithBackward(int lastJob, ArrayList<ForwardLabel> candidates, BackwardLabel backward,
			LP lp) {
		joinTerminalGroupsScanned++;
		if (lastJob != backward.jid) {
			joinTerminalGroupsArcOrVisitPruned++;
			return;
		}
		if (Utility.compareGt(minForwardEllByLastJob[lastJob], backward.frontier.tail.end)) {
			joinTerminalGroupsTimePruned++;
			return;
		}
		double joinThreshold = joinLowerBoundThreshold();
		double groupLB = minForwardPreNodeReducedCostByLastJob[lastJob] + backward.minReducedCost;
		if (!Utility.compareLt(groupLB, joinThreshold)) {
			joinTerminalGroupsCostPruned++;
			if (Utility.compareLt(joinThreshold, REDUCED_COST_TOLERANCE)) {
				joinPairsBestBoundPruned++;
			}
			return;
		}
		for (int i = 0; i < candidates.size(); i++) {
			ForwardLabel forward = candidates.get(i);
			joinCandidateLabelsVisited++;
			if (forward.isDominated) {
				joinCandidateLabelsDominated++;
				continue;
			}
			double optimisticJoinLB = forward.preNodeMinReducedCost + backward.minReducedCost;
			if (!Utility.compareLt(optimisticJoinLB, joinThreshold)) {
				joinPairsLowerBoundPruned++;
				if (Utility.compareLt(joinThreshold, REDUCED_COST_TOLERANCE)) {
					joinPairsBestBoundPruned++;
				}
				break;
			}
			tryJoin(forward, backward, optimisticJoinLB, lp);
		}
	}

	private void tryJoin(ForwardLabel forward, BackwardLabel backward, double optimisticJoinLB, LP lp) {
		if (config.maxExactPricingColumns <= 0) {
			return;
		}
		joinPairsTried++;
		if (forward.jid != backward.jid) {
			joinPairsSetPruned++;
			return;
		}
		if (intersectsExceptJoin(forward.visitedSet, backward.visitedSet, forward.jid)) {
			joinPairsSetPruned++;
			return;
		}

		if (forward.preNodeFrontier == null || forward.preNodeFrontier.head == null
				|| Utility.compareGt(forward.preNodeFrontier.head.start, backward.frontier.tail.end)) {
			joinPairsTimePruned++;
			return;
		}

		joinFunctionEvaluations++;
		PiecewiseLinearFunction backwardFull = backward.frontier;
		if (backwardFull.head == null) {
			joinFunctionPruned++;
			return;
		}
		PiecewiseLinearFunction joinCost = forward.preNodeFrontier.add(backwardFull);
		if (joinCost.head == null) {
			joinFunctionPruned++;
			return;
		}
		double reducedCostBound = joinCost.findMinimal(false, true)[0];
		if (!shouldKeepJoinedReducedCost(reducedCostBound)) {
			joinFunctionPruned++;
			if (!Utility.compareLt(reducedCostBound, REDUCED_COST_TOLERANCE)) {
				recordPositiveFunctionPruned(optimisticJoinLB, reducedCostBound);
			} else {
				joinFunctionBestRecordPruned++;
			}
			return;
		}
		ArrayList<Integer> sequence = recoverNodeJoinSequence(forward, backward);
		tryGenerateColumn(sequence, lp, reducedCostBound);
	}

	private void recordPositiveFunctionPruned(double optimisticJoinLB, double reducedCostBound) {
		joinFunctionPositivePruned++;
		if (joinFunctionPositivePruned == 1) {
			joinPositivePairLBMin = optimisticJoinLB;
			joinPositivePairLBMax = optimisticJoinLB;
			joinPositiveReducedCostMin = reducedCostBound;
			joinPositiveReducedCostMax = reducedCostBound;
			joinPositiveMaxLift = reducedCostBound - optimisticJoinLB;
		} else {
			joinPositivePairLBMin = Math.min(joinPositivePairLBMin, optimisticJoinLB);
			joinPositivePairLBMax = Math.max(joinPositivePairLBMax, optimisticJoinLB);
			joinPositiveReducedCostMin = Math.min(joinPositiveReducedCostMin, reducedCostBound);
			joinPositiveReducedCostMax = Math.max(joinPositiveReducedCostMax, reducedCostBound);
			joinPositiveMaxLift = Math.max(joinPositiveMaxLift, reducedCostBound - optimisticJoinLB);
		}
		joinPositivePairLBSum += optimisticJoinLB;
		joinPositiveReducedCostSum += reducedCostBound;
	}

	private void resetStatistics() {
		forwardLabelsKept = 0;
		forwardLabelsDominated = 0;
		backwardLabelsKept = 0;
		backwardLabelsDominated = 0;
		joinTerminalGroupsScanned = 0;
		joinTerminalGroupsArcOrVisitPruned = 0;
		joinTerminalGroupsTimePruned = 0;
		joinTerminalGroupsCostPruned = 0;
		joinCandidateLabelsVisited = 0;
		joinCandidateLabelsDominated = 0;
		joinPairsTried = 0;
		joinPairsSetPruned = 0;
		joinPairsLowerBoundPruned = 0;
		joinPairsBestBoundPruned = 0;
		joinPairsTimePruned = 0;
		joinFunctionEvaluations = 0;
		joinFunctionPruned = 0;
		joinFunctionBestRecordPruned = 0;
		joinFunctionPositivePruned = 0;
		joinPositivePairLBMin = 0.0;
		joinPositivePairLBSum = 0.0;
		joinPositivePairLBMax = 0.0;
		joinPositiveReducedCostMin = 0.0;
		joinPositiveReducedCostSum = 0.0;
		joinPositiveReducedCostMax = 0.0;
		joinPositiveMaxLift = 0.0;
		forwardSinglePointKept = 0;
		forwardSinglePointDominatedByStore = 0;
		forwardSinglePointDominatedByGraph = 0;
		backwardSinglePointKept = 0;
		backwardSinglePointDominatedByStore = 0;
		backwardSinglePointDominatedByGraph = 0;
		generatedCandidateCount = 0;
		generatedCandidateDroppedByHeap = 0;
		forwardBoundaryTerminalKept = 0;
		forwardBoundaryTerminalDominated = 0;
		backwardBoundaryTerminalKept = 0;
		backwardBoundaryTerminalDominated = 0;
		completionForwardLabelsPruned = 0;
		completionBackwardLabelsPruned = 0;
		completionBoundFunctionEvaluations = 0;
		completionBoundBuildNanos = 0;
		completionBoundLastEvaluationCutoff = Double.NaN;
		forwardExtensionNanos = 0;
		backwardExtensionNanos = 0;
		joinPhaseNanos = 0;
	}

	private String statisticsSummary() {
		long extensionNanos = forwardExtensionNanos + backwardExtensionNanos;
		long measuredNanos = extensionNanos + joinPhaseNanos;
		return "labels fw kept/dominated=" + forwardLabelsKept + "/" + forwardLabelsDominated
				+ ", bw kept/dominated=" + backwardLabelsKept + "/" + backwardLabelsDominated
				+ ", singlePoint fw kept/storeDom/graphDom=" + forwardSinglePointKept + "/"
				+ forwardSinglePointDominatedByStore + "/" + forwardSinglePointDominatedByGraph
				+ ", bw kept/storeDom/graphDom=" + backwardSinglePointKept + "/"
				+ backwardSinglePointDominatedByStore + "/" + backwardSinglePointDominatedByGraph
				+ ", join groups scanned/arcOrVisit/timeLB/costLB=" + joinTerminalGroupsScanned
				+ "/" + joinTerminalGroupsArcOrVisitPruned
				+ "/" + joinTerminalGroupsTimePruned + "/" + joinTerminalGroupsCostPruned
				+ ", join candidates visited/dominated=" + joinCandidateLabelsVisited + "/"
				+ joinCandidateLabelsDominated
				+ ", join pairs tried/set/lb/time/funcEval/funcPruned=" + joinPairsTried
				+ "/" + joinPairsSetPruned + "/" + joinPairsLowerBoundPruned + "/"
				+ joinPairsTimePruned + "/"
				+ joinFunctionEvaluations + "/" + joinFunctionPruned
				+ ", joinBest mode/bestRC/lbPruned/recordPruned=" + joinBestThresholdMode
				+ "/" + bestGeneratedReducedCost + "/" + joinPairsBestBoundPruned
				+ "/" + joinFunctionBestRecordPruned
				+ ", completionBound mode/cutoff/buildMs/eval/fwPruned/bwPruned=" + completionBoundRelaxation
				+ "/" + completionBoundCutoffForSummary() + "/" + formatMillis(completionBoundBuildNanos)
				+ "/" + completionBoundFunctionEvaluations + "/" + completionForwardLabelsPruned
				+ "/" + completionBackwardLabelsPruned
				+ ", join positiveFuncPruned count/lbMin/lbAvg/lbMax/rcMin/rcAvg/rcMax/maxLift="
				+ joinFunctionPositivePruned + "/" + positivePairLBMin() + "/" + positivePairLBAvg()
				+ "/" + positivePairLBMax() + "/" + positiveReducedCostMin() + "/"
				+ positiveReducedCostAvg() + "/" + positiveReducedCostMax() + "/" + joinPositiveMaxLift
				+ ", candidatePool kept/seen/dropped=" + generatedColumnCandidates.size() + "/"
				+ generatedCandidateCount + "/" + generatedCandidateDroppedByHeap
				+ ", boundaryTerminal fw kept/dominated=" + forwardBoundaryTerminalKept + "/"
				+ forwardBoundaryTerminalDominated
				+ ", bw kept/dominated=" + backwardBoundaryTerminalKept + "/"
				+ backwardBoundaryTerminalDominated
				+ ", queueOrdering=" + queueOrdering
				+ ", timingMs fwExt/bwExt/join/extTotal/measuredTotal=" + formatMillis(forwardExtensionNanos)
				+ "/" + formatMillis(backwardExtensionNanos) + "/" + formatMillis(joinPhaseNanos)
				+ "/" + formatMillis(extensionNanos) + "/" + formatMillis(measuredNanos)
				+ ", joinMeasuredShare=" + formatPercent(joinPhaseNanos, measuredNanos)
				+ ", dynamicHStartMin=" + dynamicMinHStart + ", dynamicHEndMax=" + dynamicMaxHEnd
				+ ", earliestSourceCompletion=" + earliestSourceCompletion
				+ ", pricingHorizon=" + pricingHorizon + ", tMid=" + tMid
				+ ", zeroDualExcludedJobs=" + zeroDualExcludedJobCount
				+ ", dualWindow=" + (dualProfitableWindowEnabled ? "enabled" : "staticOutsourcingOnly")
				+ ", " + PaperDominanceGraph.statisticsSummary();
	}

	private static String formatMillis(long nanos) {
		return String.format("%.3f", nanos / 1_000_000.0);
	}

	private static String formatPercent(long part, long total) {
		if (total <= 0L) {
			return "0.00%";
		}
		return String.format("%.2f%%", part * 100.0 / total);
	}

	private double joinLowerBoundThreshold() {
		if ((joinBestThresholdMode == JoinBestThresholdMode.BEST_UB
				|| joinBestThresholdMode == JoinBestThresholdMode.BEST_RECORD)
				&& Utility.compareLt(bestGeneratedReducedCost, REDUCED_COST_TOLERANCE)) {
			return bestGeneratedReducedCost;
		}
		return REDUCED_COST_TOLERANCE;
	}

	private double completionBoundCutoff() {
		// 2026-05-31: completion bound 用于扩展阶段判断“这个 label 是否还能补成负列”。
		// 这里固定使用 0 附近阈值，不使用当前 best reduced cost；否则会变成 record-only
		// 语义，剪掉仍为负但不刷新当前最优记录的列，影响 top-K 加列和 exact pricing 口径。
		return REDUCED_COST_TOLERANCE;
	}

	private double completionBoundCutoffForSummary() {
		return Double.isNaN(completionBoundLastEvaluationCutoff)
				? completionBoundCutoff() : completionBoundLastEvaluationCutoff;
	}

	private boolean shouldKeepJoinedReducedCost(double reducedCost) {
		double threshold = joinBestThresholdMode == JoinBestThresholdMode.BEST_RECORD
				? joinLowerBoundThreshold() : REDUCED_COST_TOLERANCE;
		return Utility.compareLt(reducedCost, threshold);
	}

	private void updateBestGeneratedReducedCost(double reducedCost) {
		if (Utility.compareLt(reducedCost, bestGeneratedReducedCost)) {
			bestGeneratedReducedCost = reducedCost;
		}
	}

	private void buildCompletionBounds(LP lp) {
		if (completionBoundRelaxation == CompletionBoundRelaxation.OFF) {
			return;
		}
		long start = System.nanoTime();
		CompletionBoundBuilder builder = new CompletionBoundBuilder(lp);
		completionBounds = builder.build(completionBoundRelaxation);
		completionBoundBuildNanos += System.nanoTime() - start;
	}

	private boolean isForwardCompletionBoundPruned(ForwardLabel label) {
		if (completionBounds == null || label.jid <= 0 || label.jid > data.n || label.frontier == null
				|| label.frontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction suffix = completionBounds.backwardRByJob[label.jid];
		if (suffix == null || suffix.head == null) {
			return false;
		}
		completionBoundFunctionEvaluations++;
		PiecewiseLinearFunction completion = label.frontier.add(suffix);
		if (completion.head == null) {
			return false;
		}
		double lowerBound = completion.findMinimal(false, true)[0];
		double cutoff = completionBoundCutoff();
		completionBoundLastEvaluationCutoff = cutoff;
		return !Utility.compareLt(lowerBound, cutoff);
	}

	private boolean isBackwardCompletionBoundPruned(BackwardLabel label) {
		if (completionBounds == null || label.isSinkRoot || label.jid <= 0 || label.jid > data.n
				|| label.frontier == null || label.frontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction prefix = completionBounds.forwardUByJob[label.jid];
		if (prefix == null || prefix.head == null) {
			return false;
		}
		completionBoundFunctionEvaluations++;
		PiecewiseLinearFunction completion = prefix.add(label.frontier);
		if (completion.head == null) {
			return false;
		}
		double lowerBound = completion.findMinimal(false, true)[0];
		double cutoff = completionBoundCutoff();
		completionBoundLastEvaluationCutoff = cutoff;
		return !Utility.compareLt(lowerBound, cutoff);
	}

	private double positivePairLBMin() {
		return joinFunctionPositivePruned == 0 ? 0.0 : joinPositivePairLBMin;
	}

	private double positivePairLBAvg() {
		return joinFunctionPositivePruned == 0 ? 0.0 : joinPositivePairLBSum / joinFunctionPositivePruned;
	}

	private double positivePairLBMax() {
		return joinFunctionPositivePruned == 0 ? 0.0 : joinPositivePairLBMax;
	}

	private double positiveReducedCostMin() {
		return joinFunctionPositivePruned == 0 ? 0.0 : joinPositiveReducedCostMin;
	}

	private double positiveReducedCostAvg() {
		return joinFunctionPositivePruned == 0 ? 0.0 : joinPositiveReducedCostSum / joinFunctionPositivePruned;
	}

	private double positiveReducedCostMax() {
		return joinFunctionPositivePruned == 0 ? 0.0 : joinPositiveReducedCostMax;
	}

	// 2026-05-30: getForwardJoinExtension/getBackwardJoinExtension/valueAtOrNearest 是
	// half-domain crossing-arc join 的常数延拓残留。旧流程只保存半域 frontier，join 前需要
	// 把另一侧补成常数；当前 full-domain node join 直接拼 preNodeFrontier 和 backward.frontier，
	// 因此不再需要延拓缓存和最近端点取值。

	private void tryGenerateColumn(ArrayList<Integer> sequence, LP lp, double inferredReducedCost) {
		if (sequence.isEmpty() || config.maxExactPricingColumns <= 0) {
			return;
		}
		Node node = lp.getNode();
		if (Configure.debugBPCPricingColumnCheck && !isSequenceCompatible(sequence, node)) {
			return;
		}
		SequenceSignature signature = new SequenceSignature(sequence);
		if (activeColumnSignatures.contains(signature)) {
			return;
		}
		if (Utility.compareLt(inferredReducedCost, REDUCED_COST_TOLERANCE)) {
			if (joinBestThresholdMode == JoinBestThresholdMode.BEST_RECORD
					&& !Utility.compareLt(inferredReducedCost, joinLowerBoundThreshold())) {
				generatedCandidateDroppedByHeap++;
				return;
			}
			rememberGeneratedCandidate(signature, PricingColumnCostRechecker.buildInferredColumn(sequence,
					inferredReducedCost, lp, data, ColumnSource.PRICING_EXACT), inferredReducedCost);
		}
	}

	private void rememberGeneratedCandidate(SequenceSignature signature, TWETColumn column, double reducedCost) {
		generatedCandidateCount++;
		PricingColumnCandidate candidate = new PricingColumnCandidate(nextCandidateId++, signature, column,
				reducedCost);
		PricingColumnCandidate existing = generatedCandidateBySignature.get(signature);
		if (existing != null) {
			generatedCandidateDroppedByHeap++;
			return;
		}
		updateBestGeneratedReducedCost(reducedCost);
		if (generatedColumnCandidates.size() < config.maxExactPricingColumns) {
			generatedColumnCandidates.add(candidate);
			generatedCandidateBySignature.put(signature, candidate);
			return;
		}
		PricingColumnCandidate worstKept = generatedColumnCandidates.peek();
		if (worstKept != null && compareCandidateBestFirst(candidate, worstKept) < 0) {
			generatedCandidateDroppedByHeap++;
			generatedColumnCandidates.poll();
			generatedCandidateBySignature.remove(worstKept.signature);
			generatedColumnCandidates.add(candidate);
			generatedCandidateBySignature.put(signature, candidate);
			return;
		}
		generatedCandidateDroppedByHeap++;
	}

	private void finalizeGeneratedColumns(LP lp) {
		generatedColumns.clear();
		ArrayList<PricingColumnCandidate> candidates = new ArrayList<PricingColumnCandidate>(generatedColumnCandidates);
		Collections.sort(candidates, candidateBestFirstComparator());
		for (int i = 0; i < candidates.size(); i++) {
			PricingColumnCandidate candidate = candidates.get(i);
			if (!dualProfitableWindowEnabled) {
				generatedColumns.add(candidate.column);
				continue;
			}
			// 2026-05-31: 只有根节点 no-cut pi-window 会让 K 堆候选成本口径偏紧。
			// pi-window 是原 hard window 的子区间，因此 inferred 成本不低于真实列成本；
			// inferred reduced cost 已为负时，真实 reduced cost 只会更小，这里只修正列成本。
			PricingColumnCostRechecker.Result checked = PricingColumnCostRechecker.evaluate(candidate.column, data,
					evaluator);
			if (checked == null) {
				continue;
			}
			// 2026-05-31: node-join 实验分支不再记录 inferred/checked mismatch 统计。
			// 若后续重新启用该分支诊断，可在这里恢复 final K 候选的 debug 计数。
			generatedColumns.add(checked.checkedColumn(data));
		}
	}

	private boolean isSequenceCompatible(ArrayList<Integer> sequence, Node node) {
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

	private boolean isDirectForwardExtensionTimeFeasibleFullDomain(PiecewiseLinearFunction frontier, int prevJob,
			int nextJob) {
		return isDirectForwardExtensionTimeFeasible(frontier, prevJob, nextJob, false);
	}

	private boolean isDirectForwardExtensionTimeFeasible(PiecewiseLinearFunction frontier, int prevJob, int nextJob,
			boolean requireTmid) {
		if (frontier == null || frontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction jobPenalty = getDynamicForwardJobPenalty(prevJob, nextJob);
		if (jobPenalty == null) {
			return false;
		}
		double hStart = getDynamicForwardHStart(prevJob, nextJob);
		double hEnd = getDynamicForwardHEnd(prevJob, nextJob);
		double earliestCompletion = Math.max(
				frontier.head.start + data.getSetUp(prevJob, nextJob) + data.getProcessT(nextJob), hStart);
		return !Utility.compareGt(earliestCompletion, hEnd)
				&& (!requireTmid || !Utility.compareGt(earliestCompletion, tMid));
	}

	/**
	 * 2026-05-22: backward 侧和论文一致，先用本轮预计算的 H^b_{ir} 做 O(1) 交集过滤；
	 * 真正的 reduced-cost 函数仍在 extendBackward 里通过 shift/add/normalize 递推。
	 */
	private boolean isDirectBackwardExtensionTimeFeasibleFullDomain(int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier, int prevJob) {
		return isDirectBackwardExtensionTimeFeasible(firstJob, isSinkRoot, frontier, prevJob, false);
	}

	private boolean isDirectBackwardExtensionTimeFeasible(int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier, int prevJob, boolean requireTmid) {
		int successor = isSinkRoot ? data.n + 1 : firstJob;
		double rhoPrime;
		if (isSinkRoot) {
			rhoPrime = getDynamicBackwardHEnd(prevJob, successor);
		} else {
			double delay = data.getSetUp(prevJob, firstJob) + data.getProcessT(firstJob);
			rhoPrime = Math.min(frontier.tail.end - delay, getDynamicBackwardHEnd(prevJob, successor));
		}
		double hStart = getDynamicBackwardHStart(prevJob, successor);
		double lower = requireTmid ? Math.max(tMid, hStart) : hStart;
		return !Utility.compareLt(rhoPrime, lower);
	}

	private PackedBitSet buildForwardReachableSet(int fromJob, PackedBitSet visited, Node node,
			PiecewiseLinearFunction frontier) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		for (int job = 1; job <= data.n; job++) {
			// 2026-05-29: node-join 版本的 reachableSet 改为 full-domain 一跳可达集合，
			// 不再用 Tmid 裁掉第一次跨界的 job；Tmid 只在 child 生成后决定是否入队。
			// forbidden arc 只禁止当前 direct arc，不代表该 job 后续不能通过其他前驱访问，
			// 因此不能进入 dominance key；实际扩展仍在 canExtendForward 中单独检查 forbidden arc。
			if (!visited.contains(job)
					&& isDirectForwardExtensionTimeFeasibleFullDomain(frontier, fromJob, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private PackedBitSet buildForwardReachableSetFromParent(ForwardLabel parent, int fromJob, PackedBitSet visited,
			Node node, PiecewiseLinearFunction frontier) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		// 2026-05-29: 仍从父 reachableSet 过滤，保持单调收缩；但集合本身不再含 Tmid 裁剪。
		for (int job = parent.reachableSet.nextSetBit(1); job > 0 && job <= data.n;
				job = parent.reachableSet.nextSetBit(job + 1)) {
			if (!visited.contains(job)
					&& isDirectForwardExtensionTimeFeasibleFullDomain(frontier, fromJob, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private PackedBitSet buildBackwardReachableSet(int firstJob, PackedBitSet visited, Node node,
			PiecewiseLinearFunction frontier) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		boolean isSinkRoot = firstJob == node.sinkId();
		for (int job = 1; job <= data.n; job++) {
			if (!visited.contains(job)
					&& isDirectBackwardExtensionTimeFeasibleFullDomain(firstJob, isSinkRoot, frontier, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private PackedBitSet buildBackwardReachableSetFromParent(BackwardLabel parent, int firstJob, PackedBitSet visited,
			Node node, PiecewiseLinearFunction frontier) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		boolean isSinkRoot = firstJob == node.sinkId();
		// 2026-05-29: backward 对称使用 full-domain 一跳可达集合；是否越过 Tmid 左侧由
		// child 生成后的 latestBackwardCompletion 判断，不写入 reachableSet。
		for (int job = parent.reachableSet.nextSetBit(1); job > 0 && job <= data.n;
				job = parent.reachableSet.nextSetBit(job + 1)) {
			if (!visited.contains(job)
					&& isDirectBackwardExtensionTimeFeasibleFullDomain(firstJob, isSinkRoot, frontier, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private void precomputeDynamicPricingWindows(LP lp) {
		dynamicJobPenaltyByJob = null;
		dynamicJobHStart = null;
		dynamicJobHEnd = null;
		effectiveJobHStart = null;
		effectiveJobHEnd = null;
		dynamicBackwardPenaltyByJob = null;
		dynamicBackwardHStartByJob = null;
		dynamicBackwardHEndByJob = null;
		zeroDualExcludedJobs = null;
		zeroDualExcludedJobCount = 0;
		dualProfitableWindowEnabled = canUseDualProfitableWindow(lp);
		precomputeEffectivePricingWindows(lp);
		tMid = computeCurrentMidpoint();
		precomputeZeroDualExcludedJobs(lp);
		ensureBaseHalfPenaltyCache();
		precomputeJobLevelDynamicPricingWindows();
		precomputeBackwardDynamicPricingWindows();
	}

	private void precomputeEffectivePricingWindows(LP lp) {
		effectiveJobHStart = new double[data.n + 1];
		effectiveJobHEnd = new double[data.n + 1];
		dynamicMinHStart = Utility.big_M;
		dynamicMaxHEnd = 0.0;
		earliestSourceCompletion = computeEarliestSourceCompletion();

		if (!dualProfitableWindowEnabled) {
			pricingHorizon = data.CmaxH;
			for (int job = 1; job <= data.n; job++) {
				recordEffectiveWindow(job, data.hardWindowStart[job], data.hardWindowEnd[job]);
			}
			finalizeEffectiveWindowStatistics(false, data.CmaxH);
			return;
		}

		double localHorizon = 0.0;
		boolean foundFiniteWindow = false;
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			double baseline = outsourcingBaseline(job);
			double jobDual = Math.max(0.0, lp.getJobDual(job));
			if (Utility.compareLt(jobDual, baseline)) {
				double dynamicStart = hWindowStart(job, jobDual);
				double dynamicEnd = hWindowEnd(job, jobDual);
				if (Utility.compareGt(dynamicStart, data.hardWindowStart[job])
						|| Utility.compareLt(dynamicEnd, data.hardWindowEnd[job])) {
					hStart = dynamicStart;
					hEnd = dynamicEnd;
				}
			}
			recordEffectiveWindow(job, hStart, hEnd);
			if (!Utility.compareGt(hStart, hEnd) && Double.isFinite(hEnd)) {
				localHorizon = Math.max(localHorizon, hEnd);
				foundFiniteWindow = true;
			}
		}
		finalizeEffectiveWindowStatistics(foundFiniteWindow, localHorizon);
	}

	private void recordEffectiveWindow(int job, double hStart, double hEnd) {
		effectiveJobHStart[job] = hStart;
		effectiveJobHEnd[job] = hEnd;
		if (!Utility.compareGt(hStart, hEnd)) {
			if (Double.isFinite(hStart)) {
				dynamicMinHStart = Math.min(dynamicMinHStart, hStart);
			}
			dynamicMaxHEnd = Math.max(dynamicMaxHEnd, hEnd);
		}
	}

	private void finalizeEffectiveWindowStatistics(boolean useLocalHorizon, double localHorizon) {
		if (Utility.isBigMValue(dynamicMinHStart)) {
			dynamicMinHStart = 0.0;
		}
		pricingHorizon = useLocalHorizon ? Math.min(data.CmaxH, localHorizon) : data.CmaxH;
		dynamicMaxHEnd = Math.max(dynamicMaxHEnd, pricingHorizon);
	}

	private double computeCurrentMidpoint() {
		double candidate;
		if (Double.isFinite(config.bidirectionalRootLocalHorizonMidpointRatio)
				&& Utility.compareGt(config.bidirectionalRootLocalHorizonMidpointRatio, 0.0)
				&& Utility.compareLt(config.bidirectionalRootLocalHorizonMidpointRatio, 1.0)) {
			candidate = pricingHorizon * config.bidirectionalRootLocalHorizonMidpointRatio;
			return clampCurrentMidpoint(candidate);
		}
		double left = Math.max(dynamicMinHStart, earliestSourceCompletion);
		if (Double.isFinite(left) && Utility.compareLt(left, pricingHorizon)) {
			candidate = (left + pricingHorizon) * 0.5;
		} else {
			// 2026-05-26: 当局部窗口已经贴到 pricingHorizon 时，回退到右偏切分，避免后向半区间长度为 0。
			candidate = pricingHorizon * 0.75;
		}
		return clampCurrentMidpoint(candidate);
	}

	private double clampCurrentMidpoint(double candidate) {
		if (!Double.isFinite(pricingHorizon) || !Utility.compareGt(pricingHorizon, 0.0)) {
			return 0.0;
		}
		// 正常 midpoint 公式应已落在 (0, pricingHorizon) 内；这里仅防御极小 horizon 或后续改公式造成贴边。
		double minWidth = Math.max(Utility.EPS * 10.0, pricingHorizon * 1e-9);
		if (!Utility.compareGt(pricingHorizon, 2.0 * minWidth)) {
			return pricingHorizon * 0.5;
		}
		if (!Double.isFinite(candidate)) {
			candidate = pricingHorizon * 0.75;
		}
		double lower = minWidth;
		double upper = pricingHorizon - minWidth;
		if (Utility.compareLt(candidate, lower)) {
			return lower;
		}
		if (Utility.compareGt(candidate, upper)) {
			return upper;
		}
		return candidate;
	}

	private double computeEarliestSourceCompletion() {
		double earliest = Utility.big_M;
		for (int job = 1; job <= data.n; job++) {
			earliest = Math.min(earliest, data.getSetUp(0, job) + data.getProcessT(job));
		}
		return earliest;
	}

	private void precomputeJobLevelDynamicPricingWindows() {
		dynamicJobPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicJobHStart = new double[data.n + 1];
		dynamicJobHEnd = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = effectiveJobHStart[job];
			double hEnd = effectiveJobHEnd[job];
			PiecewiseLinearFunction penalty = baseForwardHalfPenaltyByJob[job];
			if (isEffectiveWindowTighterThanHard(job)) {
				penalty = Utility.compareGt(hStart, hEnd) ? null : buildForwardHalfPenalty(job, hStart, hEnd);
			}
			dynamicJobHStart[job] = hStart;
			dynamicJobHEnd[job] = hEnd;
			dynamicJobPenaltyByJob[job] = penalty;
		}
	}

	private void precomputeBackwardDynamicPricingWindows() {
		dynamicBackwardPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicBackwardHStartByJob = new double[data.n + 1];
		dynamicBackwardHEndByJob = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = effectiveJobHStart[job];
			double hEnd = effectiveJobHEnd[job];
			PiecewiseLinearFunction penalty = baseBackwardHalfPenaltyByJob[job];
			if (isEffectiveWindowTighterThanHard(job)) {
				penalty = Utility.compareGt(hStart, hEnd) ? null : buildBackwardHalfPenalty(job, hStart, hEnd);
			}
			dynamicBackwardHStartByJob[job] = hStart;
			dynamicBackwardHEndByJob[job] = hEnd;
			dynamicBackwardPenaltyByJob[job] = penalty;
		}
	}

	private boolean isEffectiveWindowTighterThanHard(int job) {
		return Utility.compareGt(effectiveJobHStart[job], data.hardWindowStart[job])
				|| Utility.compareLt(effectiveJobHEnd[job], data.hardWindowEnd[job]);
	}

	private void ensureBaseHalfPenaltyCache() {
		if (baseForwardHalfPenaltyByJob != null && Utility.compareEq(baseHalfPenaltyCacheTMid, tMid)
				&& Utility.compareEq(baseHalfPenaltyCacheHorizon, pricingHorizon)) {
			return;
		}
		baseForwardHalfPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		baseBackwardHalfPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			// 2026-05-28: full-domain 对照版本保留原来的可达性和 join 流程，只放宽标签函数定义域。
			baseForwardHalfPenaltyByJob[job] = cropToInterval(data.penaltyFunction[job], 0.0, pricingHorizon);
			baseBackwardHalfPenaltyByJob[job] = cropToInterval(data.penaltyFunction[job], 0.0, pricingHorizon);
		}
		baseHalfPenaltyCacheTMid = tMid;
		baseHalfPenaltyCacheHorizon = pricingHorizon;
	}

	private boolean canUseDualProfitableWindow(LP lp) {
		Node node = lp.getNode();
		if (node == null || node.depth != 0) {
			return false;
		}
		// cut dual 或分支 dual 都可能让 reduced arc cost 不再满足原始 setup cost 的三角不等式。
		// 当前只在根节点、且没有 active cuts 时使用 pi_j 进一步收紧静态外包窗。
		return lp.getActiveCutIds().isEmpty();
	}

	/**
	 * 2026-05-28: 根节点 no-cut pricing 中，pi_j=0 的任务不进入 pricing 扩展。
	 * 在当前无 cut/branch dual 的三角不等式语义下，这类 job 不可能改善负 reduced-cost 列。
	 */
	private void precomputeZeroDualExcludedJobs(LP lp) {
		if (!dualProfitableWindowEnabled) {
			return;
		}
		zeroDualExcludedJobs = new boolean[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double jobDual = Math.max(0.0, lp.getJobDual(job));
			if (Utility.compareEq(jobDual, 0.0)) {
				zeroDualExcludedJobs[job] = true;
				zeroDualExcludedJobCount++;
			}
		}
	}

	private PiecewiseLinearFunction buildForwardHalfPenalty(int job, double hStart, double hEnd) {
		// 2026-05-28: full-domain 对照版本不按 Tmid 裁剪新增 job 函数。
		return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), 0.0, pricingHorizon);
	}

	private PiecewiseLinearFunction buildBackwardHalfPenalty(int job, double hStart, double hEnd) {
		// 2026-05-28: full-domain 对照版本不按 Tmid 裁剪新增 job 函数。
		return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), 0.0, pricingHorizon);
	}

	private PiecewiseLinearFunction getDynamicForwardJobPenalty(int prevJob, int job) {
		return dynamicJobPenaltyByJob == null ? null : dynamicJobPenaltyByJob[job];
	}

	private double getDynamicForwardHEnd(int prevJob, int job) {
		return dynamicJobHEnd[job];
	}

	private double getDynamicForwardHStart(int prevJob, int job) {
		return dynamicJobHStart[job];
	}

	private PiecewiseLinearFunction getDynamicBackwardJobPenalty(int job, int successor) {
		return dynamicBackwardPenaltyByJob == null ? null : dynamicBackwardPenaltyByJob[job];
	}

	private double getDynamicBackwardHStart(int job, int successor) {
		return dynamicBackwardHStartByJob[job];
	}

	private double getDynamicBackwardHEnd(int job, int successor) {
		return dynamicBackwardHEndByJob[job];
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
		return Utility.isBigMValue(data.outsourcingCost[job]) ? Utility.big_M : Math.max(0.0, data.outsourcingCost[job]);
	}

	private boolean isZeroDualExcludedJob(int job) {
		return job > 0 && zeroDualExcludedJobs != null && job < zeroDualExcludedJobs.length
				&& zeroDualExcludedJobs[job];
	}

	private void addZeroDualExcludedJobs(PackedBitSet visited) {
		if (zeroDualExcludedJobs == null) {
			return;
		}
		for (int job = 1; job <= data.n; job++) {
			if (isZeroDualExcludedJob(job)) {
				visited.add(job);
			}
		}
	}

	private ArrayList<Integer> recoverForwardSequence(ForwardLabel label) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		ForwardLabel cursor = label;
		while (cursor != null && cursor.jid != 0) {
			sequence.add(Integer.valueOf(cursor.jid));
			cursor = cursor.father;
		}
		reverseInPlace(sequence);
		return sequence;
	}

	private ArrayList<Integer> recoverBackwardSequence(BackwardLabel label) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		BackwardLabel cursor = label;
		while (cursor != null && !cursor.isSinkRoot) {
			sequence.add(Integer.valueOf(cursor.jid));
			cursor = cursor.father;
		}
		return sequence;
	}

	private ArrayList<Integer> recoverNodeJoinSequence(ForwardLabel forward, BackwardLabel backward) {
		ArrayList<Integer> sequence = recoverForwardSequence(forward);
		ArrayList<Integer> suffix = recoverBackwardSequence(backward);
		// 2026-05-29: node join 两侧都包含 join job；完整序列只保留 forward 侧的那一次。
		for (int i = 1; i < suffix.size(); i++) {
			sequence.add(suffix.get(i));
		}
		return sequence;
	}

	private boolean intersectsExceptJoin(PackedBitSet left, PackedBitSet right, int joinJob) {
		for (int job = left.nextSetBit(1); job >= 0; job = left.nextSetBit(job + 1)) {
			// 2026-05-29: source/sink 会共同预标记 zero-dual excluded job；
			// node join 的路径冲突检查要忽略这些非真实访问标记。
			if (job != joinJob && !isZeroDualExcludedJob(job) && right.contains(job)) {
				return true;
			}
		}
		return false;
	}

	private void reverseInPlace(ArrayList<Integer> sequence) {
		for (int left = 0, right = sequence.size() - 1; left < right; left++, right--) {
			Integer tmp = sequence.get(left);
			sequence.set(left, sequence.get(right));
			sequence.set(right, tmp);
		}
	}

	/**
	 * 2026-05-24: normal forward label 经 prefix-min normalize 后整体非增，
	 * 最小 reduced cost 直接落在最右端，不必每次再全段 findMinimal。
	 */
	private static double forwardEndpointMin(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.tail == null) {
			return Utility.big_M;
		}
		return frontier.tail.getValue(frontier.tail.end);
	}

	/**
	 * 2026-05-24: normal backward label 经 suffix-min normalize 后整体非减，
	 * 最小 reduced cost 直接落在最左端；只有 joinCost 那种未再方向化的函数才需要 findMinimal。
	 */
	private static double backwardEndpointMin(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.head == null) {
			return Utility.big_M;
		}
		return frontier.head.getValue(frontier.head.start);
	}

	private PiecewiseLinearFunction cropToInterval(PiecewiseLinearFunction function, double start, double end) {
		PiecewiseLinearFunction cropped = new PiecewiseLinearFunction();
		// 2026-05-23: crop 不只裁物理 segment，也要同步函数元数据。
		// shiftX() 的 trimToDomain 只看 domainStart/domainEnd；如果这里不重设，
		// 后续半域 label 会只能靠 add 的公共物理定义域兜底，不能自然按 Tmid 裁剪。
		cropped.resetDomain(start, end);
		if (function == null || function.head == null || Utility.compareGt(start, end)) {
			return cropped;
		}
		// 2026-05-22: 双向半域可能刚好退化到 Tmid 单点。这个点不参与继续扩展，
		// 但 join 时要能用 Tmid 处常数延拓评价，因此这里保留零长度常数段。
		if (Utility.compareEq(start, end)) {
			if (!Utility.compareLt(start, function.head.start) && !Utility.compareGt(start, function.tail.end)) {
				addConstantSegmentOrPoint(cropped, start, end, function.evaluate(start));
			}
			return cropped;
		}
		for (Segment seg = function.head; seg != null; seg = seg.next) {
			if (Utility.compareEq(seg.start, seg.end)
					&& !Utility.compareLt(seg.start, start)
					&& !Utility.compareGt(seg.start, end)) {
				addConstantSegmentOrPoint(cropped, seg.start, seg.end, seg.getValue(seg.start));
				continue;
			}
			double segStart = Math.max(seg.start, start);
			double segEnd = Math.min(seg.end, end);
			if (Utility.compareLt(segStart, segEnd)) {
				cropped.addSegment(segStart, segEnd, seg.slope, seg.intercept);
			}
		}
		mergeAdjacentEqualSegments(cropped);
		return cropped;
	}

	private void addConstantSegmentOrPoint(PiecewiseLinearFunction target, double start, double end, double value) {
		target.addSegment(start, end, 0.0, value);
	}

	private void mergeAdjacentEqualSegments(PiecewiseLinearFunction function) {
		if (function == null || function.head == null) {
			return;
		}
		Segment cur = function.head;
		while (cur.next != null) {
			if (Utility.compareEq(cur.end, cur.next.start) && Utility.compareEq(cur.slope, cur.next.slope)
					&& Utility.compareEq(cur.intercept, cur.next.intercept)) {
				cur.end = cur.next.end;
				cur.next = cur.next.next;
			} else {
				cur = cur.next;
			}
		}
		function.tail = cur;
	}

	private enum InsertStatus {
		DOMINATED, STORED_NO_EXPAND, STORED_AND_ENQUEUE
	}

	private final class CompletionBoundBuilder {
		private final LP lp;
		private final Node node;
		private final int sink;

		CompletionBoundBuilder(LP lp) {
			this.lp = lp;
			this.node = lp.getNode();
			this.sink = node.sinkId();
		}

		CompletionBounds build(CompletionBoundRelaxation relaxation) {
			if (relaxation == CompletionBoundRelaxation.TWO_CYCLE) {
				return buildTwoCycle();
			}
			return buildAllCycles();
		}

		private CompletionBounds buildAllCycles() {
			CompletionBounds bounds = new CompletionBounds(data.n);
			PiecewiseLinearFunction[] forwardF = new PiecewiseLinearFunction[data.n + 1];
			PiecewiseLinearFunction[] backwardB = new PiecewiseLinearFunction[data.n + 1];
			ArrayDeque<Integer> forwardQueue = new ArrayDeque<Integer>();
			ArrayDeque<Integer> backwardQueue = new ArrayDeque<Integer>();
			boolean[] inForwardQueue = new boolean[data.n + 1];
			boolean[] inBackwardQueue = new boolean[data.n + 1];

			PiecewiseLinearFunction source = sourcePropagationFunction(lp);
			for (int job = 1; job <= data.n; job++) {
				if (!isCompletionJobAvailable(job) || node.isArcForbidden(0, job)) {
					continue;
				}
				FunctionPair candidate = buildForwardCandidate(source, 0, job);
				if (candidate == null) {
					continue;
				}
				mergeForward(bounds.forwardUByJob, job, candidate.u);
				boolean fChanged = mergeForward(forwardF, job, candidate.f);
				if (fChanged) {
					forwardQueue.add(Integer.valueOf(job));
					inForwardQueue[job] = true;
				}
			}
			while (!forwardQueue.isEmpty()) {
				int prev = forwardQueue.poll().intValue();
				inForwardQueue[prev] = false;
				PiecewiseLinearFunction prevF = forwardF[prev];
				if (prevF == null || prevF.head == null) {
					continue;
				}
				for (int job = 1; job <= data.n; job++) {
					if (job == prev || !isCompletionJobAvailable(job) || node.isArcForbidden(prev, job)) {
						continue;
					}
					FunctionPair candidate = buildForwardCandidate(prevF, prev, job);
					if (candidate == null) {
						continue;
					}
					mergeForward(bounds.forwardUByJob, job, candidate.u);
					if (mergeForward(forwardF, job, candidate.f) && !inForwardQueue[job]) {
						forwardQueue.add(Integer.valueOf(job));
						inForwardQueue[job] = true;
					}
				}
			}

			for (int job = 1; job <= data.n; job++) {
				if (!isCompletionJobAvailable(job) || node.isArcForbidden(job, sink)) {
					continue;
				}
				FunctionPair candidate = buildBackwardSinkCandidate(job);
				if (candidate == null) {
					continue;
				}
				mergeBackward(bounds.backwardRByJob, job, candidate.u);
				boolean bChanged = mergeBackward(backwardB, job, candidate.f);
				if (bChanged) {
					backwardQueue.add(Integer.valueOf(job));
					inBackwardQueue[job] = true;
				}
			}
			while (!backwardQueue.isEmpty()) {
				int successor = backwardQueue.poll().intValue();
				inBackwardQueue[successor] = false;
				PiecewiseLinearFunction successorB = backwardB[successor];
				if (successorB == null || successorB.head == null) {
					continue;
				}
				for (int prev = 1; prev <= data.n; prev++) {
					if (prev == successor || !isCompletionJobAvailable(prev) || node.isArcForbidden(prev, successor)) {
						continue;
					}
					FunctionPair candidate = buildBackwardCandidate(successorB, prev, successor);
					if (candidate == null) {
						continue;
					}
					mergeBackward(bounds.backwardRByJob, prev, candidate.u);
					if (mergeBackward(backwardB, prev, candidate.f) && !inBackwardQueue[prev]) {
						backwardQueue.add(Integer.valueOf(prev));
						inBackwardQueue[prev] = true;
					}
				}
			}
			return bounds;
		}

		private CompletionBounds buildTwoCycle() {
			CompletionBounds bounds = new CompletionBounds(data.n);
			PiecewiseLinearFunction[][] forwardU = new PiecewiseLinearFunction[data.n + 1][data.n + 1];
			PiecewiseLinearFunction[][] forwardF = new PiecewiseLinearFunction[data.n + 1][data.n + 1];
			PiecewiseLinearFunction[][] backwardR = new PiecewiseLinearFunction[data.n + 1][data.n + 2];
			PiecewiseLinearFunction[][] backwardB = new PiecewiseLinearFunction[data.n + 1][data.n + 2];
			ArrayDeque<int[]> forwardQueue = new ArrayDeque<int[]>();
			ArrayDeque<int[]> backwardQueue = new ArrayDeque<int[]>();
			boolean[][] inForwardQueue = new boolean[data.n + 1][data.n + 1];
			boolean[][] inBackwardQueue = new boolean[data.n + 1][data.n + 2];

			PiecewiseLinearFunction source = sourcePropagationFunction(lp);
			for (int job = 1; job <= data.n; job++) {
				if (!isCompletionJobAvailable(job) || node.isArcForbidden(0, job)) {
					continue;
				}
				FunctionPair candidate = buildForwardCandidate(source, 0, job);
				if (candidate == null) {
					continue;
				}
				mergeForward(forwardU[0], job, candidate.u);
				boolean fChanged = mergeForward(forwardF[0], job, candidate.f);
				if (fChanged) {
					forwardQueue.add(new int[] { 0, job });
					inForwardQueue[0][job] = true;
				}
			}
			while (!forwardQueue.isEmpty()) {
				int[] state = forwardQueue.poll();
				int prevPrev = state[0];
				int prev = state[1];
				inForwardQueue[prevPrev][prev] = false;
				PiecewiseLinearFunction prevF = forwardF[prevPrev][prev];
				if (prevF == null || prevF.head == null) {
					continue;
				}
				for (int job = 1; job <= data.n; job++) {
					if (job == prev || job == prevPrev || !isCompletionJobAvailable(job)
							|| node.isArcForbidden(prev, job)) {
						continue;
					}
					FunctionPair candidate = buildForwardCandidate(prevF, prev, job);
					if (candidate == null) {
						continue;
					}
					mergeForward(forwardU[prev], job, candidate.u);
					if (mergeForward(forwardF[prev], job, candidate.f) && !inForwardQueue[prev][job]) {
						forwardQueue.add(new int[] { prev, job });
						inForwardQueue[prev][job] = true;
					}
				}
			}

			for (int job = 1; job <= data.n; job++) {
				if (!isCompletionJobAvailable(job) || node.isArcForbidden(job, sink)) {
					continue;
				}
				FunctionPair candidate = buildBackwardSinkCandidate(job);
				if (candidate == null) {
					continue;
				}
				mergeBackward(backwardR[job], sink, candidate.u);
				boolean bChanged = mergeBackward(backwardB[job], sink, candidate.f);
				if (bChanged) {
					backwardQueue.add(new int[] { job, sink });
					inBackwardQueue[job][sink] = true;
				}
			}
			while (!backwardQueue.isEmpty()) {
				int[] state = backwardQueue.poll();
				int current = state[0];
				int successor = state[1];
				inBackwardQueue[current][successor] = false;
				PiecewiseLinearFunction currentB = backwardB[current][successor];
				if (currentB == null || currentB.head == null) {
					continue;
				}
				for (int prev = 1; prev <= data.n; prev++) {
					if (prev == current || prev == successor || !isCompletionJobAvailable(prev)
							|| node.isArcForbidden(prev, current)) {
						continue;
					}
					FunctionPair candidate = buildBackwardCandidate(currentB, prev, current);
					if (candidate == null) {
						continue;
					}
					mergeBackward(backwardR[prev], current, candidate.u);
					if (mergeBackward(backwardB[prev], current, candidate.f) && !inBackwardQueue[prev][current]) {
						backwardQueue.add(new int[] { prev, current });
						inBackwardQueue[prev][current] = true;
					}
				}
			}

			for (int job = 1; job <= data.n; job++) {
				for (int prev = 0; prev <= data.n; prev++) {
					mergeForward(bounds.forwardUByJob, job, forwardU[prev][job]);
				}
				for (int successor = 1; successor <= data.n + 1; successor++) {
					mergeBackward(bounds.backwardRByJob, job, backwardR[job][successor]);
				}
			}
			return bounds;
		}

		private FunctionPair buildForwardCandidate(PiecewiseLinearFunction parentF, int prevJob, int job) {
			if (parentF == null || parentF.head == null) {
				return null;
			}
			double delay = data.getSetUp(prevJob, job) + data.getProcessT(job);
			PiecewiseLinearFunction u = parentF.shiftX(delay);
			if (!hasPositiveDomain(u)) {
				return null;
			}
			u.shiftYInPlace(data.getSetupCost(prevJob, job) - lp.getArcDual(prevJob, job));
			u.normalize(Direction.FORWARD);
			PiecewiseLinearFunction jobCost = forwardJobReducedPenalty(job);
			if (jobCost == null) {
				return null;
			}
			PiecewiseLinearFunction f = u.add(jobCost);
			if (!hasPositiveDomain(f)) {
				return null;
			}
			f.normalize(Direction.FORWARD);
			return hasPositiveDomain(f) ? new FunctionPair(u, f) : null;
		}

		private FunctionPair buildBackwardSinkCandidate(int job) {
			PiecewiseLinearFunction r = constantFunction(-lp.getArcDual(job, sink));
			PiecewiseLinearFunction jobCost = backwardJobReducedPenalty(job);
			if (jobCost == null) {
				return null;
			}
			PiecewiseLinearFunction b = r.add(jobCost);
			if (!hasPositiveDomain(b)) {
				return null;
			}
			b.normalize(Direction.BACKWARD);
			return hasPositiveDomain(b) ? new FunctionPair(r, b) : null;
		}

		private FunctionPair buildBackwardCandidate(PiecewiseLinearFunction successorB, int job, int successor) {
			if (successorB == null || successorB.head == null) {
				return null;
			}
			double delay = data.getSetUp(job, successor) + data.getProcessT(successor);
			PiecewiseLinearFunction r = successorB.shiftX(-delay);
			if (!hasPositiveDomain(r)) {
				return null;
			}
			r.shiftYInPlace(data.getSetupCost(job, successor) - lp.getArcDual(job, successor));
			r.normalize(Direction.BACKWARD);
			PiecewiseLinearFunction jobCost = backwardJobReducedPenalty(job);
			if (jobCost == null) {
				return null;
			}
			PiecewiseLinearFunction b = r.add(jobCost);
			if (!hasPositiveDomain(b)) {
				return null;
			}
			b.normalize(Direction.BACKWARD);
			return hasPositiveDomain(b) ? new FunctionPair(r, b) : null;
		}

		private PiecewiseLinearFunction sourcePropagationFunction(LP lp) {
			PiecewiseLinearFunction source = cropToInterval(data.penaltyFunction[0].copy(), 0.0, pricingHorizon);
			source.shiftYInPlace(-lp.getMachineDual());
			source.normalize(Direction.FORWARD);
			return source;
		}

		private PiecewiseLinearFunction forwardJobReducedPenalty(int job) {
			PiecewiseLinearFunction penalty = getDynamicForwardJobPenalty(0, job);
			if (penalty == null) {
				return null;
			}
			PiecewiseLinearFunction reduced = penalty.copy();
			reduced.shiftYInPlace(-lp.getJobDual(job));
			return reduced;
		}

		private PiecewiseLinearFunction backwardJobReducedPenalty(int job) {
			PiecewiseLinearFunction penalty = getDynamicBackwardJobPenalty(job, sink);
			if (penalty == null) {
				return null;
			}
			PiecewiseLinearFunction reduced = penalty.copy();
			reduced.shiftYInPlace(-lp.getJobDual(job));
			return reduced;
		}

		private PiecewiseLinearFunction constantFunction(double value) {
			PiecewiseLinearFunction function = new PiecewiseLinearFunction();
			function.resetDomain(0.0, pricingHorizon);
			function.addSegment(0.0, pricingHorizon, 0.0, value);
			return function;
		}

		private boolean isCompletionJobAvailable(int job) {
			return job > 0 && job <= data.n && !isZeroDualExcludedJob(job);
		}
	}

	private boolean mergeForward(PiecewiseLinearFunction[] targetByJob, int job, PiecewiseLinearFunction candidate) {
		return mergeFunction(targetByJob, job, candidate, Direction.FORWARD);
	}

	private boolean mergeBackward(PiecewiseLinearFunction[] targetByJob, int job, PiecewiseLinearFunction candidate) {
		return mergeFunction(targetByJob, job, candidate, Direction.BACKWARD);
	}

	private boolean mergeFunction(PiecewiseLinearFunction[] targetByJob, int job, PiecewiseLinearFunction candidate,
			Direction direction) {
		if (!hasPositiveDomain(candidate)) {
			return false;
		}
		PiecewiseLinearFunction current = targetByJob[job];
		if (current == null || current.head == null) {
			targetByJob[job] = candidate.copy();
			return true;
		}
		PiecewiseLinearFunction before = current.copy();
		current.mergeMinimum(candidate, direction);
		return !sameFunction(before, current);
	}

	private boolean hasPositiveDomain(PiecewiseLinearFunction function) {
		return function != null && function.head != null && function.tail != null
				&& Utility.compareLt(function.head.start, function.tail.end);
	}

	private boolean sameFunction(PiecewiseLinearFunction left, PiecewiseLinearFunction right) {
		Segment l = left == null ? null : left.head;
		Segment r = right == null ? null : right.head;
		while (l != null && r != null) {
			if (!Utility.compareEq(l.start, r.start) || !Utility.compareEq(l.end, r.end)
					|| !Utility.compareEq(l.slope, r.slope) || !Utility.compareEq(l.intercept, r.intercept)) {
				return false;
			}
			l = l.next;
			r = r.next;
		}
		return l == null && r == null;
	}

	private static final class CompletionBounds {
		final PiecewiseLinearFunction[] forwardUByJob;
		final PiecewiseLinearFunction[] backwardRByJob;

		CompletionBounds(int n) {
			this.forwardUByJob = new PiecewiseLinearFunction[n + 1];
			this.backwardRByJob = new PiecewiseLinearFunction[n + 1];
		}
	}

	private static final class FunctionPair {
		final PiecewiseLinearFunction u;
		final PiecewiseLinearFunction f;

		FunctionPair(PiecewiseLinearFunction u, PiecewiseLinearFunction f) {
			this.u = u;
			this.f = f;
		}
	}

	private static final class PricingColumnCandidate {
		final int candidateId;
		final SequenceSignature signature;
		final TWETColumn column;
		final double reducedCost;

		PricingColumnCandidate(int candidateId, SequenceSignature signature, TWETColumn column, double reducedCost) {
			this.candidateId = candidateId;
			this.signature = signature;
			this.column = column;
			this.reducedCost = reducedCost;
		}
	}

	private static final class SinglePointStore<L extends FunctionLabel> {
		final HashMap<PackedBitSet, L> bestByReachable = new HashMap<PackedBitSet, L>();
		// 2026-05-25: single-point 只按 reachable-set 支配，不需要复杂图结构；
		// 但按基数分桶后，superset/subset 扫描可以少看很多明显不可能的候选。
		final ArrayList<ArrayList<L>> liveLabelsByCardinality = new ArrayList<ArrayList<L>>();
	}

	private abstract static class FunctionLabel extends Label implements Comparable<Label> {
		final int labelId;

		FunctionLabel(int labelId, int jid, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PiecewiseLinearFunction frontier, double minReducedCost) {
			super(jid, null, visitedSet, reachableSet, frontier, minReducedCost);
			this.labelId = labelId;
		}

		@Override
		public int compareTo(Label other) {
			if (other instanceof FunctionLabel) {
				return compareReducedCost(this, (FunctionLabel) other);
			}
			if (Utility.compareLt(minReducedCost, other.minReducedCost)) {
				return -1;
			}
			if (Utility.compareGt(minReducedCost, other.minReducedCost)) {
				return 1;
			}
			return Integer.compare(jid, other.jid);
		}
	}

	private static final class ForwardLabel extends FunctionLabel {
		final ForwardLabel father;
		/** 2026-05-29: 当前 job 成本之前的 U_state，用于同 job node join，避免 join node 双计数。 */
		final PiecewiseLinearFunction preNodeFrontier;
		final double preNodeMinReducedCost;

		ForwardLabel(int labelId, int jid, ForwardLabel father, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PiecewiseLinearFunction frontier, PiecewiseLinearFunction preNodeFrontier) {
			super(labelId, jid, visitedSet, reachableSet, frontier, forwardEndpointMin(frontier));
			this.father = father;
			this.preNodeFrontier = preNodeFrontier;
			this.preNodeMinReducedCost = forwardEndpointMin(preNodeFrontier);
		}
	}

	private static final class BackwardLabel extends FunctionLabel {
		final BackwardLabel father;
		final boolean isSinkRoot;

		BackwardLabel(int labelId, int jid, BackwardLabel father, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PiecewiseLinearFunction frontier, boolean isSinkRoot) {
			super(labelId, jid, visitedSet, reachableSet, frontier, backwardEndpointMin(frontier));
			this.father = father;
			this.isSinkRoot = isSinkRoot;
		}

		static BackwardLabel sink(int labelId, int sinkId, PackedBitSet visitedSet, PiecewiseLinearFunction frontier,
				PackedBitSet reachableSet) {
			return new BackwardLabel(labelId, sinkId, null, visitedSet, reachableSet, frontier, true);
		}
	}
}
