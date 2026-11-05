import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 仅暂停06的指定NG批次；保留已完成结果，并将本次中断与算法失败分开。 */
public final class NgMainlinePause {
    private static final Path ROOT = Path.of("D:/ccx_work/考虑交付的机器调度/work1/experiments/20261104-ng-mainline-a-prepared");
    private static final String BATCH = "20261104-ng-mainline-a-prepared";
    private static final String JCMD = "D:/software/Java/jdk-21/bin/jcmd.exe";

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Path.of(args[0]).toRealPath().equals(ROOT.toRealPath()))
            throw new IllegalArgumentException("Unexpected batch directory");
        if (!"DESKTOP-06K8JI8".equalsIgnoreCase(System.getenv("COMPUTERNAME")))
            throw new IllegalStateException("Unexpected host");
        String before = jvms();
        var targets = targets(before);
        var schedulers = targets.entrySet().stream().filter(e -> e.getValue().contains("HEU.ExperimentBatchScheduler ")).toList();
        if (schedulers.size() != 1) throw new IllegalStateException("Expected one matching scheduler");
        Path receipt = Files.createDirectory(ROOT.resolve("pause-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")))));
        Files.writeString(receipt.resolve("jvms-before.txt"), before, StandardCharsets.UTF_8);
        Files.copy(ROOT.resolve("experiment.status"), receipt.resolve("experiment.status.before"));
        stop(schedulers.get(0).getKey());
        for (long pid : targets.keySet()) if (pid != schedulers.get(0).getKey()) stop(pid);
        String after = jvms();
        Files.writeString(receipt.resolve("jvms-after.txt"), after, StandardCharsets.UTF_8);
        if (!targets(after).isEmpty()) throw new IllegalStateException("Matching JVMs still running; status not changed");
        int interrupted = 0;
        try (var dirs = Files.list(ROOT.resolve("runs"))) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path file = dir.resolve("status.txt");
                if (!Files.isRegularFile(file) || Files.exists(dir.resolve("SUCCESS"))) continue;
                Properties p = new Properties();
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
                long pid = Long.parseLong(p.getProperty("pid", "0"));
                if (!"RUNNING".equals(p.getProperty("state")) || !targets.containsKey(pid)) continue;
                Files.copy(file, receipt.resolve(dir.getFileName() + ".status.before"));
                p.setProperty("state", "INTERRUPTED");
                p.setProperty("reason", "USER_REQUEST_MEMORY_CHECK");
                p.setProperty("interruptedAt", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
                try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(writer, "User requested pause"); }
                interrupted++;
            }
        }
        Files.writeString(ROOT.resolve("experiment.status"), "PAUSED_FOR_MEMORY_CHECK\n", StandardCharsets.UTF_8);
        String result = "host=DESKTOP-06K8JI8\nbatch=" + ROOT + "\nstoppedPids=" + targets.keySet()
                + "\ninterrupted=" + interrupted + "\nremainingBatchJvms=0\nreason=USER_REQUEST_MEMORY_CHECK\n";
        Files.writeString(receipt.resolve("pause-completed.txt"), result, StandardCharsets.UTF_8);
        System.out.println(result + "receipt=" + receipt);
    }

    private static Map<Long, String> targets(String list) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (String line : list.lines().toList()) {
            if (!line.contains(BATCH) || !(line.contains(" HEU.ExperimentBatchScheduler ")
                    || line.contains(" Common.formal.FormalExperimentRunner "))) continue;
            result.put(Long.parseLong(line.substring(0, line.indexOf(' '))), line);
        }
        return result;
    }

    private static String jvms() throws Exception {
        Process p = new ProcessBuilder(JCMD, "-l").redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("Cannot read JVM inventory: " + output);
        return output;
    }

    private static void stop(long pid) throws Exception {
        var process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.get().isAlive()) return;
        if (!process.get().destroyForcibly() && process.get().isAlive())
            throw new IllegalStateException("Process termination denied: " + pid);
        process.get().onExit().get(15, TimeUnit.SECONDS);
    }
}
