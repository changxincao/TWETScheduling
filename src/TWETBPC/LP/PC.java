package TWETBPC.LP;

import java.util.ArrayList;
import java.util.List;

import Output.BPCTraceSink;
import TWETBPC.TWETBPCConfig;
import TWETBPC.CUT.CutGenerationResult;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.PricingResult;
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
		// reduced cost、违反度等统计；当前骨架阶段只能输出“是否找到改进”和“新增数量”。
		TWETMasterSolution solution = lp.solveRelaxation();
		if (solution.getStatus() == TWETBPC.Model.TWETMasterStatus.INFEASIBLE) {
			return solution;
		}

		while (true) {
			ArrayList<Integer> newColumnIds = new ArrayList<Integer>();
			ArrayList<Integer> activeColumnIds = new ArrayList<Integer>(lp.getRestrictedColumnIds());
			for (PricingEngine engine : pricingEngines) {
				PricingResult result = engine.price(lp);
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
				traceSink.onPricingCall(lp.getNode(), engine.getName(), result.isImproved(), addedColumns,
						result.getMessage(), lp.getPool().size());
			}
			// 2026-05-17: 参考旧 VRP PC，节点内 pricing 不再设固定轮数；
			// 单轮加列数由 pricing 侧控制，PC 一直迭代到没有新的 active 列。
			if (newColumnIds.isEmpty()) {
				break;
			}
			lp.addColumns(newColumnIds);
			solution = lp.solveRelaxation();
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

}
