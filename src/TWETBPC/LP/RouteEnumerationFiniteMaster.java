package TWETBPC.LP;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.Model.TWETOutsourcingColumn;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

/**
 * route enumeration 完成后的有限整数主问题证明模型。
 * <p>
 * 该模型只用于证明当前节点在已完整枚举的列集合上是否还能改进 incumbent，不改变当前 LP/RMP 状态。
 */
public final class RouteEnumerationFiniteMaster {

	private static final double INTEGER_TOLERANCE = 1e-6;

	private final Data data;
	private final TWETBPCConfig config;

	public RouteEnumerationFiniteMaster(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	public Result solve(LP lp, List<Integer> finiteColumnIds, List<Integer> finiteOutsourcingColumnIds) {
		long start = System.nanoTime();
		IloCplex cplex = null;
		try {
			cplex = new IloCplex();
			cplex.setOut(null);
			cplex.setParam(IloCplex.Param.Threads, 1);
			if (config.routeEnumerationMipTimeLimitSeconds > 0.0) {
				cplex.setParam(IloCplex.Param.TimeLimit, config.routeEnumerationMipTimeLimitSeconds);
			}
			Model model = buildModel(cplex, lp, finiteColumnIds, finiteOutsourcingColumnIds);
			boolean solved = cplex.solve();
			long elapsed = System.nanoTime() - start;
			if (!solved) {
				if (cplex.getStatus() == IloCplex.Status.Infeasible) {
					return Result.provenInfeasible("finite master infeasible", elapsed);
				}
				return Result.notProven("finite master not solved: " + cplex.getStatus(), elapsed);
			}
			if (cplex.getStatus() != IloCplex.Status.Optimal) {
				return Result.notProven("finite master status " + cplex.getStatus() + " obj=" + cplex.getObjValue(),
						elapsed);
			}
			ArrayList<Integer> selected = selectedColumns(cplex, model);
			double[] outsourcing = outsourcingValues(cplex, model);
			double[] segments = segmentValues(cplex, model);
			LinkedHashMap<Integer, Double> columnValues = new LinkedHashMap<Integer, Double>();
			for (Integer id : selected) {
				columnValues.put(id, Double.valueOf(1.0));
			}
			TWETMasterSolution solution = new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION, columnValues,
					outsourcing, segments, cplex.getObjValue(), true, "route enumeration finite master optimal");
			return Result.provenOptimal(cplex.getObjValue(), selected, outsourcing, solution, elapsed);
		} catch (IloException ex) {
			return Result.notProven("finite master error: " + ex.getMessage(), System.nanoTime() - start);
		} finally {
			if (cplex != null) {
				cplex.end();
			}
		}
	}

	private Model buildModel(IloCplex cplex, LP lp, List<Integer> columnIds, List<Integer> outsourcingColumnIds)
			throws IloException {
		ArrayList<TariffSegment> segments = collectOutsourcingTariffSegments();
		IloIntVar[] x = new IloIntVar[columnIds.size()];
		IloIntVar[] w = new IloIntVar[outsourcingColumnIds.size()];
		IloIntVar[] y = lp.isColumnizedOutsourcing() ? new IloIntVar[0] : new IloIntVar[data.n + 1];
		IloIntVar[] z = lp.isColumnizedOutsourcing() ? new IloIntVar[0] : new IloIntVar[segments.size()];
		IloNumVar[] baseline = lp.isColumnizedOutsourcing() ? new IloNumVar[0] : new IloNumVar[segments.size()];

		IloLinearNumExpr obj = cplex.linearNumExpr();
		for (int idx = 0; idx < columnIds.size(); idx++) {
			int columnId = columnIds.get(idx).intValue();
			x[idx] = cplex.boolVar("enum_lambda_" + columnId);
			obj.addTerm(lp.getPool().getColumn(columnId).getCost(), x[idx]);
		}
		if (lp.isColumnizedOutsourcing()) {
			for (int idx = 0; idx < outsourcingColumnIds.size(); idx++) {
				int columnId = outsourcingColumnIds.get(idx).intValue();
				w[idx] = cplex.boolVar("enum_omega_" + columnId);
				obj.addTerm(lp.getOutsourcingPool().getColumn(columnId).getCost(), w[idx]);
			}
		} else {
			for (int job = 1; job <= data.n; job++) {
				double ub = Utility.isBigMValue(data.outsourcingCost[job]) ? 0.0 : 1.0;
				y[job] = cplex.boolVar("enum_y_" + job);
				y[job].setUB(ub);
			}
			for (int segment = 0; segment < segments.size(); segment++) {
				TariffSegment tariff = segments.get(segment);
				z[segment] = cplex.boolVar("enum_outSegActive_" + segment);
				baseline[segment] = cplex.numVar(0.0, tariff.end, "enum_outSegBaseline_" + segment);
				obj.addTerm(tariff.slope, baseline[segment]);
				obj.addTerm(tariff.intercept, z[segment]);
			}
		}
		cplex.addMinimize(obj);
		buildCoverage(cplex, lp, columnIds, outsourcingColumnIds, x, w, y);
		buildMachine(cplex, lp, x);
		buildArcBranches(cplex, lp, columnIds, x);
		buildAdjacencyBranches(cplex, lp, columnIds, x);
		buildOutsourcingMembershipBranches(cplex, lp, outsourcingColumnIds, w, y);
		if (lp.isColumnizedOutsourcing()) {
			buildOutsourcingColumnCount(cplex, w);
		} else {
			buildTariff(cplex, lp, y, z, baseline, segments);
		}
		return new Model(new ArrayList<Integer>(columnIds), new ArrayList<Integer>(outsourcingColumnIds), x, w, y, z,
				lp.getOutsourcingPool());
	}

