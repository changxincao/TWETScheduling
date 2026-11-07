package TWETBPC.GC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import Basic.Data;
import Common.Utility;
import TWETBPC.BPCAlgorithmProfile;
import TWETBPC.BestBpcProfiles;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.Util.TimeIndexedArcSet;

/** 隔离审查诊断，不修改生产配置、正式数据或远端。 */
public final class PostfixAudit {
    private static Data load(String input) throws Exception {
        Path file = Files.createTempFile("twet-postfix-audit-", ".dat");
        try {
            Files.writeString(file, input);
            return new Data(file.toString(), true, true);
        } finally { Files.deleteIfExists(file); }
    }

    public static void main(String[] args) throws Exception {
        Utility.resetCurUpperBound(Utility.big_M);
        String mode = args.length == 0 ? "oracle" : args[0];
        if ("debug".equals(mode) || "normal".equals(mode)) debugFixing("debug".equals(mode));
        else if ("segmented-contract".equals(mode)) segmentedContract();
        else oracle();
    }

    private static void debugFixing(boolean debug) throws Exception {
        Data d = load("1 1\n1 1 1 1 1\nSETUP\n0 0\n0 0\n");
        TWETBPCConfig c = new TWETBPCConfig();
        c.useTimeIndexedGraphPricing = true;
        c.enableBPCConsoleOutput = false;
        c.debugIgnorePricingOnlyArcsAtNode = debug ? 1 : -1;
        Pool pool = new Pool(d);
        int id = pool.addColumn(List.of(1), new TWETColumnEvaluator(d).evaluate(List.of(1)), ColumnSource.MANUAL, true);
        Node node = new Node(d, List.of(id), List.of(id), 0);
        node.id = 1;
        LP lp = new LP(d, pool, new CutPool(), c, new OutsourcingPool(d));
        try {
            lp.construct(node, List.of(id));
            check(lp.solveRelaxation().getStatus() == TWETMasterStatus.LP_RELAXATION, "RMP not solved");
            check(!node.isArcForbidden(0, 1), "source processing arc must be available");
            System.out.println("debug=" + debug + " assertions=" + PostfixAudit.class.desiredAssertionStatus()
                    + " H=" + d.CmaxH + " node=" + node.id + " LB=" + lp.getLastSolution().getObjectiveValue());
            var result = TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing(d, c, lp, 1.0);
            check(result.isAvailable(), "fixing unexpectedly skipped");
            System.out.println(result.summary());
        } finally { lp.closeModel(); }
    }

    /** 仅验证已证实重复写的分段计数后果，不声称运行了巨型TI网络。 */
    private static void segmentedContract() throws Exception {
        Data d = load("1 1\n1 1 1 1 1\n");
        TimeIndexedArcSet set = new TimeIndexedArcSet(2, Integer.MAX_VALUE / 4 + 1);
        check(set.usesSegmentedStorage(), "expected segmented storage");
        set.setKnownAbsent(0, 1, 1);
        set.setKnownAbsent(0, 1, 1);
        long cached = set.cardinality();
        Node node = new Node(d, List.of(), List.of(), 0);
        long reported = node.mergeTimeIndexedPricingOnlyArcSet(set);
        int actual = 0;
        for (int from = 0; from < 2; from++) for (int to = 0; to < 2; to++) {
            for (int t = 0; t <= 1; t++) if (node.isTimeIndexedPricingOnlyArcForbidden(from, to, t)) actual++;
        }
        System.out.println("segmented repeated known-absent: cache=" + cached + " node=" + reported + " actualBits=" + actual);
        check(cached == 2 && reported == 2 && actual == 1, "contract violation consequence changed");
    }

