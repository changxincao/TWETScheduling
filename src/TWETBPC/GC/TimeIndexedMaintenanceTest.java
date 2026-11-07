package TWETBPC.GC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.SequenceSignature;

/** 检查Phase-I辅助界隔离、候选堆压缩，以及fixing前后完整禁弧/窗口快照。 */
public final class TimeIndexedMaintenanceTest {

	private TimeIndexedMaintenanceTest() {
	}

	public static void main(String[] args) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		String group = args.length == 0 ? "all" : args[0];
		if ("all".equals(group) || "phase".equals(group)) testPhaseOneHelper();
		if ("all".equals(group) || "heap".equals(group)) testCandidateUpdates();
		if ("all".equals(group) || "heap".equals(group) || "snapshot".equals(group)) {
			testCandidatePricing(!"snapshot".equals(group));
		}
		if ("all".equals(group) || "fixing".equals(group)) testCleanupDistanceReuse();
		if ("all".equals(group) || "fixing".equals(group) || "snapshot".equals(group)) testFixingSnapshots();
		System.out.println("TimeIndexedMaintenanceTest " + group + " passed");
	}

	private static Data data(String rows) throws Exception {
		Path input = Files.createTempFile("twet-ti-maintenance-", ".dat");
		try {
			Files.writeString(input, rows);
			return new Data(input.toString(), true, true);
		} finally {
			Files.deleteIfExists(input);
		}
	}

	private static TWETBPCConfig config() {
		TWETBPCConfig c = new TWETBPCConfig();
		c.useTimeIndexedGraphPricing = true;
		c.enableTimeIndexedGraphDualWindow = false;
		c.timeIndexedGraphMaxExactPricingColumns = 300;
		return c;
	}

	private static LP lp(Data d, TWETBPCConfig c, double[] duals, double machine) throws Exception {
		LP lp = new LP(d, new Pool(d), new CutPool(), c, new OutsourcingPool(d));
		lp.construct(new Node(d, new ArrayList<>(), new ArrayList<>(), 0), new ArrayList<>());
		field(lp, "jobDual").set(lp, duals.clone());
		field(lp, "machineDual").setDouble(lp, machine);
		return lp;
	}

	private static void testPhaseOneHelper() throws Exception {
		Data d = data("2 1\n1 0 3 1 1\n1 1 1 1 1\nSETUP\n"
				+ "0 0 0\n0 0 0\n0 0 0\nOUTSOURCING_COST\n3 3\n");
		TWETBPCConfig c = config();
		c.timeIndexedCompletionBoundScalarEnhancement = true;
		c.timeIndexedCompletionBoundWindowTightening = true;
		c.timeIndexedCompletionBoundInRoundArcFixing = true;
		LP lp = lp(d, c, new double[] {0, 1, 1}, 0);
		try {
			lp.getNode().depth = 3;
			lp.getNode().forbidArc(1, lp.getNode().sinkId());
			lp.getNode().forbidOutsourcingJob(1);
			lp.getNode().forbidOutsourcingJob(2);
			lp.setFeasibilityRepairMode(true);
			double[] start = Arrays.copyOf(d.hardWindowStart, d.n + 1);
			double[] end = Arrays.copyOf(d.hardWindowEnd, d.n + 1);
			var ordinary = TimeIndexedScalarCompletionBound.build(d, c, lp, d.CmaxH, start, end);
			check(ordinary != null, "ordinary finite-M objective lost its helper");
			lp.setFeasibilityPhaseOneObjectiveMode(true);
			check(TimeIndexedScalarCompletionBound.build(d, c, lp, d.CmaxH, start, end) == null,
					"Phase-I must not use a real-cost helper");

			c.enableBidirectionalPricing = true;
			c.useGCNGBBStyleNgDssrPricing = true;
			c.bidirectionalMidpointProbe = false;
			c.bidirectionalCompletionBoundRelaxation = "allCycles";
			String reference = null;
			for (boolean inRound : new boolean[] {false, true}) {
				c.timeIndexedCompletionBoundInRoundArcFixing = inRound;
				var ng = new GCNGBBStyleBidirectionalNgDssr(d, c);
				List<TWETColumn> columns = ng.solve(lp);
				check(get(ng, "timeIndexedScalarBound") == null, "Phase-I retained a TI helper");
				check(columns.stream().anyMatch(column -> column.getSequence().equals(List.of(1, 2))),
						"Phase-I lost its feasible two-job column");
				String result = columnSnapshot(columns) + ":" + ng.getLastRelaxedRoundBestReducedCost();
				if (reference != null) check(reference.equals(result), "Phase-I in-round toggle changed pricing");
				reference = result;
			}
			lp.setFeasibilityPhaseOneObjectiveMode(false);
			var restored = TimeIndexedScalarCompletionBound.build(d, c, lp, d.CmaxH, start, end);
			check(restored != null, "helper did not return with the real objective");
			check(ordinary.suffixLowerBoundAfterFloor(1, 1) == restored.suffixLowerBoundAfterFloor(1, 1),
					"restored real-cost helper changed its bound");
			System.out.println("phase-one-helper excluded; normal objective restored; NG toggle identical");
		} finally {
			lp.closeModel();
		}
	}

	/** 对照不保留旧版本的独立map，覆盖小K、重复改进、同价与淘汰。 */
	private static void testCandidateUpdates() throws Exception {
		Data d = data("3 3\n1 5 5 1 1\n1 6 6 1 1\n1 7 7 1 1\n");
		TWETBPCConfig disabled = config();
		disabled.timeIndexedGraphMaxExactPricingColumns = 0;
		disabled.maxExactPricingColumns = 0;
		LP empty = lp(d, disabled, new double[d.n + 1], 0);
		try {
			Object solver = solver(new TimeIndexedGraphPricingEngine(d, disabled), empty);
			check(((List<?>) method(solver, "solve").invoke(solver)).isEmpty(), "zero-K pricing returned a column");
			check(((PriorityQueue<?>) get(solver, "candidateHeap")).isEmpty(), "zero-K pricing filled its heap");
		} finally { empty.closeModel(); }
		for (int k : new int[] {1, 2, 7, 300}) {
			TWETBPCConfig c = config();
			c.timeIndexedGraphMaxExactPricingColumns = k;
			LP lp = lp(d, c, new double[d.n + 1], 0);
			try {
				Object solver = solver(new TimeIndexedGraphPricingEngine(d, c), lp);
				Method remember = method(solver, "rememberCandidate", SequenceSignature.class,
						ArrayList.class, ColumnSource.class, double.class);
				Map<List<Integer>, double[]> expected = new HashMap<>();
				Random random = new Random(20261107L);
				int nextId = 0;
				for (int step = 0; step < 1200; step++) {
					ArrayList<Integer> sequence = new ArrayList<>(List.of(1 + random.nextInt(3), 1 + random.nextInt(3)));
					double rc = -random.nextInt(60);
					double[] previous = expected.get(sequence);
					if (previous == null || Utility.compareGt(previous[0], rc)) {
						expected.put(sequence, new double[] {rc, nextId++});
						if (expected.size() > k) {
							List<Integer> worst = null;
							for (var entry : expected.entrySet()) {
								if (worst == null || entry.getValue()[0] > expected.get(worst)[0]
										|| entry.getValue()[0] == expected.get(worst)[0]
										&& entry.getValue()[1] > expected.get(worst)[1]) worst = entry.getKey();
							}
							expected.remove(worst);
						}
					}
					remember.invoke(solver, new SequenceSignature(sequence), sequence, ColumnSource.MANUAL, rc);
					Map<?, ?> actual = (Map<?, ?>) get(solver, "candidateBySignature");
					PriorityQueue<?> heap = (PriorityQueue<?>) get(solver, "candidateHeap");
					check(actual.size() == expected.size(), "candidate count changed");
					check(heap.size() <= 2L * actual.size(), "stale heap exceeded its bound");
					for (Object candidate : actual.values()) {
						double[] wanted = expected.get(get(candidate, "sequence"));
						check(wanted != null && wanted[0] == (Double) get(candidate, "reducedCost")
								&& wanted[1] == (Integer) get(candidate, "id"), "candidate identity/tie changed");
					}
				}
			}
			finally { lp.closeModel(); }
		}
	}

	@SuppressWarnings("unchecked")
	private static void testCandidatePricing(boolean bounded) throws Exception {
		Data d = data("2 2\n500 1000 1000 1 1\n500 1500 1500 1 1\nSETUP\n0 0 0\n0 0 0\n0 0 0\n");
		d.CmaxH = 2000;
		Arrays.fill(d.hardWindowStart, 0);
		Arrays.fill(d.hardWindowEnd, 2000);
		d.setPenaltyFunctions();
		TWETBPCConfig c = config();
		LP lp = lp(d, c, new double[] {0, 1000, 1000}, -1000);
		try {
			Object solver = solver(new TimeIndexedGraphPricingEngine(d, c), lp);
			List<TWETColumn> columns = (List<TWETColumn>) method(solver, "solve").invoke(solver);
			int live = ((Map<?, ?>) get(solver, "candidateBySignature")).size();
			int retained = ((PriorityQueue<?>) get(solver, "candidateHeap")).size();
			check(live == 3 && columns.size() == 3, "candidate fixture changed");
			if (bounded) check(retained <= 2 * live, "candidate heap retained obsolete versions");
			System.out.println("heap live=" + live + " retained=" + retained);
			System.out.println("pricing " + columnSnapshot(columns) + " certificate=" + get(solver, "bestPseudoReducedCost"));
		} finally { lp.closeModel(); }
	}

	private static void testFixingSnapshots() throws Exception {
		Data d = data("2 2\n1 5 5 1 1\n1 6 6 1 1\nSETUP\n0 0 0\n0 0 0\n0 0 0\n");
		for (int variant = 0; variant < 6; variant++) {
			TWETBPCConfig c = config();
			LP lp = lp(d, c, variant % 2 == 0 ? new double[] {0, 100, 100} : new double[3],
					variant % 2 == 0 ? -100 : 0);
			try {
				Node node = lp.getNode();
				if (variant >= 2) {
					node.tightenTimeIndexedPricingWindow(1, 3, 5);
					node.forbidTimeIndexedPricingOnlyArc(1, 2, 5);
				}
				if (variant >= 4) node.tightenTimeIndexedPricingWindow(2, 0, 0);
				Class<?> type = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$ArcFixingSolver");
				Class<?> stat = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$StaticPricingData");
				Constructor<?> ctor = type.getDeclaredConstructor(Data.class, TWETBPCConfig.class, LP.class, double.class, stat);
				ctor.setAccessible(true);
				Object solver = ctor.newInstance(d, c, lp, variant % 2 == 0 ? 1e6 : 0.5, null);
				var result = (TimeIndexedGraphPricingEngine.ArcFixingResult) method(solver, "apply").invoke(solver);
				long initial = result.getProcessFixedLong() + result.getIdleFixedLong() + result.getEndFixedLong();
				check((initial == 0) == (variant % 2 == 0), "fixture failed to distinguish RC graph changes");
				BitSet arcs = new BitSet();
				int index = 0;
				for (int from = 0; from <= d.n; from++) {
					for (int to = 0; to <= d.n; to++) {
						for (int t = 0; t <= d.CmaxH; t++, index++) {
							if (node.isTimeIndexedPricingOnlyArcForbidden(from, to, t)) arcs.set(index);
						}
					}
				}
				String windows = "";
				for (int job = 1; job <= d.n; job++) {
					windows += job + ":" + node.getTimeIndexedPricingWindowStart(job) + ":"
							+ node.getTimeIndexedPricingWindowEnd(job) + ";";
				}
				System.out.println("fixing " + variant + " initial=" + initial + " fixed=" + result.getFixedLong()
						+ " rounds=" + get(result, "cleanupRounds") + " arcs=" + arcs + " windows=" + windows);
			} finally { lp.closeModel(); }
		}
	}

	/** 标记只改变有限距离的数值，不改变cleanup使用的可达性，直接核对复用分支。 */
	private static void testCleanupDistanceReuse() throws Exception {
		Data d = data("2 2\n1 5 5 1 1\n1 6 6 1 1\n");
		TWETBPCConfig c = config();
		LP lp = lp(d, c, new double[] {0, 100, 100}, -100);
		try {
			Class<?> type = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$ArcFixingSolver");
			Class<?> stat = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$StaticPricingData");
			Constructor<?> ctor = type.getDeclaredConstructor(Data.class, TWETBPCConfig.class, LP.class, double.class, stat);
			ctor.setAccessible(true);
			Object solver = ctor.newInstance(d, c, lp, 1e6, null);
			method(solver, "computeForwardDistances").invoke(solver);
			method(solver, "computeBackwardDistances").invoke(solver);
			double[] forward = (double[]) get(solver, "forward");
			double[] backward = (double[]) get(solver, "backward");
			for (int i = 0; i < forward.length; i++) {
				if (forward[i] < 5e99) forward[i] += 0.125;
				if (backward[i] < 5e99) backward[i] += 0.25;
			}
			double[] markedForward = forward.clone();
			double[] markedBackward = backward.clone();
			Method cleanup = method(solver, "cleanupGraph", boolean.class);
			long fixed = (Long) cleanup.invoke(solver, true);
			check(fixed > 0, "distance reuse must still run structural cleanup");
			check(Arrays.equals(forward, markedForward) && Arrays.equals(backward, markedBackward),
					"reuse branch recomputed its distances");
			cleanup.invoke(solver, false);
			check(!Arrays.equals(forward, markedForward) && !Arrays.equals(backward, markedBackward),
					"changed-graph branch failed to recompute distances");
		} finally { lp.closeModel(); }
	}

	private static Object solver(TimeIndexedGraphPricingEngine engine, LP lp) throws Exception {
		Class<?> type = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$TimeIndexedGraphSolver");
		Constructor<?> ctor = type.getDeclaredConstructor(TimeIndexedGraphPricingEngine.class, LP.class);
		ctor.setAccessible(true);
		return ctor.newInstance(engine, lp);
	}

	private static String columnSnapshot(List<TWETColumn> columns) {
		StringBuilder text = new StringBuilder();
		for (TWETColumn column : columns) text.append(column.getSequence()).append('@').append(column.getCost()).append(';');
		return text.toString();
	}

	private static Field field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static Object get(Object owner, String name) throws Exception { return field(owner, name).get(owner); }

	private static Method method(Object owner, String name, Class<?>... types) throws Exception {
		Method method = owner.getClass().getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
