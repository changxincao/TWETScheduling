package TWETBPC.GC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.Segment;
import Common.Utility;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;

/**
 * 双向 pricing 共用的 relaxed completion bound 计算器。
 * <p>
 * 这里只计算按 job 聚合的前缀补全函数 U_i 和后缀补全函数 R_i，不生成列，也不依赖
 * elementary label 的 visited/reachable 状态。默认 FIFO 队列是当前实测更稳的顺序；
 * reducedCost 顺序仅保留为对照开关，wet030 上会显著增加 merge 次数。
 */
final class CompletionBoundCalculator {
	enum Relaxation {
		ALL_CYCLES,
		TWO_CYCLE
	}

	enum QueueOrdering {
		FIFO,
		REDUCED_COST
	}

	static final class Bounds {
		final PiecewiseLinearFunction[] forwardUByJob;
		final PiecewiseLinearFunction[] backwardRByJob;

		Bounds(int n) {
			this.forwardUByJob = new PiecewiseLinearFunction[n + 1];
			this.backwardRByJob = new PiecewiseLinearFunction[n + 1];
		}
	}

	static final class Stats {
		long forwardBuildNanos;
		long backwardBuildNanos;
		long aggregateNanos;
		long forwardCandidateAttempts;
		long backwardCandidateAttempts;
		long forwardQueuePops;
		long backwardQueuePops;
		long mergeCalls;
		long mergeChanged;
	}

	static final class Result {
		final Bounds bounds;
		final Stats stats;

		Result(Bounds bounds, Stats stats) {
			this.bounds = bounds;
			this.stats = stats;
		}
	}

	private final Data data;
	private final LP lp;
	private final Node node;
	private final int sink;
	private final double pricingHorizon;
	private final PiecewiseLinearFunction[] forwardReducedPenaltyByJob;
	private final PiecewiseLinearFunction[] backwardReducedPenaltyByJob;
	private final boolean[] zeroDualExcludedJobs;
	private final QueueOrdering queueOrdering;
	private final int[][] forwardSuccessorsByJob;
	private final int[][] backwardPredecessorsByJob;
	private final Stats stats = new Stats();

	CompletionBoundCalculator(Data data, LP lp, double pricingHorizon,
			PiecewiseLinearFunction[] forwardPenaltyByJob, PiecewiseLinearFunction[] backwardPenaltyByJob,
			boolean[] zeroDualExcludedJobs, QueueOrdering queueOrdering) {
		this.data = data;
		this.lp = lp;
		this.node = lp.getNode();
		this.sink = node.sinkId();
		this.pricingHorizon = pricingHorizon;
		this.forwardReducedPenaltyByJob = buildReducedPenaltyCache(forwardPenaltyByJob, null, null);
		this.backwardReducedPenaltyByJob = buildReducedPenaltyCache(backwardPenaltyByJob, forwardPenaltyByJob,
				forwardReducedPenaltyByJob);
		this.zeroDualExcludedJobs = zeroDualExcludedJobs;
		this.queueOrdering = queueOrdering == null ? QueueOrdering.FIFO : queueOrdering;
		this.forwardSuccessorsByJob = buildForwardSuccessorLists();
		this.backwardPredecessorsByJob = buildBackwardPredecessorLists();
	}

	Result build(Relaxation relaxation) {
		Bounds bounds = relaxation == Relaxation.TWO_CYCLE ? buildTwoCycle() : buildAllCycles();
		return new Result(bounds, stats);
	}

