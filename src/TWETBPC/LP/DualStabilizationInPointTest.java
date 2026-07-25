package TWETBPC.LP;

import java.util.Collections;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;

/** 验证 smoothing in-point 的 range-dual 修正和外包接入边界。 */
public final class DualStabilizationInPointTest {

	private DualStabilizationInPointTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", false, true);
		for (int job = 1; job <= data.n; job++) {
			data.outsourcingCost[job] = Utility.big_M;
		}

		TWETBPCConfig columnized = new TWETBPCConfig();
		columnized.outsourcingModel = "columns";
		LP lp = new LP(data, new Pool(data), new CutPool(), columnized, new OutsourcingPool(data));
		Node node = new Node(data, Collections.<Integer>emptyList(), Collections.<Integer>emptyList(),
				columnized.pseudoCostInf);
		node.maxMachineCount = 3;
		lp.construct(node, node.seedColumnIds);

		double[] jobDual = new double[data.n + 1];
		double[] outsourcingMembershipDual = new double[data.n + 1];
		double[][] arcDual = new double[data.n + 2][data.n + 2];
		LP.PricingDualSnapshot raw = new LP.PricingDualSnapshot(jobDual, 2.0, -1.0,
				outsourcingMembershipDual, arcDual, 100.0);
		LP.PricingDualSnapshot feasible = lp.makePricingDualFeasible(raw, -5.0, -7.0);
		assertClose(-3.0, feasible.machineDual, "machine dual correction");
		assertClose(-8.0, feasible.outsourcingColumnDual, "outsourcing dual correction");
		assertClose(78.0, feasible.rhsObjective, "range-dual RHS correction");

		TWETBPCConfig explicit = new TWETBPCConfig();
		explicit.outsourcingModel = "masterVariables";
		LP explicitLp = new LP(data, new Pool(data), new CutPool(), explicit, new OutsourcingPool(data));
		explicitLp.construct(node, node.seedColumnIds);
		if (!explicitLp.supportsDualStabilizationSnapshot()) {
			throw new AssertionError("Explicit model without outsourceable jobs should support smoothing");
		}
		data.outsourcingCost[1] = 10.0;
		if (explicitLp.supportsDualStabilizationSnapshot()) {
			throw new AssertionError("Explicit outsourcing choice must disable incomplete smoothing snapshot");
		}
		if (!lp.supportsDualStabilizationSnapshot()) {
			throw new AssertionError("Columnized outsourcing should keep complete smoothing support");
		}

		System.out.println("DualStabilizationInPointTest passed");
	}

	private static void assertClose(double expected, double actual, String message) {
		if (Math.abs(expected - actual) > 1e-9) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
