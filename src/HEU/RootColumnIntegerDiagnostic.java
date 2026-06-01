package HEU;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import Basic.Data;
import Common.Utility;
import Output.BPCSolutionValidator;
import Output.BPCTraceSummary;
import Output.ValidationResult;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCSolver;
import TWETBPC.TWETSolveResult;
import TWETBPC.LP.Pool;
import TWETBPC.Model.TWETColumn;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.cplex.IloCplex;

/**
 * 诊断 root pricing 生成列的整数可用性。
 * <p>
 * 对指定算例用多个随机 seed 跑 root-only BPC，再把 root 列池固定下来解一个二进制
 * restricted master，用于区分“初始 incumbent 差”和“根节点列池本身已经能拼出更好整数解”。
 */
public class RootColumnIntegerDiagnostic {

	private static final String HEURISTIC_ENGINE = "HeuristicPricing";
	private static final String FULL_DOMAIN_ENGINE = "GCBBStyleBidirectionalFullDomainPricing";

	public static void main(String[] args) throws Exception {
		Path instanceDir = Path.of(System.getProperty("twet.bpc.rootInt.dir", "test-results/bpc"));
		String caseFilter = System.getProperty("twet.bpc.rootInt.case", "wet030_001");
		String resultStem = System.getProperty("twet.bpc.rootInt.name", "tmp-root-int-wet030");
		int seedCount = Integer.getInteger("twet.bpc.rootInt.seedCount", 5);
		long seedBase = Long.getLong("twet.bpc.rootInt.seedBase", 202605310300L);
		Path csv = Path.of("test-results", "bpc", resultStem + ".csv");

		List<Path> instances = listInstances(instanceDir, caseFilter);
		if (instances.isEmpty()) {
			throw new IllegalStateException("No .dat instance matched " + caseFilter + " under " + instanceDir);
		}

		ArrayList<String> lines = new ArrayList<String>();
		lines.add("case,seed,status,incumbent,bound,gap,initial_cols,incumbent_cols,initial_incumbent,"
				+ "root_int_obj,root_int_cols,pool,pricing,"
				+ "solve_s,exact_s,heuristic_s,valid,root_int_missing,root_int_duplicate_cover,root_int_column_ids");
		for (Path instance : instances) {
			for (int offset = 0; offset < seedCount; offset++) {
				long seed = seedBase + offset;
				RunRecord record = runOne(instance, seed);
				lines.add(record.toCsvLine());
				System.out.printf(Locale.US,
						"case=%s seed=%d status=%s incumbent=%.6f bound=%.6f rootInt=%.6f cols=%d pool=%d solve=%.3fs exact=%.3fs valid=%s%n",
						record.caseName, seed, record.status, record.incumbent, record.bound,
						record.rootIntegerObjective, record.rootIntegerColumns, record.poolSize,
						record.solveSeconds, record.exactSeconds, record.valid);
			}
		}
		Files.write(csv, lines);
		System.out.println("csv=" + csv);
	}

	private static RunRecord runOne(Path instance, long seed) throws Exception {
		resetHeuristicSeed(seed);
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(instance.toString(), false);
		TWETBPCConfig config = buildConfig(instance);
		TWETBPCSolver solver = new TWETBPCSolver(data, config);
		TWETSolveResult result = solver.solve();
		BPCTraceSummary summary = solver.getContext().traceSummary;
		ValidationResult validation = BPCSolutionValidator.validate(data, solver.getContext().pool, result);
		RestrictedMasterIntegerResult rootInteger = solveRestrictedMasterInteger(data, solver.getContext().pool);
		return new RunRecord(stripDat(instance.getFileName().toString()), seed, result.getStatus().toString(),
				result.getIncumbentCost(), result.getBestBound(),
				TanakaNoOutsourcingBPCTest.gapPercent(result.getBestBound(), result.getIncumbentCost()),
				summary.getInitialColumnCount(), summary.getInitialIncumbentColumnCount(),
				summary.getInitialIncumbentCost(),
				rootInteger.objective, rootInteger.selectedColumns, rootInteger.selectedColumnIds,
				rootInteger.missingJobs, rootInteger.duplicateCover, solver.getContext().pool.size(),
				summary.getPricingRounds(), summary.getSolveTimeSeconds(),
				seconds(summary.getPricingTimeNanos(), FULL_DOMAIN_ENGINE),
				seconds(summary.getPricingTimeNanos(), HEURISTIC_ENGINE), validation.isFeasible());
	}

