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
 * 2026-05-26: 诊断 GCNGBB-style final-join 与旧 hybrid-B eager-join 的双向 pricing 表现。
 * 该入口只用于本地实验，不改变正式求解流程。
 */
public class BidirectionalQueueStrategyComparisonTest {

	private static final String OLD_ENGINE = "BidirectionalPricing";
	private static final String GCN_ENGINE = "GCNGBBStyleBidirectionalPricing";

	public static void main(String[] args) throws Exception {
		Path instanceDir = Path.of(System.getProperty("twet.bpc.queueCompare.dir",
				"test-results/bpc/2026-05-24-no-outsourcing-20-21"));
		String resultStem = System.getProperty("twet.bpc.queueCompare.name",
				"2026-05-26-bidir-queue-strategy-20-21");
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
				"case,mode,queue_ordering,use_gcn_style,status,incumbent,bound,gap,nodes,pricing,cols,pool,solve_s,root_s,heuristic_s,heuristic_calls,exact_engine,exact_s,exact_calls,master_lp_s,valid,log");

		for (Path instance : instances) {
			records.add(runOne(instance, "oldHybrid", false, "reducedCost", outputDir));
			records.add(runOne(instance, "gcnReducedCost", true, "reducedCost", outputDir));
			records.add(runOne(instance, "gcnTime", true, "time", outputDir));
			records.add(runOne(instance, "gcnReachableSize", true, "reachableSize", outputDir));
		}
		for (RunRecord record : records) {
			lines.add(record.toCsvLine());
		}
		Files.write(csv, lines);
		printSummary(records, csv);
	}

	private static List<Path> listInstances(Path instanceDir) throws Exception {
		ArrayList<Path> instances = new ArrayList<Path>();
		try (var stream = Files.newDirectoryStream(instanceDir, "*.dat")) {
			for (Path path : stream) {
				instances.add(path);
			}
		}
		instances.sort(Comparator.comparing(path -> path.getFileName().toString()));
		return instances;
	}

	private static RunRecord runOne(Path instance, String mode, boolean useGcnStyle, String queueOrdering,
			Path outputDir) throws Exception {
		resetHeuristicSeed(instance);
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(instance.toString(), false);
		TWETBPCConfig config = buildConfig(instance, useGcnStyle, queueOrdering);
		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		TWETSolveResult result = solver.solve();
		BPCTraceSummary summary = solver.getContext().traceSummary;
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);

		String caseName = stripDat(instance.getFileName().toString());
		Path log = outputDir.resolve(caseName + "-" + mode + ".log");
		Files.write(log, summary.getEventLines());
		String exactEngine = useGcnStyle ? GCN_ENGINE : OLD_ENGINE;
		return new RunRecord(caseName, mode, queueOrdering, useGcnStyle, result.getStatus().toString(),
				result.getIncumbentCost(), result.getBestBound(),
				TanakaNoOutsourcingBPCTest.gapPercent(result.getBestBound(), result.getIncumbentCost()),
				result.getProcessedNodes(), summary.getPricingRounds(), result.getGeneratedColumns(),
				solver.getContext().pool.size(), summary.getSolveTimeSeconds(), summary.getRootSolveTimeSeconds(),
				seconds(summary.getPricingTimeNanos(), "HeuristicPricing"),
				count(summary.getPricingCallCount(), "HeuristicPricing"), exactEngine,
				seconds(summary.getPricingTimeNanos(), exactEngine), count(summary.getPricingCallCount(), exactEngine),
				totalSeconds(summary.getMasterLpTimeNanos()), validation.isFeasible(), log.toString().replace('/', '\\'));
	}

	private static TWETBPCConfig buildConfig(Path instance, boolean useGcnStyle, String queueOrdering) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = stripDat(instance.getFileName().toString()) + "-no-outsourcing-" + queueOrdering;
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.reuseConfiguredBestSolution = false;
		config.runALNSForSeed = false;
		config.maxNodes = Integer.getInteger("twet.bpc.queueCompare.maxNodes", 20000);
		config.maxHeuristicPricingColumns = Integer.getInteger("twet.bpc.queueCompare.maxHeuristicColumns", 100000);
		config.heuristicPricingPoolSize = Integer.getInteger("twet.bpc.queueCompare.heuristicPoolSize", 100000);
		config.maxExactPricingColumns = Integer.getInteger("twet.bpc.queueCompare.maxExactColumns", 100000);
		config.branchSeedColumnLimit = Integer.getInteger("twet.bpc.queueCompare.branchSeedColumnLimit", 20000);
		config.enableBidirectionalPricing = true;
		config.useGCNGBBStyleBidirectionalPricing = useGcnStyle;
		config.bidirectionalLabelQueueOrdering = queueOrdering;
		return config;
	}

	private static void resetHeuristicSeed(Path instance) {
		long seed = 202605260000L + stripDat(instance.getFileName().toString()).hashCode();
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
		System.out.println("BidirectionalQueueStrategyComparisonTest summary:");
		for (RunRecord record : records) {
			System.out.printf(Locale.US,
					"case=%s mode=%s status=%s obj=%.6f bound=%.6f solve=%.3fs exact=%s %.3fs calls=%d valid=%s log=%s%n",
					record.caseName, record.mode, record.status, record.incumbent, record.bound, record.solveSeconds,
					record.exactEngine, record.exactSeconds, record.exactCalls, record.valid, record.logPath);
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

	private static final class RunRecord {
		final String caseName;
		final String mode;
		final String queueOrdering;
		final boolean useGcnStyle;
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

		RunRecord(String caseName, String mode, String queueOrdering, boolean useGcnStyle, String status,
				double incumbent, double bound, double gap, int nodes, int pricingRounds, int generatedColumns,
				int poolSize, double solveSeconds, double rootSeconds, double heuristicSeconds, int heuristicCalls,
				String exactEngine, double exactSeconds, int exactCalls, double masterLpSeconds, boolean valid,
				String logPath) {
			this.caseName = caseName;
			this.mode = mode;
			this.queueOrdering = queueOrdering;
			this.useGcnStyle = useGcnStyle;
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
			return String.join(",", caseName, mode, queueOrdering, String.valueOf(useGcnStyle), status, fmt(incumbent),
					fmt(bound), fmt(gap), String.valueOf(nodes), String.valueOf(pricingRounds),
					String.valueOf(generatedColumns), String.valueOf(poolSize), fmt(solveSeconds), fmt(rootSeconds),
					fmt(heuristicSeconds), String.valueOf(heuristicCalls), exactEngine, fmt(exactSeconds),
					String.valueOf(exactCalls), fmt(masterLpSeconds), String.valueOf(valid), logPath);
		}
	}
}
