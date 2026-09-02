package TWETBPC.LP;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import Basic.Data;
import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.CUT.CutGenerationResult;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.GC.OutsourcingPricingEngine;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.PricingMode;
import TWETBPC.GC.PricingResult;
import TWETBPC.GC.TimeIndexedGraphPricingEngine;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.Model.TWETOutsourcingColumn;

/** cut 后有限列 RMP 不可行时，全行 Phase-I 修复的控制流回归。 */
public final class AfterCutPhaseOneRepairTest {

	private AfterCutPhaseOneRepairTest() {
	}

	public static void main(String[] args) throws Exception {
		testDirectAllRowRepairWithTimeIndexedPricing();
		testSolveRepairsInfeasibleAfterCutRmp();
		testSubsetRowCutIgnoresColumnizedOutsourcingColumns();
		testColumnizedOutsourcingRequiresPairedCertificate();
		System.out.println("AfterCutPhaseOneRepairTest passed");
	}

	private static void testDirectAllRowRepairWithTimeIndexedPricing() throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		data.n = 6;
		for (int job = 1; job <= data.n; job++) {
			data.hardWindowStart[job] = 0.0;
			data.hardWindowEnd[job] = data.CmaxH;
			for (int other = 0; other <= data.n; other++) {
				data.preprocessedArcForbidden[job][other] = false;
				data.preprocessedArcForbidden[other][job] = false;
			}
		}

		TWETBPCConfig config = new TWETBPCConfig();
		config.useTimeIndexedGraphPricing = true;
		config.enableTimeIndexedGraphDualWindow = false;
		config.enableHeuristicDualProfitableWindow = false;
		config.timeIndexedGraphMaxExactPricingColumns = 50;

		Pool pool = new Pool(data);
		CutPool cutPool = new CutPool();
		Node node = new Node(data, new ArrayList<Integer>(), new ArrayList<Integer>(), 0.0);
		LP lp = new LP(data, pool, cutPool, config, new OutsourcingPool(data));
		lp.construct(node, node.seedColumnIds);

		List<PricingEngine> engines = Collections.<PricingEngine>singletonList(
				new TimeIndexedGraphPricingEngine(data, config));
		PC pc = new PC(config, PricingMode.TIME_INDEXED, engines,
				Collections.emptyList(), new BPCTraceSink() { });

