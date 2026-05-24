package HEU;

import java.util.*;

import Basic.Data;
import Common.Utility;
import Common.Utility.TimerManager;

public class EngineVND {

	public Data data;
	public Solution s;
	

	public EngineVND(Data data, Solution s) {
		this.data = data;
		this.s = s;
		Utility.resetCurUpperBound(s.curCost);
		for(int m=0;m<data.m;m++) {
			s.updateInformationM(m);//重刷一次
		}
		
	}

	/** 生成所有 operator （依次执行 VND） */
	private List<Move> buildOps() {
		List<Move> ops = new ArrayList<>();
		// 0 IOPT
		// 1 2-opt
		// 2 2-opt*
		// 3 path-insertion
		// 4 cross exchange
		ops.add(new IOptOperator(s));
		ops.add(new TwoOptOperator(s));
		ops.add(new TwoOptStarOperator(s));
		ops.add(new PathInsertOperator(s));
		ops.add(new CrossExchangeOperator(s));
		ops.add(new OutsourcingPathInsertOperator(s));
		ops.add(new OutsourcingCrossExchangeOperator(s));
		
		Collections.shuffle(ops,new Random(1));
		return ops;
	}

	public void search() {
//		TimerManager.start("local_search");
		// 局部搜索就是找一个局部最小不存在最大未提高次数，这种是针对有跳出或者说接受差解行为的
		// 如果不限制可能无法停止
		
		int iter = 0;
		
		boolean improved = true;
		while (improved) {
			
			improved = false;
			List<Move> ops = buildOps();
			
			for (Move move:ops) {
				
				move.searchBest();
				improved = improved || commit(move);
				iter++;
					// TODO 时间监测
					
				if (improved)
					break;
				}

				
			}
//		TimerManager.end("local_search");
		}
	
	public boolean commit(Move move) {
//		System.out.println("commit ");
		TimerManager.start("commit");
		boolean imp=move.commit();
		if(imp&&Utility.compareLt(s.curCost,data.configure.bestSolution.curCost )) {
//			System.out.println("commit 更新最优解");
			data.configure.updateBestSolution(s);
		
		}
		if(imp) {
			//有改进，则更新当前局部上界
			Utility.updateCurUpperBound(s.curCost);
		}
		TimerManager.end("commit");
		return imp;
	}

	}

