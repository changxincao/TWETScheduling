package TWETBPC.LP;

import java.util.Arrays;

import Basic.Data;
import TWETBPC.TWETBPCConfig;

/** 验证 restricted column 的列表顺序与增量 membership set 始终保持一致。 */
public final class LPRestrictedColumnMembershipTest {

	private LPRestrictedColumnMembershipTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", false, true);
		TWETBPCConfig config = new TWETBPCConfig();
		config.outsourcingModel = "columns";
		LP lp = new LP(data, new Pool(data), new CutPool(), config, new OutsourcingPool(data));
		Node node = new Node(data, Arrays.asList(Integer.valueOf(3)), Arrays.asList(Integer.valueOf(3)),
				config.pseudoCostInf);
		node.seedOutsourcingColumnIds.add(Integer.valueOf(7));
		lp.construct(node, node.seedColumnIds);

		assertActiveState(lp.isRestrictedColumnActive(3), "initial internal column missing");
		assertActiveState(!lp.isRestrictedColumnActive(4), "unexpected initial internal column");
		assertActiveState(lp.addColumns(Arrays.asList(Integer.valueOf(3), Integer.valueOf(4), Integer.valueOf(4))) == 1,
				"internal duplicate filtering changed");
		assertActiveState(lp.getRestrictedColumnIds().equals(Arrays.asList(Integer.valueOf(3), Integer.valueOf(4))),
				"internal list order or membership changed");
		assertActiveState(lp.isRestrictedColumnActive(4), "new internal column missing from membership set");

		assertActiveState(lp.isRestrictedOutsourcingColumnActive(7), "initial outsourcing column missing");
		assertActiveState(lp.addOutsourcingColumns(
				Arrays.asList(Integer.valueOf(7), Integer.valueOf(8), Integer.valueOf(8))) == 1,
				"outsourcing duplicate filtering changed");
		assertActiveState(lp.getRestrictedOutsourcingColumnIds()
				.equals(Arrays.asList(Integer.valueOf(7), Integer.valueOf(8))),
				"outsourcing list order or membership changed");
		assertActiveState(lp.isRestrictedOutsourcingColumnActive(8),
				"new outsourcing column missing from membership set");

		System.out.println("LPRestrictedColumnMembershipTest passed");
	}

	private static void assertActiveState(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