		TWETMasterSolution repaired = invokeAfterCutRepair(pc, lp);
		if (repaired.getStatus() != TWETMasterStatus.LP_RELAXATION) {
			throw new AssertionError("after-cut Phase-I did not restore a feasible true-cost RMP: "
					+ repaired.getStatus() + ", message=" + repaired.getMessage());
		}
		if (lp.getRestrictedColumnIds().isEmpty()) {
			throw new AssertionError("after-cut Phase-I restored feasibility without adding legal columns");
		}
		if (lp.isFeasibilityRepairMode() || lp.isFeasibilityPhaseOneObjectiveMode()) {
			throw new AssertionError("after-cut Phase-I flags remained enabled after true-cost rebuild");
		}
	}

	private static void testSolveRepairsInfeasibleAfterCutRmp() throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		data.n = 3;
		for (int job = 1; job <= data.n; job++) {
			data.outsourcingCost[job] = Double.MAX_VALUE;
		}

		TWETBPCConfig config = new TWETBPCConfig();
		config.maxCutRounds = 2;
		config.enableDualBoundPruning = false;

		Pool pool = new Pool(data);
		ArrayList<Integer> seedIds = new ArrayList<Integer>();
		seedIds.add(Integer.valueOf(pool.addColumn(List.of(1, 2, 1, 2), 1.0,
				ColumnSource.MANUAL, true)));
		seedIds.add(Integer.valueOf(pool.addColumn(List.of(2, 3, 2, 3), 1.0,
				ColumnSource.MANUAL, true)));
		seedIds.add(Integer.valueOf(pool.addColumn(List.of(1, 3, 1, 3), 1.0,
				ColumnSource.MANUAL, true)));

		CutPool cutPool = new CutPool();
		Node node = new Node(data, seedIds, new ArrayList<Integer>(), 0.0);
		LP lp = new LP(data, pool, cutPool, config, new OutsourcingPool(data));
		lp.construct(node, seedIds);

		PhaseOneOnlyPricingEngine engine = new PhaseOneOnlyPricingEngine(data.n);
		OneShotSubsetRowCutGenerator generator = new OneShotSubsetRowCutGenerator();
		PC pc = new PC(config, PricingMode.OTHER,
				Collections.<PricingEngine>singletonList(engine),
				Collections.<CutGenerator>singletonList(generator), new BPCTraceSink() { });

		TWETMasterSolution solution = pc.solve(lp);
		if (!generator.separated) {
			throw new AssertionError("test cut was not separated");
		}
		if (!engine.phaseOneColumnReturned) {
			throw new AssertionError("after_cut infeasibility did not invoke Phase-I pricing");
		}
		if (solution.getStatus() != TWETMasterStatus.LP_RELAXATION) {
			throw new AssertionError("after-cut Phase-I did not return to the normal LP flow: "
					+ solution.getStatus() + ", message=" + solution.getMessage());
		}
		if (lp.isFeasibilityRepairMode() || lp.isFeasibilityPhaseOneObjectiveMode()) {
			throw new AssertionError("repair flags remained enabled after PC.solve()");
		}
	}

	private static void testColumnizedOutsourcingRequiresPairedCertificate() throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		data.n = 3;
		TWETBPCConfig config = new TWETBPCConfig();
		config.outsourcingModel = "columns";

		Pool pool = new Pool(data);
		CutPool cutPool = new CutPool();
		Node node = new Node(data, new ArrayList<Integer>(), new ArrayList<Integer>(), 0.0);
		LP lp = new LP(data, pool, cutPool, config, new OutsourcingPool(data));
		lp.construct(node, node.seedColumnIds);

		TrackingOutsourcingPricingEngine outsourcing = new TrackingOutsourcingPricingEngine(data, config);
		List<PricingEngine> engines = List.of(new ClosedInternalPricingEngine(), outsourcing);
		PC pc = new PC(config, PricingMode.OTHER, engines,
				Collections.emptyList(), new BPCTraceSink() { });

		TWETMasterSolution solution = invokeAfterCutRepair(pc, lp);
		if (!outsourcing.called) {
			throw new AssertionError("columnized Phase-I did not request the outsourcing exact certificate");
		}
		if (solution.getStatus() != TWETMasterStatus.INFEASIBLE) {
			throw new AssertionError("paired internal/outsourcing closure did not certify positive Phase-I residual");
		}
		if (lp.isFeasibilityRepairMode() || lp.isFeasibilityPhaseOneObjectiveMode()) {
			throw new AssertionError("repair flags remained enabled after certified infeasibility");
		}
	}

	private static void testSubsetRowCutIgnoresColumnizedOutsourcingColumns() throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		data.n = 3;
		TWETBPCConfig config = new TWETBPCConfig();
		config.outsourcingModel = "columns";

		Pool pool = new Pool(data);
		int internalColumnId = pool.addColumn(List.of(1, 2, 3), 3.0, ColumnSource.MANUAL, true);
		OutsourcingPool outsourcingPool = new OutsourcingPool(data);
		int outsourcingColumnId = outsourcingPool.addColumn(new TWETOutsourcingColumn(-1,
				List.of(1, 2, 3), data.n, 3.0, 3.0, ColumnSource.MANUAL, true));
		CutPool cutPool = new CutPool();
		int cutId = cutPool.addCut(new TWETCut(-1, TWETCutType.SUBSET_ROW,
				List.of(1, 2, 3), 0.0, "internal-only SRI"));
		Node internalOnlyNode = new Node(data, List.of(internalColumnId), List.of(internalColumnId), 0.0);
		internalOnlyNode.activeCutIds.add(Integer.valueOf(cutId));
		LP internalOnlyLp = new LP(data, pool, cutPool, config, outsourcingPool);
		internalOnlyLp.construct(internalOnlyNode, internalOnlyNode.seedColumnIds);
		TWETMasterSolution blocked = internalOnlyLp.solveRelaxation();
		if (blocked.getStatus() != TWETMasterStatus.INFEASIBLE) {
			throw new AssertionError("Columnized RMP did not enforce SRI on an internal scheduling column");
		}
		internalOnlyLp.closeModel();

		Node node = new Node(data, List.of(internalColumnId), List.of(internalColumnId), 0.0);
		node.seedOutsourcingColumnIds.add(Integer.valueOf(outsourcingColumnId));
		LP lp = new LP(data, pool, cutPool, config, outsourcingPool);
		lp.construct(node, node.seedColumnIds);
		TWETMasterSolution beforeCut = lp.solveRelaxation();
		if (beforeCut.getStatus() != TWETMasterStatus.LP_RELAXATION) {
			throw new AssertionError("Columnized RMP was infeasible before incremental SRI");
		}
		lp.addCuts(List.of(Integer.valueOf(cutId)));
		TWETMasterSolution solution = lp.resolveCurrentModel();
		if (solution.getStatus() != TWETMasterStatus.LP_RELAXATION) {
			throw new AssertionError("SRI incorrectly constrained a columnized outsourcing column: "
					+ solution.getStatus() + ", message=" + solution.getMessage());
		}
		if (solution.getColumnValues().containsKey(Integer.valueOf(internalColumnId))) {
			throw new AssertionError("Incremental SRI did not remove the blocked internal scheduling column");
		}
		double[] outsourcingValues = solution.getOutsourcingValues();
		for (int job = 1; job <= data.n; job++) {
			if (outsourcingValues[job] < 1.0 - 1e-8) {
				throw new AssertionError("Outsourcing column did not retain zero SRI coefficient for job " + job);
			}
		}
		lp.closeModel();
	}

	private static TWETMasterSolution invokeAfterCutRepair(PC pc, LP lp) throws Exception {
		Method method = PC.class.getDeclaredMethod("repairAfterCutPhaseOne", LP.class);
		method.setAccessible(true);
		try {
			return (TWETMasterSolution) method.invoke(pc, lp);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			throw exception;
		}
	}

	private static final class PhaseOneOnlyPricingEngine implements PricingEngine {
		private final int jobCount;
		boolean phaseOneColumnReturned;

		PhaseOneOnlyPricingEngine(int jobCount) {
			this.jobCount = jobCount;
		}

		@Override
		public PricingResult price(LP lp) {
			if (!lp.isFeasibilityPhaseOneObjectiveMode()) {
				return PricingResult.noImprovement("normal exact closed")
						.withCertifiedInternalReducedCost(0.0);
			}
			phaseOneColumnReturned = true;
			TWETColumn column = new TWETColumn(-1, List.of(1, 2, 3), jobCount, 10.0,
					ColumnSource.PRICING_EXACT, false);
			return new PricingResult(Collections.singletonList(column), true, "Phase-I repair column")
					.withCertifiedInternalReducedCost(-1.0);
		}

		@Override
		public boolean supportsFeasibilityPhaseOneObjective() {
			return true;
		}

		@Override
		public String getName() {
			return "PhaseOneOnlyPricing";
		}
	}

	private static final class OneShotSubsetRowCutGenerator implements CutGenerator {
		boolean separated;

		@Override
		public CutGenerationResult separate(LP lp) {
			if (separated) {
				return CutGenerationResult.empty("already separated");
			}
			separated = true;
			TWETCut cut = new TWETCut(-1, TWETCutType.SUBSET_ROW, List.of(1, 2, 3), 1.0,
					"after-cut Phase-I regression");
			return new CutGenerationResult(Collections.singletonList(cut), true, "test cut");
		}

		@Override
		public String getName() {
			return "OneShotSubsetRowCut";
		}
	}

	private static final class ClosedInternalPricingEngine implements PricingEngine {
		@Override
		public PricingResult price(LP lp) {
			return PricingResult.noImprovement("internal closed")
					.withCertifiedInternalReducedCost(0.0);
		}

		@Override
		public boolean supportsFeasibilityPhaseOneObjective() {
			return true;
		}

		@Override
		public String getName() {
			return "ClosedInternalPricing";
		}
	}

	private static final class TrackingOutsourcingPricingEngine extends OutsourcingPricingEngine {
		boolean called;

		TrackingOutsourcingPricingEngine(Data data, TWETBPCConfig config) {
			super(data, config);
		}

		@Override
		public PricingResult price(LP lp, TimeLimitChecker timeLimitChecker) {
			called = true;
			return super.price(lp, timeLimitChecker);
		}
	}
}
