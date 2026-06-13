package TWETBPC.GC;

/**
 * 带 SRI 状态的 label 标记接口。
 *
 * 2026-06-13: SRI-aware dominance 需要读取当前 subset-row 计数。label 构造后计数数组
 * 不再修改，因此这里直接暴露只读语义的内部数组，避免 dominance 热路径反复复制。
 */
interface SriStateLabel {
	String sriStateKey();

	byte[] sriCounts();
}
