package TWETBPC.BP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;

/**
 * 比较普通 arc、动态 CutSet 和静态 Cluster 分支。默认共享候选池；实验性严格优先模式
 * 按 Cluster、CutSet、Arc 逐层回退。三类约束最终都只是有向 arc 流量之和，
 * 因此定价仍使用既有 arc dual。
 */
public final class StructuredArcFlowBrancher extends ArcBrancher {

	private final Data data;
	private final TWETBPCConfig config;
	private final List<BitSet> clusters;

	public StructuredArcFlowBrancher(Data data, TWETBPCConfig config) {
		super(config.branchingTolerance);
		this.data = data;
		this.config = config;
		this.clusters = config.enableClusterBranching ? buildStaticClusters() : Collections.<BitSet>emptyList();
	}

	@Override
	public List<StrongBranchingCandidate> collectStrongBranchingCandidates(LP lp, int limit) {
		if (limit <= 0 || lp.getLastSolution() == null) {
			return Collections.emptyList();
		}
		Node node = lp.getNode();
		int sink = node.sinkId();
		double[][] arcValues = accumulateArcValues(lp, sink);
		ArrayList<StrongBranchingCandidate> candidates = new ArrayList<StrongBranchingCandidate>();
		Set<String> aggregateKeys = new HashSet<String>();
		if (config.enableClusterBranching) {
			collectClusterCandidates(candidates, aggregateKeys, lp, arcValues, sink);
			if (config.structuredArcStrictTypePriority && !candidates.isEmpty()) {
				return sortedAndLimited(candidates, limit);
			}
		}
		if (config.enableCutSetBranching) {
			collectCutSetCandidates(candidates, aggregateKeys, lp, arcValues, sink);
			if (config.structuredArcStrictTypePriority && !candidates.isEmpty()) {
				return sortedAndLimited(candidates, limit);
			}
		}
		ArrayList<StrongBranchingCandidate> arcCandidates = collectInternalArcCandidates(lp, node, sink, arcValues);
		if (arcCandidates.isEmpty()) {
			arcCandidates = collectEndpointArcCandidates(lp, node, sink, arcValues);
		}
		candidates.addAll(arcCandidates);
		return sortedAndLimited(candidates, limit);
	}

	private List<StrongBranchingCandidate> sortedAndLimited(
			ArrayList<StrongBranchingCandidate> candidates, int limit) {
		sortCandidates(candidates);
		return candidates.size() <= limit ? candidates
				: new ArrayList<StrongBranchingCandidate>(candidates.subList(0, limit));
	}

	private void collectClusterCandidates(ArrayList<StrongBranchingCandidate> candidates, Set<String> keys,
			LP lp, double[][] arcValues, int sink) {
		// singleton boundary恒为1，其pair也更接近普通arc聚合；只保留真正的簇级候选。
		ArrayList<Integer> eligibleClusters = new ArrayList<Integer>();
		for (int cluster = 0; cluster < clusters.size(); cluster++) {
			BitSet jobs = clusters.get(cluster);
			if (jobs.cardinality() < config.clusterMinimumCandidateSize) {
				continue;
			}
			eligibleClusters.add(Integer.valueOf(cluster));
			addAggregateCandidate(candidates, keys, "clusterBoundary",
					"clusterBoundary(" + jobSetText(jobs) + ")", incomingMask(jobs, sink),
					flowValue(incomingMask(jobs, sink), arcValues, sink), cluster);
			BitSet depotMask = depotPairMask(jobs, sink);
			addAggregateCandidate(candidates, keys, "clusterDepotPair",
					"clusterDepotPair(" + cluster + ")", depotMask,
					flowValue(depotMask, arcValues, sink), cluster);
		}
		int order = clusters.size() * 2;
		for (int firstIndex = 0; firstIndex < eligibleClusters.size(); firstIndex++) {
			int firstCluster = eligibleClusters.get(firstIndex).intValue();
			for (int secondIndex = firstIndex + 1; secondIndex < eligibleClusters.size(); secondIndex++) {
				int secondCluster = eligibleClusters.get(secondIndex).intValue();
				BitSet mask = betweenUndirectedMask(
						clusters.get(firstCluster), clusters.get(secondCluster), sink);
				addAggregateCandidate(candidates, keys, "clusterPair",
						"clusterPair(" + firstCluster + "<->" + secondCluster + ")", mask,
						flowValue(mask, arcValues, sink), order++);
			}
		}
	}

