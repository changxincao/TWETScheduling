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
import Output.BPCOutputFormatters;
import Output.BPCTraceSummary;
import Output.ValidationResult;
import Output.BPCSolutionValidator;
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
		String[] caseTokens = System.getProperty("twet.bpc.outsourcingCompare.cases", "0,1,2,3").split(",");
		Path output = Paths.get(System.getProperty("twet.bpc.outsourcingCompare.output",
				"test-results/bpc/2026-06-20-outsourcing-model-comparison.csv"));
		Files.createDirectories(output.getParent());

		ArrayList<PairRecord> pairs = new ArrayList<PairRecord>();
		try (BufferedWriter writer = Files.newBufferedWriter(output)) {
			writer.write("case_id,n,m,model,status,incumbent,bound,gap_percent,valid,nodes,pricing_rounds,"
					+ "generated_columns,pool_size,solve_s,root_s,heuristic_s,heuristic_calls,exact_s,"
					+ "exact_calls,master_lp_s,pricing_detail,objective_match,bound_match\n");
			for (String token : caseTokens) {
				int caseId = Integer.parseInt(token.trim());
				RunRecord master = runCase(caseId, n, machines, "masterVariables");
				RunRecord columns = runCase(caseId, n, machines, "columns");
				PairRecord pair = new PairRecord(caseId, master, columns);
				pairs.add(pair);
				writer.write(master.toCsvLine(pair.objectiveMatch, pair.boundMatch));
				writer.newLine();
				writer.write(columns.toCsvLine(pair.objectiveMatch, pair.boundMatch));
				writer.newLine();
				System.out.println(pair.summaryLine());
			}
		}

		System.out.println("CSV written: " + output.toAbsolutePath());
		printAggregate(pairs);
	}

	private static RunRecord runCase(int caseId, int n, int machines, String outsourcingModel) throws Exception {
		Data data = SmallExactHeuristicBatchTest.buildRandomCase(caseId, n, machines);
		TWETBPCConfig config = buildConfig(caseId, n, machines, outsourcingModel);
		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		TWETSolveResult result = solver.solve();
		BPCTraceSummary summary = solver.getContext().traceSummary;
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);
		return new RunRecord(caseId, n, machines, outsourcingModel, result, summary, validation,
				solver.getContext().pool.size());
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
		config.branchSeedColumnLimit = Integer.getInteger("twet.bpc.outsourcingCompare.branchSeedColumnLimit", 5000);
		config.enableHeuristicPricing = Boolean.parseBoolean(
				System.getProperty("twet.bpc.outsourcingCompare.enableHeuristicPricing", "true"));
		config.enableRestrictedMasterIntegerHeuristic = Boolean.parseBoolean(
				System.getProperty("twet.bpc.outsourcingCompare.enableRMIH", "true"));
		config.restrictedMasterIntegerHeuristicTimeLimitSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.outsourcingCompare.restrictedMasterIntegerTimeLimit",
				Double.toString(config.restrictedMasterIntegerHeuristicTimeLimitSeconds)));
		config.enableUndirectedAdjacencyBranching = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.enableUndirectedAdjacencyBranching", "false"));
		config.bidirectionalCompletionBoundRelaxation = System.getProperty(
				"twet.bpc.outsourcingCompare.completionBound", "allCycles");
		config.bidirectionalCompletionBoundQueueOrdering = System.getProperty(
				"twet.bpc.outsourcingCompare.completionBoundQueue", config.bidirectionalCompletionBoundQueueOrdering);
		config.bidirectionalCompletionBoundScalarPruning = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.completionBoundScalar", "true"));
		config.bidirectionalMidpointProbe = Boolean.parseBoolean(
				System.getProperty("twet.bpc.outsourcingCompare.midpointProbe", "false"));
		config.bidirectionalMidpointProbeReuseWithinNode = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.outsourcingCompare.midpointProbeReuseWithinNode",
				Boolean.toString(config.bidirectionalMidpointProbeReuseWithinNode)));
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

		PairRecord(int caseId, RunRecord master, RunRecord columns) {
			this.caseId = caseId;
			this.master = master;
			this.columns = columns;
			this.objectiveMatch = close(master.incumbent, columns.incumbent);
			this.boundMatch = close(master.bound, columns.bound);
		}

		String summaryLine() {
			return String.format(Locale.US,
					"case=%d objectiveMatch=%s boundMatch=%s master inc/bound/time=%s/%s/%.3fs columns inc/bound/time=%s/%s/%.3fs valid=%s/%s",
					caseId, Boolean.toString(objectiveMatch), Boolean.toString(boundMatch), fmt(master.incumbent),
					fmt(master.bound), master.solveSeconds, fmt(columns.incumbent), fmt(columns.bound),
					columns.solveSeconds, Boolean.toString(master.valid), Boolean.toString(columns.valid));
		}
	}

	private static final class RunRecord {
		final int caseId;
		final int n;
		final int machines;
		final String model;
		final String status;
		final double incumbent;
		final double bound;
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
		final String pricingDetail;

		RunRecord(int caseId, int n, int machines, String model, TWETSolveResult result, BPCTraceSummary summary,
				ValidationResult validation, int poolSize) {
			this.caseId = caseId;
			this.n = n;
			this.machines = machines;
			this.model = model;
			this.status = result.getStatus().toString();
			this.incumbent = result.getIncumbentCost();
			this.bound = result.getBestBound();
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
			this.pricingDetail = compactPricing(summary.getPricingCallCount(), summary.getPricingColumnCount(),
					summary.getPricingTimeNanos());
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

		String toCsvLine(boolean objectiveMatch, boolean boundMatch) {
			return String.join(",", String.valueOf(caseId), String.valueOf(n), String.valueOf(machines), quote(model),
					quote(status), fmt(incumbent), fmt(bound), fmt(gap), Boolean.toString(valid),
					String.valueOf(nodes), String.valueOf(pricingRounds), String.valueOf(generatedColumns),
					String.valueOf(poolSize), fmt(solveSeconds), fmt(rootSeconds), fmt(heuristicSeconds),
					String.valueOf(heuristicCalls), fmt(exactSeconds), String.valueOf(exactCalls),
					fmt(masterLpSeconds), quote(pricingDetail), Boolean.toString(objectiveMatch),
					Boolean.toString(boundMatch));
		}
	}
}
