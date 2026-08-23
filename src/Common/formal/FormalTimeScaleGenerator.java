package Common.formal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 为每个任务集合生成逐任务配对的原始、中等和高时间倍率。 */
public final class FormalTimeScaleGenerator {
	private static final int MIN_DELTA = -5;
	private static final int MAX_DELTA = 5;
	private static final double MAX_WEIGHTED_MEAN_DEVIATION = 0.10;

	public Map<String, List<Scale>> generate(List<FormalTaskSet> taskSets) {
		LinkedHashMap<String, List<Scale>> result = new LinkedHashMap<String, List<Scale>>();
		for (FormalTaskSet taskSet : taskSets) {
			long seed = FormalTaskSetGenerator.mixSeed(FormalExperimentDesign.TIME_SCALE_SEED,
					taskSet.size(), taskSet.caseIndex(), taskSet.sourceFile().getFileName().toString().hashCode());
			int[] deltas = balancedDeltas(taskSet.size(), seed);
			improveProcessingWeightedBalance(taskSet, deltas);
			int[] base = new int[taskSet.size() + 1];
			int[] medium = new int[taskSet.size() + 1];
			int[] high = new int[taskSet.size() + 1];
			for (int job = 1; job <= taskSet.size(); job++) {
				base[job] = 1;
				medium[job] = 10 + deltas[job];
				high[job] = medium[job] + 10;
			}
			Scale baseScale = createScale(taskSet, "base", 1, seed, base);
			Scale mediumScale = createScale(taskSet, "medium", 10, seed, medium);
			Scale highScale = createScale(taskSet, "high", 20, seed, high);
			if (Math.abs(mediumScale.processingWeightedMultiplier() - 10.0)
					> MAX_WEIGHTED_MEAN_DEVIATION) {
				throw new IllegalStateException("Processing-weighted medium multiplier is not balanced for "
						+ taskSet.id() + ": " + mediumScale.processingWeightedMultiplier());
			}
			result.put(taskSet.id(), List.of(baseScale, mediumScale, highScale));
		}
		return result;
	}

	private static int[] balancedDeltas(int n, long seed) {
		ArrayList<Integer> values = new ArrayList<Integer>(n);
		int fullCycles = n / 11;
		for (int cycle = 0; cycle < fullCycles; cycle++) {
			for (int value = MIN_DELTA; value <= MAX_DELTA; value++) {
				values.add(Integer.valueOf(value));
			}
		}
		int remainder = n % 11;
		int radius = remainder / 2;
		if ((remainder & 1) == 1) {
			for (int value = -radius; value <= radius; value++) {
				values.add(Integer.valueOf(value));
			}
		} else {
			for (int value = -radius; value <= -1; value++) {
				values.add(Integer.valueOf(value));
			}
			for (int value = 1; value <= radius; value++) {
				values.add(Integer.valueOf(value));
			}
		}
		if (values.size() != n || values.stream().mapToInt(Integer::intValue).sum() != 0) {
			throw new IllegalStateException("Invalid balanced time-scale multiset for n=" + n);
		}
		Collections.shuffle(values, new Random(seed));
		int[] deltas = new int[n + 1];
		for (int job = 1; job <= n; job++) {
			deltas[job] = values.get(job - 1).intValue();
		}
		return deltas;
	}

	/** 在不改变倍率多重集的前提下交换任务，使processing加权均值尽量接近名义倍率。 */
	private static void improveProcessingWeightedBalance(FormalTaskSet taskSet, int[] deltas) {
		long offset = weightedOffset(taskSet, deltas);
		while (offset != 0L) {
			long bestAbsolute = Math.abs(offset);
			long bestOffset = offset;
			int bestFirst = -1;
			int bestSecond = -1;
			for (int first = 1; first <= taskSet.size(); first++) {
				long firstProcessing = taskSet.jobs().get(first - 1).processing();
				for (int second = first + 1; second <= taskSet.size(); second++) {
					if (deltas[first] == deltas[second]) {
						continue;
					}
					long secondProcessing = taskSet.jobs().get(second - 1).processing();
					long candidate = offset
							+ (firstProcessing - secondProcessing) * (deltas[second] - deltas[first]);
					long absolute = Math.abs(candidate);
					if (absolute < bestAbsolute) {
						bestAbsolute = absolute;
						bestOffset = candidate;
						bestFirst = first;
						bestSecond = second;
					}
				}
			}
			if (bestFirst < 0) {
				return;
			}
			int temporary = deltas[bestFirst];
			deltas[bestFirst] = deltas[bestSecond];
			deltas[bestSecond] = temporary;
			offset = bestOffset;
		}
	}

	private static long weightedOffset(FormalTaskSet taskSet, int[] deltas) {
		long result = 0L;
		for (int job = 1; job <= taskSet.size(); job++) {
			result += (long) taskSet.jobs().get(job - 1).processing() * deltas[job];
		}
		return result;
	}

