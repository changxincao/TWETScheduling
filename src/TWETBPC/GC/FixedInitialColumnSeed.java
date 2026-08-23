package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 固定初始列实验使用的 sequence 快照。
 * <p>
 * 保存初始列、incumbent 内部序列和 incumbent 外包集合，不保存成本；目标场景必须重新计算内部列与 tariff。
 */
public final class FixedInitialColumnSeed {

	private final ArrayList<ArrayList<Integer>> initialSequences;
	private final ArrayList<ArrayList<Integer>> incumbentSequences;
	private final ArrayList<Integer> incumbentOutsourcedJobs;

	public FixedInitialColumnSeed(List<? extends List<Integer>> initialSequences,
			List<? extends List<Integer>> incumbentSequences) {
		this(initialSequences, incumbentSequences, Collections.<Integer>emptyList());
	}

	public FixedInitialColumnSeed(List<? extends List<Integer>> initialSequences,
			List<? extends List<Integer>> incumbentSequences, List<Integer> incumbentOutsourcedJobs) {
		this.initialSequences = deepCopy(initialSequences);
		this.incumbentSequences = deepCopy(incumbentSequences);
		this.incumbentOutsourcedJobs = new ArrayList<Integer>(incumbentOutsourcedJobs);
	}

	public List<List<Integer>> getInitialSequences() {
		return unmodifiableDeepCopy(initialSequences);
	}

	public List<List<Integer>> getIncumbentSequences() {
		return unmodifiableDeepCopy(incumbentSequences);
	}

	public List<Integer> getIncumbentOutsourcedJobs() {
		return Collections.unmodifiableList(incumbentOutsourcedJobs);
	}

	@Override
	public String toString() {
		return "initial=" + initialSequences.size() + ",incumbent=" + incumbentSequences.size()
				+ ",outsourced=" + incumbentOutsourcedJobs.size();
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
