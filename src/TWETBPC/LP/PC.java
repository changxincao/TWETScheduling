package TWETBPC.LP;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.CUT.CutGenerationResult;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.GC.CompletionBoundSubtreeArcEliminator;
import TWETBPC.GC.OutsourcingPricingEngine;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.PricingResult;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETOutsourcingColumn;

/**
 * 节点内部的 price-and-cut 控制器。
 */
public class PC {

	private final TWETBPCConfig config;
	private final List<PricingEngine> pricingEngines;
	private final List<CutGenerator> cutGenerators;
	private final BPCTraceSink traceSink;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;
	private double incumbentForDualBoundPruning;
	private double lastObservedDualBound;
	private boolean lastNodePrunedByDualBound;

	public PC(TWETBPCConfig config, List<PricingEngine> pricingEngines, List<CutGenerator> cutGenerators,
			BPCTraceSink traceSink) {
		this.config = config;
		this.pricingEngines = pricingEngines;
		this.cutGenerators = cutGenerators;
		this.traceSink = traceSink;
	}

	public TWETMasterSolution solve(LP lp) {
		return solve(lp, Double.POSITIVE_INFINITY);
	}

	public TWETMasterSolution solve(LP lp, double incumbentCost) {
		lastReusableSubtreeArcEliminationBounds = null;
		lastObservedDualBound = Double.NEGATIVE_INFINITY;
		lastNodePrunedByDualBound = false;
		incumbentForDualBoundPruning = incumbentCost;
		// 2026-05-23: 以下计时只写入 trace，用于拆分 RMP、pricing 和 cut 的耗时，
		// 不参与列选择、剪枝或对偶计算，避免改变 BPC 求解流程。
		TWETMasterSolution solution = solveRelaxationTimed(lp, "initial");
		if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
			solution = repairInfeasibleMaster(lp);
			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				return solution;
			}
		} else if (lp.getNode() != null && lp.getNode().depth > 0 && !config.debugSkipBranchColumnFilter) {
			// 2026-05-18: 对齐旧 VRP UpdateRouteSet。Child 第一次 LP 可行时，也先按当前 LP 的
			// reduced cost 和分支兼容性筛出正式列集，再进入后续 pricing；repair 成功路径也会做同样筛选。
			lp.resetRestrictedColumnsByCurrentReducedCost(config.branchSeedColumnLimit,
					config.branchSeedReducedCostAllowance);
			solution = solveRelaxationTimed(lp, "after_column_filter");
			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				solution = repairInfeasibleMaster(lp);
				if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
					return solution;
				}
			}
		}

		solution = solvePricingLoop(lp, solution);

		// 2026-06-13: 对齐旧 VRP PC.Solve()：pricing 收敛后若 LP 已经整数，不再做 cut separation。
		if (solution.isInteger()) {
			return solution;
		}

		for (int round = 0; round < config.maxCutRounds; round++) {
			ArrayList<Integer> newCutIds = new ArrayList<Integer>();
			boolean separated = false;
			for (CutGenerator generator : cutGenerators) {
				long cutStart = System.nanoTime();
				CutGenerationResult result = generator.separate(lp);
				long cutNanos = System.nanoTime() - cutStart;
				int addedCuts = 0;
				if (result.isSeparated()) {
					separated = true;
					for (int i = 0; i < result.getCuts().size(); i++) {
						newCutIds.add(Integer.valueOf(lp.getCutPool().addCut(result.getCuts().get(i))));
					}
					addedCuts = result.getCuts().size();
				}
				traceSink.onCutCall(lp.getNode(), generator.getName(), result.isSeparated(), addedCuts,
						result.getMessage(), lp.getCutPool().size(), cutNanos);
			}
			if (!separated) {
				break;
			}
			lastReusableSubtreeArcEliminationBounds = null;
			lp.addCuts(newCutIds);
			solution = solveRelaxationTimed(lp, "after_cut");
			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				return solution;
			}
			// 2026-06-13: 新 cut 改变 dual 和 reduced cost，必须重新定价闭合；否则 SRI dual 已读出但没有进入 pricing。
			solution = solvePricingLoop(lp, solution);
			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE || solution.isInteger()) {
				return solution;
			}
		}

		return solution;
	}

	public CompletionBoundSubtreeArcEliminator.PreparedBounds getLastReusableSubtreeArcEliminationBounds() {
		return lastReusableSubtreeArcEliminationBounds;
	}

	public double getLastObservedDualBound() {
		return lastObservedDualBound;
	}

	public boolean wasLastNodePrunedByDualBound() {
		return lastNodePrunedByDualBound;
	}

	private TWETMasterSolution solvePricingLoop(LP lp, TWETMasterSolution currentSolution) {
		lp.clearPricingDualOverride();
		if (config.enableDualStabilization && !lp.isFeasibilityRepairMode()) {
			return solvePricingLoopWithDualStabilization(lp, currentSolution);
		}
		return solvePricingLoopWithTrueDuals(lp, currentSolution);
	}

	private TWETMasterSolution solvePricingLoopWithTrueDuals(LP lp, TWETMasterSolution currentSolution) {
		TWETMasterSolution solution = currentSolution;
		while (true) {
			boolean addedColumn = false;
			for (int engineIndex = 0; engineIndex < pricingEngines.size(); engineIndex++) {
				PricingEngine engine = pricingEngines.get(engineIndex);
				HashSet<Integer> activeColumnIds = new HashSet<Integer>(lp.getRestrictedColumnIds());
				HashSet<Integer> activeOutsourcingColumnIds = new HashSet<Integer>(lp.getRestrictedOutsourcingColumnIds());
				GeneratedColumnIds generated = generateColumnsFromEngine(lp, engine, false, activeColumnIds,
						activeOutsourcingColumnIds);
				if (lastNodePrunedByDualBound) {
					return solution;
				}
				if (generated.isEmpty()) {
					continue;
				}

				int addedColumns = lp.addColumns(generated.internalColumnIds)
						+ lp.addOutsourcingColumns(generated.outsourcingColumnIds);
				if (addedColumns == 0) {
					continue;
				}

				// 2026-05-19: 对齐旧 VRP PC 的普通 pricing 节奏。一个定价器加列后立即重解 LP，
				// 并从第一个定价器重新开始；这样只要启发式还能补列，就不会提前调用更重的精确定价。
				resetFollowingPricingEngines(engineIndex + 1);
				lastReusableSubtreeArcEliminationBounds = null;
				solution = resolveCurrentModelTimed(lp, "after_pricing");
				addedColumn = true;
				break;
			}
			if (!addedColumn) {
				break;
			}
		}
		return solution;
	}

	private TWETMasterSolution solvePricingLoopWithDualStabilization(LP lp, TWETMasterSolution currentSolution) {
		TWETMasterSolution solution = currentSolution;
		DualStabilizationState dualState = new DualStabilizationState(lp.captureTruePricingDuals(),
				currentSolution.getObjectiveValue());
		while (true) {
			LP.PricingDualSnapshot outDual = lp.captureTruePricingDuals();
			PricingPassResult stabilizedPass = runStabilizedPricingSequence(lp, dualState, outDual);
			if (stabilizedPass.dualBoundPruned) {
				return solution;
			}
			if (stabilizedPass.addedColumns > 0) {
				solution = resolveCurrentModelTimed(lp, "after_pricing_stabilized");
				dualState.observeAccepted(lp.captureTruePricingDuals(), solution.getObjectiveValue(), stabilizedPass);
				continue;
			}
			PricingPassResult truePass = runPricingPass(lp, "true", true);
			if (truePass.dualBoundPruned) {
				return solution;
			}
			if (truePass.addedColumns > 0) {
				solution = resolveCurrentModelTimed(lp, "after_pricing_true");
				dualState.observeAccepted(lp.captureTruePricingDuals(), solution.getObjectiveValue(), truePass);
				continue;
			}
			break;
		}
		lp.clearPricingDualOverride();
		return solution;
	}

	private PricingPassResult runStabilizedPricingSequence(LP lp, DualStabilizationState dualState,
			LP.PricingDualSnapshot outDual) {
		double initialAlpha = dualState.alpha;
		double outObjective = lp.getLastSolution() == null ? Double.NaN : lp.getLastSolution().getObjectiveValue();
		int attempt = 0;
		while (true) {
			double alpha = attempt == 0 ? initialAlpha : Math.max(0.0, 1.0 - (attempt + 1) * (1.0 - initialAlpha));
			StabilizedDualPoint stabilizedDual = dualState.stabilizedDual(outDual, outObjective, alpha,
					attempt == 0);
			lp.setPricingDualOverride(stabilizedDual.dual);
			PricingPassResult pass;
			try {
				pass = runPricingPass(lp, "stabilized.a" + String.format("%.3f", Double.valueOf(alpha)), false,
						outDual, stabilizedDual.boundObjective);
			} finally {
				lp.clearPricingDualOverride();
			}
			if (pass.dualBoundPruned || pass.addedColumns > 0 || alpha <= 0.0) {
				dualState.alpha = alpha;
				return pass;
			}
			attempt++;
		}
	}

	private PricingPassResult runPricingPass(LP lp, String dualModeName, boolean allowReusableBounds) {
		return runPricingPass(lp, dualModeName, allowReusableBounds, null, Double.NaN);
	}

	private PricingPassResult runPricingPass(LP lp, String dualModeName, boolean allowReusableBounds,
			LP.PricingDualSnapshot acceptanceDual) {
		return runPricingPass(lp, dualModeName, allowReusableBounds, acceptanceDual, Double.NaN);
	}

	private PricingPassResult runPricingPass(LP lp, String dualModeName, boolean allowReusableBounds,
			LP.PricingDualSnapshot acceptanceDual, double dualBoundObjectiveOverride) {
		for (int engineIndex = 0; engineIndex < pricingEngines.size(); engineIndex++) {
			PricingEngine engine = pricingEngines.get(engineIndex);
			HashSet<Integer> activeColumnIds = new HashSet<Integer>(lp.getRestrictedColumnIds());
			HashSet<Integer> activeOutsourcingColumnIds =
					new HashSet<Integer>(lp.getRestrictedOutsourcingColumnIds());
			GeneratedColumnIds generated = generateColumnsFromEngine(lp, engine, false, activeColumnIds,
					activeOutsourcingColumnIds, dualModeName, allowReusableBounds, acceptanceDual,
					dualBoundObjectiveOverride);
			if (lastNodePrunedByDualBound) {
				return PricingPassResult.dualBoundPruned();
			}
			if (generated.isEmpty()) {
				continue;
			}

			int addedColumns = lp.addColumns(generated.internalColumnIds)
					+ lp.addOutsourcingColumns(generated.outsourcingColumnIds);
			if (addedColumns == 0) {
				continue;
			}

			resetFollowingPricingEngines(engineIndex + 1);
			lastReusableSubtreeArcEliminationBounds = null;
			return new PricingPassResult(addedColumns, generated.bestAcceptedReducedCost, generated.representativeColumn,
					generated.representativeOutsourcingColumn);
		}
		return PricingPassResult.EMPTY;
	}

	private final class DualStabilizationState {
		private LP.PricingDualSnapshot center;
		private double centerObjective;
		private LP.PricingDualSnapshot lastAcceptedGradient;
		private double alpha;

		DualStabilizationState(LP.PricingDualSnapshot initialCenter, double initialObjective) {
			this.center = initialCenter.copy();
			this.centerObjective = initialObjective;
			this.alpha = Math.max(0.0, Math.min(0.95, config.dualStabilizationAlpha));
		}

		StabilizedDualPoint stabilizedDual(LP.PricingDualSnapshot outDual, double outObjective, double attemptAlpha,
				boolean allowDirectional) {
			double clippedAlpha = Math.max(0.0, Math.min(0.95, attemptAlpha));
			LP.PricingDualSnapshot ordinary = LP.PricingDualSnapshot.blend(outDual, center, 1.0 - clippedAlpha);
			double ordinaryObjective = blendObjective(outObjective, centerObjective, 1.0 - clippedAlpha);
			if (!allowDirectional || !config.dualStabilizationDirectionalSmoothing || lastAcceptedGradient == null
					|| clippedAlpha <= 0.0) {
				return new StabilizedDualPoint(ordinary, ordinaryObjective);
			}
			double distance = normDifference(outDual, center);
			double gradientNorm = norm(lastAcceptedGradient);
			if (distance <= 1e-9 || gradientNorm <= 1e-9) {
				return new StabilizedDualPoint(ordinary, ordinaryObjective);
			}
			double beta = Math.max(0.0, Math.min(1.0, dotDifferenceGradient(outDual, center, lastAcceptedGradient)
					/ (distance * gradientNorm)));
			if (beta <= 0.0) {
				return new StabilizedDualPoint(ordinary, ordinaryObjective);
			}
			LP.PricingDualSnapshot gradientPoint = addScaled(center, lastAcceptedGradient, distance / gradientNorm);
			LP.PricingDualSnapshot rho = blendTwo(gradientPoint, outDual, beta);
			double rhoDistance = normDifference(rho, center);
			if (rhoDistance <= 1e-9) {
				return new StabilizedDualPoint(ordinary, ordinaryObjective);
			}
			// Directional smoothing uses a column-gradient direction, not a convex
			// combination of known RMP dual points. It is valid for finding columns, but
			// there is no safe objective scalar for dual-bound pruning here.
			return new StabilizedDualPoint(
					addScaled(center, difference(rho, center), (1.0 - clippedAlpha) * distance / rhoDistance),
					Double.NaN);
		}

		void observeAccepted(LP.PricingDualSnapshot trueDual, double trueObjective, PricingPassResult pass) {
			if (pass.hasRepresentative()) {
				lastAcceptedGradient = representativeGradient(pass.representativeColumn, pass.representativeOutsourcingColumn);
				double signal = dotDifferenceGradient(trueDual, center, lastAcceptedGradient);
				if (signal > 0.0) {
					alpha = alpha + config.dualStabilizationAlphaIncreaseFraction * (1.0 - alpha);
				} else {
					alpha = Math.max(0.0, alpha - config.dualStabilizationAlphaDecreaseStep);
				}
				alpha = Math.max(0.0, Math.min(0.95, alpha));
			}
			double weight = config.dualStabilizationCenterMoveWeight;
			center = LP.PricingDualSnapshot.blend(trueDual, center, weight);
			centerObjective = blendObjective(trueObjective, centerObjective, weight);
		}

		private LP.PricingDualSnapshot representativeGradient(TWETColumn column, TWETOutsourcingColumn outsourcingColumn) {
			double[] job = new double[center.jobDual.length];
			double machine = 0.0;
			double outsource = 0.0;
			double[][] arc = new double[center.arcDual.length][];
			for (int i = 0; i < arc.length; i++) {
				arc[i] = new double[center.arcDual[i].length];
			}
			if (column != null) {
				machine = -1.0;
				for (int j = 1; j < job.length; j++) {
					job[j] = -column.getJobVisitCount(j);
				}
				int sink = center.arcDual.length - 1;
				for (int from = 0; from < arc.length; from++) {
					for (int to = 1; to < arc[from].length; to++) {
						int count = column.getArcVisitCount(from, to, sink);
						if (count > 0) {
							arc[from][to] = -count;
						}
					}
				}
			} else if (outsourcingColumn != null) {
				outsource = -1.0;
				for (int j : outsourcingColumn.getJobs()) {
					if (j >= 0 && j < job.length) {
						job[j] = -1.0;
					}
				}
			}
			return new LP.PricingDualSnapshot(job, machine, outsource, arc);
		}
	}

	private static final class StabilizedDualPoint {
		final LP.PricingDualSnapshot dual;
		final double boundObjective;

		StabilizedDualPoint(LP.PricingDualSnapshot dual, double boundObjective) {
			this.dual = dual;
			this.boundObjective = boundObjective;
		}
	}

	private static final class PricingPassResult {
		static final PricingPassResult EMPTY = new PricingPassResult(0, Double.POSITIVE_INFINITY, null, null, false);

		final int addedColumns;
		final double bestAcceptedReducedCost;
		final TWETColumn representativeColumn;
		final TWETOutsourcingColumn representativeOutsourcingColumn;
		final boolean dualBoundPruned;

		PricingPassResult(int addedColumns, double bestAcceptedReducedCost, TWETColumn representativeColumn,
				TWETOutsourcingColumn representativeOutsourcingColumn) {
			this(addedColumns, bestAcceptedReducedCost, representativeColumn, representativeOutsourcingColumn, false);
		}

		PricingPassResult(int addedColumns, double bestAcceptedReducedCost, TWETColumn representativeColumn,
				TWETOutsourcingColumn representativeOutsourcingColumn, boolean dualBoundPruned) {
			this.addedColumns = addedColumns;
			this.bestAcceptedReducedCost = bestAcceptedReducedCost;
			this.representativeColumn = representativeColumn;
			this.representativeOutsourcingColumn = representativeOutsourcingColumn;
			this.dualBoundPruned = dualBoundPruned;
		}

		static PricingPassResult dualBoundPruned() {
			return new PricingPassResult(0, Double.POSITIVE_INFINITY, null, null, true);
		}

		boolean hasRepresentative() {
			return representativeColumn != null || representativeOutsourcingColumn != null;
		}
	}

	private static double averageAbsoluteDual(LP.PricingDualSnapshot dual) {
		double sum = Math.abs(dual.machineDual) + Math.abs(dual.outsourcingColumnDual);
		int count = 2;
		for (int i = 1; i < dual.jobDual.length; i++) {
			sum += Math.abs(dual.jobDual[i]);
			count++;
		}
		for (int i = 0; i < dual.arcDual.length; i++) {
			for (int j = 0; j < dual.arcDual[i].length; j++) {
				sum += Math.abs(dual.arcDual[i][j]);
				count++;
			}
		}
		return count == 0 ? 0.0 : sum / count;
	}

	private static double blendObjective(double currentObjective, double centerObjective, double currentWeight) {
		if (!Double.isFinite(currentObjective) || !Double.isFinite(centerObjective)) {
			return Double.NaN;
		}
		return currentWeight * currentObjective + (1.0 - currentWeight) * centerObjective;
	}

	private static double norm(LP.PricingDualSnapshot dual) {
		double sum = dual.machineDual * dual.machineDual + dual.outsourcingColumnDual * dual.outsourcingColumnDual;
		for (int i = 1; i < dual.jobDual.length; i++) {
			sum += dual.jobDual[i] * dual.jobDual[i];
		}
		for (int i = 0; i < dual.arcDual.length; i++) {
			for (int j = 0; j < dual.arcDual[i].length; j++) {
				sum += dual.arcDual[i][j] * dual.arcDual[i][j];
			}
		}
		return Math.sqrt(sum);
	}

	private static double normDifference(LP.PricingDualSnapshot a, LP.PricingDualSnapshot b) {
		return norm(difference(a, b));
	}

	private static double dotDifferenceGradient(LP.PricingDualSnapshot out, LP.PricingDualSnapshot center,
			LP.PricingDualSnapshot gradient) {
		return dot(difference(out, center), gradient);
	}

	private static double dot(LP.PricingDualSnapshot a, LP.PricingDualSnapshot b) {
		double sum = a.machineDual * b.machineDual + a.outsourcingColumnDual * b.outsourcingColumnDual;
		int jobLimit = Math.min(a.jobDual.length, b.jobDual.length);
		for (int i = 1; i < jobLimit; i++) {
			sum += a.jobDual[i] * b.jobDual[i];
		}
		int arcLimit = Math.min(a.arcDual.length, b.arcDual.length);
		for (int i = 0; i < arcLimit; i++) {
			int innerLimit = Math.min(a.arcDual[i].length, b.arcDual[i].length);
			for (int j = 0; j < innerLimit; j++) {
				sum += a.arcDual[i][j] * b.arcDual[i][j];
			}
		}
		return sum;
	}

	private static LP.PricingDualSnapshot difference(LP.PricingDualSnapshot a, LP.PricingDualSnapshot b) {
		double[] job = new double[a.jobDual.length];
		for (int i = 0; i < job.length; i++) {
			job[i] = a.jobDual[i] - b.jobDual[i];
		}
		double[][] arc = new double[a.arcDual.length][];
		for (int i = 0; i < a.arcDual.length; i++) {
			arc[i] = new double[a.arcDual[i].length];
			for (int j = 0; j < a.arcDual[i].length; j++) {
				arc[i][j] = a.arcDual[i][j] - b.arcDual[i][j];
			}
		}
		return new LP.PricingDualSnapshot(job, a.machineDual - b.machineDual,
				a.outsourcingColumnDual - b.outsourcingColumnDual, arc);
	}

	private static LP.PricingDualSnapshot addScaled(LP.PricingDualSnapshot base, LP.PricingDualSnapshot direction,
			double scale) {
		double[] job = new double[base.jobDual.length];
		for (int i = 0; i < job.length; i++) {
			job[i] = base.jobDual[i] + scale * direction.jobDual[i];
		}
		double[][] arc = new double[base.arcDual.length][];
		for (int i = 0; i < base.arcDual.length; i++) {
			arc[i] = new double[base.arcDual[i].length];
			for (int j = 0; j < base.arcDual[i].length; j++) {
				arc[i][j] = base.arcDual[i][j] + scale * direction.arcDual[i][j];
			}
		}
		return new LP.PricingDualSnapshot(job, base.machineDual + scale * direction.machineDual,
				base.outsourcingColumnDual + scale * direction.outsourcingColumnDual, arc);
	}

	private static LP.PricingDualSnapshot blendTwo(LP.PricingDualSnapshot first, LP.PricingDualSnapshot second,
			double firstWeight) {
		return LP.PricingDualSnapshot.blend(first, second, firstWeight);
	}

	private TWETMasterSolution repairInfeasibleMaster(LP lp) {
		// 2026-05-18: 正常 RMP 不可行时，先建立带人工 slack 的同一节点 LP；
		// slack 产生的 dual 用于引导启发式和精确定价器补列，补到 slack=0 后再回到正常 RMP。
		lp.setFeasibilityRepairMode(true);
		TWETMasterSolution solution = solveRelaxationTimed(lp, "repair_slack_initial");
		if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
			lp.setFeasibilityRepairMode(false);
			return solution;
		}

		int generatedForRepair = 0;
		while (!lp.isNoSlack() && generatedForRepair < config.maxBranchRepairColumns) {
			boolean addedInThisPass = false;
			for (int engineIndex = 0; engineIndex < pricingEngines.size()
					&& generatedForRepair < config.maxBranchRepairColumns; engineIndex++) {
				PricingEngine engine = pricingEngines.get(engineIndex);
				boolean addedByThisEngine = false;
				boolean keepCurrentEngine = true;
				while (keepCurrentEngine && generatedForRepair < config.maxBranchRepairColumns) {
					HashSet<Integer> activeColumnIds = new HashSet<Integer>(lp.getRestrictedColumnIds());
					HashSet<Integer> activeOutsourcingColumnIds =
							new HashSet<Integer>(lp.getRestrictedOutsourcingColumnIds());
					GeneratedColumnIds generated = generateColumnsFromEngine(lp, engine, true, activeColumnIds,
							activeOutsourcingColumnIds);
					if (generated.isEmpty()) {
						break;
					}
					int addedColumns = lp.addColumns(generated.internalColumnIds)
							+ lp.addOutsourcingColumns(generated.outsourcingColumnIds);
					generatedForRepair += addedColumns;
					if (addedColumns == 0) {
						break;
					}
					addedInThisPass = true;
					addedByThisEngine = true;
					solution = resolveCurrentModelTimed(lp, "repair_after_pricing");
					if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
						lp.setFeasibilityRepairMode(false);
						return solution;
					}
					// 2026-05-18: 旧 GCTabu.FindFeasible 会在启发式仍能补列且 slack 未归零时继续用启发式；
					// exact 定价不反复占用这一层，执行一次后把控制权交回外层 repair pass。
					keepCurrentEngine = engine.repeatFindFeasibleUntilExhausted() && !lp.isNoSlack();
				}
				if (addedByThisEngine) {
					resetFollowingPricingEngines(engineIndex + 1);
				}
			}
			if (!addedInThisPass) {
				break;
			}
		}

		if (!lp.isNoSlack()) {
			lp.setFeasibilityRepairMode(false);
			return new TWETMasterSolution(TWETMasterStatus.INFEASIBLE,
					new java.util.LinkedHashMap<Integer, Double>(), 0.0, false,
					"Repair RMP still has positive artificial slack after generating " + generatedForRepair
							+ " columns");
		}

		// 2026-05-18: 旧 VRP UpdateRouteSet 在 FindFeasible 成功后会按当前子节点 LP 的
		// reduced cost 重筛 route set。这里保持同一语义，避免 repair 期间生成的列全部继续携带。
		lp.resetRestrictedColumnsByCurrentReducedCost(config.branchSeedColumnLimit,
				config.branchSeedReducedCostAllowance);
		lp.setFeasibilityRepairMode(false);
		return solveRelaxationTimed(lp, "repair_final");
	}

	private GeneratedColumnIds generateColumnsFromEngine(LP lp, PricingEngine engine, boolean repairMode,
			HashSet<Integer> activeColumnIds, HashSet<Integer> activeOutsourcingColumnIds) {
		return generateColumnsFromEngine(lp, engine, repairMode, activeColumnIds, activeOutsourcingColumnIds, "",
				true, null, Double.NaN);
	}

	private GeneratedColumnIds generateColumnsFromEngine(LP lp, PricingEngine engine, boolean repairMode,
			HashSet<Integer> activeColumnIds, HashSet<Integer> activeOutsourcingColumnIds, String dualModeName,
			boolean allowReusableBounds, LP.PricingDualSnapshot acceptanceDual, double dualBoundObjectiveOverride) {
		GeneratedColumnIds generated = new GeneratedColumnIds();
		boolean filterByAcceptanceDual = acceptanceDual != null;
		LP.PricingDualSnapshot reducedCostDual = acceptanceDual;
		boolean observeDualBound = shouldObserveDualBound(lp, engine, repairMode, dualBoundObjectiveOverride);
		if (reducedCostDual == null && observeDualBound) {
			reducedCostDual = lp.captureTruePricingDuals();
		}
		String modeSuffix = dualModeName == null || dualModeName.length() == 0 ? "" : "." + dualModeName;
		heartbeat(lp, (repairMode ? "pricing.repair." : "pricing.") + engine.getName() + modeSuffix + ".start");
		long pricingStart = System.nanoTime();
		PricingResult result = repairMode ? engine.findFeasible(lp) : engine.price(lp);
		long pricingNanos = System.nanoTime() - pricingStart;
		generated.observeCertifiedReducedCosts(result);
		if (!repairMode && !result.isImproved()) {
			if (allowReusableBounds) {
				CompletionBoundSubtreeArcEliminator.PreparedBounds reusableBounds =
						engine.getReusableSubtreeArcEliminationBounds();
				if (reusableBounds != null) {
					lastReusableSubtreeArcEliminationBounds = reusableBounds;
				}
			}
		} else if (!repairMode) {
			lastReusableSubtreeArcEliminationBounds = null;
		}
		int addedColumns = 0;
		int filteredByAcceptanceDual = 0;
		if (result.isImproved()) {
			for (int i = 0; i < result.getColumns().size(); i++) {
				TWETBPC.Model.TWETColumn column = result.getColumns().get(i);
				double reducedCost = reducedCostDual == null ? Double.NEGATIVE_INFINITY
						: lp.computeReducedCost(column, reducedCostDual);
				if (reducedCostDual != null) {
					generated.observeReducedCost(reducedCost, column, null);
				}
				if (filterByAcceptanceDual && reducedCost >= -config.dualStabilizationReducedCostTolerance) {
					filteredByAcceptanceDual++;
					continue;
				}
				if (!generated.hasRepresentative()) {
					generated.representativeColumn = column;
				}
				int id = lp.getPool().addColumn(column.getSequence(), column.getCost(), column.getSource(),
						column.isSeedColumn());
				Integer value = Integer.valueOf(id);
				if (activeColumnIds.add(value)) {
					generated.internalColumnIds.add(value);
					addedColumns++;
				}
			}
			for (int i = 0; i < result.getOutsourcingColumns().size(); i++) {
				TWETBPC.Model.TWETOutsourcingColumn column = result.getOutsourcingColumns().get(i);
				double reducedCost = reducedCostDual == null ? Double.NEGATIVE_INFINITY
						: lp.computeReducedCost(column, reducedCostDual);
				if (reducedCostDual != null) {
					generated.observeReducedCost(reducedCost, null, column);
				}
				if (filterByAcceptanceDual && reducedCost >= -config.dualStabilizationReducedCostTolerance) {
					filteredByAcceptanceDual++;
					continue;
				}
				if (!generated.hasRepresentative()) {
					generated.representativeOutsourcingColumn = column;
				}
				int id = lp.getOutsourcingPool().addColumn(column);
				if (id < 0) {
					continue;
				}
				Integer value = Integer.valueOf(id);
				if (activeOutsourcingColumnIds.add(value)) {
					generated.outsourcingColumnIds.add(value);
					addedColumns++;
				}
			}
		}
		if (observeDualBound && shouldPairOutsourcingPricingForDualBound(lp, engine, repairMode, result)) {
			PricingEngine outsourcingEngine = findOutsourcingPricingEngine();
			if (outsourcingEngine != null) {
				GeneratedColumnIds outsourcingGenerated = generateColumnsFromEngine(lp, outsourcingEngine, false,
						activeColumnIds, activeOutsourcingColumnIds,
						dualModeName == null || dualModeName.length() == 0 ? "pairedOutsourcing"
								: dualModeName + ".pairedOutsourcing",
						false, reducedCostDual, dualBoundObjectiveOverride);
				generated.merge(outsourcingGenerated);
			}
		}
		String name = repairMode ? engine.getName() + "[FindFeasible]" : engine.getName();
		if (!repairMode && dualModeName != null && dualModeName.length() > 0) {
			name += "[" + dualModeName + "]";
		}
		String message = result.getMessage();
		if (reducedCostDual != null) {
			double observedDualBound = observeDualBound ? observedDualBoundEstimate(lp, generated,
					dualBoundObjectiveOverride) : Double.NaN;
			if (observeDualBound && Double.isFinite(observedDualBound)) {
				lastObservedDualBound = Math.max(lastObservedDualBound, observedDualBound);
				if (config.enableDualBoundPruning && Double.isFinite(incumbentForDualBoundPruning)
						&& observedDualBound >= incumbentForDualBoundPruning - config.dualBoundPruningTolerance) {
					lastNodePrunedByDualBound = true;
				}
			}
			message += " acceptedBestRc=" + generated.bestAcceptedReducedCost + " observedDualBound="
					+ observedDualBound + " filteredByOutDual=" + filteredByAcceptanceDual;
		}
		traceSink.onPricingCall(lp.getNode(), name, addedColumns > 0, addedColumns, message,
				totalPoolSize(lp), pricingNanos);
		return generated;
	}

	private int totalPoolSize(LP lp) {
		return lp.getPool().size() + (lp.isColumnizedOutsourcing() ? lp.getOutsourcingPool().size() : 0);
	}

	private TWETMasterSolution solveRelaxationTimed(LP lp, String phase) {
		heartbeat(lp, "master." + phase + ".start");
		long start = System.nanoTime();
		TWETMasterSolution solution = lp.solveRelaxation();
		long elapsed = System.nanoTime() - start;
		traceSink.onMasterLpSolve(lp.getNode(), phase, elapsed);
		traceSink.onMasterLpSolution(lp.getNode(), phase, solution, lp.getRestrictedColumnIds().size(),
				totalPoolSize(lp), elapsed);
		return solution;
	}

	private TWETMasterSolution resolveCurrentModelTimed(LP lp, String phase) {
		heartbeat(lp, "master." + phase + ".start");
		long start = System.nanoTime();
		TWETMasterSolution solution = lp.resolveCurrentModel();
		long elapsed = System.nanoTime() - start;
		traceSink.onMasterLpSolve(lp.getNode(), phase, elapsed);
		traceSink.onMasterLpSolution(lp.getNode(), phase, solution, lp.getRestrictedColumnIds().size(),
				totalPoolSize(lp), elapsed);
		return solution;
	}

	private void resetFollowingPricingEngines(int startIndex) {
		for (int i = startIndex; i < pricingEngines.size(); i++) {
			pricingEngines.get(i).reset();
		}
	}

	private void heartbeat(LP lp, String phase) {
		if (!config.diagnosticStageHeartbeat) {
			return;
		}
		Node node = lp.getNode();
		String nodeId = node == null ? "-" : Integer.toString(node.id);
		System.out.println("[BPC heartbeat] node=" + nodeId + " phase=" + phase
				+ " restricted=" + lp.getRestrictedColumnIds().size()
				+ " pool=" + lp.getPool().size() + " cuts=" + lp.getCutPool().size());
		System.out.flush();
	}

	private boolean shouldObserveDualBound(LP lp, PricingEngine engine, boolean repairMode,
			double dualBoundObjectiveOverride) {
		if (repairMode || !config.enableDualBoundPruning) {
			return false;
		}
		// 2026-06-22: observed dual bound 必须使用同一套 dual objective 和 rc_min 证书。
		// active SRI 和 directional smoothing 目前没有完整 objective 口径，先只找列不剪枝。
		if (!lp.getActiveSubsetRowPricingCutIds().isEmpty()) {
			return false;
		}
		if (lp.hasPricingDualOverride() && !Double.isFinite(dualBoundObjectiveOverride)) {
			return false;
		}
		String name = engine.getName().toLowerCase();
		return !name.contains("heuristic");
	}

	private boolean shouldPairOutsourcingPricingForDualBound(LP lp, PricingEngine engine, boolean repairMode,
			PricingResult result) {
		return !repairMode && config.enableDualBoundPruning && lp.isColumnizedOutsourcing()
				&& !(engine instanceof OutsourcingPricingEngine)
				&& Double.isFinite(result.getCertifiedInternalReducedCost());
	}

	private PricingEngine findOutsourcingPricingEngine() {
		for (PricingEngine engine : pricingEngines) {
			if (engine instanceof OutsourcingPricingEngine) {
				return engine;
			}
		}
		return null;
	}

	private double observedDualBoundEstimate(LP lp, GeneratedColumnIds generated, double dualBoundObjectiveOverride) {
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return Double.NaN;
		}
		if (!Double.isFinite(generated.certifiedInternalReducedCost)) {
			return Double.NaN;
		}
		if (lp.isColumnizedOutsourcing() && !Double.isFinite(generated.certifiedOutsourcingReducedCost)) {
			return Double.NaN;
		}
		double baseObjective = Double.isFinite(dualBoundObjectiveOverride) ? dualBoundObjectiveOverride
				: solution.getObjectiveValue();
		double correction = 0.0;
		if (generated.certifiedInternalReducedCost < 0.0) {
			int machineCopies = lp.getNode() == null ? 1 : Math.max(1, lp.getNode().maxMachineCount);
			correction += machineCopies * generated.certifiedInternalReducedCost;
		}
		if (lp.isColumnizedOutsourcing() && generated.certifiedOutsourcingReducedCost < 0.0) {
			correction += generated.certifiedOutsourcingReducedCost;
		}
		return baseObjective + correction;
	}

	private static final class GeneratedColumnIds {
		final ArrayList<Integer> internalColumnIds = new ArrayList<Integer>();
		final ArrayList<Integer> outsourcingColumnIds = new ArrayList<Integer>();
		double bestAcceptedReducedCost = Double.POSITIVE_INFINITY;
		double bestInternalReducedCost = Double.POSITIVE_INFINITY;
		double bestOutsourcingReducedCost = Double.POSITIVE_INFINITY;
		double certifiedInternalReducedCost = Double.NaN;
		double certifiedOutsourcingReducedCost = Double.NaN;
		TWETColumn representativeColumn;
		TWETOutsourcingColumn representativeOutsourcingColumn;

		boolean isEmpty() {
			return internalColumnIds.isEmpty() && outsourcingColumnIds.isEmpty();
		}

		boolean hasRepresentative() {
			return representativeColumn != null || representativeOutsourcingColumn != null;
		}

		void observeReducedCost(double reducedCost, TWETColumn column, TWETOutsourcingColumn outsourcingColumn) {
			if (column != null && reducedCost < bestInternalReducedCost) {
				bestInternalReducedCost = reducedCost;
			}
			if (outsourcingColumn != null && reducedCost < bestOutsourcingReducedCost) {
				bestOutsourcingReducedCost = reducedCost;
			}
			if (reducedCost < bestAcceptedReducedCost) {
				bestAcceptedReducedCost = reducedCost;
				representativeColumn = column;
				representativeOutsourcingColumn = outsourcingColumn;
			}
		}

		void observeCertifiedReducedCosts(PricingResult result) {
			if (Double.isFinite(result.getCertifiedInternalReducedCost())) {
				certifiedInternalReducedCost = result.getCertifiedInternalReducedCost();
			}
			if (Double.isFinite(result.getCertifiedOutsourcingReducedCost())) {
				certifiedOutsourcingReducedCost = result.getCertifiedOutsourcingReducedCost();
			}
		}

		void merge(GeneratedColumnIds other) {
			internalColumnIds.addAll(other.internalColumnIds);
			outsourcingColumnIds.addAll(other.outsourcingColumnIds);
			if (other.bestAcceptedReducedCost < bestAcceptedReducedCost) {
				bestAcceptedReducedCost = other.bestAcceptedReducedCost;
			}
			if (other.bestInternalReducedCost < bestInternalReducedCost) {
				bestInternalReducedCost = other.bestInternalReducedCost;
			}
			if (other.bestOutsourcingReducedCost < bestOutsourcingReducedCost) {
				bestOutsourcingReducedCost = other.bestOutsourcingReducedCost;
			}
			if (Double.isFinite(other.certifiedInternalReducedCost)) {
				certifiedInternalReducedCost = other.certifiedInternalReducedCost;
			}
			if (Double.isFinite(other.certifiedOutsourcingReducedCost)) {
				certifiedOutsourcingReducedCost = other.certifiedOutsourcingReducedCost;
			}
			if (representativeColumn == null) {
				representativeColumn = other.representativeColumn;
			}
			if (representativeOutsourcingColumn == null) {
				representativeOutsourcingColumn = other.representativeOutsourcingColumn;
			}
		}
	}

}
