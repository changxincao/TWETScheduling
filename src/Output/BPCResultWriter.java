package Output;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import TWETBPC.TWETBPCContext;
import TWETBPC.TWETSolveResult;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;

/**
 * BPC 结果导出工具。
 */
public final class BPCResultWriter {

	private BPCResultWriter() {
	}

	public static Path write(Path outputRoot, String methodName, String instanceName, TWETBPCContext context,
			TWETSolveResult result, ValidationResult validation, BPCTraceSummary trace) throws IOException {
		Path dir = ResultPathUtil.prepareMethodDir(outputRoot, methodName);
		String stem = ResultPathUtil.instanceStem(instanceName);
		Path summary = dir.resolve(stem + ".md");
		Path log = dir.resolve(stem + ".log");
		Path nodes = dir.resolve(stem + ".nodes.csv");
		Path columns = dir.resolve(stem + ".columns.csv");
		Path cuts = dir.resolve(stem + ".cuts.csv");

		try (BufferedWriter writer = Files.newBufferedWriter(summary)) {
			writer.write("# " + methodName + " 求解结果\n\n");
			writer.write("算例名：`" + instanceName + "`\n\n");
			writer.write("状态：" + result.getStatus() + "\n\n");
			writer.write(String.format(Locale.US, "初始 incumbent：%.6f\n\n", trace.getInitialIncumbentCost()));
			writer.write(String.format(Locale.US, "根节点下界：%.6f\n\n", trace.getRootBound()));
			writer.write(String.format(Locale.US, "根节点求解时间：%.3f s\n\n", trace.getRootSolveTimeSeconds()));
			writer.write(String.format(Locale.US, "最终 incumbent：%.6f\n\n", result.getIncumbentCost()));
			writer.write(String.format(Locale.US, "最终 lower bound：%.6f\n\n", result.getBestBound()));
			writer.write(String.format(Locale.US, "最终 gap：%.4f%%\n\n",
					BPCOutputFormatters.gapPercent(result.getBestBound(), result.getIncumbentCost())));
			writer.write(String.format(Locale.US, "总求解时间：%.3f s\n\n", trace.getSolveTimeSeconds()));
			writer.write("已处理节点数：" + result.getProcessedNodes() + "\n\n");
			writer.write("整数节点数：" + trace.getIntegerNodeCount() + "\n\n");
			writer.write("被 incumbent 剪枝节点数：" + trace.getPrunedByIncumbentCount() + "\n\n");
			writer.write("未能继续分支而关闭的节点数：" + trace.getClosedWithoutBranchCount() + "\n\n");
			writer.write("初始列数：" + trace.getInitialColumnCount() + "\n\n");
			writer.write("初始 incumbent 列数：" + trace.getInitialIncumbentColumnCount() + "\n\n");
			writer.write("全局列池大小：" + context.pool.size() + "\n\n");
			writer.write("全局 cut 池大小：" + context.cutPool.size() + "\n\n");
			writer.write("最终 incumbent 列数：" + result.getIncumbentColumnIds().size() + "\n\n");
			writer.write("pricing 调用次数：" + trace.getPricingRounds() + "\n\n");
			writer.write("新增列数：" + trace.getGeneratedColumns() + "\n\n");
			writer.write("cut 调用次数：" + trace.getCutRounds() + "\n\n");
			writer.write("新增 cuts 数：" + trace.getGeneratedCuts() + "\n\n");
			writer.write("成功分支次数：" + trace.getBranchCalls() + "\n\n");
			writer.write("incumbent 更新次数：" + trace.getIncumbentUpdates() + "\n\n");
			writer.write("队列峰值：" + trace.getQueuePeak() + "\n\n");
			writer.write("求解结束时剩余队列大小：" + trace.getRemainingQueueSize() + "\n\n");
			writer.write("列池峰值：" + trace.getMaxPoolSize() + "\n\n");
			writer.write("cut 池峰值：" + trace.getMaxCutPoolSize() + "\n\n");
			writer.write("说明：" + result.getMessage() + "\n\n");

			writer.write("## 组件统计\n\n");
			writeNamedCounters(writer, "Pricing 调用次数", trace.getPricingCallCount());
			writeNamedCounters(writer, "Pricing 成功次数", trace.getPricingSuccessCount());
			writeNamedCounters(writer, "Pricing 新增列数", trace.getPricingColumnCount());
			writeNamedCounters(writer, "Cut 调用次数", trace.getCutCallCount());
			writeNamedCounters(writer, "Cut 成功次数", trace.getCutSuccessCount());
			writeNamedCounters(writer, "Cut 新增数量", trace.getCutCountByGenerator());
			writeNamedCounters(writer, "Branch 尝试次数", trace.getBranchAttemptCount());
			writeNamedCounters(writer, "Branch 成功次数", trace.getBranchSuccessCount());

			writer.write("## 解验证\n\n");
			writer.write("可行性：" + (validation.isFeasible() ? "通过" : "未通过") + "\n\n");
			writer.write("目标值一致性：" + (validation.isObjectiveConsistent() ? "通过" : "未通过") + "\n\n");
			writer.write(String.format(Locale.US, "重算目标值：%.6f\n\n", validation.getRecomputedObjective()));
			if (!validation.getIssues().isEmpty()) {
				writer.write("问题列表：\n");
				for (String issue : validation.getIssues()) {
					writer.write("- " + issue + "\n");
				}
				writer.write("\n");
			}

			writer.write("## 过程文件\n\n");
			writer.write("- `.log`：逐步过程输出\n");
			writer.write("- `.nodes.csv`：节点级摘要\n");
			writer.write("- `.columns.csv`：最终 incumbent 列\n");
			writer.write("- `.cuts.csv`：当前 cut 池\n\n");
			if (trace.getNote() != null && !trace.getNote().isEmpty()) {
				writer.write("备注：" + trace.getNote() + "\n\n");
			}
		}

		try (BufferedWriter writer = Files.newBufferedWriter(log)) {
			for (String line : trace.getEventLines()) {
				writer.write(line);
				if (!line.endsWith("\n")) {
					writer.write("\n");
				}
			}
		}

		try (BufferedWriter writer = Files.newBufferedWriter(nodes)) {
			writer.write(
					"nodeId,depth,pseudoCost,masterStatus,nodeObjective,integer,incumbentUpdated,incumbentCost,bestBound,gapPercent,queueSize,restrictedColumns,activeCuts,poolSize,cutPoolSize,note\n");
			for (BPCNodeRecord node : trace.getNodeRecords()) {
				writer.write(String.format(Locale.US,
						"%d,%d,%.6f,%s,%.6f,%s,%s,%.6f,%.6f,%.6f,%d,%d,%d,%d,%d,\"%s\"\n", node.getNodeId(),
						node.getDepth(), node.getPseudoCost(), node.getMasterStatus(), node.getNodeObjective(),
						Boolean.toString(node.isIntegerSolution()), Boolean.toString(node.isIncumbentUpdated()),
						node.getIncumbentCostAfterNode(), node.getBestBoundAfterNode(), node.getGapPercentAfterNode(),
						node.getQueueSizeAfterNode(), node.getRestrictedColumnCount(), node.getActiveCutCount(),
						node.getPoolSizeAfterNode(), node.getCutPoolSizeAfterNode(),
						(node.getNote() == null ? "" : node.getNote()).replace("\"", "'")));
			}
		}

		try (BufferedWriter writer = Files.newBufferedWriter(columns)) {
			writer.write("columnId,cost,size,source,seed,sequence\n");
			for (int columnId : result.getIncumbentColumnIds()) {
				TWETColumn column = context.pool.getColumn(columnId);
				writer.write(String.format(Locale.US, "%d,%.6f,%d,%s,%s,\"%s\"\n", column.getId(), column.getCost(),
						column.size(), column.getSource(), Boolean.toString(column.isSeedColumn()),
						column.getSequence().toString()));
			}
		}

		try (BufferedWriter writer = Files.newBufferedWriter(cuts)) {
			writer.write("cutId,type,rhs,scopeJobs,description\n");
			for (int cutId = 0; cutId < context.cutPool.size(); cutId++) {
				TWETCut cut = context.cutPool.getCut(cutId);
				writer.write(String.format(Locale.US, "%d,%s,%.6f,\"%s\",\"%s\"\n", cut.getId(), cut.getType(),
						cut.getRhs(), cut.getScopeJobs().toString(),
						(cut.getDescription() == null ? "" : cut.getDescription()).replace("\"", "'")));
			}
		}
		return summary;
	}

	private static void writeNamedCounters(BufferedWriter writer, String title, Map<String, Integer> counters)
			throws IOException {
		writer.write(title + "：\n");
		if (counters.isEmpty()) {
			writer.write("- 暂无\n\n");
			return;
		}
		for (Map.Entry<String, Integer> entry : counters.entrySet()) {
			writer.write("- " + entry.getKey() + ": " + entry.getValue() + "\n");
		}
		writer.write("\n");
	}

}
