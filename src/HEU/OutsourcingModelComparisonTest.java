package HEU;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Output.BPCOutputFormatters;
import Output.BPCTraceSummary;
import Output.ValidationResult;
import Output.BPCSolutionValidator;
import TWETBPC.LP.Pool;
import TWETBPC.Model.TWETColumn;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;

/**
 * 对拍 SP2 外包变量模型和 SP1 外包列模型。
 * <p>
 * 2026-06-20: 这个入口只用于有限外包成本小算例的正确性/速度对比，不改变正式求解路径。
 */
public final class OutsourcingModelComparisonTest {

	private static final double VALUE_TOLERANCE = 1e-5;

	private OutsourcingModelComparisonTest() {
	}

	public static void main(String[] args) throws Exception {
		int n = Integer.getInteger("twet.bpc.outsourcingCompare.n", 12);
		int machines = Integer.getInteger("twet.bpc.outsourcingCompare.m", 2);
		int tariffSegments = Integer.getInteger("twet.bpc.outsourcingCompare.tariffSegments", 1);
		double outsourcingScale = Double.parseDouble(
				System.getProperty("twet.bpc.outsourcingCompare.outsourcingScale", "1.0"));
		String[] caseTokens = System.getProperty("twet.bpc.outsourcingCompare.cases", "0,1,2,3").split(",");
		String requestedModels = System.getProperty("twet.bpc.outsourcingCompare.models", "masterVariables,columns");
		boolean runMasterVariables = containsModel(requestedModels, "masterVariables");
		boolean runColumns = containsModel(requestedModels, "columns");
		Path output = Paths.get(System.getProperty("twet.bpc.outsourcingCompare.output",
				"test-results/bpc/2026-06-20-outsourcing-model-comparison.csv"));
		Files.createDirectories(output.getParent());

		ArrayList<PairRecord> pairs = new ArrayList<PairRecord>();
		try (BufferedWriter writer = Files.newBufferedWriter(output)) {
			writer.write("case_id,n,m,outsourcing_scale,tariff_segments,model,status,incumbent,bound,root_bound,gap_percent,valid,nodes,pricing_rounds,"
					+ "generated_columns,pool_size,solve_s,root_s,heuristic_s,heuristic_calls,exact_s,"
					+ "exact_calls,master_lp_s,internal_columns,internal_jobs,internal_cost,outsourced_jobs,"
					+ "outsourcing_baseline,outsourcing_cost,initial_incumbent,bound_gain_after_root,incumbent_gain,"
					+ "incumbent_updates,rmih_calls,rmih_improvements,pruned_by_incumbent,closed_without_branch,"
					+ "branch_calls,branch_detail,pricing_detail,objective_match,bound_match,root_bound_match\n");
			for (String token : caseTokens) {
				int caseId = Integer.parseInt(token.trim());
				RunRecord master = runMasterVariables
						? runCase(caseId, n, machines, outsourcingScale, tariffSegments, "masterVariables") : null;
				RunRecord columns = runColumns ? runCase(caseId, n, machines, outsourcingScale, tariffSegments,
						"columns") : null;
				if (master != null && columns != null) {
					PairRecord pair = new PairRecord(caseId, master, columns);
					pairs.add(pair);
					writer.write(master.toCsvLine(pair.objectiveMatch, pair.boundMatch, pair.rootBoundMatch));
					writer.newLine();
					writer.write(columns.toCsvLine(pair.objectiveMatch, pair.boundMatch, pair.rootBoundMatch));
					writer.newLine();
					System.out.println(pair.summaryLine());
				} else {
					if (master != null) {
						writer.write(master.toCsvLine(true, true, true));
						writer.newLine();
						System.out.println(summaryLine(master));
					}
					if (columns != null) {
						writer.write(columns.toCsvLine(true, true, true));
						writer.newLine();
						System.out.println(summaryLine(columns));
					}
				}
			}
		}

		System.out.println("CSV written: " + output.toAbsolutePath());
		if (!pairs.isEmpty()) {
			printAggregate(pairs);
		}
	}

	private static boolean containsModel(String models, String target) {
		for (String token : models.split(",")) {
			if (target.equalsIgnoreCase(token.trim())) {
				return true;
			}
		}
		return false;
	}

