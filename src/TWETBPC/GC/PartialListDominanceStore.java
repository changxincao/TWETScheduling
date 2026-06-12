package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Iterator;

import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.TrimResult;
import Common.Utility;
import TWETBPC.Util.PackedBitSet;

/**
 * 实验用 partial dominance 存储结构。
 * <p>
 * 2026-06-09: 当前版本只服务于不带 SRI/ng 的半域双向实验分支。集合语义沿用 elementary
 * pricing 的 reachable-set：reachable set 更大的 label 未来选择更多，才允许在函数区间上支配
 * reachable set 更小的 label。被支配的时间区间直接在 label.frontier 中置为 big-M 并 normalize，
 * 不额外维护区间对象，便于先评估 list-based partial dominance 的工程代价。
 */
final class PartialListDominanceStore implements DominanceStore {

	private static long labelsInserted;
	private static long labelsRejected;
	private static long labelsDeleted;
	private static long comparisons;
	private static long cardinalitySkips;
	private static long partialTrims;
	private static long fullTrims;
	private static long singlePointChecks;
	private static String diagnosticContext = "";

	private final ArrayList<ArrayList<Label>> labelsByCardinality;
	private final Direction direction;

	PartialListDominanceStore(Direction direction) {
		this.labelsByCardinality = new ArrayList<ArrayList<Label>>();
		this.direction = direction;
	}

	static void resetStatistics() {
		labelsInserted = 0L;
		labelsRejected = 0L;
		labelsDeleted = 0L;
		comparisons = 0L;
		cardinalitySkips = 0L;
		partialTrims = 0L;
		fullTrims = 0L;
		singlePointChecks = 0L;
	}

	static void setDiagnosticContext(String context) {
		diagnosticContext = context == null ? "" : context;
	}

	static String statisticsSummary() {
		return "partialList labels kept/rejected/deleted=" + labelsInserted + "/" + labelsRejected + "/"
				+ labelsDeleted + ", comparisons=" + comparisons + ", cardinalitySkips=" + cardinalitySkips
				+ ", trims partial/full=" + partialTrims + "/" + fullTrims + ", singlePointChecks="
				+ singlePointChecks + diagnosticSuffix();
	}

	@Override
	public boolean insertOrDominate(Label label) {
		if (label == null || label.frontier == null || label.frontier.head == null) {
			labelsRejected++;
			return true;
		}

		cardinalitySkips += countLabelsInBuckets(0, label.reachableCardinality - 1);
		for (int cardinality = label.reachableCardinality; cardinality < labelsByCardinality.size(); cardinality++) {
			ArrayList<Label> bucket = labelsByCardinality.get(cardinality);
			for (Label existing : bucket) {
				if (existing.isDominated || existing.frontier == null || existing.frontier.head == null) {
					continue;
				}
				comparisons++;
				if (existing.reachableSet.isSupersetOf(label.reachableSet)
						&& trimFrontierBy(label, existing.frontier)) {
					label.isDominated = true;
					labelsRejected++;
					fullTrims++;
					return true;
				}
			}
		}
		label.refreshMinReducedCost();
		if (Utility.isBigMValue(label.minReducedCost)) {
			label.isDominated = true;
			labelsRejected++;
			fullTrims++;
			return true;
		}

		cardinalitySkips += countLabelsInBuckets(label.reachableCardinality + 1, labelsByCardinality.size() - 1);
		int maxCandidateCardinality = Math.min(label.reachableCardinality, labelsByCardinality.size() - 1);
		for (int cardinality = 0; cardinality <= maxCandidateCardinality; cardinality++) {
			ArrayList<Label> bucket = labelsByCardinality.get(cardinality);
			for (Iterator<Label> it = bucket.iterator(); it.hasNext();) {
				Label existing = it.next();
				if (existing.isDominated || existing.frontier == null || existing.frontier.head == null) {
					it.remove();
					continue;
				}
				comparisons++;
				if (!label.reachableSet.isSupersetOf(existing.reachableSet)) {
					continue;
				}
				boolean deleted = trimFrontierBy(existing, label.frontier);
				if (deleted || Utility.isBigMValue(existing.minReducedCost)) {
					existing.isDominated = true;
					it.remove();
					labelsDeleted++;
					fullTrims++;
				}
			}
		}

		bucketFor(label.reachableCardinality).add(label);
		labelsInserted++;
		return false;
	}

	@Override
	public ArrayList<Label> getActiveLabels() {
		ArrayList<Label> active = new ArrayList<Label>();
		collectActiveLabels(active);
		return active;
	}

	@Override
	public void collectActiveLabels(ArrayList<Label> buffer) {
		for (ArrayList<Label> bucket : labelsByCardinality) {
			for (Label label : bucket) {
				if (!label.isDominated && label.frontier != null && label.frontier.head != null) {
					buffer.add(label);
				}
			}
		}
	}

	@Override
	public boolean dominatesSinglePoint(PackedBitSet reachableSet, int reachableCardinality, double pointTime,
			double pointValue) {
		singlePointChecks++;
		cardinalitySkips += countLabelsInBuckets(0, reachableCardinality - 1);
		for (int cardinality = reachableCardinality; cardinality < labelsByCardinality.size(); cardinality++) {
			ArrayList<Label> bucket = labelsByCardinality.get(cardinality);
			for (Label label : bucket) {
				if (label.isDominated || label.frontier == null || label.frontier.head == null
						|| !label.reachableSet.isSupersetOf(reachableSet)) {
					continue;
				}
				double value = label.frontier.evaluate(pointTime);
				if (!Utility.compareGt(value, pointValue)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean trimFrontierBy(Label label, PiecewiseLinearFunction dominatingFrontier) {
		if (!hasPositiveOverlap(label.frontier, dominatingFrontier)) {
			return false;
		}
		TrimResult result = label.frontier.updateDominatedIntervalsDetailed(dominatingFrontier, direction);
		if (result == TrimResult.NO_CHANGE) {
			return false;
		}
		label.refreshMinReducedCost();
		if (result == TrimResult.EMPTY || label.frontier == null || label.frontier.head == null) {
			label.isDominated = true;
			return true;
		}
		partialTrims++;
		return false;
	}

	private boolean hasPositiveOverlap(PiecewiseLinearFunction left, PiecewiseLinearFunction right) {
		if (left == null || right == null || left.head == null || right.head == null) {
			return false;
		}
		return Utility.compareLt(Math.max(left.head.start, right.head.start), Math.min(left.tail.end, right.tail.end));
	}

	private static String diagnosticSuffix() {
		return diagnosticContext == null || diagnosticContext.isEmpty() ? "" : ", context=" + diagnosticContext;
	}

	private ArrayList<Label> bucketFor(int cardinality) {
		while (labelsByCardinality.size() <= cardinality) {
			labelsByCardinality.add(new ArrayList<Label>());
		}
		return labelsByCardinality.get(cardinality);
	}

	private long countLabelsInBuckets(int fromCardinality, int toCardinality) {
		int from = Math.max(0, fromCardinality);
		int to = Math.min(toCardinality, labelsByCardinality.size() - 1);
		long count = 0L;
		for (int cardinality = from; cardinality <= to; cardinality++) {
			count += labelsByCardinality.get(cardinality).size();
		}
		return count;
	}
}
