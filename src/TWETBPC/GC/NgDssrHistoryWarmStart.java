package TWETBPC.GC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
	private int sameNodeId = Integer.MIN_VALUE;
	private ArrayList<Integer> sameNodeActiveCutIds;
	private final ArrayDeque<SameNodeSnapshot> sameNodeSnapshots = new ArrayDeque<SameNodeSnapshot>();
	private int sameNodeLastSelected;
	private int sameNodeLastAdded;

	NgDssrHistoryWarmStart(int n) {
		this.n = n;
		this.snapshots = new ArrayDeque<PackedBitSet[]>();
	}

	boolean hasHistory() {
		return !snapshots.isEmpty();
	}

	/**
	 * 同一 node 内只把最近困难 exact 的成员有界追加到基础 seed；每 job 和全局数量均受限，
	 * 因而不会随 exact 次数单调增大。
	 */
	boolean applySameNode(PackedBitSet[] target, int nodeId, List<Integer> activeCutIds,
			TWETBPCConfig config) {
		sameNodeLastSelected = 0;
		sameNodeLastAdded = 0;
		if (!config.enableNgDssrSameNodeWarmStart || target == null || sameNodeId != nodeId
				|| !sameActiveCutIds(activeCutIds) || sameNodeSnapshots.isEmpty()
				|| sameNodeSnapshots.peekLast().rounds < config.ngDssrSameNodeWarmStartTriggerRounds) {
			return false;
		}
		ArrayList<BoundedMember> candidates = new ArrayList<BoundedMember>();
		for (int job = 1; job <= n; job++) {
			int[] counts = new int[n + 1];
			for (SameNodeSnapshot snapshot : sameNodeSnapshots) {
				PackedBitSet set = snapshot.neighborhoods[job];
				for (int member = set.nextSetBit(1); member >= 1 && member <= n;
						member = set.nextSetBit(member + 1)) {
					counts[member]++;
				}
			}
			PackedBitSet latest = sameNodeSnapshots.peekLast().neighborhoods[job];
			for (int member = latest.nextSetBit(1); member >= 1 && member <= n;
					member = latest.nextSetBit(member + 1)) {
				if (member == job) {
					continue;
				}
				candidates.add(new BoundedMember(job, member, counts[member]));
			}
		}
		Collections.sort(candidates, new Comparator<BoundedMember>() {
			@Override
			public int compare(BoundedMember left, BoundedMember right) {
				int byCount = Integer.compare(right.count, left.count);
				if (byCount != 0) {
					return byCount;
				}
				int byJob = Integer.compare(left.job, right.job);
				return byJob != 0 ? byJob : Integer.compare(left.member, right.member);
			}
		});
		int[] learnedByJob = new int[n + 1];
		int globalLimit = Math.max(0, config.ngDssrSameNodeWarmStartGlobalPairLimit);
		int perJobLimit = Math.max(0, config.ngDssrSameNodeWarmStartPerJobLimit);
		int used = 0;
		for (BoundedMember candidate : candidates) {
			if (used >= globalLimit || learnedByJob[candidate.job] >= perJobLimit
					|| target[candidate.job].contains(candidate.member)) {
				continue;
			}
			target[candidate.job].add(candidate.member);
			learnedByJob[candidate.job]++;
			sameNodeLastSelected++;
			sameNodeLastAdded++;
			used++;
		}
		return true;
	}

	String sameNodeSummary() {
		return "selected" + sameNodeLastSelected + "/added" + sameNodeLastAdded;
	}

	void recordSameNode(PackedBitSet[] neighborhoods, int nodeId, List<Integer> activeCutIds, int rounds,
			TWETBPCConfig config) {
		if (!config.enableNgDssrSameNodeWarmStart || neighborhoods == null) {
			return;
		}
		ArrayList<Integer> cutIds = sortedCutIds(activeCutIds);
		if (sameNodeId != nodeId || sameNodeActiveCutIds == null || !sameNodeActiveCutIds.equals(cutIds)) {
			sameNodeSnapshots.clear();
			sameNodeId = nodeId;
			sameNodeActiveCutIds = cutIds;
		}
		sameNodeSnapshots.addLast(new SameNodeSnapshot(copyNeighborhoods(neighborhoods), rounds));
		int window = Math.max(2, config.ngDssrSameNodeWarmStartWindowSize);
		while (sameNodeSnapshots.size() > window) {
			sameNodeSnapshots.removeFirst();
		}
	}

	private boolean sameActiveCutIds(List<Integer> activeCutIds) {
		return sameNodeActiveCutIds != null && sameNodeActiveCutIds.equals(sortedCutIds(activeCutIds));
	}

	private ArrayList<Integer> sortedCutIds(List<Integer> activeCutIds) {
		ArrayList<Integer> copy = new ArrayList<Integer>();
		if (activeCutIds != null) {
			copy.addAll(activeCutIds);
		}
		Collections.sort(copy);
		return copy;
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

	boolean apply(PackedBitSet[] target, TWETBPCConfig config, boolean allowRootWarmStart) {
		if (!config.enableNgDssrHistoryWarmStart || target == null || snapshots.isEmpty()) {
			return false;
		}
		if (!allowRootWarmStart) {
			return false;
		}
		ensureCounters();
		double memberThreshold = config.ngDssrHistoryWarmStartFrequencyThreshold;
		double highThreshold = config.ngDssrHistoryWarmStartHighConfidenceThreshold;
		int sampleCount = snapshots.size();
		for (int job = 1; job <= n; job++) {
			target[job] = new PackedBitSet(n + 2);
			double avgSize = ((double) sizeSums[job]) / sampleCount;
			int targetSize = Math.max(0, (int) Math.floor(avgSize + 1.0e-9));
			if (targetSize < n - 1 && highConfidenceNonSelfCount(job, sampleCount, highThreshold) > targetSize) {
				targetSize = Math.min(n - 1, (int) Math.ceil(avgSize - 1.0e-9));
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
				continue;
			}
			for (int member = source.nextSetBit(1); member >= 1 && member <= n; member = source.nextSetBit(member + 1)) {
				if (member != job) {
					copy[job].add(member);
				}
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

	private static final class SameNodeSnapshot {
		final PackedBitSet[] neighborhoods;
		final int rounds;

		SameNodeSnapshot(PackedBitSet[] neighborhoods, int rounds) {
			this.neighborhoods = neighborhoods;
			this.rounds = rounds;
		}
	}

	private static final class BoundedMember {
		final int job;
		final int member;
		final int count;

		BoundedMember(int job, int member, int count) {
			this.job = job;
			this.member = member;
			this.count = count;
		}
	}
}
