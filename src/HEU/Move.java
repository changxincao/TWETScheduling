package HEU;
//TODO 对那些序列反转，单任务的就不转了
//TODO 统计PiecewiseLinearFunction中每个函数的调用时间，尝试优化 √
//TODO 简单测试noImproved被触发次数应该很少很少，此外，里边存的元素似乎也不会很多,saveOperation也不会有很多个 √
//TODO 全局上界（单个机器）bound的使用，下界的提前跳出（作用不一定大）、上界的使用包括函数的段删减和add时先判断是否成本超限 √ 作用不是很大
//TODO 给定一个顺序后的最优完工时间是一个下界,此时有一个最小成本值 ?
//TODO 全局上界应该好做？把现在的remainingValue从无穷换成全局上界好了，但这个值怎么传进来没想好 √ 作用一般
//TODO 2025.5.10 看起来设置一个全局上界对函数切割可行域，有些能切割的挺多的，直接不可行了 但细节上还得处理，不知道作用大不大最终 √ 时间上界还是很有用的，但成本就一般了
//TODO 现在只做了单个机器上的上界，整体的上界？?
//对这种其实不好做，单个机器爆上界可设置为upperBound认为不可行，但多个机器都可行，加起来不可行如何存储？
//思考在于，当给定一个上界，而当前解由于各机器都没有到达上界，但整体超过了，此时过程中如何处理？可能一个本来比当前解更差的解，由于已知上界的存在，反而比当前解更好了,想一下这种如何统一处理
//TODO 2025.5.12 ALNS的过程和VND的过程都不是太能使用一个很好的upperbound去限制函数的定义域，会出一定的问题
	//1、比如如果单纯做VND，此时上界给一个很好的上界A，但当前初始解是比较差的B，而当函数没有可行域或者超过当前上界时，成本会被评估为upperBound（或big_M），但这个是不合理的
	//即，（正常情况下）有可能VND的过程中先由B搜到一个略好的解C（A<C<B）,此时可通过C进一步走向一个更好的解D<A。但如果使用upperBound限制以后，此时可能比较极端的情况，解B搜出来的所有邻域都比A更差
	//那可能所有的邻域解都会被评估为A，此时不知道谁好谁坏了，从而无法选中能到更好解的那个。
	//当前策略下可能某个cost[M]单独计算超过upperBound时，会直接取值为upperBound，而如果单个cost[M]没超过，但整体超过了，此时cost[M]保持原始值，但整体设置为upperBound
	//此时就会导致邻域判断的时候，例如上界为1600，此时两个机器成本分别为1500,300, 而一个邻域解为1700（1600）,0, 此时从原始角度看，邻域解更好，但评估的时候会导致新解比原始解好200，这种评估就会出错了，从而可能两个解都是
	//1600,其中之一原始成本是3000，另一个是2000，虽然都比upperBound更差，但此时还会认为有改进。整个的过程就会很乱。
	//2、当结合ALNS的时候也会有问题，因为ALNS的扰动步骤本身就会接受差解，如果一个扰动比如删除后贪婪插入，此时所有插入后的成本都超过上界，那就会全部被标识为upperBound，此时其实ALNS的算子就全都变成随机算子了
	//换句话说，上界这种设置和接受差解的原理是冲突的。
	//3、一种可能是用上界做法是，先讨论VND本身，如果初始upperBound设置的比当前VND的初始解更大，那初始解本身各个函数的计算就不会有问题，在此基础上，基于初始解去搜索邻域，此时如果解有改进，是可以识别到的。
	//当commit的时候更新上界，也就是说对某个解的邻域搜索的时候，使用的upperBound比该解的目标更大就好了，从而解更差的不会接受，解更好的可以保留。 这样的话,VND的结果肯定比当前用的上界好或等于。
	//但当进入ALNS的时候，可能需要重算，不然也可能出现ALNS的所有解都比较差，此时超过了当前解之前使用的upperBound(因为当前解存储的函数是基于此算的），那就还会出问题。
		// 2种做法，要么设上界就设置一个倍数，从而要求ALNS的结果如果超过这个上界就随机选一个结果（似乎也不太靠谱）
		// 要么就是ALNS的时候取消上界，重算一下一维函数，从而ALNS的过程就正常了，进VND之前再整体刷一次。
//2025.5.13 其实可以进一步考虑将所有段都超过上界的直接设置为null，这种如果多的话可能还是能节省不少的，毕竟如果是一条直线，还要不断加一个，变成直线重复无用操作
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import Basic.Data;
import Common.Utility;
import Common.PiecewiseLinearFunction;

//TODO local search感觉只能做同质的，异质的机器之间的partial scheduling拼起来的时候没办法快速处理.
public interface Move {
	public void searchBest();

	// 对给定的解s，执行当前算子中的所有邻域找到最好
	public boolean commit();
	// 对当前算子下的最好的操作，更新解,若为true，则产生了更新，否则无更新
	
	public static double testSequence(Data data,ArrayList<Integer> sequence,Solution s) {
		double[] completionF=new double[sequence.size()];
		double[] completionB=new double[sequence.size()];
		
		
		if (sequence.size() == 0) {
			return 0;
		}
		
		double totalTime=data.s[0][sequence.get(0)]+data.p[sequence.get(0)];
		for(int i=1;i<sequence.size();i++) {
			totalTime+=data.s[sequence.get(i-1)][sequence.get(i)]+data.p[sequence.get(i)];
		}
//		System.out.println(sequence+" "+totalTime);
		if(Utility.compareGt(totalTime, data.CmaxH)) {
			return Utility.curUpperBound;
		}
		
		
		List<PiecewiseLinearFunction> f = new ArrayList<>();
		List<PiecewiseLinearFunction> b = new ArrayList<>();
		// forward
		for (int i = 0; i < sequence.size(); i++) {
			int job = sequence.get(i);
			PiecewiseLinearFunction cur;
			if (i == 0) {
				// 2026-05-14: 首任务的完成时间不能早于“从虚拟起点 0 到该任务的 setup + 加工时间”。
				// 这里裁掉 [0, s[0][job]+p[job])，避免后面回推 completion 时选到物理不可行的时间点。
				cur = data.penaltyFunction[job].setDomain(data.p[job] + data.s[0][job], data.CmaxH);
			} else {
				cur = f.get(i - 1).shiftX(data.s[sequence.get(i - 1)][job] + data.p[job]);
				cur =cur.add(data.penaltyFunction[job]);
			}
			cur.minimizePrefixInPlace();
			f.add(cur);
		}
		
		for (int i = sequence.size() - 1; i >= 0; i--) {
			b.add(null);
		}
		for (int i = sequence.size() - 1; i >= 0; i--) {
			int job = sequence.get(i);
			PiecewiseLinearFunction cur;
			if (i == sequence.size() - 1) {
//				cur = data.penaltyFunction[job].copy().setDomain(data.p[job] + data.min_s[job], data.Cmax);
				//2025.5.3 bFunction不需要设domain
				cur = data.penaltyFunction[job].copy();
				
			} else {
				cur = b.get(i + 1).shiftX(-data.s[job][sequence.get(i + 1)] - data.p[sequence.get(i + 1)]).add(data.penaltyFunction[job]);
			}
			cur.minimizeSuffixInPlace();
			b.set(i, cur);
		}
		
		// 构造每个位置上任务的惩罚成本函数
		PiecewiseLinearFunction lastF = f.get(f.size() - 1);
		double[] values=lastF.findMinimal(true, true);
		completionF[sequence.size()-1]=values[1];
		for(int i=sequence.size()-2;i>=0;i--) {
			int job=sequence.get(i);
			PiecewiseLinearFunction fJob=f.get(i).setDomain(f.get(i).domainStart, completionF[i+1]-data.s[sequence.get(i)][sequence.get(i+1)]-data.p[sequence.get(i+1)]);
			values=fJob.findMinimal(true, true);
			completionF[i]=values[1];
		}
		
		PiecewiseLinearFunction firstB = b.get(0);
		values=firstB.findMinimal(true, false);
		completionB[0]=values[1];
		for(int i=1;i<sequence.size();i++) {
			int job=sequence.get(i);
			PiecewiseLinearFunction bJob=b.get(i).setDomain( completionB[i-1]+data.s[sequence.get(i-1)][sequence.get(i)]+data.p[sequence.get(i)],b.get(i).domainEnd);
			values=bJob.findMinimal(true, false);
			completionB[i]=values[1];
		}
		
		
//		System.out.println("反向最小:"+b.get(0).findMinimal(true, true)[0]+"前向/反向最小完成时间："+Arrays.toString(completionF)+" "+Arrays.toString(completionB));
		if(lastF.tail==null) {
			return Utility.curUpperBound;
		}
		return  lastF.tail.end * lastF.tail.slope + lastF.tail.intercept;
	}
	public static void testSOutput(int[] para,int[] machines,Solution s,double testCost,double cost) {
		Utility.debugNumPlus();
		//单机器测试输出
		String string="";
		string+="测试结果： 参数: ";
		for(int p:para) {
			string+= (p+" ");
		}
		string+= "(机器：原始成本)";
//		for(int m:machines) {
//			string+= (m+" "+(s.cost[m])+" ");
//		}
		string+=" "+s.curCost+" ";
		
		string+="   最终成本:";
		string+= (testCost+" "+cost+" "+" "+Utility.curUpperBound);
		string+=(Utility.compareEq(cost,testCost))+" ";
//		if(Utility.compareEq(cost,testCost)==false) 
//			System.out.println(cost+" "+testCost);
		System.out.println(string);
		
	}
	
