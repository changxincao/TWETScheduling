package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

import Basic.Data;
import Basic.LocalHorizonCmaxSolver;
import Common.Utility;
import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETSolveResult;
import TWETBPC.TWETSolveStatus;
import TWETBPC.TimeLimitChecker;
import TWETBPC.BP.BranchResult;
import TWETBPC.BP.Brancher;
import TWETBPC.BP.StrongBranchingCandidate;
import TWETBPC.GC.CompletionBoundSubtreeArcEliminator;
import TWETBPC.GC.InitialColumnBuilder;
import TWETBPC.GC.InitialColumnBundle;
import TWETBPC.GC.TimeIndexedGraphPricingEngine;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.LP.PC.StrongBranchingTrialResult;

/**
 * 分支树主控制器。
 */
public class Tree {

	private final Data data;
	private final TWETBPCConfig config;
	private final Pool pool;
	private final OutsourcingPool outsourcingPool;
	private final CutPool cutPool;
	private final InitialColumnBuilder initialColumnBuilder;
	private final PC pc;
	private final RestrictedMasterIntegerHeuristic restrictedMasterIntegerHeuristic;
	private final RouteEnumerationEngine routeEnumerationEngine;
	private final RouteEnumerationFiniteMaster routeEnumerationFiniteMaster;
	private final CompletionBoundSubtreeArcEliminator completionBoundSubtreeArcEliminator;
	private final List<Brancher> branchers;
	private final BPCTraceSink traceSink;

	public Tree(Data data, TWETBPCConfig config, Pool pool, OutsourcingPool outsourcingPool, CutPool cutPool,
			InitialColumnBuilder initialColumnBuilder, PC pc, List<Brancher> branchers, BPCTraceSink traceSink) {
		this.data = data;
		this.config = config;
		this.pool = pool;
		this.outsourcingPool = outsourcingPool;
		this.cutPool = cutPool;
		this.initialColumnBuilder = initialColumnBuilder;
		this.pc = pc;
		this.restrictedMasterIntegerHeuristic = new RestrictedMasterIntegerHeuristic(data, config);
		this.routeEnumerationEngine = new RouteEnumerationEngine(data, config);
		this.routeEnumerationFiniteMaster = new RouteEnumerationFiniteMaster(data, config);
		this.completionBoundSubtreeArcEliminator = new CompletionBoundSubtreeArcEliminator(data, config);
		this.branchers = branchers;
		this.traceSink = traceSink;
	}

