package TWETBPC.CUT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;
import TWETBPC.Model.TWETMasterSolution;

/**
 * subset-row / rank-1 cut 分离器。
 * <p>
 * partial-ng 模式保持原来的三元 subset-row 口径；time-indexed rank-1 模式按论文同时尝试一行和三行
 * multiplier 为 1/2 的 rank-1 cuts，并用 Algorithm 2 构造 limited arc memory。
 */
public class SubsetRowCutGenerator implements CutGenerator {

	private static final double VALUE_TOLERANCE = 1e-8;
	private static final int PAPER_MAX_ONE_ROW_CUTS_PER_ROUND = 50;
	private static final int PAPER_MAX_THREE_ROW_CUTS_PER_ROUND = 75;

	private final TWETBPCConfig config;

	public SubsetRowCutGenerator() {
		this(new TWETBPCConfig());
	}

	public SubsetRowCutGenerator(TWETBPCConfig config) {
		this.config = config;
	}

	@Override
	public CutGenerationResult separate(LP lp) {
		if (!config.enableSubsetRowCutsForPartialDominance && !config.enableSubsetRowCutsForTimeIndexedGraph) {
			return CutGenerationResult.empty("subset-row disabled");
		}
		if (!isSupportedPricingMode()) {
			return CutGenerationResult.empty("subset-row requires partial-list ng-DSSR or time-indexed rank-1 cut pricing");
		}
		TWETMasterSolution solution = lp.getLastSolution();
		if (solution == null || solution.getColumnValues().isEmpty()) {
			return CutGenerationResult.empty("subset-row no positive internal columns");
		}
		return isTimeIndexedRank1Mode() ? separatePaperRank1Cuts(lp, solution) : separateLegacyTripleCuts(lp, solution);
	}

	private CutGenerationResult separatePaperRank1Cuts(LP lp, TWETMasterSolution solution) {
		HashSet<String> activeCuts = activeCutSignatures(lp);
		int remainingNewBases = Math.max(0, config.maxSubsetRowCutsPerNode - activeCuts.size());
		int[] appearances = activeSubsetRowAppearances(lp);
		ArrayList<PositiveColumn> positiveColumns = positiveColumns(lp, solution);
		ArrayList<Candidate> oneRow = new ArrayList<Candidate>();
		ArrayList<Candidate> threeRow = new ArrayList<Candidate>();
		for (int first = 1; first <= lp.getData().n; first++) {
			int[] oneRowScope = new int[] { first };
			if (activeCuts.contains(scopeSignature(oneRowScope))
					|| appearances[first] < config.maxSubsetRowCutAppearancesPerJob) {
				addCandidateIfViolated(positiveColumns, oneRow, oneRowScope);
			}
			for (int second = first + 1; second <= lp.getData().n; second++) {
				for (int third = second + 1; third <= lp.getData().n; third++) {
					int[] scope = new int[] { first, second, third };
					boolean activeBase = activeCuts.contains(scopeSignature(scope));
					if (!activeBase && (appearances[first] >= config.maxSubsetRowCutAppearancesPerJob
							|| appearances[second] >= config.maxSubsetRowCutAppearancesPerJob
							|| appearances[third] >= config.maxSubsetRowCutAppearancesPerJob)) {
						continue;
					}
					addCandidateIfViolated(positiveColumns, threeRow, scope);
				}
			}
		}
		sortCandidates(oneRow);
		sortCandidates(threeRow);
		ArrayList<TWETCut> cuts = new ArrayList<TWETCut>();
		int newBases = addPaperCuts(lp, solution, oneRow, cuts, appearances, activeCuts,
				remainingNewBases, PAPER_MAX_ONE_ROW_CUTS_PER_ROUND);
		addPaperCuts(lp, solution, threeRow, cuts, appearances, activeCuts,
				remainingNewBases - newBases, PAPER_MAX_THREE_ROW_CUTS_PER_ROUND);
		if (cuts.isEmpty()) {
			return CutGenerationResult.empty("rank-1 no violated one-row or three-row cuts");
		}
		return new CutGenerationResult(cuts, true,
				"rank-1 added " + cuts.size() + " cuts, oneRowCandidates=" + oneRow.size()
						+ ", threeRowCandidates=" + threeRow.size());
	}

