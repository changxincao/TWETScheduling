package HEU;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import Basic.ArcFlowModel;
import Basic.ATIParallel;
import Basic.Data;
import Common.Configure;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import ilog.cplex.IloCplex;

/**
 * 2026-05-17: 小规模随机算例的“精确模型 vs 启发式”批量校验。
 * <p>
 * 这里专门生成带 setup time、setup cost 和外包 G(B) 的小算例，用 ArcFlowModel
 * 求精确解，再用当前 ALNS+VND 启发式多次重启取最好值。测试目的不是替代正式实验，
 * 而是在每次外包/局部搜索逻辑调整后，快速确认小规模上启发式能够回到精确最优。
 */
public class SmallExactHeuristicBatchTest {
	private static final String BASE_INSTANCE = "data/100-2/wet100_001_2m.dat";
	private static final int CASES = 40;
	private static final int RESTARTS = 12;
	private static final double TOL = 1e-5;

	public static void main(String[] args) throws Exception {
		new SmallExactHeuristicBatchTest().run();
	}

	private void run() throws Exception {
		Path outputDir = Path.of("test-results", "exact-heuristic");
		Files.createDirectories(outputDir);
		Path csv = outputDir.resolve("2026-05-17-small-exact-heuristic.csv");

		int matched = 0;
		int parallelMatched = 0;
		double maxGap = 0.0;
		double maxParallelGap = 0.0;
		long totalExactMillis = 0L;
		long totalParallelMillis = 0L;
		long totalHeuristicMillis = 0L;
		long start = System.currentTimeMillis();
		try (BufferedWriter writer = Files.newBufferedWriter(csv)) {
			writer.write("case_id,n,m,arcflow,parallel,heuristic,parallel_gap,heuristic_gap,arcflow_ms,parallel_ms,heuristic_ms,arcflow_outsourced,parallel_outsourced,best_seed,seed_best_count,seed_worst,seed_range,all_seeds_same,status\n");
			for (int caseId = 0; caseId < CASES; caseId++) {
				int n = 6 + caseId % 3;
				Data data = buildRandomCase(caseId, n, 2);

				long exactStart = System.currentTimeMillis();
				ExactResult exact = solveExact(data);
				long exactMillis = System.currentTimeMillis() - exactStart;
				long parallelStart = System.currentTimeMillis();
				ExactResult parallel = solveParallel(data);
				long parallelMillis = System.currentTimeMillis() - parallelStart;
				long heuristicStart = System.currentTimeMillis();
				HeuristicResult heuristic = solveHeuristic(data, caseId);
				long heuristicMillis = System.currentTimeMillis() - heuristicStart;
				totalExactMillis += exactMillis;
				totalParallelMillis += parallelMillis;
				totalHeuristicMillis += heuristicMillis;
				double parallelGap = parallel.objective - exact.objective;
				double heuristicGap = heuristic.objective - exact.objective;
				maxParallelGap = Math.max(maxParallelGap, Math.abs(parallelGap));
				maxGap = Math.max(maxGap, Math.max(0.0, heuristicGap));
				boolean parallelOk = Math.abs(parallelGap) <= TOL;
				boolean heuristicOk = Math.abs(heuristicGap) <= TOL;
				boolean ok = parallelOk && heuristicOk;
				if (parallelOk) {
					parallelMatched++;
				}
				if (ok) {
					matched++;
				}

				writer.write(caseId + "," + data.n + "," + data.m + "," + exact.objective + ","
						+ parallel.objective + "," + heuristic.objective + "," + parallelGap + ","
						+ heuristicGap + "," + exactMillis + "," + parallelMillis + "," + heuristicMillis + ","
						+ exact.outsourcedJobs + "," + parallel.outsourcedJobs + ","
						+ heuristic.bestSeed + "," + heuristic.bestCount + "," + heuristic.worstObjective + ","
						+ heuristic.seedRange + "," + heuristic.allSeedsSame + "," + (ok ? "OK" : "GAP") + "\n");
				System.out.printf("case=%02d n=%d arc=%.6f parallel=%.6f heu=%.6f pGap=%.6g hGap=%.6g seedRange=%.6g bestSeeds=%d/%d arcMs=%d parallelMs=%d heuMs=%d outsourced=%d/%d %s%n",
						caseId, data.n, exact.objective, parallel.objective, heuristic.objective,
						parallelGap, heuristicGap, heuristic.seedRange, heuristic.bestCount, RESTARTS,
						exactMillis, parallelMillis, heuristicMillis,
						exact.outsourcedJobs, parallel.outsourcedJobs,
						ok ? "OK" : "GAP");
			}
		}

		double seconds = (System.currentTimeMillis() - start) / 1000.0;
		System.out.printf("SmallExactHeuristicBatchTest finished: matched=%d/%d, parallelMatched=%d/%d, maxHeuristicGap=%.6g, maxParallelGap=%.6g, avgArcFlowMs=%.2f, avgParallelMs=%.2f, avgHeuristicMs=%.2f, time=%.2fs, csv=%s%n",
				matched, CASES, parallelMatched, CASES, maxGap, maxParallelGap,
				totalExactMillis / (double) CASES, totalParallelMillis / (double) CASES, totalHeuristicMillis / (double) CASES,
				seconds, csv);
		if (matched != CASES) {
			throw new AssertionError("Parallel model or heuristic did not match ArcFlow result in all small cases. See " + csv);
		}
	}

