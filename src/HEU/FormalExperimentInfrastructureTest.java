package HEU;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import Common.FormalExperimentSuiteGenerator;
import TWETBPC.GC.FixedInitialColumnSeed;
import TWETBPC.IO.FixedInitialSeedSnapshotIO;

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
		assertTrue(Files.exists(suite.resolve("manifests/pricing-comparison.tsv")),
				"missing pricing block manifest");
		assertTrue(Files.exists(suite.resolve("manifests/outsourcing-formulation.tsv")),
				"missing outsourcing formulation manifest");
		assertTrue(Files.exists(suite.resolve("manifests/outsourcing-price.tsv")),
				"missing outsourcing price manifest");
		assertTrue(Files.exists(suite.resolve("manifests/outsourcing-discount.tsv")),
				"missing outsourcing discount manifest");

		FormalExperimentDataFactory.Scenario scenario = new FormalExperimentDataFactory.Scenario();
		scenario.timeScale = 5.0;
		scenario.dueWindowHalfWidth = 20.0;
		scenario.setupCostCoefficient = 0.5;
		scenario.outsourcingEnabled = true;
		scenario.outsourcingUnitRate = 1.25;
		scenario.discountStrength = 0.15;
		var data = FormalExperimentDataFactory.load(dataRoot.resolve("3-2/wet003_001_2m.dat"), scenario);
		assertTrue(data.n == 3 && data.m == 2, "derived instance dimensions");
		assertTrue(data.p[1] == 50.0, "time scale");
		assertTrue(data.d_l[1] - data.d_e[1] == 40.0, "due-window width");
		assertTrue(data.outsourcingCost[1] == data.p[1], "outsourcing baseline");

		FixedInitialColumnSeed seed = new FixedInitialColumnSeed(
				List.of(List.of(1, 2), List.of(3)), List.of(List.of(1, 2), List.of(3)));
		Path snapshot = root.resolve("seed.snapshot");
		FixedInitialSeedSnapshotIO.write(snapshot, "fixture", seed);
		FixedInitialColumnSeed restored = FixedInitialSeedSnapshotIO.read(snapshot);
		assertTrue(seed.getInitialSequences().equals(restored.getInitialSequences()), "seed round trip");
		System.out.println("FormalExperimentInfrastructureTest passed");
	}

	private static void reset(Path root) throws Exception {
		if (Files.exists(root)) {
			try (var paths = Files.walk(root)) {
				paths.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
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
}
