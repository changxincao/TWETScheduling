package TWETBPC.GC;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
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
 * no-cut 闂佸憡鐟ラ懟顖炲箖?pricing 闂佹眹鍔岀€氼參顢楀鍐惧殨?half-domain GCBB-style 闂佺硶鏅涢幖顐﹀闯椤栫偞鍋嬮柛顐ｇ▓閸?
 * <p>
 * 闂佸憡鐟禍婵嗭耿娓氣偓瀹曠兘濡搁妷銉т粴闂佽桨绶氶。锔剧箔閸岀偞鈷旈柟閭﹀幗瀵捇鏌熼幖顓濈凹闁哄苯娲俊瀛樻媴妞嬪海鎳?forward/backward 闂傚倸鍟伴崰搴ㄥ垂椤忓牊鐒鹃柛濠勫櫏濞煎爼鎮楅悷鐗堟拱闁哄棴缍侀幊鎾澄旈埀顒勫极閺嶎厼绫嶉悹浣告贡缁€澶愭煛閸偄澧查柣銈呮缁辨帡骞樼€甸晲鍑介梺褰掓涧缁夌銇愰崣澶嬪婵犲ň鍋撻悹?exact pricing
 * certificate闂佹寧绋掔粙鏍偓姘ュ灪濞煎繘骞嗚閻?{@link TWETBPCConfig#maxExactPricingColumns}闂佹寧绋戦惌浣烘崲閺嶎厽鐓傞悘鐐舵濞懷囨偠濞戞牕濡块柕鍥ㄥ哺閸ㄦ儳顭ㄩ崟顑跨帛婵犮垼鍩栧銊╁极閹捐绠?K 闂佸搫顥￠妶鍥舵Ч闂佸憡甯楅妵娑㈠焵椤掍胶缂氶柍?
 * <p>
 * 2026-05-22: 闁哄鏅滈悷鈺呭闯闁垮鈻旂€广儱鎳庨弲娆愮箾瀹€鍐╃《闁轰降鍊濆顔款槻闁汇倕瀚伴幃鎶藉箥椤旂晫鏆犻梺鐐藉劜缁矂骞冮幘瀵糕枖闁逞屽墯缁嬪顢旈崘顓炲箑闂傚倸鍊搁顓㈠磻?join闂佺偨鍎茬换鍐偉閿濆鐓傞煫鍥ㄦ⒒閸ㄨ偐绱掑☉娆愬珪缂佽鲸绻堥幊鎾朵沪閻愵儷锕傛煛閳ь剛鎲撮崟顐や海闂佸憡绮岄惌渚€顢欓幋锕€妫橀柛銉椤忛亶鏌ゅ畡閭﹀殶婵?
 * 闂佺偨鍎茬粩绶妑ward 闂佸憡鎸哥粔鍓佹?+ crossing arc (i,r) + backward 闂佸憡鑹惧ù椋庢閹达箑鐏虫繝闈涚墛閻ｈ京鈧鍟崟顐紳闂佽浜介崕顖炲焵?
 * 缂備緡鍋夐褔骞冮弴鐔衡枖妞ゆ挾濮甸悾?GCNGBB 婵烇絽娲︾换鍕汲閳ь剟鏌￠崘锝嗘珕婵犫偓閿熺姴宸濋柦妯侯槹閸婃娊鏌ㄥ☉娆戔槈缂傚秴顑夊畷婊冾吋閸パ屾毉闂佸搫鐗滈崜娆忥耿?label 婵炴垶鎼╅崢鎯庨鈧獮?ng-memory闂佹寧绋戦懟顖炴嚐閻斿吋鍋?DSSR 闂備緡鍋呴崝姗€鎮块崱娑樼哗闁告挷鐒﹁ぐ?
 * ng-neighborhood闂侀潧妫楅崐璇裁规径灞稿亾?{@link GCBidirectional}闂佹寧绋戦張顒€锕㈤幍顔惧暗閻犲洦褰冪敮銉╂倵閻熺増婀伴柡鍡秮閹粙鎮㈢粙璺ㄤ海 forward/backward 婵炴垶鎸堕崐鎾诲疾?label
 * table闂佹寧绋戦懟顖炲疮閳ь剛绱撴担鍝勬瀺缂佹梹鎸冲畷?crossing-arc final join闂佹寧绋掗惌顦時ward->sink 闂佽　鍋撻悹楦挎閸熸彃鈽夐弮鍌氭瀻闁艰崵鍠栧畷?final join 濠电偟绻濋懗鍫曞煝婵傜违濞ｅ洨鐏抜n
 * 闂佸憡鑹炬鎼佸储濞戙垹绠?ng-memory 濠碘槅鍋€閸嬫捇鏌＄仦璇插姕閻庢岸绠栭獮鎺楀Ψ閵夈儲鍊柣搴ｎ攰椤鍩€椤戭剙绉剁粈澶愭煕閹邦剛校濞寸媭鍠楀鍕吋閸ャ劌鐒搁柣搴℃贡閸嬫稓鑺遍銏犵婵°倐鍋撻柛銊ょ矙瀵?elementary/non-elementary闂侀潧妫楅崐璺ㄥ垝椤栨粍濯奸柕鍫濇噹濞懷囨偣娴ｅ弶娅堢紒鈧€ｎ喗鍎?
 * elementary 闂佸憡甯楅〃澶屾崲濮椻偓瀹曟濡烽敂鑺ュ闂?top-K 闂佺锕ラ悷鈺呭焵椤掆偓椤︿即鎯堝鍫熸櫖鐎光偓閸愵亶妲归梺?non-elementary 闁瑰吋娼欑换鎰板垂椤忓牊鍋ㄩ柕濞垮€楅懝楣冩煛閸パ呮憼闁?ng-set闂佹寧绋掔粙鏍儊閺嶎厼妫樻い鎾跺仧绾惧鏌涜箛姘彧濠⒀嶇畱椤曪綁鍩€椤掑嫬绫嶉悹浣告贡缁€?
 * non-elementary ng-relaxed 闂佸憡甯楅妵鐐靛姬閸愨晛顕辨慨妯块哺缁绢垶鏌熼幁鎺戝姤缂佺粯锕㈠畷妤呭Ψ閵夈垹浜炬繛鍡樻尨閸嬫挻寰勭€ｎ剚寤洪梺?
 * <p>
 * 閻熸粎澧楅幐鍛婃櫠閻樼粯鍋嬮柛顐ゅ枑閹烽亶鏌涜箛鎾跺缂佺粯姘ㄩ幏?elementary 闂佸憡鐟ラ懟顖炲箖濠婂牆绀勯柤鎭掑劜濞堝爼姊洪锝呯瑲閻㈩垰缍婂畷?T^mid 闂佸憡顨呴敃銈夋偂濞嗘垶瀚氭い鎾寸箘閻ゅ懏鎱ㄥ┑鎾舵偧闁炽儲锕㈤弫?
 * 1. forward label 闁诲孩绋掗敋闁稿绉瑰畷?[ell, Tmid]闂?
 * 2. backward label 闁诲孩绋掗敋闁稿绉瑰畷?[Tmid, rho]闂?
 * 3. join 闂佸搫鍟晶搴ㄥ极閵堝洦濯奸柣鎴炆戦悗顕€姊洪幓鎺旂伇婵炲牊鍨归弫顕€宕ㄦ繝鍐╊啀閻庣偣鍊涢崺鏍偓姘喘閺佸秶浠﹂悡搴Щ闂佸搫鍟崕鑹版＂婵?forward 闂佸憡鐟ラ崯鍨暦閹版澘鏄ラ柣鏃堟敱鐎?backward 閻庡綊娼荤粻鎴濈暦閹版澘鏄ラ柣鏇炲€荤粈澶愭煟閹烘洘纭鹃柟顔筋殜楠?crossing arc 闁诲酣娼х紞濠勭礊閸儲鍎庣紒瀣仢椤綁鏌?
 * 4. 婵帗绋掗…鍫ヮ敇婵犳碍鍎庨悗娑櫭径宥吤归敐鍫熺《闁?label/join 闂佽浜介崝宀勵敋闁秴绀勯柧蹇氼潐閻?reduced cost 闂佸憡鐟ョ粔鐢垫暜瑜版帒绀勯柛婵嗗閻忔瑩鏌熺€涙ê濮堟繝鈧导瀛樻櫖婵炴垶锕╁ú銈夋⒒閸ワ絽浜鹃柣搴ｆ嚀閺堫剟寮抽敐鍡樺劅闊洦鎸搁悘娆忣熆鐠鸿櫣校闁绘濞婇弫宥囦沪閽樺顔夐梺鐟扮仛閹稿摜妲?
 * {@link Configure#debugBPCPricingColumnCheck}闂?
 */
public class GCNGBBStyleBidirectionalNgDssr {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;
	private static final int DUPLICATE_REPAIR_DIAGNOSTIC_ROUTE_LIMIT = 10;
	/** 无 SRI 时所有 label 共享空状态；该数组只读，避免每次扩展分配零长度数组。 */
	private static final byte[] EMPTY_SRI_COUNTS = new byte[0];
	private static final HashSet<Integer> FULL_MIDPOINT_DIAGNOSTIC_DONE = new HashSet<Integer>();
	/** 2026-07-16: group-envelope 已证明可剪时，按 BitSet 连续区间跳过，旧逐项扫描仅用于 A/B 回退。 */
	private static final boolean JOIN_PREFILTER_SKIP_RUNS = Boolean.parseBoolean(
			System.getProperty("twet.bpc.ngDssrJoinPrefilterSkipRuns", "true"));
	private enum LabelQueueOrdering {
		REDUCED_COST, TIME, REACHABLE_SIZE
	}

	private enum JoinBestThresholdMode {
		ZERO,
		BEST_UB,
		// 2026-05-31: 濠电姷顣介崑鎾诲级?record-only 闁诲酣娼у﹢閬嶅矗鎼粹垾鐔煎灳瀹曞洨顢呴梺鎸庣⊕缁嬫劗妲愭导鏉戠闊洦鎸惧В灞炬叏閿濆懐绠洪柣銈呮濞煎寮幐搴ｎ槬闂佸憡甯楅〃鍡涘汲閻斿吋鏅€光偓閸曨厼绗￠柣鐘遍檷閸婃挾绮径瀣婵犲ň鍋撻悹鎰枛瀹曘儲鎯旈敐鍥ㄦ毎濠殿喗绻愮徊鐣屾閿涘嫭宕夋い鏍ㄦ皑缁愮偛霉閿濆牊纭堕柡浣靛€濇俊?
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
	/** join 时不计入 ng-memory 冲突的 source 与 zero-dual job。 */
	private PackedBitSet joinMemoryIgnoredJobs;
	private int zeroDualExcludedJobCount;
	private int nextLabelId;
	private int nextCandidateId;
	private LabelQueueOrdering queueOrdering;
	private JoinBestThresholdMode joinBestThresholdMode;
	private CompletionBoundCalculator.Relaxation completionBoundRelaxation;
	private CompletionBoundCalculator.QueueOrdering completionBoundQueueOrdering;
	private CompletionBoundCalculator.Bounds completionBounds;
	private final boolean completionBoundFlatFunctionQuery;
	private boolean[][] completionBoundFixedArc;
	/** 当前 exact solve 内可参与内部机器定价的 job。 */
	private PackedBitSet reachabilityCandidateJobs;
	/** 当前 exact solve 内固定的 forward 直接扩展弧掩码，避免每个 label 重复查询禁弧。 */
	private PackedBitSet[] forwardExtensionArcMaskByFrom;
	/** 当前 exact solve 内固定的 backward 直接扩展弧掩码，索引是 suffix 的第一个节点。 */
	private PackedBitSet[] backwardExtensionArcMaskBySuccessor;
	private double bestGeneratedReducedCost;
	private double lastRelaxedRoundBestReducedCost;
	private boolean feasibilityPhaseOneObjectiveMode;

	// 2026-05-22: 闂佸憡鐟ラ懟顖炲箖?midpoint闂佹寧绋戦懟顖濄亹瑜忛埀顒傛暩閹虫挾绱炵€ｎ喖绀?pricing 闁哄鍎愰崰妤€锕㈡笟鈧顐﹀醇濞戞帒浜?
	private double tMid;
	// 2026-05-24: 闂佸搫鐗滈崜婵嬫偪?bidirectional pricing 闁诲骸婀遍崑銈咁瀶椤栨稒濯撮悹鎭掑妽閺嗗繘鏌ｉ妸銉ヮ仼鐟滄澘鎲＄粭?horizon闂?
	// 闂佸吋鐪归崕杈╃礊鐎ｎ喖绀堢€广儱瀚畷鏌ユ煕閺傝　鍋撻崘鑼槮缂備焦妫忛崹鎶芥偘閵夆晛鍙婇幖娣灪閳绘棃鎮樿箛鎾剁缂侇煈鍓熷畷妤呭Ω閵壯勬嫳 CmaxH闂佹寧绋戦懟顖氼潩閵娾晜鍋ㄩ柕濞垮劤閺嗗﹪鏌涘Ο渚剮缂?midpoint 闂佹眹鍔岀€氼剝銇愰崫銉х煋濞村吋鐟х粈?
	// 闂備緡鍓欓悘婵嬪储?backward sink root 闂?Tmid 闁哄鏅涘ú銈堛亹閹间焦鍤€閻忕偟鏅弳姘舵煕韫囧濮傞柡鍡氶哺缁嬪顓奸崨顓熺様闂佹椿浜為崰搴ㄦ偪閸曨垰鍐€闁搞儮鏅╅崝顕€鏌?
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
	/** 2026-07-12: 仅记录有限 probe 与完整 exact 的有方向工作量，不参与 Tmid 选择。 */
	private int midpointProbeReferenceDirection;
	private int midpointProbeSelectedDirection;
	private double midpointProbeSelectedForwardMillis = Double.NaN;
	private double midpointProbeSelectedBackwardMillis = Double.NaN;
	private boolean midpointProbeLabelsReadyForJoin;
	private boolean midpointProbePerformed;
	private boolean midpointProbeStableFreezeUsed;
	private long midpointStrategyNanos;
	private static final int MIDPOINT_FREEZE_MIN_EXACT_CALLS = 5;
	private static final int MIDPOINT_FREEZE_STABLE_SELECTIONS = 3;
	private static final int MIDPOINT_FREEZE_SKIPPED_CALLS = 5;
	private static final double MIDPOINT_FREEZE_HORIZON_TOLERANCE = 0.01;
	// 2026-05-22: 閻熸粎澧楅幐鍛婃櫠閻樼鍋撶憴鍕叝闁绘粠鍨卞顏堫敊閻愵剛鏆?job-level 闂佸憡鏌ｉ崝宥夊焵?H_j 缂傚倸鍊归幐鎼佹偤閵娾晛违?
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
	// 2026-05-24: 闂佸憡鐟禍婵嗭耿娓氣偓瀵晫娑甸崨顓囨繈鏌ｉ幇鎵冲亾濞戞氨鎳嶅┑鐐插閸撴繂锕?cut dual 闂佸搫鍟抽鎰濠曠櫡_j profitable window 闂佸綊娼х粔宕囨崲濮樿埖鍋╂繛鍡楁捣閻熷繘鎮峰▎鎰瑐缂佹顦辩划鍨緞婵犲嫮顢呮繛鎾寸缁诲啫鐣垫笟鈧俊?
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
	private long joinKnownElementaryPairs;
	private long joinKnownNonElementaryPairs;
	private long joinNonElementaryWitnessLowerBoundPruned;
	private long joinNonElementaryWitnessValuePruned;
	private long joinRangeLowerBoundChecks;
	private long joinRangeLowerBoundPruned;
	private long joinEnvelopeForwardGroups;
	private long joinEnvelopeBackwardGroups;
	private long joinEnvelopeForwardLabels;
	private long joinEnvelopeBackwardLabels;
	private long joinEnvelopeSegments;
	private long joinEnvelopeGroupPairs;
	private long joinEnvelopeGroupPairsPruned;
	private long joinEnvelopeFunctionEvaluations;
	private long joinEnvelopePrefilterGroupPairs;
	private long joinEnvelopePrefilterGroupPairsPruned;
	private long joinEnvelopePrefilterPotentialPairsPruned;
	private long joinEnvelopePrefilterFunctionEvaluations;
	private long joinEnvelopeBuildNanos;
	private long joinEnvelopeJoinNanos;
	private long exactTotalNanos;
	private long exactInitializeNanos;
	private long exactInitializeSetupNanos;
	private long exactInitializeDiagnosticsNanos;
	private long exactInitializeSriNanos;
	private long exactInitializeWindowNanos;
	private long exactInitializeNgNeighborhoodNanos;
	private long exactInitializeCompletionBoundNanos;
	private long exactInitializePreCertificateNanos;
	private long exactInitializeMidpointProbeNanos;
	private long exactInitializeStateNanos;
	private long exactInitializeFullMidpointDiagnosticNanos;
	private long exactBackwardSinkNanos;
	private long exactForwardExpandNanos;
	private long exactBackwardExpandNanos;
	private long exactJoinCompactNanos;
	private long exactJoinNanos;
	private long exactFinalizeNanos;
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
	private long backwardExtensionCandidates;
	private long backwardExtensionArcPruned;
	private long backwardExtensionInfeasible;
	private long backwardExtensionConstructed;
	private long backwardExtensionBoundSurvivors;
	private long forwardExtensionArcCheckNanos;
	private long forwardExtensionBuildNanos;
	private long forwardExtensionWindowCheckNanos;
	private long forwardExtensionFunctionNanos;
	private long forwardExtensionStateNanos;
	private long forwardExtensionBoundCheckNanos;
	private long forwardExtensionInsertNanos;
	private long forwardDominanceGraphInsertNanos;
	private long forwardExtensionQueueNanos;
	private long backwardExtensionArcCheckNanos;
	private long backwardExtensionBuildNanos;
	private long backwardExtensionWindowCheckNanos;
	private long backwardExtensionFunctionNanos;
	private long backwardExtensionStateNanos;
	private long backwardExtensionBoundCheckNanos;
	private long backwardExtensionInsertNanos;
	private long backwardDominanceGraphInsertNanos;
	private long backwardExtensionQueueNanos;
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
	private boolean ngDssrUseMinimumNewPairsSegmentUpdate;
	private int ngDssrMinimumSegmentRoutesConsidered;
	private int ngDssrMinimumSegmentRoutesAlreadyBlocked;
	private int ngDssrMinimumSegmentRoutesUpdated;
	/** 当前 DSSR 轮次为获得有效更新实际扫描、跳过和采用的 route 数。 */
	private int ngDssrRoundRoutesConsidered;
	private int ngDssrRoundRoutesAlreadyBlocked;
	private int ngDssrRoundRoutesUpdated;
	private int ngDssrTotalNonElementaryNegativeSeen;
	private int ngDssrTotalElementaryColumnsReturned;
	private int ngDssrRoundNonElementaryNegativeSeen;
	private int ngDssrRoundElementaryColumnsReturned;
	/** 同一次 DSSR 内最近一次 probe 选出的 Tmid；后续轮次复用并周期校正。 */
	private double ngDssrReusableTmid;
	private int ngDssrLastMidpointProbeRound;
	private double ngDssrPreviousRoundForwardMillis;
	private double ngDssrPreviousRoundBackwardMillis;
	private StringBuilder ngDssrMidpointByRound;
	private boolean ngDssrTraceNgSetStats;
	private boolean ngDssrTraceNgSetMembers;
	/** 诊断每轮最优负非基本序列与上一轮候选集合的关系，不参与正式 DSSR 更新。 */
	private boolean ngDssrTraceRoundRouteRelation;
	private HashMap<SequenceSignature, NonElementaryNegativeRoute> ngDssrPreviousRoundNegativeRoutes;
	private HashMap<SequenceSignature, NonElementaryNegativeRoute> ngDssrCurrentRoundNegativeRoutes;
	private ArrayList<String> ngDssrRoundAddedPairs;
	private StringBuilder ngDssrRoundRouteRelation;
	/** 只读诊断：尝试把每轮最优非基本路径删重复修复为基本列，不改变正式 DSSR。 */
	private boolean ngDssrDuplicateRepairDiagnostic;
	private ArrayList<NonElementaryNegativeRoute> ngDssrDuplicateRepairCandidates;
	private StringBuilder ngDssrDuplicateRepairSummary;
	private long ngDssrDuplicateRepairEvaluatorCalls;
	private long ngDssrDuplicateRepairNanos;
	private int ngDssrDuplicateRepairAttempted;
	private int ngDssrDuplicateRepairFeasible;
	private int ngDssrDuplicateRepairNegative;
	private int ngDssrDuplicateRepairAdditional;
	private boolean ngDssrHistoryWarmStartApplied;
	private boolean ngDssrSameNodeWarmStartApplied;
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
		this.completionBoundFlatFunctionQuery = Boolean.parseBoolean(System.getProperty(
				"twet.bpc.completionBoundFlatFunctionQuery", "true"));
	}

	private void initializeNgNeighborhoods(LP lp) {
		ngNeighborhoodByJob = new PackedBitSet[data.n + 2];
		for (int job = 1; job <= data.n; job++) {
			ngNeighborhoodByJob[job] = new PackedBitSet(data.n + 2);
		}
		prepareInitialRepeatabilityFilter(lp);
		ngDssrHistoryWarmStartApplied = false;
		ngDssrSameNodeWarmStartApplied = false;
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
		int targetSize = config.resolveNgDssrInitialNgSetSize(data.n);
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
			// 2026-07-13: 两个历史名称当前都按本轮 dual 的 pair reduced cost 排序，保留别名仅为兼容旧命令。
			addDualPairNgNeighborhoods(lp);
			return;
		}
		if ("perJobFeasiblePair".equalsIgnoreCase(mode)) {
			addPerJobFeasiblePairNgNeighborhoods(lp, targetSize);
			applyBoundedSameNodeWarmStart(lp);
			return;
		}
		if ("perJobRepeatCost".equalsIgnoreCase(mode)) {
			addPerJobRepeatCostNgNeighborhoods(lp, targetSize);
			applyBoundedSameNodeWarmStart(lp);
			return;
		}
		if ("nearestRepeatHybrid".equalsIgnoreCase(mode)) {
			int nearestSize = Math.max(0, targetSize - 1);
			for (int job = 1; job <= data.n; job++) {
				addNearestJobsToNgNeighborhood(job, nearestSize);
			}
			addPerJobRepeatCostNgNeighborhoods(lp, targetSize);
			applyBoundedSameNodeWarmStart(lp);
			return;
		}
		if ("nearestK".equalsIgnoreCase(mode)) {
			for (int job = 1; job <= data.n; job++) {
				addNearestJobsToNgNeighborhood(job, targetSize);
			}
			applyBoundedSameNodeWarmStart(lp);
			return;
		}
		throw new IllegalArgumentException("Unsupported ngDssrInitialNgSetMode: " + mode);
	}

	private boolean canUseHistoryWarmStart(LP lp) {
		if (!isRootNode(lp)) {
			return true;
		}
		// 2026-07-03: root 闂佸憡甯楃换鍌烇綖閹邦厽浜ゆい鎾寸箓閺佲晛顫楀☉娆樼劸妞ゆ挾绮粙澶婎吋閸ャ劍娈㈤梺鍛娒Λ妤勩亹閸洘鏅繛鎴灻～?cut 闂佸憡鑹惧ù宄扳枔?root 闁哄鏅╅崢娲船椤掑嫬绀傚ù锝囩摂閸熷懎顭跨捄铏剐ら柡浣靛€濋弫宥呯暆閸曨亞绱氶梺绋跨箰缁夊綊宕抽崨濠傜窞鐎广儱鎷嬮崝鍛槈閺冨倸啸婵炴潙顦扮€?ng-set闂?
		return config.ngDssrHistoryWarmStartUseRoot || (lp != null && !lp.getActiveCutIds().isEmpty());
	}

	private boolean isRootNode(LP lp) {
		return lp == null || lp.getNode() == null || lp.getNode().depth == 0;
	}

	private void applyBoundedSameNodeWarmStart(LP lp) {
		if (historyWarmStart != null && historyWarmStart.applySameNode(ngNeighborhoodByJob,
				currentNodeId(lp), currentActiveCutIds(lp), config)) {
			filterInitialNgMembers();
			ngDssrSameNodeWarmStartApplied = true;
		}
	}

	private int currentNodeId(LP lp) {
		return lp == null || lp.getNode() == null ? Integer.MIN_VALUE : lp.getNode().id;
	}

	private List<Integer> currentActiveCutIds(LP lp) {
		return lp == null ? Collections.<Integer>emptyList() : lp.getActiveCutIds();
	}

	private void filterInitialNgMembers() {
		if (ngDssrInitialRepeatableMember == null) {
			return;
		}
		for (int job = 1; job <= data.n; job++) {
			PackedBitSet set = ngNeighborhoodByJob[job];
			for (int member = set.nextSetBit(1); member >= 1 && member <= data.n;) {
				int next = set.nextSetBit(member + 1);
				if (!isInitialNgMemberAllowed(member)) {
					set.remove(member);
				}
				member = next;
			}
		}
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
				+ pricingSetupCost(from, to) + pricingSetupCost(to, from);
	}

	/**
	 * 每个 ng-set 行独立选择 K 个 pair reduced cost 最小的可行成员。
	 * N_center 中的 member 用于阻止 member -> center -> member，因此时间可行性必须按这个方向检查。
	 */
	private void addPerJobFeasiblePairNgNeighborhoods(final LP lp, int targetSize) {
		if (targetSize <= 0) {
			return;
		}
		Node node = lp == null ? null : lp.getNode();
		boolean useExactTimeIndexedRepeatability = useExactTimeIndexedRepeatability(node);
		for (int center = 1; center <= data.n; center++) {
			final int centerJob = center;
			ArrayList<NgPair> candidates = new ArrayList<NgPair>();
			for (int member = 1; member <= data.n; member++) {
				if (member != centerJob && isInitialNgMemberAllowed(member)) {
					candidates.add(new NgPair(centerJob, member, ngPairReducedCost(lp, centerJob, member)));
				}
			}
			Collections.sort(candidates, new Comparator<NgPair>() {
				@Override
				public int compare(NgPair left, NgPair right) {
					int byCost = compareDoubleAsc(left.reducedPairCost, right.reducedPairCost);
					return byCost != 0 ? byCost : Integer.compare(left.second, right.second);
				}
			});
			for (int i = 0; i < candidates.size()
					&& ngNeighborhoodByJob[centerJob].cardinality() < targetSize; i++) {
				int member = candidates.get(i).second;
				if (canRepeatJobViaCurrentEffectiveWindow(node, member, centerJob,
						useExactTimeIndexedRepeatability)) {
					ngNeighborhoodByJob[centerJob].add(member);
				}
			}
		}
	}

	/**
	 * 2026-07-13: 按 member -> center -> member 重复段的最小增量 reduced cost 选择每行成员。
	 * 与只判断可行性的 pair 模式相比，这里同时计入重复段上的真实时间惩罚，避免优先选择
	 * dual 较大但时间代价很高的长重复环。该分数只决定初始 ng-set，不参与定价证书。
	 */
	private void addPerJobRepeatCostNgNeighborhoods(final LP lp, int targetSize) {
		if (targetSize <= 0) {
			return;
		}
		Node node = lp == null ? null : lp.getNode();
		boolean useExactTimeIndexedRepeatability = useExactTimeIndexedRepeatability(node);
		for (int center = 1; center <= data.n; center++) {
			ArrayList<NgPair> candidates = new ArrayList<NgPair>();
			for (int member = 1; member <= data.n; member++) {
				if (member == center || !isInitialNgMemberAllowed(member)) {
					continue;
				}
				double repeatCost = repeatCycleReducedCost(lp, node, member, center,
						useExactTimeIndexedRepeatability);
				if (!Utility.isBigMValue(repeatCost)) {
					candidates.add(new NgPair(center, member, repeatCost));
				}
			}
			Collections.sort(candidates, new Comparator<NgPair>() {
				@Override
				public int compare(NgPair left, NgPair right) {
					int byCost = compareDoubleAsc(left.reducedPairCost, right.reducedPairCost);
					return byCost != 0 ? byCost : Integer.compare(left.second, right.second);
				}
			});
			for (int i = 0; i < candidates.size()
					&& ngNeighborhoodByJob[center].cardinality() < targetSize; i++) {
				ngNeighborhoodByJob[center].add(candidates.get(i).second);
			}
		}
	}

	private double repeatCycleReducedCost(LP lp, Node node, int member, int center,
			boolean useExactTimeIndexedRepeatability) {
		if (PricingCompatibility.isRequiredOutsourcedJob(node, member)
				|| PricingCompatibility.isRequiredOutsourcedJob(node, center)
				|| isOrdinaryArcUnavailableForRepeatability(node, member, center)
				|| isOrdinaryArcUnavailableForRepeatability(node, center, member)) {
			return Utility.big_M;
		}
		double memberStart = effectiveJobHStart[member];
		double memberEnd = effectiveJobHEnd[member];
		if (Utility.compareGt(memberStart, memberEnd)) {
			return Utility.big_M;
		}
		double fixedReducedCost = ngPairReducedCost(lp, member, center);
		if (!useExactTimeIndexedRepeatability) {
			double firstLeg = repeatabilityDuration(member, center);
			double secondLeg = repeatabilityDuration(center, member);
			double lower = Math.max(memberStart,
					Math.max(effectiveJobHStart[center] - firstLeg,
							memberStart - firstLeg - secondLeg));
			double upper = Math.min(memberEnd,
					Math.min(effectiveJobHEnd[center] - firstLeg,
							memberEnd - firstLeg - secondLeg));
			if (Utility.compareGt(lower, upper)) {
				return Utility.big_M;
			}
			// 非整数实例只用于排序：分别取两个受限区间的最小惩罚，保持计算轻量。
			double centerPenalty = data.penaltyFunction[center]
					.findMinimalInRange(lower + firstLeg, upper + firstLeg);
			double memberPenalty = data.penaltyFunction[member]
					.findMinimalInRange(lower + firstLeg + secondLeg, upper + firstLeg + secondLeg);
			return fixedReducedCost + centerPenalty + memberPenalty;
		}

		int start = discreteRepeatabilityStart(memberStart);
		int end = discreteRepeatabilityEnd(memberEnd);
		int firstLeg = discreteRepeatabilityDuration(member, center);
		int secondLeg = discreteRepeatabilityDuration(center, member);
		int firstStart = Math.max(start,
				Math.max(discreteRepeatabilityStart(effectiveJobHStart[center]) - firstLeg,
						discreteRepeatabilityStart(effectiveJobHStart[member]) - firstLeg - secondLeg));
		int firstEnd = Math.min(end,
				Math.min(discreteRepeatabilityEnd(effectiveJobHEnd[center]) - firstLeg,
						discreteRepeatabilityEnd(effectiveJobHEnd[member]) - firstLeg - secondLeg));
		double best = Utility.big_M;
		for (int firstCompletion = firstStart; firstCompletion <= firstEnd; firstCompletion++) {
			if (!isTimeIndexedRepeatabilityCompletionFeasible(member, firstCompletion)
					|| isTimeIndexedRepeatabilityArcForbidden(node, member, center, firstCompletion)) {
				continue;
			}
			int centerCompletion = firstCompletion + firstLeg;
			if (!isTimeIndexedRepeatabilityCompletionFeasible(center, centerCompletion)
					|| isTimeIndexedRepeatabilityArcForbidden(node, center, member, centerCompletion)) {
				continue;
			}
			int secondMemberCompletion = centerCompletion + secondLeg;
			if (!isTimeIndexedRepeatabilityCompletionFeasible(member, secondMemberCompletion)) {
				continue;
			}
			double value = fixedReducedCost + data.penaltyFunction[center].evaluate(centerCompletion)
					+ data.penaltyFunction[member].evaluate(secondMemberCompletion);
			if (Utility.compareLt(value, best)) {
				best = value;
			}
		}
		return best;
	}

	private void addDualPairNgNeighborhoods(LP lp) {
		int targetPairCount = Math.max(0, (int) (data.n * Math.max(0.0, config.ngDssrInitialNgPairCoefficient)));
		if (targetPairCount <= 0) {
			return;
		}
		ArrayList<NgPair> pairs = new ArrayList<NgPair>();
		for (int first = 1; first <= data.n; first++) {
			for (int second = first + 1; second <= data.n; second++) {
				double reducedPairCost = ngPairReducedCost(lp, first, second);
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
		int selectedPairCount = 0;
		for (int i = 0; i < pairs.size() && selectedPairCount < targetPairCount; i++) {
			NgPair pair = pairs.get(i);
			boolean added = false;
			if (isInitialNgMemberAllowed(pair.second)) {
				ngNeighborhoodByJob[pair.first].add(pair.second);
				added = true;
			}
			if (isInitialNgMemberAllowed(pair.first)) {
				ngNeighborhoodByJob[pair.second].add(pair.first);
				added = true;
			}
			if (added) {
				selectedPairCount++;
			}
		}
	}

	private double ngPairReducedCost(LP lp, int first, int second) {
		return pricingSetupCost(first, second) - lp.getArcDual(first, second) - lp.getJobDual(second)
				+ pricingSetupCost(second, first) - lp.getArcDual(second, first) - lp.getJobDual(first);
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
		for (int via = 1; via <= data.n; via++) {
			if (canRepeatJobViaCurrentEffectiveWindow(node, job, via, useExactTimeIndexedRepeatability)) {
				return true;
			}
		}
		return false;
	}

	private boolean canRepeatJobViaCurrentEffectiveWindow(Node node, int job, int via,
			boolean useExactTimeIndexedRepeatability) {
		if (via == job || PricingCompatibility.isRequiredOutsourcedJob(node, job)
				|| PricingCompatibility.isRequiredOutsourcedJob(node, via)
				|| isOrdinaryArcUnavailableForRepeatability(node, job, via)
				|| isOrdinaryArcUnavailableForRepeatability(node, via, job)) {
			return false;
		}
		double jobStart = effectiveJobHStart[job];
		double jobEnd = effectiveJobHEnd[job];
		if (Utility.compareGt(jobStart, jobEnd)) {
			return false;
		}
		if (!useExactTimeIndexedRepeatability) {
			return canRepeatJobVia(job, via, jobStart, jobEnd);
		}
		int start = discreteRepeatabilityStart(jobStart);
		int end = discreteRepeatabilityEnd(jobEnd);
		if (start > end) {
			return false;
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
		int effectiveRouteLimit = Math.max(1, config.ngDssrNonElementaryRouteUpdateLimit);
		int effectiveRoutes = 0;
		for (NonElementaryNegativeRoute route : nonElementaryNegativeRoutes) {
			ngDssrMinimumSegmentRoutesConsidered++;
			ngDssrRoundRoutesConsidered++;
			int routeChanged = ngDssrUseMinimumNewPairsSegmentUpdate
					? addMinimumNewPairsRepeatedSegment(route.sequence, ngNeighborhoodByJob, data.n,
							ngDssrRoundAddedPairs)
					: addAllNewPairsRepeatedSegments(route.sequence, ngNeighborhoodByJob, data.n,
							ngDssrRoundAddedPairs);
			if (routeChanged < 0) {
				ngDssrMinimumSegmentRoutesAlreadyBlocked++;
				ngDssrRoundRoutesAlreadyBlocked++;
			} else if (routeChanged > 0) {
				ngDssrMinimumSegmentRoutesUpdated++;
				ngDssrRoundRoutesUpdated++;
				changed += routeChanged;
				effectiveRoutes++;
				if (effectiveRoutes >= effectiveRouteLimit) {
					break;
				}
			}
		}
		return changed;
	}

	private int nonElementaryRouteCandidateLimit() {
		return Math.max(Math.max(1, config.ngDssrNonElementaryRouteUpdateLimit),
				config.ngDssrNonElementaryRouteCandidateLimit);
	}
	/**
	 * 只补齐一个完整重复段。返回 -1 表示该路径已被现有 ng-set 禁止，0 表示没有可更新的重复段。
	 */
	static int addMinimumNewPairsRepeatedSegment(List<Integer> sequence, PackedBitSet[] neighborhoods, int n,
			List<String> addedPairs) {
		int[] lastPosition = new int[n + 1];
		Arrays.fill(lastPosition, -1);
		int[] middleMarks = new int[n + 1];
		int mark = 0;
		int bestRepeatedJob = -1;
		int bestStart = -1;
		int bestEnd = -1;
		int bestMissing = Integer.MAX_VALUE;
		for (int pos = 0; pos < sequence.size(); pos++) {
			int repeatedJob = sequence.get(pos).intValue();
			if (repeatedJob <= 0 || repeatedJob > n) {
				continue;
			}
			int previous = lastPosition[repeatedJob];
			if (previous >= 0) {
				mark++;
				int missing = 0;
				for (int middle = previous + 1; middle < pos; middle++) {
					int middleJob = sequence.get(middle).intValue();
					if (middleJob <= 0 || middleJob > n || middleJob == repeatedJob
							|| middleMarks[middleJob] == mark) {
						continue;
					}
					middleMarks[middleJob] = mark;
					if (!neighborhoods[middleJob].contains(repeatedJob)) {
						missing++;
					}
				}
				if (missing == 0) {
					return -1;
				}
				int span = pos - previous;
				int bestSpan = bestEnd - bestStart;
				if (missing < bestMissing || (missing == bestMissing && (bestStart < 0 || span < bestSpan))) {
					bestRepeatedJob = repeatedJob;
					bestStart = previous;
					bestEnd = pos;
					bestMissing = missing;
				}
			}
			lastPosition[repeatedJob] = pos;
		}
		if (bestRepeatedJob < 0) {
			return 0;
		}
		int changed = 0;
		for (int middle = bestStart + 1; middle < bestEnd; middle++) {
			int middleJob = sequence.get(middle).intValue();
			if (middleJob > 0 && middleJob <= n && middleJob != bestRepeatedJob
					&& !neighborhoods[middleJob].contains(bestRepeatedJob)) {
				neighborhoods[middleJob].add(bestRepeatedJob);
				changed++;
				if (addedPairs != null) {
					addedPairs.add(middleJob + "<-" + bestRepeatedJob);
				}
			}
		}
		return changed;
	}

	/** 补齐一条路径的全部连续重复段；-1 表示该路径已被当前 ng-set 禁止。 */
	static int addAllNewPairsRepeatedSegments(List<Integer> sequence, PackedBitSet[] neighborhoods, int n,
			List<String> addedPairs) {
		int[] lastPosition = new int[n + 1];
		Arrays.fill(lastPosition, -1);
		for (int pos = 0; pos < sequence.size(); pos++) {
			int repeatedJob = sequence.get(pos).intValue();
			if (repeatedJob <= 0 || repeatedJob > n) {
				continue;
			}
			int previous = lastPosition[repeatedJob];
			if (previous >= 0) {
				boolean missingPair = false;
				for (int middle = previous + 1; middle < pos; middle++) {
					int middleJob = sequence.get(middle).intValue();
					if (middleJob > 0 && middleJob <= n && middleJob != repeatedJob
							&& !neighborhoods[middleJob].contains(repeatedJob)) {
						missingPair = true;
						break;
					}
				}
				if (!missingPair) {
					return -1;
				}
			}
			lastPosition[repeatedJob] = pos;
		}
		Arrays.fill(lastPosition, -1);
		int changed = 0;
		for (int pos = 0; pos < sequence.size(); pos++) {
			int repeatedJob = sequence.get(pos).intValue();
			if (repeatedJob <= 0 || repeatedJob > n) {
				continue;
			}
			int previous = lastPosition[repeatedJob];
			if (previous >= 0) {
				for (int middle = previous + 1; middle < pos; middle++) {
					int middleJob = sequence.get(middle).intValue();
					if (middleJob > 0 && middleJob <= n && middleJob != repeatedJob
							&& !neighborhoods[middleJob].contains(repeatedJob)) {
						neighborhoods[middleJob].add(repeatedJob);
						changed++;
						if (addedPairs != null) {
							addedPairs.add(middleJob + "<-" + repeatedJob);
						}
					}
				}
			}
			lastPosition[repeatedJob] = pos;
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
				+ ngDssrRouteUpdateSummary()
				+ ngSetStatsSummary()
				+ ngDssrMidpointSummary()
				+ ngSetMembersSummary()
				+ roundRouteRelationSummary()
				+ duplicateRepairSummary();
	}

	private String ngDssrRouteUpdateSummary() {
		String mode = ngDssrUseMinimumNewPairsSegmentUpdate ? "minSegment" : "allSegments";
		return ", ngRouteUpdate=" + mode + "/effectiveLimit" + config.ngDssrNonElementaryRouteUpdateLimit
				+ "/candidateLimit" + nonElementaryRouteCandidateLimit()
				+ "/considered" + ngDssrMinimumSegmentRoutesConsidered
				+ "/blocked" + ngDssrMinimumSegmentRoutesAlreadyBlocked
				+ "/updated" + ngDssrMinimumSegmentRoutesUpdated;
	}
	private String ngSetWarmStartSummary() {
		if (!config.enableNgDssrHistoryWarmStart && !config.enableNgDssrSameNodeWarmStart) {
			return "";
		}
		if (ngDssrSameNodeWarmStartApplied) {
			return ", ngWarmStart=sameNodeBounded/" + historyWarmStart.sameNodeSummary();
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

	private void recordNgSetHistory(LP lp) {
		if (historyWarmStart != null) {
			historyWarmStart.recordSameNode(ngNeighborhoodByJob, currentNodeId(lp), currentActiveCutIds(lp),
					ngDssrRoundsExecuted, config);
		}
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
		feasibilityPhaseOneObjectiveMode = lp.isFeasibilityPhaseOneObjectiveMode();
		ngNeighborhoodByJob = null;
		ngDssrInitialRepeatableMember = null;
		ngDssrRoundsExecuted = 0;
		ngDssrTotalNgSetUpdates = 0;
		ngDssrTotalNonElementaryRoutes = 0;
		String routeUpdateMode = config.ngDssrNonElementaryRouteUpdateMode == null
				? "minimumNewPairsSegment" : config.ngDssrNonElementaryRouteUpdateMode.trim();
		if (!"allSegments".equalsIgnoreCase(routeUpdateMode)
				&& !"minimumNewPairsSegment".equalsIgnoreCase(routeUpdateMode)) {
			throw new IllegalArgumentException("Unsupported ngDssrNonElementaryRouteUpdateMode: " + routeUpdateMode);
		}
		ngDssrUseMinimumNewPairsSegmentUpdate = "minimumNewPairsSegment".equalsIgnoreCase(routeUpdateMode);
		ngDssrMinimumSegmentRoutesConsidered = 0;
		ngDssrMinimumSegmentRoutesAlreadyBlocked = 0;
		ngDssrMinimumSegmentRoutesUpdated = 0;
		ngDssrTotalNonElementaryNegativeSeen = 0;
		ngDssrTotalElementaryColumnsReturned = 0;
		ngDssrReusableTmid = Double.NaN;
		ngDssrLastMidpointProbeRound = 0;
		ngDssrPreviousRoundForwardMillis = Double.NaN;
		ngDssrPreviousRoundBackwardMillis = Double.NaN;
		ngDssrMidpointByRound = new StringBuilder();
		resetExactPhaseTiming();
		ngDssrHistoryWarmStartSkippedForRepeatability = false;
		ngDssrWindowRepeatabilityFilterApplied = false;
		ngDssrWindowRepeatableJobCount = 0;
		ngDssrWindowNonRepeatableJobCount = 0;
		ngDssrTraceNgSetStats = Boolean.getBoolean("twet.bpc.ngDssrSetStats")
				|| Boolean.getBoolean("twet.bpc.fullDomainCompare.ngDssrSetStats");
		ngDssrTraceNgSetMembers = Boolean.getBoolean("twet.bpc.ngDssrSetMembers")
				|| Boolean.getBoolean("twet.bpc.fullDomainCompare.ngDssrSetMembers");
		ngDssrTraceRoundRouteRelation = Boolean.getBoolean("twet.bpc.ngDssrRoundRouteRelation")
				|| Boolean.getBoolean("twet.bpc.fullDomainCompare.ngDssrRoundRouteRelation");
		ngDssrDuplicateRepairDiagnostic = Boolean.getBoolean("twet.bpc.ngDssrDuplicateRepairDiagnostic")
				|| Boolean.getBoolean("twet.bpc.fullDomainCompare.ngDssrDuplicateRepairDiagnostic");
		ngDssrNgSetStatsByRound = ngDssrTraceNgSetStats ? new StringBuilder() : null;
		ngDssrPreviousRoundNegativeRoutes = null;
		ngDssrCurrentRoundNegativeRoutes = null;
		ngDssrRoundAddedPairs = null;
		ngDssrRoundRouteRelation = ngDssrTraceRoundRouteRelation ? new StringBuilder() : null;
		ngDssrDuplicateRepairCandidates = null;
		ngDssrDuplicateRepairSummary = ngDssrDuplicateRepairDiagnostic ? new StringBuilder() : null;
		ngDssrDuplicateRepairEvaluatorCalls = 0L;
		ngDssrDuplicateRepairNanos = 0L;
		ngDssrDuplicateRepairAttempted = 0;
		ngDssrDuplicateRepairFeasible = 0;
		ngDssrDuplicateRepairNegative = 0;
		ngDssrDuplicateRepairAdditional = 0;
		ngDssrReusableCompletionBounds = null;
		ngDssrReusableCompletionBoundFixedArc = null;
		reachabilityCandidateJobs = null;
		forwardExtensionArcMaskByFrom = null;
		backwardExtensionArcMaskBySuccessor = null;
		ngDssrReusablePricingWindowPrecomputeReady = false;
		ngDssrReusablePricingHorizon = Double.NaN;
		ngDssrReusableDynamicMinHStart = Double.NaN;
		ngDssrReusableDynamicMaxHEnd = Double.NaN;
		ngDssrReusableEarliestSourceCompletion = Double.NaN;
		ngDssrReusableActiveColumnSignatures = null;

		for (ngDssrRound = 1; !this.timeLimitChecker.isTimeLimitReached(); ngDssrRound++) {
			nonElementaryNegativeRoutes = new ArrayList<NonElementaryNegativeRoute>();
			ngDssrDuplicateRepairCandidates = ngDssrDuplicateRepairDiagnostic
					? new ArrayList<NonElementaryNegativeRoute>() : null;
			ngDssrCurrentRoundNegativeRoutes = ngDssrTraceRoundRouteRelation
					? new HashMap<SequenceSignature, NonElementaryNegativeRoute>() : null;
			ngDssrRoundAddedPairs = ngDssrTraceRoundRouteRelation ? new ArrayList<String>() : null;
			ngDssrRoundNonElementaryNegativeSeen = 0;
			ngDssrRoundElementaryColumnsReturned = 0;
			ngDssrRoundRoutesConsidered = 0;
			ngDssrRoundRoutesAlreadyBlocked = 0;
			ngDssrRoundRoutesUpdated = 0;
			ArrayList<TWETColumn> columns = solveRelaxedRound(lp);
			diagnoseDuplicateRepairs(lp);
			ngDssrRoundsExecuted = ngDssrRound;
			ngDssrRoundElementaryColumnsReturned = columns.size();
			ngDssrTotalElementaryColumnsReturned += ngDssrRoundElementaryColumnsReturned;
			ngDssrTotalNonElementaryNegativeSeen += ngDssrRoundNonElementaryNegativeSeen;
			ngDssrTotalNonElementaryRoutes += nonElementaryNegativeRoutes.size();
			if (!columns.isEmpty()) {
				appendNgSetStatsForRound(0);
				appendRoundRouteRelation(0);
				appendNgDssrSummary(config.ngDssrReturnRelaxedColumns
						? "ng-relaxed negative columns returned"
						: "elementary negative columns returned");
				recordNgSetHistory(lp);
				return columns;
			}
			if (nonElementaryNegativeRoutes.isEmpty()) {
				appendNgSetStatsForRound(0);
				appendRoundRouteRelation(0);
				appendNgDssrSummary("relaxed pricing found no negative route");
				recordNgSetHistory(lp);
				return columns;
			}
			int changed = updateNgNeighborhoodsFromNonElementaryRoutes();
			ngDssrTotalNgSetUpdates += changed;
			appendNgSetStatsForRound(changed);
			appendRoundRouteRelation(changed);
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
				.append("/scan").append(ngDssrRoundRoutesConsidered)
				.append('-').append(ngDssrRoundRoutesAlreadyBlocked)
				.append('-').append(ngDssrRoundRoutesUpdated)
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
		long forwardNanosBefore = exactForwardExpandNanos;
		long backwardNanosBefore = exactBackwardExpandNanos;
		Utility.resetCurUpperBound(Utility.big_M);
		lastRelaxedRoundBestReducedCost = Double.POSITIVE_INFINITY;
		diagnosticHeartbeat(lp, "initialize.start", true);
		long phaseStart = System.nanoTime();
		initialize(lp);
		exactInitializeNanos += System.nanoTime() - phaseStart;
		diagnosticHeartbeat(lp, "initialize.done", true);
		if (completionBoundPreCertificateClosed) {
			exactTotalNanos += System.nanoTime() - exactStartNanos;
			return generatedColumns;
		}
		if (fullMidpointDiagnosticRan && Boolean.getBoolean("twet.bpc.midpointFullDiagnosticStopAfter")) {
			generatedColumns.clear();
			lastMessage = "GCNGBB-style ng-DSSR bidirectional midpoint full diagnostic executed; exact pricing skipped";
			exactTotalNanos += System.nanoTime() - exactStartNanos;
			return generatedColumns;
		}
		if (!midpointProbeLabelsReadyForJoin) {
			phaseStart = System.nanoTime();
			initializeBackwardSink(lp);
			exactBackwardSinkNanos += System.nanoTime() - phaseStart;
			diagnosticHeartbeat(lp, "backwardSink.done", true);
		} else {
			diagnosticHeartbeat(lp, "probe.rank0.reuse", true);
		}
		// 2026-05-26: GCNGBB-style 婵犮垼鍩栭悧鏇㈡儑閺夋５瑙勬媴鐞涒剝鐓犻梺闈涙閸婃悂宕㈠☉銏犵闁糕剝顨呴悞濂告煠閻楀牜娈旈柡浣圭墬缁嬪濡堕崟顒佺彿闂傚倸鍟伴崰搴ㄥ垂椤忓牊鏅悘鐐靛亾娴犳﹢鏌涘顒佹崳缂侇喚鍎ょ粙澶愬焵椤掑嫬绠ユい鎰剁到娴?backward labels 闂?crossing-arc join闂?
		if (!midpointProbeLabelsReadyForJoin) {
			diagnosticHeartbeat(lp, "forward.start", true);
			phaseStart = System.nanoTime();
			while (canContinue() && !FWUL.isEmpty()) {
				forwardExtend(lp);
			}
			exactForwardExpandNanos += System.nanoTime() - phaseStart;
			diagnosticHeartbeat(lp, "forward.done", true);
			if (!timeLimitChecker.isTimeLimitReached()) {
				diagnosticHeartbeat(lp, "backward.start", true);
				phaseStart = System.nanoTime();
				while (canContinue() && !BWUL.isEmpty()) {
					backwardExtend(lp);
				}
				exactBackwardExpandNanos += System.nanoTime() - phaseStart;
				diagnosticHeartbeat(lp, "backward.done", true);
			}
		}
		if (canContinue() && !timeLimitChecker.isTimeLimitReached()) {
			diagnosticHeartbeat(lp, "join.compact.start", true);
			phaseStart = System.nanoTime();
			compactAndSortActiveLabelListsForJoin();
			exactJoinCompactNanos += System.nanoTime() - phaseStart;
			diagnosticHeartbeat(lp, "join.start", true);
			phaseStart = System.nanoTime();
			joinAllForwardTerminalGroups(lp);
			exactJoinNanos += System.nanoTime() - phaseStart;
			diagnosticHeartbeat(lp, "finalize.start", true);
			phaseStart = System.nanoTime();
			finalizeGeneratedColumns(lp);
			if (generatedColumns.isEmpty()) {
				maybeAuditAlternativeJoin(lp);
			}
			exactFinalizeNanos += System.nanoTime() - phaseStart;
			diagnosticHeartbeat(lp, "finalize.done", true);
		}
		double roundForwardMillis = (exactForwardExpandNanos - forwardNanosBefore) / 1_000_000.0;
		double roundBackwardMillis = (exactBackwardExpandNanos - backwardNanosBefore) / 1_000_000.0;
		if (midpointProbeLabelsReadyForJoin) {
			roundForwardMillis = midpointProbeSelectedForwardMillis;
			roundBackwardMillis = midpointProbeSelectedBackwardMillis;
		}
		long roundExactNanos = System.nanoTime() - exactStartNanos;
		exactTotalNanos += roundExactNanos;
		boolean roundCompleted = !timeLimitChecker.isTimeLimitReached()
				&& (midpointProbeLabelsReadyForJoin || (FWUL.isEmpty() && BWUL.isEmpty()));
		if (roundCompleted) {
			rememberDssrRoundMidpointFeedback(roundForwardMillis, roundBackwardMillis);
			updateMidpointProbeReuseAfterExact(lp, roundExactNanos, roundForwardMillis, roundBackwardMillis);
		}
		String completionState = timeLimitChecker.isTimeLimitReached() ? "time limit reached"
				: (midpointProbeLabelsReadyForJoin ? "probe rank0 queues exhausted"
						: (canContinue() ? "queues exhausted" : "column cap disabled"));
		lastMessage = "GCNGBB-style ng-DSSR bidirectional no-cut labeling generated " + generatedColumns.size() + " columns ("
				+ completionState + "); " + statisticsSummary();
		return generatedColumns;
	}

	/** 保存本轮完整 labeling 负载，供下一次周期 probe 调整起点；不参与当前轮结果。 */
	private void rememberDssrRoundMidpointFeedback(double forwardMillis, double backwardMillis) {
		ngDssrPreviousRoundForwardMillis = forwardMillis;
		ngDssrPreviousRoundBackwardMillis = backwardMillis;
		ngDssrReusableTmid = tMid;
		if (ngDssrMidpointByRound == null) {
			return;
		}
		if (ngDssrMidpointByRound.length() > 0) {
			ngDssrMidpointByRound.append(';');
		}
		ngDssrMidpointByRound.append('r').append(ngDssrRound)
				.append("/t").append(String.format("%.3f", tMid))
				.append('/').append(midpointProbePerformed ? "probe" : "reuse")
				.append("/labels").append(forwardLabelsKept).append('-').append(backwardLabelsKept)
				.append("/ms").append(String.format("%.1f", forwardMillis)).append('-')
				.append(String.format("%.1f", backwardMillis));
	}

	private String ngDssrMidpointSummary() {
		if (ngDssrMidpointByRound == null || ngDssrMidpointByRound.length() == 0) {
			return "";
		}
		return ", midpointByDssrRound=" + ngDssrMidpointByRound.toString();
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
		if (feasibilityPhaseOneObjectiveMode || completionBounds == null || completionBoundRelaxation == null || dualProfitableWindowEnabled
				|| zeroDualExcludedJobs != null || config.timeIndexedCompletionBoundInRoundArcFixing) {
			return null;
		}
		// 2026-07-12: 基础 hard window、node 继承的 compact window 和持久 pricing-only arc
		// 都是当前子树的有效域，基于它们缩短的 horizon 可以直接复用。dual window、zero-dual
		// 排除及本轮 0-cutoff 时空弧固定只服务当前 pricing，不能作为永久 arc fixing 的证据。
		return new CompletionBoundSubtreeArcEliminator.PreparedBounds(completionBounds, pricingHorizon,
				completionBoundRelaxation, completionBoundQueueOrdering, true);
	}

	private void appendRoundRouteRelation(int changed) {
		if (!ngDssrTraceRoundRouteRelation || ngDssrRoundRouteRelation == null) {
			return;
		}
		NonElementaryNegativeRoute observedBest = bestRoundNegativeRoute(ngDssrCurrentRoundNegativeRoutes);
		// 用户关心的是本轮真正送入 DSSR 更新的 top1 witness，而不是所有 join 观察值的事后最小者。
		NonElementaryNegativeRoute selected = nonElementaryNegativeRoutes == null
				|| nonElementaryNegativeRoutes.isEmpty() ? null : nonElementaryNegativeRoutes.get(0);
		NonElementaryNegativeRoute previousMatch = selected == null || ngDssrPreviousRoundNegativeRoutes == null
				? null : ngDssrPreviousRoundNegativeRoutes.get(new SequenceSignature(selected.sequence));
		int previousRank = previousMatch == null ? -1
				: rankInRound(previousMatch, ngDssrPreviousRoundNegativeRoutes);
		String selectedPreviousRanks = selectedPreviousRanks();
		if (ngDssrRoundRouteRelation.length() > 0) {
			ngDssrRoundRouteRelation.append(';');
		}
		ngDssrRoundRouteRelation.append('r').append(ngDssrRound)
				.append("={unique=").append(roundRouteCount(ngDssrCurrentRoundNegativeRoutes))
				.append(",selectedRc=").append(selected == null ? "NA" : Double.toString(selected.reducedCost))
				.append(",selectedSeq=").append(selected == null ? "NA" : compactSequence(selected.sequence))
				.append(",observedBestRc=").append(observedBest == null ? "NA"
						: Double.toString(observedBest.reducedCost))
				.append(",selectedIsObservedBest=").append(selected != null && observedBest != null
						&& selected.sequence.equals(observedBest.sequence)
						&& Double.compare(selected.reducedCost, observedBest.reducedCost) == 0)
				.append(",prevUnique=").append(roundRouteCount(ngDssrPreviousRoundNegativeRoutes))
				.append(",prevRank=").append(previousRank < 0 ? "NA" : Integer.toString(previousRank))
				.append(",prevRc=").append(previousMatch == null ? "NA"
						: Double.toString(previousMatch.reducedCost))
				.append(",selectedPrevRanks=").append(selectedPreviousRanks)
				.append(",added=").append(changed)
				.append(",pairs=").append(ngDssrRoundAddedPairs == null || ngDssrRoundAddedPairs.isEmpty()
						? "-" : String.join(".", ngDssrRoundAddedPairs))
				.append('}');
		ngDssrPreviousRoundNegativeRoutes = ngDssrCurrentRoundNegativeRoutes;
	}

	private String selectedPreviousRanks() {
		if (nonElementaryNegativeRoutes == null || nonElementaryNegativeRoutes.isEmpty()) {
			return "-";
		}
		StringBuilder ranks = new StringBuilder();
		int present = 0;
		for (int i = 0; i < nonElementaryNegativeRoutes.size(); i++) {
			NonElementaryNegativeRoute route = nonElementaryNegativeRoutes.get(i);
			NonElementaryNegativeRoute previous = ngDssrPreviousRoundNegativeRoutes == null ? null
					: ngDssrPreviousRoundNegativeRoutes.get(new SequenceSignature(route.sequence));
			if (i > 0) {
				ranks.append('.');
			}
			if (previous == null) {
				ranks.append("NA");
			} else {
				present++;
				ranks.append(rankInRound(previous, ngDssrPreviousRoundNegativeRoutes));
			}
		}
		return present + "/" + nonElementaryNegativeRoutes.size() + ":" + ranks;
	}

	private int roundRouteCount(HashMap<SequenceSignature, NonElementaryNegativeRoute> routes) {
		return routes == null ? 0 : routes.size();
	}

	private NonElementaryNegativeRoute bestRoundNegativeRoute(
			HashMap<SequenceSignature, NonElementaryNegativeRoute> routes) {
		NonElementaryNegativeRoute best = null;
		if (routes != null) {
			for (NonElementaryNegativeRoute route : routes.values()) {
				if (best == null || compareNonElementaryNegativeRoutes(route, best) < 0) {
					best = route;
				}
			}
		}
		return best;
	}

	private int rankInRound(NonElementaryNegativeRoute target,
			HashMap<SequenceSignature, NonElementaryNegativeRoute> routes) {
		int rank = 1;
		for (NonElementaryNegativeRoute route : routes.values()) {
			if (compareNonElementaryNegativeRoutes(route, target) < 0) {
				rank++;
			}
		}
		return rank;
	}

	private String compactSequence(ArrayList<Integer> sequence) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < sequence.size(); i++) {
			if (i > 0) {
				builder.append('.');
			}
			builder.append(sequence.get(i).intValue());
		}
		return builder.toString();
	}

	private String roundRouteRelationSummary() {
		if (!ngDssrTraceRoundRouteRelation || ngDssrRoundRouteRelation == null
				|| ngDssrRoundRouteRelation.length() == 0) {
			return "";
		}
		return ", ngDssrRoundRouteRelation=" + ngDssrRoundRouteRelation.toString();
	}

	private String duplicateRepairSummary() {
		if (!ngDssrDuplicateRepairDiagnostic || ngDssrDuplicateRepairSummary == null) {
			return "";
		}
		return ", ngDssrDuplicateRepair attempted/feasible/negative/additional/evalCalls/ms="
				+ ngDssrDuplicateRepairAttempted + "/" + ngDssrDuplicateRepairFeasible + "/"
				+ ngDssrDuplicateRepairNegative + "/" + ngDssrDuplicateRepairAdditional + "/"
				+ ngDssrDuplicateRepairEvaluatorCalls + "/"
				+ String.format(Locale.US, "%.3f", ngDssrDuplicateRepairNanos / 1_000_000.0)
				+ ", byRound=" + ngDssrDuplicateRepairSummary.toString();
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
	 * 2026-05-26: 闂佽　鍋撴い鏍ㄧ☉閻︻喖鈽夐幘宕囆㈤柟?label 闂佸憡鍨跺钘壩ｉ敂鍓ч┏闁哄稁鍋呭▓锝夋煥濞戞ê顨欑紒宀冨煐缁傚秵鎯旈姀銏⌒ラ柡澶婄墕閸熺娀鍩€椤掍胶鐭嗙紓?reduced cost 婵炴潙鍚嬮敋闁告ɑ鐩崹鎯р攽閸涱垳鎲块梺鐐藉劜缁秴煤閸ф鐭楁い鏍ㄧ箓閸樻挳鎮跺鐓庝簻闁诡喗顨堢槐鎺楊敇閻斿憡娈伴梻浣规緲缁夎泛鈻?
	 * label 闂佽浜介崝宀勫箖濡ゅ懎绠ラ柍杞拌兌濞兼棃鏌嶉妷锔剧畱缂佺媴缍佸鑽も偓闈涙啞閻ｉ亶鏌涘▎鎰⒊闁搞劍顨婃俊?
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
		long sectionStart = System.nanoTime();
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
		exactInitializeSetupNanos += System.nanoTime() - sectionStart;
		sectionStart = System.nanoTime();
		if (config.diagnosticPricingSummaryDetails) {
			recordPricingDiagnostics(lp);
		}
		maybeDumpPricingSnapshot(lp);
		exactInitializeDiagnosticsNanos += System.nanoTime() - sectionStart;
		sectionStart = System.nanoTime();
		precomputeSriPricing(lp);
		exactInitializeSriNanos += System.nanoTime() - sectionStart;
		sectionStart = System.nanoTime();
		precomputeDynamicPricingWindows(lp);
		exactInitializeWindowNanos += System.nanoTime() - sectionStart;
		sectionStart = System.nanoTime();
		if (ngNeighborhoodByJob == null) {
			initializeNgNeighborhoods(lp);
		}
		exactInitializeNgNeighborhoodNanos += System.nanoTime() - sectionStart;
		sectionStart = System.nanoTime();
		if (completionBounds == null) {
			buildCompletionBounds(lp);
		}
		if (ngDssrReusableCompletionBounds == null && completionBounds != null) {
			ngDssrReusableCompletionBounds = completionBounds;
			ngDssrReusableCompletionBoundFixedArc = completionBoundFixedArc;
		}
		exactInitializeCompletionBoundNanos += System.nanoTime() - sectionStart;
		sectionStart = System.nanoTime();
		if (tryApplyCompletionBoundPreCertificate(lp)) {
			exactInitializePreCertificateNanos += System.nanoTime() - sectionStart;
			return;
		}
		exactInitializePreCertificateNanos += System.nanoTime() - sectionStart;
		sectionStart = System.nanoTime();
		ensureExtensionArcMasks(lp.getNode());
		if (!prepareMidpointWithinDssr(lp)) {
			if (!tryUseStableFrozenMidpoint(lp)) {
				runMidpointProbeIfEnabled(lp);
			}
			rememberInitialMidpointWithinDssr();
		}
		exactInitializeMidpointProbeNanos += System.nanoTime() - sectionStart;
		sectionStart = System.nanoTime();
		if (midpointProbeLabelsReadyForJoin) {
			// 2026-06-08: 闁荤偞鍑归崑濠囧焵椤掆偓椤︻噣鎳欓幋锔藉剭?rank0 probe 閻庤鐡曠亸娆戝垝閿熺姵鍤€婵°倐鍋撻柡浣圭墬缁嬪濡堕崟顒佺彿 label 闂傚倸鍟伴崰搴ㄥ垂椤忓牊鏅悘鐐舵鐠佹彃霉閻橆喖鍔ゆ繛鎻掓健楠炴帡濡烽妸褏顔掗梺?join闂?
			// 闁哄鏅滈悷鈺呭闯閻戣棄鐭楁い蹇撴硽婢跺娼伴柨婵嗘噽绾偓闂佺锕ラ悷鈺呭焵椤掆偓椤︻垶宕归鍫濆偍濠电姵鑹惧▍?闂佸壊鍋勫Λ娑欐叏閹间礁绠戝〒姘功缁€澶愭⒑椤掆偓閻忔繈宕㈤妶澶婅Е閻忕偠鍋愰鍗炩槈?Tmid 闂佸憡鍔曠粔椋庣玻閸ャ劎鈻旈柍褜鍓熼弻?labeling闂?
			initializeCandidateState(lp);
		} else {
			initializeSearchState(lp);
			initializeForwardSource(lp);
		}
		exactInitializeStateNanos += System.nanoTime() - sectionStart;
		sectionStart = System.nanoTime();
		runFullMidpointDiagnosticIfEnabled(lp);
		exactInitializeFullMidpointDiagnosticNanos += System.nanoTime() - sectionStart;
	}

	/**
	 * 同一次 DSSR 内复用最近一次 probe 的 Tmid，并按固定轮次重新校准。
	 * 周期 probe 只用上一轮完整 labeling 的负载轻移初值，最终选择仍完全交给原 probe。
	 */
	private boolean prepareMidpointWithinDssr(LP lp) {
		if (!config.bidirectionalMidpointProbe || !config.bidirectionalMidpointProbeReuseWithinDssr
				|| ngDssrRound <= 1
				|| !Double.isFinite(ngDssrReusableTmid)) {
			return false;
		}
		int interval = config.bidirectionalMidpointProbeDssrRecheckInterval;
		boolean recheck = isPreviousDssrRoundTimeImbalanced()
				|| (interval > 0 && ngDssrRound - ngDssrLastMidpointProbeRound >= interval);
		if (recheck) {
			double seed = dssrPeriodicProbeSeed();
			runMidpointProbeIfEnabled(lp, seed, "dssrPeriodicFeedback");
			ngDssrReusableTmid = tMid;
			ngDssrLastMidpointProbeRound = ngDssrRound;
			return true;
		}
		tMid = clampCurrentMidpoint(ngDssrReusableTmid);
		rebuildHalfDomainForCurrentMidpoint();
		resetProbeAffectedStatistics();
		midpointProbeLabelsReadyForJoin = false;
		midpointProbeReferenceSource = "dssrLatestProbe";
		midpointProbeReferenceDirection = 0;
		midpointProbeSelectedDirection = 0;
		midpointProbeSelectedForwardMillis = ngDssrPreviousRoundForwardMillis;
		midpointProbeSelectedBackwardMillis = ngDssrPreviousRoundBackwardMillis;
		midpointProbeSummary = "dssrReuseLatest, selected=" + tMid + ", lastProbeRound="
				+ ngDssrLastMidpointProbeRound;
		return true;
	}

	private boolean isPreviousDssrRoundTimeImbalanced() {
		double threshold = config.bidirectionalMidpointProbeDssrImbalanceThreshold;
		return Double.isFinite(threshold) && Utility.compareGt(threshold, 1.0)
				&& Double.isFinite(ngDssrPreviousRoundForwardMillis)
				&& Double.isFinite(ngDssrPreviousRoundBackwardMillis)
				&& (ngDssrPreviousRoundForwardMillis > threshold * ngDssrPreviousRoundBackwardMillis
						|| ngDssrPreviousRoundBackwardMillis > threshold * ngDssrPreviousRoundForwardMillis);
	}

	/** 上一轮耗时若明显失衡，只把 probe 起点向较轻一侧移动 5% 左右，不直接决定最终 Tmid。 */
	private double dssrPeriodicProbeSeed() {
		double seed = clampCurrentMidpoint(ngDssrReusableTmid);
		double threshold = config.bidirectionalMidpointProbeDssrImbalanceThreshold;
		double moveRatio = config.bidirectionalMidpointProbeDssrSeedMoveRatio;
		if (!Double.isFinite(threshold) || !Utility.compareGt(threshold, 1.0)
				|| !Double.isFinite(moveRatio) || !Utility.compareGt(moveRatio, 0.0)) {
			return seed;
		}
		double left = midpointLeftBound();
		double step = Math.max(0.0, pricingHorizon - left) * Math.min(0.25, moveRatio);
		if (ngDssrPreviousRoundBackwardMillis > threshold * ngDssrPreviousRoundForwardMillis) {
			seed += step;
		} else if (ngDssrPreviousRoundForwardMillis > threshold * ngDssrPreviousRoundBackwardMillis) {
			seed -= step;
		}
		return clampCurrentMidpoint(seed);
	}

	private void rememberInitialMidpointWithinDssr() {
		if (!config.bidirectionalMidpointProbe || !config.bidirectionalMidpointProbeReuseWithinDssr
				|| ngDssrRound != 1 || !Double.isFinite(tMid)) {
			return;
		}
		// 2026-07-18: 只复用 probe 选出的 Tmid，不复用旧标签；后续按固定 DSSR 轮次重新校准。
		ngDssrReusableTmid = tMid;
		ngDssrLastMidpointProbeRound = 1;
	}

	/** 2026-07-12: 稳定冻结只跳过 probe；每次仍按冻结后的 Tmid 完整执行 exact labeling。 */
	private boolean tryUseStableFrozenMidpoint(LP lp) {
		midpointProbeStableFreezeUsed = false;
		if (!config.bidirectionalMidpointProbe || !config.bidirectionalMidpointProbeReuseWithinNode
				|| !config.bidirectionalMidpointProbeStableFreeze || ngDssrRound != 1
				|| midpointProbeReuseByNode == null || lp == null || lp.getNode() == null) {
			return false;
		}
		MidpointProbeNodeReuse reuse = midpointProbeReuseByNode.get(Integer.valueOf(lp.getNode().id));
		if (reuse == null) {
			reuse = new MidpointProbeNodeReuse();
			midpointProbeReuseByNode.put(Integer.valueOf(lp.getNode().id), reuse);
		}
		reuse.ensureCutEpoch(lp.getActiveCutIds());
		if (!reuse.tryAcquireFrozenMidpoint()) {
			return false;
		}
		tMid = clampCurrentMidpoint(reuse.frozenTmid);
		rebuildHalfDomainForCurrentMidpoint();
		resetProbeAffectedStatistics();
		midpointProbeLabelsReadyForJoin = false;
		midpointProbePerformed = false;
		midpointProbeStableFreezeUsed = true;
		midpointProbeReferenceSource = "stableFreeze";
		midpointProbeSummary = "stableFreeze, selected=" + tMid + ", " + reuse.freezeSummary();
		return true;
	}

	/**
	 * 2026-06-12: 婵炲濮撮幊鎰板极閵堝棛顩查幖杈剧磿閺嗘澘霉閿濆懐孝闁诡喗鎹囬幃鈺呮嚋绾版ê浜?partial 濠电姵娲栫换鎰板垂椤忓牆违闁稿本绮嶇粣妤呮煛瀹ュ懏鎼愮紒顭戝弮瀹曟艾螖閸涱亜浜炬慨妯虹－缁犳牜绱掗婵嗗惞缂侇喛娅ｆ禒锕傛倷缁懓浜剧憸宀€妲愰崼鏇炵闁靛鍨崇粈澶婎潡濞戞瑯鐒炬い鎾愁煼瀹曟骞庨懞銉川闂?
	 * trace 闂佸憡鐟禍婊堝疮閹捐绀?lastMessage闂佹寧绋戞總鏃傜箔婢舵劕缁╅柣鐔告緲缂?label闂侀潧妫旀Λ姝癿inance 闂佺懓鐡ㄩ悧鏇㈠焵椤掍胶鎳囬柍褜鍓欓ˇ顖炲垂椤忓牊鐒婚柡鍕箳鐢棝鏌?
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
		return sriPricingEnabled ? new byte[sriCutIds.size()] : EMPTY_SRI_COUNTS;
	}

	private byte[] copySriCounts(byte[] counts) {
		return counts == null || counts.length == 0 ? EMPTY_SRI_COUNTS : counts.clone();
	}

	/**
	 * limited-memory SRI 婵?forward label 闂?state 闁荤偞绋忛崝搴ㄥΦ濮橆厾顩?source 闂佽顔栭崑鍕春瀹€鍐︿汗闁规儳鍟块·鍛存煟閹邦喗鍤€闁诡喗顨婇幆鍐礋椤愨懇鎸呮繛?half-state闂?
	 * node-memory 闂佹眹鍔岀€氭澘顭?memory job 婵炴潙鍚嬬喊宥囩博婵犳碍鈷栭柡鍫㈡暩閻燁剙鈽夐幘宕囆ユい鎾存倐瀹曟濡烽婊冭€縜rc-memory 闂佹眹鍔岀€氭澘顭?memory arc 闂佸憡鐟禍婵堢博婵犳碍鈷栭悹浣告贡缁€?
	 * 閻熸粎澧楅幐鍛婃櫠?head job 闂佸吋鐪归崕閬嶆儓濡崵顩?scope 婵炲濮寸粔宕囩礊閺冣偓缁嬪鎮滃Ο鑽ゅ帎濠电偛鐗呯徊濠氬箲閿濆鍊风痪顓炴媼閸氣偓闂佺绻堥崕顖炲焵?
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
	 * limited-memory SRI 婵?backward label 闂?state 闁荤偞绋忛崝搴ㄥΦ濮樿泛绠板璺猴工閸愨偓闂佸憡纰嶉崹鑸垫櫠鐠恒劋娌柡鍥舵娇閳ь剙瀛╅幆鏃囩疀閺傛妫侀梺?suffix 闂佸憡鑹惧ù宄扳枔閹达箑绀堥柍琛″亾缂?half-state闂?
	 * prepend 婵炴垶鎸撮崑鎾斥槈?job 闂佸搫鍟抽鎰濠曠惤de-memory 闂佸湱顭堥ˇ浼村蓟?job 闂佸憡甯囬崐鏍蓟閸ヮ剚鏅繛灞疚渃-memory 闂佸湱顭堥ˇ浼村蓟婵犲洤绠ラ柍杞拌兌濞兼梻鈧?(job,to) 闂佸憡甯囬崐鏍蓟閸ヮ剙鍙婃い鏍ㄧ閸庡﹦鈧偣鍊曟晶搴ㄦ偨婵犳艾绫?state闂?
	 * arc 婵炴垶鎸哥粔鏉戯耿?memory 婵炴垶鎼╅崢鑹般亹瑜斿顒勵敇閻愮數锛涢梺?state闂佹寧绋戞總鏃傜箔婢跺本宕夐悗鍦Х缁犳牜鎲搁悧鍫熷碍濠?job 闂佹眹鍔岀€氼參寮绘繝鍌楁灁濞达絽鍟伴、鍛存煟濮樼厧鏋旈柍?
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
		PackedBitSet sourceVisited = null;
		if (sriPricingEnabled) {
			sourceVisited = new PackedBitSet(data.n + 2);
			sourceVisited.add(0);
			addZeroDualExcludedJobs(sourceVisited);
		}
		PiecewiseLinearFunction sourceFrontier = cropToInterval(pricingPenaltyFunction(0), 0.0, tMid);
		sourceFrontier.shiftYInPlace(-lp.getMachineDual());
		sourceFrontier.normalize(Direction.FORWARD);
		PackedBitSet sourceNgMemory = new PackedBitSet(data.n + 2);
		ChildReachability sourceSets = buildForwardChildReachability(0, sourceNgMemory, lp.getNode(), sourceFrontier);
		ForwardLabel source = new ForwardLabel(nextLabelId++, 0, null, sourceVisited,
				sourceSets.dominanceSet, sourceSets.extensionSet, sourceNgMemory, sourceFrontier,
				sriPricingEnabled ? sourceFrontier.copy() : null,
				emptySriCounts(), 0.0, maintainRouteVisitProfile());
		if (insertForward(source, lp) == InsertStatus.STORED_AND_ENQUEUE) {
			FWUL.add(source);
		}
	}

	private void runMidpointProbeIfEnabled(LP lp) {
		runMidpointProbeIfEnabled(lp, Double.NaN, null);
	}

	/** 指定 reference 时仅覆盖 probe 起点，不改变原有候选移动、评分和最终选择规则。 */
	private void runMidpointProbeIfEnabled(LP lp, double referenceOverride, String referenceSource) {
		midpointProbeLabelsReadyForJoin = false;
		midpointProbePerformed = false;
		if (!config.bidirectionalMidpointProbe) {
			midpointProbeSummary = "off";
			return;
		}
		double reference;
		if (Double.isFinite(referenceOverride)) {
			reference = referenceOverride;
			midpointProbeReferenceSource = referenceSource == null ? "override" : referenceSource;
		} else {
			reference = midpointProbeReference(lp);
		}
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
		midpointProbeReferenceDirection = results.isEmpty() ? 0 : results.get(0).pressureDirection(scoreMode);
		midpointProbeSelectedDirection = best.pressureDirection(scoreMode);
		midpointProbeSelectedForwardMillis = best.forwardElapsedMillis;
		midpointProbeSelectedBackwardMillis = best.backwardElapsedMillis;
		midpointProbePerformed = true;
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
			if (cached != null) {
				cached.ensureCutEpoch(lp.getActiveCutIds());
			}
			if (cached != null && cached.hasLastExact()) {
				midpointProbeReferenceSource = "reuseLatestExact";
				return cached.lastExactTmid;
			}
		}
		return tMid;
	}

	private int midpointProbeMaxCandidatesForCurrentReference() {
		int maxCandidates = Math.max(1, config.bidirectionalMidpointProbeMaxCandidates);
		if ("reuseLatestExact".equals(midpointProbeReferenceSource)) {
			maxCandidates = Math.min(maxCandidates, Math.max(1, config.bidirectionalMidpointProbeReuseMaxCandidates));
		}
		return maxCandidates;
	}

	private void updateMidpointProbeReuseAfterExact(LP lp, long exactNanos,
			double forwardExactMillis, double backwardExactMillis) {
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
		reuse.ensureCutEpoch(lp.getActiveCutIds());
		double exactMillis = exactNanos / 1_000_000.0;
		double ratio = directionalImbalance(forwardLabelsKept, backwardLabelsKept);
		long labelTotal = forwardLabelsKept + backwardLabelsKept;
		reuse.rememberExact(tMid);
		String freezeAction = "off";
		if (config.bidirectionalMidpointProbeStableFreeze && ngDssrRound == 1) {
			if (midpointProbePerformed) {
				freezeAction = reuse.considerFreezeSelection(tMid, pricingHorizon);
			} else if (midpointProbeStableFreezeUsed) {
				freezeAction = "reuse";
			}
		}
		int exactTimeDirection = direction(forwardExactMillis, backwardExactMillis);
		int exactLabelDirection = direction(forwardLabelsKept, backwardLabelsKept);
		midpointProbeFeedbackSummary = "exactReuse=latest, exactMs=" + exactMillis + ", ratio=" + ratio
				+ ", labels=" + labelTotal + ", latestT=" + reuse.lastExactTmid
				+ ", directionAudit ref/selected/exactTime/exactLabels=" + midpointProbeReferenceDirection + ":"
				+ midpointProbeSelectedDirection + ":" + exactTimeDirection + ":" + exactLabelDirection
				+ ", selectedSideMs=" + midpointProbeSelectedForwardMillis + ":"
				+ midpointProbeSelectedBackwardMillis
				+ ", exactSideMs=" + forwardExactMillis + ":" + backwardExactMillis
				+ ", exactSideLabels=" + forwardLabelsKept + ":" + backwardLabelsKept
				+ ", stableFreeze=" + freezeAction + "/" + reuse.freezeSummary();
	}

	private int direction(double forward, double backward) {
		if (Utility.compareGt(forward, backward)) {
			return 1;
		}
		if (Utility.compareLt(forward, backward)) {
			return -1;
		}
		return 0;
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
		if ("time".equals(normalizeProbeScoreMode(scoreMode))) {
			return selectMidpointProbeResultByTime(results);
		}
		MidpointProbeResult best = null;
		for (MidpointProbeResult result : results) {
			if (best == null || compareMidpointProbeResult(result, best, scoreMode) < 0) {
				best = result;
			}
		}
		return best;
	}

	/** 先限制在最短总耗时的 20% 近优带内，再选择正反向耗时更平衡的候选。 */
	private MidpointProbeResult selectMidpointProbeResultByTime(ArrayList<MidpointProbeResult> results) {
		int bestRank = Integer.MAX_VALUE;
		double minTotalMillis = Double.POSITIVE_INFINITY;
		for (MidpointProbeResult result : results) {
			int rank = result.reliabilityRank("time");
			if (rank < bestRank) {
				bestRank = rank;
				minTotalMillis = result.sideTotalMillis();
			} else if (rank == bestRank) {
				minTotalMillis = Math.min(minTotalMillis, result.sideTotalMillis());
			}
		}
		double tolerance = config.bidirectionalMidpointProbeTimeTolerance;
		if (!Double.isFinite(tolerance) || Utility.compareLt(tolerance, 0.0)) {
			tolerance = 0.20;
		}
		double eligibleLimit = minTotalMillis * (1.0 + tolerance);
		MidpointProbeResult best = null;
		for (MidpointProbeResult result : results) {
			if (result.reliabilityRank("time") != bestRank
					|| Utility.compareGt(result.sideTotalMillis(), eligibleLimit)) {
				continue;
			}
			if (best == null || compareTimeEligibleMidpointProbeResult(result, best) < 0) {
				best = result;
			}
		}
		return best;
	}

	private int compareTimeEligibleMidpointProbeResult(MidpointProbeResult a, MidpointProbeResult b) {
		int imbalance = compareDouble(a.timeScore(), b.timeScore());
		if (imbalance != 0) {
			return imbalance;
		}
		int queue = compareDouble(a.queueScore, b.queueScore);
		if (queue != 0) {
			return queue;
		}
		int total = compareDouble(a.sideTotalMillis(), b.sideTotalMillis());
		if (total != 0) {
			return total;
		}
		int pops = Integer.compare(a.pops, b.pops);
		return pops != 0 ? pops : compareDouble(a.tMid, b.tMid);
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
		// remaining 闂佸憡鐟禍婵嬫儊椤旇姤缍囬柛鎰屽棌鎷℃繛鎴炴惄娴滄繂锕㈤銏″殌婵°倐鍋撻柡浣圭墵瀹曟劕鈻庨幘顖氫壕濠㈣泛鏈悾閬嶆煕閹绢垱娅冪紓宥嗗灴濮婂ジ鎮㈠畡鎵粴闂佸憡锚椤戝懏鎱ㄨ箛娑欐櫖婵炴垶顨嗗畷鎻掆槈閹绢垰浜炬繛鎾寸殰閸愩劌娈ョ紓鍌欑缁绘鍩€椤掍緡娈旈柡浣圭墵瀵喚鎷嬮崷顓狀槷0 闂傚倸鍟伴崰搴ㄥ垂椤忓懎顕辨慨妯夸含婢瑰啴鏌涜缁绘劘銇愰幓鎹帡寮埀顒勫焵椤戭兛璁查崑?
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
		// 2026-06-07: probe 闂佸搫瀚烽崹顖滄嫻閻斿摜顩查柛鈩冾焽濡茬兘寮堕崼婵嗘殨閻炴俺椴哥粭鐔活槻閻㈩垰娲畷婵嗏槈瀹曞洨顦繛鎴炴尭缁夌數鈧灚绮忛妵鎰板箻閸愬樊鏋€闂傚倸鍟伴崰搴ㄥ垂椤忓懎绶炵憸宥夋儍椤掑嫬绠柕蹇曞Т缁愭螞閺夊灝顏柣锝咁煼婵?
		// 闂佸憡鐔粻鎴﹀垂椤栨壕鍋撶涵鍜佹綈婵″弶顨婂畷娆撴惞閻熸壆鐤€ sidePop=N:0闂佹寧绋戦懟顖濄亹瑜嶉湁閻庯綆浜滈悡?forward 闂佺粯鐗曞Λ娑㈠磻閵忋倖鍤€閻忕偛澧藉楣冩煛?backward 闂佸搫绉归弨鍗烇耿娴兼潙违?
		long forwardStart = System.nanoTime();
		while (forwardPops < forwardLimit && !FWUL.isEmpty()) {
			forwardExtend(lp);
			forwardPops++;
			fwQueuePeak = Math.max(fwQueuePeak, queueSize(FWUL));
		}
		double forwardElapsedMillis = (System.nanoTime() - forwardStart) / 1_000_000.0;
		long backwardStart = System.nanoTime();
		while (backwardPops < backwardLimit && !BWUL.isEmpty()) {
			backwardExtend(lp);
			backwardPops++;
			bwQueuePeak = Math.max(bwQueuePeak, queueSize(BWUL));
		}
		double backwardElapsedMillis = (System.nanoTime() - backwardStart) / 1_000_000.0;
		int pops = forwardPops + backwardPops;
		fwQueuePeak = Math.max(fwQueuePeak, queueSize(FWUL));
		bwQueuePeak = Math.max(bwQueuePeak, queueSize(BWUL));
		double elapsedMillis = (System.nanoTime() - start) / 1_000_000.0;
		return new MidpointProbeResult(candidateTMid, elapsedMillis, forwardElapsedMillis, backwardElapsedMillis,
				pops, FWUL.isEmpty(), BWUL.isEmpty(),
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
		if ("time".equals(normalized) || "kept".equals(normalized) || "queue".equals(normalized) || "bound".equals(normalized)
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
		if (useIncrementalSourcedDominanceGraph()) {
			IncrementalSourcedDominanceGraphs.setDiagnosticContext(context);
		} else if (dominanceBackend == DominanceBackend.GRAPH_PARTIAL) {
			PaperPartialDominanceGraphs.setDiagnosticContext(context);
		} else if (dominanceBackend == DominanceBackend.LIST_PARTIAL) {
			PartialListDominanceStore.setDiagnosticContext(context);
		} else {
			PaperDominanceGraphs.setDiagnosticContext(context);
		}
	}

	private void resetDominanceStatistics() {
		if (useIncrementalSourcedDominanceGraph()) {
			IncrementalSourcedDominanceGraphs.resetStatistics();
		} else if (dominanceBackend == DominanceBackend.GRAPH_PARTIAL) {
			PaperPartialDominanceGraphs.resetStatistics();
		} else if (dominanceBackend == DominanceBackend.LIST_PARTIAL) {
			PartialListDominanceStore.resetStatistics();
		} else {
			PaperDominanceGraphs.resetStatistics();
		}
	}

	private DominanceStore createDominanceStore(Direction direction) {
		if (useIncrementalSourcedDominanceGraph()) {
			return IncrementalSourcedDominanceGraphs.create(direction,
					dominanceBackend != DominanceBackend.PAPER);
		}
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
		if (useIncrementalSourcedDominanceGraph()) {
			return IncrementalSourcedDominanceGraphs.statisticsSummary();
		}
		if (dominanceBackend == DominanceBackend.GRAPH_PARTIAL) {
			return PaperPartialDominanceGraphs.statisticsSummary();
		}
		if (dominanceBackend == DominanceBackend.LIST_PARTIAL) {
			return PartialListDominanceStore.statisticsSummary();
		}
		return PaperDominanceGraphs.statisticsSummary();
	}

	private boolean useIncrementalSourcedDominanceGraph() {
		// 2026-07-11: normal 和 no-SRI partial 统一使用增量 source-aware 图；partial 只增加
		// source 区间裁剪，不恢复旧 graph/list backend 的逐 label 扫描。
		// SRI state 尚未进入 source envelope 的可比条件，active cuts 下暂留 SRI-aware list store。
		return !sriPricingEnabled && config.useIncrementalSourcedDominanceGraph;
	}

	private boolean useIncrementalSourcedPartialDominance() {
		return useIncrementalSourcedDominanceGraph() && dominanceBackend != DominanceBackend.PAPER;
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
		generatedColumns.clear();
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
		// 2026-06-12: 闂佸憡鑹炬總鏃傜博閺夋垟鏋?ng-DSSR pricing 闂?DSSR 婵犮垼鍩栨穱娲偪閸℃稑鐭楁い蹇撴噺閺嗩參鏌?ng-set闂佹寧绋戦鈭昉 active 闂佸憡甯楅〃濠傗枖閿旂晫鈻旂€广儱鎳庣紞渚€鏌?
		// active signature 闂佸憡鐟禍顏勩€掗崜浣虹當妞ゆ垼娉曢閬嶅级閻戝棗鏋熷鐟板€块獮?restricted columns闂佹寧绋戦懟顖炲箖濡ゅ啰纾?round 婵犮垼娉涚粔鍫曞极閵堝棙浜ゆ繛鍡楅叄閸ゅ鏌涘▎妯规捣妞ゆ洑鍗冲鍧楀幢濡や礁鈧倝鏌?
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
	 * 2026-06-05: 闂佸湱顭堥ˇ鎵偓鍨皑閳ь剝顫夋穱鐑樹繆椤撱垺鍊风痪顓炴噺閸庝即鏌ｉ埡鍋亞绱炵€ｎ喖绀?exact pricing 闁哄鐗婇幐鎼佸矗閸℃稒鏅悘鐐跺亹閳规帒霉濠婂啴顎楁い锔界叀閹嫬螣绾拌鲸鐭楅悗娈垮暙閸愵亜褰傛繝銏ｅ煐閻喚寰?label 婵炲濮寸粔鍫曞垂鎼淬劍鍊烽柛婵勫劜閻ｈ京绱撴担瑙勫鞍闁诲寒鍨跺畷銏ゆ偄闁垮顦梺?
	 * 婵帗绋掗…鍫ヮ敇婵犳艾绀傞柟鎯板Г閿涙棃鏌ㄥ☉娆戭灱妞ゆ梹娲滅槐?twet.bpc.pricingSnapshot=true 闂?twet.bpc.pricingSnapshotNodeId=<nodeId> 闂佸憡鑹炬鎼佸箚鎼淬劍鍋ㄩ柕濠忕岛閸?
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

	private boolean hasRepeatedJob(List<Integer> sequence) {
		boolean[] seen = new boolean[data.n + 1];
		for (int i = 0; i < sequence.size(); i++) {
			int job = sequence.get(i).intValue();
			if (job < 1 || job > data.n) {
				continue;
			}
			if (seen[job]) {
				return true;
			}
			seen[job] = true;
		}
		return false;
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
		PackedBitSet sinkVisited = null;
		if (sriPricingEnabled) {
			sinkVisited = new PackedBitSet(data.n + 2);
			sinkVisited.add(lp.getNode().sinkId());
			addZeroDualExcludedJobs(sinkVisited);
		}
		PiecewiseLinearFunction sinkFrontier = new PiecewiseLinearFunction();
		// 2026-05-23: backward 闂佹儳绻戠喊宥団偓姘懅缁辨帡宕奸姀鐘卞寲闂佸搫鐗滈崜婵嬫閳哄倻鈻曢柣鏃€妞垮ú锝夋偨?[Tmid,pricingHorizon] 闂佺绻愰崯顖炲汲閻旂厧绠叉い鏃囥€€閸?
		// 闁哄鏅滈悷锕傛偋闁秴瑙﹂幖杈剧悼閺侀箖鏌ゅЧ鍥у姎鐟滄澘鍊块幃?shiftX闂佹寧绋戦鎼慽mToDomain 闂佹眹鍔岀€氼垳绮╅悢鍏煎仼閻忕偠濮ょ€氭煡鏌ｅ缁樻珖闁诡喖锕畷鈩冪節閸屾粌骞嶆繛鎴炴尨閸嬫捇鏌ら柨瀣殬闁?
		sinkFrontier.resetDomain(tMid, pricingHorizon);
		sinkFrontier.addSegment(tMid, pricingHorizon, 0.0, 0.0);
		PackedBitSet sinkNgMemory = new PackedBitSet(data.n + 2);
		ChildReachability sinkSets = buildBackwardChildReachability(lp.getNode().sinkId(), sinkNgMemory,
				lp.getNode(), sinkFrontier);
		BackwardLabel sink = new BackwardLabel(nextLabelId++, lp.getNode().sinkId(), null, sinkVisited,
				sinkSets.dominanceSet, sinkSets.extensionSet, sinkNgMemory, sinkFrontier,
				sriPricingEnabled ? sinkFrontier.copy() : null, emptySriCounts(),
				0.0, true, maintainRouteVisitProfile());
		BWUL.add(sink);
	}

	private boolean canContinue() {
		return config.maxExactPricingColumns > 0;
	}

	/** 2026-07-19: 使用两个 label 内 long 覆盖 128 个任务；更大实例回退 sequence 判断。 */
	private boolean maintainRouteVisitProfile() {
		return config.enableNgDssrJoinVisitProfilePruning && !sriPricingEnabled && data.n <= 2 * Long.SIZE;
	}

	private void forwardExtend(LP lp) {
		ForwardLabel label = FWUL.poll();
		if (label.isDominated) {
			return;
		}
		if (useIncrementalSourcedPartialDominance()) {
			IncrementalSourcedDominanceGraphs.prepareLabelForUse(FWTL.get(label.jid), label);
		}
		diagnosticForwardPops++;
		traceWatchedLabel("WATCH_F_POP", label);

		for (int nextJob = label.extensionSet.nextSetBit(1); nextJob > 0 && nextJob <= data.n && canContinue();
				nextJob = label.extensionSet.nextSetBit(nextJob + 1)) {
			forwardExtensionCandidates++;
			long timingStart = extensionTimingStart();
			ExtensionFrontier candidate = buildForwardExtensionFrontier(label, nextJob, lp);
			recordForwardBuildNanos(timingStart);
			if (candidate == null || Utility.isBigMValue(candidate.minReducedCost(Direction.FORWARD))) {
				if (candidate != null) {
					candidate.release();
				}
				forwardExtensionInfeasible++;
				continue;
			}
			forwardExtensionConstructed++;
			timingStart = extensionTimingStart();
			boolean boundPruned = isForwardCompletionBoundPruned(nextJob, candidate.noSriFrontier(),
					candidate.noSriMinReducedCost(Direction.FORWARD));
			recordForwardBoundCheckNanos(timingStart);
			if (boundPruned) {
				completionForwardLabelsPruned++;
				candidate.release();
				continue;
			}
			forwardExtensionBoundSurvivors++;
			timingStart = extensionTimingStart();
			ForwardLabel child = materializeForwardLabel(label, nextJob, candidate, lp);
			recordForwardBuildNanos(timingStart);
			traceTargetForward("F_CONSTRUCT", child, lp);
			traceWatchedChild("WATCH_F_CHILD", label, child, nextJob);
			timingStart = extensionTimingStart();
			InsertStatus status = insertForward(child, lp);
			recordForwardInsertNanos(timingStart);
			traceTargetForward("F_INSERT_" + status, child, lp);
			traceWatchedLabel("WATCH_F_INSERT_" + status, child);
			if (status == InsertStatus.STORED_AND_ENQUEUE) {
				timingStart = extensionTimingStart();
				FWUL.add(child);
				recordForwardQueueNanos(timingStart);
			}
		}
		diagnosticHeartbeat(lp, "forward.progress", false);
	}

	private void backwardExtend(LP lp) {
		BackwardLabel label = BWUL.poll();
		if (label.isDominated) {
			return;
		}
		// 虚拟 sink 的 jid=n+1 不进入按真实 terminal job 建立的 dominance store。
		if (useIncrementalSourcedPartialDominance() && label.jid >= 0 && label.jid < BWTL.size()) {
			IncrementalSourcedDominanceGraphs.prepareLabelForUse(BWTL.get(label.jid), label);
		}
		diagnosticBackwardPops++;
		traceWatchedLabel("WATCH_B_POP", label);

		for (int prevJob = label.extensionSet.nextSetBit(1); prevJob > 0 && prevJob <= data.n && canContinue();
				prevJob = label.extensionSet.nextSetBit(prevJob + 1)) {
			backwardExtensionCandidates++;
			long timingStart = extensionTimingStart();
			ExtensionFrontier candidate = buildBackwardExtensionFrontier(label, prevJob, lp);
			recordBackwardBuildNanos(timingStart);
			if (candidate == null || Utility.isBigMValue(candidate.minReducedCost(Direction.BACKWARD))) {
				if (candidate != null) {
					candidate.release();
				}
				backwardExtensionInfeasible++;
				continue;
			}
			backwardExtensionConstructed++;
			timingStart = extensionTimingStart();
			boolean boundPruned = isBackwardCompletionBoundPruned(prevJob, false, candidate.noSriFrontier(),
					candidate.noSriMinReducedCost(Direction.BACKWARD));
			recordBackwardBoundCheckNanos(timingStart);
			if (boundPruned) {
				completionBackwardLabelsPruned++;
				candidate.release();
				continue;
			}
			backwardExtensionBoundSurvivors++;
			timingStart = extensionTimingStart();
			BackwardLabel child = materializeBackwardLabel(label, prevJob, candidate, lp);
			recordBackwardBuildNanos(timingStart);
			traceTargetBackward("B_CONSTRUCT", child);
			traceWatchedChild("WATCH_B_CHILD", label, child, prevJob);
			timingStart = extensionTimingStart();
			InsertStatus status = insertBackward(child, lp);
			recordBackwardInsertNanos(timingStart);
			traceTargetBackward("B_INSERT_" + status, child);
			traceWatchedLabel("WATCH_B_INSERT_" + status, child);
			if (status == InsertStatus.STORED_AND_ENQUEUE) {
				timingStart = extensionTimingStart();
				BWUL.add(child);
				recordBackwardQueueNanos(timingStart);
			}
		}
		diagnosticHeartbeat(lp, "backward.progress", false);
	}

	private long extensionTimingStart() {
		return config.ngDssrExtensionTimingDiagnostics ? System.nanoTime() : 0L;
	}

	private void recordForwardBuildNanos(long start) {
		if (start != 0L) {
			forwardExtensionBuildNanos += System.nanoTime() - start;
		}
	}

	private void recordBackwardBuildNanos(long start) {
		if (start != 0L) {
			backwardExtensionBuildNanos += System.nanoTime() - start;
		}
	}

	private void recordForwardBoundCheckNanos(long start) {
		if (start != 0L) {
			forwardExtensionBoundCheckNanos += System.nanoTime() - start;
		}
	}

	private void recordBackwardBoundCheckNanos(long start) {
		if (start != 0L) {
			backwardExtensionBoundCheckNanos += System.nanoTime() - start;
		}
	}

	private void recordForwardInsertNanos(long start) {
		if (start != 0L) {
			forwardExtensionInsertNanos += System.nanoTime() - start;
		}
	}

	private void recordBackwardInsertNanos(long start) {
		if (start != 0L) {
			backwardExtensionInsertNanos += System.nanoTime() - start;
		}
	}

	private void recordForwardQueueNanos(long start) {
		if (start != 0L) {
			forwardExtensionQueueNanos += System.nanoTime() - start;
		}
	}

	private void recordBackwardQueueNanos(long start) {
		if (start != 0L) {
			backwardExtensionQueueNanos += System.nanoTime() - start;
		}
	}

	private void recordForwardWindowCheckNanos(long start) {
		if (start != 0L) {
			forwardExtensionWindowCheckNanos += System.nanoTime() - start;
		}
	}

	private void recordBackwardWindowCheckNanos(long start) {
		if (start != 0L) {
			backwardExtensionWindowCheckNanos += System.nanoTime() - start;
		}
	}

	private void recordForwardFunctionNanos(long start) {
		if (start != 0L) {
			forwardExtensionFunctionNanos += System.nanoTime() - start;
		}
	}

	private void recordBackwardFunctionNanos(long start) {
		if (start != 0L) {
			backwardExtensionFunctionNanos += System.nanoTime() - start;
		}
	}

	private void recordForwardStateNanos(long start) {
		if (start != 0L) {
			forwardExtensionStateNanos += System.nanoTime() - start;
		}
	}

	private void recordBackwardStateNanos(long start) {
		if (start != 0L) {
			backwardExtensionStateNanos += System.nanoTime() - start;
		}
	}

	private void recordForwardDominanceGraphNanos(long start) {
		if (start != 0L) {
			forwardDominanceGraphInsertNanos += System.nanoTime() - start;
		}
	}

	private void recordBackwardDominanceGraphNanos(long start) {
		if (start != 0L) {
			backwardDominanceGraphInsertNanos += System.nanoTime() - start;
		}
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

	private ExtensionFrontier buildForwardExtensionFrontier(ForwardLabel label, int nextJob, LP lp) {
		double delay = data.getSetUp(label.jid, nextJob) + data.getProcessT(nextJob);
		long timingStart = extensionTimingStart();
		boolean hasWindowOverlap = hasForwardExtensionWindowOverlap(label, nextJob, delay);
		recordForwardWindowCheckNanos(timingStart);
		if (!hasWindowOverlap) {
			return null;
		}
		timingStart = extensionTimingStart();
		PiecewiseLinearFunction shifted = label.frontier.shiftX(delay);
		if (shifted.head == null) {
			shifted.release();
			recordForwardFunctionNanos(timingStart);
			return null;
		}

		PiecewiseLinearFunction jobPenalty = getDynamicForwardJobPenalty(label.jid, nextJob);
		if (jobPenalty == null) {
			shifted.release();
			recordForwardFunctionNanos(timingStart);
			return null;
		}
		PiecewiseLinearFunction nextFrontier = shifted.add(jobPenalty);
		shifted.release();
		if (nextFrontier.head == null) {
			nextFrontier.release();
			recordForwardFunctionNanos(timingStart);
			return null;
		}
		PiecewiseLinearFunction nextNoSriFrontier = null;
		if (sriPricingEnabled) {
			PiecewiseLinearFunction shiftedNoSri = label.noSriFrontier.shiftX(delay);
			if (shiftedNoSri.head == null) {
				shiftedNoSri.release();
				nextFrontier.release();
				recordForwardFunctionNanos(timingStart);
				return null;
			}
			nextNoSriFrontier = shiftedNoSri.add(jobPenalty);
			shiftedNoSri.release();
			if (nextNoSriFrontier.head == null) {
				nextNoSriFrontier.release();
				nextFrontier.release();
				recordForwardFunctionNanos(timingStart);
				return null;
			}
		}
		double fixedReducedCost = pricingSetupCost(label.jid, nextJob) - lp.getJobDual(nextJob)
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
			childSriCounts = EMPTY_SRI_COUNTS;
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
			recordForwardFunctionNanos(timingStart);
			return null;
		}
		recordForwardFunctionNanos(timingStart);

		return new ExtensionFrontier(nextFrontier, nextNoSriFrontier, childSriCounts, childSriPenalty);
	}

	private ExtensionFrontier buildBackwardExtensionFrontier(BackwardLabel label, int prevJob, LP lp) {
		Node node = lp.getNode();
		PiecewiseLinearFunction nextFrontier;
		PiecewiseLinearFunction nextNoSriFrontier;
		long timingStart = extensionTimingStart();
		int successor = label.isSinkRoot ? node.sinkId() : label.jid;
		double successorHStart = getDynamicBackwardHStart(prevJob, successor);
		double successorHEnd = getDynamicBackwardHEnd(prevJob, successor);
		double rhoPrime;
		if (label.isSinkRoot) {
			rhoPrime = successorHEnd;
			recordBackwardWindowCheckNanos(timingStart);
			if (Utility.compareLt(rhoPrime, Math.max(tMid, successorHStart))) {
				return null;
			}
			timingStart = extensionTimingStart();
			PiecewiseLinearFunction jobPenalty = getDynamicBackwardJobPenalty(prevJob, node.sinkId());
			if (jobPenalty == null) {
				recordBackwardFunctionNanos(timingStart);
				return null;
			}
			// 2026-05-22: backward 婵炲濮村锕€鈻嶉崟顖氱闁绘棁娅ｉ惌鎺楁煟閹邦喗鍤€闁搞値鍙冨畷锝夊箣閻愭惌妲梺鎸庣☉閻ジ顢栭崶銊р枖闁逞屽墮閳诲酣鍨鹃崘宸奖闂佺绻堥崕鍐诧耿閿涘嫧鍋撻崷顓炰粧闁瑰箍鍨藉畷婵嬪灳閼碱剛鎲归梻鍌氭礌閸嬫捇鎮烽弴姘卞妽閻?setup/processing 濡ょ姷鍋涚壕顓濈昂闂?
			// 閻熸粎澧楅幐鍛婃櫠閻樿鐭楁慨妞诲亾闁革絽鎼蹇涙嚑椤掑倻姊鹃梺?prevJob 闂佺厧顨庢禍婊呮崲娓氣偓閹啴宕熼銈嗘闂佺懓鐡ㄩ崝鏍ь渻閸岀偞鈷掗弶鍫濆⒔缁€澶愬级閳哄倻鎳囬柛锝囧厴瀹曪綁顢旈崨顓涙嫺 job/arc dual闂?
			nextFrontier = jobPenalty.copy();
			nextNoSriFrontier = sriPricingEnabled ? jobPenalty.copy() : null;
			double fixedReducedCost = -lp.getJobDual(prevJob) - lp.getArcDual(prevJob, node.sinkId());
			nextFrontier.shiftYInPlace(fixedReducedCost);
			if (nextNoSriFrontier != null) {
				nextNoSriFrontier.shiftYInPlace(fixedReducedCost);
			}
		} else {
			double delay = data.getSetUp(prevJob, label.jid) + data.getProcessT(label.jid);
			boolean hasWindowOverlap = hasBackwardExtensionWindowOverlap(
					label, delay, successorHStart, successorHEnd);
			recordBackwardWindowCheckNanos(timingStart);
			if (!hasWindowOverlap) {
				return null;
			}
			timingStart = extensionTimingStart();
			rhoPrime = Math.min(label.frontier.tail.end - delay, successorHEnd);
			if (Utility.compareLt(rhoPrime, Math.max(tMid, successorHStart))) {
				recordBackwardFunctionNanos(timingStart);
				return null;
			}
			PiecewiseLinearFunction shifted = label.frontier.shiftX(-delay);
			if (shifted.head == null) {
				shifted.release();
				recordBackwardFunctionNanos(timingStart);
				return null;
			}
			PiecewiseLinearFunction jobPenalty = getDynamicBackwardJobPenalty(prevJob, label.jid);
			if (jobPenalty == null) {
				shifted.release();
				recordBackwardFunctionNanos(timingStart);
				return null;
			}
			nextFrontier = shifted.add(jobPenalty);
			shifted.release();
			if (nextFrontier.head == null) {
				nextFrontier.release();
				recordBackwardFunctionNanos(timingStart);
				return null;
			}
			nextNoSriFrontier = null;
			if (sriPricingEnabled) {
				PiecewiseLinearFunction shiftedNoSri = label.noSriFrontier.shiftX(-delay);
				if (shiftedNoSri.head == null) {
					shiftedNoSri.release();
					nextFrontier.release();
					recordBackwardFunctionNanos(timingStart);
					return null;
				}
				nextNoSriFrontier = shiftedNoSri.add(jobPenalty);
				shiftedNoSri.release();
				if (nextNoSriFrontier.head == null) {
					nextNoSriFrontier.release();
					nextFrontier.release();
					recordBackwardFunctionNanos(timingStart);
					return null;
				}
			}
			double fixedReducedCost = pricingSetupCost(prevJob, label.jid) - lp.getJobDual(prevJob)
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
			childSriCounts = EMPTY_SRI_COUNTS;
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
			recordBackwardFunctionNanos(timingStart);
			return null;
		}
		recordBackwardFunctionNanos(timingStart);

		return new ExtensionFrontier(nextFrontier, nextNoSriFrontier, childSriCounts, childSriPenalty);
	}

	private ForwardLabel materializeForwardLabel(ForwardLabel parent, int nextJob, ExtensionFrontier candidate,
			LP lp) {
		long timingStart = extensionTimingStart();
		PackedBitSet visited = null;
		if (sriPricingEnabled) {
			visited = parent.visitedSet.copy();
			visited.add(nextJob);
		}
		PackedBitSet childNgMemory = updateNgMemory(parent.ngMemorySet, nextJob);
		ChildReachability childSets = buildForwardChildReachability(nextJob, childNgMemory, lp.getNode(),
				candidate.frontier);
		ForwardLabel child = new ForwardLabel(nextLabelId++, nextJob, parent, visited, childSets.dominanceSet,
				childSets.extensionSet, childNgMemory, candidate.frontier, candidate.noSriFrontier,
				candidate.sriCounts, candidate.sriPenalty, maintainRouteVisitProfile());
		recordForwardStateNanos(timingStart);
		return child;
	}

	private BackwardLabel materializeBackwardLabel(BackwardLabel parent, int prevJob, ExtensionFrontier candidate,
			LP lp) {
		long timingStart = extensionTimingStart();
		PackedBitSet visited = null;
		if (sriPricingEnabled) {
			visited = parent.visitedSet.copy();
			visited.add(prevJob);
		}
		PackedBitSet childNgMemory = updateNgMemory(parent.ngMemorySet, prevJob);
		ChildReachability childSets = buildBackwardChildReachability(prevJob, childNgMemory, lp.getNode(),
				candidate.frontier);
		BackwardLabel child = new BackwardLabel(nextLabelId++, prevJob, parent, visited, childSets.dominanceSet,
				childSets.extensionSet, childNgMemory, candidate.frontier, candidate.noSriFrontier,
				candidate.sriCounts, candidate.sriPenalty, false, maintainRouteVisitProfile());
		recordBackwardStateNanos(timingStart);
		return child;
	}

	/** 鎻愬墠鍒ゆ柇 forward 鎵╁睍鍚庣殑瀹屾垚鏃堕棿鍖洪棿鏄惁鍙兘涓庝换鍔℃湁鏁堢獥鍙ｇ浉浜わ紝閬垮厤鏋勯€犲繀涓虹┖鐨?PWLF銆?*/
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

	/** 鎻愬墠鍒ゆ柇 backward 鎵╁睍鍚庣殑瀹屾垚鏃堕棿鍖洪棿鏄惁鍙兘涓庝换鍔℃湁鏁堢獥鍙ｇ浉浜わ紝閬垮厤鏋勯€犲繀涓虹┖鐨?PWLF銆?*/
	private boolean hasBackwardExtensionWindowOverlap(BackwardLabel label, double delay,
			double successorHStart, double successorHEnd) {
		if (label.frontier == null || label.frontier.head == null) {
			return false;
		}
		double shiftedStart = Math.max(label.frontier.head.start - delay, label.frontier.domainStart);
		double shiftedEnd = Math.min(label.frontier.tail.end - delay, label.frontier.domainEnd);
		double windowStart = Math.max(successorHStart, tMid);
		double windowEnd = Math.min(successorHEnd, pricingHorizon);
		double overlapStart = Math.max(shiftedStart, windowStart);
		double overlapEnd = Math.min(shiftedEnd, windowEnd);
		return !Utility.compareLt(overlapEnd, overlapStart);
	}

	private InsertStatus insertForward(ForwardLabel label, LP lp) {
		if (isSinglePointFrontier(label.frontier)) {
			return insertForwardSinglePoint(label, lp);
		}
		long timingStart = extensionTimingStart();
		boolean dominated = FWTL.get(label.jid).insertOrDominate(label);
		recordForwardDominanceGraphNanos(timingStart);
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
		long timingStart = extensionTimingStart();
		boolean dominated = BWTL.get(label.jid).insertOrDominate(label);
		recordBackwardDominanceGraphNanos(timingStart);
		if (dominated) {
			backwardLabelsDominated++;
			return InsertStatus.DOMINATED;
		}
		backwardLabelsKept++;
		activeBackwardByFirstJob.get(label.jid).add(label);
		return InsertStatus.STORED_AND_ENQUEUE;
	}

	/**
	 * 2026-05-25: Tmid 闂佸憡顨嗗ú婊堝磻?forward label 婵炴垶鎸哥粔鎾疮閳ь剟寮堕埡鍌溾槈闁告瑥妫濆鏌ヮ敋閳ь剟鍩€?dominance graph闂佹寧绋戞總鏃傚姬閸愨晝鈻旂€广儱鎳庨弲娆撴煕韫囧鍔滃褑娉曟禒锕傚即閵忊寬鏇㈡煕閹烘搩娈ｇ紒?
	 * 婵炶揪绲藉Λ鏃傚垝濞戞碍鍟哄ù锝夘棑缁犱粙鏌ｉ敐鍡欐噮缂?sink 闂佽　鍋撻悹楦挎閸熸煡鏌涘鍐闁诡喗顨堢槐?backward join闂?
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
	 * 2026-05-25: Tmid 闂佸憡顨嗗ú婊堝磻?backward label 闂佸憡鐟禍娆戞崲濮樿埖鍋╂繛鍡樺灩閼?single-point store闂?
	 * 2026-05-26: 闂?GCNGBB-style 濠电偟绻濋懗鍫曞煝閸忚偐鈻旈悗锝傛櫇閻熸繄绱掗弬娆惧剰鐎?join闂佹寧绋戦惌渚€鍩€椤掆偓閺堫剙危閹间礁鎹堕柕濠忛檮娴犳﹢鏌涘顒佹崳缂侇喚鍎ょ粙澶愬焵椤掑嫬绠ユい鎰剁到娴?join闂?
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
	 * 2026-05-28: final join 闂佸憡鎸哥粔鍓佸垝閻戞鈻旈柍褜鍓欓妴鎺楀川椤旇偐顏遍悗瑙勭摃鐏忣亪锝為敃鍌氳Е閹艰揪绲块弫?label 闂佽　鍋撴い鏍仜鐢娊鏌ｉ妸銉ヮ仾婵☆偒鍋婂鍫曟晬閸曨剛鐛ラ梺鎸庣☉閼活垶宕埀顒勬煙閻戞ê绗掔紒顭戝弮婵?
	 * 闁哄鏅滈悷锕傛偋鏉堚斁鍋撻悷鐗堟拱闁哄棴缍佸畷姘旈埀顒冦亹瑜庣粋鎺撴償閵忊€茬帛缂傚倷绀侀悧鍛垝濞戞埃鍋撳☉娅虫垵顪冮崸妤佸剭?label table 闂備焦褰冮惉濂稿极閹捐绠ｉ柟鏉垮缁€澶娾槈閹惧磭孝鐟滄澘鐗撳顕€鍩￠崒娑樼劯闂佸憡鍨跺钘壩ｉ敂濮愪簻闁告繂瀚喊宥囨喐閺夊灝鑸归柟鑲╁厴婵?
	 */
	private void compactAndSortActiveLabelListsForJoin() {
		for (int job = 1; job <= data.n; job++) {
			compactForwardLabelsForJoin(job);
			compactBackwardLabelsForJoin(job);
		}
	}

	private void compactForwardLabelsForJoin(int job) {
		ArrayList<ForwardLabel> labels = activeForwardByLastJob.get(job);
		boolean preparePartial = useIncrementalSourcedPartialDominance();
		int liveCount = 0;
		double liveMinReducedCost = Utility.big_M;
		double liveMinEll = Utility.big_M;
		for (int i = 0; i < labels.size(); i++) {
			ForwardLabel label = labels.get(i);
			if (label.isDominated) {
				continue;
			}
			if (preparePartial) {
				IncrementalSourcedDominanceGraphs.prepareLabelForUse(FWTL.get(job), label);
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
		boolean preparePartial = useIncrementalSourcedPartialDominance();
		int liveCount = 0;
		for (int i = 0; i < labels.size(); i++) {
			BackwardLabel label = labels.get(i);
			if (label.isDominated) {
				continue;
			}
			if (preparePartial) {
				IncrementalSourcedDominanceGraphs.prepareLabelForUse(BWTL.get(job), label);
			}
			labels.set(liveCount++, label);
		}
		if (liveCount < labels.size()) {
			labels.subList(liveCount, labels.size()).clear();
		}
		Collections.sort(labels);
	}

	/**
	 * 2026-05-28: 缂傚倷鑳堕崰宥囩博閹绢喖缁╅悹楦挎閸?join闂侀潧妫楅崐椋庢偖鏉堛劎鐟?label table 闂備緡鍠氶弲顐﹀极閹捐绠ｉ柟閭﹀墰閺嗘艾霉閻橆喖鍔氶柟顔筋殜閺佸秶浠﹂梻瀵搁瀺 forward terminal group 婵炴垶鎸搁幖顐λ囬懡銈勬勃闁稿矉濡囩粈?
	 * 闂佸憡鑹鹃張顒€顪冮崒娑樼窞闁告洦鍘介崐?crossing-arc join 闂?forward->sink 闂佽　鍋撻悹楦挎閸熸煡鏌ㄥ☉妯肩劯濞村皷鏅犲畷?sink 闂佸憡甯楅〃鍫㈠垝椤愶絾浜ら柛銉㈡櫇閸╃姴鈽夐幘顖氫壕闂佺锕ラ悷鈺呭焵椤掆偓椤﹂亶鎮洪锔界劵濠㈣泛鐗冮崑?
	 */
	private void joinAllForwardTerminalGroups(LP lp) {
		if (useJoinEnvelopeCompression()) {
			joinAllForwardTerminalGroupsByEnvelope(lp);
			return;
		}
		if (useJoinEnvelopePrefilter()) {
			joinAllForwardTerminalGroupsWithEnvelopePrefilter(lp);
			return;
		}
		for (int lastJob = activeForwardTerminalJobs.nextSetBit(0); lastJob >= 0 && lastJob <= data.n && canContinue();
				lastJob = activeForwardTerminalJobs.nextSetBit(lastJob + 1)) {
			ArrayList<ForwardLabel> candidates = activeForwardByLastJob.get(lastJob);
			if (candidates.isEmpty()) {
				continue;
			}
			joinForwardGroupToBackwardLabels(lastJob, candidates, lp);
			if (canContinue()) {
				joinForwardGroupToSink(candidates, lp);
			}
		}
	}

	private boolean useJoinEnvelopeCompression() {
		return config.enableNgDssrJoinEnvelopeCompression && !sriPricingEnabled && !limitedMemorySriPricing;
	}

	private boolean useJoinEnvelopePrefilter() {
		return config.enableNgDssrJoinEnvelopePrefilter && !sriPricingEnabled && !limitedMemorySriPricing;
	}

	/**
	 * 2026-07-11: envelope 只证明整组 label pair 不可能形成负 reduced-cost 列。
	 * 未被证明可剪的 group 仍完整执行标准 label-level join，保持批量加列和证书口径。
	 */
	private void joinAllForwardTerminalGroupsWithEnvelopePrefilter(LP lp) {
		long buildStart = System.nanoTime();
		JoinEnvelopeIndex index = buildJoinEnvelopeIndex();
		joinEnvelopeBuildNanos += System.nanoTime() - buildStart;
		long joinStart = System.nanoTime();
		for (int lastJob = activeForwardTerminalJobs.nextSetBit(0);
				lastJob >= 0 && lastJob <= data.n && canContinue();
				lastJob = activeForwardTerminalJobs.nextSetBit(lastJob + 1)) {
			ArrayList<ForwardLabel> candidates = activeForwardByLastJob.get(lastJob);
			if (candidates.isEmpty()) {
				continue;
			}
			joinForwardGroupToBackwardLabels(lastJob, candidates, index, lp);
			if (canContinue()) {
				joinForwardGroupToSink(candidates, lp);
			}
		}
		joinEnvelopeJoinNanos += System.nanoTime() - joinStart;
	}

	private boolean canPruneJoinEnvelopeGroupPair(int lastJob, JoinEnvelopeGroup<ForwardLabel> forward,
			JoinEnvelopeGroup<BackwardLabel> backward, LP lp) {
		Node node = lp.getNode();
		if (backward.ngMemorySet.contains(lastJob)
				|| isPricingArcForbidden(node, lastJob, backward.terminalJob)
				|| forward.terminalJob == backward.terminalJob
				|| bitSetsIntersectForJoin(forward.ngMemorySet, backward.ngMemorySet)) {
			return true;
		}
		double delay = data.getSetUp(lastJob, backward.terminalJob) + data.getProcessT(backward.terminalJob);
		if (Utility.compareGt(forward.envelope.start() + delay, backward.envelope.end())) {
			return true;
		}
		double fixedReducedCost = pricingSetupCost(lastJob, backward.terminalJob)
				- lp.getArcDual(lastJob, backward.terminalJob);
		double threshold = REDUCED_COST_TOLERANCE;
		if (!Utility.compareLt(forward.minReducedCost + backward.minReducedCost + fixedReducedCost, threshold)) {
			return true;
		}
		joinEnvelopePrefilterFunctionEvaluations++;
		double reducedCostBound = findMinimalShiftedTracedSum(forward.envelope, delay, backward.envelope,
				fixedReducedCost).reducedCost;
		observeRelaxedReducedCost(reducedCostBound);
		return !Utility.compareLt(reducedCostBound, threshold);
	}

	private void joinAllForwardTerminalGroupsByEnvelope(LP lp) {
		long buildStart = System.nanoTime();
		JoinEnvelopeIndex index = buildJoinEnvelopeIndex();
		joinEnvelopeBuildNanos += System.nanoTime() - buildStart;
		long joinStart = System.nanoTime();
		for (int lastJob = activeForwardTerminalJobs.nextSetBit(0); lastJob >= 0 && lastJob <= data.n && canContinue();
				lastJob = activeForwardTerminalJobs.nextSetBit(lastJob + 1)) {
			ArrayList<ForwardLabel> candidates = activeForwardByLastJob.get(lastJob);
			if (candidates.isEmpty()) {
				continue;
			}
			ArrayList<JoinEnvelopeGroup<ForwardLabel>> forwardGroups = index.forwardByTerminal.get(lastJob);
			if (forwardGroups != null && !forwardGroups.isEmpty()) {
				joinForwardEnvelopeGroupsToBackward(lastJob, forwardGroups, index, lp);
			}
			if (canContinue()) {
				joinForwardGroupToSink(candidates, lp);
			}
		}
		joinEnvelopeJoinNanos += System.nanoTime() - joinStart;
	}

	private void joinForwardGroupToBackwardLabels(int lastJob, ArrayList<ForwardLabel> candidates, LP lp) {
		joinForwardGroupToBackwardLabels(lastJob, candidates, null, lp);
	}

	private void joinForwardGroupToBackwardLabels(int lastJob, ArrayList<ForwardLabel> candidates,
			JoinEnvelopeIndex prefilterIndex, LP lp) {
		for (int firstJob = 1; firstJob <= data.n && canContinue(); firstJob++) {
			ArrayList<BackwardLabel> labels = activeBackwardByFirstJob.get(firstJob);
			for (int i = 0; i < labels.size() && canContinue(); i++) {
				BackwardLabel backward = labels.get(i);
				if (!backward.isDominated && !backward.isSinkRoot) {
					joinForwardGroupWithBackward(lastJob, candidates, backward, prefilterIndex, lp);
				}
			}
		}
		for (int firstJob = 1; firstJob <= data.n && canContinue(); firstJob++) {
			joinForwardGroupWithBackwardSinglePoints(lastJob, candidates, backwardSinglePointByFirstJob.get(firstJob),
					prefilterIndex, lp);
		}
	}

	private void joinForwardGroupWithBackwardSinglePoints(int lastJob, ArrayList<ForwardLabel> candidates,
			SinglePointStore<BackwardLabel> store, LP lp) {
		joinForwardGroupWithBackwardSinglePoints(lastJob, candidates, store, null, lp);
	}

	private void joinForwardGroupWithBackwardSinglePoints(int lastJob, ArrayList<ForwardLabel> candidates,
			SinglePointStore<BackwardLabel> store, JoinEnvelopeIndex prefilterIndex, LP lp) {
		for (int cardinality = 0; cardinality < store.liveLabelsByCardinality.size() && canContinue(); cardinality++) {
			ArrayList<BackwardLabel> bucket = store.liveLabelsByCardinality.get(cardinality);
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int i = 0; i < bucket.size() && canContinue(); i++) {
				BackwardLabel backward = bucket.get(i);
				if (!backward.isDominated && !backward.isSinkRoot) {
					joinForwardGroupWithBackward(lastJob, candidates, backward, prefilterIndex, lp);
				}
			}
		}
	}

	private JoinEnvelopeIndex buildJoinEnvelopeIndex() {
		JoinEnvelopeIndex index = new JoinEnvelopeIndex(data.n + 1);
		for (int job = 1; job <= data.n; job++) {
			HashMap<PackedBitSet, JoinEnvelopeGroup<ForwardLabel>> forwardMap =
					new HashMap<PackedBitSet, JoinEnvelopeGroup<ForwardLabel>>();
			ArrayList<ForwardLabel> forwardLabels = activeForwardByLastJob.get(job);
			for (int i = 0; i < forwardLabels.size(); i++) {
				ForwardLabel label = forwardLabels.get(i);
				if (!label.isDominated) {
					JoinEnvelopeGroup<ForwardLabel> group = addForwardJoinEnvelopeGroup(forwardMap, job, label);
					if (group != null) {
						group.memberIndices.set(i);
					}
				}
			}
			if (!forwardMap.isEmpty()) {
				ArrayList<JoinEnvelopeGroup<ForwardLabel>> groups =
						new ArrayList<JoinEnvelopeGroup<ForwardLabel>>(forwardMap.values());
				joinEnvelopeSegments += finalizeJoinEnvelopeGroups(groups);
				Collections.sort(groups);
				index.forwardByTerminal.set(job, groups);
				joinEnvelopeForwardGroups += groups.size();
			}

			HashMap<PackedBitSet, JoinEnvelopeGroup<BackwardLabel>> backwardMap =
					new HashMap<PackedBitSet, JoinEnvelopeGroup<BackwardLabel>>();
			ArrayList<BackwardLabel> backwardLabels = activeBackwardByFirstJob.get(job);
			for (int i = 0; i < backwardLabels.size(); i++) {
				BackwardLabel label = backwardLabels.get(i);
				if (!label.isDominated && !label.isSinkRoot) {
					addBackwardJoinEnvelopeGroup(backwardMap, job, label);
				}
			}
			addBackwardSinglePointJoinEnvelopeGroups(backwardMap, job, backwardSinglePointByFirstJob.get(job));
			if (!backwardMap.isEmpty()) {
				ArrayList<JoinEnvelopeGroup<BackwardLabel>> groups =
						new ArrayList<JoinEnvelopeGroup<BackwardLabel>>(backwardMap.values());
				joinEnvelopeSegments += finalizeJoinEnvelopeGroups(groups);
				indexBackwardJoinEnvelopeLabels(index, groups);
				Collections.sort(groups);
				index.backwardByTerminal.set(job, groups);
				joinEnvelopeBackwardGroups += groups.size();
			}
		}
		return index;
	}


	private void indexBackwardJoinEnvelopeLabels(JoinEnvelopeIndex index,
			ArrayList<JoinEnvelopeGroup<BackwardLabel>> groups) {
		for (int i = 0; i < groups.size(); i++) {
			JoinEnvelopeGroup<BackwardLabel> group = groups.get(i);
			for (int j = 0; j < group.labels.size(); j++) {
				index.backwardGroupByLabel.put(group.labels.get(j), group);
			}
		}
	}

	private <L extends FunctionLabel> long finalizeJoinEnvelopeGroups(ArrayList<JoinEnvelopeGroup<L>> groups) {
		long count = 0;
		for (int i = 0; i < groups.size(); i++) {
			JoinEnvelopeGroup<L> group = groups.get(i);
			group.minReducedCost = group.envelope.minValue();
			count += group.envelope.segmentCount();
		}
		return count;
	}

	/**
	 * 把 label 加入对应的 forward envelope group；返回 null 表示该 label 没有可用于 join 的函数。
	 */
	private JoinEnvelopeGroup<ForwardLabel> addForwardJoinEnvelopeGroup(
			HashMap<PackedBitSet, JoinEnvelopeGroup<ForwardLabel>> map,
			int terminalJob, ForwardLabel label) {
		PiecewiseLinearFunction function = getForwardJoinExtension(label);
		if (function == null || function.head == null) {
			return null;
		}
		JoinEnvelopeGroup<ForwardLabel> group = joinEnvelopeGroup(map, terminalJob, label.ngMemorySet);
		group.labels.add(label);
		group.envelope.merge(function, label);
		joinEnvelopeForwardLabels++;
		return group;
	}

	private void addBackwardJoinEnvelopeGroup(HashMap<PackedBitSet, JoinEnvelopeGroup<BackwardLabel>> map,
			int terminalJob, BackwardLabel label) {
		PiecewiseLinearFunction function = getBackwardJoinExtension(label);
		if (function == null || function.head == null) {
			return;
		}
		JoinEnvelopeGroup<BackwardLabel> group = joinEnvelopeGroup(map, terminalJob, label.ngMemorySet);
		group.labels.add(label);
		group.envelope.merge(function, label);
		joinEnvelopeBackwardLabels++;
	}

	private void addBackwardSinglePointJoinEnvelopeGroups(HashMap<PackedBitSet, JoinEnvelopeGroup<BackwardLabel>> map,
			int terminalJob, SinglePointStore<BackwardLabel> store) {
		for (int cardinality = 0; cardinality < store.liveLabelsByCardinality.size(); cardinality++) {
			ArrayList<BackwardLabel> bucket = store.liveLabelsByCardinality.get(cardinality);
			if (bucket == null || bucket.isEmpty()) {
				continue;
			}
			for (int i = 0; i < bucket.size(); i++) {
				BackwardLabel label = bucket.get(i);
				if (!label.isDominated && !label.isSinkRoot) {
					addBackwardJoinEnvelopeGroup(map, terminalJob, label);
				}
			}
		}
	}

	private <L extends FunctionLabel> JoinEnvelopeGroup<L> joinEnvelopeGroup(
			HashMap<PackedBitSet, JoinEnvelopeGroup<L>> map, int terminalJob, PackedBitSet ngMemorySet) {
		JoinEnvelopeGroup<L> group = map.get(ngMemorySet);
		if (group != null) {
			return group;
		}
		PackedBitSet key = ngMemorySet.copy();
		group = new JoinEnvelopeGroup<L>(terminalJob, key);
		map.put(key, group);
		return group;
	}

	private void joinForwardEnvelopeGroupsToBackward(int lastJob,
			ArrayList<JoinEnvelopeGroup<ForwardLabel>> forwardGroups, JoinEnvelopeIndex index, LP lp) {
		for (int firstJob = 1; firstJob <= data.n && canContinue(); firstJob++) {
			ArrayList<JoinEnvelopeGroup<BackwardLabel>> backwardGroups = index.backwardByTerminal.get(firstJob);
			if (backwardGroups == null || backwardGroups.isEmpty()) {
				continue;
			}
			for (int b = 0; b < backwardGroups.size() && canContinue(); b++) {
				JoinEnvelopeGroup<BackwardLabel> backward = backwardGroups.get(b);
				for (int f = 0; f < forwardGroups.size() && canContinue(); f++) {
					joinForwardEnvelopeGroupWithBackward(lastJob, forwardGroups.get(f), backward, lp);
				}
			}
		}
	}

	private void joinForwardEnvelopeGroupWithBackward(int lastJob, JoinEnvelopeGroup<ForwardLabel> forward,
			JoinEnvelopeGroup<BackwardLabel> backward, LP lp) {
		if (config.maxExactPricingColumns <= 0) {
			return;
		}
		Node node = lp.getNode();
		joinTerminalGroupsScanned++;
		joinEnvelopeGroupPairs++;
		if (backward.ngMemorySet.contains(lastJob) || isPricingArcForbidden(node, lastJob, backward.terminalJob)) {
			joinTerminalGroupsArcOrVisitPruned++;
			joinEnvelopeGroupPairsPruned++;
			return;
		}
		if (forward.terminalJob == backward.terminalJob) {
			joinPairsSetPruned++;
			joinEnvelopeGroupPairsPruned++;
			return;
		}
		if (bitSetsIntersectForJoin(forward.ngMemorySet, backward.ngMemorySet)) {
			joinPairsSetPruned++;
			joinEnvelopeGroupPairsPruned++;
			return;
		}
		double delay = data.getSetUp(lastJob, backward.terminalJob) + data.getProcessT(backward.terminalJob);
		if (Utility.compareGt(forward.envelope.start() + delay, backward.envelope.end())) {
			joinTerminalGroupsTimePruned++;
			joinEnvelopeGroupPairsPruned++;
			return;
		}
		double joinFixedReducedCost = pricingSetupCost(lastJob, backward.terminalJob)
				- lp.getArcDual(lastJob, backward.terminalJob);
		double joinThreshold = joinLowerBoundThreshold();
		double groupLB = forward.minReducedCost + backward.minReducedCost + joinFixedReducedCost;
		if (!Utility.compareLt(groupLB, joinThreshold)) {
			joinTerminalGroupsCostPruned++;
			joinPairsLowerBoundPruned++;
			if (Utility.compareLt(joinThreshold, REDUCED_COST_TOLERANCE)) {
				joinPairsBestBoundPruned++;
			}
			joinEnvelopeGroupPairsPruned++;
			return;
		}
		joinPairsTried++;
		joinFunctionEvaluations++;
		joinEnvelopeFunctionEvaluations++;
		JoinEnvelopeMinResult result = findMinimalShiftedTracedSum(forward.envelope, delay, backward.envelope,
				joinFixedReducedCost);
		double reducedCostBound = result.reducedCost;
		observeRelaxedReducedCost(reducedCostBound);
		if (!shouldKeepJoinedReducedCost(reducedCostBound)) {
			joinFunctionPruned++;
			if (Utility.compareLt(reducedCostBound, REDUCED_COST_TOLERANCE)) {
				joinFunctionBestRecordPruned++;
			}
			return;
		}
		if (result.forwardLabel == null || result.backwardLabel == null) {
			joinFunctionPruned++;
			return;
		}
		// 2026-07-09: envelope join 每个 group-pair 只返回一个代表 split；同一 sequence 的更优 split
		// 可能来自其它 group-pair。入候选堆前用真实 sequence cost 回刷，避免把代表 split 的
		// inferred cost 写进 RMP。普通 label-pair join 仍保持原路径。
		tryGenerateColumn(recoverJoinSequence(result.forwardLabel, result.backwardLabel), lp, reducedCostBound, true);
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
		joinForwardGroupWithBackward(lastJob, candidates, backward, null, lp);
	}

	private void joinForwardGroupWithBackward(int lastJob, ArrayList<ForwardLabel> candidates, BackwardLabel backward,
			JoinEnvelopeIndex prefilterIndex, LP lp) {
		Node node = lp.getNode();
		// 2026-05-23: 闂?joinFromForward 闁诲酣娼у﹢杈叿闂佹寧绋戞總鏃傜箔婢舵劖鍤勯柣锝呮湰閺?backward.reachableSet 闂佸憡鐟ョ粔鐢垫暜瑜版帒绠ラ柍褜鍓熷鍨緞婵犲倽顔夐梺鐟板槻閸氬鏁幘顔肩鐎广儱娲ㄧ壕濠氭煏?
		// 闁荤姴娲㈤崕闈涒枖閿曞倸瑙﹂柛顐ゅ枑绗?backward 缂傚倷缍€閸涱垱鏆伴梺鍛婄閸ㄥ灚绋婅箛娑樼闁宠桨鑳跺鏃堟煟閵娿儱顏╅柍褜鍓氶悷鈺呭焵椤掆偓椤р偓缂佽鲸绻冪粙澶婎吋閸モ晜鎯ｆ繛瀵稿Ь濞撳湱鑺遍鍕闁逞屽墴瀵灚寰勬繝鍌濐唹婵炴垶鎸告鍝ョ礊鐎ｎ喖绀堢€广儱鎳忛崐鐢电磽閸屾浜鹃梺鐟板槻閸氬鏁幘顔藉剭?forward terminal闂?
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
		double joinFixedReducedCost = pricingSetupCost(lastJob, backward.jid)
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
		BitSet prefilteredForwardIndices = prefilterIndex == null ? null
				: prefilterIndex.prunedForwardIndices(lastJob, candidates, backward, lp, this);
		for (int i = 0; i < candidates.size() && canContinue();) {
			if (JOIN_PREFILTER_SKIP_RUNS && prefilteredForwardIndices != null
					&& prefilteredForwardIndices.get(i)) {
				int nextCandidate = Math.min(candidates.size(), prefilteredForwardIndices.nextClearBit(i));
				int skipped = nextCandidate - i;
				// 逻辑剪枝数量仍完整计数，但没有读取的 label 不计入实际 visited。
				joinEnvelopePrefilterPotentialPairsPruned += skipped;
				i = nextCandidate;
				continue;
			}
			int candidateIndex = i++;
			ForwardLabel forward = candidates.get(candidateIndex);
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
			if (prefilteredForwardIndices != null && prefilteredForwardIndices.get(candidateIndex)) {
				joinEnvelopePrefilterPotentialPairsPruned++;
				continue;
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
			// 2026-06-09: ng-DSSR 闂佸憡鐟禍鐐哄极?ng-memory 闂佸憡甯囬崐鏍蓟閸ヮ剙绠柛蹇曞帶婢跺秹鏌￠崟闈涚仩闁诡垯鐒﹀璇测攽閸℃鍞ㄩ悷婊呭閹稿憡鏅堕悩鍨闁哄娉曠粻鎾绘煥?
			// 闂佹椿浜為崰搴ㄦ偪閸曨垱鐓傜€广儱鎷嬪Σ濠氱叓閸パ勫殗闁靛棗绻掔划鍨緞鎼达綆妲?reduced-cost route 闂佽鍘归崹褰捤囬弻銉ヨЕ閹肩补鈧櫕娅冮柣鐘辩劍濠㈡绱?cycle闂佹寧绋戦惉濂稿极閵堝棛顩查幖绮瑰墲閸婄數绱撴笟鍥у箺婵炴彃娼″?ng-set闂?
			joinPairsSetPruned++;
			if (targetJoinPair) {
				traceTarget("JOIN_PRUNED ngMemoryIntersect fMem=" + forward.ngMemorySet
						+ " bMem=" + backward.ngMemorySet);
			}
			return;
		}
		int elementaryState = -1;
		if (maintainRouteVisitProfile()) {
			boolean routeVisitsDisjoint = (forward.routeVisitedMask & backward.routeVisitedMask) == 0L
					&& (forward.routeVisitedMaskHigh & backward.routeVisitedMaskHigh) == 0L;
			boolean elementaryPair = forward.routeElementary && backward.routeElementary && routeVisitsDisjoint;
			elementaryState = elementaryPair ? 1 : 0;
			if (elementaryPair) {
				joinKnownElementaryPairs++;
			} else {
				joinKnownNonElementaryPairs++;
			}
		}
		if (elementaryState == 0
				&& shouldPruneNonElementaryWitness(
						forward.minReducedCost + backward.minReducedCost + joinFixedReducedCost, targetJoinPair)) {
			joinNonElementaryWitnessLowerBoundPruned++;
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
		// 2026-05-22: crossing arc (i,r) 闂佹眹鍔岀€氼剙霉閹邦喒鍋?reduced-cost 婵＄偑鍊濋埀顒佺〒閻熸繂霉閻樺弶鍣烘繝鈧?setup cost闂?
		// 闁哄鏅滈敋缂佺儵鍋撴俊鐐€楃划顖涙櫠閹间礁绠冲璺哄瘨閸ゅ鈧鍟崘鈺勫惈 RMP 婵炴垶鎼╅崢鎯р枔閹达附鍤傛慨姗嗗墯閸?arc dual闂佹寧绋掔粙鎴﹀箚娓氣偓瀹?join 婵炴垶鎸搁鍥疾椤愶絽顕辨慨姗嗗厸閻掑﹤螖閸屾冻鍏紒杈ㄧ箞瀵憡鎷呮笟顖欑磽闂佸搫鍟﹢鍦閺夋鐓堕煫鍥ㄦ煥缁旀挳鏌ｉ鍡楁灆缂佲偓鐎ｎ喖绀嗘俊鐐茬毞閸?
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
		if (elementaryState == 0
				&& shouldPruneNonElementaryWitness(reducedCostBound, targetJoinPair)) {
			joinNonElementaryWitnessValuePruned++;
			return;
		}

		if (sequence == null) {
			sequence = recoverJoinSequence(forward, backward);
		}
		if (targetJoinPair) {
			traceTarget("JOIN_KEEP reducedCostBound=" + reducedCostBound);
		}
		Boolean elementaryHint = elementaryState < 0 ? null : Boolean.valueOf(elementaryState == 1);
		tryGenerateColumn(sequence, lp, reducedCostBound, false, elementaryHint);
	}

	/** 已知 pair 必为非基本列时，只保留仍可能进入本轮 top-C witness 池的候选。 */
	private boolean shouldPruneNonElementaryWitness(double lowerBound, boolean targetJoinPair) {
		if (targetJoinPair || config.ngDssrReturnRelaxedColumns || ngDssrTraceRoundRouteRelation
				|| ngDssrDuplicateRepairDiagnostic || nonElementaryNegativeRoutes == null) {
			return false;
		}
		int limit = nonElementaryRouteCandidateLimit();
		if (nonElementaryNegativeRoutes.size() < limit) {
			return false;
		}
		NonElementaryNegativeRoute worst = nonElementaryNegativeRoutes.get(nonElementaryNegativeRoutes.size() - 1);
		return Utility.compareGt(lowerBound, worst.reducedCost);
	}

	/**
	 * 2026-06-13: full-SRI 闂佺粯顭堥崺鏍焵椤戣法顦︽繝鈧鍫濈闁哄洦纰嶅▍鐘绘煙绾版ɑ娅呴柣顐㈢Ч瀵喚鎹勯崫鍕敪闂佹椿鍘归崕鍨閸撗勫珰闁靛鍎辩壕褰掓偣娓氼垰鐏犵紒鍓佸仱瀹曟﹢宕ㄩ弶鎴濆Г闂佸搫瀚烽崹浼村箚娴ｅ壊鍟呴柤纰卞墰閻ュ懘鎮峰▎娆戠暠鐟滄澘鍊挎俊?
	 * 闂佺懓鍢查崥瀣暜閹绢喖绫嶉悹杞拌濞层倝鏌＄€ｎ偆鐭婂☉鏂跨箻瀹曪綁宕橀幓鎺旓紮闁荤姳璀﹂崹鎵閻愬搫瑙﹂柛鏇ㄥ枤椤㈠懘鏌ｅ鐓庢灆缂佹柨鐡ㄧ粙澶愵敂閸愵亞鎲归梺?scope job闂佹寧绋戦懟顖炴偩椤掑嫬鏋?route 闂佸綊娼х粔鐤杺闂佸憡鐟﹂崹宕囩博閺夋垟鏋?SRI闂佹寧绋戦惌鍌氥€掗崜浣瑰暫濞达絽鎲￠煬顒勫级閳哄倻鎳囬柛锝夘棑閹即濡烽妷锔绢槬闂?
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
				// 婵炴垶鎸堕崐鏇炵暦閹版澘瑙﹂柛鏇ㄥ枛濞堟壆鈧鐡曠亸娆戝垝閿熺姴绠ラ柨婵嗙墢缁犳牕鈽夐幘顖氫壕濠电偛妫屽Σ鍕濠靛瑙﹂悘鐐跺亹椤忛亶鏌℃径鍡忓亾瀹曞洦娈梺?route 闂佸憡鐟禍婊呰姳閺屻儱绠ラ柨婵嗗椤忚鲸绻涢崰鈩冨閸?
				shift += dual;
			} else if (forwardCount == 1 && backwardCount == 1
					&& sriHalvesContainDifferentScopeJobs(forward, backward, sriScopes.get(sriIndex))) {
				shift -= dual;
			}
		}
		return shift;
	}

	/**
	 * limited-memory join 闂佸憡鐟禍婵嬎夐崨鏉戣摕?crossing arc 婵炴垶鎸堕崐鎾诲疾閸洖鍙婃い鏍ㄧ閸庡﹪鏌熺捄鐚村伐闁诡喗鎸荤粙澶愬焵?cut 闂?residual half-state 闂佺懓鍢查崥瀣垂濮橆厾鈻旈柍褜鍓欓埢搴ㄦ鐞氭繈鏌涘▎鎰仧闁?
	 * node-memory 闂佹眹鍔岀€氼垳鎹㈠☉姘辩＜妞ゆ挾鍟块崑鎾诡槾闁?backward 婵☆偓绲鹃悧鐐翠繆椤撱垺鍊烽悷娆忓绗戦梺鍛婄啲缁犳垵锕?memory 婵炴垶鎼╅崢铏圭礊鐎ｎ喗鍋濋悽顖ｅ枤楠烆晣rc-memory 闁哄鏅滈敋缂佺儵鍋撴俊鐐€栧Σ鎺椼€呴敃鈧晥?crossing arc 闂侀潻璐熼崝蹇涱敋?cut 闂?memory arcs 婵炴垶鎼╅崢褰掑焵?
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
		joinKnownElementaryPairs = 0;
		joinKnownNonElementaryPairs = 0;
		joinNonElementaryWitnessLowerBoundPruned = 0;
		joinNonElementaryWitnessValuePruned = 0;
		joinRangeLowerBoundChecks = 0;
		joinRangeLowerBoundPruned = 0;
		joinEnvelopeForwardGroups = 0;
		joinEnvelopeBackwardGroups = 0;
		joinEnvelopeForwardLabels = 0;
		joinEnvelopeBackwardLabels = 0;
		joinEnvelopeSegments = 0;
		joinEnvelopeGroupPairs = 0;
		joinEnvelopeGroupPairsPruned = 0;
		joinEnvelopeFunctionEvaluations = 0;
		joinEnvelopePrefilterGroupPairs = 0;
		joinEnvelopePrefilterGroupPairsPruned = 0;
		joinEnvelopePrefilterPotentialPairsPruned = 0;
		joinEnvelopePrefilterFunctionEvaluations = 0;
		joinEnvelopeBuildNanos = 0;
		joinEnvelopeJoinNanos = 0;
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
		resetExtensionStatistics();
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
		midpointProbeReferenceDirection = 0;
		midpointProbeSelectedDirection = 0;
		midpointProbeSelectedForwardMillis = Double.NaN;
		midpointProbeSelectedBackwardMillis = Double.NaN;
		midpointProbeLabelsReadyForJoin = false;
		midpointProbePerformed = false;
		midpointProbeStableFreezeUsed = false;
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
		joinEnvelopeForwardGroups = 0;
		joinEnvelopeBackwardGroups = 0;
		joinEnvelopeForwardLabels = 0;
		joinEnvelopeBackwardLabels = 0;
		joinEnvelopeSegments = 0;
		joinEnvelopeGroupPairs = 0;
		joinEnvelopeGroupPairsPruned = 0;
		joinEnvelopeFunctionEvaluations = 0;
		joinEnvelopeBuildNanos = 0;
		joinEnvelopeJoinNanos = 0;
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
		resetExtensionStatistics();
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

	private void resetExtensionStatistics() {
		forwardExtensionCandidates = 0;
		forwardExtensionArcPruned = 0;
		forwardExtensionInfeasible = 0;
		forwardExtensionConstructed = 0;
		forwardExtensionBoundSurvivors = 0;
		backwardExtensionCandidates = 0;
		backwardExtensionArcPruned = 0;
		backwardExtensionInfeasible = 0;
		backwardExtensionConstructed = 0;
		backwardExtensionBoundSurvivors = 0;
		forwardExtensionArcCheckNanos = 0;
		forwardExtensionBuildNanos = 0;
		forwardExtensionWindowCheckNanos = 0;
		forwardExtensionFunctionNanos = 0;
		forwardExtensionStateNanos = 0;
		forwardExtensionBoundCheckNanos = 0;
		forwardExtensionInsertNanos = 0;
		forwardDominanceGraphInsertNanos = 0;
		forwardExtensionQueueNanos = 0;
		backwardExtensionArcCheckNanos = 0;
		backwardExtensionBuildNanos = 0;
		backwardExtensionWindowCheckNanos = 0;
		backwardExtensionFunctionNanos = 0;
		backwardExtensionStateNanos = 0;
		backwardExtensionBoundCheckNanos = 0;
		backwardExtensionInsertNanos = 0;
		backwardDominanceGraphInsertNanos = 0;
		backwardExtensionQueueNanos = 0;
	}

	private void resetExactPhaseTiming() {
		exactTotalNanos = 0;
		exactInitializeNanos = 0;
		exactInitializeSetupNanos = 0;
		exactInitializeDiagnosticsNanos = 0;
		exactInitializeSriNanos = 0;
		exactInitializeWindowNanos = 0;
		exactInitializeNgNeighborhoodNanos = 0;
		exactInitializeCompletionBoundNanos = 0;
		exactInitializePreCertificateNanos = 0;
		exactInitializeMidpointProbeNanos = 0;
		exactInitializeStateNanos = 0;
		exactInitializeFullMidpointDiagnosticNanos = 0;
		exactBackwardSinkNanos = 0;
		exactForwardExpandNanos = 0;
		exactBackwardExpandNanos = 0;
		exactJoinCompactNanos = 0;
		exactJoinNanos = 0;
		exactFinalizeNanos = 0;
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
		StringBuilder builder = new StringBuilder(2048);
		builder.append("labels fw kept/dominated=").append(forwardLabelsKept).append("/")
				.append(forwardLabelsDominated);
		builder.append(", bw kept/dominated=").append(backwardLabelsKept).append("/")
				.append(backwardLabelsDominated);
		builder.append(", exactPhaseMs total/init/sink/fw/bw/compact/join/finalize=")
				.append(formatMillis(exactTotalNanos)).append("/")
				.append(formatMillis(exactInitializeNanos)).append("/")
				.append(formatMillis(exactBackwardSinkNanos)).append("/")
				.append(formatMillis(exactForwardExpandNanos)).append("/")
				.append(formatMillis(exactBackwardExpandNanos)).append("/")
				.append(formatMillis(exactJoinCompactNanos)).append("/")
				.append(formatMillis(exactJoinNanos)).append("/")
				.append(formatMillis(exactFinalizeNanos));
		builder.append(", exactInitDetailMs setup/diag/sri/window/ng/cb/preCert/probe/state/fullProbe=")
				.append(formatMillis(exactInitializeSetupNanos)).append("/")
				.append(formatMillis(exactInitializeDiagnosticsNanos)).append("/")
				.append(formatMillis(exactInitializeSriNanos)).append("/")
				.append(formatMillis(exactInitializeWindowNanos)).append("/")
				.append(formatMillis(exactInitializeNgNeighborhoodNanos)).append("/")
				.append(formatMillis(exactInitializeCompletionBoundNanos)).append("/")
				.append(formatMillis(exactInitializePreCertificateNanos)).append("/")
				.append(formatMillis(exactInitializeMidpointProbeNanos)).append("/")
				.append(formatMillis(exactInitializeStateNanos)).append("/")
				.append(formatMillis(exactInitializeFullMidpointDiagnosticNanos));
		if (config.ngDssrExtensionTimingDiagnostics) {
			builder.append(", extensionTimingMs fw arc/build/window/function/state/cb/insert/domGraph/queue=")
					.append(formatMillis(forwardExtensionArcCheckNanos)).append("/")
					.append(formatMillis(forwardExtensionBuildNanos)).append("/")
					.append(formatMillis(forwardExtensionWindowCheckNanos)).append("/")
					.append(formatMillis(forwardExtensionFunctionNanos)).append("/")
					.append(formatMillis(forwardExtensionStateNanos)).append("/")
					.append(formatMillis(forwardExtensionBoundCheckNanos)).append("/")
					.append(formatMillis(forwardExtensionInsertNanos)).append("/")
					.append(formatMillis(forwardDominanceGraphInsertNanos)).append("/")
					.append(formatMillis(forwardExtensionQueueNanos));
			builder.append(", extensionTimingMs bw arc/build/window/function/state/cb/insert/domGraph/queue=")
					.append(formatMillis(backwardExtensionArcCheckNanos)).append("/")
					.append(formatMillis(backwardExtensionBuildNanos)).append("/")
					.append(formatMillis(backwardExtensionWindowCheckNanos)).append("/")
					.append(formatMillis(backwardExtensionFunctionNanos)).append("/")
					.append(formatMillis(backwardExtensionStateNanos)).append("/")
					.append(formatMillis(backwardExtensionBoundCheckNanos)).append("/")
					.append(formatMillis(backwardExtensionInsertNanos)).append("/")
					.append(formatMillis(backwardDominanceGraphInsertNanos)).append("/")
					.append(formatMillis(backwardExtensionQueueNanos));
		}
		builder.append(", halfWindowIneligible fw/bw=").append(forwardHalfIneligibleJobCount).append("/")
				.append(backwardHalfIneligibleJobCount);
		builder.append(", singlePoint fw kept/storeDom/graphDom=").append(forwardSinglePointKept).append("/")
				.append(forwardSinglePointDominatedByStore).append("/").append(forwardSinglePointDominatedByGraph);
		builder.append(", bw kept/storeDom/graphDom=").append(backwardSinglePointKept).append("/")
				.append(backwardSinglePointDominatedByStore).append("/").append(backwardSinglePointDominatedByGraph);
		builder.append(", join groups scanned/arcOrVisit/timeLB/costLB=").append(joinTerminalGroupsScanned)
				.append("/").append(joinTerminalGroupsArcOrVisitPruned).append("/")
				.append(joinTerminalGroupsTimePruned).append("/").append(joinTerminalGroupsCostPruned);
		builder.append(", join candidates visited/dominated=").append(joinCandidateLabelsVisited).append("/")
				.append(joinCandidateLabelsDominated);
		builder.append(", join pairs tried/set/lb/time/funcEval/funcPruned=").append(joinPairsTried).append("/")
				.append(joinPairsSetPruned).append("/").append(joinPairsLowerBoundPruned).append("/")
				.append(joinPairsTimePruned).append("/").append(joinFunctionEvaluations).append("/")
				.append(joinFunctionPruned);
		builder.append(", joinVisitProfile elementary/nonElementary/lbPruned/valuePruned=")
				.append(joinKnownElementaryPairs).append("/").append(joinKnownNonElementaryPairs).append("/")
				.append(joinNonElementaryWitnessLowerBoundPruned).append("/")
				.append(joinNonElementaryWitnessValuePruned);
		builder.append(", joinRangeLB check/pruned=").append(joinRangeLowerBoundChecks).append("/")
				.append(joinRangeLowerBoundPruned);
		if (config.enableNgDssrJoinEnvelopeCompression) {
			builder.append(", joinEnvelope fGrp/bGrp/fLbl/bLbl/seg/gPair/pruned/funcEval=")
					.append(joinEnvelopeForwardGroups).append("/").append(joinEnvelopeBackwardGroups).append("/")
					.append(joinEnvelopeForwardLabels).append("/").append(joinEnvelopeBackwardLabels).append("/")
					.append(joinEnvelopeSegments).append("/").append(joinEnvelopeGroupPairs).append("/")
					.append(joinEnvelopeGroupPairsPruned).append("/").append(joinEnvelopeFunctionEvaluations);
			builder.append(", joinEnvelopeMs build/join=")
					.append(String.format("%.3f", joinEnvelopeBuildNanos / 1_000_000.0)).append("/")
					.append(String.format("%.3f", joinEnvelopeJoinNanos / 1_000_000.0));
		}
		if (useJoinEnvelopePrefilter()) {
			builder.append(", joinEnvelopePrefilter groups/pruned/skippedPairs/funcEval/buildMs/joinMs=")
					.append(joinEnvelopePrefilterGroupPairs).append("/")
					.append(joinEnvelopePrefilterGroupPairsPruned).append("/")
					.append(joinEnvelopePrefilterPotentialPairsPruned).append("/")
					.append(joinEnvelopePrefilterFunctionEvaluations).append("/")
					.append(String.format("%.3f", joinEnvelopeBuildNanos / 1_000_000.0)).append("/")
					.append(String.format("%.3f", joinEnvelopeJoinNanos / 1_000_000.0));
		}
		builder.append(", joinBest mode/bestRC/lbPruned/recordPruned=").append(joinBestThresholdMode).append("/")
				.append(bestGeneratedReducedCost).append("/").append(joinPairsBestBoundPruned).append("/")
				.append(joinFunctionBestRecordPruned);
		builder.append(", completionBound mode/cutoff/buildMs/eval/fwPruned/bwPruned=")
				.append(completionBoundRelaxationForSummary()).append("/").append(completionBoundCutoffForSummary())
				.append("/").append(formatMillis(completionBoundBuildNanos)).append("/")
				.append(completionBoundFunctionEvaluations).append("/").append(completionForwardLabelsPruned)
				.append("/").append(completionBackwardLabelsPruned);
		builder.append(", completionBoundScalar check/pruned/fallback/unavailable=")
				.append(completionBoundScalarChecks).append("/").append(completionBoundScalarPruned).append("/")
				.append(completionBoundScalarFunctionFallbacks).append("/").append(completionBoundScalarUnavailable);
		builder.append(", timeIndexedScalar buildMs/improved/extraPruned/unavailable/windowTightenedReachable=")
				.append(formatMillis(timeIndexedScalarBuildNanos)).append("/")
				.append(timeIndexedScalarImprovedChecks).append("/").append(timeIndexedScalarExtraPruned).append("/")
				.append(timeIndexedScalarUnavailable).append("/").append(timeIndexedWindowTightenedJobs).append("-")
				.append(timeIndexedWindowReachableJobs);
		builder.append(", completionBoundArcFixing candidates/fixed/domain/scalar/unavailable/funcEval/ms=")
				.append(completionBoundArcFixingCandidates).append("/").append(completionBoundArcFixingFixed)
				.append("/").append(completionBoundArcFixingDomainPruned).append("/")
				.append(completionBoundArcFixingScalarPruned).append("/")
				.append(completionBoundArcFixingUnavailable).append("/")
				.append(completionBoundArcFixingFunctionEvaluations).append("/")
				.append(formatMillis(completionBoundArcFixingNanos));
		builder.append(", forwardSink visited/negative=").append(forwardSinkLabelsVisited).append("/")
				.append(forwardSinkNegativeCandidates);
		builder.append(", forwardExtend candidates/arcPruned/infeasible/constructed/boundSurvivors=")
				.append(forwardExtensionCandidates).append("/").append(forwardExtensionArcPruned).append("/")
				.append(forwardExtensionInfeasible).append("/").append(forwardExtensionConstructed).append("/")
				.append(forwardExtensionBoundSurvivors);
		builder.append(", backwardExtend candidates/arcPruned/infeasible/constructed/boundSurvivors=")
				.append(backwardExtensionCandidates).append("/").append(backwardExtensionArcPruned).append("/")
				.append(backwardExtensionInfeasible).append("/").append(backwardExtensionConstructed).append("/")
				.append(backwardExtensionBoundSurvivors);
		builder.append(", forwardDepth kept/negSink=").append(formatDepthHistogram(forwardLabelsKeptByDepth))
				.append("/").append(formatDepthHistogram(forwardSinkNegativeByDepth));
		builder.append(", forwardReach kept avg/min/max=")
				.append(formatAverage(forwardLabelsKeptReachableSum, forwardLabelsKept)).append("/")
				.append(formatReachableMin()).append("/").append(forwardLabelsKeptReachableMax);
		builder.append(nodeDiagnosticsSummary());
		builder.append(", completionBoundQueue=").append(completionBoundQueueOrdering);
		builder.append(", completionBoundInternal timingMs fw/bw/agg=")
				.append(formatMillis(completionBoundForwardBuildNanos)).append("/")
				.append(formatMillis(completionBoundBackwardBuildNanos)).append("/")
				.append(formatMillis(completionBoundAggregateNanos));
		builder.append(", completionBoundInternal counts fCand/bCand/fPop/bPop/stale/merge/changed=")
				.append(completionBoundForwardCandidateAttempts).append("/")
				.append(completionBoundBackwardCandidateAttempts).append("/").append(completionBoundForwardQueuePops)
				.append("/").append(completionBoundBackwardQueuePops).append("/")
				.append(completionBoundPriorityQueueStalePops).append("/").append(completionBoundMergeCalls)
				.append("/").append(completionBoundMergeChanged);
		builder.append(", completionBoundSegments fwSamples/targetAvg/candAvg/afterAvg/maxTCA=")
				.append(completionBoundForwardSegmentSamples).append("/")
				.append(formatAverage(completionBoundForwardTargetSegments, completionBoundForwardSegmentSamples))
				.append("/")
				.append(formatAverage(completionBoundForwardCandidateSegments, completionBoundForwardSegmentSamples))
				.append("/")
				.append(formatAverage(completionBoundForwardAfterSegments, completionBoundForwardSegmentSamples))
				.append("/").append(completionBoundForwardMaxTargetSegments).append("-")
				.append(completionBoundForwardMaxCandidateSegments).append("-")
				.append(completionBoundForwardMaxAfterSegments);
		builder.append(", completionBoundSegments bwSamples/targetAvg/candAvg/afterAvg/maxTCA=")
				.append(completionBoundBackwardSegmentSamples).append("/")
				.append(formatAverage(completionBoundBackwardTargetSegments, completionBoundBackwardSegmentSamples))
				.append("/")
				.append(formatAverage(completionBoundBackwardCandidateSegments, completionBoundBackwardSegmentSamples))
				.append("/")
				.append(formatAverage(completionBoundBackwardAfterSegments, completionBoundBackwardSegmentSamples))
				.append("/").append(completionBoundBackwardMaxTargetSegments).append("-")
				.append(completionBoundBackwardMaxCandidateSegments).append("-")
				.append(completionBoundBackwardMaxAfterSegments);
		builder.append(", candidatePool kept/seen/dropped=").append(generatedCandidateBySignature.size()).append("/")
                .append(generatedCandidateCount).append("/").append(generatedCandidateDroppedByHeap);
		builder.append(", queueOrdering=").append(queueOrdering);
		builder.append(", dynamicHStartMin=").append(dynamicMinHStart).append(", dynamicHEndMax=")
				.append(dynamicMaxHEnd);
		builder.append(", earliestSourceCompletion=").append(earliestSourceCompletion);
		builder.append(", pricingHorizon=").append(pricingHorizon).append(", tMid=").append(tMid);
		builder.append(", midpointStrategy/ref/ms=").append(midpointStrategyUsed).append("/")
				.append(midpointReferenceTime).append("/").append(formatMillis(midpointStrategyNanos));
		builder.append(", midpointColumns count/lastMinAvgMax/halfMinAvgMax=")
				.append(midpointColumnSelectedCount).append("/").append(midpointColumnLastMin).append("/")
				.append(midpointColumnLastAvg).append("/").append(midpointColumnLastMax).append("/")
				.append(midpointColumnHalfMin).append("/").append(midpointColumnHalfAvg).append("/")
				.append(midpointColumnHalfMax);
		builder.append(", midpointColumnTasks count/minAvgMedianMax=").append(midpointColumnTaskSampleCount)
				.append("/").append(midpointColumnTaskMin).append("/").append(midpointColumnTaskAvg).append("/")
				.append(midpointColumnTaskMedian).append("/").append(midpointColumnTaskMax);
		builder.append(", midpointProbe=").append(midpointProbeSummary);
		builder.append(", midpointProbeFeedback=").append(midpointProbeFeedbackSummary);
		builder.append(targetTraceSummary());
		builder.append(", zeroDualExcludedJobs=").append(zeroDualExcludedJobCount);
		builder.append(", piWindow=").append(dualProfitableWindowEnabled ? "enabled" : "disabled");
		builder.append(", ").append(dominanceStatisticsSummary());
		return builder.toString();
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
		// 2026-06-01: completion bound 闂佸憡鐟禍婊堝垂娴犲妫?label 闂佸搫瀚烽崹浼村箚娴ｈ浜ゆ俊顖氱仢閸樻挳鎮跺☉鏍у姕闁搞劍姘ㄩ幏褰掓偄瀹勬壆浠氶梺?
		// 婵炴垶鎸哥粔铏箾閸ヮ剚鍋ㄩ柕濞垮劤缁夊ジ鏌?best reduced cost闂佹寧绋戦惌鍌涘閳哄懎绀傜€广儱鎳庣紞渚€鏌?record-only 闂佸憡鎼╂禍婵嬫倶婵犲洨宓侀柡鍫濈仛娑撱垽鏌?top-K 闁荤姵鍔楅崰搴ㄥ垂椤忓牆违?
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
	 * 2026-06-09: 闁荤姴娲ら敃銉╁蓟?required adjacency dual 闂佸搫瀚烽崹浼村箚娓氣偓楠?relaxed suffix 婵炴垶鎸搁鍥疾椤愶箑鍌ㄩ悗锝庝簽缁讳線寮堕埡浣圭煑缂傚秴妫濇俊?
	 * 闂佸憡鐟禍婵堚偓鍨矌閸栨牠鎳￠妶鍥х厷闁诲繒鍋熼崑鐐哄焵椤戭剙瀚埢鏃傗偓娈垮枛缁绘垹鈧灚姘ㄩ埀顒冾潐娣囩儤淇婇銏″€烽悷娆忓椤ρ囧级閸喐灏柛銈庡弮閺佸秶浠﹂懖鈺冩喒閻熸粍婢樺畷顒勫箹瀹勯偊娼伴柨婵嗘噽绾偓 pricing 闁荤姴娴傞崢铏圭不閻旂厧违?
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
				double fixedReducedCost = pricingSetupCost(fromJob, toJob) - lp.getArcDual(fromJob, toJob);
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

	/**
	 * 2026-07-13: 一个 DSSR solve 内 node、pricing-only 禁弧和 completion-bound 固定弧保持不变，
	 * 因而一次性编译成 BitSet。后续每个 label 只做一次按 word 取交集，不再逐弧查询多层集合。
	 */
	private void ensureExtensionArcMasks(Node node) {
		if (forwardExtensionArcMaskByFrom != null && backwardExtensionArcMaskBySuccessor != null) {
			return;
		}
		int universeSize = data.n + 2;
		reachabilityCandidateJobs = new PackedBitSet(universeSize);
		for (int job = 1; job <= data.n; job++) {
			if (!isZeroDualExcludedJob(job) && !PricingCompatibility.isRequiredOutsourcedJob(node, job)) {
				reachabilityCandidateJobs.add(job);
			}
		}
		forwardExtensionArcMaskByFrom = new PackedBitSet[universeSize];
		for (int fromJob = 0; fromJob <= data.n; fromJob++) {
			PackedBitSet allowed = new PackedBitSet(universeSize);
			for (int toJob = reachabilityCandidateJobs.nextSetBit(1); toJob > 0 && toJob <= data.n;
					toJob = reachabilityCandidateJobs.nextSetBit(toJob + 1)) {
				if (!isPricingArcForbidden(node, fromJob, toJob)) {
					allowed.add(toJob);
				}
			}
			forwardExtensionArcMaskByFrom[fromJob] = allowed;
		}

		backwardExtensionArcMaskBySuccessor = new PackedBitSet[universeSize];
		for (int successor = 1; successor <= node.sinkId(); successor++) {
			PackedBitSet allowed = new PackedBitSet(universeSize);
			for (int fromJob = reachabilityCandidateJobs.nextSetBit(1); fromJob > 0 && fromJob <= data.n;
					fromJob = reachabilityCandidateJobs.nextSetBit(fromJob + 1)) {
				if (!isPricingArcForbidden(node, fromJob, successor)) {
					allowed.add(fromJob);
				}
			}
			backwardExtensionArcMaskBySuccessor[successor] = allowed;
		}
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

	private boolean isForwardCompletionBoundPruned(int job, PiecewiseLinearFunction noSriFrontier,
			double noSriMinReducedCost) {
		if (completionBounds == null || job <= 0 || job > data.n || noSriFrontier == null
				|| noSriFrontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction suffix = completionBounds.backwardRByJob[job];
		if (suffix == null || suffix.head == null) {
			return false;
		}
		double cutoff = completionBoundCutoff();
		completionBoundLastEvaluationCutoff = cutoff;
		if (config.bidirectionalCompletionBoundScalarPruning
				&& isForwardCompletionBoundScalarPruned(job, noSriFrontier, noSriMinReducedCost, cutoff)) {
			return true;
		}
		completionBoundFunctionEvaluations++;
		// 2026-06-13: under active SRI, this completion-bound pruning uses no-SRI frontier.
		if (!hasCommonCompletionDomain(noSriFrontier, suffix)) {
			return false;
		}
		double lowerBound = completionBoundFlatFunctionQuery
				? PiecewiseLinearFunction.findMinimalSumValue(noSriFrontier,
						completionBounds.backwardRView(job), 0.0)
				: PiecewiseLinearFunction.findMinimalSumValue(noSriFrontier, suffix, 0.0);
		return !Utility.compareLt(lowerBound, cutoff);
	}

	private boolean isBackwardCompletionBoundPruned(int job, boolean isSinkRoot,
			PiecewiseLinearFunction noSriFrontier, double noSriMinReducedCost) {
		if (completionBounds == null || isSinkRoot || job <= 0 || job > data.n
				|| noSriFrontier == null || noSriFrontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction prefix = completionBounds.forwardUByJob[job];
		if (prefix == null || prefix.head == null) {
			return false;
		}
		double cutoff = completionBoundCutoff();
		completionBoundLastEvaluationCutoff = cutoff;
		if (config.bidirectionalCompletionBoundScalarPruning
				&& isBackwardCompletionBoundScalarPruned(job, noSriFrontier, noSriMinReducedCost, cutoff)) {
			return true;
		}
		completionBoundFunctionEvaluations++;
		// 2026-06-13: symmetric to forward pruning, use no-SRI frontier here.
		if (!hasCommonCompletionDomain(prefix, noSriFrontier)) {
			return false;
		}
		// 纯函数求和可交换；动态 label 保持链表，固定 prefix 复用数组视图。
		double lowerBound = completionBoundFlatFunctionQuery
				? PiecewiseLinearFunction.findMinimalSumValue(noSriFrontier,
						completionBounds.forwardUView(job), 0.0)
				: PiecewiseLinearFunction.findMinimalSumValue(prefix, noSriFrontier, 0.0);
		return !Utility.compareLt(lowerBound, cutoff);
	}


	private boolean hasCommonCompletionDomain(PiecewiseLinearFunction left, PiecewiseLinearFunction right) {
		double start = Math.max(left.head.start, right.head.start);
		double end = Math.min(left.tail.end, right.tail.end);
		return !Utility.compareLt(end, start);
	}

	private boolean isForwardCompletionBoundScalarPruned(int job, PiecewiseLinearFunction noSriFrontier,
			double noSriMinReducedCost, double cutoff) {
		completionBoundScalarChecks++;
		double suffixLowerBound = completionBounds.backwardRAfterFloor(job, noSriFrontier.head.start);
		if (Utility.isBigMValue(suffixLowerBound)) {
			completionBoundScalarUnavailable++;
			completionBoundScalarPruned++;
			return true;
		}
		double originalScalarLowerBound = noSriMinReducedCost + suffixLowerBound;
		double timeIndexedSuffix = timeIndexedScalarBound == null ? Utility.big_M
				: timeIndexedScalarBound.suffixLowerBoundAfterFloor(job, noSriFrontier.head.start);
		if (!Utility.isBigMValue(timeIndexedSuffix) && Utility.compareGt(timeIndexedSuffix, suffixLowerBound)) {
			suffixLowerBound = timeIndexedSuffix;
			timeIndexedScalarImprovedChecks++;
		} else if (timeIndexedScalarBound != null && Utility.isBigMValue(timeIndexedSuffix)) {
			timeIndexedScalarUnavailable++;
		}
		double scalarLowerBound = noSriMinReducedCost + suffixLowerBound;
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

	private boolean isBackwardCompletionBoundScalarPruned(int job, PiecewiseLinearFunction noSriFrontier,
			double noSriMinReducedCost, double cutoff) {
		completionBoundScalarChecks++;
		double prefixLowerBound = isAtPricingHorizon(noSriFrontier.tail.end)
				? completionBounds.forwardUMin(job)
				: completionBounds.forwardUBeforeCeil(job, noSriFrontier.tail.end);
		if (Utility.isBigMValue(prefixLowerBound)) {
			completionBoundScalarUnavailable++;
			completionBoundScalarPruned++;
			return true;
		}
		double originalScalarLowerBound = noSriMinReducedCost + prefixLowerBound;
		double timeIndexedPrefix = timeIndexedScalarBound == null ? Utility.big_M
				: timeIndexedScalarBound.prefixLowerBoundBeforeCeil(job, noSriFrontier.tail.end);
		if (!Utility.isBigMValue(timeIndexedPrefix) && Utility.compareGt(timeIndexedPrefix, prefixLowerBound)) {
			prefixLowerBound = timeIndexedPrefix;
			timeIndexedScalarImprovedChecks++;
		} else if (timeIndexedScalarBound != null && Utility.isBigMValue(timeIndexedPrefix)) {
			timeIndexedScalarUnavailable++;
		}
		double scalarLowerBound = noSriMinReducedCost + prefixLowerBound;
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
	 * 2026-05-23: join 闂佸憡鎸哥粔铏緞瀹ュ绫嶉柤鎼佹涧鎯?forward 闂佸憡顨呴敃銈夋偂濞嗘挸鐭楅柛蹇撴噺濞呯姷鈧偣鍊涢崺鏍偓姘处缁?f(Tmid)闂?
	 * 闁哄鏅滈悷锕€危閸濄儲濯奸柣鎴炆戦悗顕€鎮楅崷顓炰户妤犵偛娲弻灞界暆閳ь剙鈻?join 闁哄鐗嗛幊搴㈡叏椤忓牆绀勯柤鎭掑劜濞堝爼鏌ㄥ☉妯侯殭缂佹顦靛畷妯衡枎韫囨梻顦?label闂?
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
	 * 2026-05-23: join 闂佸憡鎸哥粔铏緞瀹ュ绫嶉柤鎼佹涧鎯?backward 闂佸憡顨呴敃銈夋偂濞嗗浚鍟呴柨鏃€瀵у▍鐘碘偓鐐瑰€涢崺鏍偓姘处缁?f_b(Tmid)闂?
	 * 闁哄鏅滈悷锕€危閸濄儲濯奸柣鎴炆戦悗顕€鎮楅崷顓炰户妤犵偛娲弻灞界暆閳ь剙鈻?join 闁哄鐗嗛幊搴㈡叏椤忓牆绀勯柤鎭掑劜濞堝爼鏌ㄥ☉妯侯殭缂佹顦靛畷妯衡枎韫囨梻顦?label闂?
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
		tryGenerateColumn(sequence, lp, inferredReducedCost, false, null);
	}

	private void tryGenerateColumn(ArrayList<Integer> sequence, LP lp, double inferredReducedCost,
			boolean forceTrueCost) {
		tryGenerateColumn(sequence, lp, inferredReducedCost, forceTrueCost, null);
	}

	private void tryGenerateColumn(ArrayList<Integer> sequence, LP lp, double inferredReducedCost,
			boolean forceTrueCost, Boolean elementaryHint) {
		observeRelaxedReducedCost(inferredReducedCost);
		if (sequence.isEmpty() || config.maxExactPricingColumns <= 0) {
			return;
		}
		boolean targetSequence = isTargetSequence(sequence);
		boolean elementary = elementaryHint == null ? isElementarySequence(sequence) : elementaryHint.booleanValue();
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
		double candidateReducedCost = inferredReducedCost;
		TWETColumn candidateColumn = null;
		if (forceTrueCost) {
			double checkedCost = evaluator.evaluate(sequence);
			if (Utility.isBigMValue(checkedCost)) {
				if (targetSequence) {
					traceTarget("COLUMN_REJECT trueCostBigM inferredRC=" + inferredReducedCost);
				}
				return;
			}
			candidateColumn = new TWETColumn(-1, sequence, data.n, checkedCost, ColumnSource.PRICING_EXACT, false);
			candidateReducedCost = computeCurrentPricingReducedCost(candidateColumn, lp);
		}
		if (Utility.compareLt(candidateReducedCost, REDUCED_COST_TOLERANCE)) {
			if (joinBestThresholdMode == JoinBestThresholdMode.BEST_RECORD
					&& !Utility.compareLt(candidateReducedCost, joinLowerBoundThreshold())) {
				generatedCandidateDroppedByHeap++;
				if (targetSequence) {
					traceTarget("COLUMN_REJECT bestRecordThreshold inferredRC=" + inferredReducedCost
							+ " candidateRC=" + candidateReducedCost);
				}
				return;
			}
			if (targetSequence) {
				traceTarget("COLUMN_CANDIDATE inferredRC=" + inferredReducedCost + " candidateRC="
						+ candidateReducedCost);
			}
			if (candidateColumn == null) {
				candidateColumn = PricingColumnCostRechecker.buildInferredColumn(sequence,
						inferredReducedCost, lp, data, ColumnSource.PRICING_EXACT);
			}
			rememberGeneratedCandidate(signature, candidateColumn, candidateReducedCost);
		}
	}

	private double computeCurrentPricingReducedCost(TWETColumn column, LP lp) {
		double reducedCost = (feasibilityPhaseOneObjectiveMode ? 0.0 : column.getCost()) - lp.getMachineDual();
		int prev = 0;
		for (int job : column.getSequence()) {
			reducedCost -= lp.getJobDual(job);
			reducedCost -= lp.getArcDual(prev, job);
			prev = job;
		}
		reducedCost -= lp.getArcDual(prev, lp.getNode().sinkId());
		return reducedCost;
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
		recordRoundNegativeRoute(sequence, inferredReducedCost);
		rememberDuplicateRepairCandidate(sequence, inferredReducedCost);
		ngDssrRoundNonElementaryNegativeSeen++;
		int limit = nonElementaryRouteCandidateLimit();
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
				return compareNonElementaryNegativeRoutes(left, right);
			}
		});
	}

	private void recordRoundNegativeRoute(ArrayList<Integer> sequence, double reducedCost) {
		if (ngDssrCurrentRoundNegativeRoutes == null) {
			return;
		}
		SequenceSignature signature = new SequenceSignature(sequence);
		NonElementaryNegativeRoute previous = ngDssrCurrentRoundNegativeRoutes.get(signature);
		if (previous == null || Utility.compareLt(reducedCost, previous.reducedCost)) {
			ngDssrCurrentRoundNegativeRoutes.put(signature,
					new NonElementaryNegativeRoute(sequence, reducedCost));
		}
	}

	private void rememberDuplicateRepairCandidate(ArrayList<Integer> sequence, double reducedCost) {
		if (ngDssrDuplicateRepairCandidates == null) {
			return;
		}
		for (int i = 0; i < ngDssrDuplicateRepairCandidates.size(); i++) {
			NonElementaryNegativeRoute existing = ngDssrDuplicateRepairCandidates.get(i);
			if (existing.sequence.equals(sequence)) {
				if (Utility.compareLt(reducedCost, existing.reducedCost)) {
					ngDssrDuplicateRepairCandidates.set(i, new NonElementaryNegativeRoute(sequence, reducedCost));
				}
				return;
			}
		}
		if (ngDssrDuplicateRepairCandidates.size() < DUPLICATE_REPAIR_DIAGNOSTIC_ROUTE_LIMIT) {
			ngDssrDuplicateRepairCandidates.add(new NonElementaryNegativeRoute(sequence, reducedCost));
			return;
		}
		int worstIndex = 0;
		for (int i = 1; i < ngDssrDuplicateRepairCandidates.size(); i++) {
			if (compareNonElementaryNegativeRoutes(ngDssrDuplicateRepairCandidates.get(worstIndex),
					ngDssrDuplicateRepairCandidates.get(i)) < 0) {
				worstIndex = i;
			}
		}
		NonElementaryNegativeRoute worst = ngDssrDuplicateRepairCandidates.get(worstIndex);
		if (compareDoubleAsc(reducedCost, worst.reducedCost) > 0) {
			return;
		}
		NonElementaryNegativeRoute candidate = new NonElementaryNegativeRoute(sequence, reducedCost);
		if (compareNonElementaryNegativeRoutes(candidate, worst) < 0) {
			ngDssrDuplicateRepairCandidates.set(worstIndex, candidate);
		}
	}

	/**
	 * 2026-07-13: 只诊断“删除重复访问后能否得到负基本列”。每一步枚举可删除的重复 occurrence，
	 * 用真实 evaluator 选择 reduced cost 最低的删法；结果不进入候选池，也不改变 ng-set。
	 */
	private void diagnoseDuplicateRepairs(LP lp) {
		if (!ngDssrDuplicateRepairDiagnostic || ngDssrDuplicateRepairCandidates == null
				|| ngDssrDuplicateRepairSummary == null) {
			return;
		}
		long start = System.nanoTime();
		Collections.sort(ngDssrDuplicateRepairCandidates, this::compareNonElementaryNegativeRoutes);
		LP.PricingDualSnapshot trueDuals = lp.captureTruePricingDuals();
		long callsBefore = ngDssrDuplicateRepairEvaluatorCalls;
		int attempted = ngDssrDuplicateRepairCandidates.size();
		int feasible = 0;
		int negative = 0;
		int additional = 0;
		double bestOriginalRc = Double.POSITIVE_INFINITY;
		double bestRepairRc = Double.POSITIVE_INFINITY;
		HashSet<SequenceSignature> additionalSignatures = new HashSet<SequenceSignature>();
		for (NonElementaryNegativeRoute route : ngDssrDuplicateRepairCandidates) {
			bestOriginalRc = Math.min(bestOriginalRc, route.reducedCost);
			DuplicateRepairResult repaired = greedilyRepairDuplicateVisits(route.sequence, lp, trueDuals);
			if (repaired == null) {
				continue;
			}
			feasible++;
			bestRepairRc = Math.min(bestRepairRc, repaired.reducedCost);
			if (!Utility.compareLt(repaired.reducedCost, REDUCED_COST_TOLERANCE)) {
				continue;
			}
			negative++;
			SequenceSignature signature = new SequenceSignature(repaired.sequence);
			boolean alreadyGenerated = generatedCandidateBySignature != null
					&& generatedCandidateBySignature.containsKey(signature);
			if (!activeColumnSignatures.contains(signature) && !alreadyGenerated
					&& additionalSignatures.add(signature)) {
				additional++;
			}
		}
		long elapsed = System.nanoTime() - start;
		ngDssrDuplicateRepairNanos += elapsed;
		ngDssrDuplicateRepairAttempted += attempted;
		ngDssrDuplicateRepairFeasible += feasible;
		ngDssrDuplicateRepairNegative += negative;
		ngDssrDuplicateRepairAdditional += additional;
		if (ngDssrDuplicateRepairSummary.length() > 0) {
			ngDssrDuplicateRepairSummary.append(';');
		}
		ngDssrDuplicateRepairSummary.append('r').append(ngDssrRound)
				.append("={try").append(attempted)
				.append("/feas").append(feasible)
				.append("/neg").append(negative)
				.append("/add").append(additional)
				.append("/eval").append(ngDssrDuplicateRepairEvaluatorCalls - callsBefore)
				.append("/origRc").append(finiteOrNa(bestOriginalRc))
				.append("/repairRc").append(finiteOrNa(bestRepairRc))
				.append("/ms").append(String.format(Locale.US, "%.3f", elapsed / 1_000_000.0))
				.append('}');
	}

	private DuplicateRepairResult greedilyRepairDuplicateVisits(ArrayList<Integer> sequence, LP lp,
			LP.PricingDualSnapshot trueDuals) {
		ArrayList<Integer> current = new ArrayList<Integer>(sequence);
		double currentReducedCost = Double.POSITIVE_INFINITY;
		while (!isElementarySequence(current)) {
			int[] occurrences = new int[data.n + 1];
			for (int job : current) {
				if (job > 0 && job <= data.n) {
					occurrences[job]++;
				}
			}
			ArrayList<Integer> bestSequence = null;
			double bestReducedCost = Double.POSITIVE_INFINITY;
			for (int position = 0; position < current.size(); position++) {
				int job = current.get(position).intValue();
				if (job <= 0 || job > data.n || occurrences[job] <= 1) {
					continue;
				}
				ArrayList<Integer> trial = new ArrayList<Integer>(current);
				trial.remove(position);
				if (!isSequenceCompatible(trial, lp.getNode())) {
					continue;
				}
				double cost = evaluator.evaluate(trial);
				ngDssrDuplicateRepairEvaluatorCalls++;
				if (Utility.isBigMValue(cost)) {
					continue;
				}
				double reducedCost = computeDiagnosticSequenceReducedCost(trial, cost, lp, trueDuals);
				if (bestSequence == null || Utility.compareLt(reducedCost, bestReducedCost)) {
					bestSequence = trial;
					bestReducedCost = reducedCost;
				}
			}
			if (bestSequence == null) {
				return null;
			}
			current = bestSequence;
			currentReducedCost = bestReducedCost;
		}
		return new DuplicateRepairResult(current, currentReducedCost);
	}

	private double computeDiagnosticSequenceReducedCost(ArrayList<Integer> sequence, double cost, LP lp,
			LP.PricingDualSnapshot trueDuals) {
		TWETColumn column = new TWETColumn(-1, sequence, data.n, cost, ColumnSource.PRICING_EXACT, false);
		return lp.computeReducedCost(column, trueDuals);
	}

	private String finiteOrNa(double value) {
		return Double.isFinite(value) ? Double.toString(value) : "NA";
	}

	private int compareNonElementaryNegativeRoutes(NonElementaryNegativeRoute left,
			NonElementaryNegativeRoute right) {
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
		// 2026-06-16: 闂佸憡鑹炬總鏃傜博?sequence 闂佸憡鐟崹鎶藉极鏉堛劌绶炴慨姗嗗亰閸?split 闂佹眹鍨婚崰鎰板垂濮樿埖鏅繛鎴炵懄閿涘鏌涙繝鍐噰闁逞屽墮椤﹂亶寮抽埀顒勬煕閿斿搫濡奸柣鏍ュ灪缁嬪顢橀妸褏顦甿ap 闂佸憡鐟禍娆戞崲濮樿埖鍋╂繛鍡楃箳缁夊ジ鏌涢幘宕囆ｆ繛鎾瑰煐鐎电厧螣鐞涒€充壕婵炲棙鎸撮崑鎾村緞閸艾浜?
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

	private void maybeAuditAlternativeJoin(LP lp) {
		boolean auditEnvelope = Boolean.getBoolean("twet.bpc.ngDssrAuditEnvelopeAfterStandard")
				&& !useJoinEnvelopeCompression();
		boolean auditStandard = Boolean.getBoolean("twet.bpc.ngDssrAuditStandardAfterEnvelope")
				&& useJoinEnvelopeCompression();
		if (!auditEnvelope && !auditStandard) {
			return;
		}
		Node node = lp == null ? null : lp.getNode();
		int targetNodeId = Integer.getInteger("twet.bpc.ngDssrJoinAuditNodeId", -1);
		if (node == null || (targetNodeId >= 0 && node.id != targetNodeId)) {
			return;
		}
		String mode = auditEnvelope ? "envelope-after-standard" : "standard-after-envelope";
		ArrayList<TWETColumn> auditColumns = runAlternativeJoinAudit(lp, auditEnvelope);
		writeAlternativeJoinAudit(lp, mode, auditColumns);
	}

	private ArrayList<TWETColumn> runAlternativeJoinAudit(LP lp, boolean useEnvelopeJoin) {
		ArrayList<TWETColumn> savedGeneratedColumns = generatedColumns;
		PriorityQueue<PricingColumnCandidate> savedGeneratedColumnCandidates = generatedColumnCandidates;
		HashMap<SequenceSignature, PricingColumnCandidate> savedGeneratedCandidateBySignature =
				generatedCandidateBySignature;
		int savedNextCandidateId = nextCandidateId;
		long savedGeneratedCandidateCount = generatedCandidateCount;
		long savedGeneratedCandidateDroppedByHeap = generatedCandidateDroppedByHeap;
		double savedBestGeneratedReducedCost = bestGeneratedReducedCost;
		double savedLastRelaxedRoundBestReducedCost = lastRelaxedRoundBestReducedCost;
		ArrayList<NonElementaryNegativeRoute> savedNonElementaryNegativeRoutes = nonElementaryNegativeRoutes;

		int savedNgDssrRoundNonElementaryNegativeSeen = ngDssrRoundNonElementaryNegativeSeen;
		try {
			generatedColumns = new ArrayList<TWETColumn>();
			generatedColumnCandidates = new PriorityQueue<PricingColumnCandidate>(
					Math.max(1, config.maxExactPricingColumns), candidateWorstFirstComparator());
			generatedCandidateBySignature = new HashMap<SequenceSignature, PricingColumnCandidate>();
			nextCandidateId = 0;
			generatedCandidateCount = 0;
			generatedCandidateDroppedByHeap = 0;
			bestGeneratedReducedCost = Utility.big_M;
			lastRelaxedRoundBestReducedCost = Double.POSITIVE_INFINITY;
			nonElementaryNegativeRoutes = new ArrayList<NonElementaryNegativeRoute>();
			ngDssrRoundNonElementaryNegativeSeen = 0;
			if (useEnvelopeJoin) {
				joinAllForwardTerminalGroupsByEnvelope(lp);
			} else {
				for (int lastJob = activeForwardTerminalJobs.nextSetBit(0);
						lastJob >= 0 && lastJob <= data.n && canContinue();
						lastJob = activeForwardTerminalJobs.nextSetBit(lastJob + 1)) {
					ArrayList<ForwardLabel> candidates = activeForwardByLastJob.get(lastJob);
					if (candidates.isEmpty()) {
						continue;
					}
					joinForwardGroupToBackwardLabels(lastJob, candidates, lp);
					if (canContinue()) {
						joinForwardGroupToSink(candidates, lp);
					}
				}
			}
			finalizeGeneratedColumns(lp);
			return new ArrayList<TWETColumn>(generatedColumns);
		} finally {
			generatedColumns = savedGeneratedColumns;
			generatedColumnCandidates = savedGeneratedColumnCandidates;
			generatedCandidateBySignature = savedGeneratedCandidateBySignature;
			nextCandidateId = savedNextCandidateId;
			generatedCandidateCount = savedGeneratedCandidateCount;
			generatedCandidateDroppedByHeap = savedGeneratedCandidateDroppedByHeap;
			bestGeneratedReducedCost = savedBestGeneratedReducedCost;
			lastRelaxedRoundBestReducedCost = savedLastRelaxedRoundBestReducedCost;
			nonElementaryNegativeRoutes = savedNonElementaryNegativeRoutes;
			ngDssrRoundNonElementaryNegativeSeen = savedNgDssrRoundNonElementaryNegativeSeen;
		}
	}

	private void writeAlternativeJoinAudit(LP lp, String mode, ArrayList<TWETColumn> columns) {
		Path dir = Paths.get(System.getProperty("twet.bpc.ngDssrJoinAuditDir",
				"test-results/bpc/ng-dssr-join-audit"));
		Node node = lp.getNode();
		String fileName = "node-" + node.id + "-round-" + ngDssrRound + "-" + mode + "-"
				+ System.currentTimeMillis() + ".tsv";
		try {
			Files.createDirectories(dir);
			Path file = dir.resolve(fileName);
			LP.PricingDualSnapshot dual = lp.captureTruePricingDuals();
			try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				out.write("mode\tcolumnIndex\tstoredCost\tevalCost\tcostDiff\tstoredReducedCost\tevalReducedCost"
						+ "\tsource\tlength\trepeated\tsequence");
				out.newLine();
				for (int i = 0; i < columns.size(); i++) {
					TWETColumn column = columns.get(i);
					double evalCost = evaluator.evaluate(column.getSequence());
					double storedReducedCost = lp.computeReducedCost(column, dual);
					double evalReducedCost = storedReducedCost + evalCost - column.getCost();
					out.write(mode);
					out.write('\t');
					out.write(Integer.toString(i));
					out.write('\t');
					out.write(Double.toString(column.getCost()));
					out.write('\t');
					out.write(Double.toString(evalCost));
					out.write('\t');
					out.write(Double.toString(column.getCost() - evalCost));
					out.write('\t');
					out.write(Double.toString(storedReducedCost));
					out.write('\t');
					out.write(Double.toString(evalReducedCost));
					out.write('\t');
					out.write(String.valueOf(column.getSource()));
					out.write('\t');
					out.write(Integer.toString(column.size()));
					out.write('\t');
					out.write(Boolean.toString(hasRepeatedJob(column.getSequence())));
					out.write('\t');
					out.write(formatSequence(column.getSequence()));
					out.newLine();
				}
			}
			System.out.println("[ngDssrJoinAudit] node=" + node.id + " round=" + ngDssrRound
					+ " mode=" + mode + " columns=" + columns.size() + " file=" + file.toAbsolutePath());
		} catch (IOException ex) {
			System.err.println("[ngDssrJoinAudit] failed node=" + node.id + " mode=" + mode + ": "
					+ ex.getMessage());
		}
	}

	private void finalizeGeneratedColumns(LP lp) {
		generatedColumns.clear();
		ArrayList<PricingColumnCandidate> candidates = new ArrayList<PricingColumnCandidate>(
				generatedCandidateBySignature.values());
		Collections.sort(candidates, candidateBestFirstComparator());
		LP.PricingDualSnapshot pricingDuals = lp.captureTruePricingDuals();
		for (int i = 0; i < candidates.size(); i++) {
			PricingColumnCandidate candidate = candidates.get(i);
			// 2026-05-31: 闂佸憡鐟禍婵嗭耿娓氣偓瀵晫娑甸崨顓囨繈鏌?no-cut pi-window 婵炴潙鍚嬫穱娲敊閳?K 闂佸壊鍋勫Λ妤呭焵椤掍胶鎳囬柍褜鍓欓ˇ浼村垂濮樿泛瀚夋い鎺嗗亾鐟滅増绋戦銉╁礋椤愶紕鍓ㄧ紓浣圭槺娴ｆ彃浜?
			// pi-window 闂佸搫瀚烽崹鎵暜?hard window 闂佹眹鍔岀€氼剟鎮哄▎鎾崇婵炲樊浜濋敍鐔兼煥濞戞瀚版繛鍙夌矊椤?inferred 闂佺懓鐡ㄩ崝鏍э耿閻楀牏鈻旂€广儱瀚粔闈浢瑰鍐╂崳婵犫偓閿涘嫧鍋撻崷顓炰粶闁割煈浜獮瀣箛椤撶喐瀚抽梺?
			// inferred reduced cost 閻庤鐡曞鎾舵嫻閻旂儤瀚婚柣鏂挎啞椤ρ囨煥濞戞鐏辨繝鈧敍鍕ㄥ亾?reduced cost 闂佸憡鐟禍娆戞娴兼潙鍗抽柡澶嬪灩濮ｅ牓鏌ㄥ☉妯肩劮缂佺粯鐗犻弻宀€浠﹂挊澶嬮敪婵烇絽娴傞崰妤咁敆濠婂牆绀嗘俊銈勭閻忓洭鏌￠崼顐㈠幍闁?
			// 2026-06-13: SRI active 闂?inferred reduced cost 闂?cut dual闂佹寧绋戞總鏃傜箔婢舵劖鍤勯柦妯侯槸濞懷囨煙?machine/job/arc dual 闂佸憡鐟ョ粔鐢垫暜?objective cost闂?
			// 2026-06-15: partial dominance 婵炴潙鍚嬮懝鍓ф暜椤愶箑鎹堕柟宄扮焾濮婂潡鏌涢幙鍐х凹闁诡喖纾Σ?frontier闂佹寧绋戦張顒€顪?label 闂?minReducedCost
			// 婵炴垶鎸哥粔鎾疮閳ь剙鈽夐幘顖氫壕闁诲氦顫夊銊╂偤閹寸偟顩?recovered sequence 闂佹眹鍔岀€氼剟鎮鹃鍕瀬闁哄鍨甸悘娆撴煙鐎涙ê濮堟繝鈧导瀛樻櫖婵炴垶锚濞懷囨煛?partial backend 闂傚倸娲犻崑鎾绘偡閺囨氨顦︽繝鈧鍫濈闁靛濡囧銊╂煕閹惧磭校濞寸媭鍠楀鍕吋閸ャ劌鐒搁柣搴℃贡閸嬬偤宕瑰璺哄珘妞ゆ垿鏁崑?
			// 2026-07-11: 半域 join 的 inferred cost 依赖 split/Tmid，只用于候选排序。
			// 最终返回 Master 的 sequence 统一恢复全域最小成本，再按真实 reduced cost 过滤。
			PricingColumnCostRechecker.Result checked = PricingColumnCostRechecker.evaluate(candidate.column, data,
					evaluator);
			if (checked != null) {
				TWETColumn checkedColumn = checked.checkedColumn(data);
				if (Utility.compareLt(lp.computeReducedCost(checkedColumn, pricingDuals), REDUCED_COST_TOLERANCE)) {
					generatedColumns.add(checkedColumn);
				}
			}
		}
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

	private boolean isDirectForwardExtensionTimeFeasible(PiecewiseLinearFunction frontier, int prevJob, int nextJob,
			boolean requireTmid) {
		double hEnd = getDynamicForwardHEnd(prevJob, nextJob);
		double earliestCompletion = earliestForwardExtensionCompletion(frontier, prevJob, nextJob);
		return !Utility.compareGt(earliestCompletion, hEnd)
				&& (!requireTmid || !Utility.compareGt(earliestCompletion, tMid));
	}

	/** 计算 forward 直接扩展的最早完工时间；full/half 可达性共用这一结果。 */
	private double earliestForwardExtensionCompletion(PiecewiseLinearFunction frontier, int prevJob, int nextJob) {
		if (frontier == null || frontier.head == null || getDynamicForwardJobPenalty(prevJob, nextJob) == null) {
			return Double.POSITIVE_INFINITY;
		}
		double hStart = getDynamicForwardHStart(prevJob, nextJob);
		return Math.max(frontier.head.start + data.getSetUp(prevJob, nextJob) + data.getProcessT(nextJob), hStart);
	}

	/**
	 * 2026-05-22: backward 婵炴挻鐨滈崘鈺侇伅闁荤姳鑳堕崕銈夊几閸愨晝鈻旈柍褜鍓熼幊娑欐綇閸撗咁槷闂佺绻愰悧蹇涘极閵堝瀚夋い鎺戝暟閺嬪倸螞閺夊灝顏い鎾虫憸缁螖閸愨晝鏆?H^b_{ir} 闂?O(1) 婵炲瓨鍤庨崐婵嗏枖閿旇姤浜ら柛銉ｅ妽婵垽鏌?
	 * 闂佹椿浜為崰鎰邦敆濠婂牊鍎?reduced-cost 闂佸憡鍨兼慨銈夊汲閻斿摜顩风€广儱鎳忛煬?extendBackward 闂備焦褰冮惌鍌炲焵椤掍椒浜㈢紒?shift/add/normalize 闂備緡鍋呯敮妤冩暜瑜版帒违?
	 */
	private boolean isDirectBackwardExtensionTimeFeasible(BackwardLabel label, int prevJob) {
		return isDirectBackwardExtensionTimeFeasible(label.jid, label.isSinkRoot, label.frontier, prevJob);
	}

	private boolean isDirectBackwardExtensionTimeFeasible(int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier, int prevJob) {
		return isDirectBackwardExtensionTimeFeasible(firstJob, isSinkRoot, frontier, prevJob, true);
	}

	private boolean isDirectBackwardExtensionTimeFeasible(int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier, int prevJob, boolean requireTmid) {
		int successor = isSinkRoot ? data.n + 1 : firstJob;
		double rhoPrime = latestBackwardExtensionCompletion(firstJob, isSinkRoot, frontier, prevJob);
		double hStart = getDynamicBackwardHStart(prevJob, successor);
		double lower = requireTmid ? Math.max(tMid, hStart) : hStart;
		return !Utility.compareLt(rhoPrime, lower);
	}

	/** 计算 backward 直接扩展可容纳的最晚完工时间；full/half 可达性共用这一结果。 */
	private double latestBackwardExtensionCompletion(int firstJob, boolean isSinkRoot,
			PiecewiseLinearFunction frontier, int prevJob) {
		int successor = isSinkRoot ? data.n + 1 : firstJob;
		if (isSinkRoot) {
			return getDynamicBackwardHEnd(prevJob, successor);
		}
		double delay = data.getSetUp(prevJob, firstJob) + data.getProcessT(firstJob);
		return Math.min(frontier.tail.end - delay, getDynamicBackwardHEnd(prevJob, successor));
	}

	private PackedBitSet updateNgMemory(PackedBitSet parentNgMemory, int currentJob) {
		PackedBitSet memory = parentNgMemory.and(ngNeighborhoodByJob[currentJob]);
		memory.add(currentJob);
		return memory;
	}

	/**
	 * 扩展候选通过 completion bound 后，再一次性建立 dominance 与实际扩展集合。
	 * 两者共享 full-domain 可达性判断，避免 survivor 对全部 job 做两轮扫描。
	 */
	private ChildReachability buildForwardChildReachability(int fromJob, PackedBitSet ngMemory, Node node,
			PiecewiseLinearFunction frontier) {
		PackedBitSet dominanceSet = new PackedBitSet(data.n + 2);
		PackedBitSet extensionSet = new PackedBitSet(data.n + 2);
		for (int job = reachabilityCandidateJobs.nextSetBit(1); job > 0 && job <= data.n;
				job = reachabilityCandidateJobs.nextSetBit(job + 1)) {
			if (ngMemory.contains(job)) {
				continue;
			}
			double earliestCompletion = earliestForwardExtensionCompletion(frontier, fromJob, job);
			if (Utility.compareGt(earliestCompletion, getDynamicForwardHEnd(fromJob, job))) {
				continue;
			}
			dominanceSet.add(job);
			if (isForwardHalfEligibleJob(job) && !Utility.compareGt(earliestCompletion, tMid)) {
				extensionSet.add(job);
			}
		}
		extensionSet.andInPlace(forwardExtensionArcMaskByFrom[fromJob]);
		return new ChildReachability(dominanceSet, extensionSet);
	}

	private ChildReachability buildBackwardChildReachability(int firstJob, PackedBitSet ngMemory, Node node,
			PiecewiseLinearFunction frontier) {
		PackedBitSet dominanceSet = new PackedBitSet(data.n + 2);
		PackedBitSet extensionSet = new PackedBitSet(data.n + 2);
		boolean isSinkRoot = firstJob == node.sinkId();
		int successor = isSinkRoot ? node.sinkId() : firstJob;
		for (int job = reachabilityCandidateJobs.nextSetBit(1); job > 0 && job <= data.n;
				job = reachabilityCandidateJobs.nextSetBit(job + 1)) {
			if (ngMemory.contains(job)) {
				continue;
			}
			double rhoPrime = latestBackwardExtensionCompletion(firstJob, isSinkRoot, frontier, job);
			double hStart = getDynamicBackwardHStart(job, successor);
			if (Utility.compareLt(rhoPrime, hStart)) {
				continue;
			}
			dominanceSet.add(job);
			if (isBackwardHalfEligibleJob(job) && !Utility.compareLt(rhoPrime, Math.max(tMid, hStart))) {
				extensionSet.add(job);
			}
		}
		extensionSet.andInPlace(backwardExtensionArcMaskBySuccessor[successor]);
		return new ChildReachability(dominanceSet, extensionSet);
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
		joinMemoryIgnoredJobs = new PackedBitSet(data.n + 2);
		joinMemoryIgnoredJobs.add(0);
		zeroDualExcludedJobCount = 0;
		dualProfitableWindowEnabled = canUseDualProfitableWindow(lp);
		precomputeEffectivePricingWindows(lp);
		// 2026-07-16: in-round 关闭时，ng-DSSR pricing 内不再构造临时 time-indexed 图。
		// node/cut-loop 收敛后的永久 fixing 由独立 applyArcFixing() 路径控制，不受这里影响。
		if (config.timeIndexedCompletionBoundInRoundArcFixing) {
			buildTimeIndexedScalarBoundAndTightenWindows(lp);
		}
		precomputeZeroDualExcludedJobs(lp);
		precomputeCompletionBoundPricingWindows();
	}

	private void cacheDssrReusablePricingWindowScalars() {
		// 2026-06-12: initialize() 濠殿噯绲界换妤呮偪閸℃ê顕辨慨姗嗗墮鐢儵姊洪幓鎺斝ら柣?pricingHorizon闂佹寧绋掔粙鎴λ囬弻銉﹀仺?window 闂佽桨鐒︽竟鍡欏垝瀹ュ绫嶉悹楦挎缁犳垵顪冮妶鍫殭闁诡喗鎸搁～銏ゅΨ閿斿灝鍨濇繝銏ｆ硾缁夐鎹㈤弽銊ь洸婵炴垶鐟ч崹濂告⒑閹绘帞绠為柍?
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
		// pricing 内 helper 只在 in-round=true 时进入本方法。
		TimeIndexedScalarCompletionBound.WindowTightening tightened =
				timeIndexedScalarBound.tightenWindowsAfterZeroReducedCostArcFixing(
						effectiveJobHStart, effectiveJobHEnd);
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
				// 2026-06-29: time-indexed fixing 閻庣數澧楅〃鍛村春瀹€鍕剭闁告洦鍓氱瑧闂佸憡鐟崹鎶藉箣閻戣棄绠ラ悹鎭掑妽閻ｈ京鐥褍澧伴柣娑栧劦瀹曪綁鏁愯箛鏇炶€块梺?dual window 闂佸憡鐟﹂悧鏂款潩閿曞倹鈷栭柛鈩冾殔缁侇噣鏌涘▎妯虹仜闁?
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
			// 2026-05-26: 閻熸粎澧楅幐鎼佹儑椤掑嫭鐒鹃柕濞у懎鐐婇梺鍛婄懕缁蹭粙宕欓敍鍕＜闊洦鑹鹃崹閬嶆煕?pricingHorizon 闂佸搫鍟抽鎰濠靛鐐婇柣鎰€€閸嬫捇鍩€椤掑嫬绀嗛柡澶婄仢缁€渚€鏌涚€ｎ亞绠伴柛銊ョ箻瀹曟岸宕遍鐘殿槷闂備緡鍓欓悘婵嬪储閵堝瑙﹂幖绮瑰墲閸婂鏌涘Δ鈧敃銈囦焊椤栫偞鈷掗柟缁㈠枟濮ｆ劙骞栨潏鍓х窗閻?0闂?
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
	 * 2026-06-07: 闂佺绻愰悧鍛垝?low reduced-cost 闂佸憡甯楅妵鐐烘嚈閹寸姵瀚氶柛鏇ㄤ簽楠?2K 闂佸搫顦Σ鍕濠靛绀冪€广儱妫楅惁濠氭煕閹烘搩娈曟繝鈧婧惧亾閻熸媽瀚板ù鍏煎姍瀵噣宕奸弴鐕傜吹闂佸憡鐟﹂悧妤€銆掗崼鏇炵柧?K 闂佸搫顥￠幍鍐蹭壕?
	 * 闁哄鏅滈悷锕傛偋鏉堛劎鈹嶆繝闈涚墛濞堝苯霉閻樹警鍤欏┑顔惧枛閺?median 闂佹眹鍔岀€氼垶顢氶姀锛勨枙濠㈣埖绋撶粈澶愭煕濮橆剚婀版俊鐐插€垮畷娆掔疀閹捐埖鐦滈梺娲诲弾閸樺ジ宕归鍫濈闁哄秲鍔嶉敍宥夋倵閻熸媽瀚板ù鍏煎姍瀹曟艾螖娴ｈ倽?Tmid 闂佺懓鍢查ˇ顖滄鏉堛劍浜ら柛銉戝倻顔嗛梺?
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
		// 濠殿喗绻愮徊浠嬫偉?midpoint 闂佺娴氶崜娆戞閳╁啯鍎熼柡鍌氱仢閸ゆ帡鏌﹂埀顒勬寠婢跺鍚?(0, pricingHorizon) 闂佸憡鍔曢幏鎴犳鏉堛劍浜ゆ繛鍡樻尭濞呪€趁归悩鍙夊殗婵¤尪顕ч銉╁焺閸愵亖鍋撻鍌欑剨?horizon 闂佺懓鐡ㄩ悧鏇㈠箖濡ゅ啰纾兼い鎾跺枑閺嗩參鏌涜箛锝呭缂佹唻绻濋弻鍛存偐閸愯尙浜ｉ柣鐘冲姉椤ユ劗绮╅悢鐓幬?
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
	 * 2026-05-25: 闂佸憡鐟禍婵囩箾婵犲洤鐭楅柡宥冨€愰崑鎾愁煥閸愨晛顏梺鍛婃尭缁夊綊鍩?闂佸憡鑹惧ù鐑藉箣閻戣棄绫嶉柣妯诲絻瑜扮娀鏌曢崱鏇狀槮鐎规洜鍠栭幆?job 闂佺厧顨庢禍婊呮崲娴ｈ桨鐒婇弶鍫亝閸庢澘鈽夐幘宕囆㈤柛鈺佺灱閳ь剛鏁搁幊鎾惰姳?half-domain闂佺偨鍎茬换鍕枔閹寸偟鈹嶉柍鈺佸暕缁辨牠鏌?
	 * forward 闂佸吋鐪归崕鏌ュ汲閿濆應鏋旈柣銏㈩暯閳ь剚鐗滅划锝呂旈崨顓炲箣闂?Tmid 闂佸憡鐟ラ崢鏍疾閸洘鏅悘鐐舵閻忕喎霉閻樹警鍞虹紓?forward prefix 闂備緡鍠楅崹婵堢箔婢舵劖顥嗛柍褜鍓涢幉鐗堟媴缁嬫寧娅冮柣蹇撶箲缁诲棝鎯侀婧惧亾閻熸澘鏋︾紒?
	 * backward 闁诲酣娼у﹢杈叿闂侀潻闄勬竟鍡楋耿閸涙潙鏋侀悗娑欙供閸炵晫鐥褍澧伴柣娑栧劦瀵即顢涘顓炲墾閻庣懓鎲¤ぐ鍐偩椤掑嫬绀傞柕濞垮妽閸庝即鏌?Tmid 閻庡綊娼荤紓姘跺疾閸洖违?
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
			// 2026-05-24: data.penaltyFunction[job] 閻庤鐡曠亸娆戝垝閿熺姴绀岄柛娑卞幗閸庢捇鏌涢埡鍕€冪紒?b_j 闂佹眹鍔岀€氭澘顭囬妶澶婄畱濞达綀娅ｉ悡鎰棯椤撗冨闁绘稏鍎垫俊?
			// dual 婵炴垶鎸哥粔鐑藉礂濡粯浜ゆ繛鎴烆殘椤忚鲸鎱ㄥ┑鍕姕闁轰礁婀卞Σ鎰槼婵＄偛鍊块弫宥囦沪閽樺鎽曢梺?pricing 闂佺儵鏅涢悺銊ф暜鐎涙ê绶炵€广儱娲﹂弳蹇涘级閳哄倻鎳侀悶姘抽哺缁嬪顢旈崟顐わ紮闂佺硶鏅濋崰鎾舵閿旈敮鍋撳☉娆欏叕缂佽鲸绻堥弻鍡涘垂椤旂厧璧嬪┑顕嗙到缁绘鎮块崱娑欑厒鐎广儱鎷嬪Σ?setDomain/crop闂?
			baseForwardHalfPenaltyByJob[job] = cropToInterval(pricingPenaltyFunction(job), 0.0, tMid);
			baseBackwardHalfPenaltyByJob[job] = cropToInterval(pricingPenaltyFunction(job), tMid, pricingHorizon);
		}
		baseHalfPenaltyCacheTMid = tMid;
		baseHalfPenaltyCacheHorizon = pricingHorizon;
	}

	private boolean canUseDualProfitableWindow(LP lp) {
		return !feasibilityPhaseOneObjectiveMode && PricingCompatibility.canUseDualProfitableWindow(lp);
	}

	private double pricingSetupCost(int from, int to) {
		return feasibilityPhaseOneObjectiveMode ? 0.0 : data.getSetupCost(from, to);
	}

	private PiecewiseLinearFunction pricingPenaltyFunction(int job) {
		if (!feasibilityPhaseOneObjectiveMode) {
			return data.penaltyFunction[job];
		}
		double start = job == 0 ? 0.0 : data.hardWindowStart[job];
		double end = job == 0 ? data.CmaxH : data.hardWindowEnd[job];
		PiecewiseLinearFunction zero = new PiecewiseLinearFunction();
		zero.resetDomain(start, end);
		if (!Utility.compareGt(start, end)) {
			zero.addSegment(start, end, 0.0, 0.0);
		}
		return zero;
	}

	/**
	 * 2026-05-28: 闂佸搫绉堕、濠冧繆椤撱垺鍊?no-cut pricing 婵炴垶鎼╅崣蹇曟濠曠櫡_j=0 闂佹眹鍔岀€氼亪骞戦姀銈呯闁炽儴灏欓悷婵嬪级閳哄倻鈽夐柛?pricing 闂佸湱顣介弲娑㈡儓瀹ュ违?
	 * 闂侀潻璐熼崝宀€绱炵€ｎ喖绀堢€广儱妫欓敓?cut/branch dual 闂佹眹鍔岀€氼亞绮担鐑樺枂闁瑰搫绉堕悷婵堢磼濞戞﹩妲哥紒鎲嬬磿閹风娀顢樺┑鍫㈡瀫婵炴垶鎸搁鎴犳濠靛洦浜ゆ繛鍡樺灱椤?job 婵炴垶鎸哥粔纾嬨亹閺屻儲鍤勯柤鎭掑劜閺嗩參鏌涢悩鎻掝伂缂佲偓?reduced-cost 闂佸憡甯楅妵婊堝焵?
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
				joinMemoryIgnoredJobs.add(job);
				zeroDualExcludedJobCount++;
			}
		}
	}

	private PiecewiseLinearFunction buildForwardHalfPenalty(int job, double hStart, double hEnd) {
		// 2026-05-23: 闂佸憡顨呴敃銈夋偂濞嗘劖缍囬柛锔诲幗濞呮洟鏌ｉ埡浣烘憼閻㈩垱鎸冲畷妯衡枎韫囨挸姹查梺鍛婃煟閸斿秹鍩€?job penalty闂?
		// forward 闂佹眹鍔岀€氼參寮绘繝鍐╂珷?job 闂佸憡鍨兼慨銈夊汲閻旂厧鐭楁い蹇撳闊?[0,Tmid] 婵炴垶鎸搁敃銈咁嚕椤掍胶鈻?add闂佹寧绋戦懟顖炲矗閺囥垹绀傞柛妤冨仧閺嗘澘鈽夐弬娆炬Ц闁绘挻鐟︾€电厧顫濋崘鍙夘唶闂佺粯甯熼崺鏍ㄤ繆閹间礁鐭楃€规洖娴傛导鍌炴煕濡炵儵鍋撻搹顐ュ惈 Tmid闂?
		return cropToInterval(pricingPenaltyFunction(job).setDomain(hStart, hEnd, true), 0.0, tMid);
	}

	private PiecewiseLinearFunction buildBackwardHalfPenalty(int job, double hStart, double hEnd) {
		// 2026-05-23: backward 闁诲酣娼у﹢杈叿婵炶揪缍€濞夋洟寮?[Tmid,pricingHorizon] 婵炴垶鎸搁敃锕€鈻撻幋锕€妫橀柡澶嬵儥閺?job 闂佸憡鍨兼慨銈夊汲閻旂厧违?
		// 闂佸吋鐪归崕鎶芥偘閵夆晛鐭楅柨婵嗘噸缁狀垰銆掑鈧崒婵堫槹 big_M闂佹寧绋戦懟顖炲箖濡ゅ啰纾?normalize(BACKWARD) 婵炴潙鍚嬪畝鎼佸焵椤掍椒浜㈢紒?suffix-min 闁荤偞绋忛崝蹇涘箵椤忓牆鐏虫繝濠傚暙鐠佹彃霉閻橆喖鍔ら柣鈩冨灴瀹曟岸骞嶉鎯х倞闂佸憡鐟辩徊浠嬪船鐎电硶鍋撻悷鐗堟拱闁搞劍宀搁崹鎯р攽閸曘劌浜?
		return cropToInterval(pricingPenaltyFunction(job).setDomain(hStart, hEnd, true), tMid, pricingHorizon);
	}

	private PiecewiseLinearFunction buildCompletionBoundPenalty(int job, double hStart, double hEnd) {
		// 2026-06-01: Tmid pricing 闂佹眹鍔岀€氼參顢楀鍐惧殨?label 婵炲濮寸粔铏箾閸ヮ剚鍋ㄩ柕濞垮劙缁狀垶鏌涘▎蹇撴毐鐎规洘鍔欏畷娲偄瀹勭増鐦ｉ梺杞扮鎼存粎妲愰惇淇筸pletion bound
		// 闂傚倸娲犻崑鎾绘偡閺囨氨顦﹂柛銊ょ矙瀵剟顢橀悙鑼紮闂?label 闂佸搫瀚烽崹浼村箚娴ｈ浜ゆ俊顖氱仢閸樻挳鎮跺☉鏍у姕闁搞劍姘ㄩ埀顒傛嚀閺堫剟寮抽敐鍥ㄥ闁绘柨鍢查悘娆撴煥濞戞瀚版繛鍙夌矊椤垽濡堕崨顓狀槹闂佺粯鐟崗娑欑箾閸ヮ剚鍋ㄩ柕濞垮劤閺嗘岸鏌?[0, pricingHorizon] 闁诲氦顫夐惌顔剧不閻旂厧鏄ラ柣鏃傝ˉ閸?
		if (isEffectiveWindowTighterThanHard(job)) {
			return cropToInterval(pricingPenaltyFunction(job).setDomain(hStart, hEnd, true), 0.0, pricingHorizon);
		}
		return cropToInterval(pricingPenaltyFunction(job), 0.0, pricingHorizon);
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
		return left.intersectsExcluding(right, joinMemoryIgnoredJobs);
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
	 * 2026-05-24: normal forward label 缂?prefix-min normalize 闂佸憡鑹剧€涒晠寮抽敐鍡樺闁瑰墽绮慨婊冾熆瑜忛崕鎴犳?
	 * 闂佸搫鐗冮崑鎾绘倶?reduced cost 闂佺儵鏅涢悺銊ф暜閹绢喗濯伴柦妯侯槹闊剟鏌￠崼姘壕闂佸憡鐟ョ壕顓㈩敂椤掑嫭鏅悘鐐跺亹閻熸繈鐓崶褎鍣洪柣锔光偓鏂ユ瀻闁炽儱鍟块弲娆撴煕韫囧濡芥い?findMinimal闂?
	 */
	private static double forwardEndpointMin(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.tail == null) {
			return Utility.big_M;
		}
		return frontier.tail.getValue(frontier.tail.end);
	}

	/**
	 * 2026-05-24: normal backward label 缂?suffix-min normalize 闂佸憡鑹剧€涒晠寮抽敐鍡樺闁瑰墽绮慨婊堟煕閹存繈鐛滅紒?
	 * 闂佸搫鐗冮崑鎾绘倶?reduced cost 闂佺儵鏅涢悺銊ф暜閹绢喗濯伴柦妯侯槹闊剟鏌￠崼姘壕閻庡綊娼绘俊鍥敂椤掑嫭鏅繛鎴灻☉褔鏌?joinCost 闂備緡鍙忕徊鍧楋綖閹烘瀚夋い蹇撳閺呮瑩鏌￠崒婊勫殌闁诡喗绮撳畷鐘诲冀椤愶絿鏆犻梺鍛婂灱婵倝寮抽悢鐓庣鐎广儱顦版禒姗€鎮?findMinimal闂?
	 */
	private static double backwardEndpointMin(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.head == null) {
			return Utility.big_M;
		}
		return frontier.head.getValue(frontier.head.start);
	}

	private PiecewiseLinearFunction cropToInterval(PiecewiseLinearFunction function, double start, double end) {
		PiecewiseLinearFunction cropped = new PiecewiseLinearFunction();
		// 2026-05-23: crop 婵炴垶鎸哥粔纾嬨亹瑜忛幉妤佹媴閸濆嫧鎸呴梺?segment闂佹寧绋戞總鏃傚姬閸愵亝鍟哄ù锝呮啞閸婅鲸鎱ㄥ┑鍕姎闁搞倝浜跺顐﹀级閸喖绗氶梺杞拌兌婢ф鐣垫笟鈧俊?
		// shiftX() 闂?trimToDomain 闂佸憡鐟禍鐐诧耿?domainStart/domainEnd闂佹寧绋掔粙鎴︺€呰瀵顭ㄩ崱娆戭啇闂備焦褰冩總鏃傜箔婢舵劖鐓傜€广儱鐗滈崯搴ㄦ煥?
		// 闂佸憡鑹惧ù鐑芥偨婵犳艾纭€濠电姴鍊婚崢?label 婵炴潙鍚嬮懝鎹愩亹瑜旈幊妤佺鐎ｎ偅绁?add 闂佹眹鍔岀€氼剟宕ｉ弴銏犵閺夊牃鏅涢埛鏍煟閻愬弶顥滈柣锝囧亾缁嬪﹥寰勬繝鍕箥闂佺绻戠划宀€鑺遍幎鑺ユ櫖閻忕偠鍋愰悷婵嬫煠閸愭祴鍋撻悢閿嬵唶闂佺粯甯熼崺鏍偓?Tmid 闁荤喍妞掔粈浣圭珶閳ь剟鏌?
		cropped.resetDomain(start, end);
		if (function == null || function.head == null || Utility.compareGt(start, end)) {
			return cropped;
		}
		// 2026-05-22: 闂佸憡鐟ラ懟顖炲箖濠婂牆纭€濠电姴鍊婚崢鐢告煕濞嗘ê鐏ラ柛蹇旓耿瀹曟艾顫濋鎯т缓闂備緡鍋€閸嬫捇鏌涢弽銊уⅹ闁?Tmid 闂佸憡顨嗗ú婊堝磻閿濆违闁稿本绻勭粻鐟扳槈閹垮啩绨介柛瀣剁稻缁嬪顓奸崨顓燁棟婵炴垶鎸稿ù鐑藉箣妞嬪海纾兼い鎾跺枎閳锋牠鎮橀悙瀛樼┛缂?
		// 婵?join 闂佸搫鍟崕濂搞€呴敃鍌涘殑闁伙絽鏈弳?Tmid 婵犮垼娉涚€氼剟鎮ラ崼鏇炴瀬闁哄瀵ч浠嬫煙闁垮宕勯柣锕€瀛╃粋鎺楀礄閵堝洨顦梺鎼炲劤婵敻顢楀┑鍥ㄤ氦婵炲棙鎸稿▍鈥城庨崶銊х畼闁哄棌鍋撻梻鍌氭閻栧ジ寮虫潏銊﹀劅闁挎洍鍋撻柣鏍х埣瀵偊鎮ч崼顐㈡杸闂?
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

	private JoinEnvelopeMinResult findMinimalShiftedTracedSum(TracedJoinEnvelope<ForwardLabel> forwardEnvelope,
			double delta, TracedJoinEnvelope<BackwardLabel> backwardEnvelope, double yShift) {
		JoinEnvelopeMinResult result = new JoinEnvelopeMinResult();
		if (forwardEnvelope == null || backwardEnvelope == null
				|| forwardEnvelope.segments.isEmpty() || backwardEnvelope.segments.isEmpty()) {
			return result;
		}
		int fIndex = 0;
		int bIndex = 0;
		while (fIndex < forwardEnvelope.segments.size() && bIndex < backwardEnvelope.segments.size()) {
			TraceSegment<ForwardLabel> f = forwardEnvelope.segments.get(fIndex);
			TraceSegment<BackwardLabel> b = backwardEnvelope.segments.get(bIndex);
			double fStart = f.start + delta;
			double fEnd = f.end + delta;
			double lo = Math.max(fStart, b.start);
			double hi = Math.min(fEnd, b.end);
			if (Utility.compareLe(lo, hi)) {
				double slope = f.slope + b.slope;
				double intercept = f.intercept - f.slope * delta + b.intercept + yShift;
				double left = slope * lo + intercept;
				if (Utility.compareLt(left, result.reducedCost)) {
					result.reducedCost = left;
					result.forwardLabel = f.source;
					result.backwardLabel = b.source;
				}
				double right = slope * hi + intercept;
				if (Utility.compareLt(right, result.reducedCost)) {
					result.reducedCost = right;
					result.forwardLabel = f.source;
					result.backwardLabel = b.source;
				}
			}
			if (Utility.compareLe(fEnd, b.end)) {
				fIndex++;
				if (Utility.compareEq(fEnd, b.end)) {
					bIndex++;
				}
			} else {
				bIndex++;
			}
		}
		return result;
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
		double lastExactTmid = Double.NaN;
		private ArrayList<Integer> freezeActiveCutIds;
		private int freezeExactCalls;
		private double freezeLastSelectedTmid = Double.NaN;
		private int freezeStableSelections;
		private boolean frozen;
		private double frozenTmid = Double.NaN;
		private int frozenSkippedCalls;
		private boolean freezeValidationPending;

		void ensureCutEpoch(List<Integer> activeCutIds) {
			List<Integer> current = activeCutIds == null ? Collections.<Integer>emptyList() : activeCutIds;
			if (freezeActiveCutIds != null && freezeActiveCutIds.equals(current)) {
				return;
			}
			freezeActiveCutIds = new ArrayList<Integer>(current);
			// 2026-07-21: cut 改变后 dual 和定价域已经变化，最近一次 Tmid 也不能跨 epoch 复用。
			lastExactTmid = Double.NaN;
			freezeExactCalls = 0;
			freezeLastSelectedTmid = Double.NaN;
			freezeStableSelections = 0;
			frozen = false;
			frozenTmid = Double.NaN;
			frozenSkippedCalls = 0;
			freezeValidationPending = false;
		}

		boolean tryAcquireFrozenMidpoint() {
			if (!frozen || !Double.isFinite(frozenTmid)) {
				return false;
			}
			if (frozenSkippedCalls >= MIDPOINT_FREEZE_SKIPPED_CALLS) {
				freezeValidationPending = true;
				return false;
			}
			frozenSkippedCalls++;
			return true;
		}

		String considerFreezeSelection(double selectedTmid, double horizon) {
			freezeExactCalls++;
			double tolerance = Math.max(Utility.EPS,
					Math.abs(horizon) * MIDPOINT_FREEZE_HORIZON_TOLERANCE);
			double reference = freezeValidationPending ? frozenTmid : freezeLastSelectedTmid;
			boolean stable = Double.isFinite(reference)
					&& Utility.compareLe(Math.abs(selectedTmid - reference), tolerance);
			freezeLastSelectedTmid = selectedTmid;
			if (freezeValidationPending) {
				freezeValidationPending = false;
				frozenSkippedCalls = 0;
				if (stable) {
					frozenTmid = selectedTmid;
					freezeStableSelections = Math.max(freezeStableSelections,
							MIDPOINT_FREEZE_STABLE_SELECTIONS);
					return "validated";
				}
				frozen = false;
				frozenTmid = Double.NaN;
				freezeStableSelections = 1;
				return "validationChanged";
			}
			freezeStableSelections = stable ? freezeStableSelections + 1 : 1;
			if (!frozen && freezeExactCalls >= MIDPOINT_FREEZE_MIN_EXACT_CALLS
					&& freezeStableSelections >= MIDPOINT_FREEZE_STABLE_SELECTIONS) {
				frozen = true;
				frozenTmid = selectedTmid;
				frozenSkippedCalls = 0;
				return "frozen";
			}
			return stable ? "stable" : "reset";
		}

		String freezeSummary() {
			return "active=" + frozen + ", exact=" + freezeExactCalls + ", stable="
					+ freezeStableSelections + ", skipped=" + frozenSkippedCalls + "/"
					+ MIDPOINT_FREEZE_SKIPPED_CALLS + ", t=" + frozenTmid;
		}

		boolean hasLastExact() {
			return Double.isFinite(lastExactTmid) && Utility.compareGt(lastExactTmid, 0.0);
		}

		void rememberExact(double tMid) {
			lastExactTmid = tMid;
		}
	}

	private static final class MidpointProbeResult {
		final double tMid;
		final double elapsedMillis;
		final double forwardElapsedMillis;
		final double backwardElapsedMillis;
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

		MidpointProbeResult(double tMid, double elapsedMillis, double forwardElapsedMillis,
				double backwardElapsedMillis, int pops, boolean forwardExhausted, boolean backwardExhausted,
				int forwardPops, int backwardPops,
				long forwardKept, long backwardKept, long forwardBoundSurvivors,
				long forwardBoundPruned, long backwardBoundPruned, long forwardQueueRemaining, long backwardQueueRemaining,
				long forwardQueuePeak, long backwardQueuePeak) {
			this.tMid = tMid;
			this.elapsedMillis = elapsedMillis;
			this.forwardElapsedMillis = forwardElapsedMillis;
			this.backwardElapsedMillis = backwardElapsedMillis;
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
			if ("time".equals(normalized)) {
				return timeScore();
			}
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
			if ("time".equals(normalized)) {
				return forwardElapsedMillis;
			}
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
			if ("time".equals(normalized)) {
				return backwardElapsedMillis;
			}
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

		double sideTotalMillis() {
			return forwardElapsedMillis + backwardElapsedMillis;
		}

		double timeScore() {
			double forward = Math.max(0.001, forwardElapsedMillis);
			double backward = Math.max(0.001, backwardElapsedMillis);
			return Math.max(forward / backward, backward / forward);
		}

		String compactSummary(String mode) {
			String normalized = normalizeProbeScoreMode(mode);
			return "t=" + tMid
					+ ",ms=" + elapsedMillis
					+ ",sideMs=" + forwardElapsedMillis + ":" + backwardElapsedMillis
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
					+ ",timeTotal=" + sideTotalMillis()
					+ ",timeRatio=" + timeScore()
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

	private static final class DuplicateRepairResult {
		final ArrayList<Integer> sequence;
		final double reducedCost;

		DuplicateRepairResult(ArrayList<Integer> sequence, double reducedCost) {
			this.sequence = sequence;
			this.reducedCost = reducedCost;
		}
	}

	private static final class JoinEnvelopeIndex {
		final ArrayList<ArrayList<JoinEnvelopeGroup<ForwardLabel>>> forwardByTerminal;
		final ArrayList<ArrayList<JoinEnvelopeGroup<BackwardLabel>>> backwardByTerminal;
		final IdentityHashMap<BackwardLabel, JoinEnvelopeGroup<BackwardLabel>> backwardGroupByLabel =
				new IdentityHashMap<BackwardLabel, JoinEnvelopeGroup<BackwardLabel>>();
		final IdentityHashMap<JoinEnvelopeGroup<ForwardLabel>,
				IdentityHashMap<JoinEnvelopeGroup<BackwardLabel>, Boolean>> pruneByGroup =
				new IdentityHashMap<JoinEnvelopeGroup<ForwardLabel>,
						IdentityHashMap<JoinEnvelopeGroup<BackwardLabel>, Boolean>>();
		final IdentityHashMap<JoinEnvelopeGroup<BackwardLabel>, BitSet[]> prunedIndicesByBackwardGroup =
				new IdentityHashMap<JoinEnvelopeGroup<BackwardLabel>, BitSet[]>();
		final int terminalCount;

		JoinEnvelopeIndex(int size) {
			terminalCount = size;
			forwardByTerminal = new ArrayList<ArrayList<JoinEnvelopeGroup<ForwardLabel>>>(size);
			backwardByTerminal = new ArrayList<ArrayList<JoinEnvelopeGroup<BackwardLabel>>>(size);
			for (int i = 0; i < size; i++) {
				forwardByTerminal.add(null);
				backwardByTerminal.add(null);
			}
		}

		BitSet prunedForwardIndices(int lastJob, ArrayList<ForwardLabel> candidates, BackwardLabel backward, LP lp,
				GCNGBBStyleBidirectionalNgDssr owner) {
			JoinEnvelopeGroup<BackwardLabel> backwardGroup = backwardGroupByLabel.get(backward);
			if (backwardGroup == null) {
				return new BitSet();
			}
			BitSet[] byTerminal = prunedIndicesByBackwardGroup.get(backwardGroup);
			if (byTerminal == null) {
				byTerminal = new BitSet[terminalCount];
				prunedIndicesByBackwardGroup.put(backwardGroup, byTerminal);
			}
			BitSet cachedIndices = byTerminal[lastJob];
			if (cachedIndices != null) {
				return cachedIndices;
			}
			BitSet pruned = new BitSet(candidates.size());
			ArrayList<JoinEnvelopeGroup<ForwardLabel>> forwardGroups = forwardByTerminal.get(lastJob);
			if (forwardGroups != null) {
				for (int i = 0; i < forwardGroups.size(); i++) {
					JoinEnvelopeGroup<ForwardLabel> forwardGroup = forwardGroups.get(i);
					if (canPruneGroup(lastJob, forwardGroup, backwardGroup, lp, owner)) {
						pruned.or(forwardGroup.memberIndices);
					}
				}
			}
			byTerminal[lastJob] = pruned;
			return pruned;
		}

		private boolean canPruneGroup(int lastJob, JoinEnvelopeGroup<ForwardLabel> forwardGroup,
				JoinEnvelopeGroup<BackwardLabel> backwardGroup, LP lp, GCNGBBStyleBidirectionalNgDssr owner) {
			IdentityHashMap<JoinEnvelopeGroup<BackwardLabel>, Boolean> byBackward =
					pruneByGroup.get(forwardGroup);
			if (byBackward == null) {
				byBackward = new IdentityHashMap<JoinEnvelopeGroup<BackwardLabel>, Boolean>();
				pruneByGroup.put(forwardGroup, byBackward);
			}
			Boolean cached = byBackward.get(backwardGroup);
			if (cached != null) {
				return cached.booleanValue();
			}
			owner.joinEnvelopePrefilterGroupPairs++;
			boolean prune = owner.canPruneJoinEnvelopeGroupPair(lastJob, forwardGroup, backwardGroup, lp);
			if (prune) {
				owner.joinEnvelopePrefilterGroupPairsPruned++;
			}
			byBackward.put(backwardGroup, Boolean.valueOf(prune));
			return prune;
		}
	}

	private static final class JoinEnvelopeGroup<L extends FunctionLabel>
			implements Comparable<JoinEnvelopeGroup<L>> {
		final int terminalJob;
		final PackedBitSet ngMemorySet;
		final TracedJoinEnvelope<L> envelope = new TracedJoinEnvelope<L>();
		final ArrayList<L> labels = new ArrayList<L>();
		final BitSet memberIndices = new BitSet();
		double minReducedCost = Utility.big_M;

		JoinEnvelopeGroup(int terminalJob, PackedBitSet ngMemorySet) {
			this.terminalJob = terminalJob;
			this.ngMemorySet = ngMemorySet;
		}

		@Override
		public int compareTo(JoinEnvelopeGroup<L> other) {
			int byCost = Double.compare(minReducedCost, other.minReducedCost);
			if (byCost != 0) {
				return byCost;
			}
			return Integer.compare(terminalJob, other.terminalJob);
		}
	}

	private static final class JoinEnvelopeMinResult {
		double reducedCost = Utility.big_M;
		ForwardLabel forwardLabel;
		BackwardLabel backwardLabel;
	}

	private static final class TracedJoinEnvelope<L extends FunctionLabel> {
		final ArrayList<TraceSegment<L>> segments = new ArrayList<TraceSegment<L>>();

		void merge(PiecewiseLinearFunction function, L source) {
			if (function == null || function.head == null || source == null) {
				return;
			}
			if (segments.isEmpty()) {
				for (Segment seg = function.head; seg != null; seg = seg.next) {
					add(seg.start, seg.end, seg.slope, seg.intercept, source);
				}
				return;
			}
			ArrayList<TraceSegment<L>> merged = new ArrayList<TraceSegment<L>>(segments.size() + 8);
			int oldIndex = 0;
			Segment fresh = function.head;
			double oldCursor = segments.get(0).start;
			double freshCursor = fresh.start;
			while (oldIndex < segments.size() || fresh != null) {
				if (oldIndex >= segments.size()) {
					addFreshRemainder(merged, fresh, freshCursor, source);
					break;
				}
				if (fresh == null) {
					addOldRemainder(merged, oldIndex, oldCursor);
					break;
				}
				TraceSegment<L> old = segments.get(oldIndex);
				double oldStart = Math.max(old.start, oldCursor);
				double freshStart = Math.max(fresh.start, freshCursor);
				if (Utility.compareLe(old.end, oldStart)) {
					oldIndex++;
					if (oldIndex < segments.size()) {
						oldCursor = segments.get(oldIndex).start;
					}
					continue;
				}
				if (Utility.compareLe(fresh.end, freshStart)) {
					fresh = fresh.next;
					if (fresh != null) {
						freshCursor = fresh.start;
					}
					continue;
				}
				if (Utility.compareLt(oldStart, freshStart)) {
					double end = Math.min(old.end, freshStart);
					addTrace(merged, old, oldStart, end);
					oldCursor = end;
					continue;
				}
				if (Utility.compareLt(freshStart, oldStart)) {
					double end = Math.min(fresh.end, oldStart);
					add(merged, freshStart, end, fresh.slope, fresh.intercept, source);
					freshCursor = end;
					continue;
				}
				double start = oldStart;
				double end = Math.min(old.end, fresh.end);
				addLower(merged, start, end, old, fresh, source);
				oldCursor = end;
				freshCursor = end;
				if (Utility.compareEq(end, old.end)) {
					oldIndex++;
					if (oldIndex < segments.size()) {
						oldCursor = segments.get(oldIndex).start;
					}
				}
				if (Utility.compareEq(end, fresh.end)) {
					fresh = fresh.next;
					if (fresh != null) {
						freshCursor = fresh.start;
					}
				}
			}
			segments.clear();
			segments.addAll(merged);
		}

		double minValue() {
			double min = Utility.big_M;
			for (int i = 0; i < segments.size(); i++) {
				TraceSegment<L> seg = segments.get(i);
				double left = seg.value(seg.start);
				if (Utility.compareLt(left, min)) {
					min = left;
				}
				double right = seg.value(seg.end);
				if (Utility.compareLt(right, min)) {
					min = right;
				}
			}
			return min;
		}

		int segmentCount() {
			return segments.size();
		}

		double start() {
			return segments.isEmpty() ? Utility.big_M : segments.get(0).start;
		}

		double end() {
			return segments.isEmpty() ? -Utility.big_M : segments.get(segments.size() - 1).end;
		}

		private void addFreshRemainder(ArrayList<TraceSegment<L>> target, Segment fresh, double cursor, L source) {
			Segment cur = fresh;
			double curStart = cursor;
			while (cur != null) {
				double start = Math.max(cur.start, curStart);
				add(target, start, cur.end, cur.slope, cur.intercept, source);
				cur = cur.next;
				if (cur != null) {
					curStart = cur.start;
				}
			}
		}

		private void addOldRemainder(ArrayList<TraceSegment<L>> target, int oldIndex, double cursor) {
			for (int i = oldIndex; i < segments.size(); i++) {
				TraceSegment<L> seg = segments.get(i);
				double start = i == oldIndex ? Math.max(seg.start, cursor) : seg.start;
				addTrace(target, seg, start, seg.end);
			}
		}

		private void addLower(ArrayList<TraceSegment<L>> target, double start, double end, TraceSegment<L> old,
				Segment fresh, L source) {
			if (!Utility.compareLt(start, end) && !Utility.compareEq(start, end)) {
				return;
			}
			double slopeDiff = old.slope - fresh.slope;
			double interceptDiff = old.intercept - fresh.intercept;
			if (Utility.compareEq(slopeDiff, 0.0)) {
				if (Utility.compareLe(interceptDiff, 0.0)) {
					addTrace(target, old, start, end);
				} else {
					add(target, start, end, fresh.slope, fresh.intercept, source);
				}
				return;
			}
			double crossing = -interceptDiff / slopeDiff;
			if (!Utility.compareLt(start, crossing) || !Utility.compareLt(crossing, end)) {
				double mid = 0.5 * (start + end);
				if (Utility.compareLe(old.value(mid), fresh.getValue(mid))) {
					addTrace(target, old, start, end);
				} else {
					add(target, start, end, fresh.slope, fresh.intercept, source);
				}
				return;
			}
			double leftMid = 0.5 * (start + crossing);
			if (Utility.compareLe(old.value(leftMid), fresh.getValue(leftMid))) {
				addTrace(target, old, start, crossing);
				add(target, crossing, end, fresh.slope, fresh.intercept, source);
			} else {
				add(target, start, crossing, fresh.slope, fresh.intercept, source);
				addTrace(target, old, crossing, end);
			}
		}

		private void addTrace(ArrayList<TraceSegment<L>> target, TraceSegment<L> seg, double start, double end) {
			add(target, start, end, seg.slope, seg.intercept, seg.source);
		}

		private void add(double start, double end, double slope, double intercept, L source) {
			add(segments, start, end, slope, intercept, source);
		}

		private void add(ArrayList<TraceSegment<L>> target, double start, double end, double slope, double intercept,
				L source) {
			if (Utility.compareGt(start, end)) {
				return;
			}
			if (!target.isEmpty()) {
				TraceSegment<L> tail = target.get(target.size() - 1);
				if (tail.source == source && Utility.compareEq(tail.end, start)
						&& Utility.compareEq(tail.slope, slope) && Utility.compareEq(tail.intercept, intercept)) {
					tail.end = end;
					return;
				}
			}
			target.add(new TraceSegment<L>(start, end, slope, intercept, source));
		}
	}

	private static final class TraceSegment<L extends FunctionLabel> {
		final double start;
		double end;
		final double slope;
		final double intercept;
		final L source;

		TraceSegment(double start, double end, double slope, double intercept, L source) {
			this.start = start;
			this.end = end;
			this.slope = slope;
			this.intercept = intercept;
			this.source = source;
		}

		double value(double time) {
			return slope * time + intercept;
		}
	}

	private static final class SinglePointStore<L extends FunctionLabel> {
		// 2026-06-13: ng-DSSR 闂?dominance key 婵炶揪缍€濞夋洟寮?full-domain dominanceSet闂佹寧绋掗惌鐖攖ensionSet 闂佸憡鐟禍婵堟暜閸洖绀嗛悹楦挎缁夊ジ鏌涢幘宕囆㈢€规洘鍔欏畷娲偄缁嬪簱鎸呴柣蹇曞仦濞插繘鍩€?
		final HashMap<PackedBitSet, L> bestByDominanceKey = new HashMap<PackedBitSet, L>();
		final ArrayList<ArrayList<L>> liveLabelsByCardinality = new ArrayList<ArrayList<L>>();
	}

	/**
	 * 已完成函数扩展、但尚未复制路径状态的轻量候选。
	 * completion bound 先在这里判定；只有 survivor 才实体化为完整 label。
	 */
	private static final class ExtensionFrontier {
		final PiecewiseLinearFunction frontier;
		final PiecewiseLinearFunction noSriFrontier;
		final byte[] sriCounts;
		final double sriPenalty;

		ExtensionFrontier(PiecewiseLinearFunction frontier, PiecewiseLinearFunction noSriFrontier,
				byte[] sriCounts, double sriPenalty) {
			this.frontier = frontier;
			this.noSriFrontier = noSriFrontier;
			this.sriCounts = sriCounts;
			this.sriPenalty = sriPenalty;
		}

		PiecewiseLinearFunction noSriFrontier() {
			return noSriFrontier == null ? frontier : noSriFrontier;
		}

		double minReducedCost(Direction direction) {
			return direction == Direction.FORWARD ? forwardEndpointMin(frontier) : backwardEndpointMin(frontier);
		}

		double noSriMinReducedCost(Direction direction) {
			PiecewiseLinearFunction function = noSriFrontier();
			return direction == Direction.FORWARD ? forwardEndpointMin(function) : backwardEndpointMin(function);
		}

		void release() {
			frontier.release();
			if (noSriFrontier != null) {
				noSriFrontier.release();
			}
		}

	}

	private static final class ChildReachability {
		final PackedBitSet dominanceSet;
		final PackedBitSet extensionSet;

		ChildReachability(PackedBitSet dominanceSet, PackedBitSet extensionSet) {
			this.dominanceSet = dominanceSet;
			this.extensionSet = extensionSet;
		}
	}

	private abstract static class FunctionLabel extends Label implements Comparable<Label>, SriStateLabel {
		final int labelId;
		final PackedBitSet ngMemorySet;
		final PackedBitSet extensionSet;
		final int extensionCardinality;
		final PiecewiseLinearFunction noSriFrontier;
		final byte[] sriCounts;
		final double sriPenalty;
		final String sriStateKey;
		/** join 闂傚倸鍟抽崺鏍敊鐏炲墽鈻旈悗娑櫳戦ˇ褔鎮介姘暈闁哄棛鍠庨娆撴嚋闂堟稓褰囬梺鍛婅壘濞村嘲鈻撻幋锕€绀勯柤鎭掑劜濞堝墎绱撻崒娑欏碍闁宦板姂閺佸秴鈻界喊绯眅l frontier 闂佸憡甯楃粙鎴犵磽閹捐瑙﹂幖瀛樼箘閻熸繈鏌涢幇顒傂￠柟渚垮姂瀵劑鎯傞崫銉ь槷闂佸憡鐟崹顖涚閹烘柡鍋撻悷閭︽Ц闁告瑥绻戝鍕吋閸ャ劍娈㈤梺?*/
		PiecewiseLinearFunction joinExtendedFrontier;

		FunctionLabel(int labelId, int jid, PackedBitSet visitedSet, PackedBitSet dominanceSet,
				PackedBitSet extensionSet, PackedBitSet ngMemorySet, PiecewiseLinearFunction frontier,
				PiecewiseLinearFunction noSriFrontier, byte[] sriCounts, double minReducedCost, double sriPenalty) {
			super(jid, null, visitedSet, dominanceSet, frontier, minReducedCost);
			this.labelId = labelId;
			this.extensionSet = extensionSet;
			this.extensionCardinality = extensionSet == null ? 0 : extensionSet.cardinality();
			this.ngMemorySet = ngMemorySet;
			// 无 SRI 时保持 null；主 frontier 就是 no-SRI 口径，避免 partial 替换后残留旧 PWLF 别名。
			this.noSriFrontier = noSriFrontier;
			this.sriCounts = sriCounts == null ? EMPTY_SRI_COUNTS : sriCounts;
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
		final boolean routeElementary;
		final long routeVisitedMask;
		final long routeVisitedMaskHigh;

		ForwardLabel(int labelId, int jid, ForwardLabel father, PackedBitSet visitedSet, PackedBitSet dominanceSet,
				PackedBitSet extensionSet, PackedBitSet ngMemorySet, PiecewiseLinearFunction frontier,
				PiecewiseLinearFunction noSriFrontier, byte[] sriCounts, double sriPenalty,
				boolean maintainRouteVisitProfile) {
			super(labelId, jid, visitedSet, dominanceSet, extensionSet, ngMemorySet, frontier, noSriFrontier, sriCounts,
					forwardEndpointMin(frontier), sriPenalty);
			this.father = father;
			this.depth = father == null ? 0 : father.depth + 1;
			if (!maintainRouteVisitProfile) {
				this.routeElementary = false;
				this.routeVisitedMask = 0L;
				this.routeVisitedMaskHigh = 0L;
			} else if (father == null) {
				this.routeElementary = true;
				this.routeVisitedMask = 0L;
				this.routeVisitedMaskHigh = 0L;
			} else if (jid <= Long.SIZE) {
				long bit = 1L << (jid - 1);
				this.routeElementary = father.routeElementary && (father.routeVisitedMask & bit) == 0L;
				this.routeVisitedMask = father.routeVisitedMask | bit;
				this.routeVisitedMaskHigh = father.routeVisitedMaskHigh;
			} else {
				long bit = 1L << (jid - Long.SIZE - 1);
				this.routeElementary = father.routeElementary && (father.routeVisitedMaskHigh & bit) == 0L;
				this.routeVisitedMask = father.routeVisitedMask;
				this.routeVisitedMaskHigh = father.routeVisitedMaskHigh | bit;
			}
		}
	}

	private static final class BackwardLabel extends FunctionLabel {
		final BackwardLabel father;
		final boolean isSinkRoot;
		final boolean routeElementary;
		final long routeVisitedMask;
		final long routeVisitedMaskHigh;

		BackwardLabel(int labelId, int jid, BackwardLabel father, PackedBitSet visitedSet, PackedBitSet dominanceSet,
				PackedBitSet extensionSet, PackedBitSet ngMemorySet, PiecewiseLinearFunction frontier,
				PiecewiseLinearFunction noSriFrontier, byte[] sriCounts, double sriPenalty, boolean isSinkRoot,
				boolean maintainRouteVisitProfile) {
			super(labelId, jid, visitedSet, dominanceSet, extensionSet, ngMemorySet, frontier, noSriFrontier, sriCounts,
					backwardEndpointMin(frontier), sriPenalty);
			this.father = father;
			this.isSinkRoot = isSinkRoot;
			if (!maintainRouteVisitProfile) {
				this.routeElementary = false;
				this.routeVisitedMask = 0L;
				this.routeVisitedMaskHigh = 0L;
			} else if (isSinkRoot) {
				this.routeElementary = true;
				this.routeVisitedMask = 0L;
				this.routeVisitedMaskHigh = 0L;
			} else if (jid <= Long.SIZE) {
				long bit = 1L << (jid - 1);
				this.routeElementary = father.routeElementary && (father.routeVisitedMask & bit) == 0L;
				this.routeVisitedMask = father.routeVisitedMask | bit;
				this.routeVisitedMaskHigh = father.routeVisitedMaskHigh;
			} else {
				long bit = 1L << (jid - Long.SIZE - 1);
				this.routeElementary = father.routeElementary && (father.routeVisitedMaskHigh & bit) == 0L;
				this.routeVisitedMask = father.routeVisitedMask;
				this.routeVisitedMaskHigh = father.routeVisitedMaskHigh | bit;
			}
		}
	}
}
