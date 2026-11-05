package HEU;

import Basic.Data;
import Common.Configure;
import Common.Utility;
import Common.formal.FormalExperimentDataFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** 插入评价与独立时间DP对拍，并验证VND改进后停滞重置。 */
public final class ALNSRepairRegressionTest {
    private static final String INSERTION = "2 1\n1 10 10 1 1\n1 30 30 1 1\n"
            + "SETUP\n0 0 0\n0 0 0\n0 0 0\nOUTSOURCING_COST\n5 5\n"
            + "OUTSOURCING_TARIFF\n1\n0 11 1 0\n";

    public static void main(String[] args) throws Exception {
        Configure.SegmentPool = false;
        Path file = Files.createTempFile("alns-regression-", ".dat");
        try {
            Files.writeString(file, INSERTION);
            Data d = FormalExperimentDataFactory.load(file);
            Utility.resetCurUpperBound(Utility.big_M);
            Solution s = solution(d, List.of(1, 2));
            s.removeJobs(List.of(1));
            near(InsertionOperator.evaluateInsertionCost(s, 0, -1, 1), 0, "known insertion");
            new GreedyInsertion().insert(s, List.of(1));
            near(s.curCost, 0, "greedy repair");
            require(s.outsourcedJobs.isEmpty(), "Repair outsourced a free internal insertion");

            int checked = 0;
            for (boolean outsourcing : new boolean[] {false, true}) {
                Files.writeString(file, smallInput(outsourcing));
                d = FormalExperimentDataFactory.load(file);
                for (int job = 1; job <= d.n; job++) {
                    ArrayList<Integer> rest = new ArrayList<>();
                    for (int j = 1; j <= d.n; j++) if (j != job) rest.add(j);
                    List<List<Integer>> sequences = new ArrayList<>();
                    enumerate(rest, new ArrayList<>(), sequences);
                    for (List<Integer> seq : sequences) {
                        Utility.resetCurUpperBound(Utility.big_M);
                        double base = dp(d, seq);
                        if (!Double.isFinite(base)) continue;
                        s = solution(d, seq);
                        near(s.cost[0], base, "base route");
                        for (int pos = -1; pos < seq.size(); pos++) {
                            ArrayList<Integer> inserted = new ArrayList<>(seq);
                            inserted.add(pos + 1, job);
                            double expected = dp(d, inserted);
                            double actual = InsertionOperator.evaluateInsertionCost(s, 0, pos, job);
                            if (Double.isFinite(expected) && Math.abs(actual + base - expected) > 1e-6) {
                                System.out.println("MISMATCH out=" + outsourcing + " old=" + seq + " job=" + job
                                        + " pos=" + pos + " H=" + d.CmaxH + " base=" + base
                                        + " full=" + solution(d, inserted).cost[0]);
                            }
                            if (Double.isFinite(expected)) near(actual + base, expected, "insert " + inserted);
                            else require(Utility.isBigMValue(actual), "Infeasible insertion accepted");
                            checked++;
                        }
                    }
                }
            }
            verifyStagnation(file);
            System.out.println("ALNSRepairRegressionTest passed: known repair, " + checked
                    + " insertion/DP comparisons, VND improvement resets stagnation and perturbation");
        } finally {
            Utility.resetCurUpperBound(Utility.big_M);
            Files.deleteIfExists(file);
        }
    }

    private static String smallInput(boolean outsourcing) {
        StringBuilder text = new StringBuilder("4 1\n1 5 7 1 2\n2 12 12 2 1\n1 19 23 1 1\n2 28 30 2 2\n");
        for (String block : List.of("SETUP", "SETUP_COST")) {
            text.append(block).append('\n');
            for (int i = 0; i <= 4; i++) {
                for (int j = 0; j <= 4; j++) text.append(i == j || j == 0 ? 0 :
                        (block.equals("SETUP") ? 1 + j % 2 : 2 + j)).append(' ');
                text.append('\n');
            }
        }
        if (outsourcing) text.append("OUTSOURCING_COST\n100 100 100 100\nOUTSOURCING_TARIFF\n1\n0 401 1 0\n");
        return text.toString();
    }

