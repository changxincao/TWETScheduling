package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
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
 * 该论文的 pricing 允许 pseudo-schedule，即同一 job 在同一路径中重复出现；当前 TWET RMP 的覆盖系数仍是
 * containsJob 的 0/1 语义，所以该定价器只作为实验对照开关使用，默认关闭。
 */
public class TimeIndexedGraphPricingEngine implements PricingEngine {

	private static final double INF = 1e100;
	private static final double RC_TOLERANCE = 1e-6;

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;

	public TimeIndexedGraphPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	@Override
	public PricingResult price(LP lp) {
		if (!config.useTimeIndexedGraphPricing) {
			return PricingResult.noImprovement("Time-indexed graph pricing disabled");
		}
		TimeIndexedGraphSolver solver = new TimeIndexedGraphSolver(lp);
		ArrayList<TWETColumn> columns = solver.solve();
		if (columns.isEmpty()) {
			return PricingResult.noImprovement(solver.message(false));
		}
		return new PricingResult(columns, true, solver.message(true));
	}

	@Override
	public String getName() {
		return "TimeIndexedGraphPricing";
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
		private final double[] dist;
		private final int[] predState;
		private final int[] predAddedJob;
		private final double[][] penaltyByJobTime;
		private final int[][] durationByArc;
		private final boolean[][] processArcForbidden;
		private final boolean[] endForbidden;
		private final HashSet<SequenceSignature> activeSignatures;
		private final HashMap<SequenceSignature, Candidate> candidateBySignature;
		private final PriorityQueue<Candidate> candidateHeap;
		private int relaxedStates;
		private int processArcScans;
		private int timeIndexedArcSkips;
		private int negativeStateCandidates;
		private int duplicateJobCandidates;
		private int nextCandidateId;
		private double bestPseudoReducedCost;

		TimeIndexedGraphSolver(LP lp) {
			this.lp = lp;
			this.node = lp.getNode();
			this.n = data.n;
			this.sink = node == null ? data.n + 1 : node.sinkId();
			this.horizon = Math.max(0, (int) Math.ceil(data.CmaxH - 1e-9));
			this.width = horizon + 1;
			int stateCount = (n + 1) * width;
			this.dist = new double[stateCount];
			this.predState = new int[stateCount];
			this.predAddedJob = new int[stateCount];
			this.penaltyByJobTime = new double[n + 1][width];
			this.durationByArc = new int[n + 1][n + 1];
			this.processArcForbidden = new boolean[n + 1][n + 1];
			this.endForbidden = new boolean[n + 1];
			this.activeSignatures = collectActiveSignatures();
			this.candidateBySignature = new HashMap<SequenceSignature, Candidate>();
			this.candidateHeap = new PriorityQueue<Candidate>(Math.max(1, config.maxExactPricingColumns),
					worstCandidateFirstComparator());
			this.bestPseudoReducedCost = INF;
			precomputeStaticPricingData();
		}

		ArrayList<TWETColumn> solve() {
			if (config.maxExactPricingColumns <= 0) {
				return new ArrayList<TWETColumn>();
			}
			runForwardPass();
			ArrayList<Candidate> candidates = new ArrayList<Candidate>(candidateBySignature.values());
			Collections.sort(candidates, bestCandidateFirstComparator());
			ArrayList<TWETColumn> columns = new ArrayList<TWETColumn>();
			for (int i = 0; i < candidates.size() && columns.size() < config.maxExactPricingColumns; i++) {
				columns.add(candidates.get(i).column);
			}
			return columns;
		}

		String message(boolean improved) {
			return "Time-indexed graph pricing " + (improved ? "generated " + candidateBySignature.size()
					+ " negative pseudo-schedule columns" : "found no negative pseudo-schedule")
					+ ", bestPseudoRC=" + bestPseudoReducedCost
					+ ", horizon=" + horizon
					+ ", states=" + relaxedStates
					+ ", arcScans=" + processArcScans
					+ ", timeArcSkips=" + timeIndexedArcSkips
					+ ", negativeStates=" + negativeStateCandidates
					+ ", repeatedJobCandidates=" + duplicateJobCandidates;
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
			}
			SequenceSignature signature = new SequenceSignature(sequence);
			if (activeSignatures.contains(signature)) {
				return;
			}
			double cost = evaluator.evaluate(sequence);
			if (Utility.isBigMValue(cost)) {
				return;
			}
			rememberCandidate(signature, new TWETColumn(-1, sequence, n, cost, ColumnSource.PRICING_EXACT, false),
					reducedCost);
		}

