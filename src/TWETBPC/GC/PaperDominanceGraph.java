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
import Common.PiecewiseLinearFunction.TrimResult;
import Common.Utility;
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
class PaperDominanceGraph implements DominanceStore {

	private static final boolean TIMING_DIAGNOSTIC = Boolean.getBoolean("twet.bpc.paperGraphTiming");
	private static final boolean TIMING_HEARTBEAT = TIMING_DIAGNOSTIC
			&& Boolean.getBoolean("twet.bpc.paperGraphTimingHeartbeat");
	private static final long TIMING_HEARTBEAT_INTERVAL_NANOS = Long.getLong(
			"twet.bpc.paperGraphTimingHeartbeatIntervalMillis", 10000L) * 1000000L;
	private static final long TIMING_HEARTBEAT_VISIT_MASK = 4095L;

	private static long labelsInserted;
	private static long labelsRejected;
	private static long nodesCreated;
	private static long nodesDeleted;
	private static long labelsDeletedByPropagation;
	private static long supersetSearchCalls;
	private static long supersetNodesVisited;
	private static long supersetSearchNanos;
	private static long maxSupersetSearchNanos;
	private static long subsetSearchCalls;
	private static long subsetNodesVisited;
	private static long subsetSearchNanos;
	private static long maxSubsetSearchNanos;
	private static long propagationCalls;
	private static long propagationNodesVisited;
	private static long envelopeMergeCalls;
	private static long dominanceChecks;
	private static long partialTrimChecks;
	private static long partialTrims;
	private static long partialFullTrims;
	private static long markSeed;
	private static long timingStartNanos;
	private static long nextTimingHeartbeatNanos;
	private static String diagnosticContext = "";

	private final ArrayList<PaperDominanceNode> nodes = new ArrayList<PaperDominanceNode>();
	private final LinkedHashSet<PaperDominanceNode> roots = new LinkedHashSet<PaperDominanceNode>();
	private final Map<PackedBitSet, PaperDominanceNode> nodeByReachableSet = new HashMap<PackedBitSet, PaperDominanceNode>();
	private final Direction direction;
	private final boolean partialDominance;

	PaperDominanceGraph() {
		this(Direction.FORWARD);
	}

	PaperDominanceGraph(Direction direction) {
		this(direction, false);
	}

	PaperDominanceGraph(Direction direction, boolean partialDominance) {
		this.direction = direction;
		this.partialDominance = partialDominance;
	}

	static void resetStatistics() {
		labelsInserted = 0;
		labelsRejected = 0;
		nodesCreated = 0;
		nodesDeleted = 0;
		labelsDeletedByPropagation = 0;
		supersetSearchCalls = 0;
		supersetNodesVisited = 0;
		supersetSearchNanos = 0;
		maxSupersetSearchNanos = 0;
		subsetSearchCalls = 0;
		subsetNodesVisited = 0;
		subsetSearchNanos = 0;
		maxSubsetSearchNanos = 0;
		timingStartNanos = TIMING_HEARTBEAT ? System.nanoTime() : 0L;
		nextTimingHeartbeatNanos = TIMING_HEARTBEAT
				? timingStartNanos + TIMING_HEARTBEAT_INTERVAL_NANOS : 0L;
		propagationCalls = 0;
		propagationNodesVisited = 0;
		envelopeMergeCalls = 0;
		dominanceChecks = 0;
		partialTrimChecks = 0;
		partialTrims = 0;
		partialFullTrims = 0;
	}

	static void setDiagnosticContext(String context) {
		diagnosticContext = context == null ? "" : context;
	}

