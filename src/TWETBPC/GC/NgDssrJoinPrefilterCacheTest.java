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

/** 验证组对缓存移除及terminal位图释放后，剪枝掩码、组内复用与定价结果保持不变。 */
public final class NgDssrJoinPrefilterCacheTest {
    private static final Class<?> OWNER = GCNGBBStyleBidirectionalNgDssr.class;
    private static final Class<?> INDEX = nested("JoinEnvelopeIndex");
    private static final Class<?> GROUP = nested("JoinEnvelopeGroup");
    private static final Class<?> BACKWARD = nested("BackwardLabel");

    public static void main(String[] args) throws Exception {
        boolean baseline = args.length == 1 && "--baseline".equals(args[0]);
        testMasksAndReuse(!baseline);
        if (Boolean.getBoolean("twet.bpc.ngDssrJoinBitmapCompare")) testRealGroupMembers();
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
    private static void testMasksAndReuse(boolean checkRelease) throws Exception {
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
        NgJoinBitmapComparison comparison = Boolean.getBoolean("twet.bpc.ngDssrJoinBitmapCompare")
                ? (NgJoinBitmapComparison) field(index, "bitmapComparison") : null;
        if (comparison != null) require(comparison.units == 1 && comparison.merges == 3
                && comparison.sumK == 6 && comparison.sumW == 5, "diagnostic uses actual pruned groups once");
        long boundNanos = checkRelease ? (Long) field(owner, "joinPrefilterBoundNanos") : 0;
        long bitmapNanos = checkRelease ? (Long) field(owner, "joinPrefilterBitmapNanos") : 0;
        boolean timed = Boolean.getBoolean("twet.bpc.ngDssrJoinPhaseTiming");
        if (checkRelease) {
            require(timed ? boundNanos > 0 && bitmapNanos > 0 : boundNanos == 0 && bitmapNanos == 0,
                    "join phase timing must be explicitly enabled");
            require(timed == ((Long) field(owner, "joinPrefilterFunctionNanos") > 0),
                    "PWLF sub-timing must follow the same diagnostic switch");
        }
        BitSet repeated = (BitSet) prune.invoke(index, 1, candidates, labelB, lp, owner);
        require(first == repeated && counter(owner) == 4, "same backward group must reuse the existing BitSet");
        require(comparison == null || comparison.units == 1, "cache hit must not repeat A/B aggregation");
        require(!checkRelease || (Long) field(owner, "joinPrefilterBoundNanos") == boundNanos
                && (Long) field(owner, "joinPrefilterBitmapNanos") == bitmapNanos,
                "cache reuse must not repeat timed group checks or bitmap unions");
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
        if (checkRelease) {
            Method release = INDEX.getDeclaredMethod("releaseTerminalPrunedIndices", int.class);
            release.setAccessible(true);
            var cache = (IdentityHashMap<Object, BitSet[]>) field(index, "prunedIndicesByBackwardGroup");
            BitSet[] originalSlots = cache.get(backwardGroup);
            release.invoke(index, 1);
            for (BitSet[] slots : cache.values()) {
                require(slots[1] == null, "completed terminal must retain no cached BitSet");
            }
            require(cache.get(backwardGroup) == originalSlots, "release must retain reusable terminal arrays");
            require(originalSlots[3] == otherTerminal, "release must not discard another terminal");
            require(first.equals(expected) && all.equals(union), "release must unlink, not clear shared BitSets");
            release.invoke(index, 1);
            release.invoke(index, 0);
            require(prune.invoke(index, 3, candidates, labelB, lp, owner) == otherTerminal && counter(owner) == 9,
                    "repeated or empty-slot release must preserve other-terminal reuse");
            BitSet rebuilt = (BitSet) prune.invoke(index, 1, candidates, labelA, lp, owner);
            require(rebuilt != first && rebuilt.equals(expected) && counter(owner) == 13,
                    "revisiting a released terminal must rebuild an identical pruning mask");
            release.invoke(index, 1);
            release.invoke(index, 3);
            for (BitSet[] slots : cache.values()) {
                require(Arrays.stream(slots).allMatch(value -> value == null), "finished terminals retain no bitmap");
            }
            Object empty = constructor.newInstance(data.n + 1);
            release.invoke(empty, 1);
            require(((IdentityHashMap<?, ?>) field(empty, "prunedIndicesByBackwardGroup")).isEmpty(),
                    "releasing an empty cache must not allocate entries");
        }
        if (checkRelease) {
            Method reset = OWNER.getDeclaredMethod("resetJoinPhaseTiming");
            reset.setAccessible(true);
            reset.invoke(owner);
            for (String name : new String[] {"joinPrefilterTotalNanos", "joinPrefilterBoundNanos",
                    "joinPrefilterFunctionNanos", "joinPrefilterBitmapNanos", "joinRemainingLabelsNanos"}) {
                require((Long) field(owner, name) == 0, "per-round timing reset: " + name);
            }
        }
    }

    /** 即使某label完全不贡献包络，紧凑索引也必须保留它的原候选位置。 */
    @SuppressWarnings("unchecked")
    private static void testRealGroupMembers() throws Exception {
        var owner = new GCNGBBStyleBidirectionalNgDssr(data(), config());
        set(owner, "pricingHorizon", 100.0);
        set(owner, "tMid", 50.0);
        Constructor<?> c = nested("ForwardLabel").getDeclaredConstructors()[0]; c.setAccessible(true);
        Object low = c.newInstance(1, 1, null, bits(), bits(), bits(), bits(1),
                constant(0, 50, 0), null, new byte[0], 0.0, false);
        Object high = c.newInstance(2, 1, null, bits(), bits(), bits(), bits(1),
                constant(0, 50, 10), null, new byte[0], 0.0, false);
        Method add = OWNER.getDeclaredMethod("addForwardJoinEnvelopeGroup", java.util.HashMap.class,
                int.class, nested("ForwardLabel")); add.setAccessible(true);
        var map = new java.util.HashMap<Object, Object>();
        Object group = add.invoke(owner, map, 1, low);
        require(add.invoke(owner, map, 1, high) == group, "same terminal/memory must share group");
        require(((ArrayList<?>) field(group, "labels")).size() == 2, "all original members retained");
        Object envelope = field(group, "envelope");
        for (Object segment : (ArrayList<?>) field(envelope, "segments")) {
            require(field(segment, "source") == low, "higher function must not provide envelope minimum");
        }
        BitSet members = (BitSet) field(group, "memberIndices"); members.set(7); members.set(65);
        var diagnostic = new NgJoinBitmapComparison();
        var prepared = diagnostic.prepare(members);
        require(Arrays.equals(prepared.members, new int[] {7, 65}), "all original candidate indices, not envelope sources");
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
