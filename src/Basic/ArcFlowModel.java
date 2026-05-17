package Basic;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;
import ilog.concert.*;
import ilog.cplex.IloCplex;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Arc flow model: ‑ minimises Σ(αj Ej + βj Tj) ‑ binary x_ij decides whether
 * job j is executed immediately after i ‑ supports ≤ m parallel identical
 * machines, sequence‑dependent setups s_ij
 *
 * Indices 0 : dummy source 1 … n : real jobs n+1 : (optional) dummy sink – not
 * required for the basic model
 */

public class ArcFlowModel {

	/* ---------- model fields ---------- */
	public IloCplex cplex; // CPLEX engine
	public Data data; // instance data

	// decision variables
	public IloNumVar[][] x; // x[i][j] ∈ {0,1}
	public IloNumVar[] C; // start/completion time of job j
	public IloNumVar[] E; // earliness
	public IloNumVar[] T; // tardiness
	public IloNumVar[] y; // 2026-05-17: y[j]=1 表示任务 j 外包，不进入内部机器路径
	public IloNumVar[] outsourceSegmentActive; // 外包总成本 G(B) 的分段选择变量
	public IloNumVar[] outsourceSegmentBaseline; // 每个分段承接的 baseline 外包成本 q_l
	private ArrayList<TariffSegment> outsourcingTariffSegments;

	private static final class TariffSegment {
		double start, end, slope, intercept;

		TariffSegment(double start, double end, double slope, double intercept) {
			this.start = start;
			this.end = end;
			this.slope = slope;
			this.intercept = intercept;
		}
	}

	/* ---------- constructor ---------- */
	public ArcFlowModel(Data data) throws IloException {
		this.data = data;
		this.cplex = new IloCplex();
		buildVariables();
		buildConstraints();
		buildObjective();
	}

	/* ---------- variables ---------- */
	private void buildVariables() throws IloException {
		/*
		 * x_ij for i ∈ {0 … n}, j ∈ {1 … n} and i != j Notice: no sink node needed –
		 * each job *must* be followed by exactly one other job, the “last” job on a
		 * machine simply has no outgoing arc to a real job.
		 */
		x = new IloNumVar[data.n + 1][data.n + 2]; // row 0 is dummy start
		for (int i = 0; i < data.n + 1; i++) {
			for (int j = 1; j < data.n + 2; j++) { // j==0 forbidden
				if (i != j) {
					x[i][j] = cplex.boolVar("x_" + i + "_" + j);
					cplex.add(x[i][j]);
				}
			}
		}

		C = cplex.numVarArray(data.n + 1, 0, Double.MAX_VALUE, IloNumVarType.Float); // C[0] will be fixed to 0
		E = cplex.numVarArray(data.n + 1, 0, Double.MAX_VALUE, IloNumVarType.Float);
		T = cplex.numVarArray(data.n + 1, 0, Double.MAX_VALUE, IloNumVarType.Float);
		y = new IloNumVar[data.n + 1];
		for (int j = 1; j <= data.n; j++) {
			y[j] = cplex.boolVar("outsource_" + j);
			cplex.add(y[j]);
		}
		outsourcingTariffSegments = collectOutsourcingTariffSegments();
		outsourceSegmentActive = new IloNumVar[outsourcingTariffSegments.size()];
		outsourceSegmentBaseline = new IloNumVar[outsourcingTariffSegments.size()];
		for (int l = 0; l < outsourcingTariffSegments.size(); l++) {
			outsourceSegmentActive[l] = cplex.boolVar("outsourceTariff_" + l);
			outsourceSegmentBaseline[l] = cplex.numVar(0, Double.MAX_VALUE, "outsourceBaseline_" + l);
			cplex.add(outsourceSegmentActive[l]);
			cplex.add(outsourceSegmentBaseline[l]);
		}
		// 变量是整数的时候这几个设置为整数更快一点,区别不大
		for (int i = 0; i < data.n + 1; i++) {
			C[i].setName("C_" + i);
			E[i].setName("E_" + i);
			T[i].setName("T_" + i);
			cplex.add(C[i]);
			cplex.add(E[i]);
			cplex.add(T[i]);
		}
	}

