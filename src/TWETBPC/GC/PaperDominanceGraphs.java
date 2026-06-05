package TWETBPC.GC;

import Common.PiecewiseLinearFunction.Direction;

/**
 * Paper dominance graph 的创建与统计入口。
 *
 * 2026-06-05: indexed backend 的端到端收益不稳定，当前统一回到原经典 DFS backend。
 * 实验实现暂时保留在代码库中，但不再通过运行参数参与求解路径。
 */
final class PaperDominanceGraphs {

	private PaperDominanceGraphs() {
	}

	static DominanceStore create() {
		return create(Direction.FORWARD);
	}

	static DominanceStore create(Direction direction) {
		return new PaperDominanceGraph(direction);
	}

	static void resetStatistics() {
		PaperDominanceGraph.resetStatistics();
	}

	static String statisticsSummary() {
		return PaperDominanceGraph.statisticsSummary();
	}
}
