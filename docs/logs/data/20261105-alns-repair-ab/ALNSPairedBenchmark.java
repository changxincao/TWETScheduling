package HEU;

import Basic.Data;
import Common.Configure;
import Common.Utility;
import Common.formal.FormalExperimentDataFactory;
import TWETBPC.BestBpcProfiles;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.HeuristicSeedProvider;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** 单JVM单算例配对试验，不写正式seed；诊断计数不改变随机序列。 */
public final class ALNSPairedBenchmark {
    static final class CountRandom extends Random {
        int rounds;
        CountRandom(long seed) { super(seed); }
        @Override public double nextDouble(double lo, double hi) {
            rounds++;
            return super.nextDouble(lo, hi);
        }
    }
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[1]);
        Configure.SegmentPool = false;
        Utility.resetCurUpperBound(Utility.big_M);
        Utility.rng = new Random(seed);
        CountRandom random = new CountRandom(seed ^ 0x5DEECE66DL);
        EngineALNS.rng = random;
        Move.Recordreset();
        Data d = FormalExperimentDataFactory.load(Path.of(args[0]));
        if (d.n != 60 || d.hasOutsourcingData()) throw new AssertionError("Expected n60 scheduling-only");
        TWETBPCConfig config = new TWETBPCConfig();
        BestBpcProfiles.NG_DSSR.apply(config);
        config.reuseConfiguredBestSolution = false;
        System.out.println("START version=" + args[2] + " input=" + args[0] + " seed=" + seed
                + " budgetMs=" + config.alnsMaxRuntimeMillis + " stagnation=" + config.alnsMaxNoImproveIterations);
        long cpu0 = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        long start = System.nanoTime();
        Solution best = new HeuristicSeedProvider(d, config).getOrBuildSeed();
        double seconds = (System.nanoTime()-start)/1e9;
        double cpu = (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime()-cpu0)/1e9;
        double objective = best.curCost;
        List<Solution> history = d.configure.getBestSolutionHistoryCopies();
        String initial = history.get(0).getSequencesCopy().toString();
        double initialCost = history.get(0).curCost;
        Utility.resetCurUpperBound(Utility.big_M);
        double recalculated = best.calCost();
        int[] visits = new int[d.n+1];
        for (List<Integer> route : best.getSequencesCopy()) for (int job : route) visits[job]++;
        for (int j = 1; j <= d.n; j++) if (visits[j] != 1) throw new AssertionError("Coverage " + j);
        if (!best.outsourcedJobs.isEmpty() || !Double.isFinite(objective) || Math.abs(objective-recalculated)>1e-5)
            throw new AssertionError("Invalid objective " + objective + " / " + recalculated);
        System.out.printf(Locale.US, "RESULT version=%s seed=%d initial=%.9f final=%.9f seconds=%.6f cpu=%.6f rounds=%d improvements=%d verified=true%n",
                args[2], seed, initialCost, objective, seconds, cpu, random.rounds, history.size()-2);
        System.out.println("INITIAL " + initial);
        System.out.println("FINAL " + best.getSequencesCopy());
    }
}
