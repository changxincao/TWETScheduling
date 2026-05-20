package TWETBPC.GC;

import java.util.ArrayList;
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
 */
public class GC {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;
	private final boolean usePaperDominanceGraph;
	private PriorityQueue<Label> UL;
	private ArrayList<DominanceStore> TL;
	private ArrayList<TWETColumn> generatedColumns;
	private HashSet<SequenceSignature> generatedSignatures;
	private HashSet<SequenceSignature> activeColumnSignatures;
	// 2026-05-20: 当前 RMP dual 下的局部 H_ij 硬窗缓存，只在一次 pricing 调用内有效。
	private boolean useJobLevelDynamicWindowCache;
	private double[] dynamicJobHStart;
	private double[] dynamicJobHEnd;
	private PiecewiseLinearFunction[] dynamicJobPenaltyByJob;
	private double[][] dynamicPairHStart;
	private double[][] dynamicPairHEnd;
	private PiecewiseLinearFunction[][] dynamicJobPenaltyByPair;

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
			for (int nextJob = 1; nextJob <= data.n; nextJob++) {
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
		return generatedColumns;
	}

	private void initialize(LP lp) {
		UL = new PriorityQueue<Label>();
		TL = new ArrayList<DominanceStore>(data.n + 1);
		for (int i = 0; i <= data.n; i++) {
			TL.add(usePaperDominanceGraph ? new PaperDominanceGraph() : new DominanceGraph());
		}
		generatedColumns = new ArrayList<TWETColumn>();
		generatedSignatures = new HashSet<SequenceSignature>();
		activeColumnSignatures = new HashSet<SequenceSignature>();
		for (int columnId : lp.getRestrictedColumnIds()) {
			activeColumnSignatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		precomputeDynamicPricingWindows(lp);

		PackedBitSet visited = new PackedBitSet(data.n + 2);
		visited.add(0);
		PiecewiseLinearFunction frontier = data.penaltyFunction[0].copy();
		// 每条内部机器列在 RMP 的机器数约束中系数为 1，因此初始化时扣除该约束 dual。
		frontier.shiftYInPlace(-lp.getMachineDual());
		frontier.normalize(Direction.FORWARD);
		Label source = new Label(0, null, visited, buildReachableSet(0, visited, lp.getNode(), frontier, lp), frontier);
		TL.get(0).insertOrDominate(source);
		UL.add(source);
	}

	private boolean canExtend(Label label, int nextJob, Node node) {
		if (label.visitedSet.contains(nextJob) || !label.reachableSet.contains(nextJob)) {
			return false;
		}
		return !node.isArcForbidden(label.jid, nextJob);
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
		// 2026-05-17: pricing 里使用动态 H_ij 时，只把窗口外改成 big_M，不物理删除右端定义域。
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
		return new Label(nextJob, label, visited, buildReachableSet(nextJob, visited, lp.getNode(), nextFrontier, lp),
				nextFrontier);
	}

	/**
	 * 2026-05-17: pricing 的轻量级时间可行性过滤。
	 * <p>
	 * 当前 label 的 frontier 左端表示当前末端 job 最早可能完成到的时间上界。若再接 nextJob 后的
	 * 最早完成时间已经超过动态 H_ij 的右端，则这条扩展不可能产生有效函数，没必要再做
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
		double hEnd = getDynamicHEnd(prevJob, nextJob);
		double earliestCompletion = frontier.head.start + data.getSetUp(prevJob, nextJob) + data.getProcessT(nextJob);
		return !Utility.compareGt(earliestCompletion, hEnd) && !Utility.compareGt(earliestCompletion, data.CmaxH);
	}

	/**
	 * 2026-05-20: 每轮 pricing 的 dual 固定，因此 H_ij 也固定在本次 pricing 调用内。
	 * <p>
	 * 旧写法在每个 label 扩展时重复计算 H_ij，并重复对 job penalty 执行 setDomain(...)。
	 * 如果数据的 setup cost 满足当前 B_ij 公式对应的三角不等式，所有 B_ij 都为 0，H_ij
	 * 不再依赖 prevJob，可以退化成 job 级一维缓存；否则仍使用 prevJob->job 的 pair 级缓存。
	 * 这个缓存只在本次 pricing 内使用，不能写入全局禁弧矩阵，也不能影响分支候选。
	 */
	private void precomputeDynamicPricingWindows(LP lp) {
		useJobLevelDynamicWindowCache = data.setupCostTriangleInequalitySatisfied;
		dynamicJobHStart = null;
		dynamicJobHEnd = null;
		dynamicJobPenaltyByJob = null;
		dynamicPairHStart = null;
		dynamicPairHEnd = null;
		dynamicJobPenaltyByPair = null;
		if (useJobLevelDynamicWindowCache) {
			precomputeJobLevelDynamicPricingWindows(lp);
		} else {
			precomputePairLevelDynamicPricingWindows(lp);
		}
	}

	private void precomputeJobLevelDynamicPricingWindows(LP lp) {
		dynamicJobHStart = new double[data.n + 1];
		dynamicJobHEnd = new double[data.n + 1];
		dynamicJobPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			// B_ij 全为 0 时 hWindowGamma 不依赖 prevJob，这里用 source 作为代表前驱即可。
			double hStart = hWindowStart(0, job, lp);
			double hEnd = hWindowEnd(0, job, lp);
			dynamicJobHStart[job] = hStart;
			dynamicJobHEnd[job] = hEnd;
			if (!Utility.compareGt(hStart, hEnd)) {
				dynamicJobPenaltyByJob[job] = data.penaltyFunction[job].setDomain(hStart, hEnd, true);
			}
		}
	}

