package Test;

import Common.PiecewiseLinearFunction;

public class MergeBenchmark {
    // 老版 merge（链表+拆分，不复用）—— 请替换成你现有的 merge 方法名
    public static void oldMerge(PiecewiseLinearFunction f, PiecewiseLinearFunction g) {
        f.mergeMinimum(g, PiecewiseLinearFunction.Direction.FORWARD);
    }
    // 新版 merge（双指针+复用）
    public static void newMerge(PiecewiseLinearFunction f, PiecewiseLinearFunction g) {
       f.mergeMinimum2(g);
    }

    // 打印一个 PLF
    public static void printPLF(PiecewiseLinearFunction f) {
        for (PiecewiseLinearFunction.Segment s = f.head; s != null; s = s.next) {
            System.out.printf("[%.1f, %.1f): %.2f·t + %.2f\n",
                s.start, s.end, s.slope, s.intercept);
        }
    }

    // 构造一个简单的 PLF：给定数组 {start,end,slope,intercept,...}
    public static PiecewiseLinearFunction build(double[] segs) {
        PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, Double.POSITIVE_INFINITY);
        for (int i = 0; i < segs.length; i += 4) {
            f.addSegment(segs[i], segs[i+1], segs[i+2], segs[i+3]);
        }
        return f;
    }

    public static void main(String[] args) {
        // 几组测试案例
        double[][] casesF = {
            // Case1
            {0, 2, 1, 0,   2, 5, 0, 2},
            // Case2
            {0, 3, 0.5, 1,  3, 6, -1, 7},
            // Case3：单调上升 vs 单调下降
            {0, 4, 2, 0}, {0, 4, -1, 5}
        };
        double[][] casesG = {
            // Case1
            {0, 1, 0, 1,   1, 4, -0.5, 2},
            // Case2
            {1, 4, 1, 0,   4, 7, 0.2, 0},
            // Case3 配对第二个
        };

        // 对比输出
        System.out.println("===== 合并结果对比 =====");
        for (int i = 1; i < 2; i++) {
            PiecewiseLinearFunction f = build(casesF[i]);
            PiecewiseLinearFunction g = build(casesG[i]);
            System.out.printf("Case %d:\n", i+1);
            System.out.println("  f:");
            printPLF(f);
            System.out.println("  g:");
            printPLF(g);

            System.out.println("  oldMerge(f,g):");
            PiecewiseLinearFunction fc=f.copy();
             oldMerge(fc, g.copy());
            printPLF(fc);

            System.out.println("  newMerge(f,g):");
            fc=f.copy();
            newMerge(fc, g.copy());
            printPLF(fc);
            System.out.println();
        }

        // 单独 Case3：两段完全重合或相反
        PiecewiseLinearFunction f3 = build(casesF[2]);
        PiecewiseLinearFunction g3 = build(casesF[3]);
        System.out.println("Case 3:");
        System.out.println("  oldMerge:");
        PiecewiseLinearFunction f3c=f3.copy();
        oldMerge(f3c, g3.copy());
        printPLF(f3c);
        System.out.println("  newMerge:");
        f3c=f3.copy();
        newMerge(f3c, g3.copy());
        printPLF(f3c);
        System.out.println();

        // ===== 基准测试 =====
        final int ITERS = 200_000_000;
        // 取 Case1 的 f,g 作为大循环基准
        PiecewiseLinearFunction fb = build(casesF[0]);
        PiecewiseLinearFunction gb = build(casesG[0]);

        // 预热
        for (int i = 0; i < 1000; i++) {
            oldMerge(fb.copy(), gb.copy());
            newMerge(fb.copy(), gb.copy());
        }

        long t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            oldMerge(fb.copy(), gb.copy());
        }
        long tOld = System.nanoTime() - t0;

        t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            newMerge(fb.copy(), gb.copy());
        }
        long tNew = System.nanoTime() - t0;

        System.out.printf("Benchmark (%d iters):\n", ITERS);
        System.out.printf("  oldMerge: %.3f ms\n", tOld / 1e6);
        System.out.printf("  newMerge: %.3f ms\n", tNew / 1e6);
    }
}
