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
        if (args.length == 1 && "--control-only".equals(args[0])) {
            testDiagnosticControl();
            System.out.println("NG-DSSR Join diagnostic control passed");
            return;
        }
        boolean baseline = args.length == 1 && "--baseline".equals(args[0]);
        testMasksAndReuse(!baseline);
        if (Boolean.getBoolean("twet.bpc.ngDssrJoinBitmapCompare")
                || propertyWithDefault("twet.bpc.ngDssrJoinBitmapUseSet", true)) testRealGroupMembers();
        if (!baseline) {
            require(Arrays.stream(INDEX.getDeclaredFields()).noneMatch(f -> f.getName().equals("pruneByGroup")),
                    "unused group-pair cache must not be retained");
            if (!Boolean.getBoolean("twet.bpc.ngDssrJoinBitmapCompare")) testScalarSuffix();
            if (!Boolean.getBoolean("twet.bpc.ngDssrJoinBitmapCompare")
                    && !Boolean.getBoolean("twet.bpc.ngDssrJoinCompatibilityShadow")) testExperimentalMasks();
        }
        testPricingSnapshot(true, true);
        testPricingSnapshot(false, true);
        testPricingSnapshot(true, false);
        testPricingSnapshot(false, false);
        System.out.println("NgDssrJoinPrefilterCacheTest passed");
    }

    /** 验证诊断前置上界和 trace 隔离，不构造 LP 或启动定价。 */
    @SuppressWarnings("unchecked")
    private static void testDiagnosticControl() throws Exception {
        require(Long.getLong("twet.bpc.ngDssrJoinOptimizationMinGroupChecks", 0L) == 12L,
                "run this fixture with a group threshold of 12");
        var owner = new GCNGBBStyleBidirectionalNgDssr(data(), config());
        Method initialize = OWNER.getDeclaredMethod("initializeLabelSearchState");
        initialize.setAccessible(true);
        initialize.invoke(owner);
        Method bound = OWNER.getDeclaredMethod("canReachJoinGroupCheckThreshold");
        bound.setAccessible(true);
        var forwards = (ArrayList<ArrayList<Object>>) field(owner, "activeForwardByLastJob");
        var backwards = (ArrayList<ArrayList<Object>>) field(owner, "activeBackwardByFirstJob");
        var singlePoints = (ArrayList<Object>) field(owner, "backwardSinglePointByFirstJob");
        forwards.get(1).addAll(java.util.Collections.nCopies(3, null));
        backwards.get(2).addAll(java.util.Collections.nCopies(3, null));
        require(!(boolean) bound.invoke(owner), "inactive forward terminal cannot enter the scan");
        ((PackedBitSet) field(owner, "activeForwardTerminalJobs")).add(1);
        require(!(boolean) bound.invoke(owner), "3 x 3 is below threshold");
        var buckets = (ArrayList<ArrayList<Object>>) field(singlePoints.get(2), "liveLabelsByCardinality");
        buckets.add(new ArrayList<>(java.util.Collections.nCopies(1, null)));
        require((boolean) bound.invoke(owner), "3 x (3 + 1) must include single-point labels at equality");

        set(owner, "generatedColumns", new ArrayList<>());
        set(owner, "generatedColumnCandidates", new java.util.PriorityQueue<>());
        set(owner, "generatedCandidateBySignature", new java.util.HashMap<>());
        set(owner, "nonElementaryNegativeRoutes", new ArrayList<>());
        set(owner, "nonElementaryNegativeRouteBySignature", new java.util.HashMap<>());
        set(owner, "nonElementaryNegativeRouteRanking", new java.util.TreeSet<>());
        set(owner, "targetTrace", new StringBuilder("primary"));
        set(owner, "targetTraceEventLimit", 3);
        set(owner, "targetTraceWatchedLabelIds", new java.util.HashSet<>(java.util.Set.of(1)));
        Method clone = OWNER.getDeclaredMethod("copyJoinComparisonState");
        clone.setAccessible(true);
        Object shadow = clone.invoke(owner);
        ((StringBuilder) field(shadow, "targetTrace")).append("shadow");
        ((java.util.Set<Integer>) field(shadow, "targetTraceWatchedLabelIds")).add(2);
        require("primary".contentEquals((StringBuilder) field(owner, "targetTrace"))
                && java.util.Set.of(1).equals(field(owner, "targetTraceWatchedLabelIds"))
                && field(owner, "targetTraceEventLimit").equals(field(shadow, "targetTraceEventLimit")),
                "shadow trace writes must not leak, but tracing eligibility must be retained");
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
        boolean timed = propertyWithDefault("twet.bpc.ngDssrJoinPhaseTiming", true);
        if (checkRelease) {
            require(timed ? boundNanos > 0 && bitmapNanos > 0 : boundNanos == 0 && bitmapNanos == 0,
                    "join phase timing must follow the resolved property value");
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

    private static boolean propertyWithDefault(String name, boolean defaultValue) {
        return Boolean.parseBoolean(System.getProperty(name, Boolean.toString(defaultValue)));
    }

    /** 同组复用、跨终点释放，以及反向位图保留未入组的标签。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void testExperimentalMasks() throws Exception {
        Data data = data();
        TWETBPCConfig config = config();
        LP lp = lp(data, config);
        try {
            var owner = new GCNGBBStyleBidirectionalNgDssr(data, config);
            set(owner, "joinMemoryIgnoredJobs", bits(5));
            Class<? extends Enum> experiment = (Class<? extends Enum>) nested("JoinExperiment");
            Constructor<?> constructor = INDEX.getDeclaredConstructor(int.class, experiment);
            constructor.setAccessible(true);
            Method prune = INDEX.getDeclaredMethod("prunedForwardIndices", int.class, ArrayList.class,
                    BACKWARD, LP.class, OWNER);
            prune.setAccessible(true);
            Method release = INDEX.getDeclaredMethod("releaseTerminalPrunedIndices", int.class);
            release.setAccessible(true);
            Method fold = INDEX.getDeclaredMethod("foldScalarSuffixFloors", ArrayList.class);
            fold.setAccessible(true);
            var candidates = new ArrayList<>(java.util.Collections.nCopies(130, null));
            Object backward = backward(1, 2), sameGroup = backward(2, 2), unmapped = backward(3, 2);
            Object backwardGroup = group(true, 2, bits(4, 5), 0, 0, 1e6);
            for (String name : new String[] {"CACHE", "EMPTY", "SURVIVORS"}) {
                Object index = constructor.newInstance(data.n + 1, Enum.valueOf(experiment, name));
                var terminals = (ArrayList<ArrayList<Object>>) field(index, "forwardByTerminal");
                terminals.set(1, new ArrayList<>(Arrays.asList(
                        group(false, 1, bits(4), -1e8, 0, 1e6, 0, 129),
                        group(false, 1, bits(5), -1e8, 0, 1e6, 1, 64),
                        group(false, 1, bits(), 1e8, 0, 1e6, 5),
                        group(false, 1, bits(), -1e8, 2e6, 2e6 + 10, 8))));
                terminals.set(3, new ArrayList<>(Arrays.asList(group(false, 3, bits(), -1e8, 0, 1e6, 3))));
                var byLabel = (IdentityHashMap<Object, Object>) field(index, "backwardGroupByLabel");
                byLabel.put(backward, backwardGroup);
                byLabel.put(sameGroup, backwardGroup);
                boolean survivors = name.equals("SURVIVORS");
                if (survivors) ((BitSet[]) field(index, "unindexedForward"))[1].set(12);
                BitSet expected = new BitSet();
                for (int member : survivors ? new int[] {1, 12, 64} : new int[] {0, 5, 8, 129}) {
                    expected.set(member);
                }
                BitSet mask = (BitSet) prune.invoke(index, 1, candidates, backward, lp, owner);
                require(mask.equals(expected) && mask == prune.invoke(index, 1, candidates, sameGroup, lp, owner),
                        name + " must produce and reuse the terminal mask");
                release.invoke(index, 1);
                BitSet next = (BitSet) prune.invoke(index, 3, candidates, backward, lp, owner);
                require(next.cardinality() == (survivors ? 1 : 0) && next.get(3) == survivors && mask.equals(expected),
                        name + " must not transfer mask state to the next terminal");
                Object noGroup = prune.invoke(index, 3, candidates, unmapped, lp, owner);
                require(survivors ? noGroup == null : ((BitSet) noGroup).isEmpty(),
                        name + " must preserve the ungrouped backward fallback");
                release.invoke(index, 3);
                if (survivors) {
                    require(prune.invoke(index, 6, candidates, backward, lp, owner) == null,
                            "an ungrouped forward terminal must fall back to the original label scan");
                    ArrayList<Object> groups = terminals.get(1);
                    groups.add(groups.remove(2));
                    for (Object group : groups) set(group, "scalarCostFloor", field(group, "minReducedCost"));
                    fold.invoke(null, groups);
                    set(index, "scalarSuffixEnabled", true);
                    require(expected.equals(prune.invoke(index, 1, candidates, backward, lp, owner))
                            && (Long) field(owner, "joinPrefilterScalarSuffixSkipped") == 1,
                            "survivor mask must omit a cost suffix without losing an ungrouped label");
                }
            }
        } finally {
            lp.closeModel();
        }
    }

    @SuppressWarnings("unchecked")
    private static void testScalarSuffix() throws Exception {
        Data data = data();
        TWETBPCConfig config = config();
        LP lp = lp(data, config);
        var owner = new GCNGBBStyleBidirectionalNgDssr(data, config);
        set(owner, "joinMemoryIgnoredJobs", bits());
        Constructor<?> c = INDEX.getDeclaredConstructor(int.class);
        c.setAccessible(true);
        Object original = c.newInstance(data.n + 1);
        Object optimized = c.newInstance(data.n + 1);
        set(optimized, "scalarSuffixEnabled", true);
        ArrayList<Object> groups = new ArrayList<>();
        groups.add(group(false, 1, bits(4), -1e8, 0, 1e6, 0));
        groups.add(group(false, 1, bits(), -1e8, 0, 1e6, 1));
        for (int i = 2; i < 4096; i++) groups.add(group(false, 1, bits(), 1e8, 0, 1e6, i));
        for (Object g : groups) set(g, "scalarCostFloor", field(g, "minReducedCost"));
        Object backwardGroup = group(true, 2, bits(4), 0, 0, 1e6);
        Object backwardLabel = backward(1, 2);
        for (Object index : new Object[] {original, optimized}) {
            ((ArrayList<ArrayList<Object>>) field(index, "forwardByTerminal")).set(1, groups);
            ((IdentityHashMap<Object, Object>) field(index, "backwardGroupByLabel")).put(backwardLabel, backwardGroup);
        }
        ArrayList<Object> candidates = new ArrayList<>(java.util.Collections.nCopies(groups.size(), null));
        Method prune = INDEX.getDeclaredMethod("prunedForwardIndices", int.class, ArrayList.class,
                BACKWARD, LP.class, OWNER);
        prune.setAccessible(true);
        BitSet oldMask = (BitSet) prune.invoke(original, 1, candidates, backwardLabel, lp, owner);
        long before = counter(owner);
        BitSet newMask = (BitSet) prune.invoke(optimized, 1, candidates, backwardLabel, lp, owner);
        require(before == 4096 && counter(owner) - before == 2, "cost tail must not enter the group scan");
        require((long) field(owner, "joinPrefilterScalarSuffixSkipped") == 4094, "skipped groups counted once");
        require(newMask.cardinality() == 1 && newMask.get(0) && !newMask.get(1), "ordinary prefix mask unchanged");
        BitSet effective = (BitSet) newMask.clone();
        effective.set(2, 4096); // 后缀由原 label 级标量下界截断，不需要实际置位。
        require(effective.equals(oldMask), "implicit cost tail plus explicit prefix must match the old mask");
        require(prune.invoke(optimized, 1, candidates, backwardLabel, lp, owner) == newMask
                && counter(owner) - before == 2, "cached partial mask must not repeat the cutoff search");
        Object early = groups.get(2), late = groups.get(3);
        set(late, "scalarCostFloor", -1e8);
        Method fold = INDEX.getDeclaredMethod("foldScalarSuffixFloors", ArrayList.class);
        fold.setAccessible(true);
        fold.invoke(null, groups);
        Method prefix = INDEX.getDeclaredMethod("scalarJoinPrefix", ArrayList.class, double.class, double.class);
        prefix.setAccessible(true);
        require(groups.get(2) == early && groups.get(3) == late
                && (int) prefix.invoke(null, groups, 0.0, 0.0) == 4,
                "a late lower member floor must extend the scan without reordering original groups");
        System.out.println("SCALAR_SUFFIX checks=4096->2 skipped=4094");
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
        Constructor<?> indexConstructor = INDEX.getDeclaredConstructor(int.class); indexConstructor.setAccessible(true);
        Object index = indexConstructor.newInstance(8);
        var terminals = (ArrayList<ArrayList<Object>>) field(index, "forwardByTerminal");
        terminals.set(1, new ArrayList<>(Arrays.asList(group)));
        Method prepareSet = INDEX.getDeclaredMethod("prepareSetMembers"); prepareSet.setAccessible(true);
        prepareSet.invoke(index);
        int[] setMembers = (int[]) field(group, "setMembers");
        require(Arrays.equals(setMembers, prepared.members), "production set indices include non-envelope members");
        prepareSet.invoke(index);
        require(setMembers == field(group, "setMembers"), "set indices prepared once per join");

        // 包络近似取值较高时仍须服从成员下界，不能误把该组整个跳过。
        ((Label) low).minReducedCost = -2e-6;
        Method finalizeGroups = OWNER.getDeclaredMethod("finalizeJoinEnvelopeGroups", ArrayList.class, boolean.class);
        finalizeGroups.setAccessible(true);
        finalizeGroups.invoke(owner, terminals.get(1), true);
        require((double) field(group, "minReducedCost") == 0.0
                && (double) field(group, "scalarCostFloor") == -2e-6,
                "implicit suffix must use the lower of the envelope and actual member minima");
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
