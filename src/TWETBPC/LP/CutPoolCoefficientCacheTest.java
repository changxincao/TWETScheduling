package TWETBPC.LP;

import java.util.Arrays;

import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;

/**
 * 2026-07-28: subset-row cut/column 系数分页缓存的边界与容量测试。
 */
public final class CutPoolCoefficientCacheTest {

	private CutPoolCoefficientCacheTest() {
	}

	public static void main(String[] args) {
		String enabled = System.getProperty("twet.bpc.cacheSubsetRowCoefficients");
		String capacity = System.getProperty("twet.bpc.maxSubsetRowCoefficientCacheEntries");
		try {
			System.setProperty("twet.bpc.cacheSubsetRowCoefficients", "true");
			System.setProperty("twet.bpc.maxSubsetRowCoefficientCacheEntries", "4096");
			CutPool pool = new CutPool();
			int cut0 = pool.addCut(cut("c0"));
			int cut1 = pool.addCut(cut("c1"));
			assertValue(-1, pool.getSubsetRowCoefficient(cut0, 0), "uncached");
			pool.cacheSubsetRowCoefficient(cut0, 0, 0);
			pool.cacheSubsetRowCoefficient(cut0, 4095, 7);
			assertValue(0, pool.getSubsetRowCoefficient(cut0, 0), "cached zero");
			assertValue(7, pool.getSubsetRowCoefficient(cut0, 4095), "page end");

			pool.cacheSubsetRowCoefficient(cut0, 4096, 9);
			pool.cacheSubsetRowCoefficient(cut1, 0, 11);
			assertValue(-1, pool.getSubsetRowCoefficient(cut0, 4096), "capacity blocks next page");
			assertValue(-1, pool.getSubsetRowCoefficient(cut1, 0), "capacity shared across cuts");
			assertValue(0, pool.reclaimInactiveSubsetRowCoefficientPages(Arrays.asList(cut0, cut1)),
					"active page retained");
			assertValue(1, pool.reclaimInactiveSubsetRowCoefficientPages(Arrays.asList(cut1)),
					"inactive page reclaimed");
			pool.cacheSubsetRowCoefficient(cut1, 0, 11);
			assertValue(-1, pool.getSubsetRowCoefficient(cut0, 0), "released cut misses");
			assertValue(11, pool.getSubsetRowCoefficient(cut1, 0), "active cut reuses capacity");

			pool.cacheSubsetRowCoefficient(cut1, 1, Short.MAX_VALUE);
			assertValue(Short.MAX_VALUE, pool.getSubsetRowCoefficient(cut1, 1), "maximum cached coefficient");
			pool.cacheSubsetRowCoefficient(cut1, 2, Short.MAX_VALUE + 1);
			assertValue(-1, pool.getSubsetRowCoefficient(cut1, 2), "oversized coefficient is not cached");
			assertValue(1, pool.reclaimInactiveSubsetRowCoefficientPages(Arrays.asList(cut0)),
					"reactivated cut releases previous active page");
			pool.cacheSubsetRowCoefficient(cut0, 0, 5);
			assertValue(5, pool.getSubsetRowCoefficient(cut0, 0), "reactivated cut can be cached again");

			System.setProperty("twet.bpc.maxSubsetRowCoefficientCacheEntries", "5000");
			CutPool nonAlignedCapacity = new CutPool();
			int nonAlignedCut0 = nonAlignedCapacity.addCut(cut("nonAligned0"));
			int nonAlignedCut1 = nonAlignedCapacity.addCut(cut("nonAligned1"));
			nonAlignedCapacity.cacheSubsetRowCoefficient(nonAlignedCut0, 0, 3);
			assertValue(1, nonAlignedCapacity.reclaimInactiveSubsetRowCoefficientPages(Arrays.asList(nonAlignedCut1)),
					"non-page-aligned capacity reclaimed");

			System.setProperty("twet.bpc.cacheSubsetRowCoefficients", "false");
			CutPool disabled = new CutPool();
			int disabledCut = disabled.addCut(cut("disabled"));
			disabled.cacheSubsetRowCoefficient(disabledCut, 0, 3);
			assertValue(-1, disabled.getSubsetRowCoefficient(disabledCut, 0), "disabled cache");
		} finally {
			restore("twet.bpc.cacheSubsetRowCoefficients", enabled);
			restore("twet.bpc.maxSubsetRowCoefficientCacheEntries", capacity);
		}
		System.out.println("CutPoolCoefficientCacheTest passed");
	}

	private static TWETCut cut(String description) {
		return new TWETCut(-1, TWETCutType.SUBSET_ROW, Arrays.asList(1, 2, 3), 1.0, description);
	}

	private static void assertValue(int expected, int actual, String label) {
		if (expected != actual) {
			throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
		}
	}

	private static void restore(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}
}
