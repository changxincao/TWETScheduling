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
 * 生成论文实验数据引用和三份正式求解 manifest。
 * <p>
 * 实验三只复用已有结果做后处理，因此不生成独立求解任务；compact CPLEX 当前也不进入批次。
 */
public final class FormalExperimentSuiteGenerator {
	private static final String WORKSPACE_TOKEN = "${WORKSPACE}";
	private static final Path WORKSPACE_ROOT = Path.of("").toAbsolutePath().normalize();
	private static final double SETUP_COST_COEFFICIENT = 20.0;
	private static final double DEFAULT_DISCOUNT_STRENGTH = 0.15;
	private static final double[] WINDOW_HALF_WIDTHS = new double[] { 0.0, 100.0, 300.0 };
	private static final double[] OUTSOURCING_RATES = new double[] { 0.5, 1.0, 2.0 };
	private static final String[] BPC_ALGORITHMS = new String[] {
			"NG_DSSR", "TIME_INDEXED", "TIME_INDEXED_SRI" };
	private static final String[] OUTSOURCING_MODELS = new String[] { "columns", "masterVariables" };

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
		double[] breakpoints = outsourcingBreakpoints(instances);
		Files.write(options.outputRoot.resolve("experiment.properties"), List.of(
				"outsourcingQuotation=p*max(wE,wT)",
				"outsourcingBreakpointReferenceTotal=" + compact(2.0 * breakpoints[1]),
				"outsourcingBreakpoint1=" + compact(breakpoints[0]),
				"outsourcingBreakpoint2=" + compact(breakpoints[1]),
				"outsourcingRates=0.5,1,2",
				"discountMarginalRates=1,0.85,0.70",
				"setupCostCoefficient=" + compact(SETUP_COST_COEFFICIENT),
				"solveTimeLimitSeconds=" + compact(options.timeLimitSeconds),
				"maxNodes=" + options.maxNodes,
				"discountModel=" + options.discountModel), StandardCharsets.UTF_8);
		for (InstanceRef instance : instances) {
			for (double scale : new double[] { 1.0, 5.0, 10.0 }) {
				Path scenarioInstance = materializeTimeScaleInstance(options, instance, scale);
				for (double baseHalfWidth : WINDOW_HALF_WIDTHS) {
					double halfWidth = baseHalfWidth * scale;
					String scenario = scenarioId(instance, scale, halfWidth, SETUP_COST_COEFFICIENT);
					Path seedFile = options.outputRoot.resolve("seeds").resolve(scenario + ".seed");
					addSeedRow(seedRows, seedFiles, options, scenario, scenarioInstance, seedFile, halfWidth,
							SETUP_COST_COEFFICIENT, "none", 1.0, 0.0, breakpoints);
					for (String algorithm : BPC_ALGORITHMS) {
						String runId = "pricing-" + scenario + "-" + algorithm.toLowerCase(Locale.ROOT);
						solveRows.add(solveRow(options, runId, scenarioInstance, algorithm, seedFile, halfWidth,
								SETUP_COST_COEFFICIENT, "none", 1.0, 0.0, breakpoints,
								"pricing-comparison"));
					}
				}
			}
		}

		// 外包性能使用原时间尺度，完整交叉窗口、价格和两种 BPC formulation。
		for (InstanceRef instance : instances) {
			for (double halfWidth : WINDOW_HALF_WIDTHS) {
				for (double rate : OUTSOURCING_RATES) {
					String scenario = scenarioId(instance, 1.0, halfWidth, SETUP_COST_COEFFICIENT)
							+ "-or" + compact(rate) + "-d" + compact(DEFAULT_DISCOUNT_STRENGTH);
					Path seedFile = options.outputRoot.resolve("seeds").resolve(scenario + ".seed");
					addSeedRow(seedRows, seedFiles, options, scenario, instance.path, seedFile, halfWidth,
							SETUP_COST_COEFFICIENT, "masterVariables", rate, DEFAULT_DISCOUNT_STRENGTH,
							breakpoints);
					for (String model : OUTSOURCING_MODELS) {
						String runId = "outsourcing-performance-" + scenario + "-" + model;
						solveRows.add(solveRow(options, runId, instance.path, "NG_DSSR", seedFile, halfWidth,
								SETUP_COST_COEFFICIENT, model, rate, DEFAULT_DISCOUNT_STRENGTH,
								breakpoints, "outsourcing-performance"));
					}
				}
			}
		}

