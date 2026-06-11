package TWETBPC.GC;

import Common.PiecewiseLinearFunction.Direction;

/**
 * graph partial dominance 实验 backend 的创建入口。
 *
 * 2026-06-11: 统计计数暂时复用 {@link PaperDominanceGraph} 的全局计数，便于和 normal graph
 * 在同一套诊断字段下对照。
 */
final class PaperPartialDominanceGraphs {

	private PaperPartialDominanceGraphs() {
	}

	static DominanceStore create() {
		return create(Direction.FORWARD);
	}

	static DominanceStore create(Direction direction) {
		return new PaperPartialDominanceGraph(direction);
	}

	static void resetStatistics() {
		PaperDominanceGraph.resetStatistics();
	}

	static void setDiagnosticContext(String context) {
		PaperDominanceGraph.setDiagnosticContext(context);
	}

	static String statisticsSummary() {
		return "paperPartialGraph " + PaperDominanceGraph.statisticsSummary();
	}
}
