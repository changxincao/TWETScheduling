package HEU;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import Basic.ArcFlowModel;
import Basic.Data;
import Common.Utility;
import ilog.cplex.IloCplex;

/**
 * 2026-05-17: 小规模随机算例的 seed 差异诊断工具。
 * <p>
 * 这个类不参与正式求解流程，只用于复现某个 case 下不同随机起点的最终解结构，
 * 方便判断启发式停在局部最优时，和精确最优解相比到底差在哪里。
 */
public class HeuristicSeedDiagnosis {
	private static final int RESTARTS = 12;
	private static final int CASE_ID = 38;

	public static void main(String[] args) throws Exception {
		new HeuristicSeedDiagnosis().run();
	}

	private void run() throws Exception {
		Data exactData = SmallExactHeuristicBatchTest.buildRandomCase(CASE_ID, 6 + CASE_ID % 3, 2);
		ExactSnapshot exact = solveExact(exactData);
		System.out.println("case=" + CASE_ID + ", exactObj=" + exact.objective
				+ ", exactSequences=" + exact.sequences + ", exactOutsourced=" + exact.outsourced);

		SeedSnapshot best = null;
		SeedSnapshot worst = null;
		for (int restart = 0; restart < RESTARTS; restart++) {
			long seed = 202605170000L + CASE_ID * 1000L + restart;
			SeedSnapshot snapshot = runSeed(seed, false);
			if (best == null || Utility.compareLt(snapshot.objective, best.objective)) {
				best = snapshot;
			}
			if (worst == null || Utility.compareGt(snapshot.objective, worst.objective)) {
				worst = snapshot;
			}
			System.out.println(snapshot);
		}

		System.out.println("bestSeedSnapshot=" + best);
		System.out.println("worstSeedSnapshot=" + worst);
		printCandidateCosts("bad", listOf(listOf(5, 7, 4, 6), listOf(3, 1, 8)), listOf(2));
		printCandidateCosts("opt", listOf(listOf(5, 7, 8), listOf(3, 2, 4, 6)), listOf(1));
		printCandidateCosts("onlySwapOutsource1And2", listOf(listOf(5, 7, 4, 6), listOf(3, 2, 8)), listOf(1));
		printCandidateCosts("onlyMachineCrossTail", listOf(listOf(5, 7, 8), listOf(3, 1, 4, 6)), listOf(2));
		printCandidateCosts("move8ToFirstMachineOnly", listOf(listOf(5, 7, 8, 4, 6), listOf(3, 1)), listOf(2));
		printCandidateCosts("move46ToSecondMachineOnly", listOf(listOf(5, 7), listOf(3, 1, 4, 6, 8)), listOf(2));
		SeedSnapshot stronger = runSeed(worst.seed, true);
		System.out.println("worstSeedWithStrongerALNS=" + stronger);
	}

	private void printCandidateCosts(String name, ArrayList<ArrayList<Integer>> sequences, ArrayList<Integer> outsourced)
			throws Exception {
		Data data = SmallExactHeuristicBatchTest.buildRandomCase(CASE_ID, 6 + CASE_ID % 3, 2);
		Solution solution = new Solution(data);
		solution.setSequence(sequences);
		solution.initialize_function();
		for (int m = 0; m < data.m; m++) {
			solution.updateInformationM(m);
		}
		solution.addOutsourcedJobs(outsourced);
		System.out.println("candidate=" + name + ", cost=" + solution.curCost
				+ ", sequences=" + sequences + ", outsourced=" + outsourced);
	}

	private static ArrayList<Integer> listOf(int... jobs) {
		ArrayList<Integer> list = new ArrayList<Integer>();
		for (int job : jobs) {
			list.add(job);
		}
		return list;
	}

	@SafeVarargs
	private static ArrayList<ArrayList<Integer>> listOf(ArrayList<Integer>... sequences) {
		ArrayList<ArrayList<Integer>> list = new ArrayList<ArrayList<Integer>>();
		for (ArrayList<Integer> seq : sequences) {
			list.add(seq);
		}
		return list;
	}

