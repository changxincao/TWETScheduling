package TWETBPC.GC;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.Util.TimeIndexedArcSet;

/** debug忽略传播时，禁弧记录和新增计数仍必须去重。 */
public final class TimeIndexedDebugArcFixingTest {

	public static void main(String[] args) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		BitSet reference = null;
		for (int debug : new int[] {-1, 1, 0}) {
			for (boolean rank1 : new boolean[] {false, true}) {
				try (Fixture f = new Fixture(debug, debug == 0 ? 0 : 1)) {
					var result = rank1
							? new TimeIndexedGraphRank1CutPricingEngine(f.data, f.config)
									.applyPaperReducedCostArcFixing(f.lp, 1.0)
							: TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing(
									f.data, f.config, f.lp, 1.0);
					check(result.isAvailable(), "fixing unexpectedly skipped");
					check(result.getFixedLong() == 3 && result.getNodeTimeArcNewlyFixedLong() == 3,
							"duplicate fixing count: " + result.summary());
					BitSet actual = new BitSet();
					int index = 0;
					for (int from = 0; from <= 1; from++) for (int to = 0; to <= 1; to++) {
						for (int time = 0; time <= 1; time++, index++) {
							if (f.node.isTimeIndexedPricingOnlyArcForbidden(from, to, time)) actual.set(index);
						}
					}
					check(actual.cardinality() == 3, "unexpected actual forbidden set");
					if (reference == null) reference = actual;
					else check(reference.equals(actual), "debug/delegate changed the one-job forbidden set");
					System.out.println("public fixing debug=" + debug + " rank1Delegate=" + rank1
							+ " fixed=" + result.getFixedLong() + " actual=" + actual);
				}
			}
		}
		checkDebugWriter(false);
		checkDebugWriter(true);
		System.out.println("TimeIndexedDebugArcFixingTest passed; assertions="
				+ TimeIndexedDebugArcFixingTest.class.desiredAssertionStatus());
	}

	/** 分段测试只替换位图，不分配巨型TI图或动态规划数组。 */
	private static void checkDebugWriter(boolean segmented) throws Exception {
		try (Fixture f = new Fixture(1, 1)) {
			Class<?> type = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$ArcFixingSolver");
			Class<?> stat = Class.forName(TimeIndexedGraphPricingEngine.class.getName() + "$StaticPricingData");
			Constructor<?> ctor = type.getDeclaredConstructor(Data.class, TWETBPCConfig.class, LP.class,
					double.class, stat);
			ctor.setAccessible(true);
			Object solver = ctor.newInstance(f.data, f.config, f.lp, 1.0, null);
			Field local = type.getDeclaredField("localFixedTimeIndexedArc");
			local.setAccessible(true);
			if (segmented) local.set(solver, new TimeIndexedArcSet(2, Integer.MAX_VALUE / 4 + 1));
			TimeIndexedArcSet arcs = (TimeIndexedArcSet) local.get(solver);
			check(arcs.usesSegmentedStorage() == segmented, "unexpected storage mode");
			Method write = type.getDeclaredMethod("forbidLocalTimeIndexedArc", int.class, int.class, int.class);
			Method query = type.getDeclaredMethod("isTimeIndexedArcForbidden", int.class, int.class, int.class);
			write.setAccessible(true);
			query.setAccessible(true);
			check(Boolean.TRUE.equals(write.invoke(solver, 0, 1, 1)), "first write must be new");
			check(Boolean.FALSE.equals(query.invoke(solver, 0, 1, 1)), "debug started consuming its own fixing");
			check(Boolean.FALSE.equals(write.invoke(solver, 0, 1, 1)), "second write must not be new");
			check(arcs.cardinality() == 1 && arcs.get(0, 1, 1), "duplicate stored bit count");
			Node receiver = new Node(f.data, List.of(), List.of(), 0.0);
			check(receiver.mergeTimeIndexedPricingOnlyArcSet(arcs) == 1
					&& receiver.isTimeIndexedPricingOnlyArcForbidden(0, 1, 1), "Node received a wrong count");
			System.out.println("debug writer segmented=" + segmented + " local=1 node=1 stillIgnored=true");
		}
	}

	private static final class Fixture implements AutoCloseable {
		final Data data;
		final TWETBPCConfig config = new TWETBPCConfig();
		final Node node;
		final LP lp;

		Fixture(int debug, int nodeId) throws Exception {
			Path input = Files.createTempFile("twet-debug-fixing-", ".dat");
			try {
				Files.writeString(input, "1 1\n1 1 1 1 1\nSETUP\n0 0\n0 0\n");
				data = new Data(input.toString(), true, true);
			} finally { Files.deleteIfExists(input); }
			config.useTimeIndexedGraphPricing = true;
			config.useTimeIndexedGraphRank1CutPricing = true;
			config.debugIgnorePricingOnlyArcsAtNode = debug;
			config.enableBPCConsoleOutput = false;
			Pool pool = new Pool(data);
			int id = pool.addColumn(List.of(1), new TWETColumnEvaluator(data).evaluate(List.of(1)),
					ColumnSource.MANUAL, true);
			node = new Node(data, List.of(id), List.of(id), 0.0);
			node.id = nodeId;
			lp = new LP(data, pool, new CutPool(), config, new OutsourcingPool(data));
			lp.construct(node, List.of(id));
			check(lp.solveRelaxation().getStatus() == TWETMasterStatus.LP_RELAXATION, "RMP not solved");
		}

		@Override public void close() { lp.closeModel(); }
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
