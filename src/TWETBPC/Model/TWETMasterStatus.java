package TWETBPC.Model;

/**
 * 受限主问题求解状态枚举。
 * <p>
 * 当前阶段保留了“placeholder”这一状态，
 * 用于明确标识：该结果是框架骨架为了联通流程而构造的可行占位结果，
 * 不是最终真实的 LP 求解结果。
 */
public enum TWETMasterStatus {
	/** 主问题尚未真正建立或求解。 */
	NOT_SOLVED,
	/** 当前仅用 seed 列构造出一个占位可行解。 */
	PLACEHOLDER_FEASIBLE,
	/** 当前受限主问题不可行。 */
	INFEASIBLE,
	/** 已得到 LP 松弛解。 */
	LP_RELAXATION
}