	private void collectCutSetCandidates(ArrayList<StrongBranchingCandidate> candidates, Set<String> keys,
			LP lp, double[][] arcValues, int sink) {
		ArrayList<AggregateSpec> specs = new ArrayList<AggregateSpec>();
		Set<String> localKeys = new HashSet<String>();
		for (int seed = 1; seed <= data.n; seed++) {
			BitSet jobs = new BitSet(data.n + 1);
			jobs.set(seed);
			while (jobs.cardinality() < data.n - 1) {
				int next = strongestConnectedJob(jobs, arcValues);
				if (next < 0) {
					break;
				}
				jobs.set(next);
				BitSet mask = incomingMask(jobs, sink);
				double value = flowValue(mask, arcValues, sink);
				if (!isFractional(value)) {
					continue;
				}
				String key = mask.toString();
				if (localKeys.add(key)) {
					specs.add(new AggregateSpec((BitSet) jobs.clone(), mask, value));
				}
			}
		}
		Collections.sort(specs, Comparator
				.comparingDouble((AggregateSpec spec) -> distanceToHalf(spec.value))
				.thenComparingInt(spec -> spec.jobs.cardinality())
				.thenComparing(spec -> spec.jobs.toString()));
		int count = Math.min(config.cutSetCandidatePoolLimit, specs.size());
		for (int index = 0; index < count; index++) {
			AggregateSpec spec = specs.get(index);
			addAggregateCandidate(candidates, keys, "cutSet", "cutSet(" + jobSetText(spec.jobs) + ")",
					spec.mask, spec.value, index);
		}
	}

	private int strongestConnectedJob(BitSet jobs, double[][] arcValues) {
		int bestJob = -1;
		double bestAffinity = tolerance;
		for (int candidate = 1; candidate <= data.n; candidate++) {
			if (jobs.get(candidate)) {
				continue;
			}
			double affinity = 0.0;
			for (int member = jobs.nextSetBit(1); member >= 0; member = jobs.nextSetBit(member + 1)) {
				affinity += arcValues[member][candidate] + arcValues[candidate][member];
			}
			if (Utility.compareGt(affinity, bestAffinity)
					|| (Math.abs(affinity - bestAffinity) <= tolerance && candidate < bestJob)) {
				bestAffinity = affinity;
				bestJob = candidate;
			}
		}
		return bestJob;
	}

	private void addAggregateCandidate(ArrayList<StrongBranchingCandidate> candidates, Set<String> keys,
			String family, String description, BitSet mask, double value, int order) {
		if (!isFractional(value) || mask.isEmpty() || !keys.add(mask.toString())) {
			return;
		}
		final BitSet immutableMask = (BitSet) mask.clone();
		final int upper = (int) Math.floor(value + tolerance);
		final int lower = (int) Math.ceil(value - tolerance);
		final int candidateOrder = familyOrder(family) * 1_000_000 + order;
		candidates.add(new StrongBranchingCandidate("aggregate", description, value, candidateOrder) {
			@Override
			public BranchResult createBranchResult(LP currentLp) {
				Node base = currentLp.getNode();
				Node left = base.copy();
				Node right = base.copy();
				left.depth = right.depth = base.depth + 1;
				left.pseudoCost = right.pseudoCost = currentLp.getLastSolution().getObjectiveValue();
				left.addAggregateArcConstraint(new AggregateArcBranchConstraint(family, description,
						immutableMask, data.n + 2, false, upper));
				right.addAggregateArcConstraint(new AggregateArcBranchConstraint(family, description,
						immutableMask, data.n + 2, true, lower));
				return new BranchResult(true, left, right,
						"Branched on " + description + " value=" + value + " <= " + upper + " or >= " + lower);
			}
		});
	}

	private boolean isFractional(double value) {
		return Utility.compareGt(Math.abs(value - Math.rint(value)), tolerance);
	}

	private double distanceToHalf(double value) {
		return Math.abs(value - Math.floor(value) - 0.5);
	}

	private int familyOrder(String family) {
		if ("clusterBoundary".equals(family)) {
			return 0;
		}
		if ("clusterDepotPair".equals(family)) {
			return 1;
		}
		if ("clusterPair".equals(family)) {
			return 2;
		}
		return 3;
	}

	private BitSet incomingMask(BitSet jobs, int sink) {
		BitSet mask = new BitSet((sink + 1) * (sink + 1));
		for (int to = jobs.nextSetBit(1); to >= 0; to = jobs.nextSetBit(to + 1)) {
			for (int from = 0; from < sink; from++) {
				if (!jobs.get(from)) {
					mask.set(from * (sink + 1) + to);
				}
			}
		}
		return mask;
	}

	/** 聚合两个簇之间的双向连接，保持论文cluster-pair的高层连接语义。 */
	private BitSet betweenUndirectedMask(BitSet firstJobs, BitSet secondJobs, int sink) {
		BitSet mask = new BitSet((sink + 1) * (sink + 1));
		for (int first = firstJobs.nextSetBit(1); first >= 0; first = firstJobs.nextSetBit(first + 1)) {
			for (int second = secondJobs.nextSetBit(1); second >= 0;
					second = secondJobs.nextSetBit(second + 1)) {
				mask.set(first * (sink + 1) + second);
				mask.set(second * (sink + 1) + first);
			}
		}
		return mask;
	}

