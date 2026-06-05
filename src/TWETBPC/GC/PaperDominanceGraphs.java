package TWETBPC.GC;

import Common.PiecewiseLinearFunction.Direction;

/**
 * Paper dominance graph 的创建与统计入口。
 *
 * 2026-06-05: 保留原 DFS backend，同时增加 indexed backend 做精确候选定位实验。
 * 通过 `twet.bpc.paperGraphBackend=indexed` 切换，便于同一 pricing 代码做 old/indexed 对照。
 */
final class PaperDominanceGraphs {

	private static final String BACKEND_INDEXED = "indexed";

	private PaperDominanceGraphs() {
	}

	static DominanceStore create() {
		return create(Direction.FORWARD);
	}

	static DominanceStore create(Direction direction) {
		return useIndexedBackend() ? new IndexedPaperDominanceGraph(direction) : new PaperDominanceGraph(direction);
	}

	static void resetStatistics() {
		PaperDominanceGraph.resetStatistics();
		IndexedPaperDominanceGraph.resetStatistics();
	}

	static String statisticsSummary() {
		return useIndexedBackend() ? IndexedPaperDominanceGraph.statisticsSummary()
				: PaperDominanceGraph.statisticsSummary();
	}

	static boolean useIndexedBackend() {
		return BACKEND_INDEXED.equalsIgnoreCase(System.getProperty("twet.bpc.paperGraphBackend", "dfs"));
	}
}
