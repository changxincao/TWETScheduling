package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Random;

import Common.Configure;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.Utility;
import TWETBPC.Util.PackedBitSet;

/**
 * 来源感知增量 dominance graph 的随机、拓扑和性能对拍。
 * <p>
 * 对拍目标是每次新 label 的接收结果、每个历史 label 的存活状态、任意 reachable-set 点值包络
 * 和最终查询语义都与旧实现一致。
 */
public final class IncrementalSourcedDominanceGraphConsistencyTest {

	private static final int JOB_COUNT = 8;
	private static final double T = 100.0;
	private static final int RANDOM_CASES = 120;
	private static final int LABELS_PER_CASE = 100;
	private static final Random RANDOM = new Random(20260710L);

	private IncrementalSourcedDominanceGraphConsistencyTest() {
	}

	public static void main(String[] args) {
		Configure.SegmentPool = false;
		Utility.resetCurUpperBound(Utility.big_M);
		verifyLabelRetentionSemantics(Direction.FORWARD);
		verifyLabelRetentionSemantics(Direction.BACKWARD);
		verifyDiamond(Direction.FORWARD);
		verifyDiamond(Direction.BACKWARD);
		int insertions = verifyRandom(Direction.FORWARD) + verifyRandom(Direction.BACKWARD);
		String performance = performanceSmoke();
		System.out.println("IncrementalSourcedDominanceGraphConsistencyTest passed: insertions=" + insertions
				+ ", " + performance);
	}

	private static void verifyLabelRetentionSemantics(Direction direction) {
		PaperDominanceGraph paper = new PaperDominanceGraph(direction);
		IncrementalSourcedDominanceGraph incremental = new IncrementalSourcedDominanceGraph(direction);
		Label paperOld = constantLabel(direction, 100.0, 1, 2);
		Label incrementalOld = constantLabel(direction, 100.0, 1, 2);
		Label paperNew = constantLabel(direction, 50.0, 1, 2);
		Label incrementalNew = constantLabel(direction, 50.0, 1, 2);
		assertSame(false, paper.insertOrDominate(paperOld), "paper first label");
		assertSame(false, incremental.insertOrDominate(incrementalOld), "incremental first label");
		assertSame(false, paper.insertOrDominate(paperNew), "paper improving label");
		assertSame(false, incremental.insertOrDominate(incrementalNew), "incremental improving label");
		if (incrementalOld.isDominated || paperOld.isDominated) {
			throw new AssertionError("same-key old label must remain join-active: direction=" + direction);
		}

		PaperDominanceGraph tiePaper = new PaperDominanceGraph(direction);
		IncrementalSourcedDominanceGraph tieIncremental = new IncrementalSourcedDominanceGraph(direction);
		Label successorPaper = constantLabel(direction, 40.0, 1);
		Label successorIncremental = constantLabel(direction, 40.0, 1);
		Label predecessorPaper = constantLabel(direction, 40.0, 1, 2);
		Label predecessorIncremental = constantLabel(direction, 40.0, 1, 2);
		tiePaper.insertOrDominate(successorPaper);
		tieIncremental.insertOrDominate(successorIncremental);
		tiePaper.insertOrDominate(predecessorPaper);
		tieIncremental.insertOrDominate(predecessorIncremental);
		if (!successorPaper.isDominated || !successorIncremental.isDominated) {
			throw new AssertionError("external tie did not remove successor: direction=" + direction);
		}
	}

	private static void verifyDiamond(Direction direction) {
		ArrayList<LabelSpec> specs = new ArrayList<LabelSpec>();
		specs.add(constantSpec(direction, 100.0, 2, 3, 4));
		specs.add(constantSpec(direction, 90.0, 2, 3));
		specs.add(constantSpec(direction, 80.0, 2, 4));
		specs.add(constantSpec(direction, 70.0, 2));
		specs.add(constantSpec(direction, 0.0, 2, 3, 4));
		compareSequence(direction, specs, "diamond");

		ArrayList<LabelSpec> unbalanced = new ArrayList<LabelSpec>();
		unbalanced.add(constantSpec(direction, 120.0, 2, 3, 4, 5));
		unbalanced.add(constantSpec(direction, 110.0, 2, 3));
		unbalanced.add(constantSpec(direction, 100.0, 2, 4, 5));
		unbalanced.add(constantSpec(direction, 90.0, 2, 4));
		unbalanced.add(constantSpec(direction, 80.0, 2));
		unbalanced.add(constantSpec(direction, 10.0, 2, 3, 4, 5));
		compareSequence(direction, unbalanced, "unbalanced");
	}

