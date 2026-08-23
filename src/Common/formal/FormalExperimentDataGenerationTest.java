package Common.formal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 不调用求解器，验证六个规模的数据生成、嵌套任务、倍率和setup独立审计。 */
public final class FormalExperimentDataGenerationTest {
	private FormalExperimentDataGenerationTest() {
	}

	public static void main(String[] args) throws Exception {
		Path root = Path.of("tmp", "formal-data-generation-test");
		reset(root);
		Path dataRoot = root.resolve("data");
		writeSource(dataRoot, 40);
		writeSource(dataRoot, 50);
		writeSource(dataRoot, 100);
		Path suite = root.resolve("suite");
		FormalExperimentDataGenerator generator = new FormalExperimentDataGenerator(dataRoot, 1);
		FormalExperimentDataGenerator.GenerationResult first =
				generator.generate(suite.resolve("instances"), FormalExperimentDesign.TASK_SIZES);
		FormalSetupAuditRunner.AuditSummary audit = FormalSetupAuditRunner.audit(suite);
		FormalTimeScaleAuditRunner.AuditSummary scaleAudit = FormalTimeScaleAuditRunner.audit(suite);
		assertTrue(first.taskSets().size() == 6, "six task sizes");
		assertTrue(first.instances().size() == 108, "six sizes x two setups x three scales x three machines");
		assertTrue(audit.fileCount() == 108 && audit.groupCount() == 36, "independent setup audit coverage");
		assertTrue(scaleAudit.fileCount() == 72, "independent time-scale audit coverage");
		assertTrue(audit.maxAbsoluteMeanError() <= 0.5, "setup mean tolerance");

		HashMap<String, String> selected = selectedJobs(suite.resolve("instances/task-selection.tsv"));
		assertPrefix(selected.get("n020-set01"), selected.get("n040-set01"), "20/40 nesting");
		assertPrefix(selected.get("n060-set01"), selected.get("n080-set01"), "60/80 nesting");
		assertPrefix(selected.get("n080-set01"), selected.get("n100-set01"), "80/100 nesting");
		Map<String, ScaleVectors> scaleVectors = assertScaleRanges(
				suite.resolve("instances/scale-selection.tsv"));
		assertHeterogeneousMaterialization(first.instances(), scaleVectors.get("n060-set01"));

		Path representative = first.instances().stream()
				.filter(item -> item.taskSetId().equals("n060-set01") && item.setupType().equals("family")
						&& item.scaleLevel().equals("medium") && item.machines() == 2)
				.findFirst().orElseThrow().path();
		String hashBefore = sha256(representative);
		generator.generate(suite.resolve("instances"), FormalExperimentDesign.TASK_SIZES);
		String hashAfter = sha256(representative);
		assertTrue(hashBefore.equals(hashAfter), "deterministic regeneration");
		System.out.println("FormalExperimentDataGenerationTest passed");
	}

	private static void writeSource(Path dataRoot, int size) throws Exception {
		Path directory = dataRoot.resolve(size + "-1");
		Files.createDirectories(directory);
		ArrayList<String> lines = new ArrayList<String>();
		lines.add(Integer.toString(size));
		for (int job = 1; job <= size; job++) {
			int processing = 1 + (7 * job) % 100;
			int dueDate = 20 * size + 13 * job;
			int early = 1 + job % 10;
			int tardy = 1 + (3 * job) % 10;
			lines.add(processing + " " + dueDate + " " + early + " " + tardy);
		}
		Files.write(directory.resolve(String.format("wet%03d_001.dat", size)), lines, StandardCharsets.UTF_8);
	}

