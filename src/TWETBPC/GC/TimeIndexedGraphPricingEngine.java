package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.SequenceSignature;

/**
 * 2026-06-20: 参考 parallel-machine time-indexed graph 论文的实验性 no-cut pricing。
 * <p>
 * 图节点为 (lastJob, t)，处理弧和等待弧都只从小时间指向大时间，因此无 cut 时可以用 DAG 最短路。
 * 该论文的 pricing 允许 pseudo-schedule，即同一 job 在同一路径中重复出现；当前 TWETColumn/RMP 已按
 * visit count 接入这类列，但它仍是 no-cut 单向 DAG 实验定价器，默认关闭。
 */
public class TimeIndexedGraphPricingEngine implements PricingEngine {

	private static final double INF = 1e100;
	private static final double RC_TOLERANCE = 1e-6;
	private final Data data;
	private final TWETBPCConfig config;
	private final boolean preHeuristicMode;
	private final TWETColumnEvaluator evaluator;
	private final StaticPricingData staticPricingData;
	private TimeLimitChecker timeLimitChecker = TimeLimitChecker.NONE;

	public TimeIndexedGraphPricingEngine(Data data, TWETBPCConfig config) {
		this(data, config, false, new StaticPricingData(data));
	}

	TimeIndexedGraphPricingEngine(Data data, TWETBPCConfig config, boolean preHeuristicMode,
			StaticPricingData staticPricingData) {
		this.data = data;
		this.config = config;
		this.preHeuristicMode = preHeuristicMode;
		this.evaluator = new TWETColumnEvaluator(data);
		this.staticPricingData = staticPricingData;
	}

	public static TimeIndexedGraphPricingEngine preHeuristic(Data data, TWETBPCConfig config) {
		return new TimeIndexedGraphPricingEngine(data, config, true, new StaticPricingData(data));
	}

	@Override
	public PricingResult price(LP lp) {
		return price(lp, TimeLimitChecker.NONE);
	}

	@Override
	public PricingResult price(LP lp, TimeLimitChecker timeLimitChecker) {
		this.timeLimitChecker = timeLimitChecker == null ? TimeLimitChecker.NONE : timeLimitChecker;
		if (preHeuristicMode) {
			return pricePreHeuristic(lp);
		}
		if (!config.useTimeIndexedGraphPricing) {
			return PricingResult.noImprovement("Time-indexed graph pricing disabled");
		}
		if (this.timeLimitChecker.isTimeLimitReached()) {
			return PricingResult.noImprovement("Time limit reached before time-indexed graph pricing");
		}
		TimeIndexedGraphSolver solver = new TimeIndexedGraphSolver(lp);
		ArrayList<TWETColumn> columns = solver.solve();
		if (columns.isEmpty()) {
			return PricingResult.noImprovement(solver.message(false));
		}
		return new PricingResult(columns, true, solver.message(true));
	}

	private PricingResult pricePreHeuristic(LP lp) {
		if (!config.enableTimeIndexedPreHeuristicPricing) {
			return PricingResult.noImprovement("Time-indexed pre-heuristic pricing disabled");
		}
		if (!data.isExactIntegerTimeInstance()) {
			return PricingResult.noImprovement("Time-indexed pre-heuristic skipped: non-integer time instance");
		}
		if (lp != null && !lp.getActiveCutIds().isEmpty()) {
			return PricingResult.noImprovement("Time-indexed pre-heuristic skipped: active cuts");
		}
		if (this.timeLimitChecker.isTimeLimitReached()) {
			return PricingResult.noImprovement("Time limit reached before time-indexed pre-heuristic pricing");
		}
		TimeIndexedGraphSolver solver = new TimeIndexedGraphSolver(lp);
		ArrayList<TWETColumn> columns = solver.solve();
		if (columns.isEmpty()) {
			PricingResult result = PricingResult.noImprovement(solver.message(false));
			if (solver.certifiesNoNegativeInternalColumn()) {
				result = result.withCertifiedInternalReducedCost(solver.certifiedInternalReducedCost());
			}
			return result;
		}
		return new PricingResult(columns, true, solver.message(true));
	}

	@Override
	public String getName() {
		return preHeuristicMode ? "TimeIndexedPreHeuristicPricing" : "TimeIndexedGraphPricing";
	}

	/**
	 * 2026-06-20: time-indexed 图定价使用的离散时间窗。
	 * root/no-cut 时可复用主线 pi-window 思路压缩 horizon；其他节点保持静态 hard window。
	 */
	private static GraphWindow computeSafeFixingGraphWindow(Data data, LP lp) {
		// Arc fixing/promotion is UB-LB evidence written back to the node; the
		// current-dual profitable window is only a pricing accelerator.
		return computeGraphWindow(data, lp, true, false);
	}

