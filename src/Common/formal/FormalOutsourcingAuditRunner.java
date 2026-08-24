package Common.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 独立从 Tanaka 源任务复算报价，并核验落盘外包 overlay 的完整 tariff。 */
public final class FormalOutsourcingAuditRunner {
	private FormalOutsourcingAuditRunner() {
	}

	public static void main(String[] args) throws Exception {
		Path suiteRoot = args.length == 0 ? Path.of("experiment-suite", "formal") : Path.of(args[0]);
		AuditSummary summary = audit(suiteRoot);
		System.out.printf(Locale.ROOT, "Formal outsourcing audit passed: overlays=%d output=%s%n",
				summary.overlayCount(), summary.output().toAbsolutePath());
	}

	public static AuditSummary audit(Path suiteRoot) throws Exception {
		Path generatedRoot = suiteRoot.resolve("instances");
		Map<String, TaskQuotation> quotations = readTaskQuotations(generatedRoot.resolve("task-selection.tsv"));
		double referenceTotal = referenceTotal(quotations.values());
		double breakpoint1 = 0.25 * referenceTotal;
		double breakpoint2 = 0.50 * referenceTotal;
		List<String> indexLines = Files.readAllLines(generatedRoot.resolve("outsourcing-data.tsv"),
				StandardCharsets.UTF_8);
		ArrayList<String> output = new ArrayList<String>();
		output.add("taskSetId\trate\tdiscountStrength\tquotationTotal\tsegmentCount\toverlay");
		Map<String, Boolean> seen = new HashMap<String, Boolean>();
		for (int row = 1; row < indexLines.size(); row++) {
			String[] fields = indexLines.get(row).split("\\t", -1);
			if (fields.length != 7) {
				throw new IOException("Malformed outsourcing index row " + row);
			}
			TaskQuotation quotation = quotations.get(fields[0]);
			if (quotation == null) {
				throw new IOException("Unknown task set in outsourcing index: " + fields[0]);
			}
			double rate = Double.parseDouble(fields[1]);
			double discount = Double.parseDouble(fields[2]);
			if (!close(Double.parseDouble(fields[3]), breakpoint1)
					|| !close(Double.parseDouble(fields[4]), breakpoint2)
					|| !close(Double.parseDouble(fields[5]), quotation.total)) {
				throw new IOException("Outsourcing index values do not match frozen design in row " + row);
			}
			String key = fields[0] + "/" + rate + "/" + discount;
			if (seen.put(key, Boolean.TRUE) != null) {
				throw new IOException("Duplicate outsourcing overlay: " + key);
			}
			Path indexed = Path.of(fields[6]);
			Path overlayPath = indexed.isAbsolute() ? indexed : generatedRoot.resolve(indexed).normalize();
			OverlayData overlay = readOverlay(overlayPath, quotation.values.length - 1);
			if (!java.util.Arrays.equals(overlay.quotations, quotation.values)) {
				throw new IOException("Outsourcing quotations do not match source tasks: " + overlayPath);
			}
			List<Segment> expected = expectedSegments(rate, discount, breakpoint1, breakpoint2,
					Math.max(1.0, quotation.total + 1.0));
			if (!sameSegments(overlay.segments, expected) || !coversAndIsContinuous(overlay.segments, quotation.total)) {
				throw new IOException("Outsourcing tariff does not match frozen design: " + overlayPath);
			}
			output.add(String.format(Locale.ROOT, "%s\t%.6f\t%.6f\t%.9f\t%d\t%s",
					fields[0], rate, discount, quotation.total, overlay.segments.size(),
					portable(suiteRoot.toAbsolutePath().normalize()
							.relativize(overlayPath.toAbsolutePath().normalize()))));
		}
		int expectedCount = quotations.size() * 4;
		if (seen.size() != expectedCount) {
			throw new IOException("Expected " + expectedCount + " outsourcing overlays, found " + seen.size());
		}
		Path outputPath = generatedRoot.resolve("post-generation-outsourcing-audit.tsv");
		Files.write(outputPath, output, StandardCharsets.UTF_8);
		return new AuditSummary(seen.size(), outputPath);
	}

