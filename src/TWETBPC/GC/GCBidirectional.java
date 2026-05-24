package TWETBPC.GC;

import java.util.ArrayList;
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
 * no-cut 双向 exact pricing。
 * <p>
 * 2026-05-22: 这里不再沿用旧实现的“同一个中间点 join”标量标签，而是改成和论文一致的
 * “forward 前缀 + crossing arc (i,r) + backward 后缀”的弧拼接。
 * 外层流程仍保持旧 VRP 双向 GC 的组织方式：前向扩展、后向扩展、两侧 label table、扩展后尝试 join。
 * <p>
 * 当前版本先保证 elementary 双向函数递推和 T^mid 半域语义正确：
 * 1. forward label 存储在 [ell, Tmid]；
 * 2. backward label 存储在 [Tmid, rho]；
 * 3. join 时用论文里的常数延拓，临时补齐 forward 右半域和 backward 左半域，然后按 crossing arc 对齐相加；
 * 4. 默认直接使用 label/join 推导出的 reduced cost 反推出列成本；如需完整序列复核，可打开
 * {@link Configure#debugBPCPricingColumnCheck}。
 */
public class GCBidirectional {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;

	private PriorityQueue<ForwardLabel> FWUL;
	private PriorityQueue<BackwardLabel> BWUL;
	private ArrayList<DominanceStore> FWTL;
	private ArrayList<DominanceStore> BWTL;
	private ArrayList<ArrayList<ForwardLabel>> activeForwardByLastJob;
	private double[] minForwardReducedCostByLastJob;
	private double[] minForwardEllByLastJob;
	private ArrayList<TWETColumn> generatedColumns;
	private HashSet<SequenceSignature> generatedSignatures;
	private HashSet<SequenceSignature> activeColumnSignatures;
	private HashSet<Long> attemptedJoinPairs;
	private int nextLabelId;

	// 2026-05-22: 双向 midpoint，只对当前 pricing 轮有效。
	private double tMid;
	// 2026-05-22: 当前定价轮的 job-level 动态 H_j 缓存。
	private PiecewiseLinearFunction[] dynamicJobPenaltyByJob;
	private double[] dynamicJobHEnd;
	private PiecewiseLinearFunction[] dynamicBackwardPenaltyByJob;
	private double[] dynamicBackwardHStartByJob;
	private double[] dynamicBackwardHEndByJob;

	private String lastMessage = "Bidirectional pricing not executed";

	public GCBidirectional(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	public ArrayList<TWETColumn> solve(LP lp) {
		Utility.resetCurUpperBound(Utility.big_M);
		initialize(lp);
		// 2026-05-24: hybrid-B 流程。先完整生成 forward half labels，
		// 再在 backward half 中用 crossing arc 和已有 forward labels 拼接。
		while (canContinue() && !FWUL.isEmpty()) {
			forwardExtend(lp);
		}
		if (canContinue()) {
			initializeBackwardSink(lp);
		}
		while (canContinue() && !BWUL.isEmpty()) {
			backwardExtend(lp);
		}
		lastMessage = "Bidirectional no-cut labeling generated " + generatedColumns.size() + " columns";
		return generatedColumns;
	}

	public String getLastMessage() {
		return lastMessage;
	}

	private void initialize(LP lp) {
		tMid = data.CmaxH * 0.5;
		FWUL = new PriorityQueue<ForwardLabel>();
		BWUL = new PriorityQueue<BackwardLabel>();
		FWTL = new ArrayList<DominanceStore>(data.n + 1);
		BWTL = new ArrayList<DominanceStore>(data.n + 1);
		activeForwardByLastJob = new ArrayList<ArrayList<ForwardLabel>>(data.n + 1);
		minForwardReducedCostByLastJob = new double[data.n + 1];
		minForwardEllByLastJob = new double[data.n + 1];
		for (int i = 0; i <= data.n; i++) {
			FWTL.add(new PaperDominanceGraph(Direction.FORWARD));
			BWTL.add(new PaperDominanceGraph(Direction.BACKWARD));
			activeForwardByLastJob.add(new ArrayList<ForwardLabel>());
			minForwardReducedCostByLastJob[i] = Utility.big_M;
			minForwardEllByLastJob[i] = Utility.big_M;
		}
		generatedColumns = new ArrayList<TWETColumn>();
		generatedSignatures = new HashSet<SequenceSignature>();
		activeColumnSignatures = new HashSet<SequenceSignature>();
		attemptedJoinPairs = new HashSet<Long>();
		nextLabelId = 0;
		for (int columnId : lp.getRestrictedColumnIds()) {
			activeColumnSignatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		precomputeDynamicPricingWindows(lp);

		PackedBitSet sourceVisited = new PackedBitSet(data.n + 2);
		sourceVisited.add(0);
		PiecewiseLinearFunction sourceFrontier = cropToInterval(data.penaltyFunction[0].copy(), 0.0, tMid);
		sourceFrontier.shiftYInPlace(-lp.getMachineDual());
		sourceFrontier.normalize(Direction.FORWARD);
		ForwardLabel source = new ForwardLabel(nextLabelId++, 0, null, sourceVisited,
				buildForwardReachableSet(0, sourceVisited, lp.getNode(), sourceFrontier), sourceFrontier);
		if (!insertForward(source)) {
			FWUL.add(source);
		}
	}

	private void initializeBackwardSink(LP lp) {
		PackedBitSet sinkVisited = new PackedBitSet(data.n + 2);
		sinkVisited.add(lp.getNode().sinkId());
		PiecewiseLinearFunction sinkFrontier = new PiecewiseLinearFunction();
		// 2026-05-23: backward 虚拟终点本身也要带 [Tmid,CmaxH] 元数据。
		// 这样后续若发生 shiftX，trimToDomain 的边界和物理半域一致。
		sinkFrontier.resetDomain(tMid, data.CmaxH);
		sinkFrontier.addSegment(tMid, data.CmaxH, 0.0, 0.0);
		BackwardLabel sink = BackwardLabel.sink(nextLabelId++, lp.getNode().sinkId(), sinkVisited, sinkFrontier,
				buildBackwardReachableSet(lp.getNode().sinkId(), sinkVisited, lp.getNode(), sinkFrontier));
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
		for (int nextJob = label.reachableSet.nextSetBit(1); nextJob > 0 && nextJob <= data.n && canContinue();
				nextJob = label.reachableSet.nextSetBit(nextJob + 1)) {
			if (!canExtendForward(label, nextJob, node)) {
				continue;
			}
			ForwardLabel child = extendForward(label, nextJob, lp);
			if (child == null || Utility.isBigMValue(child.minReducedCost)) {
				continue;
			}
			if (!insertForward(child)) {
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
		for (int prevJob = label.reachableSet.nextSetBit(1); prevJob > 0 && prevJob <= data.n && canContinue();
				prevJob = label.reachableSet.nextSetBit(prevJob + 1)) {
			if (!canExtendBackward(label, prevJob, node)) {
				continue;
			}
			BackwardLabel child = extendBackward(label, prevJob, lp);
			if (child == null || Utility.isBigMValue(child.minReducedCost)) {
				continue;
			}
			if (!insertBackward(child)) {
				BWUL.add(child);
			}
		}
	}

	private boolean canExtendForward(ForwardLabel label, int nextJob, Node node) {
		if (label.visitedSet.contains(nextJob) || !label.reachableSet.contains(nextJob)) {
			return false;
		}
		return !node.isArcForbidden(label.jid, nextJob);
	}

	private boolean canExtendBackward(BackwardLabel label, int prevJob, Node node) {
		if (label.visitedSet.contains(prevJob) || !label.reachableSet.contains(prevJob)) {
			return false;
		}
		int successor = label.isSinkRoot ? node.sinkId() : label.jid;
		if (node.isArcForbidden(prevJob, successor)) {
			return false;
		}
		return true;
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
		return new ForwardLabel(nextLabelId++, nextJob, label, visited, reachable, nextFrontier);
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
		return new BackwardLabel(nextLabelId++, prevJob, label, visited, reachable, nextFrontier, false);
	}

	private boolean insertForward(ForwardLabel label) {
		boolean dominated = FWTL.get(label.jid).insertOrDominate(label);
		if (!dominated) {
			activeForwardByLastJob.get(label.jid).add(label);
			updateForwardScalarInfo(label);
		}
		return dominated;
	}

	private boolean insertBackward(BackwardLabel label) {
		return BWTL.get(label.jid).insertOrDominate(label);
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
		if (node.isArcForbidden(label.jid, sink)) {
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
		for (int lastJob = 0; lastJob <= data.n && canContinue(); lastJob++) {
			if (backward.visitedSet.contains(lastJob) || node.isArcForbidden(lastJob, backward.jid)) {
				continue;
			}
			ArrayList<ForwardLabel> candidates = activeForwardByLastJob.get(lastJob);
			if (candidates.isEmpty()) {
				continue;
			}
			double delay = data.getSetUp(lastJob, backward.jid) + data.getProcessT(backward.jid);
			if (Utility.compareGt(minForwardEllByLastJob[lastJob] + delay, backward.frontier.tail.end)) {
				continue;
			}
			double groupLB = minForwardReducedCostByLastJob[lastJob] + backward.minReducedCost
					+ data.getSetupCost(lastJob, backward.jid) - lp.getArcDual(lastJob, backward.jid);
			if (!Utility.compareLt(groupLB, REDUCED_COST_TOLERANCE)) {
				continue;
			}
			for (int i = 0; i < candidates.size() && canContinue(); i++) {
				ForwardLabel forward = candidates.get(i);
				if (!forward.isDominated) {
					tryJoin(forward, backward, lp);
				}
			}
		}
	}

	private void tryJoin(ForwardLabel forward, BackwardLabel backward, LP lp) {
		if (generatedColumns.size() >= config.maxExactPricingColumns) {
			return;
		}
		if (forward.jid == backward.jid || forward.visitedSet.intersects(backward.visitedSet)) {
			return;
		}
		// 2026-05-23: 同一对 forward/backward label 可能分别在两侧出队时各 join 一次。
		// 列签名去重发生在函数级拼接之后，太晚；这里先按 label 对去重，避免重复做常数延拓、
		// shift/add/findMinimal 等重操作。crossing arc 由两个 label 的末端任务唯一确定。
		long pairKey = (((long) forward.labelId) << 32) ^ (backward.labelId & 0xffffffffL);
		if (!attemptedJoinPairs.add(pairKey)) {
			return;
		}

		double joinFixedReducedCost = data.getSetupCost(forward.jid, backward.jid)
				- lp.getArcDual(forward.jid, backward.jid);
		// 2026-05-22: 先用 forward/backward frontier 的全局最小值做一次乐观下界。
		// join 的常数延拓和 crossing arc 对齐不会产生比两侧 frontier 全局最小值之和更低的下界，
		// 因此若这个标量下界都不能为负，就没必要再构造 join 函数。
		double optimisticJoinLB = forward.minReducedCost + backward.minReducedCost + joinFixedReducedCost;
		if (!Utility.compareLt(optimisticJoinLB, REDUCED_COST_TOLERANCE)) {
			return;
		}

		double delta = data.getSetUp(forward.jid, backward.jid) + data.getProcessT(backward.jid);
		double earliestBackwardCompletion = forward.frontier.head.start + delta;
		if (Utility.compareGt(earliestBackwardCompletion, backward.frontier.tail.end)) {
			return;
		}

		PiecewiseLinearFunction forwardFull = getForwardJoinExtension(forward);
		PiecewiseLinearFunction shiftedForward = forwardFull.shiftX(delta);
		if (shiftedForward.head == null) {
			return;
		}
		PiecewiseLinearFunction backwardFull = getBackwardJoinExtension(backward);
		if (backwardFull.head == null) {
			return;
		}
		PiecewiseLinearFunction joinCost = shiftedForward.add(backwardFull);
		if (joinCost.head == null) {
			return;
		}
		// 2026-05-22: crossing arc (i,r) 的固定 reduced-cost 项不仅有 setup cost，
		// 还必须扣掉该弧在 RMP 中的聚合 arc dual；否则 join 下界会偏高，极端时会漏掉真负列。
		joinCost.shiftYInPlace(joinFixedReducedCost);
		double reducedCostBound = joinCost.findMinimal(false, true)[0];
		if (!Utility.compareLt(reducedCostBound, REDUCED_COST_TOLERANCE)) {
			return;
		}

		ArrayList<Integer> sequence = recoverJoinSequence(forward, backward);
		tryGenerateColumn(sequence, lp, reducedCostBound);
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
		PiecewiseLinearFunction extended = new PiecewiseLinearFunction(0.0, data.CmaxH);
		appendSegments(extended, forward);
		if (forward != null && forward.tail != null && Utility.compareLt(forward.tail.end, data.CmaxH)) {
			addConstantSegmentOrPoint(extended, forward.tail.end, data.CmaxH, valueAtOrNearest(forward, tMid));
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
		PiecewiseLinearFunction extended = new PiecewiseLinearFunction(0.0, data.CmaxH);
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
		Node node = lp.getNode();
		if (Configure.debugBPCPricingColumnCheck && !isSequenceCompatible(sequence, node)) {
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
		if (frontier == null || frontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction jobPenalty = getDynamicForwardJobPenalty(prevJob, nextJob);
		if (jobPenalty == null) {
			return false;
		}
		double hEnd = getDynamicForwardHEnd(prevJob, nextJob);
		double earliestCompletion = frontier.head.start + data.getSetUp(prevJob, nextJob) + data.getProcessT(nextJob);
		return !Utility.compareGt(earliestCompletion, hEnd) && !Utility.compareGt(earliestCompletion, tMid);
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
		int successor = isSinkRoot ? data.n + 1 : firstJob;
		double rhoPrime;
		if (isSinkRoot) {
			rhoPrime = getDynamicBackwardHEnd(prevJob, successor);
		} else {
			double delay = data.getSetUp(prevJob, firstJob) + data.getProcessT(firstJob);
			rhoPrime = Math.min(frontier.tail.end - delay, getDynamicBackwardHEnd(prevJob, successor));
		}
		double lower = Math.max(tMid, getDynamicBackwardHStart(prevJob, successor));
		return !Utility.compareLt(rhoPrime, lower);
	}

	private PackedBitSet buildForwardReachableSet(int fromJob, PackedBitSet visited, Node node,
			PiecewiseLinearFunction frontier) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		for (int job = 1; job <= data.n; job++) {
			// 2026-05-24: dominance reachable-set 只放“永久不可达”信息。
			// forbidden arc 只禁止当前 direct arc，不代表该 job 后续不能通过其他前驱访问，
			// 因此不能进入 dominance key；实际扩展仍在 canExtendForward 中单独检查 forbidden arc。
			if (!visited.contains(job) && isDirectForwardExtensionTimeFeasible(frontier, fromJob, job)) {
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
			if (!visited.contains(job) && isDirectForwardExtensionTimeFeasible(frontier, fromJob, job)) {
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
			if (!visited.contains(job) && isDirectBackwardExtensionTimeFeasible(firstJob, isSinkRoot, frontier, job)) {
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
			if (!visited.contains(job) && isDirectBackwardExtensionTimeFeasible(firstJob, isSinkRoot, frontier, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private void precomputeDynamicPricingWindows(LP lp) {
		dynamicJobPenaltyByJob = null;
		dynamicJobHEnd = null;
		dynamicBackwardPenaltyByJob = null;
		dynamicBackwardHStartByJob = null;
		dynamicBackwardHEndByJob = null;
		precomputeJobLevelDynamicPricingWindows(lp);
		precomputeBackwardDynamicPricingWindows(lp);
	}

	private void precomputeJobLevelDynamicPricingWindows(LP lp) {
		dynamicJobPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicJobHEnd = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = hWindowStart(job, lp);
			double hEnd = hWindowEnd(job, lp);
			dynamicJobHEnd[job] = hEnd;
			if (!Utility.compareGt(hStart, hEnd)) {
				dynamicJobPenaltyByJob[job] = buildForwardHalfPenalty(job, hStart, hEnd);
			}
		}
	}

	private void precomputeBackwardDynamicPricingWindows(LP lp) {
		dynamicBackwardPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicBackwardHStartByJob = new double[data.n + 1];
		dynamicBackwardHEndByJob = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = hWindowStart(job, lp);
			double hEnd = hWindowEnd(job, lp);
			dynamicBackwardHStartByJob[job] = hStart;
			dynamicBackwardHEndByJob[job] = hEnd;
			if (!Utility.compareGt(hStart, hEnd)) {
				dynamicBackwardPenaltyByJob[job] = buildBackwardHalfPenalty(job, hStart, hEnd);
			}
		}
	}

	private PiecewiseLinearFunction buildForwardHalfPenalty(int job, double hStart, double hEnd) {
		// 2026-05-23: 半域边界直接写入动态 job penalty。
		// forward 的新增 job 函数只在 [0,Tmid] 上参与 add，公共定义域会自然把右端卡在 Tmid。
		return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), 0.0, tMid);
	}

	private PiecewiseLinearFunction buildBackwardHalfPenalty(int job, double hStart, double hEnd) {
		// 2026-05-23: backward 对称使用 [Tmid,CmaxH] 上的新增 job 函数。
		// 若窗口左侧为 big_M，后续 normalize(BACKWARD) 会通过 suffix-min 表达“可以等到窗口内完成”。
		return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), tMid, data.CmaxH);
	}

	private PiecewiseLinearFunction getDynamicForwardJobPenalty(int prevJob, int job) {
		return dynamicJobPenaltyByJob == null ? null : dynamicJobPenaltyByJob[job];
	}

	private double getDynamicForwardHEnd(int prevJob, int job) {
		return dynamicJobHEnd[job];
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

	private double hWindowStart(int job, LP lp) {
		double gamma = hWindowGamma(job, lp);
		if (!Utility.compareGt(data.w_e[job], 0.0)) {
			return 0.0;
		}
		return Math.max(0.0, data.d_e[job] - gamma / data.w_e[job]);
	}

	private double hWindowEnd(int job, LP lp) {
		double gamma = hWindowGamma(job, lp);
		if (!Utility.compareGt(data.w_t[job], 0.0)) {
			return data.CmaxH;
		}
		return Math.min(data.CmaxH, data.d_l[job] + gamma / data.w_t[job]);
	}

	private double hWindowGamma(int job, LP lp) {
		double baseline = Utility.isBigMValue(data.outsourcingCost[job]) ? Utility.big_M : data.outsourcingCost[job];
		double jobDual = Math.max(0.0, lp.getJobDual(job));
		return Math.min(jobDual, baseline);
	}

	private ArrayList<Integer> recoverForwardSequence(ForwardLabel label) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		ForwardLabel cursor = label;
		while (cursor != null && cursor.jid != 0) {
			sequence.add(0, Integer.valueOf(cursor.jid));
			cursor = cursor.father;
		}
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

	private abstract static class FunctionLabel extends Label implements Comparable<Label> {
		final int labelId;
		/** join 阶段临时常数延拓后的函数缓存；label frontier 创建后不再修改，可以安全复用。 */
		PiecewiseLinearFunction joinExtendedFrontier;

		FunctionLabel(int labelId, int jid, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PiecewiseLinearFunction frontier) {
			super(jid, null, visitedSet, reachableSet, frontier,
					frontier == null || frontier.head == null ? Utility.big_M : frontier.findMinimal(false, true)[0]);
			this.labelId = labelId;
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
				PiecewiseLinearFunction frontier) {
			super(labelId, jid, visitedSet, reachableSet, frontier);
			this.father = father;
		}
	}

	private static final class BackwardLabel extends FunctionLabel {
		final BackwardLabel father;
		final boolean isSinkRoot;

		BackwardLabel(int labelId, int jid, BackwardLabel father, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PiecewiseLinearFunction frontier, boolean isSinkRoot) {
			super(labelId, jid, visitedSet, reachableSet, frontier);
			this.father = father;
			this.isSinkRoot = isSinkRoot;
		}

		static BackwardLabel sink(int labelId, int sinkId, PackedBitSet visitedSet, PiecewiseLinearFunction frontier,
				PackedBitSet reachableSet) {
			return new BackwardLabel(labelId, sinkId, null, visitedSet, reachableSet, frontier, true);
		}
	}
}