	private ExactSnapshot solveExact(Data data) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		ArcFlowModel model = new ArcFlowModel(data);
		model.cplex.setOut(null);
		model.cplex.setWarning(null);
		model.cplex.setParam(IloCplex.DoubleParam.TiLim, 60);
		if (!model.solve()) {
			model.end();
			throw new AssertionError("ArcFlowModel did not solve diagnostic case.");
		}
		ArrayList<ArrayList<Integer>> sequences = new ArrayList<ArrayList<Integer>>();
		for (ArrayList<Utility.TaskInfo> machine : model.extractTaskSchedules()) {
			ArrayList<Integer> seq = new ArrayList<Integer>();
			for (Utility.TaskInfo task : machine) {
				seq.add(task.job);
			}
			sequences.add(seq);
		}
		ArrayList<Integer> outsourced = model.extractOutsourcedJobs();
		double objective = model.getObjective();
		model.end();
		return new ExactSnapshot(objective, sequences, outsourced);
	}

	private SeedSnapshot runSeed(long seed, boolean strongerALNS) throws Exception {
		Data data = SmallExactHeuristicBatchTest.buildRandomCase(CASE_ID, 6 + CASE_ID % 3, 2);
		int oldMaxNoImp = EngineALNS.maxNoImpIterN;
		int oldMaxRatioChange = EngineALNS.maxremRatioChangeN;
		int oldRatioChangeNoImp = EngineALNS.ratioChangeNoImpIterN;
		double oldRatioL = EngineALNS.defaultRemoveRatioL;
		double oldRatioU = EngineALNS.defaultRemoveRatioU;
		double oldMaxRatio = EngineALNS.maxRemRatio;
		try {
			if (strongerALNS) {
				EngineALNS.maxNoImpIterN = 160;
				EngineALNS.maxremRatioChangeN = 8;
				EngineALNS.ratioChangeNoImpIterN = 12;
				EngineALNS.defaultRemoveRatioL = 0.20;
				EngineALNS.defaultRemoveRatioU = 0.60;
				EngineALNS.maxRemRatio = 1.0;
			} else {
				EngineALNS.maxNoImpIterN = 24;
				EngineALNS.maxremRatioChangeN = 4;
				EngineALNS.ratioChangeNoImpIterN = 8;
			}
			Utility.resetCurUpperBound(Utility.big_M);
			Utility.rng = new Random(seed);
			EngineALNS.rng = new Random(seed ^ 0x5DEECE66DL);
			Move.Recordreset();
			data.configure = new Common.Configure();

			Solution solution = new Solution(data);
			solution.setInitSolution();
			solution.initialize_function();
			for (int m = 0; m < data.m; m++) {
				solution.updateInformationM(m);
			}
			data.configure.updateBestSolution(solution);

			EngineALNS engine = new EngineALNS(data, solution);
			engine.search();
			Solution best = data.configure.bestSolution;
			// 复跑一次 VND，确认这个 seed 的 best 在当前邻域下已经没有一步改进。
			double before = best.curCost;
			EngineVND vnd = new EngineVND(data, best);
			vnd.search();
			double after = best.curCost;
			return new SeedSnapshot(seed, strongerALNS, after, after - before,
					best.getSequencesCopy(), best.getOutsourcedJobsCopy());
		} finally {
			EngineALNS.maxNoImpIterN = oldMaxNoImp;
			EngineALNS.maxremRatioChangeN = oldMaxRatioChange;
			EngineALNS.ratioChangeNoImpIterN = oldRatioChangeNoImp;
			EngineALNS.defaultRemoveRatioL = oldRatioL;
			EngineALNS.defaultRemoveRatioU = oldRatioU;
			EngineALNS.maxRemRatio = oldMaxRatio;
		}
	}

	private static final class ExactSnapshot {
		final double objective;
		final List<ArrayList<Integer>> sequences;
		final List<Integer> outsourced;

		ExactSnapshot(double objective, List<ArrayList<Integer>> sequences, List<Integer> outsourced) {
			this.objective = objective;
			this.sequences = sequences;
			this.outsourced = outsourced;
		}
	}

	private static final class SeedSnapshot {
		final long seed;
		final boolean strongerALNS;
		final double objective;
		final double extraVndDelta;
		final List<ArrayList<Integer>> sequences;
		final List<Integer> outsourced;

		SeedSnapshot(long seed, boolean strongerALNS, double objective, double extraVndDelta,
				List<ArrayList<Integer>> sequences, List<Integer> outsourced) {
			this.seed = seed;
			this.strongerALNS = strongerALNS;
			this.objective = objective;
			this.extraVndDelta = extraVndDelta;
			this.sequences = sequences;
			this.outsourced = outsourced;
		}

		@Override
		public String toString() {
			return "SeedSnapshot{seed=" + seed + ", strongerALNS=" + strongerALNS
					+ ", objective=" + objective + ", extraVndDelta=" + extraVndDelta
					+ ", sequences=" + sequences + ", outsourced=" + outsourced + "}";
		}
	}
}