		// 实验三只做结果后处理；实验四仅补 n=50、中价、无折扣的缺失求解。
		for (InstanceRef instance : instances) {
			if (instance.size != 50) {
				continue;
			}
			for (double halfWidth : WINDOW_HALF_WIDTHS) {
				String scenario = scenarioId(instance, 1.0, halfWidth, SETUP_COST_COEFFICIENT)
						+ "-or1-d0";
				Path seedFile = options.outputRoot.resolve("seeds").resolve(scenario + ".seed");
				addSeedRow(seedRows, seedFiles, options, scenario, instance.path, seedFile, halfWidth,
						SETUP_COST_COEFFICIENT, "masterVariables", 1.0, 0.0, breakpoints);
				String runId = "outsourcing-discount-" + scenario + "-" + options.discountModel;
				solveRows.add(solveRow(options, runId, instance.path, "NG_DSSR", seedFile, halfWidth,
						SETUP_COST_COEFFICIENT, options.discountModel, 1.0, 0.0, breakpoints,
						"outsourcing-discount"));
			}
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
			Path instance, Path seedFile, double halfWidth, double setupCostCoefficient, String outsourcingModel,
			double outsourcingRate, double discount, double[] breakpoints) {
		if (seedFiles.putIfAbsent(scenario, seedFile) != null) {
			return;
		}
		String runId = "seed-" + scenario;
		Path output = options.outputRoot.resolve("runs").resolve(runId);
		String args = arguments(mapOf(
				"action", "seed", "runId", runId, "instance", portable(instance), "seedFile", portable(seedFile),
				"outputDir", portable(output),
				"dueWindowHalfWidth", compact(halfWidth), "setupCostCoefficient", compact(setupCostCoefficient),
				"outsourcingModel", outsourcingModel, "outsourcingUnitRate", compact(outsourcingRate),
				"discountStrength", compact(discount), "outsourcingBreakpoint1", compact(breakpoints[0]),
				"outsourcingBreakpoint2", compact(breakpoints[1])));
		rows.add(new RunRow(runId, args, portable(output), "", "seed"));
	}

	private static RunRow solveRow(Options options, String runId, Path instance, String algorithm, Path seedFile,
			double halfWidth, double setupCostCoefficient, String outsourcingModel,
			double outsourcingRate, double discount, double[] breakpoints, String block) {
		Path output = options.outputRoot.resolve("runs").resolve(block).resolve(runId);
		String args = arguments(mapOf(
				"action", "solve", "runId", runId, "instance", portable(instance), "algorithm", algorithm,
				"outputDir", portable(output), "seedFile", portable(seedFile),
				"dueWindowHalfWidth", compact(halfWidth), "setupCostCoefficient", compact(setupCostCoefficient),
				"outsourcingModel", outsourcingModel, "outsourcingUnitRate", compact(outsourcingRate),
				"discountStrength", compact(discount), "outsourcingBreakpoint1", compact(breakpoints[0]),
				"outsourcingBreakpoint2", compact(breakpoints[1]),
				"timeLimitSeconds", compact(options.timeLimitSeconds),
				"maxNodes", Integer.toString(options.maxNodes)));
		return new RunRow(runId, args, portable(output), "seed-" + stripExtension(seedFile.getFileName().toString()),
				block);
	}

	/**
	 * 断点只从 n=50 正式任务集合计算一次；同一任务集合的不同机器副本只计一次。
	 */
	private static double[] outsourcingBreakpoints(List<InstanceRef> instances) throws IOException {
		LinkedHashMap<String, Double> totals = quotationTotals(instances, 50);
		if (totals.isEmpty()) {
			totals = quotationTotals(instances, -1);
		}
		if (totals.isEmpty()) {
			throw new IllegalArgumentException("Cannot determine outsourcing breakpoints without instances");
		}
		ArrayList<Double> values = new ArrayList<Double>(totals.values());
		values.sort(Double::compare);
		double median;
		int middle = values.size() / 2;
		if (values.size() % 2 == 0) {
			median = 0.5 * (values.get(middle - 1).doubleValue() + values.get(middle).doubleValue());
		} else {
			median = values.get(middle).doubleValue();
		}
		return new double[] { 0.25 * median, 0.50 * median };
	}

	private static LinkedHashMap<String, Double> quotationTotals(List<InstanceRef> instances, int requiredSize)
			throws IOException {
		LinkedHashMap<String, Double> totals = new LinkedHashMap<String, Double>();
		for (InstanceRef instance : instances) {
			if (requiredSize > 0 && instance.size != requiredSize) {
				continue;
			}
			List<String> lines = Files.readAllLines(instance.path, StandardCharsets.UTF_8);
			StringBuilder taskKey = new StringBuilder();
			double total = 0.0;
			for (int row = 1; row <= instance.size; row++) {
				String normalized = lines.get(row).trim().replaceAll("\\s+", " ");
				String[] tokens = normalized.split(" ");
				if (tokens.length < 4) {
					throw new IOException("Malformed job row " + row + " in " + instance.path);
				}
				double processing = Double.parseDouble(tokens[0]);
				double earlyWeight = Double.parseDouble(tokens[2]);
				double tardyWeight = Double.parseDouble(tokens[3]);
				// due center 可能因旧机器派生文件不同；固定断点只按决定 q_j 的字段去重。
				taskKey.append(tokens[0]).append('/').append(tokens[2]).append('/').append(tokens[3]).append(';');
				total += processing * Math.max(earlyWeight, tardyWeight);
			}
			totals.putIfAbsent(taskKey.toString(), Double.valueOf(total));
		}
		return totals;
	}

