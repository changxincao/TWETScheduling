package Basic;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;

import Common.Configure;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import HEU.SchedulerForReleaseNoWait;

public class Data {
	public Configure configure;
	public static int scale = 1;
	public int n; // number of jobs
	public int m;
	public double[] p, d_e, d_l, w_e, w_t, r; // arrays length n
	// r先不管，感觉可能某些地方会不兼容
	public double[][] s;// sequence dependent set up
	public double[][] setupCostAdvantage;// B_ij：删除 j 后可能获得的 setup-cost advantage，供粗窗和后续 pricing 复用
	public double[][] setupCost;// sequence dependent setup cost，和 s[i][j] 使用同一条弧 i->j
	public double min_s[];// 每个任务的最小setup
	public double[] outsourcingCost;// 每个任务的 baseline outsourcing cost；默认 big_M 表示暂不收缩预处理粗硬窗
	public PiecewiseLinearFunction outsourcingCostFunction;// 2026-05-16: 总外包成本函数 G(B(O))，输入为 baseline 总量
	public double[] maxSetupCostAdvantage;// 预处理粗硬窗使用的 max_i B_ij 上界
	public double[] hardWindowStart, hardWindowEnd;// 每个任务预处理后的粗 completion 硬窗
	public boolean[][] preprocessedArcForbidden;// 2026-05-20: 基于粗硬时间窗预处理出的全局不可行弧，供 BPC pricing/branch 跳过
	public PiecewiseLinearFunction[] penaltyFunction;
	public double CmaxH = 1e6;// 问题下的全局上界，用于启发式，设置不能过紧，否则可能某些差解搜不到就跳不出去
	public double CmaxE = 1e6;// 问题下的全局上界，用于精确，越紧越好
	
	public long[] mask;
	public long full_mask;
	public int m_BigNumber = 100000000;
	public long[][] fl_reach;
	public long[][] fh_reach;
	public long[][] bl_reach;
	public long[][] bh_reach;

	public double[] earlyTime;
	public double[] lateTime;// 标记每个任务的完成时间时间窗,后续label setting的时候可能动态迭代更新

	public Data(String path, boolean setup, boolean due_date) throws IOException {
		configure = new Configure();
		BufferedReader reader = new BufferedReader(new FileReader(path));
		String line = reader.readLine();
		String[] headerTokens = splitTokens(line);
		this.n = Integer.parseInt(headerTokens[0]);
		this.m = Integer.parseInt(headerTokens[1]);
		this.p = new double[n + 1];
		this.d_e = new double[n + 1];
		this.d_l = new double[n + 1];
		this.w_e = new double[n + 1];
		this.w_t = new double[n + 1];
		this.s = new double[n + 1][n + 1];// 如果存在释放时间，setup可以发生在释放时间之前
		this.setupCost = new double[n + 1][n + 1];// 旧数据没有 SETUP_COST 块时默认全 0，保持兼容
		this.setupCostAdvantage = new double[n + 1][n + 1];
		this.min_s = new double[n + 1];
		this.r = new double[n + 1];
		this.outsourcingCost = new double[n + 1];
		this.maxSetupCostAdvantage = new double[n + 1];
		this.hardWindowStart = new double[n + 1];
		this.hardWindowEnd = new double[n + 1];
		Arrays.fill(this.outsourcingCost, Utility.big_M);
		this.outsourcingCost[0] = 0;
		debug_set();
		if (due_date) {
			load_dd_data(setup, reader);
		} else {
			load_dw_data(setup, reader);
		}

		setCmax();
		setImprovedCmax();
		ensureOutsourcingCostFunction();

//        Cmax=7700;
		setPreprocessedHardWindows();
		tightenCmaxByPreprocessedHardWindows();
		preprocessInfeasibleArcsByHardWindows();
		setPenaltyFunctions();
		for (int i = 1; i < n + 1; i++) {
			double minSi = Utility.big_M;
			for (int j = 0; j < n + 1; j++) {
				minSi = Math.min(minSi, s[j][i]);
			}
			min_s[i] = minSi;
		}

	}

