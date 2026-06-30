package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETOutsourcingColumn;

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

	public static final byte SEGMENT_FREE = 0;
	public static final byte SEGMENT_FORBIDDEN = -1;
	public static final byte SEGMENT_REQUIRED = 1;

	public static final byte OUTSOURCE_FREE = 0;
	public static final byte OUTSOURCE_FORBIDDEN = -1;
	public static final byte OUTSOURCE_REQUIRED = 1;

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

	private final Data data;
	public int id;
	public int depth;
	public double pseudoCost;
	public int minMachineCount;
	public int maxMachineCount;
	public ArrayList<Integer> seedColumnIds;
	public ArrayList<Integer> seedOutsourcingColumnIds;
	public ArrayList<Integer> incumbentColumnIds;
	public ArrayList<Integer> activeCutIds;
	// 2026-06-27: strong branching trial 已经对该 child 完成 RMP/repair/筛列后置处理。
	// 正式出队时仍需解一次 LP 取得 dual，但不再重复做 child 初始 repair/筛列。
	private boolean strongBranchingSeedPrepared;
	private byte[][] arcState;
	private boolean[][] branchImpliedForbiddenArc;
	private boolean[][] pricingOnlyForbiddenArc;
	private HashMap<Integer, BitSet> timeIndexedPricingOnlyForbiddenArcTimesByPair;
	private int timeIndexedPricingOnlyForbiddenArcCount;
	private boolean timeIndexedPricingOnlyArcStoreAllowed;
	private int timeIndexedPricingOnlyArcStoreHorizon;
	private int[] timeIndexedPricingWindowStartByJob;
	private int[] timeIndexedPricingWindowEndByJob;
	private byte[][] adjacencyPairState;
	private byte[] tariffSegmentState;
	private byte[] outsourcingJobState;
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
		this.seedOutsourcingColumnIds = new ArrayList<Integer>();
		this.incumbentColumnIds = new ArrayList<Integer>(incumbentColumnIds);
		this.activeCutIds = new ArrayList<Integer>();
		this.strongBranchingSeedPrepared = false;
		this.arcState = new byte[data.n + 2][data.n + 2];
		this.branchImpliedForbiddenArc = new boolean[data.n + 2][data.n + 2];
		this.pricingOnlyForbiddenArc = new boolean[data.n + 2][data.n + 2];
		this.timeIndexedPricingOnlyForbiddenArcTimesByPair = new HashMap<Integer, BitSet>();
		this.timeIndexedPricingOnlyForbiddenArcCount = 0;
		this.timeIndexedPricingOnlyArcStoreAllowed = false;
		this.timeIndexedPricingOnlyArcStoreHorizon = -1;
		this.timeIndexedPricingWindowStartByJob = null;
		this.timeIndexedPricingWindowEndByJob = null;
		this.adjacencyPairState = new byte[data.n + 2][data.n + 2];
		this.tariffSegmentState = new byte[countTariffSegments(data)];
		this.outsourcingJobState = new byte[data.n + 1];
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
		copy.seedOutsourcingColumnIds = new ArrayList<Integer>(seedOutsourcingColumnIds);
		copy.activeCutIds = new ArrayList<Integer>(activeCutIds);
		copy.strongBranchingSeedPrepared = false;
		copy.arcState = new byte[arcState.length][];
		for (int i = 0; i < arcState.length; i++) {
			copy.arcState[i] = arcState[i].clone();
		}
		copy.branchImpliedForbiddenArc = new boolean[branchImpliedForbiddenArc.length][];
		for (int i = 0; i < branchImpliedForbiddenArc.length; i++) {
			copy.branchImpliedForbiddenArc[i] = branchImpliedForbiddenArc[i].clone();
		}
		copy.pricingOnlyForbiddenArc = new boolean[pricingOnlyForbiddenArc.length][];
		for (int i = 0; i < pricingOnlyForbiddenArc.length; i++) {
			copy.pricingOnlyForbiddenArc[i] = pricingOnlyForbiddenArc[i].clone();
		}
		copy.timeIndexedPricingOnlyForbiddenArcTimesByPair = new HashMap<Integer, BitSet>();
		for (Map.Entry<Integer, BitSet> entry : timeIndexedPricingOnlyForbiddenArcTimesByPair.entrySet()) {
			copy.timeIndexedPricingOnlyForbiddenArcTimesByPair.put(entry.getKey(), (BitSet) entry.getValue().clone());
		}
		copy.timeIndexedPricingOnlyForbiddenArcCount = timeIndexedPricingOnlyForbiddenArcCount;
		copy.timeIndexedPricingOnlyArcStoreAllowed = timeIndexedPricingOnlyArcStoreAllowed;
		copy.timeIndexedPricingOnlyArcStoreHorizon = timeIndexedPricingOnlyArcStoreHorizon;
		copy.timeIndexedPricingWindowStartByJob = timeIndexedPricingWindowStartByJob == null ? null
				: timeIndexedPricingWindowStartByJob.clone();
		copy.timeIndexedPricingWindowEndByJob = timeIndexedPricingWindowEndByJob == null ? null
				: timeIndexedPricingWindowEndByJob.clone();
		copy.adjacencyPairState = new byte[adjacencyPairState.length][];
		for (int i = 0; i < adjacencyPairState.length; i++) {
			copy.adjacencyPairState[i] = adjacencyPairState[i].clone();
		}
		copy.tariffSegmentState = tariffSegmentState.clone();
		copy.outsourcingJobState = outsourcingJobState.clone();
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

	public byte getAdjacencyPairState(int firstJob, int secondJob) {
		int a = normalizedPairFirst(firstJob, secondJob);
		int b = normalizedPairSecond(firstJob, secondJob);
		if (a <= 0 || b <= 0 || a >= adjacencyPairState.length || b >= adjacencyPairState.length) {
			return ADJACENCY_FREE;
		}
		return adjacencyPairState[a][b];
	}

	/**
	 * 2026-05-20: 判断一条弧是否不能用于生成列。
	 * <p>
	 * 显式分支状态仍保存在 arcState 中；Data 里的预处理不可行弧只作为全局过滤条件，不写入
	 * arcState，避免 RMP 把这些静态过滤弧误建成分支约束行。
	 */
	public boolean isArcForbidden(int from, int to) {
		return data.isPreprocessedArcForbidden(from, to) || getArcState(from, to) == ARC_FORBIDDEN
				|| isBranchImpliedArcForbidden(from, to) || isAdjacencyPairForbiddenArc(from, to);
	}

	public void forbidArc(int from, int to) {
		arcState[from][to] = ARC_FORBIDDEN;
	}

	/**
	 * 2026-06-30: 右支要求 i->j 时，机器路径上 i 不能再接其他后继，j 不能再有其他前驱。
	 * 这些推导禁弧只用于过滤历史列和 pricing 扩展，不进入 master 分支行；master 里只保留选中的
	 * required arc 行，和旧 VRP 的 branch2rng / feasible_arc 分工一致。
	 */
	public void forbidBranchImpliedArc(int from, int to) {
		if (from < 0 || to < 0 || from >= branchImpliedForbiddenArc.length
				|| to >= branchImpliedForbiddenArc[from].length) {
			return;
		}
		if (arcState[from][to] == ARC_REQUIRED) {
			return;
		}
		branchImpliedForbiddenArc[from][to] = true;
	}

	private boolean isBranchImpliedArcForbidden(int from, int to) {
		return from >= 0 && to >= 0 && from < branchImpliedForbiddenArc.length
				&& to < branchImpliedForbiddenArc[from].length && branchImpliedForbiddenArc[from][to];
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

	/**
	 * 2026-06-20: time-indexed graph pricing 专用的 pricing-only 禁弧。
	 * <p>
	 * 论文 arc fixing 删除的是具体时间弧 (from,to,t)，不是普通 job-job arc。这里不写入 master
	 * 分支状态，只让后续 graph pricing 避开这些 time-expanded arcs。
	 * 2026-06-29: 按 (from,to) 分组保存 time 的 BitSet，避免百万级时空弧以 boxed Long 形式复制。
	 */
	public void forbidTimeIndexedPricingOnlyArc(int from, int to, int time) {
		if (from < 0 || to < 0 || from >= data.n + 2 || to >= data.n + 2 || time < 0) {
			return;
		}
		if (timeIndexedPricingOnlyArcStoreAllowed && time <= timeIndexedPricingOnlyArcStoreHorizon) {
			BitSet allowedTimes = timeIndexedPricingOnlyForbiddenArcTimesByPair.get(timeIndexedArcPairKey(from, to));
			if (allowedTimes != null && allowedTimes.get(time)) {
				allowedTimes.clear(time);
				timeIndexedPricingOnlyForbiddenArcCount++;
			}
		} else {
			int pairKey = timeIndexedArcPairKey(from, to);
			BitSet times = timeIndexedPricingOnlyForbiddenArcTimesByPair.get(pairKey);
			if (times == null) {
				times = new BitSet();
				timeIndexedPricingOnlyForbiddenArcTimesByPair.put(pairKey, times);
			}
			if (!times.get(time)) {
				times.set(time);
				timeIndexedPricingOnlyForbiddenArcCount++;
			}
		}
	}

	public boolean isTimeIndexedPricingOnlyArcForbidden(int from, int to, int time) {
		if (from < 0 || to < 0 || from >= data.n + 2 || to >= data.n + 2 || time < 0) {
			return false;
		}
		BitSet times = timeIndexedPricingOnlyForbiddenArcTimesByPair.get(timeIndexedArcPairKey(from, to));
		if (timeIndexedPricingOnlyArcStoreAllowed) {
			return time <= timeIndexedPricingOnlyArcStoreHorizon && (times == null || !times.get(time));
		}
		return times != null && times.get(time);
	}

	public int countTimeIndexedPricingOnlyForbiddenArcs() {
		return timeIndexedPricingOnlyForbiddenArcCount;
	}

	/**
	 * 2026-06-29: 用完整 time-indexed fixing 结果替换当前节点继承的时空禁弧。
	 * 如果被删弧更多，则存 allowed complement；如果被删弧更少，则存 forbidden set。
	 * 对外查询语义保持不变，仍然回答某条 (from,to,time) 是否被禁用。
	 */
	public int replaceTimeIndexedPricingOnlyArcSet(BitSet forbiddenArcIndices, int pairWidth, int horizon) {
		timeIndexedPricingOnlyForbiddenArcTimesByPair.clear();
		timeIndexedPricingOnlyForbiddenArcCount = forbiddenArcIndices == null ? 0 : forbiddenArcIndices.cardinality();
		timeIndexedPricingOnlyArcStoreHorizon = horizon;
		int total = pairWidth * pairWidth * (horizon + 1);
		timeIndexedPricingOnlyArcStoreAllowed = forbiddenArcIndices != null
				&& timeIndexedPricingOnlyForbiddenArcCount > total / 2;
		if (forbiddenArcIndices == null || total <= 0) {
			timeIndexedPricingOnlyArcStoreAllowed = false;
			return timeIndexedPricingOnlyForbiddenArcCount;
		}
		if (timeIndexedPricingOnlyArcStoreAllowed) {
			for (int index = forbiddenArcIndices.nextClearBit(0); index >= 0 && index < total;
					index = forbiddenArcIndices.nextClearBit(index + 1)) {
				addStoredTimeIndexedArc(index, pairWidth);
			}
		} else {
			for (int index = forbiddenArcIndices.nextSetBit(0); index >= 0 && index < total;
					index = forbiddenArcIndices.nextSetBit(index + 1)) {
				addStoredTimeIndexedArc(index, pairWidth);
			}
		}
		return timeIndexedPricingOnlyForbiddenArcCount;
	}

	private void addStoredTimeIndexedArc(int index, int pairWidth) {
		int pairCount = pairWidth * pairWidth;
		int time = index / pairCount;
		int remainder = index % pairCount;
		int from = remainder / pairWidth;
		int to = remainder % pairWidth;
		int pairKey = timeIndexedArcPairKey(from, to);
		BitSet times = timeIndexedPricingOnlyForbiddenArcTimesByPair.get(pairKey);
		if (times == null) {
			times = new BitSet();
			timeIndexedPricingOnlyForbiddenArcTimesByPair.put(pairKey, times);
		}
		times.set(time);
	}

	/**
	 * 2026-06-29: ng-DSSR 主线只继承 time-indexed fixing 提取出的 job 时间窗，
	 * 避免把数百万条 (from,to,t) 时空弧复制到子节点。
	 */
	public boolean tightenTimeIndexedPricingWindow(int job, int start, int end) {
		if (job < 1 || job > data.n) {
			return false;
		}
		ensureTimeIndexedPricingWindows();
		int oldStart = timeIndexedPricingWindowStartByJob[job];
		int oldEnd = timeIndexedPricingWindowEndByJob[job];
		int newStart = Math.max(oldStart, Math.max(0, start));
		int newEnd = Math.min(oldEnd, Math.max(0, end));
		timeIndexedPricingWindowStartByJob[job] = newStart;
		timeIndexedPricingWindowEndByJob[job] = newEnd;
		return newStart != oldStart || newEnd != oldEnd;
	}

	public boolean hasTimeIndexedPricingWindow(int job) {
		return job >= 1 && job <= data.n && timeIndexedPricingWindowStartByJob != null
				&& (timeIndexedPricingWindowStartByJob[job] > 0
						|| timeIndexedPricingWindowEndByJob[job] < Integer.MAX_VALUE);
	}

	public int getTimeIndexedPricingWindowStart(int job) {
		return timeIndexedPricingWindowStartByJob == null ? 0 : timeIndexedPricingWindowStartByJob[job];
	}

	public int getTimeIndexedPricingWindowEnd(int job) {
		return timeIndexedPricingWindowEndByJob == null ? Integer.MAX_VALUE : timeIndexedPricingWindowEndByJob[job];
	}

	public int countTimeIndexedPricingWindowTightenedJobs() {
		if (timeIndexedPricingWindowStartByJob == null) {
			return 0;
		}
		int count = 0;
		for (int job = 1; job <= data.n; job++) {
			if (hasTimeIndexedPricingWindow(job)) {
				count++;
			}
		}
		return count;
	}

	public double averageTimeIndexedPricingWindowLength() {
		if (timeIndexedPricingWindowStartByJob == null) {
			return Double.NaN;
		}
		double total = 0.0;
		int count = 0;
		for (int job = 1; job <= data.n; job++) {
			if (hasTimeIndexedPricingWindow(job)) {
				total += Math.max(0, timeIndexedPricingWindowEndByJob[job] - timeIndexedPricingWindowStartByJob[job]);
				count++;
			}
		}
		return count == 0 ? Double.NaN : total / count;
	}

	public double averageTimeIndexedPricingWindowShrinkRatio() {
		if (timeIndexedPricingWindowStartByJob == null) {
			return Double.NaN;
		}
		double total = 0.0;
		int count = 0;
		for (int job = 1; job <= data.n; job++) {
			if (!hasTimeIndexedPricingWindow(job)) {
				continue;
			}
			double original = Math.max(0.0, data.hardWindowEnd[job] - data.hardWindowStart[job]);
			if (!Double.isFinite(original) || !Utility.compareGt(original, 0.0)) {
				continue;
			}
			double current = Math.max(0, timeIndexedPricingWindowEndByJob[job] - timeIndexedPricingWindowStartByJob[job]);
			total += Math.max(0.0, original - current) / original;
			count++;
		}
		return count == 0 ? Double.NaN : total / count;
	}

	private void ensureTimeIndexedPricingWindows() {
		if (timeIndexedPricingWindowStartByJob != null) {
			return;
		}
		timeIndexedPricingWindowStartByJob = new int[data.n + 1];
		timeIndexedPricingWindowEndByJob = new int[data.n + 1];
		Arrays.fill(timeIndexedPricingWindowEndByJob, Integer.MAX_VALUE);
	}

	public int countRequiredArcStates() {
		return countArcStates(ARC_REQUIRED);
	}

	public int countForbiddenArcStates() {
		return countArcStates(ARC_FORBIDDEN);
	}

	public int countBranchImpliedForbiddenArcs() {
		int count = 0;
		for (int from = 0; from < branchImpliedForbiddenArc.length; from++) {
			for (int to = 0; to < branchImpliedForbiddenArc[from].length; to++) {
				if (branchImpliedForbiddenArc[from][to]) {
					count++;
				}
			}
		}
		return count;
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
				+ ",arcBranchImpliedForbid=" + countBranchImpliedForbiddenArcs()
				+ ",pricingOnlyArc=" + countPricingOnlyForbiddenArcs()
				+ ",timePricingOnlyArc=" + countTimeIndexedPricingOnlyForbiddenArcs()
				+ ",timeWindowJobs=" + countTimeIndexedPricingWindowTightenedJobs()
				+ ",timeWindowAvgLen=" + formatOptionalDouble(averageTimeIndexedPricingWindowLength())
				+ ",timeWindowAvgShrinkRatio=" + formatOptionalDouble(averageTimeIndexedPricingWindowShrinkRatio())
				+ ",adjReq=" + countRequiredAdjacencyPairs() + ",adjForbid=" + countForbiddenAdjacencyPairs()
				+ ",tariffReq=" + countRequiredTariffSegments() + ",tariffForbid=" + countForbiddenTariffSegments()
				+ ",outReq=" + countOutsourcingJobStates(OUTSOURCE_REQUIRED)
				+ ",outForbid=" + countOutsourcingJobStates(OUTSOURCE_FORBIDDEN)
				+ ",repair=" + repairType + ":" + repairFrom + "->" + repairTo + "/seg=" + repairSegment;
	}

	private String formatOptionalDouble(double value) {
		return Double.isFinite(value) ? String.format(Locale.US, "%.3f", Double.valueOf(value)) : "NA";
	}

	public void requireArc(int from, int to) {
		if (from >= 0 && to >= 0 && from < branchImpliedForbiddenArc.length
				&& to < branchImpliedForbiddenArc[from].length) {
			branchImpliedForbiddenArc[from][to] = false;
		}
		arcState[from][to] = ARC_REQUIRED;
	}

	public void forbidAdjacencyPair(int firstJob, int secondJob) {
		setAdjacencyPairState(firstJob, secondJob, ADJACENCY_FORBIDDEN);
	}

	public void requireAdjacencyPair(int firstJob, int secondJob) {
		setAdjacencyPairState(firstJob, secondJob, ADJACENCY_REQUIRED);
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

	public void markAdjacencyPairRepair(int firstJob, int secondJob, boolean required) {
		repairType = required ? REPAIR_ADJACENCY_REQUIRED : REPAIR_ADJACENCY_FORBIDDEN;
		repairFrom = normalizedPairFirst(firstJob, secondJob);
		repairTo = normalizedPairSecond(firstJob, secondJob);
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

	public boolean isStrongBranchingSeedPrepared() {
		return strongBranchingSeedPrepared;
	}

	public void setStrongBranchingSeedPrepared(boolean strongBranchingSeedPrepared) {
		this.strongBranchingSeedPrepared = strongBranchingSeedPrepared;
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

	public byte getOutsourcingJobState(int job) {
		if (job < 1 || job >= outsourcingJobState.length) {
			return OUTSOURCE_FREE;
		}
		return outsourcingJobState[job];
	}

	public void forbidOutsourcingJob(int job) {
		if (job >= 1 && job < outsourcingJobState.length) {
			outsourcingJobState[job] = OUTSOURCE_FORBIDDEN;
		}
	}

	public void requireOutsourcingJob(int job) {
		if (job >= 1 && job < outsourcingJobState.length) {
			outsourcingJobState[job] = OUTSOURCE_REQUIRED;
		}
	}

	public List<Integer> getRequiredOutsourcingJobs() {
		ArrayList<Integer> jobs = new ArrayList<Integer>();
		for (int job = 1; job < outsourcingJobState.length; job++) {
			if (outsourcingJobState[job] == OUTSOURCE_REQUIRED) {
				jobs.add(Integer.valueOf(job));
			}
		}
		return jobs;
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

	private int countTariffSegments(byte state) {
		int count = 0;
		for (int segment = 0; segment < tariffSegmentState.length; segment++) {
			if (tariffSegmentState[segment] == state) {
				count++;
			}
		}
		return count;
	}

	private int countOutsourcingJobStates(byte state) {
		int count = 0;
		for (int job = 1; job < outsourcingJobState.length; job++) {
			if (outsourcingJobState[job] == state) {
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

	/** 判断一条列是否违反当前节点的 forbidden arc。 */
	public boolean isColumnCompatible(TWETColumn column) {
		for (int job = 1; job < outsourcingJobState.length; job++) {
			if (outsourcingJobState[job] == OUTSOURCE_REQUIRED && column.containsJob(job)) {
				return false;
			}
		}
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

	public boolean isOutsourcingColumnCompatible(TWETOutsourcingColumn column) {
		for (int job = 1; job < outsourcingJobState.length; job++) {
			byte state = outsourcingJobState[job];
			if (state == OUTSOURCE_REQUIRED && !column.containsJob(job)) {
				return false;
			}
			if (state == OUTSOURCE_FORBIDDEN && column.containsJob(job)) {
				return false;
			}
		}
		return true;
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

	private int normalizedPairFirst(int firstJob, int secondJob) {
		return Math.min(firstJob, secondJob);
	}

	private int normalizedPairSecond(int firstJob, int secondJob) {
		return Math.max(firstJob, secondJob);
	}

	private int timeIndexedArcPairKey(int from, int to) {
		return from * (data.n + 2) + to;
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
