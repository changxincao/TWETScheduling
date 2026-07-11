package TWETBPC.GC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.Segment;
import Common.Utility;
import TWETBPC.Util.PackedBitSet;

/**
 * normal/no-SRI pricing 使用的来源感知增量 dominance graph。
 * <p>
 * 2026-07-10: 旧 {@link PaperDominanceGraph} 在后继节点上会重新扫描全部直接前驱，随后重建
 * label/predecessor/dominance 三层包络。本实现保持同一 reachable-set Hasse 拓扑，增量维护 predecessor
 * 包络 h 和综合包络 g，并把 g 的每段来源区分为本地 label 或外部 predecessor。新 label 只传播真正降低
 * 包络的离散区间；没有数值变化的分支立即停止。
 * <p>
 * 2026-07-10: 同一个 dominance key 下的 label 仍可能有不同真实 ng-memory，final join 兼容性不同。
 * 因此来源只用于一次 merge 内判断新 label 是否贡献，不按“离开综合包络”反向删除同 node 旧 label；
 * 旧 label 仍只在 predecessor h 完整占优它时删除。这一边界与旧 Paper graph 严格一致。partial dominance
 * 会原地裁剪 label frontier，仍使用原 backend。
 */
final class IncrementalSourcedDominanceGraph implements DominanceStore {

	private static final boolean TIMING_DIAGNOSTIC = Boolean.getBoolean(
			"twet.bpc.incrementalSourcedGraphTiming");
	private static final ArrayList<IncrementalSourcedDominanceGraph> DIAGNOSTIC_GRAPHS =
			new ArrayList<IncrementalSourcedDominanceGraph>();

	private static long labelsKept;
	private static long labelsRejected;
	private static long labelsRemoved;
	private static long nodesCreated;
	private static long nodesDeleted;
	private static long sourceAwareMerges;
	private static long propagatedNodes;
	private static long propagationStops;
	private static long deltaInputSegments;
	private static long deltaOutputSegments;
	private static long sourceOnlyChanges;
	private static long insertNanos;
	private static long propagationNanos;
	private static long markSeed;
	private static String diagnosticContext = "";

	private final ArrayList<IncrementalNode> nodes = new ArrayList<IncrementalNode>();
	private final LinkedHashSet<IncrementalNode> roots = new LinkedHashSet<IncrementalNode>();
	private final Map<PackedBitSet, IncrementalNode> nodeByReachableSet =
			new HashMap<PackedBitSet, IncrementalNode>();
	private final Direction direction;

	IncrementalSourcedDominanceGraph(Direction direction) {
		this.direction = direction;
		DIAGNOSTIC_GRAPHS.add(this);
	}

	static void resetStatistics() {
		DIAGNOSTIC_GRAPHS.clear();
		labelsKept = 0L;
		labelsRejected = 0L;
		labelsRemoved = 0L;
		nodesCreated = 0L;
		nodesDeleted = 0L;
		sourceAwareMerges = 0L;
		propagatedNodes = 0L;
		propagationStops = 0L;
		deltaInputSegments = 0L;
		deltaOutputSegments = 0L;
		sourceOnlyChanges = 0L;
		insertNanos = 0L;
		propagationNanos = 0L;
	}

	static void setDiagnosticContext(String context) {
		diagnosticContext = context == null ? "" : context;
	}

	static String statisticsSummary() {
		long activeNodes = 0L;
		long activeLabels = 0L;
		long envelopeSegments = 0L;
		int maxLabels = 0;
		int maxSegments = 0;
		for (IncrementalSourcedDominanceGraph graph : DIAGNOSTIC_GRAPHS) {
			for (IncrementalNode node : graph.nodes) {
				if (!node.active) {
					continue;
				}
				activeNodes++;
				activeLabels += node.activeLocalLabels;
				envelopeSegments += node.envelope.segmentCount();
				maxLabels = Math.max(maxLabels, node.activeLocalLabels);
				maxSegments = Math.max(maxSegments, node.envelope.segmentCount());
			}
		}
		double avgLabels = activeNodes == 0L ? 0.0 : ((double) activeLabels) / activeNodes;
		double avgSegments = activeNodes == 0L ? 0.0 : ((double) envelopeSegments) / activeNodes;
		String timing = TIMING_DIAGNOSTIC
				? ", insert/propagateMs=" + formatMillis(insertNanos) + "/" + formatMillis(propagationNanos)
				: "";
		return "incrementalSourcedGraph context=" + diagnosticContext
				+ ", labels kept/rejected/removed=" + labelsKept + "/" + labelsRejected + "/" + labelsRemoved
				+ ", nodes created/deleted/active=" + nodesCreated + "/" + nodesDeleted + "/" + activeNodes
				+ ", activeLabel avg/max=" + format(avgLabels) + "/" + maxLabels
				+ ", envelopeSegment avg/max=" + format(avgSegments) + "/" + maxSegments
				+ ", merges=" + sourceAwareMerges
				+ ", propagated/stopped=" + propagatedNodes + "/" + propagationStops
				+ ", delta in/out segments=" + deltaInputSegments + "/" + deltaOutputSegments
				+ ", sourceOnlyChanges=" + sourceOnlyChanges + timing;
	}

