package TWETBPC.Model;

/**
 * cut 类型枚举。
 * <p>
 * 这里并不意味着这些 cut 已经全部实现，
 * 而是先把后续可能使用的类型标签预留出来，
 * 方便 cut generator、日志和 cut pool 做统一管理。
 */
public enum TWETCutType {
	/** 通用占位类型。 */
	GENERIC,
	/** subset-row 类型。 */
	SUBSET_ROW,
	/** 顺序/先后关系类不等式。 */
	ORDERING,
	/** 相邻关系类不等式。 */
	ADJACENCY,
	/** 机器数相关不等式。 */
	MACHINE_COUNT,
	/** 用户自定义类型。 */
	USER_DEFINED
}
