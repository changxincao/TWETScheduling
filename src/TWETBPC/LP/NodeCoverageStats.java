package TWETBPC.LP;

import java.util.Locale;
import java.util.Map;

import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETMasterSolution;

/**
 * 统计 exact pricing 闭合后的 coverage 行左端值，诊断 set-covering 过覆盖是否真实出现。
 */
final class NodeCoverageStats {

	private static final double TOLERANCE = 1.0e-6;

	private final double[] internal;
	private final double[] outsourcing;
	private final double[] total;

	private NodeCoverageStats(double[] internal, double[] outsourcing, double[] total) {
		this.internal = internal;
		this.outsourcing = outsourcing;
		this.total = total;
	}

	static NodeCoverageStats from(TWETMasterSolution solution, Pool pool, int jobCount) {
		double[] internal = new double[jobCount + 1];
		for (Map.Entry<Integer, Double> entry : solution.getColumnValues().entrySet()) {
			double value = entry.getValue().doubleValue();
			if (value <= TOLERANCE) {
				continue;
			}
			TWETColumn column = pool.getColumn(entry.getKey().intValue());
			for (int job : column.getSequence()) {
				if (job >= 1 && job <= jobCount) {
					internal[job] += value;
				}
			}
		}

		double[] outsourcing = new double[jobCount + 1];
		double[] solutionOutsourcing = solution.getOutsourcingValues();
		int copied = Math.min(jobCount, solutionOutsourcing.length - 1);
		if (copied > 0) {
			System.arraycopy(solutionOutsourcing, 1, outsourcing, 1, copied);
		}
		double[] total = new double[jobCount + 1];
		for (int job = 1; job <= jobCount; job++) {
			total[job] = internal[job] + outsourcing[job];
		}
		return new NodeCoverageStats(internal, outsourcing, total);
	}

	double total(int job) {
		return total[job];
	}

	double internal(int job) {
		return internal[job];
	}

	double outsourcing(int job) {
		return outsourcing[job];
	}

	String summary() {
		if (total.length <= 1) {
			return "jobs=0";
		}
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		double sum = 0.0;
		int under = 0;
		int tight = 0;
		int over = 0;
		int maxJob = 1;
		StringBuilder details = new StringBuilder();
		for (int job = 1; job < total.length; job++) {
			double value = total[job];
			min = Math.min(min, value);
			if (value > max) {
				max = value;
				maxJob = job;
			}
			sum += value;
			if (value < 1.0 - TOLERANCE) {
				under++;
			} else if (value > 1.0 + TOLERANCE) {
				over++;
			} else {
				tight++;
			}
			if (details.length() > 0) {
				details.append(';');
			}
			details.append(job).append(':').append(format(value))
					.append('(').append(format(internal[job])).append('+')
					.append(format(outsourcing[job])).append(')');
		}
		return "jobs=" + (total.length - 1)
				+ ", min=" + format(min)
				+ ", avg=" + format(sum / (total.length - 1))
				+ ", max=" + format(max)
				+ ", maxJob=" + maxJob
				+ ", under=" + under
				+ ", tight=" + tight
				+ ", over=" + over
				+ ", values=" + details;
	}

	private static String format(double value) {
		return String.format(Locale.US, "%.6f", value);
	}
}
