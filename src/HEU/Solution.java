package HEU;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import Basic.ATIParallel;
import Basic.ArcFlowModel;
import Basic.Data;
import Basic.TimeIndexModel;
import Common.Configure;
import Common.PiecewiseLinearFunction;
import Common.PiecewisePlotter;
import Common.Utility;
import Common.Utility.TimerManager;
import ilog.concert.IloException;

public class Solution {
	Data data;
	ArrayList<ArrayList<Integer>> sequences;// 每个机器上的任务序列
	ArrayList<Integer> outsourcedJobs;// 2026-05-16: 外包任务集合。顺序只用于存储和候选生成，不表示外包加工顺序。
	ArrayList<ArrayList<Double>> completions;// 最优解下，每个机器上的任务完工时间
	// 暂时没做更新
	ArrayList<ArrayList<PiecewiseLinearFunction>> fFunctions;// 每个机器上每个任务惩罚函数,正向传播
	ArrayList<ArrayList<PiecewiseLinearFunction>> bFunctions;// 每个机器上每个任务惩罚函数,反向传播
	public double curCost;
	double[] cost;// 每个机器上的成本
	double outsourcingBaselineTotal;// 2026-05-16: 外包任务 baseline 之和 B(O)，真正成本为 G(B(O))。
	double outsourcingCostTotal;// 外包集合的总成本 G(B(O))，curCost=机器成本之和+该值。
	PiecewiseLinearFunction[][][] fFunctions_hg;// normal
	PiecewiseLinearFunction[][][] bFunctions_hg;// normal
	PiecewiseLinearFunction[][][] fFunctions_gh;// reverse
	PiecewiseLinearFunction[][][] bFunctions_gh;// reverse
	PiecewiseLinearFunction[][][][] fFunctions_hgl_normal;
	PiecewiseLinearFunction[][][][] bFunctions_hgl_normal;
	PiecewiseLinearFunction[][][][] fFunctions_hgl_reverse;
	PiecewiseLinearFunction[][][][] bFunctions_hgl_reverse;
	double[][] cumDurationNormal;// 记录每个机器上，0-id之间累计的duration
	double[][] cumDurationReverse;// 记录每个机器上，id-0之间累计的duration，可能设置时间不对称

	TimeWindowSummary[][][] timeWindowSummary_hg;// 2026-05-17: 子序列 h..g 正向硬窗摘要，用于 move 前安全剪枝
	TimeWindowSummary[][][] timeWindowSummary_gh;// 2026-05-17: 子序列 h..g 反向硬窗摘要，用于 reverse 片段剪枝

	public Solution(Data data) {
		this.data = data;
		cost = new double[data.m];
		this.sequences = new ArrayList<ArrayList<Integer>>();
		this.outsourcedJobs = new ArrayList<Integer>();
		this.fFunctions = new ArrayList<ArrayList<PiecewiseLinearFunction>>();
		this.bFunctions = new ArrayList<ArrayList<PiecewiseLinearFunction>>();
		for (int m = 0; m < data.m; m++) {
			sequences.add(new ArrayList<Integer>());
			fFunctions.add(new ArrayList<PiecewiseLinearFunction>());
			bFunctions.add(new ArrayList<PiecewiseLinearFunction>());

		}
	}

	public Solution copy() {
		Solution solution = new Solution(this.data);
		solution.sequences.clear();
		solution.outsourcedJobs = new ArrayList<Integer>(this.outsourcedJobs);
		solution.fFunctions.clear();
		solution.bFunctions.clear();
		for (int m = 0; m < data.m; m++) {
			solution.sequences.add(new ArrayList<Integer>(this.sequences.get(m)));
			solution.fFunctions.add(new ArrayList<PiecewiseLinearFunction>(this.fFunctions.get(m)));
			solution.bFunctions.add(new ArrayList<PiecewiseLinearFunction>(this.bFunctions.get(m)));

		}
		solution.curCost = this.curCost;
		solution.cost = Arrays.copyOf(this.cost, data.m);// 每个机器上的成本
		solution.outsourcingBaselineTotal = this.outsourcingBaselineTotal;
		solution.outsourcingCostTotal = this.outsourcingCostTotal;
		solution.cumDurationNormal = new double[data.m][];
		solution.cumDurationReverse = new double[data.m][];

		for (int m = 0; m < data.m; m++) {
			solution.cumDurationNormal[m] = Arrays.copyOf(cumDurationNormal[m], cumDurationNormal[m].length);
			solution.cumDurationReverse[m] = Arrays.copyOf(cumDurationReverse[m], cumDurationReverse[m].length);

		}
		solution.fFunctions_hg = fFunctions_hg;// 可直接等于，数组直接重新new 不会清空
		solution.bFunctions_hg = bFunctions_hg;// normal
		solution.fFunctions_gh = fFunctions_gh;
		solution.bFunctions_gh = bFunctions_gh;// reverse
		solution.fFunctions_hgl_normal = fFunctions_hgl_normal;
		solution.bFunctions_hgl_normal = bFunctions_hgl_normal;
		solution.fFunctions_hgl_reverse = fFunctions_hgl_reverse;
		solution.bFunctions_hgl_reverse = bFunctions_hgl_reverse;
		solution.timeWindowSummary_hg = timeWindowSummary_hg;
		solution.timeWindowSummary_gh = timeWindowSummary_gh;

		return solution;
	}

	public void initialize_function() {
		fFunctions_hg = new PiecewiseLinearFunction[data.m][][];
		bFunctions_hg = new PiecewiseLinearFunction[data.m][][];
		fFunctions_gh = new PiecewiseLinearFunction[data.m][][];
		bFunctions_gh = new PiecewiseLinearFunction[data.m][][];
		fFunctions_hgl_normal = new PiecewiseLinearFunction[data.m][][][];
		bFunctions_hgl_normal = new PiecewiseLinearFunction[data.m][][][];
		fFunctions_hgl_reverse = new PiecewiseLinearFunction[data.m][][][];
		bFunctions_hgl_reverse = new PiecewiseLinearFunction[data.m][][][];
		timeWindowSummary_hg = new TimeWindowSummary[data.m][][];
		timeWindowSummary_gh = new TimeWindowSummary[data.m][][];
		cumDurationNormal = new double[data.m][];
		cumDurationReverse = new double[data.m][];
		for (int m = 0; m < data.m; m++) {

			int size = sequences.get(m).size();
			fFunctions_hg[m] = new PiecewiseLinearFunction[size][size];// 应该不需要包含起始点
			bFunctions_hg[m] = new PiecewiseLinearFunction[size][size];
			fFunctions_gh[m] = new PiecewiseLinearFunction[size][size];// 应该不需要包含起始点
			bFunctions_gh[m] = new PiecewiseLinearFunction[size][size];
			fFunctions_hgl_normal[m] = new PiecewiseLinearFunction[size][size + 1][size];// 应该不需要包含起始点
			bFunctions_hgl_normal[m] = new PiecewiseLinearFunction[size][size + 1][size];
			fFunctions_hgl_reverse[m] = new PiecewiseLinearFunction[size][size + 1][size];// 应该不需要包含起始点
			bFunctions_hgl_reverse[m] = new PiecewiseLinearFunction[size][size + 1][size];
			timeWindowSummary_hg[m] = new TimeWindowSummary[size][size];
			timeWindowSummary_gh[m] = new TimeWindowSummary[size][size];
			// 最后一位[size]表示h2=-1的时候,即把某一段插入到最前边
			cumDurationNormal[m] = new double[size];
			cumDurationReverse[m] = new double[size];
		}
	}

	public void initialize_function(int m) {
		int size = sequences.get(m).size();
		fFunctions_hg[m] = new PiecewiseLinearFunction[size][size];// 应该不需要包含起始点
		bFunctions_hg[m] = new PiecewiseLinearFunction[size][size];
		fFunctions_gh[m] = new PiecewiseLinearFunction[size][size];// 应该不需要包含起始点
		bFunctions_gh[m] = new PiecewiseLinearFunction[size][size];
		fFunctions_hgl_normal[m] = new PiecewiseLinearFunction[size][size + 1][size];// 应该不需要包含起始点
		bFunctions_hgl_normal[m] = new PiecewiseLinearFunction[size][size + 1][size];
		fFunctions_hgl_reverse[m] = new PiecewiseLinearFunction[size][size + 1][size];// 应该不需要包含起始点
		bFunctions_hgl_reverse[m] = new PiecewiseLinearFunction[size][size + 1][size];
		timeWindowSummary_hg[m] = new TimeWindowSummary[size][size];
		timeWindowSummary_gh[m] = new TimeWindowSummary[size][size];
		// 最后一位[size]表示h2=-1的时候,即把某一段插入到最前边
		cumDurationNormal[m] = new double[size];
		cumDurationReverse[m] = new double[size];

	}

	public void setSequence(ArrayList<ArrayList<Integer>> sequences) {
		this.sequences = sequences;
		this.outsourcedJobs.clear();
		this.outsourcingBaselineTotal = 0;
		this.outsourcingCostTotal = 0;
	}

