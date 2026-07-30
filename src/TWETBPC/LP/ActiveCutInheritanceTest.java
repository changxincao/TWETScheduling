package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import Basic.Data;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.TWETBPCConfig;

/**
 * 验证正式父 LP 的最终 active cuts 会在分支前写回 Node，并由 child 独立继承。
 */
public final class ActiveCutInheritanceTest {

	private ActiveCutInheritanceTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", false, true);
		TWETBPCConfig config = new TWETBPCConfig();
		Pool pool = new Pool(data);
		CutPool cutPool = new CutPool();

		ArrayList<Integer> fullSequence = new ArrayList<Integer>(data.n);
		for (int job = 1; job <= data.n; job++) {
			fullSequence.add(Integer.valueOf(job));
		}
		int seedColumnId = pool.addOrImproveColumn(fullSequence, 100.0, ColumnSource.MANUAL, true).columnId;
		Node parent = new Node(data, Collections.singletonList(Integer.valueOf(seedColumnId)),
				Collections.singletonList(Integer.valueOf(seedColumnId)), 0.0);
		LP lp = new LP(data, pool, cutPool, config, new OutsourcingPool(data));
		lp.construct(parent, parent.seedColumnIds);
		TWETMasterSolution initial = lp.solveRelaxation();
		if (initial.getStatus() != TWETMasterStatus.LP_RELAXATION) {
			throw new AssertionError("Initial RMP should be feasible: " + initial.getMessage());
		}

		// 三个任务均在唯一列中，标准 SRI 系数为 floor(3/2)=1；rhs=0 会阻断该列。
		int blockingCut = cutPool.addCut(new TWETCut(-1, TWETCutType.SUBSET_ROW,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3)), 0.0,
				"incremental-blocking"));
		lp.addCuts(Collections.singletonList(Integer.valueOf(blockingCut)));
		TWETMasterSolution blocked = lp.resolveCurrentModel();
		if (blocked.getStatus() != TWETMasterStatus.INFEASIBLE) {
			throw new AssertionError("Incrementally added SRI was not present in the live RMP");
		}

		ArrayList<Integer> reversedSequence = new ArrayList<Integer>(fullSequence);
		Collections.reverse(reversedSequence);
		int addedAfterCut = pool.addOrImproveColumn(reversedSequence, 90.0, ColumnSource.MANUAL, true).columnId;
		lp.addColumns(Collections.singletonList(Integer.valueOf(addedAfterCut)));
		TWETMasterSolution stillBlocked = lp.resolveCurrentModel();
		if (stillBlocked.getStatus() != TWETMasterStatus.INFEASIBLE) {
			throw new AssertionError("A column added after the SRI did not receive its cut coefficient");
		}

		lp.removeCuts(Collections.singletonList(Integer.valueOf(blockingCut)));
		TWETMasterSolution restored = lp.resolveCurrentModel();
		if (restored.getStatus() != TWETMasterStatus.LP_RELAXATION) {
			throw new AssertionError("RMP did not recover after incremental SRI removal: " + restored.getMessage());
		}

		// 零 dual 行删除后原 primal/dual 证书仍然有效，论文流程不应为 purge 单独重解。
		int inactiveCut = cutPool.addCut(
				new TWETCut(-1, TWETCutType.SUBSET_ROW, Arrays.asList(Integer.valueOf(1)), 0.0, "inactive"));
		lp.addCuts(Collections.singletonList(Integer.valueOf(inactiveCut)));
		TWETMasterSolution beforeInactiveRemoval = lp.resolveCurrentModel();
		if (beforeInactiveRemoval.getStatus() != TWETMasterStatus.LP_RELAXATION
				|| lp.getActiveSubsetRowPricingCutIds().contains(Integer.valueOf(inactiveCut))) {
			throw new AssertionError("Redundant SRI should have a zero pricing dual");
		}
		int inactiveRemoved = lp.removeZeroDualCutsPreservingCurrentSolution(
				Collections.singletonList(Integer.valueOf(inactiveCut)));
		if (inactiveRemoved != 1 || lp.getLastSolution() != beforeInactiveRemoval
				|| lp.getActiveCutIds().contains(Integer.valueOf(inactiveCut))) {
			throw new AssertionError("Zero-dual SRI removal did not preserve the closed LP solution");
		}

		int keptCut = cutPool.addCut(
				new TWETCut(-1, TWETCutType.SUBSET_ROW, Arrays.asList(Integer.valueOf(1)), 0.0, "kept"));
		int removedCut = cutPool.addCut(
				new TWETCut(-1, TWETCutType.SUBSET_ROW, Arrays.asList(Integer.valueOf(2)), 0.0, "removed"));
		lp.addCuts(Arrays.asList(Integer.valueOf(keptCut), Integer.valueOf(removedCut)));
		lp.removeCuts(Collections.singletonList(Integer.valueOf(removedCut)));
		lp.syncActiveCutsToNode();

		Node child = parent.copy();
		if (!child.activeCutIds.equals(Collections.singletonList(Integer.valueOf(keptCut)))) {
			throw new AssertionError("Child did not inherit the final active cut set: " + child.activeCutIds);
		}
		child.activeCutIds.clear();
		if (!parent.activeCutIds.equals(Collections.singletonList(Integer.valueOf(keptCut)))) {
			throw new AssertionError("Child and parent active-cut lists are aliased");
		}
		child.activeCutIds.add(Integer.valueOf(keptCut));
		LP childLp = new LP(data, pool, cutPool, config, new OutsourcingPool(data));
		childLp.construct(child, child.seedColumnIds);
		if (!childLp.getActiveCutIds().equals(Collections.singletonList(Integer.valueOf(keptCut)))) {
			throw new AssertionError("Inherited cut was not restored when the child RMP was constructed");
		}
		lp.closeModel();
		childLp.closeModel();
		System.out.println("ActiveCutInheritanceTest passed");
	}
}
