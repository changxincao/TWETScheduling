package TWETBPC.GC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;

/** 只读调用当前实现，隔离记录全面审查发现；不修改正式算例和生产类。 */
public final class ReviewDiagnostics {
    public static void main(String[] args) throws Exception {
        Utility.resetCurUpperBound(Utility.big_M);
        fractionalCompletionCache();
        naturalFractionalPricing();
        phaseOneHelperObjective();
        candidateHeapRetention();
        optionalAdjacencyReducedCost();
        cleanupDistanceReuse();
    }

    private static Data data(String rows) throws Exception {
        Path p = Files.createTempFile("twet-full-review-", ".dat");
        try {
            Files.writeString(p, rows);
            return new Data(p.toString(), true, true);
        } finally {
            Files.deleteIfExists(p);
        }
    }

    private static Data twoJobs() throws Exception {
        return data("2 2\n1 5 5 1 1\n1 6 6 1 1\nSETUP\n0 0 0\n0 0 0\n0 0 0\n");
    }

    private static PiecewiseLinearFunction constant(double h, double lo, double hi, double v) {
        PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, h);
        f.addSegment(lo, hi, 0, v);
        return f;
    }

    private static void fractionalCompletionCache() throws Exception {
        for (double h : new double[] {17.5, 18.0}) {
            Data d = twoJobs();
            d.CmaxH = h;
            CompletionBoundCalculator.Bounds b = new CompletionBoundCalculator.Bounds(d.n, h);
            b.forwardUByJob[1] = constant(h, 1, h, 0);
            LP lp = fixedDualLp(d, new TWETBPCConfig(), new double[d.n + 1], 0);
            CompletionBoundCalculator calc = new CompletionBoundCalculator(d, lp, h,
                    null, null, null, CompletionBoundCalculator.QueueOrdering.FIFO, false);
            invoke(calc, "buildDiscreteCaches", new Class<?>[] {CompletionBoundCalculator.Bounds.class}, b);
            GCNGBBStyleBidirectionalNgDssr ng = new GCNGBBStyleBidirectionalNgDssr(d, new TWETBPCConfig());
            set(ng, "completionBounds", b);
            set(ng, "pricingHorizon", h);
            PiecewiseLinearFunction frontier = constant(h, 12.5, 17.4, -0.4);
            boolean pruned = (Boolean) invoke(ng, "isBackwardCompletionBoundScalarPruned",
                    new Class<?>[] {int.class, PiecewiseLinearFunction.class, double.class, double.class},
                    1, frontier, -0.4, 0.0);
            System.out.printf("fractional-cache H=%.1f P=17.4 ceilCache=%s trueMinimum=-0.4 pruned=%s%n",
                    h, b.forwardUBeforeCeil(1, 17.4), pruned);
            check(pruned == (h == 17.5), "fractional cache reproduction changed");
            frontier.release();
            b.forwardUByJob[1].release();
            lp.closeModel();
        }
    }

    private static LP fixedDualLp(Data d, TWETBPCConfig c, double[] duals, double machine) throws Exception {
        LP lp = new LP(d, new Pool(d), new CutPool(), c, new OutsourcingPool(d));
        lp.construct(new Node(d, new ArrayList<>(), new ArrayList<>(), 0), new ArrayList<>());
        set(lp, "jobDual", duals.clone());
        set(lp, "machineDual", machine);
        return lp;
    }

    private static void naturalFractionalPricing() throws Exception {
        String input = "3 3\n1 17 17 1 1\n1 17 17 1 1\n1 8 8 1 1\nSETUP\n"
                + "0 0 1 1\n0 0 1 1\n0 1 0 1\n0 1 1 0\nSETUP_COST\n"
                + "0 0 20 20\n0 0 20 20\n0 20 0 20\n0 20 20 0\n";
        for (boolean scalar : new boolean[] {false, true}) {
            Data d = data(input);
            TWETBPCConfig c = new TWETBPCConfig();
            c.enableBidirectionalPricing = true;
            c.useGCNGBBStyleNgDssrPricing = true;
            c.bidirectionalMidpointProbe = false;
            c.bidirectionalMidpointProbeDssrImbalanceThreshold = Double.POSITIVE_INFINITY;
            c.bidirectionalCompletionBoundRelaxation = "allCycles";
            c.bidirectionalCompletionBoundScalarPruning = scalar;
            LP lp = fixedDualLp(d, c, new double[] {0, 0.4, 0.5, 0.5}, 0);
            try {
                GCNGBBStyleBidirectionalNgDssr ng = new GCNGBBStyleBidirectionalNgDssr(d, c);
                List<TWETColumn> columns = ng.solve(lp);
                double witness = new TWETColumnEvaluator(d).evaluate(List.of(1)) - lp.getJobDual(1) - lp.getMachineDual();
                check(Math.abs(witness + 0.4) < 1e-6, "independent singleton witness changed");
                System.out.printf("natural-fractional NG scalar=%s dataH=%.1f pricingH=%s midpoint=%s cols=%d cert=%s witnessRc=%s%n",
                        scalar, d.CmaxH, get(ng, "pricingHorizon"), get(ng, "tMid"), columns.size(),
                        ng.getLastRelaxedRoundBestReducedCost(), witness);
                check(scalar ? columns.isEmpty() && ng.getLastRelaxedRoundBestReducedCost() >= 0
                        : !columns.isEmpty() && Math.abs(ng.getLastRelaxedRoundBestReducedCost() + 0.4) < 1e-6,
                        "natural fractional pricing comparison changed");
            } finally {
                lp.closeModel();
            }
        }
    }

    private static void phaseOneHelperObjective() throws Exception {
        Data d = data("2 1\n1 0 3 1 1\n1 1 1 1 1\nSETUP\n"
                + "0 0 0\n0 0 0\n0 0 0\nOUTSOURCING_COST\n3 3\n");
        TWETBPCConfig c = new TWETBPCConfig();
        c.timeIndexedCompletionBoundScalarEnhancement = true;
        c.timeIndexedCompletionBoundWindowTightening = true;
        c.timeIndexedCompletionBoundInRoundArcFixing = true;
        LP lp = fixedDualLp(d, c, new double[] {0, 1, 1}, 0);
        try {
            lp.getNode().depth = 3;
            lp.getNode().forbidArc(1, lp.getNode().sinkId());
            lp.getNode().forbidOutsourcingJob(1);
            lp.getNode().forbidOutsourcingJob(2);
            lp.setFeasibilityRepairMode(true);
            lp.setFeasibilityPhaseOneObjectiveMode(true);
            double[] start = Arrays.copyOf(d.hardWindowStart, d.n + 1);
            double[] end = Arrays.copyOf(d.hardWindowEnd, d.n + 1);
            TimeIndexedScalarCompletionBound bound = TimeIndexedScalarCompletionBound.build(d, c, lp,
                    d.CmaxH, start, end);
            check(bound != null, "Phase-I helper unexpectedly excluded");
            bound.tightenWindowsAfterZeroReducedCostArcFixing(start, end);
            double suffix = bound.suffixLowerBoundAfterFloor(1, 1);
            System.out.printf("phase-one-helper dataH=%.1f suffixAtA1=%.6f feasiblePhaseOneSuffix=-1.0 inflated=%s%n",
                    d.CmaxH, suffix, suffix > -1 + 1e-6);
            check(Math.abs(suffix) < 1e-6, "Phase-I inflated helper bound was not reproduced");
        } finally {
            lp.closeModel();
        }
    }

    private static void candidateHeapRetention() throws Exception {
        Data d = data("2 2\n500 1000 1000 1 1\n500 1500 1500 1 1\nSETUP\n"
                + "0 0 0\n0 0 0\n0 0 0\nOUTSOURCING_COST\n1000 1000\n");
        d.CmaxH = 2000;
        Arrays.fill(d.hardWindowStart, 0);
        Arrays.fill(d.hardWindowEnd, 2000);
        d.setPenaltyFunctions();
        TWETBPCConfig c = new TWETBPCConfig();
        c.useTimeIndexedGraphPricing = true;
        c.enableTimeIndexedGraphDualWindow = false;
        c.timeIndexedGraphMaxExactPricingColumns = 300;
        LP lp = fixedDualLp(d, c, new double[] {0, 1000, 1000}, -1000);
        try {
            TimeIndexedGraphPricingEngine engine = new TimeIndexedGraphPricingEngine(d, c);
            Class<?> solverType = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$TimeIndexedGraphSolver");
            Constructor<?> ctor = solverType.getDeclaredConstructor(TimeIndexedGraphPricingEngine.class, LP.class);
            ctor.setAccessible(true);
            Object solver = ctor.newInstance(engine, lp);
            invoke(solver, "solve", new Class<?>[0]);
            int live = ((Map<?, ?>) get(solver, "candidateBySignature")).size();
            int retained = ((PriorityQueue<?>) get(solver, "candidateHeap")).size();
            System.out.printf("ti-candidate-heap K=300 liveSignatures=%d heapObjects=%d staleObjects=%d%n",
                    live, retained, retained - live);
            check(retained > 300 && retained > live, "heap retention was not reproduced");
        } finally {
            lp.closeModel();
        }
    }

    private static void optionalAdjacencyReducedCost() throws Exception {
        Data d = data("2 2\n1 5 5 1 1\n1 6 6 1 1\nSETUP\n"
                + "0 0 0\n0 0 0\n0 0 0\nSETUP_COST\n0 0 0\n0 0 10\n0 10 0\n");
        TWETBPCConfig c = new TWETBPCConfig();
        c.enableUndirectedAdjacencyBranching = true;
        c.enableBPCConsoleOutput = false;
        Pool pool = new Pool(d);
        TWETColumnEvaluator evaluator = new TWETColumnEvaluator(d);
        List<Integer> ids = new ArrayList<>();
        for (int job = 1; job <= 2; job++) {
            ids.add(pool.addColumn(List.of(job), evaluator.evaluate(List.of(job)), ColumnSource.MANUAL, true));
        }
        ids.add(pool.addColumn(List.of(1, 2), evaluator.evaluate(List.of(1, 2)), ColumnSource.MANUAL, true));
        int pseudo = pool.addColumn(List.of(1, 2, 1), evaluator.evaluate(List.of(1, 2, 1)), ColumnSource.MANUAL, false);
        ids.add(pseudo);
        Node node = new Node(d, ids, new ArrayList<>(), 0);
        node.requireAdjacencyPair(1, 2);
        LP lp = new LP(d, pool, new CutPool(), c, new OutsourcingPool(d));
        try {
            lp.construct(node, ids);
            lp.solveRelaxation();
            TWETColumn col = pool.getColumn(pseudo);
            double master = lp.getColumnReducedCost(pseudo);
            double expanded = lp.computeReducedCost(col, lp.captureTruePricingDuals());
            System.out.printf("optional-adjacency pseudo=%s pairDual=%.6f cplexRC=%.6f expandedArcRC=%.6f%n",
                    col.getSequence(), lp.getArcDual(1, 2), master, expanded);
            check(lp.getArcDual(1, 2) > 0 && expanded < master - 1e-6,
                    "adjacency presence/traversal mismatch was not reproduced");
        } finally {
            lp.closeModel();
        }
    }

    private static void cleanupDistanceReuse() throws Exception {
        Data d = twoJobs();
        TWETBPCConfig c = new TWETBPCConfig();
        c.useTimeIndexedGraphPricing = true;
        c.enableTimeIndexedGraphDualWindow = false;
        LP lp = fixedDualLp(d, c, new double[] {0, 100, 100}, -100);
        try {
            Class<?> type = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$ArcFixingSolver");
            Class<?> stat = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$StaticPricingData");
            Constructor<?> ctor = type.getDeclaredConstructor(Data.class, TWETBPCConfig.class,
                    LP.class, double.class, stat);
            ctor.setAccessible(true);
            Object solver = ctor.newInstance(d, c, lp, 1000000.0, null);
            invoke(solver, "computeForwardDistances", new Class<?>[0]);
            invoke(solver, "computeBackwardDistances", new Class<?>[0]);
            double[] f = ((double[]) get(solver, "forward")).clone();
            double[] b = ((double[]) get(solver, "backward")).clone();
            invoke(solver, "computeForwardDistances", new Class<?>[0]);
            invoke(solver, "computeBackwardDistances", new Class<?>[0]);
            check(Arrays.equals(f, (double[]) get(solver, "forward"))
                    && Arrays.equals(b, (double[]) get(solver, "backward")), "unchanged graph changed distances");
            Object result = invoke(solver, "apply", new Class<?>[0]);
            var r = (TimeIndexedGraphPricingEngine.ArcFixingResult) result;
            System.out.printf("ti-cleanup noRcFix process=%d idle=%d end=%d repeatedDistancesEqual=true%n",
                    r.getProcessFixedLong(), r.getIdleFixedLong(), r.getEndFixedLong());
            check(r.getProcessFixedLong() + r.getIdleFixedLong() + r.getEndFixedLong() == 0,
                    "expected no initial RC-fixed arc");
        } finally {
            lp.closeModel();
        }
    }

    private static Object get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void set(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static Object invoke(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
