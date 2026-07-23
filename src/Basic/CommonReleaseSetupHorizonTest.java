package Basic;

import java.util.Arrays;

/** 对公共 release 与最大进入 setup 的 Cmax 公式做可手算回归测试。 */
public final class CommonReleaseSetupHorizonTest {
	private static final double EPS = 1e-9;

	private CommonReleaseSetupHorizonTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", false, true);
		data.n = 6;
		data.m = 2;
		double[] processing = { 0, 19, 18, 17, 12, 15, 10 };
		for (int j = 1; j <= data.n; j++) {
			data.p[j] = processing[j];
			data.d_l[j] = processing[j];
		}
		for (int i = 0; i <= data.n; i++) {
			Arrays.fill(data.s[i], 0, data.n + 1, 0.0);
		}

		assertClose(55.0, data.computeCommonReleaseSetupHorizon(), "zero-setup bound");

		for (int j = 1; j <= data.n; j++) {
			data.s[0][j] = j + 2;
		}
		assertClose(73.0, data.computeCommonReleaseSetupHorizon(), "inflated-processing bound");

		data.setImprovedCmax();
		assertClose(73.0, data.CmaxE, "CmaxE");
		assertClose(73.0, data.CmaxH, "CmaxH");
		System.out.println("CommonReleaseSetupHorizonTest passed");
	}

	private static void assertClose(double expected, double actual, String name) {
		if (Math.abs(expected - actual) > EPS) {
			throw new AssertionError(name + ": expected=" + expected + ", actual=" + actual);
		}
	}
}