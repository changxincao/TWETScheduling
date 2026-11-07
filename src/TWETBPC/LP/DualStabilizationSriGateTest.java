package TWETBPC.LP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Common.Utility;
import Output.BPCTraceSink;
import TWETBPC.BestBpcProfiles;
import TWETBPC.TWETBPCConfig;
import TWETBPC.GC.OutsourcingPricingEngine;
import TWETBPC.GC.PricingMode;
import TWETBPC.GC.TimeIndexedGraphRank1CutPricingEngine;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/** SRI开关或继承割存在时禁止稳定化，不依赖割是否已产生负dual。 */
public final class DualStabilizationSriGateTest {

	public static void main(String[] args) throws Exception {
		for (int horizon : new int[] {4, 5}) {
			for (double incumbent : new double[] {4.0, Double.POSITIVE_INFINITY}) {
				double reference = solve(horizon, false, true, false, incumbent);
				double actual = solve(horizon, true, true, false, incumbent);
				check(Math.abs(actual - reference) <= 1e-8, "SRI exclusion changed the final RMP value");
			}
		}
		solve(4, true, true, true, Double.POSITIVE_INFINITY);
		solve(4, true, true, false, Double.POSITIVE_INFINITY, true);
		double reference = solve(4, false, false, false, Double.POSITIVE_INFINITY);
		double actual = solve(4, true, false, false, Double.POSITIVE_INFINITY);
		check(Math.abs(actual - reference) <= 1e-8, "no-cut stabilization changed the closed RMP value");
		solve(4, true, false, false, Double.POSITIVE_INFINITY, false, 1);
		solve(4, true, false, false, Double.POSITIVE_INFINITY, false, 2);
		solve(4, true, true, false, Double.POSITIVE_INFINITY, false, 0);
		System.out.println("DualStabilizationSriGateTest passed");
	}

	private static double solve(int horizon, boolean stabilized, boolean withCut, boolean seedPseudo,
			double incumbent) throws Exception {
		return solve(horizon, stabilized, withCut, seedPseudo, incumbent, false);
	}

	private static double solve(int horizon, boolean stabilized, boolean withCut, boolean seedPseudo,
			double incumbent, boolean stopAfterActivation) throws Exception {
		return solve(horizon, stabilized, withCut, seedPseudo, incumbent, stopAfterActivation, withCut ? 1 : 0);
	}

