package TWETBPC.BP;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import Basic.Data;
import HEU.TanakaNoOutsourcingBPCTest;
import TWETBPC.BPCAlgorithmProfile;
import TWETBPC.BestBpcProfiles;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCContext;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETOutsourcingColumn;

/** directed CutSet / Cluster 分支公共语义的轻量回归测试。 */
public final class StructuredArcFlowBranchingTest {

	private StructuredArcFlowBranchingTest() {
	}

	public static void main(String[] args) throws Exception {
		verifyAggregateCoefficientAndDualExpansion();
		verifyZeroUpperBoundArcRestriction();
		verifyHalfIntegerOrdering();
		verifyCutSetIncrementalExpansionMatchesReference();
		verifyNodeCopyIsolation();
		verifyClusterDiagnostics();
		verifyClusterAssemblyAcrossFormalPricingModes();
		verifyDefaultConfigurationIsOff();
		System.out.println("StructuredArcFlowBranchingTest passed.");
	}

	private static void verifyCutSetIncrementalExpansionMatchesReference() throws Exception {
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(
				"data/40-2/wet040_001_2m.dat", false);
		TWETBPCConfig config = new TWETBPCConfig();
		StructuredArcFlowBrancher brancher = new StructuredArcFlowBrancher(data, config);
		int sink = data.n + 1;
		double[][] flow = new double[sink + 1][sink + 1];
		for (int job = 1; job <= data.n; job++) {
			flow[0][job] = 0.2 + (job % 7) * 0.013;
			for (int other = 1; other <= data.n; other++) {
				if (job != other) {
					flow[job][other] = ((job * 37 + other * 13) % 17 + 1) * 0.001;
				}
			}
		}

		List<StructuredArcFlowBrancher.AggregateSpec> specs =
				brancher.buildCutSetCandidateSpecs(flow, sink);
		Map<String, Double> reference = buildReferenceCutSetSpecs(data.n, flow, sink);
		require(specs.size() == reference.size(),
				"incremental CutSet search preserves the original candidate count");
		for (StructuredArcFlowBrancher.AggregateSpec spec : specs) {
			Double expected = reference.get(spec.jobs.toString());
			require(expected != null, "incremental CutSet search preserves every original job set");
			require(Math.abs(spec.value - expected.doubleValue()) < 1.0e-9,
					"incremental CutSet boundary equals direct incoming-flow evaluation");
		}
		require(specs.stream().anyMatch(spec -> spec.jobs.cardinality() == 2),
				"optimized search retains the original pair candidates");
		require(specs.stream().anyMatch(spec -> spec.jobs.cardinality() == data.n - 1),
				"optimized search retains the original largest candidate size");
	}

	private static Map<String, Double> buildReferenceCutSetSpecs(int jobCount, double[][] flow, int sink) {
		Map<String, Double> result = new HashMap<String, Double>();
		for (int seed = 1; seed <= jobCount; seed++) {
			BitSet jobs = new BitSet(jobCount + 1);
			jobs.set(seed);
			while (jobs.cardinality() < jobCount - 1) {
				int bestJob = -1;
				double bestAffinity = 1.0e-6;
				for (int candidate = 1; candidate <= jobCount; candidate++) {
					if (jobs.get(candidate)) {
						continue;
					}
					double affinity = 0.0;
					for (int member = jobs.nextSetBit(1); member >= 0;
							member = jobs.nextSetBit(member + 1)) {
						affinity += flow[member][candidate] + flow[candidate][member];
					}
					if (affinity > bestAffinity + 1.0e-6
							|| (Math.abs(affinity - bestAffinity) <= 1.0e-6 && candidate < bestJob)) {
						bestAffinity = affinity;
						bestJob = candidate;
					}
				}
				if (bestJob < 0) {
					break;
				}
				jobs.set(bestJob);
				double boundary = 0.0;
				for (int to = jobs.nextSetBit(1); to >= 0; to = jobs.nextSetBit(to + 1)) {
					for (int from = 0; from < sink; from++) {
						if (!jobs.get(from)) {
							boundary += flow[from][to];
						}
					}
				}
				if (Math.abs(boundary - Math.rint(boundary)) > 1.0e-6) {
					result.putIfAbsent(jobs.toString(), boundary);
				}
			}
		}
		return result;
	}

