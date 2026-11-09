package PWLFTesting;

import java.util.Random;

import Common.EndpointPricingDiagnostics;
import Common.EndpointPricingDiagnostics.Event;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.Segment;
import Common.Utility;

/** 用原始闭区间枚举检查端点补丁，不用 add/addShifted 互相充当正确性标准。 */
public final class PiecewiseLinearEndpointPatchTest {
	private static int checks;

	private PiecewiseLinearEndpointPatchTest() { }

	public static void main(String[] args) {
		Utility.resetCurUpperBound(Utility.big_M);
		if (args.length > 0 && args[0].equals("benchmark")) {
			benchmarkContinuousAdd();
			return;
		}
		if (args.length > 0 && args[0].equals("eps-diagnostic")) {
			PiecewiseLinearFunction raw = function(10, 0, 5, 0, 8, 5, 5.000001, 0, -4,
					5.000001, 10, 0, -4).add(function(10, 0, 5, 0, -7, 5, 10, 0, 9));
			PiecewiseLinearFunction backward = raw.copy();
			backward.normalize(Direction.BACKWARD);
			System.out.println("EPS read diagnostic raw=" + raw.evaluate(5) + " backwardClosed="
					+ minimumAt(backward, 5) + " backwardEvaluate=" + backward.evaluate(5));
			return;
		}
		testTrimAndPoints();
		testLocalAddition();
		testScalarQueries();
		testFusedInsertion();
		testLegacyInsertionCounterexample();
		testPricingDiagnosticScope();
		System.out.println("PiecewiseLinearEndpointPatchTest passed: " + checks + " checks");
	}

	private static void testPricingDiagnosticScope() {
		try (EndpointPricingDiagnostics scope = EndpointPricingDiagnostics.begin()) {
			if (scope == null) {
				check(!EndpointPricingDiagnostics.enabled(), "disabled diagnostics do not open a scope");
				return;
			}
			var f = function(10, 0, 5, 0, 8, 5, 10, 0, -4);
			var g = function(10, 0, 5, 0, -7, 5, 10, 0, 9);
			f.add(g).release();
			near(PiecewiseLinearFunction.findMinimalSumValue(f, g, 0), -11, "instrumented endpoint minimum");
			check(scope.count(Event.ADD_CALL) == 1 && scope.count(Event.ADD_ISOLATED_POINT) == 1,
					"one isolated add patch");
			check(scope.count(Event.MIN_SUM_CALL) == 1 && scope.count(Event.MIN_SUM_ENDPOINT_IMPROVED) == 1,
					"one improving scalar endpoint");
			try (EndpointPricingDiagnostics nested = EndpointPricingDiagnostics.begin()) {
				function(10, -2, 0, 0, 3).shiftX(0).release();
				check(nested.count(Event.TRIM_LEFT_POINT) == 1, "one left contact");
				check(nested.count(Event.ADD_CALL) == 0, "nested scope starts empty");
			}
			check(scope.count(Event.TRIM_LEFT_POINT) == 0, "nested scope does not change its parent");
		}
		try (EndpointPricingDiagnostics scope = EndpointPricingDiagnostics.begin()) {
			check(scope.count(Event.ADD_CALL) == 0, "next pricing round starts empty");
		}
	}