	/**
	 * 返回当前解中所有机器序列的深拷贝。
	 * <p>
	 * 这个接口是为了让新建的 TWETBPC 包能够读取启发式结果，
	 * 但又不直接暴露内部可变数组引用。
	 * <p>
	 * 之所以返回 copy 而不是原对象，是为了避免：
	 * 外部框架在拆初始列或做调试时，误改动当前启发式解本身。
	 */
	public ArrayList<ArrayList<Integer>> getSequencesCopy() {
		ArrayList<ArrayList<Integer>> copy = new ArrayList<ArrayList<Integer>>(sequences.size());
		for (List<Integer> seq : sequences) {
			copy.add(new ArrayList<Integer>(seq));
		}
		return copy;
	}

	/**
	 * 返回每台机器当前成本数组的副本。
	 * <p>
	 * 该接口同样是为 TWETBPC 新框架预留的只读桥接口。
	 * 当前主要用于 seed/初始列相关的调试和信息提取，
	 * 后续若需要根据机器成本筛选列，也会用到这个接口。
	 */
	public double[] getMachineCostsCopy() {
		return cost == null ? new double[0] : Arrays.copyOf(cost, cost.length);
	}

	public ArrayList<Integer> getOutsourcedJobsCopy() {
		return new ArrayList<Integer>(outsourcedJobs);
	}

	public boolean canOutsource(int job) {
		return job > 0 && job < data.outsourcingCost.length && !Utility.isBigMValue(data.outsourcingCost[job]);
	}

	public double getOutsourcingBaseline(int job) {
		return canOutsource(job) ? data.outsourcingCost[job] : Utility.big_M;
	}

	public double evaluateOutsourcingAddDelta(int job) {
		if (!canOutsource(job)) {
			return Utility.big_M;
		}
		ArrayList<Integer> addJobs = new ArrayList<Integer>();
		addJobs.add(job);
		return evaluateOutsourcingDelta(null, addJobs);
	}

	public double evaluateOutsourcingRemoveDelta(int job) {
		if (!outsourcedJobs.contains(job)) {
			return Utility.big_M;
		}
		ArrayList<Integer> removeJobs = new ArrayList<Integer>();
		removeJobs.add(job);
		return evaluateOutsourcingDelta(removeJobs, null);
	}

	public double getOutsourcingBaseline(List<Integer> jobs) {
		double total = 0;
		if (jobs == null) {
			return total;
		}
		for (int job : jobs) {
			double baseline = getOutsourcingBaseline(job);
			if (Utility.isBigMValue(baseline)) {
				return Utility.big_M;
			}
			total += baseline;
		}
		return total;
	}

	public double evaluateOutsourcingDelta(List<Integer> removeJobs, List<Integer> addJobs) {
		double removeBaseline = getOutsourcingBaseline(removeJobs);
		double addBaseline = getOutsourcingBaseline(addJobs);
		if (Utility.isBigMValue(removeBaseline) || Utility.isBigMValue(addBaseline)) {
			return Utility.big_M;
		}
		double newBaseline = outsourcingBaselineTotal - removeBaseline + addBaseline;
		if (Utility.compareLt(newBaseline, 0)) {
			return Utility.big_M;
		}
		return data.evaluateOutsourcingCost(newBaseline) - outsourcingCostTotal;
	}

	public void addOutsourcedJob(int job) {
		ArrayList<Integer> jobs = new ArrayList<Integer>();
		jobs.add(job);
		addOutsourcedJobs(jobs);
	}

	public void addOutsourcedJobs(List<Integer> jobs) {
		// 2026-05-16: b_j 只是 baseline，真正成本按 G(B(O)) 统一评价。
		// 因此增删任务时必须用函数差分更新，而不是简单加减 b_j。
		outsourcedJobs.addAll(jobs);
		recomputeOutsourcingCostAndCurCost();
	}

	public boolean removeOutsourcedJob(int job) {
		ArrayList<Integer> jobs = new ArrayList<Integer>();
		jobs.add(job);
		return removeOutsourcedJobs(jobs);
	}

	public boolean removeOutsourcedJobs(List<Integer> jobs) {
		boolean removed = false;
		for (int job : jobs) {
			removed = outsourcedJobs.remove(Integer.valueOf(job)) || removed;
		}
		if (removed) {
			recomputeOutsourcingCostAndCurCost();
		}
		return removed;
	}

	public void replaceOutsourcedSegment(int from, int len, List<Integer> replacement) {
		outsourcedJobs.subList(from, from + len).clear();
		outsourcedJobs.addAll(from, replacement);
		recomputeOutsourcingCostAndCurCost();
	}

	private void recomputeOutsourcingCostAndCurCost() {
		double oldCost = outsourcingCostTotal;
		outsourcingBaselineTotal = getOutsourcingBaseline(outsourcedJobs);
		outsourcingCostTotal = data.evaluateOutsourcingCost(outsourcingBaselineTotal);
		curCost += outsourcingCostTotal - oldCost;
	}

	private PiecewiseLinearFunction addSetupCost(PiecewiseLinearFunction function, int fromJob, int toJob) {
		// 2026-05-15: setup time 和 setup cost 都属于同一条弧 i->j。
		// setup time 已经体现在 shiftX(s_ij+p_j) 中；setup cost 不改时间轴，
		// 只把当前累计函数整体纵向平移 kappa_ij。
		return function.shiftYInPlace(data.getSetupCost(fromJob, toJob));
	}

	public void updateFFunctions1ForMachine(int m) {

		// 1 1维
		List<Integer> seq = sequences.get(m);
		List<PiecewiseLinearFunction> f = new ArrayList<>();
		// forward
		for (int i = 0; i < seq.size(); i++) {
			int job = seq.get(i);
			PiecewiseLinearFunction cur;
			if (i == 0) {
				// 2026-05-14: 序列首任务要先从虚拟起点 0 做 setup，再加工任务本身。
				// 因此首任务最早完工时间是 s[0][job] + p[job]，早于该时间的惩罚函数定义域物理不可行。
				cur = data.penaltyFunction[job].setDomain(data.p[job] + data.s[0][job], data.CmaxH);
				addSetupCost(cur, 0, job);
			} else {
				cur = f.get(i - 1).shiftX(data.s[seq.get(i - 1)][job] + data.p[job]).add(data.penaltyFunction[job]);
				addSetupCost(cur, seq.get(i - 1), job);
			}
			cur.minimizePrefixInPlace();
			f.add(cur);
		}
		fFunctions.get(m).clear();
		for (PiecewiseLinearFunction ff : f) {
			fFunctions.get(m).add(ff);
		}

	}

	public void updatebFunctions1ForMachine(int m) {

		List<Integer> seq = sequences.get(m);

		// backward
		List<PiecewiseLinearFunction> b = new ArrayList<>();
		for (int i = seq.size() - 1; i >= 0; i--) {
			b.add(null);
		}
		for (int i = seq.size() - 1; i >= 0; i--) {
			int job = seq.get(i);
			PiecewiseLinearFunction cur;
			if (i == seq.size() - 1) {
//				cur = data.penaltyFunction[job].copy().setDomain(data.p[job] + data.min_s[job], data.Cmax);
				// 2025.5.3 bFunction不需要设domain
				cur = data.penaltyFunction[job].copy();

			} else {
				cur = b.get(i + 1).shiftX(-data.s[job][seq.get(i + 1)] - data.p[seq.get(i + 1)])
						.add(data.penaltyFunction[job]);
				addSetupCost(cur, job, seq.get(i + 1));
			}
			cur.minimizeSuffixInPlace();
			b.set(i, cur);
		}
		bFunctions.get(m).clear();
		for (PiecewiseLinearFunction f : b) {
			bFunctions.get(m).add(f);
		}

	}

