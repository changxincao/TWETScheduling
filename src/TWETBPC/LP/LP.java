package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.CUT.SubsetRowCutEvaluator;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.Model.TWETOutsourcingColumn;
import ilog.concert.IloColumn;
import ilog.concert.IloException;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloObjective;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;

/**
 * 当前节点上的受限主问题。
 *
 * 2026-05-18: 这里按照 SP2 思路同时建内部列变量、外包 y_j 变量和 outsourcing tariff
 * segment 变量。分支 repair 参考旧 VRP 的 UpdateRouteSet/FindFeasible：先把当前分支行加入 LP，
 * 如果不可行，只对当前新增分支行加人工 slack，用 slack dual 引导定价补列。
 */
public class LP {

	private static final double VALUE_TOLERANCE = 1e-8;

	private final Data data;
	private final Pool pool;
	private final CutPool cutPool;
	private final TWETBPCConfig config;
	private final OutsourcingPool outsourcingPool;
	private Node node;
	private ArrayList<Integer> restrictedColumnIds;
	private ArrayList<Integer> restrictedOutsourcingColumnIds;
	private ArrayList<Integer> activeCutIds;
	private TWETMasterSolution lastSolution;

	private IloCplex cplex;
	private IloObjective objective;
	private IloNumVar[] lambdaVars;
	private HashMap<Integer, IloNumVar> lambdaByColumnId;
	private IloNumVar[] outsourceColumnVars;
	private HashMap<Integer, IloNumVar> outsourceColumnById;
	private IloNumVar[] outsourceVars;
	private IloNumVar[] outsourceSegmentActive;
	private IloNumVar[] outsourceSegmentBaseline;
	private ArrayList<IloNumVar> repairSlackVars;
	private IloRange[] coverRanges;
	private IloRange machineRange;
	private IloRange outsourcingColumnCountRange;
	private HashMap<Long, IloRange> arcBranchRanges;
	private HashMap<Long, IloRange> adjacencyBranchRanges;
	private HashMap<Integer, IloRange> subsetRowCutRanges;
	private ArrayList<Integer> activeSubsetRowPricingCutIds;
	private ArrayList<Double> activeSubsetRowPricingDuals;
	private IloRange[] tariffActiveBounds;
	private IloRange[] tariffBranchRanges;
	private ArrayList<TariffSegment> outsourcingTariffSegments;
	private boolean feasibilityRepairMode;

	private double[] jobDual;
	private double machineDual;
	private double outsourcingColumnDual;
	private double[][] arcDual;

	public LP(Data data, Pool pool, CutPool cutPool) {
		this(data, pool, cutPool, new TWETBPCConfig(), new OutsourcingPool(data));
	}

	public LP(Data data, Pool pool, CutPool cutPool, TWETBPCConfig config, OutsourcingPool outsourcingPool) {
		this.data = data;
		this.pool = pool;
		this.cutPool = cutPool;
		this.config = config;
		this.outsourcingPool = outsourcingPool;
		this.restrictedColumnIds = new ArrayList<Integer>();
		this.restrictedOutsourcingColumnIds = new ArrayList<Integer>();
		this.activeCutIds = new ArrayList<Integer>();
		this.jobDual = new double[data.n + 1];
		this.arcDual = new double[data.n + 2][data.n + 2];
		this.feasibilityRepairMode = false;
	}

