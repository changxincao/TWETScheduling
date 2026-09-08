package TWETBPC.GC;

import java.util.ArrayList;
import java.util.List;

/** 双池优先级、fallback、筛选边界及节点/cuts隔离回归。 */
public class NgDssrFirstRoundMidpointHistoryTest {
    public static void main(String[] args) {
        NgDssrFirstRoundMidpointHistory h = new NgDssrFirstRoundMidpointHistory();
        Object a = new Object(), b = new Object();
        ArrayList<Integer> cuts = new ArrayList<>(List.of(1));
        var p = h.forNode(a, cuts);
        check(Double.isNaN(p.midpoint()));
        p.record(Double.NaN, 1, 1);
        check(Double.isNaN(p.midpoint()));
        p.record(100, 5, 1);
        p.record(200, 1, 5);
        check(p.midpoint() == 150 && p.source().contains("fallback:all2:qualified0"));
        p.record(300, 4, 1);
        check(p.midpoint() == 300);
        p.record(400, 1, 4);
        p.record(900, 4.00001, 1);
        p.record(900, 0, 0);
        p.record(900, Double.NaN, 1);
        p.record(900, Double.POSITIVE_INFINITY, 1);
        check(p.midpoint() == 350 && p.source().contains("all8:qualified2"));
        check(Double.isNaN(h.forNode(b, cuts).midpoint()));
        check(h.forNode(a, cuts) == p);
        cuts.add(2);
        check(h.forNode(a, List.of(1)) == p);
        check(Double.isNaN(h.forNode(a, cuts).midpoint()));
        check(Double.isNaN(h.forNode(a, List.of(1)).midpoint()));
        System.out.println("NgDssrFirstRoundMidpointHistoryTest passed");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
