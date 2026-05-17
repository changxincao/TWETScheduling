package TWETBPC.LP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;
import ilog.concert.IloException;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;

/**
 * 当前节点上的受限主问题包装器。
 * <p>
 * 2026-05-17: 这里已经不再是 placeholder。当前实现采用
 * {@code parallel_machine_scheduling_with_due_window.pdf} 中 SP2 的思路：
 * 内部机器调度方案由列变量表示，外包由显式 {@code y_j} 变量和总外包成本函数
 * {@code G(sum b_j y_j)} 表示。RMP 先解 LP 松弛并向 pricing 暴露 dual。
 */
public class LP {

	private static final double VALUE_TOLERANCE = 1e-8;

	private final Data data;
	private final Pool pool;
	private final CutPool cutPool;
	private Node node;
	private ArrayList<Integer> restrictedColumnIds;
	private ArrayList<Integer> activeCutIds;
	private TWETMasterSolution lastSolution;

	private IloCplex cplex;
	private IloNumVar[] lambdaVars;
	private IloNumVar[] outsourceVars;
	private IloNumVar[] outsourceSegmentActive;
	private IloNumVar[] outsourceSegmentBaseline;
	private IloRange[] coverRanges;
	private IloRange machineRange;
	private HashMap<Long, IloRange> requiredArcRanges;
	private ArrayList<TariffSegment> outsourcingTariffSegments;

	private double[] jobDual;
	private double machineDual;
	private double[][] arcDual;

	public LP(Data data, Pool pool, CutPool cutPool) {
		this.data = data;
		this.pool = pool;
		this.cutPool = cutPool;
		this.restrictedColumnIds = new ArrayList<Integer>();
		this.activeCutIds = new ArrayList<Integer>();
		this.jobDual = new double[data.n + 1];
		this.arcDual = new double[data.n + 2][data.n + 2];
	}

	public void construct(Node node, List<Integer> columnIds) {
		this.node = node;
		this.restrictedColumnIds = filterFeasibleColumns(columnIds);
		this.activeCutIds = new ArrayList<Integer>(node.activeCutIds);
		this.lastSolution = null;
		clearDuals();
	}

	public Node getNode() {
		return node;
	}

	public Data getData() {
		return data;
	}

	public Pool getPool() {
		return pool;
	}

	public CutPool getCutPool() {
		return cutPool;
	}

	public List<Integer> getRestrictedColumnIds() {
		return restrictedColumnIds;
	}

	public List<Integer> getActiveCutIds() {
		return activeCutIds;
	}

	public TWETMasterSolution getLastSolution() {
		return lastSolution;
	}

	/** @return job 覆盖约束的 dual，供 pricing 计算 reduced cost */
	public double getJobDual(int job) {
		return jobDual[job];
	}

	/** @return 机器数约束的 dual；每条内部列的系数为 1 */
	public double getMachineDual() {
		return machineDual;
	}

	/** @return arc 分支约束的 dual；没有对应约束时为 0 */
	public double getArcDual(int from, int to) {
		if (from < 0 || from >= arcDual.length || to < 0 || to >= arcDual[from].length) {
			return 0.0;
		}
		return arcDual[from][to];
	}

	public void addColumns(List<Integer> columnIds) {
		for (int id : columnIds) {
			Integer value = Integer.valueOf(id);
			if (!restrictedColumnIds.contains(value) && isColumnCompatible(pool.getColumn(id))) {
				restrictedColumnIds.add(value);
			}
		}
	}

	public void addCuts(List<Integer> cutIds) {
		for (int id : cutIds) {
			Integer value = Integer.valueOf(id);
			if (!activeCutIds.contains(value)) {
				activeCutIds.add(value);
			}
		}
	}