	private static double solve(int horizon, boolean stabilized, boolean withCut, boolean seedPseudo,
			double incumbent, boolean stopAfterActivation, int sriSwitch) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		Path input = Files.createTempFile("twet-stabilization-sri-", ".dat");
		Data data;
		try {
			Files.writeString(input, "3 1\n1 1 1 1 1\n1 2 2 1 1\n1 3 3 1 1\n"
					+ "SETUP\n0 0 0 0\n0 0 0 0\n0 0 0 0\n0 0 0 0\n"
					+ "SETUP_COST\n0 4 4 4\n0 0 0 0\n0 0 0 0\n0 0 0 0\n"
					+ "OUTSOURCING_COST\n4 4 4\nOUTSOURCING_TARIFF\n1\n0 12 1 0\n");
			data = new Data(input.toString(), true, true);
		} finally { Files.deleteIfExists(input); }
		data.CmaxH = horizon;
		data.setPenaltyFunctions();
		TWETBPCConfig config = new TWETBPCConfig();
		BestBpcProfiles.TIME_INDEXED_GRAPH_RANK1.apply(config);
		config.enableDualStabilization = stabilized;
		config.enableSubsetRowCutsForTimeIndexedGraph = sriSwitch == 1;
		config.enableSubsetRowCutsForPartialDominance = sriSwitch == 2;
		config.outsourcingModel = "columns";
		config.timeIndexedGraphMaxExactPricingColumns = 3;
		config.enableTimeIndexedGraphDualWindow = false;
		config.timeIndexedCompletionBoundArcFixing = false;
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		Pool pool = new Pool(data);
		CutPool cuts = new CutPool();
		OutsourcingPool outside = new OutsourcingPool(data);
		TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
		ArrayList<Integer> seeds = new ArrayList<>();
		for (int job = 1; job <= 3; job++) {
			seeds.add(pool.addColumn(List.of(job), evaluator.evaluate(List.of(job)), ColumnSource.MANUAL, true));
		}
		if (seedPseudo) {
			List<Integer> route = List.of(1, 2, 3, 2);
			seeds.add(pool.addColumn(route, evaluator.evaluate(route), ColumnSource.MANUAL, true));
		}
		Node node = new Node(data, seeds, seeds, 0.0);
		node.id = 1;
		if (withCut) {
			node.activeCutIds.add(cuts.addCut(new TWETCut(-1, TWETCutType.SUBSET_ROW,
					List.of(1, 2, 3), 1.0, "inherited SRI")));
		}
		for (List<Integer> jobs : List.of(List.of(1, 2, 3), List.of(1, 3))) {
			node.seedOutsourcingColumnIds.add(outside.addColumn(jobs, ColumnSource.MANUAL, true));
		}
		LP lp = new LP(data, pool, cuts, config, outside);
		try {
			lp.construct(node, seeds);
			lp.solveRelaxation();
			check(lp.supportsDualStabilizationSnapshot(), "fixture cannot enter stabilization");
			check(lp.getActiveCutIds().size() == (withCut ? 1 : 0), "SRI row was not installed");
			check(lp.getActiveSubsetRowPricingCutIds().isEmpty() != seedPseudo,
					"fixture did not produce the expected initial cut dual");
			double feasible = evaluator.evaluate(List.of(1, 2, 3));
			check(Math.abs(feasible - 4.0) <= 1e-8, "known feasible route cost changed");
			Trace trace = new Trace(lp, stopAfterActivation);
			PC pc = new PC(config, PricingMode.TIME_INDEXED_RANK1,
					List.of(new TimeIndexedGraphRank1CutPricingEngine(data, config),
							new OutsourcingPricingEngine(data, config)), List.of(), trace);
			pc.setTimeLimitChecker(() -> trace.stopped);
			TWETMasterSolution result = pc.solve(lp, incumbent);
			double bound = pc.getLastObservedDualBound();
			System.out.println("H=" + horizon + " stabilized=" + stabilized + " cut=" + withCut
					+ " sriSwitch=" + sriSwitch + " initiallyNegative=" + seedPseudo + " incumbent=" + incumbent + " RMP="
					+ result.getObjectiveValue() + " bound=" + bound + " stableCalls=" + trace.stableCalls
					+ " rawSriCalls=" + trace.rawSriCalls + " mixedSriCalls=" + trace.mixedSriCalls
					+ " stopped=" + trace.stopped);
			check(Double.isFinite(bound) && bound <= feasible + 1e-8, "pricing bound exceeds a feasible value");
			check(trace.mixedSriCalls == 0, "stabilized pricing consumed a newly negative SRI dual");
			check(!lp.hasPricingDualOverride(), "stabilized override leaked");
			if (withCut && !stopAfterActivation) check(trace.rawSriCalls > 0, "SRI never reached true-dual pricing");
			check((trace.stableCalls > 0) == (stabilized && sriSwitch == 0 && !withCut),
					"SRI-enabled configuration entered stabilization");
			if (stopAfterActivation) {
				check(trace.stopped && !lp.getActiveSubsetRowPricingCutIds().isEmpty(), "timeout boundary not reached");
				check(result.getStatus() == TWETMasterStatus.LP_RELAXATION && trace.rawSriCalls == 0,
						"timeout continued pricing or changed master status");
				check(Math.abs(bound - 2.0) <= 1e-8 && Math.abs(result.getObjectiveValue() - 7.0) <= 1e-8,
						"timeout promoted an unclosed RMP objective to a bound");
			}
			if (withCut && !Double.isFinite(incumbent) && !stopAfterActivation) {
				check(Math.abs(result.getObjectiveValue() - feasible) <= 1e-8, "full CG did not close at the optimum");
			}
			return result.getObjectiveValue();
		} finally { lp.closeModel(); }
	}

	private static final class Trace implements BPCTraceSink {
		final LP lp;
		final boolean stopAfterActivation;
		boolean stopped;
		int stableCalls;
		int rawSriCalls;
		int mixedSriCalls;

		Trace(LP lp, boolean stopAfterActivation) {
			this.lp = lp;
			this.stopAfterActivation = stopAfterActivation;
		}

		@Override public void onMasterLpSolution(Node node, String phase, TWETMasterSolution solution,
				int restrictedColumnCount, int poolSize, long elapsedNanos) {
			if (stopAfterActivation && ("after_pricing_stabilized".equals(phase) || "after_pricing".equals(phase))
					&& !lp.getActiveSubsetRowPricingCutIds().isEmpty()) stopped = true;
		}

		@Override public void onPricingCall(Node node, String name, boolean improved, int added, String message,
				int poolSize) {
			boolean smoothed = name.contains("[stabilized.");
			if (smoothed) stableCalls++;
			if (!lp.getActiveSubsetRowPricingCutIds().isEmpty()) {
				if (smoothed || lp.hasPricingDualOverride()) mixedSriCalls++;
				else rawSriCalls++;
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
