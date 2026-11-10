import com.sun.tools.attach.VirtualMachine;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 将一次 Join 诊断接在 06 主机的扩展批次后；现有扩展进程和 F4 调度器不变。 */
public final class HeavyJoinQueueV3 {
    private static final String CURRENT = "20261110-ng-shadow-d3edd1bc";
    private static final String QUEUE_DIR = "20261110-ng-heavy-join-v3";
    private static final String QUEUE_V2 = "20261110-ng-heavy-join-e2e058c0-v2";
    private static final Path EXPERIMENTS = Path.of("D:/ccx_work/考虑交付的机器调度/work1/experiments");
    private static final String CASE = "join-n060-set02-m4-family-high-wwide-f3-set";
    private static final String RUN = "n060-set02-m4-heavy-join";
    private static final String INPUT_SHA = "cb5b7f68cbb577098bfa9ddb479fa0de7533b86a14decfe2da62aa2aa7153ba4";
    private static final String SEED_SHA = "558ae8bf240fe4171c009d795f579d831be887afa830d4824a6efec886a226fa";
    private static final String BASE_SHA = "6ca06fc6283060dc37409982c25308be5594afabba1a4f4f83568c42834085bc";
    private static final String JAVA = "D:/software/Java/jdk-21/bin/java.exe";
    private static final String CPLEX = "D:/software/IBM/ILOG/CPLEX_Studio2211";
    private static final long MIN_GROUP_CHECKS = 100_000_000L;
    private static final Pattern PAIR_LINE = Pattern.compile(
            "^\\[NG join optimization pair\\] .*\\bvariant=SURVIVORS .*\\boldChecks=([0-9]+) .*\\blabelPairs=[0-9]+$");

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(HeavyJoinQueueV3.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        // Attach 参数只传 ASCII，路径从已经核对的调度器工作目录解析。
        try { vm.loadAgent(jar.toString(), args[1]); }
        finally { vm.detach(); }
    }