	public void updateFunctions2ForMachine(int m) {
		// 更新h-g这一段的函数，注意，这里h应该不会取到虚拟0点，而g是机器上下标>=1的任意一个任务。
		// 这种一段的backward函数当g是最后一个任务的时候，其实和1维的完全等价，此处不同于VRP那种
		// 因为那种一维的是还要回到最终仓库的
		// 不过做法上暂时感觉没啥区别

		// H-G段 normal-forward处理 以及reverse的-backward处理
		PiecewiseLinearFunction[][] fFunction_hg = fFunctions_hg[m];
		PiecewiseLinearFunction[][] bFunction_gh = bFunctions_gh[m];
		ArrayList<Integer> seq = this.sequences.get(m);
		int size = seq.size();
		for (int h = 0; h < size; h++) {
			for (int g = h; g < size; g++) {
				int job = seq.get(g);
				if (g == h) {
//					fFunction_hg[h][g] = data.penaltyFunction[job].copy().setDomain(data.p[job] + data.min_s[job],
//							data.Cmax);
					// 早期：任务g的完工时间至少为他的执行时间+最小setup
					// 2024.5.3：f函数也不应该setdomain,对这种h1-h2的，没有确定的开头，从而这个函数在使用的时候的定义域是不确定的
					// 取决于这个函数前一段拼接的序列内容，所以此处不应该设置定义域，而是再拼接的时候，根据确定的前序拼接段，再确定该函数h1-h2段，h2的最早的完工时间作为开始定义域
					// 即此处将定义域设置完全,都是从0-Cmax
					fFunction_hg[h][g] = data.penaltyFunction[job].copy();
					fFunction_hg[h][g].minimizePrefixInPlace();
					// 2025.5.3 fFunction记录的得是h1-h2段的正常函数，不能记录最小函数
					// 因为使用的时候需要对h1-h2段的fFunction函数先根据前边的序列设置定义域，再取最小化才能保证正确性
					// 否则先最小化，在取定义域可能会有问题，比如一个原始函数是递增的，那么定义域越靠后minimize以后的水平直线越高，但如果先minimize再设置定义域，就没有意义了，此时水平线为较低的线
					// 2025.5.3 b函数应该不需要关注先最小化后截断可行域可能导致的问题，因为b函数是从后往前最小化的，从而即使从前截断一部分，并不影响。
//					2025.5.4 还是不对，这个f函数在递推的时候，不应该函数最小化处理后再加一个函数。这是因为如果后续使用的时候需要限制定义域
					// 此处初始未做限制认为从h1直接开始，从而递推过程中最小化也就相当于从一个很早的定义域开始最小化，例如如果某个函数是个增函数，那最小化以后是一条直线
					// 此时如果记录都按照直接从h1开始的函数最小化以后再存储，那后续限制定义域其实也会出错，比如实际使用的函数f_h1_h2定义域更小，该函数最小化以后为一条直线，取值100
					// 但此处由于没有使用实际计算时的顺序限制定义域，那可能最小化以后值为50，此时递推出来的后续函数都是错的，且无法通过限制定义域得到正确值（因为这种递增函数的最小化抹去了其他信息）
					// 尝试的做法是，这个二维函数记录不做最小化操作，直接记录函数本身，使用时再最小化和限制定义域
					// 而且感觉上其实所有函数不做min也行，定义变了，相当于恰好在此处完成，取min的含义为大于等于t完成
					// 2025.5.5 说明：5.4思考是错误的，此处函数必须存储的是minimize以后的结果，定义必须和原文一样才可以,已修改回去
					// 此外，当用于三段的拼接时，不需要处理定义域的问题，虽然不知道为什么，但不需要

					// bFunction_gh[h][g] = data.penaltyFunction[job].copy().setDomain(data.p[job] +
					// data.min_s[job],
//							data.Cmax);
					// 2025.5.3 b函数不需要设置domain
					bFunction_gh[h][g] = data.penaltyFunction[job].copy();
					bFunction_gh[h][g].minimizeSuffixInPlace();
					// 同理
					continue;
				}

				// b函数暂时认为没影响
				fFunction_hg[h][g] = fFunction_hg[h][g - 1].shiftX(data.s[seq.get(g - 1)][job] + data.p[job])
						.add(data.penaltyFunction[job]);
				addSetupCost(fFunction_hg[h][g], seq.get(g - 1), job);
				fFunction_hg[h][g].minimizePrefixInPlace();

				bFunction_gh[h][g] = bFunction_gh[h][g - 1].shiftX(-data.s[job][seq.get(g - 1)] - data.p[seq.get(g - 1)])
						.add(data.penaltyFunction[job]);
				addSetupCost(bFunction_gh[h][g], job, seq.get(g - 1));
				bFunction_gh[h][g].minimizeSuffixInPlace();
			}
		}

		PiecewiseLinearFunction[][] fFunction_gh = fFunctions_gh[m];
		PiecewiseLinearFunction[][] bFunction_hg = bFunctions_hg[m];

		// H-G段 reverse-forward处理 以及的normal-backward处理
		for (int h = 0; h < size; h++) {
			for (int g = h; g >= 0; g--) {
				int job = seq.get(g);
				if (g == h) {
					// [g][h]存储的是h-g段的逆向函数，对给定的h-g，直接按照[h][g]提取就好了
//					fFunction_gh[g][h] = data.penaltyFunction[job].copy().setDomain(data.p[job] + data.min_s[job],
//							data.Cmax);
					// 2025.5.3同上
					fFunction_gh[g][h] = data.penaltyFunction[job].copy();

					// 早期:任务g的完工时间至少为他的执行时间+最小setup
					fFunction_gh[g][h].minimizePrefixInPlace();
//					bFunction_hg[g][h] = data.penaltyFunction[job].copy().setDomain(data.p[job] + data.min_s[job],
//							data.Cmax);
					// 2025.5.3 b函数不需要设置domain

					bFunction_hg[g][h] = data.penaltyFunction[job].copy();
					bFunction_hg[g][h].minimizeSuffixInPlace();
					// 同理
					continue;
				}

				fFunction_gh[g][h] = fFunction_gh[g + 1][h].shiftX(data.s[seq.get(g + 1)][job] + data.p[job])
						.add(data.penaltyFunction[job]);
				addSetupCost(fFunction_gh[g][h], seq.get(g + 1), job);
				fFunction_gh[g][h].minimizePrefixInPlace();

				bFunction_hg[g][h] = bFunction_hg[g + 1][h].shiftX(-data.s[job][seq.get(g + 1)] - data.p[seq.get(g + 1)])
						.add(data.penaltyFunction[job]);
				addSetupCost(bFunction_hg[g][h], job, seq.get(g + 1));
				bFunction_hg[g][h].minimizeSuffixInPlace();

			}
		}

		// 2025.5.3 现在感觉f_h1_h2和b_h1_h2函数取小相等应该就更合理了，即在应用时，两者取小的时候都是基于前段和后段序列给定的
		// f函数的可行域为h1-h2段的duration+前段最后一个任务到h1第一个任务的时间
		// b函数的可行域为前段最后一个任务到h1第一个任务的时间
		// 在一维函数的情况下，经过测试这两种是等价的，即使用f函数去计算或b函数去计算,只不过他们定义域的计算是基于depot的
		// 此处则是基于前段序列

	}

