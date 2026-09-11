package Common.formal;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import Basic.Data;
import HEU.TanakaNoOutsourcingBPCTest;
import TWETBPC.TWETBPCConfig;
import TWETBPC.BP.StructuredArcFlowBrancher;
import TWETBPC.BP.StructuredArcFlowBrancher.ClusterPartitionDiagnostics;

/** 不启动求解器，批量导出setup-only MST partition的静态质量指标。 */
public final class ClusterPartitionDiagnosticsRunner {

	private ClusterPartitionDiagnosticsRunner() {
	}

	public static void main(String[] args) throws Exception {
		Arguments arguments = Arguments.parse(args);
		ArrayList<Path> instances = new ArrayList<Path>(arguments.instances);
		instances.sort(Comparator.comparing(Path::toString));
		Path parent = arguments.output.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (BufferedWriter writer = Files.newBufferedWriter(arguments.output)) {
			writer.write("instance\tn\tm\ttheta\tclusterCount\tclusterSizes\tnontrivialClusterCount\t"
					+ "setupOnlySilhouette\tmaximumClusterShare\tsingletonJobShare\t"
					+ "meanIntraClusterSetup\tmeanInterClusterSetup\tinterIntraSetupRatio\tmstCutThreshold\n");
			for (Path instance : instances) {
				Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(instance.toString(), false);
				TWETBPCConfig config = new TWETBPCConfig();
				config.enableClusterBranching = true;
				config.clusterTemporalWeight = 0.0;
				config.clusterMstTheta = arguments.theta;
				ClusterPartitionDiagnostics diagnostics =
						new StructuredArcFlowBrancher(data, config).getClusterDiagnostics();
				writer.write(instance.toString().replace('\\', '/'));
				writer.write(String.format(Locale.US,
						"\t%d\t%d\t%.6f\t%d\t%s\t%d\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\n",
						data.n, data.m, arguments.theta, diagnostics.getClusterCount(),
						diagnostics.getClusterSizes(), diagnostics.getNontrivialClusterCount(),
						diagnostics.getSetupOnlySilhouette(), diagnostics.getMaximumClusterShare(),
						diagnostics.getSingletonJobShare(), diagnostics.getMeanIntraClusterSetup(),
						diagnostics.getMeanInterClusterSetup(), diagnostics.getInterIntraSetupRatio(),
						diagnostics.getMstCutThreshold()));
			}
		}
		System.out.println("Wrote " + instances.size() + " cluster diagnostic rows to " + arguments.output);
	}

	private static final class Arguments {
		final Path output;
		final double theta;
		final List<Path> instances;

		Arguments(Path output, double theta, List<Path> instances) {
			this.output = output;
			this.theta = theta;
			this.instances = instances;
		}

		static Arguments parse(String[] args) {
			Path output = null;
			double theta = 0.5;
			ArrayList<Path> instances = new ArrayList<Path>();
			for (String arg : args) {
				if (arg.startsWith("--output=")) {
					output = Path.of(arg.substring("--output=".length()));
				} else if (arg.startsWith("--theta=")) {
					theta = Double.parseDouble(arg.substring("--theta=".length()));
				} else {
					instances.add(Path.of(arg));
				}
			}
			if (output == null || instances.isEmpty()) {
				throw new IllegalArgumentException(
						"Usage: ClusterPartitionDiagnosticsRunner --output=<tsv> [--theta=0.5] <instance.dat>...");
			}
			return new Arguments(output, theta, instances);
		}
	}
}
