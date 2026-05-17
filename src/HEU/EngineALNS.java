package HEU;


import java.util.*;

import javax.swing.text.html.CSS;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;

/**
 * ALNS (Adaptive Large Neighborhood Search) + VND 框架
 */

//扰动还是很关键的，测试中如果扰动不到位，小规模很容易局部最优
public class EngineALNS {
    private Data data;
    private Solution current;
    private EngineVND vnd;
    private List<RemovalOperator> removals;
    private List<InsertionOperator> insertions;

    public static Random rng = new Random(0);
    public static double defaultRemoveRatioU=0.2;//默认删除上界
    public static double defaultRemoveRatioL=0.05;//默认删除上界
    public static int maxNoImpIterN=40;//最多无改进次数
    public static int maxremRatioChangeN=8;//最多扩大删除率次数
    public static double maxRemRatio=0.6;//最大删除率
    public static int ratioChangeNoImpIterN=15;//连续无改进次数，扩大解扰动
    public static double increaseRate=1.5;//多次无改进，扩大扰动当前解的程度，增大率
    public double curRemoveRatioL;
    public double curRemoveRatioU;

    public EngineALNS(Data data, Solution init) {
        data.configure.updateBestSolution(init);
	this.data = data;
        this.current = init;
        this.curRemoveRatioL=defaultRemoveRatioL;
        this.curRemoveRatioU=defaultRemoveRatioU;
        initOperators();

    }

    /** 初始化所有 Removal/Insertion 算子 */
    private void initOperators() {
        removals = Arrays.asList(
            new RandomRemoval(defaultRemoveRatioU),    // 随机移除 10% 节点
            new WorstRemoval(defaultRemoveRatioU),     // 按惩罚贡献移除最坏的 10%
            new ShawRemoval(defaultRemoveRatioU)         // 基于相似度移除 5 个节点
        );
        insertions = Arrays.asList(
            new GreedyInsertion(),     // 贪心插入
            new RegretKInsertion(2),    // 2-后悔值插入
            new RandomizedInsertion(4)  // 前 5 最优位置中随机
        );
    }

    /** 主搜索过程 */
    public void search() {
        int noImprove = 0;
        int remRatioChangeN=0;//这个参数其实没啥用，不起控制作用,师兄那个是因为还有一个别的共同作用的
        while (noImprove < maxNoImpIterN&&remRatioChangeN<maxremRatioChangeN) {
	Solution tmpSol=current.copy();
	//此处要对这个解重新 M 刷一次，保证ALNS可以对那些差解算出精确值
	Utility.resetCurUpperBound(Utility.big_M);

	// 2026-05-17: ALNS 会接受扰动后的较差解，进入该阶段前必须恢复真实目标值。
	// 不能沿用 VND 阶段可能使用过的局部上界截断值，否则后续外包差分更新会破坏 curCost。
	tmpSol.curCost = tmpSol.calCost();
//        	double test1=Move.testSequence(data,tmpSol.sequences.get(0),tmpSol);
//        	double test2=Move.testSequence(data,tmpSol.sequences.get(1),tmpSol);
//        	System.out.println("当前解成本："+tmpSol.curCost+" "+(test1+test2)+" "+Utility.curUpperBound);

            // 1) 破坏
            RemovalOperator rem = removals.get(rng.nextInt(removals.size()));
            double ratio=EngineALNS.rng.nextDouble(curRemoveRatioL, curRemoveRatioU);
            rem.setRemovedRatio(ratio);
            List<Integer> removed = rem.remove(tmpSol);

            // 2) 修复
            InsertionOperator ins = insertions.get(rng.nextInt(insertions.size()));
            ins.insert(tmpSol, removed);
//            System.out.println("完成ALNS步骤"+" "+rem.getClass().getName()+" "+ins.getClass().getName());
//            System.out.println("删除："+removed);
//            System.out.println(tmpSol.sequences);
            // 3) 局部精炼

            vnd=new EngineVND(data, tmpSol);
            vnd.search();
//            System.out.println("完成VND步骤");
//            System.out.println(tmpSol.sequences);


            // 4) 更新全局最优
            double tmpCost = tmpSol.curCost;
            double bestCost= data.configure.bestSolution.curCost;
            if (Utility.compareLt(tmpCost, bestCost)) {
	 data.configure.updateBestSolution(tmpSol);
                noImprove = 0;
                remRatioChangeN=0;
                curRemoveRatioL=defaultRemoveRatioL;
                curRemoveRatioU=defaultRemoveRatioU;

            } else {
                noImprove++;
                if(noImprove%ratioChangeNoImpIterN==0) {
	double rate=increaseRate;
	if(Utility.compareGe(curRemoveRatioU*(1+increaseRate),maxRemRatio)) {
		rate=maxRemRatio/curRemoveRatioU-1;
		if(!Utility.compareEq(rate, 0.0)) {
			//当到达最大阈值的时候删除率保持不变,直到结束或新解刷新
	curRemoveRatioL*=(1+rate);
	curRemoveRatioU*=(1+rate);
	remRatioChangeN++;
		}

	}

                }
            }

            if(Utility.compareLe(tmpCost, current.curCost)) {
	current=tmpSol;//不做复制
            }
        }

    }
}

