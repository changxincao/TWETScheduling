package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import Basic.Data;
import TWETBPC.TWETBPCConfig;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;

/**
 * 2026-07-28: pricing candidate 与 Pool 正式列共享不可变 ColumnPattern 的等价性和性能测试。
 */
public final class ColumnPatternSharingTest {

	private ColumnPatternSharingTest() {
	}

	public static void main(String[] args) throws Exception {
		testSharedPatternPreservesColumnSemantics();
		testLegacyPathRemainsAvailableForComparison();
		testElementaryCacheMatchesSequenceSemantics();
		if (Boolean.getBoolean("twet.test.columnPatternBenchmark")) {
			runBenchmark();
		}
		System.out.println("ColumnPatternSharingTest passed");
	}

	private static void testSharedPatternPreservesColumnSemantics() throws Exception {
		String previous = System.getProperty("twet.bpc.shareColumnPattern");
		try {
			System.setProperty("twet.bpc.shareColumnPattern", "true");
			Data data = loadData();
			Pool pool = new Pool(data);
			LP lp = new LP(data, pool, new CutPool(), new TWETBPCConfig(), new OutsourcingPool(data));
			List<Integer> sequence = Arrays.asList(1, 2, 1, 3);
			TWETColumn candidate = new TWETColumn(-1, sequence, data.n, 100.0, ColumnSource.PRICING_EXACT, false);
			Pool.ColumnUpdate added = lp.addOrImproveColumn(candidate);
			TWETColumn stored = pool.getColumn(added.columnId);
			if (!added.newColumn || stored.getPattern() != candidate.getPattern()) {
				throw new AssertionError("new Pool column did not reuse candidate pattern");
			}
			assertEquivalentStructure(candidate, stored, data.n + 1);
			try {
				stored.getSequence().add(Integer.valueOf(4));
				throw new AssertionError("shared sequence view is mutable");
			} catch (UnsupportedOperationException expected) {
				// expected
			}

			TWETColumn improvedCandidate = new TWETColumn(-1, sequence, data.n, 90.0,
					ColumnSource.PRICING_HEURISTIC, true);
			Pool.ColumnUpdate improved = lp.addOrImproveColumn(improvedCandidate);
			TWETColumn improvedStored = pool.getColumn(improved.columnId);
			if (!improved.improvedCost || improved.newColumn || improvedStored.getPattern() != stored.getPattern()
					|| improvedStored.getCost() != 90.0 || !improvedStored.isSeedColumn()
					|| improvedStored.getSource() != ColumnSource.PRICING_HEURISTIC) {
				throw new AssertionError("cost improvement changed Pool semantics");
			}
			assertEquivalentStructure(candidate, improvedStored, data.n + 1);
		} finally {
			restoreProperty(previous);
		}
	}

	private static void testLegacyPathRemainsAvailableForComparison() throws Exception {
		String previous = System.getProperty("twet.bpc.shareColumnPattern");
		try {
			System.setProperty("twet.bpc.shareColumnPattern", "false");
			Data data = loadData();
			Pool pool = new Pool(data);
			LP lp = new LP(data, pool, new CutPool(), new TWETBPCConfig(), new OutsourcingPool(data));
			TWETColumn candidate = new TWETColumn(-1, Arrays.asList(4, 5, 6), data.n, 50.0,
					ColumnSource.PRICING_EXACT, false);
			int id = lp.addOrImproveColumn(candidate).columnId;
			TWETColumn stored = pool.getColumn(id);
			if (stored.getPattern() == candidate.getPattern()) {
				throw new AssertionError("legacy comparison path unexpectedly shared candidate pattern");
			}
			assertEquivalentStructure(candidate, stored, data.n + 1);
		} finally {
			restoreProperty(previous);
		}
	}

	private static void assertEquivalentStructure(TWETColumn expected, TWETColumn actual, int sinkId) {
		if (!expected.getSequence().equals(actual.getSequence())
				|| !expected.getSignature().equals(actual.getSignature())
				|| expected.size() != actual.size()
				|| expected.getPattern().isElementary() != actual.getPattern().isElementary()) {
			throw new AssertionError("shared pattern changed sequence structure");
		}
		for (int job = 1; job < sinkId; job++) {
			if (expected.containsJob(job) != actual.containsJob(job)
					|| expected.getJobVisitCount(job) != actual.getJobVisitCount(job)) {
				throw new AssertionError("shared pattern changed job coefficient");
			}
		}
		for (int from = 0; from < sinkId; from++) {
			for (int to = 1; to <= sinkId; to++) {
				if (expected.getArcVisitCount(from, to, sinkId) != actual.getArcVisitCount(from, to, sinkId)) {
					throw new AssertionError("shared pattern changed arc coefficient");
				}
			}
		}
	}

