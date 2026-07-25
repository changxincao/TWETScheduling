package TWETBPC.LP;

import java.util.Arrays;
import java.util.Collections;

import Basic.Data;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
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
		Node parent = new Node(data, Collections.<Integer>emptyList(), Collections.<Integer>emptyList(), 0.0);
		LP lp = new LP(data, pool, cutPool, config, new OutsourcingPool(data));
		lp.construct(parent, parent.seedColumnIds);

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
		System.out.println("ActiveCutInheritanceTest passed");
	}
}
