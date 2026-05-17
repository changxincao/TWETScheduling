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
 * <p>
 * 2026-05-17: 当前主问题允许“内部机器列 + 外包变量 y_j”共同覆盖任务，
 * 因此校验时不能只看列集合，还要把外包变量纳入覆盖和目标值重算。
 */
public final class BPCSolutionValidator {

	private BPCSolutionValidator() {
	}

	public static ValidationResult validate(Data data, Pool pool, TWETSolveResult result) {
		ArrayList<String> issues = new ArrayList<String>();
		boolean[] covered = new boolean[data.n + 1];
		double recomputedObjective = 0.0;
		List<Integer> columnIds = result.getIncumbentColumnIds();
		double[] outsourcingValues = result.getIncumbentOutsourcingValues();

		if (columnIds.size() > data.m) {
			issues.add("incumbent 使用内部机器列数超过机器上限: used=" + columnIds.size() + ", m=" + data.m);
		}

		for (int columnId : columnIds) {
			TWETColumn column = pool.getColumn(columnId);
			recomputedObjective += column.getCost();
			for (int job : column.getSequence()) {
				if (covered[job]) {
					issues.add("任务被内部列重复覆盖: job=" + job);
				}
				covered[job] = true;
			}
		}

		double outsourcingBaseline = 0.0;
		for (int job = 1; job <= data.n; job++) {
			double value = job < outsourcingValues.length ? outsourcingValues[job] : 0.0;
			if (!Utility.compareGt(value, 1e-6)) {
				continue;
			}
			if (!Utility.compareLe(Math.abs(value - 1.0), 1e-6)) {
				issues.add("外包变量不是 0/1 整数值: job=" + job + ", value=" + value);
			}
			if (covered[job]) {
				issues.add("任务同时被内部列和外包变量覆盖: job=" + job);
			}
			covered[job] = true;
			if (Utility.isBigMValue(data.outsourcingCost[job])) {
				issues.add("任务被外包但该任务外包成本不可用: job=" + job);
			} else {
				outsourcingBaseline += data.outsourcingCost[job];
			}
		}
		recomputedObjective += data.evaluateOutsourcingCost(outsourcingBaseline);

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
			issues.add("incumbent 目标值与列成本和外包成本不一致: incumbent=" + incumbentCost + ", recomputed="
					+ recomputedObjective);
		}
		return new ValidationResult(issues.isEmpty(), objectiveConsistent, recomputedObjective, issues);
	}

}
