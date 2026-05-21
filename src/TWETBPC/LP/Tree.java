package TWETBPC.LP;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

import Basic.Data;
import Common.Utility;
import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETSolveResult;
import TWETBPC.TWETSolveStatus;
import TWETBPC.BP.BranchResult;
import TWETBPC.BP.Brancher;
import TWETBPC.GC.InitialColumnBuilder;
import TWETBPC.GC.InitialColumnBundle;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/**
 * 分支树主控制器。
 */
public class Tree {

	private final Data data;
	private final TWETBPCConfig config;
	private final Pool pool;
	private final CutPool cutPool;
	private final InitialColumnBuilder initialColumnBuilder;
	private final PC pc;
	private final List<Brancher> branchers;
	private final BPCTraceSink traceSink;

	public Tree(Data data, TWETBPCConfig config, Pool pool, CutPool cutPool, InitialColumnBuilder initialColumnBuilder,
			PC pc, List<Brancher> branchers, BPCTraceSink traceSink) {
		this.data = data;
		this.config = config;
		this.pool = pool;
		this.cutPool = cutPool;
		this.initialColumnBuilder = initialColumnBuilder;
		this.pc = pc;
		this.branchers = branchers;
		this.traceSink = traceSink;
	}

	public TWETSolveResult solve() {
		InitialColumnBundle initial = initialColumnBuilder.build();
		Node root = new Node(data, initial.getInitialColumnIds(), initial.getIncumbentColumnIds(), config.pseudoCostInf);
		traceSink.onInitialColumnsReady(initial.getInitialColumnIds().size(), initial.getIncumbentColumnIds().size(),
				incumbentCostFromInitial(initial));

		PriorityQueue<Node> queue = new PriorityQueue<Node>();
		queue.add(root);

		double incumbentCost = data.configure.bestSolution == null ? Double.POSITIVE_INFINITY
				: data.configure.bestSolution.curCost;
		double bestBound = Double.POSITIVE_INFINITY;
		ArrayList<Integer> incumbentColumnIds = new ArrayList<Integer>(initial.getIncumbentColumnIds());
		double[] incumbentOutsourcingValues = initialOutsourcingValues(initial);
		int processedNodes = 0;

		while (!queue.isEmpty() && processedNodes < config.maxNodes) {
			Node node = queue.poll();
			node.id = ++processedNodes;
			traceSink.onNodePicked(node, queue.size(), pool.size(), cutPool.size());

			// 2026-05-19: 对齐旧 VRP 的 sudo_cost 预剪枝。root 的 pseudoCost 是占位大数，不能用来剪；
			// 非根节点若 pseudoCost 已不小于 incumbent，由于队列按 pseudoCost 升序，当前节点和剩余节点
			// 都不可能改进，继续构建 RMP / pricing 只会浪费时间，极端情况下还会触发标签/函数包络内存膨胀。
			if (node.depth > 0 && Double.isFinite(incumbentCost)
					&& Utility.compareGe(node.pseudoCost, incumbentCost - config.branchingTolerance)) {
				traceSink.onNodeClosed(node, "pruned_by_pseudo_cost", 0);
				queue.clear();
				break;
			}

			LP lp = new LP(data, pool, cutPool);
			lp.construct(node, node.seedColumnIds);
			TWETMasterSolution solution = pc.solve(lp);

			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				traceSink.onNodeClosed(node, "infeasible_master", queue.size());
				continue;
			}

			if (solution.getStatus() == TWETMasterStatus.LP_RELAXATION) {
				bestBound = updateReportedBound(queue, solution.getObjectiveValue(), incumbentCost);
			}

			boolean incumbentUpdated = false;
			if (solution.isInteger() && Utility.compareLt(solution.getObjectiveValue(), incumbentCost)) {
				incumbentCost = solution.getObjectiveValue();
				incumbentColumnIds = new ArrayList<Integer>(solution.getActiveColumnIds());
				incumbentOutsourcingValues = solution.getOutsourcingValues();
				incumbentUpdated = true;
				traceSink.onIncumbentUpdated(node, solution, incumbentCost);
			}

			traceSink.onMasterSolved(node, solution, lp.getRestrictedColumnIds().size(), lp.getActiveCutIds().size(),
					bestBound, incumbentCost, queue.size(), pool.size(), cutPool.size(), incumbentUpdated);

			if (incumbentUpdated) {
				traceSink.onNodeClosed(node, "integer_incumbent", queue.size());
				continue;
			}
			if (Double.isFinite(incumbentCost) && solution.getStatus() != TWETMasterStatus.NOT_SOLVED
					&& Utility.compareGe(solution.getObjectiveValue(), incumbentCost - config.branchingTolerance)) {
				traceSink.onNodeClosed(node, "pruned_by_incumbent", queue.size());
				continue;
			}

			boolean branched = false;
			for (Brancher brancher : branchers) {
				BranchResult result = brancher.branch(lp);
				if (!result.isBranched()) {
					traceSink.onBranchRejected(node, brancher.getName(), result.getMessage());
					continue;
				}
				enqueueChild(queue, result.getLeftNode(), lp);
				enqueueChild(queue, result.getRightNode(), lp);
				traceSink.onBranch(node, brancher.getName(), result, queue.size());
				branched = true;
				break;
			}
			if (!branched) {
				traceSink.onNodeClosed(node, "closed_without_branch", queue.size());
			}
		}

