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
import Common.Configure;
import Common.Utility;
import Output.BPCSolutionValidator;
import Output.BPCTraceSummary;
import Output.ValidationResult;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;

/**
 * 2026-05-28: 对照测试 正式 half-domain 双向 pricing 与 GCBB full-domain 函数定义域。
 * 其它 BPC 参数尽量保持一致，用于观察只放宽标签函数定义域后的效率变化。
 */
public class GCBBFullDomainComparisonTest {

	private static final String HEURISTIC_ENGINE = "HeuristicPricing";
	private static final String NORMAL_ENGINE = "GCNGBBStyleBidirectionalPricing";
	private static final String PARTIAL_DOMINANCE_ENGINE = "GCNGBBStylePartialDominancePricing";
	private static final String NG_DSSR_ENGINE = "GCNGBBStyleNgDssrPricing";
	private static final String FULL_DOMAIN_ENGINE = "GCBBStyleBidirectionalFullDomainPricing";
	private static final String NODE_JOIN_ENGINE = "GCBBStyleBidirectionalFullDomainNodeJoinPricing";

	public static void main(String[] args) throws Exception {
		Configure.debugBPCPricingColumnCheck = Boolean.getBoolean("twet.bpc.fullDomainCompare.debugColumnCheck");
		Path instanceDir = Path.of(System.getProperty("twet.bpc.fullDomainCompare.dir",
				"test-results/bpc/2026-05-24-no-outsourcing-20-21"));
		String caseFilter = System.getProperty("twet.bpc.fullDomainCompare.case", "wet021");
		String modeFilter = System.getProperty("twet.bpc.fullDomainCompare.mode", "both");
		String resultStem = System.getProperty("twet.bpc.fullDomainCompare.name",
				"2026-05-28-gcbb-full-domain-wet021");
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
			if (shouldRunNormal(modeFilter)) {
				RunRecord normal = runOne(instance, false, outputDir);
				records.add(normal);
				lines.add(normal.toCsvLine());
			}
			if (shouldRunFullDomain(modeFilter)) {
				RunRecord fullDomain = runOne(instance, true, outputDir);
				records.add(fullDomain);
				lines.add(fullDomain.toCsvLine());
			}
			if (shouldRunNodeJoin(modeFilter)) {
				RunRecord nodeJoin = runOne(instance, false, true, outputDir);
				records.add(nodeJoin);
				lines.add(nodeJoin.toCsvLine());
			}
		}

