package TWETBPC.GC;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Segment;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;

/**
 * 用 completion bound 做 node 后继子树的 reduced-cost arc elimination 诊断。
 * <p>
 * 2026-06-03: 这里和 pricing 轮内的临时 arc fixing 不同。临时 fixing 比较的是 0，
 * 只服务于“当前轮是否还能找到负 reduced-cost 列”；子树 elimination 比较的是
 * incumbent - 当前 node LP bound。若任一使用 arc (i,j) 的 relaxed reduced-cost 下界已经
 * 不小于该 gap，则包含该 arc 的列不可能改进当前上界，可以把该 arc 继承禁用到后续子节点。
 */
public final class CompletionBoundSubtreeArcEliminator {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;

	private final Data data;
	private final TWETBPCConfig config;

	public CompletionBoundSubtreeArcEliminator(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	public Result evaluate(LP lp, double incumbentCost, double nodeLowerBound) {
		if (lp == null || lp.getNode() == null) {
			return Result.skipped("empty LP");
		}
		if (!config.bidirectionalCompletionBoundSubtreeArcEliminationDiagnostic
				&& !config.bidirectionalCompletionBoundSubtreeArcElimination) {
			return Result.skipped("subtree arc elimination disabled");
		}
		CompletionBoundCalculator.Relaxation relaxation = parseRelaxation(config.bidirectionalCompletionBoundRelaxation);
		if (relaxation == null) {
			return Result.skipped("completion bound disabled");
		}
		if (!Double.isFinite(incumbentCost) || !Double.isFinite(nodeLowerBound)) {
			return Result.skipped("missing finite incumbent or node bound");
		}
		double gap = incumbentCost - nodeLowerBound;
		if (!Utility.compareGt(gap, config.branchingTolerance)) {
			return Result.skipped("node gap already closed");
		}

		long start = System.nanoTime();
		PiecewiseLinearFunction[] penalties = buildHardWindowCompletionPenalties();
		CompletionBoundCalculator calculator = new CompletionBoundCalculator(data, lp, data.CmaxH, penalties,
				penalties, null, parseQueueOrdering(config.bidirectionalCompletionBoundQueueOrdering), false);
		CompletionBoundCalculator.Result cbResult = calculator.build(relaxation);
		long buildNanos = System.nanoTime() - start;

		long scanStart = System.nanoTime();
		Result result = scanArcs(lp, cbResult.bounds, gap);
		result.gap = gap;
		result.buildNanos = buildNanos;
		result.scanNanos = System.nanoTime() - scanStart;
		result.totalNanos = System.nanoTime() - start;
		return result;
	}

	private Result scanArcs(LP lp, CompletionBoundCalculator.Bounds bounds, double gap) {
		Result result = new Result("OK");
		Node node = lp.getNode();
		double cutoff = gap + REDUCED_COST_TOLERANCE;
		for (int fromJob = 1; fromJob <= data.n; fromJob++) {
			PiecewiseLinearFunction prefix = bounds.forwardFByJob[fromJob];
			for (int toJob = 1; toJob <= data.n; toJob++) {
				if (fromJob == toJob || node.isArcForbidden(fromJob, toJob)
						|| node.getArcState(fromJob, toJob) == Node.ARC_REQUIRED) {
					continue;
				}
				result.candidates++;
				PiecewiseLinearFunction suffix = bounds.backwardBByJob[toJob];
				if (prefix == null || prefix.head == null || suffix == null || suffix.head == null) {
					result.unavailable++;
					continue;
				}
				double delay = data.getSetUp(fromJob, toJob) + data.getProcessT(toJob);
				double fixedReducedCost = data.getSetupCost(fromJob, toJob) - lp.getArcDual(fromJob, toJob);
				if (isTimeDisjoint(prefix, suffix, delay)) {
					result.remember(fromJob, toJob, true, false);
					continue;
				}
				if (isScalarEliminated(bounds, fromJob, toJob, fixedReducedCost, cutoff)) {
					result.remember(fromJob, toJob, false, true);
					continue;
				}
				PiecewiseLinearFunction shiftedPrefix = prefix.shiftX(delay);
				if (shiftedPrefix.head == null) {
					result.remember(fromJob, toJob, true, false);
					continue;
				}
				PiecewiseLinearFunction arcUseBound = shiftedPrefix.add(suffix);
				if (arcUseBound.head == null) {
					result.remember(fromJob, toJob, true, false);
					continue;
				}
				result.functionEvaluations++;
				arcUseBound.shiftYInPlace(fixedReducedCost);
				double lowerBound = arcUseBound.findMinimal(false, true)[0];
				if (!Utility.compareLt(lowerBound, cutoff)) {
					result.remember(fromJob, toJob, false, false);
				}
			}
		}
		return result;
	}

	private boolean isTimeDisjoint(PiecewiseLinearFunction prefix, PiecewiseLinearFunction suffix, double delay) {
		return Utility.compareGt(prefix.head.start + delay, suffix.tail.end);
	}

	private boolean isScalarEliminated(CompletionBoundCalculator.Bounds bounds, int fromJob, int toJob,
			double fixedReducedCost, double cutoff) {
		double prefixMin = bounds.forwardFMin(fromJob);
		double suffixMin = bounds.backwardBMin(toJob);
		if (Utility.isBigMValue(prefixMin) || Utility.isBigMValue(suffixMin)) {
			return false;
		}
		return !Utility.compareLt(prefixMin + suffixMin + fixedReducedCost, cutoff);
	}

	private PiecewiseLinearFunction[] buildHardWindowCompletionPenalties() {
		PiecewiseLinearFunction[] penalties = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			penalties[job] = cropToInterval(data.penaltyFunction[job], 0.0, data.CmaxH);
		}
		return penalties;
	}

