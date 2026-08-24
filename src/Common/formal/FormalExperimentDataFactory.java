package Common.formal;

import java.io.IOException;
import java.nio.file.Path;

import Basic.Data;

/** 正式实验只读取已经完整落盘的单个实例文件。 */
public final class FormalExperimentDataFactory {

	private FormalExperimentDataFactory() {
	}

	public static Data load(Path instance) throws IOException {
		return new Data(instance.toString(), true, true);
	}

	/** 文件决定是否允许外包；参数只在外包文件上选择SP1或SP2。 */
	public static void validateOutsourcingModel(Data data, String outsourcingModel, Path instance) {
		boolean unspecified = outsourcingModel == null || outsourcingModel.isBlank();
		boolean columns = "columns".equalsIgnoreCase(outsourcingModel);
		boolean masterVariables = "masterVariables".equalsIgnoreCase(outsourcingModel);
		if (data.hasOutsourcingData() && !columns && !masterVariables) {
			throw new IllegalArgumentException("Outsourcing instance requires columns or masterVariables: "
					+ instance);
		}
		if (!data.hasOutsourcingData() && !unspecified) {
			throw new IllegalArgumentException("Scheduling-only instance must not specify outsourcingModel: "
					+ instance);
		}
	}
}