	private void buildCoverage(IloCplex cplex, LP lp, List<Integer> columnIds, List<Integer> outsourcingColumnIds,
			IloIntVar[] x, IloIntVar[] w, IloIntVar[] y) throws IloException {
		for (int job = 1; job <= data.n; job++) {
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int idx = 0; idx < columnIds.size(); idx++) {
				int coefficient = lp.getPool().getColumn(columnIds.get(idx).intValue()).getJobVisitCount(job);
				if (coefficient > 0) {
					expr.addTerm(coefficient, x[idx]);
				}
			}
			if (lp.isColumnizedOutsourcing()) {
				for (int idx = 0; idx < outsourcingColumnIds.size(); idx++) {
					TWETOutsourcingColumn column =
							lp.getOutsourcingPool().getColumn(outsourcingColumnIds.get(idx).intValue());
					if (column.containsJob(job)) {
						expr.addTerm(1.0, w[idx]);
					}
				}
			} else {
				expr.addTerm(1.0, y[job]);
			}
			cplex.addGe(expr, 1.0, "enum_cover_" + job);
		}
	}

	private void buildMachine(IloCplex cplex, LP lp, IloIntVar[] x) throws IloException {
		IloLinearNumExpr expr = cplex.linearNumExpr();
		for (IloIntVar var : x) {
			expr.addTerm(1.0, var);
		}
		cplex.addRange(lp.getNode().minMachineCount, expr, lp.getNode().maxMachineCount, "enum_machineCount");
	}

	private void buildArcBranches(IloCplex cplex, LP lp, List<Integer> columnIds, IloIntVar[] x)
			throws IloException {
		Node node = lp.getNode();
		int sink = node.sinkId();
		for (int from = 0; from <= sink; from++) {
			for (int to = 1; to <= sink; to++) {
				byte state = node.getArcState(from, to);
				if (from == to || state == Node.ARC_FREE) {
					continue;
				}
				IloLinearNumExpr expr = cplex.linearNumExpr();
				for (int idx = 0; idx < columnIds.size(); idx++) {
					TWETColumn column = lp.getPool().getColumn(columnIds.get(idx).intValue());
					int coefficient = column.getArcVisitCount(from, to, sink);
					if (coefficient > 0) {
						expr.addTerm(coefficient, x[idx]);
					}
				}
				if (state == Node.ARC_REQUIRED) {
					cplex.addEq(expr, 1.0, "enum_requiredArc_" + from + "_" + to);
				} else {
					cplex.addEq(expr, 0.0, "enum_forbiddenArc_" + from + "_" + to);
				}
			}
		}
	}

	private void buildAdjacencyBranches(IloCplex cplex, LP lp, List<Integer> columnIds, IloIntVar[] x)
			throws IloException {
		buildAdjacency(cplex, lp, columnIds, x, lp.getNode().getForbiddenAdjacencyPairs(), false);
		buildAdjacency(cplex, lp, columnIds, x, lp.getNode().getRequiredAdjacencyPairs(), true);
	}

	private void buildOutsourcingColumnCount(IloCplex cplex, IloIntVar[] w) throws IloException {
		IloLinearNumExpr expr = cplex.linearNumExpr();
		for (IloIntVar var : w) {
			expr.addTerm(1.0, var);
		}
		cplex.addLe(expr, 1.0, "enum_outsourcingColumnCount");
	}

	private void buildOutsourcingMembershipBranches(IloCplex cplex, LP lp, List<Integer> outsourcingColumnIds,
			IloIntVar[] w, IloIntVar[] y) throws IloException {
		Node node = lp.getNode();
		for (int job = 1; job <= data.n; job++) {
			byte state = node.getOutsourcingJobState(job);
			if (state == Node.OUTSOURCE_FREE) {
				continue;
			}
			IloLinearNumExpr expr = cplex.linearNumExpr();
			if (lp.isColumnizedOutsourcing()) {
				for (int idx = 0; idx < outsourcingColumnIds.size(); idx++) {
					TWETOutsourcingColumn column =
							lp.getOutsourcingPool().getColumn(outsourcingColumnIds.get(idx).intValue());
					if (column.containsJob(job)) {
						expr.addTerm(1.0, w[idx]);
					}
				}
			} else {
				expr.addTerm(1.0, y[job]);
			}
			if (state == Node.OUTSOURCE_REQUIRED) {
				cplex.addEq(expr, 1.0, "enum_outRequired_" + job);
			} else if (state == Node.OUTSOURCE_FORBIDDEN) {
				cplex.addEq(expr, 0.0, "enum_outForbidden_" + job);
			}
		}
	}

	private void buildAdjacency(IloCplex cplex, LP lp, List<Integer> columnIds, IloIntVar[] x, List<int[]> pairs,
			boolean required) throws IloException {
		int sink = lp.getNode().sinkId();
		for (int[] pair : pairs) {
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int idx = 0; idx < columnIds.size(); idx++) {
				TWETColumn column = lp.getPool().getColumn(columnIds.get(idx).intValue());
				if (column.visitsArc(pair[0], pair[1], sink) || column.visitsArc(pair[1], pair[0], sink)) {
					expr.addTerm(1.0, x[idx]);
				}
			}
			if (required) {
				cplex.addGe(expr, 1.0, "enum_requiredAdj_" + pair[0] + "_" + pair[1]);
			} else {
				cplex.addEq(expr, 0.0, "enum_forbiddenAdj_" + pair[0] + "_" + pair[1]);
			}
		}
	}

	private void buildTariff(IloCplex cplex, LP lp, IloIntVar[] y, IloIntVar[] z, IloNumVar[] baseline,
			ArrayList<TariffSegment> segments) throws IloException {
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
			cplex.addGe(baseline[segment], cplex.prod(tariff.start, z[segment]), "enum_outSegLB_" + segment);
			cplex.addLe(baseline[segment], cplex.prod(tariff.end, z[segment]), "enum_outSegUB_" + segment);
			byte state = lp.getNode().getTariffSegmentState(segment);
			if (state == Node.SEGMENT_FORBIDDEN) {
				cplex.addLe(z[segment], 0.0, "enum_outSegForbidden_" + segment);
			} else if (state == Node.SEGMENT_REQUIRED) {
				cplex.addGe(z[segment], 1.0, "enum_outSegRequired_" + segment);
			}
		}
		cplex.addEq(baselineFromJobs, baselineFromSegments, "enum_outsourceBaseline");
		cplex.addEq(active, 1.0, "enum_outsourceOneSegment");
	}

	private ArrayList<Integer> selectedColumns(IloCplex cplex, Model model) throws IloException {
		ArrayList<Integer> selected = new ArrayList<Integer>();
		for (int idx = 0; idx < model.columnIds.size(); idx++) {
			if (cplex.getValue(model.columnVars[idx]) > INTEGER_TOLERANCE) {
				selected.add(model.columnIds.get(idx));
			}
		}
		return selected;
	}

	private double[] outsourcingValues(IloCplex cplex, Model model) throws IloException {
		double[] values = new double[data.n + 1];
		if (model.outsourceVars.length == 0) {
			for (int idx = 0; idx < model.outsourcingColumnIds.size(); idx++) {
				if (cplex.getValue(model.outsourceColumnVars[idx]) > INTEGER_TOLERANCE) {
					TWETOutsourcingColumn column =
							model.lpOutsourcingPool.getColumn(model.outsourcingColumnIds.get(idx).intValue());
					for (int job : column.getJobs()) {
						values[job] = 1.0;
					}
				}
			}
			return values;
		}
		for (int job = 1; job <= data.n; job++) {
			values[job] = cplex.getValue(model.outsourceVars[job]) > INTEGER_TOLERANCE ? 1.0 : 0.0;
		}
		return values;
	}

	private double[] segmentValues(IloCplex cplex, Model model) throws IloException {
		double[] values = new double[model.segmentVars.length];
		for (int segment = 0; segment < values.length; segment++) {
			values[segment] = cplex.getValue(model.segmentVars[segment]) > INTEGER_TOLERANCE ? 1.0 : 0.0;
		}
		return values;
	}

	private ArrayList<TariffSegment> collectOutsourcingTariffSegments() {
		data.evaluateOutsourcingCost(0.0);
		ArrayList<TariffSegment> segments = new ArrayList<TariffSegment>();
		for (PiecewiseLinearFunction.Segment seg = data.outsourcingCostFunction.head; seg != null; seg = seg.next) {
			segments.add(new TariffSegment(seg.start, seg.end, seg.slope, seg.intercept));
		}
		return segments;
	}

	private static final class Model {
		final ArrayList<Integer> columnIds;
		final ArrayList<Integer> outsourcingColumnIds;
		final IloIntVar[] columnVars;
		final IloIntVar[] outsourceColumnVars;
		final IloIntVar[] outsourceVars;
		final IloIntVar[] segmentVars;
		final OutsourcingPool lpOutsourcingPool;

		Model(ArrayList<Integer> columnIds, ArrayList<Integer> outsourcingColumnIds, IloIntVar[] columnVars,
				IloIntVar[] outsourceColumnVars, IloIntVar[] outsourceVars, IloIntVar[] segmentVars,
				OutsourcingPool lpOutsourcingPool) {
			this.columnIds = columnIds;
			this.outsourcingColumnIds = outsourcingColumnIds;
			this.columnVars = columnVars;
			this.outsourceColumnVars = outsourceColumnVars;
			this.outsourceVars = outsourceVars;
			this.segmentVars = segmentVars;
			this.lpOutsourcingPool = lpOutsourcingPool;
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

	public static final class Result {
		private final boolean proven;
		private final boolean infeasible;
		private final double objective;
		private final ArrayList<Integer> selectedColumnIds;
		private final double[] outsourcingValues;
		private final TWETMasterSolution solution;
		private final String message;
		private final long elapsedNanos;

		private Result(boolean proven, boolean infeasible, double objective, List<Integer> selectedColumnIds,
				double[] outsourcingValues, TWETMasterSolution solution, String message, long elapsedNanos) {
			this.proven = proven;
			this.infeasible = infeasible;
			this.objective = objective;
			this.selectedColumnIds = new ArrayList<Integer>(selectedColumnIds);
			this.outsourcingValues = outsourcingValues == null ? new double[0] : outsourcingValues.clone();
			this.solution = solution;
			this.message = message;
			this.elapsedNanos = elapsedNanos;
		}

		static Result provenInfeasible(String message, long elapsedNanos) {
			return new Result(true, true, Double.POSITIVE_INFINITY, new ArrayList<Integer>(), new double[0], null,
					message, elapsedNanos);
		}

		static Result provenOptimal(double objective, List<Integer> selectedColumnIds, double[] outsourcingValues,
				TWETMasterSolution solution, long elapsedNanos) {
			return new Result(true, false, objective, selectedColumnIds, outsourcingValues, solution,
					"finite master optimal", elapsedNanos);
		}

		static Result notProven(String message, long elapsedNanos) {
			return new Result(false, false, Double.NaN, new ArrayList<Integer>(), new double[0], null, message,
					elapsedNanos);
		}

		public boolean isProven() {
			return proven;
		}

		public boolean isInfeasible() {
			return infeasible;
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

		public TWETMasterSolution getSolution() {
			return solution;
		}

		public String getMessage() {
			return message;
		}

		public long getElapsedNanos() {
			return elapsedNanos;
		}
	}
}
