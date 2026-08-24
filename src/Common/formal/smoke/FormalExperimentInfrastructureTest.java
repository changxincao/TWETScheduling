package Common.formal.smoke;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import Common.formal.FormalExperimentDataFactory;
import Common.formal.FormalExperimentRunner;
import Common.formal.FormalExperimentSuiteGenerator;
import TWETBPC.TWETBPCConfig;
import TWETBPC.GC.FixedInitialColumnSeed;
import TWETBPC.GC.InitialColumnBuilder;
import TWETBPC.GC.InitialColumnBundle;
import TWETBPC.IO.FixedInitialSeedSnapshotIO;
import TWETBPC.IO.HeuristicSeedProvider;
import TWETBPC.LP.Pool;

/** 不启动 CPLEX，验证正式数据派生、manifest 和固定初始列快照。 */
public final class FormalExperimentInfrastructureTest {

	private FormalExperimentInfrastructureTest() {
	}

	public static void main(String[] args) throws Exception {
		Path root = Path.of("tmp", "formal-experiment-infrastructure-test");
		reset(root);
		Path dataRoot = root.resolve("data");
		Path sourceDir = dataRoot.resolve("40-1");
		Files.createDirectories(sourceDir);
		ArrayList<String> jobs40 = new ArrayList<String>();
		jobs40.add("40");
		for (int job = 1; job <= 40; job++) {
			jobs40.add((5 + job) + " " + (20 + 3 * job) + " " + (1 + job % 3) + " " + (2 + job % 4));
		}
		Files.write(sourceDir.resolve("wet040_001.dat"), jobs40, StandardCharsets.UTF_8);
		Path sourceDir50 = dataRoot.resolve("50-1");
		Files.createDirectories(sourceDir50);
		ArrayList<String> jobs50 = new ArrayList<String>();
		jobs50.add("50");
		for (int job = 1; job <= 50; job++) {
			jobs50.add((5 + job) + " " + (20 + job) + " 1 2");
		}
		Files.write(sourceDir50.resolve("wet050_001.dat"), jobs50, StandardCharsets.UTF_8);

		Path suite = root.resolve("suite");
		Files.createDirectories(suite.resolve("manifests"));
		Files.writeString(suite.resolve("manifests/outsourcing-formulation.tsv"), "obsolete",
				StandardCharsets.UTF_8);
		Files.writeString(suite.resolve("manifests/outsourcing-price.tsv"), "obsolete",
				StandardCharsets.UTF_8);
		FormalExperimentSuiteGenerator.main(new String[] {
				"--dataRoot=" + dataRoot, "--outputRoot=" + suite, "--sizes=20,50", "--casesPerSize=1",
				"--maxNodes=10" });
		String manifest = Files.readString(suite.resolve("manifest.tsv"), StandardCharsets.UTF_8);
		assertContains(manifest, "dependsOn", "dependency column");
		assertContains(manifest, "\taction\ttaskSetId\tsize\tmachines\tsetupType\t"
				+ "scaleLevel\twindowLevel\talgorithm\toutsourcingModel\toutsourcingRate\t"
				+ "discountLevel", "scheduler selection metadata columns");
		assertContains(manifest, "\tsolve\tn020-set01\t20\t2\trandom\tbase\tzero\t"
				+ "NG_DSSR\t\t\tnot-applicable", "pricing selection metadata values");
		assertContains(manifest, "NG_DSSR", "ng-DSSR row");
		assertContains(manifest, "TIME_INDEXED", "time-indexed row");
		assertContains(manifest, "TIME_INDEXED_SRI", "rank-1 row");
		assertContains(manifest, "\tseed-", "solver dependency");
		assertContains(manifest, "--action=\"seed\"", "seed task");
		assertContains(manifest, "Common.formal.FormalExperimentRunner", "formal runner package");
		assertContains(manifest, "--outputDir=\"${WORKSPACE}/", "seed output metadata directory");
		assertContains(manifest, "--timeLimitSeconds=\"10800\"", "formal solve time limit");
		assertContains(manifest, "${WORKSPACE}/", "manifest should remain portable across machines");
		assertTrue(!manifest.contains("--timeScale="), "time scale must be materialized in instance files");
		assertTrue(!manifest.contains("--dueWindowHalfWidth="), "window must be materialized in instance files");
		assertTrue(!manifest.contains("--setupCostCoefficient="), "setup cost must be materialized");
		assertTrue(!manifest.contains("--outsourcingUnitRate="), "outsourcing rate must be materialized");
		assertTrue(!manifest.contains("--discountStrength="), "discount must be materialized");
		assertTrue(!manifest.contains("--outsourcingData="), "complete outsourcing files need one input path");
		assertSeedRowsDoNotSpecifyOutsourcingModel(suite.resolve("manifest.tsv"));
		assertPricingRowsDoNotSpecifyOutsourcingModel(suite.resolve("manifests/pricing-comparison.tsv"));
		assertTrue(Files.exists(suite.resolve("manifests/pricing-comparison.tsv")),
				"missing pricing block manifest");
		assertPricingWindowMatrix(suite.resolve("manifests/pricing-comparison.tsv"));
		assertTrue(Files.exists(suite.resolve("manifests/outsourcing-performance.tsv")),
				"missing outsourcing performance manifest");
		assertTrue(Files.exists(suite.resolve("manifests/outsourcing-discount.tsv")),
				"missing outsourcing discount manifest");
		String discountManifest = Files.readString(suite.resolve("manifests/outsourcing-discount.tsv"),
				StandardCharsets.UTF_8);
		assertContains(discountManifest, "--action=\"seed\"", "discount seed task");
		assertContains(discountManifest, "outsourcing-discount", "discount solve task");
		assertTrue(!Files.exists(suite.resolve("manifests/outsourcing-formulation.tsv")),
				"obsolete outsourcing formulation manifest");
		assertTrue(!Files.exists(suite.resolve("manifests/outsourcing-price.tsv")),
				"obsolete outsourcing price manifest");
		assertContains(manifest, "outsourcing-performance", "merged outsourcing performance block");
		assertContains(manifest, "/outsourcing-data/default-discount/", "complete outsourcing instance path");
		String experimentProperties = Files.readString(suite.resolve("experiment.properties"),
				StandardCharsets.UTF_8);
		assertContains(experimentProperties, "outsourcingQuotation=p*max(wE,wT)", "quotation metadata");
		assertContains(experimentProperties, "outsourcingBreakpointReferenceTotal=3050", "breakpoint metadata");
		assertTrue(Files.exists(suite.resolve("instances/post-generation-setup-audit.tsv")),
				"missing independent post-generation setup audit");
		assertTrue(Files.exists(suite.resolve("instances/post-generation-time-scale-audit.tsv")),
				"missing independent post-generation time-scale audit");
		assertTrue(Files.exists(suite.resolve("instances/post-generation-outsourcing-audit.tsv")),
				"missing independent post-generation outsourcing audit");
		assertFamilyMetricSetup(suite);

		GeneratedIndexRow scaledRow = findGeneratedInstance(suite, "n020-set01", "random", "medium", "narrow", 2);
		GeneratedIndexRow baseRow = findGeneratedInstance(suite, "n020-set01", "random", "base", "narrow", 2);
		Path scaledInstance = scaledRow.path;
		assertTrue(Files.exists(scaledInstance), "missing materialized time-scale instance");
		var data = FormalExperimentDataFactory.load(scaledInstance);
		FormalExperimentDataFactory.validateOutsourcingModel(data, "", scaledInstance);
		var baseData = FormalExperimentDataFactory.load(baseRow.path);
		FormalExperimentDataFactory.validateOutsourcingModel(baseData, "", baseRow.path);
		assertTrue(data.n == 20 && data.m == 2, "derived instance dimensions");
		int firstJobMultiplier = readJobMultiplier(suite, "n020-set01", "medium", 1, 12);
		int firstCenterMultiplier = readJobMultiplier(suite, "n020-set01", "medium", 1, 13);
		assertTrue(scaledRow.nominalScale == 10, "medium nominal scale");
		assertTrue(data.p[1] == firstJobMultiplier * baseData.p[1], "job-specific time scale");
		double scaledCenter = data.d_l[1] - readWindowHalfWidth(suite, "n020-set01", "medium", "narrow", 1);
		double baseCenter = baseData.d_l[1] - readWindowHalfWidth(suite, "n020-set01", "base", "narrow", 1);
		assertTrue(scaledCenter == firstCenterMultiplier * baseCenter, "independent due-center scale");
		assertTrue(data.getSetupCost(1, 2) == 20.0 * data.s[1][2], "persisted setup cost");
		assertTrue(data.outsourcingCost[1] >= Common.Utility.big_M, "no-outsourcing data remains disabled");
		Path completeOutsourcing = findOutsourcingInstance(suite, baseRow.path, 1.0, 0.15);
		assertSchedulingPrefix(baseRow.path, completeOutsourcing);
		var outsourcingData = FormalExperimentDataFactory.load(completeOutsourcing);
		assertOutsourcingModelValidation(baseData, baseRow.path, outsourcingData, completeOutsourcing);
		assertTrue(outsourcingData.outsourcingCost[1]
				== baseData.p[1] * Math.max(baseData.w_e[1], baseData.w_t[1]), "persisted outsourcing baseline");
		assertClose(outsourcingData.evaluateOutsourcingCost(10.0), 10.0, "persisted tariff first segment");
		assertUnknownArgumentRejected();
		assertSeedOutsourcingModelRejected(baseRow.path);

		ArrayList<Integer> internalJobs = new ArrayList<Integer>();
		for (int job = 1; job < outsourcingData.n; job++) {
			internalJobs.add(Integer.valueOf(job));
		}
		FixedInitialColumnSeed seed = new FixedInitialColumnSeed(
				List.of(internalJobs), List.of(internalJobs), List.of(outsourcingData.n));
		Path snapshot = root.resolve("seed.snapshot");
		FixedInitialSeedSnapshotIO.write(snapshot, "fixture", seed);
		FixedInitialColumnSeed restored = FixedInitialSeedSnapshotIO.read(snapshot);
		assertTrue(seed.getInitialSequences().equals(restored.getInitialSequences()), "seed round trip");
		assertTrue(seed.getIncumbentOutsourcedJobs().equals(restored.getIncumbentOutsourcedJobs()),
				"outsourced jobs round trip");
		assertFixedOutsourcingRestoration(outsourcingData, restored);
		assertLegacySnapshotCompatibility(root);
		System.out.println("FormalExperimentInfrastructureTest passed");
	}

