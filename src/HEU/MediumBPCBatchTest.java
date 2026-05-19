package HEU;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import Output.BPCSolutionValidator;
import Output.ValidationResult;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;

/**
 * 2026-05-19: 40 任务 TWET-BPC 中等规模诊断测试。
 * <p>
 * 这个入口只测试 BP/BPC 自身的节点、分支、pricing 和 incumbent 验证情况，不再用 ArcFlow 对拍。
 * 40 任务 pricing 会产生较多临时标签和分段函数，批量测试建议从脚本层面按 case id 启动多个独立 JVM，
 * 不要在同一个 JVM 中连续跑很多 case。
 */
public class MediumBPCBatchTest {

	private static final int[] DEFAULT_CASE_IDS = { 0 };

	public static void main(String[] args) throws Exception {
		new MediumBPCBatchTest().run(parseCaseIds(args));
	}

	private static int[] parseCaseIds(String[] args) {
		if (args == null || args.length == 0) {
			return DEFAULT_CASE_IDS;
		}
		int[] ids = new int[args.length];
		for (int i = 0; i < args.length; i++) {
			ids[i] = Integer.parseInt(args[i]);
		}
		return ids;
	}

	private void run(int[] caseIds) throws Exception {
		Path outputDir = Path.of("test-results", "bpc");
		Files.createDirectories(outputDir);
		Path csv = outputDir.resolve("2026-05-19-medium-bpc-40-" + caseSuffix(caseIds) + ".csv");

		int validCount = 0;
		int branchedCount = 0;
		long totalMillis = 0L;
		try (BufferedWriter writer = Files.newBufferedWriter(csv)) {
			writer.write(
					"case_id,type,n,m,objective,bound,gap_percent,status,nodes,branches,pricing_rounds,generated_columns,pool_size,valid,bpc_ms,branch_success\n");
			for (int caseId : caseIds) {
				Data data = buildCase(caseId);
				long start = System.currentTimeMillis();
				BPCRun run = solveBPC(data, caseId);
				long millis = System.currentTimeMillis() - start;
				totalMillis += millis;
				boolean valid = run.validation.isFeasible();
				if (valid) {
					validCount++;
				}
				if (run.traceBranches > 0) {
					branchedCount++;
				}
				double gapPercent = computeGapPercent(run.result.getBestBound(), run.result.getIncumbentCost());
				String type = caseId < 3 ? "linear" : "concave_tariff";
				writer.write(caseId + "," + type + "," + data.n + "," + data.m + ","
						+ run.result.getIncumbentCost() + "," + run.result.getBestBound() + "," + gapPercent + ","
						+ run.result.getStatus() + "," + run.result.getProcessedNodes() + "," + run.traceBranches + ","
						+ run.pricingRounds + "," + run.result.getGeneratedColumns() + "," + run.poolSize + "," + valid
						+ "," + millis + "," + run.branchSuccess + "\n");
				System.out.printf(
						"case=%02d type=%s obj=%.6f bound=%.6f gap%%=%.4f status=%s nodes=%d branches=%d pricing=%d cols=%d pool=%d valid=%s ms=%d branch=%s%n",
						caseId, type, run.result.getIncumbentCost(), run.result.getBestBound(), gapPercent,
						run.result.getStatus(), run.result.getProcessedNodes(), run.traceBranches, run.pricingRounds,
						run.result.getGeneratedColumns(), run.poolSize, valid, millis, run.branchSuccess);
			}
		}

		System.out.printf(
				"MediumBPCBatchTest finished: valid=%d/%d, branchedCases=%d/%d, avgBpcMs=%.2f, csv=%s%n",
				validCount, caseIds.length, branchedCount, caseIds.length, totalMillis / (double) caseIds.length, csv);
		if (validCount != caseIds.length) {
			throw new AssertionError("Some 40-job BPC incumbents failed validation. See " + csv);
		}
	}

	private Data buildCase(int caseId) throws Exception {
		Data data = SmallExactHeuristicBatchTest.buildRandomCase(2026051900 + caseId, 40, 5);
		if (caseId >= 3) {
			double totalBaseline = 0.0;
			for (int job = 1; job <= data.n; job++) {
				totalBaseline += data.outsourcingCost[job];
			}
			double split = Math.max(1.0, totalBaseline * 0.5);
			data.outsourcingCostFunction = new PiecewiseLinearFunction(0, totalBaseline);
			// 2026-05-19: 后两例使用凹折扣 tariff，目的是观察 40 任务下 z_s 分支是否会被触发。
			data.outsourcingCostFunction.addSegment(0, split, 0.8, 0.0);
			data.outsourcingCostFunction.addSegment(split, totalBaseline, 0.25, split * (0.8 - 0.25));
			data.setPreprocessedHardWindows();
			data.setPenaltyFunctions();
		}
		return data;
	}

	private String caseSuffix(int[] caseIds) {
		if (caseIds.length == 1) {
			return "case" + caseIds[0];
		}
		StringBuilder sb = new StringBuilder("cases");
		for (int caseId : caseIds) {
			sb.append('-').append(caseId);
		}
		return sb.toString();
	}

	private BPCRun solveBPC(Data data, int caseId) {
		Utility.resetCurUpperBound(Utility.big_M);
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = "medium-bpc-40-" + caseId;
		// 2026-05-19: 默认关闭过程输出；排查慢例时可用 -Dtwet.bpc.verbose=true 打开 trace，
		// 这样即使 10 分钟超时终止，也能从日志中读取最后一次 incumbent/bound。
		config.enableBPCConsoleOutput = Boolean.getBoolean("twet.bpc.verbose");
		config.writeBPCResultFiles = false;
		config.runALNSForSeed = false;
		config.maxNodes = 5000;
		config.maxInitialColumns = 3000;
		config.maxExactPricingColumns = 200;
		config.maxHeuristicPricingColumns = 200;
		config.branchSeedColumnLimit = 3000;

		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		TWETSolveResult result = solver.solve();
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);
		return new BPCRun(result, validation, solver.getContext().traceSummary.getBranchCalls(),
				solver.getContext().traceSummary.getPricingRounds(), solver.getContext().pool.size(),
				solver.getContext().traceSummary.getBranchSuccessCount().toString());
	}

	private double computeGapPercent(double bound, double incumbent) {
		if (!Double.isFinite(bound) || !Double.isFinite(incumbent) || Math.abs(incumbent) <= 1e-9) {
			return Double.NaN;
		}
		return Math.max(0.0, (incumbent - bound) / Math.abs(incumbent) * 100.0);
	}

	private static final class BPCRun {
		final TWETSolveResult result;
		final ValidationResult validation;
		final int traceBranches;
		final int pricingRounds;
		final int poolSize;
		final String branchSuccess;

		BPCRun(TWETSolveResult result, ValidationResult validation, int traceBranches, int pricingRounds, int poolSize,
				String branchSuccess) {
			this.result = result;
			this.validation = validation;
			this.traceBranches = traceBranches;
			this.pricingRounds = pricingRounds;
			this.poolSize = poolSize;
			this.branchSuccess = branchSuccess;
		}
	}
}
