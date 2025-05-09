package Common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import Basic.ArcFlowModel;
import Basic.Data;
import Common.Utility.TimerManager;
import ilog.concert.IloException;

public class Utility {
	public static boolean timeManage=true;//记录组件时间和次数，分析效率
	public Data data;
	public static double EPS=1e-6;
	public static double big_M=1e9;
	public Utility(Data d)
	{
		data = d;

	}
	
	/** helper record to store a task and its completion time */
	public static class TaskInfo {
	    public  int job;
	    public double start;
	    public  double completion;
	    public double cost;
	    
	    public TaskInfo(int job, double completion) {
	        this.job = job; this.completion = completion;
	    }
		public TaskInfo(int job, double s, double c, double cost) {
			this.job=job; this.start=s; this.completion=c; this.cost=cost;
		}
	}
	
	/** 
	 * Reconstructs every machine’s sequence and prints:
	 *   Machine k:  job1(C= ... ) -> job2(C= ... ) -> ...
	 */
		public void printSchedule(ArcFlowModel model) throws IloException {
	
		    boolean[] visited = new boolean[data.n+1];          // mark scheduled jobs
		    int machineID = 1;
	
		    // 1. loop over arcs leaving dummy source (0,j)
		    for (int j = 1; j <= data.n; j++) {
		        if (model.x[0][j] != null && model.cplex.getValue(model.x[0][j]) > 0.5 - EPS) {
	
		            List<TaskInfo> seq = new ArrayList<>();
		            int curr = j;
	
		            while (curr != -1&&curr != data.n+1 && !visited[curr]) {
		                double Ccurr = model.cplex.getValue(model.C[curr]);
		                seq.add(new TaskInfo(curr, Ccurr));
		                visited[curr] = true;
	
		                // find successor of curr
		                int next = -1;
		                for (int k = 1; k <= data.n; k++) {
		                    if (curr != k && model.x[curr][k] != null && model.cplex.getValue(model.x[curr][k]) > 0.5 - EPS) {
		                        next = k;
		                        break;
		                    }
		                }
		                curr = next;    // if next==-1 loop will terminate
		            }
	
		            // 2. pretty print this machine’s sequence
		            System.out.printf("Machine %d:%n", machineID++);
		            for (int idx = 0; idx < seq.size(); idx++) {
		                TaskInfo t = seq.get(idx);
		                String arrow = (idx == seq.size() - 1) ? "" : " -> ";
		                System.out.printf("  %d (C=%.2f)%s", t.job, t.completion, arrow);
		            }
		            System.out.println();   // newline
		        }
		    }
	
		    // sanity check: report unscheduled jobs (should be none)
		    for (int j = 1; j <= data.n; j++) {
		        if (!visited[j])
		            System.err.println("WARNING: job " + j + " was not assigned in the solution!");
		    }
		}
		/* ------------------------------------------------------------------
		 * 友好 ASCII 甘特图：
		 *   |──0──5──10──15 …                (时间刻度  = scaleStep)
		 *   Machine 1:
		 *      [  1 ]----|----[ 4 ]---|
		 *        ↑            ↑
		 *      dueWin      dueWin          (下面行打印)
		 * ------------------------------------------------------------------*/
	
		public static boolean compareLe(double a1, double a2) {
	        return a1 <= a2 + EPS;
	    }
	    // a1 < a2 ?
	    public static boolean compareLt(double a1, double a2) {
	        return a1 < a2 - EPS;
	    }
	    // a1 ≥ a2 ?
	    public static boolean compareGe(double a1, double a2) {
	        return a1 + EPS >= a2;
	    }
	    // a1 > a2 ?
	    public static boolean compareGt(double a1, double a2) {
	        return a1 > a2 + EPS;
	    }
	    // a1 == a2 ?
	    public static boolean compareEq(double a1, double a2) {
	        return Math.abs(a1 - a2) <= EPS;
	    }
	    
	    
	    

public static class TimerManager {

    private static class TimerStat {
        long totalTimeMills = 0;
        long startTimeMills = 0;
        int callCount = 0;
        boolean running = false;
    }
    
    private static long startTimeNano = -1;

    public static void globalStart() {
        if (startTimeNano < 0)
            startTimeNano = System.nanoTime();
    }
    public static double totalTime=0;
    
    public  static double elapsedSeconds() {
        if (startTimeNano < 0)
            throw new IllegalStateException("Timer not started!");
        return (System.nanoTime() - startTimeNano) / 1e9;
    }

    private static final Map<String, TimerStat> timers = new HashMap<>();

    /** 开始计时 */
    public static void start(String name) {
    	if(!Utility.timeManage) return;
//    	double s1=System.currentTimeMillis();
        TimerStat stat = timers.computeIfAbsent(name, k -> new TimerStat());
        if (stat.running) {
            throw new IllegalStateException("Timer for " + name + " already started!");
        }
        stat.startTimeMills = System.currentTimeMillis();
        stat.running = true;
//        double s2=System.currentTimeMillis();
//        totalTime+=(s2-s1);
    }

    /** 结束计时 */
    public  static void end(String name) {
    	if(!Utility.timeManage) return;
//    	double s1=System.currentTimeMillis();
        TimerStat stat = timers.get(name);
        if (stat == null || !stat.running) {
            throw new IllegalStateException("Timer for " + name + " not started!");
        }
        long duration = System.currentTimeMillis() - stat.startTimeMills;
        stat.totalTimeMills += duration;
        stat.callCount++;
        stat.running = false;
//        double s2=System.currentTimeMillis();
//        totalTime+=(s2-s1);
    }

    /** 输出所有组件时间占比信息 */
    public  static void report() {
    	System.out.println("计时组件时间："+totalTime);
        long total = timers.values().stream().mapToLong(t -> t.totalTimeMills).sum();
        List<Map.Entry<String, TimerStat>> list = new ArrayList<>(timers.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue().totalTimeMills, a.getValue().totalTimeMills));

        System.out.println("======== Timer Report ========");
        for (Map.Entry<String, TimerStat> entry : list) {
            String name = entry.getKey();
            TimerStat stat = entry.getValue();
            double percent = (total == 0) ? 0.0 : (100.0 * stat.totalTimeMills / total);
            double ms = stat.totalTimeMills ;
            System.out.printf("%-20s : %7.2f ms, %5.2f%%, called %d times\n",
                    name, ms, percent, stat.callCount);
        }
        System.out.println("===============================");
        double totalTime=TimerManager.elapsedSeconds();
        System.out.println("总求解用时："+totalTime);
    }

    /** 清空所有记录 */
    public static void reset() {
        timers.clear();
        startTimeNano=-1;
    }
}


}