	public static void Recordreset() {
		IOptOperator.noImprovedSeq.clear();
		TwoOptOperator.noImprovedSeq.clear();
		TwoOptStarOperator.noImprovedSeqPair.clear();
		PathInsertOperator.noImprovedSeqPair.clear();
		CrossExchangeOperator.noImprovedSeqPair.clear();
		
	}
}



/**
 * IOpt算子,单个机器内部删除一段路径并插入到另一个位置，最多需要4段拼接,可参考2005年文章DP做
 */
class IOptOperator implements Move {
	// crossIntra算子
	/* 参数，可由外部调节 */
	public static int LPATH = 10;// 路径移除长度, 移动点距离当前点距离长度限制
	static int LINS = 6;
	private static boolean allowReverseIOpt = true;
	// 默认允许forward和backward的移动

	public Solution s;
	public Data data;
	private int[] bestFrom;// 起始位置
	private int[] bestTo;// 插入位置
	private double[] bestCost;//
	private int[] bestLen;// 选择长度
	private boolean[] bestReverse;// 是否反转
	public static HashSet<List<Integer>> noImprovedSeq = new HashSet<List<Integer>>();// 其实没必要用数组，同质机器都一样,而对异质机器，这种拼接的做法是无法使用的，先不考虑异质的情况
	// TODO 尝试记录没有用的顺序有哪些，这个顺序下当前算子没用
	// true表示上一轮迭代这个算子在当前其找到了一个更好的，本轮可以继续做
	// 否则的话已经陷入了局部最优，本轮没必要再去对机器m执行操作

	public IOptOperator(Solution s) {
		this.s = s;
		this.data = s.data;
		this.bestFrom = new int[data.m];
		this.bestTo = new int[data.m];
		this.bestCost = new double[data.m];
		this.bestLen = new int[data.m];
		this.bestReverse = new boolean[data.m];
		Arrays.fill(bestFrom, -1);
		Arrays.fill(bestTo, -1);
		Arrays.fill(bestLen, -1);
		Arrays.fill(bestReverse, false);

	}

	/* -------- 搜索整个邻域 -------- */
	@Override
	public void searchBest() {

		for (int m = 0; m < data.m; m++) {
			searchBest(m);
		}

	}

	public void searchBest(int m) {
		if (noImprovedSeq.contains(s.sequences.get(m)))
			return;
		s.updateFunctions3ForMachine(m);//Iopt执行的时候需要先更新相关记录函数
		// 对机器m搜索最优操作
		
		ArrayList<Integer> seq = s.sequences.get(m);
		int size = seq.size();
		double bestCost = 0;

		// backward移动
		for (int st = 1; st < size; st++) {
			for (int d = -LINS; d < -1; d++) {
				int insertedP = st + d;// 将这一段插入到insertedP之后
				if (insertedP < -1)
					break;
//				if (insertedP == st - 1)
//					continue;

				for (int len = 0; len <= LPATH && st + len < size; len++) {
					// [st,st+len]拆出
					// 枚举插入偏移
					for (boolean rev : allowReverseIOpt ? new boolean[] { false, true } : new boolean[] { false }) {
						double deltaCost = evalDelta(s, m, st, len, insertedP, rev);
						
//						double testCost =Move.testSequence(data, getNewSeqBackward(seq, st, len, insertedP, rev),s);
//						Move.testSOutput(new int[] {st,len,insertedP,rev?1:0},new int[] {m}, s, testCost, deltaCost+s.cost[m]);
//						double deltaCost1 = evalDelta(s, m, st, len, insertedP, rev);
						
						if (Utility.compareLt(deltaCost, bestCost)) {
							saveBestOperation(m, st, insertedP, len, rev, s.cost[m] + deltaCost);
							bestCost = deltaCost;
						}
						if(len==0) break;
					}
				}
			}

		}

		//// forward移动
		for (int st = 0; st < size; st++) {
			for (int d = 1; d <= LINS; d++) {
				int insertedP = st + d;// 将这一段插入到insertedP之后
				if (insertedP>size-1) break;
				for (int len = 0; len <= LPATH && st + len <insertedP; len++) {
					// 枚举插入偏移
					for (boolean rev : allowReverseIOpt ? new boolean[] { false, true } : new boolean[] { false }) {
						double deltaCost = evalDelta(s, m, st, len, insertedP, rev);
//						double testCost =Move.testSequence(data, getNewSeqForward(seq, st, len, insertedP, rev),s);
//						Move.testSOutput(new int[] {st,len,insertedP,rev?1:0},new int[] {m}, s, testCost, deltaCost+s.cost[m]);
//						double deltaCost1 = evalDelta(s, m, st, len, insertedP, rev);
						
						
						if (Utility.compareLt(deltaCost, bestCost)) {
							saveBestOperation(m, st, insertedP, len, rev, s.cost[m] + deltaCost);
							bestCost = deltaCost;
						} 
						if(len==0) break;
					}
					// normal,reverse插入

				}
			}
		}
	}
	
	public ArrayList<Integer> getNewSeqBackward(ArrayList<Integer> seq,int st,int len,int insertedP,boolean rev){
		ArrayList<Integer> newSeq = new ArrayList<Integer>();
		ArrayList<Integer> slice = new ArrayList<>(seq.subList(st, st +len+1));
		newSeq.addAll(seq.subList(0,insertedP+1));
		if (rev) {
			Collections.reverse(slice);
		}

		newSeq.addAll(slice);
		newSeq.addAll(seq.subList(insertedP + 1, st));
		newSeq.addAll(seq.subList(st +len+1, seq.size()));
		return newSeq;
	}
	public ArrayList<Integer> getNewSeqForward(ArrayList<Integer> seq,int st,int len,int insertedP,boolean rev){
		ArrayList<Integer> newSeq = new ArrayList<Integer>();
		ArrayList<Integer> slice = new ArrayList<>(seq.subList(st, st +len+1));
		newSeq.addAll(seq.subList(0,st));
		if (rev) {
			Collections.reverse(slice);
		}
		newSeq.addAll(seq.subList(st +len+1, insertedP+1));
		newSeq.addAll(slice);
		newSeq.addAll(seq.subList(insertedP + 1, seq.size()));
		
		
		
		return newSeq;
	}

	public double evalDelta(Solution s, int mid, int from, int len, int to, boolean rev) {
		ArrayList<Integer> seq=s.sequences.get(mid);
		PiecewiseLinearFunction f1=rev?s.fFunctions_hgl_reverse[mid][from][to==-1?seq.size():to][len]:s.fFunctions_hgl_normal[mid][from][to==-1?seq.size():to][len];
		PiecewiseLinearFunction b2=rev?s.bFunctions_hgl_reverse[mid][from][to==-1?seq.size():to][len]:s.bFunctions_hgl_normal[mid][from][to==-1?seq.size():to][len];
		if(b2==null) {
			System.out.println(" "+from+" "+to+" "+len);
		}
		double shift=0;
		if(to<from) shift=(rev?data.s[seq.get(from)][seq.get(to+1)]:data.s[seq.get(from+len)][seq.get(to+1)])+data.p[seq.get(to+1)];
		else shift=(rev?data.s[seq.get(to)][seq.get(from+len)]:data.s[seq.get(to)][seq.get(from)])+data.p[rev?seq.get(from+len):seq.get(from)];
		
		return s.merge2Segments(f1, b2, shift)-s.cost[mid];
	}

