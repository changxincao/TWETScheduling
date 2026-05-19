package HEU;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import Basic.ArcFlowModel;
import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import Output.BPCSolutionValidator;
import Output.ValidationResult;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;
import TWETBPC.TWETSolveStatus;
import ilog.cplex.IloCplex;

/**
 * 2026-05-19: 小规模随机算例的 ArcFlow 精确值 vs TWET-BPC 整树求解对拍。
 * 这个类只用于回归检查 BPC 的 tree/node/branch 主流程，不参与正式实验。
 */
public class SmallBPCBatchTest {

	private static final int CASES = 8;
	private static final double TOL = 1e-5;

	public static void main(String[] args) throws Exception {
		new SmallBPCBatchTest().run();
	}

	private void run() throws Exception {
		Path outputDir = Path.of("test-results", "bpc");
		Files.createDirectories(outputDir);
		Path csv = outputDir.resolve("2026-05-19-small-bpc.csv");
		int matched = 0;
		int finished = 0;
		int branchedCases = 0;
		long exactMillisTotal = 0L;
		long bpcMillisTotal = 0L;

		try (BufferedWriter writer = Files.newBufferedWriter(csv)) {
			writer.write(
					"case_id,n,m,arcflow,bpc,bound,gap,status,nodes,branches,pricing_rounds,generated_columns,validation,exact_ms,bpc_ms\n");
			for (int caseId = 0; caseId < CASES; caseId++) {
				int n = 5 + caseId % 3;
				Data exactData = SmallExactHeuristicBatchTest.buildRandomCase(caseId, n, 2);
				Data bpcData = SmallExactHeuristicBatchTest.buildRandomCase(caseId, n, 2);

				long exactStart = System.currentTimeMillis();
				double exact = solveArcFlow(exactData);
				long exactMillis = System.currentTimeMillis() - exactStart;

				long bpcStart = System.currentTimeMillis();
				BPCRun bpc = solveBPC(bpcData, caseId);
				long bpcMillis = System.currentTimeMillis() - bpcStart;

				exactMillisTotal += exactMillis;
				bpcMillisTotal += bpcMillis;
				double gap = bpc.result.getIncumbentCost() - exact;
				boolean objectiveOk = Math.abs(gap) <= TOL;
				boolean finishedOk = isTreeClosed(bpc.result);
				boolean validationOk = bpc.validation.isFeasible();
				if (objectiveOk && finishedOk && validationOk) {
					matched++;
				}
				if (finishedOk) {
					finished++;
				}
				if (bpc.branchCalls > 0) {
					branchedCases++;
				}

				writer.write(caseId + "," + n + "," + bpcData.m + "," + exact + ","
						+ bpc.result.getIncumbentCost() + "," + bpc.result.getBestBound() + "," + gap + ","
						+ bpc.result.getStatus() + "," + bpc.result.getProcessedNodes() + "," + bpc.branchCalls + ","
						+ bpc.pricingRounds + "," + bpc.result.getGeneratedColumns() + "," + validationOk + ","
						+ exactMillis + "," + bpcMillis + "\n");
				System.out.printf(
						"case=%02d n=%d arc=%.6f bpc=%.6f bound=%.6f gap=%.3g status=%s nodes=%d branches=%d pricing=%d valid=%s exactMs=%d bpcMs=%d%n",
						caseId, n, exact, bpc.result.getIncumbentCost(), bpc.result.getBestBound(), gap,
						bpc.result.getStatus(), bpc.result.getProcessedNodes(), bpc.branchCalls, bpc.pricingRounds,
						validationOk, exactMillis, bpcMillis);
			}
		}

		System.out.printf(
				"SmallBPCBatchTest finished: matched=%d/%d, finished=%d/%d, branchedCases=%d/%d, avgExactMs=%.2f, avgBPCMs=%.2f, csv=%s%n",
				matched, CASES, finished, CASES, branchedCases, CASES, exactMillisTotal / (double) CASES,
				bpcMillisTotal / (double) CASES, csv);
		if (matched != CASES) {
			throw new AssertionError("BPC did not match ArcFlow on all small cases. See " + csv);
		}
		runTariffBranchCase(outputDir.resolve("2026-05-19-small-bpc-branch.csv"));
	}

