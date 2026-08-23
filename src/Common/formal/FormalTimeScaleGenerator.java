package Common.formal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 为每个规模的5个任务集合生成配对的原始、中等和高时间倍率。 */
public final class FormalTimeScaleGenerator {

	public Map<String, List<Scale>> generate(List<FormalTaskSet> taskSets) {
		LinkedHashMap<Integer, ArrayList<FormalTaskSet>> bySize = new LinkedHashMap<Integer, ArrayList<FormalTaskSet>>();
		for (FormalTaskSet taskSet : taskSets) {
			bySize.computeIfAbsent(Integer.valueOf(taskSet.size()), ignored -> new ArrayList<FormalTaskSet>())
					.add(taskSet);
		}
		LinkedHashMap<String, List<Scale>> result = new LinkedHashMap<String, List<Scale>>();
		for (Map.Entry<Integer, ArrayList<FormalTaskSet>> entry : bySize.entrySet()) {
			ArrayList<FormalTaskSet> sets = entry.getValue();
			sets.sort(java.util.Comparator.comparingInt(FormalTaskSet::caseIndex));
			ArrayList<Integer> mediumCandidates = new ArrayList<Integer>();
			for (int value = 5; value <= 15; value++) {
				mediumCandidates.add(Integer.valueOf(value));
			}
			long seed = FormalTaskSetGenerator.mixSeed(FormalExperimentDesign.TIME_SCALE_SEED,
					entry.getKey().intValue(), 0, 0);
			Collections.shuffle(mediumCandidates, new Random(seed));
			if (sets.size() > mediumCandidates.size()) {
				throw new IllegalArgumentException("At most 11 task sets are supported per size");
			}
			for (int index = 0; index < sets.size(); index++) {
				int medium = mediumCandidates.get(index).intValue();
				result.put(sets.get(index).id(), List.of(
						new Scale("base", 1, seed),
						new Scale("medium", medium, seed),
						new Scale("high", medium + 10, seed)));
			}
		}
		return result;
	}

	public ScaledData scale(FormalTaskSet taskSet, int[] centers, int[][] setup, Scale scale) {
		int n = taskSet.size();
		int[] processing = new int[n + 1];
		int[] scaledCenters = new int[n + 1];
		int[] earlyWeights = new int[n + 1];
		int[] tardyWeights = new int[n + 1];
		for (int job = 1; job <= n; job++) {
			FormalTaskSet.Job source = taskSet.jobs().get(job - 1);
			processing[job] = Math.multiplyExact(source.processing(), scale.multiplier());
			scaledCenters[job] = Math.multiplyExact(centers[job], scale.multiplier());
			earlyWeights[job] = source.earlyWeight();
			tardyWeights[job] = source.tardyWeight();
		}
		int[][] scaledSetup = new int[n + 1][n + 1];
		for (int from = 0; from <= n; from++) {
			for (int to = 0; to <= n; to++) {
				scaledSetup[from][to] = Math.multiplyExact(setup[from][to], scale.multiplier());
			}
		}
		return new ScaledData(processing, scaledCenters, earlyWeights, tardyWeights, scaledSetup);
	}

	public record Scale(String level, int multiplier, long selectionSeed) {
	}

	public record ScaledData(int[] processing, int[] centers, int[] earlyWeights,
			int[] tardyWeights, int[][] setup) {
	}
}
