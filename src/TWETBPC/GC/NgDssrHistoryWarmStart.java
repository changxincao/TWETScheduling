package TWETBPC.GC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import TWETBPC.TWETBPCConfig;
import TWETBPC.Util.PackedBitSet;

/**
 * 2026-07-03: ng-DSSR 初始 ng-set 的历史窗口实验状态。
 * 只记录正式 exact pricing 结束后的 final ng-set；repair、strong trial 和诊断 cross-check 不写入，
 * 避免把临时节点或修复阶段的集合带入主线 warm start。
 */
final class NgDssrHistoryWarmStart {

	private final int n;
	private final ArrayDeque<PackedBitSet[]> snapshots;
	private int[][] memberCounts;
	private int[] sizeSums;

	NgDssrHistoryWarmStart(int n) {
		this.n = n;
		this.snapshots = new ArrayDeque<PackedBitSet[]>();
	}

	boolean hasHistory() {
		return !snapshots.isEmpty();
	}

	void record(PackedBitSet[] neighborhoods, TWETBPCConfig config) {
		int window = Math.max(0, config.ngDssrHistoryWarmStartWindowSize);
		if (window <= 0 || neighborhoods == null) {
			return;
		}
		ensureCounters();
		PackedBitSet[] copy = copyNeighborhoods(neighborhoods);
		snapshots.addLast(copy);
		addSnapshot(copy, 1);
		while (snapshots.size() > window) {
			PackedBitSet[] removed = snapshots.removeFirst();
			addSnapshot(removed, -1);
		}
	}

	boolean apply(PackedBitSet[] target, TWETBPCConfig config, boolean rootNode) {
		if (!config.enableNgDssrHistoryWarmStart || target == null || snapshots.isEmpty()) {
			return false;
		}
		if (rootNode && !config.ngDssrHistoryWarmStartUseRoot) {
			return false;
		}
		ensureCounters();
		double memberThreshold = config.ngDssrHistoryWarmStartFrequencyThreshold;
		double highThreshold = config.ngDssrHistoryWarmStartHighConfidenceThreshold;
		int sampleCount = snapshots.size();
		for (int job = 1; job <= n; job++) {
			target[job] = new PackedBitSet(n + 2);
			target[job].add(job);
			double avgSize = ((double) sizeSums[job]) / sampleCount;
			int targetSize = Math.max(1, (int) Math.floor(avgSize + 1.0e-9));
			if (targetSize < n && highConfidenceNonSelfCount(job, sampleCount, highThreshold) + 1 > targetSize) {
				targetSize = Math.min(n, (int) Math.ceil(avgSize - 1.0e-9));
			}
			ArrayList<MemberFrequency> candidates = frequentMembers(job, sampleCount, memberThreshold);
			for (int i = 0; i < candidates.size() && target[job].cardinality() < targetSize; i++) {
				target[job].add(candidates.get(i).member);
			}
		}
		return true;
	}

	String summary() {
		if (snapshots.isEmpty()) {
			return "historyWarmStart=empty";
		}
		return "historyWarmStart=samples" + snapshots.size();
	}

	private void ensureCounters() {
		if (memberCounts == null) {
			memberCounts = new int[n + 2][n + 2];
			sizeSums = new int[n + 2];
		}
	}

	private PackedBitSet[] copyNeighborhoods(PackedBitSet[] neighborhoods) {
		PackedBitSet[] copy = new PackedBitSet[n + 2];
		for (int job = 1; job <= n; job++) {
			copy[job] = new PackedBitSet(n + 2);
			PackedBitSet source = neighborhoods[job];
			if (source == null) {
				copy[job].add(job);
				continue;
			}
			for (int member = source.nextSetBit(1); member >= 1 && member <= n; member = source.nextSetBit(member + 1)) {
				copy[job].add(member);
			}
			if (!copy[job].contains(job)) {
				copy[job].add(job);
			}
		}
		return copy;
	}

	private void addSnapshot(PackedBitSet[] snapshot, int delta) {
		for (int job = 1; job <= n; job++) {
			PackedBitSet set = snapshot[job];
			if (set == null) {
				continue;
			}
			sizeSums[job] += delta * set.cardinality();
			for (int member = set.nextSetBit(1); member >= 1 && member <= n; member = set.nextSetBit(member + 1)) {
				memberCounts[job][member] += delta;
			}
		}
	}

	private int highConfidenceNonSelfCount(int job, int sampleCount, double threshold) {
		int count = 0;
		for (int member = 1; member <= n; member++) {
			if (member == job) {
				continue;
			}
			if (((double) memberCounts[job][member]) / sampleCount > threshold) {
				count++;
			}
		}
		return count;
	}

	private ArrayList<MemberFrequency> frequentMembers(int job, int sampleCount, double threshold) {
		ArrayList<MemberFrequency> members = new ArrayList<MemberFrequency>();
		for (int member = 1; member <= n; member++) {
			if (member == job) {
				continue;
			}
			double frequency = ((double) memberCounts[job][member]) / sampleCount;
			if (frequency > threshold) {
				members.add(new MemberFrequency(member, frequency));
			}
		}
		Collections.sort(members, new Comparator<MemberFrequency>() {
			@Override
			public int compare(MemberFrequency left, MemberFrequency right) {
				int byFrequency = Double.compare(right.frequency, left.frequency);
				if (byFrequency != 0) {
					return byFrequency;
				}
				return Integer.compare(left.member, right.member);
			}
		});
		return members;
	}

	private static final class MemberFrequency {
		final int member;
		final double frequency;

		MemberFrequency(int member, double frequency) {
			this.member = member;
			this.frequency = frequency;
		}
	}
}
