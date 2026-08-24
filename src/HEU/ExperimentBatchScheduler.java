package HEU;

import java.io.IOException;
import java.io.File;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorCompletionService;

/**
 * 2026-08-15: 读取 UTF-8 TSV manifest 并按固定并发度调度独立 JVM。
 * <p>
 * 这个入口只负责实验批处理基础设施：它继承当前 JVM 的 java executable/classpath/library path，
 * 为每个 run 单独重定向 stdout/stderr，并在输出目录写入 status/args。已有成功标志时直接跳过，
 * 不修改现有 solver 主流程。
 */
public class ExperimentBatchScheduler {

	static final String SUCCESS_MARKER_FILE = "SUCCESS";
	private static final String STATUS_FILE = "status.txt";
	private static final String ARGS_FILE = "args.txt";
	private static final String STDOUT_FILE = "stdout.log";
	private static final String STDERR_FILE = "stderr.log";
	private static final int DEFAULT_MAX_PARALLEL = 4;
	private static final String WORKSPACE_TOKEN = "${WORKSPACE}";

	private final int maxParallel;
	private final String javaExecutable;
	private final String classPath;
	private final String libraryPath;

	public static void main(String[] args) throws Exception {
		BatchArguments batchArguments = BatchArguments.parse(args);
		new ExperimentBatchScheduler(batchArguments.maxParallel).run(batchArguments.manifestPath,
				batchArguments.selectors, batchArguments.scanOnly);
	}

	ExperimentBatchScheduler(int maxParallel) {
		if (maxParallel <= 0) {
			throw new IllegalArgumentException("maxParallel must be positive: " + maxParallel);
		}
		this.maxParallel = maxParallel;
		this.javaExecutable = resolveJavaExecutable();
		this.classPath = normalizePathList(System.getProperty("java.class.path", ""));
		this.libraryPath = System.getProperty("java.library.path", "");
	}

	void run(Path manifestPath) throws Exception {
		run(manifestPath, Collections.<String>emptyList(), false);
	}

	void run(Path manifestPath, List<String> selectorTexts, boolean scanOnly) throws Exception {
		Path normalizedManifest = manifestPath.toAbsolutePath().normalize();
		List<RunSpec> allRunSpecs = readManifest(normalizedManifest);
		List<SelectionFilter> selectors = parseSelectors(selectorTexts);
		SelectionPlan selection = selectRuns(normalizedManifest.getParent(), allRunSpecs, selectors);
		List<RunSpec> runSpecs = selection.runSpecs;
		System.out.printf("ExperimentBatchScheduler scan: total=%d matched=%d dependencies=%d selected=%d "
				+ "localSuccess=%d pending=%d scanOnly=%s selectors=%s%n",
				allRunSpecs.size(), selection.matchedCount, selection.dependencyCount, runSpecs.size(),
				selection.localSuccessCount, runSpecs.size() - selection.localSuccessCount,
				Boolean.toString(scanOnly), selectorTexts.isEmpty() ? "<none>" : selectorTexts);
		System.out.printf("ExperimentBatchScheduler selected solves: algorithms=%s outsourcingModels=%s%n",
				countSelectedSolveField(runSpecs, "algorithm"),
				countSelectedSolveField(runSpecs, "outsourcingModel"));
		if (runSpecs.isEmpty()) {
			System.out.println("ExperimentBatchScheduler: no run selected from manifest " + normalizedManifest);
			return;
		}
		if (scanOnly) {
			return;
		}
		executeRuns(normalizedManifest.getParent(), runSpecs);
	}

	private static Map<String, Integer> countSelectedSolveField(List<RunSpec> runSpecs, String field) {
		LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
		for (RunSpec spec : runSpecs) {
			if (!"solve".equalsIgnoreCase(spec.fieldValue("action"))) {
				continue;
			}
			String value = spec.fieldValue(field);
			if (!value.isEmpty()) {
				counts.merge(value, Integer.valueOf(1), Integer::sum);
			}
		}
		return counts;
	}

