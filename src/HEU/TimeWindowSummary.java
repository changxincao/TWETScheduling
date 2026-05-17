package HEU;

import java.util.List;

import Basic.Data;
import Common.Utility;

/**
 * 2026-05-17: Vidal 式硬时间窗预判摘要。
 * <p>
 * 这个类只用于 move 评估前的安全剪枝：如果它判断不可行，则该拼接在当前硬时间窗下必然不可行；
 * 如果它判断可能可行，后续仍然要走原来的分段线性函数 merge 来计算真实成本。
 * 因此这里宁可漏掉一些不可行候选，也不能误删可能可行的候选。
 */
final class TimeWindowSummary {
	static final TimeWindowSummary EMPTY = new TimeWindowSummary(true, true, 0, 0, 0, 0);

	final boolean empty;
	final boolean feasible;
	final int firstJob;
	final int lastJob;
	final double durationAfterFirst;
	final double latestFirstCompletion;

	private TimeWindowSummary(boolean empty, boolean feasible, int firstJob, int lastJob, double durationAfterFirst,
			double latestFirstCompletion) {
		this.empty = empty;
		this.feasible = feasible;
		this.firstJob = firstJob;
		this.lastJob = lastJob;
		this.durationAfterFirst = durationAfterFirst;
		this.latestFirstCompletion = latestFirstCompletion;
	}

	static TimeWindowSummary fromComputed(boolean feasible, int firstJob, int lastJob, double durationAfterFirst,
			double latestFirstCompletion) {
		return new TimeWindowSummary(false, feasible, firstJob, lastJob, durationAfterFirst, latestFirstCompletion);
	}

	static TimeWindowSummary of(Data data, List<Integer> jobs) {
		if (jobs == null || jobs.isEmpty()) {
			return EMPTY;
		}
		int first = jobs.get(0);
		int last = jobs.get(jobs.size() - 1);
		double durationAfterFirst = 0;
		for (int i = 1; i < jobs.size(); i++) {
			int prev = jobs.get(i - 1);
			int job = jobs.get(i);
			durationAfterFirst += data.s[prev][job] + data.p[job];
		}

		double latest = data.hardWindowEnd[last];
		boolean feasible = !Utility.compareGt(data.hardWindowStart[last], latest);
		for (int i = jobs.size() - 2; i >= 0 && feasible; i--) {
			int job = jobs.get(i);
			int next = jobs.get(i + 1);
			latest = Math.min(data.hardWindowEnd[job], latest - data.s[job][next] - data.p[next]);
			if (Utility.compareLt(latest, data.hardWindowStart[job])) {
				feasible = false;
			}
		}
		return new TimeWindowSummary(false, feasible, first, last, durationAfterFirst, latest);
	}

	static boolean mayFormSequence(Data data, TimeWindowSummary... parts) {
		TimeWindowSummary firstPart = null;
		for (TimeWindowSummary part : parts) {
			if (part != null && !part.empty) {
				firstPart = part;
				break;
			}
		}
		if (firstPart == null) {
			return true;
		}
		if (!firstPart.feasible) {
			return false;
		}

		double firstCompletion = data.s[0][firstPart.firstJob] + data.p[firstPart.firstJob];
		if (Utility.compareGt(firstCompletion, firstPart.latestFirstCompletion)) {
			return false;
		}
		double currentEndLowerBound = Math.max(firstCompletion, data.hardWindowStart[firstPart.firstJob])
				+ firstPart.durationAfterFirst;
		int currentLastJob = firstPart.lastJob;
		boolean seenFirst = false;

		for (TimeWindowSummary part : parts) {
			if (part == null || part.empty) {
				continue;
			}
			if (!seenFirst) {
				seenFirst = true;
				continue;
			}
			if (!part.feasible) {
				return false;
			}
			double nextFirstCompletionLowerBound = currentEndLowerBound + data.s[currentLastJob][part.firstJob]
					+ data.p[part.firstJob];
			if (Utility.compareGt(nextFirstCompletionLowerBound, part.latestFirstCompletion)) {
				return false;
			}
			currentEndLowerBound = Math.max(nextFirstCompletionLowerBound, data.hardWindowStart[part.firstJob])
					+ part.durationAfterFirst;
			currentLastJob = part.lastJob;
		}
		return true;
	}
}
