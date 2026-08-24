package Common.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Common.formal.FormalExperimentDataGenerator.GeneratedInstance;
import Common.formal.FormalOutsourcingDataGenerator.CompleteInstance;

/**
 * 生成论文实验数据引用和三份正式求解 manifest。
 * <p>
 * 实验三只复用已有结果做后处理，因此不生成独立求解任务；compact CPLEX 当前也不进入批次。
 */
public final class FormalExperimentSuiteGenerator {
	private static final String WORKSPACE_TOKEN = "${WORKSPACE}";
	private static final Path WORKSPACE_ROOT = Path.of("").toAbsolutePath().normalize();
	private static final double DEFAULT_DISCOUNT_STRENGTH = FormalExperimentDesign.DEFAULT_DISCOUNT_STRENGTH;
	private static final double[] OUTSOURCING_RATES = FormalExperimentDesign.OUTSOURCING_RATES;
	private static final String[] BPC_ALGORITHMS = new String[] {
			"NG_DSSR", "TIME_INDEXED", "TIME_INDEXED_SRI" };
	private static final String[] OUTSOURCING_MODELS = new String[] { "columns", "masterVariables" };

	private FormalExperimentSuiteGenerator() {
	}

	public static void main(String[] args) throws Exception {
		Options options = Options.parse(args);
		Files.createDirectories(options.outputRoot);
		FormalExperimentDataGenerator.GenerationResult generated =
				new FormalExperimentDataGenerator(options.dataRoot, options.casesPerSize)
						.generate(options.outputRoot.resolve("instances"), options.sizes);
		FormalSetupAuditRunner.audit(options.outputRoot);
		FormalTimeScaleAuditRunner.audit(options.outputRoot);
		FormalOutsourcingAuditRunner.audit(options.outputRoot);
		List<InstanceRef> instances = generated.instances().stream().map(InstanceRef::new).toList();
		writeInstanceManifest(options, instances);
		writeSolveManifest(options, instances, generated.outsourcing());
		writeReadme(options, instances);
		System.out.printf(Locale.US, "Formal suite generated: instances=%d output=%s%n",
				instances.size(), options.outputRoot.toAbsolutePath());
	}

	private static void writeInstanceManifest(Options options, List<InstanceRef> instances) throws IOException {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("taskSetId\tsize\tcaseIndex\tmachines\tsetupType\tscaleLevel\tnominalScale\t"
				+ "windowLevel\twindowMinimum\twindowMaximum\twindowFingerprint\tinstance\taverageProcessing");
		for (InstanceRef instance : instances) {
			lines.add(String.format(Locale.US,
					"%s\t%d\t%d\t%d\t%s\t%s\t%d\t%s\t%d\t%d\t%s\t%s\t%.6f",
					instance.taskSetId, instance.size, instance.caseIndex, instance.machines,
					instance.setupType, instance.scaleLevel, instance.nominalScale, instance.windowLevel,
					instance.windowMinimum, instance.windowMaximum, instance.windowFingerprint,
					portable(instance.path),
					instance.averageProcessing));
		}
		Files.write(options.outputRoot.resolve("instances.tsv"), lines, StandardCharsets.UTF_8);
	}

