package TWETBPC.GC;

import Common.PiecewiseLinearFunction;
import Common.Utility;
import TWETBPC.Util.PackedBitSet;

/**
 * TWET pricing 的单向 label。
 * <p>
 * 这个类刻意保留旧 VRP GC 中 Label 的核心风格：一个 label 表示一条真实的
 * partial path，{@link #father} 用于恢复序列，{@link #visitedSet} 用于保证
 * elementary sequence。区别在于 TWET 的成本不再是一个标量，而是一条关于完成时间的
 * reduced-cost frontier。
 */
public class Label implements Comparable<Label> {

	/** 当前 label 的末端 job；0 表示虚拟起点。 */
	public int jid;
	/** 父 label，用于恢复完整序列。 */
	public Label father;
	/** 当前真实路径已经访问过的 job 集合。 */
	public PackedBitSet visitedSet;
	/** 从当前 label 出发仍可能扩展的 job 集合，用于 dominance graph 的 node key。 */
	public PackedBitSet reachableSet;
	/** 当前 partial sequence 的 reduced-cost 函数。 */
	public PiecewiseLinearFunction frontier;
	/** frontier 的全局最小值，供队列排序和快速过滤使用。 */
	public double minReducedCost;
	/** 传播占优后只做标记，出队时跳过，避免在 PriorityQueue 中删除对象。 */
	public boolean isDominated;

	public Label(int jid, Label father, PackedBitSet visitedSet, PackedBitSet reachableSet,
			PiecewiseLinearFunction frontier) {
		this.jid = jid;
		this.father = father;
		this.visitedSet = visitedSet;
		this.reachableSet = reachableSet;
		this.frontier = frontier;
		this.minReducedCost = computeMin(frontier);
		this.isDominated = false;
	}

	/** 刷新 frontier 被修改后的最小 reduced cost。 */
	public void refreshMinReducedCost() {
		this.minReducedCost = computeMin(frontier);
	}

	private double computeMin(PiecewiseLinearFunction f) {
		if (f == null || f.head == null) {
			return Utility.big_M;
		}
		return f.findMinimal(false, true)[0];
	}

	@Override
	public int compareTo(Label other) {
		if (Utility.compareLt(minReducedCost, other.minReducedCost)) {
			return -1;
		}
		if (Utility.compareGt(minReducedCost, other.minReducedCost)) {
			return 1;
		}
		return Integer.compare(jid, other.jid);
	}

}
