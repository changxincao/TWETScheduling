package HEU;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import Basic.Data;
import Common.Utility;
import Output.BPCSolutionValidator;
import Output.BPCTraceSummary;
import Output.ValidationResult;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;

/**
 * 2026-05-24: 批量对比 no-outsourcing 场景下单向/双向 exact pricing 的完整 BPC 表现。
 * <p>
 * 这个入口只服务本地诊断，不改正式求解流程。它会复用现成的 15 任务 no-outsourcing 子算例，
 * 分别跑 single / bidirectional 两种模式，并把 root 时间、pricing 时间和最终目标写入 CSV。
 */
public class TanakaNoOutsourcingBPCComparisonBatch {

	private static final String HEURISTIC_ENGINE = "HeuristicPricing";
	private static final String SINGLE_EXACT_ENGINE = "PaperDominanceExactPricing";
	private static final String BIDIRECTIONAL_EXACT_ENGINE = "BidirectionalPricing";

	public static void main(String[] args) throws Exception {
		Path instanceDir = Path.of(System.getProperty("twet.bpc.compare.dir",
				"test-results/bpc/2026-05-23-no-outsourcing-15"));
		String resultStem = System.getProperty("twet.bpc.compare.name", "2026-05-24-no-outsourcing-15-bidir-vs-single-rerun");
		Path outputDir = Path.of("test-results", "bpc", resultStem);
		Files.createDirectories(outputDir);
		Path csv = Path.of("test-results", "bpc", resultStem + ".csv");

		List<Path> instances = listInstances(instanceDir);
		if (instances.isEmpty()) {
			throw new IllegalStateException("No .dat instance found under " + instanceDir);
		}

		ArrayList<RunRecord> records = new ArrayList<RunRecord>();
		ArrayList<String> lines = new ArrayList<String>();
		lines.add(
				"case,mode,status,incumbent,bound,gap,nodes,pricing,cols,pool,solve_s,root_s,initial_incumbent,initial_is_optimal,time_to_optimum_s,time_to_optimum_basis,heuristic_s,heuristic_calls,exact_engine,exact_s,exact_calls,master_lp_s,valid,log");

		for (Path instance : instances) {
			RunRecord single = runOne(instance, false, outputDir);
			RunRecord bidirectional = runOne(instance, true, outputDir);
			records.add(single);
			records.add(bidirectional);
			lines.add(single.toCsvLine());
			lines.add(bidirectional.toCsvLine());
		}

		Files.write(csv, lines);
		printSummary(records, csv);
	}

	private static List<Path> listInstances(Path instanceDir) throws IOException {
		ArrayList<Path> instances = new ArrayList<Path>();
		try (var stream = Files.newDirectoryStream(instanceDir, "*.dat")) {
			for (Path path : stream) {
				instances.add(path);
			}
		}
		instances.sort(Comparator.comparing(path -> path.getFileName().toString()));
		return instances;
	}

	private static RunRecord runOne(Path instance, boolean bidirectional, Path outputDir) throws Exception {
		resetHeuristicSeed(instance);
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(instance.toString(), false);
		TWETBPCConfig config = buildConfig(instance, bidirectional);
		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		TWETSolveResult result = solver.solve();
		BPCTraceSummary summary = solver.getContext().traceSummary;
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);

		String mode = bidirectional ? "bidir" : "single";
		Path log = outputDir.resolve(stripDat(instance.getFileName().toString()) + "-" + mode + ".log");
		Files.write(log, summary.getEventLines());

		String exactEngine = bidirectional ? BIDIRECTIONAL_EXACT_ENGINE : SINGLE_EXACT_ENGINE;
		double initialIncumbent = summary.getInitialIncumbentCost();
		boolean initialIsOptimal = isSameObjective(initialIncumbent, result.getIncumbentCost());
		String timeToOptimumBasis = initialIsOptimal ? "initial"
				: result.getProcessedNodes() == 1 ? "root_close" : "solve_finish";
		double timeToOptimum = initialIsOptimal ? 0.0
				: result.getProcessedNodes() == 1 ? summary.getRootSolveTimeSeconds() : summary.getSolveTimeSeconds();