	private SelectionPlan selectRuns(Path manifestDir, List<RunSpec> allRunSpecs,
			List<SelectionFilter> selectors) {
		if (allRunSpecs.isEmpty()) {
			return new SelectionPlan(Collections.<RunSpec>emptyList(), 0, 0, 0);
		}
		for (SelectionFilter selector : selectors) {
			if (!allRunSpecs.get(0).hasField(selector.field)) {
				throw new IllegalArgumentException("unknown manifest selector field: " + selector.field
						+ "; available=" + allRunSpecs.get(0).fields.keySet());
			}
		}

		LinkedHashMap<String, RunSpec> byId = new LinkedHashMap<String, RunSpec>();
		for (RunSpec spec : allRunSpecs) {
			byId.put(spec.runId, spec);
		}
		ArrayList<RunSpec> matched = new ArrayList<RunSpec>();
		for (RunSpec spec : allRunSpecs) {
			boolean accepted = true;
			for (SelectionFilter selector : selectors) {
				if (!selector.matches(spec)) {
					accepted = false;
					break;
				}
			}
			if (accepted) {
				matched.add(spec);
			}
		}

		LinkedHashSet<String> selectedIds = new LinkedHashSet<String>();
		for (RunSpec spec : matched) {
			selectedIds.add(spec.runId);
			if (!hasSuccessMarker(manifestDir, spec)) {
				addDependencies(spec, byId, selectedIds, manifestDir);
			}
		}
		ArrayList<RunSpec> selected = new ArrayList<RunSpec>();
		int localSuccess = 0;
		for (RunSpec spec : allRunSpecs) {
			if (selectedIds.contains(spec.runId)) {
				selected.add(spec);
				if (hasSuccessMarker(manifestDir, spec)) {
					localSuccess++;
				}
			}
		}
		return new SelectionPlan(selected, matched.size(), selected.size() - matched.size(), localSuccess);
	}

	private static void addDependencies(RunSpec spec, Map<String, RunSpec> byId,
			LinkedHashSet<String> selectedIds, Path manifestDir) {
		for (String dependencyId : spec.dependencies) {
			RunSpec dependency = byId.get(dependencyId);
			if (dependency == null) {
				throw new IllegalArgumentException("unknown dependency " + dependencyId + " for " + spec.runId);
			}
			if (selectedIds.add(dependencyId) && !hasSuccessMarker(manifestDir, dependency)) {
				addDependencies(dependency, byId, selectedIds, manifestDir);
			}
		}
	}

	private static boolean hasSuccessMarker(Path manifestDir, RunSpec spec) {
		return Files.exists(resolveOutputDir(manifestDir, spec.outputDirText).resolve(SUCCESS_MARKER_FILE));
	}

	private static List<SelectionFilter> parseSelectors(List<String> selectorTexts) {
		ArrayList<SelectionFilter> result = new ArrayList<SelectionFilter>();
		for (String selectorText : selectorTexts) {
			result.add(SelectionFilter.parse(selectorText));
		}
		return result;
	}