	private static TWETBPCConfig buildConfig(Path instance) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = stripDat(instance.getFileName().toString()) + "-root-int-diagnostic";
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.reuseConfiguredBestSolution = false;
		config.runALNSForSeed = Boolean.getBoolean("twet.bpc.rootInt.runALNSForSeed");
		config.maxNodes = 1;
		config.maxHeuristicPricingColumns = Integer.getInteger("twet.bpc.rootInt.maxHeuristicColumns", 100000);
		config.heuristicPricingPoolSize = Integer.getInteger("twet.bpc.rootInt.heuristicPoolSize", 100000);
		config.maxExactPricingColumns = Integer.getInteger("twet.bpc.rootInt.maxExactColumns", 5);
		config.branchSeedColumnLimit = Integer.getInteger("twet.bpc.rootInt.branchSeedColumnLimit", 20000);
		config.enableBidirectionalPricing = true;
		config.useGCNGBBStyleBidirectionalPricing = true;
		config.useGCBBFullDomainBidirectionalPricing = true;
		config.forwardLabelQueueOrdering = "time";
		config.bidirectionalLabelQueueOrdering = "time";
		config.bidirectionalCompletionBoundRelaxation = System.getProperty("twet.bpc.rootInt.completionBound",
				"allCycles");
		return config;
	}

	private static RestrictedMasterIntegerResult solveRestrictedMasterInteger(Data data, Pool pool) throws IloException {
		IloCplex cplex = new IloCplex();
		try {
			cplex.setOut(null);
			cplex.setParam(IloCplex.Param.TimeLimit,
					Double.parseDouble(System.getProperty("twet.bpc.rootInt.mipTimeLimit", "60")));
			IloIntVar[] x = new IloIntVar[pool.size()];
			IloLinearNumExpr obj = cplex.linearNumExpr();
			for (int columnId = 0; columnId < pool.size(); columnId++) {
				TWETColumn column = pool.getColumn(columnId);
				x[columnId] = cplex.boolVar("x_" + columnId);
				obj.addTerm(column.getCost(), x[columnId]);
			}
			cplex.addMinimize(obj);

			for (int job = 1; job <= data.n; job++) {
				IloLinearNumExpr cover = cplex.linearNumExpr();
				for (int columnId = 0; columnId < pool.size(); columnId++) {
					if (pool.getColumn(columnId).containsJob(job)) {
						cover.addTerm(1.0, x[columnId]);
					}
				}
				cplex.addGe(cover, 1.0, "cover_" + job);
			}

			IloLinearNumExpr machineCount = cplex.linearNumExpr();
			for (IloIntVar var : x) {
				machineCount.addTerm(1.0, var);
			}
			cplex.addLe(machineCount, data.m, "machineCount");

			if (!cplex.solve()) {
				return new RestrictedMasterIntegerResult(Double.POSITIVE_INFINITY, 0, new ArrayList<Integer>(),
						data.n, 0);
			}
			int selected = 0;
			ArrayList<Integer> selectedIds = new ArrayList<Integer>();
			int[] coverCount = new int[data.n + 1];
			for (IloIntVar var : x) {
				if (Utility.compareGt(cplex.getValue(var), 0.5)) {
					selected++;
				}
			}
			for (int columnId = 0; columnId < pool.size(); columnId++) {
				if (!Utility.compareGt(cplex.getValue(x[columnId]), 0.5)) {
					continue;
				}
				selectedIds.add(Integer.valueOf(columnId));
				for (int job : pool.getColumn(columnId).getSequence()) {
					if (job >= 1 && job <= data.n) {
						coverCount[job]++;
					}
				}
			}
			int missing = 0;
			int duplicateCover = 0;
			for (int job = 1; job <= data.n; job++) {
				if (coverCount[job] == 0) {
					missing++;
				} else if (coverCount[job] > 1) {
					duplicateCover += coverCount[job] - 1;
				}
			}
			return new RestrictedMasterIntegerResult(cplex.getObjValue(), selected, selectedIds, missing,
					duplicateCover);
		} finally {
			cplex.end();
		}
	}

	private static List<Path> listInstances(Path instanceDir, String caseFilter) throws Exception {
		ArrayList<Path> instances = new ArrayList<Path>();
		try (var stream = Files.newDirectoryStream(instanceDir, "*.dat")) {
			for (Path path : stream) {
				if (caseFilter == null || caseFilter.isBlank()
						|| path.getFileName().toString().contains(caseFilter)) {
					instances.add(path);
				}
			}
		}
		instances.sort(Comparator.comparing(path -> path.getFileName().toString()));
		return instances;
	}

	private static void resetHeuristicSeed(long seed) {
		Utility.rng = new Random(seed);
		EngineALNS.rng = new Random(seed ^ 0x5DEECE66DL);
	}

	private static double seconds(java.util.Map<String, Long> counter, String key) {
		Long value = counter.get(key);
		return value == null ? 0.0 : value.longValue() / 1_000_000_000.0;
	}

	private static String stripDat(String name) {
		return name.endsWith(".dat") ? name.substring(0, name.length() - 4) : name;
	}

	private static String fmt(double value) {
		if (Double.isNaN(value)) {
			return "NaN";
		}
		if (Double.isInfinite(value)) {
			return value > 0 ? "INF" : "-INF";
		}
		return String.format(Locale.US, "%.6f", value);
	}

	private static final class RestrictedMasterIntegerResult {
		final double objective;
		final int selectedColumns;
		final ArrayList<Integer> selectedColumnIds;
		final int missingJobs;
		final int duplicateCover;

		RestrictedMasterIntegerResult(double objective, int selectedColumns, ArrayList<Integer> selectedColumnIds,
				int missingJobs, int duplicateCover) {
			this.objective = objective;
			this.selectedColumns = selectedColumns;
			this.selectedColumnIds = selectedColumnIds;
			this.missingJobs = missingJobs;
			this.duplicateCover = duplicateCover;
		}
	}

	private static final class RunRecord {
		final String caseName;
		final long seed;
		final String status;
		final double incumbent;
		final double bound;
		final double gap;
		final int initialColumns;
		final int incumbentColumns;
		final double initialIncumbent;
		final double rootIntegerObjective;
		final int rootIntegerColumns;
		final ArrayList<Integer> rootIntegerColumnIds;
		final int rootIntegerMissingJobs;
		final int rootIntegerDuplicateCover;
		final int poolSize;
		final int pricingRounds;
		final double solveSeconds;
		final double exactSeconds;
		final double heuristicSeconds;
		final boolean valid;

		RunRecord(String caseName, long seed, String status, double incumbent, double bound, double gap,
				int initialColumns, int incumbentColumns, double initialIncumbent, double rootIntegerObjective,
				int rootIntegerColumns, ArrayList<Integer> rootIntegerColumnIds, int rootIntegerMissingJobs,
				int rootIntegerDuplicateCover, int poolSize, int pricingRounds, double solveSeconds,
				double exactSeconds, double heuristicSeconds, boolean valid) {
			this.caseName = caseName;
			this.seed = seed;
			this.status = status;
			this.incumbent = incumbent;
			this.bound = bound;
			this.gap = gap;
			this.initialColumns = initialColumns;
			this.incumbentColumns = incumbentColumns;
			this.initialIncumbent = initialIncumbent;
			this.rootIntegerObjective = rootIntegerObjective;
			this.rootIntegerColumns = rootIntegerColumns;
			this.rootIntegerColumnIds = rootIntegerColumnIds;
			this.rootIntegerMissingJobs = rootIntegerMissingJobs;
			this.rootIntegerDuplicateCover = rootIntegerDuplicateCover;
			this.poolSize = poolSize;
			this.pricingRounds = pricingRounds;
			this.solveSeconds = solveSeconds;
			this.exactSeconds = exactSeconds;
			this.heuristicSeconds = heuristicSeconds;
			this.valid = valid;
		}

		String toCsvLine() {
			return "\"" + caseName + "\"," + seed + ",\"" + status + "\"," + fmt(incumbent) + "," + fmt(bound)
					+ "," + fmt(gap) + "," + initialColumns + "," + incumbentColumns + ","
					+ fmt(initialIncumbent) + "," + fmt(rootIntegerObjective) + "," + rootIntegerColumns
					+ "," + poolSize + "," + pricingRounds + "," + fmt(solveSeconds) + ","
					+ fmt(exactSeconds) + "," + fmt(heuristicSeconds) + "," + valid + ","
					+ rootIntegerMissingJobs + "," + rootIntegerDuplicateCover + ",\""
					+ rootIntegerColumnIds + "\"";
		}
	}
}
