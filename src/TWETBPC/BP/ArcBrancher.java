package TWETBPC.BP;

import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;
import Common.Utility;

/**
 * 弧分支器。
 * <p>
 * 这个类在思想上对应旧参考代码中的 BranchD：
 * 都是寻找某条取值分数的弧，然后生成“禁止该弧”和“强制该弧”两个子节点。
 * <p>
 * 在当前 TWET 骨架里，“弧”表示调度序列中的相邻关系。
 * 例如：
 * <ul>
 * <li>0 -> j：某 job 作为某台机器上的首任务；</li>
 * <li>i -> j：job i 紧前于 job j；</li>
 * <li>i -> sink：job i 为末任务。</li>
 * </ul>
 */
public class ArcBrancher implements Brancher {

	/** 判断某弧是否为整数时使用的容差。 */
	private final double tolerance;

	/**
	 * 构造一个弧分支器。
	 */
	public ArcBrancher(double tolerance) {
		this.tolerance = tolerance;
	}

	@Override
	/**
	 * 在当前 master 解上寻找最适合分支的分数弧。
	 * <p>
	 * 当前选择标准是：优先找最接近 0.5 的分数弧，
	 * 这与旧 BPC 里“找最像分支对象的分数变量”是一致的思路。
	 */
	public BranchResult branch(LP lp) {
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return BranchResult.none("No master solution");
		}

		Node base = lp.getNode();
		int sink = base.sinkId();
		int bestFrom = -1;
		int bestTo = -1;
		double bestFrac = Double.MAX_VALUE;

		for (int from = 0; from <= sink; from++) {
			for (int to = 1; to <= sink; to++) {
				if (from == to || base.getArcState(from, to) != Node.ARC_FREE) {
					continue;
				}
				double value = solution.getArcValue(lp.getPool(), from, to, sink);
				double frac = Math.abs(value - Math.rint(value));
				if (Utility.compareLe(frac, tolerance)) {
					continue;
				}
				double score = Math.abs(value - 0.5);
				if (Utility.compareLt(score, bestFrac)) {
					bestFrac = score;
					bestFrom = from;
					bestTo = to;
				}
			}
		}

		// 没有找到分数弧，说明本规则在当前节点下无法继续分支。
		if (bestFrom == -1) {
			return BranchResult.none("No fractional arc found");
		}

		// 左子节点：禁止该弧；右子节点：要求该弧必须出现。
		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();
		left.forbidArc(bestFrom, bestTo);
		// 2026-05-17: 强制弧分支参考旧 VRP BranchD。
		// 要求 i->j 出现时，若 i 是真实 job，则它不能再接别的后继；
		// 若 j 是真实 job，则它不能再有别的前驱。depot/sink 端点不做这种排他处理，
		// 否则会错误禁止其他机器的起点或终点。
		if (bestFrom != 0 && bestFrom != sink) {
			for (int to = 1; to <= sink; to++) {
				if (to != bestTo) {
					right.forbidArc(bestFrom, to);
				}
			}
		}
		if (bestTo != sink) {
			for (int from = 0; from <= sink; from++) {
				if (from != bestFrom) {
					right.forbidArc(from, bestTo);
				}
			}
		}
		right.requireArc(bestFrom, bestTo);
		return new BranchResult(true, left, right, "Branched on arc (" + bestFrom + "," + bestTo + ")");
	}

	@Override
	/** @return 分支器名称 */
	public String getName() {
		return "ArcBrancher";
	}

}
