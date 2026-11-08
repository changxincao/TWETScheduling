package TWETBPC.IO;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import Basic.Data;
import Common.Utility;

/** 核对合法单任务 H 点的时间回算，以及普通入口和不可行点的原有语义。 */
public final class TWETColumnTimingBoundaryTest {

    public static void main(String[] args) throws Exception {
        Utility.resetCurUpperBound(Utility.big_M);
        Data point = data("1 1\n1 0 0 1 1\nSETUP\n0 0\n0 0\n");
        check(point.CmaxH == 1.0, "fixture must naturally have H=1");
        checkTiming(point, List.of(1), 1.0, new double[] {1.0});

        Data setup = data("1 1\n2 0 0 1 1\nSETUP\n0 3\n0 0\n"
                + "SETUP_COST\n0 7\n0 0\n");
        check(setup.CmaxH == 5.0, "setup fixture must naturally have H=5");
        checkTiming(setup, List.of(1), 12.0, new double[] {5.0});

        Data ordinary = data("1 1\n1 4 4 1 1\n");
        checkTiming(ordinary, List.of(), 0.0, new double[0]);
        checkTiming(ordinary, List.of(1), 0.0, new double[] {4.0});
        Data pair = data("2 1\n1 4 4 1 1\n1 5 5 1 1\n");
        checkTiming(pair, List.of(1, 2), 0.0, new double[] {4.0, 5.0});

        // 只隔离硬窗拒绝：H 仍为1，但任务函数在H没有真实有限值，不能造合法timing。
        point.hardWindowEnd[1] = 0.5;
        point.setPenaltyFunctions();
        TWETColumnEvaluator rejected = new TWETColumnEvaluator(point);
        check(Utility.isBigMValue(rejected.evaluate(List.of(1))), "hard window must reject H");
        boolean rejectedTiming = false;
        try {
            var timing = rejected.evaluateTiming(List.of(1));
            rejectedTiming = !Double.isFinite(timing.cost) || Utility.isBigMValue(timing.cost);
        } catch (RuntimeException expected) {
            rejectedTiming = true;
        }
        check(rejectedTiming, "infeasible H was converted to a feasible completion");
        System.out.println("TWETColumnTimingBoundaryTest passed: H/setup/empty/ordinary/pair/rejected");
    }

    private static void checkTiming(Data data, List<Integer> sequence, double expectedCost,
            double[] expectedCompletions) {
        TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
        var timing = evaluator.evaluateTiming(sequence);
        check(Math.abs(timing.cost - expectedCost) < 1e-8, "wrong timing cost");
        check(Math.abs(evaluator.evaluate(sequence) - timing.cost) < 1e-8, "scalar/timing mismatch");
        check(timing.completions.length == expectedCompletions.length, "wrong completion count");
        for (int i = 0; i < expectedCompletions.length; i++) {
            check(Math.abs(timing.completions[i] - expectedCompletions[i]) < 1e-8, "wrong completion");
        }
        double last = expectedCompletions.length == 0 ? 0 : expectedCompletions[expectedCompletions.length - 1];
        double half = expectedCompletions.length == 0 ? 0 : expectedCompletions[(expectedCompletions.length - 1) / 2];
        check(timing.lastCompletion == last && timing.halfCompletion == half, "wrong timing summary");
    }

    private static Data data(String rows) throws Exception {
        Path input = Files.createTempFile("twet-timing-boundary-", ".dat");
        try {
            Files.writeString(input, rows);
            return new Data(input.toString(), true, true);
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
