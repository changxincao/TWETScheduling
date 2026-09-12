package TWETBPC.GC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;

/** 复用只读TI数据时，与独立重建路径对拍fixing和rank-1 exact结果。 */
public final class TimeIndexedReuseTest {

	public static void main(String[] args) throws Exception {
		testFixingCache();
		testScalarFixingMatchesGraphFixing();
		String property = "twet.bpc.packedRank1Residual";
		String original = System.getProperty(property);
		try {
			for (boolean packed : new boolean[] {false, true}) {
				System.setProperty(property, Boolean.toString(packed));
				for (boolean phaseOne : new boolean[] {false, true}) {
					for (double dual : new double[] {0.0, 35.0}) {
						testRank1Fallback(phaseOne, dual);
					}
				}
			}
		} finally {
			if (original == null) System.clearProperty(property);
			else System.setProperty(property, original);
		}
		System.out.println("TimeIndexedReuseTest passed");
	}

	private static Data tinyData() throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		data.n = 4;
		data.m = 4;
		data.CmaxH = 140;
		for (int job = 1; job <= data.n; job++) {
			data.p[job] = job + 2;
			data.d_e[job] = data.d_l[job] = 20 + 10 * job;
			data.w_e[job] = 1;
			data.w_t[job] = 2;
			data.hardWindowStart[job] = 5;
			data.hardWindowEnd[job] = 130;
			for (int from = 0; from <= data.n; from++) {
				data.s[from][job] = from == job ? 0 : 1;
				data.setupCost[from][job] = from == job ? 0 : 2;
			}
		}
		data.setPenaltyFunctions();
		return data;
	}

	private static TWETBPCConfig config() {
		TWETBPCConfig config = new TWETBPCConfig();
		config.useTimeIndexedGraphPricing = true;
		config.useTimeIndexedGraphRank1CutPricing = true;
		config.enableTimeIndexedGraphDualWindow = false;
		config.timeIndexedGraphMaxExactPricingColumns = 12;
		config.timeIndexedCompletionBoundScalarEnhancement = true;
		config.timeIndexedCompletionBoundArcFixing = true;
		config.timeIndexedCompletionBoundWindowTightening = true;
		return config;
	}

	private static LP lp(Data data, TWETBPCConfig config) {
		Pool pool = new Pool(data);
		ArrayList<Integer> seeds = new ArrayList<Integer>();
		TWETColumnEvaluator evaluator = new TWETColumnEvaluator(data);
		for (int job = 1; job <= data.n; job++) {
			List<Integer> sequence = List.of(job);
			seeds.add(pool.addColumn(sequence, evaluator.evaluate(sequence), ColumnSource.MANUAL, true));
		}
		Node node = new Node(data, seeds, new ArrayList<Integer>(), 0.0);
		LP lp = new LP(data, pool, new CutPool(), config, new OutsourcingPool(data));
		lp.construct(node, seeds);
		lp.solveRelaxation();
		return lp;
	}

	private static void testFixingCache() throws Exception {
		Data data = tinyData();
		TWETBPCConfig config = config();
		TimeIndexedGraphPricingEngine engine = new TimeIndexedGraphPricingEngine(data, config);
		Object staticData = field(engine, "staticPricingData");
		double[][] penalties = (double[][]) field(staticData, "penaltyByJobTime");
		double[][] saved = Arrays.stream(penalties).map(double[]::clone).toArray(double[][]::new);
		for (int variant = 0; variant < 3; variant++) {
			LP fresh = lp(data, config);
			LP cached = lp(data, config);
			try {
				for (LP current : List.of(fresh, cached)) {
					if (variant > 0) current.getNode().tightenTimeIndexedPricingWindow(1, 25, 65);
					if (variant > 1) current.getNode().forbidTimeIndexedPricingOnlyArc(1, 2, 40);
				}
				double ub = fresh.getLastSolution().getObjectiveValue() + 30;
				var a = TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing(data, config, fresh, ub);
				var b = engine.applyPaperReducedCostArcFixing(cached, ub);
				if (!a.isAvailable() || !b.isAvailable() || a.getFixedLong() != b.getFixedLong()
						|| a.getNodeTimeArcFixedAfterLong() != b.getNodeTimeArcFixedAfterLong()) {
					throw new AssertionError("cached fixing changed counts");
				}
				for (int from = 0; from <= data.n; from++) {
					for (int to = 0; to <= data.n; to++) {
						for (int t = 0; t <= data.CmaxH; t++) {
							if (fresh.getNode().isTimeIndexedPricingOnlyArcForbidden(from, to, t)
									!= cached.getNode().isTimeIndexedPricingOnlyArcForbidden(from, to, t)) {
								throw new AssertionError("cached fixing changed a forbidden arc");
							}
						}
					}
				}
				for (int job = 1; job <= data.n; job++) {
					if (fresh.getNode().getTimeIndexedPricingWindowStart(job) != cached.getNode().getTimeIndexedPricingWindowStart(job)
							|| fresh.getNode().getTimeIndexedPricingWindowEnd(job) != cached.getNode().getTimeIndexedPricingWindowEnd(job)) {
						throw new AssertionError("cached fixing changed a compact window");
					}
				}
				if (!Arrays.deepEquals(saved, penalties)) throw new AssertionError("fixing mutated shared penalties");
			} finally {
				fresh.closeModel();
				cached.closeModel();
			}
		}
	}

	/** NG辅助fixing必须与TI论文版在整数时间实例上删除完全相同的时空弧。 */
	private static void testScalarFixingMatchesGraphFixing() throws Exception {
		Data data = tinyData();
		TWETBPCConfig config = config();
		for (int variant = 0; variant < 3; variant++) {
			LP graph = lp(data, config);
			LP scalar = lp(data, config);
			try {
				for (LP current : List.of(graph, scalar)) {
					if (variant > 0) current.getNode().tightenTimeIndexedPricingWindow(1, 25, 65);
					if (variant > 1) current.getNode().forbidTimeIndexedPricingOnlyArc(1, 2, 40);
				}
				double ub = graph.getLastSolution().getObjectiveValue() + 30;
				var graphResult = TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing(data, config, graph, ub);
				var scalarResult = TimeIndexedScalarCompletionBound.applyArcFixing(data, config, scalar, ub);
				if (!graphResult.isAvailable() || !scalarResult.isAvailable()
						|| graphResult.getFixedLong() != scalarResult.fixed
						|| graphResult.getNodeTimeArcFixedAfterLong() != scalarResult.nodeTimeArcFixedAfter) {
					throw new AssertionError("scalar fixing changed graph fixing counts");
				}
				for (int from = 0; from <= data.n; from++) {
					for (int to = 0; to <= data.n; to++) {
						for (int t = 0; t <= data.CmaxH; t++) {
							if (graph.getNode().isTimeIndexedPricingOnlyArcForbidden(from, to, t)
									!= scalar.getNode().isTimeIndexedPricingOnlyArcForbidden(from, to, t)) {
								throw new AssertionError("scalar fixing changed a forbidden arc");
							}
						}
					}
				}
				for (int job = 1; job <= data.n; job++) {
					if (graph.getNode().getTimeIndexedPricingWindowStart(job)
							!= scalar.getNode().getTimeIndexedPricingWindowStart(job)
							|| graph.getNode().getTimeIndexedPricingWindowEnd(job)
									!= scalar.getNode().getTimeIndexedPricingWindowEnd(job)) {
						throw new AssertionError("scalar fixing changed a compact window");
					}
				}
			} finally {
				graph.closeModel();
				scalar.closeModel();
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void testRank1Fallback(boolean phaseOne, double jobDual) throws Exception {
		Data data = tinyData();
		TWETBPCConfig config = config();
		LP lp = lp(data, config);
		try {
			lp.setFeasibilityPhaseOneObjectiveMode(phaseOne);
			Arrays.fill((double[]) field(lp, "jobDual"), jobDual);
			List<TWETCut> cuts = List.of(
					new TWETCut(-1, TWETCutType.SUBSET_ROW, List.of(1, 2, 3), 1.0, "full"),
					new TWETCut(-1, TWETCutType.SUBSET_ROW, List.of(1, 2, 4), List.of(1, 2, 4), 0.5, 1.0, "node"),
					new TWETCut(-1, TWETCutType.SUBSET_ROW, List.of(2, 3, 4), null,
							List.of((1L << 32) | 2, (2L << 32) | 3, (3L << 32) | 4), 0.5, 1.0, "arc"));
			for (TWETCut cut : cuts) {
				((List<Integer>) field(lp, "activeSubsetRowPricingCutIds")).add(lp.getCutPool().addCut(cut));
				((List<Double>) field(lp, "activeSubsetRowPricingDuals")).add(-3.0);
			}
			TimeIndexedGraphRank1CutPricingEngine engine = new TimeIndexedGraphRank1CutPricingEngine(data, config);
			Class<?> type = Class.forName(TimeIndexedGraphRank1CutPricingEngine.class.getName() + "$Rank1CutSolver");
			Constructor<?> ctor = type.getDeclaredConstructor(TimeIndexedGraphRank1CutPricingEngine.class, LP.class, boolean.class);
			ctor.setAccessible(true);
			Constructor<?> reuse = type.getDeclaredConstructor(TimeIndexedGraphRank1CutPricingEngine.class, LP.class, boolean.class, type);
			reuse.setAccessible(true);
			Method solve = type.getDeclaredMethod("solve");
			solve.setAccessible(true);
			Object heuristic = ctor.newInstance(engine, lp, true);
			solve.invoke(heuristic);
			Object reused = reuse.newInstance(engine, lp, false, heuristic);
			Object fresh = ctor.newInstance(engine, lp, false);
			Object oldCuts = field(heuristic, "cutStateData");
			Object newCuts = field(reused, "cutStateData");
			if (field(oldCuts, "forwardRetainMaskByArc") != field(newCuts, "forwardRetainMaskByArc")) {
				throw new AssertionError("fallback rebuilt masks");
			}
			Object scratch = field(oldCuts, "scratchPackedResidual");
			if (scratch != null && scratch == field(newCuts, "scratchPackedResidual")) {
				throw new AssertionError("fallback shared mutable scratch");
			}
			List<TWETColumn> actual = (List<TWETColumn>) solve.invoke(reused);
			List<TWETColumn> expected = (List<TWETColumn>) solve.invoke(fresh);
			if (actual.size() != expected.size()
					|| !field(reused, "bestPseudoReducedCost").equals(field(fresh, "bestPseudoReducedCost"))) {
				throw new AssertionError("fallback changed exact certificate or column count");
			}
			for (int i = 0; i < actual.size(); i++) {
				if (!actual.get(i).getSequence().equals(expected.get(i).getSequence())
						|| actual.get(i).getCost() != expected.get(i).getCost()) {
					throw new AssertionError("fallback changed returned sequence/cost");
				}
			}
			if (jobDual == 0.0) {
				PricingResult closed = engine.price(lp);
				if (!closed.getColumns().isEmpty() || !Double.isFinite(closed.getCertifiedInternalReducedCost())) {
					throw new AssertionError("public fallback failed to certify closure");
				}
			}
		} finally {
			lp.closeModel();
		}
	}

	private static Object field(Object object, String name) throws Exception {
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(object);
	}
}
