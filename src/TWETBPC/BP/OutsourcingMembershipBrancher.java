package TWETBPC.BP;

import java.util.ArrayList;

import Common.Utility;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETMasterSolution;

/**
 * SP1 外包列模式下的外包 membership 分支。
 * <p>
 * 分支变量是“job j 是否在唯一外包集合列中”。左支禁止 j 外包，右支要求 j 外包。
 */
public class OutsourcingMembershipBrancher implements Brancher {

	private final double tolerance;

	public OutsourcingMembershipBrancher(double tolerance) {
		this.tolerance = tolerance;
	}

	@Override
	public BranchResult branch(LP lp) {
		if (!lp.isColumnizedOutsourcing()) {
			return BranchResult.none("Outsourcing columns disabled");
		}
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return BranchResult.none("No master solution");
		}
		Node base = lp.getNode();
		double[] values = solution.getOutsourcingValues();
		int bestJob = -1;
		double bestScore = Double.POSITIVE_INFINITY;
		for (int job = 1; job <= lp.getData().n; job++) {
			if (!lp.getOutsourcingPool().isOutsourceable(job)) {
				continue;
			}
			if (base.getOutsourcingJobState(job) != Node.OUTSOURCE_FREE) {
				continue;
			}
			double value = job < values.length ? values[job] : 0.0;
			double frac = Math.abs(value - Math.rint(value));
			if (Utility.compareLe(frac, tolerance)) {
				continue;
			}
			double score = Math.abs(value - 0.5);
			if (Utility.compareLt(score, bestScore)) {
				bestScore = score;
				bestJob = job;
			}
		}
		if (bestJob < 0) {
			return BranchResult.none("No fractional outsourcing membership found");
		}

		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();
		left.forbidOutsourcingJob(bestJob);
		right.requireOutsourcingJob(bestJob);
		seedRequiredOutsourcingColumn(lp, right);
		return new BranchResult(true, left, right, "Branched on outsourcing job " + bestJob);
	}

	private void seedRequiredOutsourcingColumn(LP lp, Node node) {
		ArrayList<Integer> jobs = new ArrayList<Integer>(node.getRequiredOutsourcingJobs());
		if (jobs.isEmpty()) {
			return;
		}
		int id = lp.getOutsourcingPool().addColumn(jobs, ColumnSource.MANUAL, true);
		if (id >= 0 && !node.seedOutsourcingColumnIds.contains(Integer.valueOf(id))) {
			node.seedOutsourcingColumnIds.add(Integer.valueOf(id));
		}
	}

	@Override
	public String getName() {
		return "OutsourcingMembershipBrancher";
	}
}