	@Override
	public boolean commit() {
		boolean imp = false;
		for (int m = 0; m < data.m; m++) {
			if (bestFrom[m] == -1) {

				noImprovedSeq.add(List.copyOf(s.sequences.get(m)));
				continue;

			}
			imp = true;
			ArrayList<Integer> seq = s.sequences.get(m);
			ArrayList<Integer> newSeq = new ArrayList<Integer>();
			ArrayList<Integer> slice = new ArrayList<>(seq.subList(bestFrom[m], bestFrom[m] + bestLen[m]+1));
			newSeq.addAll(seq.subList(0,Math.min(bestFrom[m], bestTo[m]+1)));
			if (bestReverse[m]) {
				Collections.reverse(slice);
			}

			if (bestFrom[m] < bestTo[m]) {
				newSeq.addAll(seq.subList(bestFrom[m] + bestLen[m]+1, bestTo[m] + 1));
				newSeq.addAll(slice);
				newSeq.addAll(seq.subList(bestTo[m] + 1, seq.size()));
			} else {
				newSeq.addAll(slice);
				newSeq.addAll(seq.subList(bestTo[m] + 1, bestFrom[m]));
				newSeq.addAll(seq.subList(bestFrom[m] + bestLen[m]+1, seq.size()));
			}
			s.sequences.set(m, newSeq);
			s.updateInformationM(m, bestCost[m]);
			
		}
		return imp;
	}

	public void saveBestOperation(int m, int from, int to, int len, boolean rev, double cost) {
		bestFrom[m] = from;
		bestTo[m] = to;
		bestLen[m] = len;
		bestReverse[m] = rev;
		bestCost[m] = cost;
	}
}

/**
 * 2-opt算子，3段拼接，理论上是Iopt的特例，但Iopt限制长度，如何拼接？
 */
//完成验证
class TwoOptOperator implements Move {
	public static int L2 = 30;// 限制搜索长度
	public Solution s;
	public Data data;
	private int[] bestFrom;// 起始位置
	private int[] bestTo;// 终止位置
	private double[] bestCost;// 终止位置
	static HashSet<List<Integer>> noImprovedSeq= new HashSet<List<Integer>>();;// 其实没必要用数组，同质机器都一样？
	// TODO 尝试记录没有用的顺序有哪些，这个顺序下当前算子没用
	// true表示上一轮迭代这个算子在当前其找到了一个更好的，本轮可以继续做
	// 否则的话已经陷入了局部最优，本轮没必要再去对机器m执行操作

	public TwoOptOperator(Solution s) {
		this.s = s;
		this.data = s.data;
		this.bestFrom = new int[data.m];
		this.bestTo = new int[data.m];
		this.bestCost = new double[data.m];
		Arrays.fill(bestFrom, -1);
		Arrays.fill(bestTo, -1);
		
	}

	/* =================== 搜索邻域 =================== */
	@Override
	public void searchBest() {

		for (int m = 0; m < data.m; m++) {
			searchBest(m);
		}

	}

	public void searchBest(int m) {

		ArrayList<Integer> seq = s.sequences.get(m);
		if (noImprovedSeq.contains(seq))
			return;
		int size = seq.size();
		double bestCost = 0;
//         反转[a,b]
		for (int a = 0; a < size - 1; a++) {
//			//不再需要
//				double formerShift=(a==0?0:s.cumDurationNormal[m][a-1]);
//				s.updateFunctions2ForMachine(m,formerShift, a==0?0:seq.get(a-1));
			
			for (int b = a+1 ; b < Math.min(size, a + L2); b++) {
				double deltaCost = evalDelta(s, m, a, b); // 反转 a..b
//				double testCost = Move.testSequence(data,getNewSeq(seq, a, b),s);
//				Move.testSOutput(new int[] {a,b},new int[] {m}, s, testCost, s.cost[m]+deltaCost);
				//已完成测试，全部拼接采用3段拼接
				if (Utility.compareLt(deltaCost, bestCost)) {
					bestCost = deltaCost;
					saveBestOperation(m, a, b, s.cost[m] + deltaCost);
				}
			}
		}

	}

	public ArrayList<Integer> getNewSeq(ArrayList<Integer>seq,int a,int b){
		//用于生成新序列进行测试
		ArrayList<Integer> newSeq = new ArrayList<Integer>();
		ArrayList<Integer> slice = new ArrayList<>(seq.subList(a, b + 1));
		Collections.reverse(slice);
		newSeq.addAll(seq.subList(0, a));
		newSeq.addAll(slice);
		newSeq.addAll(seq.subList(b + 1, seq.size()));
		return newSeq;
		 
	}
	/* =================== 执行移动 =================== */
	@Override
	public boolean commit() {

		boolean imp = false;

		for (int m = 0; m < data.m; m++) {
			if (bestFrom[m] == -1) {

				noImprovedSeq.add(List.copyOf(s.sequences.get(m)));
				continue;

			}
			imp = true;
			ArrayList<Integer> seq = s.sequences.get(m);
			ArrayList<Integer> newSeq = new ArrayList<Integer>();
			ArrayList<Integer> slice = new ArrayList<>(seq.subList(bestFrom[m], bestTo[m] + 1));
			Collections.reverse(slice);
			newSeq.addAll(seq.subList(0, Math.min(bestFrom[m], bestTo[m])));
			newSeq.addAll(slice);
			newSeq.addAll(seq.subList(bestTo[m] + 1, seq.size()));

			s.sequences.set(m, newSeq);
			s.updateInformationM(m, bestCost[m]);

		}

		// TODO 更新新解有变动的部分的所有的函数等
		return imp;

	}

	public void saveBestOperation(int m, int from, int to, double cost) {
		bestFrom[m] = from;
		bestTo[m] = to;
		bestCost[m] = cost;

	}

	public double evalDelta(Solution s, int mid, int aid, int bid) {
		// 2-opt算子,3段拼接
		int size = s.sequences.get(mid).size();
		ArrayList<Integer> seq = s.sequences.get(mid);

		// 第一段forward函数 0-aid-1
		PiecewiseLinearFunction f1 = aid==0?data.penaltyFunction[0]:s.fFunctions.get(mid).get(aid - 1);

		// 第2段forward函数 aid-bid 的反向
//		double domainForward=data.s[aid==0?0:seq.get(aid-1)][seq.get(bid)]+data.p[seq.get(bid)]+(aid==0?0:s.cumDurationNormal[mid][aid-1]);
		//表示函数f2基于现有基础，需要重新缩减部分可行域,为前边段的执行长度
		//但注意，后续f2函数还会平移，需要将domain重置，否则平移后的t的含义不在一样，设置相同的domain有问题
		//这个没用了 2025.5.5
		PiecewiseLinearFunction f2 = s.fFunctions_gh[mid][aid][bid];

		f2.minimizePrefixInPlace();

		// 第2段backward函数 aid-bid 的反向
		PiecewiseLinearFunction b2 = s.bFunctions_gh[mid][aid][bid];

		// 第3段backward函数 bid+1 - size-1
		PiecewiseLinearFunction b3 = bid==size-1?data.penaltyFunction[0]:s.bFunctions.get(mid).get(bid + 1);

		double shift1 = data.s[aid==0?0:seq.get(aid - 1)][seq.get(bid)] + data.p[seq.get(bid)]; // s_{(aid-1)位置任务}{(bid)位置任务}+p_{(bid)位置任务}
		double shift2 = bid==size-1?0:data.s[seq.get(aid)][seq.get(bid + 1)] + data.p[seq.get(bid + 1)];
		; // s_{(aid)位置任务}{(bid+1)位置任务}+p_{(bid+1)位置任务}
		double duration2 = s.getReverseDuration(mid, aid, bid);

//		if (aid != 0 && bid != size - 1) {
//			// 此时为三段拼接
//			List<Integer> seq2=new ArrayList<Integer>(seq.subList(aid, bid+1)); Collections.reverse(seq2);
//			double mergedCost = s.merge3SegmentsTest(f1, f2, b2, b3, shift1, shift2, duration2,seq.subList(0, aid),seq2,seq.subList(bid+1, size));
//			
//			return mergedCost - s.cost[mid];// 当前操作导致的deltaCost
//		} else {
//			if (aid == 0) {
//				// 两段拼接
//				// 第一段取forward函数 aid-bid 的反向
//				// 第二段取backward函数 bid+1 - size-1
//				if(bid==size-1) {
//					//此时相当于整段调度反向
////					double cost=f2.tail.getValue(f2.tail.end);
////					return cost- s.cost[mid];
//					List<Integer> seq2=new ArrayList<Integer>(seq.subList(aid, bid+1)); Collections.reverse(seq2);
//					double mergedCost = s.merge3SegmentsTest(f1, f2, b2, b3, shift1, shift2, duration2,seq.subList(0, aid),seq2,seq.subList(bid+1, size));
//					return mergedCost-s.cost[mid];
//				}
////				double mergedCost = s.merge2Segments(f2.setDomain(shift1, data.Cmax), b3, shift2);
////				return mergedCost - s.cost[mid];// 当前操作导致的deltaCost
//				List<Integer> seq2=new ArrayList<Integer>(seq.subList(aid, bid+1)); Collections.reverse(seq2);
//				double mergedCost = s.merge3SegmentsTest(f1, f2, b2, b3, shift1, shift2, duration2,seq.subList(0, aid),seq2,seq.subList(bid+1, size));
//				return mergedCost-s.cost[mid];
//				
//			}
//			if (bid == size - 1) {
//				// 两段拼接
//				// 第一段取forward函数 0-aid
//				// 第二段取backward函数 aid-bid 的反向
////				double mergedCost = s.merge2Segments(f1, b2, shift1);
////				return mergedCost - s.cost[mid];// 当前操作导致的deltaCost
//				List<Integer> seq2=new ArrayList<Integer>(seq.subList(aid, bid+1)); Collections.reverse(seq2);
//				double mergedCost = s.merge3SegmentsTest(f1, f2, b2, b3, shift1, shift2, duration2,seq.subList(0, aid),seq2,seq.subList(bid+1, size));
//				return mergedCost-s.cost[mid];
//			}
//		}
		
		//不再使用此处注释的分3段拼接和2段拼接（边界翻转，但上述代码也采用了3段拼接的做法）
		double mergedCost = s.merge3Segments(f1, f2, b2, b3, shift1, shift2, duration2);
		return mergedCost-s.cost[mid];
		
	}

}

