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
final class IndexedPaperDominanceGraph implements DominanceStore {

	private static final boolean TIMING_DIAGNOSTIC = Boolean.getBoolean("twet.bpc.paperGraphTiming");
	private static final boolean USE_CONTAINMENT_INDEX = Boolean.getBoolean(
			"twet.bpc.paperGraphUseContainmentIndex");
	private static final boolean USE_SET_TRIE = Boolean.getBoolean("twet.bpc.paperGraphUseSetTrie");
	private static final int SET_TRIE_SUPERSET_MIN_CARDINALITY = Integer.getInteger(
			"twet.bpc.paperGraphSetTrieSupersetMinCardinality", 4);
	private static final boolean USE_SUPERSET_CACHE = Boolean.getBoolean("twet.bpc.paperGraphSupersetCache");
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
	private static long supersetCacheHits;
	private static long supersetCacheMisses;
	private static long propagationCalls;
	private static long propagationNodesVisited;
	private static long envelopeMergeCalls;
	private static long dominanceChecks;
	private static long markSeed;
	private static long timingStartNanos;
	private static long nextTimingHeartbeatNanos;

	private final ArrayList<PaperDominanceNode> nodes = new ArrayList<PaperDominanceNode>();
	private final LinkedHashSet<PaperDominanceNode> roots = new LinkedHashSet<PaperDominanceNode>();
	private final Map<PackedBitSet, PaperDominanceNode> nodeByReachableSet = new HashMap<PackedBitSet, PaperDominanceNode>();
	private final ArrayList<LinkedHashSet<PaperDominanceNode>> nodesByCardinality = new ArrayList<LinkedHashSet<PaperDominanceNode>>();
	private final ArrayList<LinkedHashSet<PaperDominanceNode>> nodesByJob = new ArrayList<LinkedHashSet<PaperDominanceNode>>();
	private final Map<PackedBitSet, SupersetCacheEntry> terminalSupersetCache = new HashMap<PackedBitSet, SupersetCacheEntry>();
	private final SetTrieNode setTrieRoot = new SetTrieNode(-1);
	private final Direction direction;
	private long structureVersion;

	IndexedPaperDominanceGraph() {
		this(Direction.FORWARD);
	}

