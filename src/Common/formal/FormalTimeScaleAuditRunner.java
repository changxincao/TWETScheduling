package Common.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 独立读取落盘实例，核验时间倍率、逐任务窗口、setup 和 setup cost。 */
public final class FormalTimeScaleAuditRunner {
	private FormalTimeScaleAuditRunner() {
	}

	public static void main(String[] args) throws Exception {
		Path suiteRoot = args.length == 0 ? Path.of("experiment-suite", "formal") : Path.of(args[0]);
		AuditSummary summary = audit(suiteRoot);
		System.out.printf(Locale.ROOT, "Formal data audit passed: files=%d output=%s%n",
				summary.fileCount(), suiteRoot.resolve("instances/post-generation-time-scale-audit.tsv")
						.toAbsolutePath());
	}

	public static AuditSummary audit(Path suiteRoot) throws Exception {
		Path generatedRoot = suiteRoot.resolve("instances");
		Map<String, TaskTruth> taskTruths = readTaskTruths(generatedRoot.resolve("task-selection.tsv"));
		Map<String, ScaleMetadata> scales = readScales(generatedRoot.resolve("scale-selection.tsv"), taskTruths);
		Map<String, WindowMetadata> windows = readWindows(
				generatedRoot.resolve("window-selection.tsv"), taskTruths, scales);
		List<InstanceRow> rows = readRows(generatedRoot.resolve("instances.tsv"), generatedRoot);
		Map<String, InstanceRow> baseRows = new HashMap<String, InstanceRow>();
		for (InstanceRow row : rows) {
			if (row.scaleLevel.equals("base") && row.windowLevel.equals("zero")) {
				baseRows.put(row.taskSetId + "/" + row.setupType + "/" + row.machines, row);
			}
		}
		ArrayList<String> output = new ArrayList<String>();
		output.add("taskSetId\tsetupType\tscaleLevel\twindowLevel\tmachines\tinstance\t"
				+ "processingMismatches\twindowMismatches\tweightMismatches\tsetupMismatches\t"
				+ "setupCostMismatches\tworkloadScale");
		for (InstanceRow row : rows) {
			TaskTruth truth = require(taskTruths, row.taskSetId, "task set");
			ScaleMetadata scale = require(scales, row.taskSetId + "/" + row.scaleLevel, "scale");
			WindowMetadata window = require(windows,
					row.taskSetId + "/" + row.scaleLevel + "/" + row.windowLevel, "window");
			InstanceRow baseRow = baseRows.get(row.taskSetId + "/" + row.setupType + "/" + row.machines);
			if (baseRow == null) {
				throw new IOException("Missing base instance for " + row.path);
			}
			ParsedInstance base = readInstance(baseRow.path, row.size);
			ParsedInstance current = readInstance(row.path, row.size);
			Mismatch mismatch = compare(truth, base, current, scale, window, row.size);
			if (mismatch.total() != 0) {
				throw new IOException("Formal data audit failed for " + row.path + ": " + mismatch);
			}
			output.add(String.format(Locale.ROOT, "%s\t%s\t%s\t%s\t%d\t%s\t%d\t%d\t%d\t%d\t%d\t%.12f",
					row.taskSetId, row.setupType, row.scaleLevel, row.windowLevel, row.machines,
					portable(suiteRoot.toAbsolutePath().normalize().relativize(row.path.toAbsolutePath().normalize())),
					mismatch.processing, mismatch.windows, mismatch.weights, mismatch.setup,
					mismatch.setupCost, mismatch.workloadScale));
		}
		Files.write(generatedRoot.resolve("post-generation-time-scale-audit.tsv"), output,
				StandardCharsets.UTF_8);
		return new AuditSummary(rows.size());
	}

