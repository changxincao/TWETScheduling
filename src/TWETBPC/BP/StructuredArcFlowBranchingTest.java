package TWETBPC.BP;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;

import Basic.Data;
import HEU.TanakaNoOutsourcingBPCTest;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;

/** directed CutSet / Cluster 分支公共语义的轻量回归测试。 */
public final class StructuredArcFlowBranchingTest {

	private StructuredArcFlowBranchingTest() {
	}

	public static void main(String[] args) throws Exception {
		verifyAggregateCoefficientAndDualExpansion();
		verifyHalfIntegerOrdering();
		verifyNodeCopyIsolation();
		verifyDefaultConfigurationIsOff();
		System.out.println("StructuredArcFlowBranchingTest passed.");
	}

	private static void verifyAggregateCoefficientAndDualExpansion() {
		int width = 7;
		BitSet incoming = new BitSet(width * width);
		for (int to : new int[] { 2, 3 }) {
			for (int from = 0; from < width - 1; from++) {
				if (from != 2 && from != 3) {
					incoming.set(from * width + to);
				}
			}
		}
		AggregateArcBranchConstraint constraint = new AggregateArcBranchConstraint(
				"cutSet", "S={2,3}", incoming, width, false, 1);
		TWETColumn column = new TWETColumn(0, Arrays.asList(1, 2, 3, 4, 2), 5,
				0.0, ColumnSource.MANUAL, false);
		require(constraint.coefficient(column, 6) == 2, "cutset entry count");
		double[][] dual = new double[width][width];
		constraint.addDualTo(dual, 3.25);
		require(dual[1][2] == 3.25 && dual[4][2] == 3.25, "member arc dual expansion");
		require(dual[2][3] == 0.0 && dual[3][2] == 0.0, "inside arcs excluded");

		BitSet directedPair = new BitSet(width * width);
		for (int from : new int[] { 2, 3 }) {
			for (int to : new int[] { 4, 5 }) {
				directedPair.set(from * width + to);
			}
		}
		AggregateArcBranchConstraint directedConstraint = new AggregateArcBranchConstraint(
				"clusterPair", "{2,3}->{4,5}", directedPair, width, false, 1);
		TWETColumn forwardColumn = new TWETColumn(1, Arrays.asList(2, 4, 3, 5), 5,
				0.0, ColumnSource.MANUAL, false);
		TWETColumn reverseColumn = new TWETColumn(2, Arrays.asList(4, 5, 2, 3), 5,
				0.0, ColumnSource.MANUAL, false);
		require(directedConstraint.coefficient(forwardColumn, 6) == 2,
				"directed pair counts forward cluster transitions");
		require(directedConstraint.coefficient(reverseColumn, 6) == 0,
				"directed pair excludes reverse cluster transitions");
		double[][] directedDual = new double[width][width];
		directedConstraint.addDualTo(directedDual, 2.5);
		require(directedDual[2][4] == 2.5 && directedDual[4][2] == 0.0,
				"directed pair expands dual in one direction only");
	}

	private static void verifyHalfIntegerOrdering() {
		StrongBranchingCandidate candidate = new StrongBranchingCandidate("aggregate", "value=1.5", 1.5) {
			@Override
			public BranchResult createBranchResult(LP lp) {
				return BranchResult.none("unused");
			}
		};
		require(candidate.getDistanceToHalf() == 0.0, "aggregate half-integer distance");
	}

	private static void verifyNodeCopyIsolation() throws Exception {
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(
				"data/40-2/wet040_001_2m.dat", false);
		int width = data.n + 2;
		BitSet mask = new BitSet(width * width);
		mask.set(width + 2);
		Node parent = new Node(data, Collections.<Integer>emptyList(), Collections.<Integer>emptyList(), 0.0);
		parent.addAggregateArcConstraint(new AggregateArcBranchConstraint(
				"cutSet", "first", mask, width, false, 1));
		Node child = parent.copy();
		child.addAggregateArcConstraint(new AggregateArcBranchConstraint(
				"cutSet", "second", mask, width, true, 2));
		require(parent.getAggregateArcConstraints().size() == 1, "parent aggregate isolation");
		require(child.getAggregateArcConstraints().size() == 2, "child aggregate inheritance");
		require(child.getRepairType() == Node.REPAIR_AGGREGATE_ARC_LOWER, "lower repair type");
		require(child.getRepairAggregateConstraintIndex() == 1, "repair row index");
	}

	private static void verifyDefaultConfigurationIsOff() {
		TWETBPCConfig config = new TWETBPCConfig();
		require(!config.enableCutSetBranching, "cutset default off");
		require(!config.enableClusterBranching, "cluster default off");
		require(!config.structuredArcStrictTypePriority, "strict type priority default off");
		require(config.clusterMinimumCandidateSize == 2, "singleton cluster candidates excluded");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
