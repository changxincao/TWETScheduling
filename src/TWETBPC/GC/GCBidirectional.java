package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.PriorityQueue;

import Basic.Data;
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
 * 3. join 时用论文里的常数延拓，只在 join 这一步把 backward 函数投影到 forward 的时间轴；
 * 4. 最终列仍统一调用 {@link TWETColumnEvaluator} 复核真实成本，避免双向实现细节把错误 reduced-cost 列加进 RMP。
 * <p>
 * 2026-05-22: backward 侧暂时没有接入和 forward 完全对称的动态 H^b_{ir} 收缩，
 * 只使用已经写入 job penalty 的静态粗硬时间窗。这会弱一些，但不会破坏正确性；
 * 后续若继续加强，只需要在 backward extend 前把 jobPenalty 换成 pair 级窗口版本即可。
 */
public class GCBidirectional {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;

	private PriorityQueue<ForwardLabel> FWUL;
	private PriorityQueue<BackwardLabel> BWUL;
	private ArrayList<ArrayList<ForwardLabel>> FWTL;
	private ArrayList<ArrayList<BackwardLabel>> BWTL;
	private ArrayList<TWETColumn> generatedColumns;
	private HashSet<SequenceSignature> generatedSignatures;
	private HashSet<SequenceSignature> activeColumnSignatures;

	// 2026-05-22: 双向 midpoint，只对当前 pricing 轮有效。
	private double tMid;
	// 2026-05-22: forward 侧继续复用当前单向 exact 的动态 H_ij 缓存。
	private boolean useJobLevelDynamicWindowCache;
	private PiecewiseLinearFunction[] dynamicJobPenaltyByJob;
	private PiecewiseLinearFunction[][] dynamicJobPenaltyByPair;
	private double[] dynamicJobHEnd;
	private double[][] dynamicPairHEnd;
	private PiecewiseLinearFunction[] dynamicBackwardPenaltyToSinkByJob;
	private PiecewiseLinearFunction[][] dynamicBackwardPenaltyByPair;
	private double[] dynamicBackwardHStartToSinkByJob;
	private double[] dynamicBackwardHEndToSinkByJob;
	private double[][] dynamicBackwardHStartByPair;
	private double[][] dynamicBackwardHEndByPair;

	private String lastMessage = "Bidirectional pricing not executed";