	private static Mismatch compare(TaskTruth truth, ParsedInstance base, ParsedInstance current,
			ScaleMetadata scale, WindowMetadata window, int n) {
		int processingMismatch = 0;
		int windowMismatch = 0;
		int weightMismatch = 0;
		long baseWorkload = 0L;
		long currentWorkload = 0L;
		for (int job = 1; job <= n; job++) {
			FormalTaskSet.Job source = truth.taskSet.jobs().get(job - 1);
			int processingMultiplier = scale.processingMultipliers[job - 1];
			int centerMultiplier = scale.centerMultipliers[job - 1];
			int expectedProcessing = source.processing() * processingMultiplier;
			int baseCenter = truth.centers[job];
			int expectedCenter = baseCenter * centerMultiplier;
			int halfWidth = window.halfWidths[job - 1];
			if (current.processing[job] != expectedProcessing) {
				processingMismatch++;
			}
			if (current.dueEarly[job] != Math.max(0, expectedCenter - halfWidth)
					|| current.dueLate[job] != expectedCenter + halfWidth) {
				windowMismatch++;
			}
			if (current.earlyWeights[job] != source.earlyWeight()
					|| current.tardyWeights[job] != source.tardyWeight()) {
				weightMismatch++;
			}
			baseWorkload += source.processing();
			currentWorkload += current.processing[job];
		}
		int setupMismatch = 0;
		int setupCostMismatch = 0;
		int cap = Math.max(1, (int) Math.floor((double) currentWorkload / n));
		for (int from = 0; from <= n; from++) {
			for (int to = 0; to <= n; to++) {
				int expectedSetup = Math.min(cap, Math.toIntExact(Math.floorDiv(
						(long) base.setup[from][to] * currentWorkload + baseWorkload - 1L,
						baseWorkload)));
				if (current.setup[from][to] != expectedSetup) {
					setupMismatch++;
				}
				double expectedCost = from == to ? 0.0
						: FormalExperimentDesign.SETUP_COST_COEFFICIENT * current.setup[from][to];
				if (Math.abs(current.setupCost[from][to] - expectedCost) > 1e-9) {
					setupCostMismatch++;
				}
			}
		}
		return new Mismatch(processingMismatch, windowMismatch, weightMismatch, setupMismatch,
				setupCostMismatch, (double) currentWorkload / baseWorkload);
	}