	public void updateFunctions2ForMachine(int m, double formerShift, int lastJob) {
		// 更新h-g这一段的函数，注意，这里h应该不会取到虚拟0点，而g是机器上下标>=1的任意一个任务。
		// 这种一段的backward函数当g是最后一个任务的时候，其实和1维的完全等价，此处不同于VRP那种
		// 因为那种一维的是还要回到最终仓库的
		// 不过做法上暂时感觉没啥区别
		// 2025.5.5
//		本来以为对merge三段时候函数的处理，即f_h1_h2，b_h1_h2的函数都需要根据前边拼接和后边拼接的段设置可行域
		// 这样的话其实预处理就没用了，因为每次拼接的都不同。
		// 但是一方面，b函数右侧其实总可以看成正无穷的可行域，那其实缩减不缩减都一样，那不需要缩减。
		// 对于f_h1_h2函数，本来以为需要根据左侧拼接的去设置最早的完成时间的定义域，但测试以后发现似乎并不需要，f_h_h函数直接从0开始都不影响，完全不需要考虑f_h_h的最早开始时间
		// 比如什么前边的任务是什么，最小的设置时间等等，这个不需要以后就直接对一个解预处理一次就好了，且不需要考虑定义域
		// 由于这个问题和2005那个VRP还略有区别，depot不存在成本，且回depot没有任何时间，因此对2-opt或某些算子，例如2-opt中从第一个任务选中某个长度做翻转
		// 此时其实拼接是2段拼接，本来对这种直接采用f_0_l表示序列depot-\sigma(0)-\sigma_(l)的晚于t完成的最小成本，但对这种明确是0开头的，定义域就很重要了
		// 所以之前的测试这里总是出错，围绕这里修改陷入了定义域的问题很久，又把f_h1_h2,b_h1_h2的函数定义修改为了不采用minimize的形式，使用时设置定义域，然而这种也不对的。
		// 因为这种必须是从最小的状态设置定义域以后一步一步推过来才对，先推过来在设置定义域不一定对。
		// 这就导致了在merge3段的时候函数定义错误，从而一直出错，且纠结在函数定义域的问题上，但事实上经过测试，3段拼接的时候f_h1_h2和b_h1_h2是不需要考虑定义域问题的。
		// 此外，这里的这种2-opt的2段拼接也可以直接使用3段拼接包含，把depot当作一个纯0的函数就好了，以及最后回到depot的shift为0.

		// 从而这个函数不需要再使用了，这函数的存在本来就是为了给定一个前段序列，设置f_h1_h2的定义域的。b_h1_h2函数的定义域倒是不需要设置，右侧正无穷不需要处理，而左侧取决于拼接的f函数的定义域
		// 处理对应的f函数就好了

		// H-G段 normal-forward处理 以及reverse的-backward处理
		PiecewiseLinearFunction[][] fFunction_hg = fFunctions_hg[m];
		PiecewiseLinearFunction[][] bFunction_gh = bFunctions_gh[m];
		ArrayList<Integer> seq = this.sequences.get(m);
		int size = seq.size();
		for (int h = 0; h < size; h++) {
			for (int g = h; g < size; g++) {
				int job = seq.get(g);
				if (g == h) {
//					fFunction_hg[h][g] = data.penaltyFunction[job].copy().setDomain(data.p[job] + data.min_s[job],
//							data.Cmax);
					// 早期：任务g的完工时间至少为他的执行时间+最小setup
					// 2024.5.3：f函数也不应该setdomain,对这种h1-h2的，没有确定的开头，从而这个函数在使用的时候的定义域是不确定的
					// 取决于这个函数前一段拼接的序列内容，所以此处不应该设置定义域，而是再拼接的时候，根据确定的前序拼接段，再确定该函数h1-h2段，h2的最早的完工时间作为开始定义域
					// 即此处将定义域设置完全,都是从0-Cmax
					// TODO 删掉
					if (Utility.compareGt(formerShift + data.p[job] + data.s[lastJob][job], data.CmaxH))
						continue;
					fFunction_hg[h][g] = data.penaltyFunction[job]
							.setDomain(formerShift + data.p[job] + data.s[lastJob][job], data.CmaxH);
					addSetupCost(fFunction_hg[h][g], lastJob, job);
					fFunction_hg[h][g].minimizePrefixInPlace();

					bFunction_gh[h][g] = data.penaltyFunction[job].copy();
					bFunction_gh[h][g].minimizeSuffixInPlace();
					// 同理
					continue;
				}

				PiecewiseLinearFunction copy = fFunction_hg[h][g - 1];
//				copy.minimizePrefixInPlace(); 
				// b函数暂时认为没影响
				fFunction_hg[h][g] = copy.shiftX(data.s[seq.get(g - 1)][job] + data.p[job])
						.add(data.penaltyFunction[job]);
				addSetupCost(fFunction_hg[h][g], seq.get(g - 1), job);
				fFunction_hg[h][g].minimizePrefixInPlace();
				copy = bFunction_gh[h][g - 1];
//				copy.minimizeSuffixInPlace();
				bFunction_gh[h][g] = copy.shiftX(-data.s[job][seq.get(g - 1)] - data.p[seq.get(g - 1)])
						.add(data.penaltyFunction[job]);
				addSetupCost(bFunction_gh[h][g], job, seq.get(g - 1));
				bFunction_gh[h][g].minimizeSuffixInPlace();
			}
		}

		PiecewiseLinearFunction[][] fFunction_gh = fFunctions_gh[m];
		PiecewiseLinearFunction[][] bFunction_hg = bFunctions_hg[m];

		// H-G段 reverse-forward处理 以及的normal-backward处理
		for (int h = 0; h < size; h++) {
			for (int g = h; g >= 0; g--) {
				int job = seq.get(g);
				if (g == h) {
					// [g][h]存储的是h-g段的逆向函数，对给定的h-g，直接按照[h][g]提取就好了
//					fFunction_gh[g][h] = data.penaltyFunction[job].copy().setDomain(data.p[job] + data.min_s[job],
//							data.Cmax);
					// 2025.5.3同上
					// TODO 删掉
					if (Utility.compareGt(formerShift + data.p[job] + data.s[lastJob][job], data.CmaxH))
						continue;

					fFunction_gh[g][h] = data.penaltyFunction[job]
							.setDomain(formerShift + data.p[job] + data.s[lastJob][job], data.CmaxH);
					addSetupCost(fFunction_gh[g][h], lastJob, job);

					// 早期:任务g的完工时间至少为他的执行时间+最小setup
					fFunction_gh[g][h].minimizePrefixInPlace();
//					bFunction_hg[g][h] = data.penaltyFunction[job].copy().setDomain(data.p[job] + data.min_s[job],
//							data.Cmax);
					// 2025.5.3 b函数不需要设置domain
					bFunction_hg[g][h] = data.penaltyFunction[job]
							.setDomain(formerShift + data.p[job] + data.s[lastJob][job], data.CmaxH);
					addSetupCost(bFunction_hg[g][h], lastJob, job);
					bFunction_hg[g][h].minimizeSuffixInPlace();
					// 同理
					continue;
				}
				PiecewiseLinearFunction copy = fFunction_gh[g + 1][h];
//				copy.minimizePrefixInPlace(); //2025.5.4 同上
				fFunction_gh[g][h] = copy.shiftX(data.s[seq.get(g + 1)][job] + data.p[job])
						.add(data.penaltyFunction[job]);
				addSetupCost(fFunction_gh[g][h], seq.get(g + 1), job);
				fFunction_gh[g][h].minimizePrefixInPlace();
				copy = bFunction_hg[g + 1][h];
//				copy.minimizeSuffixInPlace();
				bFunction_hg[g][h] = copy.shiftX(-data.s[job][seq.get(g + 1)] - data.p[seq.get(g + 1)])
						.add(data.penaltyFunction[job]);
				addSetupCost(bFunction_hg[g][h], job, seq.get(g + 1));
				bFunction_hg[g][h].minimizeSuffixInPlace();

			}
		}

	}

