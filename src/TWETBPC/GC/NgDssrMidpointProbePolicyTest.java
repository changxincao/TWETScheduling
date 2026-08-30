package TWETBPC.GC;

import TWETBPC.TWETBPCConfig;

/** 验证单侧续探判定及后续 DSSR 轮独立 probe 参数的有效口径。 */
public final class NgDssrMidpointProbePolicyTest {

	private NgDssrMidpointProbePolicyTest() {
	}

	public static void main(String[] args) {
		assertDecision(true, true, 10.0, 20.0, 1.5,
				GCNGBBStyleBidirectionalNgDssr.OneSideProbeDecision.COMPLETE);
		assertDecision(false, false, 10.0, 20.0, 1.5,
				GCNGBBStyleBidirectionalNgDssr.OneSideProbeDecision.NOT_APPLICABLE);
		assertDecision(true, false, 10.0, 14.9, 1.5,
				GCNGBBStyleBidirectionalNgDssr.OneSideProbeDecision.CONTINUE);
		assertDecision(true, false, 10.0, 15.0, 1.5,
				GCNGBBStyleBidirectionalNgDssr.OneSideProbeDecision.RATIO_REACHED);
		assertDecision(false, true, 39.9, 10.0, 4.0,
				GCNGBBStyleBidirectionalNgDssr.OneSideProbeDecision.CONTINUE);
		assertDecision(false, true, 40.0, 10.0, 4.0,
				GCNGBBStyleBidirectionalNgDssr.OneSideProbeDecision.RATIO_REACHED);

		TWETBPCConfig inherited = new TWETBPCConfig();
		inherited.bidirectionalMidpointProbeEarlyStopRatio = 1.5;
		String inheritedSummary = GCNGBBStyleBidirectionalNgDssr.effectiveMidpointProbeConfiguration(inherited);
		requireContains(inheritedSummary, "dssrAcceptableRatio=1.5");
		requireContains(inheritedSummary, "dssrStepFraction=0.1");

		TWETBPCConfig relaxed = new TWETBPCConfig();
		relaxed.bidirectionalMidpointProbeDssrEarlyStopRatio = 4.0;
		relaxed.bidirectionalMidpointProbeDssrMoveFraction = 0.05;
		String relaxedSummary = GCNGBBStyleBidirectionalNgDssr.effectiveMidpointProbeConfiguration(relaxed);
		requireContains(relaxedSummary, "dssrAcceptableRatio=4.0");
		requireContains(relaxedSummary, "dssrStepFraction=0.05");

		System.out.println("NgDssrMidpointProbePolicyTest passed");
	}

	private static void assertDecision(boolean forwardExhausted, boolean backwardExhausted,
			double forwardMillis, double backwardMillis, double ratio,
			GCNGBBStyleBidirectionalNgDssr.OneSideProbeDecision expected) {
		GCNGBBStyleBidirectionalNgDssr.OneSideProbeDecision actual =
				GCNGBBStyleBidirectionalNgDssr.classifyOneSideProbe(forwardExhausted, backwardExhausted,
						forwardMillis, backwardMillis, ratio);
		if (actual != expected) {
			throw new AssertionError("expected=" + expected + ", actual=" + actual);
		}
	}

	private static void requireContains(String actual, String expected) {
		if (!actual.contains(expected)) {
			throw new AssertionError("missing '" + expected + "' in " + actual);
		}
	}
}
