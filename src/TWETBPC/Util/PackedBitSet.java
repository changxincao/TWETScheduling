package TWETBPC.Util;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 基于 long word 的轻量位集封装。
 * <p>
 * 之所以单独封装，而不是直接在外层到处写位运算，
 * 是为了在保留高效底层结构的同时，让调用层代码更可读。
 * <p>
 * 这类结构与旧 VRP 代码中大量使用的 bit-mask 思路是一致的：
 * 都是在用非常紧凑的方式表示集合，只是这里把相关操作集中到了一个类里。
 * <p>
 * 2026-07-13: 50/60-job pricing 的集合只有一个 word。该场景把 word 直接内联到对象中，
 * 避免每个集合额外分配单元素 {@code long[]}；更大集合仍使用数组后端。
 */
public final class PackedBitSet {

	/** 单 word 后端直接使用该字段；多 word 时使用 {@link #words}。 */
	private long singleWord;
	private final long[] words;

	/**
	 * 构造指定宇宙大小的空位集。
	 *
	 * @param universeSize 可出现元素的编号上界范围
	 */
	public PackedBitSet(int universeSize) {
		int count = wordCount(universeSize);
		this.words = count == 1 ? null : new long[count];
	}

	private PackedBitSet(long singleWord) {
		this.singleWord = singleWord;
		this.words = null;
	}

	private PackedBitSet(long[] words) {
		this.words = words;
	}

	/** 由 job 列表构造位集。 */
	public static PackedBitSet ofJobs(int universeSize, List<Integer> jobs) {
		PackedBitSet set = new PackedBitSet(universeSize + 1);
		for (int job : jobs) {
			set.add(job);
		}
		return set;
	}

	private static int wordCount(int universeSize) {
		return Math.max(1, (universeSize + 64) >>> 6);
	}

	private boolean isSingleWord() {
		return words == null;
	}

	private long wordAtOrZero(int index) {
		if (isSingleWord()) {
			return index == 0 ? singleWord : 0L;
		}
		return index < words.length ? words[index] : 0L;
	}

	private void checkSingleWordBit(int bit) {
		if ((bit >>> 6) != 0) {
			throw new ArrayIndexOutOfBoundsException(bit >>> 6);
		}
	}

	/** 把某个 bit 置为 1。 */
	public void add(int bit) {
		if (isSingleWord()) {
			checkSingleWordBit(bit);
			singleWord |= 1L << (bit & 63);
			return;
		}
		int word = bit >>> 6;
		words[word] |= 1L << (bit & 63);
	}

	/**
	 * 把某个 bit 清零。
	 * <p>
	 * 2026-05-20: pricing 预处理 reach bitset 时需要频繁删除已访问点或被禁弧点，
	 * 这里提供底层 O(1) 删除，避免外层退回 List 扫描。
	 */
	public void remove(int bit) {
		if (isSingleWord()) {
			checkSingleWordBit(bit);
			singleWord &= ~(1L << (bit & 63));
			return;
		}
		int word = bit >>> 6;
		words[word] &= ~(1L << (bit & 63));
	}

	/** 判断某个 bit 是否存在。 */
	public boolean contains(int bit) {
		if (isSingleWord()) {
			checkSingleWordBit(bit);
			return (singleWord & (1L << (bit & 63))) != 0L;
		}
		int word = bit >>> 6;
		return (words[word] & (1L << (bit & 63))) != 0L;
	}

	/** @return 当前集合是否为空。 */
	public boolean isEmpty() {
		if (isSingleWord()) {
			return singleWord == 0L;
		}
		for (long word : words) {
			if (word != 0L) {
				return false;
			}
		}
		return true;
	}

	/** @return 当前集合里 1 bit 的数量。 */
	public int cardinality() {
		if (isSingleWord()) {
			return Long.bitCount(singleWord);
		}
		int count = 0;
		for (long word : words) {
			count += Long.bitCount(word);
		}
		return count;
	}

