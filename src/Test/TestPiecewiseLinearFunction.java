package Test;

import Common.PiecewiseLinearFunctionArray;
import Common.Utility;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Segment;

import java.util.Random;

/**
 * 测试并对比链表版 vs 数组版 分段线性函数：
 *  - 多种场景下 correctness 验证
 *  - 性能基准：mergeMinimum 与 updateDominatedIntervals，重复多次测平均耗时
 */
public class TestPiecewiseLinearFunction {
	//测试基于链表实现和基于数组实现的分段线性函数的正确性和性能
	//目前来看，在merge上基于链表的要快一些，
	//下边是gpt分析的原因，那就暂时先不改了，由于我们估计任务数量也大不了很多，段数不会超过200-300，应该够用了
	
//	大量临时对象分配和拷贝
//	在数组版的 mergeMinimum、updateDominatedIntervals、normalize 之类的方法里，你几乎每次都会：
//
//	分配一个新的 ArrayList<Segment>（甚至两三个），
//
//	把旧的 segs 里的 Segment 引用 copy 到新的列表里（或者反过来），
//
//	在 normalize 里又多一次整段的摘取和重组。
//	这些操作在 5 万 次调用里累积下来，产生了几十万上百万的短生命周期垃圾对象，Java 的垃圾回收器就得花大量时间去回收它们。
//
//	而链表版的 LinkedSegment 操作几乎都是在原来的节点上“拼指针”、“拆节点”，几乎没新的 new Segment(...)，GC 和内存拷贝压力都小得多。
//
//	内存访问和缓存局部性
//
//	数组版用 ArrayList.get(i) 一次又一次地做边界检查、索引偏移，开销不可忽略。
//
//	链表版沿着 .next 指针走，由于链表段数也就几十、一两百条，节点很可能集中在同一两条缓存行里，CPU 预取效果好，反而跑得更快。
//
//	算法细节上的差异
//	你的数组版里，mergeMinimum、updateDominatedIntervals 都是先把整个输出收集到一个新列表 out，最后再 this.segs = out; normalize();。链表版则是 "in-place" 修改，一遍扫描就把节点打好标记或指针拼完，不需要再一次性把所有内容搬来搬去。
//
//	normalize 里 list.clear()+ addAll() 的二次遍历
//	数组版的 normalize 先扫一遍删掉 ∞ 段、再扫一遍合并相同段，然后再一次 merged 拷贝回去。链表版只是一趟剪接指针加一趟压平，工作量小一半。
	

	//此外，对于基于平衡树的操作也不再去尝试了，这种操作感觉上

	
	
    static final int ITERS = 50_0000;
    static final Random rnd = new Random(123);

    public static void main(String[] args) {
        // 1. 构造几种典型场景
        PiecewiseLinearFunction[] fList = new PiecewiseLinearFunction[] {
//            buildDisjointList(),       // 完全不重叠
//            buildFullOverlapList(),    // 完全重叠
//            buildPartialOverlapList(), // 部分重叠，有交点
            buildRandomList(300)        // 随机小规模
        };
        PiecewiseLinearFunctionArray[] aList = new PiecewiseLinearFunctionArray[fList.length];
        for (int i = 0; i < fList.length; i++) {
            aList[i] = toArrayVersion(fList[i]);
        }

        // 2. correctness 验证
        System.out.println("=== Correctness Check ===");
        for (int i = 0; i < fList.length; i++) {
            PiecewiseLinearFunction L = fList[i];
            PiecewiseLinearFunction R = buildShiftedList(L, 0.5); // 简单偏移测试
            PiecewiseLinearFunction mergedL = L.copy(); mergedL.mergeMinimum(R, PiecewiseLinearFunction.Direction.FORWARD);
            PiecewiseLinearFunctionArray A = aList[i];
            PiecewiseLinearFunctionArray B = toArrayVersion(R);
            PiecewiseLinearFunctionArray mergedA = A.copy(); mergedA.mergeMinimum(B);

            System.out.printf("Scenario %d:\n  list:   %s\n  array:  %s\n",
                i,
                trimmed(mergedL.toString()),
                trimmed(mergedA.toString()));
            assert mergedL.toString().equals(mergedA.toString()) : "MergeMismatch!";
        }

        // 3. 性能测试
        System.out.println("\n=== Performance Benchmark ===");
        for (int i = 0; i < fList.length; i++) {
        	int numS=0;
        	for(Segment p=fList[i].head;p!=null;p=p.next) numS++;
            System.out.printf("Scenario %d (size=%d segments):\n", i,numS );
            benchmarkMerge(fList[i], aList[i]);
            benchmarkUpdateDom(fList[i], aList[i]);
            System.out.println();
        }
    }

