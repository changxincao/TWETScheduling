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
		// 2026-06-25: required 外包 job 对所有可行 label 都是公共常数，DP 图里只保留自由可外包 job。
		Label root = new Label();
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
				root = root.include(job, data.outsourcingCost[job], lp.getJobDual(job));
			} else {
				freeJobs.add(Integer.valueOf(job));
			}
		}

		ArrayList<Label> labels = new ArrayList<Label>();
		labels.add(root);
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
			if (label.jobs.isEmpty()) {
				continue;
			}
			double cost = data.evaluateOutsourcingCost(label.baseline);
			if (Utility.isBigMValue(cost)) {
				continue;
			}
			double reducedCost = cost - label.profit - lp.getOutsourcingColumnDual();
			if (Utility.compareLt(reducedCost, bestReducedCost)) {
				bestReducedCost = reducedCost;
			}
			if (Utility.compareLt(reducedCost, -REDUCED_COST_TOLERANCE)) {
				candidates.add(new Candidate(label.jobs, label.baseline, cost, reducedCost));
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
				if (Utility.compareLt(a.reducedCost, b.reducedCost)) {
					return -1;
				}
				if (Utility.compareGt(a.reducedCost, b.reducedCost)) {
					return 1;
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
				if (Utility.compareLt(a.baseline, b.baseline)) {
					return -1;
				}
				if (Utility.compareGt(a.baseline, b.baseline)) {
					return 1;
				}
				if (Utility.compareGt(a.profit, b.profit)) {
					return -1;
				}
				if (Utility.compareLt(a.profit, b.profit)) {
					return 1;
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
