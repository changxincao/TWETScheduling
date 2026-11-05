package TWETBPC.GC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.PackedBitSet;

/** 验证去除组对Boolean缓存后，剪枝掩码、BitSet复用及定价结果保持不变。 */
public final class NgDssrJoinPrefilterCacheTest {
    private static final Class<?> OWNER = GCNGBBStyleBidirectionalNgDssr.class;
    private static final Class<?> INDEX = nested("JoinEnvelopeIndex");
    private static final Class<?> GROUP = nested("JoinEnvelopeGroup");
    private static final Class<?> BACKWARD = nested("BackwardLabel");

    public static void main(String[] args) throws Exception {
        boolean baseline = args.length == 1 && "--baseline".equals(args[0]);
        testMasksAndReuse();
        if (!baseline) {
            require(Arrays.stream(INDEX.getDeclaredFields()).noneMatch(f -> f.getName().equals("pruneByGroup")),
                    "unused group-pair cache must not be retained");
        }
        testPricingSnapshot(true, true);
        testPricingSnapshot(false, true);
        testPricingSnapshot(true, false);
        testPricingSnapshot(false, false);
        System.out.println("NgDssrJoinPrefilterCacheTest passed");
    }

    @SuppressWarnings("unchecked")
    private static void testMasksAndReuse() throws Exception {
        Data data = data();
        TWETBPCConfig config = config();
        LP lp = lp(data, config);
        var owner = new GCNGBBStyleBidirectionalNgDssr(data, config);
        set(owner, "joinMemoryIgnoredJobs", bits());
        Constructor<?> constructor = INDEX.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Object index = constructor.newInstance(data.n + 1);
        var terminals = (ArrayList<ArrayList<Object>>) field(index, "forwardByTerminal");
        var byLabel = (IdentityHashMap<Object, Object>) field(index, "backwardGroupByLabel");
        ArrayList<Object> groups = new ArrayList<>();
        groups.add(group(false, 1, bits(4), -1e8, 0, 1e6, 0, 2, 70, 129));
        groups.add(group(false, 1, bits(), -1e8, 0, 1e6, 1, 64));
        groups.add(group(false, 1, bits(), 1e8, 0, 1e6, 5));
        groups.add(group(false, 1, bits(), -1e8, 2e6, 2e6 + 10, 8));
        terminals.set(1, groups);
        terminals.set(3, new ArrayList<>(Arrays.asList(group(false, 3, bits(), -1e8, 0, 1e6, 3))));
        Object backwardGroup = group(true, 2, bits(4), 0, 0, 1e6);
        Object labelA = backward(1, 2), labelB = backward(2, 2), unknown = backward(3, 2);
        byLabel.put(labelA, backwardGroup);
        byLabel.put(labelB, backwardGroup);
        ArrayList<Object> candidates = new ArrayList<>();
        for (int i = 0; i < 130; i++) candidates.add(null);
        Method prune = INDEX.getDeclaredMethod("prunedForwardIndices", int.class, ArrayList.class,
                BACKWARD, LP.class, OWNER);
        prune.setAccessible(true);
        BitSet expected = new BitSet();
        for (int i : new int[] {0, 2, 5, 8, 70, 129}) expected.set(i);
        BitSet first = (BitSet) prune.invoke(index, 1, candidates, labelA, lp, owner);
        require(first.equals(expected), "mixed prune/keep results and multiword member indices");
        require(counter(owner) == 4, "each forward group must be evaluated once");
        BitSet repeated = (BitSet) prune.invoke(index, 1, candidates, labelB, lp, owner);
        require(first == repeated && counter(owner) == 4, "same backward group must reuse the existing BitSet");
        BitSet otherTerminal = (BitSet) prune.invoke(index, 3, candidates, labelB, lp, owner);
        require(otherTerminal.isEmpty() && counter(owner) == 5, "terminal-specific cache must not leak");
        require(prune.invoke(index, 1, candidates, labelA, lp, owner) == first && counter(owner) == 5,
                "returning to an existing terminal must not repeat group comparisons");
        BitSet unmapped = (BitSet) prune.invoke(index, 1, candidates, unknown, lp, owner);
        require(unmapped.isEmpty() && counter(owner) == 5, "unmapped backward labels must not be pruned");
        Object conflicting = backward(4, 2);
        byLabel.put(conflicting, group(true, 2, bits(1), 0, 0, 1e6));
        BitSet all = (BitSet) prune.invoke(index, 1, candidates, conflicting, lp, owner);
        BitSet union = (BitSet) expected.clone(); union.set(1); union.set(64);
        require(all.equals(union) && counter(owner) == 9, "a different backward memory must be checked independently");
        System.out.println("MASKS " + first + " / " + otherTerminal + " / " + all + ", checks=" + counter(owner));
    }

