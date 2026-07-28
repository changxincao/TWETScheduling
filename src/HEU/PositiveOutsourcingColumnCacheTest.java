package HEU;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/** 验证列化外包正值列缓存只对应最近一次有效 LP 解。 */
public final class PositiveOutsourcingColumnCacheTest {

	private PositiveOutsourcingColumnCacheTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = SmallExactHeuristicBatchTest.buildRandomCase(0, 12, 2);
		TWETBPCConfig config = new TWETBPCConfig();
		config.outsourcingModel = "columns";
		Pool pool = new Pool(data);
		OutsourcingPool outsourcingPool = new OutsourcingPool(data);

		ArrayList<Integer> allJobs = new ArrayList<Integer>(data.n);
		for (int job = 1; job <= data.n; job++) {
			allJobs.add(Integer.valueOf(job));
		}
		int internalId = pool.addOrImproveColumn(allJobs, 1.0e6, ColumnSource.MANUAL, true).columnId;
		int fullOutsourcingId = outsourcingPool.addColumn(allJobs, ColumnSource.MANUAL, true);
		Node node = new Node(data, Collections.singletonList(Integer.valueOf(internalId)),
				Collections.singletonList(Integer.valueOf(internalId)), 0.0);
		node.seedOutsourcingColumnIds.add(Integer.valueOf(fullOutsourcingId));

		LP lp = new LP(data, pool, new CutPool(), config, outsourcingPool);
		lp.construct(node, node.seedColumnIds);
		TWETMasterSolution first = lp.solveRelaxation();
		assertSolved(first, "initial");
		Set<Integer> firstPositive = lp.getPositiveOutsourcingColumnIds();
		if (!firstPositive.contains(Integer.valueOf(fullOutsourcingId))) {
			throw new AssertionError("positive outsourcing column was not cached");
		}

		ArrayList<Integer> reversedJobs = new ArrayList<Integer>(allJobs);
		Collections.reverse(reversedJobs);
		int secondInternalId =
				pool.addOrImproveColumn(reversedJobs, 1.0e6, ColumnSource.MANUAL, false).columnId;
		lp.addColumns(Collections.singletonList(Integer.valueOf(secondInternalId)));
		assertInvalidated(lp, "adding an internal column");
		TWETMasterSolution afterInternalAdd = lp.resolveCurrentModel();
		assertSolved(afterInternalAdd, "internal-column resolved");
		if (lp.getPositiveOutsourcingColumnIds().isEmpty()) {
			throw new AssertionError("positive outsourcing cache was not rebuilt after internal-column resolve");
		}

		int singletonOutsourcingId = outsourcingPool.addColumn(
				Collections.singletonList(Integer.valueOf(1)), ColumnSource.MANUAL, false);
		lp.addOutsourcingColumns(Collections.singletonList(Integer.valueOf(singletonOutsourcingId)));
		assertInvalidated(lp, "adding an outsourcing column");

		TWETMasterSolution second = lp.resolveCurrentModel();
		assertSolved(second, "resolved");
		if (lp.getPositiveOutsourcingColumnIds().isEmpty()) {
			throw new AssertionError("positive outsourcing cache was not rebuilt after resolve");
		}
		lp.closeModel();
		if (!lp.getPositiveOutsourcingColumnIds().isEmpty()) {
			throw new AssertionError("closing the model did not clear the positive outsourcing cache");
		}
		System.out.println("PositiveOutsourcingColumnCacheTest passed");
	}

	private static void assertInvalidated(LP lp, String phase) {
		if (lp.getLastSolution() != null || !lp.getPositiveOutsourcingColumnIds().isEmpty()) {
			throw new AssertionError(phase + " did not invalidate the cached LP solution");
		}
	}

	private static void assertSolved(TWETMasterSolution solution, String phase) {
		if (solution.getStatus() != TWETMasterStatus.LP_RELAXATION) {
			throw new AssertionError(phase + " LP was not solved: " + solution.getMessage());
		}
	}
}
