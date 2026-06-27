package Basic;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntervalSequenceVar;
import ilog.concert.IloIntervalVar;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloNumVarType;
import ilog.concert.IloTransitionDistance;
import ilog.cp.IloCP;
import ilog.cplex.IloCplex;

/**
 * 局部 horizon 上界模型：在给定 release、处理时间和 sequence-dependent setup 后，
 * 用精确 MIP 或 CP Optimizer 尝试求一个更小的 Cmax 可行上界。
 *
 * 2026-06-27: 该类只作为对照求解器，不接入 BPC 主流程。release 表示加工开始
 * 的下界，setup 可以在 release 前进行，因此 i 后接 j 时
 * start_j = max(release_j, completion_i + setup_ij)。
 */
public final class LocalHorizonCmaxSolver {

	private LocalHorizonCmaxSolver() {
	}

	public enum Engine {
		CPLEX_ARC_FLOW, CP_OPTIMIZER, CPLEX_TIME_INDEXED
	}

	public static final class Problem {
		public final int n;
		public final int machines;
		public final int[] processing;
		public final int[] release;
		public final int[][] setup;
		public final boolean[][] forbiddenArc;
		public final boolean[][] requiredArc;
		public final boolean[][] undirectedAdjacency;
		public final int horizonUpperBound;

		public Problem(int n, int machines, int[] processing, int[] release, int[][] setup,
				boolean[][] forbiddenArc, boolean[][] requiredArc, boolean[][] undirectedAdjacency,
				int horizonUpperBound) {
			this.n = n;
			this.machines = machines;
			this.processing = processing;
			this.release = release;
			this.setup = setup;
			this.forbiddenArc = normalizeArcMatrix(forbiddenArc, n);
			this.requiredArc = normalizeArcMatrix(requiredArc, n);
			this.undirectedAdjacency = normalizeArcMatrix(undirectedAdjacency, n);
			this.horizonUpperBound = horizonUpperBound > 0 ? horizonUpperBound
					: computeConservativeHorizon(n, processing, release, setup);
		}

		public static Problem fromData(Data data) {
			return fromData(data, null, null, null);
		}

		public static Problem fromData(Data data, boolean[][] forbiddenArc, boolean[][] requiredArc,
				boolean[][] undirectedAdjacency) {
			return fromData(data, data.m, forbiddenArc, requiredArc, undirectedAdjacency);
		}

		public static Problem fromData(Data data, int machines, boolean[][] forbiddenArc, boolean[][] requiredArc,
				boolean[][] undirectedAdjacency) {
			int n = data.n;
			int[] processing = new int[n + 1];
			int[] release = new int[n + 1];
			int[][] setup = new int[n + 1][n + 1];
			for (int j = 1; j <= n; j++) {
				processing[j] = toModelInt(data.p[j]);
				release[j] = Math.max(0, toModelInt(Math.max(0, data.d_l[j] - data.p[j])));
				setup[0][j] = toModelInt(data.s[0][j]);
			}
			for (int i = 1; i <= n; i++) {
				for (int j = 1; j <= n; j++) {
					setup[i][j] = i == j ? 0 : toModelInt(data.s[i][j]);
				}
			}
			return new Problem(n, machines, processing, release, setup, forbiddenArc, requiredArc,
					undirectedAdjacency, computeConservativeHorizon(n, processing, release, setup));
		}
	}

	public static final class Result {
		public final Engine engine;
		public final boolean feasible;
		public final boolean provenOptimal;
		public final String status;
		public final double objective;
		public final double bestBound;
		public final double elapsedSeconds;
		public final List<List<Integer>> machineRoutes;

		private Result(Engine engine, boolean feasible, boolean provenOptimal, String status, double objective,
				double bestBound, double elapsedSeconds, List<List<Integer>> machineRoutes) {
			this.engine = engine;
			this.feasible = feasible;
			this.provenOptimal = provenOptimal;
			this.status = status;
			this.objective = objective;
			this.bestBound = bestBound;
			this.elapsedSeconds = elapsedSeconds;
			this.machineRoutes = machineRoutes;
		}

