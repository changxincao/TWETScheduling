package TWETBPC;

import java.util.Objects;

/**
 * 2026-08-15: 集中维护正式对照实验使用的三套 BPC 默认参数，避免多处复制后静默漂移。
 */
public final class BestBpcProfiles {
	/** 写入正式实验结果，便于服务器侧确认三组运行使用同一套参数。 */
	public static final String VERSION = "2026-08-30-v5";

	public static final BPCAlgorithmProfile TIME_INDEXED_GRAPH = new BPCAlgorithmProfile(
			"timeIndexedGraph", true, false, false, config -> applyTimeIndexedDefaults(config, false));
	public static final BPCAlgorithmProfile TIME_INDEXED_GRAPH_RANK1 = new BPCAlgorithmProfile(
			"timeIndexedGraphRank1Cut", false, true, false, config -> applyTimeIndexedDefaults(config, true));
	public static final BPCAlgorithmProfile NG_DSSR = new BPCAlgorithmProfile(
			"ngDssr", false, false, true, BestBpcProfiles::applyNgDssrDefaults);

	private BestBpcProfiles() {
	}

	public static BPCAlgorithmProfile require(String name) {
		if (name == null) {
			throw new IllegalArgumentException("Missing BPC algorithm profile");
		}
		String normalized = name.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
		if ("NG_DSSR".equals(normalized) || "NGDSSR".equals(normalized)) {
			return NG_DSSR;
		}
		if ("TIME_INDEXED".equals(normalized) || "TIME_INDEXED_GRAPH".equals(normalized)
				|| "TI".equals(normalized) || "TI_NO_CUT".equals(normalized)) {
			return TIME_INDEXED_GRAPH;
		}
		if ("TIME_INDEXED_SRI".equals(normalized) || "TIME_INDEXED_GRAPH_RANK1".equals(normalized)
				|| "TI_SRI".equals(normalized) || "SRI".equals(normalized)) {
			return TIME_INDEXED_GRAPH_RANK1;
		}
		throw new IllegalArgumentException("Unknown BPC algorithm profile: " + name);
	}

	/**
	 * 2026-08-15: 保持旧入口语义。time-indexed 分支优先于 ng-DSSR，
	 * 但原始开关值仍按调用参数写回 config，便于后续系统属性覆盖与诊断。
	 */
	public static void applyBestPricingModeDefaults(TWETBPCConfig config,
			boolean timeIndexedGraph, boolean timeIndexedRank1, boolean ngDssr) {
		Objects.requireNonNull(config, "config");
		applyModeFlags(config, timeIndexedGraph, timeIndexedRank1, ngDssr);
		applyCommonDefaults(config);
		BPCAlgorithmProfile profile = selectFormalProfile(timeIndexedGraph, timeIndexedRank1, ngDssr);
		if (profile != null) {
			profile.applyBranchDefaults(config);
		}
	}

	public static BPCAlgorithmProfile selectFormalProfile(boolean timeIndexedGraph, boolean timeIndexedRank1,
			boolean ngDssr) {
		if (timeIndexedGraph || timeIndexedRank1) {
			return timeIndexedRank1 ? TIME_INDEXED_GRAPH_RANK1 : TIME_INDEXED_GRAPH;
		}
		if (ngDssr) {
			return NG_DSSR;
		}
		return null;
	}

	static void applyModeFlags(TWETBPCConfig config,
			boolean timeIndexedGraph, boolean timeIndexedRank1, boolean ngDssr) {
		config.useTimeIndexedGraphRank1CutPricing = timeIndexedRank1;
		config.useTimeIndexedGraphPricing = timeIndexedGraph || timeIndexedRank1;
		config.useGCNGBBStyleNgDssrPricing = ngDssr;
	}

	/** 命名正式 profile 必须排除更高优先级实验引擎，不能依赖调用者先清理旧配置。 */
	static void resetNamedPricingModeFlags(TWETBPCConfig config) {
		config.useGCBBAsymmetricBidirectionalPricing = false;
		config.useGCBBFullDomainNodeJoinBidirectionalPricing = false;
		config.useGCBBFullDomainBidirectionalPricing = false;
		config.useGCNGBBStyleNgDssrPartialDominancePricing = false;
		config.useGCNGBBStyleNgDssrGraphPartialDominancePricing = false;
		config.useGCNGBBStylePartialDominancePricing = false;
		config.useGCNGBBStyleBidirectionalPricing = true;
	}

