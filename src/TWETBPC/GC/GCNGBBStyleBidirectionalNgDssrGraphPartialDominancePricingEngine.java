package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * 基于 ng-DSSR 主体的 graph partial dominance 实验入口。
 *
 * 2026-06-11: 不复制 labeling 主流程，只切换 dominance store。ng-set 设为满集时，该入口可按
 * elementary 口径测试 graph partial dominance 的正确性与效率。
 */
public class GCNGBBStyleBidirectionalNgDssrGraphPartialDominancePricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;
	private final HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse> midpointProbeReuseByNode;

	public GCNGBBStyleBidirectionalNgDssrGraphPartialDominancePricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.midpointProbeReuseByNode = new HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse>();
	}

	@Override
	public PricingResult price(LP lp) {
		lastReusableSubtreeArcEliminationBounds = null;
		if (!config.enableBidirectionalPricing || !config.useGCNGBBStyleNgDssrGraphPartialDominancePricing) {
			return PricingResult.noImprovement("GCNGBB-style ng-DSSR graph partial-dominance pricing disabled");
		}
		GCNGBBStyleBidirectionalNgDssr gc = new GCNGBBStyleBidirectionalNgDssr(data, config,
				midpointProbeReuseByNode, true);
		ArrayList<TWETColumn> columns = gc.solve(lp);
		if (columns.isEmpty()) {
			lastReusableSubtreeArcEliminationBounds = gc.reusableSubtreeArcEliminationBounds();
			return PricingResult.noImprovement(gc.getLastMessage());
		}
		return new PricingResult(columns, true, gc.getLastMessage());
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
		return "GCNGBBStyleNgDssrGraphPartialDominancePricing";
	}
}