		@Override
		public String toString() {
			return "Result{" + "engine=" + engine + ", feasible=" + feasible + ", provenOptimal=" + provenOptimal
					+ ", status='" + status + '\'' + ", objective=" + objective + ", bestBound=" + bestBound
					+ ", elapsedSeconds=" + elapsedSeconds + ", machineRoutes=" + machineRoutes + '}';
		}
	}

	public static Result solveWithCplex(Problem problem, double timeLimitSeconds, int threads) throws IloException {
		long startNanos = System.nanoTime();
		IloCplex cplex = new IloCplex();
		try {
			cplex.setOut(null);
			if (timeLimitSeconds > 0) {
				cplex.setParam(IloCplex.Param.TimeLimit, timeLimitSeconds);
			}
			if (threads > 0) {
				cplex.setParam(IloCplex.Param.Threads, threads);
			}
			int n = problem.n;
			int sink = n + 1;
			IloNumVar[][] x = new IloNumVar[n + 1][n + 2];
			for (int j = 1; j <= n; j++) {
				if (!problem.forbiddenArc[0][j]) {
					x[0][j] = cplex.boolVar("x_0_" + j);
				}
			}
			for (int i = 1; i <= n; i++) {
				for (int j = 1; j <= n; j++) {
					if (i != j && !problem.forbiddenArc[i][j]) {
						x[i][j] = cplex.boolVar("x_" + i + "_" + j);
					}
				}
				x[i][sink] = cplex.boolVar("x_" + i + "_" + sink);
			}

			IloNumVar[] start = cplex.numVarArray(n + 1, 0, problem.horizonUpperBound, IloNumVarType.Float);
			IloNumVar[] completion = cplex.numVarArray(n + 1, 0, problem.horizonUpperBound, IloNumVarType.Float);
			IloNumVar cmax = cplex.numVar(0, problem.horizonUpperBound, "Cmax");

			for (int j = 1; j <= n; j++) {
				cplex.addEq(sumIncoming(cplex, x, j), 1, "in_" + j);
				cplex.addEq(sumOutgoing(cplex, x, j, sink), 1, "out_" + j);
				cplex.addGe(start[j], problem.release[j], "release_" + j);
				cplex.addEq(completion[j], cplex.sum(start[j], problem.processing[j]), "completion_" + j);
				cplex.addGe(cmax, completion[j], "cmax_" + j);
				if (x[0][j] != null) {
					cplex.addGe(start[j], cplex.prod(problem.setup[0][j], x[0][j]), "initial_setup_" + j);
				}
			}
			cplex.addLe(sumSource(cplex, x), problem.machines, "machine_count");

			for (int i = 1; i <= n; i++) {
				for (int j = 1; j <= n; j++) {
					if (x[i][j] != null) {
						int bigM = problem.horizonUpperBound + problem.setup[i][j];
						cplex.addGe(start[j],
								cplex.diff(cplex.sum(completion[i], problem.setup[i][j]),
										cplex.prod(bigM, cplex.diff(1.0, x[i][j]))),
								"precedence_" + i + "_" + j);
					}
				}
			}
			addRequiredArcConstraints(cplex, problem, x);
			addUndirectedAdjacencyConstraints(cplex, problem, x);
			cplex.addMinimize(cmax);

			boolean solved = cplex.solve();
			double elapsed = elapsedSeconds(startNanos);
			if (!solved) {
				return new Result(Engine.CPLEX_ARC_FLOW, false, false, String.valueOf(cplex.getStatus()),
						Double.POSITIVE_INFINITY, safeBestBound(cplex), elapsed, Collections.emptyList());
			}
			boolean optimal = cplex.getStatus() == IloCplex.Status.Optimal;
			return new Result(Engine.CPLEX_ARC_FLOW, true, optimal, String.valueOf(cplex.getStatus()),
					cplex.getObjValue(), safeBestBound(cplex), elapsed, extractCplexRoutes(problem, cplex, x));
		} finally {
			cplex.end();
		}
	}

