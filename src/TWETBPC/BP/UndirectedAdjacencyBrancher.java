package TWETBPC.BP;

import Common.Utility;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * 基于无向相邻流量 q_ij = x_ij + x_ji 的 pair 分支器。
 * <p>
 * 该规则插在机器数分支之后、directed arc 分支之前：先尝试决定两个真实 job 是否相邻；
 * 如果所有 pair 流量都已经整数，再交给后续 directed arc 分支决定方向。
 */
public class UndirectedAdjacencyBrancher implements Brancher {

	private final double tolerance;

	public UndirectedAdjacencyBrancher(double tolerance) {
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
		PairChoice choice = findBestPair(lp, solution, base, sink);
		if (choice == null) {
			return BranchResult.none("No fractional undirected adjacency pair found");
		}

		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();

		left.forbidAdjacencyPair(choice.firstJob, choice.secondJob);
		left.markAdjacencyPairRepair(choice.firstJob, choice.secondJob, false);

		right.requireAdjacencyPair(choice.firstJob, choice.secondJob);
		right.markAdjacencyPairRepair(choice.firstJob, choice.secondJob, true);

		return new BranchResult(true, left, right, "Branched on undirected adjacency pair ("
				+ choice.firstJob + "," + choice.secondJob + ") q=" + choice.value);
	}

	private PairChoice findBestPair(LP lp, TWETMasterSolution solution, Node node, int sink) {
		PairChoice best = null;
		for (int first = 1; first < sink; first++) {
			for (int second = first + 1; second < sink; second++) {
				best = betterPairChoice(best, lp, solution, node, sink, first, second);
			}
		}
		return best;
	}

	private PairChoice betterPairChoice(PairChoice best, LP lp, TWETMasterSolution solution, Node node, int sink,
			int first, int second) {
		if (node.getAdjacencyPairState(first, second) != Node.ADJACENCY_FREE
				|| node.isArcForbidden(first, second) || node.isArcForbidden(second, first)) {
			return best;
		}
		double value = solution.getArcValue(lp.getPool(), first, second, sink)
				+ solution.getArcValue(lp.getPool(), second, first, sink);
		// 当前节点状态只支持典型 0/1 pair 分支；q>=1 的广义 floor/ceil 分支留给后续扩展。
		if (!Utility.compareGt(value, tolerance) || !Utility.compareLt(value, 1.0 - tolerance)) {
			return best;
		}
		double frac = Math.abs(value - Math.rint(value));
		if (Utility.compareLe(frac, tolerance)) {
			return best;
		}
		double score = Math.abs(value - 0.5);
		if (best == null || Utility.compareLt(score, best.score)) {
			return new PairChoice(first, second, value, score);
		}
		return best;
	}

	@Override
	public String getName() {
		return "UndirectedAdjacencyBrancher";
	}

	private static final class PairChoice {
		final int firstJob;
		final int secondJob;
		final double value;
		final double score;

		PairChoice(int firstJob, int secondJob, double value, double score) {
			this.firstJob = firstJob;
			this.secondJob = secondJob;
			this.value = value;
			this.score = score;
		}
	}
}
