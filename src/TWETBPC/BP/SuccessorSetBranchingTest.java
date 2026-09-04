package TWETBPC.BP;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

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

/** 验证 successor-set 左支使用直接域限制，右支保留 aggregate lower row。 */
public final class SuccessorSetBranchingTest {

	private SuccessorSetBranchingTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		TWETBPCConfig config = new TWETBPCConfig();
		Pool pool = new Pool(data);
		int to2 = pool.addColumn(Arrays.asList(1, 2), 10.0, ColumnSource.MANUAL, false);
		int to3 = pool.addColumn(Arrays.asList(1, 3), 10.0, ColumnSource.MANUAL, false);
		LP lp = new LP(data, pool, new CutPool(), config, new OutsourcingPool(data));
		lp.construct(new Node(data, Arrays.asList(to2, to3), Arrays.asList(), 0.0), Arrays.asList(to2, to3));

		LinkedHashMap<Integer, Double> values = new LinkedHashMap<Integer, Double>();
		values.put(Integer.valueOf(to2), Double.valueOf(0.4));
		values.put(Integer.valueOf(to3), Double.valueOf(0.4));
		double[] outsourcing = new double[data.n + 1];
		outsourcing[1] = 0.2;
		setLastSolution(lp, new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION, values, outsourcing,
				0.0, false, "successor-set test"));

		List<StrongBranchingCandidate> candidates = new SuccessorSetBrancher(data, config)
				.collectStrongBranchingCandidates(lp, 20);
		require(candidates.size() == 1, "expected one successor-set candidate");
		BranchResult result = candidates.get(0).createBranchResult(lp);
		Node left = result.getLeftNode();
		Node right = result.getRightNode();
		require(left.hasSuccessorSetDirectDomainRestriction(), "left direct-domain marker");
		require(left.getAggregateArcConstraints().isEmpty(), "left must not retain aggregate upper row");
		require(left.getOutsourcingJobState(1) == Node.OUTSOURCE_FORBIDDEN, "OUT must be forbidden on left");
		require(left.getArcState(1, 2) == Node.ARC_FORBIDDEN, "selected arc must be forbidden on left");
		require(right.getAggregateArcConstraints().size() == 1, "right aggregate lower row");
		AggregateArcBranchConstraint lower = right.getAggregateArcConstraints().get(0);
		require(lower.isLowerBound() && lower.getRhs() == 1, "right must enforce Q(A)>=1");
		System.out.println("SuccessorSetBranchingTest passed.");
	}

	private static void setLastSolution(LP lp, TWETMasterSolution solution) throws Exception {
		Field field = LP.class.getDeclaredField("lastSolution");
		field.setAccessible(true);
		field.set(lp, solution);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