	public static Result solveWithCpOptimizer(Problem problem, double timeLimitSeconds, int workers) throws IloException {
		long startNanos = System.nanoTime();
		IloCP cp = new IloCP();
		try {
			cp.setOut(null);
			if (timeLimitSeconds > 0) {
				cp.setParameter(IloCP.DoubleParam.TimeLimit, timeLimitSeconds);
			}
			if (workers > 0) {
				cp.setParameter(IloCP.IntParam.Workers, workers);
			}
			int n = problem.n;
			IloIntervalVar[] job = new IloIntervalVar[n + 1];
			IloIntervalVar[][] copy = new IloIntervalVar[n + 1][problem.machines];
			for (int j = 1; j <= n; j++) {
				job[j] = cp.intervalVar(problem.processing[j], "job_" + j);
				cp.add(cp.ge(cp.startOf(job[j]), problem.release[j]));
				IloIntervalVar[] alternatives = new IloIntervalVar[problem.machines];
				for (int k = 0; k < problem.machines; k++) {
					copy[j][k] = cp.intervalVar(problem.processing[j], "job_" + j + "_m_" + k);
					copy[j][k].setOptional();
					alternatives[k] = copy[j][k];
				}
				cp.add(cp.alternative(job[j], alternatives));
			}

			int[][] transition = new int[n + 1][n + 1];
			for (int i = 0; i <= n; i++) {
				for (int j = 0; j <= n; j++) {
					transition[i][j] = i == j ? 0 : problem.setup[i][j];
				}
			}
			IloTransitionDistance distance = cp.transitionDistance(transition);
			IloIntervalVar[] dummy = new IloIntervalVar[problem.machines];
			IloIntervalSequenceVar[] sequence = new IloIntervalSequenceVar[problem.machines];
			for (int k = 0; k < problem.machines; k++) {
				IloIntervalVar[] machineIntervals = new IloIntervalVar[n + 1];
				int[] types = new int[n + 1];
				dummy[k] = cp.intervalVar(0, "dummy_m_" + k);
				machineIntervals[0] = dummy[k];
				types[0] = 0;
				for (int j = 1; j <= n; j++) {
					machineIntervals[j] = copy[j][k];
					types[j] = j;
				}
				sequence[k] = cp.intervalSequenceVar(machineIntervals, types, "seq_m_" + k);
				cp.add(cp.first(sequence[k], dummy[k]));
				cp.add(cp.noOverlap(sequence[k], distance, true));
			}
			addCpArcConstraints(cp, problem, dummy, copy, sequence);

			IloIntExpr[] ends = new IloIntExpr[n];
			for (int j = 1; j <= n; j++) {
				ends[j - 1] = cp.endOf(job[j]);
			}
			IloIntExpr cmax = cp.max(ends);
			cp.add(cp.minimize(cmax));

			boolean solved = cp.solve();
			double elapsed = elapsedSeconds(startNanos);
			if (!solved) {
				return new Result(Engine.CP_OPTIMIZER, false, false, String.valueOf(cp.getStatus()),
						Double.POSITIVE_INFINITY, safeCpBound(cp), elapsed, Collections.emptyList());
			}
			boolean optimal = "Optimal".equals(String.valueOf(cp.getStatus()));
			return new Result(Engine.CP_OPTIMIZER, true, optimal, String.valueOf(cp.getStatus()), cp.getObjValue(),
					safeCpBound(cp), elapsed, extractCpRoutes(problem, cp, copy));
		} finally {
			cp.end();
		}
	}

