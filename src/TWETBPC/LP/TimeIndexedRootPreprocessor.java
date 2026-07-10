package TWETBPC.LP;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import Basic.Data;
import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.CUT.NoOpCutGenerator;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.PricingResult;
import TWETBPC.GC.TimeIndexedGraphPricingEngine;
import TWETBPC.GC.TimeIndexedScalarCompletionBound;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/**
 * 2026-07-02: ng-DSSR root 前置 time-indexed 预处理。
 * <p>
 * 该预处理在正式 root 前临时求解一个 no-cut/no-SRI 的 time-indexed root，
 * 只把可证明安全的 pricing-only arc、time-indexed arc 状态和 compact window
 * 证据复制回正式 root；临时 graph 列不会进入主线 Pool。
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
			if (config.timeIndexedDualWindowRecheckDiagnostics && preConfig.enableTimeIndexedGraphDualWindow) {
				TWETBPCConfig noDualCheckConfig = copyConfig(preConfig);
				noDualCheckConfig.enableTimeIndexedGraphDualWindow = false;
				noDualCheckConfig.timeIndexedGraphMaxExactPricingColumns =
						Math.max(1, Math.min(10, noDualCheckConfig.timeIndexedGraphMaxExactPricingColumns));
				PricingResult noDualCheck =
						new TimeIndexedGraphPricingEngine(data, noDualCheckConfig).price(preLp, timeLimitChecker);
				traceSink.onStageHeartbeat(root,
						"timeIndexedRootPreprocess.noDualClosureCheck cols=" + noDualCheck.getColumns().size()
								+ ", improved=" + noDualCheck.isImproved()
								+ ", msg=" + noDualCheck.getMessage(),
						prePool.size(), preCutPool.size());
			}
			ColumnSolutionStats rootStats = ColumnSolutionStats.from(solution, prePool, data.n);
			if (config.timeIndexedDualWindowRecheckDiagnostics) {
				dumpPositiveColumnDiagnostics(data, solution, prePool, preRoot, traceSink, prePool.size(), preCutPool.size());
			}
			TimeIndexedGraphPricingEngine.ArcFixingResult graphFix =
					TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing(data, preConfig, preLp, incumbentCost);
			TimeIndexedScalarCompletionBound.ArcFixingResult scalarFix =
					TimeIndexedScalarCompletionBound.applyArcFixing(data, preConfig, preLp, incumbentCost);
			root.copyTimeIndexedPricingStateFrom(preRoot);
			int promotedOrdinaryArcs =
					TimeIndexedGraphPricingEngine.promoteFullyForbiddenTimeIndexedArcsToPricingOnly(data, preLp, root);
			int seedColumnsCopied = copyBestElementaryColumnsToMainRoot(data, config, prePool, preLp, mainPool, root);
			return Result.applied(prePool.size(), preRoot.countTimeIndexedPricingOnlyForbiddenArcs(), promotedOrdinaryArcs,
					preRoot.countTimeIndexedPricingWindowTightenedJobs(), preRoot.averageTimeIndexedPricingWindowLength(),
					preRoot.averageTimeIndexedPricingWindowShrinkRatio(), seedColumnsCopied, rootStats.summary(),
					graphFix.summary(), scalarFix.summary(), System.nanoTime() - start);
		} finally {
			preLp.closeModel();
		}
	}

	private static void dumpPositiveColumnDiagnostics(Data data, TWETMasterSolution solution, Pool pool, Node node,
			BPCTraceSink traceSink, int poolSize, int cutPoolSize) {
		TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
		ArrayList<java.util.Map.Entry<Integer, Double>> positive =
				new ArrayList<java.util.Map.Entry<Integer, Double>>(solution.getColumnValues().entrySet());
		positive.sort(new Comparator<java.util.Map.Entry<Integer, Double>>() {
			@Override
			public int compare(java.util.Map.Entry<Integer, Double> a, java.util.Map.Entry<Integer, Double> b) {
				int valueCompare = Double.compare(b.getValue().doubleValue(), a.getValue().doubleValue());
				if (valueCompare != 0) {
					return valueCompare;
				}
				return Integer.compare(a.getKey().intValue(), b.getKey().intValue());
			}
		});
		int index = 0;
		for (java.util.Map.Entry<Integer, Double> entry : positive) {
			double value = entry.getValue().doubleValue();
			if (value <= 1e-8) {
				continue;
			}
			TWETColumn column = pool.getColumn(entry.getKey().intValue());
			double trueCost = evaluator.evaluate(column.getSequence());
			traceSink.onStageHeartbeat(node, "timeIndexedRootPreprocess.positiveColumn idx=" + index
					+ ", id=" + entry.getKey()
					+ ", value=" + value
					+ ", elementary=" + isElementary(column, data.n)
					+ ", len=" + column.size()
					+ ", storedCost=" + column.getCost()
					+ ", evalCost=" + trueCost
					+ ", costDiff=" + (column.getCost() - trueCost)
					+ ", seq=" + column.getSequence(),
					poolSize, cutPoolSize);
			index++;
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

	private static int copyBestElementaryColumnsToMainRoot(Data data, TWETBPCConfig config, Pool prePool, LP preLp,
			Pool mainPool, Node root) {
		if (!config.timeIndexedRootPreprocessingSeedElementaryColumns
				|| config.timeIndexedRootPreprocessingSeedColumnLimit <= 0) {
			return 0;
		}
		LP.PricingDualSnapshot dual = preLp.captureTruePricingDuals();
		ArrayList<ScoredColumn> candidates = new ArrayList<ScoredColumn>();
		for (int columnId : preLp.getRestrictedColumnIds()) {
			TWETColumn column = prePool.getColumn(columnId);
			if (!isElementary(column, data.n)) {
				continue;
			}
			if (usesPricingOnlyForbiddenArc(column, root)) {
				continue;
			}
			candidates.add(new ScoredColumn(columnId, column, preLp.computeReducedCost(column, dual)));
		}
		candidates.sort(new Comparator<ScoredColumn>() {
			@Override
			public int compare(ScoredColumn a, ScoredColumn b) {
				int rc = Double.compare(a.reducedCost, b.reducedCost);
				if (rc != 0) {
					return rc;
				}
				return Integer.compare(a.sourceColumnId, b.sourceColumnId);
			}
		});
		HashSet<Integer> existingSeedIds = new HashSet<Integer>(root.seedColumnIds);
		int copied = 0;
		for (ScoredColumn candidate : candidates) {
			if (copied >= config.timeIndexedRootPreprocessingSeedColumnLimit) {
				break;
			}
			TWETColumn column = candidate.column;
			Pool.ColumnUpdate update = mainPool.addOrImproveColumn(column.getSequence(), column.getCost(),
					column.getSource(), true);
			if (existingSeedIds.add(Integer.valueOf(update.columnId))) {
				root.seedColumnIds.add(Integer.valueOf(update.columnId));
				copied++;
			}
		}
		return copied;
	}

	private static boolean isElementary(TWETColumn column, int jobCount) {
		if (column.size() == 0) {
			return false;
		}
		boolean[] seen = new boolean[jobCount + 1];
		for (int job : column.getSequence()) {
			if (job < 1 || job > jobCount || seen[job]) {
				return false;
			}
			seen[job] = true;
		}
		return true;
	}

	private static boolean usesPricingOnlyForbiddenArc(TWETColumn column, Node root) {
		if (root == null || column.size() == 0) {
			return false;
		}
		List<Integer> sequence = column.getSequence();
		if (root.isPricingOnlyArcForbidden(0, sequence.get(0).intValue())) {
			return true;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (root.isPricingOnlyArcForbidden(sequence.get(i - 1).intValue(), sequence.get(i).intValue())) {
				return true;
			}
		}
		return root.isPricingOnlyArcForbidden(sequence.get(sequence.size() - 1).intValue(), root.sinkId());
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
				double avgWindowLength, double avgShrinkRatio, int seedColumnsCopied, String rootColumnStats,
				String graphSummary, String scalarSummary, long elapsedNanos) {
			String message = "applied tempPool=" + tempPoolSize
					+ ", timeArcs=" + timeArcCount
					+ ", promotedOrdinaryArcs=" + promotedOrdinaryArcs
					+ ", windowJobs=" + tightenedJobs
					+ ", avgWindowLen=" + format(avgWindowLength)
					+ ", avgShrinkRatio=" + format(avgShrinkRatio)
					+ ", seedElementaryCols=" + seedColumnsCopied
					+ ", rootSolution={" + rootColumnStats + "}"
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

	private static final class ScoredColumn {
		final int sourceColumnId;
		final TWETColumn column;
		final double reducedCost;

		ScoredColumn(int sourceColumnId, TWETColumn column, double reducedCost) {
			this.sourceColumnId = sourceColumnId;
			this.column = column;
			this.reducedCost = reducedCost;
		}
	}
}
