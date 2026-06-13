package TWETBPC.GC;

/**
 * 带 SRI 状态的 label 标记接口。
 *
 * 2026-06-13: SRI dual 会让两个 reachable set 相同的 label 在未来扩展时承担不同
 * subset-row penalty。第一版先按完整 SRI count 状态分桶，只允许同状态 label 互相 dominance，
 * 保证不会因为忽略 SRI 状态而误删 label。
 */
interface SriStateLabel {
	String sriStateKey();
}
