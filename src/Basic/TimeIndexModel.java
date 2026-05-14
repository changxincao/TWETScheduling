package Basic;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;

import Basic.Data;
import Common.Utility;
import ilog.concert.*;
import ilog.cplex.IloCplex;

/**
 * Time‑indexed formulation WITHOUT an explicit machine index.
 * Capacity is enforced by:  for every time τ,  Σ overlaps ≤ m
 */
public class TimeIndexModel {

    private  Data d;                    // input instance
    IloCplex cpx;

    private  int H;                     // horizon
    private  IloNumVar[][] x;           // x[j][t]  (1‑based job index)
    private  IloNumVar[] C, E, T;       // completion, earliness, tardiness

    /* -------- constructor -------- */
    public TimeIndexModel(Data data) throws IloException {
        this.d   = data;
        this.cpx = new IloCplex();
        this.H=(int)data.CmaxH;
        
        /* ---- create variables ---- */
        x = new IloNumVar[d.n + 1][H + 1];
        for (int j = 1; j <= d.n; j++) {
            for (int t = 0; t <= H - d.p[j]; t++)
                x[j][t] = cpx.boolVar("x_" + j + "_" + t);
        }

        C = cpx.numVarArray(d.n + 1, 0, Double.MAX_VALUE);
        E = cpx.numVarArray(d.n + 1, 0, Double.MAX_VALUE);
        T = cpx.numVarArray(d.n + 1, 0, Double.MAX_VALUE);

        buildModel();
    }

    /* -------- build constraints & objective -------- */
    private void buildModel() throws IloException {

        /* (1) each job starts exactly once */
        for (int j = 1; j <= d.n; j++) {
            IloLinearNumExpr assign = cpx.linearNumExpr();
            for (int t = 0; t <= H - d.p[j]; t++) assign.addTerm(1, x[j][t]);
            cpx.addEq(assign, 1, "assign_" + j);
        }

        /* (2) capacity: for each time τ, overlapping jobs ≤ m */
        for (int tau = 0; tau <= H; tau++) {
            IloLinearNumExpr load = cpx.linearNumExpr();
            for (int j = 1; j <= d.n; j++) {
                int pj = (int)d.p[j];
                int earliest = Math.max(0, tau - pj + 1);
                int latest   = Math.min(tau, H - pj);
                for (int t = earliest; t <= latest; t++)
                    load.addTerm(1, x[j][t]);
            }
            cpx.addLe(load, d.m, "cap_t" + tau);
        }

        IloLinearNumExpr obj = cpx.linearNumExpr();
        for (int j = 1; j <= d.n; j++) {
            int pj   = (int)d.p[j];
            int dueE = (int)d.d_e[j];
            int dueL = (int)d.d_l[j];
            double wE   = d.w_e[j];
            double wT   = d.w_t[j];

            for (int t = 0; t <= H - pj; t++) {
                int C = t + pj; // completion time
                int earliness = Math.max(dueE - C, 0);
                int tardiness = Math.max(C - dueL, 0);
                double cost = wE * earliness + wT * tardiness;
                if (cost != 0)                       // skip zero‑cost arcs
                    obj.addTerm(cost, x[j][t]);
            }
        }
        cpx.addMinimize(obj);
    }

    /* -------- solve helpers -------- */
    public boolean solve() throws IloException {
//        cpx.setParam(IloCplex.DoubleParam.TiLim, timeLimitSeconds);
        return cpx.solve();
    }
    public double getObj() throws IloException { return cpx.getObjValue(); }
    public int    getH()  { return H; }
    public Data getData() { return d; }
    public IloCplex getCplex() { return cpx; }
    public IloNumVar[][] getX() { return x; }

    /**
     * 从 time-indexed 解中提取任务时间表。
     * <p>
     * 该模型本身不显式区分机器编号，因此这里按开始时间做一次贪心分配，
     * 只用于结果输出和可行性核查，不改变原模型含义。
     */
    public ArrayList<ArrayList<Utility.TaskInfo>> extractTaskSchedules() throws IloException {
        ArrayList<Utility.TaskInfo> tasks = new ArrayList<Utility.TaskInfo>();
        for (int j = 1; j <= d.n; j++) {
            for (int t = 0; t <= H - d.p[j]; t++) {
                if (x[j][t] != null && cpx.getValue(x[j][t]) > 0.5 - Utility.EPS) {
                    double start = t;
                    double completion = t + d.p[j];
                    double taskCost = d.w_e[j] * Math.max(d.d_e[j] - completion, 0.0)
                            + d.w_t[j] * Math.max(completion - d.d_l[j], 0.0);
                    tasks.add(new Utility.TaskInfo(j, start, completion, taskCost));
                }
            }
        }
        tasks.sort(Comparator.comparingDouble((Utility.TaskInfo t) -> t.start).thenComparingInt(t -> t.job));

        ArrayList<ArrayList<Utility.TaskInfo>> schedules = new ArrayList<ArrayList<Utility.TaskInfo>>();
        for (Utility.TaskInfo task : tasks) {
            boolean assigned = false;
            for (ArrayList<Utility.TaskInfo> machine : schedules) {
                Utility.TaskInfo last = machine.get(machine.size() - 1);
                if (last.completion <= task.start + Utility.EPS) {
                    machine.add(task);
                    assigned = true;
                    break;
                }
            }
            if (!assigned) {
                ArrayList<Utility.TaskInfo> machine = new ArrayList<Utility.TaskInfo>();
                machine.add(task);
                schedules.add(machine);
            }
        }
        return schedules;
    }
    public void   end()   { cpx.end(); }

    /* quick demo */
    public static void main(String[] args) throws Exception {
		final String ROOT_DIR = "D:/软件/eclipse/workspace/TWETScheduling/data/40-2/"; // 根目录
		for (File f : new File(ROOT_DIR).listFiles()) {
			Data data = new Data(f.getAbsolutePath(), false, true);
			TimeIndexModel model = new TimeIndexModel(data);
			model.cpx.exportModel("model.lp");
			model.solve();
			
//			model.cplex.exportModel("model.lp");
//			Utility u=new Utility(data);
//			u.printSchedule(model);
			
			break;
		}
	}
}
