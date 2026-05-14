package Output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 结果输出路径工具。
 */
public final class ResultPathUtil {

	private ResultPathUtil() {
	}

	public static String instanceStem(String instanceName) {
		int dot = instanceName.lastIndexOf('.');
		return dot < 0 ? instanceName : instanceName.substring(0, dot);
	}

	public static Path prepareMethodDir(Path outputRoot, String methodName) throws IOException {
		Path dir = outputRoot.resolve(methodName);
		Files.createDirectories(dir);
		return dir;
	}

}
