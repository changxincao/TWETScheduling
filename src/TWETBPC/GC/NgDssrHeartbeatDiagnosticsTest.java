package TWETBPC.GC;

/** ng-DSSR progress 心跳只按固定 pop 批次检查时间，避免每个 label 调用 nanoTime。 */
public final class NgDssrHeartbeatDiagnosticsTest {

	private NgDssrHeartbeatDiagnosticsTest() {
	}

	public static void main(String[] args) {
		require(!GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(1L), "first pop");
		require(!GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(4095L), "before boundary");
		require(GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(4096L), "first boundary");
		require(!GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(4097L), "after boundary");
		require(GCNGBBStyleBidirectionalNgDssr.isProgressHeartbeatCheckDue(8192L), "second boundary");
		System.out.println("NgDssrHeartbeatDiagnosticsTest passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
