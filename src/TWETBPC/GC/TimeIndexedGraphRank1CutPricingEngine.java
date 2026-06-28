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
import TWETBPC.CUT.SubsetRowCutEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Util.SequenceSignature;

/**
 * 2026-06-28: 论文式 time-indexed rank-1 cut 定价器。
 * <p>
 * 无 active rank-1 cut 时仍委托原 no-cut DAG shortest path engine；有 active cut 时按论文
 * Algorithm 4-6 使用 forward/backward bucket labels，并在拼接时修正两侧 residual state。
 */
public class TimeIndexedGraphRank1CutPricingEngine implements PricingEngine {

	private static final double INF = 1e100;
	private static final double RC_TOLERANCE = 1e-6;

	private final Data data;
	private final TWETBPCConfig config;
	private final TimeIndexedGraphPricingEngine noCutDelegate;
	private TimeLimitChecker timeLimitChecker = TimeLimitChecker.NONE;

	public TimeIndexedGraphRank1CutPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.noCutDelegate = new TimeIndexedGraphPricingEngine(data, config);
	}

	@Override
	public PricingResult price(LP lp) {
		return price(lp, TimeLimitChecker.NONE);
	}

	@Override
	public PricingResult price(LP lp, TimeLimitChecker checker) {
		this.timeLimitChecker = checker == null ? TimeLimitChecker.NONE : checker;
		if (!config.useTimeIndexedGraphPricing || !config.useTimeIndexedGraphRank1CutPricing) {
			return PricingResult.noImprovement("Time-indexed rank-1 cut pricing disabled");
		}
		if (lp.getActiveSubsetRowPricingCutIds().isEmpty()) {
			return noCutDelegate.price(lp, this.timeLimitChecker);
		}
		if (this.timeLimitChecker.isTimeLimitReached()) {
			return PricingResult.noImprovement("Time limit reached before time-indexed rank-1 cut pricing");
		}

		Rank1CutSolver solver = new Rank1CutSolver(lp, true);
		ArrayList<TWETColumn> columns = solver.solve();
		if (columns.isEmpty() && !solver.timedOut) {
			solver = new Rank1CutSolver(lp, false);
			columns = solver.solve();
		}
		PricingResult result = columns.isEmpty()
				? PricingResult.noImprovement(solver.message(false))
				: new PricingResult(columns, true, solver.message(true));
		if (!solver.heuristicMode && !solver.timedOut) {
			result = result.withCertifiedInternalReducedCost(solver.bestPseudoReducedCost);
		}
		return result;
	}

	@Override
	public String getName() {
		return "TimeIndexedGraphRank1CutPricing";
	}

	private final class Rank1CutSolver {
		private final LP lp;
		private final Node node;
		private final int n;
		private final int sink;
		private final GraphWindow graphWindow;
		private final int horizon;
		private final int width;
		private final int tStar;
		private final boolean heuristicMode;
		private final double[][] penaltyByJobTime;
		private final int[][] durationByArc;
		private final boolean[][] processArcForbidden;
		private final boolean[] endForbidden;
		private final CutStateData cutStateData;
		private final ArrayList<Label>[] forwardBuckets;
		private final ArrayList<Label>[] backwardBuckets;
		private final HashMap<SequenceSignature, Candidate> candidateBySignature;
		private final PriorityQueue<Candidate> candidateHeap;
		private int nextLabelId;
		private int nextCandidateId;
		private int labelsKept;
		private int labelsDominated;
		private int relaxedLabels;
		private int processArcScans;
		private int timeIndexedArcSkips;
		private int negativeStateCandidates;
		private int repeatedJobCandidates;
		private boolean timedOut;
		private double bestPseudoReducedCost;

		@SuppressWarnings("unchecked")
		Rank1CutSolver(LP lp, boolean heuristicMode) {
			this.lp = lp;
			this.node = lp.getNode();
			this.n = data.n;
			this.sink = node == null ? data.n + 1 : node.sinkId();
			this.graphWindow = computeGraphWindow(data, lp);
			this.horizon = graphWindow.horizon;
			this.width = horizon + 1;
			this.tStar = computeTStar(graphWindow);
			this.heuristicMode = heuristicMode;
			this.penaltyByJobTime = new double[n + 1][width];
			this.durationByArc = new int[n + 1][n + 1];
			this.processArcForbidden = new boolean[n + 1][n + 1];
			this.endForbidden = new boolean[n + 1];
			this.cutStateData = new CutStateData(lp);
			this.forwardBuckets = new ArrayList[(n + 1) * width];
			this.backwardBuckets = new ArrayList[(n + 1) * width];
			this.candidateBySignature = new HashMap<SequenceSignature, Candidate>();
			this.candidateHeap = new PriorityQueue<Candidate>(Math.max(1, maxReturnedColumns()),
					worstCandidateFirstComparator());
			this.bestPseudoReducedCost = INF;
			precomputeStaticPricingData();
		}

		ArrayList<TWETColumn> solve() {
			if (maxReturnedColumns() <= 0) {
				return new ArrayList<TWETColumn>();
			}
			initializeForward();
			runForwardLabeling();
			if (!timedOut) {
				initializeBackward();
				runBackwardLabeling();
			}
			if (!timedOut) {
				concatenateLabels();
			}
			ArrayList<Candidate> candidates = new ArrayList<Candidate>(candidateBySignature.values());
			Collections.sort(candidates, bestCandidateFirstComparator());
			ArrayList<TWETColumn> columns = new ArrayList<TWETColumn>();
			for (int i = 0; i < candidates.size() && columns.size() < maxReturnedColumns(); i++) {
				columns.add(candidates.get(i).column);
			}
			return columns;
		}

		private void initializeForward() {
			Label source = Label.forward(nextLabelId++, 0, 0, 0.0, cutStateData.emptyResidual(), null, 0);
			insertLabel(forwardBuckets, source);
		}

		private void initializeBackward() {
			for (int job = 1; job <= n; job++) {
				for (int t = tStar; t <= horizon; t++) {
					if (isCompletionFeasible(job, t) && isEndAllowed(job, t)) {
						Label label = Label.backward(nextLabelId++, job, t, sinkArcReducedCost(job),
								cutStateData.emptyResidual(), null, job);
						insertLabel(backwardBuckets, label);
					}
				}
			}
		}

		private void runForwardLabeling() {
			for (int t = 0; t < tStar; t++) {
				if (timeLimitChecker.isTimeLimitReached()) {
					timedOut = true;
					return;
				}
				for (int lastJob = 0; lastJob <= n; lastJob++) {
					ArrayList<Label> bucket = forwardBuckets[index(lastJob, t)];
					if (bucket == null || bucket.isEmpty()) {
						continue;
					}
					for (int labelIndex = 0; labelIndex < bucket.size(); labelIndex++) {
						Label label = bucket.get(labelIndex);
						relaxedLabels++;
						rememberEndCandidateIfNegative(label);
						if (t < horizon && !isTimeIndexedArcForbidden(lastJob, lastJob, t)) {
							insertLabel(forwardBuckets, label.extend(nextLabelId++, lastJob, t + 1, 0.0,
									cutStateData.copyResidual(label.residual), 0));
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
							int completion = t + durationByArc[lastJob][nextJob];
							if (completion > horizon || !isCompletionFeasible(nextJob, completion)) {
								continue;
							}
							double arcCost = processArcReducedCost(lastJob, nextJob, completion);
							if (!isFinite(arcCost)) {
								continue;
							}
							byte[] residual = cutStateData.copyResidual(label.residual);
							arcCost += cutStateData.applyForwardExtension(residual, lastJob, nextJob);
							insertLabel(forwardBuckets, label.extend(nextLabelId++, nextJob, completion, arcCost,
									residual, nextJob));
						}
					}
				}
			}
			for (int t = tStar; t <= horizon; t++) {
				for (int lastJob = 1; lastJob <= n; lastJob++) {
					ArrayList<Label> bucket = forwardBuckets[index(lastJob, t)];
					if (bucket == null) {
						continue;
					}
					for (Label label : bucket) {
						rememberEndCandidateIfNegative(label);
					}
				}
			}
		}

		private void runBackwardLabeling() {
			for (int t = horizon; t > tStar; t--) {
				if (timeLimitChecker.isTimeLimitReached()) {
					timedOut = true;
					return;
				}
				for (int currentJob = 1; currentJob <= n; currentJob++) {
					ArrayList<Label> bucket = backwardBuckets[index(currentJob, t)];
					if (bucket == null || bucket.isEmpty()) {
						continue;
					}
					for (int labelIndex = 0; labelIndex < bucket.size(); labelIndex++) {
						Label label = bucket.get(labelIndex);
						relaxedLabels++;
						if (t > tStar && !isTimeIndexedArcForbidden(currentJob, currentJob, t - 1)) {
							insertLabel(backwardBuckets, label.extend(nextLabelId++, currentJob, t - 1, 0.0,
									cutStateData.copyResidual(label.residual), 0));
						}
						for (int previousJob = 0; previousJob <= n; previousJob++) {
							if (previousJob == currentJob || processArcForbidden[previousJob][currentJob]) {
								continue;
							}
							processArcScans++;
							int previousTime = t - durationByArc[previousJob][currentJob];
							if (previousTime < 0) {
								continue;
							}
							if (isTimeIndexedArcForbidden(previousJob, currentJob, previousTime)) {
								timeIndexedArcSkips++;
								continue;
							}
							double arcCost = processArcReducedCost(previousJob, currentJob, t);
							if (!isFinite(arcCost)) {
								continue;
							}
							byte[] residual = cutStateData.copyResidual(label.residual);
							arcCost += cutStateData.applyBackwardPrepend(residual, previousJob, currentJob);
							if (previousJob == 0) {
								rememberBackwardCompleteCandidate(label, arcCost);
							} else if (previousTime >= tStar && isCompletionFeasible(previousJob, previousTime)) {
								insertLabel(backwardBuckets, label.extend(nextLabelId++, previousJob, previousTime,
										arcCost, residual, previousJob));
							}
						}
					}
				}
			}
		}

		private void concatenateLabels() {
			for (int t = tStar; t <= horizon; t++) {
				for (int job = 1; job <= n; job++) {
					ArrayList<Label> fw = forwardBuckets[index(job, t)];
					ArrayList<Label> bw = backwardBuckets[index(job, t)];
					if (fw == null || bw == null || fw.isEmpty() || bw.isEmpty()) {
						continue;
					}
					for (Label left : fw) {
						for (Label right : bw) {
							double reducedCost = left.reducedCost + right.reducedCost
									+ cutStateData.joinShift(left.residual, right.residual);
							if (Utility.compareLt(reducedCost, bestPseudoReducedCost)) {
								bestPseudoReducedCost = reducedCost;
							}
							if (Utility.compareGe(reducedCost, -RC_TOLERANCE) || !isPotentialTopCandidate(reducedCost)) {
								continue;
							}
							ArrayList<Integer> sequence = reconstructForwardSequence(left);
							ArrayList<Integer> suffix = reconstructBackwardSequence(right);
							for (int i = 1; i < suffix.size(); i++) {
								sequence.add(suffix.get(i));
							}
							rememberSequenceCandidate(sequence, reducedCost);
						}
					}
				}
			}
		}

		private boolean insertLabel(ArrayList<Label>[] buckets, Label label) {
			int idx = index(label.lastJob, label.time);
			ArrayList<Label> bucket = buckets[idx];
			if (bucket == null) {
				bucket = new ArrayList<Label>();
				buckets[idx] = bucket;
			}
			if (heuristicMode && !bucket.isEmpty()) {
				Label existing = bucket.get(0);
				if (Utility.compareLe(existing.reducedCost, label.reducedCost)) {
					labelsDominated++;
					return false;
				}
				bucket.clear();
				bucket.add(label);
				labelsDominated++;
				labelsKept++;
				return true;
			}
			for (int i = 0; i < bucket.size(); i++) {
				Label existing = bucket.get(i);
				if (cutStateData.dominates(existing, label)) {
					labelsDominated++;
					return false;
				}
			}
			for (int i = bucket.size() - 1; i >= 0; i--) {
				if (cutStateData.dominates(label, bucket.get(i))) {
					bucket.remove(i);
					labelsDominated++;
				}
			}
			bucket.add(label);
			labelsKept++;
			return true;
		}

		private void rememberEndCandidateIfNegative(Label label) {
			if (!isEndAllowed(label.lastJob, label.time)) {
				return;
			}
			double reducedCost = label.reducedCost + sinkArcReducedCost(label.lastJob);
			if (Utility.compareLt(reducedCost, bestPseudoReducedCost)) {
				bestPseudoReducedCost = reducedCost;
			}
			if (Utility.compareGe(reducedCost, -RC_TOLERANCE) || !isPotentialTopCandidate(reducedCost)) {
				return;
			}
			rememberSequenceCandidate(reconstructForwardSequence(label), reducedCost);
		}

		private void rememberBackwardCompleteCandidate(Label suffixLabel, double sourceArcCost) {
			double reducedCost = suffixLabel.reducedCost + sourceArcCost;
			if (Utility.compareLt(reducedCost, bestPseudoReducedCost)) {
				bestPseudoReducedCost = reducedCost;
			}
			if (Utility.compareGe(reducedCost, -RC_TOLERANCE) || !isPotentialTopCandidate(reducedCost)) {
				return;
			}
			rememberSequenceCandidate(reconstructBackwardSequence(suffixLabel), reducedCost);
		}

		private void rememberSequenceCandidate(ArrayList<Integer> sequence, double reducedCost) {
			if (sequence.isEmpty()) {
				return;
			}
			negativeStateCandidates++;
			if (hasRepeatedJob(sequence)) {
				repeatedJobCandidates++;
			}
			SequenceSignature signature = new SequenceSignature(sequence);
			double cost = objectiveCostFromReducedCost(sequence, reducedCost);
			rememberCandidate(signature, new TWETColumn(-1, sequence, n, cost, ColumnSource.PRICING_EXACT, false),
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
			for (int idx = 0; idx < cutStateData.cuts.size(); idx++) {
				int coefficient = SubsetRowCutEvaluator.coefficient(cutStateData.cuts.get(idx), sequence, n);
				if (coefficient > 0) {
					cost += coefficient * cutStateData.duals[idx];
				}
			}
			return cost;
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
				}
			}
			for (int job = 1; job <= n; job++) {
				endForbidden[job] = isEndArcForbiddenByNode(job);
			}
		}

		private boolean isCompletionFeasible(int job, int completion) {
			return completion >= 0 && completion <= horizon && isFinite(penaltyByJobTime[job][completion]);
		}

		private boolean isEndAllowed(int lastJob, int time) {
			return lastJob > 0 && !endForbidden[lastJob] && !isTimeIndexedArcForbidden(lastJob, 0, time);
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

		private ArrayList<Integer> reconstructForwardSequence(Label label) {
			ArrayList<Integer> reversed = new ArrayList<Integer>();
			Label current = label;
			while (current != null) {
				if (current.addedJob > 0) {
					reversed.add(Integer.valueOf(current.addedJob));
				}
				current = current.previous;
			}
			Collections.reverse(reversed);
			return reversed;
		}

		private ArrayList<Integer> reconstructBackwardSequence(Label label) {
			ArrayList<Integer> sequence = new ArrayList<Integer>();
			Label current = label;
			while (current != null) {
				if (current.addedJob > 0) {
					sequence.add(Integer.valueOf(current.addedJob));
				}
				current = current.previous;
			}
			return sequence;
		}

		private boolean hasRepeatedJob(ArrayList<Integer> sequence) {
			boolean[] seen = new boolean[n + 1];
			for (int job : sequence) {
				if (job >= 1 && job <= n) {
					if (seen[job]) {
						return true;
					}
					seen[job] = true;
				}
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
			while (candidateBySignature.size() > maxReturnedColumns()) {
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

		private int maxReturnedColumns() {
			int exactLimit = config.timeIndexedGraphMaxExactPricingColumns > 0 ? config.timeIndexedGraphMaxExactPricingColumns
					: config.maxExactPricingColumns;
			return heuristicMode ? Math.min(50, exactLimit) : exactLimit;
		}

		String message(boolean improved) {
			return "Time-indexed rank-1 cut " + (heuristicMode ? "heuristic" : "exact")
					+ " bidirectional pricing " + (improved ? "generated " + candidateBySignature.size()
					+ " negative pseudo-schedule columns" : "found no negative pseudo-schedule")
					+ ", bestPseudoRC=" + bestPseudoReducedCost
					+ ", cuts=" + cutStateData.cuts.size()
					+ ", horizon=" + horizon
					+ ", tStar=" + tStar
					+ ", piWindow=" + (graphWindow.dualWindow ? "enabled" : "disabled")
					+ ", labelsKept=" + labelsKept
					+ ", labelsDominated=" + labelsDominated
					+ ", relaxedLabels=" + relaxedLabels
					+ ", arcScans=" + processArcScans
					+ ", timeArcSkips=" + timeIndexedArcSkips
					+ ", negativeStates=" + negativeStateCandidates
					+ ", repeatedJobCandidates=" + repeatedJobCandidates
					+ (timedOut ? ", timeLimit=true" : "");
		}

		private int index(int job, int time) {
			return job * width + time;
		}
	}

	private final class CutStateData {
		private final ArrayList<TWETCut> cuts;
		private final double[] duals;
		private final boolean[][] scopeByCut;
		private final boolean[][] memoryByCut;
		private final boolean[][] arcMemoryByCut;
		private final boolean[] arcMemoryCut;
		private final int arcTableSize;

		CutStateData(LP lp) {
			List<Integer> cutIds = lp.getActiveSubsetRowPricingCutIds();
			List<Double> pricingDuals = lp.getActiveSubsetRowPricingDuals();
			this.cuts = new ArrayList<TWETCut>(cutIds.size());
			this.duals = new double[cutIds.size()];
			this.scopeByCut = new boolean[cutIds.size()][data.n + 1];
			this.memoryByCut = new boolean[cutIds.size()][data.n + 1];
			this.arcTableSize = (data.n + 2) * (data.n + 2);
			this.arcMemoryByCut = new boolean[cutIds.size()][arcTableSize];
			this.arcMemoryCut = new boolean[cutIds.size()];
			for (int idx = 0; idx < cutIds.size(); idx++) {
				TWETCut cut = lp.getCutPool().getCut(cutIds.get(idx).intValue());
				cuts.add(cut);
				duals[idx] = pricingDuals.get(idx).doubleValue();
				for (int job : cut.getScopeJobs()) {
					if (job >= 1 && job <= data.n) {
						scopeByCut[idx][job] = true;
					}
				}
				if (cut.hasMemoryArcs()) {
					arcMemoryCut[idx] = true;
					for (Long encoded : cut.getMemoryArcs()) {
						long key = encoded.longValue();
						int from = (int) (key >> 32);
						int to = (int) key;
						if (from >= 0 && from <= data.n + 1 && to >= 0 && to <= data.n + 1) {
							arcMemoryByCut[idx][arcIndex(from, to)] = true;
						}
					}
				} else if (cut.hasMemoryJobs()) {
					for (int job : cut.getMemoryJobs()) {
						if (job >= 1 && job <= data.n) {
							memoryByCut[idx][job] = true;
						}
					}
				} else {
					for (int job = 1; job <= data.n; job++) {
						memoryByCut[idx][job] = true;
					}
				}
			}
		}

		byte[] emptyResidual() {
			return new byte[cuts.size()];
		}

		byte[] copyResidual(byte[] source) {
			return source.length == 0 ? source : source.clone();
		}

		double applyForwardExtension(byte[] residual, int from, int job) {
			double shift = 0.0;
			for (int idx = 0; idx < cuts.size(); idx++) {
				if (arcMemoryCut[idx]) {
					if (!arcMemoryByCut[idx][arcIndex(from, job)]) {
						residual[idx] = 0;
					}
				} else if (!memoryByCut[idx][job]) {
					residual[idx] = 0;
					continue;
				}
				if (!scopeByCut[idx][job]) {
					continue;
				}
				int next = residual[idx] + 1;
				if (next >= 2) {
					shift -= duals[idx];
					next -= 2;
				}
				residual[idx] = (byte) next;
			}
			return shift;
		}

		double applyBackwardPrepend(byte[] residual, int previousJob, int currentJob) {
			double shift = 0.0;
			if (currentJob <= 0 || currentJob > data.n) {
				return shift;
			}
			for (int idx = 0; idx < cuts.size(); idx++) {
				if (scopeByCut[idx][currentJob]) {
					int next = residual[idx] + 1;
					if (next >= 2) {
						shift -= duals[idx];
						next -= 2;
					}
					residual[idx] = (byte) next;
				}
				if (arcMemoryCut[idx]) {
					if (!arcMemoryByCut[idx][arcIndex(previousJob, currentJob)]) {
						residual[idx] = 0;
					}
				} else if (previousJob > 0 && !memoryByCut[idx][currentJob]) {
					residual[idx] = 0;
				}
			}
			return shift;
		}

		double joinShift(byte[] forwardResidual, byte[] backwardResidual) {
			double shift = 0.0;
			for (int idx = 0; idx < cuts.size(); idx++) {
				if (forwardResidual[idx] + backwardResidual[idx] >= 2) {
					shift -= duals[idx];
				}
			}
			return shift;
		}

		boolean dominates(Label first, Label second) {
			double bound = second.reducedCost;
			for (int idx = 0; idx < duals.length; idx++) {
				if (first.residual[idx] > second.residual[idx]) {
					bound += duals[idx];
				}
			}
			return Utility.compareLe(first.reducedCost, bound + RC_TOLERANCE);
		}

		private int arcIndex(int from, int to) {
			return from * (data.n + 2) + to;
		}
	}

	private static final class Label {
		final int id;
		final int lastJob;
		final int time;
		final double reducedCost;
		final byte[] residual;
		final Label previous;
		final int addedJob;

		static Label forward(int id, int lastJob, int time, double reducedCost, byte[] residual, Label previous,
				int addedJob) {
			return new Label(id, lastJob, time, reducedCost, residual, previous, addedJob);
		}

		static Label backward(int id, int lastJob, int time, double reducedCost, byte[] residual, Label previous,
				int addedJob) {
			return new Label(id, lastJob, time, reducedCost, residual, previous, addedJob);
		}

		Label(int id, int lastJob, int time, double reducedCost, byte[] residual, Label previous, int addedJob) {
			this.id = id;
			this.lastJob = lastJob;
			this.time = time;
			this.reducedCost = reducedCost;
			this.residual = residual;
			this.previous = previous;
			this.addedJob = addedJob;
		}

		Label extend(int id, int newLastJob, int newTime, double arcCost, byte[] newResidual, int newAddedJob) {
			return new Label(id, newLastJob, newTime, reducedCost + arcCost, newResidual, this, newAddedJob);
		}
	}

	private static GraphWindow computeGraphWindow(Data data, LP lp) {
		double[] start = new double[data.n + 1];
		double[] end = new double[data.n + 1];
		start[0] = 0.0;
		end[0] = data.CmaxH;
		boolean dualWindow = canUseDualProfitableWindow(lp);
		double horizon = 0.0;
		boolean hasFeasibleJob = false;
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
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

	private static int computeTStar(GraphWindow window) {
		double sum = 0.0;
		int count = 0;
		for (int job = 1; job < window.start.length; job++) {
			if (!Utility.compareGt(window.start[job], window.end[job])) {
				sum += Math.ceil(window.start[job] - 1e-9);
				sum += Math.floor(window.end[job] + 1e-9);
				count++;
			}
		}
		if (count == 0) {
			return Math.max(0, window.horizon / 2);
		}
		int value = (int) Math.round(sum / (2.0 * count));
		return Math.max(0, Math.min(window.horizon, value));
	}

	private static boolean canUseDualProfitableWindow(LP lp) {
		if (lp == null || lp.getNode() == null || lp.getNode().depth != 0) {
			return false;
		}
		return lp.getActiveCutIds().isEmpty();
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