// ===== 算子接口 =====
interface RemovalOperator {
    /** 从解中移除若干任务，返回被移除的任务ID列表 */
    List<Integer> remove(Solution s);
    void setRemovedRatio(double frac);

}

interface InsertionOperator {
    /** 将被移除任务重新插入解中 */
    void insert(Solution s, List<Integer> removed);

    public static double evaluateInsertionCost(Solution s,int m, int pos,int job) {
	ArrayList<Integer> seq=s.sequences.get(m);
	PiecewiseLinearFunction f=(pos==-1?s.data.penaltyFunction[0]:s.fFunctions.get(m).get(pos));
	PiecewiseLinearFunction b=(pos==seq.size()-1?s.data.penaltyFunction[0]:s.bFunctions.get(m).get(pos+1));
        int bridgeFrom1 = pos == -1 ? 0 : seq.get(pos);
        double shift1=s.data.s[bridgeFrom1][job]+s.data.p[job];
        int bridgeTo2 = pos == seq.size() - 1 ? 0 : seq.get(pos + 1);
        double shift2=pos==seq.size()-1?0:s.data.s[job][bridgeTo2]+s.data.p[bridgeTo2];
	PiecewiseLinearFunction f1=f.shiftX(shift1).add(s.data.penaltyFunction[job]);
        // 2026-05-15: 插入任务时两条新弧都要同步计入 setup cost。
        // 第一条弧属于插入后的前段函数，第二条桥接弧交给 merge2Segments 处理。
        f1.shiftYInPlace(s.data.getSetupCost(bridgeFrom1, job));
        double mergedCost = s.merge2Segments(f1, b, shift2,
                pos == seq.size() - 1 ? 0.0 : s.data.getSetupCost(job, bridgeTo2));
        if (Move.isInvalidMoveCost(mergedCost)) {
	return Utility.big_M;
        }
        return mergedCost-s.cost[m];
    }

    public static double evaluateOutsourcingCost(Solution s, int job) {
	// 2026-05-16: ALNS repair 把外包当作额外插入候选。
	// 外包成本为 G(B(O))，这里返回把 job 加入当前外包集合的函数差分。
	return s.evaluateOutsourcingAddDelta(job);
    }
}

// ===== Removal 算子实例 =====
// 1. 随机移除
class RandomRemoval implements RemovalOperator {
	private double fraction;

	public RandomRemoval(double fraction) {
		this.fraction = fraction;
	}

	public List<Integer> remove(Solution s) {
		Set<Integer> jobs = new HashSet<Integer>();
		int k = Math.max(1, (int) (s.data.n * fraction));
		int[] removedNum = new int[s.data.m];
		for (int i = 0; i < k; i++) {
			ArrayList<int[]> candidates = new ArrayList<int[]>();
			for (int m = 0; m < s.data.m; m++) {
				// 2026-05-16: 外包模型允许真实机器空闲，这里只跳过已经没有剩余候选任务的机器。
				if (s.sequences.get(m).size() - removedNum[m] <= 0) {
					continue;
				}
				for (int job : s.sequences.get(m)) {
					if (!jobs.contains(job)) {
						candidates.add(new int[] { m, job });
					}
				}
			}
			for (int job : s.outsourcedJobs) {
				if (!jobs.contains(job)) {
					candidates.add(new int[] { -1, job });
				}
			}
			if (candidates.size() == 0) {
				break;
			}
			int[] selected = candidates.get(EngineALNS.rng.nextInt(candidates.size()));
			jobs.add(selected[1]);
			if (selected[0] >= 0) {
				removedNum[selected[0]]++;
			}
		}
		List<Integer> toRemove = new ArrayList<Integer>(jobs);
		s.removeJobs(toRemove);
		return toRemove;
	}