	private Data buildRandomCase(int caseId, int n, int machines) throws IOException {
		Random random = new Random(20260517L + caseId * 97L);
		Data data = loadBaseDataQuietly();
		data.n = n;
		data.m = machines;

		Arrays.fill(data.outsourcingCost, Utility.big_M);
		data.outsourcingCost[0] = 0.0;
		double totalBaseline = 0.0;

		for (int j = 1; j <= n; j++) {
			data.p[j] = 2 + random.nextInt(7);
			double center = 14 + random.nextInt(35);
			double halfWindow = random.nextInt(5);
			data.d_e[j] = Math.max(0, center - halfWindow);
			data.d_l[j] = center + halfWindow;
			data.w_e[j] = 1 + random.nextInt(5);
			data.w_t[j] = 1 + random.nextInt(5);
			data.r[j] = 0;
			data.outsourcingCost[j] = 4 + random.nextInt(15);
			totalBaseline += data.outsourcingCost[j];
		}

		for (int i = 0; i <= n; i++) {
			for (int j = 0; j <= n; j++) {
				if (i == j) {
					data.s[i][j] = 0;
					data.setupCost[i][j] = 0;
				} else {
					data.s[i][j] = random.nextInt(5);
					data.setupCost[i][j] = random.nextInt(8);
				}
			}
		}

		data.CmaxH = computeSafeHorizon(data);
		data.CmaxE = data.CmaxH;
		data.outsourcingCostFunction = new PiecewiseLinearFunction(0, Math.max(1.0, totalBaseline));
		// 这里先用线性 G(B)，便于和 ArcFlow 精确模型做直接一致性校验。
		// 后续如果要测凹折扣函数，可以在这个入口继续扩展多段 tariff。
		double outsourcingRate = 0.45 + 0.08 * (caseId % 7);
		data.outsourcingCostFunction.addSegment(0, Math.max(1.0, totalBaseline), outsourcingRate, 0.0);
		data.setPreprocessedHardWindows();
		data.setPenaltyFunctions();
		return data;
	}

	private Data loadBaseDataQuietly() throws IOException {
		PrintStream oldOut = System.out;
		try {
			// Data 构造函数会运行一次旧的 Cmax 改进启发式并打印信息。
			// 本测试随后会覆盖 n/m/数据字段，因此这里静音只为保持批量对拍输出可读。
			System.setOut(new PrintStream(OutputStream.nullOutputStream()));
			return new Data(BASE_INSTANCE, false, true);
		} finally {
			System.setOut(oldOut);
		}
	}

