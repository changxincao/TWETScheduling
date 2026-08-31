package TWETBPC;

/**
 * 正式 BPC 默认配置集中化后的回归测试。
 */
public final class BestBpcProfilesTest {

	private BestBpcProfilesTest() {
	}

	public static void main(String[] args) {
		verifyQueueDefaults();
		verifyNamedProfiles();
		verifySelectorDefaults();
		verifyOverlapSemantics();
		verifyNamedProfileClearsStaleModes();
		System.out.println("BestBpcProfilesTest passed.");
	}

	private static void verifyQueueDefaults() {
		TWETBPCConfig config = new TWETBPCConfig();
		require("time".equals(config.forwardLabelQueueOrdering), "default forward time queue");
		require("time".equals(config.bidirectionalLabelQueueOrdering), "default bidirectional time queue");
	}

	private static void verifyNamedProfileClearsStaleModes() {
		TWETBPCConfig config = new TWETBPCConfig();
		config.useGCBBAsymmetricBidirectionalPricing = true;
		config.useGCBBFullDomainNodeJoinBidirectionalPricing = true;
		config.useGCNGBBStyleNgDssrPartialDominancePricing = true;
		config.enableBidirectionalPricing = false;
		config.enableStrongBranchingDomainRepair = true;
		config.enableUndirectedAdjacencyBranching = true;
		config.enableCutSetBranching = true;
		config.enableClusterBranching = true;
		config.structuredArcStrictTypePriority = true;
		config.enableHeuristicDualProfitableWindow = true;
		config.heuristicPricingStopUnproductiveSeedAfter20 = true;
		config.heuristicPricingCollectNonBestNegativeMoves = true;
		config.ngDssrReturnRelaxedColumns = true;
		config.enableNgDssrJoinEnvelopeCompression = true;
		config.bidirectionalJoinRangeRestrictedLowerBound = true;
		config.bidirectionalRootLocalHorizonMidpointRatio = 0.25;
		config.bidirectionalMidpointStrategy = "windowAverage";
		config.bidirectionalMidpointProbeReuseWithinDssr = false;
		config.bidirectionalMidpointProbeAfterFirstDssrRound = false;
		config.enableTimeIndexedGraphDualWindow = false;
		config.timeIndexedCompletionBoundSriAwareArcFixing = true;
		config.enableSubsetRowCutsForPartialDominance = true;
		config.enableSubsetRowCutsForTimeIndexedGraph = true;
		config.enableNodeLocalHorizonImprovement = true;
		config.debugSkipBranchColumnFilter = true;
		config.debugIgnorePricingOnlyArcsAtNode = 4;
		config.maxExactPricingColumns = 7;
		config.maxHeuristicPricingColumns = 8;
		config.heuristicPricingSeedColumns = 9;
		config.heuristicPricingPoolSize = 10;
		config.heuristicPricingTabuIterations = 11;
		config.heuristicPricingTabuTenure = 12;
		config.heuristicPricingPrecomputeArcCompatibility = false;
		config.heuristicPricingPrecomputeMoveDuals = false;
		config.branchSeedColumnLimit = 13;
		config.branchSeedReducedCostAllowance = 14.0;
		config.cplexRootAlgorithm = "barrier";
		config.pseudoCostInf = 15.0;
		config.maxOutsourcingPricingColumns = 16;
		config.bidirectionalCompletionBoundQueueOrdering = "reducedCost";
		config.enableTimeIndexedPreHeuristicInStrongBranchingPhase2 = true;
		BestBpcProfiles.NG_DSSR.apply(config);
		require(!config.useGCBBAsymmetricBidirectionalPricing, "stale asymmetric mode cleared");
		require(!config.useGCBBFullDomainNodeJoinBidirectionalPricing, "stale full-domain mode cleared");
		require(!config.useGCNGBBStyleNgDssrPartialDominancePricing, "stale partial ng mode cleared");
		require(config.useGCNGBBStyleNgDssrPricing, "named ng mode retained");
		require(config.enableBidirectionalPricing, "bidirectional ng entry restored");
		require(!config.enableStrongBranchingDomainRepair, "stale domain repair cleared");
		require(!config.enableUndirectedAdjacencyBranching, "stale adjacency branching cleared");
		require(!config.enableCutSetBranching, "stale cut-set branching cleared");
		require(!config.enableClusterBranching, "stale cluster branching cleared");
		require(!config.structuredArcStrictTypePriority, "stale strict branching priority cleared");
		require(!config.enableHeuristicDualProfitableWindow, "stale heuristic dual window cleared");
		require(!config.heuristicPricingStopUnproductiveSeedAfter20, "stale heuristic early stop cleared");
		require(!config.heuristicPricingCollectNonBestNegativeMoves, "stale non-best collection cleared");
		require(!config.ngDssrReturnRelaxedColumns, "stale relaxed-column return cleared");
		require(!config.enableNgDssrJoinEnvelopeCompression, "stale join compression cleared");
		require(!config.bidirectionalJoinRangeRestrictedLowerBound, "stale join range bound cleared");
		require(Double.isNaN(config.bidirectionalRootLocalHorizonMidpointRatio), "stale fixed midpoint cleared");
		require("default".equals(config.bidirectionalMidpointStrategy), "default midpoint restored");
		require(config.bidirectionalMidpointProbeReuseWithinDssr, "DSSR midpoint reuse restored");
		require(config.bidirectionalMidpointProbeAfterFirstDssrRound, "later DSSR probe restored");
		require(config.enableTimeIndexedGraphDualWindow, "time-indexed dual window restored");
		require(!config.timeIndexedCompletionBoundSriAwareArcFixing, "stale SRI fixing cleared");
		require(!config.enableSubsetRowCutsForPartialDominance, "stale partial SRI cleared");
		require(!config.enableSubsetRowCutsForTimeIndexedGraph, "stale time-indexed SRI cleared");
		require(!config.enableNodeLocalHorizonImprovement, "stale local horizon experiment cleared");
		require(!config.debugSkipBranchColumnFilter, "stale branch filter bypass cleared");
		require(config.debugIgnorePricingOnlyArcsAtNode == -1, "stale pricing-only bypass cleared");
		require(config.maxExactPricingColumns == 5000, "exact return limit restored");
		require(config.maxHeuristicPricingColumns == 300, "heuristic return limit restored");
		require(config.heuristicPricingSeedColumns == 30, "heuristic seed count restored");
		require(config.heuristicPricingPoolSize == 300, "heuristic pool restored");
		require(config.heuristicPricingTabuIterations == 50, "tabu iterations restored");
		require(config.heuristicPricingTabuTenure == 30, "tabu tenure restored");
		require(config.heuristicPricingPrecomputeArcCompatibility, "heuristic arc snapshot restored");
		require(config.heuristicPricingPrecomputeMoveDuals, "heuristic dual snapshot restored");
		require(config.branchSeedColumnLimit == 5000, "branch seed limit restored");
		require(config.branchSeedReducedCostAllowance == 5000.0, "branch seed allowance restored");
		require("auto".equals(config.cplexRootAlgorithm), "CPLEX root algorithm restored");
		require(config.pseudoCostInf == 1.0e18, "pseudo-cost sentinel restored");
		require(config.maxOutsourcingPricingColumns == 150, "outsourcing return limit restored");
		require("fifo".equals(config.bidirectionalCompletionBoundQueueOrdering),
				"completion-bound queue restored");
		require(!config.enableTimeIndexedPreHeuristicInStrongBranchingPhase2,
				"strong phase2 pre-heuristic restored off");
	}