	public static Result solveWithTimeIndexedCplex(Problem problem, int horizon, double timeLimitSeconds, int threads)
			throws IloException {
		long startNanos = System.nanoTime();
		IloCplex cplex = new IloCplex();
		try {
			cplex.setOut(null);
			if (timeLimitSeconds > 0) {
				cplex.setParam(IloCplex.Param.TimeLimit, timeLimitSeconds);
			}
			if (threads > 0) {
				cplex.setParam(IloCplex.Param.Threads, threads);
			}
			int n = problem.n;
			@SuppressWarnings("unchecked")
			List<IloNumVar>[][] incoming = new ArrayList[n + 1][horizon + 1];
			@SuppressWarnings("unchecked")
			List<IloNumVar>[][] outgoing = new ArrayList[n + 1][horizon + 1];
			@SuppressWarnings("unchecked")
			List<IloNumExpr>[] coverTerms = new ArrayList[n + 1];
			@SuppressWarnings("unchecked")
			List<IloNumExpr>[] completionTerms = new ArrayList[n + 1];
			@SuppressWarnings("unchecked")
			List<IloNumExpr>[][] arcGroup = new ArrayList[n + 1][n + 1];
			for (int j = 1; j <= n; j++) {
				coverTerms[j] = new ArrayList<>();
				completionTerms[j] = new ArrayList<>();
			}
			List<IloNumExpr> sourceTerms = new ArrayList<>();
			List<IloNumExpr> sinkTerms = new ArrayList<>();
			int arcCount = 0;

			for (int j = 1; j <= n; j++) {
				if (problem.forbiddenArc[0][j]) {
					continue;
				}
				int completion = Math.max(problem.release[j], problem.setup[0][j]) + problem.processing[j];
				if (completion <= horizon) {
					IloNumVar var = cplex.boolVar("a_0_" + j + "_" + completion);
					rememberArc(incoming, j, completion, var);
					coverTerms[j].add(var);
					completionTerms[j].add(cplex.prod(completion, var));
					rememberArcGroup(arcGroup, 0, j, var);
					sourceTerms.add(var);
					arcCount++;
				}
			}
			for (int i = 1; i <= n; i++) {
				for (int t = problem.release[i] + problem.processing[i]; t <= horizon; t++) {
					for (int j = 1; j <= n; j++) {
						if (i == j || problem.forbiddenArc[i][j]) {
							continue;
						}
						int completion = Math.max(problem.release[j], t + problem.setup[i][j]) + problem.processing[j];
						if (completion <= horizon) {
							IloNumVar var = cplex.boolVar("a_" + i + "_" + t + "_" + j + "_" + completion);
							rememberArc(outgoing, i, t, var);
							rememberArc(incoming, j, completion, var);
							coverTerms[j].add(var);
							completionTerms[j].add(cplex.prod(completion, var));
							rememberArcGroup(arcGroup, i, j, var);
							arcCount++;
						}
					}
					IloNumVar sink = cplex.boolVar("a_" + i + "_" + t + "_sink");
					rememberArc(outgoing, i, t, sink);
					sinkTerms.add(sink);
					arcCount++;
				}
			}

			for (int j = 1; j <= n; j++) {
				if (coverTerms[j].isEmpty()) {
					cplex.addEq(cplex.constant(0), 1, "cover_infeasible_" + j);
				} else {
					cplex.addEq(sumList(cplex, coverTerms[j]), 1, "cover_" + j);
				}
			}
			cplex.addLe(sumList(cplex, sourceTerms), problem.machines, "machine_count");
			cplex.addEq(sumList(cplex, sourceTerms), sumList(cplex, sinkTerms), "source_sink_balance");
			for (int j = 1; j <= n; j++) {
				for (int t = problem.release[j] + problem.processing[j]; t <= horizon; t++) {
					List<IloNumVar> in = incoming[j][t];
					List<IloNumVar> out = outgoing[j][t];
					if (in != null || out != null) {
						cplex.addEq(sumVarList(cplex, in), sumVarList(cplex, out), "flow_" + j + "_" + t);
					}
				}
			}
			addTimeIndexedRequiredArcConstraints(cplex, problem, arcGroup);
			addTimeIndexedUndirectedAdjacencyConstraints(cplex, problem, arcGroup);
			IloNumVar cmax = cplex.numVar(0, horizon, "Cmax");
			for (int j = 1; j <= n; j++) {
				cplex.addGe(cmax, sumList(cplex, completionTerms[j]), "cmax_" + j);
			}
			cplex.addMinimize(cmax);

			boolean solved = cplex.solve();
			double elapsed = elapsedSeconds(startNanos);
			if (!solved) {
				return new Result(Engine.CPLEX_TIME_INDEXED, false, false,
						String.valueOf(cplex.getStatus()) + ", H=" + horizon + ", arcs=" + arcCount,
						Double.POSITIVE_INFINITY, safeBestBound(cplex), elapsed, Collections.emptyList());
			}
			boolean optimal = cplex.getStatus() == IloCplex.Status.Optimal;
			return new Result(Engine.CPLEX_TIME_INDEXED, true, optimal,
					String.valueOf(cplex.getStatus()) + ", H=" + horizon + ", arcs=" + arcCount, cplex.getObjValue(),
					safeBestBound(cplex), elapsed, Collections.emptyList());
		} finally {
			cplex.end();
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println(
					"Usage: Basic.LocalHorizonCmaxSolver <dataPath> [timeLimitSeconds] [threads] [setup] [dueDate] [timeIndexedHorizon]");
			return;
		}
		double timeLimitSeconds = args.length >= 2 ? Double.parseDouble(args[1]) : 10.0;
		int threads = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
		boolean setup = args.length >= 4 ? Boolean.parseBoolean(args[3]) : false;
		boolean dueDate = args.length >= 5 ? Boolean.parseBoolean(args[4]) : true;
		Problem problem = loadProblemFromInstance(args[0], setup, dueDate);
		System.out.println("problem n=" + problem.n + ", m=" + problem.machines + ", horizonUB="
				+ problem.horizonUpperBound + ", timeLimit=" + timeLimitSeconds + ", threads=" + threads);
		System.out.println(solveWithCplex(problem, timeLimitSeconds, threads));
		System.out.println(solveWithCpOptimizer(problem, timeLimitSeconds, threads));
		if (args.length >= 6) {
			int timeIndexedHorizon = Integer.parseInt(args[5]);
			System.out.println(solveWithTimeIndexedCplex(problem, timeIndexedHorizon, timeLimitSeconds, threads));
		}
	}

