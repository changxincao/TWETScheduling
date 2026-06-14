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

	private static int fullCoefficient(boolean[] scope, double multiplier, List<Integer> sequence) {
		// 2026-06-14: 保持当前 classical SRI 的 distinct-visit 口径；ng relaxed route
		// 不直接进入主问题，默认 full-SRI 不因重复访问同一 job 而改变列系数。
		boolean[] seen = new boolean[scope.length];
		double state = 0.0;
		int coefficient = 0;
		for (int job : sequence) {
			if (job >= 0 && job < scope.length && scope[job] && !seen[job]) {
				seen[job] = true;
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
