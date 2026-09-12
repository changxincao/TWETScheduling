package Common.formal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import Basic.Data;
import Common.Utility;
import HEU.EngineALNS;
import TWETBPC.BPCAlgorithmProfile;
import TWETBPC.BestBpcProfiles;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;
import TWETBPC.TWETSolveStatus;
import TWETBPC.GC.FixedInitialColumnSeed;
import TWETBPC.GC.InitialColumnBuilder;
import TWETBPC.GC.InitialColumnBundle;
import TWETBPC.IO.FixedInitialSeedSnapshotIO;
import TWETBPC.IO.HeuristicSeedProvider;
import TWETBPC.LP.Pool;

/**
 * 正式计算实验的单次运行入口。
 * <p>
 * {@code seed} 动作只生成共享初始列；{@code solve} 动作读取同一快照并按命名 profile 求解。
 * 所有长运行默认写流式日志，热循环诊断保持关闭。
 */
public final class FormalExperimentRunner {
	private static final int INITIAL_SEED_RUNS = 3;

	private FormalExperimentRunner() {
	}

	public static void main(String[] args) throws Exception {
		Arguments arguments = Arguments.parse(args);
		if ("seed".equals(arguments.action)) {
			writeSeed(arguments);
			return;
		}
		Data data = arguments.loadSolveData();
		runSolver(data, arguments);
	}

	private static void writeSeed(Arguments arguments) throws Exception {
		if (arguments.seedFile == null) {
			throw new IllegalArgumentException("seed action requires --seedFile");
		}
		long startNanos = System.nanoTime();
		ArrayList<SeedRun> runs = new ArrayList<SeedRun>(INITIAL_SEED_RUNS);
		for (int repetition = 0; repetition < INITIAL_SEED_RUNS; repetition++) {
			runs.add(runInitialSeed(arguments, repetition));
		}
		SeedRun winner = selectBestSeedRun(runs);
		FixedInitialSeedSnapshotIO.write(arguments.seedFile, arguments.instance.toString(), winner.seed);
		long elapsedNanos = System.nanoTime() - startNanos;
		Files.createDirectories(arguments.outputDir);
		writeSeedMetadata(arguments, runs, winner, elapsedNanos);
		System.out.printf(Locale.US,
				"formalSeed run=%s selected=%d cost=%.6f initial=%d incumbent=%d outsourced=%d fingerprint=%s elapsedSeconds=%.3f file=%s%n",
				arguments.runId, winner.repetition, winner.incumbentCost, winner.seed.getInitialSequences().size(),
				winner.seed.getIncumbentSequences().size(), winner.seed.getIncumbentOutsourcedJobs().size(),
				winner.fingerprint, elapsedNanos / 1_000_000_000.0, arguments.seedFile.toAbsolutePath());
	}

	private static SeedRun runInitialSeed(Arguments arguments, int repetition) throws Exception {
		long alnsSeed = deterministicSeed(arguments.runId, repetition, 0x41c64e6dL);
		long utilitySeed = deterministicSeed(arguments.runId, repetition, 0x9e3779b9L);
		EngineALNS.rng = new Random(alnsSeed);
		Utility.rng = new Random(utilitySeed);
		Data data = arguments.loadInstance();
		TWETBPCConfig config = new TWETBPCConfig();
		BestBpcProfiles.NG_DSSR.apply(config);
		config.reuseConfiguredBestSolution = false;
		config.fixedInitialColumnSeed = null;
		Pool pool = new Pool(data);
		long startNanos = System.nanoTime();
		InitialColumnBundle bundle = new InitialColumnBuilder(data, config, pool,
				new HeuristicSeedProvider(data, config)).build();
		long elapsedNanos = System.nanoTime() - startNanos;
		FixedInitialColumnSeed seed = new FixedInitialColumnSeed(
				extractSequences(pool, bundle.getInitialColumnIds()),
				extractSequences(pool, bundle.getIncumbentColumnIds()), bundle.getIncumbentOutsourcedJobs());
		double incumbentCost = bundle.getIncumbentOutsourcingCost();
		for (int columnId : bundle.getIncumbentColumnIds()) {
			incumbentCost += pool.getColumn(columnId).getCost();
		}
		return new SeedRun(repetition, alnsSeed, utilitySeed, seed, incumbentCost, elapsedNanos,
				FixedInitialSeedSnapshotIO.fingerprint(seed));
	}

