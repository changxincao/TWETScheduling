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

import Common.formal.FormalExperimentDataGenerator.GeneratedInstance;

/** 为每个外包场景写出包含调度数据和经济数据的完整实例文件。 */
public final class FormalOutsourcingDataGenerator {

	public Result generate(Path outputRoot, List<FormalTaskSet> taskSets,
			List<GeneratedInstance> schedulingInstances) throws IOException {
		double referenceTotal = referenceTotal(taskSets);
		double firstBreakpoint = 0.25 * referenceTotal;
		double secondBreakpoint = 0.50 * referenceTotal;
		Map<String, FormalTaskSet> taskSetById = new LinkedHashMap<String, FormalTaskSet>();
		for (FormalTaskSet taskSet : taskSets) {
			taskSetById.put(taskSet.id(), taskSet);
		}

		ArrayList<CompleteInstance> completeInstances = new ArrayList<CompleteInstance>();
		for (GeneratedInstance source : schedulingInstances) {
			if (!"base".equals(source.scaleLevel())) {
				continue;
			}
			FormalTaskSet taskSet = taskSetById.get(source.taskSetId());
			if (taskSet == null) {
				throw new IllegalStateException("Missing task set " + source.taskSetId());
			}
			for (double rate : FormalExperimentDesign.OUTSOURCING_RATES) {
				completeInstances.add(write(outputRoot, source, taskSet, rate,
						FormalExperimentDesign.DEFAULT_DISCOUNT_STRENGTH,
						firstBreakpoint, secondBreakpoint, "default-discount"));
			}
			if (source.size() == 50) {
				completeInstances.add(write(outputRoot, source, taskSet, 1.0, 0.0,
						firstBreakpoint, secondBreakpoint, "no-discount"));
			}
		}
		writeIndex(outputRoot, completeInstances, referenceTotal, firstBreakpoint, secondBreakpoint);
		return new Result(List.copyOf(completeInstances), referenceTotal, firstBreakpoint, secondBreakpoint);
	}

	private static CompleteInstance write(Path outputRoot, GeneratedInstance source, FormalTaskSet taskSet,
			double rate, double discountStrength, double firstBreakpoint, double secondBreakpoint,
			String experimentType) throws IOException {
		Path schedulingRoot = outputRoot.resolve("data").toAbsolutePath().normalize();
		Path sourcePath = source.path().toAbsolutePath().normalize();
		if (!sourcePath.startsWith(schedulingRoot)) {
			throw new IllegalArgumentException("Scheduling instance is outside generated data: " + source.path());
		}
		Path relative = schedulingRoot.relativize(sourcePath);
		Path output = outputRoot.resolve("outsourcing-data").resolve(experimentType)
				.resolve("rate-" + compact(rate)).resolve(relative);
		Files.createDirectories(output.getParent());

		ArrayList<String> lines = new ArrayList<String>(Files.readAllLines(source.path(), StandardCharsets.UTF_8));
		double[] quotations = quotations(taskSet);
		double total = appendEconomics(lines, quotations, rate, discountStrength,
				firstBreakpoint, secondBreakpoint);
		Files.write(output, lines, StandardCharsets.UTF_8);
		return new CompleteInstance(source.taskSetId(), source.size(), source.machines(), source.setupType(),
				source.windowLevel(), rate, discountStrength, firstBreakpoint, secondBreakpoint,
				total, experimentType, source.path(), output);
	}

	private static double appendEconomics(List<String> lines, double[] quotations, double rate,
			double discountStrength, double firstBreakpoint, double secondBreakpoint) {
		lines.add("OUTSOURCING_COST");
		StringBuilder quotationLine = new StringBuilder();
		double total = 0.0;
		for (int job = 1; job < quotations.length; job++) {
			if (job > 1) {
				quotationLine.append(' ');
			}
			quotationLine.append(compact(quotations[job]));
			total += quotations[job];
		}
		lines.add(quotationLine.toString());
		lines.add("OUTSOURCING_TARIFF");
		List<Segment> segments = segments(rate, discountStrength, firstBreakpoint, secondBreakpoint,
				Math.max(1.0, total + 1.0));
		lines.add(Integer.toString(segments.size()));
		for (Segment segment : segments) {
			lines.add(String.format(Locale.ROOT, "%.9f %.9f %.9f %.9f",
					segment.start, segment.end, segment.slope, segment.intercept));
		}
		return total;
	}

