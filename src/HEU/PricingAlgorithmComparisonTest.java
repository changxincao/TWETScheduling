package HEU;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.GC.BidirectionalPricingEngine;
import TWETBPC.GC.InitialColumnBuilder;
import TWETBPC.GC.InitialColumnBundle;
import TWETBPC.GC.PaperDominanceExactPricingEngine;
import TWETBPC.GC.PricingResult;
import TWETBPC.IO.HeuristicSeedProvider;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.Pool;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/**
 * 2026-05-20: 单向 exact pricing 和双向 exact pricing 的逐轮对拍诊断。
 * <p>
 * 这个类不参与正式 BPC 求解，只用于验证同一个 RMP dual 下，两种 exact pricing
 * 返回的最优负 reduced-cost 列是否一致，并记录各自耗时。每轮比较后把两边新列的并集加入
 * 当前 RMP，再进入下一轮，从而避免两套算法各自推进导致 dual 不可比。
 */
public class PricingAlgorithmComparisonTest {

	private static final int CASES = 3;
	private static final int MAX_ROUNDS = 80;
	private static final double RC_TOL = 1e-5;

	public static void main(String[] args) throws Exception {
		new PricingAlgorithmComparisonTest().run();
	}

	private void run() throws Exception {
		Path outputDir = Path.of("test-results", "bpc");
		Files.createDirectories(outputDir);
		Path csv = outputDir.resolve("2026-05-20-pricing-comparison.csv");

		int comparedRounds = 0;
		int matchedRounds = 0;
		long forwardMillisTotal = 0L;
		long bidirectionalMillisTotal = 0L;

		try (BufferedWriter writer = Files.newBufferedWriter(csv)) {
			writer.write(
					"case_id,round,lp_obj,restricted_cols,pool_size,forward_cols,bidirectional_cols,forward_best_rc,bidirectional_best_rc,rc_diff,match,forward_ms,bidirectional_ms,added_union,forward_best_seq,bidirectional_best_seq\n");
			for (int caseId = 0; caseId < CASES; caseId++) {
				Data data = SmallExactHeuristicBatchTest.buildRandomCase(3000 + caseId, 5 + caseId, 2);
				ComparisonRun run = runCase(data, caseId, writer);
				comparedRounds += run.rounds;
				matchedRounds += run.matchedRounds;
				forwardMillisTotal += run.forwardMillis;
				bidirectionalMillisTotal += run.bidirectionalMillis;
			}
		}

		double avgForward = comparedRounds == 0 ? 0.0 : forwardMillisTotal / (double) comparedRounds;
		double avgBidirectional = comparedRounds == 0 ? 0.0 : bidirectionalMillisTotal / (double) comparedRounds;
		System.out.printf(
				"PricingAlgorithmComparisonTest finished: matchedRounds=%d/%d, avgForwardMs=%.2f, avgBidirectionalMs=%.2f, csv=%s%n",
				matchedRounds, comparedRounds, avgForward, avgBidirectional, csv);
		if (matchedRounds != comparedRounds) {
			throw new AssertionError("Forward and bidirectional pricing best reduced costs differ. See " + csv);
		}
	}

	private ComparisonRun runCase(Data data, int caseId, BufferedWriter writer) throws Exception {
		Utility.resetCurUpperBound(Utility.big_M);
		TWETBPCConfig config = buildConfig(caseId);
		Pool pool = new Pool(data);
		InitialColumnBuilder initialBuilder = new InitialColumnBuilder(data, config, pool,
				new HeuristicSeedProvider(data, config));
		InitialColumnBundle bundle = initialBuilder.build();
		Node root = new Node(data, bundle.getInitialColumnIds(), bundle.getIncumbentColumnIds(), config.pseudoCostInf);
		root.id = 1;

		LP lp = new LP(data, pool, new CutPool());
		lp.construct(root, bundle.getInitialColumnIds());
		TWETMasterSolution solution = lp.solveRelaxation();
		if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
			throw new AssertionError("Initial RMP infeasible in pricing comparison case " + caseId);
		}

		PaperDominanceExactPricingEngine forward = new PaperDominanceExactPricingEngine(data, config);
		BidirectionalPricingEngine bidirectional = new BidirectionalPricingEngine(data, config);
		ComparisonRun run = new ComparisonRun();

