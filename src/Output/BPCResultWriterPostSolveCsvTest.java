package Output;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCContext;
import TWETBPC.TWETSolveResult;
import TWETBPC.TWETSolveStatus;
import TWETBPC.CUT.SubsetRowCutEvaluator;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/**
 * 结果写出层的 post-solve CSV 回归测试。
 */
public final class BPCResultWriterPostSolveCsvTest {

	private BPCResultWriterPostSolveCsvTest() {
	}

	public static void main(String[] args) throws Exception {
		Path outputRoot = Files.createTempDirectory("bpc-result-writer-test");
		try {
			runPostSolveCsvSmokeTest(outputRoot);
			System.out.println("BPCResultWriterPostSolveCsvTest passed");
		} finally {
			deleteRecursively(outputRoot);
		}
	}

	private static void runPostSolveCsvSmokeTest(Path outputRoot) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		// 2026-08-15: 测试只补最小可导出的外包口径，避免依赖 runner 或额外数据文件。
		data.outsourcingCost[3] = 7.0;
		data.outsourcingCost[4] = 9.0;

		TWETBPCConfig config = new TWETBPCConfig();
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.writeDetailedBPCArtifacts = true;
		config.outsourcingModel = "columns";
		TWETBPCContext context = new TWETBPCContext(data, config);

		int incumbentColumnId = context.pool
				.addOrImproveColumn(Arrays.asList(1, 2, 3), 123.0, ColumnSource.MANUAL, true).columnId;
		context.pool.addOrImproveColumn(Arrays.asList(4, 5, 4), 150.0, ColumnSource.PRICING_EXACT, false);
		context.outsourcingPool.addColumn(Arrays.asList(3, 4), ColumnSource.PRICING_EXACT, false);
		context.cutPool.addCut(new TWETCut(-1, TWETCutType.SUBSET_ROW, Arrays.asList(1, 2, 3), Arrays.asList(1, 2),
				Arrays.asList(Long.valueOf(SubsetRowCutEvaluator.arcKey(0, 1)),
						Long.valueOf(SubsetRowCutEvaluator.arcKey(1, 2))),
				0.5, 1.0, "rank1"));

