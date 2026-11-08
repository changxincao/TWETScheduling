package TWETBPC.GC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Util.SequenceSignature;

/** 检查SRI候选压缩保持原top-K与顺序，并限制过期引用的保留数量。 */
public final class TimeIndexedRank1CandidateHeapTest {

    public static void main(String[] args) throws Exception {
        Utility.resetCurUpperBound(Utility.big_M);
        boolean bounded = args.length == 0 || !"snapshot".equals(args[0]);
        if (bounded) testUpdates();
        for (String memory : List.of("full", "node", "arc")) {
            testPricing(memory, bounded);
        }
        System.out.println("TimeIndexedRank1CandidateHeapTest passed bounded=" + bounded);
    }

    private static void testUpdates() throws Exception {
        Data data = data();
        for (int k : new int[] {1, 2, 7, 50, 300}) {
            for (boolean heuristic : new boolean[] {false, true}) {
                TWETBPCConfig config = config(k);
                LP lp = lp(data, config, "full");
                try {
                    Object solver = solver(data, config, lp, heuristic);
                    Method remember = method(solver, "rememberCandidate", SequenceSignature.class,
                            ArrayList.class, double.class, double.class);
                    int limit = heuristic ? Math.min(50, k) : k;
                    Map<List<Integer>, double[]> expected = new HashMap<>();
                    int nextId = 0;
                    Random random = new Random(20261108L);
                    for (int step = 0; step < 1200; step++) {
                        ArrayList<Integer> sequence = new ArrayList<>(List.of(1 + random.nextInt(3),
                                1 + random.nextInt(3)));
                        double rc = -random.nextInt(60);
                        double cost = 1000.0 + step;
                        double[] previous = expected.get(sequence);
                        if (previous == null || Utility.compareGt(previous[0], rc)) {
                            expected.put(sequence, new double[] {rc, nextId++, cost});
                            if (expected.size() > limit) {
                                var worst = expected.entrySet().stream().max(Comparator
                                        .<Map.Entry<List<Integer>, double[]>>comparingDouble(e -> e.getValue()[0])
                                        .thenComparingDouble(e -> e.getValue()[1])).orElseThrow();
                                expected.remove(worst.getKey());
                            }
                        }
                        remember.invoke(solver, new SequenceSignature(sequence), sequence, cost, rc);
                        Map<?, ?> actual = (Map<?, ?>) get(solver, "candidateBySignature");
                        PriorityQueue<?> heap = (PriorityQueue<?>) get(solver, "candidateHeap");
                        check(actual.size() == expected.size(), "top-K count changed");
                        check(heap.size() <= 2L * actual.size(), "stale heap exceeded its bound");
                        for (Object candidate : actual.values()) {
                            double[] wanted = expected.get(get(candidate, "sequence"));
                            check(wanted != null && wanted[0] == (Double) get(candidate, "reducedCost")
                                    && wanted[1] == (Integer) get(candidate, "id")
                                    && wanted[2] == (Double) get(candidate, "cost"), "candidate changed");
                        }
                    }
                } finally { lp.closeModel(); }
            }
        }
        TWETBPCConfig disabled = config(0);
        disabled.maxExactPricingColumns = 0;
        LP empty = lp(data, disabled, "full");
        try {
            for (boolean heuristic : new boolean[] {false, true}) {
                Object solver = solver(data, disabled, empty, heuristic);
                check(((List<?>) method(solver, "solve").invoke(solver)).isEmpty(), "zero-K returned columns");
                check(((PriorityQueue<?>) get(solver, "candidateHeap")).isEmpty(), "zero-K filled heap");
            }
        } finally { empty.closeModel(); }
        System.out.println("updates: 12000 reference-map comparisons and zero-K passed");
    }

