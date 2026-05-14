package TWETBPC.Util;

import java.util.Arrays;
import java.util.List;

/**
 * 序列签名对象。
 * <p>
 * 该对象的用途很简单：把一条 job 序列转成一个可 hash、可 equals 的不可变签名，
 * 供列池做快速判重。
 * <p>
 * 旧 VRP 代码里的 route 判重更多依赖 route 内容本身和 pool 管理；
 * 这里单独提炼一个签名类，是为了让 Pool 的逻辑更干净。
 */
public final class SequenceSignature {

	/** 序列内容的不可变副本。 */
	private final int[] jobs;
	/** 预先缓存的 hash 值，避免频繁重复计算。 */
	private final int hash;

	/**
	 * 根据一条 job 序列构造签名。
	 */
	public SequenceSignature(List<Integer> sequence) {
		this.jobs = new int[sequence.size()];
		for (int i = 0; i < sequence.size(); i++) {
			this.jobs[i] = sequence.get(i);
		}
		this.hash = Arrays.hashCode(this.jobs);
	}

	/** @return 序列长度 */
	public int length() {
		return jobs.length;
	}

	/** @return 序列数组副本 */
	public int[] toArray() {
		return Arrays.copyOf(jobs, jobs.length);
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof SequenceSignature)) {
			return false;
		}
		SequenceSignature other = (SequenceSignature) obj;
		return Arrays.equals(jobs, other.jobs);
	}

	@Override
	public String toString() {
		return Arrays.toString(jobs);
	}

}