	public void setTimeWindows() {
		earlyTime = new double[this.n + 1];
		lateTime = new double[this.n + 1];
//    	earlyTime都为0 不做改变
		Arrays.fill(lateTime, this.CmaxH);
	}

	// 这个原始VRP只需要做一次，但在我们问题上，每个子问题下任务的时间窗可能都会变，每次做一次

	private void TightTW0() {
		// Label Setting的时候应该就不用Cmax了，而是使用0任务的最晚时间窗
		double tmax = CmaxH;
		for (int ix = 1; ix <= this.n; ++ix) {
			tmax = Math.max(tmax, lateTime[ix]);
		}
		if (Utility.compareLe(tmax, lateTime[0])) {
			lateTime[0] = tmax;
			System.out.println("TightTW0> Reduce the period from " + lateTime[0] + " to: " + tmax); // debug
		}
	}

	private void TightTW1() {
		int iter = 0;
		for (int jid = 1; jid <= this.n; ++jid) {
			double dbest = earlyTime[0] + getSetUp(0, jid) + getProcessT(jid);
			if (Utility.compareGt(dbest, earlyTime[jid])) {
//				System.out.println("TightTW1> Add the early time for " + cid +  ", from " + m_customer.get(cid).m_early_time + " to: " + dbest); //debug
				earlyTime[jid] = dbest;
				++iter;
			}
		}
		// 不存在最后一个任务--->仓库，lateTime无法更新缩减
		// 那可能某些极端场景下，最早开始比最晚还要大，此时该任务无法服务，可缩减本次labeling需处理任务数量

		System.out.println("Reduced time windows: " + iter); // debug
	}

	public void BuildReach() {
		// 同样应该也是每次labeling重算
		// 这个就是最简单可能也很弱的一个计算了，因为允许违背的话，那可能更重要的还是基于已有的一些bound缩减？

		int max_time = (int) lateTime[0] + 1;
		fl_reach = new long[this.n + 1][max_time + 1];
		fh_reach = new long[this.n + 1][max_time + 1];
		bl_reach = new long[this.n + 1][max_time + 1];
		bh_reach = new long[this.n + 1][max_time + 1];

		fl_reach[0][0] = fh_reach[0][0] = full_mask;
		bl_reach[0][0] = bh_reach[0][0] = full_mask;
		// 暂时不考虑最后一个虚拟任务

		// forward reach information
		for (int jid = 1; jid <= this.n; ++jid) {
			for (int t = (int) earlyTime[jid]; t <= lateTime[jid]; ++t) {
				for (int i = 1; i <= this.n; ++i) {

					if (i == jid) {
						continue;
					}
					double it = t + getSetUp(jid, i) + getProcessT(i);
					if (Utility.compareGt(it, lateTime[i])) {
						continue;
					}

					if (i < 64) {
						fl_reach[jid][t] |= mask[i];
					} else {
						fh_reach[jid][t] |= mask[i];
					}
				}
			}
		}
		// 最多处理128个顾客
		// 不设置最后一个虚拟点，总能到达

		// backward reach information
		for (int jid = 1; jid <= this.n; ++jid) {
			int tstart = (int) (max_time - (lateTime[jid]));// 相当于取下界了，经过了1.5，但按照1去算
			// 这种估计实际执行的时候可能还是需要判断,但对整数问题不需要，小数需要
			double tend = max_time - (earlyTime[jid]);
			// 相当于是反向来看，到当前任务执行结束，已经经过的时间
			for (int t = tstart; t <= tend; ++t) {
				for (int i = 0; i <= this.n; ++i) {
					if (i == jid) {
						continue;
					}
					double it = earlyTime[i] + getProcessT(jid) + getSetUp(i, jid) + t;
					if (Utility.compareGt(it, max_time)) {
						continue;
					}

					if (i < 64) {
						bl_reach[jid][t] |= mask[i];
					} else {
						bh_reach[jid][t] |= mask[i];
					}
				}
			}
		}
	}

