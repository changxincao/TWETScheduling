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

import Common.formal.FormalOutsourcingDataGenerator.Segment;

/** 从源任务和纯调度文件独立核验外包完整实例。 */
public final class FormalOutsourcingAuditRunner {
	private FormalOutsourcingAuditRunner() {
	}

	public static void main(String[] args) throws Exception {
		Path suiteRoot = args.length == 0 ? Path.of("experiment-suite", "formal") : Path.of(args[0]);
		AuditSummary summary = audit(suiteRoot);
		System.out.printf(Locale.ROOT,
				"Formal outsourcing audit passed: default=%d noDiscount=%d output=%s%n",
				summary.defaultDiscountCount(), summary.noDiscountCount(), summary.output().toAbsolutePath());
	}

	public static AuditSummary audit(Path suiteRoot) throws Exception {
		Path generatedRoot = suiteRoot.resolve("instances");
		Map<String, TaskQuotation> quotations = readTaskQuotations(generatedRoot.resolve("task-selection.tsv"));
		double referenceTotal = referenceTotal(quotations.values());
		double breakpoint1 = Math.round(0.25 * referenceTotal);
		double breakpoint2 = Math.round(0.50 * referenceTotal);
		List<String> indexLines = Files.readAllLines(generatedRoot.resolve("outsourcing-instances.tsv"),
				StandardCharsets.UTF_8);
		ArrayList<String> output = new ArrayList<String>();
		output.add("taskSetId\trate\tdiscountStrength\tquotationTotal\tsegmentCount\texperimentType\tinstance");
		Map<String, Boolean> seen = new HashMap<String, Boolean>();
		int defaultCount = 0;
		int noDiscountCount = 0;
		for (int row = 1; row < indexLines.size(); row++) {
			String[] fields = indexLines.get(row).split("\\t", -1);
			if (fields.length != 13) {
				throw new IOException("Malformed outsourcing instance index row " + row);
			}
			TaskQuotation quotation = quotations.get(fields[0]);
			if (quotation == null || quotation.size != Integer.parseInt(fields[1])) {
				throw new IOException("Unknown task set in outsourcing index: " + fields[0]);
			}
			double rate = Double.parseDouble(fields[5]);
			double discount = Double.parseDouble(fields[6]);
			if (!close(Double.parseDouble(fields[7]), breakpoint1)
					|| !close(Double.parseDouble(fields[8]), breakpoint2)
					|| !close(Double.parseDouble(fields[9]), quotation.total)) {
				throw new IOException("Outsourcing index values do not match frozen design in row " + row);
			}
			Path source = generatedRoot.resolve(fields[11]).normalize();
			Path complete = generatedRoot.resolve(fields[12]).normalize();
			String key = complete.toAbsolutePath().normalize().toString();
			if (seen.put(key, Boolean.TRUE) != null) {
				throw new IOException("Duplicate complete outsourcing instance: " + complete);
			}
			CompleteData data = readCompleteInstance(source, complete, quotation.size);
			if (!java.util.Arrays.equals(data.quotations, quotation.values)) {
				throw new IOException("Outsourcing quotations do not match source tasks: " + complete);
			}
			List<Segment> expected = FormalOutsourcingDataGenerator.segments(rate, discount,
					breakpoint1, breakpoint2, Math.max(1.0, quotation.total + 1.0));
			if (!sameSegments(data.segments, expected)
					|| !coversAndIsContinuous(data.segments, quotation.total)) {
				throw new IOException("Outsourcing tariff does not match frozen design: " + complete);
			}
			String experimentType = fields[10];
			if ("default-discount".equals(experimentType)) {
				if (!close(discount, FormalExperimentDesign.DEFAULT_DISCOUNT_STRENGTH)) {
					throw new IOException("Default-discount file has wrong discount: " + complete);
				}
				defaultCount++;
			} else if ("no-discount".equals(experimentType)) {
				if (quotation.size != 50 || !close(rate, 1.0) || !close(discount, 0.0)
						|| data.segments.size() != 1) {
					throw new IOException("Invalid no-discount comparison file: " + complete);
				}
				noDiscountCount++;
			} else {
				throw new IOException("Unknown outsourcing experiment type: " + experimentType);
			}
			output.add(String.format(Locale.ROOT, "%s\t%.6f\t%.6f\t%.9f\t%d\t%s\t%s",
					fields[0], rate, discount, quotation.total, data.segments.size(), experimentType,
					portable(suiteRoot.toAbsolutePath().normalize()
							.relativize(complete.toAbsolutePath().normalize()))));
		}

		ExpectedCounts expectedCounts = expectedCounts(generatedRoot.resolve("instances.tsv"));
		if (defaultCount != expectedCounts.baseInstances * FormalExperimentDesign.OUTSOURCING_RATES.length
				|| noDiscountCount != expectedCounts.n50BaseInstances) {
			throw new IOException("Outsourcing complete-file count mismatch: default=" + defaultCount
					+ " noDiscount=" + noDiscountCount);
		}
		Path outputPath = generatedRoot.resolve("post-generation-outsourcing-audit.tsv");
		Files.write(outputPath, output, StandardCharsets.UTF_8);
		return new AuditSummary(defaultCount, noDiscountCount, outputPath);
	}

