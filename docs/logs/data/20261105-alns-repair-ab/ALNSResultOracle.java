package HEU;

import Basic.Data;
import Common.formal.FormalExperimentDataFactory;
import java.nio.file.Path;
import java.util.ArrayList;

/** 对保存的n60最终路线作独立整数时间DP复核，输入路线用斜线分机、逗号分任务。 */
public final class ALNSResultOracle {
    public static void main(String[] args) throws Exception {
        Data d = FormalExperimentDataFactory.load(Path.of(args[0]));
        double total = 0;
        int[] visits = new int[d.n+1];
        String[] routes = args[1].split("/", -1);
        if (routes.length != d.m || d.hasOutsourcingData()) throw new AssertionError("Wrong model");
        for (int j = 1; j <= d.n; j++) {
            if (d.p[j] != Math.rint(d.p[j]) || d.d_e[j] != Math.rint(d.d_e[j])
                    || d.d_l[j] != Math.rint(d.d_l[j])) throw new AssertionError("Noninteger time");
            for (int i = 0; i <= d.n; i++)
                if (d.s[i][j] != Math.rint(d.s[i][j])) throw new AssertionError("Noninteger setup");
        }
        for (String route : routes) {
            ArrayList<Integer> seq = new ArrayList<>();
            if (!route.isEmpty()) for (String item : route.split(",")) {
                int job = Integer.parseInt(item);
                visits[job]++;
                seq.add(job);
            }
            total += ALNSRepairRegressionTest.dp(d, seq);
        }
        for (int j = 1; j <= d.n; j++) if (visits[j] != 1) throw new AssertionError("Coverage " + j);
        double expected = Double.parseDouble(args[2]);
        if (!Double.isFinite(total) || Math.abs(total - expected) > 1e-5)
            throw new AssertionError("Oracle=" + total + ", reported=" + expected);
        System.out.println("ORACLE_OK total=" + total + " horizon=" + d.CmaxH);
    }
}
