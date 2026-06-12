package PWLFTesting;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import Common.Configure;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Segment;
import Common.Utility;

public class PiecewiseLinearFunctionPropertyTest {

	private static final double INF = Utility.curUpperBound;
	private static final double TOL = 1e-5;
	private static final int RANDOM_CASES = 500;
	private static final Random RANDOM = new Random(20260513L);

	private final ArrayList<String> report = new ArrayList<String>();
	private final ArrayList<String> failures = new ArrayList<String>();
	private int passed;
	private int warnings;
	private int failed;

	public static void main(String[] args) throws Exception {
		Locale.setDefault(Locale.US);
		Configure.SegmentPool = false;
		Utility.resetCurUpperBound(Utility.big_M);

		PiecewiseLinearFunctionPropertyTest test = new PiecewiseLinearFunctionPropertyTest();
		if (args.length > 0 && "merge-find-contract".equalsIgnoreCase(args[0])) {
			test.runMergeFindContractSubset();
		} else {
			test.runAll();
		}
		test.writeReport();
	}

	private void runAll() {
		report.add("# PiecewiseLinearFunction property test report");
		report.add("");
		report.add("Scope: shiftX, add, prefix/suffix minimization, findMinimal, dominates, mergeMinimum, updateDominatedIntervals.");
		report.add("Reference idea: route-evaluation operations in Ibaraki et al. style piecewise-linear time-penalty functions.");
		report.add("");

		testShiftXAgainstEvaluation();
		testAddAgainstPointwiseSum();
		testSetDomainFillOutsideWithBigM();
		testSetDomainFillOutsideWithBigMEndpointAndClosure();
		testSetDomainFillOutsideWithBigMAddContract();
		testPrefixMinAgainstOracle();
		testSuffixMinAgainstOracle();
		testPrefixBoundaryRealMinimumRegression();
		testSuffixBoundaryRealMinimumRegression();
		testEvaluateInternalBreakpointMinimum();
		testDirectionalNormalizeRegression();
		testRandomDirectionalNormalizeSweep();
		testFindMinimalNormalCases();
		testFindMinimalVerticalJumpRisk();
		testFindMinimalPositionSelectionCases();
		testDominanceDomainCoverageRisk();
		testMergeMinimumOverlappingCases();
		testMergeMinimumChangedReturnRegression();
		testMergeMinimumDisjointDomainRisk();
		testUpdateDominatedIntervalsBasicCases();
		testUpdateDominatedIntervalsNoChangeDoesNotRewrite();
		testUpdateDominatedIntervalsComplexCases();
		testRandomDirectionalMergeSweep();
		testRandomDirectionalUpdateDominatedSweep();
		testUpperBoundSemanticDependency();
		testRandomOperationSweep();
		testRandomFrontierSweep();
	}

	/**
	 * 2026-05-14: 专门检查当前 pricing 契约下的两个函数操作。
	 * mergeMinimum 这里只测试 [a,T] 形式、右端同为 T、且存在正长度公共区间的 forward frontier；
	 * 完全不相交和 [T,T] 单点 label 不属于这个函数的有效输入，应在 pricing 层提前处理。
	 */
	private void runMergeFindContractSubset() {
		report.add("# PiecewiseLinearFunction merge/find contract test report");
		report.add("");
		report.add("Scope: findMinimal and mergeMinimum under [a,T] forward-frontier input contract.");
		report.add("");

		testFindMinimalNormalCases();
		testFindMinimalVerticalJumpRisk();
		testFindMinimalPositionSelectionCases();
		testMergeMinimumSameRightBoundContractCases();
		testRandomContractFrontierSweep();
	}

	private void testShiftXAgainstEvaluation() {
		PiecewiseLinearFunction f = function(0, 20,
				seg(0, 3, 2, 1),
				seg(3, 8, -1, 10),
				seg(8, 12, 0.5, -2));
		double delta = 2.75;
		PiecewiseLinearFunction shifted = f.shiftX(delta);
		boolean ok = true;
		for (double x : samplePoints(f)) {
			double expected = evalRef(f, x);
			double actual = evalRef(shifted, x + delta);
			ok &= checkClose("shiftX keeps function values after horizontal shift", expected, actual);
		}
		if (ok) {
			pass("shiftX: value preservation on shifted domain");
		}
	}

	private void testAddAgainstPointwiseSum() {
		PiecewiseLinearFunction f = function(0, 20,
				seg(0, 5, 1.5, 2),
				seg(5, 9, -0.5, 12));
		PiecewiseLinearFunction g = function(0, 20,
				seg(2, 6, -1, 9),
				seg(6, 10, 2, -6));
		PiecewiseLinearFunction sum = f.add(g);
		boolean ok = true;
		for (double x : sampleUnion(f, g)) {
			double expected = evalRef(f, x) + evalRef(g, x);
			double actual = evalRef(sum, x);
			if (isFinite(expected)) {
				ok &= checkClose("add equals pointwise sum on common domain", expected, actual);
			}
		}
		if (ok) {
			pass("add: pointwise sum on overlapping domain");
		}
	}

	private void testSetDomainFillOutsideWithBigM() {
		PiecewiseLinearFunction f = function(0, 10,
				seg(0, 4, 2, 1),
				seg(4, 10, -1, 20));
		PiecewiseLinearFunction restricted = f.setDomain(2, 7, true);
		boolean ok = true;
		ok &= checkClose("setDomain bigM keeps left outside infeasible", Utility.big_M, evalRef(restricted, 1));
		ok &= checkClose("setDomain bigM keeps inner left value", evalRef(f, 3), evalRef(restricted, 3));
		ok &= checkClose("setDomain bigM keeps inner right value", evalRef(f, 6), evalRef(restricted, 6));
		ok &= checkClose("setDomain bigM keeps right outside infeasible", Utility.big_M, evalRef(restricted, 8));
		ok &= checkClose("setDomain bigM keeps tail right bound", 10, restricted.tail.end);
		ok &= checkClose("setDomain bigM keeps metadata right bound", 10, restricted.domainEnd);
		if (ok) {
			pass("setDomain(fillOutsideWithBigM): keeps right bound and fills outside window");
		}
	}

	private void testSetDomainFillOutsideWithBigMEndpointAndClosure() {
		PiecewiseLinearFunction f = function(0, 10,
				seg(0, 4, 2, 1),
				seg(4, 10, -1, 20));
		PiecewiseLinearFunction restricted = f.setDomain(2, 7, true);
		boolean ok = true;
		// 2026-05-15: 窗口端点是后续 pricing 最容易误用的地方。
		// setDomain(..., true) 会在窗口外接 big_M 段，但 evaluate() 应按内部断点两侧极限取较小值，
		// 因此窗口左右端点本身仍能取到窗口内原函数值。
		ok &= checkClose("setDomain bigM keeps left endpoint value", evalRef(f, 2), restricted.evaluate(2));
		ok &= checkClose("setDomain bigM keeps right endpoint value", evalRef(f, 7), restricted.evaluate(7));

		PiecewiseLinearFunction forward = restricted.copy();
		forward.minimizePrefixInPlace();
		ok &= checkClose("setDomain bigM forward closure keeps left infeasible", Utility.big_M, evalRef(forward, 1));
		ok &= checkClose("setDomain bigM forward closure extends best feasible value to the right", 5.0,
				evalRef(forward, 8));

		PiecewiseLinearFunction backward = restricted.copy();
		backward.minimizeSuffixInPlace();
		ok &= checkClose("setDomain bigM backward closure extends best feasible value to the left", 5.0,
				evalRef(backward, 1));
		ok &= checkClose("setDomain bigM backward closure keeps right infeasible", Utility.big_M,
				evalRef(backward, 8));
		if (ok) {
			pass("setDomain(fillOutsideWithBigM): endpoint and forward/backward closure semantics");
		}
	}