	/* ---------- constraints ---------- */
	private void buildConstraints() throws IloException {

		/* (1) each job has exactly one predecessor (incoming‑flow) */
		for (int j = 1; j < data.n + 1; j++) {
			List<IloNumVar> incoming = new ArrayList<>();
			for (int i = 0; i < data.n + 1; i++) {
				if (i != j)
					incoming.add(x[i][j]);
			}
			cplex.addEq(cplex.sum(cplex.sum(incoming.toArray(IloNumVar[]::new)), y[j]), 1, "cover_" + j);
		}

		/* (2) each job has exactly one successor (outgoing‑flow) */
		for (int i = 1; i < data.n + 1; i++) {
			List<IloNumVar> outgoing = new ArrayList<>();
			for (int j = 1; j <= data.n + 1; j++) {
				if (i != j)
					outgoing.add(x[i][j]);
			}
			cplex.addEq(cplex.sum(cplex.sum(outgoing.toArray(IloNumVar[]::new)), y[i]), 1, "outFlow_" + i);
		}
		addOutsourcingTariffConstraints();

		/* (3) at most m jobs are launched from the dummy source */
		List<IloNumVar> firstArcs = new ArrayList<>();
		for (int j = 1; j < data.n + 1; j++)
			firstArcs.add(x[0][j]);
		cplex.addLe(cplex.sum(firstArcs.toArray(IloNumVar[]::new)), data.m, "machineLimit");

		/* (4) time origin */
		cplex.addEq(C[0], 0, "C0");

		/* (5) sequencing/time propagation with big-M */
		double M = data.CmaxH;
		for (int i = 0; i < data.n + 1; i++) {
			for (int j = 1; j < data.n + 1; j++) {
				if (i == j)
					continue;

				double p_j = (double) (data.p[j]); // processing time of j
				double s_ij = (double) (data.s[i][j]); // sequence‑dependent setup

				IloLinearNumExpr expr = cplex.linearNumExpr();
				expr.addTerm(1.0, C[i]);
				expr.addTerm(M, x[i][j]); // only active when x_ij = 0
				expr.addTerm(-1.0, C[j]);

				cplex.addLe(expr, -(p_j + s_ij) + M, "timeFlow_" + i + "_" + j);
			}
		}

		/* (6) earliness & (7) tardiness */
		for (int j = 1; j < data.n + 1; j++) {
			int d_e = (int) (data.d_e[j]);
			int d_l = (int) (data.d_l[j]);
			// Treat due date as preferred completion time; negative E/T permitted prevented
			// by lower bound 0
			cplex.addGe(E[j], cplex.diff(d_e, C[j]), "Earliness_" + j);
			cplex.addGe(T[j], cplex.diff(C[j], d_l), "Tardiness_" + j);
		}

		/* (8) domain was already handled via variable types */
	}

	/* ---------- objective ---------- */
	private void buildObjective() throws IloException {
		IloLinearNumExpr obj = cplex.linearNumExpr();
		for (int j = 1; j < data.n + 1; j++) {
			obj.addTerm(data.w_e[j], E[j]); // α_j * E_j
			obj.addTerm(data.w_t[j], T[j]); // β_j * T_j
		}
		// 2026-05-15: setup cost 是被选中弧 i->j 的固定目标成本，
		// 不影响时间传播，只通过 x_ij 加入目标函数。
		for (int i = 0; i < data.n + 1; i++) {
			for (int j = 1; j < data.n + 1; j++) {
				if (i != j && x[i][j] != null) {
					obj.addTerm(data.getSetupCost(i, j), x[i][j]);
				}
			}
		}
		for (int l = 0; l < outsourcingTariffSegments.size(); l++) {
			TariffSegment seg = outsourcingTariffSegments.get(l);
			obj.addTerm(seg.slope, outsourceSegmentBaseline[l]);
			obj.addTerm(seg.intercept, outsourceSegmentActive[l]);
		}
		cplex.addMinimize(obj, "TotalPenalty");
	}

	private ArrayList<TariffSegment> collectOutsourcingTariffSegments() {
		data.evaluateOutsourcingCost(0);
		ArrayList<TariffSegment> segments = new ArrayList<TariffSegment>();
		PiecewiseLinearFunction.Segment seg = data.outsourcingCostFunction.head;
		while (seg != null) {
			segments.add(new TariffSegment(seg.start, seg.end, seg.slope, seg.intercept));
			seg = seg.next;
		}
		return segments;
	}

	private void addOutsourcingTariffConstraints() throws IloException {
		// 2026-05-17: 模仿 SP2 的外包变量建模。y_j 表示任务是否外包，
		// q=sum b_j y_j，再通过一组选段变量表达总外包成本 G(q)。
		IloLinearNumExpr baselineFromJobs = cplex.linearNumExpr();
		for (int j = 1; j <= data.n; j++) {
			if (Utility.isBigMValue(data.outsourcingCost[j])) {
				cplex.addEq(y[j], 0, "outsourceDisabled_" + j);
			} else {
				baselineFromJobs.addTerm(data.outsourcingCost[j], y[j]);
			}
		}
		IloLinearNumExpr baselineFromSegments = cplex.linearNumExpr();
		IloLinearNumExpr active = cplex.linearNumExpr();
		for (int l = 0; l < outsourcingTariffSegments.size(); l++) {
			TariffSegment seg = outsourcingTariffSegments.get(l);
			baselineFromSegments.addTerm(1.0, outsourceSegmentBaseline[l]);
			active.addTerm(1.0, outsourceSegmentActive[l]);
			cplex.addGe(outsourceSegmentBaseline[l], cplex.prod(seg.start, outsourceSegmentActive[l]),
					"outsourceSegLB_" + l);
			cplex.addLe(outsourceSegmentBaseline[l], cplex.prod(seg.end, outsourceSegmentActive[l]),
					"outsourceSegUB_" + l);
		}
		cplex.addEq(baselineFromJobs, baselineFromSegments, "outsourceBaselineBalance");
		cplex.addEq(active, 1, "outsourceOneTariffSegment");
	}

