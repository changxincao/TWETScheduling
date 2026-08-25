package Common.formal;

import TWETBPC.TWETSolveStatus;

/** 验证正式runner只把可汇总的求解状态交给调度器标记为SUCCESS。 */
public final class FormalExperimentRunnerStatusTest {

	private FormalExperimentRunnerStatusTest() {
	}

	public static void main(String[] args) {
		assertReportable(TWETSolveStatus.FINISHED, true);
		assertReportable(TWETSolveStatus.ROOT_PROCESSED, true);
		assertReportable(TWETSolveStatus.TIME_LIMIT, true);
		assertReportable(TWETSolveStatus.NODE_LIMIT, true);
		assertReportable(TWETSolveStatus.FAILED, false);
		assertReportable(TWETSolveStatus.INITIALIZED, false);
		System.out.println("FormalExperimentRunnerStatusTest passed");
	}

	private static void assertReportable(TWETSolveStatus status, boolean expected) {
		boolean actual = FormalExperimentRunner.isReportableSolveStatus(status);
		if (actual != expected) {
			throw new AssertionError(status + " reportable=" + actual + ", expected=" + expected);
		}
	}
}
