package Basic;

import Common.Utility;
import ilog.concert.*;
import ilog.cplex.IloCplex;

import java.io.File;
import java.util.*;

public class ATIParallel {

    private static final class Node {
        int i, t;
        public Node(int i, int t) {
            this.i = i; this.t = t;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node node = (Node) o;
            return i == node.i && t == node.t;
        }
        @Override
        public int hashCode() {
            return Objects.hash(i, t);
        }
    }

    private static final class Arc {
        Node from, to;
        double cost;
        int index; // Index in x array
        Arc(Node n1, Node n2, double c, int idx) {
            this.from = n1; this.to = n2; this.cost = c; this.index = idx;
        }
        @Override
        public String toString() {
            return from.i + "_" + from.t + "_" + to.i + "_" + to.t;
        }
    }

    private Data d;
    private int H;
    private IloCplex cpx;
    private Map<String, Node> nodes; // Key: "i_t"
    private List<Arc> arcList; // Store all arcs for efficient iteration
    private IloNumVar[] x;
    private int[] earliestTimes; // Cache earliest completion times
    private int[][] delta; // Cache s[i][j] + p[j]
    private Map<Integer, List<Arc>> incomingArcsByJob; // Cache incoming arcs for jobs
    private Map<Node, List<Arc>> incomingArcsByNode; // Cache incoming arcs for flow
    private Map<Node, List<Arc>> outgoingArcsByNode; // Cache outgoing arcs for flow
    private List<Arc> a2Arcs; // Cache A^2 arcs for capacity constraint

    public ATIParallel(Data data) throws IloException {
        this.d = data;
        this.H = (int) data.CmaxH + 1;
        this.cpx = new IloCplex();
        this.nodes = new HashMap<>();
        this.arcList = new ArrayList<>();

        precomputeData();
        generateNodes();
        generateArcs();
        precomputeArcMappings();
        buildVariables();
        addConstraintsAndObjective();
    }

    private void precomputeData() {
        earliestTimes = new int[d.n + 1];
        delta = new int[d.n + 1][d.n + 1];
        for (int j = 1; j <= d.n; j++) {
            earliestTimes[j] = (int)(Math.max(d.r[j], 0) + d.p[j]);
        }
        for (int i = 0; i <= d.n; i++) {
            for (int j = 1; j <= d.n; j++) {
                if (i != j || i == 0) {
                    delta[i][j] = (int)(d.s[i][j] + d.p[j]);
                }
            }
        }
    }

    private void generateNodes() {
        for (int t = 0; t <= H; t++) {
            nodes.put("0_" + t, new Node(0, t));
        }
        for (int j = 1; j <= d.n; j++) {
            for (int t = earliestTimes[j]; t <= H; t++) {
                nodes.put(j + "_" + t, new Node(j, t));
            }
        }
    }

    private void generateArcs() {
        int arcIndex = 0;
        // A^1: (i,t) -> (j, t + s_ij + p_j), i != j
        for (int i = 1; i <= d.n; i++) {
            int tStart = earliestTimes[i];
            for (int t_i = tStart; t_i <= H; t_i++) {
                Node from = nodes.get(i + "_" + t_i);
                for (int j = 1; j <= d.n; j++) {
                    if (i == j) continue;
                    int t_j = t_i + delta[i][j];
                    if (t_j <= H) {
                        Node to = nodes.get(j + "_" + t_j);
                        Arc arc = new Arc(from, to, d.cost(j, t_j) + d.getSetupCost(i, j), arcIndex++);
                        arcList.add(arc);
                    }
                }
            }
        }
        // A^2: (0,t) -> (j, t + s_0j + p_j)
        for (int j = 1; j <= d.n; j++) {
            int maxStartTime = H - delta[0][j];
            for (int t = 0; t <= maxStartTime; t++) {
                int t_j = t + delta[0][j];
                Node from = nodes.get("0_" + t);
                Node to = nodes.get(j + "_" + t_j);
                Arc arc = new Arc(from, to, d.cost(j, t_j) + d.getSetupCost(0, j), arcIndex++);
                arcList.add(arc);
            }
        }
        // A^3: (j,t) -> (0,H)
        Node sink = nodes.get("0_" + H);
        for (int j = 1; j <= d.n; j++) {
            for (int t = earliestTimes[j]; t <= H; t++) {
                Node from = nodes.get(j + "_" + t);
                Arc arc = new Arc(from, sink, 0, arcIndex++);
                arcList.add(arc);
            }
        }
        // A^4: (j,t) -> (j,t+1), for t < H
        for (int j = 0; j <= d.n; j++) {
            int tStart = (j == 0) ? 0 : earliestTimes[j];
            for (int t = tStart; t < H; t++) {
                Node from = nodes.get(j + "_" + t);
                Node to = nodes.get(j + "_" + (t + 1));
                if (from != null && to != null) {
                    Arc arc = new Arc(from, to, 0, arcIndex++);
                    arcList.add(arc);
                }
            }
        }
    }