	public void setCmax() {
		/* ---- compute horizon (s_ij = 0) ---- */
		double sumP = 0, maxP = 0, maxD = 0, maxR = 0;
		for (int j = 1; j <= n; j++) {
			sumP += p[j];
			double max_sj = 0;
			for (int i = 0; i < n + 1; i++) {
				max_sj = Math.max(max_sj, s[i][j]);
			}
			sumP += max_sj;
			maxP = Math.max(maxP, p[j] + max_sj);
			maxR = Math.max(maxR, r[j]);
			maxD = Math.max(maxD, d_e[j] - (p[j] + max_sj));// 即每个任务在d_ej-pj-maxi(s_ij)这个时间以后执行，那么在任务前不可能存在等待时间，等待只会导致延迟？
			// 如果可能是小数的话，那就不能向下取整了？
		}
		CmaxH = (sumP / (double) m + (1.0 - 1.0 / (double) m) * maxP) + maxD + 1;
		CmaxE=CmaxH;
		// 不做取整，如果都是整数，向下取值，参考下文
//        Kramer, A., Dell’Amico, M., Feillet, D., & Iori, M. (2020). Scheduling jobs with release dates on identical parallel machines by minimizing the total weighted completion time. Computers and Operations Research, 123, 105018. https://doi.org/10.1016/j.cor.2020.105018
	}
	// 每个任务在时间max{(d_i-si-pi),r_i}之后不会有等待时间，其中s_i为所有i设置时间的最大
	// 在此基础上取前m-1个任务的平均执行时间+最大任务的执行时间，，这是无等待情况下，因此基于所有任务的最大最早开始时间执行
	// 还可以看作是一个带有不同释放时间的东西，后续启发式求解
	

	public void setImprovedCmax() {
		//不采用简单的基于每个任务的d_l,计算可能无等待的最大累计时间
		//将该问题作为一个决策问题，启发式获得上界，同样考虑无等待，认为每个任务的释放时间为
		//即每个任务在时间d_l[j]-p[j]释放，且其setup可发生在release之前,此时任务开始执行必然在释放时间之后，且任务不可能等待取寻求更好的成本
		SchedulerForReleaseNoWait scheduler=new SchedulerForReleaseNoWait(this);
		double improvedCmax=scheduler.solve(CmaxH, 2, 10);
		if(Utility.compareLt(improvedCmax, CmaxH)) {
			System.out.println("Cmax被进一步改进："+CmaxH+" "+improvedCmax);
			CmaxE=improvedCmax;
			CmaxH=CmaxE*1.1;
//			Cmax*=5;
			//暂时感觉是正确的..虽然结果和不设置此处不太一样，有高有低
			//相对于前边那种计算方法还是小了不少的
			//虽然结果似乎有时候会变差，但变得更快，但这个变差感觉是启发式的问题
			//这个的大小会严重影响求解速度
			//确实会有一些影响, 暂时设置一个1.4倍吧 但这个Cmax对精确应该是可用的，对启发式需要给一定的冗余才能搜到更好的解 
			//也不一定，有时候小点又快又好,感觉主要还是略大一点，保证存在ALNS更新可行解的同时，加深ls次数可能稳定一些
		}
	}

