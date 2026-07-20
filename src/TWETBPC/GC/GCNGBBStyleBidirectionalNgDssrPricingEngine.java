package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * ng-relaxation + DSSR 瀹為獙鐗堝崐鍩熷弻鍚戝畾浠峰叆鍙ｃ€?
 * <p>
 * 2026-06-09: 璇ュ叆鍙ｅ彧鐢ㄤ簬楠岃瘉 ng/DSSR 瀵瑰綋鍓?GCNGBB-style 鍗婂煙 pricing 鐨勫奖鍝嶏紝榛樿鍏抽棴銆?
 * 涓?elementary pricing銆乸artial dominance 瀹為獙鍜屾湰鍏ュ彛浜掔浉鐙珛锛屼究浜庡畾浣嶆€ц兘鍜屾纭€у樊寮傘€?
 */
public class GCNGBBStyleBidirectionalNgDssrPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;
	private final HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse> midpointProbeReuseByNode;
	private final NgDssrHistoryWarmStart historyWarmStart;

	public GCNGBBStyleBidirectionalNgDssrPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.midpointProbeReuseByNode = new HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse>();
		this.historyWarmStart = new NgDssrHistoryWarmStart(data.n);
	}

	@Override
	public PricingResult price(LP lp) {
		return price(lp, TimeLimitChecker.NONE);
	}

	@Override
	public PricingResult price(LP lp, TimeLimitChecker timeLimitChecker) {
		lastReusableSubtreeArcEliminationBounds = null;
		if (!config.enableBidirectionalPricing || !config.useGCNGBBStyleNgDssrPricing) {
			return PricingResult.noImprovement("GCNGBB-style ng-DSSR bidirectional pricing disabled");
		}
		GCNGBBStyleBidirectionalNgDssr gc = new GCNGBBStyleBidirectionalNgDssr(data, config,
				midpointProbeReuseByNode, GCNGBBStyleBidirectionalNgDssr.DominanceBackend.PAPER, historyWarmStart);
		ArrayList<TWETColumn> columns = gc.solve(lp, timeLimitChecker);
		if (columns.isEmpty()) {
			lastReusableSubtreeArcEliminationBounds = gc.reusableSubtreeArcEliminationBounds();
			return PricingResult.noImprovement(gc.getLastMessage())
					.withCertifiedInternalReducedCost(gc.getLastRelaxedRoundBestReducedCost());
		}
		return new PricingResult(columns, true, gc.getLastMessage())
				.withCertifiedInternalReducedCost(gc.getLastRelaxedRoundBestReducedCost());
	}

	@Override
	public PricingResult findFeasible(LP lp) {
		return findFeasible(lp, TimeLimitChecker.NONE);
	}

	@Override
	public PricingResult findFeasible(LP lp, TimeLimitChecker timeLimitChecker) {
		lastReusableSubtreeArcEliminationBounds = null;
		if (timeLimitChecker != null && timeLimitChecker.isTimeLimitReached()) {
			return PricingResult.noImprovement("Time limit reached before repair pricing");
		}
		if (!config.enableBidirectionalPricing || !config.useGCNGBBStyleNgDssrPricing) {
			return PricingResult.noImprovement("GCNGBB-style ng-DSSR bidirectional pricing disabled");
		}
		GCNGBBStyleBidirectionalNgDssr gc = new GCNGBBStyleBidirectionalNgDssr(data, config,
				midpointProbeReuseByNode);
		ArrayList<TWETColumn> columns = gc.solve(lp, timeLimitChecker);
		if (columns.isEmpty()) {
			lastReusableSubtreeArcEliminationBounds = gc.reusableSubtreeArcEliminationBounds();
			return PricingResult.noImprovement(gc.getLastMessage())
					.withCertifiedInternalReducedCost(gc.getLastRelaxedRoundBestReducedCost());
		}
		return new PricingResult(columns, true, gc.getLastMessage())
				.withCertifiedInternalReducedCost(gc.getLastRelaxedRoundBestReducedCost());
	}
	@Override
	public CompletionBoundSubtreeArcEliminator.PreparedBounds getReusableSubtreeArcEliminationBounds() {
		return lastReusableSubtreeArcEliminationBounds;
	}

	@Override
	public void reset() {
		lastReusableSubtreeArcEliminationBounds = null;
		if (!config.bidirectionalMidpointProbeReuseWithinNode) {
			midpointProbeReuseByNode.clear();
		}
	}

	@Override
	public boolean supportsFeasibilityPhaseOneObjective() {
		return true;
	}

	@Override
	public String getName() {
		return "GCNGBBStyleNgDssrPricing";
	}
}
