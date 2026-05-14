package TWETBPC.GC;

import TWETBPC.LP.LP;

/**
 * 精确定价器占位实现。
 * <p>
 * 它在职责上对应旧参考代码中的 GCNGBB 等精确定价器，
 * 后续会在这里放入 TWET 的标签扩展 / DP / 其它精确定价方法。
 */
public class ExactPricingEngine implements PricingEngine {

	@Override
	/**
	 * 执行一次精确定价。
	 * <p>
	 * 当前仅保留占位接口。
	 */
	public PricingResult price(LP lp) {
		return PricingResult.noImprovement("Exact pricing placeholder");
	}

	@Override
	/** @return 定价器名称 */
	public String getName() {
		return "ExactPricing";
	}

}
