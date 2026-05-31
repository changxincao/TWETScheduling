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
 */
final class PricingColumnCostRechecker {

	private static final double DEBUG_MISMATCH_TOLERANCE = 1e-5;

	private PricingColumnCostRechecker() {
	}

	static TWETColumn buildInferredColumn(List<Integer> sequence, double reducedCost, LP lp, Data data,
			ColumnSource source) {
		return new TWETColumn(-1, sequence, data.n, objectiveCostFromReducedCost(sequence, reducedCost, lp), source,
				false);
	}

	static Result evaluate(TWETColumn candidateColumn, double inferredReducedCost, LP lp, Data data,
			TWETColumnEvaluator evaluator, boolean debug, String debugPrefix) {
		double checkedCost = evaluator.evaluate(candidateColumn.getSequence());
		if (Utility.isBigMValue(checkedCost)) {
			return null;
		}
		double checkedReducedCost = reducedCost(candidateColumn.getSequence(), checkedCost, lp);
		if (debug && Utility.compareGt(Math.abs(checkedReducedCost - inferredReducedCost),
				DEBUG_MISMATCH_TOLERANCE)) {
			System.err.println(debugPrefix + " reduced-cost mismatch: inferred=" + inferredReducedCost
					+ ", checked=" + checkedReducedCost + ", sequence=" + candidateColumn.getSequence());
		}
		return new Result(candidateColumn, checkedCost, checkedReducedCost);
	}

	private static double objectiveCostFromReducedCost(List<Integer> sequence, double reducedCost, LP lp) {
		double cost = reducedCost + lp.getMachineDual();
		int prev = 0;
		for (int job : sequence) {
			cost += lp.getJobDual(job);
			cost += lp.getArcDual(prev, job);
			prev = job;
		}
		cost += lp.getArcDual(prev, lp.getNode().sinkId());
		return cost;
	}

	private static double reducedCost(List<Integer> sequence, double cost, LP lp) {
		double reducedCost = cost - lp.getMachineDual();
		int prev = 0;
		for (int job : sequence) {
			reducedCost -= lp.getJobDual(job);
			reducedCost -= lp.getArcDual(prev, job);
			prev = job;
		}
		reducedCost -= lp.getArcDual(prev, lp.getNode().sinkId());
		return reducedCost;
	}

	static final class Result {
		final TWETColumn originalColumn;
		final double checkedCost;
		final double checkedReducedCost;

		Result(TWETColumn originalColumn, double checkedCost, double checkedReducedCost) {
			this.originalColumn = originalColumn;
			this.checkedCost = checkedCost;
			this.checkedReducedCost = checkedReducedCost;
		}

		TWETColumn checkedColumn(Data data) {
			return new TWETColumn(-1, originalColumn.getSequence(), data.n, checkedCost, originalColumn.getSource(),
					originalColumn.isSeedColumn());
		}
	}
}