	private CutGenerationResult separateLegacyTripleCuts(LP lp, TWETMasterSolution solution) {
		HashSet<String> activeTriples = activeTripleSignatures(lp);
		int remainingNodeCuts = config.maxSubsetRowCutsPerNode - activeTriples.size();
		if (remainingNodeCuts <= 0) {
			return CutGenerationResult.empty("subset-row node cut limit reached: " + activeTriples.size());
		}
		int[] appearances = activeSubsetRowAppearances(lp);
		ArrayList<Candidate> candidates = new ArrayList<Candidate>();
		for (int first = 1; first <= lp.getData().n; first++) {
			if (appearances[first] >= config.maxSubsetRowCutAppearancesPerJob) {
				continue;
			}
			for (int second = first + 1; second <= lp.getData().n; second++) {
				if (appearances[second] >= config.maxSubsetRowCutAppearancesPerJob) {
					continue;
				}
				for (int third = second + 1; third <= lp.getData().n; third++) {
					if (appearances[third] >= config.maxSubsetRowCutAppearancesPerJob) {
						continue;
					}
					int[] scope = new int[] { first, second, third };
					String signature = scopeSignature(scope);
					if (activeTriples.contains(signature)) {
						continue;
					}
					double lhs = subsetRowValue(lp, solution, scope);
					if (Utility.compareGt(lhs, 1.0 + VALUE_TOLERANCE)) {
						candidates.add(new Candidate(scope, lhs, 1.0, signature));
					}
				}
			}
		}
		if (candidates.isEmpty()) {
			return CutGenerationResult.empty("subset-row no violated triples");
		}
		sortCandidates(candidates);
		if (Utility.compareLt(candidates.get(0).value, config.subsetRowCutMinimumViolationValue)) {
			return CutGenerationResult.empty("subset-row max violation below add threshold: " + candidates.get(0).value);
		}
		ArrayList<TWETCut> cuts = new ArrayList<TWETCut>();
		for (Candidate candidate : candidates) {
			if (cuts.size() >= config.maxSubsetRowCutsPerRound || cuts.size() >= remainingNodeCuts) {
				break;
			}
			if (Utility.compareLt(candidate.value, config.subsetRowCutMinimumThreshold)) {
				break;
			}
			if (!canUseScope(candidate.scope, appearances)) {
				continue;
			}
			cuts.add(buildCut(lp, solution, candidate.scope, candidate.rhs));
			increaseAppearances(candidate.scope, appearances);
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

	private boolean isSupportedPricingMode() {
		if (config.enableSubsetRowCutsForPartialDominance
				&& config.useGCNGBBStyleNgDssrPartialDominancePricing) {
			return true;
		}
		return isTimeIndexedRank1Mode();
	}

	private boolean isTimeIndexedRank1Mode() {
		return config.enableSubsetRowCutsForTimeIndexedGraph
				&& config.useTimeIndexedGraphPricing
				&& config.useTimeIndexedGraphRank1CutPricing;
	}

	private void addCandidateIfViolated(ArrayList<PositiveColumn> positiveColumns,
			ArrayList<Candidate> candidates, int[] scope) {
		double rhs = Math.floor(0.5 * scope.length + VALUE_TOLERANCE);
		double lhs = rank1Value(positiveColumns, scope);
		if (Utility.compareGt(lhs, rhs + VALUE_TOLERANCE)) {
			candidates.add(new Candidate(scope, lhs, rhs, scopeSignature(scope)));
		}
	}

	private int addPaperCuts(LP lp, TWETMasterSolution solution, ArrayList<Candidate> candidates,
			ArrayList<TWETCut> cuts, int[] appearances, HashSet<String> activeCuts,
			int remainingNewBases, int familyLimit) {
		int addedFamilyCuts = 0;
		int addedNewBases = 0;
		for (Candidate candidate : candidates) {
			if (addedFamilyCuts >= familyLimit) {
				break;
			}
			boolean activeBase = activeCuts.contains(candidate.signature);
			if (!activeBase && (addedNewBases >= remainingNewBases || !canUseScope(candidate.scope, appearances))) {
				continue;
			}
			TWETCut cut = mergeWithActivePaperMemory(lp, buildCut(lp, solution, candidate.scope, candidate.rhs));
			if (cut == null) {
				continue;
			}
			cuts.add(cut);
			if (!activeBase) {
				activeCuts.add(candidate.signature);
				increaseAppearances(candidate.scope, appearances);
				addedNewBases++;
			}
			addedFamilyCuts++;
		}
		return addedNewBases;
	}

	/**
	 * 预先合并当前节点同 base memory，并跳过 memory 没有变化的候选。
	 * 这样 no-op 候选不会占用论文每个 family 的分离额度，也不会让 cut loop 空转。
	 */
	private TWETCut mergeWithActivePaperMemory(LP lp, TWETCut candidate) {
		TWETCut merged = candidate;
		int matchingCount = 0;
		TWETCut soleActive = null;
		for (int cutId : lp.getActiveCutIds()) {
			TWETCut active = lp.getCutPool().getCut(cutId);
			if (!active.hasSameRank1Base(candidate)) {
				continue;
			}
			matchingCount++;
			soleActive = active;
			merged = merged.mergedMemoryWith(active);
		}
		if (matchingCount == 1 && merged.signature().equals(soleActive.signature())) {
			return null;
		}
		return merged;
	}

	private TWETCut buildCut(LP lp, TWETMasterSolution solution, int[] scope, double rhs) {
		ArrayList<Integer> scopeJobs = scopeList(scope);
		String description = scope.length == 1 ? "arcLmR1" : "arcLmSRI" + scope.length;
		if (isArcMemorySubsetRowCut() || isTimeIndexedRank1Mode()) {
			return new TWETCut(-1, TWETCutType.SUBSET_ROW, scopeJobs, null,
					buildLimitedMemoryArcSet(lp, solution, scope), 0.5, rhs, description);
		}
		if (isNodeMemorySubsetRowCut()) {
			return new TWETCut(-1, TWETCutType.SUBSET_ROW, scopeJobs,
					buildLimitedMemorySet(lp, solution, scope), 0.5, rhs, "lmSRI" + scope.length);
		}
		return new TWETCut(-1, TWETCutType.SUBSET_ROW, scopeJobs, rhs, "SRI" + scope.length);
	}

	private HashSet<String> activeTripleSignatures(LP lp) {
		HashSet<String> triples = new HashSet<String>();
		for (int cutId : lp.getActiveCutIds()) {
			TWETCut cut = lp.getCutPool().getCut(cutId);
			if (cut.getType() != TWETCutType.SUBSET_ROW || cut.getScopeJobs().size() != 3) {
				continue;
			}
			triples.add(scopeSignature(cut.getScopeJobs()));
		}
		return triples;
	}

	private HashSet<String> activeCutSignatures(LP lp) {
		HashSet<String> values = new HashSet<String>();
		for (int cutId : lp.getActiveCutIds()) {
			TWETCut cut = lp.getCutPool().getCut(cutId);
			if (cut.getType() == TWETCutType.SUBSET_ROW) {
				values.add(scopeSignature(cut.getScopeJobs()));
			}
		}
		return values;
	}

	private int[] activeSubsetRowAppearances(LP lp) {
		int[] appearances = new int[lp.getData().n + 1];
		HashSet<String> countedBases = new HashSet<String>();
		for (int cutId : lp.getActiveCutIds()) {
			TWETCut cut = lp.getCutPool().getCut(cutId);
			if (cut.getType() != TWETCutType.SUBSET_ROW) {
				continue;
			}
			String base = scopeSignature(cut.getScopeJobs());
			if (!countedBases.add(base)) {
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

	private double subsetRowValue(LP lp, TWETMasterSolution solution, int[] scope) {
		double value = 0.0;
		for (Map.Entry<Integer, Double> entry : solution.getColumnValues().entrySet()) {
			TWETColumn column = lp.getPool().getColumn(entry.getKey().intValue());
			int count = 0;
			for (int job : scope) {
				if (column.containsJob(job)) {
					count++;
				}
			}
			if (count >= 2) {
				value += entry.getValue().doubleValue();
			}
		}
		return value;
	}

	private ArrayList<PositiveColumn> positiveColumns(LP lp, TWETMasterSolution solution) {
		ArrayList<PositiveColumn> result = new ArrayList<PositiveColumn>(solution.getColumnValues().size());
		for (Map.Entry<Integer, Double> entry : solution.getColumnValues().entrySet()) {
			result.add(new PositiveColumn(lp.getPool().getColumn(entry.getKey().intValue()),
					entry.getValue().doubleValue()));
		}
		return result;
	}

	private double rank1Value(ArrayList<PositiveColumn> positiveColumns, int[] scope) {
		double value = 0.0;
		for (PositiveColumn positive : positiveColumns) {
			int coefficient = fullRank1Coefficient(positive.column, scope);
			if (coefficient > 0) {
				// 保持原 restricted-column 顺序累加，避免改变浮点同值候选的排序。
				value += coefficient * positive.value;
			}
		}
		return value;
	}

	private boolean isNodeMemorySubsetRowCut() {
		return "nodeMemory".equalsIgnoreCase(config.subsetRowCutMemoryMode)
				|| "lm".equalsIgnoreCase(config.subsetRowCutMemoryMode)
				|| "limitedMemory".equalsIgnoreCase(config.subsetRowCutMemoryMode);
	}

	private boolean isArcMemorySubsetRowCut() {
		return "arcMemory".equalsIgnoreCase(config.subsetRowCutMemoryMode)
				|| "arcLm".equalsIgnoreCase(config.subsetRowCutMemoryMode)
				|| "limitedArcMemory".equalsIgnoreCase(config.subsetRowCutMemoryMode);
	}

	private ArrayList<Integer> buildLimitedMemorySet(LP lp, TWETMasterSolution solution, int[] scope) {
		HashSet<Integer> memory = new HashSet<Integer>();
		for (int job : scope) {
			memory.add(Integer.valueOf(job));
		}
		for (Map.Entry<Integer, Double> entry : solution.getColumnValues().entrySet()) {
			if (!Utility.compareGt(entry.getValue().doubleValue(), VALUE_TOLERANCE)) {
				continue;
			}
			TWETColumn column = lp.getPool().getColumn(entry.getKey().intValue());
			if (!hasPositiveFullRank1Coefficient(column, scope)) {
				continue;
			}
			HashSet<Integer> aux = new HashSet<Integer>();
			int stateUnits = 0;
			for (int job : column.getSequence()) {
				if (stateUnits > 0 && job >= 1 && job <= lp.getData().n) {
					aux.add(Integer.valueOf(job));
				}
				if (isInScope(job, scope)) {
					stateUnits++;
				}
				if (stateUnits >= 2) {
					memory.addAll(aux);
					aux.clear();
					stateUnits -= 2;
				}
			}
		}
		ArrayList<Integer> result = new ArrayList<Integer>(memory);
		Collections.sort(result);
		return result;
	}

	private ArrayList<Long> buildLimitedMemoryArcSet(LP lp, TWETMasterSolution solution, int[] scope) {
		HashSet<Long> memory = new HashSet<Long>();
		for (Map.Entry<Integer, Double> entry : solution.getColumnValues().entrySet()) {
			if (!Utility.compareGt(entry.getValue().doubleValue(), VALUE_TOLERANCE)) {
				continue;
			}
			TWETColumn column = lp.getPool().getColumn(entry.getKey().intValue());
			if (!hasPositiveFullRank1Coefficient(column, scope)) {
				continue;
			}
			ArrayList<Long> part = new ArrayList<Long>();
			int stateUnits = 0;
			int previous = 0;
			boolean hasPrevious = false;
			for (int job : column.getSequence()) {
				if (hasPrevious && stateUnits > 0) {
					part.add(Long.valueOf(SubsetRowCutEvaluator.arcKey(previous, job)));
				}
				if (isInScope(job, scope)) {
					stateUnits++;
				}
				if (stateUnits >= 2) {
					memory.addAll(part);
					part.clear();
					stateUnits -= 2;
				}
				previous = job;
				hasPrevious = true;
			}
		}
		HashSet<Long> expanded = new HashSet<Long>(memory);
		for (Long encoded : memory) {
			long key = encoded.longValue();
			int from = (int) (key >> 32);
			int to = (int) key;
			expanded.add(Long.valueOf(SubsetRowCutEvaluator.arcKey(to, from)));
		}
		for (int from : scope) {
			for (int to : scope) {
				expanded.add(Long.valueOf(SubsetRowCutEvaluator.arcKey(from, to)));
			}
		}
		ArrayList<Long> result = new ArrayList<Long>(expanded);
		Collections.sort(result);
		return result;
	}

	private boolean hasPositiveFullRank1Coefficient(TWETColumn column, int[] scope) {
		return fullRank1Coefficient(column, scope) > 0;
	}

	/**
	 * full-memory、multiplier=1/2 的 rank-1 系数只取决于 scope 内总访问次数。
	 * 直接读取 ColumnPattern 缓存，避免为每个三元 scope 重建 cut 并扫描完整 sequence。
	 */
	static int fullRank1Coefficient(TWETColumn column, int[] scope) {
		int visits = 0;
		for (int job : scope) {
			visits += column.getJobVisitCount(job);
		}
		return visits / 2;
	}

	private boolean canUseScope(int[] scope, int[] appearances) {
		for (int job : scope) {
			if (job >= 1 && job < appearances.length && appearances[job] >= config.maxSubsetRowCutAppearancesPerJob) {
				return false;
			}
		}
		return true;
	}

	private void increaseAppearances(int[] scope, int[] appearances) {
		for (int job : scope) {
			if (job >= 1 && job < appearances.length) {
				appearances[job]++;
			}
		}
	}

	private boolean isInScope(int job, int[] scope) {
		for (int value : scope) {
			if (value == job) {
				return true;
			}
		}
		return false;
	}

	private ArrayList<Integer> scopeList(int[] scope) {
		ArrayList<Integer> jobs = new ArrayList<Integer>();
		for (int job : scope) {
			jobs.add(Integer.valueOf(job));
		}
		Collections.sort(jobs);
		return jobs;
	}

	private String scopeSignature(int[] scope) {
		return scopeSignature(scopeList(scope));
	}

	private String scopeSignature(List<Integer> scopeJobs) {
		ArrayList<Integer> jobs = new ArrayList<Integer>(scopeJobs);
		Collections.sort(jobs);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < jobs.size(); i++) {
			if (i > 0) {
				builder.append(',');
			}
			builder.append(jobs.get(i).intValue());
		}
		return builder.toString();
	}

	private void sortCandidates(ArrayList<Candidate> candidates) {
		Collections.sort(candidates, new Comparator<Candidate>() {
			@Override
			public int compare(Candidate a, Candidate b) {
				int valueCompare = Double.compare(b.value, a.value);
				if (valueCompare != 0) {
					return valueCompare;
				}
				if (a.scope.length != b.scope.length) {
					return Integer.compare(a.scope.length, b.scope.length);
				}
				return a.signature.compareTo(b.signature);
			}
		});
	}

	private static final class PositiveColumn {
		final TWETColumn column;
		final double value;

		PositiveColumn(TWETColumn column, double value) {
			this.column = column;
			this.value = value;
		}
	}

	private static final class Candidate {
		final int[] scope;
		final double value;
		final double rhs;
		final String signature;

		Candidate(int[] scope, double value, double rhs, String signature) {
			this.scope = scope;
			this.value = value;
			this.rhs = rhs;
			this.signature = signature;
		}
	}
}
