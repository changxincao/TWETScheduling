package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.SequenceSignature;

/**
 * 启发式定价诊断轨迹。只记录 seed 和实际采用的 best move，不记录 runner-up/non-best move。
 */
public final class HeuristicPricingDiagnosticTrace {

	private final long callId;
	private final ArrayList<State> visitedStates = new ArrayList<State>();
	private final HashMap<SequenceSignature, State> stateBySignature = new HashMap<SequenceSignature, State>();
	private final ArrayList<GeneratedColumn> generatedColumns = new ArrayList<GeneratedColumn>();

	HeuristicPricingDiagnosticTrace(long callId) {
		this.callId = callId;
	}

	void recordSeed(int seedOrdinal, List<Integer> sequence, double cost, double reducedCost) {
		recordState(new State(sequence, "SEED", seedOrdinal, -1, "-", cost, reducedCost,
				visitedStates.size()));
	}

	void recordBestMove(int seedOrdinal, int iteration, List<Integer> sequence, String move, double cost,
			double reducedCost) {
		recordState(new State(sequence, "BEST_MOVE", seedOrdinal, iteration, move, cost, reducedCost,
				visitedStates.size()));
	}

	private void recordState(State state) {
		visitedStates.add(state);
		SequenceSignature signature = new SequenceSignature(state.sequence);
		if (!stateBySignature.containsKey(signature)) {
			stateBySignature.put(signature, state);
		}
	}

	void recordGeneratedColumn(List<Integer> sequence, double cost, double reducedCost) {
		State origin = stateBySignature.get(new SequenceSignature(sequence));
		generatedColumns.add(new GeneratedColumn(sequence, cost, reducedCost, origin));
	}

	public long getCallId() {
		return callId;
	}

	public List<String> generatedColumnLines() {
		ArrayList<String> lines = new ArrayList<String>(generatedColumns.size());
		for (int i = 0; i < generatedColumns.size(); i++) {
			GeneratedColumn generated = generatedColumns.get(i);
			State origin = generated.origin;
			lines.add("call=" + callId + " generated rank=" + (i + 1)
					+ " origin=" + (origin == null ? "UNKNOWN" : origin.kind)
					+ " seed=" + (origin == null ? -1 : origin.seedOrdinal)
					+ " iter=" + (origin == null ? -1 : origin.iteration)
					+ " move=" + (origin == null ? "-" : origin.move)
					+ " rc=" + format(generated.reducedCost) + " cost=" + format(generated.cost)
					+ " len=" + generated.sequence.size() + " seq=" + generated.sequence);
		}
		return lines;
	}

	public String analyzeExactColumn(String exactEngineName, TWETColumn exactColumn, double exactReducedCost) {
		List<Integer> exact = exactColumn.getSequence();
		Nearest nearestVisited = nearest(exact, visitedStates);
		ArrayList<State> generatedStates = new ArrayList<State>(generatedColumns.size());
		for (GeneratedColumn generated : generatedColumns) {
			if (generated.origin != null) {
				generatedStates.add(generated.origin);
			}
		}
		Nearest nearestGenerated = nearest(exact, generatedStates);
		return "call=" + callId + " exactEngine=" + exactEngineName + " exactRc=" + format(exactReducedCost)
				+ " exactCost=" + format(exactColumn.getCost()) + " len=" + exact.size()
				+ " nearestVisited={" + describe(nearestVisited) + "}"
				+ " nearestGenerated={" + describe(nearestGenerated) + "}"
				+ " exactSeq=" + exact;
	}

