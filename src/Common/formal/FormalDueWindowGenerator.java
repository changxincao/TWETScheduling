package Common.formal;

/** 由Tanaka due date构造跨机器数共享的due-window中心。 */
public final class FormalDueWindowGenerator {

	public int[] generateCenters(FormalTaskSet taskSet) {
		double workloadRatio = taskSet.workload() / taskSet.parentWorkload();
		int referenceMachines = FormalExperimentDesign.referenceMachines(taskSet.size());
		int[] centers = new int[taskSet.size() + 1];
		for (int index = 1; index <= taskSet.size(); index++) {
			FormalTaskSet.Job job = taskSet.jobs().get(index - 1);
			long scaledDueDate = Math.round(job.dueDate() * workloadRatio);
			centers[index] = Math.toIntExact((scaledDueDate + referenceMachines - 1L) / referenceMachines);
		}
		return centers;
	}
}
