package TWETBPC.GC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

/** 独立校验两种聚合、缓存单元统计和分档边界，不依赖求解器。 */
public final class NgJoinBitmapComparisonTest {
    public static void main(String[] args) {
        NgJoinBitmapComparison comparison = new NgJoinBitmapComparison();
        BitSet sparse = bits(0, 63, 64, 65, 100003), dense = new BitSet();
        dense.set(0, 256);
        BitSet sparseBefore = (BitSet) sparse.clone(), denseBefore = (BitSet) dense.clone();
        var a = comparison.prepare(sparse);
        var b = comparison.prepare(dense);
        var empty = comparison.prepare(new BitSet());
        require(a.members.length == 5 && a.words == 1563, "sparse members and actual word width");
        require(b.members.length == 256 && b.words == 4, "dense width is not cardinality or capacity");
        require(NgJoinBitmapComparison.ratioBin(4, 256) == 1, "W/k must allow values below one");
        require(NgJoinBitmapComparison.ratioBin(8, 1) == 4
                && NgJoinBitmapComparison.ratioBin(9, 1) == 5, "ratio boundary is upper-inclusive");
        BitSet expected = (BitSet) sparse.clone(); expected.or(dense);
        require(comparison.compare(100004, List.of(a, b, empty)).equals(expected), "AB batch");
        require(comparison.compare(100004, List.of(b, a)).equals(expected), "BA batch");
        require(comparison.compare(100004, List.of()).isEmpty(), "empty result must remain a valid result");
        require(comparison.units == 3 && comparison.abUnits == 2 && comparison.baUnits == 1, "miss-order alternation");
        require(comparison.merges == 5 && comparison.sumK == 522 && comparison.sumW == 3134,
                "merges count once, not twice for AB");
        require(sparse.equals(sparseBefore) && dense.equals(denseBefore), "input groups must not be mutated");
        require(comparison.indexBytes == 4L * 261, "primitive-index payload only");
        comparison.merges = Integer.MAX_VALUE;
        comparison.compare(100004, List.of(a));
        require(comparison.merges == 2147483648L, "aggregate counters must be long");

        NgJoinBitmapComparison sampled = new NgJoinBitmapComparison();
        ArrayList<NgJoinBitmapComparison.Group> sameBin = new ArrayList<>();
        for (int i = 0; i < 64; i++) sameBin.add(sampled.prepare(bits(4096 + i)));
        sampled.compare(8192, sameBin); sampled.compare(8192, sameBin);
        ByteArrayOutputStream sampledLog = new ByteArrayOutputStream();
        sampled.print(new PrintStream(sampledLog, true, StandardCharsets.UTF_8), "node=1 dssrRound=1");
        require(sampledLog.toString(StandardCharsets.UTF_8).contains("sampledMerges=128")
                && sampledLog.toString(StandardCharsets.UTF_8).contains("abSamples=1 baSamples=1"),
                "joint-bin sampling must exercise both orders without per-group clocks");

        Random random = new Random(731921);
        long checks = 0;
        for (int test = 0; test < 160; test++) {
            NgJoinBitmapComparison next = new NgJoinBitmapComparison();
            ArrayList<NgJoinBitmapComparison.Group> groups = new ArrayList<>();
            for (int g = 0; g < 80; g++) {
                BitSet members = new BitSet();
                int count = g % 4 == 0 ? 200 : 1 + random.nextInt(8);
                for (int i = 0; i < count; i++) members.set(random.nextInt(8192));
                groups.add(next.prepare(members));
            }
            next.addBackwardGroup(1); next.addBackwardGroup(7);
            for (int batch = 0; batch < 16; batch++) {
                ArrayList<NgJoinBitmapComparison.Group> selected = new ArrayList<>();
                BitSet independent = new BitSet();
                for (var group : groups) if (batch == 0 || random.nextBoolean()) {
                    selected.add(group);
                    for (int member : group.members) independent.set(member);
                }
                require(next.compare(8192, selected).equals(independent), "random independent member union");
                checks++;
            }
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            next.print(new PrintStream(log, true, StandardCharsets.UTF_8), "node=1 dssrRound=1");
            String summary = log.toString(StandardCharsets.UTF_8);
            require(summary.contains("fwGroups=80") && summary.contains("bwGroups=2 bwLabels=8"), "group-size summary");
            require(summary.contains("[NG bitmap bin]") && summary.contains("sampledMerges="), "joint bins and sampling");
        }
        System.out.println("NgJoinBitmapComparisonTest passed, independent batches=" + checks);
    }

    private static BitSet bits(int... positions) {
        BitSet result = new BitSet();
        for (int position : positions) result.set(position);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
