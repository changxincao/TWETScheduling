package TWETBPC.LP;

import java.util.BitSet;
import java.util.List;

import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Util.PackedBitSet;

/**
 * SRI 行构造使用的 LP-local 倒排索引。
 * <p>
 * position 与当前 restricted-column 顺序一致。除“至少访问一次”外，还单独记录
 * “至少访问两次”，避免把旧 VRP 的 elementary-route posting 直接用于允许重复访问的列。
 */
final class SubsetRowColumnPostingIndex {

	private final BitSet[] visitedAtLeastOnce;
	private final BitSet[] visitedAtLeastTwice;
	private int indexedColumns;

	SubsetRowColumnPostingIndex(int jobCount) {
		visitedAtLeastOnce = new BitSet[jobCount + 1];
		visitedAtLeastTwice = new BitSet[jobCount + 1];
		for (int job = 1; job <= jobCount; job++) {
			visitedAtLeastOnce[job] = new BitSet();
			visitedAtLeastTwice[job] = new BitSet();
		}
	}

	void rebuild(List<Integer> columnIds, Pool pool) {
		clear();
		for (int position = 0; position < columnIds.size(); position++) {
			append(position, pool.getColumn(columnIds.get(position).intValue()));
		}
	}

	void append(int position, TWETColumn column) {
		if (position != indexedColumns) {
			throw new IllegalStateException("SRI posting positions must be appended in restricted-column order.");
		}
		PackedBitSet jobs = column.getJobs();
		for (int job = jobs.nextSetBit(1); job >= 1 && job < visitedAtLeastOnce.length;
				job = jobs.nextSetBit(job + 1)) {
			visitedAtLeastOnce[job].set(position);
			if (column.getJobVisitCount(job) >= 2) {
				visitedAtLeastTwice[job].set(position);
			}
		}
		indexedColumns++;
	}

	/**
	 * 返回系数可能非零的 restricted positions。
	 * <p>
	 * paper SRI 的 multiplier 为 1/2，非零系数要求 scope 总访问次数至少为 2；
	 * limited-memory 只会进一步把状态清零，因此同一候选仍是安全上集。其他 multiplier
	 * 使用 scope posting 并集作为保守路径，最终系数仍由统一 evaluator 决定。
	 */
	BitSet candidatePositions(TWETCut cut) {
		BitSet candidates = new BitSet(indexedColumns);
		List<Integer> scopeJobs = cut.getScopeJobs();
		if (Double.compare(cut.getMultiplier(), 0.5) != 0) {
			for (int job : scopeJobs) {
				if (isValidJob(job)) {
					candidates.or(visitedAtLeastOnce[job]);
				}
			}
			return candidates;
		}

		BitSet seenOnce = new BitSet(indexedColumns);
		for (int job : scopeJobs) {
			if (!isValidJob(job)) {
				continue;
			}
			candidates.or(visitedAtLeastTwice[job]);
			BitSet pairCandidates = (BitSet) visitedAtLeastOnce[job].clone();
			pairCandidates.and(seenOnce);
			candidates.or(pairCandidates);
			seenOnce.or(visitedAtLeastOnce[job]);
		}
		return candidates;
	}

	int size() {
		return indexedColumns;
	}

	private boolean isValidJob(int job) {
		return job >= 1 && job < visitedAtLeastOnce.length;
	}

	private void clear() {
		for (int job = 1; job < visitedAtLeastOnce.length; job++) {
			visitedAtLeastOnce[job].clear();
			visitedAtLeastTwice[job].clear();
		}
		indexedColumns = 0;
	}
}
