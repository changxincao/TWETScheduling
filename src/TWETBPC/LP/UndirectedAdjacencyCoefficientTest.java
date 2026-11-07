package TWETBPC.LP;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterStatus;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloLinearNumExprIterator;
import ilog.concert.IloNumVar;
import ilog.concert.IloRange;

/** 核对无向邻接按经过次数建行、增列，以及真实CPLEX RC与pricing展开式一致。 */
public final class UndirectedAdjacencyCoefficientTest {

	private UndirectedAdjacencyCoefficientTest() {
	}

	public static void main(String[] args) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		String group = args.length == 0 ? "all" : args[0];
		if ("all".equals(group) || "rc".equals(group)) testOriginalReducedCostSnapshot();
		if ("all".equals(group) || "rebuild".equals(group) || "incremental".equals(group)) {
			for (boolean incremental : new boolean[] {false, true}) {
				if ("rebuild".equals(group) && incremental || "incremental".equals(group) && !incremental) continue;
				for (boolean phaseOne : new boolean[] {false, true}) {
					for (boolean sri : new boolean[] {false, true}) testRows(incremental, phaseOne, sri, false);
				}
			}
		}
		if ("all".equals(group)) testRows(true, false, false, true);
		System.out.println("UndirectedAdjacencyCoefficientTest " + group + " passed");
	}

	private static Data data(boolean threeJobs) throws Exception {
		Path input = Files.createTempFile("twet-adjacency-count-", ".dat");
		try {
			Files.writeString(input, threeJobs
					? "3 3\n1 5 5 1 1\n1 6 6 1 1\n1 7 7 1 1\nSETUP\n"
							+ "0 0 0 0\n0 0 0 0\n0 0 0 0\n0 0 0 0\nSETUP_COST\n"
							+ "0 0 0 0\n0 0 10 0\n0 10 0 0\n0 0 0 0\n"
					: "2 2\n1 5 5 1 1\n1 6 6 1 1\nSETUP\n"
							+ "0 0 0\n0 0 0\n0 0 0\nSETUP_COST\n0 0 0\n0 0 10\n0 10 0\n");
			return new Data(input.toString(), true, true);
		} finally {
			Files.deleteIfExists(input);
		}
	}

	private static TWETBPCConfig config(boolean sri) {
		TWETBPCConfig c = new TWETBPCConfig();
		c.enableBPCConsoleOutput = false;
		c.enableUndirectedAdjacencyBranching = true;
		c.useTimeIndexedGraphPricing = true;
		c.useTimeIndexedGraphRank1CutPricing = sri;
		c.enableSubsetRowCutsForTimeIndexedGraph = sri;
		return c;
	}

	private static int add(Pool pool, TWETColumnEvaluator evaluator, List<Integer> sequence) {
		double cost = evaluator.evaluate(sequence);
		check(!Utility.isBigMValue(cost), "test sequence must have finite true cost");
		return pool.addColumn(sequence, cost, ColumnSource.MANUAL, true);
	}

	/** 原审查的真实RMP快照；不手工设dual，不修改路线成本。 */
	private static void testOriginalReducedCostSnapshot() throws Exception {
		Data d = data(false);
		Pool pool = new Pool(d);
		TWETColumnEvaluator evaluator = new TWETColumnEvaluator(d);
		List<Integer> ids = new ArrayList<>();
		for (List<Integer> sequence : List.of(List.of(1), List.of(2), List.of(1, 2), List.of(1, 2, 1))) {
			ids.add(add(pool, evaluator, sequence));
		}
		Node node = new Node(d, ids, List.of(), 0);
		node.requireAdjacencyPair(1, 2);
		LP lp = new LP(d, pool, new CutPool(), config(false), new OutsourcingPool(d));
		try {
			lp.construct(node, ids);
			check(lp.solveRelaxation().getStatus() == TWETMasterStatus.LP_RELAXATION, "original RMP not solved");
			int pseudo = ids.get(3);
			double actual = lp.getColumnReducedCost(pseudo);
			double expanded = lp.computeReducedCost(pool.getColumn(pseudo), lp.captureTruePricingDuals());
			System.out.println("original adjacency pairDual=" + lp.getArcDual(1, 2)
					+ " cplexRC=" + actual + " expandedRC=" + expanded);
			check(lp.getArcDual(1, 2) > 1e-6, "fixture must expose a nonzero pair dual");
			near(actual, expanded, "original RC mismatch");
		} finally { lp.closeModel(); }
	}

	private static void testRows(boolean incremental, boolean phaseOne, boolean sri, boolean forbidden) throws Exception {
		Data d = data(true);
		Pool pool = new Pool(d);
		TWETColumnEvaluator evaluator = new TWETColumnEvaluator(d);
		List<Integer> base = new ArrayList<>();
		for (List<Integer> sequence : List.of(List.of(1), List.of(2), List.of(3), List.of(1, 2), List.of(2, 1))) {
			base.add(add(pool, evaluator, sequence));
		}
		List<Integer> added = List.of(add(pool, evaluator, List.of(1, 2, 1)),
				add(pool, evaluator, List.of(1, 2, 1, 2)));
		List<Integer> all = new ArrayList<>(base);
		all.addAll(added);
		CutPool cuts = new CutPool();
		List<Integer> active = sri ? List.of(cuts.addCut(new TWETCut(-1, TWETCutType.SUBSET_ROW,
				List.of(1, 2, 3), 1.0, "adjacency coefficient regression"))) : List.of();
		Node node = new Node(d, incremental ? base : all, List.of(), 0);
		node.activeCutIds.addAll(active);
		if (forbidden) node.forbidAdjacencyPair(1, 2);
		else node.requireAdjacencyPair(1, 2);
		node.markAdjacencyPairRepair(1, 2, !forbidden);
		LP lp = new LP(d, pool, cuts, config(sri), new OutsourcingPool(d));
		try {
			lp.construct(node, incremental ? base : all);
			if (phaseOne) {
				lp.setAllRowFeasibilityRepairMode(true);
				lp.setFeasibilityPhaseOneObjectiveMode(true);
			}
			check(lp.solveRelaxation().getStatus() == TWETMasterStatus.LP_RELAXATION, "test RMP not solved");
			Map<?, ?> sriRows = (Map<?, ?>) field(lp, "subsetRowCutRanges");
			check(sriRows.size() == active.size() && sriRows.keySet().containsAll(active), "SRI row was not installed");
			if (incremental) {
				check(lp.addColumns(added) == 2, "expected two new columns");
				assertRowCoefficients(lp);
				// 必须resolve，不能重建模型掩盖增列入口漏改。
				check(lp.resolveCurrentModel().getStatus() == TWETMasterStatus.LP_RELAXATION, "incremental RMP not solved");
			}
			assertRowCoefficients(lp);
			var dual = lp.captureTruePricingDuals();
			for (int id : all) {
				near(lp.getColumnReducedCost(id), lp.computeReducedCost(pool.getColumn(id), dual), "matrix RC mismatch");
			}
			if (phaseOne) check(lp.isNoSlack(), "feasible Phase-I unexpectedly retained artificial amount");
			if (forbidden) check(node.isArcForbidden(1, 2) && node.isArcForbidden(2, 1), "left branch lost arc bans");
			System.out.println("adjacency rows incremental=" + incremental + " phaseOne=" + phaseOne
					+ " sri=" + sri + " sriRows=" + sriRows.size() + " forbidden=" + forbidden
					+ " coefficients=0/1/2/3 RC=matched");
		} finally { lp.closeModel(); }
	}

	@SuppressWarnings("unchecked")
	private static void assertRowCoefficients(LP lp) throws Exception {
		Map<Long, IloRange> rows = (Map<Long, IloRange>) field(lp, "adjacencyBranchRanges");
		Map<Integer, IloNumVar> vars = (Map<Integer, IloNumVar>) field(lp, "lambdaByColumnId");
		check(rows.size() == 1, "expected one adjacency row");
		IloRange row = rows.values().iterator().next();
		for (int id : lp.getRestrictedColumnIds()) {
			TWETColumn column = lp.getPool().getColumn(id);
			int expected = column.getArcVisitCount(1, 2, lp.getNode().sinkId())
					+ column.getArcVisitCount(2, 1, lp.getNode().sinkId());
			check(lp.getNode().columnCoversAdjacencyPair(column, 1, 2) == (expected > 0), "boolean presence changed");
			double actual = 0;
			IloLinearNumExprIterator iterator = ((IloLinearNumExpr) row.getExpr()).linearIterator();
			while (iterator.hasNext()) {
				if (iterator.nextNumVar().equals(vars.get(id))) actual += iterator.getValue();
			}
			near(actual, expected, "adjacency coefficient for " + column.getSequence());
		}
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static void near(double actual, double expected, String message) {
		check(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-6,
				message + ": actual=" + actual + " expected=" + expected);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
