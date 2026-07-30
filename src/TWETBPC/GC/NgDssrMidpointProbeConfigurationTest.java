package TWETBPC.GC;

import TWETBPC.TWETBPCConfig;

/**
 * 验证 ng-DSSR 固定 time midpoint probe 的通用配置边界。
 */
public final class NgDssrMidpointProbeConfigurationTest {

	private NgDssrMidpointProbeConfigurationTest() {
	}

	public static void main(String[] args) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.bidirectionalMidpointProbe = true;
		config.bidirectionalMidpointProbeScore = " TIME ";
		GCNGBBStyleBidirectionalNgDssr.validateMidpointProbeConfiguration(config);

		config.bidirectionalMidpointProbeScore = "queue";
		assertRejected(config, "queue");

		config.bidirectionalMidpointProbeScore = null;
		assertRejected(config, "null");

		config.bidirectionalMidpointProbe = false;
		config.bidirectionalMidpointProbeScore = "queue";
		GCNGBBStyleBidirectionalNgDssr.validateMidpointProbeConfiguration(config);

		System.out.println("NgDssrMidpointProbeConfigurationTest passed");
	}

	private static void assertRejected(TWETBPCConfig config, String expectedValue) {
		try {
			GCNGBBStyleBidirectionalNgDssr.validateMidpointProbeConfiguration(config);
			throw new AssertionError("expected invalid scoreMode to be rejected: " + expectedValue);
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains(expectedValue)) {
				throw new AssertionError("unexpected validation message: " + expected.getMessage());
			}
		}
	}
}