	private PiecewiseLinearFunction cropToInterval(PiecewiseLinearFunction function, double start, double end) {
		PiecewiseLinearFunction cropped = new PiecewiseLinearFunction();
		cropped.resetDomain(start, end);
		if (function == null || function.head == null || Utility.compareGt(start, end)) {
			return cropped;
		}
		for (Segment seg = function.head; seg != null; seg = seg.next) {
			double segStart = Math.max(seg.start, start);
			double segEnd = Math.min(seg.end, end);
			if (Utility.compareLt(segStart, segEnd)) {
				cropped.addSegment(segStart, segEnd, seg.slope, seg.intercept);
			} else if (Utility.compareEq(seg.start, seg.end)
					&& !Utility.compareLt(seg.start, start) && !Utility.compareGt(seg.start, end)) {
				cropped.addSegment(seg.start, seg.end, 0.0, seg.getValue(seg.start));
			}
		}
		return cropped;
	}

	private CompletionBoundCalculator.Relaxation parseRelaxation(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim().toLowerCase(Locale.US);
		if ("allcycles".equals(normalized) || "all_cycles".equals(normalized)
				|| "all-cycles".equals(normalized) || "all".equals(normalized)) {
			return CompletionBoundCalculator.Relaxation.ALL_CYCLES;
		}
		if ("twocycle".equals(normalized) || "two_cycle".equals(normalized)
				|| "two-cycle".equals(normalized) || "2cycle".equals(normalized)
				|| "2-cycle".equals(normalized)) {
			return CompletionBoundCalculator.Relaxation.TWO_CYCLE;
		}
		return null;
	}

	private CompletionBoundCalculator.QueueOrdering parseQueueOrdering(String value) {
		if (value == null) {
			return CompletionBoundCalculator.QueueOrdering.FIFO;
		}
		String normalized = value.trim().toLowerCase(Locale.US);
		if ("reducedcost".equals(normalized) || "reduced_cost".equals(normalized)
				|| "reduced-cost".equals(normalized) || "rc".equals(normalized)) {
			return CompletionBoundCalculator.QueueOrdering.REDUCED_COST;
		}
		return CompletionBoundCalculator.QueueOrdering.FIFO;
	}

	public static final class Result {
		private final ArrayList<int[]> fixedArcs = new ArrayList<int[]>();
		private final String status;
		private long candidates;
		private long fixed;
		private long domainFixed;
		private long scalarFixed;
		private long unavailable;
		private long functionEvaluations;
		private long buildNanos;
		private long scanNanos;
		private long totalNanos;
		private double gap;

		private Result(String status) {
			this.status = status;
		}

		static Result skipped(String status) {
			return new Result(status);
		}

		void remember(int fromJob, int toJob, boolean domain, boolean scalar) {
			fixed++;
			if (domain) {
				domainFixed++;
			}
			if (scalar) {
				scalarFixed++;
			}
			fixedArcs.add(new int[] { fromJob, toJob });
		}

		public boolean isAvailable() {
			return "OK".equals(status);
		}

		public int applyTo(Node node) {
			if (node == null || fixedArcs.isEmpty()) {
				return 0;
			}
			int applied = 0;
			for (int[] arc : fixedArcs) {
				if (node.getArcState(arc[0], arc[1]) == Node.ARC_REQUIRED
						|| node.getAdjacencyPairState(arc[0], arc[1]) == Node.ADJACENCY_REQUIRED
						|| node.isArcForbidden(arc[0], arc[1])) {
					continue;
				}
				node.forbidArc(arc[0], arc[1]);
				applied++;
			}
			return applied;
		}

		public List<int[]> getFixedArcs() {
			return new ArrayList<int[]>(fixedArcs);
		}

		public long getCandidates() {
			return candidates;
		}

		public long getFixed() {
			return fixed;
		}

		public long getDomainFixed() {
			return domainFixed;
		}

		public long getScalarFixed() {
			return scalarFixed;
		}

		public long getUnavailable() {
			return unavailable;
		}

		public long getFunctionEvaluations() {
			return functionEvaluations;
		}

		public long getBuildNanos() {
			return buildNanos;
		}

		public long getScanNanos() {
			return scanNanos;
		}

		public long getTotalNanos() {
			return totalNanos;
		}

		public double getGap() {
			return gap;
		}

		public String getStatus() {
			return status;
		}

		public String summary() {
			if (!isAvailable()) {
				return status;
			}
			return String.format(Locale.US,
					"gap=%.6f candidates/fixed/domain/scalar/unavailable/funcEval=%d/%d/%d/%d/%d/%d buildMs=%.3f scanMs=%.3f",
					gap, candidates, fixed, domainFixed, scalarFixed, unavailable, functionEvaluations,
					buildNanos / 1_000_000.0, scanNanos / 1_000_000.0);
		}
	}
}
