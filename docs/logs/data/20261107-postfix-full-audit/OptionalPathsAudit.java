package TWETBPC.LP;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import Basic.Data;
import Common.Utility;
import Output.BPCTraceSink;
import TWETBPC.BestBpcProfiles;
import TWETBPC.TWETBPCConfig;
import TWETBPC.GC.OutsourcingPricingEngine;
import TWETBPC.GC.PricingMode;
import TWETBPC.GC.TimeIndexedGraphRank1CutPricingEngine;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterSolution;
import ilog.cplex.IloCplex;

/** 正式关闭组件的独立诊断；不修改生产类或正式算例。 */
public final class OptionalPathsAudit {
    private static Data load(String text) throws Exception {
        Path file = Files.createTempFile("twet-optional-audit-", ".dat");
        try {
            Files.writeString(file, text);
            return new Data(file.toString(), true, true);
        } finally { Files.deleteIfExists(file); }
    }

    public static void main(String[] args) throws Exception {
        Utility.resetCurUpperBound(Utility.big_M);
        if (args.length > 0 && "stabilization".equals(args[0])) stabilization();
        else enumeration(args.length > 0 && "enumeration-exact".equals(args[0]));
    }

    private static void stabilization() throws Exception {
        for (boolean clipped : new boolean[] {true, false}) {
        for (boolean enabled : new boolean[] {false, true}) {
            Utility.resetCurUpperBound(Utility.big_M);
            Data d = load("3 1\n1 1 1 1 1\n1 2 2 1 1\n1 3 3 1 1\n"
                    + "SETUP\n0 0 0 0\n0 0 0 0\n0 0 0 0\n0 0 0 0\n"
                    + "SETUP_COST\n0 4 4 4\n0 0 0 0\n0 0 0 0\n0 0 0 0\n"
                    + "OUTSOURCING_COST\n4 4 4\nOUTSOURCING_TARIFF\n1\n0 12 1 0\n");
            if (clipped) { d.CmaxH = 4; d.setPenaltyFunctions(); }
            TWETBPCConfig c = new TWETBPCConfig(); BestBpcProfiles.TIME_INDEXED_GRAPH_RANK1.apply(c);
            c.enableDualStabilization = enabled; c.outsourcingModel = "columns";
            c.timeIndexedGraphMaxExactPricingColumns = 3;
            c.enableTimeIndexedGraphDualWindow = false;
            c.timeIndexedCompletionBoundArcFixing = false;
            c.enableBPCConsoleOutput = false; c.writeBPCResultFiles = false;
            Pool pool = new Pool(d); CutPool cuts = new CutPool(); OutsourcingPool out = new OutsourcingPool(d);
            ArrayList<Integer> seeds = new ArrayList<>(); TWETColumnEvaluator evaluator = new TWETColumnEvaluator(d);
            double feasibleCost = evaluator.evaluate(List.of(1, 2, 3));
            if (Math.abs(feasibleCost - 4) > 1e-8) throw new AssertionError("feasible route cost changed");
            for (int j = 1; j <= 3; j++) seeds.add(pool.addColumn(List.of(j), evaluator.evaluate(List.of(j)), ColumnSource.MANUAL, true));
            Node node = new Node(d, seeds, seeds, 0);
            node.id = 1;
            int cut = cuts.addCut(new TWETCut(-1, TWETCutType.SUBSET_ROW, List.of(1, 2, 3), 1.0, "inherited"));
            node.activeCutIds.add(cut);
            for (List<Integer> jobs : List.of(List.of(1, 2, 3), List.of(1, 3))) {
                node.seedOutsourcingColumnIds.add(out.addColumn(jobs, ColumnSource.MANUAL, true));
            }
            LP lp = new LP(d, pool, cuts, c, out);
            try {
                lp.construct(node, seeds);
                TWETMasterSolution initial = lp.solveRelaxation();
                LP.PricingDualSnapshot dual = lp.captureTruePricingDuals();
                System.out.println("stabilization=" + enabled + " H=" + d.CmaxH + " initial=" + initial.getObjectiveValue()
                        + " pi=" + Arrays.toString(dual.jobDual) + " machine=" + dual.machineDual
                        + " out=" + dual.outsourcingColumnDual + " active=" + lp.getActiveSubsetRowPricingDuals()
                        + " snapshotSupported=" + lp.supportsDualStabilizationSnapshot());
                PC pc = new PC(c, PricingMode.TIME_INDEXED_RANK1,
                        List.of(new TimeIndexedGraphRank1CutPricingEngine(d, c), new OutsourcingPricingEngine(d, c)),
                        List.of(), new BPCTraceSink() {
                            @Override public void onPricingCall(Node n, String engine, boolean improved, int added,
                                    String message, int poolSize) {
                                System.out.println(engine + " added=" + added + " " + message);
                            }
                        });
                TWETMasterSolution result = pc.solve(lp, 4.0);
                System.out.println("stabilization=" + enabled + " final=" + result.getObjectiveValue()
                        + " observed=" + pc.getLastObservedDualBound() + " best=" + pc.getBestCertifiedNodeBound()
                        + " knownOptimum=4 active=" + lp.getActiveSubsetRowPricingDuals());
                if (pc.getLastObservedDualBound() > feasibleCost + 1e-8) {
                    throw new AssertionError("stabilization bound exceeded feasible route");
                }
            } finally { lp.closeModel(); }
        }
        }
    }

