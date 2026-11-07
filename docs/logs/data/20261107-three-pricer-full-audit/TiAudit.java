package TWETBPC.GC;

import java.lang.reflect.*;
import java.util.*;
import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.CUT.SubsetRowCutEvaluator;
import TWETBPC.LP.*;
import TWETBPC.Model.*;

public class TiAudit {
    static final double INF = 1e100;
    static class Snapshot extends LP {
        final List<Integer> ids = new ArrayList<>();
        final List<Double> duals = new ArrayList<>();
        final double[] jd;
        final double[][] ad;
        double md;
        Snapshot(Data d, TWETBPCConfig c) {
            super(d, new Pool(d), new CutPool(), c, new OutsourcingPool(d));
            construct(new Node(d, new ArrayList<>(), new ArrayList<>(), 0), List.of());
            jd = new double[d.n+1]; ad = new double[d.n+2][d.n+2];
        }
        public double getJobDual(int j) { return jd[j]; }
        public double getMachineDual() { return md; }
        public double getArcDual(int i,int j) { return ad[i][j]; }
        public List<Integer> getActiveSubsetRowPricingCutIds() { return ids; }
        public List<Double> getActiveSubsetRowPricingDuals() { return duals; }
        void cut(TWETCut c, double dual) { ids.add(getCutPool().addCut(c)); duals.add(dual); }
    }
    static Object field(Object x, String name) throws Exception {
        Field f=x.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(x);
    }
    static Data data(Random r) throws Exception {
        Data d=new Data("data/40-2/wet040_001_2m.dat",true,true);
        d.n=4; d.m=2; d.CmaxH=10;
        d.preprocessedArcForbidden=new boolean[6][6];
        for(int j=1;j<=d.n;j++) {
            d.p[j]=2+r.nextInt(2); d.d_e[j]=2+r.nextInt(6); d.d_l[j]=d.d_e[j]+1;
            d.w_e[j]=1+r.nextInt(3); d.w_t[j]=1+r.nextInt(3);
            d.hardWindowStart[j]=0; d.hardWindowEnd[j]=10;
            for(int i=0;i<=d.n;i++) { d.s[i][j]=i==j?0:r.nextInt(2); d.setupCost[i][j]=r.nextInt(4); }
        }
        d.setPenaltyFunctions(); return d;
    }
    static double best;
    static List<Integer> bestSeq;
    static Map<String,Double> costs;
    static void enumerate(Data d, Snapshot lp, int from, int t, double cost, ArrayList<Integer> seq) {
        Node node=lp.getNode();
        if(from>0 && !node.isArcForbidden(from,d.n+1) && !node.isPricingOnlyArcForbidden(from,d.n+1)
                && !node.isTimeIndexedPricingOnlyArcForbidden(from,0,t)) {
            double rc=cost-lp.md-lp.ad[from][d.n+1];
            int prev=0;
            for(int j:seq) { rc-=lp.jd[j]+lp.ad[prev][j]; prev=j; }
            for(int k=0;k<lp.ids.size();k++) rc-=lp.duals.get(k)*SubsetRowCutEvaluator.coefficient(lp.getCutPool().getCut(lp.ids.get(k)),seq,d.n);
            if(rc<best) {best=rc; bestSeq=new ArrayList<>(seq);}
            costs.merge(seq.toString(),cost,Math::min);
        }
        if(t<d.CmaxH && !node.isTimeIndexedPricingOnlyArcForbidden(from,from,t)) enumerate(d,lp,from,t+1,cost,seq);
        for(int j=1;j<=d.n;j++) {
            if(j==from || node.isArcForbidden(from,j) || node.isPricingOnlyArcForbidden(from,j)
                    || node.isTimeIndexedPricingOnlyArcForbidden(from,j,t)) continue;
            int end=t+(int)(d.p[j]+d.s[from][j]);
            if(end>d.CmaxH || end<d.hardWindowStart[j] || end>d.hardWindowEnd[j])continue;
            double penalty=d.penaltyFunction[j].evaluate(end);
            if(Utility.isBigMValue(penalty))continue;
            seq.add(j); enumerate(d,lp,j,end,cost+(lp.isFeasibilityPhaseOneObjectiveMode()?0:d.setupCost[from][j]+penalty),seq); seq.remove(seq.size()-1);
        }
    }
    static double brute(Data d,Snapshot lp) {best=INF;bestSeq=List.of();costs=new HashMap<>();enumerate(d,lp,0,0,0,new ArrayList<>());return best;}
    static Object exact(TimeIndexedGraphRank1CutPricingEngine engine, LP lp) throws Exception {
        Class<?> c=Class.forName(engine.getClass().getName()+"$Rank1CutSolver");
        Constructor<?> ctor=c.getDeclaredConstructor(engine.getClass(),LP.class,boolean.class);ctor.setAccessible(true);
        Object solver=ctor.newInstance(engine,lp,false); Method solve=c.getDeclaredMethod("solve");solve.setAccessible(true);solve.invoke(solver);return solver;
    }
    static void verifyFixing(Data d,Snapshot lp,TWETBPCConfig cfg,boolean aware) throws Exception {
        lp.setFeasibilityPhaseOneObjectiveMode(false);
        brute(d,lp); Map<String,Double> before=new HashMap<>(costs);
        double[] start=Arrays.copyOf(d.hardWindowStart,d.n+1),end=Arrays.copyOf(d.hardWindowEnd,d.n+1);
        Class<?> c=TimeIndexedScalarCompletionBound.class;
        Constructor<?> ctor=c.getDeclaredConstructor(Data.class,TWETBPCConfig.class,LP.class,double.class,double[].class,double[].class,boolean.class);
        ctor.setAccessible(true);Object bound=ctor.newInstance(d,cfg,lp,d.CmaxH,start,end,false);
        Method fix=c.getDeclaredMethod(aware?"applySriAwareArcFixing":"applyArcFixing",double.class);fix.setAccessible(true);fix.invoke(bound,3.0);
        brute(d,lp);
        for(var e:before.entrySet()) {
            String text=e.getKey().substring(1,e.getKey().length()-1);
            List<Integer> seq=Arrays.stream(text.split(", ")).map(Integer::valueOf).toList();
            double rc=e.getValue()-lp.md;int prev=0;
            for(int j:seq){rc-=lp.jd[j]+lp.ad[prev][j];prev=j;}rc-=lp.ad[prev][d.n+1];
            for(int k=0;k<lp.ids.size();k++)rc-=lp.duals.get(k)*SubsetRowCutEvaluator.coefficient(lp.getCutPool().getCut(lp.ids.get(k)),seq,d.n);
            if(rc<3-1e-5 && costs.getOrDefault(e.getKey(),INF)>e.getValue()+1e-5)
                throw new AssertionError("fixing lost low-RC sequence aware="+aware+" seq="+seq+" rc="+rc);
        }
    }
    public static void main(String[] args) throws Exception {
        int mismatches=0;
        for(int seed=0;seed<160;seed++) {
            Random r=new Random(seed);Data d=data(r);TWETBPCConfig cfg=new TWETBPCConfig();
            cfg.useTimeIndexedGraphPricing=true;cfg.useTimeIndexedGraphRank1CutPricing=true;cfg.enableTimeIndexedGraphDualWindow=false;
            cfg.timeIndexedGraphMaxExactPricingColumns=20;
            Snapshot lp=new Snapshot(d,cfg);lp.md=-r.nextInt(5);
            for(int j=1;j<=d.n;j++)lp.jd[j]=r.nextInt(18);
            for(int i=0;i<=d.n;i++)for(int j=1;j<=d.n+1;j++)lp.ad[i][j]=r.nextInt(5)-2;
            if(seed%4==0)lp.setFeasibilityPhaseOneObjectiveMode(true);
            for(int k=0;k<3;k++) {
                List<Integer> scope=k==0?List.of(1,2,3):List.of(2,3,4);
                List<Long> arcs=new ArrayList<>();for(int i=0;i<=d.n;i++)for(int j=1;j<=d.n;j++)if(r.nextBoolean())arcs.add(SubsetRowCutEvaluator.arcKey(i,j));
                lp.cut(new TWETCut(-1,TWETCutType.SUBSET_ROW,scope,k==1?scope:null,k==2?arcs:null,0.5,1,"audit"),-1.0-r.nextInt(5));
            }
            if(seed>=80)for(int t=0;t<10;t++)for(int i=0;i<=d.n;i++)for(int j=1;j<=d.n;j++)
                if(r.nextInt(12)==0)lp.getNode().forbidTimeIndexedPricingOnlyArc(i,j,t);
            double expected=brute(d,lp);
            Object solver=exact(new TimeIndexedGraphRank1CutPricingEngine(d,cfg),lp);
            double actual=(double)field(solver,"bestPseudoReducedCost");
            if(Math.abs(actual-expected)>1e-5) {
                System.out.println("MISMATCH seed="+seed+" expected="+expected+" actual="+actual+" seq="+bestSeq);
                mismatches++;
            }
            if(seed<40) {
                cfg.timeIndexedCompletionBoundWindowTightening=true;
                verifyFixing(d,lp,cfg,seed%2==0);
            }
        }
        System.out.println("rank1 independent exhaustive cases=160 mismatches="+mismatches);
        System.out.println("scalar/SRI-aware fixing low-RC sequence preservation cases=40 passed");
        if(mismatches>0)throw new AssertionError("pricing mismatch");
    }
}
