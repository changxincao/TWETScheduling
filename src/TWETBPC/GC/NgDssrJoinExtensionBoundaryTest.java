package TWETBPC.GC;

import Common.PiecewiseLinearFunction;

/**
 * ng-DSSR join 扩展在 Tmid 紧贴 PWLF 端点时的回归测试。
 */
public final class NgDssrJoinExtensionBoundaryTest {

	private NgDssrJoinExtensionBoundaryTest() {
	}

	public static void main(String[] args) {
		PiecewiseLinearFunction function = new PiecewiseLinearFunction(0.0, 2513.0);
		function.addSegment(1000.0, 1934.0, 2.0, 3.0);

		double expectedTail = 2.0 * 1934.0 + 3.0;
		assertClose(expectedTail,
				GCNGBBStyleBidirectionalNgDssr.valueAtOrNearest(function, 1934.000001),
				"right endpoint clamp");
		assertClose(2.0 * 1500.0 + 3.0,
				GCNGBBStyleBidirectionalNgDssr.valueAtOrNearest(function, 1500.0),
				"interior evaluation");
		assertClose(2.0 * 1000.0 + 3.0,
				GCNGBBStyleBidirectionalNgDssr.valueAtOrNearest(function, 999.999999),
				"left endpoint clamp");

		PiecewiseLinearFunction gapped = new PiecewiseLinearFunction(0.0, 2513.0);
		gapped.addSegment(1000.0, 1200.0, 1.0, 0.0);
		gapped.addSegment(1300.0, 1934.0, 1.0, 0.0);
		boolean rejectedInternalGap = false;
		try {
			gapped.evaluateAtClampedEndpoint(1250.0);
		} catch (IllegalArgumentException expected) {
			rejectedInternalGap = true;
		}
		if (!rejectedInternalGap) {
			throw new AssertionError("endpoint clamp must not hide an internal gap");
		}

		System.out.println("NgDssrJoinExtensionBoundaryTest passed.");
	}

	private static void assertClose(double expected, double actual, String context) {
		if (Math.abs(expected - actual) > 1e-9) {
			throw new AssertionError(context + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
