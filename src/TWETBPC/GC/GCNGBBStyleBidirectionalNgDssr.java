package TWETBPC.GC;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

import Basic.Data;
import Common.Configure;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.PiecewiseLinearFunction.Segment;
import Common.PiecewiseLinearFunction.TrimResult;
import Common.Utility;
import HEU.Solution;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Util.PackedBitSet;
import TWETBPC.Util.SequenceSignature;

/**
 * no-cut 闁告瑥鑻幃?pricing 闁汇劌瀚婊冾嚕?half-domain GCBB-style 闁糕晛鎼崳顖炴偋閸稈鍋?
 * <p>
 * 闁告瑯浜濆﹢渚€宕烽妸銉ョ仚闁轰線顣︾粭鍌炴⒔閹邦厽寮撻柟鎼簼閺屽洭濡存担椋庣懍 forward/backward 闂傚啰鍠庨崹顏堟焾閸婄噥娼堕悗鐟版湰閺嗭綁鎳撳Δ鈧弫鏍籍鐠佸湱绀夐柡鍫墲閻ゅ棛绱掗幘瀵镐函闁归潧绉磋ぐ鍙夋媴濠娾偓鐠?exact pricing
 * certificate闁挎稒绋栫€氥垺娼忛幆褍鐓?{@link TWETBPCConfig#maxExactPricingColumns}闁挎稑鐭佺换鏍煂鐏炶棄娑ч悶娑栧妿閵囨岸鍨惧鍕粯濠㈣埖姘ㄩ弫鎾诲箣?K 闁哄銈囶槹闁告帗銇涢埀顒佺缚閳?
 * <p>
 * 2026-05-22: 閺夆晜鐟╅崳閿嬬▔瀹ュ懎鏅欐繛宀冩硶閺併倝寮閻ゅ嫰鎮抽幍顔界暠闁炽儲绮岄幃鎾寸▔閳ь剚绋夐鍐幀闂傚倸顕崑?join闁炽儲绻冮悥锝夋煂韫囨梻鍨肩紒娑欐嫕缁辨繈鎳撶仦鐐﹂柡鈧憴鍕亣闁告粌鐭侀鎴﹀棘閸ワ妇顏遍柤宄邦嚟濞?
 * 闁炽儲绔緊rward 闁告挸绉剁槐?+ crossing arc (i,r) + backward 闁告艾娴风槐鎴﹀灳濠靛牊鐣辩€殿啫鍕伝闁规亽鍎埀?
 * 缂侇偉顕ч幃鏇熺▔椤撶姵鐣?GCNGBB 濞ｅ洦绻勯弳鈧柡鍐ｆ櫆濠€锟犲川閽樺鍊抽柨娑欑☉缂嶅宕滃鍥ь暭闁哄牜鍓欏﹢?label 濞戞搩鍘惧ǎ顕€骞?ng-memory闁挎稑鑻懟鐔兼偨?DSSR 闂侇偅鍔橀悿鍡涘绩閸撲焦褰?
 * ng-neighborhood闁靛棗鍊诲ù澶屸偓?{@link GCBidirectional}闁挎稑鏈﹢鎵尵鐠囨彃甯ラ悗鐟版湰閺嗭綁鎮介悢绋跨亣 forward/backward 濞戞挶鍊撻弲?label
 * table闁挎稑鑻崯鈧紓浣哄枍缁旀挳宕?crossing-arc final join闁挎稒鐭rward->sink 闁衡偓鐠鸿櫣鍟插☉鏃傚枎閼荤喖宕?final join 婵炵繝鑳堕埢濂稿Υ娣囩灒in
 * 闁告艾楠搁崢娑㈠箰?ng-memory 婵☆偀鍋撻柡灞诲劜鐎氶箖骞掗妷銉ユ倯閻庣顫夐埀顑秶绀夐柛鎰У娴狀喗寰勫鍥ㄥ焸閻庡湱鍋涚花顓㈠礆濡も偓閸ㄤ粙寮?elementary/non-elementary闁靛棗鍊跨划顖滄媼閵堝懎娑ч悹浣叉櫈缁€瀣儍?
 * elementary 闁告帗顨夌换姗€宕楅妷锔芥嫳闁?top-K 闁稿﹥鐟╅埀顒€顦伴惈婊堟晬瀹€鍐槹闁?non-elementary 閹兼潙绻愰崹顏堟偨閵娿倗鑹鹃柡鍥х摠閺?ng-set闁挎稒绋栭惁鏍棘椤撶偟纾婚柛蹇氭珪婢э箑顕ｉ埀顒勫籍鐠佸湱绀?
 * non-elementary ng-relaxed 闁告帗銇炵弧鍐╁濮樿鲸绾柟鎭掑劥缁绘﹢宕楅妷銉㈠亾濞嗘挴鍋撴径瀣建闁?
 * <p>
 * 鐟滅増鎸告晶鐘绘偋閸喐鎷遍柛蹇撶墔缁绘氨鎷?elementary 闁告瑥鑻幃婊堝礄閼恒儲娈堕梺顐ｅ笚鐢綊宕?T^mid 闁告锕ら悡娆戞嫚椤撴繄鐤呮慨婵撶悼閳ユ﹢鏁?
 * 1. forward label 閻庢稒锚閸嬪秹宕?[ell, Tmid]闁?
 * 2. backward label 閻庢稒锚閸嬪秹宕?[Tmid, rho]闁?
 * 3. join 闁哄啫澧庨弫銈囨媼閻戞ɑ鐎梺鎻掔灱濞堟垹鏁崨濠冩鐎点倛鍩栫€氬洭鏁嶇仦鐓庮槻闁哄啯鍎艰棢濮?forward 闁告瑥鍟垮畷鎰板春閻旈攱瀚?backward 鐎归潻绠戝畷鎰板春閻曞倻绀夐柣鎺曟硾閹骞?crossing arc 閻庨潧缍婄紞鍫ユ儎缁嬪灝顫ｉ柨?
 * 4. 濮掓稒顭堥濠氭儎鐎涙ê澶嶅ù锝堟硶閺?label/join 闁规亽鍔岄閬嶅礄閾忚鐣?reduced cost 闁告瑥绉电敮褰掑礄閸濆嫬鐏欓柟瀛樺姈濠€浼存晬濞戞﹩娲ら梻鍥ｅ亾閻庣懓鏈弳锝嗘償韫囨挸鐏欏璺虹У閻楁娊鏁嶇仦钘夎闁瑰灚鎸哥槐?
 * {@link Configure#debugBPCPricingColumnCheck}闁?
 */
public class GCNGBBStyleBidirectionalNgDssr {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;
	private static final HashSet<Integer> FULL_MIDPOINT_DIAGNOSTIC_DONE = new HashSet<Integer>();
	private enum LabelQueueOrdering {
		REDUCED_COST, TIME, REACHABLE_SIZE
	}

	private enum JoinBestThresholdMode {
		ZERO,
		BEST_UB,
		// 2026-05-31: 婵犵鍋撻弶?record-only 閻庨潧婀遍崣搴∥熼垾宕囩闁挎稒绋愮槐浼村礄韫囨挾姣屾慨锝呯箺閻ゅ棙娼婚弬鎸庣闁告帗顨嗛弳鐔兼晬瀹€鍕笡閻犱降鍊撶粭澶嬫媴濠娾偓鐠愮喖宕ユ惔锝囨暰婵繐绲界槐锛勬崉椤栨氨绐炲ù锝堟硶閺併倝濡?
		BEST_RECORD
	}

	public enum DominanceBackend {
		PAPER,
		GRAPH_PARTIAL,
		LIST_PARTIAL
	}

	private final Data data;
	private final TWETBPCConfig config;
	private TimeLimitChecker timeLimitChecker = TimeLimitChecker.NONE;
	private final TWETColumnEvaluator evaluator;
	private final HashMap<Integer, MidpointProbeNodeReuse> midpointProbeReuseByNode;
	private final NgDssrHistoryWarmStart historyWarmStart;

	private PriorityQueue<ForwardLabel> FWUL;
	private PriorityQueue<BackwardLabel> BWUL;
	private ArrayList<DominanceStore> FWTL;
	private ArrayList<DominanceStore> BWTL;
	private ArrayList<ArrayList<ForwardLabel>> activeForwardByLastJob;
	private ArrayList<ArrayList<BackwardLabel>> activeBackwardByFirstJob;
	private ArrayList<SinglePointStore<ForwardLabel>> forwardSinglePointByLastJob;
	private ArrayList<SinglePointStore<BackwardLabel>> backwardSinglePointByFirstJob;
	private PackedBitSet activeForwardTerminalJobs;
	private double[] minForwardReducedCostByLastJob;
	private double[] minForwardEllByLastJob;
	private ArrayList<TWETColumn> generatedColumns;
	private PriorityQueue<PricingColumnCandidate> generatedColumnCandidates;
	private HashMap<SequenceSignature, PricingColumnCandidate> generatedCandidateBySignature;
	private HashSet<SequenceSignature> activeColumnSignatures;
	private boolean[] zeroDualExcludedJobs;
	private int zeroDualExcludedJobCount;
	private int nextLabelId;
	private int nextCandidateId;
	private LabelQueueOrdering queueOrdering;
	private JoinBestThresholdMode joinBestThresholdMode;
	private CompletionBoundCalculator.Relaxation completionBoundRelaxation;
	private CompletionBoundCalculator.QueueOrdering completionBoundQueueOrdering;
	private CompletionBoundCalculator.Bounds completionBounds;
	private boolean[][] completionBoundFixedArc;
	private double bestGeneratedReducedCost;
	private double lastRelaxedRoundBestReducedCost;

	// 2026-05-22: 闁告瑥鑻幃?midpoint闁挎稑鑻ぐ褏鈧數鎳撶紞瀣礈?pricing 閺夌儐鍠楀﹢渚€寮崼娑掑亾?
	private double tMid;
	// 2026-05-24: 闁哄牜鍓濋悿?bidirectional pricing 閻庡湱鍋ゅ顖涙媴鐠恒劍鏆忛柣銊ュ瑜板憡绗?horizon闁?
	// 闁兼眹鍎辩紞瀣礈瀹ュ嫭宕查柛鏂衡偓鍐茬缂佹棏鍨抽悰銉╁及鎼淬垺鈻旈悘蹇撶箣缁剟宕楅妸銉ф拱 CmaxH闁挎稑鑻銊╂偨閵娿儳鏆婇柛妯侯儎缂?midpoint 闁汇劌瀚ぐ鍝ョ矓娴兼瑧绀?
	// 闂侇剙鐏濋崢?backward sink root 闁?Tmid 閺夆晛娲よぐ鎼佹嚀鐏炵晫鏆氶柛蹇嬪姂閺嗚鲸绋夊鍛瘔闁活亞鍠庨悿鍕冀閸モ晩鍔柕?
	private double pricingHorizon;
	private String midpointStrategyUsed = "default";
	private double midpointReferenceTime = Double.NaN;
	private int midpointColumnSelectedCount;
	private double midpointColumnLastMin = Double.NaN;
	private double midpointColumnLastAvg = Double.NaN;
	private double midpointColumnLastMax = Double.NaN;
	private double midpointColumnHalfMin = Double.NaN;
	private double midpointColumnHalfAvg = Double.NaN;
	private double midpointColumnHalfMax = Double.NaN;
	private int midpointColumnTaskSampleCount;
	private double midpointColumnTaskMin = Double.NaN;
	private double midpointColumnTaskAvg = Double.NaN;
	private double midpointColumnTaskMedian = Double.NaN;
	private double midpointColumnTaskMax = Double.NaN;
	private String midpointProbeSummary = "off";
	private String midpointProbeReferenceSource = "strategy";
	private String midpointProbeFeedbackSummary = "off";
	private boolean midpointProbeLabelsReadyForJoin;
	private long midpointStrategyNanos;
	// 2026-05-22: 鐟滅増鎸告晶鐘碘偓瑙勭煯閻滎垱娼鐐暠 job-level 闁告柣鍔嶉埀?H_j 缂傚倹鎸搁悺銊╁Υ?
	private PiecewiseLinearFunction[] dynamicJobPenaltyByJob;
	private double[] dynamicJobHStart;
	private double[] dynamicJobHEnd;
	private double[] effectiveJobHStart;
	private double[] effectiveJobHEnd;
	private PiecewiseLinearFunction[] dynamicBackwardPenaltyByJob;
	private double[] dynamicBackwardHStartByJob;
	private double[] dynamicBackwardHEndByJob;
	private PiecewiseLinearFunction[] completionForwardPenaltyByJob;
	private PiecewiseLinearFunction[] completionBackwardPenaltyByJob;
	private double dynamicMinHStart;
	private double dynamicMaxHEnd;
	private double earliestSourceCompletion;
	private boolean[] forwardHalfEligibleByJob;
	private boolean[] backwardHalfEligibleByJob;
	private int forwardHalfIneligibleJobCount;
	private int backwardHalfIneligibleJobCount;
	private PiecewiseLinearFunction[] baseForwardHalfPenaltyByJob;
	private PiecewiseLinearFunction[] baseBackwardHalfPenaltyByJob;
	private double baseHalfPenaltyCacheTMid = Double.NaN;
	private double baseHalfPenaltyCacheHorizon = Double.NaN;
	// 2026-05-24: 闁告瑯浜濆﹢渚€寮界涵鍛濋柣鎰扳偓娑氱懍婵炲备鍓濆﹢?cut dual 闁哄啳顔愮槐婕癷_j profitable window 闁归潧绉崇换姘舵偩濞嗗海鐟忛悷娆愬笂缁楀绮垫径濠勭濞撴碍绻冨畵渚€濡?
	private boolean dualProfitableWindowEnabled;

	private long forwardLabelsKept;
	private long forwardLabelsDominated;
	private long backwardLabelsKept;
	private long backwardLabelsDominated;
	private long joinTerminalGroupsScanned;
	private long joinTerminalGroupsArcOrVisitPruned;
	private long joinTerminalGroupsTimePruned;
	private long joinTerminalGroupsCostPruned;
	private long joinCandidateLabelsVisited;
	private long joinCandidateLabelsDominated;
	private long joinPairsTried;
	private long joinPairsSetPruned;
	private long joinPairsLowerBoundPruned;
	private long joinPairsBestBoundPruned;
	private long joinPairsTimePruned;
	private long joinFunctionEvaluations;
	private long joinFunctionPruned;
	private long joinFunctionBestRecordPruned;
	private long joinRangeLowerBoundChecks;
	private long joinRangeLowerBoundPruned;
	private long forwardSinglePointKept;
	private long forwardSinglePointDominatedByStore;
	private long forwardSinglePointDominatedByGraph;
	private long backwardSinglePointKept;
	private long backwardSinglePointDominatedByStore;
	private long backwardSinglePointDominatedByGraph;
	private long generatedCandidateCount;
	private long generatedCandidateDroppedByHeap;
	private long forwardSinkLabelsVisited;
	private long forwardSinkNegativeCandidates;
	private long forwardExtensionCandidates;
	private long forwardExtensionArcPruned;
	private long forwardExtensionInfeasible;
	private long forwardExtensionConstructed;
	private long forwardExtensionBoundSurvivors;
	private long[] forwardLabelsKeptByDepth;
	private long[] forwardSinkNegativeByDepth;
	private long forwardLabelsKeptReachableSum;
	private int forwardLabelsKeptReachableMin;
	private int forwardLabelsKeptReachableMax;
	private long completionForwardLabelsPruned;
	private long completionBackwardLabelsPruned;
	private long completionBoundFunctionEvaluations;
	private long completionBoundScalarChecks;
	private long completionBoundScalarPruned;
	private long completionBoundScalarFunctionFallbacks;
	private long completionBoundScalarUnavailable;
	private long timeIndexedScalarBuildNanos;
	private long timeIndexedScalarImprovedChecks;
	private long timeIndexedScalarExtraPruned;
	private long timeIndexedScalarUnavailable;
	private long timeIndexedWindowTightenedJobs;
	private long timeIndexedWindowReachableJobs;
	private TimeIndexedScalarCompletionBound timeIndexedScalarBound;
	private long completionBoundArcFixingCandidates;
	private long completionBoundArcFixingFixed;
	private long completionBoundArcFixingDomainPruned;
	private long completionBoundArcFixingScalarPruned;
	private long completionBoundArcFixingUnavailable;
	private long completionBoundArcFixingFunctionEvaluations;
	private long completionBoundArcFixingNanos;
	private long completionBoundBuildNanos;
	private long completionBoundForwardBuildNanos;
	private long completionBoundBackwardBuildNanos;
	private long completionBoundAggregateNanos;
	private long completionBoundForwardCandidateAttempts;
	private long completionBoundBackwardCandidateAttempts;
	private long completionBoundForwardQueuePops;
	private long completionBoundBackwardQueuePops;
	private long completionBoundPriorityQueueStalePops;
	private long completionBoundMergeCalls;
	private long completionBoundMergeChanged;
	private long completionBoundForwardSegmentSamples;
	private long completionBoundForwardTargetSegments;
	private long completionBoundForwardCandidateSegments;
	private long completionBoundForwardAfterSegments;
	private int completionBoundForwardMaxTargetSegments;
	private int completionBoundForwardMaxCandidateSegments;
	private int completionBoundForwardMaxAfterSegments;
	private long completionBoundBackwardSegmentSamples;
	private long completionBoundBackwardTargetSegments;
	private long completionBoundBackwardCandidateSegments;
	private long completionBoundBackwardAfterSegments;
	private int completionBoundBackwardMaxTargetSegments;
	private int completionBoundBackwardMaxCandidateSegments;
	private int completionBoundBackwardMaxAfterSegments;
	private double completionBoundLastEvaluationCutoff;
	private boolean completionBoundPreCertificateClosed;
	private int diagnosticForbiddenJobArcCount;
	private int diagnosticPricingOnlyJobArcCount;
	private int diagnosticJobDualPositiveCount;
	private double diagnosticMachineDual;
	private double diagnosticJobDualMin;
	private double diagnosticJobDualMax;
	private double diagnosticJobDualSum;
	private double[] diagnosticJobDualQuantiles;
	private int diagnosticRestrictedColumnCount;
	private int diagnosticIncompatibleRestrictedColumnCount;
	private double diagnosticRestrictedColumnAvgLength;
	private int diagnosticAllowedJobArcDualNonZeroCount;
	private int diagnosticForbiddenJobArcDualNonZeroCount;
	private int diagnosticSinkArcDualNonZeroCount;
	private double diagnosticAllowedJobArcDualMin;
	private double diagnosticAllowedJobArcDualMax;
	private double diagnosticAllowedJobArcDualAbsSum;
	private double diagnosticForbiddenJobArcDualAbsSum;
	private double diagnosticSinkArcDualMin;
	private double diagnosticSinkArcDualMax;
	private double diagnosticSinkArcDualAbsSum;
	private long diagnosticLastHeartbeatNanos;
	private long diagnosticHeartbeatIntervalNanos;
	private long diagnosticForwardPops;
	private long diagnosticBackwardPops;
	private boolean fullMidpointDiagnosticRan;
	private PackedBitSet[] ngNeighborhoodByJob;
	private ArrayList<NonElementaryNegativeRoute> nonElementaryNegativeRoutes;
	private int ngDssrRound;
	private int ngDssrRoundsExecuted;
	private int ngDssrTotalNgSetUpdates;
	private int ngDssrTotalNonElementaryRoutes;
	private int ngDssrTotalNonElementaryNegativeSeen;
	private int ngDssrTotalElementaryColumnsReturned;
	private int ngDssrRoundNonElementaryNegativeSeen;
	private int ngDssrRoundElementaryColumnsReturned;
	private boolean ngDssrTraceNgSetStats;
	private boolean ngDssrTraceNgSetMembers;
	private boolean ngDssrHistoryWarmStartApplied;
	private boolean ngDssrHistoryWarmStartSkippedForRepeatability;
	private boolean ngDssrWindowRepeatabilityFilterApplied;
	private int ngDssrWindowRepeatableJobCount;
	private int ngDssrWindowNonRepeatableJobCount;
	private String ngDssrWindowRepeatabilityMode;
	private boolean[] ngDssrInitialRepeatableMember;
	private StringBuilder ngDssrNgSetStatsByRound;
	private boolean sriPricingEnabled;
	private ArrayList<Integer> sriCutIds;
	private ArrayList<TWETCut> sriCuts;
	private ArrayList<Double> sriDuals;
	private ArrayList<int[]> sriScopes;
	private ArrayList<Integer>[] sriCutsByJob;
	private ArrayList<boolean[]> sriMemoryByCut;
	private ArrayList<boolean[]> sriArcMemoryByCut;
	private boolean limitedMemorySriPricing;
	private CompletionBoundCalculator.Bounds ngDssrReusableCompletionBounds;
	private boolean[][] ngDssrReusableCompletionBoundFixedArc;
	private boolean ngDssrReusablePricingWindowPrecomputeReady;
	private double ngDssrReusablePricingHorizon;
	private double ngDssrReusableDynamicMinHStart;
	private double ngDssrReusableDynamicMaxHEnd;
	private double ngDssrReusableEarliestSourceCompletion;
	private HashSet<SequenceSignature> ngDssrReusableActiveColumnSignatures;
	private final DominanceBackend dominanceBackend;
	private ArrayList<Integer> targetTraceSequence;
	private StringBuilder targetTrace;
	private int targetTraceEventLimit;
	private boolean targetTraceProtectTarget;
	private boolean targetTraceDominatorFollow;
	private HashSet<Integer> targetTraceWatchedLabelIds;

	private String lastMessage = "GCNGBB-style ng-DSSR bidirectional pricing not executed";

	public GCNGBBStyleBidirectionalNgDssr(Data data, TWETBPCConfig config) {
		this(data, config, null);
	}

	public GCNGBBStyleBidirectionalNgDssr(Data data, TWETBPCConfig config,
			HashMap<Integer, MidpointProbeNodeReuse> midpointProbeReuseByNode) {
		this(data, config, midpointProbeReuseByNode, DominanceBackend.PAPER);
	}

	public GCNGBBStyleBidirectionalNgDssr(Data data, TWETBPCConfig config,
			HashMap<Integer, MidpointProbeNodeReuse> midpointProbeReuseByNode, boolean useGraphPartialDominance) {
		this(data, config, midpointProbeReuseByNode,
				useGraphPartialDominance ? DominanceBackend.GRAPH_PARTIAL : DominanceBackend.PAPER);
	}

	public GCNGBBStyleBidirectionalNgDssr(Data data, TWETBPCConfig config,
			HashMap<Integer, MidpointProbeNodeReuse> midpointProbeReuseByNode, DominanceBackend dominanceBackend) {
		this(data, config, midpointProbeReuseByNode, dominanceBackend, null);
	}

