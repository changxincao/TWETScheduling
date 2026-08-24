package HEU;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 2026-08-15: ExperimentBatchScheduler 的独立回归测试。
 * <p>
 * 这里不依赖现有 solver，只验证 manifest 解析、成功跳过、输出文件落盘、
 * 失败汇总以及默认并发调度确实会让后续 run 在前一批结束后继续启动。
 */
public final class ExperimentBatchSchedulerTest {

	private ExperimentBatchSchedulerTest() {
	}

	public static void main(String[] args) throws Exception {
		// Windows 可能在子 JVM 退出后短暂保留重定向日志句柄，测试目录按次隔离。
		Path baseDir = Path.of("tmp", "experiment-batch-scheduler-test-" + Long.toUnsignedString(System.nanoTime()));
		resetDirectory(baseDir);
		verifyManifestValidation(baseDir.resolve("invalid"));
		verifySchedulingAndSkip(baseDir.resolve("schedule"));
		verifyDependencies(baseDir.resolve("dependencies"));
		verifySelectionAndScan(baseDir.resolve("selection"));
		verifyFailureAggregation(baseDir.resolve("failure"));
		System.out.println("ExperimentBatchSchedulerTest passed");
	}

	private static void verifySelectionAndScan(Path workDir) throws Exception {
		Files.createDirectories(workDir);
		Path prerequisite = workDir.resolve("seed-20.ready").toAbsolutePath();
		String portablePrerequisite = "${WORKSPACE}/" + Path.of(System.getProperty("user.dir"))
				.toAbsolutePath().normalize().relativize(prerequisite).toString().replace('\\', '/');
		Path manifest = workDir.resolve("selection.tsv");
		List<String> lines = List.of(
				"runId\tmainClass\targs\toutputDir\tdependsOn\taction\tsize\talgorithm",
				"seed-20\tHEU.ExperimentBatchSchedulerTest$CreateFileMain\t\"" + portablePrerequisite
						+ "\"\tout-seed-20\t\tseed\t20\t",
				"solve-20-ng\tHEU.ExperimentBatchSchedulerTest$RequireFileMain\t\"" + portablePrerequisite
						+ "\"\tout-solve-20-ng\tseed-20\tsolve\t20\tNG_DSSR",
				"solve-20-ti\tHEU.ExperimentBatchSchedulerTest$FailureMain\tnot-selected"
						+ "\tout-solve-20-ti\tseed-20\tsolve\t20\tTIME_INDEXED",
				"seed-40\tHEU.ExperimentBatchSchedulerTest$FailureMain\tnot-selected"
						+ "\tout-seed-40\t\tseed\t40\t");
		Files.write(manifest, lines, StandardCharsets.UTF_8);

		List<String> selectors = List.of("size=20", "algorithm=NG_DSSR");
		new ExperimentBatchScheduler(2).run(manifest, selectors, true);
		assertTrue(!Files.exists(prerequisite), "scan-only must not start selected dependency");
		new ExperimentBatchScheduler(2).run(manifest, selectors, false);
		assertTrue(Files.exists(prerequisite), "selected solve dependency was not started");
		assertFileContains(workDir.resolve("out-solve-20-ng/status.txt"), "state=SUCCEEDED",
				"selected solve status");
		assertTrue(!Files.exists(workDir.resolve("out-solve-20-ti")), "unselected algorithm unexpectedly ran");
		assertTrue(!Files.exists(workDir.resolve("out-seed-40")), "unselected size unexpectedly ran");
	}

	private static void verifyDependencies(Path workDir) throws Exception {
		Files.createDirectories(workDir);
		Path prerequisite = workDir.resolve("seed.ready").toAbsolutePath();
		String portablePrerequisite = "${WORKSPACE}/" + Path.of(System.getProperty("user.dir"))
				.toAbsolutePath().normalize().relativize(prerequisite).toString().replace('\\', '/');
		Path manifest = workDir.resolve("dependencies.tsv");
		List<String> lines = List.of(
				"runId\tmainClass\targs\toutputDir\tdependsOn",
				"seed\tHEU.ExperimentBatchSchedulerTest$CreateFileMain\t\""
						+ portablePrerequisite + "\"\tout-seed\t",
				"solve\tHEU.ExperimentBatchSchedulerTest$RequireFileMain\t\""
						+ portablePrerequisite + "\"\tout-solve\tseed");
		Files.write(manifest, lines, StandardCharsets.UTF_8);
		new ExperimentBatchScheduler(2).run(manifest);
		assertFileContains(workDir.resolve("out-solve").resolve("status.txt"), "state=SUCCEEDED",
				"dependent run status");
	}

	private static void verifyManifestValidation(Path workDir) throws Exception {
		Files.createDirectories(workDir);
		Path manifest = workDir.resolve("invalid.tsv");
		Files.writeString(manifest, "runId\tmainClass\toutputDir\nbad\tjava.lang.String\tout\n",
				StandardCharsets.UTF_8);
		assertThrows(IllegalArgumentException.class,
				() -> new ExperimentBatchScheduler(1).run(manifest),
				"missing args column should be rejected");
	}

