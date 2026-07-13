package TWETBPC.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** 单 word 内联与旧 long[] 语义的逐操作随机对拍。 */
public final class PackedBitSetStorageTest {

	private static final int RANDOM_CASES = 100_000;

	private PackedBitSetStorageTest() {
	}

	public static void main(String[] args) {
		verifyBoundariesAndExceptions();
		verifyRandomOperations();
		System.out.println("PackedBitSetStorageTest passed: randomCases=" + RANDOM_CASES);
	}

	private static void verifyBoundariesAndExceptions() {
		PackedBitSet single = new PackedBitSet(63);
		single.add(0);
		single.add(63);
		assertArrayEquals(new long[] { Long.MIN_VALUE | 1L }, single.toWordArray(), "single boundaries");
		assertThrows(() -> single.add(64), "single add must retain old capacity failure");
		assertThrows(() -> single.remove(64), "single remove must retain old capacity failure");
		assertThrows(() -> single.contains(64), "single contains must retain old capacity failure");

		PackedBitSet multi = new PackedBitSet(64);
		multi.add(64);
		assertArrayEquals(new long[] { 0L, 1L }, multi.toWordArray(), "64 creates two words under old sizing rule");

		PackedBitSet sameSingle = new PackedBitSet(1);
		sameSingle.add(0);
		PackedBitSet longer = new PackedBitSet(64);
		longer.add(0);
		assertTrue(!sameSingle.equals(longer), "equals retains storage-length semantics");
		assertTrue(sameSingle.hashCode() == Arrays.hashCode(new long[] { 1L }), "single hash matches old array hash");
	}

	private static void verifyRandomOperations() {
		Random random = new Random(20260713L);
		for (int caseId = 0; caseId < RANDOM_CASES; caseId++) {
			ReferenceSet leftReference = randomReference(1 + random.nextInt(192), random);
			ReferenceSet rightReference = randomReference(1 + random.nextInt(192), random);
			ReferenceSet excludedReference = randomReference(1 + random.nextInt(192), random);
			PackedBitSet left = fromReference(leftReference);
			PackedBitSet right = fromReference(rightReference);
			PackedBitSet excluded = fromReference(excludedReference);

			assertTrue(left.isEmpty() == leftReference.isEmpty(), "isEmpty case " + caseId);
			assertTrue(left.cardinality() == leftReference.cardinality(), "cardinality case " + caseId);
			assertTrue(left.intersects(right) == leftReference.intersects(rightReference), "intersects case " + caseId);
			assertTrue(left.intersectsExcluding(right, excluded)
					== leftReference.intersectsExcluding(rightReference, excludedReference),
					"intersectsExcluding case " + caseId);
			assertTrue(left.isSubsetOf(right) == leftReference.isSubsetOf(rightReference), "subset case " + caseId);
			assertTrue(left.isSupersetOf(right) == rightReference.isSubsetOf(leftReference), "superset case " + caseId);

			assertEquivalent(left.and(right), leftReference.and(rightReference), "and case " + caseId);
			assertEquivalent(left.or(right), leftReference.or(rightReference), "or case " + caseId);
			assertEquivalent(left.andNot(right), leftReference.andNot(rightReference), "andNot case " + caseId);

			PackedBitSet inPlace = left.copy();
			inPlace.andInPlace(right);
			assertEquivalent(inPlace, leftReference.and(rightReference), "andInPlace case " + caseId);
			inPlace = left.copy();
			inPlace.orInPlace(right);
			assertEquivalent(inPlace, leftReference.or(rightReference), "orInPlace case " + caseId);
			inPlace = left.copy();
			inPlace.andNotInPlace(right);
			assertEquivalent(inPlace, leftReference.andNot(rightReference), "andNotInPlace case " + caseId);

			assertEquivalent(left.copy(), leftReference, "copy case " + caseId);
			assertTrue(left.hashCode() == Arrays.hashCode(leftReference.words), "hash case " + caseId);
			assertIteration(left, leftReference, caseId);

			PackedBitSet mutable = left.copy();
			ReferenceSet mutableReference = leftReference.copy();
			for (int operation = 0; operation < 5; operation++) {
				int bit = random.nextInt(mutableReference.words.length << 6);
				if (random.nextBoolean()) {
					mutable.add(bit);
					mutableReference.add(bit);
				} else {
					mutable.remove(bit);
					mutableReference.remove(bit);
				}
				assertTrue(mutable.contains(bit) == mutableReference.contains(bit),
						"mutation contains case " + caseId);
			}
			assertEquivalent(mutable, mutableReference, "mutation case " + caseId);
		}
	}

	private static ReferenceSet randomReference(int universe, Random random) {
		ReferenceSet set = new ReferenceSet(universe);
		for (int bit = 0; bit < universe; bit++) {
			if (random.nextInt(7) == 0) {
				set.add(bit);
			}
		}
		return set;
	}

