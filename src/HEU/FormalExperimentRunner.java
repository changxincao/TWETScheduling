package HEU;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Basic.Data;
import TWETBPC.BPCAlgorithmProfile;
import TWETBPC.BestBpcProfiles;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;
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

	private FormalExperimentRunner() {
	}

	public static void main(String[] args) throws Exception {
		Arguments arguments = Arguments.parse(args);
		FormalExperimentDataFactory.Scenario scenario = arguments.buildScenario();
		Data data = FormalExperimentDataFactory.load(arguments.instance, scenario);
		if ("seed".equals(arguments.action)) {
			writeSeed(data, arguments);
			return;
		}
		runSolver(data, arguments);
	}

	private static void writeSeed(Data data, Arguments arguments) throws Exception {
		if (arguments.seedFile == null) {
			throw new IllegalArgumentException("seed action requires --seedFile");
		}
		long startNanos = System.nanoTime();
		TWETBPCConfig config = new TWETBPCConfig();
		BestBpcProfiles.NG_DSSR.apply(config);
		config.reuseConfiguredBestSolution = false;
		config.fixedInitialColumnSeed = null;
		Pool pool = new Pool(data);
		InitialColumnBundle bundle = new InitialColumnBuilder(data, config, pool,
				new HeuristicSeedProvider(data, config)).build();
		FixedInitialColumnSeed seed = new FixedInitialColumnSeed(
				extractSequences(pool, bundle.getInitialColumnIds()),
				extractSequences(pool, bundle.getIncumbentColumnIds()));
		FixedInitialSeedSnapshotIO.write(arguments.seedFile, arguments.instance.toString(), seed);
		long elapsedNanos = System.nanoTime() - startNanos;
		String fingerprint = FixedInitialSeedSnapshotIO.fingerprint(seed);
		Files.createDirectories(arguments.outputDir);
		writeSeedMetadata(arguments, seed, fingerprint, elapsedNanos);
		System.out.printf(Locale.US,
				"formalSeed run=%s initial=%d incumbent=%d fingerprint=%s elapsedSeconds=%.3f file=%s%n",
				arguments.runId, seed.getInitialSequences().size(), seed.getIncumbentSequences().size(),
				fingerprint, elapsedNanos / 1_000_000_000.0, arguments.seedFile.toAbsolutePath());
	}

	private static void writeSeedMetadata(Arguments arguments, FixedInitialColumnSeed seed, String fingerprint,
			long elapsedNanos) throws Exception {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("runId=" + arguments.runId);
		lines.add("updatedAt=" + Instant.now());
		lines.add("action=seed");
		lines.add("instance=" + arguments.instance.toAbsolutePath());
		lines.add("profileVersion=" + BestBpcProfiles.VERSION);
		lines.add("seedFile=" + arguments.seedFile.toAbsolutePath());
		lines.add("seedFingerprint=" + fingerprint);
		lines.add("initialColumnCount=" + seed.getInitialSequences().size());
		lines.add("incumbentColumnCount=" + seed.getIncumbentSequences().size());
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

	private static void runSolver(Data data, Arguments arguments) throws Exception {
		BPCAlgorithmProfile profile = BestBpcProfiles.require(arguments.algorithm);
		TWETBPCConfig config = new TWETBPCConfig();
		profile.apply(config);
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
		config.outsourcingModel = "none".equals(arguments.outsourcingModel)
				? "masterVariables" : arguments.outsourcingModel;
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
		lines.add("timeScale=" + arguments.timeScale);
		lines.add("dueWindowHalfWidth=" + arguments.dueWindowHalfWidth);
		lines.add("setupCostCoefficient=" + arguments.setupCostCoefficient);
		lines.add("outsourcingModel=" + arguments.outsourcingModel);
		lines.add("outsourcingUnitRate=" + arguments.outsourcingUnitRate);
		lines.add("discountStrength=" + arguments.discountStrength);
		lines.add("seedFile=" + (arguments.seedFile == null ? "" : arguments.seedFile.toAbsolutePath()));
		lines.add("seedFingerprint=" + seedFingerprint);
		lines.add("cplexThreads=1");
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
		private final String action;
		private final Path instance;
		private final String algorithm;
		private final String runId;
		private final Path outputDir;
		private final Path seedFile;
		private final double timeScale;
		private final double dueWindowHalfWidth;
		private final double setupCostCoefficient;
		private final String outsourcingModel;
		private final double outsourcingUnitRate;
		private final double discountStrength;
		private final double timeLimitSeconds;
		private final int maxNodes;

		private Arguments(Map<String, String> values) {
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
			timeScale = number(values, "timeScale", 1.0);
			dueWindowHalfWidth = number(values, "dueWindowHalfWidth", 0.0);
			setupCostCoefficient = number(values, "setupCostCoefficient", 0.0);
			outsourcingModel = value(values, "outsourcingModel", "none");
			outsourcingUnitRate = number(values, "outsourcingUnitRate", 1.0);
			discountStrength = number(values, "discountStrength", 0.0);
			timeLimitSeconds = number(values, "timeLimitSeconds", 10800.0);
			maxNodes = integer(values, "maxNodes", 100000);
		}

		private FormalExperimentDataFactory.Scenario buildScenario() {
			FormalExperimentDataFactory.Scenario scenario = new FormalExperimentDataFactory.Scenario();
			scenario.timeScale = timeScale;
			scenario.dueWindowHalfWidth = dueWindowHalfWidth;
			scenario.setupCostCoefficient = setupCostCoefficient;
			scenario.outsourcingEnabled = !"none".equalsIgnoreCase(outsourcingModel);
			scenario.outsourcingUnitRate = outsourcingUnitRate;
			scenario.discountStrength = discountStrength;
			return scenario;
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

		private static String stripExtension(String fileName) {
			int dot = fileName.lastIndexOf('.');
			return dot < 0 ? fileName : fileName.substring(0, dot);
		}
	}
}