	static String statisticsSummary() {
		return "paperGraph labels kept/rejected=" + labelsInserted + "/" + labelsRejected
				+ ", nodes created/deleted=" + nodesCreated + "/" + nodesDeleted
				+ ", labelsDeleted=" + labelsDeletedByPropagation
				+ ", superset calls/visited=" + supersetSearchCalls + "/" + supersetNodesVisited
				+ ", subset calls/visited=" + subsetSearchCalls + "/" + subsetNodesVisited
				+ timingSummary()
				+ ", propagate calls/visited=" + propagationCalls + "/" + propagationNodesVisited
				+ ", envelopeMerges=" + envelopeMergeCalls
				+ ", dominanceChecks=" + dominanceChecks
				+ ", partialTrim checks/partial/full=" + partialTrimChecks + "/" + partialTrims + "/"
				+ partialFullTrims;
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
		if (dominatesOrTrimsToEmpty(dominanceEnvelope, label)) {
			label.isDominated = true;
			labelsRejected++;
			return true;
		}

		PaperDominanceNode inserted;
		if (sameNode != null && sameNode.active) {
			if (partialDominance) {
				sameNode.trimLabelsBy(label.frontier);
			}
			sameNode.addLabel(label);
			inserted = sameNode;
		} else {
			inserted = insertNewNode(label, candidates, dominanceEnvelope);
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

	@Override
	public boolean dominatesSinglePoint(PackedBitSet reachableSet, int reachableCardinality, double pointTime,
			double pointValue) {
		PaperDominanceNode sameNode = nodeByReachableSet.get(reachableSet);
		double best = sameNode != null && sameNode.active ? pointDominanceValue(sameNode.dominanceEnvelope, pointTime)
				: bestTerminalSupersetPointDominanceValue(reachableSet, reachableCardinality, pointTime);
		return !Utility.compareGt(best, pointValue);
	}

	private ArrayList<PaperDominanceNode> findTerminalSupersetNodes(PackedBitSet target) {
		long begin = TIMING_DIAGNOSTIC ? System.nanoTime() : 0L;
		supersetSearchCalls++;
		ArrayList<PaperDominanceNode> result = new ArrayList<PaperDominanceNode>();
		ArrayDeque<PaperDominanceNode> stack = new ArrayDeque<PaperDominanceNode>();
		long visitMark = nextMark();
		int targetCardinality = target.cardinality();
		try {
			for (PaperDominanceNode root : roots) {
				if (root.active && root.reachableCardinality >= targetCardinality
						&& root.reachableKey.isSupersetOf(target)) {
					stack.push(root);
				}
			}
			while (!stack.isEmpty()) {
				PaperDominanceNode node = stack.pop();
				if (node.visitMark == visitMark) {
					continue;
				}
				node.visitMark = visitMark;
				supersetNodesVisited++;
				if (TIMING_HEARTBEAT && (supersetNodesVisited & TIMING_HEARTBEAT_VISIT_MASK) == 0L) {
					maybePrintTimingHeartbeat("superset-loop");
				}
				boolean hasDeeperSuperset = false;
				for (PaperDominanceNode successor : node.successors) {
					if (successor.active && successor.reachableCardinality >= targetCardinality
							&& successor.reachableKey.isSupersetOf(target)) {
						stack.push(successor);
						hasDeeperSuperset = true;
					}
				}
				if (!hasDeeperSuperset) {
					result.add(node);
				}
			}
		} finally {
			if (TIMING_DIAGNOSTIC) {
				recordSupersetSearchNanos(System.nanoTime() - begin);
			}
		}
		return result;
	}

	private PaperDominanceNode insertNewNode(Label label, ArrayList<PaperDominanceNode> predecessors,
			PiecewiseLinearFunction precomputedPredecessorEnvelope) {
		PaperDominanceNode node = new PaperDominanceNode(label.reachableSet, direction);
		node.labels.add(label);
		node.labelEnvelope = label.frontier.copy();
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
		// 2026-05-25: 当前新点的 predecessor 集合和上面支配检查时 merge 过的 candidates 相同，
		// 这里直接复用那次合并后的 h，避免对同一批 predecessor 的 g_u 再做一遍 merge。
		node.predecessorEnvelope = precomputedPredecessorEnvelope;
		node.recomputeDominanceEnvelope();
		return node;
	}

	private ArrayList<PaperDominanceNode> findImmediateSubsetNodes(PackedBitSet newKey,
			ArrayList<PaperDominanceNode> predecessors) {
		long begin = TIMING_DIAGNOSTIC ? System.nanoTime() : 0L;
		subsetSearchCalls++;
		ArrayList<PaperDominanceNode> starts = new ArrayList<PaperDominanceNode>();
		long startMark = nextMark();
		long visitMark = nextMark();
		int newCardinality = newKey.cardinality();
		try {
			if (predecessors.isEmpty()) {
				starts.addAll(roots);
			} else {
				for (PaperDominanceNode predecessor : predecessors) {
					for (PaperDominanceNode successor : predecessor.successors) {
						if (successor.startMark != startMark) {
							successor.startMark = startMark;
							starts.add(successor);
						}
					}
				}
			}

			ArrayList<PaperDominanceNode> candidates = new ArrayList<PaperDominanceNode>();
			ArrayDeque<PaperDominanceNode> stack = new ArrayDeque<PaperDominanceNode>();
			for (PaperDominanceNode start : starts) {
				if (start.active) {
					stack.push(start);
				}
			}
			while (!stack.isEmpty()) {
				PaperDominanceNode node = stack.pop();
				if (node.visitMark == visitMark) {
					continue;
				}
				node.visitMark = visitMark;
				subsetNodesVisited++;
				if (TIMING_HEARTBEAT && (subsetNodesVisited & TIMING_HEARTBEAT_VISIT_MASK) == 0L) {
					maybePrintTimingHeartbeat("subset-loop");
				}
				if (node.reachableCardinality <= newCardinality && node.reachableKey.isSubsetOf(newKey)) {
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
		} finally {
			if (TIMING_DIAGNOSTIC) {
				recordSubsetSearchNanos(System.nanoTime() - begin);
			}
		}
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
			if (canCoverDomain(node.predecessorEnvelope, node.labelEnvelope, direction)
					&& node.predecessorEnvelope.dominates(node.labelEnvelope)) {
				ArrayList<PaperDominanceNode> affected = deleteNode(node);
				for (PaperDominanceNode successor : affected) {
					if (successor.active && queued.add(successor)) {
						queue.add(successor);
					}
				}
				continue;
			}
			if (node.removeLabelsDominatedByPredecessors(partialDominance)) {
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

	private boolean dominatesOrTrimsToEmpty(PiecewiseLinearFunction envelope, Label label) {
		if (!canCoverDomain(envelope, label.frontier, direction)) {
			return false;
		}
		if (!partialDominance) {
			return envelope.dominates(label.frontier);
		}
		return trimLabelByEnvelope(label, envelope, direction);
	}

	private static boolean trimLabelByEnvelope(Label label, PiecewiseLinearFunction envelope, Direction direction) {
		partialTrimChecks++;
		TrimResult result = label.frontier.updateDominatedIntervalsDetailed(envelope, direction);
		if (result == TrimResult.NO_CHANGE) {
			return false;
		}
		label.refreshMinReducedCost();
		if (result == TrimResult.EMPTY || label.frontier == null || label.frontier.head == null
				|| Utility.isBigMValue(label.minReducedCost)) {
			label.isDominated = true;
			partialFullTrims++;
			return true;
		}
		partialTrims++;
		return false;
	}

	/**
	 * 2026-05-25: single-point 查询只需要 terminal-superset 叶子上的最优 Tmid 点值；
	 * 这里直接在 DFS 里累计最优值，避免先构造候选列表、再做第二遍扫描。
	 */
	private double bestTerminalSupersetPointDominanceValue(PackedBitSet target, int targetCardinality,
			double pointTime) {
		long begin = TIMING_DIAGNOSTIC ? System.nanoTime() : 0L;
		supersetSearchCalls++;
		double best = Utility.big_M;
		ArrayDeque<PaperDominanceNode> stack = new ArrayDeque<PaperDominanceNode>();
		long visitMark = nextMark();
		try {
			for (PaperDominanceNode root : roots) {
				if (root.active && root.reachableCardinality >= targetCardinality
						&& root.reachableKey.isSupersetOf(target)) {
					stack.push(root);
				}
			}
			while (!stack.isEmpty()) {
				PaperDominanceNode node = stack.pop();
				if (node.visitMark == visitMark) {
					continue;
				}
				node.visitMark = visitMark;
				supersetNodesVisited++;
				if (TIMING_HEARTBEAT && (supersetNodesVisited & TIMING_HEARTBEAT_VISIT_MASK) == 0L) {
					maybePrintTimingHeartbeat("point-superset-loop");
				}
				boolean hasDeeperSuperset = false;
				for (PaperDominanceNode successor : node.successors) {
					if (successor.active && successor.reachableCardinality >= targetCardinality
							&& successor.reachableKey.isSupersetOf(target)) {
						stack.push(successor);
						hasDeeperSuperset = true;
					}
				}
				if (!hasDeeperSuperset) {
					double candidateValue = pointDominanceValue(node.dominanceEnvelope, pointTime);
					if (Utility.compareLt(candidateValue, best)) {
						best = candidateValue;
					}
				}
			}
		} finally {
			if (TIMING_DIAGNOSTIC) {
				recordSupersetSearchNanos(System.nanoTime() - begin);
			}
		}
		return best;
	}

	private static boolean canCoverDomain(PiecewiseLinearFunction candidate, PiecewiseLinearFunction target,
			Direction direction) {
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

	private static long nextMark() {
		markSeed++;
		if (markSeed == Long.MAX_VALUE) {
			markSeed = 1;
		}
		return markSeed;
	}

	private static void recordSupersetSearchNanos(long nanos) {
		supersetSearchNanos += nanos;
		if (nanos > maxSupersetSearchNanos) {
			maxSupersetSearchNanos = nanos;
		}
		if (TIMING_HEARTBEAT) {
			maybePrintTimingHeartbeat("superset-end");
		}
	}

	private static void recordSubsetSearchNanos(long nanos) {
		subsetSearchNanos += nanos;
		if (nanos > maxSubsetSearchNanos) {
			maxSubsetSearchNanos = nanos;
		}
		if (TIMING_HEARTBEAT) {
			maybePrintTimingHeartbeat("subset-end");
		}
	}

	private static String formatMillis(long nanos) {
		return Double.toString(Math.round((nanos / 1000000.0) * 1000.0) / 1000.0);
	}

	private static String timingSummary() {
		if (!TIMING_DIAGNOSTIC) {
			return "";
		}
		return ", superset ms/max=" + formatMillis(supersetSearchNanos) + "/"
				+ formatMillis(maxSupersetSearchNanos)
				+ ", subset ms/max=" + formatMillis(subsetSearchNanos) + "/"
				+ formatMillis(maxSubsetSearchNanos);
	}

	private static void maybePrintTimingHeartbeat(String phase) {
		long now = System.nanoTime();
		if (now < nextTimingHeartbeatNanos) {
			return;
		}
		nextTimingHeartbeatNanos = now + TIMING_HEARTBEAT_INTERVAL_NANOS;
		System.out.println("[paperGraph heartbeat] phase=" + phase
				+ " context=" + diagnosticContext
				+ " elapsedMs=" + formatMillis(now - timingStartNanos)
				+ " labels=" + labelsInserted + "/" + labelsRejected
				+ " nodes=" + nodesCreated + "/" + nodesDeleted
				+ " supersetCallsVisitedMsMax=" + supersetSearchCalls + "/"
				+ supersetNodesVisited + "/" + formatMillis(supersetSearchNanos)
				+ "/" + formatMillis(maxSupersetSearchNanos)
				+ " subsetCallsVisitedMsMax=" + subsetSearchCalls + "/"
				+ subsetNodesVisited + "/" + formatMillis(subsetSearchNanos)
				+ "/" + formatMillis(maxSubsetSearchNanos)
				+ " propagateCallsVisited=" + propagationCalls + "/" + propagationNodesVisited
				+ " envelopeMerges=" + envelopeMergeCalls
				+ " dominanceChecks=" + dominanceChecks);
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
		long startMark;
		long visitMark;

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

		boolean removeLabelsDominatedByPredecessors(boolean partialDominance) {
			if (predecessorEnvelope == null || predecessorEnvelope.head == null) {
				return false;
			}
			boolean changed = false;
			for (int i = labels.size() - 1; i >= 0; i--) {
				Label label = labels.get(i);
				dominanceChecks++;
				if (partialDominance && canCoverDomain(predecessorEnvelope, label.frontier, direction)
						&& trimLabelByEnvelope(label, predecessorEnvelope, direction)) {
					labels.remove(i);
					labelsDeletedByPropagation++;
					changed = true;
				} else if (!partialDominance && canCoverDomain(predecessorEnvelope, label.frontier, direction)
						&& predecessorEnvelope.dominates(label.frontier)) {
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

		void trimLabelsBy(PiecewiseLinearFunction frontier) {
			if (frontier == null || frontier.head == null) {
				return;
			}
			boolean changed = false;
			for (int i = labels.size() - 1; i >= 0; i--) {
				Label label = labels.get(i);
				dominanceChecks++;
				if (canCoverDomain(frontier, label.frontier, direction) && trimLabelByEnvelope(label, frontier, direction)) {
					labels.remove(i);
					labelsDeletedByPropagation++;
					changed = true;
				}
			}
			if (changed && labels.isEmpty()) {
				labelEnvelope = null;
			}
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