	private static String describe(Nearest nearest) {
		if (nearest == null) {
			return "none";
		}
		State state = nearest.state;
		Relation relation = nearest.relation;
		return "relation=" + relation.kind + ",suggest=" + relation.suggestion
				+ ",editDistance=" + relation.editDistance + ",missing/extra=" + relation.missingJobs + "/"
				+ relation.extraJobs + ",setMoveLB=" + relation.setMoveLowerBound
				+ ",commonOrder=" + relation.commonOrder + ",commonReorder=" + relation.commonReorder
				+ ",extra/missingRuns=" + relation.extraRuns + "/" + relation.missingRuns
				+ ",lcs=" + relation.lcsLength + ",origin=" + state.kind
				+ ",seed=" + state.seedOrdinal + ",iter=" + state.iteration + ",move=" + state.move
				+ ",stateRc=" + format(state.reducedCost) + ",stateSeq=" + state.sequence;
	}

	private static Nearest nearest(List<Integer> exact, List<State> states) {
		State bestState = null;
		int bestEditDistance = Integer.MAX_VALUE;
		int bestLcsLength = -1;
		for (State state : states) {
			int editDistance = levenshteinDistance(state.sequence, exact);
			if (editDistance > bestEditDistance) {
				continue;
			}
			int lcsLength = lcsLength(state.sequence, exact);
			if (editDistance < bestEditDistance || lcsLength > bestLcsLength) {
				bestState = state;
				bestEditDistance = editDistance;
				bestLcsLength = lcsLength;
			}
		}
		return bestState == null ? null : new Nearest(bestState, compare(bestState.sequence, exact));
	}

	static Relation compare(List<Integer> from, List<Integer> to) {
		int missing = setDifferenceSize(to, from);
		int extra = setDifferenceSize(from, to);
		int lcs = lcsLength(from, to);
		int editDistance = levenshteinDistance(from, to);
		ProjectionInfo projection = projectionInfo(from, to);
		if (from.equals(to)) {
			return relation("SAME", "visited_sequence_check_cost_or_duplicate", editDistance, missing, extra, lcs,
					projection);
		}
		if (isOneAdd(from, to)) {
			return relation("ONE_ADD", "existing_ADD_but_not_selected_or_tabu", editDistance, missing, extra, lcs,
					projection);
		}
		if (isOneRemove(from, to)) {
			return relation("ONE_REMOVE", "existing_REMOVE_but_not_selected_or_tabu", editDistance, missing, extra,
					lcs, projection);
		}
		if (isOneExchange(from, to)) {
			return relation("ONE_EXCHANGE", "existing_EXCHANGE_but_not_selected_or_tabu", editDistance, missing,
					extra, lcs, projection);
		}
		if (sameJobSet(from, to) && isOneRelocate(from, to)) {
			return relation("ONE_RELOCATE", "add_RELOCATE_move", editDistance, missing, extra, lcs, projection);
		}
		if (sameJobSet(from, to) && isOneSwap(from, to)) {
			return relation("ONE_SWAP", "add_SWAP_move", editDistance, missing, extra, lcs, projection);
		}
		String suggestion;
		if (projection.commonOrder && projection.extraRuns <= 1 && projection.missingRuns <= 1) {
			suggestion = "add_CONTIGUOUS_PATH_EXCHANGE";
		} else if ("ONE_RELOCATE".equals(projection.commonReorder)) {
			suggestion = "add_RELOCATE_plus_multi_job_exchange_or_beam";
		} else if (projection.commonOrder) {
			suggestion = "multi_position_ejection_chain_or_beam";
		} else {
			suggestion = "multi_step_reorder_and_set_change_or_beam";
		}
		return relation("MULTI_STEP", suggestion, editDistance, missing, extra, lcs, projection);
	}

	private static Relation relation(String kind, String suggestion, int editDistance, int missing, int extra,
			int lcs, ProjectionInfo projection) {
		return new Relation(kind, suggestion, editDistance, missing, extra, lcs, Math.max(missing, extra),
				projection.commonOrder, projection.commonReorder, projection.extraRuns, projection.missingRuns);
	}