	private static void writeSolveManifest(Options options, List<InstanceRef> instances,
			FormalOutsourcingDataGenerator.Result outsourcing) throws IOException {
		ArrayList<RunRow> seedRows = new ArrayList<RunRow>();
		ArrayList<RunRow> solveRows = new ArrayList<RunRow>();
		LinkedHashMap<String, Path> seedFiles = new LinkedHashMap<String, Path>();
		Files.write(options.outputRoot.resolve("experiment.properties"), List.of(
				"outsourcingQuotation=p*max(wE,wT)",
				"outsourcingBreakpointReferenceTotal=" + compact(outsourcing.referenceTotal()),
				"outsourcingBreakpoint1=" + compact(outsourcing.breakpoint1()),
				"outsourcingBreakpoint2=" + compact(outsourcing.breakpoint2()),
				"outsourcingRates=0.5,1,2",
				"discountMarginalRates=1,0.85,0.70",
				"setupCostCoefficient=" + compact(FormalExperimentDesign.SETUP_COST_COEFFICIENT),
				"solveTimeLimitSeconds=" + compact(options.timeLimitSeconds),
				"maxNodes=" + options.maxNodes,
				"discountModel=" + options.discountModel), StandardCharsets.UTF_8);
		for (InstanceRef instance : instances) {
			String scenario = scenarioId(instance);
			Path seedFile = options.outputRoot.resolve("seeds").resolve(scenario + ".seed");
			addSeedRow(seedRows, seedFiles, options, scenario, instance.path, seedFile);
			for (String algorithm : BPC_ALGORITHMS) {
				String runId = "pricing-" + scenario + "-" + algorithm.toLowerCase(Locale.ROOT);
				solveRows.add(solveRow(options, runId, instance.path, algorithm, seedFile,
						"", "pricing-comparison"));
			}
		}

		// 外包性能使用原时间尺度，完整交叉窗口、价格和两种 BPC formulation。
		for (InstanceRef instance : instances) {
			if (!instance.scaleLevel.equals("base")) {
				continue;
			}
			for (double rate : OUTSOURCING_RATES) {
				CompleteInstance complete = outsourcing.require(instance.path, rate, DEFAULT_DISCOUNT_STRENGTH);
				String scenario = scenarioId(instance) + "-or" + compact(rate) + "-ddefault";
				Path seedFile = options.outputRoot.resolve("seeds").resolve(scenario + ".seed");
				addSeedRow(seedRows, seedFiles, options, scenario, complete.path(), seedFile);
				for (String model : OUTSOURCING_MODELS) {
					String runId = "outsourcing-performance-" + scenario + "-" + model;
					solveRows.add(solveRow(options, runId, complete.path(), "NG_DSSR",
							seedFile, model, "outsourcing-performance"));
				}
			}
		}

		// 实验三只做结果后处理；实验四仅补 n=50、中价、无折扣的缺失求解。
		for (InstanceRef instance : instances) {
			if (instance.size != 50 || !instance.scaleLevel.equals("base")) {
				continue;
			}
			CompleteInstance complete = outsourcing.require(instance.path, 1.0, 0.0);
			String scenario = scenarioId(instance) + "-or1-dnone";
			Path seedFile = options.outputRoot.resolve("seeds").resolve(scenario + ".seed");
			addSeedRow(seedRows, seedFiles, options, scenario, complete.path(), seedFile);
			String runId = "outsourcing-discount-" + scenario + "-" + options.discountModel;
			solveRows.add(solveRow(options, runId, complete.path(), "NG_DSSR", seedFile,
					options.discountModel, "outsourcing-discount"));
		}

		writeManifest(options.outputRoot.resolve("manifest.tsv"), seedRows, solveRows);
		Path manifestDir = options.outputRoot.resolve("manifests");
		Files.createDirectories(manifestDir);
		// 2026-08-23: formulation 与 price 已合并，避免重用输出目录时留下第四、第五份旧清单。
		Files.deleteIfExists(manifestDir.resolve("outsourcing-formulation.tsv"));
		Files.deleteIfExists(manifestDir.resolve("outsourcing-price.tsv"));
		LinkedHashMap<String, RunRow> seedById = new LinkedHashMap<String, RunRow>();
		for (RunRow seedRow : seedRows) {
			seedById.put(seedRow.runId, seedRow);
		}
		LinkedHashMap<String, ArrayList<RunRow>> solvesByBlock = new LinkedHashMap<String, ArrayList<RunRow>>();
		for (RunRow solveRow : solveRows) {
			solvesByBlock.computeIfAbsent(solveRow.block, ignored -> new ArrayList<RunRow>()).add(solveRow);
		}
		for (String block : new String[] {
				"pricing-comparison", "outsourcing-performance", "outsourcing-discount" }) {
			ArrayList<RunRow> blockRows = solvesByBlock.getOrDefault(block, new ArrayList<RunRow>());
			LinkedHashMap<String, RunRow> requiredSeeds = new LinkedHashMap<String, RunRow>();
			for (RunRow solveRow : blockRows) {
				RunRow seedRow = seedById.get(solveRow.dependsOn);
				if (seedRow == null) {
					throw new IllegalStateException("Missing seed row for " + solveRow.runId + ": " + solveRow.dependsOn);
				}
				requiredSeeds.put(seedRow.runId, seedRow);
			}
			writeManifest(manifestDir.resolve(block + ".tsv"),
					new ArrayList<RunRow>(requiredSeeds.values()), blockRows);
		}
	}

