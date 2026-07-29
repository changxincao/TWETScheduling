package TWETBPC.GC;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;

/**
 * strong-branch Phase-I 定价的四组合回归：ng-DSSR/time-indexed × no-cut/SRI。
 */
public final class StrongBranchingPhaseOnePricingTest {

	private static final double RC_TOLERANCE = 1e-6;

	private StrongBranchingPhaseOnePricingTest() {
	}

	public static void main(String[] args) throws Exception {
		testTimeIndexedWithoutCut();
		testTimeIndexedWithSriCut();
		testTimeIndexedDeferredPackedMultiwordEquivalence();
		testNgDssrWithoutCut();
		testNgDssrWithSriCut();
		System.out.println("StrongBranchingPhaseOnePricingTest passed");
	}

	private static void testTimeIndexedDeferredPackedMultiwordEquivalence() throws Exception {
		String property = "twet.bpc.deferRank1LabelMaterialization";
		String original = System.getProperty(property);
		try {
			System.setProperty(property, "false");
			TestContext legacyContext = createContext(timeIndexedConfig(true), 65);
			PricingResult legacy = new TimeIndexedGraphRank1CutPricingEngine(legacyContext.data, legacyContext.config)
					.price(legacyContext.lp);

			System.setProperty(property, "true");
			TestContext deferredContext = createContext(timeIndexedConfig(true), 65);
			PricingResult deferred = new TimeIndexedGraphRank1CutPricingEngine(deferredContext.data,
					deferredContext.config).price(deferredContext.lp);
			assertEquivalentPricingResults("time-indexed/SRI multiword deferred", legacy, deferred);
		} finally {
			if (original == null) {
				System.clearProperty(property);
			} else {
				System.setProperty(property, original);
			}
		}
	}

	private static void testTimeIndexedWithoutCut() throws Exception {
		TestContext context = createContext(timeIndexedConfig(false), false);
		assertFindsTrueCostColumns("time-indexed/no-cut",
				new TimeIndexedGraphPricingEngine(context.data, context.config).price(context.lp), context);
	}

	private static void testTimeIndexedWithSriCut() throws Exception {
		TestContext context = createContext(timeIndexedConfig(true), true);
		assertClosedByCut("time-indexed/SRI",
				new TimeIndexedGraphRank1CutPricingEngine(context.data, context.config).price(context.lp));
	}

	private static void testNgDssrWithoutCut() throws Exception {
		TestContext context = createContext(ngDssrConfig(false), false);
		assertFindsTrueCostColumns("ng-DSSR/no-cut",
				new GCNGBBStyleBidirectionalNgDssrPricingEngine(context.data, context.config).price(context.lp),
				context);
	}

	private static void testNgDssrWithSriCut() throws Exception {
		TestContext context = createContext(ngDssrConfig(true), true);
		assertClosedByCut("ng-DSSR/SRI",
				new GCNGBBStyleBidirectionalNgDssrPartialDominancePricingEngine(
						context.data, context.config).price(context.lp));
	}

	private static TWETBPCConfig timeIndexedConfig(boolean sri) {
		TWETBPCConfig config = baseConfig();
		config.useTimeIndexedGraphPricing = true;
		config.useTimeIndexedGraphRank1CutPricing = sri;
		config.enableSubsetRowCutsForTimeIndexedGraph = sri;
		config.timeIndexedGraphMaxExactPricingColumns = 20;
		return config;
	}

	private static TWETBPCConfig ngDssrConfig(boolean sri) {
		TWETBPCConfig config = baseConfig();
		config.enableBidirectionalPricing = true;
		config.useGCNGBBStyleNgDssrPricing = !sri;
		config.useGCNGBBStyleNgDssrPartialDominancePricing = sri;
		config.enableSubsetRowCutsForPartialDominance = sri;
		config.maxExactPricingColumns = 20;
		return config;
	}

	private static TWETBPCConfig baseConfig() {
		TWETBPCConfig config = new TWETBPCConfig();
		config.enableTimeIndexedGraphDualWindow = false;
		config.enableHeuristicDualProfitableWindow = false;
		config.enableNgDssrHistoryWarmStart = false;
		return config;
	}

	private static TestContext createContext(TWETBPCConfig config, boolean withCut) throws Exception {
		return createContext(config, withCut ? 1 : 0);
	}

	private static TestContext createContext(TWETBPCConfig config, int cutCount) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		data.n = cutCount > 20 ? 10 : 6;
		for (int job = 1; job <= data.n; job++) {
			data.hardWindowStart[job] = 0.0;
			data.hardWindowEnd[job] = data.CmaxH;
			for (int other = 0; other <= data.n; other++) {
				data.preprocessedArcForbidden[job][other] = false;
				data.preprocessedArcForbidden[other][job] = false;
			}
		}