/**
 * 将调度m1上的后一段和调度m2的后一段交换,每个路线都是2段拼接
 */

class TwoOptStarOperator implements Move {

	static class OptCost implements Comparable<OptCost> {
		int m1;// 机器
		int m2;
		int from1;// 起始位置
		int from2;
		boolean rev1;// 是否反转
		boolean rev2;
		double deltaCost;

		@Override
		public int compareTo(OptCost o) {
			return (int) (this.deltaCost - o.deltaCost);
		}

		public OptCost(int m1, int m2, int from1, int from2, boolean rev1, boolean rev2, double deltaCost) {
			this.m1 = m1;
			this.m2 = m2;
			this.from1 = from1;
			this.from2 = from2;
			this.rev1 = rev1;
			this.rev2 = rev2;
			this.deltaCost = deltaCost;
		}

	}

	private ArrayList<OptCost> savedOperations;
	private static boolean allowReverseTwoOptStar = true;
	// 默认允许forward和backward的移动
	public Solution s;
	public Data data;
	static HashSet<Set<List<Integer>>> noImprovedSeqPair= new HashSet<Set<List<Integer>>>();; // 对某两个机器当前的序列，应用该算子是否还有用
	// TODO 尝试记录没有用的顺序有哪些，这个顺序下当前算子没用
	// true表示上一轮迭代这个算子在当前其找到了一个更好的，本轮可以继续做
	// 否则的话已经陷入了局部最优，本轮没必要再去对机器m执行操作

	public TwoOptStarOperator(Solution s) {
		this.s = s;
		this.data = s.data;
		this.savedOperations = new ArrayList<TwoOptStarOperator.OptCost>();
	
	}

	@Override
	public void searchBest() {
		for (int m1 = 0; m1 < data.m; m1++) {
			for (int m2 = m1 + 1; m2 < data.m; m2++) {
				if (noImprovedSeqPair.contains(Set.of(s.sequences.get(m1), s.sequences.get(m2))))
					continue;
				ArrayList<Integer> seq1 = s.sequences.get(m1);
				ArrayList<Integer> seq2 = s.sequences.get(m2);
				for (int cut1 = 1; cut1 < seq1.size(); cut1++) {
					for (int cut2 = 1; cut2 < seq2.size(); cut2++) {
						for (boolean rev1 : allowReverseTwoOptStar ? new boolean[] { false, true }
								: new boolean[] { false }) {
							for (boolean rev2 : allowReverseTwoOptStar ? new boolean[] { false, true }
									: new boolean[] { false }) {
								double deltaCost = evalDelta(s, m1, m2, cut1, cut2, rev1, rev2);
								
								ArrayList<ArrayList<Integer>> newSeqs=getNeqSeqs(seq1, cut1, seq2, cut2, rev1, rev2);
//								double testCost1=Move.testSequence(data, newSeqs.get(0), s);
//								double testCost2=Move.testSequence(data, newSeqs.get(1), s);
//								Move.testSOutput(new int[] {cut1,cut2,rev1?1:0,rev2?1:0}, new int[] {m1,m2}, s, testCost1+testCost2, deltaCost+s.cost[m1]+s.cost[m2]);
								
								// 2026-05-15:
								// 跨机器算子后续会对 savedOperations 排序，并贪婪提交互不重叠机器上的改进 move。
								// 因此这里不能只保存“刷新全局最优”的候选，否则某个机器对即使存在改进，
								// 只要它没有刷新全局 best，就不会进入 savedOperations；commit 末尾又会据此把该机器对
								// 写入 noImprovedSeqPair，导致后续相同序列被错误跳过。
								// 这里保存所有改进候选，使 noImprovedSeqPair 的含义保持为“该机器对确实没有任何改进候选”。
								if (Utility.compareLt(deltaCost, 0)) {
									savedOperations.add(new OptCost(m1, m2, cut1, cut2, rev1, rev2, deltaCost));
								}
								if(cut2==seq2.size()-1) break;
							}
							if(cut1==seq1.size()-1) break;
						}
					}

				}
			}
		}

	}
	public ArrayList<ArrayList<Integer>> getNeqSeqs(ArrayList<Integer>s1,int from1,ArrayList<Integer> s2,int from2,boolean rev1,boolean rev2){
		
		s1=new ArrayList<Integer>(s1);
		s2=new ArrayList<Integer>(s2);
		List<Integer> tail1 = new ArrayList<>(s1.subList(from1, s1.size()));
		List<Integer> tail2 = new ArrayList<>(s2.subList(from2, s2.size()));
		if (rev1)
			Collections.reverse(tail1);
		if (rev2)
			Collections.reverse(tail2);

		s1.subList(from1, s1.size()).clear();
		s2.subList(from2, s2.size()).clear();
		s1.addAll(tail2);
		s2.addAll(tail1);
		ArrayList<ArrayList<Integer>> newSeqs=new ArrayList<ArrayList<Integer>>();
		newSeqs.add(s1);
		newSeqs.add(s2);
		return newSeqs;
	}
	//TODO 不能值判断一次，一旦有setup time就不行了
	public boolean mergeCmaxValidation(int m1, int m2,int cut1,int cut2,boolean rev1,boolean rev2) {
		ArrayList<Integer> seq1=s.sequences.get(m1);
		ArrayList<Integer> seq2=s.sequences.get(m2);
		double mergedtimeM1=s.cumDurationNormal[m1][cut1-1]+data.s[seq1.get(cut1-1)][rev2?seq2.get(seq2.size()-1):seq2.get(cut2)]+data.p[rev2?seq2.get(seq2.size()-1):seq2.get(cut2)]
				+(rev2?s.getReverseDuration(m2, cut2, seq2.size()-1):s.getNormalDuration(m2, cut2, seq2.size()-1));
//		System.out.println(mergedtimeM1);
		if(Utility.compareGt(mergedtimeM1, data.CmaxH)) {
			return false;
		}
		double mergedtimeM2=s.cumDurationNormal[m2][cut2-1]+data.s[seq2.get(cut2-1)][rev1?seq1.get(seq1.size()-1):seq1.get(cut1)]+data.p[rev1?seq1.get(seq1.size()-1):seq1.get(cut1)]
				+(rev1?s.getReverseDuration(m1, cut1, seq1.size()-1):s.getNormalDuration(m1, cut1, seq1.size()-1));
//		System.out.println(mergedtimeM2);
		if(Utility.compareGt(mergedtimeM2, data.CmaxH)) {
			return false;
		}
		
		return true;
		
	}
	
	@Override
	public boolean commit() {
		boolean imp = false;
		if (savedOperations.size() == 0)
			return false;
		imp = true;
		Collections.sort(savedOperations);
		boolean[] coverdMachines = new boolean[data.m];
		boolean[][] improvedPair = new boolean[data.m][data.m];

		for (OptCost operation : savedOperations) {
			improvedPair[operation.m1][operation.m2] = true;
		}
		// 2026-05-15:
		// noImprovedSeqPair 记录的是“本轮搜索时看到的旧序列 pair 没有改进”。
		// 因此必须在真正修改机器序列之前写入缓存；否则批量 commit 改完某台机器后，
		// 可能把由新序列组成、但本轮并未搜索过的 pair 错误写成 no-improved。
		for (int m1 = 0; m1 < data.m; m1++) {
			for (int m2 = m1 + 1; m2 < data.m; m2++) {
				if (!improvedPair[m1][m2]) {
					noImprovedSeqPair.add(Set.of(List.copyOf(s.sequences.get(m1)), List.copyOf(s.sequences.get(m2))));
				}
			}
		}

		for (OptCost operation : savedOperations) {
			boolean allCover = true;
			for (boolean machineCovered : coverdMachines) {
				if (!machineCovered) {
					allCover = false;
					break;
				}
			}
			// 更新noImprovedseqPair

			int m1 = operation.m1;
			int m2 = operation.m2;

			if (allCover) {
				continue;
			}
			if (coverdMachines[m1] || coverdMachines[m2])
				continue;
			coverdMachines[m1] = true;
			coverdMachines[m2] = true;
			int from1 = operation.from1;
			int from2 = operation.from2;
			boolean rev1 = operation.rev1;
			boolean rev2 = operation.rev2;
			ArrayList<Integer> s1 = s.sequences.get(m1);
			ArrayList<Integer> s2 = s.sequences.get(m2);

			List<Integer> tail1 = new ArrayList<>(s1.subList(from1, s1.size()));
			List<Integer> tail2 = new ArrayList<>(s2.subList(from2, s2.size()));
			if (rev1)
				Collections.reverse(tail1);
			if (rev2)
				Collections.reverse(tail2);

			s1.subList(from1, s1.size()).clear();
			s2.subList(from2, s2.size()).clear();
			s1.addAll(tail2);
			s2.addAll(tail1);
			s.updateInformationM(m1);
			s.updateInformationM(m2);
		}

		return imp;
	}

