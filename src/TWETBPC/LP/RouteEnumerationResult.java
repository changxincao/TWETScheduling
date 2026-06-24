package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * route enumeration 的一次尝试结果。
 * <p>
 * 只有 {@link #isComplete()} 为 true 时，枚举出的列集才有证明意义；中途达到上限或遇到不兼容设置时，
 * 调用方必须回到原 BPC 分支流程，不能据此关闭节点。
 */
public final class RouteEnumerationResult {

	private final boolean attempted;
	private final boolean complete;
	private final String message;
	private final ArrayList<Integer> finiteColumnIds;
	private final int enumeratedStates;
	private final int candidateColumns;
	private final int newColumns;
	private final int duplicateActive;
	private final int duplicatePool;
	private final int duplicateRun;
	private final long elapsedNanos;

	private RouteEnumerationResult(boolean attempted, boolean complete, String message, List<Integer> finiteColumnIds,
			int enumeratedStates, int candidateColumns, int newColumns, int duplicateActive, int duplicatePool,
			int duplicateRun, long elapsedNanos) {
		this.attempted = attempted;
		this.complete = complete;
		this.message = message;
		this.finiteColumnIds = new ArrayList<Integer>(finiteColumnIds);
		this.enumeratedStates = enumeratedStates;
		this.candidateColumns = candidateColumns;
		this.newColumns = newColumns;
		this.duplicateActive = duplicateActive;
		this.duplicatePool = duplicatePool;
		this.duplicateRun = duplicateRun;
		this.elapsedNanos = elapsedNanos;
	}

	public static RouteEnumerationResult skipped(String message) {
		return new RouteEnumerationResult(false, false, message, Collections.<Integer>emptyList(), 0, 0, 0, 0, 0, 0, 0L);
	}

	public static RouteEnumerationResult incomplete(String message, List<Integer> finiteColumnIds, int enumeratedStates,
			int candidateColumns, int newColumns, int duplicateActive, int duplicatePool, int duplicateRun,
			long elapsedNanos) {
		return new RouteEnumerationResult(true, false, message, finiteColumnIds, enumeratedStates, candidateColumns,
				newColumns, duplicateActive, duplicatePool, duplicateRun, elapsedNanos);
	}

	public static RouteEnumerationResult complete(String message, List<Integer> finiteColumnIds, int enumeratedStates,
			int candidateColumns, int newColumns, int duplicateActive, int duplicatePool, int duplicateRun,
			long elapsedNanos) {
		return new RouteEnumerationResult(true, true, message, finiteColumnIds, enumeratedStates, candidateColumns,
				newColumns, duplicateActive, duplicatePool, duplicateRun, elapsedNanos);
	}

	public boolean isAttempted() {
		return attempted;
	}

	public boolean isComplete() {
		return complete;
	}

	public String getMessage() {
		return message;
	}

	public List<Integer> getFiniteColumnIds() {
		return Collections.unmodifiableList(finiteColumnIds);
	}

	public int getEnumeratedStates() {
		return enumeratedStates;
	}

	public int getCandidateColumns() {
		return candidateColumns;
	}

	public int getNewColumns() {
		return newColumns;
	}

	public long getElapsedNanos() {
		return elapsedNanos;
	}

	public String summary() {
		return "complete=" + complete + ",states=" + enumeratedStates + ",candidates=" + candidateColumns
				+ ",finiteCols=" + finiteColumnIds.size() + ",new=" + newColumns + ",dupActive="
				+ duplicateActive + ",dupPool=" + duplicatePool + ",dupRun=" + duplicateRun + ",ms="
				+ String.format("%.3f", elapsedNanos / 1_000_000.0) + ",msg=" + message;
	}
}
