package Output;

import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Common.Utility;

/**
 * 数学模型解的统一验证器。
 */
public final class ModelSolutionValidator {

	private ModelSolutionValidator() {
	}

	public static ValidationResult validate(Data data, List<MachineScheduleRecord> schedules, double reportedObjective) {
		ArrayList<String> issues = new ArrayList<String>();
		boolean[] seen = new boolean[data.n + 1];
		double recomputedObjective = 0.0;

		if (schedules.size() > data.m) {
			issues.add("机器条数超出上限: used=" + schedules.size() + ", m=" + data.m);
		}

		for (MachineScheduleRecord machine : schedules) {
			TaskRecord prev = null;
			int prevJob = 0;
			for (TaskRecord task : machine.getTasks()) {
				if (task.getJob() < 1 || task.getJob() > data.n) {
					issues.add("存在非法任务编号: " + task.getJob());
					continue;
				}
				if (seen[task.getJob()]) {
					issues.add("任务被重复安排: job=" + task.getJob());
				}
				seen[task.getJob()] = true;

				double expectedProcessing = data.p[task.getJob()];
				if (Utility.compareGt(Math.abs((task.getCompletion() - task.getStart()) - expectedProcessing), 1e-6)) {
					issues.add("任务加工时长不匹配: job=" + task.getJob());
				}

				if (prev != null) {
					double requiredStart = prev.getCompletion() + data.s[prevJob][task.getJob()];
					if (Utility.compareLt(task.getStart(), requiredStart)) {
						issues.add("机器内时序或 setup 约束被破坏: prev=" + prevJob + ", next=" + task.getJob());
					}
				} else {
					double requiredStart = data.s[0][task.getJob()];
					if (Utility.compareLt(task.getStart(), requiredStart)) {
						issues.add("首任务开始时间小于源点 setup: job=" + task.getJob());
					}
				}

				double taskCost = data.w_e[task.getJob()] * Math.max(data.d_e[task.getJob()] - task.getCompletion(), 0.0)
						+ data.w_t[task.getJob()] * Math.max(task.getCompletion() - data.d_l[task.getJob()], 0.0)
						+ data.getSetupCost(prevJob, task.getJob());
				recomputedObjective += taskCost;
				prev = task;
				prevJob = task.getJob();
			}
		}

		for (int job = 1; job <= data.n; job++) {
			if (!seen[job]) {
				issues.add("存在未安排任务: job=" + job);
			}
		}

		boolean objectiveConsistent;
		if (Double.isFinite(reportedObjective)) {
			objectiveConsistent = Utility.compareLe(Math.abs(recomputedObjective - reportedObjective), Math.max(1e-6,
					1e-6 * Math.max(1.0, Math.abs(reportedObjective))));
			if (!objectiveConsistent) {
				issues.add("目标值不一致: reported=" + reportedObjective + ", recomputed=" + recomputedObjective);
			}
		} else {
			objectiveConsistent = false;
			issues.add("报告目标值不可用");
		}

		return new ValidationResult(issues.isEmpty(), objectiveConsistent, recomputedObjective, issues);
	}

	public static ArrayList<MachineScheduleRecord> fromTaskInfo(List<ArrayList<Utility.TaskInfo>> schedules) {
		ArrayList<MachineScheduleRecord> records = new ArrayList<MachineScheduleRecord>(schedules.size());
		for (int machineIndex = 0; machineIndex < schedules.size(); machineIndex++) {
			ArrayList<TaskRecord> tasks = new ArrayList<TaskRecord>();
			for (Utility.TaskInfo task : schedules.get(machineIndex)) {
				tasks.add(new TaskRecord(task.job, task.start, task.completion, task.cost));
			}
			records.add(new MachineScheduleRecord(machineIndex + 1, tasks));
		}
		return records;
	}

}
