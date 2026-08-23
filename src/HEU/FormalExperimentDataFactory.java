package HEU;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.Utility;

/**
 * 正式计算实验的数据派生入口。
 * <p>
 * 直接读取已经落盘的时间尺度实例；due window、setup cost 和外包经济参数在加载后统一应用，
 * 随后一次性重建 horizon、硬窗、禁弧和惩罚函数。
 */
public final class FormalExperimentDataFactory {

	private FormalExperimentDataFactory() {
	}

	public static Data load(Path instance, Scenario scenario) throws IOException {
		Data data = new Data(instance.toString(), true, true);
		applyDueWindow(data, scenario.dueWindowHalfWidth);
		applySetupCost(data, scenario.setupCostCoefficient);
		applyOutsourcing(data, scenario);
		rebuildDerivedData(data);
		return data;
	}

	private static void applyDueWindow(Data data, double halfWidth) {
		if (!Double.isFinite(halfWidth) || Utility.compareLt(halfWidth, 0.0)) {
			throw new IllegalArgumentException("dueWindowHalfWidth must be nonnegative: " + halfWidth);
		}
		for (int job = 1; job <= data.n; job++) {
			double center = 0.5 * (data.d_e[job] + data.d_l[job]);
			data.d_e[job] = Math.max(0.0, center - halfWidth);
			data.d_l[job] = center + halfWidth;
		}
	}

	private static void applySetupCost(Data data, double coefficient) {
		if (!Double.isFinite(coefficient) || Utility.compareLt(coefficient, 0.0)) {
			throw new IllegalArgumentException("setupCostCoefficient must be nonnegative: " + coefficient);
		}
		for (int from = 0; from <= data.n; from++) {
			for (int to = 0; to <= data.n; to++) {
				data.setupCost[from][to] = from == to ? 0.0 : coefficient * data.s[from][to];
			}
		}
	}

	private static void applyOutsourcing(Data data, Scenario scenario) {
		if (!scenario.outsourcingEnabled) {
			Arrays.fill(data.outsourcingCost, Utility.big_M);
			data.outsourcingCost[0] = 0.0;
			data.outsourcingCostFunction = new PiecewiseLinearFunction(0.0, 1.0);
			data.outsourcingCostFunction.addSegment(0.0, 1.0, 1.0, 0.0);
			return;
		}
		if (!Double.isFinite(scenario.outsourcingUnitRate)
				|| Utility.compareLe(scenario.outsourcingUnitRate, 0.0)) {
			throw new IllegalArgumentException("outsourcingUnitRate must be positive");
		}
		if (!Double.isFinite(scenario.discountStrength)
				|| Utility.compareLt(scenario.discountStrength, 0.0)
				|| Utility.compareGe(scenario.discountStrength, 0.5)) {
			throw new IllegalArgumentException("discountStrength must be in [0, 0.5)");
		}
		double totalBaseline = 0.0;
		for (int job = 1; job <= data.n; job++) {
			// 2026-08-23: 正式报价量同时反映加工负荷和任务较严格一侧的服务重要性。
			data.outsourcingCost[job] = data.p[job] * Math.max(data.w_e[job], data.w_t[job]);
			totalBaseline += data.outsourcingCost[job];
		}
		double domainEnd = Math.max(1.0, totalBaseline + 1.0);
		PiecewiseLinearFunction tariff = new PiecewiseLinearFunction(0.0, domainEnd);
		double firstBreakpoint = scenario.outsourcingBreakpoint1;
		double secondBreakpoint = scenario.outsourcingBreakpoint2;
		if (!Double.isFinite(firstBreakpoint) || !Double.isFinite(secondBreakpoint)
				|| Utility.compareLe(firstBreakpoint, 0.0)
				|| Utility.compareLe(secondBreakpoint, firstBreakpoint)) {
			throw new IllegalArgumentException("outsourcing breakpoints must satisfy 0 < Q1 < Q2");
		}
		double[] ends = new double[] { Math.min(firstBreakpoint, domainEnd),
				Math.min(secondBreakpoint, domainEnd), domainEnd };
		double start = 0.0;
		double valueAtStart = 0.0;
		for (int segment = 0; segment < ends.length; segment++) {
			if (!Utility.compareGt(ends[segment], start)) {
				continue;
			}
			double slope = scenario.outsourcingUnitRate * (1.0 - segment * scenario.discountStrength);
			double intercept = valueAtStart - slope * start;
			tariff.addSegment(start, ends[segment], slope, intercept);
			valueAtStart += slope * (ends[segment] - start);
			start = ends[segment];
		}
		data.outsourcingCostFunction = tariff;
	}

	private static void rebuildDerivedData(Data data) {
		data.setImprovedCmax();
		data.setPreprocessedHardWindows();
		data.preprocessInfeasibleArcsByHardWindows();
		data.setPenaltyFunctions();
		for (int job = 1; job <= data.n; job++) {
			double minimumSetup = Utility.big_M;
			for (int from = 0; from <= data.n; from++) {
				minimumSetup = Math.min(minimumSetup, data.s[from][job]);
			}
			data.min_s[job] = minimumSetup;
		}
		data.precomputeSetupCostAdvantages();
	}

	/** 实验层参数；时间尺度已经固化在输入文件中。 */
	public static final class Scenario {
		public double dueWindowHalfWidth = 0.0;
		public double setupCostCoefficient = 0.0;
		public boolean outsourcingEnabled = false;
		public double outsourcingUnitRate = 1.0;
		public double discountStrength = 0.0;
		public double outsourcingBreakpoint1 = Double.NaN;
		public double outsourcingBreakpoint2 = Double.NaN;
	}
}
