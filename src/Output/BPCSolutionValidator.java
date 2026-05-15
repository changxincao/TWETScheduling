package Output;

import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETSolveResult;
import TWETBPC.LP.Pool;
import TWETBPC.Model.TWETColumn;

/**
 * BPC incumbent 校验器。
 */
public final class BPCSolutionValidator {

	private BPCSolutionValidator() {
	}

	public static ValidationResult validate(Data data, Pool pool, TWETSolveResult result) {
		ArrayList<String> issues = new ArrayList<String>();
		boolean[] covered = new boolean[data.n + 1];
		double recomputedObjective = 0.0;
		List<Integer> columnIds = result.getIncumbentColumnIds();

		if (columnIds.isEmpty()) {
			issues.add("当前结果没有 incumbent 列");
			return new ValidationResult(false, false, Double.NaN, issues);
		}
		if (columnIds.size() > data.m) {
			issues.add("incumbent 使用列数超过机器上限: used=" + columnIds.size() + ", m=" + data.m);
		}

		for (int columnId : columnIds) {
			TWETColumn column = pool.getColumn(columnId);
			recomputedObjective += column.getCost();
			for (int job : column.getSequence()) {
				if (covered[job]) {
					issues.add("任务被重复覆盖: job=" + job);
				}
				covered[job] = true;
			}
		}
		for (int job = 1; job <= data.n; job++) {
			if (!covered[job]) {
				issues.add("存在未覆盖任务: job=" + job);
			}
		}

		double incumbentCost = result.getIncumbentCost();
		boolean objectiveConsistent = Double.isFinite(incumbentCost)
				&& Utility.compareLe(Math.abs(incumbentCost - recomputedObjective), Math.max(1e-6,
						1e-6 * Math.max(1.0, Math.abs(incumbentCost))));
		if (!objectiveConsistent) {
			issues.add("incumbent 目标值与列成本和不一致: incumbent=" + incumbentCost + ", recomputed="
					+ recomputedObjective);
		}
		return new ValidationResult(issues.isEmpty(), objectiveConsistent, recomputedObjective, issues);
	}

}
