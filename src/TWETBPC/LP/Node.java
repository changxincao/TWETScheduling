package TWETBPC.LP;

import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.Model.TWETColumn;

/**
 * 分支树节点状态。
 * <p>
 * 节点只保存轻量状态：机器数上下界、seed 列、cut id、arc 分支状态，以及 SP2 外包 tariff
 * segment 的分支状态。每次求解节点时，由 LP 根据这些状态重建受限主问题。
 */
public class Node implements Comparable<Node> {

	public static final byte ARC_FREE = 0;
	public static final byte ARC_FORBIDDEN = -1;
	public static final byte ARC_REQUIRED = 1;

	public static final byte SEGMENT_FREE = 0;
	public static final byte SEGMENT_FORBIDDEN = -1;
	public static final byte SEGMENT_REQUIRED = 1;

	// 2026-05-18: repairType 只记录“当前 child 新增的分支行”，用于 LP repair mode
	// 给这条行挂人工 slack。coverage 等普通主问题约束不通过这里标记。
	public static final byte REPAIR_NONE = 0;
	public static final byte REPAIR_MACHINE_UPPER = 1;
	public static final byte REPAIR_MACHINE_LOWER = 2;
	public static final byte REPAIR_ARC_FORBIDDEN = 3;
	public static final byte REPAIR_ARC_REQUIRED = 4;
	public static final byte REPAIR_TARIFF_FORBIDDEN = 5;
	public static final byte REPAIR_TARIFF_REQUIRED = 6;

	private final Data data;
	public int id;
	public int depth;
	public double pseudoCost;
	public int minMachineCount;
	public int maxMachineCount;
	public ArrayList<Integer> seedColumnIds;
	public ArrayList<Integer> incumbentColumnIds;
	public ArrayList<Integer> activeCutIds;
	private byte[][] arcState;
	private byte[] tariffSegmentState;
	// 只用于子节点首次 LP 不可行时的定向 repair；不是完整分支状态本身。
	private byte repairType;
	private int repairFrom;
	private int repairTo;
	private int repairSegment;

	public Node(Data data, List<Integer> seedColumnIds, List<Integer> incumbentColumnIds, double pseudoCost) {
		this.data = data;
		this.id = 0;
		this.depth = 0;
		this.pseudoCost = pseudoCost;
		this.minMachineCount = 0;
		this.maxMachineCount = data.m;
		this.seedColumnIds = new ArrayList<Integer>(seedColumnIds);
		this.incumbentColumnIds = new ArrayList<Integer>(incumbentColumnIds);
		this.activeCutIds = new ArrayList<Integer>();
		this.arcState = new byte[data.n + 2][data.n + 2];
		this.tariffSegmentState = new byte[countTariffSegments(data)];
		this.repairType = REPAIR_NONE;
		this.repairFrom = -1;
		this.repairTo = -1;
		this.repairSegment = -1;
	}

	public Node copy() {
		Node copy = new Node(data, seedColumnIds, incumbentColumnIds, pseudoCost);
		copy.id = id;
		copy.depth = depth;
		copy.minMachineCount = minMachineCount;
		copy.maxMachineCount = maxMachineCount;
		copy.activeCutIds = new ArrayList<Integer>(activeCutIds);
		copy.arcState = new byte[arcState.length][];
		for (int i = 0; i < arcState.length; i++) {
			copy.arcState[i] = arcState[i].clone();
		}
		copy.tariffSegmentState = tariffSegmentState.clone();
		copy.repairType = repairType;
		copy.repairFrom = repairFrom;
		copy.repairTo = repairTo;
		copy.repairSegment = repairSegment;
		return copy;
	}

	public int sinkId() {
		return data.n + 1;
	}

	public byte getArcState(int from, int to) {
		return arcState[from][to];
	}

	/**
	 * 2026-05-20: 判断一条弧是否不能用于生成列。
	 * <p>
	 * 显式分支状态仍保存在 arcState 中；Data 里的预处理不可行弧只作为全局过滤条件，不写入
	 * arcState，避免 RMP 把这些静态过滤弧误建成分支约束行。
	 */
	public boolean isArcForbidden(int from, int to) {
		return data.isPreprocessedArcForbidden(from, to) || getArcState(from, to) == ARC_FORBIDDEN;
	}

	public void forbidArc(int from, int to) {
		arcState[from][to] = ARC_FORBIDDEN;
	}

	public void requireArc(int from, int to) {
		arcState[from][to] = ARC_REQUIRED;
	}

	public void markMachineUpperRepair() {
		repairType = REPAIR_MACHINE_UPPER;
		repairFrom = repairTo = repairSegment = -1;
	}

	public void markMachineLowerRepair() {
		repairType = REPAIR_MACHINE_LOWER;
		repairFrom = repairTo = repairSegment = -1;
	}

