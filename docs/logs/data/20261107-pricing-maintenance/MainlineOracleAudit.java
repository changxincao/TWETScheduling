package HEU;

import java.util.*;
import Basic.Data;
import Common.Utility;
import TWETBPC.*;
import TWETBPC.GC.FixedInitialColumnSeed;
import TWETBPC.IO.TWETColumnEvaluator;

public final class MainlineOracleAudit {
    static Data fixture(int id) throws Exception {
        Data d = SmallExactHeuristicBatchTest.buildRandomCase(id, id<4 ? 6 : 7, id<4 ? id % 2 + 2 : id % 3 + 2);
        Arrays.fill(d.outsourcingCost, Utility.big_M);
        d.outsourcingCost[0] = 0;
        for (int i=0;i<=d.n;i++) for(int j=0;j<=d.n;j++) {
            int families = id<6 ? 3 : 2;
            double s = i==j || j==0 ? 0 : i==0 ? 2 : id<2 ? 2+Math.abs(i-j) : i%families==j%families ? 2 : 8;
            d.s[i][j]=s; d.setupCost[i][j]=2*s;
        }
        d.setImprovedCmax(); d.setPreprocessedHardWindows(); d.setPenaltyFunctions();
        return d;
    }
    static void enumerate(Data d, TWETColumnEvaluator eval, ArrayList<Integer> seq, int mask, double[] route) {
        if(mask!=0) {
            double pwlf=eval.evaluate(seq), discrete=sequenceDp(d,seq);
            if(Math.abs(pwlf-discrete)>1e-5) throw new AssertionError("Sequence cost mismatch: "+seq+" pwlf="+pwlf+" dp="+discrete);
            route[mask]=Math.min(route[mask],discrete);
        }
        for(int j=1;j<=d.n;j++) if((mask & (1<<(j-1)))==0) {
            seq.add(j); enumerate(d,eval,seq,mask|(1<<(j-1)),route); seq.remove(seq.size()-1);
        }
    }
    static double sequenceDp(Data d, List<Integer> seq) {
        int h=(int)Math.ceil(d.CmaxH), prev=0;
        double[] dp=new double[h+1]; Arrays.fill(dp,Double.POSITIVE_INFINITY); dp[0]=0;
        for(int j:seq) {
            double[] next=new double[h+1]; Arrays.fill(next,Double.POSITIVE_INFINITY);
            int delay=(int)(d.s[prev][j]+d.p[j]); double prefix=Double.POSITIVE_INFINITY;
            for(int t=delay;t<=h;t++) {
                prefix=Math.min(prefix,dp[t-delay]);
                next[t]=prefix+d.setupCost[prev][j]+Math.max(0,d.d_e[j]-t)*d.w_e[j]+Math.max(0,t-d.d_l[j])*d.w_t[j];
            }
            dp=next; prev=j;
        }
        return Arrays.stream(dp).min().getAsDouble();
    }
    static double oracle(Data d) {
        int size=1<<d.n;
        double[] route=new double[size]; Arrays.fill(route,Double.POSITIVE_INFINITY); route[0]=0;
        enumerate(d,new TWETColumnEvaluator(d),new ArrayList<>(),0,route);
        double[] dp=new double[size]; Arrays.fill(dp,Double.POSITIVE_INFINITY); dp[0]=0;
        for(int k=0;k<d.m;k++) {
            double[] next=dp.clone();
            for(int mask=1;mask<size;mask++) for(int part=mask;part>0;part=(part-1)&mask)
                next[mask]=Math.min(next[mask],dp[mask^part]+route[part]);
            dp=next;
        }
        return dp[size-1];
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("twet.bpc.cplexThreads","1");
        for(int id=0;id<10;id++) {
            double optimum=oracle(fixture(id));
            for(BPCAlgorithmProfile profile:new BPCAlgorithmProfile[]{BestBpcProfiles.NG_DSSR,BestBpcProfiles.TIME_INDEXED_GRAPH,BestBpcProfiles.TIME_INDEXED_GRAPH_RANK1})
                for(boolean cluster:new boolean[]{false,true}) {
                    Data d=fixture(id); Utility.resetCurUpperBound(Utility.big_M);
                    TWETBPCConfig c=new TWETBPCConfig(); profile.apply(c);
                    c.enableClusterBranching=cluster; c.structuredArcStrictTypePriority=cluster;
                    c.timeIndexedCompletionBoundNodeArcFixingMaxDepth=Integer.MAX_VALUE;
                    c.runALNSForSeed=false; c.reuseConfiguredBestSolution=false;
                    c.writeBPCResultFiles=false; c.enableBPCConsoleOutput=false;
                    c.solveTimeLimitSeconds=40; c.maxNodes=10000;
                    ArrayList<ArrayList<Integer>> seed=new ArrayList<>();
                    for(int k=0;k<d.m;k++) seed.add(new ArrayList<>());
                    for(int j=1;j<=d.n;j++) seed.get((j-1)%d.m).add(j);
                    c.fixedInitialColumnSeed=new FixedInitialColumnSeed(seed,seed);
                    TWETSolveResult r=new TWETBPCSolver(d,c).solve();
                    System.out.printf(Locale.ROOT,"case=%d m=%d algorithm=%s cluster=%s optimum=%.9f ub=%.9f lb=%.9f nodes=%d status=%s%n",id,d.m,profile.getName(),cluster,optimum,r.getIncumbentCost(),r.getBestBound(),r.getProcessedNodes(),r.getStatus());
                    if(Math.abs(r.getIncumbentCost()-optimum)>1e-5 || Math.abs(r.getBestBound()-optimum)>1e-5)
                        throw new AssertionError("oracle mismatch or not closed: "+r.getMessage());
                }
        }
        System.out.println("MainlineOracleAudit 60/60 passed");
    }
}