	private static void verifySchedulingAndSkip(Path workDir) throws Exception {
		Files.createDirectories(workDir);
		Path manifest = workDir.resolve("batch.tsv");
		Path skippedDir = workDir.resolve("out-skip");
		Files.createDirectories(skippedDir);
		Files.writeString(skippedDir.resolve(ExperimentBatchScheduler.SUCCESS_MARKER_FILE), "done\n",
				StandardCharsets.UTF_8);

		ArrayList<String> lines = new ArrayList<String>();
		lines.add("runId\tmainClass\targs\toutputDir");
		for (int i = 0; i < 5; i++) {
			lines.add("sleep-" + i + "\tHEU.ExperimentBatchSchedulerTest$SleepAndEchoMain\t"
					+ "1200 \"job " + i + "\"\tout-" + i);
		}
		lines.add("skip-existing\tHEU.ExperimentBatchSchedulerTest$ShouldNotRunMain\t\"blocked\"\tout-skip");
		Files.write(manifest, lines, StandardCharsets.UTF_8);

		long start = System.nanoTime();
		new ExperimentBatchScheduler(4).run(manifest);
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
		if (elapsedMillis >= 6000L) {
			throw new AssertionError("default parallel scheduling looks sequential: elapsedMs=" + elapsedMillis);
		}

		for (int i = 0; i < 5; i++) {
			Path outputDir = workDir.resolve("out-" + i);
			assertFileContains(outputDir.resolve("stdout.log"), "stdout:job " + i, "stdout log");
			assertFileContains(outputDir.resolve("stderr.log"), "stderr:job " + i, "stderr log");
			assertFileContains(outputDir.resolve("status.txt"), "state=SUCCEEDED", "success status");
			assertFileContains(outputDir.resolve("args.txt"), "HEU.ExperimentBatchSchedulerTest$SleepAndEchoMain",
					"args main class");
			assertFileContains(outputDir.resolve("args.txt"), "-cp", "args classpath");
			assertTrue(Files.exists(outputDir.resolve(ExperimentBatchScheduler.SUCCESS_MARKER_FILE)),
					"success marker missing for out-" + i);
		}

		assertFileContains(skippedDir.resolve("status.txt"), "state=SKIPPED", "skip status");
		assertTrue(!Files.exists(skippedDir.resolve("stdout.log")), "skipped run must not start child JVM");
		assertTrue(!Files.exists(workDir.resolve("should-not-run.txt")), "skipped run unexpectedly executed");
	}

	private static void verifyFailureAggregation(Path workDir) throws Exception {
		Files.createDirectories(workDir);
		Path manifest = workDir.resolve("failure.tsv");
		List<String> lines = List.of(
				"runId\tmainClass\targs\toutputDir",
				"ok\tHEU.ExperimentBatchSchedulerTest$SleepAndEchoMain\t\"50\" ok-run\tout-ok",
				"fail\tHEU.ExperimentBatchSchedulerTest$FailureMain\t\"boom\"\tout-fail");
		Files.write(manifest, lines, StandardCharsets.UTF_8);

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> new ExperimentBatchScheduler(2).run(manifest),
				"failed child JVM should surface aggregated failure");
		assertFileContains(workDir.resolve("out-ok").resolve("status.txt"), "state=SUCCEEDED", "ok run status");
		assertFileContains(workDir.resolve("out-fail").resolve("status.txt"), "state=FAILED", "failed run status");
		assertTrue(ex.getMessage().contains("fail"), "failure summary should contain failed runId");
		assertTrue(!Files.exists(workDir.resolve("out-fail").resolve(ExperimentBatchScheduler.SUCCESS_MARKER_FILE)),
				"failed run must not create success marker");
	}

	private static void resetDirectory(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
			return;
		}
		try (var stream = Files.walk(dir)) {
			List<Path> paths = stream.toList();
			for (Path path : paths) {
				try {
					DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);
					if (dos != null) {
						dos.setReadOnly(false);
					}
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}
			}
			for (Path path : paths.stream().sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
		Files.createDirectories(dir);
	}

	private static void assertFileContains(Path file, String expected, String message) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		if (!text.contains(expected)) {
			throw new AssertionError(message + ": expected=" + expected + ", file=" + file + ", actual=" + text);
		}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable runnable, String message)
			throws Exception {
		try {
			runnable.run();
		} catch (Throwable ex) {
			if (type.isInstance(ex)) {
				return type.cast(ex);
			}
			throw new AssertionError(message + ": unexpected exception " + ex, ex);
		}
		throw new AssertionError(message + ": no exception thrown");
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	/** 子进程入口：打印日志并 sleep，用于验证 stdout/stderr 与并发调度。 */
	public static final class SleepAndEchoMain {
		private SleepAndEchoMain() {
		}

		public static void main(String[] args) throws Exception {
			long sleepMillis = Long.parseLong(args[0]);
			String tag = args.length >= 2 ? args[1] : "untitled";
			System.out.println("stdout:" + tag);
			System.err.println("stderr:" + tag);
			Thread.sleep(sleepMillis);
		}
	}

	/** 子进程入口：如果被启动就写标记文件，正常逻辑下应该被 success marker 跳过。 */
	public static final class ShouldNotRunMain {
		private ShouldNotRunMain() {
		}

		public static void main(String[] args) throws Exception {
			Files.writeString(Path.of("tmp", "experiment-batch-scheduler-test", "schedule", "should-not-run.txt"),
					args.length == 0 ? "unexpected\n" : args[0] + System.lineSeparator(), StandardCharsets.UTF_8);
		}
	}

	/** 子进程入口：显式失败，用于验证父调度器的失败汇总和 status 落盘。 */
	public static final class FailureMain {
		private FailureMain() {
		}

		public static void main(String[] args) {
			String message = args.length == 0 ? "failure" : args[0];
			throw new IllegalStateException(message);
		}
	}

	public static final class CreateFileMain {
		private CreateFileMain() {
		}

		public static void main(String[] args) throws Exception {
			Thread.sleep(100L);
			Files.writeString(Path.of(args[0]), "ready\n", StandardCharsets.UTF_8);
		}
	}

	public static final class RequireFileMain {
		private RequireFileMain() {
		}

		public static void main(String[] args) {
			if (!Files.exists(Path.of(args[0]))) {
				throw new IllegalStateException("dependency output is missing");
			}
		}
	}
}
