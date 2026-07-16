package PWLFTesting;

import java.util.Arrays;
import java.util.Random;

import Common.Configure;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.MergeResult;
import Common.PiecewiseLinearFunction.Segment;
import Common.PiecewiseLinearFunction.SegmentPool;
import Common.Utility;

/**
 * 对生产流式下包络、suffix-min workspace 和 backward normalize 做随机对拍与微基准。
 */
public final class PiecewiseLinearFunctionStreamingMergeExperiment {

	private static final double HORIZON = 100.0;
	private static final double VALUE_TOLERANCE = 1.0e-6;
	private static final int DEFAULT_RANDOM_CASES = 100_000;
	private static final int DEFAULT_BENCHMARK_ITERATIONS = 250_000;
	private static volatile double blackhole;

	private PiecewiseLinearFunctionStreamingMergeExperiment() {
	}

	public static void main(String[] args) {
		int randomCases = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_RANDOM_CASES;
		int benchmarkIterations = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_BENCHMARK_ITERATIONS;
		boolean originalStreamingMerge = Configure.useStreamingPwlfMinimumMerge;
		Utility.resetCurUpperBound(Utility.big_M);
		Configure.timeManage = false;
		Configure.debugPWLFDomainCheck = false;
		Configure.debugPWLFSegmentStats = false;
		Configure.SegmentPool = false;

		long correctnessStart = System.nanoTime();
		runStreamingMergeCorrectness(randomCases);
		runSuffixWorkspaceCorrectness(randomCases);
		long correctnessNanos = System.nanoTime() - correctnessStart;
		System.out.printf("[pwlfStreamingCorrectness] cases=%d mergeDirections=%d suffix=%d timeMs=%.3f%n",
				randomCases, randomCases * 2, randomCases, correctnessNanos / 1_000_000.0);

		benchmarkMerge(Direction.FORWARD, 5, benchmarkIterations);
		benchmarkMerge(Direction.FORWARD, 20, benchmarkIterations);
		benchmarkMerge(Direction.FORWARD, 50, Math.max(50_000, benchmarkIterations / 2));
		benchmarkMerge(Direction.BACKWARD, 5, benchmarkIterations);
		benchmarkMerge(Direction.BACKWARD, 20, benchmarkIterations);
		benchmarkMerge(Direction.BACKWARD, 50, Math.max(50_000, benchmarkIterations / 2));
		benchmarkSuffix(5, benchmarkIterations);
		benchmarkSuffix(20, benchmarkIterations);
		benchmarkSuffix(50, Math.max(50_000, benchmarkIterations / 2));
		benchmarkBackwardNormalize(5, benchmarkIterations);
		benchmarkBackwardNormalize(20, benchmarkIterations);
		benchmarkBackwardNormalize(50, Math.max(50_000, benchmarkIterations / 2));
		Configure.useStreamingPwlfMinimumMerge = originalStreamingMerge;
		System.out.printf("[pwlfStreamingExperiment.done] blackhole=%.9f%n", blackhole);
	}

	private static void runStreamingMergeCorrectness(int cases) {
		Random random = new Random(2026071601L);
		StreamingMinimumWorkspace workspace = new StreamingMinimumWorkspace();
		for (int index = 0; index < cases; index++) {
			int segments = 1 + random.nextInt(30);
			checkStreamingMerge(random, workspace, Direction.FORWARD, segments, index);
			checkStreamingMerge(random, workspace, Direction.BACKWARD, segments, index);
		}
	}

