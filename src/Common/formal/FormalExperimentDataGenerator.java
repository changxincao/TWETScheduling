package Common.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Common.formal.FormalSetupGenerator.Result;
import Common.formal.FormalSetupValidator.Report;
import Common.formal.FormalTimeScaleGenerator.Scale;
import Common.formal.FormalTimeScaleGenerator.ScaledData;

/** 组装各个独立生成组件，并输出正式实例和完整审计metadata。 */
public final class FormalExperimentDataGenerator {
	private final int casesPerSize;
	private final FormalTaskSetGenerator taskSetGenerator;
	private final FormalDueWindowGenerator dueWindowGenerator = new FormalDueWindowGenerator();
	private final FormalSetupValidator setupValidator = new FormalSetupValidator();
	private final FormalSetupGenerator setupGenerator = new FormalSetupGenerator(setupValidator);
	private final FormalTimeScaleGenerator timeScaleGenerator = new FormalTimeScaleGenerator();
	private final FormalInstanceWriter instanceWriter = new FormalInstanceWriter();

	public FormalExperimentDataGenerator(Path tanakaDataRoot, int casesPerSize) {
		this.casesPerSize = casesPerSize;
		taskSetGenerator = new FormalTaskSetGenerator(tanakaDataRoot, casesPerSize);
	}

	public GenerationResult generate(Path outputRoot, int[] sizes) throws IOException {
		Files.createDirectories(outputRoot);
		List<FormalTaskSet> taskSets = taskSetGenerator.generate(sizes);
		Map<String, List<Scale>> scalesByTaskSet = timeScaleGenerator.generate(taskSets);
		ArrayList<GeneratedInstance> instances = new ArrayList<GeneratedInstance>();
		ArrayList<String> taskMetadata = taskMetadataHeader();
		ArrayList<String> scaleMetadata = scaleMetadataHeader();
		ArrayList<String> setupMetadata = setupMetadataHeader();

		for (FormalTaskSet taskSet : taskSets) {
			int[] centers = dueWindowGenerator.generateCenters(taskSet);
			taskMetadata.add(taskMetadataRow(taskSet, centers));
			List<Scale> scales = scalesByTaskSet.get(taskSet.id());
			for (Scale scale : scales) {
				scaleMetadata.add(taskSet.id() + "\t" + scale.level() + "\t" + scale.multiplier()
						+ "\t" + scale.selectionSeed());
			}
			for (Result setupResult : setupGenerator.generatePair(taskSet)) {
				for (Scale scale : scales) {
					ScaledData scaled = timeScaleGenerator.scale(taskSet, centers, setupResult.setup(), scale);
					int scaledCap = Math.multiplyExact(setupResult.cap(), scale.multiplier());
					Report audit = setupValidator.audit(scaled.setup(),
							setupResult.targetMean() * scale.multiplier(), scaledCap,
							setupResult.familyByJob());
					setupValidator.requirePreset(audit, setupResult.type() == FormalSetupGenerator.Type.FAMILY);
					setupMetadata.add(setupMetadataRow(taskSet, setupResult, scale, audit));
					for (int machines : FormalExperimentDesign.machines(taskSet.size())) {
						Path path = instanceWriter.write(outputRoot, taskSet, setupResult.type(), scale,
								machines, scaled);
						instances.add(new GeneratedInstance(taskSet.id(), taskSet.size(), taskSet.caseIndex(),
								machines, setupResult.type().id(), scale.level(), scale.multiplier(), path,
								taskSet.sourceFile(), average(scaled.processing(), taskSet.size())));
					}
				}
			}
		}

		writeInstanceIndex(outputRoot, instances);
		Files.write(outputRoot.resolve("task-selection.tsv"), taskMetadata, StandardCharsets.UTF_8);
		Files.write(outputRoot.resolve("scale-selection.tsv"), scaleMetadata, StandardCharsets.UTF_8);
		Files.write(outputRoot.resolve("setup-audit.tsv"), setupMetadata, StandardCharsets.UTF_8);
		writeDesignProperties(outputRoot, sizes, casesPerSize);
		return new GenerationResult(List.copyOf(taskSets), List.copyOf(instances));
	}

	private static ArrayList<String> taskMetadataHeader() {
		return new ArrayList<String>(List.of(
				"taskSetId\tsize\tcaseIndex\tparentSize\tsourceFile\tsourceSelectionSeed\t"
				+ "taskPermutationSeed\tparentWorkload\tselectedWorkload\tworkloadRatio\treferenceMachines\t"
				+ "sourceJobIndices\tcenters"));
	}

	private static String taskMetadataRow(FormalTaskSet taskSet, int[] centers) {
		String sourceIndices = taskSet.jobs().stream()
				.map(job -> Integer.toString(job.sourceIndex())).reduce((a, b) -> a + "," + b).orElse("");
		StringBuilder centerValues = new StringBuilder();
		for (int job = 1; job <= taskSet.size(); job++) {
			if (job > 1) {
				centerValues.append(',');
			}
			centerValues.append(centers[job]);
		}
		return String.format(Locale.ROOT, "%s\t%d\t%d\t%d\t%s\t%d\t%d\t%.6f\t%.6f\t%.9f\t%d\t%s\t%s",
				taskSet.id(), taskSet.size(), taskSet.caseIndex(), taskSet.parentSize(),
				portable(taskSet.sourceFile()), taskSet.sourceSelectionSeed(), taskSet.taskPermutationSeed(),
				taskSet.parentWorkload(), taskSet.workload(), taskSet.workload() / taskSet.parentWorkload(),
				FormalExperimentDesign.referenceMachines(taskSet.size()), sourceIndices, centerValues);
	}

