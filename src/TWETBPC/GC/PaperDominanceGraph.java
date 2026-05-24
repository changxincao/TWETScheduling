package TWETBPC.GC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

	private static long labelsInserted;
	private static long labelsRejected;
	private static long nodesCreated;
	private static long nodesDeleted;
	private static long labelsDeletedByPropagation;
	private static long supersetSearchCalls;
	private static long supersetNodesVisited;
	private static long subsetSearchCalls;
	private static long subsetNodesVisited;
	private static long propagationCalls;
	private static long propagationNodesVisited;
	private static long envelopeMergeCalls;
	private static long dominanceChecks;

	private final ArrayList<PaperDominanceNode> nodes = new ArrayList<PaperDominanceNode>();
	private final LinkedHashSet<PaperDominanceNode> roots = new LinkedHashSet<PaperDominanceNode>();
	private final Map<PackedBitSet, PaperDominanceNode> nodeByReachableSet = new HashMap<PackedBitSet, PaperDominanceNode>();
	private final Direction direction;

	PaperDominanceGraph() {
		this(Direction.FORWARD);
	}

	PaperDominanceGraph(Direction direction) {
		this.direction = direction;
	}

	static void resetStatistics() {
		labelsInserted = 0;
		labelsRejected = 0;
		nodesCreated = 0;
		nodesDeleted = 0;
		labelsDeletedByPropagation = 0;
		supersetSearchCalls = 0;
		supersetNodesVisited = 0;
		subsetSearchCalls = 0;
		subsetNodesVisited = 0;
		propagationCalls = 0;
		propagationNodesVisited = 0;
		envelopeMergeCalls = 0;
		dominanceChecks = 0;
	}

	static String statisticsSummary() {
		return "paperGraph labels kept/rejected=" + labelsInserted + "/" + labelsRejected
				+ ", nodes created/deleted=" + nodesCreated + "/" + nodesDeleted
				+ ", labelsDeleted=" + labelsDeletedByPropagation
				+ ", superset calls/visited=" + supersetSearchCalls + "/" + supersetNodesVisited
				+ ", subset calls/visited=" + subsetSearchCalls + "/" + subsetNodesVisited
				+ ", propagate calls/visited=" + propagationCalls + "/" + propagationNodesVisited
				+ ", envelopeMerges=" + envelopeMergeCalls
				+ ", dominanceChecks=" + dominanceChecks;
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
		dominanceChecks++;
		if (dominanceEnvelope != null && dominanceEnvelope.dominates(label.frontier)) {
			label.isDominated = true;
			labelsRejected++;
			return true;
		}

		PaperDominanceNode inserted;
		if (sameNode != null && sameNode.active) {
			sameNode.addLabel(label);
			inserted = sameNode;
		} else {
			inserted = insertNewNode(label, candidates);
		}
		labelsInserted++;
		propagateAndTrim(inserted);
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
	}

	private ArrayList<PaperDominanceNode> findTerminalSupersetNodes(PackedBitSet target) {
		supersetSearchCalls++;
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
			supersetNodesVisited++;
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
		nodesCreated++;
		nodeByReachableSet.put(node.reachableKey, node);

		ArrayList<PaperDominanceNode> successors = findImmediateSubsetNodes(node.reachableKey, predecessors);
		for (PaperDominanceNode predecessor : predecessors) {
			connect(predecessor, node);
			for (PaperDominanceNode successor : successors) {
				disconnect(predecessor, successor);
			}
		}
		if (predecessors.isEmpty()) {
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
		subsetSearchCalls++;
		ArrayList<PaperDominanceNode> starts = new ArrayList<PaperDominanceNode>();
		if (predecessors.isEmpty()) {
			starts.addAll(roots);
		} else {
			HashSet<PaperDominanceNode> startSet = new HashSet<PaperDominanceNode>();
			for (PaperDominanceNode predecessor : predecessors) {
				for (PaperDominanceNode successor : predecessor.successors) {
					if (startSet.add(successor)) {
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
			subsetNodesVisited++;
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
		Collections.sort(candidates, new Comparator<PaperDominanceNode>() {
			@Override
			public int compare(PaperDominanceNode a, PaperDominanceNode b) {
				return Integer.compare(b.reachableCardinality, a.reachableCardinality);
			}
		});
		ArrayList<PaperDominanceNode> result = new ArrayList<PaperDominanceNode>();
		for (PaperDominanceNode candidate : candidates) {
			boolean coveredByFrontier = false;
			for (PaperDominanceNode kept : result) {
				if (kept.reachableKey.isSupersetOf(candidate.reachableKey)) {
					coveredByFrontier = true;
					break;
				}
			}
			if (!coveredByFrontier) {
				result.add(candidate);
			}
		}
		return result;
	}

	private void propagateAndTrim(PaperDominanceNode changedNode) {
		propagationCalls++;
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
			propagationNodesVisited++;
			node.recomputePredecessorEnvelope();
			dominanceChecks++;
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
		nodesDeleted++;
		labelsDeletedByPropagation += node.labels.size();
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
			if (!predecessor.active) {
				continue;
			}
			ArrayList<PaperDominanceNode> compatibleSuccessors = new ArrayList<PaperDominanceNode>();
			for (PaperDominanceNode successor : successors) {
				if (successor.active && predecessor.reachableKey.isSupersetOf(successor.reachableKey)) {
					compatibleSuccessors.add(successor);
				}
			}
			for (PaperDominanceNode successor : removeRedundantSubsetCandidates(compatibleSuccessors)) {
				connect(predecessor, successor);
			}
		}
		for (PaperDominanceNode successor : successors) {
			if (successor.active && successor.predecessors.isEmpty()) {
				roots.add(successor);
			}
		}
		return successors;
	}

	private PiecewiseLinearFunction mergeGEnvelopes(ArrayList<PaperDominanceNode> candidates) {
		envelopeMergeCalls++;
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
		from.successors.add(to);
		to.predecessors.add(from);
	}

	private void disconnect(PaperDominanceNode from, PaperDominanceNode to) {
		from.successors.remove(to);
		to.predecessors.remove(from);
	}

	private static final class PaperDominanceNode {
		final PackedBitSet reachableKey;
		final int reachableCardinality;
		final ArrayList<Label> labels = new ArrayList<Label>();
		final LinkedHashSet<PaperDominanceNode> predecessors = new LinkedHashSet<PaperDominanceNode>();
		final LinkedHashSet<PaperDominanceNode> successors = new LinkedHashSet<PaperDominanceNode>();
		final Direction direction;
		PiecewiseLinearFunction labelEnvelope;
		PiecewiseLinearFunction predecessorEnvelope;
		PiecewiseLinearFunction dominanceEnvelope;
		boolean active = true;

		PaperDominanceNode(PackedBitSet reachableKey, Direction direction) {
			this.reachableKey = reachableKey.copy();
			this.reachableCardinality = this.reachableKey.cardinality();
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
				dominanceChecks++;
				if (predecessorEnvelope.dominates(label.frontier)) {
					label.isDominated = true;
					labels.remove(i);
					labelsDeletedByPropagation++;
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