	@Override
	public void setRemovedRatio(double frac) {
		this.fraction=frac;

	}
}

// 2. 最坏移除（按单任务惩罚最高）
class WorstRemoval implements RemovalOperator {
	private double fraction;

	public WorstRemoval(double fraction) {
		this.fraction = fraction;
	}

	public List<Integer> remove(Solution s) {
		Map<Integer, Double> penalties = computeIndividualPenalties(s); // 用户自行实现
		int k = Math.max(1, (int) (penalties.size() * fraction));
		// 这就相当于完全不做迭代了，只基于当前解删除,可能不准,先这样
		List<Integer> removed = removeTopKByValue(s, penalties, k);
		s.removeJobs(removed);
		return removed;
	}

	public Map<Integer, Double> computeIndividualPenalties(Solution s) {
		// 计算基于当前序列，删除某个任务导致的成本变化,删除减小最大的
		TreeMap<Integer, Double> JobDeltaCost = new TreeMap<Integer, Double>();
		for (int m = 0; m < s.data.m; m++) {
			ArrayList<Integer> seq = s.sequences.get(m);
			double costM = s.cost[m];
			if (seq.size() == 1) {
				JobDeltaCost.put(seq.get(0), costM);
				continue;
			}
			for (int i = 0; i < seq.size(); i++) {
				int jid = seq.get(i);
				PiecewiseLinearFunction f = (i == 0 ? s.data.penaltyFunction[0] : s.fFunctions.get(m).get(i - 1));
				PiecewiseLinearFunction b = (i == seq.size() - 1 ? s.data.penaltyFunction[0]
						: s.bFunctions.get(m).get(i + 1));
				int bridgeFrom = i == 0 ? 0 : seq.get(i - 1);
				int bridgeTo = i == seq.size() - 1 ? 0 : seq.get(i + 1);
				double shift = (i == seq.size() - 1 ? 0 : s.data.s[bridgeFrom][bridgeTo] + s.data.p[bridgeTo]);
				double cost = s.merge2Segments(f, b, shift,
						i == seq.size() - 1 ? 0.0 : s.data.getSetupCost(bridgeFrom, bridgeTo));
				JobDeltaCost.put(jid, cost - costM);
			}
		}
		for (int jid : s.outsourcedJobs) {
			JobDeltaCost.put(jid, s.evaluateOutsourcingRemoveDelta(jid));
		}
		return JobDeltaCost;
	}

	public List<Integer> removeTopKByValue(Solution s, Map<Integer, Double> penalties, int k) {
		List<Integer> list = new ArrayList<Integer>(penalties.keySet());
		int[] jobMachine = new int[s.data.n + 1];
		Arrays.fill(jobMachine, -1);
		for (int m = 0; m < s.data.m; m++) {
			for (int job : s.sequences.get(m)) {
				jobMachine[job] = m;
			}
		}
		for (int job : s.outsourcedJobs) {
			jobMachine[job] = -2;
		}
		Collections.sort(list, (o1, o2) -> {
			double v1 = penalties.get(o1);
			double v2 = penalties.get(o2);
			if (Utility.compareLt(v1, v2)) return -1;
			if (Utility.compareGt(v1, v2)) return 1;
			return Integer.compare(o1, o2);
		});
		// 2026-05-16: 外包模型允许真实机器空闲，WorstRemoval 不再强制每台机器保留一个任务。
		int[] removedNum = new int[s.data.m];
		List<Integer> removed = new ArrayList<Integer>();
		int maxRemovable = s.outsourcedJobs.size();
		for (int m = 0; m < s.data.m; m++) {
			maxRemovable += s.sequences.get(m).size();
		}
		int target = Math.min(k, maxRemovable);
		for (int job : list) {
			int m = jobMachine[job];
			if (m == -2) {
				removed.add(job);
				if (removed.size() >= target) {
					break;
				}
				continue;
			}
			if (m < 0) {
				continue;
			}
			if (s.sequences.get(m).size() - removedNum[m] <= 0) {
				continue;
			}
			removed.add(job);
			removedNum[m]++;
			if (removed.size() >= target) {
				break;
			}
		}
		return removed;
	}

