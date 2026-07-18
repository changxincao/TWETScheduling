package TWETBPC.GC;

import java.util.Arrays;
import java.util.List;

/** Focused checks for heuristic/exact sequence-distance diagnostics. */
public final class HeuristicPricingDiagnosticTraceTest {

	private HeuristicPricingDiagnosticTraceTest() {
	}

	public static void main(String[] args) {
		assertKind("SAME", list(1, 2, 3), list(1, 2, 3));
		assertKind("ONE_ADD", list(1, 3), list(1, 2, 3));
		assertKind("ONE_REMOVE", list(1, 2, 3), list(1, 3));
		assertKind("ONE_EXCHANGE", list(1, 2, 3), list(1, 4, 3));
		assertKind("ONE_RELOCATE", list(1, 2, 3, 4), list(1, 3, 4, 2));
		assertKind("ONE_SWAP", list(1, 2, 3, 4), list(4, 2, 3, 1));
		assertKind("MULTI_STEP", list(1, 2, 3), list(4, 5, 6));
		assertKind("MULTI_STEP", list(1, 2), list(1, 3, 4));
		System.out.println("HeuristicPricingDiagnosticTraceTest passed");
	}

	private static List<Integer> list(Integer... jobs) {
		return Arrays.asList(jobs);
	}

	private static void assertKind(String expected, List<Integer> from, List<Integer> to) {
		String actual = HeuristicPricingDiagnosticTrace.compare(from, to).kind;
		if (!expected.equals(actual)) {
			throw new AssertionError("expected=" + expected + " actual=" + actual + " from=" + from + " to=" + to);
		}
	}
}
