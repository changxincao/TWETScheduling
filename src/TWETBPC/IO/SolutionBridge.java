package TWETBPC.IO;

import java.util.ArrayList;

import HEU.Solution;

/**
 * 旧启发式解对象到新 BPC 框架之间的桥接层。
 * <p>
 * 这层的目标很单纯：
 * 不让新包直接依赖 {@link Solution} 的内部字段布局，
 * 而是统一通过这里读取“新框架真正需要的最小信息”。
 * <p>
 * 目前只桥接：
 * <ul>
 * <li>每台机器上的序列；</li>
 * <li>每台机器的成本数组。</li>
 * </ul>
 */
public final class SolutionBridge {

	/** 工具类不允许实例化。 */
	private SolutionBridge() {
	}

	/**
	 * 提取启发式解中的机器序列。
	 * <p>
	 * 返回的是深拷贝，后续列构造过程中可安全读取。
	 */
	public static ArrayList<ArrayList<Integer>> extractSequences(Solution solution) {
		return solution.getSequencesCopy();
	}

	/**
	 * 提取启发式解中的分机成本。
	 * <p>
	 * 当前框架里这项信息暂未强依赖，
	 * 但保留该接口可方便后续做 seed 列筛选和 warm start 统计。
	 */
	public static double[] extractMachineCosts(Solution solution) {
		return solution.getMachineCostsCopy();
	}

}
