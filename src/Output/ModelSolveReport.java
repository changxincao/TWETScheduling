package Output;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于数学模型求解后的统一摘要。
 */
public final class ModelSolveReport {

	private final String methodName;
	private final String instanceName;
	private final int jobCount;
	private final int machineCount;
	private final double horizon;
	private final String solveStatus;
	private final double solveTimeSeconds;
	private final double objectiveValue;
	private final double bestBound;
	private final double relativeGap;
	private final int variableCount;
	private final int binaryVariableCount;
	private final int integerVariableCount;
	private final int continuousVariableCount;
	private final int constraintCount;
	private final ArrayList<MachineScheduleRecord> schedules;

	public ModelSolveReport(String methodName, String instanceName, int jobCount, int machineCount, double horizon,
			String solveStatus, double solveTimeSeconds, double objectiveValue, double bestBound, double relativeGap,
			int variableCount, int binaryVariableCount, int integerVariableCount, int continuousVariableCount,
			int constraintCount, List<MachineScheduleRecord> schedules) {
		this.methodName = methodName;
		this.instanceName = instanceName;
		this.jobCount = jobCount;
		this.machineCount = machineCount;
		this.horizon = horizon;
		this.solveStatus = solveStatus;
		this.solveTimeSeconds = solveTimeSeconds;
		this.objectiveValue = objectiveValue;
		this.bestBound = bestBound;
		this.relativeGap = relativeGap;
		this.variableCount = variableCount;
		this.binaryVariableCount = binaryVariableCount;
		this.integerVariableCount = integerVariableCount;
		this.continuousVariableCount = continuousVariableCount;
		this.constraintCount = constraintCount;
		this.schedules = new ArrayList<MachineScheduleRecord>(schedules);
	}

	public String getMethodName() {
		return methodName;
	}

	public String getInstanceName() {
		return instanceName;
	}

	public int getJobCount() {
		return jobCount;
	}

	public int getMachineCount() {
		return machineCount;
	}

	public double getHorizon() {
		return horizon;
	}

	public String getSolveStatus() {
		return solveStatus;
	}

	public double getSolveTimeSeconds() {
		return solveTimeSeconds;
	}

	public double getObjectiveValue() {
		return objectiveValue;
	}

	public double getBestBound() {
		return bestBound;
	}

	public double getRelativeGap() {
		return relativeGap;
	}

	public int getVariableCount() {
		return variableCount;
	}

	public int getBinaryVariableCount() {
		return binaryVariableCount;
	}

	public int getIntegerVariableCount() {
		return integerVariableCount;
	}

	public int getContinuousVariableCount() {
		return continuousVariableCount;
	}

	public int getConstraintCount() {
		return constraintCount;
	}

	public List<MachineScheduleRecord> getSchedules() {
		return Collections.unmodifiableList(schedules);
	}

}
