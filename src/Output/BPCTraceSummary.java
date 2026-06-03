package Output;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	@Override
	public void onSolveStarted(String instanceName) {
		this.instanceName = instanceName;
		this.solveStartNano = System.nanoTime();
		eventLines.add("Start instance: " + instanceName);
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
		} else if ("closed_without_branch".equals(reason)) {
			closedWithoutBranchCount++;
		}
		eventLines.add(BPCOutputFormatters.formatNodeClosed(node.id, reason, queueSizeAfterClose));
	}

	@Override
	public void onBranch(Node node, String brancherName, BranchResult result, int queueSize) {
		branchCalls++;
		remainingQueueSize = queueSize;
		queuePeak = Math.max(queuePeak, queueSize);
		increment(branchAttemptCount, brancherName, 1);
		increment(branchSuccessCount, brancherName, 1);
		eventLines.add(BPCOutputFormatters.formatBranch(brancherName, node.id, true, result.getMessage(), queueSize));
	}

	@Override
	public void onBranchRejected(Node node, String brancherName, String message) {
		increment(branchAttemptCount, brancherName, 1);
		eventLines.add(BPCOutputFormatters.formatBranch(brancherName, node.id, false, message, -1));
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
