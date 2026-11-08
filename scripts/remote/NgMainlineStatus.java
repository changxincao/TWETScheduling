import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Properties;

/** 只读检查两台主机的主批/明确补跑目录，不启动或停止任何任务。 */
public class NgMainlineStatus {
    public static void main(String[] args) throws Exception {
        Path stage = Path.of(args[0]).toRealPath();
        Path root = switch (System.getenv("COMPUTERNAME")) {
            case "DESKTOP-06K8JI8" -> Path.of("D:/ccx_work/考虑交付的机器调度");
            case "DESKTOP-42976CD" -> Path.of("D:/ccx");
            default -> throw new IllegalArgumentException("Unexpected host");
        };
        if (!stage.getParent().equals(root.toRealPath().resolve("work1/experiments"))
                || !(stage.getFileName().toString().equals("20261104-ng-mainline-a-prepared")
                || stage.getFileName().toString().matches("20261108-ng-n60-oom-retry-[0-9a-f]{8}")
                || stage.getFileName().toString().matches("20261108-ng-n60-join-timing-[0-9a-f]{8}")))
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
                if (stage.getFileName().toString().startsWith("20261108-ng-n60-join-timing-")
                        && Files.isRegularFile(dir.resolve("stdout.log"))) {
                    String lastTiming = null;
                    // 只提取ASCII计时行；活日志的末尾可能正在写入半个多字节字符。
                    var decoder = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE);
                    try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            Files.newInputStream(dir.resolve("stdout.log")), decoder))) {
                        for (String line; (line = reader.readLine()) != null;) {
                            if (line.startsWith("[NG join timing]")) lastTiming = line;
                        }
                    }
                    if (lastTiming != null) System.out.println(lastTiming);
                }
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
