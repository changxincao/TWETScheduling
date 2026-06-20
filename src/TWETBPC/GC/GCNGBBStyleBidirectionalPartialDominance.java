package TWETBPC.GC;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.Segment;
import Common.Utility;
import HEU.Solution;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.PackedBitSet;
import TWETBPC.Util.SequenceSignature;

/**
 * no-cut 双向 pricing 的正式 half-domain GCBB-style 基准版。
 * <p>
 * 只有在列数上限未截断、且 forward/backward 队列都被完整耗尽时，本轮结果才可作为 exact pricing
 * certificate；若达到 {@link TWETBPCConfig#maxExactPricingColumns}，这里只表示“最多生成 K 条负列”。
 * <p>
 * 2026-05-22: 这里不再沿用旧实现的“同一个中间点 join”标量标签，而是改成和论文一致的
 * “forward 前缀 + crossing arc (i,r) + backward 后缀”的弧拼接。
 * 类名中的 GCNGBB 保留早期命名；当前实现没有 NG memory，实际语义更接近 GCBB-style
 * final join。相对 {@link GCBidirectional}，本类先完整生成 forward/backward 两侧 label
 * table，再统一做 crossing-arc final join；forward->sink 收尾也并入 final join 流程，并用本地
 * top-K 候选池统一按 reduced cost 输出，减少出队顺序对列返回集合的影响。
 * <p>
 * 当前版本先保证 elementary 双向函数递推和 T^mid 半域语义正确：
 * 1. forward label 存储在 [ell, Tmid]；
 * 2. backward label 存储在 [Tmid, rho]；
 * 3. join 时用论文里的常数延拓，临时补齐 forward 右半域和 backward 左半域，然后按 crossing arc 对齐相加；
 * 4. 默认直接使用 label/join 推导出的 reduced cost 反推出列成本；如需完整序列复核，可打开
 * {@link Configure#debugBPCPricingColumnCheck}。
 */
public class GCNGBBStyleBidirectionalPartialDominance {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;
	private static final HashSet<Integer> FULL_MIDPOINT_DIAGNOSTIC_DONE = new HashSet<Integer>();
	private enum LabelQueueOrdering {
		REDUCED_COST, TIME, REACHABLE_SIZE
	}

	private enum JoinBestThresholdMode {
		ZERO,
		BEST_UB,
		// 2026-05-31: 激进 record-only 对照模式；会减少每轮返回列数，默认不作为后续正式路径使用。
		BEST_RECORD
	}

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;
	private final HashMap<Integer, MidpointProbeNodeReuse> midpointProbeReuseByNode;

	private PriorityQueue<ForwardLabel> FWUL;
	private PriorityQueue<BackwardLabel> BWUL;
	private ArrayList<DominanceStore> FWTL;
	private ArrayList<DominanceStore> BWTL;
	private ArrayList<ArrayList<ForwardLabel>> activeForwardByLastJob;
	private ArrayList<ArrayList<BackwardLabel>> activeBackwardByFirstJob;
	private ArrayList<SinglePointStore<ForwardLabel>> forwardSinglePointByLastJob;
	private ArrayList<SinglePointStore<BackwardLabel>> backwardSinglePointByFirstJob;
	private PackedBitSet activeForwardTerminalJobs;
	private double[] minForwardReducedCostByLastJob;
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
	private CompletionBoundCalculator.Relaxation completionBoundRelaxation;
	private CompletionBoundCalculator.QueueOrdering completionBoundQueueOrdering;
	private CompletionBoundCalculator.Bounds completionBounds;
	private boolean[][] completionBoundFixedArc;
	private double bestGeneratedReducedCost;

	// 2026-05-22: 双向 midpoint，只对当前 pricing 轮有效。
	private double tMid;
	// 2026-05-24: 本轮 bidirectional pricing 实际使用的右侧 horizon。
	// 若当前任务右端窗明显小于全局 CmaxH，就用它压住 midpoint 的右移，
	// 避免 backward sink root 因 Tmid 过右而完全长不出真实标签。
	private double pricingHorizon;
	private String midpointStrategyUsed = "default";
	private double midpointReferenceTime = Double.NaN;
	private int midpointColumnSelectedCount;
	private double midpointColumnLastMin = Double.NaN;
	private double midpointColumnLastAvg = Double.NaN;
	private double midpointColumnLastMax = Double.NaN;
	private double midpointColumnHalfMin = Double.NaN;
	private double midpointColumnHalfAvg = Double.NaN;
	private double midpointColumnHalfMax = Double.NaN;
	private int midpointColumnTaskSampleCount;
	private double midpointColumnTaskMin = Double.NaN;
	private double midpointColumnTaskAvg = Double.NaN;
	private double midpointColumnTaskMedian = Double.NaN;
	private double midpointColumnTaskMax = Double.NaN;
	private String midpointProbeSummary = "off";
	private String midpointProbeReferenceSource = "strategy";
	private String midpointProbeFeedbackSummary = "off";
	private boolean midpointProbeLabelsReadyForJoin;
	private long midpointStrategyNanos;
	// 2026-05-22: 当前定价轮的 job-level 动态 H_j 缓存。
	private PiecewiseLinearFunction[] dynamicJobPenaltyByJob;
	private double[] dynamicJobHStart;
	private double[] dynamicJobHEnd;
	private double[] effectiveJobHStart;
	private double[] effectiveJobHEnd;
	private PiecewiseLinearFunction[] dynamicBackwardPenaltyByJob;
	private double[] dynamicBackwardHStartByJob;
	private double[] dynamicBackwardHEndByJob;
	private PiecewiseLinearFunction[] completionForwardPenaltyByJob;
	private PiecewiseLinearFunction[] completionBackwardPenaltyByJob;
	private double dynamicMinHStart;
	private double dynamicMaxHEnd;
	private double earliestSourceCompletion;
	private boolean[] forwardHalfEligibleByJob;
	private boolean[] backwardHalfEligibleByJob;
	private int forwardHalfIneligibleJobCount;
	private int backwardHalfIneligibleJobCount;
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
	private long forwardSinglePointKept;
	private long forwardSinglePointDominatedByStore;
	private long forwardSinglePointDominatedByGraph;
	private long backwardSinglePointKept;
	private long backwardSinglePointDominatedByStore;
	private long backwardSinglePointDominatedByGraph;
	private long generatedCandidateCount;
	private long generatedCandidateDroppedByHeap;
	private long forwardSinkLabelsVisited;
	private long forwardSinkNegativeCandidates;
	private long forwardExtensionCandidates;
	private long forwardExtensionArcPruned;
	private long forwardExtensionInfeasible;
	private long forwardExtensionConstructed;
	private long forwardExtensionBoundSurvivors;
	private long[] forwardLabelsKeptByDepth;
	private long[] forwardSinkNegativeByDepth;
	private long forwardLabelsKeptReachableSum;
	private int forwardLabelsKeptReachableMin;
	private int forwardLabelsKeptReachableMax;
	private long completionForwardLabelsPruned;
	private long completionBackwardLabelsPruned;
	private long completionBoundFunctionEvaluations;
	private long completionBoundScalarChecks;
	private long completionBoundScalarPruned;
	private long completionBoundScalarFunctionFallbacks;
	private long completionBoundScalarUnavailable;
	private long completionBoundArcFixingCandidates;
	private long completionBoundArcFixingFixed;
	private long completionBoundArcFixingDomainPruned;
	private long completionBoundArcFixingScalarPruned;
	private long completionBoundArcFixingUnavailable;
	private long completionBoundArcFixingFunctionEvaluations;
	private long completionBoundArcFixingNanos;
	private long completionBoundBuildNanos;
	private long completionBoundForwardBuildNanos;
	private long completionBoundBackwardBuildNanos;
	private long completionBoundAggregateNanos;
	private long completionBoundForwardCandidateAttempts;
	private long completionBoundBackwardCandidateAttempts;
	private long completionBoundForwardQueuePops;
	private long completionBoundBackwardQueuePops;
	private long completionBoundPriorityQueueStalePops;
	private long completionBoundMergeCalls;
	private long completionBoundMergeChanged;
	private double completionBoundLastEvaluationCutoff;
	private int diagnosticForbiddenJobArcCount;
	private int diagnosticPricingOnlyJobArcCount;
	private int diagnosticJobDualPositiveCount;
	private double diagnosticMachineDual;
	private double diagnosticJobDualMin;
	private double diagnosticJobDualMax;
	private double diagnosticJobDualSum;
	private double[] diagnosticJobDualQuantiles;
	private int diagnosticRestrictedColumnCount;
	private int diagnosticIncompatibleRestrictedColumnCount;
	private double diagnosticRestrictedColumnAvgLength;
	private int diagnosticAllowedJobArcDualNonZeroCount;
	private int diagnosticForbiddenJobArcDualNonZeroCount;
	private int diagnosticSinkArcDualNonZeroCount;
	private double diagnosticAllowedJobArcDualMin;
	private double diagnosticAllowedJobArcDualMax;
	private double diagnosticAllowedJobArcDualAbsSum;
	private double diagnosticForbiddenJobArcDualAbsSum;
	private double diagnosticSinkArcDualMin;
	private double diagnosticSinkArcDualMax;
	private double diagnosticSinkArcDualAbsSum;
	private long diagnosticLastHeartbeatNanos;
	private long diagnosticHeartbeatIntervalNanos;
	private long diagnosticForwardPops;
	private long diagnosticBackwardPops;
	private boolean fullMidpointDiagnosticRan;

	private String lastMessage = "GCNGBB-style partial-dominance bidirectional pricing not executed";

	public GCNGBBStyleBidirectionalPartialDominance(Data data, TWETBPCConfig config) {
		this(data, config, null);
	}

