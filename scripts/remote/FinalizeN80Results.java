import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 为第一机已结束的n80批次建立本机分类入口，并纠正CMD收尾造成的外层错误状态。 */
public class FinalizeN80Results {
    public static void main(String[] args) throws Exception {
        Path batch = Path.of(args[0]).toRealPath();
        if (!batch.getFileName().toString().equals("20260928-balanced-n80-ti-5h"))
            throw new IllegalArgumentException("Unexpected batch: " + batch);
        // 旧CMD日志使用系统代码页；完成证书只含ASCII，按字节读取避免中文路径的编码差异。
        String log = new String(Files.readAllBytes(batch.resolve("scheduler-logs/solve.log")), StandardCharsets.ISO_8859_1);
        if (!log.contains("finished: total=533 succeeded=533 skipped=0 failed=0"))
            throw new IllegalStateException("Scheduler completion certificate missing");
        List<String> lines = Files.readAllLines(batch.resolve("solve.tsv"));
        List<String> headers = Arrays.asList(lines.get(0).replace("\ufeff", "").split("\t", -1));
        List<Map<String,String>> results = new ArrayList<>();
        Map<String,Integer> counts = new TreeMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] values = line.split("\t", -1);
            Map<String,String> row = new LinkedHashMap<>();
            for (int i=0; i<headers.size(); i++) row.put(headers.get(i), values[i]);
            Path source = batch.resolve(row.get("outputDir")).toRealPath();
            if (!source.startsWith(batch.resolve("runs")) || !Files.exists(source.resolve("SUCCESS")))
                throw new IllegalStateException("Invalid completed output: " + source);
            List<Path> cores;
            try (var paths = Files.walk(source, 2)) {
                cores = paths.filter(p -> p.toString().endsWith(".core-summary.csv")).toList();
            }
            if (cores.size() != 1) throw new IllegalStateException("Ambiguous core: " + source);
            List<String> core = Files.readAllLines(cores.get(0));
            List<String> names = csv(core.get(0)), data = csv(core.get(1));
            for (String key : List.of("status", "gapPercent", "solveTimeSeconds", "processedNodes", "incumbentCost", "bestBound"))
                row.put(key, data.get(names.indexOf(key)));
            row.put("source", source.toString());
            String algo = row.get("algorithm").equals("TIME_INDEXED") ? "TI" : "TI-SRI";
            if (!Set.of("TIME_INDEXED", "TIME_INDEXED_SRI").contains(row.get("algorithm")) ||
                !Set.of("family", "random").contains(row.get("setupType")))
                throw new IllegalStateException("Unexpected algorithm/setup");
            row.put("category", algo + "/" + row.get("setupType"));
            counts.merge(algo + "/" + row.get("status"), 1, Integer::sum);
            results.add(row);
        }
        if (results.size() != 533) throw new IllegalStateException("Expected 533 results");

        Path classified = batch.resolve("completed-results");
        Files.createDirectories(classified);
        List<String> index = new ArrayList<>();
        List<String> fields = List.of("runId", "algorithm", "setupType", "taskSetId", "machines", "scaleLevel", "windowLevel",
            "status", "gapPercent", "solveTimeSeconds", "processedNodes", "incumbentCost", "bestBound", "source", "category");
        index.add(String.join("\t", fields));
        for (Map<String,String> row : results) {
            Path link = classified.resolve(row.get("category")).resolve(row.get("runId"));
            Files.createDirectories(link.getParent());
            Path source = Path.of(row.get("source"));
            if (Files.exists(link)) {
                if (!Files.isSameFile(link, source)) throw new IOException("Existing unrelated entry: " + link);
            } else {
                // junction仅增加分类入口，原输出路径不变，已有清单的SUCCESS跳过逻辑仍成立。
                Process p = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), source.toString())
                    .redirectErrorStream(true).start();
                String message = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor() != 0 || !Files.isSameFile(link, source))
                    throw new IOException("Junction failed: " + message);
            }
            index.add(String.join("\t", fields.stream().map(row::get).toList()));
        }
        Files.write(classified.resolve("index.tsv"), index, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        String note = "# n80已完成结果\n\n本批533项全部正常结束。分类目录按TI/TI-SRI、family/random建立Windows junction入口，" +
            "直接访问原结果，没有复制或搬移求解文件。index.tsv包含机器数、时间尺度、窗口、gap、时间、节点及原路径。\n\n" +
            "统计：" + counts + "。7项历史复用仍单独记录在上级reused-results.tsv，不计入本批533项。\n\n" +
            "外层FAILED来自运行期间run.cmd增加9字节，CMD在Java返回后从旧字节偏移继续读，" +
            "将残片\\\" %2 %3展开成错误命令。原FAILED状态保存为experiment.status.before-tail-fix-20261014，" +
            "原solve.err.log保留。此次只在检查533项SUCCESS与唯一core后将外层状态纠正为FINISHED。" +
            "以后不得覆盖正在执行的CMD入口，应为下一次启动创建独立版本脚本。\n";
        Files.writeString(classified.resolve("README.md"), note, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Path status = batch.resolve("experiment.status");
        Files.copy(status, batch.resolve("experiment.status.before-tail-fix-20261014"));
        Files.writeString(status, "FINISHED\r\n", StandardCharsets.UTF_8);
        System.out.println("Completed=533 classified=533 " + counts);
        System.out.println("Directory=" + classified);
        System.out.println("experiment.status=FINISHED; original status/error log preserved");
    }

    private static List<String> csv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i=0; i<line.length(); i++) {
            char c=line.charAt(i);
            if (c=='"') {
                if (quoted && i+1<line.length() && line.charAt(i+1)=='"') { field.append('"'); i++; }
                else quoted=!quoted;
            } else if (c==',' && !quoted) { fields.add(field.toString()); field.setLength(0); }
            else field.append(c);
        }
        fields.add(field.toString());
        return fields;
    }
}
