package Common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import Basic.ArcFlowModel;
import Basic.Data;
import Common.Utility.TimerManager;
import HEU.Solution;
import ilog.concert.IloException;

public class Utility {
	public Data data;
	public static HashMap<String, Integer> debugMap=new HashMap<String, Integer>();//记录各类元素执行次数
	public static int debugNum=0;
	public static double EPS=1e-6;
	public static double big_M=1e8;
	public static double curUpperBound=big_M;//记录当前算例下的最优成本，过程中动态更新,注意这里由于定义为了静态的，每次迭代算例需更新
	//由于这个东西主要用于截断PiecewiseLinearFunction，不能直接在PWLF中使用data截断，在PWLF中传入一个data对象不合适
	//所有比较的地方都替换为了使用这个上界，设置为当前最优解+1，可等价于big_M应该
	//所有算例计算之处要重新初始化为big_M.
	// 2026-05-14: curUpperBound 在启发式和精确定价中的语义不同。启发式阶段它可以设为当前
	// 已知最好解成本，用来让 PiecewiseLinearFunction 在 prefix/suffix 取小和 move 评价时少维护
	// 明显不可能改进当前解的区间，相当于一个更紧的动态 big-M。后续若先用启发式生成初始列/上界，
	// 再进入 BPC/pricing 精确阶段，应先把启发式最优解和目标值单独保存到 BPC 的 incumbent/初始列池，
	// 然后把 curUpperBound 重新设回 Utility.big_M。pricing 里的 reduced cost 会受 dual/cut 项影响，
	// partial label 当前看起来较差，后续仍可能被 dual 拉低，因此不能直接用启发式上界截断函数段。
	
	public static  Random rng;//
	static {
		int seed = new Random(0).nextInt(10);
//		System.out.println("seed:" + seed);
		rng=new Random(seed);
	}
	public Utility(Data d)
	{
		data = d;	
	}
	
	public static void updateCurUpperBound(double value) {
		//并不是一个全局的上界，而是一个局部上界，当前VND初始解的值，在搜索过程中帮助减少分段数，不可使用全局上界
		// 2026-05-14: 该更新只适合 HEU/局部搜索阶段。BPC pricing 阶段如果需要使用启发式解，
		// 应通过 incumbent 或初始列传入，不应继续让 PWLF 用该值做动态截断。
		
		if(Utility.compareLt(value, curUpperBound)) {
			Utility.curUpperBound=value;
		}
		//从结果看，segmentNum数量还是少了不少的
		//怎么看上去对时间作用不大..
		//暂时可能略有一丢丢改进，区别不大。可能在启发式过程中只能用当前局部上界作用不大
		//这个到了精确似乎也不能用，因为按照上界剪枝是基于后续加入任务以后成本肯定是会增加的来算的，但精确里边考虑dual以后函数是存在负值的
		//此时直接按照上界剪枝就会出问题
	}
	public static void resetCurUpperBound(double value) {
		//并不是一个全局的上界，而是一个局部上界，当前VND初始解的值，在搜索过程中帮助减少分段数，不可使用全局上界
		// 2026-05-14: 进入精确定价前建议调用 resetCurUpperBound(Utility.big_M)，避免启发式阶段
		// 留下的动态上界影响 pricing 中的分段函数操作。
		Utility.curUpperBound=value;//从结果看，segmentNum数量还是少了不少的
		//怎么看上去对时间作用不大..
		//能快一丢丢
		//TODO 看把等于上界的segment的函数直接设置为空函数不知道会不会好点,输出一下判断,看起来并不存在这种情况哈哈哈哈
	}
	
	public static void debugNumPlus() {
		debugNum++;
	
//		System.out.print("debug:"+debugNum);
	}

	/**
	 * 2026-05-14: PWLF 右端定义域调试检查。默认由 Configure.debugPWLFDomainCheck 关闭；
	 * 接入 pricing 时打开，用来定位哪一步把 forward label 从 [a,T] 裁成了 [a,t<T]。
	 */
	public static void debugCheckPWLFRightBound(String operation, PiecewiseLinearFunction f) {
		if (!Configure.debugPWLFDomainCheck || f == null || f.head == null || f.tail == null) {
			return;
		}
		// 2026-05-14: tail.end 是当前 segment 链表真实覆盖到的右端点，domainEnd 是函数对象记录的元数据。
		// setDomain/trimToDomain 这类操作可能把链表裁到 end<T，随后又把 domainEnd 恢复成原来的 T。
		// 因此这里检查二者是否一致，用来定位哪一步破坏了 pricing 里要求的 [a,T] 右端统一约束。
		if (!compareEq(f.tail.end, f.domainEnd)) {
			String key = "PWLF_RightBound_Violation_" + operation;
			debugMap.put(key, debugMap.getOrDefault(key, 0) + 1);
			System.err.println("[PWLF domain check] " + operation + ": tail.end=" + f.tail.end
					+ ", domainEnd=" + f.domainEnd + ", domainStart=" + f.domainStart
					+ ", head.start=" + f.head.start + ", object=" + System.identityHashCode(f));
		}
	}

