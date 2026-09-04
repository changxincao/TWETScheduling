package TWETBPC.BP;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;

/**
 * 按任务的分数后继流构造 robust successor-set 分支，并在没有合格集合时回退后续 Arc 分支器。
 */
public final class SuccessorSetBrancher implements Brancher {

	private final Data data;
	private final TWETBPCConfig config;
	private final double tolerance;

	public SuccessorSetBrancher(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.tolerance = config.branchingTolerance;
	}

	@Override
	public BranchResult branch(LP lp) {
		List<StrongBranchingCandidate> candidates = collectStrongBranchingCandidates(lp, 1);
		return candidates.isEmpty() ? BranchResult.none("No fractional successor set found")
				: candidates.get(0).createBranchResult(lp);
	}

	@Override
	public List<StrongBranchingCandidate> collectStrongBranchingCandidates(LP lp, int limit) {
		if (limit <= 0 || lp.getLastSolution() == null) {
			return Collections.emptyList();
		}
		Node node = lp.getNode();
		int sink = node.sinkId();
		double[][] arcValues = accumulateArcValues(lp, sink);
		double[] outsourcingValues = lp.getLastSolution().getOutsourcingValues();
		ArrayList<SuccessorCandidate> candidates = new ArrayList<SuccessorCandidate>();
		for (int anchor = 1; anchor <= data.n; anchor++) {
			SuccessorCandidate candidate = buildCandidate(lp, anchor, sink, arcValues,
					anchor < outsourcingValues.length ? outsourcingValues[anchor] : 0.0);
			if (candidate != null) {
				candidates.add(candidate);
			}
		}
		Collections.sort(candidates, Comparator
				.comparingInt((SuccessorCandidate candidate) -> candidate.successorCount).reversed()
				.thenComparingDouble(candidate -> candidate.standardDeviation)
				.thenComparingDouble(StrongBranchingCandidate::getDistanceToHalf)
				.thenComparingInt(candidate -> candidate.anchor));
		if (candidates.size() <= limit) {
			return new ArrayList<StrongBranchingCandidate>(candidates);
		}
		return new ArrayList<StrongBranchingCandidate>(candidates.subList(0, limit));
	}

	private SuccessorCandidate buildCandidate(LP lp, int anchor, int sink, double[][] arcValues,
			double outsourcingValue) {
		double totalCoverage = outsourcingValue;
		for (int successor = 1; successor <= sink; successor++) {
			totalCoverage += arcValues[anchor][successor];
		}
		if (Utility.compareLe(totalCoverage, tolerance)
				|| Utility.compareGt(totalCoverage, 1.0 + config.successorSetCoverageUpperTolerance)) {
			return null;
		}

		ArrayList<SuccessorFlow> effective = new ArrayList<SuccessorFlow>();
		for (int successor = 1; successor <= sink; successor++) {
			if (successor == anchor) {
				continue;
			}
			addEffectiveSuccessor(effective, successor, arcValues[anchor][successor], totalCoverage);
		}
		int outsourcingKey = sink + 1;
		addEffectiveSuccessor(effective, outsourcingKey, outsourcingValue, totalCoverage);
		if (effective.size() < 3) {
			return null;
		}

		double effectiveMass = 0.0;
		for (SuccessorFlow successor : effective) {
			effectiveMass += successor.flow;
		}
		if (Utility.compareLe(effectiveMass, tolerance)) {
			return null;
		}
		for (SuccessorFlow successor : effective) {
			successor.normalizedEffectiveFlow = successor.flow / effectiveMass;
		}
		Collections.sort(effective, Comparator
				.comparingDouble((SuccessorFlow successor) -> successor.normalizedEffectiveFlow)
				.thenComparingInt(successor -> successor.key));

		int crossingIndex = -1;
		double prefix = 0.0;
		double beforeCrossing = 0.0;
		for (int index = 0; index < effective.size(); index++) {
			beforeCrossing = prefix;
			prefix += effective.get(index).normalizedEffectiveFlow;
			if (prefix >= 0.5) {
				crossingIndex = index;
				break;
			}
		}
		if (crossingIndex < 0) {
			return null;
		}
		int prefixSize = Math.abs(beforeCrossing - 0.5) <= Math.abs(prefix - 0.5)
				? crossingIndex : crossingIndex + 1;
		if (prefixSize <= 0 || prefixSize >= effective.size()) {
			return null;
		}

		ArrayList<SuccessorFlow> selected = new ArrayList<SuccessorFlow>(effective.subList(0, prefixSize));
		if (selected.size() == 1) {
			selected = new ArrayList<SuccessorFlow>(effective.subList(prefixSize, effective.size()));
		}
		if (selected.size() < 2 || selected.size() >= effective.size()) {
			return null;
		}

		double value = 0.0;
		BitSet arcMask = new BitSet((sink + 1) * (sink + 1));
		boolean includesOutsourcing = false;
		for (SuccessorFlow successor : selected) {
			value += successor.flow;
			if (successor.key == outsourcingKey) {
				includesOutsourcing = true;
			} else {
				arcMask.set(anchor * (sink + 1) + successor.key);
			}
		}
		if (Utility.compareLe(value, tolerance) || Utility.compareGe(value, 1.0 - tolerance)) {
			return null;
		}

		double mean = 1.0 / effective.size();
		double variance = 0.0;
		for (SuccessorFlow successor : effective) {
			double difference = successor.normalizedEffectiveFlow - mean;
			variance += difference * difference;
		}
		double standardDeviation = Math.sqrt(variance / effective.size());
		String description = "successorSet(i=" + anchor + ",A="
				+ successorSetText(selected, sink) + ",S=" + effective.size()
				+ ",H=" + format(totalCoverage) + ",sd=" + format(standardDeviation) + ")";
		return new SuccessorCandidate(anchor, effective.size(), standardDeviation, value, description,
				arcMask, includesOutsourcing ? anchor : -1, sink + 1);
	}

