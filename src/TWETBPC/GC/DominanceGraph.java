package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Iterator;

import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.Utility;
import TWETBPC.Util.PackedBitSet;

/**
 * 单个 terminal job 上的 dominance graph。
 * <p>
 * 第一版只实现完整 set dominance，不做 partial dominance。也就是说，
 * graph 中 eligible nodes 的下包络若能在整个定义域上压住新 label，
 * 则删除新 label；否则保留整条 label，不切函数片段。
 */
final class DominanceGraph implements DominanceStore {

	private final ArrayList<DominanceNode> nodes = new ArrayList<DominanceNode>();
	private final Direction direction;

	DominanceGraph() {
		this(Direction.FORWARD);
	}

	DominanceGraph(Direction direction) {
		this.direction = direction;
	}

	/**
	 * 尝试插入一个真实 label。
	 *
	 * @return true 表示该 label 被已有 graph 完整占优并丢弃；false 表示已插入 graph
	 */
	public boolean insertOrDominate(Label label) {
		PiecewiseLinearFunction eligibleEnvelope = buildEligibleEnvelope(label.reachableSet, null);
		if (canCoverDomain(eligibleEnvelope, label.frontier) && eligibleEnvelope.dominates(label.frontier)) {
			label.isDominated = true;
			return true;
		}

		DominanceNode node = findOrCreateNode(label.reachableSet);
		node.addLabel(label);
		propagateAfterInsert(node);
		return false;
	}

	@Override
	public ArrayList<Label> getActiveLabels() {
		ArrayList<Label> labels = new ArrayList<Label>();
		collectActiveLabels(labels);
		return labels;
	}

	@Override
	public void collectActiveLabels(ArrayList<Label> labels) {
		for (DominanceNode node : nodes) {
			if (node.isEmpty()) {
				continue;
			}
			for (Label label : node.getLabels()) {
				if (!label.isDominated) {
					labels.add(label);
				}
			}
		}
	}

	@Override
	public boolean dominatesSinglePoint(PackedBitSet reachableSet, int reachableCardinality, double pointTime,
			double pointValue) {
		double best = Utility.big_M;
		for (DominanceNode node : nodes) {
			if (node.labelEnvelope == null || node.labelEnvelope.head == null) {
				continue;
			}
			if (node.reachableCardinality < reachableCardinality) {
				continue;
			}
			if (!node.reachableKey.isSupersetOf(reachableSet)) {
				continue;
			}
			double candidateValue = pointDominanceValue(node.labelEnvelope, pointTime);
			if (Utility.compareLt(candidateValue, best)) {
				best = candidateValue;
			}
		}
		return !Utility.compareGt(best, pointValue);
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
				envelope.mergeMinimum(node.labelEnvelope, direction);
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
		DominanceNode node = new DominanceNode(reachableSet, direction);
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
				if (canCoverDomain(eligibleEnvelope, node.labelEnvelope)
						&& eligibleEnvelope.dominates(node.labelEnvelope)) {
					for (Label label : node.getLabels()) {
						label.isDominated = true;
					}
					iterator.remove();
					changed = true;
					continue;
				}
				if (node.removeDominatedBy(eligibleEnvelope, this)) {
					changed = true;
					if (node.isEmpty()) {
						iterator.remove();
					}
				}
			}
		} while (changed);
	}

	boolean canCoverDomain(PiecewiseLinearFunction candidate, PiecewiseLinearFunction target) {
		if (candidate == null || candidate.head == null || target == null || target.head == null) {
			return false;
		}
		if (direction == Direction.FORWARD) {
			return !Utility.compareGt(candidate.head.start, target.head.start);
		}
		return !Utility.compareLt(candidate.tail.end, target.tail.end);
	}

	private double pointDominanceValue(PiecewiseLinearFunction envelope, double pointTime) {
		if (envelope == null || envelope.head == null) {
			return Utility.big_M;
		}
		if (Utility.compareLt(pointTime, envelope.head.start) || Utility.compareGt(pointTime, envelope.tail.end)) {
			return Utility.big_M;
		}
		if (direction == Direction.FORWARD && Utility.compareEq(pointTime, envelope.tail.end)) {
			return envelope.tail.getValue(envelope.tail.end);
		}
		if (direction == Direction.BACKWARD && Utility.compareEq(pointTime, envelope.head.start)) {
			return envelope.head.getValue(envelope.head.start);
		}
		return envelope.evaluate(pointTime);
	}

}
