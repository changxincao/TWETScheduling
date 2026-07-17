package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import TWETBPC.TWETBPCConfig;
import TWETBPC.Util.PackedBitSet;

/** 最小新增 pair 重复段更新的聚焦测试。 */
public final class NgDssrMinimumRepeatedSegmentUpdateTest {

	private NgDssrMinimumRepeatedSegmentUpdateTest() {
	}

	public static void main(String[] args) {
		TWETBPCConfig config = new TWETBPCConfig();
		assertEquals(25, config.ngDssrNonElementaryRouteUpdateLimit,
				"minimum-segment reservoir should be enabled by default");
		assertEquals("minimumNewPairsSegment", config.ngDssrNonElementaryRouteUpdateMode,
				"minimum-segment route update should be enabled by default");

		PackedBitSet[] neighborhoods = neighborhoods(6);
		ArrayList<String> added = new ArrayList<String>();
		int changed = GCNGBBStyleBidirectionalNgDssr.addMinimumNewPairsRepeatedSegment(
				Arrays.asList(1, 2, 3, 1, 4, 5, 4), neighborhoods, 6, added);
		assertEquals(1, changed, "the one-pair repeated segment should be selected");
		assertTrue(neighborhoods[5].contains(4), "the selected segment must remember job 4 through job 5");
		assertTrue(!neighborhoods[2].contains(1) && !neighborhoods[3].contains(1),
				"the more expensive repeated segment must remain unchanged");
		assertEquals(Arrays.asList("5<-4"), added, "diagnostic pair order must be deterministic");

		int blocked = GCNGBBStyleBidirectionalNgDssr.addMinimumNewPairsRepeatedSegment(
				Arrays.asList(4, 5, 4, 6, 1, 6), neighborhoods, 6, null);
		assertEquals(-1, blocked, "an already blocked repeated segment excludes the complete route");
		assertTrue(!neighborhoods[1].contains(6), "an already blocked route must not add unrelated pairs");

		PackedBitSet[] tieNeighborhoods = neighborhoods(6);
		int tieChanged = GCNGBBStyleBidirectionalNgDssr.addMinimumNewPairsRepeatedSegment(
				Arrays.asList(1, 2, 1, 3, 4, 3), tieNeighborhoods, 6, null);
		assertEquals(1, tieChanged, "one complete segment should be added under a tie");
		assertTrue(tieNeighborhoods[2].contains(1) && !tieNeighborhoods[4].contains(3),
				"equal segments must keep the first sequence order");

		PackedBitSet[] duplicateMiddle = neighborhoods(6);
		int duplicateChanged = GCNGBBStyleBidirectionalNgDssr.addMinimumNewPairsRepeatedSegment(
				Arrays.asList(1, 2, 3, 4, 1), duplicateMiddle, 6, null);
		assertEquals(3, duplicateChanged, "each distinct middle job needs one directed ng pair");
		assertTrue(duplicateMiddle[2].contains(1) && duplicateMiddle[3].contains(1) && duplicateMiddle[4].contains(1),
				"the chosen complete segment must be fully blocked");

		System.out.println("NgDssrMinimumRepeatedSegmentUpdateTest passed");
	}

	private static PackedBitSet[] neighborhoods(int n) {
		PackedBitSet[] result = new PackedBitSet[n + 2];
		for (int job = 1; job <= n; job++) {
			result[job] = new PackedBitSet(n + 2);
		}
		return result;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void assertEquals(int expected, int actual, String message) {
		if (expected != actual) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertEquals(List<String> expected, List<String> actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertEquals(String expected, String actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