	private static Map<String, TaskQuotation> readTaskQuotations(Path path) throws IOException {
		Map<String, TaskQuotation> result = new HashMap<String, TaskQuotation>();
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if (fields.length != 13) {
				throw new IOException("Malformed task metadata row " + row);
			}
			int size = Integer.parseInt(fields[1]);
			int[] sourceIndices = parseIntVector(fields[11]);
			if (sourceIndices.length != size) {
				throw new IOException("Task selection length mismatch in row " + row);
			}
			List<String> source = Files.readAllLines(Path.of(fields[4]), StandardCharsets.UTF_8);
			double[] values = new double[size + 1];
			double total = 0.0;
			for (int job = 1; job <= size; job++) {
				String[] sourceRow = source.get(sourceIndices[job - 1]).trim().split("\\s+");
				values[job] = Integer.parseInt(sourceRow[0])
						* Math.max(Integer.parseInt(sourceRow[2]), Integer.parseInt(sourceRow[3]));
				total += values[job];
			}
			if (result.put(fields[0], new TaskQuotation(size, values, total)) != null) {
				throw new IOException("Duplicate task set in task metadata: " + fields[0]);
			}
		}
		return result;
	}

	private static double referenceTotal(java.util.Collection<TaskQuotation> quotations) {
		ArrayList<Double> totals = new ArrayList<Double>();
		for (TaskQuotation quotation : quotations) {
			if (quotation.size == 50) {
				totals.add(Double.valueOf(quotation.total));
			}
		}
		if (totals.isEmpty()) {
			for (TaskQuotation quotation : quotations) {
				totals.add(Double.valueOf(quotation.total));
			}
		}
		totals.sort(Comparator.naturalOrder());
		int middle = totals.size() / 2;
		return totals.size() % 2 == 0
				? 0.5 * (totals.get(middle - 1).doubleValue() + totals.get(middle).doubleValue())
				: totals.get(middle).doubleValue();
	}

	private static OverlayData readOverlay(Path path, int n) throws IOException {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		if (lines.size() < 5 || !"OUTSOURCING_COST".equals(lines.get(0).trim())
				|| !"OUTSOURCING_TARIFF".equals(lines.get(2).trim())) {
			throw new IOException("Malformed outsourcing overlay: " + path);
		}
		String[] quotationTokens = lines.get(1).trim().split("\\s+");
		if (quotationTokens.length != n) {
			throw new IOException("Outsourcing quotation length mismatch: " + path);
		}
		double[] quotations = new double[n + 1];
		for (int job = 1; job <= n; job++) {
			quotations[job] = Double.parseDouble(quotationTokens[job - 1]);
		}
		int count = Integer.parseInt(lines.get(3).trim());
		if (lines.size() != count + 4) {
			throw new IOException("Outsourcing tariff segment count mismatch: " + path);
		}
		ArrayList<Segment> segments = new ArrayList<Segment>(count);
		for (int index = 0; index < count; index++) {
			String[] fields = lines.get(index + 4).trim().split("\\s+");
			if (fields.length != 4) {
				throw new IOException("Malformed outsourcing tariff segment: " + path);
			}
			segments.add(new Segment(Double.parseDouble(fields[0]), Double.parseDouble(fields[1]),
					Double.parseDouble(fields[2]), Double.parseDouble(fields[3])));
		}
		return new OverlayData(quotations, segments);
	}

	private static List<Segment> expectedSegments(double rate, double discount, double firstBreakpoint,
			double secondBreakpoint, double domainEnd) {
		if (discount == 0.0) {
			return List.of(new Segment(0.0, domainEnd, rate, 0.0));
		}
		double[] ends = new double[] { Math.min(firstBreakpoint, domainEnd),
				Math.min(secondBreakpoint, domainEnd), domainEnd };
		ArrayList<Segment> result = new ArrayList<Segment>(3);
		double start = 0.0;
		double valueAtStart = 0.0;
		for (int index = 0; index < ends.length; index++) {
			if (ends[index] <= start) {
				continue;
			}
			double slope = rate * (1.0 - index * discount);
			double intercept = valueAtStart - slope * start;
			result.add(new Segment(start, ends[index], slope, intercept));
			valueAtStart += slope * (ends[index] - start);
			start = ends[index];
		}
		return result;
	}

	private static boolean sameSegments(List<Segment> actual, List<Segment> expected) {
		if (actual.size() != expected.size()) {
			return false;
		}
		for (int index = 0; index < actual.size(); index++) {
			Segment first = actual.get(index);
			Segment second = expected.get(index);
			if (!close(first.start, second.start) || !close(first.end, second.end)
					|| !close(first.slope, second.slope) || !close(first.intercept, second.intercept)) {
				return false;
			}
		}
		return true;
	}

	private static boolean coversAndIsContinuous(List<Segment> segments, double requiredEnd) {
		if (segments.isEmpty() || !close(segments.get(0).start, 0.0)
				|| segments.get(segments.size() - 1).end < requiredEnd) {
			return false;
		}
		for (int index = 0; index < segments.size(); index++) {
			Segment current = segments.get(index);
			if (current.end <= current.start || current.slope < 0.0) {
				return false;
			}
			if (index > 0) {
				Segment previous = segments.get(index - 1);
				double previousEndValue = previous.slope * previous.end + previous.intercept;
				double currentStartValue = current.slope * current.start + current.intercept;
				if (!close(previous.end, current.start) || !close(previousEndValue, currentStartValue)) {
					return false;
				}
			}
		}
		return true;
	}

	private static int[] parseIntVector(String value) {
		String[] fields = value.split(",");
		int[] result = new int[fields.length];
		for (int index = 0; index < fields.length; index++) {
			result[index] = Integer.parseInt(fields[index]);
		}
		return result;
	}

	private static boolean close(double first, double second) {
		return Math.abs(first - second) <= 1e-8 * Math.max(1.0, Math.max(Math.abs(first), Math.abs(second)));
	}

	private static String portable(Path path) {
		return path.normalize().toString().replace('\\', '/');
	}

	private record TaskQuotation(int size, double[] values, double total) {
	}

	private record Segment(double start, double end, double slope, double intercept) {
	}

	private record OverlayData(double[] quotations, List<Segment> segments) {
	}

	public record AuditSummary(int overlayCount, Path output) {
	}
}
