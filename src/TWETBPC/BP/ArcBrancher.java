package TWETBPC.BP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import Common.Utility;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;

/**
 * arc 分支器。普通分支和 strong branching 候选共用同一套 child 构造逻辑。
 */
public class ArcBrancher implements Brancher {

	private final double tolerance;

	public ArcBrancher(double tolerance) {
		this.tolerance = tolerance;
	}

	@Override
	public BranchResult branch(LP lp) {
		List<StrongBranchingCandidate> candidates = collectStrongBranchingCandidates(lp, 1);
		if (candidates.isEmpty()) {
			return BranchResult.none("No fractional arc found");
		}
		return candidates.get(0).createBranchResult(lp);
	}

	@Override
	public List<StrongBranchingCandidate> collectStrongBranchingCandidates(LP lp, int limit) {
		if (limit <= 0 || lp.getLastSolution() == null) {
			return Collections.emptyList();
		}
		Node node = lp.getNode();
		int sink = node.sinkId();
		double[][] arcValues = accumulateArcValues(lp, sink);
		ArrayList<StrongBranchingCandidate> candidates = collectInternalArcCandidates(lp, node, sink, arcValues);
		if (candidates.isEmpty()) {
			candidates = collectEndpointArcCandidates(lp, node, sink, arcValues);
		}
		sortCandidates(candidates);
		if (candidates.size() <= limit) {
			return candidates;
		}
		return new ArrayList<StrongBranchingCandidate>(candidates.subList(0, limit));
	}

	private ArrayList<StrongBranchingCandidate> collectInternalArcCandidates(LP lp, Node node, int sink,
			double[][] arcValues) {
		ArrayList<StrongBranchingCandidate> candidates = new ArrayList<StrongBranchingCandidate>();
		for (int from = 1; from < sink; from++) {
			for (int to = 1; to < sink; to++) {
				addArcCandidate(candidates, lp, node, from, to, arcValues[from][to]);
			}
		}
		return candidates;
	}

	private ArrayList<StrongBranchingCandidate> collectEndpointArcCandidates(LP lp, Node node, int sink,
			double[][] arcValues) {
		ArrayList<StrongBranchingCandidate> candidates = new ArrayList<StrongBranchingCandidate>();
		for (int job = 1; job < sink; job++) {
			// endpoint 分支只保留 source->job；job->sink 没有实际顺序含义，仍按当前正式分支口径跳过。
			addArcCandidate(candidates, lp, node, 0, job, arcValues[0][job]);
		}
		return candidates;
	}

	private void addArcCandidate(ArrayList<StrongBranchingCandidate> candidates, LP lp, Node node,
			final int from, final int to, final double value) {
		if (from == to || node.getArcState(from, to) != Node.ARC_FREE
				|| node.isArcForbidden(from, to) || node.isPricingOnlyArcForbidden(from, to)) {
			return;
		}
		double frac = Math.abs(value - Math.rint(value));
		if (Utility.compareLe(frac, tolerance)) {
			return;
		}
		int order = from * (node.sinkId() + 1) + to;
		candidates.add(new StrongBranchingCandidate("arc", "arc(" + from + "," + to + ")", value, order) {
			@Override
			public BranchResult createBranchResult(LP lp) {
				return ArcBrancher.this.createBranchResult(lp, from, to, value);
			}
		});
	}

	private double[][] accumulateArcValues(LP lp, int sink) {
		double[][] values = new double[sink + 1][sink + 1];
		TWETMasterSolution solution = lp.getLastSolution();
		for (Map.Entry<Integer, Double> entry : solution.getColumnValues().entrySet()) {
			double lambda = entry.getValue().doubleValue();
			TWETColumn column = lp.getPool().getColumn(entry.getKey().intValue());
			List<Integer> sequence = column.getSequence();
			if (sequence.isEmpty()) {
				continue;
			}
			values[0][sequence.get(0).intValue()] += lambda;
			for (int i = 1; i < sequence.size(); i++) {
				values[sequence.get(i - 1).intValue()][sequence.get(i).intValue()] += lambda;
			}
			values[sequence.get(sequence.size() - 1).intValue()][sink] += lambda;
		}
		return values;
	}

	private BranchResult createBranchResult(LP lp, int bestFrom, int bestTo, double value) {
		Node base = lp.getNode();
		int sink = base.sinkId();
		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = lp.getLastSolution().getObjectiveValue();
		left.forbidArc(bestFrom, bestTo);
		left.markArcRepair(bestFrom, bestTo, false);

		// 要求 i->j 时，同一机器路径里 i 不能再接其他后继，j 不能再有其他前驱。
		if (bestFrom != 0 && bestFrom != sink) {
			for (int to = 1; to <= sink; to++) {
				if (to != bestTo) {
					right.forbidArc(bestFrom, to);
				}
			}
		}
		if (bestTo != sink) {
			for (int from = 0; from <= sink; from++) {
				if (from != bestFrom) {
					right.forbidArc(from, bestTo);
				}
			}
		}
		right.requireArc(bestFrom, bestTo);
		right.markArcRepair(bestFrom, bestTo, true);
		return new BranchResult(true, left, right,
				"Branched on arc (" + bestFrom + "," + bestTo + ") value=" + value);
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

	@Override
	public String getName() {
		return "ArcBrancher";
	}
}