    public static void agentmain(String overlaySha, Instrumentation ignored) throws Exception {
        Path original = Path.of(System.getProperty("user.dir")).toRealPath();
        if (!original.equals(EXPERIMENTS.resolve(CURRENT).toRealPath())
                || !"HEU.ExperimentBatchScheduler solve-extension.tsv 2".equals(System.getProperty("sun.java.command"))
                || !Files.readString(original.resolve("followup.status")).startsWith("SHADOW_RUNNING")) {
            throw new IllegalStateException("Not the active extension scheduler in the F4 gate");
        }
        if (!overlaySha.matches("[0-9A-Fa-f]{64}")) throw new IllegalArgumentException("Expected overlay SHA-256");
        Path home = original.resolveSibling(QUEUE_DIR).toRealPath();
        if (!home.getParent().equals(original.getParent())) throw new IllegalStateException("Unexpected queue path");
        if (queueV2Active(original.resolveSibling(QUEUE_V2))) {
            throw new IllegalStateException("V2 has already queued a diagnostic for this extension batch");
        }
        Path input = original.resolve("assets/" + CASE + ".dat");
        Path seed = original.resolve("assets/seeds/" + CASE + ".seed");
        Path base = original.resolve("solver.jar");
        Path overlay = home.resolve("heavy-overlay.jar");
        verifySolverInputs(original);
        verify(overlay, overlaySha);
        List<String> command = List.of(JAVA, "-Xmx24g", "-Dfile.encoding=UTF-8",
                "-Djava.library.path=" + CPLEX + "/cplex/bin/x64_win64",
                "-Dtwet.bpc.ngDssrJoinOptimizationCompare=SURVIVORS",
                "-Dtwet.bpc.ngDssrJoinOptimizationMinGroupChecks=" + MIN_GROUP_CHECKS,
                "-Dtwet.bpc.ngDssrJoinBitmapUseSet=true", "-Dtwet.bpc.ngDssrJoinScalarSuffix=true",
                "-Dtwet.bpc.ngDssrJoinPhaseTiming=true", "-cp",
                overlay + ";" + base + ";" + CPLEX + "/cplex/lib/cplex.jar;" + CPLEX + "/cpoptimizer/lib/ILOG.CP.jar",
                "Common.formal.FormalExperimentRunner", "--action=solve", "--runId=" + RUN,
                "--instance=" + input, "--seedFile=" + seed, "--outputDir=" + home.resolve("run"),
                "--algorithm=NG_DSSR", "--timeLimitSeconds=10800", "--maxNodes=100000",
                "--enableClusterBranching=true", "--structuredArcStrictTypePriority=true",
                "--enableCutSetBranching=false", "--cutSetSupernodeSeeds=false", "--clusterMstTheta=0.5",
                "--clusterTemporalWeight=0.0", "--timeIndexedCompletionBoundNodeArcFixing=true",
                "--timeIndexedCompletionBoundNodeArcFixingMaxDepth=2147483647");
        List<ProcessHandle> workers = ProcessHandle.current().descendants().toList();
        Instant schedulerStart = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        Path marker = home.resolve("queued.once");
        AtomicBoolean armed = new AtomicBoolean();
        synchronized (HeavyJoinQueueV3.class) {
            Files.createFile(marker);
            try {
                Files.write(home.resolve("effective-args.txt"), command);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    synchronized (HeavyJoinQueueV3.class) {
                        if (!armed.get()) return;
                    }
                    run(home, original, workers, schedulerStart, overlay, overlaySha, command);
                }, "queued-heavy-join"));
                state(home, "QUEUED extensionScheduler=" + ProcessHandle.current().pid());
                armed.set(true);
            } catch (Exception ex) {
                Files.deleteIfExists(marker);
                throw ex;
            }
        }
    }

    private static boolean queueV2Active(Path v2) throws Exception {
        if (!Files.exists(v2.resolve("queued.once"))) return false;
        Path status = v2.resolve("status.txt");
        if (!Files.isRegularFile(status)) return true;
        return !Files.readString(status).matches(
                "(?s)^(PAIR_CAPTURED|NO_QUALIFYING_PAIR|FAILED|HARD_LIMIT_NO_PAIR|NOT_STARTED) .*");
    }

    private static void run(Path home, Path original, List<ProcessHandle> workers,
            Instant schedulerStart, Path overlay, String overlaySha, List<String> command) {
        Process child = null;
        try {
            if (workers.stream().anyMatch(ProcessHandle::isAlive)
                    || ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive)
                    || !extensionFinished(original, schedulerStart)) {
                try {
                    state(home, "NOT_STARTED extension batch not drained");
                } finally {
                    // 异常关闭时不启动诊断，也不提前放过已知仍活着的旧 worker。
                    Stream.concat(workers.stream(), ProcessHandle.current().descendants()).distinct()
                            .filter(ProcessHandle::isAlive).forEach(worker -> worker.onExit().join());
                }
                return;
            }
            verifySolverInputs(original);
            verify(overlay, overlaySha);
            Path stdout = home.resolve("stdout.log");
            ProcessBuilder builder = new ProcessBuilder(command).directory(home.toFile())
                    .redirectOutput(stdout.toFile()).redirectError(home.resolve("stderr.log").toFile());
            // 不继承当前扩展诊断的 JVM 开关。
            for (String env : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
                builder.environment().remove(env);
            }
            child = builder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12000);
            state(home, "RUNNING pid=" + child.pid());
            try (RandomAccessFile log = new RandomAccessFile(stdout.toFile(), "r")) {
                String pair = null;
                while (child.isAlive()) {
                    pair = readPair(log, MIN_GROUP_CHECKS, deadline);
                    long remaining = deadline - System.nanoTime();
                    if (pair != null || remaining <= 0L) break;
                    child.waitFor(Math.min(TimeUnit.SECONDS.toNanos(10), remaining), TimeUnit.NANOSECONDS);
                }
                boolean live = child.isAlive();
                boolean hardLimit = live && deadline - System.nanoTime() <= 0L;
                if (live) {
                    try { state(home, "STOPPING pid=" + child.pid()); }
                    catch (Exception logFailure) { logFailure.printStackTrace(); }
                }
                // 退出后再读一次，包含最后一次扫描和强停之间写出的完整行。
                pair = completePair(child, log, pair);
                if (pair != null) {
                    Files.writeString(home.resolve("pair.txt"), pair + "\n");
                    state(home, "PAIR_CAPTURED");
                } else if (hardLimit) {
                    state(home, "HARD_LIMIT_NO_PAIR");
                } else {
                    state(home, (child.exitValue() == 0 ? "NO_QUALIFYING_PAIR" : "FAILED")
                            + " exit=" + child.exitValue());
                }
            }
        } catch (Exception ex) {
            if (child != null) stopAndAwait(child);
            try { state(home, "FAILED " + ex); }
            catch (Exception logFailure) { ex.addSuppressed(logFailure); }
            ex.printStackTrace();
        } finally {
            if (child != null) stopAndAwait(child);
        }
    }

    static boolean extensionFinished(Path original, Instant schedulerStart) throws Exception {
        for (int m : new int[] {3, 4}) {
            String id = "extension-shadow-n060-set02-m" + m + "-family-high-wwide-f3";
            Path status = original.resolve("runs").resolve(id).resolve("status.txt");
            if (!Files.isRegularFile(status)) return false;
            List<String> lines = Files.readAllLines(status);
            String result = field(lines, "state=");
            if (!id.equals(field(lines, "runId=")) || !("SUCCEEDED".equals(result)
                    || "FAILED".equals(result) || "SKIPPED".equals(result))) return false;
            String time = field(lines, "updatedAt=");
            if (time == null) return false;
            Instant finished = Instant.parse(time);
            if (finished.isBefore(schedulerStart)) return false;
            String pid = field(lines, "pid=");
            if (pid == null) {
                if (!"SKIPPED".equals(result)
                        && !("FAILED".equals(result) && "-3".equals(field(lines, "exitCode=")))) return false;
            } else {
                long pidValue = Long.parseLong(pid);
                if (pidValue <= 0L || ProcessHandle.of(pidValue).filter(ProcessHandle::isAlive)
                        .filter(process -> !process.info().startInstant().orElse(Instant.MIN).isAfter(finished))
                        .isPresent()) return false;
            }
        }
        return true;
    }

    private static String field(List<String> lines, String prefix) {
        for (String line : lines) if (line.startsWith(prefix)) return line.substring(prefix.length());
        return null;
    }

    /** 只接收以 LF 结尾的行；保留未写完的尾行给下次扫描。 */
    static String readPair(RandomAccessFile log, long threshold, long deadline) throws Exception {
        while (deadline == 0L || deadline - System.nanoTime() > 0L) {
            long start = log.getFilePointer();
            StringBuilder line = new StringBuilder();
            int next;
            while ((next = log.read()) != '\n') {
                if (next < 0 || (deadline != 0L && (line.length() & 255) == 0
                        && deadline - System.nanoTime() <= 0L)) {
                    log.seek(start);
                    return null;
                }
                line.append((char) next);
            }
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
            var pair = PAIR_LINE.matcher(line);
            if (pair.matches() && Long.parseLong(pair.group(1)) >= threshold) return line.toString();
        }
        return null;
    }

    static String completePair(Process child, RandomAccessFile log, String pair) throws Exception {
        stopAndAwait(child);
        return pair != null ? pair : readPair(log, MIN_GROUP_CHECKS, 0L);
    }

    /** 不能确认子进程退出时继续等待，不让 F4 与诊断进程重叠。 */
    static void stopAndAwait(Process child) {
        if (!child.isAlive()) return;
        try { child.destroyForcibly(); }
        catch (RuntimeException stopFailure) { stopFailure.printStackTrace(); }
        boolean interrupted = false;
        while (child.isAlive()) {
            try { child.waitFor(); }
            catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void state(Path home, String status) throws Exception {
        Files.writeString(home.resolve("status.txt"), status + " at=" + Instant.now() + "\n");
    }

    private static void verify(Path file, String expected) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!expected.equalsIgnoreCase(actual)) throw new IllegalStateException("SHA-256 mismatch: " + file);
    }

    private static void verifySolverInputs(Path original) throws Exception {
        verify(original.resolve("assets/" + CASE + ".dat"), INPUT_SHA);
        verify(original.resolve("assets/seeds/" + CASE + ".seed"), SEED_SHA);
        verify(original.resolve("solver.jar"), BASE_SHA);
    }
}
