package HEU;

import java.util.*;
import java.util.stream.Collectors;

import Basic.Data;
import Common.Configure;
import Common.Utility;

// 任务类，用于存储任务的属性
class Job {
	int id; // 任务ID
	double r; // 释放时间 (release time)
	double p; // 执行时间 (processing time)

	public Job(int id, double r, double p) {
		this.id = id;
		this.r = r;
		this.p = p;
	}

	@Override
	public String toString() {
		return "Job{id=" + id + ", r=" + r + ", p=" + p + "}";
	}
}

// 用于存储任务的调度时间信息
class JobTimingInfo {
	int id; // 任务ID
	double startTime; // 任务开始时间
	double finishTime; // 任务完成时间

	public JobTimingInfo(int id, double startTime, double finishTime) {
		this.id = id;
		this.startTime = startTime;
		this.finishTime = finishTime;
	}

	@Override
	public String toString() {
		return "JobTimingInfo{id=" + id + ", start=" + String.format("%.2f", startTime) + ", finish="
				+ String.format("%.2f", finishTime) + "}";
	}
}

// 用于封装调度结果
class ScheduleSolution {
	List<List<Integer>> schedule; // 调度方案：每个子列表代表一台机器上的任务ID序列
	double makespan; // 当前调度方案的最大完工时间
	Map<Integer, JobTimingInfo> jobDetails; // 当前调度方案下，各任务的详细开始和结束时间

	public ScheduleSolution(List<List<Integer>> schedule, double makespan, Map<Integer, JobTimingInfo> jobDetails) {
		this.schedule = schedule;
		this.makespan = makespan;
		this.jobDetails = jobDetails;
	}
}

public class SchedulerForReleaseNoWait {

	private List<Job> jobs; // 任务列表
	private int numMachines; // 机器数量
	private double[][] setupTimes; // setupTimes[i][j] 表示从任务i转换到任务j的设置时间
	private double[] initialSetupTimes; // initialSetupTimes[j] 表示任务j作为机器上第一个任务时的设置时间

	/**
	 * 构造函数
	 * 
	 * @param jobsData               任务数据列表 (Job对象的列表)
	 * @param numMachines            机器数量
	 * @param setupTimesMatrix       s_ij 设置时间矩阵
	 * @param initialSetupTimesArray s_0j 初始设置时间数组 (如果为null，则第一个任务的设置时间视为0)
	 */
	public SchedulerForReleaseNoWait(Data data) {
		this.jobs=new ArrayList<Job>();
		for (int i = 1; i <= data.n; i++) {
			Job job = new Job(i-1, data.d_l[i] - data.p[i], data.p[i]);
			this.jobs.add(job);
		}
		int numJobs = data.n;
		this.numMachines = data.m;
		this.setupTimes = new double[numJobs][numJobs];
		this.initialSetupTimes=new double[numJobs];
		for (int i = 0; i < data.n; i++) {
			for (int j = 0; j < data.n; j++) {
				this.setupTimes[i][j] = data.s[i + 1][j + 1];
			}
			this.initialSetupTimes[i] = data.s[0][i + 1];
		}

	}

	/**
	 * 计算单台机器上给定任务序列的完成时间
	 * 
	 * @param machineJobIds 单台机器上按顺序排列的任务ID列表
	 * @return 该机器上最后一个任务的完成时间
	 */
	private double calculateMachineMakespan(List<Integer> machineJobIds) {
		if (machineJobIds == null || machineJobIds.isEmpty()) {
			return 0.0;
		}

		double currentTime = 0.0; // 当前机器的完成时间
		int lastJobId = -1; // 机器上处理的上一个任务的ID，-1表示初始状态

		for (int jobId : machineJobIds) {
			Job currentJob = findJobById(jobId);
			if (currentJob == null) {
				System.err.println("警告: 在计算机器完工时间时未找到任务 ID: " + jobId);
				continue; // 或者抛出异常
			}

			double setupDuration = 0.0;
			if (lastJobId == -1) { // 如果是机器上的第一个任务
				setupDuration = initialSetupTimes[jobId];
			} else {
				setupDuration = setupTimes[lastJobId][jobId];
			}

			// 任务的开始时间需要考虑其释放时间 和 (机器当前完成时间 + 设置时间)
			double startTime = Math.max(currentJob.r, currentTime + setupDuration);
			currentTime = startTime + currentJob.p; // 更新机器的完成时间
			lastJobId = jobId; // 更新机器上的最后一个任务
		}
		return currentTime;
	}