	public void updateFunctions3ForMachine(int m) {
		releaseFunctions3(m);

		// 2025.5.3 这堆函数应该不存在domain的问题,bFunction和前边一样,不需要设置定义域
		// f函数则由于从0出发的,应该隐含了
		int size = sequences.get(m).size();
		ArrayList<Integer> seq = sequences.get(m);
		PiecewiseLinearFunction[][][] fFunction_hgl_normal = fFunctions_hgl_normal[m];
		PiecewiseLinearFunction[][][] fFunction_hgl_reverse = fFunctions_hgl_reverse[m];
		PiecewiseLinearFunction[][][] bFunction_hgl_normal = bFunctions_hgl_normal[m];
		PiecewiseLinearFunction[][][] bFunction_hgl_reverse = bFunctions_hgl_reverse[m];
//		[h1,h1+l]插入到h2,h2+1中间
		for (int h1 = size - 1; h1 >= 0; h1--) {
			for (int h2 = h1 + 1; h2 < Math.min(h1 + IOptOperator.LINS + 1, size); h2++) {
				for (int l = 0; l <= IOptOperator.LPATH && h1 + l < h2; l++) {
					// normal和reverse的f_h1_h2_l函数更新，由于normal和reverse只会被插入到h2之后有影响
					// 第一段剩下的都是0- h1-1,h1+l+1)-h2，两者是相同的
					if (h2 == h1 + l + 1) {
						fFunction_hgl_normal[h1][h2][l] = (h1 == 0 ? data.penaltyFunction[0]
								: fFunctions.get(m).get(h1 - 1))
								.shiftX(data.s[h1 == 0 ? 0 : seq.get(h1 - 1)][seq.get(h2)] + data.p[seq.get(h2)])
								.add(data.penaltyFunction[seq.get(h2)]);
						addSetupCost(fFunction_hgl_normal[h1][h2][l], h1 == 0 ? 0 : seq.get(h1 - 1), seq.get(h2));
						fFunction_hgl_normal[h1][h2][l].minimizePrefixInPlace();
						fFunction_hgl_reverse[h1][h2][l] = fFunction_hgl_normal[h1][h2][l];

					} else {
						fFunction_hgl_normal[h1][h2][l] = fFunction_hgl_normal[h1][h2 - 1][l]
								.shiftX(data.s[seq.get(h2 - 1)][seq.get(h2)] + data.p[seq.get(h2)])
								.add(data.penaltyFunction[seq.get(h2)]);
						addSetupCost(fFunction_hgl_normal[h1][h2][l], seq.get(h2 - 1), seq.get(h2));
						fFunction_hgl_normal[h1][h2][l].minimizePrefixInPlace();
						fFunction_hgl_reverse[h1][h2][l] = fFunction_hgl_normal[h1][h2][l];
					}

					// 第二段为 h1+l -h1,h2+1-0 reverse
					// 第二段为 h1- h1+l,h2+1-0 normal
					if (l == 0) {
						bFunction_hgl_normal[h1][h2][l] = ((h2 == size - 1 ? data.penaltyFunction[0]
								: bFunctions.get(m).get(h2 + 1))
								.shiftX((h2 == size - 1 ? 0
										: -(data.s[seq.get(h1)][seq.get(h2 + 1)] + data.p[seq.get(h2 + 1)])))
								.add(data.penaltyFunction[seq.get(h1)]));
						if (h2 != size - 1) {
							addSetupCost(bFunction_hgl_normal[h1][h2][l], seq.get(h1), seq.get(h2 + 1));
						}
						bFunction_hgl_reverse[h1][h2][l] = bFunction_hgl_normal[h1][h2][l];
						bFunction_hgl_reverse[h1][h2][l].minimizeSuffixInPlace();
					} else {
						bFunction_hgl_reverse[h1][h2][l] = bFunction_hgl_reverse[h1][h2][l - 1]
								.shiftX(-(data.s[seq.get(h1 + l)][seq.get(h1 + l - 1)] + data.p[seq.get(h1 + l - 1)]))
								.add(data.penaltyFunction[seq.get(h1 + l)]);
						addSetupCost(bFunction_hgl_reverse[h1][h2][l], seq.get(h1 + l), seq.get(h1 + l - 1));
						bFunction_hgl_reverse[h1][h2][l].minimizeSuffixInPlace();
						bFunction_hgl_normal[h1][h2][l] = bFunction_hgl_normal[h1 + 1][h2][l - 1]
								.shiftX(-(data.s[seq.get(h1)][seq.get(h1 + 1)] + data.p[seq.get(h1 + 1)]))
								.add(data.penaltyFunction[seq.get(h1)]);
						addSetupCost(bFunction_hgl_normal[h1][h2][l], seq.get(h1), seq.get(h1 + 1));
						bFunction_hgl_normal[h1][h2][l].minimizeSuffixInPlace();

					}
				}

			}
		}

		// backward移动
		for (int h1 = size - 1; h1 >= 0; h1--) {
			for (int h2 = h1 - 2; h2 >= -1; h2--) {
				// Math.max(h1 -IOptOperator.LINS,-1)
				if (h2 == h1 - 1)
					continue;
				for (int l = 0; l < IOptOperator.LPATH + 1 && h1 + l < size; l++) {
					// 第一段为0- h2,h1+l - h1，reverse ,h2表示的是序列中位置h2的元素，如果h2=-1，此处0-h2仍然为0
					// 第一段为0- h2,h1 - h1+l，normal,
					if (l == 0) {
						fFunction_hgl_normal[h1][h2 == -1 ? size
								: h2][l] = (h2 == -1 ? data.penaltyFunction[0] : fFunctions.get(m).get(h2))
										.shiftX(data.s[h2 == -1 ? 0 : seq.get(h2)][seq.get(h1)] + data.p[seq.get(h1)])
										.add(data.penaltyFunction[seq.get(h1)]);
						addSetupCost(fFunction_hgl_normal[h1][h2 == -1 ? size : h2][l], h2 == -1 ? 0 : seq.get(h2),
								seq.get(h1));
						fFunction_hgl_reverse[h1][h2 == -1 ? size : h2][l] = fFunction_hgl_normal[h1][h2 == -1 ? size
								: h2][l];
						fFunction_hgl_reverse[h1][h2 == -1 ? size : h2][l].minimizePrefixInPlace();

					} else {
						fFunction_hgl_reverse[h1][h2 == -1 ? size
								: h2][l] = (fFunction_hgl_reverse[h1 + 1][h2 == -1 ? size : h2][l - 1])
										.shiftX(data.s[seq.get(h1 + 1)][seq.get(h1)] + data.p[seq.get(h1)])
										.add(data.penaltyFunction[seq.get(h1)]);
						addSetupCost(fFunction_hgl_reverse[h1][h2 == -1 ? size : h2][l], seq.get(h1 + 1),
								seq.get(h1));
						// 此处计算h1,h2的时候，对应的h1+1,h2可能是不存在的，因为限制了h2离h1的长度
						// 暂时此处不限制h2位置全部计算出来
						fFunction_hgl_reverse[h1][h2 == -1 ? size : h2][l].minimizePrefixInPlace();
						fFunction_hgl_normal[h1][h2 == -1 ? size
								: h2][l] = (fFunction_hgl_normal[h1][h2 == -1 ? size : h2][l - 1])
										.shiftX(data.s[seq.get(h1 + l - 1)][seq.get(h1 + l)] + data.p[seq.get(h1 + l)])
										.add(data.penaltyFunction[seq.get(h1 + l)]);
						addSetupCost(fFunction_hgl_normal[h1][h2 == -1 ? size : h2][l], seq.get(h1 + l - 1),
								seq.get(h1 + l));
						fFunction_hgl_normal[h1][h2 == -1 ? size : h2][l].minimizePrefixInPlace();
					}

					// 第二段为 h2+1 - h1-1,h1+l+1 -0 reverse
					// 第二段为 h2+1 - h1-1,h1+l+1 -0 normal
					// 若h1+l+1==size,此时h1+l+1认为等价于0，此时相当于[h1,h1+l]把一个调度后半段都截掉了
					// 两种情况下是一致的
					// h1其实不可能等于0，此时h2无取值
					if (h2 == h1 - 2) {
						bFunction_hgl_normal[h1][h2 == -1 ? size : h2][l] = (h1 + l == size - 1
								? data.penaltyFunction[seq.get(h1 - 1)].copy()
								: (bFunctions.get(m).get(h1 + l + 1).shiftX(
										-(data.s[seq.get(h1 - 1)][seq.get(h1 + l + 1)] + data.p[seq.get(h1 + l + 1)]))
										.add(data.penaltyFunction[seq.get(h1 - 1)])));
						if (h1 + l != size - 1) {
							addSetupCost(bFunction_hgl_normal[h1][h2 == -1 ? size : h2][l], seq.get(h1 - 1),
									seq.get(h1 + l + 1));
						}
						bFunction_hgl_reverse[h1][h2 == -1 ? size : h2][l] = bFunction_hgl_normal[h1][h2 == -1 ? size
								: h2][l];
						bFunction_hgl_reverse[h1][h2 == -1 ? size : h2][l].minimizeSuffixInPlace();

					} else {
						bFunction_hgl_reverse[h1][h2 == -1 ? size : h2][l] = bFunction_hgl_reverse[h1][h2 + 1][l]
								.shiftX(-(data.s[seq.get(h2 + 1)][seq.get(h2 + 2)] + data.p[seq.get(h2 + 2)]))
								.add(data.penaltyFunction[seq.get(h2 + 1)]);
						addSetupCost(bFunction_hgl_reverse[h1][h2 == -1 ? size : h2][l], seq.get(h2 + 1),
								seq.get(h2 + 2));
						bFunction_hgl_normal[h1][h2 == -1 ? size : h2][l] = bFunction_hgl_reverse[h1][h2 == -1 ? size
								: h2][l];
						bFunction_hgl_normal[h1][h2 == -1 ? size : h2][l].minimizeSuffixInPlace();
					}
				}

			}
		}

	}

	public void updateCumDuration(int m) {
		double[] cumDurationMN = cumDurationNormal[m];
		double[] cumDurationMR = cumDurationReverse[m];
		ArrayList<Integer> seq = this.sequences.get(m);
		int size = seq.size();
		if (size == 0)
			return;
		cumDurationMN[0] = data.s[0][seq.get(0)] + data.p[seq.get(0)];
		cumDurationMR[0] = data.s[0][seq.get(seq.size() - 1)] + data.p[seq.get(seq.size() - 1)];
		for (int i = 1; i < seq.size(); i++) {
			cumDurationMN[i] = cumDurationMN[i - 1] + data.s[seq.get(i - 1)][seq.get(i)] + data.p[seq.get(i)];
			cumDurationMR[i] = cumDurationMR[i - 1] + data.s[seq.get(size - i)][seq.get(size - i - 1)]
					+ data.p[seq.get(size - i - 1)];

		}
	}

	public void updateInformationM(int m, double costM) {
		int size = sequences.get(m).size();
		// 单机器内优化size不变，不需要处理
//		fFunctions_hg[m] = new PiecewiseLinearFunction[size][size];// 应该不需要包含起始点
//		bFunctions_hg[m] = new PiecewiseLinearFunction[size][size];
//		fFunctions_gh[m] = new PiecewiseLinearFunction[size][size];// 应该不需要包含起始点
//		bFunctions_gh[m] = new PiecewiseLinearFunction[size][size];
//		fFunctions_hgl_normal[m] = new PiecewiseLinearFunction[size][size+1][size];// 应该不需要包含起始点
//		bFunctions_hgl_normal[m] = new PiecewiseLinearFunction[size][size+1][size];
//		fFunctions_hgl_reverse[m] = new PiecewiseLinearFunction[size][size+1][size];// 应该不需要包含起始点
//		bFunctions_hgl_reverse[m] = new PiecewiseLinearFunction[size][size+1][size];
		// 最后一位[size]表示h2=-1的时候,即把某一段插入到最前边
		cumDurationNormal[m] = new double[size];
		cumDurationReverse[m] = new double[size];
		releaseFunctions1(m);
		releaseFunctions2(m);
		updateFFunctions1ForMachine(m);
		updatebFunctions1ForMachine(m);
		updateFunctions2ForMachine(m);
//		updateFunctions3ForMachine(m);
		updateCumDuration(m);
		updateTimeWindowSummariesForMachine(m);
		curCost = curCost - cost[m];
		curCost += costM;
		cost[m] = costM;

	}

