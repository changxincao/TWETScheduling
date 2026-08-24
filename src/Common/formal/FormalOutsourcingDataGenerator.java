package Common.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 将外包基础报价和实际分段 tariff 写成独立 overlay 文件。 */
public final class FormalOutsourcingDataGenerator {

	public Result generate(Path outputRoot, List<FormalTaskSet> taskSets) throws IOException {
		double referenceTotal = referenceTotal(taskSets);
		double firstBreakpoint = 0.25 * referenceTotal;
		double secondBreakpoint = 0.50 * referenceTotal;
		ArrayList<Overlay> overlays = new ArrayList<Overlay>();
		for (FormalTaskSet taskSet : taskSets) {
			for (double rate : FormalExperimentDesign.OUTSOURCING_RATES) {
				overlays.add(write(outputRoot, taskSet, rate,
						FormalExperimentDesign.DEFAULT_DISCOUNT_STRENGTH,
						firstBreakpoint, secondBreakpoint));
			}
			overlays.add(write(outputRoot, taskSet, 1.0, 0.0,
					firstBreakpoint, secondBreakpoint));
		}
		writeIndex(outputRoot, overlays, referenceTotal, firstBreakpoint, secondBreakpoint);
		return new Result(List.copyOf(overlays), referenceTotal, firstBreakpoint, secondBreakpoint);
	}

	private static Overlay write(Path outputRoot, FormalTaskSet taskSet, double rate,
			double discountStrength, double firstBreakpoint, double secondBreakpoint) throws IOException {
		Path directory = outputRoot.resolve("outsourcing-data").resolve(taskSet.id());
		Files.createDirectories(directory);
		String discountId = discountStrength == 0.0 ? "none" : "default";
		Path path = directory.resolve("rate-" + compact(rate) + "-discount-" + discountId + ".dat");
		double[] quotations = quotations(taskSet);
		double total = 0.0;
		StringBuilder quotationLine = new StringBuilder();
		for (int job = 1; job <= taskSet.size(); job++) {
			if (job > 1) {
				quotationLine.append(' ');
			}
			quotationLine.append(compact(quotations[job]));
			total += quotations[job];
		}
		double domainEnd = Math.max(1.0, total + 1.0);
		ArrayList<Segment> segments = segments(rate, discountStrength,
				firstBreakpoint, secondBreakpoint, domainEnd);
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("OUTSOURCING_COST");
		lines.add(quotationLine.toString());
		lines.add("OUTSOURCING_TARIFF");
		lines.add(Integer.toString(segments.size()));
		for (Segment segment : segments) {
			lines.add(String.format(Locale.ROOT, "%.9f %.9f %.9f %.9f",
					segment.start, segment.end, segment.slope, segment.intercept));
		}
		Files.write(path, lines, StandardCharsets.UTF_8);
		return new Overlay(taskSet.id(), rate, discountStrength, firstBreakpoint,
				secondBreakpoint, total, path);
	}

	private static ArrayList<Segment> segments(double rate, double discountStrength,
			double firstBreakpoint, double secondBreakpoint, double domainEnd) {
		if (discountStrength == 0.0) {
			ArrayList<Segment> result = new ArrayList<Segment>(1);
			result.add(new Segment(0.0, domainEnd, rate, 0.0));
			return result;
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
			double slope = rate * (1.0 - index * discountStrength);
			double intercept = valueAtStart - slope * start;
			result.add(new Segment(start, ends[index], slope, intercept));
			valueAtStart += slope * (ends[index] - start);
			start = ends[index];
		}
		return result;
	}

	private static double[] quotations(FormalTaskSet taskSet) {
		double[] result = new double[taskSet.size() + 1];
		for (int job = 1; job <= taskSet.size(); job++) {
			FormalTaskSet.Job source = taskSet.jobs().get(job - 1);
			result[job] = source.processing() * Math.max(source.earlyWeight(), source.tardyWeight());
		}
		return result;
	}

