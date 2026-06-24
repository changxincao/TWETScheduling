package TWETBPC.LP;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.SequenceSignature;

/**
 * 节点 LP 完全定价闭合后的 route enumeration。
 * <p>
 * 该实现只在没有 active SRI cut、没有列化外包时启用。枚举完整跑空后，所有 rc < UB-LB 的内部机器列
 * 会被提交到全局列池，并交给有限主问题 MIP 证明；如果枚举达到上限，则不关闭节点。
 */
public final class RouteEnumerationEngine {

	private static final double REDUCED_COST_TOLERANCE = 1e-8;

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;

	public RouteEnumerationEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	public RouteEnumerationResult enumerate(LP lp, double incumbentCost, double nodeLowerBound) {
		long start = System.nanoTime();
		String skipReason = skipReason(lp, incumbentCost, nodeLowerBound);
		if (skipReason != null) {
			return RouteEnumerationResult.skipped(skipReason);
		}

		double gap = incumbentCost - nodeLowerBound;
		Node node = lp.getNode();
		LP.PricingDualSnapshot dual = lp.captureTruePricingDuals();
		LinkedHashSet<Integer> finiteIds = new LinkedHashSet<Integer>(lp.getRestrictedColumnIds());
		if (finiteIds.size() > config.routeEnumerationColumnLimit) {
			return RouteEnumerationResult.incomplete("active RMP column count exceeds limit",
					new ArrayList<Integer>(finiteIds), 0, 0, 0, 0, 0, 0, System.nanoTime() - start);
		}
		HashSet<Integer> activeIds = new HashSet<Integer>(lp.getRestrictedColumnIds());
		HashSet<SequenceSignature> runSignatures = new HashSet<SequenceSignature>();
		ArrayList<PendingColumn> pending = new ArrayList<PendingColumn>();
		ArrayDeque<State> stack = new ArrayDeque<State>();
		stack.push(State.root(data.n));

		int states = 0;
		int candidates = 0;
		int newColumns = 0;
		int duplicateActive = 0;
		int duplicatePool = 0;
		int duplicateRun = 0;

		while (!stack.isEmpty()) {
			State state = stack.pop();
			states++;
			if (!state.sequence.isEmpty() && canUseArc(node, state.lastJob, node.sinkId())) {
				ColumnCheck check = checkColumn(lp, state.sequence, dual, gap, activeIds, runSignatures);
				if (check.negativeEnough) {
					candidates++;
					if (check.activeDuplicate) {
						duplicateActive++;
					} else if (check.existingColumnId >= 0) {
						if (finiteIds.add(Integer.valueOf(check.existingColumnId))) {
							duplicatePool++;
						}
					} else if (check.runDuplicate) {
						duplicateRun++;
					} else {
						pending.add(new PendingColumn(state.sequence, check.cost));
						newColumns++;
					}
					if (finiteIds.size() + pending.size() > config.routeEnumerationColumnLimit) {
						return RouteEnumerationResult.incomplete("column limit exceeded",
								new ArrayList<Integer>(finiteIds), states, candidates, newColumns, duplicateActive,
								duplicatePool, duplicateRun, System.nanoTime() - start);
					}
				}
			}

			for (int job = data.n; job >= 1; job--) {
				if (state.used[job] || isRequiredOutsourced(node, job)) {
					continue;
				}
				if (!canUseArc(node, state.lastJob, job)) {
					continue;
				}
				stack.push(state.extend(job));
			}
			if (states > config.routeEnumerationColumnLimit * Math.max(1, data.n)) {
				return RouteEnumerationResult.incomplete("state guard exceeded", new ArrayList<Integer>(finiteIds),
						states, candidates, newColumns, duplicateActive, duplicatePool, duplicateRun,
						System.nanoTime() - start);
			}
		}

		for (PendingColumn column : pending) {
			int id = lp.getPool().addColumn(column.sequence, column.cost, ColumnSource.ROUTE_ENUMERATION, false);
			finiteIds.add(Integer.valueOf(id));
		}
		return RouteEnumerationResult.complete("enumeration complete, gap=" + gap, new ArrayList<Integer>(finiteIds),
				states, candidates, newColumns, duplicateActive, duplicatePool, duplicateRun,
				System.nanoTime() - start);
	}