	public TWETMasterSolution solveRelaxation() {
		if (node == null) {
			lastSolution = new TWETMasterSolution(TWETMasterStatus.INFEASIBLE, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Node not constructed");
			return lastSolution;
		}

		try {
			buildModel();
			cplex.setOut(null);
			boolean solved = cplex.solve();
			if (!solved) {
				clearDuals();
				lastSolution = new TWETMasterSolution(TWETMasterStatus.INFEASIBLE,
						new LinkedHashMap<Integer, Double>(), 0.0, false,
						"Restricted master infeasible or not solved: " + cplex.getStatus());
				return lastSolution;
			}

			readDuals();
			LinkedHashMap<Integer, Double> columnValues = readColumnValues();
			double[] outsourcingValues = readOutsourcingValues();
			double[] segmentValues = readOutsourceSegmentValues();
			boolean integer = isIntegerSolution(columnValues, outsourcingValues);
			lastSolution = new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION, columnValues, outsourcingValues,
					segmentValues, cplex.getObjValue(), integer, "Restricted master LP solved");
			return lastSolution;
		} catch (IloException ex) {
			clearDuals();
			lastSolution = new TWETMasterSolution(TWETMasterStatus.INFEASIBLE, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Restricted master error: " + ex.getMessage());
			return lastSolution;
		}
	}

	private void buildModel() throws IloException {
		if (cplex != null) {
			cplex.end();
		}
		cplex = new IloCplex();
		requiredArcRanges = new HashMap<Long, IloRange>();
		outsourcingTariffSegments = collectOutsourcingTariffSegments();

		buildVariables();
		buildObjective();
		buildCoverageConstraints();
		buildMachineConstraint();
		buildRequiredArcConstraints();
		buildOutsourcingTariffConstraints();
	}

	private void buildVariables() throws IloException {
		lambdaVars = new IloNumVar[restrictedColumnIds.size()];
		for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
			int columnId = restrictedColumnIds.get(idx).intValue();
			lambdaVars[idx] = cplex.numVar(0.0, Double.MAX_VALUE, "lambda_" + columnId);
		}

		outsourceVars = new IloNumVar[data.n + 1];
		for (int j = 1; j <= data.n; j++) {
			double ub = Utility.isBigMValue(data.outsourcingCost[j]) ? 0.0 : 1.0;
			outsourceVars[j] = cplex.numVar(0.0, ub, "y_" + j);
		}

		outsourceSegmentActive = new IloNumVar[outsourcingTariffSegments.size()];
		outsourceSegmentBaseline = new IloNumVar[outsourcingTariffSegments.size()];
		for (int l = 0; l < outsourcingTariffSegments.size(); l++) {
			TariffSegment seg = outsourcingTariffSegments.get(l);
			double lb = node.getTariffSegmentState(l) == Node.SEGMENT_REQUIRED ? 1.0 : 0.0;
			double ub = node.getTariffSegmentState(l) == Node.SEGMENT_FORBIDDEN ? 0.0 : 1.0;
			outsourceSegmentActive[l] = cplex.numVar(lb, ub, "outSegActive_" + l);
			outsourceSegmentBaseline[l] = cplex.numVar(0.0, seg.end, "outSegBaseline_" + l);
		}
	}