	public void updateInformationM(int m) {
		// 这个暂时重新更新成本复杂度应该不会变大很多
		//TODO 这种里边其实可以也按照3维那样，下次搜哪个更新哪个，而不需要一次性更新，应该会更快一些
		//先不管了
		curCost -= cost[m];
		releaseFunctions1(m);
		releaseFunctions2(m);
		calCost(m);
		curCost += cost[m];

		// TODO bound验证
		double totalCost = 0;
		for (int m1 = 0; m1 < data.m; m1++) {
			totalCost += cost[m1];
		}
		totalCost += outsourcingCostTotal;
		if (Utility.compareGe(totalCost, Utility.curUpperBound)) {
			this.curCost = Utility.curUpperBound;// 此时和各cost[m]割裂
		} else {
			curCost = totalCost;
		}

		initialize_function(m);// 数组重新设置长度
		updateFunctions2ForMachine(m);
//		updateFunctions3ForMachine(m);//不需要每次都更新，做Iopt的时候在更新
		updateCumDuration(m);
		updateTimeWindowSummariesForMachine(m);

	}

	public void releaseFunctions1(int m) {
		if (!Configure.SegmentPool)
			return;
		if (fFunctions == null && bFunctions == null)
			return;

		if (fFunctions.get(m) == null)
			return;
		for (PiecewiseLinearFunction f : fFunctions.get(m)) {
			f.release();
		}

		if (bFunctions.get(m) == null)
			return;
		for (PiecewiseLinearFunction b : bFunctions.get(m)) {
			b.release();
		}

		fFunctions.get(m).clear();
		bFunctions.get(m).clear();

	}

	public void releaseFunctions2(int m) {
		if (!Configure.SegmentPool)
			return;

		if (fFunctions_hg[m] == null)
			return;
		for (int i = 0; i < fFunctions_hg[m].length; i++) {
			for (PiecewiseLinearFunction f : fFunctions_hg[m][i]) {
				if (f == null)
					continue;
				f.release();

			}
		}
		if (fFunctions_gh[m] == null)
			return;
		for (int i = 0; i < fFunctions_gh[m].length; i++) {
			for (PiecewiseLinearFunction f : fFunctions_gh[m][i]) {
				if (f == null)
					continue;
				f.release();
			}
		}

		if (bFunctions_hg[m] == null)
			return;
		for (int i = 0; i < bFunctions_hg[m].length; i++) {
			for (PiecewiseLinearFunction b : bFunctions_hg[m][i]) {
				if (b == null)
					continue;
				b.release();
			}
		}
		if (bFunctions_gh[m] == null)
			return;
		for (int i = 0; i < bFunctions_gh[m].length; i++) {
			for (PiecewiseLinearFunction b : bFunctions_gh[m][i]) {
				if (b == null)
					continue;
				b.release();
			}

		}

		ArrayList<PiecewiseLinearFunction[][][]> functions = new ArrayList<PiecewiseLinearFunction[][][]>();
		functions.add(fFunctions_hg);
		functions.add(fFunctions_gh);
		functions.add(bFunctions_hg);
		functions.add(bFunctions_gh);

		for (PiecewiseLinearFunction[][][] function : functions) {

			for (int i = 0; i < function[m].length; i++) {
				for (int j = 0; j < function[m][i].length; j++) {
					function[m][i][j] = null;
				}
			}
		}

	}

	public void releaseFunctions3(int m) {
		if (!Configure.SegmentPool)
			return;
		ArrayList<PiecewiseLinearFunction[][][][]> functions = new ArrayList<>();
		functions.add(fFunctions_hgl_normal);
		functions.add(fFunctions_hgl_reverse);
		functions.add(bFunctions_hgl_reverse);
		functions.add(bFunctions_hgl_normal);
		for (PiecewiseLinearFunction[][][][] function : functions) {
			for (int i = 0; i < function[m].length; i++) {
				for (int j = 0; j < function[m][i].length; j++) {
					for (PiecewiseLinearFunction f : function[m][i][j]) {
						if (f == null)
							continue;
						f.release();
					}
					for (int k = 0; k < function[m][i][j].length; k++) {
						function[m][i][j][k] = null;
					}
				}

			}

		}
	}

	public double merge3Segments(PiecewiseLinearFunction f1, PiecewiseLinearFunction f2, PiecewiseLinearFunction b2,
			PiecewiseLinearFunction b3, double shift1, double shift2, double duration2) {
		return merge3Segments(f1, f2, b2, b3, shift1, shift2, duration2, 0.0, 0.0);
	}

	public double merge3Segments(PiecewiseLinearFunction f1, PiecewiseLinearFunction f2, PiecewiseLinearFunction b2,
			PiecewiseLinearFunction b3, double shift1, double shift2, double duration2, double bridgeCost1,
			double bridgeCost2) {
		// 2026-05-15: f1/f2/b2/b3 只包含各自段内部的 setup cost。
		// 三段拼接新产生的两条桥接弧不属于任何原段，因此由 bridgeCost1/bridgeCost2 显式补入。
		if (f1.isEmpty() || f2.isEmpty() || b2.isEmpty() || b3.isEmpty())
			return Utility.big_M;
		Utility.debugMap.put("M3S Total:",Utility.debugMap.getOrDefault("M3S Total:",0)+1);
		double bridgeCost = bridgeCost1 + bridgeCost2;
		
		double f1_LB = f1.tail.getValue(f1.tail.end);
		double f2_LB = f2.tail.getValue(f2.tail.end);
		double b3_LB = b3.head.getValue(b3.head.start);
		if (Utility.compareGe(f1_LB + f2_LB + b3_LB + bridgeCost, Utility.curUpperBound)) {
			Utility.debugMap.put("M3S Skip:",Utility.debugMap.getOrDefault("M3S Skip:",0)+1);
			return f1_LB + f2_LB + b3_LB + bridgeCost;
		}

		double bestCost = 0;
		// 计算s_h2*
		PiecewiseLinearFunction merge12 = f1.shiftX(shift1).add(b2);
		// 此处b2可行域应该不需要特殊处理，f1移动以后的可行域就是b2的
		if (merge12.isEmpty())
			return Utility.curUpperBound;
		double[] pairs12 = merge12.findMinimal(true, true);
		double cost12 = pairs12[0];
		if (Utility.compareGe(cost12 + b3_LB + bridgeCost, Utility.curUpperBound)) {
			//这>=先不管，影响不大应该？
//			Utility.debugNumPlus();
//			System.out.println("M错误？");// 假设不可能
			Utility.debugMap.put("M3S Skip:",Utility.debugMap.getOrDefault("M3S Skip:",0)+1);
			
			return Utility.curUpperBound;// 应该就可以直接返回了，不需要往后做了
		}
		double s_h2 = pairs12[1];

		// 计算s_h3*
		PiecewiseLinearFunction merge23 = f2.add(b3.shiftX(-shift2));
		if (merge23.isEmpty())
			return Utility.curUpperBound;
		double[] pairs23 = merge23.findMinimal(true, false);
		double cost23 = pairs23[0];
		if (Utility.compareGe(cost23 + bridgeCost, Utility.curUpperBound)) {
//			Utility.debugNumPlus();
//			System.out.println("M错误？");// 假设不可能
			Utility.debugMap.put("M3S Skip:",Utility.debugMap.getOrDefault("M3S Skip:",0)+1);
			return Utility.curUpperBound;// 应该就可以直接返回了，不需要往后做了

		}
		double s_h3 = pairs23[1];
		if (Utility.compareGe(s_h3 - s_h2, duration2)) {
//			System.out.println("情况1");
			bestCost = f1.evaluate(s_h2 - shift1) + b2.evaluate(s_h2) + f2.evaluate(s_h3) + b3.evaluate(s_h3 + shift2)
					- f2.tail.getValue(f2.tail.end);
		} else {
//			System.out.println("情况2");
			double SplitBestCost = f2.findMinimal(true, true)[0];
			f2.resetDomain(0, data.CmaxH);
			PiecewiseLinearFunction newF = f1.shiftX(shift1).add(b2).add(f2.shiftX(-duration2))
					.add(b3.shiftX(-shift2 - duration2));

			double cost = newF.findMinimal(true, true)[0];
			newF.release();
			bestCost = cost - SplitBestCost;
		}
		merge12.release();
		merge23.release();
//		System.out.println("M3S:"+f1_LB+" "+f2_LB+" "+b3_LB+" "+bestCost);
		return bestCost + bridgeCost;

	}

