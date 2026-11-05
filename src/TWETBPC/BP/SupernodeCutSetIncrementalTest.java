package TWETBPC.BP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;

/** 用改动前的完整扫描逻辑独立校验 supernode 增量扩张；不启动 LP 或树搜索。 */
public final class SupernodeCutSetIncrementalTest {

	private static final double TOLERANCE = 1.0e-6;
	private static final double VALUE_ERROR = 1.0e-9;
	private static volatile int benchmarkSink;
	private final Data data;
	private int cases;
	private int candidates;
	private double maximumBoundaryError;

	private SupernodeCutSetIncrementalTest() throws Exception {
		data = new Data("data/100-2/wet100_001_2m.dat", false, true);
	}

	public static void main(String[] args) throws Exception {
		if (args.length > 1 || (args.length == 1 && !"benchmark".equals(args[0]))) {
			throw new IllegalArgumentException("Usage: SupernodeCutSetIncrementalTest [benchmark]");
		}
		SupernodeCutSetIncrementalTest test = new SupernodeCutSetIncrementalTest();
		test.verifyStructuralCases();
		test.verifyThresholdCases();
		test.verifyRandomCases();
		System.out.printf(Locale.ROOT,
				"SupernodeCutSetIncrementalTest passed: cases=%d candidates=%d maxBoundaryError=%.3g%n",
				test.cases, test.candidates, test.maximumBoundaryError);
		if (args.length == 1) {
			test.benchmark();
		}
	}

	private void verifyStructuralCases() {
		verify("one-job", emptyFlow(1), 1, false);
		verify("two-jobs", emptyFlow(2), 2, false);
		verify("zero-flow", emptyFlow(8), 8, false);
		verify("no-merge-path-mixture", pathMixture(12), 12, true);

		double[][] merged = decimalFlow(10, new Random(31));
		join(merged, 1, 4, 7);
		join(merged, 2, 5);
		join(merged, 3, 6);
		verify("interleaved-merged-groups", merged, 6, true);

		double[][] giant = decimalFlow(8, new Random(32));
		join(giant, 1, 2, 3, 4, 5, 6, 7, 8);
		verify("single-giant-group", giant, 1, false);
		double[][] nearlyGiant = decimalFlow(8, new Random(33));
		join(nearlyGiant, 1, 2, 3, 4, 5, 6, 7);
		verify("n-minus-one-group", nearlyGiant, 2, false);

		double[][] disconnected = emptyFlow(8);
		join(disconnected, 1, 2);
		join(disconnected, 4, 5);
		disconnected[2][3] = 0.2;
		disconnected[5][6] = 0.4;
		for (int job = 1; job <= 8; job++) {
			disconnected[0][job] = 0.13;
		}
		verify("disconnected-and-isolated", disconnected, 6, true);

		double[][] sourceOnly = emptyFlow(6);
		for (int job = 1; job <= 6; job++) {
			sourceOnly[0][job] = 0.1 * job;
		}
		verify("source-alone-does-not-expand", sourceOnly, 6, false);
		double[][] source = copy(sourceOnly);
		source[1][2] = 0.25;
		source[2][3] = 0.2;
		source[4][5] = 0.3;
		List<ExpectedSpec> sourceSpecs = verify("source-in-boundary", source, 6, true);
		double[][] irrelevant = copy(source);
		for (int job = 1; job <= 6; job++) {
			irrelevant[job][7] = 0.37 * job;
			irrelevant[job][0] = 0.19 * job;
			irrelevant[7][job] = 0.23 * job;
			irrelevant[job][job] = 7.0;
		}
		List<ExpectedSpec> ignored = verify("exclude-sink-depot-target-self-arcs", irrelevant, 6, true);
		assertOrderedEqual("irrelevant arc invariance", sourceSpecs, ignored);
	}