	private static void assertPricingWindowMatrix(Path path) throws Exception {
		Map<String, Map<String, Integer>> counts = new HashMap<String, Map<String, Integer>>();
		for (String level : List.of("base", "medium", "high")) {
			counts.put(level, new HashMap<String, Integer>());
		}
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (!fields[0].startsWith("pricing-")) {
				continue;
			}
			String level;
			if (fields[0].contains("-base-n1-")) {
				level = "base";
			} else if (fields[0].contains("-medium-n10-")) {
				level = "medium";
			} else if (fields[0].contains("-high-n20-")) {
				level = "high";
			} else {
				throw new AssertionError("Unknown pricing scale in " + fields[0]);
			}
			String window;
			if (fields[0].contains("-wzero-")) {
				window = "zero";
			} else if (fields[0].contains("-wnarrow-")) {
				window = "narrow";
			} else if (fields[0].contains("-wwide-")) {
				window = "wide";
			} else {
				throw new AssertionError("Unknown pricing window in " + fields[0]);
			}
			counts.get(level).merge(window, Integer.valueOf(1), Integer::sum);
		}
		assertWindowCounts(counts.get("base"));
		assertWindowCounts(counts.get("medium"));
		assertWindowCounts(counts.get("high"));
	}

	private static void assertPricingRowsDoNotSpecifyOutsourcingModel(Path path) throws Exception {
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			assertTrue(!line.contains("--outsourcingModel="),
					"scheduling-only manifest must infer no-outsourcing from the instance");
		}
	}

	private static void assertSeedRowsDoNotSpecifyOutsourcingModel(Path path) throws Exception {
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			if (line.contains("--action=\"seed\"")) {
				assertTrue(!line.contains("--outsourcingModel="),
						"seed generation must not depend on an outsourcing formulation");
			}
		}
	}

	private static void assertWindowCounts(Map<String, Integer> actual) {
		assertTrue(actual.size() == 3, "pricing window level count");
		for (String window : List.of("zero", "narrow", "wide")) {
			assertTrue(actual.getOrDefault(window, Integer.valueOf(0)).intValue() == 36,
					"pricing window row count for " + window);
		}
	}

	private static int readJobMultiplier(Path suite, String taskSetId, String scaleLevel, int job,
			int vectorField)
			throws Exception {
		List<String> lines = Files.readAllLines(suite.resolve("instances/scale-selection.tsv"),
				StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (!fields[0].equals(taskSetId) || !fields[1].equals(scaleLevel)) {
				continue;
			}
			String[] multipliers = fields[vectorField].split(",");
			return Integer.parseInt(multipliers[job - 1]);
		}
		throw new AssertionError("Missing scale vector " + taskSetId + "/" + scaleLevel);
	}

	private static int readWindowHalfWidth(Path suite, String taskSetId, String scaleLevel,
			String windowLevel, int job) throws Exception {
		List<String> lines = Files.readAllLines(suite.resolve("instances/window-selection.tsv"),
				StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (fields[0].equals(taskSetId) && fields[1].equals(scaleLevel)
					&& fields[2].equals(windowLevel)) {
				return Integer.parseInt(fields[6].split(",")[job - 1]);
			}
		}
		throw new AssertionError("Missing window vector");
	}

	private static Path findOutsourcingInstance(Path suite, Path sourceInstance, double rate,
			double discount) throws Exception {
		List<String> lines = Files.readAllLines(suite.resolve("instances/outsourcing-instances.tsv"),
				StandardCharsets.UTF_8);
		Path normalizedSource = sourceInstance.toAbsolutePath().normalize();
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			Path indexedSource = suite.resolve("instances").resolve(fields[11]).normalize()
					.toAbsolutePath().normalize();
			if (indexedSource.equals(normalizedSource) && Double.parseDouble(fields[5]) == rate
					&& Double.parseDouble(fields[6]) == discount) {
				return suite.resolve("instances").resolve(fields[12]).normalize();
			}
		}
		throw new AssertionError("Missing complete outsourcing instance");
	}

	private static void assertSchedulingPrefix(Path scheduling, Path outsourcing) throws Exception {
		List<String> schedulingLines = Files.readAllLines(scheduling, StandardCharsets.UTF_8);
		List<String> outsourcingLines = Files.readAllLines(outsourcing, StandardCharsets.UTF_8);
		assertTrue(outsourcingLines.size() > schedulingLines.size(), "outsourcing file appends economic blocks");
		assertTrue(outsourcingLines.subList(0, schedulingLines.size()).equals(schedulingLines),
				"complete outsourcing file preserves scheduling prefix");
	}

	private static void assertFixedOutsourcingRestoration(Basic.Data data, FixedInitialColumnSeed seed) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.fixedInitialColumnSeed = seed;
		Pool pool = new Pool(data);
		InitialColumnBundle bundle = new InitialColumnBuilder(data, config, pool,
				new HeuristicSeedProvider(data, config)).build();
		assertTrue(bundle.getIncumbentOutsourcedJobs().equals(List.of(data.n)), "fixed outsourced jobs");
		assertClose(bundle.getIncumbentOutsourcingBaseline(), data.outsourcingCost[data.n], "fixed outsourcing baseline");
		assertClose(bundle.getIncumbentOutsourcingCost(), data.evaluateOutsourcingCost(data.outsourcingCost[data.n]),
				"fixed outsourcing tariff");
	}

	private static GeneratedIndexRow findGeneratedInstance(Path suite, String taskSetId, String setupType,
			String scaleLevel, String windowLevel, int machines) throws Exception {
		List<String> lines = Files.readAllLines(suite.resolve("instances.tsv"), StandardCharsets.UTF_8);
		for (int index = 1; index < lines.size(); index++) {
			String[] fields = lines.get(index).split("\\t");
			if (fields[0].equals(taskSetId) && Integer.parseInt(fields[3]) == machines
					&& fields[4].equals(setupType) && fields[5].equals(scaleLevel)
					&& fields[7].equals(windowLevel)) {
				String rawPath = fields[11].replace("${WORKSPACE}", Path.of("").toAbsolutePath().toString());
				return new GeneratedIndexRow(Path.of(rawPath), Integer.parseInt(fields[6]));
			}
		}
		throw new AssertionError("Missing generated instance " + taskSetId + "/" + setupType + "/"
				+ scaleLevel + "/" + windowLevel + "/m" + machines);
	}

	private static void assertLegacySnapshotCompatibility(Path root) throws Exception {
		List<List<Integer>> sequences = List.of(List.of(1, 2), List.of(3));
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("TWET_FIXED_INITIAL_SEED_V1");
		lines.add("reference\tlegacy");
		lines.add("fingerprint\t" + legacyFingerprint(sequences, sequences));
		lines.add("initial\t2");
		lines.add("1,2");
		lines.add("3");
		lines.add("incumbent\t2");
		lines.add("1,2");
		lines.add("3");
		Path path = root.resolve("legacy-seed.snapshot");
		Files.write(path, lines, StandardCharsets.UTF_8);
		FixedInitialColumnSeed restored = FixedInitialSeedSnapshotIO.read(path);
		assertTrue(restored.getIncumbentOutsourcedJobs().isEmpty(), "legacy outsourced jobs");
	}

	private static void assertFamilyMetricSetup(Path suite) throws Exception {
		List<String> lines = Files.readAllLines(suite.resolve("instances/setup-audit.tsv"),
				StandardCharsets.UTF_8);
		boolean checked = false;
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (!"family".equals(fields[1]) || !"base".equals(fields[2])) {
				continue;
			}
			assertTrue(Integer.parseInt(fields[10]) == 0, "family triangle violations");
			assertTrue(Integer.parseInt(fields[11]) == 0, "family Floyd audit changes");
			assertTrue(Integer.parseInt(fields[16]) == 0, "family generation must not run Floyd");
			assertClose(Double.parseDouble(fields[14]), Double.parseDouble(fields[15]),
					"family raw and closed means");
			assertClose(Double.parseDouble(fields[20]), 4.0 * Double.parseDouble(fields[4]),
					"family switch penalty");
			double ratio = Double.parseDouble(fields[21]);
			assertTrue(ratio >= 4.0 && ratio <= 5.3, "family separation ratio");
			assertTrue(Double.parseDouble(fields[20]) > 0.0, "family switch penalty");
			checked = true;
		}
		assertTrue(checked, "missing family setup audit row");
	}

	private static String legacyFingerprint(List<List<Integer>> initial, List<List<Integer>> incumbent)
			throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		updateLegacyFingerprint(digest, "initial", initial);
		updateLegacyFingerprint(digest, "incumbent", incumbent);
		StringBuilder value = new StringBuilder(64);
		for (byte item : digest.digest()) {
			value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
		}
		return value.toString();
	}

	private static void updateLegacyFingerprint(MessageDigest digest, String name, List<List<Integer>> sequences) {
		digest.update(name.getBytes(StandardCharsets.UTF_8));
		for (List<Integer> sequence : sequences) {
			digest.update((byte) '[');
			for (int job : sequence) {
				digest.update(Integer.toString(job).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) ',');
			}
			digest.update((byte) ']');
		}
	}

	private static void assertUnknownArgumentRejected() throws Exception {
		try {
			FormalExperimentRunner.main(new String[] { "--unknown=5" });
			throw new AssertionError("unknown argument should be rejected");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("Unknown argument"), "unknown argument error message");
		}
	}

	private static void assertSeedOutsourcingModelRejected(Path instance) throws Exception {
		try {
			FormalExperimentRunner.main(new String[] { "--action=seed", "--instance=" + instance,
					"--outsourcingModel=masterVariables" });
			throw new AssertionError("seed formulation should be rejected");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("seed action does not use"),
					"seed formulation error message");
		}
	}

	private static void assertOutsourcingModelValidation(Basic.Data schedulingData, Path schedulingInstance,
			Basic.Data outsourcingData, Path outsourcingInstance) {
		FormalExperimentDataFactory.validateOutsourcingModel(schedulingData, "", schedulingInstance);
		FormalExperimentDataFactory.validateOutsourcingModel(outsourcingData, "columns", outsourcingInstance);
		FormalExperimentDataFactory.validateOutsourcingModel(outsourcingData, "masterVariables", outsourcingInstance);
		assertIllegalArgument(() -> FormalExperimentDataFactory.validateOutsourcingModel(
				schedulingData, "columns", schedulingInstance), "Scheduling-only instance");
		assertIllegalArgument(() -> FormalExperimentDataFactory.validateOutsourcingModel(
				outsourcingData, "", outsourcingInstance), "Outsourcing instance");
	}

	private static void assertIllegalArgument(Runnable action, String expectedMessage) {
		try {
			action.run();
			throw new AssertionError("Expected IllegalArgumentException containing " + expectedMessage);
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains(expectedMessage), "data/model mismatch message");
		}
	}

	private static void reset(Path root) throws Exception {
		if (Files.exists(root)) {
			try (var paths = Files.walk(root)) {
				paths.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);
						if (dos != null) {
							dos.setReadOnly(false);
						}
						Files.delete(path);
					} catch (Exception ex) {
						throw new RuntimeException(ex);
					}
				});
			}
		}
		Files.createDirectories(root);
	}

	private static void assertContains(String text, String expected, String message) {
		if (!text.contains(expected)) {
			throw new AssertionError(message + ": missing " + expected);
		}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void assertClose(double actual, double expected, String message) {
		if (Math.abs(actual - expected) > 1e-8) {
			throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
		}
	}

	private record GeneratedIndexRow(Path path, int nominalScale) {
	}
}
