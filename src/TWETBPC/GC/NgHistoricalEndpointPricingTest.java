package TWETBPC.GC;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.TWETBPCConfig;

/** 自包含重放原两任务漏列、六任务Phase-I误裁，用独立整数DP核对CB。 */
public final class NgHistoricalEndpointPricingTest {
	private static int checks;

	private NgHistoricalEndpointPricingTest() { }

	public static void main(String[] args) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		Path tiny = Files.createTempFile("ng-endpoint-two-", ".dat");
		Path six = Files.createTempFile("ng-endpoint-six-", ".dat");
		String nativeBefore = System.getProperty("twet.bpc.completionBoundNativeIntervalDelta");
		String multiBefore = System.getProperty("twet.bpc.completionBoundMultiDeltaPropagation");
		try {
			Files.writeString(tiny, "2 1\n1 16 16 1 1\n1 15 15 1 1\nSETUP\n"
					+ "0 0 0\n0 0 0\n0 0 0\nOUTSOURCING_COST\n1 1\n");
			Data small = new Data(tiny.toString(), true, true);
			near(new TWETColumnEvaluator(small).evaluate(List.of(1, 2)), 2, "two-job true cost");
			for (boolean phase : new boolean[] {false, true}) {
				for (boolean cb : new boolean[] {false, true}) {
					TWETBPCConfig config = config(cb);
					Node node = new Node(small, new ArrayList<>(), new ArrayList<>(), 0);
					node.forbidArc(0, 2); node.forbidArc(1, node.sinkId());
					node.forbidOutsourcingJob(1); node.forbidOutsourcingJob(2);
					LP lp = new LP(small, new Pool(small), new CutPool(), config, new OutsourcingPool(small));
					lp.construct(node, new ArrayList<>());
					try {
						lp.setFeasibilityPhaseOneObjectiveMode(phase);
						set(lp, "machineDual", phase ? -3.0 : -1.0);
						set(lp, "jobDual", new double[] {0, 2, 2});
						GCNGBBStyleBidirectionalNgDssr engine = new GCNGBBStyleBidirectionalNgDssr(small, config);
						check(!engine.solve(lp, () -> false).isEmpty(), "two-job negative route returned");
						near(engine.getLastRelaxedRoundBestReducedCost(), -1, "two-job certificate");
					} finally { lp.closeModel(); }
				}
			}
			Files.writeString(six, sixJobFixture());
			Data data = new Data(six.toString(), true, true);
			for (int j = 0; j <= data.n; j++) {
				data.hardWindowStart[j] = 0; data.hardWindowEnd[j] = data.CmaxH;
				Arrays.fill(data.preprocessedArcForbidden[j], false);
			}
			for (int j = 1; j <= data.n; j++) {
				data.hardWindowStart[j] = Math.max(data.p[j], data.d_e[j] - 18);
				data.hardWindowEnd[j] = Math.min(data.CmaxH, data.d_l[j] + 24);
			}
			data.setPenaltyFunctions();
			Random random = new Random(7349);
			double[] dual = new double[data.n + 1];
			double[][] arc = new double[data.n + 2][data.n + 2];
			for (int j = 1; j <= data.n; j++) { dual[j] = 10 + random.nextInt(30); }
			for (int i = 0; i <= data.n; i++) {
				for (int j = 1; j <= data.n + 1; j++) { arc[i][j] = random.nextInt(7) - 3; }
			}
			int[] witness = {2, 3, 4, 6, 5};
			double machine = 2;
			int previous = 0;
			for (int job : witness) { machine -= dual[job] + arc[previous][job]; previous = job; }
			machine -= arc[previous][data.n + 1];
			for (boolean nativeMode : new boolean[] {false, true}) {
				System.setProperty("twet.bpc.completionBoundNativeIntervalDelta", String.valueOf(nativeMode));
				for (boolean multi : new boolean[] {false, true}) {
					System.setProperty("twet.bpc.completionBoundMultiDeltaPropagation", String.valueOf(multi));
					for (boolean cb : new boolean[] {false, true}) {
						TWETBPCConfig config = config(cb);
						LP lp = new LP(data, new Pool(data), new CutPool(), config, new OutsourcingPool(data));
						lp.construct(new Node(data, new ArrayList<>(), new ArrayList<>(), 0), new ArrayList<>());
						try {
							lp.setFeasibilityPhaseOneObjectiveMode(true);
							set(lp, "machineDual", machine); set(lp, "jobDual", dual.clone()); set(lp, "arcDual", arc);
							GCNGBBStyleBidirectionalNgDssr engine = new GCNGBBStyleBidirectionalNgDssr(data, config);
							check(!engine.solve(lp, () -> false).isEmpty(), "six-job negative route returned");
							near(engine.getLastRelaxedRoundBestReducedCost(), -38, "six-job certificate");
							if (cb) { compareDp(data, lp, engine); }
						} finally { lp.closeModel(); }
					}
				}
			}
			System.out.println("NgHistoricalEndpointPricingTest passed: " + checks + " checks, 12 exact pricings");
		} finally {
			restore("twet.bpc.completionBoundNativeIntervalDelta", nativeBefore);
			restore("twet.bpc.completionBoundMultiDeltaPropagation", multiBefore);
			Files.deleteIfExists(tiny); Files.deleteIfExists(six);
		}
	}

	private static void compareDp(Data data, LP lp, GCNGBBStyleBidirectionalNgDssr engine) throws Exception {
		int horizon = (int) (double) get(engine, "pricingHorizon");
		double[][] dp = new double[data.n + 1][horizon + 1];
		for (double[] row : dp) { Arrays.fill(row, Double.POSITIVE_INFINITY); }
		dp[0][0] = -lp.getMachineDual();
		for (int t = 0; t <= horizon; t++) {
			for (int from = 0; from <= data.n; from++) {
				if (!Double.isFinite(dp[from][t])) { continue; }
				if (t < horizon) { dp[from][t + 1] = Math.min(dp[from][t + 1], dp[from][t]); }
				for (int to = 1; to <= data.n; to++) {
					int next = t + (int) (data.s[from][to] + data.p[to]);
					if (from == to || next > horizon || next < data.hardWindowStart[to]
							|| next > data.hardWindowEnd[to] || lp.getNode().isArcForbidden(from, to)) { continue; }
					dp[to][next] = Math.min(dp[to][next], dp[from][t] - lp.getJobDual(to) - lp.getArcDual(from, to));
				}
			}
		}
		CompletionBoundCalculator.Bounds bounds = (CompletionBoundCalculator.Bounds) get(engine, "completionBounds");
		for (int job = 1; job <= data.n; job++) {
			double best = Double.POSITIVE_INFINITY;
			for (int t = 0; t <= horizon; t++) {
				best = Math.min(best, dp[job][t]);
				if (t >= data.hardWindowStart[job] && t <= data.hardWindowEnd[job] && Double.isFinite(dp[job][t])) {
					PiecewiseLinearFunction f = bounds.forwardFByJob[job];
					check(f != null && f.evaluate(t) <= dp[job][t] + 1e-5, "CB must not exceed DP");
				}
			}
			near(bounds.forwardFMin(job), best, "CB minimum equals DP");
		}
	}

	private static TWETBPCConfig config(boolean cb) {
		TWETBPCConfig c = new TWETBPCConfig();
		c.enableBidirectionalPricing = true; c.useGCNGBBStyleNgDssrPricing = true;
		c.bidirectionalCompletionBoundRelaxation = cb ? "allCycles" : "off";
		c.bidirectionalMidpointProbe = false;
		c.bidirectionalMidpointProbeDssrImbalanceThreshold = Double.POSITIVE_INFINITY;
		c.enableTimeIndexedPreHeuristicPricing = false; c.enableTimeIndexedGraphDualWindow = false;
		c.enableHeuristicDualProfitableWindow = false; c.enableNgDssrHistoryWarmStart = false;
		return c;
	}

	private static String sixJobFixture() {
		return "6 2\n3 72 72 3 2\n9 30 30 1 4\n6 45 45 3 1\n6 27 27 1 1\n9 66 66 2 2\n6 45 45 1 1\n"
				+ "SETUP\n0 3 3 3 3 3 3\n0 0 3 18 18 18 18\n0 3 0 18 18 18 18\n"
				+ "0 18 18 0 3 18 18\n0 18 18 3 0 18 18\n0 18 18 18 18 0 3\n0 18 18 18 18 3 0\n"
				+ "SETUP_COST\n0 1.5 1.5 1.5 1.5 1.5 1.5\n0 0 1.5 9 9 9 9\n0 1.5 0 9 9 9 9\n"
				+ "0 9 9 0 1.5 9 9\n0 9 9 1.5 0 9 9\n0 9 9 9 9 0 1.5\n0 9 9 9 9 1.5 0\n";
	}

	private static Object get(Object owner, String name) throws Exception {
		Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
	}

	private static void set(Object owner, String name, Object value) throws Exception {
		Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value);
	}

	private static void restore(String name, String value) {
		if (value == null) { System.clearProperty(name); } else { System.setProperty(name, value); }
	}

	private static void near(double actual, double expected, String message) {
		check(Double.isFinite(actual) && Math.abs(actual - expected) <= 1e-5,
				message + " expected=" + expected + " actual=" + actual);
	}

	private static void check(boolean condition, String message) {
		checks++;
		if (!condition) { throw new AssertionError(message); }
	}
}