	/**
	 * 计算整个调度方案的最大完工时间 (Makespan)
	 * 
	 * @param fullSchedule 完整的调度方案 (一个列表，每个元素是代表一台机器的任务ID列表)
	 * @return 整体的Makespan
	 */
	public double calculateOverallMakespan(List<List<Integer>> fullSchedule) {
		if (fullSchedule == null || fullSchedule.isEmpty()) {
			return Double.POSITIVE_INFINITY; // 或者0，取决于问题定义
		}
		double maxMakespan = 0.0;
		for (List<Integer> machineSchedule : fullSchedule) {
			maxMakespan = Math.max(maxMakespan, calculateMachineMakespan(machineSchedule));
		}
		return maxMakespan;
	}

	/**
	 * 根据任务ID查找任务对象 (辅助函数) 假设任务ID是连续的且从0开始，对应其在jobs列表中的索引。
	 * 如果不是这种情况，需要更复杂的查找逻辑（例如，使用Map）。
	 */
	private Job findJobById(int jobId) {
		// 假设 job.id 就是其在 this.jobs 列表中的索引
		if (jobId >= 0 && jobId < this.jobs.size()) {
			Job job = this.jobs.get(jobId);
			return job;
		}

		return null; // Or throw an IllegalArgumentException
	}

	/**
	 * 构造性启发式算法：基于最早完成时间 (Earliest Completion Time - ECT)
	 * 
	 * @return ScheduleSolution 对象，包含生成的初始调度、makespan和任务详情
	 */
	public ScheduleSolution constructiveHeuristic() {
		List<List<Integer>> schedule = new ArrayList<>();
		for (int i = 0; i < numMachines; i++) {
			schedule.add(new ArrayList<>()); // 为每台机器初始化一个空的任务列表
		}

		// 记录每台机器当前的可用时间（即完成最后一个任务的时间）
		double[] machineAvailableTime = new double[numMachines];
		Arrays.fill(machineAvailableTime, 0.0);

		// 记录每台机器上最后安排的任务ID，用于计算后续设置时间
		int[] lastJobOnMachine = new int[numMachines];
		Arrays.fill(lastJobOnMachine, -1); // -1 表示机器上还没有任务

		// 存储所有未被调度的任务ID
		Set<Integer> unscheduledJobIds = new HashSet<>();
		for (Job job : this.jobs) {
			unscheduledJobIds.add(job.id);
		}

		// 存储每个任务的详细调度信息 (开始时间，结束时间)
		Map<Integer, JobTimingInfo> jobDetails = new HashMap<>();

		while (!unscheduledJobIds.isEmpty()) {
			int bestJobId = -1; // 本轮迭代中选出的最佳任务ID
			int bestMachineIdx = -1; // 选出的最佳任务要分配到的机器索引
			double minCompletionTime = Double.POSITIVE_INFINITY; // 记录最小的可能完成时间
			double bestStartTimeForSelected = -1; // 选出任务对应的开始时间（用于决胜）

			// 遍历所有未调度的任务
			for (int jobId : unscheduledJobIds) {
				Job currentJob = findJobById(jobId);
				if (currentJob == null)
					continue;

				// 遍历所有机器，尝试将当前任务分配给它们
				for (int mIdx = 0; mIdx < numMachines; mIdx++) {
					int prevJobId = lastJobOnMachine[mIdx]; // 该机器上的上一个任务
					double setupDuration = 0.0;

					if (prevJobId == -1) { // 如果是机器上的第一个任务
							setupDuration = initialSetupTimes[jobId];
					} else { // 如果不是第一个任务，则查找S_ij
							setupDuration = setupTimes[prevJobId][jobId];
						}
					

					// 计算任务的可能开始时间
					// 考虑: 1. 任务本身的释放时间
					// 2. (机器上次空闲时间 + 从上个任务到当前任务的设置时间)
					double earliestPossibleStartTime = Math.max(currentJob.r,
							machineAvailableTime[mIdx] + setupDuration);
					double currentCompletionTime = earliestPossibleStartTime + currentJob.p;

					// 寻找最小完成时间
					if (Utility.compareLt(currentCompletionTime, minCompletionTime)) {
						minCompletionTime = currentCompletionTime;
						bestJobId = jobId;
						bestMachineIdx = mIdx;
						bestStartTimeForSelected = earliestPossibleStartTime;
					} else if (Utility.compareEq(currentCompletionTime, minCompletionTime)) { // 如果完成时间相同，进行决胜
						// 优先选择开始时间更早的
						if (Utility.compareLt(earliestPossibleStartTime, bestStartTimeForSelected)) {
							bestJobId = jobId;
							bestMachineIdx = mIdx;
							bestStartTimeForSelected = earliestPossibleStartTime;
						}
						// 如果开始时间也相同，优先选择机器索引更小的 (任意固定规则)
						else if (Utility.compareEq(earliestPossibleStartTime, bestStartTimeForSelected) && mIdx < bestMachineIdx) {
							bestJobId = jobId;
							bestMachineIdx = mIdx;
							// bestStartTimeForSelected 不变
						}
					}
				}
			}

			// 如果找到了合适的任务进行调度
			if (bestJobId != -1) {
				schedule.get(bestMachineIdx).add(bestJobId); // 将任务加入对应机器的调度列表
				machineAvailableTime[bestMachineIdx] = minCompletionTime; // 更新机器的可用时间
				lastJobOnMachine[bestMachineIdx] = bestJobId; // 更新机器上的最后一个任务
				unscheduledJobIds.remove(bestJobId); // 从未调度任务集合中移除
				jobDetails.put(bestJobId, new JobTimingInfo(bestJobId, bestStartTimeForSelected, minCompletionTime));
			} else {
				// 如果没有任务可以调度，但仍有未调度任务，说明可能存在问题
				// （例如，所有剩余任务都因某种原因无法被安排）
				System.err.println("构造性启发式错误：在迭代中无法找到可调度的任务，但仍有未调度任务: " + unscheduledJobIds);
				break; // 退出循环，避免死循环
			}
		}
		double makespan=0;
		for(int m=0;m<numMachines;m++) {
			makespan=Math.max(makespan, machineAvailableTime[m]);
		}
				
		return new ScheduleSolution(schedule, makespan, jobDetails);
	}