	private static GraphWindow computeGraphWindow(Data data, LP lp, boolean useCompactWindow, boolean useDualWindow) {
		double[] start = new double[data.n + 1];
		double[] end = new double[data.n + 1];
		start[0] = 0.0;
		end[0] = data.CmaxH;
		boolean dualWindow = useDualWindow && canUseDualProfitableWindow(lp);
		Node node = lp == null ? null : lp.getNode();
		double horizon = 0.0;
		boolean hasFeasibleJob = false;
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			if (useCompactWindow && node != null && node.hasTimeIndexedPricingWindow(job)) {
				hStart = Math.max(hStart, node.getTimeIndexedPricingWindowStart(job));
				hEnd = Math.min(hEnd, node.getTimeIndexedPricingWindowEnd(job));
			}
			if (dualWindow) {
				double baseline = outsourcingBaseline(data, job);
				double jobDual = Math.max(0.0, lp.getJobDual(job));
				if (Utility.compareLt(jobDual, baseline)) {
					hStart = Math.max(hStart, hWindowStart(data, job, jobDual));
					hEnd = Math.min(hEnd, hWindowEnd(data, job, jobDual));
				}
			}
			start[job] = hStart;
			end[job] = hEnd;
			if (!Utility.compareGt(hStart, hEnd) && Double.isFinite(hEnd)) {
				horizon = Math.max(horizon, hEnd);
				hasFeasibleJob = true;
			}
		}
		if (!hasFeasibleJob) {
			horizon = data.CmaxH;
		}
		horizon = Math.min(data.CmaxH, horizon);
		int discreteHorizon = Math.max(0, (int) Math.ceil(horizon - 1e-9));
		return new GraphWindow(discreteHorizon, start, end, dualWindow);
	}

	/**
	 * 2026-07-02: Aggregate time-expanded fixing back to ordinary pricing-only arcs.
	 * A normal arc is disabled only when no feasible time copy remains in the same
	 * time-indexed graph window used by the preprocessing LP.
	 */
	public static int promoteFullyForbiddenTimeIndexedArcsToPricingOnly(Data data, LP graphLp, Node targetNode) {
		if (data == null || graphLp == null || targetNode == null) {
			return 0;
		}
		GraphWindow window = computeSafeFixingGraphWindow(data, graphLp);
		int promoted = 0;
		for (int from = 0; from <= data.n; from++) {
			for (int to = 1; to <= data.n; to++) {
				if (from == to || targetNode.isArcForbidden(from, to) || targetNode.isPricingOnlyArcForbidden(from, to)) {
					continue;
				}
				if (!hasAvailableTimeIndexedProcessCopy(data, targetNode, window, from, to)) {
					targetNode.forbidPricingOnlyArc(from, to);
					promoted++;
				}
			}
		}
		int sink = targetNode.sinkId();
		for (int job = 1; job <= data.n; job++) {
			if (targetNode.isArcForbidden(job, sink) || targetNode.isPricingOnlyArcForbidden(job, sink)) {
				continue;
			}
			if (!hasAvailableTimeIndexedEndCopy(data, targetNode, window, job)) {
				targetNode.forbidPricingOnlyArc(job, sink);
				promoted++;
			}
		}
		return promoted;
	}

	private static boolean hasAvailableTimeIndexedProcessCopy(Data data, Node node, GraphWindow window, int from, int to) {
		int duration = (int) Math.ceil(data.getSetUp(from, to) + data.getProcessT(to) - 1e-9);
		if (duration < 0) {
			return false;
		}
		int start = Math.max(0, (int) Math.ceil(window.start[to] - duration - 1e-9));
		int end = Math.min(window.horizon - duration, (int) Math.floor(window.end[to] - duration + 1e-9));
		for (int time = start; time <= end; time++) {
			int completion = time + duration;
			if (isGraphCompletionFeasible(data, window, to, completion)
					&& !node.isTimeIndexedPricingOnlyArcForbidden(from, to, time)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasAvailableTimeIndexedEndCopy(Data data, Node node, GraphWindow window, int job) {
		int start = Math.max(0, (int) Math.ceil(window.start[job] - 1e-9));
		int end = Math.min(window.horizon, (int) Math.floor(window.end[job] + 1e-9));
		for (int time = start; time <= end; time++) {
			if (isGraphCompletionFeasible(data, window, job, time)
					&& !node.isTimeIndexedPricingOnlyArcForbidden(job, 0, time)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isGraphCompletionFeasible(Data data, GraphWindow window, int job, int completion) {
		if (completion < 0 || completion > window.horizon
				|| Utility.compareLt(completion, window.start[job])
				|| Utility.compareGt(completion, window.end[job])) {
			return false;
		}
		double penalty = data.penaltyFunction[job].evaluate(completion);
		return !Utility.isBigMValue(penalty);
	}
	private static boolean canUseDualProfitableWindow(LP lp) {
		return PricingCompatibility.canUseDualProfitableWindow(lp);
	}

	private static double hWindowStart(Data data, int job, double gamma) {
		if (!Utility.compareGt(data.w_e[job], 0.0)) {
			return 0.0;
		}
		return Math.max(0.0, data.d_e[job] - gamma / data.w_e[job]);
	}

	private static double hWindowEnd(Data data, int job, double gamma) {
		if (!Utility.compareGt(data.w_t[job], 0.0)) {
			return data.CmaxH;
		}
		return Math.min(data.CmaxH, data.d_l[job] + gamma / data.w_t[job]);
	}

	private static double outsourcingBaseline(Data data, int job) {
		return Utility.isBigMValue(data.outsourcingCost[job]) ? Utility.big_M
				: Math.max(0.0, data.outsourcingCost[job]);
	}


	/**
	 * 2026-06-20: 论文 Algorithm 7 口径的 time-expanded reduced-cost arc fixing。
	 * <p>
	 * 它只在当前节点 column generation 已闭合后使用 UB-LB 判定具体时间弧 (from,to,t)，不参与本轮
	 * pricing 内部剪枝，也不复用当前项目的 completion-bound arc fixing。
	 */
	public static ArcFixingResult applyPaperReducedCostArcFixing(Data data, TWETBPCConfig config, LP lp,
			double incumbentCost) {
		if (!config.useTimeIndexedGraphPricing) {
			return ArcFixingResult.skipped("time-indexed graph pricing disabled");
		}
		if (lp == null || lp.getNode() == null || lp.getLastSolution() == null) {
			return ArcFixingResult.skipped("missing LP solution");
		}
		double nodeLowerBound = lp.getLastSolution().getObjectiveValue();
		if (!Double.isFinite(incumbentCost) || !Double.isFinite(nodeLowerBound)) {
			return ArcFixingResult.skipped("missing finite UB/LB");
		}
		double gap = incumbentCost - nodeLowerBound;
		if (Utility.compareLe(gap, RC_TOLERANCE)) {
			return ArcFixingResult.skipped("closed gap");
		}
		ArcFixingSolver solver = new ArcFixingSolver(data, config, lp, gap);
		return solver.apply();
	}

	private final class TimeIndexedGraphSolver {
		private final LP lp;
		private final Node node;
		private final int n;
		private final int sink;
		private final int horizon;
		private final int width;
		private final GraphWindow graphWindow;
		private final double[] dist;
		private final int[] predState;
		private final int[] predAddedJob;
		private final double[][] penaltyByJobTime;
		private final int[][] durationByArc;
		private final double[][] processArcBaseReducedCost;
		private final double[] sinkArcBaseReducedCost;
		private final boolean[][] processArcForbidden;
		private final boolean[] endForbidden;
		private final Node.TimeIndexedArcLookup timeIndexedArcLookup;
		private final HashMap<SequenceSignature, Candidate> candidateBySignature;
		private final PriorityQueue<Candidate> candidateHeap;
		private int relaxedStates;
		private int processArcScans;
		private int timeIndexedArcSkips;
		private int negativeStateCandidates;
		private int duplicateJobCandidates;
		private int nextCandidateId;
		private double bestPseudoReducedCost;
		private int dualWindowRecheckCount;
		private int dualWindowRecheckAccepted;
		private int dualWindowRecheckFiltered;
		private int dualWindowRecheckOutsideWindow;
		private int dualWindowRecheckOutsideCompletions;
		private double dualWindowRecheckMaxRcImprovement;
		private String dualWindowBestCandidateDiagnostic;
		private boolean forwardPassCompleted;

		TimeIndexedGraphSolver(LP lp) {
			this.lp = lp;
			this.node = lp.getNode();
			this.n = data.n;
			this.sink = node == null ? data.n + 1 : node.sinkId();
			this.graphWindow = computeGraphWindow(data, lp, preHeuristicMode,
					config.enableTimeIndexedGraphDualWindow);
			this.horizon = graphWindow.horizon;
			this.width = horizon + 1;
			int stateCount = (n + 1) * width;
			this.dist = new double[stateCount];
			this.predState = new int[stateCount];
			this.predAddedJob = new int[stateCount];
			this.penaltyByJobTime = staticPricingData.penaltyByJobTime;
			this.durationByArc = staticPricingData.durationByArc;
			this.processArcBaseReducedCost = new double[n + 1][n + 1];
			this.sinkArcBaseReducedCost = new double[n + 1];
			this.processArcForbidden = new boolean[n + 1][n + 1];
			this.endForbidden = new boolean[n + 1];
			this.timeIndexedArcLookup = shouldUsePricingOnlyArcs()
					? node.createTimeIndexedPricingOnlyArcLookup() : null;
			this.candidateBySignature = new HashMap<SequenceSignature, Candidate>();
			this.candidateHeap = new PriorityQueue<Candidate>(Math.max(1, maxReturnedColumns()),
					worstCandidateFirstComparator());
			this.bestPseudoReducedCost = INF;
			this.dualWindowRecheckCount = 0;
			this.dualWindowRecheckAccepted = 0;
			this.dualWindowRecheckFiltered = 0;
			this.dualWindowRecheckOutsideWindow = 0;
			this.dualWindowRecheckOutsideCompletions = 0;
			this.dualWindowRecheckMaxRcImprovement = 0.0;
			this.dualWindowBestCandidateDiagnostic = "";
			this.forwardPassCompleted = false;
			precomputePricingData();
		}

		ArrayList<TWETColumn> solve() {
			int maxColumns = maxReturnedColumns();
			if (maxColumns <= 0) {
				return new ArrayList<TWETColumn>();
			}
			runForwardPass();
			forwardPassCompleted = true;
			ArrayList<Candidate> candidates = new ArrayList<Candidate>(candidateBySignature.values());
			Collections.sort(candidates, bestCandidateFirstComparator());
			observeDualWindowBestCandidate(candidates.isEmpty() ? null : candidates.get(0));
			ArrayList<TWETColumn> columns = new ArrayList<TWETColumn>();
			for (int i = 0; i < candidates.size() && columns.size() < maxColumns; i++) {
				TWETColumn column = maybeRecheckSelectedCandidate(candidates.get(i).column);
				if (column != null) {
					columns.add(column);
				}
			}
			return columns;
		}

		private int maxReturnedColumns() {
			if (preHeuristicMode) {
				return config.timeIndexedPreHeuristicColumnLimit;
			}
			return config.timeIndexedGraphMaxExactPricingColumns > 0 ? config.timeIndexedGraphMaxExactPricingColumns
					: config.maxExactPricingColumns;
		}

		private TWETColumn maybeRecheckSelectedCandidate(TWETColumn column) {
			if (!graphWindow.dualWindow) {
				return column;
			}
			double graphReducedCost = reducedCost(column.getSequence(), column.getCost());
			double trueCost = evaluator.evaluate(column.getSequence());
			if (config.timeIndexedDualWindowRecheckDiagnostics) {
				observeDualWindowRecheckDelta(graphReducedCost, reducedCost(column.getSequence(), trueCost));
			}
			if (Utility.isBigMValue(trueCost)) {
				dualWindowRecheckFiltered++;
				return null;
			}
			double trueReducedCost = reducedCost(column.getSequence(), trueCost);
			if (!config.timeIndexedDualWindowRecheckDiagnostics) {
				observeDualWindowRecheckDelta(graphReducedCost, trueReducedCost);
			}
			if (Utility.compareGe(trueReducedCost, -RC_TOLERANCE)) {
				dualWindowRecheckFiltered++;
				return null;
			}
			dualWindowRecheckAccepted++;
			return new TWETColumn(-1, column.getSequence(), n, trueCost, column.getSource(), false);
		}

		private void observeDualWindowRecheckDiagnostics(List<Integer> sequence, double[] completions,
				double graphReducedCost, double trueReducedCost) {
			observeDualWindowRecheckDelta(graphReducedCost, trueReducedCost);
			boolean outside = false;
			int outsideCount = 0;
			int limit = Math.min(sequence.size(), completions.length);
			for (int i = 0; i < limit; i++) {
				int job = sequence.get(i).intValue();
				double completion = completions[i];
				if (Utility.compareLt(completion, graphWindow.start[job] - 1e-7)
						|| Utility.compareGt(completion, graphWindow.end[job] + 1e-7)) {
					outside = true;
					outsideCount++;
				}
			}
			if (outside) {
				dualWindowRecheckOutsideWindow++;
				dualWindowRecheckOutsideCompletions += outsideCount;
			}
		}

		private void observeDualWindowRecheckDelta(double graphReducedCost, double trueReducedCost) {
			dualWindowRecheckCount++;
			double improvement = graphReducedCost - trueReducedCost;
			if (improvement > dualWindowRecheckMaxRcImprovement) {
				dualWindowRecheckMaxRcImprovement = improvement;
			}
		}

		boolean certifiesNoNegativeInternalColumn() {
			return forwardPassCompleted && Utility.compareGe(certifiedInternalReducedCost(), -RC_TOLERANCE);
		}

		double certifiedInternalReducedCost() {
			return Double.isFinite(bestPseudoReducedCost) ? bestPseudoReducedCost : 0.0;
		}

		private double reducedCost(List<Integer> sequence, double cost) {
			double reducedCost = cost - lp.getMachineDual();
			int prev = 0;
			for (int job : sequence) {
				reducedCost -= lp.getJobDual(job);
				reducedCost -= lp.getArcDual(prev, job);
				prev = job;
			}
			reducedCost -= lp.getArcDual(prev, sink);
			return reducedCost;
		}

		String message(boolean improved) {
			String columnKind = preHeuristicMode ? "elementary columns" : "pseudo-schedule columns";
			return getName() + " " + (improved ? "generated " + candidateBySignature.size()
					+ " negative " + columnKind : "found no negative " + columnKind)
					+ ", bestPseudoRC=" + bestPseudoReducedCost
					+ ", horizon=" + horizon
					+ ", piWindow=" + (graphWindow.dualWindow ? "enabled" : "disabled")
					+ ", states=" + relaxedStates
					+ ", arcScans=" + processArcScans
					+ ", timeArcSkips=" + timeIndexedArcSkips
					+ ", negativeStates=" + negativeStateCandidates
					+ ", repeatedJobCandidates=" + duplicateJobCandidates
					+ dualWindowRecheckDiagnosticMessage();
		}

		private String dualWindowRecheckDiagnosticMessage() {
			if (!graphWindow.dualWindow || !config.timeIndexedDualWindowRecheckDiagnostics) {
				return "";
			}
			return ", dualWindowRecheck count/accepted/filtered/outside/outsideCompletions/maxRcImprove="
					+ dualWindowRecheckCount
					+ "/" + dualWindowRecheckAccepted
					+ "/" + dualWindowRecheckFiltered
					+ "/" + dualWindowRecheckOutsideWindow
					+ "/" + dualWindowRecheckOutsideCompletions
					+ "/" + dualWindowRecheckMaxRcImprovement
					+ dualWindowBestCandidateDiagnostic;
		}

		private void observeDualWindowBestCandidate(Candidate candidate) {
			if (!graphWindow.dualWindow || !config.timeIndexedDualWindowRecheckDiagnostics) {
				return;
			}
			if (candidate == null) {
				dualWindowBestCandidateDiagnostic = ", bestGraphCandidate=none";
				return;
			}
			TWETColumn column = candidate.column;
			double graphCost = column.getCost();
			double graphReducedCost = reducedCost(column.getSequence(), graphCost);
			double trueCost = evaluator.evaluate(column.getSequence());
			double trueReducedCost = reducedCost(column.getSequence(), trueCost);
			dualWindowBestCandidateDiagnostic = ", bestGraphCandidate={graphRc=" + graphReducedCost
					+ ", trueRc=" + trueReducedCost
					+ ", graphCost=" + graphCost
					+ ", trueCost=" + trueCost
					+ ", costDiff=" + (graphCost - trueCost)
					+ ", repeated=" + hasRepeatedJob(column.getSequence())
					+ ", len=" + column.size()
					+ ", seq=" + column.getSequence()
					+ "}";
		}

		private void runForwardPass() {
			for (int i = 0; i < dist.length; i++) {
				dist[i] = INF;
				predState[i] = -1;
				predAddedJob[i] = 0;
			}
			dist[index(0, 0)] = 0.0;
			for (int t = 0; t <= horizon; t++) {
				for (int lastJob = 0; lastJob <= n; lastJob++) {
					int state = index(lastJob, t);
					double base = dist[state];
					if (!isFinite(base)) {
						continue;
					}
					relaxedStates++;
					rememberEndCandidateIfNegative(lastJob, t, state, base);
					if (t < horizon) {
						int waitTarget = index(lastJob, t + 1);
						if (!isTimeIndexedArcForbidden(lastJob, lastJob, t)) {
							relax(state, waitTarget, 0.0, 0);
						}
					}
					for (int nextJob = 1; nextJob <= n; nextJob++) {
						if (nextJob == lastJob || processArcForbidden[lastJob][nextJob]) {
							continue;
						}
						processArcScans++;
						if (isTimeIndexedArcForbidden(lastJob, nextJob, t)) {
							timeIndexedArcSkips++;
							continue;
						}
						int completion = completionTime(lastJob, nextJob, t);
						if (completion > horizon || !isCompletionFeasible(nextJob, completion)) {
							continue;
						}
						double arcCost = processArcReducedCost(lastJob, nextJob, completion);
						if (!isFinite(arcCost)) {
							continue;
						}
						int target = index(nextJob, completion);
						relax(state, target, arcCost, nextJob);
					}
				}
			}
		}

		private void rememberEndCandidateIfNegative(int lastJob, int time, int state, double baseReducedCost) {
			if (lastJob == 0 || !isEndAllowed(lastJob, time)) {
				return;
			}
			double reducedCost = baseReducedCost + sinkArcReducedCost(lastJob);
			if (Utility.compareLt(reducedCost, bestPseudoReducedCost)) {
				bestPseudoReducedCost = reducedCost;
			}
			if (Utility.compareGe(reducedCost, -RC_TOLERANCE) || !isPotentialTopCandidate(reducedCost)) {
				return;
			}
			negativeStateCandidates++;
			ArrayList<Integer> sequence = reconstructSequence(state);
			if (sequence.isEmpty()) {
				return;
			}
			if (hasRepeatedJob(sequence)) {
				duplicateJobCandidates++;
				if (preHeuristicMode) {
					return;
				}
			}
			SequenceSignature signature = new SequenceSignature(sequence);
			double cost = objectiveCostFromReducedCost(sequence, reducedCost);
			ColumnSource source = preHeuristicMode ? ColumnSource.PRICING_HEURISTIC : ColumnSource.PRICING_EXACT;
			rememberCandidate(signature, new TWETColumn(-1, sequence, n, cost, source, false),
					reducedCost);
		}

		private double objectiveCostFromReducedCost(ArrayList<Integer> sequence, double reducedCost) {
			double cost = reducedCost + lp.getMachineDual();
			int prev = 0;
			for (int job : sequence) {
				cost += lp.getJobDual(job);
				cost += lp.getArcDual(prev, job);
				prev = job;
			}
			cost += lp.getArcDual(prev, sink);
			return cost;
		}

		private void relax(int fromState, int toState, double arcCost, int addedJob) {
			double value = dist[fromState] + arcCost;
			if (Utility.compareLt(value, dist[toState])) {
				dist[toState] = value;
				storeCompressedPredecessor(predState, predAddedJob, fromState, toState, addedJob);
			}
		}

		private int completionTime(int lastJob, int nextJob, int currentTime) {
			return currentTime + durationByArc[lastJob][nextJob];
		}

		private boolean isCompletionFeasible(int job, int completion) {
			return completion >= 0 && completion <= horizon
					&& !Utility.compareLt(completion, graphWindow.start[job])
					&& !Utility.compareGt(completion, graphWindow.end[job])
					&& isFinite(penaltyByJobTime[job][completion]);
		}

		private double processArcReducedCost(int from, int to, int completion) {
			double penalty = penaltyByJobTime[to][completion];
			return isFinite(penalty) ? processArcBaseReducedCost[from][to] + penalty : INF;
		}

		private double sinkArcReducedCost(int lastJob) {
			return sinkArcBaseReducedCost[lastJob];
		}

		private boolean isEndAllowed(int lastJob, int time) {
			return lastJob > 0 && !endForbidden[lastJob] && !isTimeIndexedArcForbidden(lastJob, 0, time);
		}

		private void precomputePricingData() {
			for (int from = 0; from <= n; from++) {
				for (int to = 1; to <= n; to++) {
					processArcForbidden[from][to] = from == to
							|| PricingCompatibility.isRequiredOutsourcedJob(node, to)
							|| isProcessArcForbiddenByNode(from, to);
					processArcBaseReducedCost[from][to] = data.getSetupCost(from, to)
							- lp.getJobDual(to) - lp.getArcDual(from, to)
							- (from == 0 ? lp.getMachineDual() : 0.0);
				}
			}
			for (int job = 1; job <= n; job++) {
				endForbidden[job] = isEndArcForbiddenByNode(job);
				sinkArcBaseReducedCost[job] = -lp.getArcDual(job, sink);
			}
		}
		private boolean isProcessArcForbiddenByNode(int from, int to) {
			if (node == null) {
				return data.isPreprocessedArcForbidden(from, to);
			}
			if (node.isArcForbidden(from, to)) {
				return true;
			}
			return shouldUsePricingOnlyArcs() && node.isPricingOnlyArcForbidden(from, to);
		}

		private boolean isEndArcForbiddenByNode(int lastJob) {
			if (node == null) {
				return data.isPreprocessedArcForbidden(lastJob, sink);
			}
			if (node.isArcForbidden(lastJob, sink)) {
				return true;
			}
			return shouldUsePricingOnlyArcs() && node.isPricingOnlyArcForbidden(lastJob, sink);
		}

		private boolean isTimeIndexedArcForbidden(int from, int to, int time) {
			return timeIndexedArcLookup != null && timeIndexedArcLookup.isForbidden(from, to, time);
		}

		private boolean shouldUsePricingOnlyArcs() {
			return node != null && node.id != config.debugIgnorePricingOnlyArcsAtNode;
		}

		private ArrayList<Integer> reconstructSequence(int state) {
			ArrayList<Integer> reversed = new ArrayList<Integer>();
			int current = state;
			while (current >= 0) {
				int addedJob = predAddedJob[current];
				if (addedJob > 0) {
					reversed.add(Integer.valueOf(addedJob));
				}
				current = predState[current];
			}
			Collections.reverse(reversed);
			return reversed;
		}

		private boolean hasRepeatedJob(List<Integer> sequence) {
			boolean[] seen = new boolean[n + 1];
			for (int i = 0; i < sequence.size(); i++) {
				int job = sequence.get(i).intValue();
				if (seen[job]) {
					return true;
				}
				seen[job] = true;
			}
			return false;
		}

		private boolean isPotentialTopCandidate(double reducedCost) {
			if (candidateBySignature.size() < maxReturnedColumns()) {
				return true;
			}
			Candidate worst = currentWorstCandidate();
			return worst != null && Utility.compareLt(reducedCost, worst.reducedCost);
		}

		private void rememberCandidate(SequenceSignature signature, TWETColumn column, double reducedCost) {
			Candidate existing = candidateBySignature.get(signature);
			if (existing != null && Utility.compareLe(existing.reducedCost, reducedCost)) {
				return;
			}
			Candidate candidate = new Candidate(nextCandidateId++, signature, column, reducedCost);
			candidateBySignature.put(signature, candidate);
			candidateHeap.add(candidate);
			int maxColumns = maxReturnedColumns();
			while (candidateBySignature.size() > maxColumns) {
				Candidate worst = currentWorstCandidate();
				if (worst == null) {
					break;
				}
				candidateBySignature.remove(worst.signature);
				candidateHeap.poll();
			}
		}

		private Candidate currentWorstCandidate() {
			while (!candidateHeap.isEmpty()) {
				Candidate top = candidateHeap.peek();
				if (candidateBySignature.get(top.signature) == top) {
					return top;
				}
				candidateHeap.poll();
			}
			return null;
		}

		private int index(int job, int time) {
			return job * width + time;
		}

		private boolean isFinite(double value) {
			return value < INF * 0.5;
		}
	}

	/**
	 * 2026-07-14: 等待弧不改变任务序列，直接继承最近一次处理弧的回溯记录。
	 * 这样恢复序列只经过真实任务弧，复杂度由 O(horizon) 降为 O(sequence length)。
	 */
	static void storeCompressedPredecessor(int[] predecessor, int[] addedJobs, int fromState, int toState,
			int addedJob) {
		if (addedJob == 0) {
			predecessor[toState] = predecessor[fromState];
			addedJobs[toState] = addedJobs[fromState];
		} else {
			predecessor[toState] = fromState;
			addedJobs[toState] = addedJob;
		}
	}
	private static final class ArcFixingSolver {
		private final Data data;
		private final TWETBPCConfig config;
		private final LP lp;
		private final Node node;
		private final int n;
		private final int sink;
		private final int horizon;
		private final int width;
		private final GraphWindow graphWindow;
		private final double gap;
		private final double[] forward;
		private final double[] backward;
		private final double[][] penaltyByJobTime;
		private final int[][] durationByArc;
		private final double[][] processArcBaseReducedCost;
		private final double[] sinkArcBaseReducedCost;
		private final boolean[][] processArcForbidden;
		private final boolean[] endForbidden;

		ArcFixingSolver(Data data, TWETBPCConfig config, LP lp, double gap) {
			this.data = data;
			this.config = config;
			this.lp = lp;
			this.node = lp.getNode();
			this.n = data.n;
			this.sink = node.sinkId();
			this.graphWindow = computeSafeFixingGraphWindow(data, lp);
			this.horizon = graphWindow.horizon;
			this.width = horizon + 1;
			this.gap = gap;
			int stateCount = (n + 1) * width;
			this.forward = new double[stateCount];
			this.backward = new double[stateCount];
			this.penaltyByJobTime = new double[n + 1][width];
			this.durationByArc = new int[n + 1][n + 1];
			this.processArcBaseReducedCost = new double[n + 1][n + 1];
			this.sinkArcBaseReducedCost = new double[n + 1];
			this.processArcForbidden = new boolean[n + 1][n + 1];
			this.endForbidden = new boolean[n + 1];
			precomputeStaticPricingData();
		}

		ArcFixingResult apply() {
			long start = System.nanoTime();
			computeForwardDistances();
			computeBackwardDistances();
			int processCandidates = 0;
			int processFixed = 0;
			int idleCandidates = 0;
			int idleFixed = 0;
			int endCandidates = 0;
			int endFixed = 0;
			int unavailable = 0;
			for (int t = 0; t <= horizon; t++) {
				for (int from = 0; from <= n; from++) {
					double prefix = forward[index(from, t)];
					if (!isFinite(prefix)) {
						continue;
					}
					for (int to = 1; to <= n; to++) {
						if (to == from || processArcForbidden[from][to] || isTimeIndexedArcForbidden(from, to, t)) {
							continue;
						}
						processCandidates++;
						int completion = t + durationByArc[from][to];
						if (completion > horizon || !isCompletionFeasible(to, completion)) {
							unavailable++;
							continue;
						}
						double backwardCost = backward[index(to, completion)];
						double arcCost = processArcReducedCost(from, to, completion);
						if (!isFinite(backwardCost) || !isFinite(arcCost)) {
							unavailable++;
							continue;
						}
						double cmin = prefix + arcCost + backwardCost;
						if (Utility.compareGe(cmin, gap - RC_TOLERANCE)) {
							node.forbidTimeIndexedPricingOnlyArc(from, to, t);
							processFixed++;
						}
					}
					if (t < horizon && !isTimeIndexedArcForbidden(from, from, t)) {
						idleCandidates++;
						double backwardCost = backward[index(from, t + 1)];
						if (isFinite(backwardCost)) {
							double cmin = prefix + backwardCost;
							if (Utility.compareGe(cmin, gap - RC_TOLERANCE)) {
								node.forbidTimeIndexedPricingOnlyArc(from, from, t);
								idleFixed++;
							}
						} else {
							unavailable++;
						}
					}
					if (from > 0 && isEndAllowed(from, t)) {
						endCandidates++;
						double cmin = prefix + sinkArcReducedCost(from);
						if (Utility.compareGe(cmin, gap - RC_TOLERANCE)) {
							node.forbidTimeIndexedPricingOnlyArc(from, 0, t);
							endFixed++;
						}
					}
				}
			}
			int cleanupFixed = cleanupGraph();
			int candidates = processCandidates + idleCandidates + endCandidates;
			int fixed = processFixed + idleFixed + endFixed + cleanupFixed;
			return new ArcFixingResult(true, candidates, fixed, processFixed, idleFixed, endFixed, cleanupFixed,
					unavailable, gap, false, System.nanoTime() - start,
					"paper time-indexed reduced-cost arc fixing");
		}

		private void computeForwardDistances() {
			for (int i = 0; i < forward.length; i++) {
				forward[i] = INF;
			}
			forward[index(0, 0)] = 0.0;
			for (int t = 0; t <= horizon; t++) {
				for (int lastJob = 0; lastJob <= n; lastJob++) {
					int state = index(lastJob, t);
					double base = forward[state];
					if (!isFinite(base)) {
						continue;
					}
					if (t < horizon) {
						if (!isTimeIndexedArcForbidden(lastJob, lastJob, t)) {
							relax(forward, index(lastJob, t + 1), base);
						}
					}
					for (int nextJob = 1; nextJob <= n; nextJob++) {
						if (nextJob == lastJob || processArcForbidden[lastJob][nextJob]
								|| isTimeIndexedArcForbidden(lastJob, nextJob, t)) {
							continue;
						}
						int completion = t + durationByArc[lastJob][nextJob];
						if (completion > horizon || !isCompletionFeasible(nextJob, completion)) {
							continue;
						}
						double arcCost = processArcReducedCost(lastJob, nextJob, completion);
						if (isFinite(arcCost)) {
							relax(forward, index(nextJob, completion), base + arcCost);
						}
					}
				}
			}
		}

		private void computeBackwardDistances() {
			for (int i = 0; i < backward.length; i++) {
				backward[i] = INF;
			}
			backward[index(0, horizon)] = 0.0;
			for (int t = horizon - 1; t >= 0; t--) {
				for (int lastJob = 0; lastJob <= n; lastJob++) {
					int state = index(lastJob, t);
					if (!isTimeIndexedArcForbidden(lastJob, lastJob, t)) {
						relax(backward, state, backward[index(lastJob, t + 1)]);
					}
					for (int nextJob = 1; nextJob <= n; nextJob++) {
						if (nextJob == lastJob || processArcForbidden[lastJob][nextJob]
								|| isTimeIndexedArcForbidden(lastJob, nextJob, t)) {
							continue;
						}
						int completion = t + durationByArc[lastJob][nextJob];
						if (completion > horizon || !isCompletionFeasible(nextJob, completion)) {
							continue;
						}
						double backwardCost = backward[index(nextJob, completion)];
						double arcCost = processArcReducedCost(lastJob, nextJob, completion);
						if (isFinite(backwardCost) && isFinite(arcCost)) {
							relax(backward, state, arcCost + backwardCost);
						}
					}
					if (lastJob > 0 && isEndAllowed(lastJob, t)) {
						relax(backward, state, sinkArcReducedCost(lastJob) + backward[index(0, horizon)]);
					}
				}
			}
		}

		private int cleanupGraph() {
			computeForwardDistances();
			computeBackwardDistances();
			int fixed = 0;
			// 2026-07-14: 倒序扫描时增量维护“更晚时间仍有有用处理弧”，避免先额外扫描一遍 O(n^2 H)。
			boolean[] usefulProcessingAtLaterTime = new boolean[n + 1];
			for (int t = horizon; t >= 0; t--) {
				for (int from = 0; from <= n; from++) {
					boolean fromReachable = isFinite(forward[index(from, t)]);
					boolean usefulProcessingAtCurrentTime = false;
					for (int to = 1; to <= n; to++) {
						if (to == from || processArcForbidden[from][to] || isTimeIndexedArcForbidden(from, to, t)) {
							continue;
						}
						int completion = t + durationByArc[from][to];
						boolean completionUseful = completion <= horizon && isCompletionFeasible(to, completion)
								&& fromReachable && isFinite(backward[index(to, completion)]);
						if (completionUseful && isFinite(processArcReducedCost(from, to, completion))) {
							usefulProcessingAtCurrentTime = true;
						}
						if (!completionUseful) {
							node.forbidTimeIndexedPricingOnlyArc(from, to, t);
							fixed++;
						}
					}
					if (t < horizon && !isTimeIndexedArcForbidden(from, from, t)
							&& (!fromReachable || !isFinite(backward[index(from, t + 1)]))) {
						node.forbidTimeIndexedPricingOnlyArc(from, from, t);
						fixed++;
					}
					// 若当前可以直接结束，且更晚时间没有任何有用处理弧，继续等待只会推迟同一路径的结束。
					if (from > 0 && t < horizon && isEndAllowed(from, t)
							&& !isTimeIndexedArcForbidden(from, from, t)
							&& !usefulProcessingAtLaterTime[from]) {
						node.forbidTimeIndexedPricingOnlyArc(from, from, t);
						fixed++;
					}
					if (from > 0 && isEndAllowed(from, t) && !fromReachable) {
						node.forbidTimeIndexedPricingOnlyArc(from, 0, t);
						fixed++;
					}
					if (usefulProcessingAtCurrentTime) {
						usefulProcessingAtLaterTime[from] = true;
					}
				}
			}
			return fixed;
		}
		private void relax(double[] values, int index, double candidate) {
			if (Utility.compareLt(candidate, values[index])) {
				values[index] = candidate;
			}
		}

		private void precomputeStaticPricingData() {
			for (int job = 0; job <= n; job++) {
				for (int t = 0; t <= horizon; t++) {
					penaltyByJobTime[job][t] = INF;
				}
			}
			for (int job = 1; job <= n; job++) {
				int start = Math.max(0, (int) Math.ceil(graphWindow.start[job] - 1e-9));
				int end = Math.min(horizon, (int) Math.floor(graphWindow.end[job] + 1e-9));
				for (int t = start; t <= end; t++) {
					double penalty = data.penaltyFunction[job].evaluate(t);
					if (!Utility.isBigMValue(penalty)) {
						penaltyByJobTime[job][t] = penalty;
					}
				}
			}
			for (int from = 0; from <= n; from++) {
				for (int to = 1; to <= n; to++) {
					durationByArc[from][to] =
							(int) Math.ceil(data.getSetUp(from, to) + data.getProcessT(to) - 1e-9);
					processArcForbidden[from][to] = from == to
							|| PricingCompatibility.isRequiredOutsourcedJob(node, to)
							|| isProcessArcForbiddenByNode(from, to);
					processArcBaseReducedCost[from][to] = data.getSetupCost(from, to)
							- lp.getJobDual(to) - lp.getArcDual(from, to)
							- (from == 0 ? lp.getMachineDual() : 0.0);
				}
			}
			for (int job = 1; job <= n; job++) {
				endForbidden[job] = isEndArcForbiddenByNode(job);
				sinkArcBaseReducedCost[job] = -lp.getArcDual(job, sink);
			}
		}
		private boolean isCompletionFeasible(int job, int completion) {
			return completion >= 0 && completion <= horizon
					&& !Utility.compareLt(completion, graphWindow.start[job])
					&& !Utility.compareGt(completion, graphWindow.end[job])
					&& isFinite(penaltyByJobTime[job][completion]);
		}

		private double processArcReducedCost(int from, int to, int completion) {
			double penalty = penaltyByJobTime[to][completion];
			return isFinite(penalty) ? processArcBaseReducedCost[from][to] + penalty : INF;
		}

		private double sinkArcReducedCost(int lastJob) {
			return sinkArcBaseReducedCost[lastJob];
		}

		private boolean isEndAllowed(int lastJob, int time) {
			return lastJob > 0 && !endForbidden[lastJob] && !isTimeIndexedArcForbidden(lastJob, 0, time);
		}

		private boolean isProcessArcForbiddenByNode(int from, int to) {
			if (node.isArcForbidden(from, to)) {
				return true;
			}
			return shouldUsePricingOnlyArcs() && node.isPricingOnlyArcForbidden(from, to);
		}

		private boolean isEndArcForbiddenByNode(int lastJob) {
			if (node.isArcForbidden(lastJob, sink)) {
				return true;
			}
			return shouldUsePricingOnlyArcs() && node.isPricingOnlyArcForbidden(lastJob, sink);
		}

		private boolean isTimeIndexedArcForbidden(int from, int to, int time) {
			return shouldUsePricingOnlyArcs() && node.isTimeIndexedPricingOnlyArcForbidden(from, to, time);
		}

		private boolean shouldUsePricingOnlyArcs() {
			return node != null && node.id != config.debugIgnorePricingOnlyArcsAtNode;
		}

		private int index(int job, int time) {
			return job * width + time;
		}
	}

	public static final class ArcFixingResult {
		private final boolean available;
		private final int candidates;
		private final int fixed;
		private final int processFixed;
		private final int idleFixed;
		private final int endFixed;
		private final int cleanupFixed;
		private final int unavailable;
		private final double gap;
		private final boolean reusedForwardDistances;
		private final long totalNanos;
		private final String message;

		private ArcFixingResult(boolean available, int candidates, int fixed, int processFixed, int idleFixed,
				int endFixed, int cleanupFixed, int unavailable, double gap, boolean reusedForwardDistances,
				long totalNanos, String message) {
			this.available = available;
			this.candidates = candidates;
			this.fixed = fixed;
			this.processFixed = processFixed;
			this.idleFixed = idleFixed;
			this.endFixed = endFixed;
			this.cleanupFixed = cleanupFixed;
			this.unavailable = unavailable;
			this.gap = gap;
			this.reusedForwardDistances = reusedForwardDistances;
			this.totalNanos = totalNanos;
			this.message = message;
		}

		static ArcFixingResult skipped(String message) {
			return new ArcFixingResult(false, 0, 0, 0, 0, 0, 0, 0, Double.NaN, false, 0L, message);
		}

		public boolean isAvailable() {
			return available;
		}

		public int getCandidates() {
			return candidates;
		}

		public int getFixed() {
			return fixed;
		}

		public int getProcessFixed() {
			return processFixed;
		}

		public int getIdleFixed() {
			return idleFixed;
		}

		public int getEndFixed() {
			return endFixed;
		}

		public int getCleanupFixed() {
			return cleanupFixed;
		}

		public int getUnavailable() {
			return unavailable;
		}

		public double getGap() {
			return gap;
		}

		public boolean isReusedForwardDistances() {
			return reusedForwardDistances;
		}

		public long getTotalNanos() {
			return totalNanos;
		}

		public String summary() {
			return message + ", candidates=" + candidates + ", fixed=" + fixed + ", unavailable=" + unavailable
					+ ", processFixed=" + processFixed + ", idleFixed=" + idleFixed + ", endFixed=" + endFixed
					+ ", cleanupFixed=" + cleanupFixed + ", reusedForward=" + reusedForwardDistances
					+ ", gap=" + gap + ", ms="
					+ String.format("%.3f", totalNanos / 1_000_000.0);
		}
	}

	/**
	 * 实例级只读离散数据。penalty 与 duration 不依赖 dual/node，定价轮次之间只构造一次。
	 */
	static final class StaticPricingData {
		final double[][] penaltyByJobTime;
		final int[][] durationByArc;

		StaticPricingData(Data data) {
			int n = data.n;
			int horizon = Math.max(0, (int) Math.ceil(data.CmaxH - 1e-9));
			this.penaltyByJobTime = new double[n + 1][horizon + 1];
			this.durationByArc = new int[n + 1][n + 1];
			for (int job = 0; job <= n; job++) {
				for (int t = 0; t <= horizon; t++) {
					penaltyByJobTime[job][t] = INF;
				}
			}
			for (int job = 1; job <= n; job++) {
				int start = Math.max(0, (int) Math.ceil(data.hardWindowStart[job] - 1e-9));
				int end = Math.min(horizon, (int) Math.floor(data.hardWindowEnd[job] + 1e-9));
				for (int t = start; t <= end; t++) {
					double penalty = data.penaltyFunction[job].evaluate(t);
					if (!Utility.isBigMValue(penalty)) {
						penaltyByJobTime[job][t] = penalty;
					}
				}
			}
			for (int from = 0; from <= n; from++) {
				for (int to = 1; to <= n; to++) {
					durationByArc[from][to] =
							(int) Math.ceil(data.getSetUp(from, to) + data.getProcessT(to) - 1e-9);
				}
			}
		}
	}
	private static final class GraphWindow {
		final int horizon;
		final double[] start;
		final double[] end;
		final boolean dualWindow;

		GraphWindow(int horizon, double[] start, double[] end, boolean dualWindow) {
			this.horizon = horizon;
			this.start = start;
			this.end = end;
			this.dualWindow = dualWindow;
		}
	}


	private static boolean isFinite(double value) {
		return value < INF * 0.5;
	}

	private Comparator<Candidate> worstCandidateFirstComparator() {
		return new Comparator<Candidate>() {
			@Override
			public int compare(Candidate a, Candidate b) {
				int reducedCostCompare = Double.compare(b.reducedCost, a.reducedCost);
				if (reducedCostCompare != 0) {
					return reducedCostCompare;
				}
				return Integer.compare(b.id, a.id);
			}
		};
	}

	private Comparator<Candidate> bestCandidateFirstComparator() {
		return new Comparator<Candidate>() {
			@Override
			public int compare(Candidate a, Candidate b) {
				int reducedCostCompare = Double.compare(a.reducedCost, b.reducedCost);
				if (reducedCostCompare != 0) {
					return reducedCostCompare;
				}
				return Integer.compare(a.id, b.id);
			}
		};
	}

	private static final class Candidate {
		final int id;
		final SequenceSignature signature;
		final TWETColumn column;
		final double reducedCost;

		Candidate(int id, SequenceSignature signature, TWETColumn column, double reducedCost) {
			this.id = id;
			this.signature = signature;
			this.column = column;
			this.reducedCost = reducedCost;
		}
	}
}