	@Override
	public boolean insertOrDominate(Label label) {
		long begin = TIMING_DIAGNOSTIC ? System.nanoTime() : 0L;
		long propagationBefore = TIMING_DIAGNOSTIC ? propagationNanos : 0L;
		try {
			IncrementalNode sameNode = nodeByReachableSet.get(label.reachableSet);
			if (sameNode != null && sameNode.active) {
				return insertAtExistingNode(sameNode, label);
			}
			return insertNewNode(label);
		} finally {
			if (TIMING_DIAGNOSTIC) {
				// propagationNanos 在 propagate() 内单独统计，这里只保留 insert 的排他时间。
				insertNanos += System.nanoTime() - begin - (propagationNanos - propagationBefore);
			}
		}
	}

	private boolean insertAtExistingNode(IncrementalNode node, Label label) {
		// 绝大多数扩展 label 会被当前 g_u 直接拒绝。先只读扫描，避免为这些 label
		// 分配完整 merged envelope；只有真正改善 g_u 时才进入来源感知 merge。
		if (node.envelope.coversAndDominates(label.frontier, direction)) {
			label.isDominated = true;
			labelsRejected++;
			return true;
		}
		MergeOutcome outcome = node.envelope.mergeLocal(label.frontier, label);
		if (!outcome.candidateContributes) {
			label.isDominated = true;
			labelsRejected++;
			return true;
		}
		node.labels.add(label);
		node.activeLocalLabels++;
		labelsKept++;
		propagate(node, outcome.delta);
		return false;
	}

	private boolean insertNewNode(Label label) {
		ArrayList<IncrementalNode> predecessors = findTerminalSupersetNodes(label.reachableSet);
		SourcedEnvelope predecessorEnvelope = new SourcedEnvelope();
		for (IncrementalNode predecessor : predecessors) {
			if (predecessor.active) {
				predecessorEnvelope.mergeExternalEnvelope(predecessor.envelope);
			}
		}
		if (predecessorEnvelope.coversAndDominates(label.frontier, direction)) {
			label.isDominated = true;
			labelsRejected++;
			return true;
		}
		SourcedEnvelope envelope = predecessorEnvelope.copyAsExternal();
		MergeOutcome outcome = envelope.mergeLocal(label.frontier, label);
		if (!outcome.candidateContributes) {
			label.isDominated = true;
			labelsRejected++;
			return true;
		}

		IncrementalNode node = new IncrementalNode(label.reachableSet, predecessorEnvelope, envelope);
		node.labels.add(label);
		node.activeLocalLabels = 1;
		nodes.add(node);
		nodesCreated++;
		nodeByReachableSet.put(node.reachableKey, node);

		ArrayList<IncrementalNode> successors = findImmediateSubsetNodes(node.reachableKey, predecessors);
		for (IncrementalNode predecessor : predecessors) {
			connect(predecessor, node);
			for (IncrementalNode successor : successors) {
				disconnect(predecessor, successor);
			}
		}
		if (predecessors.isEmpty()) {
			roots.add(node);
		}
		for (IncrementalNode successor : successors) {
			roots.remove(successor);
			connect(node, successor);
		}

		labelsKept++;
		propagate(node, outcome.delta);
		return false;
	}

