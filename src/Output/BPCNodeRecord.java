package Output;

/**
 * 2026-04-10: BPC 节点级摘要。
 * <p>
 * 旧参考代码里，节点过程信息主要直接打印到控制台。
 * 这里先把它收成稳定的数据对象，后续 console、csv、实验汇总都复用这份结构。
 */
public final class BPCNodeRecord {

	private final int nodeId;
	private final int depth;
	private final double pseudoCost;
	private final String masterStatus;
	private final double nodeObjective;
	private final boolean integerSolution;
	private final boolean incumbentUpdated;
	private final double incumbentCostAfterNode;
	private final double bestBoundAfterNode;
	private final double gapPercentAfterNode;
	private final int queueSizeAfterNode;
	private final int restrictedColumnCount;
	private final int activeCutCount;
	private final int poolSizeAfterNode;
	private final int cutPoolSizeAfterNode;
	private final String note;

	public BPCNodeRecord(int nodeId, int depth, double pseudoCost, String masterStatus, double nodeObjective,
			boolean integerSolution, boolean incumbentUpdated, double incumbentCostAfterNode, double bestBoundAfterNode,
			double gapPercentAfterNode, int queueSizeAfterNode, int restrictedColumnCount, int activeCutCount,
			int poolSizeAfterNode, int cutPoolSizeAfterNode, String note) {
		this.nodeId = nodeId;
		this.depth = depth;
		this.pseudoCost = pseudoCost;
		this.masterStatus = masterStatus;
		this.nodeObjective = nodeObjective;
		this.integerSolution = integerSolution;
		this.incumbentUpdated = incumbentUpdated;
		this.incumbentCostAfterNode = incumbentCostAfterNode;
		this.bestBoundAfterNode = bestBoundAfterNode;
		this.gapPercentAfterNode = gapPercentAfterNode;
		this.queueSizeAfterNode = queueSizeAfterNode;
		this.restrictedColumnCount = restrictedColumnCount;
		this.activeCutCount = activeCutCount;
		this.poolSizeAfterNode = poolSizeAfterNode;
		this.cutPoolSizeAfterNode = cutPoolSizeAfterNode;
		this.note = note;
	}

	public int getNodeId() {
		return nodeId;
	}

	public int getDepth() {
		return depth;
	}

	public double getPseudoCost() {
		return pseudoCost;
	}

	public String getMasterStatus() {
		return masterStatus;
	}

	public double getNodeObjective() {
		return nodeObjective;
	}

	public boolean isIntegerSolution() {
		return integerSolution;
	}

	public boolean isIncumbentUpdated() {
		return incumbentUpdated;
	}

	public double getIncumbentCostAfterNode() {
		return incumbentCostAfterNode;
	}

	public double getBestBoundAfterNode() {
		return bestBoundAfterNode;
	}

	public double getGapPercentAfterNode() {
		return gapPercentAfterNode;
	}

	public int getQueueSizeAfterNode() {
		return queueSizeAfterNode;
	}

	public int getRestrictedColumnCount() {
		return restrictedColumnCount;
	}

	public int getActiveCutCount() {
		return activeCutCount;
	}

	public int getPoolSizeAfterNode() {
		return poolSizeAfterNode;
	}

	public int getCutPoolSizeAfterNode() {
		return cutPoolSizeAfterNode;
	}

	public String getNote() {
		return note;
	}

}