	/**
	 * 命令行对照入口使用的轻量 loader。这里只读取 horizon 子问题需要的字段，
	 * 避免触发 Data 构造函数里的 Cmax、外包和调试预处理。
	 */
	private static Problem loadProblemFromInstance(String path, boolean hasSetupBlock, boolean dueDate)
			throws IOException {
		try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
			String[] header = splitTokens(reader.readLine());
			int n = Integer.parseInt(header[0]);
			int machines = Integer.parseInt(header[1]);
			int[] processing = new int[n + 1];
			int[] release = new int[n + 1];
			int[][] setup = new int[n + 1][n + 1];
			for (int j = 1; j <= n; j++) {
				String[] tokens = splitTokens(reader.readLine());
				processing[j] = Integer.parseInt(tokens[0]);
				int dueLate = dueDate ? Integer.parseInt(tokens[1]) : Integer.parseInt(tokens[2]);
				release[j] = Math.max(0, dueLate - processing[j]);
			}
			if (hasSetupBlock) {
				String line = nextNonEmptyLine(reader);
				if (line != null) {
					if ("SETUP".equalsIgnoreCase(line.trim())) {
						for (int i = 0; i <= n; i++) {
							fillSetupRow(setup, i, splitTokens(reader.readLine()), n);
						}
					} else {
						fillSetupRow(setup, 0, splitTokens(line), n);
						for (int i = 1; i <= n; i++) {
							fillSetupRow(setup, i, splitTokens(reader.readLine()), n);
						}
					}
				}
			}
			return new Problem(n, machines, processing, release, setup, null, null, null,
					computeConservativeHorizon(n, processing, release, setup));
		}
	}

	private static String nextNonEmptyLine(BufferedReader reader) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (!line.trim().isEmpty()) {
				return line;
			}
		}
		return null;
	}

	private static String[] splitTokens(String line) {
		return line.trim().split("\\s+");
	}

	private static void fillSetupRow(int[][] setup, int row, String[] tokens, int n) {
		for (int j = 0; j <= n; j++) {
			setup[row][j] = Integer.parseInt(tokens[j]);
		}
	}

	private static IloNumExpr sumIncoming(IloCplex cplex, IloNumVar[][] x, int job) throws IloException {
		List<IloNumExpr> terms = new ArrayList<>();
		for (int i = 0; i < x.length; i++) {
			if (x[i][job] != null) {
				terms.add(x[i][job]);
			}
		}
		return cplex.sum(terms.toArray(new IloNumExpr[0]));
	}

	private static IloNumExpr sumOutgoing(IloCplex cplex, IloNumVar[][] x, int job, int sink) throws IloException {
		List<IloNumExpr> terms = new ArrayList<>();
		for (int j = 1; j <= sink; j++) {
			if (x[job][j] != null) {
				terms.add(x[job][j]);
			}
		}
		return cplex.sum(terms.toArray(new IloNumExpr[0]));
	}

	private static IloNumExpr sumSource(IloCplex cplex, IloNumVar[][] x) throws IloException {
		List<IloNumExpr> terms = new ArrayList<>();
		for (int j = 1; j < x[0].length; j++) {
			if (x[0][j] != null) {
				terms.add(x[0][j]);
			}
		}
		return cplex.sum(terms.toArray(new IloNumExpr[0]));
	}

	private static void addRequiredArcConstraints(IloCplex cplex, Problem problem, IloNumVar[][] x) throws IloException {
		for (int i = 0; i <= problem.n; i++) {
			for (int j = 1; j <= problem.n; j++) {
				if (problem.requiredArc[i][j]) {
					if (x[i][j] == null) {
						cplex.addEq(cplex.constant(0), 1, "required_arc_infeasible_" + i + "_" + j);
					} else {
						cplex.addEq(x[i][j], 1, "required_arc_" + i + "_" + j);
					}
				}
			}
		}
	}

	private static void addUndirectedAdjacencyConstraints(IloCplex cplex, Problem problem, IloNumVar[][] x)
			throws IloException {
		for (int i = 1; i <= problem.n; i++) {
			for (int j = i + 1; j <= problem.n; j++) {
				if (problem.undirectedAdjacency[i][j] || problem.undirectedAdjacency[j][i]) {
					IloNumExpr left = x[i][j] == null ? cplex.constant(0) : x[i][j];
					IloNumExpr right = x[j][i] == null ? cplex.constant(0) : x[j][i];
					cplex.addEq(cplex.sum(left, right), 1, "undirected_adj_" + i + "_" + j);
				}
			}
		}
	}

	private static void addCpArcConstraints(IloCP cp, Problem problem, IloIntervalVar[] dummy, IloIntervalVar[][] copy,
			IloIntervalSequenceVar[] sequence) throws IloException {
		for (int j = 1; j <= problem.n; j++) {
			if (problem.forbiddenArc[0][j]) {
				for (int k = 0; k < problem.machines; k++) {
					cp.add(cp.neq(cp.typeOfNext(sequence[k], dummy[k], problem.n + 1, -1), j));
				}
			}
			if (problem.requiredArc[0][j]) {
				cp.add(cp.ge(sumCpConstraints(cp, requiredNextFromDummy(cp, problem, dummy, sequence, j)), 1));
			}
		}
		for (int i = 1; i <= problem.n; i++) {
			for (int j = 1; j <= problem.n; j++) {
				if (i == j) {
					continue;
				}
				if (problem.forbiddenArc[i][j]) {
					for (int k = 0; k < problem.machines; k++) {
						cp.add(cp.neq(cp.typeOfNext(sequence[k], copy[i][k], problem.n + 1, -1), j));
					}
				}
				if (problem.requiredArc[i][j]) {
					cp.add(cp.ge(sumCpConstraints(cp, requiredNextFromJob(cp, problem, copy, sequence, i, j)), 1));
				}
			}
		}
		for (int i = 1; i <= problem.n; i++) {
			for (int j = i + 1; j <= problem.n; j++) {
				if (problem.undirectedAdjacency[i][j] || problem.undirectedAdjacency[j][i]) {
					List<IloIntExpr> terms = new ArrayList<>();
					terms.addAll(requiredNextFromJob(cp, problem, copy, sequence, i, j));
					terms.addAll(requiredNextFromJob(cp, problem, copy, sequence, j, i));
					cp.add(cp.ge(sumCpConstraints(cp, terms), 1));
				}
			}
		}
	}

	private static List<IloIntExpr> requiredNextFromDummy(IloCP cp, Problem problem, IloIntervalVar[] dummy,
			IloIntervalSequenceVar[] sequence, int nextJob) throws IloException {
		List<IloIntExpr> terms = new ArrayList<>();
		for (int k = 0; k < problem.machines; k++) {
			terms.add(cp.intExpr(cp.eq(cp.typeOfNext(sequence[k], dummy[k], problem.n + 1, -1), nextJob)));
		}
		return terms;
	}

	private static List<IloIntExpr> requiredNextFromJob(IloCP cp, Problem problem, IloIntervalVar[][] copy,
			IloIntervalSequenceVar[] sequence, int fromJob, int nextJob) throws IloException {
		List<IloIntExpr> terms = new ArrayList<>();
		for (int k = 0; k < problem.machines; k++) {
			terms.add(cp.intExpr(cp.eq(cp.typeOfNext(sequence[k], copy[fromJob][k], problem.n + 1, -1), nextJob)));
		}
		return terms;
	}

	private static IloIntExpr sumCpConstraints(IloCP cp, List<IloIntExpr> terms) throws IloException {
		return cp.sum(terms.toArray(new IloIntExpr[0]));
	}

	private static void rememberArc(List<IloNumVar>[][] arcLists, int job, int time, IloNumVar var) {
		if (arcLists[job][time] == null) {
			arcLists[job][time] = new ArrayList<>();
		}
		arcLists[job][time].add(var);
	}

	private static void rememberArcGroup(List<IloNumExpr>[][] arcGroup, int fromJob, int toJob, IloNumVar var) {
		if (arcGroup[fromJob][toJob] == null) {
			arcGroup[fromJob][toJob] = new ArrayList<>();
		}
		arcGroup[fromJob][toJob].add(var);
	}

	private static IloNumExpr sumVarList(IloCplex cplex, List<IloNumVar> terms) throws IloException {
		if (terms == null || terms.isEmpty()) {
			return cplex.constant(0);
		}
		return cplex.sum(terms.toArray(new IloNumExpr[0]));
	}

	private static IloNumExpr sumList(IloCplex cplex, List<IloNumExpr> terms) throws IloException {
		if (terms == null || terms.isEmpty()) {
			return cplex.constant(0);
		}
		return cplex.sum(terms.toArray(new IloNumExpr[0]));
	}

	private static void addTimeIndexedRequiredArcConstraints(IloCplex cplex, Problem problem,
			List<IloNumExpr>[][] arcGroup) throws IloException {
		for (int i = 0; i <= problem.n; i++) {
			for (int j = 1; j <= problem.n; j++) {
				if (problem.requiredArc[i][j]) {
					cplex.addEq(sumList(cplex, arcGroup[i][j]), 1, "ti_required_arc_" + i + "_" + j);
				}
			}
		}
	}

	private static void addTimeIndexedUndirectedAdjacencyConstraints(IloCplex cplex, Problem problem,
			List<IloNumExpr>[][] arcGroup) throws IloException {
		for (int i = 1; i <= problem.n; i++) {
			for (int j = i + 1; j <= problem.n; j++) {
				if (problem.undirectedAdjacency[i][j] || problem.undirectedAdjacency[j][i]) {
					List<IloNumExpr> terms = new ArrayList<>();
					if (arcGroup[i][j] != null) {
						terms.addAll(arcGroup[i][j]);
					}
					if (arcGroup[j][i] != null) {
						terms.addAll(arcGroup[j][i]);
					}
					cplex.addEq(sumList(cplex, terms), 1, "ti_undirected_adj_" + i + "_" + j);
				}
			}
		}
	}

	private static List<List<Integer>> extractCplexRoutes(Problem problem, IloCplex cplex, IloNumVar[][] x)
			throws IloException {
		List<List<Integer>> routes = new ArrayList<>();
		boolean[] usedSource = new boolean[problem.n + 1];
		for (int j = 1; j <= problem.n; j++) {
			if (x[0][j] != null && cplex.getValue(x[0][j]) > 0.5 && !usedSource[j]) {
				List<Integer> route = new ArrayList<>();
				int current = j;
				while (current >= 1 && current <= problem.n && !route.contains(current)) {
					route.add(current);
					usedSource[current] = true;
					int next = findSelectedSuccessor(problem, cplex, x, current);
					if (next == problem.n + 1) {
						break;
					}
					current = next;
				}
				routes.add(route);
			}
		}
		return routes;
	}

	private static int findSelectedSuccessor(Problem problem, IloCplex cplex, IloNumVar[][] x, int current)
			throws IloException {
		for (int j = 1; j <= problem.n + 1; j++) {
			if (x[current][j] != null && cplex.getValue(x[current][j]) > 0.5) {
				return j;
			}
		}
		return problem.n + 1;
	}

	private static List<List<Integer>> extractCpRoutes(Problem problem, IloCP cp, IloIntervalVar[][] copy)
			throws IloException {
		List<List<Integer>> routes = new ArrayList<>();
		for (int k = 0; k < problem.machines; k++) {
			List<JobPlacement> placements = new ArrayList<>();
			for (int j = 1; j <= problem.n; j++) {
				if (cp.isPresent(copy[j][k])) {
					placements.add(new JobPlacement(j, cp.getStart(copy[j][k])));
				}
			}
			placements.sort((a, b) -> Integer.compare(a.start, b.start));
			if (!placements.isEmpty()) {
				List<Integer> route = new ArrayList<>();
				for (JobPlacement placement : placements) {
					route.add(placement.job);
				}
				routes.add(route);
			}
		}
		return routes;
	}

	private static final class JobPlacement {
		final int job;
		final int start;

		JobPlacement(int job, int start) {
			this.job = job;
			this.start = start;
		}
	}

	private static boolean[][] normalizeArcMatrix(boolean[][] input, int n) {
		boolean[][] result = new boolean[n + 1][n + 1];
		if (input == null) {
			return result;
		}
		int maxI = Math.min(n, input.length - 1);
		for (int i = 0; i <= maxI; i++) {
			if (input[i] == null) {
				continue;
			}
			int maxJ = Math.min(n, input[i].length - 1);
			for (int j = 0; j <= maxJ; j++) {
				result[i][j] = input[i][j];
			}
		}
		return result;
	}

	private static int computeConservativeHorizon(int n, int[] processing, int[] release, int[][] setup) {
		int maxRelease = 0;
		int totalProcessing = 0;
		int maxSetup = 0;
		for (int j = 1; j <= n; j++) {
			maxRelease = Math.max(maxRelease, release[j]);
			totalProcessing += processing[j];
			maxSetup = Math.max(maxSetup, setup[0][j]);
		}
		for (int i = 1; i <= n; i++) {
			for (int j = 1; j <= n; j++) {
				if (i != j) {
					maxSetup = Math.max(maxSetup, setup[i][j]);
				}
			}
		}
		return maxRelease + totalProcessing + n * maxSetup;
	}

	private static int toModelInt(double value) {
		if (value <= 0) {
			return 0;
		}
		double rounded = Math.rint(value);
		if (Math.abs(value - rounded) <= 1e-6) {
			return (int) rounded;
		}
		return (int) Math.ceil(value);
	}

	private static double safeBestBound(IloCplex cplex) {
		try {
			return cplex.getBestObjValue();
		} catch (IloException ignored) {
			return Double.NEGATIVE_INFINITY;
		}
	}

	private static double safeCpBound(IloCP cp) {
		try {
			return cp.getObjBound();
		} catch (IloException ignored) {
			return Double.NEGATIVE_INFINITY;
		}
	}

	private static double elapsedSeconds(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000_000.0;
	}
}
