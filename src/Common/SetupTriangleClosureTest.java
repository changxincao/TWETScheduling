package Common;

/**
 * 验证 setup 三角闭包不会把虚拟初始状态 0 当作任务间中转点。
 */
public final class SetupTriangleClosureTest {

    private SetupTriangleClosureTest() { }

    public static void main(String[] args) {
        verifyClosure(ETConverter::enforceDirectedTriangleInequality);
        verifyClosure(SetupRatioVariantGenerator::enforceDirectedTriangleInequality);
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