	//TODO 2025.5.16 这俩玩意的一个问题在于，设置上界以后虽然速度会快很多，但不知道这个东西限制的太死会不会导致某些解直接无法被搜索到，从而导致陷入局部最优
	//可能说限制的太死，扰动范围可能就很小.
	//或者就是扰动的时候允许一个更大的Cmax，VND则保持原样。在ALNS处允许函数范围扩大一定倍数
	//不能这么做，这么做要修改像下边的这些东西，而且如果ALNS基于这个算了一个Cmax超出的解，那进入VND也是一个上界不可行的解相当于
	//两种办法：
		//1、要么就是将这种解超出Cmax的解当作不可行解，但仍然接受做一定惩罚
		//2、要么就是仍然只允许可行解，但将Cmax设置大一些,但这个是否永远不会报错就不一定，只能设置足够大 
		//还得尝试一下 先用第2种吧，第一种还得修改评估逻辑,稍微大一点应该都有可行的解，主要看一下对不同的Cmax结果变化大不大，是不是Cmax越大可行解越好越稳定
		//因为Cmax变大会显著影响时间
	
	//	public void enlargeCmax() {
//		this.Cmax*=1.3;
//		for(PiecewiseLinearFunction f:this.penaltyFunction) {
//			f.tail.end*=1.3;
//			f.domainEnd*=1.3;
//		}
		
//	}
	
//	public void resetCmax() {
//		this.Cmax/=1.3;
//		for(PiecewiseLinearFunction f:this.penaltyFunction) {
//			f.tail.end/=1.3;
//			f.domainEnd*=1.3;
//		}
//	}
	
	
	

	public void setPenaltyFunctions() {
		preprocessInfeasibleArcsByHardWindows();
		penaltyFunction = new PiecewiseLinearFunction[n + 1];
		penaltyFunction[0] = new PiecewiseLinearFunction(0, CmaxH);
		penaltyFunction[0].addSegment(0, CmaxH, 0, 0);
		for (int jid = 1; jid < n + 1; jid++) {
			penaltyFunction[jid] = new PiecewiseLinearFunction(0, CmaxH);
			if ((!Utility.compareEq(0, d_e[jid])) && (!Utility.compareEq(w_e[jid], 0))) {
				penaltyFunction[jid].addSegment(0, d_e[jid], -w_e[jid], w_e[jid] * d_e[jid]);
			}
			if (!Utility.compareEq(d_e[jid], d_l[jid])) {
				penaltyFunction[jid].addSegment(d_e[jid], d_l[jid], 0, 0);
			}
			penaltyFunction[jid].addSegment(d_l[jid], CmaxH, w_t[jid], -w_t[jid] * d_l[jid]);
			// 2026-05-15: 预处理粗硬窗直接作用到原始 job 成本函数，但窗外只写成 big_M，不物理删除定义域。
			// 这样启发式和后续 BPC pricing 仍能保持函数右端到 CmaxH/T 的结构；更窄的 H_ij 留给 pricing 扩展时动态处理。
			penaltyFunction[jid] = penaltyFunction[jid].setDomain(hardWindowStart[jid], hardWindowEnd[jid], true);

		}
		// TODO 待测试setDomian()
		// 每个点上最多三段，可能最少就一段,如果不存在窗口且de=0或者不存在早到惩罚成本，此时就只有迟到成本了
	}