	private static ArrayList<String> scaleMetadataHeader() {
		return new ArrayList<String>(List.of("taskSetId\tscaleLevel\tmultiplier\tselectionSeed"));
	}

	private static ArrayList<String> setupMetadataHeader() {
		return new ArrayList<String>(List.of(
				"taskSetId\tsetupType\tscaleLevel\tmultiplier\ttargetMean\tactualMean\tmeanError\tcap\tmaximum\t"
				+ "capHitRate\ttriangleViolations\tfloydChangedArcs\twithinMean\tbetweenMean\trawMean\t"
				+ "closedMean\tfirstFloydChangedArcs\tcalibrationScale\twithinPairRatio\trandomLocation\t"
				+ "withinLocation\tbetweenLocation\tfamilyByJob"));
	}

	private static String setupMetadataRow(FormalTaskSet taskSet, Result setup, Scale scale, Report audit) {
		String familyByJob = familyAssignment(setup.familyByJob());
		return String.format(Locale.ROOT,
				"%s\t%s\t%s\t%d\t%.9f\t%.9f\t%.9f\t%d\t%d\t%.9f\t%d\t%d\t%.9f\t%.9f\t"
				+ "%.9f\t%.9f\t%d\t%.12f\t%.9f\t%.9f\t%.9f\t%.9f\t%s",
				taskSet.id(), setup.type().id(), scale.level(), scale.multiplier(), audit.targetMean(),
				audit.actualMean(), audit.meanError(), audit.cap(), audit.maximum(), audit.capHitRate(),
				audit.triangleViolations(), audit.floydChangedArcs(), audit.withinMean(), audit.betweenMean(),
				setup.rawMean(), setup.closedMean(), setup.firstFloydChangedArcs(), setup.calibrationScale(),
				setup.withinPairRatio(), setup.randomLocation(), setup.withinLocation(), setup.betweenLocation(),
				familyByJob);
	}

	private static String familyAssignment(int[] familyByJob) {
		if (familyByJob == null) {
			return "NA";
		}
		StringBuilder result = new StringBuilder();
		for (int job = 1; job < familyByJob.length; job++) {
			if (job > 1) {
				result.append(',');
			}
			result.append(familyByJob[job]);
		}
		return result.toString();
	}

	private static void writeInstanceIndex(Path outputRoot, List<GeneratedInstance> instances) throws IOException {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("taskSetId\tsize\tcaseIndex\tmachines\tsetupType\tscaleLevel\tscale\tinstance\tsourceFile\taverageProcessing");
		for (GeneratedInstance instance : instances) {
			lines.add(String.format(Locale.ROOT, "%s\t%d\t%d\t%d\t%s\t%s\t%d\t%s\t%s\t%.9f",
					instance.taskSetId(), instance.size(), instance.caseIndex(), instance.machines(),
					instance.setupType(), instance.scaleLevel(), instance.scale(),
					portable(outputRoot.toAbsolutePath().normalize()
							.relativize(instance.path().toAbsolutePath().normalize())),
					portable(instance.sourceFile()), instance.averageProcessing()));
		}
		Files.write(outputRoot.resolve("instances.tsv"), lines, StandardCharsets.UTF_8);
	}

	private static String portable(Path path) {
		return path.normalize().toString().replace('\\', '/');
	}

	private static void writeDesignProperties(Path outputRoot, int[] sizes, int casesPerSize) throws IOException {
		LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
		values.put("taskSizes", Arrays.stream(sizes).mapToObj(Integer::toString)
				.reduce((first, second) -> first + "," + second).orElse(""));
		values.put("casesPerSize", Integer.toString(casesPerSize));
		values.put("machines.n20To60", "2,3,4");
		values.put("machines.n80To100", "3,4,5");
		values.put("windowHalfWidths", "0,100,300");
		values.put("setupTypes", "random,family");
		values.put("setupMeanRatio", "0.5");
		values.put("setupCapRatio", "1.0");
		values.put("familyCounts", "20:3,40:3,50:4,60:4,80:5,100:6");
		values.put("familyWithinMeanRatio", "0.4");
		values.put("setupCostCoefficient", "20");
		values.put("mediumScaleRange", "5,15");
		values.put("highScaleOffset", "10");
		ArrayList<String> lines = new ArrayList<String>();
		for (Map.Entry<String, String> entry : values.entrySet()) {
			lines.add(entry.getKey() + "=" + entry.getValue());
		}
		Files.write(outputRoot.resolve("design.properties"), lines, StandardCharsets.UTF_8);
	}

	private static double average(int[] values, int n) {
		long total = 0L;
		for (int index = 1; index <= n; index++) {
			total += values[index];
		}
		return (double) total / n;
	}

	public record GeneratedInstance(String taskSetId, int size, int caseIndex, int machines,
			String setupType, String scaleLevel, int scale, Path path, Path sourceFile,
			double averageProcessing) {
	}

	public record GenerationResult(List<FormalTaskSet> taskSets, List<GeneratedInstance> instances) {
	}
}
