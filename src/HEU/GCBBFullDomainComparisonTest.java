package HEU;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
import TWETBPC.GC.FixedInitialColumnSeed;
import TWETBPC.GC.InitialColumnBuilder;
import TWETBPC.GC.InitialColumnBundle;
import TWETBPC.GC.PricingMode;
import TWETBPC.IO.HeuristicSeedProvider;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.Pool;
import TWETBPC.Model.TWETColumn;

/**
 * 2026-05-28: 对照测试 正式 half-domain 双向 pricing 与 GCBB full-domain 函数定义域。
 * 其它 BPC 参数尽量保持一致，用于观察只放宽标签函数定义域后的效率变化。
 */
public class GCBBFullDomainComparisonTest {

	private static final String HEURISTIC_ENGINE = "HeuristicPricing";
	private static final String NORMAL_ENGINE = "GCNGBBStyleBidirectionalPricing";
	private static final String PARTIAL_DOMINANCE_ENGINE = "GCNGBBStylePartialDominancePricing";
	private static final String NG_DSSR_ENGINE = "GCNGBBStyleNgDssrPricing";
	private static final String NG_DSSR_PARTIAL_ENGINE = "GCNGBBStyleNgDssrPartialDominancePricing";
	private static final String NG_DSSR_GRAPH_PARTIAL_ENGINE = "GCNGBBStyleNgDssrGraphPartialDominancePricing";
	private static final String FULL_DOMAIN_ENGINE = "GCBBStyleBidirectionalFullDomainPricing";
	private static final String NODE_JOIN_ENGINE = "GCBBStyleBidirectionalFullDomainNodeJoinPricing";
	private static final String TIME_INDEXED_GRAPH_ENGINE = "TimeIndexedGraphPricing";
	private static final String TIME_INDEXED_GRAPH_RANK1_ENGINE = "TimeIndexedGraphRank1CutPricing";

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
		boolean zeroSetup = Boolean.getBoolean("twet.bpc.fullDomainCompare.zeroSetup");
		FixedInitialReference fixedInitialReference = prepareFixedInitialColumnReference(zeroSetup);

