import java.nio.file.*;
import java.util.*;

/** 按正式清单导出已正常结束的结果，不扫描归档或运行中目录。 */
public class ExportCompletedFormal {
    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args[0]).toAbsolutePath();
        List<String> rows = Files.readAllLines(manifest);
        List<String> headers = Arrays.asList(rows.get(0).split("\t", -1));
        int id = headers.indexOf("runId"), output = headers.indexOf("outputDir");
        System.out.println("runId,status,seconds,gap,nodes,incumbent,bound");
        for (String row : rows.subList(1, rows.size())) {
            if (row.isBlank()) continue;
            String[] fields = row.split("\t", -1);
            Path dir = manifest.getParent().resolve(fields[output]);
            if (!Files.exists(dir.resolve("SUCCESS"))) continue;
            List<Path> summaries;
            try (var files = Files.walk(dir, 2)) {
                summaries = files.filter(p -> p.toString().endsWith(".core-summary.csv")).toList();
            }
            if (summaries.size() != 1) throw new IllegalStateException("Ambiguous summary: " + dir);
            List<String> csv = Files.readAllLines(summaries.get(0));
            List<String> keys = Arrays.asList(csv.get(0).split(",", -1));
            String[] values = csv.get(1).split(",", -1);
            List<String> result = new ArrayList<>();
            result.add(fields[id]);
            for (String key : List.of("status", "solveTimeSeconds", "gapPercent", "processedNodes", "incumbentCost", "bestBound"))
                result.add(values[keys.indexOf(key)]);
            System.out.println(String.join(",", result));
        }
    }
}