	IndexedPaperDominanceGraph(Direction direction) {
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
		supersetSearchNanos = 0;
		maxSupersetSearchNanos = 0;
		subsetSearchCalls = 0;
		subsetNodesVisited = 0;
		subsetSearchNanos = 0;
		maxSubsetSearchNanos = 0;
		supersetCacheHits = 0;
		supersetCacheMisses = 0;
		timingStartNanos = TIMING_HEARTBEAT ? System.nanoTime() : 0L;
		nextTimingHeartbeatNanos = TIMING_HEARTBEAT
				? timingStartNanos + TIMING_HEARTBEAT_INTERVAL_NANOS : 0L;
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
				+ ", superset cache hit/miss=" + supersetCacheHits + "/" + supersetCacheMisses
				+ timingSummary()
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
		if (canCoverDomain(dominanceEnvelope, label.frontier, direction)
				&& dominanceEnvelope.dominates(label.frontier)) {
			label.isDominated = true;
			labelsRejected++;
			return true;
		}

		PaperDominanceNode inserted;
		if (sameNode != null && sameNode.active) {
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
		return findTerminalSupersetNodes(target, target.cardinality());
	}

	private ArrayList<PaperDominanceNode> findTerminalSupersetNodes(PackedBitSet target, int targetCardinality) {
		long begin = TIMING_DIAGNOSTIC ? System.nanoTime() : 0L;
		supersetSearchCalls++;
		if (USE_SUPERSET_CACHE) {
			SupersetCacheEntry cached = terminalSupersetCache.get(target);
			if (cached != null && cached.structureVersion == structureVersion) {
				supersetCacheHits++;
				if (TIMING_DIAGNOSTIC) {
					recordSupersetSearchNanos(System.nanoTime() - begin);
				}
				return new ArrayList<PaperDominanceNode>(cached.nodes);
			}
			supersetCacheMisses++;
		}
		ArrayList<PaperDominanceNode> result = new ArrayList<PaperDominanceNode>();
		try {
			ArrayList<PaperDominanceNode> candidates;
			if (shouldUseSupersetSetTrie(targetCardinality)) {
				candidates = collectSupersetCandidatesSetTrie(target);
			} else if (shouldUseSupersetIndex(target)) {
				candidates = collectSupersetCandidates(target, targetCardinality);
			} else {
				candidates = collectSupersetCandidatesDfs(target, targetCardinality);
			}
			for (PaperDominanceNode node : candidates) {
				boolean hasDeeperSuperset = false;
				for (PaperDominanceNode successor : node.successors) {
					if (successor.active && successor.reachableCardinality >= targetCardinality
							&& successor.reachableKey.isSupersetOf(target)) {
						hasDeeperSuperset = true;
						break;
					}
				}
				if (!hasDeeperSuperset) {
					result.add(node);
				}
			}
			if (USE_SUPERSET_CACHE) {
				terminalSupersetCache.put(target.copy(), new SupersetCacheEntry(structureVersion, result));
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
		if (USE_CONTAINMENT_INDEX) {
			addNodeToIndexes(node);
		}
		if (USE_SET_TRIE) {
			addNodeToSetTrie(node);
		}
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
		invalidateSupersetCache();
		return node;
	}

	private ArrayList<PaperDominanceNode> findImmediateSubsetNodes(PackedBitSet newKey,
			ArrayList<PaperDominanceNode> predecessors) {
		long begin = TIMING_DIAGNOSTIC ? System.nanoTime() : 0L;
		subsetSearchCalls++;
		int newCardinality = newKey.cardinality();
		try {
			ArrayList<PaperDominanceNode> starts = collectSubsetSearchStarts(predecessors);
			ArrayList<PaperDominanceNode> candidates = new ArrayList<PaperDominanceNode>();
			if (shouldUseSubsetIndex(newKey, newCardinality, starts.size())) {
				collectSubsetCandidates(newKey, newCardinality, candidates);
			} else {
				collectSubsetCandidatesDfs(newKey, newCardinality, starts, candidates);
			}
			removeNonSubsetCandidates(newKey, candidates);
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

	private ArrayList<PaperDominanceNode> collectSupersetCandidates(PackedBitSet target, int targetCardinality) {
		ArrayList<PaperDominanceNode> candidates = new ArrayList<PaperDominanceNode>();
		LinkedHashSet<PaperDominanceNode> seed = null;
		for (int bit = target.nextSetBit(0); bit >= 0; bit = target.nextSetBit(bit + 1)) {
			LinkedHashSet<PaperDominanceNode> posting = bit < nodesByJob.size() ? nodesByJob.get(bit) : null;
			if (posting == null || posting.isEmpty()) {
				return candidates;
			}
			if (seed == null || posting.size() < seed.size()) {
				seed = posting;
			}
		}
		if (seed == null) {
			collectActiveNodesByMaxCardinality(Integer.MAX_VALUE, candidates, 0L, false, "superset-loop");
			return candidates;
		}
		for (PaperDominanceNode node : seed) {
			supersetNodesVisited++;
			if (TIMING_HEARTBEAT && (supersetNodesVisited & TIMING_HEARTBEAT_VISIT_MASK) == 0L) {
				maybePrintTimingHeartbeat("superset-loop");
			}
			if (node.active && node.reachableCardinality >= targetCardinality
					&& node.reachableKey.isSupersetOf(target)) {
				candidates.add(node);
			}
		}
		return candidates;
	}

	private ArrayList<PaperDominanceNode> collectSupersetCandidatesDfs(PackedBitSet target, int targetCardinality) {
		ArrayList<PaperDominanceNode> result = new ArrayList<PaperDominanceNode>();
		ArrayDeque<PaperDominanceNode> stack = new ArrayDeque<PaperDominanceNode>();
		long visitMark = nextMark();
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
			result.add(node);
			for (PaperDominanceNode successor : node.successors) {
				if (successor.active && successor.reachableCardinality >= targetCardinality
						&& successor.reachableKey.isSupersetOf(target)) {
					stack.push(successor);
				}
			}
		}
		return result;
	}

	private ArrayList<PaperDominanceNode> collectSupersetCandidatesSetTrie(PackedBitSet target) {
		ArrayList<PaperDominanceNode> result = new ArrayList<PaperDominanceNode>();
		int[] queryBits = toBits(target);
		collectSupersetCandidatesSetTrie(setTrieRoot, queryBits, 0, result);
		return result;
	}

	private void collectSupersetCandidatesSetTrie(SetTrieNode trieNode, int[] queryBits, int queryIndex,
			ArrayList<PaperDominanceNode> result) {
		if (queryIndex >= queryBits.length) {
			collectAllSetTrieTerminals(trieNode, result);
			return;
		}
		int requiredBit = queryBits[queryIndex];
		for (SetTrieNode child : trieNode.children) {
			if (child.bit < requiredBit) {
				collectSupersetCandidatesSetTrie(child, queryBits, queryIndex, result);
			} else if (child.bit == requiredBit) {
				collectSupersetCandidatesSetTrie(child, queryBits, queryIndex + 1, result);
			} else {
				break;
			}
		}
	}

	private void collectAllSetTrieTerminals(SetTrieNode trieNode, ArrayList<PaperDominanceNode> result) {
		if (trieNode.terminal != null && trieNode.terminal.active) {
			supersetNodesVisited++;
			if (TIMING_HEARTBEAT && (supersetNodesVisited & TIMING_HEARTBEAT_VISIT_MASK) == 0L) {
				maybePrintTimingHeartbeat("superset-trie");
			}
			result.add(trieNode.terminal);
		}
		for (SetTrieNode child : trieNode.children) {
			collectAllSetTrieTerminals(child, result);
		}
	}

	private void collectSubsetCandidates(PackedBitSet newKey, int newCardinality,
			ArrayList<PaperDominanceNode> candidates) {
		int cardinalityCandidates = countCardinalityCandidates(newCardinality);
		int excludedPostingCost = countExcludedPostingCost(newKey);
		if (excludedPostingCost < cardinalityCandidates) {
			long excludedMark = nextMark();
			markNodesContainingExcludedJobs(newKey, excludedMark);
			collectActiveNodesByMaxCardinality(newCardinality, candidates, excludedMark, true, "subset-loop");
			return;
		}
		collectActiveNodesByMaxCardinality(newCardinality, candidates, 0L, false, "subset-loop");
	}

	private ArrayList<PaperDominanceNode> collectSubsetSearchStarts(ArrayList<PaperDominanceNode> predecessors) {
		ArrayList<PaperDominanceNode> starts = new ArrayList<PaperDominanceNode>();
		if (predecessors.isEmpty()) {
			starts.addAll(roots);
			return starts;
		}
		long startMark = nextMark();
		for (PaperDominanceNode predecessor : predecessors) {
			for (PaperDominanceNode successor : predecessor.successors) {
				if (successor.startMark != startMark) {
					successor.startMark = startMark;
					starts.add(successor);
				}
			}
		}
		return starts;
	}

	private void collectSubsetCandidatesDfs(PackedBitSet newKey, int newCardinality,
			ArrayList<PaperDominanceNode> starts, ArrayList<PaperDominanceNode> candidates) {
		ArrayDeque<PaperDominanceNode> stack = new ArrayDeque<PaperDominanceNode>();
		long visitMark = nextMark();
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
	}

	private void removeNonSubsetCandidates(PackedBitSet newKey, ArrayList<PaperDominanceNode> candidates) {
		for (int i = candidates.size() - 1; i >= 0; i--) {
			if (!candidates.get(i).reachableKey.isSubsetOf(newKey)) {
				candidates.remove(i);
			}
		}
	}

	private void collectActiveNodesByMaxCardinality(int maxCardinality, ArrayList<PaperDominanceNode> candidates,
			long excludedMark, boolean useExcludedMark, String heartbeatPhase) {
		int limit = Math.min(maxCardinality, nodesByCardinality.size() - 1);
		for (int card = 0; card <= limit; card++) {
			LinkedHashSet<PaperDominanceNode> bucket = nodesByCardinality.get(card);
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (PaperDominanceNode node : bucket) {
				if (!node.active || (useExcludedMark && node.indexMark == excludedMark)) {
					continue;
				}
				if ("superset-loop".equals(heartbeatPhase)) {
					supersetNodesVisited++;
					if (TIMING_HEARTBEAT && (supersetNodesVisited & TIMING_HEARTBEAT_VISIT_MASK) == 0L) {
						maybePrintTimingHeartbeat(heartbeatPhase);
					}
				} else {
					subsetNodesVisited++;
					if (TIMING_HEARTBEAT && (subsetNodesVisited & TIMING_HEARTBEAT_VISIT_MASK) == 0L) {
						maybePrintTimingHeartbeat(heartbeatPhase);
					}
				}
				candidates.add(node);
			}
		}
	}

	private int countCardinalityCandidates(int maxCardinality) {
		int count = 0;
		int limit = Math.min(maxCardinality, nodesByCardinality.size() - 1);
		for (int card = 0; card <= limit; card++) {
			LinkedHashSet<PaperDominanceNode> bucket = nodesByCardinality.get(card);
			if (bucket != null) {
				count += bucket.size();
			}
		}
		return count;
	}

	private int countExcludedPostingCost(PackedBitSet allowed) {
		int count = 0;
		for (int bit = 0; bit < nodesByJob.size(); bit++) {
			if (allowed.contains(bit)) {
				continue;
			}
			LinkedHashSet<PaperDominanceNode> posting = nodesByJob.get(bit);
			if (posting != null) {
				count += posting.size();
			}
		}
		return count;
	}

	private void markNodesContainingExcludedJobs(PackedBitSet allowed, long excludedMark) {
		for (int bit = 0; bit < nodesByJob.size(); bit++) {
			if (allowed.contains(bit)) {
				continue;
			}
			LinkedHashSet<PaperDominanceNode> posting = nodesByJob.get(bit);
			if (posting == null) {
				continue;
			}
			for (PaperDominanceNode node : posting) {
				if (node.active) {
					node.indexMark = excludedMark;
				}
			}
		}
	}

	private boolean shouldUseSupersetIndex(PackedBitSet target) {
		if (!USE_CONTAINMENT_INDEX) {
			return false;
		}
		int seedSize = estimateSupersetSeedSize(target);
		if (seedSize < 0) {
			return false;
		}
		return seedSize < roots.size();
	}

	private int estimateSupersetSeedSize(PackedBitSet target) {
		LinkedHashSet<PaperDominanceNode> seed = null;
		for (int bit = target.nextSetBit(0); bit >= 0; bit = target.nextSetBit(bit + 1)) {
			LinkedHashSet<PaperDominanceNode> posting = bit < nodesByJob.size() ? nodesByJob.get(bit) : null;
			if (posting == null || posting.isEmpty()) {
				return 0;
			}
			if (seed == null || posting.size() < seed.size()) {
				seed = posting;
			}
		}
		return seed == null ? -1 : seed.size();
	}

	private boolean shouldUseSupersetSetTrie(int targetCardinality) {
		return USE_SET_TRIE && targetCardinality >= SET_TRIE_SUPERSET_MIN_CARDINALITY;
	}

	private boolean shouldUseSubsetIndex(PackedBitSet newKey, int newCardinality, int dfsStartCount) {
		if (!USE_CONTAINMENT_INDEX) {
			return false;
		}
		int cardinalityCandidates = countCardinalityCandidates(newCardinality);
		int excludedPostingCost = countExcludedPostingCost(newKey);
		int estimate = cardinalityCandidates + Math.min(cardinalityCandidates, excludedPostingCost);
		return estimate < dfsStartCount;
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
		invalidateSupersetCache();
		nodesDeleted++;
		labelsDeletedByPropagation += node.labels.size();
		for (Label label : node.labels) {
			label.isDominated = true;
		}
		nodeByReachableSet.remove(node.reachableKey);
		if (USE_CONTAINMENT_INDEX) {
			removeNodeFromIndexes(node);
		}
		if (USE_SET_TRIE) {
			removeNodeFromSetTrie(node);
		}
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

	private void addNodeToIndexes(PaperDominanceNode node) {
		ensureBucket(nodesByCardinality, node.reachableCardinality).add(node);
		for (int bit = node.reachableKey.nextSetBit(0); bit >= 0; bit = node.reachableKey.nextSetBit(bit + 1)) {
			ensureBucket(nodesByJob, bit).add(node);
		}
	}

	private void removeNodeFromIndexes(PaperDominanceNode node) {
		if (node.reachableCardinality < nodesByCardinality.size()) {
			LinkedHashSet<PaperDominanceNode> bucket = nodesByCardinality.get(node.reachableCardinality);
			if (bucket != null) {
				bucket.remove(node);
			}
		}
		for (int bit = node.reachableKey.nextSetBit(0); bit >= 0; bit = node.reachableKey.nextSetBit(bit + 1)) {
			if (bit < nodesByJob.size()) {
				LinkedHashSet<PaperDominanceNode> posting = nodesByJob.get(bit);
				if (posting != null) {
					posting.remove(node);
				}
			}
		}
	}

	private LinkedHashSet<PaperDominanceNode> ensureBucket(
			ArrayList<LinkedHashSet<PaperDominanceNode>> buckets, int index) {
		while (buckets.size() <= index) {
			buckets.add(null);
		}
		LinkedHashSet<PaperDominanceNode> bucket = buckets.get(index);
		if (bucket == null) {
			bucket = new LinkedHashSet<PaperDominanceNode>();
			buckets.set(index, bucket);
		}
		return bucket;
	}

	private void addNodeToSetTrie(PaperDominanceNode node) {
		SetTrieNode trieNode = setTrieRoot;
		for (int bit = node.reachableKey.nextSetBit(0); bit >= 0; bit = node.reachableKey.nextSetBit(bit + 1)) {
			trieNode = trieNode.child(bit, true);
		}
		trieNode.terminal = node;
	}

	private void removeNodeFromSetTrie(PaperDominanceNode node) {
		SetTrieNode trieNode = setTrieRoot;
		for (int bit = node.reachableKey.nextSetBit(0); bit >= 0; bit = node.reachableKey.nextSetBit(bit + 1)) {
			trieNode = trieNode.child(bit, false);
			if (trieNode == null) {
				return;
			}
		}
		if (trieNode.terminal == node) {
			trieNode.terminal = null;
		}
	}

	private int[] toBits(PackedBitSet set) {
		int[] bits = new int[set.cardinality()];
		int idx = 0;
		for (int bit = set.nextSetBit(0); bit >= 0; bit = set.nextSetBit(bit + 1)) {
			bits[idx++] = bit;
		}
		return bits;
	}

	/**
	 * 2026-05-25: single-point 查询只需要 terminal-superset 叶子上的最优 Tmid 点值；
	 * 这里直接在 DFS 里累计最优值，避免先构造候选列表、再做第二遍扫描。
	 */
	private double bestTerminalSupersetPointDominanceValue(PackedBitSet target, int targetCardinality,
			double pointTime) {
		double best = Utility.big_M;
		for (PaperDominanceNode node : findTerminalSupersetNodes(target, targetCardinality)) {
			double candidateValue = pointDominanceValue(node.dominanceEnvelope, pointTime);
			if (Utility.compareLt(candidateValue, best)) {
				best = candidateValue;
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
		boolean changed = false;
		if (!from.successors.contains(to)) {
			from.successors.add(to);
			changed = true;
		}
		if (!to.predecessors.contains(from)) {
			to.predecessors.add(from);
			changed = true;
		}
		if (changed) {
			invalidateSupersetCache();
		}
	}

	private void disconnect(PaperDominanceNode from, PaperDominanceNode to) {
		boolean changed = from.successors.remove(to);
		changed = to.predecessors.remove(from) || changed;
		if (changed) {
			invalidateSupersetCache();
		}
	}

	private void invalidateSupersetCache() {
		if (!USE_SUPERSET_CACHE) {
			return;
		}
		structureVersion++;
		terminalSupersetCache.clear();
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
		final ArrayList<PaperDominanceNode> predecessors = new ArrayList<PaperDominanceNode>();
		final ArrayList<PaperDominanceNode> successors = new ArrayList<PaperDominanceNode>();
		final Direction direction;
		PiecewiseLinearFunction labelEnvelope;
		PiecewiseLinearFunction predecessorEnvelope;
		PiecewiseLinearFunction dominanceEnvelope;
		boolean active = true;
		long indexMark;
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

		boolean removeLabelsDominatedByPredecessors() {
			if (predecessorEnvelope == null || predecessorEnvelope.head == null) {
				return false;
			}
			boolean changed = false;
			for (int i = labels.size() - 1; i >= 0; i--) {
				Label label = labels.get(i);
				dominanceChecks++;
				if (canCoverDomain(predecessorEnvelope, label.frontier, direction)
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

	private static final class SupersetCacheEntry {
		final long structureVersion;
		final ArrayList<PaperDominanceNode> nodes;

		SupersetCacheEntry(long structureVersion, ArrayList<PaperDominanceNode> nodes) {
			this.structureVersion = structureVersion;
			this.nodes = new ArrayList<PaperDominanceNode>(nodes);
		}
	}

	private static final class SetTrieNode {
		final int bit;
		final ArrayList<SetTrieNode> children = new ArrayList<SetTrieNode>();
		PaperDominanceNode terminal;

		SetTrieNode(int bit) {
			this.bit = bit;
		}

		SetTrieNode child(int childBit, boolean create) {
			int low = 0;
			int high = children.size() - 1;
			while (low <= high) {
				int mid = (low + high) >>> 1;
				SetTrieNode child = children.get(mid);
				if (child.bit == childBit) {
					return child;
				}
				if (child.bit < childBit) {
					low = mid + 1;
				} else {
					high = mid - 1;
				}
			}
			if (!create) {
				return null;
			}
			SetTrieNode child = new SetTrieNode(childBit);
			children.add(low, child);
			return child;
		}
	}
}
