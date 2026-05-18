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
		addMissingCoverageFallbackColumns(seed, child);
		for (int[] arc : child.getRequiredArcs()) {
			if (!hasColumnCoveringArc(seed, child, arc[0], arc[1])) {
				addRequiredArcFallbackColumn(seed, child, arc[0], arc[1]);
			}
		}
		child.seedColumnIds = seed;
	}

	private void addMissingCoverageFallbackColumns(ArrayList<Integer> seed, Node child) {
		for (int job = 1; job <= data.n; job++) {
			if (!Utility.isBigMValue(data.outsourcingCost[job]) || hasColumnCoveringJob(seed, job)) {
				continue;
			}
			addJobFallbackColumn(seed, child, job);
		}
	}

	private boolean hasColumnCoveringJob(ArrayList<Integer> seed, int job) {
		for (int columnId : seed) {
			if (pool.getColumn(columnId).containsJob(job)) {
				return true;
			}
		}
		return false;
	}

	private void addJobFallbackColumn(ArrayList<Integer> seed, Node child, int requiredJob) {
		ArrayList<Integer> sequence = findJobFallbackSequence(child, requiredJob);
		if (sequence == null || sequence.isEmpty()) {
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

	private ArrayList<Integer> findJobFallbackSequence(Node child, int requiredJob) {
		ArrayList<Integer> bestSequence = null;
		double bestCost = Double.POSITIVE_INFINITY;
		ArrayList<Integer> singleton = new ArrayList<Integer>();
		singleton.add(Integer.valueOf(requiredJob));
		double cost = evaluateFallbackCandidate(child, singleton, -1, -1);
		if (Utility.compareLt(cost, bestCost)) {
			bestCost = cost;
			bestSequence = singleton;
		}

		// 2026-05-18: 这里对应旧 VRP UpdateRouteSet/FindFeasible 的“子节点缺列时主动找可行列”。
		// 当前还没有引入 slack RMP + 定向 pricing，因此先用轻量兜底：把缺覆盖的 job 插入已有兼容列的不同位置。
		// 这只在该 job 不能外包且 seed 中没有任何可兼容覆盖列时触发，不改变正常节点的列池结构。
		for (TWETColumn baseColumn : pool.getColumns()) {
			List<Integer> base = baseColumn.getSequence();
			if (base.contains(Integer.valueOf(requiredJob))) {
				continue;
			}
			for (int pos = 0; pos <= base.size(); pos++) {
				ArrayList<Integer> candidate = new ArrayList<Integer>(base.size() + 1);
				candidate.addAll(base.subList(0, pos));
				candidate.add(Integer.valueOf(requiredJob));
				candidate.addAll(base.subList(pos, base.size()));
				cost = evaluateFallbackCandidate(child, candidate, -1, -1);
				if (Utility.compareLt(cost, bestCost)) {
					bestCost = cost;
					bestSequence = candidate;
				}
			}
		}
		return bestSequence;
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
		ArrayList<Integer> sequence = findRequiredArcFallbackSequence(child, requiredFrom, requiredTo);
		if (sequence == null || sequence.isEmpty()) {
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

	private ArrayList<Integer> findRequiredArcFallbackSequence(Node child, int requiredFrom, int requiredTo) {
		ArrayList<Integer> requiredChain = buildRequiredArcChain(child, requiredFrom, requiredTo);
		if (requiredChain.isEmpty()) {
			return null;
		}

		// 2026-05-18: required-arc 子节点不能只依赖 restricted pool 里已有列。
		// 旧 VRP 的 UpdateRouteSet 会在子节点缺可行列时主动找满足分支状态的 route。
		// 这里先做一个轻量兜底：把 required arc 链插入已有列的不同位置，挑一条兼容且成本最小的列。
		// 这不是完整定向 pricing，但比只尝试裸 required-chain 更不容易误关可行子节点。
		ArrayList<Integer> bestSequence = null;
		double bestCost = Double.POSITIVE_INFINITY;
		double cost = evaluateFallbackCandidate(child, requiredChain, requiredFrom, requiredTo);
		if (Utility.compareLt(cost, bestCost)) {
			bestCost = cost;
			bestSequence = requiredChain;
		}

		for (TWETColumn baseColumn : pool.getColumns()) {
			List<Integer> base = baseColumn.getSequence();
			if (hasJobOverlap(base, requiredChain)) {
				continue;
			}
			for (int pos = 0; pos <= base.size(); pos++) {
				ArrayList<Integer> candidate = new ArrayList<Integer>(base.size() + requiredChain.size());
				candidate.addAll(base.subList(0, pos));
				candidate.addAll(requiredChain);
				candidate.addAll(base.subList(pos, base.size()));
				cost = evaluateFallbackCandidate(child, candidate, requiredFrom, requiredTo);
				if (Utility.compareLt(cost, bestCost)) {
					bestCost = cost;
					bestSequence = candidate;
				}
			}
		}
		return bestSequence;
	}

	private double evaluateFallbackCandidate(Node child, ArrayList<Integer> sequence, int requiredFrom, int requiredTo) {
		if (sequence.isEmpty() || hasDuplicateJobs(sequence) || !isSequenceCompatible(child, sequence)
				|| (requiredFrom >= 0 && requiredTo >= 0
						&& !sequenceCoversArc(sequence, requiredFrom, requiredTo, child.sinkId()))) {
			return Utility.big_M;
		}
		double cost = columnEvaluator.evaluate(sequence);
		return Utility.isBigMValue(cost) ? Utility.big_M : cost;
	}

	private boolean hasJobOverlap(List<Integer> base, ArrayList<Integer> chain) {
		for (int job : chain) {
			if (base.contains(Integer.valueOf(job))) {
				return true;
			}
		}
		return false;
	}

	private boolean hasDuplicateJobs(ArrayList<Integer> sequence) {
		boolean[] seen = new boolean[data.n + 1];
		for (int job : sequence) {
			if (job < 1 || job > data.n || seen[job]) {
				return true;
			}
			seen[job] = true;
		}
		return false;
	}

	private boolean sequenceCoversArc(List<Integer> sequence, int from, int to, int sink) {
		if (sequence.isEmpty()) {
			return false;
		}
		if (from == 0) {
			return sequence.get(0).intValue() == to;
		}
		if (to == sink) {
			return sequence.get(sequence.size() - 1).intValue() == from;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (sequence.get(i - 1).intValue() == from && sequence.get(i).intValue() == to) {
				return true;
			}
		}
		return false;
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
