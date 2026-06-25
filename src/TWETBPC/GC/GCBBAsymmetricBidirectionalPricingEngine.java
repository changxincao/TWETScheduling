package TWETBPC.GC;

import java.util.ArrayList;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * 2026-05-28: GCBB-style 非对称动态 half-way 双向 pricing 实验入口。
 */
public class GCBBAsymmetricBidirectionalPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;

	public GCBBAsymmetricBidirectionalPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	@Override
	public PricingResult price(LP lp) {
		return price(lp, TimeLimitChecker.NONE);
	}

	@Override
	public PricingResult price(LP lp, TimeLimitChecker timeLimitChecker) {
		if (!config.enableBidirectionalPricing) {
			return PricingResult.noImprovement("GCBB asymmetric bidirectional pricing disabled");
		}
		GCBBAsymmetricBidirectional gc = new GCBBAsymmetricBidirectional(data, config);
		ArrayList<TWETColumn> columns = gc.solve(lp, timeLimitChecker);
		if (columns.isEmpty()) {
			return PricingResult.noImprovement(gc.getLastMessage());
		}
		return new PricingResult(columns, true, gc.getLastMessage());
	}

	@Override
	public String getName() {
		return "GCBBAsymmetricBidirectionalPricing";
	}
}
