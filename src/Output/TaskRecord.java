package Output;

/**
 * 单个任务在导出结果中的时间记录。
 */
public final class TaskRecord {

	private final int job;
	private final double start;
	private final double completion;
	private final double cost;

	public TaskRecord(int job, double start, double completion, double cost) {
		this.job = job;
		this.start = start;
		this.completion = completion;
		this.cost = cost;
	}

	public int getJob() {
		return job;
	}

	public double getStart() {
		return start;
	}

	public double getCompletion() {
		return completion;
	}

	public double getCost() {
		return cost;
	}

}
