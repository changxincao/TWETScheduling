package TWETBPC.BP;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import Common.Utility;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;

/**
 * 连续片段 i->j->k 分支器。
 */
public class ArcPairBrancher implements Brancher {

	private final double tolerance;

	public ArcPairBrancher(double tolerance) {
		this.tolerance = tolerance;
	}

	@Override
	public BranchResult branch(LP lp) {
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return BranchResult.none("No master solution");
		}
		Node base = lp.getNode();
		ArcPairChoice choice = findBestArcPair(lp, solution, base);
		if (choice == null) {
			return BranchResult.none("No fractional arc-pair found");
		}

		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();

		left.forbidArcPair(choice.first, choice.middle, choice.third);
		left.markArcPairRepair(choice.first, choice.middle, choice.third, false);

		right.requireArcPair(choice.first, choice.middle, choice.third);
		right.markArcPairRepair(choice.first, choice.middle, choice.third, true);

		return new BranchResult(true, left, right, "Branched on arc-pair (" + choice.first + ","
				+ choice.middle + "," + choice.third + ") q=" + choice.value);
	}

	private ArcPairChoice findBestArcPair(LP lp, TWETMasterSolution solution, Node node) {
		HashMap<Long, Double> values = collectArcPairValues(lp, solution);
		ArcPairChoice best = null;
		int base = lp.getData().n + 2;
		for (Map.Entry<Long, Double> entry : values.entrySet()) {
			long key = entry.getKey().longValue();
			int first = (int) (key / ((long) base * base));
			int middle = (int) ((key / base) % base);
			int third = (int) (key % base);
			best = betterArcPairChoice(best, node, first, middle, third, entry.getValue().doubleValue());
		}
		return best;
	}

	private HashMap<Long, Double> collectArcPairValues(LP lp, TWETMasterSolution solution) {
		HashMap<Long, Double> values = new HashMap<Long, Double>();
		int base = lp.getData().n + 2;
		for (Map.Entry<Integer, Double> entry : solution.getColumnValues().entrySet()) {
			double value = entry.getValue().doubleValue();
			if (!Utility.compareGt(value, tolerance)) {
				continue;
			}
			TWETColumn column = lp.getPool().getColumn(entry.getKey().intValue());
			List<Integer> sequence = column.getSequence();
			for (int i = 2; i < sequence.size(); i++) {
				int first = sequence.get(i - 2).intValue();
				int middle = sequence.get(i - 1).intValue();
				int third = sequence.get(i).intValue();
				long key = ((long) first) * base * base + ((long) middle) * base + third;
				Double old = values.get(Long.valueOf(key));
				values.put(Long.valueOf(key), Double.valueOf((old == null ? 0.0 : old.doubleValue()) + value));
			}
		}
		return values;
	}

	private ArcPairChoice betterArcPairChoice(ArcPairChoice best, Node node, int first, int middle, int third,
			double value) {
		if (node.getArcPairState(first, middle, third) != Node.ARC_PAIR_FREE
				|| node.isArcForbidden(first, middle) || node.isArcForbidden(middle, third)
				|| node.isArcPairForbidden(first, middle, third)) {
			return best;
		}
		if (!Utility.compareGt(value, tolerance) || !Utility.compareLt(value, 1.0 - tolerance)) {
			return best;
		}
		double frac = Math.abs(value - Math.rint(value));
		if (Utility.compareLe(frac, tolerance)) {
			return best;
		}
		double score = Math.abs(value - 0.5);
		if (best == null || Utility.compareLt(score, best.score)) {
			return new ArcPairChoice(first, middle, third, value, score);
		}
		return best;
	}

	@Override
	public String getName() {
		return "ArcPairBrancher";
	}

	private static final class ArcPairChoice {
		final int first;
		final int middle;
		final int third;
		final double value;
		final double score;

		ArcPairChoice(int first, int middle, int third, double value, double score) {
			this.first = first;
			this.middle = middle;
			this.third = third;
			this.value = value;
			this.score = score;
		}
	}
}
