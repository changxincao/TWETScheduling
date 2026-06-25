package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * partial-list dominance 实验版半域双向定价入口。
 * <p>
 * 2026-06-09: 该入口只替换 dominance backend，不接 ng/DSSR/SRI。默认不启用，用于和当前
 * paper dominance graph 版本对照 partial dominance 的开销和剪枝效果。
 */
public class GCNGBBStyleBidirectionalPartialDominancePricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;
	private final boolean diagnosticForceEnabled;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;
	private final HashMap<Integer, GCNGBBStyleBidirectionalPartialDominance.MidpointProbeNodeReuse> midpointProbeReuseByNode;

	public GCNGBBStyleBidirectionalPartialDominancePricingEngine(Data data, TWETBPCConfig config) {
		this(data, config, false);
	}

	/** 仅供同一 dual 下的 whole/partial dominance 停止条件对拍。 */
	public GCNGBBStyleBidirectionalPartialDominancePricingEngine(Data data, TWETBPCConfig config,
			boolean diagnosticForceEnabled) {
		this.data = data;
		this.config = config;
		this.diagnosticForceEnabled = diagnosticForceEnabled;
		this.midpointProbeReuseByNode = new HashMap<Integer, GCNGBBStyleBidirectionalPartialDominance.MidpointProbeNodeReuse>();
	}

	@Override
	public PricingResult price(LP lp) {
		return price(lp, TimeLimitChecker.NONE);
	}

	@Override
	public PricingResult price(LP lp, TimeLimitChecker timeLimitChecker) {
		lastReusableSubtreeArcEliminationBounds = null;
		if (!config.enableBidirectionalPricing
				|| (!diagnosticForceEnabled && !config.useGCNGBBStylePartialDominancePricing)) {
			return PricingResult.noImprovement("GCNGBB-style partial-dominance bidirectional pricing disabled");
		}
		GCNGBBStyleBidirectionalPartialDominance gc = new GCNGBBStyleBidirectionalPartialDominance(data, config,
				midpointProbeReuseByNode);
		ArrayList<TWETColumn> columns = gc.solve(lp, timeLimitChecker);
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
		return "GCNGBBStylePartialDominancePricing";
	}
}
