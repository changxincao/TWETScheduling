package TWETBPC.LP;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;

import Basic.Data;
import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.BP.AggregateArcBranchConstraint;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.PricingMode;
import TWETBPC.GC.PricingResult;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.Model.ColumnSource;

/** 在定价内部确定性触发时限，检查Phase-I正常退出和非超时证书保护。 */
public final class PhaseOneRepairTimeoutTest {
    private PhaseOneRepairTimeoutTest() { }

    public static void main(String[] args) throws Exception {
        for (boolean strongTrial : new boolean[] { true, false }) {
            checkExit(strongTrial, true, false);
            checkExit(strongTrial, false, false);
            checkExit(strongTrial, false, true);
            checkExit(strongTrial, true, true);
        }
        System.out.println("PhaseOneRepairTimeoutTest passed (8 timeout/certificate exit cases)");
    }

    private static void checkExit(boolean strongTrial, boolean expireDuringPricing,
            boolean certified) throws Exception {
        Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
        data.n = 2;
        data.outsourcingCost[1] = Double.MAX_VALUE;
        data.outsourcingCost[2] = Double.MAX_VALUE;
        TWETBPCConfig config = new TWETBPCConfig();
        config.enableStrongBranchingPhaseOneRepair = true;
        config.enableStrongBranchingBranchImpliedPenalty = false;
        config.enableDualBoundPruning = false;
        Pool pool = new Pool(data);
        int first = pool.addColumn(List.of(1), 1.0, ColumnSource.MANUAL, false);
        int second = pool.addColumn(List.of(2), 1.0, ColumnSource.MANUAL, false);
        Node node = new Node(data, List.of(first, second), List.of(first, second), 0.0);
        // 覆盖本来可行，仅新分支1->2缺列；对应正式strong trial的分支行slack。
        BitSet arcs = new BitSet();
        arcs.set(1 * (data.n + 2) + 2);
        node.addAggregateArcConstraint(new AggregateArcBranchConstraint(
                "test", "require 1->2", arcs, data.n + 2, true, 1));
        LP lp = new LP(data, pool, new CutPool(), config, new OutsourcingPool(data));
        lp.construct(node, node.seedColumnIds);
        boolean[] expired = { false };
        boolean[] called = { false };
        PricingEngine engine = new PricingEngine() {
            @Override public PricingResult price(LP currentLp) {
                if (!currentLp.isFeasibilityPhaseOneObjectiveMode()) {
                    throw new AssertionError("Expected Phase-I pricing");
                }
                called[0] = true;
                expired[0] = expireDuringPricing;
                PricingResult result = PricingResult.noImprovement("test Phase-I pricing");
                return certified ? result.withCertifiedInternalReducedCost(0.0) : result;
            }
            @Override public boolean supportsFeasibilityPhaseOneObjective() { return true; }
            @Override public String getName() { return "PhaseOneTimeoutRegression"; }
        };
        PC pc = new PC(config, PricingMode.OTHER, List.of(engine), List.of(), new BPCTraceSink() { });
        pc.setTimeLimitChecker(() -> expired[0]);
        boolean missingCertificateRejected = false;
        try {
            if (strongTrial) {
                PC.StrongBranchingTrialResult result = pc.solveStrongBranchingRmpTrial(lp);
                if (expireDuringPricing) {
                    if (!result.isTimeLimited() || result.isInfeasible() || result.isClosed()
                            || result.isReusableForQueue() || !result.getInternalColumnIds().isEmpty()
                            || result.getSolution().getObjectiveValue() <= 0.0) {
                        throw new AssertionError("Interrupted repair was closed, reused or mistaken for feasible");
                    }
                } else if (certified && (!result.isInfeasible() || !result.isClosed()
                        || result.isTimeLimited())) {
                    throw new AssertionError("Complete certificate no longer closes positive Phase-I residual");
                }
            } else {
                Method method = PC.class.getDeclaredMethod("repairAfterCutPhaseOne", LP.class);
                method.setAccessible(true);
                try {
                    var result = (TWETBPC.Model.TWETMasterSolution) method.invoke(pc, lp);
                    if (expireDuringPricing && (result.getStatus() == TWETMasterStatus.INFEASIBLE
                            || result.getObjectiveValue() <= 0.0)) {
                        throw new AssertionError("Interrupted all-row repair was mistaken for feasible/infeasible");
                    }
                    if (!expireDuringPricing && certified && result.getStatus() != TWETMasterStatus.INFEASIBLE) {
                        throw new AssertionError("Complete all-row certificate no longer proves infeasibility");
                    }
                } catch (InvocationTargetException exception) {
                    if (exception.getCause() instanceof IllegalStateException cause) throw cause;
                    throw exception;
                }
            }
        } catch (IllegalStateException exception) {
            if (expireDuringPricing || certified || !exception.getMessage().contains("without a complete")) {
                throw exception;
            }
            missingCertificateRejected = true;
        } finally {
            boolean dirty = lp.isFeasibilityRepairMode() || lp.isFeasibilityPhaseOneObjectiveMode();
            lp.closeModel();
            if (dirty) throw new AssertionError("Phase-I modes were not cleared on exit");
        }
        if (!called[0] || (!expireDuringPricing && !certified && !missingCertificateRejected)) {
            throw new AssertionError("Expected pricing call and non-timeout certificate rejection");
        }
    }
}
