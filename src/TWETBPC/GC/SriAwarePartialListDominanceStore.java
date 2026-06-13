package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;

import Common.PiecewiseLinearFunction.Direction;
import TWETBPC.Util.PackedBitSet;

/**
 * SRI-aware 的 partial-list dominance 包装器。
 *
 * 该版本不做跨 SRI 状态的支配补偿，只把相同 SRI count signature 的 label 放进同一个
 * partial-list store。这样比旧 VRP 的 sr_1_value 补偿弱，但语义安全，适合作为第一版 SRI 接入。
 */
final class SriAwarePartialListDominanceStore implements DominanceStore {

	private final Direction direction;
	private final HashMap<String, PartialListDominanceStore> storeBySriState;

	SriAwarePartialListDominanceStore(Direction direction) {
		this.direction = direction;
		this.storeBySriState = new HashMap<String, PartialListDominanceStore>();
	}

	@Override
	public boolean insertOrDominate(Label label) {
		return storeFor(stateKey(label)).insertOrDominate(label);
	}

	@Override
	public ArrayList<Label> getActiveLabels() {
		ArrayList<Label> labels = new ArrayList<Label>();
		collectActiveLabels(labels);
		return labels;
	}

	@Override
	public void collectActiveLabels(ArrayList<Label> buffer) {
		for (PartialListDominanceStore store : storeBySriState.values()) {
			store.collectActiveLabels(buffer);
		}
	}

	@Override
	public boolean dominatesSinglePoint(PackedBitSet reachableSet, int reachableCardinality, double pointTime,
			double pointValue) {
		// SRI 状态未传入该接口。为避免跨 SRI 状态误删单点 label，这里保守返回 false；
		// 单点之间仍在 GCNGBBStyleBidirectionalNgDssr 的 SinglePointStore 中按 SRI key 比较。
		return false;
	}

	private PartialListDominanceStore storeFor(String stateKey) {
		PartialListDominanceStore store = storeBySriState.get(stateKey);
		if (store == null) {
			store = new PartialListDominanceStore(direction);
			storeBySriState.put(stateKey, store);
		}
		return store;
	}

	private String stateKey(Label label) {
		if (label instanceof SriStateLabel) {
			return ((SriStateLabel) label).sriStateKey();
		}
		return "";
	}
}
