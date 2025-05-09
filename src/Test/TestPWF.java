package Test;

import java.util.List;

import Common.PiecewiseLinearFunction;
import Common.PiecewisePlotter;

public class TestPWF {
    public static void main(String[] args) {
//        testCompleteDominance();    // 测试 1: 完全支配
//        testPartialDominance();     // 测试 2: 部分支配
//        testIntersection();         // 测试 3: 存在交点
//        testNoCommonDomain();       // 测试 4: 无公共定义域
//        testEmptyFunction();        // 测试 5: 空函数
//        testSingleSegment();        // 测试 6: 单段函数
//        testMultiSegment();         // 测试 7: 多段函数
    	PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, 3);
    	f.addSegment(0, 1, -1, 2);
    	f.addSegment(1, 3, +1, 0);
    	PiecewiseLinearFunction fc=f.copy();
    	// 把你的 reverse‐prefix‐min 代码跑一遍
    	PiecewiseLinearFunction fs=f.copy();
    	f.minimizeSuffixInPlace();
    	fs.minimizePrefixInPlace();
    	List<PiecewiseLinearFunction> list=List.of(fc, f,fs);
    	PiecewisePlotter.plotAndSave(list);
    }

    // 测试 1: 完全支配
    private static void testCompleteDominance() {
        PiecewiseLinearFunction L1 = new PiecewiseLinearFunction();
        L1.addSegment(0, 10, 0, 5); // L1(t) = 5, t in [0,10)

        PiecewiseLinearFunction L2 = new PiecewiseLinearFunction();
        L2.addSegment(0, 10, 0, 10); // L2(t) = 10, t in [0,10)

        L2.updateDominatedIntervals(L1);
        System.out.println("测试 1 - 完全支配:");
        System.out.println("预期: [0.000,10.000): 0.000·t+5.000");
        System.out.println("实际: " + L2.toString());
        System.out.println();
    }

    // 测试 2: 部分支配
    private static void testPartialDominance() {
        PiecewiseLinearFunction L1 = new PiecewiseLinearFunction();
        L1.addSegment(0, 5, 0, 5);   // L1(t) = 5, t in [0,5)
        L1.addSegment(5, 10, 0, 15); // L1(t) = 15, t in [5,10)

        PiecewiseLinearFunction L2 = new PiecewiseLinearFunction();
        L2.addSegment(0, 10, 0, 10); // L2(t) = 10, t in [0,10)

        L2.updateDominatedIntervals(L1);
        System.out.println("测试 2 - 部分支配:");
        System.out.println("预期: [0.000,5.000): 0.000·t+5.000, [5.000,10.000): 0.000·t+10.000");
        System.out.println("实际: " + L2.toString());
        System.out.println();
    }

    // 测试 3: 存在交点
    private static void testIntersection() {
        PiecewiseLinearFunction L1 = new PiecewiseLinearFunction();
        L1.addSegment(0, 10, 1, 0); // L1(t) = t, t in [0,10)

        PiecewiseLinearFunction L2 = new PiecewiseLinearFunction();
        L2.addSegment(0, 10, 0, 5); // L2(t) = 5, t in [0,10)

        L2.updateDominatedIntervals(L1);
        System.out.println("测试 3 - 存在交点:");
        System.out.println("预期: [0.000,5.000): 0.000·t+∞, [5.000,10.000): 0.000·t+5.000");
        System.out.println("实际: " + L2.toString());
        System.out.println();
    }

    // 测试 4: 无公共定义域
    private static void testNoCommonDomain() {
        PiecewiseLinearFunction L1 = new PiecewiseLinearFunction();
        L1.addSegment(0, 5, 0, 5); // L1(t) = 5, t in [0,5)

        PiecewiseLinearFunction L2 = new PiecewiseLinearFunction();
        L2.addSegment(5, 10, 0, 10); // L2(t) = 10, t in [5,10)

        L2.updateDominatedIntervals(L1);
        System.out.println("测试 4 - 无公共定义域:");
        System.out.println("预期: [5.000,10.000): 0.000·t+10.000");
        System.out.println("实际: " + L2.toString());
        System.out.println();
    }

    // 测试 5: 空函数
    private static void testEmptyFunction() {
        PiecewiseLinearFunction L1 = new PiecewiseLinearFunction(); // L1 为空
        PiecewiseLinearFunction L2 = new PiecewiseLinearFunction();
        L2.addSegment(0, 10, 0, 10); // L2(t) = 10, t in [0,10)

        L2.updateDominatedIntervals(L1);
        System.out.println("测试 5 - 空函数 (L1 为空):");
        System.out.println("预期: [0.000,10.000): 0.000·t+10.000");
        System.out.println("实际: " + L2.toString());
        System.out.println();

        L1.addSegment(0, 10, 0, 5); // L1(t) = 5, t in [0,10)
        L2 = new PiecewiseLinearFunction(); // L2 为空
        L2.updateDominatedIntervals(L1);
        System.out.println("测试 5 - 空函数 (L2 为空):");
        System.out.println("预期: [0.000,10.000): 0.000·t+5.000");
        System.out.println("实际: " + L2.toString());
        System.out.println();
    }

    // 测试 6: 单段函数
    private static void testSingleSegment() {
        PiecewiseLinearFunction L1 = new PiecewiseLinearFunction();
        L1.addSegment(0, 10, 0, 5); // L1(t) = 5, t in [0,10)

        PiecewiseLinearFunction L2 = new PiecewiseLinearFunction();
        L2.addSegment(0, 10, 0, 10); // L2(t) = 10, t in [0,10)

        L2.updateDominatedIntervals(L1);
        System.out.println("测试 6 - 单段函数:");
        System.out.println("预期: [0.000,10.000): 0.000·t+5.000");
        System.out.println("实际: " + L2.toString());
        System.out.println();
    }

    // 测试 7: 多段函数
    private static void testMultiSegment() {
        PiecewiseLinearFunction L1 = new PiecewiseLinearFunction();
        L1.addSegment(0, 3, 0, 5);   // L1(t) = 5, t in [0,3)
        L1.addSegment(3, 7, 1, 0);   // L1(t) = t, t in [3,7)
        L1.addSegment(7, 10, 0, 10); // L1(t) = 10, t in [7,10)

        PiecewiseLinearFunction L2 = new PiecewiseLinearFunction();
        L2.addSegment(0, 5, 0, 10);  // L2(t) = 10, t in [0,5)
        L2.addSegment(5, 10, 0, 15); // L2(t) = 15, t in [5,10)

        L2.mergeMinimum(L1);
          System.out.println("测试 7 - 多段函数:");
        System.out.println("预期: [0.000,3.000): 0.000·t+5.000, [3.000,5.000): 0.000·t+10.000, [5.000,10.000): 0.000·t+15.000");
        System.out.println("实际: " + L2.toString());
        System.out.println();
    }
}