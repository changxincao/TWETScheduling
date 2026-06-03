package TWETBPC.GC;

import TWETBPC.LP.LP;

/**
 * 定价器接口。
 *
 * 这层对应旧 BPC 代码中的 GCTabu / GCNGBB 等列生成器。当前先抽象出普通 pricing、
 * repair 阶段 FindFeasible 以及状态 reset 三个入口，方便后续接入启发式定价、精确定价和 DSSR/ng-route。
 */
public interface PricingEngine {

	/**
	 * 在当前节点的正常 RMP 上执行一轮定价。
	 *
	 * @param lp 当前节点的受限主问题对象
	 * @return 定价结果，包含是否找到改进列以及这些列本身
	 */
	PricingResult price(LP lp);

	/**
	 * 2026-05-18: 分支子节点 RMP 暂时不可行时的补列入口。
	 * 默认复用普通 pricing；如果后续 required arc 节点需要更强的定向搜索，可以在具体定价器中覆盖。
	 */
	default PricingResult findFeasible(LP lp) {
		return price(lp);
	}

	/**
	 * 2026-05-18: repair 阶段中，如果前一个定价器已经加列并重解 LP，
	 * 后续定价器可能需要清掉依赖旧 dual 的内部状态。当前基础定价器没有跨轮状态，因此默认不做事；
	 * 后续接入 DSSR/ng-route 时可在具体实现中覆盖。
	 */
	default void reset() {
	}

	default CompletionBoundSubtreeArcEliminator.PreparedBounds getReusableSubtreeArcEliminationBounds() {
		return null;
	}

	/** @return 定价器名称，主要用于日志和调试。 */
	default boolean repeatFindFeasibleUntilExhausted() {
		return false;
	}

	String getName();

}
