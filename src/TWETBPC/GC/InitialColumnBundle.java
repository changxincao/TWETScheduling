package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import HEU.Solution;

/**
 * 初始列构造结果。
 * <p>
 * 这个对象只是一个简单的数据打包器，
 * 用来把“seed 解本身”和“由它构造出的列 id 集合”一起返回给 Tree。
 */
public final class InitialColumnBundle {

	/** 用来生成初始列的启发式种子解。 */
	private final Solution seedSolution;
	/** 根节点允许使用的初始列。 */
	private final ArrayList<Integer> initialColumnIds;
	/** 当前 incumbent 直接对应的完整列集合。 */
	private final ArrayList<Integer> incumbentColumnIds;

	/**
	 * 构造一个 bundle。
	 */
	public InitialColumnBundle(Solution seedSolution, List<Integer> initialColumnIds, List<Integer> incumbentColumnIds) {
		this.seedSolution = seedSolution;
		this.initialColumnIds = new ArrayList<Integer>(initialColumnIds);
		this.incumbentColumnIds = new ArrayList<Integer>(incumbentColumnIds);
	}

	/** @return 种子解对象 */
	public Solution getSeedSolution() {
		return seedSolution;
	}

	/** @return 初始列 id 集（只读） */
	public List<Integer> getInitialColumnIds() {
		return Collections.unmodifiableList(initialColumnIds);
	}

	/** @return incumbent 对应列 id 集（只读） */
	public List<Integer> getIncumbentColumnIds() {
		return Collections.unmodifiableList(incumbentColumnIds);
	}

}
