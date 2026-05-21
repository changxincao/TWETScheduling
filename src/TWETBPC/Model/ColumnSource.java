package TWETBPC.Model;

/**
 * 列来源枚举。
 * <p>
 * 这个枚举主要用于记录某列如何进入全局列池，便于后续调试、统计和实验对比。
 */
public enum ColumnSource {
	/** 来自启发式解的一整条机器序列。 */
	HEURISTIC_FULL,
	/** 来自启发式 pricing 的新增列。 */
	PRICING_HEURISTIC,
	/** 来自精确定价器的新增列。 */
	PRICING_EXACT,
	/** 手工或测试代码直接加入的列。 */
	MANUAL
}