	private static Map<String, ScaleMetadata> readScales(Path path, Map<String, TaskTruth> taskTruths)
			throws IOException {
		Map<String, ScaleMetadata> result = new HashMap<String, ScaleMetadata>();
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			final int metadataRow = row;
			String[] fields = lines.get(row).split("\\t", -1);
			if (fields.length != 16) {
				throw new IOException("Malformed scale metadata row " + row + " in " + path);
			}
			int[] processing = parseVector(fields[12]);
			int[] centers = parseVector(fields[13]);
			TaskTruth truth = require(taskTruths, fields[0], "task set");
			FormalTimeScaleGenerator.Scale expected = new FormalTimeScaleGenerator()
					.generate(List.of(truth.taskSet)).get(truth.taskSet.id()).stream()
					.filter(scale -> scale.level().equals(fields[1])).findFirst()
					.orElseThrow(() -> new IOException("Unknown scale level in row " + metadataRow));
			if (Integer.parseInt(fields[2]) != expected.nominalMultiplier()
					|| Long.parseLong(fields[3]) != expected.processingSeed()
					|| Long.parseLong(fields[4]) != expected.centerSeed()
					|| !java.util.Arrays.equals(processing, withoutDummy(expected.processingMultipliers()))
					|| !java.util.Arrays.equals(centers, withoutDummy(expected.centerMultipliers()))
					|| !fields[14].equals(expected.processingFingerprint())
					|| !fields[15].equals(expected.centerFingerprint())
					|| !close(Double.parseDouble(fields[5]), expected.processingArithmeticMean())
					|| !close(Double.parseDouble(fields[6]), expected.centerArithmeticMean())
					|| !close(Double.parseDouble(fields[7]), expected.processingWeightedMultiplier())
					|| Integer.parseInt(fields[8]) != expected.processingMinimum()
					|| Integer.parseInt(fields[9]) != expected.processingMaximum()
					|| Integer.parseInt(fields[10]) != expected.centerMinimum()
					|| Integer.parseInt(fields[11]) != expected.centerMaximum()) {
				throw new IOException("Scale metadata does not match frozen generation rule in row " + row);
			}
			String key = fields[0] + "/" + fields[1];
			if (result.put(key, new ScaleMetadata(processing, centers, expected)) != null) {
				throw new IOException("Duplicate scale metadata: " + key);
			}
		}
		return result;
	}

	private static Map<String, WindowMetadata> readWindows(Path path, Map<String, TaskTruth> taskTruths,
			Map<String, ScaleMetadata> scales) throws IOException {
		Map<String, WindowMetadata> result = new HashMap<String, WindowMetadata>();
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			final int metadataRow = row;
			String[] fields = lines.get(row).split("\\t", -1);
			if (fields.length != 8) {
				throw new IOException("Malformed window metadata row " + row + " in " + path);
			}
			TaskTruth truth = require(taskTruths, fields[0], "task set");
			ScaleMetadata scale = require(scales, fields[0] + "/" + fields[1], "scale");
			FormalWindowGenerator.Window expected = new FormalWindowGenerator()
					.generate(truth.taskSet, scale.expected).stream()
					.filter(window -> window.level().equals(fields[2])).findFirst()
					.orElseThrow(() -> new IOException("Unknown window level in row " + metadataRow));
			int minimum = Integer.parseInt(fields[3]);
			int maximum = Integer.parseInt(fields[4]);
			int[] halfWidths = parseVector(fields[6]);
			if (minimum != expected.minimum() || maximum != expected.maximum()
					|| Long.parseLong(fields[5]) != expected.seed()
					|| !java.util.Arrays.equals(halfWidths, withoutDummy(expected.halfWidths()))
					|| !fields[7].equals(expected.fingerprint())) {
				throw new IOException("Window metadata does not match frozen generation rule in row " + row);
			}
			String key = fields[0] + "/" + fields[1] + "/" + fields[2];
			if (result.put(key, new WindowMetadata(halfWidths)) != null) {
				throw new IOException("Duplicate window metadata: " + key);
			}
		}
		return result;
	}

	private static Map<String, TaskTruth> readTaskTruths(Path path) throws IOException {
		Map<String, TaskTruth> result = new HashMap<String, TaskTruth>();
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (fields.length != 13) {
				throw new IOException("Malformed task metadata row " + row + " in " + path);
			}
			String id = fields[0];
			int size = Integer.parseInt(fields[1]);
			int caseIndex = Integer.parseInt(fields[2]);
			int parentSize = Integer.parseInt(fields[3]);
			Path sourcePath = Path.of(fields[4]);
			List<String> sourceLines = Files.readAllLines(sourcePath, StandardCharsets.UTF_8);
			if (Integer.parseInt(sourceLines.get(0).trim().split("\\s+")[0]) != parentSize) {
				throw new IOException("Source size mismatch for " + id);
			}
			int[] sourceIndices = parseVector(fields[11]);
			int[] centers = withDummy(parseVector(fields[12]));
			if (sourceIndices.length != size || centers.length != size + 1) {
				throw new IOException("Task vector length mismatch for " + id);
			}
			ArrayList<FormalTaskSet.Job> jobs = new ArrayList<FormalTaskSet.Job>(size);
			double parentWorkload = 0.0;
			for (int sourceJob = 1; sourceJob <= parentSize; sourceJob++) {
				parentWorkload += Integer.parseInt(sourceLines.get(sourceJob).trim().split("\\s+")[0]);
			}
			double selectedWorkload = 0.0;
			for (int sourceIndex : sourceIndices) {
				String[] values = sourceLines.get(sourceIndex).trim().split("\\s+");
				FormalTaskSet.Job job = new FormalTaskSet.Job(sourceIndex, Integer.parseInt(values[0]),
						Integer.parseInt(values[1]), Integer.parseInt(values[2]), Integer.parseInt(values[3]));
				jobs.add(job);
				selectedWorkload += job.processing();
			}
			long selectionSeed = FormalTaskSetGenerator.mixSeed(
					FormalExperimentDesign.SOURCE_SELECTION_SEED, parentSize, 0, 0);
			long permutationSeed = parentSize == 50 ? 0L : FormalTaskSetGenerator.mixSeed(
					FormalExperimentDesign.TASK_PERMUTATION_SEED, parentSize, caseIndex,
					sourcePath.getFileName().toString().hashCode());
			FormalTaskSet taskSet = new FormalTaskSet(id, size, parentSize, caseIndex, sourcePath,
					selectionSeed, permutationSeed, parentWorkload, jobs);
			int[] expectedCenters = new FormalDueWindowGenerator().generateCenters(taskSet);
			if (Long.parseLong(fields[5]) != selectionSeed || Long.parseLong(fields[6]) != permutationSeed
					|| !close(Double.parseDouble(fields[7]), parentWorkload)
					|| !close(Double.parseDouble(fields[8]), selectedWorkload)
					|| !close(Double.parseDouble(fields[9]), selectedWorkload / parentWorkload)
					|| Integer.parseInt(fields[10]) != FormalExperimentDesign.referenceMachines(size)
					|| !java.util.Arrays.equals(centers, expectedCenters)) {
				throw new IOException("Task metadata does not match source data in row " + row);
			}
			if (result.put(id, new TaskTruth(taskSet, centers)) != null) {
				throw new IOException("Duplicate task metadata: " + id);
			}
		}
		return result;
	}

	private static List<InstanceRow> readRows(Path index, Path generatedRoot) throws IOException {
		ArrayList<InstanceRow> result = new ArrayList<InstanceRow>();
		List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (fields.length != 14) {
				throw new IOException("Malformed instance metadata row " + row + " in " + index);
			}
			Path indexed = Path.of(fields[11]);
			Path path = indexed.isAbsolute() ? indexed : generatedRoot.resolve(indexed).normalize();
			result.add(new InstanceRow(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[3]),
					fields[4], fields[5], fields[7], path));
		}
		return result;
	}

	private static ParsedInstance readInstance(Path path, int n) throws IOException {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		int[] processing = new int[n + 1];
		int[] dueEarly = new int[n + 1];
		int[] dueLate = new int[n + 1];
		int[] early = new int[n + 1];
		int[] tardy = new int[n + 1];
		for (int job = 1; job <= n; job++) {
			String[] fields = lines.get(job).trim().split("\\s+");
			if (fields.length != 5) {
				throw new IOException("Expected persisted due-window endpoints in " + path);
			}
			processing[job] = Integer.parseInt(fields[0]);
			dueEarly[job] = Integer.parseInt(fields[1]);
			dueLate[job] = Integer.parseInt(fields[2]);
			early[job] = Integer.parseInt(fields[3]);
			tardy[job] = Integer.parseInt(fields[4]);
		}
		int setupMarker = n + 1;
		if (!lines.get(setupMarker).equals("SETUP")) {
			throw new IOException("Missing SETUP marker in " + path);
		}
		int[][] setup = new int[n + 1][n + 1];
		for (int from = 0; from <= n; from++) {
			String[] fields = lines.get(setupMarker + 1 + from).trim().split("\\s+");
			for (int to = 0; to <= n; to++) {
				setup[from][to] = Integer.parseInt(fields[to]);
			}
		}
		int costMarker = setupMarker + n + 2;
		if (!lines.get(costMarker).equals("SETUP_COST")) {
			throw new IOException("Missing SETUP_COST marker in " + path);
		}
		double[][] setupCost = new double[n + 1][n + 1];
		for (int from = 0; from <= n; from++) {
			String[] fields = lines.get(costMarker + 1 + from).trim().split("\\s+");
			for (int to = 0; to <= n; to++) {
				setupCost[from][to] = Double.parseDouble(fields[to]);
			}
		}
		return new ParsedInstance(processing, dueEarly, dueLate, early, tardy, setup, setupCost);
	}

	private static int[] parseVector(String value) {
		String[] fields = value.split(",");
		int[] result = new int[fields.length];
		for (int index = 0; index < fields.length; index++) {
			result[index] = Integer.parseInt(fields[index]);
		}
		return result;
	}

	private static int[] withoutDummy(int[] values) {
		return java.util.Arrays.copyOfRange(values, 1, values.length);
	}

	private static int[] withDummy(int[] values) {
		int[] result = new int[values.length + 1];
		System.arraycopy(values, 0, result, 1, values.length);
		return result;
	}

	private static boolean close(double first, double second) {
		return Math.abs(first - second) <= 1e-8 * Math.max(1.0, Math.max(Math.abs(first), Math.abs(second)));
	}

	private static <T> T require(Map<String, T> values, String key, String type) throws IOException {
		T value = values.get(key);
		if (value == null) {
			throw new IOException("Missing " + type + " metadata: " + key);
		}
		return value;
	}

	private static String portable(Path path) {
		return path.toString().replace('\\', '/');
	}

	private record ScaleMetadata(int[] processingMultipliers, int[] centerMultipliers,
			FormalTimeScaleGenerator.Scale expected) {
	}

	private record WindowMetadata(int[] halfWidths) {
	}

	private record InstanceRow(String taskSetId, int size, int machines, String setupType,
			String scaleLevel, String windowLevel, Path path) {
	}

	private record ParsedInstance(int[] processing, int[] dueEarly, int[] dueLate,
			int[] earlyWeights, int[] tardyWeights, int[][] setup, double[][] setupCost) {
	}

	private record TaskTruth(FormalTaskSet taskSet, int[] centers) {
	}

	private record Mismatch(int processing, int windows, int weights, int setup,
			int setupCost, double workloadScale) {
		private int total() {
			return processing + windows + weights + setup + setupCost;
		}
	}

	public record AuditSummary(int fileCount) {
	}
}
