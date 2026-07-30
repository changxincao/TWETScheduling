package TWETBPC;

import java.util.List;

import Basic.Data;

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
		System.out.println("TWETBPCContextEffectiveNgDssrConfigurationTest passed");
	}

	private static void assertStaleNgFlagIgnoredByTimeIndexed(Data data) {
		TWETBPCConfig config = staleNgConfiguration();
		config.useTimeIndexedGraphPricing = true;
		List<String> lines = new TWETBPCContext(data, config).runConfigurationLines();
		assertNoLineWithPrefix(lines, EFFECTIVE_PREFIX, "time-indexed pricing");
	}

	private static void assertStaleNgFlagIgnoredByHigherPriorityGcbb(Data data) {
		TWETBPCConfig config = staleNgConfiguration();
		config.useGCBBFullDomainBidirectionalPricing = true;
		List<String> lines = new TWETBPCContext(data, config).runConfigurationLines();
		assertNoLineWithPrefix(lines, EFFECTIVE_PREFIX, "full-domain GCBB pricing");
	}

	private static void assertEffectiveConfigReportedForSelectedNgDssr(Data data) {
		TWETBPCConfig config = staleNgConfiguration();
		config.bidirectionalMidpointProbeScore = "time";
		List<String> lines = new TWETBPCContext(data, config).runConfigurationLines();
		assertHasLineWithPrefix(lines, EFFECTIVE_PREFIX, "selected ng-DSSR pricing");
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
}
