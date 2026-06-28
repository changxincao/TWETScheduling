package TWETBPC.CUT;

import java.util.List;

import TWETBPC.Model.TWETCut;

/**
 * subset-row cut 列系数与 pricing penalty 的统一计算口径。
 * <p>
 * 2026-06-14: 普通 SRI 和 limited-memory SRI 都从这里扫描序列，避免 LP、pricing
 * 和启发式分别实现后出现系数不一致。
 */
public final class SubsetRowCutEvaluator {

	private static final double VALUE_TOLERANCE = 1e-8;

	private SubsetRowCutEvaluator() {
	}

	/** @return cut 在给定任务序列上的列系数。 */
	public static int coefficient(TWETCut cut, List<Integer> sequence, int jobCount) {
		if (cut == null || sequence == null || sequence.isEmpty()) {
			return 0;
		}
		boolean[] scope = membership(cut.getScopeJobs(), jobCount);
		if (cut.hasMemoryArcs()) {
			return arcMemoryCoefficient(scope, cut, cut.getMultiplier(), sequence);
		}
		if (cut.hasMemoryJobs()) {
			return limitedMemoryCoefficient(scope, membership(cut.getMemoryJobs(), jobCount), cut.getMultiplier(),
					sequence);
		}
		return fullCoefficient(scope, cut.getMultiplier(), sequence);
	}

	/** @return 多个 active SRI cut 在序列上的 reduced-cost penalty。 */
	public static double penalty(List<TWETCut> cuts, List<Double> duals, List<Integer> sequence, int jobCount) {
		if (cuts == null || duals == null || sequence == null || sequence.isEmpty()) {
			return 0.0;
		}
		int limit = Math.min(cuts.size(), duals.size());
		double penalty = 0.0;
		for (int idx = 0; idx < limit; idx++) {
			int coefficient = coefficient(cuts.get(idx), sequence, jobCount);
			if (coefficient > 0) {
				penalty -= duals.get(idx).doubleValue() * coefficient;
			}
		}
		return penalty;
	}

	/** 统一的 arc 编码，避免 memory arc 存储依赖当前 n。 */
	public static long arcKey(int from, int to) {
		return (((long) from) << 32) ^ (to & 0xffffffffL);
	}

	private static int fullCoefficient(boolean[] scope, double multiplier, List<Integer> sequence) {
		// 2026-06-28: 论文 time-indexed pseudo-schedule 口径按访问次数累加，重复访问同一 job 也会继续贡献。
		double state = 0.0;
		int coefficient = 0;
		for (int job : sequence) {
			if (job >= 0 && job < scope.length && scope[job]) {
				state += multiplier;
				while (state + VALUE_TOLERANCE >= 1.0) {
					coefficient++;
					state -= 1.0;
				}
			}
		}
		return coefficient;
	}

	private static int limitedMemoryCoefficient(boolean[] scope, boolean[] memory, double multiplier,
			List<Integer> sequence) {
		double state = 0.0;
		int coefficient = 0;
		for (int job : sequence) {
			if (job < 0 || job >= memory.length || !memory[job]) {
				state = 0.0;
				continue;
			}
			if (job < scope.length && scope[job]) {
				state += multiplier;
				while (state + VALUE_TOLERANCE >= 1.0) {
					coefficient++;
					state -= 1.0;
				}
			}
		}
		return coefficient;
	}

	private static int arcMemoryCoefficient(boolean[] scope, TWETCut cut, double multiplier, List<Integer> sequence) {
		double state = 0.0;
		int coefficient = 0;
		int previous = 0;
		for (int job : sequence) {
			if (!cut.containsMemoryArc(arcKey(previous, job))) {
				state = 0.0;
			}
			if (job >= 0 && job < scope.length && scope[job]) {
				state += multiplier;
				while (state + VALUE_TOLERANCE >= 1.0) {
					coefficient++;
					state -= 1.0;
				}
			}
			previous = job;
		}
		return coefficient;
	}

	private static boolean[] membership(List<Integer> jobs, int jobCount) {
		boolean[] values = new boolean[jobCount + 1];
		if (jobs == null) {
			return values;
		}
		for (int job : jobs) {
			if (job >= 1 && job <= jobCount) {
				values[job] = true;
			}
		}
		return values;
	}
}
