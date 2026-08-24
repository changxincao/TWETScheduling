package HEU;

import java.nio.file.Path;
import java.util.List;

/**
 * 正式实验批处理的 Eclipse 直接启动入口。
 * <p>
 * 无命令行参数时使用本类顶部的配置；传入参数时完全沿用
 * {@link ExperimentBatchScheduler} 的命令行语法。
 */
public final class FormalExperimentBatchLauncher {

	private static final Path MANIFEST = Path.of("experiment-suite", "formal", "manifests",
			"pricing-comparison.tsv");
	private static final int MAX_PARALLEL = 4;
	private static final boolean SCAN_ONLY = true;
	private static final List<String> SELECTORS = List.of(
			"size=50",
			"setupType=family",
			"scaleLevel=base",
			"windowLevel=narrow",
			"algorithm=NG_DSSR,TIME_INDEXED,TIME_INDEXED_SRI");

	/* 在新服务器上只需修改这一项；cplex.jar 仍由项目 classpath 提供。 */
	private static final String CPLEX_NATIVE_LIBRARY_PATH =
			"D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64";

	private FormalExperimentBatchLauncher() {
	}

	public static void main(String[] args) throws Exception {
		if (args != null && args.length > 0) {
			ExperimentBatchScheduler.main(args);
			return;
		}
		configureCplexNativeLibraryPath();
		new ExperimentBatchScheduler(MAX_PARALLEL).run(MANIFEST, SELECTORS, SCAN_ONLY);
	}

	private static void configureCplexNativeLibraryPath() {
		if (!CPLEX_NATIVE_LIBRARY_PATH.isBlank()) {
			System.setProperty("java.library.path", Path.of(CPLEX_NATIVE_LIBRARY_PATH)
					.toAbsolutePath().normalize().toString());
		}
	}
}
