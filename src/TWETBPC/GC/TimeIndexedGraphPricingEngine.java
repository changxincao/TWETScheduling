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

	private final class TimeIndexedGraphSolver {
		private final LP lp;
		private final Node node;
		private final int n;
		private final int sink;
		private final int horizon;
		private final int width;
		private final double[] suffix;
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
		private int processArcPrunedBySuffix;
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
			this.suffix = new double[stateCount];
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
			computeSuffixDistances();
			bestPseudoReducedCost = suffix[index(0, 0)];
			if (!isFinite(bestPseudoReducedCost) || Utility.compareGe(bestPseudoReducedCost, -RC_TOLERANCE)) {
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
					+ ", suffixPruned=" + processArcPrunedBySuffix
					+ ", negativeStates=" + negativeStateCandidates
					+ ", repeatedJobCandidates=" + duplicateJobCandidates;
		}

		private void computeSuffixDistances() {
			for (int i = 0; i < suffix.length; i++) {
				suffix[i] = INF;
			}
			for (int t = 0; t <= horizon; t++) {
				for (int lastJob = 1; lastJob <= n; lastJob++) {
					if (isEndAllowed(lastJob)) {
						suffix[index(lastJob, t)] = sinkArcReducedCost(lastJob);
					}
				}
			}
			for (int t = horizon - 1; t >= 0; t--) {
				for (int lastJob = 0; lastJob <= n; lastJob++) {
					int state = index(lastJob, t);
					suffix[state] = Math.min(suffix[state], suffix[index(lastJob, t + 1)]);
					for (int nextJob = 1; nextJob <= n; nextJob++) {
						if (nextJob == lastJob || processArcForbidden[lastJob][nextJob]) {
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
						double targetSuffix = suffix[index(nextJob, completion)];
						if (isFinite(targetSuffix)) {
							suffix[state] = Math.min(suffix[state], arcCost + targetSuffix);
						}
					}
				}
			}
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
					rememberEndCandidateIfNegative(lastJob, state, base);
					if (t < horizon) {
						int waitTarget = index(lastJob, t + 1);
						if (isFinite(suffix[waitTarget])
								&& Utility.compareLt(base + suffix[waitTarget], -RC_TOLERANCE)) {
							relax(state, waitTarget, 0.0, 0);
						}
					}
					for (int nextJob = 1; nextJob <= n; nextJob++) {
						if (nextJob == lastJob || processArcForbidden[lastJob][nextJob]) {
							continue;
						}
						processArcScans++;
						int completion = completionTime(lastJob, nextJob, t);
						if (completion > horizon || !isCompletionFeasible(nextJob, completion)) {
							continue;
						}
						double arcCost = processArcReducedCost(lastJob, nextJob, completion);
						if (!isFinite(arcCost)) {
							continue;
						}
						int target = index(nextJob, completion);
						double suffixCost = suffix[target];
						if (!isFinite(suffixCost) || Utility.compareGe(base + arcCost + suffixCost, -RC_TOLERANCE)) {
							processArcPrunedBySuffix++;
							continue;
						}
						relax(state, target, arcCost, nextJob);
					}
				}
			}
		}

		private void rememberEndCandidateIfNegative(int lastJob, int state, double baseReducedCost) {
			if (lastJob == 0 || !isEndAllowed(lastJob)) {
				return;
			}
			double reducedCost = baseReducedCost + sinkArcReducedCost(lastJob);
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

		private boolean isEndAllowed(int lastJob) {
			return lastJob > 0 && !endForbidden[lastJob];
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
