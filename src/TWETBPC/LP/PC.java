package TWETBPC.LP;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.CUT.CutGenerationResult;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.GC.CompletionBoundSubtreeArcEliminator;
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

	public PC(TWETBPCConfig config, List<PricingEngine> pricingEngines, List<CutGenerator> cutGenerators,
			BPCTraceSink traceSink) {
		this.config = config;
		this.pricingEngines = pricingEngines;
		this.cutGenerators = cutGenerators;
		this.traceSink = traceSink;
	}

	public TWETMasterSolution solve(LP lp) {
		lastReusableSubtreeArcEliminationBounds = null;
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
		DualStabilizationState dualState = new DualStabilizationState(lp.captureTruePricingDuals());
		boolean penaltyActive = config.dualStabilizationUsePenalty;
		if (penaltyActive) {
			lp.setDualPenaltyProfile(dualState.penaltyProfile());
			solution = solveRelaxationTimed(lp, "stabilized_penalty_initial");
		}
		while (true) {
			LP.PricingDualSnapshot outDual = lp.captureTruePricingDuals();
			PricingPassResult stabilizedPass = runStabilizedPricingSequence(lp, dualState, outDual);
			if (stabilizedPass.addedColumns > 0) {
				solution = resolveCurrentModelTimed(lp, "after_pricing_stabilized");
				dualState.observeAccepted(lp.captureTruePricingDuals(), stabilizedPass);
				if (penaltyActive) {
					dualState.updatePenaltyAfterMaster(lp.getDualPenaltyArtificialActivity());
				}
				continue;
			}
			if (penaltyActive && lp.getDualPenaltyArtificialActivity() > config.dualStabilizationPenaltyArtificialTolerance
					&& dualState.relaxPenalty()) {
				lp.setDualPenaltyProfile(dualState.penaltyProfile());
				solution = solveRelaxationTimed(lp, "stabilized_penalty_relaxed");
				continue;
			}

			if (lp.hasDualPenaltyProfile()) {
				lp.clearDualPenaltyProfile();
				solution = solveRelaxationTimed(lp, "true_master_after_penalty");
			}
			PricingPassResult truePass = runPricingPass(lp, "true", true);
			if (truePass.addedColumns > 0) {
				solution = resolveCurrentModelTimed(lp, "after_pricing_true");
				dualState.observeAccepted(lp.captureTruePricingDuals(), truePass);
				if (penaltyActive) {
					lp.setDualPenaltyProfile(dualState.penaltyProfile());
					solution = solveRelaxationTimed(lp, "stabilized_penalty_restart");
				}
				continue;
			}
			break;
		}
		lp.clearPricingDualOverride();
		if (lp.hasDualPenaltyProfile()) {
			lp.clearDualPenaltyProfile();
		}
		return solution;
	}

	private PricingPassResult runStabilizedPricingSequence(LP lp, DualStabilizationState dualState,
			LP.PricingDualSnapshot outDual) {
		double initialAlpha = dualState.alpha;
		int attempt = 0;
		while (true) {
			double alpha = attempt == 0 ? initialAlpha : Math.max(0.0, 1.0 - (attempt + 1) * (1.0 - initialAlpha));
			lp.setPricingDualOverride(dualState.stabilizedDual(outDual, alpha, attempt == 0));
			PricingPassResult pass;
			try {
				pass = runPricingPass(lp, "stabilized.a" + String.format("%.3f", Double.valueOf(alpha)), false, outDual);
			} finally {
				lp.clearPricingDualOverride();
			}
			if (pass.addedColumns > 0 || alpha <= 0.0) {
				dualState.alpha = alpha;
				return pass;
			}
			attempt++;
		}
	}

	private PricingPassResult runPricingPass(LP lp, String dualModeName, boolean allowReusableBounds) {
		return runPricingPass(lp, dualModeName, allowReusableBounds, null);
	}

	private PricingPassResult runPricingPass(LP lp, String dualModeName, boolean allowReusableBounds,
			LP.PricingDualSnapshot acceptanceDual) {
		for (int engineIndex = 0; engineIndex < pricingEngines.size(); engineIndex++) {
			PricingEngine engine = pricingEngines.get(engineIndex);
			HashSet<Integer> activeColumnIds = new HashSet<Integer>(lp.getRestrictedColumnIds());
			HashSet<Integer> activeOutsourcingColumnIds =
					new HashSet<Integer>(lp.getRestrictedOutsourcingColumnIds());
			GeneratedColumnIds generated = generateColumnsFromEngine(lp, engine, false, activeColumnIds,
					activeOutsourcingColumnIds, dualModeName, allowReusableBounds, acceptanceDual);
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
		private LP.PricingDualSnapshot lastAcceptedGradient;
		private double alpha;
		private double penaltyDelta;
		private double penaltyGamma;
		private double penaltyCurvature;
		private int penaltyRelaxations;

		DualStabilizationState(LP.PricingDualSnapshot initialCenter) {
			this.center = initialCenter.copy();
			this.alpha = Math.max(0.0, Math.min(0.95, config.dualStabilizationAlpha));
			double scale = Math.max(1.0, averageAbsoluteDual(initialCenter));
			this.penaltyDelta = scale / Math.max(1.0, config.dualStabilizationPenaltyKappa);
			this.penaltyGamma = Math.max(1e-6, config.dualStabilizationPenaltyInitialGamma);
			this.penaltyCurvature = this.penaltyDelta;
		}

		LP.PricingDualSnapshot stabilizedDual(LP.PricingDualSnapshot outDual, double attemptAlpha,
				boolean allowDirectional) {
			double clippedAlpha = Math.max(0.0, Math.min(0.95, attemptAlpha));
			LP.PricingDualSnapshot ordinary = LP.PricingDualSnapshot.blend(outDual, center, 1.0 - clippedAlpha);
			if (!allowDirectional || !config.dualStabilizationDirectionalSmoothing || lastAcceptedGradient == null
					|| clippedAlpha <= 0.0) {
				return ordinary;
			}
			double distance = normDifference(outDual, center);
			double gradientNorm = norm(lastAcceptedGradient);
			if (distance <= 1e-9 || gradientNorm <= 1e-9) {
				return ordinary;
			}
			double beta = Math.max(0.0, Math.min(1.0, dotDifferenceGradient(outDual, center, lastAcceptedGradient)
					/ (distance * gradientNorm)));
			if (beta <= 0.0) {
				return ordinary;
			}
			LP.PricingDualSnapshot gradientPoint = addScaled(center, lastAcceptedGradient, distance / gradientNorm);
			LP.PricingDualSnapshot rho = blendTwo(gradientPoint, outDual, beta);
			double rhoDistance = normDifference(rho, center);
			if (rhoDistance <= 1e-9) {
				return ordinary;
			}
			return addScaled(center, difference(rho, center), (1.0 - clippedAlpha) * distance / rhoDistance);
		}

		LP.DualPenaltyProfile penaltyProfile() {
			double delta = penaltyDelta;
			double gamma = penaltyGamma;
			if ("curvature".equalsIgnoreCase(config.dualStabilizationPenaltyMode)) {
				delta = Math.max(1e-6, norm(lastAcceptedGradient == null ? center : lastAcceptedGradient) / 2.0);
				gamma = Math.max(1e-6, delta / Math.max(1e-6, penaltyCurvature));
			}
			return new LP.DualPenaltyProfile(center, delta, gamma);
		}

		void observeAccepted(LP.PricingDualSnapshot trueDual, PricingPassResult pass) {
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
			center = LP.PricingDualSnapshot.blend(trueDual, center, config.dualStabilizationCenterMoveWeight);
		}

		void updatePenaltyAfterMaster(double artificialActivity) {
			if (artificialActivity > config.dualStabilizationPenaltyArtificialTolerance) {
				return;
			}
			if ("curvature".equalsIgnoreCase(config.dualStabilizationPenaltyMode)) {
				penaltyCurvature = Math.max(1e-6, penaltyCurvature * 0.5);
			} else {
				penaltyDelta = Math.max(1e-6, penaltyDelta * 0.5);
			}
		}

		boolean relaxPenalty() {
			if (penaltyRelaxations >= config.dualStabilizationPenaltyMaxRelaxations) {
				return false;
			}
			penaltyRelaxations++;
			if ("curvature".equalsIgnoreCase(config.dualStabilizationPenaltyMode)) {
				penaltyCurvature *= 10.0;
			} else {
				penaltyGamma = Math.max(1e-6, penaltyGamma / 10.0);
			}
			return true;
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

	private static final class PricingPassResult {
		static final PricingPassResult EMPTY = new PricingPassResult(0, Double.POSITIVE_INFINITY, null, null);

		final int addedColumns;
		final double bestAcceptedReducedCost;
		final TWETColumn representativeColumn;
		final TWETOutsourcingColumn representativeOutsourcingColumn;

		PricingPassResult(int addedColumns, double bestAcceptedReducedCost, TWETColumn representativeColumn,
				TWETOutsourcingColumn representativeOutsourcingColumn) {
			this.addedColumns = addedColumns;
			this.bestAcceptedReducedCost = bestAcceptedReducedCost;
			this.representativeColumn = representativeColumn;
			this.representativeOutsourcingColumn = representativeOutsourcingColumn;
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
				true, null);
	}

	private GeneratedColumnIds generateColumnsFromEngine(LP lp, PricingEngine engine, boolean repairMode,
			HashSet<Integer> activeColumnIds, HashSet<Integer> activeOutsourcingColumnIds, String dualModeName,
			boolean allowReusableBounds, LP.PricingDualSnapshot acceptanceDual) {
		GeneratedColumnIds generated = new GeneratedColumnIds();
		String modeSuffix = dualModeName == null || dualModeName.length() == 0 ? "" : "." + dualModeName;
		heartbeat(lp, (repairMode ? "pricing.repair." : "pricing.") + engine.getName() + modeSuffix + ".start");
		long pricingStart = System.nanoTime();
		PricingResult result = repairMode ? engine.findFeasible(lp) : engine.price(lp);
		long pricingNanos = System.nanoTime() - pricingStart;
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
				double acceptanceReducedCost = acceptanceDual == null ? Double.NEGATIVE_INFINITY
						: lp.computeReducedCost(column, acceptanceDual);
				if (acceptanceDual != null) {
					generated.observeReducedCost(acceptanceReducedCost, column, null);
					if (acceptanceReducedCost >= -config.dualStabilizationReducedCostTolerance) {
						filteredByAcceptanceDual++;
						continue;
					}
				} else if (!generated.hasRepresentative()) {
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
				double acceptanceReducedCost = acceptanceDual == null ? Double.NEGATIVE_INFINITY
						: lp.computeReducedCost(column, acceptanceDual);
				if (acceptanceDual != null) {
					generated.observeReducedCost(acceptanceReducedCost, null, column);
					if (acceptanceReducedCost >= -config.dualStabilizationReducedCostTolerance) {
						filteredByAcceptanceDual++;
						continue;
					}
				} else if (!generated.hasRepresentative()) {
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
		String name = repairMode ? engine.getName() + "[FindFeasible]" : engine.getName();
		if (!repairMode && dualModeName != null && dualModeName.length() > 0) {
			name += "[" + dualModeName + "]";
		}
		String message = result.getMessage();
		if (acceptanceDual != null) {
			double observedDualBound = observedDualBoundEstimate(lp, generated);
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

	private double observedDualBoundEstimate(LP lp, GeneratedColumnIds generated) {
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return Double.NaN;
		}
		double correction = 0.0;
		if (generated.bestInternalReducedCost < 0.0) {
			int machineCopies = lp.getNode() == null ? 1 : Math.max(1, lp.getNode().maxMachineCount);
			correction += machineCopies * generated.bestInternalReducedCost;
		}
		if (lp.isColumnizedOutsourcing() && generated.bestOutsourcingReducedCost < 0.0) {
			correction += generated.bestOutsourcingReducedCost;
		}
		return solution.getObjectiveValue() + correction;
	}

	private static final class GeneratedColumnIds {
		final ArrayList<Integer> internalColumnIds = new ArrayList<Integer>();
		final ArrayList<Integer> outsourcingColumnIds = new ArrayList<Integer>();
		double bestAcceptedReducedCost = Double.POSITIVE_INFINITY;
		double bestInternalReducedCost = Double.POSITIVE_INFINITY;
		double bestOutsourcingReducedCost = Double.POSITIVE_INFINITY;
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
	}

}