	private void precomputePairLevelDynamicPricingWindows(LP lp) {
		dynamicPairHStart = new double[data.n + 1][data.n + 1];
		dynamicPairHEnd = new double[data.n + 1][data.n + 1];
		dynamicJobPenaltyByPair = new PiecewiseLinearFunction[data.n + 1][data.n + 1];
		for (int prevJob = 0; prevJob <= data.n; prevJob++) {
			for (int job = 1; job <= data.n; job++) {
				if (prevJob == job) {
					continue;
				}
				double hStart = hWindowStart(prevJob, job, lp);
				double hEnd = hWindowEnd(prevJob, job, lp);
				dynamicPairHStart[prevJob][job] = hStart;
				dynamicPairHEnd[prevJob][job] = hEnd;
				if (!Utility.compareGt(hStart, hEnd)) {
					dynamicJobPenaltyByPair[prevJob][job] = data.penaltyFunction[job].setDomain(hStart, hEnd, true);
				}
			}
		}
	}

	private PiecewiseLinearFunction getDynamicJobPenalty(int prevJob, int job) {
		if (useJobLevelDynamicWindowCache) {
			return dynamicJobPenaltyByJob == null ? null : dynamicJobPenaltyByJob[job];
		}
		return dynamicJobPenaltyByPair == null ? null : dynamicJobPenaltyByPair[prevJob][job];
	}

	private double getDynamicHEnd(int prevJob, int job) {
		if (useJobLevelDynamicWindowCache) {
			return dynamicJobHEnd[job];
		}
		return dynamicPairHEnd[prevJob][job];
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

	private PackedBitSet buildReachableSet(int fromJob, PackedBitSet visited, Node node, PiecewiseLinearFunction frontier,
			LP lp) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		for (int job = 1; job <= data.n; job++) {
			if (!visited.contains(job) && !node.isArcForbidden(fromJob, job)
					&& isDirectExtensionTimeFeasible(frontier, fromJob, job)) {
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
		if (node.isArcForbidden(label.jid, sink)) {
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
		double cost = evaluator.evaluate(sequence);
		generatedColumns.add(new TWETColumn(-1, sequence, data.n, cost, ColumnSource.PRICING_EXACT, false));
	}

	private ArrayList<Integer> recoverSequence(Label label) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		Label cursor = label;
		while (cursor != null && cursor.jid != 0) {
			sequence.add(0, Integer.valueOf(cursor.jid));
			cursor = cursor.father;
		}
		return sequence;
	}

}