	private static void testElementaryCacheMatchesSequenceSemantics() {
		assertElementary(Arrays.asList(1, 2, 3), 3, true);
		assertElementary(Arrays.asList(1, 2, 1), 3, false);
		assertElementary(Collections.<Integer>emptyList(), 3, false);
		assertElementary(Arrays.asList(0, 1), 3, false);
		assertElementary(Arrays.asList(1, 4), 3, false);
	}

	private static void assertElementary(List<Integer> sequence, int jobCount, boolean expected) {
		TWETColumn column = new TWETColumn(-1, sequence, jobCount, 0.0, ColumnSource.PRICING_EXACT, false);
		if (column.getPattern().isElementary() != expected) {
			throw new AssertionError("elementary cache mismatch for sequence=" + sequence);
		}
	}

	private static void runBenchmark() throws Exception {
		int count = Integer.getInteger("twet.test.columnPatternBenchmarkCount", 100000);
		int length = Integer.getInteger("twet.test.columnPatternBenchmarkLength", 12);
		Data data = loadData();
		int[][] sequences = generateSequences(count, length, data.n);
		String previous = System.getProperty("twet.bpc.shareColumnPattern");
		try {
			System.setProperty("twet.bpc.shareColumnPattern", "true");
			LP shared = new LP(data, new Pool(data), new CutPool(), new TWETBPCConfig(), new OutsourcingPool(data));
			System.setProperty("twet.bpc.shareColumnPattern", "false");
			LP legacy = new LP(data, new Pool(data), new CutPool(), new TWETBPCConfig(), new OutsourcingPool(data));
			long sharedNanos = 0L;
			long legacyNanos = 0L;
			for (int i = 0; i < sequences.length; i++) {
				if ((i & 1) == 0) {
					sharedNanos += addCandidate(shared, sequences[i], data.n);
					legacyNanos += addCandidate(legacy, sequences[i], data.n);
				} else {
					legacyNanos += addCandidate(legacy, sequences[i], data.n);
					sharedNanos += addCandidate(shared, sequences[i], data.n);
				}
			}
			System.out.printf(java.util.Locale.US,
					"ColumnPattern benchmark: columns=%d length=%d sharedMs=%.3f legacyMs=%.3f speedup=%.3f%n",
					count, length, sharedNanos / 1.0e6, legacyNanos / 1.0e6,
					legacyNanos / (double) Math.max(1L, sharedNanos));
		} finally {
			restoreProperty(previous);
		}
	}

	private static long addCandidate(LP lp, int[] jobs, int jobCount) {
		ArrayList<Integer> sequence = new ArrayList<Integer>(jobs.length);
		for (int job : jobs) {
			sequence.add(Integer.valueOf(job));
		}
		long startNanos = System.nanoTime();
		TWETColumn candidate = new TWETColumn(-1, sequence, jobCount, jobs[0], ColumnSource.PRICING_EXACT, false);
		lp.addOrImproveColumn(candidate);
		return System.nanoTime() - startNanos;
	}

	private static int[][] generateSequences(int count, int length, int jobCount) {
		int[][] sequences = new int[count][length];
		Random random = new Random(20260728L);
		ArrayList<Integer> permutation = new ArrayList<Integer>(jobCount);
		for (int job = 1; job <= jobCount; job++) {
			permutation.add(Integer.valueOf(job));
		}
		for (int i = 0; i < count; i++) {
			Collections.shuffle(permutation, random);
			for (int j = 0; j < length; j++) {
				sequences[i][j] = permutation.get(j).intValue();
			}
		}
		return sequences;
	}

	private static void restoreProperty(String previous) {
		if (previous == null) {
			System.clearProperty("twet.bpc.shareColumnPattern");
		} else {
			System.setProperty("twet.bpc.shareColumnPattern", previous);
		}
	}

	private static Data loadData() throws Exception {
		return new Data("data/40-2/wet040_001_2m.dat", true, true);
	}
}