	private static double referenceTotal(List<FormalTaskSet> taskSets) {
		ArrayList<Double> totals = new ArrayList<Double>();
		for (FormalTaskSet taskSet : taskSets) {
			if (taskSet.size() == 50) {
				totals.add(Double.valueOf(totalQuotation(taskSet)));
			}
		}
		if (totals.isEmpty()) {
			for (FormalTaskSet taskSet : taskSets) {
				totals.add(Double.valueOf(totalQuotation(taskSet)));
			}
		}
		if (totals.isEmpty()) {
			throw new IllegalArgumentException("At least one task set is required");
		}
		totals.sort(Comparator.naturalOrder());
		int middle = totals.size() / 2;
		return totals.size() % 2 == 0
				? 0.5 * (totals.get(middle - 1).doubleValue() + totals.get(middle).doubleValue())
				: totals.get(middle).doubleValue();
	}

	private static double totalQuotation(FormalTaskSet taskSet) {
		double total = 0.0;
		for (int job = 1; job <= taskSet.size(); job++) {
			FormalTaskSet.Job source = taskSet.jobs().get(job - 1);
			total += source.processing() * Math.max(source.earlyWeight(), source.tardyWeight());
		}
		return total;
	}

	private static void writeIndex(Path outputRoot, List<Overlay> overlays, double referenceTotal,
			double firstBreakpoint, double secondBreakpoint) throws IOException {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("taskSetId\trate\tdiscountStrength\tbreakpoint1\tbreakpoint2\tquotationTotal\toverlay");
		for (Overlay overlay : overlays) {
			lines.add(String.format(Locale.ROOT, "%s\t%.6f\t%.6f\t%.9f\t%.9f\t%.9f\t%s",
					overlay.taskSetId, overlay.rate, overlay.discountStrength, overlay.breakpoint1,
					overlay.breakpoint2, overlay.quotationTotal,
					portable(outputRoot.toAbsolutePath().normalize()
							.relativize(overlay.path.toAbsolutePath().normalize()))));
		}
		Files.write(outputRoot.resolve("outsourcing-data.tsv"), lines, StandardCharsets.UTF_8);
		Files.write(outputRoot.resolve("outsourcing-design.properties"), List.of(
				"quotation=p*max(wE,wT)",
				"breakpointReferenceN=50",
				"breakpointReferenceTotal=" + compact(referenceTotal),
				"breakpoint1=" + compact(firstBreakpoint),
				"breakpoint2=" + compact(secondBreakpoint),
				"defaultMarginalRateFactors=1,0.85,0.70"), StandardCharsets.UTF_8);
	}

	private static String portable(Path path) {
		return path.normalize().toString().replace('\\', '/');
	}

	private static String compact(double value) {
		return String.format(Locale.ROOT, "%.9f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	private record Segment(double start, double end, double slope, double intercept) {
	}

	public record Overlay(String taskSetId, double rate, double discountStrength,
			double breakpoint1, double breakpoint2, double quotationTotal, Path path) {
	}

	public record Result(List<Overlay> overlays, double referenceTotal,
			double breakpoint1, double breakpoint2) {
		public Map<String, Overlay> byKey() {
			LinkedHashMap<String, Overlay> result = new LinkedHashMap<String, Overlay>();
			for (Overlay overlay : overlays) {
				result.put(key(overlay.taskSetId, overlay.rate, overlay.discountStrength), overlay);
			}
			return result;
		}

		public Overlay require(String taskSetId, double rate, double discountStrength) {
			for (Overlay overlay : overlays) {
				if (overlay.taskSetId.equals(taskSetId)
						&& Double.compare(overlay.rate, rate) == 0
						&& Double.compare(overlay.discountStrength, discountStrength) == 0) {
					return overlay;
				}
			}
			throw new IllegalArgumentException("Missing outsourcing overlay: "
					+ taskSetId + " rate=" + rate + " discount=" + discountStrength);
		}

		private static String key(String taskSetId, double rate, double discountStrength) {
			return taskSetId + "/" + Double.toString(rate) + "/" + Double.toString(discountStrength);
		}
	}
}
