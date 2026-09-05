package TWETBPC.Util;

import java.util.BitSet;

/**
 * time-indexed arc 集合。小定义域使用单个扁平 BitSet，大定义域按普通弧分段保存时间位集。
 *
 * 2026-09-05: Java BitSet 只接受 int 索引；当 (from,to,time) 的扁平定义域超过
 * Integer.MAX_VALUE 时，必须改用 pair-local 时间索引，避免乘法溢出。
 */
public final class TimeIndexedArcSet {

	private final int pairWidth;
	private final int pairCount;
	private final int horizon;
	private final long totalArcSlots;
	private final BitSet flatBits;
	private BitSet[] timesByPair;
	private long cardinality;

	public TimeIndexedArcSet(int pairWidth, int horizon) {
		if (pairWidth <= 0 || horizon < 0) {
			throw new IllegalArgumentException("Invalid time-indexed arc dimensions");
		}
		long pairCountLong = (long) pairWidth * pairWidth;
		if (pairCountLong > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Too many time-indexed arc pairs: " + pairCountLong);
		}
		this.pairWidth = pairWidth;
		this.pairCount = (int) pairCountLong;
		this.horizon = horizon;
		try {
			this.totalArcSlots = Math.multiplyExact(pairCountLong, horizon + 1L);
		} catch (ArithmeticException ex) {
			throw new IllegalArgumentException("Time-indexed arc domain exceeds long capacity", ex);
		}
		if (totalArcSlots <= Integer.MAX_VALUE) {
			this.flatBits = new BitSet((int) totalArcSlots);
			this.timesByPair = null;
		} else {
			this.flatBits = null;
			this.timesByPair = new BitSet[pairCount];
		}
	}

	public boolean get(int from, int to, int time) {
		if (flatBits != null) {
			return flatBits.get(flatIndex(from, to, time));
		}
		ensureSegmentedAvailable();
		BitSet times = timesByPair[pairIndex(from, to)];
		return times != null && times.get(time);
	}

	public void set(int from, int to, int time) {
		if (flatBits != null) {
			int index = flatIndex(from, to, time);
			if (!flatBits.get(index)) {
				flatBits.set(index);
				cardinality++;
			}
			return;
		}
		ensureSegmentedAvailable();
		int pair = pairIndex(from, to);
		BitSet times = timesByPair[pair];
		if (times == null) {
			times = new BitSet();
			timesByPair[pair] = times;
		}
		if (!times.get(time)) {
			times.set(time);
			cardinality++;
		}
	}

	public boolean isEmpty() {
		return cardinality == 0L;
	}

	public long cardinality() {
		return cardinality;
	}

	public int getPairWidth() {
		return pairWidth;
	}

	public int getHorizon() {
		return horizon;
	}

	public long getTotalArcSlots() {
		return totalArcSlots;
	}

	public boolean usesSegmentedStorage() {
		return flatBits == null;
	}

	/** 小定义域写回 Node 时复用旧的扁平合并路径；大定义域返回 null。 */
	public BitSet getFlatBits() {
		return flatBits;
	}

	/**
	 * 将大定义域的分段位集交给 Node。调用后当前集合不再允许查询或写入。
	 */
	public BitSet[] takeSegmentedTimesByPair() {
		if (flatBits != null) {
			throw new IllegalStateException("Flat time-indexed arc set has no segmented storage");
		}
		ensureSegmentedAvailable();
		BitSet[] result = timesByPair;
		timesByPair = null;
		cardinality = 0L;
		return result;
	}

	private int flatIndex(int from, int to, int time) {
		return time * pairCount + pairIndex(from, to);
	}

	private int pairIndex(int from, int to) {
		return from * pairWidth + to;
	}

	private void ensureSegmentedAvailable() {
		if (timesByPair == null) {
			throw new IllegalStateException("Segmented time-indexed arc set has already been transferred");
		}
	}
}
