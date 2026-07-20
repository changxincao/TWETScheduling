package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * ng-relaxation + DSSR 涓讳綋涓婄殑 partial-list dominance 瀹為獙鍏ュ彛銆?
 *
 * 2026-06-12: 涓嶅鍒?ng-DSSR labeling 涓绘祦绋嬶紝鍙妸 dominance store 鍒囨崲涓?
 * {@link PartialListDominanceStore}锛岀敤浜庤瀵?bucket partial-list 鍦?ng-set 璇箟涓嬬殑琛ㄧ幇銆?
 */
public class GCNGBBStyleBidirectionalNgDssrPartialDominancePricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;
	private final HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse> midpointProbeReuseByNode;
	private final NgDssrHistoryWarmStart historyWarmStart;

	public GCNGBBStyleBidirectionalNgDssrPartialDominancePricingEngine(Data data, TWETBPCConfig config) {
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
		if (!config.enableBidirectionalPricing || !config.useGCNGBBStyleNgDssrPartialDominancePricing) {
			return PricingResult.noImprovement("GCNGBB-style ng-DSSR partial-list dominance pricing disabled");
		}
		GCNGBBStyleBidirectionalNgDssr gc = new GCNGBBStyleBidirectionalNgDssr(data, config,
				midpointProbeReuseByNode, GCNGBBStyleBidirectionalNgDssr.DominanceBackend.LIST_PARTIAL,
				historyWarmStart);
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
		if (!config.enableBidirectionalPricing || !config.useGCNGBBStyleNgDssrPartialDominancePricing) {
			return PricingResult.noImprovement("GCNGBB-style ng-DSSR partial-list dominance pricing disabled");
		}
		GCNGBBStyleBidirectionalNgDssr gc = new GCNGBBStyleBidirectionalNgDssr(data, config,
				midpointProbeReuseByNode, GCNGBBStyleBidirectionalNgDssr.DominanceBackend.LIST_PARTIAL);
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
		return "GCNGBBStyleNgDssrPartialDominancePricing";
	}
}
