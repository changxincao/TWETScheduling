package Common.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 独立读取落盘实例，核验逐任务时间倍率和setup整体缩放是否与metadata一致。 */
public final class FormalTimeScaleAuditRunner {
	private FormalTimeScaleAuditRunner() {
	}

	public static void main(String[] args) throws Exception {
		Path suiteRoot = args.length == 0 ? Path.of("experiment-suite", "formal") : Path.of(args[0]);
		AuditSummary summary = audit(suiteRoot);
		System.out.printf(Locale.ROOT,
				"Formal time-scale audit passed: files=%d maxNominalDeviation=%.9f output=%s%n",
				summary.fileCount(), summary.maxAbsoluteWorkloadDeviation(),
				suiteRoot.resolve("instances/post-generation-time-scale-audit.tsv").toAbsolutePath());
	}

	public static AuditSummary audit(Path suiteRoot) throws Exception {
		Path generatedRoot = suiteRoot.resolve("instances");
		Map<String, ScaleMetadata> scales = readScaleMetadata(
				generatedRoot.resolve("scale-selection.tsv"));
		List<InstanceRow> rows = readInstanceRows(generatedRoot.resolve("instances.tsv"), generatedRoot);
		HashMap<String, InstanceRow> byKey = new HashMap<String, InstanceRow>();
		for (InstanceRow row : rows) {
			ScaleMetadata scale = scales.get(row.taskSetId() + "/" + row.scaleLevel());
			if (scale == null || scale.nominalMultiplier() != row.nominalScale()
					|| scale.multipliers().length != row.size()) {
				throw new IOException("Instance/scale metadata mismatch for " + row.path());
			}
			if (byKey.put(row.key(), row) != null) {
				throw new IOException("Duplicate instance metadata key: " + row.key());
			}
		}
		for (InstanceRow row : rows) {
			if (!row.scaleLevel().equals("base")) {
				continue;
			}
			String prefix = row.taskSetId() + "/" + row.setupType() + "/";
			String suffix = "/" + row.machines();
			if (!byKey.containsKey(prefix + "medium" + suffix)
					|| !byKey.containsKey(prefix + "high" + suffix)) {
				throw new IOException("Incomplete base/medium/high instance group: " + row.key());
			}
		}

		ArrayList<String> output = new ArrayList<String>();
		output.add("taskSetId\tsetupType\tscaleLevel\tnominalScale\tmachines\tinstance\t"
				+ "processingMismatches\tcenterMismatches\tweightMismatches\tsetupMismatches\t"
				+ "workloadScale\tmultiplierFingerprint\tbodySha256");
		int audited = 0;
		double maxWeightedDeviation = 0.0;
		for (InstanceRow row : rows) {
			if (row.scaleLevel().equals("base")) {
				continue;
			}
			InstanceRow baseRow = byKey.get(row.baseKey());
			if (baseRow == null) {
				throw new IOException("Missing base instance for " + row.path());
			}
			ScaleMetadata scale = scales.get(row.taskSetId() + "/" + row.scaleLevel());
			if (scale == null || scale.multipliers().length != row.size()) {
				throw new IOException("Missing or malformed scale metadata for " + row.path());
			}
			ParsedInstance base = readInstance(baseRow.path(), row.size());
			ParsedInstance current = readInstance(row.path(), row.size());
			validateWeightedMean(row, scale, base);
			Mismatch mismatch = compare(base, current, scale.multipliers(), row.size());
			if (mismatch.total() != 0) {
				throw new IllegalStateException("Time-scale audit failed for " + row.path()
						+ ": " + mismatch);
			}
			maxWeightedDeviation = Math.max(maxWeightedDeviation,
					Math.abs(mismatch.workloadScale() - row.nominalScale()));
			output.add(String.format(Locale.ROOT,
					"%s\t%s\t%s\t%d\t%d\t%s\t%d\t%d\t%d\t%d\t%.12f\t%s\t%s",
					row.taskSetId(), row.setupType(), row.scaleLevel(), row.nominalScale(), row.machines(),
					portable(suiteRoot.toAbsolutePath().normalize()
							.relativize(row.path().toAbsolutePath().normalize())),
					mismatch.processing(), mismatch.centers(), mismatch.weights(), mismatch.setup(),
					mismatch.workloadScale(), scale.fingerprint(), sha256(row.path())));
			audited++;
		}
		Files.write(generatedRoot.resolve("post-generation-time-scale-audit.tsv"), output,
				StandardCharsets.UTF_8);
		return new AuditSummary(audited, maxWeightedDeviation);
	}

