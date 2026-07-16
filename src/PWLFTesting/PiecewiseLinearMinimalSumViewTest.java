package PWLFTesting;

import java.util.Random;

import Common.PiecewiseLinearFunction;
import Common.Utility;

/** 对拍 completion-bound 固定侧数组查询与原链表查询。 */
public final class PiecewiseLinearMinimalSumViewTest {
	private static final double TOLERANCE = 1e-6;

	private PiecewiseLinearMinimalSumViewTest() {
	}

	public static void main(String[] args) {
		verifyGappedDomains();
		int cases = Integer.getInteger("twet.pwlf.minSumView.randomCases", 500_000);
		Random random = new Random(20260716L);
		for (int test = 0; test < cases; test++) {
			double horizon = 20.0 + random.nextInt(281);
			PiecewiseLinearFunction left = randomFunction(random, horizon, 1 + random.nextInt(30));
			PiecewiseLinearFunction right = randomFunction(random, horizon, 1 + random.nextInt(30));
			double shift = random.nextDouble() * 200.0 - 100.0;
			double expected = PiecewiseLinearFunction.findMinimalSumValue(left, right, shift);
			double rightFixed = PiecewiseLinearFunction.findMinimalSumValue(
					left, right.readOnlySegmentView(), shift);
			double leftFixed = PiecewiseLinearFunction.findMinimalSumValue(
					right, left.readOnlySegmentView(), shift);
			if (!same(expected, rightFixed) || !same(expected, leftFixed)) {
				throw new AssertionError("Mismatch case=" + test + ", expected=" + expected
						+ ", rightFixed=" + rightFixed + ", leftFixed=" + leftFixed);
			}
		}
		System.out.println("minimal-sum fixed-view equivalence passed: cases=" + cases);
	}

	private static void verifyGappedDomains() {
		PiecewiseLinearFunction left = new PiecewiseLinearFunction(0.0, 20.0);
		left.addSegment(1.0, 3.0, 2.0, 5.0);
		left.addSegment(7.0, 9.0, -1.0, 20.0);
		left.addSegment(12.0, 18.0, 0.5, -3.0);
		PiecewiseLinearFunction right = new PiecewiseLinearFunction(0.0, 20.0);
		right.addSegment(3.0, 5.0, -2.0, 15.0);
		right.addSegment(8.0, 10.0, 3.0, -10.0);
		right.addSegment(15.0, 19.0, -0.25, 30.0);
		assertEquivalent(left, right, 7.5, "gapped");

		PiecewiseLinearFunction disjointLeft = new PiecewiseLinearFunction(0.0, 10.0);
		disjointLeft.addSegment(1.0, 2.0, 1.0, 0.0);
		disjointLeft.addSegment(5.0, 6.0, 1.0, 0.0);
		PiecewiseLinearFunction disjointRight = new PiecewiseLinearFunction(0.0, 10.0);
		disjointRight.addSegment(3.0, 4.0, -1.0, 0.0);
		assertEquivalent(disjointLeft, disjointRight, 0.0, "disjoint physical domains");

		PiecewiseLinearFunction empty = new PiecewiseLinearFunction(0.0, 10.0);
		assertEquivalent(disjointLeft, empty, 0.0, "empty right");
		assertEquivalent(empty, disjointRight, 0.0, "empty left");
	}

	private static void assertEquivalent(PiecewiseLinearFunction left, PiecewiseLinearFunction right,
			double shift, String context) {
		double expected = PiecewiseLinearFunction.findMinimalSumValue(left, right, shift);
		double rightFixed = PiecewiseLinearFunction.findMinimalSumValue(
				left, right.readOnlySegmentView(), shift);
		double leftFixed = PiecewiseLinearFunction.findMinimalSumValue(
				right, left.readOnlySegmentView(), shift);
		if (!same(expected, rightFixed) || !same(expected, leftFixed)) {
			throw new AssertionError("Mismatch " + context + ", expected=" + expected
					+ ", rightFixed=" + rightFixed + ", leftFixed=" + leftFixed);
		}
	}

	private static PiecewiseLinearFunction randomFunction(Random random, double horizon, int segmentCount) {
		PiecewiseLinearFunction function = new PiecewiseLinearFunction(0.0, horizon);
		double start = random.nextDouble() * horizon * 0.25;
		double end = horizon * (0.75 + random.nextDouble() * 0.25);
		double cursor = start;
		for (int segment = 0; segment < segmentCount; segment++) {
			double segmentEnd = segment == segmentCount - 1
					? end : cursor + (end - cursor) * (0.05 + random.nextDouble() * 0.9);
			double slope = random.nextInt(9) - 4;
			double intercept = random.nextDouble() < 0.08
					? Utility.big_M : random.nextDouble() * 1000.0 - 500.0;
			function.addSegment(cursor, segmentEnd, slope, intercept);
			cursor = segmentEnd;
		}
		return function;
	}

	private static boolean same(double expected, double actual) {
		if (Utility.isBigMValue(expected) || Utility.isBigMValue(actual)) {
			return Utility.isBigMValue(expected) == Utility.isBigMValue(actual);
		}
		double scale = Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
		return Math.abs(expected - actual) <= TOLERANCE * scale;
	}
}