	private static void checkStreamingMerge(Random random, StreamingMinimumWorkspace workspace,
			Direction direction, int segments, int caseIndex) {
		PiecewiseLinearFunction left = randomDirectionalFunction(random, direction, segments);
		PiecewiseLinearFunction right = randomDirectionalFunction(random, direction, segments);
		PiecewiseLinearFunction rightSnapshot = right.copy();

		PiecewiseLinearFunction expected = left.copy();
		Configure.useStreamingPwlfMinimumMerge = false;
		MergeResult expectedChange = expected.mergeMinimumWithChangeHull(right, direction);
		StreamingOutcome actual = workspace.merge(left, right);
		PiecewiseLinearFunction production = left.copy();
		Configure.useStreamingPwlfMinimumMerge = true;
		MergeResult productionChange = production.mergeMinimumWithChangeHull(right, direction);
		Configure.useStreamingPwlfMinimumMerge = false;

		assertEquivalent("stream-merge " + direction + " case=" + caseIndex, expected, actual.function);
		assertEquivalent("production-stream-merge " + direction + " case=" + caseIndex, expected, production);
		assertEquivalent("stream-right-input " + direction + " case=" + caseIndex, rightSnapshot, right);
		if (expectedChange.changed != productionChange.changed) {
			throw new AssertionError("production changed mismatch direction=" + direction + " case=" + caseIndex
					+ " expected=" + expectedChange.changed + " actual=" + productionChange.changed);
		}
		if (expectedChange.changed) {
			assertClose("production changedStart " + direction + " case=" + caseIndex,
					expectedChange.changedStart, productionChange.changedStart);
			assertClose("production changedEnd " + direction + " case=" + caseIndex,
					expectedChange.changedEnd, productionChange.changedEnd);
		}
		if (expectedChange.changed != actual.changed) {
			throw new AssertionError("changed mismatch direction=" + direction + " case=" + caseIndex
					+ " expected=" + expectedChange.changed + " actual=" + actual.changed);
		}
		if (expectedChange.changed) {
			assertClose("changedStart " + direction + " case=" + caseIndex,
					expectedChange.changedStart, actual.changedStart);
			assertClose("changedEnd " + direction + " case=" + caseIndex,
					expectedChange.changedEnd, actual.changedEnd);
		}
	}

	private static void runSuffixWorkspaceCorrectness(int cases) {
		Random random = new Random(2026071602L);
		SuffixMinimumWorkspace workspace = new SuffixMinimumWorkspace();
		BackwardNormalizeWorkspace normalizeWorkspace = new BackwardNormalizeWorkspace();
		for (int index = 0; index < cases; index++) {
			PiecewiseLinearFunction input = randomRawFunction(random, 0.0, HORIZON,
					1 + random.nextInt(30));
			PiecewiseLinearFunction expected = input.copy();
			PiecewiseLinearFunction actual = input.copy();
			expected.minimizeSuffixInPlace();
			workspace.minimize(actual);
			assertEquivalent("suffix-workspace case=" + index, expected, actual);

			PiecewiseLinearFunction normalizeInput = input.copy();
			if (index % 100 == 0) {
				for (Segment segment = normalizeInput.head; segment != null; segment = segment.next) {
					segment.slope = 0.0;
					segment.intercept = Utility.big_M;
				}
			} else if ((index & 3) == 0) {
				normalizeInput.tail.slope = 0.0;
				normalizeInput.tail.intercept = Utility.big_M;
			}
			PiecewiseLinearFunction expectedNormalized = normalizeInput.copy();
			PiecewiseLinearFunction actualNormalized = normalizeInput.copy();
			expectedNormalized.normalize(Direction.BACKWARD);
			normalizeWorkspace.normalize(actualNormalized);
			assertEquivalent("backward-normalize-workspace case=" + index,
					expectedNormalized, actualNormalized);
		}
	}

	private static void benchmarkMerge(Direction direction, int segments, int iterations) {
		Random random = new Random(2026071610L + segments + 1000L * direction.ordinal());
		FunctionPair[] changedPairs = new FunctionPair[64];
		FunctionPair[] skippedPairs = new FunctionPair[64];
		int changedPairCount = 0;
		for (int i = 0; i < changedPairs.length; i++) {
			PiecewiseLinearFunction left = randomDirectionalFunction(random, direction, segments);
			PiecewiseLinearFunction right = randomDirectionalFunction(random, direction, segments);
			changedPairs[i] = new FunctionPair(left, right, direction);
			PiecewiseLinearFunction changedProbe = left.copy();
			if (changedProbe.mergeMinimumWithChangeHull(right, direction).changed) {
				changedPairCount++;
			}
			PiecewiseLinearFunction worse = left.copy();
			worse.shiftYInPlace(1000.0);
			skippedPairs[i] = new FunctionPair(left, worse, direction);
		}

		warmMerge(changedPairs, 20_000);
		warmMerge(skippedPairs, 20_000);
		MergeTiming changed = timeMerge(changedPairs, iterations);
		MergeTiming skipped = timeMerge(skippedPairs, iterations);
		System.out.printf("[pwlfStreamingMerge] direction=%s segments=%d iterations=%d changedPairs=%d/%d"
				+ " changedOldMs=%.3f changedStreamMs=%.3f changedSpeedup=%.3fx"
				+ " skippedOldMs=%.3f skippedStreamMs=%.3f skippedSpeedup=%.3fx%n",
				direction, segments, iterations, changedPairCount, changedPairs.length,
				changed.oldNanos / 1_000_000.0, changed.streamingNanos / 1_000_000.0,
				ratio(changed.oldNanos, changed.streamingNanos),
				skipped.oldNanos / 1_000_000.0, skipped.streamingNanos / 1_000_000.0,
				ratio(skipped.oldNanos, skipped.streamingNanos));
	}