	private static SeedRun selectBestSeedRun(List<SeedRun> runs) {
		if (runs.isEmpty()) {
			throw new IllegalArgumentException("At least one initial seed run is required");
		}
		SeedRun best = runs.get(0);
		for (int index = 1; index < runs.size(); index++) {
			SeedRun candidate = runs.get(index);
			if (candidate.incumbentCost < best.incumbentCost
					|| (Double.compare(candidate.incumbentCost, best.incumbentCost) == 0
							&& candidate.repetition < best.repetition)) {
				best = candidate;
			}
		}
		return best;
	}

	private static long deterministicSeed(String runId, int repetition, long salt) {
		long value = 0xcbf29ce484222325L ^ salt;
		for (int index = 0; index < runId.length(); index++) {
			value = (value ^ runId.charAt(index)) * 0x100000001b3L;
		}
		return value ^ (0x9e3779b97f4a7c15L * (repetition + 1L));
	}

	private static void writeSeedMetadata(Arguments arguments, List<SeedRun> runs, SeedRun winner,
			long elapsedNanos) throws Exception {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("runId=" + arguments.runId);
		lines.add("updatedAt=" + Instant.now());
		lines.add("action=seed");
		lines.add("instance=" + arguments.instance.toAbsolutePath());
		lines.add("profileVersion=" + BestBpcProfiles.VERSION);
		lines.add("seedFile=" + arguments.seedFile.toAbsolutePath());
		lines.add("seedRunCount=" + runs.size());
		for (SeedRun run : runs) {
			String prefix = "seedRun" + run.repetition + ".";
			lines.add(prefix + "alnsSeed=" + run.alnsSeed);
			lines.add(prefix + "utilitySeed=" + run.utilitySeed);
			lines.add(prefix + "incumbentCost=" + run.incumbentCost);
			lines.add(prefix + "elapsedSeconds=" + run.elapsedNanos / 1_000_000_000.0);
			lines.add(prefix + "initialColumnCount=" + run.seed.getInitialSequences().size());
			lines.add(prefix + "incumbentColumnCount=" + run.seed.getIncumbentSequences().size());
			lines.add(prefix + "outsourcedJobCount=" + run.seed.getIncumbentOutsourcedJobs().size());
			lines.add(prefix + "fingerprint=" + run.fingerprint);
		}
		lines.add("selectedSeedRun=" + winner.repetition);
		lines.add("seedFingerprint=" + winner.fingerprint);
		lines.add("initialColumnCount=" + winner.seed.getInitialSequences().size());
		lines.add("incumbentColumnCount=" + winner.seed.getIncumbentSequences().size());
		lines.add("incumbentOutsourcedJobCount=" + winner.seed.getIncumbentOutsourcedJobs().size());
		lines.add("incumbentCost=" + winner.incumbentCost);
		lines.add("elapsedSeconds=" + elapsedNanos / 1_000_000_000.0);
		Files.write(arguments.outputDir.resolve("run.properties"), lines, StandardCharsets.UTF_8);
	}

	private static List<List<Integer>> extractSequences(Pool pool, List<Integer> columnIds) {
		ArrayList<List<Integer>> sequences = new ArrayList<List<Integer>>(columnIds.size());
		for (int columnId : columnIds) {
			sequences.add(new ArrayList<Integer>(pool.getColumn(columnId).getSequence()));
		}
		return sequences;
	}