	private static CompleteData readCompleteInstance(Path source, Path complete, int n) throws IOException {
		List<String> sourceLines = Files.readAllLines(source, StandardCharsets.UTF_8);
		List<String> completeLines = Files.readAllLines(complete, StandardCharsets.UTF_8);
		if (completeLines.size() < sourceLines.size() + 5
				|| !completeLines.subList(0, sourceLines.size()).equals(sourceLines)) {
			throw new IOException("Scheduling prefix differs in complete outsourcing file: " + complete);
		}
		int offset = sourceLines.size();
		if (!"OUTSOURCING_COST".equals(completeLines.get(offset).trim())
				|| !"OUTSOURCING_TARIFF".equals(completeLines.get(offset + 2).trim())) {
			throw new IOException("Missing consecutive outsourcing blocks: " + complete);
		}
		String[] quotationTokens = completeLines.get(offset + 1).trim().split("\\s+");
		if (quotationTokens.length != n) {
			throw new IOException("Outsourcing quotation length mismatch: " + complete);
		}
		double[] quotationValues = new double[n + 1];
		for (int job = 1; job <= n; job++) {
			quotationValues[job] = Double.parseDouble(quotationTokens[job - 1]);
		}
		int count = Integer.parseInt(completeLines.get(offset + 3).trim());
		if (completeLines.size() != offset + count + 4) {
			throw new IOException("Outsourcing tariff segment count mismatch: " + complete);
		}
		ArrayList<Segment> segments = new ArrayList<Segment>(count);
		for (int index = 0; index < count; index++) {
			String[] values = completeLines.get(offset + 4 + index).trim().split("\\s+");
			if (values.length != 4) {
				throw new IOException("Malformed outsourcing tariff segment: " + complete);
			}
			segments.add(new Segment(Double.parseDouble(values[0]), Double.parseDouble(values[1]),
					Double.parseDouble(values[2]), Double.parseDouble(values[3])));
		}
		return new CompleteData(quotationValues, segments);
	}

	private static ExpectedCounts expectedCounts(Path index) throws IOException {
		int base = 0;
		int n50Base = 0;
		List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			if ("base".equals(fields[5])) {
				base++;
				if (Integer.parseInt(fields[1]) == 50) {
					n50Base++;
				}
			}
		}
		return new ExpectedCounts(base, n50Base);
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
			List<String> source = Files.readAllLines(Path.of(fields[4]), StandardCharsets.UTF_8);
			double[] values = new double[size + 1];
			double total = 0.0;
			for (int job = 1; job <= size; job++) {
				String[] sourceRow = source.get(sourceIndices[job - 1]).trim().split("\\s+");
				values[job] = Integer.parseInt(sourceRow[0])
						* Math.max(Integer.parseInt(sourceRow[2]), Integer.parseInt(sourceRow[3]));
				total += values[job];
			}
			result.put(fields[0], new TaskQuotation(size, values, total));
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
				? 0.5 * (totals.get(middle - 1) + totals.get(middle)) : totals.get(middle);
	}

	private static boolean sameSegments(List<Segment> actual, List<Segment> expected) {
		if (actual.size() != expected.size()) {
			return false;
		}
		for (int index = 0; index < actual.size(); index++) {
			Segment first = actual.get(index);
			Segment second = expected.get(index);
			if (!close(first.start(), second.start()) || !close(first.end(), second.end())
					|| !close(first.slope(), second.slope()) || !close(first.intercept(), second.intercept())) {
				return false;
			}
		}
		return true;
	}

	private static boolean coversAndIsContinuous(List<Segment> segments, double requiredEnd) {
		if (segments.isEmpty() || !close(segments.get(0).start(), 0.0)
				|| segments.get(segments.size() - 1).end() < requiredEnd) {
			return false;
		}
		for (int index = 0; index < segments.size(); index++) {
			Segment current = segments.get(index);
			if (current.end() <= current.start() || current.slope() < 0.0) {
				return false;
			}
			if (index > 0) {
				Segment previous = segments.get(index - 1);
				double previousEndValue = previous.slope() * previous.end() + previous.intercept();
				double currentStartValue = current.slope() * current.start() + current.intercept();
				if (!close(previous.end(), current.start()) || !close(previousEndValue, currentStartValue)) {
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

	private record CompleteData(double[] quotations, List<Segment> segments) {
	}

	private record ExpectedCounts(int baseInstances, int n50BaseInstances) {
	}

	public record AuditSummary(int defaultDiscountCount, int noDiscountCount, Path output) {
	}
}
