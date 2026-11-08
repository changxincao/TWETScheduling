package TWETBPC.GC;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/** 默认关闭的同状态OR/set诊断；只返回原OR结果，不参与定价决策。 */
final class NgJoinBitmapComparison {
    private static final double[] Q_LIMITS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 1024,
            Double.POSITIVE_INFINITY};
    private static final int[] K_LIMITS = {1, 2, 4, 8, 16, 64, 256, 1024, Integer.MAX_VALUE};
    private final Bin[][] bins = new Bin[Q_LIMITS.length + 1][K_LIMITS.length + 1];
    private final long[] widthGroups = new long[32], widthMerges = new long[32];
    private final ArrayList<Integer> forwardSizes = new ArrayList<>(), backwardSizes = new ArrayList<>();
    long units, abUnits, baUnits, merges, sumK, sumW, orNanos, setNanos, lastOrNanos;
    long preparationNanos, verificationNanos, samplingNanos, indexBytes;
    private long sampleUnits;

    /** 位图下标是compact/sort后原候选的下标，不是包络贡献者或labelId。 */
    static final class Group {
        final BitSet bits;
        final int[] members;
        final int words, qBin, kBin, widthBin;

        Group(BitSet bits) {
            this.bits = bits;
            members = new int[bits.cardinality()];
            int pos = 0;
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) members[pos++] = i;
            words = (int) ((bits.length() + 63L) / 64L);
            qBin = ratioBin(words, members.length);
            kBin = sizeBin(members.length);
            widthBin = words == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(words);
        }
    }

    private static final class Bin {
        long groups, staticK, staticW, merges, sumK, sumW, sampledMerges;
        long orNanos, setNanos, abSamples, baSamples;
    }

    Group prepare(BitSet members) {
        long start = System.nanoTime();
        Group group = new Group(members);
        Bin bin = bin(group);
        bin.groups++;
        bin.staticK += group.members.length;
        bin.staticW += group.words;
        widthGroups[group.widthBin]++;
        forwardSizes.add(group.members.length);
        indexBytes += 4L * group.members.length;
        preparationNanos += System.nanoTime() - start;
        return group;
    }

    void addBackwardGroup(int size) {
        backwardSizes.add(size);
    }

    /** 每个缓存miss只判定一次组对；两种实现消费同一名单，只把A交回真实join。 */
    BitSet compare(int candidateCount, List<Group> groups) {
        units++;
        for (Group group : groups) {
            Bin bin = bin(group);
            bin.merges++;
            bin.sumK += group.members.length;
            bin.sumW += group.words;
            widthMerges[group.widthBin]++;
            merges++;
            sumK += group.members.length;
            sumW += group.words;
        }
        BitSet a = new BitSet(candidateCount), b = new BitSet(candidateCount);
        boolean orFirst = (units & 1L) != 0;
        long[] times = measure(groups, a, b, orFirst);
        lastOrNanos = times[0];
        orNanos += times[0];
        setNanos += times[1];
        if (orFirst) abUnits++; else baUnits++;
        long verifyStart = System.nanoTime();
        if (!a.equals(b)) throw new IllegalStateException("join OR/set masks differ");
        verificationNanos += System.nanoTime() - verifyStart;

        // 分档计时只是解释性抽样：前8次及其后的2次幂miss，不给每个小组包时钟。
        if (groups.size() >= 64 && (units <= 8 || (units & (units - 1)) == 0)) {
            long sampleStart = System.nanoTime();
            sampleBins(candidateCount, groups);
            samplingNanos += System.nanoTime() - sampleStart;
        }
        return a;
    }

    private void sampleBins(int candidateCount, List<Group> groups) {
        sampleUnits++;
        @SuppressWarnings("unchecked")
        ArrayList<Group>[][] batches = (ArrayList<Group>[][]) new ArrayList<?>[bins.length][K_LIMITS.length + 1];
        for (Group group : groups) {
            ArrayList<Group> batch = batches[group.qBin][group.kBin];
            if (batch == null) batches[group.qBin][group.kBin] = batch = new ArrayList<>();
            batch.add(group);
        }
        for (ArrayList<Group>[] row : batches) {
            for (ArrayList<Group> batch : row) {
                if (batch == null || batch.size() < 32) continue;
                Bin bin = bin(batch.get(0));
                // 同一分档跨抽样单元交替，避免该档总是先执行同一种操作。
                boolean orFirst = ((bin.abSamples + bin.baSamples) & 1L) == 0;
                BitSet a = new BitSet(candidateCount), b = new BitSet(candidateCount);
                long[] times = measure(batch, a, b, orFirst);
                if (!a.equals(b)) throw new IllegalStateException("join OR/set bin masks differ");
                bin.sampledMerges += batch.size();
                bin.orNanos += times[0];
                bin.setNanos += times[1];
                if (orFirst) bin.abSamples++; else bin.baSamples++;
            }
        }
    }

    private static long[] measure(List<Group> groups, BitSet a, BitSet b, boolean orFirst) {
        long orTime, setTime, start;
        if (orFirst) {
            start = System.nanoTime(); fillOr(a, groups); orTime = System.nanoTime() - start;
            start = System.nanoTime(); fillSet(b, groups); setTime = System.nanoTime() - start;
        } else {
            start = System.nanoTime(); fillSet(b, groups); setTime = System.nanoTime() - start;
            start = System.nanoTime(); fillOr(a, groups); orTime = System.nanoTime() - start;
        }
        return new long[] {orTime, setTime};
    }

    private static void fillOr(BitSet target, List<Group> groups) {
        for (Group group : groups) target.or(group.bits);
    }

    private static void fillSet(BitSet target, List<Group> groups) {
        for (Group group : groups) for (int member : group.members) target.set(member);
    }

    private Bin bin(Group group) {
        Bin bin = bins[group.qBin][group.kBin];
        if (bin == null) bins[group.qBin][group.kBin] = bin = new Bin();
        return bin;
    }

    static int ratioBin(int words, int members) {
        if (members == 0) return 0;
        double q = (double) words / members;
        for (int i = 0; i < Q_LIMITS.length; i++) if (q <= Q_LIMITS[i]) return i + 1;
        throw new AssertionError();
    }

    private static int sizeBin(int size) {
        if (size == 0) return 0;
        for (int i = 0; i < K_LIMITS.length; i++) if (size <= K_LIMITS[i]) return i + 1;
        throw new AssertionError();
    }

    private static String qName(int bin) {
        if (bin == 0) return "empty";
        return (bin == 1 ? "0" : Integer.toString((int) Q_LIMITS[bin - 2])) + "to"
                + (Double.isInfinite(Q_LIMITS[bin - 1]) ? "inf" : Integer.toString((int) Q_LIMITS[bin - 1]));
    }

    private static String kName(int bin) {
        if (bin == 0) return "0";
        return (bin == 1 ? "1" : Integer.toString(K_LIMITS[bin - 2] + 1)) + "to"
                + (K_LIMITS[bin - 1] == Integer.MAX_VALUE ? "inf" : Integer.toString(K_LIMITS[bin - 1]));
    }

    private static int[] sortedSizes(ArrayList<Integer> values) {
        int[] sorted = new int[values.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = values.get(i);
        Arrays.sort(sorted);
        return sorted;
    }

    private static int percentile(int[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.ceil(p * sorted.length) - 1];
    }

    private static long sum(int[] values) {
        long sum = 0;
        for (int value : values) sum += value;
        return sum;
    }

    void print(PrintStream out, String context) {
        int[] forward = sortedSizes(forwardSizes), backward = sortedSizes(backwardSizes);
        out.printf(Locale.US, "[NG bitmap compare] %s units=%d abUnits=%d baUnits=%d merges=%d sumK=%d sumW=%d"
                + " orMs=%.6f setMs=%.6f prepMs=%.6f verifyMs=%.6f binDiagnosticMs=%.6f indexPayloadBytes=%d"
                + " fwGroups=%d fwLabels=%d fwKMedian=%d fwKP90=%d fwKMax=%d"
                + " bwGroups=%d bwLabels=%d bwKMedian=%d bwKP90=%d bwKMax=%d sampleUnits=%d%n",
                context, units, abUnits, baUnits, merges, sumK, sumW, orNanos / 1e6, setNanos / 1e6,
                preparationNanos / 1e6, verificationNanos / 1e6, samplingNanos / 1e6, indexBytes,
                forward.length, sum(forward), percentile(forward, .5), percentile(forward, .9), percentile(forward, 1),
                backward.length, sum(backward), percentile(backward, .5), percentile(backward, .9), percentile(backward, 1),
                sampleUnits);
        for (int q = 0; q < bins.length; q++) for (int k = 0; k < bins[q].length; k++) {
            Bin bin = bins[q][k];
            if (bin == null) continue;
            out.printf(Locale.US, "[NG bitmap bin] %s qBin=%s kBin=%s groups=%d staticK=%d staticW=%d"
                    + " merges=%d sumK=%d sumW=%d sampledMerges=%d orMs=%.6f setMs=%.6f abSamples=%d baSamples=%d%n",
                    context, qName(q), kName(k), bin.groups, bin.staticK, bin.staticW, bin.merges, bin.sumK,
                    bin.sumW, bin.sampledMerges, bin.orNanos / 1e6, bin.setNanos / 1e6, bin.abSamples, bin.baSamples);
        }
        for (int w = 0; w < widthGroups.length; w++) if (widthGroups[w] != 0) {
            out.printf(Locale.US, "[NG bitmap width] %s wordPowerBin=%d groups=%d merges=%d%n",
                    context, w, widthGroups[w], widthMerges[w]);
        }
    }
}