	/** 将TWET分离的source/sink合并解释为论文中的depot cluster。 */
	private BitSet depotPairMask(BitSet jobs, int sink) {
		BitSet mask = new BitSet((sink + 1) * (sink + 1));
		for (int job = jobs.nextSetBit(1); job >= 0; job = jobs.nextSetBit(job + 1)) {
			mask.set(job);
			mask.set(job * (sink + 1) + sink);
		}
		return mask;
	}

	private double flowValue(BitSet mask, double[][] arcValues, int sink) {
		double value = 0.0;
		for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
			value += arcValues[bit / (sink + 1)][bit % (sink + 1)];
		}
		return value;
	}

	private List<BitSet> buildStaticClusters() {
		if (data.n < 3) {
			return Collections.emptyList();
		}
		double[][] distances = normalizedJobDistances();
		int[] parent = minimumSpanningTreeParents(distances);
		double[] edgeWeights = new double[data.n - 1];
		for (int job = 2; job <= data.n; job++) {
			edgeWeights[job - 2] = distances[job][parent[job]];
		}
		double mean = Arrays.stream(edgeWeights).average().orElse(0.0);
		double variance = 0.0;
		for (double weight : edgeWeights) {
			variance += (weight - mean) * (weight - mean);
		}
		double threshold = mean + config.clusterMstTheta * Math.sqrt(variance / edgeWeights.length);
		int[] components = new int[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			components[job] = job;
		}
		for (int job = 2; job <= data.n; job++) {
			if (Utility.compareLe(distances[job][parent[job]], threshold)) {
				union(components, job, parent[job]);
			}
		}
		ArrayList<BitSet> result = new ArrayList<BitSet>();
		for (int job = 1; job <= data.n; job++) {
			int root = find(components, job);
			while (result.size() <= root) {
				result.add(null);
			}
			if (result.get(root) == null) {
				result.set(root, new BitSet(data.n + 1));
			}
			result.get(root).set(job);
		}
		result.removeIf(cluster -> cluster == null || cluster.isEmpty() || cluster.cardinality() == data.n);
		return result;
	}

	private double[][] normalizedJobDistances() {
		double maxSetup = 0.0;
		double maxDue = 0.0;
		for (int first = 1; first <= data.n; first++) {
			for (int second = first + 1; second <= data.n; second++) {
				maxSetup = Math.max(maxSetup,
						0.5 * (data.s[first][second] + data.s[second][first]));
				maxDue = Math.max(maxDue, Math.abs(dueCenter(first) - dueCenter(second)));
			}
		}
		double[][] distances = new double[data.n + 1][data.n + 1];
		for (int first = 1; first <= data.n; first++) {
			for (int second = first + 1; second <= data.n; second++) {
				double setup = 0.5 * (data.s[first][second] + data.s[second][first]);
				double value = (maxSetup > 0.0 ? setup / maxSetup : 0.0)
						+ config.clusterTemporalWeight * (maxDue > 0.0
								? Math.abs(dueCenter(first) - dueCenter(second)) / maxDue : 0.0);
				distances[first][second] = distances[second][first] = value;
			}
		}
		return distances;
	}

	private double dueCenter(int job) {
		return 0.5 * (data.d_e[job] + data.d_l[job]);
	}

	private int[] minimumSpanningTreeParents(double[][] distances) {
		int[] parent = new int[data.n + 1];
		double[] best = new double[data.n + 1];
		boolean[] selected = new boolean[data.n + 1];
		Arrays.fill(best, Double.POSITIVE_INFINITY);
		best[1] = 0.0;
		for (int count = 1; count <= data.n; count++) {
			int next = -1;
			for (int job = 1; job <= data.n; job++) {
				if (!selected[job] && (next < 0 || best[job] < best[next])) {
					next = job;
				}
			}
			selected[next] = true;
			for (int job = 1; job <= data.n; job++) {
				if (!selected[job] && distances[next][job] < best[job]) {
					best[job] = distances[next][job];
					parent[job] = next;
				}
			}
		}
		return parent;
	}

	private int find(int[] parents, int value) {
		while (parents[value] != value) {
			parents[value] = parents[parents[value]];
			value = parents[value];
		}
		return value;
	}

	private void union(int[] parents, int first, int second) {
		int firstRoot = find(parents, first);
		int secondRoot = find(parents, second);
		if (firstRoot != secondRoot) {
			parents[Math.max(firstRoot, secondRoot)] = Math.min(firstRoot, secondRoot);
		}
	}

	private String jobSetText(BitSet jobs) {
		StringBuilder builder = new StringBuilder();
		for (int job = jobs.nextSetBit(1); job >= 0; job = jobs.nextSetBit(job + 1)) {
			if (builder.length() > 0) {
				builder.append('-');
			}
			builder.append(job);
		}
		return builder.toString();
	}

	@Override
	public String getName() {
		return "StructuredArcFlowBrancher";
	}

	private static final class AggregateSpec {
		final BitSet jobs;
		final BitSet mask;
		final double value;

		AggregateSpec(BitSet jobs, BitSet mask, double value) {
			this.jobs = jobs;
			this.mask = mask;
			this.value = value;
		}
	}
}
