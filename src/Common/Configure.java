package Common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import HEU.Solution;

public class Configure {

	public static boolean timeManage=false;//记录组件时间和次数，分析效率
	public static boolean SegmentPool=false;
	// 2026-05-14: 分段线性函数定义域检查开关。默认关闭，避免影响启发式性能；
	// 后续接入 BPC pricing 时可打开，用来确认 label 函数是否始终保持右端到全局 T。
	public static boolean debugPWLFDomainCheck=false;
	// 2026-05-23: 双向 exact pricing 的列生成复核开关。默认关闭，正式运行直接信任
	// label/join 推导出的 reduced cost；调试时打开，用完整序列 evaluator 和分支兼容性做兜底校验。
	public static boolean debugBPCPricingColumnCheck=false;
	// 2026-06-16: 分段数量统计会扫描整条 PWLF 链，默认关闭，避免混入真实求解热路径。
	public static boolean debugPWLFSegmentStats=false;
	// 2026-06-16: HEU 拼接次数统计只用于诊断，默认关闭，避免频繁写 debugMap。
	public static boolean debugAlgorithmCounters=false;
	public Solution bestSolution;
	private final ArrayList<Solution> bestSolutionHistory = new ArrayList<Solution>();
	
	
	public int tpath_number;	//the number of the two-path cuts
	public int ssrow_number;    //the number of the subset-row cuts
	
	public Configure() {
		
	}
	
	public void updateBestSolution(Solution solution) {
		if (solution == null) {
			return;
		}
		bestSolution=solution.copy();
		// 2026-05-21: 记录 ALNS/VND 历史全局 best，用于 BPC root 初始列补充。
		bestSolutionHistory.add(bestSolution.copy());
	}

	public List<Solution> getBestSolutionHistoryCopies() {
		ArrayList<Solution> copies = new ArrayList<Solution>(bestSolutionHistory.size());
		for (Solution solution : bestSolutionHistory) {
			copies.add(solution.copy());
		}
		return Collections.unmodifiableList(copies);
	}
	
}
