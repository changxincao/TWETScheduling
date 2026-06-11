package TWETBPC.GC;

import Common.PiecewiseLinearFunction.Direction;

/**
 * 在 paper dominance graph 上叠加 partial dominance 的实验 store。
 *
 * 2026-06-11: 图结构、reachable-set 前后继和 envelope 传播沿用
 * {@link PaperDominanceGraph}，只把完整函数占优删除改为先裁剪被支配区间，裁空后才删除 label。
 */
final class PaperPartialDominanceGraph extends PaperDominanceGraph {

	PaperPartialDominanceGraph(Direction direction) {
		super(direction, true);
	}
}