	private static void benchmarkContinuousAdd() {
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, 20);
		double value = 100;
		for (int i = 0; i < 20; i++) {
			double slope = -(i % 3 + 1);
			f.addSegment(i, i + 1, slope, value - slope * i);
			value += slope;
		}
		PiecewiseLinearFunction job = function(20, 0, 10, -1, 10, 10, 20, 1, -10);
		int count = Integer.getInteger("twet.endpoint.benchmarkIterations", 100000);
		double checksum = 0;
		for (int i = 0; i < 20000; i++) { f.add(job).release(); }
		long start = System.nanoTime();
		for (int i = 0; i < count; i++) {
			PiecewiseLinearFunction sum = f.add(job);
			checksum += sum.tail.getValue(20);
			sum.release();
		}
		System.out.println("continuous-add benchmark nsPerCall=" + (System.nanoTime() - start) / (double) count
				+ " checksum=" + checksum);
	}

	private static void testTrimAndPoints() {
		PiecewiseLinearFunction left = function(10, -2, 0, 0, 3).shiftX(0);
		check(left.head != null && left.head.start == 0 && left.tail.end == 0, "trim left contact");
		near(left.evaluate(0), 3, "trim left value");
		check(function(10, -Utility.EPS / 2, -Utility.EPS / 4, 0, 3).shiftX(0).isEmpty(),
				"trim truly separated left interval");
		PiecewiseLinearFunction right = function(10, 8, 10, 0, 4).shiftX(2);
		check(right.head.start == 10 && right.tail.end == 10, "trim right contact");
		for (double time : new double[] {0, 5, 10}) {
			PiecewiseLinearFunction point = function(10, time, time, 0, 2);
			PiecewiseLinearFunction sum = point.add(function(10, 0, 10, 0, 3));
			near(sum.evaluate(time), 5, "point sum at " + time);
			check(sum.tail.end > sum.head.start, "point received as positive platform");
			structure(sum);
		}
	}

	private static void testLocalAddition() {
		double m = Utility.big_M;
		PiecewiseLinearFunction tailPoint = function(2, 0, 1, 0, 10, 1, 1, 0, 1);
		PiecewiseLinearFunction zero = function(2, 0, 2, 0, 0);
		near(tailPoint.add(zero).evaluate(1), 1, "low tail point before one-sided exhaustion");
		near(zero.add(tailPoint).evaluate(1), 1, "low tail point swapped operands");
		for (double[] pair : new double[][] {{8, -4, -7, 9}, {m, 1, 1, m},
				{1, 1, 4, 2}, {1, 4, 2, 5}}) {
			PiecewiseLinearFunction f = function(10, 0, 5, 0, pair[0], 5, 10, 0, pair[1]);
			PiecewiseLinearFunction g = function(10, 0, 5, 0, pair[2], 5, 10, 0, pair[3]);
			String beforeF = signature(f), beforeG = signature(g);
			PiecewiseLinearFunction sum = f.add(g);
			near(sum.evaluate(5), minimumAt(f, 5) + minimumAt(g, 5), "closed endpoint add");
			near(sum.evaluate(4), pair[0] + pair[2], "add left unchanged");
			near(sum.evaluate(6), pair[1] + pair[3], "add right unchanged");
			check(beforeF.equals(signature(f)) && beforeG.equals(signature(g)), "add input immutable");
			structure(sum);
		}
		PiecewiseLinearFunction small = function(10, 0, 5, 0, 8, 5, 5.000001, 0, -4,
				5.000001, 10, 0, -4);
		PiecewiseLinearFunction other = function(10, 0, 5, 0, -7, 5, 10, 0, 9);
		PiecewiseLinearFunction received = small.add(other);
		near(minimumAt(received, 5), -11, "short immediate neighbour raw closed value");
		near(received.evaluate(5), -11, "short neighbour remains readable at the restored endpoint");
		structure(received);
		received.normalize(Direction.FORWARD);
		near(received.evaluate(5), -11, "short neighbour consumed by directional minimum");
		PiecewiseLinearFunction shortLeft = function(10, 0, 0.000001, 0, 8,
				0.000001, 10, 0, -4).add(function(10, 0, 0.000001, 0, -7, 0.000001, 10, 0, 9));
		near(minimumAt(shortLeft, 0.000001), -11, "short left neighbour value");
		structure(shortLeft);
		Random random = new Random(20261107L);
		for (int i = 0; i < 500; i++) {
			PiecewiseLinearFunction f = randomFunction(random, 20, 5);
			PiecewiseLinearFunction g = randomFunction(random, 20, 5);
			PiecewiseLinearFunction sum = f.add(g);
			structure(sum);
			for (int t = 0; t <= 20; t += 4) {
				near(sum.evaluate(t), minimumAt(f, t) + minimumAt(g, t), "random add endpoint " + i);
			}
		}
	}

	private static void testScalarQueries() {
		Random random = new Random(1072026L);
		for (int i = 0; i < 1000; i++) {
			PiecewiseLinearFunction f = randomFunction(random, 20, 5);
			PiecewiseLinearFunction g = randomFunction(random, 20, 5);
			double expected = scalarOracle(f, 0, g, 2, false);
			near(PiecewiseLinearFunction.findMinimalSumValue(f, g, 2), expected, "linked scalar");
			near(PiecewiseLinearFunction.findMinimalSumValue(f, g.readOnlySegmentView(), 2), expected,
					"flat scalar");
			double delta = i % 5 - 2;
			near(PiecewiseLinearFunction.findMinimalShiftedSumValue(f, delta, g, 2),
					scalarOracle(f, delta, g, 2, true), "shifted scalar");
		}
	}

	private static void testFusedInsertion() {
		Random random = new Random(7112026L);
		PiecewiseLinearFunction.PrefixMinimumWorkspace workspace =
				new PiecewiseLinearFunction.PrefixMinimumWorkspace();
		for (int i = 0; i < 1000; i++) {
			PiecewiseLinearFunction f = randomFunction(random, 20, 4);
			PiecewiseLinearFunction job = randomFunction(random, 20, 5);
			PiecewiseLinearFunction b = randomFunction(random, 20, 4);
			f.normalize(Direction.FORWARD);
			b.normalize(Direction.BACKWARD);
			double delay = i % 4, suffixDelay = i % 3;
			double expected = insertionOracle(f, delay, job, b, suffixDelay, 2);
			double actual = PiecewiseLinearFunction.findMinimalInsertedJobCost(f, delay, job, b,
					suffixDelay, 2, workspace);
			double flat = PiecewiseLinearFunction.findMinimalInsertedJobCost(f.readOnlySegmentView(), delay,
					job.readOnlySegmentView(), b.readOnlySegmentView(), suffixDelay, 2, workspace);
			near(actual, expected, "fused linked case " + i);
			near(flat, expected, "fused flat case " + i);
		}
		PiecewiseLinearFunction f = function(10, 0, 5, 0, 8, 5, 10, 0, -4);
		PiecewiseLinearFunction job = function(10, 0, 5, 0, -7, 5, 10, 0, 9);
		PiecewiseLinearFunction b = function(10, 0, 10, 1, 0);
		near(PiecewiseLinearFunction.findMinimalInsertedJobCost(f, 0, job, b, 0, 0, workspace), -6,
				"fused isolated low point consumed at its actual time");
		PiecewiseLinearFunction zero = function(10, 0, 10, 0, 0);
		PiecewiseLinearFunction falling = function(10, 0, 5, 0, 1, 5, 10, 0, -4);
		PiecewiseLinearFunction rising = function(10, 0, 5, 0, 0, 5, 10, 0, 10);
		near(PiecewiseLinearFunction.findMinimalInsertedJobCost(zero, 0, falling, rising, 0, 0, workspace), -4,
				"new prefix pairs old suffix at shared endpoint");
		PiecewiseLinearFunction ends = function(10, 0, 5, 0, -4);
		PiecewiseLinearFunction starts = function(10, 0, 5, 0, Utility.big_M, 5, 10, 0, -7);
		near(PiecewiseLinearFunction.findMinimalInsertedJobCost(ends, 0, starts, zero, 0, 0, workspace), -11,
				"final endpoint sent without future interval");
		near(PiecewiseLinearFunction.findMinimalInsertedJobCost(ends.readOnlySegmentView(), 0,
				starts.readOnlySegmentView(), zero.readOnlySegmentView(), 0, 0, workspace), -11,
				"flat final endpoint sent without future interval");
		Utility.resetCurUpperBound(4);
		try {
			near(PiecewiseLinearFunction.findMinimalInsertedJobCost(function(10, 0, 10, 0, 8), 0,
					function(10, 0, 10, 0, 0), function(10, 0, 10, 0, 0), 0, 0, workspace), 4,
					"existing upper bound cap");
			near(PiecewiseLinearFunction.findMinimalInsertedJobCost(function(10, 0, 10, 0, 8).readOnlySegmentView(),
					0, zero.readOnlySegmentView(), zero.readOnlySegmentView(), 0, 0, workspace), 4,
					"flat existing upper bound cap");
			Utility.resetCurUpperBound(6);
			PiecewiseLinearFunction beforeEnd = function(10, 0, 5, 0, 0);
			PiecewiseLinearFunction finalDrop = function(10, 0, 5, 0, 5, 5, 10, 0, 0);
			PiecewiseLinearFunction suffixThree = function(10, 0, 10, 0, 3);
			near(PiecewiseLinearFunction.findMinimalInsertedJobCost(beforeEnd, 0, finalDrop, suffixThree,
					0, 0, workspace), 3, "last point changes upper-bound decision");
			near(PiecewiseLinearFunction.findMinimalInsertedJobCost(beforeEnd.readOnlySegmentView(), 0,
					finalDrop.readOnlySegmentView(), suffixThree.readOnlySegmentView(), 0, 0, workspace), 3,
					"flat last point changes upper-bound decision");
		} finally {
			Utility.resetCurUpperBound(Utility.big_M);
		}
	}

	private static void testLegacyInsertionCounterexample() {
		Random random = new Random(20260715L);
		for (int i = 0; i <= 123; i++) {
			double h = 80 + random.nextInt(241);
			PiecewiseLinearFunction f = legacyFunction(random, h, 1 + random.nextInt(24));
			PiecewiseLinearFunction job = legacyFunction(random, h, 1 + random.nextInt(24));
			PiecewiseLinearFunction b = legacyFunction(random, h, 1 + random.nextInt(24));
			double shift = random.nextInt(51), suffixShift = random.nextInt(51);
			double fixed = random.nextDouble() * 100 - 20;
			double cap = i % 5 == 0 ? 50 + random.nextDouble() * 500 : Utility.big_M;
			if (i == 123) {
				Utility.resetCurUpperBound(cap);
				double expected = insertionOracle(f, shift, job, b, suffixShift, fixed);
				near(expected, -185.2692555933868, "legacy case 123 independent polygon oracle");
				near(PiecewiseLinearFunction.findMinimalInsertedJobCost(f, shift, job, b, suffixShift, fixed,
						new PiecewiseLinearFunction.PrefixMinimumWorkspace()), expected, "legacy case 123 fixed");
			}
		}
		Utility.resetCurUpperBound(Utility.big_M);
	}

	private static PiecewiseLinearFunction legacyFunction(Random random, double h, int count) {
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, h);
		double current = random.nextInt(Math.max(1, (int) Math.floor(h / 5) + 1));
		double end = h - random.nextInt(Math.max(1, (int) Math.floor(h / 5) + 1));
		end = Math.max(current, end);
		for (int i = 0; i < count; i++) {
			double next = i == count - 1 ? end : current + (end - current) * (0.05 + 0.9 * random.nextDouble());
			double slope = random.nextInt(7) - 3;
			double intercept = random.nextDouble() < 0.08 ? Utility.big_M : random.nextDouble() * 800 - 300;
			f.addSegment(current, next, slope, intercept);
			current = next;
		}
		return f;
	}

	private static double scalarOracle(PiecewiseLinearFunction f, double shift,
			PiecewiseLinearFunction g, double fixed, boolean clip) {
		double best = Utility.big_M;
		for (Segment p = f.head; p != null; p = p.next) {
			for (Segment q = g.head; q != null; q = q.next) {
				double lo = Math.max(p.start + shift, q.start), hi = Math.min(p.end + shift, q.end);
				if (clip) { lo = Math.max(lo, f.domainStart); hi = Math.min(hi, f.domainEnd); }
				if (lo <= hi) {
					best = Math.min(best, Math.min(p.getValue(lo - shift) + q.getValue(lo) + fixed,
							p.getValue(hi - shift) + q.getValue(hi) + fixed));
				}
			}
		}
		return best;
	}

	/** 三原段形成(s,t)多边形，s<=t；线性目标只需枚举四角和对角线端点。 */
	static double insertionOracle(PiecewiseLinearFunction f, double shift, PiecewiseLinearFunction job,
			PiecewiseLinearFunction suffix, double suffixShift, double fixed) {
		if (f.head == null || job.head == null || suffix.head == null) { return Utility.big_M; }
		double begin = Math.max(Math.max(f.head.start + shift, f.domainStart), job.head.start);
		double end = Math.min(Math.min(f.tail.end + shift, f.domainEnd), job.tail.end);
		double best = Utility.big_M;
		for (Segment p = f.head; p != null; p = p.next) {
			for (Segment j = job.head; j != null; j = j.next) {
				double lo = Math.max(begin, Math.max(p.start + shift, j.start));
				double hi = Math.min(end, Math.min(p.end + shift, j.end));
				if (lo > hi) { continue; }
				for (Segment b = suffix.head; b != null; b = b.next) {
					double tl = Math.max(begin, Math.max(b.start - suffixShift, suffix.domainStart));
					double th = Math.min(end, Math.min(b.end - suffixShift, suffix.domainEnd));
					if (tl > th) { continue; }
					double[] ss = {lo, lo, hi, hi, Math.max(lo, tl), Math.min(hi, th)};
					double[] ts = {tl, th, tl, th, Math.max(lo, tl), Math.min(hi, th)};
					for (int k = 0; k < ss.length; k++) {
						double s = ss[k], t = ts[k];
						if (s < lo || s > hi || t < tl || t > th || s > t) { continue; }
						double value = p.getValue(s - shift) + j.getValue(s) + b.getValue(t + suffixShift) + fixed;
						best = Math.min(best, value);
					}
				}
			}
		}
		return best;
	}

	private static double minimumAt(PiecewiseLinearFunction function, double t) {
		double value = Utility.big_M;
		for (Segment s = function.head; s != null; s = s.next) {
			if (s.start <= t && t <= s.end) { value = Math.min(value, s.getValue(t)); }
		}
		return value;
	}

	private static PiecewiseLinearFunction randomFunction(Random random, double h, int count) {
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, h);
		for (int i = 0; i < count; i++) {
			f.addSegment(h * i / count, h * (i + 1) / count, random.nextInt(5) - 2, random.nextInt(31) - 15);
		}
		return f;
	}

	private static PiecewiseLinearFunction function(double horizon, double... rows) {
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, horizon);
		for (int i = 0; i < rows.length; i += 4) { f.addSegment(rows[i], rows[i + 1], rows[i + 2], rows[i + 3]); }
		return f;
	}

	private static String signature(PiecewiseLinearFunction f) {
		StringBuilder s = new StringBuilder();
		for (Segment p = f.head; p != null; p = p.next) {
			s.append(p.start).append(':').append(p.end).append(':').append(p.slope).append(':').append(p.intercept).append(';');
		}
		return s.toString();
	}

	private static void structure(PiecewiseLinearFunction f) {
		Segment previous = null;
		for (Segment s = f.head; s != null; s = s.next) {
			check(s.start < s.end, "no persistent zero-width segment");
			check(s.start >= f.domainStart && s.end <= f.domainEnd, "platform inside metadata");
			check(previous == null || previous.end == s.start, "ordered connected segment chain");
			previous = s;
		}
		check(previous == f.tail, "tail linked correctly");
	}

	private static void near(double actual, double expected, String message) {
		checks++;
		if (Utility.isBigMValue(actual) && Utility.isBigMValue(expected)) { return; }
		if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1e-6 * Math.max(1, Math.abs(expected))) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void check(boolean condition, String message) {
		checks++;
		if (!condition) { throw new AssertionError(message); }
	}
}
