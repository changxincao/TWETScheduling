package TWETBPC.GC;

import java.util.ArrayList;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * 按论文 dominance graph 伪代码组织占优结构的 exact forward pricing。
 * <p>
 * 2026-05-17: 该类和旧 `ExactPricingEngine` 共用同一套 label 扩展逻辑，只替换 terminal job
 * 上的 dominance graph 数据结构。这样后续可以通过配置切换，直接比较“全量扫描版”和“论文图传播版”的效率。
 */
public class PaperDominanceExactPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;

	public PaperDominanceExactPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	@Override
	public PricingResult price(LP lp) {
		GC gc = new GC(data, config, true);
		ArrayList<TWETColumn> columns = gc.solve(lp);
		if (columns.isEmpty()) {
			return PricingResult.noImprovement(gc.getLastMessage());
		}
		return new PricingResult(columns, true, gc.getLastMessage());
	}

	@Override
	public String getName() {
		return "PaperDominanceExactPricing";
	}

}
