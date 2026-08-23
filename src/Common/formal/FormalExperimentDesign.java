package Common.formal;

import java.util.Arrays;

/** 正式实验中冻结的数据规模、机器档和经济参数。 */
public final class FormalExperimentDesign {
	public static final int[] TASK_SIZES = new int[] { 20, 40, 50, 60, 80, 100 };
	public static final int CASES_PER_SIZE = 5;
	public static final int[] WINDOW_HALF_WIDTHS = new int[] { 0, 100, 300 };
	public static final double SETUP_MEAN_RATIO = 0.5;
	public static final double FAMILY_WITHIN_MEAN_RATIO = 0.4;
	public static final double SETUP_COST_COEFFICIENT = 20.0;
	public static final double[] OUTSOURCING_RATES = new double[] { 0.5, 1.0, 2.0 };
	public static final double DEFAULT_DISCOUNT_STRENGTH = 0.15;

	public static final long SOURCE_SELECTION_SEED = 2026082301L;
	public static final long TASK_PERMUTATION_SEED = 2026082302L;
	public static final long FAMILY_ASSIGNMENT_SEED = 2026082303L;
	public static final long SETUP_QUANTILE_SEED = 2026082304L;
	public static final long TIME_SCALE_SEED = 2026082305L;

	private FormalExperimentDesign() {
	}

	public static int parentSize(int taskSize) {
		if (taskSize == 20 || taskSize == 40) {
			return 40;
		}
		if (taskSize == 50) {
			return 50;
		}
		if (taskSize == 60 || taskSize == 80 || taskSize == 100) {
			return 100;
		}
		throw new IllegalArgumentException("Unsupported formal task size: " + taskSize);
	}

	public static int[] machines(int taskSize) {
		return taskSize <= 60 ? new int[] { 2, 3, 4 } : new int[] { 3, 4, 5 };
	}

	public static int referenceMachines(int taskSize) {
		return taskSize <= 60 ? 3 : 4;
	}

	public static int familyCount(int taskSize) {
		return switch (taskSize) {
		case 20, 40 -> 3;
		case 50, 60 -> 4;
		case 80 -> 5;
		case 100 -> 6;
		default -> throw new IllegalArgumentException("Unsupported formal task size: " + taskSize);
		};
	}

	public static void validateSizes(int[] sizes) {
		for (int size : sizes) {
			if (Arrays.stream(TASK_SIZES).noneMatch(candidate -> candidate == size)) {
				throw new IllegalArgumentException("Unsupported formal task size: " + size);
			}
		}
	}
}
