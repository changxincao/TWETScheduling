package Common.formal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import Common.formal.FormalSetupValidator.Report;

/** 生成配对的random/family setup，并在连续闭包后校准最终整数均值。 */
public final class FormalSetupGenerator {
	private static final double SQRT_TWO_PI = Math.sqrt(2.0 * Math.PI);
	private static final double MIN_PROBABILITY = 1e-12;
	private final FormalSetupValidator validator;

	public FormalSetupGenerator(FormalSetupValidator validator) {
		this.validator = validator;
	}

	public List<Result> generatePair(FormalTaskSet taskSet) {
		int n = taskSet.size();
		double averageProcessing = taskSet.averageProcessing();
		double targetMean = FormalExperimentDesign.SETUP_MEAN_RATIO * averageProcessing;
		int cap = Math.max(1, (int) Math.floor(averageProcessing));
		int[] familyByJob = assignFamilies(taskSet);
		double withinPairRatio = withinPairRatio(familyByJob);
		double withinTarget = FormalExperimentDesign.FAMILY_WITHIN_MEAN_RATIO * targetMean;
		double betweenTarget = (targetMean - withinPairRatio * withinTarget) / (1.0 - withinPairRatio);

		double randomDeviation = targetMean / 2.0;
		double withinDeviation = withinTarget / 2.0;
		double betweenDeviation = betweenTarget / 2.0;
		double randomLocation = calibratedLocation(targetMean, randomDeviation, cap);
		double withinLocation = calibratedLocation(withinTarget, withinDeviation, cap);
		double betweenLocation = calibratedLocation(betweenTarget, betweenDeviation, cap);

		double[][] quantiles = pairedQuantiles(taskSet);
		double[][] randomRaw = new double[n + 1][n + 1];
		double[][] familyRaw = new double[n + 1][n + 1];
		for (int from = 0; from <= n; from++) {
			for (int to = 1; to <= n; to++) {
				if (from == to) {
					continue;
				}
				double u = quantiles[from][to];
				double randomValue = sampleTruncated(u, randomLocation, randomDeviation, cap);
				randomRaw[from][to] = randomValue;
				if (from == 0) {
					familyRaw[from][to] = randomValue;
				} else if (familyByJob[from] == familyByJob[to]) {
					familyRaw[from][to] = sampleTruncated(u, withinLocation, withinDeviation, cap);
				} else {
					familyRaw[from][to] = sampleTruncated(u, betweenLocation, betweenDeviation, cap);
				}
			}
		}

		Result randomResult = finish(Type.RANDOM, randomRaw, null, targetMean, cap,
				randomLocation, Double.NaN, Double.NaN, withinPairRatio);
		Result familyResult = finish(Type.FAMILY, familyRaw, familyByJob, targetMean, cap,
				randomLocation, withinLocation, betweenLocation, withinPairRatio);
		return List.of(randomResult, familyResult);
	}

	private Result finish(Type type, double[][] raw, int[] familyByJob, double targetMean, int cap,
			double randomLocation, double withinLocation, double betweenLocation, double withinPairRatio) {
		double rawMean = meanJobArcs(raw);
		int firstFloydChangedArcs = closeInPlace(raw);
		double closedMean = meanJobArcs(raw);
		double calibrationScale = calibrateScale(raw, targetMean, cap);
		int[][] setup = integerize(raw, calibrationScale, cap);
		Report report = validator.audit(setup, targetMean, cap, familyByJob);
		validator.requirePreset(report, type == Type.FAMILY);
		return new Result(type, setup, familyByJob == null ? null : familyByJob.clone(), targetMean, cap,
				rawMean, closedMean, firstFloydChangedArcs, calibrationScale, randomLocation,
				withinLocation, betweenLocation, withinPairRatio, report);
	}

	private static int[] assignFamilies(FormalTaskSet taskSet) {
		int n = taskSet.size();
		int familyCount = FormalExperimentDesign.familyCount(n);
		ArrayList<Integer> jobs = new ArrayList<Integer>(n);
		for (int job = 1; job <= n; job++) {
			jobs.add(Integer.valueOf(job));
		}
		long seed = FormalTaskSetGenerator.mixSeed(FormalExperimentDesign.FAMILY_ASSIGNMENT_SEED,
				n, taskSet.caseIndex(), taskSet.sourceFile().getFileName().toString().hashCode());
		Collections.shuffle(jobs, new Random(seed));
		int[] familyByJob = new int[n + 1];
		for (int index = 0; index < jobs.size(); index++) {
			familyByJob[jobs.get(index).intValue()] = index % familyCount;
		}
		return familyByJob;
	}

