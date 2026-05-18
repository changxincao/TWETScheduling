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
import ilog.concert.IloColumn;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloObjective;
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
	private IloObjective objective;
	private IloNumVar[] lambdaVars;
	private HashMap<Integer, IloNumVar> lambdaByColumnId;
	private IloNumVar[] outsourceVars;
	private IloNumVar[] outsourceSegmentActive;
	private IloNumVar[] outsourceSegmentBaseline;
	private IloNumVar[] coverSlackVars;
	private HashMap<Long, IloNumVar> requiredArcSlackVars;
	private IloRange[] coverRanges;
	private IloRange machineRange;
	private HashMap<Long, IloRange> requiredArcRanges;
	private ArrayList<TariffSegment> outsourcingTariffSegments;
	private boolean feasibilityRepairMode;

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
		this.feasibilityRepairMode = false;
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

	/**
	 * 2026-05-18: 分支子节点缺列时，临时打开人工 slack RMP。
	 * 这个开关只用于模仿旧 VRP 的 UpdateRouteSet/FindFeasible：先让子节点 LP 可解并产生 dual，
	 * 再用 pricing 找列把 slack 压回 0。正常节点求解必须关闭该模式。
	 */
	public void setFeasibilityRepairMode(boolean enabled) {
		this.feasibilityRepairMode = enabled;
		this.lastSolution = null;
	}

	public boolean isFeasibilityRepairMode() {
		return feasibilityRepairMode;
	}

	public boolean isNoSlack() {
		if (cplex == null) {
			return true;
		}
		try {
			if (coverSlackVars != null) {
				for (int job = 1; job < coverSlackVars.length; job++) {
					if (coverSlackVars[job] != null && Utility.compareGt(cplex.getValue(coverSlackVars[job]), VALUE_TOLERANCE)) {
						return false;
					}
				}
			}
			if (requiredArcSlackVars != null) {
				for (IloNumVar slack : requiredArcSlackVars.values()) {
					if (slack != null && Utility.compareGt(cplex.getValue(slack), VALUE_TOLERANCE)) {
						return false;
					}
				}
			}
		} catch (IloException ex) {
			return false;
		}
		return true;
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

	public int addColumns(List<Integer> columnIds) {
		int added = 0;
		for (int id : columnIds) {
			Integer value = Integer.valueOf(id);
			if (!restrictedColumnIds.contains(value) && isColumnCompatible(pool.getColumn(id))) {
				restrictedColumnIds.add(value);
				added++;
				if (cplex != null && objective != null) {
					try {
						addColumnToCurrentModel(id);
					} catch (IloException ex) {
						throw new IllegalStateException("Failed to add column " + id + " to current RMP", ex);
					}
				}
			}
		}
		return added;
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
			return solveCurrentModel("Restricted master LP solved");
		} catch (IloException ex) {
			clearDuals();
			lastSolution = new TWETMasterSolution(TWETMasterStatus.INFEASIBLE, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Restricted master error: " + ex.getMessage());
			return lastSolution;
		}
	}

	public TWETMasterSolution resolveCurrentModel() {
		if (cplex == null) {
			return solveRelaxation();
		}
		try {
			return solveCurrentModel("Restricted master LP resolved");
		} catch (IloException ex) {
			clearDuals();
			lastSolution = new TWETMasterSolution(TWETMasterStatus.INFEASIBLE, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Restricted master resolve error: " + ex.getMessage());
			return lastSolution;
		}
	}

	private TWETMasterSolution solveCurrentModel(String successMessage) throws IloException {
		boolean solved = cplex.solve();
		if (!solved) {
			clearDuals();
			lastSolution = new TWETMasterSolution(TWETMasterStatus.INFEASIBLE, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Restricted master infeasible or not solved: " + cplex.getStatus());
			return lastSolution;
		}

		readDuals();
		LinkedHashMap<Integer, Double> columnValues = readColumnValues();
		double[] outsourcingValues = readOutsourcingValues();
		double[] segmentValues = readOutsourceSegmentValues();
		boolean integer = isIntegerSolution(columnValues, outsourcingValues);
		String message = feasibilityRepairMode && !isNoSlack() ? successMessage + " with positive artificial slack"
				: successMessage;
		lastSolution = new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION, columnValues, outsourcingValues,
				segmentValues, cplex.getObjValue(), integer, message);
		return lastSolution;
	}

	private void buildModel() throws IloException {
		if (cplex != null) {
			cplex.end();
		}
		cplex = new IloCplex();
		objective = null;
		lambdaByColumnId = new HashMap<Integer, IloNumVar>();
		coverSlackVars = null;
		requiredArcSlackVars = new HashMap<Long, IloNumVar>();
		requiredArcRanges = new HashMap<Long, IloRange>();
		outsourcingTariffSegments = collectOutsourcingTariffSegments();

		buildVariables();
		buildObjective();
		buildCoverageConstraints();
		buildMachineConstraint();
		buildRequiredArcConstraints();
		buildOutsourcingTariffConstraints();
		if (feasibilityRepairMode) {
			addFeasibilitySlacks();
		}
	}

	private void buildVariables() throws IloException {
		lambdaVars = new IloNumVar[restrictedColumnIds.size()];
		for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
			int columnId = restrictedColumnIds.get(idx).intValue();
			lambdaVars[idx] = cplex.numVar(0.0, Double.MAX_VALUE, "lambda_" + columnId);
			lambdaByColumnId.put(Integer.valueOf(columnId), lambdaVars[idx]);
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
		objective = cplex.addMinimize(obj);
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

	/**
	 * 2026-05-18: 子节点修复模式下加入人工 slack。
	 * <p>
	 * 旧 VRP 在 UpdateRouteSet 里发现子节点 RMP 暂时不可行时，会先 AddSlack，
	 * 然后调用启发式/精确定价器 FindFeasible，用 slack dual 引导生成满足分支状态的列。
	 * 这里保持同一语义：slack 只用于 repair LP，正常 RMP 不带这些变量。
	 */
	private void addFeasibilitySlacks() throws IloException {
		double penalty = Utility.big_M;
		coverSlackVars = new IloNumVar[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			IloColumn col = cplex.column(objective, penalty);
			col = col.and(cplex.column(coverRanges[job], 1.0));
			coverSlackVars[job] = cplex.numVar(col, 0.0, 1.0, "coverSlack_" + job);
		}

		for (Map.Entry<Long, IloRange> entry : requiredArcRanges.entrySet()) {
			long key = entry.getKey().longValue();
			int from = decodeFrom(key);
			int to = decodeTo(key);
			IloColumn col = cplex.column(objective, penalty);
			col = col.and(cplex.column(entry.getValue(), 1.0));
			requiredArcSlackVars.put(Long.valueOf(key),
					cplex.numVar(col, 0.0, 1.0, "requiredArcSlack_" + from + "_" + to));
		}
	}

	private void addColumnToCurrentModel(int columnId) throws IloException {
		TWETColumn column = pool.getColumn(columnId);
		IloColumn cplexColumn = cplex.column(objective, column.getCost());
		cplexColumn = cplexColumn.and(cplex.column(machineRange, 1.0));
		for (int job = 1; job <= data.n; job++) {
			if (column.containsJob(job)) {
				cplexColumn = cplexColumn.and(cplex.column(coverRanges[job], 1.0));
			}
		}
		for (Map.Entry<Long, IloRange> entry : requiredArcRanges.entrySet()) {
			int from = decodeFrom(entry.getKey().longValue());
			int to = decodeTo(entry.getKey().longValue());
			if (column.visitsArc(from, to, node.sinkId())) {
				cplexColumn = cplexColumn.and(cplex.column(entry.getValue(), 1.0));
			}
		}
		IloNumVar var = cplex.numVar(cplexColumn, 0.0, Double.MAX_VALUE, "lambda_" + columnId);
		lambdaByColumnId.put(Integer.valueOf(columnId), var);
		lambdaVars = append(lambdaVars, var);
	}

	private IloNumVar[] append(IloNumVar[] vars, IloNumVar var) {
		IloNumVar[] expanded = new IloNumVar[vars.length + 1];
		System.arraycopy(vars, 0, expanded, 0, vars.length);
		expanded[vars.length] = var;
		return expanded;
	}

	public double getColumnReducedCost(int columnId) {
		if (cplex == null || lambdaByColumnId == null) {
			return Double.POSITIVE_INFINITY;
		}
		IloNumVar var = lambdaByColumnId.get(Integer.valueOf(columnId));
		if (var == null) {
			return Double.POSITIVE_INFINITY;
		}
		try {
			return cplex.getReducedCost(var);
		} catch (IloException ex) {
			return Double.POSITIVE_INFINITY;
		}
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
