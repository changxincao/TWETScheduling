package TWETBPC.Model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import TWETBPC.Util.PackedBitSet;
import TWETBPC.Util.SequenceSignature;

/**
 * TWET 列对象。
 * <p>
 * 在当前框架里，一列表示“一台机器上的一条 job 序列以及它对应的成本”。
 * 这和旧 VRP-BPC 里的 route 列在角色上是一一对应的：
 * <ul>
 * <li>旧代码的一列 = 一条路径/路线；</li>
 * <li>这里的一列 = 一条单机调度序列。</li>
 * </ul>
 * <p>
 * 该对象是列池中的基础值对象，负责保存：
 * 序列内容、签名、job 集、成本、来源等信息。
 */
public final class TWETColumn {

	/** 列在全局列池中的编号。 */
	private final int id;
	/** 该列对应的单机 job 序列。 */
	private final ArrayList<Integer> sequence;
	/** 用于快速判重的序列签名。 */
	private final SequenceSignature signature;
	/** 该列覆盖的 job 集合；用于后续做覆盖判断、冲突判断等。 */
	private final PackedBitSet jobs;
	/** 每个 job 在该列序列中出现的次数；time-indexed pseudo-schedule 列需要该系数。 */
	private final int[] jobVisitCounts;
	/** 列成本，即把这条序列放在一台机器上的目标值。 */
	private final double cost;
	/** 列的来源类型。 */
	private final ColumnSource source;
	/** 是否属于初始 seed 列。 */
	private final boolean seedColumn;

	/**
	 * 构造一个列对象。
	 *
	 * @param id         全局列 id
	 * @param sequence   单机序列
	 * @param jobCount   job 总数，用于构造 bitset
	 * @param cost       序列成本
	 * @param source     列来源
	 * @param seedColumn 是否是初始列
	 */
	public TWETColumn(int id, List<Integer> sequence, int jobCount, double cost, ColumnSource source, boolean seedColumn) {
		this.id = id;
		this.sequence = new ArrayList<Integer>(sequence);
		this.signature = new SequenceSignature(this.sequence);
		this.jobs = PackedBitSet.ofJobs(jobCount, this.sequence);
		this.jobVisitCounts = new int[jobCount + 1];
		for (int job : this.sequence) {
			if (job >= 1 && job <= jobCount) {
				this.jobVisitCounts[job]++;
			}
		}
		this.cost = cost;
		this.source = source;
		this.seedColumn = seedColumn;
	}

	/** @return 列 id */
	public int getId() {
		return id;
	}

	/**
	 * 返回该列的只读序列视图。
	 * <p>
	 * 外部不应直接修改列中的 job 顺序，因此这里暴露不可修改视图。
	 */
	public List<Integer> getSequence() {
		return Collections.unmodifiableList(sequence);
	}

	/** @return 序列签名，用于判重和比较 */
	public SequenceSignature getSignature() {
		return signature;
	}

	/**
	 * 返回该列对应的 job 集。
	 * <p>
	 * 当前返回的是内部 bitset 对象本身，
	 * 约定调用方把它视为只读对象使用。
	 */
	public PackedBitSet getJobs() {
		return jobs;
	}

	/** @return 列成本 */
	public double getCost() {
		return cost;
	}

	/** @return 列来源 */
	public ColumnSource getSource() {
		return source;
	}

	/** @return 该列是否属于 seed 列 */
	public boolean isSeedColumn() {
		return seedColumn;
	}

	/** @return 序列长度 */
	public int size() {
		return sequence.size();
	}

	/**
	 * 判断该列是否覆盖给定 job。
	 */
	public boolean containsJob(int job) {
		return jobs.contains(job);
	}

	/**
	 * @return 该 job 在列序列中出现的次数。普通 elementary 列为 0/1；论文 DWM pseudo-schedule 列可大于 1。
	 */
	public int getJobVisitCount(int job) {
		return job >= 0 && job < jobVisitCounts.length ? jobVisitCounts[job] : 0;
	}

	/**
	 * 判断该列是否经过给定弧。
	 * <p>
	 * 这里的“弧”是把调度序列看成路径后得到的相邻关系：
	 * <ul>
	 * <li>0 -> firstJob 表示该列以某 job 作为首任务；</li>
	 * <li>jobA -> jobB 表示序列中 A 紧接着 B；</li>
	 * <li>lastJob -> sink 表示该列在最后一个任务后结束。</li>
	 * </ul>
	 * 这个接口是当前弧分支器直接依赖的基础判定逻辑。
	 */
	public boolean visitsArc(int from, int to, int sinkId) {
		return getArcVisitCount(from, to, sinkId) > 0;
	}

	/**
	 * @return 调度序列中给定普通 arc 的出现次数。对重复 job 的 pseudo-schedule，arc 系数可能大于 1。
	 */
	public int getArcVisitCount(int from, int to, int sinkId) {
		if (sequence.isEmpty()) {
			return 0;
		}
		int count = 0;
		if (from == 0 && sequence.get(0) == to) {
			count++;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (sequence.get(i - 1) == from && sequence.get(i) == to) {
				count++;
			}
		}
		if (to == sinkId && sequence.get(sequence.size() - 1) == from) {
			count++;
		}
		return count;
	}

	@Override
	public String toString() {
		return "TWETColumn{id=" + id + ", seq=" + sequence + ", cost=" + cost + ", source=" + source + "}";
	}

}
