package Common;

import java.util.Random;

/**
 * 验证 setup 三角闭包不会把虚拟初始状态 0 当作任务间中转点。
 */
public final class SetupTriangleClosureTest {

    private SetupTriangleClosureTest() { }

    public static void main(String[] args) {
        verifyClosure(ETConverter::enforceDirectedTriangleInequality);
        verifyClosure(SetupRatioVariantGenerator::enforceDirectedTriangleInequality);
        verifyRandomMatrices();
        System.out.println("SetupTriangleClosureTest passed");
    }

    private static void verifyClosure(Closure closure) {
        int[][] setup = {
                { 0, 7, 100, 100 },
                { 0, 0, 50, 100 },
                { 0, 60, 0, 20 },
                { 0, 100, 100, 0 }
        };

        closure.apply(setup);

        require(setup[0][2] == 57, "source-to-job closure was not applied");
        require(setup[0][3] == 77, "source closure did not propagate through real jobs");
        require(setup[1][3] == 70, "job-to-job closure was not applied");
        require(setup[1][2] == 50, "sink shortcut changed job-to-job setup");
        require(setup[2][1] == 60, "zero return arc was used as an intermediate");
        require(setup[3][1] == 100, "zero return arc changed an unrelated setup");
        require(setup[1][0] == 0 && setup[2][0] == 0 && setup[3][0] == 0,
                "terminal arcs must remain zero");
    }

    private static void verifyRandomMatrices() {
        Random random = new Random(20260820L);
        for (int n = 2; n <= 12; n++) {
            for (int sample = 0; sample < 50; sample++) {
                int[][] raw = randomMatrix(n, random);
                int[][] etClosure = copy(raw);
                int[][] ratioClosure = copy(raw);
                int[][] differentTerminal = copy(raw);
                for (int i = 1; i <= n; i++) {
                    differentTerminal[i][0] = 1000 + i;
                }

                ETConverter.enforceDirectedTriangleInequality(etClosure);
                SetupRatioVariantGenerator.enforceDirectedTriangleInequality(ratioClosure);
                ETConverter.enforceDirectedTriangleInequality(differentTerminal);

                for (int i = 0; i <= n; i++) {
                    for (int j = 1; j <= n; j++) {
                        require(etClosure[i][j] == ratioClosure[i][j],
                                "the two setup generators produced different closures");
                        require(etClosure[i][j] == differentTerminal[i][j],
                                "closure depends on terminal arcs to node zero");
                        require(etClosure[i][j] <= raw[i][j], "closure increased a setup time");
                    }
                }
                verifyRequiredTriangleInequality(etClosure, n);
            }
        }
    }

    private static int[][] randomMatrix(int n, Random random) {
        int[][] setup = new int[n + 1][n + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = 1; j <= n; j++) {
                if (i != j) {
                    setup[i][j] = 1 + random.nextInt(100);
                }
            }
        }
        return setup;
    }

    private static int[][] copy(int[][] source) {
        int[][] result = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i].clone();
        }
        return result;
    }

    private static void verifyRequiredTriangleInequality(int[][] setup, int n) {
        for (int i = 0; i <= n; i++) {
            for (int j = 1; j <= n; j++) {
                for (int k = 1; k <= n; k++) {
                    if (i == j || i == k || j == k) {
                        continue;
                    }
                    require(setup[i][k] <= setup[i][j] + setup[j][k],
                            "required setup triangle inequality was violated");
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Closure {
        void apply(int[][] setup);
    }
}
