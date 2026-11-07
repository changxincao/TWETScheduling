package TWETBPC.GC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.Segment;
import Common.Utility;
import TWETBPC.TWETBPCConfig;

/** traced join 闭端点及 Phase-I 完整物理域的独立回归，不运行完整 pricing。 */
public final class NgTracedJoinPhaseOneEndpointRegressionTest {

	private static final String OWNER = GCNGBBStyleBidirectionalNgDssr.class.getName();
	private static final Class<?> ENVELOPE = nested("TracedJoinEnvelope");
	private static final Class<?> TRACE = nested("TraceSegment");
	private static final Class<?> LABEL = nested("FunctionLabel");
	private static final Class<?> FORWARD = nested("ForwardLabel");
	private static final Class<?> BACKWARD = nested("BackwardLabel");
	private static int checks;

	private NgTracedJoinPhaseOneEndpointRegressionTest() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0 || "traced".equals(args[0])) {
			testHistoricalNegativeEndpoint();
			testLocalEndpointCases();
			testRandomClosedIntervalOracle();
		}
		if (args.length == 0 || "phase".equals(args[0])) {
			testPhaseOnePhysicalDomain();
		}
		System.out.println("NgTracedJoinPhaseOneEndpointRegressionTest passed: " + checks + " checks");
	}

	private static void testHistoricalNegativeEndpoint() throws Exception {
		// 二次审查 envelope-diagnostic.log：delta=24，t=69，旧查询14，真实端点-2。
		double[][] forward = {{27, 36, -4, 519.5}, {36, 45, -3, 483.5}, {45, 96, 0, 266.5}};
		double[][] backward = {{0, 52.5, 0, -398}, {52.5, 54, 1, -450.5},
				{54, 63, 5, -666.5}, {63, 69, 3, -486.5}};
		assertJoin("historical negative endpoint", forward, 24, backward, 11, -2, 2, 3);
	}

	private static void testLocalEndpointCases() throws Exception {
		assertJoin("opposite jumps", rows(0, 5, 0, 8, 5, 10, 0, -4), 0,
				rows(0, 5, 0, -7, 5, 10, 0, 9), 0, -11, 1, 0);
		assertJoin("backward next at exhausted forward", rows(0, 5, 0, -7), 0,
				rows(0, 5, 0, 8, 5, 10, 0, -4), 0, -11, 0, 1);
		assertJoin("continuous different sources", rows(0, 5, -1, 5, 5, 10, 1, -5), 0,
				rows(0, 5, 0, -2, 5, 10, 0, 3), 0, -2, 0, 0);
		assertJoin("same direction jumps", rows(0, 5, 0, 2, 5, 10, 0, 4), 0,
				rows(0, 5, 0, 3, 5, 10, 0, 5), 0, 5, 0, 0);
		assertJoin("equal cost keeps old sources", rows(0, 5, 0, 1, 5, 10, 0, 1), 0,
				rows(0, 5, 0, 2, 5, 10, 0, 2), 0, 3, 0, 0);
		assertJoin("single physical contact", rows(5, 10, 0, -4), 0,
				rows(0, 5, 0, -7), 0, -11, 0, 0);
		assertJoin("point input", rows(5, 5, 0, -4), 0,
				rows(0, 5, 0, 8, 5, 10, 0, -7), 0, -11, 0, 1);
		assertJoin("sub-EPS disjoint is empty", rows(0, 5, 0, -4), 0,
				rows(5 + Utility.EPS / 2, 10, 0, -7), 0, Utility.big_M, -1, -1);
		assertJoin("sub-EPS distinct ends", rows(0, 5, 0, 8, 5, 10, 0, -4), 0,
				rows(0, 5 + Utility.EPS / 2, 0, -7), 0, -11, 1, 0);
		assertJoin("next beyond real endpoint", rows(0, 5, 0, 8, 5 + Utility.EPS / 2, 10, 0, -4), 0,
				rows(0, 5, 0, -7), 0, 1, 0, 0);
	}

	/** 独立枚举所有原始闭区间对；不调用 PWLF add、evaluate 或其它最小和实现。 */
	private static void testRandomClosedIntervalOracle() throws Exception {
		Random random = new Random(7349);
		for (int trial = 0; trial < 600; trial++) {
			double[][] forward = randomSegments(random);
			double[][] backward = randomSegments(random);
			double delta = trial % 3 == 0 ? 24 : trial % 3 == 1 ? 0 : -7;
			double yShift = random.nextInt(19) - 9;
			double expected = Utility.big_M;
			for (double[] f : forward) {
				for (double[] b : backward) {
					double lo = Math.max(f[0] + delta, b[0]);
					double hi = Math.min(f[1] + delta, b[1]);
					if (lo <= hi) {
						expected = Math.min(expected, Math.min(value(f, lo - delta) + value(b, lo),
								value(f, hi - delta) + value(b, hi)) + yShift);
					}
				}
			}
			assertJoin("oracle " + trial, forward, delta, backward, yShift, expected, -2, -2);
		}
	}

	private static double[][] randomSegments(Random random) {
		double[][] segments = new double[1 + random.nextInt(5)][4];
		double start = random.nextInt(5);
		for (double[] segment : segments) {
			segment[0] = start;
			segment[1] = start + 1 + random.nextInt(12);
			segment[2] = random.nextInt(9) - 4;
			segment[3] = random.nextInt(101) - 50;
			start = segment[1];
		}
		return segments;
	}

	private static void assertJoin(String name, double[][] f, double delta, double[][] b, double yShift,
			double expected, int forwardIndex, int backwardIndex) throws Exception {
		Object[] forwardSources = sources(FORWARD, f.length);
		Object[] backwardSources = sources(BACKWARD, b.length);
		Object forward = envelope(f, forwardSources);
		Object backward = envelope(b, backwardSources);
		Method method = GCNGBBStyleBidirectionalNgDssr.class.getDeclaredMethod("findMinimalShiftedTracedSum",
				ENVELOPE, double.class, ENVELOPE, double.class);
		method.setAccessible(true);
		Object result = method.invoke(new GCNGBBStyleBidirectionalNgDssr(null, new TWETBPCConfig()),
				forward, delta, backward, yShift);
		assertClose(name, expected, (Double) get(result, "reducedCost"));
		if (forwardIndex >= -1) {
			assertTrue(name + " forward source", get(result, "forwardLabel")
					== (forwardIndex < 0 ? null : forwardSources[forwardIndex]));
			assertTrue(name + " backward source", get(result, "backwardLabel")
					== (backwardIndex < 0 ? null : backwardSources[backwardIndex]));
		} else if (expected < Utility.big_M) {
			// 返回来源必须本身能在某个真实交集上达到所报数值，不能混用来源。
			int fi = indexOf(forwardSources, get(result, "forwardLabel"));
			int bi = indexOf(backwardSources, get(result, "backwardLabel"));
			assertTrue(name + " sources exist", fi >= 0 && bi >= 0);
			double lo = Math.max(f[fi][0] + delta, b[bi][0]);
			double hi = Math.min(f[fi][1] + delta, b[bi][1]);
			assertTrue(name + " sources overlap", lo <= hi);
			assertClose(name + " source value", expected, Math.min(value(f[fi], lo - delta) + value(b[bi], lo),
					value(f[fi], hi - delta) + value(b[bi], hi)) + yShift);
		}
	}

	private static void testPhaseOnePhysicalDomain() throws Exception {
		Data data = new Data("docs/logs/data/20261105-ng-second-review/ng-second-review-20261105/input-8.dat",
				true, true);
		// 复用 EndpointProbe 的 2->6 短域传播：[36,54]+24 与 [60,69] 相交。
		data.CmaxH = 96;
		data.hardWindowStart[2] = 36;
		data.hardWindowEnd[2] = 54;
		data.hardWindowStart[6] = 27;
		data.hardWindowEnd[6] = 69;
		data.setPenaltyFunctions();
		GCNGBBStyleBidirectionalNgDssr gc = new GCNGBBStyleBidirectionalNgDssr(data, new TWETBPCConfig());
		Method penalty = GCNGBBStyleBidirectionalNgDssr.class.getDeclaredMethod("pricingPenaltyFunction", int.class);
		penalty.setAccessible(true);
		assertTrue("ordinary objective preserves function", penalty.invoke(gc, 2) == data.penaltyFunction[2]);
		Field phase = GCNGBBStyleBidirectionalNgDssr.class.getDeclaredField("feasibilityPhaseOneObjectiveMode");
		phase.setAccessible(true);
		phase.setBoolean(gc, true);
		PiecewiseLinearFunction second = (PiecewiseLinearFunction) penalty.invoke(gc, 2);
		assertFullDomain("phase job2", second, 96);
		assertClose("inside zero", 0, second.evaluate(40));
		assertTrue("left remains infeasible", Utility.isBigMValue(second.evaluate(35)));
		assertTrue("right remains infeasible", Utility.isBigMValue(second.evaluate(55)));
		PiecewiseLinearFunction source = (PiecewiseLinearFunction) penalty.invoke(gc, 0);
		assertFullDomain("phase source", source, 96);
		assertClose("source zero", 0, source.evaluate(95));
		PiecewiseLinearFunction sixth = (PiecewiseLinearFunction) penalty.invoke(gc, 6);
		PiecewiseLinearFunction forward = second.copy();
		forward.normalize(Direction.FORWARD);
		assertClose("forward physical tail", 96, forward.tail.end);
		forward.resetDomain(forward.head.start, forward.tail.end);
		PiecewiseLinearFunction joined = forward.shiftX(24).add(sixth);
		assertClose("short-domain forward continuation", 0, joined.evaluate(60));
		PiecewiseLinearFunction backward = sixth.copy();
		backward.normalize(Direction.BACKWARD);
		assertClose("backward physical head", 0, backward.head.start);
		backward.resetDomain(backward.head.start, backward.tail.end);
		assertClose("short-domain backward continuation", 0, backward.shiftX(-24).add(second).evaluate(40));
		for (double width : new double[] {0, Utility.EPS / 2}) {
			data.hardWindowStart[2] = 54;
			data.hardWindowEnd[2] = 54 + width;
			PiecewiseLinearFunction actual = (PiecewiseLinearFunction) penalty.invoke(gc, 2);
			PiecewiseLinearFunction zero = new PiecewiseLinearFunction(0, 96);
			zero.addSegment(0, 96, 0, 0);
			PiecewiseLinearFunction expected = zero.setDomain(54, 54 + width, true);
			assertFullDomain("degenerate window", actual, 96);
			for (double time : new double[] {0, 53.8, 53.95, 54, 54.05, 54.2, 96}) {
				assertClose("existing degenerate-window policy", expected.evaluate(time), actual.evaluate(time));
			}
		}
	}

	private static void assertFullDomain(String name, PiecewiseLinearFunction function, double horizon) {
		assertClose(name + " metadata start", 0, function.domainStart);
		assertClose(name + " metadata end", horizon, function.domainEnd);
		double cursor = 0;
		for (Segment segment = function.head; segment != null; segment = segment.next) {
			assertClose(name + " physical coverage", cursor, segment.start);
			assertTrue(name + " ordered segment", segment.start <= segment.end);
			cursor = segment.end;
		}
		assertClose(name + " physical end", horizon, cursor);
	}

	private static Object[] sources(Class<?> type, int count) throws Exception {
		Constructor<?> constructor = type.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		Object[] result = new Object[count];
		for (int i = 0; i < count; i++) {
			Object[] args = type == FORWARD
					? new Object[] {i, 1, null, null, null, null, null, null, null, null, 0.0, false}
					: new Object[] {i, 1, null, null, null, null, null, null, null, null, 0.0, false, false};
			result[i] = constructor.newInstance(args);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Object envelope(double[][] segments, Object[] sources) throws Exception {
		Constructor<?> constructor = ENVELOPE.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object result = constructor.newInstance();
		List<Object> target = (List<Object>) get(result, "segments");
		Constructor<?> trace = TRACE.getDeclaredConstructor(double.class, double.class, double.class, double.class, LABEL);
		trace.setAccessible(true);
		for (int i = 0; i < segments.length; i++) {
			double[] s = segments[i];
			target.add(trace.newInstance(s[0], s[1], s[2], s[3], sources[i]));
		}
		return result;
	}

	private static Class<?> nested(String name) {
		try {
			return Class.forName(OWNER + "$" + name);
		} catch (ClassNotFoundException exception) {
			throw new AssertionError(exception);
		}
	}

	private static Object get(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static double[][] rows(double... values) {
		double[][] result = new double[values.length / 4][4];
		for (int i = 0; i < result.length; i++) {
			System.arraycopy(values, 4 * i, result[i], 0, 4);
		}
		return result;
	}

	private static int indexOf(Object[] values, Object target) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == target) {
				return i;
			}
		}
		return -1;
	}

	private static double value(double[] segment, double time) {
		return segment[2] * time + segment[3];
	}

	private static void assertClose(String name, double expected, double actual) {
		assertTrue(name + ": expected=" + expected + ", actual=" + actual,
				Double.isFinite(actual) && Math.abs(expected - actual) <= Utility.EPS);
	}

	private static void assertTrue(String message, boolean condition) {
		checks++;
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
