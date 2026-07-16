package PWLFTesting;

import java.util.Random;

import Common.PiecewiseLinearFunction;
import Common.Utility;

/**
 * 对拍启发式单任务插入的旧链表实现与无临时 PWLF 标量实现，并提供高频微基准。
 */
public final class PiecewiseLinearInsertScalarTest {
    private static final double TOLERANCE = 1e-6;

    private PiecewiseLinearInsertScalarTest() {
    }

    public static void main(String[] args) {
        int randomCases = Integer.getInteger("twet.pwlf.scalarInsert.randomCases", 200_000);
        int benchmarkIterations = Integer.getInteger("twet.pwlf.scalarInsert.benchmarkIterations", 2_000_000);
        runRandomEquivalence(randomCases);
        runBenchmark(benchmarkIterations);
    }

    private static void runRandomEquivalence(int cases) {
        Random random = new Random(20260715L);
        PiecewiseLinearFunction.PrefixMinimumWorkspace workspace =
                new PiecewiseLinearFunction.PrefixMinimumWorkspace();
        double originalUpperBound = Utility.curUpperBound;
        try {
            for (int test = 0; test < cases; test++) {
                double domainEnd = 80.0 + random.nextInt(241);
                PiecewiseLinearFunction prefix = randomFunction(random, domainEnd, 1 + random.nextInt(24));
                PiecewiseLinearFunction penalty = randomFunction(random, domainEnd, 1 + random.nextInt(24));
                PiecewiseLinearFunction suffix = randomFunction(random, domainEnd, 1 + random.nextInt(24));
                double prefixShift = random.nextInt(51);
                double suffixShift = random.nextInt(51);
                double bridgeCost = random.nextDouble() * 100.0 - 20.0;
                Utility.curUpperBound = test % 5 == 0 ? 50.0 + random.nextDouble() * 500.0 : Utility.big_M;

                double expected = reference(prefix, prefixShift, penalty, suffix, suffixShift, bridgeCost);
                double actual = PiecewiseLinearFunction.findMinimalInsertedJobCost(prefix, prefixShift, penalty,
                        suffix, suffixShift, bridgeCost, workspace);
                if (!same(expected, actual)) {
                    throw new AssertionError("Mismatch at case " + test + ": expected=" + expected
                            + ", actual=" + actual + ", prefixShift=" + prefixShift
                            + ", suffixShift=" + suffixShift + ", upperBound=" + Utility.curUpperBound);
                }
            }
        } finally {
            Utility.curUpperBound = originalUpperBound;
        }
        System.out.println("scalar-insert random equivalence passed: cases=" + cases);
    }

