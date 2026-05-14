package Test;

import Common.PiecewiseLinearFunction;

public class PiecewiseLinearFunctionDemo {
    public static void main(String[] args) {
        // 1. 测试 dominates（完全重叠域）
        PiecewiseLinearFunction f1 = new PiecewiseLinearFunction();
        f1.addSegment(0, 10, 1, 0); // f1(t) = t on [0,10)
        PiecewiseLinearFunction g1 = new PiecewiseLinearFunction();
        g1.addSegment(0, 10, 1, 1); // g1(t) = t + 1 on [0,10)
        System.out.println("--- dominates: 完全重叠域 ---");
        System.out.println("f1.dominates(g1): " + f1.dominates(g1));
        System.out.println("g1.dominates(f1): " + g1.dominates(f1));

        // 2. 测试 dominates（不完全重叠域）
        PiecewiseLinearFunction f2 = new PiecewiseLinearFunction();
        f2.addSegment(0, 5, 1, 0);  // f2 defined on [0,5)
        PiecewiseLinearFunction g2 = new PiecewiseLinearFunction();
        g2.addSegment(2, 7, 0.5, 1); // g2 defined on [2,7)
        System.out.println("--- dominates: 部分重叠域 ---");
        System.out.println("f2.dominates(g2): " + f2.dominates(g2));
        System.out.println("g2.dominates(f2): " + g2.dominates(f2));

        // 3. 测试 updateDominatedIntervals（完全支配）
        PiecewiseLinearFunction f3 = new PiecewiseLinearFunction();
        f3.addSegment(0, 10, 1, 0); // f3(t) = t
        PiecewiseLinearFunction g3 = new PiecewiseLinearFunction();
        g3.addSegment(0, 10, 1, 1); // g3(t) = t + 1
        boolean emptied3 = g3.updateDominatedIntervals(f3);
        System.out.println("--- updateDominatedIntervals: 完全支配 ---");
        System.out.println("g3 emptied: " + emptied3 + ", isEmpty: " + g3.isEmpty());

        // 4. 测试 updateDominatedIntervals（部分支配）
        PiecewiseLinearFunction f4 = new PiecewiseLinearFunction();
        f4.addSegment(0, 5, 0, 2);  // f4=2 constant on [0,5)
        PiecewiseLinearFunction g4 = new PiecewiseLinearFunction();
        g4.addSegment(0, 5, 0, 5);  // g4=5 constant on [0,5)
        f4.addSegment(5, 10, 1, 0); // f4(t)=t on [5,10)
        g4.addSegment(5, 10, 2, 0); // g4(t)=2t on [5,10)
        boolean emptied4 = g4.updateDominatedIntervals(f4);
        System.out.println("--- updateDominatedIntervals: 部分支配 ---");
        System.out.println("g4 emptied: " + emptied4 + ", g4 after update:");
        System.out.println(g4);

        // 5. 测试 mergeMinimum（完全并集无重叠）
        PiecewiseLinearFunction f5 = new PiecewiseLinearFunction();
        f5.addSegment(0, 6, 1, 0);  // f5: [0,6)
        PiecewiseLinearFunction g5 = new PiecewiseLinearFunction();
        g5.addSegment(5, 8, -1, 10); // g5: [5,8)
        PiecewiseLinearFunction f5c=f5.copy();
        PiecewiseLinearFunction g5c=g5.copy();
        f5c.mergeMinimum(g5.copy());
        g5c.mergeMinimum(f5.copy());
        System.out.println("--- mergeMinimum: 完全不重叠域 ---");
        System.out.println(f5);

        // 6. 测试 mergeMinimum（部分重叠、双段）
        PiecewiseLinearFunction f6 = new PiecewiseLinearFunction();
        f6.addSegment(0, 5, 1, 0);    // [0,5): f6(t)=t
        f6.addSegment(5, 10, 0, 7);   // [5,10): f6=7
        PiecewiseLinearFunction g6 = new PiecewiseLinearFunction();
        g6.addSegment(2, 4, 3, 7);    // [3,8): g6=5
        
        PiecewiseLinearFunction f6c=f6.copy();
        PiecewiseLinearFunction g6c=g6.copy();
        f6c.mergeMinimum(g6.copy());
        g6c.mergeMinimum(f6.copy());
        System.out.println("--- mergeMinimum: 部分重叠、多段 ---");
        System.out.println(f6);
    }
}