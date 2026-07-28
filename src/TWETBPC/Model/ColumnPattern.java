package TWETBPC.Model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import TWETBPC.Util.PackedBitSet;
import TWETBPC.Util.SequenceSignature;

/**
 * 一条列中不随 id、成本和来源变化的结构。
 * <p>
 * 2026-07-28: pricing candidate 与 Pool 中的正式列共享该对象，避免同一 sequence
 * 重复复制并重复构造 signature、job 集和访问次数。
 */
public final class ColumnPattern {

	private final ArrayList<Integer> sequence;
	private final List<Integer> sequenceView;
	private final SequenceSignature signature;
	private final PackedBitSet jobs;
	private final int[] jobVisitCounts;

	public ColumnPattern(List<Integer> sequence, int jobCount) {
		this.sequence = new ArrayList<Integer>(sequence);
		this.sequenceView = Collections.unmodifiableList(this.sequence);
		this.signature = new SequenceSignature(this.sequence);
		this.jobs = PackedBitSet.ofJobs(jobCount, this.sequence);
		this.jobVisitCounts = new int[jobCount + 1];
		for (int job : this.sequence) {
			if (job >= 1 && job <= jobCount) {
				this.jobVisitCounts[job]++;
			}
		}
	}

	public List<Integer> getSequence() {
		return sequenceView;
	}

	public SequenceSignature getSignature() {
		return signature;
	}

	/**
	 * 返回内部只读位集。调用方不得修改；该约定与原 TWETColumn.getJobs() 一致。
	 */
	public PackedBitSet getJobs() {
		return jobs;
	}

	public int size() {
		return sequence.size();
	}

	public int getJobVisitCount(int job) {
		return job >= 0 && job < jobVisitCounts.length ? jobVisitCounts[job] : 0;
	}
}
