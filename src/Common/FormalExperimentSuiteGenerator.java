package Common;

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

/**
 * 生成论文正式实验所需的多机数据和批处理 manifest。
 * <p>
 * 默认主比较只覆盖 n=40/50/60；n=100 数据同时生成，留作扩展性 pilot，避免默认批次直接爆炸。
 */
public final class FormalExperimentSuiteGenerator {
	private static final String WORKSPACE_TOKEN = "${WORKSPACE}";
	private static final Path WORKSPACE_ROOT = Path.of("").toAbsolutePath().normalize();

	private FormalExperimentSuiteGenerator() {
	}

	public static void main(String[] args) throws Exception {
		Options options = Options.parse(args);
		Files.createDirectories(options.outputRoot);
		List<InstanceRef> instances = prepareInstances(options);
		writeInstanceManifest(options, instances);
		writeSolveManifest(options, instances);
		writeReadme(options, instances);
		System.out.printf(Locale.US, "Formal suite generated: instances=%d output=%s%n",
				instances.size(), options.outputRoot.toAbsolutePath());
	}

	private static List<InstanceRef> prepareInstances(Options options) throws Exception {
		ArrayList<InstanceRef> instances = new ArrayList<InstanceRef>();
		for (int size : options.sizes) {
			Path sourceDir = options.dataRoot.resolve(size + "-1");
			if (size == 60 && !Files.isDirectory(sourceDir)) {
				prepareTruncatedSixty(options.dataRoot, options.casesPerSize);
			}
			if (!Files.isDirectory(sourceDir)) {
				System.err.println("Skip missing single-machine source directory: " + sourceDir);
				continue;
			}
			List<Path> sourceFiles = listDatFiles(sourceDir, options.casesPerSize);
			int[] machines = size <= 60 ? new int[] { 2, 3, 4 } : new int[] { 2, 3, 4, 5 };
			for (Path source : sourceFiles) {
				ArrayList<Integer> missingMachines = new ArrayList<Integer>();
				for (int machine : machines) {
					Path target = convertedPath(options.dataRoot, source, size, machine);
					if (!Files.exists(target)) {
						missingMachines.add(Integer.valueOf(machine));
					}
				}
				if (!missingMachines.isEmpty()) {
					ETConverter.convertFile(source.toString(), toIntArray(missingMachines));
				}
				for (int machine : machines) {
					Path target = convertedPath(options.dataRoot, source, size, machine);
					if (Files.exists(target)) {
						instances.add(new InstanceRef(size, machine, target, averageProcessing(source)));
					}
				}
			}
		}
		instances.sort(Comparator.comparingInt((InstanceRef value) -> value.size)
				.thenComparingInt(value -> value.machines).thenComparing(value -> value.path.toString()));
		return instances;
	}

	private static void prepareTruncatedSixty(Path dataRoot, int limit) throws IOException {
		Path sourceDir = dataRoot.resolve("100-1");
		Path targetDir = dataRoot.resolve("60-1");
		Files.createDirectories(targetDir);
		for (Path source : listDatFiles(sourceDir, limit)) {
			List<String> input = Files.readAllLines(source, StandardCharsets.UTF_8);
			if (input.size() < 61) {
				throw new IllegalArgumentException("Cannot truncate malformed 100-job instance: " + source);
			}
			ArrayList<String> output = new ArrayList<String>(61);
			output.add("60");
			output.addAll(input.subList(1, 61));
			String name = source.getFileName().toString().replaceFirst("100", "060");
			Path target = targetDir.resolve(name);
			if (!Files.exists(target)) {
				Files.write(target, output, StandardCharsets.UTF_8);
			}
		}
	}

	private static void writeInstanceManifest(Options options, List<InstanceRef> instances) throws IOException {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("size\tmachines\tinstance\taverageProcessing");
		for (InstanceRef instance : instances) {
			lines.add(String.format(Locale.US, "%d\t%d\t%s\t%.6f", instance.size, instance.machines,
					portable(instance.path), instance.averageProcessing));
		}
		Files.write(options.outputRoot.resolve("instances.tsv"), lines, StandardCharsets.UTF_8);
	}

