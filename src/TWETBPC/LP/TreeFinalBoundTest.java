package TWETBPC.LP;

import java.util.Collections;
import java.util.PriorityQueue;

import Basic.Data;

/**
 * 验证搜索中断时，最终下界不会丢掉已经出队但尚未关闭的当前节点。
 */
public final class TreeFinalBoundTest {

	private TreeFinalBoundTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", false, true);
		PriorityQueue<Node> queue = new PriorityQueue<Node>();
		queue.add(new Node(data, Collections.emptyList(), Collections.emptyList(), 150.0));

		assertClose(140.0, Tree.finalBound(queue, 200.0, 100.0, true, true, 140.0),
				"interrupted current node must retain its strongest inherited or certified bound");
		assertClose(150.0, Tree.finalBound(queue, 200.0, 100.0, true, false, 140.0),
				"timeout between fully closed nodes may use open queue bound");

		queue.add(new Node(data, Collections.emptyList(), Collections.emptyList(), 80.0));
		assertClose(80.0, Tree.finalBound(queue, 200.0, 100.0, true, true, 140.0),
				"smaller sibling bound must remain global bound");

		PriorityQueue<Node> empty = new PriorityQueue<Node>();
		assertClose(140.0, Tree.finalBound(empty, 200.0, 100.0, true, true, 140.0),
				"interrupted current node remains reportable when no sibling is open");
		assertClose(100.0, Tree.finalBound(empty, 200.0, 100.0, true, true, Double.NEGATIVE_INFINITY),
				"unknown current node falls back to the last conservative report");
		assertPositiveInfinity(Tree.finalBound(empty, 200.0, Double.POSITIVE_INFINITY, true, true,
				Double.NEGATIVE_INFINITY), "unknown bound must not be capped to the incumbent");
		assertClose(200.0, Tree.finalBound(empty, 200.0, 100.0, false, false, 140.0),
				"completed search with empty queue");

		PriorityQueue<Node> realCase = new PriorityQueue<Node>();
		realCase.add(new Node(data, Collections.emptyList(), Collections.emptyList(), 74250.0));
		assertClose(74230.24269005848,
				Tree.finalBound(realCase, 75423.0, 63515.99401197584, true, true, 74230.24269005848),
				"a weaker in-node observation must not erase the inherited node bound");

		assertClose(120.0, Tree.strongestReportableNodeBound(Double.NEGATIVE_INFINITY, 120.0),
				"completed exact pricing certificate must survive a later timeout");
		assertClose(130.0, Tree.strongestReportableNodeBound(130.0, 120.0),
				"full pricing closure remains stronger than an earlier observed bound");
		assertClose(130.0, Tree.strongestReportableNodeBound(120.0, 130.0),
				"the strongest certified observed bound must be retained");
		assertNegativeInfinity(Tree.strongestReportableNodeBound(Double.NEGATIVE_INFINITY, Double.NaN),
				"no completed exact pricing certificate must keep the bound unknown");

		System.out.println("TreeFinalBoundTest passed");
	}

	private static void assertClose(double expected, double actual, String context) {
		if (Math.abs(expected - actual) > 1e-9) {
			throw new AssertionError(context + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertNegativeInfinity(double actual, String context) {
		if (actual != Double.NEGATIVE_INFINITY) {
			throw new AssertionError(context + ": actual=" + actual);
		}
	}

	private static void assertPositiveInfinity(double actual, String context) {
		if (actual != Double.POSITIVE_INFINITY) {
			throw new AssertionError(context + ": actual=" + actual);
		}
	}
}
