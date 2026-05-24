package TWETBPC.GC;

import java.util.ArrayList;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * 精确定价器。
 * <p>
 * 第一版使用单向 forward labeling，并通过 dominance graph 做完整 set dominance。
 * 暂不接 SRI cut、partial dominance、ng-route、DSSR 或双向拼接。
 */
public class ExactPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;

	public ExactPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	@Override
	public PricingResult price(LP lp) {
		GC gc = new GC(data, config);
		ArrayList<TWETColumn> columns = gc.solve(lp);
		if (columns.isEmpty()) {
			return PricingResult.noImprovement(gc.getLastMessage());
		}
		return new PricingResult(columns, true, gc.getLastMessage());
	}

	@Override
	public String getName() {
		return "ExactPricing";
	}

}
