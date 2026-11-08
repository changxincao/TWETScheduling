import java.util.List;
import Basic.Data;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.BestBpcProfiles;
import TWETBPC.BPCAlgorithmProfile;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.GC.FixedInitialColumnSeed;

/** 只核对列成本与排程时间回算的边界契约，不修改求解流程。 */
public final class TimingBoundaryAudit {
    public static void main(String[] args) throws Exception {
        Data data = new Data(args[0], true, true);
        TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
        System.out.println("n=" + data.n + " m=" + data.m + " H=" + data.CmaxH);
        System.out.println("cost=" + evaluator.evaluate(List.of(1)));
        try {
            var timing = evaluator.evaluateTiming(List.of(1));
            System.out.println("timing cost=" + timing.cost + " completion=" + timing.completions[0]);
        } catch (RuntimeException failure) {
            failure.printStackTrace(System.out);
        }
        if (args.length < 2) return;
        for (BPCAlgorithmProfile profile : new BPCAlgorithmProfile[] {
                BestBpcProfiles.NG_DSSR, BestBpcProfiles.TIME_INDEXED_GRAPH,
                BestBpcProfiles.TIME_INDEXED_GRAPH_RANK1}) {
            for (boolean detailed : new boolean[] {false, true}) {
                TWETBPCConfig config = new TWETBPCConfig();
                profile.apply(config);
                config.fixedInitialColumnSeed = new FixedInitialColumnSeed(
                        List.of(List.of(1)), List.of(List.of(1)));
                config.runALNSForSeed = false;
                config.reuseConfiguredBestSolution = false;
                config.enableBPCConsoleOutput = false;
                config.writeDetailedBPCArtifacts = detailed;
                config.solveTimeLimitSeconds = 5;
                config.bpcOutputRoot = args[1];
                config.bpcMethodName = profile.getName() + "-detailed-" + detailed;
                var solver = new TWETBPCSolver(new Data(args[0], true, true), config);
                try {
                    var result = solver.solve();
                    System.out.println(profile.getName() + " detailed=" + detailed
                            + " status=" + result.getStatus() + " ub=" + result.getIncumbentCost()
                            + " lb=" + result.getBestBound());
                } catch (RuntimeException failure) {
                    System.out.println(profile.getName() + " detailed=" + detailed + " FAILED");
                    failure.printStackTrace(System.out);
                }
            }
        }
    }
}
