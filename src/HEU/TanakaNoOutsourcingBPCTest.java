package HEU;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import Output.BPCSolutionValidator;
import Output.ValidationResult;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;

/**
 * 2026-05-21: 用 Tanaka 扩展多机器实例做 no-outsourcing BPC 诊断。
 * 数据文件不含 OUTSOURCING_COST 块时，Data 默认把 outsourcingCost[j] 设为 big_M，
 * 因而 RMP 中 y_j 会被固定为 0，只允许内部机器调度。
 */
public class TanakaNoOutsourcingBPCTest {

	public static void main(String[] args) throws Exception {
		String instance = args.length > 0 ? args[0] : "data/50-2/wet050_001_2m.dat";
		boolean bidirectional = Boolean.getBoolean("twet.bpc.bidirectional");
		boolean zeroSetup = Boolean.getBoolean("twet.bpc.zeroSetup");
		int maxNodes = Integer.getInteger("twet.bpc.maxNodes", 20000);

		Utility.resetCurUpperBound(Utility.big_M);
		Data data = loadTanakaMultiMachine(instance, zeroSetup);
		int disabledOutsourcing = 0;
		for (int j = 1; j <= data.n; j++) {
			if (Utility.isBigMValue(data.outsourcingCost[j])) {
				disabledOutsourcing++;
			}
		}

		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = Path.of(instance).getFileName().toString().replace(".dat", "") + "-no-outsourcing"
				+ (zeroSetup ? "-zero-setup" : "");
		config.enableBPCConsoleOutput = Boolean.getBoolean("twet.bpc.verbose");
		config.writeBPCResultFiles = false;
		// 2026-05-21: 这里用手工 loader 绕开 Data.debug_set()，不能复用 base Data 构造时留下的旧 bestSolution。
		// 因此让 BPC 在当前 50-job 实例上重新构造 seed；默认不额外跑 ALNS，避免诊断时把时间耗在 seed 优化上。
		// 如需复核更强初始解，可用 -Dtwet.bpc.seedALNS=true 打开。
		config.reuseConfiguredBestSolution = false;
		config.runALNSForSeed = Boolean.getBoolean("twet.bpc.seedALNS");
		config.maxNodes = maxNodes;
		config.maxInitialColumns = 5000;
		config.maxHeuristicPricingColumns = 200;
		config.maxExactPricingColumns = 200;
		config.branchSeedColumnLimit = 5000;
		config.enableBidirectionalPricing = bidirectional;

		long start = System.currentTimeMillis();
		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		TWETSolveResult result = solver.solve();
		long millis = System.currentTimeMillis() - start;
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);

		Path outputDir = Path.of("test-results", "bpc");
		Files.createDirectories(outputDir);
		Path csv = outputDir.resolve("2026-05-21-tanaka-no-outsourcing-bpc.csv");
		String line = String.join(",",
				"instance", "n", "m", "outsourcing_disabled", "zero_setup", "bidirectional", "status", "incumbent", "bound",
				"gap_percent", "nodes", "branches", "pricing_rounds", "generated_columns", "pool_size", "valid",
				"millis");
		String values = String.join(",",
				instance, String.valueOf(data.n), String.valueOf(data.m), String.valueOf(disabledOutsourcing),
				String.valueOf(zeroSetup), String.valueOf(bidirectional), String.valueOf(result.getStatus()), String.valueOf(result.getIncumbentCost()),
				String.valueOf(result.getBestBound()), String.valueOf(gapPercent(result.getBestBound(), result.getIncumbentCost())),
				String.valueOf(result.getProcessedNodes()),
				String.valueOf(solver.getContext().traceSummary.getBranchCalls()),
				String.valueOf(solver.getContext().traceSummary.getPricingRounds()),
				String.valueOf(result.getGeneratedColumns()), String.valueOf(solver.getContext().pool.size()),
				String.valueOf(validation.isFeasible()), String.valueOf(millis));
		Files.writeString(csv, line + System.lineSeparator() + values + System.lineSeparator());

