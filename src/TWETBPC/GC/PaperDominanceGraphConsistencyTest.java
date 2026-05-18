package TWETBPC.GC;

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
	private static final Random RANDOM = new Random(20260518L);

	public static void main(String[] args) {
		Configure.SegmentPool = false;
		Utility.resetCurUpperBound(Utility.big_M);
		int insertions = 0;
		for (int caseId = 0; caseId < CASES; caseId++) {
			DominanceStore baseline = new DominanceGraph();
			DominanceStore paper = new PaperDominanceGraph();
			for (int labelId = 0; labelId < LABELS_PER_CASE; labelId++) {
				LabelPair pair = randomLabelPair(labelId);
				boolean baselineDominated = baseline.insertOrDominate(pair.baselineLabel);
				boolean paperDominated = paper.insertOrDominate(pair.paperLabel);
				if (baselineDominated != paperDominated) {
					throw new AssertionError("PaperDominanceGraph mismatch at case=" + caseId + ", label=" + labelId
							+ ", baselineDominated=" + baselineDominated + ", paperDominated=" + paperDominated);
				}
				insertions++;
			}
		}
		System.out.println("PaperDominanceGraphConsistencyTest passed: cases=" + CASES + ", insertions=" + insertions);
	}

	private static LabelPair randomLabelPair(int jid) {
		PackedBitSet reachable = randomReachableSet();
		PackedBitSet visited = new PackedBitSet(JOB_COUNT + 2);
		PiecewiseLinearFunction frontier = randomFrontier();
		Label baseline = new Label(jid % (JOB_COUNT + 1), null, visited.copy(), reachable.copy(), frontier.copy());
		Label paper = new Label(jid % (JOB_COUNT + 1), null, visited.copy(), reachable.copy(), frontier.copy());
		return new LabelPair(baseline, paper);
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

	private static PiecewiseLinearFunction randomFrontier() {
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
		f.normalize(Direction.FORWARD);
		return f;
	}

	private static final class LabelPair {
		final Label baselineLabel;
		final Label paperLabel;

		LabelPair(Label baselineLabel, Label paperLabel) {
			this.baselineLabel = baselineLabel;
			this.paperLabel = paperLabel;
		}
	}

}