	private static final class SeedRun {
		final int repetition;
		final long alnsSeed;
		final long utilitySeed;
		final FixedInitialColumnSeed seed;
		final double incumbentCost;
		final long elapsedNanos;
		final String fingerprint;

		SeedRun(int repetition, long alnsSeed, long utilitySeed, FixedInitialColumnSeed seed,
				double incumbentCost, long elapsedNanos, String fingerprint) {
			this.repetition = repetition;
			this.alnsSeed = alnsSeed;
			this.utilitySeed = utilitySeed;
			this.seed = seed;
			this.incumbentCost = incumbentCost;
			this.elapsedNanos = elapsedNanos;
			this.fingerprint = fingerprint;
		}
	}

	private static void runSolver(Data data, Arguments arguments) throws Exception {
		BPCAlgorithmProfile profile = BestBpcProfiles.require(arguments.algorithm);
		TWETBPCConfig config = new TWETBPCConfig();
		profile.apply(config);
		arguments.applyNgDssrExperimentOverrides(config);
		applyMidpointExperimentSystemPropertyOverrides(config);
		config.instanceName = arguments.runId;
		config.bpcMethodName = profile.getName();
		config.bpcOutputRoot = arguments.outputDir.toString();
		config.writeBPCResultFiles = true;
		config.writeDetailedBPCArtifacts = true;
		config.enableBPCConsoleOutput = false;
		config.liveTraceLogPath = arguments.outputDir.resolve("live.log").toString();
		config.diagnosticStageHeartbeat = true;
		config.diagnosticNodeProgressSummary = true;
		config.diagnosticPricingSummaryDetails = false;
		config.solveTimeLimitSeconds = arguments.timeLimitSeconds;
		config.maxNodes = arguments.maxNodes;
		if (data.hasOutsourcingData()) {
			config.outsourcingModel = arguments.outsourcingModel;
		}
		config.reuseConfiguredBestSolution = false;
		String seedFingerprint = "";
		if (arguments.seedFile != null) {
			config.fixedInitialColumnSeed = FixedInitialSeedSnapshotIO.read(arguments.seedFile);
			seedFingerprint = FixedInitialSeedSnapshotIO.fingerprint(config.fixedInitialColumnSeed);
		}
		System.setProperty("twet.bpc.cplexThreads", "1");
		Files.createDirectories(arguments.outputDir);
		writeRunMetadata(arguments, profile, seedFingerprint, null);

		System.out.printf(Locale.US,
				"formalRun start run=%s profile=%s profileVersion=%s n=%d m=%d timeLimit=%.1fs output=%s%n",
				arguments.runId, profile.getName(), BestBpcProfiles.VERSION, data.n, data.m,
				arguments.timeLimitSeconds, arguments.outputDir.toAbsolutePath());
		TWETSolveResult result = new TWETBPCSolver(data, config).solve();
		writeRunMetadata(arguments, profile, seedFingerprint, result);
		System.out.printf(Locale.US,
				"formalRun finished run=%s status=%s incumbent=%.6f bound=%.6f nodes=%d columns=%d%n",
				arguments.runId, result.getStatus(), result.getIncumbentCost(), result.getBestBound(),
				result.getProcessedNodes(), result.getGeneratedColumns());
		if (!isReportableSolveStatus(result.getStatus())) {
			throw new IllegalStateException("Formal solve ended with non-reportable status: " + result.getStatus());
		}
	}

	/** 显式-D属性只用于A/B覆盖；属性缺失时保留正式profile，避免静默关闭生产配置。 */
	static void applyMidpointExperimentSystemPropertyOverrides(TWETBPCConfig config) {
		String previousPricing = System.getProperty("twet.bpc.midpointPreviousPricing");
		if (previousPricing != null) {
			config.enableNgDssrSameNodeMidpointStart = Boolean.parseBoolean(previousPricing);
		}
		String firstRoundMean = System.getProperty("twet.bpc.midpointFirstRoundMean");
		if (firstRoundMean != null) {
			config.enableNgDssrFirstRoundMeanStart = Boolean.parseBoolean(firstRoundMean);
		}
		if (config.enableNgDssrSameNodeMidpointStart && config.enableNgDssrFirstRoundMeanStart) {
			throw new IllegalArgumentException("Choose only one midpoint history strategy");
		}
	}