	/* ------- Δ 两段：各线路独立计算 ------- */
	private double evalDelta(Solution sol, int m1, int m2, int cut1, int cut2, boolean rev1, boolean rev2) {
		
		if(!mergeCmaxValidation(m1, m2, cut1, cut2, rev1, rev2)) return Utility.curUpperBound;//这个返回big_M或者upperBound应该都可以
		ArrayList<Integer> seqM1 = s.sequences.get(m1);
		ArrayList<Integer> seqM2 = s.sequences.get(m2);
		
		double originCost = s.cost[m1] + s.cost[m2];
		// 对机器m1执行拼接计算
		PiecewiseLinearFunction f1M1 = s.fFunctions.get(m1).get(cut1 - 1);// 机器m1的第一段
		PiecewiseLinearFunction b2M2 = null;
		if (!rev2)
			b2M2 = s.bFunctions.get(m2).get(cut2);// 机器m2的第2段正向的backward函数
		else
			b2M2 = s.bFunctions_gh[m2][cut2][seqM2.size() - 1];// 机器m2的第2段反向的backward函数

		// 拼接
		double shift1 = data.s[seqM1.get(cut1 - 1)][rev2?seqM2.get(seqM2.size() - 1):seqM2.get(cut2)] + data.p[rev2?seqM2.get(seqM2.size() - 1):seqM2.get(cut2)];
		double cost1 = s.merge2Segments(f1M1, b2M2, shift1);

		// 对机器m2执行拼接计算
		PiecewiseLinearFunction f1M2 = s.fFunctions.get(m2).get(cut2 - 1);// 机器m2的第一段
		PiecewiseLinearFunction b2M1 = null;
		if (!rev1)
			b2M1 = s.bFunctions.get(m1).get(cut1);// 机器m1的第2段
		else
			b2M1 = s.bFunctions_gh[m1][cut1][seqM1.size() - 1];// 机器m1的第2段反向的backward函数

		double shift2 = data.s[seqM2.get(cut2 - 1)][rev1?seqM1.get(seqM1.size()-1):seqM1.get(cut1)] + data.p[rev1?seqM1.get(seqM1.size()-1):seqM1.get(cut1)];
		double cost2 = s.merge2Segments(f1M2, b2M1, shift2);

		// 2026-05-15: 返回的是变化成本 delta = 新两机成本 - 原两机成本，不是新解总成本；
		// 因此 searchBest 中用 deltaCost < 0 判断是否为改进候选是正确的。
		return cost1 + cost2 - originCost;

	}

}

/**
 * 从一个调度中选择一段任务，插入到另一个路径的某个位置
 */
class PathInsertOperator implements Move {

	private static int LSEG = 20; // 段长与插入距离限制

	static class OptCost implements Comparable<OptCost> {
		int m1;// 机器
		int m2;
		int from1;// 起始位置
		int len;
		int to2;
		boolean rev;// 是否反转
		double deltaCost;

		@Override
		public int compareTo(OptCost o) {
			return (int) (this.deltaCost - o.deltaCost);
		}

		public OptCost(int m1, int m2, int from1, int len, int to2, boolean rev, double deltaCost) {
			this.m1 = m1;
			this.m2 = m2;
			this.from1 = from1;
			this.len = len;
			this.to2 = to2;
			this.rev = rev;
			this.deltaCost = deltaCost;
		}
	}

	private ArrayList<OptCost> savedOperations;
	private static boolean allowReversePathInsert = true;
	// 默认允许forward和backward的移动
	public Solution s;
	public Data data;
	public static HashSet<List<List<Integer>>> noImprovedSeqPair=new HashSet<List<List<Integer>>>();; // 对某两个机器当前的序列，应用该算子是否还有用

	public PathInsertOperator(Solution s) {
		this.s = s;
		this.data = s.data;
		this.savedOperations = new ArrayList<OptCost>();
		

	}

	/* -------- Δ：源线拼三段，目标线也拼三段 -------- */
	private double evalDelta(Solution s, int m1, int m2, int stM1, int len, int stM2, boolean rev) {
		if(!mergeCmaxValidation(m1, m2, stM1, len, stM2, rev)) return Utility.curUpperBound;//返回upperBound或者big_M应该都行
		ArrayList<Integer> seqM1 = s.sequences.get(m1);
		ArrayList<Integer> seqM2 = s.sequences.get(m2);
		double costM1 = 0;
		double costM2 = 0;
		double originCost = s.cost[m1] + s.cost[m2];
		int sizeM1 = seqM1.size();
		// 对机器M1,执行两段拼接 0-st-1, st+len+1-size-1
		PiecewiseLinearFunction f1M1 = stM1>=1?s.fFunctions.get(m1).get(stM1 - 1):data.penaltyFunction[0];
		PiecewiseLinearFunction b2M1 = null;
		if (stM1 + len != sizeM1 - 1) {
			b2M1 = s.bFunctions.get(m1).get(stM1 + len + 1);
			costM1 = s.merge2Segments(f1M1, b2M1,
					data.s[stM1==0?0:seqM1.get(stM1 - 1)][seqM1.get(stM1 + len + 1)] + data.p[seqM1.get(stM1 + len + 1)]);
		} else {
			costM1 = f1M1.tail.getValue(f1M1.tail.end);
			// 否则此时机器m1只剩下第一段,不需要拼接
		}

		// 对机器M2，执行2段或三段拼接
		PiecewiseLinearFunction fSplitM1 = null;// f1中拆出来这段的函数
		PiecewiseLinearFunction bSplitM1 = null;// f1中拆出来这段的函数
		if (!rev) {
			fSplitM1 = s.fFunctions_hg[m1][stM1][stM1 + len];
			bSplitM1 = s.bFunctions_hg[m1][stM1][stM1 + len];
		} else {
			fSplitM1 = s.fFunctions_gh[m1][stM1][stM1 + len];
			bSplitM1 = s.bFunctions_gh[m1][stM1][stM1 + len];
		}
		PiecewiseLinearFunction f1M2 = stM2==-1?data.penaltyFunction[0]:s.fFunctions.get(m2).get(stM2);
		PiecewiseLinearFunction b2M2 = stM2==seqM2.size()-1?data.penaltyFunction[0]:s.bFunctions.get(m2).get(stM2 + 1);
//		double shift = 0;
//		if (stM2 == -1) {
//			// 此时只需要两段拼接
//			shift = data.s[seqM1.get(stM1 + len)][seqM2.get(0)] + data.p[seqM2.get(0)];
//			costM2 = s.merge2Segments(fSplitM1.setDomain(data.s[0][seqM1.get(stM1)] + data.p[seqM1.get(stM1)], data.Cmax), b2M2, shift);
//		} else if (stM2 == seqM2.size() - 1) {
//
//			shift = data.s[seqM2.get(seqM2.size() - 1)][seqM1.get(stM1)] + data.p[seqM1.get(stM1)];
//			costM2 = s.merge2Segments(f1M2, bSplitM1, shift);
//		} else {
		//不在采用拼两段的做法,直接拼三段
		
			// 拼三段
			double shift1 = data.s[stM2==-1?0:seqM2.get(stM2)][rev?seqM1.get(stM1+len):seqM1.get(stM1)] + data.p[rev?seqM1.get(stM1+len):seqM1.get(stM1)];
			double shift2 = stM2==seqM2.size()-1?0:(data.s[rev?seqM1.get(stM1):seqM1.get(stM1+len)][seqM2.get(stM2 + 1)] + data.p[seqM2.get(stM2 + 1)]);
			double durationSplit =rev?s.getReverseDuration(m1, stM1, stM1+len):s.getNormalDuration(m1, stM1, stM1+len);
			costM2 = s.merge3Segments(f1M2, fSplitM1, bSplitM1, b2M2, shift1, shift2, durationSplit);
//		}

		// 2026-05-15: 返回的是变化成本 delta = 新两机成本 - 原两机成本，不是总成本。
		// 所以 searchBest 中保存所有 deltaCost < 0 的候选，语义就是保存所有改进候选。
		return costM1 + costM2 - originCost;

	}

