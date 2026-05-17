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
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.ColumnSource;
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
	private final TWETColumnEvaluator columnEvaluator;
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
		this.columnEvaluator = new TWETColumnEvaluator(data);
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
				enqueueChild(queue, result.getLeftNode());
				enqueueChild(queue, result.getRightNode());
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
				incumbentOutsourcingValues,
				"TWET BPC solved with LP RMP and forward exact pricing; advanced cuts/pricing remain pending");
	}

	private void enqueueChild(PriorityQueue<Node> queue, Node child) {
		if (child == null) {
			return;
		}
		prepareChildSeedColumns(child);
		queue.add(child);
	}

	private void prepareChildSeedColumns(Node child) {
		ArrayList<Integer> seed = new ArrayList<Integer>();
		for (TWETColumn column : pool.getColumns()) {
			if (child.isColumnCompatible(column)) {
				seed.add(Integer.valueOf(column.getId()));
			}
		}
		for (int[] arc : child.getRequiredArcs()) {
			if (!hasColumnCoveringArc(seed, child, arc[0], arc[1])) {
				addRequiredArcFallbackColumn(seed, child, arc[0], arc[1]);
			}
		}
		child.seedColumnIds = seed;
	}

	private boolean hasColumnCoveringArc(ArrayList<Integer> seed, Node child, int from, int to) {
		for (int columnId : seed) {
			if (child.columnCoversRequiredArc(pool.getColumn(columnId), from, to)) {
				return true;
			}
		}
		return false;
	}

	private void addRequiredArcFallbackColumn(ArrayList<Integer> seed, Node child, int requiredFrom, int requiredTo) {
		ArrayList<Integer> sequence = buildRequiredArcChain(child, requiredFrom, requiredTo);
		if (sequence.isEmpty() || !isSequenceCompatible(child, sequence)) {
			return;
		}
		double cost = columnEvaluator.evaluate(sequence);
		if (Utility.isBigMValue(cost)) {
			return;
		}
		int id = pool.addColumn(sequence, cost, ColumnSource.MANUAL, false);
		Integer value = Integer.valueOf(id);
		if (!seed.contains(value)) {
			seed.add(value);
		}
	}

	private ArrayList<Integer> buildRequiredArcChain(Node child, int requiredFrom, int requiredTo) {
		int sink = child.sinkId();
		int[] succ = new int[sink + 1];
		int[] pred = new int[sink + 1];
		for (int i = 0; i < succ.length; i++) {
			succ[i] = -1;
			pred[i] = -1;
		}
		for (int[] arc : child.getRequiredArcs()) {
			int from = arc[0];
			int to = arc[1];
			if (from >= succ.length || to >= pred.length) {
				continue;
			}
			if (succ[from] != -1 && succ[from] != to) {
				return new ArrayList<Integer>();
			}
			if (to != sink && pred[to] != -1 && pred[to] != from) {
				return new ArrayList<Integer>();
			}
			succ[from] = to;
			if (to != sink) {
				pred[to] = from;
			}
		}

		int start = requiredFrom;
		while (start != 0 && pred[start] != -1) {
			start = pred[start];
		}
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		boolean[] seen = new boolean[sink + 1];
		int cursor = start;
		if (cursor != 0 && cursor != sink) {
			sequence.add(Integer.valueOf(cursor));
			seen[cursor] = true;
		}
		while (cursor >= 0 && cursor < succ.length && succ[cursor] != -1) {
			int next = succ[cursor];
			if (next == sink) {
				break;
			}
			if (seen[next]) {
				return new ArrayList<Integer>();
			}
			sequence.add(Integer.valueOf(next));
			seen[next] = true;
			cursor = next;
		}
		return sequence;
	}

	private boolean isSequenceCompatible(Node child, ArrayList<Integer> sequence) {
		if (sequence.isEmpty()) {
			return false;
		}
		if (child.getArcState(0, sequence.get(0).intValue()) == Node.ARC_FORBIDDEN) {
			return false;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (child.getArcState(sequence.get(i - 1).intValue(), sequence.get(i).intValue()) == Node.ARC_FORBIDDEN) {
				return false;
			}
		}
		return child.getArcState(sequence.get(sequence.size() - 1).intValue(), child.sinkId()) != Node.ARC_FORBIDDEN;
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

}