	/** 判断两个位集是否有交集。 */
	public boolean intersects(PackedBitSet other) {
		if (isSingleWord() || other.isSingleWord()) {
			return (wordAtOrZero(0) & other.wordAtOrZero(0)) != 0L;
		}
		int len = Math.min(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			if ((words[i] & other.words[i]) != 0L) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断两个位集在排除指定元素后是否仍有交集。
	 * <p>
	 * 2026-07-13: ng-DSSR join 需要忽略 source bit 和 dual window 人工写入 memory 的零 dual job。
	 */
	public boolean intersectsExcluding(PackedBitSet other, PackedBitSet excluded) {
		if (isSingleWord() || other.isSingleWord()) {
			return (wordAtOrZero(0) & other.wordAtOrZero(0) & ~excluded.wordAtOrZero(0)) != 0L;
		}
		int len = Math.min(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			long intersection = words[i] & other.words[i] & ~excluded.wordAtOrZero(i);
			if (intersection != 0L) {
				return true;
			}
		}
		return false;
	}

	/** 原地取交集。 */
	public void andInPlace(PackedBitSet other) {
		if (isSingleWord()) {
			singleWord &= other.wordAtOrZero(0);
			return;
		}
		if (other.isSingleWord()) {
			words[0] &= other.singleWord;
			Arrays.fill(words, 1, words.length, 0L);
			return;
		}
		int len = Math.min(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			words[i] &= other.words[i];
		}
		Arrays.fill(words, len, words.length, 0L);
	}

	/** @return 当前集合与 {@code other} 的交集副本。 */
	public PackedBitSet and(PackedBitSet other) {
		PackedBitSet result = copy();
		result.andInPlace(other);
		return result;
	}

	/**
	 * 原地取并集；word 数不同时，只在当前集合已有 word 范围内合并。
	 */
	public void orInPlace(PackedBitSet other) {
		if (isSingleWord()) {
			singleWord |= other.wordAtOrZero(0);
			return;
		}
		if (other.isSingleWord()) {
			words[0] |= other.singleWord;
			return;
		}
		int len = Math.min(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			words[i] |= other.words[i];
		}
	}

	/** @return 当前集合与 {@code other} 的并集副本。 */
	public PackedBitSet or(PackedBitSet other) {
		PackedBitSet result = copy();
		result.orInPlace(other);
		return result;
	}

	/** 原地删除 {@code other} 中出现的 bit。 */
	public void andNotInPlace(PackedBitSet other) {
		if (isSingleWord()) {
			singleWord &= ~other.wordAtOrZero(0);
			return;
		}
		if (other.isSingleWord()) {
			words[0] &= ~other.singleWord;
			return;
		}
		int len = Math.min(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			words[i] &= ~other.words[i];
		}
	}

	/** @return 当前集合减去 {@code other} 后的副本。 */
	public PackedBitSet andNot(PackedBitSet other) {
		PackedBitSet result = copy();
		result.andNotInPlace(other);
		return result;
	}

	/**
	 * 从指定位置开始寻找下一个置 1 的 bit。
	 *
	 * @return 找到的 bit 编号；如果不存在，返回 -1
	 */
	public int nextSetBit(int fromInclusive) {
		int bit = Math.max(0, fromInclusive);
		int wordIndex = bit >>> 6;
		if (isSingleWord()) {
			if (wordIndex != 0) {
				return -1;
			}
			long word = singleWord & (-1L << (bit & 63));
			return word == 0L ? -1 : Long.numberOfTrailingZeros(word);
		}
		if (wordIndex >= words.length) {
			return -1;
		}
		long word = words[wordIndex] & (-1L << (bit & 63));
		while (true) {
			if (word != 0L) {
				return (wordIndex << 6) + Long.numberOfTrailingZeros(word);
			}
			wordIndex++;
			if (wordIndex >= words.length) {
				return -1;
			}
			word = words[wordIndex];
		}
	}

	/** 按从小到大的 bit 编号遍历集合元素。 */
	public void forEachSetBit(IntConsumer consumer) {
		if (isSingleWord()) {
			long word = singleWord;
			while (word != 0L) {
				int offset = Long.numberOfTrailingZeros(word);
				consumer.accept(offset);
				word &= word - 1;
			}
			return;
		}
		for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
			long word = words[wordIndex];
			while (word != 0L) {
				int offset = Long.numberOfTrailingZeros(word);
				consumer.accept((wordIndex << 6) + offset);
				word &= word - 1;
			}
		}
	}

	/**
	 * 判断当前集合是否包含在另一个集合中，供 dominance graph 高频使用。
	 */
	public boolean isSubsetOf(PackedBitSet other) {
		if (isSingleWord()) {
			return (singleWord & ~other.wordAtOrZero(0)) == 0L;
		}
		for (int i = 0; i < words.length; i++) {
			if ((words[i] & ~other.wordAtOrZero(i)) != 0L) {
				return false;
			}
		}
		return true;
	}

	/** @return 当前集合是否是 {@code other} 的超集。 */
	public boolean isSupersetOf(PackedBitSet other) {
		return other.isSubsetOf(this);
	}

	/** @return 当前位集的深拷贝。 */
	public PackedBitSet copy() {
		return isSingleWord() ? new PackedBitSet(singleWord) : new PackedBitSet(Arrays.copyOf(words, words.length));
	}

	/** @return 底层 word 的数组副本。单 word 后端也保持旧接口返回长度 1 的新数组。 */
	public long[] toWordArray() {
		return isSingleWord() ? new long[] { singleWord } : Arrays.copyOf(words, words.length);
	}

	@Override
	public int hashCode() {
		if (isSingleWord()) {
			return 31 + (int) (singleWord ^ (singleWord >>> 32));
		}
		return Arrays.hashCode(words);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof PackedBitSet)) {
			return false;
		}
		PackedBitSet other = (PackedBitSet) obj;
		if (isSingleWord() != other.isSingleWord()) {
			return false;
		}
		return isSingleWord() ? singleWord == other.singleWord : Arrays.equals(words, other.words);
	}
}
