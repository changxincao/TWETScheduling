import java.util.Arrays;

import Common.formal.FormalExperimentRunner;

/** 实验入口在NG类初始化前固定聚合模式；正式求解入口和profile保持不变。 */
public final class NgJoinSetExperimentRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("OR") || args[0].equals("SET"))) {
            throw new IllegalArgumentException("Expected OR or SET, followed by formal solve arguments");
        }
        System.setProperty("twet.bpc.ngDssrJoinBitmapUseSet", Boolean.toString(args[0].equals("SET")));
        System.setProperty("twet.bpc.ngDssrJoinBitmapCompare", "false");
        System.setProperty("twet.bpc.ngDssrJoinPhaseTiming", "true");
        System.out.println("[NG join bitmap strategy] mode=" + args[0] + " comparison=false phaseTiming=true");
        FormalExperimentRunner.main(Arrays.copyOfRange(args, 1, args.length));
    }
}