	private static void verifyNamedProfiles() {
		TWETBPCConfig timeIndexed = new TWETBPCConfig();
		BestBpcProfiles.TIME_INDEXED_GRAPH.apply(timeIndexed);
		require("timeIndexedGraph".equals(BestBpcProfiles.TIME_INDEXED_GRAPH.getName()), "time-indexed profile name");
		assertTimeIndexed(timeIndexed, false);

		TWETBPCConfig rank1 = new TWETBPCConfig();
		BestBpcProfiles.TIME_INDEXED_GRAPH_RANK1.apply(rank1);
		require("timeIndexedGraphRank1Cut".equals(BestBpcProfiles.TIME_INDEXED_GRAPH_RANK1.getName()),
				"rank-1 profile name");
		assertTimeIndexed(rank1, true);

		TWETBPCConfig ngDssr = new TWETBPCConfig();
		BestBpcProfiles.NG_DSSR.apply(ngDssr);
		require("ngDssr".equals(BestBpcProfiles.NG_DSSR.getName()), "ng-DSSR profile name");
		assertNgDssr(ngDssr);
	}

	private static void verifySelectorDefaults() {
		TWETBPCConfig timeIndexed = new TWETBPCConfig();
		BestBpcProfiles.applyBestPricingModeDefaults(timeIndexed, true, false, false);
		assertTimeIndexed(timeIndexed, false);
		require(BestBpcProfiles.selectFormalProfile(true, false, false) == BestBpcProfiles.TIME_INDEXED_GRAPH,
				"time-indexed selector");

		TWETBPCConfig rank1 = new TWETBPCConfig();
		BestBpcProfiles.applyBestPricingModeDefaults(rank1, false, true, false);
		assertTimeIndexed(rank1, true);
		require(BestBpcProfiles.selectFormalProfile(false, true, false) == BestBpcProfiles.TIME_INDEXED_GRAPH_RANK1,
				"rank-1 selector");

		TWETBPCConfig ngDssr = new TWETBPCConfig();
		BestBpcProfiles.applyBestPricingModeDefaults(ngDssr, false, false, true);
		assertNgDssr(ngDssr);
		require(BestBpcProfiles.selectFormalProfile(false, false, true) == BestBpcProfiles.NG_DSSR,
				"ng-DSSR selector");
	}

