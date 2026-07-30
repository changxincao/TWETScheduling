package TWETBPC;

import java.util.List;

import Basic.Data;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.PricingMode;

/**
 * 验证 ng-DSSR effective 配置只跟随最终装配的 pricing engine。
 */
public final class TWETBPCContextEffectiveNgDssrConfigurationTest {

	private static final String EFFECTIVE_PREFIX = "run.effective.ngDssrMidpointProbe=";

	private TWETBPCContextEffectiveNgDssrConfigurationTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", false, true);
		assertStaleNgFlagIgnoredByTimeIndexed(data);
		assertStaleNgFlagIgnoredByHigherPriorityGcbb(data);
		assertEffectiveConfigReportedForSelectedNgDssr(data);
		assertSriGeneratorFollowsSelectedPartialNgDssr(data);
		assertSriGeneratorFollowsSelectedTimeIndexedRank1(data);
		System.out.println("TWETBPCContextEffectiveNgDssrConfigurationTest passed");
	}

	private static void assertStaleNgFlagIgnoredByTimeIndexed(Data data) {
		TWETBPCConfig config = staleNgConfiguration();
		config.useTimeIndexedGraphPricing = true;
		TWETBPCContext context = new TWETBPCContext(data, config);
		assertMode(context, PricingMode.TIME_INDEXED, "time-indexed pricing");
		List<String> lines = context.runConfigurationLines();
		assertNoLineWithPrefix(lines, EFFECTIVE_PREFIX, "time-indexed pricing");
	}

	private static void assertStaleNgFlagIgnoredByHigherPriorityGcbb(Data data) {
		TWETBPCConfig config = staleNgConfiguration();
		config.useGCNGBBStyleNgDssrPricing = false;
		config.useGCNGBBStyleNgDssrPartialDominancePricing = true;
		config.useGCBBFullDomainBidirectionalPricing = true;
		config.enableSubsetRowCutsForPartialDominance = true;
		config.enableTimeIndexedPreHeuristicPricing = true;
		TWETBPCContext context = new TWETBPCContext(data, config);
		assertMode(context, PricingMode.OTHER, "full-domain GCBB pricing");
		assertMissingComponent(context.cutGenerators, "SubsetRowCutGenerator", "stale partial SRI flag");
		assertMissingPricingEngine(context.pricingEngines, "TimeIndexedPreHeuristicPricing",
				"stale ng-DSSR pre-heuristic flag");
		List<String> lines = context.runConfigurationLines();
		assertNoLineWithPrefix(lines, EFFECTIVE_PREFIX, "full-domain GCBB pricing");
	}

	private static void assertEffectiveConfigReportedForSelectedNgDssr(Data data) {
		TWETBPCConfig config = staleNgConfiguration();
		config.bidirectionalMidpointProbeScore = "time";
		TWETBPCContext context = new TWETBPCContext(data, config);
		assertMode(context, PricingMode.NG_DSSR, "selected ng-DSSR pricing");
		List<String> lines = context.runConfigurationLines();
		assertHasLineWithPrefix(lines, EFFECTIVE_PREFIX, "selected ng-DSSR pricing");
	}

	private static void assertSriGeneratorFollowsSelectedPartialNgDssr(Data data) {
		TWETBPCConfig config = staleNgConfiguration();
		config.useGCNGBBStyleNgDssrPricing = false;
		config.useGCNGBBStyleNgDssrPartialDominancePricing = true;
		config.bidirectionalMidpointProbeScore = "time";
		config.enableSubsetRowCutsForPartialDominance = true;
		TWETBPCContext context = new TWETBPCContext(data, config);
		assertMode(context, PricingMode.NG_DSSR_PARTIAL, "selected partial ng-DSSR pricing");
		assertHasComponent(context.cutGenerators, "SubsetRowCutGenerator", "selected partial ng-DSSR pricing");
	}

	private static void assertSriGeneratorFollowsSelectedTimeIndexedRank1(Data data) {
		TWETBPCConfig config = staleNgConfiguration();
		config.useTimeIndexedGraphPricing = true;
		config.useTimeIndexedGraphRank1CutPricing = true;
		config.enableSubsetRowCutsForTimeIndexedGraph = true;
		TWETBPCContext context = new TWETBPCContext(data, config);
		assertMode(context, PricingMode.TIME_INDEXED_RANK1, "selected time-indexed rank-1 pricing");
		assertHasComponent(context.cutGenerators, "SubsetRowCutGenerator", "selected time-indexed rank-1 pricing");
	}

	private static TWETBPCConfig staleNgConfiguration() {
		TWETBPCConfig config = new TWETBPCConfig();
		config.enableBidirectionalPricing = true;
		config.useGCNGBBStyleNgDssrPricing = true;
		config.bidirectionalMidpointProbe = true;
		config.bidirectionalMidpointProbeScore = "queue";
		return config;
	}

	private static void assertNoLineWithPrefix(List<String> lines, String prefix, String context) {
		for (String line : lines) {
			if (line.startsWith(prefix)) {
				throw new AssertionError(context + " unexpectedly reported " + line);
			}
		}
	}

	private static void assertHasLineWithPrefix(List<String> lines, String prefix, String context) {
		for (String line : lines) {
			if (line.startsWith(prefix)) {
				return;
			}
		}
		throw new AssertionError(context + " did not report " + prefix);
	}

	private static void assertMode(TWETBPCContext context, PricingMode expected, String description) {
		if (context.pricingMode != expected) {
			throw new AssertionError(description + ": expected mode=" + expected + ", actual=" + context.pricingMode);
		}
	}

	private static void assertMissingComponent(List<CutGenerator> components, String simpleName, String context) {
		for (CutGenerator component : components) {
			if (simpleName.equals(component.getClass().getSimpleName())) {
				throw new AssertionError(context + " unexpectedly installed " + simpleName);
			}
		}
	}

	private static void assertHasComponent(List<CutGenerator> components, String simpleName, String context) {
		for (CutGenerator component : components) {
			if (simpleName.equals(component.getClass().getSimpleName())) {
				return;
			}
		}
		throw new AssertionError(context + " did not install " + simpleName);
	}

	private static void assertMissingPricingEngine(List<PricingEngine> engines, String name, String context) {
		for (PricingEngine engine : engines) {
			if (name.equals(engine.getName())) {
				throw new AssertionError(context + " unexpectedly installed " + name);
			}
		}
	}
}
