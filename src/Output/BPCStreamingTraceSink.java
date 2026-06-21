package Output;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import TWETBPC.TWETSolveResult;
import TWETBPC.BP.BranchResult;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * 长算例运行中的 BPC 事件落盘器。
 *
 * 2026-06-21: 对照测试原来只在 case 结束后一次性写 log，长时间卡在 root pricing
 * 时无法判断阶段。这个 sink 每条事件立即 flush 到文件，便于中途中断或 tail 查看。
 */
public final class BPCStreamingTraceSink implements BPCTraceSink, AutoCloseable {

	private final BufferedWriter writer;

	public BPCStreamingTraceSink(Path path) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to open live BPC trace log: " + path, ex);
		}
	}

	@Override
	public void onSolveStarted(String instanceName) {
		write("Start instance: " + instanceName);
	}

	@Override
	public void onInitialColumnsReady(int initialColumnCount, int incumbentColumnCount, double initialIncumbentCost) {
		write(BPCOutputFormatters.formatInitialColumns(initialColumnCount, incumbentColumnCount, initialIncumbentCost));
	}

	@Override
	public void onNodePicked(Node node, int queueSize, int poolSize, int cutPoolSize) {
		write(BPCOutputFormatters.formatNodeHeader(node, queueSize, poolSize, cutPoolSize));
	}

	@Override
	public void onMasterSolved(Node node, TWETMasterSolution solution, int restrictedColumnCount, int activeCutCount,
			double bestBound, double incumbentCost, int queueSize, int poolSize, int cutPoolSize,
			boolean incumbentUpdated) {
		write(BPCOutputFormatters.formatNodeSummary(node, solution, incumbentUpdated, incumbentCost, bestBound,
				queueSize, restrictedColumnCount, activeCutCount, poolSize, cutPoolSize));
	}

	@Override
	public void onMasterLpSolution(Node node, String phase, TWETMasterSolution solution, int restrictedColumnCount,
			int poolSize, long elapsedNanos) {
		write(BPCOutputFormatters.formatMasterLpSolution(node == null ? -1 : node.id, phase, solution,
				restrictedColumnCount, poolSize, elapsedNanos));
	}

	@Override
	public void onPricingCall(Node node, String engineName, boolean improved, int addedColumns, String message,
			int poolSize, long elapsedNanos) {
		write(BPCOutputFormatters.formatPricing(engineName, node.id, improved, addedColumns, poolSize, message,
				elapsedNanos));
	}

	@Override
	public void onCutCall(Node node, String generatorName, boolean separated, int addedCuts, String message,
			int cutPoolSize, long elapsedNanos) {
		write(BPCOutputFormatters.formatCut(generatorName, node.id, separated, addedCuts, cutPoolSize, message,
				elapsedNanos));
	}

	@Override
	public void onRestrictedMasterIntegerHeuristic(Node node, boolean feasible, boolean improved, double objective,
			int selectedColumns, String message, long elapsedNanos) {
		write(BPCOutputFormatters.formatRestrictedMasterIntegerHeuristic(node.id, feasible, improved, objective,
				selectedColumns, message, elapsedNanos));
	}

	@Override
	public void onCompletionBoundSubtreeArcElimination(Node node, boolean applied, long candidates, long fixed,
			long domainFixed, long scalarFixed, long unavailable, long functionEvaluations, double gap,
			String message, long elapsedNanos) {
		write(BPCOutputFormatters.formatCompletionBoundSubtreeArcElimination(node.id, applied, candidates, fixed,
				domainFixed, scalarFixed, unavailable, functionEvaluations, gap, message, elapsedNanos));
	}

	@Override
	public void onIncumbentUpdated(Node node, TWETMasterSolution solution, double incumbentCost) {
		write(BPCOutputFormatters.formatIncumbentUpdate(node.id, incumbentCost));
	}

	@Override
	public void onNodeClosed(Node node, String reason, int queueSizeAfterClose) {
		write(BPCOutputFormatters.formatNodeClosed(node.id, reason, queueSizeAfterClose));
	}

	@Override
	public void onBranch(Node node, String brancherName, BranchResult result, int queueSize) {
		write(BPCOutputFormatters.formatBranch(brancherName, node.id, true, result.getMessage(), queueSize));
	}

	@Override
	public void onBranchRejected(Node node, String brancherName, String message) {
		write(BPCOutputFormatters.formatBranch(brancherName, node.id, false, message, -1));
	}

	@Override
	public void onStageHeartbeat(Node node, String phase, int poolSize, int cutPoolSize) {
		write(BPCOutputFormatters.formatStageHeartbeat(node == null ? -1 : node.id, phase, poolSize, cutPoolSize));
	}

	@Override
	public void onSolveFinished(TWETSolveResult result, double solveSeconds) {
		write("Solve finished status=" + result.getStatus() + " incumbent=" + result.getIncumbentCost()
				+ " bound=" + result.getBestBound() + " time=" + solveSeconds + "s");
		close();
	}

	@Override
	public void close() {
		try {
			writer.close();
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to close live BPC trace log", ex);
		}
	}

	private void write(String line) {
		try {
			writer.write(line);
			writer.newLine();
			writer.flush();
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to write live BPC trace log", ex);
		}
	}

}
