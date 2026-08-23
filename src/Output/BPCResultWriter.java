package Output;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import Common.Utility;
import TWETBPC.TWETBPCContext;
import TWETBPC.TWETSolveResult;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETOutsourcingColumn;
import TWETBPC.IO.TWETColumnEvaluator;

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
		Path coreSummary = dir.resolve(stem + ".core-summary.csv");
		Path components = dir.resolve(stem + ".components.csv");
		Path poolSummary = dir.resolve(stem + ".pool-summary.csv");
		Path poolColumns = dir.resolve(stem + ".pool-columns.csv");
		Path incumbentSchedule = dir.resolve(stem + ".incumbent-schedule.csv");
		Path outsourcing = dir.resolve(stem + ".outsourcing.csv");
		Path cuts = dir.resolve(stem + ".cuts.csv");
		Path configFile = dir.resolve(stem + ".config.properties");

		try (BufferedWriter writer = Files.newBufferedWriter(summary)) {
			writer.write("# " + methodName + " 求解结果\n\n");
			writer.write("算例名：`" + instanceName + "`\n\n");
			writer.write("状态：" + result.getStatus() + "\n\n");
			writer.write(String.format(Locale.US, "初始 incumbent：%.6f\n\n", trace.getInitialIncumbentCost()));
			writer.write(String.format(Locale.US, "初始列构造时间：%.3f s\n\n", trace.getInitialColumnBuildTimeSeconds()));
			writer.write("根节点预处理应用：" + trace.isRootPreprocessingApplied() + "\n\n");
			writer.write(String.format(Locale.US, "根节点预处理时间：%.3f s\n\n",
					trace.getRootPreprocessingTimeSeconds()));
			writer.write(String.format(Locale.US, "cut 前根节点下界：%.6f\n\n", trace.getRootBoundBeforeCuts()));
			writer.write(String.format(Locale.US, "根节点下界：%.6f\n\n", trace.getRootBound()));
			writer.write("根节点新增 cuts 数：" + trace.getRootGeneratedCuts() + "\n\n");
			writer.write(String.format(Locale.US, "根节点 cut separation 时间：%.3f s\n\n",
					trace.getRootCutTimeSeconds()));
			writer.write(String.format(Locale.US, "根节点求解时间：%.3f s\n\n", trace.getRootSolveTimeSeconds()));
			writer.write(String.format(Locale.US, "最终 incumbent：%.6f\n\n", result.getIncumbentCost()));
			writer.write(String.format(Locale.US, "最终 lower bound：%.6f\n\n", result.getBestBound()));
			writer.write(String.format(Locale.US, "最终 gap：%.4f%%\n\n",
					BPCOutputFormatters.gapPercent(result.getBestBound(), result.getIncumbentCost())));
			writer.write(String.format(Locale.US, "总求解时间：%.3f s\n\n", trace.getSolveTimeSeconds()));
			writer.write("已处理节点数：" + result.getProcessedNodes() + "\n\n");
			writer.write("整数节点数：" + trace.getIntegerNodeCount() + "\n\n");
			writer.write("被 incumbent 剪枝节点数：" + trace.getPrunedByIncumbentCount() + "\n\n");
			writer.write("被 dual bound 剪枝节点数：" + trace.getPrunedByDualBoundCount() + "\n\n");
			writer.write("未能继续分支而关闭的节点数：" + trace.getClosedWithoutBranchCount() + "\n\n");
			writer.write("初始列数：" + trace.getInitialColumnCount() + "\n\n");
			writer.write("初始 incumbent 列数：" + trace.getInitialIncumbentColumnCount() + "\n\n");
			writer.write("全局列池大小：" + totalPoolSize(context) + "\n\n");
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
			writeNamedTimes(writer, "Pricing 耗时", trace.getPricingTimeNanos(), trace.getPricingCallCount());
			writeNamedTimes(writer, "RMP/LP 求解耗时", trace.getMasterLpTimeNanos(), trace.getMasterLpCallCount());
			writeNamedTimes(writer, "RMP/LP 模型构造耗时（已包含于求解总耗时）",
					trace.getMasterLpBuildTimeNanos(), trace.getMasterLpBuildCallCount());
			writeNamedTimes(writer, "Strong trial 状态准备耗时",
					trace.getStrongTrialSetupTimeNanos(), trace.getStrongTrialSetupCallCount());
			writeNamedCounters(writer, "Cut 调用次数", trace.getCutCallCount());
			writeNamedCounters(writer, "Cut 成功次数", trace.getCutSuccessCount());
			writeNamedCounters(writer, "Cut 新增数量", trace.getCutCountByGenerator());
			writeNamedTimes(writer, "Cut 耗时", trace.getCutTimeNanos(), trace.getCutCallCount());
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
			writer.write("- `.core-summary.csv`：核心结果一行摘要\n");
			writer.write("- `.components.csv`：组件级计数与耗时摘要\n");
			writer.write("- `.pool-summary.csv`：最终列池聚合摘要\n");
			if (context.config.writeDetailedBPCArtifacts) {
				writer.write("- `.pool-columns.csv`：最终全局列池快照\n");
				writer.write("- `.incumbent-schedule.csv`：最终内部机器调度与成本分解\n");
			}
			writer.write("- `.cuts.csv`：当前 cut 池\n\n");
			writer.write("- `.config.properties`：本次求解配置快照\n\n");
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

		try (BufferedWriter writer = Files.newBufferedWriter(coreSummary)) {
			writeCsvLine(writer, "methodName", "instanceName", "status", "initialIncumbentCost",
					"initialColumnBuildTimeSeconds", "rootPreprocessingApplied", "rootPreprocessingTimeSeconds",
					"rootBoundBeforeCuts", "rootBound", "rootGeneratedCuts", "rootCutSeparationTimeSeconds",
					"rootSolveTimeSeconds", "incumbentCost", "bestBound", "gapPercent", "solveTimeSeconds",
					"processedNodes", "integerNodeCount", "prunedByIncumbentCount", "prunedByDualBoundCount",
					"closedWithoutBranchCount",
					"initialColumnCount", "initialIncumbentColumnCount", "machinePoolSize", "outsourcingPoolSize",
					"totalPoolSize", "cutPoolSize", "incumbentColumnCount", "pricingRounds", "generatedColumns",
					"cutRounds", "generatedCuts", "branchCalls", "incumbentUpdates", "queuePeak",
					"remainingQueueSize", "maxPoolSize", "maxCutPoolSize", "validationFeasible",
					"validationObjectiveConsistent", "recomputedObjective", "message", "note");
			writeCsvLine(writer, methodName, instanceName, String.valueOf(result.getStatus()),
					formatFinite(trace.getInitialIncumbentCost()), formatFinite(trace.getInitialColumnBuildTimeSeconds()),
					Boolean.toString(trace.isRootPreprocessingApplied()),
					formatFinite(trace.getRootPreprocessingTimeSeconds()),
					formatFinite(trace.getRootBoundBeforeCuts()), formatFinite(trace.getRootBound()),
					Integer.toString(trace.getRootGeneratedCuts()), formatFinite(trace.getRootCutTimeSeconds()),
					formatFinite(trace.getRootSolveTimeSeconds()), formatFinite(result.getIncumbentCost()),
					formatFinite(result.getBestBound()),
					formatFinite(BPCOutputFormatters.gapPercent(result.getBestBound(), result.getIncumbentCost())),
					formatFinite(trace.getSolveTimeSeconds()), Integer.toString(result.getProcessedNodes()),
					Integer.toString(trace.getIntegerNodeCount()), Integer.toString(trace.getPrunedByIncumbentCount()),
					Integer.toString(trace.getPrunedByDualBoundCount()),
					Integer.toString(trace.getClosedWithoutBranchCount()),
					Integer.toString(trace.getInitialColumnCount()),
					Integer.toString(trace.getInitialIncumbentColumnCount()), Integer.toString(context.pool.size()),
					Integer.toString(context.outsourcingPool.size()), Integer.toString(totalPoolSize(context)),
					Integer.toString(context.cutPool.size()), Integer.toString(result.getIncumbentColumnIds().size()),
					Integer.toString(trace.getPricingRounds()), Integer.toString(trace.getGeneratedColumns()),
					Integer.toString(trace.getCutRounds()), Integer.toString(trace.getGeneratedCuts()),
					Integer.toString(trace.getBranchCalls()), Integer.toString(trace.getIncumbentUpdates()),
					Integer.toString(trace.getQueuePeak()), Integer.toString(trace.getRemainingQueueSize()),
					Integer.toString(trace.getMaxPoolSize()), Integer.toString(trace.getMaxCutPoolSize()),
					Boolean.toString(validation.isFeasible()), Boolean.toString(validation.isObjectiveConsistent()),
					formatFinite(validation.getRecomputedObjective()), safe(result.getMessage()),
					safe(trace.getNote()));
		}

		try (BufferedWriter writer = Files.newBufferedWriter(components)) {
			writeCsvLine(writer, "category", "component", "calls", "successes", "generated", "totalSeconds",
					"averageMillis");
			writeNamedComponentRows(writer, "pricing", trace.getPricingRounds(), trace.getGeneratedColumns(),
					trace.getPricingCallCount(), trace.getPricingSuccessCount(), trace.getPricingColumnCount(),
					trace.getPricingTimeNanos(), true);
			writeNamedComponentRows(writer, "masterLp", sumInt(trace.getMasterLpCallCount()), null,
					trace.getMasterLpCallCount(), null, null, trace.getMasterLpTimeNanos(), true);
			writeNamedComponentRows(writer, "masterLpBuild", sumInt(trace.getMasterLpBuildCallCount()), null,
					trace.getMasterLpBuildCallCount(), null, null, trace.getMasterLpBuildTimeNanos(), true);
			writeNamedComponentRows(writer, "strongTrialSetup", sumInt(trace.getStrongTrialSetupCallCount()), null,
					trace.getStrongTrialSetupCallCount(), null, null, trace.getStrongTrialSetupTimeNanos(), true);
			writeNamedComponentRows(writer, "cut", trace.getCutRounds(), trace.getGeneratedCuts(),
					trace.getCutCallCount(), trace.getCutSuccessCount(), trace.getCutCountByGenerator(),
					trace.getCutTimeNanos(), true);
			writeNamedComponentRows(writer, "branch", sumInt(trace.getBranchAttemptCount()), null,
					trace.getBranchAttemptCount(), trace.getBranchSuccessCount(), null, null, false);
		}

		try (BufferedWriter writer = Files.newBufferedWriter(poolSummary)) {
			writeCsvLine(writer, "recordType", "poolType", "source", "columnCount", "seedCount", "totalCost",
					"minCost", "maxCost", "avgCost", "totalSize", "avgSize", "maxSize", "elementaryCount",
					"nonElementaryCount", "totalBaseline", "minBaseline", "maxBaseline", "avgBaseline");
			writeMachinePoolSummary(writer, context);
			writeOutsourcingPoolSummary(writer, context);
		}

		if (context.config.writeDetailedBPCArtifacts) {
			writeDetailedArtifacts(poolColumns, incumbentSchedule, context, result);
		}

		try (BufferedWriter writer = Files.newBufferedWriter(outsourcing)) {
			// 2026-05-17: y_j 是 RMP 的正式解变量，单独导出，避免结果只看内部机器列。
			double[] values = result.getIncumbentOutsourcingValues();
			double outsourcingBaseline = 0.0;
			double outsourcedProcessing = 0.0;
			double totalProcessing = 0.0;
			double totalAvailableBaseline = 0.0;
			int outsourcedJobCount = 0;
			for (int job = 1; job <= context.data.n; job++) {
				double value = job < values.length ? values[job] : 0.0;
				totalProcessing += context.data.p[job];
				if (!Utility.isBigMValue(context.data.outsourcingCost[job])) {
					totalAvailableBaseline += context.data.outsourcingCost[job];
				}
				if (value > 1e-8) {
					outsourcedJobCount++;
					outsourcedProcessing += value * context.data.p[job];
					outsourcingBaseline += value * context.data.outsourcingCost[job];
				}
			}
			double outsourcingCost = context.data.evaluateOutsourcingCost(outsourcingBaseline);
			writeCsvLine(writer, "recordType", "jobId", "value", "baselineCost",
					"weightedBaselineContribution", "outsourcingCostTotal", "processingTime",
					"weightedProcessing", "dueWindowStart", "dueWindowEnd", "earlinessWeight",
					"tardinessWeight", "outsourcedJobCount", "outsourcedJobFraction",
					"outsourcedProcessing", "outsourcedProcessingFraction", "quotationFraction");
			writeCsvLine(writer, "TOTAL", "", "", "", formatFinite(outsourcingBaseline),
					formatFinite(outsourcingCost), "", "", "", "", "", "",
					Integer.toString(outsourcedJobCount), formatFinite(safeRatio(outsourcedJobCount, context.data.n)),
					formatFinite(outsourcedProcessing), formatFinite(safeRatio(outsourcedProcessing, totalProcessing)),
					formatFinite(safeRatio(outsourcingBaseline, totalAvailableBaseline)));
			for (int job = 1; job <= context.data.n; job++) {
				double value = job < values.length ? values[job] : 0.0;
				if (value > 1e-8) {
					writeCsvLine(writer, "JOB", Integer.toString(job), formatFinite(value),
							formatFinite(context.data.outsourcingCost[job]),
							formatFinite(value * context.data.outsourcingCost[job]), "",
							formatFinite(context.data.p[job]), formatFinite(value * context.data.p[job]),
							formatFinite(context.data.d_e[job]), formatFinite(context.data.d_l[job]),
							formatFinite(context.data.w_e[job]), formatFinite(context.data.w_t[job]), "", "", "", "", "");
				}
			}
		}

		try (BufferedWriter writer = Files.newBufferedWriter(cuts)) {
			writer.write("cutId,type,rhs,scopeJobs,description,multiplier,memoryJobs,memoryArcs\n");
			for (int cutId = 0; cutId < context.cutPool.size(); cutId++) {
				TWETCut cut = context.cutPool.getCut(cutId);
				writeCsvLine(writer, Integer.toString(cut.getId()), String.valueOf(cut.getType()),
						formatFinite(cut.getRhs()), cut.getScopeJobs().toString(), safe(cut.getDescription()),
						formatFinite(cut.getMultiplier()), cut.getMemoryJobs().toString(), cut.getMemoryArcs().toString());
			}
		}
		try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
			for (String line : trace.getRunConfigurationLines()) {
				writer.write(line);
				writer.write("\n");
			}
		}
		return summary;
	}

	private static void writeDetailedArtifacts(Path poolColumns, Path incumbentSchedule, TWETBPCContext context,
			TWETSolveResult result) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(poolColumns)) {
			writeCsvLine(writer, "poolType", "columnId", "cost", "size", "source", "seed", "elementary",
					"baseline", "sequenceOrJobs");
			for (TWETColumn column : context.pool.getColumns()) {
				writeCsvLine(writer, "machine", Integer.toString(column.getId()), formatFinite(column.getCost()),
						Integer.toString(column.size()), String.valueOf(column.getSource()),
						Boolean.toString(column.isSeedColumn()),
						Boolean.toString(column.getPattern().isElementary()), "", column.getSequence().toString());
			}
			for (TWETOutsourcingColumn column : context.outsourcingPool.getColumns()) {
				writeCsvLine(writer, "outsourcing", Integer.toString(column.getId()), formatFinite(column.getCost()),
						Integer.toString(column.size()), String.valueOf(column.getSource()),
						Boolean.toString(column.isSeedColumn()), "", formatFinite(column.getBaseline()),
						column.getJobs().toString());
			}
		}

		try (BufferedWriter writer = Files.newBufferedWriter(incumbentSchedule)) {
			writeCsvLine(writer, "machine", "columnId", "position", "job", "completion", "earliness",
					"tardiness", "penaltyCost", "setupCost", "jobContribution");
			TWETColumnEvaluator evaluator = new TWETColumnEvaluator(context.data);
			int machine = 0;
			for (int columnId : result.getIncumbentColumnIds()) {
				TWETColumn column = context.pool.getColumn(columnId);
				TWETColumnEvaluator.Timing timing = evaluator.evaluateTiming(column.getSequence());
				int previousJob = 0;
				for (int position = 0; position < column.getSequence().size(); position++) {
					int job = column.getSequence().get(position).intValue();
					double completion = timing.completions[position];
					double earliness = Math.max(0.0, context.data.d_e[job] - completion);
					double tardiness = Math.max(0.0, completion - context.data.d_l[job]);
					double penalty = context.data.w_e[job] * earliness + context.data.w_t[job] * tardiness;
					double setupCost = context.data.getSetupCost(previousJob, job);
					writeCsvLine(writer, Integer.toString(machine), Integer.toString(columnId),
							Integer.toString(position), Integer.toString(job), formatFinite(completion),
							formatFinite(earliness), formatFinite(tardiness), formatFinite(penalty),
							formatFinite(setupCost), formatFinite(penalty + setupCost));
					previousJob = job;
				}
				machine++;
			}
		}
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

	private static void writeNamedTimes(BufferedWriter writer, String title, Map<String, Long> nanosByKey,
			Map<String, Integer> callCountByKey) throws IOException {
		writer.write(title + "：\n");
		if (nanosByKey.isEmpty()) {
			writer.write("- 暂无\n\n");
			return;
		}
		for (Map.Entry<String, Long> entry : nanosByKey.entrySet()) {
			Integer callCount = callCountByKey.get(entry.getKey());
			int calls = callCount == null ? 0 : callCount.intValue();
			if (calls > 0) {
				writer.write(String.format(Locale.US, "- %s: %.3f s, %d 次, 平均 %.3f ms\n", entry.getKey(),
						entry.getValue().longValue() / 1_000_000_000.0, calls,
						entry.getValue().longValue() / 1_000_000.0 / calls));
			} else {
				writer.write(String.format(Locale.US, "- %s: %.3f s\n", entry.getKey(),
						entry.getValue().longValue() / 1_000_000_000.0));
			}
		}
		writer.write("\n");
	}

	private static double safeRatio(double numerator, double denominator) {
		return denominator > 0.0 ? numerator / denominator : 0.0;
	}

	private static int totalPoolSize(TWETBPCContext context) {
		return context.pool.size()
				+ (context.config.useColumnizedOutsourcing() ? context.outsourcingPool.size() : 0);
	}

	private static void writeNamedComponentRows(BufferedWriter writer, String category, int totalCalls,
			Integer totalGenerated, Map<String, Integer> calls, Map<String, Integer> successes,
			Map<String, Integer> generated, Map<String, Long> nanos, boolean hasTiming) throws IOException {
		writeComponentRow(writer, category, "TOTAL", totalCalls,
				successes == null ? null : Integer.valueOf(sumInt(successes)),
				totalGenerated, hasTiming ? Long.valueOf(sumLong(nanos)) : null);
		for (Map.Entry<String, Integer> entry : calls.entrySet()) {
			String name = entry.getKey();
			writeComponentRow(writer, category, name, entry.getValue().intValue(),
					successes == null ? null : Integer.valueOf(valueInt(successes, name)),
					generated == null ? null : Integer.valueOf(valueInt(generated, name)),
					hasTiming ? Long.valueOf(valueLong(nanos, name)) : null);
		}
	}

	private static void writeComponentRow(BufferedWriter writer, String category, String component, int calls,
			Integer successes, Integer generated, Long elapsedNanos) throws IOException {
		writeCsvLine(writer, category, component, Integer.toString(calls), valueOrBlank(successes),
				valueOrBlank(generated), elapsedNanos == null ? "" : formatFinite(elapsedNanos.longValue() / 1_000_000_000.0),
				elapsedNanos == null ? ""
						: formatFinite(calls == 0 ? 0.0 : elapsedNanos.longValue() / 1_000_000.0 / calls));
	}

	private static void writeMachinePoolSummary(BufferedWriter writer, TWETBPCContext context) throws IOException {
		PoolAggregate total = new PoolAggregate();
		LinkedHashMap<String, PoolAggregate> bySource = new LinkedHashMap<String, PoolAggregate>();
		for (TWETColumn column : context.pool.getColumns()) {
			total.acceptMachine(column);
			aggregate(bySource, String.valueOf(column.getSource())).acceptMachine(column);
		}
		writePoolSummaryRow(writer, "TOTAL", "machine", "ALL", total, true, false);
		for (Map.Entry<String, PoolAggregate> entry : bySource.entrySet()) {
			writePoolSummaryRow(writer, "SOURCE", "machine", entry.getKey(), entry.getValue(), true, false);
		}
	}

	private static void writeOutsourcingPoolSummary(BufferedWriter writer, TWETBPCContext context) throws IOException {
		PoolAggregate total = new PoolAggregate();
		LinkedHashMap<String, PoolAggregate> bySource = new LinkedHashMap<String, PoolAggregate>();
		for (TWETOutsourcingColumn column : context.outsourcingPool.getColumns()) {
			total.acceptOutsourcing(column);
			aggregate(bySource, String.valueOf(column.getSource())).acceptOutsourcing(column);
		}
		writePoolSummaryRow(writer, "TOTAL", "outsourcing", "ALL", total, false, true);
		for (Map.Entry<String, PoolAggregate> entry : bySource.entrySet()) {
			writePoolSummaryRow(writer, "SOURCE", "outsourcing", entry.getKey(), entry.getValue(), false, true);
		}
	}

	private static void writePoolSummaryRow(BufferedWriter writer, String recordType, String poolType, String source,
			PoolAggregate aggregate, boolean includeElementary, boolean includeBaseline) throws IOException {
		writeCsvLine(writer, recordType, poolType, source, Integer.toString(aggregate.columnCount),
				Integer.toString(aggregate.seedCount), formatAggregate(aggregate.totalCost, aggregate.columnCount > 0),
				formatAggregate(aggregate.minCost, aggregate.columnCount > 0),
				formatAggregate(aggregate.maxCost, aggregate.columnCount > 0),
				formatAggregate(aggregate.columnCount == 0 ? Double.NaN : aggregate.totalCost / aggregate.columnCount,
						aggregate.columnCount > 0),
				Integer.toString(aggregate.totalSize),
				formatAggregate(aggregate.columnCount == 0 ? Double.NaN : aggregate.totalSize / (double) aggregate.columnCount,
						aggregate.columnCount > 0),
				Integer.toString(aggregate.maxSize),
				includeElementary ? Integer.toString(aggregate.elementaryCount) : "",
				includeElementary ? Integer.toString(aggregate.nonElementaryCount) : "",
				includeBaseline ? formatAggregate(aggregate.totalBaseline, aggregate.columnCount > 0) : "",
				includeBaseline ? formatAggregate(aggregate.minBaseline, aggregate.columnCount > 0) : "",
				includeBaseline ? formatAggregate(aggregate.maxBaseline, aggregate.columnCount > 0) : "",
				includeBaseline
						? formatAggregate(aggregate.columnCount == 0 ? Double.NaN
								: aggregate.totalBaseline / aggregate.columnCount, aggregate.columnCount > 0)
						: "");
	}

	private static PoolAggregate aggregate(LinkedHashMap<String, PoolAggregate> aggregates, String key) {
		PoolAggregate aggregate = aggregates.get(key);
		if (aggregate == null) {
			aggregate = new PoolAggregate();
			aggregates.put(key, aggregate);
		}
		return aggregate;
	}

	private static void writeCsvLine(BufferedWriter writer, String... values) throws IOException {
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				writer.write(",");
			}
			writer.write(escapeCsv(values[i]));
		}
		writer.write("\n");
	}

	private static String escapeCsv(String value) {
		String safeValue = safe(value);
		if (safeValue.indexOf(',') < 0 && safeValue.indexOf('"') < 0 && safeValue.indexOf('\n') < 0
				&& safeValue.indexOf('\r') < 0) {
			return safeValue;
		}
		return "\"" + safeValue.replace("\"", "\"\"") + "\"";
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static String formatFinite(double value) {
		return Double.isFinite(value) ? String.format(Locale.US, "%.6f", value) : "";
	}

	private static String formatAggregate(double value, boolean present) {
		return present ? formatFinite(value) : "";
	}

	private static String valueOrBlank(Integer value) {
		return value == null ? "" : Integer.toString(value.intValue());
	}

	private static int sumInt(Map<String, Integer> values) {
		int total = 0;
		for (Integer value : values.values()) {
			total += value.intValue();
		}
		return total;
	}

	private static long sumLong(Map<String, Long> values) {
		long total = 0L;
		for (Long value : values.values()) {
			total += value.longValue();
		}
		return total;
	}

	private static int valueInt(Map<String, Integer> values, String key) {
		Integer value = values.get(key);
		return value == null ? 0 : value.intValue();
	}

	private static long valueLong(Map<String, Long> values, String key) {
		Long value = values.get(key);
		return value == null ? 0L : value.longValue();
	}

	private static final class PoolAggregate {
		int columnCount;
		int seedCount;
		double totalCost;
		double minCost = Double.POSITIVE_INFINITY;
		double maxCost = Double.NEGATIVE_INFINITY;
		int totalSize;
		int maxSize;
		int elementaryCount;
		int nonElementaryCount;
		double totalBaseline;
		double minBaseline = Double.POSITIVE_INFINITY;
		double maxBaseline = Double.NEGATIVE_INFINITY;

		void acceptMachine(TWETColumn column) {
			columnCount++;
			if (column.isSeedColumn()) {
				seedCount++;
			}
			totalCost += column.getCost();
			minCost = Math.min(minCost, column.getCost());
			maxCost = Math.max(maxCost, column.getCost());
			totalSize += column.size();
			maxSize = Math.max(maxSize, column.size());
			if (column.getPattern().isElementary()) {
				elementaryCount++;
			} else {
				nonElementaryCount++;
			}
		}

		void acceptOutsourcing(TWETOutsourcingColumn column) {
			columnCount++;
			if (column.isSeedColumn()) {
				seedCount++;
			}
			totalCost += column.getCost();
			minCost = Math.min(minCost, column.getCost());
			maxCost = Math.max(maxCost, column.getCost());
			totalSize += column.size();
			maxSize = Math.max(maxSize, column.size());
			totalBaseline += column.getBaseline();
			minBaseline = Math.min(minBaseline, column.getBaseline());
			maxBaseline = Math.max(maxBaseline, column.getBaseline());
		}
	}

}