	public double merge3SegmentsTest(PiecewiseLinearFunction f1, PiecewiseLinearFunction f2, PiecewiseLinearFunction b2,
			PiecewiseLinearFunction b3, double shift1, double shift2, double duration2, List<Integer> seq1,
			List<Integer> seq2, List<Integer> seq3) {
		// 2025.5.5
		// 经过测试，f2,b2函数根本不需要考虑定义域的存在,即使考虑的话也只是为了在传递的时候缩减可行域
		// 为什么不需要考虑可行域还是没太搞懂，先做实现，注意，这个merge所依赖定理的成立，必须是函数要minimize的，即定义为时间t之后完成或t之前完成的最小成本
		// 而不是恰好在t完成，从而函数形式必须按照原文那样两个函数相加后再取小，不然的话结果会出错的

		double bestCost = 0;
		// 计算s_h2*
		PiecewiseLinearFunction merge12 = f1.shiftX(shift1).add(b2);
		// 此处b2可行域应该不需要特殊处理，f1移动以后的可行域就是b2的
		ArrayList<Integer> newSeq1 = new ArrayList<Integer>();
		newSeq1.addAll(seq1);
		newSeq1.addAll(seq2);
		double cost11 = Move.testSequence(data, newSeq1, this);
		double[] pairs12 = merge12.findMinimal(true, true);
		double cost12 = pairs12[0];
		if (Utility.compareGe(cost12, Utility.curUpperBound)) {
			System.out.println("M错误？");// 假设不可能
		}
		double s_h2 = pairs12[1];

		// 计算s_h3*
		PiecewiseLinearFunction merge23 = f2.add(b3.shiftX(-shift2));
		double[] pairs23 = merge23.findMinimal(true, false);
		ArrayList<Integer> newSeq2 = new ArrayList<Integer>();
		newSeq2.addAll(seq2);
		newSeq2.addAll(seq3);
		cost11 = Move.testSequence(data, newSeq2, this);
		double cost23 = pairs23[0];
		if (Utility.compareGe(cost23, Utility.curUpperBound)) {
			System.out.println("M错误？");// 假设不可能
		}
		double s_h3 = pairs23[1];
		if (Utility.compareGe(s_h3 - s_h2, duration2)) {
			System.out.print("情况1");
			bestCost = f1.evaluate(s_h2 - shift1) + b2.evaluate(s_h2) + f2.evaluate(s_h3) + b3.evaluate(s_h3 + shift2)
					- f2.tail.getValue(f2.tail.end);
		} else {
			System.out.print("情况2");
			PiecewiseLinearFunction ff1 = f1.shiftX(shift1);
			PiecewiseLinearFunction ff2 = ff1.add(b2);
			PiecewiseLinearFunction ff3 = f2.shiftX(-duration2);
			PiecewiseLinearFunction ff4 = ff2.add(ff3);
			PiecewiseLinearFunction ff5 = b3.shiftX(-shift2 - duration2);

			PiecewiseLinearFunction newF = ff4.add(ff5);

			ArrayList<Integer> newSeq3 = new ArrayList<Integer>();
			newSeq3.addAll(seq1);
			newSeq3.addAll(seq2);
			newSeq3.addAll(seq3);
			cost11 = Move.testSequence(data, newSeq3, this);

			double[] values = newF.findMinimal(true, true);
			double cost = values[0];
			double SplitBestCost = f2.findMinimal(true, true)[0];

			f2.resetDomain(0, data.CmaxH);

			bestCost = cost - SplitBestCost;
		}
		return bestCost;

	}

	//merge2S和merge3S的bound使用感觉没啥用
	//且要使用局部上界也得使用才行
	public double merge2Segments(PiecewiseLinearFunction f1, PiecewiseLinearFunction b2, double shift) {
		return merge2Segments(f1, b2, shift, 0.0);
	}

	public double merge2Segments(PiecewiseLinearFunction f1, PiecewiseLinearFunction b2, double shift,
			double bridgeCost) {
		// 2026-05-15: 两段内部成本已在各自函数里累计；这里额外补两段之间新桥接弧的 setup cost。
		
		if (f1.isEmpty() || b2.isEmpty())
			return Utility.big_M;
//		b2=b2.setDomain(f1.head.start+shift,b2.tail.end);//TODO 是否有用
//		if(b2.isEmpty()) {
//			Utility.debugMap.put("M2S Skip:",Utility.debugMap.getOrDefault("M2S Skip:",0)+1);
//			return Utility.big_M;
//		
//		}
		//TODO 通过对b2取更好的下界，直接砍掉的多了一点，但时间会慢点
		//整体的影响不大感觉
		Utility.debugMap.put("M2S Total:",Utility.debugMap.getOrDefault("M2S Total:",0)+1);
		
		double f1_LB = f1.tail.getValue(f1.tail.end);
		double b2_LB = b2.head.getValue(b2.head.start);
		if (Utility.compareGe(f1_LB + b2_LB + bridgeCost, Utility.curUpperBound)) {
//			System.out.println("下界跳出");
			Utility.debugMap.put("M2S Skip:",Utility.debugMap.getOrDefault("M2S Skip:",0)+1);
			return f1_LB + b2_LB + bridgeCost;
		}
		double bestCost = 0;
		PiecewiseLinearFunction newF = f1.add(b2.shiftX(-shift));
		if (newF.isEmpty())
			return Utility.curUpperBound;
		bestCost = newF.findMinimal(true, true)[0] + bridgeCost;
		newF.release();
//		System.out.println("M2S:"+f1_LB+" "+b2_LB+" "+bestCost);
		return bestCost;

	}

	public double calCost(int m) {
		// 计算单个机器上序列成本
		// 不存储0

		ArrayList<Integer> sequence = sequences.get(m);
		ArrayList<Double> cTime = new ArrayList<Double>();

//		for(int i=0;i<sequence.size();i++) {
//			if(i==0) {
//				cum_time.add((double)data.s[0][sequence.get(i)]+data.p[sequence.get(i)]);
//	
//			}else {
//				cum_time.add((double)data.s[sequence.get(i-1)][sequence.get(i)]+data.p[sequence.get(i)]);
//				
//			}
//					}
		if (sequence.size() == 0) {
			cost[m] = 0;
			return cost[m];
		}
		updateFFunctions1ForMachine(m);
		updatebFunctions1ForMachine(m);

		// 构造每个位置上任务的惩罚成本函数
		PiecewiseLinearFunction lastF = fFunctions.get(m).get(fFunctions.get(m).size() - 1);
		cost[m] = lastF.tail.end * lastF.tail.slope + lastF.tail.intercept;
//		PiecewiseLinearFunction firstFb = bFunctions.get(m).get(0)
//				.setDomain(data.s[0][sequence.get(0)] + data.p[sequence.get(0)], data.Cmax);
//		或和0拼接
//		PiecewiseLinearFunction merged = data.penaltyFunction[0]
//				.add(bFunctions.get(m).get(0).shift(-data.s[0][sequence.get(0)] - data.p[sequence.get(0)]));
		// 注意！反向的时候函数的含义为这一段排列到结束的最小成本，如果要算最优，还要和depot拼接,或者是把可行域处理一下，要从开头能到达
		// 而fFunction是不需要的

		// 2025.5.3
		// ！debug找到的问题，对ffunction的更新，第一个函数的处理要设置他的定义域，从而后续可以言语，这种处理没毛病，这种函数的变量t表示的0-这堆序列的完成时间为t或更迟时的函数
		// 从而定义域如何设置很清晰，第一个设置以后后续的传递过去自然而然
		// 但对于bFunction的，其实不太应该给第一个设置可行域，一种处理方法是这里记录的函数都是定义在全局上的，使用的时候再限制可行域
		// 不然会有如下问题
		// 1、如果对序列最后一个任务设置了可行域的开始时间非0，而是p[job]+min_s[job],此时向前传递的时候，前边的任务的最早开始时间可能比这个值更早，这样就会丢掉部分可行域
		// 这种显然是不合理的，对每个序列中的任务job，最早完成时间为p[job]+min_s[job]，代表这个子序列中的第一个任务的最早完工时间
		// 2、如果对最后一个任务设置了可行域domainStart!=0,此时往前传递的时候，domainStart是会保留的
		// 从而，当考虑某个序列job-...-0的bFunction，此时job的函数设置了domainStart，从而我们要计算这个序列直接接在0之后的时候，此时拼接采用0函数的整个定义域上的0的这个函数
		// 和bFunction[job](t-shift)拼接，shift为s[0][job]+p[job],此时b函数相当于整体左移，！！！但是移动后由于domainStart不是0，而是之前限制的某个时间，此时相当于trimDomain砍掉了某些可行域
		// 换句话说，此时函数g(t)=bFunction[job](t-shift)以后的函数的t代表的是接的前段f函数的t，此处直接接0表示depot的完成时间，显然可行域从0开始，也就是说bFunction函数原始的t表示的是后段序列的第一个任务的完工时间
		// 但当shift以后，t的含义变为了前段序列的最后一个任务的完工时间，定义都变了，此时shift后的函数的可行域取决于前段函数。
		// 因此此时domainStart的设置完全是把两个定义的东西，针对后续序列第一个任务的完工时间的限制，用到前段最后一个任务的完工时间限制上，显然会出问题

		// 一种做法是，先不设置b函数的可行域，当全部扩展完以后再挨个设置，不过这种设置感觉意义不大，因为每个序列的domainStart为p[job]+min_s[job]，job为序列的第一个任务
		// 但这种意义其实不大，感觉完全可以不设置，因为现在的实现b函数使用的时候都会前移一个值，而这个值肯定比p[job]+min_s[job]更大，所以设置了其实没什么用
		// 如果换一种做法，即每次拼接让f函数去shift,b函数不变，此时b函数的定义域可能就重要了？分析一下似乎也没用，因为f函数后移的量就超过了p[job]+min_s[job]，变相的导致了domain的设置

		// 综上，对b函数的处理，不需要设置domain

//		System.out.println("使用反向函数方式1计算：" + firstFb.head.getValue(firstFb.head.start) + ", "
//				+ (firstFb.head.getValue(firstFb.head.start) == cost[m]));
//		double method2 = merged.findMinimal(true, true)[0];
//		System.out.println("使用反向函数方式2计算：" + method2 + " " + (method2 == cost[m]));

		// PiecewisePlotter.plotAndSave(fFunction);

		return cost[m];

	}