		for (int round = 0; round < MAX_ROUNDS; round++) {
			long forwardStart = System.nanoTime();
			PricingResult forwardResult = forward.price(lp);
			long forwardMillis = (System.nanoTime() - forwardStart) / 1_000_000L;

			long bidirectionalStart = System.nanoTime();
			PricingResult bidirectionalResult = bidirectional.price(lp);
			long bidirectionalMillis = (System.nanoTime() - bidirectionalStart) / 1_000_000L;

			PricingStats forwardStats = PricingStats.from(forwardResult.getColumns(), lp);
			PricingStats bidirectionalStats = PricingStats.from(bidirectionalResult.getColumns(), lp);
			boolean match = bestReducedCostMatch(forwardStats.bestReducedCost, bidirectionalStats.bestReducedCost);
			if (match) {
				run.matchedRounds++;
			}
			run.rounds++;
			run.forwardMillis += forwardMillis;
			run.bidirectionalMillis += bidirectionalMillis;

			LinkedHashSet<Integer> newColumnIds = new LinkedHashSet<Integer>();
			addColumnsToPool(forwardResult.getColumns(), lp, pool, newColumnIds);
			addColumnsToPool(bidirectionalResult.getColumns(), lp, pool, newColumnIds);
			int added = lp.addColumns(new ArrayList<Integer>(newColumnIds));

			writer.write(caseId + "," + round + "," + solution.getObjectiveValue() + ","
					+ lp.getRestrictedColumnIds().size() + "," + pool.size() + "," + forwardStats.columnCount + ","
					+ bidirectionalStats.columnCount + "," + forwardStats.bestReducedCost + ","
					+ bidirectionalStats.bestReducedCost + ","
					+ reducedCostDiff(forwardStats.bestReducedCost, bidirectionalStats.bestReducedCost) + "," + match
					+ "," + forwardMillis + "," + bidirectionalMillis + "," + added + ","
					+ quote(forwardStats.bestSequence) + "," + quote(bidirectionalStats.bestSequence) + "\n");

			System.out.printf(
					"case=%d round=%d lp=%.6f forwardBest=%.6f bidirBest=%.6f match=%s forwardCols=%d bidirCols=%d forwardMs=%d bidirMs=%d added=%d%n",
					caseId, round, solution.getObjectiveValue(), forwardStats.bestReducedCost,
					bidirectionalStats.bestReducedCost, match, forwardStats.columnCount,
					bidirectionalStats.columnCount, forwardMillis, bidirectionalMillis, added);

			if (!match) {
				break;
			}
			if (added == 0) {
				break;
			}
			solution = lp.resolveCurrentModel();
			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				throw new AssertionError("RMP became infeasible after adding pricing columns in case " + caseId);
			}
		}
		return run;
	}

	private TWETBPCConfig buildConfig(int caseId) {
		TWETBPCConfig config = new TWETBPCConfig();
		config.instanceName = "pricing-comparison-" + caseId;
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.runALNSForSeed = false;
		config.enableHeuristicPricing = false;
		config.usePaperDominancePricing = true;
		config.enableBidirectionalPricing = true;
		// 诊断小算例上尽量不让 addin_size 先截断结果，方便比较两种 exact pricing 的最优 reduced cost。
		config.maxExactPricingColumns = 100000;
		config.maxInitialColumns = 2000;
		config.branchSeedColumnLimit = 2000;
		return config;
	}

	private void addColumnsToPool(List<TWETColumn> columns, LP lp, Pool pool, LinkedHashSet<Integer> newColumnIds) {
		for (TWETColumn column : columns) {
			int id = pool.addColumn(column.getSequence(), column.getCost(), column.getSource(), column.isSeedColumn());
			if (!lp.getRestrictedColumnIds().contains(Integer.valueOf(id))) {
				newColumnIds.add(Integer.valueOf(id));
			}
		}
	}

	private static boolean bestReducedCostMatch(double a, double b) {
		if (Double.isInfinite(a) && Double.isInfinite(b)) {
			return true;
		}
		if (Double.isInfinite(a) || Double.isInfinite(b)) {
			return false;
		}
		return Math.abs(a - b) <= RC_TOL;
	}

	private static double reducedCostDiff(double a, double b) {
		if (Double.isInfinite(a) && Double.isInfinite(b)) {
			return 0.0;
		}
		return a - b;
	}

	private static String quote(String value) {
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	private static final class PricingStats {
		final int columnCount;
		final double bestReducedCost;
		final String bestSequence;

		private PricingStats(int columnCount, double bestReducedCost, String bestSequence) {
			this.columnCount = columnCount;
			this.bestReducedCost = bestReducedCost;
			this.bestSequence = bestSequence;
		}

		static PricingStats from(List<TWETColumn> columns, LP lp) {
			double best = Double.POSITIVE_INFINITY;
			String sequence = "";
			for (TWETColumn column : columns) {
				double reducedCost = reducedCost(column, lp);
				if (Utility.compareLt(reducedCost, best)) {
					best = reducedCost;
					sequence = column.getSequence().toString();
				}
			}
			return new PricingStats(columns.size(), best, sequence);
		}

		private static double reducedCost(TWETColumn column, LP lp) {
			double reducedCost = column.getCost() - lp.getMachineDual();
			int prev = 0;
			for (int job : column.getSequence()) {
				reducedCost -= lp.getJobDual(job);
				reducedCost -= lp.getArcDual(prev, job);
				prev = job;
			}
			reducedCost -= lp.getArcDual(prev, lp.getNode().sinkId());
			return reducedCost;
		}
	}

	private static final class ComparisonRun {
		int rounds;
		int matchedRounds;
		long forwardMillis;
		long bidirectionalMillis;
	}
}