	public GCNGBBStyleBidirectionalNgDssr(Data data, TWETBPCConfig config,
			HashMap<Integer, MidpointProbeNodeReuse> midpointProbeReuseByNode, DominanceBackend dominanceBackend,
			NgDssrHistoryWarmStart historyWarmStart) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
		this.midpointProbeReuseByNode = midpointProbeReuseByNode;
		this.dominanceBackend = dominanceBackend == null ? DominanceBackend.PAPER : dominanceBackend;
		this.historyWarmStart = historyWarmStart;
	}

	private void initializeNgNeighborhoods(LP lp) {
		ngNeighborhoodByJob = new PackedBitSet[data.n + 2];
		for (int job = 1; job <= data.n; job++) {
			ngNeighborhoodByJob[job] = new PackedBitSet(data.n + 2);
		}
		prepareInitialRepeatabilityFilter(lp);
		ngDssrHistoryWarmStartApplied = false;
		ngDssrHistoryWarmStartSkippedForRepeatability = ngDssrWindowRepeatabilityFilterApplied;
		// 2026-07-05: repeatability filter uses the current window, while
		// history warm-start is learned across pricing calls. Keep them separate.
		if (!ngDssrHistoryWarmStartSkippedForRepeatability
				&& historyWarmStart != null
				&& historyWarmStart.apply(ngNeighborhoodByJob, config, canUseHistoryWarmStart(lp))) {
			ngDssrHistoryWarmStartApplied = true;
			return;
		}
		String mode = config.ngDssrInitialNgSetMode == null ? "nearestK" : config.ngDssrInitialNgSetMode;
		int targetSize = Math.max(0, config.ngDssrInitialNgSetSize);
		if ("empty".equalsIgnoreCase(mode)) {
			return;
		}
		if ("full".equalsIgnoreCase(mode)) {
			for (int job = 1; job <= data.n; job++) {
				for (int other = 1; other <= data.n; other++) {
					if (other != job && isInitialNgMemberAllowed(other)) {
						ngNeighborhoodByJob[job].add(other);
					}
				}
			}
			return;
		}
		if ("dualPair".equalsIgnoreCase(mode) || "reducedCostPair".equalsIgnoreCase(mode)) {
			addDualPairNgNeighborhoods(lp, targetSize);
			return;
		}
		if ("nearestK".equalsIgnoreCase(mode)) {
			for (int job = 1; job <= data.n; job++) {
				addNearestJobsToNgNeighborhood(job, targetSize);
			}
			return;
		}
		throw new IllegalArgumentException("Unsupported ngDssrInitialNgSetMode: " + mode);
	}

	private boolean canUseHistoryWarmStart(LP lp) {
		if (!isRootNode(lp)) {
			return true;
		}
		// 2026-07-03: root 闁告帗绻傞～鎰交椤撴繂鏁╁娑欘焾椤撶粯绋夊鍥ㄦ殢闁告ê妫楄ぐ鍫曟晬濞戞ê顫?cut 闁告艾娴峰▓?root 閺夆晩鍘洪崬顒勫礂娴ｇ瓔鍟呭璺虹Ф閺併倝鏁嶅畝鍕級闁稿繐绉归崳鍛婂緞瀹ュ拋鍔呭☉鏃傚Х濞村瀵?ng-set闁?
		return config.ngDssrHistoryWarmStartUseRoot || (lp != null && !lp.getActiveCutIds().isEmpty());
	}

	private boolean isRootNode(LP lp) {
		return lp == null || lp.getNode() == null || lp.getNode().depth == 0;
	}

	private void addNearestJobsToNgNeighborhood(final int centerJob, int targetSize) {
		ArrayList<Integer> jobs = new ArrayList<Integer>();
		for (int job = 1; job <= data.n; job++) {
			if (job != centerJob && isInitialNgMemberAllowed(job)) {
				jobs.add(Integer.valueOf(job));
			}
		}
		Collections.sort(jobs, new Comparator<Integer>() {
			@Override
			public int compare(Integer left, Integer right) {
				int byDistance = compareDoubleAsc(ngDistance(centerJob, left.intValue()),
						ngDistance(centerJob, right.intValue()));
				if (byDistance != 0) {
					return byDistance;
				}
				return Integer.compare(left.intValue(), right.intValue());
			}
		});
		for (int i = 0; i < jobs.size() && ngNeighborhoodByJob[centerJob].cardinality() < targetSize; i++) {
			ngNeighborhoodByJob[centerJob].add(jobs.get(i).intValue());
		}
	}

	private double ngDistance(int from, int to) {
		return data.getSetUp(from, to) + data.getSetUp(to, from)
				+ data.getSetupCost(from, to) + data.getSetupCost(to, from);
	}

	private void addDualPairNgNeighborhoods(LP lp, int targetSize) {
		if (targetSize <= 0) {
			return;
		}
		ArrayList<NgPair> pairs = new ArrayList<NgPair>();
		for (int first = 1; first <= data.n; first++) {
			for (int second = first + 1; second <= data.n; second++) {
				double reducedPairCost = data.getSetupCost(first, second) - lp.getArcDual(first, second)
						- lp.getJobDual(second)
						+ data.getSetupCost(second, first) - lp.getArcDual(second, first) - lp.getJobDual(first);
				if (Utility.compareLt(reducedPairCost, REDUCED_COST_TOLERANCE)) {
					pairs.add(new NgPair(first, second, reducedPairCost));
				}
			}
		}
		Collections.sort(pairs, new Comparator<NgPair>() {
			@Override
			public int compare(NgPair left, NgPair right) {
				int byCost = compareDoubleAsc(left.reducedPairCost, right.reducedPairCost);
				if (byCost != 0) {
					return byCost;
				}
				if (left.first != right.first) {
					return Integer.compare(left.first, right.first);
				}
				return Integer.compare(left.second, right.second);
			}
		});
		for (int i = 0; i < pairs.size(); i++) {
			NgPair pair = pairs.get(i);
			if (isInitialNgMemberAllowed(pair.second)
					&& ngNeighborhoodByJob[pair.first].cardinality() < targetSize) {
				ngNeighborhoodByJob[pair.first].add(pair.second);
			}
			if (isInitialNgMemberAllowed(pair.first)
					&& ngNeighborhoodByJob[pair.second].cardinality() < targetSize) {
				ngNeighborhoodByJob[pair.second].add(pair.first);
			}
		}
	}

	private void prepareInitialRepeatabilityFilter(LP lp) {
		ngDssrInitialRepeatableMember = null;
		ngDssrWindowRepeatabilityMode = null;
		if (!config.enableNgDssrWindowRepeatabilityInitialFilter
				|| effectiveJobHStart == null || effectiveJobHEnd == null) {
			return;
		}
		Node node = lp == null ? null : lp.getNode();
		boolean useExactTimeIndexedRepeatability = useExactTimeIndexedRepeatability(node);
		ngDssrWindowRepeatabilityMode = useExactTimeIndexedRepeatability ? "timeIndexed" : "hull";
		ngDssrInitialRepeatableMember = new boolean[data.n + 1];
		int repeatableJobs = 0;
		for (int job = 1; job <= data.n; job++) {
			ngDssrInitialRepeatableMember[job] = canRepeatJobInCurrentEffectiveWindow(
					node, job, useExactTimeIndexedRepeatability);
			if (ngDssrInitialRepeatableMember[job]) {
				repeatableJobs++;
			}
		}
		ngDssrWindowRepeatabilityFilterApplied = true;
		ngDssrWindowRepeatableJobCount = repeatableJobs;
		ngDssrWindowNonRepeatableJobCount = data.n - repeatableJobs;
	}

	private boolean isInitialNgMemberAllowed(int member) {
		return ngDssrInitialRepeatableMember == null
				|| (member >= 1 && member <= data.n && ngDssrInitialRepeatableMember[member]);
	}

	private boolean useExactTimeIndexedRepeatability(Node node) {
		if (!data.isExactIntegerTimeInstance()) {
			return false;
		}
		if (dualProfitableWindowEnabled) {
			return true;
		}
		return node != null && (node.countTimeIndexedPricingWindowTightenedJobs() > 0
				|| node.countTimeIndexedPricingOnlyForbiddenArcs() > 0);
	}

	private boolean canRepeatJobInCurrentEffectiveWindow(Node node, int job,
			boolean useExactTimeIndexedRepeatability) {
		if (PricingCompatibility.isRequiredOutsourcedJob(node, job)) {
			return false;
		}
		double jobStart = effectiveJobHStart[job];
		double jobEnd = effectiveJobHEnd[job];
		if (Utility.compareGt(jobStart, jobEnd)) {
			return false;
		}
		if (useExactTimeIndexedRepeatability) {
			return canRepeatJobByTimeIndexedPoints(node, job, jobStart, jobEnd);
		}
		for (int via = 1; via <= data.n; via++) {
			if (via == job || PricingCompatibility.isRequiredOutsourcedJob(node, via)
					|| isOrdinaryArcUnavailableForRepeatability(node, job, via)
					|| isOrdinaryArcUnavailableForRepeatability(node, via, job)) {
				continue;
			}
			if (canRepeatJobVia(job, via, jobStart, jobEnd)) {
				return true;
			}
		}
		return false;
	}

	private boolean canRepeatJobByTimeIndexedPoints(Node node, int job, double jobStart, double jobEnd) {
		int start = discreteRepeatabilityStart(jobStart);
		int end = discreteRepeatabilityEnd(jobEnd);
		if (start > end) {
			return false;
		}
		for (int via = 1; via <= data.n; via++) {
			if (via == job || PricingCompatibility.isRequiredOutsourcedJob(node, via)
					|| isOrdinaryArcUnavailableForRepeatability(node, job, via)
					|| isOrdinaryArcUnavailableForRepeatability(node, via, job)) {
				continue;
			}
			int firstLeg = discreteRepeatabilityDuration(job, via);
			int secondLeg = discreteRepeatabilityDuration(via, job);
			int firstStart = Math.max(start, Math.max(discreteRepeatabilityStart(effectiveJobHStart[via]) - firstLeg,
					discreteRepeatabilityStart(effectiveJobHStart[job]) - firstLeg - secondLeg));
			int firstEnd = Math.min(end, Math.min(discreteRepeatabilityEnd(effectiveJobHEnd[via]) - firstLeg,
					discreteRepeatabilityEnd(effectiveJobHEnd[job]) - firstLeg - secondLeg));
			for (int firstCompletion = firstStart; firstCompletion <= firstEnd; firstCompletion++) {
				if (!isTimeIndexedRepeatabilityCompletionFeasible(job, firstCompletion)
						|| isTimeIndexedRepeatabilityArcForbidden(node, job, via, firstCompletion)) {
					continue;
				}
				int viaCompletion = firstCompletion + firstLeg;
				if (!isTimeIndexedRepeatabilityCompletionFeasible(via, viaCompletion)
						|| isTimeIndexedRepeatabilityArcForbidden(node, via, job, viaCompletion)) {
					continue;
				}
				int secondCompletion = viaCompletion + secondLeg;
				if (isTimeIndexedRepeatabilityCompletionFeasible(job, secondCompletion)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean canRepeatJobVia(int job, int via, double jobStart, double jobEnd) {
		double viaStart = effectiveJobHStart[via];
		double viaEnd = effectiveJobHEnd[via];
		if (Utility.compareGt(viaStart, viaEnd)) {
			return false;
		}
		double firstLeg = repeatabilityDuration(job, via);
		double secondLeg = repeatabilityDuration(via, job);
		double lower = Math.max(jobStart, Math.max(viaStart - firstLeg, jobStart - firstLeg - secondLeg));
		double upper = Math.min(jobEnd, Math.min(viaEnd - firstLeg, jobEnd - firstLeg - secondLeg));
		return !Utility.compareGt(lower, upper);
	}

	private double repeatabilityDuration(int from, int to) {
		return data.getSetUp(from, to) + data.getProcessT(to);
	}

	private int discreteRepeatabilityDuration(int from, int to) {
		return (int) Math.rint(data.getSetUp(from, to) + data.getProcessT(to));
	}

	private int discreteRepeatabilityStart(double value) {
		return Math.max(0, (int) Math.ceil(value - 1e-9));
	}

	private int discreteRepeatabilityEnd(double value) {
		return Math.min((int) Math.floor(pricingHorizon + 1e-9), (int) Math.floor(value + 1e-9));
	}

	private boolean isTimeIndexedRepeatabilityCompletionFeasible(int job, int completion) {
		if (completion < 0 || completion > pricingHorizon + 1e-9
				|| completion < discreteRepeatabilityStart(effectiveJobHStart[job])
				|| completion > discreteRepeatabilityEnd(effectiveJobHEnd[job])) {
			return false;
		}
		return !Utility.isBigMValue(data.penaltyFunction[job].evaluate(completion));
	}

	private boolean isTimeIndexedRepeatabilityArcForbidden(Node node, int from, int to, int time) {
		return node != null && node.isTimeIndexedPricingOnlyArcForbidden(from, to, time);
	}

	private boolean isOrdinaryArcUnavailableForRepeatability(Node node, int from, int to) {
		return node != null && (node.isArcForbidden(from, to) || node.isPricingOnlyArcForbidden(from, to));
	}

	private int updateNgNeighborhoodsFromNonElementaryRoutes() {
		int changed = 0;
		for (int routeIndex = 0; routeIndex < nonElementaryNegativeRoutes.size(); routeIndex++) {
			ArrayList<Integer> sequence = nonElementaryNegativeRoutes.get(routeIndex).sequence;
			int[] lastPosition = new int[data.n + 1];
			Arrays.fill(lastPosition, -1);
			for (int pos = 0; pos < sequence.size(); pos++) {
				int repeatedJob = sequence.get(pos).intValue();
				if (repeatedJob <= 0 || repeatedJob > data.n) {
					continue;
				}
				int previous = lastPosition[repeatedJob];
				if (previous >= 0) {
					for (int middle = previous + 1; middle < pos; middle++) {
						int middleJob = sequence.get(middle).intValue();
						if (middleJob > 0 && middleJob <= data.n && middleJob != repeatedJob
								&& !ngNeighborhoodByJob[middleJob].contains(repeatedJob)) {
							ngNeighborhoodByJob[middleJob].add(repeatedJob);
							changed++;
						}
					}
				}
				lastPosition[repeatedJob] = pos;
			}
		}
		return changed;
	}

	private void appendNgDssrSummary(String reason) {
		lastMessage = lastMessage + " | ng-DSSR reason=" + reason + ngSetWarmStartSummary()
				+ ngSetWindowRepeatabilitySummary()
				+ ", rounds=" + ngDssrRoundsExecuted
				+ ", totalNonElementarySeen=" + ngDssrTotalNonElementaryNegativeSeen
				+ ", totalNonElementaryStored=" + ngDssrTotalNonElementaryRoutes
				+ ", totalElementaryReturned=" + ngDssrTotalElementaryColumnsReturned
				+ ", totalNgSetUpdates=" + ngDssrTotalNgSetUpdates
				+ ngSetStatsSummary()
				+ ngSetMembersSummary();
	}

	private String ngSetWarmStartSummary() {
		if (!config.enableNgDssrHistoryWarmStart) {
			return "";
		}
		String source = ngDssrHistoryWarmStartSkippedForRepeatability
				? "skippedRepeatability"
				: (ngDssrHistoryWarmStartApplied ? "learned" : "base");
		String history = historyWarmStart == null ? "historyWarmStart=none" : historyWarmStart.summary();
		return ", ngWarmStart=" + source + "/" + history;
	}

	private String ngSetWindowRepeatabilitySummary() {
		if (!config.enableNgDssrWindowRepeatabilityInitialFilter) {
			return "";
		}
		if (!ngDssrWindowRepeatabilityFilterApplied) {
			return ", ngWindowRepeatability=skipped";
		}
		String mode = ngDssrWindowRepeatabilityMode == null ? "unknown" : ngDssrWindowRepeatabilityMode;
		return ", ngWindowRepeatability=" + mode
				+ "/repeatable" + ngDssrWindowRepeatableJobCount
				+ "/nonRepeatable" + ngDssrWindowNonRepeatableJobCount;
	}

	private void recordNgSetHistory() {
		if (ngDssrHistoryWarmStartSkippedForRepeatability) {
			return;
		}
		if (historyWarmStart != null && config.enableNgDssrHistoryWarmStart) {
			historyWarmStart.record(ngNeighborhoodByJob, config);
		}
	}

	public ArrayList<TWETColumn> solve(LP lp) {
		return solve(lp, TimeLimitChecker.NONE);
	}

	public ArrayList<TWETColumn> solve(LP lp, TimeLimitChecker timeLimitChecker) {
		this.timeLimitChecker = timeLimitChecker == null ? TimeLimitChecker.NONE : timeLimitChecker;
		ngNeighborhoodByJob = null;
		ngDssrInitialRepeatableMember = null;
		ngDssrRoundsExecuted = 0;
		ngDssrTotalNgSetUpdates = 0;
		ngDssrTotalNonElementaryRoutes = 0;
		ngDssrTotalNonElementaryNegativeSeen = 0;
		ngDssrTotalElementaryColumnsReturned = 0;
		ngDssrHistoryWarmStartSkippedForRepeatability = false;
		ngDssrWindowRepeatabilityFilterApplied = false;
		ngDssrWindowRepeatableJobCount = 0;
		ngDssrWindowNonRepeatableJobCount = 0;
		ngDssrTraceNgSetStats = Boolean.getBoolean("twet.bpc.ngDssrSetStats")
				|| Boolean.getBoolean("twet.bpc.fullDomainCompare.ngDssrSetStats");
		ngDssrTraceNgSetMembers = Boolean.getBoolean("twet.bpc.ngDssrSetMembers")
				|| Boolean.getBoolean("twet.bpc.fullDomainCompare.ngDssrSetMembers");
		ngDssrNgSetStatsByRound = ngDssrTraceNgSetStats ? new StringBuilder() : null;
		ngDssrReusableCompletionBounds = null;
		ngDssrReusableCompletionBoundFixedArc = null;
		ngDssrReusablePricingWindowPrecomputeReady = false;
		ngDssrReusablePricingHorizon = Double.NaN;
		ngDssrReusableDynamicMinHStart = Double.NaN;
		ngDssrReusableDynamicMaxHEnd = Double.NaN;
		ngDssrReusableEarliestSourceCompletion = Double.NaN;
		ngDssrReusableActiveColumnSignatures = null;

		for (ngDssrRound = 1; !this.timeLimitChecker.isTimeLimitReached(); ngDssrRound++) {
			nonElementaryNegativeRoutes = new ArrayList<NonElementaryNegativeRoute>();
			ngDssrRoundNonElementaryNegativeSeen = 0;
			ngDssrRoundElementaryColumnsReturned = 0;
			ArrayList<TWETColumn> columns = solveRelaxedRound(lp);
			ngDssrRoundsExecuted = ngDssrRound;
			ngDssrRoundElementaryColumnsReturned = columns.size();
			ngDssrTotalElementaryColumnsReturned += ngDssrRoundElementaryColumnsReturned;
			ngDssrTotalNonElementaryNegativeSeen += ngDssrRoundNonElementaryNegativeSeen;
			ngDssrTotalNonElementaryRoutes += nonElementaryNegativeRoutes.size();
			if (!columns.isEmpty()) {
				appendNgSetStatsForRound(0);
				appendNgDssrSummary(config.ngDssrReturnRelaxedColumns
						? "ng-relaxed negative columns returned"
						: "elementary negative columns returned");
				recordNgSetHistory();
				return columns;
			}
			if (nonElementaryNegativeRoutes.isEmpty()) {
				appendNgSetStatsForRound(0);
				appendNgDssrSummary("relaxed pricing found no negative route");
				recordNgSetHistory();
				return columns;
			}
			int changed = updateNgNeighborhoodsFromNonElementaryRoutes();
			ngDssrTotalNgSetUpdates += changed;
			appendNgSetStatsForRound(changed);
			if (changed == 0) {
				throw new IllegalStateException(
						"NG-DSSR found non-elementary negative routes but ng-set did not change");
			}
		}
		appendNgDssrSummary("time limit reached");
		return new ArrayList<TWETColumn>();
	}

	private void appendNgSetStatsForRound(int changed) {
		if (!ngDssrTraceNgSetStats || ngDssrNgSetStatsByRound == null || ngNeighborhoodByJob == null) {
			return;
		}
		int min = Integer.MAX_VALUE;
		int max = 0;
		int total = 0;
		for (int job = 1; job <= data.n; job++) {
			int size = ngNeighborhoodByJob[job] == null ? 0 : ngNeighborhoodByJob[job].cardinality();
			min = Math.min(min, size);
			max = Math.max(max, size);
			total += size;
		}
		double avg = data.n == 0 ? 0.0 : ((double) total) / data.n;
		if (ngDssrNgSetStatsByRound.length() > 0) {
			ngDssrNgSetStatsByRound.append(';');
		}
		ngDssrNgSetStatsByRound.append('r').append(ngDssrRound)
				.append('=').append(String.format("%.3f", avg))
				.append('/').append(min == Integer.MAX_VALUE ? 0 : min)
				.append('/').append(max)
				.append("/u").append(changed)
				.append("/neSeen").append(ngDssrRoundNonElementaryNegativeSeen)
				.append("/neStored").append(nonElementaryNegativeRoutes == null ? 0 : nonElementaryNegativeRoutes.size())
				.append("/elem").append(ngDssrRoundElementaryColumnsReturned);
	}

	private String ngSetStatsSummary() {
		if (!ngDssrTraceNgSetStats || ngDssrNgSetStatsByRound == null
				|| ngDssrNgSetStatsByRound.length() == 0) {
			return "";
		}
		return ", ngSetSize avg/min/max/updateByRound=" + ngDssrNgSetStatsByRound.toString();
	}

	private String ngSetMembersSummary() {
		if (!ngDssrTraceNgSetMembers || ngNeighborhoodByJob == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder(", ngSetMembers=");
		for (int job = 1; job <= data.n; job++) {
			if (job > 1) {
				builder.append('|');
			}
			builder.append(job).append(':');
			PackedBitSet set = ngNeighborhoodByJob[job];
			if (set == null) {
				continue;
			}
			boolean first = true;
			for (int member = set.nextSetBit(1); member >= 1 && member <= data.n; member = set.nextSetBit(member + 1)) {
				if (!first) {
					builder.append('.');
				}
				builder.append(member);
				first = false;
			}
		}
		return builder.toString();
	}

	private ArrayList<TWETColumn> solveRelaxedRound(LP lp) {
		long exactStartNanos = System.nanoTime();
		Utility.resetCurUpperBound(Utility.big_M);
		lastRelaxedRoundBestReducedCost = Double.POSITIVE_INFINITY;
		diagnosticHeartbeat(lp, "initialize.start", true);
		initialize(lp);
		diagnosticHeartbeat(lp, "initialize.done", true);
		if (completionBoundPreCertificateClosed) {
			return generatedColumns;
		}
		if (fullMidpointDiagnosticRan && Boolean.getBoolean("twet.bpc.midpointFullDiagnosticStopAfter")) {
			generatedColumns.clear();
			lastMessage = "GCNGBB-style ng-DSSR bidirectional midpoint full diagnostic executed; exact pricing skipped";
			return generatedColumns;
		}
		if (!midpointProbeLabelsReadyForJoin) {
			initializeBackwardSink(lp);
			diagnosticHeartbeat(lp, "backwardSink.done", true);
		} else {
			diagnosticHeartbeat(lp, "probe.rank0.reuse", true);
		}
		// 2026-05-26: GCNGBB-style 濠㈣埖鐗曢惇鏉棵规担琛℃煠闁靛棗鍊搁崢娑㈠礆閸℃鐒奸柤鐗堫殔閺佹牗绋夐妶鍕珷闂傚啰鍠庨崹顏堟晬鐏炵偓浠橀柛姘捣缁儤绋夐埀顒勫箥椤愶絽浼?backward labels 闁?crossing-arc join闁?
		if (!midpointProbeLabelsReadyForJoin) {
			diagnosticHeartbeat(lp, "forward.start", true);
			while (canContinue() && !FWUL.isEmpty()) {
				forwardExtend(lp);
			}
			diagnosticHeartbeat(lp, "forward.done", true);
			if (!timeLimitChecker.isTimeLimitReached()) {
				diagnosticHeartbeat(lp, "backward.start", true);
				while (canContinue() && !BWUL.isEmpty()) {
					backwardExtend(lp);
				}
				diagnosticHeartbeat(lp, "backward.done", true);
			}
		}
		if (canContinue() && !timeLimitChecker.isTimeLimitReached()) {
			diagnosticHeartbeat(lp, "join.compact.start", true);
			compactAndSortActiveLabelListsForJoin();
			diagnosticHeartbeat(lp, "join.start", true);
			joinAllForwardTerminalGroups(lp);
			diagnosticHeartbeat(lp, "finalize.start", true);
			finalizeGeneratedColumns(lp);
			diagnosticHeartbeat(lp, "finalize.done", true);
		}
		updateMidpointProbeReuseAfterExact(lp, System.nanoTime() - exactStartNanos);
		String completionState = timeLimitChecker.isTimeLimitReached() ? "time limit reached"
				: (midpointProbeLabelsReadyForJoin ? "probe rank0 queues exhausted"
						: (canContinue() ? "queues exhausted" : "column cap disabled"));
		lastMessage = "GCNGBB-style ng-DSSR bidirectional no-cut labeling generated " + generatedColumns.size() + " columns ("
				+ completionState + "); " + statisticsSummary();
		return generatedColumns;
	}

	public String getLastMessage() {
		return lastMessage;
	}

	public double getLastRelaxedRoundBestReducedCost() {
		if (Double.isInfinite(lastRelaxedRoundBestReducedCost)) {
			return Double.NaN;
		}
		return lastRelaxedRoundBestReducedCost;
	}

	CompletionBoundSubtreeArcEliminator.PreparedBounds reusableSubtreeArcEliminationBounds() {
		if (completionBounds == null || completionBoundRelaxation == null || dualProfitableWindowEnabled
				|| zeroDualExcludedJobs != null || !Utility.compareEq(pricingHorizon, data.CmaxH)) {
			return null;
		}
		return new CompletionBoundSubtreeArcEliminator.PreparedBounds(completionBounds, pricingHorizon,
				completionBoundRelaxation, completionBoundQueueOrdering);
	}

	private LabelQueueOrdering parseQueueOrdering(String value) {
		if (value == null) {
			return LabelQueueOrdering.REDUCED_COST;
		}
		String normalized = value.trim().toLowerCase();
		if ("time".equals(normalized)) {
			return LabelQueueOrdering.TIME;
		}
		if ("reachablesize".equals(normalized) || "reachable_size".equals(normalized)
				|| "reachable".equals(normalized)) {
			return LabelQueueOrdering.REACHABLE_SIZE;
		}
		return LabelQueueOrdering.REDUCED_COST;
	}

	private JoinBestThresholdMode parseJoinBestThresholdMode(String value) {
		if (value == null) {
			return JoinBestThresholdMode.ZERO;
		}
		String normalized = value.trim().toLowerCase();
		if ("bestub".equals(normalized) || "best_ub".equals(normalized) || "best-ub".equals(normalized)) {
			return JoinBestThresholdMode.BEST_UB;
		}
		if ("bestrecord".equals(normalized) || "best_record".equals(normalized)
				|| "best-record".equals(normalized) || "record".equals(normalized)) {
			return JoinBestThresholdMode.BEST_RECORD;
		}
		return JoinBestThresholdMode.ZERO;
	}

	private CompletionBoundCalculator.Relaxation parseCompletionBoundRelaxation(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim().toLowerCase();
		if ("allcycles".equals(normalized) || "all_cycles".equals(normalized)
				|| "all-cycles".equals(normalized) || "all".equals(normalized)) {
			return CompletionBoundCalculator.Relaxation.ALL_CYCLES;
		}
		if ("twocycle".equals(normalized) || "two_cycle".equals(normalized)
				|| "two-cycle".equals(normalized) || "2cycle".equals(normalized)
				|| "2-cycle".equals(normalized)) {
			return CompletionBoundCalculator.Relaxation.TWO_CYCLE;
		}
		return null;
	}

	private CompletionBoundCalculator.QueueOrdering parseCompletionBoundQueueOrdering(String value) {
		if (value == null) {
			return CompletionBoundCalculator.QueueOrdering.FIFO;
		}
		String normalized = value.trim().toLowerCase();
		if ("reducedcost".equals(normalized) || "reduced_cost".equals(normalized)
				|| "reduced-cost".equals(normalized) || "rc".equals(normalized)) {
			return CompletionBoundCalculator.QueueOrdering.REDUCED_COST;
		}
		return CompletionBoundCalculator.QueueOrdering.FIFO;
	}

	/**
	 * 2026-05-26: 闁衡偓椤栨稑鐦☉鎾崇Т閹?label 闁告垶妞藉Σ锔剧驳閺嶎偅娈ｉ柨娑樺缁岃埖绂嶆惔銏㈡Х閺夊牆鍟犻埀顒佺矆缂?reduced cost 濞村吋锚閸樻盯鍨惧┑鍛憿闁炽儲绮嶅ú鍧楀矗椤栨繂鍘撮悶姘煎亜閹绱掗鐔告殰闂佹澘绉跺▓?
	 * label 闁规亽鍔岄幃妤呭箥閳轰胶娼旈柍銉︾箑缁狅綁姊荤€靛憡鐣遍柛娆愮墳閸ㄦ濡?
	 */
	private Comparator<ForwardLabel> forwardQueueComparator(LabelQueueOrdering ordering) {
		return new Comparator<ForwardLabel>() {
			@Override
			public int compare(ForwardLabel left, ForwardLabel right) {
				if (ordering == LabelQueueOrdering.TIME) {
					int byTime = compareDoubleAsc(earliestForwardCompletion(left), earliestForwardCompletion(right));
					if (byTime != 0) {
						return byTime;
					}
					int byReachable = compareReachableCardinalityDesc(left, right);
					return byReachable != 0 ? byReachable : compareReducedCost(left, right);
				}
				if (ordering == LabelQueueOrdering.REACHABLE_SIZE) {
					int byReachable = compareReachableCardinalityDesc(left, right);
					return byReachable != 0 ? byReachable : compareReducedCost(left, right);
				}
				return compareReducedCost(left, right);
			}
		};
	}

	private Comparator<BackwardLabel> backwardQueueComparator(LabelQueueOrdering ordering) {
		return new Comparator<BackwardLabel>() {
			@Override
			public int compare(BackwardLabel left, BackwardLabel right) {
				if (ordering == LabelQueueOrdering.TIME) {
					int byTime = compareDoubleDesc(latestBackwardCompletion(left), latestBackwardCompletion(right));
					if (byTime != 0) {
						return byTime;
					}
					int byReachable = compareReachableCardinalityDesc(left, right);
					return byReachable != 0 ? byReachable : compareReducedCost(left, right);
				}
				if (ordering == LabelQueueOrdering.REACHABLE_SIZE) {
					int byReachable = compareReachableCardinalityDesc(left, right);
					return byReachable != 0 ? byReachable : compareReducedCost(left, right);
				}
				return compareReducedCost(left, right);
			}
		};
	}

	private static int compareReducedCost(FunctionLabel left, FunctionLabel right) {
		int byCost = compareDoubleAsc(left.minReducedCost, right.minReducedCost);
		if (byCost != 0) {
			return byCost;
		}
		int byJob = Integer.compare(left.jid, right.jid);
		return byJob != 0 ? byJob : Integer.compare(left.labelId, right.labelId);
	}

	private static Comparator<PricingColumnCandidate> candidateWorstFirstComparator() {
		return new Comparator<PricingColumnCandidate>() {
			@Override
			public int compare(PricingColumnCandidate left, PricingColumnCandidate right) {
				return -compareCandidateBestFirst(left, right);
			}
		};
	}

	private static Comparator<PricingColumnCandidate> candidateBestFirstComparator() {
		return new Comparator<PricingColumnCandidate>() {
			@Override
			public int compare(PricingColumnCandidate left, PricingColumnCandidate right) {
				return compareCandidateBestFirst(left, right);
			}
		};
	}

	private static int compareCandidateBestFirst(PricingColumnCandidate left, PricingColumnCandidate right) {
		int byCost = compareDoubleAsc(left.reducedCost, right.reducedCost);
		if (byCost != 0) {
			return byCost;
		}
		return Integer.compare(left.candidateId, right.candidateId);
	}

	private static int compareReachableCardinalityDesc(FunctionLabel left, FunctionLabel right) {
		return Integer.compare(right.extensionCardinality, left.extensionCardinality);
	}

	private static int compareDoubleAsc(double left, double right) {
		return Double.compare(left, right);
	}

	private static int compareDoubleDesc(double left, double right) {
		return compareDoubleAsc(right, left);
	}

	private static double earliestForwardCompletion(ForwardLabel label) {
		return label.frontier == null || label.frontier.head == null ? Utility.big_M : label.frontier.head.start;
	}

	private static double latestBackwardCompletion(BackwardLabel label) {
		return label.frontier == null || label.frontier.tail == null ? -Utility.big_M : label.frontier.tail.end;
	}

	private void initialize(LP lp) {
		resetStatistics();
		initializeTargetTrace(lp);
		installPartialListTrimTrace();
		setDominanceDiagnosticContext(pricingDiagnosticContext(lp));
		resetDominanceStatistics();
		pricingHorizon = data.CmaxH;
		tMid = Math.min(data.CmaxH * 0.5, pricingHorizon);
		queueOrdering = parseQueueOrdering(config.bidirectionalLabelQueueOrdering);
		joinBestThresholdMode = parseJoinBestThresholdMode(config.bidirectionalJoinBestThresholdMode);
		completionBoundRelaxation = parseCompletionBoundRelaxation(config.bidirectionalCompletionBoundRelaxation);
		completionBoundQueueOrdering = parseCompletionBoundQueueOrdering(
				config.bidirectionalCompletionBoundQueueOrdering);
		completionBounds = ngDssrReusableCompletionBounds;
		completionBoundFixedArc = ngDssrReusableCompletionBoundFixedArc;
		bestGeneratedReducedCost = Utility.big_M;
		generatedColumns = new ArrayList<TWETColumn>();
		if (config.diagnosticPricingSummaryDetails) {
			recordPricingDiagnostics(lp);
		}
		maybeDumpPricingSnapshot(lp);
		precomputeSriPricing(lp);
		precomputeDynamicPricingWindows(lp);
		if (ngNeighborhoodByJob == null) {
			initializeNgNeighborhoods(lp);
		}
		if (completionBounds == null) {
			buildCompletionBounds(lp);
		}
		if (ngDssrReusableCompletionBounds == null && completionBounds != null) {
			ngDssrReusableCompletionBounds = completionBounds;
			ngDssrReusableCompletionBoundFixedArc = completionBoundFixedArc;
		}
		if (tryApplyCompletionBoundPreCertificate(lp)) {
			return;
		}
		runMidpointProbeIfEnabled(lp);
		if (midpointProbeLabelsReadyForJoin) {
			// 2026-06-08: 閻炴凹鍋婇埀顒€顦懙鎴︽儍?rank0 probe 鐎规瓕灏欑划锟犳嚀濡も偓閺佹牗绋夐妶鍕珷 label 闂傚啰鍠庨崹顏堟晬鐏炶棄璁插ù鐘劤濞插潡骞掗妷銊х闁?join闁?
			// 閺夆晜鐟╅崳鐑藉矗椤忓泚澶婎潰閿濆懐纭€闁稿﹥鐟╅埀顒€顦崹顏堝储婵犳艾娅?闁割偄妫涙慨鎼佸箑娓氬﹦绀夐梺顒€鐏濋崢銈夊触鐏炶偐顏卞☉?Tmid 闁告劕绉风粣鍥ㄧ▔閳ь剟鏌?labeling闁?
			initializeCandidateState(lp);
		} else {
			initializeSearchState(lp);
			initializeForwardSource(lp);
		}
		runFullMidpointDiagnosticIfEnabled(lp);
	}

	/**
	 * 2026-06-12: 濞寸姴鎳愰弫銈嗙鎼达紕鏆板ù锝呯Т閹捇鎮╅懜纰樺亾?partial 婵犳洖绻愰崹顏堝Υ閸屾粍绐楅柡宥呮搐缁參宕氬Δ鍛亾濮樺磭绠栫紒顖濆吹缁櫣浠﹂悙绮瑰亾瑜岀槐鍫曞礂閵夘垳绀夊娑欘焾椤撳宕楅幎鑺ワ紨闁?
	 * trace 闁告瑯浜滈崯鎾诲礂?lastMessage闁挎稑濂旂粭澶愬绩閻熸澘缍?label闁靛棔榫歰minance 闁瑰瓨鐗曢埀顒佺懇閳ь剙顦崹顏堟焻閺勫繒甯嗛柕?
	 */
	private void initializeTargetTrace(LP lp) {
		targetTraceSequence = null;
		targetTrace = null;
		targetTraceProtectTarget = false;
		targetTraceDominatorFollow = false;
		targetTraceWatchedLabelIds = null;
		String raw = System.getProperty("twet.bpc.ngDssrTraceSequence",
				System.getProperty("twet.bpc.fullDomainCompare.ngDssrTraceSequence", "")).trim();
		if (raw.isEmpty()) {
			return;
		}
		int targetNode = Integer.getInteger("twet.bpc.ngDssrTraceNode",
				Integer.getInteger("twet.bpc.fullDomainCompare.ngDssrTraceNode", -1));
		if (targetNode >= 0 && (lp == null || lp.getNode() == null || lp.getNode().id != targetNode)) {
			return;
		}
		ArrayList<Integer> sequence = parseTraceSequence(raw);
		if (sequence.isEmpty()) {
			return;
		}
		targetTraceSequence = sequence;
		targetTrace = new StringBuilder();
		targetTraceEventLimit = Integer.getInteger("twet.bpc.ngDssrTraceLimit",
				Integer.getInteger("twet.bpc.fullDomainCompare.ngDssrTraceLimit", 120));
		targetTraceProtectTarget = Boolean.parseBoolean(System.getProperty("twet.bpc.ngDssrTraceProtectTarget",
				System.getProperty("twet.bpc.fullDomainCompare.ngDssrTraceProtectTarget", "false")));
		targetTraceDominatorFollow = Boolean.parseBoolean(System.getProperty("twet.bpc.ngDssrTraceDominator",
				System.getProperty("twet.bpc.fullDomainCompare.ngDssrTraceDominator", "false")));
		targetTraceWatchedLabelIds = new HashSet<Integer>();
		traceTarget("init backend=" + dominanceBackend + " node="
				+ (lp == null || lp.getNode() == null ? -1 : lp.getNode().id)
				+ " protect=" + targetTraceProtectTarget
				+ " followDominator=" + targetTraceDominatorFollow
				+ " target=" + targetTraceSequence);
	}

	private void installPartialListTrimTrace() {
		if (dominanceBackend != DominanceBackend.LIST_PARTIAL || targetTraceSequence == null) {
			PartialListDominanceStore.setTrimListener(null);
			return;
		}
		PartialListDominanceStore.setTrimListener(new PartialListDominanceStore.TrimListener() {
			@Override
			public boolean skipTrim(Label trimmed, Label dominator, Direction direction) {
				return shouldProtectTargetTrim(trimmed, dominator, direction);
			}

			@Override
			public void onTrim(Label trimmed, Label dominator, TrimResult result, Direction direction) {
				traceTargetPartialListTrim(trimmed, dominator, result, direction);
			}
		});
	}

	private ArrayList<Integer> parseTraceSequence(String raw) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		String cleaned = raw.replace("[", "").replace("]", "").replace(";", ",");
		for (String token : cleaned.split(",")) {
			String trimmed = token.trim();
			if (!trimmed.isEmpty()) {
				sequence.add(Integer.valueOf(Integer.parseInt(trimmed)));
			}
		}
		return sequence;
	}

	private void traceTarget(String message) {
		if (targetTrace == null || targetTraceEventLimit <= 0) {
			return;
		}
		targetTraceEventLimit--;
		if (targetTrace.length() > 0) {
			targetTrace.append(" || ");
		}
		targetTrace.append(message);
	}

	private void runFullMidpointDiagnosticIfEnabled(LP lp) {
		int targetNodeId = Integer.getInteger("twet.bpc.midpointFullDiagnosticNodeId", -1);
		if (targetNodeId < 0 || lp.getNode() == null || lp.getNode().id != targetNodeId) {
			return;
		}
		String tmidList = System.getProperty("twet.bpc.midpointFullDiagnosticTMids", "").trim();
		if (tmidList.isEmpty()) {
			return;
		}
		if (!FULL_MIDPOINT_DIAGNOSTIC_DONE.add(Integer.valueOf(lp.getNode().id))) {
			return;
		}
		midpointProbeLabelsReadyForJoin = false;
		double originalTMid = tMid;
		String originalProbeSummary = midpointProbeSummary;
		double forwardSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.midpointFullDiagnosticForwardSeconds", "180.0"));
		double backwardSeconds = Double.parseDouble(System.getProperty(
				"twet.bpc.midpointFullDiagnosticBackwardSeconds", "120.0"));
		System.out.println("[midpointFullDiagnostic] node=" + lp.getNode().id
				+ " pricingHorizon=" + pricingHorizon
				+ " originalTmid=" + originalTMid
				+ " forwardSeconds=" + forwardSeconds
				+ " backwardSeconds=" + backwardSeconds
				+ " tmids=" + tmidList);
		System.out.flush();
		for (String token : tmidList.split(",")) {
			String trimmed = token.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			double candidate = clampCurrentMidpoint(Double.parseDouble(trimmed));
			runFullMidpointDiagnosticCandidate(lp, candidate, forwardSeconds, backwardSeconds);
		}
		fullMidpointDiagnosticRan = true;
		tMid = originalTMid;
		midpointProbeSummary = originalProbeSummary;
		rebuildHalfDomainForCurrentMidpoint();
		resetProbeAffectedStatistics();
		initializeSearchState(lp);
		initializeForwardSource(lp);
		midpointProbeLabelsReadyForJoin = false;
	}

	private void runFullMidpointDiagnosticCandidate(LP lp, double candidateTMid, double forwardSeconds,
			double backwardSeconds) {
		tMid = candidateTMid;
		rebuildHalfDomainForCurrentMidpoint();
		resetProbeAffectedStatistics();
		initializeSearchState(lp);
		initializeForwardSource(lp);
		initializeBackwardSink(lp);
		long start = System.nanoTime();
		long forwardDeadline = deadlineNanos(start, forwardSeconds);
		while (canContinue() && !FWUL.isEmpty() && !timeReached(forwardDeadline)) {
			forwardExtend(lp);
		}
		long forwardElapsed = System.nanoTime() - start;
		long forwardKept = forwardLabelsKept;
		long forwardQueue = queueSize(FWUL);
		boolean forwardExhausted = FWUL.isEmpty();

		long backwardStart = System.nanoTime();
		long backwardDeadline = deadlineNanos(backwardStart, backwardSeconds);
		while (canContinue() && !BWUL.isEmpty() && !timeReached(backwardDeadline)) {
			backwardExtend(lp);
		}
		long backwardElapsed = System.nanoTime() - backwardStart;
		long backwardKept = backwardLabelsKept;
		long backwardQueue = queueSize(BWUL);
		boolean backwardExhausted = BWUL.isEmpty();
		System.out.println("[midpointFullDiagnostic] node=" + lp.getNode().id
				+ " tMid=" + candidateTMid
				+ " fwElapsedMs=" + formatMillis(forwardElapsed)
				+ " bwElapsedMs=" + formatMillis(backwardElapsed)
				+ " fwExhausted=" + forwardExhausted
				+ " bwExhausted=" + backwardExhausted
				+ " fwKept=" + forwardKept
				+ " bwKept=" + backwardKept
				+ " fwQueue=" + forwardQueue
				+ " bwQueue=" + backwardQueue
				+ " keptQueueRatio=" + directionalRatio(forwardKept + forwardQueue, backwardKept + backwardQueue)
				+ " queueOnlyRatio=" + directionalRatio(forwardQueue, backwardQueue)
				+ " keptRatio=" + directionalRatio(forwardKept, backwardKept)
				+ " fwPops=" + diagnosticForwardPops
				+ " bwPops=" + diagnosticBackwardPops
				+ " fCand=" + forwardExtensionCandidates
				+ " fBuilt=" + forwardExtensionConstructed
				+ " fBoundSurvivors=" + forwardExtensionBoundSurvivors
				+ " cbFPruned=" + completionForwardLabelsPruned
				+ " cbBPruned=" + completionBackwardLabelsPruned);
		System.out.flush();
	}

	private long deadlineNanos(long start, double seconds) {
		if (!Double.isFinite(seconds) || !Utility.compareGt(seconds, 0.0)) {
			return start;
		}
		return start + (long) (seconds * 1_000_000_000.0);
	}

	private boolean timeReached(long deadlineNanos) {
		return System.nanoTime() >= deadlineNanos;
	}

	private String directionalRatio(long forward, long backward) {
		return forward + ":" + backward + "(" + ((double) forward + 1.0) / ((double) backward + 1.0) + ")";
	}

	@SuppressWarnings("unchecked")
	private void precomputeSriPricing(LP lp) {
		sriPricingEnabled = false;
		limitedMemorySriPricing = false;
		sriCutIds = new ArrayList<Integer>();
		sriCuts = new ArrayList<TWETCut>();
		sriDuals = new ArrayList<Double>();
		sriScopes = new ArrayList<int[]>();
		sriMemoryByCut = new ArrayList<boolean[]>();
		sriArcMemoryByCut = new ArrayList<boolean[]>();
		sriCutsByJob = new ArrayList[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			sriCutsByJob[job] = new ArrayList<Integer>();
		}
		if (!config.enableSubsetRowCutsForPartialDominance || dominanceBackend != DominanceBackend.LIST_PARTIAL) {
			return;
		}
		List<Integer> cutIds = lp.getActiveSubsetRowPricingCutIds();
		List<Double> duals = lp.getActiveSubsetRowPricingDuals();
		int arcTableSize = (data.n + 2) * (data.n + 2);
		for (int idx = 0; idx < cutIds.size(); idx++) {
			TWETCut cut = lp.getCutPool().getCut(cutIds.get(idx).intValue());
			if (cut.getScopeJobs().size() != 3) {
				continue;
			}
			int activeIndex = sriCutIds.size();
			int[] scope = new int[3];
			for (int pos = 0; pos < 3; pos++) {
				scope[pos] = cut.getScopeJobs().get(pos).intValue();
				if (scope[pos] >= 1 && scope[pos] <= data.n) {
					sriCutsByJob[scope[pos]].add(Integer.valueOf(activeIndex));
				}
			}
			boolean[] memory = new boolean[data.n + 1];
			boolean[] arcMemory = new boolean[arcTableSize];
			if (cut.hasMemoryArcs()) {
				for (Long encoded : cut.getMemoryArcs()) {
					long key = encoded.longValue();
					int from = (int) (key >> 32);
					int to = (int) key;
					if (from >= 0 && from <= data.n + 1 && to >= 0 && to <= data.n + 1) {
						arcMemory[sriArcMemoryIndex(from, to)] = true;
					}
				}
				limitedMemorySriPricing = true;
			} else if (cut.hasMemoryJobs()) {
				for (int job : cut.getMemoryJobs()) {
					if (job >= 1 && job <= data.n) {
						memory[job] = true;
					}
				}
				limitedMemorySriPricing = true;
			} else {
				Arrays.fill(memory, true);
			}
			sriCutIds.add(cutIds.get(idx));
			sriCuts.add(cut);
			sriDuals.add(duals.get(idx));
			sriScopes.add(scope);
			sriMemoryByCut.add(memory);
			sriArcMemoryByCut.add(arcMemory);
		}
		sriPricingEnabled = !sriCutIds.isEmpty();
	}

	private int sriArcMemoryIndex(int from, int to) {
		return from * (data.n + 2) + to;
	}

	private boolean isSriMemoryArc(int sriIndex, int from, int to) {
		if (from < 0 || from > data.n + 1 || to < 0 || to > data.n + 1) {
			return false;
		}
		return sriArcMemoryByCut.get(sriIndex)[sriArcMemoryIndex(from, to)];
	}

	private byte[] emptySriCounts() {
		return sriPricingEnabled ? new byte[sriCutIds.size()] : new byte[0];
	}

	private byte[] copySriCounts(byte[] counts) {
		return counts == null || counts.length == 0 ? new byte[0] : counts.clone();
	}

	/**
	 * limited-memory SRI 濞?forward label 闁?state 閻炴稏鍔庨妵姘?source 闁规鍋勯崺宀冦亹閹惧啿顤呴柣鎰嚀閹鎯冮崟顐⑩挅濞?half-state闁?
	 * node-memory 闁汇劌瀚板?memory job 濞村吋纰嶇粩濠氭⒖閺堢數鐟☉鎾崇Х椤撴悂宕楅妷顖滃耿arc-memory 闁汇劌瀚板?memory arc 闁告瑯浜濈粩濠氭⒖鐠佸湱绀?
	 * 鐟滅増鎸告晶?head job 闁兼眹鍎遍惈妯荤?scope 濞寸姴绉崇紞鏃€绋夐悜妯荤厐婵炲牅绲婚幑锝夋倷绾拋鍚€闁稿繈鍎埀?
	 */
	private double applySriForwardExtensionShift(byte[] states, PackedBitSet visitedBeforeExtension, int from, int job) {
		if (!sriPricingEnabled || job <= 0 || job > data.n) {
			return 0.0;
		}
		double shift = 0.0;
		if (limitedMemorySriPricing) {
			for (int sriIndex = 0; sriIndex < sriCutIds.size(); sriIndex++) {
				TWETCut cut = sriCuts.get(sriIndex);
				if (cut.hasMemoryArcs()) {
					if (!isSriMemoryArc(sriIndex, from, job)) {
						states[sriIndex] = 0;
					}
				} else if (!sriMemoryByCut.get(sriIndex)[job]) {
					states[sriIndex] = 0;
				}
			}
			for (int sriIndex : sriCutsByJob[job]) {
				TWETCut cut = sriCuts.get(sriIndex);
				if (!cut.hasMemoryArcs() && !sriMemoryByCut.get(sriIndex)[job]) {
					continue;
				}
				int next = states[sriIndex] + 1;
				if (next >= 2) {
					shift -= sriDuals.get(sriIndex).doubleValue();
					next -= 2;
				}
				states[sriIndex] = (byte) next;
			}
		} else {
			boolean firstVisit = !visitedBeforeExtension.contains(job);
			for (int sriIndex : sriCutsByJob[job]) {
				if (firstVisit && states[sriIndex] < 2) {
					int next = states[sriIndex] + 1;
					if (states[sriIndex] == 1 && next == 2) {
						shift -= sriDuals.get(sriIndex).doubleValue();
					}
					states[sriIndex] = (byte) next;
				}
			}
		}
		return shift;
	}

	/**
	 * limited-memory SRI 濞?backward label 闁?state 閻炴稏鍔庨妵姘跺箰婢跺﹤鍐€闁告碍鍨舵晶璺ㄤ沪閺囶潿鈧孩鎯旇箛鏂款棁闁?suffix 闁告艾娴峰▓鎴﹀礈閳衡偓缂?half-state闁?
	 * prepend 濞戞挴鍋撳☉?job 闁哄啳顔愮槐婕琽de-memory 闁圭顦伴弻?job 闁告帇鍊栭弻鍥晬濞屾Μc-memory 闁圭顦伴弻濠囧箥閳轰胶娼旂€?(job,to) 闁告帇鍊栭弻鍥及椤栨碍鍎婄€点倕澧庨悽濠氬籍?state闁?
	 * arc 濞戞挸绉村﹢?memory 濞戞搩鍘艰ぐ褔寮鐐电；闁?state闁挎稑濂旂粭澶屾崉鐎圭姷绠栫憸鐗堟尭婢?job 闁汇劌瀚弻濠傗枔娴ｅ啰顢呴柣姘煎枔閳?
	 */
	private double applySriBackwardPrependShift(byte[] states, PackedBitSet visitedBeforeExtension, int job, int to) {
		if (!sriPricingEnabled || job <= 0 || job > data.n) {
			return 0.0;
		}
		double shift = 0.0;
		if (limitedMemorySriPricing) {
			for (int sriIndex = 0; sriIndex < sriCutIds.size(); sriIndex++) {
				TWETCut cut = sriCuts.get(sriIndex);
				if (cut.hasMemoryArcs()) {
					if (!isSriMemoryArc(sriIndex, job, to)) {
						states[sriIndex] = 0;
					}
				} else if (!sriMemoryByCut.get(sriIndex)[job]) {
					states[sriIndex] = 0;
				}
			}
			for (int sriIndex : sriCutsByJob[job]) {
				TWETCut cut = sriCuts.get(sriIndex);
				if (!cut.hasMemoryArcs() && !sriMemoryByCut.get(sriIndex)[job]) {
					continue;
				}
				int next = states[sriIndex] + 1;
				if (next >= 2) {
					shift -= sriDuals.get(sriIndex).doubleValue();
					next -= 2;
				}
				states[sriIndex] = (byte) next;
			}
		} else {
			boolean firstVisit = !visitedBeforeExtension.contains(job);
			for (int sriIndex : sriCutsByJob[job]) {
				if (firstVisit && states[sriIndex] < 2) {
					int next = states[sriIndex] + 1;
					if (states[sriIndex] == 1 && next == 2) {
						shift -= sriDuals.get(sriIndex).doubleValue();
					}
					states[sriIndex] = (byte) next;
				}
			}
		}
		return shift;
	}

	private void initializeForwardSource(LP lp) {
		PackedBitSet sourceVisited = new PackedBitSet(data.n + 2);
		sourceVisited.add(0);
		addZeroDualExcludedJobs(sourceVisited);
		PiecewiseLinearFunction sourceFrontier = cropToInterval(data.penaltyFunction[0].copy(), 0.0, tMid);
		sourceFrontier.shiftYInPlace(-lp.getMachineDual());
		sourceFrontier.normalize(Direction.FORWARD);
		PackedBitSet sourceNgMemory = new PackedBitSet(data.n + 2);
		PackedBitSet sourceDominanceSet = buildForwardDominanceSet(0, sourceNgMemory, lp.getNode(), sourceFrontier);
		PackedBitSet sourceExtensionSet = buildForwardExtensionSet(sourceDominanceSet, 0, sourceFrontier);
		ForwardLabel source = new ForwardLabel(nextLabelId++, 0, null, sourceVisited,
				sourceDominanceSet, sourceExtensionSet, sourceNgMemory, sourceFrontier,
				sriPricingEnabled ? sourceFrontier.copy() : null,
				emptySriCounts(), 0.0);
		if (insertForward(source, lp) == InsertStatus.STORED_AND_ENQUEUE) {
			FWUL.add(source);
		}
	}

	private void runMidpointProbeIfEnabled(LP lp) {
		midpointProbeLabelsReadyForJoin = false;
		if (!config.bidirectionalMidpointProbe) {
			midpointProbeSummary = "off";
			return;
		}
		double reference = midpointProbeReference(lp);
		if (!Double.isFinite(reference) || !Utility.compareGt(reference, 0.0)) {
			midpointProbeSummary = "skipped:noReference";
			return;
		}
		int popLimit = Math.max(1, config.bidirectionalMidpointProbePopLimit);
		int maxCandidates = midpointProbeMaxCandidatesForCurrentReference();
		double moveRatio = normalizedProbeMoveRatio();
		double earlyStopRatio = normalizedProbeEarlyStopRatio();
		double highImbalanceRatio = normalizedProbeHighImbalanceRatio();
		int extraAfterThreshold = Math.max(0, config.bidirectionalMidpointProbeExtraCandidatesAfterThreshold);
		String scoreMode = config.bidirectionalMidpointProbeScore;
		ArrayList<MidpointProbeResult> results = new ArrayList<MidpointProbeResult>();
		HashSet<String> seen = new HashSet<String>();
		double candidate = clampCurrentMidpoint(reference);
		MidpointProbeResult previous = null;
		MidpointProbeResult currentStateResult = null;
		MidpointProbeResult acceptedRank0 = null;
		int extraCandidatesRemaining = -1;
		String stopReason = "maxCandidates";
		int candidateCount = 0;
		while (true) {
			String key = String.format("%.9f", candidate);
			if (!seen.add(key)) {
				stopReason = "duplicate";
				break;
			}
			MidpointProbeResult result = runMidpointProbeCandidate(lp, candidate, popLimit);
			candidateCount++;
			currentStateResult = result;
			results.add(result);
			if (result.reliabilityRank(scoreMode) == 0) {
				acceptedRank0 = result;
				stopReason = "rank0";
				break;
			}
			if (config.bidirectionalMidpointProbeBracketOnDirectionChange && previous != null
					&& isProbeDirectionReversed(previous, result, scoreMode)) {
				double bracketMidpoint = clampCurrentMidpoint((previous.tMid + result.tMid) * 0.5);
				String bracketKey = String.format("%.9f", bracketMidpoint);
				if (seen.add(bracketKey)) {
					MidpointProbeResult bracketResult = runMidpointProbeCandidate(lp, bracketMidpoint, popLimit);
					currentStateResult = bracketResult;
					results.add(bracketResult);
				}
				stopReason = "bracket";
				break;
			}
			if (candidateCount >= maxCandidates) {
				if (!shouldContinueHighImbalanceProbe(previous, result, scoreMode, highImbalanceRatio)) {
					stopReason = Utility.compareLe(result.score(scoreMode), highImbalanceRatio)
							? "highImbalanceResolved" : "maxCandidates";
					break;
				}
			}
			if (extraCandidatesRemaining > 0) {
				extraCandidatesRemaining--;
				if (extraCandidatesRemaining == 0) {
					stopReason = "thresholdExtra";
					break;
				}
			} else if (extraCandidatesRemaining < 0 && Utility.compareGt(earlyStopRatio, 1.0)
					&& Utility.compareLe(result.score(scoreMode), earlyStopRatio)) {
				extraCandidatesRemaining = extraAfterThreshold;
				if (extraCandidatesRemaining == 0) {
					stopReason = "threshold";
					break;
				}
			}
			previous = result;
			candidate = nextMidpointProbeCandidate(result, candidate, moveRatio);
		}
		MidpointProbeResult best = acceptedRank0 != null ? acceptedRank0
				: selectMidpointProbeResult(results, scoreMode);
		if (best == null) {
			midpointProbeSummary = "skipped:noResult";
			return;
		}
		tMid = best.tMid;
		midpointProbeLabelsReadyForJoin = best == currentStateResult
				&& best.reliabilityRank(scoreMode) == 0;
		if (!midpointProbeLabelsReadyForJoin) {
			rebuildHalfDomainForCurrentMidpoint();
			resetProbeAffectedStatistics();
		}
		midpointProbeSummary = formatMidpointProbeSummary(reference, best, results, stopReason, maxCandidates,
				results.size());
		if (midpointProbeLabelsReadyForJoin) {
			midpointProbeSummary += ", rank0LabelsReused=true";
		}
	}

	private double midpointProbeReference(LP lp) {
		midpointProbeReferenceSource = "strategy";
		if (config.bidirectionalMidpointProbeReuseWithinNode && midpointProbeReuseByNode != null
				&& lp.getNode() != null) {
			MidpointProbeNodeReuse cached = midpointProbeReuseByNode.get(Integer.valueOf(lp.getNode().id));
			if (cached != null && cached.hasBestExact()) {
				midpointProbeReferenceSource = "reuseBestExact";
				return cached.bestExactTmid;
			}
		}
		return tMid;
	}

	private int midpointProbeMaxCandidatesForCurrentReference() {
		int maxCandidates = Math.max(1, config.bidirectionalMidpointProbeMaxCandidates);
		if ("reuseBestExact".equals(midpointProbeReferenceSource)) {
			maxCandidates = Math.min(maxCandidates, Math.max(1, config.bidirectionalMidpointProbeReuseMaxCandidates));
		}
		return maxCandidates;
	}

	private void updateMidpointProbeReuseAfterExact(LP lp, long exactNanos) {
		if (!config.bidirectionalMidpointProbe || !config.bidirectionalMidpointProbeReuseWithinNode
				|| midpointProbeReuseByNode == null || lp == null || lp.getNode() == null || !Double.isFinite(tMid)) {
			midpointProbeFeedbackSummary = "off";
			return;
		}
		MidpointProbeNodeReuse reuse = midpointProbeReuseByNode.get(Integer.valueOf(lp.getNode().id));
		if (reuse == null) {
			reuse = new MidpointProbeNodeReuse();
			midpointProbeReuseByNode.put(Integer.valueOf(lp.getNode().id), reuse);
		}
		double exactMillis = exactNanos / 1_000_000.0;
		double ratio = directionalImbalance(forwardLabelsKept, backwardLabelsKept);
		long labelTotal = forwardLabelsKept + backwardLabelsKept;
		String action = reuse.considerExact(tMid, exactMillis, ratio, labelTotal,
				config.bidirectionalMidpointProbeExactTimeTieTolerance, normalizedExactBalanceImprovementTolerance());
		midpointProbeFeedbackSummary = "exactReuse=" + action + ", exactMs=" + exactMillis + ", ratio=" + ratio
				+ ", labels=" + labelTotal + ", bestT=" + reuse.bestExactTmid + ", bestMs="
				+ reuse.bestExactMillis + ", bestRatio=" + reuse.bestExactRatio + ", bestLabels="
				+ reuse.bestExactLabelTotal;
	}

	private double normalizedProbeMoveRatio() {
		double ratio = config.bidirectionalMidpointProbeMoveRatio;
		if (!Double.isFinite(ratio) || !Utility.compareGt(ratio, 0.0) || !Utility.compareLt(ratio, 0.5)) {
			return 0.10;
		}
		return ratio;
	}

	private double directionalImbalance(long left, long right) {
		double l = (double) left + 1.0;
		double r = (double) right + 1.0;
		return Math.max(l / r, r / l);
	}

	private double normalizedProbeEarlyStopRatio() {
		double ratio = config.bidirectionalMidpointProbeEarlyStopRatio;
		return Double.isFinite(ratio) && Utility.compareGt(ratio, 1.0) ? ratio : 0.0;
	}

	private double normalizedProbeHighImbalanceRatio() {
		double ratio = config.bidirectionalMidpointProbeHighImbalanceRatio;
		return Double.isFinite(ratio) && Utility.compareGt(ratio, 1.0) ? ratio : 10.0;
	}

	private double normalizedExactBalanceImprovementTolerance() {
		double tolerance = config.bidirectionalMidpointProbeExactBalanceImprovementTolerance;
		return Double.isFinite(tolerance) && Utility.compareGe(tolerance, 0.0)
				&& Utility.compareLe(tolerance, 1.0) ? tolerance : 0.30;
	}

	private boolean isProbeDirectionReversed(MidpointProbeResult previous, MidpointProbeResult current, String mode) {
		int previousDirection = previous.pressureDirection(mode);
		int currentDirection = current.pressureDirection(mode);
		return previousDirection != 0 && currentDirection != 0 && previousDirection != currentDirection;
	}

	private boolean shouldContinueHighImbalanceProbe(MidpointProbeResult previous, MidpointProbeResult current,
			String mode, double highImbalanceRatio) {
		if (Utility.compareLe(current.score(mode), highImbalanceRatio)) {
			return false;
		}
		int currentDirection = current.pressureDirection(mode);
		if (currentDirection == 0) {
			return false;
		}
		if (previous == null) {
			return true;
		}
		int previousDirection = previous.pressureDirection(mode);
		return previousDirection == 0 || previousDirection == currentDirection;
	}

	private double nextMidpointProbeCandidate(MidpointProbeResult result, double current, double moveRatio) {
		String mode = normalizeProbeScoreMode(config.bidirectionalMidpointProbeScore);
		double leftPressure = result.leftPressure(mode);
		double rightPressure = result.rightPressure(mode);
		double multiplier = Utility.compareGt(leftPressure, rightPressure) ? (1.0 - moveRatio) : (1.0 + moveRatio);
		return clampCurrentMidpoint(current * multiplier);
	}

	private MidpointProbeResult selectMidpointProbeResult(ArrayList<MidpointProbeResult> results, String scoreMode) {
		MidpointProbeResult best = null;
		for (MidpointProbeResult result : results) {
			if (best == null || compareMidpointProbeResult(result, best, scoreMode) < 0) {
				best = result;
			}
		}
		return best;
	}

	private int compareMidpointProbeResult(MidpointProbeResult a, MidpointProbeResult b, String scoreMode) {
		int reliability = Integer.compare(a.reliabilityRank(scoreMode), b.reliabilityRank(scoreMode));
		if (reliability != 0) {
			return reliability;
		}
		double aPrimaryScore = a.score(scoreMode);
		double bPrimaryScore = b.score(scoreMode);
		int score = compareDouble(aPrimaryScore, bPrimaryScore);
		String tieMode = normalizeProbeTieScoreMode(config.bidirectionalMidpointProbeTieScore);
		if (!"off".equals(tieMode) && isProbePrimaryScoreClose(aPrimaryScore, bPrimaryScore)
				&& isProbeTieScoreComparable(a, b, tieMode)) {
			int tieScore = compareDouble(a.score(tieMode), b.score(tieMode));
			if (tieScore != 0) {
				return tieScore;
			}
		}
		if (score != 0) {
			return score;
		}
		int pressure = Long.compare(a.totalPressure(scoreMode), b.totalPressure(scoreMode));
		if (pressure != 0) {
			return pressure;
		}
		int pops = Integer.compare(a.pops, b.pops);
		if (pops != 0) {
			return pops;
		}
		int elapsed = compareDouble(a.elapsedMillis, b.elapsedMillis);
		if (elapsed != 0) {
			return elapsed;
		}
		return compareDouble(a.tMid, b.tMid);
	}

	private boolean isProbePrimaryScoreClose(double a, double b) {
		double tolerance = config.bidirectionalMidpointProbeTieTolerance;
		return Double.isFinite(tolerance) && Utility.compareGt(tolerance, 0.0)
				&& Utility.compareLe(Math.abs(a - b), tolerance);
	}

	private boolean isProbeTieScoreComparable(MidpointProbeResult a, MidpointProbeResult b, String tieMode) {
		if (!"remaining".equals(tieMode)) {
			return true;
		}
		// remaining 闁告瑯浜濋惁顔芥綇閸愌嗏拡濞戞搩浜濆﹢顓㈡嚀濡も偓閺佹牠宕愬▎鎾亾婢跺本鐣遍柛鎾櫃缂嶆垿姊奸悢宄扮仚闁告ê顑呮慨蹇涙晬濞戞宕插☉鎾亾濞撴皜鍐ㄥ殥缂備礁绻楅埀顒侇殔閺佹牠寮拋鍦0 闂傚啰鍠庨崹顏呭濮樿京澹冮柛褍绻愯ぐ鎻捫掗弮鈧埀顑讲鍋?
		return !a.forwardExhausted && !a.backwardExhausted && !b.forwardExhausted && !b.backwardExhausted;
	}

	private int compareDouble(double a, double b) {
		return Double.compare(a, b);
	}

	private MidpointProbeResult runMidpointProbeCandidate(LP lp, double candidateTMid, int popLimit) {
		long start = System.nanoTime();
		tMid = candidateTMid;
		rebuildHalfDomainForCurrentMidpoint();
		resetProbeAffectedStatistics();
		initializeLabelSearchState();
		initializeForwardSource(lp);
		initializeBackwardSink(lp);
		long fwQueuePeak = queueSize(FWUL);
		long bwQueuePeak = queueSize(BWUL);
		int forwardLimit = (popLimit + 1) / 2;
		int backwardLimit = popLimit / 2;
		int forwardPops = 0;
		int backwardPops = 0;
		// 2026-06-07: probe 闁哄嫷鍨拹鐔哥閸℃妲烽弶鍫濆暕鐞氳鲸绗熻鐢洭宕濆☉宕囩濞戞挸绉电€垫粏銇愰幘鍐差枀闂傚啰鍠庨崹顏呭緞瑜嶉惃顒勫箮閵忕姴绐楀Λ鏉垮閻ｅ濡?
		// 闁告熬绠戦崹顖溾偓纭咁潐濡叉宕欓搹鐟扮疀 sidePop=N:0闁挎稑鑻ぐ褍霉鐎ｎ亜鐓?forward 闁绘牕妫涢崑銏ゆ嚀鐏炲墽姊鹃柡?backward 闁哄秹鏀卞﹢浼村Υ?
		while (forwardPops < forwardLimit && !FWUL.isEmpty()) {
			forwardExtend(lp);
			forwardPops++;
			fwQueuePeak = Math.max(fwQueuePeak, queueSize(FWUL));
		}
		while (backwardPops < backwardLimit && !BWUL.isEmpty()) {
			backwardExtend(lp);
			backwardPops++;
			bwQueuePeak = Math.max(bwQueuePeak, queueSize(BWUL));
		}
		int pops = forwardPops + backwardPops;
		fwQueuePeak = Math.max(fwQueuePeak, queueSize(FWUL));
		bwQueuePeak = Math.max(bwQueuePeak, queueSize(BWUL));
		double elapsedMillis = (System.nanoTime() - start) / 1_000_000.0;
		return new MidpointProbeResult(candidateTMid, elapsedMillis, pops, FWUL.isEmpty(), BWUL.isEmpty(),
				forwardPops, backwardPops, forwardLabelsKept, backwardLabelsKept, forwardExtensionBoundSurvivors,
				completionForwardLabelsPruned, completionBackwardLabelsPruned, queueSize(FWUL), queueSize(BWUL),
				fwQueuePeak, bwQueuePeak);
	}

	private String formatMidpointProbeSummary(double reference, MidpointProbeResult best,
			ArrayList<MidpointProbeResult> results, String stopReason, int maxCandidates, int candidateCount) {
		StringBuilder builder = new StringBuilder();
		builder.append("ref=").append(reference)
				.append("(").append(midpointProbeReferenceSource).append(")")
				.append(", selected=").append(best.tMid)
				.append(", scoreMode=").append(normalizeProbeScoreMode(config.bidirectionalMidpointProbeScore))
				.append(", tieScoreMode=").append(normalizeProbeTieScoreMode(config.bidirectionalMidpointProbeTieScore))
				.append(", tieTolerance=").append(Math.max(0.0, config.bidirectionalMidpointProbeTieTolerance))
				.append(", moveRatio=").append(normalizedProbeMoveRatio())
				.append(", earlyStopRatio=").append(normalizedProbeEarlyStopRatio())
				.append(", extraAfterThreshold=")
				.append(Math.max(0, config.bidirectionalMidpointProbeExtraCandidatesAfterThreshold))
				.append(", highImbalanceRatio=").append(normalizedProbeHighImbalanceRatio())
				.append(", bracket=").append(config.bidirectionalMidpointProbeBracketOnDirectionChange)
				.append(", stop=").append(stopReason)
				.append(", maxCandidates=").append(maxCandidates)
				.append(", candidateCount=").append(candidateCount)
				.append(", candidates=");
		for (int i = 0; i < results.size(); i++) {
			if (i > 0) {
				builder.append('|');
			}
			MidpointProbeResult result = results.get(i);
			builder.append(result.compactSummary(config.bidirectionalMidpointProbeScore));
		}
		return builder.toString();
	}

	private static String normalizeProbeScoreMode(String mode) {
		if (mode == null) {
			return "queue";
		}
		String normalized = mode.trim().toLowerCase();
		if ("kept".equals(normalized) || "queue".equals(normalized) || "bound".equals(normalized)
				|| "peak".equals(normalized) || "remaining".equals(normalized)) {
			return normalized;
		}
		return "queue";
	}

	private static String normalizeProbeTieScoreMode(String mode) {
		if (mode == null) {
			return "off";
		}
		String normalized = mode.trim().toLowerCase();
		if ("off".equals(normalized) || "none".equals(normalized)) {
			return "off";
		}
		return normalizeProbeScoreMode(normalized);
	}

	private void initializeSearchState(LP lp) {
		initializeLabelSearchState();
		initializeCandidateState(lp);
	}

	private void setDominanceDiagnosticContext(String context) {
		if (dominanceBackend == DominanceBackend.GRAPH_PARTIAL) {
			PaperPartialDominanceGraphs.setDiagnosticContext(context);
		} else if (dominanceBackend == DominanceBackend.LIST_PARTIAL) {
			PartialListDominanceStore.setDiagnosticContext(context);
		} else {
			PaperDominanceGraphs.setDiagnosticContext(context);
		}
	}

	private void resetDominanceStatistics() {
		if (dominanceBackend == DominanceBackend.GRAPH_PARTIAL) {
			PaperPartialDominanceGraphs.resetStatistics();
		} else if (dominanceBackend == DominanceBackend.LIST_PARTIAL) {
			PartialListDominanceStore.resetStatistics();
		} else {
			PaperDominanceGraphs.resetStatistics();
		}
	}

	private DominanceStore createDominanceStore(Direction direction) {
		if (dominanceBackend == DominanceBackend.GRAPH_PARTIAL) {
			return PaperPartialDominanceGraphs.create(direction);
		}
		if (dominanceBackend == DominanceBackend.LIST_PARTIAL) {
			if (sriPricingEnabled) {
				return new SriAwarePartialListDominanceStore(direction, sriDuals, sriScopes);
			}
			return new PartialListDominanceStore(direction);
		}
		return PaperDominanceGraphs.create(direction);
	}

	private String dominanceStatisticsSummary() {
		if (dominanceBackend == DominanceBackend.GRAPH_PARTIAL) {
			return PaperPartialDominanceGraphs.statisticsSummary();
		}
		if (dominanceBackend == DominanceBackend.LIST_PARTIAL) {
			return PartialListDominanceStore.statisticsSummary();
		}
		return PaperDominanceGraphs.statisticsSummary();
	}

	private void initializeLabelSearchState() {
		resetDominanceStatistics();
		FWUL = new PriorityQueue<ForwardLabel>(forwardQueueComparator(queueOrdering));
		BWUL = new PriorityQueue<BackwardLabel>(backwardQueueComparator(queueOrdering));
		FWTL = new ArrayList<DominanceStore>(data.n + 1);
		BWTL = new ArrayList<DominanceStore>(data.n + 1);
		activeForwardByLastJob = new ArrayList<ArrayList<ForwardLabel>>(data.n + 1);
		activeBackwardByFirstJob = new ArrayList<ArrayList<BackwardLabel>>(data.n + 1);
		forwardSinglePointByLastJob = new ArrayList<SinglePointStore<ForwardLabel>>(data.n + 1);
		backwardSinglePointByFirstJob = new ArrayList<SinglePointStore<BackwardLabel>>(data.n + 1);
		activeForwardTerminalJobs = new PackedBitSet(data.n + 2);
		minForwardReducedCostByLastJob = new double[data.n + 1];
		minForwardEllByLastJob = new double[data.n + 1];
		for (int i = 0; i <= data.n; i++) {
			FWTL.add(createDominanceStore(Direction.FORWARD));
			BWTL.add(createDominanceStore(Direction.BACKWARD));
			activeForwardByLastJob.add(new ArrayList<ForwardLabel>());
			activeBackwardByFirstJob.add(new ArrayList<BackwardLabel>());
			forwardSinglePointByLastJob.add(new SinglePointStore<ForwardLabel>());
			backwardSinglePointByFirstJob.add(new SinglePointStore<BackwardLabel>());
			minForwardReducedCostByLastJob[i] = Utility.big_M;
			minForwardEllByLastJob[i] = Utility.big_M;
		}
		nextLabelId = 0;
	}

	private void initializeCandidateState(LP lp) {
		generatedColumns = new ArrayList<TWETColumn>();
		generatedColumnCandidates = new PriorityQueue<PricingColumnCandidate>(
				Math.max(1, config.maxExactPricingColumns), candidateWorstFirstComparator());
		generatedCandidateBySignature = new HashMap<SequenceSignature, PricingColumnCandidate>();
		activeColumnSignatures = activeColumnSignaturesForCurrentDssrSolve(lp);
		nextCandidateId = 0;
	}

	private HashSet<SequenceSignature> activeColumnSignaturesForCurrentDssrSolve(LP lp) {
		if (ngDssrReusableActiveColumnSignatures != null) {
			return ngDssrReusableActiveColumnSignatures;
		}
		HashSet<SequenceSignature> signatures = new HashSet<SequenceSignature>();
		// 2026-06-12: 闁告艾濂旂粩鏉戔枎?ng-DSSR pricing 闁?DSSR 濠㈣埖淇洪悿鍡涘矗椤忓懏鏆柛?ng-set闁挎稑顒∕P active 闁告帗顨婂▔锔界▔瀹ュ懎缍侀柕?
		// active signature 闁告瑯浜〒鍓佺箔椤戣法顏遍弶鐑嗗枟婢瑰倿骞?restricted columns闁挎稑鑻幃妤冪磼?round 濠㈣泛绉堕弫銈嗘交濞嗗酣鍤嬮柛娆樹海椤曚即姊块崱妤佸€ら柕?
		for (int columnId : lp.getRestrictedColumnIds()) {
			signatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		ngDssrReusableActiveColumnSignatures = signatures;
		return signatures;
	}

	private String pricingDiagnosticContext(LP lp) {
		Node node = lp == null ? null : lp.getNode();
		return node == null ? "node=-" : node.diagnosticSummary();
	}

	/**
	 * 2026-06-05: 闁圭顦扮€垫氨鈧淇烘俊顓㈡倷绾懏鍎伴柣鈺偯紞瀣礈?exact pricing 閺夊牊鎸搁崣鍡涙晬鐏炶偐鈹掑ù婊冮椤︽煡鎯勫Ο纰辨矗鐎殿啫鍐彂濠㈣埖鐭徊?label 濞寸姴绉堕崹搴ㄦ倷閸濄儲鐣辩紓浣规尰閻庮垶宕㈤悢閿嬬闁?
	 * 濮掓稒顭堥濠氬礂閹惰姤锛旈柨娑欑椤旀洜绱?twet.bpc.pricingSnapshot=true 闁?twet.bpc.pricingSnapshotNodeId=<nodeId> 闁告艾楠搁幆搴ㄦ偨閵婏絺鍋?
	 */
	private void maybeDumpPricingSnapshot(LP lp) {
		Node node = lp == null ? null : lp.getNode();
		if (node == null) {
			return;
		}
		int targetNodeId = Integer.getInteger("twet.bpc.pricingSnapshotNodeId", -1);
		boolean enabled = Boolean.getBoolean("twet.bpc.pricingSnapshot") || targetNodeId >= 0;
		if (!enabled || (targetNodeId >= 0 && node.id != targetNodeId)) {
			return;
		}
		Path dir = Paths.get(System.getProperty("twet.bpc.pricingSnapshotDir",
				"test-results/bpc/pricing-snapshots"));
		String prefix = "pricing-node-" + node.id + "-" + System.currentTimeMillis();
		try {
			Files.createDirectories(dir);
			writePricingSnapshotSummary(lp, dir.resolve(prefix + "-summary.txt"));
			writePricingSnapshotJobDuals(lp, dir.resolve(prefix + "-job-duals.tsv"));
			writePricingSnapshotArcs(lp, dir.resolve(prefix + "-arcs.tsv"));
			writePricingSnapshotColumns(lp, dir.resolve(prefix + "-columns.tsv"));
			System.out.println("[pricingSnapshot] node=" + node.id + " dir=" + dir.toAbsolutePath()
					+ " prefix=" + prefix);
		} catch (IOException ex) {
			System.err.println("[pricingSnapshot] failed for node " + node.id + ": " + ex.getMessage());
		}
	}

	private void writePricingSnapshotSummary(LP lp, Path file) throws IOException {
		Node node = lp.getNode();
		int jobArcAllowed = 0;
		int jobArcForbidden = 0;
		int pricingOnlyForbidden = 0;
		for (int from = 1; from <= data.n; from++) {
			for (int to = 1; to <= data.n; to++) {
				if (from == to) {
					continue;
				}
				if (isPricingArcForbidden(node, from, to)) {
					jobArcForbidden++;
				} else {
					jobArcAllowed++;
				}
				if (node.isPricingOnlyArcForbidden(from, to)) {
					pricingOnlyForbidden++;
				}
			}
		}
		try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			out.write("node	" + node.diagnosticSummary());
			out.newLine();
			out.write("n	" + data.n);
			out.newLine();
			out.write("m	" + data.m);
			out.newLine();
			out.write("CmaxH	" + data.CmaxH);
			out.newLine();
			out.write("restrictedColumns	" + lp.getRestrictedColumnIds().size());
			out.newLine();
			out.write("machineDual	" + lp.getMachineDual());
			out.newLine();
			out.write("jobJobPricingForbidden	" + jobArcForbidden);
			out.newLine();
			out.write("jobJobPricingAllowed	" + jobArcAllowed);
			out.newLine();
			out.write("jobJobPricingOnlyForbidden	" + pricingOnlyForbidden);
			out.newLine();
			out.write("requiredAdjacencyPairs	" + formatPairs(node.getRequiredAdjacencyPairs()));
			out.newLine();
			out.write("forbiddenAdjacencyPairs	" + formatPairs(node.getForbiddenAdjacencyPairs()));
			out.newLine();
		}
	}

	private void writePricingSnapshotJobDuals(LP lp, Path file) throws IOException {
		try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			out.write("job	dual");
			out.newLine();
			for (int job = 1; job <= data.n; job++) {
				out.write(job + "	" + lp.getJobDual(job));
				out.newLine();
			}
		}
	}

	private void writePricingSnapshotArcs(LP lp, Path file) throws IOException {
		Node node = lp.getNode();
		int sink = node.sinkId();
		try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			out.write("from	to	realForbidden	pricingOnlyForbidden	pricingForbidden	arcDual");
			out.newLine();
			for (int from = 0; from <= sink; from++) {
				for (int to = 1; to <= sink; to++) {
					if (from == to) {
						continue;
					}
					boolean realForbidden = node.isArcForbidden(from, to);
					boolean pricingOnly = node.isPricingOnlyArcForbidden(from, to);
					boolean pricingForbidden = isPricingArcForbidden(node, from, to);
					out.write(from + "	" + to + "	" + realForbidden + "	" + pricingOnly + "	"
							+ pricingForbidden + "	" + lp.getArcDual(from, to));
					out.newLine();
				}
			}
		}
	}

	private void writePricingSnapshotColumns(LP lp, Path file) throws IOException {
		Node node = lp.getNode();
		try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			out.write("columnId	cost	length	realForbidden	pricingOnlyForbidden	pricingForbidden	sequence");
			out.newLine();
			for (int columnId : lp.getRestrictedColumnIds()) {
				TWETColumn column = lp.getPool().getColumn(columnId);
				List<Integer> sequence = column.getSequence();
				boolean realForbidden = sequenceUsesRealForbiddenArc(node, sequence);
				boolean pricingOnly = sequenceUsesPricingOnlyForbiddenArc(node, sequence);
				boolean pricingForbidden = sequenceUsesPricingForbiddenArc(node, sequence);
				out.write(columnId + "	" + column.getCost() + "	" + sequence.size() + "	" + realForbidden
						+ "	" + pricingOnly + "	" + pricingForbidden + "	" + formatSequence(sequence));
				out.newLine();
			}
		}
	}

	private String formatPairs(List<int[]> pairs) {
		StringBuilder builder = new StringBuilder();
		for (int[] pair : pairs) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(pair[0]).append('-').append(pair[1]);
		}
		return builder.length() == 0 ? "-" : builder.toString();
	}

	private String formatSequence(List<Integer> sequence) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < sequence.size(); i++) {
			if (i > 0) {
				builder.append(' ');
			}
			builder.append(sequence.get(i).intValue());
		}
		return builder.toString();
	}

	private boolean sequenceUsesRealForbiddenArc(Node node, List<Integer> sequence) {
		return sequenceUsesForbiddenArc(node, sequence, ForbiddenArcMode.REAL);
	}

	private boolean sequenceUsesPricingOnlyForbiddenArc(Node node, List<Integer> sequence) {
		return sequenceUsesForbiddenArc(node, sequence, ForbiddenArcMode.PRICING_ONLY);
	}

	private boolean sequenceUsesPricingForbiddenArc(Node node, List<Integer> sequence) {
		return sequenceUsesForbiddenArc(node, sequence, ForbiddenArcMode.PRICING);
	}

	private boolean sequenceUsesForbiddenArc(Node node, List<Integer> sequence, ForbiddenArcMode mode) {
		if (sequence.isEmpty()) {
			return false;
		}
		if (isForbiddenByMode(node, 0, sequence.get(0).intValue(), mode)) {
			return true;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (isForbiddenByMode(node, sequence.get(i - 1).intValue(), sequence.get(i).intValue(), mode)) {
				return true;
			}
		}
		return isForbiddenByMode(node, sequence.get(sequence.size() - 1).intValue(), node.sinkId(), mode);
	}

	private boolean isForbiddenByMode(Node node, int from, int to, ForbiddenArcMode mode) {
		if (mode == ForbiddenArcMode.REAL) {
			return node.isArcForbidden(from, to);
		}
		if (mode == ForbiddenArcMode.PRICING_ONLY) {
			return node.isPricingOnlyArcForbidden(from, to);
		}
		return isPricingArcForbidden(node, from, to);
	}

	private enum ForbiddenArcMode {
		REAL, PRICING_ONLY, PRICING
	}

	private void initializeBackwardSink(LP lp) {
		PackedBitSet sinkVisited = new PackedBitSet(data.n + 2);
		sinkVisited.add(lp.getNode().sinkId());
		addZeroDualExcludedJobs(sinkVisited);
		PiecewiseLinearFunction sinkFrontier = new PiecewiseLinearFunction();
		// 2026-05-23: backward 闁惧繑纰嶇€氭瑧绱掗崼銏犱化闁哄牜鍓濋棅鈺傜▕閻旀椿娲ｉ悽?[Tmid,pricingHorizon] 闁稿繐鍟弳鐔煎箲椤旇　鍋?
		// 閺夆晜鐟﹂悧閬嶅触鎼达絿鏁鹃柤姹囧劚瑜板倿鎮?shiftX闁挎稑顔搑imToDomain 闁汇劌瀚粩鐔兼偩鐏炶姤瀚查柣妞绘櫇閹﹪宕℃繝鍌滃幍濞戞挴鍋撻柤閿嬬暘閳?
		sinkFrontier.resetDomain(tMid, pricingHorizon);
		sinkFrontier.addSegment(tMid, pricingHorizon, 0.0, 0.0);
		PackedBitSet sinkNgMemory = new PackedBitSet(data.n + 2);
		PackedBitSet sinkDominanceSet = buildBackwardDominanceSet(lp.getNode().sinkId(), sinkNgMemory, lp.getNode(),
				sinkFrontier);
		PackedBitSet sinkExtensionSet = buildBackwardExtensionSet(sinkDominanceSet, lp.getNode().sinkId(), true,
				sinkFrontier);
		BackwardLabel sink = new BackwardLabel(nextLabelId++, lp.getNode().sinkId(), null, sinkVisited,
				sinkDominanceSet, sinkExtensionSet, sinkNgMemory, sinkFrontier,
				sriPricingEnabled ? sinkFrontier.copy() : null, emptySriCounts(),
				0.0, true);
		BWUL.add(sink);
	}

	private boolean canContinue() {
		return config.maxExactPricingColumns > 0;
	}

	private void forwardExtend(LP lp) {
		ForwardLabel label = FWUL.poll();
		if (label.isDominated) {
			return;
		}
		diagnosticForwardPops++;
		traceWatchedLabel("WATCH_F_POP", label);

		Node node = lp.getNode();
		for (int nextJob = label.extensionSet.nextSetBit(1); nextJob > 0 && nextJob <= data.n && canContinue();
				nextJob = label.extensionSet.nextSetBit(nextJob + 1)) {
			forwardExtensionCandidates++;
			if (!canExtendForward(label, nextJob, node)) {
				forwardExtensionArcPruned++;
				continue;
			}
			ForwardLabel child = extendForward(label, nextJob, lp);
			if (child == null || Utility.isBigMValue(child.minReducedCost)) {
				forwardExtensionInfeasible++;
				continue;
			}
			forwardExtensionConstructed++;
			traceTargetForward("F_CONSTRUCT", child, lp);
			traceWatchedChild("WATCH_F_CHILD", label, child, nextJob);
			if (isForwardCompletionBoundPruned(child)) {
				completionForwardLabelsPruned++;
				traceTargetForward("F_CB_PRUNED", child, lp);
				traceWatchedLabel("WATCH_F_CB_PRUNED", child);
				continue;
			}
			forwardExtensionBoundSurvivors++;
			InsertStatus status = insertForward(child, lp);
			traceTargetForward("F_INSERT_" + status, child, lp);
			traceWatchedLabel("WATCH_F_INSERT_" + status, child);
			if (status == InsertStatus.STORED_AND_ENQUEUE) {
				FWUL.add(child);
			}
		}
		diagnosticHeartbeat(lp, "forward.progress", false);
	}

	private void backwardExtend(LP lp) {
		BackwardLabel label = BWUL.poll();
		if (label.isDominated) {
			return;
		}
		diagnosticBackwardPops++;
		traceWatchedLabel("WATCH_B_POP", label);

		Node node = lp.getNode();
		for (int prevJob = label.extensionSet.nextSetBit(1); prevJob > 0 && prevJob <= data.n && canContinue();
				prevJob = label.extensionSet.nextSetBit(prevJob + 1)) {
			if (!canExtendBackward(label, prevJob, node)) {
				continue;
			}
			BackwardLabel child = extendBackward(label, prevJob, lp);
			if (child == null || Utility.isBigMValue(child.minReducedCost)) {
				continue;
			}
			traceTargetBackward("B_CONSTRUCT", child);
			traceWatchedChild("WATCH_B_CHILD", label, child, prevJob);
			if (isBackwardCompletionBoundPruned(child)) {
				completionBackwardLabelsPruned++;
				traceTargetBackward("B_CB_PRUNED", child);
				traceWatchedLabel("WATCH_B_CB_PRUNED", child);
				continue;
			}
			InsertStatus status = insertBackward(child, lp);
			traceTargetBackward("B_INSERT_" + status, child);
			traceWatchedLabel("WATCH_B_INSERT_" + status, child);
			if (status == InsertStatus.STORED_AND_ENQUEUE) {
				BWUL.add(child);
			}
		}
		diagnosticHeartbeat(lp, "backward.progress", false);
	}

	private boolean canExtendForward(ForwardLabel label, int nextJob, Node node) {
		// 2026-06-10: 閻犲鍟伴弫銈夊棘閻熸澘娑ч柡瀣煯婵?extensionSet闁挎稒绋戦悾鐘差啅閼碱剛鐥呴柟鐑樺浮濞?ng-memory 闁告粌鏈鍌炴⒒閺夋垵纾归柛鈺冨枍缁楀宕ｉ婵囧涧闁绘劕绠嶉埀?
		// 闁活亞鍠庨悿?visited 濞戞挸绉堕弫銈嗙?ng-relaxation 闁圭鏅涢惈宥嗘交閸ャ劍濮㈤柨娑樼焸閸ｅ憡寰勫鍕床闁告柡鈧櫕韬柟顓滃灩椤?route 闁告艾绨煎锔剧磼?DSSR 濠㈣泛瀚幃濠囧Υ?
		// 闁烩晝顥愮换娑氱矉娴ｇ袣濞撴碍绻嗙粋鍡氥亹閹惧啿顤?node/pricingOnly 闁绘鍩栭埀顑跨筏缁辨繃绂掑鍛含闁圭鏅涢惈宥夋倷閻熸澘绁柡鍐煐椤ュ懘寮婚妷锝傚亾?
		return !isPricingArcForbidden(node, label.jid, nextJob);
	}

	private boolean canExtendBackward(BackwardLabel label, int prevJob, Node node) {
		int successor = label.isSinkRoot ? node.sinkId() : label.jid;
		// 2026-06-10: backward 闁告艾鏈悧閬嶅矗椤忓懐浜ｅ☉?extensionSet闁挎稒绋撳﹢锛勨偓鍦仱閸ｅ憡寰勫鍥ㄦ殸 DSSR route 闁诡厹鍨归ˇ鏌ュ触鎼粹槅妲遍柣鐐叉閳?
		// 閺夆晜鐟╅崳鐑藉础閾忣偅顦ф俊顐熷亾闁?prevJob -> successor 闁烩晝顥愮换娑橆嚕瑜濈槐婵嬫焼閸喖甯?pricingOnly/闁告帒妫欓弫顔剧矉娴ｇ袣缂備焦娲濈换鍐箥閳轰胶娼旈弶鈺佹处閹躲倝濡?
		return !isPricingArcForbidden(node, prevJob, successor);
	}

	private int previousForwardJob(ForwardLabel label) {
		return label != null && label.father != null ? label.father.jid : 0;
	}

	private int nextBackwardJob(BackwardLabel label, Node node) {
		if (label == null || label.isSinkRoot || label.father == null || label.father.isSinkRoot) {
			return node.sinkId();
		}
		return label.father.jid;
	}

	private ForwardLabel extendForward(ForwardLabel label, int nextJob, LP lp) {
		double delay = data.getSetUp(label.jid, nextJob) + data.getProcessT(nextJob);
		if (!hasForwardExtensionWindowOverlap(label, nextJob, delay)) {
			return null;
		}
		PiecewiseLinearFunction shifted = label.frontier.shiftX(delay);
		if (shifted.head == null) {
			shifted.release();
			return null;
		}

		PiecewiseLinearFunction jobPenalty = getDynamicForwardJobPenalty(label.jid, nextJob);
		if (jobPenalty == null) {
			shifted.release();
			return null;
		}
		PiecewiseLinearFunction nextFrontier = shifted.add(jobPenalty);
		shifted.release();
		if (nextFrontier.head == null) {
			nextFrontier.release();
			return null;
		}
		PiecewiseLinearFunction nextNoSriFrontier = null;
		if (sriPricingEnabled) {
			PiecewiseLinearFunction shiftedNoSri = label.noSriFrontier.shiftX(delay);
			if (shiftedNoSri.head == null) {
				shiftedNoSri.release();
				nextFrontier.release();
				return null;
			}
			nextNoSriFrontier = shiftedNoSri.add(jobPenalty);
			shiftedNoSri.release();
			if (nextNoSriFrontier.head == null) {
				nextNoSriFrontier.release();
				nextFrontier.release();
				return null;
			}
		}
		double fixedReducedCost = data.getSetupCost(label.jid, nextJob) - lp.getJobDual(nextJob)
				- lp.getArcDual(label.jid, nextJob);
		nextFrontier.shiftYInPlace(fixedReducedCost);
		if (nextNoSriFrontier != null) {
			nextNoSriFrontier.shiftYInPlace(fixedReducedCost);
		}
		byte[] childSriCounts;
		double childSriPenalty;
		double sriShift;
		if (sriPricingEnabled) {
			childSriCounts = copySriCounts(label.sriCounts);
			sriShift = applySriForwardExtensionShift(childSriCounts, label.visitedSet, label.jid, nextJob);
			childSriPenalty = label.sriPenalty + sriShift;
		} else {
			childSriCounts = copySriCounts(label.sriCounts);
			sriShift = 0.0;
			childSriPenalty = label.sriPenalty;
		}
		if (!Utility.compareEq(sriShift, 0.0)) {
			nextFrontier.shiftYInPlace(sriShift);
		}
		nextFrontier.normalize(Direction.FORWARD);
		if (nextNoSriFrontier != null) {
			nextNoSriFrontier.normalize(Direction.FORWARD);
		}
		if (nextFrontier.head == null || (nextNoSriFrontier != null && nextNoSriFrontier.head == null)) {
			nextFrontier.release();
			if (nextNoSriFrontier != null) {
				nextNoSriFrontier.release();
			}
			return null;
		}

		PackedBitSet visited = label.visitedSet.copy();
		visited.add(nextJob);
		PackedBitSet childNgMemory = updateNgMemory(label.ngMemorySet, nextJob);
		PackedBitSet childDominanceSet = buildForwardDominanceSet(nextJob, childNgMemory, lp.getNode(), nextFrontier);
		PackedBitSet childExtensionSet = buildForwardExtensionSet(childDominanceSet, nextJob, nextFrontier);
		return new ForwardLabel(nextLabelId++, nextJob, label, visited, childDominanceSet, childExtensionSet,
				childNgMemory, nextFrontier, nextNoSriFrontier, childSriCounts, childSriPenalty);
	}

	private BackwardLabel extendBackward(BackwardLabel label, int prevJob, LP lp) {
		Node node = lp.getNode();
		PiecewiseLinearFunction nextFrontier;
		PiecewiseLinearFunction nextNoSriFrontier;
		double successorHStart = getDynamicBackwardHStart(prevJob, label.isSinkRoot ? node.sinkId() : label.jid);
		double rhoPrime;
		if (label.isSinkRoot) {
			rhoPrime = getDynamicBackwardHEnd(prevJob, node.sinkId());
			if (Utility.compareLt(rhoPrime, Math.max(tMid, successorHStart))) {
				return null;
			}
			PiecewiseLinearFunction jobPenalty = getDynamicBackwardJobPenalty(prevJob, node.sinkId());
			if (jobPenalty == null) {
				return null;
			}
			// 2026-05-22: backward 濞寸姴姘﹀▍鍕箯閻旇櫣鐭掗柣鎰嚀閸ゎ參宕ｉ幋鐐搭槯闁挎稑鐬奸鍥ㄧ▔閳ь剙鈻庨垾鍐差潱闁稿繈鍎冲﹢锛勨偓鍦仒閹广垽宕濋垾鑼憹闂傚洠鍋撻悷鏇氱劍鐎?setup/processing 妤犵偛纾簺闁?
			// 鐟滅増鎸告晶鐘诲矗濮椻偓閸ｅ搫顔忛懠顒傜梾闁?prevJob 闁煎浜滅换渚€鎯冮崟顐ゆ殮闁瑰瓨鍔栧鍌炴⒒鏉堝墽绀夐弶鈺傜懇閸ｇ兘宕ｉ鍛拸 job/arc dual闁?
			nextFrontier = jobPenalty.copy();
			nextNoSriFrontier = sriPricingEnabled ? jobPenalty.copy() : null;
			double fixedReducedCost = -lp.getJobDual(prevJob) - lp.getArcDual(prevJob, node.sinkId());
			nextFrontier.shiftYInPlace(fixedReducedCost);
			if (nextNoSriFrontier != null) {
				nextNoSriFrontier.shiftYInPlace(fixedReducedCost);
			}
		} else {
			double delay = data.getSetUp(prevJob, label.jid) + data.getProcessT(label.jid);
			if (!hasBackwardExtensionWindowOverlap(label, prevJob, delay)) {
				return null;
			}
			rhoPrime = Math.min(label.frontier.tail.end - delay, getDynamicBackwardHEnd(prevJob, label.jid));
			if (Utility.compareLt(rhoPrime, Math.max(tMid, successorHStart))) {
				return null;
			}
			PiecewiseLinearFunction shifted = label.frontier.shiftX(-delay);
			if (shifted.head == null) {
				shifted.release();
				return null;
			}
			PiecewiseLinearFunction jobPenalty = getDynamicBackwardJobPenalty(prevJob, label.jid);
			if (jobPenalty == null) {
				shifted.release();
				return null;
			}
			nextFrontier = shifted.add(jobPenalty);
			shifted.release();
			if (nextFrontier.head == null) {
				nextFrontier.release();
				return null;
			}
			nextNoSriFrontier = null;
			if (sriPricingEnabled) {
				PiecewiseLinearFunction shiftedNoSri = label.noSriFrontier.shiftX(-delay);
				if (shiftedNoSri.head == null) {
					shiftedNoSri.release();
					nextFrontier.release();
					return null;
				}
				nextNoSriFrontier = shiftedNoSri.add(jobPenalty);
				shiftedNoSri.release();
				if (nextNoSriFrontier.head == null) {
					nextNoSriFrontier.release();
					nextFrontier.release();
					return null;
				}
			}
			double fixedReducedCost = data.getSetupCost(prevJob, label.jid) - lp.getJobDual(prevJob)
					- lp.getArcDual(prevJob, label.jid);
			nextFrontier.shiftYInPlace(fixedReducedCost);
			if (nextNoSriFrontier != null) {
				nextNoSriFrontier.shiftYInPlace(fixedReducedCost);
			}
		}
		byte[] childSriCounts;
		double childSriPenalty;
		double sriShift;
		if (sriPricingEnabled) {
			childSriCounts = copySriCounts(label.sriCounts);
			sriShift = applySriBackwardPrependShift(childSriCounts, label.visitedSet, prevJob,
					label.isSinkRoot ? node.sinkId() : label.jid);
			childSriPenalty = label.sriPenalty + sriShift;
		} else {
			childSriCounts = copySriCounts(label.sriCounts);
			sriShift = 0.0;
			childSriPenalty = label.sriPenalty;
		}
		if (!Utility.compareEq(sriShift, 0.0)) {
			nextFrontier.shiftYInPlace(sriShift);
		}
		nextFrontier.normalize(Direction.BACKWARD);
		if (nextNoSriFrontier != null) {
			nextNoSriFrontier.normalize(Direction.BACKWARD);
		}
		if (nextFrontier.head == null || (nextNoSriFrontier != null && nextNoSriFrontier.head == null)) {
			nextFrontier.release();
			if (nextNoSriFrontier != null) {
				nextNoSriFrontier.release();
			}
			return null;
		}

		PackedBitSet visited = label.visitedSet.copy();
		visited.add(prevJob);
		PackedBitSet childNgMemory = updateNgMemory(label.ngMemorySet, prevJob);
		PackedBitSet childDominanceSet = buildBackwardDominanceSet(prevJob, childNgMemory, lp.getNode(),
				nextFrontier);
		PackedBitSet childExtensionSet = buildBackwardExtensionSet(childDominanceSet, prevJob, false, nextFrontier);
		return new BackwardLabel(nextLabelId++, prevJob, label, visited, childDominanceSet, childExtensionSet,
				childNgMemory, nextFrontier, nextNoSriFrontier, childSriCounts, childSriPenalty, false);
	}

	/** 提前判断 forward 扩展后的完成时间区间是否可能与任务有效窗口相交，避免构造必为空的 PWLF。 */
	private boolean hasForwardExtensionWindowOverlap(ForwardLabel label, int nextJob, double delay) {
		if (label.frontier == null || label.frontier.head == null) {
			return false;
		}
		double shiftedStart = Math.max(label.frontier.head.start + delay, label.frontier.domainStart);
		double shiftedEnd = Math.min(label.frontier.tail.end + delay, label.frontier.domainEnd);
		double windowStart = Math.max(getDynamicForwardHStart(label.jid, nextJob), 0.0);
		double windowEnd = Math.min(getDynamicForwardHEnd(label.jid, nextJob), tMid);
		double overlapStart = Math.max(shiftedStart, windowStart);
		double overlapEnd = Math.min(shiftedEnd, windowEnd);
		return !Utility.compareLt(overlapEnd, overlapStart);
	}

	/** 提前判断 backward 扩展后的完成时间区间是否可能与任务有效窗口相交，避免构造必为空的 PWLF。 */
	private boolean hasBackwardExtensionWindowOverlap(BackwardLabel label, int prevJob, double delay) {
		if (label.frontier == null || label.frontier.head == null) {
			return false;
		}
		double shiftedStart = Math.max(label.frontier.head.start - delay, label.frontier.domainStart);
		double shiftedEnd = Math.min(label.frontier.tail.end - delay, label.frontier.domainEnd);
		double windowStart = Math.max(getDynamicBackwardHStart(prevJob, label.jid), tMid);
		double windowEnd = Math.min(getDynamicBackwardHEnd(prevJob, label.jid), pricingHorizon);
		double overlapStart = Math.max(shiftedStart, windowStart);
		double overlapEnd = Math.min(shiftedEnd, windowEnd);
		return !Utility.compareLt(overlapEnd, overlapStart);
	}

	private InsertStatus insertForward(ForwardLabel label, LP lp) {
		if (isSinglePointFrontier(label.frontier)) {
			return insertForwardSinglePoint(label, lp);
		}
		boolean dominated = FWTL.get(label.jid).insertOrDominate(label);
		if (!dominated) {
			forwardLabelsKept++;
			activeForwardByLastJob.get(label.jid).add(label);
			activeForwardTerminalJobs.add(label.jid);
			updateForwardScalarInfo(label);
			recordForwardKeptDiagnostics(label);
			return InsertStatus.STORED_AND_ENQUEUE;
		}
		forwardLabelsDominated++;
		return InsertStatus.DOMINATED;
	}

	private InsertStatus insertBackward(BackwardLabel label, LP lp) {
		if (isSinglePointFrontier(label.frontier)) {
			return insertBackwardSinglePoint(label, lp);
		}
		boolean dominated = BWTL.get(label.jid).insertOrDominate(label);
		if (dominated) {
			backwardLabelsDominated++;
			return InsertStatus.DOMINATED;
		}
		backwardLabelsKept++;
		activeBackwardByFirstJob.get(label.jid).add(label);
		return InsertStatus.STORED_AND_ENQUEUE;
	}

	/**
	 * 2026-05-25: Tmid 闁告娲滈崑?forward label 濞戞挸绉撮崯鈧弶鈺傜☉閸欏棝寮查鈧埀?dominance graph闁挎稑濂旂弧鍐╃▔瀹ュ懎鏅欓柛蹇嬪劜婢ц法浠﹂弴銏⌒曢柛鎺擃殣缁?
	 * 濞达絽妫旂划娑氭啺娴ｉ绠介柣锝嗙懅缁?sink 闁衡偓鐠鸿櫣鍟查柛婊冭嫰閹绱?backward join闁?
	 */
	private InsertStatus insertForwardSinglePoint(ForwardLabel label, LP lp) {
		SinglePointStore<ForwardLabel> store = forwardSinglePointByLastJob.get(label.jid);
		if (isDominatedBySinglePointStore(store, label)) {
			label.isDominated = true;
			forwardLabelsDominated++;
			forwardSinglePointDominatedByStore++;
			return InsertStatus.DOMINATED;
		}
		if (FWTL.get(label.jid).dominatesSinglePoint(label.reachableSet, label.reachableCardinality, tMid,
				label.minReducedCost)) {
			label.isDominated = true;
			forwardLabelsDominated++;
			forwardSinglePointDominatedByGraph++;
			return InsertStatus.DOMINATED;
		}
		removeSinglePointsDominatedBy(store, label);
		addSinglePointLabel(store, label);
		forwardLabelsKept++;
		forwardSinglePointKept++;
		activeForwardByLastJob.get(label.jid).add(label);
		activeForwardTerminalJobs.add(label.jid);
		updateForwardScalarInfo(label);
		recordForwardKeptDiagnostics(label);
		return InsertStatus.STORED_NO_EXPAND;
	}

	/**
	 * 2026-05-25: Tmid 闁告娲滈崑?backward label 闁告瑯浜欑换姘舵偩濞嗘垹鑸?single-point store闁?
	 * 2026-05-26: 闁?GCNGBB-style 婵炵繝鑳堕埢鍏肩▔鐎ｂ晝鐟濈紒鏂款儏瀹?join闁挎稑鐭侀埀顒€鏈Σ鎼佸捶閵婏附浠橀柛姘捣缁儤绋夐埀顒勫箥椤愶絽浼?join闁?
	 */
	private InsertStatus insertBackwardSinglePoint(BackwardLabel label, LP lp) {
		SinglePointStore<BackwardLabel> store = backwardSinglePointByFirstJob.get(label.jid);
		if (isDominatedBySinglePointStore(store, label)) {
			label.isDominated = true;
			backwardLabelsDominated++;
			backwardSinglePointDominatedByStore++;
			return InsertStatus.DOMINATED;
		}
		if (BWTL.get(label.jid).dominatesSinglePoint(label.reachableSet, label.reachableCardinality, tMid,
				label.minReducedCost)) {
			label.isDominated = true;
			backwardLabelsDominated++;
			backwardSinglePointDominatedByGraph++;
			return InsertStatus.DOMINATED;
		}
		removeSinglePointsDominatedBy(store, label);
		addSinglePointLabel(store, label);
		backwardLabelsKept++;
		backwardSinglePointKept++;
		return InsertStatus.STORED_NO_EXPAND;
	}

	private boolean isSinglePointFrontier(PiecewiseLinearFunction frontier) {
		return frontier != null && frontier.head != null && frontier.tail != null
				&& Utility.compareEq(frontier.head.start, frontier.tail.end)
				&& Utility.compareEq(frontier.head.start, tMid);
	}

	private <L extends FunctionLabel> boolean isDominatedBySinglePointStore(SinglePointStore<L> store, L label) {
		if (!sriPricingEnabled) {
			L exact = store.bestByDominanceKey.get(label.reachableSet);
			if (exact != null) {
				if (exact.isDominated) {
					store.bestByDominanceKey.remove(label.reachableSet);
				} else if (!Utility.compareLt(label.minReducedCost, exact.minReducedCost)) {
					return true;
				}
			}
		}
		int labelCardinality = label.reachableCardinality;
		for (int cardinality = labelCardinality; cardinality < store.liveLabelsByCardinality.size(); cardinality++) {
			ArrayList<L> bucket = store.liveLabelsByCardinality.get(cardinality);
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int i = 0; i < bucket.size(); i++) {
				L existing = bucket.get(i);
				if (existing.isDominated) {
					continue;
				}
				if (existing.reachableSet.isSupersetOf(label.reachableSet)
						&& singlePointDominates(existing, label)) {
					return true;
				}
			}
		}
		return false;
	}

	private <L extends FunctionLabel> void removeSinglePointsDominatedBy(SinglePointStore<L> store, L label) {
		int labelCardinality = label.reachableCardinality;
		int maxCardinality = Math.min(labelCardinality, store.liveLabelsByCardinality.size() - 1);
		for (int cardinality = maxCardinality; cardinality >= 0; cardinality--) {
			ArrayList<L> bucket = store.liveLabelsByCardinality.get(cardinality);
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int i = bucket.size() - 1; i >= 0; i--) {
				L existing = bucket.get(i);
				if (existing.isDominated) {
					bucket.remove(i);
					continue;
				}
				if (label.reachableSet.isSupersetOf(existing.reachableSet)
						&& singlePointDominates(label, existing)) {
					existing.isDominated = true;
					bucket.remove(i);
					if (!sriPricingEnabled) {
						L mapped = store.bestByDominanceKey.get(existing.reachableSet);
						if (mapped == existing) {
							store.bestByDominanceKey.remove(existing.reachableSet);
						}
					}
				}
			}
		}
	}

	private <L extends FunctionLabel> void addSinglePointLabel(SinglePointStore<L> store, L label) {
		if (!sriPricingEnabled) {
			store.bestByDominanceKey.put(label.reachableSet, label);
		}
		ensureSinglePointBucket(store, label.reachableCardinality).add(label);
	}

	private <L extends FunctionLabel> boolean singlePointDominates(L dominator, L dominated) {
		double compensation = sriPricingEnabled
				? SriAwarePartialListDominanceStore.sriDominanceCompensation(dominator, dominated, sriDuals, sriScopes)
				: 0.0;
		return !Utility.compareGt(dominator.minReducedCost + compensation, dominated.minReducedCost);
	}

	private <L extends FunctionLabel> ArrayList<L> ensureSinglePointBucket(SinglePointStore<L> store, int cardinality) {
		while (store.liveLabelsByCardinality.size() <= cardinality) {
			store.liveLabelsByCardinality.add(null);
		}
		ArrayList<L> bucket = store.liveLabelsByCardinality.get(cardinality);
		if (bucket == null) {
			bucket = new ArrayList<L>();
			store.liveLabelsByCardinality.set(cardinality, bucket);
		}
		return bucket;
	}

	private void updateForwardScalarInfo(ForwardLabel label) {
		int lastJob = label.jid;
		if (Utility.compareLt(label.minReducedCost, minForwardReducedCostByLastJob[lastJob])) {
			minForwardReducedCostByLastJob[lastJob] = label.minReducedCost;
		}
		if (label.frontier != null && label.frontier.head != null
				&& Utility.compareLt(label.frontier.head.start, minForwardEllByLastJob[lastJob])) {
			minForwardEllByLastJob[lastJob] = label.frontier.head.start;
		}
	}

	private void tryGenerateForwardColumn(ForwardLabel label, LP lp) {
		if (label.jid == 0 || config.maxExactPricingColumns <= 0) {
			return;
		}
		Node node = lp.getNode();
		int sink = node.sinkId();
		if (isPricingArcForbidden(node, label.jid, sink)) {
			traceWatchedLabel("WATCH_F_SINK_ARC_FORBIDDEN", label);
			return;
		}
		forwardSinkLabelsVisited++;
		double reducedCost = label.minReducedCost - lp.getArcDual(label.jid, sink);
		observeRelaxedReducedCost(reducedCost);
		if (isWatchedLabel(label)) {
			traceTarget("WATCH_F_SINK_CHECK #" + labelId(label)
					+ " seq=" + recoverForwardSequence(label)
					+ " rc=" + reducedCost
					+ " min=" + label.minReducedCost
					+ " arcDual=" + lp.getArcDual(label.jid, sink));
		}
		if (!Utility.compareLt(reducedCost, REDUCED_COST_TOLERANCE)) {
			return;
		}
		forwardSinkNegativeCandidates++;
		recordDepthCount(forwardSinkNegativeByDepth, label.depth);
		ArrayList<Integer> sequence = recoverForwardSequence(label);
		tryGenerateColumn(sequence, lp, reducedCost);
	}

	/**
	 * 2026-05-28: final join 闁告挸绉剁划鐑樼▔閳ь剙銆掗崨顔肩鐎规瓕灏～锕傚触鎼达絿鏁?label 闁衡偓椤栫偛甯抽柣銊ュ濡偊寮堕敍鍕獥闁挎稑鑻崯鈧柟鐑樺笒缁參濡?
	 * 閺夆晜鐟﹂悧杈┾偓鐟版湰閺嗭綁宕氬Δ鈧ぐ褎绂掓惔銏′粯缂備礁鐗呯划娑氣偓娑櫳戝鍧楁儍?label table 闂佹彃鐬奸弫鎾诲箣閹板墎绀夊☉鎾崇Т瑜板牓寮埡鍌涘焸闁告垶妞藉Σ锔姐亜閸濆嫮纰嶇憸鏉垮船閹肩兘濡?
	 */
	private void compactAndSortActiveLabelListsForJoin() {
		for (int job = 1; job <= data.n; job++) {
			compactForwardLabelsForJoin(job);
			compactBackwardLabelsForJoin(job);
		}
	}

	private void compactForwardLabelsForJoin(int job) {
		ArrayList<ForwardLabel> labels = activeForwardByLastJob.get(job);
		int liveCount = 0;
		double liveMinReducedCost = Utility.big_M;
		double liveMinEll = Utility.big_M;
		for (int i = 0; i < labels.size(); i++) {
			ForwardLabel label = labels.get(i);
			if (label.isDominated) {
				continue;
			}
			labels.set(liveCount++, label);
			if (Utility.compareLt(label.minReducedCost, liveMinReducedCost)) {
				liveMinReducedCost = label.minReducedCost;
			}
			if (label.frontier != null && label.frontier.head != null
					&& Utility.compareLt(label.frontier.head.start, liveMinEll)) {
				liveMinEll = label.frontier.head.start;
			}
		}
		if (liveCount < labels.size()) {
			labels.subList(liveCount, labels.size()).clear();
		}
		if (liveCount == 0) {
			activeForwardTerminalJobs.remove(job);
			minForwardReducedCostByLastJob[job] = Utility.big_M;
			minForwardEllByLastJob[job] = Utility.big_M;
			return;
		}
		Collections.sort(labels);
		minForwardReducedCostByLastJob[job] = liveMinReducedCost;
		minForwardEllByLastJob[job] = liveMinEll;
		activeForwardTerminalJobs.add(job);
	}

	private void compactBackwardLabelsForJoin(int job) {
		ArrayList<BackwardLabel> labels = activeBackwardByFirstJob.get(job);
		int liveCount = 0;
		for (int i = 0; i < labels.size(); i++) {
			BackwardLabel label = labels.get(i);
			if (!label.isDominated) {
				labels.set(liveCount++, label);
			}
		}
		if (liveCount < labels.size()) {
			labels.subList(liveCount, labels.size()).clear();
		}
		Collections.sort(labels);
	}

	/**
	 * 2026-05-28: 缂備胶鍠嶇粩鎾绩鐠鸿櫣鍟?join闁靛棗鍊风悮杈ㄧ瑹?label table 闂侇喚鏅弫鎾诲箣閹邦剛鏆氬ù鐘劚閹鏁嶇仦闂寸鞍 forward terminal group 濞戞挸鎼ˇ鑽や沪閸岋妇绀?
	 * 闁告艾鏈鍌涘緞閸曨厽鍊?crossing-arc join 闁?forward->sink 闁衡偓鐠鸿櫣鍟查柨娑樼焸娴尖晠宕?sink 闁告帗顨堢划顐ｆ交閸モ晝鍩犲☉鎾亾闁稿﹥鐟╅埀顒€顦遍悺顐︽焻婢跺牃鍋?
	 */
	private void joinAllForwardTerminalGroups(LP lp) {
		for (int lastJob = activeForwardTerminalJobs.nextSetBit(0); lastJob >= 0 && lastJob <= data.n && canContinue();
				lastJob = activeForwardTerminalJobs.nextSetBit(lastJob + 1)) {
			ArrayList<ForwardLabel> candidates = activeForwardByLastJob.get(lastJob);
			if (candidates.isEmpty()) {
				continue;
			}
			joinForwardGroupToBackwardLabels(lastJob, candidates, lp);
			joinForwardGroupToSink(candidates, lp);
		}
	}

	private void joinForwardGroupToBackwardLabels(int lastJob, ArrayList<ForwardLabel> candidates, LP lp) {
		for (int firstJob = 1; firstJob <= data.n && canContinue(); firstJob++) {
			ArrayList<BackwardLabel> labels = activeBackwardByFirstJob.get(firstJob);
			for (int i = 0; i < labels.size() && canContinue(); i++) {
				BackwardLabel backward = labels.get(i);
				if (!backward.isDominated && !backward.isSinkRoot) {
					joinForwardGroupWithBackward(lastJob, candidates, backward, lp);
				}
			}
		}
		for (int firstJob = 1; firstJob <= data.n && canContinue(); firstJob++) {
			joinForwardGroupWithBackwardSinglePoints(lastJob, candidates, backwardSinglePointByFirstJob.get(firstJob),
					lp);
		}
	}

	private void joinForwardGroupWithBackwardSinglePoints(int lastJob, ArrayList<ForwardLabel> candidates,
			SinglePointStore<BackwardLabel> store, LP lp) {
		for (int cardinality = 0; cardinality < store.liveLabelsByCardinality.size() && canContinue(); cardinality++) {
			ArrayList<BackwardLabel> bucket = store.liveLabelsByCardinality.get(cardinality);
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int i = 0; i < bucket.size() && canContinue(); i++) {
				BackwardLabel backward = bucket.get(i);
				if (!backward.isDominated && !backward.isSinkRoot) {
					joinForwardGroupWithBackward(lastJob, candidates, backward, lp);
				}
			}
		}
	}

	private void joinForwardGroupToSink(ArrayList<ForwardLabel> candidates, LP lp) {
		for (int i = 0; i < candidates.size() && canContinue(); i++) {
			ForwardLabel label = candidates.get(i);
			if (!label.isDominated) {
				tryGenerateForwardColumn(label, lp);
			}
		}
	}

	private void joinForwardGroupWithBackward(int lastJob, ArrayList<ForwardLabel> candidates, BackwardLabel backward,
			LP lp) {
		Node node = lp.getNode();
		// 2026-05-23: 闁?joinFromForward 閻庨潧婀辫ⅷ闁挎稑濂旂粭澶愭嚄閻ｅ本鏆?backward.reachableSet 闁告瑥绉电敮褰掑箥閳ь剟寮垫径濠傝闁瑰嘲鍚嬬敮鎾礈瀹ュ洨纾婚柕?
		// 閻犲洢鍎靛▔锕傚触閸喐笑 backward 缂備綀鍛暰闁告碍鍨垫稊蹇涘箥閳轰胶娼旈柣銊ュ閳ь剚鐟╅埀顒€顧€缁辨繃绋夊鍥╂惣濞寸姾娓圭花顒勫箥閳ь剟寮垫径濠傝濞戞挸楠哥紞瀣礈瀹ュ懏鍊电紓鍌楀亾闁瑰嘲鍚嬬敮鎾儍?forward terminal闁?
		joinTerminalGroupsScanned++;
		if (backward.ngMemorySet.contains(lastJob) || isPricingArcForbidden(node, lastJob, backward.jid)) {
			joinTerminalGroupsArcOrVisitPruned++;
			return;
		}
		double delay = data.getSetUp(lastJob, backward.jid) + data.getProcessT(backward.jid);
		if (Utility.compareGt(minForwardEllByLastJob[lastJob] + delay, backward.frontier.tail.end)) {
			joinTerminalGroupsTimePruned++;
			return;
		}
		double joinFixedReducedCost = data.getSetupCost(lastJob, backward.jid)
				- lp.getArcDual(lastJob, backward.jid);
		double joinThreshold = joinLowerBoundThreshold();
		double groupLB = minForwardReducedCostByLastJob[lastJob] + backward.minReducedCost + joinFixedReducedCost;
		if (!Utility.compareLt(groupLB, joinThreshold)) {
			joinTerminalGroupsCostPruned++;
			if (Utility.compareLt(joinThreshold, REDUCED_COST_TOLERANCE)) {
				joinPairsBestBoundPruned++;
			}
			return;
		}
		for (int i = 0; i < candidates.size(); i++) {
			ForwardLabel forward = candidates.get(i);
			joinCandidateLabelsVisited++;
			if (forward.isDominated) {
				joinCandidateLabelsDominated++;
				continue;
			}
			double optimisticJoinLB = forward.minReducedCost + backward.minReducedCost + joinFixedReducedCost;
			if (!Utility.compareLt(optimisticJoinLB, joinThreshold)) {
				joinPairsLowerBoundPruned++;
				if (Utility.compareLt(joinThreshold, REDUCED_COST_TOLERANCE)) {
					joinPairsBestBoundPruned++;
				}
				break;
			}
			tryJoin(forward, backward, lp, joinFixedReducedCost);
		}
	}

	private void tryJoin(ForwardLabel forward, BackwardLabel backward, LP lp, double joinFixedReducedCost) {
		if (config.maxExactPricingColumns <= 0) {
			return;
		}
		joinPairsTried++;
		boolean targetJoinPair = isTargetJoinPair(forward, backward);
		if (targetJoinPair) {
			traceTarget("JOIN_PAIR f#" + forward.labelId + " b#" + backward.labelId
					+ " f=" + recoverForwardSequence(forward) + " b=" + recoverBackwardSequence(backward)
					+ " fMin=" + forward.minReducedCost + " bMin=" + backward.minReducedCost);
		}
		if (forward.jid == backward.jid) {
			joinPairsSetPruned++;
			if (targetJoinPair) {
				traceTarget("JOIN_PRUNED sameTerminal");
			}
			return;
		}
		if (bitSetsIntersectForJoin(forward.ngMemorySet, backward.ngMemorySet)) {
			// 2026-06-09: ng-DSSR 闁告瑯浜為弫?ng-memory 闁告帇鍊栭弻鍥箯閸忕厧澶嶉柡鍕靛灠閹焦娼诲┑鍡楀唨鐟滅増鎸告晶鐘垫媼閺夎法绠撻柨?
			// 闁活亞鍠庨悿鍕煂瀹ュ拋妲婚煫鍥ф嚇閵嗗繒绮垫径搴ｎ槹 reduced-cost route 闁诡厹鍨归ˇ鏌ュ触鎼粹€虫櫃閻犱焦婢樼紞?cycle闁挎稑鐬奸弫銈嗙鎼粹剝鍊电紓渚囧幗濞插潡寮?ng-set闁?
			joinPairsSetPruned++;
			if (targetJoinPair) {
				traceTarget("JOIN_PRUNED ngMemoryIntersect fMem=" + forward.ngMemorySet
						+ " bMem=" + backward.ngMemorySet);
			}
			return;
		}
		double delta = data.getSetUp(forward.jid, backward.jid) + data.getProcessT(backward.jid);
		double earliestBackwardCompletion = forward.frontier.head.start + delta;
		if (Utility.compareGt(earliestBackwardCompletion, backward.frontier.tail.end)) {
			joinPairsTimePruned++;
			if (targetJoinPair) {
				traceTarget("JOIN_PRUNED time earliestBackwardCompletion=" + earliestBackwardCompletion
						+ " bTail=" + backward.frontier.tail.end);
			}
			return;
		}

		PiecewiseLinearFunction forwardFull = getForwardJoinExtension(forward);
		if (forwardFull.head == null) {
			joinFunctionPruned++;
			if (targetJoinPair) {
				traceTarget("JOIN_PRUNED forwardFullEmpty");
			}
			return;
		}
		PiecewiseLinearFunction backwardFull = getBackwardJoinExtension(backward);
		if (backwardFull.head == null) {
			joinFunctionPruned++;
			if (targetJoinPair) {
				traceTarget("JOIN_PRUNED backwardFullEmpty");
			}
			return;
		}
		// 2026-05-22: crossing arc (i,r) 闁汇劌瀚ù鎰偓?reduced-cost 濡炪倝鈧稓鐟濆ù鐘叉噺濠€?setup cost闁?
		// 閺夆晜锚缁烩偓濡炪倗绮晶鎼佸箳婢跺寒鍤夌€殿啫鍐╄含 RMP 濞戞搩鍘惧▓鎴︽嚂濮橆剚鍊?arc dual闁挎稒绋戦幆渚€宕?join 濞戞挸顑囬弲顐ｅ濮橆兛鐒婂Δ鍌涳公缁辨繈寮告担渚紓闁哄啯婀圭槐鏉款煶韫囨柨绔撮柣顏嗗枙缁€瀣礆濡炲皷鍋?
		ArrayList<Integer> sequence = null;
		double sriJoinShift;
		if (limitedMemorySriPricing) {
			sriJoinShift = limitedMemorySriJoinShift(forward, backward);
		} else {
			sriJoinShift = sriJoinShift(forward, backward);
		}
		double fixedJoinShift = joinFixedReducedCost + sriJoinShift;
		double shiftedForwardStart = Math.max(forwardFull.head.start + delta, forwardFull.domainStart);
		double shiftedForwardEnd = Math.min(forwardFull.tail.end + delta, forwardFull.domainEnd);
		double overlapStart = Math.max(shiftedForwardStart, backwardFull.head.start);
		double overlapEnd = Math.min(shiftedForwardEnd, backwardFull.tail.end);
		if (Utility.compareLt(overlapEnd, overlapStart)) {
			joinFunctionPruned++;
			if (targetJoinPair) {
				traceTarget("JOIN_PRUNED joinDomainEmpty");
			}
			return;
		}
		if (config.bidirectionalJoinRangeRestrictedLowerBound) {
			joinRangeLowerBoundChecks++;
			double forwardRangeMin = forwardFull.findMinimalInRange(overlapStart - delta, overlapEnd - delta);
			double backwardRangeMin = backwardFull.findMinimalInRange(overlapStart, overlapEnd);
			double rangeLowerBound = forwardRangeMin + backwardRangeMin + fixedJoinShift;
			double threshold = joinLowerBoundThreshold();
			if (!Utility.compareLt(rangeLowerBound, threshold)) {
				joinRangeLowerBoundPruned++;
				if (Utility.compareLt(threshold, REDUCED_COST_TOLERANCE)) {
					joinPairsBestBoundPruned++;
				}
				if (targetJoinPair) {
					traceTarget("JOIN_PRUNED rangeLB=" + rangeLowerBound);
				}
				return;
			}
		}
		joinFunctionEvaluations++;
		double reducedCostBound = PiecewiseLinearFunction.findMinimalShiftedSumValue(forwardFull, delta,
				backwardFull, fixedJoinShift);
		observeRelaxedReducedCost(reducedCostBound);
		if (!shouldKeepJoinedReducedCost(reducedCostBound)) {
			joinFunctionPruned++;
			if (Utility.compareLt(reducedCostBound, REDUCED_COST_TOLERANCE)) {
				joinFunctionBestRecordPruned++;
			}
			if (targetJoinPair) {
				traceTarget("JOIN_PRUNED reducedCostBound=" + reducedCostBound);
			}
			return;
		}

		if (sequence == null) {
			sequence = recoverJoinSequence(forward, backward);
		}
		if (targetJoinPair) {
			traceTarget("JOIN_KEEP reducedCostBound=" + reducedCostBound);
		}
		tryGenerateColumn(sequence, lp, reducedCostBound);
	}

	/**
	 * 2026-06-13: full-SRI 闁绘鍩栭埀顑跨濠€顏堝础閺囨碍娅犻柟纰樻櫅閻秹寮捄鍝勬锭闁活厹鍎垫禍鍓ф嫚閵夈儱纾归悹渚灠缁剁偤宕橀崨鏉戝姤闁哄嫷鍨伴幆浣割啅閼碱剛鐥呴悷娆欑畱瑜板倿濡?
	 * 闁瑰嘲鍚嬬敮鎾籍鐠轰警娲ら柡瀣矊娑斿繘宕ｉ崘鎻掔９閻犱警鍨扮欢鐐哄触閸曨喚顢呴柣姘煎枙缁斿瓨绋夐鍐憹闁?scope job闁挎稑鑻悾顒勫极?route 闁归潧绉疯闁告瑦鍨崇粩鏉戔枎?SRI闁挎稑鐭傚〒鍓佹啺娴ｅ憡韬弶鈺傜懇閸ｉ鎮伴妷銉︾闁?
	 */
	private double sriJoinShift(ForwardLabel forward, BackwardLabel backward) {
		if (!sriPricingEnabled) {
			return 0.0;
		}
		double shift = 0.0;
		for (int sriIndex = 0; sriIndex < sriCutIds.size(); sriIndex++) {
			int forwardCount = forward.sriCounts[sriIndex];
			int backwardCount = backward.sriCounts[sriIndex];
			double dual = sriDuals.get(sriIndex).doubleValue();
			if (forwardCount > 1 && backwardCount > 1) {
				// 濞戞挶鍊曞畷鎰板触閸曨喖娈扮€规瓕灏欑划锟犲箥閿濆牏绠栧☉鎾亾婵炲棌妲勭槐婵嬪触鐏炶偐顏遍柡澶嗏偓宕囨殮闁?route 闁告瑯浜滅花鏌ュ箥閿濆嫮顏辨繛鍠℃壋鍋?
				shift += dual;
			} else if (forwardCount == 1 && backwardCount == 1
					&& sriHalvesContainDifferentScopeJobs(forward, backward, sriScopes.get(sriIndex))) {
				shift -= dual;
			}
		}
		return shift;
	}

	/**
	 * limited-memory join 闁告瑯浜濋ˉ鍛村蓟?crossing arc 濞戞挶鍊撻弲鍫曞及椤栨碍鍎婇柟璺猴工閹挻绋夐埀?cut 闁?residual half-state 闁瑰嘲鍚嬮崹姘▔閳ь剙鈻庨檱琚濋柛娆愬灟閳?
	 * node-memory 闁汇劌瀚换娑氱磼椤撶啿鍋撹閺?backward 濡絾鐗炴俊顓㈡倷鐟欏嫭笑闁告熬绠戝﹢?memory 濞戞搩鍘虹紞瀣偝鐢喚骞rc-memory 閺夆晜锚缁烩偓濡炪倖妲掗々锕€效?crossing arc 闁革负鍔忛?cut 闁?memory arcs 濞戞搩鍘归埀?
	 */
	private double limitedMemorySriJoinShift(ForwardLabel forward, BackwardLabel backward) {
		if (!sriPricingEnabled) {
			return 0.0;
		}
		double shift = 0.0;
		for (int sriIndex = 0; sriIndex < sriCutIds.size(); sriIndex++) {
			if (sriCuts.get(sriIndex).hasMemoryArcs() && !isSriMemoryArc(sriIndex, forward.jid, backward.jid)) {
				continue;
			}
			if (forward.sriCounts[sriIndex] + backward.sriCounts[sriIndex] >= 2) {
				shift -= sriDuals.get(sriIndex).doubleValue();
			}
		}
		return shift;
	}

	private boolean sriHalvesContainDifferentScopeJobs(ForwardLabel forward, BackwardLabel backward, int[] scope) {
		boolean forwardOnly = false;
		boolean backwardOnly = false;
		for (int job : scope) {
			boolean inForward = forward.visitedSet.contains(job);
			boolean inBackward = backward.visitedSet.contains(job);
			if (inForward && !inBackward) {
				forwardOnly = true;
			}
			if (inBackward && !inForward) {
				backwardOnly = true;
			}
		}
		return forwardOnly && backwardOnly;
	}

	private void resetStatistics() {
		forwardLabelsKept = 0;
		forwardLabelsDominated = 0;
		backwardLabelsKept = 0;
		backwardLabelsDominated = 0;
		joinTerminalGroupsScanned = 0;
		joinTerminalGroupsArcOrVisitPruned = 0;
		joinTerminalGroupsTimePruned = 0;
		joinTerminalGroupsCostPruned = 0;
		joinCandidateLabelsVisited = 0;
		joinCandidateLabelsDominated = 0;
		joinPairsTried = 0;
		joinPairsSetPruned = 0;
		joinPairsLowerBoundPruned = 0;
		joinPairsBestBoundPruned = 0;
		joinPairsTimePruned = 0;
		joinFunctionEvaluations = 0;
		joinFunctionPruned = 0;
		joinFunctionBestRecordPruned = 0;
		joinRangeLowerBoundChecks = 0;
		joinRangeLowerBoundPruned = 0;
		forwardSinglePointKept = 0;
		forwardSinglePointDominatedByStore = 0;
		forwardSinglePointDominatedByGraph = 0;
		backwardSinglePointKept = 0;
		backwardSinglePointDominatedByStore = 0;
		backwardSinglePointDominatedByGraph = 0;
		generatedCandidateCount = 0;
		generatedCandidateDroppedByHeap = 0;
		forwardSinkLabelsVisited = 0;
		forwardSinkNegativeCandidates = 0;
		forwardExtensionCandidates = 0;
		forwardExtensionArcPruned = 0;
		forwardExtensionInfeasible = 0;
		forwardExtensionConstructed = 0;
		forwardExtensionBoundSurvivors = 0;
		forwardLabelsKeptByDepth = new long[data.n + 1];
		forwardSinkNegativeByDepth = new long[data.n + 1];
		forwardLabelsKeptReachableSum = 0;
		forwardLabelsKeptReachableMin = Integer.MAX_VALUE;
		forwardLabelsKeptReachableMax = 0;
		completionForwardLabelsPruned = 0;
		completionBackwardLabelsPruned = 0;
		completionBoundFunctionEvaluations = 0;
		completionBoundScalarChecks = 0;
		completionBoundScalarPruned = 0;
		completionBoundScalarFunctionFallbacks = 0;
		completionBoundScalarUnavailable = 0;
		timeIndexedScalarBuildNanos = 0;
		timeIndexedScalarImprovedChecks = 0;
		timeIndexedScalarExtraPruned = 0;
		timeIndexedScalarUnavailable = 0;
		timeIndexedWindowTightenedJobs = 0;
		timeIndexedWindowReachableJobs = 0;
		timeIndexedScalarBound = null;
		completionBoundArcFixingCandidates = 0;
		completionBoundArcFixingFixed = 0;
		completionBoundArcFixingDomainPruned = 0;
		completionBoundArcFixingScalarPruned = 0;
		completionBoundArcFixingUnavailable = 0;
		completionBoundArcFixingFunctionEvaluations = 0;
		completionBoundArcFixingNanos = 0;
		completionBoundBuildNanos = 0;
		completionBoundForwardBuildNanos = 0;
		completionBoundBackwardBuildNanos = 0;
		completionBoundAggregateNanos = 0;
		completionBoundForwardCandidateAttempts = 0;
		completionBoundBackwardCandidateAttempts = 0;
		completionBoundForwardQueuePops = 0;
		completionBoundBackwardQueuePops = 0;
		completionBoundPriorityQueueStalePops = 0;
		completionBoundMergeCalls = 0;
		completionBoundMergeChanged = 0;
		completionBoundForwardSegmentSamples = 0;
		completionBoundForwardTargetSegments = 0;
		completionBoundForwardCandidateSegments = 0;
		completionBoundForwardAfterSegments = 0;
		completionBoundForwardMaxTargetSegments = 0;
		completionBoundForwardMaxCandidateSegments = 0;
		completionBoundForwardMaxAfterSegments = 0;
		completionBoundBackwardSegmentSamples = 0;
		completionBoundBackwardTargetSegments = 0;
		completionBoundBackwardCandidateSegments = 0;
		completionBoundBackwardAfterSegments = 0;
		completionBoundBackwardMaxTargetSegments = 0;
		completionBoundBackwardMaxCandidateSegments = 0;
		completionBoundBackwardMaxAfterSegments = 0;
		completionBoundLastEvaluationCutoff = Double.NaN;
		completionBoundPreCertificateClosed = false;
		midpointStrategyUsed = "default";
		midpointReferenceTime = Double.NaN;
		midpointColumnSelectedCount = 0;
		midpointColumnLastMin = Double.NaN;
		midpointColumnLastAvg = Double.NaN;
		midpointColumnLastMax = Double.NaN;
		midpointColumnHalfMin = Double.NaN;
		midpointColumnHalfAvg = Double.NaN;
		midpointColumnHalfMax = Double.NaN;
		midpointColumnTaskSampleCount = 0;
		midpointColumnTaskMin = Double.NaN;
		midpointColumnTaskAvg = Double.NaN;
		midpointColumnTaskMedian = Double.NaN;
		midpointColumnTaskMax = Double.NaN;
		midpointProbeSummary = "off";
		midpointProbeFeedbackSummary = "off";
		midpointProbeLabelsReadyForJoin = false;
		midpointStrategyNanos = 0;
		diagnosticForbiddenJobArcCount = 0;
		diagnosticPricingOnlyJobArcCount = 0;
		diagnosticJobDualPositiveCount = 0;
		diagnosticMachineDual = 0.0;
		diagnosticJobDualMin = 0.0;
		diagnosticJobDualMax = 0.0;
		diagnosticJobDualSum = 0.0;
		diagnosticJobDualQuantiles = null;
		diagnosticRestrictedColumnCount = 0;
		diagnosticIncompatibleRestrictedColumnCount = 0;
		diagnosticRestrictedColumnAvgLength = 0.0;
		diagnosticAllowedJobArcDualNonZeroCount = 0;
		diagnosticForbiddenJobArcDualNonZeroCount = 0;
		diagnosticSinkArcDualNonZeroCount = 0;
		diagnosticAllowedJobArcDualMin = 0.0;
		diagnosticAllowedJobArcDualMax = 0.0;
		diagnosticAllowedJobArcDualAbsSum = 0.0;
		diagnosticForbiddenJobArcDualAbsSum = 0.0;
		diagnosticSinkArcDualMin = 0.0;
		diagnosticSinkArcDualMax = 0.0;
		diagnosticSinkArcDualAbsSum = 0.0;
		diagnosticLastHeartbeatNanos = 0;
		diagnosticHeartbeatIntervalNanos = Long.getLong("twet.bpc.diagnosticHeartbeatIntervalMillis", 10000L)
				* 1000000L;
		diagnosticForwardPops = 0;
		diagnosticBackwardPops = 0;
	}

	private void resetProbeAffectedStatistics() {
		forwardLabelsKept = 0;
		forwardLabelsDominated = 0;
		backwardLabelsKept = 0;
		backwardLabelsDominated = 0;
		joinTerminalGroupsScanned = 0;
		joinTerminalGroupsArcOrVisitPruned = 0;
		joinTerminalGroupsTimePruned = 0;
		joinTerminalGroupsCostPruned = 0;
		joinCandidateLabelsVisited = 0;
		joinCandidateLabelsDominated = 0;
		joinPairsTried = 0;
		joinPairsSetPruned = 0;
		joinPairsLowerBoundPruned = 0;
		joinPairsBestBoundPruned = 0;
		joinPairsTimePruned = 0;
		joinFunctionEvaluations = 0;
		joinFunctionPruned = 0;
		joinFunctionBestRecordPruned = 0;
		forwardSinglePointKept = 0;
		forwardSinglePointDominatedByStore = 0;
		forwardSinglePointDominatedByGraph = 0;
		backwardSinglePointKept = 0;
		backwardSinglePointDominatedByStore = 0;
		backwardSinglePointDominatedByGraph = 0;
		generatedCandidateCount = 0;
		generatedCandidateDroppedByHeap = 0;
		forwardSinkLabelsVisited = 0;
		forwardSinkNegativeCandidates = 0;
		forwardExtensionCandidates = 0;
		forwardExtensionArcPruned = 0;
		forwardExtensionInfeasible = 0;
		forwardExtensionConstructed = 0;
		forwardExtensionBoundSurvivors = 0;
		forwardLabelsKeptByDepth = new long[data.n + 1];
		forwardSinkNegativeByDepth = new long[data.n + 1];
		forwardLabelsKeptReachableSum = 0;
		forwardLabelsKeptReachableMin = Integer.MAX_VALUE;
		forwardLabelsKeptReachableMax = 0;
		completionForwardLabelsPruned = 0;
		completionBackwardLabelsPruned = 0;
		completionBoundFunctionEvaluations = 0;
		completionBoundScalarChecks = 0;
		completionBoundScalarPruned = 0;
		completionBoundScalarFunctionFallbacks = 0;
		completionBoundScalarUnavailable = 0;
		timeIndexedScalarImprovedChecks = 0;
		timeIndexedScalarExtraPruned = 0;
		timeIndexedScalarUnavailable = 0;
		completionBoundLastEvaluationCutoff = Double.NaN;
		diagnosticForwardPops = 0;
		diagnosticBackwardPops = 0;
		fullMidpointDiagnosticRan = false;
	}

	private void diagnosticHeartbeat(LP lp, String phase, boolean force) {
		if (!config.diagnosticStageHeartbeat) {
			return;
		}
		long now = System.nanoTime();
		if (!force && diagnosticHeartbeatIntervalNanos > 0 && diagnosticLastHeartbeatNanos > 0
				&& now - diagnosticLastHeartbeatNanos < diagnosticHeartbeatIntervalNanos) {
			return;
		}
		diagnosticLastHeartbeatNanos = now;
		Node node = lp == null ? null : lp.getNode();
		String nodeId = node == null ? "-" : Integer.toString(node.id);
		System.out.println("[BPC exact heartbeat] node=" + nodeId
				+ " phase=" + phase
				+ " fwQueue=" + queueSize(FWUL)
				+ " bwQueue=" + queueSize(BWUL)
				+ " fwPops=" + diagnosticForwardPops
				+ " bwPops=" + diagnosticBackwardPops
				+ " fwKept=" + forwardLabelsKept
				+ " fwDom=" + forwardLabelsDominated
				+ " bwKept=" + backwardLabelsKept
				+ " bwDom=" + backwardLabelsDominated
				+ " fCand=" + forwardExtensionCandidates
				+ " fBuilt=" + forwardExtensionConstructed
				+ " fBoundSurvivors=" + forwardExtensionBoundSurvivors
				+ " cbFPruned=" + completionForwardLabelsPruned
				+ " cbBPruned=" + completionBackwardLabelsPruned
				+ " joinPairs=" + joinPairsTried
				+ " generated=" + generatedCandidateCount
				+ " bestRC=" + bestGeneratedReducedCost
				+ " pricingHorizon=" + pricingHorizon
				+ " tMid=" + tMid
				+ " midpointStrategy=" + midpointStrategyUsed
				+ " midpointRef=" + midpointReferenceTime);
		System.out.flush();
	}

	private int queueSize(PriorityQueue<?> queue) {
		return queue == null ? 0 : queue.size();
	}

	private String statisticsSummary() {
		return "labels fw kept/dominated=" + forwardLabelsKept + "/" + forwardLabelsDominated
				+ ", bw kept/dominated=" + backwardLabelsKept + "/" + backwardLabelsDominated
				+ ", halfWindowIneligible fw/bw=" + forwardHalfIneligibleJobCount + "/"
				+ backwardHalfIneligibleJobCount
				+ ", singlePoint fw kept/storeDom/graphDom=" + forwardSinglePointKept + "/"
				+ forwardSinglePointDominatedByStore + "/" + forwardSinglePointDominatedByGraph
				+ ", bw kept/storeDom/graphDom=" + backwardSinglePointKept + "/"
				+ backwardSinglePointDominatedByStore + "/" + backwardSinglePointDominatedByGraph
				+ ", join groups scanned/arcOrVisit/timeLB/costLB=" + joinTerminalGroupsScanned
				+ "/" + joinTerminalGroupsArcOrVisitPruned
				+ "/" + joinTerminalGroupsTimePruned + "/" + joinTerminalGroupsCostPruned
				+ ", join candidates visited/dominated=" + joinCandidateLabelsVisited + "/"
				+ joinCandidateLabelsDominated
				+ ", join pairs tried/set/lb/time/funcEval/funcPruned=" + joinPairsTried
				+ "/" + joinPairsSetPruned + "/" + joinPairsLowerBoundPruned + "/"
				+ joinPairsTimePruned + "/"
				+ joinFunctionEvaluations + "/" + joinFunctionPruned
				+ ", joinRangeLB check/pruned=" + joinRangeLowerBoundChecks
				+ "/" + joinRangeLowerBoundPruned
				+ ", joinBest mode/bestRC/lbPruned/recordPruned=" + joinBestThresholdMode
				+ "/" + bestGeneratedReducedCost + "/" + joinPairsBestBoundPruned
				+ "/" + joinFunctionBestRecordPruned
				+ ", completionBound mode/cutoff/buildMs/eval/fwPruned/bwPruned="
				+ completionBoundRelaxationForSummary()
				+ "/" + completionBoundCutoffForSummary() + "/" + formatMillis(completionBoundBuildNanos)
				+ "/" + completionBoundFunctionEvaluations + "/" + completionForwardLabelsPruned
				+ "/" + completionBackwardLabelsPruned
				+ ", completionBoundScalar check/pruned/fallback/unavailable=" + completionBoundScalarChecks
				+ "/" + completionBoundScalarPruned + "/" + completionBoundScalarFunctionFallbacks
				+ "/" + completionBoundScalarUnavailable
				+ ", timeIndexedScalar buildMs/improved/extraPruned/unavailable/windowTightenedReachable="
				+ formatMillis(timeIndexedScalarBuildNanos) + "/" + timeIndexedScalarImprovedChecks
				+ "/" + timeIndexedScalarExtraPruned + "/" + timeIndexedScalarUnavailable
				+ "/" + timeIndexedWindowTightenedJobs + "-" + timeIndexedWindowReachableJobs
				+ ", completionBoundArcFixing candidates/fixed/domain/scalar/unavailable/funcEval/ms="
				+ completionBoundArcFixingCandidates + "/" + completionBoundArcFixingFixed
				+ "/" + completionBoundArcFixingDomainPruned + "/" + completionBoundArcFixingScalarPruned
				+ "/" + completionBoundArcFixingUnavailable + "/" + completionBoundArcFixingFunctionEvaluations
				+ "/" + formatMillis(completionBoundArcFixingNanos)
				+ ", forwardSink visited/negative=" + forwardSinkLabelsVisited
				+ "/" + forwardSinkNegativeCandidates
				+ ", forwardExtend candidates/arcPruned/infeasible/constructed/boundSurvivors="
				+ forwardExtensionCandidates + "/" + forwardExtensionArcPruned
				+ "/" + forwardExtensionInfeasible + "/" + forwardExtensionConstructed
				+ "/" + forwardExtensionBoundSurvivors
				+ ", forwardDepth kept/negSink=" + formatDepthHistogram(forwardLabelsKeptByDepth)
				+ "/" + formatDepthHistogram(forwardSinkNegativeByDepth)
				+ ", forwardReach kept avg/min/max=" + formatAverage(forwardLabelsKeptReachableSum,
						forwardLabelsKept) + "/" + formatReachableMin() + "/" + forwardLabelsKeptReachableMax
				+ nodeDiagnosticsSummary()
				+ ", completionBoundQueue=" + completionBoundQueueOrdering
				+ ", completionBoundInternal timingMs fw/bw/agg=" + formatMillis(completionBoundForwardBuildNanos)
				+ "/" + formatMillis(completionBoundBackwardBuildNanos) + "/"
				+ formatMillis(completionBoundAggregateNanos)
				+ ", completionBoundInternal counts fCand/bCand/fPop/bPop/stale/merge/changed="
				+ completionBoundForwardCandidateAttempts + "/" + completionBoundBackwardCandidateAttempts
				+ "/" + completionBoundForwardQueuePops + "/" + completionBoundBackwardQueuePops
				+ "/" + completionBoundPriorityQueueStalePops
				+ "/" + completionBoundMergeCalls + "/" + completionBoundMergeChanged
				+ ", completionBoundSegments fwSamples/targetAvg/candAvg/afterAvg/maxTCA="
				+ completionBoundForwardSegmentSamples
				+ "/" + formatAverage(completionBoundForwardTargetSegments, completionBoundForwardSegmentSamples)
				+ "/" + formatAverage(completionBoundForwardCandidateSegments, completionBoundForwardSegmentSamples)
				+ "/" + formatAverage(completionBoundForwardAfterSegments, completionBoundForwardSegmentSamples)
				+ "/" + completionBoundForwardMaxTargetSegments + "-" + completionBoundForwardMaxCandidateSegments
				+ "-" + completionBoundForwardMaxAfterSegments
				+ ", completionBoundSegments bwSamples/targetAvg/candAvg/afterAvg/maxTCA="
				+ completionBoundBackwardSegmentSamples
				+ "/" + formatAverage(completionBoundBackwardTargetSegments, completionBoundBackwardSegmentSamples)
				+ "/" + formatAverage(completionBoundBackwardCandidateSegments, completionBoundBackwardSegmentSamples)
				+ "/" + formatAverage(completionBoundBackwardAfterSegments, completionBoundBackwardSegmentSamples)
				+ "/" + completionBoundBackwardMaxTargetSegments + "-" + completionBoundBackwardMaxCandidateSegments
				+ "-" + completionBoundBackwardMaxAfterSegments
				+ ", candidatePool kept/seen/dropped=" + generatedCandidateBySignature.size() + "/"
				+ generatedCandidateCount + "/" + generatedCandidateDroppedByHeap
				+ ", queueOrdering=" + queueOrdering
				+ ", dynamicHStartMin=" + dynamicMinHStart + ", dynamicHEndMax=" + dynamicMaxHEnd
				+ ", earliestSourceCompletion=" + earliestSourceCompletion
				+ ", pricingHorizon=" + pricingHorizon + ", tMid=" + tMid
				+ ", midpointStrategy/ref/ms=" + midpointStrategyUsed + "/" + midpointReferenceTime + "/"
				+ formatMillis(midpointStrategyNanos)
				+ ", midpointColumns count/lastMinAvgMax/halfMinAvgMax=" + midpointColumnSelectedCount
				+ "/" + midpointColumnLastMin + "/" + midpointColumnLastAvg + "/" + midpointColumnLastMax
				+ "/" + midpointColumnHalfMin + "/" + midpointColumnHalfAvg + "/" + midpointColumnHalfMax
				+ ", midpointColumnTasks count/minAvgMedianMax=" + midpointColumnTaskSampleCount
				+ "/" + midpointColumnTaskMin + "/" + midpointColumnTaskAvg + "/" + midpointColumnTaskMedian
				+ "/" + midpointColumnTaskMax
				+ ", midpointProbe=" + midpointProbeSummary
				+ ", midpointProbeFeedback=" + midpointProbeFeedbackSummary
				+ targetTraceSummary()
				+ ", zeroDualExcludedJobs=" + zeroDualExcludedJobCount
				+ ", piWindow=" + (dualProfitableWindowEnabled ? "enabled" : "disabled")
				+ ", " + dominanceStatisticsSummary();
	}

	private String targetTraceSummary() {
		if (targetTrace == null) {
			return "";
		}
		return ", targetTrace=" + targetTrace.toString();
	}

	private String nodeDiagnosticsSummary() {
		if (!config.diagnosticPricingSummaryDetails) {
			return "";
		}
		return ", nodeDiag forbiddenJobArcs/pricingOnlyJobArcs/machineDual/jobDual min/max/sum/pos="
				+ diagnosticForbiddenJobArcCount + "/" + diagnosticPricingOnlyJobArcCount
				+ "/" + diagnosticMachineDual + "/" + diagnosticJobDualMin + "/" + diagnosticJobDualMax
				+ "/" + diagnosticJobDualSum + "/" + diagnosticJobDualPositiveCount
				+ ", nodeDiag jobDual q0/q10/q25/q50/q75/q90/q100="
				+ formatJobDualQuantiles()
				+ ", nodeDiag columns/incompat/avgLen=" + diagnosticRestrictedColumnCount
				+ "/" + diagnosticIncompatibleRestrictedColumnCount + "/"
				+ String.format("%.3f", diagnosticRestrictedColumnAvgLength)
				+ ", nodeDiag arcDual allowedNZ/min/max/absSum=" + diagnosticAllowedJobArcDualNonZeroCount
				+ "/" + diagnosticAllowedJobArcDualMin + "/" + diagnosticAllowedJobArcDualMax
				+ "/" + diagnosticAllowedJobArcDualAbsSum
				+ ", forbiddenNZ/absSum=" + diagnosticForbiddenJobArcDualNonZeroCount
				+ "/" + diagnosticForbiddenJobArcDualAbsSum
				+ ", sinkNZ/min/max/absSum=" + diagnosticSinkArcDualNonZeroCount
				+ "/" + diagnosticSinkArcDualMin + "/" + diagnosticSinkArcDualMax
				+ "/" + diagnosticSinkArcDualAbsSum;
	}

	private static String formatMillis(long nanos) {
		return String.format("%.3f", nanos / 1_000_000.0);
	}

	private void recordForwardKeptDiagnostics(ForwardLabel label) {
		if (label == null || label.jid == 0) {
			return;
		}
		recordDepthCount(forwardLabelsKeptByDepth, label.depth);
		forwardLabelsKeptReachableSum += label.extensionCardinality;
		forwardLabelsKeptReachableMin = Math.min(forwardLabelsKeptReachableMin, label.extensionCardinality);
		forwardLabelsKeptReachableMax = Math.max(forwardLabelsKeptReachableMax, label.extensionCardinality);
	}

	private void recordDepthCount(long[] histogram, int depth) {
		if (histogram == null || depth < 0) {
			return;
		}
		int bucket = Math.min(depth, histogram.length - 1);
		histogram[bucket]++;
	}

	private String formatDepthHistogram(long[] histogram) {
		if (histogram == null) {
			return "-";
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < histogram.length; i++) {
			if (histogram[i] == 0) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append(';');
			}
			builder.append(i).append(':').append(histogram[i]);
		}
		return builder.length() == 0 ? "-" : builder.toString();
	}

	private String formatAverage(long sum, long count) {
		if (count <= 0) {
			return "0.000";
		}
		return String.format("%.3f", ((double) sum) / count);
	}

	private int formatReachableMin() {
		return forwardLabelsKeptReachableMin == Integer.MAX_VALUE ? 0 : forwardLabelsKeptReachableMin;
	}

	private void recordPricingDiagnostics(LP lp) {
		Node node = lp.getNode();
		int forbidden = 0;
		int pricingOnlyForbidden = 0;
		int allowedDualNonZero = 0;
		int forbiddenDualNonZero = 0;
		double allowedDualMin = Utility.big_M;
		double allowedDualMax = -Utility.big_M;
		double allowedDualAbsSum = 0.0;
		double forbiddenDualAbsSum = 0.0;
		if (node != null) {
			for (int from = 1; from <= data.n; from++) {
				for (int to = 1; to <= data.n; to++) {
					if (from == to) {
						continue;
					}
					boolean arcForbidden = node.isArcForbidden(from, to);
					if (arcForbidden) {
						forbidden++;
					}
					if (node.isPricingOnlyArcForbidden(from, to)) {
						pricingOnlyForbidden++;
					}
					double dual = lp.getArcDual(from, to);
					if (isDiagnosticNonZero(dual)) {
						if (arcForbidden) {
							forbiddenDualNonZero++;
							forbiddenDualAbsSum += Math.abs(dual);
						} else {
							allowedDualNonZero++;
							allowedDualMin = Math.min(allowedDualMin, dual);
							allowedDualMax = Math.max(allowedDualMax, dual);
							allowedDualAbsSum += Math.abs(dual);
						}
					}
				}
			}
		}
		recordRestrictedColumnDiagnostics(lp, node);
		recordSinkArcDualDiagnostics(lp, node);
		diagnosticMachineDual = lp.getMachineDual();
		double min = Utility.big_M;
		double max = -Utility.big_M;
		double sum = 0.0;
		int positive = 0;
		double[] jobDuals = new double[data.n];
		for (int job = 1; job <= data.n; job++) {
			double dual = lp.getJobDual(job);
			jobDuals[job - 1] = dual;
			min = Math.min(min, dual);
			max = Math.max(max, dual);
			sum += dual;
			if (Utility.compareGt(dual, 0.0)) {
				positive++;
			}
		}
		diagnosticForbiddenJobArcCount = forbidden;
		diagnosticPricingOnlyJobArcCount = pricingOnlyForbidden;
		diagnosticJobDualPositiveCount = positive;
		diagnosticJobDualMin = Utility.isBigMValue(min) ? 0.0 : min;
		diagnosticJobDualMax = Utility.isBigMValue(-max) ? 0.0 : max;
		diagnosticJobDualSum = sum;
		diagnosticJobDualQuantiles = computeQuantiles(jobDuals);
		diagnosticAllowedJobArcDualNonZeroCount = allowedDualNonZero;
		diagnosticForbiddenJobArcDualNonZeroCount = forbiddenDualNonZero;
		diagnosticAllowedJobArcDualMin = Utility.isBigMValue(allowedDualMin) ? 0.0 : allowedDualMin;
		diagnosticAllowedJobArcDualMax = Utility.isBigMValue(-allowedDualMax) ? 0.0 : allowedDualMax;
		diagnosticAllowedJobArcDualAbsSum = allowedDualAbsSum;
		diagnosticForbiddenJobArcDualAbsSum = forbiddenDualAbsSum;
	}

	private void recordRestrictedColumnDiagnostics(LP lp, Node node) {
		diagnosticRestrictedColumnCount = lp.getRestrictedColumnIds().size();
		if (diagnosticRestrictedColumnCount == 0) {
			diagnosticIncompatibleRestrictedColumnCount = 0;
			diagnosticRestrictedColumnAvgLength = 0.0;
			return;
		}
		int incompatible = 0;
		long totalLength = 0;
		for (int columnId : lp.getRestrictedColumnIds()) {
			TWETColumn column = lp.getPool().getColumn(columnId);
			if (node != null && !node.isColumnCompatible(column)) {
				incompatible++;
			}
			totalLength += column.getSequence().size();
		}
		diagnosticIncompatibleRestrictedColumnCount = incompatible;
		diagnosticRestrictedColumnAvgLength = ((double) totalLength) / diagnosticRestrictedColumnCount;
	}

	private void recordSinkArcDualDiagnostics(LP lp, Node node) {
		int sink = node == null ? data.n + 1 : node.sinkId();
		int nonZero = 0;
		double min = Utility.big_M;
		double max = -Utility.big_M;
		double absSum = 0.0;
		for (int job = 1; job <= data.n; job++) {
			double dual = lp.getArcDual(job, sink);
			if (!isDiagnosticNonZero(dual)) {
				continue;
			}
			nonZero++;
			min = Math.min(min, dual);
			max = Math.max(max, dual);
			absSum += Math.abs(dual);
		}
		diagnosticSinkArcDualNonZeroCount = nonZero;
		diagnosticSinkArcDualMin = Utility.isBigMValue(min) ? 0.0 : min;
		diagnosticSinkArcDualMax = Utility.isBigMValue(-max) ? 0.0 : max;
		diagnosticSinkArcDualAbsSum = absSum;
	}

	private boolean isDiagnosticNonZero(double value) {
		return Utility.compareGt(Math.abs(value), 1e-8);
	}

	private double[] computeQuantiles(double[] values) {
		if (values == null || values.length == 0) {
			return new double[0];
		}
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		double[] probabilities = new double[] {0.0, 0.10, 0.25, 0.50, 0.75, 0.90, 1.0};
		double[] quantiles = new double[probabilities.length];
		for (int i = 0; i < probabilities.length; i++) {
			quantiles[i] = quantile(sorted, probabilities[i]);
		}
		return quantiles;
	}

	private double quantile(double[] sorted, double probability) {
		if (sorted.length == 1) {
			return sorted[0];
		}
		double position = probability * (sorted.length - 1);
		int lower = (int) Math.floor(position);
		int upper = (int) Math.ceil(position);
		if (lower == upper) {
			return sorted[lower];
		}
		double weight = position - lower;
		return sorted[lower] * (1.0 - weight) + sorted[upper] * weight;
	}

	private String formatJobDualQuantiles() {
		if (diagnosticJobDualQuantiles == null || diagnosticJobDualQuantiles.length == 0) {
			return "-";
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < diagnosticJobDualQuantiles.length; i++) {
			if (i > 0) {
				builder.append('/');
			}
			builder.append(String.format("%.3f", diagnosticJobDualQuantiles[i]));
		}
		return builder.toString();
	}

	private double joinLowerBoundThreshold() {
		if ((joinBestThresholdMode == JoinBestThresholdMode.BEST_UB
				|| joinBestThresholdMode == JoinBestThresholdMode.BEST_RECORD)
				&& Utility.compareLt(bestGeneratedReducedCost, REDUCED_COST_TOLERANCE)) {
			return bestGeneratedReducedCost;
		}
		return REDUCED_COST_TOLERANCE;
	}

	private boolean shouldKeepJoinedReducedCost(double reducedCost) {
		double threshold = joinBestThresholdMode == JoinBestThresholdMode.BEST_RECORD
				? joinLowerBoundThreshold() : REDUCED_COST_TOLERANCE;
		return Utility.compareLt(reducedCost, threshold);
	}

	private double completionBoundCutoff() {
		// 2026-06-01: completion bound 闁告瑯浜滈崹浠嬪棘?label 闁哄嫷鍨伴幆浣规交濡灝鍘撮悶娑栧劜閸ㄦ氨鎷归悢宄扮仚闁?
		// 濞戞挸绉虫繛鍥偨閵娿儳绉奸柛?best reduced cost闁挎稑鐭傛导鈺呭礂瀹ュ懎缍侀柟?record-only 闁告搩浜濋悘濠囩嵁閺堝灚涓㈤柟?top-K 閻犳劗鍠庨崹顏堝Υ?
		return REDUCED_COST_TOLERANCE;
	}

	private double completionBoundCutoffForSummary() {
		return Double.isNaN(completionBoundLastEvaluationCutoff)
				? completionBoundCutoff() : completionBoundLastEvaluationCutoff;
	}

	private String completionBoundRelaxationForSummary() {
		return completionBoundRelaxation == null ? "OFF" : completionBoundRelaxation.toString();
	}

	private void buildCompletionBounds(LP lp) {
		if (completionBoundRelaxation == null) {
			return;
		}
		long start = System.nanoTime();
		CompletionBoundCalculator calculator = new CompletionBoundCalculator(data, lp, pricingHorizon,
				completionForwardPenaltyByJob, completionBackwardPenaltyByJob, zeroDualExcludedJobs,
				completionBoundQueueOrdering, config.bidirectionalCompletionBoundScalarPruning,
				ignorePricingOnlyArcsForNode(lp.getNode()));
		CompletionBoundCalculator.Result result = calculator.build(completionBoundRelaxation);
		completionBounds = result.bounds;
		recordCompletionBoundStats(result.stats);
		completionBoundBuildNanos += System.nanoTime() - start;
		maybeDumpCompletionBoundMinDiagnostic(lp);
		evaluateCompletionBoundArcFixing(lp);
	}

	private boolean tryApplyCompletionBoundPreCertificate(LP lp) {
		if (completionBounds == null || completionBoundRelaxation == null
				|| shouldSkipCompletionBoundPreCertificateBecauseTimeIndexedPreHeuristicCoversIt(lp)) {
			return false;
		}
		double lowerBound = completionBoundForwardSinkLowerBound(lp);
		if (!Double.isFinite(lowerBound) || Utility.isBigMValue(lowerBound)
				|| Utility.compareLt(lowerBound, REDUCED_COST_TOLERANCE)) {
			return false;
		}
		completionBoundPreCertificateClosed = true;
		lastRelaxedRoundBestReducedCost = lowerBound;
		lastMessage = "GCNGBB-style ng-DSSR bidirectional completion-bound pre-certificate closed internal pricing"
				+ " bound=" + lowerBound;
		return true;
	}

	private boolean shouldSkipCompletionBoundPreCertificateBecauseTimeIndexedPreHeuristicCoversIt(LP lp) {
		if (!data.isExactIntegerTimeInstance()) {
			return false;
		}
		// 2026-07-06: integer instances can also use this completion-bound certificate,
		// but when time-indexed pre-heuristic is active on a no-cut pass it already provides
		// the corresponding discrete graph certificate, so this O(n) check would be duplicate work.
		return config.enableTimeIndexedPreHeuristicPricing
				&& lp != null
				&& lp.getActiveCutIds().isEmpty();
	}

	private double completionBoundForwardSinkLowerBound(LP lp) {
		Node node = lp == null ? null : lp.getNode();
		int sink = node == null ? data.n + 1 : node.sinkId();
		double best = Utility.big_M;
		for (int job = 1; job <= data.n; job++) {
			if (node != null && isPricingArcForbidden(node, job, sink)) {
				continue;
			}
			double prefixLowerBound = completionBounds.forwardFMin(job);
			if (!Double.isFinite(prefixLowerBound) || Utility.isBigMValue(prefixLowerBound)) {
				continue;
			}
			double lowerBound = prefixLowerBound - lp.getArcDual(job, sink);
			if (Utility.compareLt(lowerBound, best)) {
				best = lowerBound;
			}
		}
		return Utility.isBigMValue(best) ? 0.0 : best;
	}

	/**
	 * 2026-06-09: 閻犲洤锕ラ弻?required adjacency dual 闁哄嫷鍨伴幆渚€骞?relaxed suffix 濞戞挸顑囬弲顐﹀储鐎ｎ亞绻侀弶鈺佹矗缂嶅棝濡?
	 * 闁告瑯浜濈€垫粎鍖栭懡銈囧煚閻忕偟鍋為埀顑嫭鈻旂€殿喖绻戠€垫氨鈧淇烘俊顓㈡倷鐟欏嫭顦ч弶鍫熸尭閸ゎ參鏁嶇仦鑲╃憹鐟滄澘宕幖宄邦潰閿濆懐纭€ pricing 閻犲浂鍘虹粻鐔煎Υ?
	 */
	private void maybeDumpCompletionBoundMinDiagnostic(LP lp) {
		Node currentNode = lp == null ? null : lp.getNode();
		if (currentNode == null || completionBounds == null) {
			return;
		}
		int targetNodeId = Integer.getInteger("twet.bpc.completionBoundMinDiagnosticNodeId", -1);
		if (targetNodeId < 0 || currentNode.id != targetNodeId) {
			return;
		}

		int count = 0;
		int negative = 0;
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		double sum = 0.0;
		StringBuilder detail = new StringBuilder();
		for (int job = 1; job <= data.n; job++) {
			PiecewiseLinearFunction function = completionBounds.backwardRByJob[job];
			if (function == null || function.head == null) {
				detail.append(" job=").append(job).append(":NA");
				continue;
			}
			double[] argmin = function.findMinimal(false, true);
			if (argmin == null || argmin.length < 2 || !Double.isFinite(argmin[0])) {
				detail.append(" job=").append(job).append(":NA");
				continue;
			}
			double value = argmin[0];
			double time = argmin[1];
			count++;
			sum += value;
			min = Math.min(min, value);
			max = Math.max(max, value);
			if (Utility.compareLt(value, 0.0)) {
				negative++;
			}
			detail.append(" job=").append(job).append(":").append(value).append("@").append(time);
		}
		double avg = count == 0 ? Double.NaN : sum / count;
		System.out.println("[completionBoundMinDiagnostic] node=" + currentNode.id
				+ " relaxation=" + completionBoundRelaxationForSummary()
				+ " backwardR count/negative/min/max/avg=" + count + "/" + negative
				+ "/" + min + "/" + max + "/" + avg);
		for (int[] pair : currentNode.getRequiredAdjacencyPairs()) {
			int first = pair[0];
			int second = pair[1];
			System.out.println("[completionBoundMinDiagnostic] requiredAdjacency=" + first + "-" + second
					+ " arcDual(" + first + "," + second + ")=" + lp.getArcDual(first, second)
					+ " arcDual(" + second + "," + first + ")=" + lp.getArcDual(second, first));
		}
		System.out.println("[completionBoundMinDiagnostic] backwardRByJob" + detail.toString());
	}

	private void evaluateCompletionBoundArcFixing(LP lp) {
		if ((!config.bidirectionalCompletionBoundArcFixingDiagnostic
				&& !config.bidirectionalCompletionBoundArcFixing) || completionBounds == null) {
			return;
		}
		if (config.bidirectionalCompletionBoundArcFixing) {
			completionBoundFixedArc = new boolean[data.n + 1][data.n + 1];
		}
		long start = System.nanoTime();
		Node node = lp.getNode();
		double cutoff = completionBoundCutoff();
		for (int fromJob = 1; fromJob <= data.n; fromJob++) {
			for (int toJob = 1; toJob <= data.n; toJob++) {
				if (fromJob == toJob || isZeroDualExcludedJob(fromJob) || isZeroDualExcludedJob(toJob)
						|| node.isArcForbidden(fromJob, toJob)
						|| (!ignorePricingOnlyArcsForNode(node) && node.isPricingOnlyArcForbidden(fromJob, toJob))) {
					continue;
				}
				completionBoundArcFixingCandidates++;
				PiecewiseLinearFunction prefix = completionBounds.forwardFByJob[fromJob];
				PiecewiseLinearFunction suffix = completionBounds.backwardBByJob[toJob];
				if (prefix == null || prefix.head == null || suffix == null || suffix.head == null) {
					completionBoundArcFixingUnavailable++;
					continue;
				}
				double delay = data.getSetUp(fromJob, toJob) + data.getProcessT(toJob);
				if (isCompletionBoundArcTimeDisjoint(prefix, suffix, delay)) {
					rememberCompletionBoundFixedArc(fromJob, toJob, true);
					continue;
				}
				double fixedReducedCost = data.getSetupCost(fromJob, toJob) - lp.getArcDual(fromJob, toJob);
				if (isCompletionBoundArcScalarPruned(fromJob, toJob, fixedReducedCost, cutoff)) {
					rememberCompletionBoundFixedArc(fromJob, toJob, false);
					completionBoundArcFixingScalarPruned++;
					continue;
				}
				completionBoundArcFixingFunctionEvaluations++;
				double lowerBound = PiecewiseLinearFunction.findMinimalShiftedSumValue(prefix, delay, suffix,
						fixedReducedCost);
				if (!Utility.compareLt(lowerBound, cutoff)) {
					rememberCompletionBoundFixedArc(fromJob, toJob, false);
				}
			}
		}
		completionBoundArcFixingNanos += System.nanoTime() - start;
	}

	private boolean isCompletionBoundArcTimeDisjoint(PiecewiseLinearFunction prefix, PiecewiseLinearFunction suffix,
			double delay) {
		double shiftedStart = Math.max(prefix.head.start + delay, prefix.domainStart);
		double shiftedEnd = Math.min(prefix.tail.end + delay, prefix.domainEnd);
		double start = Math.max(shiftedStart, suffix.head.start);
		double end = Math.min(shiftedEnd, suffix.tail.end);
		return Utility.compareLt(end, start);
	}

	private boolean isCompletionBoundArcScalarPruned(int fromJob, int toJob, double fixedReducedCost, double cutoff) {
		double prefixMin = completionBounds.forwardFMin(fromJob);
		double suffixMin = completionBounds.backwardBMin(toJob);
		if (Utility.isBigMValue(prefixMin) || Utility.isBigMValue(suffixMin)) {
			return false;
		}
		double lowerBound = prefixMin + suffixMin + fixedReducedCost;
		return !Utility.compareLt(lowerBound, cutoff);
	}

	private void rememberCompletionBoundFixedArc(int fromJob, int toJob, boolean domainPruned) {
		completionBoundArcFixingFixed++;
		if (domainPruned) {
			completionBoundArcFixingDomainPruned++;
		}
		if (completionBoundFixedArc != null) {
			completionBoundFixedArc[fromJob][toJob] = true;
		}
	}

	private boolean isCompletionBoundArcFixed(int fromJob, int toJob) {
		return fromJob > 0 && fromJob <= data.n && toJob > 0 && toJob <= data.n
				&& completionBoundFixedArc != null && completionBoundFixedArc[fromJob][toJob];
	}

	private boolean isPricingArcForbidden(Node node, int fromJob, int toJob) {
		return node.isArcForbidden(fromJob, toJob)
				|| (!ignorePricingOnlyArcsForNode(node) && node.isPricingOnlyArcForbidden(fromJob, toJob))
				|| isCompletionBoundArcFixed(fromJob, toJob);
	}

	private boolean ignorePricingOnlyArcsForNode(Node node) {
		return node != null && config.debugIgnorePricingOnlyArcsAtNode >= 0
				&& node.id == config.debugIgnorePricingOnlyArcsAtNode;
	}

	private void recordCompletionBoundStats(CompletionBoundCalculator.Stats stats) {
		if (stats == null) {
			return;
		}
		completionBoundForwardBuildNanos += stats.forwardBuildNanos;
		completionBoundBackwardBuildNanos += stats.backwardBuildNanos;
		completionBoundAggregateNanos += stats.aggregateNanos;
		completionBoundForwardCandidateAttempts += stats.forwardCandidateAttempts;
		completionBoundBackwardCandidateAttempts += stats.backwardCandidateAttempts;
		completionBoundForwardQueuePops += stats.forwardQueuePops;
		completionBoundBackwardQueuePops += stats.backwardQueuePops;
		completionBoundPriorityQueueStalePops += stats.priorityQueueStalePops;
		completionBoundMergeCalls += stats.mergeCalls;
		completionBoundMergeChanged += stats.mergeChanged;
		completionBoundForwardSegmentSamples += stats.forwardSegmentSamples;
		completionBoundForwardTargetSegments += stats.forwardTargetSegments;
		completionBoundForwardCandidateSegments += stats.forwardCandidateSegments;
		completionBoundForwardAfterSegments += stats.forwardAfterSegments;
		completionBoundForwardMaxTargetSegments = Math.max(completionBoundForwardMaxTargetSegments,
				stats.forwardMaxTargetSegments);
		completionBoundForwardMaxCandidateSegments = Math.max(completionBoundForwardMaxCandidateSegments,
				stats.forwardMaxCandidateSegments);
		completionBoundForwardMaxAfterSegments = Math.max(completionBoundForwardMaxAfterSegments,
				stats.forwardMaxAfterSegments);
		completionBoundBackwardSegmentSamples += stats.backwardSegmentSamples;
		completionBoundBackwardTargetSegments += stats.backwardTargetSegments;
		completionBoundBackwardCandidateSegments += stats.backwardCandidateSegments;
		completionBoundBackwardAfterSegments += stats.backwardAfterSegments;
		completionBoundBackwardMaxTargetSegments = Math.max(completionBoundBackwardMaxTargetSegments,
				stats.backwardMaxTargetSegments);
		completionBoundBackwardMaxCandidateSegments = Math.max(completionBoundBackwardMaxCandidateSegments,
				stats.backwardMaxCandidateSegments);
		completionBoundBackwardMaxAfterSegments = Math.max(completionBoundBackwardMaxAfterSegments,
				stats.backwardMaxAfterSegments);
	}

	private boolean isForwardCompletionBoundPruned(ForwardLabel label) {
		if (completionBounds == null || label.jid <= 0 || label.jid > data.n || label.noSriFrontier == null
				|| label.noSriFrontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction suffix = completionBounds.backwardRByJob[label.jid];
		if (suffix == null || suffix.head == null) {
			return false;
		}
		double cutoff = completionBoundCutoff();
		completionBoundLastEvaluationCutoff = cutoff;
		if (config.bidirectionalCompletionBoundScalarPruning
				&& isForwardCompletionBoundScalarPruned(label, cutoff)) {
			return true;
		}
		completionBoundFunctionEvaluations++;
		// 2026-06-13: under active SRI, this completion-bound pruning uses no-SRI frontier.
		if (!hasCommonCompletionDomain(label.noSriFrontier, suffix)) {
			return false;
		}
		double lowerBound = PiecewiseLinearFunction.findMinimalSumValue(label.noSriFrontier, suffix, 0.0);
		return !Utility.compareLt(lowerBound, cutoff);
	}

	private boolean isBackwardCompletionBoundPruned(BackwardLabel label) {
		if (completionBounds == null || label.isSinkRoot || label.jid <= 0 || label.jid > data.n
				|| label.noSriFrontier == null || label.noSriFrontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction prefix = completionBounds.forwardUByJob[label.jid];
		if (prefix == null || prefix.head == null) {
			return false;
		}
		double cutoff = completionBoundCutoff();
		completionBoundLastEvaluationCutoff = cutoff;
		if (config.bidirectionalCompletionBoundScalarPruning
				&& isBackwardCompletionBoundScalarPruned(label, cutoff)) {
			return true;
		}
		completionBoundFunctionEvaluations++;
		// 2026-06-13: symmetric to forward pruning, use no-SRI frontier here.
		if (!hasCommonCompletionDomain(prefix, label.noSriFrontier)) {
			return false;
		}
		double lowerBound = PiecewiseLinearFunction.findMinimalSumValue(prefix, label.noSriFrontier, 0.0);
		return !Utility.compareLt(lowerBound, cutoff);
	}


	private boolean hasCommonCompletionDomain(PiecewiseLinearFunction left, PiecewiseLinearFunction right) {
		double start = Math.max(left.head.start, right.head.start);
		double end = Math.min(left.tail.end, right.tail.end);
		return !Utility.compareLt(end, start);
	}

	private boolean isForwardCompletionBoundScalarPruned(ForwardLabel label, double cutoff) {
		completionBoundScalarChecks++;
		double suffixLowerBound = completionBounds.backwardRAfterFloor(label.jid, label.noSriFrontier.head.start);
		if (Utility.isBigMValue(suffixLowerBound)) {
			completionBoundScalarUnavailable++;
			completionBoundScalarPruned++;
			return true;
		}
		double originalScalarLowerBound = label.noSriMinReducedCost + suffixLowerBound;
		double timeIndexedSuffix = timeIndexedScalarBound == null ? Utility.big_M
				: timeIndexedScalarBound.suffixLowerBoundAfterFloor(label.jid, label.noSriFrontier.head.start);
		if (!Utility.isBigMValue(timeIndexedSuffix) && Utility.compareGt(timeIndexedSuffix, suffixLowerBound)) {
			suffixLowerBound = timeIndexedSuffix;
			timeIndexedScalarImprovedChecks++;
		} else if (timeIndexedScalarBound != null && Utility.isBigMValue(timeIndexedSuffix)) {
			timeIndexedScalarUnavailable++;
		}
		double scalarLowerBound = label.noSriMinReducedCost + suffixLowerBound;
		if (!Utility.compareLt(scalarLowerBound, cutoff)) {
			completionBoundScalarPruned++;
			if (Utility.compareLt(originalScalarLowerBound, cutoff)) {
				timeIndexedScalarExtraPruned++;
			}
			return true;
		}
		completionBoundScalarFunctionFallbacks++;
		return false;
	}

	private boolean isBackwardCompletionBoundScalarPruned(BackwardLabel label, double cutoff) {
		completionBoundScalarChecks++;
		double prefixLowerBound = isAtPricingHorizon(label.noSriFrontier.tail.end)
				? completionBounds.forwardUMin(label.jid)
				: completionBounds.forwardUBeforeCeil(label.jid, label.noSriFrontier.tail.end);
		if (Utility.isBigMValue(prefixLowerBound)) {
			completionBoundScalarUnavailable++;
			completionBoundScalarPruned++;
			return true;
		}
		double originalScalarLowerBound = label.noSriMinReducedCost + prefixLowerBound;
		double timeIndexedPrefix = timeIndexedScalarBound == null ? Utility.big_M
				: timeIndexedScalarBound.prefixLowerBoundBeforeCeil(label.jid, label.noSriFrontier.tail.end);
		if (!Utility.isBigMValue(timeIndexedPrefix) && Utility.compareGt(timeIndexedPrefix, prefixLowerBound)) {
			prefixLowerBound = timeIndexedPrefix;
			timeIndexedScalarImprovedChecks++;
		} else if (timeIndexedScalarBound != null && Utility.isBigMValue(timeIndexedPrefix)) {
			timeIndexedScalarUnavailable++;
		}
		double scalarLowerBound = label.noSriMinReducedCost + prefixLowerBound;
		if (!Utility.compareLt(scalarLowerBound, cutoff)) {
			completionBoundScalarPruned++;
			if (Utility.compareLt(originalScalarLowerBound, cutoff)) {
				timeIndexedScalarExtraPruned++;
			}
			return true;
		}
		completionBoundScalarFunctionFallbacks++;
		return false;
	}

	private boolean isAtPricingHorizon(double time) {
		return Utility.compareEq(time, pricingHorizon);
	}

	private void updateBestGeneratedReducedCost(double reducedCost) {
		if (Utility.compareLt(reducedCost, bestGeneratedReducedCost)) {
			bestGeneratedReducedCost = reducedCost;
		}
	}

	/**
	 * 2026-05-23: join 闁告挸绉虫径宥夊籍閼搁潧惟 forward 闁告锕ら悡娆撳矗閸忓懏娅犵€点倛鍩栫€氬洦绋?f(Tmid)闁?
	 * 閺夆晜鐟﹀Σ鍝ユ媼閻戞ɑ鐎悗鍦仧楠炲洭鏌屽畝鈧▓?join 閺夊牆鎳庢慨顏堝礄閼恒儲娈堕柨娑樺缁楀宕樺▎蹇旂 label闁?
	 */
	private PiecewiseLinearFunction getForwardJoinExtension(ForwardLabel label) {
		if (label.joinExtendedFrontier == null) {
			label.joinExtendedFrontier = buildForwardJoinExtension(label.frontier);
		}
		return label.joinExtendedFrontier;
	}

	private PiecewiseLinearFunction buildForwardJoinExtension(PiecewiseLinearFunction forward) {
		PiecewiseLinearFunction extended = new PiecewiseLinearFunction(0.0, pricingHorizon);
		appendSegments(extended, forward);
		if (forward != null && forward.tail != null && Utility.compareLt(forward.tail.end, pricingHorizon)) {
			addConstantSegmentOrPoint(extended, forward.tail.end, pricingHorizon, valueAtOrNearest(forward, tMid));
		}
		mergeAdjacentEqualSegments(extended);
		return extended;
	}

	/**
	 * 2026-05-23: join 闁告挸绉虫径宥夊籍閼搁潧惟 backward 闁告锕ら悡娆忣啅閿旀寧娅犵€点倛鍩栫€氬洦绋?f_b(Tmid)闁?
	 * 閺夆晜鐟﹀Σ鍝ユ媼閻戞ɑ鐎悗鍦仧楠炲洭鏌屽畝鈧▓?join 閺夊牆鎳庢慨顏堝礄閼恒儲娈堕柨娑樺缁楀宕樺▎蹇旂 label闁?
	 */
	private PiecewiseLinearFunction getBackwardJoinExtension(BackwardLabel label) {
		if (label.joinExtendedFrontier == null) {
			label.joinExtendedFrontier = buildBackwardJoinExtension(label.frontier);
		}
		return label.joinExtendedFrontier;
	}

	private PiecewiseLinearFunction buildBackwardJoinExtension(PiecewiseLinearFunction backward) {
		PiecewiseLinearFunction extended = new PiecewiseLinearFunction(0.0, pricingHorizon);
		if (backward != null && backward.head != null && Utility.compareLt(0.0, backward.head.start)) {
			addConstantSegmentOrPoint(extended, 0.0, backward.head.start, valueAtOrNearest(backward, tMid));
		}
		appendSegments(extended, backward);
		mergeAdjacentEqualSegments(extended);
		return extended;
	}

	private double valueAtOrNearest(PiecewiseLinearFunction function, double t) {
		if (function == null || function.head == null) {
			return Utility.big_M;
		}
		if (!Utility.compareLt(t, function.head.start) && !Utility.compareGt(t, function.tail.end)) {
			return function.evaluate(t);
		}
		if (Utility.compareLt(t, function.head.start)) {
			return function.evaluate(function.head.start);
		}
		return function.evaluate(function.tail.end);
	}

	private void tryGenerateColumn(ArrayList<Integer> sequence, LP lp, double inferredReducedCost) {
		observeRelaxedReducedCost(inferredReducedCost);
		if (sequence.isEmpty() || config.maxExactPricingColumns <= 0) {
			return;
		}
		boolean targetSequence = isTargetSequence(sequence);
		boolean elementary = isElementarySequence(sequence);
		if (!elementary && !config.ngDssrReturnRelaxedColumns) {
			if (targetSequence) {
				traceTarget("COLUMN_REJECT nonElementary inferredRC=" + inferredReducedCost);
			}
			recordNonElementaryNegativeSequence(sequence, inferredReducedCost);
			return;
		}
		if (!elementary && targetSequence) {
			traceTarget("COLUMN_CANDIDATE ngRelaxed inferredRC=" + inferredReducedCost);
		}
		SequenceSignature signature = new SequenceSignature(sequence);
		if (activeColumnSignatures.contains(signature)) {
			if (targetSequence) {
				traceTarget("COLUMN_REJECT alreadyActive inferredRC=" + inferredReducedCost);
			}
			return;
		}
		if (Utility.compareLt(inferredReducedCost, REDUCED_COST_TOLERANCE)) {
			if (joinBestThresholdMode == JoinBestThresholdMode.BEST_RECORD
					&& !Utility.compareLt(inferredReducedCost, joinLowerBoundThreshold())) {
				generatedCandidateDroppedByHeap++;
				if (targetSequence) {
					traceTarget("COLUMN_REJECT bestRecordThreshold inferredRC=" + inferredReducedCost);
				}
				return;
			}
			if (targetSequence) {
				traceTarget("COLUMN_CANDIDATE inferredRC=" + inferredReducedCost);
			}
			rememberGeneratedCandidate(signature, PricingColumnCostRechecker.buildInferredColumn(sequence,
					inferredReducedCost, lp, data, ColumnSource.PRICING_EXACT), inferredReducedCost);
		}
	}

	private boolean isElementarySequence(ArrayList<Integer> sequence) {
		boolean[] seen = new boolean[data.n + 1];
		for (int i = 0; i < sequence.size(); i++) {
			int job = sequence.get(i).intValue();
			if (job <= 0 || job > data.n) {
				continue;
			}
			if (seen[job]) {
				return false;
			}
			seen[job] = true;
		}
		return true;
	}

	private void recordNonElementaryNegativeSequence(ArrayList<Integer> sequence, double inferredReducedCost) {
		if (!Utility.compareLt(inferredReducedCost, REDUCED_COST_TOLERANCE)
				|| nonElementaryNegativeRoutes == null) {
			return;
		}
		ngDssrRoundNonElementaryNegativeSeen++;
		int limit = Math.max(1, config.ngDssrNonElementaryRouteUpdateLimit);
		for (int i = 0; i < nonElementaryNegativeRoutes.size(); i++) {
			NonElementaryNegativeRoute route = nonElementaryNegativeRoutes.get(i);
			if (route.sequence.equals(sequence)) {
				if (Utility.compareLt(inferredReducedCost, route.reducedCost)) {
					nonElementaryNegativeRoutes.set(i,
							new NonElementaryNegativeRoute(sequence, inferredReducedCost));
					sortNonElementaryNegativeRoutes();
				}
				return;
			}
		}
		if (nonElementaryNegativeRoutes.size() < limit) {
			nonElementaryNegativeRoutes.add(new NonElementaryNegativeRoute(sequence, inferredReducedCost));
			sortNonElementaryNegativeRoutes();
			return;
		}
		NonElementaryNegativeRoute worst = nonElementaryNegativeRoutes.get(nonElementaryNegativeRoutes.size() - 1);
		if (Utility.compareLt(inferredReducedCost, worst.reducedCost)) {
			nonElementaryNegativeRoutes.set(nonElementaryNegativeRoutes.size() - 1,
					new NonElementaryNegativeRoute(sequence, inferredReducedCost));
			sortNonElementaryNegativeRoutes();
		}
	}

	private void observeRelaxedReducedCost(double reducedCost) {
		if (Utility.compareLt(reducedCost, lastRelaxedRoundBestReducedCost)) {
			lastRelaxedRoundBestReducedCost = reducedCost;
		}
	}

	private void sortNonElementaryNegativeRoutes() {
		Collections.sort(nonElementaryNegativeRoutes, new Comparator<NonElementaryNegativeRoute>() {
			@Override
			public int compare(NonElementaryNegativeRoute left, NonElementaryNegativeRoute right) {
				int byCost = compareDoubleAsc(left.reducedCost, right.reducedCost);
				if (byCost != 0) {
					return byCost;
				}
				int bySize = Integer.compare(left.sequence.size(), right.sequence.size());
				if (bySize != 0) {
					return bySize;
				}
				return compareSequence(left.sequence, right.sequence);
			}
		});
	}

	private int compareSequence(ArrayList<Integer> left, ArrayList<Integer> right) {
		int size = Math.min(left.size(), right.size());
		for (int i = 0; i < size; i++) {
			int diff = Integer.compare(left.get(i).intValue(), right.get(i).intValue());
			if (diff != 0) {
				return diff;
			}
		}
		return Integer.compare(left.size(), right.size());
	}

	private void rememberGeneratedCandidate(SequenceSignature signature, TWETColumn column, double reducedCost) {
		generatedCandidateCount++;
		boolean targetSignature = isTargetSignature(signature);
		PricingColumnCandidate candidate = new PricingColumnCandidate(nextCandidateId++, signature, column,
				reducedCost);
		PricingColumnCandidate existing = generatedCandidateBySignature.get(signature);
		if (existing != null && compareCandidateBestFirst(candidate, existing) >= 0) {
			generatedCandidateDroppedByHeap++;
			if (targetSignature) {
				traceTarget("COLUMN_DROP duplicate reducedCost=" + reducedCost);
			}
			return;
		}
		updateBestGeneratedReducedCost(reducedCost);
		generatedCandidateBySignature.put(signature, candidate);
		generatedColumnCandidates.add(candidate);
		pruneGeneratedCandidatePool();
		if (targetSignature) {
			traceTarget("COLUMN_KEEP bestBySignature reducedCost=" + reducedCost);
		}
	}

	private void pruneGeneratedCandidatePool() {
		// 2026-06-16: 闁告艾濂旂粩?sequence 闁告瑯鍨抽弫杈ㄥ緞濮橆偊鍤?split 闁汇垻鍠愰崹姘舵晬濞戞瑦锛嬮柛濠冪懇閳ь剙顦遍弳鈧柛锔哄妼閻栥垺绋夐銊хmap 闁告瑯浜欑换姘舵偩濞嗗繒绉奸柛鎾崇У濞撹埖瀵煎Ο琛″亾濞嗘挴鍋撴径鍫氬亾?
		while (generatedCandidateBySignature.size() > config.maxExactPricingColumns) {
			PricingColumnCandidate worstKept = pollCurrentWorstGeneratedCandidate();
			if (worstKept == null) {
				break;
			}
			generatedCandidateBySignature.remove(worstKept.signature);
			generatedCandidateDroppedByHeap++;
		}
	}

	private PricingColumnCandidate pollCurrentWorstGeneratedCandidate() {
		while (!generatedColumnCandidates.isEmpty()) {
			PricingColumnCandidate candidate = generatedColumnCandidates.poll();
			if (generatedCandidateBySignature.get(candidate.signature) == candidate) {
				return candidate;
			}
		}
		return null;
	}

	private void finalizeGeneratedColumns(LP lp) {
		generatedColumns.clear();
		ArrayList<PricingColumnCandidate> candidates = new ArrayList<PricingColumnCandidate>(
				generatedCandidateBySignature.values());
		Collections.sort(candidates, candidateBestFirstComparator());
		for (int i = 0; i < candidates.size(); i++) {
			PricingColumnCandidate candidate = candidates.get(i);
			if (!requiresExactColumnCostRecovery()) {
				generatedColumns.add(candidate.column);
				continue;
			}
			// 2026-05-31: 闁告瑯浜濆﹢渚€寮界涵鍛濋柣?no-cut pi-window 濞村吋淇洪鈧?K 闁割偄妫楅埀顒佺懇閳ь剙顦伴崹姘跺嫉椤掆偓瑜版稑顕ラ崟顐＄剨缂佹瘱浣插亾?
			// pi-window 闁哄嫷鍨扮敮?hard window 闁汇劌瀚悺娆撳礌濞差亝锛熼柨娑樿嫰濞叉粌顫?inferred 闁瑰瓨鍔栧﹢鐗堢▔瀹ュ嫮绉靛ù婊冩捣濠€锛勨偓鍦仜閸亪骞嬮幇顓熸嫳闁?
			// inferred reduced cost 鐎规瓕寮撶拹鐔烘嫻閻斿憡顦ч柨娑樼灱濠€锛勨偓?reduced cost 闁告瑯浜欑槐浼村即閺夋垹姣堥柨娑樼焷缁绘牠鏌岀仦钘夋锭濞ｅ浂鍠楅婊堝礆濡や礁鐏囬柡鍫厵閳?
			// 2026-06-13: SRI active 闁?inferred reduced cost 闁?cut dual闁挎稑濂旂粭澶愭嚄閽樺娑ч柟?machine/job/arc dual 闁告瑥绉电敮?objective cost闁?
			// 2026-06-15: partial dominance 濞村吋鑹剧敮顐﹀捶閹峰矈姊块柛鎿冧簼閹磭妲?frontier闁挎稑鏈?label 闁?minReducedCost
			// 濞戞挸绉撮崯鈧☉鎾亾閻庤姘ㄩ悺鎴炵?recovered sequence 闁汇劌瀚悾顒勫极閺夋垵鐏欓柟瀛樺姈濠€浼存晬濞戞ê娑ч柡?partial backend 闂傚洠鍋撻悷鏇氱濠€顏堝礂閵夛妇娼ㄩ柛鎾崇У娴狀喗寰勫鍥ㄥ焸閻庡湱鍋為崹姘跺嫉椤戦敮鍋?
			PricingColumnCostRechecker.Result checked = PricingColumnCostRechecker.evaluate(candidate.column, data,
					evaluator);
			if (checked != null) {
				generatedColumns.add(checked.checkedColumn(data));
			}
		}
	}

	private boolean requiresExactColumnCostRecovery() {
		return dualProfitableWindowEnabled || sriPricingEnabled || dominanceBackend != DominanceBackend.PAPER;
	}

	private boolean isSequenceCompatible(List<Integer> sequence, Node node) {
		if (PricingCompatibility.containsRequiredOutsourcedJob(node, sequence)) {
			return false;
		}
		if (isPricingArcForbidden(node, 0, sequence.get(0).intValue())) {
			return false;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (isPricingArcForbidden(node, sequence.get(i - 1).intValue(), sequence.get(i).intValue())) {
				return false;
			}
		}
		return !isPricingArcForbidden(node, sequence.get(sequence.size() - 1).intValue(), node.sinkId());
	}

	private boolean isDirectForwardExtensionTimeFeasible(PiecewiseLinearFunction frontier, int prevJob, int nextJob) {
		return isDirectForwardExtensionTimeFeasible(frontier, prevJob, nextJob, true);
	}

	private boolean isDirectForwardExtensionTimeFeasibleFullDomain(PiecewiseLinearFunction frontier, int prevJob,
			int nextJob) {
		return isDirectForwardExtensionTimeFeasible(frontier, prevJob, nextJob, false);
	}

	private boolean isDirectForwardExtensionTimeFeasible(PiecewiseLinearFunction frontier, int prevJob, int nextJob,
			boolean requireTmid) {
		if (frontier == null || frontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction jobPenalty = getDynamicForwardJobPenalty(prevJob, nextJob);
		if (jobPenalty == null) {
			return false;
		}
		double hStart = getDynamicForwardHStart(prevJob, nextJob);
		double hEnd = getDynamicForwardHEnd(prevJob, nextJob);
		double earliestCompletion = Math.max(
				frontier.head.start + data.getSetUp(prevJob, nextJob) + data.getProcessT(nextJob), hStart);
		return !Utility.compareGt(earliestCompletion, hEnd)
				&& (!requireTmid || !Utility.compareGt(earliestCompletion, tMid));
	}

	/**
	 * 2026-05-22: backward 濞撴皜鍐╁閻犱胶鍎ら弸鍐╃▔閳ь剟鎳涙潏鍓х闁稿繐鐗忛弫銈夊嫉椤掑啰鏋傚Λ鏉垮椤撳摜绮诲Δ鍐╃暠 H^b_{ir} 闁?O(1) 濞存嚎鍊濆▔锔芥交閸ャ劍濮㈤柨?
	 * 闁活亞鍠愰婊堟儍?reduced-cost 闁告垼濮ら弳鐔哥瀹ュ懏韬?extendBackward 闂佹彃鐭傞埀顒佷亢缁?shift/add/normalize 闂侇偅甯楃敮褰掑Υ?
	 */
	private boolean isDirectBackwardExtensionTimeFeasible(BackwardLabel label, int prevJob) {
		return isDirectBackwardExtensionTimeFeasible(label.jid, label.isSinkRoot, label.frontier, prevJob);
	}

	private boolean isDirectBackwardExtensionTimeFeasible(int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier, int prevJob) {
		return isDirectBackwardExtensionTimeFeasible(firstJob, isSinkRoot, frontier, prevJob, true);
	}

	private boolean isDirectBackwardExtensionTimeFeasibleFullDomain(int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier, int prevJob) {
		return isDirectBackwardExtensionTimeFeasible(firstJob, isSinkRoot, frontier, prevJob, false);
	}

	private boolean isDirectBackwardExtensionTimeFeasible(int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier, int prevJob, boolean requireTmid) {
		int successor = isSinkRoot ? data.n + 1 : firstJob;
		double rhoPrime;
		if (isSinkRoot) {
			rhoPrime = getDynamicBackwardHEnd(prevJob, successor);
		} else {
			double delay = data.getSetUp(prevJob, firstJob) + data.getProcessT(firstJob);
			rhoPrime = Math.min(frontier.tail.end - delay, getDynamicBackwardHEnd(prevJob, successor));
		}
		double hStart = getDynamicBackwardHStart(prevJob, successor);
		double lower = requireTmid ? Math.max(tMid, hStart) : hStart;
		return !Utility.compareLt(rhoPrime, lower);
	}

	private PackedBitSet updateNgMemory(PackedBitSet parentNgMemory, int currentJob) {
		PackedBitSet memory = parentNgMemory.and(ngNeighborhoodByJob[currentJob]);
		memory.add(currentJob);
		return memory;
	}

	private PackedBitSet buildForwardDominanceSet(int fromJob, PackedBitSet ngMemory, Node node,
			PiecewiseLinearFunction frontier) {
		PackedBitSet dominanceSet = new PackedBitSet(data.n + 2);
		for (int job = 1; job <= data.n; job++) {
			boolean unavailable = isZeroDualExcludedJob(job) || PricingCompatibility.isRequiredOutsourcedJob(node, job)
					|| ngMemory.contains(job)
					|| !isDirectForwardExtensionTimeFeasibleFullDomain(frontier, fromJob, job);
			if (!unavailable) {
				dominanceSet.add(job);
			}
		}
		return dominanceSet;
	}

	private PackedBitSet buildForwardExtensionSet(PackedBitSet dominanceSet, int fromJob,
			PiecewiseLinearFunction frontier) {
		PackedBitSet extensionSet = new PackedBitSet(data.n + 2);
		for (int job = dominanceSet.nextSetBit(1); job > 0 && job <= data.n;
				job = dominanceSet.nextSetBit(job + 1)) {
			if (isForwardHalfEligibleJob(job) && isDirectForwardExtensionTimeFeasible(frontier, fromJob, job)) {
				extensionSet.add(job);
			}
		}
		return extensionSet;
	}

	private PackedBitSet buildBackwardDominanceSet(int firstJob, PackedBitSet ngMemory, Node node,
			PiecewiseLinearFunction frontier) {
		PackedBitSet dominanceSet = new PackedBitSet(data.n + 2);
		boolean isSinkRoot = firstJob == node.sinkId();
		for (int job = 1; job <= data.n; job++) {
			boolean unavailable = isZeroDualExcludedJob(job) || PricingCompatibility.isRequiredOutsourcedJob(node, job)
					|| ngMemory.contains(job)
					|| !isDirectBackwardExtensionTimeFeasibleFullDomain(firstJob, isSinkRoot, frontier, job);
			if (!unavailable) {
				dominanceSet.add(job);
			}
		}
		return dominanceSet;
	}

	private PackedBitSet buildBackwardExtensionSet(PackedBitSet dominanceSet, int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier) {
		PackedBitSet extensionSet = new PackedBitSet(data.n + 2);
		for (int job = dominanceSet.nextSetBit(1); job > 0 && job <= data.n;
				job = dominanceSet.nextSetBit(job + 1)) {
			if (isBackwardHalfEligibleJob(job)
					&& isDirectBackwardExtensionTimeFeasible(firstJob, isSinkRoot, frontier, job)) {
				extensionSet.add(job);
			}
		}
		return extensionSet;
	}

	private void precomputeDynamicPricingWindows(LP lp) {
		dynamicJobPenaltyByJob = null;
		dynamicJobHStart = null;
		dynamicJobHEnd = null;
		dynamicBackwardPenaltyByJob = null;
		dynamicBackwardHStartByJob = null;
		dynamicBackwardHEndByJob = null;
		forwardHalfEligibleByJob = null;
		backwardHalfEligibleByJob = null;
		forwardHalfIneligibleJobCount = 0;
		backwardHalfIneligibleJobCount = 0;
		if (!ngDssrReusablePricingWindowPrecomputeReady) {
			precomputeDssrReusablePricingWindows(lp);
			cacheDssrReusablePricingWindowScalars();
			ngDssrReusablePricingWindowPrecomputeReady = true;
		} else {
			restoreDssrReusablePricingWindowScalars();
		}
		tMid = computeDefaultMidpoint();
		ensureBaseHalfPenaltyCache();
		if (requiresCompletionBoundForMidpoint() && completionBounds == null) {
			buildCompletionBounds(lp);
		}
		tMid = computeCurrentMidpoint(lp);
		rebuildHalfDomainForCurrentMidpoint();
	}

	private void precomputeDssrReusablePricingWindows(LP lp) {
		effectiveJobHStart = null;
		effectiveJobHEnd = null;
		completionForwardPenaltyByJob = null;
		completionBackwardPenaltyByJob = null;
		zeroDualExcludedJobs = null;
		zeroDualExcludedJobCount = 0;
		dualProfitableWindowEnabled = canUseDualProfitableWindow(lp);
		precomputeEffectivePricingWindows(lp);
		buildTimeIndexedScalarBoundAndTightenWindows(lp);
		precomputeZeroDualExcludedJobs(lp);
		precomputeCompletionBoundPricingWindows();
	}

	private void cacheDssrReusablePricingWindowScalars() {
		// 2026-06-12: initialize() 婵絽绻楅悿鍡樺濮橆剙甯ラ梺鎻掔Ф閻?pricingHorizon闁挎稒绋戦ˇ鏌ユ偨?window 闁轰焦澹嗙划宥夊籍鐠鸿櫣绠戝銈堫嚙閹挸顫㈤妷锔垮垝濠㈣泛绉风换鏍ㄧ濞戞瑧鍨奸梺鎻掔箞閳?
		ngDssrReusablePricingHorizon = pricingHorizon;
		ngDssrReusableDynamicMinHStart = dynamicMinHStart;
		ngDssrReusableDynamicMaxHEnd = dynamicMaxHEnd;
		ngDssrReusableEarliestSourceCompletion = earliestSourceCompletion;
	}

	private void restoreDssrReusablePricingWindowScalars() {
		pricingHorizon = ngDssrReusablePricingHorizon;
		dynamicMinHStart = ngDssrReusableDynamicMinHStart;
		dynamicMaxHEnd = ngDssrReusableDynamicMaxHEnd;
		earliestSourceCompletion = ngDssrReusableEarliestSourceCompletion;
	}

	private void buildTimeIndexedScalarBoundAndTightenWindows(LP lp) {
		timeIndexedScalarBound = TimeIndexedScalarCompletionBound.build(data, config, lp, pricingHorizon,
				effectiveJobHStart, effectiveJobHEnd);
		if (timeIndexedScalarBound == null) {
			return;
		}
		timeIndexedScalarBuildNanos += timeIndexedScalarBound.getBuildNanos();
		TimeIndexedScalarCompletionBound.WindowTightening tightened;
		if (config.timeIndexedCompletionBoundInRoundArcFixing) {
			// 2026-06-29: pricing 濞戞搩鍙冨Λ鎸庢交椤撴繂鏁╅柛娆樹簷婵炲洭鎮?no-SRI 闁汇劌瀚禍銈夋煂?relaxed fixing闁?
			// SRI-aware fixing 闁告瑯浜欑换姘舵偩濞嗗繑韬?node 闂傚偆鍘奸幃搴ㄥ触鎼达絾鐣遍柛娆樺灣閹寸兘骞?reduced-cost fixing 濞戞搩鍙忕槐婵嬫焼閸喖甯虫慨锝呯箺閻?DSSR 閺夆晩鍘洪崬顒傜磼鐎涙ê袘濠㈣埖姘ㄦ慨鎼佸箑?bucket闁?
			tightened = timeIndexedScalarBound.tightenWindowsAfterZeroReducedCostArcFixing(
					effectiveJobHStart, effectiveJobHEnd);
		} else {
			tightened = timeIndexedScalarBound.tightenWindows(effectiveJobHStart, effectiveJobHEnd);
		}
		timeIndexedWindowTightenedJobs += tightened.tightenedJobs;
		timeIndexedWindowReachableJobs += tightened.reachableJobs;
		if (tightened.tightenedJobs > 0) {
			recomputeEffectiveWindowScalars();
		}
	}

	private void recomputeEffectiveWindowScalars() {
		dynamicMinHStart = Utility.big_M;
		dynamicMaxHEnd = 0.0;
		for (int job = 1; job <= data.n; job++) {
			double hStart = effectiveJobHStart[job];
			double hEnd = effectiveJobHEnd[job];
			if (!Utility.compareGt(hStart, hEnd)) {
				if (Double.isFinite(hStart)) {
					dynamicMinHStart = Math.min(dynamicMinHStart, hStart);
				}
				dynamicMaxHEnd = Math.max(dynamicMaxHEnd, hEnd);
			}
		}
		pricingHorizon = Math.min(data.CmaxH, dynamicMaxHEnd);
	}

	private void rebuildHalfDomainForCurrentMidpoint() {
		ensureBaseHalfPenaltyCache();
		precomputeJobLevelDynamicPricingWindows();
		precomputeBackwardDynamicPricingWindows();
		precomputeHalfDomainEligibility();
	}

	private void precomputeEffectivePricingWindows(LP lp) {
		effectiveJobHStart = new double[data.n + 1];
		effectiveJobHEnd = new double[data.n + 1];
		dynamicMinHStart = Utility.big_M;
		dynamicMaxHEnd = 0.0;
		earliestSourceCompletion = computeEarliestSourceCompletion();

		double localHorizon = 0.0;
		boolean foundFiniteWindow = false;
		Node node = lp == null ? null : lp.getNode();
		for (int job = 1; job <= data.n; job++) {
			double hStart = data.hardWindowStart[job];
			double hEnd = data.hardWindowEnd[job];
			if (dualProfitableWindowEnabled) {
				double baseline = outsourcingBaseline(job);
				double jobDual = Math.max(0.0, lp.getJobDual(job));
				if (Utility.compareLt(jobDual, baseline)) {
					double dynamicStart = hWindowStart(job, jobDual);
					double dynamicEnd = hWindowEnd(job, jobDual);
					if (Utility.compareGt(dynamicStart, data.hardWindowStart[job])
							|| Utility.compareLt(dynamicEnd, data.hardWindowEnd[job])) {
						hStart = dynamicStart;
						hEnd = dynamicEnd;
					}
				}
			}
			if (node != null && node.hasTimeIndexedPricingWindow(job)) {
				// 2026-06-29: time-indexed fixing 鐎电増顨呴崺宀勬儍閸曨剚笑闁告瑯鍨抽幋鐑藉箥鐠恒劍鐣辩痪顓у墰閻涖儵宕ｉ敐蹇曞耿闁?dual window 闁告瑦鐗斿锕傛⒖閸℃绁柛娆樺灛閳?
				hStart = Math.max(hStart, node.getTimeIndexedPricingWindowStart(job));
				hEnd = Math.min(hEnd, node.getTimeIndexedPricingWindowEnd(job));
			}
			recordEffectiveWindow(job, hStart, hEnd);
			if (!Utility.compareGt(hStart, hEnd) && Double.isFinite(hEnd)) {
				localHorizon = Math.max(localHorizon, hEnd);
				foundFiniteWindow = true;
			}
		}
		finalizeEffectiveWindowStatistics(foundFiniteWindow, localHorizon);
	}

	private void recordEffectiveWindow(int job, double hStart, double hEnd) {
		effectiveJobHStart[job] = hStart;
		effectiveJobHEnd[job] = hEnd;
		if (!Utility.compareGt(hStart, hEnd)) {
			if (Double.isFinite(hStart)) {
				dynamicMinHStart = Math.min(dynamicMinHStart, hStart);
			}
			dynamicMaxHEnd = Math.max(dynamicMaxHEnd, hEnd);
		}
	}

	private void finalizeEffectiveWindowStatistics(boolean useLocalHorizon, double localHorizon) {
		if (Utility.isBigMValue(dynamicMinHStart)) {
			dynamicMinHStart = 0.0;
		}
		pricingHorizon = useLocalHorizon ? Math.min(data.CmaxH, localHorizon) : data.CmaxH;
		dynamicMaxHEnd = Math.max(dynamicMaxHEnd, pricingHorizon);
	}

	private double computeCurrentMidpoint(LP lp) {
		long start = System.nanoTime();
		midpointStrategyUsed = configuredMidpointStrategy();
		midpointReferenceTime = Double.NaN;
		midpointColumnSelectedCount = 0;
		midpointColumnLastMin = Double.NaN;
		midpointColumnLastAvg = Double.NaN;
		midpointColumnLastMax = Double.NaN;
		midpointColumnHalfMin = Double.NaN;
		midpointColumnHalfAvg = Double.NaN;
		midpointColumnHalfMax = Double.NaN;
		double candidate;
		if (Double.isFinite(config.bidirectionalRootLocalHorizonMidpointRatio)
				&& Utility.compareGt(config.bidirectionalRootLocalHorizonMidpointRatio, 0.0)
				&& Utility.compareLt(config.bidirectionalRootLocalHorizonMidpointRatio, 1.0)) {
			midpointStrategyUsed = "ratio";
			midpointReferenceTime = pricingHorizon;
			candidate = pricingHorizon * config.bidirectionalRootLocalHorizonMidpointRatio;
			midpointStrategyNanos += System.nanoTime() - start;
			return clampCurrentMidpoint(candidate);
		}

		double left = midpointLeftBound();
		if ("incumbentMakespan".equalsIgnoreCase(midpointStrategyUsed)) {
			double reference = incumbentBestMakespan();
			if (Double.isFinite(reference)) {
				midpointReferenceTime = reference;
				midpointStrategyNanos += System.nanoTime() - start;
				return clampCurrentMidpoint((left + Math.min(reference, pricingHorizon)) * 0.5);
			}
		} else if ("completionBound".equalsIgnoreCase(midpointStrategyUsed)) {
			double reference = completionBoundArgminTime();
			if (Double.isFinite(reference)) {
				midpointReferenceTime = reference;
				midpointStrategyNanos += System.nanoTime() - start;
				return clampCurrentMidpoint((left + Math.min(reference, pricingHorizon)) * 0.5);
			}
		} else if ("columnLastAvg".equalsIgnoreCase(midpointStrategyUsed)
				|| "columnHalfAvg".equalsIgnoreCase(midpointStrategyUsed)
				|| "columnTaskMedian".equalsIgnoreCase(midpointStrategyUsed)
				|| "columnTaskMedianTopLast".equalsIgnoreCase(midpointStrategyUsed)) {
			boolean topLastTaskMedianStrategy = "columnTaskMedianTopLast".equalsIgnoreCase(midpointStrategyUsed);
			MidpointColumnTimingStats stats = topLastTaskMedianStrategy ? evaluateTopLastMidpointColumnTiming(lp)
					: evaluateMidpointColumnTiming(lp);
			if (stats.count > 0) {
				boolean taskMedianStrategy = "columnTaskMedian".equalsIgnoreCase(midpointStrategyUsed)
						|| topLastTaskMedianStrategy;
				midpointReferenceTime = taskMedianStrategy ? stats.taskMedian
						: ("columnHalfAvg".equalsIgnoreCase(midpointStrategyUsed) ? stats.halfAvg : stats.lastAvg);
				midpointColumnSelectedCount = stats.count;
				midpointColumnLastMin = stats.lastMin;
				midpointColumnLastAvg = stats.lastAvg;
				midpointColumnLastMax = stats.lastMax;
				midpointColumnHalfMin = stats.halfMin;
				midpointColumnHalfAvg = stats.halfAvg;
				midpointColumnHalfMax = stats.halfMax;
				midpointColumnTaskSampleCount = stats.taskCount;
				midpointColumnTaskMin = stats.taskMin;
				midpointColumnTaskAvg = stats.taskAvg;
				midpointColumnTaskMedian = stats.taskMedian;
				midpointColumnTaskMax = stats.taskMax;
				double reference = Math.min(midpointReferenceTime, pricingHorizon);
				midpointStrategyNanos += System.nanoTime() - start;
				if ("columnHalfAvg".equalsIgnoreCase(midpointStrategyUsed) || taskMedianStrategy) {
					return clampCurrentMidpoint(reference);
				}
				return clampCurrentMidpoint((left + reference) * 0.5);
			}
		}

		midpointStrategyUsed = "default";
		candidate = computeDefaultMidpoint();
		midpointReferenceTime = pricingHorizon;
		midpointStrategyNanos += System.nanoTime() - start;
		return candidate;
	}

	private double computeDefaultMidpoint() {
		double left = midpointLeftBound();
		double candidate;
		if (Double.isFinite(left) && Utility.compareLt(left, pricingHorizon)) {
			candidate = (left + pricingHorizon) * 0.5;
		} else {
			// 2026-05-26: 鐟滅増鎸搁惇顒勬焾閵娧呭炊闁告瑱绲介崙锛勭磼韫囨艾鍨遍柛?pricingHorizon 闁哄啳顔愮槐婵嬪炊閻愯　鍋撻埀顒勫礆閺夊灝绀侀柛瀣箰閸ㄥ繘宕氶崱顓犵闂侇剙鐏濋崢銈夊触鎼粹剝鍊婚柛妤€锕ょ亸顖炴⒒閹绢喗姣愰幖杈剧細鐠?0闁?
			candidate = pricingHorizon * 0.75;
		}
		return clampCurrentMidpoint(candidate);
	}

	private String configuredMidpointStrategy() {
		String strategy = config.bidirectionalMidpointStrategy == null ? "default"
				: config.bidirectionalMidpointStrategy.trim();
		return strategy.isEmpty() ? "default" : strategy;
	}

	private boolean requiresCompletionBoundForMidpoint() {
		return "completionBound".equalsIgnoreCase(configuredMidpointStrategy()) && completionBoundRelaxation != null;
	}

	private double midpointLeftBound() {
		double left = Math.max(dynamicMinHStart, earliestSourceCompletion);
		return Double.isFinite(left) ? left : 0.0;
	}

	private double incumbentBestMakespan() {
		if (data.configure == null || data.configure.bestSolution == null) {
			return Double.NaN;
		}
		Solution incumbent = data.configure.bestSolution;
		ArrayList<ArrayList<Integer>> sequences = incumbent.getSequencesCopy();
		double makespan = Double.NaN;
		for (ArrayList<Integer> sequence : sequences) {
			if (sequence.isEmpty()) {
				continue;
			}
			TWETColumnEvaluator.Timing timing = evaluator.evaluateTiming(sequence);
			if (Double.isFinite(timing.lastCompletion)) {
				makespan = Double.isNaN(makespan) ? timing.lastCompletion : Math.max(makespan, timing.lastCompletion);
			}
		}
		return makespan;
	}

	private double completionBoundArgminTime() {
		if (completionBounds == null) {
			return Double.NaN;
		}
		MidpointFunctionArgmin all = new MidpointFunctionArgmin();
		MidpointFunctionArgmin negative = new MidpointFunctionArgmin();
		for (int job = 1; job <= data.n; job++) {
			recordCompletionBoundArgmin(completionBounds.forwardFByJob[job], all, negative);
			recordCompletionBoundArgmin(completionBounds.forwardUByJob[job], all, negative);
			recordCompletionBoundArgmin(completionBounds.backwardBByJob[job], all, negative);
			recordCompletionBoundArgmin(completionBounds.backwardRByJob[job], all, negative);
		}
		return negative.count > 0 ? negative.maxTime : all.maxTime;
	}

	private void recordCompletionBoundArgmin(PiecewiseLinearFunction function, MidpointFunctionArgmin all,
			MidpointFunctionArgmin negative) {
		if (function == null) {
			return;
		}
		double[] min = function.findMinimal(false, true);
		if (min == null || min.length < 2 || !Double.isFinite(min[0]) || !Double.isFinite(min[1])) {
			return;
		}
		all.accept(min[1]);
		if (Utility.compareLt(min[0], 0.0)) {
			negative.accept(min[1]);
		}
	}

	private MidpointColumnTimingStats evaluateMidpointColumnTiming(LP lp) {
		MidpointColumnTimingStats stats = new MidpointColumnTimingStats();
		List<ColumnMidpointCandidate> candidates = selectMidpointColumnCandidates(lp);
		int limit = Math.max(0, config.bidirectionalMidpointColumnLimit);
		for (ColumnMidpointCandidate candidate : candidates) {
			if (limit > 0 && stats.count >= limit) {
				break;
			}
			TWETColumn column = lp.getPool().getColumn(candidate.columnId);
			ArrayList<Integer> sequence = new ArrayList<Integer>(column.getSequence());
			TWETColumnEvaluator.Timing timing = evaluator.evaluateTiming(sequence);
			if (Double.isFinite(timing.lastCompletion) && Double.isFinite(timing.halfCompletion)) {
				stats.accept(timing);
			}
		}
		stats.finish();
		return stats;
	}

	/**
	 * 2026-06-07: 闁稿繐鐗呯划?low reduced-cost 闁告帗銇為懙鎴犳嫚閸曨亞骞?2K 闁哄妲勭槐婵嬪礃瀹ュ棗鐦婚柛鎺擃殕濠€顖溾偓鐟拌嫰娴兼劙寮崼鏇燂紵闁告瑦鐗楀〒鍫曞疾?K 闁哄鎵冲亾?
	 * 閺夆晜鐟﹂悧杈ㄧ┍濠靛牊娈屽ù鐘侯嚙婵喖鏌?median 闁汇劌瀚銏＄▕婢舵稓绀夐柛姘湰濡炲倿宕欒箛鎾舵瘜闁活収鍘奸崹顏堝箣閺嶃劍锛嶉悗鐟拌嫰娴兼劙宕氬Δ浣肝?Tmid 闁瑰嘲顦欢杈ㄦ交閸パ傜闁?
	 */
	private MidpointColumnTimingStats evaluateTopLastMidpointColumnTiming(LP lp) {
		List<ColumnMidpointCandidate> candidates = selectMidpointColumnCandidates(lp);
		int selectedLimit = Math.max(0, config.bidirectionalMidpointColumnLimit);
		int timingLimit = selectedLimit > 0 ? selectedLimit * 2 : 0;
		ArrayList<ColumnMidpointTimingCandidate> timedCandidates = new ArrayList<ColumnMidpointTimingCandidate>();
		for (ColumnMidpointCandidate candidate : candidates) {
			if (timingLimit > 0 && timedCandidates.size() >= timingLimit) {
				break;
			}
			TWETColumn column = lp.getPool().getColumn(candidate.columnId);
			ArrayList<Integer> sequence = new ArrayList<Integer>(column.getSequence());
			TWETColumnEvaluator.Timing timing = evaluator.evaluateTiming(sequence);
			if (Double.isFinite(timing.lastCompletion) && Double.isFinite(timing.halfCompletion)) {
				timedCandidates.add(new ColumnMidpointTimingCandidate(candidate.columnId, timing));
			}
		}
		Collections.sort(timedCandidates, new Comparator<ColumnMidpointTimingCandidate>() {
			@Override
			public int compare(ColumnMidpointTimingCandidate a, ColumnMidpointTimingCandidate b) {
				int byLastCompletion = -Double.compare(a.timing.lastCompletion, b.timing.lastCompletion);
				return byLastCompletion != 0 ? byLastCompletion : Integer.compare(a.columnId, b.columnId);
			}
		});
		MidpointColumnTimingStats stats = new MidpointColumnTimingStats();
		for (ColumnMidpointTimingCandidate candidate : timedCandidates) {
			if (selectedLimit > 0 && stats.count >= selectedLimit) {
				break;
			}
			stats.accept(candidate.timing);
		}
		stats.finish();
		return stats;
	}

	private List<ColumnMidpointCandidate> selectMidpointColumnCandidates(LP lp) {
		ArrayList<ColumnMidpointCandidate> candidates = new ArrayList<ColumnMidpointCandidate>();
		for (int columnId : lp.getRestrictedColumnIds()) {
			TWETColumn column = lp.getPool().getColumn(columnId);
			if (column.getSequence().isEmpty()) {
				continue;
			}
			if (!isSequenceCompatible(column.getSequence(), lp.getNode())) {
				continue;
			}
			candidates.add(new ColumnMidpointCandidate(columnId, lp.getColumnReducedCost(columnId)));
		}
		Collections.sort(candidates, new Comparator<ColumnMidpointCandidate>() {
			@Override
			public int compare(ColumnMidpointCandidate a, ColumnMidpointCandidate b) {
				int byReducedCost = Double.compare(a.reducedCost, b.reducedCost);
				return byReducedCost != 0 ? byReducedCost : Integer.compare(a.columnId, b.columnId);
			}
		});
		return candidates;
	}

	private double clampCurrentMidpoint(double candidate) {
		if (!Double.isFinite(pricingHorizon) || !Utility.compareGt(pricingHorizon, 0.0)) {
			return 0.0;
		}
		// 婵繐绲介悥?midpoint 闁稿浚鍓欑槐鈩冩償閺傚灝鍤掗柦鈧挊澶嬭含 (0, pricingHorizon) 闁告劕鎷戠槐杈ㄦ交濞嗘挸娅″ù鐘叉嚇濡茶顕ラ埄鍐偓顒備焊?horizon 闁瑰瓨鐗曢幃妤冪磼椤撶喐鏆柛蹇ｅ墮缁憋繝鏌呴悩鍐茬亣閻犳劗顥愮粩鐔煎Υ?
		double minWidth = Math.max(Utility.EPS * 10.0, pricingHorizon * 1e-9);
		if (!Utility.compareGt(pricingHorizon, 2.0 * minWidth)) {
			return pricingHorizon * 0.5;
		}
		if (!Double.isFinite(candidate)) {
			candidate = pricingHorizon * 0.75;
		}
		double lower = minWidth;
		double upper = pricingHorizon - minWidth;
		double clamped = candidate;
		if (Utility.compareLt(candidate, lower)) {
			clamped = lower;
		} else if (Utility.compareGt(candidate, upper)) {
			clamped = upper;
		}
		return clamped;
	}

	private double computeEarliestSourceCompletion() {
		double earliest = Utility.big_M;
		for (int job = 1; job <= data.n; job++) {
			earliest = Math.min(earliest, data.getSetUp(0, job) + data.getProcessT(job));
		}
		return earliest;
	}

	private void precomputeJobLevelDynamicPricingWindows() {
		dynamicJobPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicJobHStart = new double[data.n + 1];
		dynamicJobHEnd = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = effectiveJobHStart[job];
			double hEnd = effectiveJobHEnd[job];
			PiecewiseLinearFunction penalty = baseForwardHalfPenaltyByJob[job];
			if (isEffectiveWindowTighterThanHard(job)) {
				penalty = Utility.compareGt(hStart, hEnd) ? null : buildForwardHalfPenalty(job, hStart, hEnd);
			}
			dynamicJobHStart[job] = hStart;
			dynamicJobHEnd[job] = hEnd;
			dynamicJobPenaltyByJob[job] = penalty;
		}
	}

	private void precomputeBackwardDynamicPricingWindows() {
		dynamicBackwardPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		dynamicBackwardHStartByJob = new double[data.n + 1];
		dynamicBackwardHEndByJob = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = effectiveJobHStart[job];
			double hEnd = effectiveJobHEnd[job];
			PiecewiseLinearFunction penalty = baseBackwardHalfPenaltyByJob[job];
			if (isEffectiveWindowTighterThanHard(job)) {
				penalty = Utility.compareGt(hStart, hEnd) ? null : buildBackwardHalfPenalty(job, hStart, hEnd);
			}
			dynamicBackwardHStartByJob[job] = hStart;
			dynamicBackwardHEndByJob[job] = hEnd;
			dynamicBackwardPenaltyByJob[job] = penalty;
		}
	}

	private void precomputeCompletionBoundPricingWindows() {
		completionForwardPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		completionBackwardPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double hStart = effectiveJobHStart[job];
			double hEnd = effectiveJobHEnd[job];
			PiecewiseLinearFunction penalty = Utility.compareGt(hStart, hEnd)
					? null : buildCompletionBoundPenalty(job, hStart, hEnd);
			completionForwardPenaltyByJob[job] = penalty;
			completionBackwardPenaltyByJob[job] = penalty;
		}
	}

	private boolean isEffectiveWindowTighterThanHard(int job) {
		return Utility.compareGt(effectiveJobHStart[job], data.hardWindowStart[job])
				|| Utility.compareLt(effectiveJobHEnd[job], data.hardWindowEnd[job]);
	}

	/**
	 * 2026-05-25: 闁告瑯浜濇繛濠囧矗閺嶃倐鍋撳鍐╁闁告挸绉归埞?闁告艾娴烽幋鐑藉籍閻樻彃褰犻柕鍡曠瀹曠喖鎯?job 闁煎浜滅换浣轰焊鏉堫偅鍎板☉鎾崇Т閸╁瞼鈧數鎳撶花?half-domain闁炽儲绻勫▓鎴炵┍閳╁啩绱栭柕?
	 * forward 闁兼眹鍎查弳锝呪枔閻㈢鈧牜绮ｅΔ鍛幋闁?Tmid 闁告瑥鍘栭弲鍫曟晬鐏炶棄鐏熷ù鐘侯唺缂?forward prefix 闂侇喗鍨濈粭澶愭閳ь剛鎲版担绋挎櫃閻忓繑绻嗛惁顖溾偓鐟板枦缁?
	 * backward 閻庨潧婀辫ⅷ闁革附澹嗗﹢鍛村极鐎涙﹩鍞界痪顓у墰閻涖儵寮伴姘剨鐎瑰憡褰冮悾顒勫礂閵娿劍鍎伴柛?Tmid 鐎归潻缂氶弲鍫曞Υ?
	 */
	private void precomputeHalfDomainEligibility() {
		forwardHalfEligibleByJob = new boolean[data.n + 1];
		backwardHalfEligibleByJob = new boolean[data.n + 1];
		forwardHalfIneligibleJobCount = 0;
		backwardHalfIneligibleJobCount = 0;
		for (int job = 1; job <= data.n; job++) {
			boolean forwardEligible = dynamicJobPenaltyByJob[job] != null
					&& !Utility.compareGt(dynamicJobHStart[job], tMid);
			boolean backwardEligible = dynamicBackwardPenaltyByJob[job] != null
					&& !Utility.compareLt(dynamicBackwardHEndByJob[job], tMid);
			forwardHalfEligibleByJob[job] = forwardEligible;
			backwardHalfEligibleByJob[job] = backwardEligible;
			if (!forwardEligible) {
				forwardHalfIneligibleJobCount++;
			}
			if (!backwardEligible) {
				backwardHalfIneligibleJobCount++;
			}
		}
	}

	private void ensureBaseHalfPenaltyCache() {
		if (baseForwardHalfPenaltyByJob != null && Utility.compareEq(baseHalfPenaltyCacheTMid, tMid)
				&& Utility.compareEq(baseHalfPenaltyCacheHorizon, pricingHorizon)) {
			return;
		}
		baseForwardHalfPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		baseBackwardHalfPenaltyByJob = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			// 2026-05-24: data.penaltyFunction[job] 鐎规瓕灏欑划锟犲礌閸涱厽鍎撻柛鈺勬〃缁?b_j 闁汇劌瀚板銈夊箑娴ｈ櫣鐓愮痪顓у墰閻涖儵濡?
			// dual 濞戞挸绉烽崗妯绘交濞戞顏辨慨婵勫劜閺佸湱妲愯濡炲倿鏁嶇仦钘夎摕闁?pricing 闁烩晛鐡ㄧ敮瀛樺緞瀹ュ洦鏆忛弶鈺傜懁鐞氳鲸绋夐鍕９闁糕晝鍠撶槐锔锯偓娑欙公缁辨繈鏌嗛崹顔煎赋婵絽绻楅悿鍡涙煂瀹ュ拋妲?setDomain/crop闁?
			baseForwardHalfPenaltyByJob[job] = cropToInterval(data.penaltyFunction[job], 0.0, tMid);
			baseBackwardHalfPenaltyByJob[job] = cropToInterval(data.penaltyFunction[job], tMid, pricingHorizon);
		}
		baseHalfPenaltyCacheTMid = tMid;
		baseHalfPenaltyCacheHorizon = pricingHorizon;
	}

	private boolean canUseDualProfitableWindow(LP lp) {
		Node node = lp.getNode();
		if (node == null || node.depth != 0) {
			return false;
		}
		// cut dual 闁瑰瓨鐗曢崹搴ㄥ绩?dual 闂侇喛妫勮ぐ鏌ユ嚄閸婄噥鍞?reduced arc cost 濞戞挸绉撮崯鈧繝濞愩倕鍠曢柛妯煎枎椤?setup cost 闁汇劌瀚粭浣烘喆閹哄秶鐟濈紒娑橆槸缁憋繝濡?
		// 鐟滅増鎸告晶鐘诲矗椤忓嫭韬柡宥囶攰婵☆參鎮欓獮搴撳亾娴ｉ鐟繛灞稿墲濠€?active cuts 闁哄啯婀规繛鍥偨?pi_j 閺夆晜绋愮粩鏉戭潰閵夛附鏆紒姣栧洦楗柟顑跨椤﹀宕犻崨顖滃炊闁?
		return lp.getActiveCutIds().isEmpty();
	}

	/**
	 * 2026-05-28: 闁哄秶顢婃俊顓㈡倷?no-cut pricing 濞戞搩鍙忕槐婕癷_j=0 闁汇劌瀚幑銏ゅ礉閳ヨ尙鐟濋弶鈺傜☉閸?pricing 闁圭鏅涢惈宥夊Υ?
	 * 闁革负鍔岀紞瀣礈瀹ュ棙锟?cut/branch dual 闁汇劌瀚粭浣烘喆閹哄秶鐟濈紒娑橆槸缁憋紕鎷犻婵堢枀濞戞挸顑戠槐婵囨交濞嗘垼顫?job 濞戞挸绉磋ぐ鏌ユ嚄閼恒儲鏆柛鐘插缁€?reduced-cost 闁告帗銇滈埀?
	 */
	private void precomputeZeroDualExcludedJobs(LP lp) {
		if (!dualProfitableWindowEnabled) {
			return;
		}
		zeroDualExcludedJobs = new boolean[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			double jobDual = Math.max(0.0, lp.getJobDual(job));
			if (Utility.compareEq(jobDual, 0.0)) {
				zeroDualExcludedJobs[job] = true;
				zeroDualExcludedJobCount++;
			}
		}
	}

	private PiecewiseLinearFunction buildForwardHalfPenalty(int job, double hStart, double hEnd) {
		// 2026-05-23: 闁告锕ら悡娆愭綇閸︻厽娅曢柣鈺佺摠鐢挳宕樺▎蹇撳汲闁告柣鍔嶉埀?job penalty闁?
		// forward 闁汇劌瀚弻濠冩櫠?job 闁告垼濮ら弳鐔煎矗椤忓嫭韬?[0,Tmid] 濞戞挸锕ゅ顒佺▔?add闁挎稑鑻崣鏇㈠礂閸楃偟鏆板☉鏂款槸閻撴瑦瀵煎鍐叉闁绘帟鍩栨俊鎼佸矗瀹曞浂浼傞柛妞烩偓铏含 Tmid闁?
		return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), 0.0, tMid);
	}

	private PiecewiseLinearFunction buildBackwardHalfPenalty(int job, double hStart, double hEnd) {
		// 2026-05-23: backward 閻庨潧婀辫ⅷ濞达綀娉曢弫?[Tmid,pricingHorizon] 濞戞挸锕﹀▓鎴﹀棘閺夋鏉?job 闁告垼濮ら弳鐔煎Υ?
		// 闁兼眹鍎抽悰銉╁矗閿濆懍绠〒姘€鍌濈 big_M闁挎稑鑻幃妤冪磼?normalize(BACKWARD) 濞村吋宀搁埀顒佷亢缁?suffix-min 閻炴稏鍔忛幓顏堝灳濠婂啫璁插ù鐘劤閻℃垿宕氶幍顔惧炊闁告瑱绲介崬瀵糕偓鐟版湰閸ㄦ岸鍨惧┑鍕ㄥ亾?
		return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), tMid, pricingHorizon);
	}

	private PiecewiseLinearFunction buildCompletionBoundPenalty(int job, double hStart, double hEnd) {
		// 2026-06-01: Tmid pricing 闁汇劌瀚婊冾嚕?label 濞寸姴绉虫繛鍥偨閵娿儰绠柛娆忓暱瀹曟劙宕洪悢宄版瘣闁轰礁搴滅槐鐪俹mpletion bound
		// 闂傚洠鍋撻悷鏇氱閸ㄤ粙寮鐐茬９闁?label 闁哄嫷鍨伴幆浣规交濡灝鍘撮悶娑栧劜閸ㄦ氨鈧懓鏈弳锝囨嫻閻斿嘲鐏欓柨娑樿嫰濞叉粌顫㈤妶鍛闁绘瑯鍏涙繛鍥偨閵娿儳鏆氶柡?[0, pricingHorizon] 閻庤鐭粻鐔煎春閻旂补鍋?
		if (isEffectiveWindowTighterThanHard(job)) {
			return cropToInterval(data.penaltyFunction[job].setDomain(hStart, hEnd, true), 0.0, pricingHorizon);
		}
		return cropToInterval(data.penaltyFunction[job], 0.0, pricingHorizon);
	}

	private PiecewiseLinearFunction getDynamicForwardJobPenalty(int prevJob, int job) {
		return dynamicJobPenaltyByJob == null ? null : dynamicJobPenaltyByJob[job];
	}

	private double getDynamicForwardHEnd(int prevJob, int job) {
		return dynamicJobHEnd[job];
	}

	private double getDynamicForwardHStart(int prevJob, int job) {
		return dynamicJobHStart[job];
	}

	private PiecewiseLinearFunction getDynamicBackwardJobPenalty(int job, int successor) {
		return dynamicBackwardPenaltyByJob == null ? null : dynamicBackwardPenaltyByJob[job];
	}

	private double getDynamicBackwardHStart(int job, int successor) {
		return dynamicBackwardHStartByJob[job];
	}

	private double getDynamicBackwardHEnd(int job, int successor) {
		return dynamicBackwardHEndByJob[job];
	}

	private double hWindowStart(int job, double gamma) {
		if (!Utility.compareGt(data.w_e[job], 0.0)) {
			return 0.0;
		}
		return Math.max(0.0, data.d_e[job] - gamma / data.w_e[job]);
	}

	private double hWindowEnd(int job, double gamma) {
		if (!Utility.compareGt(data.w_t[job], 0.0)) {
			return data.CmaxH;
		}
		return Math.min(data.CmaxH, data.d_l[job] + gamma / data.w_t[job]);
	}

	private double outsourcingBaseline(int job) {
		return Utility.isBigMValue(data.outsourcingCost[job]) ? Utility.big_M : Math.max(0.0, data.outsourcingCost[job]);
	}

	private boolean isZeroDualExcludedJob(int job) {
		return job > 0 && zeroDualExcludedJobs != null && job < zeroDualExcludedJobs.length
				&& zeroDualExcludedJobs[job];
	}

	private void addZeroDualExcludedJobs(PackedBitSet visited) {
		if (zeroDualExcludedJobs == null) {
			return;
		}
		for (int job = 1; job <= data.n; job++) {
			if (isZeroDualExcludedJob(job)) {
				visited.add(job);
			}
		}
	}

	private boolean bitSetsIntersectForJoin(PackedBitSet left, PackedBitSet right) {
		for (int job = left.nextSetBit(1); job >= 0; job = left.nextSetBit(job + 1)) {
			if (!isZeroDualExcludedJob(job) && right.contains(job)) {
				return true;
			}
		}
		return false;
	}

	private boolean isForwardHalfEligibleJob(int job) {
		return job > 0 && forwardHalfEligibleByJob != null && job < forwardHalfEligibleByJob.length
				&& forwardHalfEligibleByJob[job];
	}

	private boolean isBackwardHalfEligibleJob(int job) {
		return job > 0 && backwardHalfEligibleByJob != null && job < backwardHalfEligibleByJob.length
				&& backwardHalfEligibleByJob[job];
	}

	private ArrayList<Integer> recoverForwardSequence(ForwardLabel label) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		ForwardLabel cursor = label;
		while (cursor != null && cursor.jid != 0) {
			sequence.add(Integer.valueOf(cursor.jid));
			cursor = cursor.father;
		}
		reverseInPlace(sequence);
		return sequence;
	}

	private ArrayList<Integer> recoverBackwardSequence(BackwardLabel label) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		BackwardLabel cursor = label;
		while (cursor != null && !cursor.isSinkRoot) {
			sequence.add(Integer.valueOf(cursor.jid));
			cursor = cursor.father;
		}
		return sequence;
	}

	private ArrayList<Integer> recoverJoinSequence(ForwardLabel forward, BackwardLabel backward) {
		ArrayList<Integer> sequence = recoverForwardSequence(forward);
		sequence.addAll(recoverBackwardSequence(backward));
		return sequence;
	}

	private boolean isTargetSequence(ArrayList<Integer> sequence) {
		return targetTraceSequence != null && targetTraceSequence.equals(sequence);
	}

	private boolean isTargetSignature(SequenceSignature signature) {
		return targetTraceSequence != null && signature.equals(new SequenceSignature(targetTraceSequence));
	}

	private boolean isTargetJoinPair(ForwardLabel forward, BackwardLabel backward) {
		if (targetTraceSequence == null) {
			return false;
		}
		ArrayList<Integer> sequence = recoverJoinSequence(forward, backward);
		return targetTraceSequence.equals(sequence);
	}

	private void traceTargetForward(String stage, ForwardLabel label, LP lp) {
		if (targetTraceSequence == null || label == null) {
			return;
		}
		ArrayList<Integer> sequence = recoverForwardSequence(label);
		if (!isTargetPrefix(sequence)) {
			return;
		}
		traceTarget(stage + " f#" + label.labelId + " depth=" + label.depth + " seq=" + sequence
				+ " min=" + label.minReducedCost + " domain=" + labelDomain(label)
				+ " ext=" + label.extensionCardinality + " ng=" + label.ngMemorySet.cardinality()
				+ " dominated=" + label.isDominated
				+ " next=" + targetForwardNextStatus(label, sequence, lp));
	}

	private String targetForwardNextStatus(ForwardLabel label, ArrayList<Integer> sequence, LP lp) {
		if (targetTraceSequence == null || sequence.size() >= targetTraceSequence.size()) {
			return "end";
		}
		int next = targetTraceSequence.get(sequence.size()).intValue();
		boolean inExtension = label.extensionSet != null && label.extensionSet.contains(next);
		boolean inNgMemory = label.ngMemorySet != null && label.ngMemorySet.contains(next);
		boolean halfEligible = isForwardHalfEligibleJob(next);
		boolean timeFeasible = label.frontier != null
				&& isDirectForwardExtensionTimeFeasible(label.frontier, label.jid, next);
		boolean arcForbidden = lp != null && lp.getNode() != null && isPricingArcForbidden(lp.getNode(), label.jid, next);
		return next + "{ext=" + inExtension
				+ ",ng=" + inNgMemory
				+ ",half=" + halfEligible
				+ ",time=" + timeFeasible
				+ ",arcForbidden=" + arcForbidden + "}";
	}

	private void traceTargetBackward(String stage, BackwardLabel label) {
		if (targetTraceSequence == null || label == null || label.isSinkRoot) {
			return;
		}
		ArrayList<Integer> sequence = recoverBackwardSequence(label);
		if (!isTargetSuffix(sequence)) {
			return;
		}
		traceTarget(stage + " b#" + label.labelId + " seq=" + sequence
				+ " min=" + label.minReducedCost + " domain=" + labelDomain(label)
				+ " ext=" + label.extensionCardinality + " ng=" + label.ngMemorySet.cardinality()
				+ " dominated=" + label.isDominated);
	}

	private void traceTargetPartialListTrim(Label trimmed, Label dominator, TrimResult result, Direction direction) {
		if (targetTraceSequence == null || trimmed == null || dominator == null) {
			return;
		}
		ArrayList<Integer> trimmedSequence = recoverAnySequence(trimmed);
		if (trimmedSequence == null) {
			return;
		}
		boolean targetSide = direction == Direction.FORWARD
				? isTargetPrefix(trimmedSequence)
				: isTargetSuffix(trimmedSequence);
		if (!targetSide) {
			return;
		}
		ArrayList<Integer> dominatorSequence = recoverAnySequence(dominator);
		watchTargetDominator(dominator, direction, dominatorSequence, trimmedSequence);
		traceTarget("PARTIAL_TRIM " + direction
				+ " result=" + result
				+ " trimmed#" + labelId(trimmed)
				+ " seq=" + trimmedSequence
				+ " min=" + trimmed.minReducedCost
				+ " domain=" + labelDomain(trimmed)
				+ " state=" + labelStateSummary(trimmed)
				+ " by#" + labelId(dominator)
				+ " seq=" + dominatorSequence
				+ " min=" + dominator.minReducedCost
				+ " domain=" + labelDomain(dominator)
				+ " state=" + labelStateSummary(dominator)
				+ " forgottenTargetJobs=" + forgottenTargetJobs(dominator, trimmedSequence, direction));
	}

	private boolean shouldProtectTargetTrim(Label trimmed, Label dominator, Direction direction) {
		if (!targetTraceProtectTarget || targetTraceSequence == null || trimmed == null) {
			return false;
		}
		ArrayList<Integer> trimmedSequence = recoverAnySequence(trimmed);
		if (trimmedSequence == null) {
			return false;
		}
		boolean targetSide = direction == Direction.FORWARD
				? isTargetPrefix(trimmedSequence)
				: isTargetSuffix(trimmedSequence);
		if (!targetSide) {
			return false;
		}
		ArrayList<Integer> dominatorSequence = recoverAnySequence(dominator);
		watchTargetDominator(dominator, direction, dominatorSequence, trimmedSequence);
		traceTarget("PARTIAL_TRIM_SKIPPED " + direction
				+ " trimmed#" + labelId(trimmed)
				+ " seq=" + trimmedSequence
				+ " domain=" + labelDomain(trimmed)
				+ " by#" + labelId(dominator)
				+ " seq=" + dominatorSequence
				+ " domain=" + labelDomain(dominator)
				+ " forgottenTargetJobs=" + forgottenTargetJobs(dominator, trimmedSequence, direction));
		return true;
	}

	private void watchTargetDominator(Label dominator, Direction direction, ArrayList<Integer> dominatorSequence,
			ArrayList<Integer> trimmedSequence) {
		if (!targetTraceDominatorFollow || dominator == null || targetTraceWatchedLabelIds == null) {
			return;
		}
		int id = labelId(dominator);
		if (id < 0 || !targetTraceWatchedLabelIds.add(Integer.valueOf(id))) {
			return;
		}
		traceTarget("WATCH_DOMINATOR " + direction
				+ " #" + id
				+ " seq=" + dominatorSequence
				+ " min=" + dominator.minReducedCost
				+ " domain=" + labelDomain(dominator)
				+ " trimmedSeq=" + trimmedSequence
				+ " state=" + labelStateSummary(dominator));
	}

	private ArrayList<Integer> recoverAnySequence(Label label) {
		if (label instanceof ForwardLabel) {
			return recoverForwardSequence((ForwardLabel) label);
		}
		if (label instanceof BackwardLabel) {
			return recoverBackwardSequence((BackwardLabel) label);
		}
		return null;
	}

	private int labelId(Label label) {
		return label instanceof FunctionLabel ? ((FunctionLabel) label).labelId : -1;
	}

	private String labelStateSummary(Label label) {
		int visited = label.visitedSet == null ? 0 : label.visitedSet.cardinality();
		int reachable = label.reachableCardinality;
		if (label instanceof FunctionLabel) {
			FunctionLabel functionLabel = (FunctionLabel) label;
			int ng = functionLabel.ngMemorySet == null ? 0 : functionLabel.ngMemorySet.cardinality();
			int ext = functionLabel.extensionCardinality;
			return "visited/ng/ext=" + visited + "/" + ng + "/" + ext;
		}
		return "visited/reach=" + visited + "/" + reachable;
	}

	private String forgottenTargetJobs(Label label, ArrayList<Integer> trimmedSequence, Direction direction) {
		if (!(label instanceof FunctionLabel) || trimmedSequence == null) {
			return "[]";
		}
		FunctionLabel functionLabel = (FunctionLabel) label;
		ArrayList<Integer> jobs = new ArrayList<Integer>();
		if (direction == Direction.FORWARD) {
			for (int i = trimmedSequence.size(); i < targetTraceSequence.size(); i++) {
				int job = targetTraceSequence.get(i).intValue();
				if (label.visitedSet != null && label.visitedSet.contains(job)
						&& !functionLabel.ngMemorySet.contains(job)) {
					jobs.add(Integer.valueOf(job));
				}
			}
		} else {
			int suffixStart = targetTraceSequence.size() - trimmedSequence.size();
			for (int i = 0; i < suffixStart; i++) {
				int job = targetTraceSequence.get(i).intValue();
				if (label.visitedSet != null && label.visitedSet.contains(job)
						&& !functionLabel.ngMemorySet.contains(job)) {
					jobs.add(Integer.valueOf(job));
				}
			}
		}
		return jobs.toString();
	}

	private void traceWatchedChild(String stage, FunctionLabel parent, FunctionLabel child, int extensionJob) {
		if (!targetTraceDominatorFollow || !isWatchedLabel(parent) || child == null) {
			return;
		}
		watchLabel(child);
		traceTarget(stage
				+ " parent#" + labelId(parent)
				+ " child#" + labelId(child)
				+ " via=" + extensionJob
				+ " seq=" + recoverAnySequence(child)
				+ " min=" + child.minReducedCost
				+ " domain=" + labelDomain(child)
				+ " state=" + labelStateSummary(child));
	}

	private void traceWatchedLabel(String stage, FunctionLabel label) {
		if (!targetTraceDominatorFollow || !isWatchedLabel(label)) {
			return;
		}
		traceTarget(stage
				+ " #" + labelId(label)
				+ " seq=" + recoverAnySequence(label)
				+ " min=" + label.minReducedCost
				+ " domain=" + labelDomain(label)
				+ " dominated=" + label.isDominated
				+ " state=" + labelStateSummary(label));
	}

	private boolean isWatchedLabel(Label label) {
		return targetTraceWatchedLabelIds != null && labelId(label) >= 0
				&& targetTraceWatchedLabelIds.contains(Integer.valueOf(labelId(label)));
	}

	private void watchLabel(Label label) {
		if (targetTraceWatchedLabelIds != null && labelId(label) >= 0) {
			targetTraceWatchedLabelIds.add(Integer.valueOf(labelId(label)));
		}
	}

	private boolean isTargetPrefix(ArrayList<Integer> sequence) {
		if (sequence.size() > targetTraceSequence.size()) {
			return false;
		}
		for (int i = 0; i < sequence.size(); i++) {
			if (!targetTraceSequence.get(i).equals(sequence.get(i))) {
				return false;
			}
		}
		return true;
	}

	private boolean isTargetSuffix(ArrayList<Integer> sequence) {
		if (sequence.size() > targetTraceSequence.size()) {
			return false;
		}
		int offset = targetTraceSequence.size() - sequence.size();
		for (int i = 0; i < sequence.size(); i++) {
			if (!targetTraceSequence.get(offset + i).equals(sequence.get(i))) {
				return false;
			}
		}
		return true;
	}

	private String labelDomain(Label label) {
		if (label.frontier == null || label.frontier.head == null || label.frontier.tail == null) {
			return "empty";
		}
		return "[" + label.frontier.head.start + "," + label.frontier.tail.end + "]";
	}

	private void reverseInPlace(ArrayList<Integer> sequence) {
		for (int left = 0, right = sequence.size() - 1; left < right; left++, right--) {
			Integer tmp = sequence.get(left);
			sequence.set(left, sequence.get(right));
			sequence.set(right, tmp);
		}
	}

	/**
	 * 2026-05-24: normal forward label 缂?prefix-min normalize 闁告艾瀛╅弳锝嗘媴閹剧粯濮滃褏鍎戠槐?
	 * 闁哄牃鍋撻悘?reduced cost 闁烩晛鐡ㄧ敮鎾媰閽樺韬柡鍫氬亾闁告瑥纾顒勬晬鐏炶偐鐟濋煫鍥ф噺閻︹€斥枎閳ュ啿鏅欓柛蹇嬪妽椤?findMinimal闁?
	 */
	private static double forwardEndpointMin(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.tail == null) {
			return Utility.big_M;
		}
		return frontier.tail.getValue(frontier.tail.end);
	}

	/**
	 * 2026-05-24: normal backward label 缂?suffix-min normalize 闁告艾瀛╅弳锝嗘媴閹剧粯濮滈柛鎴濋獜缁?
	 * 闁哄牃鍋撻悘?reduced cost 闁烩晛鐡ㄧ敮鎾媰閽樺韬柡鍫氬亾鐎归潻濡囬顒勬晬濞戞ê娑ч柡?joinCost 闂侇叏绲块～鎺楀嫉椤忓嫬鏅欓柡鍌滄嚀閹粓宕犻弽顐ｇ暠闁告垼濮ら弳鐔煎箥瀹ュ浠橀悷?findMinimal闁?
	 */
	private static double backwardEndpointMin(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.head == null) {
			return Utility.big_M;
		}
		return frontier.head.getValue(frontier.head.start);
	}

	private PiecewiseLinearFunction cropToInterval(PiecewiseLinearFunction function, double start, double end) {
		PiecewiseLinearFunction cropped = new PiecewiseLinearFunction();
		// 2026-05-23: crop 濞戞挸绉磋ぐ褏鎲楁担鍝勨挅闁?segment闁挎稑濂旂弧鍐啺娴ｅ憡鍊辨慨婵勫劚閸ら亶寮弶鍨笚闁轰胶澧楀畵渚€濡?
		// shiftX() 闁?trimToDomain 闁告瑯浜炲﹢?domainStart/domainEnd闁挎稒绋戦々褔寮稿鍡欑闂佹彃濂旂粭澶愭煂瀹ュ牜鍟庨柨?
		// 闁告艾娴烽悽濠氬础婵犲倻鍘?label 濞村吋鑹捐ぐ褔鎳楁禒瀣祮 add 闁汇劌瀚崣鏇㈠礂鏉堚晛鈷栭柣鐐叉閻ｇ偓绋婃径濠勫幍闁稿繑绮岀花鎶芥晬鐏炶偐鐟濋柤鍐测偓鐔锋闁绘帟鍩栫€?Tmid 閻熶椒绀佹竟鈧柕?
		cropped.resetDomain(start, end);
		if (function == null || function.head == null || Utility.compareGt(start, end)) {
			return cropped;
		}
		// 2026-05-22: 闁告瑥鑻幃婊堝础婵犲倻鍘甸柛娆樺灥閸忔﹢宕氬顑惧仺闂侇偀鍋撻柛鏍ㄧ墪閸?Tmid 闁告娲滈崑锝夊Υ閸屾繄绠瑰☉鎿冧簽閸嬶絾绋夊鍛濞戞挸娴烽幋椋庣磼椤撶喎鈷栭悘鐐存穿缁?
		// 濞?join 闁哄啯鍎奸々锕傛嚄閻ｅ本鏆?Tmid 濠㈣泛瀚悥鍫曞极閺夋寧顐介柟閿嬫崄閻﹀孩绂掗崙銈囩闁搞儳濮甸婵囨交濞嗘挸娅″ǎ鍥ㄧ箘閺嗏偓闂傚棗鐖奸弳杈ㄦ償閿曗偓閻栧爼寮悧鍫斀闁?
		if (Utility.compareEq(start, end)) {
			if (!Utility.compareLt(start, function.head.start) && !Utility.compareGt(start, function.tail.end)) {
				addConstantSegmentOrPoint(cropped, start, end, function.evaluate(start));
			}
			return cropped;
		}
		for (Segment seg = function.head; seg != null; seg = seg.next) {
			if (Utility.compareEq(seg.start, seg.end)
					&& !Utility.compareLt(seg.start, start)
					&& !Utility.compareGt(seg.start, end)) {
				addConstantSegmentOrPoint(cropped, seg.start, seg.end, seg.getValue(seg.start));
				continue;
			}
			double segStart = Math.max(seg.start, start);
			double segEnd = Math.min(seg.end, end);
			if (Utility.compareLt(segStart, segEnd)) {
				cropped.addSegment(segStart, segEnd, seg.slope, seg.intercept);
			}
		}
		mergeAdjacentEqualSegments(cropped);
		return cropped;
	}

	private void addConstantSegmentOrPoint(PiecewiseLinearFunction target, double start, double end, double value) {
		target.addSegment(start, end, 0.0, value);
	}

	private void appendSegments(PiecewiseLinearFunction target, PiecewiseLinearFunction source) {
		if (target == null || source == null || source.head == null) {
			return;
		}
		for (Segment seg = source.head; seg != null; seg = seg.next) {
			target.addSegment(seg.start, seg.end, seg.slope, seg.intercept);
		}
	}

	private void mergeAdjacentEqualSegments(PiecewiseLinearFunction function) {
		if (function == null || function.head == null) {
			return;
		}
		Segment cur = function.head;
		while (cur.next != null) {
			if (Utility.compareEq(cur.end, cur.next.start) && Utility.compareEq(cur.slope, cur.next.slope)
					&& Utility.compareEq(cur.intercept, cur.next.intercept)) {
				cur.end = cur.next.end;
				cur.next = cur.next.next;
			} else {
				cur = cur.next;
			}
		}
		function.tail = cur;
	}

	private enum InsertStatus {
		DOMINATED, STORED_NO_EXPAND, STORED_AND_ENQUEUE
	}

	private static final class ColumnMidpointCandidate {
		final int columnId;
		final double reducedCost;

		ColumnMidpointCandidate(int columnId, double reducedCost) {
			this.columnId = columnId;
			this.reducedCost = reducedCost;
		}
	}

	private static final class MidpointFunctionArgmin {
		int count;
		double maxTime = Double.NaN;

		void accept(double time) {
			if (!Double.isFinite(time)) {
				return;
			}
			count++;
			maxTime = Double.isNaN(maxTime) ? time : Math.max(maxTime, time);
		}
	}

	private static final class MidpointColumnTimingStats {
		int count;
		double lastMin = Double.POSITIVE_INFINITY;
		double lastMax = Double.NEGATIVE_INFINITY;
		double lastSum;
		double lastAvg = Double.NaN;
		double halfMin = Double.POSITIVE_INFINITY;
		double halfMax = Double.NEGATIVE_INFINITY;
		double halfSum;
		double halfAvg = Double.NaN;
		ArrayList<Double> taskCompletions = new ArrayList<Double>();
		int taskCount;
		double taskMin = Double.NaN;
		double taskMax = Double.NaN;
		double taskSum;
		double taskAvg = Double.NaN;
		double taskMedian = Double.NaN;

		void accept(TWETColumnEvaluator.Timing timing) {
			count++;
			double lastCompletion = timing.lastCompletion;
			double halfCompletion = timing.halfCompletion;
			lastMin = Math.min(lastMin, lastCompletion);
			lastMax = Math.max(lastMax, lastCompletion);
			lastSum += lastCompletion;
			lastAvg = lastSum / count;
			halfMin = Math.min(halfMin, halfCompletion);
			halfMax = Math.max(halfMax, halfCompletion);
			halfSum += halfCompletion;
			halfAvg = halfSum / count;
			for (double completion : timing.completions) {
				if (Double.isFinite(completion)) {
					taskCompletions.add(Double.valueOf(completion));
					taskSum += completion;
				}
			}
		}

		void finish() {
			taskCount = taskCompletions.size();
			if (taskCount == 0) {
				return;
			}
			Collections.sort(taskCompletions);
			taskMin = taskCompletions.get(0).doubleValue();
			taskMax = taskCompletions.get(taskCount - 1).doubleValue();
			taskAvg = taskSum / taskCount;
			int middle = taskCount / 2;
			if (taskCount % 2 == 1) {
				taskMedian = taskCompletions.get(middle).doubleValue();
			} else {
				taskMedian = (taskCompletions.get(middle - 1).doubleValue()
						+ taskCompletions.get(middle).doubleValue()) * 0.5;
			}
		}
	}

	private static final class ColumnMidpointTimingCandidate {
		final int columnId;
		final TWETColumnEvaluator.Timing timing;

		ColumnMidpointTimingCandidate(int columnId, TWETColumnEvaluator.Timing timing) {
			this.columnId = columnId;
			this.timing = timing;
		}
	}

	static final class MidpointProbeNodeReuse {
		double bestExactTmid = Double.NaN;
		double bestExactMillis = Double.POSITIVE_INFINITY;
		double bestExactRatio = Double.POSITIVE_INFINITY;
		long bestExactLabelTotal = Long.MAX_VALUE;
		double lastExactTmid = Double.NaN;
		double lastExactMillis = Double.NaN;
		double lastExactRatio = Double.NaN;
		long lastExactLabelTotal;

		boolean hasBestExact() {
			return Double.isFinite(bestExactTmid) && Double.isFinite(bestExactMillis)
					&& Utility.compareGt(bestExactTmid, 0.0);
		}

		String considerExact(double tMid, double exactMillis, double ratio, long labelTotal,
				double timeTieTolerance, double balanceImprovementTolerance) {
			lastExactTmid = tMid;
			lastExactMillis = exactMillis;
			lastExactRatio = ratio;
			lastExactLabelTotal = labelTotal;
			if (!hasBestExact()) {
				updateBest(tMid, exactMillis, ratio, labelTotal);
				return "init";
			}
			boolean timeClose = isTimeClose(exactMillis, bestExactMillis, timeTieTolerance);
			if (timeClose && isBalanceMeaningfullyBetter(ratio, bestExactRatio, balanceImprovementTolerance)) {
				updateBest(tMid, exactMillis, ratio, labelTotal);
				return "balance";
			}
			if (!timeClose && Utility.compareLt(exactMillis, bestExactMillis)) {
				updateBest(tMid, exactMillis, ratio, labelTotal);
				return "time";
			}
			return "keep";
		}

		private void updateBest(double tMid, double exactMillis, double ratio, long labelTotal) {
			bestExactTmid = tMid;
			bestExactMillis = exactMillis;
			bestExactRatio = ratio;
			bestExactLabelTotal = labelTotal;
		}

		private boolean isTimeClose(double currentMillis, double incumbentMillis, double tolerance) {
			double base = Math.max(currentMillis, incumbentMillis);
			return Double.isFinite(base) && Utility.compareLe(Math.abs(currentMillis - incumbentMillis),
					base * tolerance);
		}

		private boolean isBalanceMeaningfullyBetter(double currentRatio, double incumbentRatio, double tolerance) {
			if (!Double.isFinite(currentRatio) || !Double.isFinite(incumbentRatio)) {
				return false;
			}
			double required = incumbentRatio * Math.max(0.0, 1.0 - tolerance);
			return Utility.compareLt(currentRatio, required);
		}
	}

	private static final class MidpointProbeResult {
		final double tMid;
		final double elapsedMillis;
		final int pops;
		final int forwardPops;
		final int backwardPops;
		final boolean forwardExhausted;
		final boolean backwardExhausted;
		final long forwardKept;
		final long backwardKept;
		final long forwardBoundSurvivors;
		final long forwardBoundPruned;
		final long backwardBoundPruned;
		final long forwardQueueRemaining;
		final long backwardQueueRemaining;
		final long forwardQueuePeak;
		final long backwardQueuePeak;
		final double keptScore;
		final double queueScore;
		final double boundScore;
		final double peakScore;
		final double remainingScore;

		MidpointProbeResult(double tMid, double elapsedMillis, int pops, boolean forwardExhausted, boolean backwardExhausted,
				int forwardPops, int backwardPops,
				long forwardKept, long backwardKept, long forwardBoundSurvivors,
				long forwardBoundPruned, long backwardBoundPruned, long forwardQueueRemaining, long backwardQueueRemaining,
				long forwardQueuePeak, long backwardQueuePeak) {
			this.tMid = tMid;
			this.elapsedMillis = elapsedMillis;
			this.pops = pops;
			this.forwardPops = forwardPops;
			this.backwardPops = backwardPops;
			this.forwardExhausted = forwardExhausted;
			this.backwardExhausted = backwardExhausted;
			this.forwardKept = forwardKept;
			this.backwardKept = backwardKept;
			this.forwardBoundSurvivors = forwardBoundSurvivors;
			this.forwardBoundPruned = forwardBoundPruned;
			this.backwardBoundPruned = backwardBoundPruned;
			this.forwardQueueRemaining = forwardQueueRemaining;
			this.backwardQueueRemaining = backwardQueueRemaining;
			this.forwardQueuePeak = forwardQueuePeak;
			this.backwardQueuePeak = backwardQueuePeak;
			this.keptScore = imbalance(forwardKept, backwardKept);
			this.queueScore = imbalance(forwardKept + forwardQueueRemaining, backwardKept + backwardQueueRemaining);
			this.boundScore = imbalance(forwardBoundSurvivors + forwardQueueRemaining, backwardKept + backwardQueueRemaining);
			this.peakScore = imbalance(forwardKept + forwardQueuePeak, backwardKept + backwardQueuePeak);
			this.remainingScore = imbalance(forwardQueueRemaining, backwardQueueRemaining);
		}

		double score(String mode) {
			String normalized = normalizeProbeScoreMode(mode);
			if ("kept".equals(normalized)) {
				return keptScore;
			}
			if ("bound".equals(normalized)) {
				return boundScore;
			}
			if ("peak".equals(normalized)) {
				return peakScore;
			}
			if ("remaining".equals(normalized)) {
				return remainingScore;
			}
			return queueScore;
		}

		double leftPressure(String mode) {
			String normalized = normalizeProbeScoreMode(mode);
			if ("kept".equals(normalized)) {
				return forwardKept;
			}
			if ("bound".equals(normalized)) {
				return forwardBoundSurvivors + forwardQueueRemaining;
			}
			if ("peak".equals(normalized)) {
				return forwardKept + forwardQueuePeak;
			}
			if ("remaining".equals(normalized)) {
				return forwardQueueRemaining;
			}
			return forwardKept + forwardQueueRemaining;
		}

		double rightPressure(String mode) {
			String normalized = normalizeProbeScoreMode(mode);
			if ("kept".equals(normalized)) {
				return backwardKept;
			}
			if ("bound".equals(normalized)) {
				return backwardKept + backwardQueueRemaining;
			}
			if ("peak".equals(normalized)) {
				return backwardKept + backwardQueuePeak;
			}
			if ("remaining".equals(normalized)) {
				return backwardQueueRemaining;
			}
			return backwardKept + backwardQueueRemaining;
		}

		int reliabilityRank(String mode) {
			if (forwardExhausted && backwardExhausted) {
				return 0;
			}
			return 1;
		}

		long totalPressure(String mode) {
			return Math.round(leftPressure(mode) + rightPressure(mode));
		}

		String compactSummary(String mode) {
			String normalized = normalizeProbeScoreMode(mode);
			return "t=" + tMid
					+ ",ms=" + elapsedMillis
					+ ",pop=" + pops
					+ ",sidePop=" + forwardPops + ":" + backwardPops
					+ ",ex=" + (forwardExhausted ? "F" : "f") + (backwardExhausted ? "B" : "b")
					+ ",kept=" + forwardKept + ":" + backwardKept
					+ ",q=" + forwardQueueRemaining + ":" + backwardQueueRemaining
					+ ",qPeak=" + forwardQueuePeak + ":" + backwardQueuePeak
					+ ",bound=" + forwardBoundSurvivors + ":" + backwardKept
					+ ",cb=" + forwardBoundPruned + ":" + backwardBoundPruned
					+ ",rank=" + reliabilityRank(mode)
					+ ",direction=" + pressureDirection(normalized)
					+ ",queueRatio=" + queueScore
					+ ",remainingRatio=" + remainingScore
					+ ",selectedScore=" + normalized + ":" + score(normalized)
					+ ",score=" + keptScore + "/" + queueScore + "/" + boundScore + "/" + peakScore + "/"
					+ remainingScore;
		}

		private static double imbalance(long left, long right) {
			double l = (double) left + 1.0;
			double r = (double) right + 1.0;
			return Math.max(l / r, r / l);
		}

		int pressureDirection(String mode) {
			double left = leftPressure(mode);
			double right = rightPressure(mode);
			if (Utility.compareGt(left, right)) {
				return 1;
			}
			if (Utility.compareLt(left, right)) {
				return -1;
			}
			return 0;
		}

	}

	private static final class PricingColumnCandidate {
		final int candidateId;
		final SequenceSignature signature;
		final TWETColumn column;
		final double reducedCost;

		PricingColumnCandidate(int candidateId, SequenceSignature signature, TWETColumn column, double reducedCost) {
			this.candidateId = candidateId;
			this.signature = signature;
			this.column = column;
			this.reducedCost = reducedCost;
		}
	}

	private static final class NgPair {
		final int first;
		final int second;
		final double reducedPairCost;

		NgPair(int first, int second, double reducedPairCost) {
			this.first = first;
			this.second = second;
			this.reducedPairCost = reducedPairCost;
		}
	}

	private static final class NonElementaryNegativeRoute {
		final ArrayList<Integer> sequence;
		final double reducedCost;

		NonElementaryNegativeRoute(ArrayList<Integer> sequence, double reducedCost) {
			this.sequence = new ArrayList<Integer>(sequence);
			this.reducedCost = reducedCost;
		}
	}

	private static final class SinglePointStore<L extends FunctionLabel> {
		// 2026-06-13: ng-DSSR 闁?dominance key 濞达綀娉曢弫?full-domain dominanceSet闁挎稒鐭爔tensionSet 闁告瑯浜濈敮鍫曞礆鐠鸿櫣绉奸柛鎾崇Т瀹曟劙宕洪悢绋库挅閻忕偞娲忛埀?
		final HashMap<PackedBitSet, L> bestByDominanceKey = new HashMap<PackedBitSet, L>();
		final ArrayList<ArrayList<L>> liveLabelsByCardinality = new ArrayList<ArrayList<L>>();
	}

	private abstract static class FunctionLabel extends Label implements Comparable<Label>, SriStateLabel {
		final int labelId;
		final PackedBitSet ngMemorySet;
		final PackedBitSet extensionSet;
		final int extensionCardinality;
		final PiecewiseLinearFunction noSriFrontier;
		final double noSriMinReducedCost;
		final byte[] sriCounts;
		final double sriPenalty;
		final String sriStateKey;
		/** join 闂傚啳鍩栭灞剧▔鐎涙ɑ顦ч悽顖氭啞閺嗙喎顕欓懜闈涚彇闁告艾娴峰▓鎴﹀礄閼恒儲娈剁紓鍌涙尭閻°劑鏁嶅▽纰糱el frontier 闁告帗绋戠紓鎾诲触鎼存繄鐟濋柛鎰С閹便劑寮ㄩ惂鍝ョ闁告瑯鍨禍鎺斺偓鐟邦槸閸欏繑寰勫鍥ㄦ殢闁?*/
		PiecewiseLinearFunction joinExtendedFrontier;

		FunctionLabel(int labelId, int jid, PackedBitSet visitedSet, PackedBitSet dominanceSet,
				PackedBitSet extensionSet, PackedBitSet ngMemorySet, PiecewiseLinearFunction frontier,
				PiecewiseLinearFunction noSriFrontier, byte[] sriCounts, double minReducedCost,
				double noSriMinReducedCost, double sriPenalty) {
			super(jid, null, visitedSet, dominanceSet, frontier, minReducedCost);
			this.labelId = labelId;
			this.extensionSet = extensionSet;
			this.extensionCardinality = extensionSet == null ? 0 : extensionSet.cardinality();
			this.ngMemorySet = ngMemorySet;
			this.noSriFrontier = noSriFrontier == null ? frontier : noSriFrontier;
			this.noSriMinReducedCost = noSriMinReducedCost;
			this.sriCounts = sriCounts == null ? new byte[0] : sriCounts;
			this.sriPenalty = sriPenalty;
			this.sriStateKey = buildSriStateKey(this.sriCounts);
		}

		@Override
		public String sriStateKey() {
			return sriStateKey;
		}

		@Override
		public byte[] sriCounts() {
			return sriCounts;
		}

		private static String buildSriStateKey(byte[] counts) {
			if (counts == null || counts.length == 0) {
				return "";
			}
			StringBuilder key = new StringBuilder(counts.length);
			for (int i = 0; i < counts.length; i++) {
				if (i > 0) {
					key.append(',');
				}
				key.append((int) counts[i]);
			}
			return key.toString();
		}

		@Override
		public int compareTo(Label other) {
			if (other instanceof FunctionLabel) {
				return compareReducedCost(this, (FunctionLabel) other);
			}
			int reducedCostCompare = Double.compare(minReducedCost, other.minReducedCost);
			if (reducedCostCompare != 0) {
				return reducedCostCompare;
			}
			return Integer.compare(jid, other.jid);
		}
	}

	private static final class ForwardLabel extends FunctionLabel {
		final ForwardLabel father;
		final int depth;

		ForwardLabel(int labelId, int jid, ForwardLabel father, PackedBitSet visitedSet, PackedBitSet dominanceSet,
				PackedBitSet extensionSet, PackedBitSet ngMemorySet, PiecewiseLinearFunction frontier,
				PiecewiseLinearFunction noSriFrontier, byte[] sriCounts, double sriPenalty) {
			super(labelId, jid, visitedSet, dominanceSet, extensionSet, ngMemorySet, frontier, noSriFrontier, sriCounts,
					forwardEndpointMin(frontier), forwardEndpointMin(noSriFrontier == null ? frontier : noSriFrontier),
					sriPenalty);
			this.father = father;
			this.depth = father == null ? 0 : father.depth + 1;
		}
	}

	private static final class BackwardLabel extends FunctionLabel {
		final BackwardLabel father;
		final boolean isSinkRoot;

		BackwardLabel(int labelId, int jid, BackwardLabel father, PackedBitSet visitedSet, PackedBitSet dominanceSet,
				PackedBitSet extensionSet, PackedBitSet ngMemorySet, PiecewiseLinearFunction frontier,
				PiecewiseLinearFunction noSriFrontier, byte[] sriCounts, double sriPenalty, boolean isSinkRoot) {
			super(labelId, jid, visitedSet, dominanceSet, extensionSet, ngMemorySet, frontier, noSriFrontier, sriCounts,
					backwardEndpointMin(frontier), backwardEndpointMin(noSriFrontier == null ? frontier : noSriFrontier),
					sriPenalty);
			this.father = father;
			this.isSinkRoot = isSinkRoot;
		}
	}
}