		System.out.printf(
				"tanakaNoOutsource instance=%s n=%d m=%d disabledOutsourcing=%d zeroSetup=%s bidirectional=%s status=%s incumbent=%.6f bound=%.6f gap%%=%.4f nodes=%d branches=%d pricing=%d cols=%d pool=%d valid=%s ms=%d csv=%s%n",
				instance, data.n, data.m, disabledOutsourcing, zeroSetup, bidirectional, result.getStatus(),
				result.getIncumbentCost(), result.getBestBound(), gapPercent(result.getBestBound(), result.getIncumbentCost()),
				result.getProcessedNodes(), solver.getContext().traceSummary.getBranchCalls(),
				solver.getContext().traceSummary.getPricingRounds(), result.getGeneratedColumns(),
				solver.getContext().pool.size(), validation.isFeasible(), millis, csv);
	}

	private static double gapPercent(double bound, double incumbent) {
		if (!Double.isFinite(bound) || !Double.isFinite(incumbent) || Math.abs(incumbent) <= 1e-9) {
			return Double.NaN;
		}
		return Math.max(0.0, (incumbent - bound) / Math.abs(incumbent) * 100.0);
	}

	private static Data loadTanakaMultiMachine(String instance, boolean zeroSetup) throws IOException {
		Data data = loadBaseDataQuietly();
		try (BufferedReader reader = Files.newBufferedReader(Path.of(instance))) {
			String[] header = split(reader.readLine());
			int n = Integer.parseInt(header[0]);
			int m = Integer.parseInt(header[1]);
			data.n = n;
			data.m = m;
			for (int j = 1; j <= n; j++) {
				String[] tokens = split(reader.readLine());
				data.p[j] = Double.parseDouble(tokens[0]);
				data.d_e[j] = Double.parseDouble(tokens[1]);
				data.d_l[j] = data.d_e[j];
				data.w_e[j] = Double.parseDouble(tokens[2]);
				data.w_t[j] = Double.parseDouble(tokens[3]);
				data.r[j] = 0;
			}
			Arrays.fill(data.outsourcingCost, Utility.big_M);
			data.outsourcingCost[0] = 0;
			for (int i = 0; i <= n; i++) {
				Arrays.fill(data.s[i], 0);
				Arrays.fill(data.setupCost[i], 0);
			}
			String line = nextNonEmptyLine(reader);
			if (line != null && "SETUP".equalsIgnoreCase(line.trim())) {
				for (int i = 0; i <= n; i++) {
					String[] row = split(reader.readLine());
					for (int j = 0; j <= n; j++) {
						data.s[i][j] = Double.parseDouble(row[j]);
					}
				}
			}
		}
		if (zeroSetup) {
			// 2026-05-21: 诊断开关，只用于比较 setup time 对 BPC pricing 难度的影响。
			// 清零后仍使用同一批 Tanaka job 数据、同一 no-outsourcing 设置，不改生产数据读取逻辑。
			for (int i = 0; i <= data.n; i++) {
				Arrays.fill(data.s[i], 0, data.n + 1, 0);
			}
		}
		data.CmaxH = computeSafeHorizon(data);
		data.CmaxE = data.CmaxH;
		data.outsourcingCostFunction = new PiecewiseLinearFunction(0, 1);
		data.outsourcingCostFunction.addSegment(0, 1, 1, 0);
		data.setPreprocessedHardWindows();
		data.preprocessInfeasibleArcsByHardWindows();
		data.setPenaltyFunctions();
		for (int i = 1; i <= data.n; i++) {
			double minSi = Utility.big_M;
			for (int j = 0; j <= data.n; j++) {
				minSi = Math.min(minSi, data.s[j][i]);
			}
			data.min_s[i] = minSi;
		}
		data.precomputeSetupCostAdvantages();
		return data;
	}

	private static Data loadBaseDataQuietly() throws IOException {
		PrintStream oldOut = System.out;
		try {
			System.setOut(new PrintStream(OutputStream.nullOutputStream()));
			return new Data("data/100-2/wet100_001_2m.dat", false, true);
		} finally {
			System.setOut(oldOut);
		}
	}

	private static double computeSafeHorizon(Data data) {
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

	private static String nextNonEmptyLine(BufferedReader reader) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (!line.trim().isEmpty()) {
				return line;
			}
		}
		return null;
	}

	private static String[] split(String line) {
		return line.trim().split("\\s+");
	}
}
