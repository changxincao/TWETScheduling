package TWETBPC.GC;

import java.util.ArrayList;
import java.util.List;

/** 缓存身份、cuts快照及失败轮失效的独立回归。 */
public class NgDssrMidpointStartCacheTest {
    public static void main(String[] args) {
        NgDssrMidpointStartCache c = new NgDssrMidpointStartCache();
        Object a = new Object(), b = new Object();
        ArrayList<Integer> cuts = new ArrayList<>(List.of(1));
        check(Double.isNaN(c.lookup(a, cuts)));
        c.remember(a, cuts, 500);
        check(c.lookup(a, cuts) == 500);
        check(Double.isNaN(c.lookup(b, cuts)));
        cuts.add(2);
        check(Double.isNaN(c.lookup(a, cuts)));
        check(c.lookup(a, List.of(1)) == 500);
        c.remember(a, cuts, Double.NaN);
        check(Double.isNaN(c.lookup(a, cuts)));
        c.remember(null, cuts, 500);
        check(Double.isNaN(c.lookup(null, cuts)));
        System.out.println("MidpointStartCache PASS");
    }
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
}