		private void relax(int fromState, int toState, double arcCost, int addedJob) {
			double value = dist[fromState] + arcCost;
			if (Utility.compareLt(value, dist[toState])) {
				dist[toState] = value;
				predState[toState] = fromState;
				predAddedJob[toState] = addedJob;
			}
		}

		private int completionTime(int lastJob, int nextJob, int currentTime) {
			return currentTime + durationByArc[lastJob][nextJob];
		}

		private boolean isCompletionFeasible(int job, int completion) {
			return completion >= 0 && completion <= horizon && isFinite(penaltyByJobTime[job][completion]);
		}

		private double processArcReducedCost(int from, int to, int completion) {
			double penalty = penaltyByJobTime[to][completion];
			if (!isFinite(penalty)) {
				return INF;
			}
			double cost = data.getSetupCost(from, to) + penalty - lp.getJobDual(to) - lp.getArcDual(from, to);
			if (from == 0) {
				cost -= lp.getMachineDual();
			}
			return cost;
		}

		private double sinkArcReducedCost(int lastJob) {
			return -lp.getArcDual(lastJob, sink);
		}

		private boolean isEndAllowed(int lastJob, int time) {
			return lastJob > 0 && !endForbidden[lastJob] && !isTimeIndexedArcForbidden(lastJob, 0, time);
		}

