import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** 将同一work1内历次正常结束的n80结果实体集中存放，原路径保留兼容junction。 */
public class ConsolidateN80Results {
    record Result(Path source, Path target, Path core, String coreHash) {}

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]).toRealPath();
        Path experiments = work.resolve("experiments").toRealPath();
        Path destination = work.resolve("results/n80-completed").toAbsolutePath().normalize();
        if (!destination.startsWith(work) || destination.startsWith(experiments))
            throw new IllegalArgumentException("Invalid destination");
        List<Result> results = new ArrayList<>();
        Files.walkFileTree(experiments, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.equals("completed-results") || name.equals("archive") || name.startsWith("backup") || name.startsWith("interrupted"))
                    return FileVisitResult.SKIP_SUBTREE;
                if (!Files.exists(dir.resolve("SUCCESS"))) return FileVisitResult.CONTINUE;
                Path source = dir.toRealPath();
                if (!source.startsWith(experiments)) return FileVisitResult.SKIP_SUBTREE;
                List<Path> configs, cores;
                try (var files = Files.walk(source, 2)) {
                    List<Path> all = files.filter(Files::isRegularFile).toList();
                    configs = all.stream().filter(p -> p.toString().endsWith(".config.properties")).toList();
                    cores = all.stream().filter(p -> p.toString().endsWith(".core-summary.csv")).toList();
                }
                if (cores.isEmpty()) return FileVisitResult.SKIP_SUBTREE; // seed不是求解结果。
                boolean n80 = false;
                for (Path config : configs) {
                    if (Files.readAllLines(config).stream().anyMatch(s -> s.trim().matches("run\\.data\\.n\\s*=\\s*80(?:\\.0)?"))) n80=true;
                }
                if (n80) {
                    if (cores.size()!=1) throw new IOException("Ambiguous result: " + source);
                    Path target = destination.resolve(experiments.relativize(source)).normalize();
                    if (!target.startsWith(destination)) throw new IOException("Escaping target");
                    if (Files.exists(target)) throw new IOException("Existing target: " + target);
                    results.add(new Result(source, target, cores.get(0), sha(cores.get(0))));
                }
                return FileVisitResult.SKIP_SUBTREE;
            }
        });
        results.sort(Comparator.comparing(r -> r.source.toString()));
        Map<String,Integer> counts=new TreeMap<>();
        for (Result r : results) counts.merge(experiments.relativize(r.source).getName(0).toString(), 1, Integer::sum);
        System.out.println("Selected n80 completed results="+results.size()+" batches="+counts);
        if (args.length<2 || !args[1].equals("--apply")) return;
        for (Result r : results) {
            Path existing = r.target.getParent();
            while (!Files.exists(existing)) existing=existing.getParent();
            if (!existing.toRealPath().startsWith(work)) throw new IOException("Target parent outside work1");
        }
        Files.createDirectories(destination);
        Path journal = destination.resolve("move-index.tsv");
        Files.writeString(journal, "originalPath\tcurrentPath\tcoreSha256\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        int moved=0;
        for (Result r : results) {
            // 已核验源、目标均在指定work1内；同卷原子改名不复制大日志或列池。
            if (!r.source.toRealPath().startsWith(experiments) || Files.exists(r.target))
                throw new IOException("Move precondition changed: " + r.source);
            Files.createDirectories(r.target.getParent());
            if (!r.target.getParent().toRealPath().startsWith(destination.toRealPath()))
                throw new IOException("Unexpected target junction");
            Files.move(r.source, r.target, StandardCopyOption.ATOMIC_MOVE);
            try {
                Process p = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", r.source.toString(), r.target.toString())
                    .redirectErrorStream(true).start();
                byte[] message=p.getInputStream().readAllBytes();
                if (p.waitFor()!=0) throw new IOException("Junction failed: "+new String(message));
                if (!Files.isSameFile(r.source,r.target) ||
                    !sha(r.target.resolve(r.source.relativize(r.core))).equals(r.coreHash))
                    throw new IOException("Verification failed: "+r.source);
            } catch (Exception e) {
                if (Files.exists(r.source) && Files.isSameFile(r.source,r.target)) Files.delete(r.source);
                if (!Files.exists(r.source)) Files.move(r.target,r.source,StandardCopyOption.ATOMIC_MOVE);
                throw e;
            }
            Files.writeString(journal, r.source+"\t"+r.target+"\t"+r.coreHash+"\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            moved++;
        }
        Files.writeString(destination.resolve("README.md"),
            "# 第一台n80结果集中目录\n\n此处存放历次已正常结束的n80结果目录本体，共"+moved+"项。按原实验批次和runId保留结构："+counts+
            "。不同批次的重复求解记录没有删选。\n\nmove-index.tsv记录旧路径、新路径和核心统计SHA-256。旧路径为兼容junction，"+
            "原manifest和本批completed-results分类入口仍可访问。SUCCESS含限时结束，不等同于证明最优。"+
            "旧批次的数据、算法版本和预算可能不同，不应直接混入20260928正式统计。\n",
            StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println("Moved and verified="+moved+" directory="+destination);
    }

    private static String sha(Path file) throws IOException {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(file)) {
                byte[] b=new byte[65536]; for(int n;(n=in.read(b))>=0;) md.update(b,0,n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
