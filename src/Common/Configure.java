package Common;

import HEU.Solution;

public class Configure {

	public static boolean timeManage=false;//记录组件时间和次数，分析效率
	public static boolean SegmentPool=false;
	public Solution bestSolution;
	
	
	public int tpath_number;	//the number of the two-path cuts
	public int ssrow_number;    //the number of the subset-row cuts
	
	public Configure() {
		
	}
	
	public void updateBestSolution(Solution solution) {
		bestSolution=solution.copy();
		
	}
	
}
