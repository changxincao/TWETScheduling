package TWETBPC.Model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Common.Utility;
import TWETBPC.LP.Pool;

/**
 * RMP（受限主问题）当前一次求解的结果对象。
 * <p>
 * 对照旧 BPC 的 LP 对象，可以把它理解成：
 * “把 LP 当前解里最重要的状态提炼成一个只读结果对象”。
 * <p>
 * 目前保存的是：
 * <ul>
 * <li>状态（是否未求、是否 placeholder 可行等）；</li>
 * <li>列值 map；</li>
 * <li>目标值；</li>
 * <li>是否整数；</li>
 * <li>辅助说明文字。</li>
 * </ul>
 */
public final class TWETMasterSolution {

	/** 当前主问题解的状态。 */
	private final TWETMasterStatus status;
	/** 列变量值；key 是列 id，value 是该列在解中的取值。 */
	private final LinkedHashMap<Integer, Double> columnValues;
	/** 外包变量值；下标为 job id。 */
	private final double[] outsourcingValues;
	/** 当前解的目标值。 */
	private final double objectiveValue;
	/** 当前解是否为整数解。 */
	private final boolean integer;
	/** 补充说明。 */
	private final String message;

	/**
	 * 构造一个主问题结果对象。
	 */
	public TWETMasterSolution(TWETMasterStatus status, Map<Integer, Double> columnValues, double objectiveValue,
			boolean integer, String message) {
		this(status, columnValues, null, objectiveValue, integer, message);
	}

	/**
	 * 构造一个包含外包变量值的主问题结果对象。
	 */
	public TWETMasterSolution(TWETMasterStatus status, Map<Integer, Double> columnValues, double[] outsourcingValues,
			double objectiveValue, boolean integer, String message) {
		this.status = status;
		this.columnValues = new LinkedHashMap<Integer, Double>(columnValues);
		this.outsourcingValues = outsourcingValues == null ? new double[0] : outsourcingValues.clone();
		this.objectiveValue = objectiveValue;
		this.integer = integer;
		this.message = message;
	}

	/** @return 主问题解状态 */
	public TWETMasterStatus getStatus() {
		return status;
	}

	/** @return 列值的只读映射 */
	public Map<Integer, Double> getColumnValues() {
		return Collections.unmodifiableMap(columnValues);
	}

	/** @return 外包变量值的副本；若当前主问题没有外包变量，则返回空数组 */
	public double[] getOutsourcingValues() {
		return outsourcingValues.clone();
	}

	/**
	 * 返回当前取值非零的列 id 列表。
	 * <p>
	 * 当前实现中 columnValues 本身就只保存被选中的列，
	 * 因此这里直接返回 key 集即可。
	 */
	public List<Integer> getActiveColumnIds() {
		return new ArrayList<Integer>(columnValues.keySet());
	}

	/** @return 目标值 */
	public double getObjectiveValue() {
		return objectiveValue;
	}

	/** @return 是否整数 */
	public boolean isInteger() {
		return integer;
	}

	/** @return 补充说明 */
	public String getMessage() {
		return message;
	}

	/**
	 * 计算当前解使用了多少“机器列”。
	 * <p>
	 * 在 set partitioning 风格的主问题里，
	 * 这一值等于所有列变量的和。
	 */
	public double getMachineUsage() {
		double usage = 0.0;
		for (double value : columnValues.values()) {
			usage += value;
		}
		return usage;
	}

	/**
	 * 统计给定弧在当前解中的总取值。
	 * <p>
	 * 弧分支器会利用这个函数，判断某个相邻关系是否是分数的。
	 */
	public double getArcValue(Pool pool, int from, int to, int sinkId) {
		double value = 0.0;
		for (Map.Entry<Integer, Double> entry : columnValues.entrySet()) {
			TWETColumn column = pool.getColumn(entry.getKey());
			if (column.visitsArc(from, to, sinkId)) {
				value += entry.getValue();
			}
		}
		return value;
	}

	/**
	 * 判断当前解中是否使用了给定弧。
	 * <p>
	 * 这是 {@link #getArcValue(Pool, int, int, int)} 的布尔包装。
	 */
	public boolean usesArc(Pool pool, int from, int to, int sinkId) {
		return Utility.compareGt(getArcValue(pool, from, to, sinkId), 0.0);
	}

}
