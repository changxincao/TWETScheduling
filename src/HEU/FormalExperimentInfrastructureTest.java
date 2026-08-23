package HEU;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import Common.FormalExperimentSuiteGenerator;
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
		Path sourceDir = dataRoot.resolve("3-1");
		Files.createDirectories(sourceDir);
		Files.write(sourceDir.resolve("wet003_001.dat"), List.of(
				"3", "10 20 1 2", "15 35 2 1", "8 48 1 1"), StandardCharsets.UTF_8);

		Path suite = root.resolve("suite");
		FormalExperimentSuiteGenerator.main(new String[] {
				"--dataRoot=" + dataRoot, "--outputRoot=" + suite, "--sizes=3", "--casesPerSize=1",
				"--maxNodes=10" });
		String manifest = Files.readString(suite.resolve("manifest.tsv"), StandardCharsets.UTF_8);
		assertContains(manifest, "dependsOn", "dependency column");
		assertContains(manifest, "NG_DSSR", "ng-DSSR row");
		assertContains(manifest, "TIME_INDEXED", "time-indexed row");
		assertContains(manifest, "TIME_INDEXED_SRI", "rank-1 row");
		assertContains(manifest, "\tseed-", "solver dependency");
		assertContains(manifest, "--action=\"seed\"", "seed task");
		assertContains(manifest, "--outputDir=\"${WORKSPACE}/", "seed output metadata directory");
		assertContains(manifest, "--timeLimitSeconds=\"10800\"", "formal solve time limit");
		assertContains(manifest, "${WORKSPACE}/", "manifest should remain portable across machines");
		assertTrue(!manifest.contains("--timeScale="), "time scale must be materialized in instance files");
		assertTrue(Files.exists(suite.resolve("manifests/pricing-comparison.tsv")),
				"missing pricing block manifest");
		assertTrue(Files.exists(suite.resolve("manifests/outsourcing-formulation.tsv")),
				"missing outsourcing formulation manifest");
		assertTrue(Files.exists(suite.resolve("manifests/outsourcing-price.tsv")),
				"missing outsourcing price manifest");
		assertTrue(Files.exists(suite.resolve("manifests/outsourcing-discount.tsv")),
				"missing outsourcing discount manifest");

		FormalExperimentDataFactory.Scenario scenario = new FormalExperimentDataFactory.Scenario();
		scenario.dueWindowHalfWidth = 20.0;
		scenario.setupCostCoefficient = 0.5;
		scenario.outsourcingEnabled = true;
		scenario.outsourcingUnitRate = 1.25;
		scenario.discountStrength = 0.15;
		Path scaledInstance = suite.resolve("instances/3-2/wet003_001_2m_timeX5.dat");
		assertTrue(Files.exists(scaledInstance), "missing materialized time-scale instance");
		var data = FormalExperimentDataFactory.load(scaledInstance, scenario);
		FormalExperimentDataFactory.Scenario baseScenario = new FormalExperimentDataFactory.Scenario();
		var baseData = FormalExperimentDataFactory.load(dataRoot.resolve("3-2/wet003_001_2m.dat"), baseScenario);
		assertTrue(data.n == 3 && data.m == 2, "derived instance dimensions");
		assertTrue(data.p[1] == 50.0, "time scale");
		assertTrue(data.s[0][1] == 5.0 * baseData.s[0][1], "materialized setup scale");
		assertTrue(data.d_l[1] - data.d_e[1] == 40.0, "due-window width");
		assertTrue(data.outsourcingCost[1] == data.p[1], "outsourcing baseline");
		assertLegacyTimeScaleRejected();

		FixedInitialColumnSeed seed = new FixedInitialColumnSeed(
				List.of(List.of(1, 2)), List.of(List.of(1, 2)), List.of(3));
		Path snapshot = root.resolve("seed.snapshot");
		FixedInitialSeedSnapshotIO.write(snapshot, "fixture", seed);
		FixedInitialColumnSeed restored = FixedInitialSeedSnapshotIO.read(snapshot);
		assertTrue(seed.getInitialSequences().equals(restored.getInitialSequences()), "seed round trip");
		assertTrue(seed.getIncumbentOutsourcedJobs().equals(restored.getIncumbentOutsourcedJobs()),
				"outsourced jobs round trip");
		assertFixedOutsourcingRestoration(data, restored);
		assertLegacySnapshotCompatibility(root);
		assertSeedWinnerSelection();
		System.out.println("FormalExperimentInfrastructureTest passed");
	}

	private static void assertFixedOutsourcingRestoration(Basic.Data data, FixedInitialColumnSeed seed) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.fixedInitialColumnSeed = seed;
		Pool pool = new Pool(data);
		InitialColumnBundle bundle = new InitialColumnBuilder(data, config, pool,
				new HeuristicSeedProvider(data, config)).build();
		assertTrue(bundle.getIncumbentOutsourcedJobs().equals(List.of(3)), "fixed outsourced jobs");
		assertClose(bundle.getIncumbentOutsourcingBaseline(), data.outsourcingCost[3], "fixed outsourcing baseline");
		assertClose(bundle.getIncumbentOutsourcingCost(), data.evaluateOutsourcingCost(data.outsourcingCost[3]),
				"fixed outsourcing tariff");
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

	private static void assertSeedWinnerSelection() {
		FixedInitialColumnSeed seed = new FixedInitialColumnSeed(List.of(List.of(1)), List.of(List.of(1)));
		FormalExperimentRunner.SeedRun selected = FormalExperimentRunner.selectBestSeedRun(List.of(
				new FormalExperimentRunner.SeedRun(0, 1L, 2L, seed, 10.0, 1L, "a"),
				new FormalExperimentRunner.SeedRun(1, 3L, 4L, seed, 8.0, 1L, "b"),
				new FormalExperimentRunner.SeedRun(2, 5L, 6L, seed, 8.0, 1L, "c")));
		assertTrue(selected.repetition == 1, "seed winner cost and tie break");
	}

	private static void assertLegacyTimeScaleRejected() throws Exception {
		try {
			FormalExperimentRunner.main(new String[] { "--timeScale=5" });
			throw new AssertionError("legacy timeScale argument should be rejected");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("materialized instance"), "legacy timeScale error message");
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
}