	/** 同时覆盖严格比较的两侧和精确浮点边界，不用大容差放过候选增删或排序变化。 */
	private void verifyThresholdCases() {
		for (double affinity : new double[] { 0.5 * TOLERANCE, Math.nextDown(TOLERANCE),
				TOLERANCE, Math.nextUp(TOLERANCE), 1.5 * TOLERANCE }) {
			double[][] flow = pairFlow(0.37);
			flow[1][2] = affinity;
			verify("affinity-first-" + affinity, flow, 3, affinity > TOLERANCE);
		}
		double comparison = 0.25 + TOLERANCE;
		for (double second : new double[] { comparison - 1.0e-10, Math.nextDown(comparison),
				comparison, Math.nextUp(comparison), comparison + 1.0e-10 }) {
			double[][] flow = emptyFlow(6);
			flow[1][2] = 0.25;
			flow[1][3] = second;
			flow[2][4] = 0.1;
			flow[3][5] = 0.1;
			for (int job = 1; job <= 6; job++) {
				flow[0][job] = 0.17 + job * 0.013;
			}
			verify("affinity-best-plus-tolerance-" + second, flow, 6, true);
		}
		double groupedThreshold = 0.2 + TOLERANCE;
		for (double lastTerm : new double[] { groupedThreshold - 1.0e-10, Math.nextDown(groupedThreshold),
				groupedThreshold, Math.nextUp(groupedThreshold), groupedThreshold + 1.0e-10 }) {
			double[][] flow = emptyFlow(8);
			join(flow, 1, 4);
			join(flow, 2, 5);
			flow[1][2] = 0.1;
			flow[4][5] = 0.2;
			flow[1][3] = 0.1;
			flow[4][3] = lastTerm;
			for (int job = 1; job <= 8; job++) {
				flow[0][job] = 0.13;
			}
			verify("grouped-affinity-sum-threshold-" + lastTerm, flow, 6, true);
		}
		for (double edge : new double[] { Math.nextDown(0.999), 0.999, Math.nextUp(0.999) }) {
			double[][] flow = emptyFlow(5);
			flow[1][2] = edge;
			flow[2][3] = 0.2;
			flow[0][1] = 0.23;
			flow[0][3] = 0.31;
			verify("merge-threshold-" + edge, flow, edge >= 0.999 ? 4 : 5, true);
		}

		double fractionalThreshold = TOLERANCE + Utility.EPS;
		for (double center : new double[] { fractionalThreshold, 1.0 - fractionalThreshold,
				1.0 + fractionalThreshold, 0.4995, 0.5005, 1.4995, 1.5005 }) {
			for (double value : new double[] { center - 1.0e-10, Math.nextDown(center), center,
					Math.nextUp(center), center + 1.0e-10 }) {
				verify("boundary-or-sort-threshold-" + value, pairFlow(value), 3, null);
			}
		}
		for (double middle : new double[] { Math.nextDown(0.5005), 0.5005, Math.nextUp(0.5005) }) {
			double[][] flow = emptyFlow(6);
			flow[1][2] = flow[3][4] = flow[5][6] = 0.25;
			flow[0][1] = 0.5004;
			flow[0][3] = middle;
			flow[0][5] = 0.5006;
			verify("multiple-sort-buckets-" + middle, flow, 6, true);
		}
		// 多段累加后才接近阈值，避免只有 pair 的测试漏掉求和次序变化。
		for (double offset : new double[] { -1.0e-10, 0.0, 1.0e-10 }) {
			double[][] flow = decimalFlow(9, new Random(83));
			join(flow, 1, 4);
			join(flow, 2, 5);
			flow[0][1] = 0.5005 + offset;
			flow[3][6] = 0.1 + TOLERANCE + offset;
			verify("merged-nonbinary-near-threshold-" + offset, flow, 7, true);
		}
	}