	private static int verifyRandom(Direction direction) {
		RANDOM.setSeed(20260710L + direction.ordinal());
		int insertions = 0;
		for (int caseId = 0; caseId < RANDOM_CASES; caseId++) {
			PaperDominanceGraph paper = new PaperDominanceGraph(direction);
			IncrementalSourcedDominanceGraph incremental = new IncrementalSourcedDominanceGraph(direction);
			ArrayList<LabelSpec> history = new ArrayList<LabelSpec>();
			ArrayList<Label> paperLabels = new ArrayList<Label>();
			ArrayList<Label> incrementalLabels = new ArrayList<Label>();
			for (int labelId = 0; labelId < LABELS_PER_CASE; labelId++) {
				LabelSpec spec = randomSpec(direction, labelId);
				history.add(spec);
				Label paperLabel = spec.newLabel();
				Label incrementalLabel = spec.newLabel();
				paperLabels.add(paperLabel);
				incrementalLabels.add(incrementalLabel);
				boolean paperDominated = paper.insertOrDominate(paperLabel);
				boolean incrementalDominated = incremental.insertOrDominate(incrementalLabel);
				if (paperDominated != incrementalDominated) {
					throw new AssertionError("insert mismatch: direction=" + direction + ", case=" + caseId
							+ ", label=" + labelId + ", paper=" + paperDominated
							+ ", incremental=" + incrementalDominated);
				}
				assertLabelStates(direction, paperLabels, incrementalLabels, caseId, labelId);
				assertPointEnvelopes(direction, history, paper, incremental, caseId, labelId);
				if (incremental.getActiveLabels().size() != paper.getActiveLabels().size()) {
					throw new AssertionError("active-label count mismatch: direction=" + direction
							+ ", case=" + caseId + ", label=" + labelId + ", paper="
							+ paper.getActiveLabels().size() + ", incremental="
							+ incremental.getActiveLabels().size());
				}
				insertions++;
			}
		}
		return insertions;
	}

	private static void compareSequence(Direction direction, ArrayList<LabelSpec> specs, String name) {
		PaperDominanceGraph paper = new PaperDominanceGraph(direction);
		IncrementalSourcedDominanceGraph incremental = new IncrementalSourcedDominanceGraph(direction);
		ArrayList<LabelSpec> history = new ArrayList<LabelSpec>();
		ArrayList<Label> paperLabels = new ArrayList<Label>();
		ArrayList<Label> incrementalLabels = new ArrayList<Label>();
		for (int i = 0; i < specs.size(); i++) {
			LabelSpec spec = specs.get(i);
			history.add(spec);
			Label paperLabel = spec.newLabel();
			Label incrementalLabel = spec.newLabel();
			paperLabels.add(paperLabel);
			incrementalLabels.add(incrementalLabel);
			boolean paperDominated = paper.insertOrDominate(paperLabel);
			boolean incrementalDominated = incremental.insertOrDominate(incrementalLabel);
			if (paperDominated != incrementalDominated) {
				throw new AssertionError(name + " insertion mismatch: direction=" + direction + ", label=" + i);
			}
			assertLabelStates(direction, paperLabels, incrementalLabels, -1, i);
			assertPointEnvelopes(direction, history, paper, incremental, -1, i);
		}
	}

	private static void assertLabelStates(Direction direction, ArrayList<Label> paperLabels,
			ArrayList<Label> incrementalLabels, int caseId, int labelId) {
		for (int i = 0; i < paperLabels.size(); i++) {
			if (paperLabels.get(i).isDominated != incrementalLabels.get(i).isDominated) {
				throw new AssertionError("label-state mismatch: direction=" + direction + ", case=" + caseId
						+ ", inserted=" + labelId + ", history=" + i + ", paper="
						+ paperLabels.get(i).isDominated + ", incremental="
						+ incrementalLabels.get(i).isDominated);
			}
		}
	}

	private static void assertPointEnvelopes(Direction direction, ArrayList<LabelSpec> history,
			PaperDominanceGraph paper, IncrementalSourcedDominanceGraph incremental, int caseId, int labelId) {
		for (int query = 0; query < 16; query++) {
			PackedBitSet target = randomReachableSet();
			double time;
			if (query == 0) {
				time = 0.0;
			} else if (query == 1) {
				time = T;
			} else {
				time = RANDOM.nextDouble() * T;
			}
			double expected = bruteBest(history, target, time);
			double actual = incremental.debugBestValue(target, target.cardinality(), time);
			assertClose(expected, actual, "point envelope direction=" + direction + ", case=" + caseId
					+ ", label=" + labelId + ", query=" + query + ", time=" + time);
			if (!Utility.isBigMValue(expected)) {
				double epsilon = 1e-4;
				boolean paperAt = paper.dominatesSinglePoint(target, target.cardinality(), time, expected + epsilon);
				boolean incrementalAt = incremental.dominatesSinglePoint(target, target.cardinality(), time,
						expected + epsilon);
				if (!paperAt || !incrementalAt) {
					throw new AssertionError("point dominance upper check failed: direction=" + direction
							+ ", expected=" + expected + ", actual=" + actual);
				}
				boolean paperBelow = paper.dominatesSinglePoint(target, target.cardinality(), time,
						expected - epsilon);
				boolean incrementalBelow = incremental.dominatesSinglePoint(target, target.cardinality(), time,
						expected - epsilon);
				if (paperBelow || incrementalBelow) {
					throw new AssertionError("point dominance lower check failed: direction=" + direction
							+ ", expected=" + expected + ", actual=" + actual);
				}
			}
		}
	}

