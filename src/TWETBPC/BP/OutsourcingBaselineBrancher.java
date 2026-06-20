package TWETBPC.BP;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * SP1 外包列模式下的总 baseline 区间分支。
 * <p>
 * 外包集合列没有 SP2 的 z_s 分段变量。这里改为按 tariff 断点 q 切分总 baseline：
 * 左支 B <= q，右支 B >= q。这样保留列化外包的集合变量，同时补回一部分原 tariff 分支的全局区间信息。
 */
public class OutsourcingBaselineBrancher implements Brancher {

	private final double tolerance;

	public OutsourcingBaselineBrancher(double tolerance) {
		this.tolerance = tolerance;
	}

	@Override
	public BranchResult branch(LP lp) {
		if (!lp.isColumnizedOutsourcing()) {
			return BranchResult.none("Outsourcing columns disabled");
		}
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return BranchResult.none("No master solution");
		}
		Node base = lp.getNode();
		double currentBaseline = currentOutsourcingBaseline(lp.getData(), solution.getOutsourcingValues());
		double split = chooseSplitPoint(lp.getData(), base, currentBaseline);
		if (!Double.isFinite(split)) {
			return BranchResult.none("No outsourcing baseline tariff breakpoint in current interval");
		}

		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();
		left.tightenOutsourcingBaselineUpperBound(split);
		right.tightenOutsourcingBaselineLowerBound(split);
		return new BranchResult(true, left, right,
				"Branched on outsourcing baseline B at " + split + " (current=" + currentBaseline + ")");
	}

	private double currentOutsourcingBaseline(Data data, double[] outsourcingValues) {
		double baseline = 0.0;
		for (int job = 1; job <= data.n && job < outsourcingValues.length; job++) {
			if (!Utility.isBigMValue(data.outsourcingCost[job])) {
				baseline += data.outsourcingCost[job] * outsourcingValues[job];
			}
		}
		return baseline;
	}

	private double chooseSplitPoint(Data data, Node node, double currentBaseline) {
		if (data.outsourcingCostFunction == null || data.outsourcingCostFunction.head == null) {
			return Double.NaN;
		}
		double lower = node.getOutsourcingBaselineLowerBound();
		double upper = node.getOutsourcingBaselineUpperBound();
		double best = Double.NaN;
		double bestDistance = Double.POSITIVE_INFINITY;
		PiecewiseLinearFunction.Segment seg = data.outsourcingCostFunction.head;
		while (seg != null) {
			best = betterBreakpoint(seg.start, lower, upper, currentBaseline, best, bestDistance);
			if (Double.isFinite(best)) {
				bestDistance = Math.abs(best - currentBaseline);
			}
			best = betterBreakpoint(seg.end, lower, upper, currentBaseline, best, bestDistance);
			if (Double.isFinite(best)) {
				bestDistance = Math.abs(best - currentBaseline);
			}
			seg = seg.next;
		}
		return best;
	}

	private double betterBreakpoint(double point, double lower, double upper, double currentBaseline,
			double best, double bestDistance) {
		if (Utility.compareLe(point, lower + tolerance) || Utility.compareGe(point, upper - tolerance)) {
			return best;
		}
		if (Utility.compareLe(Math.abs(point - currentBaseline), tolerance)) {
			return best;
		}
		double distance = Math.abs(point - currentBaseline);
		if (!Double.isFinite(best) || Utility.compareLt(distance, bestDistance)) {
			return point;
		}
		return best;
	}

	@Override
	public String getName() {
		return "OutsourcingBaselineBrancher";
	}
}
