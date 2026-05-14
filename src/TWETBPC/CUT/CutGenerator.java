package TWETBPC.CUT;

import TWETBPC.LP.LP;

/**
 * 割分离器接口。
 * <p>
 * 这层对应旧 BPC 中 GenCut 调用的各类具体 cut 模块。
 * 当前先统一抽象成一个接口，后续可以逐步挂接 TWET 自己的有效不等式。
 */
public interface CutGenerator {

	/**
	 * 在当前 LP 解上执行一次 cut separation。
	 *
	 * @param lp 当前节点的受限主问题
	 * @return 本次分离得到的割结果
	 */
	CutGenerationResult separate(LP lp);

	/** @return 分离器名称 */
	String getName();

}
