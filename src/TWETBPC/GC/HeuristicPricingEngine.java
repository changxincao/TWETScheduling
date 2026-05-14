package TWETBPC.GC;

import TWETBPC.LP.LP;

/**
 * 启发式定价器占位实现。
 * <p>
 * 它在角色上对应旧参考代码中的 GCTabu 一类“先快搜负 reduced cost 列”的模块，
 * 但当前阶段尚未写入 TWET 版具体算法，因此只保留接口和调用位置。
 */
public class HeuristicPricingEngine implements PricingEngine {

	@Override
	/**
	 * 进行一次启发式定价。
	 * <p>
	 * 当前返回空结果，表示“框架已预留，算法待补”。
	 */
	public PricingResult price(LP lp) {
		return PricingResult.noImprovement("Heuristic pricing placeholder");
	}

	@Override
	/** @return 定价器名称 */
	public String getName() {
		return "HeuristicPricing";
	}

}
