package TWETBPC.BP;

import java.util.BitSet;
import java.util.List;

import TWETBPC.Model.TWETColumn;

/**
 * 由一组有向 arc 的总流量构成的 robust 分支行。每条完整机器序列在该行上的系数均为整数。
 */
public final class AggregateArcBranchConstraint {

	private final String family;
	private final String description;
	private final BitSet memberArcs;
	private final int arcWidth;
	private final boolean lowerBound;
	private final int rhs;

	public AggregateArcBranchConstraint(String family, String description, BitSet memberArcs, int arcWidth,
			boolean lowerBound, int rhs) {
		this.family = family;
		this.description = description;
		this.memberArcs = (BitSet) memberArcs.clone();
		this.arcWidth = arcWidth;
		this.lowerBound = lowerBound;
		this.rhs = rhs;
	}

	public String getFamily() {
		return family;
	}

	public String getDescription() {
		return description;
	}

	public boolean isLowerBound() {
		return lowerBound;
	}

	public int getRhs() {
		return rhs;
	}

	/** 计算一条机器序列经过本分支 arc 集合的次数。 */
	public int coefficient(TWETColumn column, int sink) {
		List<Integer> sequence = column.getSequence();
		if (sequence.isEmpty()) {
			return 0;
		}
		int count = 0;
		int first = sequence.get(0).intValue();
		if (containsArc(0, first)) {
			count++;
		}
		for (int index = 1; index < sequence.size(); index++) {
			int from = sequence.get(index - 1).intValue();
			int to = sequence.get(index).intValue();
			if (containsArc(from, to)) {
				count++;
			}
		}
		int last = sequence.get(sequence.size() - 1).intValue();
		if (containsArc(last, sink)) {
			count++;
		}
		return count;
	}

	/** 将 aggregate row dual 展开到已有的 arc-dual 接口，pricing 无需新增资源状态。 */
	public void addDualTo(double[][] arcDual, double dual) {
		for (int bit = memberArcs.nextSetBit(0); bit >= 0; bit = memberArcs.nextSetBit(bit + 1)) {
			int from = bit / arcWidth;
			int to = bit % arcWidth;
			arcDual[from][to] += dual;
		}
	}

	private boolean containsArc(int from, int to) {
		return from >= 0 && to >= 0 && memberArcs.get(from * arcWidth + to);
	}
}
