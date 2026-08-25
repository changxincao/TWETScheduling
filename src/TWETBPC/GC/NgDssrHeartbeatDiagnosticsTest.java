package TWETBPC.GC;

/** ng-DSSR progress 心跳只按固定 pop 批次检查时间，避免每个 label 调用 nanoTime。 */
public final class NgDssrHeartbeatDiagnosticsTest {

	private NgDssrHeartbeatDiagnosticsTest() {
	}

	public static void main(String[] args) {
		require(!GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(1L, 20000L), "first pop");
		require(!GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(19999L, 20000L),
				"before boundary");
		require(GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(20000L, 20000L),
				"first boundary");
		require(!GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(20001L, 40000L),
				"after boundary");
		require(GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(40000L, 40000L),
				"second boundary");
		System.out.println("NgDssrHeartbeatDiagnosticsTest passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
