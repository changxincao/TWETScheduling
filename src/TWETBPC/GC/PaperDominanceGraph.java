package TWETBPC.GC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import TWETBPC.Util.PackedBitSet;

/**
 * 按 `parallel_machine_scheduling_with_due_window.pdf` 的 dominance graph 伪代码实现的占优结构。
 * <p>
 * 2026-05-17: 旧 `DominanceGraph` 每次插入都会全量扫描 eligible node。这个版本显式维护
 * reachable set 的包含关系图，每个 node 保存：
 * `f_u` = 本 node 真实 label 集合的下包络；
 * `h_u` = 直接前驱传下来的支配包络；
 * `g_u = min(f_u, h_u)` = 继续向后继传播的综合包络。
 * 新 label 先从 roots 沿 successors 找 terminal candidate nodes，再只沿受影响的 successors 传播。
 * <p>
 * 当前仍只做完整占优，不做 partial dominance；这样不会因为函数切段实现不完整而误删最优列。
 */
final class PaperDominanceGraph implements DominanceStore {

	private final ArrayList<PaperDominanceNode> nodes = new ArrayList<PaperDominanceNode>();
	private final ArrayList<PaperDominanceNode> roots = new ArrayList<PaperDominanceNode>();
	private final Map<PackedBitSet, PaperDominanceNode> nodeByReachableSet = new HashMap<PackedBitSet, PaperDominanceNode>();
	private final Direction direction;

	PaperDominanceGraph() {
		this(Direction.FORWARD);
	}

	PaperDominanceGraph(Direction direction) {
		this.direction = direction;
	}

	@Override
	public boolean insertOrDominate(Label label) {
		PaperDominanceNode sameNode = nodeByReachableSet.get(label.reachableSet);
		ArrayList<PaperDominanceNode> candidates = new ArrayList<PaperDominanceNode>();
		if (sameNode != null && sameNode.active) {
			candidates.add(sameNode);
		} else {
			candidates.addAll(findTerminalSupersetNodes(label.reachableSet));
		}

		PiecewiseLinearFunction dominanceEnvelope = mergeGEnvelopes(candidates);
		if (dominanceEnvelope != null && dominanceEnvelope.dominates(label.frontier)) {
			label.isDominated = true;
			return true;
		}

		PaperDominanceNode inserted;
		if (sameNode != null && sameNode.active) {
			sameNode.addLabel(label);
			inserted = sameNode;
		} else {
			inserted = insertNewNode(label, candidates);
		}
		propagateAndTrim(inserted);
		return false;
	}

	@Override
	public ArrayList<Label> getActiveLabels() {
		ArrayList<Label> labels = new ArrayList<Label>();
		for (PaperDominanceNode node : nodes) {
			if (!node.active) {
				continue;
			}
			for (Label label : node.labels) {
				if (!label.isDominated) {
					labels.add(label);
				}
			}
		}
		return labels;
	}

	private ArrayList<PaperDominanceNode> findTerminalSupersetNodes(PackedBitSet target) {
		ArrayList<PaperDominanceNode> result = new ArrayList<PaperDominanceNode>();
		HashSet<PaperDominanceNode> visited = new HashSet<PaperDominanceNode>();
		ArrayDeque<PaperDominanceNode> stack = new ArrayDeque<PaperDominanceNode>();
		for (PaperDominanceNode root : roots) {
			if (root.active && root.reachableKey.isSupersetOf(target)) {
				stack.push(root);
			}
		}
		while (!stack.isEmpty()) {
			PaperDominanceNode node = stack.pop();
			if (!visited.add(node)) {
				continue;
			}
			boolean hasDeeperSuperset = false;
			for (PaperDominanceNode successor : node.successors) {
				if (successor.active && successor.reachableKey.isSupersetOf(target)) {
					stack.push(successor);
					hasDeeperSuperset = true;
				}
			}
			if (!hasDeeperSuperset) {
				result.add(node);
			}
		}
		return result;
	}

	private PaperDominanceNode insertNewNode(Label label, ArrayList<PaperDominanceNode> predecessors) {
		PaperDominanceNode node = new PaperDominanceNode(label.reachableSet, direction);
		node.addLabel(label);
		nodes.add(node);
		nodeByReachableSet.put(node.reachableKey, node);

		ArrayList<PaperDominanceNode> successors = findImmediateSubsetNodes(node.reachableKey, predecessors);
		for (PaperDominanceNode predecessor : predecessors) {
			connect(predecessor, node);
			for (PaperDominanceNode successor : successors) {
				disconnect(predecessor, successor);
			}
		}
		if (predecessors.isEmpty() && !roots.contains(node)) {
			roots.add(node);
		}
		for (PaperDominanceNode successor : successors) {
			roots.remove(successor);
			connect(node, successor);
		}
		node.recomputePredecessorEnvelope();
		node.recomputeDominanceEnvelope();
		return node;
	}