	private void addEffectiveSuccessor(ArrayList<SuccessorFlow> effective, int key, double flow,
			double totalCoverage) {
		if (Utility.compareGt(flow / totalCoverage, tolerance)) {
			effective.add(new SuccessorFlow(key, flow));
		}
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
			for (int index = 1; index < sequence.size(); index++) {
				values[sequence.get(index - 1).intValue()][sequence.get(index).intValue()] += lambda;
			}
			values[sequence.get(sequence.size() - 1).intValue()][sink] += lambda;
		}
		return values;
	}

	private String successorSetText(List<SuccessorFlow> successors, int sink) {
		StringBuilder builder = new StringBuilder("{");
		for (SuccessorFlow successor : successors) {
			if (builder.length() > 1) {
				builder.append(',');
			}
			builder.append(successor.key == sink ? "sink" : successor.key == sink + 1 ? "OUT" : successor.key);
		}
		return builder.append('}').toString();
	}

	private static String format(double value) {
		return String.format(Locale.US, "%.6f", value);
	}

	@Override
	public String getName() {
		return "SuccessorSetBrancher";
	}

	private final class SuccessorCandidate extends StrongBranchingCandidate {
		final int anchor;
		final int successorCount;
		final double standardDeviation;
		final String description;
		final BitSet arcMask;
		final int outsourcingJob;
		final int arcWidth;

		SuccessorCandidate(int anchor, int successorCount, double standardDeviation, double value,
				String description, BitSet arcMask, int outsourcingJob, int arcWidth) {
			super("successorSet", description, value, anchor);
			this.anchor = anchor;
			this.successorCount = successorCount;
			this.standardDeviation = standardDeviation;
			this.description = description;
			this.arcMask = (BitSet) arcMask.clone();
			this.outsourcingJob = outsourcingJob;
			this.arcWidth = arcWidth;
		}

		@Override
		public BranchResult createBranchResult(LP lp) {
			Node base = lp.getNode();
			Node left = base.copy();
			Node right = base.copy();
			left.depth = right.depth = base.depth + 1;
			left.pseudoCost = right.pseudoCost = lp.getLastSolution().getObjectiveValue();
			// Q(A)<=0 与逐项禁止 A 中的非负 arc/OUT 完全等价；直接收紧 pricing 域，避免左支仍搜索这些后继。
			for (int bit = arcMask.nextSetBit(0); bit >= 0; bit = arcMask.nextSetBit(bit + 1)) {
				left.forbidArc(bit / arcWidth, bit % arcWidth);
			}
			if (outsourcingJob > 0) {
				left.forbidOutsourcingJob(outsourcingJob);
			}
			left.markSuccessorSetDirectDomainRestriction();
			right.addAggregateArcConstraint(new AggregateArcBranchConstraint("successorSet", description,
					arcMask, arcWidth, outsourcingJob, true, 1));
			return new BranchResult(true, left, right,
					"Branched on " + description + " value=" + getValue() + " <= 0 or >= 1");
		}
	}

	private static final class SuccessorFlow {
		final int key;
		final double flow;
		double normalizedEffectiveFlow;

		SuccessorFlow(int key, double flow) {
			this.key = key;
			this.flow = flow;
		}
	}
}