	/** 只有形成可用于实验汇总的正常终止状态，子进程才允许返回0。 */
	static boolean isReportableSolveStatus(TWETSolveStatus status) {
		return status == TWETSolveStatus.FINISHED
				|| status == TWETSolveStatus.ROOT_PROCESSED
				|| status == TWETSolveStatus.TIME_LIMIT
				|| status == TWETSolveStatus.NODE_LIMIT;
	}

	private static void writeRunMetadata(Arguments arguments, BPCAlgorithmProfile profile, String seedFingerprint,
			TWETSolveResult result)
			throws Exception {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("runId=" + arguments.runId);
		lines.add("updatedAt=" + Instant.now());
		lines.add("instance=" + arguments.instance.toAbsolutePath());
		lines.add("algorithm=" + profile.getName());
		lines.add("profileVersion=" + BestBpcProfiles.VERSION);
		lines.add("outsourcingModel=" + (arguments.outsourcingModel.isEmpty()
				? "not-applicable" : arguments.outsourcingModel));
		lines.add("seedFile=" + (arguments.seedFile == null ? "" : arguments.seedFile.toAbsolutePath()));
		lines.add("seedFingerprint=" + seedFingerprint);
		lines.add("cplexThreads=1");
		lines.add("ngDssrSameNodeWarmStartOverride=" + arguments.ngDssrSameNodeWarmStart);
		lines.add("ngDssrSameNodeWarmStartWindowOverride=" + arguments.ngDssrSameNodeWarmStartWindow);
		lines.add("ngDssrSameNodeWarmStartPerJobLimitOverride="
				+ arguments.ngDssrSameNodeWarmStartPerJobLimit);
		lines.add("ngDssrSameNodeWarmStartGlobalPairLimitOverride="
				+ arguments.ngDssrSameNodeWarmStartGlobalPairLimit);
		lines.add("ngDssrSameNodeWarmStartTriggerRoundsOverride="
				+ arguments.ngDssrSameNodeWarmStartTriggerRounds);
		lines.add("ngDssrSameNodeWarmStartMinimumOccurrenceOverride="
				+ arguments.ngDssrSameNodeWarmStartMinimumOccurrence);
		lines.add("bidirectionalMidpointProbeAfterFirstDssrRoundOverride="
				+ arguments.bidirectionalMidpointProbeAfterFirstDssrRound);
		lines.add("bidirectionalMidpointProbeDssrEarlyStopRatioOverride="
				+ arguments.bidirectionalMidpointProbeDssrEarlyStopRatio);
		lines.add("bidirectionalMidpointProbeDssrImbalanceThresholdOverride="
				+ arguments.bidirectionalMidpointProbeDssrImbalanceThreshold);
		lines.add("bidirectionalMidpointProbeDssrMoveFractionOverride="
				+ arguments.bidirectionalMidpointProbeDssrMoveFraction);
		lines.add("ngDssrNonElementaryRouteCandidateLimitOverride="
				+ arguments.ngDssrNonElementaryRouteCandidateLimit);
		lines.add("ngDssrNonElementaryRouteUpdateModeOverride="
				+ arguments.ngDssrNonElementaryRouteUpdateMode);
		lines.add("ngDssrInitialNgSetSizeOverride=" + arguments.ngDssrInitialNgSetSize);
		lines.add("enableClusterBranchingOverride=" + arguments.enableClusterBranching);
		lines.add("enableCutSetBranchingOverride=" + arguments.enableCutSetBranching);
		lines.add("enableSuccessorSetBranchingOverride=" + arguments.enableSuccessorSetBranching);
		lines.add("structuredArcStrictTypePriorityOverride=" + arguments.structuredArcStrictTypePriority);
		lines.add("clusterMstThetaOverride=" + arguments.clusterMstTheta);
		lines.add("clusterTemporalWeightOverride=" + arguments.clusterTemporalWeight);
		lines.add("diagnosticNodeCoverageOverride=" + arguments.diagnosticNodeCoverage);
		lines.add("timeIndexedCompletionBoundNodeArcFixingOverride="
				+ arguments.timeIndexedCompletionBoundNodeArcFixing);
		lines.add("timeIndexedCompletionBoundNodeArcFixingMaxDepthOverride="
				+ arguments.timeIndexedCompletionBoundNodeArcFixingMaxDepth);
		if (result != null) {
			lines.add("status=" + result.getStatus());
			lines.add("incumbent=" + result.getIncumbentCost());
			lines.add("bound=" + result.getBestBound());
			lines.add("processedNodes=" + result.getProcessedNodes());
			lines.add("generatedColumns=" + result.getGeneratedColumns());
		}
		Files.write(arguments.outputDir.resolve("run.properties"), lines, StandardCharsets.UTF_8);
	}

