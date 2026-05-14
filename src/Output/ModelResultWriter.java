package Output;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

import Basic.ATIParallel;
import Basic.ArcFlowModel;
import Basic.Data;
import Basic.TimeIndexModel;
import Common.Utility;
import ilog.concert.IloException;
import ilog.cplex.IloCplex;

/**
 * 数学模型结果导出工具。
 */
public final class ModelResultWriter {

	private ModelResultWriter() {
	}

	public static ModelSolveReport capture(String instanceName, String methodName, ArcFlowModel model, double solveSeconds)
			throws IloException {
		return buildReport(instanceName, methodName, model.getData(), model.getCplex(),
				ModelSolutionValidator.fromTaskInfo(model.extractTaskSchedules()), solveSeconds, model.getObjective());
	}

	public static ModelSolveReport capture(String instanceName, String methodName, TimeIndexModel model, double solveSeconds)
			throws IloException {
		double objective = safeObjective(model.getCplex());
		return buildReport(instanceName, methodName, model.getData(), model.getCplex(),
				ModelSolutionValidator.fromTaskInfo(model.extractTaskSchedules()), solveSeconds, objective);
	}

	public static ModelSolveReport capture(String instanceName, String methodName, ATIParallel model, double solveSeconds)
			throws IloException {
		double objective = safeObjective(model.getCplex());
		return buildReport(instanceName, methodName, model.getData(), model.getCplex(),
				ModelSolutionValidator.fromTaskInfo(model.extractTaskSchedules()), solveSeconds, objective);
	}

	private static ModelSolveReport buildReport(String instanceName, String methodName, Data data, IloCplex cplex,
			ArrayList<MachineScheduleRecord> schedules, double solveSeconds, double objectiveValue) throws IloException {
		int variableCount = cplex.getNcols();
		int binaryVariableCount = cplex.getNbinVars();
		int integerVariableCount = cplex.getNintVars();
		int continuousVariableCount = variableCount - binaryVariableCount - integerVariableCount;
		int constraintCount = cplex.getNrows();
		String solveStatus = cplex.getStatus().toString();
		double bestBound = safeBestBound(cplex, objectiveValue);
		double relativeGap = safeGap(cplex);
		return new ModelSolveReport(methodName, instanceName, data.n, data.m, data.CmaxE, solveStatus, solveSeconds,
				objectiveValue, bestBound, relativeGap, variableCount, binaryVariableCount, integerVariableCount,
				continuousVariableCount, constraintCount, schedules);
	}

	public static Path write(Path outputRoot, ModelSolveReport report, ValidationResult validation) throws IOException {
		Path dir = ResultPathUtil.prepareMethodDir(outputRoot, report.getMethodName());
		String stem = ResultPathUtil.instanceStem(report.getInstanceName());
		Path summary = dir.resolve(stem + ".md");
		Path schedule = dir.resolve(stem + ".schedule.csv");

		try (BufferedWriter writer = Files.newBufferedWriter(summary)) {
			writer.write("# " + report.getMethodName() + " 求解结果\n\n");
			writer.write("算例名：`" + report.getInstanceName() + "`\n\n");
			writer.write("求解状态：" + report.getSolveStatus() + "\n\n");
			writer.write(String.format(Locale.US, "求解时间：%.3f 秒\n\n", report.getSolveTimeSeconds()));
			writer.write(String.format(Locale.US, "目标值：%.6f\n\n", report.getObjectiveValue()));
			writer.write(String.format(Locale.US, "最好界：%.6f\n\n", report.getBestBound()));
			writer.write(String.format(Locale.US, "相对 gap：%.6f\n\n", report.getRelativeGap()));
			writer.write(String.format(Locale.US, "问题规模：n=%d, m=%d, horizon=%.3f\n\n", report.getJobCount(),
					report.getMachineCount(), report.getHorizon()));
			writer.write(String.format(Locale.US, "变量数：total=%d, binary=%d, integer=%d, continuous=%d\n\n",
					report.getVariableCount(), report.getBinaryVariableCount(), report.getIntegerVariableCount(),
					report.getContinuousVariableCount()));
			writer.write("约束数：" + report.getConstraintCount() + "\n\n");

			writer.write("## 解验证\n\n");
			writer.write("可行性：" + (validation.isFeasible() ? "通过" : "未通过") + "\n\n");
			writer.write("目标值一致性：" + (validation.isObjectiveConsistent() ? "通过" : "未通过") + "\n\n");
			writer.write(String.format(Locale.US, "重算目标值：%.6f\n\n", validation.getRecomputedObjective()));
			if (!validation.getIssues().isEmpty()) {
				writer.write("问题列表：\n");
				for (String issue : validation.getIssues()) {
					writer.write("- " + issue + "\n");
				}
				writer.write("\n");
			}

			writer.write("## 调度摘要\n\n");
			for (MachineScheduleRecord machine : report.getSchedules()) {
				writer.write(String.format(Locale.US, "机器 %d：任务数=%d，成本=%.6f\n\n", machine.getMachineIndex(),
						machine.getTasks().size(), machine.getTotalCost()));
			}
		}

		try (BufferedWriter writer = Files.newBufferedWriter(schedule)) {
			writer.write("machine,job,start,completion,cost\n");
			for (MachineScheduleRecord machine : report.getSchedules()) {
				for (TaskRecord task : machine.getTasks()) {
					writer.write(String.format(Locale.US, "%d,%d,%.6f,%.6f,%.6f\n", machine.getMachineIndex(),
							task.getJob(), task.getStart(), task.getCompletion(), task.getCost()));
				}
			}
		}
		return summary;
	}

	private static double safeObjective(IloCplex cplex) {
		try {
			return cplex.getObjValue();
		} catch (Exception ex) {
			return Double.NaN;
		}
	}

	private static double safeBestBound(IloCplex cplex, double objectiveFallback) {
		try {
			return cplex.getBestObjValue();
		} catch (Exception ex) {
			return objectiveFallback;
		}
	}

	private static double safeGap(IloCplex cplex) {
		try {
			return cplex.getMIPRelativeGap();
		} catch (Exception ex) {
			return 0.0;
		}
	}

}
