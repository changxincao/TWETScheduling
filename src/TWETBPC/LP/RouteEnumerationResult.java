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
	private final ArrayList<Integer> finiteOutsourcingColumnIds;
	private final int enumeratedStates;
	private final int candidateColumns;
	private final int outsourcingCandidateColumns;
	private final int newColumns;
	private final int newOutsourcingColumns;
	private final int duplicateActive;
	private final int duplicatePool;
	private final int duplicateRun;
	private final int completionBoundPrunedExtensions;
	private final long elapsedNanos;

	private RouteEnumerationResult(boolean attempted, boolean complete, String message, List<Integer> finiteColumnIds,
			List<Integer> finiteOutsourcingColumnIds, int enumeratedStates, int candidateColumns,
			int outsourcingCandidateColumns, int newColumns, int newOutsourcingColumns, int duplicateActive,
			int duplicatePool, int duplicateRun, int completionBoundPrunedExtensions, long elapsedNanos) {
		this.attempted = attempted;
		this.complete = complete;
		this.message = message;
		this.finiteColumnIds = new ArrayList<Integer>(finiteColumnIds);
		this.finiteOutsourcingColumnIds = new ArrayList<Integer>(finiteOutsourcingColumnIds);
		this.enumeratedStates = enumeratedStates;
		this.candidateColumns = candidateColumns;
		this.outsourcingCandidateColumns = outsourcingCandidateColumns;
		this.newColumns = newColumns;
		this.newOutsourcingColumns = newOutsourcingColumns;
		this.duplicateActive = duplicateActive;
		this.duplicatePool = duplicatePool;
		this.duplicateRun = duplicateRun;
		this.completionBoundPrunedExtensions = completionBoundPrunedExtensions;
		this.elapsedNanos = elapsedNanos;
	}

	public static RouteEnumerationResult skipped(String message) {
		return new RouteEnumerationResult(false, false, message, Collections.<Integer>emptyList(),
				Collections.<Integer>emptyList(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L);
	}

	public static RouteEnumerationResult incomplete(String message, List<Integer> finiteColumnIds, int enumeratedStates,
			int candidateColumns, int newColumns, int duplicateActive, int duplicatePool, int duplicateRun,
			long elapsedNanos) {
		return incomplete(message, finiteColumnIds, Collections.<Integer>emptyList(), enumeratedStates, candidateColumns,
				0, newColumns, 0, duplicateActive, duplicatePool, duplicateRun, 0, elapsedNanos);
	}

	public static RouteEnumerationResult incomplete(String message, List<Integer> finiteColumnIds,
			List<Integer> finiteOutsourcingColumnIds, int enumeratedStates, int candidateColumns,
			int outsourcingCandidateColumns, int newColumns, int newOutsourcingColumns, int duplicateActive,
			int duplicatePool, int duplicateRun, int completionBoundPrunedExtensions, long elapsedNanos) {
		return new RouteEnumerationResult(true, false, message, finiteColumnIds, finiteOutsourcingColumnIds,
				enumeratedStates, candidateColumns, outsourcingCandidateColumns, newColumns, newOutsourcingColumns,
				duplicateActive, duplicatePool, duplicateRun, completionBoundPrunedExtensions, elapsedNanos);
	}

	public static RouteEnumerationResult complete(String message, List<Integer> finiteColumnIds, int enumeratedStates,
			int candidateColumns, int newColumns, int duplicateActive, int duplicatePool, int duplicateRun,
			long elapsedNanos) {
		return complete(message, finiteColumnIds, Collections.<Integer>emptyList(), enumeratedStates, candidateColumns,
				0, newColumns, 0, duplicateActive, duplicatePool, duplicateRun, 0, elapsedNanos);
	}

	public static RouteEnumerationResult complete(String message, List<Integer> finiteColumnIds,
			List<Integer> finiteOutsourcingColumnIds, int enumeratedStates, int candidateColumns,
			int outsourcingCandidateColumns, int newColumns, int newOutsourcingColumns, int duplicateActive,
			int duplicatePool, int duplicateRun, int completionBoundPrunedExtensions, long elapsedNanos) {
		return new RouteEnumerationResult(true, true, message, finiteColumnIds, finiteOutsourcingColumnIds,
				enumeratedStates, candidateColumns, outsourcingCandidateColumns, newColumns, newOutsourcingColumns,
				duplicateActive, duplicatePool, duplicateRun, completionBoundPrunedExtensions, elapsedNanos);
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

	public List<Integer> getFiniteOutsourcingColumnIds() {
		return Collections.unmodifiableList(finiteOutsourcingColumnIds);
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
				+ ",outCandidates=" + outsourcingCandidateColumns + ",finiteCols=" + finiteColumnIds.size()
				+ ",finiteOutCols=" + finiteOutsourcingColumnIds.size() + ",new=" + newColumns
				+ ",newOut=" + newOutsourcingColumns + ",dupActive=" + duplicateActive + ",dupPool="
				+ duplicatePool + ",dupRun=" + duplicateRun + ",cbPruned=" + completionBoundPrunedExtensions
				+ ",ms=" + String.format("%.3f", elapsedNanos / 1_000_000.0) + ",msg=" + message;
	}
}
