package Output;

import java.util.ArrayList;
import java.util.List;

import TWETBPC.TWETSolveResult;
import TWETBPC.BP.BranchResult;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * BPC trace 多播器。
 */
public final class BPCCompositeTraceSink implements BPCTraceSink {

	private final ArrayList<BPCTraceSink> delegates;

	public BPCCompositeTraceSink(List<BPCTraceSink> delegates) {
		this.delegates = new ArrayList<BPCTraceSink>(delegates);
	}

	@Override
	public void onSolveStarted(String instanceName) {
		for (BPCTraceSink sink : delegates) {
			sink.onSolveStarted(instanceName);
		}
	}

	@Override
	public void onInitialColumnsReady(int initialColumnCount, int incumbentColumnCount, double initialIncumbentCost) {
		for (BPCTraceSink sink : delegates) {
			sink.onInitialColumnsReady(initialColumnCount, incumbentColumnCount, initialIncumbentCost);
		}
	}

	@Override
	public void onNodePicked(Node node, int queueSize, int poolSize, int cutPoolSize) {
		for (BPCTraceSink sink : delegates) {
			sink.onNodePicked(node, queueSize, poolSize, cutPoolSize);
		}
	}

	@Override
	public void onMasterSolved(Node node, TWETMasterSolution solution, int restrictedColumnCount, int activeCutCount,
			double bestBound, double incumbentCost, int queueSize, int poolSize, int cutPoolSize,
			boolean incumbentUpdated) {
		for (BPCTraceSink sink : delegates) {
			sink.onMasterSolved(node, solution, restrictedColumnCount, activeCutCount, bestBound, incumbentCost,
					queueSize, poolSize, cutPoolSize, incumbentUpdated);
		}
	}

	@Override
	public void onPricingCall(Node node, String engineName, boolean improved, int addedColumns, String message,
			int poolSize, long elapsedNanos) {
		for (BPCTraceSink sink : delegates) {
			sink.onPricingCall(node, engineName, improved, addedColumns, message, poolSize, elapsedNanos);
		}
	}

	@Override
	public void onCutCall(Node node, String generatorName, boolean separated, int addedCuts, String message,
			int cutPoolSize, long elapsedNanos) {
		for (BPCTraceSink sink : delegates) {
			sink.onCutCall(node, generatorName, separated, addedCuts, message, cutPoolSize, elapsedNanos);
		}
	}

	@Override
	public void onMasterLpSolve(Node node, String phase, long elapsedNanos) {
		for (BPCTraceSink sink : delegates) {
			sink.onMasterLpSolve(node, phase, elapsedNanos);
		}
	}

	@Override
	public void onMasterLpSolution(Node node, String phase, TWETMasterSolution solution, int restrictedColumnCount,
			int poolSize, long elapsedNanos) {
		for (BPCTraceSink sink : delegates) {
			sink.onMasterLpSolution(node, phase, solution, restrictedColumnCount, poolSize, elapsedNanos);
		}
	}

	@Override
	public void onRestrictedMasterIntegerHeuristic(Node node, boolean feasible, boolean improved, double objective,
			int selectedColumns, String message, long elapsedNanos) {
		for (BPCTraceSink sink : delegates) {
			sink.onRestrictedMasterIntegerHeuristic(node, feasible, improved, objective, selectedColumns, message,
					elapsedNanos);
		}
	}

	@Override
	public void onCompletionBoundSubtreeArcElimination(Node node, boolean applied, long candidates, long fixed,
			long domainFixed, long scalarFixed, long unavailable, long functionEvaluations, double gap,
			String message, long elapsedNanos) {
		for (BPCTraceSink sink : delegates) {
			sink.onCompletionBoundSubtreeArcElimination(node, applied, candidates, fixed, domainFixed, scalarFixed,
					unavailable, functionEvaluations, gap, message, elapsedNanos);
		}
	}

	@Override
	public void onIncumbentUpdated(Node node, TWETMasterSolution solution, double incumbentCost) {
		for (BPCTraceSink sink : delegates) {
			sink.onIncumbentUpdated(node, solution, incumbentCost);
		}
	}

	@Override
	public void onNodeClosed(Node node, String reason, int queueSizeAfterClose) {
		for (BPCTraceSink sink : delegates) {
			sink.onNodeClosed(node, reason, queueSizeAfterClose);
		}
	}

	@Override
	public void onBranch(Node node, String brancherName, BranchResult result, int queueSize) {
		for (BPCTraceSink sink : delegates) {
			sink.onBranch(node, brancherName, result, queueSize);
		}
	}

	@Override
	public void onBranchRejected(Node node, String brancherName, String message) {
		for (BPCTraceSink sink : delegates) {
			sink.onBranchRejected(node, brancherName, message);
		}
	}

	@Override
	public void onStageHeartbeat(Node node, String phase, int poolSize, int cutPoolSize) {
		for (BPCTraceSink sink : delegates) {
			sink.onStageHeartbeat(node, phase, poolSize, cutPoolSize);
		}
	}

	@Override
	public void onSolveFinished(TWETSolveResult result, double solveSeconds) {
		for (BPCTraceSink sink : delegates) {
			sink.onSolveFinished(result, solveSeconds);
		}
	}

}