	private void testSetDomainFillOutsideWithBigMAddContract() {
		PiecewiseLinearFunction job = function(0, 10,
				seg(0, 4, 2, 1),
				seg(4, 10, -1, 20)).setDomain(2, 7, true);
		PiecewiseLinearFunction label = function(0, 10,
				seg(0, 10, 0, 3));
		PiecewiseLinearFunction sum = label.add(job);
		boolean ok = true;
		// 2026-05-15: 这个测试对应后续 BPC pricing 的核心契约。
		// job 窗口外不能物理裁掉，否则 add() 只取公共定义域会把 label 的右端 T 一起裁短。
		ok &= checkClose("setDomain bigM add keeps right bound", 10, sum.tail.end);
		ok &= checkClose("setDomain bigM add keeps metadata right bound", 10, sum.domainEnd);
		ok &= checkClose("setDomain bigM add keeps left outside infeasible", Utility.big_M + 3, evalRef(sum, 1));
		ok &= checkClose("setDomain bigM add keeps inner value", evalRef(job, 3) + 3, evalRef(sum, 3));
		ok &= checkClose("setDomain bigM add keeps right outside infeasible", Utility.big_M + 3, evalRef(sum, 8));
		if (ok) {
			pass("setDomain(fillOutsideWithBigM): add keeps [a,T] contract");
		}
	}

	private void testPrefixMinAgainstOracle() {
		PiecewiseLinearFunction f = function(0, 20,
				seg(0, 3, 3, 1),
				seg(3, 7, -2, 18),
				seg(7, 11, 1, -2));
		PiecewiseLinearFunction actual = f.copy();
		actual.minimizePrefixInPlace();
		boolean ok = true;
		for (double x : samplePoints(f)) {
			double expected = prefixMinRef(f, x);
			ok &= checkClose("minimizePrefixInPlace equals prefix-min oracle", expected, evalRef(actual, x));
		}
		if (ok) {
			pass("minimizePrefixInPlace: normal continuous/nonconvex case");
		}
	}

	private void testSuffixMinAgainstOracle() {
		PiecewiseLinearFunction f = function(0, 20,
				seg(0, 3, -2, 12),
				seg(3, 7, 1, 1),
				seg(7, 11, -1.5, 24));
		PiecewiseLinearFunction actual = f.copy();
		actual.minimizeSuffixInPlace();
		boolean ok = true;
		for (double x : samplePoints(f)) {
			double expected = suffixMinRef(f, x);
			ok &= checkClose("minimizeSuffixInPlace equals suffix-min oracle", expected, evalRef(actual, x));
		}
		if (ok) {
			pass("minimizeSuffixInPlace: normal continuous/nonconvex case");
		}
	}

	private void testPrefixBoundaryRealMinimumRegression() {
		// 2026-05-15: 回归测试。左边界的 runningMin 已经来自真实函数值时，不能因为 prevT==head.start 跳过常数最小值段。
		PiecewiseLinearFunction f = function(0, 8,
				seg(0, 4, 1, 5),
				seg(4, 8, -2, 17));
		PiecewiseLinearFunction actual = f.copy();
		actual.minimizePrefixInPlace();
		boolean ok = true;
		for (double x : new double[] { 0, 2, 5.5, 6, 7.5 }) {
			ok &= checkClose("minimizePrefixInPlace keeps real boundary minimum", prefixMinRef(f, x), evalRef(actual, x));
		}
		if (ok) {
			pass("minimizePrefixInPlace: boundary real-minimum regression");
		}
	}

	private void testSuffixBoundaryRealMinimumRegression() {
		// 2026-05-15: 回归测试。右边界的 runningMin 已经来自真实函数值时，不能因为 lastT==tail.end 跳过常数最小值段。
		PiecewiseLinearFunction f = function(3, 11,
				seg(3, 7, 1, 1),
				seg(7, 11, -1.5, 24));
		PiecewiseLinearFunction actual = f.copy();
		actual.minimizeSuffixInPlace();
		boolean ok = true;
		for (double x : new double[] { 3.5, 6.5, 8, 10, 11 }) {
			ok &= checkClose("minimizeSuffixInPlace keeps real boundary minimum", suffixMinRef(f, x), evalRef(actual, x));
		}
		if (ok) {
			pass("minimizeSuffixInPlace: boundary real-minimum regression");
		}
	}

	private void testDirectionalNormalizeRegression() {
		PiecewiseLinearFunction forward = function(0, 10,
				seg(0, 2, 0, INF),
				seg(2, 5, -1, 8),
				seg(5, 10, 0, INF));
		forward.normalize(PiecewiseLinearFunction.Direction.FORWARD);

		boolean forwardOk = true;
		forwardOk &= checkClose("normalize(FORWARD) drops unreachable left side", 2, forward.head.start);
		forwardOk &= checkClose("normalize(FORWARD) keeps right endpoint T", 10, forward.tail.end);
		forwardOk &= checkClose("normalize(FORWARD) closes trailing bigM by prefix minimum", 3, evalRef(forward, 7));

		PiecewiseLinearFunction backward = function(0, 10,
				seg(0, 3, 0, INF),
				seg(3, 8, 1, 1),
				seg(8, 10, 0, INF));
		backward.normalize(PiecewiseLinearFunction.Direction.BACKWARD);

		boolean backwardOk = true;
		backwardOk &= checkClose("normalize(BACKWARD) keeps left endpoint 0", 0, backward.head.start);
		backwardOk &= checkClose("normalize(BACKWARD) drops unreachable right tail", 8, backward.tail.end);
		backwardOk &= checkClose("normalize(BACKWARD) closes leading bigM by suffix minimum", 4, evalRef(backward, 1));
		backwardOk &= checkClose("normalize(BACKWARD) keeps suffix-min value inside real domain", 8, evalRef(backward, 7));

		if (forwardOk && backwardOk) {
			pass("normalize(Direction): forward keeps T, backward keeps 0");
		}
	}

	private void testEvaluateInternalBreakpointMinimum() {
		PiecewiseLinearFunction f = function(0, 20,
				seg(0, 10, 3, 0),
				seg(10, 20, 0, 70));
		double actual = f.evaluate(10);
		if (checkClose("evaluate internal breakpoint uses better side", 30, actual)) {
			pass("evaluate: internal breakpoint takes min of adjacent limits");
		}
	}