	private static String summaryLine(RunRecord record) {
		return String.format(Locale.US,
				"case=%d model=%s status=%s obj=%.6f bound=%.6f root=%.6f nodes=%d pricing=%d cols=%d time=%.3fs",
				record.caseId, record.model, record.status, record.incumbent, record.bound, record.rootBound,
				record.nodes, record.pricingRounds, record.generatedColumns, record.solveSeconds);
	}

	private static RunRecord runCase(int caseId, int n, int machines, double outsourcingScale, int tariffSegments,
			String outsourcingModel) throws Exception {
		Data data = SmallExactHeuristicBatchTest.buildRandomCase(caseId, n, machines);
		applyOutsourcingScale(data, caseId, outsourcingScale, tariffSegments);
		TWETBPCConfig config = buildConfig(caseId, n, machines, outsourcingModel);
		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		TWETSolveResult result = solver.solve();
		BPCTraceSummary summary = solver.getContext().traceSummary;
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);
		return new RunRecord(caseId, n, machines, outsourcingScale, tariffSegments, outsourcingModel, data,
				solver.getContext().pool, result, summary, validation, solver.getContext().pool.size());
	}

	private static void applyOutsourcingScale(Data data, int caseId, double outsourcingScale, int tariffSegments) {
		if (!Double.isFinite(outsourcingScale) || outsourcingScale <= 0.0) {
			throw new IllegalArgumentException("outsourcingScale must be positive: " + outsourcingScale);
		}
		if (tariffSegments <= 0) {
			throw new IllegalArgumentException("tariffSegments must be positive: " + tariffSegments);
		}
		if (close(outsourcingScale, 1.0) && tariffSegments == 1) {
			return;
		}
		double totalBaseline = 0.0;
		for (int job = 1; job <= data.n; job++) {
			data.outsourcingCost[job] *= outsourcingScale;
			totalBaseline += data.outsourcingCost[job];
		}
		double domainEnd = Math.max(1.0, totalBaseline);
		data.outsourcingCostFunction = new PiecewiseLinearFunction(0.0, domainEnd);
		if (tariffSegments == 1) {
			double outsourcingRate = 0.45 + 0.08 * (caseId % 7);
			data.outsourcingCostFunction.addSegment(0.0, domainEnd, outsourcingRate, 0.0);
		} else {
			// 2026-06-20: 用连续凹折扣 tariff 检查 SP1 外包列是否能强化 root bound。
			double segmentWidth = domainEnd / tariffSegments;
			double start = 0.0;
			double valueAtStart = 0.0;
			for (int segment = 0; segment < tariffSegments; segment++) {
				double end = segment == tariffSegments - 1 ? domainEnd : segmentWidth * (segment + 1);
				double slope = 1.0 / (segment + 1.0);
				double intercept = valueAtStart - slope * start;
				data.outsourcingCostFunction.addSegment(start, end, slope, intercept);
				valueAtStart += slope * (end - start);
				start = end;
			}
		}
		data.setPreprocessedHardWindows();
		data.setPenaltyFunctions();
	}

	private static TWETBPCConfig buildConfig(int caseId, int n, int machines, String outsourcingModel) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = "outsourcing-compare-c" + caseId + "-n" + n + "-m" + machines + "-" + outsourcingModel;
		config.enableBPCConsoleOutput = Boolean.parseBoolean(
				System.getProperty("twet.bpc.outsourcingCompare.console", "false"));
		config.writeBPCResultFiles = false;
		config.reuseConfiguredBestSolution = false;
		config.runALNSForSeed = false;
		config.outsourcingModel = outsourcingModel;
		config.maxNodes = Integer.getInteger("twet.bpc.outsourcingCompare.maxNodes", 5000);
		config.maxHeuristicPricingColumns = Integer.getInteger("twet.bpc.outsourcingCompare.maxHeuristicColumns",
				1500);
		config.heuristicPricingPoolSize = Integer.getInteger("twet.bpc.outsourcingCompare.heuristicPoolSize", 5000);
		config.maxExactPricingColumns = Integer.getInteger("twet.bpc.outsourcingCompare.maxExactColumns", 5000);
		config.maxOutsourcingPricingColumns = Integer.getInteger(
				"twet.bpc.outsourcingCompare.maxOutsourcingColumns", config.maxExactPricingColumns);
		config.branchSeedColumnLimit = Integer.getInteger("twet.bpc.outsourcingCompare.branchSeedColumnLimit", 5000);
		config.enableHeuristicPricing = Boolean.parseBoolean(
				System.getProperty("twet.bpc.outsourcingCompare.enableHeuristicPricing", "true"));
		config.enableRestrictedMasterIntegerHeuristic = Boolean.parseBoolean(
				System.getProperty("twet.bpc.outsourcingCompare.enableRMIH", "true"));
		config.restrictedMasterIntegerHeuristicTimeLimitSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.restrictedMasterIntegerTimeLimit",
				Double.toString(config.restrictedMasterIntegerHeuristicTimeLimitSeconds)));
		config.restrictedMasterIntegerHeuristicLargeInstanceThreshold = Integer.getInteger(
				"twet.bpc.outsourcingCompare.restrictedMasterIntegerLargeThreshold",
				config.restrictedMasterIntegerHeuristicLargeInstanceThreshold);
		config.restrictedMasterIntegerHeuristicLargeInstanceTimeLimitSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.restrictedMasterIntegerLargeTimeLimit",
				Double.toString(config.restrictedMasterIntegerHeuristicLargeInstanceTimeLimitSeconds)));
		config.enableUndirectedAdjacencyBranching = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.enableUndirectedAdjacencyBranching", "false"));
		config.useGCNGBBStyleNgDssrPricing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.useNgDssr", Boolean.toString(config.useGCNGBBStyleNgDssrPricing)));
		config.ngDssrInitialNgSetMode = System.getProperty("twet.bpc.outsourcingCompare.ngSetMode",
				config.ngDssrInitialNgSetMode);
		config.ngDssrInitialNgSetSize = Integer.getInteger("twet.bpc.outsourcingCompare.ngSetSize",
				config.ngDssrInitialNgSetSize);
		config.ngDssrNonElementaryRouteUpdateLimit = Integer.getInteger(
				"twet.bpc.outsourcingCompare.ngUpdateLimit", config.ngDssrNonElementaryRouteUpdateLimit);
		config.bidirectionalCompletionBoundRelaxation = System.getProperty(
				"twet.bpc.outsourcingCompare.completionBound", "allCycles");
		config.bidirectionalCompletionBoundQueueOrdering = System.getProperty(
				"twet.bpc.outsourcingCompare.completionBoundQueue", config.bidirectionalCompletionBoundQueueOrdering);
		config.bidirectionalCompletionBoundScalarPruning = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.completionBoundScalar", "true"));
		config.bidirectionalCompletionBoundArcFixing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.completionBoundArcFixing",
				Boolean.toString(config.bidirectionalCompletionBoundArcFixing)));
		config.bidirectionalCompletionBoundSubtreeArcElimination = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.completionBoundSubtreeArcElimination",
				Boolean.toString(config.bidirectionalCompletionBoundSubtreeArcElimination)));
		config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.completionBoundSubtreeArcEliminationPricingOnly",
				Boolean.toString(config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly)));
		config.bidirectionalMidpointProbe = Boolean.parseBoolean(
				System.getProperty("twet.bpc.outsourcingCompare.midpointProbe", "false"));
		config.bidirectionalMidpointProbePopLimit = Integer.getInteger(
				"twet.bpc.outsourcingCompare.midpointProbePopLimit", config.bidirectionalMidpointProbePopLimit);
		config.bidirectionalMidpointProbeMaxCandidates = Integer.getInteger(
				"twet.bpc.outsourcingCompare.midpointProbeMaxCandidates",
				config.bidirectionalMidpointProbeMaxCandidates);
		config.bidirectionalMidpointProbeReuseMaxCandidates = Integer.getInteger(
				"twet.bpc.outsourcingCompare.midpointProbeReuseMaxCandidates",
				config.bidirectionalMidpointProbeReuseMaxCandidates);
		config.bidirectionalMidpointProbeMoveRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.midpointProbeMoveRatio",
				Double.toString(config.bidirectionalMidpointProbeMoveRatio)));
		config.bidirectionalMidpointProbeScore = System.getProperty(
				"twet.bpc.outsourcingCompare.midpointProbeScore", config.bidirectionalMidpointProbeScore);
		config.bidirectionalMidpointProbeTieScore = System.getProperty(
				"twet.bpc.outsourcingCompare.midpointProbeTieScore", config.bidirectionalMidpointProbeTieScore);
		config.bidirectionalMidpointProbeEarlyStopRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.midpointProbeEarlyStopRatio",
				Double.toString(config.bidirectionalMidpointProbeEarlyStopRatio)));
		config.bidirectionalMidpointProbeExtraCandidatesAfterThreshold = Integer.getInteger(
				"twet.bpc.outsourcingCompare.midpointProbeExtraCandidatesAfterThreshold",
				config.bidirectionalMidpointProbeExtraCandidatesAfterThreshold);
		config.bidirectionalMidpointProbeBracketOnDirectionChange = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.midpointProbeBracketOnDirectionChange",
				Boolean.toString(config.bidirectionalMidpointProbeBracketOnDirectionChange)));
		config.bidirectionalMidpointProbeHighImbalanceRatio = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.midpointProbeHighImbalanceRatio",
				Double.toString(config.bidirectionalMidpointProbeHighImbalanceRatio)));
		config.bidirectionalMidpointProbeReuseWithinNode = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.midpointProbeReuseWithinNode",
				Boolean.toString(config.bidirectionalMidpointProbeReuseWithinNode)));
		config.enableDualStabilization = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.dualStabilization",
				Boolean.toString(config.enableDualStabilization)));
		config.dualStabilizationAlpha = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.dualStabilizationAlpha",
				Double.toString(config.dualStabilizationAlpha)));
		config.dualStabilizationCenterMoveWeight = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.dualStabilizationCenterMoveWeight",
				Double.toString(config.dualStabilizationCenterMoveWeight)));
		config.dualStabilizationAlphaIncreaseFraction = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.dualStabilizationAlphaIncreaseFraction",
				Double.toString(config.dualStabilizationAlphaIncreaseFraction)));
		config.dualStabilizationAlphaDecreaseStep = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.dualStabilizationAlphaDecreaseStep",
				Double.toString(config.dualStabilizationAlphaDecreaseStep)));
		config.dualStabilizationDirectionalSmoothing = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.dualStabilizationDirectionalSmoothing",
				Boolean.toString(config.dualStabilizationDirectionalSmoothing)));
		config.dualStabilizationReducedCostTolerance = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.dualStabilizationReducedCostTolerance",
				Double.toString(config.dualStabilizationReducedCostTolerance)));
		config.enableDualBoundPruning = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.dualBoundPruning",
				Boolean.toString(config.enableDualBoundPruning)));
		config.dualBoundPruningTolerance = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.dualBoundPruningTolerance",
				Double.toString(config.dualBoundPruningTolerance)));
		return config;
	}

	private static void printAggregate(List<PairRecord> pairs) {
		int objectiveMatches = 0;
		int boundMatches = 0;
		double masterSeconds = 0.0;
		double columnSeconds = 0.0;
		for (PairRecord pair : pairs) {
			if (pair.objectiveMatch) {
				objectiveMatches++;
			}
			if (pair.boundMatch) {
				boundMatches++;
			}
			masterSeconds += pair.master.solveSeconds;
			columnSeconds += pair.columns.solveSeconds;
		}
		System.out.println(String.format(Locale.US,
				"Aggregate: objectiveMatch=%d/%d, boundMatch=%d/%d, masterVariables=%.3fs, columns=%.3fs",
				objectiveMatches, pairs.size(), boundMatches, pairs.size(), masterSeconds, columnSeconds));
	}

	private static double sumSeconds(Map<String, Long> times) {
		long nanos = 0L;
		for (Long value : times.values()) {
			nanos += value.longValue();
		}
		return nanos / 1_000_000_000.0;
	}

	private static String compactPricing(Map<String, Integer> calls, Map<String, Integer> columns,
			Map<String, Long> times) {
		LinkedHashMap<String, String> parts = new LinkedHashMap<String, String>();
		for (String engine : calls.keySet()) {
			int callCount = calls.get(engine).intValue();
			int columnCount = columns.containsKey(engine) ? columns.get(engine).intValue() : 0;
			double seconds = times.containsKey(engine) ? times.get(engine).longValue() / 1_000_000_000.0 : 0.0;
			parts.put(engine, callCount + "/" + columnCount + "/" + fmt(seconds) + "s");
		}
		return parts.toString();
	}

	private static boolean close(double a, double b) {
		if (!Double.isFinite(a) || !Double.isFinite(b)) {
			return Double.isInfinite(a) && Double.isInfinite(b);
		}
		return Math.abs(a - b) <= Math.max(VALUE_TOLERANCE, VALUE_TOLERANCE * Math.max(Math.abs(a), Math.abs(b)));
	}

	private static String fmt(double value) {
		if (!Double.isFinite(value)) {
			return Double.toString(value);
		}
		return String.format(Locale.US, "%.6f", value);
	}

	private static String quote(String value) {
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	private static final class PairRecord {
		final int caseId;
		final RunRecord master;
		final RunRecord columns;
		final boolean objectiveMatch;
		final boolean boundMatch;
		final boolean rootBoundMatch;

		PairRecord(int caseId, RunRecord master, RunRecord columns) {
			this.caseId = caseId;
			this.master = master;
			this.columns = columns;
			this.objectiveMatch = close(master.incumbent, columns.incumbent);
			this.boundMatch = close(master.bound, columns.bound);
			this.rootBoundMatch = close(master.rootBound, columns.rootBound);
		}

		String summaryLine() {
			return String.format(Locale.US,
					"case=%d objectiveMatch=%s boundMatch=%s master inc/bound/time=%s/%s/%.3fs out=%d/%d intCols=%d columns inc/bound/time=%s/%s/%.3fs out=%d/%d intCols=%d valid=%s/%s",
					caseId, Boolean.toString(objectiveMatch), Boolean.toString(boundMatch), fmt(master.incumbent),
					fmt(master.bound), master.solveSeconds, master.outsourcedJobs, master.n, master.internalColumns,
					fmt(columns.incumbent), fmt(columns.bound), columns.solveSeconds, columns.outsourcedJobs,
					columns.n, columns.internalColumns, Boolean.toString(master.valid), Boolean.toString(columns.valid));
		}
	}

	private static final class RunRecord {
		final int caseId;
		final int n;
		final int machines;
		final double outsourcingScale;
		final int tariffSegments;
		final String model;
		final String status;
		final double incumbent;
		final double bound;
		final double rootBound;
		final double gap;
		final boolean valid;
		final int nodes;
		final int pricingRounds;
		final int generatedColumns;
		final int poolSize;
		final double solveSeconds;
		final double rootSeconds;
		final double heuristicSeconds;
		final int heuristicCalls;
		final double exactSeconds;
		final int exactCalls;
		final double masterLpSeconds;
		final int internalColumns;
		final int internalJobs;
		final double internalCost;
		final int outsourcedJobs;
		final double outsourcingBaseline;
		final double outsourcingCost;
		final double initialIncumbent;
		final double boundGainAfterRoot;
		final double incumbentGain;
		final int incumbentUpdates;
		final int rmihCalls;
		final int rmihImprovements;
		final int prunedByIncumbent;
		final int closedWithoutBranch;
		final int branchCalls;
		final String branchDetail;
		final String pricingDetail;

		RunRecord(int caseId, int n, int machines, double outsourcingScale, int tariffSegments, String model,
				Data data, Pool pool, TWETSolveResult result, BPCTraceSummary summary, ValidationResult validation,
				int poolSize) {
			this.caseId = caseId;
			this.n = n;
			this.machines = machines;
			this.outsourcingScale = outsourcingScale;
			this.tariffSegments = tariffSegments;
			this.model = model;
			this.status = result.getStatus().toString();
			this.incumbent = result.getIncumbentCost();
			this.bound = result.getBestBound();
			this.rootBound = summary.getRootBound();
			this.gap = BPCOutputFormatters.gapPercent(bound, incumbent);
			this.valid = validation.isFeasible();
			this.nodes = result.getProcessedNodes();
			this.pricingRounds = summary.getPricingRounds();
			this.generatedColumns = result.getGeneratedColumns();
			this.poolSize = poolSize;
			this.solveSeconds = summary.getSolveTimeSeconds();
			this.rootSeconds = summary.getRootSolveTimeSeconds();
			this.heuristicSeconds = pricingSeconds(summary, "heuristic");
			this.heuristicCalls = pricingCalls(summary, "heuristic");
			this.exactSeconds = sumSeconds(summary.getPricingTimeNanos()) - heuristicSeconds;
			this.exactCalls = summary.getPricingRounds() - heuristicCalls;
			this.masterLpSeconds = sumSeconds(summary.getMasterLpTimeNanos());
			this.initialIncumbent = summary.getInitialIncumbentCost();
			this.boundGainAfterRoot = Double.isFinite(rootBound) && Double.isFinite(bound) ? bound - rootBound : 0.0;
			this.incumbentGain = Double.isFinite(initialIncumbent) && Double.isFinite(incumbent)
					? initialIncumbent - incumbent : 0.0;
			this.incumbentUpdates = summary.getIncumbentUpdates();
			this.rmihCalls = summary.getRestrictedIntegerHeuristicCalls();
			this.rmihImprovements = summary.getRestrictedIntegerHeuristicImproveCount();
			this.prunedByIncumbent = summary.getPrunedByIncumbentCount();
			this.closedWithoutBranch = summary.getClosedWithoutBranchCount();
			this.branchCalls = summary.getBranchCalls();
			this.branchDetail = compactCounts(summary.getBranchSuccessCount());
			this.pricingDetail = compactPricing(summary.getPricingCallCount(), summary.getPricingColumnCount(),
					summary.getPricingTimeNanos());

			boolean[] internallyCovered = new boolean[data.n + 1];
			int internalJobCount = 0;
			double selectedInternalCost = 0.0;
			for (int columnId : result.getIncumbentColumnIds()) {
				TWETColumn column = pool.getColumn(columnId);
				selectedInternalCost += column.getCost();
				for (int job : column.getSequence()) {
					if (job >= 1 && job <= data.n && !internallyCovered[job]) {
						internallyCovered[job] = true;
						internalJobCount++;
					}
				}
			}
			this.internalColumns = result.getIncumbentColumnIds().size();
			this.internalJobs = internalJobCount;
			this.internalCost = selectedInternalCost;

			int outsourcedJobCount = 0;
			double baseline = 0.0;
			double[] outsourcingValues = result.getIncumbentOutsourcingValues();
			for (int job = 1; job <= data.n && job < outsourcingValues.length; job++) {
				if (outsourcingValues[job] > VALUE_TOLERANCE) {
					outsourcedJobCount++;
					baseline += data.outsourcingCost[job] * outsourcingValues[job];
				}
			}
			this.outsourcedJobs = outsourcedJobCount;
			this.outsourcingBaseline = baseline;
			this.outsourcingCost = data.evaluateOutsourcingCost(baseline);
		}

		private double pricingSeconds(BPCTraceSummary summary, String contains) {
			double seconds = 0.0;
			for (Map.Entry<String, Long> entry : summary.getPricingTimeNanos().entrySet()) {
				if (entry.getKey().toLowerCase(Locale.US).contains(contains)) {
					seconds += entry.getValue().longValue() / 1_000_000_000.0;
				}
			}
			return seconds;
		}

		private int pricingCalls(BPCTraceSummary summary, String contains) {
			int calls = 0;
			for (Map.Entry<String, Integer> entry : summary.getPricingCallCount().entrySet()) {
				if (entry.getKey().toLowerCase(Locale.US).contains(contains)) {
					calls += entry.getValue().intValue();
				}
			}
			return calls;
		}

		String toCsvLine(boolean objectiveMatch, boolean boundMatch, boolean rootBoundMatch) {
			return String.join(",", String.valueOf(caseId), String.valueOf(n), String.valueOf(machines),
					fmt(outsourcingScale), String.valueOf(tariffSegments), quote(model), quote(status), fmt(incumbent),
					fmt(bound), fmt(rootBound), fmt(gap),
					Boolean.toString(valid), String.valueOf(nodes), String.valueOf(pricingRounds),
					String.valueOf(generatedColumns), String.valueOf(poolSize), fmt(solveSeconds), fmt(rootSeconds),
					fmt(heuristicSeconds), String.valueOf(heuristicCalls), fmt(exactSeconds),
					String.valueOf(exactCalls), fmt(masterLpSeconds), String.valueOf(internalColumns),
					String.valueOf(internalJobs), fmt(internalCost), String.valueOf(outsourcedJobs),
					fmt(outsourcingBaseline), fmt(outsourcingCost), fmt(initialIncumbent), fmt(boundGainAfterRoot),
					fmt(incumbentGain), String.valueOf(incumbentUpdates), String.valueOf(rmihCalls),
					String.valueOf(rmihImprovements), String.valueOf(prunedByIncumbent),
					String.valueOf(closedWithoutBranch), String.valueOf(branchCalls), quote(branchDetail),
					quote(pricingDetail), Boolean.toString(objectiveMatch), Boolean.toString(boundMatch),
					Boolean.toString(rootBoundMatch));
		}
	}

	private static String compactCounts(Map<String, Integer> counts) {
		LinkedHashMap<String, String> parts = new LinkedHashMap<String, String>();
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			parts.put(entry.getKey(), String.valueOf(entry.getValue().intValue()));
		}
		return parts.toString();
	}
}