	private static final class Arguments {
		private static final java.util.Set<String> SUPPORTED_KEYS = java.util.Set.of(
				"action", "instance", "algorithm", "runId", "outputDir", "seedFile",
				"outsourcingModel", "timeLimitSeconds", "maxNodes", "ngDssrSameNodeWarmStart",
				"ngDssrSameNodeWarmStartWindow", "ngDssrSameNodeWarmStartPerJobLimit",
				"ngDssrSameNodeWarmStartGlobalPairLimit", "ngDssrSameNodeWarmStartTriggerRounds",
				"ngDssrSameNodeWarmStartMinimumOccurrence",
				"bidirectionalMidpointProbeAfterFirstDssrRound",
				"bidirectionalMidpointProbeDssrEarlyStopRatio",
				"bidirectionalMidpointProbeDssrImbalanceThreshold",
				"bidirectionalMidpointProbeDssrMoveFraction",
				"ngDssrNonElementaryRouteCandidateLimit", "ngDssrNonElementaryRouteUpdateMode",
				"ngDssrInitialNgSetSize", "enableClusterBranching", "enableCutSetBranching",
				"enableSuccessorSetBranching",
				"structuredArcStrictTypePriority",
				"clusterMstTheta", "clusterTemporalWeight", "diagnosticNodeCoverage",
				"timeIndexedCompletionBoundNodeArcFixing",
				"timeIndexedCompletionBoundNodeArcFixingMaxDepth");

		private final String action;
		private final Path instance;
		private final String algorithm;
		private final String runId;
		private final Path outputDir;
		private final Path seedFile;
		private final String outsourcingModel;
		private final double timeLimitSeconds;
		private final int maxNodes;
		private final Boolean ngDssrSameNodeWarmStart;
		private final Integer ngDssrSameNodeWarmStartWindow;
		private final Integer ngDssrSameNodeWarmStartPerJobLimit;
		private final Integer ngDssrSameNodeWarmStartGlobalPairLimit;
		private final Integer ngDssrSameNodeWarmStartTriggerRounds;
		private final Integer ngDssrSameNodeWarmStartMinimumOccurrence;
		private final Boolean bidirectionalMidpointProbeAfterFirstDssrRound;
		private final Double bidirectionalMidpointProbeDssrEarlyStopRatio;
		private final Double bidirectionalMidpointProbeDssrImbalanceThreshold;
		private final Double bidirectionalMidpointProbeDssrMoveFraction;
		private final Integer ngDssrNonElementaryRouteCandidateLimit;
		private final String ngDssrNonElementaryRouteUpdateMode;
		private final Integer ngDssrInitialNgSetSize;
		private final Boolean enableClusterBranching;
		private final Boolean enableCutSetBranching;
		private final Boolean enableSuccessorSetBranching;
		private final Boolean structuredArcStrictTypePriority;
		private final Double clusterMstTheta;
		private final Double clusterTemporalWeight;
		private final Boolean diagnosticNodeCoverage;
		private final Boolean timeIndexedCompletionBoundNodeArcFixing;
		private final Integer timeIndexedCompletionBoundNodeArcFixingMaxDepth;

