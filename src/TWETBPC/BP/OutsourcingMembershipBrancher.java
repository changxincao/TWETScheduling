package TWETBPC.BP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
		List<StrongBranchingCandidate> candidates = collectStrongBranchingCandidates(lp, 1);
		if (candidates.isEmpty()) {
			return BranchResult.none("No fractional outsourcing membership found");
		}
		return candidates.get(0).createBranchResult(lp);
	}

	@Override
	public List<StrongBranchingCandidate> collectStrongBranchingCandidates(LP lp, int limit) {
		if (limit <= 0 || !lp.isColumnizedOutsourcing()) {
			return Collections.emptyList();
		}
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return Collections.emptyList();
		}
		double[] values = solution.getOutsourcingValues();
		ArrayList<StrongBranchingCandidate> candidates = new ArrayList<StrongBranchingCandidate>();
		for (int job = 1; job <= lp.getData().n; job++) {
			if (!lp.getOutsourcingPool().isOutsourceable(job)) {
				continue;
			}
			double value = job < values.length ? values[job] : 0.0;
			double frac = Math.abs(value - Math.rint(value));
			if (Utility.compareLe(frac, tolerance)) {
				continue;
			}
			final int candidateJob = job;
			final double candidateValue = value;
			candidates.add(new StrongBranchingCandidate("outsourcing", "outsourcingJob(" + job + ")", value, job) {
				@Override
				public BranchResult createBranchResult(LP lp) {
					return OutsourcingMembershipBrancher.this.createBranchResult(lp, candidateJob, candidateValue);
				}
			});
		}
		sortCandidates(candidates);
		if (candidates.size() <= limit) {
			return candidates;
		}
		return new ArrayList<StrongBranchingCandidate>(candidates.subList(0, limit));
	}

	private BranchResult createBranchResult(LP lp, int bestJob, double value) {
		TWETMasterSolution solution = lp.getLastSolution();
		Node base = lp.getNode();
		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();
		left.forbidOutsourcingJob(bestJob);
		right.requireOutsourcingJob(bestJob);
		seedRequiredOutsourcingColumn(lp, right);
		return new BranchResult(true, left, right, "Branched on outsourcing job " + bestJob + " value=" + value);
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

	private void sortCandidates(ArrayList<StrongBranchingCandidate> candidates) {
		Collections.sort(candidates, new Comparator<StrongBranchingCandidate>() {
			@Override
			public int compare(StrongBranchingCandidate a, StrongBranchingCandidate b) {
				if (Utility.compareLt(a.getDistanceToHalf(), b.getDistanceToHalf())) {
					return -1;
				}
				if (Utility.compareGt(a.getDistanceToHalf(), b.getDistanceToHalf())) {
					return 1;
				}
				int orderCompare = Integer.compare(a.getOrder(), b.getOrder());
				return orderCompare != 0 ? orderCompare : a.getDescription().compareTo(b.getDescription());
			}
		});
	}
}
