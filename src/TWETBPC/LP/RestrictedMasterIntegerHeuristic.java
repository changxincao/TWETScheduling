package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

/**
 * 当前节点 restricted master 的整数化启发式。
 * <p>
 * 这个组件不修改原 LP 对象，只复用当前列池、节点分支状态和 outsourcing tariff 结构，单独构造整数
 * RMP。2026-06-04: 支持两种上界刷新口径：全量 exact-cover partition MIP，以及 screened covering
 * MIP + 重复覆盖修复列 + exact-cover partition MIP。
 */
public final class RestrictedMasterIntegerHeuristic {

	private static final String MODE_PARTITION = "partition";
	private static final String MODE_COVER_REPAIR = "coverRepair";
	private static final double INTEGER_TOLERANCE = 0.5;

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator columnEvaluator;

	public RestrictedMasterIntegerHeuristic(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.columnEvaluator = new TWETColumnEvaluator(data);
	}

	public Result solve(LP lp) {
		if (lp == null || lp.getNode() == null || lp.getRestrictedColumnIds().isEmpty()) {
			return Result.notSolved("restricted integer heuristic skipped: empty LP");
		}
		long start = System.nanoTime();
		try {
			String mode = config.restrictedMasterIntegerHeuristicMode;
			if (MODE_PARTITION.equalsIgnoreCase(mode)) {
				return solvePartition(lp, lp.getRestrictedColumnIds(), "partition", start);
			}
			if (MODE_COVER_REPAIR.equalsIgnoreCase(mode)) {
				return solveCoverRepair(lp, start);
			}
			return Result.notSolved("restricted integer heuristic skipped: unknown mode " + mode,
					System.nanoTime() - start);
		} catch (IloException ex) {
			return Result.notSolved("restricted integer heuristic error: " + ex.getMessage(),
					System.nanoTime() - start);
		}
	}

	private Result solvePartition(LP lp, List<Integer> columnIds, String label, long start) throws IloException {
		Attempt attempt = solveOnce(lp, columnIds, true, label);
		long elapsed = System.nanoTime() - start;
		if (!attempt.solved) {
			return Result.notSolved("restricted integer heuristic " + label + " not solved: " + attempt.status,
					elapsed);
		}
		if (!attempt.coverage.valid()) {
			return Result.notSolved("restricted integer heuristic " + label + " invalid coverage: "
					+ attempt.coverage.summary(), elapsed);
		}
		return buildFeasibleResult(attempt, "restricted integer heuristic " + label + " " + attempt.status,
				elapsed);
	}

	private Result solveCoverRepair(LP lp, long start) throws IloException {
		ArrayList<Integer> screened = selectCoverRepairColumns(lp);
		Attempt covering = solveOnce(lp, screened, false, "coverRepair_covering");
		long elapsed = System.nanoTime() - start;
		if (!covering.solved) {
			return Result.notSolved("restricted integer heuristic coverRepair covering not solved: "
					+ covering.status + " screenedCols=" + screened.size(), elapsed);
		}
		if (covering.coverage.valid()) {
			return buildFeasibleResult(covering, "restricted integer heuristic coverRepair covering "
					+ covering.status + " screenedCols=" + screened.size(), elapsed);
		}

		RepairGeneration repair = generateRepairColumns(lp, covering.selectedColumnIds,
				covering.outsourcingValues, covering.coverage);
		LinkedHashSet<Integer> finalColumnIds = new LinkedHashSet<Integer>(screened);
		finalColumnIds.addAll(repair.repairColumnIds);

		Attempt partition = solveOnce(lp, new ArrayList<Integer>(finalColumnIds), true, "coverRepair_partition");
		elapsed = System.nanoTime() - start;
		String prefix = "restricted integer heuristic coverRepair covering=" + covering.status + " "
				+ covering.coverage.summary() + " screenedCols=" + screened.size() + " repairCols="
				+ repair.repairColumnIds.size() + " repairMode=heuristic partition=" + partition.status;
		if (!partition.solved) {
			return Result.notSolved(prefix, elapsed);
		}
		if (!partition.coverage.valid()) {
			return Result.notSolved(prefix + " invalid coverage: " + partition.coverage.summary(), elapsed);
		}
		return buildFeasibleResult(partition, prefix, elapsed);
	}

