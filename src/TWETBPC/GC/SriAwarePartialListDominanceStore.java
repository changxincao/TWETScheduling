package TWETBPC.GC;

import java.util.ArrayList;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.Utility;
import TWETBPC.Util.PackedBitSet;

/**
 * SRI-aware 的 partial-list dominance 包装器。
 *
 * 2026-06-13: 这里按旧 VRP 的 subset-row dominance 思路处理跨 SRI 状态比较：如果支配方
 * 当前某个 SRI 计数为 1，而被支配方为偶数，并且被支配方还能到达一个支配方没有访问过的
 * cut 内 job，则支配方未来可能额外触发一次 SRI penalty。比较前临时把支配方函数整体上移
 * 这份 penalty，再交给普通 partial-list dominance。这样比“相同 SRI signature 才比较”更强，
 * 同时不改变 label 自身保存的真实 frontier。
 */
final class SriAwarePartialListDominanceStore implements DominanceStore {

	private final PartialListDominanceStore delegate;
	private final ArrayList<Double> sriDuals;
	private final ArrayList<int[]> sriScopes;

	SriAwarePartialListDominanceStore(Direction direction, ArrayList<Double> sriDuals, ArrayList<int[]> sriScopes) {
		this.sriDuals = sriDuals == null ? new ArrayList<Double>() : sriDuals;
		this.sriScopes = sriScopes == null ? new ArrayList<int[]>() : sriScopes;
		this.delegate = new PartialListDominanceStore(direction, new PartialListDominanceStore.DominanceFrontierAdjuster() {
			@Override
			public PiecewiseLinearFunction adjustDominatingFrontier(Label trimmed, Label dominator, Direction dir) {
				return adjustedDominatingFrontier(trimmed, dominator);
			}
		});
	}

	@Override
	public boolean insertOrDominate(Label label) {
		return delegate.insertOrDominate(label);
	}

	@Override
	public ArrayList<Label> getActiveLabels() {
		return delegate.getActiveLabels();
	}

	@Override
	public void collectActiveLabels(ArrayList<Label> buffer) {
		delegate.collectActiveLabels(buffer);
	}

	@Override
	public boolean dominatesSinglePoint(PackedBitSet reachableSet, int reachableCardinality, double pointTime,
			double pointValue) {
		// 单点接口没有传入单点 label 的 SRI count / visited set，无法计算旧 VRP 的 UseSR 补偿；
		// 单点之间仍在 GCNGBBStyleBidirectionalNgDssr 的 SinglePointStore 中按相同 SRI key 比较。
		return false;
	}

	private PiecewiseLinearFunction adjustedDominatingFrontier(Label trimmed, Label dominator) {
		if (dominator == null || dominator.frontier == null) {
			return null;
		}
		double compensation = sriDominanceCompensation(dominator, trimmed);
		if (!Utility.compareGt(compensation, 0.0)) {
			return dominator.frontier;
		}
		PiecewiseLinearFunction adjusted = dominator.frontier.copy();
		adjusted.shiftYInPlace(compensation);
		return adjusted;
	}

	private double sriDominanceCompensation(Label dominator, Label dominated) {
		if (!(dominator instanceof SriStateLabel) || !(dominated instanceof SriStateLabel)) {
			return 0.0;
		}
		byte[] dominatorCounts = ((SriStateLabel) dominator).sriCounts();
		byte[] dominatedCounts = ((SriStateLabel) dominated).sriCounts();
		int limit = Math.min(Math.min(dominatorCounts.length, dominatedCounts.length),
				Math.min(sriDuals.size(), sriScopes.size()));
		double compensation = 0.0;
		for (int sriIndex = 0; sriIndex < limit; sriIndex++) {
			if (dominatorCounts[sriIndex] == 1 && (dominatedCounts[sriIndex] & 1) == 0
					&& mayDominatedUseSriLater(dominator, dominated, sriScopes.get(sriIndex))) {
				compensation -= sriDuals.get(sriIndex).doubleValue();
			}
		}
		return compensation;
	}

	private boolean mayDominatedUseSriLater(Label dominator, Label dominated, int[] scope) {
		if (scope == null || dominated.reachableSet == null || dominated.visitedSet == null || dominator.visitedSet == null) {
			return false;
		}
		for (int job : scope) {
			if (dominator.visitedSet.contains(job)) {
				continue;
			}
			if (!dominated.visitedSet.contains(job) && dominated.reachableSet.contains(job)) {
				return true;
			}
		}
		return false;
	}
}
