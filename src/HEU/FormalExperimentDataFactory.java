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
 * 基础文件保持不变；时间尺度、due window、setup cost 和外包经济参数在加载后统一应用，
 * 随后一次性重建 horizon、硬窗、禁弧和惩罚函数。
 */
public final class FormalExperimentDataFactory {

	private FormalExperimentDataFactory() {
	}

	public static Data load(Path instance, Scenario scenario) throws IOException {
		Data data = new Data(instance.toString(), true, true);
		applyTimeScale(data, scenario.timeScale);
		applyDueWindow(data, scenario.dueWindowHalfWidth);
		applySetupCost(data, scenario.setupCostCoefficient);
		applyOutsourcing(data, scenario);
		rebuildDerivedData(data);
		return data;
	}

	private static void applyTimeScale(Data data, double scale) {
		if (!Double.isFinite(scale) || Utility.compareLe(scale, 0.0)) {
			throw new IllegalArgumentException("timeScale must be positive: " + scale);
		}
		if (Utility.compareEq(scale, 1.0)) {
			return;
		}
		for (int job = 1; job <= data.n; job++) {
			data.p[job] *= scale;
			data.d_e[job] *= scale;
			data.d_l[job] *= scale;
			data.r[job] *= scale;
		}
		for (int from = 0; from <= data.n; from++) {
			for (int to = 0; to <= data.n; to++) {
				data.s[from][to] *= scale;
			}
		}
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
			// 实验中 b_j 取处理时间，保证外包规模与任务工作量同量纲。
			data.outsourcingCost[job] = data.p[job];
			totalBaseline += data.outsourcingCost[job];
		}
		double domainEnd = Math.max(1.0, totalBaseline + 1.0);
		PiecewiseLinearFunction tariff = new PiecewiseLinearFunction(0.0, domainEnd);
		double[] ends = new double[] { totalBaseline / 3.0, 2.0 * totalBaseline / 3.0, domainEnd };
		double start = 0.0;
		double valueAtStart = 0.0;
		for (int segment = 0; segment < ends.length; segment++) {
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

	/** 实验层参数；默认值对应原时间尺度、零宽窗、无 setup cost、无外包。 */
	public static final class Scenario {
		public double timeScale = 1.0;
		public double dueWindowHalfWidth = 0.0;
		public double setupCostCoefficient = 0.0;
		public boolean outsourcingEnabled = false;
		public double outsourcingUnitRate = 1.0;
		public double discountStrength = 0.0;
	}
}
