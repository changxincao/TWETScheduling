package Common.formal;

import TWETBPC.BestBpcProfiles;
import TWETBPC.TWETBPCConfig;

/** 正式profile与显式JVM属性的中点开关优先级回归。 */
public final class FormalExperimentRunnerMidpointOverrideTest {

	private static final String PREVIOUS = "twet.bpc.midpointPreviousPricing";
	private static final String MEAN = "twet.bpc.midpointFirstRoundMean";

	private FormalExperimentRunnerMidpointOverrideTest() {
	}

	public static void main(String[] args) {
		String oldPrevious = System.getProperty(PREVIOUS);
		String oldMean = System.getProperty(MEAN);
		try {
			System.clearProperty(PREVIOUS);
			System.clearProperty(MEAN);
			TWETBPCConfig production = ngConfig();
			FormalExperimentRunner.applyMidpointExperimentSystemPropertyOverrides(production);
			require(!production.enableNgDssrSameNodeMidpointStart, "production old start off");
			require(production.enableNgDssrFirstRoundMeanStart, "production mean retained");

			System.setProperty(PREVIOUS, "true");
			System.setProperty(MEAN, "false");
			TWETBPCConfig baseline = ngConfig();
			FormalExperimentRunner.applyMidpointExperimentSystemPropertyOverrides(baseline);
			require(baseline.enableNgDssrSameNodeMidpointStart, "explicit previous start on");
			require(!baseline.enableNgDssrFirstRoundMeanStart, "explicit mean off");

			System.setProperty(MEAN, "true");
			boolean rejected = false;
			try {
				FormalExperimentRunner.applyMidpointExperimentSystemPropertyOverrides(ngConfig());
			} catch (IllegalArgumentException expected) {
				rejected = true;
			}
			require(rejected, "conflicting midpoint strategies rejected before solve");
			System.out.println("FormalExperimentRunnerMidpointOverrideTest passed.");
		} finally {
			restore(PREVIOUS, oldPrevious);
			restore(MEAN, oldMean);
		}
	}

	private static TWETBPCConfig ngConfig() {
		TWETBPCConfig config = new TWETBPCConfig();
		BestBpcProfiles.NG_DSSR.apply(config);
		return config;
	}

	private static void restore(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}

	private static void require(boolean condition, String name) {
		if (!condition) {
			throw new AssertionError(name);
		}
	}
}
