import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Properties;

/** 只读检查06主机本批状态、日志增长及对应子JVM，不启动或停止任何任务。 */
public class NgMainlineStatus {
    public static void main(String[] args) throws Exception {
        Path stage = Path.of(args[0]).toRealPath();
        if (!stage.equals(Path.of("D:/ccx_work/考虑交付的机器调度/work1/experiments/20261104-ng-mainline-a-prepared")))
            throw new IllegalArgumentException("Unexpected status directory");
        System.out.println("TIME " + OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
        System.out.println("BATCH " + Files.readString(stage.resolve("experiment.status")).trim());
        Path runs = stage.resolve("runs");
        if (!Files.isDirectory(runs)) return;
        try (var dirs = Files.list(runs)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Properties status = load(dir.resolve("status.txt"));
                Properties run = load(dir.resolve("run.properties"));
                long pid = Long.parseLong(status.getProperty("pid", "0"));
                var process = ProcessHandle.of(pid);
                boolean alive = process.map(ProcessHandle::isAlive).orElse(false);
                long cpu = process.flatMap(p -> p.info().totalCpuDuration()).map(d -> d.toMillis()).orElse(-1L);
                System.out.println(String.join("\t", "RUN", dir.getFileName().toString(),
                        status.getProperty("state", "UNKNOWN"), "pid=" + pid, "alive=" + alive,
                        "cpuMs=" + cpu, "liveBytes=" + size(dir.resolve("live.log")),
                        "stderrBytes=" + size(dir.resolve("stderr.log")),
                        "profile=" + run.getProperty("profileVersion", "?"),
                        "threads=" + run.getProperty("cplexThreads", "?"),
                        "seed=" + run.getProperty("seedFingerprint", "?")));
            }
        }
    }

    private static Properties load(Path file) throws Exception {
        var properties = new Properties();
        if (Files.exists(file)) try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static long size(Path file) throws Exception {
        return Files.exists(file) ? Files.size(file) : -1;
    }
}