	private static Mismatch compare(ParsedInstance base, ParsedInstance current,
			int[] multipliers, int n) {
		int processingMismatch = 0;
		int centerMismatch = 0;
		int weightMismatch = 0;
		long baseWorkload = 0L;
		long currentWorkload = 0L;
		for (int job = 1; job <= n; job++) {
			if (current.processing()[job] != base.processing()[job] * multipliers[job - 1]) {
				processingMismatch++;
			}
			if (current.centers()[job] != base.centers()[job] * multipliers[job - 1]) {
				centerMismatch++;
			}
			if (current.earlyWeights()[job] != base.earlyWeights()[job]
					|| current.tardyWeights()[job] != base.tardyWeights()[job]) {
				weightMismatch++;
			}
			baseWorkload += base.processing()[job];
			currentWorkload += current.processing()[job];
		}
		int setupMismatch = 0;
		int cap = Math.max(1, (int) Math.floor((double) currentWorkload / n));
		for (int from = 0; from <= n; from++) {
			for (int to = 0; to <= n; to++) {
				long numerator = (long) base.setup()[from][to] * currentWorkload;
				int expected = Math.min(cap, Math.toIntExact(Math.floorDiv(
						numerator + baseWorkload - 1L, baseWorkload)));
				if (current.setup()[from][to] != expected) {
					setupMismatch++;
				}
			}
		}
		return new Mismatch(processingMismatch, centerMismatch, weightMismatch, setupMismatch,
				(double) currentWorkload / baseWorkload);
	}