	private static void verifyOverlapSemantics() {
		TWETBPCConfig overlap = new TWETBPCConfig();
		overlap.enableCutSetBranching = true;
		BestBpcProfiles.applyBestPricingModeDefaults(overlap, true, false, true);
		require(overlap.useTimeIndexedGraphPricing, "overlap keeps time-indexed graph flag");
		require(overlap.useGCNGBBStyleNgDssrPricing, "overlap keeps ng-DSSR flag");
		require(!overlap.enableHeuristicPricing, "time-indexed branch still wins");
		require(!overlap.bidirectionalMidpointProbe, "ng-DSSR midpoint probe must not leak into time-indexed");
		require(!overlap.enableSubsetRowCutsForTimeIndexedGraph, "non-rank1 overlap must stay without SRI cuts");
		require(overlap.enableCutSetBranching, "legacy selector keeps explicit experiment overrides");
	}

	private static void assertTimeIndexed(TWETBPCConfig config, boolean sri) {
		require(config.useTimeIndexedGraphPricing, "time-indexed engine");
		require(config.useTimeIndexedGraphRank1CutPricing == sri, "rank-1 mode");
		require(!config.useGCNGBBStyleNgDssrPricing, "ng-DSSR flag off");
		require(!config.enableHeuristicPricing, "no external heuristic pricing");
		require(config.enableTimeIndexedGraphDualWindow, "dual window");
		require(config.timeIndexedGraphMaxExactPricingColumns == 300, "time-indexed column limit");
		require("off".equals(config.bidirectionalCompletionBoundRelaxation), "generic completion bound off");
		require(!config.bidirectionalCompletionBoundScalarPruning, "generic scalar helper off");
		require(!config.bidirectionalCompletionBoundArcFixing, "generic completion arc fixing off");
		require(!config.bidirectionalCompletionBoundSubtreeArcElimination, "generic subtree fixing off");
		require(!config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly, "generic pricing-only fixing off");
		require(!config.timeIndexedCompletionBoundScalarEnhancement, "time-indexed scalar helper off");
		require(!config.timeIndexedCompletionBoundWindowTightening, "time-indexed window helper off");
		require(config.timeIndexedCompletionBoundArcFixing, "node-end paper arc fixing");
		require(!config.timeIndexedCompletionBoundInRoundArcFixing, "in-round fixing off");
		require(config.timeIndexedCompletionBoundCutLoopArcFixing == sri, "cut-loop fixing");
		require(!config.timeIndexedCompletionBoundSriAwareArcFixing, "SRI-aware fixing off");
		require(config.enableSubsetRowCutsForTimeIndexedGraph == sri, "SRI separation");
		require("arcMemory".equals(config.subsetRowCutMemoryMode), "arc memory");
		require(config.maxCutRounds == 8, "outer cut rounds");
		require(config.maxSubsetRowCutAppearancesPerJob == 20, "rank-1 job appearance limit");
		require(!config.bidirectionalMidpointProbe, "midpoint probe off");
		require(!config.enableStrongBranchingPhaseOneRepair, "time-indexed old M repair");
		assertCommon(config);
	}