	@Override
	public void searchBest() {

		for (int m1 = 0; m1 < data.m; m1++) {
			for (int m2 = 0; m2 < data.m; m2++) {
				if (m1 == m2)
					continue;
				if (noImprovedSeqPair.contains(List.of(s.sequences.get(m1), s.sequences.get(m2))))
					continue;
				// 这种算子下判断是否算过是有顺序的，使用list存储
				ArrayList<Integer> seq1 = s.sequences.get(m1);
				ArrayList<Integer> seq2 = s.sequences.get(m2);
				for (int a = 0; a < seq1.size(); a++) {
//					[a,a+len]插入m2的位置b之后

					// 不考虑将m1的调度全部插入
					// m1中的[a,a+len],a>=1,len可从开始
					for (int b = -1; b < seq2.size(); b++) {
						for (int len = 0; len <= LSEG && a + len < seq1.size(); len++) {
							for (boolean rev : allowReversePathInsert ? new boolean[] { false, true }
									: new boolean[] { false }) {
								double deltaCost = evalDelta(s, m1, m2, a, len, b, rev);
//								double testCost1=Move.testSequence(data, getNeqSeqs(seq1, a, len, seq2, b, rev).get(0), s);
//								double testCost2=Move.testSequence(data, getNeqSeqs(seq1, a, len, seq2, b, rev).get(1), s);
//								Move.testSOutput(new int[] {a,b,len,rev?1:0}, new int[] {m1,m2}, s, Utility.compareGe((testCost2+testCost1),Utility.upperBound)?Utility.upperBound:testCost2+testCost1,Utility.compareGe(deltaCost+s.cost[m1]+s.cost[m2], Utility.upperBound)?Utility.upperBound:deltaCost+s.cost[m1]+s.cost[m2]);
//								Move.testSOutput(new int[] {a,b,len,rev?1:0}, new int[] {m1,m2}, s, testCost2+testCost1,Utility.compareGe(deltaCost+s.cost[m1]+s.cost[m2], Utility.upperBound)?Utility.upperBound:deltaCost+s.cost[m1]+s.cost[m2]);
								
								// 2026-05-15:
								// savedOperations 是后续排序并贪婪提交的候选池，不应只记录刷新全局 best 的候选。
								// 如果某个有向机器对存在改进但未刷新全局 best，它也必须进入候选池；
								// 否则 commit 末尾会把这个有向机器对误写入 noImprovedSeqPair，后续直接跳过。
								if (Utility.compareLt(deltaCost, 0)) {
									savedOperations.add(new OptCost(m1, m2, a, len, b, rev, deltaCost));
								}
								// 2026-05-15:
								// len 表示片段长度减 1。len=0 时只有一个任务，reverse 与 normal 等价，
								// 因此可以跳过第二个方向；len>=1 时片段至少有两个任务，反向插入是不同候选，不能跳过。
								if(len==0) break;
							}

						}
					}

				}
			}
		}

	}

public ArrayList<ArrayList<Integer>> getNeqSeqs(ArrayList<Integer>s1,int from1,int len,ArrayList<Integer> s2,int from2,boolean rev1){
		ArrayList<ArrayList<Integer>> seqs=new ArrayList<ArrayList<Integer>>();
		s1=new ArrayList<Integer>(s1);
		s2=new ArrayList<Integer>(s2);
//		System.out.println(s1.size()+" "+from1+" "+(from1+len+1));
		List<Integer> slice = new ArrayList<>(s1.subList(from1, from1 + len + 1));
		if (rev1)
			Collections.reverse(slice);
		s1.subList(from1, from1 + len + 1).clear();
		s2.addAll(from2 + 1, slice);
		seqs.add(s1);
		seqs.add(s2);
		return seqs;
	}

	
	@Override
	public boolean commit() {
		
		boolean imp = false;
		if (savedOperations.size() == 0)
			return false;
		imp = true;
		Collections.sort(savedOperations);
		boolean[] coverdMachines = new boolean[data.m];
		boolean[][] improvedPair = new boolean[data.m][data.m];

		for (OptCost operation : savedOperations) {
			improvedPair[operation.m1][operation.m2] = true;
		}
		// 2026-05-15:
		// Path insertion 的 pair 是有向的，缓存也保持有向。
		// 缓存必须写在序列修改之前，保证记录的是刚刚完整搜索过的旧序列 pair。
		for (int m1 = 0; m1 < data.m; m1++) {
			for (int m2 = 0; m2 < data.m; m2++) {
				if (m1 == m2)
					continue;
				if (!improvedPair[m1][m2]) {
					noImprovedSeqPair.add(List.of(List.copyOf(s.sequences.get(m1)), List.copyOf(s.sequences.get(m2))));
				}
			}
		}

		for (OptCost operation : savedOperations) {
			boolean allCover = true;
			for (boolean machineCovered : coverdMachines) {
				if (!machineCovered) {
					allCover = false;
					break;
				}
			}
			// 更新noImprovedseqPair

			int m1 = operation.m1;
			int m2 = operation.m2;

			if (allCover) {
				continue;
			}
			if (coverdMachines[m1] || coverdMachines[m2])
				continue;
			coverdMachines[m1] = true;
			coverdMachines[m2] = true;
			int from1 = operation.from1;
			int to2 = operation.to2;
			int len = operation.len;
			boolean rev = operation.rev;

			List<Integer> slice = new ArrayList<>(s.sequences.get(m1).subList(from1, from1 + len + 1));
			if (rev)
				Collections.reverse(slice);
			s.sequences.get(m1).subList(from1, from1 + len + 1).clear();
			s.sequences.get(m2).addAll(to2 + 1, slice);
			s.updateInformationM(m1);
			s.updateInformationM(m2);
		}

		return imp;

	}
	
	
	public boolean mergeCmaxValidation(int m1, int m2,int stM1,int len,int stM2,boolean rev1) {
		ArrayList<Integer> seq1=s.sequences.get(m1);
		ArrayList<Integer> seq2=s.sequences.get(m2);
		// 2026-05-15:
		// 这里不能再依赖“M1 删除一段后只会变小”的旧假设。
		// 当 setup=0 或 setup 严格满足三角不等式时，这个判断通常成立；
		// 但当前随机生成的 sequence-dependent setup 不保证三角不等式，
		// 删除片段后新连的 pre -> post setup 可能比原路径更大。
		// 因此用 cumDurationNormal 和区间 duration 做 O(1) 预验证，
		// 使 Cmax validation 与后续 evalDelta() 中的真实拼接评价保持一致。
		int endM1 = stM1 + len;
		double mergedtimeM1 = stM1 == 0 ? 0 : s.cumDurationNormal[m1][stM1 - 1];
		if (endM1 != seq1.size() - 1) {
			int preJob = stM1 == 0 ? 0 : seq1.get(stM1 - 1);
			int postJob = seq1.get(endM1 + 1);
			mergedtimeM1 += data.s[preJob][postJob] + data.p[postJob]
					+ s.getNormalDuration(m1, endM1 + 1, seq1.size() - 1);
		}
		if (Utility.compareGt(mergedtimeM1, data.CmaxH)) {
			return false;
		}

		double mergedtimeM2=(stM2==-1?0:s.cumDurationNormal[m2][stM2]);
		int fitstJobS2=rev1?seq1.get(stM1+len):seq1.get(stM1);
		int lastJobS2 =rev1?seq1.get(stM1):seq1.get(stM1+len);
		double shift1_2=data.s[stM2==-1?0:seq2.get(stM2)][fitstJobS2]+data.p[fitstJobS2];
		double durationS2=rev1?s.getReverseDuration(m1, stM1, stM1+len):s.getNormalDuration(m1, stM1, stM1+len);
		double shift2_3=stM2==seq2.size()-1?0:data.s[lastJobS2][seq2.get(stM2+1)]+data.p[seq2.get(stM2+1)];
		double duration3=(stM2==seq2.size()-1?0:s.getNormalDuration(m2, stM2+1, seq2.size()-1));
		mergedtimeM2+=shift1_2+durationS2+shift2_3+duration3;
		
//		System.out.println(mergedtimeM2);
		if(Utility.compareGt(mergedtimeM2, data.CmaxH)) {
			return false;
		}
		
		return true;
		
	}

}

class CrossExchangeOperator implements Move {

	private static int LSEG = 20; // 段长与插入距离限制

	static class OptCost implements Comparable<OptCost> {
		int m1;// 机器
		int m2;
		int from1;// 起始位置
		int from2;// 起始位置
		int len1;
		int len2;
		boolean rev1;// 是否反转
		boolean rev2;// 是否反转
		double deltaCost;