	/* ---------- solve & accessors ---------- */
	public boolean solve() throws IloException {
		cplex.setParam(IloCplex.DoubleParam.TiLim, 3600);
//		cplex.setOut(null);
		return cplex.solve();
	}

	public double getObjective() throws IloException {
		return cplex.getObjValue();
	}

	/** @return 当前模型对应的数据实例。 */
	public Data getData() {
		return data;
	}

	/** @return 当前模型内部使用的 CPLEX 对象。 */
	public IloCplex getCplex() {
		return cplex;
	}

	/** @return 当前解中被外包的任务编号。 */
	public ArrayList<Integer> extractOutsourcedJobs() throws IloException {
		ArrayList<Integer> outsourced = new ArrayList<Integer>();
		for (int j = 1; j <= data.n; j++) {
			if (y[j] != null && Utility.compareGe(cplex.getValue(y[j]), 0.5)) {
				outsourced.add(j);
			}
		}
		return outsourced;
	}

	/**
	 * 提取当前解对应的按机器划分的任务时间表。
	 * <p>
	 * 这个接口主要给结果输出/验证模块使用，避免外部重复写一遍从 x/C 变量重构序列的逻辑。
	 */
	public ArrayList<ArrayList<Utility.TaskInfo>> extractTaskSchedules() throws IloException {
		ArrayList<ArrayList<Utility.TaskInfo>> schedules = new ArrayList<ArrayList<Utility.TaskInfo>>();
		boolean[] visited = new boolean[data.n + 1];
		for (int j = 1; j <= data.n; j++) {
			if (x[0][j] == null || !Utility.compareGe(cplex.getValue(x[0][j]), 0.5)) {
				continue;
			}
			ArrayList<Utility.TaskInfo> machine = new ArrayList<Utility.TaskInfo>();
			int curr = j;
			int prevJob = 0;
			while (curr != -1 && curr != data.n + 1 && !visited[curr]) {
				double completion = cplex.getValue(C[curr]);
				double start = completion - data.p[curr];
				double taskCost = data.w_e[curr] * Math.max(data.d_e[curr] - completion, 0.0)
						+ data.w_t[curr] * Math.max(completion - data.d_l[curr], 0.0)
						+ data.getSetupCost(prevJob, curr);
				machine.add(new Utility.TaskInfo(curr, start, completion, taskCost));
				visited[curr] = true;
				int next = -1;
				for (int k = 1; k <= data.n + 1; k++) {
					if (curr == k || x[curr][k] == null) {
						continue;
					}
					if (Utility.compareGe(cplex.getValue(x[curr][k]), 0.5)) {
						next = k;
						break;
					}
				}
				prevJob = curr;
				curr = next;
			}
			if (!machine.isEmpty()) {
				schedules.add(machine);
			}
		}
		return schedules;
	}

	public void end() {
		cplex.end();
	}

	public void setSequence(ArrayList<ArrayList<Integer>> sequences) throws IloException {
		// 暂时只用于1个机器的情况，即给出所有的任务顺序
		for (ArrayList<Integer> sequence : sequences) {
			for (int i = 0; i < sequence.size(); i++) {
				int jid = sequence.get(i);
				if (i == 0)
					cplex.addEq(x[0][jid], 1);
				else
					cplex.addEq(x[sequence.get(i - 1)][jid], 1);
			}
		}
	}

	public static void main(String[] args) throws IOException, IloException {
		final String ROOT_DIR = "D:/软件/eclipse/workspace/TWETScheduling/data/40-2/"; // 根目录
		for (File f : new File(ROOT_DIR).listFiles()) {
			Data data = new Data(f.getAbsolutePath(), true, true);
			ArcFlowModel model = new ArcFlowModel(data);
			// sij大小对这个模型影响也挺大的，不过估计是因为变大M就变大了，松弛效果变差
			model.solve();
			model.cplex.exportModel("model.lp");
			Utility u = new Utility(data);
//			u.printSchedule(model);

			break;
		}
	}
}
