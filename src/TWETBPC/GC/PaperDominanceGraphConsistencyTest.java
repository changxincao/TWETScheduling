package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Random;

import Common.Configure;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.Utility;
import TWETBPC.Util.PackedBitSet;

/**
 * PaperDominanceGraph 的轻量一致性测试。
 * <p>
 * 2026-05-18: 论文式 dominance graph 是为了避免每次插入 label 都全量扫描 eligible nodes。
 * 但它的判定语义必须和朴素全量扫描版一致。这个测试用同一批随机 label 同步喂给
 * {@link DominanceGraph} 和 {@link PaperDominanceGraph}，检查“新 label 是否被占优丢弃”的结果一致。
 * 它不是性能测试，也不覆盖 partial dominance；目标是防止图传播方向或 predecessor/successor 维护写反。
 */
public class PaperDominanceGraphConsistencyTest {

	private static final int JOB_COUNT = 8;
	private static final double T = 100.0;
	private static final int CASES = 200;
	private static final int LABELS_PER_CASE = 80;
	private static final int UNBALANCED_CASES = 1000;
	private static final Random RANDOM = new Random(20260518L);

	public static void main(String[] args) {
		Configure.SegmentPool = false;
		Utility.resetCurUpperBound(Utility.big_M);
		int insertions = verifyRandomInsertions(Direction.FORWARD)
				+ verifyRandomInsertions(Direction.BACKWARD);
		verifyDiamondPropagation(Direction.FORWARD);
		verifyDiamondPropagation(Direction.BACKWARD);
		verifyUnbalancedPropagation(Direction.FORWARD);
		verifyUnbalancedPropagation(Direction.BACKWARD);
		System.out.println("PaperDominanceGraphConsistencyTest passed: directions=2, cases=" + CASES
				+ ", insertions=" + insertions + ", diamondCases=2, unbalancedCases=" + (2 * UNBALANCED_CASES));
	}

	private static int verifyRandomInsertions(Direction direction) {
		RANDOM.setSeed(20260518L + direction.ordinal());
		int insertions = 0;
		for (int caseId = 0; caseId < CASES; caseId++) {
			DominanceStore baseline = new DominanceGraph(direction);
			DominanceStore paper = new PaperDominanceGraph(direction);
			DominanceStore indexed = new IndexedPaperDominanceGraph(direction);
			ArrayList<LabelPair> history = new ArrayList<LabelPair>();
			for (int labelId = 0; labelId < LABELS_PER_CASE; labelId++) {
				LabelPair pair = randomLabelPair(labelId, direction);
				history.add(pair);
				boolean baselineDominated = baseline.insertOrDominate(pair.baselineLabel);
				boolean paperDominated = paper.insertOrDominate(pair.paperLabel);
				boolean indexedDominated = indexed.insertOrDominate(pair.indexedLabel);
				if (baselineDominated != paperDominated) {
					throw new AssertionError("PaperDominanceGraph mismatch at case=" + caseId + ", label=" + labelId
							+ ", baselineDominated=" + baselineDominated + ", paperDominated=" + paperDominated);
				}
				if (paperDominated != indexedDominated) {
					throw new AssertionError("IndexedPaperDominanceGraph mismatch at case=" + caseId + ", label="
							+ labelId + ", paperDominated=" + paperDominated + ", indexedDominated="
							+ indexedDominated);
				}
				assertEquivalentState(history, baseline, paper, indexed, direction, caseId, labelId);
				insertions++;
			}
		}
		return insertions;
	}

