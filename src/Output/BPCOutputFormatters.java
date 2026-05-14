package Output;

import java.util.Locale;

import TWETBPC.TWETSolveResult;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * BPC 输出格式化工具。
 */
public final class BPCOutputFormatters {

	private BPCOutputFormatters() {
	}

	public static String formatInitialColumns(int initialColumnCount, int incumbentColumnCount, double initialCost) {
		return String.format(Locale.US, "Initial columns=%d, incumbent columns=%d, initial incumbent=%.6f",
				initialColumnCount, incumbentColumnCount, initialCost);
	}

	public static String formatNodeHeader(Node node, int queueSize, int poolSize, int cutPoolSize) {
		return String.format(Locale.US,
				"%n=== Node %d depth=%d pseudo=%.6f seed=%d activeCuts=%d queue=%d pool=%d cutPool=%d",
				node.id, node.depth, node.pseudoCost, node.seedColumnIds.size(), node.activeCutIds.size(), queueSize,
				poolSize, cutPoolSize);
	}

	public static String formatNodeSummary(Node node, TWETMasterSolution solution, boolean incumbentUpdated,
			double incumbentCost, double bestBound, int queueSize, int restrictedColumnCount, int activeCutCount,
			int poolSize, int cutPoolSize) {
		double gap = gapPercent(bestBound, incumbentCost);
		return String.format(Locale.US,
				"%d\t%.6f\t%s\t%.6f\t%.4f%%\t%d\t%d\t%d\t%d\t%d\t%s",
				node.id, solution.getObjectiveValue(), incumbentUpdated ? "true" : "false", incumbentCost, gap,
				queueSize, restrictedColumnCount, poolSize, activeCutCount, cutPoolSize, solution.getStatus());
	}

	public static String formatPricing(String engineName, int nodeId, boolean improved, int addedColumns, int poolSize,
			String message) {
		return String.format(Locale.US, "Pricing[%s] node=%d improved=%s addedColumns=%d pool=%d %s", engineName,
				nodeId, Boolean.toString(improved), addedColumns, poolSize, safeMessage(message));
	}

	public static String formatCut(String generatorName, int nodeId, boolean separated, int addedCuts, int cutPoolSize,
			String message) {
		return String.format(Locale.US, "Cuts[%s] node=%d separated=%s addedCuts=%d cutPool=%d %s", generatorName,
				nodeId, Boolean.toString(separated), addedCuts, cutPoolSize, safeMessage(message));
	}

	public static String formatIncumbentUpdate(int nodeId, double incumbentCost) {
		return String.format(Locale.US, "Incumbent updated at node=%d cost=%.6f", nodeId, incumbentCost);
	}

	public static String formatNodeClosed(int nodeId, String reason, int queueSizeAfterClose) {
		return String.format(Locale.US, "Close node=%d reason=%s queue=%d", nodeId, reason, queueSizeAfterClose);
	}

	public static String formatBranch(String brancherName, int nodeId, boolean branched, String message,
			int queueSize) {
		if (queueSize >= 0) {
			return String.format(Locale.US, "Branch[%s] node=%d branched=%s queue=%d %s", brancherName, nodeId,
					Boolean.toString(branched), queueSize, safeMessage(message));
		}
		return String.format(Locale.US, "Branch[%s] node=%d branched=%s %s", brancherName, nodeId,
				Boolean.toString(branched), safeMessage(message));
	}

	public static String formatFinalSummary(BPCTraceSummary summary, TWETSolveResult result) {
		StringBuilder builder = new StringBuilder();
		builder.append("\n=== BPC Summary ===\n");
		builder.append(String.format(Locale.US, "status=%s%n", result.getStatus()));
		builder.append(String.format(Locale.US, "initial incumbent=%.6f%n", summary.getInitialIncumbentCost()));
		builder.append(String.format(Locale.US, "root bound=%.6f%n", summary.getRootBound()));
		builder.append(String.format(Locale.US, "root solving time=%.3f s%n", summary.getRootSolveTimeSeconds()));
		builder.append(String.format(Locale.US, "final incumbent=%.6f%n", result.getIncumbentCost()));
		builder.append(String.format(Locale.US, "final lower bound=%.6f%n", result.getBestBound()));
		builder.append(
				String.format(Locale.US, "gap=%.4f%%%n", gapPercent(result.getBestBound(), result.getIncumbentCost())));
		builder.append(String.format(Locale.US, "processed nodes=%d, integer nodes=%d%n", summary.getProcessedNodes(),
				summary.getIntegerNodeCount()));
		builder.append(String.format(Locale.US, "pricing rounds=%d, added columns=%d%n", summary.getPricingRounds(),
				summary.getGeneratedColumns()));
		builder.append(String.format(Locale.US, "cut rounds=%d, added cuts=%d%n", summary.getCutRounds(),
				summary.getGeneratedCuts()));
		builder.append(String.format(Locale.US, "branch calls=%d, incumbent updates=%d%n", summary.getBranchCalls(),
				summary.getIncumbentUpdates()));
		builder.append(String.format(Locale.US, "pruned by incumbent=%d, closed without branch=%d%n",
				summary.getPrunedByIncumbentCount(), summary.getClosedWithoutBranchCount()));
		builder.append(String.format(Locale.US, "peak queue=%d, peak pool=%d, peak cut pool=%d%n", summary.getQueuePeak(),
				summary.getMaxPoolSize(), summary.getMaxCutPoolSize()));
		builder.append(String.format(Locale.US, "remaining queue=%d%n", summary.getRemainingQueueSize()));
		builder.append(String.format(Locale.US, "total solve time=%.3f s%n", summary.getSolveTimeSeconds()));
		return builder.toString();
	}

	private static String safeMessage(String message) {
		return message == null || message.isEmpty() ? "" : message;
	}

	public static double gapPercent(double bestBound, double incumbentCost) {
		if (!Double.isFinite(bestBound) || !Double.isFinite(incumbentCost) || incumbentCost <= 0.0) {
			return Double.NaN;
		}
		return Math.max(0.0, (1.0 - bestBound / incumbentCost) * 100.0);
	}

}