	/**
	 * 2026-05-15: 基于外包 baseline cost 和 setup-cost advantage 预处理 job 级粗硬窗。
	 * 这里只做不含 dual、不含给定前驱 i 的保守版本：对每个 job j 取 max_i B_ij，
	 * 再用 b_j 给出不会误删的 completion 窗口。真正依赖 dual 和具体前驱的 H_ij，
	 * 后续仍应在 pricing 扩展 i -> j 时重新计算。
	 */
	public void setPreprocessedHardWindows() {
		if (hardWindowStart == null || hardWindowStart.length != n + 1) {
			hardWindowStart = new double[n + 1];
			hardWindowEnd = new double[n + 1];
		}
		if (maxSetupCostAdvantage == null || maxSetupCostAdvantage.length != n + 1) {
			maxSetupCostAdvantage = new double[n + 1];
		}
		precomputeSetupCostAdvantages();
		hardWindowStart[0] = 0;
		hardWindowEnd[0] = CmaxH;
		for (int j = 1; j <= n; j++) {
			double baselineOutsourcingCost = outsourcingCost == null ? Utility.big_M : outsourcingCost[j];
			// 2026-05-16: b_j 只是外包 baseline，真正外包成本是 G(B)。
			// 粗硬窗只需要一个安全上界；单任务外包成本用 G(b_j)-G(0)=G(b_j)。
			double gamma = maxSetupCostAdvantage[j] + Math.max(0, evaluateOutsourcingCost(baselineOutsourcingCost));
			double left = Utility.compareGt(w_e[j], 0) ? d_e[j] - gamma / w_e[j] : 0;
			double right = Utility.compareGt(w_t[j], 0) ? d_l[j] + gamma / w_t[j] : CmaxH;
			left = Math.max(0, left);
			right = Math.min(CmaxH, right);
			hardWindowStart[j] = left;
			hardWindowEnd[j] = right;
		}
	}

	/**
	 * 2026-05-16: 预处理论文中的 B_ij。
	 * B_ij = max_k [kappa_ik - kappa_ij - kappa_jk]^+，
	 * 表示从 i 后面删除 j、改接到 k 时最多能节省多少 setup cost。
	 * 这里先按当前任务点 1..n 扫 k；论文里的终点 n+1 若 setup cost 为 0，只会贡献非正项，不影响 max(.,0)。
	 * 后续 pricing 扩展 i -> j 时可以直接使用 setupCostAdvantage[i][j]，不用每次扩展再扫 k。
	 */
	/**
	 * 2026-05-17: 用预处理粗硬窗反向收紧全局时间 horizon。
	 * <p>
	 * setPreprocessedHardWindows() 之后，每个 job 的 completion 已经被限制在
	 * [hardWindowStart[j], hardWindowEnd[j]] 内；窗口外会在 penaltyFunction 中写成 big_M，
	 * 不再是有意义的内部加工完成时间。因此若所有 job 的 hardWindowEnd 最大值已经小于当前 CmaxH，
	 * 可以先把 CmaxH 收到这个最大右端，再构造分段函数，减少启发式和 BPC pricing 维护的无效尾段。
	 * <p>
	 * 这里不使用 pricing dual。dual 相关的 H_ij 仍只在 pricing 扩展 i->j 时动态计算，避免同一轮
	 * RMP 下不同列使用不同全局 horizon。
	 */
	private void tightenCmaxByPreprocessedHardWindows() {
		double tightened = 0.0;
		for (int j = 1; j <= n; j++) {
			if (!Double.isFinite(hardWindowEnd[j])) {
				return;
			}
			tightened = Math.max(tightened, hardWindowEnd[j]);
		}
		if (Utility.compareGt(tightened, 0.0) && Utility.compareLt(tightened, CmaxH)) {
			CmaxH = tightened;
			CmaxE = Math.min(CmaxE, CmaxH);
			hardWindowEnd[0] = CmaxH;
		}
	}