	private static double withinPairRatio(int[] familyByJob) {
		int n = familyByJob.length - 1;
		int familyCount = 0;
		for (int job = 1; job <= n; job++) {
			familyCount = Math.max(familyCount, familyByJob[job] + 1);
		}
		int[] sizes = new int[familyCount];
		for (int job = 1; job <= n; job++) {
			sizes[familyByJob[job]]++;
		}
		long withinPairs = 0L;
		for (int size : sizes) {
			withinPairs += (long) size * (size - 1L);
		}
		return (double) withinPairs / ((long) n * (n - 1L));
	}

	private static double[][] pairedQuantiles(FormalTaskSet taskSet) {
		int n = taskSet.size();
		long seed = FormalTaskSetGenerator.mixSeed(FormalExperimentDesign.SETUP_QUANTILE_SEED,
				n, taskSet.caseIndex(), taskSet.sourceFile().getFileName().toString().hashCode());
		Random random = new Random(seed);
		double[][] quantiles = new double[n + 1][n + 1];
		for (int from = 0; from <= n; from++) {
			for (int to = 1; to <= n; to++) {
				if (from != to) {
					quantiles[from][to] = Math.max(MIN_PROBABILITY,
							Math.min(1.0 - MIN_PROBABILITY, random.nextDouble()));
				}
			}
		}
		return quantiles;
	}

