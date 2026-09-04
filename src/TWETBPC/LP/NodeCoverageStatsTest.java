package TWETBPC.LP;

import java.util.Arrays;
import java.util.LinkedHashMap;

import Basic.Data;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/** 验证节点 coverage 诊断严格复现 master 覆盖行的 visit-count 与外包系数。 */
public final class NodeCoverageStatsTest {

	private NodeCoverageStatsTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		Pool pool = new Pool(data);
		int repeated = pool.addColumn(Arrays.asList(1, 2, 1), 10.0, ColumnSource.MANUAL, false);
		int ordinary = pool.addColumn(Arrays.asList(2, 3), 10.0, ColumnSource.MANUAL, false);

		LinkedHashMap<Integer, Double> values = new LinkedHashMap<Integer, Double>();
		values.put(Integer.valueOf(repeated), Double.valueOf(0.5));
		values.put(Integer.valueOf(ordinary), Double.valueOf(0.5));
		double[] outsourcing = new double[data.n + 1];
		outsourcing[3] = 0.5;
		TWETMasterSolution solution = new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION, values,
				outsourcing, 0.0, false, "test");

		NodeCoverageStats stats = NodeCoverageStats.from(solution, pool, data.n);
		assertClose(1.0, stats.internal(1), "repeated visit count");
		assertClose(1.0, stats.total(2), "two internal columns");
		assertClose(0.5, stats.internal(3), "internal component");
		assertClose(0.5, stats.outsourcing(3), "outsourcing component");
		assertClose(1.0, stats.total(3), "combined coverage");
		System.out.println("NodeCoverageStatsTest passed");
	}

	private static void assertClose(double expected, double actual, String context) {
		if (Math.abs(expected - actual) > 1.0e-9) {
			throw new AssertionError(context + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