    /** 固定时间切分，避免删除缓存导致的耗时变化干扰probe反馈，比较真实列与证书。 */
    private static void testPricingSnapshot(boolean negative, boolean phaseOne) throws Exception {
        Data data = data();
        TWETBPCConfig config = config();
        LP lp = lp(data, config);
        lp.setFeasibilityPhaseOneObjectiveMode(phaseOne);
        set(lp, "machineDual", -15.0);
        double[] dual = (double[]) field(lp, "jobDual");
        if (negative) dual[1] = dual[2] = dual[3] = phaseOne ? 10.0 : 1e6;
        PricingResult result = new GCNGBBStyleBidirectionalNgDssrPricingEngine(data, config).price(lp);
        require(!result.getMessage().contains("time limit reached"), "test pricing must finish");
        require(negative == result.isImproved(), "negative-column and closed cases must be distinguished");
        if (!negative) require(Double.isFinite(result.getCertifiedInternalReducedCost())
                && result.getCertifiedInternalReducedCost() >= -1e-6, "no-negative case needs a valid certificate");
        var snapshot = new ArrayList<String>();
        TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
        for (TWETColumn column : result.getColumns()) {
            require(column.getSequence().stream().distinct().count() == column.getSequence().size(),
                    "returned scheduling columns must remain elementary");
            double cost = evaluator.evaluate(column.getSequence());
            require(Math.abs(cost - column.getCost()) <= 1e-7 * Math.max(1.0, Math.abs(cost)), "true cost unchanged");
            double rc = lp.computeReducedCost(column, lp.captureTruePricingDuals());
            require(rc < -1e-6, "returned column must be negative under the actual dual");
            snapshot.add(column.getSignature() + ":" + Double.toHexString(column.getCost()) + ":" + Double.toHexString(rc));
        }
        // 保留返回顺序，修改前后不仅应集合相同，顺序也应一致。
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", snapshot).getBytes(StandardCharsets.UTF_8)));
        System.out.println("PRICING phaseOne=" + phaseOne + ", negative=" + negative + ", columns=" + snapshot.size()
                + ", certificate=" + Double.toHexString(result.getCertifiedInternalReducedCost()) + ", sha=" + digest);
    }

    private static Data data() throws Exception {
        Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
        data.n = 6;
        for (int i = 0; i <= data.n; i++) {
            data.hardWindowStart[i] = 0;
            data.hardWindowEnd[i] = data.CmaxH;
            for (int j = 0; j <= data.n; j++) data.preprocessedArcForbidden[i][j] = false;
        }
        return data;
    }

    private static TWETBPCConfig config() {
        TWETBPCConfig c = new TWETBPCConfig();
        c.enableBidirectionalPricing = true;
        c.useGCNGBBStyleNgDssrPricing = true;
        c.enableNgDssrJoinEnvelopePrefilter = true;
        c.enableNgDssrJoinEnvelopeCompression = false;
        c.bidirectionalMidpointProbe = false;
        c.bidirectionalMidpointProbeDssrImbalanceThreshold = Double.POSITIVE_INFINITY;
        c.enableTimeIndexedGraphDualWindow = false;
        c.enableHeuristicDualProfitableWindow = false;
        c.enableNgDssrHistoryWarmStart = false;
        c.maxExactPricingColumns = 20;
        return c;
    }

    private static LP lp(Data data, TWETBPCConfig config) {
        LP lp = new LP(data, new Pool(data), new CutPool(), config, new OutsourcingPool(data));
        lp.construct(new Node(data, new ArrayList<Integer>(), new ArrayList<Integer>(), 0), new ArrayList<Integer>());
        return lp;
    }

    private static Object group(boolean backward, int terminal, PackedBitSet memory, double cost, double start, double end,
            int... members) throws Exception {
        Constructor<?> c = GROUP.getDeclaredConstructor(int.class, PackedBitSet.class); c.setAccessible(true);
        Object group = c.newInstance(terminal, memory);
        Object envelope = field(group, "envelope");
        Method merge = envelope.getClass().getDeclaredMethod("merge", PiecewiseLinearFunction.class, nested("FunctionLabel"));
        Object source;
        if (backward) {
            source = backward(0, terminal);
        } else {
            Constructor<?> label = nested("ForwardLabel").getDeclaredConstructors()[0]; label.setAccessible(true);
            source = label.newInstance(0, terminal, null, bits(), bits(), bits(), memory,
                    constant(start, end, cost), null, new byte[0], 0.0, false);
        }
        merge.setAccessible(true); merge.invoke(envelope, constant(start, end, cost), source);
        set(group, "minReducedCost", cost);
        for (int member : members) ((BitSet) field(group, "memberIndices")).set(member);
        return group;
    }

    private static Object backward(int id, int terminal) throws Exception {
        Constructor<?> c = BACKWARD.getDeclaredConstructors()[0]; c.setAccessible(true);
        return c.newInstance(id, terminal, null, bits(), bits(), bits(), bits(), constant(0, 1e6, 0),
                null, new byte[0], 0.0, false, false);
    }

    private static PiecewiseLinearFunction constant(double start, double end, double cost) {
        PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, end);
        f.addSegment(start, end, 0, cost); return f;
    }

    private static PackedBitSet bits(int... members) {
        PackedBitSet b = new PackedBitSet(8); for (int member : members) b.add(member); return b;
    }
    private static Class<?> nested(String name) {
        try { return Class.forName(OWNER.getName() + "$" + name); }
        catch (ClassNotFoundException e) { throw new AssertionError(e); }
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static long counter(Object owner) throws Exception { return (Long) field(owner, "joinEnvelopePrefilterGroupPairs"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
