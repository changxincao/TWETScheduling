package Basic;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Random;

import Common.PiecewiseLinearFunction;
import Common.Utility;

public class Data {
	public static int scale=1;
    public int n;           // number of jobs
    public int m;
    public double[] p, d_e,d_l, w_e, w_t,r; // arrays length n
    //r先不管，感觉可能某些地方会不兼容
    public double[][] s;//sequence dependent set up
    public double min_s[];//每个任务的最小setup
    public PiecewiseLinearFunction[] penaltyFunction;
    public double Cmax=1e6;//问题下的全局上界
    public Data(String path,boolean setup,boolean due_date) throws IOException {
    	BufferedReader reader=new BufferedReader(new FileReader(path));
    	String line=reader.readLine();
    	this.n = Integer.parseInt(line.split(" ")[0]);
    	this.m = Integer.parseInt(line.split(" ")[1]);
        this.p = new double[n+1]; 
        this.d_e = new double[n+1]; 
        this.d_l = new double[n+1]; 
        this.w_e = new double[n+1];
        this.w_t = new double[n+1];
        this.s= new double[n+1][n+1];//如果存在释放时间，setup可以发生在释放时间之前
        this.min_s=new double[n+1];
        this.r= new double[n+1];
        debug_set();
        if(due_date) {
        	 load_dd_data(setup, reader);
        }else {
        	load_dw_data(setup, reader);
        }
       
        setCmax();
//        Cmax=7700;
        setPenaltyFunctions();
        for(int i=1;i<n+1;i++) {
        	double minSi=Utility.big_M;
        	for(int j=0;j<n+1;j++) {
        		minSi=Math.min(minSi, s[j][i]);
        	}
        	min_s[i]=minSi;
        }
        
    }
    
    public void setCmax() {
    	/* ---- compute horizon (s_ij = 0) ---- */
        double sumP = 0, maxP = 0, maxD = 0, maxR=0;
        for (int j = 1; j <= n; j++) {
            sumP += p[j];
            double max_sj=0;
            for(int i=0;i<n+1;i++) {
            	max_sj=Math.max(max_sj, s[i][j]);
            }
            sumP+=max_sj;
            maxP = Math.max(maxP, p[j]+max_sj);
            maxR =Math.max(maxR, r[j]);
            maxD = Math.max(maxD, d_e[j]-(p[j]+max_sj));//即每个任务在d_ej-pj-maxi(s_ij)这个时间以后执行，那么在任务前不可能存在等待时间，等待只会导致延迟？
            //如果可能是小数的话，那就不能向下取整了？
        }
        Cmax =  (sumP / (double) m + (1.0-1.0/(double)m)*maxP) + maxD+1;
        //不做取整，如果都是整数，向下取值，参考下文
//        Kramer, A., Dell’Amico, M., Feillet, D., & Iori, M. (2020). Scheduling jobs with release dates on identical parallel machines by minimizing the total weighted completion time. Computers and Operations Research, 123, 105018. https://doi.org/10.1016/j.cor.2020.105018
    }
    
    //每个任务在时间max{(d_i-si-pi),r_i}之后不会有等待时间，其中s_i为所有i设置时间的最大
    //在此基础上取前m-1个任务的平均执行时间+最大任务的执行时间，，这是无等待情况下，因此基于所有任务的最大最早开始时间执行
    //还可以看作是一个带有不同释放时间的东西，后续启发式求解
    
    public void setPenaltyFunctions() {
    	penaltyFunction=new PiecewiseLinearFunction[n+1];
    	penaltyFunction[0]=new PiecewiseLinearFunction(0, Cmax);
    	penaltyFunction[0].addSegment(0,Cmax,0,0);
    	for(int jid=1;jid<n+1;jid++) {
    		penaltyFunction[jid]=new PiecewiseLinearFunction(0,Cmax);
    		if((!Utility.compareEq(0, d_e[jid]))&&(!Utility.compareEq(w_e[jid], 0))) {
    			penaltyFunction[jid].addSegment(0, d_e[jid], -w_e[jid], w_e[jid]*d_e[jid]);
    		}
    		if(!Utility.compareEq(d_e[jid],d_l[jid] )) {
    			penaltyFunction[jid].addSegment(d_e[jid],d_l[jid] , 0,0);
    		}
    		penaltyFunction[jid].addSegment(d_l[jid], Cmax, w_t[jid], -w_t[jid]*d_l[jid]);
        	
    	}
    	//TODO 待测试setDomian()
    	//每个点上最多三段，可能最少就一段,如果不存在窗口且de=0或者不存在早到惩罚成本，此时就只有迟到成本了
    }
    
    public void load_dw_data(boolean setup,BufferedReader reader) throws IOException {
    	//dw->single due window
    	//TODO
    }

    public void load_dd_data(boolean setup,BufferedReader reader) throws IOException {
    	//dd->single due date
    	//后续有sij数据再写
    	
    	for(int jid=1;jid<=n;jid++) {
    		String line=reader.readLine();
    		this.p[jid]=Integer.parseInt(line.split(" ")[0])*scale;
    		this.d_e[jid]=Integer.parseInt(line.split(" ")[1])*scale;
    		this.d_l[jid]=Integer.parseInt(line.split(" ")[1])*scale;
    		this.w_e[jid]=Integer.parseInt(line.split(" ")[2]);
    		this.w_t[jid]=Integer.parseInt(line.split(" ")[3]);
    	}
    	if(setup) {
//    		...
    		for(int i=0;i<n+1;i++) {
    			for(int j=1;j<n+1;j++) {
    				if(i==j) continue;
    				s[i][j]=0;//new Random(0).nextDouble(30)*scale;
    			}
    		}
    	}
    }
    public double cost(int j, int C) {
        /*  1. 计算提前量 E_j = max{d_j - C, 0} */
        double earliness = Math.max(d_e[j] - C, 0);

        /*  2. 计算逾期量 T_j = max{C - d_j, 0} */
        double tardiness = Math.max(C - d_l[j], 0);

        /*  3. 加权求和返回 */
        return w_e[j] * earliness + w_t[j] * tardiness;
    }
   
    
    public void debug_set() {
//    	n=40;
    	m=2;
    	scale=3;
    }
    
}
