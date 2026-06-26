package TWETBPC.BP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import Common.Utility;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETMasterSolution;

/**
 * SP2 外包 tariff segment 选择变量的分支器。
 * <p>
 * 2026-05-17: RMP 中 `z_s` 当前是 LP relaxation。即使内部 route/arc 已经整数，
 * 多个 tariff segment 仍可能分数混合。该分支器按用户给出的 SP2 分支规则：
 * 选择最接近 0.5 的分数 segment，生成 `z_s <= 0` 和 `z_s >= 1` 两个子节点。
 * 这些约束只进入 master，不改变 private-route pricing 结构，只通过新的 dual 间接影响定价。
 */
public class TariffSegmentBrancher implements Brancher {

	private final double tolerance;

	public TariffSegmentBrancher(double tolerance) {
		this.tolerance = tolerance;
	}

	@Override
	public BranchResult branch(LP lp) {
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null) {
			return BranchResult.none("No master solution");
		}
		List<StrongBranchingCandidate> candidates = collectStrongBranchingCandidates(lp, 1);
		if (candidates.isEmpty()) {
			return BranchResult.none("Tariff segment variables already integral");
		}
		return candidates.get(0).createBranchResult(lp);
	}

	@Override
	public List<StrongBranchingCandidate> collectStrongBranchingCandidates(LP lp, int limit) {
		TWETMasterSolution solution = lp.getLastSolution();
		if (limit <= 0 || solution == null) {
			return Collections.emptyList();
		}
		ArrayList<StrongBranchingCandidate> candidates = new ArrayList<StrongBranchingCandidate>();
		double[] values = solution.getOutsourceSegmentValues();
		for (int segment = 0; segment < values.length; segment++) {
			double value = values[segment];
			if (Utility.compareLe(Math.abs(value - Math.rint(value)), tolerance)) {
				continue;
			}
			final int candidateSegment = segment;
			final double candidateValue = value;
			candidates.add(new StrongBranchingCandidate("tariff", "tariffSegment(" + segment + ")", value) {
				@Override
				public BranchResult createBranchResult(LP lp) {
					return TariffSegmentBrancher.this.createBranchResult(lp, candidateSegment, candidateValue);
				}
			});
		}
		sortCandidates(candidates);
		if (candidates.size() <= limit) {
			return candidates;
		}
		return new ArrayList<StrongBranchingCandidate>(candidates.subList(0, limit));
	}

	private BranchResult createBranchResult(LP lp, int bestSegment, double value) {
		TWETMasterSolution solution = lp.getLastSolution();
		Node base = lp.getNode();
		Node left = base.copy();
		Node right = base.copy();
		left.depth = right.depth = base.depth + 1;
		left.pseudoCost = right.pseudoCost = solution.getObjectiveValue();
		left.forbidTariffSegment(bestSegment);
		left.markTariffRepair(bestSegment, false);
		right.requireTariffSegment(bestSegment);
		right.markTariffRepair(bestSegment, true);
		return new BranchResult(true, left, right,
				"Branched on outsourcing tariff segment z_" + bestSegment + "=" + value);
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
				return a.getDescription().compareTo(b.getDescription());
			}
		});
	}

	@Override
	public String getName() {
		return "TariffSegmentBrancher";
	}

}
