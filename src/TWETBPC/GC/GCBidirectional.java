package TWETBPC.GC;

import java.util.ArrayList;
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
 * no-cut 双向 pricing 的 hybrid-B 基准版。
 * <p>
 * 只有在列数上限未截断、且 forward/backward 队列都被完整耗尽时，本轮结果才可作为 exact pricing
 * certificate；若达到 {@link TWETBPCConfig#maxExactPricingColumns}，这里只表示“最多生成 K 条负列”。
 * <p>
 * 2026-05-22: 这里不再沿用旧实现的“同一个中间点 join”标量标签，而是改成和论文一致的
 * “forward 前缀 + crossing arc (i,r) + backward 后缀”的弧拼接。
 * 相对正式 GCBB-style final join 基准，本类的主要差异是外层流程：先完整生成 forward
 * half labels，再处理 backward half；backward label 出队时立即和已有 forward labels 做
 * crossing-arc join，同时 forward label 出队时尝试 forward->sink 收尾。因此列生成会受 label
 * 出队顺序影响，不使用统一 final top-K 候选池。
 * <p>
 * 当前版本先保证 elementary 双向函数递推和 T^mid 半域语义正确：
 * 1. forward label 存储在 [ell, Tmid]；
 * 2. backward label 存储在 [Tmid, rho]；
 * 3. join 时用论文里的常数延拓，临时补齐 forward 右半域和 backward 左半域，然后按 crossing arc 对齐相加；
 * 4. 默认直接使用 label/join 推导出的 reduced cost 反推出列成本；如需完整序列复核，可打开
 * {@link Configure#debugBPCPricingColumnCheck}。
 * <p>
 * 2026-05-31: 本类已封存，不再作为后续维护或实验入口。原因是它的外层流程本身不适合当前
 * top-K exact pricing 口径：负列在 forward 出队收尾和 backward 出队 join 时在线加入
 * {@code generatedColumns}，达到 {@link TWETBPCConfig#maxExactPricingColumns} 后立即停止，
 * 因此得到的是“扫描顺序下最先发现的 K 条负列”，不是统一候选池中的 best K。更重要的是，
 * 根节点 no-cut 下启用 pi profitable window 时，label/join inferred 成本只是当前 pricing 的
 * 临时窗口口径；本类正常路径不会在最终加列前用 {@link TWETColumnEvaluator} 修正
 * {@link TWETColumn#cost}，只有 debug 开关下才会复核。继续在这里补丁式维护会同时保留
 * first-K 流程问题和 pi-window 永久列成本污染风险。后续双向 pricing 修改统一放到带
 * final top-K 候选堆和 K 堆固定后成本复核的 GCBB/GCNGBB-style 实现中。
 */
@Deprecated
public class GCBidirectional {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;
	private enum LabelQueueOrdering {
		REDUCED_COST, TIME, REACHABLE_SIZE
	}

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;

	private PriorityQueue<ForwardLabel> FWUL;
	private PriorityQueue<BackwardLabel> BWUL;
	private ArrayList<DominanceStore> FWTL;
	private ArrayList<DominanceStore> BWTL;
	private ArrayList<ArrayList<ForwardLabel>> activeForwardByLastJob;
	private ArrayList<SinglePointStore<ForwardLabel>> forwardSinglePointByLastJob;
	private ArrayList<SinglePointStore<BackwardLabel>> backwardSinglePointByFirstJob;
	private PackedBitSet activeForwardTerminalJobs;
	private double[] minForwardReducedCostByLastJob;
	private double[] minForwardEllByLastJob;
	private ArrayList<TWETColumn> generatedColumns;
	private HashSet<SequenceSignature> generatedSignatures;
	private HashSet<SequenceSignature> activeColumnSignatures;
	private boolean[] zeroDualExcludedJobs;
	private int zeroDualExcludedJobCount;
	private int nextLabelId;
	private LabelQueueOrdering queueOrdering;

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
	private PiecewiseLinearFunction[] dynamicBackwardPenaltyByJob;
	private double[] dynamicBackwardHStartByJob;
	private double[] dynamicBackwardHEndByJob;
	private boolean[] forwardHalfEligibleByJob;
	private boolean[] backwardHalfEligibleByJob;
	private int forwardHalfIneligibleJobCount;
	private int backwardHalfIneligibleJobCount;
	private PiecewiseLinearFunction[] baseForwardHalfPenaltyByJob;
	private PiecewiseLinearFunction[] baseBackwardHalfPenaltyByJob;
	private double baseHalfPenaltyCacheTMid = Double.NaN;
	// 2026-05-24: 只有根节点且没有 cut dual 时，pi_j profitable window 才保留三角不等式依据。
	private boolean dualProfitableWindowEnabled;

	private long forwardLabelsKept;
	private long forwardLabelsDominated;
	private long backwardLabelsKept;
	private long backwardLabelsDominated;
	private long joinTerminalGroupsScanned;
	private long joinTerminalGroupsArcOrVisitPruned;
	private long joinTerminalGroupsEmpty;
	private long joinTerminalGroupsTimePruned;
	private long joinTerminalGroupsCostPruned;
	private long joinCandidateLabelsVisited;
	private long joinCandidateLabelsDominated;
	private long joinPairsTried;
	private long joinPairsSetPruned;
	private long joinPairsLowerBoundPruned;
	private long joinPairsTimePruned;
	private long joinFunctionEvaluations;
	private long joinFunctionPruned;
	private long forwardSinglePointKept;
	private long forwardSinglePointDominatedByStore;
	private long forwardSinglePointDominatedByGraph;
	private long backwardSinglePointKept;
	private long backwardSinglePointDominatedByStore;
	private long backwardSinglePointDominatedByGraph;

	private String lastMessage = "Bidirectional pricing not executed";

	public GCBidirectional(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	public ArrayList<TWETColumn> solve(LP lp) {
		generatedColumns = new ArrayList<TWETColumn>();
		lastMessage = "Deprecated hybrid-B bidirectional pricing is disabled: the old online first-K flow has no final top-K heap, "
				+ "and root pi-window inferred costs are not repaired before columns enter the master problem";
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

	/**
	 * 2026-05-26: 旧 hybrid-B 路径也复用同一组队列排序策略，便于和 GCNGBB-style final join 对比。
	 */
	private Comparator<ForwardLabel> forwardQueueComparator(LabelQueueOrdering ordering) {
		return new Comparator<ForwardLabel>() {
			@Override
			public int compare(ForwardLabel left, ForwardLabel right) {
				if (ordering == LabelQueueOrdering.TIME) {
					int byTime = compareDoubleAsc(earliestForwardCompletion(left), earliestForwardCompletion(right));
					return byTime != 0 ? byTime : compareReducedCost(left, right);
				}
				if (ordering == LabelQueueOrdering.REACHABLE_SIZE) {
					int byReachable = Integer.compare(right.reachableCardinality, left.reachableCardinality);
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
					return byTime != 0 ? byTime : compareReducedCost(left, right);
				}
				if (ordering == LabelQueueOrdering.REACHABLE_SIZE) {
					int byReachable = Integer.compare(right.reachableCardinality, left.reachableCardinality);
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
		PaperDominanceGraphs.resetStatistics();
		pricingHorizon = data.CmaxH;
		tMid = Math.min(data.CmaxH * 0.5, pricingHorizon);
		queueOrdering = parseQueueOrdering(config.bidirectionalLabelQueueOrdering);
		FWUL = new PriorityQueue<ForwardLabel>(forwardQueueComparator(queueOrdering));
		BWUL = new PriorityQueue<BackwardLabel>(backwardQueueComparator(queueOrdering));
		FWTL = new ArrayList<DominanceStore>(data.n + 1);
		BWTL = new ArrayList<DominanceStore>(data.n + 1);
		activeForwardByLastJob = new ArrayList<ArrayList<ForwardLabel>>(data.n + 1);
		forwardSinglePointByLastJob = new ArrayList<SinglePointStore<ForwardLabel>>(data.n + 1);
		backwardSinglePointByFirstJob = new ArrayList<SinglePointStore<BackwardLabel>>(data.n + 1);
		activeForwardTerminalJobs = new PackedBitSet(data.n + 2);
		minForwardReducedCostByLastJob = new double[data.n + 1];
		minForwardEllByLastJob = new double[data.n + 1];
		for (int i = 0; i <= data.n; i++) {
			FWTL.add(PaperDominanceGraphs.create(Direction.FORWARD));
			BWTL.add(PaperDominanceGraphs.create(Direction.BACKWARD));
			activeForwardByLastJob.add(new ArrayList<ForwardLabel>());
			forwardSinglePointByLastJob.add(new SinglePointStore<ForwardLabel>());
			backwardSinglePointByFirstJob.add(new SinglePointStore<BackwardLabel>());
			minForwardReducedCostByLastJob[i] = Utility.big_M;
			minForwardEllByLastJob[i] = Utility.big_M;
		}
		generatedColumns = new ArrayList<TWETColumn>();
		generatedSignatures = new HashSet<SequenceSignature>();
		activeColumnSignatures = new HashSet<SequenceSignature>();
		nextLabelId = 0;
		// 只记录当前 RMP active 列。全局 pool 自身会按 signature 去重；若历史列当前不 active，
		// pricing 仍可把它返回给 PC，让 LP.addColumns() 重新激活已有列。
		for (int columnId : lp.getRestrictedColumnIds()) {
			activeColumnSignatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		precomputeDynamicPricingWindows(lp);

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
		return generatedColumns.size() < config.maxExactPricingColumns;
	}

	private void forwardExtend(LP lp) {
		ForwardLabel label = FWUL.poll();
		if (label.isDominated) {
			return;
		}
		tryGenerateForwardColumn(label, lp);

		Node node = lp.getNode();
		for (int nextJob = label.extensionSet.nextSetBit(1); nextJob > 0 && nextJob <= data.n && canContinue();
				nextJob = label.extensionSet.nextSetBit(nextJob + 1)) {
			if (!canExtendForward(label, nextJob, node)) {
				continue;
			}
			ForwardLabel child = extendForward(label, nextJob, lp);
			if (child == null || Utility.isBigMValue(child.minReducedCost)) {
				continue;
			}
			if (insertForward(child, lp) == InsertStatus.STORED_AND_ENQUEUE) {
				FWUL.add(child);
			}
		}
	}

	private void backwardExtend(LP lp) {
		BackwardLabel label = BWUL.poll();
		if (label.isDominated) {
			return;
		}
		if (!label.isSinkRoot) {
			joinFromBackward(label, lp);
		}

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
			if (insertBackward(child, lp) == InsertStatus.STORED_AND_ENQUEUE) {
				BWUL.add(child);
			}
		}
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

	private boolean isPricingArcForbidden(Node node, int fromJob, int toJob) {
		return node.isArcForbidden(fromJob, toJob)
				|| (!ignorePricingOnlyArcsForNode(node) && node.isPricingOnlyArcForbidden(fromJob, toJob));
	}

	private boolean ignorePricingOnlyArcsForNode(Node node) {
		return node != null && config.debugIgnorePricingOnlyArcsAtNode >= 0
				&& node.id == config.debugIgnorePricingOnlyArcsAtNode;
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
		tryGenerateForwardColumn(label, lp);
		return InsertStatus.STORED_NO_EXPAND;
	}

	/**
	 * 2026-05-25: Tmid 单点 backward label 只保留给 single-point store 和立即 join，
	 * 不再进入普通 dominance graph 或 backward 扩展队列。
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
		joinFromBackward(label, lp);
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
		if (label.jid == 0 || generatedColumns.size() >= config.maxExactPricingColumns) {
			return;
		}
		Node node = lp.getNode();
		int sink = node.sinkId();
		if (isPricingArcForbidden(node, label.jid, sink)) {
			return;
		}
		double reducedCost = label.minReducedCost - lp.getArcDual(label.jid, sink);
		if (!Utility.compareLt(reducedCost, REDUCED_COST_TOLERANCE)) {
			return;
		}
		ArrayList<Integer> sequence = recoverForwardSequence(label);
		tryGenerateColumn(sequence, lp, reducedCost);
	}

	private void joinFromBackward(BackwardLabel backward, LP lp) {
		if (generatedColumns.size() >= config.maxExactPricingColumns) {
			return;
		}
		Node node = lp.getNode();
		// 2026-05-23: 和 joinFromForward 对称，不能用 backward.reachableSet 反推所有可拼接前缀。
		// 该集合是 backward 继续向左扩展的候选，不等价于所有可与当前后缀拼接的 forward terminal。
		for (int lastJob = activeForwardTerminalJobs.nextSetBit(0); lastJob >= 0 && lastJob <= data.n && canContinue();
				lastJob = activeForwardTerminalJobs.nextSetBit(lastJob + 1)) {
			joinTerminalGroupsScanned++;
			if (backward.visitedSet.contains(lastJob) || isPricingArcForbidden(node, lastJob, backward.jid)) {
				joinTerminalGroupsArcOrVisitPruned++;
				continue;
			}
			ArrayList<ForwardLabel> candidates = activeForwardByLastJob.get(lastJob);
			if (candidates.isEmpty()) {
				joinTerminalGroupsEmpty++;
				continue;
			}
			double delay = data.getSetUp(lastJob, backward.jid) + data.getProcessT(backward.jid);
			if (Utility.compareGt(minForwardEllByLastJob[lastJob] + delay, backward.frontier.tail.end)) {
				joinTerminalGroupsTimePruned++;
				continue;
			}
			double groupLB = minForwardReducedCostByLastJob[lastJob] + backward.minReducedCost
					+ data.getSetupCost(lastJob, backward.jid) - lp.getArcDual(lastJob, backward.jid);
			if (!Utility.compareLt(groupLB, REDUCED_COST_TOLERANCE)) {
				joinTerminalGroupsCostPruned++;
				continue;
			}
			int liveCount = 0;
			double liveMinReducedCost = Utility.big_M;
			double liveMinEll = Utility.big_M;
			for (int i = 0; i < candidates.size(); i++) {
				ForwardLabel forward = candidates.get(i);
				joinCandidateLabelsVisited++;
				if (forward.isDominated) {
					joinCandidateLabelsDominated++;
					continue;
				}
				candidates.set(liveCount++, forward);
				if (Utility.compareLt(forward.minReducedCost, liveMinReducedCost)) {
					liveMinReducedCost = forward.minReducedCost;
				}
				if (forward.frontier != null && forward.frontier.head != null
						&& Utility.compareLt(forward.frontier.head.start, liveMinEll)) {
					liveMinEll = forward.frontier.head.start;
				}
				if (canContinue()) {
					tryJoin(forward, backward, lp);
				}
			}
			refreshForwardGroupAfterJoinScan(lastJob, candidates, liveCount, liveMinReducedCost, liveMinEll);
		}
	}

	private void tryJoin(ForwardLabel forward, BackwardLabel backward, LP lp) {
		if (generatedColumns.size() >= config.maxExactPricingColumns) {
			return;
		}
		joinPairsTried++;
		if (forward.jid == backward.jid || visitedSetsIntersectForJoin(forward.visitedSet, backward.visitedSet)) {
			joinPairsSetPruned++;
			return;
		}
		double joinFixedReducedCost = data.getSetupCost(forward.jid, backward.jid)
				- lp.getArcDual(forward.jid, backward.jid);
		// 2026-05-22: 先用 forward/backward frontier 的全局最小值做一次乐观下界。
		// join 的常数延拓和 crossing arc 对齐不会产生比两侧 frontier 全局最小值之和更低的下界，
		// 因此若这个标量下界都不能为负，就没必要再构造 join 函数。
		double optimisticJoinLB = forward.minReducedCost + backward.minReducedCost + joinFixedReducedCost;
		if (!Utility.compareLt(optimisticJoinLB, REDUCED_COST_TOLERANCE)) {
			joinPairsLowerBoundPruned++;
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
		if (!Utility.compareLt(reducedCostBound, REDUCED_COST_TOLERANCE)) {
			joinFunctionPruned++;
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
		joinTerminalGroupsEmpty = 0;
		joinTerminalGroupsTimePruned = 0;
		joinTerminalGroupsCostPruned = 0;
		joinCandidateLabelsVisited = 0;
		joinCandidateLabelsDominated = 0;
		joinPairsTried = 0;
		joinPairsSetPruned = 0;
		joinPairsLowerBoundPruned = 0;
		joinPairsTimePruned = 0;
		joinFunctionEvaluations = 0;
		joinFunctionPruned = 0;
		forwardSinglePointKept = 0;
		forwardSinglePointDominatedByStore = 0;
		forwardSinglePointDominatedByGraph = 0;
		backwardSinglePointKept = 0;
		backwardSinglePointDominatedByStore = 0;
		backwardSinglePointDominatedByGraph = 0;
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
				+ ", join groups scanned/arcOrVisit/empty/timeLB/costLB=" + joinTerminalGroupsScanned
				+ "/" + joinTerminalGroupsArcOrVisitPruned + "/" + joinTerminalGroupsEmpty
				+ "/" + joinTerminalGroupsTimePruned + "/" + joinTerminalGroupsCostPruned
				+ ", join candidates visited/dominated=" + joinCandidateLabelsVisited + "/"
				+ joinCandidateLabelsDominated
				+ ", join pairs tried/set/lb/time/funcEval/funcPruned=" + joinPairsTried
				+ "/" + joinPairsSetPruned + "/" + joinPairsLowerBoundPruned + "/"
				+ joinPairsTimePruned + "/"
				+ joinFunctionEvaluations + "/" + joinFunctionPruned
				+ ", queueOrdering=" + queueOrdering
				+ ", pricingHorizon=" + pricingHorizon + ", tMid=" + tMid
				+ ", zeroDualExcludedJobs=" + zeroDualExcludedJobCount
				+ ", dualWindow=" + (dualProfitableWindowEnabled ? "enabled" : "staticOutsourcingOnly")
				+ ", " + PaperDominanceGraphs.statisticsSummary();
	}

	private void refreshForwardGroupAfterJoinScan(int lastJob, ArrayList<ForwardLabel> candidates, int liveCount,
			double liveMinReducedCost, double liveMinEll) {
		if (liveCount < candidates.size()) {
			candidates.subList(liveCount, candidates.size()).clear();
		}
		if (liveCount == 0) {
			activeForwardTerminalJobs.remove(lastJob);
			minForwardReducedCostByLastJob[lastJob] = Utility.big_M;
			minForwardEllByLastJob[lastJob] = Utility.big_M;
			return;
		}
		minForwardReducedCostByLastJob[lastJob] = liveMinReducedCost;
		minForwardEllByLastJob[lastJob] = liveMinEll;
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
		if (sequence.isEmpty() || generatedColumns.size() >= config.maxExactPricingColumns) {
			return;
		}
		if (PricingCompatibility.containsRequiredOutsourcedJob(lp.getNode(), sequence)) {
			return;
		}
		SequenceSignature signature = new SequenceSignature(sequence);
		if (activeColumnSignatures.contains(signature) || !generatedSignatures.add(signature)) {
			return;
		}
		double cost = objectiveCostFromReducedCost(sequence, inferredReducedCost, lp);
		double reducedCost = inferredReducedCost;
		if (Configure.debugBPCPricingColumnCheck) {
			double checkedCost = evaluator.evaluate(sequence);
			if (Utility.isBigMValue(checkedCost)) {
				return;
			}
			double checkedReducedCost = reducedCost(sequence, checkedCost, lp);
			if (Math.abs(checkedReducedCost - inferredReducedCost) > 1e-5) {
				System.err.println("[debugBPCPricingColumnCheck] bidirectional pricing reduced-cost mismatch: inferred="
						+ inferredReducedCost + ", checked=" + checkedReducedCost + ", sequence=" + sequence);
			}
			cost = checkedCost;
			reducedCost = checkedReducedCost;
		}
		if (Utility.compareLt(reducedCost, REDUCED_COST_TOLERANCE)) {
			generatedColumns.add(new TWETColumn(-1, sequence, data.n, cost, ColumnSource.PRICING_EXACT, false));
		}
	}

	private double objectiveCostFromReducedCost(ArrayList<Integer> sequence, double reducedCost, LP lp) {
		double cost = reducedCost + lp.getMachineDual();
		int prev = 0;
		for (int job : sequence) {
			cost += lp.getJobDual(job);
			cost += lp.getArcDual(prev, job);
			prev = job;
		}
		cost += lp.getArcDual(prev, lp.getNode().sinkId());
		return cost;
	}

	private double reducedCost(ArrayList<Integer> sequence, double cost, LP lp) {
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
		dynamicBackwardPenaltyByJob = null;
		dynamicBackwardHStartByJob = null;
		dynamicBackwardHEndByJob = null;
		forwardHalfEligibleByJob = null;
		backwardHalfEligibleByJob = null;
		forwardHalfIneligibleJobCount = 0;
		backwardHalfIneligibleJobCount = 0;
		zeroDualExcludedJobs = null;
		zeroDualExcludedJobCount = 0;
		dualProfitableWindowEnabled = canUseDualProfitableWindow(lp);
		pricingHorizon = computeCurrentPricingHorizon(lp);
		tMid = computeCurrentMidpoint(lp);
		precomputeZeroDualExcludedJobs(lp);
		ensureBaseHalfPenaltyCache();
		precomputeJobLevelDynamicPricingWindows(lp);
		precomputeBackwardDynamicPricingWindows(lp);
		precomputeHalfDomainEligibility();
	}

	private double computeCurrentMidpoint(LP lp) {
		double candidate;
		if (Double.isFinite(config.bidirectionalRootLocalHorizonMidpointRatio)
				&& Utility.compareGt(config.bidirectionalRootLocalHorizonMidpointRatio, 0.0)
				&& Utility.compareLt(config.bidirectionalRootLocalHorizonMidpointRatio, 1.0)) {
			candidate = pricingHorizon * config.bidirectionalRootLocalHorizonMidpointRatio;
			return clampCurrentMidpoint(candidate);
		}
		double left = Math.max(computeCurrentPricingWindowStart(lp), computeEarliestSourceCompletion());
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

	private double computeCurrentPricingWindowStart(LP lp) {
		double localStart = Utility.big_M;
		boolean foundFiniteWindow = false;
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			double baseline = outsourcingBaseline(job);
			double jobDual = dualProfitableWindowEnabled ? Math.max(0.0, lp.getJobDual(job)) : baseline;
			if (dualProfitableWindowEnabled && Utility.compareLt(jobDual, baseline)) {
				double dynamicStart = hWindowStart(job, jobDual);
				double dynamicEnd = hWindowEnd(job, jobDual);
				if (Utility.compareGt(dynamicStart, data.hardWindowStart[job])
						|| Utility.compareLt(dynamicEnd, data.hardWindowEnd[job])) {
					hStart = dynamicStart;
					hEnd = dynamicEnd;
				}
			}
			if (!Utility.compareGt(hStart, hEnd) && Double.isFinite(hStart)) {
				localStart = Math.min(localStart, hStart);
				foundFiniteWindow = true;
			}
		}
		return foundFiniteWindow ? localStart : 0.0;
	}

	private double computeEarliestSourceCompletion() {
		double earliest = Utility.big_M;
		for (int job = 1; job <= data.n; job++) {
			earliest = Math.min(earliest, data.getSetUp(0, job) + data.getProcessT(job));
		}
		return earliest;
	}

	/**
	 * 2026-05-24: 先按本轮实际使用的 job 右端时间窗求一个局部 horizon，
	 * 再用它压住全局 CmaxH/2，避免 root no-cut 且 profitable window 明显收紧时，
	 * backward sink root 因 Tmid 过右而完全无标签。
	 */
	private double computeCurrentPricingHorizon(LP lp) {
		double localHorizon = 0.0;
		boolean foundFiniteWindow = false;
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			double baseline = outsourcingBaseline(job);
			double jobDual = dualProfitableWindowEnabled ? Math.max(0.0, lp.getJobDual(job)) : baseline;
			if (dualProfitableWindowEnabled && Utility.compareLt(jobDual, baseline)) {
				double dynamicStart = hWindowStart(job, jobDual);
				double dynamicEnd = hWindowEnd(job, jobDual);
				if (Utility.compareGt(dynamicStart, data.hardWindowStart[job])
						|| Utility.compareLt(dynamicEnd, data.hardWindowEnd[job])) {
					hStart = dynamicStart;
					hEnd = dynamicEnd;
				}
			}
			if (!Utility.compareGt(hStart, hEnd) && Double.isFinite(hEnd)) {
				localHorizon = Math.max(localHorizon, hEnd);
				foundFiniteWindow = true;
			}
		}
		if (!foundFiniteWindow) {
			return data.CmaxH;
		}
		return Math.min(data.CmaxH, localHorizon);
	}

	private void precomputeJobLevelDynamicPricingWindows(LP lp) {
		dynamicJobPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicJobHStart = new double[data.n + 1];
		dynamicJobHEnd = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			PiecewiseLinearFunction penalty = baseForwardHalfPenaltyByJob[job];
			double baseline = outsourcingBaseline(job);
			double jobDual = dualProfitableWindowEnabled ? Math.max(0.0, lp.getJobDual(job)) : baseline;
			if (dualProfitableWindowEnabled && Utility.compareLt(jobDual, baseline)) {
				double dynamicStart = hWindowStart(job, jobDual);
				double dynamicEnd = hWindowEnd(job, jobDual);
				if (Utility.compareGt(dynamicStart, data.hardWindowStart[job])
						|| Utility.compareLt(dynamicEnd, data.hardWindowEnd[job])) {
					hStart = dynamicStart;
					hEnd = dynamicEnd;
					penalty = Utility.compareGt(hStart, hEnd) ? null : buildForwardHalfPenalty(job, hStart, hEnd);
				}
			}
			dynamicJobHStart[job] = hStart;
			dynamicJobHEnd[job] = hEnd;
			dynamicJobPenaltyByJob[job] = penalty;
		}
	}

	private void precomputeBackwardDynamicPricingWindows(LP lp) {
		dynamicBackwardPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicBackwardHStartByJob = new double[data.n + 1];
		dynamicBackwardHEndByJob = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			PiecewiseLinearFunction penalty = baseBackwardHalfPenaltyByJob[job];
			double baseline = outsourcingBaseline(job);
			double jobDual = dualProfitableWindowEnabled ? Math.max(0.0, lp.getJobDual(job)) : baseline;
			if (dualProfitableWindowEnabled && Utility.compareLt(jobDual, baseline)) {
				double dynamicStart = hWindowStart(job, jobDual);
				double dynamicEnd = hWindowEnd(job, jobDual);
				if (Utility.compareGt(dynamicStart, data.hardWindowStart[job])
						|| Utility.compareLt(dynamicEnd, data.hardWindowEnd[job])) {
					hStart = dynamicStart;
					hEnd = dynamicEnd;
					penalty = Utility.compareGt(hStart, hEnd) ? null : buildBackwardHalfPenalty(job, hStart, hEnd);
				}
			}
			dynamicBackwardHStartByJob[job] = hStart;
			dynamicBackwardHEndByJob[job] = hEnd;
			dynamicBackwardPenaltyByJob[job] = penalty;
		}
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
		if (baseForwardHalfPenaltyByJob != null && Utility.compareEq(baseHalfPenaltyCacheTMid, tMid)) {
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
	 * 2026-05-29: 根节点 no-cut pricing 中，pi_j=0 的任务整轮不进入 pricing 扩展。
	 * 初始化 source/sink visited 时直接标记这些 job，后续 reachable set 只按 visited 过滤。
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

	private boolean isForwardHalfEligibleJob(int job) {
		return job > 0 && forwardHalfEligibleByJob != null && job < forwardHalfEligibleByJob.length
				&& forwardHalfEligibleByJob[job];
	}

	private boolean isBackwardHalfEligibleJob(int job) {
		return job > 0 && backwardHalfEligibleByJob != null && job < backwardHalfEligibleByJob.length
				&& backwardHalfEligibleByJob[job];
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

		ForwardLabel(int labelId, int jid, ForwardLabel father, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PackedBitSet extensionSet, PiecewiseLinearFunction frontier) {
			super(labelId, jid, visitedSet, reachableSet, extensionSet, frontier, forwardEndpointMin(frontier));
			this.father = father;
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