	private void testRandomDirectionalNormalizeSweep() {
		int localFailures = 0;
		for (int i = 0; i < RANDOM_CASES; i++) {
			PiecewiseLinearFunction forward = randomForwardNormalizeInput(100.0);
			PiecewiseLinearFunction forwardOriginal = forward.copy();
			PiecewiseLinearFunction backward = randomBackwardNormalizeInput(100.0);
			PiecewiseLinearFunction backwardOriginal = backward.copy();
			try {
				forward.normalize(PiecewiseLinearFunction.Direction.FORWARD);
				if (forward.head == null || !Utility.compareEq(forward.tail.end, 100.0)) {
					throw new AssertionError("forward normalize should keep right endpoint T");
				}
				for (double x : samplePoints(forward)) {
					requireClose(prefixMinRef(forwardOriginal, x), evalRef(forward, x));
				}

				backward.normalize(PiecewiseLinearFunction.Direction.BACKWARD);
				if (backward.head == null || !Utility.compareEq(backward.head.start, 0.0)) {
					throw new AssertionError("backward normalize should keep left endpoint 0");
				}
				for (double x : samplePoints(backward)) {
					requireClose(suffixMinRef(backwardOriginal, x), evalRef(backward, x));
				}
			} catch (Throwable ex) {
				localFailures++;
				if (localFailures <= 5) {
					fail("random directional normalize failure", "case=" + i + ", "
							+ ex.getClass().getSimpleName() + ": " + ex.getMessage()
							+ ", forward=" + compact(forwardOriginal)
							+ ", backward=" + compact(backwardOriginal));
				}
			}
		}
		if (localFailures == 0) {
			pass("normalize(Direction): random forward/backward sweep on " + RANDOM_CASES + " cases");
		} else {
			warn("normalize(Direction) random sweep found failures", "failureCount=" + localFailures + " / " + RANDOM_CASES);
		}
	}

	private void testFindMinimalNormalCases() {
		PiecewiseLinearFunction f = function(0, 20,
				seg(0, 2, -3, 12),
				seg(2, 5, 1, 4),
				seg(5, 8, -0.25, 8));
		double[] actual = f.findMinimal(false, true);
		double[] expected = minRef(f);
		if (checkClose("findMinimal normal min value", expected[0], actual[0])) {
			pass("findMinimal: normal multi-segment case");
		}
	}

	private void testFindMinimalVerticalJumpRisk() {
		PiecewiseLinearFunction f = function(0, 10,
				seg(0, 1, -10, 10),
				seg(1, 2, 0, 5));
		double[] actual = f.findMinimal(false, true);
		double leftLimitMinimum = 0.0;
		if (actual[0] > leftLimitMinimum + TOL) {
			warn("findMinimal misses a left-limit minimum at a vertical jump",
					"expectedLeftLimit=0.0, actual=" + actual[0]
							+ ". This matters only if segment-end left limits are meaningful states.");
		} else {
			pass("findMinimal: vertical jump left-limit case");
		}
	}

	private void testFindMinimalPositionSelectionCases() {
		boolean ok = true;

		PiecewiseLinearFunction flatBottom = function(0, 10,
				seg(0, 2, -2, 9),
				seg(2, 5, 0, 5),
				seg(5, 7, 2, -5));
		ok &= checkMinimalPair("findMinimal continuous flat bottom fromLeft", flatBottom.findMinimal(true, true), 5, 2);
		ok &= checkMinimalPair("findMinimal continuous flat bottom fromRight", flatBottom.findMinimal(true, false), 5, 5);

		PiecewiseLinearFunction verticalLeftLimit = function(0, 10,
				seg(0, 1, -10, 10),
				seg(1, 2, 0, 5));
		ok &= checkMinimalPair("findMinimal vertical gap left-limit fromLeft", verticalLeftLimit.findMinimal(false, true), 0, 1);
		ok &= checkMinimalPair("findMinimal vertical gap left-limit fromRight", verticalLeftLimit.findMinimal(false, false), 0, 1);

		PiecewiseLinearFunction jumpIntoFlat = function(0, 10,
				seg(0, 1, 0, 5),
				seg(1, 2, 0, 0),
				seg(2, 3, 0, 0));
		ok &= checkMinimalPair("findMinimal downward jump into flat fromLeft", jumpIntoFlat.findMinimal(false, true), 0, 1);
		ok &= checkMinimalPair("findMinimal downward jump into flat fromRight", jumpIntoFlat.findMinimal(false, false), 0, 3);

		PiecewiseLinearFunction separatedMinima = function(0, 10,
				seg(0, 1, 0, 0),
				seg(1, 2, 0, 5),
				seg(2, 3, 0, 0));
		ok &= checkMinimalPair("findMinimal separated equal minima fromLeft", separatedMinima.findMinimal(false, true), 0, 0);
		ok &= checkMinimalPair("findMinimal separated equal minima fromRight", separatedMinima.findMinimal(false, false), 0, 3);

		PiecewiseLinearFunction cacheOrder = function(0, 10,
				seg(0, 2, -1, 4),
				seg(2, 4, 0, 2));
		ok &= checkMinimalPair("findMinimal cache order fromRight first", cacheOrder.findMinimal(false, false), 2, 4);
		ok &= checkMinimalPair("findMinimal cache order then fromLeft", cacheOrder.findMinimal(false, true), 2, 2);

		if (ok) {
			pass("findMinimal: left/right position selection on continuous and discontinuous endpoints");
		}
	}

	private void testDominanceDomainCoverageRisk() {
		PiecewiseLinearFunction shorter = function(0, 20,
				seg(0, 5, 0, 1));
		PiecewiseLinearFunction longer = function(0, 20,
				seg(0, 10, 0, 5));
		boolean actual = shorter.dominates(longer);
		if (actual) {
			fail("dominates returns true when dominator does not cover dominated function domain",
					"shorter=[0,5], longer=[0,10]. If dominance is defined over the full dominated domain, this is unsafe.");
		} else {
			pass("dominates: rejects insufficient domain coverage");
		}
	}

	private void testMergeMinimumOverlappingCases() {
		PiecewiseLinearFunction f = function(0, 20,
				seg(0, 4, 2, 0),
				seg(4, 8, -1, 12));
		PiecewiseLinearFunction g = function(0, 20,
				seg(2, 6, -0.5, 8),
				seg(6, 10, 0.25, 2));
		PiecewiseLinearFunction actual = f.copy();
		actual.mergeMinimum(g.copy(), PiecewiseLinearFunction.Direction.FORWARD);
		boolean ok = true;
		for (double x : sampleUnion(f, g)) {
			double expected = prefixMinOfLowerEnvelopeRef(f, g, x);
			ok &= checkClose("mergeMinimum equals prefix-min lower envelope on overlapping/extended domain",
					expected, evalRef(actual, x));
		}
		if (ok) {
			pass("mergeMinimum: overlapping domains under forward frontier semantics");
		}
	}

	private void testMergeMinimumSameRightBoundContractCases() {
		boolean ok = true;
		ok &= checkMergedMinimumContractCase("mergeMinimum contract: g has left prefix",
				function(0, 100,
						seg(20, 45, 1.2, -3.0),
						seg(45, 100, -0.4, 69.0)),
				function(0, 100,
						seg(10, 30, -0.8, 35.0),
						seg(30, 100, 0.1, 8.0)));
		ok &= checkMergedMinimumContractCase("mergeMinimum contract: this has left prefix",
				function(0, 100,
						seg(10, 30, -0.6, 20.0),
						seg(30, 100, 0.0, 2.0)),
				function(0, 100,
						seg(25, 60, -0.3, 18.0),
						seg(60, 100, 0.2, -12.0)));
		ok &= checkMergedMinimumContractCase("mergeMinimum contract: same left and right bounds",
				function(0, 100,
						seg(5, 40, 0.5, 1.0),
						seg(40, 100, -0.2, 29.0)),
				function(0, 100,
						seg(5, 55, -0.1, 15.0),
						seg(55, 100, 0.0, 9.5)));
		if (ok) {
			pass("mergeMinimum: [a,T] same-right-bound contract cases");
		}
	}