	private static void warmMerge(FunctionPair[] pairs, int iterations) {
		timeMerge(pairs, iterations);
	}

	private static MergeTiming timeMerge(FunctionPair[] pairs, int iterations) {
		long oldNanos = 0L;
		long streamingNanos = 0L;
		Configure.useStreamingPwlfMinimumMerge = false;
		for (int iteration = 0; iteration < iterations; iteration++) {
			FunctionPair pair = pairs[iteration & (pairs.length - 1)];
			PiecewiseLinearFunction oldInput = pair.left.copy();
			long start = System.nanoTime();
			oldInput.mergeMinimumWithChangeHull(pair.right, pair.direction);
			oldNanos += System.nanoTime() - start;
			blackhole += endpointChecksum(oldInput);
		}
		Configure.useStreamingPwlfMinimumMerge = true;
		for (int iteration = 0; iteration < iterations; iteration++) {
			FunctionPair pair = pairs[iteration & (pairs.length - 1)];
			PiecewiseLinearFunction streamingInput = pair.left.copy();
			long start = System.nanoTime();
			streamingInput.mergeMinimumWithChangeHull(pair.right, pair.direction);
			streamingNanos += System.nanoTime() - start;
			blackhole += endpointChecksum(streamingInput);
		}
		Configure.useStreamingPwlfMinimumMerge = false;
		return new MergeTiming(oldNanos, streamingNanos);
	}
	private static void benchmarkSuffix(int segments, int iterations) {
		Random random = new Random(2026071620L + segments);
		PiecewiseLinearFunction[] inputs = new PiecewiseLinearFunction[64];
		PiecewiseLinearFunction[] mirroredInputs = new PiecewiseLinearFunction[64];
		for (int i = 0; i < inputs.length; i++) {
			inputs[i] = randomRawFunction(random, 0.0, HORIZON, segments);
			mirroredInputs[i] = mirrorTime(inputs[i], HORIZON);
		}
		warmSuffix(inputs, mirroredInputs, 20_000);
		SuffixTiming timing = timeSuffix(inputs, mirroredInputs, iterations);
		System.out.printf("[pwlfSuffixWorkspace] segments=%d iterations=%d"
				+ " prefixMirrorMs=%.3f productionSuffixMs=%.3f referenceSuffixMs=%.3f"
				+ " productionVsPrefix=%.3fx productionVsReference=%.3fx%n",
				segments, iterations, timing.prefixNanos / 1_000_000.0,
				timing.oldNanos / 1_000_000.0, timing.workspaceNanos / 1_000_000.0,
				ratio(timing.oldNanos, timing.prefixNanos), ratio(timing.oldNanos, timing.workspaceNanos));
	}

	private static void warmSuffix(PiecewiseLinearFunction[] inputs,
			PiecewiseLinearFunction[] mirroredInputs, int iterations) {
		timeSuffix(inputs, mirroredInputs, iterations);
	}

