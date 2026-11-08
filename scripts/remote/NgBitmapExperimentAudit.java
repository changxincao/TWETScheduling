import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;

import Common.formal.FormalExperimentDataFactory;
import TWETBPC.IO.FixedInitialSeedSnapshotIO;

/** 只读验收位图诊断或OR/set独立求解包；不控制进程或写入求解目录。 */
public final class NgBitmapExperimentAudit {
    public static void main(String[] args) throws Exception {
        Path stage = Path.of(args[0]).toRealPath();
        Path allowed = Path.of("D:/ccx_work/考虑交付的机器调度").toRealPath();
        if (!"DESKTOP-06K8JI8".equals(System.getenv("COMPUTERNAME"))
                || !stage.getParent().equals(allowed.resolve("work1/experiments"))
                || !stage.getFileName().toString().matches("20261108-ng-(?:(?:n60-)?join-bitmap-ab|join-pure-set)-[0-9a-f]{8}")) {
            throw new IllegalArgumentException("Unexpected audit target");
        }
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        System.out.println("TIME " + OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
        System.out.println("HOST " + System.getenv("COMPUTERNAME") + " memory=" + os.getTotalMemorySize()
                + " free=" + os.getFreeMemorySize());
        int assets = 0;
        for (String line : Files.readAllLines(stage.resolve("asset-hashes.tsv"), StandardCharsets.UTF_8).subList(1,
                Files.readAllLines(stage.resolve("asset-hashes.tsv"), StandardCharsets.UTF_8).size())) {
            String[] fields = line.split("\t");
            Path file = stage.resolve(fields[0]).toRealPath();
            if (!file.startsWith(stage)) throw new IllegalArgumentException("Asset escaped stage");
            try (var stream = Files.newInputStream(file)) {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = new byte[65536];
                for (int read; (read = stream.read(bytes)) >= 0;) digest.update(bytes, 0, read);
                if (!HexFormat.of().formatHex(digest.digest()).equals(fields[1])) throw new IllegalStateException("Hash mismatch: " + file);
            }
            assets++;
        }
        System.out.println("ASSETS_OK " + assets);
        var cases = Files.readAllLines(stage.resolve("cases.tsv"), StandardCharsets.UTF_8);
        boolean independentRuns = stage.getFileName().toString().startsWith("20261108-ng-join-pure-set-");
        int expectedCases = independentRuns ? 14 : stage.getFileName().toString().startsWith("20261108-ng-n60-") ? 1 : 6;
        if (cases.size() != expectedCases + 1) throw new IllegalStateException("Unexpected case count");
        for (String line : cases.subList(1, cases.size())) {
            String[] fields = line.split("\t");
            String id = fields[0];
            var data = FormalExperimentDataFactory.load(stage.resolve("assets/" + id + ".dat"));
            if (data.n != Integer.parseInt(fields[1]) || data.m != Integer.parseInt(fields[2]) || data.hasOutsourcingData())
                throw new IllegalStateException("Unexpected instance: " + id);
            var seed = FixedInitialSeedSnapshotIO.read(stage.resolve("assets/" + id + ".seed"));
            System.out.println("CASE " + id + " n=" + data.n + " m=" + data.m + " H=" + data.CmaxH
                    + " seed=" + FixedInitialSeedSnapshotIO.fingerprint(seed));
            Path output = stage.resolve("runs/" + id);
            if (Files.isRegularFile(output.resolve("status.txt")))
                System.out.println("STATUS " + id + " " + Files.readString(output.resolve("status.txt")).replace('\n', ' '));
            if (Files.isRegularFile(output.resolve("stdout.log"))) {
                long count = 0; String last = null;
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        Files.newInputStream(output.resolve("stdout.log")), StandardCharsets.UTF_8))) {
                    for (String log; (log = reader.readLine()) != null;) if (log.startsWith("[NG bitmap compare]")) {count++; last=log;}
                }
                System.out.println("DIAGNOSTIC " + id + " rounds=" + count + (last == null ? "" : " " + last));
                if (independentRuns) {
                    String strategy = null, timing = null;
                    long rounds = 0;
                    try (var reader = Files.newBufferedReader(output.resolve("stdout.log"), StandardCharsets.UTF_8)) {
                        for (String log; (log = reader.readLine()) != null;) {
                            if (log.startsWith("[NG join bitmap strategy]")) strategy = log;
                            if (log.startsWith("[NG join timing]")) { rounds++; timing = log; }
                        }
                    }
                    System.out.println("STRATEGY " + id + " " + strategy);
                    System.out.println("JOIN_TIMING " + id + " rounds=" + rounds + " " + timing);
                }
            }
        }
        System.out.println("BATCH " + Files.readString(stage.resolve("experiment.status")).trim());
    }
}
