package TWETBPC.Util;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

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
	 * 把某个 bit 清零。
	 * <p>
	 * 2026-05-20: 后续 pricing 若预处理 reach bitset，需要频繁从候选集合里删掉已访问点或被禁弧点，
	 * 这里提供底层 O(1) 删除，避免外层退回 List 扫描。
	 */
	public void remove(int bit) {
		int word = bit >>> 6;
		words[word] &= ~(1L << (bit & 63));
	}

	/**
	 * 判断某个 bit 是否存在。
	 */
	public boolean contains(int bit) {
		int word = bit >>> 6;
		return (words[word] & (1L << (bit & 63))) != 0L;
	}

	/**
	 * @return 当前集合是否为空。
	 */
	public boolean isEmpty() {
		for (long word : words) {
			if (word != 0L) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @return 当前集合里 1 bit 的数量。
	 */
	public int cardinality() {
		int count = 0;
		for (long word : words) {
			count += Long.bitCount(word);
		}
		return count;
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
	 * 原地取交集。
	 * <p>
	 * 主要用于后续把“时间可达集合”和“未访问集合”相交，得到当前 label 真正候选扩展点。
	 */
	public void andInPlace(PackedBitSet other) {
		int len = Math.min(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			words[i] &= other.words[i];
		}
		for (int i = len; i < words.length; i++) {
			words[i] = 0L;
		}
	}

	/**
	 * @return 当前集合与 {@code other} 的交集副本。
	 */
	public PackedBitSet and(PackedBitSet other) {
		PackedBitSet res = copy();
		res.andInPlace(other);
		return res;
	}

	/**
	 * 原地取并集。
	 * <p>
	 * 本类是固定 universe 的轻量 bitset；如果两个集合 word 数不同，只在当前集合已有 word 范围内合并。
	 */
	public void orInPlace(PackedBitSet other) {
		int len = Math.min(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			words[i] |= other.words[i];
		}
	}

	/**
	 * @return 当前集合与 {@code other} 的并集副本。
	 */
	public PackedBitSet or(PackedBitSet other) {
		PackedBitSet res = copy();
		res.orInPlace(other);
		return res;
	}

	/**
	 * 原地删除 {@code other} 中出现的 bit。
	 * <p>
	 * 后续 reach 预处理可用它一次性去掉 visited set 或 forbidden-successor set。
	 */
	public void andNotInPlace(PackedBitSet other) {
		int len = Math.min(words.length, other.words.length);
		for (int i = 0; i < len; i++) {
			words[i] &= ~other.words[i];
		}
	}

	/**
	 * @return 当前集合减去 {@code other} 后的副本。
	 */
	public PackedBitSet andNot(PackedBitSet other) {
		PackedBitSet res = copy();
		res.andNotInPlace(other);
		return res;
	}

	/**
	 * 从指定位置开始寻找下一个置 1 的 bit。
	 *
	 * @return 找到的 bit 编号；如果不存在，返回 -1。
	 */
	public int nextSetBit(int fromInclusive) {
		int bit = Math.max(0, fromInclusive);
		int wordIndex = bit >>> 6;
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

	/**
	 * 按从小到大的 bit 编号遍历集合元素。
	 * <p>
	 * 2026-05-20: 这是给后续高效 pricing 扩展准备的接口。旧 VRP 代码大量依赖 bit-mask 枚举候选点，
	 * 这里集中封装后，GC 层可以只关心“遍历候选 job”，不再暴露 long word 细节。
	 */
	public void forEachSetBit(IntConsumer consumer) {
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
