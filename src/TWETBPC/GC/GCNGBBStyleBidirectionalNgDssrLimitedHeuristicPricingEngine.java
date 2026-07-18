package TWETBPC.GC;

import java.util.ArrayList;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TimeLimitChecker;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;

/**
 * full-ng 的受限扩展 labeling 启发式。
 * 每个 label 只保留候选函数最小 reduced cost 最好的 K 个扩展方向；无列时不提供闭合证书，
 * 因而 PC 会继续调用后续 exact ng-DSSR。
 */
public class GCNGBBStyleBidirectionalNgDssrLimitedHeuristicPricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;

	public GCNGBBStyleBidirectionalNgDssrLimitedHeuristicPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	@Override
	public PricingResult price(LP lp) {
		return price(lp, TimeLimitChecker.NONE);
	}

	@Override
	public PricingResult price(LP lp, TimeLimitChecker timeLimitChecker) {
		if (!config.enableNgDssrLimitedLabelingHeuristic
				|| config.ngDssrLimitedLabelingExtensionLimit <= 0) {
			return PricingResult.noImprovement("full-ng limited labeling heuristic disabled");
		}
		GCNGBBStyleBidirectionalNgDssr solver = GCNGBBStyleBidirectionalNgDssr.limitedLabelingHeuristic(
				data, config, config.ngDssrLimitedLabelingExtensionLimit);
		ArrayList<TWETColumn> columns = solver.solve(lp,
				timeLimitChecker == null ? TimeLimitChecker.NONE : timeLimitChecker);
		if (columns.isEmpty()) {
			// 这是定向搜索失败，不是对完整内部列族的 exact certificate。
			return PricingResult.noImprovement(solver.getLastMessage());
		}
		return new PricingResult(columns, true, solver.getLastMessage());
	}

	@Override
	public PricingResult findFeasible(LP lp, TimeLimitChecker timeLimitChecker) {
		return price(lp, timeLimitChecker);
	}

	@Override
	public String getName() {
		return "GCNGBBStyleNgDssrLimitedHeuristicPricing";
	}
}