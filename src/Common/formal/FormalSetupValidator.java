package Common.formal;

/** 独立审计最终setup矩阵是否满足正式实验的强度、上限和有向三角要求。 */
public final class FormalSetupValidator {

	public Report audit(int[][] setup, double targetMean, int cap, int[] familyByJob) {
		int n = setup.length - 1;
		long count = 0L;
		double total = 0.0;
		int max = 0;
		int capHits = 0;
		int negative = 0;
		int diagonalErrors = 0;
		int terminalErrors = 0;
		double withinTotal = 0.0;
		double betweenTotal = 0.0;
		long withinCount = 0L;
		long betweenCount = 0L;
		for (int from = 0; from <= n; from++) {
			if (setup[from][0] != 0) {
				terminalErrors++;
			}
			for (int to = 0; to <= n; to++) {
				int value = setup[from][to];
				if (value < 0) {
					negative++;
				}
				max = Math.max(max, value);
				if (from == to && value != 0) {
					diagonalErrors++;
				}
				if (from == 0 || to == 0 || from == to) {
					continue;
				}
				total += value;
				count++;
				if (value == cap) {
					capHits++;
				}
				if (familyByJob != null && familyByJob[from] == familyByJob[to]) {
					withinTotal += value;
					withinCount++;
				} else if (familyByJob != null) {
					betweenTotal += value;
					betweenCount++;
				}
			}
		}

		int triangleViolations = 0;
		for (int middle = 1; middle <= n; middle++) {
			for (int from = 0; from <= n; from++) {
				if (from == middle) {
					continue;
				}
				for (int to = 1; to <= n; to++) {
					if (from == to || to == middle) {
						continue;
					}
					if (setup[from][to] > setup[from][middle] + setup[middle][to]) {
						triangleViolations++;
					}
				}
			}
		}
		int floydChangedArcs = countFloydChanges(setup);
		double mean = count == 0L ? 0.0 : total / count;
		double tolerance = Math.max(0.5, 0.01 * targetMean);
		double withinMean = withinCount == 0L ? Double.NaN : withinTotal / withinCount;
		double betweenMean = betweenCount == 0L ? Double.NaN : betweenTotal / betweenCount;
		return new Report(n, targetMean, mean, mean - targetMean, tolerance, cap, max,
				count == 0L ? 0.0 : (double) capHits / count, negative, diagonalErrors, terminalErrors,
				triangleViolations, floydChangedArcs, withinMean, betweenMean);
	}

	public void requirePreset(Report report, boolean familySetup) {
		if (report.negativeEntries() != 0 || report.diagonalErrors() != 0 || report.terminalColumnErrors() != 0) {
			throw new IllegalStateException("Invalid setup matrix shape/content: " + report);
		}
		if (report.maximum() > report.cap()) {
			throw new IllegalStateException("Setup cap violated: " + report);
		}
		if (Math.abs(report.meanError()) > report.meanTolerance()) {
			throw new IllegalStateException("Setup mean misses target: " + report);
		}
		if (report.triangleViolations() != 0 || report.floydChangedArcs() != 0) {
			throw new IllegalStateException("Setup triangle audit failed: " + report);
		}
		if (familySetup && !(report.betweenMean() > report.withinMean())) {
			throw new IllegalStateException("Family setup lost within/between separation: " + report);
		}
	}

	private static int countFloydChanges(int[][] setup) {
		int n = setup.length - 1;
		int[][] closed = new int[n + 1][n + 1];
		for (int row = 0; row <= n; row++) {
			closed[row] = setup[row].clone();
		}
		for (int middle = 1; middle <= n; middle++) {
			for (int from = 0; from <= n; from++) {
				if (from == middle) {
					continue;
				}
				for (int to = 1; to <= n; to++) {
					if (from == to || to == middle) {
						continue;
					}
					closed[from][to] = Math.min(closed[from][to], closed[from][middle] + closed[middle][to]);
				}
			}
		}
		int changed = 0;
		for (int from = 0; from <= n; from++) {
			for (int to = 1; to <= n; to++) {
				if (from != to && closed[from][to] != setup[from][to]) {
					changed++;
				}
			}
		}
		return changed;
	}

	public record Report(int n, double targetMean, double actualMean, double meanError, double meanTolerance,
			int cap, int maximum, double capHitRate, int negativeEntries, int diagonalErrors,
			int terminalColumnErrors, int triangleViolations, int floydChangedArcs,
			double withinMean, double betweenMean) {
	}
}