	/** 命名正式 profile 同时排除已验证不采用的实验分支，旧布尔入口仍保留调用者的覆盖语义。 */
	static void resetNamedExperimentFlags(TWETBPCConfig config) {
		config.enableStrongBranchingDomainRepair = false;
		config.enableUndirectedAdjacencyBranching = false;
		config.enableCutSetBranching = false;
		config.enableClusterBranching = false;
		config.structuredArcStrictTypePriority = false;
		config.enableSubsetRowCutsForPartialDominance = false;
		config.enableNodeLocalHorizonImprovement = false;
		config.debugSkipBranchColumnFilter = false;
		config.debugIgnorePricingOnlyArcsAtNode = -1;
	}

	static void applyCommonDefaults(TWETBPCConfig config) {
		config.runALNSForSeed = true;
		config.alnsMaxRuntimeMillis = 60_000L;
		config.alnsMaxNoImproveIterations = 80;
		config.alnsUseSimulatedAnnealingAcceptance = false;
		config.initialHeuristicColumnHistoryMode = "best";
		config.acceptedSolutionHistoryLimit = 2000;
		config.cplexRootAlgorithm = "auto";
		config.enableTwoStageStrongBranching = true;
		config.strongBranchingCandidateLimit = 20;
		config.strongBranchingPhase2CandidateLimit = 0;
		// 0 在 pass 参数上表示不限轮数；这里由 candidateLimit=0 关闭整个 Phase 2。
		config.strongBranchingPhase2MaxHeuristicPasses = 0;
		config.strongBranchingScoreEpsilon = 1.0e-6;
		config.enableStrongBranchingLightweightRepair = true;
		config.enableStrongBranchingBranchImpliedPenalty = true;
		config.enableDualBoundPruning = true;
		config.dualBoundPruningTolerance = 1.0e-7;
		config.enableDualStabilization = false;
		config.enableRestrictedMasterIntegerHeuristic = false;
		config.enableRouteEnumeration = false;
		config.branchSeedColumnLimit = 5000;
		config.branchSeedReducedCostAllowance = 5000.0;
		config.branchingTolerance = 1.0e-6;
		config.pseudoCostInf = 1.0e18;
		config.maxOutsourcingPricingColumns = 150;
	}

	private static void applyTimeIndexedDefaults(TWETBPCConfig config, boolean timeIndexedRank1) {
		// 2026-08-16: 既有 A/B 中 no-cut 均退化，SRI 仅有 1.4% 的单例改善；正式口径保留旧 M repair。
		config.enableStrongBranchingPhaseOneRepair = false;
		config.enableHeuristicPricing = false;
		config.enableTimeIndexedGraphDualWindow = true;
		config.timeIndexedGraphMaxExactPricingColumns = 300;
		config.bidirectionalCompletionBoundRelaxation = "off";
		config.bidirectionalCompletionBoundScalarPruning = false;
		config.bidirectionalCompletionBoundArcFixing = false;
		config.bidirectionalCompletionBoundSubtreeArcElimination = false;
		config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly = false;
		config.timeIndexedCompletionBoundScalarEnhancement = false;
		config.timeIndexedCompletionBoundWindowTightening = false;
		config.timeIndexedCompletionBoundArcFixing = true;
		config.timeIndexedCompletionBoundInRoundArcFixing = false;
		config.timeIndexedCompletionBoundCutLoopArcFixing = timeIndexedRank1;
		config.timeIndexedCompletionBoundSriAwareArcFixing = false;
		config.enableSubsetRowCutsForTimeIndexedGraph = timeIndexedRank1;
		config.subsetRowCutMemoryMode = "arcMemory";
		config.maxCutRounds = 8;
		config.maxSubsetRowCutAppearancesPerJob = 20;
		config.bidirectionalMidpointProbe = false;
	}

