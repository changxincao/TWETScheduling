package TWETBPC.GC;

import java.util.ArrayList;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * 双向 no-cut labeling 定价器入口。
 * <p>
 * 2026-05-20: 该类只新增双向版本，不替换原来的 forward exact pricing。
 * 当前上下文中它会放在启发式定价之后、forward exact 之前；如果双向版没有找到负 reduced-cost
 * 列，后续 forward exact 仍会继续执行，保证基础 no-cut 定价证明不依赖这第一版双向实现。
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