		ArrayList<RunRecord> records = new ArrayList<RunRecord>();
		ArrayList<String> lines = new ArrayList<String>();
		lines.add(
				"case,mode,status,incumbent,bound,gap,nodes,pricing,cols,pool,solve_s,root_s,heuristic_s,heuristic_calls,exact_engine,exact_s,exact_calls,master_lp_s,valid,fixed_seed_fingerprint,log");
		for (Path instance : instances) {
			if (shouldRunNormal(modeFilter)) {
				RunRecord normal = runOne(instance, false, outputDir, fixedInitialReference);
				records.add(normal);
				lines.add(normal.toCsvLine());
			}
			if (shouldRunFullDomain(modeFilter)) {
				RunRecord fullDomain = runOne(instance, true, outputDir, fixedInitialReference);
				records.add(fullDomain);
				lines.add(fullDomain.toCsvLine());
			}
			if (shouldRunNodeJoin(modeFilter)) {
				RunRecord nodeJoin = runOne(instance, false, true, outputDir, fixedInitialReference);
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

	private static RunRecord runOne(Path instance, boolean fullDomain, Path outputDir,
			FixedInitialReference fixedInitialReference) throws Exception {
		return runOne(instance, fullDomain, false, outputDir, fixedInitialReference);
	}

	private static RunRecord runOne(Path instance, boolean fullDomain, boolean nodeJoin, Path outputDir,
			FixedInitialReference fixedInitialReference) throws Exception {
		resetHeuristicSeed(instance);
		boolean zeroSetup = Boolean.getBoolean("twet.bpc.fullDomainCompare.zeroSetup");
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(instance.toString(), zeroSetup);
		TWETBPCConfig config = buildConfig(instance, fullDomain, nodeJoin);
		applyFixedInitialColumnReference(data, config, fixedInitialReference);
		String mode = runModeName(config, fullDomain, nodeJoin);
		Path log = outputDir.resolve(stripDat(instance.getFileName().toString()) + "-" + mode + ".log");
		// 2026-06-23: live trace 会在每个节点/阶段写文件，只用于诊断；
		// 默认关闭，避免对纯性能对比实验造成 I/O 和字符串格式化开销。
		if (Boolean.parseBoolean(System.getProperty("twet.bpc.fullDomainCompare.liveTrace", "false"))) {
			config.liveTraceLogPath = log.toString();
		}
		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		validateNgDssrMidpointProbeOverrides(solver.getContext().pricingMode);
		TWETSolveResult result = solver.solve();
		BPCTraceSummary summary = solver.getContext().traceSummary;
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);
		if (Boolean.getBoolean("twet.bpc.fullDomainCompare.incumbentColumnAudit")) {
			writeIncumbentColumnAudit(outputDir, stripDat(instance.getFileName().toString()), data,
					solver.getContext().pool, result);
		}

		Files.write(log, summary.getEventLines());
		String exactEngine = exactEngineName(config, fullDomain, nodeJoin);
		return new RunRecord(stripDat(instance.getFileName().toString()), mode, result.getStatus().toString(),
				result.getIncumbentCost(), result.getBestBound(),
				TanakaNoOutsourcingBPCTest.gapPercent(result.getBestBound(), result.getIncumbentCost()),
				result.getProcessedNodes(), summary.getPricingRounds(), result.getGeneratedColumns(),
				solver.getContext().pool.size(), summary.getSolveTimeSeconds(), summary.getRootSolveTimeSeconds(),
				seconds(summary.getPricingTimeNanos(), HEURISTIC_ENGINE),
				count(summary.getPricingCallCount(), HEURISTIC_ENGINE), exactEngine,
				formalPricingSeconds(summary.getPricingTimeNanos(), exactEngine),
				formalPricingCalls(summary.getPricingCallCount(), exactEngine),
				totalSeconds(summary.getMasterLpTimeNanos()), validation.isFeasible(),
				fixedInitialReference == null ? "" : fixedInitialReference.fingerprint,
				log.toString().replace('/', '\\'));
	}

	/**
	 * 在所有 mode 启动前只运行一次 reference ALNS；配置 snapshot 文件时可跨 Java 进程复用。
	 */
	private static FixedInitialReference prepareFixedInitialColumnReference(boolean zeroSetup) throws Exception {
		String snapshotFile = System.getProperty("twet.bpc.fullDomainCompare.fixedInitialSeedSnapshot", "").trim();
		if (!snapshotFile.isEmpty()) {
			Path snapshotPath = Path.of(snapshotFile);
			if (Files.exists(snapshotPath)) {
				FixedInitialReference snapshot = readFixedInitialReference(snapshotPath);
				System.out.printf(Locale.US,
						"Loaded fixed initial reference %s: columns=%d incumbentColumns=%d sourceInc=%.6f fingerprint=%s snapshot=%s%n",
						snapshot.reference, snapshot.seed.getInitialSequences().size(),
						snapshot.seed.getIncumbentSequences().size(), snapshot.sourceIncumbent,
						snapshot.fingerprint, snapshotPath);
				return snapshot;
			}
		}

		String referenceDir = System.getProperty("twet.bpc.fullDomainCompare.fixedInitialReferenceDir", "");
		if (referenceDir.isBlank()) {
			return null;
		}
		String referenceCase = System.getProperty("twet.bpc.fullDomainCompare.fixedInitialReferenceCase", "");
		List<Path> references = listInstances(Path.of(referenceDir), referenceCase);
		if (references.size() != 1) {
			throw new IllegalStateException("Expected exactly one fixed-initial reference instance, found "
					+ references.size() + " under " + referenceDir + " filter=" + referenceCase);
		}
		Path reference = references.get(0);
		String dueWindowKey = "twet.data.dueWindowHalfWidth";
		String oldDueWindow = System.getProperty(dueWindowKey);
		String referenceDueWindow = System.getProperty(
				"twet.bpc.fullDomainCompare.fixedInitialReferenceDueWindowHalfWidth", "0");
		Data referenceData;
		try {
			System.setProperty(dueWindowKey, referenceDueWindow);
			referenceData = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(reference.toString(), zeroSetup);
		} finally {
			if (oldDueWindow == null) {
				System.clearProperty(dueWindowKey);
			} else {
				System.setProperty(dueWindowKey, oldDueWindow);
			}
		}

		resetHeuristicSeed(reference);
		// reference seed 与待比较的 pricing mode 无关，固定使用 normal 配置构造一次。
		TWETBPCConfig referenceConfig = buildConfig(reference, false, false);
		referenceConfig.fixedInitialColumnSeed = null;
		Pool referencePool = new Pool(referenceData);
		InitialColumnBuilder referenceBuilder = new InitialColumnBuilder(referenceData, referenceConfig,
				referencePool, new HeuristicSeedProvider(referenceData, referenceConfig));
		InitialColumnBundle referenceBundle = referenceBuilder.build();

		ArrayList<List<Integer>> initialSequences = extractSequences(referencePool,
				referenceBundle.getInitialColumnIds());
		ArrayList<List<Integer>> incumbentSequences = extractSequences(referencePool,
				referenceBundle.getIncumbentColumnIds());
		ArrayList<Double> sourceInitialCosts = new ArrayList<Double>(referenceBundle.getInitialColumnIds().size());
		for (int id : referenceBundle.getInitialColumnIds()) {
			sourceInitialCosts.add(Double.valueOf(referencePool.getColumn(id).getCost()));
		}
		FixedInitialColumnSeed seed = new FixedInitialColumnSeed(initialSequences, incumbentSequences);
		FixedInitialReference snapshot = new FixedInitialReference(reference, seed, sourceInitialCosts,
				columnCost(referencePool, referenceBundle.getIncumbentColumnIds()), fixedSeedFingerprint(seed));
		if (!snapshotFile.isEmpty()) {
			writeFixedInitialReference(Path.of(snapshotFile), snapshot);
		}
		System.out.printf(Locale.US,
				"Prepared fixed initial reference %s: columns=%d incumbentColumns=%d sourceInc=%.6f fingerprint=%s%s%n",
				reference, initialSequences.size(), incumbentSequences.size(), snapshot.sourceIncumbent,
				snapshot.fingerprint, snapshotFile.isEmpty() ? "" : " snapshot=" + snapshotFile);
		return snapshot;
	}

	/** 以确定性文本格式保存跨进程共享的初始列快照。 */
	private static void writeFixedInitialReference(Path snapshotPath, FixedInitialReference snapshot) throws Exception {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("TWET_FIXED_INITIAL_REFERENCE_V1");
		lines.add("reference\t" + snapshot.reference);
		lines.add("sourceIncumbent\t" + Double.toString(snapshot.sourceIncumbent));
		lines.add("fingerprint\t" + snapshot.fingerprint);
		List<List<Integer>> initialSequences = snapshot.seed.getInitialSequences();
		lines.add("initial\t" + initialSequences.size());
		for (int index = 0; index < initialSequences.size(); index++) {
			lines.add(Double.toString(snapshot.sourceInitialCosts.get(index).doubleValue()) + "\t"
					+ encodeSequence(initialSequences.get(index)));
		}
		List<List<Integer>> incumbentSequences = snapshot.seed.getIncumbentSequences();
		lines.add("incumbent\t" + incumbentSequences.size());
		for (List<Integer> sequence : incumbentSequences) {
			lines.add(encodeSequence(sequence));
		}
		Path parent = snapshotPath.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(snapshotPath, lines, StandardCharsets.UTF_8);
	}

	/** 读取快照并重新计算 fingerprint，损坏或错配时在进入 BPC 前直接失败。 */
	private static FixedInitialReference readFixedInitialReference(Path snapshotPath) throws Exception {
		List<String> lines = Files.readAllLines(snapshotPath, StandardCharsets.UTF_8);
		int cursor = 0;
		if (lines.isEmpty() || !"TWET_FIXED_INITIAL_REFERENCE_V1".equals(lines.get(cursor++))) {
			throw new IllegalStateException("Unsupported fixed initial seed snapshot: " + snapshotPath);
		}
		Path reference = Path.of(valueAfterTab(lines.get(cursor++), "reference"));
		double sourceIncumbent = Double.parseDouble(valueAfterTab(lines.get(cursor++), "sourceIncumbent"));
		String storedFingerprint = valueAfterTab(lines.get(cursor++), "fingerprint");
		int initialCount = Integer.parseInt(valueAfterTab(lines.get(cursor++), "initial"));
		ArrayList<List<Integer>> initialSequences = new ArrayList<List<Integer>>(initialCount);
		ArrayList<Double> sourceInitialCosts = new ArrayList<Double>(initialCount);
		for (int index = 0; index < initialCount; index++) {
			String[] fields = lines.get(cursor++).split("\\t", 2);
			if (fields.length != 2) {
				throw new IllegalStateException("Malformed initial column in snapshot: " + snapshotPath);
			}
			sourceInitialCosts.add(Double.valueOf(fields[0]));
			initialSequences.add(decodeSequence(fields[1]));
		}
		int incumbentCount = Integer.parseInt(valueAfterTab(lines.get(cursor++), "incumbent"));
		ArrayList<List<Integer>> incumbentSequences = new ArrayList<List<Integer>>(incumbentCount);
		for (int index = 0; index < incumbentCount; index++) {
			incumbentSequences.add(decodeSequence(lines.get(cursor++)));
		}
		if (cursor != lines.size()) {
			throw new IllegalStateException("Unexpected trailing data in fixed initial seed snapshot: " + snapshotPath);
		}
		FixedInitialColumnSeed seed = new FixedInitialColumnSeed(initialSequences, incumbentSequences);
		String actualFingerprint = fixedSeedFingerprint(seed);
		if (!storedFingerprint.equals(actualFingerprint)) {
			throw new IllegalStateException("Fixed initial seed fingerprint mismatch: stored=" + storedFingerprint
					+ " actual=" + actualFingerprint + " snapshot=" + snapshotPath);
		}
		return new FixedInitialReference(reference, seed, sourceInitialCosts, sourceIncumbent, actualFingerprint);
	}

	private static String valueAfterTab(String line, String expectedKey) {
		String prefix = expectedKey + "\t";
		if (!line.startsWith(prefix)) {
			throw new IllegalStateException("Expected " + expectedKey + " in fixed initial seed snapshot");
		}
		return line.substring(prefix.length());
	}

	private static String encodeSequence(List<Integer> sequence) {
		StringBuilder encoded = new StringBuilder();
		for (int index = 0; index < sequence.size(); index++) {
			if (index > 0) {
				encoded.append(',');
			}
			encoded.append(sequence.get(index).intValue());
		}
		return encoded.toString();
	}

	private static List<Integer> decodeSequence(String encoded) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		if (encoded.isEmpty()) {
			return sequence;
		}
		for (String token : encoded.split(",")) {
			sequence.add(Integer.valueOf(token));
		}
		return sequence;
	}

