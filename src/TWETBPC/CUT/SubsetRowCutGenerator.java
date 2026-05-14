package TWETBPC.CUT;

import TWETBPC.LP.LP;

/**
 * subset-row cut 分离器占位实现。
 * <p>
 * 之所以先保留这个类，是因为旧 VRP-BPC 中 subset-row cut 是一类比较重要的增强约束。
 * 对 TWET 来说，它是否还能以原形式成立，需要后续单独推导；
 * 因此当前只保留“接口位置”和“职责名字”。
 */
public class SubsetRowCutGenerator implements CutGenerator {

	@Override
	/** 当前返回空结果，表示具体 TWET 版分离逻辑尚未实现。 */
	public CutGenerationResult separate(LP lp) {
		return CutGenerationResult.empty("TWET subset-row cut placeholder");
	}

	@Override
	/** @return 分离器名称 */
	public String getName() {
		return "SubsetRowCutGenerator";
	}

}
