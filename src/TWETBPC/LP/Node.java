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
		return copy;
	}

	public int sinkId() {
		return data.n + 1;
	}

	public byte getArcState(int from, int to) {
		return arcState[from][to];
	}

	public void forbidArc(int from, int to) {
		arcState[from][to] = ARC_FORBIDDEN;
	}

	public void requireArc(int from, int to) {
		arcState[from][to] = ARC_REQUIRED;
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
		if (getArcState(0, seq.get(0).intValue()) == ARC_FORBIDDEN) {
			return false;
		}
		for (int i = 1; i < seq.size(); i++) {
			if (getArcState(seq.get(i - 1).intValue(), seq.get(i).intValue()) == ARC_FORBIDDEN) {
				return false;
			}
		}
		return getArcState(seq.get(seq.size() - 1).intValue(), sinkId()) != ARC_FORBIDDEN;
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
