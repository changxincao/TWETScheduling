package TWETBPC.LP;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.CUT.NoOpCutGenerator;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.TimeIndexedGraphPricingEngine;
import TWETBPC.GC.TimeIndexedScalarCompletionBound;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/**
 * 2026-07-02: ng-DSSR root 的可选 time-indexed 预处理器。
 * <p>
 * 它使用独立列池求解一个 no-cut/no-SRI time-indexed root，只把 root 闭合后得到的
 * time-indexed 禁弧与 compact window 证据复制回正式 root；临时 graph 列不会进入主线 Pool。
 */
final class TimeIndexedRootPreprocessor {

	private TimeIndexedRootPreprocessor() {
	}

	static Result run(Data data, TWETBPCConfig config, Pool mainPool, Node root, double incumbentCost,
			BPCTraceSink traceSink, TimeLimitChecker timeLimitChecker) {
		long start = System.nanoTime();
		if (!shouldRun(config)) {
			return Result.skipped("disabled or incompatible main pricing");
		}
		if (root == null || root.depth != 0) {
			return Result.skipped("missing root node");
		}
		if (!Double.isFinite(incumbentCost)) {
			return Result.skipped("missing incumbent upper bound");
		}
		TWETBPCConfig preConfig = preprocessingConfig(config);
		Pool prePool = new Pool(data);
		OutsourcingPool preOutsourcingPool = new OutsourcingPool(data);
		CutPool preCutPool = new CutPool();
		ArrayList<Integer> seedIds = copyColumns(mainPool, prePool, root.seedColumnIds);
		ArrayList<Integer> incumbentIds = copyColumns(mainPool, prePool, root.incumbentColumnIds);
		Node preRoot = new Node(data, seedIds, incumbentIds, root.pseudoCost);
		ArrayList<PricingEngine> engines = new ArrayList<PricingEngine>();
		engines.add(new TimeIndexedGraphPricingEngine(data, preConfig));
		ArrayList<CutGenerator> cuts = new ArrayList<CutGenerator>();
		cuts.add(new NoOpCutGenerator());
		PC prePc = new PC(preConfig, engines, cuts, traceSink);
		prePc.setTimeLimitChecker(timeLimitChecker);
		LP preLp = new LP(data, prePool, preCutPool, preConfig, preOutsourcingPool);
		try {
			if (isTimeLimitReached(timeLimitChecker)) {
				return Result.skipped("time limit before preprocessing");
			}
			preLp.construct(preRoot, preRoot.seedColumnIds);
			TWETMasterSolution solution = prePc.solve(preLp, incumbentCost);
			if (isTimeLimitReached(timeLimitChecker)) {
				return Result.skipped("time limit during preprocessing");
			}
			if (solution == null || solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				return Result.skipped("time-indexed preprocessing root infeasible");
			}
			if (solution.getStatus() != TWETMasterStatus.LP_RELAXATION && !solution.isInteger()) {
				return Result.skipped("time-indexed preprocessing root not solved");
			}
			TimeIndexedGraphPricingEngine.ArcFixingResult graphFix =
					TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing(data, preConfig, preLp, incumbentCost);
			TimeIndexedScalarCompletionBound.ArcFixingResult scalarFix =
					TimeIndexedScalarCompletionBound.applyArcFixing(data, preConfig, preLp, incumbentCost);
			root.copyTimeIndexedPricingStateFrom(preRoot);
			int promotedOrdinaryArcs =
					TimeIndexedGraphPricingEngine.promoteFullyForbiddenTimeIndexedArcsToPricingOnly(data, preLp, root);
			return Result.applied(prePool.size(), preRoot.countTimeIndexedPricingOnlyForbiddenArcs(), promotedOrdinaryArcs,
					preRoot.countTimeIndexedPricingWindowTightenedJobs(), preRoot.averageTimeIndexedPricingWindowLength(),
					preRoot.averageTimeIndexedPricingWindowShrinkRatio(), graphFix.summary(), scalarFix.summary(),
					System.nanoTime() - start);
		} finally {
			preLp.closeModel();
		}
	}

