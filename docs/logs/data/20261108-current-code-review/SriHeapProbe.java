package TWETBPC.GC;

import java.lang.reflect.*;
import java.util.*;
import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.*;
import TWETBPC.Model.*;

// Isolated probe: execute unchanged production labeling, then inspect retained candidates.
public final class SriHeapProbe {
    static Object get(Object o, String n) throws Exception {
        Field f = o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        return f.get(o);
    }
    static void set(Object o, String n, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        f.set(o, v);
    }
    public static void main(String[] args) throws Exception {
        Data d = new Data(args.length > 1 ? args[1]
                : "docs/logs/data/20261108-current-code-review/heap.dat", true, true);
        TWETBPCConfig c = new TWETBPCConfig();
        c.useTimeIndexedGraphPricing = true;
        c.useTimeIndexedGraphRank1CutPricing = true;
        c.enableSubsetRowCutsForTimeIndexedGraph = true;
        c.enableTimeIndexedGraphDualWindow = true;
        c.timeIndexedGraphMaxExactPricingColumns = 300;
        LP lp = new LP(d, new Pool(d), new CutPool(), c, new OutsourcingPool(d));
        Node node = new Node(d, new ArrayList<>(), new ArrayList<>(), 0);
        String memory = args.length == 0 ? "full" : args[0];
        List<Long> arcs = new ArrayList<>();
        for (int from = 0; from <= d.n; from++) {
            for (int to = 1; to <= d.n; to++) arcs.add(((long)from << 32) | to);
        }
        int id = lp.getCutPool().addCut(new TWETCut(-1, TWETCutType.SUBSET_ROW,
                List.of(1, 2, 3), "node".equals(memory) ? List.of(1, 2, 3) : null,
                "arc".equals(memory) ? arcs : null, 0.5, 1.0, "isolated-heap"));
        node.activeCutIds.add(id);
        lp.construct(node, List.of());
        set(lp, "jobDual", new double[]{0, 100, 100, 0});
        set(lp, "machineDual", -100.0);
        set(lp, "activeSubsetRowPricingCutIds", new ArrayList<>(List.of(id)));
        set(lp, "activeSubsetRowPricingDuals", new ArrayList<>(List.of(-1.0)));
        try {
            for (boolean heuristic : new boolean[]{true, false}) {
                TimeIndexedGraphRank1CutPricingEngine engine = new TimeIndexedGraphRank1CutPricingEngine(d, c);
                Class<?> type = Class.forName(engine.getClass().getName() + "$Rank1CutSolver");
                Constructor<?> ctor = type.getDeclaredConstructor(engine.getClass(), LP.class, boolean.class);
                ctor.setAccessible(true);
                Object solver = ctor.newInstance(engine, lp, heuristic);
                Method solve = type.getDeclaredMethod("solve");
                solve.setAccessible(true);
                List<?> columns = (List<?>)solve.invoke(solver);
                Map<?, ?> live = (Map<?, ?>)get(solver, "candidateBySignature");
                PriorityQueue<?> heap = (PriorityQueue<?>)get(solver, "candidateHeap");
                int stale = 0;
                for (Object candidate : heap) {
                    if (live.get(get(candidate, "signature")) != candidate) stale++;
                }
                System.out.println("memory=" + memory + " heuristic=" + heuristic + " H=" + d.CmaxH
                        + " tStar=" + get(solver, "tStar") + " live=" + live.size()
                        + " heap=" + heap.size() + " stale=" + stale + " returned=" + columns.size()
                        + " bestRC=" + get(solver, "bestPseudoReducedCost"));
            }
            PricingResult result = new TimeIndexedGraphRank1CutPricingEngine(d, c).price(lp);
            System.out.println("public price: " + result.getMessage());
        } finally { lp.closeModel(); }
    }
}
