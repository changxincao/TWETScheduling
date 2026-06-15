package TWETBPC.LP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	public static final byte ADJACENCY_FREE = 0;
	public static final byte ADJACENCY_FORBIDDEN = -1;
	public static final byte ADJACENCY_REQUIRED = 1;

	public static final byte ARC_PAIR_FREE = 0;
	public static final byte ARC_PAIR_FORBIDDEN = -1;
	public static final byte ARC_PAIR_REQUIRED = 1;

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
	public static final byte REPAIR_ADJACENCY_FORBIDDEN = 7;
	public static final byte REPAIR_ADJACENCY_REQUIRED = 8;
	public static final byte REPAIR_ARC_PAIR_FORBIDDEN = 9;
	public static final byte REPAIR_ARC_PAIR_REQUIRED = 10;

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
	private boolean[][] pricingOnlyForbiddenArc;
	private byte[][] adjacencyPairState;
	private HashMap<Long, Byte> arcPairState;
	private boolean[][] requiredArcPairArc;
	private byte[] tariffSegmentState;
	// 只用于子节点首次 LP 不可行时的定向 repair；不是完整分支状态本身。
	private byte repairType;
	private int repairFrom;
	private int repairTo;
	private int repairThird;
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
		this.pricingOnlyForbiddenArc = new boolean[data.n + 2][data.n + 2];
		this.adjacencyPairState = new byte[data.n + 2][data.n + 2];
		this.arcPairState = new HashMap<Long, Byte>();
		this.requiredArcPairArc = new boolean[data.n + 2][data.n + 2];
		this.tariffSegmentState = new byte[countTariffSegments(data)];
		this.repairType = REPAIR_NONE;
		this.repairFrom = -1;
		this.repairTo = -1;
		this.repairThird = -1;
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
		copy.pricingOnlyForbiddenArc = new boolean[pricingOnlyForbiddenArc.length][];
		for (int i = 0; i < pricingOnlyForbiddenArc.length; i++) {
			copy.pricingOnlyForbiddenArc[i] = pricingOnlyForbiddenArc[i].clone();
		}
		copy.adjacencyPairState = new byte[adjacencyPairState.length][];
		for (int i = 0; i < adjacencyPairState.length; i++) {
			copy.adjacencyPairState[i] = adjacencyPairState[i].clone();
		}
		copy.arcPairState = new HashMap<Long, Byte>(arcPairState);
		copy.requiredArcPairArc = new boolean[requiredArcPairArc.length][];
		for (int i = 0; i < requiredArcPairArc.length; i++) {
			copy.requiredArcPairArc[i] = requiredArcPairArc[i].clone();
		}
		copy.tariffSegmentState = tariffSegmentState.clone();
		copy.repairType = repairType;
		copy.repairFrom = repairFrom;
		copy.repairTo = repairTo;
		copy.repairThird = repairThird;
		copy.repairSegment = repairSegment;
		return copy;
	}

	public int sinkId() {
		return data.n + 1;
	}

	public byte getArcState(int from, int to) {
		return arcState[from][to];
	}

	public byte getAdjacencyPairState(int firstJob, int secondJob) {
		int a = normalizedPairFirst(firstJob, secondJob);
		int b = normalizedPairSecond(firstJob, secondJob);
		if (a <= 0 || b <= 0 || a >= adjacencyPairState.length || b >= adjacencyPairState.length) {
			return ADJACENCY_FREE;
		}
		return adjacencyPairState[a][b];
	}

	public byte getArcPairState(int firstJob, int middleJob, int thirdJob) {
		if (!isRealJob(firstJob) || !isRealJob(middleJob) || !isRealJob(thirdJob)
				|| firstJob == middleJob || middleJob == thirdJob || firstJob == thirdJob) {
			return ARC_PAIR_FREE;
		}
		Byte state = arcPairState.get(Long.valueOf(arcPairKey(firstJob, middleJob, thirdJob)));
		return state == null ? ARC_PAIR_FREE : state.byteValue();
	}

	/**
	 * 2026-05-20: 判断一条弧是否不能用于生成列。
	 * <p>
	 * 显式分支状态仍保存在 arcState 中；Data 里的预处理不可行弧只作为全局过滤条件，不写入
	 * arcState，避免 RMP 把这些静态过滤弧误建成分支约束行。
	 */
	public boolean isArcForbidden(int from, int to) {
		return data.isPreprocessedArcForbidden(from, to) || getArcState(from, to) == ARC_FORBIDDEN
				|| isAdjacencyPairForbiddenArc(from, to);
	}

	public void forbidArc(int from, int to) {
		arcState[from][to] = ARC_FORBIDDEN;
	}

	/**
	 * 2026-06-03: 仅用于 subtree arc elimination 的隔离实验。该状态只给 pricing 图禁弧，
	 * 不进入 master 分支行，也不参与历史列兼容性过滤，用来排除 RMP/dual 变化的影响。
	 */
	public void forbidPricingOnlyArc(int from, int to) {
		if (from < 0 || to < 0 || from >= pricingOnlyForbiddenArc.length
				|| to >= pricingOnlyForbiddenArc[from].length) {
			return;
		}
		pricingOnlyForbiddenArc[from][to] = true;
	}

	public boolean isPricingOnlyArcForbidden(int from, int to) {
		return from >= 0 && to >= 0 && from < pricingOnlyForbiddenArc.length
				&& to < pricingOnlyForbiddenArc[from].length && pricingOnlyForbiddenArc[from][to];
	}

	public int countRequiredArcStates() {
		return countArcStates(ARC_REQUIRED);
	}

	public int countForbiddenArcStates() {
		return countArcStates(ARC_FORBIDDEN);
	}

	public int countPricingOnlyForbiddenArcs() {
		int count = 0;
		for (int from = 0; from < pricingOnlyForbiddenArc.length; from++) {
			for (int to = 0; to < pricingOnlyForbiddenArc[from].length; to++) {
				if (pricingOnlyForbiddenArc[from][to]) {
					count++;
				}
			}
		}
		return count;
	}

	public int countRequiredAdjacencyPairs() {
		return countAdjacencyPairs(ADJACENCY_REQUIRED);
	}

	public int countForbiddenAdjacencyPairs() {
		return countAdjacencyPairs(ADJACENCY_FORBIDDEN);
	}

	public int countRequiredArcPairs() {
		return countArcPairs(ARC_PAIR_REQUIRED);
	}

	public int countForbiddenArcPairs() {
		return countArcPairs(ARC_PAIR_FORBIDDEN);
	}

	public int countRequiredTariffSegments() {
		return countTariffSegments(SEGMENT_REQUIRED);
	}

	public int countForbiddenTariffSegments() {
		return countTariffSegments(SEGMENT_FORBIDDEN);
	}

	/**
	 * 2026-06-05: 轻量节点诊断摘要，只用于 heartbeat / paper graph 运行中定位卡点。
	 */
	public String diagnosticSummary() {
		return "id=" + id + ",depth=" + depth + ",pseudo=" + pseudoCost + ",machine=[" + minMachineCount + ","
				+ maxMachineCount + "],seed=" + seedColumnIds.size() + ",cuts=" + activeCutIds.size()
				+ ",arcReq=" + countRequiredArcStates() + ",arcForbid=" + countForbiddenArcStates()
				+ ",pricingOnlyArc=" + countPricingOnlyForbiddenArcs()
				+ ",arcPairReq=" + countRequiredArcPairs() + ",arcPairForbid=" + countForbiddenArcPairs()
				+ ",adjReq=" + countRequiredAdjacencyPairs() + ",adjForbid=" + countForbiddenAdjacencyPairs()
				+ ",tariffReq=" + countRequiredTariffSegments() + ",tariffForbid=" + countForbiddenTariffSegments()
				+ ",repair=" + repairType + ":" + repairFrom + "->" + repairTo + "->" + repairThird
				+ "/seg=" + repairSegment;
	}

	public void requireArc(int from, int to) {
		arcState[from][to] = ARC_REQUIRED;
	}

	public void forbidAdjacencyPair(int firstJob, int secondJob) {
		setAdjacencyPairState(firstJob, secondJob, ADJACENCY_FORBIDDEN);
	}

	public void requireAdjacencyPair(int firstJob, int secondJob) {
		setAdjacencyPairState(firstJob, secondJob, ADJACENCY_REQUIRED);
	}

	public void forbidArcPair(int firstJob, int middleJob, int thirdJob) {
		setArcPairState(firstJob, middleJob, thirdJob, ARC_PAIR_FORBIDDEN);
	}

	public void requireArcPair(int firstJob, int middleJob, int thirdJob) {
		setArcPairState(firstJob, middleJob, thirdJob, ARC_PAIR_REQUIRED);
	}

	public void markMachineUpperRepair() {
		repairType = REPAIR_MACHINE_UPPER;
		repairFrom = repairTo = repairThird = repairSegment = -1;
	}

	public void markMachineLowerRepair() {
		repairType = REPAIR_MACHINE_LOWER;
		repairFrom = repairTo = repairThird = repairSegment = -1;
	}

	public void markArcRepair(int from, int to, boolean required) {
		repairType = required ? REPAIR_ARC_REQUIRED : REPAIR_ARC_FORBIDDEN;
		repairFrom = from;
		repairTo = to;
		repairThird = -1;
		repairSegment = -1;
	}

	public void markAdjacencyPairRepair(int firstJob, int secondJob, boolean required) {
		repairType = required ? REPAIR_ADJACENCY_REQUIRED : REPAIR_ADJACENCY_FORBIDDEN;
		repairFrom = normalizedPairFirst(firstJob, secondJob);
		repairTo = normalizedPairSecond(firstJob, secondJob);
		repairThird = -1;
		repairSegment = -1;
	}

	public void markArcPairRepair(int firstJob, int middleJob, int thirdJob, boolean required) {
		repairType = required ? REPAIR_ARC_PAIR_REQUIRED : REPAIR_ARC_PAIR_FORBIDDEN;
		repairFrom = firstJob;
		repairTo = middleJob;
		repairThird = thirdJob;
		repairSegment = -1;
	}

	public void markTariffRepair(int segment, boolean required) {
		repairType = required ? REPAIR_TARIFF_REQUIRED : REPAIR_TARIFF_FORBIDDEN;
		repairFrom = repairTo = -1;
		repairThird = -1;
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

	public int getRepairThird() {
		return repairThird;
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

	private int countArcStates(byte state) {
		int count = 0;
		for (int from = 0; from < arcState.length; from++) {
			for (int to = 0; to < arcState[from].length; to++) {
				if (arcState[from][to] == state) {
					count++;
				}
			}
		}
		return count;
	}

	private int countAdjacencyPairs(byte state) {
		int count = 0;
		for (int first = 1; first <= data.n; first++) {
			for (int second = first + 1; second <= data.n; second++) {
				if (adjacencyPairState[first][second] == state) {
					count++;
				}
			}
		}
		return count;
	}

	private int countArcPairs(byte state) {
		int count = 0;
		for (Byte value : arcPairState.values()) {
			if (value != null && value.byteValue() == state) {
				count++;
			}
		}
		return count;
	}

	private int countTariffSegments(byte state) {
		int count = 0;
		for (int segment = 0; segment < tariffSegmentState.length; segment++) {
			if (tariffSegmentState[segment] == state) {
				count++;
			}
		}
		return count;
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

	public List<int[]> getRequiredAdjacencyPairs() {
		return collectAdjacencyPairs(ADJACENCY_REQUIRED);
	}

	public List<int[]> getForbiddenAdjacencyPairs() {
		return collectAdjacencyPairs(ADJACENCY_FORBIDDEN);
	}

	public List<int[]> getRequiredArcPairs() {
		return collectArcPairs(ARC_PAIR_REQUIRED);
	}

	public List<int[]> getForbiddenArcPairs() {
		return collectArcPairs(ARC_PAIR_FORBIDDEN);
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
		for (int i = 2; i < seq.size(); i++) {
			if (isArcPairForbidden(seq.get(i - 2).intValue(), seq.get(i - 1).intValue(),
					seq.get(i).intValue())) {
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

	public boolean columnCoversAdjacencyPair(TWETColumn column, int firstJob, int secondJob) {
		return column.visitsArc(firstJob, secondJob, sinkId()) || column.visitsArc(secondJob, firstJob, sinkId());
	}

	public boolean columnCoversArcPair(TWETColumn column, int firstJob, int middleJob, int thirdJob) {
		return column.visitsArcPair(firstJob, middleJob, thirdJob);
	}

	public boolean isArcPairForbidden(int firstJob, int middleJob, int thirdJob) {
		return getArcPairState(firstJob, middleJob, thirdJob) == ARC_PAIR_FORBIDDEN;
	}

	public boolean isArcRequiredByArcPair(int fromJob, int toJob) {
		return fromJob >= 0 && toJob >= 0 && fromJob < requiredArcPairArc.length
				&& toJob < requiredArcPairArc[fromJob].length && requiredArcPairArc[fromJob][toJob];
	}

	private void setAdjacencyPairState(int firstJob, int secondJob, byte state) {
		int a = normalizedPairFirst(firstJob, secondJob);
		int b = normalizedPairSecond(firstJob, secondJob);
		if (a <= 0 || b <= 0 || a >= adjacencyPairState.length || b >= adjacencyPairState.length) {
			return;
		}
		adjacencyPairState[a][b] = state;
	}

	private boolean isAdjacencyPairForbiddenArc(int from, int to) {
		return from > 0 && to > 0 && from <= data.n && to <= data.n
				&& getAdjacencyPairState(from, to) == ADJACENCY_FORBIDDEN;
	}

	private void setArcPairState(int firstJob, int middleJob, int thirdJob, byte state) {
		if (!isRealJob(firstJob) || !isRealJob(middleJob) || !isRealJob(thirdJob)
				|| firstJob == middleJob || middleJob == thirdJob || firstJob == thirdJob) {
			return;
		}
		long key = arcPairKey(firstJob, middleJob, thirdJob);
		Byte oldState = arcPairState.get(Long.valueOf(key));
		if (state == ARC_PAIR_FREE) {
			arcPairState.remove(Long.valueOf(key));
		} else {
			arcPairState.put(Long.valueOf(key), Byte.valueOf(state));
		}
		byte old = oldState == null ? ARC_PAIR_FREE : oldState.byteValue();
		if (old != state && (old == ARC_PAIR_REQUIRED || state == ARC_PAIR_REQUIRED)) {
			rebuildRequiredArcPairArcCache();
		}
	}

	private void rebuildRequiredArcPairArcCache() {
		requiredArcPairArc = new boolean[data.n + 2][data.n + 2];
		for (Map.Entry<Long, Byte> entry : arcPairState.entrySet()) {
			if (entry.getValue() == null || entry.getValue().byteValue() != ARC_PAIR_REQUIRED) {
				continue;
			}
			long key = entry.getKey().longValue();
			int first = decodeArcPairFirst(key);
			int middle = decodeArcPairMiddle(key);
			int third = decodeArcPairThird(key);
			requiredArcPairArc[first][middle] = true;
			requiredArcPairArc[middle][third] = true;
		}
	}

	private ArrayList<int[]> collectAdjacencyPairs(byte state) {
		ArrayList<int[]> pairs = new ArrayList<int[]>();
		for (int first = 1; first <= data.n; first++) {
			for (int second = first + 1; second <= data.n; second++) {
				if (adjacencyPairState[first][second] == state) {
					pairs.add(new int[] { first, second });
				}
			}
		}
		return pairs;
	}

	private ArrayList<int[]> collectArcPairs(byte state) {
		ArrayList<int[]> pairs = new ArrayList<int[]>();
		for (Map.Entry<Long, Byte> entry : arcPairState.entrySet()) {
			if (entry.getValue() == null || entry.getValue().byteValue() != state) {
				continue;
			}
			long key = entry.getKey().longValue();
			pairs.add(new int[] { decodeArcPairFirst(key), decodeArcPairMiddle(key), decodeArcPairThird(key) });
		}
		return pairs;
	}

	private boolean isRealJob(int job) {
		return job > 0 && job <= data.n;
	}

	private long arcPairKey(int firstJob, int middleJob, int thirdJob) {
		long base = data.n + 2L;
		return ((long) firstJob) * base * base + ((long) middleJob) * base + thirdJob;
	}

	private int decodeArcPairFirst(long key) {
		long base = data.n + 2L;
		return (int) (key / (base * base));
	}

	private int decodeArcPairMiddle(long key) {
		long base = data.n + 2L;
		return (int) ((key / base) % base);
	}

	private int decodeArcPairThird(long key) {
		long base = data.n + 2L;
		return (int) (key % base);
	}

	private int normalizedPairFirst(int firstJob, int secondJob) {
		return Math.min(firstJob, secondJob);
	}

	private int normalizedPairSecond(int firstJob, int secondJob) {
		return Math.max(firstJob, secondJob);
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
