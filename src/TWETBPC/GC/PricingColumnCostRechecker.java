package TWETBPC.GC;

import java.util.List;

import Basic.Data;
import Common.Utility;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;

/**
 * 双向 pricing 候选列的最终成本复核工具。
 * <p>
 * pricing 阶段可以先用 label/join 推导出的 inferred reduced cost 维护 top-K 候选堆；
 * K 堆固定后，再用该工具对最终候选恢复真实 sequence cost，避免把 pi-window 口径写入永久列池。
 * <p>
 * 2026-05-31: inferred/checked reduced-cost mismatch 诊断已关闭。当前正式路径只需要
 * evaluator 的真实 objective cost；若后续要重新诊断 pi-window 数值差异，再在这里临时恢复
 * checked reduced cost 计算和 mismatch 输出。
 */
final class PricingColumnCostRechecker {

	private PricingColumnCostRechecker() {
	}

	static TWETColumn buildInferredColumn(List<Integer> sequence, double reducedCost, LP lp, Data data,
			ColumnSource source) {
		return new TWETColumn(-1, sequence, data.n, objectiveCostFromReducedCost(sequence, reducedCost, lp), source,
				false);
	}

	static Result evaluate(TWETColumn candidateColumn, Data data, TWETColumnEvaluator evaluator) {
		double checkedCost = evaluator.evaluate(candidateColumn.getSequence());
		if (Utility.isBigMValue(checkedCost)) {
			return null;
		}
		return new Result(candidateColumn, checkedCost);
	}

	private static double objectiveCostFromReducedCost(List<Integer> sequence, double reducedCost, LP lp) {
		double cost = reducedCost + lp.getMachineDual();
		int prev = 0;
		int prevPrev = 0;
		for (int job : sequence) {
			cost += lp.getJobDual(job);
			cost += lp.getArcDual(prev, job);
			cost += lp.getArcPairDual(prevPrev, prev, job);
			prevPrev = prev;
			prev = job;
		}
		cost += lp.getArcDual(prev, lp.getNode().sinkId());
		return cost;
	}

	static final class Result {
		final TWETColumn originalColumn;
		final double checkedCost;

		Result(TWETColumn originalColumn, double checkedCost) {
			this.originalColumn = originalColumn;
			this.checkedCost = checkedCost;
		}

		TWETColumn checkedColumn(Data data) {
			return new TWETColumn(-1, originalColumn.getSequence(), data.n, checkedCost, originalColumn.getSource(),
					originalColumn.isSeedColumn());
		}
	}
}
