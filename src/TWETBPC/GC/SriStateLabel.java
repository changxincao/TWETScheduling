package TWETBPC.GC;

/** 供 SRI-aware dominance 直接读取不可变计数状态的 label 接口。 */
interface SriStateLabel {
	byte[] sriCounts();
}