	/**
	 * 目标实例只重评同一个不可变快照，不再重新运行 reference ALNS。
	 */
	private static void applyFixedInitialColumnReference(Data targetData, TWETBPCConfig targetConfig,
			FixedInitialReference snapshot) {
		if (snapshot == null) {
			return;
		}
		List<List<Integer>> initialSequences = snapshot.seed.getInitialSequences();
		List<List<Integer>> incumbentSequences = snapshot.seed.getIncumbentSequences();
		double expectedScale = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.fixedInitialExpectedCostScale", "1"));
		TWETColumnEvaluator targetEvaluator = new TWETColumnEvaluator(targetData);
		double maximumScaleError = 0.0;
		for (int index = 0; index < initialSequences.size(); index++) {
			List<Integer> sequence = initialSequences.get(index);
			double sourceCost = snapshot.sourceInitialCosts.get(index).doubleValue();
			double targetCost = targetEvaluator.evaluate(sequence);
			maximumScaleError = Math.max(maximumScaleError,
					Math.abs(targetCost - expectedScale * sourceCost));
		}
		double targetIncumbent = 0.0;
		for (List<Integer> sequence : incumbentSequences) {
			targetIncumbent += targetEvaluator.evaluate(sequence);
		}
		double tolerance = 1.0e-6 * Math.max(1.0, Math.abs(expectedScale * snapshot.sourceIncumbent));
		if (maximumScaleError > tolerance
				|| Math.abs(targetIncumbent - expectedScale * snapshot.sourceIncumbent) > tolerance) {
			throw new IllegalStateException(String.format(Locale.US,
					"Fixed initial columns do not scale as expected: sourceInc=%.6f targetInc=%.6f scale=%.6f maxColumnError=%.6g",
					snapshot.sourceIncumbent, targetIncumbent, expectedScale, maximumScaleError));
		}
		targetConfig.fixedInitialColumnSeed = snapshot.seed;
		System.out.printf(Locale.US,
				"Fixed initial columns from %s: columns=%d incumbentColumns=%d sourceInc=%.6f targetInc=%.6f scale=%.6f maxColumnError=%.6g fingerprint=%s%n",
				snapshot.reference, initialSequences.size(), incumbentSequences.size(), snapshot.sourceIncumbent,
				targetIncumbent, expectedScale, maximumScaleError, snapshot.fingerprint);
	}

	private static ArrayList<List<Integer>> extractSequences(Pool pool, List<Integer> columnIds) {
		ArrayList<List<Integer>> sequences = new ArrayList<List<Integer>>(columnIds.size());
		for (int id : columnIds) {
			sequences.add(new ArrayList<Integer>(pool.getColumn(id).getSequence()));
		}
		return sequences;
	}

	private static double columnCost(Pool pool, List<Integer> columnIds) {
		double total = 0.0;
		for (int id : columnIds) {
			total += pool.getColumn(id).getCost();
		}
		return total;
	}