    private static String enumerationFixture(int seed) {
        int n = 7;
        Random r = new Random(2026110700L + seed);
        StringBuilder text = new StringBuilder(n + " 3\n");
        for (int j = 1; j <= n; j++) {
            int due = 2 + r.nextInt(22);
            text.append(1 + r.nextInt(3)).append(' ').append(due).append(' ').append(due)
                    .append(' ').append(1 + r.nextInt(5)).append(' ').append(1 + r.nextInt(5)).append('\n');
        }
        text.append("SETUP\n");
        for (int i = 0; i <= n; i++) for (int j = 0; j <= n; j++) text.append(j == n ? "0\n" : "0 ");
        text.append("SETUP_COST\n");
        for (int i = 0; i <= n; i++) for (int j = 0; j <= n; j++) {
            int cost = j == 0 || i == j ? 0 : i == 0 ? 1000000 : 1 + Math.abs(i - j);
            text.append(cost).append(j == n ? '\n' : ' ');
        }
        return text.toString();
    }

    private static double cost(Data d, List<Integer> seq) {
        int h = (int) Math.ceil(d.computeCommonReleaseSetupHorizon());
        double[] dp = new double[h + 1]; Arrays.fill(dp, Double.POSITIVE_INFINITY); dp[0] = 0;
        int prev = 0;
        for (int j : seq) {
            int delay = (int) (d.p[j] + d.s[prev][j]);
            double[] next = new double[h + 1]; Arrays.fill(next, Double.POSITIVE_INFINITY);
            double prefix = Double.POSITIVE_INFINITY;
            for (int t = delay; t <= h; t++) {
                prefix = Math.min(prefix, dp[t - delay]);
                next[t] = prefix + d.getSetupCost(prev, j)
                        + Math.max(0, d.d_e[j] - t) * d.w_e[j] + Math.max(0, t - d.d_l[j]) * d.w_t[j];
            }
            dp = next; prev = j;
        }
        return Arrays.stream(dp).min().getAsDouble();
    }

