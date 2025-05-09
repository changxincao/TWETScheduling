package Basic;

import Basic.Data;
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
			cplex.addEq(cplex.sum(incoming.toArray(IloNumVar[]::new)), 1, "inFlow_" + j);
		}

		/* (2) each job has exactly one successor (outgoing‑flow) */
		for (int i = 1; i < data.n + 1; i++) {
			List<IloNumVar> outgoing = new ArrayList<>();
			for (int j = 1; j <= data.n + 1; j++) {
				if (i != j)
					outgoing.add(x[i][j]);
			}
			cplex.addEq(cplex.sum(outgoing.toArray(IloNumVar[]::new)), 1, "outFlow_" + i);
		}

		/* (3) at most m jobs are launched from the dummy source */
		List<IloNumVar> firstArcs = new ArrayList<>();
		for (int j = 1; j < data.n + 1; j++)
			firstArcs.add(x[0][j]);
		cplex.addLe(cplex.sum(firstArcs.toArray(IloNumVar[]::new)), data.m, "machineLimit");

		/* (4) time origin */
		cplex.addEq(C[0], 0, "C0");

		/* (5) sequencing/time propagation with big-M */
		double M = data.Cmax;
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
		cplex.addMinimize(obj, "TotalPenalty");
	}

	/* ---------- solve & accessors ---------- */
	public boolean solve() throws IloException {
		cplex.setParam(IloCplex.DoubleParam.TiLim, 3600);
		cplex.setOut(null);
		return cplex.solve();
	}

	public double getObjective() throws IloException {
		return cplex.getObjValue();
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
