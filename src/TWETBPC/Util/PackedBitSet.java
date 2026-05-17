package TWETBPC.Util;

import java.util.Arrays;
import java.util.List;

/**
 * 基于 long 数组的轻量位集封装。
 * <p>
 * 之所以单独封装，而不是直接在外层到处写位运算，
 * 是为了在保留高效底层结构的同时，让调用层代码更可读。
 * <p>
 * 这类结构与旧 VRP 代码中大量使用的 bit-mask 思路是一致的：
 * 都是在用非常紧凑的方式表示集合，只是这里把相关操作集中到了一个类里。
 */
public final class PackedBitSet {

	/** 以 64 bit 为单位存储集合。 */
	private final long[] words;

	/**
	 * 构造指定宇宙大小的空位集。
	 *
	 * @param universeSize 可出现元素的编号上界范围
	 */
	public PackedBitSet(int universeSize) {
		this.words = new long[wordCount(universeSize)];
	}

	/** 内部 copy 构造器。 */
	private PackedBitSet(long[] words) {
		this.words = words;
	}

	/**
	 * 由 job 列表构造位集。
	 */
	public static PackedBitSet ofJobs(int universeSize, List<Integer> jobs) {
		PackedBitSet set = new PackedBitSet(universeSize + 1);
		for (int job : jobs) {
			set.add(job);
		}
		return set;
	}

	/**
	 * 根据宇宙大小估算需要多少个 long word。
	 */
	private static int wordCount(int universeSize) {
		return Math.max(1, (universeSize + 64) >>> 6);
	}

	/**
	 * 把某个 bit 置为 1。
	 */
	public void add(int bit) {
		int word = bit >>> 6;
		words[word] |= 1L << (bit & 63);
	}

	/**
	 * 判断某个 bit 是否存在。
	 */
	public boolean contains(int bit) {
		int word = bit >>> 6;
		return (words[word] & (1L << (bit & 63))) != 0L;
	}

	/**
	 * 判断两个位集是否有交集。
	 * <p>
	 * 这是后续做“列之间是否共享 job”“某 cut 范围是否被命中”等操作的基础接口。
	 */
	public boolean intersects(PackedBitSet other) {
		int len = Math.min(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			if ((words[i] & other.words[i]) != 0L) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断当前集合是否包含在另一个集合中。
	 * <p>
	 * BPC pricing 的 dominance graph 需要频繁判断“一个 label 的未来可扩展集合
	 * 是否不强于另一个 label”。这里直接在 bit 层做子集判断，避免在外层展开成 List。
	 */
	public boolean isSubsetOf(PackedBitSet other) {
		int len = Math.max(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			long a = i < words.length ? words[i] : 0L;
			long b = i < other.words.length ? other.words[i] : 0L;
			if ((a & ~b) != 0L) {
				return false;
			}
		}
		return true;
	}

	/** @return 当前集合是否是 {@code other} 的超集 */
	public boolean isSupersetOf(PackedBitSet other) {
		return other.isSubsetOf(this);
	}

	/** @return 当前位集的深拷贝 */
	public PackedBitSet copy() {
		return new PackedBitSet(Arrays.copyOf(words, words.length));
	}

	/** @return 底层 long 数组的副本 */
	public long[] toWordArray() {
		return Arrays.copyOf(words, words.length);
	}

	@Override
	public int hashCode() {
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
		return Arrays.equals(words, other.words);
	}

}
