package HEU;

import java.lang.reflect.Method;
import java.nio.file.Path;

import TWETBPC.TWETBPCConfig;

/**
 * 三种正式定价模式的默认配置回归，防止启动命令再次关闭关键组件。
 */
public class GCBBFullDomainBestProfileTest {

	public static void main(String[] args) {
		verifyNgDssr();
		verifyTimeIndexed(false);
		verifyTimeIndexed(true);
		verifyRunnerDefaults();
		System.out.println("GCBBFullDomainBestProfileTest passed.");
	}

	private static void verifyNgDssr() {
		TWETBPCConfig config = new TWETBPCConfig();
		GCBBFullDomainComparisonTest.applyBestPricingModeDefaults(config, false, false, true);
		require(config.useGCNGBBStyleNgDssrPricing, "ng-DSSR engine");
		require(config.enableHeuristicPricing, "ng heuristic pricing");
		require("time".equals(config.forwardLabelQueueOrdering), "ng forward time queue");
		require("time".equals(config.bidirectionalLabelQueueOrdering), "ng bidirectional time queue");
		require(config.enableStrongBranchingPhaseOneRepair, "ng Phase-I repair");
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
		require("bestUB".equals(config.bidirectionalJoinBestThresholdMode), "ng join threshold");
		require(config.bidirectionalCompletionBoundScalarPruning, "ng scalar completion pruning");
		require(config.bidirectionalCompletionBoundArcFixing, "ng completion arc fixing");
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
		require(config.bidirectionalMidpointProbePopLimit == 10000, "ng midpoint total pop budget");
		require("time".equals(config.bidirectionalMidpointProbeScore), "ng midpoint score");
		require(config.bidirectionalMidpointProbeEarlyStopRatio == 1.5, "ng midpoint acceptance ratio");
		require(config.bidirectionalMidpointProbeDssrImbalanceThreshold == 4.0,
				"ng DSSR midpoint feedback ratio");
		verifyCommon(config);
	}

	private static void verifyTimeIndexed(boolean sri) {
		TWETBPCConfig config = new TWETBPCConfig();
		GCBBFullDomainComparisonTest.applyBestPricingModeDefaults(config, true, sri, false);
		require(config.useTimeIndexedGraphPricing, "time-indexed engine");
		require(config.useTimeIndexedGraphRank1CutPricing == sri, "rank-1 mode");
		require(!config.enableHeuristicPricing, "no external heuristic pricing");
		require(config.enableTimeIndexedGraphDualWindow, "dual window");
		require(config.timeIndexedGraphMaxExactPricingColumns == 300, "time-indexed column limit");
		require("off".equals(config.bidirectionalCompletionBoundRelaxation), "generic completion bound off");
		require(!config.bidirectionalCompletionBoundScalarPruning, "generic scalar helper off");
		require(!config.bidirectionalCompletionBoundArcFixing, "generic completion arc fixing off");
		require(!config.bidirectionalCompletionBoundSubtreeArcElimination, "generic subtree fixing off");
		require(!config.timeIndexedCompletionBoundScalarEnhancement, "time-indexed scalar helper off");
		require(!config.timeIndexedCompletionBoundWindowTightening, "time-indexed window helper off");
		require(config.timeIndexedCompletionBoundArcFixing, "node-end paper arc fixing");
		require(!config.timeIndexedCompletionBoundInRoundArcFixing, "in-round fixing off");
		require(config.timeIndexedCompletionBoundCutLoopArcFixing == sri, "cut-loop fixing");
		require(!config.timeIndexedCompletionBoundSriAwareArcFixing, "SRI-aware fixing off");
		require(config.enableSubsetRowCutsForTimeIndexedGraph == sri, "SRI separation");
		require(!config.enableStrongBranchingPhaseOneRepair, "time-indexed old M repair");
		require("arcMemory".equals(config.subsetRowCutMemoryMode), "arc memory");
		require(config.maxCutRounds == 8, "outer cut rounds");
		require(config.maxSubsetRowCutAppearancesPerJob == 20, "rank-1 job appearance limit");
		verifyCommon(config);
	}

	private static void verifyCommon(TWETBPCConfig config) {
		require(config.runALNSForSeed, "ALNS seed");
		require(config.alnsMaxRuntimeMillis == 60_000L, "ALNS time");
		require(!config.alnsUseSimulatedAnnealingAcceptance, "SA off");
		require("best".equals(config.initialHeuristicColumnHistoryMode), "best history");
		require(config.enableTwoStageStrongBranching, "strong branching");
		require(config.strongBranchingCandidateLimit == 20, "strong candidates");
		require(config.strongBranchingPhase2CandidateLimit == 0, "strong phase2 off");
		require(config.enableStrongBranchingLightweightRepair, "lightweight strong trial");
		require(config.enableStrongBranchingBranchImpliedPenalty, "branch-implied penalty");
		require(config.enableDualBoundPruning, "dual-bound pruning");
		require(!config.enableDualStabilization, "dual stabilization off");
		require(!config.enableRouteEnumeration, "route enumeration off");
	}

	private static void verifyRunnerDefaults() {
		String prefix = "twet.bpc.fullDomainCompare.";
		String[] keys = {
				"timeIndexedGraphPricing",
				"timeIndexedGraphRank1CutPricing",
				"ngDssr",
				"timeIndexedCompletionBoundArcFixing"
		};
		try {
			clear(prefix, keys);
			System.setProperty(prefix + "timeIndexedGraphPricing", "true");
			TWETBPCConfig noCut = buildRunnerConfig();
			require(noCut.timeIndexedCompletionBoundArcFixing, "runner no-cut node-end fixing");
			require(!noCut.timeIndexedCompletionBoundCutLoopArcFixing, "runner no-cut cut-loop fixing");
			require(!noCut.enableSubsetRowCutsForTimeIndexedGraph, "runner no-cut separation");

			clear(prefix, keys);
			System.setProperty(prefix + "timeIndexedGraphRank1CutPricing", "true");
			TWETBPCConfig sri = buildRunnerConfig();
			require(sri.useTimeIndexedGraphPricing, "rank-1 implies time-indexed graph");
			require(sri.enableSubsetRowCutsForTimeIndexedGraph, "runner SRI separation");
			require(sri.timeIndexedCompletionBoundCutLoopArcFixing, "runner SRI cut-loop fixing");

			clear(prefix, keys);
			System.setProperty(prefix + "timeIndexedGraphPricing", "true");
			System.setProperty(prefix + "timeIndexedCompletionBoundArcFixing", "false");
			require(!buildRunnerConfig().timeIndexedCompletionBoundArcFixing, "explicit A/B override");
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError("Cannot inspect runner defaults", ex);
		} finally {
			clear(prefix, keys);
		}
	}

	private static TWETBPCConfig buildRunnerConfig() throws ReflectiveOperationException {
		Method method = GCBBFullDomainComparisonTest.class.getDeclaredMethod(
				"buildConfig", Path.class, boolean.class, boolean.class);
		method.setAccessible(true);
		return (TWETBPCConfig) method.invoke(null, Path.of("profile-test.dat"), false, false);
	}

	private static void clear(String prefix, String[] keys) {
		for (String key : keys) {
			System.clearProperty(prefix + key);
		}
	}

	private static void require(boolean condition, String name) {
		if (!condition) {
			throw new AssertionError("Unexpected best-profile setting: " + name);
		}
	}
}
