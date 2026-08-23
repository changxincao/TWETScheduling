package Common.formal;

import java.nio.file.Path;
import java.util.List;

/** 从一个Tanaka父实例抽取出的、跨机器数和实验因子共享的任务集合。 */
public record FormalTaskSet(String id, int size, int parentSize, int caseIndex, Path sourceFile,
		long sourceSelectionSeed, long taskPermutationSeed, double parentWorkload,
		List<Job> jobs) {

	public FormalTaskSet {
		jobs = List.copyOf(jobs);
		if (jobs.size() != size) {
			throw new IllegalArgumentException("Task count does not match size for " + id);
		}
	}

	public double workload() {
		return jobs.stream().mapToDouble(Job::processing).sum();
	}

	public double averageProcessing() {
		return workload() / size;
	}

	/** sourceIndex保留父实例中的任务编号，便于审计嵌套子集。 */
	public record Job(int sourceIndex, int processing, int dueDate, int earlyWeight, int tardyWeight) {
	}
}