	private static PackedBitSet fromReference(ReferenceSet reference) {
		PackedBitSet set = new PackedBitSet(reference.universe);
		for (int word = 0; word < reference.words.length; word++) {
			long value = reference.words[word];
			while (value != 0L) {
				set.add((word << 6) + Long.numberOfTrailingZeros(value));
				value &= value - 1;
			}
		}
		return set;
	}

	private static void assertIteration(PackedBitSet actual, ReferenceSet expected, int caseId) {
		List<Integer> viaNext = new ArrayList<>();
		for (int bit = actual.nextSetBit(-3); bit >= 0; bit = actual.nextSetBit(bit + 1)) {
			viaNext.add(bit);
		}
		List<Integer> viaConsumer = new ArrayList<>();
		actual.forEachSetBit(viaConsumer::add);
		List<Integer> reference = expected.bits();
		assertTrue(viaNext.equals(reference), "nextSetBit case " + caseId);
		assertTrue(viaConsumer.equals(reference), "forEachSetBit case " + caseId);
	}

	private static void assertEquivalent(PackedBitSet actual, ReferenceSet expected, String message) {
		assertArrayEquals(expected.words, actual.toWordArray(), message);
		PackedBitSet rebuilt = fromReference(expected);
		assertTrue(actual.equals(rebuilt), message + " equals");
		assertTrue(actual.hashCode() == rebuilt.hashCode(), message + " hash");
	}

	private static void assertArrayEquals(long[] expected, long[] actual, String message) {
		if (!Arrays.equals(expected, actual)) {
			throw new AssertionError(message + ": expected=" + Arrays.toString(expected)
					+ ", actual=" + Arrays.toString(actual));
		}
	}

	private static void assertThrows(Runnable action, String message) {
		try {
			action.run();
		} catch (ArrayIndexOutOfBoundsException expected) {
			return;
		}
		throw new AssertionError(message);
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static final class ReferenceSet {
		private final int universe;
		private final long[] words;

		private ReferenceSet(int universe) {
			this.universe = universe;
			this.words = new long[Math.max(1, (universe + 64) >>> 6)];
		}

		private ReferenceSet(int universe, long[] words) {
			this.universe = universe;
			this.words = words;
		}

		private void add(int bit) {
			words[bit >>> 6] |= 1L << (bit & 63);
		}

		private void remove(int bit) {
			words[bit >>> 6] &= ~(1L << (bit & 63));
		}

		private boolean contains(int bit) {
			return (words[bit >>> 6] & (1L << (bit & 63))) != 0L;
		}

		private ReferenceSet copy() {
			return new ReferenceSet(universe, Arrays.copyOf(words, words.length));
		}

		private boolean isEmpty() {
			for (long word : words) {
				if (word != 0L) {
					return false;
				}
			}
			return true;
		}

		private int cardinality() {
			int count = 0;
			for (long word : words) {
				count += Long.bitCount(word);
			}
			return count;
		}

		private boolean intersects(ReferenceSet other) {
			int length = Math.min(words.length, other.words.length);
			for (int i = 0; i < length; i++) {
				if ((words[i] & other.words[i]) != 0L) {
					return true;
				}
			}
			return false;
		}

		private boolean intersectsExcluding(ReferenceSet other, ReferenceSet excluded) {
			int length = Math.min(words.length, other.words.length);
			for (int i = 0; i < length; i++) {
				long excludedWord = i < excluded.words.length ? excluded.words[i] : 0L;
				if ((words[i] & other.words[i] & ~excludedWord) != 0L) {
					return true;
				}
			}
			return false;
		}

		private boolean isSubsetOf(ReferenceSet other) {
			int length = Math.max(words.length, other.words.length);
			for (int i = 0; i < length; i++) {
				long left = i < words.length ? words[i] : 0L;
				long right = i < other.words.length ? other.words[i] : 0L;
				if ((left & ~right) != 0L) {
					return false;
				}
			}
			return true;
		}

		private ReferenceSet and(ReferenceSet other) {
			long[] result = Arrays.copyOf(words, words.length);
			int length = Math.min(result.length, other.words.length);
			for (int i = 0; i < length; i++) {
				result[i] &= other.words[i];
			}
			Arrays.fill(result, length, result.length, 0L);
			return new ReferenceSet(universe, result);
		}

		private ReferenceSet or(ReferenceSet other) {
			long[] result = Arrays.copyOf(words, words.length);
			int length = Math.min(result.length, other.words.length);
			for (int i = 0; i < length; i++) {
				result[i] |= other.words[i];
			}
			return new ReferenceSet(universe, result);
		}

		private ReferenceSet andNot(ReferenceSet other) {
			long[] result = Arrays.copyOf(words, words.length);
			int length = Math.min(result.length, other.words.length);
			for (int i = 0; i < length; i++) {
				result[i] &= ~other.words[i];
			}
			return new ReferenceSet(universe, result);
		}

		private List<Integer> bits() {
			List<Integer> result = new ArrayList<>();
			for (int word = 0; word < words.length; word++) {
				long value = words[word];
				while (value != 0L) {
					result.add((word << 6) + Long.numberOfTrailingZeros(value));
					value &= value - 1;
				}
			}
			return result;
		}
	}
}
