package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import TWETBPC.CUT.SubsetRowCutEvaluator;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;

/**
 * 验证论文式同 base memory 合并只替换当前 active ID，不修改全局旧 cut。
 */
public final class CutPoolActiveRank1MemoryMergeTest {

	private CutPoolActiveRank1MemoryMergeTest() {
	}

	public static void main(String[] args) {
		CutPool pool = new CutPool();
		TWETCut first = cut(arcKey(1, 2));
		int firstId = pool.addCut(first);
		ArrayList<Integer> active = new ArrayList<Integer>(Collections.singletonList(Integer.valueOf(firstId)));

		TWETCut second = cut(arcKey(2, 3));
		CutPool.Rank1MemoryMergeResult firstMerge = pool.mergeRank1MemoryIntoActive(active, second);
		assertTrue(firstMerge.isChanged(), "New memory did not replace the active base");
		assertTrue(firstMerge.getMatchedActiveVersions() == 1, "Unexpected matched version count");
		assertTrue(active.size() == 1, "The active base was duplicated");
		int unionId = active.get(0).intValue();
		assertTrue(unionId != firstId, "The immutable old cut ID was reused");
		assertTrue(pool.getCut(firstId).getMemoryArcs().equals(first.getMemoryArcs()),
				"The old cut ID was modified");
		assertTrue(pool.getCut(unionId).getMemoryArcs().equals(
				Arrays.asList(Long.valueOf(arcKey(1, 2)), Long.valueOf(arcKey(2, 3)))),
				"The active cut does not contain the memory union");

		CutPool.Rank1MemoryMergeResult noChange =
				pool.mergeRank1MemoryIntoActive(active, cut(arcKey(1, 2)));
		assertTrue(!noChange.isChanged(), "A memory subset created a redundant replacement");
		assertTrue(active.equals(Collections.singletonList(Integer.valueOf(unionId))),
				"A no-op merge changed the active cut");

		ArrayList<Integer> duplicatedActive = new ArrayList<Integer>(
				Arrays.asList(Integer.valueOf(firstId), Integer.valueOf(unionId)));
		CutPool.Rank1MemoryMergeResult collapse =
				pool.mergeRank1MemoryIntoActive(duplicatedActive, cut(arcKey(2, 3)));
		assertTrue(collapse.isChanged(), "Existing memory versions were not consolidated");
		assertTrue(collapse.getMatchedActiveVersions() == 2, "Two active versions were not detected");
		assertTrue(collapse.getRemovedActiveVersions() == 1, "The redundant active version was not counted");
		assertTrue(duplicatedActive.equals(Collections.singletonList(Integer.valueOf(unionId))),
				"Consolidation did not leave exactly one active union cut");

		verifyUnionCoefficientDominance();
		System.out.println("CutPoolActiveRank1MemoryMergeTest passed");
	}

	/**
	 * 扩大 arc memory 只会减少 residual 清零，因此 union cut 的列系数不能小于任一旧版本。
	 */
	private static void verifyUnionCoefficientDominance() {
		TWETCut first = threeRowCut(arcKey(1, 4), arcKey(4, 2));
		TWETCut second = threeRowCut(arcKey(2, 4), arcKey(4, 3));
		TWETCut union = first.mergedMemoryWith(second);
		for (int length = 1; length <= 6; length++) {
			verifySequences(new int[length], 0, first, second, union);
		}
	}

	private static void verifySequences(int[] sequence, int position, TWETCut first, TWETCut second, TWETCut union) {
		if (position == sequence.length) {
			ArrayList<Integer> jobs = new ArrayList<Integer>();
			for (int job : sequence) {
				jobs.add(Integer.valueOf(job));
			}
			int firstCoefficient = SubsetRowCutEvaluator.coefficient(first, jobs, 4);
			int secondCoefficient = SubsetRowCutEvaluator.coefficient(second, jobs, 4);
			int unionCoefficient = SubsetRowCutEvaluator.coefficient(union, jobs, 4);
			assertTrue(unionCoefficient >= firstCoefficient && unionCoefficient >= secondCoefficient,
					"Memory union weakened a route coefficient: " + jobs);
			return;
		}
		for (int job = 1; job <= 4; job++) {
			sequence[position] = job;
			verifySequences(sequence, position + 1, first, second, union);
		}
	}

	private static TWETCut cut(long... memoryArcs) {
		ArrayList<Long> arcs = new ArrayList<Long>();
		for (long arc : memoryArcs) {
			arcs.add(Long.valueOf(arc));
		}
		Collections.sort(arcs);
		return new TWETCut(-1, TWETCutType.SUBSET_ROW, Collections.singletonList(Integer.valueOf(1)),
				null, arcs, 0.5, 0.0, "arcLmR1");
	}

	private static TWETCut threeRowCut(long... memoryArcs) {
		ArrayList<Long> arcs = new ArrayList<Long>();
		for (long arc : memoryArcs) {
			arcs.add(Long.valueOf(arc));
		}
		Collections.sort(arcs);
		return new TWETCut(-1, TWETCutType.SUBSET_ROW,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3)),
				null, arcs, 0.5, 1.0, "arcLmSRI3");
	}

	private static long arcKey(int from, int to) {
		return (((long) from) << 32) ^ (to & 0xffffffffL);
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
