package Common.formal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 为每个任务集合生成彼此独立的 processing 和 due-center 时间倍率。 */
public final class FormalTimeScaleGenerator {

	public Map<String, List<Scale>> generate(List<FormalTaskSet> taskSets) {
		LinkedHashMap<String, List<Scale>> result = new LinkedHashMap<String, List<Scale>>();
		for (FormalTaskSet taskSet : taskSets) {
			int[] base = constantMultipliers(taskSet.size(), 1);
			Scale baseScale = createScale(taskSet, "base", 1, 0L, 0L, base, base);
			Scale mediumScale = randomScale(taskSet, "medium", 10, 5, 15, 0x4d454449L);
			Scale highScale = randomScale(taskSet, "high", 20, 15, 25, 0x48494748L);
			result.put(taskSet.id(), List.of(baseScale, mediumScale, highScale));
		}
		return result;
	}

	private static Scale randomScale(FormalTaskSet taskSet, String level, int nominalMultiplier,
			int minimum, int maximum, long levelSalt) {
		long processingSeed = FormalTaskSetGenerator.mixSeed(FormalExperimentDesign.TIME_SCALE_SEED,
				taskSet.size(), taskSet.caseIndex(), (int) levelSalt);
		long centerSeed = FormalTaskSetGenerator.mixSeed(FormalExperimentDesign.TIME_SCALE_SEED,
				taskSet.size(), taskSet.caseIndex(), (int) (levelSalt ^ 0x43454e54L));
		int[] processing = randomMultipliers(taskSet.size(), minimum, maximum, processingSeed);
		int[] centers = randomMultipliers(taskSet.size(), minimum, maximum, centerSeed);
		return createScale(taskSet, level, nominalMultiplier, processingSeed, centerSeed,
				processing, centers);
	}

	private static int[] constantMultipliers(int n, int value) {
		int[] result = new int[n + 1];
		for (int job = 1; job <= n; job++) {
			result[job] = value;
		}
		return result;
	}

	private static int[] randomMultipliers(int n, int minimum, int maximum, long seed) {
		Random random = new Random(seed);
		int[] result = new int[n + 1];
		for (int job = 1; job <= n; job++) {
			result[job] = minimum + random.nextInt(maximum - minimum + 1);
		}
		return result;
	}

	private static Scale createScale(FormalTaskSet taskSet, String level, int nominalMultiplier,
			long processingSeed, long centerSeed, int[] processingMultipliers,
			int[] centerMultipliers) {
		long processing = 0L;
		long scaledProcessing = 0L;
		for (int job = 1; job <= taskSet.size(); job++) {
			int value = taskSet.jobs().get(job - 1).processing();
			processing += value;
			scaledProcessing += (long) value * processingMultipliers[job];
		}
		return new Scale(level, nominalMultiplier, processingSeed, centerSeed,
				processingMultipliers, centerMultipliers,
				(double) scaledProcessing / processing,
				fingerprint(processingMultipliers), fingerprint(centerMultipliers));
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
			processing[job] = Math.multiplyExact(source.processing(), scale.processingMultiplier(job));
			scaledCenters[job] = Math.multiplyExact(centers[job], scale.centerMultiplier(job));
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

	static String fingerprint(int[] values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (int job = 1; job < values.length; job++) {
				digest.update(Integer.toString(values[job]).getBytes(StandardCharsets.UTF_8));
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

	public record Scale(String level, int nominalMultiplier, long processingSeed, long centerSeed,
			int[] processingMultipliers, int[] centerMultipliers,
			double processingWeightedMultiplier, String processingFingerprint,
			String centerFingerprint) {
		public Scale {
			processingMultipliers = processingMultipliers.clone();
			centerMultipliers = centerMultipliers.clone();
		}

		@Override
		public int[] processingMultipliers() {
			return processingMultipliers.clone();
		}

		@Override
		public int[] centerMultipliers() {
			return centerMultipliers.clone();
		}

		public int jobCount() {
			return processingMultipliers.length - 1;
		}

		public int processingMultiplier(int job) {
			return processingMultipliers[job];
		}

		public int centerMultiplier(int job) {
			return centerMultipliers[job];
		}

		public double processingArithmeticMean() {
			return arithmeticMean(processingMultipliers);
		}

		public double centerArithmeticMean() {
			return arithmeticMean(centerMultipliers);
		}

		public int processingMinimum() {
			return minimum(processingMultipliers);
		}

		public int processingMaximum() {
			return maximum(processingMultipliers);
		}

		public int centerMinimum() {
			return minimum(centerMultipliers);
		}

		public int centerMaximum() {
			return maximum(centerMultipliers);
		}

		public String processingValues() {
			return values(processingMultipliers);
		}

		public String centerValues() {
			return values(centerMultipliers);
		}

		public String directoryName() {
			return level.equals("base") ? "base" : level + "-heterogeneous";
		}

		private static double arithmeticMean(int[] values) {
			long total = 0L;
			for (int job = 1; job < values.length; job++) {
				total += values[job];
			}
			return (double) total / (values.length - 1);
		}

		private static int minimum(int[] values) {
			int result = Integer.MAX_VALUE;
			for (int job = 1; job < values.length; job++) {
				result = Math.min(result, values[job]);
			}
			return result;
		}

		private static int maximum(int[] values) {
			int result = Integer.MIN_VALUE;
			for (int job = 1; job < values.length; job++) {
				result = Math.max(result, values[job]);
			}
			return result;
		}

		private static String values(int[] values) {
			StringBuilder result = new StringBuilder();
			for (int job = 1; job < values.length; job++) {
				if (job > 1) {
					result.append(',');
				}
				result.append(values[job]);
			}
			return result.toString();
		}
	}

	public record ScaledData(int[] processing, int[] centers, int[] earlyWeights,
			int[] tardyWeights, int[][] setup, double targetSetupMean, int setupCap,
			double workloadScale) {
	}
}