	/** 共享后继会从两条支路收到传播；节点删除和重连后仍只能按最终前驱包络处理一次。 */
	private static void verifyDiamondPropagation(Direction direction) {
		DominanceStore baseline = new DominanceGraph(direction);
		DominanceStore paper = new PaperDominanceGraph(direction);
		DominanceStore indexed = new IndexedPaperDominanceGraph(direction);
		ArrayList<LabelPair> history = new ArrayList<LabelPair>();
		history.add(constantLabelPair(direction, 100.0, 2, 3, 4));
		history.add(constantLabelPair(direction, 90.0, 2, 3));
		history.add(constantLabelPair(direction, 80.0, 2, 4));
		history.add(constantLabelPair(direction, 70.0, 2));
		history.add(constantLabelPair(direction, 0.0, 2, 3, 4));
		for (int i = 0; i < history.size(); i++) {
			LabelPair pair = history.get(i);
			boolean baselineDominated = baseline.insertOrDominate(pair.baselineLabel);
			boolean paperDominated = paper.insertOrDominate(pair.paperLabel);
			boolean indexedDominated = indexed.insertOrDominate(pair.indexedLabel);
			if (baselineDominated != paperDominated || paperDominated != indexedDominated) {
				throw new AssertionError("diamond insertion mismatch: direction=" + direction + ", label=" + i);
			}
			assertEquivalentState(history.subList(0, i + 1), baseline, paper, indexed, direction, -1, i);
		}
		for (int i = 1; i <= 3; i++) {
			if (!history.get(i).paperLabel.isDominated) {
				throw new AssertionError("diamond successor was not removed: direction=" + direction + ", label=" + i);
			}
		}
	}

	/**
	 * 覆盖同一后继存在不同传播深度的包含图：S-A-C 与 S-B-D-C。Paper 图的一轮传播结果
	 * 必须和朴素 fixed-point 扫描一致，不能因为 C 较早入队而漏掉较长路径上的后续变化。
	 */
	private static void verifyUnbalancedPropagation(Direction direction) {
		RANDOM.setSeed(20260710L + direction.ordinal());
		int[][] reachableSets = {
				{ 2, 3, 4, 5 },
				{ 2, 3 },
				{ 2, 4, 5 },
				{ 2, 4 },
				{ 2 },
				{ 2, 3, 4, 5 }
		};
		for (int caseId = 0; caseId < UNBALANCED_CASES; caseId++) {
			DominanceStore baseline = new DominanceGraph(direction);
			DominanceStore paper = new PaperDominanceGraph(direction);
			DominanceStore indexed = new IndexedPaperDominanceGraph(direction);
			ArrayList<LabelPair> history = new ArrayList<LabelPair>();
			for (int labelId = 0; labelId < reachableSets.length; labelId++) {
				LabelPair pair = randomLabelPairForSet(direction, reachableSets[labelId]);
				history.add(pair);
				boolean baselineDominated = baseline.insertOrDominate(pair.baselineLabel);
				boolean paperDominated = paper.insertOrDominate(pair.paperLabel);
				boolean indexedDominated = indexed.insertOrDominate(pair.indexedLabel);
				if (baselineDominated != paperDominated || paperDominated != indexedDominated) {
					throw new AssertionError("unbalanced insertion mismatch: direction=" + direction + ", case="
							+ caseId + ", label=" + labelId);
				}
				assertEquivalentState(history, baseline, paper, indexed, direction, caseId, labelId);
			}
		}
	}

	private static void assertEquivalentState(java.util.List<LabelPair> history, DominanceStore baseline,
			DominanceStore paper, DominanceStore indexed, Direction direction, int caseId, int labelId) {
		for (int i = 0; i < history.size(); i++) {
			LabelPair pair = history.get(i);
			if (pair.baselineLabel.isDominated != pair.paperLabel.isDominated
					|| pair.paperLabel.isDominated != pair.indexedLabel.isDominated) {
				throw new AssertionError("active-state mismatch: direction=" + direction + ", case=" + caseId
						+ ", label=" + labelId + ", history=" + i);
			}
		}
		int baselineActive = baseline.getActiveLabels().size();
		int paperActive = paper.getActiveLabels().size();
		int indexedActive = indexed.getActiveLabels().size();
		if (baselineActive != paperActive || paperActive != indexedActive) {
			throw new AssertionError("active-count mismatch: direction=" + direction + ", case=" + caseId
					+ ", label=" + labelId + ", counts=" + baselineActive + "/" + paperActive + "/"
					+ indexedActive);
		}
	}

