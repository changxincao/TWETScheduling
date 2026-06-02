package TWETBPC.GC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
		// 2026-06-01: two-cycle relaxed bound 需要维护 last-arc 维度的函数状态。
		// 它不像经典 VRP 的标量 completion bound 那样只保留 best/second-best 值即可；
		// 每个二维状态都是 PWLF envelope，仍要完整建表并迭代更新。wet030 当前实测表明
		// 其剪枝收益接近 all-cycles，但构造时间显著更高，因此暂只保留为对照/论文扩展选项。
		// 当前输出会把 last-arc 二维状态再合并成按 job 聚合的一维 bound，无法在正式 label
		// 拼接时排除“刚好接回上一点”的那一维状态，因此比完整 two-cycle 状态查询略弱。
		TWO_CYCLE
	}

	enum QueueOrdering {
		FIFO,
		REDUCED_COST
	}

	static final class Bounds {
		final PiecewiseLinearFunction[] forwardUByJob;
		final PiecewiseLinearFunction[] backwardRByJob;
		final double[][] forwardUBeforeByJob;
		final double[][] backwardRAfterByJob;
		final int maxDiscreteTime;

		Bounds(int n, double pricingHorizon) {
			this.forwardUByJob = new PiecewiseLinearFunction[n + 1];
			this.backwardRByJob = new PiecewiseLinearFunction[n + 1];
			this.forwardUBeforeByJob = new double[n + 1][];
			this.backwardRAfterByJob = new double[n + 1][];
			this.maxDiscreteTime = Math.max(0, (int) Math.ceil(pricingHorizon));
		}

		double forwardUBeforeCeil(int job, double latestTime) {
			if (job <= 0 || job >= forwardUBeforeByJob.length || !Double.isFinite(latestTime)) {
				return Utility.big_M;
			}
			int index = (int) Math.ceil(latestTime);
			if (index < 0) {
				return Utility.big_M;
			}
			if (index > maxDiscreteTime) {
				return Utility.big_M;
			}
			return discreteValue(forwardUBeforeByJob[job], index);
		}

		double backwardRAfterFloor(int job, double earliestTime) {
			if (job <= 0 || job >= backwardRAfterByJob.length || !Double.isFinite(earliestTime)) {
				return Utility.big_M;
			}
			int index = (int) Math.floor(earliestTime);
			if (index < 0) {
				return Utility.big_M;
			}
			if (index > maxDiscreteTime) {
				return Utility.big_M;
			}
			return discreteValue(backwardRAfterByJob[job], index);
		}

		private double discreteValue(double[] values, int index) {
			if (values == null || index < 0 || index >= values.length) {
				return Utility.big_M;
			}
			return values[index];
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
		long priorityQueueStalePops;
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
	private final boolean buildDiscreteCaches;
	private final int[][] forwardSuccessorsByJob;
	private final int[][] backwardPredecessorsByJob;
	private final Stats stats = new Stats();

	CompletionBoundCalculator(Data data, LP lp, double pricingHorizon,
			PiecewiseLinearFunction[] forwardPenaltyByJob, PiecewiseLinearFunction[] backwardPenaltyByJob,
			boolean[] zeroDualExcludedJobs, QueueOrdering queueOrdering) {
		this(data, lp, pricingHorizon, forwardPenaltyByJob, backwardPenaltyByJob, zeroDualExcludedJobs,
				queueOrdering, true);
	}

	CompletionBoundCalculator(Data data, LP lp, double pricingHorizon,
			PiecewiseLinearFunction[] forwardPenaltyByJob, PiecewiseLinearFunction[] backwardPenaltyByJob,
			boolean[] zeroDualExcludedJobs, QueueOrdering queueOrdering, boolean buildDiscreteCaches) {
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
		this.buildDiscreteCaches = buildDiscreteCaches;
		this.forwardSuccessorsByJob = buildForwardSuccessorLists();
		this.backwardPredecessorsByJob = buildBackwardPredecessorLists();
	}

	Result build(Relaxation relaxation) {
		Bounds bounds = relaxation == Relaxation.TWO_CYCLE ? buildTwoCycle() : buildAllCycles();
		if (buildDiscreteCaches) {
			buildDiscreteCaches(bounds);
		}
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
		Bounds bounds = new Bounds(data.n, pricingHorizon);
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
		Bounds bounds = new Bounds(data.n, pricingHorizon);
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

	private void buildDiscreteCaches(Bounds bounds) {
		for (int job = 1; job <= data.n; job++) {
			bounds.forwardUBeforeByJob[job] = buildForwardBeforeCache(bounds.forwardUByJob[job],
					bounds.maxDiscreteTime);
			bounds.backwardRAfterByJob[job] = buildBackwardAfterCache(bounds.backwardRByJob[job],
					bounds.maxDiscreteTime);
		}
	}

	/**
	 * 2026-06-02: 先只填真实 segment 覆盖到的整数点，再向右传播前缀最小值。
	 * 这样 UBefore[k] 才真正表示 min_{t<=k} U_i(t)，而不是 U_i(k) 本身。
	 */
	private double[] buildForwardBeforeCache(PiecewiseLinearFunction function, int maxDiscreteTime) {
		if (function == null || function.head == null) {
			return null;
		}
		double[] values = new double[maxDiscreteTime + 1];
		Arrays.fill(values, Utility.big_M);
		fillDiscreteValuesFromSegments(values, function);
		applyPrefixMinimum(values);
		return values;
	}

	/**
	 * 2026-06-02: 先只填真实 segment 覆盖到的整数点，再向左传播后缀最小值。
	 * 这样 RAfter[k] 才真正表示 min_{t>=k} R_i(t)，而不是 R_i(k) 本身。
	 */
	private double[] buildBackwardAfterCache(PiecewiseLinearFunction function, int maxDiscreteTime) {
		if (function == null || function.head == null) {
			return null;
		}
		double[] values = new double[maxDiscreteTime + 1];
		Arrays.fill(values, Utility.big_M);
		fillDiscreteValuesFromSegments(values, function);
		applySuffixMinimum(values);
		return values;
	}

	private void fillDiscreteValuesFromSegments(double[] values, PiecewiseLinearFunction function) {
		int maxDiscreteTime = values.length - 1;
		for (Segment segment = function.head; segment != null; segment = segment.next) {
			int firstTime = Math.max(0, (int) Math.ceil(segment.start));
			int lastTime = Math.min(maxDiscreteTime, (int) Math.floor(segment.end));
			for (int time = firstTime; time <= lastTime; time++) {
				if (!Utility.compareLt(time, segment.start) && !Utility.compareGt(time, segment.end)) {
					values[time] = Math.min(values[time], segment.getValue(time));
				}
			}
		}
	}

	private void applyPrefixMinimum(double[] values) {
		double best = Utility.big_M;
		for (int time = 0; time < values.length; time++) {
			best = Math.min(best, values[time]);
			values[time] = best;
		}
	}

	private void applySuffixMinimum(double[] values) {
		double best = Utility.big_M;
		for (int time = values.length - 1; time >= 0; time--) {
			best = Math.min(best, values[time]);
			values[time] = best;
		}
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

	private double queueTimePriority(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.head == null) {
			return Utility.big_M;
		}
		return frontier.head.start;
	}

	private final class StateQueue {
		private final ArrayDeque<QueueState> fifoQueue;
		private final PriorityQueue<QueueEntry> priorityQueue;
		private long nextQueueSeq = 0L;

		StateQueue() {
			if (queueOrdering == QueueOrdering.REDUCED_COST) {
				fifoQueue = null;
				priorityQueue = new PriorityQueue<QueueEntry>(new Comparator<QueueEntry>() {
					@Override
					public int compare(QueueEntry left, QueueEntry right) {
						if (left.state == right.state) {
							return Long.compare(right.seq, left.seq);
						}
						int byTime = compareDoubleAsc(left.timePriority, right.timePriority);
						if (byTime != 0) {
							return byTime;
						}
						int byCost = compareDoubleAsc(left.costPriority, right.costPriority);
						if (byCost != 0) {
							return byCost;
						}
						int byFirst = Integer.compare(left.state.first, right.state.first);
						if (byFirst != 0) {
							return byFirst;
						}
						int bySecond = Integer.compare(left.state.second, right.state.second);
						return bySecond != 0 ? bySecond : Long.compare(right.seq, left.seq);
					}
				});
			} else {
				fifoQueue = new ArrayDeque<QueueState>();
				priorityQueue = null;
			}
		}

		void enqueue(QueueState state, PiecewiseLinearFunction frontier) {
			if (priorityQueue != null) {
				long seq = ++nextQueueSeq;
				state.latestQueueSeq = seq;
				state.inQueue = true;
				priorityQueue.add(new QueueEntry(state, queueTimePriority(frontier), queuePriority(frontier), seq));
				return;
			}
			if (state.inQueue) {
					return;
			}
			state.priority = queuePriority(frontier);
			state.inQueue = true;
			fifoQueue.add(state);
		}

		boolean isEmpty() {
			if (priorityQueue != null) {
				discardStalePriorityEntries();
				return priorityQueue.isEmpty();
			}
			return fifoQueue.isEmpty();
		}

		QueueState poll() {
			if (priorityQueue != null) {
				discardStalePriorityEntries();
				QueueEntry entry = priorityQueue.poll();
				if (entry == null) {
					return null;
				}
				entry.state.inQueue = false;
				return entry.state;
			}
			QueueState state = fifoQueue.poll();
			state.inQueue = false;
			return state;
		}

		private void discardStalePriorityEntries() {
			while (priorityQueue != null && !priorityQueue.isEmpty()
					&& priorityQueue.peek().seq != priorityQueue.peek().state.latestQueueSeq) {
				priorityQueue.poll();
				stats.priorityQueueStalePops++;
			}
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
		long latestQueueSeq;

		QueueState(int first, int second) {
			this.first = first;
			this.second = second;
		}
	}

	private static final class QueueEntry {
		final QueueState state;
		final double timePriority;
		final double costPriority;
		final long seq;

		QueueEntry(QueueState state, double timePriority, double costPriority, long seq) {
			this.state = state;
			this.timePriority = timePriority;
			this.costPriority = costPriority;
			this.seq = seq;
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