	private ArrayList<PaperDominanceNode> findImmediateSubsetNodes(PackedBitSet newKey,
			ArrayList<PaperDominanceNode> predecessors) {
		ArrayList<PaperDominanceNode> starts = new ArrayList<PaperDominanceNode>();
		if (predecessors.isEmpty()) {
			starts.addAll(roots);
		} else {
			for (PaperDominanceNode predecessor : predecessors) {
				for (PaperDominanceNode successor : predecessor.successors) {
					if (!starts.contains(successor)) {
						starts.add(successor);
					}
				}
			}
		}

		ArrayList<PaperDominanceNode> candidates = new ArrayList<PaperDominanceNode>();
		HashSet<PaperDominanceNode> visited = new HashSet<PaperDominanceNode>();
		ArrayDeque<PaperDominanceNode> stack = new ArrayDeque<PaperDominanceNode>();
		for (PaperDominanceNode start : starts) {
			if (start.active) {
				stack.push(start);
			}
		}
		while (!stack.isEmpty()) {
			PaperDominanceNode node = stack.pop();
			if (!visited.add(node)) {
				continue;
			}
			if (node.reachableKey.isSubsetOf(newKey)) {
				candidates.add(node);
				continue;
			}
			for (PaperDominanceNode successor : node.successors) {
				if (successor.active) {
					stack.push(successor);
				}
			}
		}
		return removeRedundantSubsetCandidates(candidates);
	}

	private ArrayList<PaperDominanceNode> removeRedundantSubsetCandidates(ArrayList<PaperDominanceNode> candidates) {
		ArrayList<PaperDominanceNode> result = new ArrayList<PaperDominanceNode>();
		for (PaperDominanceNode candidate : candidates) {
			boolean dominatedByAnotherCandidate = false;
			for (PaperDominanceNode other : candidates) {
				if (candidate != other && other.reachableKey.isSupersetOf(candidate.reachableKey)) {
					dominatedByAnotherCandidate = true;
					break;
				}
			}
			if (!dominatedByAnotherCandidate && !result.contains(candidate)) {
				result.add(candidate);
			}
		}
		return result;
	}

	private void propagateAndTrim(PaperDominanceNode changedNode) {
		ArrayDeque<PaperDominanceNode> queue = new ArrayDeque<PaperDominanceNode>();
		HashSet<PaperDominanceNode> queued = new HashSet<PaperDominanceNode>();
		for (PaperDominanceNode successor : changedNode.successors) {
			if (successor.active && queued.add(successor)) {
				queue.add(successor);
			}
		}
		while (!queue.isEmpty()) {
			PaperDominanceNode node = queue.poll();
			if (!node.active) {
				continue;
			}
			node.recomputePredecessorEnvelope();
			if (node.predecessorEnvelope != null && node.predecessorEnvelope.dominates(node.labelEnvelope)) {
				ArrayList<PaperDominanceNode> affected = deleteNode(node);
				for (PaperDominanceNode successor : affected) {
					if (successor.active && queued.add(successor)) {
						queue.add(successor);
					}
				}
				continue;
			}
			if (node.removeLabelsDominatedByPredecessors()) {
				if (node.labels.isEmpty()) {
					ArrayList<PaperDominanceNode> affected = deleteNode(node);
					for (PaperDominanceNode successor : affected) {
						if (successor.active && queued.add(successor)) {
							queue.add(successor);
						}
					}
					continue;
				}
			}
			node.recomputeDominanceEnvelope();
			for (PaperDominanceNode successor : node.successors) {
				if (successor.active && queued.add(successor)) {
					queue.add(successor);
				}
			}
		}
	}

