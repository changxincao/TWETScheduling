package TWETBPC.Model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import TWETBPC.Util.PackedBitSet;
import TWETBPC.Util.SequenceSignature;

/**
 * SP1 外包列：一列表示同一个外包集合 O。
 * <p>
 * 该列不参与机器 arc 分支，只在覆盖约束中给集合内 job 一个 1 系数，并在外包列数量约束中给 1 系数。
 */
public final class TWETOutsourcingColumn {

	private final int id;
	private final ArrayList<Integer> jobs;
	private final PackedBitSet jobSet;
	private final SequenceSignature signature;
	private final double baseline;
	private final double cost;
	private final ColumnSource source;
	private final boolean seedColumn;

	public TWETOutsourcingColumn(int id, List<Integer> jobs, int jobCount, double baseline, double cost,
			ColumnSource source, boolean seedColumn) {
		this.id = id;
		this.jobs = new ArrayList<Integer>(jobs);
		Collections.sort(this.jobs);
		this.jobSet = PackedBitSet.ofJobs(jobCount, this.jobs);
		this.signature = new SequenceSignature(this.jobs);
		this.baseline = baseline;
		this.cost = cost;
		this.source = source;
		this.seedColumn = seedColumn;
	}

	public int getId() {
		return id;
	}

	public List<Integer> getJobs() {
		return Collections.unmodifiableList(jobs);
	}

	public PackedBitSet getJobSet() {
		return jobSet;
	}

	public SequenceSignature getSignature() {
		return signature;
	}

	public double getBaseline() {
		return baseline;
	}

	public double getCost() {
		return cost;
	}

	public ColumnSource getSource() {
		return source;
	}

	public boolean isSeedColumn() {
		return seedColumn;
	}

	public boolean containsJob(int job) {
		return jobSet.contains(job);
	}

	public int size() {
		return jobs.size();
	}

	@Override
	public String toString() {
		return "TWETOutsourcingColumn{id=" + id + ", jobs=" + jobs + ", baseline=" + baseline
				+ ", cost=" + cost + "}";
	}
}
