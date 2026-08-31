package TWETBPC;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 正式 BPC 算法配置档案，封装命名模式对应的默认参数组合。
 */
public final class BPCAlgorithmProfile {

	private final String name;
	private final boolean timeIndexedGraph;
	private final boolean timeIndexedRank1;
	private final boolean ngDssr;
	private final Consumer<TWETBPCConfig> branchDefaultsApplier;

	BPCAlgorithmProfile(String name, boolean timeIndexedGraph, boolean timeIndexedRank1, boolean ngDssr,
			Consumer<TWETBPCConfig> branchDefaultsApplier) {
		this.name = Objects.requireNonNull(name, "name");
		this.timeIndexedGraph = timeIndexedGraph;
		this.timeIndexedRank1 = timeIndexedRank1;
		this.ngDssr = ngDssr;
		this.branchDefaultsApplier = Objects.requireNonNull(branchDefaultsApplier, "branchDefaultsApplier");
	}

	public String getName() {
		return name;
	}

	public boolean usesTimeIndexedGraphPricing() {
		return timeIndexedGraph || timeIndexedRank1;
	}

	public boolean usesTimeIndexedRank1CutPricing() {
		return timeIndexedRank1;
	}

	public boolean usesNgDssrPricing() {
		return ngDssr;
	}

	/**
	 * 将该命名档案完整应用到配置，包括模式开关、公共默认项和分支默认项。
	 */
	public void apply(TWETBPCConfig config) {
		Objects.requireNonNull(config, "config");
		BestBpcProfiles.resetNamedPricingModeFlags(config);
		BestBpcProfiles.resetNamedExperimentFlags(config);
		BestBpcProfiles.applyModeFlags(config, timeIndexedGraph, timeIndexedRank1, ngDssr);
		BestBpcProfiles.applyCommonDefaults(config);
		applyBranchDefaults(config);
	}

	void applyBranchDefaults(TWETBPCConfig config) {
		branchDefaultsApplier.accept(config);
	}
}
