package TWETBPC.CUT;

import TWETBPC.LP.LP;

/**
 * 空操作 cut generator。
 * <p>
 * 这个类本身没有数学意义，它的作用主要是：
 * 在框架搭建阶段提供一个最简单的 cut 分离器实现，
 * 保证 PC 的 cut 循环结构已经完整。
 */
public class NoOpCutGenerator implements CutGenerator {

	@Override
	/** 当前始终返回空结果。 */
	public CutGenerationResult separate(LP lp) {
		return CutGenerationResult.empty("Cut separation placeholder");
	}

	@Override
	/** @return 分离器名称 */
	public String getName() {
		return "NoOpCutGenerator";
	}

}
