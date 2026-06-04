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
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCContext;
import TWETBPC.GC.InitialColumnBundle;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
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
		lines.add("case,root_lp,restricted_cols,selection,model,cover_sense,selected_cols,repair_cols,positive_cols,per_job_k,"
				+ "status,obj,best_bound,mip_gap,solve_s,first_incumbent_s,first_incumbent_obj,"
				+ "missing_jobs,duplicate_cover,max_cover,duplicate_jobs,selected_column_ids,selected_sequences");

		for (int limit : reducedCostLimits) {
			ArrayList<Integer> selected = selectColumns(scores, restricted, rootLp, data, context, limit, perJobK);
			runAndAppend(lines, instance, rootLp, restricted.size(), "rc" + limit + "_jobK" + perJobK,
					"formal", ">=", selected, 0, rootLp.getActiveColumnIds().size(), perJobK, data, context, root,
					false, timeLimit);
			runAndAppend(lines, instance, rootLp, restricted.size(), "rc" + limit + "_jobK" + perJobK,
					"formal", "==", selected, 0, rootLp.getActiveColumnIds().size(), perJobK, data, context, root,
					true, timeLimit);
			runRepairAndAppend(lines, instance, rootLp, restricted.size(), "rc" + limit + "_jobK" + perJobK,
					selected, rootLp.getActiveColumnIds().size(), perJobK, data, context, root, timeLimit);
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
			int restrictedColumns, String selectionName, String modelName, String coverSense,
			List<Integer> selectedColumns, int repairColumns, int positiveColumns, int perJobK, Data data,
			TWETBPCContext context, Node node, boolean exactCover,
			double timeLimit) throws IloException {
		SolveRecord record = solveInteger(data, context, node, selectedColumns, exactCover, timeLimit);
		lines.add(quote(stripDat(instance.getFileName().toString())) + "," + fmt(rootLp.getObjectiveValue()) + ","
				+ restrictedColumns + "," + quote(selectionName) + "," + quote(modelName) + ","
				+ quote(coverSense) + "," + selectedColumns.size() + "," + repairColumns + "," + positiveColumns
				+ "," + perJobK + "," + quote(record.status) + "," + fmt(record.objective) + ","
				+ fmt(record.bestBound) + "," + fmt(record.mipGap) + "," + fmt(record.solveSeconds) + ","
				+ fmt(record.firstIncumbentSeconds) + "," + fmt(record.firstIncumbentObjective) + ","
				+ record.missingJobs + "," + record.duplicateCover + "," + record.maxCover + ","
				+ quote(record.duplicateJobs) + "," + quote(record.selectedColumnIds) + ","
				+ quote(record.selectedSequences));
		System.out.printf(Locale.US,
				"selection=%s model=%s cover=%s cols=%d repair=%d status=%s obj=%.6f bound=%.6f time=%.3fs first=%.3fs dup=%d missing=%d%n",
				selectionName, modelName, coverSense, selectedColumns.size(), repairColumns, record.status, record.objective,
				record.bestBound, record.solveSeconds, record.firstIncumbentSeconds, record.duplicateCover,
				record.missingJobs);
	}

	private static void runRepairAndAppend(ArrayList<String> lines, Path instance, TWETMasterSolution rootLp,
			int restrictedColumns, String selectionName, List<Integer> selectedColumns, int positiveColumns,
			int perJobK, Data data, TWETBPCContext context, Node node, double timeLimit) throws IloException {
		SolveRecord covering = solveInteger(data, context, node, selectedColumns, false, timeLimit);
		ArrayList<Integer> repaired = new ArrayList<Integer>(selectedColumns);
		int added = addDuplicateDeletionRepairColumns(data, context, covering.chosenColumnIds, repaired);
		runAndAppend(lines, instance, rootLp, restrictedColumns, selectionName, "formalRepair", "repair==",
				repaired, added, positiveColumns, perJobK, data, context, node, true, timeLimit);
	}

	private static SolveRecord solveInteger(Data data, TWETBPCContext context, List<Integer> selectedColumns,
			boolean exactCover, double timeLimit) throws IloException {
		return solveInteger(data, context, null, selectedColumns, exactCover, timeLimit);
	}

	private static SolveRecord solveInteger(Data data, TWETBPCContext context, Node node,
			List<Integer> selectedColumns, boolean exactCover, double timeLimit) throws IloException {
		IloCplex cplex = new IloCplex();
		long start = System.nanoTime();
		IncumbentProbe probe = new IncumbentProbe();
		try {
			cplex.setOut(null);
			if (Utility.compareGt(timeLimit, 0.0)) {
				cplex.setParam(IloCplex.Param.TimeLimit, timeLimit);
			}
			IloIntVar[] x = new IloIntVar[selectedColumns.size()];
			IloIntVar[] y = new IloIntVar[data.n + 1];
			ArrayList<TariffSegment> segments = collectOutsourcingTariffSegments(data);
			IloIntVar[] z = new IloIntVar[segments.size()];
			IloNumVar[] baseline = new IloNumVar[segments.size()];
			IloLinearNumExpr obj = cplex.linearNumExpr();
			for (int idx = 0; idx < selectedColumns.size(); idx++) {
				int columnId = selectedColumns.get(idx).intValue();
				x[idx] = cplex.boolVar("x_" + columnId);
				obj.addTerm(context.pool.getColumn(columnId).getCost(), x[idx]);
			}
			for (int job = 1; job <= data.n; job++) {
				double ub = Utility.isBigMValue(data.outsourcingCost[job]) ? 0.0 : 1.0;
				y[job] = cplex.boolVar("y_" + job);
				y[job].setUB(ub);
			}
			for (int segment = 0; segment < segments.size(); segment++) {
				TariffSegment tariff = segments.get(segment);
				z[segment] = cplex.boolVar("outSegActive_" + segment);
				baseline[segment] = cplex.numVar(0.0, tariff.end, "outSegBaseline_" + segment);
				obj.addTerm(tariff.slope, baseline[segment]);
				obj.addTerm(tariff.intercept, z[segment]);
			}
			cplex.addMinimize(obj);
			for (int job = 1; job <= data.n; job++) {
				IloLinearNumExpr cover = cplex.linearNumExpr();
				for (int idx = 0; idx < selectedColumns.size(); idx++) {
					if (context.pool.getColumn(selectedColumns.get(idx).intValue()).containsJob(job)) {
						cover.addTerm(1.0, x[idx]);
					}
				}
				cover.addTerm(1.0, y[job]);
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
			double minMachines = node == null ? 0.0 : node.minMachineCount;
			double maxMachines = node == null ? data.m : node.maxMachineCount;
			cplex.addRange(minMachines, machineCount, maxMachines, "diag_machineCount");
			buildTariffConstraints(cplex, data, node, y, z, baseline, segments);
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
						probe.firstIncumbentObjective, 0, data.n, 0, "", "", "", new ArrayList<Integer>());
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
			ArrayList<Integer> outsourced = new ArrayList<Integer>();
			for (int job = 1; job <= data.n; job++) {
				if (Utility.compareGt(cplex.getValue(y[job]), 0.5)) {
					outsourced.add(Integer.valueOf(job));
					coverCount[job]++;
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
					missing, maxCover, duplicateJobs.toString(), sequences(context, chosen), chosen.toString(),
					chosen);
		} finally {
			cplex.end();
		}
	}

	private static void buildTariffConstraints(IloCplex cplex, Data data, Node node, IloIntVar[] y, IloIntVar[] z,
			IloNumVar[] baseline, ArrayList<TariffSegment> segments) throws IloException {
		if (segments.isEmpty()) {
			return;
		}
		IloLinearNumExpr baselineFromJobs = cplex.linearNumExpr();
		for (int job = 1; job <= data.n; job++) {
			if (!Utility.isBigMValue(data.outsourcingCost[job])) {
				baselineFromJobs.addTerm(data.outsourcingCost[job], y[job]);
			}
		}
		IloLinearNumExpr baselineFromSegments = cplex.linearNumExpr();
		IloLinearNumExpr active = cplex.linearNumExpr();
		for (int segment = 0; segment < segments.size(); segment++) {
			TariffSegment tariff = segments.get(segment);
			baselineFromSegments.addTerm(1.0, baseline[segment]);
			active.addTerm(1.0, z[segment]);
			if (node != null) {
				byte state = node.getTariffSegmentState(segment);
				if (state == Node.SEGMENT_FORBIDDEN) {
					cplex.addLe(z[segment], 0.0, "diag_outSegForbidden_" + segment);
				} else if (state == Node.SEGMENT_REQUIRED) {
					cplex.addGe(z[segment], 1.0, "diag_outSegRequired_" + segment);
				}
			}
			cplex.addGe(baseline[segment], cplex.prod(tariff.start, z[segment]), "diag_outSegLB_" + segment);
			cplex.addLe(baseline[segment], cplex.prod(tariff.end, z[segment]), "diag_outSegUB_" + segment);
		}
		cplex.addEq(baselineFromJobs, baselineFromSegments, "diag_outsourceBaseline");
		cplex.addEq(active, 1.0, "diag_outsourceOneSegment");
	}

	private static ArrayList<TariffSegment> collectOutsourcingTariffSegments(Data data) {
		data.evaluateOutsourcingCost(0.0);
		ArrayList<TariffSegment> segments = new ArrayList<TariffSegment>();
		if (data.outsourcingCostFunction == null || data.outsourcingCostFunction.head == null) {
			return segments;
		}
		for (PiecewiseLinearFunction.Segment seg = data.outsourcingCostFunction.head; seg != null; seg = seg.next) {
			segments.add(new TariffSegment(seg.start, seg.end, seg.slope, seg.intercept));
		}
		return segments;
	}

	private static int addDuplicateDeletionRepairColumns(Data data, TWETBPCContext context,
			List<Integer> chosenColumnIds, ArrayList<Integer> selectedColumns) {
		if (chosenColumnIds.isEmpty()) {
			return 0;
		}
		int[] coverCount = new int[data.n + 1];
		for (int columnId : chosenColumnIds) {
			for (int job : context.pool.getColumn(columnId).getSequence()) {
				if (job >= 1 && job <= data.n) {
					coverCount[job]++;
				}
			}
		}
		HashSet<Integer> duplicateJobs = new HashSet<Integer>();
		for (int job = 1; job <= data.n; job++) {
			if (coverCount[job] > 1) {
				duplicateJobs.add(Integer.valueOf(job));
			}
		}
		if (duplicateJobs.isEmpty()) {
			return 0;
		}
		HashSet<Integer> selectedSet = new HashSet<Integer>(selectedColumns);
		TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
		int added = 0;
		for (int columnId : chosenColumnIds) {
			List<Integer> sequence = context.pool.getColumn(columnId).getSequence();
			ArrayList<Integer> removable = new ArrayList<Integer>();
			for (int job : sequence) {
				if (duplicateJobs.contains(Integer.valueOf(job))) {
					removable.add(Integer.valueOf(job));
				}
			}
			int subsetCount = 1 << removable.size();
			for (int mask = 1; mask < subsetCount; mask++) {
				ArrayList<Integer> repairedSequence = new ArrayList<Integer>();
				for (int job : sequence) {
					int pos = removable.indexOf(Integer.valueOf(job));
					if (pos >= 0 && ((mask >>> pos) & 1) == 1) {
						continue;
					}
					repairedSequence.add(Integer.valueOf(job));
				}
				if (repairedSequence.isEmpty()) {
					continue;
				}
				int repairedId = context.pool.addColumn(repairedSequence, evaluator.evaluate(repairedSequence),
						ColumnSource.MANUAL, false);
				if (selectedSet.add(Integer.valueOf(repairedId))) {
					selectedColumns.add(Integer.valueOf(repairedId));
					added++;
				}
			}
		}
		return added;
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

	private static final class TariffSegment {
		final double start;
		final double end;
		final double slope;
		final double intercept;

		TariffSegment(double start, double end, double slope, double intercept) {
			this.start = start;
			this.end = end;
			this.slope = slope;
			this.intercept = intercept;
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
		final ArrayList<Integer> chosenColumnIds;

		SolveRecord(String status, double objective, double bestBound, double mipGap, double solveSeconds,
				double firstIncumbentSeconds, double firstIncumbentObjective, int duplicateCover,
				int missingJobs, int maxCover, String duplicateJobs, String selectedSequences,
				String selectedColumnIds, ArrayList<Integer> chosenColumnIds) {
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
			this.chosenColumnIds = chosenColumnIds;
		}
	}
}
