package Output;

import java.util.Collections;

import Basic.Data;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/**
 * 验证根节点认证界与根处理时间使用独立统计口径。
 */
public final class BPCPricingClosureTraceTest {

	private BPCPricingClosureTraceTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", false, true);
		Node root = new Node(data, Collections.emptyList(), Collections.emptyList(), 1e100);
		root.id = 1;

		BPCTraceSummary completed = new BPCTraceSummary();
		completed.onSolveStarted("completed-root");
		completed.onPricingClosure(root, 100.0, 100.0, 0);
		completed.onPricingClosure(root, 125.0, 125.0, 10);
		completed.onPricingClosure(root, 120.0, 125.0, 8);
		assertClose(125.0, completed.getRootBound(), "strongest pricing closure");
		assertClose(0.0, completed.getRootSolveTimeSeconds(), "closure must not finish root timing");

		Thread.sleep(1L);
		TWETMasterSolution finalSolution = new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION,
				Collections.emptyMap(), 123.0, false, "test");
		completed.onMasterSolved(root, finalSolution, 0, 8, 123.0, 200.0, 0, 0, 0, false);
		assertClose(125.0, completed.getRootBound(), "weaker final LP must not replace certified root bound");
		assertPositive(completed.getRootSolveTimeSeconds(), "completed root timing");

		BPCTraceSummary timedOut = new BPCTraceSummary();
		timedOut.onSolveStarted("timed-out-root");
		timedOut.onPricingClosure(root, 110.0, 110.0, 0);
		assertClose(0.0, timedOut.getRootSolveTimeSeconds(), "closure before timeout");
		Thread.sleep(1L);
		timedOut.onNodeClosed(root, "time_limit", 0);
		assertClose(110.0, timedOut.getRootBound(), "timeout root bound");
		assertPositive(timedOut.getRootSolveTimeSeconds(), "timed-out root timing");

		System.out.println("BPCPricingClosureTraceTest passed");
	}

	private static void assertClose(double expected, double actual, String context) {
		if (Math.abs(expected - actual) > 1e-9) {
			throw new AssertionError(context + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertPositive(double value, String context) {
		if (!(value > 0.0)) {
			throw new AssertionError(context + ": expected positive value, actual=" + value);
		}
	}
}