    private void precomputeArcMappings() {
        incomingArcsByJob = new HashMap<>();
        incomingArcsByNode = new HashMap<>();
        outgoingArcsByNode = new HashMap<>();
        a2Arcs = new ArrayList<>();
        for (int j = 1; j <= d.n; j++) {
            incomingArcsByJob.put(j, new ArrayList<>());
        }
        for (Node node : nodes.values()) {
            incomingArcsByNode.put(node, new ArrayList<>());
            outgoingArcsByNode.put(node, new ArrayList<>());
        }
        for (Arc arc : arcList) {
            if (arc.from.i != arc.to.i&&arc.to.i!=0) { // Exclude A^4 for job constraints
              
            	incomingArcsByJob.get(arc.to.i).add(arc);
            }
            incomingArcsByNode.get(arc.to).add(arc);
            outgoingArcsByNode.get(arc.from).add(arc);
            if (arc.from.i == 0 && arc.to.i != 0) { // A^2 arcs
                a2Arcs.add(arc);
            }
        }
    }

    private void buildVariables() throws IloException {
        x = new IloNumVar[arcList.size()];
        for (int idx = 0; idx < arcList.size(); idx++) {
            x[idx] = cpx.numVar(0, d.m,IloNumVarType.Float, "x_" + idx);
        }
    }

    private void addConstraintsAndObjective() throws IloException {
        // Objective: Minimize sum of costs
        IloLinearNumExpr obj = cpx.linearNumExpr();
        for (Arc arc : arcList) {
            obj.addTerm(arc.cost, x[arc.index]);
        }
        cpx.addMinimize(obj);

        // Constraint (2): Each job processed exactly once
        for (int j = 1; j <= d.n; j++) {
            IloLinearNumExpr expr = cpx.linearNumExpr();
            for (Arc arc : incomingArcsByJob.get(j)) {
                expr.addTerm(1, x[arc.index]);
            }
            cpx.addEq(expr, 1, "job_" + j);
        }

        // Constraint (3): Machine capacity
        IloLinearNumExpr capExpr = cpx.linearNumExpr();
        for (Arc arc : a2Arcs) {
            capExpr.addTerm(1, x[arc.index]);
        }
        cpx.addLe(capExpr, d.m, "capacity");

        // Constraint (4): Flow conservation
        for (Node v : nodes.values()) {
            if ((v.i == 0 && v.t == 0) || (v.i == 0 && v.t == H)) continue;
            IloLinearNumExpr flow = cpx.linearNumExpr();
            for (Arc arc : incomingArcsByNode.get(v)) {
                flow.addTerm(1, x[arc.index]);
            }
            for (Arc arc : outgoingArcsByNode.get(v)) {
                flow.addTerm(-1, x[arc.index]);
            }
            cpx.addEq(flow, 0, "flow_" + v.i + "_" + v.t);
        }
    }

    public boolean solve() throws IloException {
        return cpx.solve();
    }

    public double getObj() throws IloException {
        return cpx.getObjValue();
    }

    /** @return 当前模型对应的数据实例。 */
    public Data getData() {
        return d;
    }

    /** @return 当前模型内部使用的 CPLEX 对象。 */
    public IloCplex getCplex() {
        return cpx;
    }

    /**
     * 提取当前解对应的按机器划分的任务时间表。
     * <p>
     * 这里沿用 displayResults 里的重构思路，但改成返回结构化结果，供外部导出和验证使用。
     */
    public ArrayList<ArrayList<Utility.TaskInfo>> extractTaskSchedules() throws IloException {
        ArrayList<Arc> activeArcs = new ArrayList<Arc>();
        for (Arc arc : arcList) {
            if (Utility.compareGe(cpx.getValue(x[arc.index]), 0.5)) {
                activeArcs.add(arc);
            }
        }

        ArrayList<ArrayList<Utility.TaskInfo>> schedules = new ArrayList<ArrayList<Utility.TaskInfo>>();
        ArrayList<Arc> startArcs = new ArrayList<Arc>();
        for (Arc a : activeArcs) {
            if (a.from.i == 0 && a.to.i != 0) {
                startArcs.add(a);
            }
        }
        startArcs.sort(Comparator.comparingInt((Arc a) -> a.from.t).thenComparingInt(a -> a.to.i));

        for (Arc startArc : startArcs) {
            ArrayList<Utility.TaskInfo> machine = new ArrayList<Utility.TaskInfo>();
            Arc currentArc = startArc;
            while (currentArc != null && currentArc.to.i != 0) {
                int job = currentArc.to.i;
                double completion = currentArc.to.t;
                double start = completion - d.p[job];
                double taskCost = d.w_e[job] * Math.max(d.d_e[job] - completion, 0.0)
                        + d.w_t[job] * Math.max(completion - d.d_l[job], 0.0)
                        + d.getSetupCost(currentArc.from.i, job);
                machine.add(new Utility.TaskInfo(job, start, completion, taskCost));

                Node currentNode = currentArc.to;
                currentArc = null;
                for (Arc arc : activeArcs) {
                    if (arc.from.equals(currentNode) && arc.to.i != arc.from.i) {
                        currentArc = arc;
                        break;
                    }
                }
            }
            if (!machine.isEmpty()) {
                schedules.add(machine);
            }
        }
        return schedules;
    }

