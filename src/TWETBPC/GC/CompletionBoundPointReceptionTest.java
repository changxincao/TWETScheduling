package TWETBPC.GC;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.Pool;

/** 独立检查 CB 局部点接收和首任务 H 点，不改变生产定价集合。 */
public final class CompletionBoundPointReceptionTest {
	private CompletionBoundPointReceptionTest() {
	}

	public static void main(String[] args) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		boolean localOnly = args.length > 0 && args[0].equals("local");
		Path input = Files.createTempFile("twet-point-reception-", ".dat");
		try {
			// 沿用二次审查原两任务反例，Data 自然生成 [15,17]、[14,16]。
			Files.writeString(input, "2 1\n1 16 16 1 1\n1 15 15 1 1\nSETUP\n"
					+ "0 0 0\n0 0 0\n0 0 0\nOUTSOURCING_COST\n1 1\n");
			Data data = new Data(input.toString(), true, true);
			testFirstTask(data);
			testCandidates(data, !localOnly);
			if (!localOnly) {
				testOriginalCounterexample(data);
			}
			System.out.println("CompletionBoundPointReceptionTest " + (localOnly ? "local" : "all") + " passed");
		} finally {
			Files.deleteIfExists(input);
		}
	}

	private static void testFirstTask(Data data) {
		TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
		double process = data.p[1];
		double setupCost = data.setupCost[0][1];
		PiecewiseLinearFunction penalty = data.penaltyFunction[1];
		String before = penalty.toString();
		try {
			data.p[1] = data.CmaxH;
			data.setupCost[0][1] = 3.0;
			near(evaluator.evaluate(List.of(1)), 4.0, "first H point plus setup");
			check(before.equals(penalty.toString()), "first task changed original penalty");
			data.p[1] = data.CmaxH + Utility.EPS / 2;
			check(Utility.isBigMValue(evaluator.evaluate(List.of(1))), "a > H must not be rescued");
			data.p[1] = data.CmaxH - 1;
			near(evaluator.evaluate(List.of(1)), 3.0, "ordinary first task");
			data.p[1] = data.CmaxH;
			for (double value : new double[] { Utility.big_M, Double.NaN, Double.NEGATIVE_INFINITY }) {
				data.penaltyFunction[1] = function(data.CmaxH, 0, data.CmaxH, value);
				check(Utility.isBigMValue(evaluator.evaluate(List.of(1))), "nonfinite/M endpoint");
				data.penaltyFunction[1].release();
			}
			data.penaltyFunction[1] = function(data.CmaxH, 0, data.CmaxH - Utility.EPS / 2, 1);
			check(Utility.isBigMValue(evaluator.evaluate(List.of(1))), "separated penalty is not H point");
			data.penaltyFunction[1].release();
		} finally {
			data.penaltyFunction[1] = penalty;
			data.p[1] = process;
			data.setupCost[0][1] = setupCost;
		}
	}

	private static void testCandidates(Data data, boolean includeBoundary) throws Exception {
		double h = data.CmaxH;
		double oldForwardSetup = data.setupCost[1][2];
		double oldBackwardSetup = data.setupCost[2][1];
		data.setupCost[1][2] = 4;
		data.setupCost[2][1] = 5;
		LP lp = lp(data, 1.25, 2);
		PiecewiseLinearFunction[] costs = { null, function(h, 0, h, 11), function(h, 0, h, 11) };
		CompletionBoundCalculator calculator = new CompletionBoundCalculator(data, lp, h, costs, costs, null,
				CompletionBoundCalculator.QueueOrdering.FIFO, false);
		try {
			for (boolean interval : new boolean[] { false, true }) {
				PiecewiseLinearFunction parent;
				Object forward;
				Object backward;
				PiecewiseLinearFunction u;
				PiecewiseLinearFunction r;
				if (includeBoundary) {
				parent = function(h, h - 1, h, 7);
				String original = parent.toString();
				forward = candidate(calculator, true, parent, 1, 2, interval);
				verifyPair(forward, h, 9.75, 18.75);
				u = component(forward, "u");
				check(u.head.start >= h - 0.0005 - Utility.EPS && u.tail.end == h,
						"H auxiliary must use local add platform, not backward waiting");
				check(original.equals(parent.toString()), "forward parent mutated");
				releasePair(forward);
				parent.release();

				parent = function(h, 0, 1, 7);
				original = parent.toString();
				backward = candidate(calculator, false, parent, 2, 1, interval);
				verifyPair(backward, 0, 10.75, 19.75);
				r = component(backward, "u");
				check(r.head.start == 0 && r.tail.end <= 0.0005 + Utility.EPS,
						"0 auxiliary must use local add platform, not forward waiting");
				check(original.equals(parent.toString()), "backward parent mutated");
				releasePair(backward);
				parent.release();
				}

				parent = function(h, 5, 5, 7);
				forward = candidate(calculator, true, parent, 1, 2, interval);
				verifyPair(forward, 6, 9.75, 18.75);
				u = component(forward, "u");
				check(u.head.start == 6 && u.tail.end == h, "forward waiting direction");
				releasePair(forward);
				parent.release();

				parent = function(h, 7, 7, 7);
				backward = candidate(calculator, false, parent, 2, 1, interval);
				verifyPair(backward, 6, 10.75, 19.75);
				r = component(backward, "u");
				check(r.head.start == 0 && r.tail.end == 6, "backward waiting direction");
				releasePair(backward);
				parent.release();
			}
			for (double value : new double[] { Utility.big_M, Double.NaN, Double.POSITIVE_INFINITY }) {
				PiecewiseLinearFunction parent = function(h, h - 1, h, value);
				check(candidate(calculator, true, parent, 1, 2, false) == null, "reject nonfinite/M point");
				parent.release();
			}
			PiecewiseLinearFunction parent = function(h, h - 1 + Utility.EPS / 2, h, 7);
			check(candidate(calculator, true, parent, 1, 2, false) == null, "separated shift is not a point");
			parent.release();
			for (double[] span : new double[][] { { 3, 3 + Utility.EPS / 2 }, { h + Utility.EPS / 2, h } }) {
				PiecewiseLinearFunction narrow = function(h, span[0], span[1], 7);
				check(!finitePoint(calculator, narrow), "narrow/inverted support is not a whole point");
				narrow.release();
			}
			PiecewiseLinearFunction empty = new PiecewiseLinearFunction(0, h);
			check(!finitePoint(calculator, empty), "empty is not a point");
			empty.release();
			PiecewiseLinearFunction outside = function(h, h + Utility.EPS / 2, h + Utility.EPS / 2, 7);
			check(!finitePoint(calculator, outside), "metadata-separated point");
			outside.release();

			PiecewiseLinearFunction[] blocked = { null, costs[1], function(h, 0, h, Utility.big_M) };
			CompletionBoundCalculator rejecting = new CompletionBoundCalculator(data, lp, h, blocked, blocked,
					null, CompletionBoundCalculator.QueueOrdering.FIFO, false);
			parent = function(h, h - 1, h, 7);
			check(candidate(rejecting, true, parent, 1, 2, false) == null, "job hard window still enforced");
			parent.release();
			blocked[2].release();
		} finally {
			data.setupCost[1][2] = oldForwardSetup;
			data.setupCost[2][1] = oldBackwardSetup;
			costs[1].release();
			costs[2].release();
			lp.closeModel();
		}
	}

	private static void testOriginalCounterexample(Data data) throws Exception {
		near(new TWETColumnEvaluator(data).evaluate(List.of(1, 2)), 2, "original evaluator counterexample");
		LP lp = lp(data, 0, 2);
		try {
			CompletionBoundCalculator calculator = new CompletionBoundCalculator(data, lp, data.CmaxH,
					data.penaltyFunction, data.penaltyFunction, null, CompletionBoundCalculator.QueueOrdering.FIFO,
					false);
			PiecewiseLinearFunction source = function(data.CmaxH, 0, data.CmaxH, 3);
			Object first = candidate(calculator, true, source, 0, 1, false);
			check(first != null, "original first candidate");
			Object second = candidate(calculator, true, component(first, "f"), 1, 2, false);
			check(second != null, "original contact at 16");
			near(component(second, "f").evaluate(16), 1, "original schedule cost plus source minus duals");
			releasePair(second);
			releasePair(first);
			source.release();
		} finally {
			lp.closeModel();
		}
	}

	/** 仅固定本地候选的对偶，不启动求解器或改变弧集合。 */
	private static LP lp(Data data, double arcDual, double jobDual) {
		LP lp = new LP(data, new Pool(data), new CutPool()) {
			@Override
			public double getArcDual(int from, int to) {
				return arcDual;
			}

			@Override
			public double getJobDual(int job) {
				return jobDual;
			}
		};
		lp.construct(new Node(data, new ArrayList<>(), new ArrayList<>(), 0), new ArrayList<>());
		return lp;
	}

	private static Object candidate(CompletionBoundCalculator calculator, boolean forward,
			PiecewiseLinearFunction parent, int job, int other, boolean interval) throws Exception {
		Method method = CompletionBoundCalculator.class.getDeclaredMethod(
				forward ? "buildForwardCandidate" : "buildBackwardCandidate", PiecewiseLinearFunction.class,
				int.class, int.class, boolean.class);
		method.setAccessible(true);
		return method.invoke(calculator, parent, job, other, interval);
	}

	private static boolean finitePoint(CompletionBoundCalculator calculator, PiecewiseLinearFunction function)
			throws Exception {
		Method method = CompletionBoundCalculator.class.getDeclaredMethod("isFiniteWholePoint",
				PiecewiseLinearFunction.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(calculator, function);
	}

	private static PiecewiseLinearFunction component(Object pair, String name) throws Exception {
		Field field = pair.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (PiecewiseLinearFunction) field.get(pair);
	}

	private static void verifyPair(Object pair, double time, double auxiliary, double complete) throws Exception {
		check(pair != null, "finite candidate was rejected");
		for (String name : new String[] { "u", "f" }) {
			PiecewiseLinearFunction function = component(pair, name);
			check(function.head != null && function.tail != null
					&& Utility.compareLt(function.head.start, function.tail.end), "positive output " + name);
			near(function.evaluate(time), name.equals("u") ? auxiliary : complete, "cost " + name);
		}
	}

	private static void releasePair(Object pair) throws Exception {
		component(pair, "u").release();
		component(pair, "f").release();
	}

	private static PiecewiseLinearFunction function(double horizon, double start, double end, double value) {
		PiecewiseLinearFunction result = new PiecewiseLinearFunction(0, horizon);
		result.addSegment(start, end, 0, value);
		return result;
	}

	private static void near(double actual, double expected, String message) {
		check(Double.isFinite(actual) && Math.abs(actual - expected) <= Utility.EPS,
				message + ": expected=" + expected + ", actual=" + actual);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
