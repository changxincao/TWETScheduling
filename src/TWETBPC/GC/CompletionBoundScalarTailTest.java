package TWETBPC.GC;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.TWETColumn;

/** 检查CB向上取整越出物理右端时的安全回退，以及普通/外包小数窗口的定价结果。 */
public final class CompletionBoundScalarTailTest {

	private CompletionBoundScalarTailTest() {
	}

	public static void main(String[] args) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		String group = args.length == 0 ? "all" : args[0];
		if ("all".equals(group) || "lookup".equals(group)) testLookup();
		if ("all".equals(group) || "pricing".equals(group)) testPricing(false);
		if ("all".equals(group) || "outsourcing".equals(group)) testPricing(true);
		System.out.println("CompletionBoundScalarTailTest " + group + " passed");
	}

	private static void testLookup() {
		PiecewiseLinearFunction fractional = function(17.5, 1, 17.5, -1, 0);
		PiecewiseLinearFunction late = function(17.5, 2.5, 17.5, -1, 0);
		PiecewiseLinearFunction infeasible = function(17.5, 0, 17.5, 0, Utility.big_M);
		PiecewiseLinearFunction integer = function(18, 1, 18, -1, 0);
		PiecewiseLinearFunction shorter = function(30, 1, 17.5, -1, 0);
		PiecewiseLinearFunction point = function(17.5, 17.5, 17.5, 0, -7);
		try {
			var b = cached(fractional, 17.5, -17.5);
			String before = fractional.toString();
			near(b.forwardUBeforeCeil(1, 17.4), -17.5, "fractional tail must keep negative lower bound");
			near(b.forwardUBeforeCeil(1, 17.5), -17.5, "exact fractional horizon");
			near(b.forwardUBeforeCeil(1, 16.4), -17, "interior must use original ceil entry");
			near(b.forwardUBeforeCeil(1, 17), -17, "exact interior integer");
			near(b.forwardUBeforeCeil(1, 17 + Utility.EPS / 2), -17, "near integer keeps existing snap");
			near(b.forwardUBeforeCeil(1, 17 + 2 * Utility.EPS), -17.5, "outside snap reaches tail fallback");
			check(before.equals(fractional.toString()), "query mutated shared U function");
			check(Utility.isBigMValue(b.forwardUBeforeCeil(0, 17.4)), "invalid job");
			check(Utility.isBigMValue(b.forwardUBeforeCeil(1, Double.NaN)), "nonfinite query");
			check(Utility.isBigMValue(b.forwardUBeforeCeil(1, 19)), "outside discrete grid");

			var delayed = cached(late, 17.5, -17.5);
			check(Utility.isBigMValue(delayed.forwardUBeforeCeil(1, 1.4)), "genuinely unreachable early prefix");
			var blocked = cached(infeasible, 17.5, Utility.big_M);
			check(Utility.isBigMValue(blocked.forwardUBeforeCeil(1, 17.4)), "all-M prefix remains infeasible");
			var empty = new CompletionBoundCalculator.Bounds(1, 17.5);
			check(Utility.isBigMValue(empty.forwardUBeforeCeil(1, 17.4)), "missing prefix remains unavailable");
			var shortDomain = cached(shorter, 30, -17.5);
			near(shortDomain.forwardUBeforeCeil(1, 17.4), -17.5, "physical tail need not equal global horizon");
			var wholePoint = cached(point, 17.5, -7);
			near(wholePoint.forwardUBeforeCeil(1, 17.5), -7, "whole point has finite global minimum");

			var integral = cached(integer, 18, -18);
			for (double p = 0; p <= 18; p += 0.125) {
				int q = (int) Math.ceil(p);
				near(integral.forwardUBeforeCeil(1, p), integral.forwardUBeforeByJob[1][q],
						"integer horizon lookup changed at " + p);
			}
		} finally {
			fractional.release();
			late.release();
			infeasible.release();
			integer.release();
			shorter.release();
			point.release();
		}
	}

	/** 独立按物理段填写整数缓存，不调用待验证的查询或生产建表方法。 */
	private static CompletionBoundCalculator.Bounds cached(PiecewiseLinearFunction u, double h, double minimum) {
		var b = new CompletionBoundCalculator.Bounds(1, h);
		b.forwardUByJob[1] = u;
		b.forwardUMinByJob[1] = minimum;
		b.forwardUBeforeByJob[1] = new double[b.maxDiscreteTime + 1];
		Arrays.fill(b.forwardUBeforeByJob[1], Utility.big_M);
		for (PiecewiseLinearFunction.Segment s = u.head; s != null; s = s.next) {
			for (int t = Math.max(0, (int) Math.ceil(s.start)); t <= Math.min(b.maxDiscreteTime, (int) Math.floor(s.end)); t++) {
				b.forwardUBeforeByJob[1][t] = Math.min(b.forwardUBeforeByJob[1][t], s.getValue(t));
			}
		}
		return b;
	}

	private static PiecewiseLinearFunction function(double h, double start, double end, double slope, double intercept) {
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, h);
		f.addSegment(start, end, slope, intercept);
		return f;
	}

	private static void testPricing(boolean outsourcing) throws Exception {
		Path input = Files.createTempFile("twet-cb-scalar-tail-", ".dat");
		try {
			Files.writeString(input, "3 3\n1 17 17 1 1\n1 17 17 1 1\n1 8 8 1 1\nSETUP\n"
					+ "0 0 1 1\n0 0 1 1\n0 1 0 1\n0 1 1 0\nSETUP_COST\n"
					+ "0 0 20 20\n0 0 20 20\n0 20 0 20\n0 20 20 0\n"
					+ (outsourcing ? "OUTSOURCING_COST\n0.4 0.5 0.5\n" : ""));
			for (boolean scalar : new boolean[] {false, true}) {
				Data d = new Data(input.toString(), true, true);
				TWETBPCConfig c = new TWETBPCConfig();
				c.enableBidirectionalPricing = true;
				c.useGCNGBBStyleNgDssrPricing = true;
				c.bidirectionalMidpointProbe = false;
				c.bidirectionalMidpointProbeDssrImbalanceThreshold = Double.POSITIVE_INFINITY;
				c.bidirectionalCompletionBoundRelaxation = "allCycles";
				c.bidirectionalCompletionBoundScalarPruning = scalar;
				LP lp = new LP(d, new Pool(d), new CutPool(), c, new OutsourcingPool(d));
				lp.construct(new Node(d, new ArrayList<>(), new ArrayList<>(), 0), new ArrayList<>());
				try {
					set(lp, "jobDual", new double[] {0, 0.4, 0.5, 0.5});
					set(lp, "machineDual", 0.0);
					if (outsourcing) {
						near(d.hardWindowEnd[1], 17.4, "outsourcing static job window");
						// 非root关闭dual-window，单独验证原始外包硬窗的小数右端。
						lp.getNode().depth = 1;
					}
					GCNGBBStyleBidirectionalNgDssr ng = new GCNGBBStyleBidirectionalNgDssr(d, c);
					List<TWETColumn> columns = ng.solve(lp);
					double witness = new TWETColumnEvaluator(d).evaluate(List.of(1)) - lp.getJobDual(1);
					near(witness, -0.4, "independent singleton witness");
					near(ng.getLastRelaxedRoundBestReducedCost(), witness, "negative certificate");
					check(columns.stream().anyMatch(col -> col.getSequence().equals(List.of(1))), "missing negative singleton");
					near((Double) get(ng, "pricingHorizon"), 17.5, "fractional pricing horizon");
					check((Boolean) get(ng, "dualProfitableWindowEnabled") == !outsourcing, "unexpected dual-window mode");
					System.out.println("scalarTail outsourcing=" + outsourcing + " scalar=" + scalar
							+ " cols=" + columns.size() + " certificate=" + ng.getLastRelaxedRoundBestReducedCost());
				} finally {
					lp.closeModel();
				}
			}
		} finally {
			Files.deleteIfExists(input);
		}
	}

	private static Object get(Object owner, String name) throws Exception {
		Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(owner);
	}

	private static void set(Object owner, String name, Object value) throws Exception {
		Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		f.set(owner, value);
	}

	private static void near(double actual, double expected, String message) {
		check(Math.abs(actual - expected) <= 1e-7, message + ": expected=" + expected + " actual=" + actual);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
