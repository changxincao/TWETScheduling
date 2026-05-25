package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import TWETBPC.Util.PackedBitSet;

/**
 * dominance graph 中的一个节点。
 * <p>
 * 2026-05-17: 这里采用“真实 label 集合 + 聚合 envelope”的折中结构。
 * 论文中的 node 只保留一个 aggregate label；当前实现额外保存真实 labels，
 * 这样既能用 aggregate envelope 做 set dominance，也能在生成列时恢复真实路径。
 */
final class DominanceNode {

	final PackedBitSet reachableKey;
	final ArrayList<Label> labels;
	private final Direction direction;
	PiecewiseLinearFunction labelEnvelope;

	DominanceNode(PackedBitSet reachableKey, Direction direction) {
		this.reachableKey = reachableKey.copy();
		this.labels = new ArrayList<Label>();
		this.direction = direction;
	}

	void addLabel(Label label) {
		labels.add(label);
		if (labelEnvelope == null || labelEnvelope.head == null) {
			labelEnvelope = label.frontier.copy();
		} else {
			labelEnvelope.mergeMinimum(label.frontier, direction);
		}
	}

	boolean removeDominatedBy(PiecewiseLinearFunction envelope, DominanceGraph graph) {
		boolean changed = false;
		Iterator<Label> iterator = labels.iterator();
		while (iterator.hasNext()) {
			Label label = iterator.next();
			if (graph.canCoverDomain(envelope, label.frontier) && envelope.dominates(label.frontier)) {
				label.isDominated = true;
				iterator.remove();
				changed = true;
			}
		}
		if (changed) {
			rebuildEnvelope();
		}
		return changed;
	}

	boolean isEmpty() {
		return labels.isEmpty();
	}

	List<Label> getLabels() {
		return labels;
	}

	private void rebuildEnvelope() {
		labelEnvelope = null;
		for (Label label : labels) {
			if (labelEnvelope == null || labelEnvelope.head == null) {
				labelEnvelope = label.frontier.copy();
			} else {
				labelEnvelope.mergeMinimum(label.frontier, direction);
			}
		}
	}

}
