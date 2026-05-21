package TWETBPC.GC;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import Basic.Data;
import HEU.Solution;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.HeuristicSeedProvider;
import TWETBPC.IO.SolutionBridge;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.Pool;
import TWETBPC.Model.ColumnSource;

/**
 * 初始列构造器。
 * <p>
 * 对照旧 VRP-BPC 参考代码，这里只把启发式最终 seed solution 中每台机器的完整序列
 * 加入 root 初始列池。2026-05-21: 此前额外切短子序列和补 singleton 的逻辑不是旧 VRP
 * root 初始列流程，已经删除，避免 root 阶段引入额外组合列导致流程和参考代码不一致。
 */
public class InitialColumnBuilder {

	/** 当前实例数据。 */
	private final Data data;
	/** 全局列池。 */
	private final Pool pool;
	/** 启发式 seed 提供器。 */
	private final HeuristicSeedProvider seedProvider;
	/** 用于给每条列重新精确评价成本的对象。 */
	private final TWETColumnEvaluator evaluator;

	/**
	 * 构造一个初始列构造器。config 保留在构造签名里，是为了不扩大调用侧改动范围。
	 */
	public InitialColumnBuilder(Data data, TWETBPCConfig config, Pool pool, HeuristicSeedProvider seedProvider) {
		this.data = data;
		this.pool = pool;
		this.seedProvider = seedProvider;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	/**
	 * 构造初始列 bundle。
	 * <p>
	 * 返回结果里既包含 seed solution，也包含所有初始列 id 和 incumbent 对应的完整列 id。
	 * 使用 {@link LinkedHashSet} 是为了去重，同时保留列加入顺序。
	 */
	public InitialColumnBundle build() {
		Solution seed = seedProvider.getOrBuildSeed();
		LinkedHashSet<Integer> initialColumnIds = new LinkedHashSet<Integer>();
		LinkedHashSet<Integer> incumbentColumnIds = new LinkedHashSet<Integer>();

		ArrayList<ArrayList<Integer>> machineSequences = SolutionBridge.extractSequences(seed);
		for (List<Integer> seq : machineSequences) {
			if (seq.isEmpty()) {
				continue;
			}

			int id = pool.addColumn(seq, evaluator.evaluate(seq), ColumnSource.HEURISTIC_FULL, true);
			initialColumnIds.add(Integer.valueOf(id));
			incumbentColumnIds.add(Integer.valueOf(id));
		}

		return new InitialColumnBundle(seed, new ArrayList<Integer>(initialColumnIds),
				new ArrayList<Integer>(incumbentColumnIds));
	}

}