	static List<Segment> segments(double rate, double discountStrength,
			double firstBreakpoint, double secondBreakpoint, double domainEnd) {
		if (discountStrength == 0.0) {
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
		for (FormalTaskSet.Job source : taskSet.jobs()) {
			total += source.processing() * Math.max(source.earlyWeight(), source.tardyWeight());
		}
		return total;
	}

	private static void writeIndex(Path outputRoot, List<CompleteInstance> instances,
			double referenceTotal, double firstBreakpoint, double secondBreakpoint) throws IOException {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("taskSetId\tsize\tmachines\tsetupType\twindowLevel\trate\tdiscountStrength\t"
				+ "breakpoint1\tbreakpoint2\tquotationTotal\texperimentType\tsourceInstance\tinstance");
		for (CompleteInstance instance : instances) {
			lines.add(String.format(Locale.ROOT,
					"%s\t%d\t%d\t%s\t%s\t%.6f\t%.6f\t%.9f\t%.9f\t%.9f\t%s\t%s\t%s",
					instance.taskSetId, instance.size, instance.machines, instance.setupType,
					instance.windowLevel, instance.rate, instance.discountStrength,
					instance.breakpoint1, instance.breakpoint2, instance.quotationTotal,
					instance.experimentType, portable(outputRoot, instance.sourceInstance),
					portable(outputRoot, instance.path)));
		}
		Files.write(outputRoot.resolve("outsourcing-instances.tsv"), lines, StandardCharsets.UTF_8);
		Files.deleteIfExists(outputRoot.resolve("outsourcing-data.tsv"));
		long defaultCount = instances.stream().filter(item -> item.discountStrength > 0.0).count();
		long noDiscountCount = instances.size() - defaultCount;
		Files.write(outputRoot.resolve("outsourcing-design.properties"), List.of(
				"quotation=p*max(wE,wT)",
				"breakpointReferenceN=50",
				"breakpointReferenceTotal=" + compact(referenceTotal),
				"breakpoint1=" + compact(firstBreakpoint),
				"breakpoint2=" + compact(secondBreakpoint),
				"defaultDiscountMarginalRates=1,0.85,0.70",
				"defaultDiscountFileCount=" + defaultCount,
				"noDiscountFileCount=" + noDiscountCount), StandardCharsets.UTF_8);
	}

	private static String portable(Path root, Path path) {
		return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
				.toString().replace('\\', '/');
	}

	private static String compact(double value) {
		return String.format(Locale.ROOT, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	public record CompleteInstance(String taskSetId, int size, int machines, String setupType,
			String windowLevel, double rate, double discountStrength, double breakpoint1,
			double breakpoint2, double quotationTotal, String experimentType,
			Path sourceInstance, Path path) {
	}

	public record Segment(double start, double end, double slope, double intercept) {
	}

	public record Result(List<CompleteInstance> instances, double referenceTotal,
			double breakpoint1, double breakpoint2) {
		public CompleteInstance require(Path sourceInstance, double rate, double discountStrength) {
			Path normalized = sourceInstance.toAbsolutePath().normalize();
			for (CompleteInstance instance : instances) {
				if (instance.sourceInstance.toAbsolutePath().normalize().equals(normalized)
						&& Double.compare(instance.rate, rate) == 0
						&& Double.compare(instance.discountStrength, discountStrength) == 0) {
					return instance;
				}
			}
			throw new IllegalArgumentException("Missing complete outsourcing instance: "
					+ sourceInstance + " rate=" + rate + " discount=" + discountStrength);
		}
	}
}
