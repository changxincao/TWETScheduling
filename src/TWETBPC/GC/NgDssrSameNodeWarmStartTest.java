package TWETBPC.GC;

import java.util.Arrays;
import java.util.Collections;

import TWETBPC.TWETBPCConfig;
import TWETBPC.Util.PackedBitSet;

/** 同 node final ng-set warm-start 的轻量状态测试。 */
public final class NgDssrSameNodeWarmStartTest {

	private NgDssrSameNodeWarmStartTest() {
	}

	public static void main(String[] args) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.enableNgDssrSameNodeWarmStart = true;
		config.ngDssrSameNodeWarmStartWindowSize = 3;
		config.ngDssrSameNodeWarmStartPerJobLimit = 2;
		config.ngDssrSameNodeWarmStartGlobalPairLimit = 3;
		config.ngDssrSameNodeWarmStartTriggerRounds = 3;
		NgDssrHistoryWarmStart warmStart = new NgDssrHistoryWarmStart(4);

		PackedBitSet[] first = neighborhoods(4);
		first[1].add(3);
		first[3].add(1);
		warmStart.recordSameNode(first, 7, Arrays.asList(Integer.valueOf(3), Integer.valueOf(1)), 2, config);
		assertTrue(!warmStart.applySameNode(baseNeighborhoods(4), 7,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(3)), config), "easy sample must not warm start");

		PackedBitSet[] second = neighborhoods(4);
		second[1].add(3);
		second[2].add(4);
		second[3].add(1);
		warmStart.recordSameNode(second, 7, Arrays.asList(Integer.valueOf(1), Integer.valueOf(3)), 3, config);

		PackedBitSet[] restored = baseNeighborhoods(4);
		assertTrue(warmStart.applySameNode(restored, 7,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(3)), config), "same context should match");
		assertTrue(restored[1].contains(3) && restored[3].contains(1), "latest hard members should be appended");
		assertTrue(restored[1].cardinality() == 2 && restored[3].cardinality() == 2,
				"warm start should retain base members and add a bounded history seed");
		assertTrue(totalMembers(restored, 4) <= totalMembers(baseNeighborhoods(4), 4)
				+ config.ngDssrSameNodeWarmStartGlobalPairLimit,
				"global history budget must bound every initialization");
		assertTrue(!warmStart.applySameNode(baseNeighborhoods(4), 8,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(3)), config), "different node must not match");
		assertTrue(!warmStart.applySameNode(baseNeighborhoods(4), 7,
				Collections.singletonList(Integer.valueOf(1)), config), "different cut set must not match");

		PackedBitSet[] replacement = neighborhoods(4);
		replacement[2].add(4);
		warmStart.recordSameNode(replacement, 8, Collections.<Integer>emptyList(), 3, config);
		assertTrue(!warmStart.applySameNode(baseNeighborhoods(4), 7,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(3)), config), "only the latest node is retained");
		PackedBitSet[] latest = baseNeighborhoods(4);
		assertTrue(warmStart.applySameNode(latest, 8, Collections.<Integer>emptyList(), config)
				&& latest[2].contains(4), "one hard sample should provide a bounded next-exact seed");

		System.out.println("NgDssrSameNodeWarmStartTest passed");
	}

	private static PackedBitSet[] neighborhoods(int n) {
		PackedBitSet[] result = new PackedBitSet[n + 2];
		for (int job = 1; job <= n; job++) {
			result[job] = new PackedBitSet(n + 2);
		}
		return result;
	}

	private static PackedBitSet[] baseNeighborhoods(int n) {
		PackedBitSet[] result = neighborhoods(n);
		for (int job = 1; job <= n; job++) {
			result[job].add(job == n ? 1 : job + 1);
		}
		return result;
	}

	private static int totalMembers(PackedBitSet[] neighborhoods, int n) {
		int total = 0;
		for (int job = 1; job <= n; job++) {
			total += neighborhoods[job].cardinality();
		}
		return total;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
