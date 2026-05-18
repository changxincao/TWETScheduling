package TWETBPC.GC;

import TWETBPC.LP.LP;

/**
 * 定价器接口。
 * <p>
 * 这层对应旧 BPC 里的 GCTabu / GCNGBB 等列生成器的统一抽象。
 * 区别在于这里先用接口把“定价行为”抽出来，
 * 便于后续按 TWET 需要替换成启发式 pricing、精确定价、混合定价等不同实现。
 */
public interface PricingEngine {

	/**
	 * 在当前节点的 LP 上执行一次定价。
	 *
	 * @param lp 当前节点的受限主问题对象
	 * @return 定价结果，包含是否找到改进列以及这些列本身
	 */
	PricingResult price(LP lp);

	/**
	 * 2026-05-18: 分支子节点 RMP 暂时不可行时的补列入口。
	 * 默认复用普通 pricing；后续如果要完全贴近旧 VRP 的 GCTabu/GCNGBB FindFeasible，
	 * 可以在具体定价器里覆盖这个方法，做 required arc 定向搜索或更强的可行列修复。
	 */
	default PricingResult findFeasible(LP lp) {
		return price(lp);
	}

	/** @return 定价器名称，主要用于日志和调试 */
	String getName();

}
