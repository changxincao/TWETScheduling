package TWETBPC.GC;

import Common.PiecewiseLinearFunction.Direction;

/** IncrementalSourcedDominanceGraph 的创建与统计入口。 */
final class IncrementalSourcedDominanceGraphs {

	private IncrementalSourcedDominanceGraphs() {
	}

	static DominanceStore create(Direction direction, boolean partialDominance) {
		return new IncrementalSourcedDominanceGraph(direction, partialDominance);
	}

	/** 调用方确认使用新图 partial 后，在 label 真正参与扩展或 join 前应用累计裁剪。 */
	static void prepareLabelForUse(DominanceStore store, Label label) {
		((IncrementalSourcedDominanceGraph) store).prepareLabelForUse(label);
	}

	static void resetStatistics() {
		IncrementalSourcedDominanceGraph.resetStatistics();
	}

	static void setDiagnosticContext(String context) {
		IncrementalSourcedDominanceGraph.setDiagnosticContext(context);
	}

	static String statisticsSummary() {
		return IncrementalSourcedDominanceGraph.statisticsSummary();
	}
}