	private static SuffixTiming timeSuffix(PiecewiseLinearFunction[] inputs,
			PiecewiseLinearFunction[] mirroredInputs, int iterations) {
		SuffixMinimumWorkspace workspace = new SuffixMinimumWorkspace();
		long prefixNanos = 0L;
		long oldNanos = 0L;
		long workspaceNanos = 0L;
		for (int iteration = 0; iteration < iterations; iteration++) {
			int inputIndex = iteration & (inputs.length - 1);
			PiecewiseLinearFunction prefixFunction = mirroredInputs[inputIndex].copy();
			long start = System.nanoTime();
			prefixFunction.minimizePrefixInPlace();
			prefixNanos += System.nanoTime() - start;
			blackhole += endpointChecksum(prefixFunction);

			PiecewiseLinearFunction input = inputs[inputIndex];
			PiecewiseLinearFunction oldFunction = input.copy();
			start = System.nanoTime();
			oldFunction.minimizeSuffixInPlace();
			oldNanos += System.nanoTime() - start;
			blackhole += endpointChecksum(oldFunction);

			PiecewiseLinearFunction workspaceFunction = input.copy();
			start = System.nanoTime();
			workspace.minimize(workspaceFunction);
			workspaceNanos += System.nanoTime() - start;
			blackhole += endpointChecksum(workspaceFunction);
		}
		return new SuffixTiming(prefixNanos, oldNanos, workspaceNanos);
	}

	private static PiecewiseLinearFunction mirrorTime(PiecewiseLinearFunction function, double horizon) {
		Segment[] source = new Segment[countSegments(function)];
		int size = 0;
		for (Segment segment = function.head; segment != null; segment = segment.next) {
			source[size++] = segment;
		}
		PiecewiseLinearFunction mirrored = new PiecewiseLinearFunction(
				horizon - function.domainEnd, horizon - function.domainStart);
		for (int index = size - 1; index >= 0; index--) {
			Segment segment = source[index];
			mirrored.addSegment(horizon - segment.end, horizon - segment.start,
					-segment.slope, segment.slope * horizon + segment.intercept);
		}
		return mirrored;
	}

	private static void benchmarkBackwardNormalize(int segments, int iterations) {
		Random random = new Random(2026071630L + segments);
		PiecewiseLinearFunction[] inputs = new PiecewiseLinearFunction[64];
		PiecewiseLinearFunction[] mirroredInputs = new PiecewiseLinearFunction[64];
		for (int i = 0; i < inputs.length; i++) {
			inputs[i] = randomRawFunction(random, 0.0, HORIZON, segments);
			mirroredInputs[i] = mirrorTime(inputs[i], HORIZON);
		}
		warmBackwardNormalize(inputs, mirroredInputs, 20_000);
		NormalizeTiming timing = timeBackwardNormalize(inputs, mirroredInputs, iterations);
		System.out.printf("[pwlfBackwardNormalizeWorkspace] segments=%d iterations=%d"
				+ " forwardMirrorMs=%.3f productionBackwardMs=%.3f referenceBackwardMs=%.3f"
				+ " productionVsForward=%.3fx productionVsReference=%.3fx%n",
				segments, iterations, timing.forwardNanos / 1_000_000.0,
				timing.oldBackwardNanos / 1_000_000.0, timing.workspaceBackwardNanos / 1_000_000.0,
				ratio(timing.oldBackwardNanos, timing.forwardNanos),
				ratio(timing.oldBackwardNanos, timing.workspaceBackwardNanos));
	}

	private static void warmBackwardNormalize(PiecewiseLinearFunction[] inputs,
			PiecewiseLinearFunction[] mirroredInputs, int iterations) {
		timeBackwardNormalize(inputs, mirroredInputs, iterations);
	}

