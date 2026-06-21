package Output;

import TWETBPC.TWETSolveResult;
import TWETBPC.BP.BranchResult;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * BPC 过程事件接口。
 * <p>
 * 2026-04-10: Tree/PC 只发事件，不直接做统计和输出拼接。
 */
public interface BPCTraceSink {

	default void onSolveStarted(String instanceName) {
	}

	default void onInitialColumnsReady(int initialColumnCount, int incumbentColumnCount, double initialIncumbentCost) {
	}

	default void onNodePicked(Node node, int queueSize, int poolSize, int cutPoolSize) {
	}

	default void onMasterSolved(Node node, TWETMasterSolution solution, int restrictedColumnCount, int activeCutCount,
			double bestBound, double incumbentCost, int queueSize, int poolSize, int cutPoolSize,
			boolean incumbentUpdated) {
	}

	default void onPricingCall(Node node, String engineName, boolean improved, int addedColumns, String message,
			int poolSize) {
	}

	default void onPricingCall(Node node, String engineName, boolean improved, int addedColumns, String message,
			int poolSize, long elapsedNanos) {
		onPricingCall(node, engineName, improved, addedColumns, message, poolSize);
	}

	default void onCutCall(Node node, String generatorName, boolean separated, int addedCuts, String message,
			int cutPoolSize) {
	}

	default void onCutCall(Node node, String generatorName, boolean separated, int addedCuts, String message,
			int cutPoolSize, long elapsedNanos) {
		onCutCall(node, generatorName, separated, addedCuts, message, cutPoolSize);
	}

	default void onMasterLpSolve(Node node, String phase, long elapsedNanos) {
	}

	default void onRestrictedMasterIntegerHeuristic(Node node, boolean feasible, boolean improved, double objective,
			int selectedColumns, String message, long elapsedNanos) {
	}

	default void onCompletionBoundSubtreeArcElimination(Node node, boolean applied, long candidates, long fixed,
			long domainFixed, long scalarFixed, long unavailable, long functionEvaluations, double gap,
			String message, long elapsedNanos) {
	}

	default void onIncumbentUpdated(Node node, TWETMasterSolution solution, double incumbentCost) {
	}

	default void onNodeClosed(Node node, String reason, int queueSizeAfterClose) {
	}

	default void onBranch(Node node, String brancherName, BranchResult result, int queueSize) {
	}

	default void onBranchRejected(Node node, String brancherName, String message) {
	}

	default void onStageHeartbeat(Node node, String phase, int poolSize, int cutPoolSize) {
	}

	default void onSolveFinished(TWETSolveResult result, double solveSeconds) {
	}

}
