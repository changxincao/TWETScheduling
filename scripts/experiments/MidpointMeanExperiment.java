package experiments;

/** 仅用于配对实验：逐个子JVM设置开关，不改变正式profile或调度器。 */
public final class MidpointMeanExperiment {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("baseline") || args[0].equals("mean"))) {
            throw new IllegalArgumentException("Expected baseline|mean followed by FormalExperimentRunner args");
        }
        System.setProperty("twet.bpc.midpointPreviousPricing", "false");
        System.setProperty("twet.bpc.midpointFirstRoundMean", Boolean.toString(args[0].equals("mean")));
        Common.formal.FormalExperimentRunner.main(java.util.Arrays.copyOfRange(args, 1, args.length));
    }
}