	private static void applyNgDssrDefaults(TWETBPCConfig config) {
		// ng-DSSR 的多轮 DSSR repair 使用纯 Phase-I 明显更容易先恢复可行 RMP。
		config.enableStrongBranchingPhaseOneRepair = true;
		config.enableHeuristicPricing = true;
		config.enableHeuristicDualProfitableWindow = false;
		config.maxHeuristicPricingColumns = 300;
		config.heuristicPricingSeedColumns = 30;
		config.heuristicPricingPoolSize = 300;
		config.heuristicPricingTabuIterations = 50;
		config.heuristicPricingTabuTenure = 30;
		config.heuristicPricingStopUnproductiveSeedAfter20 = false;
		config.heuristicPricingCollectNonBestNegativeMoves = false;
		config.heuristicPricingPrecomputeArcCompatibility = true;
		config.heuristicPricingPrecomputeMoveDuals = true;
		config.maxExactPricingColumns = 5000;
		// 命名 profile 必须显式恢复主定价入口，不能依赖新建 config 时恰好默认为 true。
		config.enableBidirectionalPricing = true;
		// 2026-08-28: 显式固定历史 A/B 更快的时间队列，避免正式 runner 继承漂移。
		config.forwardLabelQueueOrdering = "time";
		config.bidirectionalLabelQueueOrdering = "time";
		config.ngDssrInitialNgSetMode = "nearestK";
		config.ngDssrInitialNgSetSize = -1;
		config.ngDssrNonElementaryRouteUpdateLimit = 20;
		config.ngDssrNonElementaryRouteCandidateLimit = 1000;
		config.ngDssrNonElementaryRouteUpdateMode = "minimumNewPairsSegment";
		config.useIncrementalSourcedDominanceGraph = true;
		config.enableNgDssrJoinEnvelopePrefilter = true;
		config.enableNgDssrJoinVisitProfilePruning = true;
		config.enableNgDssrWindowRepeatabilityInitialFilter = true;
		config.enableNgDssrHistoryWarmStart = false;
		config.enableNgDssrSameNodeWarmStart = false;
		config.ngDssrReturnRelaxedColumns = false;
		config.enableNgDssrJoinEnvelopeCompression = false;
		config.bidirectionalJoinBestThresholdMode = "bestUB";
		config.bidirectionalJoinRangeRestrictedLowerBound = false;
		config.bidirectionalCompletionBoundRelaxation = "allCycles";
		config.bidirectionalCompletionBoundQueueOrdering = "fifo";
		config.bidirectionalCompletionBoundScalarPruning = true;
		config.bidirectionalCompletionBoundArcFixing = true;
		config.bidirectionalCompletionBoundSubtreeArcElimination = false;
		config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly = true;
		config.bidirectionalRootLocalHorizonMidpointRatio = Double.NaN;
		config.bidirectionalMidpointStrategy = "default";
		config.bidirectionalMidpointProbe = true;
		config.bidirectionalMidpointProbePopLimit = 10000;
		config.bidirectionalMidpointProbeScore = "time";
		config.bidirectionalMidpointProbeEarlyStopRatio = 1.5;
		// 2026-08-30: 后续 DSSR 轮统一以 4 倍失衡触发并校验 adaptive Tmid，避免 2--4 区间重复大幅纠偏。
		config.bidirectionalMidpointProbeDssrEarlyStopRatio = 4.0;
		config.bidirectionalMidpointProbeDssrMoveFraction = 0.05;
		config.bidirectionalMidpointProbeDssrImbalanceThreshold = 4.0;
		config.bidirectionalMidpointProbeReuseWithinDssr = true;
		config.bidirectionalMidpointProbeAfterFirstDssrRound = true;
		config.enableTimeIndexedPreHeuristicPricing = false;
		config.enableTimeIndexedPreHeuristicInStrongBranchingPhase2 = false;
		config.enableTimeIndexedGraphDualWindow = true;
		config.enableTimeIndexedRootPreprocessingForNgDssr = true;
		config.timeIndexedRootPreprocessingSeedElementaryColumns = true;
		config.timeIndexedRootPreprocessingSeedColumnLimit = 200;
		config.timeIndexedCompletionBoundScalarEnhancement = true;
		config.timeIndexedCompletionBoundWindowTightening = true;
		config.timeIndexedCompletionBoundArcFixing = true;
		config.timeIndexedCompletionBoundInRoundArcFixing = false;
		config.timeIndexedCompletionBoundCutLoopArcFixing = true;
		config.timeIndexedCompletionBoundSriAwareArcFixing = false;
		config.enableSubsetRowCutsForTimeIndexedGraph = false;
	}
}