	private void testMergeMinimumDisjointDomainRisk() {
		PiecewiseLinearFunction f = function(0, 20, seg(5, 8, 0, 10));
		PiecewiseLinearFunction left = function(0, 20, seg(0, 3, 0, 1));
		PiecewiseLinearFunction right = function(0, 20, seg(10, 12, 0, 1));
		checkMergeDisjoint("mergeMinimum disjoint-left domain", f, left);
		checkMergeDisjoint("mergeMinimum disjoint-right domain", f, right);
	}

	private void checkMergeDisjoint(String name, PiecewiseLinearFunction f, PiecewiseLinearFunction g) {
		try {
			PiecewiseLinearFunction actual = f.copy();
			actual.mergeMinimum(g.copy(), PiecewiseLinearFunction.Direction.FORWARD);
			boolean ok = true;
			for (double x : sampleUnion(f, g)) {
				double expected = prefixMinOfLowerEnvelopeRef(f, g, x);
				ok &= checkClose(name + " equals prefix-min lower envelope", expected, evalRef(actual, x));
			}
			if (ok) {
				pass(name);
			}
		} catch (Throwable ex) {
			fail(name + " throws exception", ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
	}

	private void testUpdateDominatedIntervalsBasicCases() {
		PiecewiseLinearFunction f = function(0, 20, seg(0, 5, 0, 10));
		PiecewiseLinearFunction g = function(0, 20, seg(0, 5, 0, 1));
		boolean removed = f.updateDominatedIntervals(g, PiecewiseLinearFunction.Direction.FORWARD);
		if (!removed || !f.isEmpty()) {
			fail("updateDominatedIntervals should remove fully dominated same-domain function",
					"removed=" + removed + ", empty=" + f.isEmpty());
		} else {
			pass("updateDominatedIntervals: full domination");
		}

		PiecewiseLinearFunction partial = function(0, 20,
				seg(0, 2, 0, 1),
				seg(2, 4, 0, 10),
				seg(4, 6, 0, 1));
		PiecewiseLinearFunction dominator = function(0, 20, seg(2, 4, 0, 5));
		try {
			partial.updateDominatedIntervals(dominator, PiecewiseLinearFunction.Direction.FORWARD);
			pass("updateDominatedIntervals: partial middle domination no exception");
		} catch (Throwable ex) {
			fail("updateDominatedIntervals partial middle domination throws exception",
					ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
	}

	private void testUpdateDominatedIntervalsComplexCases() {
		boolean ok = true;
		ok &= checkUpdateDominatedForwardClosure("updateDominatedIntervals: crossing split case",
				function(0, 100,
						seg(0, 20, 0.0, 12.0),
						seg(20, 55, -0.2, 16.0),
						seg(55, 100, 0.0, 5.0)),
				function(0, 100,
						seg(10, 35, -0.5, 22.0),
						seg(35, 75, 0.0, 4.0),
						seg(75, 100, 0.1, -3.5)));
		ok &= checkUpdateDominatedForwardClosure("updateDominatedIntervals: multiple dominated pieces",
				function(0, 100,
						seg(0, 25, 0.0, 20.0),
						seg(25, 50, 0.0, 16.0),
						seg(50, 75, 0.0, 12.0),
						seg(75, 100, 0.0, 8.0)),
				function(0, 100,
						seg(15, 40, 0.0, 14.0),
						seg(40, 60, 0.0, 18.0),
						seg(60, 90, 0.0, 7.0)));
		ok &= checkRandomUpdateDominatedForwardClosure();
		if (ok) {
			pass("updateDominatedIntervals: complex forward-closure cases");
		}
	}

	private void testUpperBoundSemanticDependency() {
		double old = Utility.curUpperBound;
		Utility.resetCurUpperBound(100.0);
		try {
			PiecewiseLinearFunction f = function(0, 10, seg(0, 4, 0, 200));
			PiecewiseLinearFunction p = f.copy();
			p.minimizePrefixInPlace();
			double actual = evalRef(p, 2);
			if (Math.abs(actual - 200.0) > TOL) {
				warn("prefix/suffix minimization is not pure mathematical min when curUpperBound is below function values",
						"curUpperBound=100, original=200, prefix result at t=2 is " + actual
								+ ". This is intentional pruning behavior but unsafe for exact pricing with negative dual offsets.");
			} else {
				pass("curUpperBound dependency: prefix behaves as pure min");
			}
		} finally {
			Utility.resetCurUpperBound(old);
		}
	}

	private void testRandomOperationSweep() {
		int localFailures = 0;
		for (int i = 0; i < RANDOM_CASES; i++) {
			PiecewiseLinearFunction f = randomContinuousFunction();
			PiecewiseLinearFunction g = randomContinuousFunction();
			try {
				PiecewiseLinearFunction sum = f.add(g);
				for (double x : sampleUnion(f, g)) {
					double expected = evalRef(f, x) + evalRef(g, x);
					if (isFinite(expected)) {
						requireClose(expected, evalRef(sum, x));
					}
				}
				PiecewiseLinearFunction min = f.copy();
				min.mergeMinimum(g.copy(), PiecewiseLinearFunction.Direction.FORWARD);
				for (double x : sampleUnion(f, g)) {
					requireClose(prefixMinOfLowerEnvelopeRef(f, g, x), evalRef(min, x));
				}
			} catch (Throwable ex) {
				localFailures++;
				if (localFailures <= 5) {
					fail("random sweep operation failure", "case=" + i + ", " + ex.getClass().getSimpleName()
							+ ": " + ex.getMessage() + ", f=" + compact(f) + ", g=" + compact(g));
				}
			}
		}
		if (localFailures == 0) {
			pass("random sweep: add and mergeMinimum forward-frontier semantics on " + RANDOM_CASES + " continuous cases");
		} else {
			warn("random sweep found failures", "failureCount=" + localFailures + " / " + RANDOM_CASES);
		}
	}

	private void testRandomFrontierSweep() {
		int localFailures = 0;
		for (int i = 0; i < RANDOM_CASES; i++) {
			PiecewiseLinearFunction f = randomContinuousFunction();
			PiecewiseLinearFunction g = randomContinuousFunction();
			f.minimizePrefixInPlace();
			g.minimizePrefixInPlace();
			Throwable ex = runWithTimeout(new Runnable() {
				@Override
				public void run() {
					checkMergedMinimum(f, g);
				}
			}, 300);
			if (ex != null) {
				PiecewiseLinearFunction min = f.copy();
				localFailures++;
				if (localFailures <= 5) {
					fail("random frontier sweep mergeMinimum failure", "case=" + i + ", " + ex.getClass().getSimpleName()
							+ ": " + ex.getMessage() + ", f=" + compact(f) + ", g=" + compact(g)
							+ ", currentMinCopy=" + compact(min));
				}
				if (ex instanceof TestTimeoutException) {
					break;
				}
			}
		}
		if (localFailures == 0) {
			pass("random frontier sweep: mergeMinimum on " + RANDOM_CASES + " prefix-minimized cases");
		} else {
			warn("random frontier sweep found failures", "failureCount=" + localFailures + " / " + RANDOM_CASES
					+ ". This is closer to route-evaluation frontier usage than arbitrary raw functions.");
		}
	}

	private void testRandomContractFrontierSweep() {
		int localFailures = 0;
		for (int i = 0; i < RANDOM_CASES; i++) {
			PiecewiseLinearFunction f = randomContractFunction(100.0);
			PiecewiseLinearFunction g = randomContractFunction(100.0);
			f.minimizePrefixInPlace();
			g.minimizePrefixInPlace();
			Throwable ex = runWithTimeout(new Runnable() {
				@Override
				public void run() {
					checkMergedMinimum(f, g);
				}
			}, 300);
			if (ex != null) {
				localFailures++;
				if (localFailures <= 5) {
					fail("random contract frontier mergeMinimum failure", "case=" + i + ", "
							+ ex.getClass().getSimpleName() + ": " + ex.getMessage()
							+ ", f=" + compact(f) + ", g=" + compact(g));
				}
				if (ex instanceof TestTimeoutException) {
					break;
				}
			}
		}
		if (localFailures == 0) {
			pass("random contract frontier sweep: mergeMinimum on " + RANDOM_CASES + " [a,T] cases");
		} else {
			warn("random contract frontier sweep found failures", "failureCount=" + localFailures + " / " + RANDOM_CASES);
		}
	}

	private void checkMergedMinimum(PiecewiseLinearFunction f, PiecewiseLinearFunction g) {
		PiecewiseLinearFunction min = f.copy();
		min.mergeMinimum(g.copy(), PiecewiseLinearFunction.Direction.FORWARD);
		for (double x : sampleUnion(f, g)) {
			requireClose(prefixMinOfLowerEnvelopeRef(f, g, x), evalRef(min, x));
		}
	}

	private boolean checkMergedMinimumContractCase(String name, PiecewiseLinearFunction f, PiecewiseLinearFunction g) {
		try {
			checkMergedMinimum(f, g);
			return true;
		} catch (Throwable ex) {
			fail(name, ex.getClass().getSimpleName() + ": " + ex.getMessage()
					+ ", f=" + compact(f) + ", g=" + compact(g));
			return false;
		}
	}

	private boolean checkUpdateDominatedForwardClosure(String name, PiecewiseLinearFunction f, PiecewiseLinearFunction g) {
		try {
			PiecewiseLinearFunction actual = f.copy();
			actual.updateDominatedIntervals(g.copy(), PiecewiseLinearFunction.Direction.FORWARD);
			for (double x : sampleUnion(f, g)) {
				double expected = prefixMinAfterDominanceRef(f, g, x);
				requireClose(expected, evalRef(actual, x));
			}
			return true;
		} catch (Throwable ex) {
			fail(name, ex.getClass().getSimpleName() + ": " + ex.getMessage()
					+ ", f=" + compact(f) + ", g=" + compact(g));
			return false;
		}
	}

	private boolean checkRandomUpdateDominatedForwardClosure() {
		int localFailures = 0;
		for (int i = 0; i < RANDOM_CASES; i++) {
			PiecewiseLinearFunction f = randomContractFunction(100.0);
			PiecewiseLinearFunction g = randomContractFunction(100.0);
			f.minimizePrefixInPlace();
			g.minimizePrefixInPlace();
			try {
				PiecewiseLinearFunction actual = f.copy();
				actual.updateDominatedIntervals(g.copy(), PiecewiseLinearFunction.Direction.FORWARD);
				for (double x : sampleUnion(f, g)) {
					requireClose(prefixMinAfterDominanceRef(f, g, x), evalRef(actual, x));
				}
			} catch (Throwable ex) {
				localFailures++;
				if (localFailures <= 5) {
					fail("random updateDominatedIntervals forward-closure failure", "case=" + i + ", "
							+ ex.getClass().getSimpleName() + ": " + ex.getMessage()
							+ ", f=" + compact(f) + ", g=" + compact(g));
				}
			}
		}
		if (localFailures > 0) {
			warn("random updateDominatedIntervals forward-closure found failures",
					"failureCount=" + localFailures + " / " + RANDOM_CASES);
			return false;
		}
		return true;
	}

	private void testRandomDirectionalMergeSweep() {
		int localFailures = 0;
		for (int i = 0; i < RANDOM_CASES; i++) {
			PiecewiseLinearFunction forwardF = randomContractFunction(100.0);
			PiecewiseLinearFunction forwardG = randomContractFunction(100.0);
			forwardF.minimizePrefixInPlace();
			forwardG.minimizePrefixInPlace();

			PiecewiseLinearFunction backwardF = randomBackwardContractFunction(100.0);
			PiecewiseLinearFunction backwardG = randomBackwardContractFunction(100.0);
			backwardF.minimizeSuffixInPlace();
			backwardG.minimizeSuffixInPlace();
			try {
				PiecewiseLinearFunction forwardActual = forwardF.copy();
				forwardActual.mergeMinimum(forwardG.copy(), PiecewiseLinearFunction.Direction.FORWARD);
				for (double x : sampleUnion(forwardF, forwardG)) {
					requireClose(prefixMinOfLowerEnvelopeRef(forwardF, forwardG, x), evalRef(forwardActual, x));
				}

				PiecewiseLinearFunction backwardActual = backwardF.copy();
				backwardActual.mergeMinimum(backwardG.copy(), PiecewiseLinearFunction.Direction.BACKWARD);
				for (double x : sampleUnion(backwardF, backwardG)) {
					requireClose(suffixMinOfLowerEnvelopeRef(backwardF, backwardG, x), evalRef(backwardActual, x));
				}
			} catch (Throwable ex) {
				localFailures++;
				if (localFailures <= 5) {
					fail("random directional mergeMinimum failure", "case=" + i + ", "
							+ ex.getClass().getSimpleName() + ": " + ex.getMessage()
							+ ", forwardF=" + compact(forwardF) + ", forwardG=" + compact(forwardG)
							+ ", backwardF=" + compact(backwardF) + ", backwardG=" + compact(backwardG));
				}
			}
		}
		if (localFailures == 0) {
			pass("mergeMinimum(Direction): random forward/backward sweep on " + RANDOM_CASES + " cases");
		} else {
			warn("mergeMinimum(Direction) random sweep found failures", "failureCount=" + localFailures + " / " + RANDOM_CASES);
		}
	}

	private void testMergeMinimumChangedReturnRegression() {
		PiecewiseLinearFunction equalBase = function(0, 20, seg(0, 10, 0, 5));
		PiecewiseLinearFunction equalCandidate = function(0, 20, seg(0, 5, 0, 5), seg(5, 10, 0, 5));
		PiecewiseLinearFunction equalMerged = equalBase.copy();
		int beforeSegments = segmentCount(equalMerged);
		boolean equalChanged = equalMerged.mergeMinimum(equalCandidate, PiecewiseLinearFunction.Direction.FORWARD, true);
		boolean ok = true;
		if (equalChanged) {
			fail("mergeMinimum changed return: equal candidate", "equal-valued candidate must not report changed");
			ok = false;
		}
		if (segmentCount(equalMerged) != beforeSegments) {
			fail("mergeMinimum changed return: equal candidate structure",
					"equal-valued candidate should not split or replace the current envelope");
			ok = false;
		}
		ok &= checkClose("mergeMinimum changed return equal value at left", evalRef(equalBase, 0), evalRef(equalMerged, 0));
		ok &= checkClose("mergeMinimum changed return equal value at right", evalRef(equalBase, 10), evalRef(equalMerged, 10));

		PiecewiseLinearFunction strictMerged = equalBase.copy();
		PiecewiseLinearFunction strictCandidate = function(0, 20, seg(0, 10, 0, 4));
		boolean strictChanged = strictMerged.mergeMinimum(strictCandidate, PiecewiseLinearFunction.Direction.FORWARD, true);
		if (!strictChanged) {
			fail("mergeMinimum changed return: strict candidate", "strictly lower candidate must report changed");
			ok = false;
		}
		ok &= checkClose("mergeMinimum changed return strict value", 4.0, evalRef(strictMerged, 0));

		PiecewiseLinearFunction boundaryBase = function(0, 1039,
				seg(60, 147, 0, 9930.647058823535),
				seg(147, 244, -4, 10126.411764705888));
		PiecewiseLinearFunction boundaryCandidate = function(0, 1039,
				seg(147, 244, -4, 10126.411764705888));
		PiecewiseLinearFunction boundaryMerged = boundaryBase.copy();
		boolean boundaryChanged = boundaryMerged.mergeMinimum(boundaryCandidate,
				PiecewiseLinearFunction.Direction.FORWARD, true);
		if (boundaryChanged) {
			fail("mergeMinimum changed return: boundary touch",
					"candidate equal to the right segment at p.end==start must not report changed");
			ok = false;
		}
		ok &= checkClose("mergeMinimum changed return boundary before value",
				evalRef(boundaryBase, 146.5), evalRef(boundaryMerged, 146.5));
		ok &= checkClose("mergeMinimum changed return boundary common value",
				evalRef(boundaryBase, 180), evalRef(boundaryMerged, 180));
		if (ok) {
			pass("mergeMinimum: changed return ignores equal structure and reports strict improvement");
		}
	}

	private void testUpdateDominatedIntervalsNoChangeDoesNotRewrite() {
		PiecewiseLinearFunction f = function(0, 20,
				seg(0, 6, 0, 1),
				seg(6, 20, 0, 2));
		PiecewiseLinearFunction g = function(0, 20,
				seg(2, 4, 0, 10),
				seg(4, 12, 0, 11));
		String before = signature(f);
		PiecewiseLinearFunction.TrimResult result = f.updateDominatedIntervalsDetailed(g,
				PiecewiseLinearFunction.Direction.FORWARD);
		if (result != PiecewiseLinearFunction.TrimResult.NO_CHANGE || !before.equals(signature(f))) {
			fail("updateDominatedIntervals: no-change does not rewrite frontier",
					"result=" + result + ", before=" + before + ", after=" + signature(f));
		} else {
			pass("updateDominatedIntervals: no-change does not rewrite frontier");
		}
	}

	private void testRandomDirectionalUpdateDominatedSweep() {
		int localFailures = 0;
		for (int i = 0; i < RANDOM_CASES; i++) {
			PiecewiseLinearFunction forwardF = randomContractFunction(100.0);
			PiecewiseLinearFunction forwardG = randomContractFunction(100.0);
			forwardF.minimizePrefixInPlace();
			forwardG.minimizePrefixInPlace();

			PiecewiseLinearFunction backwardF = randomBackwardContractFunction(100.0);
			PiecewiseLinearFunction backwardG = randomBackwardContractFunction(100.0);
			backwardF.minimizeSuffixInPlace();
			backwardG.minimizeSuffixInPlace();
			try {
				PiecewiseLinearFunction forwardActual = forwardF.copy();
				forwardActual.updateDominatedIntervals(forwardG.copy(), PiecewiseLinearFunction.Direction.FORWARD);
				for (double x : sampleUnion(forwardF, forwardG)) {
					requireClose(prefixMinAfterDominanceRef(forwardF, forwardG, x), evalRef(forwardActual, x));
				}

				PiecewiseLinearFunction backwardActual = backwardF.copy();
				backwardActual.updateDominatedIntervals(backwardG.copy(), PiecewiseLinearFunction.Direction.BACKWARD);
				for (double x : sampleUnion(backwardF, backwardG)) {
					requireClose(suffixMinAfterDominanceRef(backwardF, backwardG, x), evalRef(backwardActual, x));
				}
			} catch (Throwable ex) {
				localFailures++;
				if (localFailures <= 5) {
					fail("random directional updateDominatedIntervals failure", "case=" + i + ", "
							+ ex.getClass().getSimpleName() + ": " + ex.getMessage()
							+ ", forwardF=" + compact(forwardF) + ", forwardG=" + compact(forwardG)
							+ ", backwardF=" + compact(backwardF) + ", backwardG=" + compact(backwardG));
				}
			}
		}
		if (localFailures == 0) {
			pass("updateDominatedIntervals(Direction): random forward/backward sweep on " + RANDOM_CASES + " cases");
		} else {
			warn("updateDominatedIntervals(Direction) random sweep found failures",
					"failureCount=" + localFailures + " / " + RANDOM_CASES);
		}
	}

	private static Throwable runWithTimeout(Runnable task, long timeoutMillis) {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
		Thread worker = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					task.run();
				} catch (Throwable ex) {
					thrown.set(ex);
				} finally {
					done.countDown();
				}
			}
		}, "pwlf-test-worker");
		worker.setDaemon(true);
		worker.start();
		try {
			if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
				return new TestTimeoutException("operation did not finish in " + timeoutMillis + " ms");
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return ex;
		}
		return thrown.get();
	}