	private static void assertNgDssr(TWETBPCConfig config) {
		require(!config.useTimeIndexedGraphPricing, "time-indexed flag off");
		require(!config.useTimeIndexedGraphRank1CutPricing, "rank-1 flag off");
		require(config.useGCNGBBStyleNgDssrPricing, "ng-DSSR engine");
		require(config.enableHeuristicPricing, "ng heuristic pricing");
		require("time".equals(config.forwardLabelQueueOrdering), "ng forward time queue");
		require("time".equals(config.bidirectionalLabelQueueOrdering), "ng bidirectional time queue");
		require("nearestK".equals(config.ngDssrInitialNgSetMode), "ng initial mode");
		require(config.ngDssrInitialNgSetSize == -1, "ng auto n/10 initial size");
		require("allCycles".equals(config.bidirectionalCompletionBoundRelaxation), "ng completion bound");
		require(config.useIncrementalSourcedDominanceGraph, "source-aware dominance");
		require(config.enableNgDssrJoinEnvelopePrefilter, "group join prefilter");
		require(config.enableNgDssrJoinVisitProfilePruning, "join visit-profile pruning");
		require(config.enableNgDssrWindowRepeatabilityInitialFilter, "repeatability filter");
		require(config.ngDssrNonElementaryRouteCandidateLimit == 1000, "ng candidate C");
		require(config.ngDssrNonElementaryRouteUpdateLimit == 20, "ng update K");
		require("minimumNewPairsSegment".equals(config.ngDssrNonElementaryRouteUpdateMode), "ng update mode");
		require(!config.enableNgDssrHistoryWarmStart, "ng history warm-start off");
		require(!config.enableNgDssrSameNodeWarmStart, "ng same-node warm-start off");
		require(config.ngDssrSameNodeWarmStartWindowSize == 3, "ng same-node history window");
		require(config.ngDssrSameNodeWarmStartPerJobLimit == 2, "ng same-node per-job cap");
		require(config.ngDssrSameNodeWarmStartGlobalPairLimit == 10, "ng same-node global cap");
		require(config.ngDssrSameNodeWarmStartMinimumOccurrence == 2,
				"ng same-node minimum occurrence");
		require("bestUB".equals(config.bidirectionalJoinBestThresholdMode), "ng join threshold");
		require(config.bidirectionalCompletionBoundScalarPruning, "ng scalar completion pruning");
		require(config.bidirectionalCompletionBoundArcFixing, "ng completion arc fixing");
		require(config.bidirectionalMidpointProbeAfterFirstDssrRound,
				"ng later DSSR rounds keep probing until a dynamic policy is validated");
		require(!config.bidirectionalCompletionBoundSubtreeArcElimination, "ng hard subtree fixing off");
		require(config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly,
				"ng pricing-only subtree fixing");
		require(!config.enableTimeIndexedPreHeuristicPricing, "ng time-indexed pre-heuristic off");
		require(config.enableTimeIndexedRootPreprocessingForNgDssr, "ng root preprocessing");
		require(config.timeIndexedRootPreprocessingSeedElementaryColumns, "ng elementary root seed");
		require(config.timeIndexedRootPreprocessingSeedColumnLimit == 200, "ng root seed limit");
		require(config.timeIndexedCompletionBoundScalarEnhancement, "ng time-indexed scalar helper");
		require(config.timeIndexedCompletionBoundWindowTightening, "ng time-indexed window helper");
		require(config.timeIndexedCompletionBoundArcFixing, "ng node-end fixing");
		require(!config.timeIndexedCompletionBoundInRoundArcFixing, "ng in-round fixing off");
		require(config.timeIndexedCompletionBoundCutLoopArcFixing, "ng cut-loop fixing");
		require(config.bidirectionalMidpointProbe, "ng midpoint probe");
		require(config.bidirectionalMidpointProbePopLimit == 10000, "ng midpoint total pop budget");
		require("time".equals(config.bidirectionalMidpointProbeScore), "ng midpoint score");
		require(config.bidirectionalMidpointProbeEarlyStopRatio == 1.5, "ng midpoint acceptance ratio");
		require(config.bidirectionalMidpointProbeDssrEarlyStopRatio == 4.0,
				"ng later DSSR midpoint acceptance ratio");
		require(config.bidirectionalMidpointProbeDssrMoveFraction == 0.05,
				"ng later DSSR midpoint move fraction");
		require(config.bidirectionalMidpointProbeDssrImbalanceThreshold == 4.0,
				"ng DSSR midpoint feedback ratio");
		require(config.enableStrongBranchingPhaseOneRepair, "ng Phase-I repair");
		assertCommon(config);
	}