		CutPool cutPool = new CutPool();
		Node node = new Node(data, new ArrayList<Integer>(), new ArrayList<Integer>(), 0.0);
		LP lp = new LP(data, new Pool(data), cutPool, config, new OutsourcingPool(data));
		lp.construct(node, node.seedColumnIds);
		lp.setFeasibilityPhaseOneObjectiveMode(true);
		setField(lp, "machineDual", Double.valueOf(-15.0));
		double[] jobDual = (double[]) getField(lp, "jobDual");
		jobDual[1] = 10.0;
		jobDual[2] = 10.0;
		jobDual[3] = 10.0;

		if (cutCount > 0) {
			ArrayList<Integer> cutIds = new ArrayList<Integer>(cutCount);
			ArrayList<Double> cutDuals = new ArrayList<Double>(cutCount);
			outer: for (int first = 1; first <= data.n; first++) {
				for (int second = first + 1; second <= data.n; second++) {
					for (int third = second + 1; third <= data.n; third++) {
						int cutId = cutPool.addCut(new TWETCut(-1, TWETCutType.SUBSET_ROW,
								Arrays.asList(Integer.valueOf(first), Integer.valueOf(second),
										Integer.valueOf(third)),
								1.0, "phaseOneRegression-" + cutIds.size()));
						cutIds.add(Integer.valueOf(cutId));
						cutDuals.add(Double.valueOf(-100.0));
						if (cutIds.size() == cutCount) {
							break outer;
						}
					}
				}
			}
			if (cutIds.size() != cutCount) {
				throw new AssertionError("unable to construct " + cutCount + " distinct rank-1 cuts");
			}
			node.activeCutIds.addAll(cutIds);
			setField(lp, "activeSubsetRowPricingCutIds", cutIds);
			setField(lp, "activeSubsetRowPricingDuals", cutDuals);
		}
		return new TestContext(data, config, lp);
	}

	private static void assertEquivalentPricingResults(String name, PricingResult first, PricingResult second) {
		if (first.isImproved() != second.isImproved()
				|| Double.doubleToLongBits(first.getCertifiedInternalReducedCost()) != Double
						.doubleToLongBits(second.getCertifiedInternalReducedCost())
				|| first.getColumns().size() != second.getColumns().size()
				|| first.getOutsourcingColumns().size() != second.getOutsourcingColumns().size()) {
			throw new AssertionError(name + " result header mismatch");
		}
		for (int index = 0; index < first.getColumns().size(); index++) {
			TWETColumn left = first.getColumns().get(index);
			TWETColumn right = second.getColumns().get(index);
			if (!left.getSignature().equals(right.getSignature())
					|| Double.doubleToLongBits(left.getCost()) != Double.doubleToLongBits(right.getCost())) {
				throw new AssertionError(name + " column mismatch at index " + index);
			}
		}
	}

	private static void assertFindsTrueCostColumns(String name, PricingResult result, TestContext context) {
		if (!result.isImproved() || result.getColumns().isEmpty()) {
			throw new AssertionError(name + " did not find a Phase-I negative column: " + result.getMessage());
		}
		TWETColumnEvaluator evaluator = new TWETColumnEvaluator(context.data);
		for (TWETColumn column : result.getColumns()) {
			double trueCost = evaluator.evaluate(column.getSequence());
			if (Utility.isBigMValue(trueCost)
					|| Math.abs(trueCost - column.getCost()) > 1e-7 * Math.max(1.0, Math.abs(trueCost))) {
				throw new AssertionError(name + " did not retain the true objective cost");
			}
			if (!Utility.compareLt(context.lp.computeReducedCost(column,
					context.lp.captureTruePricingDuals()), -RC_TOLERANCE)) {
				throw new AssertionError(name + " returned a nonnegative Phase-I column");
			}
		}
	}

	private static void assertClosedByCut(String name, PricingResult result) {
		if (result.isImproved() || !Double.isFinite(result.getCertifiedInternalReducedCost())
				|| Utility.compareLt(result.getCertifiedInternalReducedCost(), -RC_TOLERANCE)) {
			throw new AssertionError(name + " did not return a nonnegative complete certificate: "
					+ result.getMessage() + ", certificate=" + result.getCertifiedInternalReducedCost());
		}
	}

	private static Object getField(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static final class TestContext {
		final Data data;
		final TWETBPCConfig config;
		final LP lp;

		TestContext(Data data, TWETBPCConfig config, LP lp) {
			this.data = data;
			this.config = config;
			this.lp = lp;
		}
	}
}