	/** 只传播本次真正降低综合包络的离散区间；来源变化但数值不降时不再向下传播。 */
	private void propagate(IncrementalNode changedNode, SparseDelta initialDelta) {
		if (initialDelta.isEmpty() || changedNode.successors.isEmpty()) {
			propagationStops++;
			return;
		}
		long begin = TIMING_DIAGNOSTIC ? System.nanoTime() : 0L;
		long queueMark = nextMark();
		ArrayDeque<PropagationItem> queue = new ArrayDeque<PropagationItem>();
		for (IncrementalNode successor : changedNode.successors) {
			enqueueDelta(queue, successor, initialDelta, queueMark);
		}
		while (!queue.isEmpty()) {
			PropagationItem item = queue.poll();
			IncrementalNode node = item.node;
			if (!node.active) {
				continue;
			}
			propagatedNodes++;
			deltaInputSegments += item.delta.segmentCount();
			MergeOutcome predecessorOutcome = node.predecessorEnvelope.mergeExternal(item.delta);
			MergeOutcome outcome = node.envelope.mergeExternal(item.delta);
			if (!predecessorOutcome.delta.isEmpty()) {
				removeLabelsDominatedByPredecessors(node);
			}

			ArrayList<IncrementalNode> successors;
			if (node.activeLocalLabels == 0) {
				successors = deleteNode(node);
			} else {
				successors = new ArrayList<IncrementalNode>(node.successors);
			}
			if (outcome.delta.isEmpty()) {
				propagationStops++;
				continue;
			}
			deltaOutputSegments += outcome.delta.segmentCount();
			for (IncrementalNode successor : successors) {
				enqueueDelta(queue, successor, outcome.delta, queueMark);
			}
		}
		if (TIMING_DIAGNOSTIC) {
			propagationNanos += System.nanoTime() - begin;
		}
	}

	/**
	 * 一次插入只传播同一个 F_new，且 dominance 边上只有 min merge。若 F_new 在下游 node 的
	 * 某时刻改善 g，它必然也在该时刻改善每个直接 predecessor；因此任一首条到达父边已经包含
	 * 该 node 的全部可能变化，本轮只需入队一次。未来若批量传播多个独立 label，才需要 pending delta。
	 */
	private static void enqueueDelta(ArrayDeque<PropagationItem> queue, IncrementalNode node, SparseDelta delta,
			long queueMark) {
		if (!node.active || delta.isEmpty() || node.propagationQueueMark == queueMark) {
			return;
		}
		node.propagationQueueMark = queueMark;
		queue.add(new PropagationItem(node, delta));
	}

	private void removeLabelsDominatedByPredecessors(IncrementalNode node) {
		for (Label label : node.labels) {
			if (label.isDominated || !node.predecessorEnvelope.coversAndDominates(label.frontier, direction)) {
				continue;
			}
			label.isDominated = true;
			node.activeLocalLabels--;
			labelsRemoved++;
		}
	}

	@Override
	public ArrayList<Label> getActiveLabels() {
		ArrayList<Label> result = new ArrayList<Label>();
		collectActiveLabels(result);
		return result;
	}

	@Override
	public void collectActiveLabels(ArrayList<Label> buffer) {
		for (IncrementalNode node : nodes) {
			if (!node.active) {
				continue;
			}
			for (Label label : node.labels) {
				if (!label.isDominated) {
					buffer.add(label);
				}
			}
		}
	}

	@Override
	public boolean dominatesSinglePoint(PackedBitSet reachableSet, int reachableCardinality, double pointTime,
			double pointValue) {
		double best = debugBestValue(reachableSet, reachableCardinality, pointTime);
		return !Utility.compareGt(best, pointValue);
	}

	/** 测试入口：返回当前 reachable-set 口径下的点值下包络。 */
	double debugBestValue(PackedBitSet reachableSet, int reachableCardinality, double pointTime) {
		IncrementalNode sameNode = nodeByReachableSet.get(reachableSet);
		if (sameNode != null && sameNode.active) {
			return sameNode.envelope.valueAt(pointTime);
		}
		double best = Utility.big_M;
		for (IncrementalNode node : findTerminalSupersetNodes(reachableSet)) {
			double candidate = node.envelope.valueAt(pointTime);
			if (Utility.compareLt(candidate, best)) {
				best = candidate;
			}
		}
		return best;
	}

