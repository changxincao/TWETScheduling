package TWETBPC.CUT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;

import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterSolution;

/**
 * 三元 subset-row cut 分离器。
 * <p>
 * 2026-06-13: 当前按旧 VRP 的 SRI 口径实现：枚举 job 三元组，若当前 LP 正值内部列中
 * “一条列包含该三元组中至少两个 job”的变量和超过 1，则生成 row <= 1 的 subset-row cut。
 */
public class SubsetRowCutGenerator implements CutGenerator {

	private static final double VALUE_TOLERANCE = 1e-8;

	private final TWETBPCConfig config;

	public SubsetRowCutGenerator() {
		this(new TWETBPCConfig());
	}

	public SubsetRowCutGenerator(TWETBPCConfig config) {
		this.config = config;
	}

	@Override
	public CutGenerationResult separate(LP lp) {
		if (!config.enableSubsetRowCutsForPartialDominance) {
			return CutGenerationResult.empty("subset-row disabled");
		}
		if (!config.useGCNGBBStyleNgDssrPartialDominancePricing) {
			return CutGenerationResult.empty("subset-row requires partial-list ng-DSSR exact pricing");
		}
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null || solution.getColumnValues().isEmpty()) {
			return CutGenerationResult.empty("subset-row no positive internal columns");
		}

		HashSet<String> activeTriples = activeSubsetRowTriples(lp);
		ArrayList<Candidate> candidates = new ArrayList<Candidate>();
		for (int first = 1; first <= lp.getData().n; first++) {
			for (int second = first + 1; second <= lp.getData().n; second++) {
				for (int third = second + 1; third <= lp.getData().n; third++) {
					String signature = tripleSignature(first, second, third);
					if (activeTriples.contains(signature)) {
						continue;
					}
					double lhs = subsetRowValue(lp, solution, first, second, third);
					if (Utility.compareGt(lhs, 1.0 + VALUE_TOLERANCE)) {
						candidates.add(new Candidate(first, second, third, lhs));
					}
				}
			}
		}
		if (candidates.isEmpty()) {
			return CutGenerationResult.empty("subset-row no violated triples");
		}
		Collections.sort(candidates, new Comparator<Candidate>() {
			@Override
			public int compare(Candidate a, Candidate b) {
				if (Utility.compareGt(a.value, b.value)) {
					return -1;
				}
				if (Utility.compareLt(a.value, b.value)) {
					return 1;
				}
				if (a.first != b.first) {
					return Integer.compare(a.first, b.first);
				}
				if (a.second != b.second) {
					return Integer.compare(a.second, b.second);
				}
				return Integer.compare(a.third, b.third);
			}
		});
		if (Utility.compareLt(candidates.get(0).value, config.subsetRowCutMinimumViolationValue)) {
			return CutGenerationResult.empty("subset-row max violation below add threshold: "
					+ candidates.get(0).value);
		}

		ArrayList<TWETCut> cuts = new ArrayList<TWETCut>();
		// 2026-06-13: 对齐旧 VRP 的 lp.sr_cus_number：appearance 限制按当前 active cuts 累计，而不是只看本轮新增。
		int[] appearances = activeSubsetRowAppearances(lp);
		for (Candidate candidate : candidates) {
			if (cuts.size() >= config.maxSubsetRowCutsPerRound) {
				break;
			}
			if (Utility.compareLt(candidate.value, config.subsetRowCutMinimumThreshold)) {
				break;
			}
			if (appearances[candidate.first] >= config.maxSubsetRowCutAppearancesPerJob
					|| appearances[candidate.second] >= config.maxSubsetRowCutAppearancesPerJob
					|| appearances[candidate.third] >= config.maxSubsetRowCutAppearancesPerJob) {
				continue;
			}
			ArrayList<Integer> scope = new ArrayList<Integer>();
			scope.add(Integer.valueOf(candidate.first));
			scope.add(Integer.valueOf(candidate.second));
			scope.add(Integer.valueOf(candidate.third));
			cuts.add(new TWETCut(-1, TWETCutType.SUBSET_ROW, scope, 1.0, "SRI3"));
			appearances[candidate.first]++;
			appearances[candidate.second]++;
			appearances[candidate.third]++;
		}
		if (cuts.isEmpty()) {
			return CutGenerationResult.empty("subset-row candidates filtered by appearance limits");
		}
		return new CutGenerationResult(cuts, true,
				"subset-row added " + cuts.size() + " cuts, best lhs=" + candidates.get(0).value);
	}

	@Override
	public String getName() {
		return "SubsetRowCutGenerator";
	}

	private HashSet<String> activeSubsetRowTriples(LP lp) {
		HashSet<String> triples = new HashSet<String>();
		for (int cutId : lp.getActiveCutIds()) {
			TWETCut cut = lp.getCutPool().getCut(cutId);
			if (cut.getType() != TWETCutType.SUBSET_ROW || cut.getScopeJobs().size() != 3) {
				continue;
			}
			ArrayList<Integer> jobs = new ArrayList<Integer>(cut.getScopeJobs());
			Collections.sort(jobs);
			triples.add(tripleSignature(jobs.get(0).intValue(), jobs.get(1).intValue(), jobs.get(2).intValue()));
		}
		return triples;
	}

	private int[] activeSubsetRowAppearances(LP lp) {
		int[] appearances = new int[lp.getData().n + 1];
		for (int cutId : lp.getActiveCutIds()) {
			TWETCut cut = lp.getCutPool().getCut(cutId);
			if (cut.getType() != TWETCutType.SUBSET_ROW) {
				continue;
			}
			for (int job : cut.getScopeJobs()) {
				if (job >= 1 && job <= lp.getData().n) {
					appearances[job]++;
				}
			}
		}
		return appearances;
	}

	private double subsetRowValue(LP lp, TWETMasterSolution solution, int first, int second, int third) {
		double value = 0.0;
		for (Map.Entry<Integer, Double> entry : solution.getColumnValues().entrySet()) {
			TWETColumn column = lp.getPool().getColumn(entry.getKey().intValue());
			int count = 0;
			if (column.containsJob(first)) {
				count++;
			}
			if (column.containsJob(second)) {
				count++;
			}
			if (column.containsJob(third)) {
				count++;
			}
			if (count >= 2) {
				value += entry.getValue().doubleValue();
			}
		}
		return value;
	}

	private String tripleSignature(int first, int second, int third) {
		return first + "," + second + "," + third;
	}

	private static final class Candidate {
		final int first;
		final int second;
		final int third;
		final double value;

		Candidate(int first, int second, int third, double value) {
			this.first = first;
			this.second = second;
			this.third = third;
			this.value = value;
		}
	}
}
