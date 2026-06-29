package Output;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETSolveResult;
import TWETBPC.BP.BranchResult;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * BPC 过程摘要收集器。
 * <p>
 * 这既是结果导出的数据载体，也是运行时的统计汇总器。
 */
public class BPCTraceSummary implements BPCTraceSink {

	private final boolean printNodeProgressSummary;
	private String instanceName;
	private long solveStartNano;
	private int initialColumnCount;
	private int initialIncumbentColumnCount;
	private double initialIncumbentCost = Double.POSITIVE_INFINITY;
	private int processedNodes;
	private int integerNodeCount;
	private int pricingRounds;
	private int cutRounds;
	private int generatedColumns;
	private int generatedCuts;
	private int branchCalls;
	private int incumbentUpdates;
	private int restrictedIntegerHeuristicCalls;
	private int restrictedIntegerHeuristicFeasibleCount;
	private int restrictedIntegerHeuristicImproveCount;
	private long restrictedIntegerHeuristicTimeNanos;
	private int completionBoundSubtreeArcEliminationCalls;
	private long completionBoundSubtreeArcEliminationFixed;
	private long completionBoundSubtreeArcEliminationApplied;
	private long completionBoundSubtreeArcEliminationTimeNanos;
	private int prunedByIncumbentCount;
	private int prunedByDualBoundCount;
	private int closedWithoutBranchCount;
	private int queuePeak;
	private int remainingQueueSize;
	private int maxPoolSize;
	private int maxCutPoolSize;
	private double rootBound = Double.POSITIVE_INFINITY;
	private double rootSolveTimeSeconds;
	private double solveTimeSeconds;
	private final ArrayList<BPCNodeRecord> nodeRecords = new ArrayList<BPCNodeRecord>();
	private final ArrayList<String> eventLines = new ArrayList<String>();
	private final ArrayList<String> runConfigurationLines = new ArrayList<String>();
	private final LinkedHashMap<String, Integer> pricingCallCount = new LinkedHashMap<String, Integer>();
	private final LinkedHashMap<String, Integer> pricingSuccessCount = new LinkedHashMap<String, Integer>();
	private final LinkedHashMap<String, Integer> pricingColumnCount = new LinkedHashMap<String, Integer>();
	private final LinkedHashMap<String, Long> pricingTimeNanos = new LinkedHashMap<String, Long>();
	private final LinkedHashMap<String, Integer> masterLpCallCount = new LinkedHashMap<String, Integer>();
	private final LinkedHashMap<String, Long> masterLpTimeNanos = new LinkedHashMap<String, Long>();
	private final LinkedHashMap<String, Integer> cutCallCount = new LinkedHashMap<String, Integer>();
	private final LinkedHashMap<String, Integer> cutSuccessCount = new LinkedHashMap<String, Integer>();
	private final LinkedHashMap<String, Integer> cutCountByGenerator = new LinkedHashMap<String, Integer>();
	private final LinkedHashMap<String, Long> cutTimeNanos = new LinkedHashMap<String, Long>();
	private final LinkedHashMap<String, Integer> branchAttemptCount = new LinkedHashMap<String, Integer>();
	private final LinkedHashMap<String, Integer> branchSuccessCount = new LinkedHashMap<String, Integer>();
	private String note;
	private NodeProgress currentNodeProgress;

	public BPCTraceSummary() {
		this(null);
	}

	public BPCTraceSummary(TWETBPCConfig config) {
		this.printNodeProgressSummary = config != null && config.diagnosticNodeProgressSummary;
	}

	@Override
	public void onSolveStarted(String instanceName) {
		this.instanceName = instanceName;
		this.solveStartNano = System.nanoTime();
		eventLines.add("Start instance: " + instanceName);
	}

	@Override
	public void onRunConfiguration(List<String> lines) {
		runConfigurationLines.clear();
		runConfigurationLines.addAll(lines);
		eventLines.add("Run configuration:");
		for (String line : lines) {
			eventLines.add("  " + line);
		}
	}

	@Override
	public void onInitialColumnsReady(int initialColumnCount, int incumbentColumnCount, double initialIncumbentCost) {
		this.initialColumnCount = initialColumnCount;
		this.initialIncumbentColumnCount = incumbentColumnCount;
		this.initialIncumbentCost = initialIncumbentCost;
		eventLines.add(BPCOutputFormatters.formatInitialColumns(initialColumnCount, incumbentColumnCount,
				initialIncumbentCost));
	}

