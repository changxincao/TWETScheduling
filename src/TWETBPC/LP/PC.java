package TWETBPC.LP;

import java.util.ArrayList;
import java.util.List;

import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.CUT.CutGenerationResult;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.PricingResult;
import TWETBPC.Model.TWETMasterStatus;
import TWETBPC.Model.TWETMasterSolution;

/**
 * 节点内部的 price-and-cut 控制器。
 */
public class PC {

	private final TWETBPCConfig config;
	private final List<PricingEngine> pricingEngines;
	private final List<CutGenerator> cutGenerators;
	private final BPCTraceSink traceSink;

	public PC(TWETBPCConfig config, List<PricingEngine> pricingEngines, List<CutGenerator> cutGenerators,
			BPCTraceSink traceSink) {
		this.config = config;
		this.pricingEngines = pricingEngines;
		this.cutGenerators = cutGenerators;
		this.traceSink = traceSink;
	}

	public TWETMasterSolution solve(LP lp) {
		// 2026-05-23: 以下计时只写入 trace，用于拆分 RMP、pricing 和 cut 的耗时，
		// 不参与列选择、剪枝或对偶计算，避免改变 BPC 求解流程。
		TWETMasterSolution solution = solveRelaxationTimed(lp, "initial");
		if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
			solution = repairInfeasibleMaster(lp);
			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				return solution;
			}
		} else if (lp.getNode() != null && lp.getNode().depth > 0) {
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
			lp.addCuts(newCutIds);
			solution = solveRelaxationTimed(lp, "after_cut");
		}

		return solution;
	}

	private TWETMasterSolution solvePricingLoop(LP lp, TWETMasterSolution currentSolution) {
		TWETMasterSolution solution = currentSolution;
		while (true) {
			boolean addedColumn = false;
			for (int engineIndex = 0; engineIndex < pricingEngines.size(); engineIndex++) {
				PricingEngine engine = pricingEngines.get(engineIndex);
				ArrayList<Integer> activeColumnIds = new ArrayList<Integer>(lp.getRestrictedColumnIds());
				ArrayList<Integer> newColumnIds = generateColumnsFromEngine(lp, engine, false, activeColumnIds);
				if (newColumnIds.isEmpty()) {
					continue;
				}

				int addedColumns = lp.addColumns(newColumnIds);
				if (addedColumns == 0) {
					continue;
				}

				// 2026-05-19: 对齐旧 VRP PC 的普通 pricing 节奏。一个定价器加列后立即重解 LP，
				// 并从第一个定价器重新开始；这样只要启发式还能补列，就不会提前调用更重的精确定价。
				resetFollowingPricingEngines(engineIndex + 1);
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
					ArrayList<Integer> activeColumnIds = new ArrayList<Integer>(lp.getRestrictedColumnIds());
					ArrayList<Integer> newColumnIds = generateColumnsFromEngine(lp, engine, true, activeColumnIds);
					if (newColumnIds.isEmpty()) {
						break;
					}
					if (generatedForRepair + newColumnIds.size() > config.maxBranchRepairColumns) {
						newColumnIds = new ArrayList<Integer>(
								newColumnIds.subList(0, config.maxBranchRepairColumns - generatedForRepair));
					}
					int addedColumns = lp.addColumns(newColumnIds);
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

	private ArrayList<Integer> generateColumnsFromEngine(LP lp, PricingEngine engine, boolean repairMode,
			ArrayList<Integer> activeColumnIds) {
		ArrayList<Integer> newColumnIds = new ArrayList<Integer>();
		long pricingStart = System.nanoTime();
		PricingResult result = repairMode ? engine.findFeasible(lp) : engine.price(lp);
		long pricingNanos = System.nanoTime() - pricingStart;
		int addedColumns = 0;
		if (result.isImproved()) {
			for (int i = 0; i < result.getColumns().size(); i++) {
				TWETBPC.Model.TWETColumn column = result.getColumns().get(i);
				int id = lp.getPool().addColumn(column.getSequence(), column.getCost(), column.getSource(),
						column.isSeedColumn());
				Integer value = Integer.valueOf(id);
				if (!activeColumnIds.contains(value)) {
					activeColumnIds.add(value);
					newColumnIds.add(value);
					addedColumns++;
				}
			}
		}
		String name = repairMode ? engine.getName() + "[FindFeasible]" : engine.getName();
		traceSink.onPricingCall(lp.getNode(), name, result.isImproved(), addedColumns, result.getMessage(),
				lp.getPool().size(), pricingNanos);
		return newColumnIds;
	}

	private TWETMasterSolution solveRelaxationTimed(LP lp, String phase) {
		long start = System.nanoTime();
		TWETMasterSolution solution = lp.solveRelaxation();
		traceSink.onMasterLpSolve(lp.getNode(), phase, System.nanoTime() - start);
		return solution;
	}

	private TWETMasterSolution resolveCurrentModelTimed(LP lp, String phase) {
		long start = System.nanoTime();
		TWETMasterSolution solution = lp.resolveCurrentModel();
		traceSink.onMasterLpSolve(lp.getNode(), phase, System.nanoTime() - start);
		return solution;
	}

	private void resetFollowingPricingEngines(int startIndex) {
		for (int i = startIndex; i < pricingEngines.size(); i++) {
			pricingEngines.get(i).reset();
		}
	}

}