	public void construct(Node node, List<Integer> columnIds) {
		this.node = node;
		// 2026-05-18: 子节点首次 LP 先使用父节点传下来的列集，不按新分支状态提前筛列。
		// 这样可先判断“父节点列集 + 新分支行”是否可行；若可行或 repair 成功，再统一筛成正式列集。
		this.restrictedColumnIds = new ArrayList<Integer>();
		for (int columnId : columnIds) {
			// 2026-05-20: child 首次 LP 不按当前分支状态提前筛列，但静态预处理禁弧是全局不可行，
			// 如果历史列池里残留这类列，继续建模会把已证明不可行的列重新放回 RMP。
			// 2026-06-04: 正常父子继承路径下父节点列通常已经满足该条件；这里主要是防御性兜底，
			// 防止外部 seed、旧配置列或调试列绕过 pricing 后把全局预处理禁弧带回模型。
			TWETColumn column = pool.getColumn(columnId);
			boolean compatible = node == null || (isColumnizedOutsourcing() ? node.isColumnCompatible(column)
					: node.isColumnPreprocessingCompatible(column));
			if (compatible) {
				this.restrictedColumnIds.add(Integer.valueOf(columnId));
			}
		}
		this.restrictedOutsourcingColumnIds = new ArrayList<Integer>();
		if (isColumnizedOutsourcing()) {
			for (int columnId : node.seedOutsourcingColumnIds) {
				TWETOutsourcingColumn column = outsourcingPool.getColumn(columnId);
				if (node.isOutsourcingColumnCompatible(column)) {
					this.restrictedOutsourcingColumnIds.add(Integer.valueOf(columnId));
				}
			}
		}
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

	public OutsourcingPool getOutsourcingPool() {
		return outsourcingPool;
	}

	public CutPool getCutPool() {
		return cutPool;
	}

	public List<Integer> getRestrictedColumnIds() {
		return restrictedColumnIds;
	}

	public List<Integer> getRestrictedOutsourcingColumnIds() {
		return restrictedOutsourcingColumnIds;
	}

	public boolean isColumnizedOutsourcing() {
		return config.useColumnizedOutsourcing();
	}

	public List<Integer> getActiveCutIds() {
		return activeCutIds;
	}

	/** @return 当前 LP dual 下真正参与 SRI pricing 的 subset-row cut id；只包含负 dual 的行。 */
	public List<Integer> getActiveSubsetRowPricingCutIds() {
		if (activeSubsetRowPricingCutIds == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(activeSubsetRowPricingCutIds);
	}

	/** @return 与 getActiveSubsetRowPricingCutIds() 同下标的 SRI dual。 */
	public List<Double> getActiveSubsetRowPricingDuals() {
		if (activeSubsetRowPricingDuals == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(activeSubsetRowPricingDuals);
	}

	public TWETMasterSolution getLastSolution() {
		return lastSolution;
	}

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
			if (repairSlackVars != null) {
				for (IloNumVar slack : repairSlackVars) {
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

	/** @return job 覆盖约束的 dual，供 pricing 计算 reduced cost。 */
	public double getJobDual(int job) {
		return jobDual[job];
	}

	/** @return 机器数量约束 dual；每条内部列的系数为 1。 */
	public double getMachineDual() {
		return machineDual;
	}

	public double getOutsourcingColumnDual() {
		return outsourcingColumnDual;
	}

	/** @return arc 分支约束 dual；没有对应约束时为 0。 */
	public double getArcDual(int from, int to) {
		if (from < 0 || from >= arcDual.length || to < 0 || to >= arcDual[from].length) {
			return 0.0;
		}
		return arcDual[from][to];
	}

	public int addColumns(List<Integer> columnIds) {
		int added = 0;
		HashSet<Integer> activeColumnIds = new HashSet<Integer>(restrictedColumnIds);
		for (int id : columnIds) {
			Integer value = Integer.valueOf(id);
			if (!activeColumnIds.contains(value)) {
				restrictedColumnIds.add(value);
				activeColumnIds.add(value);
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
		// 2026-06-19: 为了实验可复现和评估 CPLEX 并行开销，主 RMP 固定为单线程。
		cplex.setParam(IloCplex.Param.Threads, 1);
		objective = null;
		lambdaByColumnId = new HashMap<Integer, IloNumVar>();
		outsourceColumnById = new HashMap<Integer, IloNumVar>();
		repairSlackVars = new ArrayList<IloNumVar>();
		arcBranchRanges = new HashMap<Long, IloRange>();
		adjacencyBranchRanges = new HashMap<Long, IloRange>();
		outsourcingColumnCountRange = null;
		subsetRowCutRanges = new HashMap<Integer, IloRange>();
		activeSubsetRowPricingCutIds = new ArrayList<Integer>();
		activeSubsetRowPricingDuals = new ArrayList<Double>();
		outsourcingTariffSegments = isColumnizedOutsourcing() ? new ArrayList<TariffSegment>()
				: collectOutsourcingTariffSegments();

		buildVariables();
		buildObjective();
		buildCoverageConstraints();
		buildMachineConstraint();
		buildArcBranchConstraints();
		buildAdjacencyBranchConstraints();
		if (!isColumnizedOutsourcing()) {
			buildSubsetRowCutConstraints();
			buildOutsourcingTariffConstraints();
		}
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

		if (isColumnizedOutsourcing()) {
			outsourceColumnVars = new IloNumVar[restrictedOutsourcingColumnIds.size()];
			for (int idx = 0; idx < restrictedOutsourcingColumnIds.size(); idx++) {
				int columnId = restrictedOutsourcingColumnIds.get(idx).intValue();
				outsourceColumnVars[idx] = cplex.numVar(0.0, Double.MAX_VALUE, "omega_" + columnId);
				outsourceColumnById.put(Integer.valueOf(columnId), outsourceColumnVars[idx]);
			}
			outsourceVars = new IloNumVar[data.n + 1];
			outsourceSegmentActive = new IloNumVar[0];
			outsourceSegmentBaseline = new IloNumVar[0];
			return;
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
			// 2026-05-18: z_s 的 [0,1] 写成显式约束行，而不是只依赖变量上界。
			// 这样 z_s<=0 / z_s>=1 分支以及对应 repair slack 都有明确的 LP 行可以挂接。
			outsourceSegmentActive[l] = cplex.numVar(0.0, Double.MAX_VALUE, "outSegActive_" + l);
			outsourceSegmentBaseline[l] = cplex.numVar(0.0, seg.end, "outSegBaseline_" + l);
		}
	}

	private void buildObjective() throws IloException {
		IloLinearNumExpr obj = cplex.linearNumExpr();
		for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
			TWETColumn column = pool.getColumn(restrictedColumnIds.get(idx).intValue());
			obj.addTerm(column.getCost(), lambdaVars[idx]);
		}
		if (isColumnizedOutsourcing()) {
			for (int idx = 0; idx < restrictedOutsourcingColumnIds.size(); idx++) {
				TWETOutsourcingColumn column =
						outsourcingPool.getColumn(restrictedOutsourcingColumnIds.get(idx).intValue());
				obj.addTerm(column.getCost(), outsourceColumnVars[idx]);
			}
			objective = cplex.addMinimize(obj);
			return;
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
				int coefficient = column.getJobVisitCount(job);
				if (coefficient > 0) {
					expr.addTerm(coefficient, lambdaVars[idx]);
				}
			}
			if (isColumnizedOutsourcing()) {
				for (int idx = 0; idx < restrictedOutsourcingColumnIds.size(); idx++) {
					TWETOutsourcingColumn column =
							outsourcingPool.getColumn(restrictedOutsourcingColumnIds.get(idx).intValue());
					if (column.containsJob(job)) {
						expr.addTerm(1.0, outsourceColumnVars[idx]);
					}
				}
				// 2026-06-20: 列化外包仍沿用 set covering 口径；重复覆盖由后续上界启发式修复处理。
				coverRanges[job] = cplex.addGe(expr, 1.0, "cover_" + job);
			} else {
				expr.addTerm(1.0, outsourceVars[job]);
				// 2026-05-24: BPC pricing 后续按 set covering 对偶语义处理任务覆盖行。
				// 在 setup time/cost 满足三角不等式的设定下，重复服务任务不会带来有利的列结构；
				// 覆盖行放宽为 >= 后，job dual 非负，动态 profitable window 可退化为 job-level H_j。
				coverRanges[job] = cplex.addGe(expr, 1.0, "cover_" + job);
			}
		}
	}

	private void buildMachineConstraint() throws IloException {
		IloLinearNumExpr expr = cplex.linearNumExpr();
		for (IloNumVar var : lambdaVars) {
			expr.addTerm(1.0, var);
		}
		// 2026-05-18: 带外包模型下允许真实机器为空，机器数按节点区间建模。
		machineRange = cplex.addRange(node.minMachineCount, expr, node.maxMachineCount, "machineCount");
		if (isColumnizedOutsourcing()) {
			IloLinearNumExpr outsourceExpr = cplex.linearNumExpr();
			for (IloNumVar var : outsourceColumnVars) {
				outsourceExpr.addTerm(1.0, var);
			}
			outsourcingColumnCountRange = cplex.addLe(outsourceExpr, 1.0, "outsourcingColumnCount");
		}
	}

	private void buildArcBranchConstraints() throws IloException {
		int sink = node.sinkId();
		for (int from = 0; from <= sink; from++) {
			for (int to = 1; to <= sink; to++) {
				byte state = node.getArcState(from, to);
				if (from == to || state == Node.ARC_FREE) {
					continue;
				}
				IloLinearNumExpr expr = cplex.linearNumExpr();
				for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
					TWETColumn column = pool.getColumn(restrictedColumnIds.get(idx).intValue());
					int coefficient = column.getArcVisitCount(from, to, sink);
					if (coefficient > 0) {
						expr.addTerm(coefficient, lambdaVars[idx]);
					}
				}
				IloRange range = state == Node.ARC_REQUIRED ? cplex.addEq(expr, 1.0, "requiredArc_" + from + "_" + to)
						: cplex.addEq(expr, 0.0, "forbiddenArc_" + from + "_" + to);
				arcBranchRanges.put(arcKey(from, to), range);
			}
		}
	}

	private void buildAdjacencyBranchConstraints() throws IloException {
		addAdjacencyBranchConstraints(node.getForbiddenAdjacencyPairs(), false);
		addAdjacencyBranchConstraints(node.getRequiredAdjacencyPairs(), true);
	}

	public int addOutsourcingColumns(List<Integer> columnIds) {
		if (!isColumnizedOutsourcing()) {
			return 0;
		}
		int added = 0;
		HashSet<Integer> activeColumnIds = new HashSet<Integer>(restrictedOutsourcingColumnIds);
		for (int id : columnIds) {
			Integer value = Integer.valueOf(id);
			if (!activeColumnIds.contains(value)
					&& node.isOutsourcingColumnCompatible(outsourcingPool.getColumn(id))) {
				restrictedOutsourcingColumnIds.add(value);
				activeColumnIds.add(value);
				added++;
				if (cplex != null && objective != null) {
					try {
						addOutsourcingColumnToCurrentModel(id);
					} catch (IloException ex) {
						throw new IllegalStateException("Failed to add outsourcing column " + id + " to current RMP",
								ex);
					}
				}
			}
		}
		return added;
	}

	private void addAdjacencyBranchConstraints(List<int[]> pairs, boolean required) throws IloException {
		int sink = node.sinkId();
		for (int[] pair : pairs) {
			int first = pair[0];
			int second = pair[1];
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
				TWETColumn column = pool.getColumn(restrictedColumnIds.get(idx).intValue());
				if (column.visitsArc(first, second, sink) || column.visitsArc(second, first, sink)) {
					expr.addTerm(1.0, lambdaVars[idx]);
				}
			}
			// 2026-06-02: 无向相邻右支只要求两方向之一出现，不在 pricing graph 中固定方向。
			IloRange range = required ? cplex.addGe(expr, 1.0, "requiredAdjacency_" + first + "_" + second)
					: cplex.addEq(expr, 0.0, "forbiddenAdjacency_" + first + "_" + second);
			adjacencyBranchRanges.put(Long.valueOf(pairKey(first, second)), range);
		}
	}

	private void buildSubsetRowCutConstraints() throws IloException {
		for (int cutId : activeCutIds) {
			TWETCut cut = cutPool.getCut(cutId);
			if (cut.getType() != TWETCutType.SUBSET_ROW) {
				continue;
			}
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
				TWETColumn column = pool.getColumn(restrictedColumnIds.get(idx).intValue());
				double coefficient = subsetRowCoefficient(column, cut);
				if (coefficient > 0.0) {
					expr.addTerm(coefficient, lambdaVars[idx]);
				}
			}
			// 2026-06-14: 普通 SRI 仍是 0/1 系数；limited-memory SRI 可能产生更大整数系数。
			IloRange range = cplex.addLe(expr, cut.getRhs(), "subsetRow_" + cutId);
			subsetRowCutRanges.put(Integer.valueOf(cutId), range);
		}
	}

	private double subsetRowCoefficient(TWETColumn column, TWETCut cut) {
		return SubsetRowCutEvaluator.coefficient(cut, column.getSequence(), data.n);
	}

	private void buildOutsourcingTariffConstraints() throws IloException {
		IloLinearNumExpr baselineFromJobs = cplex.linearNumExpr();
		for (int job = 1; job <= data.n; job++) {
			if (!Utility.isBigMValue(data.outsourcingCost[job])) {
				baselineFromJobs.addTerm(data.outsourcingCost[job], outsourceVars[job]);
			}
		}
		IloLinearNumExpr baselineFromSegments = cplex.linearNumExpr();
		IloLinearNumExpr active = cplex.linearNumExpr();
		tariffActiveBounds = new IloRange[outsourcingTariffSegments.size()];
		tariffBranchRanges = new IloRange[outsourcingTariffSegments.size()];
		for (int l = 0; l < outsourcingTariffSegments.size(); l++) {
			TariffSegment seg = outsourcingTariffSegments.get(l);
			baselineFromSegments.addTerm(1.0, outsourceSegmentBaseline[l]);
			active.addTerm(1.0, outsourceSegmentActive[l]);

			IloLinearNumExpr zBoundExpr = cplex.linearNumExpr();
			zBoundExpr.addTerm(1.0, outsourceSegmentActive[l]);
			tariffActiveBounds[l] = cplex.addRange(0.0, zBoundExpr, 1.0, "outSegActiveBound_" + l);

			byte state = node.getTariffSegmentState(l);
			if (state == Node.SEGMENT_FORBIDDEN) {
				IloLinearNumExpr branchExpr = cplex.linearNumExpr();
				branchExpr.addTerm(1.0, outsourceSegmentActive[l]);
				tariffBranchRanges[l] = cplex.addLe(branchExpr, 0.0, "outSegForbidden_" + l);
			} else if (state == Node.SEGMENT_REQUIRED) {
				IloLinearNumExpr branchExpr = cplex.linearNumExpr();
				branchExpr.addTerm(1.0, outsourceSegmentActive[l]);
				tariffBranchRanges[l] = cplex.addGe(branchExpr, 1.0, "outSegRequired_" + l);
			}

			cplex.addGe(outsourceSegmentBaseline[l], cplex.prod(seg.start, outsourceSegmentActive[l]),
					"outSegLB_" + l);
			cplex.addLe(outsourceSegmentBaseline[l], cplex.prod(seg.end, outsourceSegmentActive[l]),
					"outSegUB_" + l);
		}
		cplex.addEq(baselineFromJobs, baselineFromSegments, "outsourceBaseline");
		cplex.addEq(active, 1.0, "outsourceOneSegment");
	}

	/**
	 * 2026-05-18: 子节点 repair LP 只给“当前新分支行”加人工 slack。
	 * coverage 如果不可行，应由 pricing/外包列修复；repair slack 只用于产生当前分支行的引导 dual。
	 */
	private void addFeasibilitySlacks() throws IloException {
		double penalty = Utility.big_M;
		byte type = node.getRepairType();
		if (type == Node.REPAIR_MACHINE_UPPER) {
			addRepairSlack(machineRange, -1.0, "machineUpperSlack", penalty);
		} else if (type == Node.REPAIR_MACHINE_LOWER) {
			addRepairSlack(machineRange, 1.0, "machineLowerSlack", penalty);
		} else if (type == Node.REPAIR_ARC_FORBIDDEN || type == Node.REPAIR_ARC_REQUIRED) {
			IloRange range = arcBranchRanges.get(Long.valueOf(arcKey(node.getRepairFrom(), node.getRepairTo())));
			if (range != null) {
				double coeff = type == Node.REPAIR_ARC_REQUIRED ? 1.0 : -1.0;
				addRepairSlack(range, coeff,
						"arcBranchSlack_" + node.getRepairFrom() + "_" + node.getRepairTo(), penalty);
			}
		} else if (type == Node.REPAIR_ADJACENCY_FORBIDDEN || type == Node.REPAIR_ADJACENCY_REQUIRED) {
			IloRange range = adjacencyBranchRanges.get(Long.valueOf(pairKey(node.getRepairFrom(), node.getRepairTo())));
			if (range != null) {
				double coeff = type == Node.REPAIR_ADJACENCY_REQUIRED ? 1.0 : -1.0;
				addRepairSlack(range, coeff,
						"adjacencyBranchSlack_" + node.getRepairFrom() + "_" + node.getRepairTo(), penalty);
			}
		} else if (type == Node.REPAIR_TARIFF_FORBIDDEN || type == Node.REPAIR_TARIFF_REQUIRED) {
			int segment = node.getRepairSegment();
			if (tariffBranchRanges != null && segment >= 0 && segment < tariffBranchRanges.length
					&& tariffBranchRanges[segment] != null) {
				double coeff = type == Node.REPAIR_TARIFF_REQUIRED ? 1.0 : -1.0;
				addRepairSlack(tariffBranchRanges[segment], coeff, "tariffBranchSlack_" + segment, penalty);
			}
		}
	}

	private void addRepairSlack(IloRange range, double coeff, String name, double penalty) throws IloException {
		IloColumn col = cplex.column(objective, penalty);
		col = col.and(cplex.column(range, coeff));
		repairSlackVars.add(cplex.numVar(col, 0.0, Double.MAX_VALUE, name));
	}

	private void addColumnToCurrentModel(int columnId) throws IloException {
		TWETColumn column = pool.getColumn(columnId);
		IloColumn cplexColumn = cplex.column(objective, column.getCost());
		cplexColumn = cplexColumn.and(cplex.column(machineRange, 1.0));
		for (int job = 1; job <= data.n; job++) {
			int coefficient = column.getJobVisitCount(job);
			if (coefficient > 0) {
				cplexColumn = cplexColumn.and(cplex.column(coverRanges[job], coefficient));
			}
		}
		for (Map.Entry<Long, IloRange> entry : arcBranchRanges.entrySet()) {
			int from = decodeFrom(entry.getKey().longValue());
			int to = decodeTo(entry.getKey().longValue());
			int coefficient = column.getArcVisitCount(from, to, node.sinkId());
			if (coefficient > 0) {
				cplexColumn = cplexColumn.and(cplex.column(entry.getValue(), coefficient));
			}
		}
		for (Map.Entry<Long, IloRange> entry : adjacencyBranchRanges.entrySet()) {
			int first = decodeFrom(entry.getKey().longValue());
			int second = decodeTo(entry.getKey().longValue());
			if (node.columnCoversAdjacencyPair(column, first, second)) {
				cplexColumn = cplexColumn.and(cplex.column(entry.getValue(), 1.0));
			}
		}
		for (Map.Entry<Integer, IloRange> entry : subsetRowCutRanges.entrySet()) {
			TWETCut cut = cutPool.getCut(entry.getKey().intValue());
			double coefficient = subsetRowCoefficient(column, cut);
			if (coefficient > 0.0) {
				cplexColumn = cplexColumn.and(cplex.column(entry.getValue(), coefficient));
			}
		}
		IloNumVar var = cplex.numVar(cplexColumn, 0.0, Double.MAX_VALUE, "lambda_" + columnId);
		lambdaByColumnId.put(Integer.valueOf(columnId), var);
		lambdaVars = append(lambdaVars, var);
	}

	private void addOutsourcingColumnToCurrentModel(int columnId) throws IloException {
		TWETOutsourcingColumn column = outsourcingPool.getColumn(columnId);
		IloColumn cplexColumn = cplex.column(objective, column.getCost());
		cplexColumn = cplexColumn.and(cplex.column(outsourcingColumnCountRange, 1.0));
		for (int job = 1; job <= data.n; job++) {
			if (column.containsJob(job)) {
				cplexColumn = cplexColumn.and(cplex.column(coverRanges[job], 1.0));
			}
		}
		IloNumVar var = cplex.numVar(cplexColumn, 0.0, Double.MAX_VALUE, "omega_" + columnId);
		outsourceColumnById.put(Integer.valueOf(columnId), var);
		outsourceColumnVars = append(outsourceColumnVars, var);
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

	/**
	 * repair 成功后，按当前 LP 的 reduced cost 筛出正式子节点列集。
	 */
	public void resetRestrictedColumnsByCurrentReducedCost(int maxColumns, double reducedCostAllowance) {
		if (cplex == null || lambdaByColumnId == null) {
			return;
		}
		ArrayList<Integer> selected = new ArrayList<Integer>();
		ArrayList<ColumnReducedCost> candidates = new ArrayList<ColumnReducedCost>();
		for (int columnId : restrictedColumnIds) {
			TWETColumn column = pool.getColumn(columnId);
			if (!isColumnCompatible(column)) {
				continue;
			}
			double reducedCost = getColumnReducedCost(columnId);
			if (isPositiveCurrentColumn(columnId)) {
				// 2026-05-19: child LP 已经可行时，正值列构成当前可行解的一部分。
				// 理论上这些基列的 reduced cost 通常为 0；这里先无条件保留它们，
				// 再用低 reduced-cost 列补足，避免大量 0 reduced-cost 退化列按 id 截断时误删当前可行基。
				selected.add(Integer.valueOf(columnId));
				continue;
			}
			if (Utility.compareLt(reducedCost, reducedCostAllowance)) {
				candidates.add(new ColumnReducedCost(columnId, reducedCost));
			}
		}
		Collections.sort(candidates, new Comparator<ColumnReducedCost>() {
			@Override
			public int compare(ColumnReducedCost a, ColumnReducedCost b) {
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return 1;
				}
				return Integer.compare(a.columnId, b.columnId);
			}
		});

		for (int i = 0; i < candidates.size() && selected.size() < maxColumns; i++) {
			selected.add(Integer.valueOf(candidates.get(i).columnId));
		}
		if (!selected.isEmpty()) {
			restrictedColumnIds = selected;
			lastSolution = null;
		}
		if (isColumnizedOutsourcing()) {
			resetRestrictedOutsourcingColumnsByCurrentReducedCost(maxColumns, reducedCostAllowance);
		}
	}

	private void resetRestrictedOutsourcingColumnsByCurrentReducedCost(int maxColumns, double reducedCostAllowance) {
		if (outsourceColumnById == null) {
			return;
		}
		ArrayList<Integer> selected = new ArrayList<Integer>();
		ArrayList<ColumnReducedCost> candidates = new ArrayList<ColumnReducedCost>();
		for (int columnId : restrictedOutsourcingColumnIds) {
			TWETOutsourcingColumn column = outsourcingPool.getColumn(columnId);
			if (!node.isOutsourcingColumnCompatible(column)) {
				continue;
			}
			double reducedCost = getOutsourcingColumnReducedCost(columnId);
			if (isPositiveCurrentOutsourcingColumn(columnId)) {
				selected.add(Integer.valueOf(columnId));
				continue;
			}
			if (Utility.compareLt(reducedCost, reducedCostAllowance)) {
				candidates.add(new ColumnReducedCost(columnId, reducedCost));
			}
		}
		Collections.sort(candidates, new Comparator<ColumnReducedCost>() {
			@Override
			public int compare(ColumnReducedCost a, ColumnReducedCost b) {
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return 1;
				}
				return Integer.compare(a.columnId, b.columnId);
			}
		});
		for (int i = 0; i < candidates.size() && selected.size() < maxColumns; i++) {
			selected.add(Integer.valueOf(candidates.get(i).columnId));
		}
		if (!selected.isEmpty()) {
			restrictedOutsourcingColumnIds = selected;
			lastSolution = null;
		}
	}

	private boolean isPositiveCurrentColumn(int columnId) {
		IloNumVar var = lambdaByColumnId.get(Integer.valueOf(columnId));
		if (var == null) {
			return false;
		}
		try {
			return Utility.compareGt(cplex.getValue(var), VALUE_TOLERANCE);
		} catch (IloException ex) {
			return false;
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
		outsourcingColumnDual = isColumnizedOutsourcing() && outsourcingColumnCountRange != null
				? cplex.getDual(outsourcingColumnCountRange) : 0.0;
		for (Map.Entry<Long, IloRange> entry : arcBranchRanges.entrySet()) {
			int from = decodeFrom(entry.getKey().longValue());
			int to = decodeTo(entry.getKey().longValue());
			arcDual[from][to] = cplex.getDual(entry.getValue());
		}
		for (Map.Entry<Long, IloRange> entry : adjacencyBranchRanges.entrySet()) {
			int first = decodeFrom(entry.getKey().longValue());
			int second = decodeTo(entry.getKey().longValue());
			double dual = cplex.getDual(entry.getValue());
			arcDual[first][second] += dual;
			arcDual[second][first] += dual;
		}
		for (Map.Entry<Integer, IloRange> entry : subsetRowCutRanges.entrySet()) {
			double dual = cplex.getDual(entry.getValue());
			if (Utility.compareLt(dual, -VALUE_TOLERANCE)) {
				activeSubsetRowPricingCutIds.add(entry.getKey());
				activeSubsetRowPricingDuals.add(Double.valueOf(dual));
			}
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
		if (isColumnizedOutsourcing()) {
			for (int idx = 0; idx < restrictedOutsourcingColumnIds.size(); idx++) {
				double value = cplex.getValue(outsourceColumnVars[idx]);
				if (Utility.compareGt(value, VALUE_TOLERANCE)) {
					TWETOutsourcingColumn column =
							outsourcingPool.getColumn(restrictedOutsourcingColumnIds.get(idx).intValue());
					for (int job : column.getJobs()) {
						values[job] += value;
					}
				}
			}
			return values;
		}
		for (int job = 1; job <= data.n; job++) {
			values[job] = cplex.getValue(outsourceVars[job]);
		}
		return values;
	}

	private double[] readOutsourceSegmentValues() throws IloException {
		if (isColumnizedOutsourcing()) {
			return new double[0];
		}
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
		if (isColumnizedOutsourcing()) {
			for (IloNumVar var : outsourceColumnVars) {
				if (!isIntegral01(cplex.getValue(var))) {
					return false;
				}
			}
			return true;
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

	private boolean isColumnCompatible(TWETColumn column) {
		return node == null || node.isColumnCompatible(column);
	}

	private void clearDuals() {
		for (int i = 0; i < jobDual.length; i++) {
			jobDual[i] = 0.0;
		}
		machineDual = 0.0;
		outsourcingColumnDual = 0.0;
		for (int i = 0; i < arcDual.length; i++) {
			for (int j = 0; j < arcDual[i].length; j++) {
				arcDual[i][j] = 0.0;
			}
		}
		if (activeSubsetRowPricingCutIds != null) {
			activeSubsetRowPricingCutIds.clear();
		}
		if (activeSubsetRowPricingDuals != null) {
			activeSubsetRowPricingDuals.clear();
		}
	}

	private boolean isPositiveCurrentOutsourcingColumn(int columnId) {
		IloNumVar var = outsourceColumnById.get(Integer.valueOf(columnId));
		if (var == null) {
			return false;
		}
		try {
			return Utility.compareGt(cplex.getValue(var), VALUE_TOLERANCE);
		} catch (IloException ex) {
			return false;
		}
	}

	public double getOutsourcingColumnReducedCost(int columnId) {
		if (!isColumnizedOutsourcing() || cplex == null || outsourceColumnById == null) {
			return Double.POSITIVE_INFINITY;
		}
		IloNumVar var = outsourceColumnById.get(Integer.valueOf(columnId));
		if (var == null) {
			return Double.POSITIVE_INFINITY;
		}
		try {
			return cplex.getReducedCost(var);
		} catch (IloException ex) {
			return Double.POSITIVE_INFINITY;
		}
	}

	private long arcKey(int from, int to) {
		return ((long) from) * (data.n + 2L) + to;
	}

	private long pairKey(int first, int second) {
		int a = Math.min(first, second);
		int b = Math.max(first, second);
		return arcKey(a, b);
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

	private static final class ColumnReducedCost {
		final int columnId;
		final double reducedCost;

		ColumnReducedCost(int columnId, double reducedCost) {
			this.columnId = columnId;
			this.reducedCost = reducedCost;
		}
	}

}