		private Arguments(Map<String, String> values) {
			for (String key : values.keySet()) {
				if (!SUPPORTED_KEYS.contains(key)) {
					throw new IllegalArgumentException("Unknown argument --" + key);
				}
			}
			action = value(values, "action", "solve").toLowerCase(Locale.ROOT);
			if (!"seed".equals(action) && !"solve".equals(action)) {
				throw new IllegalArgumentException("action must be seed or solve: " + action);
			}
			instance = Path.of(required(values, "instance"));
			algorithm = value(values, "algorithm", "NG_DSSR");
			runId = value(values, "runId", stripExtension(instance.getFileName().toString()) + "-" + algorithm);
			outputDir = Path.of(value(values, "outputDir", "results/formal/" + runId));
			String seed = value(values, "seedFile", "");
			seedFile = seed.isEmpty() ? null : Path.of(seed);
			outsourcingModel = value(values, "outsourcingModel", "");
			if ("seed".equals(action) && !outsourcingModel.isEmpty()) {
				throw new IllegalArgumentException("seed action does not use --outsourcingModel");
			}
			timeLimitSeconds = number(values, "timeLimitSeconds", 10800.0);
			maxNodes = integer(values, "maxNodes", 100000);
			ngDssrSameNodeWarmStart = optionalBoolean(values, "ngDssrSameNodeWarmStart");
			ngDssrSameNodeWarmStartWindow = optionalInteger(values, "ngDssrSameNodeWarmStartWindow");
			ngDssrSameNodeWarmStartPerJobLimit = optionalInteger(values,
					"ngDssrSameNodeWarmStartPerJobLimit");
			ngDssrSameNodeWarmStartGlobalPairLimit = optionalInteger(values,
					"ngDssrSameNodeWarmStartGlobalPairLimit");
			ngDssrSameNodeWarmStartTriggerRounds = optionalInteger(values,
					"ngDssrSameNodeWarmStartTriggerRounds");
			ngDssrSameNodeWarmStartMinimumOccurrence = optionalInteger(values,
					"ngDssrSameNodeWarmStartMinimumOccurrence");
			bidirectionalMidpointProbeAfterFirstDssrRound = optionalBoolean(values,
					"bidirectionalMidpointProbeAfterFirstDssrRound");
			bidirectionalMidpointProbeDssrEarlyStopRatio = optionalDouble(values,
					"bidirectionalMidpointProbeDssrEarlyStopRatio");
			bidirectionalMidpointProbeDssrImbalanceThreshold = optionalDouble(values,
					"bidirectionalMidpointProbeDssrImbalanceThreshold");
			bidirectionalMidpointProbeDssrMoveFraction = optionalDouble(values,
					"bidirectionalMidpointProbeDssrMoveFraction");
			ngDssrNonElementaryRouteCandidateLimit = optionalInteger(values,
					"ngDssrNonElementaryRouteCandidateLimit");
			ngDssrNonElementaryRouteUpdateMode = optionalString(values,
					"ngDssrNonElementaryRouteUpdateMode");
			ngDssrInitialNgSetSize = optionalInteger(values, "ngDssrInitialNgSetSize");
			enableClusterBranching = optionalBoolean(values, "enableClusterBranching");
			enableCutSetBranching = optionalBoolean(values, "enableCutSetBranching");
			enableSuccessorSetBranching = optionalBoolean(values, "enableSuccessorSetBranching");
			structuredArcStrictTypePriority = optionalBoolean(values, "structuredArcStrictTypePriority");
			clusterMstTheta = optionalDouble(values, "clusterMstTheta");
			clusterTemporalWeight = optionalDouble(values, "clusterTemporalWeight");
			diagnosticNodeCoverage = optionalBoolean(values, "diagnosticNodeCoverage");
			timeIndexedCompletionBoundNodeArcFixing = optionalBoolean(values,
					"timeIndexedCompletionBoundNodeArcFixing");
			timeIndexedCompletionBoundNodeArcFixingMaxDepth = optionalInteger(values,
					"timeIndexedCompletionBoundNodeArcFixingMaxDepth");
		}

