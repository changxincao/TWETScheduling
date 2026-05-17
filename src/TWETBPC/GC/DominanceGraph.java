package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Iterator;

import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import TWETBPC.Util.PackedBitSet;

/**
 * 单个 terminal job 上的 dominance graph。
 * <p>
 * 第一版只实现完整 set dominance，不做 partial dominance。也就是说，
 * graph 中 eligible nodes 的下包络若能在整个定义域上压住新 label，
 * 则删除新 label；否则保留整条 label，不切函数片段。
 */
final class DominanceGraph {

	private final ArrayList<DominanceNode> nodes = new ArrayList<DominanceNode>();

	/**
	 * 尝试插入一个真实 label。
	 *
	 * @return true 表示该 label 被已有 graph 完整占优并丢弃；false 表示已插入 graph
	 */
	boolean insertOrDominate(Label label) {
		PiecewiseLinearFunction eligibleEnvelope = buildEligibleEnvelope(label.reachableSet, null);
		if (eligibleEnvelope != null && eligibleEnvelope.dominates(label.frontier)) {
			label.isDominated = true;
			return true;
		}

		DominanceNode node = findOrCreateNode(label.reachableSet);
		node.addLabel(label);
		propagateAfterInsert(node);
		return false;
	}

	private PiecewiseLinearFunction buildEligibleEnvelope(PackedBitSet reachableSet, DominanceNode excluded) {
		PiecewiseLinearFunction envelope = null;
		for (DominanceNode node : nodes) {
			if (node == excluded || node.labelEnvelope == null || node.labelEnvelope.head == null) {
				continue;
			}
			// reachableSet 越大，未来扩展能力越强；超集 node 才有资格支配当前状态。
			if (!node.reachableKey.isSupersetOf(reachableSet)) {
				continue;
			}
			if (envelope == null || envelope.head == null) {
				envelope = node.labelEnvelope.copy();
			} else {
				envelope.mergeMinimum(node.labelEnvelope, Direction.FORWARD);
			}
		}
		return envelope;
	}

	private DominanceNode findOrCreateNode(PackedBitSet reachableSet) {
		for (DominanceNode node : nodes) {
			if (node.reachableKey.equals(reachableSet)) {
				return node;
			}
		}
		DominanceNode node = new DominanceNode(reachableSet);
		nodes.add(node);
		return node;
	}

	private void propagateAfterInsert(DominanceNode insertedNode) {
		boolean changed;
		do {
			changed = false;
			Iterator<DominanceNode> iterator = nodes.iterator();
			while (iterator.hasNext()) {
				DominanceNode node = iterator.next();
				if (node == insertedNode || node.isEmpty()) {
					continue;
				}
				PiecewiseLinearFunction eligibleEnvelope = buildEligibleEnvelope(node.reachableKey, node);
				if (eligibleEnvelope == null || eligibleEnvelope.head == null) {
					continue;
				}
				if (eligibleEnvelope.dominates(node.labelEnvelope)) {
					for (Label label : node.getLabels()) {
						label.isDominated = true;
					}
					iterator.remove();
					changed = true;
					continue;
				}
				if (node.removeDominatedBy(eligibleEnvelope)) {
					changed = true;
					if (node.isEmpty()) {
						iterator.remove();
					}
				}
			}
		} while (changed);
	}

}
