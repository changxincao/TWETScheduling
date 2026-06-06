package HEU;

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
 * 2026-05-26: 本地诊断入口，用同一个 21 任务 no-outsourcing 算例比较单向/双向 time 出队。
 */
public class SingleBidirectionalTimeQueueComparisonTest {

	private static final String HEURISTIC_ENGINE = "HeuristicPricing";
	private static final String SINGLE_EXACT_ENGINE = "PaperDominanceExactPricing";
	private static final String BIDIRECTIONAL_EXACT_ENGINE = "GCNGBBStyleBidirectionalPricing";

	public static void main(String[] args) throws Exception {
		Path instanceDir = Path.of(System.getProperty("twet.bpc.timeCompare.dir",
				"test-results/bpc/2026-05-24-no-outsourcing-20-21"));
		String caseFilter = System.getProperty("twet.bpc.timeCompare.case", "wet021");
		String modeFilter = System.getProperty("twet.bpc.timeCompare.mode", "both");
		String resultStem = System.getProperty("twet.bpc.timeCompare.name",
				"2026-05-26-single-vs-bidir-time-wet021");
		Path outputDir = Path.of("test-results", "bpc", resultStem);
		Files.createDirectories(outputDir);
		Path csv = Path.of("test-results", "bpc", resultStem + ".csv");

		List<Path> instances = listInstances(instanceDir, caseFilter);
		if (instances.isEmpty()) {
			throw new IllegalStateException("No .dat instance matched " + caseFilter + " under " + instanceDir);
		}

		ArrayList<RunRecord> records = new ArrayList<RunRecord>();
		ArrayList<String> lines = new ArrayList<String>();
		lines.add(
				"case,mode,status,incumbent,bound,gap,nodes,pricing,cols,pool,solve_s,root_s,heuristic_s,heuristic_calls,exact_engine,exact_s,exact_calls,master_lp_s,valid,log");
		for (Path instance : instances) {
			if (shouldRunSingle(modeFilter)) {
				RunRecord single = runOne(instance, false, outputDir);
				records.add(single);
				lines.add(single.toCsvLine());
			}
			if (shouldRunBidirectional(modeFilter)) {
				RunRecord bidirectional = runOne(instance, true, outputDir);
				records.add(bidirectional);
				lines.add(bidirectional.toCsvLine());
			}
		}

		Files.write(csv, lines);
		printSummary(records, csv);
	}

	private static boolean shouldRunSingle(String modeFilter) {
		return "both".equalsIgnoreCase(modeFilter) || "single".equalsIgnoreCase(modeFilter);
	}

	private static boolean shouldRunBidirectional(String modeFilter) {
		return "both".equalsIgnoreCase(modeFilter) || "bidir".equalsIgnoreCase(modeFilter)
				|| "bidirectional".equalsIgnoreCase(modeFilter);
	}

