package TWETBPC.LP;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.GC.CompletionBoundSubtreeArcEliminator;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETOutsourcingColumn;
import TWETBPC.Util.SequenceSignature;

/**
 * 节点 LP 完全定价闭合后的 route enumeration。
 * <p>
 * 该实现只在没有 active SRI cut、非 time-indexed pricing、gap 足够小时启用。内部机器列按 elementary
 * full-domain 序列枚举；列化外包模式会同步枚举外包列。枚举达到上限时不关闭节点。
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

	public RouteEnumerationResult enumerate(LP lp, double incumbentCost, double nodeLowerBound,
			CompletionBoundSubtreeArcEliminator.PreparedBounds preparedBounds) {
		long start = System.nanoTime();
		String skipReason = skipReason(lp, incumbentCost, nodeLowerBound);
		if (skipReason != null) {
			return RouteEnumerationResult.skipped(skipReason);
		}

		double gap = incumbentCost - nodeLowerBound;
		Node node = lp.getNode();
		LP.PricingDualSnapshot dual = lp.captureTruePricingDuals();
		LinkedHashSet<Integer> finiteIds = new LinkedHashSet<Integer>(lp.getRestrictedColumnIds());
		LinkedHashSet<Integer> finiteOutsourcingIds =
				new LinkedHashSet<Integer>(lp.getRestrictedOutsourcingColumnIds());
		if (finiteIds.size() + finiteOutsourcingIds.size() > config.routeEnumerationColumnLimit) {
			return RouteEnumerationResult.incomplete("active RMP column count exceeds limit",
					new ArrayList<Integer>(finiteIds), new ArrayList<Integer>(finiteOutsourcingIds), 0, 0, 0, 0, 0,
					0, 0, 0, 0, System.nanoTime() - start);
		}
		HashSet<Integer> activeIds = new HashSet<Integer>(lp.getRestrictedColumnIds());
		HashSet<Integer> activeOutsourcingIds = new HashSet<Integer>(lp.getRestrictedOutsourcingColumnIds());
		HashSet<SequenceSignature> runSignatures = new HashSet<SequenceSignature>();
		HashSet<SequenceSignature> outsourcingRunSignatures = new HashSet<SequenceSignature>();
		ArrayList<PendingColumn> pending = new ArrayList<PendingColumn>();
		ArrayList<TWETOutsourcingColumn> pendingOutsourcing = new ArrayList<TWETOutsourcingColumn>();
		PiecewiseLinearFunction[] jobPenalties = buildJobPenaltyCache();
		long nextSerial = 0L;
		State root = buildRootState(dual, jobPenalties, nextSerial++);
		if (root == null) {
			return RouteEnumerationResult.incomplete("empty source frontier", new ArrayList<Integer>(finiteIds),
					new ArrayList<Integer>(finiteOutsourcingIds), 0, 0, 0, 0, 0, 0, 0, 0, 0,
					System.nanoTime() - start);
		}
		ArrayDeque<State> queue = new ArrayDeque<State>();
		queue.add(root);

		int states = 0;
		int candidates = 0;
		int outsourcingCandidates = 0;
		int newColumns = 0;
		int newOutsourcingColumns = 0;
		int duplicateActive = 0;
		int duplicatePool = 0;
		int duplicateRun = 0;
		int cbPruned = 0;

		while (!queue.isEmpty()) {
			State state = queue.poll();
			states++;
			if (!state.sequence.isEmpty() && canUseArc(node, state.lastJob, node.sinkId())) {
				double reducedCost = completeReducedCost(state, dual, node.sinkId());
				ColumnCheck check = checkColumn(lp, state.sequence, reducedCost, dual, gap, activeIds,
						runSignatures);
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
					if (finiteIds.size() + pending.size() + finiteOutsourcingIds.size()
							+ pendingOutsourcing.size() > config.routeEnumerationColumnLimit) {
						return RouteEnumerationResult.incomplete("column limit exceeded",
								new ArrayList<Integer>(finiteIds), new ArrayList<Integer>(finiteOutsourcingIds),
								states, candidates, outsourcingCandidates, newColumns, newOutsourcingColumns,
								duplicateActive, duplicatePool, duplicateRun, cbPruned, System.nanoTime() - start);
					}
				}
			}

			for (int job = 1; job <= data.n; job++) {
				if (state.used[job] || isRequiredOutsourced(node, job)) {
					continue;
				}
				if (!canUseArc(node, state.lastJob, job)) {
					continue;
				}
				State child = extendState(state, job, nextSerial++, dual, jobPenalties);
				if (child == null || Utility.isBigMValue(child.minReducedCost)) {
					continue;
				}
				if (canPruneByCompletionBound(preparedBounds, child, gap)) {
					cbPruned++;
					continue;
				}
				queue.add(child);
			}
			if (states > config.routeEnumerationStateLimit) {
				return RouteEnumerationResult.incomplete("state limit exceeded", new ArrayList<Integer>(finiteIds),
						new ArrayList<Integer>(finiteOutsourcingIds), states, candidates, outsourcingCandidates,
						newColumns, newOutsourcingColumns, duplicateActive, duplicatePool, duplicateRun, cbPruned,
						System.nanoTime() - start);
			}
		}

		if (lp.isColumnizedOutsourcing()) {
			OutsourcingEnumeration outsourcing = enumerateOutsourcingColumns(lp, dual, gap, activeOutsourcingIds,
					outsourcingRunSignatures);
			outsourcingCandidates = outsourcing.candidates;
			newOutsourcingColumns = outsourcing.newColumns.size();
			finiteOutsourcingIds.addAll(outsourcing.existingIds);
			pendingOutsourcing.addAll(outsourcing.newColumns);
			if (finiteIds.size() + pending.size() + finiteOutsourcingIds.size()
					+ pendingOutsourcing.size() > config.routeEnumerationColumnLimit) {
				return RouteEnumerationResult.incomplete("column limit exceeded after outsourcing enumeration",
						new ArrayList<Integer>(finiteIds), new ArrayList<Integer>(finiteOutsourcingIds), states,
						candidates, outsourcingCandidates, newColumns, newOutsourcingColumns, duplicateActive,
						duplicatePool, duplicateRun, cbPruned, System.nanoTime() - start);
			}
		}

		for (PendingColumn column : pending) {
			int id = lp.getPool().addColumn(column.sequence, column.cost, ColumnSource.ROUTE_ENUMERATION, false);
			finiteIds.add(Integer.valueOf(id));
		}
		for (TWETOutsourcingColumn column : pendingOutsourcing) {
			int id = lp.getOutsourcingPool().addColumn(column);
			if (id >= 0) {
				finiteOutsourcingIds.add(Integer.valueOf(id));
			}
		}
		return RouteEnumerationResult.complete("enumeration complete, gap=" + gap, new ArrayList<Integer>(finiteIds),
				new ArrayList<Integer>(finiteOutsourcingIds), states, candidates, outsourcingCandidates, newColumns,
				newOutsourcingColumns, duplicateActive, duplicatePool, duplicateRun, cbPruned,
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
		if (config.useTimeIndexedGraphPricing) {
			return "time-indexed graph pricing uses non-elementary columns";
		}
		double gap = incumbentCost - nodeLowerBound;
		if (Utility.compareLe(gap, 0.0) || Utility.compareGe(gap, config.routeEnumerationAbsoluteGapThreshold)) {
			return "gap outside trigger: " + gap;
		}
		if (!lp.getActiveCutIds().isEmpty()) {
			return "active cuts are incompatible";
		}
		return null;
	}

	private OutsourcingEnumeration enumerateOutsourcingColumns(LP lp, LP.PricingDualSnapshot dual, double gap,
			HashSet<Integer> activeOutsourcingIds, HashSet<SequenceSignature> outsourcingRunSignatures) {
		Node node = lp.getNode();
		ArrayList<OutsourcingLabel> labels = new ArrayList<OutsourcingLabel>();
		labels.add(new OutsourcingLabel());
		for (int job = 1; job <= data.n; job++) {
			if (!lp.getOutsourcingPool().isOutsourceable(job)) {
				continue;
			}
			byte state = node.getOutsourcingJobState(job);
			if (state == Node.OUTSOURCE_FORBIDDEN) {
				continue;
			}
			ArrayList<OutsourcingLabel> next = new ArrayList<OutsourcingLabel>(labels.size() * 2);
			if (state != Node.OUTSOURCE_REQUIRED) {
				next.addAll(labels);
			}
			for (OutsourcingLabel label : labels) {
				next.add(label.include(job, data.outsourcingCost[job], dual.jobDual[job]));
			}
			labels = pruneOutsourcing(next);
		}

		OutsourcingEnumeration result = new OutsourcingEnumeration();
		for (OutsourcingLabel label : labels) {
			if (label.jobs.isEmpty()) {
				continue;
			}
			double cost = data.evaluateOutsourcingCost(label.baseline);
			if (Utility.isBigMValue(cost)) {
				continue;
			}
			double reducedCost = cost - label.profit - dual.outsourcingColumnDual;
			if (!Utility.compareLt(reducedCost, gap - REDUCED_COST_TOLERANCE)) {
				continue;
			}
			result.candidates++;
			SequenceSignature signature = new SequenceSignature(label.jobs);
			int existingId = lp.getOutsourcingPool().getColumnIdBySignature(signature);
			if (existingId >= 0) {
				if (!activeOutsourcingIds.contains(Integer.valueOf(existingId))) {
					result.existingIds.add(Integer.valueOf(existingId));
				}
				continue;
			}
			if (!outsourcingRunSignatures.add(signature)) {
				continue;
			}
			result.newColumns.add(new TWETOutsourcingColumn(-1, label.jobs, data.n, label.baseline, cost,
					ColumnSource.ROUTE_ENUMERATION, false));
		}
		return result;
	}

	private ArrayList<OutsourcingLabel> pruneOutsourcing(ArrayList<OutsourcingLabel> labels) {
		ArrayList<OutsourcingLabel> kept = new ArrayList<OutsourcingLabel>();
		for (OutsourcingLabel label : labels) {
			boolean dominated = false;
			for (int i = 0; i < kept.size(); i++) {
				OutsourcingLabel existing = kept.get(i);
				if (existing.dominates(label)) {
					dominated = true;
					break;
				}
				if (label.dominates(existing)) {
					kept.remove(i);
					i--;
				}
			}
			if (!dominated) {
				kept.add(label);
			}
		}
		return kept;
	}

	private State buildRootState(LP.PricingDualSnapshot dual, PiecewiseLinearFunction[] jobPenalties, long serial) {
		PiecewiseLinearFunction frontier = jobPenalties[0].copy();
		if (frontier.head == null) {
			return null;
		}
		frontier.shiftYInPlace(-dual.machineDual);
		frontier.normalize(Direction.FORWARD);
		if (frontier.head == null) {
			return null;
		}
		return State.root(data.n, frontier, forwardEndpointMin(frontier), serial);
	}

	private State extendState(State state, int job, long serial, LP.PricingDualSnapshot dual,
			PiecewiseLinearFunction[] jobPenalties) {
		double delay = data.getSetUp(state.lastJob, job) + data.getProcessT(job);
		PiecewiseLinearFunction shifted = state.frontier.shiftX(delay);
		if (shifted.head == null) {
			return null;
		}
		PiecewiseLinearFunction jobPenalty = jobPenalties[job];
		if (jobPenalty.head == null) {
			return null;
		}
		PiecewiseLinearFunction nextFrontier = shifted.add(jobPenalty);
		if (nextFrontier.head == null) {
			return null;
		}
		double fixedReducedCost = data.getSetupCost(state.lastJob, job) - dual.jobDual[job]
				- dual.arcDual[state.lastJob][job];
		nextFrontier.shiftYInPlace(fixedReducedCost);
		nextFrontier.normalize(Direction.FORWARD);
		if (nextFrontier.head == null) {
			return null;
		}
		return state.extend(job, nextFrontier, forwardEndpointMin(nextFrontier), serial);
	}

	private double completeReducedCost(State state, LP.PricingDualSnapshot dual, int sink) {
		return state.minReducedCost - dual.arcDual[state.lastJob][sink];
	}

	private PiecewiseLinearFunction[] buildJobPenaltyCache() {
		PiecewiseLinearFunction[] penalties = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 0; job <= data.n; job++) {
			penalties[job] = cropToHorizon(data.penaltyFunction[job]);
		}
		return penalties;
	}

	private PiecewiseLinearFunction cropToHorizon(PiecewiseLinearFunction function) {
		PiecewiseLinearFunction cropped = new PiecewiseLinearFunction();
		cropped.resetDomain(0.0, data.CmaxH);
		if (function == null || function.head == null) {
			return cropped;
		}
		for (PiecewiseLinearFunction.Segment segment = function.head; segment != null; segment = segment.next) {
			double start = Math.max(segment.start, 0.0);
			double end = Math.min(segment.end, data.CmaxH);
			if (Utility.compareLt(start, end)) {
				cropped.addSegment(start, end, segment.slope, segment.intercept);
			}
		}
		return cropped;
	}

	private static double forwardEndpointMin(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.tail == null) {
			return Utility.big_M;
		}
		return frontier.tail.getValue(frontier.tail.end);
	}

	private ColumnCheck checkColumn(LP lp, List<Integer> sequence, double reducedCost,
			LP.PricingDualSnapshot dual, double gap, HashSet<Integer> activeIds,
			HashSet<SequenceSignature> runSignatures) {
		SequenceSignature signature = new SequenceSignature(sequence);
		int existingId = lp.getPool().getColumnIdBySignature(signature);
		if (existingId >= 0) {
			TWETColumn existing = lp.getPool().getColumn(existingId);
			double existingReducedCost = lp.computeReducedCost(existing, dual);
			boolean negativeEnough = existingReducedCost < gap - REDUCED_COST_TOLERANCE;
			if (activeIds.contains(Integer.valueOf(existingId))) {
				return ColumnCheck.activeDuplicate(negativeEnough);
			}
			return ColumnCheck.existing(negativeEnough, existingId);
		}
		if (!Utility.compareLt(reducedCost, gap - REDUCED_COST_TOLERANCE)) {
			return ColumnCheck.newColumn(false, Double.NaN);
		}
		if (!runSignatures.add(signature)) {
			return ColumnCheck.runDuplicate();
		}
		double cost = evaluator.evaluate(sequence);
		TWETColumn column = new TWETColumn(-1, sequence, data.n, cost, ColumnSource.ROUTE_ENUMERATION, false);
		double checkedReducedCost = lp.computeReducedCost(column, dual);
		return ColumnCheck.newColumn(checkedReducedCost < gap - REDUCED_COST_TOLERANCE, cost);
	}

	private boolean canUseArc(Node node, int from, int to) {
		return !node.isArcForbidden(from, to) && !node.isPricingOnlyArcForbidden(from, to);
	}

	private boolean canPruneByCompletionBound(CompletionBoundSubtreeArcEliminator.PreparedBounds preparedBounds,
			State state, double gap) {
		if (!config.routeEnumerationUseCompletionBound || preparedBounds == null || state.lastJob <= 0) {
			return false;
		}
		return preparedBounds.canPruneForwardLabel(state.lastJob, state.frontier, state.minReducedCost, gap,
				config.bidirectionalCompletionBoundScalarPruning);
	}

	private boolean isRequiredOutsourced(Node node, int job) {
		return node.getOutsourcingJobState(job) == Node.OUTSOURCE_REQUIRED;
	}

	private static final class State {
		final int lastJob;
		final ArrayList<Integer> sequence;
		final boolean[] used;
		final PiecewiseLinearFunction frontier;
		final double minReducedCost;
		final long serial;

		static State root(int jobCount, PiecewiseLinearFunction frontier, double minReducedCost, long serial) {
			return new State(0, new ArrayList<Integer>(), new boolean[jobCount + 1], frontier, minReducedCost,
					serial);
		}

		State(int lastJob, ArrayList<Integer> sequence, boolean[] used, PiecewiseLinearFunction frontier,
				double minReducedCost, long serial) {
			this.lastJob = lastJob;
			this.sequence = sequence;
			this.used = used;
			this.frontier = frontier;
			this.minReducedCost = minReducedCost;
			this.serial = serial;
		}

		State extend(int job, PiecewiseLinearFunction frontier, double minReducedCost, long serial) {
			ArrayList<Integer> nextSequence = new ArrayList<Integer>(sequence);
			nextSequence.add(Integer.valueOf(job));
			boolean[] nextUsed = used.clone();
			nextUsed[job] = true;
			return new State(job, nextSequence, nextUsed, frontier, minReducedCost, serial);
		}
	}

	private static final class OutsourcingEnumeration {
		final ArrayList<Integer> existingIds = new ArrayList<Integer>();
		final ArrayList<TWETOutsourcingColumn> newColumns = new ArrayList<TWETOutsourcingColumn>();
		int candidates;
	}

	private static final class OutsourcingLabel {
		final ArrayList<Integer> jobs;
		final double baseline;
		final double profit;

		OutsourcingLabel() {
			this.jobs = new ArrayList<Integer>();
			this.baseline = 0.0;
			this.profit = 0.0;
		}

		OutsourcingLabel(ArrayList<Integer> jobs, double baseline, double profit) {
			this.jobs = jobs;
			this.baseline = baseline;
			this.profit = profit;
		}

		OutsourcingLabel include(int job, double baselineDelta, double profitDelta) {
			ArrayList<Integer> nextJobs = new ArrayList<Integer>(jobs);
			nextJobs.add(Integer.valueOf(job));
			return new OutsourcingLabel(nextJobs, baseline + baselineDelta, profit + profitDelta);
		}

		boolean dominates(OutsourcingLabel other) {
			return Utility.compareLe(baseline, other.baseline) && Utility.compareGe(profit, other.profit);
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
