package TWETBPC.IO;

import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import HEU.Solution;

/**
 * 列成本评价器。
 * <p>
 * 该类的职责是：给定一条单机 job 序列，调用当前项目已有的
 * {@link Solution} / PiecewiseLinearFunction 评价链，算出这条列的成本。
 * <p>
 * 这样做的意义在于：
 * 后续无论初始列、pricing 新列还是手工测试列，
 * 都使用同一套 TWET 原生目标值定义，而不是在 BPC 包里再复制一套成本计算逻辑。
 */
public class TWETColumnEvaluator {

	/** 当前实例数据。 */
	private final Data data;

	/**
	 * 构造一个列评价器。
	 */
	public TWETColumnEvaluator(Data data) {
		this.data = data;
	}

	/**
	 * 评价一条单机序列的成本。
	 * <p>
	 * 当前实现方式是：
	 * <ol>
	 * <li>新建一个 scratch Solution；</li>
	 * <li>把目标序列放在第 0 台机器，其余机器留空；</li>
	 * <li>调用现有启发式主线中的成本更新逻辑；</li>
	 * <li>返回第 0 台机器对应的成本。</li>
	 * </ol>
	 * 这是一种“先保证口径一致，再谈优化”的写法。
	 */
	public double evaluate(List<Integer> sequence) {
		if (sequence.isEmpty()) {
			return 0.0;
		}
		Solution scratch = buildScratch(sequence);
		scratch.updateInformationM(0);
		return scratch.calCost(0);
	}

	/**
	 * 计算列成本以及完整 PWLF 口径下的最优完工时间信息，供 Tmid 诊断和策略使用。
	 */
	public Timing evaluateTiming(List<Integer> sequence) {
		if (sequence.isEmpty()) {
			return new Timing(0.0, 0.0, 0.0, new double[0]);
		}
		Solution scratch = buildScratch(sequence);
		double cost = scratch.calCost(0);
		double[] completions = scratch.computeBestCompletionTimesFromCurrentForwardFunctions(0);
		double lastCompletion = completions.length == 0 ? 0.0 : completions[completions.length - 1];
		int halfIndex = completions.length == 0 ? 0 : (completions.length - 1) / 2;
		double halfCompletion = completions.length == 0 ? 0.0 : completions[halfIndex];
		return new Timing(cost, lastCompletion, halfCompletion, completions);
	}

	private Solution buildScratch(List<Integer> sequence) {
		Solution scratch = new Solution(data);
		ArrayList<ArrayList<Integer>> sequences = new ArrayList<ArrayList<Integer>>(data.m);
		for (int machine = 0; machine < data.m; machine++) {
			sequences.add(new ArrayList<Integer>());
		}
		sequences.get(0).addAll(sequence);
		scratch.setSequence(sequences);
		scratch.initialize_function();
		return scratch;
	}

	public static final class Timing {
		public final double cost;
		public final double lastCompletion;
		public final double halfCompletion;
		public final double[] completions;

		Timing(double cost, double lastCompletion, double halfCompletion, double[] completions) {
			this.cost = cost;
			this.lastCompletion = lastCompletion;
			this.halfCompletion = halfCompletion;
			this.completions = completions;
		}
	}

}