	public void markArcRepair(int from, int to, boolean required) {
		repairType = required ? REPAIR_ARC_REQUIRED : REPAIR_ARC_FORBIDDEN;
		repairFrom = from;
		repairTo = to;
		repairSegment = -1;
	}

	public void markTariffRepair(int segment, boolean required) {
		repairType = required ? REPAIR_TARIFF_REQUIRED : REPAIR_TARIFF_FORBIDDEN;
		repairFrom = repairTo = -1;
		repairSegment = segment;
	}

	public byte getRepairType() {
		return repairType;
	}

	public int getRepairFrom() {
		return repairFrom;
	}

	public int getRepairTo() {
		return repairTo;
	}

	public int getRepairSegment() {
		return repairSegment;
	}

	public byte getTariffSegmentState(int segment) {
		if (segment < 0 || segment >= tariffSegmentState.length) {
			return SEGMENT_FREE;
		}
		return tariffSegmentState[segment];
	}

	public void forbidTariffSegment(int segment) {
		ensureTariffSegmentCapacity(segment);
		tariffSegmentState[segment] = SEGMENT_FORBIDDEN;
	}

	public void requireTariffSegment(int segment) {
		ensureTariffSegmentCapacity(segment);
		tariffSegmentState[segment] = SEGMENT_REQUIRED;
	}

	private void ensureTariffSegmentCapacity(int segment) {
		if (segment < tariffSegmentState.length) {
			return;
		}
		byte[] expanded = new byte[segment + 1];
		System.arraycopy(tariffSegmentState, 0, expanded, 0, tariffSegmentState.length);
		tariffSegmentState = expanded;
	}

	public boolean hasRequiredArcs() {
		for (int from = 0; from < arcState.length; from++) {
			for (int to = 0; to < arcState[from].length; to++) {
				if (arcState[from][to] == ARC_REQUIRED) {
					return true;
				}
			}
		}
		return false;
	}

	public List<int[]> getRequiredArcs() {
		ArrayList<int[]> arcs = new ArrayList<int[]>();
		for (int from = 0; from < arcState.length; from++) {
			for (int to = 0; to < arcState[from].length; to++) {
				if (arcState[from][to] == ARC_REQUIRED) {
					arcs.add(new int[] { from, to });
				}
			}
		}
		return arcs;
	}

	/** 判断一条列是否违反当前节点的 forbidden arc。 */
	public boolean isColumnCompatible(TWETColumn column) {
		List<Integer> seq = column.getSequence();
		if (seq.isEmpty()) {
			return true;
		}
		if (isArcForbidden(0, seq.get(0).intValue())) {
			return false;
		}
		for (int i = 1; i < seq.size(); i++) {
			if (isArcForbidden(seq.get(i - 1).intValue(), seq.get(i).intValue())) {
				return false;
			}
		}
		return !isArcForbidden(seq.get(seq.size() - 1).intValue(), sinkId());
	}

	/**
	 * 2026-05-20: 只检查 Data 预处理得到的全局不可行弧，不检查当前分支状态。
	 * <p>
	 * child 第一次 LP 仍要继承父节点列集并带新分支行求一次可行性，这一点不能被提前分支过滤破坏；
	 * 但粗硬时间窗已经证明不可行的静态弧不属于任何节点，历史列池里如果残留这类列，应在建模入口直接排除。
	 */
	public boolean isColumnPreprocessingCompatible(TWETColumn column) {
		List<Integer> seq = column.getSequence();
		if (seq.isEmpty()) {
			return true;
		}
		if (data.isPreprocessedArcForbidden(0, seq.get(0).intValue())) {
			return false;
		}
		for (int i = 1; i < seq.size(); i++) {
			if (data.isPreprocessedArcForbidden(seq.get(i - 1).intValue(), seq.get(i).intValue())) {
				return false;
			}
		}
		return !data.isPreprocessedArcForbidden(seq.get(seq.size() - 1).intValue(), sinkId());
	}

	public boolean columnCoversRequiredArc(TWETColumn column, int from, int to) {
		return column.visitsArc(from, to, sinkId());
	}

	private int countTariffSegments(Data data) {
		if (data.outsourcingCostFunction == null || data.outsourcingCostFunction.head == null) {
			return 0;
		}
		int count = 0;
		PiecewiseLinearFunction.Segment seg = data.outsourcingCostFunction.head;
		while (seg != null) {
			count++;
			seg = seg.next;
		}
		return count;
	}

	@Override
	public int compareTo(Node other) {
		if (Utility.compareLt(pseudoCost, other.pseudoCost)) {
			return -1;
		}
		if (Utility.compareGt(pseudoCost, other.pseudoCost)) {
			return 1;
		}
		return Integer.compare(id, other.id);
	}

}