	@Override
	public void onNodePicked(Node node, int queueSize, int poolSize, int cutPoolSize) {
		// 2026-05-19: pseudoCost 预剪枝节点不会进入 onMasterSolved，也应计入已弹出处理的节点数。
		processedNodes = Math.max(processedNodes, node.id);
		queuePeak = Math.max(queuePeak, queueSize);
		maxPoolSize = Math.max(maxPoolSize, poolSize);
		maxCutPoolSize = Math.max(maxCutPoolSize, cutPoolSize);
		currentNodeProgress = new NodeProgress(node.id, node.depth, node.pseudoCost, poolSize, cutPoolSize);
		eventLines.add(BPCOutputFormatters.formatNodeHeader(node, queueSize, poolSize, cutPoolSize));
	}

	@Override
	public void onMasterSolved(Node node, TWETMasterSolution solution, int restrictedColumnCount, int activeCutCount,
			double bestBound, double incumbentCost, int queueSize, int poolSize, int cutPoolSize,
			boolean incumbentUpdated) {
		processedNodes = Math.max(processedNodes, node.id);
		if (solution.isInteger()) {
			integerNodeCount++;
		}
		maxPoolSize = Math.max(maxPoolSize, poolSize);
		maxCutPoolSize = Math.max(maxCutPoolSize, cutPoolSize);
		if (node.id == 1 && Double.isInfinite(rootBound)) {
			rootBound = solution.getObjectiveValue();
			rootSolveTimeSeconds = (System.nanoTime() - solveStartNano) / 1_000_000_000.0;
		}
		double gap = BPCOutputFormatters.gapPercent(bestBound, incumbentCost);
		if (currentNodeProgress != null && currentNodeProgress.nodeId == node.id) {
			currentNodeProgress.lpObjective = solution.getObjectiveValue();
			currentNodeProgress.bestBound = bestBound;
			currentNodeProgress.incumbentCost = incumbentCost;
			currentNodeProgress.gapPercent = gap;
			currentNodeProgress.queueSize = queueSize;
			currentNodeProgress.restrictedColumns = restrictedColumnCount;
			currentNodeProgress.activeCuts = activeCutCount;
			currentNodeProgress.poolSize = poolSize;
			currentNodeProgress.cutPoolSize = cutPoolSize;
			currentNodeProgress.masterStatus = solution.getStatus().toString();
			currentNodeProgress.integerSolution = solution.isInteger();
			currentNodeProgress.incumbentUpdated = incumbentUpdated;
		}
		nodeRecords.add(new BPCNodeRecord(node.id, node.depth, node.pseudoCost, solution.getStatus().toString(),
				solution.getObjectiveValue(), solution.isInteger(), incumbentUpdated, incumbentCost, bestBound, gap,
				queueSize, restrictedColumnCount, activeCutCount, poolSize, cutPoolSize, solution.getMessage()));
		eventLines.add(BPCOutputFormatters.formatNodeSummary(node, solution, incumbentUpdated, incumbentCost, bestBound,
				queueSize, restrictedColumnCount, activeCutCount, poolSize, cutPoolSize));
	}

	@Override
	public void onPricingCall(Node node, String engineName, boolean improved, int addedColumns, String message,
			int poolSize, long elapsedNanos) {
		pricingRounds++;
		increment(pricingCallCount, engineName, 1);
		increment(pricingTimeNanos, engineName, elapsedNanos);
		if (improved) {
			generatedColumns += addedColumns;
			increment(pricingSuccessCount, engineName, 1);
			increment(pricingColumnCount, engineName, addedColumns);
		}
		if (currentNodeProgress != null && currentNodeProgress.nodeId == node.id) {
			currentNodeProgress.pricingCalls++;
			currentNodeProgress.pricingTimeNanos += elapsedNanos;
			currentNodeProgress.pricingAddedColumns += addedColumns;
			if (isHeuristicPricingEngine(engineName)) {
				currentNodeProgress.heuristicPricingCalls++;
				currentNodeProgress.heuristicPricingTimeNanos += elapsedNanos;
				currentNodeProgress.heuristicPricingAddedColumns += addedColumns;
			} else {
				currentNodeProgress.exactPricingCalls++;
				currentNodeProgress.exactPricingTimeNanos += elapsedNanos;
				currentNodeProgress.exactPricingAddedColumns += addedColumns;
				currentNodeProgress.lastExactPricingStats = compactPricingStats(message);
			}
			currentNodeProgress.poolSize = poolSize;
		}
		maxPoolSize = Math.max(maxPoolSize, poolSize);
		eventLines.add(BPCOutputFormatters.formatPricing(engineName, node.id, improved, addedColumns, poolSize, message,
				elapsedNanos));
	}