	private void runTariffBranchCase(Path csv) throws Exception {
		Data exactData = buildTariffBranchCase();
		Data bpcData = buildTariffBranchCase();
		double exact = solveArcFlow(exactData);
		BPCRun bpc = solveBPC(bpcData, 1000);
		double gap = bpc.result.getIncumbentCost() - exact;
		boolean objectiveOk = Math.abs(gap) <= TOL;
		boolean completedOk = isTreeClosed(bpc.result);
		boolean validationOk = bpc.validation.isFeasible();
		try (BufferedWriter writer = Files.newBufferedWriter(csv)) {
			writer.write("case_id,n,m,arcflow,bpc,bound,gap,status,nodes,branches,pricing_rounds,generated_columns,validation\n");
			writer.write("tariff_branch," + bpcData.n + "," + bpcData.m + "," + exact + ","
					+ bpc.result.getIncumbentCost() + "," + bpc.result.getBestBound() + "," + gap + ","
					+ bpc.result.getStatus() + "," + bpc.result.getProcessedNodes() + "," + bpc.branchCalls + ","
					+ bpc.pricingRounds + "," + bpc.result.getGeneratedColumns() + "," + validationOk + "\n");
		}
		System.out.printf(
				"tariffBranch arc=%.6f bpc=%.6f bound=%.6f gap=%.3g status=%s nodes=%d branches=%d pricing=%d valid=%s csv=%s%n",
				exact, bpc.result.getIncumbentCost(), bpc.result.getBestBound(), gap, bpc.result.getStatus(),
				bpc.result.getProcessedNodes(), bpc.branchCalls, bpc.pricingRounds, validationOk, csv);
		if (!objectiveOk || !completedOk || !validationOk || bpc.branchCalls <= 0 || bpc.result.getProcessedNodes() <= 1) {
			throw new AssertionError("Tariff branch diagnostic failed. See " + csv);
		}
	}

	private Data buildTariffBranchCase() throws Exception {
		Data data = SmallExactHeuristicBatchTest.buildRandomCase(20260519, 5, 2);
		for (int j = 1; j <= data.n; j++) {
			data.p[j] = 100;
			data.d_e[j] = 0;
			data.d_l[j] = 0;
			data.w_e[j] = 1;
			data.w_t[j] = 100;
			data.outsourcingCost[j] = 1;
		}
		for (int i = 0; i <= data.n; i++) {
			for (int j = 0; j <= data.n; j++) {
				if (i != j) {
					data.s[i][j] = 20;
					data.setupCost[i][j] = 50;
				}
			}
		}
		data.CmaxH = 400;
		data.CmaxE = data.CmaxH;
		data.outsourcingCostFunction = new PiecewiseLinearFunction(0, 20);
		// 2026-05-19: 人为构造两段凹折扣 tariff。Q=5 时整数模型只能选第一段，
		// LP 松弛可以分数使用第二段，因此会触发 TariffSegmentBrancher，用于验证分支树流程。
		data.outsourcingCostFunction.addSegment(0, 10, 2.0, 0.0);
		data.outsourcingCostFunction.addSegment(10, 20, 0.1, 10.0);
		data.setPreprocessedHardWindows();
		data.setPenaltyFunctions();
		return data;
	}

	private double solveArcFlow(Data data) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		ArcFlowModel model = new ArcFlowModel(data);
		model.cplex.setOut(null);
		model.cplex.setWarning(null);
		model.cplex.setParam(IloCplex.DoubleParam.TiLim, 60);
		boolean solved = model.solve();
		if (!solved) {
			model.end();
			throw new AssertionError("ArcFlowModel did not solve case n=" + data.n);
		}
		double objective = model.getObjective();
		model.end();
		return objective;
	}

	private BPCRun solveBPC(Data data, int caseId) {
		Utility.resetCurUpperBound(Utility.big_M);
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = "small-bpc-" + caseId;
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.runALNSForSeed = false;
		config.maxNodes = 2000;
		config.maxExactPricingColumns = 300;
		config.maxHeuristicPricingColumns = 300;
		config.branchSeedColumnLimit = 2000;

		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		TWETSolveResult result = solver.solve();
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);
		return new BPCRun(result, validation, solver.getContext().traceSummary.getBranchCalls(),
				solver.getContext().traceSummary.getPricingRounds());
	}

	private boolean isTreeClosed(TWETSolveResult result) {
		if (result.getStatus() == TWETSolveStatus.FINISHED) {
			return true;
		}
		// 2026-05-19: ROOT_PROCESSED 表示整棵树只处理了根节点；如果此时 bound 和 incumbent 已经相等，
		// 说明根节点已经闭合，不应被测试误判为未完成。
		return result.getStatus() == TWETSolveStatus.ROOT_PROCESSED
				&& Math.abs(result.getBestBound() - result.getIncumbentCost()) <= TOL;
	}

	private static final class BPCRun {
		final TWETSolveResult result;
		final ValidationResult validation;
		final int branchCalls;
		final int pricingRounds;

		BPCRun(TWETSolveResult result, ValidationResult validation, int branchCalls, int pricingRounds) {
			this.result = result;
			this.validation = validation;
			this.branchCalls = branchCalls;
			this.pricingRounds = pricingRounds;
		}
	}
}
