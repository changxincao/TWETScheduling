package Output;

import TWETBPC.TWETSolveResult;
import TWETBPC.BP.BranchResult;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * BPC 控制台输出器。
 */
public final class BPCConsoleReporter implements BPCTraceSink {

	@Override
	public void onSolveStarted(String instanceName) {
		System.out.println("\n=== Start TWET-BPC on instance: " + instanceName);
	}

	@Override
	public void onInitialColumnsReady(int initialColumnCount, int incumbentColumnCount, double initialIncumbentCost) {
		System.out.println(BPCOutputFormatters.formatInitialColumns(initialColumnCount, incumbentColumnCount,
				initialIncumbentCost));
	}

	@Override
	public void onNodePicked(Node node, int queueSize, int poolSize, int cutPoolSize) {
		System.out.println(BPCOutputFormatters.formatNodeHeader(node, queueSize, poolSize, cutPoolSize));
	}

	@Override
	public void onMasterSolved(Node node, TWETMasterSolution solution, int restrictedColumnCount, int activeCutCount,
			double bestBound, double incumbentCost, int queueSize, int poolSize, int cutPoolSize,
			boolean incumbentUpdated) {
		System.out.println(BPCOutputFormatters.formatNodeSummary(node, solution, incumbentUpdated, incumbentCost,
				bestBound, queueSize, restrictedColumnCount, activeCutCount, poolSize, cutPoolSize));
	}

	@Override
	public void onPricingCall(Node node, String engineName, boolean improved, int addedColumns, String message,
			int poolSize, long elapsedNanos) {
		System.out.println(BPCOutputFormatters.formatPricing(engineName, node.id, improved, addedColumns, poolSize,
				message, elapsedNanos));
	}

	@Override
	public void onCutCall(Node node, String generatorName, boolean separated, int addedCuts, String message,
			int cutPoolSize, long elapsedNanos) {
		System.out.println(BPCOutputFormatters.formatCut(generatorName, node.id, separated, addedCuts, cutPoolSize,
				message, elapsedNanos));
	}

	@Override
	public void onIncumbentUpdated(Node node, TWETMasterSolution solution, double incumbentCost) {
		System.out.println(BPCOutputFormatters.formatIncumbentUpdate(node.id, incumbentCost));
	}

	@Override
	public void onNodeClosed(Node node, String reason, int queueSizeAfterClose) {
		System.out.println(BPCOutputFormatters.formatNodeClosed(node.id, reason, queueSizeAfterClose));
	}

	@Override
	public void onBranch(Node node, String brancherName, BranchResult result, int queueSize) {
		System.out.println(BPCOutputFormatters.formatBranch(brancherName, node.id, true, result.getMessage(), queueSize));
	}

	@Override
	public void onBranchRejected(Node node, String brancherName, String message) {
		System.out.println(BPCOutputFormatters.formatBranch(brancherName, node.id, false, message, -1));
	}

	@Override
	public void onSolveFinished(TWETSolveResult result, double solveSeconds) {
	}

	public void printFinalSummary(BPCTraceSummary summary, TWETSolveResult result) {
		System.out.println(BPCOutputFormatters.formatFinalSummary(summary, result));
	}

}
