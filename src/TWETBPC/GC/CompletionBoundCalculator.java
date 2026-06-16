package TWETBPC.GC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.MergeResult;
import Common.PiecewiseLinearFunction.MergeMinimumObserver;
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
		final PiecewiseLinearFunction[] forwardFByJob;
		final PiecewiseLinearFunction[] backwardBByJob;
		final double[][] forwardUBeforeByJob;
		final double[][] backwardRAfterByJob;
		final double[] forwardUMinByJob;
		final double[] forwardFMinByJob;
		final double[] backwardBMinByJob;
		final int maxDiscreteTime;

		Bounds(int n, double pricingHorizon) {
			this.forwardUByJob = new PiecewiseLinearFunction[n + 1];
			this.backwardRByJob = new PiecewiseLinearFunction[n + 1];
			this.forwardFByJob = new PiecewiseLinearFunction[n + 1];
			this.backwardBByJob = new PiecewiseLinearFunction[n + 1];
			this.forwardUBeforeByJob = new double[n + 1][];
			this.backwardRAfterByJob = new double[n + 1][];
			this.forwardUMinByJob = new double[n + 1];
			this.forwardFMinByJob = new double[n + 1];
			this.backwardBMinByJob = new double[n + 1];
			Arrays.fill(this.forwardUMinByJob, Utility.big_M);
			Arrays.fill(this.forwardFMinByJob, Utility.big_M);
			Arrays.fill(this.backwardBMinByJob, Utility.big_M);
			this.maxDiscreteTime = Math.max(0, (int) Math.ceil(pricingHorizon));
		}

		double forwardUBeforeCeil(int job, double latestTime) {
			if (job <= 0 || job >= forwardUBeforeByJob.length || !Double.isFinite(latestTime)) {
				return Utility.big_M;
			}
			int index = (int) Math.ceil(snapToNearestInteger(latestTime));
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
			int index = (int) Math.floor(snapToNearestInteger(earliestTime));
			if (index < 0) {
				return Utility.big_M;
			}
			if (index > maxDiscreteTime) {
				return Utility.big_M;
			}
			return discreteValue(backwardRAfterByJob[job], index);
		}

		double forwardUMin(int job) {
			if (job <= 0 || job >= forwardUMinByJob.length) {
				return Utility.big_M;
			}
			return forwardUMinByJob[job];
		}

		double forwardFMin(int job) {
			if (job <= 0 || job >= forwardFMinByJob.length) {
				return Utility.big_M;
			}
			return forwardFMinByJob[job];
		}

		double backwardBMin(int job) {
			if (job <= 0 || job >= backwardBMinByJob.length) {
				return Utility.big_M;
			}
			return backwardBMinByJob[job];
		}

		private double snapToNearestInteger(double value) {
			double rounded = Math.rint(value);
			return Utility.compareEq(value, rounded) ? rounded : value;
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
		long forwardMergeSkipped;
		long forwardMergeFull;
		long forwardMergeFullChanged;
		long forwardMergeSkipNanos;
		long forwardMergeCopyNanos;
		long forwardMergeBodyNanos;
		long forwardMergeNormalizeNanos;
		long forwardNormalizeTrimNanos;
		long forwardNormalizePreCompactNanos;
		long forwardNormalizeDirectionalMinNanos;
		long forwardNormalizePostCompactNanos;
		long backwardMergeSkipped;
		long backwardMergeFull;
		long backwardMergeFullChanged;
		long backwardMergeSkipNanos;
		long backwardMergeCopyNanos;
		long backwardMergeBodyNanos;
		long backwardMergeNormalizeNanos;
		long backwardNormalizeTrimNanos;
		long backwardNormalizePreCompactNanos;
		long backwardNormalizeDirectionalMinNanos;
		long backwardNormalizePostCompactNanos;
		long forwardSegmentSamples;
		long forwardTargetSegments;
		long forwardCandidateSegments;
		long forwardAfterSegments;
		int forwardMaxTargetSegments;
		int forwardMaxCandidateSegments;
		int forwardMaxAfterSegments;
		long backwardSegmentSamples;
		long backwardTargetSegments;
		long backwardCandidateSegments;
		long backwardAfterSegments;
		int backwardMaxTargetSegments;
		int backwardMaxCandidateSegments;
		int backwardMaxAfterSegments;
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
	private final boolean ignorePricingOnlyArcs;
	private final PiecewiseLinearFunction[] forwardReducedPenaltyByJob;
	private final PiecewiseLinearFunction[] backwardReducedPenaltyByJob;
	private final boolean[] zeroDualExcludedJobs;
	private final QueueOrdering queueOrdering;
	private final boolean buildDiscreteCaches;
	private final int[][] forwardSuccessorsByJob;
	private final int[][] backwardPredecessorsByJob;
	private final Stats stats = new Stats();
	private final boolean diagnosticHeartbeat;
	private final long diagnosticHeartbeatIntervalNanos;
	private long diagnosticBuildStartNanos;
	private long diagnosticLastHeartbeatNanos;
	private final boolean diagnosticChangeAudit;
	private final long diagnosticChangeAuditInterval;
	private long diagnosticChangedAudits;
	private long diagnosticChangedAuditNoSampleImprovement;
	private double diagnosticLargestSampleImprovement;
	private final boolean diagnosticChangeSource;
	private final int diagnosticChangeSourceDumpLimit;
	private final boolean deltaPropagation;
	private long diagnosticChangedStrictValue;
	private long diagnosticChangedDomainOnly;
	private long diagnosticChangedNoVisible;
	private int diagnosticChangedNoVisibleDumps;
	private final boolean diagnosticSegments;
	private final boolean diagnosticMergeTiming;

	CompletionBoundCalculator(Data data, LP lp, double pricingHorizon,
			PiecewiseLinearFunction[] forwardPenaltyByJob, PiecewiseLinearFunction[] backwardPenaltyByJob,
			boolean[] zeroDualExcludedJobs, QueueOrdering queueOrdering) {
		this(data, lp, pricingHorizon, forwardPenaltyByJob, backwardPenaltyByJob, zeroDualExcludedJobs,
				queueOrdering, true);
	}

	CompletionBoundCalculator(Data data, LP lp, double pricingHorizon,
			PiecewiseLinearFunction[] forwardPenaltyByJob, PiecewiseLinearFunction[] backwardPenaltyByJob,
			boolean[] zeroDualExcludedJobs, QueueOrdering queueOrdering, boolean buildDiscreteCaches) {
		this(data, lp, pricingHorizon, forwardPenaltyByJob, backwardPenaltyByJob, zeroDualExcludedJobs,
				queueOrdering, buildDiscreteCaches, false);
	}

	CompletionBoundCalculator(Data data, LP lp, double pricingHorizon,
			PiecewiseLinearFunction[] forwardPenaltyByJob, PiecewiseLinearFunction[] backwardPenaltyByJob,
			boolean[] zeroDualExcludedJobs, QueueOrdering queueOrdering, boolean buildDiscreteCaches,
			boolean ignorePricingOnlyArcs) {
		this.data = data;
		this.lp = lp;
		this.node = lp.getNode();
		this.sink = node.sinkId();
		this.pricingHorizon = pricingHorizon;
		this.ignorePricingOnlyArcs = ignorePricingOnlyArcs;
		this.forwardReducedPenaltyByJob = buildReducedPenaltyCache(forwardPenaltyByJob, null, null);
		this.backwardReducedPenaltyByJob = buildReducedPenaltyCache(backwardPenaltyByJob, forwardPenaltyByJob,
				forwardReducedPenaltyByJob);
		this.zeroDualExcludedJobs = zeroDualExcludedJobs;
		this.queueOrdering = queueOrdering == null ? QueueOrdering.FIFO : queueOrdering;
		this.buildDiscreteCaches = buildDiscreteCaches;
		this.forwardSuccessorsByJob = buildForwardSuccessorLists();
		this.backwardPredecessorsByJob = buildBackwardPredecessorLists();
		this.diagnosticHeartbeat = Boolean.getBoolean("twet.bpc.completionBoundHeartbeat");
		long intervalMillis = Long.getLong("twet.bpc.completionBoundHeartbeatIntervalMillis", 10000L);
		this.diagnosticHeartbeatIntervalNanos = Math.max(1L, intervalMillis) * 1000000L;
		this.diagnosticChangeAudit = Boolean.getBoolean("twet.bpc.completionBoundChangeAudit");
		this.diagnosticChangeAuditInterval = Math.max(1L, Long.getLong(
				"twet.bpc.completionBoundChangeAuditInterval", 1000L));
		this.diagnosticChangeSource = Boolean.getBoolean("twet.bpc.completionBoundChangeSource");
		this.diagnosticChangeSourceDumpLimit = Math.max(0,
				Integer.getInteger("twet.bpc.completionBoundChangeSourceDumpLimit", 0));
		this.deltaPropagation = Boolean.getBoolean("twet.bpc.completionBoundDeltaPropagation");
		this.diagnosticSegments = Boolean.getBoolean("twet.bpc.completionBoundSegments");
		this.diagnosticMergeTiming = Boolean.getBoolean("twet.bpc.completionBoundMergeTiming");
	}

	Result build(Relaxation relaxation) {
		diagnosticBuildStartNanos = System.nanoTime();
		diagnosticLastHeartbeatNanos = 0L;
		MergeMinimumObserver previousObserver = null;
		if (diagnosticMergeTiming) {
			previousObserver = PiecewiseLinearFunction.setMergeMinimumObserver(new MergeMinimumObserver() {
				@Override
				public void record(Direction direction, boolean skipped, boolean changed, long skipNanos,
						long copyNanos, long bodyNanos, long normalizeNanos) {
					recordMergeMinimumTiming(direction, skipped, changed, skipNanos, copyNanos, bodyNanos,
							normalizeNanos);
				}

				@Override
				public void recordNormalize(Direction direction, long trimNanos, long preCompactNanos,
						long directionalMinNanos, long postCompactNanos) {
					recordMergeMinimumNormalizeTiming(direction, trimNanos, preCompactNanos,
							directionalMinNanos, postCompactNanos);
				}
			});
		}
		try {
			diagnosticHeartbeat("build.start." + relaxation, 0, 0, 0, true);
			Bounds bounds = relaxation == Relaxation.TWO_CYCLE ? buildTwoCycle()
					: (deltaPropagation ? buildAllCyclesDelta() : buildAllCycles());
			diagnosticHeartbeat("build.after_relaxation." + relaxation, 0, 0, 0, true);
			buildCompletionFunctionMins(bounds);
			diagnosticHeartbeat("build.after_mins." + relaxation, 0, 0, 0, true);
			if (buildDiscreteCaches) {
				buildDiscreteCaches(bounds);
				diagnosticHeartbeat("build.after_discrete." + relaxation, 0, 0, 0, true);
			}
			if (diagnosticMergeTiming) {
				printMergeTiming(relaxation);
			}
			return new Result(bounds, stats);
		} finally {
			if (diagnosticMergeTiming) {
				PiecewiseLinearFunction.setMergeMinimumObserver(previousObserver);
			}
		}
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
				if (job != prev && isCompletionJobAvailable(job) && !isCompletionArcForbidden(prev, job)) {
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
				if (prev != successor && isCompletionJobAvailable(prev) && !isCompletionArcForbidden(prev, successor)) {
					predecessors.add(Integer.valueOf(prev));
				}
			}
			predecessorsByJob[successor] = toIntArray(predecessors);
		}
		return predecessorsByJob;
	}

	private boolean isCompletionArcForbidden(int from, int to) {
		return node.isArcForbidden(from, to)
				|| (!ignorePricingOnlyArcs && node.isPricingOnlyArcForbidden(from, to));
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
		diagnosticHeartbeat("allCycles.forward.seed.done", forwardQueue.size(), 0, 0, true);
		while (!forwardQueue.isEmpty()) {
			stats.forwardQueuePops++;
			int prev = forwardQueue.poll().first;
			diagnosticHeartbeat("allCycles.forward.loop", forwardQueue.size(), prev, 0, false);
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
		diagnosticHeartbeat("allCycles.forward.done", forwardQueue.size(), 0, 0, true);
		stats.forwardBuildNanos += System.nanoTime() - phaseStart;
		copyCompletionFunctions(bounds.forwardFByJob, forwardF);

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
		diagnosticHeartbeat("allCycles.backward.seed.done", backwardQueue.size(), 0, 0, true);
		while (!backwardQueue.isEmpty()) {
			stats.backwardQueuePops++;
			int successor = backwardQueue.poll().first;
			diagnosticHeartbeat("allCycles.backward.loop", backwardQueue.size(), successor, 0, false);
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
		diagnosticHeartbeat("allCycles.backward.done", backwardQueue.size(), 0, 0, true);
		stats.backwardBuildNanos += System.nanoTime() - phaseStart;
		copyCompletionFunctions(bounds.backwardBByJob, backwardB);
		return bounds;
	}

	private Bounds buildAllCyclesDelta() {
		Bounds bounds = new Bounds(data.n, pricingHorizon);
		PiecewiseLinearFunction[] forwardF = new PiecewiseLinearFunction[data.n + 1];
		PiecewiseLinearFunction[] backwardB = new PiecewiseLinearFunction[data.n + 1];
		DeltaQueue forwardQueue = new DeltaQueue(data.n + 1);
		DeltaQueue backwardQueue = new DeltaQueue(data.n + 1);

		long phaseStart = System.nanoTime();
		PiecewiseLinearFunction source = sourcePropagationFunction();
		for (int idx = 0; idx < forwardSuccessorsByJob[0].length; idx++) {
			int job = forwardSuccessorsByJob[0][idx];
			FunctionPair candidate = buildForwardCandidate(source, 0, job);
			if (candidate == null) {
				continue;
			}
			mergeForward(bounds.forwardUByJob, job, candidate.u);
			MergeResult changed = mergeForwardWithChangeHull(forwardF, job, candidate.f);
			if (changed.hasPositiveChangedInterval()) {
				forwardQueue.enqueue(job, changed.changedStart, changed.changedEnd);
			}
		}
		diagnosticHeartbeat("allCyclesDelta.forward.seed.done", forwardQueue.size(), 0, 0, true);
		while (!forwardQueue.isEmpty()) {
			stats.forwardQueuePops++;
			int prev = forwardQueue.poll();
			double changedStart = forwardQueue.polledStart();
			diagnosticHeartbeat("allCyclesDelta.forward.loop", forwardQueue.size(), prev, 0, false);
			PiecewiseLinearFunction prevF = cropToInterval(forwardF[prev], changedStart, pricingHorizon);
			if (!hasPositiveDomain(prevF)) {
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
				MergeResult changed = mergeForwardWithChangeHull(forwardF, job, candidate.f);
				if (changed.hasPositiveChangedInterval()) {
					forwardQueue.enqueue(job, changed.changedStart, changed.changedEnd);
				}
			}
		}
		diagnosticHeartbeat("allCyclesDelta.forward.done", forwardQueue.size(), 0, 0, true);
		stats.forwardBuildNanos += System.nanoTime() - phaseStart;
		copyCompletionFunctions(bounds.forwardFByJob, forwardF);

		phaseStart = System.nanoTime();
		int[] sinkPredecessors = backwardPredecessorsByJob[sink];
		for (int idx = 0; idx < sinkPredecessors.length; idx++) {
			int job = sinkPredecessors[idx];
			FunctionPair candidate = buildBackwardSinkCandidate(job);
			if (candidate == null) {
				continue;
			}
			mergeBackward(bounds.backwardRByJob, job, candidate.u);
			MergeResult changed = mergeBackwardWithChangeHull(backwardB, job, candidate.f);
			if (changed.hasPositiveChangedInterval()) {
				backwardQueue.enqueue(job, changed.changedStart, changed.changedEnd);
			}
		}
		diagnosticHeartbeat("allCyclesDelta.backward.seed.done", backwardQueue.size(), 0, 0, true);
		while (!backwardQueue.isEmpty()) {
			stats.backwardQueuePops++;
			int successor = backwardQueue.poll();
			double changedEnd = backwardQueue.polledEnd();
			diagnosticHeartbeat("allCyclesDelta.backward.loop", backwardQueue.size(), successor, 0, false);
			PiecewiseLinearFunction successorB = cropToInterval(backwardB[successor], 0.0, changedEnd);
			if (!hasPositiveDomain(successorB)) {
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
				MergeResult changed = mergeBackwardWithChangeHull(backwardB, prev, candidate.f);
				if (changed.hasPositiveChangedInterval()) {
					backwardQueue.enqueue(prev, changed.changedStart, changed.changedEnd);
				}
			}
		}
		diagnosticHeartbeat("allCyclesDelta.backward.done", backwardQueue.size(), 0, 0, true);
		stats.backwardBuildNanos += System.nanoTime() - phaseStart;
		copyCompletionFunctions(bounds.backwardBByJob, backwardB);
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
		diagnosticHeartbeat("twoCycle.forward.seed.done", forwardQueue.size(), 0, 0, true);
		while (!forwardQueue.isEmpty()) {
			stats.forwardQueuePops++;
			QueueState state = forwardQueue.poll();
			int prevPrev = state.first;
			int prev = state.second;
			diagnosticHeartbeat("twoCycle.forward.loop", forwardQueue.size(), prevPrev, prev, false);
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
		diagnosticHeartbeat("twoCycle.forward.done", forwardQueue.size(), 0, 0, true);
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
		diagnosticHeartbeat("twoCycle.backward.seed.done", backwardQueue.size(), 0, 0, true);
		while (!backwardQueue.isEmpty()) {
			stats.backwardQueuePops++;
			QueueState state = backwardQueue.poll();
			int current = state.first;
			int successor = state.second;
			diagnosticHeartbeat("twoCycle.backward.loop", backwardQueue.size(), current, successor, false);
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
		diagnosticHeartbeat("twoCycle.backward.done", backwardQueue.size(), 0, 0, true);
		stats.backwardBuildNanos += System.nanoTime() - phaseStart;

		phaseStart = System.nanoTime();
		diagnosticHeartbeat("twoCycle.aggregate.start", 0, 0, 0, true);
		for (int job = 1; job <= data.n; job++) {
			for (int prev = 0; prev <= data.n; prev++) {
				mergeForward(bounds.forwardUByJob, job, forwardU[prev][job]);
				mergeForward(bounds.forwardFByJob, job, forwardF[prev][job]);
			}
			for (int successor = 1; successor <= data.n + 1; successor++) {
				mergeBackward(bounds.backwardRByJob, job, backwardR[job][successor]);
				mergeBackward(bounds.backwardBByJob, job, backwardB[job][successor]);
			}
		}
		diagnosticHeartbeat("twoCycle.aggregate.done", 0, 0, 0, true);
		stats.aggregateNanos += System.nanoTime() - phaseStart;
		return bounds;
	}

	private void copyCompletionFunctions(PiecewiseLinearFunction[] targetByJob, PiecewiseLinearFunction[] sourceByJob) {
		for (int job = 1; job <= data.n; job++) {
			PiecewiseLinearFunction source = sourceByJob[job];
			if (source != null && source.head != null) {
				targetByJob[job] = source.copy();
			}
		}
	}

	private void buildDiscreteCaches(Bounds bounds) {
		for (int job = 1; job <= data.n; job++) {
			bounds.forwardUMinByJob[job] = functionMin(bounds.forwardUByJob[job]);
			bounds.forwardUBeforeByJob[job] = buildForwardBeforeCache(bounds.forwardUByJob[job],
					bounds.maxDiscreteTime);
			bounds.backwardRAfterByJob[job] = buildBackwardAfterCache(bounds.backwardRByJob[job],
					bounds.maxDiscreteTime);
		}
	}

	private void buildCompletionFunctionMins(Bounds bounds) {
		for (int job = 1; job <= data.n; job++) {
			bounds.forwardFMinByJob[job] = functionMin(bounds.forwardFByJob[job]);
			bounds.backwardBMinByJob[job] = functionMin(bounds.backwardBByJob[job]);
		}
	}

	/**
	 * 2026-06-02: 只记录函数物理定义域内部真实覆盖到的整数点；边界上的特殊前缀/后缀
	 * 语义由调用方按 label 场景处理，不在这里对整张表做外推。
	 */
	private double[] buildForwardBeforeCache(PiecewiseLinearFunction function, int maxDiscreteTime) {
		if (function == null || function.head == null) {
			return null;
		}
		double[] values = new double[maxDiscreteTime + 1];
		Arrays.fill(values, Utility.big_M);
		fillDiscreteValuesFromSegments(values, function);
		return values;
	}

	/**
	 * 2026-06-02: 只记录函数物理定义域内部真实覆盖到的整数点；边界上的特殊前缀/后缀
	 * 语义由调用方按 label 场景处理，不在这里对整张表做外推。
	 */
	private double[] buildBackwardAfterCache(PiecewiseLinearFunction function, int maxDiscreteTime) {
		if (function == null || function.head == null) {
			return null;
		}
		double[] values = new double[maxDiscreteTime + 1];
		Arrays.fill(values, Utility.big_M);
		fillDiscreteValuesFromSegments(values, function);
		return values;
	}

	private double functionMin(PiecewiseLinearFunction function) {
		if (function == null || function.head == null) {
			return Utility.big_M;
		}
		return function.findMinimal(false, true)[0];
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

	private MergeResult mergeForwardWithChangeHull(PiecewiseLinearFunction[] targetByJob, int job,
			PiecewiseLinearFunction candidate) {
		return mergeFunctionWithChangeHull(targetByJob, job, candidate, Direction.FORWARD);
	}

	private MergeResult mergeBackwardWithChangeHull(PiecewiseLinearFunction[] targetByJob, int job,
			PiecewiseLinearFunction candidate) {
		return mergeFunctionWithChangeHull(targetByJob, job, candidate, Direction.BACKWARD);
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
			recordSegmentMerge(direction, 0, countSegments(candidate), countSegments(targetByJob[job]));
			stats.mergeChanged++;
			return true;
		}
		int targetSegmentsBefore = diagnosticSegments ? countSegments(current) : 0;
		int candidateSegments = diagnosticSegments ? countSegments(candidate) : 0;
		PiecewiseLinearFunction beforeAudit = null;
		if (diagnosticChangeAudit || diagnosticChangeSource) {
			boolean auditThisMerge = shouldAuditNextChangedMerge();
			// 2026-06-05: 默认关闭的 011 定位诊断。只在开关打开时复制 before。
			beforeAudit = (auditThisMerge || diagnosticChangeSource) ? current.copy() : null;
		}
		boolean changed = current.mergeMinimum(candidate, direction, true);
		if (changed) {
			stats.mergeChanged++;
		}
		recordSegmentMerge(direction, targetSegmentsBefore, candidateSegments, countSegments(current));
		if (diagnosticChangeSource) {
			classifyChangedMerge(beforeAudit, candidate, current, direction, changed);
		}
		if (diagnosticChangeAudit) {
			auditChangedMerge(beforeAudit, current, direction, changed);
		}
		return changed;
	}

	private MergeResult mergeFunctionWithChangeHull(PiecewiseLinearFunction[] targetByJob, int job,
			PiecewiseLinearFunction candidate, Direction direction) {
		if (!hasPositiveDomain(candidate)) {
			return new MergeResult();
		}
		stats.mergeCalls++;
		PiecewiseLinearFunction current = targetByJob[job];
		if (current == null || current.head == null) {
			targetByJob[job] = candidate.copy();
			recordSegmentMerge(direction, 0, countSegments(candidate), countSegments(targetByJob[job]));
			stats.mergeChanged++;
			MergeResult result = new MergeResult();
			result.changed = true;
			result.changedStart = targetByJob[job].head.start;
			result.changedEnd = targetByJob[job].tail.end;
			return result;
		}
		int targetSegmentsBefore = diagnosticSegments ? countSegments(current) : 0;
		int candidateSegments = diagnosticSegments ? countSegments(candidate) : 0;
		MergeResult result = current.mergeMinimumWithChangeHull(candidate, direction);
		if (result.changed) {
			stats.mergeChanged++;
		}
		recordSegmentMerge(direction, targetSegmentsBefore, candidateSegments, countSegments(current));
		return result;
	}

	private void recordSegmentMerge(Direction direction, int targetSegments, int candidateSegments, int afterSegments) {
		if (!diagnosticSegments) {
			return;
		}
		if (direction == Direction.FORWARD) {
			stats.forwardSegmentSamples++;
			stats.forwardTargetSegments += targetSegments;
			stats.forwardCandidateSegments += candidateSegments;
			stats.forwardAfterSegments += afterSegments;
			stats.forwardMaxTargetSegments = Math.max(stats.forwardMaxTargetSegments, targetSegments);
			stats.forwardMaxCandidateSegments = Math.max(stats.forwardMaxCandidateSegments, candidateSegments);
			stats.forwardMaxAfterSegments = Math.max(stats.forwardMaxAfterSegments, afterSegments);
		} else {
			stats.backwardSegmentSamples++;
			stats.backwardTargetSegments += targetSegments;
			stats.backwardCandidateSegments += candidateSegments;
			stats.backwardAfterSegments += afterSegments;
			stats.backwardMaxTargetSegments = Math.max(stats.backwardMaxTargetSegments, targetSegments);
			stats.backwardMaxCandidateSegments = Math.max(stats.backwardMaxCandidateSegments, candidateSegments);
			stats.backwardMaxAfterSegments = Math.max(stats.backwardMaxAfterSegments, afterSegments);
		}
	}

	private void recordMergeMinimumTiming(Direction direction, boolean skipped, boolean changed, long skipNanos,
			long copyNanos, long bodyNanos, long normalizeNanos) {
		if (direction == Direction.FORWARD) {
			if (skipped) {
				stats.forwardMergeSkipped++;
			} else {
				stats.forwardMergeFull++;
				if (changed) {
					stats.forwardMergeFullChanged++;
				}
			}
			stats.forwardMergeSkipNanos += skipNanos;
			stats.forwardMergeCopyNanos += copyNanos;
			stats.forwardMergeBodyNanos += bodyNanos;
			stats.forwardMergeNormalizeNanos += normalizeNanos;
		} else {
			if (skipped) {
				stats.backwardMergeSkipped++;
			} else {
				stats.backwardMergeFull++;
				if (changed) {
					stats.backwardMergeFullChanged++;
				}
			}
			stats.backwardMergeSkipNanos += skipNanos;
			stats.backwardMergeCopyNanos += copyNanos;
			stats.backwardMergeBodyNanos += bodyNanos;
			stats.backwardMergeNormalizeNanos += normalizeNanos;
		}
	}

	private void recordMergeMinimumNormalizeTiming(Direction direction, long trimNanos, long preCompactNanos,
			long directionalMinNanos, long postCompactNanos) {
		if (direction == Direction.FORWARD) {
			stats.forwardNormalizeTrimNanos += trimNanos;
			stats.forwardNormalizePreCompactNanos += preCompactNanos;
			stats.forwardNormalizeDirectionalMinNanos += directionalMinNanos;
			stats.forwardNormalizePostCompactNanos += postCompactNanos;
		} else {
			stats.backwardNormalizeTrimNanos += trimNanos;
			stats.backwardNormalizePreCompactNanos += preCompactNanos;
			stats.backwardNormalizeDirectionalMinNanos += directionalMinNanos;
			stats.backwardNormalizePostCompactNanos += postCompactNanos;
		}
	}

	private void printMergeTiming(Relaxation relaxation) {
		System.out.println("[completionBoundMergeTiming] relaxation=" + relaxation
				+ " fwSkip/full/changed=" + stats.forwardMergeSkipped + "/" + stats.forwardMergeFull
				+ "/" + stats.forwardMergeFullChanged
				+ " bwSkip/full/changed=" + stats.backwardMergeSkipped + "/" + stats.backwardMergeFull
				+ "/" + stats.backwardMergeFullChanged
				+ " fwMs skip/copy/body/normalize=" + formatNanos(stats.forwardMergeSkipNanos)
				+ "/" + formatNanos(stats.forwardMergeCopyNanos)
				+ "/" + formatNanos(stats.forwardMergeBodyNanos)
				+ "/" + formatNanos(stats.forwardMergeNormalizeNanos)
				+ " bwMs skip/copy/body/normalize=" + formatNanos(stats.backwardMergeSkipNanos)
				+ "/" + formatNanos(stats.backwardMergeCopyNanos)
				+ "/" + formatNanos(stats.backwardMergeBodyNanos)
				+ "/" + formatNanos(stats.backwardMergeNormalizeNanos)
				+ " fwNormMs trim/pre/min/post=" + formatNanos(stats.forwardNormalizeTrimNanos)
				+ "/" + formatNanos(stats.forwardNormalizePreCompactNanos)
				+ "/" + formatNanos(stats.forwardNormalizeDirectionalMinNanos)
				+ "/" + formatNanos(stats.forwardNormalizePostCompactNanos)
				+ " bwNormMs trim/pre/min/post=" + formatNanos(stats.backwardNormalizeTrimNanos)
				+ "/" + formatNanos(stats.backwardNormalizePreCompactNanos)
				+ "/" + formatNanos(stats.backwardNormalizeDirectionalMinNanos)
				+ "/" + formatNanos(stats.backwardNormalizePostCompactNanos));
		System.out.flush();
	}

	private int countSegments(PiecewiseLinearFunction function) {
		if (!diagnosticSegments || function == null || function.head == null) {
			return 0;
		}
		int count = 0;
		Segment segment = function.head;
		while (segment != null) {
			count++;
			segment = segment.next;
		}
		return count;
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

	private static final class DeltaQueue {
		private final ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
		private final boolean[] inQueue;
		private final boolean[] hasPending;
		private final double[] startByState;
		private final double[] endByState;
		private double lastStart;
		private double lastEnd;

		DeltaQueue(int stateCount) {
			this.inQueue = new boolean[stateCount];
			this.hasPending = new boolean[stateCount];
			this.startByState = new double[stateCount];
			this.endByState = new double[stateCount];
		}

		void enqueue(int state, double start, double end) {
			if (state < 0 || state >= inQueue.length || !Utility.compareLt(start, end)) {
				return;
			}
			if (hasPending[state]) {
				startByState[state] = Math.min(startByState[state], start);
				endByState[state] = Math.max(endByState[state], end);
			} else {
				hasPending[state] = true;
				startByState[state] = start;
				endByState[state] = end;
			}
			if (!inQueue[state]) {
				inQueue[state] = true;
				queue.add(Integer.valueOf(state));
			}
		}

		boolean isEmpty() {
			return queue.isEmpty();
		}

		int size() {
			return queue.size();
		}

		int poll() {
			int state = queue.poll().intValue();
			inQueue[state] = false;
			lastStart = startByState[state];
			lastEnd = endByState[state];
			hasPending[state] = false;
			return state;
		}

		double polledStart() {
			return lastStart;
		}

		double polledEnd() {
			return lastEnd;
		}
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

		int size() {
			if (priorityQueue != null) {
				discardStalePriorityEntries();
				return priorityQueue.size();
			}
			return fifoQueue.size();
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

	private void diagnosticHeartbeat(String phase, int queueSize, int first, int second, boolean force) {
		if (!diagnosticHeartbeat) {
			return;
		}
		long now = System.nanoTime();
		if (!force && diagnosticLastHeartbeatNanos > 0L
				&& now - diagnosticLastHeartbeatNanos < diagnosticHeartbeatIntervalNanos) {
			return;
		}
		diagnosticLastHeartbeatNanos = now;
		double elapsedMs = diagnosticBuildStartNanos == 0L ? 0.0 : (now - diagnosticBuildStartNanos) / 1000000.0;
		System.out.println("[completion-bound heartbeat] phase=" + phase
				+ " elapsedMs=" + formatMillis(elapsedMs)
				+ " queue=" + queueSize
				+ " state=" + first + "," + second
				+ " fCand=" + stats.forwardCandidateAttempts
				+ " bCand=" + stats.backwardCandidateAttempts
				+ " fPop=" + stats.forwardQueuePops
				+ " bPop=" + stats.backwardQueuePops
				+ " merge=" + stats.mergeCalls
				+ " changed=" + stats.mergeChanged
				+ changeSourceSummary()
				+ " audit=" + diagnosticChangedAudits + "/" + diagnosticChangedAuditNoSampleImprovement
				+ "/" + formatMillis(diagnosticLargestSampleImprovement)
				+ " stale=" + stats.priorityQueueStalePops);
	}

	private String changeSourceSummary() {
		if (!diagnosticChangeSource) {
			return "";
		}
		return " changedClass=" + diagnosticChangedStrictValue + "/"
				+ diagnosticChangedDomainOnly + "/" + diagnosticChangedNoVisible;
	}

	private String formatMillis(double millis) {
		return String.format(java.util.Locale.US, "%.3f", millis);
	}

	private String formatNanos(long nanos) {
		return formatMillis(nanos / 1000000.0);
	}

	private boolean shouldAuditNextChangedMerge() {
		return diagnosticChangeAudit && (stats.mergeChanged + 1L) % diagnosticChangeAuditInterval == 0L;
	}

	private void auditChangedMerge(PiecewiseLinearFunction before, PiecewiseLinearFunction currentAfter,
			Direction direction, boolean changed) {
		if (!diagnosticChangeAudit || !changed || before == null || before.head == null
				|| currentAfter == null || currentAfter.head == null
				|| stats.mergeChanged % diagnosticChangeAuditInterval != 0L) {
			return;
		}
		diagnosticChangedAudits++;
		double bestImprovement = maxStrictImprovement(before, currentAfter);
		if (!Utility.compareGt(bestImprovement, 0.0)) {
			boolean domainExtended = Utility.compareLt(currentAfter.head.start, before.head.start)
					|| Utility.compareGt(currentAfter.tail.end, before.tail.end);
			if (!domainExtended) {
				diagnosticChangedAuditNoSampleImprovement++;
				System.out.println("[completion-bound change audit] no exact improvement"
						+ " direction=" + direction
						+ " mergeChanged=" + stats.mergeChanged
						+ " before=[" + before.head.start + "," + before.tail.end + "]"
						+ " after=[" + currentAfter.head.start + "," + currentAfter.tail.end + "]"
						+ " exactBest=" + bestImprovement);
			}
		} else if (Utility.compareGt(bestImprovement, diagnosticLargestSampleImprovement)) {
			diagnosticLargestSampleImprovement = bestImprovement;
		}
	}

	private void classifyChangedMerge(PiecewiseLinearFunction before, PiecewiseLinearFunction candidate,
			PiecewiseLinearFunction currentAfter, Direction direction, boolean changed) {
		if (!diagnosticChangeSource || !changed || before == null || before.head == null
				|| currentAfter == null || currentAfter.head == null) {
			return;
		}
		double bestImprovement = maxStrictImprovement(before, currentAfter);
		if (Utility.compareGt(bestImprovement, 0.0)) {
			diagnosticChangedStrictValue++;
			return;
		}
		boolean domainExtended = Utility.compareLt(currentAfter.head.start, before.head.start)
				|| Utility.compareGt(currentAfter.tail.end, before.tail.end);
		if (domainExtended) {
			diagnosticChangedDomainOnly++;
			return;
		}
		diagnosticChangedNoVisible++;
		if (diagnosticChangedNoVisibleDumps < diagnosticChangeSourceDumpLimit) {
			diagnosticChangedNoVisibleDumps++;
			System.out.println("[completion-bound change source] no visible change"
					+ " direction=" + direction
					+ " mergeChanged=" + stats.mergeChanged
					+ " before=" + functionSummary(before, 10)
					+ " candidate=" + functionSummary(candidate, 10)
					+ " after=" + functionSummary(currentAfter, 10));
		}
	}

	private String functionSummary(PiecewiseLinearFunction function, int maxSegments) {
		if (function == null || function.head == null) {
			return "empty";
		}
		StringBuilder builder = new StringBuilder();
		builder.append('[').append(function.head.start).append(',').append(function.tail.end).append("]{");
		int count = 0;
		for (Segment seg = function.head; seg != null && count < maxSegments; seg = seg.next, count++) {
			if (count > 0) {
				builder.append(';');
			}
			builder.append('(').append(seg.start).append(',').append(seg.end)
					.append(",a=").append(seg.slope).append(",b=").append(seg.intercept).append(')');
		}
		if (functionSegmentCount(function) > maxSegments) {
			builder.append(";...");
		}
		builder.append('}');
		return builder.toString();
	}

	private int functionSegmentCount(PiecewiseLinearFunction function) {
		int count = 0;
		for (Segment seg = function.head; seg != null; seg = seg.next) {
			count++;
		}
		return count;
	}

	private double maxStrictImprovement(PiecewiseLinearFunction before, PiecewiseLinearFunction after) {
		double start = Math.max(before.head.start, after.head.start);
		double end = Math.min(before.tail.end, after.tail.end);
		if (!Utility.compareLt(start, end)) {
			return 0.0;
		}
		double best = 0.0;
		Segment p = before.head;
		while (p != null && Utility.compareLe(p.end, start)) {
			p = p.next;
		}
		Segment q = after.head;
		while (q != null && Utility.compareLe(q.end, start)) {
			q = q.next;
		}
		double cur = start;
		while (p != null && q != null && Utility.compareLt(cur, end)) {
			double next = Math.min(Math.min(p.end, q.end), end);
			if (Utility.compareLt(cur, next)) {
				double startImprovement = p.getValue(cur) - q.getValue(cur);
				double endImprovement = p.getValue(next) - q.getValue(next);
				if (Utility.compareGt(startImprovement, best)) {
					best = startImprovement;
				}
				if (Utility.compareGt(endImprovement, best)) {
					best = endImprovement;
				}
			}
			cur = next;
			if (Utility.compareEq(next, p.end)) {
				p = p.next;
			}
			if (Utility.compareEq(next, q.end)) {
				q = q.next;
			}
		}
		return best;
	}
}
