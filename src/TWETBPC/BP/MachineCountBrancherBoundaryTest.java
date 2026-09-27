package TWETBPC.BP;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import Basic.Data;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.Pool;
import TWETBPC.TWETBPCConfig;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETMasterStatus;

/** 验证机器数分支不会因父区间边界附近的数值漂移生成空 child。 */
public final class MachineCountBrancherBoundaryTest {

	private MachineCountBrancherBoundaryTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", true, true);
		TWETBPCConfig config = new TWETBPCConfig();
		LP lp = new LP(data, new Pool(data), new CutPool(), config, new OutsourcingPool(data));
		Node node = new Node(data, java.util.Collections.<Integer>emptyList(),
				java.util.Collections.<Integer>emptyList(), 0.0);
		node.minMachineCount = 0;
		node.maxMachineCount = 2;
		lp.construct(node, node.seedColumnIds);

		setSolution(lp, 2.0000001);
		BranchResult boundary = new MachineCountBrancher(1.0e-9).branch(lp);
		if (boundary.isBranched()) {
			throw new AssertionError("Upper-bound numerical drift generated child nodes");
		}

		setSolution(lp, -0.0000001);
		BranchResult lowerBoundary = new MachineCountBrancher(1.0e-9).branch(lp);
		if (lowerBoundary.isBranched()) {
			throw new AssertionError("Lower-bound numerical drift generated child nodes");
		}

		setSolution(lp, 1.4);
		BranchResult fractional = new MachineCountBrancher(1.0e-9).branch(lp);
		if (!fractional.isBranched() || fractional.getLeftNode().maxMachineCount != 1
				|| fractional.getRightNode().minMachineCount != 2) {
			throw new AssertionError("Valid fractional machine split changed");
		}
		System.out.println("MachineCountBrancherBoundaryTest passed");
	}

	private static void setSolution(LP lp, double machineUsage) throws Exception {
		LinkedHashMap<Integer, Double> values = new LinkedHashMap<Integer, Double>();
		values.put(Integer.valueOf(0), Double.valueOf(machineUsage));
		TWETMasterSolution solution = new TWETMasterSolution(TWETMasterStatus.LP_RELAXATION,
				values, 0.0, false, "machine-boundary-test");
		Field field = LP.class.getDeclaredField("lastSolution");
		field.setAccessible(true);
		field.set(lp, solution);
	}
}
