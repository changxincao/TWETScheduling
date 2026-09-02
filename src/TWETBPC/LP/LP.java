package TWETBPC.LP;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.BP.AggregateArcBranchConstraint;
import TWETBPC.CUT.SubsetRowCutEvaluator;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.Model.TWETOutsourcingColumn;
import TWETBPC.Util.PackedBitSet;
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
	private final boolean shareColumnPattern;
	private final boolean buildCoverageRowsByColumn;
	private final boolean boundedReducedCostColumnSelection;
	private final boolean restrictedColumnFilterTimingEnabled;
	private final boolean subsetRowPostingEnabled;
	private final boolean subsetRowBuildTimingEnabled;
	private SubsetRowColumnPostingIndex subsetRowPostingIndex;
	private Node node;
	private ArrayList<Integer> restrictedColumnIds;
	private HashSet<Integer> restrictedColumnIdSet;
	private ArrayList<Integer> restrictedOutsourcingColumnIds;
	private HashSet<Integer> restrictedOutsourcingColumnIdSet;
	private ArrayList<Integer> activeCutIds;
	private TWETMasterSolution lastSolution;
	/** 最近一次有效 LP 解中取正值的列化外包列；避免 strong sides 重复查询 CPLEX。 */
	private Set<Integer> positiveOutsourcingColumnIds;

	private IloCplex cplex;
	private IloObjective objective;
	private IloNumVar[] lambdaVars;
	private HashMap<Integer, IloNumVar> lambdaByColumnId;
	private HashSet<Integer> branchImpliedPenaltyColumnIds;
	private boolean branchImpliedPenaltyObjectiveMode;
	/** Pure Phase-I strong repair: legal columns cost 0 and artificial terms cost 1. */
	private boolean feasibilityPhaseOneObjectiveMode;
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
	private ArrayList<IloRange> aggregateArcBranchRanges;
	private HashMap<Integer, IloRange> subsetRowCutRanges;
	private ArrayList<Integer> activeSubsetRowPricingCutIds;
	private ArrayList<Double> activeSubsetRowPricingDuals;
	/** 当前真实 LP 解中 dual 严格为 0.0/-0.0、可无重解删除的 subset-row cuts。 */
	private ArrayList<Integer> exactZeroSubsetRowCutIds;
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
	/** Pricing dual components multiplied by the right-hand sides of their master rows. */
	private double pricingDualRhsObjective;
	private PricingDualSnapshot pricingDualOverride;
	/** 2026-07-28: 仅在诊断开关开启时记录 RMP 建模、求解和结果读取的真实耗时。 */
	private boolean masterLpPhaseTimingEnabled;
	private boolean masterLpPhaseTimingRecorded;
	private boolean masterLpPhaseTimingRebuild;
	private long masterLpPhaseTotalNanos;
	private long masterLpPhaseBuildNanos;
	private long masterLpPhaseModelInitNanos;
	private long masterLpPhaseVariablesObjectiveNanos;
	private long masterLpPhaseCoverageNanos;
	private long masterLpPhaseCoverageRowsNanos;
	private long masterLpPhaseMachineRowsNanos;
	private long masterLpPhaseBranchRowsNanos;
	private long masterLpPhaseCutRowsNanos;
	private long masterLpPhaseRepairRowsNanos;
	private long masterLpPhaseSolveNanos;
	private long masterLpPhaseExtractNanos;
	/** 最近一次 solveRelaxation() 中真实 buildModel() 的墙钟耗时。 */
	private long lastMasterLpModelBuildNanos;

	public LP(Data data, Pool pool, CutPool cutPool) {
		this(data, pool, cutPool, new TWETBPCConfig(), new OutsourcingPool(data));
	}

	public LP(Data data, Pool pool, CutPool cutPool, TWETBPCConfig config, OutsourcingPool outsourcingPool) {
		this.data = data;
		this.pool = pool;
		this.cutPool = cutPool;
		this.config = config;
		this.outsourcingPool = outsourcingPool;
		this.shareColumnPattern = Boolean.parseBoolean(System.getProperty("twet.bpc.shareColumnPattern", "true"));
		this.buildCoverageRowsByColumn =
				Boolean.parseBoolean(System.getProperty("twet.bpc.buildCoverageRowsByColumn", "true"));
		this.boundedReducedCostColumnSelection =
				Boolean.parseBoolean(System.getProperty("twet.bpc.boundedReducedCostColumnSelection", "true"));
		this.restrictedColumnFilterTimingEnabled =
				Boolean.parseBoolean(System.getProperty("twet.bpc.restrictedColumnFilterTiming", "false"));
		this.subsetRowPostingEnabled =
				Boolean.parseBoolean(System.getProperty("twet.bpc.subsetRowPosting", "true"));
		this.subsetRowBuildTimingEnabled =
				Boolean.parseBoolean(System.getProperty("twet.bpc.subsetRowBuildTiming", "false"));
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
		this.feasibilityPhaseOneObjectiveMode = false;
		this.positiveOutsourcingColumnIds = Collections.emptySet();
	}

	public void construct(Node node, List<Integer> columnIds) {
		this.node = node;
		replaceRestrictedColumnIds(columnIds);
		replaceRestrictedOutsourcingColumnIds(isColumnizedOutsourcing()
				? node.seedOutsourcingColumnIds : Collections.<Integer>emptyList());
		this.activeCutIds = new ArrayList<Integer>(node.activeCutIds);
		this.lastSolution = null;
		this.positiveOutsourcingColumnIds = Collections.emptySet();
		clearDuals();
	}

	public void setBranchImpliedPenaltyObjectiveMode(boolean enabled) {
		if (branchImpliedPenaltyObjectiveMode != enabled) {
			branchImpliedPenaltyObjectiveMode = enabled;
			lastSolution = null;
		}
	}

	public void setFeasibilityPhaseOneObjectiveMode(boolean enabled) {
		if (feasibilityPhaseOneObjectiveMode != enabled) {
			feasibilityPhaseOneObjectiveMode = enabled;
			lastSolution = null;
		}
	}

	public boolean isFeasibilityPhaseOneObjectiveMode() {
		return feasibilityPhaseOneObjectiveMode;
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
		// position posting 与 restricted 顺序绑定；筛列或切换 node 后必须按新顺序重建。
		subsetRowPostingIndex = null;
	}

	private void replaceRestrictedOutsourcingColumnIds(List<Integer> columnIds) {
		restrictedOutsourcingColumnIds = new ArrayList<Integer>(columnIds);
		restrictedOutsourcingColumnIdSet = new HashSet<Integer>(columnIds);
	}

	public Set<Integer> getPositiveOutsourcingColumnIds() {
		if (!isColumnizedOutsourcing() || lastSolution == null || positiveOutsourcingColumnIds.isEmpty()) {
			return Collections.emptySet();
		}
		return new HashSet<Integer>(positiveOutsourcingColumnIds);
	}

	public boolean isColumnizedOutsourcing() {
		return config.useColumnizedOutsourcing();
	}

	/**
	 * 当前 pricing snapshot 是否覆盖 smoothing 所需的全部可变成本口径。
	 * 列化外包的 dual 已显式进入 snapshot；显式外包只有在没有任何可外包任务时才不会随 job dual 改变。
	 */
	public boolean supportsDualStabilizationSnapshot() {
		if (isColumnizedOutsourcing()) {
			return true;
		}
		for (int job = 1; job <= data.n; job++) {
			if (outsourcingPool.isOutsourceable(job)) {
				return false;
			}
		}
		return true;
	}

	public List<Integer> getActiveCutIds() {
		return activeCutIds;
	}

	/**
	 * 将当前正式 RMP 最终仍激活的 cuts 写回节点，供随后创建的所有 child 继承。
	 * LP 内 cut 集会在 cut loop 中增删，不能继续使用建模前的 node 快照。
	 */
	public void syncActiveCutsToNode() {
		node.activeCutIds = new ArrayList<Integer>(activeCutIds);
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

	/** @return 本次有效真实 LP 解中 dual 严格为零的 subset-row cut IDs。 */
	List<Integer> getExactZeroSubsetRowCutIds() {
		if (exactZeroSubsetRowCutIds == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(exactZeroSubsetRowCutIds);
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
		return new PricingDualSnapshot(jobDual, machineDual, outsourcingColumnDual, outsourcingMembershipDual, arcDual,
				pricingDualRhsObjective);
	}

	/**
	 * 把 exact pricing 的 reduced-cost 证书吸收到机器数/外包列数 dual 中，得到对完整列族可行的 in-point。
	 * 最小 reduced cost 为负时，把修正量加入对应 upper-bound dual。这样即使 machine range 的净 dual
	 * 跨过 0，也保留原 lower/upper dual 分解，snapshot 向量和 RHS objective 始终代表同一个 dual 点。
	 */
	public PricingDualSnapshot makePricingDualFeasible(PricingDualSnapshot dual,
			double certifiedInternalReducedCost, double certifiedOutsourcingReducedCost) {
		double internalShift = Double.isFinite(certifiedInternalReducedCost)
				? Math.min(0.0, certifiedInternalReducedCost) : 0.0;
		double outsourcingShift = isColumnizedOutsourcing() && Double.isFinite(certifiedOutsourcingReducedCost)
				? Math.min(0.0, certifiedOutsourcingReducedCost) : 0.0;
		double shiftedMachineDual = dual.machineDual + internalShift;
		double shiftedOutsourcingDual = dual.outsourcingColumnDual + outsourcingShift;
		int machineUpperBound = node == null ? 0 : Math.max(0, node.maxMachineCount);
		double rhsObjective = dual.rhsObjective + machineUpperBound * internalShift + outsourcingShift;
		return new PricingDualSnapshot(dual.jobDual, shiftedMachineDual, shiftedOutsourcingDual,
				dual.outsourcingMembershipDual, dual.arcDual, rhsObjective);
	}

	/** @return 当前 pricing 实际使用的 dual；稳定化开启时返回 override，否则返回真实 LP dual。 */
	public PricingDualSnapshot captureEffectivePricingDuals() {
		return pricingDualOverride == null ? captureTruePricingDuals() : pricingDualOverride.copy();
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
		double reducedCost = (feasibilityPhaseOneObjectiveMode ? 0.0 : column.getCost()) - dual.machineDual;
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
				double coefficient = SubsetRowCutEvaluator.coefficient(cut, column.getSequence(), data.n);
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
		double reducedCost = (feasibilityPhaseOneObjectiveMode ? 0.0 : column.getCost())
				- dual.outsourcingColumnDual;
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
		ArrayList<IloNumVar> addedVars = cplex != null && objective != null
				? new ArrayList<IloNumVar>(columnIds.size()) : null;
		for (int id : columnIds) {
			Integer value = Integer.valueOf(id);
			if (restrictedColumnIdSet.add(value)) {
				int position = restrictedColumnIds.size();
				restrictedColumnIds.add(value);
				added++;
				if (addedVars != null) {
					try {
						addedVars.add(addColumnToCurrentModel(id));
					} catch (IloException ex) {
						throw new IllegalStateException("Failed to add column " + id + " to current RMP", ex);
					}
				}
				if (subsetRowPostingIndex != null) {
					subsetRowPostingIndex.append(position, pool.getColumn(id));
				}
			}
		}
		if (addedVars != null && !addedVars.isEmpty()) {
			lambdaVars = append(lambdaVars, addedVars);
		}
		if (added > 0) {
			lastSolution = null;
			positiveOutsourcingColumnIds = Collections.emptySet();
		}
		return added;
	}
	public Pool.ColumnUpdate addOrImproveColumn(TWETColumn column) {
		Pool.ColumnUpdate update = shareColumnPattern ? pool.addOrImproveColumn(column)
				: pool.addOrImproveColumn(column.getSequence(), column.getCost(), column.getSource(),
						column.isSeedColumn());
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
		if (!cutIds.isEmpty()) {
			ArrayList<Integer> retainedCutIds = new ArrayList<Integer>(activeCutIds.size() + cutIds.size());
			retainedCutIds.addAll(activeCutIds);
			retainedCutIds.addAll(cutIds);
			cutPool.reclaimInactiveSubsetRowCoefficientPages(retainedCutIds);
		}
		SubsetRowBuildStats stats = subsetRowBuildTimingEnabled ? new SubsetRowBuildStats() : null;
		boolean changed = false;
		for (int id : cutIds) {
			Integer value = Integer.valueOf(id);
			if (activeCutIds.contains(value)) {
				continue;
			}
			if (cplex != null && objective != null) {
				try {
					addSubsetRowCutToCurrentModel(id, stats);
				} catch (IloException ex) {
					throw new IllegalStateException("Failed to add cut " + id + " to current RMP", ex);
				}
			}
			activeCutIds.add(value);
			changed = true;
		}
		traceSubsetRowBuild("add", stats);
		if (changed) {
			lastSolution = null;
			clearPricingDualOverride();
		}
	}

	public int removeCuts(List<Integer> cutIds) {
		return removeCuts(cutIds, false);
	}

	/**
	 * 删除当前真实 LP dual 严格为零的 subset-row cuts，同时保留已经闭合的 primal/dual 快照。
	 * 2026-07-30: pricing-active 使用数值容差，而该接口只接受 CPLEX 返回的 0.0/-0.0；
	 * near-zero 非零行必须保留，不能在不重解的情况下继续沿用旧 bound。
	 */
	public int removeZeroDualCutsPreservingCurrentSolution(List<Integer> cutIds) {
		if (lastSolution == null || lastSolution.getStatus() != TWETMasterStatus.LP_RELAXATION) {
			throw new IllegalStateException("Zero-dual cuts require a current solved LP relaxation");
		}
		HashSet<Integer> exactZeroCutIds = new HashSet<Integer>(getExactZeroSubsetRowCutIds());
		for (int id : cutIds) {
			TWETCut cut = cutPool.getCut(id);
			if (cut.getType() != TWETCutType.SUBSET_ROW || !exactZeroCutIds.contains(Integer.valueOf(id))) {
				throw new IllegalArgumentException(
						"Only exact-zero-dual subset-row cuts can preserve the current LP solution: " + id);
			}
		}
		return removeCuts(cutIds, true);
	}

	private int removeCuts(List<Integer> cutIds, boolean preserveCurrentSolution) {
		long startNanos = subsetRowBuildTimingEnabled ? System.nanoTime() : 0L;
		int removed = 0;
		for (int id : cutIds) {
			Integer value = Integer.valueOf(id);
			if (!activeCutIds.contains(value)) {
				continue;
			}
			IloRange range = subsetRowCutRanges == null ? null : subsetRowCutRanges.get(value);
			if (range != null) {
				if (cplex != null) {
					try {
						cplex.remove(range);
					} catch (IloException ex) {
						throw new IllegalStateException("Failed to remove cut " + id + " from current RMP", ex);
					}
				}
				subsetRowCutRanges.remove(value);
			}
			activeCutIds.remove(value);
			if (exactZeroSubsetRowCutIds != null) {
				exactZeroSubsetRowCutIds.remove(value);
			}
			removed++;
		}
		if (subsetRowBuildTimingEnabled && !cutIds.isEmpty()) {
			System.out.println(String.format(java.util.Locale.US,
					"[SriRowRemoveTiming] requested=%d removed=%d preserveSolution=%s timeMs=%.3f",
					cutIds.size(), removed, Boolean.toString(preserveCurrentSolution),
					(System.nanoTime() - startNanos) / 1.0e6));
		}
		if (removed > 0) {
			clearPricingDualOverride();
			if (!preserveCurrentSolution) {
				lastSolution = null;
			}
		}
		return removed;
	}

	private long beginMasterLpPhaseTiming(boolean rebuild) {
		masterLpPhaseTimingEnabled = Boolean.getBoolean("twet.bpc.masterLpPhaseTiming");
		masterLpPhaseTimingRecorded = false;
		masterLpPhaseTimingRebuild = rebuild;
		masterLpPhaseTotalNanos = 0L;
		masterLpPhaseBuildNanos = 0L;
		masterLpPhaseModelInitNanos = 0L;
		masterLpPhaseVariablesObjectiveNanos = 0L;
		masterLpPhaseCoverageNanos = 0L;
		masterLpPhaseCoverageRowsNanos = 0L;
		masterLpPhaseMachineRowsNanos = 0L;
		masterLpPhaseBranchRowsNanos = 0L;
		masterLpPhaseCutRowsNanos = 0L;
		masterLpPhaseRepairRowsNanos = 0L;
		masterLpPhaseSolveNanos = 0L;
		masterLpPhaseExtractNanos = 0L;
		return masterLpPhaseTimingEnabled ? System.nanoTime() : 0L;
	}

	private long masterLpTimingStart() {
		return masterLpPhaseTimingEnabled ? System.nanoTime() : 0L;
	}

	private long masterLpTimingElapsed(long startNanos) {
		return masterLpPhaseTimingEnabled ? System.nanoTime() - startNanos : 0L;
	}

	private void finishMasterLpPhaseTiming(long totalStartNanos) {
		if (masterLpPhaseTimingEnabled) {
			masterLpPhaseTotalNanos = System.nanoTime() - totalStartNanos;
			masterLpPhaseTimingRecorded = true;
		}
	}

	/**
	 * 返回最近一次 RMP 调用的分段计时。只在显式诊断开关开启时生成字符串，
	 * 避免常规求解承担格式化和日志分配开销。
	 */
	String masterLpPhaseTimingSummary(String phase, long outerElapsedNanos) {
		if (!masterLpPhaseTimingRecorded) {
			return null;
		}
		long accountedNanos = masterLpPhaseBuildNanos + masterLpPhaseSolveNanos + masterLpPhaseExtractNanos;
		long otherNanos = Math.max(0L, masterLpPhaseTotalNanos - accountedNanos);
		return String.format(java.util.Locale.US,
				"phase=%s mode=%s outerMs=%.3f totalMs=%.3f buildMs=%.3f modelInitMs=%.3f "
						+ "variablesObjectiveMs=%.3f coverageMs=%.3f coverageRowsMs=%.3f machineRowsMs=%.3f "
						+ "branchRowsMs=%.3f cutRowsMs=%.3f "
						+ "repairRowsMs=%.3f solveMs=%.3f extractMs=%.3f otherMs=%.3f",
				phase, masterLpPhaseTimingRebuild ? "rebuild" : "resolve", outerElapsedNanos / 1.0e6,
				masterLpPhaseTotalNanos / 1.0e6, masterLpPhaseBuildNanos / 1.0e6,
				masterLpPhaseModelInitNanos / 1.0e6, masterLpPhaseVariablesObjectiveNanos / 1.0e6,
				masterLpPhaseCoverageNanos / 1.0e6, masterLpPhaseCoverageRowsNanos / 1.0e6,
				masterLpPhaseMachineRowsNanos / 1.0e6, masterLpPhaseBranchRowsNanos / 1.0e6,
				masterLpPhaseCutRowsNanos / 1.0e6, masterLpPhaseRepairRowsNanos / 1.0e6,
				masterLpPhaseSolveNanos / 1.0e6, masterLpPhaseExtractNanos / 1.0e6, otherNanos / 1.0e6);
	}

	public TWETMasterSolution solveRelaxation() {
		clearPricingDualOverride();
		lastMasterLpModelBuildNanos = 0L;
		long totalStartNanos = beginMasterLpPhaseTiming(true);
		if (node == null) {
			lastSolution = new TWETMasterSolution(TWETMasterStatus.INFEASIBLE, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Node not constructed");
			finishMasterLpPhaseTiming(totalStartNanos);
			return lastSolution;
		}

		try {
			long modelBuildStart = System.nanoTime();
			try {
				buildModel();
			} finally {
				lastMasterLpModelBuildNanos = System.nanoTime() - modelBuildStart;
			}
			cplex.setOut(null);
			return solveCurrentModel("Restricted master LP solved");
		} catch (IloException ex) {
			clearDuals();
			lastSolution = new TWETMasterSolution(TWETMasterStatus.NOT_SOLVED, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Restricted master error: " + ex.getMessage());
			return lastSolution;
		} finally {
			finishMasterLpPhaseTiming(totalStartNanos);
		}
	}

	long getLastMasterLpModelBuildNanos() {
		return lastMasterLpModelBuildNanos;
	}

	public TWETMasterSolution resolveCurrentModel() {
		clearPricingDualOverride();
		if (cplex == null) {
			return solveRelaxation();
		}
		long totalStartNanos = beginMasterLpPhaseTiming(false);
		try {
			return solveCurrentModel("Restricted master LP resolved");
		} catch (IloException ex) {
			clearDuals();
			lastSolution = new TWETMasterSolution(TWETMasterStatus.NOT_SOLVED, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Restricted master resolve error: " + ex.getMessage());
			return lastSolution;
		} finally {
			finishMasterLpPhaseTiming(totalStartNanos);
		}
	}

	public void closeModel() {
		if (cplex != null) {
			cplex.end();
			cplex = null;
		}
		subsetRowPostingIndex = null;
		positiveOutsourcingColumnIds = Collections.emptySet();
	}

	private TWETMasterSolution solveCurrentModel(String successMessage) throws IloException {
		positiveOutsourcingColumnIds = Collections.emptySet();
		long solveStartNanos = masterLpTimingStart();
		boolean solved;
		try {
			solved = cplex.solve();
		} finally {
			masterLpPhaseSolveNanos += masterLpTimingElapsed(solveStartNanos);
		}
		long extractStartNanos = masterLpTimingStart();
		if (!solved) {
			clearDuals();
			// 2026-07-23: 只有 CPLEX 明确证明 infeasible 才能关闭节点；异常和未知状态必须向上传播。
			IloCplex.Status cplexStatus = cplex.getStatus();
			TWETMasterStatus status = cplexStatus == IloCplex.Status.Infeasible
					? TWETMasterStatus.INFEASIBLE : TWETMasterStatus.NOT_SOLVED;
			lastSolution = new TWETMasterSolution(status, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Restricted master not solved: " + cplexStatus);
			masterLpPhaseExtractNanos += masterLpTimingElapsed(extractStartNanos);
			return lastSolution;
		}

		readDuals();
		LinkedHashMap<Integer, Double> columnValues = readColumnValues();
		OutsourcingPrimalRead outsourcingPrimal = readOutsourcingValues();
		double[] segmentValues = readOutsourceSegmentValues();
		boolean integer = isIntegerSolution(columnValues, outsourcingPrimal.jobValues,
				outsourcingPrimal.columnValues, segmentValues);
		String message = feasibilityRepairMode && !isNoSlack() ? successMessage + " with positive artificial slack"
				: successMessage;
		lastSolution = new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION, columnValues,
				outsourcingPrimal.jobValues,
				segmentValues, cplex.getObjValue(), integer, message);
		masterLpPhaseExtractNanos += masterLpTimingElapsed(extractStartNanos);
		return lastSolution;
	}

	private void buildModel() throws IloException {
		long buildStartNanos = masterLpTimingStart();
		long phaseStartNanos = buildStartNanos;
		if (cplex != null) {
			cplex.end();
		}
		cplex = new IloCplex();
		// 2026-06-23: 默认保持单线程；诊断退化 dual 差异时允许临时恢复 CPLEX 默认线程。
		int cplexThreads = Integer.getInteger("twet.bpc.cplexThreads", 1);
		if (cplexThreads > 0) {
			cplex.setParam(IloCplex.Param.Threads, cplexThreads);
		}
		configureRootAlgorithm();
		objective = null;
		lambdaByColumnId = new HashMap<Integer, IloNumVar>();
		branchImpliedPenaltyColumnIds = new HashSet<Integer>();
		outsourceColumnById = new HashMap<Integer, IloNumVar>();
		repairSlackVars = new ArrayList<IloNumVar>();
		arcBranchRanges = new HashMap<Long, IloRange>();
		outsourcingMembershipBranchRanges = new HashMap<Integer, IloRange>();
		adjacencyBranchRanges = new HashMap<Long, IloRange>();
		aggregateArcBranchRanges = new ArrayList<IloRange>();
		outsourcingColumnCountRange = null;
		subsetRowCutRanges = new HashMap<Integer, IloRange>();
		subsetRowPostingIndex = null;
		activeSubsetRowPricingCutIds = new ArrayList<Integer>();
		activeSubsetRowPricingDuals = new ArrayList<Double>();
		exactZeroSubsetRowCutIds = new ArrayList<Integer>();
		outsourcingTariffSegments = isColumnizedOutsourcing() ? new ArrayList<TariffSegment>()
				: collectOutsourcingTariffSegments();
		masterLpPhaseModelInitNanos += masterLpTimingElapsed(phaseStartNanos);

		phaseStartNanos = masterLpTimingStart();
		buildVariables();
		buildObjective();
		masterLpPhaseVariablesObjectiveNanos += masterLpTimingElapsed(phaseStartNanos);

		phaseStartNanos = masterLpTimingStart();
		buildCoverageConstraints();
		masterLpPhaseCoverageRowsNanos += masterLpTimingElapsed(phaseStartNanos);
		phaseStartNanos = masterLpTimingStart();
		buildMachineConstraint();
		masterLpPhaseMachineRowsNanos += masterLpTimingElapsed(phaseStartNanos);
		masterLpPhaseCoverageNanos =
				masterLpPhaseCoverageRowsNanos + masterLpPhaseMachineRowsNanos;

		phaseStartNanos = masterLpTimingStart();
		buildOutsourcingMembershipBranchConstraints();
		buildArcBranchConstraints();
		buildAdjacencyBranchConstraints();
		buildAggregateArcBranchConstraints();
		masterLpPhaseBranchRowsNanos += masterLpTimingElapsed(phaseStartNanos);

		phaseStartNanos = masterLpTimingStart();
		// SRI 只含内部调度列，两种外包建模方式都必须恢复 active cut 行。
		buildSubsetRowCutConstraints();
		if (!isColumnizedOutsourcing()) {
			buildOutsourcingTariffConstraints();
		}
		masterLpPhaseCutRowsNanos += masterLpTimingElapsed(phaseStartNanos);

		phaseStartNanos = masterLpTimingStart();
		if (feasibilityRepairMode) {
			if (allRowFeasibilityRepairMode) {
				addAllRowFeasibilitySlacks();
			} else {
				addFeasibilitySlacks();
			}
		}
		masterLpPhaseRepairRowsNanos += masterLpTimingElapsed(phaseStartNanos);
		masterLpPhaseBuildNanos += masterLpTimingElapsed(buildStartNanos);
	}

	/** 配置 LP 算法；Barrier 保留 CPLEX 默认 crossover，正式 pricing 仍可读取 dual。 */
	private void configureRootAlgorithm() throws IloException {
		String algorithm = config.cplexRootAlgorithm == null ? "auto"
				: config.cplexRootAlgorithm.trim().toLowerCase();
		if (algorithm.isEmpty() || "auto".equals(algorithm)) {
			cplex.setParam(IloCplex.Param.RootAlgorithm, IloCplex.Algorithm.Auto);
			return;
		}
		if ("barrier".equals(algorithm)) {
			cplex.setParam(IloCplex.Param.RootAlgorithm, IloCplex.Algorithm.Barrier);
			return;
		}
		throw new IllegalArgumentException("Unsupported CPLEX root algorithm: " + config.cplexRootAlgorithm
				+ "; expected auto or barrier");
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
				obj.addTerm(outsourcingColumnObjectiveCost(column), outsourceColumnVars[idx]);
			}
			objective = cplex.addMinimize(obj);
			return;
		}
		for (int l = 0; l < outsourcingTariffSegments.size(); l++) {
			TariffSegment seg = outsourcingTariffSegments.get(l);
			obj.addTerm(feasibilityPhaseOneObjectiveMode ? 0.0 : seg.slope, outsourceSegmentBaseline[l]);
			obj.addTerm(feasibilityPhaseOneObjectiveMode ? 0.0 : seg.intercept, outsourceSegmentActive[l]);
		}
		objective = cplex.addMinimize(obj);
	}

	private void buildCoverageConstraints() throws IloException {
		if (buildCoverageRowsByColumn) {
			buildCoverageConstraintsByColumn();
			return;
		}
		buildCoverageConstraintsByJob();
	}

	/** 保留原始逐 job 扫描路径，供严格 A/B 使用。 */
	private void buildCoverageConstraintsByJob() throws IloException {
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

	/**
	 * 2026-07-28: 每条内部列和列化外包列都只读取一次，并把非零覆盖系数分发到各 job 行。
	 * 每行的 term 仍按 restricted column 顺序加入，模型系数和输入顺序与逐 job 路径一致。
	 */
	private void buildCoverageConstraintsByColumn() throws IloException {
		coverRanges = new IloRange[data.n + 1];
		IloLinearNumExpr[] expressions = new IloLinearNumExpr[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			expressions[job] = cplex.linearNumExpr();
		}
		for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
			TWETColumn column = pool.getColumn(restrictedColumnIds.get(idx).intValue());
			PackedBitSet jobs = column.getJobs();
			for (int job = jobs.nextSetBit(1); job >= 1 && job <= data.n; job = jobs.nextSetBit(job + 1)) {
				expressions[job].addTerm(column.getJobVisitCount(job), lambdaVars[idx]);
			}
		}
		if (isColumnizedOutsourcing()) {
			for (int idx = 0; idx < restrictedOutsourcingColumnIds.size(); idx++) {
				TWETOutsourcingColumn column =
						outsourcingPool.getColumn(restrictedOutsourcingColumnIds.get(idx).intValue());
				for (int job : column.getJobs()) {
					expressions[job].addTerm(1.0, outsourceColumnVars[idx]);
				}
			}
		}
		for (int job = 1; job <= data.n; job++) {
			IloLinearNumExpr expr = expressions[job];
			if (isColumnizedOutsourcing()) {
				coverRanges[job] = cplex.addGe(expr, 1.0, "cover_" + job);
			} else {
				expr.addTerm(1.0, outsourceVars[job]);
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

	private void buildAggregateArcBranchConstraints() throws IloException {
		List<AggregateArcBranchConstraint> constraints = node.getAggregateArcConstraints();
		for (int constraintIndex = 0; constraintIndex < constraints.size(); constraintIndex++) {
			AggregateArcBranchConstraint constraint = constraints.get(constraintIndex);
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int columnIndex = 0; columnIndex < restrictedColumnIds.size(); columnIndex++) {
				TWETColumn column = pool.getColumn(restrictedColumnIds.get(columnIndex).intValue());
				int coefficient = constraint.coefficient(column, node.sinkId());
				if (coefficient != 0) {
					expr.addTerm(coefficient, lambdaVars[columnIndex]);
				}
			}
			IloRange range = constraint.isLowerBound()
					? cplex.addGe(expr, constraint.getRhs(), "aggregateArcLower_" + constraintIndex)
					: cplex.addLe(expr, constraint.getRhs(), "aggregateArcUpper_" + constraintIndex);
			aggregateArcBranchRanges.add(range);
		}
	}

	public int addOutsourcingColumns(List<Integer> columnIds) {
		if (!isColumnizedOutsourcing()) {
			return 0;
		}
		int added = 0;
		ArrayList<IloNumVar> addedVars = cplex != null && objective != null
				? new ArrayList<IloNumVar>(columnIds.size()) : null;
		for (int id : columnIds) {
			Integer value = Integer.valueOf(id);
			if (restrictedOutsourcingColumnIdSet.add(value)) {
				restrictedOutsourcingColumnIds.add(value);
				added++;
				if (addedVars != null) {
					try {
						addedVars.add(addOutsourcingColumnToCurrentModel(id));
					} catch (IloException ex) {
						throw new IllegalStateException("Failed to add outsourcing column " + id + " to current RMP",
								ex);
					}
				}
			}
		}
		if (addedVars != null && !addedVars.isEmpty()) {
			outsourceColumnVars = append(outsourceColumnVars, addedVars);
		}
		if (added > 0) {
			lastSolution = null;
			positiveOutsourcingColumnIds = Collections.emptySet();
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
		cutPool.reclaimInactiveSubsetRowCoefficientPages(activeCutIds);
		SubsetRowBuildStats stats = subsetRowBuildTimingEnabled ? new SubsetRowBuildStats() : null;
		for (int cutId : activeCutIds) {
			addSubsetRowCutToCurrentModel(cutId, stats);
		}
		traceSubsetRowBuild("rebuild", stats);
	}

	/**
	 * 向当前 RMP 增量加入一条 SRI。已有列的系数在这里一次性写入；后续新列由
	 * {@link #addColumnToCurrentModel(int)} 补上该行系数，避免 cut loop 重建整个模型。
	 */
	private void addSubsetRowCutToCurrentModel(int cutId, SubsetRowBuildStats stats) throws IloException {
		long totalStart = stats == null ? 0L : System.nanoTime();
		Integer key = Integer.valueOf(cutId);
		if (subsetRowCutRanges.containsKey(key)) {
			return;
		}
		TWETCut cut = cutPool.getCut(cutId);
		if (cut.getType() != TWETCutType.SUBSET_ROW) {
			return;
		}

		BitSet candidatePositions = null;
		if (subsetRowPostingEnabled) {
			long postingStart = stats == null ? 0L : System.nanoTime();
			ensureSubsetRowPostingIndex();
			candidatePositions = subsetRowPostingIndex.candidatePositions(cut);
			if (stats != null) {
				stats.postingNanos += System.nanoTime() - postingStart;
			}
		}

		IloLinearNumExpr expr = cplex.linearNumExpr();
		int fullColumnCount = restrictedColumnIds.size();
		int candidateCount = candidatePositions == null ? fullColumnCount : candidatePositions.cardinality();
		if (stats != null) {
			stats.rows++;
			stats.fullColumns += fullColumnCount;
			stats.candidateColumns += candidateCount;
		}
		for (int idx = candidatePositions == null ? 0 : candidatePositions.nextSetBit(0);
				idx >= 0 && idx < fullColumnCount;
				idx = candidatePositions == null ? idx + 1 : candidatePositions.nextSetBit(idx + 1)) {
			int columnId = restrictedColumnIds.get(idx).intValue();
			TWETColumn column = pool.getColumn(columnId);
			long coefficientStart = stats == null ? 0L : System.nanoTime();
			int coefficient = subsetRowCoefficient(cutId, columnId, column, cut, stats);
			if (stats != null) {
				stats.coefficientNanos += System.nanoTime() - coefficientStart;
			}
			if (coefficient > 0) {
				long termStart = stats == null ? 0L : System.nanoTime();
				expr.addTerm(coefficient, lambdaVars[idx]);
				if (stats != null) {
					stats.termNanos += System.nanoTime() - termStart;
					stats.nonzeroTerms++;
				}
			}
		}
		// 普通 SRI 是 0/1 系数；limited-memory SRI 允许更大的整数系数。
		long rangeStart = stats == null ? 0L : System.nanoTime();
		IloRange range = cplex.addLe(expr, cut.getRhs(), "subsetRow_" + cutId);
		if (stats != null) {
			stats.rangeNanos += System.nanoTime() - rangeStart;
			stats.totalNanos += System.nanoTime() - totalStart;
		}
		subsetRowCutRanges.put(key, range);
	}

	private int subsetRowCoefficient(int cutId, int columnId, TWETColumn column, TWETCut cut) {
		return subsetRowCoefficient(cutId, columnId, column, cut, null);
	}

	private int subsetRowCoefficient(int cutId, int columnId, TWETColumn column, TWETCut cut,
			SubsetRowBuildStats stats) {
		int cached = cutPool.getSubsetRowCoefficient(cutId, columnId);
		if (cached >= 0) {
			if (stats != null) {
				stats.cacheHits++;
			}
			return cached;
		}
		if (stats != null) {
			stats.cacheMisses++;
		}
		int coefficient = SubsetRowCutEvaluator.coefficient(cut, column.getSequence(), data.n);
		cutPool.cacheSubsetRowCoefficient(cutId, columnId, coefficient);
		return coefficient;
	}

	private void ensureSubsetRowPostingIndex() {
		if (subsetRowPostingIndex == null) {
			subsetRowPostingIndex = new SubsetRowColumnPostingIndex(data.n);
			subsetRowPostingIndex.rebuild(restrictedColumnIds, pool);
		}
		if (subsetRowPostingIndex.size() != restrictedColumnIds.size()) {
			throw new IllegalStateException("SRI posting index is not aligned with restricted columns.");
		}
	}

	private void traceSubsetRowBuild(String phase, SubsetRowBuildStats stats) {
		if (!subsetRowBuildTimingEnabled || stats == null || stats.rows == 0) {
			return;
		}
		double candidateRatio = stats.fullColumns == 0L ? 0.0
				: (double) stats.candidateColumns / stats.fullColumns;
		System.out.println(String.format(java.util.Locale.US,
				"[SriRowBuildTiming] phase=%s posting=%s rows=%d full=%d candidates=%d ratio=%.6f "
						+ "cacheHitMiss=%d/%d nonzero=%d postingMs=%.3f coefficientMs=%.3f "
						+ "termMs=%.3f rangeMs=%.3f totalMs=%.3f",
				phase, Boolean.toString(subsetRowPostingEnabled), stats.rows, stats.fullColumns,
				stats.candidateColumns, candidateRatio, stats.cacheHits, stats.cacheMisses,
				stats.nonzeroTerms, stats.postingNanos / 1.0e6, stats.coefficientNanos / 1.0e6,
				stats.termNanos / 1.0e6, stats.rangeNanos / 1.0e6, stats.totalNanos / 1.0e6));
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
		double penalty = repairArtificialObjectiveCost();
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
		for (int index = 0; index < aggregateArcBranchRanges.size(); index++) {
			addRangeRepairSlacks(aggregateArcBranchRanges.get(index), "aggregateArcSlack_" + index, penalty);
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
		double penalty = repairArtificialObjectiveCost();
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
		} else if (type == Node.REPAIR_AGGREGATE_ARC_UPPER || type == Node.REPAIR_AGGREGATE_ARC_LOWER) {
			int index = node.getRepairAggregateConstraintIndex();
			if (index >= 0 && index < aggregateArcBranchRanges.size()) {
				double coeff = type == Node.REPAIR_AGGREGATE_ARC_LOWER ? 1.0 : -1.0;
				addRepairSlack(aggregateArcBranchRanges.get(index), coeff,
						"aggregateArcBranchSlack_" + index, penalty);
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

	private IloNumVar addColumnToCurrentModel(int columnId) throws IloException {
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
		List<AggregateArcBranchConstraint> aggregateConstraints = node.getAggregateArcConstraints();
		for (int index = 0; index < aggregateArcBranchRanges.size(); index++) {
			int coefficient = aggregateConstraints.get(index).coefficient(column, node.sinkId());
			if (coefficient != 0) {
				cplexColumn = cplexColumn.and(cplex.column(aggregateArcBranchRanges.get(index), coefficient));
			}
		}
		for (Map.Entry<Integer, IloRange> entry : subsetRowCutRanges.entrySet()) {
			TWETCut cut = cutPool.getCut(entry.getKey().intValue());
			double coefficient = subsetRowCoefficient(entry.getKey().intValue(), columnId, column, cut);
			if (coefficient > 0.0) {
				cplexColumn = cplexColumn.and(cplex.column(entry.getValue(), coefficient));
			}
		}
		IloNumVar var = cplex.numVar(cplexColumn, 0.0, Double.MAX_VALUE, "lambda_" + columnId);
		lambdaByColumnId.put(Integer.valueOf(columnId), var);
		return var;
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
			return feasibilityPhaseOneObjectiveMode ? 1.0 : repairObjectivePenalty;
		}
		return feasibilityPhaseOneObjectiveMode ? 0.0 : column.getCost();
	}

	private double outsourcingColumnObjectiveCost(TWETOutsourcingColumn column) {
		return feasibilityPhaseOneObjectiveMode ? 0.0 : column.getCost();
	}

	private double repairArtificialObjectiveCost() {
		return feasibilityPhaseOneObjectiveMode ? 1.0 : repairObjectivePenalty;
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

	/** Remove branch-implied competitors before restoring the true-cost RMP. */
	public int removeBranchImpliedPenaltyColumnsFromRestrictedSet() {
		if (node == null || restrictedColumnIds.isEmpty()) {
			return 0;
		}
		ArrayList<Integer> kept = new ArrayList<Integer>(restrictedColumnIds.size());
		int removed = 0;
		for (Integer columnId : restrictedColumnIds) {
			TWETColumn column = pool.getColumn(columnId.intValue());
			if (isBranchImpliedPenaltyColumn(column)) {
				removed++;
			} else {
				kept.add(columnId);
			}
		}
		if (removed > 0) {
			replaceRestrictedColumnIds(kept);
			lastSolution = null;
		}
		return removed;
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

	private IloNumVar addOutsourcingColumnToCurrentModel(int columnId) throws IloException {
		TWETOutsourcingColumn column = outsourcingPool.getColumn(columnId);
		IloColumn cplexColumn = cplex.column(objective, outsourcingColumnObjectiveCost(column));
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
		return var;
	}

	private IloNumVar[] append(IloNumVar[] vars, List<IloNumVar> addedVars) {
		IloNumVar[] expanded = new IloNumVar[vars.length + addedVars.size()];
		System.arraycopy(vars, 0, expanded, 0, vars.length);
		for (int i = 0; i < addedVars.size(); i++) {
			expanded[vars.length + i] = addedVars.get(i);
		}
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
	 * 2026-07-28: 正值列直接复用本次求解快照，其余 reduced cost 一次批量读取，避免逐变量访问 CPLEX。
	 */
	public void resetRestrictedColumnsByCurrentReducedCost(int maxColumns, double reducedCostAllowance) {
		if (cplex == null || lambdaByColumnId == null) {
			return;
		}
		final long startNanos = restrictedColumnFilterTimingEnabled ? System.nanoTime() : 0L;
		final int inputColumnCount = restrictedColumnIds.size();
		if (lastSolution == null || lastSolution.getStatus() != TWETMasterStatus.LP_RELAXATION) {
			throw new IllegalStateException("Restricted-column filtering requires the current LP solution.");
		}
		Set<Integer> positiveColumnIds = lastSolution.getColumnValues().keySet();
		final double[] reducedCosts;
		try {
			reducedCosts = cplex.getReducedCosts(lambdaVars);
		} catch (IloException ex) {
			throw new IllegalStateException("Unable to read restricted-column reduced costs in bulk.", ex);
		}
		if (reducedCosts.length != inputColumnCount) {
			throw new IllegalStateException("Restricted-column IDs and reduced costs are not aligned.");
		}
		ArrayList<Integer> selected = new ArrayList<Integer>();
		ArrayList<ColumnReducedCost> candidates =
				boundedReducedCostColumnSelection ? null : new ArrayList<ColumnReducedCost>();
		PriorityQueue<ColumnReducedCost> boundedCandidates =
				boundedReducedCostColumnSelection ? newReducedCostCandidateHeap(maxColumns) : null;
		int positiveCount = 0;
		int incompatibleCount = 0;
		for (int index = 0; index < inputColumnCount; index++) {
			int columnId = restrictedColumnIds.get(index).intValue();
			boolean positive = positiveColumnIds.contains(Integer.valueOf(columnId));
			if (positive) {
				// 2026-07-04: seed 筛选只做规模控制，不能删掉当前可行 LP 的正值列。
				// 分支隐含竞争列若需要排斥，由 strong-trial 的 M 目标处理。
				selected.add(Integer.valueOf(columnId));
				positiveCount++;
				continue;
			}
			TWETColumn column = pool.getColumn(columnId);
			boolean compatible = isColumnCompatible(column);
			if (!compatible) {
				incompatibleCount++;
				continue;
			}
			double reducedCost = reducedCosts[index];
			if (Utility.compareLt(reducedCost, reducedCostAllowance)) {
				addReducedCostCandidate(candidates, boundedCandidates, maxColumns, columnId, reducedCost);
			}
		}
		candidates = orderedReducedCostCandidates(candidates, boundedCandidates,
				Math.max(0, maxColumns - selected.size()));

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
		if (restrictedColumnFilterTimingEnabled) {
			System.out.println(String.format(java.util.Locale.US,
					"[RestrictedColumnFilterTiming] input=%d positive=%d incompatible=%d selected=%d timeMs=%.3f",
					inputColumnCount, positiveCount, incompatibleCount, restrictedColumnIds.size(),
					(System.nanoTime() - startNanos) / 1.0e6));
		}
	}

	private void resetRestrictedOutsourcingColumnsByCurrentReducedCost(int maxColumns, double reducedCostAllowance) {
		if (outsourceColumnById == null) {
			return;
		}
		final int inputColumnCount = restrictedOutsourcingColumnIds.size();
		final double[] reducedCosts;
		try {
			reducedCosts = cplex.getReducedCosts(outsourceColumnVars);
		} catch (IloException ex) {
			throw new IllegalStateException("Unable to read outsourcing-column reduced costs in bulk.", ex);
		}
		if (reducedCosts.length != inputColumnCount) {
			throw new IllegalStateException("Restricted outsourcing-column IDs and reduced costs are not aligned.");
		}
		ArrayList<Integer> selected = new ArrayList<Integer>();
		ArrayList<ColumnReducedCost> candidates =
				boundedReducedCostColumnSelection ? null : new ArrayList<ColumnReducedCost>();
		PriorityQueue<ColumnReducedCost> boundedCandidates =
				boundedReducedCostColumnSelection ? newReducedCostCandidateHeap(maxColumns) : null;
		for (int index = 0; index < inputColumnCount; index++) {
			int columnId = restrictedOutsourcingColumnIds.get(index).intValue();
			TWETOutsourcingColumn column = outsourcingPool.getColumn(columnId);
			if (positiveOutsourcingColumnIds.contains(Integer.valueOf(columnId))) {
				selected.add(Integer.valueOf(columnId));
				continue;
			}
			if (!node.isOutsourcingColumnCompatible(column)) {
				continue;
			}
			double reducedCost = reducedCosts[index];
			if (Utility.compareLt(reducedCost, reducedCostAllowance)) {
				addReducedCostCandidate(candidates, boundedCandidates, maxColumns, columnId, reducedCost);
			}
		}
		candidates = orderedReducedCostCandidates(candidates, boundedCandidates,
				Math.max(0, maxColumns - selected.size()));
		for (int i = 0; i < candidates.size() && selected.size() < maxColumns; i++) {
			selected.add(Integer.valueOf(candidates.get(i).columnId));
		}
		if (!selected.isEmpty()) {
			replaceRestrictedOutsourcingColumnIds(selected);
			lastSolution = null;
		}
	}

	private static PriorityQueue<ColumnReducedCost> newReducedCostCandidateHeap(int limit) {
		return new PriorityQueue<ColumnReducedCost>(Math.max(1, limit), new Comparator<ColumnReducedCost>() {
			@Override
			public int compare(ColumnReducedCost a, ColumnReducedCost b) {
				return compareColumnReducedCost(b, a);
			}
		});
	}

	private static void addReducedCostCandidate(ArrayList<ColumnReducedCost> candidates,
			PriorityQueue<ColumnReducedCost> boundedCandidates, int limit, int columnId, double reducedCost) {
		if (boundedCandidates == null) {
			candidates.add(new ColumnReducedCost(columnId, reducedCost));
			return;
		}
		if (limit <= 0) {
			return;
		}
		if (boundedCandidates.size() < limit) {
			boundedCandidates.add(new ColumnReducedCost(columnId, reducedCost));
			return;
		}
		if (compareColumnReducedCost(columnId, reducedCost, boundedCandidates.peek()) < 0) {
			boundedCandidates.poll();
			boundedCandidates.add(new ColumnReducedCost(columnId, reducedCost));
		}
	}

	private static ArrayList<ColumnReducedCost> orderedReducedCostCandidates(
			ArrayList<ColumnReducedCost> candidates, PriorityQueue<ColumnReducedCost> boundedCandidates, int limit) {
		if (boundedCandidates != null) {
			while (boundedCandidates.size() > limit) {
				boundedCandidates.poll();
			}
			candidates = new ArrayList<ColumnReducedCost>(boundedCandidates);
		}
		Collections.sort(candidates, new Comparator<ColumnReducedCost>() {
			@Override
			public int compare(ColumnReducedCost a, ColumnReducedCost b) {
				return compareColumnReducedCost(a, b);
			}
		});
		return candidates;
	}

	private static int compareColumnReducedCost(ColumnReducedCost a, ColumnReducedCost b) {
		int reducedCostCompare = Double.compare(a.reducedCost, b.reducedCost);
		return reducedCostCompare != 0 ? reducedCostCompare : Integer.compare(a.columnId, b.columnId);
	}

	private static int compareColumnReducedCost(int columnId, double reducedCost, ColumnReducedCost other) {
		int reducedCostCompare = Double.compare(reducedCost, other.reducedCost);
		return reducedCostCompare != 0 ? reducedCostCompare : Integer.compare(columnId, other.columnId);
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

	/** 仅严格的 IEEE 0.0/-0.0 支持删除 row 后复用当前 LP 最优解。 */
	static boolean isExactZeroSubsetRowDual(double dual) {
		return dual == 0.0;
	}

	private void readDuals() throws IloException {
		clearDuals();
		double[] coverageDuals = cplex.getDuals(coverRanges, 1, data.n);
		for (int job = 1; job <= data.n; job++) {
			jobDual[job] = coverageDuals[job - 1];
			pricingDualRhsObjective += jobDual[job];
		}
		machineDual = cplex.getDual(machineRange);
		if (Utility.compareGt(machineDual, VALUE_TOLERANCE)) {
			pricingDualRhsObjective += machineDual * node.minMachineCount;
		} else if (Utility.compareLt(machineDual, -VALUE_TOLERANCE)) {
			pricingDualRhsObjective += machineDual * node.maxMachineCount;
		}
		outsourcingColumnDual = isColumnizedOutsourcing() && outsourcingColumnCountRange != null
				? cplex.getDual(outsourcingColumnCountRange) : 0.0;
		pricingDualRhsObjective += outsourcingColumnDual;
		if (!outsourcingMembershipBranchRanges.isEmpty()) {
			ArrayList<Map.Entry<Integer, IloRange>> outsourcingEntries =
					new ArrayList<Map.Entry<Integer, IloRange>>(outsourcingMembershipBranchRanges.entrySet());
			double[] outsourcingDuals = readRangeDuals(outsourcingEntries);
			for (int index = 0; index < outsourcingEntries.size(); index++) {
				Map.Entry<Integer, IloRange> entry = outsourcingEntries.get(index);
				int job = entry.getKey().intValue();
				double dual = outsourcingDuals[index];
				outsourcingMembershipDual[job] = dual;
				if (node.getOutsourcingJobState(job) == Node.OUTSOURCE_REQUIRED) {
					pricingDualRhsObjective += dual;
				}
			}
		}
		if (!arcBranchRanges.isEmpty()) {
			ArrayList<Map.Entry<Long, IloRange>> arcEntries =
					new ArrayList<Map.Entry<Long, IloRange>>(arcBranchRanges.entrySet());
			double[] arcDuals = readRangeDuals(arcEntries);
			for (int index = 0; index < arcEntries.size(); index++) {
				Map.Entry<Long, IloRange> entry = arcEntries.get(index);
				int from = decodeFrom(entry.getKey().longValue());
				int to = decodeTo(entry.getKey().longValue());
				double dual = arcDuals[index];
				arcDual[from][to] = dual;
				if (node.getArcState(from, to) == Node.ARC_REQUIRED) {
					pricingDualRhsObjective += dual;
				}
			}
		}
		if (!adjacencyBranchRanges.isEmpty()) {
			ArrayList<Map.Entry<Long, IloRange>> adjacencyEntries =
					new ArrayList<Map.Entry<Long, IloRange>>(adjacencyBranchRanges.entrySet());
			double[] adjacencyDuals = readRangeDuals(adjacencyEntries);
			for (int index = 0; index < adjacencyEntries.size(); index++) {
				Map.Entry<Long, IloRange> entry = adjacencyEntries.get(index);
				int first = decodeFrom(entry.getKey().longValue());
				int second = decodeTo(entry.getKey().longValue());
				double dual = adjacencyDuals[index];
				arcDual[first][second] += dual;
				arcDual[second][first] += dual;
				if (node.getAdjacencyPairState(first, second) == Node.ADJACENCY_REQUIRED) {
					pricingDualRhsObjective += dual;
				}
			}
		}
		if (!aggregateArcBranchRanges.isEmpty()) {
			double[] aggregateDuals = cplex.getDuals(
					aggregateArcBranchRanges.toArray(new IloRange[aggregateArcBranchRanges.size()]));
			List<AggregateArcBranchConstraint> constraints = node.getAggregateArcConstraints();
			for (int index = 0; index < aggregateDuals.length; index++) {
				double dual = aggregateDuals[index];
				AggregateArcBranchConstraint constraint = constraints.get(index);
				constraint.addDualTo(arcDual, dual);
				pricingDualRhsObjective += dual * constraint.getRhs();
			}
		}
		if (!subsetRowCutRanges.isEmpty()) {
			ArrayList<Map.Entry<Integer, IloRange>> cutEntries =
					new ArrayList<Map.Entry<Integer, IloRange>>(subsetRowCutRanges.entrySet());
			double[] cutDuals = readRangeDuals(cutEntries);
			for (int index = 0; index < cutEntries.size(); index++) {
				Map.Entry<Integer, IloRange> entry = cutEntries.get(index);
				double dual = cutDuals[index];
				TWETCut cut = cutPool.getCut(entry.getKey().intValue());
				// 2026-09-02: pricing dual snapshot 必须包含完整的 master dual objective；
				// SRI 是非零 RHS 的 <= 行，遗漏 dual*rhs 会破坏后续稳定化使用的 dual 点语义。
				pricingDualRhsObjective += dual * cut.getRhs();
				if (Utility.compareLt(dual, -VALUE_TOLERANCE)) {
					activeSubsetRowPricingCutIds.add(entry.getKey());
					activeSubsetRowPricingDuals.add(Double.valueOf(dual));
				}
				if (isExactZeroSubsetRowDual(dual)) {
					exactZeroSubsetRowCutIds.add(entry.getKey());
				}
			}
		}
	}

	private double[] readRangeDuals(List<? extends Map.Entry<?, IloRange>> entries) throws IloException {
		if (entries.isEmpty()) {
			return new double[0];
		}
		IloRange[] ranges = new IloRange[entries.size()];
		for (int index = 0; index < entries.size(); index++) {
			ranges[index] = entries.get(index).getValue();
		}
		return cplex.getDuals(ranges);
	}

	private LinkedHashMap<Integer, Double> readColumnValues() throws IloException {
		LinkedHashMap<Integer, Double> values = new LinkedHashMap<Integer, Double>();
		if (lambdaVars.length == 0) {
			return values;
		}
		double[] primalValues = cplex.getValues(lambdaVars);
		for (int idx = 0; idx < restrictedColumnIds.size(); idx++) {
			double value = primalValues[idx];
			if (Utility.compareGt(value, VALUE_TOLERANCE)) {
				values.put(restrictedColumnIds.get(idx), Double.valueOf(value));
			}
		}
		return values;
	}

	private OutsourcingPrimalRead readOutsourcingValues() throws IloException {
		double[] values = new double[data.n + 1];
		if (isColumnizedOutsourcing()) {
			double[] columnValues = outsourceColumnVars.length == 0
					? new double[0] : cplex.getValues(outsourceColumnVars);
			HashSet<Integer> positiveColumnIds = new HashSet<Integer>();
			for (int idx = 0; idx < restrictedOutsourcingColumnIds.size(); idx++) {
				double value = columnValues[idx];
				if (Utility.compareGt(value, VALUE_TOLERANCE)) {
					positiveColumnIds.add(restrictedOutsourcingColumnIds.get(idx));
					TWETOutsourcingColumn column =
							outsourcingPool.getColumn(restrictedOutsourcingColumnIds.get(idx).intValue());
					for (int job : column.getJobs()) {
						values[job] += value;
					}
				}
			}
			positiveOutsourcingColumnIds = positiveColumnIds;
			return new OutsourcingPrimalRead(values, columnValues);
		}
		if (data.n > 0) {
			double[] jobValues = cplex.getValues(outsourceVars, 1, data.n);
			System.arraycopy(jobValues, 0, values, 1, data.n);
		}
		return new OutsourcingPrimalRead(values, new double[0]);
	}

	private double[] readOutsourceSegmentValues() throws IloException {
		if (isColumnizedOutsourcing()) {
			return new double[0];
		}
		return outsourceSegmentActive.length == 0
				? new double[0] : cplex.getValues(outsourceSegmentActive);
	}

	private boolean isIntegerSolution(Map<Integer, Double> columnValues, double[] outsourcingValues,
			double[] outsourcingColumnValues, double[] segmentValues) {
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
			for (double value : outsourcingColumnValues) {
				if (!isIntegral01(value)) {
					return false;
				}
			}
			return true;
		}
		for (double value : segmentValues) {
			if (!isIntegral01(value)) {
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
		pricingDualRhsObjective = 0.0;
		if (activeSubsetRowPricingCutIds != null) {
			activeSubsetRowPricingCutIds.clear();
		}
		if (activeSubsetRowPricingDuals != null) {
			activeSubsetRowPricingDuals.clear();
		}
		if (exactZeroSubsetRowCutIds != null) {
			exactZeroSubsetRowCutIds.clear();
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

	private static final class OutsourcingPrimalRead {
		final double[] jobValues;
		final double[] columnValues;

		OutsourcingPrimalRead(double[] jobValues, double[] columnValues) {
			this.jobValues = jobValues;
			this.columnValues = columnValues;
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

	private static final class SubsetRowBuildStats {
		int rows;
		long fullColumns;
		long candidateColumns;
		long cacheHits;
		long cacheMisses;
		long nonzeroTerms;
		long postingNanos;
		long coefficientNanos;
		long termNanos;
		long rangeNanos;
		long totalNanos;
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
		final double rhsObjective;

		PricingDualSnapshot(double[] jobDual, double machineDual, double outsourcingColumnDual,
				double[] outsourcingMembershipDual, double[][] arcDual, double rhsObjective) {
			this.jobDual = copy(jobDual);
			this.machineDual = machineDual;
			this.outsourcingColumnDual = outsourcingColumnDual;
			this.outsourcingMembershipDual = copy(outsourcingMembershipDual);
			this.arcDual = copy(arcDual);
			this.rhsObjective = rhsObjective;
		}

		public PricingDualSnapshot copy() {
			return new PricingDualSnapshot(jobDual, machineDual, outsourcingColumnDual, outsourcingMembershipDual,
					arcDual, rhsObjective);
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
					blendedOutsourcingMembership, blendedArc,
					currentWeight * current.rhsObjective + centerWeight * center.rhsObjective);
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
