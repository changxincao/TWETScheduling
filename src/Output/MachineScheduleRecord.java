package Output;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单台机器上的任务安排。
 */
public final class MachineScheduleRecord {

	private final int machineIndex;
	private final ArrayList<TaskRecord> tasks;

	public MachineScheduleRecord(int machineIndex, List<TaskRecord> tasks) {
		this.machineIndex = machineIndex;
		this.tasks = new ArrayList<TaskRecord>(tasks);
	}

	public int getMachineIndex() {
		return machineIndex;
	}

	public List<TaskRecord> getTasks() {
		return Collections.unmodifiableList(tasks);
	}

	public double getTotalCost() {
		double total = 0.0;
		for (TaskRecord task : tasks) {
			total += task.getCost();
		}
		return total;
	}

}
