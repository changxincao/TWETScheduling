package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	private HashSet<Integer> restrictedColumnIdSet;
	private ArrayList<Integer> restrictedOutsourcingColumnIds;
	private HashSet<Integer> restrictedOutsourcingColumnIdSet;
	private ArrayList<Integer> activeCutIds;
	private TWETMasterSolution lastSolution;

	private IloCplex cplex;
	private IloObjective objective;
	private IloNumVar[] lambdaVars;
	private HashMap<Integer, IloNumVar> lambdaByColumnId;
	private HashSet<Integer> branchImpliedPenaltyColumnIds;
	private boolean branchImpliedPenaltyObjectiveMode;
	private IloNumVar[] outsourceColumnVars;
	private HashMap<Integer, IloNumVar> outsourceColumnById;
	private IloNumVar[] outsourceVars;
	private IloNumVar[] outsourceSegmentActive;
	private IloNumVar[] outsourceSegmentBaseline;
	private ArrayList<IloNumVar> repairSlackVars;
	private IloRange[] coverRanges;
	private IloRange machineRange;
	private IloRange outsourcingColumnCountRange;
	private HashMap<Integer, IloRange> outsourcingMembershipBranchRanges;
	private HashMap<Long, IloRange> arcBranchRanges;
	private HashMap<Long, IloRange> adjacencyBranchRanges;
	private HashMap<Integer, IloRange> subsetRowCutRanges;
	private ArrayList<Integer> activeSubsetRowPricingCutIds;
	private ArrayList<Double> activeSubsetRowPricingDuals;
	private IloRange[] tariffActiveBounds;
	private IloRange[] tariffBranchRanges;
	private ArrayList<TariffSegment> outsourcingTariffSegments;
	private boolean feasibilityRepairMode;
	/** repair slack 与 branch-implied 竞争列共用的有限目标惩罚，不再复用 PWLF big_M。 */
	private double repairObjectivePenalty;
	private boolean allRowFeasibilityRepairMode;

	private double[] jobDual;
	private double machineDual;
	private double outsourcingColumnDual;
	private double[] outsourcingMembershipDual;
	private double[][] arcDual;
	private PricingDualSnapshot pricingDualOverride;

	public LP(Data data, Pool pool, CutPool cutPool) {
		this(data, pool, cutPool, new TWETBPCConfig(), new OutsourcingPool(data));
	}

	public LP(Data data, Pool pool, CutPool cutPool, TWETBPCConfig config, OutsourcingPool outsourcingPool) {
		this.data = data;
		this.pool = pool;
		this.cutPool = cutPool;
		this.config = config;
		this.outsourcingPool = outsourcingPool;
		replaceRestrictedColumnIds(Collections.<Integer>emptyList());
		replaceRestrictedOutsourcingColumnIds(Collections.<Integer>emptyList());
		this.activeCutIds = new ArrayList<Integer>();
		this.jobDual = new double[data.n + 1];
		this.outsourcingMembershipDual = new double[data.n + 1];
		this.arcDual = new double[data.n + 2][data.n + 2];
		this.feasibilityRepairMode = false;
		this.repairObjectivePenalty = Utility.big_M;
		this.allRowFeasibilityRepairMode = false;
		this.branchImpliedPenaltyObjectiveMode = false;
	}

	public void construct(Node node, List<Integer> columnIds) {
		this.node = node;
		replaceRestrictedColumnIds(columnIds);
		replaceRestrictedOutsourcingColumnIds(isColumnizedOutsourcing()
				? node.seedOutsourcingColumnIds : Collections.<Integer>emptyList());
		this.activeCutIds = new ArrayList<Integer>(node.activeCutIds);
		this.lastSolution = null;
		clearDuals();
	}

	public void setBranchImpliedPenaltyObjectiveMode(boolean enabled) {
		if (branchImpliedPenaltyObjectiveMode != enabled) {
			branchImpliedPenaltyObjectiveMode = enabled;
			lastSolution = null;
		}
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

	/** 当前内部机器列是否已经进入 restricted master。 */
	public boolean isRestrictedColumnActive(int columnId) {
		return restrictedColumnIdSet.contains(Integer.valueOf(columnId));
	}

	/** 当前外包列是否已经进入 restricted master。 */
	public boolean isRestrictedOutsourcingColumnActive(int columnId) {
		return restrictedOutsourcingColumnIdSet.contains(Integer.valueOf(columnId));
	}

	private void replaceRestrictedColumnIds(List<Integer> columnIds) {
		restrictedColumnIds = new ArrayList<Integer>(columnIds);
		restrictedColumnIdSet = new HashSet<Integer>(columnIds);
	}

	private void replaceRestrictedOutsourcingColumnIds(List<Integer> columnIds) {
		restrictedOutsourcingColumnIds = new ArrayList<Integer>(columnIds);
		restrictedOutsourcingColumnIdSet = new HashSet<Integer>(columnIds);
	}

	public Set<Integer> getPositiveOutsourcingColumnIds() {
		if (!isColumnizedOutsourcing() || outsourceColumnById == null) {
			return Collections.emptySet();
		}
		HashSet<Integer> positive = new HashSet<Integer>();
		for (int columnId : restrictedOutsourcingColumnIds) {
			if (isPositiveCurrentOutsourcingColumn(columnId)) {
				positive.add(Integer.valueOf(columnId));
			}
		}
		return positive;
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
		if (!enabled) {
			this.allRowFeasibilityRepairMode = false;
		}
		this.lastSolution = null;
	}

	/** 2026-07-01: strong branching 域筛列 repair 使用全行 slack，而旧 repair 仍只 slack 当前分支行。 */
	public void setAllRowFeasibilityRepairMode(boolean enabled) {
		this.allRowFeasibilityRepairMode = enabled;
		if (enabled) {
			this.feasibilityRepairMode = true;
		}
		this.lastSolution = null;
	}

	public boolean isFeasibilityRepairMode() {
		return feasibilityRepairMode;
	}

	/** 2026-07-14: 在建模前由 PC 按当前 incumbent 设置，避免 repair dual 进入 PWLF BigM 区间。 */
	public void setRepairObjectivePenalty(double penalty) {
		this.repairObjectivePenalty = penalty;
		this.lastSolution = null;
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
		if (pricingDualOverride != null) {
			return pricingDualOverride.jobDual[job];
		}
		return jobDual[job];
	}

	/** @return 机器数量约束 dual；每条内部列的系数为 1。 */
	public double getMachineDual() {
		if (pricingDualOverride != null) {
			return pricingDualOverride.machineDual;
		}
		return machineDual;
	}

	public double getOutsourcingColumnDual() {
		if (pricingDualOverride != null) {
			return pricingDualOverride.outsourcingColumnDual;
		}
		return outsourcingColumnDual;
	}

	public double getOutsourcingMembershipDual(int job) {
		if (job < 1 || job >= outsourcingMembershipDual.length) {
			return 0.0;
		}
		if (pricingDualOverride != null && job < pricingDualOverride.outsourcingMembershipDual.length) {
			return pricingDualOverride.outsourcingMembershipDual[job];
		}
		return outsourcingMembershipDual[job];
	}

	/** @return arc 分支约束 dual；没有对应约束时为 0。 */
	public double getArcDual(int from, int to) {
		if (from < 0 || from >= arcDual.length || to < 0 || to >= arcDual[from].length) {
			return 0.0;
		}
		if (pricingDualOverride != null) {
			return pricingDualOverride.arcDual[from][to];
		}
		return arcDual[from][to];
	}

	/**
	 * 2026-06-21: dual stabilization 只改变 pricing 看到的 dual，不改变主问题真实 dual。
	 * SRI cut dual 暂不混合，保持用当前 LP 真实值，避免 cut state 与稳定化中心不同步。
	 */
	public PricingDualSnapshot captureTruePricingDuals() {
		return new PricingDualSnapshot(jobDual, machineDual, outsourcingColumnDual, outsourcingMembershipDual, arcDual);
	}

	public void setPricingDualOverride(PricingDualSnapshot snapshot) {
		this.pricingDualOverride = snapshot == null ? null : snapshot.copy();
	}

	public void clearPricingDualOverride() {
		this.pricingDualOverride = null;
	}

	public boolean hasPricingDualOverride() {
		return pricingDualOverride != null;
	}

	public double computeReducedCost(TWETColumn column, PricingDualSnapshot dual) {
		double reducedCost = column.getCost() - dual.machineDual;
		for (int job = column.getJobs().nextSetBit(1); job > 0 && job <= data.n;
				job = column.getJobs().nextSetBit(job + 1)) {
			int count = column.getJobVisitCount(job);
			reducedCost -= count * dual.jobDual[job];
		}
		int sink = node == null ? data.n + 1 : node.sinkId();
		if (!column.getSequence().isEmpty()) {
			int prev = 0;
			for (int job : column.getSequence()) {
				reducedCost -= arcDualValue(dual, prev, job);
				prev = job;
			}
			reducedCost -= arcDualValue(dual, prev, sink);
		}
		if (activeSubsetRowPricingCutIds != null && activeSubsetRowPricingDuals != null) {
			for (int i = 0; i < activeSubsetRowPricingCutIds.size(); i++) {
				TWETCut cut = cutPool.getCut(activeSubsetRowPricingCutIds.get(i).intValue());
				double coefficient = subsetRowCoefficient(column, cut);
				if (coefficient > 0.0) {
					reducedCost -= coefficient * activeSubsetRowPricingDuals.get(i).doubleValue();
				}
			}
		}
		return reducedCost;
	}

	private double arcDualValue(PricingDualSnapshot dual, int from, int to) {
		if (from < 0 || from >= dual.arcDual.length || to < 0 || to >= dual.arcDual[from].length) {
			return 0.0;
		}
		return dual.arcDual[from][to];
	}

	public double computeReducedCost(TWETOutsourcingColumn column, PricingDualSnapshot dual) {
		double reducedCost = column.getCost() - dual.outsourcingColumnDual;
		for (int job : column.getJobs()) {
			reducedCost -= dual.jobDual[job];
			if (job < dual.outsourcingMembershipDual.length) {
				reducedCost -= dual.outsourcingMembershipDual[job];
			}
		}
		return reducedCost;
	}

	public int addColumns(List<Integer> columnIds) {
		int added = 0;
		for (int id : columnIds) {
			Integer value = Integer.valueOf(id);
			if (restrictedColumnIdSet.add(value)) {
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

	public Pool.ColumnUpdate addOrImproveColumn(TWETColumn column) {
		Pool.ColumnUpdate update = pool.addOrImproveColumn(column.getSequence(), column.getCost(),
				column.getSource(), column.isSeedColumn());
		if (update.improvedCost && cplex != null && objective != null) {
			try {
				updateCurrentColumnObjective(update.columnId);
			} catch (IloException ex) {
				throw new IllegalStateException("Failed to update improved column " + update.columnId
						+ " objective coefficient", ex);
			}
		}
		return update;
	}

	public void addCuts(List<Integer> cutIds) {
		for (int id : cutIds) {
			Integer value = Integer.valueOf(id);
			if (!activeCutIds.contains(value)) {
				activeCutIds.add(value);
			}
		}
	}

	public int removeCuts(List<Integer> cutIds) {
		int removed = 0;
		for (int id : cutIds) {
			if (activeCutIds.remove(Integer.valueOf(id))) {
				removed++;
			}
		}
		if (removed > 0) {
			lastSolution = null;
			clearPricingDualOverride();
		}
		return removed;
	}

	public TWETMasterSolution solveRelaxation() {
		clearPricingDualOverride();
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
		clearPricingDualOverride();
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

	public void closeModel() {
		if (cplex != null) {
			cplex.end();
			cplex = null;
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
		// 2026-06-23: 默认保持单线程；诊断退化 dual 差异时允许临时恢复 CPLEX 默认线程。
		int cplexThreads = Integer.getInteger("twet.bpc.cplexThreads", 1);
		if (cplexThreads > 0) {
			cplex.setParam(IloCplex.Param.Threads, cplexThreads);
		}
		objective = null;
		lambdaByColumnId = new HashMap<Integer, IloNumVar>();
		branchImpliedPenaltyColumnIds = new HashSet<Integer>();
		outsourceColumnById = new HashMap<Integer, IloNumVar>();
		repairSlackVars = new ArrayList<IloNumVar>();
		arcBranchRanges = new HashMap<Long, IloRange>();
		outsourcingMembershipBranchRanges = new HashMap<Integer, IloRange>();
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
		buildOutsourcingMembershipBranchConstraints();
		buildArcBranchConstraints();
		buildAdjacencyBranchConstraints();
		if (!isColumnizedOutsourcing()) {
			buildSubsetRowCutConstraints();
			buildOutsourcingTariffConstraints();
		}
		if (feasibilityRepairMode) {
			if (allRowFeasibilityRepairMode) {
				addAllRowFeasibilitySlacks();
			} else {
				addFeasibilitySlacks();
			}
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
			int columnId = restrictedColumnIds.get(idx).intValue();
			obj.addTerm(internalColumnObjectiveCost(columnId), lambdaVars[idx]);
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

	/**
	 * 2026-07-04: 列化外包 membership 分支用显式 master row 表达，而不是在构造 LP 前删父节点正值列。
	 * 这样初始 LP/repair 的不可行性只来自新增分支行或后续 pricing 不能补列，不会被 seed 预筛污染。
	 */
	private void buildOutsourcingMembershipBranchConstraints() throws IloException {
		if (!isColumnizedOutsourcing()) {
			return;
		}
		for (int job = 1; job <= data.n; job++) {
			byte state = node.getOutsourcingJobState(job);
			if (state == Node.OUTSOURCE_FREE) {
				continue;
			}
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int idx = 0; idx < restrictedOutsourcingColumnIds.size(); idx++) {
				TWETOutsourcingColumn column = outsourcingPool.getColumn(restrictedOutsourcingColumnIds.get(idx)
						.intValue());
				if (column.containsJob(job)) {
					expr.addTerm(1.0, outsourceColumnVars[idx]);
				}
			}
			IloRange range = state == Node.OUTSOURCE_REQUIRED ? cplex.addGe(expr, 1.0, "requiredOutsource_" + job)
					: cplex.addLe(expr, 0.0, "forbiddenOutsource_" + job);
			outsourcingMembershipBranchRanges.put(Integer.valueOf(job), range);
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
		for (int id : columnIds) {
			Integer value = Integer.valueOf(id);
			if (restrictedOutsourcingColumnIdSet.add(value)) {
				restrictedOutsourcingColumnIds.add(value);
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
	 * 2026-07-01: 强分支实验 repair。先按 child 域筛列后，当前 RMP 可能已经不满足覆盖、机器数或分支行，
	 * 因此这里给所有已保存的核心约束行按有限上下界加 slack。目标仍保留真实列成本并给 slack 大惩罚，
	 * 这样和现有 pricing engine 的 reduced-cost 口径一致；旧 repair 仍只 slack 当前新分支行。
	 */
	private void addAllRowFeasibilitySlacks() throws IloException {
		double penalty = repairObjectivePenalty;
		if (coverRanges != null) {
			for (int job = 1; job < coverRanges.length; job++) {
				addRangeRepairSlacks(coverRanges[job], "coverSlack_" + job, penalty);
			}
		}
		addRangeRepairSlacks(machineRange, "machineSlack", penalty);
		addRangeRepairSlacks(outsourcingColumnCountRange, "outsourcingColumnCountSlack", penalty);
		for (Map.Entry<Long, IloRange> entry : arcBranchRanges.entrySet()) {
			addRangeRepairSlacks(entry.getValue(), "arcSlack_" + entry.getKey(), penalty);
		}
		for (Map.Entry<Integer, IloRange> entry : outsourcingMembershipBranchRanges.entrySet()) {
			addRangeRepairSlacks(entry.getValue(), "outsourcingMembershipSlack_" + entry.getKey(), penalty);
		}
		for (Map.Entry<Long, IloRange> entry : adjacencyBranchRanges.entrySet()) {
			addRangeRepairSlacks(entry.getValue(), "adjacencySlack_" + entry.getKey(), penalty);
		}
		for (Map.Entry<Integer, IloRange> entry : subsetRowCutRanges.entrySet()) {
			addRangeRepairSlacks(entry.getValue(), "subsetRowSlack_" + entry.getKey(), penalty);
		}
		if (tariffActiveBounds != null) {
			for (int i = 0; i < tariffActiveBounds.length; i++) {
				addRangeRepairSlacks(tariffActiveBounds[i], "tariffActiveSlack_" + i, penalty);
			}
		}
		if (tariffBranchRanges != null) {
			for (int i = 0; i < tariffBranchRanges.length; i++) {
				addRangeRepairSlacks(tariffBranchRanges[i], "tariffBranchSlack_" + i, penalty);
			}
		}
	}

	private void addRangeRepairSlacks(IloRange range, String name, double penalty) throws IloException {
		if (range == null) {
			return;
		}
		double lb = range.getLB();
		double ub = range.getUB();
		if (isFiniteRangeBound(lb)) {
			addRepairSlack(range, 1.0, name + "_lb", penalty);
		}
		if (isFiniteRangeBound(ub)) {
			addRepairSlack(range, -1.0, name + "_ub", penalty);
		}
	}

	private boolean isFiniteRangeBound(double value) {
		return Double.isFinite(value) && Math.abs(value) < 1.0e20;
	}

	/**
	 * 2026-05-18: 子节点 repair LP 只给“当前新分支行”加人工 slack。
	 * coverage 如果不可行，应由 pricing/外包列修复；repair slack 只用于产生当前分支行的引导 dual。
	 */
	private void addFeasibilitySlacks() throws IloException {
		double penalty = repairObjectivePenalty;
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
		} else if (type == Node.REPAIR_OUTSOURCING_FORBIDDEN || type == Node.REPAIR_OUTSOURCING_REQUIRED) {
			IloRange range = outsourcingMembershipBranchRanges.get(Integer.valueOf(node.getRepairFrom()));
			if (range != null) {
				double coeff = type == Node.REPAIR_OUTSOURCING_REQUIRED ? 1.0 : -1.0;
				addRepairSlack(range, coeff, "outsourcingMembershipSlack_" + node.getRepairFrom(), penalty);
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
		IloColumn cplexColumn = cplex.column(objective, internalColumnObjectiveCost(columnId));
		cplexColumn = cplexColumn.and(cplex.column(machineRange, 1.0));
		for (int job = column.getJobs().nextSetBit(1); job > 0 && job <= data.n;
				job = column.getJobs().nextSetBit(job + 1)) {
			int coefficient = column.getJobVisitCount(job);
			cplexColumn = cplexColumn.and(cplex.column(coverRanges[job], coefficient));
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

	private void updateCurrentColumnObjective(int columnId) throws IloException {
		if (lambdaByColumnId == null || objective == null) {
			return;
		}
		IloNumVar var = lambdaByColumnId.get(Integer.valueOf(columnId));
		if (var != null) {
			cplex.setLinearCoef(objective, var, internalColumnObjectiveCost(columnId));
			lastSolution = null;
		}
	}

	/** strong branching phase-1 中，按配置把 branch-implied 竞争列从建模开始按有限 repair penalty 处理。 */
	private double internalColumnObjectiveCost(int columnId) {
		TWETColumn column = pool.getColumn(columnId);
		if (isBranchImpliedPenaltyColumn(column)) {
			if (branchImpliedPenaltyColumnIds != null) {
				branchImpliedPenaltyColumnIds.add(Integer.valueOf(columnId));
			}
			return repairObjectivePenalty;
		}
		return column.getCost();
	}

	private boolean isBranchImpliedPenaltyColumn(TWETColumn column) {
		if (!branchImpliedPenaltyObjectiveMode || node == null) {
			return false;
		}
		if (node.usesBranchImpliedForbiddenArc(column)) {
			return true;
		}
		if (!isColumnizedOutsourcing()) {
			return false;
		}
		for (int job = column.getJobs().nextSetBit(1); job > 0 && job <= data.n;
				job = column.getJobs().nextSetBit(job + 1)) {
			if (node.getOutsourcingJobState(job) == Node.OUTSOURCE_REQUIRED) {
				return true;
			}
		}
		return false;
	}

	public boolean hasPositiveBranchImpliedPenaltyColumn() {
		return branchImpliedPenaltyValue() > VALUE_TOLERANCE;
	}

	public double branchImpliedPenaltyValue() {
		if (cplex == null || lambdaByColumnId == null || branchImpliedPenaltyColumnIds == null
				|| branchImpliedPenaltyColumnIds.isEmpty()) {
			return 0.0;
		}
		double total = 0.0;
		for (Integer columnId : branchImpliedPenaltyColumnIds) {
			IloNumVar var = lambdaByColumnId.get(columnId);
			if (var == null) {
				continue;
			}
			try {
				double value = cplex.getValue(var);
				if (Utility.compareGt(value, VALUE_TOLERANCE)) {
					total += value;
				}
			} catch (IloException ex) {
				return Utility.big_M;
			}
		}
		return total;
	}

	private void addOutsourcingColumnToCurrentModel(int columnId) throws IloException {
		TWETOutsourcingColumn column = outsourcingPool.getColumn(columnId);
		IloColumn cplexColumn = cplex.column(objective, column.getCost());
		cplexColumn = cplexColumn.and(cplex.column(outsourcingColumnCountRange, 1.0));
		for (int job = column.getJobSet().nextSetBit(1); job > 0 && job <= data.n;
				job = column.getJobSet().nextSetBit(job + 1)) {
			cplexColumn = cplexColumn.and(cplex.column(coverRanges[job], 1.0));
		}
		for (Map.Entry<Integer, IloRange> entry : outsourcingMembershipBranchRanges.entrySet()) {
			if (column.containsJob(entry.getKey().intValue())) {
				cplexColumn = cplexColumn.and(cplex.column(entry.getValue(), 1.0));
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
			boolean compatible = isColumnCompatible(column);
			if (isPositiveCurrentColumn(columnId)) {
				// 2026-07-04: seed 筛选只做规模控制，不能删掉当前可行 LP 的正值列。
				// 分支隐含竞争列若需要排斥，由 strong-trial 的 M 目标处理。
				selected.add(Integer.valueOf(columnId));
				continue;
			}
			if (!compatible) {
				continue;
			}
			double reducedCost = getColumnReducedCost(columnId);
			if (Utility.compareLt(reducedCost, reducedCostAllowance)) {
				candidates.add(new ColumnReducedCost(columnId, reducedCost));
			}
		}
		Collections.sort(candidates, new Comparator<ColumnReducedCost>() {
			@Override
			public int compare(ColumnReducedCost a, ColumnReducedCost b) {
				int reducedCostCompare = Double.compare(a.reducedCost, b.reducedCost);
				if (reducedCostCompare != 0) {
					return reducedCostCompare;
				}
				return Integer.compare(a.columnId, b.columnId);
			}
		});

		for (int i = 0; i < candidates.size() && selected.size() < maxColumns; i++) {
			selected.add(Integer.valueOf(candidates.get(i).columnId));
		}
		if (!selected.isEmpty()) {
			replaceRestrictedColumnIds(selected);
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
			if (isPositiveCurrentOutsourcingColumn(columnId)) {
				selected.add(Integer.valueOf(columnId));
				continue;
			}
			if (!node.isOutsourcingColumnCompatible(column)) {
				continue;
			}
			double reducedCost = getOutsourcingColumnReducedCost(columnId);
			if (Utility.compareLt(reducedCost, reducedCostAllowance)) {
				candidates.add(new ColumnReducedCost(columnId, reducedCost));
			}
		}
		Collections.sort(candidates, new Comparator<ColumnReducedCost>() {
			@Override
			public int compare(ColumnReducedCost a, ColumnReducedCost b) {
				int reducedCostCompare = Double.compare(a.reducedCost, b.reducedCost);
				if (reducedCostCompare != 0) {
					return reducedCostCompare;
				}
				return Integer.compare(a.columnId, b.columnId);
			}
		});
		for (int i = 0; i < candidates.size() && selected.size() < maxColumns; i++) {
			selected.add(Integer.valueOf(candidates.get(i).columnId));
		}
		if (!selected.isEmpty()) {
			replaceRestrictedOutsourcingColumnIds(selected);
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
		for (Map.Entry<Integer, IloRange> entry : outsourcingMembershipBranchRanges.entrySet()) {
			outsourcingMembershipDual[entry.getKey().intValue()] = cplex.getDual(entry.getValue());
		}
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
		clearPricingDualOverride();
		for (int i = 0; i < jobDual.length; i++) {
			jobDual[i] = 0.0;
		}
		machineDual = 0.0;
		outsourcingColumnDual = 0.0;
		for (int i = 0; i < outsourcingMembershipDual.length; i++) {
			outsourcingMembershipDual[i] = 0.0;
		}
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

	public static final class PricingDualSnapshot {
		final double[] jobDual;
		final double machineDual;
		final double outsourcingColumnDual;
		final double[] outsourcingMembershipDual;
		final double[][] arcDual;

		PricingDualSnapshot(double[] jobDual, double machineDual, double outsourcingColumnDual,
				double[] outsourcingMembershipDual, double[][] arcDual) {
			this.jobDual = copy(jobDual);
			this.machineDual = machineDual;
			this.outsourcingColumnDual = outsourcingColumnDual;
			this.outsourcingMembershipDual = copy(outsourcingMembershipDual);
			this.arcDual = copy(arcDual);
		}

		public PricingDualSnapshot copy() {
			return new PricingDualSnapshot(jobDual, machineDual, outsourcingColumnDual, outsourcingMembershipDual,
					arcDual);
		}

		public static PricingDualSnapshot blend(PricingDualSnapshot current, PricingDualSnapshot center,
				double currentWeight) {
			double centerWeight = 1.0 - currentWeight;
			double[] blendedJob = new double[current.jobDual.length];
			for (int i = 0; i < blendedJob.length; i++) {
				blendedJob[i] = currentWeight * current.jobDual[i] + centerWeight * center.jobDual[i];
			}
			double[] blendedOutsourcingMembership = new double[current.outsourcingMembershipDual.length];
			for (int i = 0; i < blendedOutsourcingMembership.length; i++) {
				blendedOutsourcingMembership[i] = currentWeight * current.outsourcingMembershipDual[i]
						+ centerWeight * center.outsourcingMembershipDual[i];
			}
			double[][] blendedArc = new double[current.arcDual.length][];
			for (int i = 0; i < current.arcDual.length; i++) {
				blendedArc[i] = new double[current.arcDual[i].length];
				for (int j = 0; j < current.arcDual[i].length; j++) {
					blendedArc[i][j] =
							currentWeight * current.arcDual[i][j] + centerWeight * center.arcDual[i][j];
				}
			}
			return new PricingDualSnapshot(blendedJob,
					currentWeight * current.machineDual + centerWeight * center.machineDual,
					currentWeight * current.outsourcingColumnDual + centerWeight * center.outsourcingColumnDual,
					blendedOutsourcingMembership, blendedArc);
		}

		private static double[] copy(double[] values) {
			double[] copied = new double[values.length];
			System.arraycopy(values, 0, copied, 0, values.length);
			return copied;
		}

		private static double[][] copy(double[][] values) {
			double[][] copied = new double[values.length][];
			for (int i = 0; i < values.length; i++) {
				copied[i] = copy(values[i]);
			}
			return copied;
		}
	}

}
