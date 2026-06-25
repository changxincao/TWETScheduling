package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETOutsourcingColumn;

/**
 * SP1 外包集合列定价。
 * <p>
 * 定价问题等价于选择一个外包 job 集合 O，使
 * G(sum b_j) - sum pi_j - mu 最小。这里按文章思路用 baseline/profit 二维 label 做集合 DP。
 */
public class OutsourcingPricingEngine implements PricingEngine {

	private static final double REDUCED_COST_TOLERANCE = 1e-8;

	private final Data data;
	private final TWETBPCConfig config;

	public OutsourcingPricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
	}

	@Override
	public PricingResult price(LP lp) {
		if (!lp.isColumnizedOutsourcing()) {
			return PricingResult.noImprovement("Outsourcing column pricing disabled");
		}
		Node node = lp.getNode();
		// 2026-06-25: required 外包 job 是公共常数，不进入中间 label，避免每次 include 都复制它们。
		ArrayList<Integer> requiredJobs = new ArrayList<Integer>();
		double requiredBaseline = 0.0;
		double requiredProfit = 0.0;
		ArrayList<Integer> freeJobs = new ArrayList<Integer>();
		for (int job = 1; job <= data.n; job++) {
			if (!lp.getOutsourcingPool().isOutsourceable(job)) {
				continue;
			}
			byte state = node.getOutsourcingJobState(job);
			if (state == Node.OUTSOURCE_FORBIDDEN) {
				continue;
			}
			if (state == Node.OUTSOURCE_REQUIRED) {
				requiredJobs.add(Integer.valueOf(job));
				requiredBaseline += data.outsourcingCost[job];
				requiredProfit += lp.getJobDual(job);
			} else {
				freeJobs.add(Integer.valueOf(job));
			}
		}

		ArrayList<Label> labels = new ArrayList<Label>();
		labels.add(new Label());
		for (int idx = 0; idx < freeJobs.size(); idx++) {
			int job = freeJobs.get(idx).intValue();
			ArrayList<Label> next = new ArrayList<Label>(labels.size() * 2);
			next.addAll(labels);
			for (Label label : labels) {
				next.add(label.include(job, data.outsourcingCost[job], lp.getJobDual(job)));
			}
			labels = prune(next);
		}

		ArrayList<Candidate> candidates = new ArrayList<Candidate>();
		double bestReducedCost = Double.POSITIVE_INFINITY;
		for (Label label : labels) {
			if (requiredJobs.isEmpty() && label.jobs.isEmpty()) {
				continue;
			}
			double baseline = requiredBaseline + label.baseline;
			double profit = requiredProfit + label.profit;
			double cost = data.evaluateOutsourcingCost(baseline);
			if (Utility.isBigMValue(cost)) {
				continue;
			}
			double reducedCost = cost - profit - lp.getOutsourcingColumnDual();
			if (Utility.compareLt(reducedCost, bestReducedCost)) {
				bestReducedCost = reducedCost;
			}
			if (Utility.compareLt(reducedCost, -REDUCED_COST_TOLERANCE)) {
				candidates.add(new Candidate(mergeJobs(requiredJobs, label.jobs), baseline, cost, reducedCost));
			}
		}
		if (Double.isInfinite(bestReducedCost)) {
			bestReducedCost = 0.0;
		}
		if (candidates.isEmpty()) {
			return PricingResult.noImprovement("No negative outsourcing column")
					.withCertifiedOutsourcingReducedCost(bestReducedCost);
		}
		Collections.sort(candidates, new Comparator<Candidate>() {
			@Override
			public int compare(Candidate a, Candidate b) {
				int reducedCostOrder = Double.compare(a.reducedCost, b.reducedCost);
				if (reducedCostOrder != 0) {
					return reducedCostOrder;
				}
				return Integer.compare(a.jobs.size(), b.jobs.size());
			}
		});
		ArrayList<TWETOutsourcingColumn> columns = new ArrayList<TWETOutsourcingColumn>();
		int limit = Math.min(config.maxOutsourcingPricingColumns, candidates.size());
		for (int i = 0; i < limit; i++) {
			Candidate c = candidates.get(i);
			columns.add(new TWETOutsourcingColumn(-1, c.jobs, data.n, c.baseline, c.cost, ColumnSource.PRICING_EXACT,
					false));
		}
		return new PricingResult(Collections.<TWETBPC.Model.TWETColumn>emptyList(), columns, true,
				"Generated " + columns.size() + " outsourcing columns; best rc=" + candidates.get(0).reducedCost)
						.withCertifiedOutsourcingReducedCost(bestReducedCost);
	}

	@Override
	public String getName() {
		return "OutsourcingPricing";
	}

	private ArrayList<Label> prune(ArrayList<Label> labels) {
		// 2026-06-25: dominance 为 baseline 越小、profit 越大越好；排序后只需保留 profit 前沿。
		Collections.sort(labels, new Comparator<Label>() {
			@Override
			public int compare(Label a, Label b) {
				int baselineOrder = Double.compare(a.baseline, b.baseline);
				if (baselineOrder != 0) {
					return baselineOrder;
				}
				int profitOrder = Double.compare(b.profit, a.profit);
				if (profitOrder != 0) {
					return profitOrder;
				}
				return Integer.compare(a.jobs.size(), b.jobs.size());
			}
		});
		ArrayList<Label> kept = new ArrayList<Label>();
		double bestProfit = Double.NEGATIVE_INFINITY;
		for (Label label : labels) {
			if (Utility.compareGt(label.profit, bestProfit)) {
				kept.add(label);
				bestProfit = label.profit;
			}
		}
		return kept;
	}

	private ArrayList<Integer> mergeJobs(List<Integer> requiredJobs, List<Integer> freeLabelJobs) {
		ArrayList<Integer> merged = new ArrayList<Integer>(requiredJobs.size() + freeLabelJobs.size());
		int requiredIdx = 0;
		int freeIdx = 0;
		while (requiredIdx < requiredJobs.size() || freeIdx < freeLabelJobs.size()) {
			if (freeIdx >= freeLabelJobs.size()) {
				merged.add(requiredJobs.get(requiredIdx++));
			} else if (requiredIdx >= requiredJobs.size()) {
				merged.add(freeLabelJobs.get(freeIdx++));
			} else {
				int requiredJob = requiredJobs.get(requiredIdx).intValue();
				int freeJob = freeLabelJobs.get(freeIdx).intValue();
				if (requiredJob < freeJob) {
					merged.add(Integer.valueOf(requiredJob));
					requiredIdx++;
				} else if (freeJob < requiredJob) {
					merged.add(Integer.valueOf(freeJob));
					freeIdx++;
				} else {
					merged.add(Integer.valueOf(requiredJob));
					requiredIdx++;
					freeIdx++;
				}
			}
		}
		return merged;
	}

	private static final class Label {
		final ArrayList<Integer> jobs;
		final double baseline;
		final double profit;

		Label() {
			this.jobs = new ArrayList<Integer>();
			this.baseline = 0.0;
			this.profit = 0.0;
		}

		Label(ArrayList<Integer> jobs, double baseline, double profit) {
			this.jobs = jobs;
			this.baseline = baseline;
			this.profit = profit;
		}

		Label include(int job, double baselineDelta, double profitDelta) {
			ArrayList<Integer> nextJobs = new ArrayList<Integer>(jobs);
			nextJobs.add(Integer.valueOf(job));
			return new Label(nextJobs, baseline + baselineDelta, profit + profitDelta);
		}

	}

	private static final class Candidate {
		final List<Integer> jobs;
		final double baseline;
		final double cost;
		final double reducedCost;

		Candidate(List<Integer> jobs, double baseline, double cost, double reducedCost) {
			this.jobs = jobs;
			this.baseline = baseline;
			this.cost = cost;
			this.reducedCost = reducedCost;
		}
	}
}
