package TWETBPC.LP;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
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
 * 该组件不修改原 LP 对象，而是复制当前节点已经激活的列和分支状态，单独构造一个 MIP。
 * 列、外包和 tariff segment active 变量取整数，job 覆盖约束沿用 RMP 的 set-covering 语义。
 */
public final class RestrictedMasterIntegerHeuristic {

	private final Data data;
	private final TWETBPCConfig config;

	public RestrictedMasterIntegerHeuristic(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	public Result solve(LP lp) {
		if (lp == null || lp.getNode() == null || lp.getRestrictedColumnIds().isEmpty()) {
			return Result.notSolved("restricted integer heuristic skipped: empty LP");
		}
		long start = System.nanoTime();
		IloCplex cplex = null;
		try {
			cplex = new IloCplex();
			cplex.setOut(null);
			if (Utility.compareGt(config.restrictedMasterIntegerHeuristicTimeLimitSeconds, 0.0)) {
				cplex.setParam(IloCplex.Param.TimeLimit,
						config.restrictedMasterIntegerHeuristicTimeLimitSeconds);
			}
			Model model = buildModel(cplex, lp);
			boolean solved = cplex.solve();
			long elapsed = System.nanoTime() - start;
			if (!solved) {
				return Result.notSolved("restricted integer heuristic not solved: " + cplex.getStatus(), elapsed);
			}
			return readResult(cplex, model, elapsed);
		} catch (IloException ex) {
			return Result.notSolved("restricted integer heuristic error: " + ex.getMessage(),
					System.nanoTime() - start);
		} finally {
			if (cplex != null) {
				cplex.end();
			}
		}
	}

	private Model buildModel(IloCplex cplex, LP lp) throws IloException {
		Node node = lp.getNode();
		List<Integer> columnIds = lp.getRestrictedColumnIds();
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

		buildCoverage(cplex, lp, x, y);
		buildMachineConstraint(cplex, node, x);
		buildArcBranchConstraints(cplex, lp, x);
		buildAdjacencyBranchConstraints(cplex, lp, x);
		buildTariffConstraints(cplex, node, y, z, baseline, segments);
		return new Model(columnIds, x, y, z);
	}

	private void buildCoverage(IloCplex cplex, LP lp, IloIntVar[] x, IloIntVar[] y) throws IloException {
		List<Integer> columnIds = lp.getRestrictedColumnIds();
		for (int job = 1; job <= data.n; job++) {
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int idx = 0; idx < columnIds.size(); idx++) {
				if (lp.getPool().getColumn(columnIds.get(idx).intValue()).containsJob(job)) {
					expr.addTerm(1.0, x[idx]);
				}
			}
			expr.addTerm(1.0, y[job]);
			cplex.addGe(expr, 1.0, "rmih_cover_" + job);
		}
	}

	private void buildMachineConstraint(IloCplex cplex, Node node, IloIntVar[] x) throws IloException {
		IloLinearNumExpr expr = cplex.linearNumExpr();
		for (IloIntVar var : x) {
			expr.addTerm(1.0, var);
		}
		cplex.addRange(node.minMachineCount, expr, node.maxMachineCount, "rmih_machineCount");
	}

	private void buildArcBranchConstraints(IloCplex cplex, LP lp, IloIntVar[] x) throws IloException {
		Node node = lp.getNode();
		int sink = node.sinkId();
		List<Integer> columnIds = lp.getRestrictedColumnIds();
		for (int from = 0; from <= sink; from++) {
			for (int to = 1; to <= sink; to++) {
				byte state = node.getArcState(from, to);
				if (from == to || state == Node.ARC_FREE) {
					continue;
				}
				IloLinearNumExpr expr = cplex.linearNumExpr();
				for (int idx = 0; idx < columnIds.size(); idx++) {
					TWETColumn column = lp.getPool().getColumn(columnIds.get(idx).intValue());
					if (column.visitsArc(from, to, sink)) {
						expr.addTerm(1.0, x[idx]);
					}
				}
				if (state == Node.ARC_REQUIRED) {
					cplex.addEq(expr, 1.0, "rmih_requiredArc_" + from + "_" + to);
				} else {
					cplex.addEq(expr, 0.0, "rmih_forbiddenArc_" + from + "_" + to);
				}
			}
		}
	}

	private void buildAdjacencyBranchConstraints(IloCplex cplex, LP lp, IloIntVar[] x) throws IloException {
		addAdjacencyBranchConstraints(cplex, lp, x, lp.getNode().getForbiddenAdjacencyPairs(), false);
		addAdjacencyBranchConstraints(cplex, lp, x, lp.getNode().getRequiredAdjacencyPairs(), true);
	}