		@Override
		public int compareTo(OptCost o) {
			return (int) (this.deltaCost - o.deltaCost);
		}

		public OptCost(int m1, int m2, int from1, int from2, int len1, int len2, boolean rev1, boolean rev2,
				double deltaCost) {
			this.m1 = m1;
			this.m2 = m2;
			this.from1 = from1;
			this.from2 = from2;
			this.len1 = len1;
			this.len2 = len2;
			this.rev1 = rev1;
			this.rev2 = rev2;
			this.deltaCost = deltaCost;
		}
	}

	private ArrayList<OptCost> savedOperations;
	private static boolean allowReverseCrossExchange = true;
	// 默认允许forward和backward的移动
	public Solution s;
	public Data data;
	static HashSet<Set<List<Integer>>> noImprovedSeqPair= new HashSet<Set<List<Integer>>>();; // 对某两个机器当前的序列，应用该算子是否还有用

	public CrossExchangeOperator(Solution s) {
		this.s = s;
		this.data = s.data;
		this.savedOperations = new ArrayList<OptCost>();

	}

	@Override
	public void searchBest() {

		for (int m1 = 0; m1 < data.m; m1++) {
			for (int m2 = m1 + 1; m2 < data.m; m2++) {
				if (noImprovedSeqPair.contains(Set.of(s.sequences.get(m1), s.sequences.get(m2))))
					continue;
				ArrayList<Integer> seq1 = s.sequences.get(m1);
				ArrayList<Integer> seq2 = s.sequences.get(m2);
				for (int a = 0; a < seq1.size(); a++) {
					for (int b = 0; b < seq2.size(); b++) {
						for (int l1 = 0; l1 <= LSEG && a + l1 < seq1.size(); l1++) {
							for (int l2 = 0; l2 <= LSEG && b + l2 < seq2.size(); l2++)
								for (boolean rev1 : allowReverseCrossExchange ? new boolean[] { false, true }
										: new boolean[] { false }) {
									for (boolean rev2 : allowReverseCrossExchange ? new boolean[] { false, true }
											: new boolean[] { false }) {
										double deltaCost = evalDelta(s, m1, m2, a, l1, b, l2, rev1, rev2);
										
//										double testCost1=Move.testSequence(data, getNeqSeqs(seq1, a, l1, seq2, b, l2, rev1, rev2).get(0), s);
//										double testCost2=Move.testSequence(data, getNeqSeqs(seq1, a, l1, seq2, b, l2, rev1, rev2).get(1), s);
//										Move.testSOutput(new int[] {a,l1,b,l2,rev1?1:0,rev2?1:0},new int[] {m1,m2} , s, Utility.compareGe((testCost2+testCost1),Utility.upperBound)?Utility.upperBound:testCost2+testCost1, Utility.compareGe(deltaCost+s.cost[m1]+s.cost[m2], Utility.upperBound)?Utility.upperBound:deltaCost+s.cost[m1]+s.cost[m2]);
//										
//										testCost1=Move.testSequence(data, getNeqSeqs(seq1, a, l1, seq2, b, l2, rev1, rev2).get(0), s);
//										testCost2=Move.testSequence(data, getNeqSeqs(seq1, a, l1, seq2, b, l2, rev1, rev2).get(1), s);
										
									
										// 2026-05-15:
										// 当前 commit 逻辑会对 savedOperations 排序，然后选择互不冲突的机器对提交。
										// 所以这里保存所有改进 cross-exchange 候选，而不是只保存刷新全局最优的候选；
										// 这样 noImprovedSeqPair 才能准确表示“该机器对没有任何改进候选”。
										if (Utility.compareLt(deltaCost, 0)) {
											savedOperations
													.add(new OptCost(m1, m2, a, b, l1, l2, rev1, rev2, deltaCost));

										}
										if(l2==0)break;
									}
									if(l1==0)break;
								}

						}
					}
				}
			}
		}

	}

	@Override
	public boolean commit() {

		boolean imp = false;
		if (savedOperations.size() == 0)
			return false;
		imp = true;
		Collections.sort(savedOperations);
		boolean[] coverdMachines = new boolean[data.m];
		boolean[][] improvedPair = new boolean[data.m][data.m];

		for (OptCost operation : savedOperations) {
			improvedPair[operation.m1][operation.m2] = true;
		}
		// 2026-05-15:
		// Cross exchange 的 pair 是无向的。先用修改前序列写 no-improved 缓存，
		// 再执行批量 commit，避免把本轮 commit 后生成的新 pair 错记为已搜索无改进。
		for (int m1 = 0; m1 < data.m; m1++) {
			for (int m2 = m1 + 1; m2 < data.m; m2++) {
				if (!improvedPair[m1][m2]) {
					noImprovedSeqPair.add(Set.of(List.copyOf(s.sequences.get(m1)), List.copyOf(s.sequences.get(m2))));
				}
			}
		}

		for (OptCost operation : savedOperations) {
			boolean allCover = true;
			for (boolean machineCovered : coverdMachines) {
				if (!machineCovered) {
					allCover = false;
					break;
				}
			}
			// 更新noImprovedseqPair

			int m1 = operation.m1;
			int m2 = operation.m2;

			if (allCover) {
				continue;
			}
			if (coverdMachines[m1] || coverdMachines[m2])
				continue;
			coverdMachines[m1] = true;
			coverdMachines[m2] = true;
			int from1 = operation.from1;
			int from2 = operation.from2;
			int len1 = operation.len1;
			int len2 = operation.len2;
			boolean rev1 = operation.rev1;
			boolean rev2 = operation.rev2;

			ArrayList<Integer> s1 = s.sequences.get(m1);
			ArrayList<Integer> s2 = s.sequences.get(m2);

			List<Integer> slice1 = new ArrayList<>(s1.subList(from1, from1 + len1 + 1));
			if (rev1)
				Collections.reverse(slice1);
			List<Integer> slice2 = new ArrayList<>(s2.subList(from2, from2 + len2 + 1));
			if (rev2)
				Collections.reverse(slice2);
			s1.subList(from1, from1 + len1 + 1).clear();
			s2.subList(from2, from2 + len2 + 1).clear();
			s1.addAll(from1, slice2);
			s2.addAll(from2, slice1);
			s.updateInformationM(m1);
			s.updateInformationM(m2);
		}

		return imp;

	}

