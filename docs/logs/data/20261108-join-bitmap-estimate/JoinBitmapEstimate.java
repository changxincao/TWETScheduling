import java.util.Arrays;
import java.util.BitSet;
import java.util.Locale;
import java.util.Random;

/** 只测已判定组生成剪枝位图的成本，不模拟组对判定、PWLF或完整pricing。 */
public final class JoinBitmapEstimate {
    private static volatile int sink;
    private static final int LABELS = 65536;
    private static final int BACKWARD_GROUPS = 32;
    private static final int WARMUP = 4;
    private static final int REPEATS = 7;

    private static final class Workload {
        final BitSet[] masks;
        final int[][] members;
        final int[][] pruned;
        final double averageWords;

        Workload(int groupSize) {
            int groups = LABELS / groupSize;
            masks = new BitSet[groups];
            members = new int[groups][groupSize];
            int[] positions = new int[LABELS];
            for (int i = 0; i < positions.length; i++) positions[i] = i;
            Random random = new Random(20261108L + groupSize);
            for (int i = positions.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1), value = positions[i];
                positions[i] = positions[j];
                positions[j] = value;
            }
            long words = 0;
            for (int g = 0; g < groups; g++) {
                System.arraycopy(positions, g * groupSize, members[g], 0, groupSize);
                Arrays.sort(members[g]);
                masks[g] = new BitSet();
                for (int index : members[g]) masks[g].set(index);
                words += (masks[g].length() + 63L) / 64L;
            }
            averageWords = (double) words / groups;
            pruned = new int[BACKWARD_GROUPS][];
            for (int b = 0; b < BACKWARD_GROUPS; b++) {
                int[] values = new int[groups];
                int count = 0;
                for (int g = 0; g < groups; g++) {
                    if ((g + b * 37) % 227 != 0) values[count++] = g;
                }
                pruned[b] = Arrays.copyOf(values, count);
            }
        }
    }

    private static BitSet bitmapOr(Workload w, int b) {
        BitSet result = new BitSet(LABELS);
        for (int g : w.pruned[b]) result.or(w.masks[g]);
        return result;
    }

    private static BitSet memberSet(Workload w, int b) {
        BitSet result = new BitSet(LABELS);
        for (int g : w.pruned[b]) {
            for (int index : w.members[g]) result.set(index);
        }
        return result;
    }

    private static long measure(Workload w, boolean sparse) {
        int value = 0;
        long begin = System.nanoTime();
        for (int b = 0; b < BACKWARD_GROUPS; b++) {
            BitSet result = sparse ? memberSet(w, b) : bitmapOr(w, b);
            value = value * 31 + result.hashCode();
        }
        long elapsed = System.nanoTime() - begin;
        sink = value;
        return elapsed;
    }

    private static double medianMillis(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2] / 1e6;
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        System.err.println("java=" + System.getProperty("java.version")
                + " labels=" + LABELS + " backwardGroups=" + BACKWARD_GROUPS
                + " warmup=" + WARMUP + " repeats=" + REPEATS);
        System.out.println("labels,groupSize,groups,averageWords,backwardGroups,orMedianMs,memberMedianMs,speedup,identical");
        for (int size : new int[] {2, 16, 256, 4096}) {
            Workload w = new Workload(size);
            for (int b = 0; b < BACKWARD_GROUPS; b++) {
                if (!bitmapOr(w, b).equals(memberSet(w, b))) {
                    throw new AssertionError("Different mask at groupSize=" + size + ", backward=" + b);
                }
            }
            for (int i = 0; i < WARMUP; i++) {
                measure(w, false);
                measure(w, true);
            }
            long[] oldTimes = new long[REPEATS], newTimes = new long[REPEATS];
            for (int i = 0; i < REPEATS; i++) {
                if ((i & 1) == 0) {
                    oldTimes[i] = measure(w, false);
                    newTimes[i] = measure(w, true);
                } else {
                    newTimes[i] = measure(w, true);
                    oldTimes[i] = measure(w, false);
                }
            }
            double oldMs = medianMillis(oldTimes), newMs = medianMillis(newTimes);
            System.out.printf("%d,%d,%d,%.3f,%d,%.6f,%.6f,%.3f,true%n",
                    LABELS, size, w.masks.length, w.averageWords, BACKWARD_GROUPS,
                    oldMs, newMs, oldMs / newMs);
        }
    }
}