		BPCTraceSummary trace = new BPCTraceSummary(config);
		trace.onSolveStarted("demo-instance");
		trace.onRunConfiguration(Collections.singletonList("config.test=true"));
		trace.onInitialColumnsReady(2, 1, 123.0, 250_000_000L);
		trace.onRootPreprocessing(true, "test", 100_000_000L);
		Node root = new Node(data, Arrays.asList(Integer.valueOf(incumbentColumnId)),
				Arrays.asList(Integer.valueOf(incumbentColumnId)), config.pseudoCostInf);
		root.id = 1;
		trace.onRootPricingClosedBeforeCuts(root,
				new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION, Collections.emptyMap(), 120.0, false, "test"), 0);
		trace.onCutCall(root, "testCut", true, 2, "test", 2, 50_000_000L);
		trace.onNodeClosed(root, "pruned_by_dual_bound", 0);
		trace.setNote("csv smoke test");

		double[] outsourcingValues = new double[data.n + 1];
		outsourcingValues[3] = 1.0;
		TWETSolveResult result = new TWETSolveResult(TWETSolveStatus.FINISHED, 130.0, 120.0, 7, 2,
				Collections.singletonList(Integer.valueOf(incumbentColumnId)), outsourcingValues, "done");
		ValidationResult validation = new ValidationResult(true, true, 130.0, Collections.<String>emptyList());
		trace.onSolveFinished(result, 3.25);

		Path summary = BPCResultWriter.write(outputRoot, "postsolve-method", "demo-instance", context, result,
				validation, trace);
		Path methodDir = summary.getParent();

		assertTrue(Files.exists(methodDir.resolve("demo-instance.core-summary.csv")), "missing core-summary.csv");
		assertTrue(Files.exists(methodDir.resolve("demo-instance.components.csv")), "missing components.csv");
		assertTrue(Files.exists(methodDir.resolve("demo-instance.pool-summary.csv")), "missing pool-summary.csv");
		assertTrue(Files.exists(methodDir.resolve("demo-instance.pool-columns.csv")), "missing pool-columns.csv");

		String summaryMarkdown = Files.readString(summary);
		assertContains(summaryMarkdown, ".core-summary.csv", "summary should mention core-summary.csv");
		assertContains(summaryMarkdown, ".pool-columns.csv", "summary should mention pool-columns.csv");
		assertContains(summaryMarkdown, "被 dual bound 剪枝节点数：1", "dual-bound prune count missing");

		String columnsCsv = Files.readString(methodDir.resolve("demo-instance.columns.csv"));
		assertContains(columnsCsv, "[1, 2, 3]", "incumbent columns should remain in columns.csv");
		assertTrue(!columnsCsv.contains("[4, 5, 4]"), "columns.csv should stay incumbent-only");

		String coreSummaryCsv = Files.readString(methodDir.resolve("demo-instance.core-summary.csv"));
		assertContains(coreSummaryCsv, "methodName,instanceName,status", "core-summary header mismatch");
		assertContains(coreSummaryCsv,
				"initialColumnBuildTimeSeconds,rootPreprocessingApplied,rootPreprocessingTimeSeconds,rootBoundBeforeCuts",
				"root setup fields missing");
		assertContains(coreSummaryCsv, "prunedByIncumbentCount,prunedByDualBoundCount,closedWithoutBranchCount",
				"node prune fields missing");
		assertContains(coreSummaryCsv, "123.000000,0.250000,true,0.100000", "root setup values mismatch");
		assertContains(coreSummaryCsv, "120.000000,,2,0.050000", "root cut fields mismatch");
		assertContains(coreSummaryCsv, "postsolve-method,demo-instance,FINISHED", "core-summary row mismatch");
		assertContains(coreSummaryCsv, ",7,0,0,1,0,", "dual-bound prune value mismatch");
		assertContains(coreSummaryCsv, ",2,1,3,1,", "core-summary should include machine/outsourcing/total pool sizes");

		String componentsCsv = Files.readString(methodDir.resolve("demo-instance.components.csv"));
		assertContains(componentsCsv, "pricing,TOTAL,0,0,0,0.000000,0.000000", "pricing total row missing");
		assertContains(componentsCsv, "branch,TOTAL,0,0,,,", "branch total row missing");

		String poolSummaryCsv = Files.readString(methodDir.resolve("demo-instance.pool-summary.csv"));
		assertContains(poolSummaryCsv, "TOTAL,machine,ALL,2,1,273.000000", "machine pool summary mismatch");
		assertContains(poolSummaryCsv, "TOTAL,outsourcing,ALL,1,0,16.000000", "outsourcing pool summary mismatch");

		String poolColumnsCsv = Files.readString(methodDir.resolve("demo-instance.pool-columns.csv"));
		assertContains(poolColumnsCsv, "machine,1,150.000000,3,PRICING_EXACT,false,false,,\"[4, 5, 4]\"",
				"pool-columns should include non-incumbent machine column");
		assertContains(poolColumnsCsv, "outsourcing,0,16.000000,2,PRICING_EXACT,false,,16.000000,\"[3, 4]\"",
				"pool-columns should include outsourcing pool column");

		String cutsCsv = Files.readString(methodDir.resolve("demo-instance.cuts.csv"));
		assertContains(cutsCsv, "cutId,type,rhs,scopeJobs,description,multiplier,memoryJobs,memoryArcs",
				"cuts header mismatch");
		assertContains(cutsCsv, "SUBSET_ROW,1.000000,\"[1, 2, 3]\",rank1,0.500000,\"[1, 2]\"",
				"cuts row should include multiplier and memory jobs");
		assertContains(cutsCsv, "\"[1, 4294967298]\"", "cuts row should include memory arcs");

		String outsourcingCsv = Files.readString(methodDir.resolve("demo-instance.outsourcing.csv"));
		assertContains(outsourcingCsv, "outsourcedJobCount,outsourcedJobFraction,outsourcedProcessing",
				"outsourcing aggregate fields missing");
		assertContains(outsourcingCsv, "processingTime,weightedProcessing,dueWindowStart,dueWindowEnd",
				"outsourcing job fields missing");
		assertContains(outsourcingCsv, "JOB,3,1.000000,7.000000,7.000000",
				"outsourced job detail mismatch");

		config.writeDetailedBPCArtifacts = false;
		Path compactSummary = BPCResultWriter.write(outputRoot, "postsolve-compact", "demo-instance", context,
				result, validation, trace);
		Path compactDir = compactSummary.getParent();
		assertTrue(!Files.exists(compactDir.resolve("demo-instance.pool-columns.csv")),
				"compact output must not write full pool columns");
		assertTrue(!Files.exists(compactDir.resolve("demo-instance.incumbent-schedule.csv")),
				"compact output must not write detailed schedule");
		assertTrue(!Files.readString(compactSummary).contains(".pool-columns.csv"),
				"compact summary must not advertise absent detailed files");
	}

	private static void deleteRecursively(Path root) throws Exception {
		if (!Files.exists(root)) {
			return;
		}
		Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> {
			try {
				Files.deleteIfExists(path);
			} catch (Exception ex) {
				throw new IllegalStateException("failed to delete " + path, ex);
			}
		});
	}

	private static void assertContains(String text, String expected, String message) {
		assertTrue(text.contains(expected), message + " expected=" + expected + " actual=" + text);
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