	@Override
	public void onCutCall(Node node, String generatorName, boolean separated, int addedCuts, String message,
			int cutPoolSize, long elapsedNanos) {
		cutRounds++;
		increment(cutCallCount, generatorName, 1);
		increment(cutTimeNanos, generatorName, elapsedNanos);
		if (separated) {
			generatedCuts += addedCuts;
			increment(cutSuccessCount, generatorName, 1);
			increment(cutCountByGenerator, generatorName, addedCuts);
		}
		maxCutPoolSize = Math.max(maxCutPoolSize, cutPoolSize);
		eventLines.add(BPCOutputFormatters.formatCut(generatorName, node.id, separated, addedCuts, cutPoolSize, message,
				elapsedNanos));
	}

	@Override
	public void onMasterLpSolve(Node node, String phase, long elapsedNanos) {
		increment(masterLpCallCount, phase, 1);
		increment(masterLpTimeNanos, phase, elapsedNanos);
		if (currentNodeProgress != null && currentNodeProgress.nodeId == node.id) {
			currentNodeProgress.masterLpCalls++;
			currentNodeProgress.masterLpTimeNanos += elapsedNanos;
		}
	}

	@Override
	public void onRestrictedMasterIntegerHeuristic(Node node, boolean feasible, boolean improved, double objective,
			int selectedColumns, String message, long elapsedNanos) {
		restrictedIntegerHeuristicCalls++;
		restrictedIntegerHeuristicTimeNanos += elapsedNanos;
		if (feasible) {
			restrictedIntegerHeuristicFeasibleCount++;
		}
		if (improved) {
			restrictedIntegerHeuristicImproveCount++;
		}
		if (currentNodeProgress != null && currentNodeProgress.nodeId == node.id) {
			currentNodeProgress.rmihCalls++;
			currentNodeProgress.rmihTimeNanos += elapsedNanos;
			currentNodeProgress.rmihFeasible = feasible;
			currentNodeProgress.rmihImproved = improved;
			currentNodeProgress.rmihObjective = objective;
			currentNodeProgress.rmihSelectedColumns = selectedColumns;
		}
		eventLines.add(BPCOutputFormatters.formatRestrictedMasterIntegerHeuristic(node.id, feasible, improved,
				objective, selectedColumns, message, elapsedNanos));
	}

	@Override
	public void onCompletionBoundSubtreeArcElimination(Node node, boolean applied, long candidates, long fixed,
			long domainFixed, long scalarFixed, long unavailable, long functionEvaluations, double gap,
			String message, long elapsedNanos) {
		completionBoundSubtreeArcEliminationCalls++;
		completionBoundSubtreeArcEliminationFixed += fixed;
		if (applied) {
			completionBoundSubtreeArcEliminationApplied += fixed;
		}
		completionBoundSubtreeArcEliminationTimeNanos += elapsedNanos;
		if (currentNodeProgress != null && currentNodeProgress.nodeId == node.id) {
			currentNodeProgress.subtreeCalls++;
			currentNodeProgress.subtreeApplied = applied;
			currentNodeProgress.subtreeCandidates += candidates;
			currentNodeProgress.subtreeFixed += fixed;
			currentNodeProgress.subtreeFunctionEvaluations += functionEvaluations;
			currentNodeProgress.subtreeTimeNanos += elapsedNanos;
		}
		eventLines.add(BPCOutputFormatters.formatCompletionBoundSubtreeArcElimination(node.id, applied, candidates,
				fixed, domainFixed, scalarFixed, unavailable, functionEvaluations, gap, message, elapsedNanos));
	}

	@Override
	public void onIncumbentUpdated(Node node, TWETMasterSolution solution, double incumbentCost) {
		incumbentUpdates++;
		eventLines.add(BPCOutputFormatters.formatIncumbentUpdate(node.id, incumbentCost));
	}