	public TWETSolveResult solve() {
		long solveStartNanos = System.nanoTime();
		TimeLimitChecker timeLimitChecker = new TimeLimitChecker() {
			@Override
			public boolean isTimeLimitReached() {
				return Tree.this.isSolveTimeLimitReached(solveStartNanos);
			}
		};
		pc.setTimeLimitChecker(timeLimitChecker);
		heartbeat(null, "initialColumnBuilder.start");
		InitialColumnBundle initial = initialColumnBuilder.build();
		heartbeat(null, "initialColumnBuilder.done");
		Node root = new Node(data, initial.getInitialColumnIds(), initial.getIncumbentColumnIds(), config.pseudoCostInf);
		seedInitialOutsourcingColumn(root, initial);
		traceSink.onInitialColumnsReady(initial.getInitialColumnIds().size(), initial.getIncumbentColumnIds().size(),
				incumbentCostFromInitial(initial));

		PriorityQueue<Node> queue = new PriorityQueue<Node>();
		queue.add(root);

		double incumbentCost = data.configure.bestSolution == null ? Double.POSITIVE_INFINITY
				: data.configure.bestSolution.curCost;
		double bestBound = Double.POSITIVE_INFINITY;
		ArrayList<Integer> incumbentColumnIds = new ArrayList<Integer>(initial.getIncumbentColumnIds());
		double[] incumbentOutsourcingValues = initialOutsourcingValues(initial);
		int processedNodes = 0;
		boolean stoppedByTimeLimit = false;

		while (!queue.isEmpty() && processedNodes < config.maxNodes && !isSolveTimeLimitReached(solveStartNanos)) {
			Node node = queue.poll();
			node.id = ++processedNodes;
			traceSink.onNodePicked(node, queue.size(), totalPoolSize(), cutPool.size());
			heartbeat(node, "node.pick " + node.diagnosticSummary());
			// 2026-06-28: 暂停 node-local horizon 改进调用。这个模块的本意是用当前
			// node 的机器侧约束重新求一个更紧 pricing horizon；但 branch/pricingOnly 禁弧
			// 会让可行顺序集合更强，局部 Cmax 通常只会变差，实测不适合接入主流程。
			// 保留 tryImproveLocalHorizonAtNode() 方法，后续若重新评估再恢复调用。
			// tryImproveLocalHorizonAtNode(node);

			// 2026-05-19: 对齐旧 VRP 的 sudo_cost 预剪枝。root 的 pseudoCost 是占位大数，不能用来剪；
			// 非根节点若 pseudoCost 已不小于 incumbent，由于队列按 pseudoCost 升序，当前节点和剩余节点
			// 都不可能改进，继续构建 RMP / pricing 只会浪费时间，极端情况下还会触发标签/函数包络内存膨胀。
			if (node.depth > 0 && Double.isFinite(incumbentCost)
					&& Utility.compareGe(node.pseudoCost, incumbentCost - config.branchingTolerance)) {
				traceSink.onNodeClosed(node, "pruned_by_pseudo_cost", 0);
				queue.clear();
				break;
			}

			LP lp = new LP(data, pool, cutPool, config, outsourcingPool);
			lp.construct(node, node.seedColumnIds);
			heartbeat(node, "pc.solve.start");
			TWETMasterSolution solution = pc.solve(lp, incumbentCost);

			if (isSolveTimeLimitReached(solveStartNanos)) {
				traceSink.onNodeClosed(node, "time_limit", queue.size());
				stoppedByTimeLimit = true;
				break;
			}
			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				traceSink.onNodeClosed(node, "infeasible_master", queue.size());
				continue;
			}
			if (pc.wasLastNodePrunedByDualBound()) {
				bestBound = updateReportedBound(queue, pc.getLastObservedDualBound(), incumbentCost);
				traceSink.onNodeClosed(node, "pruned_by_dual_bound", queue.size());
				continue;
			}

			if (solution.getStatus() == TWETMasterStatus.LP_RELAXATION) {
				bestBound = updateReportedBound(queue, solution.getObjectiveValue(), incumbentCost);
			}

			boolean incumbentUpdated = false;
			boolean lpIntegerIncumbentUpdated = false;
			if (solution.isInteger() && Utility.compareLt(solution.getObjectiveValue(), incumbentCost)) {
				incumbentCost = solution.getObjectiveValue();
				incumbentColumnIds = new ArrayList<Integer>(solution.getActiveColumnIds());
				incumbentOutsourcingValues = solution.getOutsourcingValues();
				incumbentUpdated = true;
				lpIntegerIncumbentUpdated = true;
				traceSink.onIncumbentUpdated(node, solution, incumbentCost);
			}
			if (!lpIntegerIncumbentUpdated && isSolveTimeLimitReached(solveStartNanos)) {
				traceSink.onMasterSolved(node, solution, lp.getRestrictedColumnIds().size(), lp.getActiveCutIds().size(),
						bestBound, incumbentCost, queue.size(), totalPoolSize(), cutPool.size(), incumbentUpdated);
				traceSink.onNodeClosed(node, "time_limit", queue.size());
				stoppedByTimeLimit = true;
				break;
			}
			if (!solution.isInteger() && config.enableRestrictedMasterIntegerHeuristic) {
				heartbeat(node, "rmih.start");
				RestrictedMasterIntegerHeuristic.Result integerResult = restrictedMasterIntegerHeuristic.solve(lp);
				boolean heuristicImproved = integerResult.isFeasible()
						&& Utility.compareLt(integerResult.getObjective(), incumbentCost);
				traceSink.onRestrictedMasterIntegerHeuristic(node, integerResult.isFeasible(), heuristicImproved,
						integerResult.getObjective(), integerResult.getSelectedColumnIds().size(),
						integerResult.getStatus(), integerResult.getElapsedNanos());
				if (heuristicImproved) {
					incumbentCost = integerResult.getObjective();
					incumbentColumnIds = integerResult.getSelectedColumnIds();
					incumbentOutsourcingValues = integerResult.getOutsourcingValues();
					incumbentUpdated = true;
					traceSink.onIncumbentUpdated(node, integerResult.getSolution(), incumbentCost);
				}
			}

			traceSink.onMasterSolved(node, solution, lp.getRestrictedColumnIds().size(), lp.getActiveCutIds().size(),
					bestBound, incumbentCost, queue.size(), totalPoolSize(), cutPool.size(), incumbentUpdated);
			if (isSolveTimeLimitReached(solveStartNanos)) {
				traceSink.onNodeClosed(node, "time_limit", queue.size());
				stoppedByTimeLimit = true;
				break;
			}

			if (lpIntegerIncumbentUpdated) {
				traceSink.onNodeClosed(node, "integer_incumbent", queue.size());
				continue;
			}
			if (Double.isFinite(incumbentCost) && solution.getStatus() != TWETMasterStatus.NOT_SOLVED
					&& Utility.compareGe(solution.getObjectiveValue(), incumbentCost - config.branchingTolerance)) {
				traceSink.onNodeClosed(node, "pruned_by_incumbent", queue.size());
				continue;
			}

			applyTimeIndexedGraphArcFixing(lp, incumbentCost);
			CompletionBoundSubtreeArcEliminator.Result subtreeArcElimination = null;
			if (!config.useTimeIndexedGraphPricing) {
				heartbeat(node, "subtreeArcElimination.start");
				subtreeArcElimination = evaluateSubtreeArcElimination(lp, incumbentCost, solution.getObjectiveValue());
			}
			if (isSolveTimeLimitReached(solveStartNanos)) {
				traceSink.onNodeClosed(node, "time_limit", queue.size());
				stoppedByTimeLimit = true;
				break;
			}

			CompletionBoundSubtreeArcEliminator.Result enumerationArcElimination =
					(config.bidirectionalCompletionBoundSubtreeArcElimination
							|| config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly)
									? subtreeArcElimination : null;
			RouteEnumerationFiniteMaster.Result enumerationProof =
					tryRouteEnumeration(lp, incumbentCost, solution.getObjectiveValue(), solveStartNanos,
							enumerationArcElimination);
			if (enumerationProof != null && enumerationProof.isProven()) {
				if (!enumerationProof.isInfeasible()
						&& Utility.compareLt(enumerationProof.getObjective(), incumbentCost)) {
					incumbentCost = enumerationProof.getObjective();
					incumbentColumnIds = enumerationProof.getSelectedColumnIds();
					incumbentOutsourcingValues = enumerationProof.getOutsourcingValues();
					traceSink.onIncumbentUpdated(node, enumerationProof.getSolution(), incumbentCost);
				}
				bestBound = updateReportedBound(queue,
						enumerationProof.isInfeasible() ? Double.POSITIVE_INFINITY : enumerationProof.getObjective(),
						incumbentCost);
				traceSink.onNodeClosed(node,
						enumerationProof.isInfeasible() ? "route_enumeration_infeasible"
								: "route_enumeration_finite_master",
						queue.size());
				continue;
			}
			if (isSolveTimeLimitReached(solveStartNanos)) {
				traceSink.onNodeClosed(node, "time_limit", queue.size());
				stoppedByTimeLimit = true;
				break;
			}

			boolean branched = false;
			heartbeat(node, "branch.start");
			for (Brancher brancher : branchers) {
				StrongBranchingSelection strongSelection =
						tryTwoStageStrongBranching(lp, brancher, subtreeArcElimination, solution.getObjectiveValue());
				if (strongSelection != null) {
					if (strongSelection.hasTimeLimitedTrial()) {
						traceSink.onBranch(node, brancher.getName(), strongSelection.traceResult(), queue.size());
						traceSink.onNodeClosed(node, "time_limit", queue.size());
						heartbeat(node, "strongBranching.timeLimit " + strongSelection.summary());
						stoppedByTimeLimit = true;
						branched = true;
						break;
					}
					enqueueStrongBranchingChild(queue, strongSelection.result.getLeftNode(), strongSelection.leftTrial);
					enqueueStrongBranchingChild(queue, strongSelection.result.getRightNode(), strongSelection.rightTrial);
					traceSink.onBranch(node, brancher.getName(), strongSelection.traceResult(), queue.size());
					heartbeat(node, "strongBranching.selected " + strongSelection.summary());
					branched = true;
					break;
				}
				BranchResult result = brancher.branch(lp);
				if (!result.isBranched()) {
					traceSink.onBranchRejected(node, brancher.getName(), result.getMessage());
					continue;
				}
				applySubtreeArcElimination(result, subtreeArcElimination);
				enqueueChild(queue, result.getLeftNode(), lp);
				enqueueChild(queue, result.getRightNode(), lp);
				traceSink.onBranch(node, brancher.getName(), result, queue.size());
				branched = true;
				break;
			}
			if (stoppedByTimeLimit) {
				break;
			}
			if (!branched) {
				traceSink.onNodeClosed(node, "closed_without_branch", queue.size());
			}
		}

