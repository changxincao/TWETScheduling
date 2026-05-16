package HEU;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;

/**
 * 2026-05-16: 外包局部搜索 move 的一致性测试。
 * <p>
 * 目的不是验证启发式一定找到全局最优，而是验证外包 move 中的快速评价：
 * 机器侧用 merge2Segments/merge3Segments 拼接得到的新成本，应与慢速
 * Move.testSequence(newSeq) 对同一条新机器序列的重算结果一致。
 * 外包侧 G(B(O)) 的增量由 Solution.evaluateOutsourcingDelta(...) 单独测试。
 */
public class OutsourcingMoveConsistencyTest {
	private static final double TOL = 1e-6;
	private static final int LSEG = 4;
	private int checked;

	public static void main(String[] args) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		OutsourcingMoveConsistencyTest test = new OutsourcingMoveConsistencyTest();
		test.run();
	}

	private void run() throws Exception {
		Data data = buildData();
		Solution s = buildSolutionWithOutsourcing(data);
		checkMachineToOutsource(s);
		checkOutsourceToMachine(s);
		checkCrossExchangeWithOutsourcing(s);
		checkOutsourcingDelta(s);
		checkMachineCanBecomeEmptyByOutsourcing();
		System.out.println("OutsourcingMoveConsistencyTest passed, checked=" + checked);
	}

	private Data buildData() throws IOException {
		// Data.debug_set() 当前会把 n 固定为 60。这里不直接读取文件中的 SETUP 块，
		// 避免 wet100 文件在第 100 个任务之后才出现 SETUP 时被误判格式错误；
		// 测试需要的 setup time/setup cost 在下方手工注入。
		Data data = new Data("data/100-2/wet100_001_2m.dat", false, true);
		Random random = new Random(20260516L);
		for (int i = 0; i <= data.n; i++) {
			for (int j = 0; j <= data.n; j++) {
				data.s[i][j] = i == j ? 0 : random.nextInt(6);
				data.setupCost[i][j] = i == j ? 0 : 1 + random.nextInt(9);
			}
		}
		data.setCmax();
		data.setImprovedCmax();
		Arrays.fill(data.outsourcingCost, 10.0);
		data.outsourcingCost[0] = 0;
		data.outsourcingCostFunction = new PiecewiseLinearFunction(0, data.n * 20.0);
		// 线性 G(B)=0.8B+3，测试时既覆盖函数差分，也避免默认 big_M 禁止外包。
		data.outsourcingCostFunction.addSegment(0, data.n * 20.0, 0.8, 3.0);
		return data;
	}

	private Solution buildSolutionWithOutsourcing(Data data) {
		Solution s = new Solution(data);
		s.setInitSolution();
		s.initialize_function();
		for (int m = 0; m < data.m; m++) {
			s.updateInformationM(m);
		}

		ArrayList<Integer> outsourced = new ArrayList<Integer>();
		for (int m = 0; m < data.m && outsourced.size() < 8; m++) {
			ArrayList<Integer> seq = s.sequences.get(m);
			int take = Math.min(3, Math.max(0, seq.size() - 2));
			for (int k = 0; k < take && outsourced.size() < 8; k++) {
				outsourced.add(seq.remove(seq.size() - 1));
			}
		}
		s.outsourcedJobs.clear();
		s.outsourcingBaselineTotal = 0;
		s.outsourcingCostTotal = 0;
		s.addOutsourcedJobs(outsourced);

		Arrays.fill(s.cost, 0);
		s.curCost = s.outsourcingCostTotal;
		s.initialize_function();
		for (int m = 0; m < data.m; m++) {
			s.updateInformationM(m);
		}
		return s;
	}

	private void checkMachineToOutsource(Solution s) {
		for (int m = 0; m < s.data.m; m++) {
			ArrayList<Integer> seq = s.sequences.get(m);
			for (int from = 0; from < seq.size(); from++) {
				int maxLen = Math.min(LSEG, seq.size() - from);
				for (int len = 1; len <= maxLen; len++) {
					ArrayList<Integer> newSeq = new ArrayList<Integer>(seq);
					newSeq.subList(from, from + len).clear();
					boolean fastFeasible = OutsourcingMoveEvaluator.isRemoveCmaxFeasible(s, m, from, len);
					boolean slowFeasible = isCmaxFeasible(s.data, newSeq);
					assertEquals(fastFeasible, slowFeasible, "remove Cmax mismatch");
					if (fastFeasible) {
						double fast = OutsourcingMoveEvaluator.evaluateRemoveMachineCost(s, m, from, len);
						double slow = Move.testSequence(s.data, newSeq, s);
						assertClose(fast, slow, "machine->outsource cost mismatch");
					}
					checked++;
				}
			}
		}
	}

	private void checkOutsourceToMachine(Solution s) {
		OutsourcingMoveEvaluator.OutsourcedSegmentCache cache = new OutsourcingMoveEvaluator.OutsourcedSegmentCache(s,
				LSEG);
		ArrayList<Integer> out = s.outsourcedJobs;
		for (int from = 0; from < out.size(); from++) {
			int maxLen = Math.min(LSEG, out.size() - from);
			for (int len = 1; len <= maxLen; len++) {
				for (boolean reverse : len == 1 ? new boolean[] { false } : new boolean[] { false, true }) {
					OutsourcingMoveEvaluator.SegmentProfile profile = cache.get(from, len, reverse);
					for (int m = 0; m < s.data.m; m++) {
						ArrayList<Integer> seq = s.sequences.get(m);
						for (int pos = -1; pos < seq.size(); pos++) {
							ArrayList<Integer> newSeq = new ArrayList<Integer>(seq);
							newSeq.addAll(pos + 1, profile.jobs);
							boolean fastFeasible = OutsourcingMoveEvaluator.isInsertCmaxFeasible(s, m, pos, profile);
							boolean slowFeasible = isCmaxFeasible(s.data, newSeq);
							assertEquals(fastFeasible, slowFeasible, "insert Cmax mismatch");
							if (fastFeasible) {
								double fast = OutsourcingMoveEvaluator.evaluateInsertMachineCost(s, m, pos, profile);
								double slow = Move.testSequence(s.data, newSeq, s);
								assertClose(fast, slow, "outsource->machine cost mismatch");
							}
							checked++;
						}
					}
				}
			}
		}
	}

	private void checkCrossExchangeWithOutsourcing(Solution s) {
		OutsourcingMoveEvaluator.OutsourcedSegmentCache cache = new OutsourcingMoveEvaluator.OutsourcedSegmentCache(s,
				LSEG);
		ArrayList<Integer> out = s.outsourcedJobs;
		for (int m = 0; m < s.data.m; m++) {
			ArrayList<Integer> seq = s.sequences.get(m);
			for (int mf = 0; mf < seq.size(); mf++) {
				int maxMLen = Math.min(LSEG, seq.size() - mf);
				for (int mLen = 1; mLen <= maxMLen; mLen++) {
					for (int of = 0; of < out.size(); of++) {
						int maxOLen = Math.min(LSEG, out.size() - of);
						for (int oLen = 1; oLen <= maxOLen; oLen++) {
							for (boolean reverse : oLen == 1 ? new boolean[] { false } : new boolean[] { false, true }) {
								OutsourcingMoveEvaluator.SegmentProfile profile = cache.get(of, oLen, reverse);
								ArrayList<Integer> newSeq = new ArrayList<Integer>(seq);
								newSeq.subList(mf, mf + mLen).clear();
								newSeq.addAll(mf, profile.jobs);
								boolean fastFeasible = OutsourcingMoveEvaluator.isReplaceCmaxFeasible(s, m, mf, mLen,
										profile);
								boolean slowFeasible = isCmaxFeasible(s.data, newSeq);
								assertEquals(fastFeasible, slowFeasible, "exchange Cmax mismatch");
								if (fastFeasible) {
									double fast = OutsourcingMoveEvaluator.evaluateReplaceMachineCost(s, m, mf, mLen,
											profile);
									double slow = Move.testSequence(s.data, newSeq, s);
									assertClose(fast, slow, "machine<->outsource cost mismatch");
								}
								checked++;
							}
						}
					}
				}
			}
		}
	}

	private void checkOutsourcingDelta(Solution s) {
		List<Integer> remove = s.outsourcedJobs.subList(0, Math.min(2, s.outsourcedJobs.size()));
		List<Integer> add = new ArrayList<Integer>();
		for (int m = 0; m < s.data.m && add.size() < 2; m++) {
			for (int job : s.sequences.get(m)) {
				add.add(job);
				if (add.size() == 2) {
					break;
				}
			}
		}
		double oldCost = s.outsourcingCostTotal;
		double expectedBaseline = s.outsourcingBaselineTotal - s.getOutsourcingBaseline(remove)
				+ s.getOutsourcingBaseline(add);
		double expected = s.data.evaluateOutsourcingCost(expectedBaseline) - oldCost;
		assertClose(expected, s.evaluateOutsourcingDelta(remove, add), "outsourcing G(B) delta mismatch");
		checked++;
	}

	private void checkMachineCanBecomeEmptyByOutsourcing() throws IOException {
		Data data = buildData();
		Arrays.fill(data.outsourcingCost, 0.0);
		data.outsourcingCostFunction = new PiecewiseLinearFunction(0, data.n);
		data.outsourcingCostFunction.addSegment(0, data.n, 0.0, 0.0);

		Solution s = new Solution(data);
		s.sequences.get(0).add(1);
		s.initialize_function();
		for (int m = 0; m < data.m; m++) {
			s.updateInformationM(m);
		}

		double oldCost = s.curCost;
		OutsourcingPathInsertOperator op = new OutsourcingPathInsertOperator(s);
		op.searchBest();
		boolean committed = op.commit();
		if (!committed) {
			throw new AssertionError("machine->outsourcing should allow deleting the last internal job");
		}
		if (!s.sequences.get(0).isEmpty()) {
			throw new AssertionError("machine 0 should become empty after outsourcing its only job");
		}
		if (!s.outsourcedJobs.contains(1)) {
			throw new AssertionError("job 1 should be moved to outsourcing");
		}
		if (!Utility.compareLt(s.curCost, oldCost) && Math.abs(s.curCost - oldCost) > TOL) {
			throw new AssertionError("zero-cost outsourcing should not increase solution cost");
		}
		checked++;
	}

	private boolean isCmaxFeasible(Data data, ArrayList<Integer> sequence) {
		if (sequence.isEmpty()) {
			return true;
		}
		double total = data.s[0][sequence.get(0)] + data.p[sequence.get(0)];
		for (int i = 1; i < sequence.size(); i++) {
			total += data.s[sequence.get(i - 1)][sequence.get(i)] + data.p[sequence.get(i)];
		}
		return !Utility.compareGt(total, data.CmaxH);
	}

	private void assertClose(double expected, double actual, String message) {
		if (Math.abs(expected - actual) > TOL && !(Utility.isBigMValue(expected) && Utility.isBigMValue(actual))) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private void assertEquals(boolean expected, boolean actual, String message) {
		if (expected != actual) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