    public void end() {
        cpx.end();
    }

    public void displayResults() throws IloException {
        if (!cpx.getStatus().equals(IloCplex.Status.Optimal)) {
            System.out.println("No optimal solution found.");
            return;
        }

        List<Arc> activeArcs = new ArrayList<>();
        for (Arc arc : arcList) {
            if (Utility.compareGe(cpx.getValue(x[arc.index]), 0.5)) {
                activeArcs.add(arc);
            }
        }
        for (Arc arc : activeArcs) {
            if (arc.from.i != arc.to.i) {
                System.err.println(arc);
            }
        }

        List<List<Arc>> machineSchedules = new ArrayList<>();
        for (int m = 0; m < d.m; m++) {
            machineSchedules.add(new ArrayList<>());
        }

        List<Arc> startArcs = new ArrayList<>();
        for (Arc a : activeArcs) {
            if (a.from.i == 0 && a.to.i != 0) {
                startArcs.add(a);
            }
        }

        int machineIdx = 0;
        for (Arc startArc : startArcs) {
            if (machineIdx >= d.m) break;
            List<Arc> schedule = machineSchedules.get(machineIdx);
            schedule.add(startArc);
            Node currentNode = startArc.to;
            while (currentNode.i != 0 || currentNode.t != H) {
                Arc nextArc = null;
                for (Arc a : activeArcs) {
                    if (a.from.equals(currentNode) && !a.to.equals(currentNode)) {
                        nextArc = a;
                        break;
                    }
                }
                if (nextArc == null) break;
                schedule.add(nextArc);
                currentNode = nextArc.to;
            }
            machineIdx++;
        }

        int[] C = new int[d.n + 1];
        int[] E = new int[d.n + 1];
        int[] T = new int[d.n + 1];
        double selectedSetupCost = 0.0;

        System.out.println("\n=== Scheduling Results ===");
        for (int m = 0; m < machineSchedules.size(); m++) {
            List<Arc> schedule = machineSchedules.get(m);
            if (schedule.isEmpty()) continue;
            System.out.println("Machine " + (m + 1) + ":");
            for (Arc a : schedule) {
                if (a.from.i == 0 && a.to.i != 0) {
                    int job = a.to.i;
                    int completionTime = a.to.t;
                    int startTime = (int)(completionTime - (double)d.p[job]);
                    System.out.printf("  Job %d: Start = %d, Completion = %d, due_window= [%d,%d]\n",
                            job, startTime, completionTime, d.d_e[job], d.d_l[job]);
                    C[job] = completionTime;
                    E[job] = (int)(Math.max(0, d.d_e[job] - completionTime));
                    T[job] = (int)(Math.max(0, completionTime - d.d_l[job]));
                    selectedSetupCost += d.getSetupCost(a.from.i, job);
                } else if (a.from.i != 0 && a.to.i != 0 && a.from.i != a.to.i) {
                    int job = a.to.i;
                    int completionTime = a.to.t;
                    int startTime = (int)(completionTime - d.p[job]);
                    System.out.printf("  Job %d: Start = %d, Completion = %d, due_window= [%d,%d]\n",
                            job, startTime, completionTime, d.d_e[job], d.d_l[job]);
                    C[job] = completionTime;
                    E[job] = (int)(Math.max(0, d.d_e[job] - completionTime));
                    T[job] = (int)(Math.max(0, completionTime - d.d_l[job]));
                    selectedSetupCost += d.getSetupCost(a.from.i, job);
                }
            }
        }

        System.out.println("\n=== Job Earliness and Tardiness ===");
        System.out.printf("%-10s %-15s %-15s %-10s\n", "Job", "Completion Time", "Earliness (E_j)", "Tardiness (T_j)");
        for (int j = 1; j <= d.n; j++) {
            if (C[j] != 0) {
                System.out.printf("%-10d %-15d %-15d %-10d\n", j, C[j], E[j], T[j]);
            }
        }

        double totalCost = selectedSetupCost;
        for (int j = 1; j <= d.n; j++) {
            if (C[j] != 0) {
                totalCost += d.w_e[j] * E[j] + d.w_t[j] * T[j];
            }
        }
        System.out.println("\nTotal Objective Value: " + totalCost);
        System.out.println("CPLEX Objective Value: " + cpx.getObjValue());
    }

    public static void main(String[] args) throws Exception {
        final String ROOT_DIR = "D:/软件/eclipse/workspace/TWETScheduling/data/40-2/";
        for (File f : new File(ROOT_DIR).listFiles()) {
            Data data = new Data(f.getAbsolutePath(), true, true);
            ATIParallel model = new ATIParallel(data);
            model.cpx.exportModel("model.lp");
            if (model.solve()) {
                System.out.println("Objective: " + model.getObj());
                model.displayResults();
            } else {
                System.out.println("No solution found.");
            }
            model.end();
            break;
        }
    }
}