	private static String fixedSeedFingerprint(FixedInitialColumnSeed seed) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		updateFingerprint(digest, "initial", seed.getInitialSequences());
		updateFingerprint(digest, "incumbent", seed.getIncumbentSequences());
		byte[] hash = digest.digest();
		StringBuilder value = new StringBuilder(hash.length * 2);
		for (byte item : hash) {
			value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
		}
		return value.toString();
	}

	private static void updateFingerprint(MessageDigest digest, String section, List<List<Integer>> sequences) {
		digest.update(section.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) ':');
		for (List<Integer> sequence : sequences) {
			digest.update((byte) '[');
			for (int job : sequence) {
				digest.update(Integer.toString(job).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) ',');
			}
			digest.update((byte) ']');
		}
	}

	private static String runModeName(TWETBPCConfig config, boolean fullDomain, boolean nodeJoin) {
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
		if (config.useTimeIndexedGraphPricing) {
			mode += "-timeGraph";
			if (config.useTimeIndexedGraphRank1CutPricing) {
				mode += "-rank1Cut";
			}
		}
		String completionBound = config.bidirectionalCompletionBoundRelaxation == null
				? "off" : config.bidirectionalCompletionBoundRelaxation.trim();
		if ((fullDomain || nodeJoin) && !completionBound.isEmpty() && !"off".equalsIgnoreCase(completionBound)) {
			mode += "-cb-" + completionBound;
			if (fullDomain && !config.bidirectionalCompletionBoundScalarPruning) {
				mode += "-scalarOff";
			}
		}
		if (config.useGCNGBBStyleNgDssrPartialDominancePricing) {
			mode += "-ngPartial-" + config.ngDssrInitialNgSetMode + ngDssrInitialModeSuffix(config)
					+ "-top" + config.ngDssrNonElementaryRouteUpdateLimit
					+ "-cand" + config.ngDssrNonElementaryRouteCandidateLimit;
		} else if (config.useGCNGBBStyleNgDssrGraphPartialDominancePricing) {
			mode += "-ngGraphPartial-" + config.ngDssrInitialNgSetMode + ngDssrInitialModeSuffix(config)
					+ "-top" + config.ngDssrNonElementaryRouteUpdateLimit
					+ "-cand" + config.ngDssrNonElementaryRouteCandidateLimit;
		} else if (config.useGCNGBBStyleNgDssrPricing) {
			mode += "-ng-" + config.ngDssrInitialNgSetMode + ngDssrInitialModeSuffix(config)
					+ "-top" + config.ngDssrNonElementaryRouteUpdateLimit
					+ "-cand" + config.ngDssrNonElementaryRouteCandidateLimit
					+ (config.useIncrementalSourcedDominanceGraph ? "-srcDom" : "-paperDom");
		}
		if ((config.useGCNGBBStyleNgDssrPricing || config.useGCNGBBStyleNgDssrPartialDominancePricing
				|| config.useGCNGBBStyleNgDssrGraphPartialDominancePricing)
				&& "minimumNewPairsSegment".equalsIgnoreCase(config.ngDssrNonElementaryRouteUpdateMode)) {
			mode += "-minSeg";
		}
		if (config.enableNgDssrHistoryWarmStart) {
			mode += "-ngHistW" + config.ngDssrHistoryWarmStartWindowSize;
		}
		if (config.enableNgDssrSameNodeWarmStart) {
			mode += "-ngNodeWarm";
		}
		if (config.enableNgDssrWindowRepeatabilityInitialFilter) {
			mode += "-ngWinRep";
		}
		if (config.ngDssrReturnRelaxedColumns) {
			mode += "-ngRelaxedColumns";
		}
		return mode;
	}

	private static String ngDssrInitialModeSuffix(TWETBPCConfig config) {
		String mode = config.ngDssrInitialNgSetMode == null ? "" : config.ngDssrInitialNgSetMode.trim();
		if ("dualPair".equalsIgnoreCase(mode) || "reducedCostPair".equalsIgnoreCase(mode)) {
			return "Coef" + config.ngDssrInitialNgPairCoefficient;
		}
		if (("nearestK".equalsIgnoreCase(mode) || "nearestRepeatHybrid".equalsIgnoreCase(mode)
				|| "perJobFeasiblePair".equalsIgnoreCase(mode)
				|| "perJobRepeatCost".equalsIgnoreCase(mode))
				&& config.ngDssrInitialNgSetSize < 0) {
			return "AutoN10";
		}
		return Integer.toString(config.ngDssrInitialNgSetSize);
	}

	private static String exactEngineName(TWETBPCConfig config, boolean fullDomain, boolean nodeJoin) {
		return config.useTimeIndexedGraphPricing
				? (config.useTimeIndexedGraphRank1CutPricing ? TIME_INDEXED_GRAPH_RANK1_ENGINE
						: TIME_INDEXED_GRAPH_ENGINE)
				: (nodeJoin ? NODE_JOIN_ENGINE : (fullDomain ? FULL_DOMAIN_ENGINE
				: (config.useGCNGBBStyleNgDssrPartialDominancePricing ? NG_DSSR_PARTIAL_ENGINE
				: (config.useGCNGBBStyleNgDssrGraphPartialDominancePricing ? NG_DSSR_GRAPH_PARTIAL_ENGINE
						: (config.useGCNGBBStyleNgDssrPricing ? NG_DSSR_ENGINE
						: (config.useGCNGBBStylePartialDominancePricing ? PARTIAL_DOMINANCE_ENGINE : NORMAL_ENGINE))))));
	}

	private static TWETBPCConfig buildConfig(Path instance, boolean fullDomain) {
		return buildConfig(instance, fullDomain, false);
	}

	private static void writeIncumbentColumnAudit(Path outputDir, String caseName, Data data, TWETBPC.LP.Pool pool,
			TWETSolveResult result) throws Exception {
		TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("columnId,storedCost,evaluatorCost,diff,size,source,seed,sequence");
		for (int columnId : result.getIncumbentColumnIds()) {
			TWETColumn column = pool.getColumn(columnId);
			double checked = evaluator.evaluate(column.getSequence());
			lines.add(String.format(Locale.US, "%d,%.12f,%.12f,%.12f,%d,%s,%s,\"%s\"", columnId,
					column.getCost(), checked, column.getCost() - checked, column.size(), column.getSource(),
					Boolean.toString(column.isSeedColumn()), column.getSequence().toString()));
		}
		Files.write(outputDir.resolve(caseName + ".incumbentAudit.csv"), lines);
	}

	/**
	 * 2026-07-26: 按实际定价器应用已经验证的默认组合。系统属性在此后读取，
	 * 因而仍可显式覆盖单项参数做 A/B，不再要求每个启动命令重复列出整套配置。
	 */
	static void applyBestPricingModeDefaults(TWETBPCConfig config,
			boolean timeIndexedGraph, boolean timeIndexedRank1, boolean ngDssr) {
		config.useTimeIndexedGraphRank1CutPricing = timeIndexedRank1;
		config.useTimeIndexedGraphPricing = timeIndexedGraph || timeIndexedRank1;
		config.useGCNGBBStyleNgDssrPricing = ngDssr;

		config.runALNSForSeed = true;
		config.alnsMaxRuntimeMillis = 60_000L;
		config.alnsUseSimulatedAnnealingAcceptance = false;
		config.initialHeuristicColumnHistoryMode = "best";
		config.enableTwoStageStrongBranching = true;
		config.strongBranchingCandidateLimit = 20;
		config.strongBranchingPhase2CandidateLimit = 0;
		config.strongBranchingPhase2MaxHeuristicPasses = 0;
		config.enableStrongBranchingLightweightRepair = true;
		config.enableStrongBranchingBranchImpliedPenalty = true;
		config.enableStrongBranchingPhaseOneRepair = true;
		config.enableDualBoundPruning = true;
		config.enableDualStabilization = false;
		config.enableRestrictedMasterIntegerHeuristic = false;
		config.enableRouteEnumeration = false;

		if (config.useTimeIndexedGraphPricing) {
			config.enableHeuristicPricing = false;
			config.enableTimeIndexedGraphDualWindow = true;
			config.timeIndexedGraphMaxExactPricingColumns = 300;
			config.bidirectionalCompletionBoundRelaxation = "off";
			config.bidirectionalCompletionBoundScalarPruning = false;
			config.bidirectionalCompletionBoundArcFixing = false;
			config.bidirectionalCompletionBoundSubtreeArcElimination = false;
			config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly = false;
			config.timeIndexedCompletionBoundScalarEnhancement = false;
			config.timeIndexedCompletionBoundWindowTightening = false;
			config.timeIndexedCompletionBoundArcFixing = true;
			config.timeIndexedCompletionBoundInRoundArcFixing = false;
			config.timeIndexedCompletionBoundCutLoopArcFixing = timeIndexedRank1;
			config.timeIndexedCompletionBoundSriAwareArcFixing = false;
			config.enableSubsetRowCutsForTimeIndexedGraph = timeIndexedRank1;
			config.subsetRowCutMemoryMode = "arcMemory";
			config.maxCutRounds = 8;
			config.maxSubsetRowCutAppearancesPerJob = 20;
			config.bidirectionalMidpointProbe = false;
			return;
		}

		if (ngDssr) {
			config.enableHeuristicPricing = true;
			config.ngDssrInitialNgSetMode = "nearestK";
			config.ngDssrInitialNgSetSize = -1;
			config.ngDssrNonElementaryRouteUpdateLimit = 20;
			config.ngDssrNonElementaryRouteCandidateLimit = 1000;
			config.ngDssrNonElementaryRouteUpdateMode = "minimumNewPairsSegment";
			config.useIncrementalSourcedDominanceGraph = true;
			config.enableNgDssrJoinEnvelopePrefilter = true;
			config.enableNgDssrJoinVisitProfilePruning = true;
			config.enableNgDssrWindowRepeatabilityInitialFilter = true;
			config.enableNgDssrHistoryWarmStart = false;
			config.enableNgDssrSameNodeWarmStart = false;
			config.bidirectionalJoinBestThresholdMode = "bestUB";
			config.bidirectionalCompletionBoundRelaxation = "allCycles";
			config.bidirectionalCompletionBoundScalarPruning = true;
			config.bidirectionalCompletionBoundArcFixing = true;
			config.bidirectionalCompletionBoundSubtreeArcElimination = false;
			config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly = true;
			config.bidirectionalMidpointProbe = true;
			config.bidirectionalMidpointProbePopLimit = 10000;
			config.bidirectionalMidpointProbeScore = "time";
			config.bidirectionalMidpointProbeEarlyStopRatio = 1.5;
			config.bidirectionalMidpointProbeDssrImbalanceThreshold = 2.0;
			config.enableTimeIndexedPreHeuristicPricing = false;
			config.enableTimeIndexedRootPreprocessingForNgDssr = true;
			config.timeIndexedRootPreprocessingSeedElementaryColumns = true;
			config.timeIndexedRootPreprocessingSeedColumnLimit = 200;
			config.timeIndexedCompletionBoundScalarEnhancement = true;
			config.timeIndexedCompletionBoundWindowTightening = true;
			config.timeIndexedCompletionBoundArcFixing = true;
			config.timeIndexedCompletionBoundInRoundArcFixing = false;
			config.timeIndexedCompletionBoundCutLoopArcFixing = true;
		}
	}

	private static TWETBPCConfig buildConfig(Path instance, boolean fullDomain, boolean nodeJoin) {
		TWETBPCConfig config = new TWETBPCConfig();
		boolean timeIndexedGraph = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedGraphPricing",
				Boolean.toString(config.useTimeIndexedGraphPricing)));
		boolean timeIndexedRank1 = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedGraphRank1CutPricing",
				Boolean.toString(config.useTimeIndexedGraphRank1CutPricing)));
		boolean ngDssr = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssr",
				Boolean.toString(config.useGCNGBBStyleNgDssrPricing)));
		applyBestPricingModeDefaults(config, timeIndexedGraph, timeIndexedRank1, ngDssr);
		config.instanceName = stripDat(instance.getFileName().toString()) + "-no-outsourcing-domain";
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.reuseConfiguredBestSolution = false;
		// 2026-07-01: full-domain 主线默认沿用全局 ALNS seed 设置；系统属性只做显式覆盖。
		config.runALNSForSeed = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.runALNSForSeed", Boolean.toString(config.runALNSForSeed)));
		config.alnsMaxRuntimeMillis = Long.getLong("twet.bpc.fullDomainCompare.alnsMaxMillis",
				config.alnsMaxRuntimeMillis);
		config.alnsMaxNoImproveIterations = Integer.getInteger("twet.bpc.fullDomainCompare.alnsMaxNoImprove",
				config.alnsMaxNoImproveIterations);
		config.alnsUseSimulatedAnnealingAcceptance = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.alnsSA", Boolean.toString(config.alnsUseSimulatedAnnealingAcceptance)));
		config.alnsSimulatedAnnealingInitialTemperatureRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.alnsSAT0Ratio",
				Double.toString(config.alnsSimulatedAnnealingInitialTemperatureRatio)));
		config.alnsSimulatedAnnealingCoolingRate = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.alnsSACooling",
				Double.toString(config.alnsSimulatedAnnealingCoolingRate)));
		config.initialHeuristicColumnHistoryMode = System.getProperty(
				"twet.bpc.fullDomainCompare.initialHeuristicColumnHistoryMode",
				config.initialHeuristicColumnHistoryMode);
		config.acceptedSolutionHistoryLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.acceptedSolutionHistoryLimit", config.acceptedSolutionHistoryLimit);
		config.enableHeuristicPricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableHeuristicPricing",
				Boolean.toString(config.enableHeuristicPricing)));
		config.enableHeuristicDualProfitableWindow = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableHeuristicDualProfitableWindow",
				Boolean.toString(config.enableHeuristicDualProfitableWindow)));
		config.enableBPCConsoleOutput = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableConsoleOutput",
				Boolean.toString(config.enableBPCConsoleOutput)));
		config.enableRestrictedMasterIntegerHeuristic = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableRestrictedMasterIntegerHeuristic",
				Boolean.toString(config.enableRestrictedMasterIntegerHeuristic)));
		config.restrictedMasterIntegerHeuristicTimeLimitSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.restrictedMasterIntegerTimeLimit",
				Double.toString(config.restrictedMasterIntegerHeuristicTimeLimitSeconds)));
		config.restrictedMasterIntegerHeuristicLargeInstanceThreshold = Integer.parseInt(System.getProperty(
				"twet.bpc.fullDomainCompare.restrictedMasterIntegerLargeThreshold",
				System.getProperty("twet.bpc.fullDomainCompare.restrictedMasterIntegerLargeInstanceThreshold",
						Integer.toString(config.restrictedMasterIntegerHeuristicLargeInstanceThreshold))));
		config.restrictedMasterIntegerHeuristicLargeInstanceTimeLimitSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.restrictedMasterIntegerLargeTimeLimit",
				System.getProperty("twet.bpc.fullDomainCompare.restrictedMasterIntegerLargeInstanceTimeLimit",
						Double.toString(config.restrictedMasterIntegerHeuristicLargeInstanceTimeLimitSeconds))));
		config.restrictedMasterIntegerHeuristicLargeInstanceReducedCostColumnLimit = Integer.parseInt(System.getProperty(
				"twet.bpc.fullDomainCompare.restrictedMasterIntegerLargeInstanceReducedCostColumnLimit",
				Integer.toString(config.restrictedMasterIntegerHeuristicLargeInstanceReducedCostColumnLimit)));
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
		config.diagnosticHeuristicPricingDetails = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.heuristicPricingDiagnostics",
				Boolean.toString(config.diagnosticHeuristicPricingDetails)));
		config.diagnosticHeuristicExactMissAnalysis = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.heuristicExactMissAnalysis",
				Boolean.toString(config.diagnosticHeuristicExactMissAnalysis)));
		config.heuristicPricingStopUnproductiveSeedAfter20 = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.heuristicStopUnproductiveSeedAfter20",
				Boolean.toString(config.heuristicPricingStopUnproductiveSeedAfter20)));
		config.heuristicPricingCollectNonBestNegativeMoves = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.heuristicCollectNonBestNegativeMoves",
				Boolean.toString(config.heuristicPricingCollectNonBestNegativeMoves)));
		config.heuristicPricingNonBestMovesPerIteration = Integer.getInteger(
				"twet.bpc.fullDomainCompare.heuristicNonBestMovesPerIteration",
				config.heuristicPricingNonBestMovesPerIteration);
		config.heuristicPricingNonBestColumnsPerSeed = Integer.getInteger(
				"twet.bpc.fullDomainCompare.heuristicNonBestColumnsPerSeed",
				config.heuristicPricingNonBestColumnsPerSeed);
		config.heuristicPricingPrecomputeArcCompatibility = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.heuristicPrecomputeArcCompatibility",
				Boolean.toString(config.heuristicPricingPrecomputeArcCompatibility)));
		config.heuristicPricingPrecomputeMoveDuals = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.heuristicPrecomputeMoveDuals",
				Boolean.toString(config.heuristicPricingPrecomputeMoveDuals)));

		config.enableNodeLocalHorizonImprovement = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.nodeLocalHorizonImprove",
				Boolean.toString(config.enableNodeLocalHorizonImprovement)));
		config.nodeLocalHorizonImprovementNodeId = Integer.getInteger(
				"twet.bpc.fullDomainCompare.nodeLocalHorizonImproveNode",
				config.nodeLocalHorizonImprovementNodeId);
		config.nodeLocalHorizonImprovementTimeLimitSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.nodeLocalHorizonTimeLimit",
				Double.toString(config.nodeLocalHorizonImprovementTimeLimitSeconds)));
		config.nodeLocalHorizonImprovementUseCplex = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.nodeLocalHorizonUseCplex",
				Boolean.toString(config.nodeLocalHorizonImprovementUseCplex)));
		config.nodeLocalHorizonImprovementUseCp = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.nodeLocalHorizonUseCp",
				Boolean.toString(config.nodeLocalHorizonImprovementUseCp)));
		config.maxNodes = Integer.getInteger("twet.bpc.fullDomainCompare.maxNodes", 20000);
		config.solveTimeLimitSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.solveTimeLimitSeconds",
				Double.toString(config.solveTimeLimitSeconds)));
		config.cplexRootAlgorithm = System.getProperty(
				"twet.bpc.fullDomainCompare.cplexRootAlgorithm", config.cplexRootAlgorithm);
		config.maxHeuristicPricingColumns = Integer.getInteger("twet.bpc.fullDomainCompare.maxHeuristicColumns",
				300);
		config.heuristicPricingPoolSize = Integer.getInteger("twet.bpc.fullDomainCompare.heuristicPoolSize",
				300);
		config.maxExactPricingColumns = Integer.getInteger("twet.bpc.fullDomainCompare.maxExactColumns", 5000);
		config.timeIndexedGraphMaxExactPricingColumns = Integer.getInteger(
				"twet.bpc.fullDomainCompare.timeIndexedGraphMaxExactColumns",
				config.timeIndexedGraphMaxExactPricingColumns);
		config.branchSeedColumnLimit = Integer.getInteger("twet.bpc.fullDomainCompare.branchSeedColumnLimit", 5000);
		config.enableUndirectedAdjacencyBranching = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableUndirectedAdjacencyBranching",
				Boolean.toString(config.enableUndirectedAdjacencyBranching)));
		config.useTimeIndexedGraphPricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedGraphPricing",
				Boolean.toString(config.useTimeIndexedGraphPricing)));
		config.useTimeIndexedGraphRank1CutPricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedGraphRank1CutPricing",
				Boolean.toString(config.useTimeIndexedGraphRank1CutPricing)));
		if (config.useTimeIndexedGraphRank1CutPricing) {
			config.useTimeIndexedGraphPricing = true;
		}
		config.enableTimeIndexedGraphDualWindow = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedGraphDualWindow",
				Boolean.toString(config.enableTimeIndexedGraphDualWindow)));
		config.timeIndexedDualWindowRecheckDiagnostics = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedDualWindowRecheckDiagnostics",
				Boolean.toString(config.timeIndexedDualWindowRecheckDiagnostics)));
		config.enableTimeIndexedPreHeuristicPricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedPreHeuristicPricing",
				Boolean.toString(config.enableTimeIndexedPreHeuristicPricing)));
		config.enableTimeIndexedPreHeuristicInStrongBranchingPhase2 = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedPreHeuristicInStrongBranchingPhase2",
				Boolean.toString(config.enableTimeIndexedPreHeuristicInStrongBranchingPhase2)));
		config.timeIndexedPreHeuristicColumnLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.timeIndexedPreHeuristicColumnLimit",
				config.timeIndexedPreHeuristicColumnLimit);
		config.enableTimeIndexedRootPreprocessingForNgDssr = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedRootPreprocessingForNgDssr",
				Boolean.toString(config.enableTimeIndexedRootPreprocessingForNgDssr)));
		config.timeIndexedRootPreprocessingSeedElementaryColumns = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedRootPreprocessingSeedElementaryColumns",
				Boolean.toString(config.timeIndexedRootPreprocessingSeedElementaryColumns)));
		config.timeIndexedRootPreprocessingSeedColumnLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.timeIndexedRootPreprocessingSeedColumnLimit",
				config.timeIndexedRootPreprocessingSeedColumnLimit);
		config.outsourcingModel = System.getProperty("twet.bpc.fullDomainCompare.outsourcingModel",
				config.outsourcingModel);
		config.enableDualStabilization = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.dualStabilization",
				Boolean.toString(config.enableDualStabilization)));
		config.dualStabilizationSmoothingRule = System.getProperty(
				"twet.bpc.fullDomainCompare.dualStabilizationSmoothingRule",
				config.dualStabilizationSmoothingRule);
		config.dualStabilizationAlpha = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.dualStabilizationAlpha",
				Double.toString(config.dualStabilizationAlpha)));
		config.dualStabilizationAlphaIncreaseFraction = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.dualStabilizationAlphaIncreaseFraction",
				Double.toString(config.dualStabilizationAlphaIncreaseFraction)));
		config.dualStabilizationAlphaDecreaseStep = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.dualStabilizationAlphaDecreaseStep",
				Double.toString(config.dualStabilizationAlphaDecreaseStep)));
		config.dualStabilizationReducedCostTolerance = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.dualStabilizationReducedCostTolerance",
				Double.toString(config.dualStabilizationReducedCostTolerance)));
		config.enableDualBoundPruning = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.dualBoundPruning",
				Boolean.toString(config.enableDualBoundPruning)));
		config.dualBoundPruningTolerance = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.dualBoundPruningTolerance",
				Double.toString(config.dualBoundPruningTolerance)));
		config.enableTwoStageStrongBranching = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.strongBranching",
				Boolean.toString(config.enableTwoStageStrongBranching)));
		config.strongBranchingCandidateLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.strongBranchingCandidateLimit",
				config.strongBranchingCandidateLimit);
		config.strongBranchingPhase2CandidateLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.strongBranchingPhase2CandidateLimit",
				config.strongBranchingPhase2CandidateLimit);
		config.strongBranchingPhase2MaxHeuristicPasses = Integer.getInteger(
				"twet.bpc.fullDomainCompare.strongBranchingPhase2MaxHeuristicPasses",
				config.strongBranchingPhase2MaxHeuristicPasses);
		config.strongBranchingScoreEpsilon = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.strongBranchingScoreEpsilon",
				Double.toString(config.strongBranchingScoreEpsilon)));
		// 2026-07-01: domain-filtered all-row slack repair 已通过实验确认净效果变慢，
		// 暂停 common runner 的系统属性入口，避免历史命令残留参数误开；底层代码保留供后续复核。
		config.enableStrongBranchingDomainRepair = false;
		boolean defaultStrongBranchingLightweightRepair = config.enableTwoStageStrongBranching;
		config.enableStrongBranchingLightweightRepair = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.strongBranchingLightweightRepair",
				Boolean.toString(defaultStrongBranchingLightweightRepair)));
		config.enableStrongBranchingBranchImpliedPenalty = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.strongBranchingBranchImpliedPenalty",
				Boolean.toString(config.enableStrongBranchingBranchImpliedPenalty)));
		config.enableStrongBranchingPhaseOneRepair = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.strongBranchingPhaseOneRepair",
				Boolean.toString(config.enableStrongBranchingPhaseOneRepair)));
		config.enableRouteEnumeration = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.routeEnumeration",
				Boolean.toString(config.enableRouteEnumeration)));
		config.routeEnumerationAbsoluteGapThreshold = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.routeEnumerationGapThreshold",
				Double.toString(config.routeEnumerationAbsoluteGapThreshold)));
		config.routeEnumerationColumnLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.routeEnumerationColumnLimit",
				config.routeEnumerationColumnLimit);
		config.routeEnumerationUseCompletionBound = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.routeEnumerationUseCompletionBound",
				Boolean.toString(config.routeEnumerationUseCompletionBound)));
		config.routeEnumerationUseExactOutsourcingSuffixBound = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.routeEnumerationUseExactOutsourcingSuffixBound",
				Boolean.toString(config.routeEnumerationUseExactOutsourcingSuffixBound)));
		config.routeEnumerationUseTimeIndexedWindow = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.routeEnumerationUseTimeIndexedWindow",
				Boolean.toString(config.routeEnumerationUseTimeIndexedWindow)));
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
		config.useIncrementalSourcedDominanceGraph = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.incrementalSourcedDominance",
				Boolean.toString(config.useIncrementalSourcedDominanceGraph)));
		config.useGCNGBBStyleNgDssrPartialDominancePricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrPartialDominance",
				Boolean.toString(config.useGCNGBBStyleNgDssrPartialDominancePricing)));
		config.useGCNGBBStyleNgDssrGraphPartialDominancePricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrGraphPartialDominance",
				Boolean.toString(config.useGCNGBBStyleNgDssrGraphPartialDominancePricing)));
		config.ngDssrInitialNgSetMode = System.getProperty("twet.bpc.fullDomainCompare.ngDssrInitialMode",
				config.ngDssrInitialNgSetMode);
		config.ngDssrInitialNgSetSize = Integer.getInteger("twet.bpc.fullDomainCompare.ngDssrInitialSize",
				config.ngDssrInitialNgSetSize);
		config.ngDssrInitialNgPairCoefficient = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrInitialPairCoefficient",
				Double.toString(config.ngDssrInitialNgPairCoefficient)));
		config.ngDssrNonElementaryRouteUpdateLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.ngDssrRouteUpdateLimit",
				config.ngDssrNonElementaryRouteUpdateLimit);
		config.ngDssrNonElementaryRouteCandidateLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.ngDssrRouteCandidateLimit",
				config.ngDssrNonElementaryRouteCandidateLimit);
		config.ngDssrNonElementaryRouteUpdateMode = System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrRouteUpdateMode",
				config.ngDssrNonElementaryRouteUpdateMode);
		config.enableNgDssrHistoryWarmStart = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrHistoryWarmStart",
				Boolean.toString(config.enableNgDssrHistoryWarmStart)));
		config.enableNgDssrSameNodeWarmStart = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrSameNodeWarmStart",
				Boolean.toString(config.enableNgDssrSameNodeWarmStart)));
		config.ngDssrSameNodeWarmStartWindowSize = Integer.getInteger(
				"twet.bpc.fullDomainCompare.ngDssrSameNodeWarmStartWindow",
				config.ngDssrSameNodeWarmStartWindowSize);
		config.ngDssrSameNodeWarmStartPerJobLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.ngDssrSameNodeWarmStartPerJobLimit",
				config.ngDssrSameNodeWarmStartPerJobLimit);
		config.ngDssrSameNodeWarmStartGlobalPairLimit = Integer.getInteger(
				"twet.bpc.fullDomainCompare.ngDssrSameNodeWarmStartGlobalPairLimit",
				config.ngDssrSameNodeWarmStartGlobalPairLimit);
		config.ngDssrSameNodeWarmStartTriggerRounds = Integer.getInteger(
				"twet.bpc.fullDomainCompare.ngDssrSameNodeWarmStartTriggerRounds",
				config.ngDssrSameNodeWarmStartTriggerRounds);
		config.ngDssrHistoryWarmStartWindowSize = Integer.getInteger(
				"twet.bpc.fullDomainCompare.ngDssrHistoryWindow", config.ngDssrHistoryWarmStartWindowSize);
		config.ngDssrHistoryWarmStartFrequencyThreshold = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrHistoryFrequencyThreshold",
				Double.toString(config.ngDssrHistoryWarmStartFrequencyThreshold)));
		config.ngDssrHistoryWarmStartHighConfidenceThreshold = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrHistoryHighConfidenceThreshold",
				Double.toString(config.ngDssrHistoryWarmStartHighConfidenceThreshold)));
		config.ngDssrHistoryWarmStartUseRoot = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrHistoryUseRoot",
				Boolean.toString(config.ngDssrHistoryWarmStartUseRoot)));
		config.enableNgDssrWindowRepeatabilityInitialFilter = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrWindowRepeatabilityFilter",
				Boolean.toString(config.enableNgDssrWindowRepeatabilityInitialFilter)));
		config.enableNgDssrJoinEnvelopeCompression = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrJoinEnvelopeCompression",
				Boolean.toString(config.enableNgDssrJoinEnvelopeCompression)));
		config.enableNgDssrJoinEnvelopePrefilter = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrJoinEnvelopePrefilter",
				Boolean.toString(config.enableNgDssrJoinEnvelopePrefilter)));
		config.enableNgDssrJoinVisitProfilePruning = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrJoinVisitProfilePruning",
				Boolean.toString(config.enableNgDssrJoinVisitProfilePruning)));
		config.ngDssrReturnRelaxedColumns = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrReturnRelaxedColumns",
				Boolean.toString(config.ngDssrReturnRelaxedColumns)));
		config.enableSubsetRowCutsForPartialDominance = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableSubsetRowCutsForPartialDominance",
				Boolean.toString(config.enableSubsetRowCutsForPartialDominance)));
		config.enableSubsetRowCutsForTimeIndexedGraph = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.enableSubsetRowCutsForTimeIndexedGraph",
				Boolean.toString(config.enableSubsetRowCutsForTimeIndexedGraph)));
		config.maxCutRounds = Integer.getInteger(
				"twet.bpc.fullDomainCompare.maxCutRounds", config.maxCutRounds);
		config.maxSubsetRowCutsPerRound = Integer.getInteger(
				"twet.bpc.fullDomainCompare.maxSubsetRowCutsPerRound", config.maxSubsetRowCutsPerRound);
		config.maxSubsetRowCutsPerNode = Integer.getInteger(
				"twet.bpc.fullDomainCompare.maxSubsetRowCutsPerNode", config.maxSubsetRowCutsPerNode);
		config.maxSubsetRowCutAppearancesPerJob = Integer.getInteger(
				"twet.bpc.fullDomainCompare.maxSubsetRowCutAppearancesPerJob",
				config.maxSubsetRowCutAppearancesPerJob);
		config.subsetRowCutMemoryMode = System.getProperty("twet.bpc.fullDomainCompare.subsetRowCutMemoryMode",
				config.subsetRowCutMemoryMode);
		config.forwardLabelQueueOrdering = "time";
		config.bidirectionalLabelQueueOrdering = "time";
		config.bidirectionalJoinBestThresholdMode = System.getProperty(
				"twet.bpc.fullDomainCompare.joinBestMode", config.bidirectionalJoinBestThresholdMode);
		config.bidirectionalJoinRangeRestrictedLowerBound = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.joinRangeLB",
				Boolean.toString(config.bidirectionalJoinRangeRestrictedLowerBound)));
		config.bidirectionalCompletionBoundRelaxation = System.getProperty(
				"twet.bpc.fullDomainCompare.completionBound", config.bidirectionalCompletionBoundRelaxation);
		config.bidirectionalCompletionBoundQueueOrdering = System.getProperty(
				"twet.bpc.fullDomainCompare.completionBoundQueue",
				config.bidirectionalCompletionBoundQueueOrdering);
		config.bidirectionalCompletionBoundScalarPruning = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.completionBoundScalar",
				Boolean.toString(config.bidirectionalCompletionBoundScalarPruning)));
		config.timeIndexedCompletionBoundScalarEnhancement = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedCompletionBoundScalar",
				Boolean.toString(config.timeIndexedCompletionBoundScalarEnhancement)));
		config.timeIndexedCompletionBoundWindowTightening = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedCompletionBoundWindow",
				Boolean.toString(config.timeIndexedCompletionBoundWindowTightening)));
		config.timeIndexedCompletionBoundArcFixing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedCompletionBoundArcFixing",
				Boolean.toString(config.timeIndexedCompletionBoundArcFixing)));
		config.timeIndexedCompletionBoundInRoundArcFixing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedCompletionBoundInRoundArcFixing",
				Boolean.toString(config.timeIndexedCompletionBoundInRoundArcFixing)));
		config.timeIndexedCompletionBoundSriAwareArcFixing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedCompletionBoundSriAwareArcFixing",
				Boolean.toString(config.timeIndexedCompletionBoundSriAwareArcFixing)));
		config.timeIndexedCompletionBoundCutLoopArcFixing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.timeIndexedCompletionBoundCutLoopArcFixing",
				Boolean.toString(config.timeIndexedCompletionBoundCutLoopArcFixing)));
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
		config.bidirectionalMidpointProbeMoveRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeMoveRatio",
				Double.toString(config.bidirectionalMidpointProbeMoveRatio)));
		config.bidirectionalMidpointProbeScore = System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeScore", config.bidirectionalMidpointProbeScore);
		config.bidirectionalMidpointProbeTimeTolerance = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeTimeTolerance",
				Double.toString(config.bidirectionalMidpointProbeTimeTolerance)));
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
		config.bidirectionalMidpointProbeHighImbalanceRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeHighImbalanceRatio",
				Double.toString(config.bidirectionalMidpointProbeHighImbalanceRatio)));
		config.bidirectionalMidpointProbeReuseWithinDssr = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeReuseWithinDssr",
				Boolean.toString(config.bidirectionalMidpointProbeReuseWithinDssr)));
		config.bidirectionalMidpointProbeDssrImbalanceThreshold = Double.parseDouble(System.getProperty(
				"twet.bpc.fullDomainCompare.midpointProbeDssrImbalanceThreshold",
				Double.toString(config.bidirectionalMidpointProbeDssrImbalanceThreshold)));
		config.ngDssrExtensionTimingDiagnostics = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.fullDomainCompare.ngDssrExtensionTimingDiagnostics",
				Boolean.toString(config.ngDssrExtensionTimingDiagnostics)));
		return config;
	}

	/**
	 * 2026-07-30: ng-DSSR 使用 7 月 23 日后的固定 time-probe。旧旋钮仍服务其他双向
	 * pricing，但在 ng-DSSR 比较 runner 中显式设置会形成无效 A/B，因此直接拒绝。
	 */
	private static void validateNgDssrMidpointProbeOverrides(PricingMode pricingMode) {
		if (!pricingMode.usesNgDssrPricing()) {
			return;
		}
		String prefix = "twet.bpc.fullDomainCompare.";
		String requestedScore = System.getProperty(prefix + "midpointProbeScore");
		if (requestedScore != null && !"time".equalsIgnoreCase(requestedScore.trim())) {
			throw new IllegalArgumentException(prefix + "midpointProbeScore=" + requestedScore
					+ " is unsupported by ng-DSSR; effective scoreMode is fixed to time");
		}
		String[] unsupported = new String[] {
				"midpointProbeMaxCandidates",
				"midpointProbeMoveRatio",
				"midpointProbeTimeTolerance",
				"midpointProbeTieScore",
				"midpointProbeTieTolerance",
				"midpointProbeExtraCandidates",
				"midpointProbeBracket",
				"midpointProbeHighImbalanceRatio"
		};
		for (String suffix : unsupported) {
			if (System.getProperty(prefix + suffix) != null) {
				throw new IllegalArgumentException(prefix + suffix
						+ " belongs to the legacy midpoint probe and is unsupported by ng-DSSR");
			}
		}
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

	/**
	 * Dual stabilization 会给正式定价附加 [stabilized.*] 或 [true] 口径。
	 * CSV 应汇总这两类正式调用，但不能把 repair/strong-branching 试探混入 exact 统计。
	 */
	private static int formalPricingCalls(Map<String, Integer> counter, String engine) {
		int total = count(counter, engine);
		for (Map.Entry<String, Integer> entry : counter.entrySet()) {
			if (isFormalStabilizedPricingKey(entry.getKey(), engine)) {
				total += entry.getValue().intValue();
			}
		}
		return total;
	}

	private static double formalPricingSeconds(Map<String, Long> counter, String engine) {
		long total = counter.containsKey(engine) ? counter.get(engine).longValue() : 0L;
		for (Map.Entry<String, Long> entry : counter.entrySet()) {
			if (isFormalStabilizedPricingKey(entry.getKey(), engine)) {
				total += entry.getValue().longValue();
			}
		}
		return total / 1_000_000_000.0;
	}

	private static boolean isFormalStabilizedPricingKey(String key, String engine) {
		return key != null && (key.startsWith(engine + "[stabilized.") || key.equals(engine + "[true]"));
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

	/** 同一次比较中供所有 pricing mode 共享的 reference seed 快照。 */
	private static final class FixedInitialReference {
		final Path reference;
		final FixedInitialColumnSeed seed;
		final ArrayList<Double> sourceInitialCosts;
		final double sourceIncumbent;
		final String fingerprint;

		FixedInitialReference(Path reference, FixedInitialColumnSeed seed, List<Double> sourceInitialCosts,
				double sourceIncumbent, String fingerprint) {
			this.reference = reference;
			this.seed = seed;
			this.sourceInitialCosts = new ArrayList<Double>(sourceInitialCosts);
			this.sourceIncumbent = sourceIncumbent;
			this.fingerprint = fingerprint;
		}
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
		final String fixedSeedFingerprint;
		final String logPath;

		RunRecord(String caseName, String mode, String status, double incumbent, double bound, double gap, int nodes,
				int pricingRounds, int generatedColumns, int poolSize, double solveSeconds, double rootSeconds,
				double heuristicSeconds, int heuristicCalls, String exactEngine, double exactSeconds, int exactCalls,
				double masterLpSeconds, boolean valid, String fixedSeedFingerprint, String logPath) {
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
			this.fixedSeedFingerprint = fixedSeedFingerprint;
			this.logPath = logPath;
		}

		String toCsvLine() {
			return String.join(",", quote(caseName), quote(mode), quote(status), fmt(incumbent), fmt(bound), fmt(gap),
					String.valueOf(nodes), String.valueOf(pricingRounds), String.valueOf(generatedColumns),
					String.valueOf(poolSize), fmt(solveSeconds), fmt(rootSeconds), fmt(heuristicSeconds),
					String.valueOf(heuristicCalls), quote(exactEngine), fmt(exactSeconds),
					String.valueOf(exactCalls), fmt(masterLpSeconds), String.valueOf(valid),
					quote(fixedSeedFingerprint), quote(logPath));
		}
	}
}
