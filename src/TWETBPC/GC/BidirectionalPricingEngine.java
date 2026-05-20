package TWETBPC.GC;

import java.util.ArrayList;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * 双向 no-cut labeling 定价器入口。
 * <p>
 * 2026-05-20: 该类是 exact pricing 层的双向实现。当前通过
 * {@code TWETBPCConfig.enableBidirectionalPricing} 和原单向 forward exact 二选一，
 * 不再把双向和单向串成顺序兜底。
 */
public class BidirectionalPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;

	public BidirectionalPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	@Override
	public PricingResult price(LP lp) {
		if (!config.enableBidirectionalPricing) {
			return PricingResult.noImprovement("Bidirectional pricing disabled");
		}
		GCBidirectional gc = new GCBidirectional(data, config);
		ArrayList<TWETColumn> columns = gc.solve(lp);
		if (columns.isEmpty()) {
			return PricingResult.noImprovement(gc.getLastMessage());
		}
		return new PricingResult(columns, true, gc.getLastMessage());
	}

	@Override
	public String getName() {
		return "BidirectionalPricing";
	}
}