	private void executeRuns(Path manifestDir, List<RunSpec> runSpecs) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxParallel, runSpecs.size()));
		CompletionService<RunResult> completion = new ExecutorCompletionService<RunResult>(executor);
		LinkedHashMap<String, RunSpec> pending = new LinkedHashMap<String, RunSpec>();
		java.util.HashSet<String> finishedSuccessfully = new java.util.HashSet<String>();
		java.util.HashSet<String> failedIds = new java.util.HashSet<String>();
		ArrayList<RunResult> results = new ArrayList<RunResult>();
		for (RunSpec spec : runSpecs) {
			Path outputDir = resolveOutputDir(manifestDir, spec.outputDirText);
			if (Files.exists(outputDir.resolve(SUCCESS_MARKER_FILE))) {
				Files.createDirectories(outputDir);
				writeStatus(outputDir, spec, "SKIPPED", null, null, "existing success marker");
				results.add(new RunResult(spec.runId, "SKIPPED", outputDir, 0));
				finishedSuccessfully.add(spec.runId);
				continue;
			}
			if (pending.put(spec.runId, spec) != null) {
				throw new IllegalArgumentException("duplicate runId in manifest: " + spec.runId);
			}
		}
		int running = 0;
		try {
			while (results.size() < runSpecs.size()) {
				java.util.Iterator<Map.Entry<String, RunSpec>> iterator = pending.entrySet().iterator();
				while (running < maxParallel && iterator.hasNext()) {
					RunSpec spec = iterator.next().getValue();
					if (hasFailedDependency(spec, failedIds)) {
						Path outputDir = resolveOutputDir(manifestDir, spec.outputDirText);
						Files.createDirectories(outputDir);
						writeStatus(outputDir, spec, "BLOCKED", Integer.valueOf(-2), null,
								"dependency failed: " + spec.dependencies);
						results.add(new RunResult(spec.runId, "FAILED", outputDir, -2));
						failedIds.add(spec.runId);
						iterator.remove();
						continue;
					}
					if (!finishedSuccessfully.containsAll(spec.dependencies)) {
						continue;
					}
					completion.submit(new RunTask(manifestDir, spec));
					iterator.remove();
					running++;
				}
				if (running == 0) {
					if (!pending.isEmpty()) {
						throw new IllegalArgumentException("unresolved or cyclic manifest dependencies: "
								+ pending.keySet());
					}
					break;
				}
				RunResult result = awaitResult(completion.take());
				running--;
				results.add(result);
				if ("FAILED".equals(result.state)) {
					failedIds.add(result.runId);
				} else {
					finishedSuccessfully.add(result.runId);
				}
			}
		} finally {
			executor.shutdown();
		}

		ArrayList<RunResult> failures = new ArrayList<RunResult>();
		int skipped = 0;
		int succeeded = 0;
		for (RunResult result : results) {
			if ("FAILED".equals(result.state)) {
				failures.add(result);
			} else if ("SKIPPED".equals(result.state)) {
				skipped++;
			} else if ("SUCCEEDED".equals(result.state)) {
				succeeded++;
			}
		}

		System.out.printf("ExperimentBatchScheduler finished: total=%d succeeded=%d skipped=%d failed=%d manifest=%s%n",
				runSpecs.size(), succeeded, skipped, failures.size(), manifestDir);
		if (!failures.isEmpty()) {
			StringBuilder summary = new StringBuilder("ExperimentBatchScheduler detected failed runs:");
			for (RunResult failure : failures) {
				summary.append(System.lineSeparator()).append(" - ").append(failure.runId).append(" -> ")
						.append(failure.outputDir).append(" (exitCode=").append(failure.exitCode).append(")");
			}
			throw new IllegalStateException(summary.toString());
		}
	}

	private static boolean hasFailedDependency(RunSpec spec, java.util.Set<String> failedIds) {
		for (String dependency : spec.dependencies) {
			if (failedIds.contains(dependency)) {
				return true;
			}
		}
		return false;
	}

	private RunResult awaitResult(Future<RunResult> future) throws Exception {
		try {
			return future.get();
		} catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			throw new RuntimeException(cause);
		}
	}

	private List<RunSpec> readManifest(Path manifestPath) throws IOException {
		List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
		if (lines.isEmpty()) {
			return Collections.emptyList();
		}
		int headerIndex = findFirstDataLine(lines);
		if (headerIndex < 0) {
			return Collections.emptyList();
		}

		String[] headers = splitTsv(lines.get(headerIndex));
		Map<String, Integer> columns = buildColumnIndex(headers);
		requireColumn(columns, "runId");
		requireColumn(columns, "mainClass");
		requireColumn(columns, "args");
		requireColumn(columns, "outputDir");
		Integer dependsOnColumn = columns.get("dependsOn");

		ArrayList<RunSpec> runSpecs = new ArrayList<RunSpec>();
		for (int lineIndex = headerIndex + 1; lineIndex < lines.size(); lineIndex++) {
			String rawLine = lines.get(lineIndex);
			if (isIgnorableLine(rawLine)) {
				continue;
			}
			String[] cells = splitTsv(rawLine);
			String runId = cell(cells, columns.get("runId"));
			String mainClass = cell(cells, columns.get("mainClass"));
			String argsText = cell(cells, columns.get("args"));
			String outputDir = cell(cells, columns.get("outputDir"));
			String dependsOn = dependsOnColumn == null ? "" : cell(cells, dependsOnColumn.intValue());
			LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
			for (Map.Entry<String, Integer> column : columns.entrySet()) {
				fields.put(column.getKey(), cell(cells, column.getValue().intValue()));
			}
			if (runId.isEmpty() || mainClass.isEmpty() || outputDir.isEmpty()) {
				throw new IllegalArgumentException(
						"manifest line " + (lineIndex + 1) + " has empty required fields: " + rawLine);
			}
			runSpecs.add(new RunSpec(runId, mainClass, splitCommandLine(argsText), outputDir,
					splitDependencies(dependsOn), fields, lineIndex + 1));
		}
		java.util.HashSet<String> ids = new java.util.HashSet<String>();
		for (RunSpec spec : runSpecs) {
			if (!ids.add(spec.runId)) {
				throw new IllegalArgumentException("duplicate runId in manifest: " + spec.runId);
			}
		}
		for (RunSpec spec : runSpecs) {
			for (String dependency : spec.dependencies) {
				if (!ids.contains(dependency)) {
					throw new IllegalArgumentException("unknown dependency " + dependency + " for " + spec.runId);
				}
			}
		}
		return runSpecs;
	}

	private static List<String> splitDependencies(String value) {
		if (value == null || value.trim().isEmpty()) {
			return Collections.emptyList();
		}
		ArrayList<String> result = new ArrayList<String>();
		for (String token : value.split(",")) {
			String dependency = token.trim();
			if (!dependency.isEmpty()) {
				result.add(dependency);
			}
		}
		return result;
	}

	private static int findFirstDataLine(List<String> lines) {
		for (int i = 0; i < lines.size(); i++) {
			if (!isIgnorableLine(lines.get(i))) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isIgnorableLine(String line) {
		String trimmed = line == null ? "" : line.trim();
		return trimmed.isEmpty() || trimmed.startsWith("#");
	}

	private static Map<String, Integer> buildColumnIndex(String[] headers) {
		LinkedHashMap<String, Integer> columns = new LinkedHashMap<String, Integer>();
		for (int i = 0; i < headers.length; i++) {
			String name = stripBom(headers[i]).trim();
			if (!name.isEmpty()) {
				columns.put(name, Integer.valueOf(i));
			}
		}
		return columns;
	}

	private static void requireColumn(Map<String, Integer> columns, String name) {
		if (!columns.containsKey(name)) {
			throw new IllegalArgumentException("manifest missing required column: " + name);
		}
	}

	private static String cell(String[] cells, int index) {
		if (index < 0 || index >= cells.length) {
			return "";
		}
		return cells[index].trim();
	}

	private static String[] splitTsv(String line) {
		return stripBom(line).split("\t", -1);
	}

	private static String stripBom(String value) {
		if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
			return value.substring(1);
		}
		return value;
	}

	/**
	 * args 列按简单命令行语义拆分，支持单双引号和引号内反斜杠转义。
	 */
	static List<String> splitCommandLine(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return Collections.emptyList();
		}
		ArrayList<String> parts = new ArrayList<String>();
		StringBuilder current = new StringBuilder();
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		boolean escaping = false;
		boolean tokenStarted = false;
		for (int i = 0; i < raw.length(); i++) {
			char ch = raw.charAt(i);
			if (escaping) {
				current.append(ch);
				escaping = false;
				tokenStarted = true;
				continue;
			}
			if (ch == '\\' && (inSingleQuote || inDoubleQuote)) {
				escaping = true;
				tokenStarted = true;
				continue;
			}
			if (ch == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
				tokenStarted = true;
				continue;
			}
			if (ch == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
				tokenStarted = true;
				continue;
			}
			if (Character.isWhitespace(ch) && !inSingleQuote && !inDoubleQuote) {
				if (tokenStarted || current.length() > 0) {
					parts.add(current.toString());
					current.setLength(0);
					tokenStarted = false;
				}
				continue;
			}
			current.append(ch);
			tokenStarted = true;
		}
		if (escaping) {
			current.append('\\');
		}
		if (inSingleQuote || inDoubleQuote) {
			throw new IllegalArgumentException("unterminated quotes in args field: " + raw);
		}
		if (tokenStarted || current.length() > 0) {
			parts.add(current.toString());
		}
		return parts;
	}

	private static String resolveJavaExecutable() {
		Optional<String> command = ProcessHandle.current().info().command();
		if (command.isPresent() && !command.get().trim().isEmpty()) {
			return command.get();
		}
		Path javaHome = Path.of(System.getProperty("java.home", ""));
		String fileName = isWindows() ? "java.exe" : "java";
		return javaHome.resolve("bin").resolve(fileName).toString();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	/**
	 * 子 JVM 的工作目录可能与父进程不同，因此 classpath 里的相对项需要先按当前进程目录固化成绝对路径。
	 */
	private static String normalizePathList(String rawPathList) {
		if (rawPathList == null || rawPathList.isEmpty()) {
			return rawPathList == null ? "" : rawPathList;
		}
		Path baseDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
		String[] parts = rawPathList.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
		ArrayList<String> normalized = new ArrayList<String>(parts.length);
		for (String part : parts) {
			if (part == null || part.isEmpty()) {
				normalized.add(baseDir.toString());
				continue;
			}
			Path path = Path.of(part);
			normalized.add((path.isAbsolute() ? path : baseDir.resolve(path)).normalize().toString());
		}
		return String.join(File.pathSeparator, normalized);
	}

	private final class RunTask implements Callable<RunResult> {
		private final Path manifestDir;
		private final RunSpec runSpec;

		private RunTask(Path manifestDir, RunSpec runSpec) {
			this.manifestDir = manifestDir;
			this.runSpec = runSpec;
		}

		@Override
		public RunResult call() throws Exception {
			Path outputDir = resolveOutputDir(manifestDir, runSpec.outputDirText);
			Files.createDirectories(outputDir);
			Path successMarker = outputDir.resolve(SUCCESS_MARKER_FILE);
			if (Files.exists(successMarker)) {
				writeStatus(outputDir, runSpec, "SKIPPED", null, null, "existing success marker");
				return new RunResult(runSpec.runId, "SKIPPED", outputDir, 0);
			}

			List<String> command = buildCommand(runSpec);
			writeArgs(outputDir, runSpec, command);
			writeStatus(outputDir, runSpec, "STARTING", null, null, "launching child JVM");

			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.directory(manifestDir.toFile());
			processBuilder.redirectOutput(Redirect.to(outputDir.resolve(STDOUT_FILE).toFile()));
			processBuilder.redirectError(Redirect.to(outputDir.resolve(STDERR_FILE).toFile()));

			Process process;
			try {
				process = processBuilder.start();
			} catch (IOException ex) {
				writeStatus(outputDir, runSpec, "FAILED", Integer.valueOf(-3), null,
						"child JVM failed to start: " + ex.getMessage());
				return new RunResult(runSpec.runId, "FAILED", outputDir, -3);
			}
			writeStatus(outputDir, runSpec, "RUNNING", null, Long.valueOf(process.pid()), "child JVM running");
			int exitCode = process.waitFor();
			if (exitCode == 0) {
				Files.writeString(successMarker,
						"runId=" + runSpec.runId + System.lineSeparator() + "finishedAt=" + Instant.now()
								+ System.lineSeparator() + "exitCode=0" + System.lineSeparator(),
						StandardCharsets.UTF_8);
				writeStatus(outputDir, runSpec, "SUCCEEDED", Integer.valueOf(0), Long.valueOf(process.pid()),
						"child JVM finished successfully");
				return new RunResult(runSpec.runId, "SUCCEEDED", outputDir, 0);
			}
			writeStatus(outputDir, runSpec, "FAILED", Integer.valueOf(exitCode), Long.valueOf(process.pid()),
					"child JVM exited abnormally");
			return new RunResult(runSpec.runId, "FAILED", outputDir, exitCode);
		}
	}

	private List<String> buildCommand(RunSpec runSpec) {
		ArrayList<String> command = new ArrayList<String>();
		command.add(javaExecutable);
		if (!libraryPath.isEmpty()) {
			command.add("-Djava.library.path=" + libraryPath);
		}
		command.add("-cp");
		command.add(classPath);
		command.add(runSpec.mainClass);
		for (String argument : runSpec.mainArgs) {
			command.add(expandWorkspaceToken(argument));
		}
		return command;
	}

	private static Path resolveOutputDir(Path manifestDir, String outputDirText) {
		Path outputDir = Path.of(expandWorkspaceToken(outputDirText));
		if (outputDir.isAbsolute()) {
			return outputDir.normalize();
		}
		return manifestDir.resolve(outputDir).normalize();
	}

	private static String expandWorkspaceToken(String value) {
		if (value == null || value.indexOf(WORKSPACE_TOKEN) < 0) {
			return value;
		}
		String workspace = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().toString();
		return value.replace(WORKSPACE_TOKEN, workspace);
	}

	private static void writeArgs(Path outputDir, RunSpec runSpec, List<String> command) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("runId=").append(runSpec.runId).append(System.lineSeparator());
		sb.append("mainClass=").append(runSpec.mainClass).append(System.lineSeparator());
		sb.append("manifestLine=").append(runSpec.manifestLineNumber).append(System.lineSeparator());
		for (int i = 0; i < command.size(); i++) {
			sb.append(String.format("[%02d] %s%n", Integer.valueOf(i), command.get(i)));
		}
		Files.writeString(outputDir.resolve(ARGS_FILE), sb.toString(), StandardCharsets.UTF_8);
	}

	private static void writeStatus(Path outputDir, RunSpec runSpec, String state, Integer exitCode, Long pid,
			String note) throws IOException {
		LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
		fields.put("runId", runSpec.runId);
		fields.put("state", state);
		fields.put("updatedAt", Instant.now().toString());
		fields.put("mainClass", runSpec.mainClass);
		fields.put("outputDir", outputDir.toString());
		if (pid != null) {
			fields.put("pid", String.valueOf(pid.longValue()));
		}
		if (exitCode != null) {
			fields.put("exitCode", String.valueOf(exitCode.intValue()));
		}
		if (note != null && !note.isEmpty()) {
			fields.put("note", note);
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> entry : fields.entrySet()) {
			sb.append(entry.getKey()).append('=').append(entry.getValue()).append(System.lineSeparator());
		}
		Files.writeString(outputDir.resolve(STATUS_FILE), sb.toString(), StandardCharsets.UTF_8);
	}

	/** manifest 每一行对应一个独立 JVM run。 */
	static final class RunSpec {
		final String runId;
		final String mainClass;
		final List<String> mainArgs;
		final String outputDirText;
		final List<String> dependencies;
		final Map<String, String> fields;
		final int manifestLineNumber;

		RunSpec(String runId, String mainClass, List<String> mainArgs, String outputDirText, int manifestLineNumber) {
			this(runId, mainClass, mainArgs, outputDirText, Collections.<String>emptyList(),
					defaultFields(runId, mainClass, outputDirText), manifestLineNumber);
		}

		RunSpec(String runId, String mainClass, List<String> mainArgs, String outputDirText,
				List<String> dependencies, Map<String, String> fields, int manifestLineNumber) {
			this.runId = runId;
			this.mainClass = mainClass;
			this.mainArgs = Collections.unmodifiableList(new ArrayList<String>(mainArgs));
			this.outputDirText = outputDirText;
			this.dependencies = Collections.unmodifiableList(new ArrayList<String>(dependencies));
			this.fields = Collections.unmodifiableMap(new LinkedHashMap<String, String>(fields));
			this.manifestLineNumber = manifestLineNumber;
		}

		boolean hasField(String requestedField) {
			for (String field : fields.keySet()) {
				if (field.equalsIgnoreCase(requestedField)) {
					return true;
				}
			}
			return false;
		}

		String fieldValue(String requestedField) {
			for (Map.Entry<String, String> entry : fields.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(requestedField)) {
					return entry.getValue();
				}
			}
			return "";
		}

		private static Map<String, String> defaultFields(String runId, String mainClass, String outputDir) {
			LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
			values.put("runId", runId);
			values.put("mainClass", mainClass);
			values.put("outputDir", outputDir);
			return values;
		}
	}

	private static final class SelectionFilter {
		final String field;
		final List<String> acceptedValues;

		private SelectionFilter(String field, List<String> acceptedValues) {
			this.field = field;
			this.acceptedValues = acceptedValues;
		}

		boolean matches(RunSpec spec) {
			String actual = spec.fieldValue(field);
			for (String expected : acceptedValues) {
				if (actual.equalsIgnoreCase(expected)) {
					return true;
				}
			}
			return false;
		}

		static SelectionFilter parse(String text) {
			int equals = text == null ? -1 : text.indexOf('=');
			if (equals <= 0 || equals == text.length() - 1) {
				throw new IllegalArgumentException("selector must be field=value1[,value2]: " + text);
			}
			String field = text.substring(0, equals).trim();
			ArrayList<String> values = new ArrayList<String>();
			for (String token : text.substring(equals + 1).split(",")) {
				String value = token.trim();
				if (!value.isEmpty()) {
					values.add(value);
				}
			}
			if (field.isEmpty() || values.isEmpty()) {
				throw new IllegalArgumentException("selector must be field=value1[,value2]: " + text);
			}
			return new SelectionFilter(field, Collections.unmodifiableList(values));
		}
	}

	private static final class SelectionPlan {
		final List<RunSpec> runSpecs;
		final int matchedCount;
		final int dependencyCount;
		final int localSuccessCount;

		SelectionPlan(List<RunSpec> runSpecs, int matchedCount, int dependencyCount, int localSuccessCount) {
			this.runSpecs = runSpecs;
			this.matchedCount = matchedCount;
			this.dependencyCount = dependencyCount;
			this.localSuccessCount = localSuccessCount;
		}
	}

	private static final class RunResult {
		final String runId;
		final String state;
		final Path outputDir;
		final int exitCode;

		RunResult(String runId, String state, Path outputDir, int exitCode) {
			this.runId = runId;
			this.state = state;
			this.outputDir = outputDir;
			this.exitCode = exitCode;
		}
	}

	private static final class BatchArguments {
		final Path manifestPath;
		final int maxParallel;
		final List<String> selectors;
		final boolean scanOnly;

		private BatchArguments(Path manifestPath, int maxParallel, List<String> selectors, boolean scanOnly) {
			this.manifestPath = manifestPath;
			this.maxParallel = maxParallel;
			this.selectors = selectors;
			this.scanOnly = scanOnly;
		}

		private static BatchArguments parse(String[] args) {
			if (args == null || args.length == 0) {
				throw new IllegalArgumentException(
						"Usage: HEU.ExperimentBatchScheduler <manifest.tsv> [maxParallel] "
								+ "[--select=field=value1,value2]... [--scan-only]");
			}
			Path manifestPath = Path.of(args[0]);
			int index = 1;
			int maxParallel = DEFAULT_MAX_PARALLEL;
			if (index < args.length && !args[index].startsWith("--")) {
				maxParallel = Integer.parseInt(args[index++]);
			}
			ArrayList<String> selectors = new ArrayList<String>();
			boolean scanOnly = false;
			for (; index < args.length; index++) {
				String argument = args[index];
				if (argument.startsWith("--select=")) {
					selectors.add(argument.substring("--select=".length()));
				} else if ("--scan-only".equals(argument)) {
					scanOnly = true;
				} else {
					throw new IllegalArgumentException("unknown scheduler argument: " + argument);
				}
			}
			return new BatchArguments(manifestPath, maxParallel,
					Collections.unmodifiableList(selectors), scanOnly);
		}
	}
}
