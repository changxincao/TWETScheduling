package HEU;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCContext;
import TWETBPC.GC.InitialColumnBundle;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.cplex.IloCplex;

/**
 * 2026-06-04: 诊断 restricted master integer heuristic 的列筛选规模、可行性和求解耗时。
 */
public final class RestrictedMasterIntegerFilterDiagnostic {

	private RestrictedMasterIntegerFilterDiagnostic() {
	}

	public static void main(String[] args) throws Exception {
		Path instance = Path.of(System.getProperty("twet.bpc.rmihDiag.instance",
				"test-results/bpc/tmp-wet030_from040_010_2m.dat"));
		Path csv = Path.of(System.getProperty("twet.bpc.rmihDiag.csv",
				"test-results/bpc/rmih-filter-diagnostic-010-root.csv"));
		int[] reducedCostLimits = parseIntList(System.getProperty("twet.bpc.rmihDiag.rcLimits",
				"500,1000,2000,3000,4000,5341"));
		int perJobK = Integer.getInteger("twet.bpc.rmihDiag.perJobK", 10);
		double timeLimit = Double.parseDouble(System.getProperty("twet.bpc.rmihDiag.timeLimit", "0"));

		resetHeuristicSeed(instance);
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(instance.toString(), false);
		TWETBPCConfig config = buildConfig(instance);
		TWETBPCContext context = new TWETBPCContext(data, config);
		InitialColumnBundle initial = context.initialColumnBuilder.build();
		Node root = new Node(data, initial.getInitialColumnIds(), initial.getIncumbentColumnIds(),
				config.pseudoCostInf);
		root.id = 1;

		LP lp = new LP(data, context.pool, context.cutPool);
		lp.construct(root, root.seedColumnIds);
		TWETMasterSolution rootLp = context.pc.solve(lp);
		List<Integer> restricted = lp.getRestrictedColumnIds();
		ArrayList<ColumnScore> scores = collectScores(lp, restricted, rootLp);

		ArrayList<String> lines = new ArrayList<String>();
		lines.add("case,root_lp,restricted_cols,selection,cover_sense,selected_cols,positive_cols,per_job_k,"
				+ "status,obj,best_bound,mip_gap,solve_s,first_incumbent_s,first_incumbent_obj,"
				+ "missing_jobs,duplicate_cover,max_cover,duplicate_jobs,selected_column_ids,selected_sequences");

		for (int limit : reducedCostLimits) {
			ArrayList<Integer> selected = selectColumns(scores, restricted, rootLp, data, context, limit, perJobK);
			runAndAppend(lines, instance, rootLp, restricted.size(), "rc" + limit + "_jobK" + perJobK,
					">=", selected, rootLp.getActiveColumnIds().size(), perJobK, data, context, false, timeLimit);
			runAndAppend(lines, instance, rootLp, restricted.size(), "rc" + limit + "_jobK" + perJobK,
					"==", selected, rootLp.getActiveColumnIds().size(), perJobK, data, context, true, timeLimit);
		}

		Files.createDirectories(csv.getParent());
		Files.write(csv, lines);
		System.out.println("csv=" + csv);
	}