	@Override
	public void onNodeClosed(Node node, String reason, int queueSizeAfterClose) {
		remainingQueueSize = queueSizeAfterClose;
		if ("pruned_by_incumbent".equals(reason)) {
			prunedByIncumbentCount++;
		} else if ("pruned_by_dual_bound".equals(reason)) {
			prunedByDualBoundCount++;
		} else if ("closed_without_branch".equals(reason)) {
			closedWithoutBranchCount++;
		}
		eventLines.add(BPCOutputFormatters.formatNodeClosed(node.id, reason, queueSizeAfterClose));
		emitNodeProgress(node, reason, queueSizeAfterClose);
	}

	@Override
	public void onBranch(Node node, String brancherName, BranchResult result, int queueSize) {
		branchCalls++;
		remainingQueueSize = queueSize;
		queuePeak = Math.max(queuePeak, queueSize);
		increment(branchAttemptCount, brancherName, 1);
		increment(branchSuccessCount, brancherName, 1);
		eventLines.add(BPCOutputFormatters.formatBranch(brancherName, node.id, true, result.getMessage(), queueSize));
		emitNodeProgress(node, "branched_by_" + brancherName, queueSize);
	}

	@Override
	public void onBranchRejected(Node node, String brancherName, String message) {
		increment(branchAttemptCount, brancherName, 1);
		eventLines.add(BPCOutputFormatters.formatBranch(brancherName, node.id, false, message, -1));
	}

	@Override
	public void onStageHeartbeat(Node node, String phase, int poolSize, int cutPoolSize) {
		eventLines.add(BPCOutputFormatters.formatStageHeartbeat(node == null ? -1 : node.id, phase, poolSize,
				cutPoolSize));
	}

	@Override
	public void onSolveFinished(TWETSolveResult result, double solveSeconds) {
		this.solveTimeSeconds = solveSeconds;
		eventLines.add(BPCOutputFormatters.formatFinalSummary(this, result));
	}

	private void increment(LinkedHashMap<String, Integer> counter, String key, int delta) {
		Integer value = counter.get(key);
		counter.put(key, Integer.valueOf((value == null ? 0 : value.intValue()) + delta));
	}

	private void increment(LinkedHashMap<String, Long> counter, String key, long delta) {
		Long value = counter.get(key);
		counter.put(key, Long.valueOf((value == null ? 0L : value.longValue()) + delta));
	}

	private boolean isHeuristicPricingEngine(String engineName) {
		return engineName != null && engineName.toLowerCase(Locale.US).contains("heuristic");
	}

	private void emitNodeProgress(Node node, String outcome, int queueSize) {
		if (!printNodeProgressSummary || currentNodeProgress == null || node == null
				|| currentNodeProgress.nodeId != node.id || currentNodeProgress.emitted) {
			return;
		}
		currentNodeProgress.emitted = true;
		currentNodeProgress.queueSize = queueSize;
		String line = formatNodeProgress(currentNodeProgress, outcome);
		System.out.println(line);
		System.out.flush();
		eventLines.add(line);
	}

	private String formatNodeProgress(NodeProgress progress, String outcome) {
		double nodeSeconds = (System.nanoTime() - progress.startNanos) / 1_000_000_000.0;
		double totalSeconds = (System.nanoTime() - solveStartNano) / 1_000_000_000.0;
		StringBuilder builder = new StringBuilder();
		builder.append(String.format(Locale.US,
				"[BPC node summary] node=%d depth=%d outcome=%s nodeTime=%.3fs total=%.3fs lpObj=%.6f status=%s integer=%s inc=%.6f bound=%.6f gap=%.4f%% queue=%d pool=%d cutPool=%d restricted=%d cuts=%d",
				progress.nodeId, progress.depth, outcome, nodeSeconds, totalSeconds, progress.lpObjective,
				progress.masterStatus, Boolean.toString(progress.integerSolution), progress.incumbentCost,
				progress.bestBound, progress.gapPercent, progress.queueSize, progress.poolSize,
				progress.cutPoolSize, progress.restrictedColumns, progress.activeCuts));
		builder.append(String.format(Locale.US,
				" lp=%.3fs/%d pricing=%.3fs/%d/add%d heur=%.3fs/%d/add%d exact=%.3fs/%d/add%d",
				toSeconds(progress.masterLpTimeNanos), progress.masterLpCalls,
				toSeconds(progress.pricingTimeNanos), progress.pricingCalls, progress.pricingAddedColumns,
				toSeconds(progress.heuristicPricingTimeNanos), progress.heuristicPricingCalls,
				progress.heuristicPricingAddedColumns, toSeconds(progress.exactPricingTimeNanos),
				progress.exactPricingCalls, progress.exactPricingAddedColumns));
		if (progress.rmihCalls > 0) {
			builder.append(String.format(Locale.US,
					" rmih=%.3fs/%d feasible=%s improved=%s obj=%.6f cols=%d",
					toSeconds(progress.rmihTimeNanos), progress.rmihCalls,
					Boolean.toString(progress.rmihFeasible), Boolean.toString(progress.rmihImproved),
					progress.rmihObjective, progress.rmihSelectedColumns));
		}
		if (progress.subtreeCalls > 0) {
			builder.append(String.format(Locale.US,
					" subtree=%.3fs/%d applied=%s cand=%d fixed=%d funcEval=%d",
					toSeconds(progress.subtreeTimeNanos), progress.subtreeCalls,
					Boolean.toString(progress.subtreeApplied), progress.subtreeCandidates,
					progress.subtreeFixed, progress.subtreeFunctionEvaluations));
		}
		if (progress.lastExactPricingStats != null && !progress.lastExactPricingStats.isEmpty()) {
			builder.append(" exactStats={").append(progress.lastExactPricingStats).append("}");
		}
		return builder.toString();
	}

