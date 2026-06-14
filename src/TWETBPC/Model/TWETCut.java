package TWETBPC.Model;

import java.util.ArrayList;
import java.util.Collections;
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
	/** limited-memory SRI 的 memory 集；为空表示使用普通 full-SRI 口径。 */
	private final ArrayList<Integer> memoryJobs;
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
		this(id, type, scopeJobs, null, 0.5, rhs, description);
	}

	/**
	 * 构造带 memory 语义的 subset-row cut。
	 * <p>
	 * 2026-06-14: memoryJobs 非空时按 limited-memory SRI 扫描列系数；保持旧构造函数不变，
	 * 避免默认 full-SRI 路径受影响。
	 */
	public TWETCut(int id, TWETCutType type, List<Integer> scopeJobs, List<Integer> memoryJobs, double multiplier,
			double rhs, String description) {
		this.id = id;
		this.type = type;
		this.scopeJobs = new ArrayList<Integer>(scopeJobs);
		this.memoryJobs = memoryJobs == null ? new ArrayList<Integer>() : new ArrayList<Integer>(memoryJobs);
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

	/** @return limited-memory SRI 的 memory 集合（只读）；为空表示普通 full-SRI。 */
	public List<Integer> getMemoryJobs() {
		return Collections.unmodifiableList(memoryJobs);
	}

	/** @return 该 cut 是否使用 limited-memory SRI 口径。 */
	public boolean hasMemoryJobs() {
		return !memoryJobs.isEmpty();
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
		return type + "|" + scopeJobs.toString() + "|M=" + memoryJobs.toString() + "|p=" + multiplier + "|" + rhs
				+ "|" + description;
	}

}