		/** 仅显式传参时覆盖正式 profile，未传参保持生产配置不变。 */
		private void applyNgDssrExperimentOverrides(TWETBPCConfig config) {
			if (ngDssrSameNodeWarmStart != null) {
				config.enableNgDssrSameNodeWarmStart = ngDssrSameNodeWarmStart.booleanValue();
			}
			if (ngDssrSameNodeWarmStartWindow != null) {
				config.ngDssrSameNodeWarmStartWindowSize = ngDssrSameNodeWarmStartWindow.intValue();
			}
			if (ngDssrSameNodeWarmStartPerJobLimit != null) {
				config.ngDssrSameNodeWarmStartPerJobLimit = ngDssrSameNodeWarmStartPerJobLimit.intValue();
			}
			if (ngDssrSameNodeWarmStartGlobalPairLimit != null) {
				config.ngDssrSameNodeWarmStartGlobalPairLimit = ngDssrSameNodeWarmStartGlobalPairLimit.intValue();
			}
			if (ngDssrSameNodeWarmStartTriggerRounds != null) {
				config.ngDssrSameNodeWarmStartTriggerRounds = ngDssrSameNodeWarmStartTriggerRounds.intValue();
			}
			if (ngDssrSameNodeWarmStartMinimumOccurrence != null) {
				config.ngDssrSameNodeWarmStartMinimumOccurrence =
						ngDssrSameNodeWarmStartMinimumOccurrence.intValue();
			}
			if (bidirectionalMidpointProbeAfterFirstDssrRound != null) {
				config.bidirectionalMidpointProbeAfterFirstDssrRound =
						bidirectionalMidpointProbeAfterFirstDssrRound.booleanValue();
			}
			if (bidirectionalMidpointProbeDssrEarlyStopRatio != null) {
				config.bidirectionalMidpointProbeDssrEarlyStopRatio =
						bidirectionalMidpointProbeDssrEarlyStopRatio.doubleValue();
			}
			if (bidirectionalMidpointProbeDssrImbalanceThreshold != null) {
				config.bidirectionalMidpointProbeDssrImbalanceThreshold =
						bidirectionalMidpointProbeDssrImbalanceThreshold.doubleValue();
			}
			if (bidirectionalMidpointProbeDssrMoveFraction != null) {
				config.bidirectionalMidpointProbeDssrMoveFraction =
						bidirectionalMidpointProbeDssrMoveFraction.doubleValue();
			}
			if (ngDssrNonElementaryRouteCandidateLimit != null) {
				config.ngDssrNonElementaryRouteCandidateLimit =
						ngDssrNonElementaryRouteCandidateLimit.intValue();
			}
			if (ngDssrNonElementaryRouteUpdateMode != null) {
				config.ngDssrNonElementaryRouteUpdateMode = ngDssrNonElementaryRouteUpdateMode;
			}
			if (ngDssrInitialNgSetSize != null) {
				config.ngDssrInitialNgSetSize = ngDssrInitialNgSetSize.intValue();
			}
			if (enableClusterBranching != null) {
				config.enableClusterBranching = enableClusterBranching.booleanValue();
			}
			if (enableCutSetBranching != null) {
				config.enableCutSetBranching = enableCutSetBranching.booleanValue();
			}
			if (enableSuccessorSetBranching != null) {
				config.enableSuccessorSetBranching = enableSuccessorSetBranching.booleanValue();
			}
			if (structuredArcStrictTypePriority != null) {
				config.structuredArcStrictTypePriority = structuredArcStrictTypePriority.booleanValue();
			}
			if (clusterMstTheta != null) {
				config.clusterMstTheta = clusterMstTheta.doubleValue();
			}
			if (clusterTemporalWeight != null) {
				config.clusterTemporalWeight = clusterTemporalWeight.doubleValue();
			}
			if (diagnosticNodeCoverage != null) {
				config.diagnosticNodeCoverage = diagnosticNodeCoverage.booleanValue();
			}
			if (timeIndexedCompletionBoundNodeArcFixing != null) {
				config.timeIndexedCompletionBoundNodeArcFixing =
						timeIndexedCompletionBoundNodeArcFixing.booleanValue();
			}
			if (timeIndexedCompletionBoundNodeArcFixingMaxDepth != null) {
				config.timeIndexedCompletionBoundNodeArcFixingMaxDepth =
						timeIndexedCompletionBoundNodeArcFixingMaxDepth.intValue();
			}
		}

