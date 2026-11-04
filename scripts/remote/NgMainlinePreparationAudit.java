import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;
import TWETBPC.IO.FixedInitialSeedSnapshotIO;

/** 只读取正式清单、输入、seed及结果元数据；输出限于本次准备目录，不调用求解器。 */
public class NgMainlinePreparationAudit {
    private static final Pattern ARG = Pattern.compile("--([A-Za-z0-9]+)=(?:\"([^\"]*)\"|(\\S+))");
    private static final Map<Path, String> HASHES = new HashMap<>();
    private static final Map<Path, String> SEEDS = new HashMap<>();
    private static Path allowed;

    public static void main(String[] args) throws Exception {
        allowed = Path.of(args[0]).toRealPath();
        Set<String> roots = Set.of("D:/ccx", "D:/ccxWork", "D:/ccx_work/\u8003\u8651\u4ea4\u4ed8\u7684\u673a\u5668\u8c03\u5ea6");
        if (roots.stream().noneMatch(s -> Path.of(s).toAbsolutePath().normalize().equals(allowed))) {
            throw new IllegalArgumentException("Unexpected allowed root");
        }
        Path stage = checked(args[1]);
        if (!Set.of("20261103-ng-mainline-prepared", "20261104-ng-mainline-a-prepared")
                .contains(stage.getFileName().toString())) throw new IllegalArgumentException("Wrong stage");
        String mode = args.length > 2 ? args[2] : "audit";
        if (mode.equals("--export-assets")) { exportAssets(stage); return; }
        if (mode.equals("--install-ready")) { installReady(stage); return; }
        if (mode.equals("--verify-ready")) { verifyReady(stage); return; }
        if (!Set.of("audit", "--extra").contains(mode)) throw new IllegalArgumentException("Unknown mode");
        String prefix = mode.equals("--extra") ? "audit-extra" : "audit";
        Path output = stage.resolve(prefix + "-rows.tsv");
        if (Files.exists(output)) throw new IllegalStateException("Audit already exists; do not overwrite");
        var contracts = new TreeMap<String, String>();
        int count = 0;
        try (var out = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            out.write("label\tmanifest\trunId\talgorithm\ttaskSetId\tsize\tmachines\tsetupType\tscaleLevel\twindowLevel\targs\toutputDir\tinput\tseed\tinputSha\tseedSha\tseedFingerprint\tsuccess\tstate\trunPropsB64\tsummaryB64\tcontractId\tconfigSha\tcorefile\n");
            var catalog = Files.readAllLines(stage.resolve(prefix + "-catalog.tsv"), StandardCharsets.UTF_8);
            for (String entry : catalog.subList(1, catalog.size())) {
                String[] spec = entry.split("\t", -1);
                Path manifest = checked(spec[1]);
                var lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
                String[] header = lines.get(0).replace("\uFEFF", "").split("\t", -1);
                for (String line : lines.subList(1, lines.size())) {
                    if (line.isBlank()) continue;
                    String[] cells = line.split("\t", -1);
                    if (cells.length != header.length) throw new IllegalArgumentException("Malformed manifest row");
                    var row = new HashMap<String, String>();
                    for (int i = 0; i < header.length; i++) row.put(header[i], cells[i]);
                    String algorithm = row.get("algorithm");
                    if (spec[2].equals("TI") && !Set.of("TIME_INDEXED", "TIME_INDEXED_SRI").contains(algorithm)) continue;
                    if (Set.of("NG_A", "NG_B").contains(spec[2])) {
                        Set<String> variants = spec[2].equals("NG_A") ? Set.of("F-A", "R-A") : Set.of("F-B", "R-B");
                        if (!variants.contains(row.get("block"))) continue;
                        if (spec[0].equals("ng-old") && row.get("size").equals("50") && row.get("setupType").equals("family")) continue;
                    }
                    var matcher = ARG.matcher(row.get("args"));
                    var options = new HashMap<String, String>();
                    while (matcher.find()) options.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
                    Path input = checked(options.get("instance"));
                    Path seed = checked(options.get("seedFile"));
                    Path dir = checked(row.get("outputDir"));
                    if (!SEEDS.containsKey(seed)) {
                        var value = FixedInitialSeedSnapshotIO.read(seed);
                        SEEDS.put(seed, FixedInitialSeedSnapshotIO.fingerprint(value));
                    }
                    Path summary = null, config = null;
                    if (Files.isDirectory(dir)) try (var files = Files.walk(dir, 2)) {
                        for (Path file : files.filter(Files::isRegularFile).toList()) {
                            if (file.toString().endsWith(".core-summary.csv")) {
                                if (summary != null) throw new IllegalStateException("Duplicate summary " + dir);
                                summary = file;
                            }
                            if (file.toString().endsWith(".config.properties")) {
                                if (config != null) throw new IllegalStateException("Duplicate config " + dir);
                                config = file;
                            }
                        }
                    }
                    String contractId = "", configSha = "";
                    if (config != null) {
                        var p = new Properties();
                        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { p.load(reader); }
                        var stable = new TreeMap<String, String>();
                        for (String key : p.stringPropertyNames()) {
                            if (key.startsWith("config.") && !Set.of("config.instanceName", "config.bpcOutputRoot", "config.liveTraceLogPath").contains(key)) stable.put(key, p.getProperty(key));
                        }
                        String text = String.join("\n", stable.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
                        contractId = hash(text.getBytes(StandardCharsets.UTF_8));
                        contracts.put(contractId, text);
                        configSha = hash(config);
                    }
                    Path properties = dir.resolve("run.properties");
                    Path status = dir.resolve("status.txt");
                    String state = "MISSING";
                    if (Files.exists(status)) state = Files.readAllLines(status).stream().filter(s -> s.startsWith("state=")).findFirst().orElse("UNKNOWN");
                    List<String> values = new ArrayList<>();
                    values.add(spec[0]); values.add(manifest.toString());
                    for (String key : List.of("runId", "algorithm", "taskSetId", "size", "machines", "setupType", "scaleLevel", "windowLevel", "args", "outputDir")) values.add(row.getOrDefault(key, ""));
                    values.addAll(List.of(input.toString(), seed.toString(), hash(input), hash(seed), SEEDS.get(seed),
                            Boolean.toString(Files.exists(dir.resolve("SUCCESS"))), state,
                            encoded(properties), summary == null ? "" : encoded(summary), contractId, configSha,
                            summary == null ? "" : summary.toString()));
                    for (String value : values) if (value.contains("\t") || value.contains("\n")) throw new IllegalArgumentException("Unexpected TSV cell");
                    out.write(String.join("\t", values)); out.newLine(); count++;
                }
            }
        }
        try (var out = Files.newBufferedWriter(stage.resolve(prefix + "-configs.tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            out.write("contractId\tpropertiesB64\n");
            for (var e : contracts.entrySet()) out.write(e.getKey() + "\t" + Base64.getEncoder().encodeToString(e.getValue().getBytes(StandardCharsets.UTF_8)) + "\n");
        }
        System.out.println("AUDIT_ONLY rows=" + count + " inputsAndSeeds=" + HASHES.size() + " contracts=" + contracts.size());
    }

    /** 只复制清单明确列出的输入和seed；禁止覆盖原文件，归档不含求解结果。 */
    private static void exportAssets(Path stage) throws Exception {
        Path zipFile = stage.resolve("prepared-assets.zip");
        if (Files.exists(zipFile)) throw new IllegalStateException("Export already exists");
        var lines = Files.readAllLines(stage.resolve("asset-export.tsv"), StandardCharsets.UTF_8);
        var seen = new HashSet<Path>();
        try (var zip = new ZipOutputStream(Files.newOutputStream(zipFile, StandardOpenOption.CREATE_NEW))) {
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = line.split("\t", -1);
                Path source = checked(cells[0]);
                Path target = stage.resolve(cells[1]).normalize();
                if (!target.startsWith(stage.resolve("export-assets")) || !seen.add(target)) throw new IllegalArgumentException("Invalid export destination");
                if (!hash(source).equals(cells[2])) throw new IllegalStateException("Source asset changed: " + source);
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
                if (!hash(target).equals(cells[2])) throw new IllegalStateException("Copy hash mismatch");
                zip.putNextEntry(new ZipEntry(cells[1])); Files.copy(target, zip); zip.closeEntry();
            }
        }
        System.out.println("ASSETS_ONLY exported=" + seen.size() + " zipSha256=" + hash(zipFile));
    }

    /** 静态验收准备结果；不构造RMP、定价器或调度器。 */
    private static void verifyReady(Path stage) throws Exception {
        var lines = Files.readAllLines(stage.resolve("asset-hashes.tsv"), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split("\t", -1);
            Path file = stage.resolve(cells[0]).normalize();
            if (!file.startsWith(stage) || !hash(file).equals(cells[1])) throw new IllegalStateException("Prepared file mismatch: " + file);
        }
        String jarHash = hash(stage.resolve("solver.jar"));
        if (!jarHash.equals("1b00188438f7a38ddacb809ebe2f7dd736dfb5a31d734a2e0df484713a6e432a")) throw new IllegalStateException("Frozen NG JAR mismatch");
        Path original = allowed.resolve("work1/deployments/20260913-ng-cutset-supernode-v8/solver.jar");
        if (Files.exists(original) && !hash(original).equals(jarHash)) throw new IllegalStateException("Original experiment JAR mismatch");
        var rows = Files.readAllLines(stage.resolve("solve.tsv"), StandardCharsets.UTF_8);
        String[] columns = rows.get(0).split("\t", -1);
        var ids = new HashSet<String>();
        var cases = new HashSet<String>();
        for (String line : rows.subList(1, rows.size())) {
            String[] cells = line.split("\t", -1);
            var row = new HashMap<String, String>();
            for (int i = 0; i < columns.length; i++) row.put(columns[i], cells[i]);
            if (!ids.add(row.get("runId"))) throw new IllegalStateException("Duplicate runId");
            if (!cases.add(row.get("taskSetId") + "/" + row.get("machines") + "/" + row.get("setupType") + "/" + row.get("scaleLevel") + "/" + row.get("windowLevel"))) throw new IllegalStateException("Duplicate case");
            var options = new HashMap<String, String>();
            var matcher = ARG.matcher(row.get("args"));
            while (matcher.find()) options.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            String depth = stage.getFileName().toString().equals("20261104-ng-mainline-a-prepared") ? "2147483647" : "0";
            if (!"NG_DSSR".equals(options.get("algorithm")) || !depth.equals(options.get("timeIndexedCompletionBoundNodeArcFixingMaxDepth"))) throw new IllegalStateException("Wrong pricing/fixing");
            boolean family = row.get("setupType").equals("family");
            if (!Boolean.toString(family).equals(options.get("enableClusterBranching")) || !Boolean.toString(family).equals(options.get("structuredArcStrictTypePriority"))) throw new IllegalStateException("Wrong branch setting");
            if (!"false".equals(options.get("enableCutSetBranching")) || !"false".equals(options.get("cutSetSupernodeSeeds"))) throw new IllegalStateException("CutSet enabled");
            if (!Set.of("60", "80").contains(row.get("size"))) throw new IllegalStateException("Unexpected scale");
            if (!(row.get("size").equals("60") ? "10800" : "18000").equals(options.get("timeLimitSeconds")) || !"100000".equals(options.get("maxNodes"))) throw new IllegalStateException("Wrong budget");
            if (!checked(options.get("instance")).startsWith(stage.resolve("assets/inputs")) || !checked(options.get("seedFile")).startsWith(stage.resolve("assets/seeds")) || !checked(row.get("outputDir")).startsWith(stage.resolve("runs"))) throw new IllegalStateException("Unexpected prepared path");
            FixedInitialSeedSnapshotIO.read(checked(options.get("seedFile")));
        }
        if (Files.exists(stage.resolve("worker.lock")) || Files.exists(stage.resolve("worker.exit-code")) || Files.exists(stage.resolve("runs"))) throw new IllegalStateException("Unexpected execution artifacts");
        if (!Files.readString(stage.resolve("experiment.status")).trim().equals("PREPARED_NOT_STARTED")) throw new IllegalStateException("Wrong preparation status");
        System.out.println("PREPARED_ONLY tasks=" + ids.size() + " checkedFiles=" + (lines.size() - 1) + " solverSha256=" + jarHash + " noRunArtifacts=true");
    }

    private static void installReady(Path stage) throws Exception {
        Set<String> metadata = Set.of("solve.tsv", "run.cmd", "worker.cmd", "experiment.properties",
                "experiment.status", "README.md", "asset-hashes.tsv", "profile-lock.properties",
                "reuse-index.csv", "case-map.tsv", "preparation-summary.json", "profile-family.properties", "profile-random.properties");
        var seen = new HashSet<Path>();
        try (var zip = new ZipInputStream(Files.newInputStream(stage.resolve("ready-package.zip")))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName().replace('\\', '/');
                Path target = stage.resolve(name).normalize();
                if (entry.isDirectory() || !target.startsWith(stage) || !seen.add(target)
                        || (!target.startsWith(stage.resolve("assets")) && !metadata.contains(name))) throw new IllegalArgumentException("Unexpected ready-package entry");
                if (Files.exists(target)) throw new IllegalStateException("Refuse to overwrite " + target);
                Files.createDirectories(target.getParent());
                Files.copy(zip, target); zip.closeEntry();
            }
        }
        System.out.println("INSTALLED_FILES_ONLY count=" + seen.size());
    }

    private static Path checked(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing path");
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(allowed)) throw new IllegalArgumentException("Path outside allowed workspace: " + path);
        return path;
    }

    private static String encoded(Path file) throws Exception {
        return Files.exists(file) ? Base64.getEncoder().encodeToString(Files.readAllBytes(file)) : "";
    }

    private static String hash(Path file) throws Exception {
        if (!HASHES.containsKey(file)) HASHES.put(file, hash(Files.readAllBytes(file)));
        return HASHES.get(file);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
