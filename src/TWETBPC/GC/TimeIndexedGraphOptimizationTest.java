package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import Basic.Data;
import TWETBPC.LP.Node;

/**
 * 2026-07-14: time-indexed 热路径等价性回归测试，不依赖求解器。
 */
public final class TimeIndexedGraphOptimizationTest {

	private TimeIndexedGraphOptimizationTest() {
	}

	public static void main(String[] args) throws Exception {
		testCompressedPredecessorMatchesFullWaitingChain();
		testTimeIndexedArcLookupMatchesNode();
		testStaticPricingDataMatchesInstance();
		System.out.println("TimeIndexedGraphOptimizationTest passed");
	}

	private static void testCompressedPredecessorMatchesFullWaitingChain() {
		final int states = 20000;
		int[] oldPred = new int[states];
		int[] oldAdded = new int[states];
		int[] compressedPred = new int[states];
		int[] compressedAdded = new int[states];
		Arrays.fill(oldPred, -1);
		Arrays.fill(compressedPred, -1);
		Random random = new Random(20260714L);
		for (int state = 1; state < states; state++) {
			int from = random.nextInt(state);
			int addedJob = random.nextInt(4) == 0 ? 1 + random.nextInt(40) : 0;
			oldPred[state] = from;
			oldAdded[state] = addedJob;
			TimeIndexedGraphPricingEngine.storeCompressedPredecessor(
					compressedPred, compressedAdded, from, state, addedJob);
			if (!trace(oldPred, oldAdded, state).equals(trace(compressedPred, compressedAdded, state))) {
				throw new AssertionError("compressed predecessor changed sequence at state " + state);
			}
		}

		Arrays.fill(oldPred, -1);
		Arrays.fill(oldAdded, 0);
		Arrays.fill(compressedPred, -1);
		Arrays.fill(compressedAdded, 0);
		oldPred[1] = 0;
		oldAdded[1] = 7;
		TimeIndexedGraphPricingEngine.storeCompressedPredecessor(compressedPred, compressedAdded, 0, 1, 7);
		for (int state = 2; state < states; state++) {
			oldPred[state] = state - 1;
			TimeIndexedGraphPricingEngine.storeCompressedPredecessor(
					compressedPred, compressedAdded, state - 1, state, 0);
		}
		if (!trace(oldPred, oldAdded, states - 1).equals(trace(compressedPred, compressedAdded, states - 1))) {
			throw new AssertionError("long waiting chain changed sequence");
		}
		if (traceSteps(compressedPred, states - 1) > 2 || traceSteps(oldPred, states - 1) < states - 1) {
			throw new AssertionError("waiting predecessor chain was not compressed");
		}
	}

	private static void testTimeIndexedArcLookupMatchesNode() throws Exception {
		Data data = loadData();
		Node node = new Node(data, new ArrayList<Integer>(), new ArrayList<Integer>(), 0.0);
		Node.TimeIndexedArcLookup empty = node.createTimeIndexedPricingOnlyArcLookup();
		if (empty.isForbidden(1, 2, 3)) {
			throw new AssertionError("empty lookup forbids an arc");
		}

		node.forbidTimeIndexedPricingOnlyArc(1, 2, 0);
		node.forbidTimeIndexedPricingOnlyArc(1, 2, 3);
		node.forbidTimeIndexedPricingOnlyArc(3, 0, 2);
		assertLookupMatches(node, node.createTimeIndexedPricingOnlyArcLookup(), data.n + 2, 6);

		int pairWidth = data.n + 2;
		int horizon = 5;
		int total = pairWidth * pairWidth * (horizon + 1);
		BitSet forbidden = new BitSet(total);
		forbidden.set(0, total);
		forbidden.clear(timeArcIndex(pairWidth, 1, 2, 1));
		forbidden.clear(timeArcIndex(pairWidth, 4, 0, 5));
		node.replaceTimeIndexedPricingOnlyArcSet(forbidden, pairWidth, horizon);
		assertLookupMatches(node, node.createTimeIndexedPricingOnlyArcLookup(), pairWidth, horizon);
	}

	private static void testStaticPricingDataMatchesInstance() throws Exception {
		Data data = loadData();
		TimeIndexedGraphPricingEngine.StaticPricingData pricingData =
				new TimeIndexedGraphPricingEngine.StaticPricingData(data);
		Random random = new Random(42L);
		int horizon = pricingData.penaltyByJobTime[0].length - 1;
		for (int sample = 0; sample < 1000; sample++) {
			int from = random.nextInt(data.n + 1);
			int to = 1 + random.nextInt(data.n);
			int expectedDuration = (int) Math.ceil(data.getSetUp(from, to) + data.getProcessT(to) - 1e-9);
			if (pricingData.durationByArc[from][to] != expectedDuration) {
				throw new AssertionError("duration cache mismatch");
			}
			int time = random.nextInt(horizon + 1);
			if (time >= data.hardWindowStart[to] && time <= data.hardWindowEnd[to]) {
				double expected = data.penaltyFunction[to].evaluate(time);
				double actual = pricingData.penaltyByJobTime[to][time];
				if (Math.abs(expected - actual) > 1e-8 * Math.max(1.0, Math.abs(expected))) {
					throw new AssertionError("penalty cache mismatch");
				}
			}
		}
	}

	private static Data loadData() throws Exception {
		return new Data("data/40-2/wet040_001_2m.dat", true, true);
	}

	private static void assertLookupMatches(Node node, Node.TimeIndexedArcLookup lookup,
			int pairWidth, int horizon) {
		for (int from = 0; from < pairWidth; from++) {
			for (int to = 0; to < pairWidth; to++) {
				for (int time = 0; time <= horizon; time++) {
					boolean expected = node.isTimeIndexedPricingOnlyArcForbidden(from, to, time);
					boolean actual = lookup.isForbidden(from, to, time);
					if (expected != actual) {
						throw new AssertionError("lookup mismatch at " + from + "->" + to + "@" + time);
					}
				}
			}
		}
	}

	private static int timeArcIndex(int pairWidth, int from, int to, int time) {
		return time * pairWidth * pairWidth + from * pairWidth + to;
	}

	private static List<Integer> trace(int[] predecessor, int[] addedJobs, int state) {
		ArrayList<Integer> reversed = new ArrayList<Integer>();
		for (int current = state; current >= 0; current = predecessor[current]) {
			if (addedJobs[current] > 0) {
				reversed.add(Integer.valueOf(addedJobs[current]));
			}
		}
		ArrayList<Integer> result = new ArrayList<Integer>(reversed.size());
		for (int i = reversed.size() - 1; i >= 0; i--) {
			result.add(reversed.get(i));
		}
		return result;
	}

	private static int traceSteps(int[] predecessor, int state) {
		int steps = 0;
		for (int current = state; current >= 0; current = predecessor[current]) {
			steps++;
		}
		return steps;
	}
}