	private double computeSafeHorizon(Data data) {
		double sumP = 0.0;
		double maxSetup = 0.0;
		double maxDue = 0.0;
		for (int j = 1; j <= data.n; j++) {
			sumP += data.p[j];
			maxDue = Math.max(maxDue, data.d_l[j]);
			for (int i = 0; i <= data.n; i++) {
				maxSetup = Math.max(maxSetup, data.s[i][j]);
			}
		}
		return Math.max(maxDue + sumP + data.n * maxSetup + 20.0, sumP + data.n * maxSetup + 20.0);
	}

	private ExactResult solveExact(Data data) throws Exception {
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
		int outsourced = model.extractOutsourcedJobs().size();
		model.end();
		return new ExactResult(objective, outsourced);
	}

	private ExactResult solveParallel(Data data) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		ATIParallel model = new ATIParallel(data);
		model.getCplex().setOut(null);
		model.getCplex().setWarning(null);
		model.getCplex().setParam(IloCplex.DoubleParam.TiLim, 60);
		boolean solved = model.solve();
		if (!solved) {
			model.end();
			throw new AssertionError("ATIParallel did not solve case n=" + data.n);
		}
		double objective = model.getObj();
		int outsourced = model.extractOutsourcedJobs().size();
		model.end();
		return new ExactResult(objective, outsourced);
	}

	private HeuristicResult solveHeuristic(Data data, int caseId) {
		int oldMaxNoImp = EngineALNS.maxNoImpIterN;
		int oldMaxRatioChange = EngineALNS.maxremRatioChangeN;
		int oldRatioChangeNoImp = EngineALNS.ratioChangeNoImpIterN;
		try {
			EngineALNS.maxNoImpIterN = 24;
			EngineALNS.maxremRatioChangeN = 4;
			EngineALNS.ratioChangeNoImpIterN = 8;

			double best = Utility.big_M;
			double worst = -Utility.big_M;
			long bestSeed = -1;
			int bestCount = 0;
			for (int restart = 0; restart < RESTARTS; restart++) {
				long seed = 202605170000L + caseId * 1000L + restart;
				Utility.resetCurUpperBound(Utility.big_M);
				Utility.rng = new Random(seed);
				EngineALNS.rng = new Random(seed ^ 0x5DEECE66DL);
				Move.Recordreset();
				data.configure = new Configure();

				Solution solution = new Solution(data);
				solution.setInitSolution();
				solution.initialize_function();
				for (int m = 0; m < data.m; m++) {
					solution.updateInformationM(m);
				}
				data.configure.updateBestSolution(solution);

				EngineALNS engine = new EngineALNS(data, solution);
				engine.search();
				double value = data.configure.bestSolution.curCost;
				if (Utility.compareLt(value, best)) {
					best = value;
					bestSeed = seed;
					bestCount = 1;
				} else if (Math.abs(value - best) <= TOL) {
					bestCount++;
				}
				worst = Math.max(worst, value);
			}
			return new HeuristicResult(best, worst, worst - best, bestSeed, bestCount);
		} finally {
			EngineALNS.maxNoImpIterN = oldMaxNoImp;
			EngineALNS.maxremRatioChangeN = oldMaxRatioChange;
			EngineALNS.ratioChangeNoImpIterN = oldRatioChangeNoImp;
		}
	}

	private static final class ExactResult {
		final double objective;
		final int outsourcedJobs;

		ExactResult(double objective, int outsourcedJobs) {
			this.objective = objective;
			this.outsourcedJobs = outsourcedJobs;
		}
	}

	private static final class HeuristicResult {
		final double objective;
		final double worstObjective;
		final double seedRange;
		final long bestSeed;
		final int bestCount;
		final boolean allSeedsSame;

		HeuristicResult(double objective, double worstObjective, double seedRange, long bestSeed, int bestCount) {
			this.objective = objective;
			this.worstObjective = worstObjective;
			this.seedRange = seedRange;
			this.bestSeed = bestSeed;
			this.bestCount = bestCount;
			this.allSeedsSame = seedRange <= TOL;
		}
	}
}