	private static NormalizeTiming timeBackwardNormalize(PiecewiseLinearFunction[] inputs,
			PiecewiseLinearFunction[] mirroredInputs, int iterations) {
		BackwardNormalizeWorkspace workspace = new BackwardNormalizeWorkspace();
		long forwardNanos = 0L;
		long oldBackwardNanos = 0L;
		long workspaceBackwardNanos = 0L;
		for (int iteration = 0; iteration < iterations; iteration++) {
			int inputIndex = iteration & (inputs.length - 1);
			PiecewiseLinearFunction forward = mirroredInputs[inputIndex].copy();
			long start = System.nanoTime();
			forward.normalize(Direction.FORWARD);
			forwardNanos += System.nanoTime() - start;
			blackhole += endpointChecksum(forward);

			PiecewiseLinearFunction oldBackward = inputs[inputIndex].copy();
			start = System.nanoTime();
			oldBackward.normalize(Direction.BACKWARD);
			oldBackwardNanos += System.nanoTime() - start;
			blackhole += endpointChecksum(oldBackward);

			PiecewiseLinearFunction workspaceBackward = inputs[inputIndex].copy();
			start = System.nanoTime();
			workspace.normalize(workspaceBackward);
			workspaceBackwardNanos += System.nanoTime() - start;
			blackhole += endpointChecksum(workspaceBackward);
		}
		return new NormalizeTiming(forwardNanos, oldBackwardNanos, workspaceBackwardNanos);
	}
	private static PiecewiseLinearFunction randomDirectionalFunction(Random random, Direction direction,
			int segments) {
		double start = direction == Direction.FORWARD ? random.nextDouble() * 20.0 : 0.0;
		double end = direction == Direction.FORWARD ? HORIZON : 80.0 + random.nextDouble() * 20.0;
		PiecewiseLinearFunction function = randomRawFunction(random, start, end, segments);
		function.normalize(direction);
		if (function.head == null || !Utility.compareLt(function.head.start, function.tail.end)) {
			return randomDirectionalFunction(random, direction, segments);
		}
		return function;
	}

	private static PiecewiseLinearFunction randomRawFunction(Random random, double start, double end, int segments) {
		PiecewiseLinearFunction function = new PiecewiseLinearFunction(start, end);
		double width = (end - start) / segments;
		double value = -100.0 + random.nextDouble() * 200.0;
		double cursor = start;
		for (int index = 0; index < segments; index++) {
			double next = index + 1 == segments ? end : start + width * (index + 1);
			double slope = -4.0 + random.nextDouble() * 8.0;
			double intercept = value - slope * cursor;
			function.addSegment(cursor, next, slope, intercept);
			value = slope * next + intercept;
			cursor = next;
		}
		return function;
	}

	private static void assertEquivalent(String context, PiecewiseLinearFunction expected,
			PiecewiseLinearFunction actual) {
		if ((expected.head == null) != (actual.head == null)) {
			throw new AssertionError(context + " empty mismatch");
		}
		if (expected.head == null) {
			return;
		}
		double[] points = collectProbePoints(expected, actual);
		for (double point : points) {
			double expectedValue = valueAt(expected, point);
			double actualValue = valueAt(actual, point);
			assertClose(context + " t=" + point, expectedValue, actualValue);
		}
	}

	private static double[] collectProbePoints(PiecewiseLinearFunction first, PiecewiseLinearFunction second) {
		double[] points = new double[4 * (countSegments(first) + countSegments(second)) + 4];
		int size = 0;
		for (Segment segment = first.head; segment != null; segment = segment.next) {
			points[size++] = segment.start;
			points[size++] = segment.end;
			points[size++] = (segment.start + segment.end) * 0.5;
		}
		for (Segment segment = second.head; segment != null; segment = segment.next) {
			points[size++] = segment.start;
			points[size++] = segment.end;
			points[size++] = (segment.start + segment.end) * 0.5;
		}
		Arrays.sort(points, 0, size);
		return Arrays.copyOf(points, size);
	}

	private static int countSegments(PiecewiseLinearFunction function) {
		int count = 0;
		for (Segment segment = function.head; segment != null; segment = segment.next) {
			count++;
		}
		return count;
	}

	private static double valueAt(PiecewiseLinearFunction function, double time) {
		for (Segment segment = function.head; segment != null; segment = segment.next) {
			if (!Utility.compareLt(time, segment.start) && !Utility.compareGt(time, segment.end)) {
				return segment.getValue(time);
			}
		}
		return Utility.big_M;
	}

	private static void assertClose(String context, double expected, double actual) {
		double scale = Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
		if (Math.abs(expected - actual) > VALUE_TOLERANCE * scale) {
			throw new AssertionError(context + " expected=" + expected + " actual=" + actual);
		}
	}

	private static double endpointChecksum(PiecewiseLinearFunction function) {
		if (function == null || function.head == null) {
			return 0.0;
		}
		return function.head.getValue(function.head.start) + function.tail.getValue(function.tail.end);
	}