	@Override
	public void setRemovedRatio(double frac) {
		this.fraction=frac;

	}
}

// 3. Shaw 相似性移除
class ShawRemoval implements RemovalOperator {
	private double fraction;

	public ShawRemoval(double frac) {
		this.fraction = frac;
	}

	public double taskDistance(int i, int j,Data data) {
		double alpha = 1.0, alpha2 = 1.0, alpha3 = 1.0; // 可调权重
		double alpha4=1.0;double alpha5=1.0;
		return alpha * Math.abs(data.p[i] - data.p[j])
				+ alpha2  * (data.s[i][j] + data.s[j][i])
				+ alpha3* Math.abs(data.d_e[i] - data.d_e[j])+alpha4*Math.abs(data.d_l[i] - data.d_l[j])
				+ alpha5*Math.abs(data.w_e[i] - data.w_e[j])+alpha5*Math.abs(data.w_t[i] - data.w_t[j]);
	}

	//TODO 可以预处理，先不管
	public int findMostSimilar(Solution s,int seed, List<Integer> removed,int[] removedNumM,int[]jobMMap) {
		double bestDist = Double.POSITIVE_INFINITY;
		int bestJob = -1;
		for (Integer job=1;job<=s.data.n;job++) {
			int machine = jobMMap[job];
			if(machine == -1) continue;
			if(machine >= 0) {
				int a1=s.sequences.get(machine).size();
				int a2=removedNumM[machine]+1;
				if(a2>a1) continue;
			}
			if (job == seed || removed.contains(job)) continue;
			double dist = taskDistance(seed, job,s.data);
			if (Utility.compareLt(dist, bestDist)) {
				bestDist = dist;
				bestJob = job;
			}
		}
		return bestJob;
	}

	public List<Integer> remove(Solution s) {
		List<Integer> all = new ArrayList<Integer>();
		for(int i=1;i<=s.data.n;i++) {
			all.add(i);
		}
		int k = Math.max(1,(int)(all.size() * fraction));
		int[] jobMMap=new int[s.data.n+1];
		Arrays.fill(jobMMap, -1);
		int maxRemovable = s.outsourcedJobs.size();
		for (int m = 0; m < s.data.m; m++) {
			maxRemovable += s.sequences.get(m).size();
		}
		k=Math.min(k,maxRemovable);// 2026-05-16: 真实机器允许删空，删除上限就是当前内部任务+外包任务总数。
		for(int m=0;m<s.data.m;m++) {
			for(int jid:s.sequences.get(m)) {
				jobMMap[jid]=m;
			}
		}
		for (int jid : s.outsourcedJobs) {
			jobMMap[jid] = -2;
		}

		int[] removedNumM=new int[s.data.m];
		if (k <= 0) {
			return new ArrayList<Integer>();
		}
		int seed = all.get(EngineALNS.rng.nextInt(all.size()));
		while(jobMMap[seed] == -1) {
			seed = all.get(EngineALNS.rng.nextInt(all.size()));
		}
		List<Integer> removed = new ArrayList<>();
		removed.add(seed);
		if (jobMMap[seed] >= 0) {
			removedNumM[jobMMap[seed]]++;
		}
		while (removed.size() < k) {
			int best = findMostSimilar(s, seed, removed,removedNumM,jobMMap); // 用户定义相似度
			if (best < 0) {
				break;
			}
			removed.add(best);
			if (jobMMap[best] >= 0) {
				removedNumM[jobMMap[best]]++;
			}
		}
		s.removeJobs(removed);
		return removed;
	}

	@Override
	public void setRemovedRatio(double frac) {
		this.fraction=frac;

	}
}

