package TWETBPC.BP;

import Common.Utility;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * SP2 外包 tariff segment 选择变量的分支器。
 * <p>
 * 2026-05-17: RMP 中 `z_s` 当前是 LP relaxation。即使内部 route/arc 已经整数，
 * 多个 tariff segment 仍可能分数混合。该分支器按用户给出的 SP2 分支规则：
 * 选择最接近 0.5 的分数 segment，生成 `z_s <= 0` 和 `z_s >= 1` 两个子节点。
 * 这些约束只进入 master，不改变 private-route pricing 结构，只通过新的 dual 间接影响定价。
 */
public class TariffSegmentBrancher implements Brancher {

	private final double tolerance;

	public TariffSegmentBrancher(double tolerance) {
		this.tolerance = tolerance;
	}

	@Override
	public BranchResult branch(LP lp) {
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return BranchResult.none("No master solution");
		}
		double[] values = solution.getOutsourceSegmentValues();
		int bestSegment = -1;
		double bestScore = Double.MAX_VALUE;
		for (int segment = 0; segment < values.length; segment++) {
			double value = values[segment];
			if (Utility.compareLe(Math.abs(value - Math.rint(value)), tolerance)) {
				continue;
			}
			double score = Math.abs(value - 0.5);
			if (Utility.compareLt(score, bestScore)) {
				bestScore = score;
				bestSegment = segment;
			}
		}
		if (bestSegment < 0) {
			return BranchResult.none("Tariff segment variables already integral");
		}

		Node base = lp.getNode();
		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();
		left.forbidTariffSegment(bestSegment);
		right.requireTariffSegment(bestSegment);
		return new BranchResult(true, left, right,
				"Branched on outsourcing tariff segment z_" + bestSegment + "=" + values[bestSegment]);
	}

	@Override
	public String getName() {
		return "TariffSegmentBrancher";
	}

}
