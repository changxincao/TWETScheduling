package Common.formal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

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
		assertTrue(first.taskSets().size() == 6, "six task sizes");
		assertTrue(first.instances().size() == 108, "six sizes x two setups x three scales x three machines");
		assertTrue(audit.fileCount() == 108 && audit.groupCount() == 36, "independent setup audit coverage");
		assertTrue(audit.maxAbsoluteMeanError() <= 0.5, "setup mean tolerance");

		HashMap<String, String> selected = selectedJobs(suite.resolve("instances/task-selection.tsv"));
		assertPrefix(selected.get("n020-set01"), selected.get("n040-set01"), "20/40 nesting");
		assertPrefix(selected.get("n060-set01"), selected.get("n080-set01"), "60/80 nesting");
		assertPrefix(selected.get("n080-set01"), selected.get("n100-set01"), "80/100 nesting");
		assertScaleRanges(suite.resolve("instances/scale-selection.tsv"));

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

	private static void assertScaleRanges(Path path) throws Exception {
		HashMap<String, Integer> mediumByTaskSet = new HashMap<String, Integer>();
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t");
			int multiplier = Integer.parseInt(fields[2]);
			if (fields[1].equals("medium")) {
				assertTrue(multiplier >= 5 && multiplier <= 15, "medium multiplier range");
				mediumByTaskSet.put(fields[0], Integer.valueOf(multiplier));
			} else if (fields[1].equals("high")) {
				assertTrue(multiplier == mediumByTaskSet.get(fields[0]).intValue() + 10,
						"paired high multiplier");
			} else {
				assertTrue(multiplier == 1, "base multiplier");
			}
		}
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
}