	private static Scale createScale(FormalTaskSet taskSet, String level, int nominalMultiplier,
			long seed, int[] jobMultipliers) {
		long processing = 0L;
		long scaledProcessing = 0L;
		for (int job = 1; job <= taskSet.size(); job++) {
			int value = taskSet.jobs().get(job - 1).processing();
			processing += value;
			scaledProcessing += (long) value * jobMultipliers[job];
		}
		return new Scale(level, nominalMultiplier, seed, jobMultipliers,
				(double) scaledProcessing / processing, fingerprint(jobMultipliers));
	}

	public ScaledData scale(FormalTaskSet taskSet, int[] centers, int[][] setup, Scale scale) {
		int n = taskSet.size();
		if (scale.jobCount() != n) {
			throw new IllegalArgumentException("Scale/job mismatch: " + scale.level() + " n=" + n);
		}
		int[] processing = new int[n + 1];
		int[] scaledCenters = new int[n + 1];
		int[] earlyWeights = new int[n + 1];
		int[] tardyWeights = new int[n + 1];
		long baseProcessingTotal = 0L;
		long scaledProcessingTotal = 0L;
		for (int job = 1; job <= n; job++) {
			FormalTaskSet.Job source = taskSet.jobs().get(job - 1);
			int multiplier = scale.multiplierForJob(job);
			processing[job] = Math.multiplyExact(source.processing(), multiplier);
			scaledCenters[job] = Math.multiplyExact(centers[job], multiplier);
			earlyWeights[job] = source.earlyWeight();
			tardyWeights[job] = source.tardyWeight();
			baseProcessingTotal += source.processing();
			scaledProcessingTotal += processing[job];
		}
		double averageProcessing = (double) scaledProcessingTotal / n;
		double targetSetupMean = FormalExperimentDesign.SETUP_MEAN_RATIO * averageProcessing;
		int setupCap = Math.max(1, (int) Math.floor(averageProcessing));
		int[][] scaledSetup = scaleSetup(setup, scaledProcessingTotal, baseProcessingTotal, setupCap);
		return new ScaledData(processing, scaledCenters, earlyWeights, tardyWeights, scaledSetup,
				targetSetupMean, setupCap, (double) scaledProcessingTotal / baseProcessingTotal);
	}

	private static int[][] scaleSetup(int[][] setup, long scaledProcessingTotal,
			long baseProcessingTotal, int cap) {
		int n = setup.length - 1;
		int[][] result = new int[n + 1][n + 1];
		for (int from = 0; from <= n; from++) {
			for (int to = 0; to <= n; to++) {
				if (setup[from][to] == 0) {
					continue;
				}
				long numerator = Math.multiplyExact((long) setup[from][to], scaledProcessingTotal);
				long roundedUp = Math.floorDiv(Math.addExact(numerator, baseProcessingTotal - 1L),
						baseProcessingTotal);
				result[from][to] = Math.min(cap, Math.toIntExact(roundedUp));
			}
		}
		return result;
	}

	private static String fingerprint(int[] jobMultipliers) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (int job = 1; job < jobMultipliers.length; job++) {
				digest.update(Integer.toString(jobMultipliers[job]).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) ',');
			}
			StringBuilder value = new StringBuilder(64);
			for (byte item : digest.digest()) {
				value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
			}
			return value.toString();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	public record Scale(String level, int nominalMultiplier, long selectionSeed, int[] jobMultipliers,
			double processingWeightedMultiplier, String fingerprint) {
		public Scale {
			jobMultipliers = jobMultipliers.clone();
		}

		@Override
		public int[] jobMultipliers() {
			return jobMultipliers.clone();
		}

		public int jobCount() {
			return jobMultipliers.length - 1;
		}

		public int multiplierForJob(int job) {
			return jobMultipliers[job];
		}

		public double arithmeticMean() {
			long total = 0L;
			for (int job = 1; job < jobMultipliers.length; job++) {
				total += jobMultipliers[job];
			}
			return (double) total / jobCount();
		}

		public int minimumMultiplier() {
			int value = Integer.MAX_VALUE;
			for (int job = 1; job < jobMultipliers.length; job++) {
				value = Math.min(value, jobMultipliers[job]);
			}
			return value;
		}

		public int maximumMultiplier() {
			int value = Integer.MIN_VALUE;
			for (int job = 1; job < jobMultipliers.length; job++) {
				value = Math.max(value, jobMultipliers[job]);
			}
			return value;
		}

		public String multiplierValues() {
			StringBuilder value = new StringBuilder();
			for (int job = 1; job < jobMultipliers.length; job++) {
				if (job > 1) {
					value.append(',');
				}
				value.append(jobMultipliers[job]);
			}
			return value.toString();
		}

		public String directoryName() {
			return level.equals("base") ? "base" : level + "-heterogeneous";
		}
	}

	public record ScaledData(int[] processing, int[] centers, int[] earlyWeights,
			int[] tardyWeights, int[][] setup, double targetSetupMean, int setupCap,
			double workloadScale) {
	}
}
