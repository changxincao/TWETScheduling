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
	/** 是否为每个 job 额外生成 singleton 列。 */
	public boolean generateSingletonColumns = true;
	/** 是否从启发式完整序列中切出短子序列列，作为额外初始列。 */
	public boolean generateSubsequenceColumns = true;
	/** 从一条启发式序列切子列时，允许的最大长度。 */
	public int maxSeedSubsequenceLength = 4;
	/** 初始列总数上限，避免一开始把列池扩得过大。 */
	public int maxInitialColumns = 2000;
	/** 树搜索最多处理多少个节点。 */
	public int maxNodes = 1000;
	/** 是否使用按论文 dominance graph 伪代码实现的精确定价器；关闭后可回退旧的全量扫描版做效率对比。 */
	public boolean usePaperDominancePricing = true;
	/** 2026-05-18: 是否在 exact pricing 前先用当前列池做一轮启发式定价。 */
	public boolean enableHeuristicPricing = true;
	/**
	 * 2026-05-20: 是否在单向 exact forward pricing 前先运行一轮双向 no-cut labeling。
	 * 该实现参考旧 VRP 的 FW/BW/Join 流程新建，不替换原 forward exact；双向版没有返回列时，
	 * 仍会继续调用后面的 forward exact pricing 作为严格兜底。
	 */
	public boolean enableBidirectionalPricing = true;
	/**
	 * 2026-05-20: 双向 labeling 的局部 label 数工程保护。
	 * 旧 VRP 依赖资源半程截断、ng/DSSR 和 dominance 控制规模；当前第一版 no-cut 双向实现
	 * 先保守限制局部 label 数，防止在无负列节点上爆炸。该限制不作为无负列证明，因为后面还有
	 * 原 forward exact pricing 兜底。
	 */
	public int maxBidirectionalPartialLabels = 50000;
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
