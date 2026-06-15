package TWETBPC.Model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * TWET cut 对象。
 * <p>
 * 这一层对应旧 VRP-BPC 中 cut pool 里保存的割描述，
 * 但当前阶段还没有绑定到具体 CPLEX 约束。
 * 因此它更像一个“割的元数据载体”：
 * 记录类型、作用范围、右端项和文字说明。
 */
public final class TWETCut {

	/** 割在全局 cut pool 中的编号。 */
	private final int id;
	/** 割类型。 */
	private final TWETCutType type;
	/** 割涉及的 job 集。 */
	private final ArrayList<Integer> scopeJobs;
	/** limited-node-memory SRI 的 memory job 集；为空表示不使用 node-memory 口径。 */
	private final ArrayList<Integer> memoryJobs;
	/** limited-arc-memory SRI 的 memory arc 集，使用全局 arcKey(from,to) 编码。 */
	private final ArrayList<Long> memoryArcs;
	/** memoryArcs 的 membership 缓存，避免列系数计算时反复构造 HashSet。 */
	private final HashSet<Long> memoryArcSet;
	/** subset-row multiplier，三元 SRI 默认为 1/2。 */
	private final double multiplier;
	/** 右端项。 */
	private final double rhs;
	/** 文字说明，用于调试或后续日志。 */
	private final String description;

	/**
	 * 构造一个 cut 描述对象。
	 */
	public TWETCut(int id, TWETCutType type, List<Integer> scopeJobs, double rhs, String description) {
		this(id, type, scopeJobs, null, null, 0.5, rhs, description);
	}

	/**
	 * 构造带 node-memory 语义的 subset-row cut。
	 * <p>
	 * 2026-06-14: memoryJobs 非空时按 limited-node-memory SRI 扫描列系数；保持旧构造函数不变，
	 * 避免默认 full-SRI 路径受影响。
	 */
	public TWETCut(int id, TWETCutType type, List<Integer> scopeJobs, List<Integer> memoryJobs, double multiplier,
			double rhs, String description) {
		this(id, type, scopeJobs, memoryJobs, null, multiplier, rhs, description);
	}

	/**
	 * 构造带 arc-memory 语义的 subset-row cut。
	 * <p>
	 * 2026-06-15: arc-memory 和 node-memory 只启用一种；memoryArcs 非空时，列系数按 route 中经过的 arc
	 * 是否属于 memory arc set 来决定 state 是否清零。
	 */
	public TWETCut(int id, TWETCutType type, List<Integer> scopeJobs, List<Integer> memoryJobs,
			List<Long> memoryArcs, double multiplier, double rhs, String description) {
		this.id = id;
		this.type = type;
		this.scopeJobs = new ArrayList<Integer>(scopeJobs);
		this.memoryJobs = memoryJobs == null ? new ArrayList<Integer>() : new ArrayList<Integer>(memoryJobs);
		this.memoryArcs = memoryArcs == null ? new ArrayList<Long>() : new ArrayList<Long>(memoryArcs);
		this.memoryArcSet = new HashSet<Long>(this.memoryArcs);
		this.multiplier = multiplier;
		this.rhs = rhs;
		this.description = description;
	}

	/** @return cut id */
	public int getId() {
		return id;
	}

	/** @return cut 类型 */
	public TWETCutType getType() {
		return type;
	}

	/** @return 涉及的 job 集合（只读） */
	public List<Integer> getScopeJobs() {
		return Collections.unmodifiableList(scopeJobs);
	}

	/** @return limited-node-memory SRI 的 memory job 集合（只读）。 */
	public List<Integer> getMemoryJobs() {
		return Collections.unmodifiableList(memoryJobs);
	}

	/** @return limited-arc-memory SRI 的 memory arc 集合（只读）。 */
	public List<Long> getMemoryArcs() {
		return Collections.unmodifiableList(memoryArcs);
	}

	/** @return 该 cut 是否使用 limited-node-memory SRI 口径。 */
	public boolean hasMemoryJobs() {
		return !memoryJobs.isEmpty();
	}

	/** @return 该 cut 是否使用 limited-arc-memory SRI 口径。 */
	public boolean hasMemoryArcs() {
		return !memoryArcs.isEmpty();
	}

	/** @return 给定编码 arc 是否属于 limited-arc-memory set。 */
	public boolean containsMemoryArc(long arcKey) {
		return memoryArcSet.contains(Long.valueOf(arcKey));
	}

	/** @return 该 cut 是否使用任意 limited-memory SRI 口径。 */
	public boolean hasLimitedMemory() {
		return hasMemoryJobs() || hasMemoryArcs();
	}

	/** @return subset-row multiplier。 */
	public double getMultiplier() {
		return multiplier;
	}

	/** @return 右端项 */
	public double getRhs() {
		return rhs;
	}

	/** @return 文字说明 */
	public String getDescription() {
		return description;
	}

	/**
	 * 返回一个可用于判重的签名字符串。
	 * <p>
	 * 当前 cut pool 还比较轻量，因此直接用字符串签名做 dedup。
	 */
	public String signature() {
		return type + "|" + scopeJobs.toString() + "|M=" + memoryJobs.toString() + "|A="
				+ memoryArcs.toString() + "|p=" + multiplier + "|" + rhs + "|" + description;
	}

}
