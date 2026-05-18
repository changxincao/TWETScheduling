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
		// TODO 2026-04-10: 真正接入 RMP 之后，这里还要补每轮 pricing/cut 前后的 dual、
		// reduced cost、违反度等统计；当前骨架阶段只输出“是否找到改进”和“新增数量”。
		TWETMasterSolution solution = lp.solveRelaxation();
		if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
			solution = repairInfeasibleMaster(lp);
			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				return solution;
			}
		} else if (lp.getNode() != null && lp.getNode().depth > 0) {
			// 2026-05-18: 对齐旧 VRP UpdateRouteSet。child 第一次 LP 可行时，也先按当前 LP 的
			// reduced cost 和分支兼容性筛出正式列集，再进入后续 pricing；repair 成功路径也会做同样筛选。
			lp.resetRestrictedColumnsByCurrentReducedCost(config.branchSeedColumnLimit,
					config.branchSeedReducedCostAllowance);
			solution = lp.solveRelaxation();
			if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
				solution = repairInfeasibleMaster(lp);
				if (solution.getStatus() == TWETMasterStatus.INFEASIBLE) {
					return solution;
				}
			}
		}

		while (true) {
			ArrayList<Integer> newColumnIds = generateColumns(lp, false);
			// 2026-05-17: 参考旧 VRP PC，节点内 pricing 不再设固定轮数；
			// 单轮加列数由 pricing 侧控制，PC 一直迭代到没有新的 active 列。
			if (newColumnIds.isEmpty()) {
				break;
			}
			lp.addColumns(newColumnIds);
			solution = lp.resolveCurrentModel();
		}

		for (int round = 0; round < config.maxCutRounds; round++) {
			ArrayList<Integer> newCutIds = new ArrayList<Integer>();
			boolean separated = false;
			for (CutGenerator generator : cutGenerators) {
				CutGenerationResult result = generator.separate(lp);
				int addedCuts = 0;
				if (result.isSeparated()) {
					separated = true;
					for (int i = 0; i < result.getCuts().size(); i++) {
						newCutIds.add(Integer.valueOf(lp.getCutPool().addCut(result.getCuts().get(i))));
					}
					addedCuts = result.getCuts().size();
				}
				traceSink.onCutCall(lp.getNode(), generator.getName(), result.isSeparated(), addedCuts,
						result.getMessage(), lp.getCutPool().size());
			}
			if (!separated) {
				break;
			}
			lp.addCuts(newCutIds);
			solution = lp.solveRelaxation();
		}

		return solution;
	}

	private TWETMasterSolution repairInfeasibleMaster(LP lp) {
		// 2026-05-18: 正常 RMP 不可行时，先建立带人工 slack 的同一节点 LP；
		// slack 产生的 dual 用于引导启发式和精确定价器补列，补到 slack=0 后再回到正常 RMP。
		lp.setFeasibilityRepairMode(true);
		TWETMasterSolution solution = lp.solveRelaxation();
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
					solution = lp.resolveCurrentModel();
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
		return lp.solveRelaxation();
	}

	private ArrayList<Integer> generateColumns(LP lp, boolean repairMode) {
		ArrayList<Integer> newColumnIds = new ArrayList<Integer>();
		ArrayList<Integer> activeColumnIds = new ArrayList<Integer>(lp.getRestrictedColumnIds());
		for (PricingEngine engine : pricingEngines) {
			newColumnIds.addAll(generateColumnsFromEngine(lp, engine, repairMode, activeColumnIds));
		}
		return newColumnIds;
	}

	private ArrayList<Integer> generateColumnsFromEngine(LP lp, PricingEngine engine, boolean repairMode,
			ArrayList<Integer> activeColumnIds) {
		ArrayList<Integer> newColumnIds = new ArrayList<Integer>();
		PricingResult result = repairMode ? engine.findFeasible(lp) : engine.price(lp);
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
				lp.getPool().size());
		return newColumnIds;
	}

	private void resetFollowingPricingEngines(int startIndex) {
		for (int i = startIndex; i < pricingEngines.size(); i++) {
			pricingEngines.get(i).reset();
		}
	}

}
