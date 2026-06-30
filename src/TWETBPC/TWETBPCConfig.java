package TWETBPC;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
	/** 2026-06-21: 长算例诊断用。非空时 BPC 事件会边运行边追加写入该日志文件。 */
	public String liveTraceLogPath = "";

	/** 是否优先复用 data.configure.bestSolution 中已经存在的最好启发式解。 */
	public boolean reuseConfiguredBestSolution = true;
	/** 如果没有现成最好解，是否额外跑一遍 ALNS 生成更好的 seed。 */
	public boolean runALNSForSeed = true;
	/** 2026-06-23: seed ALNS 的时间上限；避免大算例初始化阶段无限拉长。 */
	public long alnsMaxRuntimeMillis = 600_000L;
	/** 2026-06-23: seed ALNS 连续未刷新全局 best 的停止阈值。 */
	public int alnsMaxNoImproveIterations = 80;
	/** 2026-06-23: 是否用模拟退火接受较差 current；默认关闭，保持原 greedy 接受逻辑。 */
	public boolean alnsUseSimulatedAnnealingAcceptance = false;
	public double alnsSimulatedAnnealingInitialTemperatureRatio = 0.01;
	public double alnsSimulatedAnnealingCoolingRate = 0.995;
	public double alnsSimulatedAnnealingMinTemperatureRatio = 1.0e-6;
	/** 2026-06-23: root 初始列使用哪类 ALNS 历史解；可选 accepted/best。 */
	public String initialHeuristicColumnHistoryMode = "accepted";
	public int acceptedSolutionHistoryLimit = 2000;
	/** 树搜索最多处理多少个节点。 */
	public int maxNodes = 1000;
	/** 2026-06-25: BPC 全局 wall-clock 时间上限，单位秒；<=0 表示不限时。默认 2 小时。 */
	public double solveTimeLimitSeconds = 7200.0;
	/** 是否使用按论文 dominance graph 伪代码实现的精确定价器；关闭后可回退旧的全量扫描版做效率对比。 */
	public boolean usePaperDominancePricing = true;
	/**
	 * 2026-06-20: 实验开关。打开后用 time-indexed DAG pseudo-schedule pricing 替换当前 exact pricing。
	 * 该图定价允许重复 job，TWETColumn/RMP 已按 visit count 接入；当前仍是不带 cut 的单向 DAG 对照实现。
	 */
	public boolean useTimeIndexedGraphPricing = false;
	/** 2026-06-28: time-indexed graph pricing 的 rank-1 cut 实验模式，默认关闭。 */
	public boolean useTimeIndexedGraphRank1CutPricing = false;
	/**
	 * 2026-06-20: 外包建模方式。masterVariables 保持当前 SP2 的 y_j + tariff segment 变量；
	 * columns 使用 SP1 风格，把外包集合也作为列加入主问题，并启用外包集合定价与外包 membership 分支。
	 */
	public String outsourcingModel = "masterVariables";
	/**
	 * 2026-06-22: dual stabilization 实验开关。打开后使用 Pessoa et al. 口径的
	 * smoothing/mispricing schedule；节点闭合前仍强制回到 true dual exact pricing 验证。
	 */
	public boolean enableDualStabilization = false;
	/** 2026-06-23: smoothing center 更新规则；wentges=best-bound incumbent，neame=latest evaluated sep-point。 */
	public String dualStabilizationSmoothingRule = "wentges";
	/** 2026-06-22: smoothing 初始 alpha；alpha 越大越靠近稳定中心。 */
	public double dualStabilizationAlpha = 0.5;
	/** 2026-06-22: 非 mispricing 且方向支持加强稳定化时，alpha 向 1 增加的比例。 */
	public double dualStabilizationAlphaIncreaseFraction = 0.1;
	/** 2026-06-22: 非 mispricing 且方向支持靠近真实 dual 时，alpha 每次减少的步长。 */
	public double dualStabilizationAlphaDecreaseStep = 0.1;
	/** 2026-06-22: stabilized candidate 必须在当前 out dual 下达到该 reduced-cost 阈值才真正入池。 */
	public double dualStabilizationReducedCostTolerance = 1e-7;
	/** 2026-06-22: 用当前 RMP dual bound 与 incumbent 比较，尝试提前剪节点；只使用 pricing 提供的 certified rc_min。 */
	public boolean enableDualBoundPruning = true;
	/** 2026-06-22: dual-bound 剪枝容差。 */
	public double dualBoundPruningTolerance = 1e-7;
	/** 2026-06-26: two-stage strong branching 实验开关，默认关闭，关闭时完全走原分支流程。 */
	public boolean enableTwoStageStrongBranching = false;
	/** 2026-06-26: 第一阶段最多试探最接近 0.5 的分支候选数。 */
	public int strongBranchingCandidateLimit = 20;
	/** 2026-06-26: 第二阶段进入启发式 pricing 试探的候选数。 */
	public int strongBranchingPhase2CandidateLimit = 4;
	/** 2026-06-26: 第二阶段每个 trial 最多执行几轮启发式 pricing；0 表示直到启发式无列。 */
	public int strongBranchingPhase2MaxHeuristicPasses = 0;
	/** 2026-06-26: strong branching 评分里避免一侧提升为 0 导致 product 退化的极小值。 */
	public double strongBranchingScoreEpsilon = 1.0e-6;
	/** 2026-06-24: 节点 LP true-dual 闭合后，是否尝试枚举所有 rc < UB-LB 的列并解有限主问题。 */
	public boolean enableRouteEnumeration = false;
	/** 2026-06-24: 只有节点绝对 gap 小于该阈值时才触发 route enumeration。 */
	public double routeEnumerationAbsoluteGapThreshold = 10.0;
	/** 2026-06-24: 当前 RMP 列 + 枚举列超过该上限时中止枚举，避免证明模型失控。 */
	public int routeEnumerationColumnLimit = 100000;
	/** 2026-06-24: 复用节点闭合时 exact pricing 留下的 completion bound 做枚举扩展剪枝。 */
	public boolean routeEnumerationUseCompletionBound = true;
	/** 2026-06-25: 外包枚举 cheap suffix bound 剪不掉时，是否继续做逐 label 的精细 suffix 扫描。 */
	public boolean routeEnumerationUseExactOutsourcingSuffixBound = false;
	/** 2026-06-29: route enumeration 是否使用 node 继承的 time-indexed compact window 裁剪机器列枚举函数域。 */
	public boolean routeEnumerationUseTimeIndexedWindow = false;
	/** 2026-05-18: 是否在 exact pricing 前先用当前列池做一轮启发式定价。 */
	public boolean enableHeuristicPricing = true;
	// 2026-06-29: Keep root dual profitable window out of heuristic pricing by default.
	// Sparse initial RMP duals can make this window too narrow and kill all tabu seeds.
	public boolean enableHeuristicDualProfitableWindow = false;
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
	 * 2026-06-09: 实验开关。true 时使用 GCNGBB-style 半域双向的 partial-list dominance
	 * 复制版本；默认关闭，避免影响当前主要求解路径。
	 */
	public boolean useGCNGBBStylePartialDominancePricing = false;
	/**
	 * 2026-06-11: 仅用于 exactness 对拍。主双向定价器返回空列后，再用另一套
	 * whole/partial dominance 在同一 LP dual 上复查；默认关闭，不参与正式求解。
	 */
	public boolean diagnosticCrossCheckPartialDominance = false;
	/**
	 * 2026-06-09: 实验开关。true 时使用独立复制的 GCNGBB-style 半域 ng-relaxation + DSSR
	 * 版本；默认关闭，避免影响当前 elementary 主路径。
	 */
	public boolean useGCNGBBStyleNgDssrPricing = false;
	/** 2026-06-12: 实验开关；复用 ng-DSSR 主体，仅把 dominance store 切到 partial-list bucket 版本。 */
	public boolean useGCNGBBStyleNgDssrPartialDominancePricing = false;
	/** 2026-06-11: 实验开关；复用 ng-DSSR 主体，仅把 dominance store 切到 graph partial dominance。 */
	public boolean useGCNGBBStyleNgDssrGraphPartialDominancePricing = false;
	/** 2026-06-09: ng/DSSR 初始 ng-set 模式；可选 empty/nearestK。 */
	public String ngDssrInitialNgSetMode = "nearestK";
	/** 2026-06-09: nearestK 初始 ng-set 的目标大小，包含任务自身。 */
	public int ngDssrInitialNgSetSize = 8;
	/** 2026-06-10: 每轮 DSSR 最多用多少条最负 non-elementary route 更新 ng-set；默认 1 对齐旧 VRP。 */
	public int ngDssrNonElementaryRouteUpdateLimit = 1;
	/**
	 * 2026-06-30: 仅用于诊断对照。打开后，ng-DSSR 的负 non-elementary route 直接作为
	 * ng-relaxed 列进入 RMP，不再只用于 DSSR 收紧 ng-set；默认关闭，保持主线 elementary 列口径。
	 */
	public boolean ngDssrReturnRelaxedColumns = false;
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
	 * 2026-06-01: full-domain node-join 诊断分支中，哪一侧允许生成第一次越过 Tmid 的
	 * boundary terminal label。可选 both/forward/backward；默认 both 保持原实验口径。
	 */
	public String fullDomainNodeJoinCrossingSide = "both";
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
	/** 2026-06-02: completion bound 是否启用离散 scalar 预筛；仅用于 full-domain 对照路径。 */
	public boolean bidirectionalCompletionBoundScalarPruning = true;
	/** 2026-06-28: ng-DSSR 主线可选用独立 time-indexed relaxed graph 强化 scalar completion bound。 */
	public boolean timeIndexedCompletionBoundScalarEnhancement = false;
	/** 2026-06-30: active SRI cut 存在时默认仍使用 no-SRI 的 time-indexed 松弛 helper；完整 SRI-aware fixing 另由开关控制。 */
	public boolean timeIndexedCompletionBoundAllowNoSriWithActiveCuts = true;
	/** 2026-06-29: 每轮 pricing 内部先按 0 reduced-cost 做本地 time-indexed arc fixing，再据此收缩本轮窗口。 */
	public boolean timeIndexedCompletionBoundInRoundArcFixing = false;
	/** 2026-06-29: active SRI cut 下使用带 SRI state 的 time-indexed labeling 做本轮窗口强化。 */
	public boolean timeIndexedCompletionBoundSriAwareArcFixing = false;
	/**
	 * 2026-06-29: cut loop 内每次 pricing 收敛后，用 UB-LB 做 pricing-only arc fixing/window tightening。
	 * 不删除当前 RMP 旧列，只让同一 node 后续 cut/pricing 和子节点继承更强的 pricing-only 状态。
	 */
	public boolean timeIndexedCompletionBoundCutLoopArcFixing = false;
	/** 2026-06-28: 整数时间实例上，使用 time-indexed 剩余可达时间收紧当前 node 内部 pricing window。 */
	public boolean timeIndexedCompletionBoundWindowTightening = true;
	/** 2026-06-28: node 闭合后，用 time-indexed relaxed graph 做时空弧 pricingOnly fixing。 */
	public boolean timeIndexedCompletionBoundArcFixing = true;
	/** 2026-06-03: 是否用当前 pricing 轮的 completion bound 做本地 job-job arc fixing。 */
	public boolean bidirectionalCompletionBoundArcFixing = true;
	/** 2026-06-03: 只诊断 completion bound 能否在当前 pricing 轮安全判掉 job-job arc，不写回 node。 */
	public boolean bidirectionalCompletionBoundArcFixingDiagnostic = false;
	/** 2026-06-03: node LP 最优且已有上界后，是否把 completion-bound reduced-cost fixing 继承到子节点。 */
	public boolean bidirectionalCompletionBoundSubtreeArcElimination = false;
	/** 2026-06-03: debug 对照；只在后续 pricing 中禁用 subtree arcs，不过滤初始列，也不建 master forbidden 行。 */
	public boolean bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly = false;
	/** 2026-06-03: debug 对照；child 初始 LP 可行后不按当前 forbidden arc 过滤 RMP 列。 */
	public boolean debugSkipBranchColumnFilter = false;
	/** 2026-06-09: 诊断开关；指定 node 在 pricing/CB 中临时忽略 pricingOnly arc，默认关闭。 */
	public int debugIgnorePricingOnlyArcsAtNode = -1;
	/** 2026-06-03: 只统计 subtree arc elimination 潜力和耗时，不写入子节点。 */
	public boolean bidirectionalCompletionBoundSubtreeArcEliminationDiagnostic = false;
	/**
	 * 2026-05-26: 双向 pricing 的 midpoint 固定比例实验开关。
	 * 取 NaN 时使用动态 hard/profitable window 中点；
	 * 取 0..1 时强制使用本轮 pricingHorizon 的该比例，不是全局 CmaxH 的比例。
	 */
	public double bidirectionalRootLocalHorizonMidpointRatio = Double.NaN;
	/** 2026-06-06: GCNGBB-style bidirectional pricing 的 Tmid 策略；默认 default 保持原有逻辑。 */
	public String bidirectionalMidpointStrategy = "default";
	/** 2026-06-06: column-based Tmid 策略最多评价多少条低 reduced-cost 当前列。 */
	public int bidirectionalMidpointColumnLimit = 400;
	/** 2026-06-06: 是否在正式 exact pricing 前用有限 pop dry-run 试探多个 Tmid。 */
	public boolean bidirectionalMidpointProbe = false;
	/** 2026-06-06: 每个 Tmid probe 候选最多弹出多少个 label。 */
	public int bidirectionalMidpointProbePopLimit = 5000;
	/** 2026-06-06: Tmid probe 最多连续试探多少个候选点。 */
	public int bidirectionalMidpointProbeMaxCandidates = 5;
	/** 2026-06-08: 同一 node 已有历史 exact best Tmid 时，后续 probe 最多试探多少个候选点。 */
	public int bidirectionalMidpointProbeReuseMaxCandidates = 3;
	/** 2026-06-06: Tmid probe 每轮按左右压力移动的比例；0.15 表示左移 *0.85，右移 *1.15。 */
	public double bidirectionalMidpointProbeMoveRatio = 0.15;
	/** 2026-06-06: Tmid probe 自动选择使用的 score；可选 kept/queue/bound/remaining/peak。 */
	public String bidirectionalMidpointProbeScore = "queue";
	/** 2026-06-08: 二级 score 仍默认 off；remaining 只作为实验口径，个别算例不能证明适合全局默认。 */
	public String bidirectionalMidpointProbeTieScore = "off";
	/** 2026-06-07: 主指标倍数差不超过该值时，才使用二级 score 打破平局。 */
	public double bidirectionalMidpointProbeTieTolerance = 0.0;
	/** 2026-06-07: rank=1 候选达到该不均衡倍数后，再额外试少量候选即可停止；小于等于 1 表示关闭。 */
	public double bidirectionalMidpointProbeEarlyStopRatio = 1.5;
	/** 2026-06-07: 达到早停阈值后额外继续试探的候选数。 */
	public int bidirectionalMidpointProbeExtraCandidatesAfterThreshold = 1;
	/** 2026-06-07: Tmid 试探方向反转时，是否在前后两个候选之间额外试一次中点后停止。 */
	public boolean bidirectionalMidpointProbeBracketOnDirectionChange = true;
	/** 2026-06-14: probe 到达基础候选数后，如果不均衡仍超过该倍数，则允许继续同方向试探。 */
	public double bidirectionalMidpointProbeHighImbalanceRatio = 10.0;
	/** 2026-06-07: 同一 BPC node 后续 pricing round 是否以上一轮选中的 Tmid 作为 probe reference。 */
	public boolean bidirectionalMidpointProbeReuseWithinNode = false;
	/**
	 * 2026-06-07: 保留的实验开关。当前主线改为复用同一 node 内历史 exact 表现最好的 Tmid，
	 * 不再默认用上一轮 forward/backward 压力做方向乘法修正。
	 */
	public boolean bidirectionalMidpointProbeExactFeedback = false;
	/** 2026-06-08: node 内历史 Tmid 复用时，两个 exact 耗时相差不超过较大值的该比例才进入二级比较。 */
	public double bidirectionalMidpointProbeExactTimeTieTolerance = 0.10;
	/** 2026-06-08: exact 耗时接近时，F/B kept ratio 至少改善该比例才替换历史最快 Tmid。 */
	public double bidirectionalMidpointProbeExactBalanceImprovementTolerance = 0.30;
	/** 2026-06-15: 40 任务测试显示 150/1000 过小会增加 pricing 轮数和波动，默认放宽到 1500。 */
	public int maxHeuristicPricingColumns = 1500;
	/** 2026-05-18: 对应旧 VRP Configure.m_tabu_cg_size，从当前 RMP 中挑多少条低 reduced cost 列作为 tabu seed。 */
	public int heuristicPricingSeedColumns = 30;
	/** 2026-06-15: 本地负 reduced-cost 候选池生成上限；与 maxHeuristicPricingColumns 配套放宽到 5000。 */
	public int heuristicPricingPoolSize = 5000;
	/** 2026-05-18: 对应旧 VRP Configure.m_tabu_cg_iteration_number，每条 seed 的 tabu 搜索轮数。 */
	public int heuristicPricingTabuIterations = 50;
	/** 2026-05-18: 对应旧 VRP Configure.m_tabu_cg_tenure，tabu 禁忌长度。 */
	public int heuristicPricingTabuTenure = 30;
	/** 2026-06-21: 40 任务测试中列过少会增加 pricing 波动，单次 exact pricing 默认放宽到 5000。 */
	public int maxExactPricingColumns = 5000;
	/** 2026-06-20: Columnized outsourcing pricing has its own return cap; keep it separate from internal exact pricing. */
	public int maxOutsourcingPricingColumns = 150;
	/**
	 * 2026-06-20: no-cut time-indexed graph pricing 每轮最多返回多少条负列。
	 * 原文 exact stage 每个 pricing subproblem 最多返回 300 条；这里单独成参，避免影响普通 exact pricing。
	 */
	public int timeIndexedGraphMaxExactPricingColumns = 300;
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
	/** 2026-06-13: 是否启用三元 subset-row cut；当前只允许 partial-list ng-DSSR exact pricing 使用其 dual。 */
	public boolean enableSubsetRowCutsForPartialDominance = false;
	/** 2026-06-28: 允许 time-indexed rank-1 cut pricing 使用 subset-row cut。 */
	public boolean enableSubsetRowCutsForTimeIndexedGraph = false;
	/** 2026-06-13: 每轮 subset-row separation 最多加入多少条 cut。 */
	public int maxSubsetRowCutsPerRound = 10;
	/** 2026-06-14: subset-row cut 默认不限制单 node 总数，只保留每轮加入上限。 */
	public int maxSubsetRowCutsPerNode = Integer.MAX_VALUE;
	/** 2026-06-13: 同一 job 在 active subset-row cuts 中最多出现多少次；默认对齐旧 VRP m_max_appear_number。 */
	public int maxSubsetRowCutAppearancesPerJob = 20;
	/** 2026-06-13: subset-row cut 加入阈值；旧 VRP 中首条 cut 至少需要达到约 1.1。 */
	public double subsetRowCutMinimumViolationValue = 1.1;
	/** 2026-06-13: subset-row cut 扫描停止阈值；低于该行值的候选不再加入。 */
	public double subsetRowCutMinimumThreshold = 1.02;
	/** 2026-06-15: subset-row cut memory 口径；full 保持旧 SRI，nodeMemory/arcMemory 使用 limited-memory SRI。 */
	public String subsetRowCutMemoryMode = "full";
	/** 判断是否整数时使用的容差。 */
	public double branchingTolerance = 1e-6;
	/** 2026-06-26: 无向邻接分支默认关闭；此前测试中它容易引入异常 arc dual，削弱 pricing dominance/bounds。 */
	public boolean enableUndirectedAdjacencyBranching = false;
	/** 节点初始伪成本占位值。 */
	public double pseudoCostInf = 1e18;
	/** 2026-06-02: 每个节点 LP 松弛完成后，是否用当前 restricted columns 求一次整数 RMP 刷新启发式上界。 */
	public boolean enableRestrictedMasterIntegerHeuristic = true;
	/** 2026-06-02: restricted integer RMP 的 CPLEX 时间限制；小于等于 0 表示不设限制。 */
	public double restrictedMasterIntegerHeuristicTimeLimitSeconds = 4.0;
	/** 2026-06-21: RMIH 大规模算例阈值；n 大于该值时使用更宽的 MIP 时间限制。 */
	public int restrictedMasterIntegerHeuristicLargeInstanceThreshold = 50;
	/** 2026-06-21: RMIH 大规模算例时间限制；只在该值大于普通限制时生效。 */
	public double restrictedMasterIntegerHeuristicLargeInstanceTimeLimitSeconds = 20.0;
	/** 2026-06-04: RMIH 模式；coverRepair=筛列 >= + 修复列 + ==，partition=全量 ==。 */
	public String restrictedMasterIntegerHeuristicMode = "coverRepair";
	/** 2026-06-04: coverRepair 中按 reduced cost 排序保留的列数；小于等于 0 表示全保留。 */
	public int restrictedMasterIntegerHeuristicReducedCostColumnLimit = 2000;
	/** 2026-06-26: RMIH 大规模算例保留更多低 reduced-cost 列，减少筛列过窄导致的上界波动。 */
	public int restrictedMasterIntegerHeuristicLargeInstanceReducedCostColumnLimit = 6000;
	/** 2026-06-04: coverRepair 中每个 job 额外保留的最低 reduced cost 覆盖列数。 */
	public int restrictedMasterIntegerHeuristicPerJobColumnLimit = 10;
	/** 2026-06-04: 单列重复 job 不超过该数量时枚举所有删除组合生成修复列。 */
	public int restrictedMasterIntegerHeuristicRepairEnumerationDuplicateLimit = 5;
	/** 2026-06-08: 是否打印 RMIH 内部 CPLEX MIP 日志；默认关闭，仅用于判断耗时是在找可行解还是证明最优。 */
	public boolean diagnosticRestrictedIntegerMipLog = false;
	/** 2026-06-04: 长运行诊断用阶段开始 heartbeat；默认关闭，不改变求解逻辑。 */
	public boolean diagnosticStageHeartbeat = false;
	/** 2026-06-05: 每个节点处理完成后输出一行聚合诊断，便于定位长跑主要耗时阶段。 */
	public boolean diagnosticNodeProgressSummary = false;
	/** 2026-06-05: subtree/dual 诊断明细；默认关闭，避免每轮 pricing 扫描列池和所有 job arc。 */
	public boolean diagnosticPricingSummaryDetails = false;
	/** 2026-06-28: node 级 local horizon 改进实验，当前默认关闭且不接入主求解。 */
	public boolean enableNodeLocalHorizonImprovement = false;
	public int nodeLocalHorizonImprovementNodeId = -1;
	public double nodeLocalHorizonImprovementTimeLimitSeconds = 5.0;
	public boolean nodeLocalHorizonImprovementUseCplex = true;
	public boolean nodeLocalHorizonImprovementUseCp = true;

	public boolean useColumnizedOutsourcing() {
		return "columns".equalsIgnoreCase(outsourcingModel)
				|| "columnized".equalsIgnoreCase(outsourcingModel)
				|| "sp1".equalsIgnoreCase(outsourcingModel);
	}

	/**
	 * 返回本次求解使用的配置快照。日志里记录最终 config 对象，而不是只记录命令行参数，方便追溯 runner 默认值与覆盖值。
	 */
	public List<String> snapshotLines() {
		ArrayList<Field> fields = new ArrayList<Field>();
		for (Field field : TWETBPCConfig.class.getFields()) {
			if (!Modifier.isStatic(field.getModifiers())) {
				fields.add(field);
			}
		}
		Collections.sort(fields, new Comparator<Field>() {
			@Override
			public int compare(Field a, Field b) {
				return a.getName().compareTo(b.getName());
			}
		});
		ArrayList<String> lines = new ArrayList<String>();
		for (Field field : fields) {
			try {
				lines.add("config." + field.getName() + "=" + String.valueOf(field.get(this)));
			} catch (IllegalAccessException ex) {
				throw new IllegalStateException("Failed to read BPC config field: " + field.getName(), ex);
			}
		}
		lines.add("config.derived.useColumnizedOutsourcing=" + Boolean.toString(useColumnizedOutsourcing()));
		return lines;
	}

}