	private ArrayList<PaperDominanceNode> deleteNode(PaperDominanceNode node) {
		node.active = false;
		for (Label label : node.labels) {
			label.isDominated = true;
		}
		nodeByReachableSet.remove(node.reachableKey);
		roots.remove(node);

		ArrayList<PaperDominanceNode> predecessors = new ArrayList<PaperDominanceNode>(node.predecessors);
		ArrayList<PaperDominanceNode> successors = new ArrayList<PaperDominanceNode>(node.successors);
		for (PaperDominanceNode predecessor : predecessors) {
			disconnect(predecessor, node);
		}
		for (PaperDominanceNode successor : successors) {
			disconnect(node, successor);
		}
		for (PaperDominanceNode predecessor : predecessors) {
			for (PaperDominanceNode successor : successors) {
				if (predecessor.active && successor.active && predecessor.reachableKey.isSupersetOf(successor.reachableKey)) {
					connect(predecessor, successor);
				}
			}
		}
		for (PaperDominanceNode successor : successors) {
			if (successor.active && successor.predecessors.isEmpty() && !roots.contains(successor)) {
				roots.add(successor);
			}
		}
		return successors;
	}

	private PiecewiseLinearFunction mergeGEnvelopes(ArrayList<PaperDominanceNode> candidates) {
		PiecewiseLinearFunction envelope = null;
		for (PaperDominanceNode node : candidates) {
			if (!node.active || node.dominanceEnvelope == null || node.dominanceEnvelope.head == null) {
				continue;
			}
			if (envelope == null || envelope.head == null) {
				envelope = node.dominanceEnvelope.copy();
			} else {
				envelope.mergeMinimum(node.dominanceEnvelope, direction);
			}
		}
		return envelope;
	}

	private void connect(PaperDominanceNode from, PaperDominanceNode to) {
		if (from == to) {
			return;
		}
		if (!from.successors.contains(to)) {
			from.successors.add(to);
		}
		if (!to.predecessors.contains(from)) {
			to.predecessors.add(from);
		}
	}

	private void disconnect(PaperDominanceNode from, PaperDominanceNode to) {
		from.successors.remove(to);
		to.predecessors.remove(from);
	}

	private static final class PaperDominanceNode {
		final PackedBitSet reachableKey;
		final ArrayList<Label> labels = new ArrayList<Label>();
		final ArrayList<PaperDominanceNode> predecessors = new ArrayList<PaperDominanceNode>();
		final ArrayList<PaperDominanceNode> successors = new ArrayList<PaperDominanceNode>();
		final Direction direction;
		PiecewiseLinearFunction labelEnvelope;
		PiecewiseLinearFunction predecessorEnvelope;
		PiecewiseLinearFunction dominanceEnvelope;
		boolean active = true;

		PaperDominanceNode(PackedBitSet reachableKey, Direction direction) {
			this.reachableKey = reachableKey.copy();
			this.direction = direction;
		}

		void addLabel(Label label) {
			labels.add(label);
			if (labelEnvelope == null || labelEnvelope.head == null) {
				labelEnvelope = label.frontier.copy();
			} else {
				labelEnvelope.mergeMinimum(label.frontier, direction);
			}
			recomputeDominanceEnvelope();
		}

		void recomputePredecessorEnvelope() {
			predecessorEnvelope = null;
			for (PaperDominanceNode predecessor : predecessors) {
				if (!predecessor.active || predecessor.dominanceEnvelope == null
						|| predecessor.dominanceEnvelope.head == null) {
					continue;
				}
				if (predecessorEnvelope == null || predecessorEnvelope.head == null) {
					predecessorEnvelope = predecessor.dominanceEnvelope.copy();
				} else {
					predecessorEnvelope.mergeMinimum(predecessor.dominanceEnvelope, direction);
				}
			}
		}

		void recomputeDominanceEnvelope() {
			if (labelEnvelope == null || labelEnvelope.head == null) {
				dominanceEnvelope = predecessorEnvelope == null ? null : predecessorEnvelope.copy();
				return;
			}
			dominanceEnvelope = labelEnvelope.copy();
			if (predecessorEnvelope != null && predecessorEnvelope.head != null) {
				dominanceEnvelope.mergeMinimum(predecessorEnvelope, direction);
			}
		}

		boolean removeLabelsDominatedByPredecessors() {
			if (predecessorEnvelope == null || predecessorEnvelope.head == null) {
				return false;
			}
			boolean changed = false;
			for (int i = labels.size() - 1; i >= 0; i--) {
				Label label = labels.get(i);
				if (predecessorEnvelope.dominates(label.frontier)) {
					label.isDominated = true;
					labels.remove(i);
					changed = true;
				}
			}
			if (labels.isEmpty()) {
				labelEnvelope = null;
				dominanceEnvelope = predecessorEnvelope.copy();
				return true;
			}
			if (changed) {
				rebuildLabelEnvelope();
				recomputeDominanceEnvelope();
			}
			return changed;
		}

		private void rebuildLabelEnvelope() {
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
}
