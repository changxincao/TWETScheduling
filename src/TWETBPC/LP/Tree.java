package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import TWETBPC.Model.TWETColumn;
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

			LP lp = new LP(data, pool, cutPool);
			lp.construct(node, node.seedColumnIds);
			TWETMasterSolution solution = pc.solve(lp);

			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				traceSink.onNodeClosed(node, "infeasible_master", queue.size());
				continue;
			}

			if (solution.getStatus() == TWETMasterStatus.LP_RELAXATION) {
				bestBound = Math.min(bestBound, solution.getObjectiveValue());
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

		TWETSolveStatus status = finalStatus(processedNodes, queue.isEmpty());
		return new TWETSolveResult(status, incumbentCost, bestBound, processedNodes, pool.size(), incumbentColumnIds,
				incumbentOutsourcingValues,
				"TWET BPC solved with LP RMP and forward exact pricing; advanced cuts/pricing remain pending");
	}

	private TWETSolveStatus finalStatus(int processedNodes, boolean queueEmpty) {
		if (processedNodes == 0) {
			return TWETSolveStatus.INITIALIZED;
		}
		// 2026-05-18: 旧实现只按 processedNodes 判断 FINISHED/ROOT_PROCESSED，
		// 在达到 maxNodes 且队列仍有未处理节点时会误报完成。这里显式区分节点上限停止。
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
		ArrayList<SeedCandidate> candidates = new ArrayList<SeedCandidate>();
		if (parentLp != null) {
			for (int columnId : parentLp.getRestrictedColumnIds()) {
				TWETColumn column = pool.getColumn(columnId);
				if (!child.isColumnCompatible(column)) {
					continue;
				}
				candidates.add(new SeedCandidate(columnId, parentLp.getColumnReducedCost(columnId)));
			}
		}
		Collections.sort(candidates, new Comparator<SeedCandidate>() {
			@Override
			public int compare(SeedCandidate a, SeedCandidate b) {
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return 1;
				}
				return Integer.compare(a.columnId, b.columnId);
			}
		});

		ArrayList<Integer> seed = new ArrayList<Integer>();
		for (SeedCandidate candidate : candidates) {
			if (seed.size() >= config.branchSeedColumnLimit) {
				break;
			}
			if (Utility.compareGt(candidate.reducedCost, config.branchSeedReducedCostAllowance)) {
				continue;
			}
			seed.add(Integer.valueOf(candidate.columnId));
		}
		// 2026-05-18: 这里对齐旧 VRP UpdateRouteSet 的“子节点先继承父节点低 reduced-cost 列”。
		// 如果这些列不足以让 RMP 可行，不再在 Tree 里暴力拼 fallback 序列，而是交给 PC 的 slack RMP + FindFeasible 修复。
		child.seedColumnIds = seed;
	}

	private double incumbentCostFromInitial(InitialColumnBundle initial) {
		if (data.configure.bestSolution != null) {
			return data.configure.bestSolution.curCost;
		}
		double cost = 0.0;
		for (int columnId : initial.getIncumbentColumnIds()) {
			cost += pool.getColumn(columnId).getCost();
		}
		return cost;
	}

	private double[] initialOutsourcingValues(InitialColumnBundle initial) {
		double[] values = new double[data.n + 1];
		if (initial.getSeedSolution() == null) {
			return values;
		}
		for (int job : initial.getSeedSolution().getOutsourcedJobsCopy()) {
			if (job >= 1 && job <= data.n) {
				values[job] = 1.0;
			}
		}
		return values;
	}

	private static final class SeedCandidate {
		final int columnId;
		final double reducedCost;

		SeedCandidate(int columnId, double reducedCost) {
			this.columnId = columnId;
			this.reducedCost = reducedCost;
		}
	}

}
