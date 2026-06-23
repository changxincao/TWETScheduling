package TWETBPC.IO;

import Basic.Data;
import HEU.EngineALNS;
import HEU.Solution;
import TWETBPC.TWETBPCConfig;

/**
 * 启发式种子解提供器。
 * <p>
 * 旧 VRP 参考代码里，根节点之前会通过 {@code GenRoute} 先构造一个启发式可行解，
 * 同时得到初始上界和初始路集。
 * <p>
 * 这里做的是同一层职责，但接到当前 TWET 项目已有的启发式主线：
 * <ul>
 * <li>优先复用 {@code data.configure.bestSolution}；</li>
 * <li>如果没有，就先构造一个初始解；</li>
 * <li>必要时再用 ALNS 做进一步改进；</li>
 * <li>最终返回一个可以拿来拆初始列的 seed solution。</li>
 * </ul>
 */
public class HeuristicSeedProvider {

	/** 当前实例数据。 */
	private final Data data;
	/** seed 构造过程的控制参数。 */
	private final TWETBPCConfig config;

	/**
	 * 构造一个 seed provider。
	 */
	public HeuristicSeedProvider(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	/**
	 * 获取或构造一个启发式 seed。
	 * <p>
	 * 返回值总是拷贝对象，避免后续列构造过程误改全局 best solution。
	 */
	public Solution getOrBuildSeed() {
		if (config.reuseConfiguredBestSolution && data.configure.bestSolution != null) {
			return data.configure.bestSolution.copy();
		}

		// 没有现成最好解时，先用项目里已有的构造逻辑生成一个起点。
		Solution seed = new Solution(data);
		seed.setInitSolution();
		seed.initialize_function();
		for (int machine = 0; machine < data.m; machine++) {
			seed.updateInformationM(machine);
		}
		data.configure.updateBestSolution(seed);

		// 如果配置允许，再用 ALNS 把 seed 往前推一步。
		if (config.runALNSForSeed) {
			configureAlns();
			EngineALNS alns = new EngineALNS(data, seed);
			alns.search();
		}

		// ALNS 可能会刷新全局 bestSolution，因此优先返回它。
		if (data.configure.bestSolution != null) {
			return data.configure.bestSolution.copy();
		}
		return seed.copy();
	}

	private void configureAlns() {
		EngineALNS.maxRuntimeMillis = config.alnsMaxRuntimeMillis;
		EngineALNS.maxNoImpIterN = config.alnsMaxNoImproveIterations;
		EngineALNS.maxAcceptedSolutionHistory = config.acceptedSolutionHistoryLimit;
		// 2026-06-23: 初始列只会读取 accepted 或 best 其中一种历史；best 模式下不再记录 accepted，
		// 避免 ALNS 热路径反复复制不会被使用的中间解。
		EngineALNS.recordAcceptedSolutions = !"best".equalsIgnoreCase(config.initialHeuristicColumnHistoryMode)
				&& config.acceptedSolutionHistoryLimit > 0;
		EngineALNS.useSimulatedAnnealingAcceptance = config.alnsUseSimulatedAnnealingAcceptance;
		EngineALNS.simulatedAnnealingInitialTemperatureRatio = config.alnsSimulatedAnnealingInitialTemperatureRatio;
		EngineALNS.simulatedAnnealingCoolingRate = config.alnsSimulatedAnnealingCoolingRate;
		EngineALNS.simulatedAnnealingMinTemperatureRatio = config.alnsSimulatedAnnealingMinTemperatureRatio;
	}
}
