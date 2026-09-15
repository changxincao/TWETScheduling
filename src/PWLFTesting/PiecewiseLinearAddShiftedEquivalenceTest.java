package PWLFTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Segment;
import Common.Utility;

/** 验证融合的平移相加与原 shiftX().add() 在函数值上严格等价。 */
public final class PiecewiseLinearAddShiftedEquivalenceTest {
	private static final double TOLERANCE = 1.0e-7;

	private PiecewiseLinearAddShiftedEquivalenceTest() {
	}

	public static void main(String[] args) {
		int cases = Integer.getInteger("twet.pwlf.addShifted.randomCases", 20_000);
		Random random = new Random(20260915L);
		for (int test = 0; test < cases; test++) {
			double domainEnd = 40.0 + random.nextInt(161);
			PiecewiseLinearFunction f = randomFunction(random, domainEnd);
			PiecewiseLinearFunction g = randomFunction(random, domainEnd);
			double delta = random.nextDouble() * domainEnd - domainEnd / 2.0;

			PiecewiseLinearFunction shifted = f.shiftX(delta);
			PiecewiseLinearFunction expected = shifted.add(g);
			PiecewiseLinearFunction actual = PiecewiseLinearFunction.addShifted(f, delta, g);
			assertEquivalent(expected, actual, test, delta);
		}
		checkSelfLoopFailsFast();
		System.out.println("addShifted equivalence passed: cases=" + cases);
	}

	private static PiecewiseLinearFunction randomFunction(Random random, double domainEnd) {
		PiecewiseLinearFunction function = new PiecewiseLinearFunction(0.0, domainEnd);
		int count = 1 + random.nextInt(12);
		double current = random.nextDouble() * domainEnd * 0.2;
		double physicalEnd = domainEnd * (0.8 + random.nextDouble() * 0.2);
		for (int index = 0; index < count; index++) {
			double end = index == count - 1
					? physicalEnd
					: current + (physicalEnd - current) * (0.05 + 0.75 * random.nextDouble());
			function.addSegment(current, end, random.nextInt(9) - 4, random.nextDouble() * 500.0 - 100.0);
			current = end;
		}
		return function;
	}

	private static void assertEquivalent(PiecewiseLinearFunction expected, PiecewiseLinearFunction actual,
			int test, double delta) {
		if (expected.isEmpty() || actual.isEmpty()) {
			if (expected.isEmpty() != actual.isEmpty()) {
				throw new AssertionError("empty mismatch at case " + test + ", delta=" + delta);
			}
			return;
		}
		assertClose(expected.head.start, actual.head.start, "start", test, delta);
		assertClose(expected.tail.end, actual.tail.end, "end", test, delta);
		List<Double> points = new ArrayList<>();
		collectBoundaries(expected, points);
		collectBoundaries(actual, points);
		Collections.sort(points);
		for (int index = 0; index < points.size(); index++) {
			double point = points.get(index);
			assertClose(expected.evaluate(point), actual.evaluate(point), "value", test, delta);
			if (index + 1 < points.size() && points.get(index + 1) > point) {
				double middle = 0.5 * (point + points.get(index + 1));
				assertClose(expected.evaluate(middle), actual.evaluate(middle), "midpoint", test, delta);
			}
		}
	}

	private static void collectBoundaries(PiecewiseLinearFunction function, List<Double> points) {
		for (Segment segment = function.head; segment != null; segment = segment.next) {
			points.add(segment.start);
			points.add(segment.end);
		}
	}

	private static void assertClose(double expected, double actual, String field, int test, double delta) {
		double scale = Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
		if (Math.abs(expected - actual) > TOLERANCE * scale) {
			throw new AssertionError(field + " mismatch at case " + test + ", delta=" + delta
					+ ", expected=" + expected + ", actual=" + actual);
		}
	}

	private static void checkSelfLoopFailsFast() {
		PiecewiseLinearFunction malformed = new PiecewiseLinearFunction(0.0, 10.0);
		malformed.addSegment(0.0, 10.0, 1.0, 0.0);
		malformed.head.next = malformed.head;
		try {
			malformed.shiftX(1.0);
			throw new AssertionError("self-loop was not rejected");
		} catch (IllegalStateException expected) {
			// 预期：损坏链表必须立即失败，不能无限遍历。
		}
	}
}
