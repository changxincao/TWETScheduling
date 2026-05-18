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
		return node.getArcState(label.jid, nextJob) != Node.ARC_FORBIDDEN;
	}

	private Label extend(Label label, int nextJob, LP lp) {
		if (!isDirectExtensionTimeFeasible(label.frontier, label.jid, nextJob, lp)) {
			return null;
		}
		double delay = data.getSetUp(label.jid, nextJob) + data.getProcessT(nextJob);
		PiecewiseLinearFunction shifted = label.frontier.shiftX(delay);
		if (shifted.head == null) {
			return null;
		}

		double hStart = hWindowStart(label.jid, nextJob, lp);
		double hEnd = hWindowEnd(label.jid, nextJob, lp);
		if (Utility.compareGt(hStart, hEnd)) {
			return null;
		}
		// 2026-05-17: pricing 里使用动态 H_ij 时，只把窗口外改成 big_M，不物理删除右端定义域。
		// 这样仍保持前向函数 [a,T] 结构，后续 add/merge/dominance 的假设不被破坏。
		PiecewiseLinearFunction jobPenalty = data.penaltyFunction[nextJob].setDomain(hStart, hEnd, true);
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
	private boolean isDirectExtensionTimeFeasible(PiecewiseLinearFunction frontier, int prevJob, int nextJob, LP lp) {
		if (frontier == null || frontier.head == null) {
			return false;
		}
		double hStart = hWindowStart(prevJob, nextJob, lp);
		double hEnd = hWindowEnd(prevJob, nextJob, lp);
		if (Utility.compareGt(hStart, hEnd)) {
			return false;
		}
		double earliestCompletion = frontier.head.start + data.getSetUp(prevJob, nextJob) + data.getProcessT(nextJob);
		return !Utility.compareGt(earliestCompletion, hEnd) && !Utility.compareGt(earliestCompletion, data.CmaxH);
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
			if (!visited.contains(job) && node.getArcState(fromJob, job) != Node.ARC_FORBIDDEN
					&& isDirectExtensionTimeFeasible(frontier, fromJob, job, lp)) {
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
		if (node.getArcState(label.jid, sink) == Node.ARC_FORBIDDEN) {
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