	private String skipReason(LP lp, double incumbentCost, double nodeLowerBound) {
		if (!config.enableRouteEnumeration) {
			return "disabled";
		}
		if (lp.getNode() == null) {
			return "no node";
		}
		if (!Double.isFinite(incumbentCost) || !Double.isFinite(nodeLowerBound)) {
			return "no finite incumbent or lower bound";
		}
		double gap = incumbentCost - nodeLowerBound;
		if (Utility.compareLe(gap, 0.0) || Utility.compareGe(gap, config.routeEnumerationAbsoluteGapThreshold)) {
			return "gap outside trigger: " + gap;
		}
		if (!lp.getActiveCutIds().isEmpty()) {
			return "active cuts are incompatible";
		}
		if (lp.isColumnizedOutsourcing()) {
			return "columnized outsourcing enumeration not enabled";
		}
		return null;
	}

	private ColumnCheck checkColumn(LP lp, List<Integer> sequence, LP.PricingDualSnapshot dual, double gap,
			HashSet<Integer> activeIds, HashSet<SequenceSignature> runSignatures) {
		SequenceSignature signature = new SequenceSignature(sequence);
		int existingId = lp.getPool().getColumnIdBySignature(signature);
		if (existingId >= 0) {
			TWETColumn existing = lp.getPool().getColumn(existingId);
			double reducedCost = lp.computeReducedCost(existing, dual);
			boolean negativeEnough = reducedCost < gap - REDUCED_COST_TOLERANCE;
			if (activeIds.contains(Integer.valueOf(existingId))) {
				return ColumnCheck.activeDuplicate(negativeEnough);
			}
			return ColumnCheck.existing(negativeEnough, existingId);
		}
		if (!runSignatures.add(signature)) {
			return ColumnCheck.runDuplicate();
		}
		double cost = evaluator.evaluate(sequence);
		TWETColumn column = new TWETColumn(-1, sequence, data.n, cost, ColumnSource.ROUTE_ENUMERATION, false);
		double reducedCost = lp.computeReducedCost(column, dual);
		return ColumnCheck.newColumn(reducedCost < gap - REDUCED_COST_TOLERANCE, cost);
	}

	private boolean canUseArc(Node node, int from, int to) {
		return !node.isArcForbidden(from, to) && !node.isPricingOnlyArcForbidden(from, to);
	}

	private boolean isRequiredOutsourced(Node node, int job) {
		return node.getOutsourcingJobState(job) == Node.OUTSOURCE_REQUIRED;
	}

	private static final class State {
		final int lastJob;
		final ArrayList<Integer> sequence;
		final boolean[] used;

		static State root(int jobCount) {
			return new State(0, new ArrayList<Integer>(), new boolean[jobCount + 1]);
		}

		State(int lastJob, ArrayList<Integer> sequence, boolean[] used) {
			this.lastJob = lastJob;
			this.sequence = sequence;
			this.used = used;
		}

		State extend(int job) {
			ArrayList<Integer> nextSequence = new ArrayList<Integer>(sequence);
			nextSequence.add(Integer.valueOf(job));
			boolean[] nextUsed = used.clone();
			nextUsed[job] = true;
			return new State(job, nextSequence, nextUsed);
		}
	}

	private static final class PendingColumn {
		final ArrayList<Integer> sequence;
		final double cost;

		PendingColumn(List<Integer> sequence, double cost) {
			this.sequence = new ArrayList<Integer>(sequence);
			this.cost = cost;
		}
	}

	private static final class ColumnCheck {
		final boolean negativeEnough;
		final boolean activeDuplicate;
		final boolean runDuplicate;
		final int existingColumnId;
		final double cost;

		static ColumnCheck activeDuplicate(boolean negativeEnough) {
			return new ColumnCheck(negativeEnough, true, false, -1, Double.NaN);
		}

		static ColumnCheck runDuplicate() {
			return new ColumnCheck(true, false, true, -1, Double.NaN);
		}

		static ColumnCheck existing(boolean negativeEnough, int existingColumnId) {
			return new ColumnCheck(negativeEnough, false, false, existingColumnId, Double.NaN);
		}

		static ColumnCheck newColumn(boolean negativeEnough, double cost) {
			return new ColumnCheck(negativeEnough, false, false, -1, cost);
		}

		ColumnCheck(boolean negativeEnough, boolean activeDuplicate, boolean runDuplicate, int existingColumnId,
				double cost) {
			this.negativeEnough = negativeEnough;
			this.activeDuplicate = activeDuplicate;
			this.runDuplicate = runDuplicate;
			this.existingColumnId = existingColumnId;
			this.cost = cost;
		}
	}
}