	private void buildObjective() throws IloException {
		IloLinearNumExpr obj = cplex.linearNumExpr();
		for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
			TWETColumn column = pool.getColumn(restrictedColumnIds.get(idx).intValue());
			obj.addTerm(column.getCost(), lambdaVars[idx]);
		}
		for (int l = 0; l < outsourcingTariffSegments.size(); l++) {
			TariffSegment seg = outsourcingTariffSegments.get(l);
			obj.addTerm(seg.slope, outsourceSegmentBaseline[l]);
			obj.addTerm(seg.intercept, outsourceSegmentActive[l]);
		}
		cplex.addMinimize(obj);
	}

	private void buildCoverageConstraints() throws IloException {
		coverRanges = new IloRange[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
				TWETColumn column = pool.getColumn(restrictedColumnIds.get(idx).intValue());
				if (column.containsJob(job)) {
					expr.addTerm(1.0, lambdaVars[idx]);
				}
			}
			expr.addTerm(1.0, outsourceVars[job]);
			coverRanges[job] = cplex.addEq(expr, 1.0, "cover_" + job);
		}
	}

	private void buildMachineConstraint() throws IloException {
		IloLinearNumExpr expr = cplex.linearNumExpr();
		for (IloNumVar var : lambdaVars) {
			expr.addTerm(1.0, var);
		}
		machineRange = cplex.addRange(node.minMachineCount, expr, node.maxMachineCount, "machineCount");
	}

	private void buildRequiredArcConstraints() throws IloException {
		int sink = node.sinkId();
		for (int from = 0; from <= sink; from++) {
			for (int to = 1; to <= sink; to++) {
				if (from == to || node.getArcState(from, to) != Node.ARC_REQUIRED) {
					continue;
				}
				IloLinearNumExpr expr = cplex.linearNumExpr();
				for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
					TWETColumn column = pool.getColumn(restrictedColumnIds.get(idx).intValue());
					if (column.visitsArc(from, to, sink)) {
						expr.addTerm(1.0, lambdaVars[idx]);
					}
				}
				IloRange range = cplex.addEq(expr, 1.0, "requiredArc_" + from + "_" + to);
				requiredArcRanges.put(arcKey(from, to), range);
			}
		}
	}

	private void buildOutsourcingTariffConstraints() throws IloException {
		// SP2 的外包部分：y_j 决定 baseline 总量 q=sum b_j y_j，
		// 分段变量给出 G(q) 的 LP 松弛表达。整数化/更强刻画后续在 B&B 层继续加强。
		IloLinearNumExpr baselineFromJobs = cplex.linearNumExpr();
		for (int job = 1; job <= data.n; job++) {
			if (!Utility.isBigMValue(data.outsourcingCost[job])) {
				baselineFromJobs.addTerm(data.outsourcingCost[job], outsourceVars[job]);
			}
		}
		IloLinearNumExpr baselineFromSegments = cplex.linearNumExpr();
		IloLinearNumExpr active = cplex.linearNumExpr();
		for (int l = 0; l < outsourcingTariffSegments.size(); l++) {
			TariffSegment seg = outsourcingTariffSegments.get(l);
			baselineFromSegments.addTerm(1.0, outsourceSegmentBaseline[l]);
			active.addTerm(1.0, outsourceSegmentActive[l]);
			cplex.addGe(outsourceSegmentBaseline[l], cplex.prod(seg.start, outsourceSegmentActive[l]),
					"outSegLB_" + l);
			cplex.addLe(outsourceSegmentBaseline[l], cplex.prod(seg.end, outsourceSegmentActive[l]),
					"outSegUB_" + l);
		}
		cplex.addEq(baselineFromJobs, baselineFromSegments, "outsourceBaseline");
		cplex.addEq(active, 1.0, "outsourceOneSegment");
	}

	private ArrayList<TariffSegment> collectOutsourcingTariffSegments() {
		data.evaluateOutsourcingCost(0.0);
		ArrayList<TariffSegment> segments = new ArrayList<TariffSegment>();
		PiecewiseLinearFunction.Segment seg = data.outsourcingCostFunction.head;
		while (seg != null) {
			segments.add(new TariffSegment(seg.start, seg.end, seg.slope, seg.intercept));
			seg = seg.next;
		}
		return segments;
	}

	private void readDuals() throws IloException {
		clearDuals();
		for (int job = 1; job <= data.n; job++) {
			jobDual[job] = cplex.getDual(coverRanges[job]);
		}
		machineDual = cplex.getDual(machineRange);
		for (Map.Entry<Long, IloRange> entry : requiredArcRanges.entrySet()) {
			int from = decodeFrom(entry.getKey().longValue());
			int to = decodeTo(entry.getKey().longValue());
			arcDual[from][to] = cplex.getDual(entry.getValue());
		}
	}

	private LinkedHashMap<Integer, Double> readColumnValues() throws IloException {
		LinkedHashMap<Integer, Double> values = new LinkedHashMap<Integer, Double>();
		for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
			double value = cplex.getValue(lambdaVars[idx]);
			if (Utility.compareGt(value, VALUE_TOLERANCE)) {
				values.put(restrictedColumnIds.get(idx), Double.valueOf(value));
			}
		}
		return values;
	}

	private double[] readOutsourcingValues() throws IloException {
		double[] values = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			values[job] = cplex.getValue(outsourceVars[job]);
		}
		return values;
	}

	private double[] readOutsourceSegmentValues() throws IloException {
		double[] values = new double[outsourceSegmentActive.length];
		for (int segment = 0; segment < outsourceSegmentActive.length; segment++) {
			values[segment] = cplex.getValue(outsourceSegmentActive[segment]);
		}
		return values;
	}

	private boolean isIntegerSolution(Map<Integer, Double> columnValues, double[] outsourcingValues) throws IloException {
		for (double value : columnValues.values()) {
			if (!isIntegral01(value)) {
				return false;
			}
		}
		for (int job = 1; job <= data.n; job++) {
			if (!isIntegral01(outsourcingValues[job])) {
				return false;
			}
		}
		for (IloNumVar var : outsourceSegmentActive) {
			if (!isIntegral01(cplex.getValue(var))) {
				return false;
			}
		}
		return true;
	}

	private boolean isIntegral01(double value) {
		return Utility.compareLe(Math.abs(value - Math.rint(value)), VALUE_TOLERANCE);
	}

	private ArrayList<Integer> filterFeasibleColumns(List<Integer> columnIds) {
		ArrayList<Integer> feasible = new ArrayList<Integer>(columnIds.size());
		for (int id : columnIds) {
			if (isColumnCompatible(pool.getColumn(id))) {
				feasible.add(Integer.valueOf(id));
			}
		}
		return feasible;
	}

	private boolean isColumnCompatible(TWETColumn column) {
		return node == null || node.isColumnCompatible(column);
	}

	private void clearDuals() {
		for (int i = 0; i < jobDual.length; i++) {
			jobDual[i] = 0.0;
		}
		machineDual = 0.0;
		for (int i = 0; i < arcDual.length; i++) {
			for (int j = 0; j < arcDual[i].length; j++) {
				arcDual[i][j] = 0.0;
			}
		}
	}

	private long arcKey(int from, int to) {
		return ((long) from) * (data.n + 2L) + to;
	}

	private int decodeFrom(long key) {
		return (int) (key / (data.n + 2L));
	}

	private int decodeTo(long key) {
		return (int) (key % (data.n + 2L));
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