	public GCNGBBStyleBidirectionalPartialDominance(Data data, TWETBPCConfig config,
			HashMap<Integer, MidpointProbeNodeReuse> midpointProbeReuseByNode) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
		this.midpointProbeReuseByNode = midpointProbeReuseByNode;
	}

	public ArrayList<TWETColumn> solve(LP lp) {
		long exactStartNanos = System.nanoTime();
		Utility.resetCurUpperBound(Utility.big_M);
		diagnosticHeartbeat(lp, "initialize.start", true);
		initialize(lp);
		diagnosticHeartbeat(lp, "initialize.done", true);
		if (fullMidpointDiagnosticRan && Boolean.getBoolean("twet.bpc.midpointFullDiagnosticStopAfter")) {
			generatedColumns.clear();
			lastMessage = "GCNGBB-style partial-dominance midpoint full diagnostic executed; exact pricing skipped";
			return generatedColumns;
		}
		if (!midpointProbeLabelsReadyForJoin) {
			initializeBackwardSink(lp);
			diagnosticHeartbeat(lp, "backwardSink.done", true);
		} else {
			diagnosticHeartbeat(lp, "probe.rank0.reuse", true);
		}
		// 2026-05-26: GCNGBB-style 外层流程。先分别耗尽两侧队列，最后统一扫描 backward labels 做 crossing-arc join。
		if (!midpointProbeLabelsReadyForJoin) {
			diagnosticHeartbeat(lp, "forward.start", true);
			while (canContinue() && !FWUL.isEmpty()) {
				forwardExtend(lp);
			}
			diagnosticHeartbeat(lp, "forward.done", true);
			diagnosticHeartbeat(lp, "backward.start", true);
			while (canContinue() && !BWUL.isEmpty()) {
				backwardExtend(lp);
			}
			diagnosticHeartbeat(lp, "backward.done", true);
		}
		if (canContinue()) {
			diagnosticHeartbeat(lp, "join.compact.start", true);
			compactAndSortActiveLabelListsForJoin();
			diagnosticHeartbeat(lp, "join.start", true);
			joinAllForwardTerminalGroups(lp);
			diagnosticHeartbeat(lp, "finalize.start", true);
			finalizeGeneratedColumns(lp);
			diagnosticHeartbeat(lp, "finalize.done", true);
		}
		updateMidpointProbeReuseAfterExact(lp, System.nanoTime() - exactStartNanos);
		String completionState = midpointProbeLabelsReadyForJoin ? "probe rank0 queues exhausted"
				: (canContinue() ? "queues exhausted" : "column cap disabled");
		lastMessage = "GCNGBB-style partial-dominance bidirectional no-cut labeling generated " + generatedColumns.size() + " columns ("
				+ completionState + "); " + statisticsSummary();
		return generatedColumns;
	}

	public String getLastMessage() {
		return lastMessage;
	}

	CompletionBoundSubtreeArcEliminator.PreparedBounds reusableSubtreeArcEliminationBounds() {
		if (completionBounds == null || completionBoundRelaxation == null || dualProfitableWindowEnabled
				|| zeroDualExcludedJobs != null || !Utility.compareEq(pricingHorizon, data.CmaxH)) {
			return null;
		}
		return new CompletionBoundSubtreeArcEliminator.PreparedBounds(completionBounds, pricingHorizon,
				completionBoundRelaxation, completionBoundQueueOrdering);
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

	private CompletionBoundCalculator.Relaxation parseCompletionBoundRelaxation(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim().toLowerCase();
		if ("allcycles".equals(normalized) || "all_cycles".equals(normalized)
				|| "all-cycles".equals(normalized) || "all".equals(normalized)) {
			return CompletionBoundCalculator.Relaxation.ALL_CYCLES;
		}
		if ("twocycle".equals(normalized) || "two_cycle".equals(normalized)
				|| "two-cycle".equals(normalized) || "2cycle".equals(normalized)
				|| "2-cycle".equals(normalized)) {
			return CompletionBoundCalculator.Relaxation.TWO_CYCLE;
		}
		return null;
	}

	private CompletionBoundCalculator.QueueOrdering parseCompletionBoundQueueOrdering(String value) {
		if (value == null) {
			return CompletionBoundCalculator.QueueOrdering.FIFO;
		}
		String normalized = value.trim().toLowerCase();
		if ("reducedcost".equals(normalized) || "reduced_cost".equals(normalized)
				|| "reduced-cost".equals(normalized) || "rc".equals(normalized)) {
			return CompletionBoundCalculator.QueueOrdering.REDUCED_COST;
		}
		return CompletionBoundCalculator.QueueOrdering.FIFO;
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
		PartialListDominanceStore.setDiagnosticContext(pricingDiagnosticContext(lp));
		PartialListDominanceStore.resetStatistics();
		pricingHorizon = data.CmaxH;
		tMid = Math.min(data.CmaxH * 0.5, pricingHorizon);
		queueOrdering = parseQueueOrdering(config.bidirectionalLabelQueueOrdering);
		joinBestThresholdMode = parseJoinBestThresholdMode(config.bidirectionalJoinBestThresholdMode);
		completionBoundRelaxation = parseCompletionBoundRelaxation(config.bidirectionalCompletionBoundRelaxation);
		completionBoundQueueOrdering = parseCompletionBoundQueueOrdering(
				config.bidirectionalCompletionBoundQueueOrdering);
		completionBounds = null;
		completionBoundFixedArc = null;
		bestGeneratedReducedCost = Utility.big_M;
		generatedColumns = new ArrayList<TWETColumn>();
		if (config.diagnosticPricingSummaryDetails) {
			recordPricingDiagnostics(lp);
		}
		maybeDumpPricingSnapshot(lp);
		precomputeDynamicPricingWindows(lp);
		if (completionBounds == null) {
			buildCompletionBounds(lp);
		}
		runMidpointProbeIfEnabled(lp);
		if (midpointProbeLabelsReadyForJoin) {
			// 2026-06-08: 被选中的 rank0 probe 已经耗尽两侧 label 队列，可以直接进入 join。
			// 这里只补正式候选列去重/堆状态，避免同一个 Tmid 再跑一遍 labeling。
			initializeCandidateState(lp);
		} else {
			initializeSearchState(lp);
			initializeForwardSource(lp);
		}
		runFullMidpointDiagnosticIfEnabled(lp);
	}

	private void runFullMidpointDiagnosticIfEnabled(LP lp) {
		int targetNodeId = Integer.getInteger("twet.bpc.midpointFullDiagnosticNodeId", -1);
		if (targetNodeId < 0 || lp.getNode() == null || lp.getNode().id != targetNodeId) {
			return;
		}
		String tmidList = System.getProperty("twet.bpc.midpointFullDiagnosticTMids", "").trim();
		if (tmidList.isEmpty()) {
			return;
		}
		if (!FULL_MIDPOINT_DIAGNOSTIC_DONE.add(Integer.valueOf(lp.getNode().id))) {
			return;
		}
		midpointProbeLabelsReadyForJoin = false;
		double originalTMid = tMid;
		String originalProbeSummary = midpointProbeSummary;
		double forwardSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.midpointFullDiagnosticForwardSeconds", "180.0"));
		double backwardSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.midpointFullDiagnosticBackwardSeconds", "120.0"));
		System.out.println("[midpointFullDiagnostic] node=" + lp.getNode().id
				+ " pricingHorizon=" + pricingHorizon
				+ " originalTmid=" + originalTMid
				+ " forwardSeconds=" + forwardSeconds
				+ " backwardSeconds=" + backwardSeconds
				+ " tmids=" + tmidList);
		System.out.flush();
		for (String token : tmidList.split(",")) {
			String trimmed = token.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			double candidate = clampCurrentMidpoint(Double.parseDouble(trimmed));
			runFullMidpointDiagnosticCandidate(lp, candidate, forwardSeconds, backwardSeconds);
		}
		fullMidpointDiagnosticRan = true;
		tMid = originalTMid;
		midpointProbeSummary = originalProbeSummary;
		rebuildHalfDomainForCurrentMidpoint();
		resetProbeAffectedStatistics();
		initializeSearchState(lp);
		initializeForwardSource(lp);
		midpointProbeLabelsReadyForJoin = false;
	}

	private void runFullMidpointDiagnosticCandidate(LP lp, double candidateTMid, double forwardSeconds,
			double backwardSeconds) {
		tMid = candidateTMid;
		rebuildHalfDomainForCurrentMidpoint();
		resetProbeAffectedStatistics();
		initializeSearchState(lp);
		initializeForwardSource(lp);
		initializeBackwardSink(lp);
		long start = System.nanoTime();
		long forwardDeadline = deadlineNanos(start, forwardSeconds);
		while (canContinue() && !FWUL.isEmpty() && !timeReached(forwardDeadline)) {
			forwardExtend(lp);
		}
		long forwardElapsed = System.nanoTime() - start;
		long forwardKept = forwardLabelsKept;
		long forwardQueue = queueSize(FWUL);
		boolean forwardExhausted = FWUL.isEmpty();

		long backwardStart = System.nanoTime();
		long backwardDeadline = deadlineNanos(backwardStart, backwardSeconds);
		while (canContinue() && !BWUL.isEmpty() && !timeReached(backwardDeadline)) {
			backwardExtend(lp);
		}
		long backwardElapsed = System.nanoTime() - backwardStart;
		long backwardKept = backwardLabelsKept;
		long backwardQueue = queueSize(BWUL);
		boolean backwardExhausted = BWUL.isEmpty();
		System.out.println("[midpointFullDiagnostic] node=" + lp.getNode().id
				+ " tMid=" + candidateTMid
				+ " fwElapsedMs=" + formatMillis(forwardElapsed)
				+ " bwElapsedMs=" + formatMillis(backwardElapsed)
				+ " fwExhausted=" + forwardExhausted
				+ " bwExhausted=" + backwardExhausted
				+ " fwKept=" + forwardKept
				+ " bwKept=" + backwardKept
				+ " fwQueue=" + forwardQueue
				+ " bwQueue=" + backwardQueue
				+ " keptQueueRatio=" + directionalRatio(forwardKept + forwardQueue, backwardKept + backwardQueue)
				+ " queueOnlyRatio=" + directionalRatio(forwardQueue, backwardQueue)
				+ " keptRatio=" + directionalRatio(forwardKept, backwardKept)
				+ " fwPops=" + diagnosticForwardPops
				+ " bwPops=" + diagnosticBackwardPops
				+ " fCand=" + forwardExtensionCandidates
				+ " fBuilt=" + forwardExtensionConstructed
				+ " fBoundSurvivors=" + forwardExtensionBoundSurvivors
				+ " cbFPruned=" + completionForwardLabelsPruned
				+ " cbBPruned=" + completionBackwardLabelsPruned);
		System.out.flush();
	}

	private long deadlineNanos(long start, double seconds) {
		if (!Double.isFinite(seconds) || !Utility.compareGt(seconds, 0.0)) {
			return start;
		}
		return start + (long) (seconds * 1_000_000_000.0);
	}

	private boolean timeReached(long deadlineNanos) {
		return System.nanoTime() >= deadlineNanos;
	}

	private String directionalRatio(long forward, long backward) {
		return forward + ":" + backward + "(" + ((double) forward + 1.0) / ((double) backward + 1.0) + ")";
	}

	private void initializeForwardSource(LP lp) {
		PackedBitSet sourceVisited = new PackedBitSet(data.n + 2);
		sourceVisited.add(0);
		addZeroDualExcludedJobs(sourceVisited);
		PiecewiseLinearFunction sourceFrontier = cropToInterval(data.penaltyFunction[0].copy(), 0.0, tMid);
		sourceFrontier.shiftYInPlace(-lp.getMachineDual());
		sourceFrontier.normalize(Direction.FORWARD);
		PackedBitSet sourceReachable = buildForwardReachableSet(0, sourceVisited, lp.getNode(), sourceFrontier);
		ForwardLabel source = new ForwardLabel(nextLabelId++, 0, null, sourceVisited, sourceReachable,
				buildForwardExtensionSet(sourceReachable, 0, sourceFrontier), sourceFrontier);
		if (insertForward(source, lp) == InsertStatus.STORED_AND_ENQUEUE) {
			FWUL.add(source);
		}
	}

	private void runMidpointProbeIfEnabled(LP lp) {
		midpointProbeLabelsReadyForJoin = false;
		if (!config.bidirectionalMidpointProbe) {
			midpointProbeSummary = "off";
			return;
		}
		double reference = midpointProbeReference(lp);
		if (!Double.isFinite(reference) || !Utility.compareGt(reference, 0.0)) {
			midpointProbeSummary = "skipped:noReference";
			return;
		}
		int popLimit = Math.max(1, config.bidirectionalMidpointProbePopLimit);
		int maxCandidates = midpointProbeMaxCandidatesForCurrentReference();
		double moveRatio = normalizedProbeMoveRatio();
		double earlyStopRatio = normalizedProbeEarlyStopRatio();
		int extraAfterThreshold = Math.max(0, config.bidirectionalMidpointProbeExtraCandidatesAfterThreshold);
		ArrayList<MidpointProbeResult> results = new ArrayList<MidpointProbeResult>();
		HashSet<String> seen = new HashSet<String>();
		double candidate = clampCurrentMidpoint(reference);
		MidpointProbeResult previous = null;
		MidpointProbeResult currentStateResult = null;
		MidpointProbeResult acceptedRank0 = null;
		int extraCandidatesRemaining = -1;
		String stopReason = "maxCandidates";
		for (int i = 0; i < maxCandidates; i++) {
			String key = String.format("%.9f", candidate);
			if (!seen.add(key)) {
				stopReason = "duplicate";
				break;
			}
			MidpointProbeResult result = runMidpointProbeCandidate(lp, candidate, popLimit);
			currentStateResult = result;
			results.add(result);
			if (result.reliabilityRank(config.bidirectionalMidpointProbeScore) == 0) {
				acceptedRank0 = result;
				stopReason = "rank0";
				break;
			}
			if (config.bidirectionalMidpointProbeBracketOnDirectionChange && previous != null
					&& isProbeDirectionReversed(previous, result, config.bidirectionalMidpointProbeScore)) {
				double bracketMidpoint = clampCurrentMidpoint((previous.tMid + result.tMid) * 0.5);
				String bracketKey = String.format("%.9f", bracketMidpoint);
				if (seen.add(bracketKey)) {
					MidpointProbeResult bracketResult = runMidpointProbeCandidate(lp, bracketMidpoint, popLimit);
					currentStateResult = bracketResult;
					results.add(bracketResult);
				}
				stopReason = "bracket";
				break;
			}
			if (extraCandidatesRemaining > 0) {
				extraCandidatesRemaining--;
				if (extraCandidatesRemaining == 0) {
					stopReason = "thresholdExtra";
					break;
				}
			} else if (extraCandidatesRemaining < 0 && Utility.compareGt(earlyStopRatio, 1.0)
					&& Utility.compareLe(result.score(config.bidirectionalMidpointProbeScore), earlyStopRatio)) {
				extraCandidatesRemaining = extraAfterThreshold;
				if (extraCandidatesRemaining == 0) {
					stopReason = "threshold";
					break;
				}
			}
			previous = result;
			candidate = nextMidpointProbeCandidate(result, candidate, moveRatio);
		}
		MidpointProbeResult best = acceptedRank0 != null ? acceptedRank0
				: selectMidpointProbeResult(results, config.bidirectionalMidpointProbeScore);
		if (best == null) {
			midpointProbeSummary = "skipped:noResult";
			return;
		}
		tMid = best.tMid;
		midpointProbeLabelsReadyForJoin = best == currentStateResult
				&& best.reliabilityRank(config.bidirectionalMidpointProbeScore) == 0;
		if (!midpointProbeLabelsReadyForJoin) {
			rebuildHalfDomainForCurrentMidpoint();
			resetProbeAffectedStatistics();
		}
		midpointProbeSummary = formatMidpointProbeSummary(reference, best, results, stopReason, maxCandidates);
		if (midpointProbeLabelsReadyForJoin) {
			midpointProbeSummary += ", rank0LabelsReused=true";
		}
	}

	private double midpointProbeReference(LP lp) {
		midpointProbeReferenceSource = "strategy";
		if (config.bidirectionalMidpointProbeReuseWithinNode && midpointProbeReuseByNode != null
				&& lp.getNode() != null) {
			MidpointProbeNodeReuse cached = midpointProbeReuseByNode.get(Integer.valueOf(lp.getNode().id));
			if (cached != null && cached.hasBestExact()) {
				midpointProbeReferenceSource = "reuseBestExact";
				return cached.bestExactTmid;
			}
		}
		return tMid;
	}

	private int midpointProbeMaxCandidatesForCurrentReference() {
		int maxCandidates = Math.max(1, config.bidirectionalMidpointProbeMaxCandidates);
		if ("reuseBestExact".equals(midpointProbeReferenceSource)) {
			maxCandidates = Math.min(maxCandidates, Math.max(1, config.bidirectionalMidpointProbeReuseMaxCandidates));
		}
		return maxCandidates;
	}

	private void updateMidpointProbeReuseAfterExact(LP lp, long exactNanos) {
		if (!config.bidirectionalMidpointProbe || !config.bidirectionalMidpointProbeReuseWithinNode
				|| midpointProbeReuseByNode == null || lp == null || lp.getNode() == null || !Double.isFinite(tMid)) {
			midpointProbeFeedbackSummary = "off";
			return;
		}
		MidpointProbeNodeReuse reuse = midpointProbeReuseByNode.get(Integer.valueOf(lp.getNode().id));
		if (reuse == null) {
			reuse = new MidpointProbeNodeReuse();
			midpointProbeReuseByNode.put(Integer.valueOf(lp.getNode().id), reuse);
		}
		double exactMillis = exactNanos / 1_000_000.0;
		double ratio = directionalImbalance(forwardLabelsKept, backwardLabelsKept);
		long labelTotal = forwardLabelsKept + backwardLabelsKept;
		String action = reuse.considerExact(tMid, exactMillis, ratio, labelTotal,
				config.bidirectionalMidpointProbeExactTimeTieTolerance, normalizedExactBalanceImprovementTolerance());
		midpointProbeFeedbackSummary = "exactReuse=" + action + ", exactMs=" + exactMillis + ", ratio=" + ratio
				+ ", labels=" + labelTotal + ", bestT=" + reuse.bestExactTmid + ", bestMs="
				+ reuse.bestExactMillis + ", bestRatio=" + reuse.bestExactRatio + ", bestLabels="
				+ reuse.bestExactLabelTotal;
	}

	private double normalizedProbeMoveRatio() {
		double ratio = config.bidirectionalMidpointProbeMoveRatio;
		if (!Double.isFinite(ratio) || !Utility.compareGt(ratio, 0.0) || !Utility.compareLt(ratio, 0.5)) {
			return 0.10;
		}
		return ratio;
	}

	private double directionalImbalance(long left, long right) {
		double l = (double) left + 1.0;
		double r = (double) right + 1.0;
		return Math.max(l / r, r / l);
	}

	private double normalizedProbeEarlyStopRatio() {
		double ratio = config.bidirectionalMidpointProbeEarlyStopRatio;
		return Double.isFinite(ratio) && Utility.compareGt(ratio, 1.0) ? ratio : 0.0;
	}

	private double normalizedExactBalanceImprovementTolerance() {
		double tolerance = config.bidirectionalMidpointProbeExactBalanceImprovementTolerance;
		return Double.isFinite(tolerance) && Utility.compareGe(tolerance, 0.0)
				&& Utility.compareLe(tolerance, 1.0) ? tolerance : 0.30;
	}

	private boolean isProbeDirectionReversed(MidpointProbeResult previous, MidpointProbeResult current, String mode) {
		int previousDirection = previous.pressureDirection(mode);
		int currentDirection = current.pressureDirection(mode);
		return previousDirection != 0 && currentDirection != 0 && previousDirection != currentDirection;
	}

	private double nextMidpointProbeCandidate(MidpointProbeResult result, double current, double moveRatio) {
		String mode = normalizeProbeScoreMode(config.bidirectionalMidpointProbeScore);
		double leftPressure = result.leftPressure(mode);
		double rightPressure = result.rightPressure(mode);
		double multiplier = Utility.compareGt(leftPressure, rightPressure) ? (1.0 - moveRatio) : (1.0 + moveRatio);
		return clampCurrentMidpoint(current * multiplier);
	}

	private MidpointProbeResult selectMidpointProbeResult(ArrayList<MidpointProbeResult> results, String scoreMode) {
		MidpointProbeResult best = null;
		for (MidpointProbeResult result : results) {
			if (best == null || compareMidpointProbeResult(result, best, scoreMode) < 0) {
				best = result;
			}
		}
		return best;
	}

	private int compareMidpointProbeResult(MidpointProbeResult a, MidpointProbeResult b, String scoreMode) {
		int reliability = Integer.compare(a.reliabilityRank(scoreMode), b.reliabilityRank(scoreMode));
		if (reliability != 0) {
			return reliability;
		}
		double aPrimaryScore = a.score(scoreMode);
		double bPrimaryScore = b.score(scoreMode);
		int score = compareDouble(aPrimaryScore, bPrimaryScore);
		String tieMode = normalizeProbeTieScoreMode(config.bidirectionalMidpointProbeTieScore);
		if (!"off".equals(tieMode) && isProbePrimaryScoreClose(aPrimaryScore, bPrimaryScore)
				&& isProbeTieScoreComparable(a, b, tieMode)) {
			int tieScore = compareDouble(a.score(tieMode), b.score(tieMode));
			if (tieScore != 0) {
				return tieScore;
			}
		}
		if (score != 0) {
			return score;
		}
		int pressure = Long.compare(a.totalPressure(scoreMode), b.totalPressure(scoreMode));
		if (pressure != 0) {
			return pressure;
		}
		int pops = Integer.compare(a.pops, b.pops);
		if (pops != 0) {
			return pops;
		}
		int elapsed = compareDouble(a.elapsedMillis, b.elapsedMillis);
		if (elapsed != 0) {
			return elapsed;
		}
		return compareDouble(a.tMid, b.tMid);
	}

	private boolean isProbePrimaryScoreClose(double a, double b) {
		double tolerance = config.bidirectionalMidpointProbeTieTolerance;
		return Double.isFinite(tolerance) && Utility.compareGt(tolerance, 0.0)
				&& Utility.compareLe(Math.abs(a - b), tolerance);
	}

	private boolean isProbeTieScoreComparable(MidpointProbeResult a, MidpointProbeResult b, String tieMode) {
		if (!"remaining".equals(tieMode)) {
			return true;
		}
		// remaining 只比较两个未耗尽候选的剩余队列压力；任一侧已经耗尽时，0 队列会破坏可比性。
		return !a.forwardExhausted && !a.backwardExhausted && !b.forwardExhausted && !b.backwardExhausted;
	}

	private int compareDouble(double a, double b) {
		if (Utility.compareLt(a, b)) {
			return -1;
		}
		if (Utility.compareGt(a, b)) {
			return 1;
		}
		return 0;
	}

	private MidpointProbeResult runMidpointProbeCandidate(LP lp, double candidateTMid, int popLimit) {
		long start = System.nanoTime();
		tMid = candidateTMid;
		rebuildHalfDomainForCurrentMidpoint();
		resetProbeAffectedStatistics();
		initializeLabelSearchState();
		initializeForwardSource(lp);
		initializeBackwardSink(lp);
		long fwQueuePeak = queueSize(FWUL);
		long bwQueuePeak = queueSize(BWUL);
		int forwardLimit = (popLimit + 1) / 2;
		int backwardLimit = popLimit / 2;
		int forwardPops = 0;
		int backwardPops = 0;
		// 2026-06-07: probe 是为了比较两侧压力，不按当前队列大小抢占预算。
		// 否则容易出现 sidePop=N:0，只测到 forward 爆炸而没有 backward 样本。
		while (forwardPops < forwardLimit && !FWUL.isEmpty()) {
			forwardExtend(lp);
			forwardPops++;
			fwQueuePeak = Math.max(fwQueuePeak, queueSize(FWUL));
		}
		while (backwardPops < backwardLimit && !BWUL.isEmpty()) {
			backwardExtend(lp);
			backwardPops++;
			bwQueuePeak = Math.max(bwQueuePeak, queueSize(BWUL));
		}
		int pops = forwardPops + backwardPops;
		fwQueuePeak = Math.max(fwQueuePeak, queueSize(FWUL));
		bwQueuePeak = Math.max(bwQueuePeak, queueSize(BWUL));
		double elapsedMillis = (System.nanoTime() - start) / 1_000_000.0;
		return new MidpointProbeResult(candidateTMid, elapsedMillis, pops, FWUL.isEmpty(), BWUL.isEmpty(),
				forwardPops, backwardPops, forwardLabelsKept, backwardLabelsKept, forwardExtensionBoundSurvivors,
				completionForwardLabelsPruned, completionBackwardLabelsPruned, queueSize(FWUL), queueSize(BWUL),
				fwQueuePeak, bwQueuePeak);
	}

	private String formatMidpointProbeSummary(double reference, MidpointProbeResult best,
			ArrayList<MidpointProbeResult> results, String stopReason, int maxCandidates) {
		StringBuilder builder = new StringBuilder();
		builder.append("ref=").append(reference)
				.append("(").append(midpointProbeReferenceSource).append(")")
				.append(", selected=").append(best.tMid)
				.append(", scoreMode=").append(normalizeProbeScoreMode(config.bidirectionalMidpointProbeScore))
				.append(", tieScoreMode=").append(normalizeProbeTieScoreMode(config.bidirectionalMidpointProbeTieScore))
				.append(", tieTolerance=").append(Math.max(0.0, config.bidirectionalMidpointProbeTieTolerance))
				.append(", moveRatio=").append(normalizedProbeMoveRatio())
				.append(", earlyStopRatio=").append(normalizedProbeEarlyStopRatio())
				.append(", extraAfterThreshold=")
				.append(Math.max(0, config.bidirectionalMidpointProbeExtraCandidatesAfterThreshold))
				.append(", bracket=").append(config.bidirectionalMidpointProbeBracketOnDirectionChange)
				.append(", stop=").append(stopReason)
				.append(", maxCandidates=").append(maxCandidates)
				.append(", candidates=");
		for (int i = 0; i < results.size(); i++) {
			if (i > 0) {
				builder.append('|');
			}
			MidpointProbeResult result = results.get(i);
			builder.append(result.compactSummary(config.bidirectionalMidpointProbeScore));
		}
		return builder.toString();
	}

	private static String normalizeProbeScoreMode(String mode) {
		if (mode == null) {
			return "queue";
		}
		String normalized = mode.trim().toLowerCase();
		if ("kept".equals(normalized) || "queue".equals(normalized) || "bound".equals(normalized)
				|| "peak".equals(normalized) || "remaining".equals(normalized)) {
			return normalized;
		}
		return "queue";
	}

	private static String normalizeProbeTieScoreMode(String mode) {
		if (mode == null) {
			return "off";
		}
		String normalized = mode.trim().toLowerCase();
		if ("off".equals(normalized) || "none".equals(normalized)) {
			return "off";
		}
		return normalizeProbeScoreMode(normalized);
	}

	private void initializeSearchState(LP lp) {
		initializeLabelSearchState();
		initializeCandidateState(lp);
	}

	private void initializeLabelSearchState() {
		PartialListDominanceStore.resetStatistics();
		FWUL = new PriorityQueue<ForwardLabel>(forwardQueueComparator(queueOrdering));
		BWUL = new PriorityQueue<BackwardLabel>(backwardQueueComparator(queueOrdering));
		FWTL = new ArrayList<DominanceStore>(data.n + 1);
		BWTL = new ArrayList<DominanceStore>(data.n + 1);
		activeForwardByLastJob = new ArrayList<ArrayList<ForwardLabel>>(data.n + 1);
		activeBackwardByFirstJob = new ArrayList<ArrayList<BackwardLabel>>(data.n + 1);
		forwardSinglePointByLastJob = new ArrayList<SinglePointStore<ForwardLabel>>(data.n + 1);
		backwardSinglePointByFirstJob = new ArrayList<SinglePointStore<BackwardLabel>>(data.n + 1);
		activeForwardTerminalJobs = new PackedBitSet(data.n + 2);
		minForwardReducedCostByLastJob = new double[data.n + 1];
		minForwardEllByLastJob = new double[data.n + 1];
		for (int i = 0; i <= data.n; i++) {
			FWTL.add(new PartialListDominanceStore(Direction.FORWARD));
			BWTL.add(new PartialListDominanceStore(Direction.BACKWARD));
			activeForwardByLastJob.add(new ArrayList<ForwardLabel>());
			activeBackwardByFirstJob.add(new ArrayList<BackwardLabel>());
			forwardSinglePointByLastJob.add(new SinglePointStore<ForwardLabel>());
			backwardSinglePointByFirstJob.add(new SinglePointStore<BackwardLabel>());
			minForwardReducedCostByLastJob[i] = Utility.big_M;
			minForwardEllByLastJob[i] = Utility.big_M;
		}
		nextLabelId = 0;
	}

	private void initializeCandidateState(LP lp) {
		generatedColumns = new ArrayList<TWETColumn>();
		generatedColumnCandidates = new PriorityQueue<PricingColumnCandidate>(
				Math.max(1, config.maxExactPricingColumns), candidateWorstFirstComparator());
		generatedCandidateBySignature = new HashMap<SequenceSignature, PricingColumnCandidate>();
		activeColumnSignatures = new HashSet<SequenceSignature>();
		nextCandidateId = 0;
		// 只记录当前 RMP active 列。全局 pool 自身会按 signature 去重；若历史列当前不 active，
		// pricing 仍可把它返回给 PC，让 LP.addColumns() 重新激活已有列。
		for (int columnId : lp.getRestrictedColumnIds()) {
			activeColumnSignatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
	}

	private String pricingDiagnosticContext(LP lp) {
		Node node = lp == null ? null : lp.getNode();
		return node == null ? "node=-" : node.diagnosticSummary();
	}

	/**
	 * 2026-06-05: 按指定节点落盘当前 exact pricing 输入，便于复盘禁弧很多但 label 仍爆炸的结构原因。
	 * 默认关闭；设置 twet.bpc.pricingSnapshot=true 或 twet.bpc.pricingSnapshotNodeId=<nodeId> 后启用。
	 */
	private void maybeDumpPricingSnapshot(LP lp) {
		Node node = lp == null ? null : lp.getNode();
		if (node == null) {
			return;
		}
		int targetNodeId = Integer.getInteger("twet.bpc.pricingSnapshotNodeId", -1);
		boolean enabled = Boolean.getBoolean("twet.bpc.pricingSnapshot") || targetNodeId >= 0;
		if (!enabled || (targetNodeId >= 0 && node.id != targetNodeId)) {
			return;
		}
		Path dir = Paths.get(System.getProperty("twet.bpc.pricingSnapshotDir",
				"test-results/bpc/pricing-snapshots"));
		String prefix = "pricing-node-" + node.id + "-" + System.currentTimeMillis();
		try {
			Files.createDirectories(dir);
			writePricingSnapshotSummary(lp, dir.resolve(prefix + "-summary.txt"));
			writePricingSnapshotJobDuals(lp, dir.resolve(prefix + "-job-duals.tsv"));
			writePricingSnapshotArcs(lp, dir.resolve(prefix + "-arcs.tsv"));
			writePricingSnapshotColumns(lp, dir.resolve(prefix + "-columns.tsv"));
			System.out.println("[pricingSnapshot] node=" + node.id + " dir=" + dir.toAbsolutePath()
					+ " prefix=" + prefix);
		} catch (IOException ex) {
			System.err.println("[pricingSnapshot] failed for node " + node.id + ": " + ex.getMessage());
		}
	}

	private void writePricingSnapshotSummary(LP lp, Path file) throws IOException {
		Node node = lp.getNode();
		int jobArcAllowed = 0;
		int jobArcForbidden = 0;
		int pricingOnlyForbidden = 0;
		for (int from = 1; from <= data.n; from++) {
			for (int to = 1; to <= data.n; to++) {
				if (from == to) {
					continue;
				}
				if (isPricingArcForbidden(node, from, to)) {
					jobArcForbidden++;
				} else {
					jobArcAllowed++;
				}
				if (node.isPricingOnlyArcForbidden(from, to)) {
					pricingOnlyForbidden++;
				}
			}
		}
		try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			out.write("node\t" + node.diagnosticSummary());
			out.newLine();
			out.write("n\t" + data.n);
			out.newLine();
			out.write("m\t" + data.m);
			out.newLine();
			out.write("CmaxH\t" + data.CmaxH);
			out.newLine();
			out.write("restrictedColumns\t" + lp.getRestrictedColumnIds().size());
			out.newLine();
			out.write("machineDual\t" + lp.getMachineDual());
			out.newLine();
			out.write("jobJobPricingForbidden\t" + jobArcForbidden);
			out.newLine();
			out.write("jobJobPricingAllowed\t" + jobArcAllowed);
			out.newLine();
			out.write("jobJobPricingOnlyForbidden\t" + pricingOnlyForbidden);
			out.newLine();
			out.write("requiredAdjacencyPairs\t" + formatPairs(node.getRequiredAdjacencyPairs()));
			out.newLine();
			out.write("forbiddenAdjacencyPairs\t" + formatPairs(node.getForbiddenAdjacencyPairs()));
			out.newLine();
		}
	}

	private void writePricingSnapshotJobDuals(LP lp, Path file) throws IOException {
		try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			out.write("job\tdual");
			out.newLine();
			for (int job = 1; job <= data.n; job++) {
				out.write(job + "\t" + lp.getJobDual(job));
				out.newLine();
			}
		}
	}

	private void writePricingSnapshotArcs(LP lp, Path file) throws IOException {
		Node node = lp.getNode();
		int sink = node.sinkId();
		try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			out.write("from\tto\trealForbidden\tpricingOnlyForbidden\tpricingForbidden\tarcDual");
			out.newLine();
			for (int from = 0; from <= sink; from++) {
				for (int to = 1; to <= sink; to++) {
					if (from == to) {
						continue;
					}
					boolean realForbidden = node.isArcForbidden(from, to);
					boolean pricingOnly = node.isPricingOnlyArcForbidden(from, to);
					boolean pricingForbidden = isPricingArcForbidden(node, from, to);
					out.write(from + "\t" + to + "\t" + realForbidden + "\t" + pricingOnly + "\t"
							+ pricingForbidden + "\t" + lp.getArcDual(from, to));
					out.newLine();
				}
			}
		}
	}

	private void writePricingSnapshotColumns(LP lp, Path file) throws IOException {
		Node node = lp.getNode();
		try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			out.write("columnId\tcost\tlength\trealForbidden\tpricingOnlyForbidden\tpricingForbidden\tsequence");
			out.newLine();
			for (int columnId : lp.getRestrictedColumnIds()) {
				TWETColumn column = lp.getPool().getColumn(columnId);
				List<Integer> sequence = column.getSequence();
				boolean realForbidden = sequenceUsesRealForbiddenArc(node, sequence);
				boolean pricingOnly = sequenceUsesPricingOnlyForbiddenArc(node, sequence);
				boolean pricingForbidden = sequenceUsesPricingForbiddenArc(node, sequence);
				out.write(columnId + "\t" + column.getCost() + "\t" + sequence.size() + "\t" + realForbidden
						+ "\t" + pricingOnly + "\t" + pricingForbidden + "\t" + formatSequence(sequence));
				out.newLine();
			}
		}
	}

	private String formatPairs(List<int[]> pairs) {
		StringBuilder builder = new StringBuilder();
		for (int[] pair : pairs) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(pair[0]).append('-').append(pair[1]);
		}
		return builder.length() == 0 ? "-" : builder.toString();
	}

	private String formatSequence(List<Integer> sequence) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < sequence.size(); i++) {
			if (i > 0) {
				builder.append(' ');
			}
			builder.append(sequence.get(i).intValue());
		}
		return builder.toString();
	}

	private boolean sequenceUsesRealForbiddenArc(Node node, List<Integer> sequence) {
		return sequenceUsesForbiddenArc(node, sequence, ForbiddenArcMode.REAL);
	}

	private boolean sequenceUsesPricingOnlyForbiddenArc(Node node, List<Integer> sequence) {
		return sequenceUsesForbiddenArc(node, sequence, ForbiddenArcMode.PRICING_ONLY);
	}

	private boolean sequenceUsesPricingForbiddenArc(Node node, List<Integer> sequence) {
		return sequenceUsesForbiddenArc(node, sequence, ForbiddenArcMode.PRICING);
	}

	private boolean sequenceUsesForbiddenArc(Node node, List<Integer> sequence, ForbiddenArcMode mode) {
		if (sequence.isEmpty()) {
			return false;
		}
		if (isForbiddenByMode(node, 0, sequence.get(0).intValue(), mode)) {
			return true;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (isForbiddenByMode(node, sequence.get(i - 1).intValue(), sequence.get(i).intValue(), mode)) {
				return true;
			}
		}
		return isForbiddenByMode(node, sequence.get(sequence.size() - 1).intValue(), node.sinkId(), mode);
	}

	private boolean isForbiddenByMode(Node node, int from, int to, ForbiddenArcMode mode) {
		if (mode == ForbiddenArcMode.REAL) {
			return node.isArcForbidden(from, to);
		}
		if (mode == ForbiddenArcMode.PRICING_ONLY) {
			return node.isPricingOnlyArcForbidden(from, to);
		}
		return isPricingArcForbidden(node, from, to);
	}

	private enum ForbiddenArcMode {
		REAL, PRICING_ONLY, PRICING
	}

	private void initializeBackwardSink(LP lp) {
		PackedBitSet sinkVisited = new PackedBitSet(data.n + 2);
		sinkVisited.add(lp.getNode().sinkId());
		addZeroDualExcludedJobs(sinkVisited);
		PiecewiseLinearFunction sinkFrontier = new PiecewiseLinearFunction();
		// 2026-05-23: backward 虚拟终点本身也要带 [Tmid,pricingHorizon] 元数据。
		// 这样后续若发生 shiftX，trimToDomain 的边界和物理半域一致。
		sinkFrontier.resetDomain(tMid, pricingHorizon);
		sinkFrontier.addSegment(tMid, pricingHorizon, 0.0, 0.0);
		PackedBitSet sinkReachable = buildBackwardReachableSet(lp.getNode().sinkId(), sinkVisited, lp.getNode(),
				sinkFrontier);
		BackwardLabel sink = BackwardLabel.sink(nextLabelId++, lp.getNode().sinkId(), sinkVisited, sinkFrontier,
				sinkReachable,
				buildBackwardExtensionSet(sinkReachable, lp.getNode().sinkId(), true, sinkFrontier));
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
		diagnosticForwardPops++;

		Node node = lp.getNode();
		for (int nextJob = label.extensionSet.nextSetBit(1); nextJob > 0 && nextJob <= data.n && canContinue();
				nextJob = label.extensionSet.nextSetBit(nextJob + 1)) {
			forwardExtensionCandidates++;
			if (!canExtendForward(label, nextJob, node)) {
				forwardExtensionArcPruned++;
				continue;
			}
			ForwardLabel child = extendForward(label, nextJob, lp);
			if (child == null || Utility.isBigMValue(child.minReducedCost)) {
				forwardExtensionInfeasible++;
				continue;
			}
			forwardExtensionConstructed++;
			if (isForwardCompletionBoundPruned(child)) {
				completionForwardLabelsPruned++;
				continue;
			}
			forwardExtensionBoundSurvivors++;
			if (insertForward(child, lp) == InsertStatus.STORED_AND_ENQUEUE) {
				FWUL.add(child);
			}
		}
		diagnosticHeartbeat(lp, "forward.progress", false);
	}

	private void backwardExtend(LP lp) {
		BackwardLabel label = BWUL.poll();
		if (label.isDominated) {
			return;
		}
		diagnosticBackwardPops++;

		Node node = lp.getNode();
		for (int prevJob = label.extensionSet.nextSetBit(1); prevJob > 0 && prevJob <= data.n && canContinue();
				prevJob = label.extensionSet.nextSetBit(prevJob + 1)) {
			if (!canExtendBackward(label, prevJob, node)) {
				continue;
			}
			BackwardLabel child = extendBackward(label, prevJob, lp);
			if (child == null || Utility.isBigMValue(child.minReducedCost)) {
				continue;
			}
			if (isBackwardCompletionBoundPruned(child)) {
				completionBackwardLabelsPruned++;
				continue;
			}
			if (insertBackward(child, lp) == InsertStatus.STORED_AND_ENQUEUE) {
				BWUL.add(child);
			}
		}
		diagnosticHeartbeat(lp, "backward.progress", false);
	}

	private boolean canExtendForward(ForwardLabel label, int nextJob, Node node) {
		// 2026-06-13: 调用方只枚举 label.extensionSet；visited
		// 和时间可行性已经在 reachable set 构造时维护。下面旧检查保留为防御性说明，
		// 正常不应触发；实际会随节点变化、必须即时检查的是直连禁弧。
		// if (label.visitedSet.contains(nextJob) || !label.reachableSet.contains(nextJob)) {
		// 	return false;
		// }
		return !PricingCompatibility.isRequiredOutsourcedJob(node, nextJob)
				&& !isPricingArcForbidden(node, label.jid, nextJob);
	}

	private boolean canExtendBackward(BackwardLabel label, int prevJob, Node node) {
		int successor = label.isSinkRoot ? node.sinkId() : label.jid;
		// 2026-05-29: reachable set 已维护 visited 和时间可行性；
		// 下面旧检查保留为防御性说明，正常不应触发；backward 扩展点只需即时检查
		// prevJob -> successor 这条直连弧是否被禁。
		// if (label.visitedSet.contains(prevJob) || !label.reachableSet.contains(prevJob)) {
		// 	return false;
		// }
		return !PricingCompatibility.isRequiredOutsourcedJob(node, prevJob)
				&& !isPricingArcForbidden(node, prevJob, successor);
	}

	private int previousForwardJob(ForwardLabel label) {
		return label != null && label.father != null ? label.father.jid : 0;
	}

	private int nextBackwardJob(BackwardLabel label, Node node) {
		if (label == null || label.isSinkRoot || label.father == null || label.father.isSinkRoot) {
			return node.sinkId();
		}
		return label.father.jid;
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
		PiecewiseLinearFunction nextFrontier = shifted.add(jobPenalty);
		if (nextFrontier.head == null) {
			return null;
		}
		double fixedReducedCost = data.getSetupCost(label.jid, nextJob) - lp.getJobDual(nextJob)
				- lp.getArcDual(label.jid, nextJob);
		nextFrontier.shiftYInPlace(fixedReducedCost);
		nextFrontier.normalize(Direction.FORWARD);
		if (nextFrontier.head == null) {
			return null;
		}

		PackedBitSet visited = label.visitedSet.copy();
		visited.add(nextJob);
		PackedBitSet reachable = buildForwardReachableSetFromParent(label, nextJob, visited, lp.getNode(),
				nextFrontier);
		return new ForwardLabel(nextLabelId++, nextJob, label, visited, reachable,
				buildForwardExtensionSet(reachable, nextJob, nextFrontier), nextFrontier);
	}

	private BackwardLabel extendBackward(BackwardLabel label, int prevJob, LP lp) {
		Node node = lp.getNode();
		PiecewiseLinearFunction nextFrontier;
		double successorHStart = getDynamicBackwardHStart(prevJob, label.isSinkRoot ? node.sinkId() : label.jid);
		double rhoPrime;
		if (label.isSinkRoot) {
			rhoPrime = getDynamicBackwardHEnd(prevJob, node.sinkId());
			if (Utility.compareLt(rhoPrime, Math.max(tMid, successorHStart))) {
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
			if (Utility.compareLt(rhoPrime, Math.max(tMid, successorHStart))) {
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
		nextFrontier.normalize(Direction.BACKWARD);
		if (nextFrontier.head == null) {
			return null;
		}

		PackedBitSet visited = label.visitedSet.copy();
		visited.add(prevJob);
		PackedBitSet reachable = buildBackwardReachableSetFromParent(label, prevJob, visited, lp.getNode(),
				nextFrontier);
		return new BackwardLabel(nextLabelId++, prevJob, label, visited, reachable,
				buildBackwardExtensionSet(reachable, prevJob, false, nextFrontier), nextFrontier, false);
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
			recordForwardKeptDiagnostics(label);
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
		if (FWTL.get(label.jid).dominatesSinglePoint(label.reachableSet, label.reachableCardinality, tMid,
				label.minReducedCost)) {
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
		recordForwardKeptDiagnostics(label);
		return InsertStatus.STORED_NO_EXPAND;
	}

	/**
	 * 2026-05-25: Tmid 单点 backward label 只保留给 single-point store；
	 * 2026-05-26: 在 GCNGBB-style 流程下不立即 join，而是在最后统一扫描 join。
	 */
	private InsertStatus insertBackwardSinglePoint(BackwardLabel label, LP lp) {
		SinglePointStore<BackwardLabel> store = backwardSinglePointByFirstJob.get(label.jid);
		if (isDominatedBySinglePointStore(store, label)) {
			label.isDominated = true;
			backwardLabelsDominated++;
			backwardSinglePointDominatedByStore++;
			return InsertStatus.DOMINATED;
		}
		if (BWTL.get(label.jid).dominatesSinglePoint(label.reachableSet, label.reachableCardinality, tMid,
				label.minReducedCost)) {
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
		if (Utility.compareLt(label.minReducedCost, minForwardReducedCostByLastJob[lastJob])) {
			minForwardReducedCostByLastJob[lastJob] = label.minReducedCost;
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
		if (isPricingArcForbidden(node, label.jid, sink)) {
			return;
		}
		forwardSinkLabelsVisited++;
		double reducedCost = label.minReducedCost - lp.getArcDual(label.jid, sink);
		if (!Utility.compareLt(reducedCost, REDUCED_COST_TOLERANCE)) {
			return;
		}
		forwardSinkNegativeCandidates++;
		recordDepthCount(forwardSinkNegativeByDepth, label.depth);
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
		double liveMinReducedCost = Utility.big_M;
		double liveMinEll = Utility.big_M;
		for (int i = 0; i < labels.size(); i++) {
			ForwardLabel label = labels.get(i);
			if (label.isDominated) {
				continue;
			}
			labels.set(liveCount++, label);
			if (Utility.compareLt(label.minReducedCost, liveMinReducedCost)) {
				liveMinReducedCost = label.minReducedCost;
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
			minForwardReducedCostByLastJob[job] = Utility.big_M;
			minForwardEllByLastJob[job] = Utility.big_M;
			return;
		}
		Collections.sort(labels);
		minForwardReducedCostByLastJob[job] = liveMinReducedCost;
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
	 * 2026-05-28: 统一收尾 join。两侧 label table 都生成完以后，以 forward terminal group 为外层，
	 * 同时处理 crossing-arc join 和 forward->sink 收尾，避免 sink 列绕过统一候选筛选。
	 */
	private void joinAllForwardTerminalGroups(LP lp) {
		for (int lastJob = activeForwardTerminalJobs.nextSetBit(0); lastJob >= 0 && lastJob <= data.n && canContinue();
				lastJob = activeForwardTerminalJobs.nextSetBit(lastJob + 1)) {
			ArrayList<ForwardLabel> candidates = activeForwardByLastJob.get(lastJob);
			if (candidates.isEmpty()) {
				continue;
			}
			joinForwardGroupToBackwardLabels(lastJob, candidates, lp);
			joinForwardGroupToSink(candidates, lp);
		}
	}

	private void joinForwardGroupToBackwardLabels(int lastJob, ArrayList<ForwardLabel> candidates, LP lp) {
		for (int firstJob = 1; firstJob <= data.n && canContinue(); firstJob++) {
			ArrayList<BackwardLabel> labels = activeBackwardByFirstJob.get(firstJob);
			for (int i = 0; i < labels.size() && canContinue(); i++) {
				BackwardLabel backward = labels.get(i);
				if (!backward.isDominated && !backward.isSinkRoot) {
					joinForwardGroupWithBackward(lastJob, candidates, backward, lp);
				}
			}
		}
		for (int firstJob = 1; firstJob <= data.n && canContinue(); firstJob++) {
			joinForwardGroupWithBackwardSinglePoints(lastJob, candidates, backwardSinglePointByFirstJob.get(firstJob),
					lp);
		}
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
		Node node = lp.getNode();
		// 2026-05-23: 和 joinFromForward 对称，不能用 backward.reachableSet 反推所有可拼接前缀。
		// 该集合是 backward 继续向左扩展的候选，不等价于所有可与当前后缀拼接的 forward terminal。
		joinTerminalGroupsScanned++;
		if (backward.visitedSet.contains(lastJob) || isPricingArcForbidden(node, lastJob, backward.jid)) {
			joinTerminalGroupsArcOrVisitPruned++;
			return;
		}
		double delay = data.getSetUp(lastJob, backward.jid) + data.getProcessT(backward.jid);
		if (Utility.compareGt(minForwardEllByLastJob[lastJob] + delay, backward.frontier.tail.end)) {
			joinTerminalGroupsTimePruned++;
			return;
		}
		double joinFixedReducedCost = data.getSetupCost(lastJob, backward.jid)
				- lp.getArcDual(lastJob, backward.jid);
		double joinThreshold = joinLowerBoundThreshold();
		double groupLB = minForwardReducedCostByLastJob[lastJob] + backward.minReducedCost + joinFixedReducedCost;
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
			double optimisticJoinLB = forward.minReducedCost + backward.minReducedCost + joinFixedReducedCost;
			if (!Utility.compareLt(optimisticJoinLB, joinThreshold)) {
				joinPairsLowerBoundPruned++;
				if (Utility.compareLt(joinThreshold, REDUCED_COST_TOLERANCE)) {
					joinPairsBestBoundPruned++;
				}
				break;
			}
			tryJoin(forward, backward, lp, joinFixedReducedCost);
		}
	}

	private void tryJoin(ForwardLabel forward, BackwardLabel backward, LP lp, double joinFixedReducedCost) {
		if (config.maxExactPricingColumns <= 0) {
			return;
		}
		joinPairsTried++;
		if (forward.jid == backward.jid || visitedSetsIntersectForJoin(forward.visitedSet, backward.visitedSet)) {
			joinPairsSetPruned++;
			return;
		}
		double delta = data.getSetUp(forward.jid, backward.jid) + data.getProcessT(backward.jid);
		double earliestBackwardCompletion = forward.frontier.head.start + delta;
		if (Utility.compareGt(earliestBackwardCompletion, backward.frontier.tail.end)) {
			joinPairsTimePruned++;
			return;
		}

		joinFunctionEvaluations++;
		PiecewiseLinearFunction forwardFull = getForwardJoinExtension(forward);
		PiecewiseLinearFunction shiftedForward = forwardFull.shiftX(delta);
		if (shiftedForward.head == null) {
			joinFunctionPruned++;
			return;
		}
		PiecewiseLinearFunction backwardFull = getBackwardJoinExtension(backward);
		if (backwardFull.head == null) {
			joinFunctionPruned++;
			return;
		}
		PiecewiseLinearFunction joinCost = shiftedForward.add(backwardFull);
		if (joinCost.head == null) {
			joinFunctionPruned++;
			return;
		}
		// 2026-05-22: crossing arc (i,r) 的固定 reduced-cost 项不仅有 setup cost，
		// 还必须扣掉该弧在 RMP 中的聚合 arc dual；否则 join 下界会偏高，极端时会漏掉真负列。
		joinCost.shiftYInPlace(joinFixedReducedCost);
		double reducedCostBound = joinCost.findMinimal(false, true)[0];
		if (!shouldKeepJoinedReducedCost(reducedCostBound)) {
			joinFunctionPruned++;
			if (Utility.compareLt(reducedCostBound, REDUCED_COST_TOLERANCE)) {
				joinFunctionBestRecordPruned++;
			}
			return;
		}

		ArrayList<Integer> sequence = recoverJoinSequence(forward, backward);
		tryGenerateColumn(sequence, lp, reducedCostBound);
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
		forwardSinglePointKept = 0;
		forwardSinglePointDominatedByStore = 0;
		forwardSinglePointDominatedByGraph = 0;
		backwardSinglePointKept = 0;
		backwardSinglePointDominatedByStore = 0;
		backwardSinglePointDominatedByGraph = 0;
		generatedCandidateCount = 0;
		generatedCandidateDroppedByHeap = 0;
		forwardSinkLabelsVisited = 0;
		forwardSinkNegativeCandidates = 0;
		forwardExtensionCandidates = 0;
		forwardExtensionArcPruned = 0;
		forwardExtensionInfeasible = 0;
		forwardExtensionConstructed = 0;
		forwardExtensionBoundSurvivors = 0;
		forwardLabelsKeptByDepth = new long[data.n + 1];
		forwardSinkNegativeByDepth = new long[data.n + 1];
		forwardLabelsKeptReachableSum = 0;
		forwardLabelsKeptReachableMin = Integer.MAX_VALUE;
		forwardLabelsKeptReachableMax = 0;
		completionForwardLabelsPruned = 0;
		completionBackwardLabelsPruned = 0;
		completionBoundFunctionEvaluations = 0;
		completionBoundScalarChecks = 0;
		completionBoundScalarPruned = 0;
		completionBoundScalarFunctionFallbacks = 0;
		completionBoundScalarUnavailable = 0;
		completionBoundArcFixingCandidates = 0;
		completionBoundArcFixingFixed = 0;
		completionBoundArcFixingDomainPruned = 0;
		completionBoundArcFixingScalarPruned = 0;
		completionBoundArcFixingUnavailable = 0;
		completionBoundArcFixingFunctionEvaluations = 0;
		completionBoundArcFixingNanos = 0;
		completionBoundBuildNanos = 0;
		completionBoundForwardBuildNanos = 0;
		completionBoundBackwardBuildNanos = 0;
		completionBoundAggregateNanos = 0;
		completionBoundForwardCandidateAttempts = 0;
		completionBoundBackwardCandidateAttempts = 0;
		completionBoundForwardQueuePops = 0;
		completionBoundBackwardQueuePops = 0;
		completionBoundPriorityQueueStalePops = 0;
		completionBoundMergeCalls = 0;
		completionBoundMergeChanged = 0;
		completionBoundLastEvaluationCutoff = Double.NaN;
		midpointStrategyUsed = "default";
		midpointReferenceTime = Double.NaN;
		midpointColumnSelectedCount = 0;
		midpointColumnLastMin = Double.NaN;
		midpointColumnLastAvg = Double.NaN;
		midpointColumnLastMax = Double.NaN;
		midpointColumnHalfMin = Double.NaN;
		midpointColumnHalfAvg = Double.NaN;
		midpointColumnHalfMax = Double.NaN;
		midpointColumnTaskSampleCount = 0;
		midpointColumnTaskMin = Double.NaN;
		midpointColumnTaskAvg = Double.NaN;
		midpointColumnTaskMedian = Double.NaN;
		midpointColumnTaskMax = Double.NaN;
		midpointProbeSummary = "off";
		midpointProbeFeedbackSummary = "off";
		midpointProbeLabelsReadyForJoin = false;
		midpointStrategyNanos = 0;
		diagnosticForbiddenJobArcCount = 0;
		diagnosticPricingOnlyJobArcCount = 0;
		diagnosticJobDualPositiveCount = 0;
		diagnosticMachineDual = 0.0;
		diagnosticJobDualMin = 0.0;
		diagnosticJobDualMax = 0.0;
		diagnosticJobDualSum = 0.0;
		diagnosticJobDualQuantiles = null;
		diagnosticRestrictedColumnCount = 0;
		diagnosticIncompatibleRestrictedColumnCount = 0;
		diagnosticRestrictedColumnAvgLength = 0.0;
		diagnosticAllowedJobArcDualNonZeroCount = 0;
		diagnosticForbiddenJobArcDualNonZeroCount = 0;
		diagnosticSinkArcDualNonZeroCount = 0;
		diagnosticAllowedJobArcDualMin = 0.0;
		diagnosticAllowedJobArcDualMax = 0.0;
		diagnosticAllowedJobArcDualAbsSum = 0.0;
		diagnosticForbiddenJobArcDualAbsSum = 0.0;
		diagnosticSinkArcDualMin = 0.0;
		diagnosticSinkArcDualMax = 0.0;
		diagnosticSinkArcDualAbsSum = 0.0;
		diagnosticLastHeartbeatNanos = 0;
		diagnosticHeartbeatIntervalNanos = Long.getLong("twet.bpc.diagnosticHeartbeatIntervalMillis", 10000L)
				* 1000000L;
		diagnosticForwardPops = 0;
		diagnosticBackwardPops = 0;
	}

	private void resetProbeAffectedStatistics() {
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
		forwardSinglePointKept = 0;
		forwardSinglePointDominatedByStore = 0;
		forwardSinglePointDominatedByGraph = 0;
		backwardSinglePointKept = 0;
		backwardSinglePointDominatedByStore = 0;
		backwardSinglePointDominatedByGraph = 0;
		generatedCandidateCount = 0;
		generatedCandidateDroppedByHeap = 0;
		forwardSinkLabelsVisited = 0;
		forwardSinkNegativeCandidates = 0;
		forwardExtensionCandidates = 0;
		forwardExtensionArcPruned = 0;
		forwardExtensionInfeasible = 0;
		forwardExtensionConstructed = 0;
		forwardExtensionBoundSurvivors = 0;
		forwardLabelsKeptByDepth = new long[data.n + 1];
		forwardSinkNegativeByDepth = new long[data.n + 1];
		forwardLabelsKeptReachableSum = 0;
		forwardLabelsKeptReachableMin = Integer.MAX_VALUE;
		forwardLabelsKeptReachableMax = 0;
		completionForwardLabelsPruned = 0;
		completionBackwardLabelsPruned = 0;
		completionBoundFunctionEvaluations = 0;
		completionBoundScalarChecks = 0;
		completionBoundScalarPruned = 0;
		completionBoundScalarFunctionFallbacks = 0;
		completionBoundScalarUnavailable = 0;
		completionBoundLastEvaluationCutoff = Double.NaN;
		diagnosticForwardPops = 0;
		diagnosticBackwardPops = 0;
		fullMidpointDiagnosticRan = false;
	}

	private void diagnosticHeartbeat(LP lp, String phase, boolean force) {
		if (!config.diagnosticStageHeartbeat) {
			return;
		}
		long now = System.nanoTime();
		if (!force && diagnosticHeartbeatIntervalNanos > 0 && diagnosticLastHeartbeatNanos > 0
				&& now - diagnosticLastHeartbeatNanos < diagnosticHeartbeatIntervalNanos) {
			return;
		}
		diagnosticLastHeartbeatNanos = now;
		Node node = lp == null ? null : lp.getNode();
		String nodeId = node == null ? "-" : Integer.toString(node.id);
		System.out.println("[BPC exact heartbeat] node=" + nodeId
				+ " phase=" + phase
				+ " fwQueue=" + queueSize(FWUL)
				+ " bwQueue=" + queueSize(BWUL)
				+ " fwPops=" + diagnosticForwardPops
				+ " bwPops=" + diagnosticBackwardPops
				+ " fwKept=" + forwardLabelsKept
				+ " fwDom=" + forwardLabelsDominated
				+ " bwKept=" + backwardLabelsKept
				+ " bwDom=" + backwardLabelsDominated
				+ " fCand=" + forwardExtensionCandidates
				+ " fBuilt=" + forwardExtensionConstructed
				+ " fBoundSurvivors=" + forwardExtensionBoundSurvivors
				+ " cbFPruned=" + completionForwardLabelsPruned
				+ " cbBPruned=" + completionBackwardLabelsPruned
				+ " joinPairs=" + joinPairsTried
				+ " generated=" + generatedCandidateCount
				+ " bestRC=" + bestGeneratedReducedCost
				+ " pricingHorizon=" + pricingHorizon
				+ " tMid=" + tMid
				+ " midpointStrategy=" + midpointStrategyUsed
				+ " midpointRef=" + midpointReferenceTime);
		System.out.flush();
	}

	private int queueSize(PriorityQueue<?> queue) {
		return queue == null ? 0 : queue.size();
	}

	private String statisticsSummary() {
		return "labels fw kept/dominated=" + forwardLabelsKept + "/" + forwardLabelsDominated
				+ ", bw kept/dominated=" + backwardLabelsKept + "/" + backwardLabelsDominated
				+ ", halfWindowIneligible fw/bw=" + forwardHalfIneligibleJobCount + "/"
				+ backwardHalfIneligibleJobCount
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
				+ ", completionBound mode/cutoff/buildMs/eval/fwPruned/bwPruned="
				+ completionBoundRelaxationForSummary()
				+ "/" + completionBoundCutoffForSummary() + "/" + formatMillis(completionBoundBuildNanos)
				+ "/" + completionBoundFunctionEvaluations + "/" + completionForwardLabelsPruned
				+ "/" + completionBackwardLabelsPruned
				+ ", completionBoundScalar check/pruned/fallback/unavailable=" + completionBoundScalarChecks
				+ "/" + completionBoundScalarPruned + "/" + completionBoundScalarFunctionFallbacks
				+ "/" + completionBoundScalarUnavailable
				+ ", completionBoundArcFixing candidates/fixed/domain/scalar/unavailable/funcEval/ms="
				+ completionBoundArcFixingCandidates + "/" + completionBoundArcFixingFixed
				+ "/" + completionBoundArcFixingDomainPruned + "/" + completionBoundArcFixingScalarPruned
				+ "/" + completionBoundArcFixingUnavailable + "/" + completionBoundArcFixingFunctionEvaluations
				+ "/" + formatMillis(completionBoundArcFixingNanos)
				+ ", forwardSink visited/negative=" + forwardSinkLabelsVisited
				+ "/" + forwardSinkNegativeCandidates
				+ ", forwardExtend candidates/arcPruned/infeasible/constructed/boundSurvivors="
				+ forwardExtensionCandidates + "/" + forwardExtensionArcPruned
				+ "/" + forwardExtensionInfeasible + "/" + forwardExtensionConstructed
				+ "/" + forwardExtensionBoundSurvivors
				+ ", forwardDepth kept/negSink=" + formatDepthHistogram(forwardLabelsKeptByDepth)
				+ "/" + formatDepthHistogram(forwardSinkNegativeByDepth)
				+ ", forwardReach kept avg/min/max=" + formatAverage(forwardLabelsKeptReachableSum,
						forwardLabelsKept) + "/" + formatReachableMin() + "/" + forwardLabelsKeptReachableMax
				+ nodeDiagnosticsSummary()
				+ ", completionBoundQueue=" + completionBoundQueueOrdering
				+ ", completionBoundInternal timingMs fw/bw/agg=" + formatMillis(completionBoundForwardBuildNanos)
				+ "/" + formatMillis(completionBoundBackwardBuildNanos) + "/"
				+ formatMillis(completionBoundAggregateNanos)
				+ ", completionBoundInternal counts fCand/bCand/fPop/bPop/stale/merge/changed="
				+ completionBoundForwardCandidateAttempts + "/" + completionBoundBackwardCandidateAttempts
				+ "/" + completionBoundForwardQueuePops + "/" + completionBoundBackwardQueuePops
				+ "/" + completionBoundPriorityQueueStalePops
				+ "/" + completionBoundMergeCalls + "/" + completionBoundMergeChanged
				+ ", candidatePool kept/seen/dropped=" + generatedCandidateBySignature.size() + "/"
				+ generatedCandidateCount + "/" + generatedCandidateDroppedByHeap
				+ ", queueOrdering=" + queueOrdering
				+ ", dynamicHStartMin=" + dynamicMinHStart + ", dynamicHEndMax=" + dynamicMaxHEnd
				+ ", earliestSourceCompletion=" + earliestSourceCompletion
				+ ", pricingHorizon=" + pricingHorizon + ", tMid=" + tMid
				+ ", midpointStrategy/ref/ms=" + midpointStrategyUsed + "/" + midpointReferenceTime + "/"
				+ formatMillis(midpointStrategyNanos)
				+ ", midpointColumns count/lastMinAvgMax/halfMinAvgMax=" + midpointColumnSelectedCount
				+ "/" + midpointColumnLastMin + "/" + midpointColumnLastAvg + "/" + midpointColumnLastMax
				+ "/" + midpointColumnHalfMin + "/" + midpointColumnHalfAvg + "/" + midpointColumnHalfMax
				+ ", midpointColumnTasks count/minAvgMedianMax=" + midpointColumnTaskSampleCount
				+ "/" + midpointColumnTaskMin + "/" + midpointColumnTaskAvg + "/" + midpointColumnTaskMedian
				+ "/" + midpointColumnTaskMax
				+ ", midpointProbe=" + midpointProbeSummary
				+ ", midpointProbeFeedback=" + midpointProbeFeedbackSummary
				+ ", zeroDualExcludedJobs=" + zeroDualExcludedJobCount
				+ ", dualWindow=" + (dualProfitableWindowEnabled ? "enabled" : "staticOutsourcingOnly")
				+ ", " + PartialListDominanceStore.statisticsSummary();
	}

	private String nodeDiagnosticsSummary() {
		if (!config.diagnosticPricingSummaryDetails) {
			return "";
		}
		return ", nodeDiag forbiddenJobArcs/pricingOnlyJobArcs/machineDual/jobDual min/max/sum/pos="
				+ diagnosticForbiddenJobArcCount + "/" + diagnosticPricingOnlyJobArcCount
				+ "/" + diagnosticMachineDual + "/" + diagnosticJobDualMin + "/" + diagnosticJobDualMax
				+ "/" + diagnosticJobDualSum + "/" + diagnosticJobDualPositiveCount
				+ ", nodeDiag jobDual q0/q10/q25/q50/q75/q90/q100="
				+ formatJobDualQuantiles()
				+ ", nodeDiag columns/incompat/avgLen=" + diagnosticRestrictedColumnCount
				+ "/" + diagnosticIncompatibleRestrictedColumnCount + "/"
				+ String.format("%.3f", diagnosticRestrictedColumnAvgLength)
				+ ", nodeDiag arcDual allowedNZ/min/max/absSum=" + diagnosticAllowedJobArcDualNonZeroCount
				+ "/" + diagnosticAllowedJobArcDualMin + "/" + diagnosticAllowedJobArcDualMax
				+ "/" + diagnosticAllowedJobArcDualAbsSum
				+ ", forbiddenNZ/absSum=" + diagnosticForbiddenJobArcDualNonZeroCount
				+ "/" + diagnosticForbiddenJobArcDualAbsSum
				+ ", sinkNZ/min/max/absSum=" + diagnosticSinkArcDualNonZeroCount
				+ "/" + diagnosticSinkArcDualMin + "/" + diagnosticSinkArcDualMax
				+ "/" + diagnosticSinkArcDualAbsSum;
	}

	private static String formatMillis(long nanos) {
		return String.format("%.3f", nanos / 1_000_000.0);
	}

	private void recordForwardKeptDiagnostics(ForwardLabel label) {
		if (label == null || label.jid == 0) {
			return;
		}
		recordDepthCount(forwardLabelsKeptByDepth, label.depth);
		forwardLabelsKeptReachableSum += label.reachableCardinality;
		forwardLabelsKeptReachableMin = Math.min(forwardLabelsKeptReachableMin, label.reachableCardinality);
		forwardLabelsKeptReachableMax = Math.max(forwardLabelsKeptReachableMax, label.reachableCardinality);
	}

	private void recordDepthCount(long[] histogram, int depth) {
		if (histogram == null || depth < 0) {
			return;
		}
		int bucket = Math.min(depth, histogram.length - 1);
		histogram[bucket]++;
	}

	private String formatDepthHistogram(long[] histogram) {
		if (histogram == null) {
			return "-";
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < histogram.length; i++) {
			if (histogram[i] == 0) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append(';');
			}
			builder.append(i).append(':').append(histogram[i]);
		}
		return builder.length() == 0 ? "-" : builder.toString();
	}

	private String formatAverage(long sum, long count) {
		if (count <= 0) {
			return "0.000";
		}
		return String.format("%.3f", ((double) sum) / count);
	}

	private int formatReachableMin() {
		return forwardLabelsKeptReachableMin == Integer.MAX_VALUE ? 0 : forwardLabelsKeptReachableMin;
	}

	private void recordPricingDiagnostics(LP lp) {
		Node node = lp.getNode();
		int forbidden = 0;
		int pricingOnlyForbidden = 0;
		int allowedDualNonZero = 0;
		int forbiddenDualNonZero = 0;
		double allowedDualMin = Utility.big_M;
		double allowedDualMax = -Utility.big_M;
		double allowedDualAbsSum = 0.0;
		double forbiddenDualAbsSum = 0.0;
		if (node != null) {
			for (int from = 1; from <= data.n; from++) {
				for (int to = 1; to <= data.n; to++) {
					if (from == to) {
						continue;
					}
					boolean arcForbidden = node.isArcForbidden(from, to);
					if (arcForbidden) {
						forbidden++;
					}
					if (node.isPricingOnlyArcForbidden(from, to)) {
						pricingOnlyForbidden++;
					}
					double dual = lp.getArcDual(from, to);
					if (isDiagnosticNonZero(dual)) {
						if (arcForbidden) {
							forbiddenDualNonZero++;
							forbiddenDualAbsSum += Math.abs(dual);
						} else {
							allowedDualNonZero++;
							allowedDualMin = Math.min(allowedDualMin, dual);
							allowedDualMax = Math.max(allowedDualMax, dual);
							allowedDualAbsSum += Math.abs(dual);
						}
					}
				}
			}
		}
		recordRestrictedColumnDiagnostics(lp, node);
		recordSinkArcDualDiagnostics(lp, node);
		diagnosticMachineDual = lp.getMachineDual();
		double min = Utility.big_M;
		double max = -Utility.big_M;
		double sum = 0.0;
		int positive = 0;
		double[] jobDuals = new double[data.n];
		for (int job = 1; job <= data.n; job++) {
			double dual = lp.getJobDual(job);
			jobDuals[job - 1] = dual;
			min = Math.min(min, dual);
			max = Math.max(max, dual);
			sum += dual;
			if (Utility.compareGt(dual, 0.0)) {
				positive++;
			}
		}
		diagnosticForbiddenJobArcCount = forbidden;
		diagnosticPricingOnlyJobArcCount = pricingOnlyForbidden;
		diagnosticJobDualPositiveCount = positive;
		diagnosticJobDualMin = Utility.isBigMValue(min) ? 0.0 : min;
		diagnosticJobDualMax = Utility.isBigMValue(-max) ? 0.0 : max;
		diagnosticJobDualSum = sum;
		diagnosticJobDualQuantiles = computeQuantiles(jobDuals);
		diagnosticAllowedJobArcDualNonZeroCount = allowedDualNonZero;
		diagnosticForbiddenJobArcDualNonZeroCount = forbiddenDualNonZero;
		diagnosticAllowedJobArcDualMin = Utility.isBigMValue(allowedDualMin) ? 0.0 : allowedDualMin;
		diagnosticAllowedJobArcDualMax = Utility.isBigMValue(-allowedDualMax) ? 0.0 : allowedDualMax;
		diagnosticAllowedJobArcDualAbsSum = allowedDualAbsSum;
		diagnosticForbiddenJobArcDualAbsSum = forbiddenDualAbsSum;
	}

	private void recordRestrictedColumnDiagnostics(LP lp, Node node) {
		diagnosticRestrictedColumnCount = lp.getRestrictedColumnIds().size();
		if (diagnosticRestrictedColumnCount == 0) {
			diagnosticIncompatibleRestrictedColumnCount = 0;
			diagnosticRestrictedColumnAvgLength = 0.0;
			return;
		}
		int incompatible = 0;
		long totalLength = 0;
		for (int columnId : lp.getRestrictedColumnIds()) {
			TWETColumn column = lp.getPool().getColumn(columnId);
			if (node != null && !node.isColumnCompatible(column)) {
				incompatible++;
			}
			totalLength += column.getSequence().size();
		}
		diagnosticIncompatibleRestrictedColumnCount = incompatible;
		diagnosticRestrictedColumnAvgLength = ((double) totalLength) / diagnosticRestrictedColumnCount;
	}

	private void recordSinkArcDualDiagnostics(LP lp, Node node) {
		int sink = node == null ? data.n + 1 : node.sinkId();
		int nonZero = 0;
		double min = Utility.big_M;
		double max = -Utility.big_M;
		double absSum = 0.0;
		for (int job = 1; job <= data.n; job++) {
			double dual = lp.getArcDual(job, sink);
			if (!isDiagnosticNonZero(dual)) {
				continue;
			}
			nonZero++;
			min = Math.min(min, dual);
			max = Math.max(max, dual);
			absSum += Math.abs(dual);
		}
		diagnosticSinkArcDualNonZeroCount = nonZero;
		diagnosticSinkArcDualMin = Utility.isBigMValue(min) ? 0.0 : min;
		diagnosticSinkArcDualMax = Utility.isBigMValue(-max) ? 0.0 : max;
		diagnosticSinkArcDualAbsSum = absSum;
	}

	private boolean isDiagnosticNonZero(double value) {
		return Utility.compareGt(Math.abs(value), 1e-8);
	}

	private double[] computeQuantiles(double[] values) {
		if (values == null || values.length == 0) {
			return new double[0];
		}
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		double[] probabilities = new double[] {0.0, 0.10, 0.25, 0.50, 0.75, 0.90, 1.0};
		double[] quantiles = new double[probabilities.length];
		for (int i = 0; i < probabilities.length; i++) {
			quantiles[i] = quantile(sorted, probabilities[i]);
		}
		return quantiles;
	}

	private double quantile(double[] sorted, double probability) {
		if (sorted.length == 1) {
			return sorted[0];
		}
		double position = probability * (sorted.length - 1);
		int lower = (int) Math.floor(position);
		int upper = (int) Math.ceil(position);
		if (lower == upper) {
			return sorted[lower];
		}
		double weight = position - lower;
		return sorted[lower] * (1.0 - weight) + sorted[upper] * weight;
	}

	private String formatJobDualQuantiles() {
		if (diagnosticJobDualQuantiles == null || diagnosticJobDualQuantiles.length == 0) {
			return "-";
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < diagnosticJobDualQuantiles.length; i++) {
			if (i > 0) {
				builder.append('/');
			}
			builder.append(String.format("%.3f", diagnosticJobDualQuantiles[i]));
		}
		return builder.toString();
	}

	private double joinLowerBoundThreshold() {
		if ((joinBestThresholdMode == JoinBestThresholdMode.BEST_UB
				|| joinBestThresholdMode == JoinBestThresholdMode.BEST_RECORD)
				&& Utility.compareLt(bestGeneratedReducedCost, REDUCED_COST_TOLERANCE)) {
			return bestGeneratedReducedCost;
		}
		return REDUCED_COST_TOLERANCE;
	}

	private boolean shouldKeepJoinedReducedCost(double reducedCost) {
		double threshold = joinBestThresholdMode == JoinBestThresholdMode.BEST_RECORD
				? joinLowerBoundThreshold() : REDUCED_COST_TOLERANCE;
		return Utility.compareLt(reducedCost, threshold);
	}

	private double completionBoundCutoff() {
		// 2026-06-01: completion bound 只判断 label 是否还能补成负列；
		// 不使用当前 best reduced cost，避免变成 record-only 剪枝并丢掉 top-K 负列。
		return REDUCED_COST_TOLERANCE;
	}

	private double completionBoundCutoffForSummary() {
		return Double.isNaN(completionBoundLastEvaluationCutoff)
				? completionBoundCutoff() : completionBoundLastEvaluationCutoff;
	}

	private String completionBoundRelaxationForSummary() {
		return completionBoundRelaxation == null ? "OFF" : completionBoundRelaxation.toString();
	}

	private void buildCompletionBounds(LP lp) {
		if (completionBoundRelaxation == null) {
			return;
		}
		long start = System.nanoTime();
		CompletionBoundCalculator calculator = new CompletionBoundCalculator(data, lp, pricingHorizon,
				completionForwardPenaltyByJob, completionBackwardPenaltyByJob, zeroDualExcludedJobs,
				completionBoundQueueOrdering, config.bidirectionalCompletionBoundScalarPruning,
				ignorePricingOnlyArcsForNode(lp.getNode()));
		CompletionBoundCalculator.Result result = calculator.build(completionBoundRelaxation);
		completionBounds = result.bounds;
		recordCompletionBoundStats(result.stats);
		completionBoundBuildNanos += System.nanoTime() - start;
		maybeDumpCompletionBoundMinDiagnostic(lp);
		evaluateCompletionBoundArcFixing(lp);
	}

	/**
	 * 2026-06-09: 诊断 required adjacency dual 是否把 relaxed suffix 下界压得过低。
	 * 只按系统属性显式指定节点时输出，不影响正式 pricing 语义。
	 */
	private void maybeDumpCompletionBoundMinDiagnostic(LP lp) {
		Node currentNode = lp == null ? null : lp.getNode();
		if (currentNode == null || completionBounds == null) {
			return;
		}
		int targetNodeId = Integer.getInteger("twet.bpc.completionBoundMinDiagnosticNodeId", -1);
		if (targetNodeId < 0 || currentNode.id != targetNodeId) {
			return;
		}

		int count = 0;
		int negative = 0;
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		double sum = 0.0;
		StringBuilder detail = new StringBuilder();
		for (int job = 1; job <= data.n; job++) {
			PiecewiseLinearFunction function = completionBounds.backwardRByJob[job];
			if (function == null || function.head == null) {
				detail.append(" job=").append(job).append(":NA");
				continue;
			}
			double[] argmin = function.findMinimal(false, true);
			if (argmin == null || argmin.length < 2 || !Double.isFinite(argmin[0])) {
				detail.append(" job=").append(job).append(":NA");
				continue;
			}
			double value = argmin[0];
			double time = argmin[1];
			count++;
			sum += value;
			min = Math.min(min, value);
			max = Math.max(max, value);
			if (Utility.compareLt(value, 0.0)) {
				negative++;
			}
			detail.append(" job=").append(job).append(":").append(value).append("@").append(time);
		}
		double avg = count == 0 ? Double.NaN : sum / count;
		System.out.println("[completionBoundMinDiagnostic] node=" + currentNode.id
				+ " relaxation=" + completionBoundRelaxationForSummary()
				+ " backwardR count/negative/min/max/avg=" + count + "/" + negative
				+ "/" + min + "/" + max + "/" + avg);
		for (int[] pair : currentNode.getRequiredAdjacencyPairs()) {
			int first = pair[0];
			int second = pair[1];
			System.out.println("[completionBoundMinDiagnostic] requiredAdjacency=" + first + "-" + second
					+ " arcDual(" + first + "," + second + ")=" + lp.getArcDual(first, second)
					+ " arcDual(" + second + "," + first + ")=" + lp.getArcDual(second, first));
		}
		System.out.println("[completionBoundMinDiagnostic] backwardRByJob" + detail.toString());
	}

	private void evaluateCompletionBoundArcFixing(LP lp) {
		if ((!config.bidirectionalCompletionBoundArcFixingDiagnostic
				&& !config.bidirectionalCompletionBoundArcFixing) || completionBounds == null) {
			return;
		}
		if (config.bidirectionalCompletionBoundArcFixing) {
			completionBoundFixedArc = new boolean[data.n + 1][data.n + 1];
		}
		long start = System.nanoTime();
		Node node = lp.getNode();
		double cutoff = completionBoundCutoff();
		for (int fromJob = 1; fromJob <= data.n; fromJob++) {
			for (int toJob = 1; toJob <= data.n; toJob++) {
				if (fromJob == toJob || isZeroDualExcludedJob(fromJob) || isZeroDualExcludedJob(toJob)
						|| node.isArcForbidden(fromJob, toJob)
						|| (!ignorePricingOnlyArcsForNode(node) && node.isPricingOnlyArcForbidden(fromJob, toJob))) {
					continue;
				}
				completionBoundArcFixingCandidates++;
				PiecewiseLinearFunction prefix = completionBounds.forwardFByJob[fromJob];
				PiecewiseLinearFunction suffix = completionBounds.backwardBByJob[toJob];
				if (prefix == null || prefix.head == null || suffix == null || suffix.head == null) {
					completionBoundArcFixingUnavailable++;
					continue;
				}
				double delay = data.getSetUp(fromJob, toJob) + data.getProcessT(toJob);
				if (isCompletionBoundArcTimeDisjoint(prefix, suffix, delay)) {
					rememberCompletionBoundFixedArc(fromJob, toJob, true);
					continue;
				}
				double fixedReducedCost = data.getSetupCost(fromJob, toJob) - lp.getArcDual(fromJob, toJob);
				if (isCompletionBoundArcScalarPruned(fromJob, toJob, fixedReducedCost, cutoff)) {
					rememberCompletionBoundFixedArc(fromJob, toJob, false);
					completionBoundArcFixingScalarPruned++;
					continue;
				}
				PiecewiseLinearFunction shiftedPrefix = prefix.shiftX(delay);
				if (shiftedPrefix.head == null) {
					rememberCompletionBoundFixedArc(fromJob, toJob, true);
					continue;
				}
				PiecewiseLinearFunction arcBound = shiftedPrefix.add(suffix);
				if (arcBound.head == null) {
					rememberCompletionBoundFixedArc(fromJob, toJob, true);
					continue;
				}
				arcBound.shiftYInPlace(fixedReducedCost);
				completionBoundArcFixingFunctionEvaluations++;
				double lowerBound = arcBound.findMinimal(false, true)[0];
				if (!Utility.compareLt(lowerBound, cutoff)) {
					rememberCompletionBoundFixedArc(fromJob, toJob, false);
				}
			}
		}
		completionBoundArcFixingNanos += System.nanoTime() - start;
	}

	private boolean isCompletionBoundArcTimeDisjoint(PiecewiseLinearFunction prefix, PiecewiseLinearFunction suffix,
			double delay) {
		return Utility.compareGt(prefix.head.start + delay, suffix.tail.end);
	}

	private boolean isCompletionBoundArcScalarPruned(int fromJob, int toJob, double fixedReducedCost, double cutoff) {
		double prefixMin = completionBounds.forwardFMin(fromJob);
		double suffixMin = completionBounds.backwardBMin(toJob);
		if (Utility.isBigMValue(prefixMin) || Utility.isBigMValue(suffixMin)) {
			return false;
		}
		double lowerBound = prefixMin + suffixMin + fixedReducedCost;
		return !Utility.compareLt(lowerBound, cutoff);
	}

	private void rememberCompletionBoundFixedArc(int fromJob, int toJob, boolean domainPruned) {
		completionBoundArcFixingFixed++;
		if (domainPruned) {
			completionBoundArcFixingDomainPruned++;
		}
		if (completionBoundFixedArc != null) {
			completionBoundFixedArc[fromJob][toJob] = true;
		}
	}

	private boolean isCompletionBoundArcFixed(int fromJob, int toJob) {
		return fromJob > 0 && fromJob <= data.n && toJob > 0 && toJob <= data.n
				&& completionBoundFixedArc != null && completionBoundFixedArc[fromJob][toJob];
	}

	private boolean isPricingArcForbidden(Node node, int fromJob, int toJob) {
		return node.isArcForbidden(fromJob, toJob)
				|| (!ignorePricingOnlyArcsForNode(node) && node.isPricingOnlyArcForbidden(fromJob, toJob))
				|| isCompletionBoundArcFixed(fromJob, toJob);
	}

	private boolean ignorePricingOnlyArcsForNode(Node node) {
		return node != null && config.debugIgnorePricingOnlyArcsAtNode >= 0
				&& node.id == config.debugIgnorePricingOnlyArcsAtNode;
	}

	private void recordCompletionBoundStats(CompletionBoundCalculator.Stats stats) {
		if (stats == null) {
			return;
		}
		completionBoundForwardBuildNanos += stats.forwardBuildNanos;
		completionBoundBackwardBuildNanos += stats.backwardBuildNanos;
		completionBoundAggregateNanos += stats.aggregateNanos;
		completionBoundForwardCandidateAttempts += stats.forwardCandidateAttempts;
		completionBoundBackwardCandidateAttempts += stats.backwardCandidateAttempts;
		completionBoundForwardQueuePops += stats.forwardQueuePops;
		completionBoundBackwardQueuePops += stats.backwardQueuePops;
		completionBoundPriorityQueueStalePops += stats.priorityQueueStalePops;
		completionBoundMergeCalls += stats.mergeCalls;
		completionBoundMergeChanged += stats.mergeChanged;
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
		double cutoff = completionBoundCutoff();
		completionBoundLastEvaluationCutoff = cutoff;
		if (config.bidirectionalCompletionBoundScalarPruning
				&& isForwardCompletionBoundScalarPruned(label, cutoff)) {
			return true;
		}
		completionBoundFunctionEvaluations++;
		PiecewiseLinearFunction completion = label.frontier.add(suffix);
		if (completion.head == null) {
			return false;
		}
		double lowerBound = completion.findMinimal(false, true)[0];
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
		double cutoff = completionBoundCutoff();
		completionBoundLastEvaluationCutoff = cutoff;
		if (config.bidirectionalCompletionBoundScalarPruning
				&& isBackwardCompletionBoundScalarPruned(label, cutoff)) {
			return true;
		}
		completionBoundFunctionEvaluations++;
		PiecewiseLinearFunction completion = prefix.add(label.frontier);
		if (completion.head == null) {
			return false;
		}
		double lowerBound = completion.findMinimal(false, true)[0];
		return !Utility.compareLt(lowerBound, cutoff);
	}

	private boolean isForwardCompletionBoundScalarPruned(ForwardLabel label, double cutoff) {
		completionBoundScalarChecks++;
		double suffixLowerBound = completionBounds.backwardRAfterFloor(label.jid, label.frontier.head.start);
		if (Utility.isBigMValue(suffixLowerBound)) {
			completionBoundScalarUnavailable++;
			completionBoundScalarPruned++;
			return true;
		}
		double scalarLowerBound = label.minReducedCost + suffixLowerBound;
		if (!Utility.compareLt(scalarLowerBound, cutoff)) {
			completionBoundScalarPruned++;
			return true;
		}
		completionBoundScalarFunctionFallbacks++;
		return false;
	}

	private boolean isBackwardCompletionBoundScalarPruned(BackwardLabel label, double cutoff) {
		completionBoundScalarChecks++;
		double prefixLowerBound = isAtPricingHorizon(label.frontier.tail.end)
				? completionBounds.forwardUMin(label.jid)
				: completionBounds.forwardUBeforeCeil(label.jid, label.frontier.tail.end);
		if (Utility.isBigMValue(prefixLowerBound)) {
			completionBoundScalarUnavailable++;
			completionBoundScalarPruned++;
			return true;
		}
		double scalarLowerBound = label.minReducedCost + prefixLowerBound;
		if (!Utility.compareLt(scalarLowerBound, cutoff)) {
			completionBoundScalarPruned++;
			return true;
		}
		completionBoundScalarFunctionFallbacks++;
		return false;
	}

	private boolean isAtPricingHorizon(double time) {
		return Utility.compareEq(time, pricingHorizon);
	}

	private void updateBestGeneratedReducedCost(double reducedCost) {
		if (Utility.compareLt(reducedCost, bestGeneratedReducedCost)) {
			bestGeneratedReducedCost = reducedCost;
		}
	}

	/**
	 * 2026-05-23: join 前临时把 forward 半域右侧延拓为 f(Tmid)。
	 * 这是论文实现里的 join 辅助函数，不写回 label。
	 */
	private PiecewiseLinearFunction getForwardJoinExtension(ForwardLabel label) {
		if (label.joinExtendedFrontier == null) {
			label.joinExtendedFrontier = buildForwardJoinExtension(label.frontier);
		}
		return label.joinExtendedFrontier;
	}

	private PiecewiseLinearFunction buildForwardJoinExtension(PiecewiseLinearFunction forward) {
		PiecewiseLinearFunction extended = new PiecewiseLinearFunction(0.0, pricingHorizon);
		appendSegments(extended, forward);
		if (forward != null && forward.tail != null && Utility.compareLt(forward.tail.end, pricingHorizon)) {
			addConstantSegmentOrPoint(extended, forward.tail.end, pricingHorizon, valueAtOrNearest(forward, tMid));
		}
		mergeAdjacentEqualSegments(extended);
		return extended;
	}

	/**
	 * 2026-05-23: join 前临时把 backward 半域左侧延拓为 f_b(Tmid)。
	 * 这是论文实现里的 join 辅助函数，不写回 label。
	 */
	private PiecewiseLinearFunction getBackwardJoinExtension(BackwardLabel label) {
		if (label.joinExtendedFrontier == null) {
			label.joinExtendedFrontier = buildBackwardJoinExtension(label.frontier);
		}
		return label.joinExtendedFrontier;
	}

	private PiecewiseLinearFunction buildBackwardJoinExtension(PiecewiseLinearFunction backward) {
		PiecewiseLinearFunction extended = new PiecewiseLinearFunction(0.0, pricingHorizon);
		if (backward != null && backward.head != null && Utility.compareLt(0.0, backward.head.start)) {
			addConstantSegmentOrPoint(extended, 0.0, backward.head.start, valueAtOrNearest(backward, tMid));
		}
		appendSegments(extended, backward);
		mergeAdjacentEqualSegments(extended);
		return extended;
	}

	private double valueAtOrNearest(PiecewiseLinearFunction function, double t) {
		if (function == null || function.head == null) {
			return Utility.big_M;
		}
		if (!Utility.compareLt(t, function.head.start) && !Utility.compareGt(t, function.tail.end)) {
			return function.evaluate(t);
		}
		if (Utility.compareLt(t, function.head.start)) {
			return function.evaluate(function.head.start);
		}
		return function.evaluate(function.tail.end);
	}

	private void tryGenerateColumn(ArrayList<Integer> sequence, LP lp, double inferredReducedCost) {
		if (sequence.isEmpty() || config.maxExactPricingColumns <= 0) {
			return;
		}
		if (PricingCompatibility.containsRequiredOutsourcedJob(lp.getNode(), sequence)) {
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
		if (existing != null && compareCandidateBestFirst(candidate, existing) >= 0) {
			generatedCandidateDroppedByHeap++;
			return;
		}
		updateBestGeneratedReducedCost(reducedCost);
		generatedCandidateBySignature.put(signature, candidate);
		generatedColumnCandidates.add(candidate);
		pruneGeneratedCandidatePool();
	}

	private void pruneGeneratedCandidatePool() {
		// 2026-06-16: 同一 sequence 可由多个 split 生成；旧候选留在堆中，map 只保留当前最优候选。
		while (generatedCandidateBySignature.size() > config.maxExactPricingColumns) {
			PricingColumnCandidate worstKept = pollCurrentWorstGeneratedCandidate();
			if (worstKept == null) {
				break;
			}
			generatedCandidateBySignature.remove(worstKept.signature);
			generatedCandidateDroppedByHeap++;
		}
	}

	private PricingColumnCandidate pollCurrentWorstGeneratedCandidate() {
		while (!generatedColumnCandidates.isEmpty()) {
			PricingColumnCandidate candidate = generatedColumnCandidates.poll();
			if (generatedCandidateBySignature.get(candidate.signature) == candidate) {
				return candidate;
			}
		}
		return null;
	}

	private void finalizeGeneratedColumns(LP lp) {
		generatedColumns.clear();
		ArrayList<PricingColumnCandidate> candidates = new ArrayList<PricingColumnCandidate>(
				generatedCandidateBySignature.values());
		Collections.sort(candidates, candidateBestFirstComparator());
		for (int i = 0; i < candidates.size(); i++) {
			PricingColumnCandidate candidate = candidates.get(i);
			// 2026-05-31: 只有根节点 no-cut pi-window 会让 K 堆候选成本口径偏紧。
			// pi-window 是原 hard window 的子区间，因此 inferred 成本不低于真实列成本；
			// inferred reduced cost 已为负时，真实 reduced cost 只会更小，这里只修正列成本。
			// 2026-06-15: partial dominance 会把活 label 的 frontier 裁成搜索残余函数；
			// 因此最终进入列池的候选必须按完整 sequence 恢复真实列成本。
			PricingColumnCostRechecker.Result checked = PricingColumnCostRechecker.evaluate(candidate.column, data,
					evaluator);
			if (checked != null) {
				generatedColumns.add(checked.checkedColumn(data));
			}
		}
	}

	private boolean isSequenceCompatible(ArrayList<Integer> sequence, Node node) {
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

	private boolean isDirectForwardExtensionTimeFeasible(PiecewiseLinearFunction frontier, int prevJob, int nextJob) {
		return isDirectForwardExtensionTimeFeasible(frontier, prevJob, nextJob, true);
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
	private boolean isDirectBackwardExtensionTimeFeasible(BackwardLabel label, int prevJob) {
		return isDirectBackwardExtensionTimeFeasible(label.jid, label.isSinkRoot, label.frontier, prevJob);
	}

	private boolean isDirectBackwardExtensionTimeFeasible(int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier, int prevJob) {
		return isDirectBackwardExtensionTimeFeasible(firstJob, isSinkRoot, frontier, prevJob, true);
	}

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
			// 2026-05-24: dominance reachable-set 只放“永久不可达”信息。
			// forbidden arc 只禁止当前 direct arc，不代表该 job 后续不能通过其他前驱访问，
			// 因此不能进入 dominance key；实际扩展仍在 canExtendForward 中单独检查 forbidden arc。
			if (!visited.contains(job) && isDirectForwardExtensionTimeFeasibleFullDomain(frontier, fromJob, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private PackedBitSet buildForwardReachableSetFromParent(ForwardLabel parent, int fromJob, PackedBitSet visited,
			Node node, PiecewiseLinearFunction frontier) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		// 2026-05-24: 三角不等式下可达性随路径扩展单调收缩，child 不需要重新扫描 1..n。
		for (int job = parent.reachableSet.nextSetBit(1); job > 0 && job <= data.n;
				job = parent.reachableSet.nextSetBit(job + 1)) {
			if (!visited.contains(job) && isDirectForwardExtensionTimeFeasibleFullDomain(frontier, fromJob, job)) {
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
		// 2026-05-24: backward 方向同样只从父可达集合中过滤；已经无法接到旧后缀的前驱，
		// 在中间再插入一个真实 job 后不会重新可达。
		for (int job = parent.reachableSet.nextSetBit(1); job > 0 && job <= data.n;
				job = parent.reachableSet.nextSetBit(job + 1)) {
			if (!visited.contains(job)
					&& isDirectBackwardExtensionTimeFeasibleFullDomain(firstJob, isSinkRoot, frontier, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private PackedBitSet buildForwardExtensionSet(PackedBitSet dominanceReachable, int fromJob,
			PiecewiseLinearFunction frontier) {
		PackedBitSet extension = new PackedBitSet(data.n + 2);
		for (int job = dominanceReachable.nextSetBit(1); job > 0 && job <= data.n;
				job = dominanceReachable.nextSetBit(job + 1)) {
			if (isForwardHalfEligibleJob(job) && isDirectForwardExtensionTimeFeasible(frontier, fromJob, job)) {
				extension.add(job);
			}
		}
		return extension;
	}

	private PackedBitSet buildBackwardExtensionSet(PackedBitSet dominanceReachable, int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier) {
		PackedBitSet extension = new PackedBitSet(data.n + 2);
		for (int job = dominanceReachable.nextSetBit(1); job > 0 && job <= data.n;
				job = dominanceReachable.nextSetBit(job + 1)) {
			if (isBackwardHalfEligibleJob(job)
					&& isDirectBackwardExtensionTimeFeasible(firstJob, isSinkRoot, frontier, job)) {
				extension.add(job);
			}
		}
		return extension;
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
		completionForwardPenaltyByJob = null;
		completionBackwardPenaltyByJob = null;
		forwardHalfEligibleByJob = null;
		backwardHalfEligibleByJob = null;
		forwardHalfIneligibleJobCount = 0;
		backwardHalfIneligibleJobCount = 0;
		zeroDualExcludedJobs = null;
		zeroDualExcludedJobCount = 0;
		dualProfitableWindowEnabled = canUseDualProfitableWindow(lp);
		precomputeEffectivePricingWindows(lp);
		precomputeZeroDualExcludedJobs(lp);
		tMid = computeDefaultMidpoint();
		ensureBaseHalfPenaltyCache();
		precomputeJobLevelDynamicPricingWindows();
		precomputeBackwardDynamicPricingWindows();
		precomputeCompletionBoundPricingWindows();
		if (requiresCompletionBoundForMidpoint()) {
			buildCompletionBounds(lp);
		}
		tMid = computeCurrentMidpoint(lp);
		rebuildHalfDomainForCurrentMidpoint();
	}

	private void rebuildHalfDomainForCurrentMidpoint() {
		ensureBaseHalfPenaltyCache();
		precomputeJobLevelDynamicPricingWindows();
		precomputeBackwardDynamicPricingWindows();
		precomputeHalfDomainEligibility();
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

	private double computeCurrentMidpoint(LP lp) {
		long start = System.nanoTime();
		midpointStrategyUsed = configuredMidpointStrategy();
		midpointReferenceTime = Double.NaN;
		midpointColumnSelectedCount = 0;
		midpointColumnLastMin = Double.NaN;
		midpointColumnLastAvg = Double.NaN;
		midpointColumnLastMax = Double.NaN;
		midpointColumnHalfMin = Double.NaN;
		midpointColumnHalfAvg = Double.NaN;
		midpointColumnHalfMax = Double.NaN;
		double candidate;
		if (Double.isFinite(config.bidirectionalRootLocalHorizonMidpointRatio)
				&& Utility.compareGt(config.bidirectionalRootLocalHorizonMidpointRatio, 0.0)
				&& Utility.compareLt(config.bidirectionalRootLocalHorizonMidpointRatio, 1.0)) {
			midpointStrategyUsed = "ratio";
			midpointReferenceTime = pricingHorizon;
			candidate = pricingHorizon * config.bidirectionalRootLocalHorizonMidpointRatio;
			midpointStrategyNanos += System.nanoTime() - start;
			return clampCurrentMidpoint(candidate);
		}

		double left = midpointLeftBound();
		if ("incumbentMakespan".equalsIgnoreCase(midpointStrategyUsed)) {
			double reference = incumbentBestMakespan();
			if (Double.isFinite(reference)) {
				midpointReferenceTime = reference;
				midpointStrategyNanos += System.nanoTime() - start;
				return clampCurrentMidpoint((left + Math.min(reference, pricingHorizon)) * 0.5);
			}
		} else if ("completionBound".equalsIgnoreCase(midpointStrategyUsed)) {
			double reference = completionBoundArgminTime();
			if (Double.isFinite(reference)) {
				midpointReferenceTime = reference;
				midpointStrategyNanos += System.nanoTime() - start;
				return clampCurrentMidpoint((left + Math.min(reference, pricingHorizon)) * 0.5);
			}
		} else if ("columnLastAvg".equalsIgnoreCase(midpointStrategyUsed)
				|| "columnHalfAvg".equalsIgnoreCase(midpointStrategyUsed)
				|| "columnTaskMedian".equalsIgnoreCase(midpointStrategyUsed)
				|| "columnTaskMedianTopLast".equalsIgnoreCase(midpointStrategyUsed)) {
			boolean topLastTaskMedianStrategy = "columnTaskMedianTopLast".equalsIgnoreCase(midpointStrategyUsed);
			MidpointColumnTimingStats stats = topLastTaskMedianStrategy ? evaluateTopLastMidpointColumnTiming(lp)
					: evaluateMidpointColumnTiming(lp);
			if (stats.count > 0) {
				boolean taskMedianStrategy = "columnTaskMedian".equalsIgnoreCase(midpointStrategyUsed)
						|| topLastTaskMedianStrategy;
				midpointReferenceTime = taskMedianStrategy ? stats.taskMedian
						: ("columnHalfAvg".equalsIgnoreCase(midpointStrategyUsed) ? stats.halfAvg : stats.lastAvg);
				midpointColumnSelectedCount = stats.count;
				midpointColumnLastMin = stats.lastMin;
				midpointColumnLastAvg = stats.lastAvg;
				midpointColumnLastMax = stats.lastMax;
				midpointColumnHalfMin = stats.halfMin;
				midpointColumnHalfAvg = stats.halfAvg;
				midpointColumnHalfMax = stats.halfMax;
				midpointColumnTaskSampleCount = stats.taskCount;
				midpointColumnTaskMin = stats.taskMin;
				midpointColumnTaskAvg = stats.taskAvg;
				midpointColumnTaskMedian = stats.taskMedian;
				midpointColumnTaskMax = stats.taskMax;
				double reference = Math.min(midpointReferenceTime, pricingHorizon);
				midpointStrategyNanos += System.nanoTime() - start;
				if ("columnHalfAvg".equalsIgnoreCase(midpointStrategyUsed) || taskMedianStrategy) {
					return clampCurrentMidpoint(reference);
				}
				return clampCurrentMidpoint((left + reference) * 0.5);
			}
		}

		midpointStrategyUsed = "default";
		candidate = computeDefaultMidpoint();
		midpointReferenceTime = pricingHorizon;
		midpointStrategyNanos += System.nanoTime() - start;
		return candidate;
	}

	private double computeDefaultMidpoint() {
		double left = midpointLeftBound();
		double candidate;
		if (Double.isFinite(left) && Utility.compareLt(left, pricingHorizon)) {
			candidate = (left + pricingHorizon) * 0.5;
		} else {
			// 2026-05-26: 当局部窗口已经贴到 pricingHorizon 时，回退到右偏切分，避免后向半区间长度为 0。
			candidate = pricingHorizon * 0.75;
		}
		return clampCurrentMidpoint(candidate);
	}

	private String configuredMidpointStrategy() {
		String strategy = config.bidirectionalMidpointStrategy == null ? "default"
				: config.bidirectionalMidpointStrategy.trim();
		return strategy.isEmpty() ? "default" : strategy;
	}

	private boolean requiresCompletionBoundForMidpoint() {
		return "completionBound".equalsIgnoreCase(configuredMidpointStrategy()) && completionBoundRelaxation != null;
	}

	private double midpointLeftBound() {
		double left = Math.max(dynamicMinHStart, earliestSourceCompletion);
		return Double.isFinite(left) ? left : 0.0;
	}

	private double incumbentBestMakespan() {
		if (data.configure == null || data.configure.bestSolution == null) {
			return Double.NaN;
		}
		Solution incumbent = data.configure.bestSolution;
		ArrayList<ArrayList<Integer>> sequences = incumbent.getSequencesCopy();
		double makespan = Double.NaN;
		for (ArrayList<Integer> sequence : sequences) {
			if (sequence.isEmpty()) {
				continue;
			}
			TWETColumnEvaluator.Timing timing = evaluator.evaluateTiming(sequence);
			if (Double.isFinite(timing.lastCompletion)) {
				makespan = Double.isNaN(makespan) ? timing.lastCompletion : Math.max(makespan, timing.lastCompletion);
			}
		}
		return makespan;
	}

	private double completionBoundArgminTime() {
		if (completionBounds == null) {
			return Double.NaN;
		}
		MidpointFunctionArgmin all = new MidpointFunctionArgmin();
		MidpointFunctionArgmin negative = new MidpointFunctionArgmin();
		for (int job = 1; job <= data.n; job++) {
			recordCompletionBoundArgmin(completionBounds.forwardFByJob[job], all, negative);
			recordCompletionBoundArgmin(completionBounds.forwardUByJob[job], all, negative);
			recordCompletionBoundArgmin(completionBounds.backwardBByJob[job], all, negative);
			recordCompletionBoundArgmin(completionBounds.backwardRByJob[job], all, negative);
		}
		return negative.count > 0 ? negative.maxTime : all.maxTime;
	}

	private void recordCompletionBoundArgmin(PiecewiseLinearFunction function, MidpointFunctionArgmin all,
			MidpointFunctionArgmin negative) {
		if (function == null) {
			return;
		}
		double[] min = function.findMinimal(false, true);
		if (min == null || min.length < 2 || !Double.isFinite(min[0]) || !Double.isFinite(min[1])) {
			return;
		}
		all.accept(min[1]);
		if (Utility.compareLt(min[0], 0.0)) {
			negative.accept(min[1]);
		}
	}

	private MidpointColumnTimingStats evaluateMidpointColumnTiming(LP lp) {
		MidpointColumnTimingStats stats = new MidpointColumnTimingStats();
		List<ColumnMidpointCandidate> candidates = selectMidpointColumnCandidates(lp);
		int limit = Math.max(0, config.bidirectionalMidpointColumnLimit);
		for (ColumnMidpointCandidate candidate : candidates) {
			if (limit > 0 && stats.count >= limit) {
				break;
			}
			TWETColumn column = lp.getPool().getColumn(candidate.columnId);
			ArrayList<Integer> sequence = new ArrayList<Integer>(column.getSequence());
			if (!isSequenceCompatible(sequence, lp.getNode())) {
				continue;
			}
			TWETColumnEvaluator.Timing timing = evaluator.evaluateTiming(sequence);
			if (Double.isFinite(timing.lastCompletion) && Double.isFinite(timing.halfCompletion)) {
				stats.accept(timing);
			}
		}
		stats.finish();
		return stats;
	}

	/**
	 * 2026-06-07: 先从 low reduced-cost 列中评价 2K 条，再按列末完工时间取最晚 K 条。
	 * 这样保留任务量 median 的语义，同时减少短列或早完工列把 Tmid 拉得过左。
	 */
	private MidpointColumnTimingStats evaluateTopLastMidpointColumnTiming(LP lp) {
		List<ColumnMidpointCandidate> candidates = selectMidpointColumnCandidates(lp);
		int selectedLimit = Math.max(0, config.bidirectionalMidpointColumnLimit);
		int timingLimit = selectedLimit > 0 ? selectedLimit * 2 : 0;
		ArrayList<ColumnMidpointTimingCandidate> timedCandidates = new ArrayList<ColumnMidpointTimingCandidate>();
		for (ColumnMidpointCandidate candidate : candidates) {
			if (timingLimit > 0 && timedCandidates.size() >= timingLimit) {
				break;
			}
			TWETColumn column = lp.getPool().getColumn(candidate.columnId);
			ArrayList<Integer> sequence = new ArrayList<Integer>(column.getSequence());
			if (!isSequenceCompatible(sequence, lp.getNode())) {
				continue;
			}
			TWETColumnEvaluator.Timing timing = evaluator.evaluateTiming(sequence);
			if (Double.isFinite(timing.lastCompletion) && Double.isFinite(timing.halfCompletion)) {
				timedCandidates.add(new ColumnMidpointTimingCandidate(candidate.columnId, timing));
			}
		}
		Collections.sort(timedCandidates, new Comparator<ColumnMidpointTimingCandidate>() {
			@Override
			public int compare(ColumnMidpointTimingCandidate a, ColumnMidpointTimingCandidate b) {
				int byLastCompletion = -Double.compare(a.timing.lastCompletion, b.timing.lastCompletion);
				return byLastCompletion != 0 ? byLastCompletion : Integer.compare(a.columnId, b.columnId);
			}
		});
		MidpointColumnTimingStats stats = new MidpointColumnTimingStats();
		for (ColumnMidpointTimingCandidate candidate : timedCandidates) {
			if (selectedLimit > 0 && stats.count >= selectedLimit) {
				break;
			}
			stats.accept(candidate.timing);
		}
		stats.finish();
		return stats;
	}

	private List<ColumnMidpointCandidate> selectMidpointColumnCandidates(LP lp) {
		ArrayList<ColumnMidpointCandidate> candidates = new ArrayList<ColumnMidpointCandidate>();
		for (int columnId : lp.getRestrictedColumnIds()) {
			TWETColumn column = lp.getPool().getColumn(columnId);
			if (column.getSequence().isEmpty()) {
				continue;
			}
			candidates.add(new ColumnMidpointCandidate(columnId, lp.getColumnReducedCost(columnId)));
		}
		Collections.sort(candidates, new Comparator<ColumnMidpointCandidate>() {
			@Override
			public int compare(ColumnMidpointCandidate a, ColumnMidpointCandidate b) {
				int byReducedCost = Double.compare(a.reducedCost, b.reducedCost);
				return byReducedCost != 0 ? byReducedCost : Integer.compare(a.columnId, b.columnId);
			}
		});
		return candidates;
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
		double clamped = candidate;
		if (Utility.compareLt(candidate, lower)) {
			clamped = lower;
		} else if (Utility.compareGt(candidate, upper)) {
			clamped = upper;
		}
		return clamped;
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

	private void precomputeCompletionBoundPricingWindows() {
		completionForwardPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		completionBackwardPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = effectiveJobHStart[job];
			double hEnd = effectiveJobHEnd[job];
			PiecewiseLinearFunction penalty = Utility.compareGt(hStart, hEnd)
					? null : buildCompletionBoundPenalty(job, hStart, hEnd);
			completionForwardPenaltyByJob[job] = penalty;
			completionBackwardPenaltyByJob[job] = penalty;
		}
	}

	private boolean isEffectiveWindowTighterThanHard(int job) {
		return Utility.compareGt(effectiveJobHStart[job], data.hardWindowStart[job])
				|| Utility.compareLt(effectiveJobHEnd[job], data.hardWindowEnd[job]);
	}

	/**
	 * 2026-05-25: 只抽取“和前驱/后继无关、单看 job 自己就落不到对应 half-domain”的信息。
	 * forward 若整段硬窗都在 Tmid 右侧，则任何 forward prefix 都不需要再尝试它；
	 * backward 对称地看整段硬窗是否已完全落在 Tmid 左侧。
	 */
	private void precomputeHalfDomainEligibility() {
		forwardHalfEligibleByJob = new boolean[data.n + 1];
		backwardHalfEligibleByJob = new boolean[data.n + 1];
		forwardHalfIneligibleJobCount = 0;
		backwardHalfIneligibleJobCount = 0;
		for (int job = 1; job <= data.n; job++) {
			boolean forwardEligible = dynamicJobPenaltyByJob[job] != null
					&& !Utility.compareGt(dynamicJobHStart[job], tMid);
			boolean backwardEligible = dynamicBackwardPenaltyByJob[job] != null
					&& !Utility.compareLt(dynamicBackwardHEndByJob[job], tMid);
			forwardHalfEligibleByJob[job] = forwardEligible;
			backwardHalfEligibleByJob[job] = backwardEligible;
			if (!forwardEligible) {
				forwardHalfIneligibleJobCount++;
			}
			if (!backwardEligible) {
				backwardHalfIneligibleJobCount++;
			}
		}
	}

	private void ensureBaseHalfPenaltyCache() {
		if (baseForwardHalfPenaltyByJob != null && Utility.compareEq(baseHalfPenaltyCacheTMid, tMid)
				&& Utility.compareEq(baseHalfPenaltyCacheHorizon, pricingHorizon)) {
			return;
		}
		baseForwardHalfPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		baseBackwardHalfPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			// 2026-05-24: data.penaltyFunction[job] 已经包含基于 b_j 的静态粗硬窗。
			// dual 不能进一步收紧时，双向 pricing 直接复用这两个半域缓存，避免每轮重复 setDomain/crop。
			baseForwardHalfPenaltyByJob[job] = cropToInterval(data.penaltyFunction[job], 0.0, tMid);
			baseBackwardHalfPenaltyByJob[job] = cropToInterval(data.penaltyFunction[job], tMid, pricingHorizon);
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
		// 2026-05-23: 半域边界直接写入动态 job penalty。
		// forward 的新增 job 函数只在 [0,Tmid] 上参与 add，公共定义域会自然把右端卡在 Tmid。
		return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), 0.0, tMid);
	}

	private PiecewiseLinearFunction buildBackwardHalfPenalty(int job, double hStart, double hEnd) {
		// 2026-05-23: backward 对称使用 [Tmid,pricingHorizon] 上的新增 job 函数。
		// 若窗口左侧为 big_M，后续 normalize(BACKWARD) 会通过 suffix-min 表达“可以等到窗口内完成”。
		return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), tMid, pricingHorizon);
	}

	private PiecewiseLinearFunction buildCompletionBoundPenalty(int job, double hStart, double hEnd) {
		// 2026-06-01: Tmid pricing 的正式 label 仍使用左右半域函数；completion bound
		// 需要判断半域 label 是否还能补成完整负列，因此单独使用完整 [0, pricingHorizon] 定义域。
		if (isEffectiveWindowTighterThanHard(job)) {
			return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), 0.0, pricingHorizon);
		}
		return cropToInterval(data.penaltyFunction[job], 0.0, pricingHorizon);
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

	private boolean visitedSetsIntersectForJoin(PackedBitSet left, PackedBitSet right) {
		for (int job = left.nextSetBit(1); job >= 0; job = left.nextSetBit(job + 1)) {
			if (!isZeroDualExcludedJob(job) && right.contains(job)) {
				return true;
			}
		}
		return false;
	}

	private boolean isForwardHalfEligibleJob(int job) {
		return job > 0 && forwardHalfEligibleByJob != null && job < forwardHalfEligibleByJob.length
				&& forwardHalfEligibleByJob[job];
	}

	private boolean isBackwardHalfEligibleJob(int job) {
		return job > 0 && backwardHalfEligibleByJob != null && job < backwardHalfEligibleByJob.length
				&& backwardHalfEligibleByJob[job];
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

	private ArrayList<Integer> recoverJoinSequence(ForwardLabel forward, BackwardLabel backward) {
		ArrayList<Integer> sequence = recoverForwardSequence(forward);
		sequence.addAll(recoverBackwardSequence(backward));
		return sequence;
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

	private void appendSegments(PiecewiseLinearFunction target, PiecewiseLinearFunction source) {
		if (target == null || source == null || source.head == null) {
			return;
		}
		for (Segment seg = source.head; seg != null; seg = seg.next) {
			target.addSegment(seg.start, seg.end, seg.slope, seg.intercept);
		}
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

	private static final class ColumnMidpointCandidate {
		final int columnId;
		final double reducedCost;

		ColumnMidpointCandidate(int columnId, double reducedCost) {
			this.columnId = columnId;
			this.reducedCost = reducedCost;
		}
	}

	private static final class MidpointFunctionArgmin {
		int count;
		double maxTime = Double.NaN;

		void accept(double time) {
			if (!Double.isFinite(time)) {
				return;
			}
			count++;
			maxTime = Double.isNaN(maxTime) ? time : Math.max(maxTime, time);
		}
	}

	private static final class MidpointColumnTimingStats {
		int count;
		double lastMin = Double.POSITIVE_INFINITY;
		double lastMax = Double.NEGATIVE_INFINITY;
		double lastSum;
		double lastAvg = Double.NaN;
		double halfMin = Double.POSITIVE_INFINITY;
		double halfMax = Double.NEGATIVE_INFINITY;
		double halfSum;
		double halfAvg = Double.NaN;
		ArrayList<Double> taskCompletions = new ArrayList<Double>();
		int taskCount;
		double taskMin = Double.NaN;
		double taskMax = Double.NaN;
		double taskSum;
		double taskAvg = Double.NaN;
		double taskMedian = Double.NaN;

		void accept(TWETColumnEvaluator.Timing timing) {
			count++;
			double lastCompletion = timing.lastCompletion;
			double halfCompletion = timing.halfCompletion;
			lastMin = Math.min(lastMin, lastCompletion);
			lastMax = Math.max(lastMax, lastCompletion);
			lastSum += lastCompletion;
			lastAvg = lastSum / count;
			halfMin = Math.min(halfMin, halfCompletion);
			halfMax = Math.max(halfMax, halfCompletion);
			halfSum += halfCompletion;
			halfAvg = halfSum / count;
			for (double completion : timing.completions) {
				if (Double.isFinite(completion)) {
					taskCompletions.add(Double.valueOf(completion));
					taskSum += completion;
				}
			}
		}

		void finish() {
			taskCount = taskCompletions.size();
			if (taskCount == 0) {
				return;
			}
			Collections.sort(taskCompletions);
			taskMin = taskCompletions.get(0).doubleValue();
			taskMax = taskCompletions.get(taskCount - 1).doubleValue();
			taskAvg = taskSum / taskCount;
			int middle = taskCount / 2;
			if (taskCount % 2 == 1) {
				taskMedian = taskCompletions.get(middle).doubleValue();
			} else {
				taskMedian = (taskCompletions.get(middle - 1).doubleValue()
						+ taskCompletions.get(middle).doubleValue()) * 0.5;
			}
		}
	}

	private static final class ColumnMidpointTimingCandidate {
		final int columnId;
		final TWETColumnEvaluator.Timing timing;

		ColumnMidpointTimingCandidate(int columnId, TWETColumnEvaluator.Timing timing) {
			this.columnId = columnId;
			this.timing = timing;
		}
	}

	static final class MidpointProbeNodeReuse {
		double bestExactTmid = Double.NaN;
		double bestExactMillis = Double.POSITIVE_INFINITY;
		double bestExactRatio = Double.POSITIVE_INFINITY;
		long bestExactLabelTotal = Long.MAX_VALUE;
		double lastExactTmid = Double.NaN;
		double lastExactMillis = Double.NaN;
		double lastExactRatio = Double.NaN;
		long lastExactLabelTotal;

		boolean hasBestExact() {
			return Double.isFinite(bestExactTmid) && Double.isFinite(bestExactMillis)
					&& Utility.compareGt(bestExactTmid, 0.0);
		}

		String considerExact(double tMid, double exactMillis, double ratio, long labelTotal,
				double timeTieTolerance, double balanceImprovementTolerance) {
			lastExactTmid = tMid;
			lastExactMillis = exactMillis;
			lastExactRatio = ratio;
			lastExactLabelTotal = labelTotal;
			if (!hasBestExact()) {
				updateBest(tMid, exactMillis, ratio, labelTotal);
				return "init";
			}
			boolean timeClose = isTimeClose(exactMillis, bestExactMillis, timeTieTolerance);
			if (timeClose && isBalanceMeaningfullyBetter(ratio, bestExactRatio, balanceImprovementTolerance)) {
				updateBest(tMid, exactMillis, ratio, labelTotal);
				return "balance";
			}
			if (!timeClose && Utility.compareLt(exactMillis, bestExactMillis)) {
				updateBest(tMid, exactMillis, ratio, labelTotal);
				return "time";
			}
			return "keep";
		}

		private void updateBest(double tMid, double exactMillis, double ratio, long labelTotal) {
			bestExactTmid = tMid;
			bestExactMillis = exactMillis;
			bestExactRatio = ratio;
			bestExactLabelTotal = labelTotal;
		}

		private boolean isTimeClose(double currentMillis, double incumbentMillis, double tolerance) {
			double base = Math.max(currentMillis, incumbentMillis);
			return Double.isFinite(base) && Utility.compareLe(Math.abs(currentMillis - incumbentMillis),
					base * tolerance);
		}

		private boolean isBalanceMeaningfullyBetter(double currentRatio, double incumbentRatio, double tolerance) {
			if (!Double.isFinite(currentRatio) || !Double.isFinite(incumbentRatio)) {
				return false;
			}
			double required = incumbentRatio * Math.max(0.0, 1.0 - tolerance);
			return Utility.compareLt(currentRatio, required);
		}
	}

	private static final class MidpointProbeResult {
		final double tMid;
		final double elapsedMillis;
		final int pops;
		final int forwardPops;
		final int backwardPops;
		final boolean forwardExhausted;
		final boolean backwardExhausted;
		final long forwardKept;
		final long backwardKept;
		final long forwardBoundSurvivors;
		final long forwardBoundPruned;
		final long backwardBoundPruned;
		final long forwardQueueRemaining;
		final long backwardQueueRemaining;
		final long forwardQueuePeak;
		final long backwardQueuePeak;
		final double keptScore;
		final double queueScore;
		final double boundScore;
		final double peakScore;
		final double remainingScore;

		MidpointProbeResult(double tMid, double elapsedMillis, int pops, boolean forwardExhausted, boolean backwardExhausted,
				int forwardPops, int backwardPops,
				long forwardKept, long backwardKept, long forwardBoundSurvivors,
				long forwardBoundPruned, long backwardBoundPruned, long forwardQueueRemaining, long backwardQueueRemaining,
				long forwardQueuePeak, long backwardQueuePeak) {
			this.tMid = tMid;
			this.elapsedMillis = elapsedMillis;
			this.pops = pops;
			this.forwardPops = forwardPops;
			this.backwardPops = backwardPops;
			this.forwardExhausted = forwardExhausted;
			this.backwardExhausted = backwardExhausted;
			this.forwardKept = forwardKept;
			this.backwardKept = backwardKept;
			this.forwardBoundSurvivors = forwardBoundSurvivors;
			this.forwardBoundPruned = forwardBoundPruned;
			this.backwardBoundPruned = backwardBoundPruned;
			this.forwardQueueRemaining = forwardQueueRemaining;
			this.backwardQueueRemaining = backwardQueueRemaining;
			this.forwardQueuePeak = forwardQueuePeak;
			this.backwardQueuePeak = backwardQueuePeak;
			this.keptScore = imbalance(forwardKept, backwardKept);
			this.queueScore = imbalance(forwardKept + forwardQueueRemaining, backwardKept + backwardQueueRemaining);
			this.boundScore = imbalance(forwardBoundSurvivors + forwardQueueRemaining, backwardKept + backwardQueueRemaining);
			this.peakScore = imbalance(forwardKept + forwardQueuePeak, backwardKept + backwardQueuePeak);
			this.remainingScore = imbalance(forwardQueueRemaining, backwardQueueRemaining);
		}

		double score(String mode) {
			String normalized = normalizeProbeScoreMode(mode);
			if ("kept".equals(normalized)) {
				return keptScore;
			}
			if ("bound".equals(normalized)) {
				return boundScore;
			}
			if ("peak".equals(normalized)) {
				return peakScore;
			}
			if ("remaining".equals(normalized)) {
				return remainingScore;
			}
			return queueScore;
		}

		double leftPressure(String mode) {
			String normalized = normalizeProbeScoreMode(mode);
			if ("kept".equals(normalized)) {
				return forwardKept;
			}
			if ("bound".equals(normalized)) {
				return forwardBoundSurvivors + forwardQueueRemaining;
			}
			if ("peak".equals(normalized)) {
				return forwardKept + forwardQueuePeak;
			}
			if ("remaining".equals(normalized)) {
				return forwardQueueRemaining;
			}
			return forwardKept + forwardQueueRemaining;
		}

		double rightPressure(String mode) {
			String normalized = normalizeProbeScoreMode(mode);
			if ("kept".equals(normalized)) {
				return backwardKept;
			}
			if ("bound".equals(normalized)) {
				return backwardKept + backwardQueueRemaining;
			}
			if ("peak".equals(normalized)) {
				return backwardKept + backwardQueuePeak;
			}
			if ("remaining".equals(normalized)) {
				return backwardQueueRemaining;
			}
			return backwardKept + backwardQueueRemaining;
		}

		int reliabilityRank(String mode) {
			if (forwardExhausted && backwardExhausted) {
				return 0;
			}
			return 1;
		}

		long totalPressure(String mode) {
			return Math.round(leftPressure(mode) + rightPressure(mode));
		}

		String compactSummary(String mode) {
			String normalized = normalizeProbeScoreMode(mode);
			return "t=" + tMid
					+ ",ms=" + elapsedMillis
					+ ",pop=" + pops
					+ ",sidePop=" + forwardPops + ":" + backwardPops
					+ ",ex=" + (forwardExhausted ? "F" : "f") + (backwardExhausted ? "B" : "b")
					+ ",kept=" + forwardKept + ":" + backwardKept
					+ ",q=" + forwardQueueRemaining + ":" + backwardQueueRemaining
					+ ",qPeak=" + forwardQueuePeak + ":" + backwardQueuePeak
					+ ",bound=" + forwardBoundSurvivors + ":" + backwardKept
					+ ",cb=" + forwardBoundPruned + ":" + backwardBoundPruned
					+ ",rank=" + reliabilityRank(mode)
					+ ",direction=" + pressureDirection(normalized)
					+ ",queueRatio=" + queueScore
					+ ",remainingRatio=" + remainingScore
					+ ",selectedScore=" + normalized + ":" + score(normalized)
					+ ",score=" + keptScore + "/" + queueScore + "/" + boundScore + "/" + peakScore + "/"
					+ remainingScore;
		}

		private static double imbalance(long left, long right) {
			double l = (double) left + 1.0;
			double r = (double) right + 1.0;
			return Math.max(l / r, r / l);
		}

		int pressureDirection(String mode) {
			double left = leftPressure(mode);
			double right = rightPressure(mode);
			if (Utility.compareGt(left, right)) {
				return 1;
			}
			if (Utility.compareLt(left, right)) {
				return -1;
			}
			return 0;
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
		final PackedBitSet extensionSet;
		/** join 阶段临时常数延拓后的函数缓存；label frontier 创建后不再修改，可以安全复用。 */
		PiecewiseLinearFunction joinExtendedFrontier;

		FunctionLabel(int labelId, int jid, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PackedBitSet extensionSet, PiecewiseLinearFunction frontier, double minReducedCost) {
			super(jid, null, visitedSet, reachableSet, frontier, minReducedCost);
			this.labelId = labelId;
			this.extensionSet = extensionSet;
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
		final int depth;

		ForwardLabel(int labelId, int jid, ForwardLabel father, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PackedBitSet extensionSet, PiecewiseLinearFunction frontier) {
			super(labelId, jid, visitedSet, reachableSet, extensionSet, frontier, forwardEndpointMin(frontier));
			this.father = father;
			this.depth = father == null ? 0 : father.depth + 1;
		}
	}

	private static final class BackwardLabel extends FunctionLabel {
		final BackwardLabel father;
		final boolean isSinkRoot;

		BackwardLabel(int labelId, int jid, BackwardLabel father, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PackedBitSet extensionSet, PiecewiseLinearFunction frontier, boolean isSinkRoot) {
			super(labelId, jid, visitedSet, reachableSet, extensionSet, frontier, backwardEndpointMin(frontier));
			this.father = father;
			this.isSinkRoot = isSinkRoot;
		}

		static BackwardLabel sink(int labelId, int sinkId, PackedBitSet visitedSet, PiecewiseLinearFunction frontier,
				PackedBitSet reachableSet, PackedBitSet extensionSet) {
			return new BackwardLabel(labelId, sinkId, null, visitedSet, reachableSet, extensionSet, frontier, true);
		}
	}
}

