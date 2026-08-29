package TWETBPC.LP;

/**
 * 验证多次 pricing 闭合只保留最强安全节点下界。
 */
public final class CertifiedNodeBoundTrackingTest {

	private CertifiedNodeBoundTrackingTest() {
	}

	public static void main(String[] args) {
		double bound = Double.NEGATIVE_INFINITY;
		bound = PC.strongestCertifiedBound(bound, 100.0);
		bound = PC.strongestCertifiedBound(bound, 125.0);
		bound = PC.strongestCertifiedBound(bound, 120.0);
		assertClose(125.0, bound, "weaker later closure must not replace the strongest bound");

		bound = PC.strongestCertifiedBound(bound, Double.NaN);
		bound = PC.strongestCertifiedBound(bound, Double.POSITIVE_INFINITY);
		assertClose(125.0, bound, "non-finite incomplete bounds must be ignored");

		System.out.println("CertifiedNodeBoundTrackingTest passed");
	}

	private static void assertClose(double expected, double actual, String context) {
		if (Math.abs(expected - actual) > 1e-9) {
			throw new AssertionError(context + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
