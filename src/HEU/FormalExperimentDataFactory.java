package HEU;

import java.io.IOException;
import java.nio.file.Path;

import Basic.Data;

/** 正式实验只读取已经完整落盘的单个实例文件。 */
public final class FormalExperimentDataFactory {

	private FormalExperimentDataFactory() {
	}

	public static Data loadNoOutsourcing(Path schedulingInstance) throws IOException {
		return new Data(schedulingInstance.toString(), true, true);
	}

	public static Data loadOutsourcing(Path completeInstance) throws IOException {
		Data data = new Data(completeInstance.toString(), true, true);
		if (!data.hasOutsourcingData()) {
			throw new IOException("Outsourcing instance is missing OUTSOURCING_COST or OUTSOURCING_TARIFF: "
					+ completeInstance);
		}
		return data;
	}
}
