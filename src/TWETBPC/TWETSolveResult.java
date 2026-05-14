package TWETBPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 求解结果对象。
 * <p>
 * 这份结果并不试图承载所有内部调试信息，而是只保留对外最常用的摘要：
 * 状态、incumbent、bound、节点数、列数以及最终选中的列。
 * <p>
 * 后续如果需要更详细的日志，可在 context / tree / pc 内部另行扩展。
 */
public final class TWETSolveResult {

	/** 当前求解流程结束时的状态。 */
	private final TWETSolveStatus status;
	/** 当前已知 incumbent 的目标值。 */
	private final double incumbentCost;
	/** 当前搜索过程中得到的最好下界。 */
	private final double bestBound;
	/** 已处理的节点数。 */
	private final int processedNodes;
	/** 全局列池中生成过的列数。 */
	private final int generatedColumns;
	/** 结果中对应的列 id 集合。 */
	private final ArrayList<Integer> incumbentColumnIds;
	/** 供调试/说明使用的文字信息。 */
	private final String message;

	/**
	 * 构造一个求解结果。
	 */
	public TWETSolveResult(TWETSolveStatus status, double incumbentCost, double bestBound, int processedNodes,
			int generatedColumns, List<Integer> incumbentColumnIds, String message) {
		this.status = status;
		this.incumbentCost = incumbentCost;
		this.bestBound = bestBound;
		this.processedNodes = processedNodes;
		this.generatedColumns = generatedColumns;
		this.incumbentColumnIds = new ArrayList<Integer>(incumbentColumnIds);
		this.message = message;
	}

	/** @return 求解流程结束状态 */
	public TWETSolveStatus getStatus() {
		return status;
	}

	/** @return 当前 incumbent 的目标值 */
	public double getIncumbentCost() {
		return incumbentCost;
	}

	/** @return 当前已知最好下界 */
	public double getBestBound() {
		return bestBound;
	}

	/** @return 已处理节点数 */
	public int getProcessedNodes() {
		return processedNodes;
	}

	/** @return 生成的列总数 */
	public int getGeneratedColumns() {
		return generatedColumns;
	}

	/**
	 * 返回最终 incumbent 对应的列 id 列表。
	 * <p>
	 * 这里返回的是只读视图，避免外部误改结果对象内部状态。
	 */
	public List<Integer> getIncumbentColumnIds() {
		return Collections.unmodifiableList(incumbentColumnIds);
	}

	/** @return 求解器返回的补充说明 */
	public String getMessage() {
		return message;
	}

}