	private static List<Path> listInstances(Path instanceDir, String caseFilter) throws Exception {
		ArrayList<Path> instances = new ArrayList<Path>();
		try (var stream = Files.newDirectoryStream(instanceDir, "*.dat")) {
			for (Path path : stream) {
				if (caseFilter == null || caseFilter.isBlank()
						|| path.getFileName().toString().contains(caseFilter)) {
					instances.add(path);
				}
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

		String mode = bidirectional ? "bidirTime" : "singleTime";
		String exactEngine = bidirectional ? BIDIRECTIONAL_EXACT_ENGINE : SINGLE_EXACT_ENGINE;
		String joinBestMode = config.bidirectionalJoinBestThresholdMode == null
				? "zero" : config.bidirectionalJoinBestThresholdMode.trim();
		if (bidirectional && !joinBestMode.isEmpty() && !"zero".equalsIgnoreCase(joinBestMode)) {
			mode += "-" + joinBestMode;
		}
		Path log = outputDir.resolve(stripDat(instance.getFileName().toString()) + "-" + mode + ".log");
		Files.write(log, summary.getEventLines());
		return new RunRecord(stripDat(instance.getFileName().toString()), mode, result.getStatus().toString(),
				result.getIncumbentCost(), result.getBestBound(),
				TanakaNoOutsourcingBPCTest.gapPercent(result.getBestBound(), result.getIncumbentCost()),
				result.getProcessedNodes(), summary.getPricingRounds(), result.getGeneratedColumns(),
				solver.getContext().pool.size(), summary.getSolveTimeSeconds(), summary.getRootSolveTimeSeconds(),
				seconds(summary.getPricingTimeNanos(), HEURISTIC_ENGINE),
				count(summary.getPricingCallCount(), HEURISTIC_ENGINE), exactEngine,
				seconds(summary.getPricingTimeNanos(), exactEngine), count(summary.getPricingCallCount(), exactEngine),
				totalSeconds(summary.getMasterLpTimeNanos()), validation.isFeasible(), log.toString().replace('/', '\\'));
	}

	private static TWETBPCConfig buildConfig(Path instance, boolean bidirectional) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = stripDat(instance.getFileName().toString()) + "-no-outsourcing-time";
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.reuseConfiguredBestSolution = false;
		config.runALNSForSeed = false;
		config.maxNodes = Integer.getInteger("twet.bpc.timeCompare.maxNodes", 20000);
		config.maxHeuristicPricingColumns = Integer.getInteger("twet.bpc.timeCompare.maxHeuristicColumns", 100000);
		config.heuristicPricingPoolSize = Integer.getInteger("twet.bpc.timeCompare.heuristicPoolSize", 100000);
		config.maxExactPricingColumns = Integer.getInteger("twet.bpc.timeCompare.maxExactColumns", 100000);
		config.branchSeedColumnLimit = Integer.getInteger("twet.bpc.timeCompare.branchSeedColumnLimit", 20000);
		config.enableBidirectionalPricing = bidirectional;
		config.useGCNGBBStyleBidirectionalPricing = true;
		config.forwardLabelQueueOrdering = "time";
		config.bidirectionalLabelQueueOrdering = "time";
		config.bidirectionalJoinBestThresholdMode = System.getProperty(
				"twet.bpc.timeCompare.joinBestMode", config.bidirectionalJoinBestThresholdMode);
		config.bidirectionalRootLocalHorizonMidpointRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.timeCompare.midpointRatio",
				Double.toString(config.bidirectionalRootLocalHorizonMidpointRatio)));
		config.bidirectionalNoWindowHorizonFactor = Double.parseDouble(System.getProperty(
				"twet.bpc.timeCompare.noWindowHorizonFactor",
				Double.toString(config.bidirectionalNoWindowHorizonFactor)));
		return config;
	}

	private static void resetHeuristicSeed(Path instance) {
		long seed = 202605260100L + stripDat(instance.getFileName().toString()).hashCode();
		Utility.rng = new Random(seed);
		EngineALNS.rng = new Random(seed ^ 0x5DEECE66DL);
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
		System.out.println("SingleBidirectionalTimeQueueComparisonTest summary:");
		for (RunRecord record : records) {
			System.out.printf(Locale.US,
					"case=%s mode=%s status=%s obj=%.6f bound=%.6f solve=%.3fs exact=%s %.3fs calls=%d valid=%s log=%s%n",
					record.caseName, record.mode, record.status, record.incumbent, record.bound, record.solveSeconds,
					record.exactEngine, record.exactSeconds, record.exactCalls, record.valid, record.logPath);
		}
		if (records.size() == 2) {
			RunRecord single = records.get(0);
			RunRecord bidirectional = records.get(1);
			System.out.printf(Locale.US,
					"speedup: exact %.2fx, solve %.2fx, exact_diff %.3fs, solve_diff %.3fs%n",
					single.exactSeconds / bidirectional.exactSeconds, single.solveSeconds / bidirectional.solveSeconds,
					single.exactSeconds - bidirectional.exactSeconds, single.solveSeconds - bidirectional.solveSeconds);
		}
		System.out.println("csv=" + csv);
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

	private static String quote(String value) {
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	private static final class RunRecord {
		final String caseName;
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
		final double heuristicSeconds;
		final int heuristicCalls;
		final String exactEngine;
		final double exactSeconds;
		final int exactCalls;
		final double masterLpSeconds;
		final boolean valid;
		final String logPath;

		RunRecord(String caseName, String mode, String status, double incumbent, double bound, double gap, int nodes,
				int pricingRounds, int generatedColumns, int poolSize, double solveSeconds, double rootSeconds,
				double heuristicSeconds, int heuristicCalls, String exactEngine, double exactSeconds, int exactCalls,
				double masterLpSeconds, boolean valid, String logPath) {
			this.caseName = caseName;
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
			return String.join(",", quote(caseName), quote(mode), quote(status), fmt(incumbent), fmt(bound), fmt(gap),
					String.valueOf(nodes), String.valueOf(pricingRounds), String.valueOf(generatedColumns),
					String.valueOf(poolSize), fmt(solveSeconds), fmt(rootSeconds), fmt(heuristicSeconds),
					String.valueOf(heuristicCalls), quote(exactEngine), fmt(exactSeconds),
					String.valueOf(exactCalls), fmt(masterLpSeconds), String.valueOf(valid), quote(logPath));
		}
	}
}
