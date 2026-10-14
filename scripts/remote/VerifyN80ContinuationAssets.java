import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;

/** 仅解包接续任务的输入和 seed，并核验源文件哈希及冻结 JAR。 */
public class VerifyN80ContinuationAssets {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        Path allowed = Path.of("D:/ccx_work/\u8003\u8651\u4ea4\u4ed8\u7684\u673a\u5668\u8c03\u5ea6/work1/experiments").toRealPath();
        if (!root.startsWith(allowed) || !root.getFileName().toString().equals("20261014-n80-cluster-continuation")) {
            throw new IllegalArgumentException("Unexpected continuation root");
        }
        boolean verifyOnly = args.length == 2 && args[1].equals("--verify-only");
        if (!verifyOnly && Files.exists(root.resolve("assets"))) throw new IllegalStateException("Refuse to overwrite assets");
        HashSet<Path> extracted = new HashSet<>();
        if (!verifyOnly) try (var zip = new ZipInputStream(Files.newInputStream(root.resolve("assets.zip")))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path path = root.resolve(entry.getName().replace('\\', '/')).normalize();
                if (!path.startsWith(root.resolve("assets")) && !path.equals(root.resolve("asset-hashes.csv"))) {
                    throw new IllegalArgumentException("Unexpected ZIP entry: " + entry.getName());
                }
                if (!extracted.add(path)) throw new IllegalArgumentException("Duplicate ZIP entry");
                if (entry.isDirectory()) Files.createDirectories(path);
                else {
                    Files.createDirectories(path.getParent());
                    Files.copy(zip, path);
                }
            }
        }
        int verified = 0;
        var lines = Files.readAllLines(root.resolve("asset-hashes.csv"), StandardCharsets.UTF_8);
        if (lines.size() != 183) throw new IllegalStateException("Expected exactly 182 asset hashes");
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            if (cells.length != 3) throw new IllegalArgumentException("Unexpected hash CSV format");
            String relative = unquote(cells[0]);
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root.resolve("assets"))) throw new IllegalArgumentException("Invalid asset path");
            if (!hash(path).equals(unquote(cells[1])) || Files.size(path) != Long.parseLong(unquote(cells[2]))) {
                throw new IllegalStateException("Asset mismatch: " + relative);
            }
            verified++;
        }
        Path jar = root.resolve("../../deployments/20260927-bbe53192-ti-retry/solver.jar").normalize();
        String jarHash = hash(jar);
        if (!jarHash.equals("83a1ed28b6e3e592ea1d338e4ca33def79ef905ffa2a124af864eafb84262767")) {
            throw new IllegalStateException("Solver JAR mismatch");
        }
        System.out.println("verifiedAssets=" + verified);
        System.out.println("solverSha256=" + jarHash);
    }

    private static String unquote(String s) {
        if (!s.startsWith("\"") || !s.endsWith("\"")) throw new IllegalArgumentException("Expected quoted CSV field");
        return s.substring(1, s.length() - 1);
    }

    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) >= 0;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
