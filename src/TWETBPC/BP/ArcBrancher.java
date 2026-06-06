package TWETBPC.BP;

import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;
import Common.Utility;

/**
 * 弧分支器。
 * <p>
 * 该分支器对应旧 VRP 参考代码中的 BranchD：在当前 master 解中寻找取值为分数的相邻弧，
 * 然后生成“禁止该弧”和“要求该弧”两个子节点。
 */
public class ArcBrancher implements Brancher {

	/** 判断某条弧是否为整数时使用的容差。 */
	private final double tolerance;

	/** 构造一个弧分支器。 */
	public ArcBrancher(double tolerance) {
		this.tolerance = tolerance;
	}

	@Override
	public BranchResult branch(LP lp) {
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return BranchResult.none("No master solution");
		}

		Node base = lp.getNode();
		int sink = base.sinkId();
		// 2026-06-02: 对齐旧 VRP BranchD 的候选优先级：先分支真实 job 之间的内部弧；
		// 只有内部弧没有分数候选时，才用 source/sink 端点弧兜底。每一层内仍选最接近 0.5 的弧。
		ArcChoice choice = findBestInternalArc(lp, solution, base, sink);
		if (choice == null) {
			choice = findBestEndpointArc(lp, solution, base, sink);
		}
		if (choice == null) {
			return BranchResult.none("No fractional arc found");
		}
		int bestFrom = choice.from;
		int bestTo = choice.to;

		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();
		left.forbidArc(bestFrom, bestTo);
		left.markArcRepair(bestFrom, bestTo, false);

		// 要求 i->j 出现时，真实 job i 不能再接其他后继，真实 job j 不能再有其他前驱。
		// depot/sink 端点不做这种排他处理，避免误禁其他机器的起点或终点。
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
		right.markArcRepair(bestFrom, bestTo, true);
		return new BranchResult(true, left, right, "Branched on arc (" + bestFrom + "," + bestTo + ")");
	}

	private ArcChoice findBestInternalArc(LP lp, TWETMasterSolution solution, Node node, int sink) {
		ArcChoice best = null;
		for (int from = 1; from < sink; from++) {
			for (int to = 1; to < sink; to++) {
				best = betterArcChoice(best, lp, solution, node, sink, from, to);
			}
		}
		return best;
	}

	private ArcChoice findBestEndpointArc(LP lp, TWETMasterSolution solution, Node node, int sink) {
		ArcChoice best = null;
		for (int job = 1; job < sink; job++) {
			// 2026-06-06: sink 是虚拟终点。endpoint 分支只保留 source->job，避免把无实际顺序含义的 job->sink
			// 写成后续节点的硬分支约束，干扰 pricing/subtree 的弧语义分析。
			best = betterArcChoice(best, lp, solution, node, sink, 0, job);
		}
		return best;
	}

	private ArcChoice betterArcChoice(ArcChoice best, LP lp, TWETMasterSolution solution, Node node, int sink,
			int from, int to) {
		if (from == to || node.getArcState(from, to) != Node.ARC_FREE || node.isArcForbidden(from, to)) {
			return best;
		}
		double value = solution.getArcValue(lp.getPool(), from, to, sink);
		double frac = Math.abs(value - Math.rint(value));
		if (Utility.compareLe(frac, tolerance)) {
			return best;
		}
		double score = Math.abs(value - 0.5);
		if (best == null || Utility.compareLt(score, best.score)) {
			return new ArcChoice(from, to, score);
		}
		return best;
	}

	@Override
	public String getName() {
		return "ArcBrancher";
	}

	private static final class ArcChoice {
		final int from;
		final int to;
		final double score;

		ArcChoice(int from, int to, double score) {
			this.from = from;
			this.to = to;
			this.score = score;
		}
	}
}