    @SuppressWarnings("unchecked")
    private static void testPricing(String memory, boolean bounded) throws Exception {
        Data data = data();
        TWETBPCConfig config = config(300);
        LP lp = lp(data, config, memory);
        try {
            for (boolean heuristic : new boolean[] {true, false}) {
                Object solver = solver(data, config, lp, heuristic);
                List<TWETColumn> columns = (List<TWETColumn>) method(solver, "solve").invoke(solver);
                Map<?, ?> live = (Map<?, ?>) get(solver, "candidateBySignature");
                PriorityQueue<?> heap = (PriorityQueue<?>) get(solver, "candidateHeap");
                check(live.size() == 5 && columns.size() == 5, "pricing fixture changed");
                if (bounded) check(heap.size() <= 2L * live.size(), "pricing retained stale versions");
                ArrayList<Object> ordered = new ArrayList<>(live.values());
                ordered.sort(Comparator.comparingDouble((Object o) -> number(o, "reducedCost"))
                        .thenComparingDouble(o -> number(o, "id")));
                StringBuilder snapshot = new StringBuilder();
                for (Object candidate : ordered) {
                    snapshot.append(get(candidate, "id")).append(':').append(get(candidate, "sequence"))
                            .append('@').append(get(candidate, "cost"))
                            .append('/').append(get(candidate, "reducedCost")).append(';');
                }
                snapshot.append(" columns=");
                for (TWETColumn column : columns) {
                    snapshot.append(column.getSequence()).append('@').append(column.getCost()).append(';');
                }
                System.out.println("heap " + memory + "/" + heuristic + " live=" + live.size()
                        + " retained=" + heap.size());
                System.out.println("snapshot " + memory + "/" + heuristic + " bestRC="
                        + get(solver, "bestPseudoReducedCost") + " " + snapshot);
            }
        } finally { lp.closeModel(); }
    }

    private static TWETBPCConfig config(int k) {
        TWETBPCConfig config = new TWETBPCConfig();
        config.useTimeIndexedGraphPricing = true;
        config.useTimeIndexedGraphRank1CutPricing = true;
        config.enableSubsetRowCutsForTimeIndexedGraph = true;
        config.timeIndexedGraphMaxExactPricingColumns = k;
        return config;
    }

    private static LP lp(Data data, TWETBPCConfig config, String memory) throws Exception {
        LP lp = new LP(data, new Pool(data), new CutPool(), config, new OutsourcingPool(data));
        List<Long> arcs = new ArrayList<>();
        for (int from = 0; from <= data.n; from++) {
            for (int to = 1; to <= data.n; to++) arcs.add(((long) from << 32) | to);
        }
        int cut = lp.getCutPool().addCut(new TWETCut(-1, TWETCutType.SUBSET_ROW, List.of(1, 2, 3),
                "node".equals(memory) ? List.of(1, 2, 3) : null,
                "arc".equals(memory) ? arcs : null, 0.5, 1.0, "heap-regression"));
        Node node = new Node(data, List.of(), List.of(), 0);
        node.activeCutIds.add(cut);
        lp.construct(node, List.of());
        field(lp, "jobDual").set(lp, new double[] {0, 100, 100, 0});
        field(lp, "machineDual").setDouble(lp, -100);
        field(lp, "activeSubsetRowPricingCutIds").set(lp, new ArrayList<>(List.of(cut)));
        field(lp, "activeSubsetRowPricingDuals").set(lp, new ArrayList<>(List.of(-1.0)));
        return lp;
    }

    private static Object solver(Data data, TWETBPCConfig config, LP lp, boolean heuristic) throws Exception {
        Class<?> type = Class.forName(TimeIndexedGraphRank1CutPricingEngine.class.getName() + "$Rank1CutSolver");
        Constructor<?> ctor = type.getDeclaredConstructor(TimeIndexedGraphRank1CutPricingEngine.class,
                LP.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(new TimeIndexedGraphRank1CutPricingEngine(data, config), lp, heuristic);
    }

    private static Data data() throws Exception {
        Path input = Files.createTempFile("twet-sri-heap-", ".dat");
        try {
            Files.writeString(input, "3 2\n50 100 100 1 1\n50 150 150 1 1\n50 150 150 1 1\n"
                    + "SETUP\n0 0 0 0\n0 0 0 0\n0 0 0 0\n0 0 0 0\n");
            return new Data(input.toString(), true, true);
        } finally { Files.deleteIfExists(input); }
    }

    private static double number(Object object, String name) {
        try { return ((Number) get(object, name)).doubleValue(); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object get(Object owner, String name) throws Exception { return field(owner, name).get(owner); }

    private static Method method(Object owner, String name, Class<?>... types) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