    // ============ 构造函数 ============

    // 场景1：完全不重叠
    static PiecewiseLinearFunction buildDisjointList() {
        PiecewiseLinearFunction L = new PiecewiseLinearFunction(0, 10);
        for (int i = 0; i < 5; i++) {
            L.addSegment(i*2, i*2+1, 1.0 + i, 0.5*i);
        }
        return L;
    }
    // 场景2：完全重叠
    static PiecewiseLinearFunction buildFullOverlapList() {
        PiecewiseLinearFunction L = new PiecewiseLinearFunction(0, 5);
        L.addSegment(0, 5, 2.0, 1.0);
        return L;
    }
    // 场景3：部分重叠，有交点
    static PiecewiseLinearFunction buildPartialOverlapList() {
        PiecewiseLinearFunction L = new PiecewiseLinearFunction(0, 8);
        L.addSegment(0, 3,  1.0, 0.0);
        L.addSegment(3, 6, -0.5, 5.0);
        L.addSegment(6, 8,  2.0, -4.0);
        return L;
    }
    // 随机生成 n 段
    static PiecewiseLinearFunction buildRandomList(int n) {
        PiecewiseLinearFunction L = new PiecewiseLinearFunction(0, n);
        double x = 0;
        for (int i = 0; i < n; i++) {
            double w = 0.5 + rnd.nextDouble();
            double a = rnd.nextDouble()*4 - 2;
            double b = rnd.nextDouble()*3;
            L.addSegment(x, x+w, a, b);
            x += w;
        }
        return L;
    }
    // 渐进偏移，生成第二个函数
    static PiecewiseLinearFunction buildShiftedList(PiecewiseLinearFunction src, double delta) {
        return src.shiftX(delta);
    }

    // 链表版 → 数组版
    static PiecewiseLinearFunctionArray toArrayVersion(PiecewiseLinearFunction L) {
        PiecewiseLinearFunctionArray A = new PiecewiseLinearFunctionArray(L.domainStart, L.domainEnd);
        for (PiecewiseLinearFunction.Segment s=L.head;s!=null;s=s.next) {
            A.addSegment(s.start, s.end, s.slope, s.intercept);
        }
        return A;
    }

    // ============ Benchmark ============
    static void benchmarkMerge(PiecewiseLinearFunction L, PiecewiseLinearFunctionArray A) {
        PiecewiseLinearFunction R1 = buildShiftedList(L, 0.123);
        PiecewiseLinearFunctionArray A1 = toArrayVersion(R1);

        // 链表版
        long tList=0;
        for (int k = 0; k < ITERS; k++) {
            PiecewiseLinearFunction tmp = L.copy();
            long t0 = System.nanoTime();
            tmp.mergeMinimum(R1, PiecewiseLinearFunction.Direction.FORWARD);
            long t1 = System.nanoTime();
            tList+=(t1-t0);
        }
        

        // 数组版
        long tArr = 0;
        for (int k = 0; k < ITERS; k++) {
            PiecewiseLinearFunctionArray tmp = A.copy();
            long t0 = System.nanoTime();
            tmp.mergeMinimum(A1);
            long t1 = System.nanoTime();
            tArr+=(t1-t0);
        }
        

        System.out.printf("  merge: list=%,d ms, array=%,d ms%n",
            tList/1_000_000, tArr/1_000_000);
    }

    static void benchmarkUpdateDom(PiecewiseLinearFunction L, PiecewiseLinearFunctionArray A) {
        PiecewiseLinearFunction R1 = buildShiftedList(L, 0.123);
        PiecewiseLinearFunctionArray A1 = toArrayVersion(R1);

        // 链表版
        long t0 = System.nanoTime();
        for (int k = 0; k < ITERS; k++) {
            PiecewiseLinearFunction tmp = L.copy();
            tmp.updateDominatedIntervals(R1, PiecewiseLinearFunction.Direction.FORWARD);
        }
        long tList = System.nanoTime() - t0;

        // 数组版
        t0 = System.nanoTime();
        for (int k = 0; k < ITERS; k++) {
            PiecewiseLinearFunctionArray tmp = A.copy();
            tmp.updateDominatedIntervals(A1);
        }
        long tArr = System.nanoTime() - t0;

        System.out.printf("  updateDom: list=%,d ms, array=%,d ms%n",
            tList/1_000_000, tArr/1_000_000);
    }

    // 简短输出：只保留头尾 100 字符
    static String trimmed(String s) {
        if (s.length() < 200) return s;
        return s.substring(0, 80) + " … " + s.substring(s.length()-80);
    }
}