	private static void writeSolveManifest(Options options, List<InstanceRef> instances) throws IOException {
		ArrayList<RunRow> seedRows = new ArrayList<RunRow>();
		ArrayList<RunRow> solveRows = new ArrayList<RunRow>();
		LinkedHashMap<String, Path> seedFiles = new LinkedHashMap<String, Path>();
		for (InstanceRef instance : instances) {
			if (instance.size > 60) {
				continue;
			}
			for (double scale : new double[] { 1.0, 5.0, 10.0 }) {
				for (double windowRatio : new double[] { 0.0, 2.0, 6.0 }) {
					double halfWidth = Math.rint(windowRatio * instance.averageProcessing * scale);
					String scenario = scenarioId(instance, scale, halfWidth, 0.0);
					Path seedFile = options.outputRoot.resolve("seeds").resolve(scenario + ".seed");
					addSeedRow(seedRows, seedFiles, options, scenario, instance.path, seedFile, scale, halfWidth, 0.0);
					for (String algorithm : new String[] { "NG_DSSR", "TIME_INDEXED", "TIME_INDEXED_SRI" }) {
						String runId = "pricing-" + scenario + "-" + algorithm.toLowerCase(Locale.ROOT);
						solveRows.add(solveRow(options, runId, instance.path, algorithm, seedFile, scale, halfWidth,
								0.0, "none", 1.0, 0.0, "pricing-comparison"));
					}
				}
			}
		}

		// 外包模型与灵敏度采用原时间尺度和中等窗口，避免和时间尺度实验做全因子乘积。
		for (InstanceRef instance : instances) {
			if (instance.size > 60) {
				continue;
			}
			double halfWidth = Math.rint(2.0 * instance.averageProcessing);
			String scenario = scenarioId(instance, 1.0, halfWidth, 0.0);
			Path seedFile = options.outputRoot.resolve("seeds").resolve(scenario + ".seed");
			addSeedRow(seedRows, seedFiles, options, scenario, instance.path, seedFile, 1.0, halfWidth, 0.0);
			for (String model : new String[] { "masterVariables", "columns" }) {
				String runId = "outsourcing-formulation-" + scenario + "-" + model;
				solveRows.add(solveRow(options, runId, instance.path, "NG_DSSR", seedFile, 1.0, halfWidth,
						0.0, model, 1.0, 0.15, "outsourcing-formulation"));
			}
			for (double rate : new double[] { 0.75, 1.0, 1.25 }) {
				String runId = "outsourcing-price-" + scenario + "-r" + compact(rate);
				solveRows.add(solveRow(options, runId, instance.path, "NG_DSSR", seedFile, 1.0, halfWidth,
						0.0, "masterVariables", rate, 0.15, "outsourcing-price"));
			}
			for (double discount : new double[] { 0.0, 0.15, 0.30 }) {
				String runId = "outsourcing-discount-" + scenario + "-d" + compact(discount);
				solveRows.add(solveRow(options, runId, instance.path, "NG_DSSR", seedFile, 1.0, halfWidth,
						0.0, "masterVariables", 1.0, discount, "outsourcing-discount"));
			}
		}

		ArrayList<String> lines = new ArrayList<String>();
		writeManifest(options.outputRoot.resolve("manifest.tsv"), seedRows, solveRows);
		Path manifestDir = options.outputRoot.resolve("manifests");
		Files.createDirectories(manifestDir);
		LinkedHashMap<String, RunRow> seedById = new LinkedHashMap<String, RunRow>();
		for (RunRow seedRow : seedRows) {
			seedById.put(seedRow.runId, seedRow);
		}
		LinkedHashMap<String, ArrayList<RunRow>> solvesByBlock = new LinkedHashMap<String, ArrayList<RunRow>>();
		for (RunRow solveRow : solveRows) {
			solvesByBlock.computeIfAbsent(solveRow.block, ignored -> new ArrayList<RunRow>()).add(solveRow);
		}
		for (Map.Entry<String, ArrayList<RunRow>> entry : solvesByBlock.entrySet()) {
			LinkedHashMap<String, RunRow> requiredSeeds = new LinkedHashMap<String, RunRow>();
			for (RunRow solveRow : entry.getValue()) {
				RunRow seedRow = seedById.get(solveRow.dependsOn);
				if (seedRow == null) {
					throw new IllegalStateException("Missing seed row for " + solveRow.runId + ": " + solveRow.dependsOn);
				}
				requiredSeeds.put(seedRow.runId, seedRow);
			}
			writeManifest(manifestDir.resolve(entry.getKey() + ".tsv"),
					new ArrayList<RunRow>(requiredSeeds.values()), entry.getValue());
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
			Path instance, Path seedFile, double scale, double halfWidth, double setupCostCoefficient) {
		if (seedFiles.putIfAbsent(scenario, seedFile) != null) {
			return;
		}
		String runId = "seed-" + scenario;
		Path output = options.outputRoot.resolve("runs").resolve(runId);
		String args = arguments(mapOf(
				"action", "seed", "runId", runId, "instance", portable(instance), "seedFile", portable(seedFile),
				"outputDir", portable(output),
				"timeScale", compact(scale), "dueWindowHalfWidth", compact(halfWidth),
				"setupCostCoefficient", compact(setupCostCoefficient)));
		rows.add(new RunRow(runId, args, portable(output), "", "seed"));
	}

	private static RunRow solveRow(Options options, String runId, Path instance, String algorithm, Path seedFile,
			double scale, double halfWidth, double setupCostCoefficient, String outsourcingModel,
			double outsourcingRate, double discount, String block) {
		Path output = options.outputRoot.resolve("runs").resolve(block).resolve(runId);
		String args = arguments(mapOf(
				"action", "solve", "runId", runId, "instance", portable(instance), "algorithm", algorithm,
				"outputDir", portable(output), "seedFile", portable(seedFile), "timeScale", compact(scale),
				"dueWindowHalfWidth", compact(halfWidth), "setupCostCoefficient", compact(setupCostCoefficient),
				"outsourcingModel", outsourcingModel, "outsourcingUnitRate", compact(outsourcingRate),
				"discountStrength", compact(discount), "timeLimitSeconds", compact(options.timeLimitSeconds),
				"maxNodes", Integer.toString(options.maxNodes)));
		return new RunRow(runId, args, portable(output), "seed-" + stripExtension(seedFile.getFileName().toString()),
				block);
	}

	private static void writeReadme(Options options, List<InstanceRef> instances) throws IOException {
		int mainInstanceCount = 0;
		for (InstanceRef instance : instances) {
			if (instance.size <= 60) {
				mainInstanceCount++;
			}
		}
		int seedTaskCount = mainInstanceCount * 9;
		int pricingTaskCount = seedTaskCount * 3;
		int outsourcingFormulationTaskCount = mainInstanceCount * 2;
		int outsourcingPriceTaskCount = mainInstanceCount * 3;
		int outsourcingDiscountTaskCount = mainInstanceCount * 3;
		int solveTaskCount = pricingTaskCount + outsourcingFormulationTaskCount
				+ outsourcingPriceTaskCount + outsourcingDiscountTaskCount;
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("# 正式计算实验 pilot 包");
		lines.add("");
		lines.add("这个目录先验证数据派生、共享起点、任务依赖、结果输出和服务器并发，不代表论文样本已经冻结。三种定价算法统一使用运行时 `BestBpcProfiles.VERSION` 对应的参数；每个场景先生成一次固定初始列，随后三种算法复用同一快照和 SHA-256 fingerprint。");
		lines.add("");
		lines.add("执行：`java HEU.ExperimentBatchScheduler manifest.tsv 4`。每个子 JVM 固定 CPLEX 单线程，调度器始终最多保持 4 个独立进程。");
		lines.add("也可以只执行 `manifests/` 下与论文实验小节对应的单独 manifest；每个子 manifest 已包含自己依赖的 seed 任务。");
		lines.add("");
		lines.add("`pricing-comparison` 比较 n=40/50/60、m=2/3/4、相对窗口 0/2/6 倍平均处理时间以及时间尺度 1/5/10。外包模型和灵敏度不与时间尺度做全因子乘积。");
		lines.add("");
		lines.add("已准备实例记录数：" + instances.size() + "；当前每个规模只取按文件名排序后的前 "
				+ options.casesPerSize + " 个 case。n=100 的 m=2/3/4/5 数据只进入 `instances.tsv`，默认不进入耗时很高的完整精确批次。");
		lines.add("");
		lines.add("总 manifest 包含 " + seedTaskCount + " 个共享 seed 任务和 " + solveTaskCount
				+ " 个求解任务。其中 pricing comparison=" + pricingTaskCount
				+ "，outsourcing formulation=" + outsourcingFormulationTaskCount
				+ "，outsourcing price=" + outsourcingPriceTaskCount
				+ "，outsourcing discount=" + outsourcingDiscountTaskCount + "。这些是场景/算法任务数，不是不同原始数据实例数。");
		lines.add("");
		lines.add("正式批量运行前必须重新确定 `casesPerSize` 和分层抽样规则；当前“前几个文件”只适合 smoke/pilot，不能直接作为论文代表性样本。");
		Files.write(options.outputRoot.resolve("README.md"), lines, StandardCharsets.UTF_8);
	}

	private static String scenarioId(InstanceRef instance, double scale, double halfWidth, double setupCost) {
		String stem = stripExtension(instance.path.getFileName().toString());
		return stem + "-a" + compact(scale) + "-w" + compact(halfWidth) + "-sc" + compact(setupCost);
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

	private static List<Path> listDatFiles(Path dir, int limit) throws IOException {
		try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
			return stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dat"))
					.sorted().limit(limit).toList();
		}
	}

	private static int[] toIntArray(List<Integer> values) {
		int[] result = new int[values.size()];
		for (int index = 0; index < values.size(); index++) {
			result[index] = values.get(index).intValue();
		}
		return result;
	}

	private static Path convertedPath(Path dataRoot, Path source, int size, int machine) {
		String stem = stripExtension(source.getFileName().toString());
		return dataRoot.resolve(size + "-" + machine).resolve(stem + "_" + machine + "m.dat");
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(0, dot);
	}

	private static double averageProcessing(Path source) throws IOException {
		List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
		int n = Integer.parseInt(lines.get(0).trim().split("\\s+")[0]);
		double total = 0.0;
		for (int index = 1; index <= n; index++) {
			total += Double.parseDouble(lines.get(index).trim().split("\\s+")[0]);
		}
		return total / n;
	}

	private static final class InstanceRef {
		private final int size;
		private final int machines;
		private final Path path;
		private final double averageProcessing;

		private InstanceRef(int size, int machines, Path path, double averageProcessing) {
			this.size = size;
			this.machines = machines;
			this.path = path;
			this.averageProcessing = averageProcessing;
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
			return runId + "\tHEU.FormalExperimentRunner\t" + args + "\t" + outputDir + "\t" + dependsOn
					+ "\t" + block;
		}
	}

	private static final class Options {
		private Path dataRoot = Path.of("data");
		private Path outputRoot = Path.of("experiment-suite", "formal-v1");
		private int casesPerSize = 3;
		private int[] sizes = new int[] { 40, 50, 60, 100 };
		private double timeLimitSeconds = 10800.0;
		private int maxNodes = 100000;

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
				} else {
					throw new IllegalArgumentException("Unknown option: " + key);
				}
			}
			if (options.casesPerSize <= 0) {
				throw new IllegalArgumentException("casesPerSize must be positive");
			}
			return options;
		}
	}
}
