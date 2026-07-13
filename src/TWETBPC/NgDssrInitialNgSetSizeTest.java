package TWETBPC;

/** 验证 nearestK 的自动 n/10 口径以及显式固定 K 的覆盖语义。 */
public final class NgDssrInitialNgSetSizeTest {

	private NgDssrInitialNgSetSizeTest() {
	}

	public static void main(String[] args) {
		TWETBPCConfig config = new TWETBPCConfig();
		if (!"nearestK".equals(config.ngDssrInitialNgSetMode)) {
			throw new AssertionError("default ng-set mode should be nearestK: " + config.ngDssrInitialNgSetMode);
		}
		assertEquals(4, config.resolveNgDssrInitialNgSetSize(40), "auto K for n=40");
		assertEquals(5, config.resolveNgDssrInitialNgSetSize(50), "auto K for n=50");
		assertEquals(6, config.resolveNgDssrInitialNgSetSize(60), "auto K for n=60");

		config.ngDssrInitialNgSetSize = 3;
		assertEquals(3, config.resolveNgDssrInitialNgSetSize(50), "explicit fixed K");
		config.ngDssrInitialNgSetSize = 0;
		assertEquals(0, config.resolveNgDssrInitialNgSetSize(50), "explicit empty nearest set");
		System.out.println("NgDssrInitialNgSetSizeTest passed");
	}

	private static void assertEquals(int expected, int actual, String message) {
		if (expected != actual) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