	private static void writeManifest(Path path, List<RunRow> seedRows, List<RunRow> solveRows) throws IOException {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("runId\tmainClass\targs\toutputDir\tdependsOn\tblock");
		LinkedHashMap<String, ArrayList<RunRow>> solvesBySeed = new LinkedHashMap<String, ArrayList<RunRow>>();
		ArrayList<RunRow> independentRows = new ArrayList<RunRow>();
		for (RunRow row : solveRows) {
			if (row.dependsOn.isEmpty()) {
				independentRows.add(row);
			} else {
				solvesBySeed.computeIfAbsent(row.dependsOn, ignored -> new ArrayList<RunRow>()).add(row);
			}
		}
		for (RunRow row : seedRows) {
			lines.add(row.toTsv());
			List<RunRow> dependents = solvesBySeed.remove(row.runId);
			if (dependents != null) {
				for (RunRow dependent : dependents) {
					lines.add(dependent.toTsv());
				}
			}
		}
		for (RunRow row : independentRows) {
			lines.add(row.toTsv());
		}
		if (!solvesBySeed.isEmpty()) {
			throw new IllegalStateException("Manifest contains solve rows without seed rows: " + solvesBySeed.keySet());
		}
		Files.write(path, lines, StandardCharsets.UTF_8);
	}

	private static void addSeedRow(List<RunRow> rows, Map<String, Path> seedFiles, Options options, String scenario,
			Path instance, Path seedFile) {
		if (seedFiles.putIfAbsent(scenario, seedFile) != null) {
			return;
		}
		String runId = "seed-" + scenario;
		Path output = options.outputRoot.resolve("runs").resolve(runId);
		Map<String, String> values = mapOf(
				"action", "seed", "runId", runId, "instance", portable(instance), "seedFile", portable(seedFile),
				"outputDir", portable(output));
		String args = arguments(values);
		rows.add(new RunRow(runId, args, portable(output), "", "seed"));
	}

	private static RunRow solveRow(Options options, String runId, Path instance,
			String algorithm, Path seedFile, String outsourcingModel, String block) {
		Path output = options.outputRoot.resolve("runs").resolve(block).resolve(runId);
		Map<String, String> values = mapOf(
				"action", "solve", "runId", runId, "instance", portable(instance), "algorithm", algorithm,
				"outputDir", portable(output), "seedFile", portable(seedFile),
				"timeLimitSeconds", compact(options.timeLimitSeconds),
				"maxNodes", Integer.toString(options.maxNodes));
		putIfNotEmpty(values, "outsourcingModel", outsourcingModel);
		String args = arguments(values);
		return new RunRow(runId, args, portable(output), "seed-" + stripExtension(seedFile.getFileName().toString()),
				block);
	}

