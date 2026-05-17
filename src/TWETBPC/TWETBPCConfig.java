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
	/** 如果没有现成最好解，是否额外跑一遍 ALNS 来生成更好的 seed。 */
	public boolean runALNSForSeed = true;
	/** 是否为每个 job 额外生成 singleton 列。 */
	public boolean generateSingletonColumns = true;
	/** 是否从启发式完整序列中切出短子序列，作为额外初始列。 */
	public boolean generateSubsequenceColumns = true;
	/** 从一条启发式序列切子列时，允许的最大长度。 */
	public int maxSeedSubsequenceLength = 4;
	/** 初始列总数上限，避免一开始把列池扩得过大。 */
	public int maxInitialColumns = 2000;
	/** 树搜索最多处理多少个节点。 */
	public int maxNodes = 1000;
	/** 是否使用按论文 dominance graph 伪代码实现的精确定价器；关闭后可回退旧的全量扫描版做效率对比。 */
	public boolean usePaperDominancePricing = true;
	/** 单次 exact pricing 最多返回多少条负 reduced-cost 列；取旧 VRP 代码 Configure.addin_size 的默认值。 */
	public int maxExactPricingColumns = 150;
	/** 单个节点内最多进行多少轮 cut separation。 */
	public int maxCutRounds = 8;
	/** 判断是否整数时使用的容差。 */
	public double branchingTolerance = 1e-6;
	/** 节点初始伪成本占位值。 */
	public double pseudoCostInf = 1e18;

}