	public GCBidirectional(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	public ArrayList<TWETColumn> solve(LP lp) {
		Utility.resetCurUpperBound(Utility.big_M);
		initialize(lp);
		while (canContinue() && (!FWUL.isEmpty() || !BWUL.isEmpty())) {
			if (!FWUL.isEmpty()) {
				forwardExtend(lp);
			}
			if (canContinue() && !BWUL.isEmpty()) {
				backwardExtend(lp);
			}
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
		FWTL = new ArrayList<ArrayList<ForwardLabel>>(data.n + 1);
		BWTL = new ArrayList<ArrayList<BackwardLabel>>(data.n + 1);
		for (int i = 0; i <= data.n; i++) {
			FWTL.add(new ArrayList<ForwardLabel>());
			BWTL.add(new ArrayList<BackwardLabel>());
		}
		generatedColumns = new ArrayList<TWETColumn>();
		generatedSignatures = new HashSet<SequenceSignature>();
		activeColumnSignatures = new HashSet<SequenceSignature>();
		for (int columnId : lp.getRestrictedColumnIds()) {
			activeColumnSignatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		precomputeDynamicPricingWindows(lp);

		PackedBitSet sourceVisited = new PackedBitSet(data.n + 2);
		sourceVisited.add(0);
		PiecewiseLinearFunction sourceFrontier = cropToInterval(data.penaltyFunction[0].copy(), 0.0, tMid);
		sourceFrontier.shiftYInPlace(-lp.getMachineDual());
		sourceFrontier.normalize(Direction.FORWARD);
		ForwardLabel source = new ForwardLabel(0, null, sourceVisited,
				buildForwardReachableSet(0, sourceVisited, lp.getNode(), sourceFrontier), sourceFrontier);
		FWTL.get(0).add(source);
		FWUL.add(source);

		PackedBitSet sinkVisited = new PackedBitSet(data.n + 2);
		sinkVisited.add(lp.getNode().sinkId());
		PiecewiseLinearFunction sinkFrontier = new PiecewiseLinearFunction();
		// 2026-05-23: backward 虚拟终点本身也要带 [Tmid,CmaxH] 元数据。
		// 这样后续若发生 shiftX，trimToDomain 的边界和物理半域一致。
		sinkFrontier.resetDomain(tMid, data.CmaxH);
		sinkFrontier.addSegment(tMid, data.CmaxH, 0.0, 0.0);
		BackwardLabel sink = BackwardLabel.sink(lp.getNode().sinkId(), sinkVisited, sinkFrontier,
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
		joinFromForward(label, lp);

		Node node = lp.getNode();
		for (int nextJob = 1; nextJob <= data.n && canContinue(); nextJob++) {
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
		for (int prevJob = 1; prevJob <= data.n && canContinue(); prevJob++) {
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
		return isDirectBackwardExtensionTimeFeasible(label, prevJob);
	}

	private ForwardLabel extendForward(ForwardLabel label, int nextJob, LP lp) {
		if (!isDirectForwardExtensionTimeFeasible(label.frontier, label.jid, nextJob)) {
			return null;
		}
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
		PackedBitSet reachable = buildForwardReachableSet(nextJob, visited, lp.getNode(), nextFrontier);
		return new ForwardLabel(nextJob, label, visited, reachable, nextFrontier);
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
		PackedBitSet reachable = buildBackwardReachableSet(prevJob, visited, lp.getNode(), nextFrontier);
		return new BackwardLabel(prevJob, label, visited, reachable, nextFrontier, false);
	}

	private boolean insertForward(ForwardLabel label) {
		ArrayList<ForwardLabel> table = FWTL.get(label.jid);
		for (int i = 0; i < table.size(); i++) {
			ForwardLabel existing = table.get(i);
			if (dominatesForward(existing, label)) {
				label.isDominated = true;
				return true;
			}
			if (dominatesForward(label, existing)) {
				existing.isDominated = true;
				table.remove(i);
				i--;
			}
		}
		table.add(label);
		return false;
	}

	private boolean insertBackward(BackwardLabel label) {
		ArrayList<BackwardLabel> table = BWTL.get(label.jid);
		for (int i = 0; i < table.size(); i++) {
			BackwardLabel existing = table.get(i);
			if (dominatesBackward(existing, label)) {
				label.isDominated = true;
				return true;
			}
			if (dominatesBackward(label, existing)) {
				existing.isDominated = true;
				table.remove(i);
				i--;
			}
		}
		table.add(label);
		return false;
	}

	private boolean dominatesForward(ForwardLabel a, ForwardLabel b) {
		return a.reachableSet.isSupersetOf(b.reachableSet) && a.frontier.dominates(b.frontier);
	}

	private boolean dominatesBackward(BackwardLabel a, BackwardLabel b) {
		return a.reachableSet.isSupersetOf(b.reachableSet) && a.frontier.dominates(b.frontier);
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
		tryGenerateColumn(sequence, lp);
	}

	private void joinFromForward(ForwardLabel forward, LP lp) {
		if (generatedColumns.size() >= config.maxExactPricingColumns) {
			return;
		}
		Node node = lp.getNode();
		for (int firstJob = forward.reachableSet.nextSetBit(1); firstJob >= 0 && canContinue();
				firstJob = forward.reachableSet.nextSetBit(firstJob + 1)) {
			ArrayList<BackwardLabel> table = BWTL.get(firstJob);
			for (int i = 0; i < table.size() && canContinue(); i++) {
				BackwardLabel backward = table.get(i);
				if (!backward.isDominated) {
					tryJoin(forward, backward, lp);
				}
			}
		}
	}

	private void joinFromBackward(BackwardLabel backward, LP lp) {
		if (generatedColumns.size() >= config.maxExactPricingColumns) {
			return;
		}
		Node node = lp.getNode();
		if (!node.isArcForbidden(0, backward.jid)) {
			ArrayList<ForwardLabel> sourceTable = FWTL.get(0);
			for (int i = 0; i < sourceTable.size() && canContinue(); i++) {
				ForwardLabel forward = sourceTable.get(i);
				if (!forward.isDominated) {
					tryJoin(forward, backward, lp);
				}
			}
		}
		for (int lastJob = backward.reachableSet.nextSetBit(1); lastJob >= 0 && canContinue();
				lastJob = backward.reachableSet.nextSetBit(lastJob + 1)) {
			ArrayList<ForwardLabel> table = FWTL.get(lastJob);
			for (int i = 0; i < table.size() && canContinue(); i++) {
				ForwardLabel forward = table.get(i);
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

		double joinFixedReducedCost = data.getSetupCost(forward.jid, backward.jid)
				- lp.getArcDual(forward.jid, backward.jid);
		// 2026-05-22: 先用 forward/backward frontier 的全局最小值做一次乐观下界。
		// cropToInterval 和 backward projection 只会进一步收紧定义域或把取值抬高，
		// 因此若这个标量下界都不能为负，就没必要再构造 join 函数。
		double optimisticJoinLB = forward.minReducedCost + backward.minReducedCost + joinFixedReducedCost;
		if (!Utility.compareLt(optimisticJoinLB, REDUCED_COST_TOLERANCE)) {
			return;
		}

		double delta = data.getSetUp(forward.jid, backward.jid) + data.getProcessT(backward.jid);
		double joinUpper = Math.min(tMid, backward.frontier.tail.end - delta);
		double joinLower = forward.frontier.head.start;
		if (Utility.compareGt(joinLower, joinUpper)) {
			return;
		}

		PiecewiseLinearFunction forwardPart = cropToInterval(forward.frontier, joinLower, joinUpper);
		if (forwardPart.head == null) {
			return;
		}
		PiecewiseLinearFunction backwardProjection = buildJoinBackwardProjection(backward.frontier, delta, joinLower,
				joinUpper);
		if (backwardProjection == null || backwardProjection.head == null) {
			return;
		}
		PiecewiseLinearFunction joinCost = forwardPart.add(backwardProjection);
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
		tryGenerateColumn(sequence, lp);
	}

	/**
	 * 2026-05-22: 论文里的 join 使用
	 * f_b(max(Tmid, x + Delta))。
	 * 这里把 backward 函数投影回 forward 时间轴：
	 * 1. x < Tmid-Delta 时取常数 f_b(Tmid)；
	 * 2. x >= Tmid-Delta 时取 shift 后的 f_b(x+Delta)。
	 * 这个投影只在 join 阶段临时构造，不会写回 backward label。
	 */
	private PiecewiseLinearFunction buildJoinBackwardProjection(PiecewiseLinearFunction backward, double delta,
			double xStart, double xEnd) {
		if (backward == null || backward.head == null || Utility.compareGt(xStart, xEnd)) {
			return null;
		}
		PiecewiseLinearFunction projection = new PiecewiseLinearFunction();
		projection.resetDomain(xStart, xEnd);
		double split = tMid - delta;
		if (Utility.compareEq(xStart, xEnd)) {
			if (!Utility.compareGt(xStart, split)) {
				addConstantSegmentOrPoint(projection, xStart, xEnd, backward.evaluate(tMid));
			} else {
				PiecewiseLinearFunction shifted = shiftBackwardForJoinProjection(backward, delta);
				appendSegments(projection, cropToInterval(shifted, xStart, xEnd));
			}
			mergeAdjacentEqualSegments(projection);
			return projection;
		}
		double constantEnd = Math.min(xEnd, split);
		if (Utility.compareLt(xStart, constantEnd)) {
			addConstantSegmentOrPoint(projection, xStart, constantEnd, backward.evaluate(tMid));
		}

		double shiftedStart = Math.max(xStart, split);
		if (!Utility.compareGt(shiftedStart, xEnd)) {
			PiecewiseLinearFunction shifted = shiftBackwardForJoinProjection(backward, delta);
			PiecewiseLinearFunction shiftedPart = cropToInterval(shifted, shiftedStart, xEnd);
			appendSegments(projection, shiftedPart);
		}
		mergeAdjacentEqualSegments(projection);
		return projection;
	}

	/**
	 * 2026-05-23: join 投影需要的是 f_b(x + delta)，定义域也应随之左移。
	 * 不能直接调用 backward.shiftX(-delta)，因为 shiftX 会按 backward 原来的
	 * [Tmid,CmaxH] 元数据 trim，误删 x<Tmid 但满足 x+delta>=Tmid 的合法投影段。
	 */
	private PiecewiseLinearFunction shiftBackwardForJoinProjection(PiecewiseLinearFunction backward, double delta) {
		PiecewiseLinearFunction shifted = backward.copy();
		shifted.resetDomain(backward.domainStart - delta, backward.domainEnd - delta);
		for (Segment seg = shifted.head; seg != null; seg = seg.next) {
			seg.start -= delta;
			seg.end -= delta;
			seg.intercept += seg.slope * delta;
		}
		return shifted;
	}

	private void tryGenerateColumn(ArrayList<Integer> sequence, LP lp) {
		if (sequence.isEmpty() || generatedColumns.size() >= config.maxExactPricingColumns) {
			return;
		}
		Node node = lp.getNode();
		if (!isSequenceCompatible(sequence, node)) {
			return;
		}
		SequenceSignature signature = new SequenceSignature(sequence);
		if (activeColumnSignatures.contains(signature) || !generatedSignatures.add(signature)) {
			return;
		}
		double cost = evaluator.evaluate(sequence);
		if (Utility.isBigMValue(cost)) {
			return;
		}
		double reducedCost = reducedCost(sequence, cost, lp);
		if (Utility.compareLt(reducedCost, REDUCED_COST_TOLERANCE)) {
			generatedColumns.add(new TWETColumn(-1, sequence, data.n, cost, ColumnSource.PRICING_EXACT, false));
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
		int successor = label.isSinkRoot ? data.n + 1 : label.jid;
		double rhoPrime;
		if (label.isSinkRoot) {
			rhoPrime = getDynamicBackwardHEnd(prevJob, successor);
		} else {
			double delay = data.getSetUp(prevJob, label.jid) + data.getProcessT(label.jid);
			rhoPrime = Math.min(label.frontier.tail.end - delay, getDynamicBackwardHEnd(prevJob, successor));
		}
		double lower = Math.max(tMid, getDynamicBackwardHStart(prevJob, successor));
		return !Utility.compareLt(rhoPrime, lower);
	}

	private PackedBitSet buildForwardReachableSet(int fromJob, PackedBitSet visited, Node node,
			PiecewiseLinearFunction frontier) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		for (int job = 1; job <= data.n; job++) {
			if (!visited.contains(job) && !node.isArcForbidden(fromJob, job)
					&& isDirectForwardExtensionTimeFeasible(frontier, fromJob, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private PackedBitSet buildBackwardReachableSet(int firstJob, PackedBitSet visited, Node node,
			PiecewiseLinearFunction frontier) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		BackwardLabel fake = new BackwardLabel(firstJob, null, visited, new PackedBitSet(data.n + 2), frontier,
				firstJob == node.sinkId());
		for (int job = 1; job <= data.n; job++) {
			int successor = fake.isSinkRoot ? node.sinkId() : firstJob;
			if (!visited.contains(job) && !node.isArcForbidden(job, successor)
					&& isDirectBackwardExtensionTimeFeasible(fake, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private void precomputeDynamicPricingWindows(LP lp) {
		useJobLevelDynamicWindowCache = data.setupCostTriangleInequalitySatisfied;
		dynamicJobPenaltyByJob = null;
		dynamicJobPenaltyByPair = null;
		dynamicJobHEnd = null;
		dynamicPairHEnd = null;
		dynamicBackwardPenaltyToSinkByJob = null;
		dynamicBackwardPenaltyByPair = null;
		dynamicBackwardHStartToSinkByJob = null;
		dynamicBackwardHEndToSinkByJob = null;
		dynamicBackwardHStartByPair = null;
		dynamicBackwardHEndByPair = null;
		if (useJobLevelDynamicWindowCache) {
			precomputeJobLevelDynamicPricingWindows(lp);
		} else {
			precomputePairLevelDynamicPricingWindows(lp);
		}
		precomputeBackwardDynamicPricingWindows(lp);
	}

	private void precomputeJobLevelDynamicPricingWindows(LP lp) {
		dynamicJobPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicJobHEnd = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = hWindowStart(0, job, lp);
			double hEnd = hWindowEnd(0, job, lp);
			dynamicJobHEnd[job] = hEnd;
			if (!Utility.compareGt(hStart, hEnd)) {
				dynamicJobPenaltyByJob[job] = buildForwardHalfPenalty(job, hStart, hEnd);
			}
		}
	}

	private void precomputePairLevelDynamicPricingWindows(LP lp) {
		dynamicJobPenaltyByPair = new PiecewiseLinearFunction[data.n + 1][data.n + 1];
		dynamicPairHEnd = new double[data.n + 1][data.n + 1];
		for (int prevJob = 0; prevJob <= data.n; prevJob++) {
			for (int job = 1; job <= data.n; job++) {
				if (prevJob == job) {
					continue;
				}
				double hStart = hWindowStart(prevJob, job, lp);
				double hEnd = hWindowEnd(prevJob, job, lp);
				dynamicPairHEnd[prevJob][job] = hEnd;
				if (!Utility.compareGt(hStart, hEnd)) {
					dynamicJobPenaltyByPair[prevJob][job] = buildForwardHalfPenalty(job, hStart, hEnd);
				}
			}
		}
	}

	private void precomputeBackwardDynamicPricingWindows(LP lp) {
		dynamicBackwardPenaltyToSinkByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicBackwardHStartToSinkByJob = new double[data.n + 1];
		dynamicBackwardHEndToSinkByJob = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = backwardHWindowStart(job, data.n + 1, lp);
			double hEnd = backwardHWindowEnd(job, data.n + 1, lp);
			dynamicBackwardHStartToSinkByJob[job] = hStart;
			dynamicBackwardHEndToSinkByJob[job] = hEnd;
			if (!Utility.compareGt(hStart, hEnd)) {
				dynamicBackwardPenaltyToSinkByJob[job] = buildBackwardHalfPenalty(job, hStart, hEnd);
			}
		}
		if (useJobLevelDynamicWindowCache) {
			dynamicBackwardPenaltyByPair = null;
			dynamicBackwardHStartByPair = null;
			dynamicBackwardHEndByPair = null;
			return;
		}
		dynamicBackwardPenaltyByPair = new PiecewiseLinearFunction[data.n + 1][data.n + 1];
		dynamicBackwardHStartByPair = new double[data.n + 1][data.n + 1];
		dynamicBackwardHEndByPair = new double[data.n + 1][data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			for (int successor = 1; successor <= data.n; successor++) {
				if (job == successor) {
					continue;
				}
				double hStart = backwardHWindowStart(job, successor, lp);
				double hEnd = backwardHWindowEnd(job, successor, lp);
				dynamicBackwardHStartByPair[job][successor] = hStart;
				dynamicBackwardHEndByPair[job][successor] = hEnd;
				if (!Utility.compareGt(hStart, hEnd)) {
					dynamicBackwardPenaltyByPair[job][successor] = buildBackwardHalfPenalty(job, hStart, hEnd);
				}
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
		if (useJobLevelDynamicWindowCache) {
			return dynamicJobPenaltyByJob == null ? null : dynamicJobPenaltyByJob[job];
		}
		return dynamicJobPenaltyByPair == null ? null : dynamicJobPenaltyByPair[prevJob][job];
	}

	private double getDynamicForwardHEnd(int prevJob, int job) {
		if (useJobLevelDynamicWindowCache) {
			return dynamicJobHEnd[job];
		}
		return dynamicPairHEnd[prevJob][job];
	}

	private PiecewiseLinearFunction getDynamicBackwardJobPenalty(int job, int successor) {
		if (successor == data.n + 1) {
			return dynamicBackwardPenaltyToSinkByJob == null ? null : dynamicBackwardPenaltyToSinkByJob[job];
		}
		if (useJobLevelDynamicWindowCache) {
			return dynamicBackwardPenaltyToSinkByJob == null ? null : dynamicBackwardPenaltyToSinkByJob[job];
		}
		return dynamicBackwardPenaltyByPair == null ? null : dynamicBackwardPenaltyByPair[job][successor];
	}

	private double getDynamicBackwardHStart(int job, int successor) {
		if (successor == data.n + 1 || useJobLevelDynamicWindowCache) {
			return dynamicBackwardHStartToSinkByJob[job];
		}
		return dynamicBackwardHStartByPair[job][successor];
	}

	private double getDynamicBackwardHEnd(int job, int successor) {
		if (successor == data.n + 1 || useJobLevelDynamicWindowCache) {
			return dynamicBackwardHEndToSinkByJob[job];
		}
		return dynamicBackwardHEndByPair[job][successor];
	}

	private double hWindowStart(int prevJob, int job, LP lp) {
		double gamma = hWindowGamma(prevJob, job, lp);
		if (!Utility.compareGt(data.w_e[job], 0.0)) {
			return 0.0;
		}
		return Math.max(0.0, data.d_e[job] - gamma / data.w_e[job]);
	}

	private double hWindowEnd(int prevJob, int job, LP lp) {
		double gamma = hWindowGamma(prevJob, job, lp);
		if (!Utility.compareGt(data.w_t[job], 0.0)) {
			return data.CmaxH;
		}
		return Math.min(data.CmaxH, data.d_l[job] + gamma / data.w_t[job]);
	}

	private double hWindowGamma(int prevJob, int job, LP lp) {
		double baseline = Utility.isBigMValue(data.outsourcingCost[job]) ? Utility.big_M : data.outsourcingCost[job];
		return data.getSetupCostAdvantage(prevJob, job) + Math.min(lp.getJobDual(job), baseline);
	}

	private double backwardHWindowStart(int job, int successor, LP lp) {
		double gamma = backwardHWindowGamma(job, successor, lp);
		if (!Utility.compareGt(data.w_e[job], 0.0)) {
			return 0.0;
		}
		return Math.max(0.0, data.d_e[job] - gamma / data.w_e[job]);
	}

	private double backwardHWindowEnd(int job, int successor, LP lp) {
		double gamma = backwardHWindowGamma(job, successor, lp);
		if (!Utility.compareGt(data.w_t[job], 0.0)) {
			return data.CmaxH;
		}
		return Math.min(data.CmaxH, data.d_l[job] + gamma / data.w_t[job]);
	}

	private double backwardHWindowGamma(int job, int successor, LP lp) {
		double baseline = Utility.isBigMValue(data.outsourcingCost[job]) ? Utility.big_M : data.outsourcingCost[job];
		if (successor == data.n + 1 || useJobLevelDynamicWindowCache) {
			return Math.min(lp.getJobDual(job), baseline);
		}
		double bBackward = data.getBackwardSetupCostAdvantage(job, successor);
		return bBackward + Math.min(lp.getJobDual(job), baseline);
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

	private abstract static class FunctionLabel implements Comparable<FunctionLabel> {
		final int jid;
		final PackedBitSet visitedSet;
		final PackedBitSet reachableSet;
		final PiecewiseLinearFunction frontier;
		double minReducedCost;
		boolean isDominated;

		FunctionLabel(int jid, PackedBitSet visitedSet, PackedBitSet reachableSet, PiecewiseLinearFunction frontier) {
			this.jid = jid;
			this.visitedSet = visitedSet;
			this.reachableSet = reachableSet;
			this.frontier = frontier;
			this.minReducedCost = frontier == null || frontier.head == null ? Utility.big_M
					: frontier.findMinimal(false, true)[0];
			this.isDominated = false;
		}

		@Override
		public int compareTo(FunctionLabel other) {
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

		ForwardLabel(int jid, ForwardLabel father, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PiecewiseLinearFunction frontier) {
			super(jid, visitedSet, reachableSet, frontier);
			this.father = father;
		}
	}

	private static final class BackwardLabel extends FunctionLabel {
		final BackwardLabel father;
		final boolean isSinkRoot;

		BackwardLabel(int jid, BackwardLabel father, PackedBitSet visitedSet, PackedBitSet reachableSet,
				PiecewiseLinearFunction frontier, boolean isSinkRoot) {
			super(jid, visitedSet, reachableSet, frontier);
			this.father = father;
			this.isSinkRoot = isSinkRoot;
		}

		static BackwardLabel sink(int sinkId, PackedBitSet visitedSet, PiecewiseLinearFunction frontier,
				PackedBitSet reachableSet) {
			return new BackwardLabel(sinkId, null, visitedSet, reachableSet, frontier, true);
		}
	}
}