	/**
	 * 局部搜索算法：基于迭代改进 (Iterative Improvement)
	 * 
	 * @param initialSolution    构造性启发式算法产生的初始调度方案
	 * @param maxIterations      最大迭代次数
	 * @param noImprovementLimit 连续未改进的迭代次数达到此限制时停止
	 * @return 经过局部搜索改进后的 ScheduleSolution 对象
	 */
	public ScheduleSolution localSearch(ScheduleSolution initialSolution, int maxIterations, int noImprovementLimit) {
		List<List<Integer>> currentSchedule = deepCopySchedule(initialSolution.schedule);
		double currentMakespan = initialSolution.makespan; // 当前解的makespan

		List<List<Integer>> bestScheduleFound = deepCopySchedule(currentSchedule); // 存储找到的最佳调度
		double bestMakespanFound = currentMakespan; // 存储找到的最佳makespan

		int iterationsWithoutImprovement = 0;
		int iter; // 将iter声明在循环外部，以便在循环后打印最终迭代次数

		for (iter = 0; iter < maxIterations; iter++) {
			if (iterationsWithoutImprovement >= noImprovementLimit) {
				if (Configure.debugAlgorithmCounters) {
					System.out.println("局部搜索：连续 " + noImprovementLimit + " 次迭代未找到改进，提前停止。");
				}
				break;
			}

			boolean improvedInThisIteration = false;

			// --- 邻域操作1: 机器内任务重插入 (Intra-machine move) ---
			// 遍历每台机器
			for (int mIdx = 0; mIdx < numMachines; mIdx++) {
				if (currentSchedule.get(mIdx).size() < 2)
					continue; // 机器上至少需要2个任务才能进行移动

				// 遍历机器mIdx上的每个任务 (作为要移动的任务)
				for (int jobPosOrigin = 0; jobPosOrigin < currentSchedule.get(mIdx).size(); jobPosOrigin++) {
					int jobToMoveId = currentSchedule.get(mIdx).get(jobPosOrigin);

					// 创建一个临时调度，从原位置移除jobToMoveId
					List<List<Integer>> tempScheduleForIntraMove = deepCopySchedule(currentSchedule);
					tempScheduleForIntraMove.get(mIdx).remove(jobPosOrigin);

					// 尝试将jobToMoveId插入到机器mIdx的其他位置
					for (int jobPosTarget = 0; jobPosTarget <= tempScheduleForIntraMove.get(mIdx)
							.size(); jobPosTarget++) {
						// 注意：如果jobPosTarget等于jobPosOrigin，在移除后插入相当于回到了原始位置的“邻近”位置，
						// 这通常是允许的，因为序列会改变。

						List<List<Integer>> candidateSchedule = deepCopySchedule(tempScheduleForIntraMove);
						candidateSchedule.get(mIdx).add(jobPosTarget, jobToMoveId); // 插入到新位置

						double candidateMakespan = calculateOverallMakespan(candidateSchedule);

						// 如果找到了一个更好的解 (首次改进策略)
						if (Utility.compareLt(candidateMakespan, bestMakespanFound)) { // 与全局最优比较
							bestMakespanFound = candidateMakespan;
							bestScheduleFound = deepCopySchedule(candidateSchedule);

							// 更新当前解，以便下一次迭代基于此改进解进行
							currentSchedule = deepCopySchedule(bestScheduleFound);
							currentMakespan = bestMakespanFound;

							iterationsWithoutImprovement = 0; // 重置未改进计数
							improvedInThisIteration = true;
							// System.out.println(" LS Intra-Move: Iter " + iter + ", Job " + jobToMoveId +
							// " on M" + mIdx + " -> pos " + jobPosTarget + ". New best makespan: " +
							// bestMakespanFound);
							break; // 跳出内层插入位置循环
						}
					}
					if (improvedInThisIteration)
						break; // 跳出被移动任务循环
				}
				if (improvedInThisIteration)
					break; // 跳出机器循环
			}
			// 如果在机器内移动中找到了改进，则立即开始下一次主迭代
			if (improvedInThisIteration)
				continue;

			// --- 邻域操作2: 机器间任务重插入 (Inter-machine move) ---
			// 遍历每台源机器 mFromIdx
			for (int mFromIdx = 0; mFromIdx < numMachines; mFromIdx++) {
				if (currentSchedule.get(mFromIdx).isEmpty())
					continue; // 源机器没有任务可移

				// 遍历源机器上的每个任务 (作为要移动的任务)
				for (int jobPosOrigin = 0; jobPosOrigin < currentSchedule.get(mFromIdx).size(); jobPosOrigin++) {
					int jobToMoveId = currentSchedule.get(mFromIdx).get(jobPosOrigin);

					// 遍历每个目标机器 mToIdx
					for (int mToIdx = 0; mToIdx < numMachines; mToIdx++) {
						if (mFromIdx == mToIdx)
							continue; // 不能移动到同一台机器 (这属于机器内移动)

						// 创建一个临时调度，从源机器移除jobToMoveId
						List<List<Integer>> tempScheduleForInterMove = deepCopySchedule(currentSchedule);
						tempScheduleForInterMove.get(mFromIdx).remove(jobPosOrigin);

						// 尝试将jobToMoveId插入到目标机器mToIdx的每个可能位置
						for (int jobPosTarget = 0; jobPosTarget <= tempScheduleForInterMove.get(mToIdx)
								.size(); jobPosTarget++) {
							List<List<Integer>> candidateSchedule = deepCopySchedule(tempScheduleForInterMove);
							candidateSchedule.get(mToIdx).add(jobPosTarget, jobToMoveId); // 插入到目标机器

							double candidateMakespan = calculateOverallMakespan(candidateSchedule);

							if (Utility.compareLt(candidateMakespan, bestMakespanFound)) { // 与全局最优比较
								bestMakespanFound = candidateMakespan;
								bestScheduleFound = deepCopySchedule(candidateSchedule);

								currentSchedule = deepCopySchedule(bestScheduleFound);
								currentMakespan = bestMakespanFound;

								iterationsWithoutImprovement = 0;
								improvedInThisIteration = true;
								// System.out.println(" LS Inter-Move: Iter " + iter + ", Job " + jobToMoveId +
								// " from M" + mFromIdx + " to M" + mToIdx + " pos " + jobPosTarget + ". New
								// best makespan: " + bestMakespanFound);
								break; // 插入位置循环
							}
						}
						if (improvedInThisIteration)
							break; // 目标机器循环
					}
					if (improvedInThisIteration)
						break; // 被移动任务循环
				}
				if (improvedInThisIteration)
					break; // 源机器循环
			}

			// 如果本轮迭代没有任何改进
			if (!improvedInThisIteration) {
				iterationsWithoutImprovement++;
			}
		} // 结束主迭代循环

		if (Configure.debugAlgorithmCounters) {
			System.out.println("局部搜索完成。总迭代次数: " + iter);
		}
		// 为最终的最佳调度方案计算详细的作业时间
		Map<Integer, JobTimingInfo> finalJobDetails = calculateJobTimingsForSchedule(bestScheduleFound);
		return new ScheduleSolution(bestScheduleFound, bestMakespanFound, finalJobDetails);
	}