	private static TWETBPCConfig buildConfig(Path instance) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = stripDat(instance.getFileName().toString()) + "-rmih-filter-diagnostic";
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.reuseConfiguredBestSolution = false;
		config.runALNSForSeed = false;
		config.maxNodes = 1;
		config.maxHeuristicPricingColumns = Integer.getInteger("twet.bpc.rmihDiag.maxHeuristicColumns", 100000);
		config.heuristicPricingPoolSize = Integer.getInteger("twet.bpc.rmihDiag.heuristicPoolSize", 100000);
		config.maxExactPricingColumns = Integer.getInteger("twet.bpc.rmihDiag.maxExactColumns", 100000);
		config.branchSeedColumnLimit = Integer.getInteger("twet.bpc.rmihDiag.branchSeedColumnLimit", 20000);
		config.enableBidirectionalPricing = true;
		config.useGCNGBBStyleBidirectionalPricing = true;
		config.forwardLabelQueueOrdering = "time";
		config.bidirectionalLabelQueueOrdering = "time";
		config.bidirectionalCompletionBoundRelaxation = System.getProperty("twet.bpc.rmihDiag.completionBound",
				"allCycles");
		return config;
	}

	private static ArrayList<ColumnScore> collectScores(LP lp, List<Integer> restricted,
			TWETMasterSolution rootLp) {
		HashSet<Integer> positive = new HashSet<Integer>(rootLp.getActiveColumnIds());
		ArrayList<ColumnScore> scores = new ArrayList<ColumnScore>();
		for (int columnId : restricted) {
			scores.add(new ColumnScore(columnId, lp.getColumnReducedCost(columnId), positive.contains(columnId)));
		}
		Collections.sort(scores, new Comparator<ColumnScore>() {
			@Override
			public int compare(ColumnScore a, ColumnScore b) {
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return 1;
				}
				return Integer.compare(a.columnId, b.columnId);
			}
		});
		return scores;
	}

	private static ArrayList<Integer> selectColumns(ArrayList<ColumnScore> scores, List<Integer> restricted,
			TWETMasterSolution rootLp, Data data, TWETBPCContext context, int reducedCostLimit, int perJobK) {
		LinkedHashMap<Integer, Boolean> selected = new LinkedHashMap<Integer, Boolean>();
		for (int columnId : rootLp.getActiveColumnIds()) {
			selected.put(columnId, Boolean.TRUE);
		}
		for (int i = 0; i < scores.size() && i < reducedCostLimit; i++) {
			selected.put(scores.get(i).columnId, Boolean.TRUE);
		}
		for (int job = 1; job <= data.n; job++) {
			int kept = 0;
			for (ColumnScore score : scores) {
				TWETColumn column = context.pool.getColumn(score.columnId);
				if (!column.containsJob(job)) {
					continue;
				}
				selected.put(score.columnId, Boolean.TRUE);
				kept++;
				if (kept >= perJobK) {
					break;
				}
			}
		}
		for (int columnId : restricted) {
			if (selected.size() >= reducedCostLimit || reducedCostLimit >= restricted.size()) {
				break;
			}
		}
		return new ArrayList<Integer>(selected.keySet());
	}

	private static void runAndAppend(ArrayList<String> lines, Path instance, TWETMasterSolution rootLp,
			int restrictedColumns, String selectionName, String coverSense, List<Integer> selectedColumns,
			int positiveColumns, int perJobK, Data data, TWETBPCContext context, boolean exactCover,
			double timeLimit) throws IloException {
		SolveRecord record = solveInteger(data, context, selectedColumns, exactCover, timeLimit);
		lines.add(quote(stripDat(instance.getFileName().toString())) + "," + fmt(rootLp.getObjectiveValue()) + ","
				+ restrictedColumns + "," + quote(selectionName) + "," + quote(coverSense) + ","
				+ selectedColumns.size() + "," + positiveColumns + "," + perJobK + "," + quote(record.status)
				+ "," + fmt(record.objective) + "," + fmt(record.bestBound) + "," + fmt(record.mipGap) + ","
				+ fmt(record.solveSeconds) + "," + fmt(record.firstIncumbentSeconds) + ","
				+ fmt(record.firstIncumbentObjective) + "," + record.missingJobs + ","
				+ record.duplicateCover + "," + record.maxCover + "," + quote(record.duplicateJobs) + ","
				+ quote(record.selectedColumnIds) + "," + quote(record.selectedSequences));
		System.out.printf(Locale.US,
				"selection=%s cover=%s cols=%d status=%s obj=%.6f bound=%.6f time=%.3fs first=%.3fs dup=%d missing=%d%n",
				selectionName, coverSense, selectedColumns.size(), record.status, record.objective,
				record.bestBound, record.solveSeconds, record.firstIncumbentSeconds, record.duplicateCover,
				record.missingJobs);
	}

	private static SolveRecord solveInteger(Data data, TWETBPCContext context, List<Integer> selectedColumns,
			boolean exactCover, double timeLimit) throws IloException {
		IloCplex cplex = new IloCplex();
		long start = System.nanoTime();
		IncumbentProbe probe = new IncumbentProbe();
		try {
			cplex.setOut(null);
			if (Utility.compareGt(timeLimit, 0.0)) {
				cplex.setParam(IloCplex.Param.TimeLimit, timeLimit);
			}
			IloIntVar[] x = new IloIntVar[selectedColumns.size()];
			IloLinearNumExpr obj = cplex.linearNumExpr();
			for (int idx = 0; idx < selectedColumns.size(); idx++) {
				int columnId = selectedColumns.get(idx).intValue();
				x[idx] = cplex.boolVar("x_" + columnId);
				obj.addTerm(context.pool.getColumn(columnId).getCost(), x[idx]);
			}
			cplex.addMinimize(obj);
			for (int job = 1; job <= data.n; job++) {
				IloLinearNumExpr cover = cplex.linearNumExpr();
				for (int idx = 0; idx < selectedColumns.size(); idx++) {
					if (context.pool.getColumn(selectedColumns.get(idx).intValue()).containsJob(job)) {
						cover.addTerm(1.0, x[idx]);
					}
				}
				if (exactCover) {
					cplex.addEq(cover, 1.0, "diag_cover_" + job);
				} else {
					cplex.addGe(cover, 1.0, "diag_cover_" + job);
				}
			}
			IloLinearNumExpr machineCount = cplex.linearNumExpr();
			for (IloIntVar var : x) {
				machineCount.addTerm(1.0, var);
			}
			cplex.addRange(0.0, machineCount, data.m, "diag_machineCount");
			cplex.use(new IloCplex.MIPInfoCallback() {
				@Override
				protected void main() throws IloException {
					if (!probe.hasIncumbent && hasIncumbent()) {
						probe.hasIncumbent = true;
						probe.firstIncumbentNanos = System.nanoTime() - start;
						probe.firstIncumbentObjective = getIncumbentObjValue();
					}
				}
			});
			boolean solved = cplex.solve();
			double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
			if (!solved) {
				return new SolveRecord(cplex.getStatus().toString(), Double.POSITIVE_INFINITY,
						safeBestBound(cplex), safeGap(cplex), elapsed, probe.firstSeconds(),
						probe.firstIncumbentObjective, 0, data.n, 0, "", "", "");
			}
			int[] coverCount = new int[data.n + 1];
			ArrayList<Integer> chosen = new ArrayList<Integer>();
			for (int idx = 0; idx < selectedColumns.size(); idx++) {
				if (!Utility.compareGt(cplex.getValue(x[idx]), 0.5)) {
					continue;
				}
				int columnId = selectedColumns.get(idx).intValue();
				chosen.add(columnId);
				for (int job : context.pool.getColumn(columnId).getSequence()) {
					if (job >= 1 && job <= data.n) {
						coverCount[job]++;
					}
				}
			}
			int missing = 0;
			int duplicate = 0;
			int maxCover = 0;
			ArrayList<Integer> duplicateJobs = new ArrayList<Integer>();
			for (int job = 1; job <= data.n; job++) {
				if (coverCount[job] == 0) {
					missing++;
				}
				if (coverCount[job] > 1) {
					duplicate += coverCount[job] - 1;
					duplicateJobs.add(Integer.valueOf(job));
				}
				maxCover = Math.max(maxCover, coverCount[job]);
			}
			return new SolveRecord(cplex.getStatus().toString(), cplex.getObjValue(), safeBestBound(cplex),
					safeGap(cplex), elapsed, probe.firstSeconds(), probe.firstIncumbentObjective, duplicate,
					missing, maxCover, duplicateJobs.toString(), sequences(context, chosen), chosen.toString());
		} finally {
			cplex.end();
		}
	}

	private static String sequences(TWETBPCContext context, List<Integer> columnIds) {
		ArrayList<String> values = new ArrayList<String>();
		for (int columnId : columnIds) {
			values.add(columnId + ":" + context.pool.getColumn(columnId).getSequence());
		}
		return values.toString();
	}

	private static double safeBestBound(IloCplex cplex) {
		try {
			return cplex.getBestObjValue();
		} catch (IloException ex) {
			return Double.NaN;
		}
	}

	private static double safeGap(IloCplex cplex) {
		try {
			return cplex.getMIPRelativeGap();
		} catch (IloException ex) {
			return Double.NaN;
		}
	}

	private static int[] parseIntList(String text) {
		String[] parts = text.split(",");
		int[] values = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			values[i] = Integer.parseInt(parts[i].trim());
		}
		return values;
	}

	private static String stripDat(String name) {
		return name.endsWith(".dat") ? name.substring(0, name.length() - 4) : name;
	}

	private static void resetHeuristicSeed(Path instance) {
		long seed = 202605280100L + stripDat(instance.getFileName().toString()).hashCode();
		Utility.rng = new Random(seed);
		EngineALNS.rng = new Random(seed ^ 0x5DEECE66DL);
	}

	private static String quote(Object value) {
		String text = String.valueOf(value);
		return "\"" + text.replace("\"", "\"\"") + "\"";
	}

	private static String fmt(double value) {
		if (!Double.isFinite(value)) {
			return String.valueOf(value);
		}
		return String.format(Locale.US, "%.6f", value);
	}

	private static final class ColumnScore {
		final int columnId;
		final double reducedCost;
		final boolean positive;

		ColumnScore(int columnId, double reducedCost, boolean positive) {
			this.columnId = columnId;
			this.reducedCost = reducedCost;
			this.positive = positive;
		}
	}

	private static final class IncumbentProbe {
		boolean hasIncumbent;
		long firstIncumbentNanos;
		double firstIncumbentObjective = Double.NaN;

		double firstSeconds() {
			return hasIncumbent ? firstIncumbentNanos / 1_000_000_000.0 : Double.NaN;
		}
	}

	private static final class SolveRecord {
		final String status;
		final double objective;
		final double bestBound;
		final double mipGap;
		final double solveSeconds;
		final double firstIncumbentSeconds;
		final double firstIncumbentObjective;
		final int duplicateCover;
		final int missingJobs;
		final int maxCover;
		final String duplicateJobs;
		final String selectedColumnIds;
		final String selectedSequences;

		SolveRecord(String status, double objective, double bestBound, double mipGap, double solveSeconds,
				double firstIncumbentSeconds, double firstIncumbentObjective, int duplicateCover,
				int missingJobs, int maxCover, String duplicateJobs, String selectedSequences,
				String selectedColumnIds) {
			this.status = status;
			this.objective = objective;
			this.bestBound = bestBound;
			this.mipGap = mipGap;
			this.solveSeconds = solveSeconds;
			this.firstIncumbentSeconds = firstIncumbentSeconds;
			this.firstIncumbentObjective = firstIncumbentObjective;
			this.duplicateCover = duplicateCover;
			this.missingJobs = missingJobs;
			this.maxCover = maxCover;
			this.duplicateJobs = duplicateJobs;
			this.selectedColumnIds = selectedColumnIds;
			this.selectedSequences = selectedSequences;
		}
	}
}