	private Attempt solveOnce(LP lp, List<Integer> columnIds, boolean exactCover, String label) throws IloException {
		IloCplex cplex = null;
		try {
			cplex = new IloCplex();
			cplex.setOut(null);
			if (Utility.compareGt(config.restrictedMasterIntegerHeuristicTimeLimitSeconds, 0.0)) {
				cplex.setParam(IloCplex.Param.TimeLimit,
						config.restrictedMasterIntegerHeuristicTimeLimitSeconds);
			}
			Model model = buildModel(cplex, lp, columnIds, exactCover);
			boolean solved = cplex.solve();
			if (!solved) {
				return Attempt.notSolved(label, cplex.getStatus().toString());
			}
			return readAttempt(cplex, lp, model);
		} finally {
			if (cplex != null) {
				cplex.end();
			}
		}
	}

	private Model buildModel(IloCplex cplex, LP lp, List<Integer> columnIds, boolean exactCover)
			throws IloException {
		ArrayList<TariffSegment> segments = collectOutsourcingTariffSegments();
		IloIntVar[] x = new IloIntVar[columnIds.size()];
		IloIntVar[] y = new IloIntVar[data.n + 1];
		IloIntVar[] z = new IloIntVar[segments.size()];
		IloNumVar[] baseline = new IloNumVar[segments.size()];

		IloLinearNumExpr obj = cplex.linearNumExpr();
		for (int idx = 0; idx < columnIds.size(); idx++) {
			int columnId = columnIds.get(idx).intValue();
			x[idx] = cplex.boolVar("rmih_lambda_" + columnId);
			obj.addTerm(lp.getPool().getColumn(columnId).getCost(), x[idx]);
		}
		for (int job = 1; job <= data.n; job++) {
			double ub = Utility.isBigMValue(data.outsourcingCost[job]) ? 0.0 : 1.0;
			y[job] = cplex.boolVar("rmih_y_" + job);
			y[job].setUB(ub);
		}
		for (int segment = 0; segment < segments.size(); segment++) {
			TariffSegment tariff = segments.get(segment);
			z[segment] = cplex.boolVar("rmih_outSegActive_" + segment);
			baseline[segment] = cplex.numVar(0.0, tariff.end, "rmih_outSegBaseline_" + segment);
			obj.addTerm(tariff.slope, baseline[segment]);
			obj.addTerm(tariff.intercept, z[segment]);
		}
		cplex.addMinimize(obj);

		buildCoverage(cplex, lp, columnIds, x, y, exactCover);
		buildMachineConstraint(cplex, x);
		buildTariffConstraints(cplex, y, z, baseline, segments);
		return new Model(new ArrayList<Integer>(columnIds), x, y, z);
	}