	public double calCost() {
		double totalCost = 0;
		for (int m = 0; m < data.m; m++) {
			totalCost += calCost(m);
		}
		totalCost += outsourcingCostTotal;

		return totalCost;
	}

	public double getNormalDuration(int m, int a, int b) {
		return this.cumDurationNormal[m][b] - this.cumDurationNormal[m][a];
	}

	public double getReverseDuration(int m, int a, int b) {
		// [a,b]之间元素倒置,b的完工 --- a的完工时间
		int size = this.cumDurationReverse[m].length;

		return this.cumDurationReverse[m][size - 1 - a] - this.cumDurationReverse[m][size - 1 - b];
	}

	void updateTimeWindowSummariesForMachine(int m) {
		ArrayList<Integer> seq = sequences.get(m);
		int size = seq.size();
		if (size == 0) {
			return;
		}
		for (int from = 0; from < size; from++) {
			for (int to = from; to < size; to++) {
				ArrayList<Integer> normal = new ArrayList<Integer>(seq.subList(from, to + 1));
				timeWindowSummary_hg[m][from][to] = TimeWindowSummary.of(data, normal);
				if (from == to) {
					timeWindowSummary_gh[m][from][to] = timeWindowSummary_hg[m][from][to];
				} else {
					ArrayList<Integer> reversed = new ArrayList<Integer>(normal);
					Collections.reverse(reversed);
					timeWindowSummary_gh[m][from][to] = TimeWindowSummary.of(data, reversed);
				}
			}
		}
	}

	TimeWindowSummary getTimeWindowSummary(int m, int from, int to, boolean reverse) {
		if (from > to) {
			return TimeWindowSummary.EMPTY;
		}
		return reverse ? timeWindowSummary_gh[m][from][to] : timeWindowSummary_hg[m][from][to];
	}

	boolean maySequenceSatisfyHardWindows(TimeWindowSummary... parts) {
		return TimeWindowSummary.mayFormSequence(data, parts);
	}

	public void removeJobs(List<Integer> toRemove) {

		for (int m = 0; m < data.m; m++) {
			ArrayList<Integer> jobs = sequences.get(m);
			boolean removed = jobs.removeAll(toRemove);
//			 if(removed) updateInformationM(m);//TODO 应该不需要更新这么多信息，插入的时候应该是一个一个插入的?只需要更新1维度就好了
			if (removed) {
				// 同insert
				curCost -= cost[m];
				calCost(m);
				curCost += cost[m];
			}
		}
		for (Integer job : toRemove) {
			removeOutsourcedJob(job);
		}

	}

	public void insertJob(int m, int pos, int job) {
		// 插入在pos之后
		sequences.get(m).add(pos + 1, job);
//		updateInformationM(m);// 不需要全部更新，只有一维的信息要使用，剩下的进入VND在使用
		// 直接用下边的就好了，其实可以进一步优化，插入一个任务，f和b某些都不会变，不过感觉不会影响太大
		curCost -= cost[m];
		calCost(m);
		curCost += cost[m];

	}

	public void setInitSolution() {
		// 保证每个机器上的任务时间量不超过上界，所以不能太随机生成
		// 暂时这么做，每次随机选择一个任务插入到某个时间不会爆的机器上
		int numJob = data.n;
		ArrayList<Integer> jobs = new ArrayList<Integer>();
		for (int i = 1; i <= numJob; i++) {
			jobs.add(i);
		}
		this.sequences.clear();
		this.outsourcedJobs.clear();
		this.outsourcingBaselineTotal = 0;
		this.outsourcingCostTotal = 0;
		this.curCost = 0;
		Arrays.fill(this.cost, 0);
		Collections.shuffle(jobs, Utility.rng);
		for (int m = 0; m < data.m; m++) {
			this.sequences.add(new ArrayList<Integer>());
		}

		double[] cumTimesM = new double[data.m];
		int[] lastJobM = new int[data.m];
		for (Integer jid : jobs) {
			// 对当前任务，随机选择一个还能插入的机器插入
			ArrayList<Integer> candMachines = new ArrayList<Integer>();
			ArrayList<Double> candCompletions = new ArrayList<Double>();
			// 可选择的机器（时间未超上界，且满足预处理粗硬窗）
			for (int m = 0; m < data.m; m++) {
				double earliestCompletion = cumTimesM[m] + data.s[lastJobM[m]][jid] + data.p[jid];
				// 2026-05-16: 初始构造阶段不建分段函数，因此用粗硬窗做轻量 earliest-time 递推。
				// 若最早完工早于硬窗左端，可以在加工该任务前等待；最终记录调整后的完工时间。
				double adjustedCompletion = Math.max(earliestCompletion, data.hardWindowStart[jid]);
				if (Utility.compareLe(adjustedCompletion, data.hardWindowEnd[jid])
						&& Utility.compareLe(adjustedCompletion, data.CmaxH)) {
					candMachines.add(m);
					candCompletions.add(adjustedCompletion);
				}
			}
			if (candMachines.isEmpty()) {
				if (canOutsource(jid)) {
					// 2026-05-16: 初始构造仍优先真实机器；只有没有可行机器时才用外包兜底。
					// 若外包成本为 big_M，说明该任务当前不允许外包，应显式暴露初始化不可行。
					addOutsourcedJob(jid);
					continue;
				}
				throw new IllegalStateException("No feasible machine and outsourcing is disabled for job " + jid);
			}
			int selectedIndex = Utility.rng.nextInt(candMachines.size());
			int selectedM = candMachines.get(selectedIndex);
			cumTimesM[selectedM] = candCompletions.get(selectedIndex);
			lastJobM[selectedM] = jid;
			this.sequences.get(selectedM).add(jid);
		}

	}



	public static void main(String[] args) throws IOException, IloException {

		final String ROOT_DIR = "D:/软件/eclipse/workspace/TWETScheduling/data/100-2/"; // 根目录
		TimerManager.globalStart();
		for (File f : new File(ROOT_DIR).listFiles()) {
			Utility.curUpperBound=Utility.big_M;//重新初始化!
			if (!f.getName().contains("wet100_023_2m.dat"))//wet040_039_2m.dat
				continue;
			Move.Recordreset();
			System.out.println(f.getName());
			Data data = new Data(f.getAbsolutePath(), true, true);
//			data.Cmax*=5;//Cmax的限制还是能快不少的 
//			data.m = 1;
			
			for (int q = 0; q < 1; q++) {
				Solution s = new Solution(data);
				s.setInitSolution();
				
//				ArcFlowModel model = new ArcFlowModel(data);
//				ATIParallel model=new ATIParallel(data);
//				TimeIndexModel model=new TimeIndexModel(data);
				// sij大小对这个模型影响也挺大的，不过估计是因为变大M就变大了，松弛效果变差
//				model.setSequence(s.sequences);
//			model.cplex.exportModel("model.lp");
				double s1 = System.currentTimeMillis();
//				model.solve();
				double e1 = System.currentTimeMillis();
				double s2 = System.currentTimeMillis();
//				double cost0 = s.calCost(0);
//				double cost1=s.calCost(1);
//				double cost=cost0+cost1;

				double e2 = System.currentTimeMillis();

//				System.out.println(cost + " " + (s2 - e2) / 1000 + "s " + model.getObjective() + " " + (s1 - e1) / 1000
//						+ "s " + Utility.compareEq(cost, model.getObjective()));
				s.initialize_function();
				for(int m=0;m<data.m;m++) {
					s.updateInformationM(m);
				}
				data.configure.updateBestSolution(s);
				System.out.println("初始解：" + s.curCost);
//				EngineVND engine = new EngineVND(data, s);
//				engine.search();
				EngineALNS engine =new EngineALNS(data, s);
				engine.search();
				s=data.configure.bestSolution;
				for(int m=0;m<data.m;m++) {
					System.out.println(s.fFunctions.get(m).get(s.sequences.get(m).size()-1).findMinimal(true, true)[1]);
				}
				System.out.println("搜索后最优解：" + s.curCost+" "+s.sequences);
				System.out.println("验证:"+s.calCost());
			}
//			break;

		}
		TimerManager.report();
		System.out.println(Utility.debugNum);
		System.out.println(Utility.debugMap);
	}
//	

}
