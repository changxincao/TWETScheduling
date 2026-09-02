package TWETBPC.CUT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;

/**
 * 对拍 full-memory、multiplier=1/2 的 rank-1 快速系数与通用 evaluator。
 */
public final class SubsetRowCutCoefficientTest {

	private SubsetRowCutCoefficientTest() {
	}

	public static void main(String[] args) {
		testCoefficientCanAccumulateBeyondResidualState();
		Random random = new Random(20260728L);
		for (int jobCount = 1; jobCount <= 80; jobCount++) {
			for (int round = 0; round < 200; round++) {
				ArrayList<Integer> sequence = new ArrayList<Integer>();
				int length = random.nextInt(jobCount * 3 + 1);
				for (int index = 0; index < length; index++) {
					sequence.add(Integer.valueOf(1 + random.nextInt(jobCount)));
				}
				TWETColumn column = new TWETColumn(0, sequence, jobCount, 0.0, ColumnSource.MANUAL, false);
				int[] scope = randomScope(random, jobCount);
				ArrayList<Integer> scopeJobs = new ArrayList<Integer>();
				for (int job : scope) {
					scopeJobs.add(Integer.valueOf(job));
				}
				TWETCut cut = new TWETCut(-1, TWETCutType.SUBSET_ROW, scopeJobs,
						Math.floor(scope.length * 0.5), "rank1CoefficientTest");
				int expected = SubsetRowCutEvaluator.coefficient(cut, sequence, jobCount);
				int actual = SubsetRowCutGenerator.fullRank1Coefficient(column, scope);
				if (expected != actual) {
					throw new AssertionError("rank-1 coefficient mismatch: scope="
							+ Arrays.toString(scope) + ", sequence=" + sequence
							+ ", expected=" + expected + ", actual=" + actual);
				}
			}
		}
		System.out.println("SubsetRowCutCoefficientTest passed");
	}

	/** residual 只保存未配对的 0/1 状态；已完成的访问对仍会累计为大于 1 的整列系数。 */
	private static void testCoefficientCanAccumulateBeyondResidualState() {
		ArrayList<Long> memoryArcs = new ArrayList<Long>();
		memoryArcs.add(Long.valueOf(SubsetRowCutEvaluator.arcKey(0, 1)));
		memoryArcs.add(Long.valueOf(SubsetRowCutEvaluator.arcKey(1, 1)));
		TWETCut cut = new TWETCut(-1, TWETCutType.SUBSET_ROW, Arrays.asList(Integer.valueOf(1)), null,
				memoryArcs, 0.5, 0.0, "accumulatedOneRowCoefficient");
		int coefficient = SubsetRowCutEvaluator.coefficient(cut,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(1), Integer.valueOf(1), Integer.valueOf(1)), 1);
		if (coefficient != 2) {
			throw new AssertionError("Four remembered visits must produce coefficient 2, got " + coefficient);
		}
	}

	private static int[] randomScope(Random random, int jobCount) {
		if (jobCount < 3 || random.nextBoolean()) {
			return new int[] { 1 + random.nextInt(jobCount) };
		}
		int first = 1 + random.nextInt(jobCount);
		int second;
		int third;
		do {
			second = 1 + random.nextInt(jobCount);
		} while (second == first);
		do {
			third = 1 + random.nextInt(jobCount);
		} while (third == first || third == second);
		int[] scope = new int[] { first, second, third };
		Arrays.sort(scope);
		return scope;
	}
}
