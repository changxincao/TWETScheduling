package TWETBPC;

import java.util.Objects;

/**
 * 2026-08-15: 集中维护正式对照实验使用的三套 BPC 默认参数，避免多处复制后静默漂移。
 */
public final class BestBpcProfiles {
	/** 写入正式实验结果，便于服务器侧确认三组运行使用同一套参数。 */
	public static final String VERSION = "2026-08-28-v3";

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

	static void applyCommonDefaults(TWETBPCConfig config) {
		config.runALNSForSeed = true;
		config.alnsMaxRuntimeMillis = 60_000L;
		config.alnsUseSimulatedAnnealingAcceptance = false;
		config.initialHeuristicColumnHistoryMode = "best";
		config.enableTwoStageStrongBranching = true;
		config.strongBranchingCandidateLimit = 20;
		config.strongBranchingPhase2CandidateLimit = 0;
		config.strongBranchingPhase2MaxHeuristicPasses = 0;
		config.enableStrongBranchingLightweightRepair = true;
		config.enableStrongBranchingBranchImpliedPenalty = true;
		config.enableDualBoundPruning = true;
		config.enableDualStabilization = false;
		config.enableRestrictedMasterIntegerHeuristic = false;
		config.enableRouteEnumeration = false;
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
		config.bidirectionalJoinBestThresholdMode = "bestUB";
		config.bidirectionalCompletionBoundRelaxation = "allCycles";
		config.bidirectionalCompletionBoundScalarPruning = true;
		config.bidirectionalCompletionBoundArcFixing = true;
		config.bidirectionalCompletionBoundSubtreeArcElimination = false;
		config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly = true;
		config.bidirectionalMidpointProbe = true;
		config.bidirectionalMidpointProbePopLimit = 10000;
		config.bidirectionalMidpointProbeScore = "time";
		config.bidirectionalMidpointProbeEarlyStopRatio = 1.5;
		config.bidirectionalMidpointProbeDssrImbalanceThreshold = 2.0;
		config.enableTimeIndexedPreHeuristicPricing = false;
		config.enableTimeIndexedRootPreprocessingForNgDssr = true;
		config.timeIndexedRootPreprocessingSeedElementaryColumns = true;
		config.timeIndexedRootPreprocessingSeedColumnLimit = 200;
		config.timeIndexedCompletionBoundScalarEnhancement = true;
		config.timeIndexedCompletionBoundWindowTightening = true;
		config.timeIndexedCompletionBoundArcFixing = true;
		config.timeIndexedCompletionBoundInRoundArcFixing = false;
		config.timeIndexedCompletionBoundCutLoopArcFixing = true;
	}
}