	private void addAdjacencyBranchConstraints(IloCplex cplex, LP lp, IloIntVar[] x, List<int[]> pairs,
			boolean required) throws IloException {
		List<Integer> columnIds = lp.getRestrictedColumnIds();
		for (int[] pair : pairs) {
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int idx = 0; idx < columnIds.size(); idx++) {
				TWETColumn column = lp.getPool().getColumn(columnIds.get(idx).intValue());
				if (lp.getNode().columnCoversAdjacencyPair(column, pair[0], pair[1])) {
					expr.addTerm(1.0, x[idx]);
				}
			}
			if (required) {
				cplex.addGe(expr, 1.0, "rmih_requiredAdjacency_" + pair[0] + "_" + pair[1]);
			} else {
				cplex.addEq(expr, 0.0, "rmih_forbiddenAdjacency_" + pair[0] + "_" + pair[1]);
			}
		}
	}

	private void buildTariffConstraints(IloCplex cplex, Node node, IloIntVar[] y, IloIntVar[] z,
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
			byte state = node.getTariffSegmentState(segment);
			if (state == Node.SEGMENT_FORBIDDEN) {
				cplex.addLe(z[segment], 0.0, "rmih_outSegForbidden_" + segment);
			} else if (state == Node.SEGMENT_REQUIRED) {
				cplex.addGe(z[segment], 1.0, "rmih_outSegRequired_" + segment);
			}
			cplex.addGe(baseline[segment], cplex.prod(tariff.start, z[segment]), "rmih_outSegLB_" + segment);
			cplex.addLe(baseline[segment], cplex.prod(tariff.end, z[segment]), "rmih_outSegUB_" + segment);
		}
		cplex.addEq(baselineFromJobs, baselineFromSegments, "rmih_outsourceBaseline");
		cplex.addEq(active, 1.0, "rmih_outsourceOneSegment");
	}

	private Result readResult(IloCplex cplex, Model model, long elapsedNanos) throws IloException {
		LinkedHashMap<Integer, Double> columnValues = new LinkedHashMap<Integer, Double>();
		ArrayList<Integer> selectedColumnIds = new ArrayList<Integer>();
		for (int idx = 0; idx < model.columnIds.size(); idx++) {
			if (Utility.compareGt(cplex.getValue(model.columnVars[idx]), 0.5)) {
				Integer columnId = model.columnIds.get(idx);
				columnValues.put(columnId, Double.valueOf(1.0));
				selectedColumnIds.add(columnId);
			}
		}
		double[] outsourcingValues = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			outsourcingValues[job] = cplex.getValue(model.outsourceVars[job]) > 0.5 ? 1.0 : 0.0;
		}
		double[] segmentValues = new double[model.segmentVars.length];
		for (int segment = 0; segment < model.segmentVars.length; segment++) {
			segmentValues[segment] = cplex.getValue(model.segmentVars[segment]) > 0.5 ? 1.0 : 0.0;
		}
		TWETMasterSolution solution = new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION, columnValues,
				outsourcingValues, segmentValues, cplex.getObjValue(), true,
				"Restricted master integer heuristic " + cplex.getStatus());
		return Result.feasible(cplex.getObjValue(), selectedColumnIds, outsourcingValues, segmentValues,
				cplex.getStatus().toString(), elapsedNanos, solution);
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
		private final double[] segmentValues;
		private final String status;
		private final long elapsedNanos;
		private final TWETMasterSolution solution;

		private Result(boolean feasible, double objective, ArrayList<Integer> selectedColumnIds,
				double[] outsourcingValues, double[] segmentValues, String status, long elapsedNanos,
				TWETMasterSolution solution) {
			this.feasible = feasible;
			this.objective = objective;
			this.selectedColumnIds = selectedColumnIds;
			this.outsourcingValues = outsourcingValues;
			this.segmentValues = segmentValues;
			this.status = status;
			this.elapsedNanos = elapsedNanos;
			this.solution = solution;
		}

		static Result feasible(double objective, ArrayList<Integer> selectedColumnIds, double[] outsourcingValues,
				double[] segmentValues, String status, long elapsedNanos, TWETMasterSolution solution) {
			return new Result(true, objective, selectedColumnIds, outsourcingValues, segmentValues, status,
					elapsedNanos, solution);
		}

		static Result notSolved(String status) {
			return notSolved(status, 0L);
		}

		static Result notSolved(String status, long elapsedNanos) {
			return new Result(false, Double.POSITIVE_INFINITY, new ArrayList<Integer>(), new double[0], new double[0],
					status, elapsedNanos, null);
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

		public String summary() {
			return String.format(Locale.US, "%s obj=%.6f cols=%d", status, objective, selectedColumnIds.size());
		}
	}

	private static final class Model {
		final List<Integer> columnIds;
		final IloIntVar[] columnVars;
		final IloIntVar[] outsourceVars;
		final IloIntVar[] segmentVars;

		Model(List<Integer> columnIds, IloIntVar[] columnVars, IloIntVar[] outsourceVars, IloIntVar[] segmentVars) {
			this.columnIds = columnIds;
			this.columnVars = columnVars;
			this.outsourceVars = outsourceVars;
			this.segmentVars = segmentVars;
		}
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
