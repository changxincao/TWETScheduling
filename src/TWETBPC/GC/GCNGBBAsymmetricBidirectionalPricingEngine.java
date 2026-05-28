package TWETBPC.GC;

import java.util.ArrayList;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * 2026-05-28: GCNGBB-style 非对称动态 half-way 双向 pricing 实验入口。
 */
public class GCNGBBAsymmetricBidirectionalPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;

	public GCNGBBAsymmetricBidirectionalPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	@Override
	public PricingResult price(LP lp) {
		if (!config.enableBidirectionalPricing) {
			return PricingResult.noImprovement("GCNGBB asymmetric bidirectional pricing disabled");
		}
		GCNGBBAsymmetricBidirectional gc = new GCNGBBAsymmetricBidirectional(data, config);
		ArrayList<TWETColumn> columns = gc.solve(lp);
		if (columns.isEmpty()) {
			return PricingResult.noImprovement(gc.getLastMessage());
		}
		return new PricingResult(columns, true, gc.getLastMessage());
	}

	@Override
	public String getName() {
		return "GCNGBBAsymmetricBidirectionalPricing";
	}
}
