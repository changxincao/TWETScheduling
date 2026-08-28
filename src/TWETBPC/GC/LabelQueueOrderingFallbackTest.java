package TWETBPC.GC;

import java.lang.reflect.Method;

import Basic.Data;
import TWETBPC.TWETBPCConfig;

/** 验证所有labeling主线都以TIME作为缺省和非法配置的安全回退。 */
public final class LabelQueueOrderingFallbackTest {

	private LabelQueueOrderingFallbackTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", false, true);
		TWETBPCConfig config = new TWETBPCConfig();
		Object[] solvers = {
				new GC(data, config),
				new GCBidirectional(data, config),
				new GCBBAsymmetricBidirectional(data, config),
				new GCBBStyleBidirectionalFullDomain(data, config),
				new GCBBStyleBidirectionalFullDomainNodeJoin(data, config),
				new GCNGBBStyleBidirectional(data, config),
				new GCNGBBStyleBidirectionalNgDssr(data, config),
				new GCNGBBStyleBidirectionalPartialDominance(data, config)
		};
		for (Object solver : solvers) {
			assertOrdering(solver, null, "TIME");
			assertOrdering(solver, "unknown", "TIME");
			assertOrdering(solver, "reducedCost", "REDUCED_COST");
		}
		System.out.println("LabelQueueOrderingFallbackTest passed");
	}

	private static void assertOrdering(Object solver, String value, String expected) throws Exception {
		Method parser = solver.getClass().getDeclaredMethod("parseQueueOrdering", String.class);
		parser.setAccessible(true);
		String actual = String.valueOf(parser.invoke(solver, value));
		if (!expected.equals(actual)) {
			throw new AssertionError(solver.getClass().getSimpleName() + " value=" + value
					+ ": expected=" + expected + ", actual=" + actual);
		}
	}
}
