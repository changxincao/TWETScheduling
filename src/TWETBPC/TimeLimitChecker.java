package TWETBPC;

/**
 * 全局求解时间检查器。
 *
 * 2026-06-25: 对齐旧 VRP 的 TimeOut() 口径。Tree 持有 wall-clock 起点，
 * PC 和 pricing engine 只通过这个轻量接口查询是否应停止，避免各层重复维护时间状态。
 */
public interface TimeLimitChecker {

	TimeLimitChecker NONE = new TimeLimitChecker() {
		@Override
		public boolean isTimeLimitReached() {
			return false;
		}
	};

	boolean isTimeLimitReached();

	default boolean canContinue() {
		return !isTimeLimitReached();
	}
}