	/**
	 * 2026-05-20: 参考旧 VRP 的 feasible_arc 预处理，利用当前 job 粗硬时间窗提前删除不可能出现的相邻弧。
	 * <p>
	 * 这里只做静态安全判断：若 job i 最早允许完成时间 hardWindowStart[i] 后接 j，
	 * 即使取 i 的最早完成，也已经使 j 的最早完成时间超过 hardWindowEnd[j]，则弧 i->j
	 * 在任何可行列中都不可能出现。source->j 同理使用 s_0j+p_j；sink 不代表真实加工点，
	 * 只禁止 sink 出边、自环、回到 source 等结构性非法弧。
	 * <p>
	 * 注意这里不把这些弧写入 Node.arcState。它们不是分支约束，只是 pricing/branch 生成候选时的
	 * 全局过滤条件；否则 RMP 会为每条预处理不可行弧额外建 forbidden-arc 行，反而膨胀模型。
	 */
	public void preprocessInfeasibleArcsByHardWindows() {
		int sink = n + 1;
		preprocessedArcForbidden = new boolean[n + 2][n + 2];
		preprocessedArcForbidden[0][sink] = true;
		for (int i = 0; i <= sink; i++) {
			preprocessedArcForbidden[i][0] = true;
			preprocessedArcForbidden[i][i] = true;
		}
		for (int to = 0; to <= sink; to++) {
			preprocessedArcForbidden[sink][to] = true;
		}
		for (int j = 1; j <= n; j++) {
			if (Utility.compareGt(hardWindowStart[j], hardWindowEnd[j])) {
				for (int from = 0; from <= sink; from++) {
					preprocessedArcForbidden[from][j] = true;
				}
				continue;
			}
			double earliestFromDepot = s[0][j] + p[j];
			if (Utility.compareGt(earliestFromDepot, hardWindowEnd[j]) || Utility.compareGt(earliestFromDepot, CmaxH)) {
				preprocessedArcForbidden[0][j] = true;
			}
		}
		for (int i = 1; i <= n; i++) {
			if (Utility.compareGt(hardWindowStart[i], hardWindowEnd[i])) {
				for (int to = 1; to <= sink; to++) {
					preprocessedArcForbidden[i][to] = true;
				}
				continue;
			}
			for (int j = 1; j <= n; j++) {
				if (i == j) {
					continue;
				}
				double earliestCompletionJ = hardWindowStart[i] + s[i][j] + p[j];
				if (Utility.compareGt(earliestCompletionJ, hardWindowEnd[j])
						|| Utility.compareGt(earliestCompletionJ, CmaxH)) {
					preprocessedArcForbidden[i][j] = true;
				}
			}
		}
	}

	public boolean isPreprocessedArcForbidden(int from, int to) {
		if (from < 0 || to < 0 || from >= n + 2 || to >= n + 2) {
			return true;
		}
		return preprocessedArcForbidden != null && preprocessedArcForbidden[from][to];
	}

