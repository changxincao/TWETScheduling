package TWETBPC.GC;

import java.util.ArrayList;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * 2026-05-29: GCBB full-domain node-join 双向 pricing 实验入口。
 * 保留 full-domain label 流程，仅把 final join 改为同一 job 上的 node join。
 */
public class GCBBStyleBidirectionalFullDomainNodeJoinPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;

	public GCBBStyleBidirectionalFullDomainNodeJoinPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	@Override
	public PricingResult price(LP lp) {
		return price(lp, TimeLimitChecker.NONE);
	}

	@Override
	public PricingResult price(LP lp, TimeLimitChecker timeLimitChecker) {
		lastReusableSubtreeArcEliminationBounds = null;
		if (!config.enableBidirectionalPricing) {
			return PricingResult.noImprovement("GCBB full-domain node-join bidirectional pricing disabled");
		}
		GCBBStyleBidirectionalFullDomainNodeJoin gc = new GCBBStyleBidirectionalFullDomainNodeJoin(data, config);
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
	}

	@Override
	public String getName() {
		return "GCBBStyleBidirectionalFullDomainNodeJoinPricing";
	}
}
