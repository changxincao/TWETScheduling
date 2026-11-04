import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import TWETBPC.BestBpcProfiles;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.FixedInitialSeedSnapshotIO;

/** 用冻结JAR的真实参数解析器和profile验证配置，不创建求解器或输出结果目录。 */
public class NgMainlineConfigAudit {
    private static final Pattern ARG = Pattern.compile("--([A-Za-z0-9]+)=(?:\"([^\"]*)\"|(\\S+))");
    private static final Set<String> VARIABLE = Set.of("config.instanceName", "config.bpcOutputRoot",
            "config.liveTraceLogPath", "config.fixedInitialColumnSeed", "config.solveTimeLimitSeconds");

    public static void main(String[] args) throws Exception {
        Path stage = Path.of(args[0]).toRealPath();
        if (!stage.getFileName().toString().equals("20261104-ng-mainline-a-prepared")
                || !Set.of(Path.of("D:/ccx/work1/experiments"), Path.of("D:/ccxWork/work1/experiments"))
                        .contains(stage.getParent())) throw new IllegalArgumentException("Unexpected stage");
        var parser = Class.forName("Common.formal.FormalExperimentRunner$Arguments");
        Method parse = parser.getDeclaredMethod("parse", String[].class);
        Method apply = parser.getDeclaredMethod("applyNgDssrExperimentOverrides", TWETBPCConfig.class);
        parse.setAccessible(true); apply.setAccessible(true);
        var lines = Files.readAllLines(stage.resolve("solve.tsv"), StandardCharsets.UTF_8);
        String[] head = lines.get(0).split("\t", -1);
        int count = 0, fields = 0;
        var fingerprints = new HashMap<Path, String>();
        var expectedSeeds = new HashMap<String, String>();
        for (String line : Files.readAllLines(stage.resolve("case-map.tsv"), StandardCharsets.UTF_8).subList(1, 271)) {
            String[] cell = line.split("\t", -1);
            // case-map列由准备脚本固定为scenario,target,assetSourceHost,inputSha,seedSha,seedFingerprint,...。
            expectedSeeds.put(cell[0], cell[5]);
        }
        for (String line : lines.subList(1, lines.size())) {
            var row = new HashMap<String, String>();
            String[] cell = line.split("\t", -1);
            for (int i = 0; i < head.length; i++) row.put(head[i], cell[i]);
            var options = new LinkedHashMap<String, String>();
            var matcher = ARG.matcher(row.get("args"));
            while (matcher.find()) {
                if (options.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3)) != null)
                    throw new IllegalStateException("Duplicate option");
            }
            Object parsed = parse.invoke(null, (Object) options.entrySet().stream()
                    .map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
            var config = new TWETBPCConfig();
            BestBpcProfiles.require(options.get("algorithm")).apply(config);
            apply.invoke(parsed, config);
            config.instanceName = row.get("runId"); config.bpcMethodName = "ngDssr";
            config.bpcOutputRoot = options.get("outputDir");
            config.writeBPCResultFiles = true; config.writeDetailedBPCArtifacts = true;
            config.enableBPCConsoleOutput = false;
            config.liveTraceLogPath = Path.of(options.get("outputDir")).resolve("live.log").toString();
            config.diagnosticStageHeartbeat = true; config.diagnosticNodeProgressSummary = true;
            config.diagnosticPricingSummaryDetails = false;
            config.solveTimeLimitSeconds = Double.parseDouble(options.get("timeLimitSeconds"));
            config.maxNodes = Integer.parseInt(options.get("maxNodes"));
            config.reuseConfiguredBestSolution = false;
            Path seed = Path.of(options.get("seedFile")).toRealPath();
            config.fixedInitialColumnSeed = FixedInitialSeedSnapshotIO.read(seed);
            String fp = FixedInitialSeedSnapshotIO.fingerprint(config.fixedInitialColumnSeed);
            String scenario = seed.getFileName().toString().replaceFirst("\\.seed$", "");
            if (!fp.equals(expectedSeeds.get(scenario))) throw new IllegalStateException("Seed content mismatch " + seed);
            fingerprints.put(seed, fp);
            var actual = new TreeMap<String, String>();
            for (String value : config.snapshotLines()) {
                int at = value.indexOf('=');
                actual.put(value.substring(0, at), value.substring(at + 1));
            }
            var expected = new TreeMap<String, String>();
            Path reference = stage.resolve("profile-" + row.get("setupType") + ".properties");
            for (String value : Files.readAllLines(reference, StandardCharsets.UTF_8)) {
                if (value.startsWith("config.")) {
                    int at = value.indexOf('='); expected.put(value.substring(0, at), value.substring(at + 1));
                }
            }
            VARIABLE.forEach(actual::remove); VARIABLE.forEach(expected::remove);
            if (!actual.equals(expected)) {
                var keys = new TreeSet<>(actual.keySet()); keys.addAll(expected.keySet());
                for (String key : keys) if (!Objects.equals(actual.get(key), expected.get(key)))
                    System.err.println(row.get("runId") + " " + key + " expected=" + expected.get(key) + " actual=" + actual.get(key));
                throw new IllegalStateException("Effective configuration differs from original A");
            }
            fields = actual.size(); count++;
        }
        if (count != 270 || fingerprints.size() != 270) throw new IllegalStateException("Unexpected coverage");
        String worker = Files.readString(stage.resolve("worker.cmd"));
        if (!worker.contains("solve.tsv\" 5 ") || worker.contains("solve.tsv\" 6 ")) throw new IllegalStateException("Wrong worker parallelism");
        for (String file : List.of("runs", "worker.lock", "worker.exit-code"))
            if (Files.exists(stage.resolve(file))) throw new IllegalStateException("Unexpected run artifact " + file);
        System.out.println("CONFIG_ONLY tasks=" + count + " fieldsPerTask=" + fields
                + " seedContents=" + fingerprints.size() + " maxParallel=5 modelThreads=1 noSolverCreated=true");
    }
}
