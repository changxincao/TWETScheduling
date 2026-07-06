package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Locale;

import Basic.Data;
import Common.Utility;
import HEU.TanakaNoOutsourcingBPCTest;
import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.GC.InitialColumnBuilder;
import TWETBPC.GC.InitialColumnBundle;
import TWETBPC.IO.HeuristicSeedProvider;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/**
 * 诊断 time-indexed 窗口下 pseudo-schedule 是否仍可能重复访问同一个 job。
 * <p>
 * 只用于实验分析：给定一组完成时间窗，枚举第一次完成 job j 的时间 t，再检查是否存在
 * j -> k -> j 的可行时空路径。如果不存在，则该窗口口径下 job j 不可能通过二次访问重复吃 dual。
 */
public final class RepeatVisitWindowDiagnostic {

	private RepeatVisitWindowDiagnostic() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.out.println("Usage: TWETBPC.LP.RepeatVisitWindowDiagnostic <instance.dat> [--runALNS=false] [--rootPreprocess=true]");
			return;
		}
		String instance = args[0];
		boolean runAlns = booleanArg(args, "--runALNS", false);
		boolean rootPreprocess = booleanArg(args, "--rootPreprocess", true);
		boolean zeroSetup = booleanArg(args, "--zeroSetup", false);

		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(instance, zeroSetup);
		TWETBPCConfig config = buildDiagnosticConfig(runAlns);
		Pool pool = new Pool(data);
		OutsourcingPool outsourcingPool = new OutsourcingPool(data);
		CutPool cutPool = new CutPool();
		InitialColumnBuilder builder = new InitialColumnBuilder(data, config, pool,
				new HeuristicSeedProvider(data, config));
		InitialColumnBundle initial = builder.build();
		Node root = new Node(data, initial.getInitialColumnIds(), initial.getIncumbentColumnIds(), config.pseudoCostInf);
		double incumbentCost = data.configure.bestSolution == null
				? Double.POSITIVE_INFINITY : data.configure.bestSolution.curCost;

		System.out.println("instance=" + instance);
		System.out.println("n=" + data.n + ", m=" + data.m + ", CmaxH=" + fmt(data.CmaxH)
				+ ", initialColumns=" + initial.getInitialColumnIds().size()
				+ ", incumbent=" + fmt(incumbentCost) + ", runALNS=" + runAlns + ", zeroSetup=" + zeroSetup);
		report("baseHardWindow", data, null, data.hardWindowStart, data.hardWindowEnd);

		Window initialDualWindow = null;
		LP initialLp = new LP(data, pool, cutPool, config, outsourcingPool);
		try {
			initialLp.construct(root, root.seedColumnIds);
			TWETMasterSolution solution = initialLp.solveRelaxation();
			if (solution.getStatus() == TWETMasterStatus.LP_RELAXATION) {
				initialDualWindow = buildInitialDualWindow(data, initialLp);
				report("initialRootDualWindow", data, root, initialDualWindow.start, initialDualWindow.end);
			} else {
				System.out.println("initialRootDualWindow skipped: " + solution.getMessage());
			}
		} finally {
			initialLp.closeModel();
		}

		if (rootPreprocess) {
			TimeIndexedRootPreprocessor.Result result = TimeIndexedRootPreprocessor.run(data, config, pool, root,
					incumbentCost, new BPCTraceSink() {
					}, TimeLimitChecker.NONE);
			System.out.println(result.summary());
			Window compact = buildCompactWindow(data, root);
			report("rootCompactWindow", data, root, compact.start, compact.end);
			if (initialDualWindow != null) {
				Window effective = intersect(initialDualWindow, compact);
				report("initialDualAndRootCompactWindow", data, root, effective.start, effective.end);
			}
		}
	}

	private static TWETBPCConfig buildDiagnosticConfig(boolean runAlns) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.reuseConfiguredBestSolution = false;
		config.runALNSForSeed = runAlns;
		config.useTimeIndexedGraphPricing = false;
		config.enableBidirectionalPricing = true;
		config.useGCNGBBStyleNgDssrPricing = true;
		config.enableTimeIndexedRootPreprocessingForNgDssr = true;
		config.enableRestrictedMasterIntegerHeuristic = false;
		return config;
	}

	private static Window buildInitialDualWindow(Data data, LP lp) {
		double[] start = new double[data.n + 1];
		double[] end = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			double gamma = Math.max(0.0, lp.getJobDual(job));
			double baseline = Utility.isBigMValue(data.outsourcingCost[job])
					? Utility.big_M : Math.max(0.0, data.outsourcingCost[job]);
			if (Utility.compareLt(gamma, baseline)) {
				hStart = Math.max(hStart, hWindowStart(data, job, gamma));
				hEnd = Math.min(hEnd, hWindowEnd(data, job, gamma));
			}
			start[job] = hStart;
			end[job] = hEnd;
		}
		return new Window(start, end);
	}

	private static Window buildCompactWindow(Data data, Node node) {
		double[] start = new double[data.n + 1];
		double[] end = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			if (node.hasTimeIndexedPricingWindow(job)) {
				hStart = Math.max(hStart, node.getTimeIndexedPricingWindowStart(job));
				hEnd = Math.min(hEnd, node.getTimeIndexedPricingWindowEnd(job));
			}
			start[job] = hStart;
			end[job] = hEnd;
		}
		return new Window(start, end);
	}

	private static Window intersect(Window first, Window second) {
		double[] start = new double[first.start.length];
		double[] end = new double[first.end.length];
		for (int job = 1; job < start.length; job++) {
			start[job] = Math.max(first.start[job], second.start[job]);
			end[job] = Math.min(first.end[job], second.end[job]);
		}
		return new Window(start, end);
	}

	private static void report(String name, Data data, Node node, double[] start, double[] end) {
		ArrayList<Integer> repeatable = new ArrayList<Integer>();
		ArrayList<HullOnlyWitness> hullOnly = new ArrayList<HullOnlyWitness>();
		double totalWidth = 0.0;
		double totalMinCycle = 0.0;
		int feasibleJobs = 0;
		RepeatWitness tightest = null;
		int hullRepeatableCount = 0;
		for (int job = 1; job <= data.n; job++) {
			double width = Math.max(0.0, end[job] - start[job]);
			totalWidth += width;
			double minCycle = minOrdinaryLeaveReturnDuration(data, node, job);
			if (Double.isFinite(minCycle)) {
				totalMinCycle += minCycle;
				feasibleJobs++;
			}
			HullWitness hullWitness = findHullRepeat(data, node, start, end, job);
			if (hullWitness != null) {
				hullRepeatableCount++;
			}
			RepeatWitness witness = findEarliestRepeat(data, node, start, end, job);
			if (witness != null) {
				repeatable.add(Integer.valueOf(job));
				if (tightest == null || witness.slack < tightest.slack) {
					tightest = witness;
				}
			} else if (hullWitness != null) {
				hullOnly.add(describeHullOnly(data, node, start, end, hullWitness));
			}
		}
		System.out.println(name + ": repeatableJobs=" + repeatable.size() + "/" + data.n
				+ ", hullRepeatableJobs=" + hullRepeatableCount + "/" + data.n
				+ ", hullOnlyJobs=" + hullOnly.size()
				+ ", avgWindowLen=" + fmt(totalWidth / data.n)
				+ ", avgMinLeaveReturn=" + fmt(feasibleJobs == 0 ? Double.NaN : totalMinCycle / feasibleJobs)
				+ ", repeatableList=" + repeatable);
		if (tightest != null) {
			System.out.println(name + ".tightestRepeat job=" + tightest.job + ", via=" + tightest.viaJob
					+ ", firstT=" + tightest.firstCompletion + ", viaT=" + tightest.viaCompletion
					+ ", secondT=" + tightest.secondCompletion + ", slack=" + fmt(tightest.slack));
		}
		for (int i = 0; i < Math.min(8, hullOnly.size()); i++) {
			System.out.println(name + ".hullOnly[" + i + "] " + hullOnly.get(i));
		}
	}

	private static HullWitness findHullRepeat(Data data, Node node, double[] start, double[] end, int job) {
		if (Utility.compareGt(start[job], end[job])) {
			return null;
		}
		for (int via = 1; via <= data.n; via++) {
			if (via == job || ordinaryArcForbidden(node, job, via) || ordinaryArcForbidden(node, via, job)
					|| Utility.compareGt(start[via], end[via])) {
				continue;
			}
			double firstLeg = duration(data, job, via);
			double secondLeg = duration(data, via, job);
			double lower = Math.max(start[job], Math.max(start[via] - firstLeg, start[job] - firstLeg - secondLeg));
			double upper = Math.min(end[job], Math.min(end[via] - firstLeg, end[job] - firstLeg - secondLeg));
			if (!Utility.compareGt(lower, upper)) {
				return new HullWitness(job, via, lower, upper, firstLeg, secondLeg);
			}
		}
		return null;
	}

	private static HullOnlyWitness describeHullOnly(Data data, Node node, double[] start, double[] end,
			HullWitness hull) {
		int firstStart = Math.max(0, (int) Math.ceil(hull.lower - 1e-9));
		int firstEnd = Math.min((int) Math.floor(hull.upper + 1e-9), (int) Math.ceil(data.CmaxH));
		int candidates = 0;
		int firstArcBlocked = 0;
		int secondArcBlocked = 0;
		int infeasibleTime = 0;
		for (int t = firstStart; t <= firstEnd; t++) {
			int viaCompletion = t + duration(data, hull.job, hull.viaJob);
			int secondCompletion = viaCompletion + duration(data, hull.viaJob, hull.job);
			if (!completionFeasible(data, start, end, hull.job, t)
					|| !completionFeasible(data, start, end, hull.viaJob, viaCompletion)
					|| !completionFeasible(data, start, end, hull.job, secondCompletion)) {
				infeasibleTime++;
				continue;
			}
			candidates++;
			if (timeArcForbidden(node, hull.job, hull.viaJob, t)) {
				firstArcBlocked++;
				continue;
			}
			if (timeArcForbidden(node, hull.viaJob, hull.job, viaCompletion)) {
				secondArcBlocked++;
			}
		}
		return new HullOnlyWitness(hull.job, hull.viaJob, hull.lower, hull.upper, firstStart, firstEnd,
				candidates, firstArcBlocked, secondArcBlocked, infeasibleTime,
				start[hull.job], end[hull.job], start[hull.viaJob], end[hull.viaJob]);
	}

	private static RepeatWitness findEarliestRepeat(Data data, Node node, double[] start, double[] end, int job) {
		int firstStart = Math.max(0, (int) Math.ceil(start[job] - 1e-9));
		int firstEnd = Math.min((int) Math.floor(end[job] + 1e-9), (int) Math.ceil(data.CmaxH));
		RepeatWitness best = null;
		for (int t = firstStart; t <= firstEnd; t++) {
			if (!completionFeasible(data, start, end, job, t)) {
				continue;
			}
			for (int via = 1; via <= data.n; via++) {
				if (via == job || ordinaryArcForbidden(node, job, via) || timeArcForbidden(node, job, via, t)) {
					continue;
				}
				int viaCompletion = t + duration(data, job, via);
				if (!completionFeasible(data, start, end, via, viaCompletion)) {
					continue;
				}
				if (ordinaryArcForbidden(node, via, job) || timeArcForbidden(node, via, job, viaCompletion)) {
					continue;
				}
				int secondCompletion = viaCompletion + duration(data, via, job);
				if (!completionFeasible(data, start, end, job, secondCompletion)) {
					continue;
				}
				RepeatWitness witness = new RepeatWitness(job, via, t, viaCompletion, secondCompletion,
						end[job] - secondCompletion);
				if (best == null || witness.secondCompletion < best.secondCompletion) {
					best = witness;
				}
			}
		}
		return best;
	}

	private static boolean completionFeasible(Data data, double[] start, double[] end, int job, int completion) {
		return completion >= 0
				&& !Utility.compareLt(completion, start[job])
				&& !Utility.compareGt(completion, end[job])
				&& !Utility.isBigMValue(data.penaltyFunction[job].evaluate(completion));
	}

	private static double minOrdinaryLeaveReturnDuration(Data data, Node node, int job) {
		double best = Double.POSITIVE_INFINITY;
		for (int via = 1; via <= data.n; via++) {
			if (via == job || ordinaryArcForbidden(node, job, via) || ordinaryArcForbidden(node, via, job)) {
				continue;
			}
			best = Math.min(best, duration(data, job, via) + duration(data, via, job));
		}
		return best;
	}

	private static int duration(Data data, int from, int to) {
		return (int) Math.ceil(data.getSetUp(from, to) + data.getProcessT(to) - 1e-9);
	}

	private static boolean ordinaryArcForbidden(Node node, int from, int to) {
		return node != null && (node.isArcForbidden(from, to) || node.isPricingOnlyArcForbidden(from, to));
	}

	private static boolean timeArcForbidden(Node node, int from, int to, int time) {
		return node != null && node.isTimeIndexedPricingOnlyArcForbidden(from, to, time);
	}

	private static double hWindowStart(Data data, int job, double gamma) {
		return Utility.compareGt(data.w_e[job], 0.0) ? Math.max(0.0, data.d_e[job] - gamma / data.w_e[job]) : 0.0;
	}

	private static double hWindowEnd(Data data, int job, double gamma) {
		return Utility.compareGt(data.w_t[job], 0.0) ? Math.min(data.CmaxH, data.d_l[job] + gamma / data.w_t[job])
				: data.CmaxH;
	}

	private static boolean booleanArg(String[] args, String name, boolean defaultValue) {
		for (String arg : args) {
			if (arg.startsWith(name + "=")) {
				return Boolean.parseBoolean(arg.substring(name.length() + 1));
			}
		}
		return defaultValue;
	}

	private static String fmt(double value) {
		return Double.isFinite(value) ? String.format(Locale.US, "%.3f", value) : "NA";
	}

	private static final class Window {
		final double[] start;
		final double[] end;

		Window(double[] start, double[] end) {
			this.start = start;
			this.end = end;
		}
	}

	private static final class RepeatWitness {
		final int job;
		final int viaJob;
		final int firstCompletion;
		final int viaCompletion;
		final int secondCompletion;
		final double slack;

		RepeatWitness(int job, int viaJob, int firstCompletion, int viaCompletion, int secondCompletion, double slack) {
			this.job = job;
			this.viaJob = viaJob;
			this.firstCompletion = firstCompletion;
			this.viaCompletion = viaCompletion;
			this.secondCompletion = secondCompletion;
			this.slack = slack;
		}
	}

	private static final class HullWitness {
		final int job;
		final int viaJob;
		final double lower;
		final double upper;
		final double firstLeg;
		final double secondLeg;

		HullWitness(int job, int viaJob, double lower, double upper, double firstLeg, double secondLeg) {
			this.job = job;
			this.viaJob = viaJob;
			this.lower = lower;
			this.upper = upper;
			this.firstLeg = firstLeg;
			this.secondLeg = secondLeg;
		}
	}

	private static final class HullOnlyWitness {
		final int job;
		final int viaJob;
		final double lower;
		final double upper;
		final int firstStart;
		final int firstEnd;
		final int candidates;
		final int firstArcBlocked;
		final int secondArcBlocked;
		final int infeasibleTime;
		final double jobStart;
		final double jobEnd;
		final double viaStart;
		final double viaEnd;

		HullOnlyWitness(int job, int viaJob, double lower, double upper, int firstStart, int firstEnd,
				int candidates, int firstArcBlocked, int secondArcBlocked, int infeasibleTime,
				double jobStart, double jobEnd, double viaStart, double viaEnd) {
			this.job = job;
			this.viaJob = viaJob;
			this.lower = lower;
			this.upper = upper;
			this.firstStart = firstStart;
			this.firstEnd = firstEnd;
			this.candidates = candidates;
			this.firstArcBlocked = firstArcBlocked;
			this.secondArcBlocked = secondArcBlocked;
			this.infeasibleTime = infeasibleTime;
			this.jobStart = jobStart;
			this.jobEnd = jobEnd;
			this.viaStart = viaStart;
			this.viaEnd = viaEnd;
		}

		@Override
		public String toString() {
			return "job=" + job + ", via=" + viaJob
					+ ", hullT=[" + fmt(lower) + "," + fmt(upper) + "]"
					+ ", integerT=[" + firstStart + "," + firstEnd + "]"
					+ ", candidateTimes=" + candidates
					+ ", blockedFirstArc=" + firstArcBlocked
					+ ", blockedSecondArc=" + secondArcBlocked
					+ ", infeasibleTimes=" + infeasibleTime
					+ ", jobWindow=[" + fmt(jobStart) + "," + fmt(jobEnd) + "]"
					+ ", viaWindow=[" + fmt(viaStart) + "," + fmt(viaEnd) + "]";
		}
	}
}
