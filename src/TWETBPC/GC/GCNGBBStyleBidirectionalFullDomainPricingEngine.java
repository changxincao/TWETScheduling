package TWETBPC.GC;

import java.util.ArrayList;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * 2026-05-28: GCNGBB-style 双向 pricing 的 full-domain 对照入口。
 * 仅用于测试标签函数不做 half-domain 裁剪时的额外函数操作成本，默认正式路径不使用。
 */
public class GCNGBBStyleBidirectionalFullDomainPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;

	public GCNGBBStyleBidirectionalFullDomainPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	@Override
	public PricingResult price(LP lp) {
		if (!config.enableBidirectionalPricing) {
			return PricingResult.noImprovement("GCNGBB-style full-domain bidirectional pricing disabled");
		}
		GCNGBBStyleBidirectionalFullDomain gc = new GCNGBBStyleBidirectionalFullDomain(data, config);
		ArrayList<TWETColumn> columns = gc.solve(lp);
		if (columns.isEmpty()) {
			return PricingResult.noImprovement(gc.getLastMessage());
		}
		return new PricingResult(columns, true, gc.getLastMessage());
	}

	@Override
	public String getName() {
		return "GCNGBBStyleBidirectionalFullDomainPricing";
	}
}
