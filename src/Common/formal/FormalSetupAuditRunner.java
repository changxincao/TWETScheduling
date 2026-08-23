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

import Common.formal.FormalSetupValidator.Report;

/**
 * 生成后独立读取所有正式.dat，复查setup强度、上限、三角性和跨机器副本一致性。
 */
public final class FormalSetupAuditRunner {
	private FormalSetupAuditRunner() {
	}

	public static void main(String[] args) throws Exception {
		Path suiteRoot = args.length == 0 ? Path.of("experiment-suite", "formal") : Path.of(args[0]);
		AuditSummary summary = audit(suiteRoot);
		System.out.printf(Locale.ROOT,
				"Formal setup audit passed: files=%d groups=%d maxMeanError=%.9f output=%s%n",
				summary.fileCount(), summary.groupCount(), summary.maxAbsoluteMeanError(),
				summary.output().toAbsolutePath());
	}

	public static AuditSummary audit(Path suiteRoot) throws Exception {
		Path absoluteSuiteRoot = suiteRoot.toAbsolutePath().normalize();
		Path generatedRoot = absoluteSuiteRoot.resolve("instances");
		Path index = generatedRoot.resolve("instances.tsv");
		Path setupMetadata = generatedRoot.resolve("setup-audit.tsv");
		Map<String, int[]> familyAssignments = readFamilyAssignments(setupMetadata);
		List<String> indexLines = Files.readAllLines(index, StandardCharsets.UTF_8);
		ArrayList<String> output = new ArrayList<String>();
		output.add("taskSetId\tsetupType\tscaleLevel\tscale\tmachines\tinstance\ttargetMean\tactualMean\t"
				+ "meanError\tcap\tmaximum\ttriangleViolations\tfloydChangedArcs\twithinMean\tbetweenMean\tbodySha256");
		HashMap<String, String> bodyHashByGroup = new HashMap<String, String>();
		double maxError = 0.0;
		int files = 0;
		for (int row = 1; row < indexLines.size(); row++) {
			String[] fields = indexLines.get(row).split("\\t", -1);
			if (fields.length < 10) {
				throw new IOException("Malformed generated instance index row: " + indexLines.get(row));
			}
			String taskSetId = fields[0];
			int size = Integer.parseInt(fields[1]);
			int machines = Integer.parseInt(fields[3]);
			String setupType = fields[4];
			String scaleLevel = fields[5];
			int scale = Integer.parseInt(fields[6]);
			Path indexedPath = Path.of(fields[7]);
			Path path = indexedPath.isAbsolute() ? indexedPath : generatedRoot.resolve(indexedPath).normalize();
			ParsedInstance instance = readInstance(path, size);
			long baseProcessingTotal = baseProcessingTotal(instance.processing(), size, scale);
			double averageProcessing = (double) Math.multiplyExact(baseProcessingTotal, scale) / size;
			double targetMean = FormalExperimentDesign.SETUP_MEAN_RATIO * averageProcessing;
			int cap = Math.multiplyExact(Math.toIntExact(baseProcessingTotal / size), scale);
			int[] family = "family".equals(setupType) ? familyAssignments.get(taskSetId) : null;
			if ("family".equals(setupType) && family == null) {
				throw new IOException("Missing family assignment for " + taskSetId);
			}
			FormalSetupValidator validator = new FormalSetupValidator();
			Report report = validator.audit(instance.setup(), targetMean, cap, family);
			validator.requirePreset(report, "family".equals(setupType));
			String bodyHash = hashBody(path);
			String group = taskSetId + "/" + setupType + "/" + scaleLevel + "/g" + scale;
			String previous = bodyHashByGroup.putIfAbsent(group, bodyHash);
			if (previous != null && !previous.equals(bodyHash)) {
				throw new IllegalStateException("Machine copies differ for " + group + ": " + path);
			}
			maxError = Math.max(maxError, Math.abs(report.meanError()));
			output.add(String.format(Locale.ROOT,
					"%s\t%s\t%s\t%d\t%d\t%s\t%.9f\t%.9f\t%.9f\t%d\t%d\t%d\t%d\t%.9f\t%.9f\t%s",
					taskSetId, setupType, scaleLevel, scale, machines,
					portable(absoluteSuiteRoot.relativize(path.toAbsolutePath().normalize())), report.targetMean(),
					report.actualMean(), report.meanError(), report.cap(), report.maximum(),
					report.triangleViolations(), report.floydChangedArcs(), report.withinMean(),
					report.betweenMean(), bodyHash));
			files++;
		}
		Path outputPath = generatedRoot.resolve("post-generation-setup-audit.tsv");
		Files.write(outputPath, output, StandardCharsets.UTF_8);
		return new AuditSummary(files, bodyHashByGroup.size(), maxError, outputPath);
	}

	private static String portable(Path path) {
		return path.normalize().toString().replace('\\', '/');
	}

	private static Map<String, int[]> readFamilyAssignments(Path path) throws IOException {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		HashMap<String, int[]> result = new HashMap<String, int[]>();
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (fields.length < 23 || !"family".equals(fields[1]) || fields[22].isEmpty()) {
				continue;
			}
			String[] tokens = fields[22].split(",");
			int[] assignment = new int[tokens.length + 1];
			for (int job = 1; job <= tokens.length; job++) {
				assignment[job] = Integer.parseInt(tokens[job - 1]);
			}
			result.putIfAbsent(fields[0], assignment);
		}
		return result;
	}

	private static ParsedInstance readInstance(Path path, int expectedSize) throws IOException {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		String[] header = lines.get(0).trim().split("\\s+");
		int size = Integer.parseInt(header[0]);
		if (size != expectedSize || lines.size() < 2 * size + 3) {
			throw new IOException("Malformed generated instance: " + path);
		}
		int[] processing = new int[size + 1];
		for (int job = 1; job <= size; job++) {
			processing[job] = Integer.parseInt(lines.get(job).trim().split("\\s+")[0]);
		}
		if (!"SETUP".equals(lines.get(size + 1).trim())) {
			throw new IOException("Missing SETUP block: " + path);
		}
		int[][] setup = new int[size + 1][size + 1];
		for (int from = 0; from <= size; from++) {
			String[] tokens = lines.get(size + 2 + from).trim().split("\\s+");
			if (tokens.length != size + 1) {
				throw new IOException("Malformed SETUP row in " + path);
			}
			for (int to = 0; to <= size; to++) {
				setup[from][to] = Integer.parseInt(tokens[to]);
			}
		}
		return new ParsedInstance(processing, setup);
	}

	private static String hashBody(Path path) throws Exception {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for (int line = 1; line < lines.size(); line++) {
			digest.update(lines.get(line).getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
		}
		StringBuilder value = new StringBuilder(64);
		for (byte item : digest.digest()) {
			value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
		}
		return value.toString();
	}

	private static long baseProcessingTotal(int[] values, int size, int scale) throws IOException {
		long total = 0L;
		for (int job = 1; job <= size; job++) {
			if (values[job] % scale != 0) {
				throw new IOException("Processing time is not divisible by scale " + scale + ": " + values[job]);
			}
			total += values[job] / scale;
		}
		return total;
	}

	private record ParsedInstance(int[] processing, int[][] setup) {
	}

	public record AuditSummary(int fileCount, int groupCount, double maxAbsoluteMeanError, Path output) {
	}
}
