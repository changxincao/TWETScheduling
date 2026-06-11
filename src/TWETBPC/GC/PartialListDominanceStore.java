package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Iterator;

import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
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

	private final ArrayList<Label> labels;
	private final Direction direction;

	PartialListDominanceStore(Direction direction) {
		this.labels = new ArrayList<Label>();
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

		for (Label existing : labels) {
			if (existing.isDominated || existing.frontier == null || existing.frontier.head == null) {
				continue;
			}
			comparisons++;
			if (existing.reachableCardinality < label.reachableCardinality) {
				cardinalitySkips++;
				continue;
			}
			if (existing.reachableSet.isSupersetOf(label.reachableSet)
					&& trimFrontierBy(label, existing.frontier)) {
				label.isDominated = true;
				labelsRejected++;
				fullTrims++;
				return true;
			}
		}
		label.refreshMinReducedCost();
		if (Utility.isBigMValue(label.minReducedCost)) {
			label.isDominated = true;
			labelsRejected++;
			fullTrims++;
			return true;
		}

		for (Iterator<Label> it = labels.iterator(); it.hasNext();) {
			Label existing = it.next();
			if (existing.isDominated || existing.frontier == null || existing.frontier.head == null) {
				it.remove();
				continue;
			}
			comparisons++;
			if (label.reachableCardinality < existing.reachableCardinality) {
				cardinalitySkips++;
				continue;
			}
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

		labels.add(label);
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
		for (Label label : labels) {
			if (!label.isDominated && label.frontier != null && label.frontier.head != null) {
				buffer.add(label);
			}
		}
	}

	@Override
	public boolean dominatesSinglePoint(PackedBitSet reachableSet, int reachableCardinality, double pointTime,
			double pointValue) {
		singlePointChecks++;
		for (Label label : labels) {
			if (label.isDominated || label.frontier == null || label.frontier.head == null
					|| label.reachableCardinality < reachableCardinality
					|| !label.reachableSet.isSupersetOf(reachableSet)) {
				continue;
			}
			double value = label.frontier.evaluate(pointTime);
			if (!Utility.compareGt(value, pointValue)) {
				return true;
			}
		}
		return false;
	}

	private boolean trimFrontierBy(Label label, PiecewiseLinearFunction dominatingFrontier) {
		boolean deleted = label.frontier.updateDominatedIntervals(dominatingFrontier, direction);
		label.refreshMinReducedCost();
		if (deleted || label.frontier == null || label.frontier.head == null) {
			label.isDominated = true;
			return true;
		}
		partialTrims++;
		return false;
	}

	private static String diagnosticSuffix() {
		return diagnosticContext == null || diagnosticContext.isEmpty() ? "" : ", context=" + diagnosticContext;
	}
}
