package TWETBPC.BP;

import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * 机器数分支器。
 * <p>
 * 这个类在思想上对应旧参考代码中的 BranchA：
 * 都是在对“当前解使用了多少条列/多少台机器”这一全局量做分支。
 * <p>
 * 在 TWET 中它不是最核心的分支规则，但保留它有两个价值：
 * <ul>
 * <li>结构上对齐旧 BPC；</li>
 * <li>当机器使用量出现分数时，提供一种全局型分支备选。</li>
 * </ul>
 */
public class MachineCountBrancher implements Brancher {

	/** 整数判断容差。 */
	private final double tolerance;

	/**
	 * 构造一个机器数分支器。
	 */
	public MachineCountBrancher(double tolerance) {
		this.tolerance = tolerance;
	}

	@Override
	/**
	 * 按当前解中的机器使用量做分支。
	 * <p>
	 * 逻辑与旧 BranchA 类似：
	 * 若总机器使用量是分数，则切成
	 * {@code <= floor(value)} 和 {@code >= ceil(value)} 两个子空间。
	 */
	public BranchResult branch(LP lp) {
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return BranchResult.none("No master solution");
		}
		double machineUsage = solution.getMachineUsage();
		double rounded = Math.rint(machineUsage);
		if (Math.abs(machineUsage - rounded) <= tolerance) {
			return BranchResult.none("Machine usage already integral");
		}

		Node base = lp.getNode();
		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();
		left.maxMachineCount = (int) Math.floor(machineUsage);
		right.minMachineCount = (int) Math.ceil(machineUsage);
		return new BranchResult(true, left, right, "Branched on machine usage");
	}

	@Override
	/** @return 分支器名称 */
	public String getName() {
		return "MachineCountBrancher";
	}

}