		return new RunRecord(stripDat(instance.getFileName().toString()), mode, result.getStatus().toString(),
				result.getIncumbentCost(), result.getBestBound(),
				TanakaNoOutsourcingBPCTest.gapPercent(result.getBestBound(), result.getIncumbentCost()),
				result.getProcessedNodes(), summary.getPricingRounds(), result.getGeneratedColumns(),
				solver.getContext().pool.size(), summary.getSolveTimeSeconds(), summary.getRootSolveTimeSeconds(),
				initialIncumbent, initialIsOptimal, timeToOptimum, timeToOptimumBasis,
				seconds(summary.getPricingTimeNanos(), HEURISTIC_ENGINE), count(summary.getPricingCallCount(),
						HEURISTIC_ENGINE),
				exactEngine, seconds(summary.getPricingTimeNanos(), exactEngine), count(summary.getPricingCallCount(),
						exactEngine),
				totalSeconds(summary.getMasterLpTimeNanos()), validation.isFeasible(), log.toString().replace('/', '\\'));
	}

	private static void resetHeuristicSeed(Path instance) {
		long seed = 202605240000L + stripDat(instance.getFileName().toString()).hashCode();
		Utility.rng = new Random(seed);
		EngineALNS.rng = new Random(seed ^ 0x5DEECE66DL);
	}

	private static TWETBPCConfig buildConfig(Path instance, boolean bidirectional) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = stripDat(instance.getFileName().toString()) + "-no-outsourcing";
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.reuseConfiguredBestSolution = false;
		config.runALNSForSeed = false;
		config.maxNodes = Integer.getInteger("twet.bpc.compare.maxNodes", 20000);
		// 2026-05-24: 这里临时放大列池和 exact 返回上限，避免工程阈值先截断对比结果。
		config.maxHeuristicPricingColumns = Integer.getInteger("twet.bpc.compare.maxHeuristicColumns", 100000);
		config.heuristicPricingPoolSize = Integer.getInteger("twet.bpc.compare.heuristicPoolSize", 100000);
		config.maxExactPricingColumns = Integer.getInteger("twet.bpc.compare.maxExactColumns", 100000);
		config.branchSeedColumnLimit = Integer.getInteger("twet.bpc.compare.branchSeedColumnLimit", 20000);
		config.enableBidirectionalPricing = bidirectional;
		return config;
	}

	private static boolean isSameObjective(double a, double b) {
		if (!Double.isFinite(a) || !Double.isFinite(b)) {
			return false;
		}
		return Math.abs(a - b) <= 1e-6;
	}

	private static int count(Map<String, Integer> counter, String key) {
		Integer value = counter.get(key);
		return value == null ? 0 : value.intValue();
	}

	private static double seconds(Map<String, Long> counter, String key) {
		Long value = counter.get(key);
		return value == null ? 0.0 : value.longValue() / 1_000_000_000.0;
	}

	private static double totalSeconds(Map<String, Long> counter) {
		long total = 0L;
		for (Long value : counter.values()) {
			total += value.longValue();
		}
		return total / 1_000_000_000.0;
	}

	private static void printSummary(List<RunRecord> records, Path csv) {
		double singleSolve = 0.0;
		double bidirSolve = 0.0;
		double singleExact = 0.0;
		double bidirExact = 0.0;
		int singleCount = 0;
		int bidirCount = 0;
		int objectiveMatch = 0;
		int boundMatch = 0;
		int optimalFromInitial = 0;
		for (int i = 0; i + 1 < records.size(); i += 2) {
			RunRecord single = "single".equals(records.get(i).mode) ? records.get(i) : records.get(i + 1);
			RunRecord bidir = "bidir".equals(records.get(i).mode) ? records.get(i) : records.get(i + 1);
			if (isSameObjective(single.incumbent, bidir.incumbent)) {
				objectiveMatch++;
			}
			if (isSameObjective(single.bound, bidir.bound)) {
				boundMatch++;
			}
		}
		for (RunRecord record : records) {
			if ("single".equals(record.mode)) {
				singleSolve += record.solveSeconds;
				singleExact += record.exactSeconds;
				singleCount++;
			} else {
				bidirSolve += record.solveSeconds;
				bidirExact += record.exactSeconds;
				bidirCount++;
			}
			if (record.initialIsOptimal) {
				optimalFromInitial++;
			}
		}
		System.out.printf(Locale.US,
				"TanakaNoOutsourcingBPCComparisonBatch finished: cases=%d objectiveMatch=%d boundMatch=%d avgSingleSolve=%.3fs avgBidirSolve=%.3fs avgSingleExact=%.3fs avgBidirExact=%.3fs initialOptimalRuns=%d csv=%s%n",
				singleCount, objectiveMatch, boundMatch, singleCount == 0 ? 0.0 : singleSolve / singleCount,
				bidirCount == 0 ? 0.0 : bidirSolve / bidirCount,
				singleCount == 0 ? 0.0 : singleExact / singleCount,
				bidirCount == 0 ? 0.0 : bidirExact / bidirCount, optimalFromInitial, csv);
	}

	private static String stripDat(String name) {
		return name.endsWith(".dat") ? name.substring(0, name.length() - 4) : name;
	}

	private static String fmt(double value) {
		if (Double.isNaN(value)) {
			return "NaN";
		}
		if (Double.isInfinite(value)) {
			return value > 0 ? "INF" : "-INF";
		}
		return String.format(Locale.US, "%.6f", value);
	}

	private static final class RunRecord {
		final String instanceName;
		final String mode;
		final String status;
		final double incumbent;
		final double bound;
		final double gap;
		final int nodes;
		final int pricingRounds;
		final int generatedColumns;
		final int poolSize;
		final double solveSeconds;
		final double rootSeconds;
		final double initialIncumbent;
		final boolean initialIsOptimal;
		final double timeToOptimumSeconds;
		final String timeToOptimumBasis;
		final double heuristicSeconds;
		final int heuristicCalls;
		final String exactEngine;
		final double exactSeconds;
		final int exactCalls;
		final double masterLpSeconds;
		final boolean valid;
		final String logPath;

		RunRecord(String instanceName, String mode, String status, double incumbent, double bound, double gap, int nodes,
				int pricingRounds, int generatedColumns, int poolSize, double solveSeconds, double rootSeconds,
				double initialIncumbent, boolean initialIsOptimal, double timeToOptimumSeconds,
				String timeToOptimumBasis, double heuristicSeconds, int heuristicCalls, String exactEngine,
				double exactSeconds, int exactCalls, double masterLpSeconds, boolean valid, String logPath) {
			this.instanceName = instanceName;
			this.mode = mode;
			this.status = status;
			this.incumbent = incumbent;
			this.bound = bound;
			this.gap = gap;
			this.nodes = nodes;
			this.pricingRounds = pricingRounds;
			this.generatedColumns = generatedColumns;
			this.poolSize = poolSize;
			this.solveSeconds = solveSeconds;
			this.rootSeconds = rootSeconds;
			this.initialIncumbent = initialIncumbent;
			this.initialIsOptimal = initialIsOptimal;
			this.timeToOptimumSeconds = timeToOptimumSeconds;
			this.timeToOptimumBasis = timeToOptimumBasis;
			this.heuristicSeconds = heuristicSeconds;
			this.heuristicCalls = heuristicCalls;
			this.exactEngine = exactEngine;
			this.exactSeconds = exactSeconds;
			this.exactCalls = exactCalls;
			this.masterLpSeconds = masterLpSeconds;
			this.valid = valid;
			this.logPath = logPath;
		}

		String toCsvLine() {
			return quote(instanceName) + "," + quote(mode) + "," + quote(status) + "," + fmt(incumbent) + ","
					+ fmt(bound) + "," + fmt(gap) + "," + nodes + "," + pricingRounds + "," + generatedColumns + ","
					+ poolSize + "," + fmt(solveSeconds) + "," + fmt(rootSeconds) + "," + fmt(initialIncumbent) + ","
					+ initialIsOptimal + "," + fmt(timeToOptimumSeconds) + "," + quote(timeToOptimumBasis) + ","
					+ fmt(heuristicSeconds) + "," + heuristicCalls + "," + quote(exactEngine) + ","
					+ fmt(exactSeconds) + "," + exactCalls + "," + fmt(masterLpSeconds) + "," + valid + ","
					+ quote(logPath);
		}

		private static String quote(String value) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
	}
}