	private static Map<String, ScaleMetadata> readScaleMetadata(Path path) throws IOException {
		HashMap<String, ScaleMetadata> result = new HashMap<String, ScaleMetadata>();
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		if (lines.isEmpty() || lines.get(0).split("\\t", -1).length != 10) {
			throw new IOException("Unexpected scale metadata schema: " + path);
		}
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (fields.length != 10) {
				throw new IOException("Malformed scale metadata row " + row + " in " + path);
			}
			String[] values = fields[8].split(",");
			int[] multipliers = new int[values.length];
			for (int index = 0; index < values.length; index++) {
				multipliers[index] = Integer.parseInt(values[index]);
			}
			ScaleMetadata metadata = new ScaleMetadata(fields[1], Integer.parseInt(fields[2]),
					Long.parseLong(fields[3]), Double.parseDouble(fields[4]),
					Double.parseDouble(fields[5]), Integer.parseInt(fields[6]),
					Integer.parseInt(fields[7]), multipliers, fields[9]);
			validateVectorSummary(fields[0], metadata);
			if (result.put(fields[0] + "/" + fields[1], metadata) != null) {
				throw new IOException("Duplicate scale metadata row: " + fields[0] + "/" + fields[1]);
			}
		}
		validatePairedScales(result);
		return result;
	}

	private static List<InstanceRow> readInstanceRows(Path index, Path generatedRoot) throws IOException {
		ArrayList<InstanceRow> result = new ArrayList<InstanceRow>();
		List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
		if (lines.isEmpty() || lines.get(0).split("\\t", -1).length != 10) {
			throw new IOException("Unexpected instance metadata schema: " + index);
		}
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (fields.length != 10) {
				throw new IOException("Malformed instance metadata row " + row + " in " + index);
			}
			Path indexed = Path.of(fields[7]);
			Path path = indexed.isAbsolute() ? indexed : generatedRoot.resolve(indexed).normalize();
			if (!Files.isRegularFile(path)) {
				throw new IOException("Missing generated instance: " + path);
			}
			result.add(new InstanceRow(fields[0], Integer.parseInt(fields[1]),
					Integer.parseInt(fields[3]), fields[4], fields[5], Integer.parseInt(fields[6]), path));
		}
		return result;
	}

	private static void validateVectorSummary(String taskSetId, ScaleMetadata scale) throws IOException {
		if (scale.multipliers().length == 0) {
			throw new IOException("Empty scale vector for " + taskSetId + "/" + scale.level());
		}
		long total = 0L;
		int minimum = Integer.MAX_VALUE;
		int maximum = Integer.MIN_VALUE;
		for (int value : scale.multipliers()) {
			total += value;
			minimum = Math.min(minimum, value);
			maximum = Math.max(maximum, value);
		}
		double arithmeticMean = (double) total / scale.multipliers().length;
		int expectedNominal = switch (scale.level()) {
		case "base" -> 1;
		case "medium" -> 10;
		case "high" -> 20;
		default -> throw new IOException("Unknown scale level: " + scale.level());
		};
		if (scale.nominalMultiplier() != expectedNominal
				|| Math.abs(scale.arithmeticMean() - arithmeticMean) > 1e-9
				|| scale.minimum() != minimum || scale.maximum() != maximum
				|| !scale.fingerprint().equals(vectorFingerprint(scale.multipliers()))) {
			throw new IOException("Scale summary mismatch for " + taskSetId + "/" + scale.level());
		}
		if (scale.level().equals("base") && (minimum != 1 || maximum != 1)) {
			throw new IOException("Invalid base scale vector for " + taskSetId);
		}
		if (scale.level().equals("medium") && (minimum < 5 || maximum > 15)) {
			throw new IOException("Invalid medium scale range for " + taskSetId);
		}
		if (scale.level().equals("high") && (minimum < 15 || maximum > 25)) {
			throw new IOException("Invalid high scale range for " + taskSetId);
		}
	}

	private static void validatePairedScales(Map<String, ScaleMetadata> scales) throws IOException {
		for (Map.Entry<String, ScaleMetadata> entry : scales.entrySet()) {
			if (!entry.getKey().endsWith("/medium")) {
				continue;
			}
			String taskSetId = entry.getKey().substring(0, entry.getKey().length() - "/medium".length());
			ScaleMetadata base = scales.get(taskSetId + "/base");
			ScaleMetadata medium = entry.getValue();
			ScaleMetadata high = scales.get(taskSetId + "/high");
			if (base == null || high == null || base.selectionSeed() != medium.selectionSeed()
					|| high.selectionSeed() != medium.selectionSeed()
					|| base.multipliers().length != medium.multipliers().length
					|| high.multipliers().length != medium.multipliers().length) {
				throw new IOException("Incomplete paired scale metadata for " + taskSetId);
			}
			for (int job = 0; job < medium.multipliers().length; job++) {
				if (high.multipliers()[job] != medium.multipliers()[job] + 10) {
					throw new IOException("High/medium scale mismatch for " + taskSetId
							+ " job " + (job + 1));
				}
			}
		}
	}

	private static void validateWeightedMean(InstanceRow row, ScaleMetadata scale,
			ParsedInstance base) throws IOException {
		long workload = 0L;
		long scaledWorkload = 0L;
		for (int job = 1; job <= row.size(); job++) {
			workload += base.processing()[job];
			scaledWorkload += (long) base.processing()[job] * scale.multipliers()[job - 1];
		}
		double weightedMean = (double) scaledWorkload / workload;
		if (Math.abs(weightedMean - scale.processingWeightedMean()) > 1e-8
				|| Math.abs(weightedMean - scale.nominalMultiplier()) > 0.10) {
			throw new IOException("Processing-weighted scale mismatch for " + row.taskSetId()
					+ "/" + row.scaleLevel());
		}
	}

	private static String vectorFingerprint(int[] multipliers) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (int value : multipliers) {
				digest.update(Integer.toString(value).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) ',');
			}
			StringBuilder result = new StringBuilder(64);
			for (byte value : digest.digest()) {
				result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
			}
			return result.toString();
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new IOException("SHA-256 is unavailable", ex);
		}
	}

	private static ParsedInstance readInstance(Path path, int n) throws IOException {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		int[] processing = new int[n + 1];
		int[] centers = new int[n + 1];
		int[] early = new int[n + 1];
		int[] tardy = new int[n + 1];
		for (int job = 1; job <= n; job++) {
			String[] fields = lines.get(job).trim().split("\\s+");
			processing[job] = Integer.parseInt(fields[0]);
			centers[job] = Integer.parseInt(fields[1]);
			early[job] = Integer.parseInt(fields[2]);
			tardy[job] = Integer.parseInt(fields[3]);
		}
		if (!lines.get(n + 1).equals("SETUP")) {
			throw new IOException("Missing SETUP marker in " + path);
		}
		int[][] setup = new int[n + 1][n + 1];
		for (int from = 0; from <= n; from++) {
			String[] fields = lines.get(n + 2 + from).trim().split("\\s+");
			for (int to = 0; to <= n; to++) {
				setup[from][to] = Integer.parseInt(fields[to]);
			}
		}
		return new ParsedInstance(processing, centers, early, tardy, setup);
	}

	private static String sha256(Path path) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		StringBuilder result = new StringBuilder(64);
		for (byte value : digest.digest(Files.readAllBytes(path))) {
			result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
		}
		return result.toString();
	}

	private static String portable(Path path) {
		return path.toString().replace('\\', '/');
	}

	private record InstanceRow(String taskSetId, int size, int machines, String setupType,
			String scaleLevel, int nominalScale, Path path) {
		private String key() {
			return taskSetId + "/" + setupType + "/" + scaleLevel + "/" + machines;
		}

		private String baseKey() {
			return taskSetId + "/" + setupType + "/base/" + machines;
		}
	}

	private record ScaleMetadata(String level, int nominalMultiplier, long selectionSeed,
			double arithmeticMean, double processingWeightedMean, int minimum, int maximum,
			int[] multipliers, String fingerprint) {
	}

	private record ParsedInstance(int[] processing, int[] centers, int[] earlyWeights,
			int[] tardyWeights, int[][] setup) {
	}

	private record Mismatch(int processing, int centers, int weights, int setup,
			double workloadScale) {
		private int total() {
			return processing + centers + weights + setup;
		}
	}

	public record AuditSummary(int fileCount, double maxAbsoluteWorkloadDeviation) {
	}
}
