package TWETBPC.LP;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Basic.Data;
import Common.Utility;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/**
 * 当前节点上的受限主问题包装器。
 */
public class LP {

	private final Data data;
	private final Pool pool;
	private final CutPool cutPool;
	private Node node;
	private ArrayList<Integer> restrictedColumnIds;
	private ArrayList<Integer> activeCutIds;
	private TWETMasterSolution lastSolution;

	public LP(Data data, Pool pool, CutPool cutPool) {
		this.data = data;
		this.pool = pool;
		this.cutPool = cutPool;
		this.restrictedColumnIds = new ArrayList<Integer>();
		this.activeCutIds = new ArrayList<Integer>();
	}

	public void construct(Node node, List<Integer> columnIds) {
		this.node = node;
		this.restrictedColumnIds = filterFeasibleColumns(columnIds);
		this.activeCutIds = new ArrayList<Integer>(node.activeCutIds);
		this.lastSolution = null;
	}

	public Node getNode() {
		return node;
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

		// TODO 2026-04-10: 真正的 RMP 建起来以后，这里需要补齐旧版高密度输出里最关键的几类信息：
		// 1. active column values，而不是现在这种隐含 1.0 的占位解；
		// 2. dual / reduced-cost 相关统计，供 DebugDual 风格输出使用；
		// 3. branch/cut 在模型里的实际约束条数和 LP 状态。
		LinkedHashMap<Integer, Double> chosen = new LinkedHashMap<Integer, Double>();
		double objective = 0.0;
		for (int id : node.incumbentColumnIds) {
			if (!restrictedColumnIds.contains(Integer.valueOf(id))) {
				continue;
			}
			TWETColumn column = pool.getColumn(id);
			if (!isColumnCompatible(column)) {
				continue;
			}
			chosen.put(Integer.valueOf(id), Double.valueOf(1.0));
			objective += column.getCost();
		}

		if (!chosen.isEmpty() && isFeasibleIncumbent(chosen)) {
			lastSolution = new TWETMasterSolution(TWETMasterStatus.PLACEHOLDER_FEASIBLE, chosen, objective, true,
					"Seed incumbent injected as placeholder master solution");
		} else {
			lastSolution = new TWETMasterSolution(TWETMasterStatus.NOT_SOLVED, new LinkedHashMap<Integer, Double>(), 0.0,
					false, "Master model scaffold created; exact LP still pending");
		}
		return lastSolution;
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
		int sink = node == null ? data.n + 1 : node.sinkId();
		List<Integer> seq = column.getSequence();
		if (!seq.isEmpty() && node != null) {
			if (node.getArcState(0, seq.get(0)) == Node.ARC_FORBIDDEN) {
				return false;
			}
			for (int i = 1; i < seq.size(); i++) {
				if (node.getArcState(seq.get(i - 1), seq.get(i)) == Node.ARC_FORBIDDEN) {
					return false;
				}
			}
			if (node.getArcState(seq.get(seq.size() - 1), sink) == Node.ARC_FORBIDDEN) {
				return false;
			}
		}
		return true;
	}

	private boolean isFeasibleIncumbent(Map<Integer, Double> chosen) {
		boolean[] covered = new boolean[data.n + 1];
		int usedColumns = 0;
		for (Map.Entry<Integer, Double> entry : chosen.entrySet()) {
			if (Utility.compareGt(Math.abs(entry.getValue().doubleValue() - 1.0), 1e-9)) {
				return false;
			}
			usedColumns++;
			for (int job : pool.getColumn(entry.getKey().intValue()).getSequence()) {
				if (covered[job]) {
					return false;
				}
				covered[job] = true;
			}
		}
		for (int job = 1; job <= data.n; job++) {
			if (!covered[job]) {
				return false;
			}
		}
		if (usedColumns < node.minMachineCount || usedColumns > node.maxMachineCount) {
			return false;
		}
		if (node.hasRequiredArcs()) {
			int sink = node.sinkId();
			for (int[] arc : node.getRequiredArcs()) {
				boolean found = false;
				for (int columnId : chosen.keySet()) {
					if (pool.getColumn(columnId).visitsArc(arc[0], arc[1], sink)) {
						found = true;
						break;
					}
				}
				if (!found) {
					return false;
				}
			}
		}
		return true;
	}

}