	private PiecewiseLinearFunction[] buildReducedPenaltyCache(PiecewiseLinearFunction[] penaltyByJob,
			PiecewiseLinearFunction[] reusablePenaltyByJob, PiecewiseLinearFunction[] reusableReducedByJob) {
		if (penaltyByJob == null) {
			return null;
		}
		PiecewiseLinearFunction[] reducedByJob = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			PiecewiseLinearFunction penalty = job < penaltyByJob.length ? penaltyByJob[job] : null;
			if (penalty == null) {
				continue;
			}
			// 2026-06-01: Tmid completion bound 会把同一个 full-domain penalty 同时作为
			// forward/backward 输入。reduced penalty 只依赖 job dual，add() 不改写右参数，
			// 因此同一个 penalty 对象可以复用同一个 reduced 缓存，避免初始化阶段复制两份。
			if (reusablePenaltyByJob != null && reusableReducedByJob != null
					&& job < reusablePenaltyByJob.length && reusablePenaltyByJob[job] == penalty) {
				reducedByJob[job] = reusableReducedByJob[job];
				continue;
			}
			PiecewiseLinearFunction reduced = penalty.copy();
			reduced.shiftYInPlace(-lp.getJobDual(job));
			reducedByJob[job] = reduced;
		}
		return reducedByJob;
	}

	private int[][] buildForwardSuccessorLists() {
		int[][] successorsByJob = new int[data.n + 1][];
		for (int prev = 0; prev <= data.n; prev++) {
			ArrayList<Integer> successors = new ArrayList<Integer>();
			for (int job = 1; job <= data.n; job++) {
				if (job != prev && isCompletionJobAvailable(job) && !node.isArcForbidden(prev, job)) {
					successors.add(Integer.valueOf(job));
				}
			}
			successorsByJob[prev] = toIntArray(successors);
		}
		return successorsByJob;
	}

	private int[][] buildBackwardPredecessorLists() {
		int[][] predecessorsByJob = new int[data.n + 2][];
		for (int successor = 1; successor <= data.n + 1; successor++) {
			ArrayList<Integer> predecessors = new ArrayList<Integer>();
			for (int prev = 1; prev <= data.n; prev++) {
				if (prev != successor && isCompletionJobAvailable(prev) && !node.isArcForbidden(prev, successor)) {
					predecessors.add(Integer.valueOf(prev));
				}
			}
			predecessorsByJob[successor] = toIntArray(predecessors);
		}
		return predecessorsByJob;
	}

	private int[] toIntArray(ArrayList<Integer> values) {
		int[] array = new int[values.size()];
		for (int i = 0; i < values.size(); i++) {
			array[i] = values.get(i).intValue();
		}
		return array;
	}

	private StateQueue stateQueue() {
		return new StateQueue();
	}

	private QueueState state(QueueState[] states, int first) {
		QueueState state = states[first];
		if (state == null) {
			state = new QueueState(first, 0);
			states[first] = state;
		}
		return state;
	}

	private QueueState state(QueueState[][] states, int first, int second) {
		QueueState state = states[first][second];
		if (state == null) {
			state = new QueueState(first, second);
			states[first][second] = state;
		}
		return state;
	}

	private Bounds buildAllCycles() {
		Bounds bounds = new Bounds(data.n);
		PiecewiseLinearFunction[] forwardF = new PiecewiseLinearFunction[data.n + 1];
		PiecewiseLinearFunction[] backwardB = new PiecewiseLinearFunction[data.n + 1];
		StateQueue forwardQueue = stateQueue();
		StateQueue backwardQueue = stateQueue();
		QueueState[] forwardStates = new QueueState[data.n + 1];
		QueueState[] backwardStates = new QueueState[data.n + 1];

		long phaseStart = System.nanoTime();
		PiecewiseLinearFunction source = sourcePropagationFunction();
		for (int idx = 0; idx < forwardSuccessorsByJob[0].length; idx++) {
			int job = forwardSuccessorsByJob[0][idx];
			FunctionPair candidate = buildForwardCandidate(source, 0, job);
			if (candidate == null) {
				continue;
			}
			mergeForward(bounds.forwardUByJob, job, candidate.u);
			if (mergeForward(forwardF, job, candidate.f)) {
				forwardQueue.enqueue(state(forwardStates, job), forwardF[job]);
			}
		}
		while (!forwardQueue.isEmpty()) {
			stats.forwardQueuePops++;
			int prev = forwardQueue.poll().first;
			PiecewiseLinearFunction prevF = forwardF[prev];
			if (prevF == null || prevF.head == null) {
				continue;
			}
			int[] successors = forwardSuccessorsByJob[prev];
			for (int idx = 0; idx < successors.length; idx++) {
				int job = successors[idx];
				FunctionPair candidate = buildForwardCandidate(prevF, prev, job);
				if (candidate == null) {
					continue;
				}
				mergeForward(bounds.forwardUByJob, job, candidate.u);
				if (mergeForward(forwardF, job, candidate.f)) {
					forwardQueue.enqueue(state(forwardStates, job), forwardF[job]);
				}
			}
		}
		stats.forwardBuildNanos += System.nanoTime() - phaseStart;

		phaseStart = System.nanoTime();
		int[] sinkPredecessors = backwardPredecessorsByJob[sink];
		for (int idx = 0; idx < sinkPredecessors.length; idx++) {
			int job = sinkPredecessors[idx];
			FunctionPair candidate = buildBackwardSinkCandidate(job);
			if (candidate == null) {
				continue;
			}
			mergeBackward(bounds.backwardRByJob, job, candidate.u);
			if (mergeBackward(backwardB, job, candidate.f)) {
				backwardQueue.enqueue(state(backwardStates, job), backwardB[job]);
			}
		}
		while (!backwardQueue.isEmpty()) {
			stats.backwardQueuePops++;
			int successor = backwardQueue.poll().first;
			PiecewiseLinearFunction successorB = backwardB[successor];
			if (successorB == null || successorB.head == null) {
				continue;
			}
			int[] predecessors = backwardPredecessorsByJob[successor];
			for (int idx = 0; idx < predecessors.length; idx++) {
				int prev = predecessors[idx];
				FunctionPair candidate = buildBackwardCandidate(successorB, prev, successor);
				if (candidate == null) {
					continue;
				}
				mergeBackward(bounds.backwardRByJob, prev, candidate.u);
				if (mergeBackward(backwardB, prev, candidate.f)) {
					backwardQueue.enqueue(state(backwardStates, prev), backwardB[prev]);
				}
			}
		}
		stats.backwardBuildNanos += System.nanoTime() - phaseStart;
		return bounds;
	}

	private Bounds buildTwoCycle() {
		Bounds bounds = new Bounds(data.n);
		PiecewiseLinearFunction[][] forwardU = new PiecewiseLinearFunction[data.n + 1][data.n + 1];
		PiecewiseLinearFunction[][] forwardF = new PiecewiseLinearFunction[data.n + 1][data.n + 1];
		PiecewiseLinearFunction[][] backwardR = new PiecewiseLinearFunction[data.n + 1][data.n + 2];
		PiecewiseLinearFunction[][] backwardB = new PiecewiseLinearFunction[data.n + 1][data.n + 2];
		StateQueue forwardQueue = stateQueue();
		StateQueue backwardQueue = stateQueue();
		QueueState[][] forwardStates = new QueueState[data.n + 1][data.n + 1];
		QueueState[][] backwardStates = new QueueState[data.n + 1][data.n + 2];

		long phaseStart = System.nanoTime();
		PiecewiseLinearFunction source = sourcePropagationFunction();
		for (int idx = 0; idx < forwardSuccessorsByJob[0].length; idx++) {
			int job = forwardSuccessorsByJob[0][idx];
			FunctionPair candidate = buildForwardCandidate(source, 0, job);
			if (candidate == null) {
				continue;
			}
			mergeForward(forwardU[0], job, candidate.u);
			if (mergeForward(forwardF[0], job, candidate.f)) {
				forwardQueue.enqueue(state(forwardStates, 0, job), forwardF[0][job]);
			}
		}
		while (!forwardQueue.isEmpty()) {
			stats.forwardQueuePops++;
			QueueState state = forwardQueue.poll();
			int prevPrev = state.first;
			int prev = state.second;
			PiecewiseLinearFunction prevF = forwardF[prevPrev][prev];
			if (prevF == null || prevF.head == null) {
				continue;
			}
			int[] successors = forwardSuccessorsByJob[prev];
			for (int idx = 0; idx < successors.length; idx++) {
				int job = successors[idx];
				if (job == prevPrev) {
					continue;
				}
				FunctionPair candidate = buildForwardCandidate(prevF, prev, job);
				if (candidate == null) {
					continue;
				}
				mergeForward(forwardU[prev], job, candidate.u);
				if (mergeForward(forwardF[prev], job, candidate.f)) {
					forwardQueue.enqueue(state(forwardStates, prev, job), forwardF[prev][job]);
				}
			}
		}
		stats.forwardBuildNanos += System.nanoTime() - phaseStart;

		phaseStart = System.nanoTime();
		int[] sinkPredecessors = backwardPredecessorsByJob[sink];
		for (int idx = 0; idx < sinkPredecessors.length; idx++) {
			int job = sinkPredecessors[idx];
			FunctionPair candidate = buildBackwardSinkCandidate(job);
			if (candidate == null) {
				continue;
			}
			mergeBackward(backwardR[job], sink, candidate.u);
			if (mergeBackward(backwardB[job], sink, candidate.f)) {
				backwardQueue.enqueue(state(backwardStates, job, sink), backwardB[job][sink]);
			}
		}
		while (!backwardQueue.isEmpty()) {
			stats.backwardQueuePops++;
			QueueState state = backwardQueue.poll();
			int current = state.first;
			int successor = state.second;
			PiecewiseLinearFunction currentB = backwardB[current][successor];
			if (currentB == null || currentB.head == null) {
				continue;
			}
			int[] predecessors = backwardPredecessorsByJob[current];
			for (int idx = 0; idx < predecessors.length; idx++) {
				int prev = predecessors[idx];
				if (prev == successor) {
					continue;
				}
				FunctionPair candidate = buildBackwardCandidate(currentB, prev, current);
				if (candidate == null) {
					continue;
				}
				mergeBackward(backwardR[prev], current, candidate.u);
				if (mergeBackward(backwardB[prev], current, candidate.f)) {
					backwardQueue.enqueue(state(backwardStates, prev, current), backwardB[prev][current]);
				}
			}
		}
		stats.backwardBuildNanos += System.nanoTime() - phaseStart;

		phaseStart = System.nanoTime();
		for (int job = 1; job <= data.n; job++) {
			for (int prev = 0; prev <= data.n; prev++) {
				mergeForward(bounds.forwardUByJob, job, forwardU[prev][job]);
			}
			for (int successor = 1; successor <= data.n + 1; successor++) {
				mergeBackward(bounds.backwardRByJob, job, backwardR[job][successor]);
			}
		}
		stats.aggregateNanos += System.nanoTime() - phaseStart;
		return bounds;
	}

	private FunctionPair buildForwardCandidate(PiecewiseLinearFunction parentF, int prevJob, int job) {
		stats.forwardCandidateAttempts++;
		if (parentF == null || parentF.head == null) {
			return null;
		}
		double delay = data.getSetUp(prevJob, job) + data.getProcessT(job);
		PiecewiseLinearFunction u = parentF.shiftX(delay);
		if (!hasPositiveDomain(u)) {
			return null;
		}
		u.shiftYInPlace(data.getSetupCost(prevJob, job) - lp.getArcDual(prevJob, job));
		u.normalize(Direction.FORWARD);
		PiecewiseLinearFunction jobCost = forwardJobReducedPenalty(job);
		if (jobCost == null) {
			return null;
		}
		PiecewiseLinearFunction f = u.add(jobCost);
		if (!hasPositiveDomain(f)) {
			return null;
		}
		f.normalize(Direction.FORWARD);
		return hasPositiveDomain(f) ? new FunctionPair(u, f) : null;
	}

	private FunctionPair buildBackwardSinkCandidate(int job) {
		PiecewiseLinearFunction r = constantFunction(-lp.getArcDual(job, sink));
		PiecewiseLinearFunction jobCost = backwardJobReducedPenalty(job);
		if (jobCost == null) {
			return null;
		}
		PiecewiseLinearFunction b = r.add(jobCost);
		if (!hasPositiveDomain(b)) {
			return null;
		}
		b.normalize(Direction.BACKWARD);
		return hasPositiveDomain(b) ? new FunctionPair(r, b) : null;
	}

	private FunctionPair buildBackwardCandidate(PiecewiseLinearFunction successorB, int job, int successor) {
		stats.backwardCandidateAttempts++;
		if (successorB == null || successorB.head == null) {
			return null;
		}
		double delay = data.getSetUp(job, successor) + data.getProcessT(successor);
		PiecewiseLinearFunction r = successorB.shiftX(-delay);
		if (!hasPositiveDomain(r)) {
			return null;
		}
		r.shiftYInPlace(data.getSetupCost(job, successor) - lp.getArcDual(job, successor));
		r.normalize(Direction.BACKWARD);
		PiecewiseLinearFunction jobCost = backwardJobReducedPenalty(job);
		if (jobCost == null) {
			return null;
		}
		PiecewiseLinearFunction b = r.add(jobCost);
		if (!hasPositiveDomain(b)) {
			return null;
		}
		b.normalize(Direction.BACKWARD);
		return hasPositiveDomain(b) ? new FunctionPair(r, b) : null;
	}

	private PiecewiseLinearFunction sourcePropagationFunction() {
		PiecewiseLinearFunction source = cropToInterval(data.penaltyFunction[0].copy(), 0.0, pricingHorizon);
		source.shiftYInPlace(-lp.getMachineDual());
		source.normalize(Direction.FORWARD);
		return source;
	}

	private PiecewiseLinearFunction forwardJobReducedPenalty(int job) {
		return forwardReducedPenaltyByJob == null ? null : forwardReducedPenaltyByJob[job];
	}

	private PiecewiseLinearFunction backwardJobReducedPenalty(int job) {
		return backwardReducedPenaltyByJob == null ? null : backwardReducedPenaltyByJob[job];
	}

	private PiecewiseLinearFunction constantFunction(double value) {
		PiecewiseLinearFunction function = new PiecewiseLinearFunction();
		function.resetDomain(0.0, pricingHorizon);
		function.addSegment(0.0, pricingHorizon, 0.0, value);
		return function;
	}

	private boolean isCompletionJobAvailable(int job) {
		return job > 0 && job <= data.n && !isZeroDualExcludedJob(job);
	}

	private boolean isZeroDualExcludedJob(int job) {
		return job > 0 && zeroDualExcludedJobs != null && job < zeroDualExcludedJobs.length
				&& zeroDualExcludedJobs[job];
	}

	private boolean mergeForward(PiecewiseLinearFunction[] targetByJob, int job, PiecewiseLinearFunction candidate) {
		return mergeFunction(targetByJob, job, candidate, Direction.FORWARD);
	}

	private boolean mergeBackward(PiecewiseLinearFunction[] targetByJob, int job, PiecewiseLinearFunction candidate) {
		return mergeFunction(targetByJob, job, candidate, Direction.BACKWARD);
	}

	private boolean mergeFunction(PiecewiseLinearFunction[] targetByJob, int job, PiecewiseLinearFunction candidate,
			Direction direction) {
		if (!hasPositiveDomain(candidate)) {
			return false;
		}
		stats.mergeCalls++;
		PiecewiseLinearFunction current = targetByJob[job];
		if (current == null || current.head == null) {
			targetByJob[job] = candidate.copy();
			stats.mergeChanged++;
			return true;
		}
		boolean changed = current.mergeMinimum(candidate, direction, true);
		if (changed) {
			stats.mergeChanged++;
		}
		return changed;
	}

	private boolean hasPositiveDomain(PiecewiseLinearFunction function) {
		// 2026-06-01: completion bound 传播只维护可继续接后继 job 的正长度函数。
		// 单点 candidate 最多代表边界时刻的收尾值，不能作为 relaxed completion 的后续状态继续传播；
		// 直接纳入 mergeMinimum 还会破坏 PWLF 当前“正长度 overlap”的合并契约。
		return function != null && function.head != null && function.tail != null
				&& Utility.compareLt(function.head.start, function.tail.end);
	}

	private PiecewiseLinearFunction cropToInterval(PiecewiseLinearFunction function, double start, double end) {
		PiecewiseLinearFunction cropped = new PiecewiseLinearFunction();
		cropped.resetDomain(start, end);
		if (function == null || function.head == null || Utility.compareGt(start, end)) {
			return cropped;
		}
		if (Utility.compareEq(start, end)) {
			if (!Utility.compareLt(start, function.head.start) && !Utility.compareGt(start, function.tail.end)) {
				cropped.addSegment(start, end, 0.0, function.evaluate(start));
			}
			return cropped;
		}
		for (Segment seg = function.head; seg != null; seg = seg.next) {
			if (Utility.compareEq(seg.start, seg.end)
					&& !Utility.compareLt(seg.start, start)
					&& !Utility.compareGt(seg.start, end)) {
				cropped.addSegment(seg.start, seg.end, 0.0, seg.getValue(seg.start));
				continue;
			}
			double segStart = Math.max(seg.start, start);
			double segEnd = Math.min(seg.end, end);
			if (Utility.compareLt(segStart, segEnd)) {
				cropped.addSegment(segStart, segEnd, seg.slope, seg.intercept);
			}
		}
		mergeAdjacentEqualSegments(cropped);
		return cropped;
	}

	private void mergeAdjacentEqualSegments(PiecewiseLinearFunction function) {
		if (function == null || function.head == null) {
			return;
		}
		Segment cur = function.head;
		while (cur.next != null) {
			if (Utility.compareEq(cur.end, cur.next.start) && Utility.compareEq(cur.slope, cur.next.slope)
					&& Utility.compareEq(cur.intercept, cur.next.intercept)) {
				cur.end = cur.next.end;
				cur.next = cur.next.next;
			} else {
				cur = cur.next;
			}
		}
		function.tail = cur;
	}

	private double queuePriority(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.tail == null) {
			return Utility.big_M;
		}
		return frontier.tail.getValue(frontier.tail.end);
	}

	private final class StateQueue {
		private final ArrayDeque<QueueState> fifoQueue;
		private final PriorityQueue<QueueState> priorityQueue;

		StateQueue() {
			if (queueOrdering == QueueOrdering.REDUCED_COST) {
				fifoQueue = null;
				priorityQueue = new PriorityQueue<QueueState>(new Comparator<QueueState>() {
					@Override
					public int compare(QueueState left, QueueState right) {
						int byPriority = compareDoubleAsc(left.priority, right.priority);
						if (byPriority != 0) {
							return byPriority;
						}
						int byFirst = Integer.compare(left.first, right.first);
						return byFirst != 0 ? byFirst : Integer.compare(left.second, right.second);
					}
				});
			} else {
				fifoQueue = new ArrayDeque<QueueState>();
				priorityQueue = null;
			}
		}

		void enqueue(QueueState state, PiecewiseLinearFunction frontier) {
			if (state.inQueue) {
				if (priorityQueue != null) {
					priorityQueue.remove(state);
				} else {
					return;
				}
			}
			state.priority = queuePriority(frontier);
			state.inQueue = true;
			if (priorityQueue != null) {
				priorityQueue.add(state);
			} else {
				fifoQueue.add(state);
			}
		}

		boolean isEmpty() {
			return priorityQueue != null ? priorityQueue.isEmpty() : fifoQueue.isEmpty();
		}

		QueueState poll() {
			QueueState state = priorityQueue != null ? priorityQueue.poll() : fifoQueue.poll();
			state.inQueue = false;
			return state;
		}
	}

	private static int compareDoubleAsc(double left, double right) {
		if (Utility.compareLt(left, right)) {
			return -1;
		}
		if (Utility.compareGt(left, right)) {
			return 1;
		}
		return 0;
	}

	private static final class QueueState {
		final int first;
		final int second;
		double priority;
		boolean inQueue;

		QueueState(int first, int second) {
			this.first = first;
			this.second = second;
		}
	}

	private static final class FunctionPair {
		final PiecewiseLinearFunction u;
		final PiecewiseLinearFunction f;

		FunctionPair(PiecewiseLinearFunction u, PiecewiseLinearFunction f) {
			this.u = u;
			this.f = f;
		}
	}
}
