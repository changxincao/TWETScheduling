package Common.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import Common.formal.FormalTimeScaleGenerator.Scale;
import Common.formal.FormalTimeScaleGenerator.ScaledData;
import Common.formal.FormalWindowGenerator.Window;

/** 将一个已生成的任务/setup/尺度组合写成不同机器数共享数据的.dat文件。 */
public final class FormalInstanceWriter {

	public Path write(Path outputRoot, FormalTaskSet taskSet, FormalSetupGenerator.Type setupType,
			Scale scale, Window window, int machines, ScaledData data) throws IOException {
		Path directory = outputRoot.resolve("data").resolve(taskSet.id())
				.resolve(setupType.id()).resolve(scale.directoryName()).resolve(window.level());
		Files.createDirectories(directory);
		Path output = directory.resolve("m" + machines + ".dat");
		ArrayList<String> lines = new ArrayList<String>(2 * taskSet.size() + 3);
		lines.add(taskSet.size() + " " + machines);
		for (int job = 1; job <= taskSet.size(); job++) {
			int early = Math.max(0, data.centers()[job] - window.halfWidth(job));
			int late = Math.addExact(data.centers()[job], window.halfWidth(job));
			lines.add(data.processing()[job] + " " + early + " " + late + " "
					+ data.earlyWeights()[job] + " " + data.tardyWeights()[job]);
		}
		lines.add("SETUP");
		for (int from = 0; from <= taskSet.size(); from++) {
			StringBuilder row = new StringBuilder();
			for (int to = 0; to <= taskSet.size(); to++) {
				if (to > 0) {
					row.append(' ');
				}
				row.append(data.setup()[from][to]);
			}
			lines.add(row.toString());
		}
		lines.add("SETUP_COST");
		for (int from = 0; from <= taskSet.size(); from++) {
			StringBuilder row = new StringBuilder();
			for (int to = 0; to <= taskSet.size(); to++) {
				if (to > 0) {
					row.append(' ');
				}
				double cost = from == to ? 0.0
						: FormalExperimentDesign.SETUP_COST_COEFFICIENT * data.setup()[from][to];
				row.append(String.format(java.util.Locale.ROOT, "%.1f", cost));
			}
			lines.add(row.toString());
		}
		Files.write(output, lines, StandardCharsets.UTF_8);
		return output;
	}
}