	private static ProjectionInfo projectionInfo(List<Integer> from, List<Integer> to) {
		HashSet<Integer> fromJobs = new HashSet<Integer>(from);
		HashSet<Integer> toJobs = new HashSet<Integer>(to);
		ArrayList<Integer> commonFrom = filterToSet(from, toJobs);
		ArrayList<Integer> commonTo = filterToSet(to, fromJobs);
		boolean commonOrder = commonFrom.equals(commonTo);
		String commonReorder = commonOrder ? "NONE"
				: isOneRelocate(commonFrom, commonTo) ? "ONE_RELOCATE"
						: isOneSwap(commonFrom, commonTo) ? "ONE_SWAP" : "MULTI_REORDER";
		HashSet<Integer> commonJobs = new HashSet<Integer>(commonFrom);
		return new ProjectionInfo(commonOrder, commonReorder, gapRuns(from, commonJobs), gapRuns(to, commonJobs));
	}

	private static ArrayList<Integer> filterToSet(List<Integer> sequence, HashSet<Integer> allowed) {
		ArrayList<Integer> result = new ArrayList<Integer>();
		for (Integer job : sequence) {
			if (allowed.contains(job)) {
				result.add(job);
			}
		}
		return result;
	}

	private static int gapRuns(List<Integer> sequence, HashSet<Integer> commonJobs) {
		int runs = 0;
		boolean insideGap = false;
		for (Integer job : sequence) {
			if (commonJobs.contains(job)) {
				insideGap = false;
			} else if (!insideGap) {
				runs++;
				insideGap = true;
			}
		}
		return runs;
	}

	private static boolean isOneAdd(List<Integer> from, List<Integer> to) {
		return to.size() == from.size() + 1 && isSubsequenceAfterDeletingOne(to, from);
	}

	private static boolean isOneRemove(List<Integer> from, List<Integer> to) {
		return from.size() == to.size() + 1 && isSubsequenceAfterDeletingOne(from, to);
	}

	private static boolean isSubsequenceAfterDeletingOne(List<Integer> longer, List<Integer> shorter) {
		for (int skip = 0; skip < longer.size(); skip++) {
			int target = 0;
			boolean matched = true;
			for (int i = 0; i < longer.size(); i++) {
				if (i == skip) {
					continue;
				}
				if (!longer.get(i).equals(shorter.get(target++))) {
					matched = false;
					break;
				}
			}
			if (matched && target == shorter.size()) {
				return true;
			}
		}
		return false;
	}

	private static boolean isOneExchange(List<Integer> from, List<Integer> to) {
		if (from.size() != to.size()) {
			return false;
		}
		int differences = 0;
		for (int i = 0; i < from.size(); i++) {
			if (!from.get(i).equals(to.get(i)) && ++differences > 1) {
				return false;
			}
		}
		return differences == 1;
	}