	private static void verifyClusterAssemblyAcrossFormalPricingModes() throws Exception {
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(
				"experiment-suite/formal/instances/data/n040-set02/family/base/zero/m4.dat", false);
		for (BPCAlgorithmProfile profile : Arrays.asList(BestBpcProfiles.NG_DSSR,
				BestBpcProfiles.TIME_INDEXED_GRAPH, BestBpcProfiles.TIME_INDEXED_GRAPH_RANK1)) {
			for (String outsourcingModel : Arrays.asList("masterVariables", "columns")) {
				TWETBPCConfig config = new TWETBPCConfig();
				profile.apply(config);
				config.outsourcingModel = outsourcingModel;
				config.enableClusterBranching = true;
				config.clusterTemporalWeight = 0.0;
				TWETBPCContext context = new TWETBPCContext(data, config);
				String description = profile.getName() + "/" + outsourcingModel;
				require(context.branchers.stream().anyMatch(brancher -> brancher instanceof StructuredArcFlowBrancher),
						description + " assembles StructuredArcFlowBrancher");
				require(context.runConfigurationLines().stream()
						.anyMatch(line -> line.startsWith("run.clusterDiagnostics.setupOnlySilhouette=")),
						description + " reports cluster diagnostics");
				require(context.pricingEngines.stream()
						.anyMatch(engine -> "OutsourcingPricing".equals(engine.getName())) == config.useColumnizedOutsourcing(),
						description + " assembles the matching outsourcing pricing family");
				require(context.branchers.stream()
						.anyMatch(brancher -> brancher instanceof OutsourcingMembershipBrancher) == config.useColumnizedOutsourcing(),
						description + " assembles the matching outsourcing brancher");
			}
		}
	}

	private static void verifyClusterDiagnostics() throws Exception {
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(
				"experiment-suite/formal/instances/data/n040-set02/family/base/zero/m4.dat", false);
		TWETBPCConfig config = new TWETBPCConfig();
		config.enableClusterBranching = true;
		config.clusterTemporalWeight = 0.0;
		config.clusterMstTheta = 0.5;
		StructuredArcFlowBrancher brancher = new StructuredArcFlowBrancher(data, config);
		StructuredArcFlowBrancher.ClusterPartitionDiagnostics diagnostics = brancher.getClusterDiagnostics();
		require(diagnostics.getClusterCount() == 3, "setup-only MST recovers three family clusters");
		require(diagnostics.getClusterSizes().equals(Arrays.asList(14, 13, 13)),
				"family cluster sizes are stable");
		require(diagnostics.getNontrivialClusterCount() == 3, "all family clusters are nontrivial");
		require(diagnostics.getSetupOnlySilhouette() > 0.7, "family setup silhouette is clear");
		require(diagnostics.getMaximumClusterShare() == 0.35, "maximum family share");
		require(diagnostics.getSingletonJobShare() == 0.0, "family partition has no singleton jobs");
		require(diagnostics.getInterIntraSetupRatio() > 4.0, "family setup separation is strong");
		require(diagnostics.configurationLines().stream()
				.anyMatch(line -> line.startsWith("run.clusterDiagnostics.setupOnlySilhouette=")),
				"diagnostics are available to run configuration output");
	}

	private static void verifyZeroUpperBoundArcRestriction() throws Exception {
		Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(
				"data/40-2/wet040_001_2m.dat", false);
		int width = data.n + 2;
		Node node = new Node(data, Collections.<Integer>emptyList(), Collections.<Integer>emptyList(), 0.0);
		BitSet mask = new BitSet(width * width);
		mask.set(2 * width + 3);
		mask.set(4 * width + 5);
		node.requireArc(4, 5);

		StructuredArcFlowBrancher.applyZeroUpperBoundArcRestrictions(node, mask, width);

		require(node.isArcForbidden(2, 3),
				"Q<=0 directly removes free member arcs from the pricing domain");
		require(node.getArcState(2, 3) == Node.ARC_FREE,
				"Q<=0 propagation does not create redundant explicit arc rows");
		require(node.getArcState(4, 5) == Node.ARC_REQUIRED,
				"Q<=0 propagation does not overwrite inherited required arcs");
		require(node.getArcState(3, 4) == Node.ARC_FREE,
				"Q<=0 propagation leaves non-member arcs unchanged");
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
		TWETOutsourcingColumn outsourcingColumn = new TWETOutsourcingColumn(0, Arrays.asList(2, 3), 5,
				0.0, 0.0, ColumnSource.MANUAL, false);
		require(constraint.outsourcingCoefficient(outsourcingColumn) == 0,
				"cluster/cutset rows exclude outsourcing columns");
		double[] outsourcingMembershipDual = new double[width];
		constraint.addDualTo(new double[width][width], outsourcingMembershipDual, 4.5);
		require(outsourcingMembershipDual[2] == 0.0 && outsourcingMembershipDual[3] == 0.0,
				"cluster/cutset duals do not enter outsourcing pricing");

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