	private static void assertCommon(TWETBPCConfig config) {
		require(config.solveTimeLimitSeconds == 10800.0, "formal three-hour solve limit");
		require(config.runALNSForSeed, "ALNS seed");
		require(config.alnsMaxRuntimeMillis == 60_000L, "ALNS time");
		require(config.alnsMaxNoImproveIterations == 80, "ALNS no-improve limit");
		require(!config.alnsUseSimulatedAnnealingAcceptance, "SA off");
		require("best".equals(config.initialHeuristicColumnHistoryMode), "best history");
		require(config.acceptedSolutionHistoryLimit == 2000, "accepted history limit");
		require("auto".equals(config.cplexRootAlgorithm), "CPLEX root algorithm");
		require(config.enableTwoStageStrongBranching, "strong branching");
		require(config.strongBranchingCandidateLimit == 20, "strong candidates");
		require(config.strongBranchingPhase2CandidateLimit == 0, "strong phase2 off");
		require(config.strongBranchingPhase2MaxHeuristicPasses == 0,
				"strong phase2 unlimited pass cap inactive while candidate limit is zero");
		require(config.strongBranchingScoreEpsilon == 1.0e-6, "strong score epsilon");
		require(config.enableStrongBranchingLightweightRepair, "lightweight strong trial");
		require(config.enableStrongBranchingBranchImpliedPenalty, "branch-implied penalty");
		require(config.enableDualBoundPruning, "dual-bound pruning");
		require(config.dualBoundPruningTolerance == 1.0e-7, "dual-bound tolerance");
		require(!config.enableDualStabilization, "dual stabilization off");
		require(!config.enableRestrictedMasterIntegerHeuristic, "restricted-master heuristic off");
		require(!config.enableRouteEnumeration, "route enumeration off");
		require(config.branchSeedColumnLimit == 5000, "branch seed limit");
		require(config.branchSeedReducedCostAllowance == 5000.0, "branch seed allowance");
		require(config.branchingTolerance == 1.0e-6, "branching tolerance");
		require(config.pseudoCostInf == 1.0e18, "pseudo-cost sentinel");
		require(config.maxOutsourcingPricingColumns == 150, "outsourcing pricing limit");
	}

	private static void require(boolean condition, String name) {
		if (!condition) {
			throw new AssertionError("Unexpected best-profile setting: " + name);
		}
	}
}
