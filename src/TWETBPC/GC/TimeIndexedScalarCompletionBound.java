package TWETBPC.GC;

import java.util.Arrays;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;

/**
 * 2026-06-28: 给 ng-DSSR 主线单独使用的 time-indexed relaxed scalar bound。
 * <p>
 * 该类不生成列，也不替代 PWLF completion bound。它只在整数时间实例上构造允许重复任务的
 * time-indexed relaxed graph，用 prefix/suffix shortest path 强化现有 scalar 预剪，
 * 并在节点闭合后按同一图执行 time-indexed reduced-cost arc fixing。
 */
public final class TimeIndexedScalarCompletionBound {
	private static final double INF = 1e100;
	private static final double RC_TOLERANCE = 1e-6;

	static final class WindowTightening {
		final int tightenedJobs;
		final int reachableJobs;

		WindowTightening(int tightenedJobs, int reachableJobs) {
			this.tightenedJobs = tightenedJobs;
			this.reachableJobs = reachableJobs;
		}
	}

	public static final class ArcFixingResult {
		final boolean available;
		final int candidates;
		final int fixed;
		final int unavailable;
		final int processFixed;
		final int idleFixed;
		final int endFixed;
		final double gap;
		final long totalNanos;
		final String message;
		final WindowReachabilityStats windowStats;

		private ArcFixingResult(boolean available, int candidates, int fixed, int unavailable,
				int processFixed, int idleFixed, int endFixed, double gap, long totalNanos, String message,
				WindowReachabilityStats windowStats) {
			this.available = available;
			this.candidates = candidates;
			this.fixed = fixed;
			this.unavailable = unavailable;
			this.processFixed = processFixed;
			this.idleFixed = idleFixed;
			this.endFixed = endFixed;
			this.gap = gap;
			this.totalNanos = totalNanos;
			this.message = message;
			this.windowStats = windowStats;
		}

		static ArcFixingResult skipped(String message) {
			return new ArcFixingResult(false, 0, 0, 0, 0, 0, 0, Double.NaN, 0L, message, null);
		}

		public boolean isAvailable() {
			return available;
		}

		public String summary() {
			return message + ", candidates=" + candidates + ", fixed=" + fixed
					+ ", unavailable=" + unavailable + ", process/idle/end=" + processFixed
					+ "/" + idleFixed + "/" + endFixed + ", gap=" + gap
					+ ", ms=" + String.format("%.3f", totalNanos / 1_000_000.0)
					+ (windowStats == null ? "" : ", " + windowStats.summary());
		}
	}

	private static final class WindowReachabilityStats {
		final boolean exactIntegerTime;
		final int reachableJobs;
		final int tightenedJobs;
		final int unreachableJobs;
		final double avgOriginalPoints;
		final double avgHullPoints;
		final double avgReachablePoints;
		final int maxHullShrink;
		final String topShrinkSample;

		WindowReachabilityStats(boolean exactIntegerTime, int reachableJobs, int tightenedJobs, int unreachableJobs,
				double avgOriginalPoints, double avgHullPoints, double avgReachablePoints, int maxHullShrink,
				String topShrinkSample) {
			this.exactIntegerTime = exactIntegerTime;
			this.reachableJobs = reachableJobs;
			this.tightenedJobs = tightenedJobs;
			this.unreachableJobs = unreachableJobs;
			this.avgOriginalPoints = avgOriginalPoints;
			this.avgHullPoints = avgHullPoints;
			this.avgReachablePoints = avgReachablePoints;
			this.maxHullShrink = maxHullShrink;
			this.topShrinkSample = topShrinkSample;
		}

		String summary() {
			return "windowMode=" + (exactIntegerTime ? "integer" : "relaxed")
					+ ", windows reachable/tightened/unreachable=" + reachableJobs + "/" + tightenedJobs
					+ "/" + unreachableJobs
					+ ", avgOrigPts/hullPts/reachablePts=" + format(avgOriginalPoints) + "/"
					+ format(avgHullPoints) + "/" + format(avgReachablePoints)
					+ ", maxHullShrink=" + maxHullShrink
					+ ", topWindowShrink=[" + topShrinkSample + "]";
		}

