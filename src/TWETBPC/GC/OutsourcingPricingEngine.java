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
		double baselineLowerBound = node.getOutsourcingBaselineLowerBound();
		double baselineUpperBound = node.getOutsourcingBaselineUpperBound();
		ArrayList<Label> labels = new ArrayList<Label>();
		labels.add(new Label());
		for (int job = 1; job <= data.n; job++) {
			if (!lp.getOutsourcingPool().isOutsourceable(job)) {
				if (node.getOutsourcingJobState(job) == Node.OUTSOURCE_REQUIRED) {
					return PricingResult.noImprovement("Required outsourcing job " + job + " is disabled");
				}
				continue;
			}
			byte state = node.getOutsourcingJobState(job);
			if (state == Node.OUTSOURCE_FORBIDDEN) {
				continue;
			}
			ArrayList<Label> next = new ArrayList<Label>(labels.size() * 2);
			if (state != Node.OUTSOURCE_REQUIRED) {
				next.addAll(labels);
			}
			for (Label label : labels) {
				Label included = label.include(job, data.outsourcingCost[job], lp.getJobDual(job));
				if (Utility.compareLe(included.baseline, baselineUpperBound)) {
					next.add(included);
				}
			}
			labels = prune(next);
		}

		ArrayList<Candidate> candidates = new ArrayList<Candidate>();
		for (Label label : labels) {
			if (label.jobs.isEmpty()) {
				continue;
			}
			if (Utility.compareLt(label.baseline, baselineLowerBound)
					|| Utility.compareGt(label.baseline, baselineUpperBound)) {
				continue;
			}
			double cost = data.evaluateOutsourcingCost(label.baseline);
			if (Utility.isBigMValue(cost)) {
				continue;
			}
			double reducedCost = cost - label.profit - lp.getOutsourcingColumnDual()
					- lp.getOutsourcingBaselineDual() * label.baseline;
			if (Utility.compareLt(reducedCost, -REDUCED_COST_TOLERANCE)) {
				candidates.add(new Candidate(label.jobs, label.baseline, cost, reducedCost));
			}
		}
		if (candidates.isEmpty()) {
			return PricingResult.noImprovement("No negative outsourcing column");
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
				"Generated " + columns.size() + " outsourcing columns; best rc=" + candidates.get(0).reducedCost);
	}

	@Override
	public String getName() {
		return "OutsourcingPricing";
	}

	private ArrayList<Label> prune(ArrayList<Label> labels) {
		ArrayList<Label> kept = new ArrayList<Label>();
		for (Label label : labels) {
			boolean dominated = false;
			for (int i = 0; i < kept.size(); i++) {
				Label existing = kept.get(i);
				if (existing.dominates(label)) {
					dominated = true;
					break;
				}
				if (label.dominates(existing)) {
					kept.remove(i);
					i--;
				}
			}
			if (!dominated) {
				kept.add(label);
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

		boolean dominates(Label other) {
			return Utility.compareLe(baseline, other.baseline) && Utility.compareGe(profit, other.profit);
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