	private static double bruteBest(ArrayList<LabelSpec> history, PackedBitSet target, double time) {
		double best = Utility.big_M;
		for (LabelSpec spec : history) {
			if (!spec.reachableSet.isSupersetOf(target) || spec.frontier.head == null
					|| Utility.compareLt(time, spec.frontier.head.start)
					|| Utility.compareGt(time, spec.frontier.tail.end)) {
				continue;
			}
			double value = spec.frontier.evaluate(time);
			if (Utility.compareLt(value, best)) {
				best = value;
			}
		}
		return best;
	}

	private static String performanceSmoke() {
		ArrayList<LabelSpec> specs = new ArrayList<LabelSpec>();
		RANDOM.setSeed(20260711L);
		for (int i = 0; i < 4000; i++) {
			specs.add(randomSpec(Direction.FORWARD, i));
		}
		long paperStart = System.nanoTime();
		PaperDominanceGraph paper = new PaperDominanceGraph(Direction.FORWARD);
		for (LabelSpec spec : specs) {
			paper.insertOrDominate(spec.newLabel());
		}
		long paperNanos = System.nanoTime() - paperStart;

		long incrementalStart = System.nanoTime();
		IncrementalSourcedDominanceGraph incremental = new IncrementalSourcedDominanceGraph(Direction.FORWARD);
		for (LabelSpec spec : specs) {
			incremental.insertOrDominate(spec.newLabel());
		}
		long incrementalNanos = System.nanoTime() - incrementalStart;
		return "performanceMs paper/incremental=" + formatMillis(paperNanos) + "/"
				+ formatMillis(incrementalNanos) + ", active=" + paper.getActiveLabels().size() + "/"
				+ incremental.getActiveLabels().size();
	}

	private static LabelSpec randomSpec(Direction direction, int jid) {
		return new LabelSpec(jid % (JOB_COUNT + 1), randomReachableSet(), randomFrontier(direction));
	}

	private static LabelSpec constantSpec(Direction direction, double value, int... jobs) {
		PackedBitSet reachable = setOf(jobs);
		PiecewiseLinearFunction frontier = new PiecewiseLinearFunction(0.0, T);
		frontier.addSegment(0.0, T, 0.0, value);
		frontier.normalize(direction);
		return new LabelSpec(1, reachable, frontier);
	}

	private static Label constantLabel(Direction direction, double value, int... jobs) {
		return constantSpec(direction, value, jobs).newLabel();
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

	private static PackedBitSet setOf(int... jobs) {
		PackedBitSet set = new PackedBitSet(JOB_COUNT + 2);
		for (int job : jobs) {
			set.add(job);
		}
		return set;
	}

	private static PiecewiseLinearFunction randomFrontier(Direction direction) {
		double start = direction == Direction.FORWARD ? RANDOM.nextInt(21) : 0.0;
		double end = direction == Direction.BACKWARD ? T - RANDOM.nextInt(21) : T;
		if (!Utility.compareLt(start, end)) {
			end = Math.min(T, start + 1.0);
		}
		double firstEnd = start + (end - start) / 3.0;
		double secondEnd = start + 2.0 * (end - start) / 3.0;
		double value = -30.0 + RANDOM.nextDouble() * 180.0;
		PiecewiseLinearFunction frontier = new PiecewiseLinearFunction(0.0, T);
		double slope = -2.0 + RANDOM.nextDouble() * 4.0;
		frontier.addSegment(start, firstEnd, slope, value - slope * start);
		value = slope * firstEnd + (value - slope * start);
		slope = -2.0 + RANDOM.nextDouble() * 4.0;
		frontier.addSegment(firstEnd, secondEnd, slope, value - slope * firstEnd);
		value = slope * secondEnd + (value - slope * firstEnd);
		slope = -2.0 + RANDOM.nextDouble() * 4.0;
		frontier.addSegment(secondEnd, end, slope, value - slope * secondEnd);
		frontier.normalize(direction);
		return frontier;
	}

	private static void assertSame(boolean expected, boolean actual, String label) {
		if (expected != actual) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertClose(double expected, double actual, String label) {
		if (Utility.isBigMValue(expected) && Utility.isBigMValue(actual)) {
			return;
		}
		double tolerance = 1e-6 * Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
		if (Math.abs(expected - actual) > tolerance) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static String formatMillis(long nanos) {
		return String.format(java.util.Locale.US, "%.3f", nanos / 1_000_000.0);
	}

	private static final class LabelSpec {
		final int jid;
		final PackedBitSet reachableSet;
		final PiecewiseLinearFunction frontier;

		LabelSpec(int jid, PackedBitSet reachableSet, PiecewiseLinearFunction frontier) {
			this.jid = jid;
			this.reachableSet = reachableSet;
			this.frontier = frontier;
		}

		Label newLabel() {
			return new Label(jid, null, new PackedBitSet(JOB_COUNT + 2), reachableSet.copy(), frontier.copy());
		}
	}
}
