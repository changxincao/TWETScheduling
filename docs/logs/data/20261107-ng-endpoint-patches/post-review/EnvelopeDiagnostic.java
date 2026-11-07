package TWETBPC.GC;
import Common.PiecewiseLinearFunction;
import TWETBPC.LP.LP;
import java.lang.reflect.*;
import java.util.*;

public class EnvelopeDiagnostic {
    static Object field(Object o,String n)throws Exception {
        for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(n);
    }
    static Object call(Object o,String n,Object...args)throws Exception {
        for(Method m:o.getClass().getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(o,args);}
        throw new NoSuchMethodException(n);
    }
    static Object find(Object gc,List<?> groups,boolean forward,List<Integer> seq)throws Exception {
        for(Object group:groups)for(Object label:(List<?>)field(group,"labels"))
            if(call(gc,forward?"recoverForwardSequence":"recoverBackwardSequence",label).equals(seq))return group;
        throw new AssertionError("missing group "+seq);
    }
    static Object label(Object gc,Object group,boolean f,List<Integer> seq)throws Exception {
        for(Object l:(List<?>)field(group,"labels"))if(call(gc,f?"recoverForwardSequence":"recoverBackwardSequence",l).equals(seq))return l;
        throw new AssertionError();
    }
    static void dump(Object group,double t)throws Exception {
        for(Object seg:(List<?>)field(field(group,"envelope"),"segments")) {
            double s=(double)field(seg,"start"),e=(double)field(seg,"end"),a=(double)field(seg,"slope"),b=(double)field(seg,"intercept");
            System.out.println("ENV seg="+s+":"+e+" a="+a+" b="+b+" containsT="+(s<=t&&t<=e)+" atT="+(a*t+b));
        }
    }
    static void inspect(Object gc,LP lp)throws Exception {
        Object index=call(gc,"buildJoinEnvelopeIndex");
        Object fg=find(gc,(List<?>)((List<?>)field(index,"forwardByTerminal")).get(3),true,List.of(2,4,3));
        Object bg=find(gc,(List<?>)((List<?>)field(index,"backwardByTerminal")).get(6),false,List.of(6,5));
        Object fl=label(gc,fg,true,List.of(2,4,3)),bl=label(gc,bg,false,List.of(6,5));
        PiecewiseLinearFunction f=(PiecewiseLinearFunction)call(gc,"getForwardJoinExtension",fl);
        PiecewiseLinearFunction b=(PiecewiseLinearFunction)call(gc,"getBackwardJoinExtension",bl);
        double bridge=NgFreshOracle.data.getSetupCost(3,6)-lp.getArcDual(3,6);
        System.out.println("PAIR f45="+f.evaluate(45)+" b69="+b.evaluate(69)+" shift="+bridge+" exact="+PiecewiseLinearFunction.findMinimalShiftedSumValue(f,24,b,bridge));
        dump(fg,45);dump(bg,69);
        Object v=call(gc,"findMinimalShiftedTracedSum",field(fg,"envelope"),24.0,field(bg,"envelope"),bridge);
        System.out.println("ENV bound="+field(v,"reducedCost")+" prune="+call(gc,"canPruneJoinEnvelopeGroupPair",3,fg,bg,lp));
    }
}
