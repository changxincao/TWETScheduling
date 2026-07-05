package TWETBPC.LP;

import java.util.Map;

import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;

/**
 * 读取一次 RMP 解中的正值机器列统计。
 * 这里的 elementary/basic 口径指序列内 job 不重复，不是 CPLEX basis 状态。
 */
final class ColumnSolutionStats {

	final int positiveColumns;
	final int elementaryPositiveColumns;
	final int nonElementaryPositiveColumns;
	final double positiveValueSum;
	final double elementaryValueSum;
	final int maxSequenceLength;

	private ColumnSolutionStats(int positiveColumns, int elementaryPositiveColumns,
			int nonElementaryPositiveColumns, double positiveValueSum, double elementaryValueSum,
			int maxSequenceLength) {
		this.positiveColumns = positiveColumns;
		this.elementaryPositiveColumns = elementaryPositiveColumns;
		this.nonElementaryPositiveColumns = nonElementaryPositiveColumns;
		this.positiveValueSum = positiveValueSum;
		this.elementaryValueSum = elementaryValueSum;
		this.maxSequenceLength = maxSequenceLength;
	}

	static ColumnSolutionStats from(TWETMasterSolution solution, Pool pool, int jobCount) {
		if (solution == null || pool == null) {
			return empty();
		}
		int positive = 0;
		int elementary = 0;
		int nonElementary = 0;
		double positiveSum = 0.0;
		double elementarySum = 0.0;
		int maxLength = 0;
		for (Map.Entry<Integer, Double> entry : solution.getColumnValues().entrySet()) {
			double value = entry.getValue().doubleValue();
			if (value <= 1e-8) {
				continue;
			}
			TWETColumn column = pool.getColumn(entry.getKey().intValue());
			positive++;
			positiveSum += value;
			maxLength = Math.max(maxLength, column.size());
			if (isElementary(column, jobCount)) {
				elementary++;
				elementarySum += value;
			} else {
				nonElementary++;
			}
		}
		return new ColumnSolutionStats(positive, elementary, nonElementary, positiveSum, elementarySum, maxLength);
	}

	private static ColumnSolutionStats empty() {
		return new ColumnSolutionStats(0, 0, 0, 0.0, 0.0, 0);
	}

	private static boolean isElementary(TWETColumn column, int jobCount) {
		if (column == null || column.size() == 0) {
			return false;
		}
		boolean[] seen = new boolean[jobCount + 1];
		for (int job : column.getSequence()) {
			if (job < 1 || job > jobCount || seen[job]) {
				return false;
			}
			seen[job] = true;
		}
		return true;
	}

	String summary() {
		return "positiveCols=" + positiveColumns
				+ ", elementaryPositiveCols=" + elementaryPositiveColumns
				+ ", nonElementaryPositiveCols=" + nonElementaryPositiveColumns
				+ ", positiveValueSum=" + format(positiveValueSum)
				+ ", elementaryValueSum=" + format(elementaryValueSum)
				+ ", maxPositiveSequenceLength=" + maxSequenceLength;
	}

	private static String format(double value) {
		return String.format(java.util.Locale.US, "%.6f", value);
	}
}