	private ArrayList<IncrementalNode> findTerminalSupersetNodes(PackedBitSet target) {
		ArrayList<IncrementalNode> result = new ArrayList<IncrementalNode>();
		ArrayDeque<IncrementalNode> stack = new ArrayDeque<IncrementalNode>();
		long visitMark = nextMark();
		int targetCardinality = target.cardinality();
		for (IncrementalNode root : roots) {
			if (root.active && root.reachableCardinality >= targetCardinality
					&& root.reachableKey.isSupersetOf(target)) {
				stack.push(root);
			}
		}
		while (!stack.isEmpty()) {
			IncrementalNode node = stack.pop();
			if (node.visitMark == visitMark) {
				continue;
			}
			node.visitMark = visitMark;
			boolean hasDeeperSuperset = false;
			for (IncrementalNode successor : node.successors) {
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
		return result;
	}

	private ArrayList<IncrementalNode> findImmediateSubsetNodes(PackedBitSet newKey,
			ArrayList<IncrementalNode> predecessors) {
		ArrayList<IncrementalNode> starts = new ArrayList<IncrementalNode>();
		long startMark = nextMark();
		long visitMark = nextMark();
		int newCardinality = newKey.cardinality();
		if (predecessors.isEmpty()) {
			starts.addAll(roots);
		} else {
			for (IncrementalNode predecessor : predecessors) {
				for (IncrementalNode successor : predecessor.successors) {
					if (successor.startMark != startMark) {
						successor.startMark = startMark;
						starts.add(successor);
					}
				}
			}
		}

		ArrayList<IncrementalNode> candidates = new ArrayList<IncrementalNode>();
		ArrayDeque<IncrementalNode> stack = new ArrayDeque<IncrementalNode>();
		for (IncrementalNode start : starts) {
			if (start.active) {
				stack.push(start);
			}
		}
		while (!stack.isEmpty()) {
			IncrementalNode node = stack.pop();
			if (node.visitMark == visitMark) {
				continue;
			}
			node.visitMark = visitMark;
			if (node.reachableCardinality <= newCardinality && node.reachableKey.isSubsetOf(newKey)) {
				candidates.add(node);
				continue;
			}
			for (IncrementalNode successor : node.successors) {
				if (successor.active) {
					stack.push(successor);
				}
			}
		}
		return removeRedundantSubsetCandidates(candidates);
	}

	private static ArrayList<IncrementalNode> removeRedundantSubsetCandidates(
			ArrayList<IncrementalNode> candidates) {
		Collections.sort(candidates, new Comparator<IncrementalNode>() {
			@Override
			public int compare(IncrementalNode a, IncrementalNode b) {
				return Integer.compare(b.reachableCardinality, a.reachableCardinality);
			}
		});
		ArrayList<IncrementalNode> result = new ArrayList<IncrementalNode>();
		for (IncrementalNode candidate : candidates) {
			boolean covered = false;
			for (IncrementalNode kept : result) {
				if (kept.reachableKey.isSupersetOf(candidate.reachableKey)) {
					covered = true;
					break;
				}
			}
			if (!covered) {
				result.add(candidate);
			}
		}
		return result;
	}

	private ArrayList<IncrementalNode> deleteNode(IncrementalNode node) {
		node.active = false;
		nodesDeleted++;
		for (Label label : node.labels) {
			if (!label.isDominated) {
				label.isDominated = true;
				labelsRemoved++;
			}
		}
		node.activeLocalLabels = 0;
		nodeByReachableSet.remove(node.reachableKey);
		roots.remove(node);

		ArrayList<IncrementalNode> predecessors = new ArrayList<IncrementalNode>(node.predecessors);
		ArrayList<IncrementalNode> successors = new ArrayList<IncrementalNode>(node.successors);
		for (IncrementalNode predecessor : predecessors) {
			disconnect(predecessor, node);
		}
		for (IncrementalNode successor : successors) {
			disconnect(node, successor);
		}
		for (IncrementalNode predecessor : predecessors) {
			if (!predecessor.active) {
				continue;
			}
			ArrayList<IncrementalNode> compatible = new ArrayList<IncrementalNode>();
			for (IncrementalNode successor : successors) {
				if (successor.active && predecessor.reachableKey.isSupersetOf(successor.reachableKey)) {
					compatible.add(successor);
				}
			}
			for (IncrementalNode successor : removeRedundantSubsetCandidates(compatible)) {
				connect(predecessor, successor);
			}
		}
		for (IncrementalNode successor : successors) {
			if (successor.active && successor.predecessors.isEmpty()) {
				roots.add(successor);
			}
		}
		return successors;
	}

	private static void connect(IncrementalNode from, IncrementalNode to) {
		if (from == to) {
			return;
		}
		from.successors.add(to);
		to.predecessors.add(from);
	}

	private static void disconnect(IncrementalNode from, IncrementalNode to) {
		from.successors.remove(to);
		to.predecessors.remove(from);
	}

	private static long nextMark() {
		markSeed++;
		if (markSeed == Long.MAX_VALUE) {
			markSeed = 1L;
		}
		return markSeed;
	}

	private static String formatMillis(long nanos) {
		return format(nanos / 1_000_000.0);
	}

	private static String format(double value) {
		return String.format(java.util.Locale.US, "%.3f", value);
	}

	private static final class IncrementalNode {
		final PackedBitSet reachableKey;
		final int reachableCardinality;
		final ArrayList<Label> labels = new ArrayList<Label>();
		final LinkedHashSet<IncrementalNode> predecessors = new LinkedHashSet<IncrementalNode>();
		final LinkedHashSet<IncrementalNode> successors = new LinkedHashSet<IncrementalNode>();
		final SourcedEnvelope predecessorEnvelope;
		final SourcedEnvelope envelope;
		int activeLocalLabels;
		boolean active = true;
		long startMark;
		long visitMark;
		long propagationQueueMark;

		IncrementalNode(PackedBitSet reachableKey, SourcedEnvelope predecessorEnvelope,
				SourcedEnvelope envelope) {
			this.reachableKey = reachableKey.copy();
			this.reachableCardinality = this.reachableKey.cardinality();
			this.predecessorEnvelope = predecessorEnvelope;
			this.envelope = envelope;
		}
	}

	private static final class PropagationItem {
		final IncrementalNode node;
		final SparseDelta delta;

		PropagationItem(IncrementalNode node, SparseDelta delta) {
			this.node = node;
			this.delta = delta;
		}
	}

	private static final class MergeOutcome {
		final SparseDelta delta = new SparseDelta();
		boolean candidateContributes;
		boolean sourceChanged;
	}

	/**
	 * dominance 专用包络。几何和 PWLF 一致，但每段额外记录本地 label；null 表示 predecessor 来源。
	 */
	private static final class SourcedEnvelope {
		private ArrayList<SourcedSegment> segments = new ArrayList<SourcedSegment>();

		MergeOutcome mergeLocal(PiecewiseLinearFunction function, Label source) {
			return merge(new PwlfCursor(function), source, false);
		}

		MergeOutcome mergeExternal(SparseDelta delta) {
			return merge(new DeltaCursor(delta), null, true);
		}

		MergeOutcome mergeExternalEnvelope(SourcedEnvelope other) {
			if (other == null || other.segments.isEmpty()) {
				return new MergeOutcome();
			}
			return merge(new SourcedCursor(other.segments), null, true);
		}

		SourcedEnvelope copyAsExternal() {
			SourcedEnvelope copy = new SourcedEnvelope();
			for (SourcedSegment segment : segments) {
				append(copy.segments, segment.start, segment.end, segment.slope, segment.intercept, null);
			}
			return copy;
		}

		private MergeOutcome merge(CandidateCursor fresh, Label source, boolean externalWinsTies) {
			sourceAwareMerges++;
			MergeOutcome outcome = new MergeOutcome();
			if (!fresh.hasCurrent()) {
				return outcome;
			}
			ArrayList<SourcedSegment> merged = new ArrayList<SourcedSegment>(segments.size() + 8);
			int oldIndex = 0;
			double oldCursor = segments.isEmpty() ? Double.POSITIVE_INFINITY : segments.get(0).start;
			double freshCursor = fresh.start();
			while (oldIndex < segments.size() || fresh.hasCurrent()) {
				if (oldIndex >= segments.size()) {
					appendFreshRemainder(merged, fresh, freshCursor, source, outcome);
					break;
				}
				if (!fresh.hasCurrent()) {
					appendOldRemainder(merged, oldIndex, oldCursor);
					break;
				}
				SourcedSegment old = segments.get(oldIndex);
				double oldStart = Math.max(old.start, oldCursor);
				double freshStart = Math.max(fresh.start(), freshCursor);
				if (Utility.compareLe(old.end, oldStart)) {
					oldIndex++;
					if (oldIndex < segments.size()) {
						oldCursor = segments.get(oldIndex).start;
					}
					continue;
				}
				if (Utility.compareLe(fresh.end(), freshStart)) {
					fresh.advance();
					if (fresh.hasCurrent()) {
						freshCursor = fresh.start();
					}
					continue;
				}
				if (Utility.compareLt(oldStart, freshStart)) {
					double end = Math.min(old.end, freshStart);
					append(merged, oldStart, end, old.slope, old.intercept, old.source);
					oldCursor = end;
					continue;
				}
				if (Utility.compareLt(freshStart, oldStart)) {
					double end = Math.min(fresh.end(), oldStart);
					appendCandidate(merged, freshStart, end, fresh.slope(), fresh.intercept(), source, outcome,
							true);
					freshCursor = end;
					continue;
				}

				double start = oldStart;
				double end = Math.min(old.end, fresh.end());
				appendLower(merged, start, end, old, fresh.slope(), fresh.intercept(), source,
						externalWinsTies, outcome);
				oldCursor = end;
				freshCursor = end;
				if (Utility.compareEq(end, old.end)) {
					oldIndex++;
					if (oldIndex < segments.size()) {
						oldCursor = segments.get(oldIndex).start;
					}
				}
				if (Utility.compareEq(end, fresh.end())) {
					fresh.advance();
					if (fresh.hasCurrent()) {
						freshCursor = fresh.start();
					}
				}
			}

			if (source != null && !outcome.candidateContributes) {
				// 本地候选在 tie 时不替换旧来源；没有正长度贡献就等价于被旧综合包络完整占优。
				return outcome;
			}
			if (!outcome.delta.isEmpty() || outcome.sourceChanged || segments.isEmpty()) {
				segments = merged;
				if (outcome.delta.isEmpty() && outcome.sourceChanged) {
					sourceOnlyChanges++;
				}
			}
			return outcome;
		}

		private static void appendLower(ArrayList<SourcedSegment> target, double start, double end,
				SourcedSegment old, double freshSlope, double freshIntercept, Label freshSource,
				boolean externalWinsTies, MergeOutcome outcome) {
			if (!Utility.compareLt(start, end)) {
				return;
			}
			double oldStart = old.value(start);
			double oldEnd = old.value(end);
			double freshStart = freshSlope * start + freshIntercept;
			double freshEnd = freshSlope * end + freshIntercept;
			boolean freshNoWorse = Utility.compareLe(freshStart, oldStart)
					&& Utility.compareLe(freshEnd, oldEnd);
			boolean oldNoWorse = Utility.compareLe(oldStart, freshStart)
					&& Utility.compareLe(oldEnd, freshEnd);
			if (freshNoWorse && !oldNoWorse) {
				appendCandidate(target, start, end, freshSlope, freshIntercept, freshSource, outcome, true);
				return;
			}
			if (oldNoWorse && !freshNoWorse) {
				append(target, start, end, old.slope, old.intercept, old.source);
				return;
			}
			if (freshNoWorse && oldNoWorse) {
				if (externalWinsTies && old.source != null) {
					// 数值仍保留旧几何，只把来源改成 predecessor，避免容差内近似相等造成数值漂移。
					append(target, start, end, old.slope, old.intercept, null);
					outcome.sourceChanged = true;
				} else {
					append(target, start, end, old.slope, old.intercept, old.source);
				}
				return;
			}

			double slopeDiff = old.slope - freshSlope;
			double crossing = Utility.compareEq(slopeDiff, 0.0)
					? Double.NaN : (freshIntercept - old.intercept) / slopeDiff;
			if (Double.isFinite(crossing) && Utility.compareLt(start, crossing)
					&& Utility.compareLt(crossing, end)) {
				appendLower(target, start, crossing, old, freshSlope, freshIntercept, freshSource,
						externalWinsTies, outcome);
				appendLower(target, crossing, end, old, freshSlope, freshIntercept, freshSource,
						externalWinsTies, outcome);
				return;
			}
			double mid = 0.5 * (start + end);
			double oldMid = old.value(mid);
			double freshMid = freshSlope * mid + freshIntercept;
			if (Utility.compareLt(freshMid, oldMid)) {
				appendCandidate(target, start, end, freshSlope, freshIntercept, freshSource, outcome, true);
			} else if (Utility.compareLt(oldMid, freshMid)) {
				append(target, start, end, old.slope, old.intercept, old.source);
			} else if (externalWinsTies && old.source != null) {
				append(target, start, end, old.slope, old.intercept, null);
				outcome.sourceChanged = true;
			} else {
				append(target, start, end, old.slope, old.intercept, old.source);
			}
		}

		private static void appendFreshRemainder(ArrayList<SourcedSegment> target, CandidateCursor fresh,
				double cursor, Label source, MergeOutcome outcome) {
			double currentStart = cursor;
			while (fresh.hasCurrent()) {
				double start = Math.max(fresh.start(), currentStart);
				appendCandidate(target, start, fresh.end(), fresh.slope(), fresh.intercept(), source, outcome,
						true);
				fresh.advance();
				if (fresh.hasCurrent()) {
					currentStart = fresh.start();
				}
			}
		}

		private void appendOldRemainder(ArrayList<SourcedSegment> target, int oldIndex, double cursor) {
			for (int i = oldIndex; i < segments.size(); i++) {
				SourcedSegment old = segments.get(i);
				double start = i == oldIndex ? Math.max(old.start, cursor) : old.start;
				append(target, start, old.end, old.slope, old.intercept, old.source);
			}
		}

		private static void appendCandidate(ArrayList<SourcedSegment> target, double start, double end,
				double slope, double intercept, Label source, MergeOutcome outcome, boolean numericChange) {
			append(target, start, end, slope, intercept, source);
			if (source != null && Utility.compareLt(start, end)
					&& !Utility.isBigMValue(slope * (0.5 * (start + end)) + intercept)) {
				outcome.candidateContributes = true;
			}
			if (numericChange && Utility.compareLt(start, end)) {
				outcome.delta.add(start, end, slope, intercept);
			}
		}

		private static void append(ArrayList<SourcedSegment> target, double start, double end, double slope,
				double intercept, Label source) {
			if (!Utility.compareLt(start, end)) {
				return;
			}
			if (!target.isEmpty()) {
				SourcedSegment tail = target.get(target.size() - 1);
				if (tail.source == source && Utility.compareEq(tail.end, start)
						&& Utility.compareEq(tail.slope, slope)
						&& Utility.compareEq(tail.intercept, intercept)) {
					tail.end = end;
					return;
				}
			}
			target.add(new SourcedSegment(start, end, slope, intercept, source));
		}

		double valueAt(double time) {
			if (segments.isEmpty() || Utility.compareLt(time, segments.get(0).start)
					|| Utility.compareGt(time, segments.get(segments.size() - 1).end)) {
				return Utility.big_M;
			}
			int low = 0;
			int high = segments.size() - 1;
			while (low <= high) {
				int mid = (low + high) >>> 1;
				if (Utility.compareLe(segments.get(mid).start, time)) {
					low = mid + 1;
				} else {
					high = mid - 1;
				}
			}
			int index = Math.max(0, high);
			double best = Utility.big_M;
			SourcedSegment segment = segments.get(index);
			if (Utility.compareLe(segment.start, time) && Utility.compareLe(time, segment.end)) {
				best = segment.value(time);
			}
			if (index > 0) {
				SourcedSegment previous = segments.get(index - 1);
				if (Utility.compareEq(previous.end, time)) {
					best = Math.min(best, previous.value(time));
				}
			}
			return best;
		}

		boolean coversAndDominates(PiecewiseLinearFunction target, Direction direction) {
			if (segments.isEmpty() || target == null || target.head == null) {
				return false;
			}
			SourcedSegment first = segments.get(0);
			SourcedSegment last = segments.get(segments.size() - 1);
			if (direction == Direction.FORWARD) {
				if (Utility.compareGt(first.start, target.head.start)) {
					return false;
				}
			} else if (Utility.compareLt(last.end, target.tail.end)) {
				return false;
			}

			int envelopeIndex = 0;
			Segment targetSegment = target.head;
			double current = target.head.start;
			double targetEnd = target.tail.end;
			while (envelopeIndex < segments.size()
					&& Utility.compareLe(segments.get(envelopeIndex).end, current)) {
				envelopeIndex++;
			}
			while (targetSegment != null && Utility.compareLe(targetSegment.end, current)) {
				targetSegment = targetSegment.next;
			}
			while (targetSegment != null && Utility.compareLt(current, targetEnd)) {
				if (envelopeIndex >= segments.size()) {
					return false;
				}
				SourcedSegment envelopeSegment = segments.get(envelopeIndex);
				if (Utility.compareLt(current, envelopeSegment.start)) {
					return false;
				}
				double next = Math.min(Math.min(envelopeSegment.end, targetSegment.end), targetEnd);
				double envelopeStart = envelopeSegment.value(current);
				double envelopeEnd = envelopeSegment.value(next);
				double targetStart = targetSegment.slope * current + targetSegment.intercept;
				double targetNext = targetSegment.slope * next + targetSegment.intercept;
				if (Utility.compareGt(envelopeStart, targetStart)
						|| Utility.compareGt(envelopeEnd, targetNext)) {
					return false;
				}
				current = next;
				if (Utility.compareEq(envelopeSegment.end, current)) {
					envelopeIndex++;
				}
				if (Utility.compareEq(targetSegment.end, current)) {
					targetSegment = targetSegment.next;
				}
			}
			return !Utility.compareLt(current, targetEnd);
		}

		int segmentCount() {
			return segments.size();
		}
	}

	private static final class SourcedSegment {
		final double start;
		double end;
		final double slope;
		final double intercept;
		final Label source;

		SourcedSegment(double start, double end, double slope, double intercept, Label source) {
			this.start = start;
			this.end = end;
			this.slope = slope;
			this.intercept = intercept;
			this.source = source;
		}

		double value(double time) {
			return slope * time + intercept;
		}
	}

	/** 只保存真实下降区间，不用区间外 BigM 填充。 */
	private static final class SparseDelta {
		final ArrayList<DeltaSegment> segments = new ArrayList<DeltaSegment>();

		void add(double start, double end, double slope, double intercept) {
			if (!Utility.compareLt(start, end)) {
				return;
			}
			if (!segments.isEmpty()) {
				DeltaSegment tail = segments.get(segments.size() - 1);
				if (Utility.compareEq(tail.end, start) && Utility.compareEq(tail.slope, slope)
						&& Utility.compareEq(tail.intercept, intercept)) {
					tail.end = end;
					return;
				}
			}
			segments.add(new DeltaSegment(start, end, slope, intercept));
		}

		boolean isEmpty() {
			return segments.isEmpty();
		}

		int segmentCount() {
			return segments.size();
		}
	}

	private static final class DeltaSegment {
		final double start;
		double end;
		final double slope;
		final double intercept;

		DeltaSegment(double start, double end, double slope, double intercept) {
			this.start = start;
			this.end = end;
			this.slope = slope;
			this.intercept = intercept;
		}
	}

	private interface CandidateCursor {
		boolean hasCurrent();

		double start();

		double end();

		double slope();

		double intercept();

		void advance();
	}

	private static final class PwlfCursor implements CandidateCursor {
		private Segment current;

		PwlfCursor(PiecewiseLinearFunction function) {
			this.current = function == null ? null : function.head;
		}

		@Override
		public boolean hasCurrent() {
			return current != null;
		}

		@Override
		public double start() {
			return current.start;
		}

		@Override
		public double end() {
			return current.end;
		}

		@Override
		public double slope() {
			return current.slope;
		}

		@Override
		public double intercept() {
			return current.intercept;
		}

		@Override
		public void advance() {
			current = current.next;
		}
	}

	private static final class DeltaCursor implements CandidateCursor {
		private final ArrayList<DeltaSegment> segments;
		private int index;

		DeltaCursor(SparseDelta delta) {
			this.segments = delta.segments;
		}

		@Override
		public boolean hasCurrent() {
			return index < segments.size();
		}

		@Override
		public double start() {
			return segments.get(index).start;
		}

		@Override
		public double end() {
			return segments.get(index).end;
		}

		@Override
		public double slope() {
			return segments.get(index).slope;
		}

		@Override
		public double intercept() {
			return segments.get(index).intercept;
		}

		@Override
		public void advance() {
			index++;
		}
	}

	private static final class SourcedCursor implements CandidateCursor {
		private final ArrayList<SourcedSegment> segments;
		private int index;

		SourcedCursor(ArrayList<SourcedSegment> segments) {
			this.segments = segments;
		}

		@Override
		public boolean hasCurrent() {
			return index < segments.size();
		}

		@Override
		public double start() {
			return segments.get(index).start;
		}

		@Override
		public double end() {
			return segments.get(index).end;
		}

		@Override
		public double slope() {
			return segments.get(index).slope;
		}

		@Override
		public double intercept() {
			return segments.get(index).intercept;
		}

		@Override
		public void advance() {
			index++;
		}
	}
}