	private static boolean shouldRun(TWETBPCConfig config) {
		return config.enableTimeIndexedRootPreprocessingForNgDssr
				&& !config.useTimeIndexedGraphPricing
				&& (config.useGCNGBBStyleNgDssrPricing
						|| config.useGCNGBBStyleNgDssrPartialDominancePricing
						|| config.useGCNGBBStyleNgDssrGraphPartialDominancePricing)
				&& !config.useColumnizedOutsourcing();
	}

	private static TWETBPCConfig preprocessingConfig(TWETBPCConfig source) {
		TWETBPCConfig copy = copyConfig(source);
		copy.enableTimeIndexedRootPreprocessingForNgDssr = false;
		copy.useTimeIndexedGraphPricing = true;
		copy.useTimeIndexedGraphRank1CutPricing = false;
		copy.enableSubsetRowCutsForTimeIndexedGraph = false;
		copy.enableSubsetRowCutsForPartialDominance = false;
		copy.maxCutRounds = 0;
		copy.enableDualStabilization = false;
		copy.enableDualBoundPruning = false;
		copy.enableRestrictedMasterIntegerHeuristic = false;
		copy.timeIndexedCompletionBoundScalarEnhancement = true;
		copy.timeIndexedCompletionBoundArcFixing = true;
		copy.timeIndexedCompletionBoundWindowTightening = true;
		copy.timeIndexedCompletionBoundCutLoopArcFixing = false;
		copy.timeIndexedCompletionBoundInRoundArcFixing = false;
		copy.timeIndexedCompletionBoundSriAwareArcFixing = false;
		return copy;
	}

	private static TWETBPCConfig copyConfig(TWETBPCConfig source) {
		TWETBPCConfig copy = new TWETBPCConfig();
		for (Field field : TWETBPCConfig.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			try {
				field.set(copy, field.get(source));
			} catch (IllegalAccessException ex) {
				throw new IllegalStateException("Failed to copy TWETBPCConfig field: " + field.getName(), ex);
			}
		}
		return copy;
	}

	private static ArrayList<Integer> copyColumns(Pool source, Pool target, List<Integer> ids) {
		ArrayList<Integer> copied = new ArrayList<Integer>();
		for (int id : ids) {
			TWETColumn column = source.getColumn(id);
			copied.add(Integer.valueOf(target.addColumn(column.getSequence(), column.getCost(),
					column.getSource(), column.isSeedColumn())));
		}
		return copied;
	}

	private static boolean isTimeLimitReached(TimeLimitChecker checker) {
		return checker != null && checker.isTimeLimitReached();
	}

	static final class Result {
		final boolean applied;
		final String message;
		final long elapsedNanos;

		private Result(boolean applied, String message, long elapsedNanos) {
			this.applied = applied;
			this.message = message;
			this.elapsedNanos = elapsedNanos;
		}

		static Result skipped(String message) {
			return new Result(false, message, 0L);
		}

		static Result applied(int tempPoolSize, int timeArcCount, int promotedOrdinaryArcs, int tightenedJobs,
				double avgWindowLength, double avgShrinkRatio, String graphSummary, String scalarSummary, long elapsedNanos) {
			String message = "applied tempPool=" + tempPoolSize
					+ ", timeArcs=" + timeArcCount
					+ ", promotedOrdinaryArcs=" + promotedOrdinaryArcs
					+ ", windowJobs=" + tightenedJobs
					+ ", avgWindowLen=" + format(avgWindowLength)
					+ ", avgShrinkRatio=" + format(avgShrinkRatio)
					+ ", graphFix={" + graphSummary + "}"
					+ ", scalarFix={" + scalarSummary + "}";
			return new Result(true, message, elapsedNanos);
		}

		String summary() {
			return (applied ? "timeIndexedRootPreprocess.done " : "timeIndexedRootPreprocess.skip ")
					+ message + ", ms=" + String.format("%.3f", elapsedNanos / 1_000_000.0);
		}

		private static String format(double value) {
			return Double.isFinite(value) ? String.format("%.3f", value) : "NA";
		}
	}
}
