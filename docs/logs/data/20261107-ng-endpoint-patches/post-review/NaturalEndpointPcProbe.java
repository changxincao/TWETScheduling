package TWETBPC.LP;

import java.util.*;
import Basic.Data;
import Common.Utility;
import Output.BPCTraceSink;
import TWETBPC.*;
import TWETBPC.GC.*;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.*;

public final class NaturalEndpointPcProbe {
    static double dp(Data d,List<Integer> seq) {
        int h=(int)d.CmaxH,from=0;
        double[] old=new double[h+1]; Arrays.fill(old,Double.POSITIVE_INFINITY);old[0]=0;
        for(int j:seq) {
            double[] cur=new double[h+1];Arrays.fill(cur,Double.POSITIVE_INFINITY);
            int lag=(int)(d.p[j]+d.s[from][j]);double best=Double.POSITIVE_INFINITY;
            for(int t=0;t<=h;t++) {
                if(t>=lag)best=Math.min(best,old[t-lag]);
                if(t>=d.hardWindowStart[j]&&t<=d.hardWindowEnd[j])
                    cur[t]=best+d.getSetupCost(from,j)+d.w_e[j]*Math.max(0,d.d_e[j]-t)+d.w_t[j]*Math.max(0,t-d.d_l[j]);
            }
            old=cur;from=j;
        }
        return Arrays.stream(old).min().orElse(Double.POSITIVE_INFINITY);
    }
    public static void main(String[] args)throws Exception {
        for(String cb:List.of("off","allCycles")) {
            Utility.resetCurUpperBound(Utility.big_M);
            Data d=new Data(".codex-tmp/ng-second-review-20261105-master/endpoint-pc.dat",true,true);
            TWETBPCConfig c=new TWETBPCConfig();
            c.enableBidirectionalPricing=true;c.useGCNGBBStyleNgDssrPricing=true;
            c.enableHeuristicDualProfitableWindow=false;c.enableTimeIndexedGraphDualWindow=false;
            c.bidirectionalMidpointProbe=false;c.bidirectionalCompletionBoundRelaxation=cb;
            c.enableDualBoundPruning=false;c.maxExactPricingColumns=5000;
            TWETColumnEvaluator eval=new TWETColumnEvaluator(d);
            List<Integer> seedSeq=List.of(2,3,1);double seedCost=eval.evaluate(seedSeq);
            System.out.println("cb="+cb+" hasOutsourcingData="+d.hasOutsourcingData()+" H="+d.CmaxH
                    +" hw="+Arrays.toString(d.hardWindowStart)+":"+Arrays.toString(d.hardWindowEnd)
                    +" seed="+seedSeq+" cost="+seedCost+" independent="+dp(d,seedSeq));
            if(Utility.isBigMValue(seedCost))throw new AssertionError("Seed must be production-evaluated feasible");
            Pool pool=new Pool(d);
            int id=pool.addColumn(seedSeq,seedCost,ColumnSource.MANUAL,true);
            Node node=new Node(d,List.of(id),List.of(id),0);node.depth=1;node.id=2;
            double[] beforeLo=d.hardWindowStart.clone(),beforeHi=d.hardWindowEnd.clone();
            node.forbidArc(2,1);
            for(int j=1;j<=d.n;j++)node.forbidOutsourcingJob(j);
            if(!Arrays.equals(beforeLo,d.hardWindowStart)||!Arrays.equals(beforeHi,d.hardWindowEnd))
                throw new AssertionError("Branch changed windows");
            double best=Double.POSITIVE_INFINITY;
            for(List<Integer> seq:List.of(List.of(1,2,3),List.of(1,3,2),List.of(2,1,3),
                    List.of(2,3,1),List.of(3,1,2),List.of(3,2,1))) {
                double exact=dp(d,seq),actual=eval.evaluate(seq);
                boolean legal=node.isColumnCompatible(new TWETColumn(-1,seq,d.n,exact,ColumnSource.MANUAL,false));
                if(legal)best=Math.min(best,exact);
                System.out.println("ROUTE "+seq+" legal="+legal+" DP="+exact+" evaluator="+actual);
            }
            LP lp=new LP(d,pool,new CutPool(),c,new OutsourcingPool(d));lp.construct(node,List.of(id));
            BPCTraceSink trace=new BPCTraceSink(){
                public void onPricingCall(Node n,String engine,boolean improved,int added,String msg,int size) {
                    System.out.println("PRICING added="+added+" "+msg);
                }
            };
            PricingEngine engine=new GCNGBBStyleBidirectionalNgDssrPricingEngine(d,c) {
                public PricingResult price(LP current,TimeLimitChecker checker) {
                    PricingResult result=super.price(current,checker);
                    System.out.println("RAW_RESULT cb="+cb+" columns="+result.getColumns().size()
                            +" certificate="+result.getCertifiedInternalReducedCost()
                            +" machineDual="+current.getMachineDual()+" jobDuals="
                            +current.getJobDual(1)+","+current.getJobDual(2)+","+current.getJobDual(3));
                    return result;
                }
            };
            PC pc=new PC(c,PricingMode.NG_DSSR,List.of(engine),List.of(),trace);
            long start=System.nanoTime();pc.setTimeLimitChecker(()->System.nanoTime()-start>20_000_000_000L);
            try {
                TWETMasterSolution result=pc.solve(lp);
                System.out.println("NATURAL_PC cb="+cb+" returned="+result.getStatus()+" value="+result.getObjectiveValue()
                        +" independentFullOpt="+best+" closureBound="+pc.getBestCertifiedNodeBound()
                        +" observedBound="+pc.getLastObservedDualBound()+" pool="+pool.size()+" integer="+result.isInteger());
                double witnessRc=lp.computeReducedCost(new TWETColumn(-1,List.of(1,2,3),d.n,2.0,
                        ColumnSource.MANUAL,false),lp.captureTruePricingDuals());
                System.out.println("POST_SOLVE witness[1,2,3] trueRC="+witnessRc+" windowsUnchanged="
                        +(Arrays.equals(beforeLo,d.hardWindowStart)&&Arrays.equals(beforeHi,d.hardWindowEnd)));
                if(result.getObjectiveValue()!=2.0 || best!=2.0 || pc.getBestCertifiedNodeBound()!=2.0
                        || witnessRc < -1e-6)
                    throw new AssertionError("Expected repaired PC closure at independent optimum 2");
            }finally{lp.closeModel();}
        }
    }
}
