import java.util.Arrays;
import java.util.Locale;

/** 标定逐项诊断读时钟的量级；仅用于解释插桩开销，不从真实计时中强行扣除。 */
public final class JoinClockCost {
    private static volatile long sink;

    public static void main(String[] args) {
        final int count = 2_000_000;
        double[] bracket = new double[7], total = new double[7];
        for (int round = -3; round < bracket.length; round++) {
            long sum = 0;
            long begin = System.nanoTime();
            for (int i = 0; i < count; i++) {
                long start = System.nanoTime();
                sum += System.nanoTime() - start;
            }
            long elapsed = System.nanoTime() - begin;
            sink = sum;
            if (round >= 0) {
                bracket[round] = (double) sum / count;
                total[round] = (double) elapsed / count;
            }
        }
        Arrays.sort(bracket);
        Arrays.sort(total);
        System.out.printf(Locale.US,
                "java=%s samples=%d repeats=%d medianEmptyBracketNs=%.3f medianTwoReadsLoopNs=%.3f%n",
                System.getProperty("java.version"), count, bracket.length, bracket[3], total[3]);
    }
}
