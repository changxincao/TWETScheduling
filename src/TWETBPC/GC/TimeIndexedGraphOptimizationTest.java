package TWETBPC.GC;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;

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
		testExactPricingRejectsNonIntegerGrid();
		testCompactWindowConsumptionBoundaries();
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

		// allowed-complement 可以来自较小的父节点 horizon；子节点在更大时间上追加的
		// forbidden overlay 必须同时被直接查询和热循环 lookup 看见。
		node.forbidTimeIndexedPricingOnlyArc(1, 2, horizon + 2);
		Node.TimeIndexedArcLookup expandedLookup = node.createTimeIndexedPricingOnlyArcLookup();
		if (!node.isTimeIndexedPricingOnlyArcForbidden(1, 2, horizon + 2)
				|| !expandedLookup.isForbidden(1, 2, horizon + 2)) {
			throw new AssertionError("allowed-complement ignored an arc forbidden above its stored horizon");
		}
		assertLookupMatches(node, expandedLookup, pairWidth, horizon + 3);
	}

	private static void testStaticPricingDataMatchesInstance() throws Exception {
		Data data = loadData();
		TimeIndexedGraphPricingEngine.StaticPricingData pricingData =
				new TimeIndexedGraphPricingEngine.StaticPricingData(data);
		int horizon = pricingData.penaltyByJobTime[0].length - 1;
		for (int from = 0; from <= data.n; from++) {
			for (int to = 1; to <= data.n; to++) {
				int expectedDuration =
						(int) Math.ceil(data.getSetUp(from, to) + data.getProcessT(to) - 1e-9);
				if (pricingData.durationByArc[from][to] != expectedDuration) {
					throw new AssertionError("duration cache mismatch");
				}
			}
		}
		for (int job = 1; job <= data.n; job++) {
			for (int time = 0; time <= horizon; time++) {
				double expected = data.penaltyFunction[job].evaluate(time);
				double actual = pricingData.penaltyByJobTime[job][time];
				boolean feasible = time >= data.hardWindowStart[job] && time <= data.hardWindowEnd[job]
						&& !Utility.isBigMValue(expected);
				if (!feasible) {
					if (actual < 5e99) {
						throw new AssertionError("penalty cache accepted an infeasible completion");
					}
				} else if (Math.abs(expected - actual) > 1e-8 * Math.max(1.0, Math.abs(expected))) {
					throw new AssertionError("penalty cache mismatch");
				}
			}
		}
	}

	private static void testExactPricingRejectsNonIntegerGrid() throws Exception {
		Data data = loadData();
		data.p[1] += 0.5;
		data.setPenaltyFunctions();
		if (data.isExactIntegerTimeInstance()) {
			throw new AssertionError("fractional processing time was not detected");
		}
		TWETBPCConfig config = new TWETBPCConfig();
		try {
			new TimeIndexedGraphPricingEngine(data, config);
			throw new AssertionError("no-cut exact pricing accepted a non-integer grid");
		} catch (IllegalArgumentException expected) {
			// expected
		}
		try {
			new TimeIndexedGraphRank1CutPricingEngine(data, config);
			throw new AssertionError("rank-1 exact pricing accepted a non-integer grid");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * 2026-07-14: compact hull 只供 pre-heuristic/fixing 等受限入口使用；正式 exact 图
	 * 必须继续按 hard window 和精确时空禁弧构造，不能再次把 job hull 套到 exact horizon 上。
	 */
	private static void testCompactWindowConsumptionBoundaries() throws Exception {
		Data data = loadData();
		TWETBPCConfig config = new TWETBPCConfig();
		config.enableTimeIndexedGraphDualWindow = false;
		config.useTimeIndexedGraphPricing = true;
		config.enableTimeIndexedPreHeuristicPricing = true;
		config.maxExactPricingColumns = 0;
		config.timeIndexedGraphMaxExactPricingColumns = 0;
		config.timeIndexedPreHeuristicColumnLimit = 0;

		Node node = new Node(data, new ArrayList<Integer>(), new ArrayList<Integer>(), 0.0);
		int compactHorizon = 0;
		int hardHorizon = 0;
		for (int job = 1; job <= data.n; job++) {
			int compactTime = Math.max(0, (int) Math.ceil(data.hardWindowStart[job] - 1e-9));
			node.tightenTimeIndexedPricingWindow(job, compactTime, compactTime);
			compactHorizon = Math.max(compactHorizon, compactTime);
			hardHorizon = Math.max(hardHorizon,
					(int) Math.ceil(Math.min(data.CmaxH, data.hardWindowEnd[job]) - 1e-9));
		}
		if (compactHorizon >= hardHorizon) {
			throw new AssertionError("test instance does not distinguish compact and hard horizons");
		}

		LP lp = new LP(data, new Pool(data), new CutPool(), config, new OutsourcingPool(data));
		lp.construct(node, node.seedColumnIds);
		String exactMessage = new TimeIndexedGraphPricingEngine(data, config).price(lp).getMessage();
		String preHeuristicMessage = TimeIndexedGraphPricingEngine.preHeuristic(data, config).price(lp).getMessage();
		assertMessageHorizon("formal no-cut exact", exactMessage, hardHorizon);
		assertMessageHorizon("time-indexed pre-heuristic", preHeuristicMessage, compactHorizon);

		TimeIndexedGraphRank1CutPricingEngine rank1 = new TimeIndexedGraphRank1CutPricingEngine(data, config);
		Method computeGraphWindow = TimeIndexedGraphRank1CutPricingEngine.class
				.getDeclaredMethod("computeGraphWindow", Data.class, LP.class);
		computeGraphWindow.setAccessible(true);
		Object rank1Window = computeGraphWindow.invoke(rank1, data, lp);
		Field horizon = rank1Window.getClass().getDeclaredField("horizon");
		horizon.setAccessible(true);
		if (horizon.getInt(rank1Window) != hardHorizon) {
			throw new AssertionError("rank-1 exact consumed compact horizon: " + horizon.getInt(rank1Window)
					+ " != " + hardHorizon);
		}
	}

	private static void assertMessageHorizon(String label, String message, int expectedHorizon) {
		if (message == null || !message.contains("horizon=" + expectedHorizon + ",")) {
			throw new AssertionError(label + " used unexpected graph window: " + message);
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