    private static void routes(Data d, List<Integer> sequence, int mask, double[] costs, List<Integer>[] best) {
        if (mask != 0) {
            double value = cost(d, sequence);
            if (value < costs[mask]) { costs[mask] = value; best[mask] = new ArrayList<>(sequence); }
        }
        for (int j = 1; j <= d.n; j++) if ((mask & (1 << (j - 1))) == 0) {
            sequence.add(j); routes(d, sequence, mask | (1 << (j - 1)), costs, best);
            sequence.remove(sequence.size() - 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static void enumeration(boolean exactCheck) throws Exception {
        int falseProofs = 0;
        for (int seed = 0; seed < 12; seed++) {
            Data d = load(enumerationFixture(seed));
            int size = 1 << d.n;
            double[] costs = new double[size]; Arrays.fill(costs, Double.POSITIVE_INFINITY);
            List<Integer>[] best = (List<Integer>[]) new List<?>[size];
            routes(d, new ArrayList<>(), 0, costs, best);
            double[] dp = new double[size]; Arrays.fill(dp, Double.POSITIVE_INFINITY); dp[0] = 0;
            for (int k = 0; k < 3; k++) {
                double[] next = new double[size]; Arrays.fill(next, Double.POSITIVE_INFINITY);
                for (int mask = 1; mask < size; mask++) for (int sub = mask; sub > 0; sub = (sub - 1) & mask) {
                    next[mask] = Math.min(next[mask], dp[mask ^ sub] + costs[sub]);
                }
                dp = next;
            }
            double optimum = dp[size - 1];
            TWETBPCConfig c = new TWETBPCConfig(); Pool pool = new Pool(d);
            ArrayList<Integer> ids = new ArrayList<>(); TWETColumnEvaluator evaluator = new TWETColumnEvaluator(d);
            for (int mask = 1; mask < size; mask++) {
                double actual = evaluator.evaluate(best[mask]);
                if (Math.abs(actual - costs[mask]) > 1e-6) throw new AssertionError("route cost mismatch");
                ids.add(pool.addColumn(best[mask], actual, ColumnSource.MANUAL, true));
            }
            Node node = new Node(d, ids, ids, optimum); node.minMachineCount = node.maxMachineCount = 3;
            LP lp = new LP(d, pool, new CutPool(), c, new OutsourcingPool(d));
            try {
                lp.construct(node, ids);
                RouteEnumerationFiniteMaster master = new RouteEnumerationFiniteMaster(d, c);
                Method build = RouteEnumerationFiniteMaster.class.getDeclaredMethod("buildModel", IloCplex.class,
                        LP.class, List.class, List.class); build.setAccessible(true);
                IloCplex raw = new IloCplex();
                try {
                    raw.setOut(null); raw.setParam(IloCplex.Param.Threads, 1);
                    build.invoke(master, raw, lp, ids, List.of()); raw.solve();
                    var result = master.solve(lp, ids, List.of(), 30);
                    System.out.printf(Locale.ROOT, "seed=%d defaultGap=%.9g status=%s detail=%s ub=%.9f lb=%.9f gap=%.9g oracle=%.9f proven=%s result=%.9f%n",
                            seed, raw.getParam(IloCplex.Param.MIP.Tolerances.MIPGap), raw.getStatus(), raw.getCplexStatus(),
                            raw.getObjValue(), raw.getBestObjValue(), raw.getMIPRelativeGap(), optimum,
                            result.isProven(), result.getObjective());
                    if (result.isProven() && result.getObjective() > optimum + 1e-6) falseProofs++;
                    if (exactCheck) {
                        raw.setParam(IloCplex.Param.MIP.Tolerances.MIPGap, 0.0);
                        raw.setParam(IloCplex.Param.MIP.Tolerances.AbsMIPGap, 0.0);
                        if (!raw.solve()) throw new AssertionError("zero-gap re-solve failed");
                        System.out.printf(Locale.ROOT, "zeroGap seed=%d detail=%s ub=%.9f lb=%.9f oracle=%.9f%n",
                                seed, raw.getCplexStatus(), raw.getObjValue(), raw.getBestObjValue(), optimum);
                        if (Math.abs(raw.getObjValue() - optimum) > 1e-6
                                || Math.abs(raw.getBestObjValue() - optimum) > 1e-6) {
                            throw new AssertionError("zero-gap solve differs from independent oracle");
                        }
                    }
                } finally { raw.end(); }
            } finally { lp.closeModel(); }
        }
        if (falseProofs == 0) throw new AssertionError("finite-master tolerance defect not reproduced");
    }
}