		Files.write(csv, lines);
		printSummary(records, csv);
	}

	private static boolean shouldRunNormal(String modeFilter) {
		return "both".equalsIgnoreCase(modeFilter) || "normal".equalsIgnoreCase(modeFilter)
				|| "half".equalsIgnoreCase(modeFilter) || "halfDomain".equalsIgnoreCase(modeFilter);
	}

	private static boolean shouldRunFullDomain(String modeFilter) {
		return "both".equalsIgnoreCase(modeFilter) || "full".equalsIgnoreCase(modeFilter)
				|| "fullDomain".equalsIgnoreCase(modeFilter);
	}

	private static boolean shouldRunNodeJoin(String modeFilter) {
		return "node".equalsIgnoreCase(modeFilter) || "nodeJoin".equalsIgnoreCase(modeFilter)
				|| "fullNodeJoin".equalsIgnoreCase(modeFilter);
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

	private static RunRecord runOne(Path instance, boolean fullDomain, Path outputDir) throws Exception {
		return runOne(instance, fullDomain, false, outputDir);
	}

	private static RunRecord runOne(Path instance, boolean fullDomain, boolean nodeJoin, Path outputDir)
			throws Exception {
		resetHeuristicSeed(instance);
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(instance.toString(), false);
		TWETBPCConfig config = buildConfig(instance, fullDomain, nodeJoin);
		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		TWETSolveResult result = solver.solve();
		BPCTraceSummary summary = solver.getContext().traceSummary;
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);

		String mode = nodeJoin ? "nodeJoin" : (fullDomain ? "fullDomain" : "halfDomain");
		String crossingSide = config.fullDomainNodeJoinCrossingSide == null
				? "both" : config.fullDomainNodeJoinCrossingSide.trim();
		if (nodeJoin && !crossingSide.isEmpty() && !"both".equalsIgnoreCase(crossingSide)) {
			mode += "-cross" + crossingSide;
		}
		String joinBestMode = config.bidirectionalJoinBestThresholdMode == null
				? "zero" : config.bidirectionalJoinBestThresholdMode.trim();
		if (!joinBestMode.isEmpty() && !"zero".equalsIgnoreCase(joinBestMode)) {
			mode += "-" + joinBestMode;
		}
		String completionBound = config.bidirectionalCompletionBoundRelaxation == null
				? "off" : config.bidirectionalCompletionBoundRelaxation.trim();
		if ((fullDomain || nodeJoin) && !completionBound.isEmpty() && !"off".equalsIgnoreCase(completionBound)) {
			mode += "-cb-" + completionBound;
			if (fullDomain && !config.bidirectionalCompletionBoundScalarPruning) {
				mode += "-scalarOff";
			}
		}
		if (config.useGCNGBBStyleNgDssrPricing) {
			mode += "-ng-" + config.ngDssrInitialNgSetMode + config.ngDssrInitialNgSetSize
					+ "-top" + config.ngDssrNonElementaryRouteUpdateLimit;
		}
		String exactEngine = nodeJoin ? NODE_JOIN_ENGINE : (fullDomain ? FULL_DOMAIN_ENGINE
				: (config.useGCNGBBStyleNgDssrPricing ? NG_DSSR_ENGINE
						: (config.useGCNGBBStylePartialDominancePricing ? PARTIAL_DOMINANCE_ENGINE : NORMAL_ENGINE)));
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

	private static TWETBPCConfig buildConfig(Path instance, boolean fullDomain) {
		return buildConfig(instance, fullDomain, false);
	}

	private static TWETBPCConfig buildConfig(Path instance, boolean fullDomain, boolean nodeJoin) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = stripDat(instance.getFileName().toString()) + "-no-outsourcing-domain";
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.reuseConfiguredBestSolution = false;
		// 2026-06-02: 对照实验默认仍关闭 ALNS；需要验证完整 BPC 上界质量时可显式打开。
		config.runALNSForSeed = Boolean.getBoolean("twet.bpc.fullDomainCompare.runALNSForSeed");
		config.enableHeuristicPricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableHeuristicPricing",
				Boolean.toString(config.enableHeuristicPricing)));
		config.enableBPCConsoleOutput = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableConsoleOutput",
				Boolean.toString(config.enableBPCConsoleOutput)));
		config.enableRestrictedMasterIntegerHeuristic = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableRestrictedMasterIntegerHeuristic",
				Boolean.toString(config.enableRestrictedMasterIntegerHeuristic)));
		config.restrictedMasterIntegerHeuristicTimeLimitSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.restrictedMasterIntegerTimeLimit",
				Double.toString(config.restrictedMasterIntegerHeuristicTimeLimitSeconds)));
		config.diagnosticRestrictedIntegerMipLog = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.restrictedMasterIntegerMipLog",
				Boolean.toString(config.diagnosticRestrictedIntegerMipLog)));
		config.diagnosticStageHeartbeat = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.stageHeartbeat",
				Boolean.toString(config.diagnosticStageHeartbeat)));
		config.diagnosticNodeProgressSummary = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.nodeProgressSummary",
				Boolean.toString(config.diagnosticNodeProgressSummary)));
		config.diagnosticPricingSummaryDetails = config.diagnosticStageHeartbeat
				|| config.diagnosticNodeProgressSummary
				|| Boolean.parseBoolean(System.getProperty("twet.bpc.fullDomainCompare.pricingDiagnostics",
						Boolean.toString(config.diagnosticPricingSummaryDetails)));
		config.maxNodes = Integer.getInteger("twet.bpc.fullDomainCompare.maxNodes", 20000);
		config.maxHeuristicPricingColumns = Integer.getInteger("twet.bpc.fullDomainCompare.maxHeuristicColumns",
				100000);
		config.heuristicPricingPoolSize = Integer.getInteger("twet.bpc.fullDomainCompare.heuristicPoolSize",
				100000);
		config.maxExactPricingColumns = Integer.getInteger("twet.bpc.fullDomainCompare.maxExactColumns", 100000);
		config.branchSeedColumnLimit = Integer.getInteger("twet.bpc.fullDomainCompare.branchSeedColumnLimit", 20000);
		config.enableUndirectedAdjacencyBranching = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableUndirectedAdjacencyBranching",
				Boolean.toString(config.enableUndirectedAdjacencyBranching)));
		config.enableBidirectionalPricing = true;
		config.useGCNGBBStyleBidirectionalPricing = true;
		config.useGCBBFullDomainBidirectionalPricing = fullDomain;
		config.useGCBBFullDomainNodeJoinBidirectionalPricing = nodeJoin;
		config.useGCNGBBStylePartialDominancePricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.partialDominance",
				Boolean.toString(config.useGCNGBBStylePartialDominancePricing)));
		config.diagnosticCrossCheckPartialDominance = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.crossCheckPartialDominance",
				Boolean.toString(config.diagnosticCrossCheckPartialDominance)));
		config.useGCNGBBStyleNgDssrPricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssr",
				Boolean.toString(config.useGCNGBBStyleNgDssrPricing)));
		config.ngDssrInitialNgSetMode = System.getProperty("twet.bpc.fullDomainCompare.ngDssrInitialMode",
				config.ngDssrInitialNgSetMode);
		config.ngDssrInitialNgSetSize = Integer.getInteger("twet.bpc.fullDomainCompare.ngDssrInitialSize",
				config.ngDssrInitialNgSetSize);
		config.ngDssrNonElementaryRouteUpdateLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.ngDssrRouteUpdateLimit",
				config.ngDssrNonElementaryRouteUpdateLimit);
		config.forwardLabelQueueOrdering = "time";
		config.bidirectionalLabelQueueOrdering = "time";
		config.bidirectionalJoinBestThresholdMode = System.getProperty(
				"twet.bpc.fullDomainCompare.joinBestMode", config.bidirectionalJoinBestThresholdMode);
		config.bidirectionalCompletionBoundRelaxation = System.getProperty(
				"twet.bpc.fullDomainCompare.completionBound", config.bidirectionalCompletionBoundRelaxation);
		config.bidirectionalCompletionBoundQueueOrdering = System.getProperty(
				"twet.bpc.fullDomainCompare.completionBoundQueue",
				config.bidirectionalCompletionBoundQueueOrdering);
		config.bidirectionalCompletionBoundScalarPruning = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.completionBoundScalar",
				Boolean.toString(config.bidirectionalCompletionBoundScalarPruning)));
		config.bidirectionalCompletionBoundArcFixing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.completionBoundArcFixing",
				Boolean.toString(config.bidirectionalCompletionBoundArcFixing)));
		config.bidirectionalCompletionBoundArcFixingDiagnostic = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.completionBoundArcFixingDiagnostic",
				Boolean.toString(config.bidirectionalCompletionBoundArcFixingDiagnostic)));
		config.bidirectionalCompletionBoundSubtreeArcElimination = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.completionBoundSubtreeArcElimination",
				Boolean.toString(config.bidirectionalCompletionBoundSubtreeArcElimination)));
		config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.completionBoundSubtreeArcEliminationPricingOnly",
				Boolean.toString(config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly)));
		config.debugSkipBranchColumnFilter = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.debugSkipBranchColumnFilter",
				Boolean.toString(config.debugSkipBranchColumnFilter)));
		config.debugIgnorePricingOnlyArcsAtNode = Integer.getInteger(
				"twet.bpc.fullDomainCompare.debugIgnorePricingOnlyArcsAtNode",
				config.debugIgnorePricingOnlyArcsAtNode);
		config.bidirectionalCompletionBoundSubtreeArcEliminationDiagnostic = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.completionBoundSubtreeArcEliminationDiagnostic",
				Boolean.toString(config.bidirectionalCompletionBoundSubtreeArcEliminationDiagnostic)));
		config.fullDomainNodeJoinCrossingSide = System.getProperty(
				"twet.bpc.fullDomainCompare.nodeJoinCrossingSide", config.fullDomainNodeJoinCrossingSide);
		config.bidirectionalRootLocalHorizonMidpointRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointRatio",
				Double.toString(config.bidirectionalRootLocalHorizonMidpointRatio)));
		config.bidirectionalMidpointStrategy = System.getProperty(
				"twet.bpc.fullDomainCompare.midpointStrategy", config.bidirectionalMidpointStrategy);
		config.bidirectionalMidpointColumnLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.midpointColumnLimit", config.bidirectionalMidpointColumnLimit);
		config.bidirectionalMidpointProbe = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbe", Boolean.toString(config.bidirectionalMidpointProbe)));
		config.bidirectionalMidpointProbePopLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.midpointProbePopLimit", config.bidirectionalMidpointProbePopLimit);
		config.bidirectionalMidpointProbeMaxCandidates = Integer.getInteger(
				"twet.bpc.fullDomainCompare.midpointProbeMaxCandidates",
				config.bidirectionalMidpointProbeMaxCandidates);
		config.bidirectionalMidpointProbeReuseMaxCandidates = Integer.getInteger(
				"twet.bpc.fullDomainCompare.midpointProbeReuseMaxCandidates",
				config.bidirectionalMidpointProbeReuseMaxCandidates);
		config.bidirectionalMidpointProbeMoveRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeMoveRatio",
				Double.toString(config.bidirectionalMidpointProbeMoveRatio)));
		config.bidirectionalMidpointProbeScore = System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeScore", config.bidirectionalMidpointProbeScore);
		config.bidirectionalMidpointProbeTieScore = System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeTieScore", config.bidirectionalMidpointProbeTieScore);
		config.bidirectionalMidpointProbeTieTolerance = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeTieTolerance",
				Double.toString(config.bidirectionalMidpointProbeTieTolerance)));
		config.bidirectionalMidpointProbeEarlyStopRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeEarlyStopRatio",
				Double.toString(config.bidirectionalMidpointProbeEarlyStopRatio)));
		config.bidirectionalMidpointProbeExtraCandidatesAfterThreshold = Integer.getInteger(
				"twet.bpc.fullDomainCompare.midpointProbeExtraCandidates",
				config.bidirectionalMidpointProbeExtraCandidatesAfterThreshold);
		config.bidirectionalMidpointProbeBracketOnDirectionChange = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeBracket",
				Boolean.toString(config.bidirectionalMidpointProbeBracketOnDirectionChange)));
		config.bidirectionalMidpointProbeReuseWithinNode = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeReuseWithinNode",
				Boolean.toString(config.bidirectionalMidpointProbeReuseWithinNode)));
		config.bidirectionalMidpointProbeExactFeedback = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeExactFeedback",
				Boolean.toString(config.bidirectionalMidpointProbeExactFeedback)));
		config.bidirectionalMidpointProbeExactTimeTieTolerance = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeExactTimeTieTolerance",
				Double.toString(config.bidirectionalMidpointProbeExactTimeTieTolerance)));
		config.bidirectionalMidpointProbeExactBalanceImprovementTolerance = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeExactBalanceImprovementTolerance",
				Double.toString(config.bidirectionalMidpointProbeExactBalanceImprovementTolerance)));
		return config;
	}

	private static void resetHeuristicSeed(Path instance) {
		long seed = 202605280100L + stripDat(instance.getFileName().toString()).hashCode();
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
		System.out.println("GCBBFullDomainComparisonTest summary:");
		for (RunRecord record : records) {
			System.out.printf(Locale.US,
					"case=%s mode=%s status=%s obj=%.6f bound=%.6f solve=%.3fs exact=%s %.3fs calls=%d valid=%s log=%s%n",
					record.caseName, record.mode, record.status, record.incumbent, record.bound, record.solveSeconds,
					record.exactEngine, record.exactSeconds, record.exactCalls, record.valid, record.logPath);
		}
		if (records.size() == 2) {
			RunRecord normal = records.get(0);
			RunRecord fullDomain = records.get(1);
			System.out.printf(Locale.US,
					"full/half exact %.2fx, solve %.2fx, exact_diff %.3fs, solve_diff %.3fs%n",
					fullDomain.exactSeconds / normal.exactSeconds, fullDomain.solveSeconds / normal.solveSeconds,
					fullDomain.exactSeconds - normal.exactSeconds, fullDomain.solveSeconds - normal.solveSeconds);
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