		private static String format(double value) {
			return String.format("%.1f", value);
		}
	}

	private final Data data;
	private final TWETBPCConfig config;
	private final LP lp;
	private final Node node;
	private final int n;
	private final int sink;
	private final int horizon;
	private final int width;
	private final boolean exactIntegerTime;
	private final double[] forward;
	private final double[] backward;
	private final double[][] prefixBeforeByJob;
	private final double[][] suffixAfterByJob;
	private final double[][] penaltyByJobTime;
	private final double[] originalWindowStartByJob;
	private final double[] originalWindowEndByJob;
	private final int[][] durationByArc;
	private final boolean[][] processArcForbidden;
	private final boolean[] endForbidden;
	private final boolean available;
	private final String message;
	private long buildNanos;

	static TimeIndexedScalarCompletionBound build(Data data, TWETBPCConfig config, LP lp, double pricingHorizon,
			double[] hStartByJob, double[] hEndByJob) {
		if (!config.timeIndexedCompletionBoundScalarEnhancement || lp == null || lp.getNode() == null
				|| lp.getNode().depth <= 0) {
			return null;
		}
		TimeIndexedScalarCompletionBound bound =
				new TimeIndexedScalarCompletionBound(data, config, lp, pricingHorizon, hStartByJob, hEndByJob);
		return bound.available ? bound : null;
	}