	private static LabelPair randomLabelPair(int jid, Direction direction) {
		PackedBitSet reachable = randomReachableSet();
		PackedBitSet visited = new PackedBitSet(JOB_COUNT + 2);
		PiecewiseLinearFunction frontier = randomFrontier(direction);
		Label baseline = new Label(jid % (JOB_COUNT + 1), null, visited.copy(), reachable.copy(), frontier.copy());
		Label paper = new Label(jid % (JOB_COUNT + 1), null, visited.copy(), reachable.copy(), frontier.copy());
		Label indexed = new Label(jid % (JOB_COUNT + 1), null, visited.copy(), reachable.copy(), frontier.copy());
		return new LabelPair(baseline, paper, indexed);
	}

	private static LabelPair constantLabelPair(Direction direction, double value, int... jobs) {
		PackedBitSet reachable = new PackedBitSet(JOB_COUNT + 2);
		for (int job : jobs) {
			reachable.add(job);
		}
		PackedBitSet visited = new PackedBitSet(JOB_COUNT + 2);
		PiecewiseLinearFunction frontier = new PiecewiseLinearFunction(0.0, T);
		frontier.addSegment(0.0, T, 0.0, value);
		frontier.normalize(direction);
		Label baseline = new Label(1, null, visited.copy(), reachable.copy(), frontier.copy());
		Label paper = new Label(1, null, visited.copy(), reachable.copy(), frontier.copy());
		Label indexed = new Label(1, null, visited.copy(), reachable.copy(), frontier.copy());
		return new LabelPair(baseline, paper, indexed);
	}

	private static LabelPair randomLabelPairForSet(Direction direction, int... jobs) {
		PackedBitSet reachable = new PackedBitSet(JOB_COUNT + 2);
		for (int job : jobs) {
			reachable.add(job);
		}
		PackedBitSet visited = new PackedBitSet(JOB_COUNT + 2);
		PiecewiseLinearFunction frontier = randomFrontier(direction);
		Label baseline = new Label(1, null, visited.copy(), reachable.copy(), frontier.copy());
		Label paper = new Label(1, null, visited.copy(), reachable.copy(), frontier.copy());
		Label indexed = new Label(1, null, visited.copy(), reachable.copy(), frontier.copy());
		return new LabelPair(baseline, paper, indexed);
	}

	private static PackedBitSet randomReachableSet() {
		PackedBitSet set = new PackedBitSet(JOB_COUNT + 2);
		for (int job = 1; job <= JOB_COUNT; job++) {
			if (RANDOM.nextBoolean()) {
				set.add(job);
			}
		}
		return set;
	}

	private static PiecewiseLinearFunction randomFrontier(Direction direction) {
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0.0, T);
		double v0 = 10.0 + RANDOM.nextDouble() * 80.0;
		double slope1 = -2.0 + RANDOM.nextDouble() * 4.0;
		double midValue = v0 + slope1 * 40.0;
		double slope2 = -2.0 + RANDOM.nextDouble() * 4.0;
		double v80 = midValue + slope2 * 40.0;
		double slope3 = -2.0 + RANDOM.nextDouble() * 4.0;
		f.addSegment(0.0, 40.0, slope1, v0);
		f.addSegment(40.0, 80.0, slope2, midValue - slope2 * 40.0);
		f.addSegment(80.0, T, slope3, v80 - slope3 * 80.0);
		f.normalize(direction);
		return f;
	}

	private static final class LabelPair {
		final Label baselineLabel;
		final Label paperLabel;
		final Label indexedLabel;

		LabelPair(Label baselineLabel, Label paperLabel, Label indexedLabel) {
			this.baselineLabel = baselineLabel;
			this.paperLabel = paperLabel;
			this.indexedLabel = indexedLabel;
		}
	}

}
