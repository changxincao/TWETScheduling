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
		NgDssrHistoryWarmStart warmStart = new NgDssrHistoryWarmStart(4);

		PackedBitSet[] source = neighborhoods(4);
		source[1].add(2);
		source[3].add(4);
		warmStart.recordSameNode(source, 7, Arrays.asList(Integer.valueOf(3), Integer.valueOf(1)), config);

		PackedBitSet[] restored = neighborhoods(4);
		assertTrue(warmStart.applySameNode(restored, 7,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(3)), config), "same context should match");
		assertTrue(restored[1].contains(2) && restored[3].contains(4), "recorded members should be restored");
		restored[1].remove(2);

		PackedBitSet[] restoredAgain = neighborhoods(4);
		assertTrue(warmStart.applySameNode(restoredAgain, 7,
				Arrays.asList(Integer.valueOf(3), Integer.valueOf(1)), config), "stored state should remain available");
		assertTrue(restoredAgain[1].contains(2), "restored state must not alias the stored snapshot");
		assertTrue(!warmStart.applySameNode(neighborhoods(4), 8,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(3)), config), "different node must not match");
		assertTrue(!warmStart.applySameNode(neighborhoods(4), 7,
				Collections.singletonList(Integer.valueOf(1)), config), "different cut set must not match");

		PackedBitSet[] replacement = neighborhoods(4);
		replacement[2].add(4);
		warmStart.recordSameNode(replacement, 8, Collections.<Integer>emptyList(), config);
		assertTrue(!warmStart.applySameNode(neighborhoods(4), 7,
				Arrays.asList(Integer.valueOf(1), Integer.valueOf(3)), config), "only the latest node is retained");
		PackedBitSet[] latest = neighborhoods(4);
		assertTrue(warmStart.applySameNode(latest, 8, Collections.<Integer>emptyList(), config)
				&& latest[2].contains(4), "latest node should be restored");

		System.out.println("NgDssrSameNodeWarmStartTest passed");
	}

	private static PackedBitSet[] neighborhoods(int n) {
		PackedBitSet[] result = new PackedBitSet[n + 2];
		for (int job = 1; job <= n; job++) {
			result[job] = new PackedBitSet(n + 2);
		}
		return result;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
