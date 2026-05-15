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
		int processedNodes = 0;

		while (!queue.isEmpty() && processedNodes < config.maxNodes) {
			Node node = queue.poll();
			node.id = ++processedNodes;
			traceSink.onNodePicked(node, queue.size(), pool.size(), cutPool.size());

			LP lp = new LP(data, pool, cutPool);
			lp.construct(node, node.seedColumnIds);
			TWETMasterSolution solution = pc.solve(lp);

			if (solution.getStatus() != TWETMasterStatus.NOT_SOLVED) {
				bestBound = Math.min(bestBound, solution.getObjectiveValue());
			}

			boolean incumbentUpdated = false;
			if (solution.isInteger() && Utility.compareLt(solution.getObjectiveValue(), incumbentCost)) {
				incumbentCost = solution.getObjectiveValue();
				incumbentColumnIds = new ArrayList<Integer>(solution.getActiveColumnIds());
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
				if (result.getLeftNode() != null) {
					queue.add(result.getLeftNode());
				}
				if (result.getRightNode() != null) {
					queue.add(result.getRightNode());
				}
				traceSink.onBranch(node, brancher.getName(), result, queue.size());
				branched = true;
				break;
			}
			if (!branched) {
				traceSink.onNodeClosed(node, "closed_without_branch", queue.size());
			}
		}

		TWETSolveStatus status = processedNodes <= 1 ? TWETSolveStatus.ROOT_PROCESSED : TWETSolveStatus.FINISHED;
		return new TWETSolveResult(status, incumbentCost, bestBound, processedNodes, pool.size(), incumbentColumnIds,
				"TWET BPC scaffold built; exact master/pricing/cuts remain to be completed");
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

}