	/**
	 * 为给定的完整调度方案计算每个任务的精确开始和结束时间
	 * 
	 * @param schedule 完整的调度方案
	 * @return 一个Map，键是任务ID，值是JobTimingInfo对象
	 */
	private Map<Integer, JobTimingInfo> calculateJobTimingsForSchedule(List<List<Integer>> schedule) {
		Map<Integer, JobTimingInfo> jobDetailsMap = new HashMap<>();
		for (int mIdx = 0; mIdx < numMachines; mIdx++) {
			double machineCurrentTime = 0.0;
			int lastJobIdOnMachine = -1;
			if (schedule.size() <= mIdx || schedule.get(mIdx) == null)
				continue;

			for (int jobId : schedule.get(mIdx)) {
				Job currentJob = findJobById(jobId);
				if (currentJob == null)
					continue;

				double setupDuration = 0.0;
				if (lastJobIdOnMachine == -1) {
					if (jobId >= 0 && jobId < initialSetupTimes.length) {
						setupDuration = initialSetupTimes[jobId];
					}
				} else {
					if (lastJobIdOnMachine >= 0 && lastJobIdOnMachine < setupTimes.length && jobId >= 0
							&& jobId < setupTimes[lastJobIdOnMachine].length) {
						setupDuration = setupTimes[lastJobIdOnMachine][jobId];
					}
				}

				double startTime = Math.max(currentJob.r, machineCurrentTime + setupDuration);
				double finishTime = startTime + currentJob.p;
				jobDetailsMap.put(jobId, new JobTimingInfo(jobId, startTime, finishTime));

				machineCurrentTime = finishTime; // 更新机器的完成时间
				lastJobIdOnMachine = jobId; // 更新机器上的最后一个任务
			}
		}
		return jobDetailsMap;
	}

