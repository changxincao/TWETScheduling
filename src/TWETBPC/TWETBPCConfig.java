package TWETBPC;

/**
 * TWET-BPC 框架的可调参数集合。
 */
public class TWETBPCConfig {

	/** 结果输出时使用的实例名；为空时回退到 n/m 拼接名。 */
	public String instanceName = "";
	/** BPC 结果输出目录。 */
	public String bpcOutputRoot = "results";
	/** BPC 方法名目录。 */
	public String bpcMethodName = "twet-bpc";
	/** 是否输出 BPC 求解过程到控制台。 */
	public boolean enableBPCConsoleOutput = true;
	/** 是否在求解结束后自动写出 BPC 结果文件。 */
	public boolean writeBPCResultFiles = true;

	/** 是否优先复用 data.configure.bestSolution 中已经存在的最好启发式解。 */
	public boolean reuseConfiguredBestSolution = true;
	/** 如果没有现成最好解，是否额外跑一遍 ALNS 生成更好的 seed。 */
	public boolean runALNSForSeed = true;
	/** 2026-05-21: 是否把 ALNS/VND 历史 best 解中的完整机器序列也作为 root 初始列。 */
	public boolean useBestSolutionHistoryForInitialColumns = true;
	/** 树搜索最多处理多少个节点。 */
	public int maxNodes = 1000;
	/** 是否使用按论文 dominance graph 伪代码实现的精确定价器；关闭后可回退旧的全量扫描版做效率对比。 */
	public boolean usePaperDominancePricing = true;
	/** 2026-05-18: 是否在 exact pricing 前先用当前列池做一轮启发式定价。 */
	public boolean enableHeuristicPricing = true;
	/**
	 * 2026-05-20: exact pricing 层是否使用双向 no-cut labeling。
	 * 为 true 时，启发式定价之后只接双向定价；为 false 时，才按 usePaperDominancePricing
	 * 选择原来的单向 forward exact 定价。这里是二选一开关，不把双向和单向串起来顺序兜底。
	 */
	public boolean enableBidirectionalPricing = true;
	/**
	 * 2026-05-26: 双向 exact pricing 是否使用旧 VRP GCNGBB 风格的外层流程。
	 * true 时先完整生成 forward/backward 两侧 label，最后统一 join；false 时回到原 hybrid-B 实现。
	 */
	public boolean useGCNGBBStyleBidirectionalPricing = true;
	/**
	 * 2026-05-28: 仅用于效率对照。true 时双向 pricing 改用 GCBB full-domain 复制版本，
	 * 不按 Tmid 裁剪 forward/backward 标签函数；正式求解默认保持 false。
	 */
	public boolean useGCBBFullDomainBidirectionalPricing = false;
	/**
	 * 2026-05-29: 仅用于实验。true 时使用 full-domain 函数标签，并将 final join
	 * 从 crossing arc 改为同一 job 上的 node join。
	 */
	public boolean useGCBBFullDomainNodeJoinBidirectionalPricing = false;
	/**
	 * 2026-05-28: 仅用于实验。true 时使用 full-domain 函数标签 + 动态 half-way 边界的非对称双向 pricing。
	 */
	public boolean useGCBBAsymmetricBidirectionalPricing = false;
	/**
	 * 2026-05-28: 非对称动态双向 pricing 的选边策略。
	 * 可选值：moreLabels、fewerLabels。
	 */
	public String asymmetricBidirectionalSideSelection = "moreLabels";
	/**
	 * 2026-05-26: GCNGBB-style 双向 pricing 的 label 扩展队列排序。
	 * 可选值：reducedCost、time、reachableSize。
	 */
	public String bidirectionalLabelQueueOrdering = "reducedCost";
	/**
	 * 2026-05-31: 使用 K 堆的双向 final join 是否用当前最好负列加强剪枝。
	 * 可选值：zero（只和 0 比，默认）、bestUB（仅 group/pair 下界和当前最好负列上界比）、
	 * bestRecord（下界和函数真实值都必须刷新 bestRC 才保留）。
	 * bestRecord 是激进的 record-only 对照模式，会显著减少每轮加列数，默认不作为后续正式路径使用。
	 */
	public String bidirectionalJoinBestThresholdMode = "zero";
	/**
	 * 2026-05-31: full-domain node-join 实验分支的 completion bound 松弛模式。
	 * 可选值：off、allCycles、twoCycle。默认关闭，避免改变现有对照实验语义。
	 */
	public String bidirectionalCompletionBoundRelaxation = "off";
	/** 2026-06-01: completion bound correcting 队列顺序；可选 fifo/reducedCost，当前实测 FIFO 更稳。 */
	public String bidirectionalCompletionBoundQueueOrdering = "fifo";
	/**
	 * 2026-05-26: 双向 pricing 的 midpoint 固定比例实验开关。
	 * 取 NaN 时使用动态 hard/profitable window 中点；
	 * 取 0..1 时强制使用本轮 pricingHorizon 的该比例，不是全局 CmaxH 的比例。
	 */
	public double bidirectionalRootLocalHorizonMidpointRatio = Double.NaN;
	/** 2026-05-18: 对应旧 VRP Configure.addin_size，启发式定价最多返回给 RMP 的优质负 reduced-cost 列数。 */
	public int maxHeuristicPricingColumns = 150;
	/** 2026-05-18: 对应旧 VRP Configure.m_tabu_cg_size，从当前 RMP 中挑多少条低 reduced cost 列作为 tabu seed。 */
	public int heuristicPricingSeedColumns = 30;
	/** 2026-05-18: 对应旧 VRP Configure.m_gen_size，本地负 reduced-cost 候选池生成上限。 */
	public int heuristicPricingPoolSize = 1000;
	/** 2026-05-18: 对应旧 VRP Configure.m_tabu_cg_iteration_number，每条 seed 的 tabu 搜索轮数。 */
	public int heuristicPricingTabuIterations = 50;
	/** 2026-05-18: 对应旧 VRP Configure.m_tabu_cg_tenure，tabu 禁忌长度。 */
	public int heuristicPricingTabuTenure = 30;
	/** 单次 exact pricing 最多返回多少条负 reduced-cost 列；取旧 VRP Configure.addin_size 的默认值。 */
	public int maxExactPricingColumns = 150;
	/**
	 * 2026-05-18: 对应旧 VRP Configure.m_branch_col_number。这里默认调大，只作为防死循环保护，
	 * 不作为不可行证明；正常 repair 应由 slack=0 或无新列退出。原来的 500 过小，
	 * 可能让可修复子节点过早触发工程上限，因此先放宽到 100000。
	 */
	public int maxBranchRepairColumns = 100000;
	/**
	 * 2026-05-26: 单向 forward exact pricing 的 label 扩展队列排序。
	 * 可选值：reducedCost、time、reachableSize。
	 */
	public String forwardLabelQueueOrdering = "reducedCost";
	/** 2026-05-18: 对应旧 VRP Configure.m_initial_col_number，子节点初始 RMP 最多继承多少条低 reduced-cost 列。 */
	public int branchSeedColumnLimit = 1000;
	/** 2026-05-18: 对应旧 VRP Configure.m_addin_red_cost，父节点 reduced cost 低于该阈值的列优先传给子节点。 */
	public double branchSeedReducedCostAllowance = 5000.0;
	/** 单个节点内最多进行多少轮 cut separation。 */
	public int maxCutRounds = 8;
	/** 判断是否整数时使用的容差。 */
	public double branchingTolerance = 1e-6;
	/** 节点初始伪成本占位值。 */
	public double pseudoCostInf = 1e18;

}