	private static void writeReadme(Options options, List<InstanceRef> instances) throws IOException {
		int mainInstanceCount = instances.size();
		int n50InstanceCount = 0;
		for (InstanceRef instance : instances) {
			if (instance.size == 50) {
				n50InstanceCount++;
			}
		}
		int pricingSeedCount = mainInstanceCount * 9;
		int outsourcingSeedCount = mainInstanceCount * 9;
		int discountSeedCount = n50InstanceCount * 3;
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
		lines.add("`pricing-comparison` 使用 W0/W100/W300 和时间尺度 1/5/10 比较三种 BPC；放大后的 processing、due date 和 setup time 写入独立 `.dat`，runner 不接收时间倍率。`outsourcing-performance` 在原时间尺度完整比较三档价格和 columns/masterVariables。`outsourcing-discount` 只补 n=50、中价、无折扣任务。实验三不生成求解任务。");
		lines.add("");
		lines.add("已准备基础实例记录数：" + instances.size() + "；当前每个规模取 "
				+ options.casesPerSize + " 个 case。实例抽样和 random/family setup 的最终落盘规则仍由正式数据生成步骤负责。");
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

	/**
	 * 时间尺度属于数据生成口径。放大后的实例在生成 suite 时写盘，正式 runner 只读取最终文件。
	 */
	private static Path materializeTimeScaleInstance(Options options, InstanceRef instance, double scale)
			throws IOException {
		if (!Double.isFinite(scale) || scale <= 0.0) {
			throw new IllegalArgumentException("time scale must be positive: " + scale);
		}
		if (scale == 1.0) {
			return instance.path;
		}
		List<String> input = Files.readAllLines(instance.path, StandardCharsets.UTF_8);
		if (input.size() < instance.size + 2) {
			throw new IOException("Malformed instance: " + instance.path);
		}
		ArrayList<String> output = new ArrayList<String>(input.size());
		output.add(input.get(0));
		for (int row = 1; row <= instance.size; row++) {
			String[] tokens = input.get(row).trim().split("\\s+");
			if (tokens.length < 4) {
				throw new IOException("Malformed job row " + row + " in " + instance.path);
			}
			tokens[0] = scaleIntegerToken(tokens[0], scale, "processing", instance.path);
			tokens[1] = scaleIntegerToken(tokens[1], scale, "due date", instance.path);
			output.add(String.join(" ", tokens));
		}
		int setupHeader = instance.size + 1;
		if (!"SETUP".equalsIgnoreCase(input.get(setupHeader).trim())) {
			throw new IOException("Expected SETUP block in " + instance.path);
		}
		output.add("SETUP");
		int setupEnd = setupHeader + instance.size + 1;
		if (input.size() <= setupEnd) {
			throw new IOException("Incomplete SETUP block in " + instance.path);
		}
		for (int row = setupHeader + 1; row <= setupEnd; row++) {
			String[] tokens = input.get(row).trim().split("\\s+");
			if (tokens.length != instance.size + 1) {
				throw new IOException("Malformed SETUP row in " + instance.path + ": " + input.get(row));
			}
			for (int column = 0; column < tokens.length; column++) {
				tokens[column] = compact(Double.parseDouble(tokens[column]) * scale);
			}
			output.add(String.join(" ", tokens));
		}
		output.addAll(input.subList(setupEnd + 1, input.size()));

		Path directory = options.outputRoot.resolve("instances")
				.resolve(instance.size + "-" + instance.machines);
		Files.createDirectories(directory);
		String stem = stripExtension(instance.path.getFileName().toString());
		Path target = directory.resolve(stem + "_timeX" + compact(scale) + ".dat");
		Files.write(target, output, StandardCharsets.UTF_8);
		return target;
	}

	private static String scaleIntegerToken(String token, double scale, String field, Path source)
			throws IOException {
		double scaled = Double.parseDouble(token) * scale;
		long rounded = Math.round(scaled);
		if (Math.abs(scaled - rounded) > 1e-8 || rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
			throw new IOException("Scaled " + field + " must remain an integer in " + source + ": " + scaled);
		}
		return Long.toString(rounded);
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
		private Path outputRoot = Path.of("experiment-suite", "formal");
		private int casesPerSize = 3;
		private int[] sizes = new int[] { 40, 50, 60, 100 };
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
