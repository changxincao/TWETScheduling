package Common.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 沿用正式数据生成链，仅重设大规模family数量并核对与旧数据的配对关系。 */
public final class FormalFamilyCountVariantGenerator {
	private static final int[] SIZES = { 50, 60, 80, 100 };
	private static final Map<Integer, Integer> FAMILY_COUNTS = Map.of(50, 3, 60, 3, 80, 4, 100, 4);

	private FormalFamilyCountVariantGenerator() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 3 && (args.length != 4 || !"--verifyOnly".equals(args[3]))) {
			throw new IllegalArgumentException(
					"Usage: FormalFamilyCountVariantGenerator <source-data-root> <old-instances-root> <new-suite-root> [--verifyOnly]");
		}
		Path source = Path.of(args[0]).normalize();
		Path oldRoot = Path.of(args[1]).toAbsolutePath().normalize();
		Path suite = Path.of(args[2]).toAbsolutePath().normalize();
		Path output = suite.resolve("instances");
		if (!Files.isRegularFile(oldRoot.resolve("instances.tsv"))) {
			throw new IOException("Baseline index missing: " + oldRoot);
		}
		if (args.length == 3 && Files.exists(suite)) {
			throw new IOException("Variant output already exists: " + suite);
		}
		if (args.length == 4 && !Files.isRegularFile(output.resolve("instances.tsv"))) {
			throw new IOException("Variant index missing: " + output);
		}
		if (args.length == 3) {
			new FormalExperimentDataGenerator(source, FormalExperimentDesign.CASES_PER_SIZE)
					.generateFamilyVariant(output, SIZES, FAMILY_COUNTS);
		}
		FormalSetupAuditRunner.AuditSummary setup = FormalSetupAuditRunner.audit(suite);
		FormalTimeScaleAuditRunner.AuditSummary time = FormalTimeScaleAuditRunner.audit(suite);
		verifyMatchedData(oldRoot, output);
		verifyRandomSetupStable(source);
		if (setup.fileCount() != 540 || time.fileCount() != 540) {
			throw new IOException("Expected 540 family instances, got setup=" + setup.fileCount()
					+ " time=" + time.fileCount());
		}
		System.out.println("Family-count variant verified: 540 instances, F=3/3/4/4, output=" + suite);
	}

	private static void verifyRandomSetupStable(Path source) throws IOException {
		FormalSetupGenerator generator = new FormalSetupGenerator(new FormalSetupValidator());
		for (FormalTaskSet taskSet : new FormalTaskSetGenerator(source,
				FormalExperimentDesign.CASES_PER_SIZE).generate(SIZES)) {
			int oldCount = FormalExperimentDesign.familyCount(taskSet.size());
			int newCount = FAMILY_COUNTS.get(taskSet.size());
			int[][] oldRandom = generator.generatePair(taskSet, oldCount).get(0).setup();
			int[][] newRandom = generator.generatePair(taskSet, newCount).get(0).setup();
			if (!Arrays.deepEquals(oldRandom, newRandom)) {
				throw new IOException("Random setup changed for " + taskSet.id());
			}
		}
	}

	private static void verifyMatchedData(Path oldRoot, Path newRoot) throws IOException {
		compareMetadata(oldRoot, newRoot, "task-selection.tsv");
		compareMetadata(oldRoot, newRoot, "scale-selection.tsv");
		compareMetadata(oldRoot, newRoot, "window-selection.tsv");
		Map<String, Path> oldInstances = readIndex(oldRoot);
		Map<String, Path> newInstances = readIndex(newRoot);
		if (newInstances.size() != 540) {
			throw new IOException("Expected 540 unique variant paths, got " + newInstances.size());
		}
		HashMap<String, Boolean> changedByTaskSet = new HashMap<>();
		for (Map.Entry<String, Path> entry : newInstances.entrySet()) {
			Path baseline = oldInstances.get(entry.getKey());
			if (baseline == null) {
				throw new IOException("Missing matched baseline: " + entry.getKey());
			}
			List<String> before = Files.readAllLines(baseline, StandardCharsets.UTF_8);
			List<String> after = Files.readAllLines(entry.getValue(), StandardCharsets.UTF_8);
			int size = Integer.parseInt(after.get(0).split("\\s+")[0]);
			if (before.size() != after.size() || !before.subList(0, size + 2).equals(after.subList(0, size + 2))) {
				throw new IOException("Task, machine, or due-window data changed: " + entry.getKey());
			}
			String taskSet = entry.getKey().split("/")[1];
			if (!before.equals(after)) {
				changedByTaskSet.put(taskSet, true);
			}
		}
		if (changedByTaskSet.size() != 20) {
			throw new IOException("Family setup did not change in every task set: " + changedByTaskSet.size());
		}
	}

	private static void compareMetadata(Path oldRoot, Path newRoot, String name) throws IOException {
		List<String> oldLines = Files.readAllLines(oldRoot.resolve(name), StandardCharsets.UTF_8);
		List<String> newLines = Files.readAllLines(newRoot.resolve(name), StandardCharsets.UTF_8);
		Map<String, String> oldByKey = new HashMap<>();
		for (String line : oldLines.subList(1, oldLines.size())) {
			oldByKey.put(metadataKey(name, line), line);
		}
		for (String line : newLines.subList(1, newLines.size())) {
			if (!line.equals(oldByKey.get(metadataKey(name, line)))) {
				throw new IOException("Unmatched " + name + " row: " + line);
			}
		}
	}

	private static String metadataKey(String name, String line) {
		String[] fields = line.split("\\t", -1);
		return fields[0] + (name.equals("task-selection.tsv") ? "" : "/" + fields[1])
				+ (name.equals("window-selection.tsv") ? "/" + fields[2] : "");
	}

	private static Map<String, Path> readIndex(Path root) throws IOException {
		Map<String, Path> paths = new HashMap<>();
		List<String> lines = Files.readAllLines(root.resolve("instances.tsv"), StandardCharsets.UTF_8);
		for (String line : lines.subList(1, lines.size())) {
			String[] fields = line.split("\\t", -1);
			Path relative = Path.of(fields[11]);
			paths.put(relative.toString().replace('\\', '/'), root.resolve(relative));
		}
		return paths;
	}
}