		private void precomputeStaticPricingData() {
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
					processArcForbidden[from][to] = from == to || isProcessArcForbiddenByNode(from, to);
				}
			}
			for (int job = 1; job <= n; job++) {
				endForbidden[job] = isEndArcForbiddenByNode(job);
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
			return shouldUsePricingOnlyArcs() && node.isTimeIndexedPricingOnlyArcForbidden(from, to, time);
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

		private boolean hasRepeatedJob(ArrayList<Integer> sequence) {
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
			if (candidateBySignature.size() < config.maxExactPricingColumns) {
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
			while (candidateBySignature.size() > config.maxExactPricingColumns) {
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

		private HashSet<SequenceSignature> collectActiveSignatures() {
			HashSet<SequenceSignature> signatures = new HashSet<SequenceSignature>();
			for (int columnId : lp.getRestrictedColumnIds()) {
				signatures.add(lp.getPool().getColumn(columnId).getSignature());
			}
			return signatures;
		}

		private int index(int job, int time) {
			return job * width + time;
		}

		private boolean isFinite(double value) {
			return value < INF * 0.5;
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
		private final double gap;
		private final double[] forward;
		private final double[] backward;
		private final double[][] penaltyByJobTime;
		private final int[][] durationByArc;
		private final boolean[][] processArcForbidden;
		private final boolean[] endForbidden;

		ArcFixingSolver(Data data, TWETBPCConfig config, LP lp, double gap) {
			this.data = data;
			this.config = config;
			this.lp = lp;
			this.node = lp.getNode();
			this.n = data.n;
			this.sink = node.sinkId();
			this.horizon = Math.max(0, (int) Math.ceil(data.CmaxH - 1e-9));
			this.width = horizon + 1;
			this.gap = gap;
			int stateCount = (n + 1) * width;
			this.forward = new double[stateCount];
			this.backward = new double[stateCount];
			this.penaltyByJobTime = new double[n + 1][width];
			this.durationByArc = new int[n + 1][n + 1];
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
					unavailable, gap, System.nanoTime() - start, "paper time-indexed reduced-cost arc fixing");
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
			for (int t = 0; t <= horizon; t++) {
				for (int from = 0; from <= n; from++) {
					boolean fromReachable = isFinite(forward[index(from, t)]);
					for (int to = 1; to <= n; to++) {
						if (to == from || processArcForbidden[from][to] || isTimeIndexedArcForbidden(from, to, t)) {
							continue;
						}
						int completion = t + durationByArc[from][to];
						if (completion > horizon || !isCompletionFeasible(to, completion)
								|| !fromReachable || !isFinite(backward[index(to, completion)])) {
							node.forbidTimeIndexedPricingOnlyArc(from, to, t);
							fixed++;
						}
					}
					if (t < horizon && !isTimeIndexedArcForbidden(from, from, t)
							&& (!fromReachable || !isFinite(backward[index(from, t + 1)]))) {
						node.forbidTimeIndexedPricingOnlyArc(from, from, t);
						fixed++;
					}
					if (from > 0 && isEndAllowed(from, t) && !fromReachable) {
						node.forbidTimeIndexedPricingOnlyArc(from, 0, t);
						fixed++;
					}
					if (from > 0 && t < horizon && isEndAllowed(from, t)
							&& !isTimeIndexedArcForbidden(from, from, t)
							&& !hasProcessingOutgoingAtOrAfter(from, t + 1)) {
						node.forbidTimeIndexedPricingOnlyArc(from, from, t);
						fixed++;
					}
				}
			}
			return fixed;
		}

		private boolean hasProcessingOutgoingAtOrAfter(int from, int startTime) {
			for (int t = startTime; t <= horizon; t++) {
				for (int to = 1; to <= n; to++) {
					if (to == from || processArcForbidden[from][to] || isTimeIndexedArcForbidden(from, to, t)) {
						continue;
					}
					int completion = t + durationByArc[from][to];
					if (completion <= horizon && isCompletionFeasible(to, completion)) {
						return true;
					}
				}
			}
			return false;
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
					processArcForbidden[from][to] = from == to || isProcessArcForbiddenByNode(from, to);
				}
			}
			for (int job = 1; job <= n; job++) {
				endForbidden[job] = isEndArcForbiddenByNode(job);
			}
		}

		private boolean isCompletionFeasible(int job, int completion) {
			return completion >= 0 && completion <= horizon && isFinite(penaltyByJobTime[job][completion]);
		}

		private double processArcReducedCost(int from, int to, int completion) {
			double penalty = penaltyByJobTime[to][completion];
			if (!isFinite(penalty)) {
				return INF;
			}
			double cost = data.getSetupCost(from, to) + penalty - lp.getJobDual(to) - lp.getArcDual(from, to);
			if (from == 0) {
				cost -= lp.getMachineDual();
			}
			return cost;
		}

		private double sinkArcReducedCost(int lastJob) {
			return -lp.getArcDual(lastJob, sink);
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
		private final long totalNanos;
		private final String message;

		private ArcFixingResult(boolean available, int candidates, int fixed, int processFixed, int idleFixed,
				int endFixed, int cleanupFixed, int unavailable, double gap, long totalNanos, String message) {
			this.available = available;
			this.candidates = candidates;
			this.fixed = fixed;
			this.processFixed = processFixed;
			this.idleFixed = idleFixed;
			this.endFixed = endFixed;
			this.cleanupFixed = cleanupFixed;
			this.unavailable = unavailable;
			this.gap = gap;
			this.totalNanos = totalNanos;
			this.message = message;
		}

		static ArcFixingResult skipped(String message) {
			return new ArcFixingResult(false, 0, 0, 0, 0, 0, 0, 0, Double.NaN, 0L, message);
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

		public long getTotalNanos() {
			return totalNanos;
		}

		public String summary() {
			return message + ", candidates=" + candidates + ", fixed=" + fixed + ", unavailable=" + unavailable
					+ ", processFixed=" + processFixed + ", idleFixed=" + idleFixed + ", endFixed=" + endFixed
					+ ", cleanupFixed=" + cleanupFixed + ", gap=" + gap + ", ms="
					+ String.format("%.3f", totalNanos / 1_000_000.0);
		}
	}

	private static boolean isFinite(double value) {
		return value < INF * 0.5;
	}

	private Comparator<Candidate> worstCandidateFirstComparator() {
		return new Comparator<Candidate>() {
			@Override
			public int compare(Candidate a, Candidate b) {
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return 1;
				}
				return Integer.compare(b.id, a.id);
			}
		};
	}

	private Comparator<Candidate> bestCandidateFirstComparator() {
		return new Comparator<Candidate>() {
			@Override
			public int compare(Candidate a, Candidate b) {
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return 1;
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