	public static ArcFixingResult applyArcFixing(Data data, TWETBPCConfig config, LP lp, double incumbentCost) {
		if (!config.timeIndexedCompletionBoundScalarEnhancement || !config.timeIndexedCompletionBoundArcFixing) {
			return ArcFixingResult.skipped("time-indexed scalar helper disabled");
		}
		if (lp == null || lp.getNode() == null || lp.getNode().depth <= 0 || lp.getLastSolution() == null) {
			return ArcFixingResult.skipped("root or missing LP solution");
		}
		double nodeLowerBound = lp.getLastSolution().getObjectiveValue();
		if (!Double.isFinite(incumbentCost) || !Double.isFinite(nodeLowerBound)) {
			return ArcFixingResult.skipped("missing finite UB/LB");
		}
		double gap = incumbentCost - nodeLowerBound;
		if (Utility.compareLe(gap, RC_TOLERANCE)) {
			return ArcFixingResult.skipped("closed gap");
		}
		double[] hStart = new double[data.n + 1];
		double[] hEnd = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			hStart[job] = data.hardWindowStart[job];
			hEnd[job] = data.hardWindowEnd[job];
		}
		TimeIndexedScalarCompletionBound bound =
				new TimeIndexedScalarCompletionBound(data, config, lp, data.CmaxH, hStart, hEnd);
		if (!bound.available) {
			return ArcFixingResult.skipped(bound.message);
		}
		return bound.applyArcFixing(gap);
	}

	private TimeIndexedScalarCompletionBound(Data data, TWETBPCConfig config, LP lp, double pricingHorizon,
			double[] hStartByJob, double[] hEndByJob) {
		this.data = data;
		this.config = config;
		this.lp = lp;
		this.node = lp.getNode();
		this.n = data.n;
		this.sink = node.sinkId();
		this.exactIntegerTime = isIntegerTimeInstance(data, pricingHorizon, hStartByJob, hEndByJob);
		this.horizon = Math.max(0, (int) Math.ceil(pricingHorizon - 1e-9));
		this.width = horizon + 1;
		this.originalWindowStartByJob = Arrays.copyOf(hStartByJob, n + 1);
		this.originalWindowEndByJob = Arrays.copyOf(hEndByJob, n + 1);
		if (!hasPositiveDiscreteDurations(data, exactIntegerTime)) {
			this.forward = null;
			this.backward = null;
			this.prefixBeforeByJob = null;
			this.suffixAfterByJob = null;
			this.penaltyByJobTime = null;
			this.durationByArc = null;
			this.processArcForbidden = null;
			this.endForbidden = null;
			this.available = false;
			this.message = "non-positive discretized duration";
			return;
		}
		int states = (n + 1) * width;
		this.forward = new double[states];
		this.backward = new double[states];
		this.prefixBeforeByJob = new double[n + 1][];
		this.suffixAfterByJob = new double[n + 1][];
		this.penaltyByJobTime = new double[n + 1][width];
		this.durationByArc = new int[n + 1][n + 1];
		this.processArcForbidden = new boolean[n + 1][n + 1];
		this.endForbidden = new boolean[n + 1];
		long start = System.nanoTime();
		precomputeStaticData(hStartByJob, hEndByJob);
		computeForwardDistances();
		computeBackwardDistances();
		buildScalarCaches();
		this.buildNanos = System.nanoTime() - start;
		this.available = true;
		this.message = "available";
	}

	double suffixLowerBoundAfterFloor(int job, double earliestTime) {
		if (!available || job <= 0 || job > n || !Double.isFinite(earliestTime)) {
			return Utility.big_M;
		}
		int t = (int) Math.floor(snapToInteger(earliestTime));
		if (t < 0 || t > horizon) {
			return Utility.big_M;
		}
		return suffixAfterByJob[job][t];
	}

	double prefixLowerBoundBeforeCeil(int job, double latestTime) {
		if (!available || job <= 0 || job > n || !Double.isFinite(latestTime)) {
			return Utility.big_M;
		}
		int t = (int) Math.ceil(snapToInteger(latestTime));
		if (t < 0 || t > horizon) {
			return Utility.big_M;
		}
		return prefixBeforeByJob[job][t];
	}

	long getBuildNanos() {
		return buildNanos;
	}

	WindowTightening tightenWindows(double[] hStartByJob, double[] hEndByJob) {
		if (!available || !exactIntegerTime || !config.timeIndexedCompletionBoundWindowTightening || hStartByJob == null
				|| hEndByJob == null) {
			return new WindowTightening(0, 0);
		}
		int tightened = 0;
		int reachable = 0;
		for (int job = 1; job <= n; job++) {
			int min = -1;
			int max = -1;
			for (int t = 0; t <= horizon; t++) {
				if (isFinite(forward[index(job, t)]) && isFinite(backward[index(job, t)])) {
					if (min < 0) {
						min = t;
					}
					max = t;
				}
			}
			if (min < 0) {
				continue;
			}
			reachable++;
			double newStart = Math.max(hStartByJob[job], min);
			double newEnd = Math.min(hEndByJob[job], max);
			if (Utility.compareGt(newStart, hStartByJob[job]) || Utility.compareLt(newEnd, hEndByJob[job])) {
				hStartByJob[job] = newStart;
				hEndByJob[job] = newEnd;
				tightened++;
			}
		}
		return new WindowTightening(tightened, reachable);
	}

	private ArcFixingResult applyArcFixing(double gap) {
		long start = System.nanoTime();
		int candidates = 0;
		int fixed = 0;
		int unavailable = 0;
		int processFixed = 0;
		int idleFixed = 0;
		int endFixed = 0;
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
					candidates++;
					int completion = t + durationByArc[from][to];
					if (completion > horizon || !isCompletionFeasible(to, completion)) {
						unavailable++;
						continue;
					}
					double suffix = backward[index(to, completion)];
					double arcCost = processArcReducedCost(from, to, completion);
					if (!isFinite(suffix) || !isFinite(arcCost)) {
						unavailable++;
						continue;
					}
					if (Utility.compareGe(prefix + arcCost + suffix, gap - RC_TOLERANCE)) {
						node.forbidTimeIndexedPricingOnlyArc(from, to, t);
						processFixed++;
						fixed++;
					}
				}
				if (t < horizon && !isTimeIndexedArcForbidden(from, from, t)) {
					candidates++;
					double suffix = backward[index(from, t + 1)];
					if (!isFinite(suffix)) {
						unavailable++;
					} else if (Utility.compareGe(prefix + suffix, gap - RC_TOLERANCE)) {
						node.forbidTimeIndexedPricingOnlyArc(from, from, t);
						idleFixed++;
						fixed++;
					}
				}
				if (from > 0 && isEndAllowed(from, t)) {
					candidates++;
					if (Utility.compareGe(prefix + sinkArcReducedCost(from), gap - RC_TOLERANCE)) {
						node.forbidTimeIndexedPricingOnlyArc(from, 0, t);
						endFixed++;
						fixed++;
					}
				}
			}
		}
		if (fixed > 0) {
			computeForwardDistances();
			computeBackwardDistances();
			buildScalarCaches();
		}
		WindowReachabilityStats windowStats = summarizeReachableWindows();
		return new ArcFixingResult(true, candidates, fixed, unavailable, processFixed, idleFixed, endFixed, gap,
				System.nanoTime() - start, "ng-DSSR time-indexed scalar helper arc fixing", windowStats);
	}

	private WindowReachabilityStats summarizeReachableWindows() {
		int reachable = 0;
		int tightened = 0;
		int unreachable = 0;
		double originalPointSum = 0.0;
		double hullPointSum = 0.0;
		double reachablePointSum = 0.0;
		int maxShrink = 0;
		final int sampleSize = 8;
		int[] topJob = new int[sampleSize];
		int[] topShrink = new int[sampleSize];
		int[] topMin = new int[sampleSize];
		int[] topMax = new int[sampleSize];
		int[] topCount = new int[sampleSize];
		int[] topOldStart = new int[sampleSize];
		int[] topOldEnd = new int[sampleSize];
		Arrays.fill(topJob, -1);
		for (int job = 1; job <= n; job++) {
			int originalStart = discreteWindowStart(job);
			int originalEnd = discreteWindowEnd(job);
			int originalPoints = originalEnd >= originalStart ? originalEnd - originalStart + 1 : 0;
			int min = -1;
			int max = -1;
			int count = 0;
			for (int t = 0; t <= horizon; t++) {
				if (isFinite(forward[index(job, t)]) && isFinite(backward[index(job, t)])) {
					if (min < 0) {
						min = t;
					}
					max = t;
					count++;
				}
			}
			if (count == 0) {
				unreachable++;
				continue;
			}
			reachable++;
			int hullPoints = max - min + 1;
			int shrink = Math.max(0, originalPoints - hullPoints);
			originalPointSum += originalPoints;
			hullPointSum += hullPoints;
			reachablePointSum += count;
			maxShrink = Math.max(maxShrink, shrink);
			if (min > originalStart || max < originalEnd) {
				tightened++;
			}
			recordTopWindowShrink(topJob, topShrink, topMin, topMax, topCount, topOldStart, topOldEnd,
					job, shrink, min, max, count, originalStart, originalEnd);
		}
		double divisor = Math.max(1, reachable);
		return new WindowReachabilityStats(exactIntegerTime, reachable, tightened, unreachable,
				originalPointSum / divisor, hullPointSum / divisor, reachablePointSum / divisor,
				maxShrink, formatTopWindowShrink(topJob, topShrink, topMin, topMax, topCount,
						topOldStart, topOldEnd));
	}

	private int discreteWindowStart(int job) {
		double value = originalWindowStartByJob[job];
		return Math.max(0, exactIntegerTime ? (int) Math.ceil(value - 1e-9)
				: (int) Math.floor(value + 1e-9));
	}

	private int discreteWindowEnd(int job) {
		double value = originalWindowEndByJob[job];
		return Math.min(horizon, exactIntegerTime ? (int) Math.floor(value + 1e-9)
				: (int) Math.ceil(value - 1e-9));
	}

	private static void recordTopWindowShrink(int[] topJob, int[] topShrink, int[] topMin, int[] topMax,
			int[] topCount, int[] topOldStart, int[] topOldEnd, int job, int shrink, int min, int max,
			int count, int oldStart, int oldEnd) {
		for (int pos = 0; pos < topJob.length; pos++) {
			if (shrink > topShrink[pos]) {
				for (int move = topJob.length - 1; move > pos; move--) {
					topJob[move] = topJob[move - 1];
					topShrink[move] = topShrink[move - 1];
					topMin[move] = topMin[move - 1];
					topMax[move] = topMax[move - 1];
					topCount[move] = topCount[move - 1];
					topOldStart[move] = topOldStart[move - 1];
					topOldEnd[move] = topOldEnd[move - 1];
				}
				topJob[pos] = job;
				topShrink[pos] = shrink;
				topMin[pos] = min;
				topMax[pos] = max;
				topCount[pos] = count;
				topOldStart[pos] = oldStart;
				topOldEnd[pos] = oldEnd;
				return;
			}
		}
	}

	private static String formatTopWindowShrink(int[] topJob, int[] topShrink, int[] topMin, int[] topMax,
			int[] topCount, int[] topOldStart, int[] topOldEnd) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < topJob.length; i++) {
			if (topJob[i] < 0 || topShrink[i] <= 0) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append("; ");
			}
			builder.append("j").append(topJob[i]).append(":")
					.append(topMin[i]).append("-").append(topMax[i])
					.append("/old").append(topOldStart[i]).append("-").append(topOldEnd[i])
					.append(",pts=").append(topCount[i])
					.append(",shrink=").append(topShrink[i]);
		}
		return builder.length() == 0 ? "none" : builder.toString();
	}

	private void precomputeStaticData(double[] hStartByJob, double[] hEndByJob) {
		for (int job = 0; job <= n; job++) {
			Arrays.fill(penaltyByJobTime[job], INF);
		}
		for (int job = 1; job <= n; job++) {
			int start = Math.max(0, exactIntegerTime ? (int) Math.ceil(hStartByJob[job] - 1e-9)
					: (int) Math.floor(hStartByJob[job] + 1e-9));
			int end = Math.min(horizon, exactIntegerTime ? (int) Math.floor(hEndByJob[job] + 1e-9)
					: (int) Math.ceil(hEndByJob[job] - 1e-9));
			for (int t = start; t <= end; t++) {
				double penalty = exactIntegerTime ? data.penaltyFunction[job].evaluate(t)
						: relaxedBucketPenalty(job, t, hStartByJob[job], hEndByJob[job]);
				if (!Utility.isBigMValue(penalty)) {
					penaltyByJobTime[job][t] = penalty;
				}
			}
		}
		for (int from = 0; from <= n; from++) {
			for (int to = 1; to <= n; to++) {
				durationByArc[from][to] = exactIntegerTime
						? (int) Math.rint(data.getSetUp(from, to) + data.getProcessT(to))
						: (int) Math.floor(data.getSetUp(from, to) + data.getProcessT(to) + 1e-9);
				processArcForbidden[from][to] = from == to
						|| PricingCompatibility.isRequiredOutsourcedJob(node, to)
						|| node.isArcForbidden(from, to)
						|| node.isPricingOnlyArcForbidden(from, to);
			}
		}
		for (int job = 1; job <= n; job++) {
			endForbidden[job] = node.isArcForbidden(job, sink) || node.isPricingOnlyArcForbidden(job, sink);
		}
	}

	private void computeForwardDistances() {
		Arrays.fill(forward, INF);
		forward[index(0, 0)] = 0.0;
		for (int t = 0; t <= horizon; t++) {
			for (int last = 0; last <= n; last++) {
				double base = forward[index(last, t)];
				if (!isFinite(base)) {
					continue;
				}
				if (t < horizon && !isTimeIndexedArcForbidden(last, last, t)) {
					relax(forward, index(last, t + 1), base);
				}
				for (int next = 1; next <= n; next++) {
					if (next == last || processArcForbidden[last][next]
							|| isTimeIndexedArcForbidden(last, next, t)) {
						continue;
					}
					int completion = t + durationByArc[last][next];
					if (completion > horizon || !isCompletionFeasible(next, completion)) {
						continue;
					}
					double arcCost = processArcReducedCost(last, next, completion);
					if (isFinite(arcCost)) {
						relax(forward, index(next, completion), base + arcCost);
					}
				}
			}
		}
	}

	private void computeBackwardDistances() {
		Arrays.fill(backward, INF);
		backward[index(0, horizon)] = 0.0;
		for (int t = horizon - 1; t >= 0; t--) {
			for (int last = 0; last <= n; last++) {
				if (!isTimeIndexedArcForbidden(last, last, t)) {
					relax(backward, index(last, t), backward[index(last, t + 1)]);
				}
				for (int next = 1; next <= n; next++) {
					if (next == last || processArcForbidden[last][next]
							|| isTimeIndexedArcForbidden(last, next, t)) {
						continue;
					}
					int completion = t + durationByArc[last][next];
					if (completion > horizon || !isCompletionFeasible(next, completion)) {
						continue;
					}
					double suffix = backward[index(next, completion)];
					double arcCost = processArcReducedCost(last, next, completion);
					if (isFinite(suffix) && isFinite(arcCost)) {
						relax(backward, index(last, t), arcCost + suffix);
					}
				}
				if (last > 0 && isEndAllowed(last, t)) {
					relax(backward, index(last, t), sinkArcReducedCost(last));
				}
			}
		}
	}

	private void buildScalarCaches() {
		for (int job = 1; job <= n; job++) {
			double[] prefix = new double[width];
			double best = INF;
			for (int t = 0; t <= horizon; t++) {
				best = Math.min(best, forward[index(job, t)]);
				prefix[t] = best;
			}
			prefixBeforeByJob[job] = prefix;
			double[] suffix = new double[width];
			best = INF;
			for (int t = horizon; t >= 0; t--) {
				best = Math.min(best, backward[index(job, t)]);
				suffix[t] = best;
			}
			suffixAfterByJob[job] = suffix;
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

	private boolean isTimeIndexedArcForbidden(int from, int to, int time) {
		return node.isTimeIndexedPricingOnlyArcForbidden(from, to, time);
	}

	private double relaxedBucketPenalty(int job, int time, double hStart, double hEnd) {
		double start = Math.max(time, hStart);
		double end = Math.min(Math.min(horizon, time + 1.0), hEnd);
		if (Utility.compareGt(start, end)) {
			return Utility.big_M;
		}
		double best = Utility.big_M;
		best = Math.min(best, evaluatePenaltyIfFinite(job, start));
		best = Math.min(best, evaluatePenaltyIfFinite(job, end));
		if (!Utility.compareLt(data.d_e[job], start) && !Utility.compareGt(data.d_e[job], end)) {
			best = Math.min(best, evaluatePenaltyIfFinite(job, data.d_e[job]));
		}
		if (!Utility.compareLt(data.d_l[job], start) && !Utility.compareGt(data.d_l[job], end)) {
			best = Math.min(best, evaluatePenaltyIfFinite(job, data.d_l[job]));
		}
		return best;
	}

	private double evaluatePenaltyIfFinite(int job, double time) {
		double value = data.penaltyFunction[job].evaluate(time);
		return Utility.isBigMValue(value) ? Utility.big_M : value;
	}

	private void relax(double[] values, int index, double candidate) {
		if (Utility.compareLt(candidate, values[index])) {
			values[index] = candidate;
		}
	}

	private int index(int job, int time) {
		return job * width + time;
	}

	private static boolean isFinite(double value) {
		return value < INF * 0.5;
	}

	private static double snapToInteger(double value) {
		double rounded = Math.rint(value);
		return Utility.compareEq(value, rounded) ? rounded : value;
	}

	private static boolean isIntegerTimeInstance(Data data, double pricingHorizon, double[] hStartByJob,
			double[] hEndByJob) {
		// pricingHorizon 是当前 node 的安全上界，可以来自 LP/启发式计算而带小数；
		// 只要任务时间、setup 和窗口本身是整数，ceil(horizon) 的 time-indexed 图仍是精确整数时间图。
		for (int job = 1; job <= data.n; job++) {
			if (!isInteger(data.getProcessT(job)) || !isInteger(hStartByJob[job]) || !isInteger(hEndByJob[job])) {
				return false;
			}
		}
		for (int from = 0; from <= data.n; from++) {
			for (int to = 1; to <= data.n; to++) {
				if (!isInteger(data.getSetUp(from, to))) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean hasPositiveDiscreteDurations(Data data, boolean exactIntegerTime) {
		for (int from = 0; from <= data.n; from++) {
			for (int to = 1; to <= data.n; to++) {
				int duration = exactIntegerTime
						? (int) Math.rint(data.getSetUp(from, to) + data.getProcessT(to))
						: (int) Math.floor(data.getSetUp(from, to) + data.getProcessT(to) + 1e-9);
				if (duration <= 0) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isInteger(double value) {
		return Double.isFinite(value) && Utility.compareEq(value, Math.rint(value));
	}
}
