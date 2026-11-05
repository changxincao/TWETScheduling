package TWETBPC.LP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import Basic.Data;
import Common.formal.FormalExperimentDataFactory;
import Output.BPCTraceSink;
import TWETBPC.BestBpcProfiles;
import TWETBPC.GC.FixedInitialColumnSeed;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCContext;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.PricingMode;
import TWETBPC.GC.PricingResult;
import TWETBPC.GC.TimeIndexedGraphPricingEngine;
import TWETBPC.GC.TimeIndexedGraphRank1CutPricingEngine;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.TWETSolveStatus;

/** 有限M正残差必须经Phase-I确认；使用真实文件、真实列成本及TI定价回归。 */
public final class FiniteMRepairConfirmationTest {
    private static final String INPUT = """
            2 2
            1 0 0 10
            1 0 0 0
            SETUP
            0 0 0
            0 0 0
            0 0 0
            SETUP_COST
            0 0 0
            0 0 0
            0 0 0
            OUTSOURCING_COST
            100 100000
            OUTSOURCING_TARIFF
            2
            0 50 1 0
            50 100101 0.0001 49.995
            """;

    private FiniteMRepairConfirmationTest() { }

    public static void main(String[] args) throws Exception {
        Path input = Files.createTempFile("twet-finite-m-repair-", ".dat");
        try {
            Files.writeString(input, INPUT);
            verifyFiniteMMechanism(input);
            for (boolean rank1 : new boolean[] { false, true }) {
                for (boolean phaseOne : new boolean[] { false, true }) {
                    verifyWholeTree(input, rank1, phaseOne);
                }
                for (boolean allRows : new boolean[] { false, true }) {
                    verifyTrial(input, rank1, allRows, "confirmed_feasible");
                    verifyTrial(input, rank1, allRows, "confirmed_infeasible");
                    verifyTrial(input, rank1, allRows, "m_success");
                    verifyTrial(input, rank1, allRows, "timeout_in_m");
                    verifyTrial(input, rank1, allRows, "timeout_in_phase_one");
                    verifyTrial(input, rank1, allRows, "missing_certificate");
                }
                verifyFormalNode(input, rank1);
            }
            verifyPositivePenaltyColumn();
            System.out.println("FiniteMRepairConfirmationTest passed: mechanism, 4 full trees, "
                    + "24 repair exits, 2 formal-node repairs, positive penalty-column confirmation");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static TWETBPCConfig config(boolean rank1) {
        TWETBPCConfig config = new TWETBPCConfig();
        BestBpcProfiles.applyBestPricingModeDefaults(config, !rank1, rank1, false);
        config.outsourcingModel = "masterVariables";
        config.enableStrongBranchingPhaseOneRepair = false;
        config.enableBPCConsoleOutput = false;
        config.solveTimeLimitSeconds = 30;
        config.maxNodes = 20;
        config.fixedInitialColumnSeed = new FixedInitialColumnSeed(
                List.of(List.of(2)), List.of(List.of(2)), List.of(1));
        return config;
    }

    /** 任务1内部最少成本10，外包至少50.005；两条singleton实现10，独立确定整数最优值。 */
    private static void verifyWholeTree(Path input, boolean rank1, boolean phaseOne) throws Exception {
        Data data = FormalExperimentDataFactory.load(input);
        TWETBPCConfig config = config(rank1);
        config.enableStrongBranchingPhaseOneRepair = phaseOne;
        TWETBPCContext context = new TWETBPCContext(data, config);
        var result = context.tree.solve();
        require(result.getStatus() == TWETSolveStatus.FINISHED, "Tree did not finish");
        near(result.getIncumbentCost(), 10, "tree incumbent");
        near(result.getBestBound(), 10, "tree bound");
        System.out.println("TREE rank1=" + rank1 + " directPhaseOne=" + phaseOne
                + " nodes=" + result.getProcessedNodes() + " UB=" + result.getIncumbentCost()
                + " LB=" + result.getBestBound());
    }

    private static void verifyFiniteMMechanism(Path input) throws Exception {
        Fixture f = new Fixture(input, false, "confirmed_feasible");
        try {
            f.lp.setRepairObjectivePenalty(50 * f.incumbent);
            f.lp.setFeasibilityRepairMode(true);
            var solution = f.lp.solveRelaxation();
            var priced = f.engine.delegate.price(f.lp);
            int first = f.pool.addColumn(List.of(1), 10, ColumnSource.MANUAL, true);
            double reducedCost = f.lp.computeReducedCost(f.pool.getColumn(first), f.lp.captureTruePricingDuals());
            require(solution.getStatus() == TWETMasterStatus.LP_RELAXATION && !f.lp.isNoSlack(),
                    "Expected positive artificial residual");
            require(priced.getColumns().isEmpty() && priced.getCertifiedInternalReducedCost() >= -1e-6
                    && reducedCost > 0, "Expected closed finite-M pricing with missing feasible column");
            System.out.println("MECHANISM finiteMObjective=" + solution.getObjectiveValue()
                    + " singleton1RC=" + reducedCost + " certificate=" + priced.getCertifiedInternalReducedCost());
        } finally {
            f.lp.closeModel();
        }
    }

    private static void verifyTrial(Path input, boolean rank1, boolean allRows, String scenario) throws Exception {
        Fixture f = new Fixture(input, rank1, scenario);
        // 全行M可能自行找到内部列；停止该阶段补列以确定性覆盖确认出口，不伪造闭合证书。
        f.engine.suppressFiniteMColumns = allRows && !scenario.equals("m_success");
        boolean rejectedMissingCertificate = false;
        try {
            f.pc.prepareStandaloneStrongBranchingTrial(scenario.equals("m_success") ? 1_000_000 : f.incumbent);
            var result = f.pc.solveStrongBranchingRmpTrial(f.lp, allRows, false);
            if (scenario.startsWith("timeout")) {
                require(result.isTimeLimited() && !result.isInfeasible() && !result.isClosed()
                        && !result.isReusableForQueue(), "Timeout incorrectly closed/reused a node");
            } else if (scenario.equals("confirmed_infeasible")) {
                require(result.isInfeasible() && result.isClosed() && !result.isTimeLimited(),
                        "Real infeasibility not certified");
            } else {
                require(!result.isInfeasible() && !result.isTimeLimited() && !result.isUnusable(),
                        "Feasible trial rejected");
                near(result.getBound(), 10, "restored true-cost trial");
                require(f.lp.isNoSlack() && !f.lp.hasPositiveBranchImpliedPenaltyColumn(),
                        "Repair returned with positive residual");
                require(f.lp.getRestrictedColumnIds().contains(f.second), "Existing valid seed was lost");
                require(f.lp.getRestrictedColumnIds().stream().anyMatch(id ->
                        f.pool.getColumn(id).getSequence().equals(List.of(1))), "New repair column was lost");
            }
        } catch (IllegalStateException ex) {
            if (!scenario.equals("missing_certificate") || !ex.getMessage().contains("without a complete")) {
                throw ex;
            }
            rejectedMissingCertificate = true;
        } finally {
            require(!f.lp.isFeasibilityRepairMode() && !f.lp.isFeasibilityPhaseOneObjectiveMode(),
                    "Repair modes leaked on exit");
            f.lp.closeModel();
        }
        boolean expectedPhaseOne = !scenario.equals("m_success") && !scenario.equals("timeout_in_m");
        require(f.engine.mCalls > 0 && (f.engine.phaseCalls > 0) == expectedPhaseOne,
                "Unexpected confirmation activation: " + scenario + ", allRows=" + allRows
                + ", mCalls=" + f.engine.mCalls + ", phaseCalls=" + f.engine.phaseCalls);
        require(rejectedMissingCertificate == scenario.equals("missing_certificate"),
                "Missing certificate was accepted");
        System.out.println("TRIAL rank1=" + rank1 + " allRows=" + allRows + " " + scenario
                + " mCalls=" + f.engine.mCalls + " phaseCalls=" + f.engine.phaseCalls);
    }

    private static void verifyFormalNode(Path input, boolean rank1) throws Exception {
        Fixture f = new Fixture(input, rank1, "confirmed_feasible");
        try {
            var result = f.pc.solve(f.lp, f.incumbent);
            require(result.getStatus() == TWETMasterStatus.LP_RELAXATION, "Formal node was discarded");
            near(result.getObjectiveValue(), 10, "formal-node objective");
            require(f.engine.phaseCalls > 0 && !f.lp.isFeasibilityPhaseOneObjectiveMode()
                    && !f.lp.isFeasibilityRepairMode(), "Formal node confirmation/cleanup failed");
            require(!f.engine.phaseStateLive, "Phase-I pricing state leaked into the formal node");
        } finally {
            f.lp.closeModel();
        }
    }

    /** slack已经为0但竞争列仍为正时，也必须确认并清除竞争列，不能直接丢弃节点。 */
    private static void verifyPositivePenaltyColumn() throws Exception {
        Path input = Files.createTempFile("twet-penalty-column-", ".dat");
        LP lp = null;
        try {
            Files.writeString(input, """
                    3 2
                    1 0 0 10
                    1 0 0 0
                    1 0 0 0
                    SETUP
                    0 0 0 0
                    0 0 0 0
                    0 0 0 0
                    0 0 0 0
                    SETUP_COST
                    0 0 0 0
                    0 0 0 0
                    0 0 0 0
                    0 0 0 0
                    """);
            Data data = FormalExperimentDataFactory.load(input);
            TWETBPCConfig config = config(false);
            config.enableDualBoundPruning = false;
            Pool pool = new Pool(data);
            TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
            int legal = pool.addColumn(List.of(1, 2), evaluator.evaluate(List.of(1, 2)), ColumnSource.MANUAL, true);
            int competing = pool.addColumn(List.of(1, 3), evaluator.evaluate(List.of(1, 3)), ColumnSource.MANUAL, true);
            Node node = new Node(data, List.of(legal, competing), List.of(legal, competing), 0);
            node.depth = 1;
            for (int to = 1; to <= node.sinkId(); to++) {
                if (to != 2) node.forbidBranchImpliedArc(1, to);
            }
            for (int from = 0; from <= node.sinkId(); from++) {
                if (from != 1) node.forbidBranchImpliedArc(from, 2);
            }
            node.requireArc(1, 2);
            node.markArcRepair(1, 2, true);
            lp = new LP(data, pool, new CutPool(), config, new OutsourcingPool(data));
            lp.construct(node, node.seedColumnIds);
            lp.setBranchImpliedPenaltyObjectiveMode(true);
            lp.setRepairObjectivePenalty(5000);
            lp.setFeasibilityRepairMode(true);
            require(lp.solveRelaxation().getStatus() == TWETMasterStatus.LP_RELAXATION
                    && lp.isNoSlack() && lp.hasPositiveBranchImpliedPenaltyColumn(),
                    "Expected penalty-only positive residual");
            lp.setFeasibilityRepairMode(false);
            ObservedPricing engine = new ObservedPricing(new TimeIndexedGraphPricingEngine(data, config),
                    "penalty_exit_control");
            engine.suppressFiniteMColumns = true;
            PC pc = new PC(config, PricingMode.TIME_INDEXED, List.of(engine), List.of(), new BPCTraceSink() { });
            pc.prepareStandaloneStrongBranchingTrial(100);
            var result = pc.solveStrongBranchingRmpTrial(lp);
            require(!result.isInfeasible() && !result.isUnusable() && engine.phaseCalls > 0,
                    "Penalty-only residual did not recover");
            near(result.getBound(), 10, "penalty-column repaired bound");
            require(lp.isNoSlack() && !lp.hasPositiveBranchImpliedPenaltyColumn()
                    && !lp.getRestrictedColumnIds().contains(competing)
                    && lp.getRestrictedColumnIds().contains(legal), "Penalty-column cleanup failed");
            near(pool.getColumn(competing).getCost(), evaluator.evaluate(List.of(1, 3)), "pool cost changed");
            require(!lp.isFeasibilityRepairMode() && !lp.isFeasibilityPhaseOneObjectiveMode(),
                    "Penalty repair mode leaked");
        } finally {
            if (lp != null) lp.closeModel();
            Files.deleteIfExists(input);
        }
    }

    /** 每例独立构造同一个费率分支，不依赖手填列成本或未闭合父节点的假证书。 */
    private static final class Fixture {
        final Pool pool;
        final LP lp;
        final PC pc;
        final ObservedPricing engine;
        final int second;
        final double incumbent;

        Fixture(Path input, boolean rank1, String scenario) throws Exception {
            Data data = FormalExperimentDataFactory.load(input);
            TWETBPCConfig config = config(rank1);
            // 单独观察修复出口，避免有效的bound剪枝提前结束试算；完整树保留默认剪枝。
            config.enableDualBoundPruning = false;
            pool = new Pool(data);
            TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
            near(evaluator.evaluate(List.of(1)), 10, "actual singleton1 cost");
            second = pool.addColumn(List.of(2), evaluator.evaluate(List.of(2)), ColumnSource.MANUAL, true);
            Node node = new Node(data, List.of(second), List.of(second), 0);
            node.depth = 1;
            node.forbidTariffSegment(1);
            node.markTariffRepair(1, false);
            if (scenario.equals("confirmed_infeasible") || scenario.equals("missing_certificate")) {
                // 禁止所有内部进入1的弧，第一费率段又无法外包1，故完整节点确实不可行。
                node.forbidArc(0, 1);
                node.forbidArc(2, 1);
            }
            lp = new LP(data, pool, new CutPool(), config, new OutsourcingPool(data));
            lp.construct(node, node.seedColumnIds);
            PricingEngine delegate = rank1 ? new TimeIndexedGraphRank1CutPricingEngine(data, config)
                    : new TimeIndexedGraphPricingEngine(data, config);
            engine = new ObservedPricing(delegate, scenario);
            pc = new PC(config, rank1 ? PricingMode.TIME_INDEXED_RANK1 : PricingMode.TIME_INDEXED,
                    List.of(engine), List.of(), new BPCTraceSink() { });
            pc.setTimeLimitChecker(() -> engine.expired);
            incumbent = data.evaluateOutsourcingCost(100);
        }
    }

    /** 正常路径使用真实定价，仅为超时/证书契约测试确定性注入中断或缺证书。 */
    private static final class ObservedPricing implements PricingEngine {
        final PricingEngine delegate;
        final String scenario;
        int mCalls;
        int phaseCalls;
        boolean expired;
        boolean mStateLive;
        boolean phaseStateLive;
        boolean suppressFiniteMColumns;

        ObservedPricing(PricingEngine delegate, String scenario) {
            this.delegate = delegate;
            this.scenario = scenario;
        }

        @Override public PricingResult price(LP lp) {
            if (lp.isFeasibilityPhaseOneObjectiveMode()) {
                require(!mStateLive, "Finite-M pricing state reused in Phase-I");
                phaseStateLive = true;
                phaseCalls++;
                if (scenario.equals("timeout_in_phase_one")) {
                    expired = true;
                    return PricingResult.noImprovement("injected Phase-I timeout");
                }
                if (scenario.equals("missing_certificate")) {
                    return PricingResult.noImprovement("injected missing Phase-I certificate");
                }
            } else {
                require(!phaseStateLive, "Phase-I pricing state reused under the real objective");
                if (lp.isFeasibilityRepairMode()) {
                    mCalls++;
                    mStateLive = true;
                    if (scenario.equals("timeout_in_m")) {
                        expired = true;
                        return PricingResult.noImprovement("injected finite-M timeout");
                    }
                    if (suppressFiniteMColumns) {
                        return PricingResult.noImprovement("all-row finite-M exit control");
                    }
                }
            }
            return delegate.price(lp);
        }

        @Override public boolean supportsFeasibilityPhaseOneObjective() { return true; }
        @Override public String getName() { return "Observed" + delegate.getName(); }
        @Override public void reset() { mStateLive = false; phaseStateLive = false; delegate.reset(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-6, message + ": " + actual + " != " + expected);
    }
}
