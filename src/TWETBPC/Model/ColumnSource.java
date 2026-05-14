package TWETBPC.Model;

/**
 * 列来源枚举。
 * <p>
 * 这个枚举主要用于记录某列是如何进入全局列池的，
 * 便于后续做调试、统计、对比实验。
 */
public enum ColumnSource {
	/** 来自启发式解的一整条机器序列。 */
	HEURISTIC_FULL,
	/** 来自启发式完整序列切出来的子序列。 */
	HEURISTIC_SUBSEQUENCE,
	/** 只包含一个 job 的基本列。 */
	SINGLETON,
	/** 来自启发式 pricing 的新增列。 */
	PRICING_HEURISTIC,
	/** 来自精确定价器的新增列。 */
	PRICING_EXACT,
	/** 手工/测试代码直接加入的列。 */
	MANUAL
}
