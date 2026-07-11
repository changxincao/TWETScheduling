package TWETBPC.GC;

import Common.PiecewiseLinearFunction.Direction;

/** IncrementalSourcedDominanceGraph 的创建与统计入口。 */
final class IncrementalSourcedDominanceGraphs {

	private IncrementalSourcedDominanceGraphs() {
	}

	static DominanceStore create(Direction direction, boolean partialDominance) {
		return new IncrementalSourcedDominanceGraph(direction, partialDominance);
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