    private static String fixture(int id) {
        Random r = new Random(2026110700L + id);
        int n = 4 + id % 2, m = 1 + id % 3;
        StringBuilder s = new StringBuilder(n + " " + m + "\n");
        for (int j = 1; j <= n; j++) {
            int p = 1 + r.nextInt(3), center = 3 + r.nextInt(11), width = id % 3 == 0 ? 0 : id % 3 == 1 ? 2 : 5;
            s.append(p).append(' ').append(center).append(' ').append(center + width).append(' ')
                    .append(1 + r.nextInt(3)).append(' ').append(1 + r.nextInt(3)).append('\n');
        }
        s.append("SETUP\n");
        for (int i = 0; i <= n; i++) for (int j = 0; j <= n; j++) {
            int v = j == 0 || i == j ? 0 : i == 0 ? 1 : id % 2 == 0 ? (i % 2 == j % 2 ? 1 : 4) : 1 + Math.abs(i - j);
            s.append(v).append(j == n ? '\n' : ' ');
        }
        s.append("SETUP_COST\n");
        for (int i = 0; i <= n; i++) for (int j = 0; j <= n; j++) {
            int v = j == 0 || i == j || id % 4 == 0 ? 0 : i == 0 ? 1 : id % 2 == 0 ? (i % 2 == j % 2 ? 1 : 4) : 1 + Math.abs(i - j);
            s.append(v).append(j == n ? '\n' : ' ');
        }
        if (id >= 4) {
            s.append("OUTSOURCING_COST\n");
            double total = 0;
            for (int j = 1; j <= n; j++) {
                double b = 4 + r.nextInt(13) + (id % 2 == 0 ? 0 : 0.5);
                total += b; s.append(b).append(j == n ? '\n' : ' ');
            }
            s.append("OUTSOURCING_TARIFF\n");
            if (id % 2 == 0) s.append("1\n0 ").append(total + 1).append(" 0.8 0\n");
            else {
                double split = Math.floor(total / 2);
                s.append("2\n0 ").append(split).append(" 1 0\n").append(split).append(' ')
                        .append(total + 1).append(" 0.5 ").append(split * 0.5).append('\n');
            }
        }
        return s.toString();
    }

    private static double sequenceCost(Data d, List<Integer> seq) {
        int h = (int) Math.ceil(d.computeCommonReleaseSetupHorizon());
        double[] dp = new double[h + 1]; Arrays.fill(dp, Double.POSITIVE_INFINITY); dp[0] = 0;
        int from = 0;
        for (int j : seq) {
            int delay = (int) (d.p[j] + d.s[from][j]);
            double[] next = new double[h + 1]; Arrays.fill(next, Double.POSITIVE_INFINITY);
            double prefix = Double.POSITIVE_INFINITY;
            for (int t = delay; t <= h; t++) {
                prefix = Math.min(prefix, dp[t - delay]);
                next[t] = prefix + d.getSetupCost(from, j)
                        + Math.max(0, d.d_e[j] - t) * d.w_e[j] + Math.max(0, t - d.d_l[j]) * d.w_t[j];
            }
            dp = next; from = j;
        }
        return Arrays.stream(dp).min().getAsDouble();
    }

    private static void routes(Data d, List<Integer> seq, int mask, double[] cost) {
        if (mask != 0) cost[mask] = Math.min(cost[mask], sequenceCost(d, seq));
        for (int j = 1; j <= d.n; j++) if ((mask & (1 << (j - 1))) == 0) {
            seq.add(j); routes(d, seq, mask | (1 << (j - 1)), cost); seq.remove(seq.size() - 1);
        }
    }

    private static double optimum(Data d, int id) {
        int size = 1 << d.n;
        double[] route = new double[size]; Arrays.fill(route, Double.POSITIVE_INFINITY); route[0] = 0;
        routes(d, new ArrayList<>(), 0, route);
        double[] dp = new double[size]; Arrays.fill(dp, Double.POSITIVE_INFINITY); dp[0] = 0;
        for (int k = 0; k < d.m; k++) {
            double[] next = dp.clone();
            for (int mask = 1; mask < size; mask++) for (int part = mask; part > 0; part = (part - 1) & mask) {
                next[mask] = Math.min(next[mask], dp[mask ^ part] + route[part]);
            }
            dp = next;
        }
        if (id < 4) return dp[size - 1];
        double total = 0; for (int j = 1; j <= d.n; j++) total += d.outsourcingCost[j];
        double answer = Double.POSITIVE_INFINITY;
        for (int mask = 0; mask < size; mask++) {
            double q = 0;
            for (int j = 1; j <= d.n; j++) if ((mask & (1 << (j - 1))) == 0) q += d.outsourcingCost[j];
            double out = id % 2 == 0 ? 0.8 * q : q <= Math.floor(total / 2) ? q : 0.5 * q + 0.5 * Math.floor(total / 2);
            answer = Math.min(answer, dp[mask] + out);
        }
        return answer;
    }