	private void verifyRandomCases() {
		Random random = new Random(20261105L);
		for (int sample = 0; sample < 80; sample++) {
			int n = 4 + random.nextInt(17);
			double[][] flow = decimalFlow(n, random);
			if (sample % 2 == 0) {
				ArrayList<Integer> order = new ArrayList<Integer>();
				for (int job = 1; job <= n; job++) {
					order.add(job);
				}
				Collections.shuffle(order, random);
				for (int index = 0; index + 1 < n; index += 3) {
					join(flow, order.get(index), order.get(index + 1));
				}
			}
			verify("random-decimal-" + sample, flow, -1, true);
		}
	}

	private StructuredArcFlowBrancher brancher(int n) {
		// 此API仅读取n和分支配置；保留100任务Data的数组，缩小有效规模，无须落盘临时实例。
		data.n = n;
		TWETBPCConfig config = new TWETBPCConfig();
		config.branchingTolerance = TOLERANCE;
		config.enableCutSetBranching = true;
		config.cutSetSupernodeSeeds = true;
		config.enableClusterBranching = false;
		return new StructuredArcFlowBrancher(data, config);
	}

	private List<ExpectedSpec> verify(String name, double[][] flow, int groupCount, Boolean nonempty) {
		int n = flow.length - 2;
		double[][] before = copy(flow);
		if (groupCount >= 0) {
			require(referenceGroups(n, flow).size() == groupCount, name + ": fixture group count");
		}
		List<ExpectedSpec> expected = reference(n, flow);
		List<ExpectedSpec> actual = new ArrayList<ExpectedSpec>();
		for (StructuredArcFlowBrancher.AggregateSpec spec : brancher(n).buildCutSetCandidateSpecs(flow, n + 1)) {
			actual.add(new ExpectedSpec((BitSet) spec.jobs.clone(), spec.value));
		}
		if (nonempty != null) {
			require(!expected.isEmpty() == nonempty.booleanValue(), name + ": fixture candidate presence");
		}
		require(Arrays.deepEquals(before, flow), name + ": input flow was mutated");
		assertOrderedEqual(name, expected, actual);
		Set<BitSet> unique = new HashSet<BitSet>();
		for (int index = 0; index < actual.size(); index++) {
			ExpectedSpec spec = actual.get(index);
			require(unique.add(spec.jobs), name + ": duplicate candidate " + spec.jobs);
			require(spec.jobs.cardinality() < n, name + ": whole-job-set candidate");
			// 独立双循环检验边界；参考路径则保留旧mask的from-major求和次序。
			double direct = 0.0;
			for (int from = 0; from <= n; from++) {
				for (int to = 1; to <= n; to++) {
					if (!spec.jobs.get(from) && spec.jobs.get(to)) {
						direct += flow[from][to];
					}
				}
			}
			assertValue(name + ": direct boundary", direct, spec.value);
			require(sortKey(expected.get(index).value) == sortKey(spec.value),
					name + ": three-decimal bucket differs for " + spec.jobs);
		}
		Comparator<ExpectedSpec> comparator = Comparator.comparingLong((ExpectedSpec s) -> sortKey(s.value))
				.thenComparingInt(s -> s.jobs.cardinality()).thenComparing(s -> s.jobs.toString());
		List<ExpectedSpec> sortedExpected = new ArrayList<ExpectedSpec>(expected);
		List<ExpectedSpec> sortedActual = new ArrayList<ExpectedSpec>(actual);
		sortedExpected.sort(comparator);
		sortedActual.sort(comparator);
		assertOrderedEqual(name + ": three-decimal sorted order", sortedExpected, sortedActual);
		cases++;
		candidates += actual.size();
		return actual;
	}

	private void assertOrderedEqual(String name, List<ExpectedSpec> expected, List<ExpectedSpec> actual) {
		require(expected.size() == actual.size(), name + ": count expected=" + expected.size()
				+ " actual=" + actual.size());
		for (int index = 0; index < expected.size(); index++) {
			ExpectedSpec e = expected.get(index);
			ExpectedSpec a = actual.get(index);
			require(e.jobs.equals(a.jobs), name + ": index=" + index + " expected=" + e.jobs + " actual=" + a.jobs);
			assertValue(name + ": index=" + index + " jobs=" + e.jobs, e.value, a.value);
		}
	}

