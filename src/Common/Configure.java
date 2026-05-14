package Common;

import HEU.Solution;

public class Configure {

	public static boolean timeManage=false;//记录组件时间和次数，分析效率
	public static boolean SegmentPool=false;
	// 2026-05-14: 分段线性函数定义域检查开关。默认关闭，避免影响启发式性能；
	// 后续接入 BPC pricing 时可打开，用来确认 label 函数是否始终保持右端到全局 T。
	public static boolean debugPWLFDomainCheck=false;
	public Solution bestSolution;
	
	
	public int tpath_number;	//the number of the two-path cuts
	public int ssrow_number;    //the number of the subset-row cuts
	
	public Configure() {
		
	}
	
	public void updateBestSolution(Solution solution) {
		bestSolution=solution.copy();
		
	}
	
}