	private static HashMap<String, String> selectedJobs(Path path) throws Exception {
		HashMap<String, String> result = new HashMap<String, String>();
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			result.put(fields[0], fields[11]);
		}
		return result;
	}

	private static void assertPrefix(String prefix, String whole, String message) {
		assertTrue(prefix != null && whole != null && (whole.equals(prefix) || whole.startsWith(prefix + ",")), message);
	}

	private static Map<String, ScaleVectors> assertScaleRanges(Path path) throws Exception {
		LinkedHashMap<String, int[]> mediumByTaskSet = new LinkedHashMap<String, int[]>();
		LinkedHashMap<String, ScaleVectors> result = new LinkedHashMap<String, ScaleVectors>();
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			assertTrue(fields.length == 10, "scale metadata schema");
			int nominal = Integer.parseInt(fields[2]);
			double arithmeticMean = Double.parseDouble(fields[4]);
			double weightedMean = Double.parseDouble(fields[5]);
			int minimum = Integer.parseInt(fields[6]);
			int maximum = Integer.parseInt(fields[7]);
			int[] multipliers = parseVector(fields[8]);
			assertTrue(fields[9].length() == 64, "scale fingerprint");
			if (fields[1].equals("medium")) {
				assertTrue(nominal == 10 && minimum >= 5 && maximum <= 15,
						"medium multiplier range");
				assertClose(arithmeticMean, 10.0, "medium arithmetic mean");
				assertTrue(Math.abs(weightedMean - 10.0) <= 0.10,
						"medium processing-weighted mean");
				assertTrue(!allEqual(multipliers), "medium scale must vary by job");
				mediumByTaskSet.put(fields[0], multipliers);
			} else if (fields[1].equals("high")) {
				assertTrue(nominal == 20 && minimum >= 15 && maximum <= 25,
						"high multiplier range");
				assertClose(arithmeticMean, 20.0, "high arithmetic mean");
				assertTrue(Math.abs(weightedMean - 20.0) <= 0.10,
						"high processing-weighted mean");
				int[] medium = mediumByTaskSet.get(fields[0]);
				assertTrue(medium != null && medium.length == multipliers.length,
						"paired medium vector");
				for (int job = 0; job < multipliers.length; job++) {
					assertTrue(multipliers[job] == medium[job] + 10,
							"paired high multiplier for job " + (job + 1));
				}
				result.put(fields[0], new ScaleVectors(medium, multipliers));
			} else {
				assertTrue(nominal == 1 && minimum == 1 && maximum == 1,
						"base multiplier");
				assertClose(arithmeticMean, 1.0, "base arithmetic mean");
				assertClose(weightedMean, 1.0, "base processing-weighted mean");
			}
		}
		return result;
	}

	private static void assertHeterogeneousMaterialization(
			List<FormalExperimentDataGenerator.GeneratedInstance> instances, ScaleVectors vectors)
			throws Exception {
		assertTrue(vectors != null, "missing representative scale vectors");
		Path basePath = findInstance(instances, "n060-set01", "family", "base", 2);
		Path mediumPath = findInstance(instances, "n060-set01", "family", "medium", 2);
		ParsedInstance base = readInstance(basePath, 60);
		ParsedInstance medium = readInstance(mediumPath, 60);
		long baseWorkload = 0L;
		long mediumWorkload = 0L;
		for (int job = 1; job <= 60; job++) {
			int multiplier = vectors.medium()[job - 1];
			assertTrue(medium.processing()[job] == base.processing()[job] * multiplier,
					"processing scale for job " + job);
			assertTrue(medium.centers()[job] == base.centers()[job] * multiplier,
					"due-center scale for job " + job);
			baseWorkload += base.processing()[job];
			mediumWorkload += medium.processing()[job];
		}
		int cap = Math.max(1, (int) Math.floor((double) mediumWorkload / 60));
		for (int from = 0; from <= 60; from++) {
			for (int to = 0; to <= 60; to++) {
				long numerator = (long) base.setup()[from][to] * mediumWorkload;
				int expected = Math.min(cap, Math.toIntExact(Math.floorDiv(
						numerator + baseWorkload - 1L, baseWorkload)));
				assertTrue(medium.setup()[from][to] == expected,
						"setup workload scaling for arc " + from + "->" + to);
			}
		}
	}

	private static Path findInstance(List<FormalExperimentDataGenerator.GeneratedInstance> instances,
			String taskSetId, String setupType, String scaleLevel, int machines) {
		return instances.stream()
				.filter(item -> item.taskSetId().equals(taskSetId) && item.setupType().equals(setupType)
						&& item.scaleLevel().equals(scaleLevel) && item.machines() == machines)
				.map(FormalExperimentDataGenerator.GeneratedInstance::path)
				.findFirst().orElseThrow();
	}

	private static ParsedInstance readInstance(Path path, int n) throws Exception {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		int[] processing = new int[n + 1];
		int[] centers = new int[n + 1];
		for (int job = 1; job <= n; job++) {
			String[] fields = lines.get(job).trim().split("\\s+");
			processing[job] = Integer.parseInt(fields[0]);
			centers[job] = Integer.parseInt(fields[1]);
		}
		assertTrue(lines.get(n + 1).equals("SETUP"), "setup marker");
		int[][] setup = new int[n + 1][n + 1];
		for (int from = 0; from <= n; from++) {
			String[] fields = lines.get(n + 2 + from).trim().split("\\s+");
			for (int to = 0; to <= n; to++) {
				setup[from][to] = Integer.parseInt(fields[to]);
			}
		}
		return new ParsedInstance(processing, centers, setup);
	}

	private static int[] parseVector(String value) {
		String[] fields = value.split(",");
		int[] result = new int[fields.length];
		for (int index = 0; index < fields.length; index++) {
			result[index] = Integer.parseInt(fields[index]);
		}
		return result;
	}

	private static boolean allEqual(int[] values) {
		for (int index = 1; index < values.length; index++) {
			if (values[index] != values[0]) {
				return false;
			}
		}
		return true;
	}

	private static String sha256(Path path) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] bytes = digest.digest(Files.readAllBytes(path));
		StringBuilder result = new StringBuilder(64);
		for (byte value : bytes) {
			result.append(String.format("%02x", value & 0xff));
		}
		return result.toString();
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

	private record ScaleVectors(int[] medium, int[] high) {
	}

	private record ParsedInstance(int[] processing, int[] centers, int[][] setup) {
	}
}