	private void assertValue(String name, double expected, double actual) {
		double error = Math.abs(expected - actual);
		require(Double.isFinite(actual) && error <= VALUE_ERROR * Math.max(1.0, Math.abs(expected)),
				name + ": boundary expected=" + expected + " actual=" + actual);
		maximumBoundaryError = Math.max(maximumBoundaryError, error);
	}

	private static long sortKey(double value) {
		return Math.round(Math.abs(value - Math.floor(value) - 0.5) * 1_000.0);
	}

	/** 固定2026-11-05改动前逻辑，不调用生产分组、增量或数值guard辅助方法。 */
	private static List<ExpectedSpec> reference(int n, double[][] flow) {
		List<BitSet> groups = referenceGroups(n, flow);
		List<ExpectedSpec> result = new ArrayList<ExpectedSpec>();
		Set<BitSet> unique = new HashSet<BitSet>();
		for (BitSet seed : groups) {
			BitSet jobs = (BitSet) seed.clone();
			while (jobs.cardinality() < n - 1) {
				int chosen = -1;
				double bestAffinity = TOLERANCE;
				for (int index = 0; index < groups.size(); index++) {
					BitSet group = groups.get(index);
					if (jobs.intersects(group) || jobs.cardinality() + group.cardinality() > n - 1) {
						continue;
					}
					double affinity = 0.0;
					for (int from = jobs.nextSetBit(1); from >= 0; from = jobs.nextSetBit(from + 1)) {
						for (int to = group.nextSetBit(1); to >= 0; to = group.nextSetBit(to + 1)) {
							affinity += flow[from][to] + flow[to][from];
						}
					}
					if (chosen < 0 ? affinity > TOLERANCE : affinity > bestAffinity + TOLERANCE) {
						chosen = index;
						bestAffinity = affinity;
					}
				}
				if (chosen < 0) {
					break;
				}
				jobs.or(groups.get(chosen));
				double boundary = referenceMaskBoundary(jobs, flow, n + 1);
				if (Math.abs(boundary - Math.rint(boundary)) > TOLERANCE + Utility.EPS) {
					BitSet snapshot = (BitSet) jobs.clone();
					if (unique.add(snapshot)) {
						result.add(new ExpectedSpec(snapshot, boundary));
					}
				}
			}
		}
		return result;
	}

	private static List<BitSet> referenceGroups(int n, double[][] flow) {
		int[] parents = new int[n + 1];
		for (int job = 1; job <= n; job++) {
			parents[job] = job;
		}
		for (int first = 1; first <= n; first++) {
			for (int second = first + 1; second <= n; second++) {
				if (flow[first][second] + flow[second][first] >= 0.999) {
					int a = root(parents, first), b = root(parents, second);
					parents[Math.max(a, b)] = Math.min(a, b);
				}
			}
		}
		List<BitSet> groups = new ArrayList<BitSet>();
		int[] groupByRoot = new int[n + 1];
		Arrays.fill(groupByRoot, -1);
		for (int job = 1; job <= n; job++) {
			int root = root(parents, job);
			int index = groupByRoot[root];
			if (index < 0) {
				index = groups.size();
				groupByRoot[root] = index;
				groups.add(new BitSet(n + 1));
			}
			groups.get(index).set(job);
		}
		return groups;
	}

	private static int root(int[] parents, int value) {
		while (parents[value] != value) {
			parents[value] = parents[parents[value]];
			value = parents[value];
		}
		return value;
	}

