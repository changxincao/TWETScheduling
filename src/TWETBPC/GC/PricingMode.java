package TWETBPC.GC;

/**
 * 最终装配的内部 exact pricing 模式。下游流程只使用这里的能力，不再重新解释原始配置 flag。
 */
public enum PricingMode {
	OTHER,
	TIME_INDEXED,
	TIME_INDEXED_RANK1,
	NG_DSSR,
	NG_DSSR_PARTIAL,
	NG_DSSR_GRAPH_PARTIAL;

	public boolean usesTimeIndexedPricing() {
		return this == TIME_INDEXED || this == TIME_INDEXED_RANK1;
	}

	public boolean usesTimeIndexedRank1Pricing() {
		return this == TIME_INDEXED_RANK1;
	}

	public boolean usesNgDssrPricing() {
		return this == NG_DSSR || this == NG_DSSR_PARTIAL || this == NG_DSSR_GRAPH_PARTIAL;
	}

	public boolean supportsPartialNgSubsetRowCuts() {
		return this == NG_DSSR_PARTIAL;
	}

	public boolean supportsTimeIndexedRank1Cuts() {
		return this == TIME_INDEXED_RANK1;
	}
}