		boolean timeLimitReached = isSolveTimeLimitReached(solveStartNanos);
		bestBound = finalBound(queue, incumbentCost, bestBound, stoppedByTimeLimit || timeLimitReached);
		TWETSolveStatus status = finalStatus(processedNodes, queue.isEmpty(), stoppedByTimeLimit, timeLimitReached);
		return new TWETSolveResult(status, incumbentCost, bestBound, processedNodes, totalPoolSize(), incumbentColumnIds,
				incumbentOutsourcingValues,
				"TWET BPC solved with LP RMP and configured pricing engines; advanced cuts/pricing remain pending");
	}

	private int totalPoolSize() {
		return pool.size() + (config.useColumnizedOutsourcing() ? outsourcingPool.size() : 0);
	}

	private RouteEnumerationFiniteMaster.Result tryRouteEnumeration(LP lp, double incumbentCost,
			double nodeLowerBound, long solveStartNanos,
			CompletionBoundSubtreeArcEliminator.Result currentNodeArcElimination) {
		if (!config.enableRouteEnumeration) {
			return null;
		}
		if (isSolveTimeLimitReached(solveStartNanos)) {
			heartbeat(lp.getNode(), "routeEnumeration.skip time limit reached");
			return null;
		}
		heartbeat(lp.getNode(), "routeEnumeration.start");
		RouteEnumerationResult enumeration = routeEnumerationEngine.enumerate(lp, incumbentCost, nodeLowerBound,
				pc.getLastReusableSubtreeArcEliminationBounds(), currentNodeArcElimination);
		heartbeat(lp.getNode(), "routeEnumeration.done " + enumeration.summary());
		if (!enumeration.isAttempted() || !enumeration.isComplete()) {
			return null;
		}
		double remainingSeconds = remainingSolveTimeSeconds(solveStartNanos);
		if (Utility.compareLe(remainingSeconds, 0.0)) {
			heartbeat(lp.getNode(), "routeEnumerationFiniteMaster.skip time limit reached");
			return null;
		}
		heartbeat(lp.getNode(), "routeEnumerationFiniteMaster.start remainingSeconds="
				+ (Double.isInfinite(remainingSeconds) ? "INF" : String.format("%.3f", remainingSeconds)));
		RouteEnumerationFiniteMaster.Result proof =
				routeEnumerationFiniteMaster.solve(lp, enumeration.getFiniteColumnIds(),
						enumeration.getFiniteOutsourcingColumnIds(), remainingSeconds);
		heartbeat(lp.getNode(), "routeEnumerationFiniteMaster.done proven=" + proof.isProven()
				+ ",obj=" + proof.getObjective() + ",msg=" + proof.getMessage()
				+ ",buildMs=" + String.format("%.3f", proof.getBuildNanos() / 1_000_000.0)
				+ ",solveMs=" + String.format("%.3f", proof.getSolveNanos() / 1_000_000.0)
				+ ",extractMs=" + String.format("%.3f", proof.getExtractNanos() / 1_000_000.0)
				+ ",ms=" + String.format("%.3f", proof.getElapsedNanos() / 1_000_000.0));
		return proof;
	}

	private CompletionBoundSubtreeArcEliminator.Result evaluateSubtreeArcElimination(LP lp, double incumbentCost,
			double nodeLowerBound) {
		CompletionBoundSubtreeArcEliminator.Result result = completionBoundSubtreeArcEliminator.evaluate(lp,
				incumbentCost, nodeLowerBound, pc.getLastReusableSubtreeArcEliminationBounds());
		if (result.isAvailable()) {
			traceSink.onCompletionBoundSubtreeArcElimination(lp.getNode(),
					config.bidirectionalCompletionBoundSubtreeArcElimination
							|| config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly,
					result.getCandidates(), result.getFixed(), result.getDomainFixed(), result.getScalarFixed(),
					result.getUnavailable(), result.getFunctionEvaluations(), result.getGap(), result.summary(),
					result.getTotalNanos());
		}
		return result;
	}

	private void applyTimeIndexedGraphArcFixing(LP lp, double incumbentCost) {
		if (!config.useTimeIndexedGraphPricing) {
			return;
		}
		heartbeat(lp.getNode(), "timeIndexedArcFixing.start");
		TimeIndexedGraphPricingEngine.ArcFixingResult result =
				TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing(data, config, lp, incumbentCost);
		if (result.isAvailable()) {
			heartbeat(lp.getNode(), "timeIndexedArcFixing.done " + result.summary());
		}
	}

	private void heartbeat(Node node, String phase) {
		if (!config.diagnosticStageHeartbeat
				&& (config.liveTraceLogPath == null || config.liveTraceLogPath.trim().isEmpty())) {
			return;
		}
		traceSink.onStageHeartbeat(node, phase, totalPoolSize(), cutPool.size());
	}

	private void tryImproveLocalHorizonAtNode(Node node) {
		if (!config.enableNodeLocalHorizonImprovement) {
			return;
		}
		if (config.nodeLocalHorizonImprovementNodeId > 0 && node.id != config.nodeLocalHorizonImprovementNodeId) {
			return;
		}
		if (node.depth <= 0) {
			return;
		}
		boolean[][] forbidden = new boolean[data.n + 1][data.n + 1];
		for (int from = 0; from <= data.n; from++) {
			for (int to = 1; to <= data.n; to++) {
				if (from != to && (node.isArcForbidden(from, to) || node.isPricingOnlyArcForbidden(from, to))) {
					forbidden[from][to] = true;
				}
			}
		}
		boolean[][] required = new boolean[data.n + 1][data.n + 1];
		for (int[] arc : node.getRequiredArcs()) {
			int from = arc[0];
			int to = arc[1];
			if (from >= 0 && from <= data.n && to >= 1 && to <= data.n) {
				required[from][to] = true;
			}
		}
		LocalHorizonCmaxSolver.Problem problem =
				LocalHorizonCmaxSolver.Problem.fromData(data, node.maxMachineCount, forbidden, required, null);
		System.out.println(String.format(Locale.US,
				"[LocalHorizonImprove] node=%d depth=%d maxMachine=%d forbidden=%d pricingOnly=%d required=%d horizonUB=%d timeLimit=%.3f",
				node.id, node.depth, node.maxMachineCount, countForbidden(forbidden), node.countPricingOnlyForbiddenArcs(),
				node.countRequiredArcStates(), problem.horizonUpperBound,
				config.nodeLocalHorizonImprovementTimeLimitSeconds));
		if (config.nodeLocalHorizonImprovementUseCplex) {
			try {
				LocalHorizonCmaxSolver.Result result = LocalHorizonCmaxSolver.solveWithCplex(problem,
						config.nodeLocalHorizonImprovementTimeLimitSeconds, 1);
				System.out.println("[LocalHorizonImprove] node=" + node.id + " " + result);
			} catch (Exception e) {
				System.out.println("[LocalHorizonImprove] node=" + node.id + " CPLEX_ARC_FLOW failed: " + e.getMessage());
			}
		}
		if (config.nodeLocalHorizonImprovementUseCp) {
			try {
				LocalHorizonCmaxSolver.Result result = LocalHorizonCmaxSolver.solveWithCpOptimizer(problem,
						config.nodeLocalHorizonImprovementTimeLimitSeconds, 1);
				System.out.println("[LocalHorizonImprove] node=" + node.id + " " + result);
			} catch (Exception e) {
				System.out.println("[LocalHorizonImprove] node=" + node.id + " CP_OPTIMIZER failed: " + e.getMessage());
			}
		}
	}

	private int countForbidden(boolean[][] forbidden) {
		int count = 0;
		for (int from = 0; from < forbidden.length; from++) {
			for (int to = 0; to < forbidden[from].length; to++) {
				if (forbidden[from][to]) {
					count++;
				}
			}
		}
		return count;
	}

	private void applySubtreeArcElimination(BranchResult branchResult,
			CompletionBoundSubtreeArcEliminator.Result subtreeArcElimination) {
		if (!config.bidirectionalCompletionBoundSubtreeArcElimination || subtreeArcElimination == null
				|| !subtreeArcElimination.isAvailable()) {
			if (config.bidirectionalCompletionBoundSubtreeArcEliminationPricingOnly
					&& subtreeArcElimination != null && subtreeArcElimination.isAvailable()) {
				subtreeArcElimination.applyToPricingOnly(branchResult.getLeftNode());
				subtreeArcElimination.applyToPricingOnly(branchResult.getRightNode());
			}
			return;
		}
		subtreeArcElimination.applyTo(branchResult.getLeftNode());
		subtreeArcElimination.applyTo(branchResult.getRightNode());
	}

	private StrongBranchingSelection tryTwoStageStrongBranching(LP parentLp, Brancher brancher,
			CompletionBoundSubtreeArcEliminator.Result subtreeArcElimination, double parentBound) {
		if (!config.enableTwoStageStrongBranching || config.strongBranchingCandidateLimit <= 0) {
			return null;
		}
		List<StrongBranchingCandidate> allCandidates =
				brancher.collectStrongBranchingCandidates(parentLp, Integer.MAX_VALUE);
		if (allCandidates.isEmpty()) {
			return null;
		}
		int candidateCount = allCandidates.size();
		int testedLimit = Math.min(config.strongBranchingCandidateLimit, candidateCount);
		List<StrongBranchingCandidate> candidates = candidateCount == testedLimit
				? allCandidates : new ArrayList<StrongBranchingCandidate>(allCandidates.subList(0, testedLimit));
		String candidatePreview = strongBranchingCandidatePreview(candidates, 8);
		ArrayList<StrongBranchingSelection> phase1 = new ArrayList<StrongBranchingSelection>();
		for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
			StrongBranchingCandidate candidate = candidates.get(candidateIndex);
			BranchResult branchResult = candidate.createBranchResult(parentLp);
			if (!branchResult.isBranched()) {
				continue;
			}
			applySubtreeArcElimination(branchResult, subtreeArcElimination);
			StrongBranchingTrialResult leftTrial = solveStrongBranchingRmpTrial(branchResult.getLeftNode(), parentLp);
			applyTrialSeed(branchResult.getLeftNode(), leftTrial);
			if (leftTrial != null && leftTrial.isTimeLimited()) {
				return new StrongBranchingSelection(branchResult, candidate, leftTrial, null, 0.0, false,
						candidateCount, candidates.size(), candidateIndex + 1, candidatePreview);
			}
			StrongBranchingTrialResult rightTrial = solveStrongBranchingRmpTrial(branchResult.getRightNode(), parentLp);
			applyTrialSeed(branchResult.getRightNode(), rightTrial);
			double score = hasTimeLimitedTrial(leftTrial, rightTrial) ? 0.0
					: strongBranchingScore(parentBound, leftTrial, rightTrial);
			StrongBranchingSelection selection = new StrongBranchingSelection(branchResult, candidate, leftTrial,
					rightTrial, score, false, candidateCount, candidates.size(), candidateIndex + 1, candidatePreview);
			phase1.add(selection);
			if (selection.hasTimeLimitedTrial()) {
				return selection;
			}
			if (selection.bothChildrenInfeasible()) {
				return selection;
			}
		}
		if (phase1.isEmpty()) {
			return null;
		}
		Collections.sort(phase1, new Comparator<StrongBranchingSelection>() {
			@Override
			public int compare(StrongBranchingSelection a, StrongBranchingSelection b) {
				return compareStrongBranchingSelection(a, b);
			}
		});
		// 2026-06-28: time-indexed pricing 不混用原机器序列启发式；强分支只用 phase1 RMP/repair 评分。
		if (config.useTimeIndexedGraphPricing) {
			return phase1.get(0);
		}
		int phase2Limit = Math.min(config.strongBranchingPhase2CandidateLimit, phase1.size());
		if (phase2Limit <= 0) {
			return phase1.get(0);
		}
		ArrayList<StrongBranchingSelection> phase2 = new ArrayList<StrongBranchingSelection>();
		for (int idx = 0; idx < phase2Limit; idx++) {
			StrongBranchingSelection selected = phase1.get(idx);
			StrongBranchingTrialResult leftTrial = !selected.leftTrial.isReusableForQueue()
					? selected.leftTrial : solveStrongBranchingHeuristicTrial(selected.result.getLeftNode());
			applyTrialSeed(selected.result.getLeftNode(), leftTrial);
			if (leftTrial != null && leftTrial.isTimeLimited()) {
				return new StrongBranchingSelection(selected.result, selected.candidate, leftTrial, selected.rightTrial,
						0.0, true, selected.candidateCount, selected.testedCandidateCount, selected.rankByHalf,
						selected.candidatePreview);
			}
			StrongBranchingTrialResult rightTrial = !selected.rightTrial.isReusableForQueue()
					? selected.rightTrial : solveStrongBranchingHeuristicTrial(selected.result.getRightNode());
			applyTrialSeed(selected.result.getRightNode(), rightTrial);
			double score = hasTimeLimitedTrial(leftTrial, rightTrial) ? 0.0
					: strongBranchingScore(parentBound, leftTrial, rightTrial);
			StrongBranchingSelection selection = new StrongBranchingSelection(selected.result, selected.candidate,
					leftTrial, rightTrial, score, true, selected.candidateCount, selected.testedCandidateCount,
					selected.rankByHalf, selected.candidatePreview);
			phase2.add(selection);
			if (selection.hasTimeLimitedTrial()) {
				return selection;
			}
		}
		Collections.sort(phase2, new Comparator<StrongBranchingSelection>() {
			@Override
			public int compare(StrongBranchingSelection a, StrongBranchingSelection b) {
				return compareStrongBranchingSelection(a, b);
			}
		});
		return phase2.isEmpty() ? phase1.get(0) : phase2.get(0);
	}

	private String strongBranchingCandidatePreview(List<StrongBranchingCandidate> candidates, int limit) {
		StringBuilder builder = new StringBuilder();
		int count = Math.min(limit, candidates.size());
		for (int i = 0; i < count; i++) {
			if (i > 0) {
				builder.append(';');
			}
			StrongBranchingCandidate candidate = candidates.get(i);
			builder.append(candidate.getDescription()).append('=')
					.append(String.format(Locale.US, "%.6f", candidate.getValue()));
		}
		if (candidates.size() > count) {
			builder.append(";...");
		}
		return builder.toString();
	}

	private StrongBranchingTrialResult solveStrongBranchingRmpTrial(Node child, LP parentLp) {
		prepareChildSeedColumns(child, parentLp);
		LP trial = new LP(data, pool, cutPool, config, outsourcingPool);
		try {
			trial.construct(child, child.seedColumnIds);
			return pc.solveStrongBranchingRmpTrial(trial);
		} finally {
			trial.closeModel();
		}
	}

	private StrongBranchingTrialResult solveStrongBranchingHeuristicTrial(Node child) {
		LP trial = new LP(data, pool, cutPool, config, outsourcingPool);
		try {
			trial.construct(child, child.seedColumnIds);
			return pc.solveStrongBranchingHeuristicTrial(trial);
		} finally {
			trial.closeModel();
		}
	}

	private void applyTrialSeed(Node child, StrongBranchingTrialResult trial) {
		if (child == null || trial == null) {
			return;
		}
		if (!trial.isReusableForQueue()) {
			child.setStrongBranchingSeedPrepared(false);
			return;
		}
		child.seedColumnIds = trial.getInternalColumnIds();
		child.seedOutsourcingColumnIds = trial.getOutsourcingColumnIds();
		child.setStrongBranchingSeedPrepared(true);
	}

	private double strongBranchingScore(double parentBound, StrongBranchingTrialResult left,
			StrongBranchingTrialResult right) {
		double leftGain = strongBranchingGain(parentBound, left);
		double rightGain = strongBranchingGain(parentBound, right);
		double eps = Math.max(0.0, config.strongBranchingScoreEpsilon);
		return Math.max(leftGain, eps) * Math.max(rightGain, eps);
	}

	private boolean hasTimeLimitedTrial(StrongBranchingTrialResult left, StrongBranchingTrialResult right) {
		return (left != null && left.isTimeLimited()) || (right != null && right.isTimeLimited());
	}

	private double strongBranchingGain(double parentBound, StrongBranchingTrialResult trial) {
		if (trial == null) {
			throw new IllegalStateException("Strong branching score requested for missing trial result");
		}
		if (trial.isTimeLimited()) {
			return 0.0;
		}
		if (trial.isInfeasible()) {
			return config.pseudoCostInf;
		}
		if (!Double.isFinite(parentBound) || !Double.isFinite(trial.getBound())) {
			return 0.0;
		}
		return Math.max(0.0, trial.getBound() - parentBound);
	}

	private int compareStrongBranchingSelection(StrongBranchingSelection a, StrongBranchingSelection b) {
		if (Utility.compareGt(a.score, b.score)) {
			return -1;
		}
		if (Utility.compareLt(a.score, b.score)) {
			return 1;
		}
		if (Utility.compareLt(a.candidate.getDistanceToHalf(), b.candidate.getDistanceToHalf())) {
			return -1;
		}
		if (Utility.compareGt(a.candidate.getDistanceToHalf(), b.candidate.getDistanceToHalf())) {
			return 1;
		}
		int orderCompare = Integer.compare(a.candidate.getOrder(), b.candidate.getOrder());
		return orderCompare != 0 ? orderCompare : a.candidate.getDescription().compareTo(b.candidate.getDescription());
	}

	private double updateReportedBound(PriorityQueue<Node> queue, double currentNodeBound, double incumbentCost) {
		// 2026-05-19: 参考旧 VRP 的 sudo_cost 语义，报告当前节点 bound 与 open queue 中最小伪下界的较小值。
		// 旧实现最后用 queue.peek().sudo_cost 修正 lower bound；这里在求解过程中也用同一语义更新输出。
		double bound = currentNodeBound;
		if (!queue.isEmpty()) {
			bound = Math.min(bound, queue.peek().pseudoCost);
		}
		if (Double.isFinite(incumbentCost) && Utility.compareGt(bound, incumbentCost)) {
			return incumbentCost;
		}
		return bound;
	}

	private double finalBound(PriorityQueue<Node> queue, double incumbentCost, double lastReportedBound,
			boolean timeLimitReached) {
		// 2026-05-19: 如果队列为空，所有节点已经关闭，最终 LB 应等于 incumbent；
		// 如果达到节点上限仍有 open node，则用 open queue 中最小伪下界作为当前全局 LB。
		if (queue.isEmpty()) {
			return timeLimitReached ? lastReportedBound : incumbentCost;
		}
		double bound = queue.peek().pseudoCost;
		// 2026-06-25: root 尚未处理或伪下界仍是占位大数时，不能把 bound 截成 incumbent；
		// 否则 TIME_LIMIT / NODE_LIMIT 会被误报成 gap=0。
		if (!Double.isFinite(bound) || Utility.isBigMValue(bound)) {
			return lastReportedBound;
		}
		if (Double.isFinite(incumbentCost) && Utility.compareGt(bound, incumbentCost)) {
			return incumbentCost;
		}
		return bound;
	}

	private TWETSolveStatus finalStatus(int processedNodes, boolean queueEmpty, boolean stoppedByTimeLimit,
			boolean timeLimitReached) {
		if (stoppedByTimeLimit) {
			return TWETSolveStatus.TIME_LIMIT;
		}
		if (processedNodes == 0) {
			return timeLimitReached ? TWETSolveStatus.TIME_LIMIT : TWETSolveStatus.INITIALIZED;
		}
		// 2026-06-25: 全局时间上限优先于节点上限状态，避免长算例被误报为已完成。
		if (timeLimitReached && !queueEmpty) {
			return TWETSolveStatus.TIME_LIMIT;
		}
		// 2026-05-18: 显式区分达到 maxNodes 后队列仍非空的情况，避免把节点上限停止误报为完成。
		if (!queueEmpty && processedNodes >= config.maxNodes) {
			return TWETSolveStatus.NODE_LIMIT;
		}
		if (processedNodes == 1) {
			return TWETSolveStatus.ROOT_PROCESSED;
		}
		return TWETSolveStatus.FINISHED;
	}

	private boolean isSolveTimeLimitReached(long solveStartNanos) {
		return Double.isFinite(config.solveTimeLimitSeconds) && Utility.compareGt(config.solveTimeLimitSeconds, 0.0)
				&& remainingSolveTimeSeconds(solveStartNanos) <= 0.0;
	}

	private double remainingSolveTimeSeconds(long solveStartNanos) {
		if (!Double.isFinite(config.solveTimeLimitSeconds) || Utility.compareLe(config.solveTimeLimitSeconds, 0.0)) {
			return Double.POSITIVE_INFINITY;
		}
		double elapsedSeconds = (System.nanoTime() - solveStartNanos) / 1_000_000_000.0;
		return Math.max(0.0, config.solveTimeLimitSeconds - elapsedSeconds);
	}

	private void enqueueChild(PriorityQueue<Node> queue, Node child, LP parentLp) {
		if (child == null) {
			return;
		}
		prepareChildSeedColumns(child, parentLp);
		queue.add(child);
	}

	private boolean enqueueStrongBranchingChild(PriorityQueue<Node> queue, Node child,
			StrongBranchingTrialResult trial) {
		if (child == null || trial == null || !trial.isReusableForQueue()) {
			return false;
		}
		child.setStrongBranchingSeedPrepared(true);
		queue.add(child);
		return true;
	}

	private void prepareChildSeedColumns(Node child, LP parentLp) {
		ArrayList<Integer> seed = new ArrayList<Integer>();
		ArrayList<Integer> outsourcingSeed = new ArrayList<Integer>(child.seedOutsourcingColumnIds);
		if (parentLp != null) {
			seed.addAll(parentLp.getRestrictedColumnIds());
			for (int id : parentLp.getRestrictedOutsourcingColumnIds()) {
				Integer value = Integer.valueOf(id);
				if (!outsourcingSeed.contains(value)) {
					outsourcingSeed.add(value);
				}
			}
		}
		// 2026-05-18: 对齐旧 VRP UpdateRouteSet 的时机。child 入队时先继承父节点当前列集，
		// 不提前按新分支状态或 reduced cost 过滤；等 child 出队后，RMP 带新分支行先求一次 LP。
		// 若可行或通过 slack repair 修复成功，再在 LP.resetRestrictedColumnsByCurrentReducedCost()
		// 里筛成正式子节点列集。这样保留“出队时处理”的实现方式，但逻辑上等价于旧代码。
		child.seedColumnIds = seed;
		child.seedOutsourcingColumnIds = outsourcingSeed;
	}

	private void seedInitialOutsourcingColumn(Node root, InitialColumnBundle initial) {
		if (!config.useColumnizedOutsourcing() || initial.getIncumbentOutsourcedJobs().isEmpty()) {
			return;
		}
		int id = outsourcingPool.addColumn(initial.getIncumbentOutsourcedJobs(), TWETBPC.Model.ColumnSource.HEURISTIC_FULL,
				true);
		if (id >= 0) {
			root.seedOutsourcingColumnIds.add(Integer.valueOf(id));
		}
	}

	private double incumbentCostFromInitial(InitialColumnBundle initial) {
		if (data.configure.bestSolution != null) {
			return data.configure.bestSolution.curCost;
		}
		double cost = initial.getIncumbentOutsourcingCost();
		for (int columnId : initial.getIncumbentColumnIds()) {
			cost += pool.getColumn(columnId).getCost();
		}
		return cost;
	}

	private double[] initialOutsourcingValues(InitialColumnBundle initial) {
		double[] values = new double[data.n + 1];
		for (int job : initial.getIncumbentOutsourcedJobs()) {
			if (job >= 1 && job <= data.n) {
				values[job] = 1.0;
			}
		}
		return values;
	}

	private static final class StrongBranchingSelection {
		final BranchResult result;
		final StrongBranchingCandidate candidate;
		final StrongBranchingTrialResult leftTrial;
		final StrongBranchingTrialResult rightTrial;
		final double score;
		final boolean phase2;
		final int candidateCount;
		final int testedCandidateCount;
		final int rankByHalf;
		final String candidatePreview;

		StrongBranchingSelection(BranchResult result, StrongBranchingCandidate candidate,
				StrongBranchingTrialResult leftTrial, StrongBranchingTrialResult rightTrial, double score,
				boolean phase2, int candidateCount, int testedCandidateCount, int rankByHalf, String candidatePreview) {
			this.result = result;
			this.candidate = candidate;
			this.leftTrial = leftTrial;
			this.rightTrial = rightTrial;
			this.score = score;
			this.phase2 = phase2;
			this.candidateCount = candidateCount;
			this.testedCandidateCount = testedCandidateCount;
			this.rankByHalf = rankByHalf;
			this.candidatePreview = candidatePreview;
		}

		BranchResult traceResult() {
			return new BranchResult(true, result.getLeftNode(), result.getRightNode(),
					result.getMessage() + " | strongBranching " + summary());
		}

		boolean hasTimeLimitedTrial() {
			return (leftTrial != null && leftTrial.isTimeLimited())
					|| (rightTrial != null && rightTrial.isTimeLimited());
		}

		boolean bothChildrenInfeasible() {
			return leftTrial != null && rightTrial != null
					&& leftTrial.isInfeasible() && rightTrial.isInfeasible();
		}

		String summary() {
			return "candidate=" + candidate.getDescription()
					+ ",value=" + candidate.getValue()
					+ ",distToHalf=" + candidate.getDistanceToHalf()
					+ ",rankByHalf=" + rankByHalf
					+ ",candidateCount=" + candidateCount
					+ ",tested=" + testedCandidateCount
					+ ",phase=" + (phase2 ? "phase2" : "phase1")
					+ ",leftBound=" + boundText(leftTrial)
					+ ",rightBound=" + boundText(rightTrial)
					+ ",score=" + score
					+ ",top=" + candidatePreview;
		}

		private static String boundText(StrongBranchingTrialResult trial) {
			if (trial == null) {
				return "NA";
			}
			if (trial.isTimeLimited()) {
				return "TIME_LIMIT";
			}
			if (trial.isInfeasible()) {
				return "INF";
			}
			return String.valueOf(trial.getBound());
		}
	}

}