	private double evalDelta(Solution s, int m1, int m2, int stM1, int l1, int stM2, int l2, boolean rev1,
			boolean rev2) {
		if(!mergeCmaxValidation(m1, m2, stM1, l1, stM2, l2, rev1, rev2)) return Utility.curUpperBound;//返回upperBound或者big_M应该都行
		ArrayList<Integer> seqM1 = s.sequences.get(m1);
		ArrayList<Integer> seqM2 = s.sequences.get(m2);
		if (stM1 == 0 && stM2 == 0 && l1 == seqM1.size() - 1 && l2 == seqM2.size() - 1&&!rev1&&!rev2)
			return 0;// 此时两个机器序列完全互换，不再往后
		double originalCost = s.cost[m1] + s.cost[m2];
		double costM1 = 0, costM2 = 0;
//    	M1：[stM1,stM1+l1] 和M2：[stM2,stM2+l2] 交换
		// 机器M1上的函数
		PiecewiseLinearFunction f1M1 = stM1 != 0 ? s.fFunctions.get(m1).get(stM1 - 1) : data.penaltyFunction[0];
		PiecewiseLinearFunction fSplitM1 = rev1 ? s.fFunctions_gh[m1][stM1][stM1 + l1]
				: s.fFunctions_hg[m1][stM1][stM1 + l1];
		PiecewiseLinearFunction bSplitM1 = rev1 ? s.bFunctions_gh[m1][stM1][stM1 + l1]
				: s.bFunctions_hg[m1][stM1][stM1 + l1];
		PiecewiseLinearFunction b3M1 = stM1 + l1 != seqM1.size() - 1 ? s.bFunctions.get(m1).get(stM1 + l1 + 1) : data.penaltyFunction[0]; // M1上的第三段

		// 机器M2上的函数
		PiecewiseLinearFunction f1M2 = stM2 != 0 ? s.fFunctions.get(m2).get(stM2 - 1) : data.penaltyFunction[0];
		PiecewiseLinearFunction fSplitM2 = rev2 ? s.fFunctions_gh[m2][stM2][stM2 + l2]
				: s.fFunctions_hg[m2][stM2][stM2 + l2];
		PiecewiseLinearFunction bSplitM2 = rev2 ? s.bFunctions_gh[m2][stM2][stM2 + l2]
				: s.bFunctions_hg[m2][stM2][stM2 + l2];
		PiecewiseLinearFunction b3M2 = stM2 + l2 != seqM2.size() - 1 ? s.bFunctions.get(m2).get(stM2 + l2 + 1) : data.penaltyFunction[0]; // M1上的第三段
		
		// 机器m1拼接
//		if (stM1 == 0) {
//			double shift = data.s[seqM2.get(stM2 + l2)][seqM1.get(stM1 + l1 + 1)] + data.p[seqM1.get(stM1 + l1 + 1)];// stM1+l1+1不可能越界
//			// 此时M1上的拼接为2段拼接
//			costM1 = s.merge2Segments(fSplitM2.setDomain(data.s[0][seqM2.get(stM2)] + data.p[seqM2.get(stM2)], data.Cmax), b3M1, shift);
//		} else if (stM1 + l1 == seqM1.size() - 1) {
//			double shift = data.s[seqM1.get(stM1 - 1)][seqM2.get(stM2)] + data.p[seqM2.get(stM2)];
//			// 此时M1上的拼接为2段拼接
//			costM1 = s.merge2Segments(f1M1, bSplitM2, shift);
//		} else {
		//不再使用2段拼接 2025.5.6
			// 此处M1上被拆的在中间
			double shift1 = data.s[stM1==0?0:seqM1.get(stM1 - 1)][rev2?seqM2.get(stM2+l2):seqM2.get(stM2)] + data.p[rev2?seqM2.get(stM2+l2):seqM2.get(stM2)];
			double shift2 = stM1 + l1==seqM1.size()-1?0:(data.s[rev2?seqM2.get(stM2):seqM2.get(stM2+l2)][seqM1.get(stM1 + l1 + 1)] + data.p[seqM1.get(stM1 + l1 + 1)]);
			double durationSplitM2 = rev2?s.getReverseDuration(m2, stM2, stM2+l2):s.getNormalDuration(m2, stM2, stM2+l2);
			costM1 = s.merge3Segments(f1M1, fSplitM2, bSplitM2, b3M1, shift1, shift2, durationSplitM2);
//		}

		// 机器m2拼接
//		if (stM2 == 0) {
//			double shift = data.s[seqM1.get(stM1 + l1)][seqM2.get(stM2 + l2 + 1)] + data.p[seqM2.get(stM2 + l2 + 1)];// stM1+l1+1不可能越界
//			// 此时M2上的拼接为2段拼接
//			costM2 = s.merge2Segments(fSplitM1.setDomain(data.s[0][seqM1.get(stM1)] + data.p[seqM1.get(stM1)], data.Cmax), b3M2, shift);
//		} else if (stM2 + l2 == seqM2.size() - 1) {
//			double shift = data.s[seqM2.get(stM2 - 1)][seqM1.get(stM1)] + data.p[seqM1.get(stM1)];
//			// 此时M2上的拼接为2段拼接
//			costM2 = s.merge2Segments(f1M2, bSplitM1, shift);
//		} else {
			//不使用2段拼接 2025.5.6
			// 此处M2上被拆的在中间
			// 2026-05-15: 当 M2 被替换片段从首位开始时，M2 前缀为空，插入片段的前驱应为虚拟起点 0。
			// 旧写法在 stM2==0 时取 seqM2.get(0)，setup 非零时会把原首任务误当作前驱。
			shift1 = data.s[stM2==0?0:seqM2.get(stM2 - 1)][rev1?seqM1.get(stM1+l1):seqM1.get(stM1)] + data.p[rev1?seqM1.get(stM1+l1):seqM1.get(stM1)];
			shift2 = stM2 + l2 ==seqM2.size()-1?0:(data.s[rev1?seqM1.get(stM1):seqM1.get(stM1+l1)][seqM2.get(stM2 + l2 + 1)] + data.p[seqM2.get(stM2 + l2 + 1)]);
			double durationSplitM1 =rev1?s.getReverseDuration(m1, stM1, stM1+l1):s.getNormalDuration(m1, stM1, stM1+l1);
			costM2 = s.merge3Segments(f1M2, fSplitM1, bSplitM1, b3M2, shift1, shift2, durationSplitM1);
//		}

		// 2026-05-15: 返回变化成本 delta = 新两机成本 - 原两机成本。
		// 该值小于 0 才表示 cross-exchange 候选能改善当前解。
		return costM1 + costM2 - originalCost;
	}
	public ArrayList<ArrayList<Integer>> getNeqSeqs(ArrayList<Integer>s1,int from1,int len1,ArrayList<Integer> s2,int from2,int len2, boolean rev1,boolean rev2){
		ArrayList<ArrayList<Integer>> seqs=new ArrayList<ArrayList<Integer>>();
		s1=new ArrayList<Integer>(s1);
		s2=new ArrayList<Integer>(s2);
//		System.out.println(s1.size()+" "+from1+" "+(from1+len+1));
		List<Integer> slice1 = new ArrayList<>(s1.subList(from1, from1 + len1 + 1));
		if (rev1)
			Collections.reverse(slice1);
		List<Integer> slice2 = new ArrayList<>(s2.subList(from2, from2 + len2 + 1));
		if (rev2)
			Collections.reverse(slice2);
		s1.subList(from1, from1 + len1 + 1).clear();
		s2.subList(from2, from2 + len2 + 1).clear();
		s1.addAll(from1, slice2);
		s2.addAll(from2, slice1);
		seqs.add(s1);seqs.add(s2);
		return seqs;
	}
	
	public boolean mergeCmaxValidation(int m1, int m2,int stM1,int len1,int stM2,int len2,boolean rev1,boolean rev2) {
		ArrayList<Integer> seq1=s.sequences.get(m1);
		ArrayList<Integer> seq2=s.sequences.get(m2);
		double mergedtimeM1=(stM1==0?0:s.cumDurationNormal[m1][stM1-1]);
		int fitstJobS2M1=rev2?seq2.get(stM2+len2):seq2.get(stM2);
		int lastJobS2M1 =rev2?seq2.get(stM2):seq2.get(stM2+len2);
		double shift1_2M1=data.s[stM1==0?0:seq1.get(stM1-1)][fitstJobS2M1]+data.p[fitstJobS2M1];
		double durationS2M1=rev2?s.getReverseDuration(m2, stM2, stM2+len2):s.getNormalDuration(m2, stM2, stM2+len2);
		double shift2_3M1=(stM1+len1)==seq1.size()-1?0:data.s[lastJobS2M1][seq1.get(stM1+len1+1)]+data.p[seq1.get(stM1+len1+1)];
		double duration3M1=((stM1+len1)==seq1.size()-1?0:s.getNormalDuration(m1, stM1+len1+1, seq1.size()-1));
		mergedtimeM1+=shift1_2M1+durationS2M1+shift2_3M1+duration3M1;
		if(Utility.compareGt(mergedtimeM1, data.CmaxH)) {
			return false;
		}
		
		double mergedtimeM2=(stM2==0?0:s.cumDurationNormal[m2][stM2-1]);
		int fitstJobS2M2=rev1?seq1.get(stM1+len1):seq1.get(stM1);
		int lastJobS2M2 =rev1?seq1.get(stM1):seq1.get(stM1+len1);
		double shift1_2M2=data.s[stM2==0?0:seq2.get(stM2-1)][fitstJobS2M2]+data.p[fitstJobS2M2];
		double durationS2M2=rev1?s.getReverseDuration(m1, stM1, stM1+len1):s.getNormalDuration(m1, stM1, stM1+len1);
		double shift2_3M2=(stM2+len2)==seq2.size()-1?0:data.s[lastJobS2M2][seq2.get(stM2+len2+1)]+data.p[seq2.get(stM2+len2+1)];
		double duration3M2=((stM2+len2)==seq2.size()-1?0:s.getNormalDuration(m2, stM2+len2+1, seq2.size()-1));
		mergedtimeM2+=shift1_2M2+durationS2M2+shift2_3M2+duration3M2;
		
//		System.out.println(mergedtimeM2);
		if(Utility.compareGt(mergedtimeM2, data.CmaxH)) {
			return false;
		}
		
		
		return true;
		
	}


}
//注意，我们这里很多地方的2段拼接，是原文的三段拼接，因为原文的depot是存在成本的，而我们的为0 
//从而我们的fFunctions_hg，当h为0的时候，其实fFunctions[0][g]和函数fFunctions[g]应该是等价的，应该是近似的,fFunctions函数的定义域可能会小一些？
//因为hg这种函数处理的时候hh的初始值开始时间的setup取得是到这个任务的最小，而fFunctions取得是从0过来的
//是否会有什么影响？可能还是会的，这么拼接的时候定义域得限制一下
//之后验证一下，hg这种setDomain是否等价，不等价的话还得再想想
//TODO 空函数检查？是否正确
//应该只需要对f函数重新设置domain，b应该不需要，因为b本身的可行域就是用最小时间做的,可以认为原文中回到depot的时候设置时间和加工时间都为0