	private static double calibratedLocation(double targetMean, double deviation, double cap) {
		if (deviation <= 0.0) {
			return targetMean;
		}
		double low = -4.0 * cap;
		double high = 5.0 * cap;
		for (int iteration = 0; iteration < 100; iteration++) {
			double middle = 0.5 * (low + high);
			if (truncatedMean(middle, deviation, cap) < targetMean) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return 0.5 * (low + high);
	}

	private static double truncatedMean(double location, double deviation, double cap) {
		double alpha = -location / deviation;
		double beta = (cap - location) / deviation;
		double denominator = normalCdf(beta) - normalCdf(alpha);
		if (denominator <= 1e-15) {
			return location < 0.0 ? 0.0 : cap;
		}
		return location + deviation * (normalDensity(alpha) - normalDensity(beta)) / denominator;
	}

	private static double sampleTruncated(double unitQuantile, double location, double deviation, double cap) {
		if (deviation <= 0.0) {
			return Math.max(0.0, Math.min(cap, location));
		}
		double alphaProbability = normalCdf(-location / deviation);
		double betaProbability = normalCdf((cap - location) / deviation);
		double probability = alphaProbability + unitQuantile * (betaProbability - alphaProbability);
		probability = Math.max(MIN_PROBABILITY, Math.min(1.0 - MIN_PROBABILITY, probability));
		return Math.max(0.0, Math.min(cap, location + deviation * inverseNormalCdf(probability)));
	}

	private static int closeInPlace(double[][] setup) {
		int n = setup.length - 1;
		double[][] before = new double[n + 1][];
		for (int row = 0; row <= n; row++) {
			before[row] = setup[row].clone();
		}
		for (int middle = 1; middle <= n; middle++) {
			for (int from = 0; from <= n; from++) {
				if (from == middle) {
					continue;
				}
				for (int to = 1; to <= n; to++) {
					if (from != to && to != middle) {
						setup[from][to] = Math.min(setup[from][to], setup[from][middle] + setup[middle][to]);
					}
				}
			}
		}
		int changed = 0;
		for (int from = 0; from <= n; from++) {
			for (int to = 1; to <= n; to++) {
				if (from != to && setup[from][to] + 1e-10 < before[from][to]) {
					changed++;
				}
			}
		}
		return changed;
	}

	private static double calibrateScale(double[][] closed, double targetMean, int cap) {
		double low = 0.0;
		double high = 1.0;
		while (integerMean(closed, high, cap) < targetMean && high < 1e9) {
			high *= 2.0;
		}
		double bestScale = high;
		double bestError = Math.abs(integerMean(closed, high, cap) - targetMean);
		for (int iteration = 0; iteration < 100; iteration++) {
			double middle = 0.5 * (low + high);
			double mean = integerMean(closed, middle, cap);
			double error = Math.abs(mean - targetMean);
			if (error < bestError) {
				bestError = error;
				bestScale = middle;
			}
			if (mean < targetMean) {
				low = middle;
			} else {
				high = middle;
			}
		}
		for (double candidate : new double[] { low, high, 0.5 * (low + high) }) {
			double error = Math.abs(integerMean(closed, candidate, cap) - targetMean);
			if (error < bestError) {
				bestError = error;
				bestScale = candidate;
			}
		}
		return bestScale;
	}

	private static double integerMean(double[][] setup, double scale, int cap) {
		return meanJobArcs(integerize(setup, scale, cap));
	}

	private static int[][] integerize(double[][] setup, double scale, int cap) {
		int n = setup.length - 1;
		int[][] result = new int[n + 1][n + 1];
		for (int from = 0; from <= n; from++) {
			for (int to = 1; to <= n; to++) {
				if (from != to) {
					double value = Math.min(scale * setup[from][to], cap);
					result[from][to] = (int) Math.ceil(Math.max(0.0, value - 1e-10));
				}
			}
		}
		return result;
	}

	private static double meanJobArcs(double[][] setup) {
		int n = setup.length - 1;
		double total = 0.0;
		for (int from = 1; from <= n; from++) {
			for (int to = 1; to <= n; to++) {
				if (from != to) {
					total += setup[from][to];
				}
			}
		}
		return total / ((long) n * (n - 1L));
	}

	private static double meanJobArcs(int[][] setup) {
		int n = setup.length - 1;
		long total = 0L;
		for (int from = 1; from <= n; from++) {
			for (int to = 1; to <= n; to++) {
				if (from != to) {
					total += setup[from][to];
				}
			}
		}
		return (double) total / ((long) n * (n - 1L));
	}

	private static double normalDensity(double value) {
		return Math.exp(-0.5 * value * value) / SQRT_TWO_PI;
	}

	/** Abramowitz-Stegun 7.1.26，足以支持数据生成中的截断分布校准。 */
	private static double normalCdf(double value) {
		double sign = value < 0.0 ? -1.0 : 1.0;
		double x = Math.abs(value) / Math.sqrt(2.0);
		double t = 1.0 / (1.0 + 0.3275911 * x);
		double polynomial = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
				- 0.284496736) * t + 0.254829592) * t;
		double erf = sign * (1.0 - polynomial * Math.exp(-x * x));
		return 0.5 * (1.0 + erf);
	}

	/** Peter J. Acklam的标准正态逆CDF有理近似。 */
	private static double inverseNormalCdf(double probability) {
		double[] a = { -39.69683028665376, 220.9460984245205, -275.9285104469687,
				138.3577518672690, -30.66479806614716, 2.506628277459239 };
		double[] b = { -54.47609879822406, 161.5858368580409, -155.6989798598866,
				66.80131188771972, -13.28068155288572 };
		double[] c = { -0.007784894002430293, -0.3223964580411365, -2.400758277161838,
				-2.549732539343734, 4.374664141464968, 2.938163982698783 };
		double[] d = { 0.007784695709041462, 0.3224671290700398, 2.445134137142996,
				3.754408661907416 };
		if (probability < 0.02425) {
			double q = Math.sqrt(-2.0 * Math.log(probability));
			return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
					/ ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
		}
		if (probability > 1.0 - 0.02425) {
			double q = Math.sqrt(-2.0 * Math.log(1.0 - probability));
			return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
					/ ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
		}
		double q = probability - 0.5;
		double r = q * q;
		return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
				/ (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
	}

	public enum Type {
		RANDOM("random"), FAMILY("family");

		private final String id;

		Type(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}
	}

	public record Result(Type type, int[][] setup, int[] familyByJob, double targetMean, int cap,
			double rawMean, double closedMean, int firstFloydChangedArcs, double calibrationScale,
			double randomLocation, double withinLocation, double betweenLocation,
			double withinPairRatio, Report audit) {
	}
}
