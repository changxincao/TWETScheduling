package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * GCNGBB-style 双向 no-cut labeling 定价器入口。
 * 该入口用于试验“先完整生成两侧 label，最后统一 join”的旧 VRP 双向框架。
 */
public class GCNGBBStyleBidirectionalPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;
	private final HashMap<Integer, Double> midpointProbeReuseByNode;

	public GCNGBBStyleBidirectionalPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.midpointProbeReuseByNode = new HashMap<Integer, Double>();
	}

	@Override
	public PricingResult price(LP lp) {
		lastReusableSubtreeArcEliminationBounds = null;
		if (!config.enableBidirectionalPricing) {
			return PricingResult.noImprovement("GCNGBB-style bidirectional pricing disabled");
		}
		GCNGBBStyleBidirectional gc = new GCNGBBStyleBidirectional(data, config, midpointProbeReuseByNode);
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
		midpointProbeReuseByNode.clear();
	}

	@Override
	public String getName() {
		return "GCNGBBStyleBidirectionalPricing";
	}
}
