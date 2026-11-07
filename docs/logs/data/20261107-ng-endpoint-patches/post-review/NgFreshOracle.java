package TWETBPC.GC;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.*;
import TWETBPC.Model.TWETColumn;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** 独立整数时间DP枚举基本序列，不调用生产PWLF计算期望值。 */
public final class NgFreshOracle {
    static final Path ROOT=Path.of(".codex-tmp/ng-second-review-20261105");
    static Data data;
    static Node node;
    static final ArrayList<ArrayList<Integer>> routes=new ArrayList<>();
    static final ArrayList<Double> costs=new ArrayList<>();
    static int runs, returned, rounds, maxRounds;
    static boolean tightWindows;
    static int mismatches;
    static int costDeviations;
    static double maxAbsoluteCostDeviation;
    static boolean forceNormal;
    static Object get(Object owner,String name)throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true);return f.get(owner);
    }
    static void set(Object owner,String name,Object value)throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);
    }
    static void enumerate(ArrayList<Integer> seq,int mask,double[] previous) {
        int h=(int)data.CmaxH, last=seq.isEmpty()?0:seq.get(seq.size()-1);
        for(int j=1;j<=data.n;j++) if((mask&(1<<j))==0&&!node.isArcForbidden(last,j)) {
            int delay=(int)(data.s[last][j]+data.p[j]);
            double[] cur=new double[h+1];Arrays.fill(cur,Double.POSITIVE_INFINITY);
            double prefix=Double.POSITIVE_INFINITY;
            for(int t=0;t<=h;t++) {
                if(t>=delay)prefix=Math.min(prefix,previous[t-delay]);
                if(t>=data.hardWindowStart[j]&&t<=data.hardWindowEnd[j])
                    cur[t]=prefix+data.getSetupCost(last,j)+data.w_e[j]*Math.max(0,data.d_e[j]-t)
                        +data.w_t[j]*Math.max(0,t-data.d_l[j]);
            }
            seq.add(j);
            double value=Arrays.stream(cur).min().orElseThrow();
            if(Double.isFinite(value)) {
                if(!node.isArcForbidden(j,node.sinkId())) {routes.add(new ArrayList<>(seq));costs.add(value);}
                enumerate(seq,mask|(1<<j),cur);
            }
            seq.remove(seq.size()-1);
        }
    }
    static void fixture(int sample)throws Exception {
        Random r=new Random(991700L+sample);int n=6,scale=1+sample/4;
        StringBuilder text=new StringBuilder(n+" 2\n");
        for(int j=1;j<=n;j++) {
            int p=(1+r.nextInt(3))*scale, de=(5+r.nextInt(20))*scale;
            int dl=de+(sample%2==0?0:8*scale);
            text.append(p+" "+de+" "+dl+" "+(1+r.nextInt(4))+" "+(1+r.nextInt(4))+"\n");
        }
        int[][] s=new int[n+1][n+1];
        for(int i=0;i<=n;i++)for(int j=1;j<=n;j++)if(i!=j)
            s[i][j]=scale*(sample%4<2?(i==0||((i-1)/2==(j-1)/2)?1:6):1+r.nextInt(7));
        for(int k=1;k<=n;k++)for(int i=0;i<=n;i++)for(int j=1;j<=n;j++)s[i][j]=Math.min(s[i][j],s[i][k]+s[k][j]);
        for(String block:List.of("SETUP","SETUP_COST")) {
            text.append(block+"\n");
            for(int i=0;i<=n;i++){for(int j=0;j<=n;j++)text.append(s[i][j]*(block.equals("SETUP")?1:0.5)).append(' ');text.append('\n');}
        }
        Path input=ROOT.resolve("input-"+sample+".dat");Files.writeString(input,text);
        Utility.resetCurUpperBound(Utility.big_M);data=new Data(input.toString(),true,true);
        // 使用明确的整数端点，避免混入已单独讨论的孤立硬窗端点问题。
        for(int i=0;i<=n;i++) {data.hardWindowStart[i]=0;data.hardWindowEnd[i]=data.CmaxH;
            Arrays.fill(data.preprocessedArcForbidden[i],false);}
        if(tightWindows) {
            for(int j=1;j<=n;j++) {
                data.hardWindowStart[j]=Math.max(data.p[j],data.d_e[j]-6*scale);
                data.hardWindowEnd[j]=Math.min(data.CmaxH,data.d_l[j]+8*scale);
            }
            data.setPenaltyFunctions();
        }
        node=new Node(data,new ArrayList<>(),new ArrayList<>(),0);
        if(sample%3==1){node.forbidArc(1,2);node.forbidArc(0,3);node.forbidArc(4,node.sinkId());}
        routes.clear();costs.clear();double[] source=new double[(int)data.CmaxH+1];
        Arrays.fill(source,Double.POSITIVE_INFINITY);source[0]=0;enumerate(new ArrayList<>(),0,source);
    }
    public static void main(String[] args)throws Exception {
        tightWindows=args.length>0&&args[0].equals("windows");
        forceNormal=args.length>2&&args[2].equals("normal");
        for(int sample=0;sample<12;sample++) {
            if(args.length>1&&!args[1].equals("all")&&sample!=Integer.parseInt(args[1]))continue;
            fixture(sample);Random r=new Random(7341+sample);
            double[] dual=new double[data.n+1];double[][] arc=new double[data.n+2][data.n+2];
            for(int j=1;j<=data.n;j++)dual[j]=(forceNormal?100:10)+r.nextInt(forceNormal?150:30);
            for(int i=0;i<=data.n;i++)for(int j=1;j<=data.n+1;j++)arc[i][j]=r.nextInt(7)-3;
            for(int variant=0;variant<8;variant++)for(int sign:new int[]{-1,1}) {
                if(Integer.getInteger("audit.variant",-1)>=0&&variant!=Integer.getInteger("audit.variant",-1))continue;
                boolean phaseOne=!forceNormal&&sample%3==2;
                TWETBPCConfig c=new TWETBPCConfig();c.useGCNGBBStyleNgDssrPricing=true;c.enableBidirectionalPricing=true;
                c.bidirectionalLabelQueueOrdering="TIME";c.bidirectionalJoinBestThresholdMode="bestUB";
                c.ngDssrInitialNgSetSize=(variant&1)==0?0:data.n;c.bidirectionalMidpointProbe=(variant&2)!=0;
                c.bidirectionalMidpointProbePopLimit=20;c.bidirectionalMidpointProbeDssrImbalanceThreshold=4;
                c.bidirectionalCompletionBoundRelaxation=(variant&4)==0?"allCycles":"off";
                c.enableNgDssrJoinEnvelopePrefilter=sample%2==0;c.enableNgDssrJoinEnvelopeCompression=false;
                if(System.getProperty("audit.envelope")!=null)c.enableNgDssrJoinEnvelopePrefilter=Boolean.getBoolean("audit.envelope");
                c.enableTimeIndexedGraphDualWindow=false;c.enableHeuristicDualProfitableWindow=false;
                c.enableNgDssrHistoryWarmStart=false;c.maxExactPricingColumns=20;
                double base=Double.POSITIVE_INFINITY;int bestRoute=-1;
                for(int ri=0;ri<routes.size();ri++) {
                    double value=phaseOne?0:costs.get(ri);int prev=0;
                    for(int job:routes.get(ri)){value-=dual[job]+arc[prev][job];prev=job;}
                    value-=arc[prev][node.sinkId()];if(value<base){base=value;bestRoute=ri;}
                }
                double machine=base+sign*2.0;
                LP lp=new LP(data,new Pool(data),new CutPool(),c,new OutsourcingPool(data));
                lp.construct(node,new ArrayList<>());lp.setFeasibilityPhaseOneObjectiveMode(phaseOne);
                try {
                    set(lp,"machineDual",machine);System.arraycopy(dual,0,(double[])get(lp,"jobDual"),0,dual.length);
                    double[][] dest=(double[][])get(lp,"arcDual");for(int i=0;i<arc.length;i++)System.arraycopy(arc[i],0,dest[i],0,arc[i].length);
                    var backend=sample%2==0?GCNGBBStyleBidirectionalNgDssr.DominanceBackend.PAPER:GCNGBBStyleBidirectionalNgDssr.DominanceBackend.LIST_PARTIAL;
                    var gc=new GCNGBBStyleBidirectionalNgDssr(data,c,backend);long start=System.nanoTime();
                    ArrayList<TWETColumn> columns=gc.solve(lp,()->System.nanoTime()-start>10_000_000_000L);
                    double cert=gc.getLastRelaxedRoundBestReducedCost(),exact=base-machine;
                    String key="sample="+sample+" variant="+variant+" sign="+sign;
                    if(!Double.isFinite(cert))throw new AssertionError("INCOMPLETE "+key+" "+gc.getLastMessage());
                    if((exact<0)!=!columns.isEmpty()||Math.min(0,cert)>Math.min(0,exact)+1e-5) {
                        mismatches++;System.out.println("MISMATCH "+key+" exact="+exact+" cert="+cert+" best="+routes.get(bestRoute)+" truecost="+costs.get(bestRoute)+" "+gc.getLastMessage());
                        if(get(gc,"targetTrace")!=null)System.out.println("TRACE "+get(gc,"targetTrace"));
                        if(Boolean.getBoolean("audit.envelopeDiagnostic"))EnvelopeDiagnostic.inspect(gc,lp);
                    }
                    for(TWETColumn col:columns){int ri=routes.indexOf(col.getSequence());if(ri<0)throw new AssertionError("INVALID ROUTE "+key+" "+col.getSequence());
                        double trueCost=costs.get(ri),rc=(phaseOne?0:trueCost)-machine;int prev=0;
                        for(int j:col.getSequence()){rc-=dual[j]+arc[prev][j];prev=j;}rc-=arc[prev][node.sinkId()];
                        if(rc>=-1e-6)throw new AssertionError("BAD COLUMN RC "+key+" "+col.getSequence()+" rc="+rc);
                        double costError = col.getCost() - trueCost;
                        if(Math.abs(costError)>1e-5) {
                            costDeviations++;
                            maxAbsoluteCostDeviation=Math.max(maxAbsoluteCostDeviation,Math.abs(costError));
                            System.out.println("COST_DEVIATION "+key+" sequence="+col.getSequence()
                                    +" reported="+col.getCost()+" independent="+trueCost+" signedError="+costError);
                        }
                    }
                    int nr=(int)get(gc,"ngDssrRoundsExecuted");rounds+=nr;maxRounds=Math.max(maxRounds,nr);runs++;returned+=columns.size();
                }finally{lp.closeModel();}
            }
            System.out.println("PASS sample="+sample+" sequences="+routes.size()+" horizon="+data.CmaxH+" runs="+runs);
        }
        System.out.println("SUMMARY runs="+runs+" returned="+returned+" rounds="+rounds+" maxRounds="+maxRounds+" mismatches="+mismatches);
        System.out.println("COST_SUMMARY deviatingColumns="+costDeviations+" maxAbsoluteError="+maxAbsoluteCostDeviation);
        if(mismatches>0)throw new AssertionError("mismatches="+mismatches);
    }
}