	/** 原实现先建mask再按bit递增求和，因此是from-major，而非按to遍历累加。 */
	private static double referenceMaskBoundary(BitSet jobs, double[][] flow, int sink) {
		int width = sink + 1;
		BitSet mask = new BitSet(width * width);
		for (int to = jobs.nextSetBit(1); to >= 0; to = jobs.nextSetBit(to + 1)) {
			for (int from = 0; from < sink; from++) {
				if (!jobs.get(from)) {
					mask.set(from * width + to);
				}
			}
		}
		double value = 0.0;
		for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
			value += flow[bit / width][bit % width];
		}
		return value;
	}

	private static double[][] emptyFlow(int n) {
		return new double[n + 2][n + 2];
	}

	private static double[][] pairFlow(double boundary) {
		double[][] flow = emptyFlow(3);
		flow[1][2] = 0.25;
		flow[0][1] = boundary;
		return flow;
	}

	private static double[][] decimalFlow(int n, Random random) {
		double[][] flow = emptyFlow(n);
		for (int to = 1; to <= n; to++) {
			flow[0][to] = 0.13 + random.nextDouble() * 0.2;
			for (int from = 1; from <= n; from++) {
				if (from != to) {
					flow[from][to] = 0.001 + random.nextDouble() * 0.07;
				}
			}
		}
		return flow;
	}

	private static void join(double[][] flow, int... jobs) {
		for (int index = 1; index < jobs.length; index++) {
			flow[jobs[index - 1]][jobs[index]] = 1.0;
		}
	}

	private static double[][] pathMixture(int n) {
		double[][] flow = emptyFlow(n);
		Random random = new Random(20261105L);
		List<Integer> jobs = new ArrayList<Integer>();
		for (int job = 1; job <= n; job++) {
			jobs.add(job);
		}
		for (int path = 0; path < 16; path++) {
			Collections.shuffle(jobs, random);
			flow[0][jobs.get(0)] += 1.0 / 16;
			for (int index = 1; index < n; index++) {
				flow[jobs.get(index - 1)][jobs.get(index)] += 1.0 / 16;
			}
			flow[jobs.get(n - 1)][n + 1] += 1.0 / 16;
		}
		return flow;
	}

	private static double[][] copy(double[][] flow) {
		double[][] result = new double[flow.length][];
		for (int row = 0; row < flow.length; row++) {
			result[row] = flow[row].clone();
		}
		return result;
	}

	/** 可选微基准，无速度断言；只能说明候选构造耗时，不能换算成整树加速。 */
	private void benchmark() {
		for (int n : new int[] { 40, 80, 100 }) {
			double[][] flow = pathMixture(n);
			verify("benchmark-" + n, flow, n, true);
			StructuredArcFlowBrancher brancher = brancher(n);
			for (int warmup = 0; warmup < 4; warmup++) {
				benchmarkSink = brancher.buildCutSetCandidateSpecs(flow, n + 1).size();
				benchmarkSink = reference(n, flow).size();
			}
			long[] actual = new long[7], old = new long[7];
			for (int repetition = 0; repetition < 7; repetition++) {
				if (repetition % 2 == 0) {
					actual[repetition] = timed(brancher, n, flow, false);
					old[repetition] = timed(brancher, n, flow, true);
				} else {
					old[repetition] = timed(brancher, n, flow, true);
					actual[repetition] = timed(brancher, n, flow, false);
				}
			}
			Arrays.sort(actual);
			Arrays.sort(old);
			System.out.printf(Locale.ROOT, "Supernode benchmark n=%d candidates=%d currentMs=%.3f referenceMs=%.3f ratio=%.2f%n",
					n, benchmarkSink, actual[3] / 1.0e6, old[3] / 1.0e6, (double) old[3] / actual[3]);
		}
	}

	private static long timed(StructuredArcFlowBrancher brancher, int n, double[][] flow, boolean old) {
		long start = System.nanoTime();
		benchmarkSink = old ? reference(n, flow).size() : brancher.buildCutSetCandidateSpecs(flow, n + 1).size();
		return System.nanoTime() - start;
	}

	/** 独立参考候选，不借用生产AggregateSpec构造或比较辅助函数。 */
	private record ExpectedSpec(BitSet jobs, double value) {
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