	private static void writeReadme(Options options, List<InstanceRef> instances) throws IOException {
		int mainInstanceCount = instances.size();
		int originalScaleInstanceCount = 0;
		int n50OriginalScaleInstanceCount = 0;
		for (InstanceRef instance : instances) {
			if (instance.scaleLevel.equals("base")) {
				originalScaleInstanceCount++;
				if (instance.size == 50) {
					n50OriginalScaleInstanceCount++;
				}
			}
		}
		int pricingSeedCount = mainInstanceCount;
		int outsourcingSeedCount = originalScaleInstanceCount * OUTSOURCING_RATES.length;
		int discountSeedCount = n50OriginalScaleInstanceCount;
		int seedTaskCount = pricingSeedCount + outsourcingSeedCount + discountSeedCount;
		int pricingTaskCount = pricingSeedCount * BPC_ALGORITHMS.length;
		int outsourcingPerformanceTaskCount = outsourcingSeedCount * OUTSOURCING_MODELS.length;
		int outsourcingDiscountTaskCount = discountSeedCount;
		int solveTaskCount = pricingTaskCount + outsourcingPerformanceTaskCount + outsourcingDiscountTaskCount;
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("# 正式计算实验任务包");
		lines.add("");
		lines.add("三种定价算法统一使用运行时 `BestBpcProfiles.VERSION` 对应的参数；每个场景先生成一次固定初始列，待比较方法复用同一快照和 SHA-256 fingerprint。");
		lines.add("");
		lines.add("执行：`java HEU.ExperimentBatchScheduler manifest.tsv 4`。每个子 JVM 固定 CPLEX 单线程，调度器始终最多保持 4 个独立进程。");
		lines.add("也可以只执行 `manifests/` 下与论文实验小节对应的单独 manifest；每个子 manifest 已包含自己依赖的 seed 任务。");
		lines.add("");
		lines.add("`pricing-comparison` 使用逐任务落盘的 zero/narrow/wide 窗口和 base/medium/high 三个尺度比较三种 BPC。medium/high 的 processing 与 due-center 倍率独立抽取，setup 按实际 processing workload 比例整体缩放。`outsourcing-performance` 使用包含完整调度与经济数据的单文件，在原时间尺度比较三档报价和 columns/masterVariables；`outsourcing-discount` 只补 n=50、中价、无折扣的完整文件。runner 不构造任何物理或经济数据。");
		lines.add("");
		lines.add("已准备落盘实例记录数：" + instances.size() + "；每个规模固定取 "
				+ options.casesPerSize + " 个任务集合，并生成 random/family 与 base/medium/high 尺度。完整抽样、倍率、逐任务窗口、setup 和外包审计见 `instances/` 下的 metadata 与三个 `post-generation-*-audit.tsv`。");
		lines.add("");
		lines.add("总 manifest 包含 " + seedTaskCount + " 个共享 seed 任务和 " + solveTaskCount
				+ " 个求解任务。其中 pricing comparison=" + pricingTaskCount
				+ "，outsourcing performance=" + outsourcingPerformanceTaskCount
				+ "，outsourcing discount=" + outsourcingDiscountTaskCount
				+ "。这些是场景/方法任务数，不是不同原始数据实例数。");
		lines.add("");
		lines.add("外包报价为 q_j=p_j*max(wE_j,wT_j)；Q1/Q2 从 n=50 不重复任务集合的报价总量中位数按 25%/50% 一次确定，并写入每条外包任务参数。");
		Files.write(options.outputRoot.resolve("README.md"), lines, StandardCharsets.UTF_8);
	}

	private static String scenarioId(InstanceRef instance) {
		return instance.taskSetId + "-m" + instance.machines + "-" + instance.setupType
				+ "-" + instance.scaleLevel + "-n" + instance.nominalScale
				+ "-w" + instance.windowLevel;
	}

	private static String arguments(Map<String, String> values) {
		ArrayList<String> parts = new ArrayList<String>();
		for (Map.Entry<String, String> entry : values.entrySet()) {
			parts.add("--" + entry.getKey() + "=" + quote(entry.getValue()));
		}
		return String.join(" ", parts);
	}

	private static Map<String, String> mapOf(String... values) {
		LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
		for (int index = 0; index < values.length; index += 2) {
			result.put(values[index], values[index + 1]);
		}
		return result;
	}

	private static void putIfNotEmpty(Map<String, String> values, String key, String value) {
		if (value != null && !value.isEmpty()) {
			values.put(key, value);
		}
	}

	private static String quote(String value) {
		return "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\"";
	}

