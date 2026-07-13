package TWETBPC.Util;

import java.util.Random;

/** PackedBitSet 普通相交和排除掩码相交的边界、随机对拍。 */
public final class PackedBitSetIntersectionTest {

	private static final int RANDOM_CASES = 200_000;

	private PackedBitSetIntersectionTest() {
	}

	public static void main(String[] args) {
		verifyBoundaries();
		verifyRandomizedEquivalence();
		System.out.println("PackedBitSetIntersectionTest passed: randomCases=" + RANDOM_CASES);
	}

	private static void verifyBoundaries() {
		PackedBitSet left = new PackedBitSet(132);
		PackedBitSet right = new PackedBitSet(132);
		PackedBitSet excluded = new PackedBitSet(67);
		left.add(1);
		left.add(64);
		left.add(129);
		right.add(64);
		right.add(129);
		excluded.add(64);
		assertTrue(left.intersects(right), "ordinary intersection should see bit 64/129");
		assertTrue(left.intersectsExcluding(right, excluded), "bit 129 must survive shorter exclusion mask");

		PackedBitSet fullExcluded = new PackedBitSet(132);
		left.add(0);
		right.add(0);
		fullExcluded.add(0);
		fullExcluded.add(64);
		fullExcluded.add(129);
		assertTrue(!left.intersectsExcluding(right, fullExcluded), "all intersections are excluded");

		PackedBitSet disjoint = new PackedBitSet(132);
		disjoint.add(63);
		assertTrue(!left.intersects(disjoint), "word-boundary neighbors are not equal bits");
	}

	private static void verifyRandomizedEquivalence() {
		Random random = new Random(20260713L);
		for (int caseId = 0; caseId < RANDOM_CASES; caseId++) {
			int leftUniverse = 1 + random.nextInt(192);
			int rightUniverse = 1 + random.nextInt(192);
			int excludedUniverse = 1 + random.nextInt(192);
			PackedBitSet left = randomSet(leftUniverse, random);
			PackedBitSet right = randomSet(rightUniverse, random);
			PackedBitSet excluded = randomSet(excludedUniverse, random);

			boolean expectedOrdinary = referenceIntersects(left, right, null);
			boolean expectedExcluded = referenceIntersects(left, right, excluded);
			assertTrue(left.intersects(right) == expectedOrdinary,
					"ordinary mismatch at case " + caseId);
			assertTrue(left.intersectsExcluding(right, excluded) == expectedExcluded,
					"excluded mismatch at case " + caseId);
		}
	}

	private static PackedBitSet randomSet(int universe, Random random) {
		PackedBitSet set = new PackedBitSet(universe);
		for (int bit = 0; bit < universe; bit++) {
			if (random.nextInt(7) == 0) {
				set.add(bit);
			}
		}
		return set;
	}

	private static boolean referenceIntersects(PackedBitSet left, PackedBitSet right, PackedBitSet excluded) {
		long[] rightWords = right.toWordArray();
		long[] excludedWords = excluded == null ? null : excluded.toWordArray();
		for (int bit = left.nextSetBit(0); bit >= 0; bit = left.nextSetBit(bit + 1)) {
			if (contains(rightWords, bit) && (excludedWords == null || !contains(excludedWords, bit))) {
				return true;
			}
		}
		return false;
	}

	private static boolean contains(long[] words, int bit) {
		int word = bit >>> 6;
		return word < words.length && (words[word] & (1L << (bit & 63))) != 0L;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