	public static void debugCheckPWLFRightBoundPair(String operation, PiecewiseLinearFunction f,
			PiecewiseLinearFunction g) {
		if (!Configure.debugPWLFDomainCheck) {
			return;
		}
		debugCheckPWLFRightBound(operation + ".left", f);
		debugCheckPWLFRightBound(operation + ".right", g);
		if (f == null || g == null || f.head == null || g.head == null || f.tail == null || g.tail == null) {
			return;
		}
		if (!compareEq(f.tail.end, g.tail.end)) {
			String key = "PWLF_RightBound_Mismatch_" + operation;
			debugMap.put(key, debugMap.getOrDefault(key, 0) + 1);
			System.err.println("[PWLF domain check] " + operation + ": left.tail.end=" + f.tail.end
					+ ", right.tail.end=" + g.tail.end + ", left=" + System.identityHashCode(f)
					+ ", right=" + System.identityHashCode(g));
		}
	}

	/**
	 * 2026-05-15: PWLF backward 方向的左端定义域调试检查。
	 * forward pricing 关注真实右端是否仍到 T；backward pricing 对称地关注真实左端是否仍从 0/domainStart 开始。
	 */
	public static void debugCheckPWLFLeftBound(String operation, PiecewiseLinearFunction f) {
		if (!Configure.debugPWLFDomainCheck || f == null || f.head == null || f.tail == null) {
			return;
		}
		if (!compareEq(f.head.start, f.domainStart)) {
			String key = "PWLF_LeftBound_Violation_" + operation;
			debugMap.put(key, debugMap.getOrDefault(key, 0) + 1);
			System.err.println("[PWLF left-domain check] " + operation + ": head.start=" + f.head.start
					+ ", domainStart=" + f.domainStart + ", tail.end=" + f.tail.end
					+ ", domainEnd=" + f.domainEnd + ", object=" + System.identityHashCode(f));
		}
	}

	public static void debugCheckPWLFMergeContract(String operation, PiecewiseLinearFunction f,
			PiecewiseLinearFunction g) {
		if (!Configure.debugPWLFDomainCheck) {
			return;
		}
		debugCheckPWLFRightBoundPair(operation, f, g);
		if (f == null || g == null || f.head == null || g.head == null || f.tail == null || g.tail == null) {
			return;
		}

		double overlapStart = Math.max(f.head.start, g.head.start);
		double overlapEnd = Math.min(f.tail.end, g.tail.end);
		boolean noPositiveOverlap = !compareLt(overlapStart, overlapEnd);
		boolean differentRightBound = !compareEq(f.tail.end, g.tail.end);
		boolean leftSinglePoint = compareEq(f.head.start, f.tail.end);
		boolean rightSinglePoint = compareEq(g.head.start, g.tail.end);

		// 2026-05-14: mergeMinimum 不是通用下包络，只按 forward label 的 [a,T] 契约使用。
		// 这里额外检查“正长度交集、右端相同、非单点退化”，发现问题时只记录，不改变算法流程。
		if (noPositiveOverlap || differentRightBound || leftSinglePoint || rightSinglePoint) {
			String key = "PWLF_MergeContract_Violation_" + operation;
			debugMap.put(key, debugMap.getOrDefault(key, 0) + 1);
			System.err.println("[PWLF merge contract] " + operation
					+ ": left=[" + f.head.start + "," + f.tail.end + "]"
					+ ", right=[" + g.head.start + "," + g.tail.end + "]"
					+ ", overlap=[" + overlapStart + "," + overlapEnd + "]"
					+ ", noPositiveOverlap=" + noPositiveOverlap
					+ ", differentRightBound=" + differentRightBound
					+ ", leftSinglePoint=" + leftSinglePoint
					+ ", rightSinglePoint=" + rightSinglePoint
					+ ", leftObject=" + System.identityHashCode(f)
					+ ", rightObject=" + System.identityHashCode(g));
		}
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
		        if (model.x[0][j] != null && compareGe(model.cplex.getValue(model.x[0][j]), 0.5)) {
	
		            List<TaskInfo> seq = new ArrayList<>();
		            int curr = j;
	
		            while (curr != -1&&curr != data.n+1 && !visited[curr]) {
		                double Ccurr = model.cplex.getValue(model.C[curr]);
		                seq.add(new TaskInfo(curr, Ccurr));
		                visited[curr] = true;
	
		                // find successor of curr
		                int next = -1;
		                for (int k = 1; k <= data.n; k++) {
		                    if (curr != k && model.x[curr][k] != null && compareGe(model.cplex.getValue(model.x[curr][k]), 0.5)) {
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
	    
//	    public double getScheduleCost(ArrayList<Integer> schedule)
//		{
//			double cost = 0;
//			for(int i = 1; i < schedule.size(); ++i)
//			{
//				cost += data.GetDistance(route.get(i - 1), route.get(i));
//			}
//			
//			return cost;
//		}
	    
	    

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
    	if(!Configure.timeManage) return;
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
    	if(!Configure.timeManage) return;
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

