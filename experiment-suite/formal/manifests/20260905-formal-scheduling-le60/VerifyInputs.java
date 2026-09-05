import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
public class VerifyInputs {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("D:/ccx_work/考虑交付的机器调度").toRealPath();
        int count = 0;
        for (String line : Files.readAllLines(Path.of(args[0])).subList(1, Files.readAllLines(Path.of(args[0])).size())) {
            String[] fields = line.split("\t");
            Path path = Path.of(fields[0]).toRealPath();
            if (!path.startsWith(root)) throw new IllegalArgumentException("Outside allowed root");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            if (!hash.equals(fields[1])) throw new IllegalStateException("Hash mismatch: " + path);
            count++;
        }
        System.out.println("Verified input SHA256 count=" + count);
    }
}
