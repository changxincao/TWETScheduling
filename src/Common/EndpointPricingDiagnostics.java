package Common;

/** 只收集当前线程 NG 定价轮内的端点事件；关闭时不读取线程状态。 */
public final class EndpointPricingDiagnostics implements AutoCloseable {
	public enum Event {
		TRIM_CALL, TRIM_LEFT_POINT,
		ADD_CALL, ADD_WHOLE_POINT, ADD_ISOLATED_POINT,
		MIN_SUM_CALL, MIN_SUM_ENDPOINT_IMPROVED,
		TRACED_JOIN_CALL, TRACED_JOIN_ENDPOINT_IMPROVED,
		FUSED_INSERT_CALL, FUSED_PREFIX_ENDPOINT_IMPROVED, FUSED_POINT_MATCH_IMPROVED,
		COLUMN_EVALUATE_CALL, COLUMN_FIRST_HORIZON_POINT,
		CB_FORWARD_SHIFT_POINT, CB_BACKWARD_SHIFT_POINT, CB_AUXILIARY_POINT
	}

	private static final boolean ENABLED = Boolean.getBoolean("twet.bpc.ngDssrEndpointDiagnostics");
	private static final ThreadLocal<EndpointPricingDiagnostics> ACTIVE = new ThreadLocal<>();
	private static final Event[] EVENTS = Event.values();
	private final long[] counts = new long[EVENTS.length];
	private final EndpointPricingDiagnostics previous;

	private EndpointPricingDiagnostics(EndpointPricingDiagnostics previous) {
		this.previous = previous;
	}

	public static EndpointPricingDiagnostics begin() {
		if (!ENABLED) return null;
		EndpointPricingDiagnostics scope = new EndpointPricingDiagnostics(ACTIVE.get());
		ACTIVE.set(scope);
		return scope;
	}

	public static boolean enabled() {
		return ENABLED;
	}

	public static void record(Event event) {
		if (!ENABLED) return;
		EndpointPricingDiagnostics scope = ACTIVE.get();
		if (scope != null) scope.counts[event.ordinal()]++;
	}

	public long count(Event event) {
		return counts[event.ordinal()];
	}

	public String summary() {
		StringBuilder output = new StringBuilder();
		for (Event event : EVENTS) output.append(' ').append(event.name()).append('=').append(count(event));
		return output.toString();
	}

	@Override
	public void close() {
		if (previous == null) ACTIVE.remove();
		else ACTIVE.set(previous);
	}
}