	private String compactPricingStats(String message) {
		if (message == null || message.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		appendStat(builder, message, "labels fw kept/dominated=");
		appendStat(builder, message, "bw kept/dominated=");
		appendStat(builder, message, "forwardSink visited/negative=");
		appendStat(builder, message, "candidatePool kept/seen/dropped=");
		appendStat(builder, message, "completionBound mode/cutoff/buildMs/eval/fwPruned/bwPruned=");
		appendStat(builder, message, "completionBoundInternal counts fCand/bCand/fPop/bPop/stale/merge/changed=");
		return builder.toString();
	}

	private void appendStat(StringBuilder builder, String message, String key) {
		int start = message.indexOf(key);
		if (start < 0) {
			return;
		}
		int end = message.indexOf(", ", start);
		if (end < 0) {
			end = message.length();
		}
		if (builder.length() > 0) {
			builder.append("; ");
		}
		builder.append(message.substring(start, end));
	}

	private double toSeconds(long nanos) {
		return nanos / 1_000_000_000.0;
	}

	private static final class NodeProgress {
		final int nodeId;
		final int depth;
		final double pseudoCost;
		final long startNanos;
		double lpObjective = Double.NaN;
		double incumbentCost = Double.POSITIVE_INFINITY;
		double bestBound = Double.POSITIVE_INFINITY;
		double gapPercent = Double.NaN;
		String masterStatus = "-";
		boolean integerSolution;
		boolean incumbentUpdated;
		int queueSize;
		int poolSize;
		int cutPoolSize;
		int restrictedColumns;
		int activeCuts;
		int pricingCalls;
		int pricingAddedColumns;
		long pricingTimeNanos;
		int heuristicPricingCalls;
		int heuristicPricingAddedColumns;
		long heuristicPricingTimeNanos;
		int exactPricingCalls;
		int exactPricingAddedColumns;
		long exactPricingTimeNanos;
		int masterLpCalls;
		long masterLpTimeNanos;
		int rmihCalls;
		long rmihTimeNanos;
		boolean rmihFeasible;
		boolean rmihImproved;
		double rmihObjective = Double.NaN;
		int rmihSelectedColumns;
		int subtreeCalls;
		boolean subtreeApplied;
		long subtreeCandidates;
		long subtreeFixed;
		long subtreeFunctionEvaluations;
		long subtreeTimeNanos;
		String lastExactPricingStats = "";
		boolean emitted;

		NodeProgress(int nodeId, int depth, double pseudoCost, int poolSize, int cutPoolSize) {
			this.nodeId = nodeId;
			this.depth = depth;
			this.pseudoCost = pseudoCost;
			this.poolSize = poolSize;
			this.cutPoolSize = cutPoolSize;
			this.startNanos = System.nanoTime();
		}
	}

	public String getInstanceName() {
		return instanceName;
	}

	public int getInitialColumnCount() {
		return initialColumnCount;
	}

	public int getInitialIncumbentColumnCount() {
		return initialIncumbentColumnCount;
	}

	public double getInitialIncumbentCost() {
		return initialIncumbentCost;
	}

	public int getProcessedNodes() {
		return processedNodes;
	}

	public int getIntegerNodeCount() {
		return integerNodeCount;
	}

	public int getPricingRounds() {
		return pricingRounds;
	}

	public int getCutRounds() {
		return cutRounds;
	}

	public int getGeneratedColumns() {
		return generatedColumns;
	}

	public int getGeneratedCuts() {
		return generatedCuts;
	}

	public int getBranchCalls() {
		return branchCalls;
	}

	public int getIncumbentUpdates() {
		return incumbentUpdates;
	}

	public int getRestrictedIntegerHeuristicCalls() {
		return restrictedIntegerHeuristicCalls;
	}

	public int getRestrictedIntegerHeuristicFeasibleCount() {
		return restrictedIntegerHeuristicFeasibleCount;
	}

	public int getRestrictedIntegerHeuristicImproveCount() {
		return restrictedIntegerHeuristicImproveCount;
	}

	public long getRestrictedIntegerHeuristicTimeNanos() {
		return restrictedIntegerHeuristicTimeNanos;
	}

	public int getCompletionBoundSubtreeArcEliminationCalls() {
		return completionBoundSubtreeArcEliminationCalls;
	}

	public long getCompletionBoundSubtreeArcEliminationFixed() {
		return completionBoundSubtreeArcEliminationFixed;
	}

	public long getCompletionBoundSubtreeArcEliminationApplied() {
		return completionBoundSubtreeArcEliminationApplied;
	}

	public long getCompletionBoundSubtreeArcEliminationTimeNanos() {
		return completionBoundSubtreeArcEliminationTimeNanos;
	}

	public int getPrunedByIncumbentCount() {
		return prunedByIncumbentCount;
	}

	public int getPrunedByDualBoundCount() {
		return prunedByDualBoundCount;
	}

	public int getClosedWithoutBranchCount() {
		return closedWithoutBranchCount;
	}

	public int getQueuePeak() {
		return queuePeak;
	}

	public int getRemainingQueueSize() {
		return remainingQueueSize;
	}

	public int getMaxPoolSize() {
		return maxPoolSize;
	}

	public int getMaxCutPoolSize() {
		return maxCutPoolSize;
	}

	public double getRootBound() {
		return rootBound;
	}

	public double getRootSolveTimeSeconds() {
		return rootSolveTimeSeconds;
	}

	public double getSolveTimeSeconds() {
		return solveTimeSeconds;
	}

	public List<BPCNodeRecord> getNodeRecords() {
		return Collections.unmodifiableList(nodeRecords);
	}

	public List<String> getEventLines() {
		return Collections.unmodifiableList(eventLines);
	}

	public List<String> getRunConfigurationLines() {
		return Collections.unmodifiableList(runConfigurationLines);
	}

	public Map<String, Integer> getPricingCallCount() {
		return Collections.unmodifiableMap(pricingCallCount);
	}

	public Map<String, Integer> getPricingSuccessCount() {
		return Collections.unmodifiableMap(pricingSuccessCount);
	}

	public Map<String, Integer> getPricingColumnCount() {
		return Collections.unmodifiableMap(pricingColumnCount);
	}

	public Map<String, Long> getPricingTimeNanos() {
		return Collections.unmodifiableMap(pricingTimeNanos);
	}

	public Map<String, Long> getMasterLpTimeNanos() {
		return Collections.unmodifiableMap(masterLpTimeNanos);
	}

	public Map<String, Integer> getMasterLpCallCount() {
		return Collections.unmodifiableMap(masterLpCallCount);
	}

	public Map<String, Integer> getCutCallCount() {
		return Collections.unmodifiableMap(cutCallCount);
	}

	public Map<String, Integer> getCutSuccessCount() {
		return Collections.unmodifiableMap(cutSuccessCount);
	}

	public Map<String, Integer> getCutCountByGenerator() {
		return Collections.unmodifiableMap(cutCountByGenerator);
	}

	public Map<String, Long> getCutTimeNanos() {
		return Collections.unmodifiableMap(cutTimeNanos);
	}

	public Map<String, Integer> getBranchAttemptCount() {
		return Collections.unmodifiableMap(branchAttemptCount);
	}

	public Map<String, Integer> getBranchSuccessCount() {
		return Collections.unmodifiableMap(branchSuccessCount);
	}

	public String getNote() {
		return note;
	}

	public void setNote(String note) {
		this.note = note;
	}

}
