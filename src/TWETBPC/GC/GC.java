package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
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
 * TWET 的单向 forward labeling 定价器。
 * <p>
 * 结构上尽量贴近旧 VRP 代码的 GC：UL 是待扩展队列，TL 按末端 job 存 label。
 * 当前版本把 TL[j] 升级为 dominance graph；每个 graph node 保存真实 label 集合和聚合 envelope，
 * 用于实现完整 set dominance。暂不做 partial dominance、SRI、ng-route 和双向拼接。
 * <p>
 * 2026-06-01: 本单向版本达到 {@link TWETBPCConfig#maxExactPricingColumns} 后直接停止，
 * 返回的是当前队列/扫描顺序下最先发现的 K 条负列，不是枚举后按 reduced cost 选出的最优 K 条。
 * 这和旧 VRP 基础单向 GC 一致，但可能让外层列生成迭代次数增加；当前性能主路径仍优先使用
 * 带候选堆的双向 GCBB-style pricing。
 * <p>
 * 2026-06-01: completion bound 后续优先维护在 Tmid 双向和 full-domain 诊断分支；
 * 单向 forward pricing 预计不再作为性能主路径，暂不在本类接入 completion-bound 预剪枝。
 */
public class GC {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;

	private enum LabelQueueOrdering {
		REDUCED_COST, TIME, REACHABLE_SIZE
	}

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;
	private final boolean usePaperDominanceGraph;
	private PriorityQueue<Label> UL;
	private ArrayList<DominanceStore> TL;
	private ArrayList<TWETColumn> generatedColumns;
	private HashSet<SequenceSignature> generatedSignatures;
	private HashSet<SequenceSignature> activeColumnSignatures;
	private boolean[] zeroDualExcludedJobs;
	private int zeroDualExcludedJobCount;
	private LabelQueueOrdering queueOrdering;
	private String lastMessage = "Exact forward labeling not executed";
	// 2026-05-20: 当前 RMP dual 下的 job-level H_j 硬窗缓存，只在一次 pricing 调用内有效。
	private double[] dynamicJobHStart;
	private double[] dynamicJobHEnd;
	private PiecewiseLinearFunction[] dynamicJobPenaltyByJob;
	// 2026-05-24: 根节点无 cut 时才允许用 pi_j 动态 profitable window。
	private boolean dualProfitableWindowEnabled;

	public GC(Data data, TWETBPCConfig config) {
		this(data, config, false);
	}

	public GC(Data data, TWETBPCConfig config, boolean usePaperDominanceGraph) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
		this.usePaperDominanceGraph = usePaperDominanceGraph;
	}

	public ArrayList<TWETColumn> solve(LP lp) {
		Utility.resetCurUpperBound(Utility.big_M);
		initialize(lp);
		Node node = lp.getNode();
		while (!UL.isEmpty() && generatedColumns.size() < config.maxExactPricingColumns) {
			Label label = UL.poll();
			if (label.isDominated) {
				continue;
			}
			tryGenerateColumn(label, lp);
			for (int nextJob = label.reachableSet.nextSetBit(1); nextJob > 0 && nextJob <= data.n;
					nextJob = label.reachableSet.nextSetBit(nextJob + 1)) {
				if (!canExtend(label, nextJob, node)) {
					continue;
				}
				Label child = extend(label, nextJob, lp);
				if (child == null || Utility.isBigMValue(child.minReducedCost)) {
					continue;
				}
				if (!TL.get(nextJob).insertOrDominate(child)) {
					UL.add(child);
				}
			}
		}
		lastMessage = "Exact forward labeling generated " + generatedColumns.size() + " columns; dualWindow="
				+ (dualProfitableWindowEnabled ? "enabled" : "staticOutsourcingOnly")
				+ ", queueOrdering=" + queueOrdering
				+ ", zeroDualExcludedJobs=" + zeroDualExcludedJobCount
				+ (usePaperDominanceGraph ? ", " + PaperDominanceGraphs.statisticsSummary() : "");
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
	 * 2026-05-26: 单向 GC 也提供 label 出队策略，便于和双向 pricing 的队列实验保持一致。
	 */
	private Comparator<Label> queueComparator(LabelQueueOrdering ordering) {
		return new Comparator<Label>() {
			@Override
			public int compare(Label left, Label right) {
				if (ordering == LabelQueueOrdering.TIME) {
					int byTime = compareDoubleAsc(earliestCompletion(left), earliestCompletion(right));
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

	private static int compareReducedCost(Label left, Label right) {
		int byCost = compareDoubleAsc(left.minReducedCost, right.minReducedCost);
		if (byCost != 0) {
			return byCost;
		}
		return Integer.compare(left.jid, right.jid);
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

	private static double earliestCompletion(Label label) {
		return label.frontier == null || label.frontier.head == null ? Utility.big_M : label.frontier.head.start;
	}

	private void initialize(LP lp) {
		if (usePaperDominanceGraph) {
			PaperDominanceGraphs.resetStatistics();
		}
		queueOrdering = parseQueueOrdering(config.forwardLabelQueueOrdering);
		UL = new PriorityQueue<Label>(queueComparator(queueOrdering));
		TL = new ArrayList<DominanceStore>(data.n + 1);
		for (int i = 0; i <= data.n; i++) {
			TL.add(usePaperDominanceGraph ? PaperDominanceGraphs.create() : new DominanceGraph());
		}
		generatedColumns = new ArrayList<TWETColumn>();
		generatedSignatures = new HashSet<SequenceSignature>();
		activeColumnSignatures = new HashSet<SequenceSignature>();
		// 只跳过当前 RMP 已 active 的列。全局 Pool.addColumn() 会按 signature 去重；
		// 历史列若当前不 active，仍可由 pricing 返回并交给 PC 重新激活。
		for (int columnId : lp.getRestrictedColumnIds()) {
			activeColumnSignatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		precomputeDynamicPricingWindows(lp);

		PackedBitSet visited = new PackedBitSet(data.n + 2);
		visited.add(0);
		addZeroDualExcludedJobs(visited);
		PiecewiseLinearFunction frontier = data.penaltyFunction[0].copy();
		// 每条内部机器列在 RMP 的机器数约束中系数为 1，因此初始化时扣除该约束 dual。
		frontier.shiftYInPlace(-lp.getMachineDual());
		frontier.normalize(Direction.FORWARD);
		Label source = new Label(0, null, visited, buildReachableSet(0, visited, lp.getNode(), frontier, lp), frontier,
				forwardEndpointMin(frontier));
		TL.get(0).insertOrDominate(source);
		UL.add(source);
	}

	private boolean canExtend(Label label, int nextJob, Node node) {
		// 2026-05-29: 调用方只枚举 label.reachableSet；visited
		// 和时间可行性已经在 reachable set 构造时维护。下面旧检查保留为防御性说明，
		// 正常不应触发；实际会随节点变化、必须即时检查的是直连禁弧。
		// if (label.visitedSet.contains(nextJob) || !label.reachableSet.contains(nextJob)) {
		// 	return false;
		// }
		return !PricingCompatibility.isRequiredOutsourcedJob(node, nextJob)
				&& !isPricingArcForbidden(node, label.jid, nextJob);
	}

	private boolean isPricingArcForbidden(Node node, int fromJob, int toJob) {
		return node.isArcForbidden(fromJob, toJob)
				|| (!ignorePricingOnlyArcsForNode(node) && node.isPricingOnlyArcForbidden(fromJob, toJob));
	}

	private boolean ignorePricingOnlyArcsForNode(Node node) {
		return node != null && config.debugIgnorePricingOnlyArcsAtNode >= 0
				&& node.id == config.debugIgnorePricingOnlyArcsAtNode;
	}

	private Label extend(Label label, int nextJob, LP lp) {
		if (!isDirectExtensionTimeFeasible(label.frontier, label.jid, nextJob)) {
			return null;
		}
		double delay = data.getSetUp(label.jid, nextJob) + data.getProcessT(nextJob);
		PiecewiseLinearFunction shifted = label.frontier.shiftX(delay);
		if (shifted.head == null) {
			return null;
		}

		PiecewiseLinearFunction jobPenalty = getDynamicJobPenalty(label.jid, nextJob);
		if (jobPenalty == null) {
			return null;
		}
		// 2026-05-17: pricing 里使用动态 H_j 时，只把窗口外改成 big_M，不物理删除右端定义域。
		// 这样仍保持前向函数 [a,T] 结构，后续 add/merge/dominance 的假设不被破坏。
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
		return new Label(nextJob, label, visited,
				buildReachableSetFromParent(label, nextJob, visited, lp.getNode(), nextFrontier, lp), nextFrontier,
				forwardEndpointMin(nextFrontier));
	}

	/**
	 * 2026-05-17: pricing 的轻量级时间可行性过滤。
	 * <p>
	 * 当前 label 的 frontier 左端表示当前末端 job 最早可能完成到的时间上界。若再接 nextJob 后的
	 * 最早完成时间已经超过动态 H_j 的右端，则这条扩展不可能产生有效函数，没必要再做
	 * shift/add/normalize 这些较重的分段函数操作。
	 */
	private boolean isDirectExtensionTimeFeasible(PiecewiseLinearFunction frontier, int prevJob, int nextJob) {
		if (frontier == null || frontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction jobPenalty = getDynamicJobPenalty(prevJob, nextJob);
		if (jobPenalty == null) {
			return false;
		}
		double hStart = getDynamicHStart(prevJob, nextJob);
		double hEnd = getDynamicHEnd(prevJob, nextJob);
		double earliestCompletion = Math.max(
				frontier.head.start + data.getSetUp(prevJob, nextJob) + data.getProcessT(nextJob), hStart);
		return !Utility.compareGt(earliestCompletion, hEnd) && !Utility.compareGt(earliestCompletion, data.CmaxH);
	}

	/**
	 * 2026-05-20: 每轮 pricing 的 dual 固定，因此 H_j 也固定在本次 pricing 调用内。
	 * <p>
	 * 旧写法在每个 label 扩展时重复计算 H_ij，并重复对 job penalty 执行 setDomain(...)。
	 * 当前模型直接假设 setup time/cost 满足三角不等式，且 B_ij=0，因此 profitable window
	 * 退化为 job-level H_j；这里不再保留 pair-level H_ij 分支。
	 * 这个缓存只在本次 pricing 内使用，不能写入全局禁弧矩阵，也不能影响分支候选。
	 */
	private void precomputeDynamicPricingWindows(LP lp) {
		dynamicJobHStart = null;
		dynamicJobHEnd = null;
		dynamicJobPenaltyByJob = null;
		zeroDualExcludedJobs = null;
		zeroDualExcludedJobCount = 0;
		dualProfitableWindowEnabled = canUseDualProfitableWindow(lp);
		precomputeZeroDualExcludedJobs(lp);
		dynamicJobHStart = new double[data.n + 1];
		dynamicJobHEnd = new double[data.n + 1];
		dynamicJobPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			PiecewiseLinearFunction penalty = data.penaltyFunction[job];
			double baseline = outsourcingBaseline(job);
			double jobDual = dualProfitableWindowEnabled ? Math.max(0.0, lp.getJobDual(job)) : baseline;
			if (dualProfitableWindowEnabled && Utility.compareLt(jobDual, baseline)) {
				double dynamicStart = hWindowStart(job, jobDual);
				double dynamicEnd = hWindowEnd(job, jobDual);
				if (Utility.compareGt(dynamicStart, data.hardWindowStart[job])
						|| Utility.compareLt(dynamicEnd, data.hardWindowEnd[job])) {
					hStart = dynamicStart;
					hEnd = dynamicEnd;
					penalty = Utility.compareGt(hStart, hEnd) ? null : data.penaltyFunction[job].setDomain(hStart, hEnd, true);
				}
			}
			dynamicJobHStart[job] = hStart;
			dynamicJobHEnd[job] = hEnd;
			dynamicJobPenaltyByJob[job] = penalty;
		}
	}

	private boolean canUseDualProfitableWindow(LP lp) {
		Node node = lp.getNode();
		if (node == null || node.depth != 0) {
			return false;
		}
		// 非根节点可能有 forbidden arc，root 加 cut 后也可能产生 arc-level dual。
		// 这两类情形都不能保证 reduced arc cost 仍满足原始三角不等式，只保留 b_j 静态窗。
		return lp.getActiveCutIds().isEmpty();
	}

	/**
	 * 2026-05-29: 根节点 no-cut pricing 中，pi_j=0 的任务整轮不进入 pricing 扩展。
	 * 初始化 source visited 时会直接标记这些 job，reachable set 后续只按 visited 过滤。
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

	private PiecewiseLinearFunction getDynamicJobPenalty(int prevJob, int job) {
		return dynamicJobPenaltyByJob == null ? null : dynamicJobPenaltyByJob[job];
	}

	private double getDynamicHEnd(int prevJob, int job) {
		return dynamicJobHEnd[job];
	}

	private double getDynamicHStart(int prevJob, int job) {
		return dynamicJobHStart[job];
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

	private PackedBitSet buildReachableSet(int fromJob, PackedBitSet visited, Node node, PiecewiseLinearFunction frontier,
			LP lp) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		for (int job = 1; job <= data.n; job++) {
			if (!visited.contains(job) && isDirectExtensionTimeFeasible(frontier, fromJob, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private PackedBitSet buildReachableSetFromParent(Label parent, int fromJob, PackedBitSet visited, Node node,
			PiecewiseLinearFunction frontier, LP lp) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		// 2026-05-24: 在 setup time/cost 三角不等式下，父 label 中已经一跳不可达的 job，
		// 继续追加真实 job 后不会重新变成可达。child reachable set 因此只需从父候选集中过滤。
		for (int job = parent.reachableSet.nextSetBit(1); job > 0 && job <= data.n;
				job = parent.reachableSet.nextSetBit(job + 1)) {
			if (!visited.contains(job) && isDirectExtensionTimeFeasible(frontier, fromJob, job)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private void tryGenerateColumn(Label label, LP lp) {
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

		ArrayList<Integer> sequence = recoverSequence(label);
		SequenceSignature signature = new SequenceSignature(sequence);
		// 2026-05-18: 旧 VRP GC 在计入 addin_size 前会先查全局 route pool。
		// 这里对应地先跳过当前 RMP 已经 active 的同序列列，避免重复列消耗本轮返回列上限。
		// 如果该序列只存在于全局 pool、但尚未 active，则仍返回给 PC，由 PC 负责激活已有列。
		if (activeColumnSignatures.contains(signature)) {
			return;
		}
		if (!generatedSignatures.add(signature)) {
			return;
		}
		// 2026-06-01: 单向 GC 在线发现负列后立即计入返回集合，满 K 即停；
		// 这里不维护 top-K 候选堆，因此不是“所有已见负列中最好的 K 条”语义。
		double cost = evaluator.evaluate(sequence);
		generatedColumns.add(new TWETColumn(-1, sequence, data.n, cost, ColumnSource.PRICING_EXACT, false));
	}

	private ArrayList<Integer> recoverSequence(Label label) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		Label cursor = label;
		while (cursor != null && cursor.jid != 0) {
			sequence.add(Integer.valueOf(cursor.jid));
			cursor = cursor.father;
		}
		reverseInPlace(sequence);
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
	 * 2026-05-24: exact forward pricing 的 frontier 在 normalize(FORWARD) 后整体非增，
	 * 最小 reduced cost 直接位于最右端。
	 */
	private double forwardEndpointMin(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.tail == null) {
			return Utility.big_M;
		}
		return frontier.tail.getValue(frontier.tail.end);
	}

}
