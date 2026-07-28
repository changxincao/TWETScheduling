package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 固定初始列实验使用的 sequence 快照。
 * <p>
 * 只保存列的任务顺序，不保存成本；目标实例必须用自己的 evaluator 重新计算成本。
 */
public final class FixedInitialColumnSeed {

	private final ArrayList<ArrayList<Integer>> initialSequences;
	private final ArrayList<ArrayList<Integer>> incumbentSequences;

	public FixedInitialColumnSeed(List<? extends List<Integer>> initialSequences,
			List<? extends List<Integer>> incumbentSequences) {
		this.initialSequences = deepCopy(initialSequences);
		this.incumbentSequences = deepCopy(incumbentSequences);
	}

	public List<List<Integer>> getInitialSequences() {
		return unmodifiableDeepCopy(initialSequences);
	}

	public List<List<Integer>> getIncumbentSequences() {
		return unmodifiableDeepCopy(incumbentSequences);
	}

	@Override
	public String toString() {
		return "initial=" + initialSequences.size() + ",incumbent=" + incumbentSequences.size();
	}

	private static ArrayList<ArrayList<Integer>> deepCopy(List<? extends List<Integer>> source) {
		ArrayList<ArrayList<Integer>> copy = new ArrayList<ArrayList<Integer>>(source.size());
		for (List<Integer> sequence : source) {
			copy.add(new ArrayList<Integer>(sequence));
		}
		return copy;
	}

	private static List<List<Integer>> unmodifiableDeepCopy(List<? extends List<Integer>> source) {
		ArrayList<List<Integer>> copy = new ArrayList<List<Integer>>(source.size());
		for (List<Integer> sequence : source) {
			copy.add(Collections.unmodifiableList(new ArrayList<Integer>(sequence)));
		}
		return Collections.unmodifiableList(copy);
	}
}
