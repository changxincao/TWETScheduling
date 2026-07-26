package TWETBPC.LP;

import java.util.Arrays;
import java.util.Collections;

import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;

/**
 * 验证 limited-memory cut 的全局 ID 不会被其他节点生成的 memory 版本原地改写。
 */
public final class CutPoolImmutableIdTest {

	private CutPoolImmutableIdTest() {
	}

	public static void main(String[] args) {
		CutPool pool = new CutPool();
		TWETCut first = cut(Arrays.asList(Long.valueOf(arcKey(1, 2))));
		int firstId = pool.addCut(first);

		TWETCut expanded = cut(Arrays.asList(Long.valueOf(arcKey(1, 2)), Long.valueOf(arcKey(2, 3))));
		int expandedId = pool.addCut(expanded);
		if (expandedId == firstId) {
			throw new AssertionError("Different limited-memory definitions reused the same cut ID");
		}
		if (!pool.getCut(firstId).getMemoryArcs().equals(first.getMemoryArcs())) {
			throw new AssertionError("Adding another memory version changed the existing cut ID");
		}
		if (pool.addCut(first) != firstId) {
			throw new AssertionError("Exact cut signature was not deduplicated");
		}
		if (pool.size() != 2) {
			throw new AssertionError("Unexpected cut pool size: " + pool.size());
		}
		System.out.println("CutPoolImmutableIdTest passed");
	}

	private static TWETCut cut(java.util.List<Long> memoryArcs) {
		return new TWETCut(-1, TWETCutType.SUBSET_ROW, Collections.singletonList(Integer.valueOf(1)),
				null, memoryArcs, 0.5, 0.0, "arcLmR1");
	}

	private static long arcKey(int from, int to) {
		return (((long) from) << 32) ^ (to & 0xffffffffL);
	}
}