// ===== Insertion 算子实例 =====
// 1. 贪心插入
class GreedyInsertion implements InsertionOperator {
    public void insert(Solution s, List<Integer> removed) {
        for (Integer job : removed) {
            double bestDelta = Double.POSITIVE_INFINITY;
            int bestM = -1, bestPos = -1;
            boolean bestOutsource = false;
            for (int m = 0; m < s.data.m; m++) {
                List<Integer> seq = s.sequences.get(m);
                for (int pos = -1; pos < seq.size(); pos++) {
                    double delta = InsertionOperator.evaluateInsertionCost(s,m, pos, job);
                    if (Utility.compareLt(delta, bestDelta)) {
                        bestDelta = delta;
                        bestM = m;
                        bestPos = pos;
                    }
                }
            }
            double outsourceDelta = InsertionOperator.evaluateOutsourcingCost(s, job);
            if (Utility.compareLt(outsourceDelta, bestDelta)) {
	bestOutsource = true;
            }
            if (bestOutsource) {
	s.addOutsourcedJob(job);
            } else {
	s.insertJob(bestM, bestPos, job);
            }
//            System.out.println("插入任务："+job+" "+bestM+" "+bestPos+" "+bestDelta);
        }
    }


}

// 2. 后悔值插入 (Regret-k)
class RegretKInsertion implements InsertionOperator {
    private int k;
    public RegretKInsertion(int k) { this.k = k; }
    public void insert(Solution s, List<Integer> removed) {
	removed=new ArrayList<Integer>(removed);
        while (!removed.isEmpty()) {
            int bestJob=-1;
            double bestRegret=-1;
            int bestM=0, bestPos=0;
            for (Integer job : removed) {
                // 记录前 k 小的成本

                List<double[]> locs = new ArrayList<>();
                for (int m = 0; m < s.data.m; m++) {
                    for (int pos=-1; pos<s.sequences.get(m).size(); pos++) {
                        double d = InsertionOperator.evaluateInsertionCost(s,m,pos,job);
                        if (!Utility.isBigMValue(d)) {
                            locs.add(new double[]{m,pos,d});
                        }
                    }
                }
                double outsourceDelta = InsertionOperator.evaluateOutsourcingCost(s, job);
                if (!Utility.isBigMValue(outsourceDelta)) {
	locs.add(new double[]{-1,-1,outsourceDelta});
                }
                // 取 k 最小
                Collections.sort(locs,(o1,o2)->{
	if (Utility.compareLt(o1[2], o2[2])) return -1;
	if (Utility.compareGt(o1[2], o2[2])) return 1;
	if ((int)o1[0] != (int)o2[0]) return Integer.compare((int)o1[0], (int)o2[0]);
	return Integer.compare((int)o1[1], (int)o2[1]);
                });//先假设不太可能插入不可行
                double best = locs.get(0)[2];
                double regret = 0;
                for (int i=1;i<Math.min(k,locs.size());i++) {
                    regret += locs.get(i)[2] - best;
                }
                if (Utility.compareGt(regret, bestRegret)) {
                    bestRegret = regret;
                    bestJob = job;
                    bestPos = (int)locs.get(0)[1];
                    bestM=(int)locs.get(0)[0];

                }

            }
//            System.out.println(bestJob+" "+bestM+" "+bestPos);
            // 对 bestJob 做贪心插入
            if (bestM == -1) {
	s.addOutsourcedJob(bestJob);
            } else {
	s.insertJob(bestM, bestPos, bestJob);
            }
            removed.remove(Integer.valueOf(bestJob));
        }
    }

}

// 3. 随机化插入
class RandomizedInsertion implements InsertionOperator {
    private int r;
    public RandomizedInsertion(int r) { this.r = r; }
    public void insert(Solution s, List<Integer> removed) {
        for (Integer job: removed) {
            List<InsertionOption> opts = new ArrayList<>();
            for (int m=0;m<s.data.m;m++)
                for (int pos=-1;pos<s.sequences.get(m).size();pos++) {
                    double d = InsertionOperator.evaluateInsertionCost(s,m,pos,job);
                    if (!Utility.isBigMValue(d)) {
                        opts.add(new InsertionOption(m,pos,d));
                    }
                }
            double outsourceDelta = InsertionOperator.evaluateOutsourcingCost(s, job);
            if (!Utility.isBigMValue(outsourceDelta)) {
	opts.add(new InsertionOption(-1, -1, outsourceDelta));
            }
            Collections.sort(opts, Comparator.comparingDouble(o->o.delta));
            InsertionOption pick = opts.get(EngineALNS.rng.nextInt(Math.min(r,opts.size())));
            if (pick.machine == -1) {
	s.addOutsourcedJob(job);
            } else {
	s.insertJob(pick.machine,pick.position, job);
            }
        }
    }
}

class InsertionOption { int machine, position; double delta;
    InsertionOption(int m,int p,double d){machine=m;position=p;delta=d;}
}

