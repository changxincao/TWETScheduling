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
 * 对照旧 VRP-BPC 参考代码，可以把它看作“GenRoute / 初始路集生成”的 TWET 版本入口。
 * 只不过这里的对象不再是 route，而是单机序列列。
 * <p>
 * 当前策略是：
 * <ul>
 * <li>先拿到一个启发式 seed；</li>
 * <li>把 seed 中每台机器的完整序列作为初始列；</li>
 * <li>可选地再切出若干短子序列列；</li>
 * <li>可选地补 singleton 列，增强主问题起步阶段的可行性。</li>
 * </ul>
 */
public class InitialColumnBuilder {

	/** 当前实例数据。 */
	private final Data data;
	/** 初始列构造相关参数。 */
	private final TWETBPCConfig config;
	/** 全局列池。 */
	private final Pool pool;
	/** 启发式 seed 提供器。 */
	private final HeuristicSeedProvider seedProvider;
	/** 用于给每条列重新精确评价成本的对象。 */
	private final TWETColumnEvaluator evaluator;

	/**
	 * 构造一个初始列构造器。
	 */
	public InitialColumnBuilder(Data data, TWETBPCConfig config, Pool pool, HeuristicSeedProvider seedProvider) {
		this.data = data;
		this.config = config;
		this.pool = pool;
		this.seedProvider = seedProvider;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	/**
	 * 构造初始列 bundle。
	 * <p>
	 * 返回结果里既包含 seed solution，也包含：
	 * <ul>
	 * <li>所有初始列 id；</li>
	 * <li>当前 incumbent 对应的完整列 id。</li>
	 * </ul>
	 * 其中使用 {@link LinkedHashSet} 的原因是：
	 * 既要去重，又希望尽量保留列加入的先后顺序。
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

			// 每台机器上的完整序列，视作一条高质量 seed 列。
			int id = pool.addColumn(seq, evaluator.evaluate(seq), ColumnSource.HEURISTIC_FULL, true);
			initialColumnIds.add(Integer.valueOf(id));
			incumbentColumnIds.add(Integer.valueOf(id));

			if (!config.generateSubsequenceColumns) {
				continue;
			}
			int maxLen = Math.min(config.maxSeedSubsequenceLength, seq.size());
			for (int len = 1; len <= maxLen && initialColumnIds.size() < config.maxInitialColumns; len++) {
				for (int start = 0; start + len <= seq.size() && initialColumnIds.size() < config.maxInitialColumns; start++) {
					// 从完整 seed 列里切出较短子列，为后续 RMP 提供更灵活的覆盖组合。
					ArrayList<Integer> subSeq = new ArrayList<Integer>(seq.subList(start, start + len));
					ColumnSource source = len == 1 ? ColumnSource.SINGLETON : ColumnSource.HEURISTIC_SUBSEQUENCE;
					int subId = pool.addColumn(subSeq, evaluator.evaluate(subSeq), source, true);
					initialColumnIds.add(Integer.valueOf(subId));
				}
			}
		}

		if (config.generateSingletonColumns) {
			for (int job = 1; job <= data.n && initialColumnIds.size() < config.maxInitialColumns; job++) {
				// singleton 列通常是主问题起步时最稳妥的保底列。
				ArrayList<Integer> singleton = new ArrayList<Integer>(1);
				singleton.add(Integer.valueOf(job));
				int id = pool.addColumn(singleton, evaluator.evaluate(singleton), ColumnSource.SINGLETON, true);
				initialColumnIds.add(Integer.valueOf(id));
			}
		}

		return new InitialColumnBundle(seed, new ArrayList<Integer>(initialColumnIds),
				new ArrayList<Integer>(incumbentColumnIds));
	}

}