    private static void oracle() throws Exception {
        int count = 0, mismatches = 0, nonRoot = 0;
        for (int id = 0; id < 12; id++) {
            double optimum = optimum(load(fixture(id)), id);
            for (String model : id < 4 ? new String[] {"masterVariables"} : new String[] {"masterVariables", "columns"}) {
                for (BPCAlgorithmProfile profile : new BPCAlgorithmProfile[] {BestBpcProfiles.NG_DSSR,
                        BestBpcProfiles.TIME_INDEXED_GRAPH, BestBpcProfiles.TIME_INDEXED_GRAPH_RANK1}) {
                    for (boolean cluster : new boolean[] {false, true}) {
                        Data d = load(fixture(id)); Utility.resetCurUpperBound(Utility.big_M);
                        TWETBPCConfig c = new TWETBPCConfig(); profile.apply(c); c.outsourcingModel = model;
                        c.enableClusterBranching = cluster; c.structuredArcStrictTypePriority = cluster;
                        c.timeIndexedCompletionBoundNodeArcFixingMaxDepth = Integer.MAX_VALUE;
                        c.runALNSForSeed = false; c.reuseConfiguredBestSolution = false;
                        c.writeBPCResultFiles = false; c.enableBPCConsoleOutput = false;
                        c.solveTimeLimitSeconds = 30; c.maxNodes = 10000;
                        ArrayList<List<Integer>> initial = new ArrayList<>();
                        for (int j = 1; j <= d.n; j++) initial.add(List.of(j));
                        ArrayList<ArrayList<Integer>> incumbent = new ArrayList<>();
                        for (int k = 0; k < d.m; k++) incumbent.add(new ArrayList<>());
                        ArrayList<Integer> outside = new ArrayList<>();
                        if (id >= 4) { for (int j = 1; j <= d.n; j++) outside.add(j); }
                        else {
                            double[] load = new double[d.m];
                            for (int j = 1; j <= d.n; j++) {
                                int machine = 0;
                                for (int k = 1; k < d.m; k++) if (load[k] < load[machine]) machine = k;
                                double maxSetup = 0; for (int i = 0; i <= d.n; i++) maxSetup = Math.max(maxSetup, d.s[i][j]);
                                incumbent.get(machine).add(j); load[machine] += d.p[j] + maxSetup;
                            }
                        }
                        c.fixedInitialColumnSeed = new FixedInitialColumnSeed(initial, incumbent, outside);
                        TWETSolveResult result = new TWETBPCSolver(d, c).solve();
                        boolean match = Math.abs(result.getIncumbentCost() - optimum) <= 1e-5
                                && Math.abs(result.getBestBound() - optimum) <= 1e-5;
                        count++; if (!match) mismatches++; if (result.getProcessedNodes() > 1) nonRoot++;
                        System.out.printf(Locale.ROOT, "case=%d n=%d m=%d out=%s tariff=%s alg=%s cluster=%s opt=%.9f ub=%.9f lb=%.9f nodes=%d status=%s match=%s%n",
                                id, d.n, d.m, id < 4 ? "none" : model, id % 2 == 0 ? "linear" : "concave", profile.getName(),
                                cluster, optimum, result.getIncumbentCost(), result.getBestBound(), result.getProcessedNodes(), result.getStatus(), match);
                    }
                }
            }
        }
        System.out.println("independent original-cost oracle count=" + count + " mismatches=" + mismatches + " nonRoot=" + nonRoot);
        check(mismatches == 0, "independent optimum mismatches");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
