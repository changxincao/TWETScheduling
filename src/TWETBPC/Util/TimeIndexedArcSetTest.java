package TWETBPC.Util;

/** TimeIndexedArcSet扁平/分段存储及已知未设置写入口的轻量回归。 */
public final class TimeIndexedArcSetTest {

	private TimeIndexedArcSetTest() {
	}

	public static void main(String[] args) {
		verifyFlatStorage();
		verifySegmentedStorage();
		System.out.println("TimeIndexedArcSetTest passed");
	}

	private static void verifyFlatStorage() {
		TimeIndexedArcSet set = new TimeIndexedArcSet(3, 10);
		set.setKnownAbsent(1, 2, 7);
		require(set.get(1, 2, 7), "flat known-absent write");
		require(set.cardinality() == 1L, "flat cardinality after known-absent write");
		set.set(1, 2, 7);
		require(set.cardinality() == 1L, "flat ordinary set remains idempotent");
	}

	private static void verifySegmentedStorage() {
		TimeIndexedArcSet set = new TimeIndexedArcSet(200, 100_000);
		require(set.usesSegmentedStorage(), "large domain uses segmented storage");
		set.setKnownAbsent(199, 198, 99_999);
		require(set.get(199, 198, 99_999), "segmented known-absent write");
		require(set.cardinality() == 1L, "segmented cardinality after known-absent write");
		set.set(199, 198, 99_999);
		require(set.cardinality() == 1L, "segmented ordinary set remains idempotent");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
