import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** 内存检修后续跑的运维工具：只检查两台指定主机；备份未完成输出后恢复原批次。 */
public final class NgMainlineResume {
    public static void main(String[] args) throws Exception {
        String host = System.getenv("COMPUTERNAME");
        Path expected = switch (host) {
            case "DESKTOP-06K8JI8" -> Path.of("D:/ccx_work/考虑交付的机器调度");
            case "DESKTOP-42976CD" -> Path.of("D:/ccx");
            default -> throw new IllegalStateException("Unexpected host: " + host);
        };
        Path stage = Path.of(args[0]).toRealPath();
        if (!stage.equals(expected.toRealPath().resolve("work1/experiments/20261104-ng-mainline-a-prepared")))
            throw new IllegalArgumentException("Unexpected batch directory");
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        System.out.println("HOST\t" + host + "\t" + os.getTotalMemorySize() + "\t" + os.getFreeMemorySize());
        System.out.println("JAR\t" + sha(stage.resolve("solver.jar")));
        System.out.println("SOLVE\t" + sha(stage.resolve("solve.tsv")));
        System.out.println("BATCH\t" + Files.readString(stage.resolve("experiment.status")).trim());
        System.out.println("WORKER_BASE64\t" + Base64.getEncoder().encodeToString(Files.readAllBytes(stage.resolve("worker.cmd"))));
        String jvms = jvms();
        System.out.println("JVMS_BASE64\t" + Base64.getEncoder().encodeToString(jvms.getBytes(StandardCharsets.UTF_8)));
        List<String> original = Files.readAllLines(stage.resolve("solve.tsv"), StandardCharsets.UTF_8);
        String[] header = original.get(0).replace("\uFEFF", "").split("\t", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.length; i++) columns.put(header[i], i);
        Map<String, Path> dirs = new LinkedHashMap<>();
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : original.subList(1, original.size())) {
            if (line.isBlank()) continue;
            String[] cells = line.split("\t", -1);
            String id = cells[columns.get("runId")];
            Path dir = Path.of(cells[columns.get("outputDir")]).toAbsolutePath().normalize();
            if (!dir.equals(stage.resolve("runs").resolve(id)) || !id.matches("[A-Za-z0-9._-]+"))
                throw new IllegalStateException("Unexpected output " + dir);
            dirs.put(id, dir); rows.put(id, line);
            Properties p = new Properties();
            if (Files.exists(dir.resolve("status.txt"))) try (var r = Files.newBufferedReader(dir.resolve("status.txt"))) { p.load(r); }
            var assetArgs = Pattern.compile("--(instance|seedFile)=\"([^\"]+)\"").matcher(cells[columns.get("args")]);
            Map<String, String> assets = new HashMap<>();
            while (assetArgs.find()) assets.put(assetArgs.group(1), sha(Path.of(assetArgs.group(2))));
            long pid = Long.parseLong(p.getProperty("pid", "0"));
            long cpuMs = "RUNNING".equals(p.getProperty("state")) ? ProcessHandle.of(pid)
                    .flatMap(proc -> proc.info().totalCpuDuration()).map(d -> d.toMillis()).orElse(-1L) : -1L;
            System.out.println("RUN\t" + id + "\t" + Files.exists(dir.resolve("SUCCESS")) + "\t" + p.getProperty("state", "NOT_STARTED")
                    + "\t" + assets.get("instance") + "\t" + assets.get("seedFile")
                    + "\t" + (Files.exists(dir.resolve("SUCCESS")) ? sha(dir.resolve("SUCCESS")) : "-")
                    + "\t" + pid + "\t" + cpuMs + "\t" + size(dir.resolve("live.log")) + "\t" + size(dir.resolve("stderr.log")));
        }
        if (args.length == 1) return;
        if (!"prepare".equals(args[1]) || args.length != 5) throw new IllegalArgumentException("prepare <parallel> <expectedMemoryGiB>");
        int parallel = Integer.parseInt(args[2]);
        long expectedGiB = Long.parseLong(args[3]);
        if (!Set.of(2, 4).contains(parallel) || !Set.of(64L, 128L).contains(expectedGiB)
                || Math.abs(os.getTotalMemorySize() / (1024.0 * 1024 * 1024) - expectedGiB) > 2)
            throw new IllegalStateException("Resource budget mismatch");
        if (!sha(stage.resolve("solve.tsv")).equals(args[4])) throw new IllegalStateException("Original manifest changed");
        // 仅允许在无调度器/solver时改派任务，不依赖暂停标志推断进程已经退出。
        if (jvms.contains("HEU.ExperimentBatchScheduler") || jvms.contains("Common.formal.FormalExperimentRunner"))
            throw new IllegalStateException("Solver JVMs still active");
        List<String> selected = Files.readAllLines(stage.resolve("resume-selected-20261105.tsv"), StandardCharsets.UTF_8);
        if (!selected.get(0).equals(original.get(0))) throw new IllegalStateException("Header mismatch");
        Set<String> ids = new LinkedHashSet<>();
        for (String line : selected.subList(1, selected.size())) {
            String id = line.split("\t", -1)[columns.get("runId")];
            if (!ids.add(id) || !line.equals(rows.get(id))) throw new IllegalStateException("Manifest row changed: " + id);
            if (Files.exists(dirs.get(id).resolve("SUCCESS"))) throw new IllegalStateException("Selected completed run " + id);
        }
        Path backup = Files.createDirectory(stage.resolve("resume-backup-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")))));
        int moved = 0;
        for (var entry : dirs.entrySet()) {
            String id = entry.getKey(); Path dir = entry.getValue();
            if (Files.exists(dir) && !Files.exists(dir.resolve("SUCCESS"))) {
                if (!dir.toRealPath().equals(dir)) throw new IllegalStateException("Linked run directory");
                Files.createDirectories(backup.resolve("runs"));
                Files.move(dir, backup.resolve("runs").resolve(id)); moved++;
            }
        }
        for (String name : List.of("worker.cmd", "experiment.properties", "experiment.status", "worker.exit-code", "worker.lock", "scheduler-logs")) {
            Path file = stage.resolve(name);
            if (!Files.exists(file)) continue;
            if (Set.of("worker.cmd", "experiment.properties").contains(name)) Files.copy(file, backup.resolve(name));
            else Files.move(file, backup.resolve(name));
        }
        String worker = "@echo off\r\nsetlocal\r\ncd /d \"%~dp0\"\r\n"
                + "set \"JAVA_TOOL_OPTIONS=-Xmx24g\"\r\n"
                + "mkdir worker.lock 2>nul\r\nif errorlevel 1 exit /b 19\r\n"
                + "if not exist scheduler-logs mkdir scheduler-logs\r\necho SOLVE_RUNNING>experiment.status\r\n"
                + "call \"%~dp0run.cmd\" \"%~dp0resume-selected-20261105.tsv\" " + parallel
                + " 1>\"%~dp0scheduler-logs\\solve.out.log\" 2>\"%~dp0scheduler-logs\\solve.err.log\"\r\n"
                + "set \"RC=%ERRORLEVEL%\"\r\nif \"%RC%\"==\"0\" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)\r\n"
                + "echo %RC%>worker.exit-code\r\nexit /b %RC%\r\n";
        Files.writeString(stage.resolve("worker.cmd"), worker, StandardCharsets.US_ASCII);
        String properties = Files.readString(stage.resolve("experiment.properties"), StandardCharsets.UTF_8)
                .replaceAll("(?m)^maxParallel=.*$", "maxParallel=" + parallel);
        Files.writeString(stage.resolve("experiment.properties"), properties + "\nresumeManifest=resume-selected-20261105.tsv\nresumeTasks=" + ids.size() + "\nsolverMaxHeapGiB=24\n", StandardCharsets.UTF_8);
        Files.writeString(stage.resolve("experiment.status"), "PREPARED_RESUME_AFTER_MEMORY_CHECK\n");
        String receipt = "host=" + host + "\nparallel=" + parallel + "\nheapGiB=24\nselected=" + ids.size()
                + "\nbackedUpIncomplete=" + moved + "\noriginalSolveSha=" + sha(stage.resolve("solve.tsv"))
                + "\nselectedSha=" + sha(stage.resolve("resume-selected-20261105.tsv")) + "\nbackup=" + backup + "\n";
        Files.writeString(backup.resolve("prepare-receipt.txt"), receipt, StandardCharsets.UTF_8);
        System.out.println(receipt);
    }
    private static String jvms() throws Exception {
        Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd.exe");
        Process p = new ProcessBuilder(jcmd.toString(), "-l").redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("jcmd failed: " + output);
        return output;
    }
    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static long size(Path path) throws Exception { return Files.exists(path) ? Files.size(path) : 0; }
}
