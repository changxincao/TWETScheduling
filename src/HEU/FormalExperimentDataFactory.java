package HEU;

import java.io.IOException;
import java.nio.file.Path;

import Basic.Data;

/** 正式实验只读取已落盘的调度数据，并按需叠加已落盘的外包经济数据。 */
public final class FormalExperimentDataFactory {

	private FormalExperimentDataFactory() {
	}

	public static Data loadNoOutsourcing(Path schedulingInstance) throws IOException {
		return new Data(schedulingInstance.toString(), true, true);
	}

	public static Data loadOutsourcing(Path schedulingInstance, Path outsourcingOverlay)
			throws IOException {
		if (outsourcingOverlay == null) {
			throw new IllegalArgumentException("Outsourcing experiments require a persisted overlay file");
		}
		return new Data(schedulingInstance.toString(), outsourcingOverlay.toString(), true, true);
	}
}
