package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * ng-relaxation + DSSR 主体上的 partial-list dominance 实验入口。
 *
 * 2026-06-12: 不复制 ng-DSSR labeling 主流程，只把 dominance store 切换为
 * {@link PartialListDominanceStore}，用于观察 bucket partial-list 在 ng-set 语义下的表现。
 */
public class GCNGBBStyleBidirectionalNgDssrPartialDominancePricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;
	private final HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse> midpointProbeReuseByNode;

	public GCNGBBStyleBidirectionalNgDssrPartialDominancePricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.midpointProbeReuseByNode = new HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse>();
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
	public String getName() {
		return "GCNGBBStyleNgDssrPartialDominancePricing";
	}
}