    private static void runBenchmark(int iterations) {
        Random random = new Random(42L);
        PiecewiseLinearFunction prefix = randomFunction(random, 300.0, 20);
        PiecewiseLinearFunction penalty = randomFunction(random, 300.0, 20);
        PiecewiseLinearFunction suffix = randomFunction(random, 300.0, 20);
        PiecewiseLinearFunction.PrefixMinimumWorkspace workspace =
                new PiecewiseLinearFunction.PrefixMinimumWorkspace();
        String mode = System.getProperty("twet.pwlf.scalarInsert.benchmarkMode", "both");
        boolean reverse = Boolean.getBoolean("twet.pwlf.scalarInsert.reverse");
        double originalUpperBound = Utility.curUpperBound;
        Utility.curUpperBound = Utility.big_M;
        double checksum = 0.0;
        try {
            for (int i = 0; i < 50_000; i++) {
                if (!"new".equals(mode)) {
                    checksum += reference(prefix, 17.0, penalty, suffix, 11.0, 7.0);
                }
                if (!"old".equals(mode)) {
                    checksum += PiecewiseLinearFunction.findMinimalInsertedJobCost(prefix, 17.0, penalty, suffix,
                            11.0, 7.0, workspace);
                }
            }
            long oldNanos = -1L;
            long newNanos = -1L;
            if ("old".equals(mode)) {
                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    checksum += reference(prefix, 17.0, penalty, suffix, 11.0, 7.0);
                }
                oldNanos = System.nanoTime() - start;
            } else if ("new".equals(mode)) {
                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    checksum += PiecewiseLinearFunction.findMinimalInsertedJobCost(prefix, 17.0, penalty, suffix,
                            11.0, 7.0, workspace);
                }
                newNanos = System.nanoTime() - start;
            } else {
                long firstStart = System.nanoTime();
                if (reverse) {
                    for (int i = 0; i < iterations; i++) {
                        checksum += PiecewiseLinearFunction.findMinimalInsertedJobCost(prefix, 17.0, penalty,
                                suffix, 11.0, 7.0, workspace);
                    }
                    newNanos = System.nanoTime() - firstStart;
                } else {
                    for (int i = 0; i < iterations; i++) {
                        checksum += reference(prefix, 17.0, penalty, suffix, 11.0, 7.0);
                    }
                    oldNanos = System.nanoTime() - firstStart;
                }
                long secondStart = System.nanoTime();
                if (reverse) {
                    for (int i = 0; i < iterations; i++) {
                        checksum += reference(prefix, 17.0, penalty, suffix, 11.0, 7.0);
                    }
                    oldNanos = System.nanoTime() - secondStart;
                } else {
                    for (int i = 0; i < iterations; i++) {
                        checksum += PiecewiseLinearFunction.findMinimalInsertedJobCost(prefix, 17.0, penalty,
                                suffix, 11.0, 7.0, workspace);
                    }
                    newNanos = System.nanoTime() - secondStart;
                }
            }
            System.out.printf("scalar-insert benchmark: mode=%s reverse=%s iterations=%d old=%.3fs new=%.3fs"
                            + " speedup=%.3fx checksum=%.6f%n",
                    mode, reverse, iterations, oldNanos < 0 ? -1.0 : oldNanos / 1e9,
                    newNanos < 0 ? -1.0 : newNanos / 1e9,
                    oldNanos < 0 || newNanos < 0 ? -1.0 : (double) oldNanos / newNanos, checksum);
        } finally {
            Utility.curUpperBound = originalUpperBound;
        }
    }

    private static double reference(PiecewiseLinearFunction prefix, double prefixShift,
            PiecewiseLinearFunction penalty, PiecewiseLinearFunction suffix, double suffixShift,
            double bridgeCost) {
        PiecewiseLinearFunction prefixWithJob = PiecewiseLinearFunction.addShifted(prefix, prefixShift, penalty);
        prefixWithJob.minimizePrefixInPlace();
        if (prefixWithJob.isEmpty()) {
            prefixWithJob.release();
            return Utility.big_M;
        }
        double endpointLowerBound = prefixWithJob.tail.getValue(prefixWithJob.tail.end)
                + suffix.head.getValue(suffix.head.start) + bridgeCost;
        if (Utility.compareGe(endpointLowerBound, Utility.curUpperBound)) {
            prefixWithJob.release();
            return endpointLowerBound;
        }
        double value = PiecewiseLinearFunction.findMinimalShiftedSumValue(suffix, -suffixShift,
                prefixWithJob, bridgeCost);
        prefixWithJob.release();
        return Utility.isBigMValue(value) ? Utility.curUpperBound : value;
    }

    private static PiecewiseLinearFunction randomFunction(Random random, double domainEnd, int segmentCount) {
        PiecewiseLinearFunction function = new PiecewiseLinearFunction(0.0, domainEnd);
        double physicalStart = random.nextInt(Math.max(1, (int) Math.floor(domainEnd / 5.0) + 1));
        double physicalEnd = domainEnd - random.nextInt(Math.max(1, (int) Math.floor(domainEnd / 5.0) + 1));
        if (physicalEnd < physicalStart) {
            physicalEnd = physicalStart;
        }
        double current = physicalStart;
        for (int segment = 0; segment < segmentCount; segment++) {
            double end = segment == segmentCount - 1
                    ? physicalEnd
                    : current + (physicalEnd - current) * (0.05 + 0.9 * random.nextDouble());
            if (end > physicalEnd) {
                end = physicalEnd;
            }
            double slope = random.nextInt(7) - 3;
            double intercept = random.nextDouble() < 0.08
                    ? Utility.big_M
                    : random.nextDouble() * 800.0 - 300.0;
            function.addSegment(current, end, slope, intercept);
            current = end;
        }
        return function;
    }

    private static boolean same(double expected, double actual) {
        if (Utility.isBigMValue(expected) || Utility.isBigMValue(actual)) {
            return Utility.isBigMValue(expected) == Utility.isBigMValue(actual);
        }
        double scale = Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
        return Math.abs(expected - actual) <= TOLERANCE * scale;
    }
}