	private static double ratio(long numerator, long denominator) {
		return denominator == 0L ? Double.POSITIVE_INFINITY : (double) numerator / denominator;
	}

	private static final class FunctionPair {
		final PiecewiseLinearFunction left;
		final PiecewiseLinearFunction right;
		final Direction direction;

		FunctionPair(PiecewiseLinearFunction left, PiecewiseLinearFunction right, Direction direction) {
			this.left = left;
			this.right = right;
			this.direction = direction;
		}
	}

	private static final class MergeTiming {
		final long oldNanos;
		final long streamingNanos;

		MergeTiming(long oldNanos, long streamingNanos) {
			this.oldNanos = oldNanos;
			this.streamingNanos = streamingNanos;
		}
	}

	private static final class SuffixTiming {
		final long prefixNanos;
		final long oldNanos;
		final long workspaceNanos;

		SuffixTiming(long prefixNanos, long oldNanos, long workspaceNanos) {
			this.prefixNanos = prefixNanos;
			this.oldNanos = oldNanos;
			this.workspaceNanos = workspaceNanos;
		}
	}

	private static final class NormalizeTiming {
		final long forwardNanos;
		final long oldBackwardNanos;
		final long workspaceBackwardNanos;

		NormalizeTiming(long forwardNanos, long oldBackwardNanos, long workspaceBackwardNanos) {
			this.forwardNanos = forwardNanos;
			this.oldBackwardNanos = oldBackwardNanos;
			this.workspaceBackwardNanos = workspaceBackwardNanos;
		}
	}
	private static final class StreamingOutcome {
		final PiecewiseLinearFunction function;
		final boolean changed;
		final double changedStart;
		final double changedEnd;

		StreamingOutcome(PiecewiseLinearFunction function, boolean changed, double changedStart, double changedEnd) {
			this.function = function;
			this.changed = changed;
			this.changedStart = changedStart;
			this.changedEnd = changedEnd;
		}
	}

	/** 复用 primitive scratch；确认有改善后才物化 Segment 链。 */
	private static final class StreamingMinimumWorkspace {
		private double[] segments = new double[64];
		private int size;
		private boolean changed;
		private double changedStart;
		private double changedEnd;