	private static class TestTimeoutException extends RuntimeException {
		TestTimeoutException(String message) {
			super(message);
		}
	}

	private PiecewiseLinearFunction randomContinuousFunction() {
		int n = 2 + RANDOM.nextInt(7);
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, 100);
		double x = RANDOM.nextDouble() * 3.0;
		double value = RANDOM.nextDouble() * 20.0 - 5.0;
		for (int i = 0; i < n; i++) {
			double width = 0.5 + RANDOM.nextDouble() * 4.0;
			double slope = -4.0 + RANDOM.nextDouble() * 8.0;
			f.addSegment(x, x + width, slope, value - slope * x);
			value = slope * (x + width) + (value - slope * x);
			x += width;
		}
		return f;
	}

	private static PiecewiseLinearFunction function(double domainStart, double domainEnd, double[]... segments) {
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(domainStart, domainEnd);
		for (double[] s : segments) {
			f.addSegment(s[0], s[1], s[2], s[3]);
		}
		return f;
	}

	private static int segmentCount(PiecewiseLinearFunction f) {
		int count = 0;
		for (Segment s = f.head; s != null; s = s.next) {
			count++;
		}
		return count;
	}

	private static String signature(PiecewiseLinearFunction f) {
		StringBuilder sb = new StringBuilder();
		for (Segment s = f.head; s != null; s = s.next) {
			if (sb.length() > 0) {
				sb.append('|');
			}
			sb.append(String.format(Locale.US, "%.12f,%.12f,%.12f,%.12f", s.start, s.end, s.slope,
					s.intercept));
		}
		return sb.toString();
	}

	private PiecewiseLinearFunction randomContractFunction(double rightBound) {
		int n = 2 + RANDOM.nextInt(6);
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, rightBound);
		double x = RANDOM.nextDouble() * 30.0;
		double value = RANDOM.nextDouble() * 20.0 - 5.0;
		for (int i = 0; i < n; i++) {
			double next = (i == n - 1)
					? rightBound
					: x + (rightBound - x) * (0.15 + RANDOM.nextDouble() * 0.45);
			if (!Utility.compareLt(x, next)) {
				next = Math.min(rightBound, x + 1.0);
			}
			double slope = -4.0 + RANDOM.nextDouble() * 8.0;
			f.addSegment(x, next, slope, value - slope * x);
			value = slope * next + (value - slope * x);
			x = next;
		}
		return f;
	}

	private PiecewiseLinearFunction randomBackwardContractFunction(double rightBound) {
		int n = 2 + RANDOM.nextInt(6);
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, rightBound);
		double x = 0.0;
		double value = RANDOM.nextDouble() * 20.0 - 5.0;
		for (int i = 0; i < n; i++) {
			double next = (i == n - 1)
					? rightBound
					: x + (rightBound - x) * (0.15 + RANDOM.nextDouble() * 0.45);
			if (!Utility.compareLt(x, next)) {
				next = Math.min(rightBound, x + 1.0);
			}
			double slope = -4.0 + RANDOM.nextDouble() * 8.0;
			f.addSegment(x, next, slope, value - slope * x);
			value = slope * next + (value - slope * x);
			x = next;
		}
		return f;
	}

	private PiecewiseLinearFunction randomForwardNormalizeInput(double rightBound) {
		PiecewiseLinearFunction core = randomContractFunction(rightBound);
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, rightBound);
		f.addSegment(0.0, core.head.start, 0.0, INF);
		for (Segment s = core.head; s != null; s = s.next) {
			f.addSegment(s.start, s.end, s.slope, s.intercept);
		}
		if (RANDOM.nextBoolean()) {
			double cut = core.tail.end - 5.0 - RANDOM.nextDouble() * 10.0;
			if (Utility.compareLt(core.head.start, cut) && Utility.compareLt(cut, rightBound)) {
				f = f.setDomain(core.head.start, cut, true);
			}
		}
		return f;
	}

	private PiecewiseLinearFunction randomBackwardNormalizeInput(double rightBound) {
		PiecewiseLinearFunction core = randomBackwardContractFunction(rightBound);
		double tailStart = rightBound - 5.0 - RANDOM.nextDouble() * 10.0;
		if (Utility.compareLe(tailStart, 0.0)) {
			tailStart = rightBound * 0.75;
		}
		PiecewiseLinearFunction f = new PiecewiseLinearFunction(0, rightBound);
		double firstEnd = Math.min(tailStart, rightBound * 0.2 + RANDOM.nextDouble() * rightBound * 0.2);
		if (Utility.compareLt(0.0, firstEnd)) {
			f.addSegment(0.0, firstEnd, 0.0, INF);
		}
		for (Segment s = core.head; s != null; s = s.next) {
			double start = Math.max(s.start, firstEnd);
			double end = Math.min(s.end, tailStart);
			if (Utility.compareLt(start, end)) {
				f.addSegment(start, end, s.slope, s.intercept);
			}
		}
		if (f.head == null || Utility.compareLt(f.tail.end, tailStart)) {
			double start = f.head == null ? firstEnd : f.tail.end;
			if (Utility.compareLt(start, tailStart)) {
				f.addSegment(start, tailStart, 0.0, RANDOM.nextDouble() * 20.0);
			}
		}
		if (Utility.compareLt(tailStart, rightBound)) {
			f.addSegment(tailStart, rightBound, 0.0, INF);
		}
		return f;
	}

	private static double[] seg(double start, double end, double slope, double intercept) {
		return new double[] { start, end, slope, intercept };
	}

	private static double evalRef(PiecewiseLinearFunction f, double x) {
		if (f.head == null) {
			return INF;
		}
		Segment prev = null;
		for (Segment s = f.head; s != null; s = s.next) {
			if (prev != null && Math.abs(prev.end - s.start) <= TOL && Math.abs(x - s.start) <= TOL) {
				double leftValue = prev.slope * x + prev.intercept;
				double rightValue = s.slope * x + s.intercept;
				return Math.min(leftValue, rightValue);
			}
			if ((x >= s.start - TOL && x < s.end - TOL) || (s.next == null && Math.abs(x - s.end) <= TOL)) {
				return s.slope * x + s.intercept;
			}
			prev = s;
		}
		return INF;
	}

	private static double prefixMinRef(PiecewiseLinearFunction f, double x) {
		double min = INF;
		for (Segment s = f.head; s != null; s = s.next) {
			if (s.start > x + TOL) {
				break;
			}
			double right = Math.min(x, s.end);
			if (s.start <= right + TOL) {
				min = Math.min(min, s.slope * s.start + s.intercept);
				min = Math.min(min, s.slope * right + s.intercept);
			}
		}
		return min;
	}

	private static double suffixMinRef(PiecewiseLinearFunction f, double x) {
		double min = INF;
		for (Segment s = f.head; s != null; s = s.next) {
			if (s.end + TOL < x) {
				continue;
			}
			double left = Math.max(x, s.start);
			if (left <= s.end + TOL) {
				min = Math.min(min, s.slope * left + s.intercept);
				min = Math.min(min, s.slope * s.end + s.intercept);
			}
		}
		return min;
	}

	private static double prefixMinOfLowerEnvelopeRef(PiecewiseLinearFunction f, PiecewiseLinearFunction g, double x) {
		double min = INF;
		for (double p : sampleUnion(f, g)) {
			if (p <= x + TOL) {
				min = Math.min(min, Math.min(evalRef(f, p), evalRef(g, p)));
			}
		}
		return min;
	}

	private static double suffixMinOfLowerEnvelopeRef(PiecewiseLinearFunction f, PiecewiseLinearFunction g, double x) {
		double min = INF;
		for (double p : sampleUnion(f, g)) {
			if (p + TOL >= x) {
				min = Math.min(min, Math.min(evalRef(f, p), evalRef(g, p)));
			}
		}
		return min;
	}

	private static double prefixMinAfterDominanceRef(PiecewiseLinearFunction f, PiecewiseLinearFunction g, double x) {
		double min = INF;
		List<Double> samples = sampleUnion(f, g);
		for (int i = 0; i < samples.size(); i++) {
			double p = samples.get(i);
			if (p <= x + TOL) {
				// 2026-05-14: updateDominatedIntervals 不维护零长度单点段。
				// 被支配区间按 [cur,nxt) 打成 big_M，nxt 端点归右侧片段。
				// 因此在断点处判断是否被支配时，看断点右侧的开区间，而不是只看断点等值。
				double probe = p;
				if (i + 1 < samples.size() && p < samples.get(i + 1) - TOL) {
					probe = (p + samples.get(i + 1)) * 0.5;
				}
				double fv = evalRef(f, p);
				double dominanceFv = evalRef(f, probe);
				double dominanceGv = evalRef(g, probe);
				double valueAfterDominance = Utility.compareLe(dominanceGv, dominanceFv) ? INF : fv;
				min = Math.min(min, valueAfterDominance);

				// 同一个断点还要检查左极限。典型情况是 dominated 区间从 p 开始，
				// p 右侧被打成 big_M，但 p 左侧原函数可能在靠近 p 时取得更小值；
				// prefix-min 必须保留这个左侧极限，否则会误判 updateDominatedIntervals。
				double leftValue = evalRefLeftLimit(f, p);
				if (leftValue < INF * 0.5) {
					double leftProbe = p;
					if (i > 0 && samples.get(i - 1) < p - TOL) {
						leftProbe = (samples.get(i - 1) + p) * 0.5;
					}
					double leftDominanceFv = evalRef(f, leftProbe);
					double leftDominanceGv = evalRef(g, leftProbe);
					double leftAfterDominance = Utility.compareLe(leftDominanceGv, leftDominanceFv) ? INF : leftValue;
					min = Math.min(min, leftAfterDominance);
				}
			}
		}
		return min;
	}

	private static double suffixMinAfterDominanceRef(PiecewiseLinearFunction f, PiecewiseLinearFunction g, double x) {
		double min = INF;
		List<Double> samples = sampleUnion(f, g);
		for (int i = samples.size() - 1; i >= 0; i--) {
			double p = samples.get(i);
			if (p + TOL >= x) {
				double probe = p;
				if (i > 0 && samples.get(i - 1) < p - TOL) {
					probe = (samples.get(i - 1) + p) * 0.5;
				}
				double fv = evalRef(f, p);
				double dominanceFv = evalRef(f, probe);
				double dominanceGv = evalRef(g, probe);
				double valueAfterDominance = Utility.compareLe(dominanceGv, dominanceFv) ? INF : fv;
				min = Math.min(min, valueAfterDominance);

				double rightValue = evalRefRightLimit(f, p);
				if (rightValue < INF * 0.5) {
					double rightProbe = p;
					if (i + 1 < samples.size() && p < samples.get(i + 1) - TOL) {
						rightProbe = (samples.get(i + 1) + p) * 0.5;
					}
					double rightDominanceFv = evalRef(f, rightProbe);
					double rightDominanceGv = evalRef(g, rightProbe);
					double rightAfterDominance = Utility.compareLe(rightDominanceGv, rightDominanceFv) ? INF : rightValue;
					min = Math.min(min, rightAfterDominance);
				}
			}
		}
		return min;
	}

	private static double evalRefLeftLimit(PiecewiseLinearFunction f, double x) {
		if (f.head == null) {
			return INF;
		}
		for (Segment s = f.head; s != null; s = s.next) {
			if (x > s.start + TOL && x <= s.end + TOL) {
				return s.slope * x + s.intercept;
			}
		}
		return INF;
	}

	private static double evalRefRightLimit(PiecewiseLinearFunction f, double x) {
		if (f.head == null) {
			return INF;
		}
		for (Segment s = f.head; s != null; s = s.next) {
			if (x >= s.start - TOL && x < s.end - TOL) {
				return s.slope * x + s.intercept;
			}
		}
		return INF;
	}

	private static double[] minRef(PiecewiseLinearFunction f) {
		double min = INF;
		double arg = -1;
		for (double p : samplePoints(f)) {
			double v = evalRef(f, p);
			if (v < min - TOL) {
				min = v;
				arg = p;
			}
		}
		return new double[] { min, arg };
	}

	private static List<Double> samplePoints(PiecewiseLinearFunction f) {
		ArrayList<Double> xs = new ArrayList<Double>();
		for (Segment s = f.head; s != null; s = s.next) {
			addUnique(xs, s.start);
			addUnique(xs, (s.start + s.end) * 0.5);
			addUnique(xs, s.end);
		}
		return xs;
	}

	private static List<Double> sampleUnion(PiecewiseLinearFunction f, PiecewiseLinearFunction g) {
		ArrayList<Double> xs = new ArrayList<Double>();
		for (double x : samplePoints(f)) {
			addUnique(xs, x);
		}
		for (double x : samplePoints(g)) {
			addUnique(xs, x);
		}
		for (Segment a = f.head; a != null; a = a.next) {
			for (Segment b = g.head; b != null; b = b.next) {
				double start = Math.max(a.start, b.start);
				double end = Math.min(a.end, b.end);
				if (start <= end + TOL) {
					addUnique(xs, start);
					addUnique(xs, (start + end) * 0.5);
					addUnique(xs, end);
					double ds = a.slope - b.slope;
					double di = b.intercept - a.intercept;
					if (Math.abs(ds) > TOL) {
						double cross = di / ds;
						if (cross > start + TOL && cross < end - TOL) {
							addUnique(xs, cross);
						}
					}
				}
			}
		}
		xs.sort(Double::compareTo);
		return xs;
	}

	private static void addUnique(ArrayList<Double> xs, double x) {
		for (double y : xs) {
			if (Math.abs(x - y) <= TOL) {
				return;
			}
		}
		xs.add(Double.valueOf(x));
	}

	private static boolean isFinite(double v) {
		return v < INF * 0.5;
	}

	private boolean checkClose(String name, double expected, double actual) {
		try {
			requireClose(expected, actual);
			return true;
		} catch (AssertionError ex) {
			fail(name, "expected=" + expected + ", actual=" + actual);
			return false;
		}
	}

	private boolean checkMinimalPair(String name, double[] actual, double expectedValue, double expectedX) {
		boolean ok = true;
		ok &= checkClose(name + " value", expectedValue, actual[0]);
		ok &= checkClose(name + " x", expectedX, actual[1]);
		return ok;
	}

	private static void requireClose(double expected, double actual) {
		if (Math.abs(expected - actual) > TOL) {
			throw new AssertionError("expected=" + expected + ", actual=" + actual);
		}
	}

	private void pass(String name) {
		passed++;
		report.add("- PASS: " + name);
	}

	private void warn(String name, String detail) {
		warnings++;
		report.add("- WARN: " + name + ". " + detail);
		failures.add("WARN," + csv(name) + "," + csv(detail));
	}

	private void fail(String name, String detail) {
		failed++;
		report.add("- FAIL: " + name + ". " + detail);
		failures.add("FAIL," + csv(name) + "," + csv(detail));
	}

	private static String csv(String s) {
		return "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\"";
	}

	private static String compact(PiecewiseLinearFunction f) {
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (Segment s = f.head; s != null && count < 6; s = s.next, count++) {
			sb.append(String.format("[%.3f,%.3f,%.3f,%.3f]", s.start, s.end, s.slope, s.intercept));
		}
		return sb.toString();
	}

	private void writeReport() throws IOException {
		Path outDir = Paths.get("pwlf_test_results");
		Files.createDirectories(outDir);
		Path reportPath = outDir.resolve("pwlf_property_test_report.md");
		Path csvPath = outDir.resolve("pwlf_case_findings.csv");
		report.add("");
		report.add("Summary: passed=" + passed + ", warnings=" + warnings + ", failed=" + failed);
		try (BufferedWriter writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
			for (String line : report) {
				writer.write(line);
				writer.newLine();
			}
		}
		try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
			writer.write("level,case,detail");
			writer.newLine();
			for (String line : failures) {
				writer.write(line);
				writer.newLine();
			}
		}
		System.out.println("PWLF property tests completed.");
		System.out.println("Report: " + reportPath.toAbsolutePath());
		System.out.println("Findings: " + csvPath.toAbsolutePath());
		System.out.println("Summary: passed=" + passed + ", warnings=" + warnings + ", failed=" + failed);
	}
}