	private static String portable(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		if (absolute.startsWith(WORKSPACE_ROOT)) {
			String relative = WORKSPACE_ROOT.relativize(absolute).toString().replace('\\', '/');
			return relative.isEmpty() ? WORKSPACE_TOKEN : WORKSPACE_TOKEN + "/" + relative;
		}
		return absolute.toString().replace('\\', '/');
	}

	private static String compact(double value) {
		return String.format(Locale.ROOT, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(0, dot);
	}

	private static final class InstanceRef {
		private final String taskSetId;
		private final int size;
		private final int caseIndex;
		private final int machines;
		private final String setupType;
		private final String scaleLevel;
		private final int nominalScale;
		private final String windowLevel;
		private final int windowMinimum;
		private final int windowMaximum;
		private final String windowFingerprint;
		private final Path path;
		private final double averageProcessing;

		private InstanceRef(GeneratedInstance generated) {
			taskSetId = generated.taskSetId();
			size = generated.size();
			caseIndex = generated.caseIndex();
			machines = generated.machines();
			setupType = generated.setupType();
			scaleLevel = generated.scaleLevel();
			nominalScale = generated.nominalScale();
			windowLevel = generated.windowLevel();
			windowMinimum = generated.windowMinimum();
			windowMaximum = generated.windowMaximum();
			windowFingerprint = generated.windowFingerprint();
			path = generated.path();
			averageProcessing = generated.averageProcessing();
		}
	}

	private static final class RunRow {
		private final String runId;
		private final String args;
		private final String outputDir;
		private final String dependsOn;
		private final String block;

		private RunRow(String runId, String args, String outputDir, String dependsOn, String block) {
			this.runId = runId;
			this.args = args;
			this.outputDir = outputDir;
			this.dependsOn = dependsOn;
			this.block = block;
		}

		private String toTsv() {
			return runId + "\tCommon.formal.FormalExperimentRunner\t" + args + "\t" + outputDir + "\t" + dependsOn
					+ "\t" + block;
		}
	}

	private static final class Options {
		private Path dataRoot = Path.of("data");
		private Path outputRoot = Path.of("experiment-suite", "formal");
		private int casesPerSize = FormalExperimentDesign.CASES_PER_SIZE;
		private int[] sizes = FormalExperimentDesign.TASK_SIZES.clone();
		private double timeLimitSeconds = 10800.0;
		private int maxNodes = 100000;
		private String discountModel = "masterVariables";

		private static Options parse(String[] args) {
			Options options = new Options();
			for (String argument : args) {
				String token = argument.startsWith("--") ? argument.substring(2) : argument;
				int equals = token.indexOf('=');
				if (equals <= 0) {
					throw new IllegalArgumentException("Expected --key=value argument: " + argument);
				}
				String key = token.substring(0, equals);
				String value = token.substring(equals + 1);
				if ("dataRoot".equals(key)) {
					options.dataRoot = Path.of(value);
				} else if ("outputRoot".equals(key)) {
					options.outputRoot = Path.of(value);
				} else if ("casesPerSize".equals(key)) {
					options.casesPerSize = Integer.parseInt(value);
				} else if ("sizes".equals(key)) {
					String[] tokens = value.split(",");
					options.sizes = new int[tokens.length];
					for (int index = 0; index < tokens.length; index++) {
						options.sizes[index] = Integer.parseInt(tokens[index].trim());
					}
				} else if ("timeLimitSeconds".equals(key)) {
					options.timeLimitSeconds = Double.parseDouble(value);
				} else if ("maxNodes".equals(key)) {
					options.maxNodes = Integer.parseInt(value);
				} else if ("discountModel".equals(key)) {
					options.discountModel = value;
				} else {
					throw new IllegalArgumentException("Unknown option: " + key);
				}
			}
			if (options.casesPerSize <= 0) {
				throw new IllegalArgumentException("casesPerSize must be positive");
			}
			if (!"columns".equalsIgnoreCase(options.discountModel)
					&& !"masterVariables".equalsIgnoreCase(options.discountModel)) {
				throw new IllegalArgumentException("discountModel must be columns or masterVariables");
			}
			return options;
		}
	}
}