	/**
	 * 深拷贝调度方案 (List<List<Integer>>)
	 * 
	 * @param originalSchedule 原始调度方案
	 * @return 一个新的、内容相同的调度方案副本
	 */
	private List<List<Integer>> deepCopySchedule(List<List<Integer>> originalSchedule) {
		List<List<Integer>> newSchedule = new ArrayList<>(originalSchedule.size());
		for (List<Integer> machineSchedule : originalSchedule) {
			newSchedule.add(new ArrayList<>(machineSchedule)); // 拷贝每个机器的任务列表
		}
		return newSchedule;
	}

	/**
	 * 求解调度问题的主函数
	 * 
	 * @param CmaxU                已知的全局上界 (仅用于最终结果的比较，当前算法不直接用此上界进行剪枝)
	 * @param lsMaxIterations      局部搜索的最大迭代次数
	 * @param lsNoImprovementLimit 局部搜索中连续未改进迭代次数的上限，达到则停止
	 * @return 最优的 ScheduleSolution 对象，包含最佳调度方案、makespan和任务详情
	 */
	public double solve(double CmaxU, int lsMaxIterations, int lsNoImprovementLimit) {
		

		if (Configure.debugAlgorithmCounters) {
			System.out.println("开始执行构造性启发式算法...");
		}
		long startTimeCH = System.currentTimeMillis();
		ScheduleSolution initialSolution = constructiveHeuristic();
		long endTimeCH = System.currentTimeMillis();
		if (Configure.debugAlgorithmCounters) {
			System.out.println("构造性启发式算法完成，耗时: " + (endTimeCH - startTimeCH) + " ms");
			System.out.println("初始调度 Makespan: " + String.format("%.2f", initialSolution.makespan));
		}
		// System.out.println("初始调度方案: " + initialSolution.schedule);

//		if (initialSolution.makespan > CmaxU) {
//			System.out.println("提示：初始启发式解 (" + String.format("%.2f", initialSolution.makespan) + ") 差于已知的上界 CmaxU ("
//					+ String.format("%.2f", CmaxU) + ").");
//		}

		ScheduleSolution bestOverallSolution = initialSolution; // 初始化最佳解为构造算法的结果

//		System.out.println("\n开始执行局部搜索算法...");
//		long startTimeLS = System.currentTimeMillis();
//		ScheduleSolution lsImprovedSolution = localSearch(initialSolution, lsMaxIterations, lsNoImprovementLimit);
//		long endTimeLS = System.currentTimeMillis();
//		System.out.println("局部搜索算法完成，耗时: " + (endTimeLS - startTimeLS) + " ms");
//
//		if (lsImprovedSolution.makespan < bestOverallSolution.makespan) {
//			System.out.println("局部搜索找到了一个更好的解。");
//			bestOverallSolution = lsImprovedSolution;
//		} else {
//			System.out.println("局部搜索未能进一步改进构造性启发式算法的结果。");
//		}

//		System.out.println("\n--- 最终求解结果 ---");
//		System.out.println("最终调度 Makespan: " + String.format("%.2f", bestOverallSolution.makespan));
		// System.out.println("最终调度方案: " + bestOverallSolution.schedule);

//		if (bestOverallSolution.makespan < CmaxU) {
//			System.out.println("找到的 Makespan (" + String.format("%.2f", bestOverallSolution.makespan)
//					+ ") 优于已知的上界 CmaxU (" + String.format("%.2f", CmaxU) + ").");
//		} else if (bestOverallSolution.makespan > CmaxU) {
//			System.out.println("找到的 Makespan (" + String.format("%.2f", bestOverallSolution.makespan)
//					+ ") 差于或等于已知的上界 CmaxU (" + String.format("%.2f", CmaxU) + ").");
//		} else { // 等于
//			System.out.println("找到的 Makespan (" + String.format("%.2f", bestOverallSolution.makespan)
//					+ ") 等于已知的上界 CmaxU (" + String.format("%.2f", CmaxU) + ").");
//		}
		
		//不做local search,无优化的local search过于耗时,且和初始解差距不大
		return bestOverallSolution.makespan;
	}

	
}