		StreamingOutcome merge(PiecewiseLinearFunction left, PiecewiseLinearFunction right) {
			if (left == null || left.head == null) {
				PiecewiseLinearFunction copied = right == null ? new PiecewiseLinearFunction() : right.copy();
				return new StreamingOutcome(copied, copied.head != null,
						copied.head == null ? Double.POSITIVE_INFINITY : copied.head.start,
						copied.tail == null ? Double.NEGATIVE_INFINITY : copied.tail.end);
			}
			if (right == null || right.head == null) {
				return new StreamingOutcome(left, false, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
			}
			double overlapStart = Math.max(left.head.start, right.head.start);
			double overlapEnd = Math.min(left.tail.end, right.tail.end);
			if (!Utility.compareLt(overlapStart, overlapEnd)) {
				throw new IllegalArgumentException("streaming minimum requires positive overlap");
			}

			size = 0;
			changed = false;
			changedStart = Double.POSITIVE_INFINITY;
			changedEnd = Double.NEGATIVE_INFINITY;
			Segment p = left.head;
			Segment q = right.head;
			double cursor = Math.min(p.start, q.start);
			double unionEnd = Math.max(left.tail.end, right.tail.end);
			while (Utility.compareLt(cursor, unionEnd)) {
				while (p != null && !Utility.compareGt(p.end, cursor)) {
					p = p.next;
				}
				while (q != null && !Utility.compareGt(q.end, cursor)) {
					q = q.next;
				}
				boolean pActive = p != null && !Utility.compareGt(p.start, cursor);
				boolean qActive = q != null && !Utility.compareGt(q.start, cursor);
				double pEvent = p == null ? Double.POSITIVE_INFINITY : (pActive ? p.end : p.start);
				double qEvent = q == null ? Double.POSITIVE_INFINITY : (qActive ? q.end : q.start);
				double next = Math.min(unionEnd, Math.min(pEvent, qEvent));
				if (!Utility.compareLt(cursor, next)) {
					throw new IllegalStateException("non-positive streaming interval at " + cursor);
				}
				if (pActive && qActive) {
					appendMinimum(cursor, next, p, q);
				} else if (pActive) {
					append(cursor, next, p.slope, p.intercept);
				} else if (qActive) {
					append(cursor, next, q.slope, q.intercept);
					markChanged(cursor, next);
				} else {
					throw new IllegalArgumentException("gap in streaming minimum at " + cursor);
				}
				cursor = next;
			}

			if (!changed) {
				return new StreamingOutcome(left, false, changedStart, changedEnd);
			}
			PiecewiseLinearFunction result = new PiecewiseLinearFunction(left.domainStart, left.domainEnd);
			for (int index = 0; index < size; index++) {
				int offset = index << 2;
				result.addSegment(segments[offset], segments[offset + 1], segments[offset + 2], segments[offset + 3]);
			}
			return new StreamingOutcome(result, true, changedStart, changedEnd);
		}

		private void appendMinimum(double start, double end, Segment left, Segment right) {
			double leftStart = left.getValue(start);
			double leftEnd = left.getValue(end);
			double rightStart = right.getValue(start);
			double rightEnd = right.getValue(end);
			boolean leftNoWorse = Utility.compareLe(leftStart, rightStart)
					&& Utility.compareLe(leftEnd, rightEnd);
			boolean rightNoWorse = Utility.compareLe(rightStart, leftStart)
					&& Utility.compareLe(rightEnd, leftEnd);
			if (leftNoWorse) {
				append(start, end, left.slope, left.intercept);
				return;
			}
			if (rightNoWorse) {
				append(start, end, right.slope, right.intercept);
				markChanged(start, end);
				return;
			}
			double denominator = left.slope - right.slope;
			if (Utility.compareEq(denominator, 0.0)) {
				throw new IllegalStateException("parallel segments classified as crossing");
			}
			double crossing = (right.intercept - left.intercept) / denominator;
			crossing = Math.max(start, Math.min(end, crossing));
			if (Utility.compareLt(leftStart, rightStart)) {
				append(start, crossing, left.slope, left.intercept);
				append(crossing, end, right.slope, right.intercept);
				markChanged(crossing, end);
			} else {
				append(start, crossing, right.slope, right.intercept);
				markChanged(start, crossing);
				append(crossing, end, left.slope, left.intercept);
			}
		}

		private void append(double start, double end, double slope, double intercept) {
			if (!Utility.compareLt(start, end)) {
				return;
			}
			if (size > 0) {
				int previous = (size - 1) << 2;
				if (Utility.compareEq(segments[previous + 1], start)
						&& Utility.compareEq(segments[previous + 2], slope)
						&& Utility.compareEq(segments[previous + 3], intercept)) {
					segments[previous + 1] = end;
					return;
				}
			}
			ensureCapacity(size + 1);
			int offset = size << 2;
			segments[offset] = start;
			segments[offset + 1] = end;
			segments[offset + 2] = slope;
			segments[offset + 3] = intercept;
			size++;
		}

		private void markChanged(double start, double end) {
			if (!Utility.compareLt(start, end)) {
				return;
			}
			changed = true;
			changedStart = Math.min(changedStart, start);
			changedEnd = Math.max(changedEnd, end);
		}

		private void ensureCapacity(int requiredSegments) {
			int required = requiredSegments << 2;
			if (required > segments.length) {
				segments = Arrays.copyOf(segments, Math.max(required, segments.length << 1));
			}
		}
	}

	/** 与生产 suffix-min 算法一致，只把每次 ArrayList 改成复用引用数组。 */
	/** 合并 backward 的尾部裁剪、相邻段压缩和 suffix-min；仅供独立实验。 */
	private static final class BackwardNormalizeWorkspace {
		private final SuffixMinimumWorkspace suffixWorkspace = new SuffixMinimumWorkspace();

