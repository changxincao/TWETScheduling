package Common.formal.smoke;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import Basic.Data;
import Common.formal.FormalExperimentDataFactory;

/** 用生产Data读取器逐个装载全部正式调度与外包实例。 */
public final class FormalPersistedInstanceLoadTest {

	private FormalPersistedInstanceLoadTest() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length > 2 || (args.length == 2 && !"--scheduling-only".equals(args[1]))) {
			throw new IllegalArgumentException("Usage: FormalPersistedInstanceLoadTest [suite-root] [--scheduling-only]");
		}
		Path suiteRoot = args.length == 0 ? Path.of("experiment-suite", "formal") : Path.of(args[0]);
		Path instanceRoot = suiteRoot.resolve("instances");
		int scheduling = auditScheduling(instanceRoot);
		int outsourcing = args.length == 2 ? 0 : auditOutsourcing(instanceRoot);
		System.out.printf("FormalPersistedInstanceLoadTest passed: scheduling=%d outsourcing=%d%n",
				Integer.valueOf(scheduling), Integer.valueOf(outsourcing));
	}

	private static int auditScheduling(Path root) throws Exception {
		List<String> lines = Files.readAllLines(root.resolve("instances.tsv"), StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			Path path = root.resolve(fields[11]).normalize();
			Data data = FormalExperimentDataFactory.load(path);
			assertDimensions(data, fields[1], fields[3], path);
			if (data.hasOutsourcingData()) {
				throw new AssertionError("Scheduling instance unexpectedly enables outsourcing: " + path);
			}
		}
		return lines.size() - 1;
	}

	private static int auditOutsourcing(Path root) throws Exception {
		List<String> lines = Files.readAllLines(root.resolve("outsourcing-instances.tsv"),
				StandardCharsets.UTF_8);
		for (int row = 1; row < lines.size(); row++) {
			String[] fields = lines.get(row).split("\\t", -1);
			Path path = root.resolve(fields[12]).normalize();
			Data data = FormalExperimentDataFactory.load(path);
			assertDimensions(data, fields[1], fields[2], path);
			if (!data.hasOutsourcingData()) {
				throw new AssertionError("Outsourcing instance did not enable outsourcing: " + path);
			}
			FormalExperimentDataFactory.validateOutsourcingModel(data, "columns", path);
			FormalExperimentDataFactory.validateOutsourcingModel(data, "masterVariables", path);
		}
		return lines.size() - 1;
	}

	private static void assertDimensions(Data data, String expectedN, String expectedM, Path path) {
		int n = Integer.parseInt(expectedN);
		int m = Integer.parseInt(expectedM);
		if (data.n != n || data.m != m) {
			throw new AssertionError("Dimension mismatch for " + path + ": "
					+ data.n + "/" + data.m + " expected " + n + "/" + m);
		}
	}
}