    private static void enumerate(List<Integer> remaining, ArrayList<Integer> prefix, List<List<Integer>> out) {
        out.add(new ArrayList<>(prefix));
        for (int job : remaining) {
            if (prefix.contains(job)) continue;
            prefix.add(job);
            enumerate(remaining, prefix, out);
            prefix.remove(prefix.size() - 1);
        }
    }

    static Solution solution(Data d, List<Integer> seq) {
        Solution s = new Solution(d);
        ArrayList<ArrayList<Integer>> routes = new ArrayList<>();
        routes.add(new ArrayList<>(seq));
        s.setSequence(routes);
        s.initialize_function();
        s.updateInformationM(0);
        return s;
    }

    /** 测试数据为整数时间，直接枚举完工时间，不使用PWLF拼接。 */
    static double dp(Data d, List<Integer> seq) {
        int horizon = (int)Math.floor(d.CmaxH);
        double[] previous = new double[horizon + 1];
        Arrays.fill(previous, Double.POSITIVE_INFINITY);
        previous[0] = 0;
        int before = 0;
        for (int job : seq) {
            double[] next = new double[horizon + 1];
            Arrays.fill(next, Double.POSITIVE_INFINITY);
            int lag = (int)(d.s[before][job] + d.p[job]);
            double best = Double.POSITIVE_INFINITY;
            for (int t = 0; t <= horizon; t++) {
                if (t >= lag) best = Math.min(best, previous[t-lag]);
                if (t < d.hardWindowStart[job] || t > d.hardWindowEnd[job]) continue;
                next[t] = best + d.w_e[job] * Math.max(0, d.d_e[job]-t)
                        + d.w_t[job] * Math.max(0, t-d.d_l[job]) + d.getSetupCost(before, job);
            }
            previous = next;
            before = job;
        }
        return Arrays.stream(previous).min().orElse(Double.POSITIVE_INFINITY);
    }

    private static void verifyStagnation(Path file) throws Exception {
        Files.writeString(file, "4 1\n1 2 2 1 1\n1 4 4 1 1\n1 6 6 1 1\n1 8 8 1 1\nSETUP\n"
                + "0 0 0 0 0\n0 0 0 0 0\n0 0 0 0 0\n0 0 0 0 0\n0 0 0 0 0\n");
        Data d = FormalExperimentDataFactory.load(file);
        Utility.resetCurUpperBound(Utility.big_M);
        Solution s = solution(d, List.of(4,3,2,1));
        near(s.curCost, 12, "initial cost");
        Move.Recordreset();
        CountingRandom random = new CountingRandom();
        Random oldRandom = EngineALNS.rng;
        long oldTime = EngineALNS.maxRuntimeMillis;
        int oldLimit = EngineALNS.maxNoImpIterN, oldInterval = EngineALNS.ratioChangeNoImpIterN;
        try {
            EngineALNS.rng = random;
            EngineALNS.maxRuntimeMillis = 0;
            EngineALNS.maxNoImpIterN = 1;
            EngineALNS.ratioChangeNoImpIterN = 1;
            new EngineALNS(d, s).search();
            near(d.configure.bestSolution.curCost, 0, "final best");
            require(random.upper.size() == 2, "Improving iteration counted as stagnant: " + random.upper);
            near(random.upper.get(1), EngineALNS.defaultRemoveRatioU, "perturbation reset");
        } finally {
            EngineALNS.rng = oldRandom;
            EngineALNS.maxRuntimeMillis = oldTime;
            EngineALNS.maxNoImpIterN = oldLimit;
            EngineALNS.ratioChangeNoImpIterN = oldInterval;
        }
    }

    private static final class CountingRandom extends Random {
        final List<Double> upper = new ArrayList<>();
        CountingRandom() { super(0); }
        @Override public double nextDouble(double lo, double hi) {
            upper.add(hi);
            return super.nextDouble(lo,hi);
        }
    }

    private static void near(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual-expected) <= 1e-6,
                message + ": " + actual + " != " + expected);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