	private void buildCoverage(IloCplex cplex, LP lp, List<Integer> columnIds, IloIntVar[] x, IloIntVar[] y,
			boolean exactCover) throws IloException {
		for (int job = 1; job <= data.n; job++) {
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int idx = 0; idx < columnIds.size(); idx++) {
				if (lp.getPool().getColumn(columnIds.get(idx).intValue()).containsJob(job)) {
					expr.addTerm(1.0, x[idx]);
				}
			}
			expr.addTerm(1.0, y[job]);
			if (exactCover) {
				cplex.addEq(expr, 1.0, "rmih_cover_" + job);
			} else {
				cplex.addGe(expr, 1.0, "rmih_cover_" + job);
			}
		}
	}

	private void buildMachineConstraint(IloCplex cplex, IloIntVar[] x) throws IloException {
		IloLinearNumExpr expr = cplex.linearNumExpr();
		for (IloIntVar var : x) {
			expr.addTerm(1.0, var);
		}
		cplex.addRange(0.0, expr, data.m, "rmih_machineCount");
	}

	private void buildTariffConstraints(IloCplex cplex, IloIntVar[] y, IloIntVar[] z,
			IloNumVar[] baseline, ArrayList<TariffSegment> segments) throws IloException {
		if (segments.isEmpty()) {
			return;
		}
		IloLinearNumExpr baselineFromJobs = cplex.linearNumExpr();
		for (int job = 1; job <= data.n; job++) {
			if (!Utility.isBigMValue(data.outsourcingCost[job])) {
				baselineFromJobs.addTerm(data.outsourcingCost[job], y[job]);
			}
		}
		IloLinearNumExpr baselineFromSegments = cplex.linearNumExpr();
		IloLinearNumExpr active = cplex.linearNumExpr();
		for (int segment = 0; segment < segments.size(); segment++) {
			TariffSegment tariff = segments.get(segment);
			baselineFromSegments.addTerm(1.0, baseline[segment]);
			active.addTerm(1.0, z[segment]);
			cplex.addGe(baseline[segment], cplex.prod(tariff.start, z[segment]), "rmih_outSegLB_" + segment);
			cplex.addLe(baseline[segment], cplex.prod(tariff.end, z[segment]), "rmih_outSegUB_" + segment);
		}
		cplex.addEq(baselineFromJobs, baselineFromSegments, "rmih_outsourceBaseline");
		cplex.addEq(active, 1.0, "rmih_outsourceOneSegment");
	}

	private Attempt readAttempt(IloCplex cplex, LP lp, Model model) throws IloException {
		ArrayList<Integer> selectedColumnIds = new ArrayList<Integer>();
		for (int idx = 0; idx < model.columnIds.size(); idx++) {
			if (Utility.compareGt(cplex.getValue(model.columnVars[idx]), INTEGER_TOLERANCE)) {
				selectedColumnIds.add(model.columnIds.get(idx));
			}
		}
		double[] outsourcingValues = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			outsourcingValues[job] = cplex.getValue(model.outsourceVars[job]) > INTEGER_TOLERANCE ? 1.0 : 0.0;
		}
		double[] segmentValues = new double[model.segmentVars.length];
		for (int segment = 0; segment < model.segmentVars.length; segment++) {
			segmentValues[segment] = cplex.getValue(model.segmentVars[segment]) > INTEGER_TOLERANCE ? 1.0 : 0.0;
		}
		CoverageStats coverage = inspectCoverage(lp, selectedColumnIds, outsourcingValues);
		return new Attempt(true, cplex.getStatus().toString(), cplex.getObjValue(), selectedColumnIds,
				outsourcingValues, segmentValues, coverage);
	}

	private Result buildFeasibleResult(Attempt attempt, String status, long elapsedNanos) {
		LinkedHashMap<Integer, Double> columnValues = new LinkedHashMap<Integer, Double>();
		for (Integer columnId : attempt.selectedColumnIds) {
			columnValues.put(columnId, Double.valueOf(1.0));
		}
		TWETMasterSolution solution = new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION, columnValues,
				attempt.outsourcingValues, attempt.segmentValues, attempt.objective, true, status);
		return Result.feasible(attempt.objective, attempt.selectedColumnIds, attempt.outsourcingValues,
				status, elapsedNanos, solution);
	}

	private ArrayList<Integer> selectCoverRepairColumns(LP lp) {
		ArrayList<ColumnScore> scores = new ArrayList<ColumnScore>();
		for (Integer columnId : lp.getRestrictedColumnIds()) {
			double reducedCost = lp.getColumnReducedCost(columnId.intValue());
			scores.add(new ColumnScore(columnId.intValue(), reducedCost));
		}
		Collections.sort(scores, new Comparator<ColumnScore>() {
			@Override
			public int compare(ColumnScore a, ColumnScore b) {
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return 1;
				}
				return Integer.compare(a.columnId, b.columnId);
			}
		});

		LinkedHashSet<Integer> selected = new LinkedHashSet<Integer>();
		TWETMasterSolution lastSolution = lp.getLastSolution();
		if (lastSolution != null) {
			selected.addAll(lastSolution.getActiveColumnIds());
		}

		int reducedCostLimit = config.restrictedMasterIntegerHeuristicReducedCostColumnLimit;
		int keepByReducedCost = reducedCostLimit <= 0 ? scores.size() : Math.min(reducedCostLimit, scores.size());
		for (int idx = 0; idx < keepByReducedCost; idx++) {
			selected.add(Integer.valueOf(scores.get(idx).columnId));
		}

		int perJobLimit = Math.max(0, config.restrictedMasterIntegerHeuristicPerJobColumnLimit);
		if (perJobLimit > 0) {
			for (int job = 1; job <= data.n; job++) {
				int kept = 0;
				for (ColumnScore score : scores) {
					if (lp.getPool().getColumn(score.columnId).containsJob(job)) {
						selected.add(Integer.valueOf(score.columnId));
						kept++;
						if (kept >= perJobLimit) {
							break;
						}
					}
				}
			}
		}
		return new ArrayList<Integer>(selected);
	}

	private RepairGeneration generateRepairColumns(LP lp, List<Integer> selectedColumnIds, double[] outsourcingValues,
			CoverageStats coverage) {
		RepairGeneration repair = new RepairGeneration();
		HashSet<Integer> duplicateJobs = new HashSet<Integer>(coverage.duplicateJobs);
		HashSet<Integer> outsourcedDuplicateJobs = outsourcedDuplicateJobs(outsourcingValues, duplicateJobs);
		boolean needsFallback = false;
		for (Integer columnId : selectedColumnIds) {
			TWETColumn column = lp.getPool().getColumn(columnId.intValue());
			ArrayList<Integer> duplicatedInColumn = duplicatedJobsInColumn(column, duplicateJobs);
			if (duplicatedInColumn.isEmpty()) {
				continue;
			}
			if (duplicatedInColumn.size() > config.restrictedMasterIntegerHeuristicRepairEnumerationDuplicateLimit) {
				needsFallback = true;
				continue;
			}
			addEnumerationRepairColumns(lp, columnId.intValue(), duplicatedInColumn, outsourcedDuplicateJobs, repair);
		}
		if (needsFallback) {
			addMinLossFallbackRepairColumns(lp, selectedColumnIds, duplicateJobs, outsourcedDuplicateJobs, true,
					repair);
			if (!outsourcedDuplicateJobs.isEmpty()) {
				addMinLossFallbackRepairColumns(lp, selectedColumnIds, duplicateJobs, outsourcedDuplicateJobs, false,
						repair);
			}
		}
		return repair;
	}

	private HashSet<Integer> outsourcedDuplicateJobs(double[] outsourcingValues, HashSet<Integer> duplicateJobs) {
		HashSet<Integer> outsourced = new HashSet<Integer>();
		for (Integer job : duplicateJobs) {
			int id = job.intValue();
			if (id >= 1 && id < outsourcingValues.length
					&& Utility.compareGt(outsourcingValues[id], INTEGER_TOLERANCE)) {
				outsourced.add(job);
			}
		}
		return outsourced;
	}

	private ArrayList<Integer> duplicatedJobsInColumn(TWETColumn column, HashSet<Integer> duplicateJobs) {
		ArrayList<Integer> jobs = new ArrayList<Integer>();
		for (Integer job : column.getSequence()) {
			if (duplicateJobs.contains(job)) {
				jobs.add(job);
			}
		}
		return jobs;
	}

	private void addEnumerationRepairColumns(LP lp, int originalColumnId, ArrayList<Integer> duplicatedJobs,
			HashSet<Integer> outsourcedDuplicateJobs, RepairGeneration repair) {
		TWETColumn original = lp.getPool().getColumn(originalColumnId);
		HashSet<Integer> forcedRemoved = new HashSet<Integer>();
		ArrayList<Integer> optionalDuplicates = new ArrayList<Integer>();
		for (Integer job : duplicatedJobs) {
			if (outsourcedDuplicateJobs.contains(job)) {
				forcedRemoved.add(job);
			} else {
				optionalDuplicates.add(job);
			}
		}
		ArrayList<Integer> baseSequence = forcedRemoved.isEmpty()
				? new ArrayList<Integer>(original.getSequence())
				: removeJobs(original.getSequence(), forcedRemoved);
		if (!forcedRemoved.isEmpty() && !baseSequence.isEmpty()) {
			addRepairColumn(lp, originalColumnId, baseSequence, repair);
		}
		if (optionalDuplicates.isEmpty()) {
			return;
		}
		enumerateRemovedDuplicateSubsets(lp, originalColumnId, original.getSequence(), forcedRemoved,
				optionalDuplicates, repair);
		if (!forcedRemoved.isEmpty()) {
			enumerateRemovedDuplicateSubsets(lp, originalColumnId, original.getSequence(), new HashSet<Integer>(),
					optionalDuplicates, repair);
		}
	}

	private void enumerateRemovedDuplicateSubsets(LP lp, int originalColumnId, List<Integer> originalSequence,
			HashSet<Integer> fixedRemoved, ArrayList<Integer> optionalDuplicates, RepairGeneration repair) {
		int combinations = 1 << optionalDuplicates.size();
		for (int mask = 1; mask < combinations; mask++) {
			HashSet<Integer> removed = new HashSet<Integer>(fixedRemoved);
			for (int bit = 0; bit < optionalDuplicates.size(); bit++) {
				if ((mask & (1 << bit)) != 0) {
					removed.add(optionalDuplicates.get(bit));
				}
			}
			ArrayList<Integer> sequence = removeJobs(originalSequence, removed);
			if (!sequence.isEmpty()) {
				addRepairColumn(lp, originalColumnId, sequence, repair);
			}
		}
	}

	private void addMinLossFallbackRepairColumns(LP lp, List<Integer> selectedColumnIds, HashSet<Integer> duplicateJobs,
			HashSet<Integer> outsourcedDuplicateJobs, boolean keepOutsourcingDuplicates, RepairGeneration repair) {
		LinkedHashMap<Integer, ArrayList<Integer>> current = new LinkedHashMap<Integer, ArrayList<Integer>>();
		for (Integer columnId : selectedColumnIds) {
			current.put(columnId, new ArrayList<Integer>(lp.getPool().getColumn(columnId.intValue()).getSequence()));
		}
		for (Integer job : duplicateJobs) {
			Integer keepColumnId = null;
			if (!keepOutsourcingDuplicates || !outsourcedDuplicateJobs.contains(job)) {
				double smallestReduction = Double.POSITIVE_INFINITY;
				for (Integer columnId : selectedColumnIds) {
					ArrayList<Integer> sequence = current.get(columnId);
					if (!sequence.contains(job)) {
						continue;
					}
					ArrayList<Integer> removedSequence = removeJob(sequence, job.intValue());
					if (removedSequence.isEmpty()) {
						continue;
					}
					double reduction = columnEvaluator.evaluate(sequence) - columnEvaluator.evaluate(removedSequence);
					if (Utility.compareLt(reduction, smallestReduction)) {
						smallestReduction = reduction;
						keepColumnId = columnId;
					}
				}
				if (keepColumnId == null) {
					continue;
				}
			}
			for (Integer columnId : selectedColumnIds) {
				if (columnId.equals(keepColumnId)) {
					continue;
				}
				ArrayList<Integer> sequence = current.get(columnId);
				if (!sequence.contains(job)) {
					continue;
				}
				ArrayList<Integer> repaired = removeJob(sequence, job.intValue());
				current.put(columnId, repaired);
				if (!repaired.isEmpty()) {
					addRepairColumn(lp, columnId.intValue(), repaired, repair);
				}
			}
		}
	}

	private void addRepairColumn(LP lp, int originalColumnId, List<Integer> sequence, RepairGeneration repair) {
		double cost = columnEvaluator.evaluate(sequence);
		int columnId = lp.getPool().addColumn(sequence, cost, ColumnSource.MANUAL, false);
		if (columnId != originalColumnId) {
			repair.repairColumnIds.add(Integer.valueOf(columnId));
		}
	}

	private ArrayList<Integer> removeJobs(List<Integer> sequence, HashSet<Integer> removed) {
		ArrayList<Integer> repaired = new ArrayList<Integer>();
		for (Integer job : sequence) {
			if (!removed.contains(job)) {
				repaired.add(job);
			}
		}
		return repaired;
	}

	private ArrayList<Integer> removeJob(List<Integer> sequence, int removedJob) {
		ArrayList<Integer> repaired = new ArrayList<Integer>();
		for (Integer job : sequence) {
			if (job.intValue() != removedJob) {
				repaired.add(job);
			}
		}
		return repaired;
	}

	private CoverageStats inspectCoverage(LP lp, List<Integer> selectedColumnIds, double[] outsourcingValues) {
		int[] cover = new int[data.n + 1];
		for (Integer columnId : selectedColumnIds) {
			TWETColumn column = lp.getPool().getColumn(columnId.intValue());
			for (Integer job : column.getSequence()) {
				if (job.intValue() >= 1 && job.intValue() <= data.n) {
					cover[job.intValue()]++;
				}
			}
		}
		for (int job = 1; job <= data.n && job < outsourcingValues.length; job++) {
			if (Utility.compareGt(outsourcingValues[job], INTEGER_TOLERANCE)) {
				cover[job]++;
			}
		}
		ArrayList<Integer> missingJobs = new ArrayList<Integer>();
		ArrayList<Integer> duplicateJobs = new ArrayList<Integer>();
		int maxCover = 0;
		for (int job = 1; job <= data.n; job++) {
			maxCover = Math.max(maxCover, cover[job]);
			if (cover[job] == 0) {
				missingJobs.add(Integer.valueOf(job));
			} else if (cover[job] > 1) {
				duplicateJobs.add(Integer.valueOf(job));
			}
		}
		return new CoverageStats(missingJobs, duplicateJobs, maxCover);
	}

	private ArrayList<TariffSegment> collectOutsourcingTariffSegments() {
		data.evaluateOutsourcingCost(0.0);
		ArrayList<TariffSegment> segments = new ArrayList<TariffSegment>();
		if (data.outsourcingCostFunction == null || data.outsourcingCostFunction.head == null) {
			return segments;
		}
		for (PiecewiseLinearFunction.Segment seg = data.outsourcingCostFunction.head; seg != null; seg = seg.next) {
			segments.add(new TariffSegment(seg.start, seg.end, seg.slope, seg.intercept));
		}
		return segments;
	}

	public static final class Result {
		private final boolean feasible;
		private final double objective;
		private final ArrayList<Integer> selectedColumnIds;
		private final double[] outsourcingValues;
		private final String status;
		private final long elapsedNanos;
		private final TWETMasterSolution solution;

		private Result(boolean feasible, double objective, ArrayList<Integer> selectedColumnIds,
				double[] outsourcingValues, String status, long elapsedNanos, TWETMasterSolution solution) {
			this.feasible = feasible;
			this.objective = objective;
			this.selectedColumnIds = selectedColumnIds;
			this.outsourcingValues = outsourcingValues;
			this.status = status;
			this.elapsedNanos = elapsedNanos;
			this.solution = solution;
		}

		static Result feasible(double objective, ArrayList<Integer> selectedColumnIds, double[] outsourcingValues,
				String status, long elapsedNanos, TWETMasterSolution solution) {
			return new Result(true, objective, selectedColumnIds, outsourcingValues, status, elapsedNanos, solution);
		}

		static Result notSolved(String status) {
			return notSolved(status, 0L);
		}

		static Result notSolved(String status, long elapsedNanos) {
			return new Result(false, Double.POSITIVE_INFINITY, new ArrayList<Integer>(), new double[0], status,
					elapsedNanos, null);
		}

		public boolean isFeasible() {
			return feasible;
		}

		public double getObjective() {
			return objective;
		}

		public ArrayList<Integer> getSelectedColumnIds() {
			return new ArrayList<Integer>(selectedColumnIds);
		}

		public double[] getOutsourcingValues() {
			return outsourcingValues.clone();
		}

		public String getStatus() {
			return status;
		}

		public long getElapsedNanos() {
			return elapsedNanos;
		}

		public TWETMasterSolution getSolution() {
			return solution;
		}
	}

	private static final class Attempt {
		final boolean solved;
		final String status;
		final double objective;
		final ArrayList<Integer> selectedColumnIds;
		final double[] outsourcingValues;
		final double[] segmentValues;
		final CoverageStats coverage;

		Attempt(boolean solved, String status, double objective, ArrayList<Integer> selectedColumnIds,
				double[] outsourcingValues, double[] segmentValues, CoverageStats coverage) {
			this.solved = solved;
			this.status = status;
			this.objective = objective;
			this.selectedColumnIds = selectedColumnIds;
			this.outsourcingValues = outsourcingValues;
			this.segmentValues = segmentValues;
			this.coverage = coverage;
		}

		static Attempt notSolved(String label, String status) {
			return new Attempt(false, status, Double.POSITIVE_INFINITY, new ArrayList<Integer>(), new double[0],
					new double[0], CoverageStats.empty());
		}
	}

	private static final class Model {
		final ArrayList<Integer> columnIds;
		final IloIntVar[] columnVars;
		final IloIntVar[] outsourceVars;
		final IloIntVar[] segmentVars;

		Model(ArrayList<Integer> columnIds, IloIntVar[] columnVars, IloIntVar[] outsourceVars,
				IloIntVar[] segmentVars) {
			this.columnIds = columnIds;
			this.columnVars = columnVars;
			this.outsourceVars = outsourceVars;
			this.segmentVars = segmentVars;
		}
	}

	private static final class CoverageStats {
		final ArrayList<Integer> missingJobs;
		final ArrayList<Integer> duplicateJobs;
		final int maxCover;

		CoverageStats(ArrayList<Integer> missingJobs, ArrayList<Integer> duplicateJobs, int maxCover) {
			this.missingJobs = missingJobs;
			this.duplicateJobs = duplicateJobs;
			this.maxCover = maxCover;
		}

		static CoverageStats empty() {
			return new CoverageStats(new ArrayList<Integer>(), new ArrayList<Integer>(), 0);
		}

		boolean valid() {
			return missingJobs.isEmpty() && duplicateJobs.isEmpty();
		}

		String summary() {
			return "missing=" + missingJobs.size() + " duplicate=" + duplicateJobs.size() + " maxCover=" + maxCover;
		}
	}

	private static final class ColumnScore {
		final int columnId;
		final double reducedCost;

		ColumnScore(int columnId, double reducedCost) {
			this.columnId = columnId;
			this.reducedCost = reducedCost;
		}
	}

	private static final class RepairGeneration {
		final LinkedHashSet<Integer> repairColumnIds = new LinkedHashSet<Integer>();
	}

	private static final class TariffSegment {
		final double start;
		final double end;
		final double slope;
		final double intercept;

		TariffSegment(double start, double end, double slope, double intercept) {
			this.start = start;
			this.end = end;
			this.slope = slope;
			this.intercept = intercept;
		}
	}
}
