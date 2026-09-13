package TWETBPC.BP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
	private final ClusterPartitionDiagnostics clusterDiagnostics;

	public StructuredArcFlowBrancher(Data data, TWETBPCConfig config) {
		super(config.branchingTolerance);
		this.data = data;
		this.config = config;
		if (config.enableClusterBranching) {
			ClusterBuild build = buildStaticClusters();
			this.clusters = build.branchingClusters;
			this.clusterDiagnostics = build.diagnostics;
		} else {
			this.clusters = Collections.<BitSet>emptyList();
			this.clusterDiagnostics = null;
		}
	}

	/** 返回本次静态partition的可落盘诊断；这些指标不参与分支开关。 */
	public List<String> clusterDiagnosticConfigurationLines() {
		return clusterDiagnostics == null ? Collections.<String>emptyList()
				: clusterDiagnostics.configurationLines();
	}

	public ClusterPartitionDiagnostics getClusterDiagnostics() {
		return clusterDiagnostics;
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
			collectCutSetCandidates(candidates, aggregateKeys, arcValues, sink);
			if (config.structuredArcStrictTypePriority && !candidates.isEmpty()) {
				return limitSortedCutSetCandidates(candidates, limit);
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
		}
		int order = clusters.size();
		for (int firstIndex = 0; firstIndex < eligibleClusters.size(); firstIndex++) {
			int firstCluster = eligibleClusters.get(firstIndex).intValue();
			for (int secondIndex = firstIndex + 1; secondIndex < eligibleClusters.size(); secondIndex++) {
				int secondCluster = eligibleClusters.get(secondIndex).intValue();
				BitSet forwardMask = betweenDirectedMask(
						clusters.get(firstCluster), clusters.get(secondCluster), sink);
				addAggregateCandidate(candidates, keys, "clusterPair",
						"clusterPair(" + firstCluster + "->" + secondCluster + ")", forwardMask,
						flowValue(forwardMask, arcValues, sink), order++);
				BitSet backwardMask = betweenDirectedMask(
						clusters.get(secondCluster), clusters.get(firstCluster), sink);
				addAggregateCandidate(candidates, keys, "clusterPair",
						"clusterPair(" + secondCluster + "->" + firstCluster + ")", backwardMask,
						flowValue(backwardMask, arcValues, sink), order++);
			}
		}
	}

	private void collectCutSetCandidates(ArrayList<StrongBranchingCandidate> candidates, Set<String> keys,
			double[][] arcValues, int sink) {
		ArrayList<AggregateSpec> specs = buildCutSetCandidateSpecs(arcValues, sink);
		Collections.sort(specs, Comparator
				.comparingLong((AggregateSpec spec) -> cutSetHalfDistanceSortKey(spec.value))
				.thenComparingInt(spec -> spec.jobs.cardinality())
				.thenComparing(spec -> spec.jobs.toString()));
		int count = Math.min(config.cutSetCandidatePoolLimit, specs.size());
		for (int index = 0; index < count; index++) {
			AggregateSpec spec = specs.get(index);
			BitSet mask = incomingMask(spec.jobs, sink);
			addAggregateCandidate(candidates, keys, "cutSet", "cutSet(" + jobSetText(spec.jobs) + ")",
					mask, spec.value, index);
		}
	}

	/** CutSet已按三位距离排序；严格分层时不能再由公共排序器按原始double重排。 */
	static List<StrongBranchingCandidate> limitSortedCutSetCandidates(
			ArrayList<StrongBranchingCandidate> candidates, int limit) {
		return candidates.size() <= limit ? candidates
				: new ArrayList<StrongBranchingCandidate>(candidates.subList(0, limit));
	}

	/**
	 * 2026-09-12: 保持原有singleton起点和最大support-affinity扩张语义，但增量维护
	 * 集合进入流，避免每个prefix重建完整arc mask并重扫所有arc。只为排序后的
	 * 最终候选建立mask，因此不改变CutSet集合、预排序或分支约束。
	 */
	ArrayList<AggregateSpec> buildCutSetCandidateSpecs(double[][] arcValues, int sink) {
		if (config.cutSetSupernodeSeeds) {
			return buildSupernodeCutSetCandidateSpecs(arcValues, sink);
		}
		double[] singletonBoundary = new double[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			for (int from = 0; from < sink; from++) {
				if (from != job) {
					singletonBoundary[job] += arcValues[from][job];
				}
			}
		}
		ArrayList<AggregateSpec> specs = new ArrayList<AggregateSpec>();
		Set<BitSet> uniqueJobSets = new HashSet<BitSet>();
		for (int seed = 1; seed <= data.n; seed++) {
			BitSet jobs = new BitSet(data.n + 1);
			jobs.set(seed);
			double[] affinityToSet = new double[data.n + 1];
			for (int candidate = 1; candidate <= data.n; candidate++) {
				if (candidate != seed) {
					affinityToSet[candidate] = arcValues[seed][candidate] + arcValues[candidate][seed];
				}
			}
			double boundary = singletonBoundary[seed];
			while (jobs.cardinality() < data.n - 1) {
				int next = -1;
				double bestAffinity = tolerance;
				for (int candidate = 1; candidate <= data.n; candidate++) {
					if (jobs.get(candidate)) {
						continue;
					}
					double affinity = affinityToSet[candidate];
					if (Utility.compareGt(affinity, bestAffinity)
							|| (Math.abs(affinity - bestAffinity) <= tolerance && candidate < next)) {
						next = candidate;
						bestAffinity = affinity;
					}
				}
				if (next < 0) {
					break;
				}
				boundary += singletonBoundary[next] - bestAffinity;
				jobs.set(next);
				for (int candidate = 1; candidate <= data.n; candidate++) {
					if (!jobs.get(candidate)) {
						affinityToSet[candidate] += arcValues[next][candidate] + arcValues[candidate][next];
					}
				}
				if (isFractional(boundary)) {
					BitSet snapshot = (BitSet) jobs.clone();
					if (uniqueJobSets.add(snapshot)) {
						specs.add(new AggregateSpec(snapshot, boundary));
					}
				}
			}
		}
		return specs;
	}

	/** 近整数相邻任务合并为起点，之后仍按原CutSet的最大support affinity扩张。 */
	private ArrayList<AggregateSpec> buildSupernodeCutSetCandidateSpecs(double[][] arcValues, int sink) {
		int[] parents = new int[data.n + 1];
		for (int job = 1; job <= data.n; job++) {
			parents[job] = job;
		}
		for (int first = 1; first <= data.n; first++) {
			for (int second = first + 1; second <= data.n; second++) {
				if (arcValues[first][second] + arcValues[second][first] >= 0.999) {
					union(parents, first, second);
				}
			}
		}
		ArrayList<BitSet> groups = new ArrayList<BitSet>();
		int[] groupByRoot = new int[data.n + 1];
		Arrays.fill(groupByRoot, -1);
		for (int job = 1; job <= data.n; job++) {
			int root = find(parents, job);
			int index = groupByRoot[root];
			if (index < 0) {
				index = groups.size();
				groupByRoot[root] = index;
				groups.add(new BitSet(data.n + 1));
			}
			groups.get(index).set(job);
		}
		ArrayList<AggregateSpec> specs = new ArrayList<AggregateSpec>();
		Set<BitSet> unique = new HashSet<BitSet>();
		for (BitSet seed : groups) {
			BitSet jobs = (BitSet) seed.clone();
			while (jobs.cardinality() < data.n - 1) {
				int chosen = -1;
				double bestAffinity = tolerance;
				for (int index = 0; index < groups.size(); index++) {
					BitSet group = groups.get(index);
					if (jobs.intersects(group) || jobs.cardinality() + group.cardinality() > data.n - 1) {
						continue;
					}
					double affinity = 0.0;
					for (int from = jobs.nextSetBit(1); from >= 0; from = jobs.nextSetBit(from + 1)) {
						for (int to = group.nextSetBit(1); to >= 0; to = group.nextSetBit(to + 1)) {
							affinity += arcValues[from][to] + arcValues[to][from];
						}
					}
					if (chosen < 0 ? affinity > tolerance : affinity > bestAffinity + tolerance) {
						chosen = index;
						bestAffinity = affinity;
					}
				}
				if (chosen < 0) {
					break;
				}
				jobs.or(groups.get(chosen));
				double boundary = flowValue(incomingMask(jobs, sink), arcValues, sink);
				if (isFractional(boundary)) {
					BitSet snapshot = (BitSet) jobs.clone();
					if (unique.add(snapshot)) {
						specs.add(new AggregateSpec(snapshot, boundary));
					}
				}
			}
		}
		return specs;
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
				if (upper == 0) {
					// 2026-09-11: aggregate系数非负，Q<=0可直接禁止全部成员arc以收紧pricing域。
					// 分支行仍保留，用来约束继承自父RMP、尚未按新域过滤的历史列。
					applyZeroUpperBoundArcRestrictions(left, immutableMask, data.n + 2);
				}
				left.addAggregateArcConstraint(new AggregateArcBranchConstraint(family, description,
						immutableMask, data.n + 2, false, upper));
				right.addAggregateArcConstraint(new AggregateArcBranchConstraint(family, description,
						immutableMask, data.n + 2, true, lower));
				return new BranchResult(true, left, right,
						"Branched on " + description + " value=" + value + " <= " + upper + " or >= " + lower);
			}
		});
	}

	static void applyZeroUpperBoundArcRestrictions(Node node, BitSet memberArcs, int arcWidth) {
		for (int bit = memberArcs.nextSetBit(0); bit >= 0; bit = memberArcs.nextSetBit(bit + 1)) {
			int from = bit / arcWidth;
			int to = bit % arcWidth;
			// 理论上fractional Q<1不会包含父节点required arc；若数值边界触发，保留required状态，
			// 由同时存在的aggregate行把该子节点正确判为不可行，不能静默覆盖父分支。
			// aggregate行已经承担RMP约束；这里只收紧pricing/列过滤域，避免再建立一批冗余arc rows。
			node.forbidBranchImpliedArc(from, to);
		}
	}

	private boolean isFractional(double value) {
		return Utility.compareGt(Math.abs(value - Math.rint(value)), tolerance);
	}

	private double distanceToHalf(double value) {
		return Math.abs(value - Math.floor(value) - 0.5);
	}

	/** 2026-09-13: CutSet预排序只保留三位距离，避免增量求和的浮点尾差改变top-K。 */
	static long cutSetHalfDistanceSortKey(double value) {
		double fractionalPart = value - Math.floor(value);
		double distance = Math.abs(fractionalPart - 0.5);
		return Math.round(distance * 1_000.0);
	}

	private int familyOrder(String family) {
		if ("clusterBoundary".equals(family)) {
			return 0;
		}
		if ("clusterPair".equals(family)) {
			return 1;
		}
		return 2;
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

	/** 分开统计两个簇之间的有向连接，保留TWET的setup方向和完成时序。 */
	private BitSet betweenDirectedMask(BitSet fromJobs, BitSet toJobs, int sink) {
		BitSet mask = new BitSet((sink + 1) * (sink + 1));
		for (int from = fromJobs.nextSetBit(1); from >= 0; from = fromJobs.nextSetBit(from + 1)) {
			for (int to = toJobs.nextSetBit(1); to >= 0; to = toJobs.nextSetBit(to + 1)) {
				mask.set(from * (sink + 1) + to);
			}
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

	private ClusterBuild buildStaticClusters() {
		if (data.n < 3) {
			ArrayList<BitSet> partition = new ArrayList<BitSet>();
			BitSet allJobs = new BitSet(data.n + 1);
			allJobs.set(1, data.n + 1);
			if (!allJobs.isEmpty()) {
				partition.add(allJobs);
			}
			return new ClusterBuild(Collections.<BitSet>emptyList(),
					analyzePartition(partition, Double.NaN));
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
		ArrayList<BitSet> partition = new ArrayList<BitSet>();
		for (int job = 1; job <= data.n; job++) {
			int root = find(components, job);
			while (partition.size() <= root) {
				partition.add(null);
			}
			if (partition.get(root) == null) {
				partition.set(root, new BitSet(data.n + 1));
			}
			partition.get(root).set(job);
		}
		partition.removeIf(cluster -> cluster == null || cluster.isEmpty());
		ArrayList<BitSet> branchingClusters = new ArrayList<BitSet>();
		for (BitSet cluster : partition) {
			if (cluster.cardinality() < data.n) {
				branchingClusters.add((BitSet) cluster.clone());
			}
		}
		return new ClusterBuild(Collections.unmodifiableList(branchingClusters),
				analyzePartition(partition, threshold));
	}

	private ClusterPartitionDiagnostics analyzePartition(List<BitSet> partition, double mstThreshold) {
		double[][] setupDistances = setupOnlyDistances();
		double silhouette = setupOnlySilhouette(partition, setupDistances);
		int nontrivial = 0;
		int singletonJobs = 0;
		int maximumSize = 0;
		ArrayList<Integer> sizes = new ArrayList<Integer>();
		for (BitSet cluster : partition) {
			int size = cluster.cardinality();
			sizes.add(Integer.valueOf(size));
			maximumSize = Math.max(maximumSize, size);
			if (size == 1) {
				singletonJobs++;
			}
			if (size >= config.clusterMinimumCandidateSize && size < data.n) {
				nontrivial++;
			}
		}
		Collections.sort(sizes, Collections.reverseOrder());

		double intraSum = 0.0;
		double interSum = 0.0;
		long intraPairs = 0L;
		long interPairs = 0L;
		int[] clusterByJob = new int[data.n + 1];
		Arrays.fill(clusterByJob, -1);
		for (int cluster = 0; cluster < partition.size(); cluster++) {
			for (int job = partition.get(cluster).nextSetBit(1); job >= 0;
					job = partition.get(cluster).nextSetBit(job + 1)) {
				clusterByJob[job] = cluster;
			}
		}
		for (int first = 1; first <= data.n; first++) {
			for (int second = first + 1; second <= data.n; second++) {
				if (clusterByJob[first] == clusterByJob[second]) {
					intraSum += setupDistances[first][second];
					intraPairs++;
				} else {
					interSum += setupDistances[first][second];
					interPairs++;
				}
			}
		}
		double intraMean = intraPairs == 0L ? Double.NaN : intraSum / intraPairs;
		double interMean = interPairs == 0L ? Double.NaN : interSum / interPairs;
		double separationRatio = Double.isNaN(intraMean) || Double.isNaN(interMean)
				? Double.NaN : (intraMean == 0.0
						? (interMean == 0.0 ? Double.NaN : Double.POSITIVE_INFINITY)
						: interMean / intraMean);
		return new ClusterPartitionDiagnostics(partition.size(), sizes, nontrivial, silhouette,
				data.n == 0 ? Double.NaN : (double) maximumSize / data.n,
				data.n == 0 ? Double.NaN : (double) singletonJobs / data.n,
				intraMean, interMean, separationRatio, mstThreshold);
	}

	private double[][] setupOnlyDistances() {
		double[][] distances = new double[data.n + 1][data.n + 1];
		for (int first = 1; first <= data.n; first++) {
			for (int second = first + 1; second <= data.n; second++) {
				double distance = 0.5 * (data.s[first][second] + data.s[second][first]);
				distances[first][second] = distances[second][first] = distance;
			}
		}
		return distances;
	}

	private double setupOnlySilhouette(List<BitSet> partition, double[][] distances) {
		if (partition.size() <= 1 || data.n == 0) {
			return 0.0;
		}
		double sum = 0.0;
		for (int clusterIndex = 0; clusterIndex < partition.size(); clusterIndex++) {
			BitSet own = partition.get(clusterIndex);
			for (int job = own.nextSetBit(1); job >= 0; job = own.nextSetBit(job + 1)) {
				if (own.cardinality() <= 1) {
					continue;
				}
				double within = 0.0;
				for (int other = own.nextSetBit(1); other >= 0; other = own.nextSetBit(other + 1)) {
					if (other != job) {
						within += distances[job][other];
					}
				}
				within /= own.cardinality() - 1;
				double nearestOther = Double.POSITIVE_INFINITY;
				for (int otherCluster = 0; otherCluster < partition.size(); otherCluster++) {
					if (otherCluster == clusterIndex) {
						continue;
					}
					BitSet otherJobs = partition.get(otherCluster);
					double mean = 0.0;
					for (int other = otherJobs.nextSetBit(1); other >= 0;
							other = otherJobs.nextSetBit(other + 1)) {
						mean += distances[job][other];
					}
					nearestOther = Math.min(nearestOther, mean / otherJobs.cardinality());
				}
				double scale = Math.max(within, nearestOther);
				if (scale > 0.0 && Double.isFinite(nearestOther)) {
					sum += (nearestOther - within) / scale;
				}
			}
		}
		return sum / data.n;
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

	static final class AggregateSpec {
		final BitSet jobs;
		final double value;

		AggregateSpec(BitSet jobs, double value) {
			this.jobs = jobs;
			this.value = value;
		}
	}

	private static final class ClusterBuild {
		final List<BitSet> branchingClusters;
		final ClusterPartitionDiagnostics diagnostics;

		ClusterBuild(List<BitSet> branchingClusters, ClusterPartitionDiagnostics diagnostics) {
			this.branchingClusters = branchingClusters;
			this.diagnostics = diagnostics;
		}
	}

	/** 静态Cluster partition的结构质量统计，只用于日志和离线门槛分析。 */
	public static final class ClusterPartitionDiagnostics {
		private final int clusterCount;
		private final List<Integer> clusterSizes;
		private final int nontrivialClusterCount;
		private final double setupOnlySilhouette;
		private final double maximumClusterShare;
		private final double singletonJobShare;
		private final double meanIntraClusterSetup;
		private final double meanInterClusterSetup;
		private final double interIntraSetupRatio;
		private final double mstCutThreshold;

		ClusterPartitionDiagnostics(int clusterCount, List<Integer> clusterSizes, int nontrivialClusterCount,
				double setupOnlySilhouette, double maximumClusterShare, double singletonJobShare,
				double meanIntraClusterSetup, double meanInterClusterSetup, double interIntraSetupRatio,
				double mstCutThreshold) {
			this.clusterCount = clusterCount;
			this.clusterSizes = Collections.unmodifiableList(new ArrayList<Integer>(clusterSizes));
			this.nontrivialClusterCount = nontrivialClusterCount;
			this.setupOnlySilhouette = setupOnlySilhouette;
			this.maximumClusterShare = maximumClusterShare;
			this.singletonJobShare = singletonJobShare;
			this.meanIntraClusterSetup = meanIntraClusterSetup;
			this.meanInterClusterSetup = meanInterClusterSetup;
			this.interIntraSetupRatio = interIntraSetupRatio;
			this.mstCutThreshold = mstCutThreshold;
		}

		public List<String> configurationLines() {
			ArrayList<String> lines = new ArrayList<String>();
			lines.add("run.clusterDiagnostics.clusterCount=" + clusterCount);
			lines.add("run.clusterDiagnostics.clusterSizes=" + clusterSizes);
			lines.add("run.clusterDiagnostics.nontrivialClusterCount=" + nontrivialClusterCount);
			lines.add("run.clusterDiagnostics.setupOnlySilhouette=" + format(setupOnlySilhouette));
			lines.add("run.clusterDiagnostics.maximumClusterShare=" + format(maximumClusterShare));
			lines.add("run.clusterDiagnostics.singletonJobShare=" + format(singletonJobShare));
			lines.add("run.clusterDiagnostics.meanIntraClusterSetup=" + format(meanIntraClusterSetup));
			lines.add("run.clusterDiagnostics.meanInterClusterSetup=" + format(meanInterClusterSetup));
			lines.add("run.clusterDiagnostics.interIntraSetupRatio=" + format(interIntraSetupRatio));
			lines.add("run.clusterDiagnostics.mstCutThreshold=" + format(mstCutThreshold));
			return Collections.unmodifiableList(lines);
		}

		public int getClusterCount() { return clusterCount; }
		public List<Integer> getClusterSizes() { return clusterSizes; }
		public int getNontrivialClusterCount() { return nontrivialClusterCount; }
		public double getSetupOnlySilhouette() { return setupOnlySilhouette; }
		public double getMaximumClusterShare() { return maximumClusterShare; }
		public double getSingletonJobShare() { return singletonJobShare; }
		public double getMeanIntraClusterSetup() { return meanIntraClusterSetup; }
		public double getMeanInterClusterSetup() { return meanInterClusterSetup; }
		public double getInterIntraSetupRatio() { return interIntraSetupRatio; }
		public double getMstCutThreshold() { return mstCutThreshold; }

		private static String format(double value) {
			return Double.isFinite(value) ? String.format(Locale.US, "%.6f", value) : Double.toString(value);
		}
	}
}