		private Data loadInstance() throws Exception {
			return FormalExperimentDataFactory.load(instance);
		}

		private Data loadSolveData() throws Exception {
			Data data = loadInstance();
			FormalExperimentDataFactory.validateOutsourcingModel(data, outsourcingModel, instance);
			return data;
		}

		private static Arguments parse(String[] args) {
			LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
			for (String argument : args) {
				String token = argument.startsWith("--") ? argument.substring(2) : argument;
				int equals = token.indexOf('=');
				if (equals <= 0) {
					throw new IllegalArgumentException("Expected --key=value argument: " + argument);
				}
				values.put(token.substring(0, equals), token.substring(equals + 1));
			}
			return new Arguments(values);
		}

		private static String required(Map<String, String> values, String key) {
			String result = values.get(key);
			if (result == null || result.trim().isEmpty()) {
				throw new IllegalArgumentException("Missing --" + key);
			}
			return result.trim();
		}

		private static String value(Map<String, String> values, String key, String fallback) {
			String result = values.get(key);
			return result == null || result.trim().isEmpty() ? fallback : result.trim();
		}

		private static double number(Map<String, String> values, String key, double fallback) {
			return Double.parseDouble(value(values, key, Double.toString(fallback)));
		}

		private static int integer(Map<String, String> values, String key, int fallback) {
			return Integer.parseInt(value(values, key, Integer.toString(fallback)));
		}

		private static Integer optionalInteger(Map<String, String> values, String key) {
			String result = values.get(key);
			return result == null || result.trim().isEmpty() ? null : Integer.valueOf(result.trim());
		}

		private static Double optionalDouble(Map<String, String> values, String key) {
			String result = values.get(key);
			return result == null || result.trim().isEmpty() ? null : Double.valueOf(result.trim());
		}

		private static String optionalString(Map<String, String> values, String key) {
			String result = values.get(key);
			return result == null || result.trim().isEmpty() ? null : result.trim();
		}

		private static Boolean optionalBoolean(Map<String, String> values, String key) {
			String result = values.get(key);
			if (result == null || result.trim().isEmpty()) {
				return null;
			}
			String normalized = result.trim().toLowerCase(Locale.ROOT);
			if (!"true".equals(normalized) && !"false".equals(normalized)) {
				throw new IllegalArgumentException("--" + key + " must be true or false: " + result);
			}
			return Boolean.valueOf(normalized);
		}

		private static String stripExtension(String fileName) {
			int dot = fileName.lastIndexOf('.');
			return dot < 0 ? fileName : fileName.substring(0, dot);
		}
	}
}