		void normalize(PiecewiseLinearFunction function) {
			if (function.head == null) {
				function.tail = null;
				return;
			}
			Segment write = function.head;
			Segment lastNonBigM = Utility.isBigMValue(write.intercept) ? null : write;
			Segment read = write.next;
			while (read != null) {
				Segment next = read.next;
				if (Utility.compareEq(write.slope, read.slope)
						&& Utility.compareEq(write.intercept, read.intercept)) {
					write.end = read.end;
				} else {
					write.next = read;
					write = read;
				}
				if (!Utility.isBigMValue(write.intercept)) {
					lastNonBigM = write;
				}
				read = next;
			}
			write.next = null;
			if (lastNonBigM == null) {
				function.head = function.tail = null;
				return;
			}
			lastNonBigM.next = null;
			function.tail = lastNonBigM;
			suffixWorkspace.minimize(function);
			compact(function);
		}

		private void compact(PiecewiseLinearFunction function) {
			if (function.head == null) {
				function.tail = null;
				return;
			}
			Segment segment = function.head;
			while (segment.next != null) {
				if (Utility.compareEq(segment.slope, segment.next.slope)
						&& Utility.compareEq(segment.intercept, segment.next.intercept)
						&& Utility.compareEq(segment.end, segment.next.start)) {
					segment.end = segment.next.end;
					segment.next = segment.next.next;
				} else {
					segment = segment.next;
				}
			}
			function.tail = segment;
		}
	}
	private static final class SuffixMinimumWorkspace {
		private Segment[] segments = new Segment[16];
		private Segment builtTail;

		void minimize(PiecewiseLinearFunction function) {
			if (function.head == null) {
				return;
			}
			int size = 0;
			for (Segment segment = function.head; segment != null; segment = segment.next) {
				if (size == segments.length) {
					segments = Arrays.copyOf(segments, segments.length << 1);
				}
				segments[size++] = segment;
			}
			Segment nextSegment = null;
			builtTail = null;
			double runningMin = Utility.curUpperBound;
			double lastTime = function.tail.end;
			for (int index = size - 1; index >= 0; index--) {
				Segment segment = segments[index];
				double start = segment.start;
				double end = segment.end;
				double slope = segment.slope;
				double intercept = segment.intercept;
				double startValue = slope * start + intercept;
				double endValue = slope * end + intercept;
				if (Utility.compareLe(slope, 0.0)) {
					if (Utility.compareLe(runningMin, endValue)) {
						continue;
					}
					if (!Utility.compareEq(lastTime, end)) {
						nextSegment = prepend(SegmentPool.obtain(end, lastTime, 0.0, runningMin), nextSegment);
						lastTime = end;
					}
					runningMin = endValue;
				} else {
					if (Utility.compareLe(runningMin, startValue)) {
						continue;
					}
					if (Utility.compareGt(endValue, runningMin)) {
						double crossing = (runningMin - intercept) / slope;
						if (Utility.compareGt(lastTime, crossing)
								&& !Utility.compareEq(runningMin, Utility.curUpperBound)) {
							nextSegment = prepend(SegmentPool.obtain(crossing, lastTime, 0.0, runningMin),
									nextSegment);
						}
						segment.end = crossing;
						nextSegment = prepend(segment, nextSegment);
						runningMin = startValue;
						lastTime = start;
						continue;
					}
					if (!Utility.compareEq(lastTime, end)) {
						nextSegment = prepend(SegmentPool.obtain(end, lastTime, 0.0, runningMin), nextSegment);
					}
					nextSegment = prepend(segment, nextSegment);
					runningMin = startValue;
					lastTime = start;
				}
			}
			double firstStart = function.head.start;
			if (Utility.compareLt(firstStart, lastTime)) {
				nextSegment = prepend(SegmentPool.obtain(firstStart, lastTime, 0.0, runningMin), nextSegment);
			}
			if (Utility.compareGe(lastTime, firstStart) && nextSegment == null) {
				nextSegment = prepend(SegmentPool.obtain(firstStart, lastTime, 0.0, runningMin), nextSegment);
			}
			function.head = nextSegment;
			function.tail = builtTail;
			Arrays.fill(segments, 0, size, null);
		}

		private Segment prepend(Segment segment, Segment next) {
			segment.next = next;
			if (builtTail == null) {
				builtTail = segment;
			}
			return segment;
		}
	}
}