	public int countPreprocessedForbiddenArcs() {
		if (preprocessedArcForbidden == null) {
			return 0;
		}
		int count = 0;
		for (int i = 0; i < preprocessedArcForbidden.length; i++) {
			for (int j = 0; j < preprocessedArcForbidden[i].length; j++) {
				if (preprocessedArcForbidden[i][j]) {
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * 2026-05-16: 预处理 B_ij。
	 * B_ij = max_k [kappa_ik - kappa_ij - kappa_jk]^+，表示从 i 后面删除 j、
	 * 改接到 k 时最多能节省多少 setup cost。pricing 扩展 i->j 时直接使用该值。
	 */
	public void precomputeSetupCostAdvantages() {
		if (setupCostAdvantage == null || setupCostAdvantage.length != n + 1) {
			setupCostAdvantage = new double[n + 1][n + 1];
		}
		Arrays.fill(maxSetupCostAdvantage, 0);
		for (int i = 0; i <= n; i++) {
			Arrays.fill(setupCostAdvantage[i], 0);
			for (int j = 1; j <= n; j++) {
				if (i == j) {
					continue;
				}
				double best = 0;
				for (int k = 1; k <= n; k++) {
					if (k == i || k == j) {
						continue;
					}
					double advantage = setupCost[i][k] - setupCost[i][j] - setupCost[j][k];
					if (Utility.compareGt(advantage, best)) {
						best = advantage;
					}
				}
				setupCostAdvantage[i][j] = best;
				if (Utility.compareGt(best, maxSetupCostAdvantage[j])) {
					maxSetupCostAdvantage[j] = best;
				}
			}
		}
	}

	public void load_dw_data(boolean setup, BufferedReader reader) throws IOException {
		// dw->single due window
		// TODO
	}

	public void load_dd_data(boolean setup, BufferedReader reader) throws IOException {
		// dd->single due date

		for (int jid = 1; jid <= n; jid++) {
			String line = reader.readLine();
			String[] tokens = splitTokens(line);
			this.p[jid] = Integer.parseInt(tokens[0]) * scale;
			this.d_e[jid] = Integer.parseInt(tokens[1]) * scale;
			this.d_l[jid] = Integer.parseInt(tokens[1]) * scale;
			this.w_e[jid] = Integer.parseInt(tokens[2]);
			this.w_t[jid] = Integer.parseInt(tokens[3]);
		}
		if (setup) {
			loadSetupMatrices(reader);
		}
	}

	/**
	 * 2026-04-17: 多机生成数据会在任务区后追加一个 SETUP 块，按 (n+1)*(n+1) 直接读入。
	 * 如果实例里没有这部分，就继续保持默认的零 setup，兼容旧格式。
	 * 2026-05-15: 允许在 SETUP 后继续追加 SETUP_COST 块。
	 * setup time 影响时间递推，setup cost 只影响目标值；两者共享同一条弧 i->j。
	 */
	private void loadSetupMatrices(BufferedReader reader) throws IOException {
		// 2026-05-17: 当前 OUTSOURCING_COST/OUTSOURCING_TARIFF 也作为 SETUP 后的 optional block 读取。
		// 因此带外包的正式数据需要走 setup=true；若以后要在无 setup 数据里单独启用外包，应把 optional block 解析拆到更外层。
		String line = nextNonEmptyLine(reader);
		if (line == null) {
			return;
		}
		if ("SETUP".equalsIgnoreCase(line.trim())) {
			for (int i = 0; i <= n; i++) {
				fillSetupRow(i, splitTokens(reader.readLine()));
			}
			line = nextNonEmptyLine(reader);
		} else {
			String[] firstRow = splitTokens(line);
			if (firstRow.length != n + 1) {
				throw new IOException("Invalid setup block in instance file");
			}
			fillSetupRow(0, firstRow);
			for (int i = 1; i <= n; i++) {
				fillSetupRow(i, splitTokens(reader.readLine()));
			}
			line = nextNonEmptyLine(reader);
		}
		while (line != null) {
			String blockName = line.trim();
			if ("SETUP_COST".equalsIgnoreCase(blockName)) {
				for (int i = 0; i <= n; i++) {
					fillSetupCostRow(i, splitTokens(reader.readLine()));
				}
			} else if ("OUTSOURCING_COST".equalsIgnoreCase(blockName)) {
				fillOutsourcingCost(splitTokens(reader.readLine()));
			} else if ("OUTSOURCING_TARIFF".equalsIgnoreCase(blockName)) {
				fillOutsourcingTariff(reader);
			} else {
				throw new IOException("Unknown optional block after SETUP: " + line);
			}
			line = nextNonEmptyLine(reader);
		}
	}

	private void fillSetupRow(int row, String[] tokens) {
		for (int j = 0; j <= n; j++) {
			s[row][j] = Double.parseDouble(tokens[j]);
		}
	}

	private void fillSetupCostRow(int row, String[] tokens) {
		for (int j = 0; j <= n; j++) {
			setupCost[row][j] = Double.parseDouble(tokens[j]);
		}
	}

	private void fillOutsourcingCost(String[] tokens) throws IOException {
		if (tokens.length == n) {
			for (int j = 1; j <= n; j++) {
				outsourcingCost[j] = Double.parseDouble(tokens[j - 1]);
			}
		} else if (tokens.length == n + 1) {
			for (int j = 0; j <= n; j++) {
				outsourcingCost[j] = Double.parseDouble(tokens[j]);
			}
		} else {
			throw new IOException("Invalid OUTSOURCING_COST vector length: " + tokens.length);
		}
	}

	private void fillOutsourcingTariff(BufferedReader reader) throws IOException {
		int segmentCount = Integer.parseInt(nextNonEmptyLine(reader).trim());
		outsourcingCostFunction = new PiecewiseLinearFunction(0, Utility.big_M);
		// 2026-05-17: 这里读取的是总外包成本函数 G(B) 的分段。
		// 数据生成时要保证分段覆盖 B=0 和所有可能的 sum b_j；通常还应让 G(0)=0，
		// 否则“没有任务外包”也会因为选段截距产生固定外包费用。
		for (int i = 0; i < segmentCount; i++) {
			String[] tokens = splitTokens(reader.readLine());
			if (tokens.length != 4) {
				throw new IOException("Invalid OUTSOURCING_TARIFF row, expected: start end slope intercept");
			}
			double start = Double.parseDouble(tokens[0]);
			double end = Double.parseDouble(tokens[1]);
			double slope = Double.parseDouble(tokens[2]);
			double intercept = Double.parseDouble(tokens[3]);
			outsourcingCostFunction.addSegment(start, end, slope, intercept);
		}
	}

	private void ensureOutsourcingCostFunction() {
		if (outsourcingCostFunction != null) {
			return;
		}
		double maxBaseline = Math.max(1.0, getMaxOutsourcingBaseline());
		outsourcingCostFunction = new PiecewiseLinearFunction(0, maxBaseline);
		// 2026-05-16: 没有给定折扣函数时使用 G(q)=q，兼容旧数据和旧逻辑。
		outsourcingCostFunction.addSegment(0, maxBaseline, 1, 0);
	}

	public double getMaxOutsourcingBaseline() {
		double total = 0;
		if (outsourcingCost == null) {
			return total;
		}
		for (int j = 1; j <= n; j++) {
			if (!Utility.isBigMValue(outsourcingCost[j])) {
				total += Math.max(0, outsourcingCost[j]);
			}
		}
		return total;
	}

	public double evaluateOutsourcingCost(double baselineTotal) {
		if (Utility.isBigMValue(baselineTotal)) {
			return Utility.big_M;
		}
		ensureOutsourcingCostFunction();
		double q = Math.max(0, baselineTotal);
		if (Utility.compareGt(q, outsourcingCostFunction.tail.end)) {
			return Utility.big_M;
		}
		return outsourcingCostFunction.evaluate(q);
	}

	private String nextNonEmptyLine(BufferedReader reader) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (!line.trim().isEmpty()) {
				return line;
			}
		}
		return null;
	}

	private String[] splitTokens(String line) {
		return line.trim().split("\\s+");
	}

	public void debug_set() {
		n = 60;
		m = 3;
		scale = 3;
	}

	public double cost(int j, int C) {
		/* 1. 计算提前量 E_j = max{d_j - C, 0} */
		double earliness = Math.max(d_e[j] - C, 0);

		/* 2. 计算逾期量 T_j = max{C - d_j, 0} */
		double tardiness = Math.max(C - d_l[j], 0);

		/* 3. 加权求和返回 */
		return w_e[j] * earliness + w_t[j] * tardiness;
	}

	public double getSetUp(int i, int j) {
		return this.s[i][j];
	}

	public double getSetupCost(int i, int j) {
		return this.setupCost[i][j];
	}

	public double getSetupCostAdvantage(int i, int j) {
		return this.setupCostAdvantage[i][j];
	}

	public double getProcessT(int i) {
		return this.p[i];
	}

	private void buildMask() {
		// 只包含0-n,没考虑最后回到0
		mask = new long[this.n + 1];
		long x = 1;
		for (int i = 0; i <= n; ++i) {
			mask[i] = x;
			if (i == 63) {
				x = 1;
			} else {
				x <<= 1;
			}
		}

		full_mask = ((1L << 63) - 1);
		if (this.n > 62)// 0不算一个任务,但占一个位置
		{
			full_mask |= mask[63];
		}
	}

}