	private static boolean isOneRelocate(List<Integer> from, List<Integer> to) {
		for (int remove = 0; remove < from.size(); remove++) {
			ArrayList<Integer> reduced = new ArrayList<Integer>(from);
			Integer job = reduced.remove(remove);
			for (int insert = 0; insert <= reduced.size(); insert++) {
				ArrayList<Integer> candidate = new ArrayList<Integer>(reduced);
				candidate.add(insert, job);
				if (candidate.equals(to)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isOneSwap(List<Integer> from, List<Integer> to) {
		if (from.size() != to.size()) {
			return false;
		}
		ArrayList<Integer> differences = new ArrayList<Integer>(2);
		for (int i = 0; i < from.size(); i++) {
			if (!from.get(i).equals(to.get(i))) {
				differences.add(Integer.valueOf(i));
				if (differences.size() > 2) {
					return false;
				}
			}
		}
		if (differences.size() != 2) {
			return false;
		}
		int first = differences.get(0).intValue();
		int second = differences.get(1).intValue();
		return from.get(first).equals(to.get(second)) && from.get(second).equals(to.get(first));
	}

	private static boolean sameJobSet(List<Integer> first, List<Integer> second) {
		return first.size() == second.size() && new HashSet<Integer>(first).equals(new HashSet<Integer>(second));
	}

	private static int setDifferenceSize(List<Integer> first, List<Integer> second) {
		HashSet<Integer> remaining = new HashSet<Integer>(first);
		remaining.removeAll(new HashSet<Integer>(second));
		return remaining.size();
	}

	private static int lcsLength(List<Integer> first, List<Integer> second) {
		int[] previous = new int[second.size() + 1];
		int[] current = new int[second.size() + 1];
		for (int i = 1; i <= first.size(); i++) {
			for (int j = 1; j <= second.size(); j++) {
				current[j] = first.get(i - 1).equals(second.get(j - 1)) ? previous[j - 1] + 1
						: Math.max(previous[j], current[j - 1]);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
			java.util.Arrays.fill(current, 0);
		}
		return previous[second.size()];
	}

	private static int levenshteinDistance(List<Integer> first, List<Integer> second) {
		int[] previous = new int[second.size() + 1];
		int[] current = new int[second.size() + 1];
		for (int j = 0; j <= second.size(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= first.size(); i++) {
			current[0] = i;
			for (int j = 1; j <= second.size(); j++) {
				int substitute = previous[j - 1] + (first.get(i - 1).equals(second.get(j - 1)) ? 0 : 1);
				current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[second.size()];
	}

	private static String format(double value) {
		return Double.isFinite(value) ? String.format(Locale.US, "%.6f", value) : Double.toString(value);
	}

	static final class Relation {
		final String kind;
		final String suggestion;
		final int editDistance;
		final int missingJobs;
		final int extraJobs;
		final int lcsLength;
		final int setMoveLowerBound;
		final boolean commonOrder;
		final String commonReorder;
		final int extraRuns;
		final int missingRuns;

		Relation(String kind, String suggestion, int editDistance, int missingJobs, int extraJobs, int lcsLength,
				int setMoveLowerBound, boolean commonOrder, String commonReorder, int extraRuns, int missingRuns) {
			this.kind = kind;
			this.suggestion = suggestion;
			this.editDistance = editDistance;
			this.missingJobs = missingJobs;
			this.extraJobs = extraJobs;
			this.lcsLength = lcsLength;
			this.setMoveLowerBound = setMoveLowerBound;
			this.commonOrder = commonOrder;
			this.commonReorder = commonReorder;
			this.extraRuns = extraRuns;
			this.missingRuns = missingRuns;
		}
	}

	private static final class ProjectionInfo {
		final boolean commonOrder;
		final String commonReorder;
		final int extraRuns;
		final int missingRuns;

		ProjectionInfo(boolean commonOrder, String commonReorder, int extraRuns, int missingRuns) {
			this.commonOrder = commonOrder;
			this.commonReorder = commonReorder;
			this.extraRuns = extraRuns;
			this.missingRuns = missingRuns;
		}
	}

	private static final class State {
		final ArrayList<Integer> sequence;
		final String kind;
		final int seedOrdinal;
		final int iteration;
		final String move;
		final double cost;
		final double reducedCost;
		final int order;

		State(List<Integer> sequence, String kind, int seedOrdinal, int iteration, String move, double cost,
				double reducedCost, int order) {
			this.sequence = new ArrayList<Integer>(sequence);
			this.kind = kind;
			this.seedOrdinal = seedOrdinal;
			this.iteration = iteration;
			this.move = move;
			this.cost = cost;
			this.reducedCost = reducedCost;
			this.order = order;
		}
	}

	private static final class GeneratedColumn {
		final ArrayList<Integer> sequence;
		final double cost;
		final double reducedCost;
		final State origin;

		GeneratedColumn(List<Integer> sequence, double cost, double reducedCost, State origin) {
			this.sequence = new ArrayList<Integer>(sequence);
			this.cost = cost;
			this.reducedCost = reducedCost;
			this.origin = origin;
		}
	}

	private static final class Nearest {
		final State state;
		final Relation relation;

		Nearest(State state, Relation relation) {
			this.state = state;
			this.relation = relation;
		}
	}
}
