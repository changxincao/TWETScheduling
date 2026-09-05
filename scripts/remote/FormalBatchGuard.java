import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** 正式批次的接续检查；只处理传入manifest所在实验目录内的文件。 */
public class FormalBatchGuard {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("mode manifest expectedCount");
        Path manifest = Path.of(args[1]).toRealPath();
        Path root = manifest.getParent();
        Path allowed = Path.of("D:/ccx_work/考虑交付的机器调度").toRealPath();
        if (!root.startsWith(allowed)) throw new IllegalArgumentException("Outside remote workspace");
        List<String> lines = Files.readAllLines(manifest);
        int expected = Integer.parseInt(args[2]);
        if (lines.size() != expected + 1) throw new IllegalArgumentException("Unexpected manifest count");
        boolean archive = args[0].equals("archive-incomplete");
        if (!archive && !args[0].equals("verify")) throw new IllegalArgumentException("Unknown mode");
        Path backup = root.resolve("interrupted-seeds-before-pipeline");
        List<Path[]> moves = new ArrayList<>();
        int complete = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split("\t", -1);
            Path output = checked(root, Path.of(cells[3]));
            Matcher matcher = Pattern.compile("--seedFile=\"([^\"]+)\"").matcher(cells[2]);
            if (!matcher.find()) throw new IllegalArgumentException("Missing seed path");
            Path seed = checked(root, Path.of(matcher.group(1)));
            if (Files.exists(output.resolve("SUCCESS"))) {
                if (!Files.isRegularFile(seed) || Files.size(seed) == 0) throw new IllegalStateException("Missing seed: " + seed);
                complete++;
                continue;
            }
            if (!archive) throw new IllegalStateException("Unfinished run: " + cells[0]);
            if (Files.exists(output)) moves.add(new Path[]{output, backup.resolve(output.getFileName())});
            if (Files.exists(seed)) moves.add(new Path[]{seed, backup.resolve(seed.getFileName())});
        }
        if (archive && !moves.isEmpty()) {
            if (Files.exists(backup)) throw new IllegalStateException("Backup already exists");
            for (Path[] move : moves) {
                checked(root, move[0]);
                checked(root, move[1]);
            }
            Files.createDirectory(backup);
            for (Path[] move : moves) Files.move(move[0], move[1]);
        }
        System.out.printf("BatchGuard mode=%s complete=%d expected=%d archived=%d%n", args[0], complete, expected, moves.size());
    }

    private static Path checked(Path root, Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root)) throw new IllegalArgumentException("Outside experiment: " + path);
        Path existing = normalized;
        while (!Files.exists(existing)) existing = existing.getParent();
        if (!existing.toRealPath().startsWith(root)) throw new IllegalArgumentException("Linked outside experiment: " + path);
        return normalized;
    }
}
