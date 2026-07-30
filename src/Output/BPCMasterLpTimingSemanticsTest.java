package Output;

/**
 * 验证真实 CPLEX model build 与 strong trial Java setup 使用独立统计口径。
 */
public final class BPCMasterLpTimingSemanticsTest {

	private BPCMasterLpTimingSemanticsTest() {
	}

	public static void main(String[] args) {
		BPCTraceSummary summary = new BPCTraceSummary();
		summary.onMasterLpBuild(null, "model", 10, 20, 30L);
		summary.onStrongTrialSetup(null, "setup", 10, 20, 40L);

		assertValue(summary.getMasterLpBuildTimeNanos().get("model"), 30L, "model build time");
		assertValue(summary.getMasterLpBuildCallCount().get("model"), 1L, "model build calls");
		assertValue(summary.getStrongTrialSetupTimeNanos().get("setup"), 40L, "strong setup time");
		assertValue(summary.getStrongTrialSetupCallCount().get("setup"), 1L, "strong setup calls");
		if (summary.getMasterLpBuildTimeNanos().containsKey("setup")
				|| summary.getStrongTrialSetupTimeNanos().containsKey("model")) {
			throw new AssertionError("model build and strong setup statistics must remain separate");
		}
		System.out.println("BPCMasterLpTimingSemanticsTest passed");
	}

	private static void assertValue(Number actual, long expected, String context) {
		if (actual == null || actual.longValue() != expected) {
			throw new AssertionError(context + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
