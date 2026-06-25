package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * ng-relaxation + DSSR 实验版半域双向定价入口。
 * <p>
 * 2026-06-09: 该入口只用于验证 ng/DSSR 对当前 GCNGBB-style 半域 pricing 的影响，默认关闭。
 * 主 elementary pricing、partial dominance 实验和本入口互相独立，便于定位性能和正确性差异。
 */
public class GCNGBBStyleBidirectionalNgDssrPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;
	private final HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse> midpointProbeReuseByNode;

	public GCNGBBStyleBidirectionalNgDssrPricingEngine(Data data, TWETBPCConfig config) {
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
	public String getName() {
		return "GCNGBBStyleNgDssrPricing";
	}
}