		bestBound = finalBound(queue, incumbentCost, bestBound);
		TWETSolveStatus status = finalStatus(processedNodes, queue.isEmpty());
		return new TWETSolveResult(status, incumbentCost, bestBound, processedNodes, pool.size(), incumbentColumnIds,
				incumbentOutsourcingValues,
				"TWET BPC solved with LP RMP and forward exact pricing; advanced cuts/pricing remain pending");
	}

	private double updateReportedBound(PriorityQueue<Node> queue, double currentNodeBound, double incumbentCost) {
		// 2026-05-19: 参考旧 VRP 的 sudo_cost 语义，报告当前节点 bound 与 open queue 中最小伪下界的较小值。
		// 旧实现最后用 queue.peek().sudo_cost 修正 lower bound；这里在求解过程中也用同一语义更新输出。
		double bound = currentNodeBound;
		if (!queue.isEmpty()) {
			bound = Math.min(bound, queue.peek().pseudoCost);
		}
		if (Double.isFinite(incumbentCost) && Utility.compareGt(bound, incumbentCost)) {
			return incumbentCost;
		}
		return bound;
	}

	private double finalBound(PriorityQueue<Node> queue, double incumbentCost, double lastReportedBound) {
		// 2026-05-19: 如果队列为空，所有节点已经关闭，最终 LB 应等于 incumbent；
		// 如果达到节点上限仍有 open node，则用 open queue 中最小伪下界作为当前全局 LB。
		if (queue.isEmpty()) {
			return incumbentCost;
		}
		double bound = queue.peek().pseudoCost;
		if (Double.isFinite(incumbentCost) && Utility.compareGt(bound, incumbentCost)) {
			return incumbentCost;
		}
		if (Double.isFinite(bound)) {
			return bound;
		}
		return lastReportedBound;
	}

	private TWETSolveStatus finalStatus(int processedNodes, boolean queueEmpty) {
		if (processedNodes == 0) {
			return TWETSolveStatus.INITIALIZED;
		}
		// 2026-05-18: 显式区分达到 maxNodes 后队列仍非空的情况，避免把节点上限停止误报为完成。
		if (!queueEmpty && processedNodes >= config.maxNodes) {
			return TWETSolveStatus.NODE_LIMIT;
		}
		if (processedNodes == 1) {
			return TWETSolveStatus.ROOT_PROCESSED;
		}
		return TWETSolveStatus.FINISHED;
	}

	private void enqueueChild(PriorityQueue<Node> queue, Node child, LP parentLp) {
		if (child == null) {
			return;
		}
		prepareChildSeedColumns(child, parentLp);
		queue.add(child);
	}

	private void prepareChildSeedColumns(Node child, LP parentLp) {
		ArrayList<Integer> seed = new ArrayList<Integer>();
		if (parentLp != null) {
			seed.addAll(parentLp.getRestrictedColumnIds());
		}
		// 2026-05-18: 对齐旧 VRP UpdateRouteSet 的时机。child 入队时先继承父节点当前列集，
		// 不提前按新分支状态或 reduced cost 过滤；等 child 出队后，RMP 带新分支行先求一次 LP。
		// 若可行或通过 slack repair 修复成功，再在 LP.resetRestrictedColumnsByCurrentReducedCost()
		// 里筛成正式子节点列集。这样保留“出队时处理”的实现方式，但逻辑上等价于旧代码。
		child.seedColumnIds = seed;
	}

	private double incumbentCostFromInitial(InitialColumnBundle initial) {
		if (data.configure.bestSolution != null) {
			return data.configure.bestSolution.curCost;
		}
		double cost = initial.getIncumbentOutsourcingCost();
		for (int columnId : initial.getIncumbentColumnIds()) {
			cost += pool.getColumn(columnId).getCost();
		}
		return cost;
	}

	private double[] initialOutsourcingValues(InitialColumnBundle initial) {
		double[] values = new double[data.n + 1];
		for (int job : initial.getIncumbentOutsourcedJobs()) {
			if (job >= 1 && job <= data.n) {
				values[job] = 1.0;
			}
		}
		return values;
	}

}
