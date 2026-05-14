package Output;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解验证结果。
 */
public final class ValidationResult {

	private final boolean feasible;
	private final boolean objectiveConsistent;
	private final double recomputedObjective;
	private final ArrayList<String> issues;

	public ValidationResult(boolean feasible, boolean objectiveConsistent, double recomputedObjective, List<String> issues) {
		this.feasible = feasible;
		this.objectiveConsistent = objectiveConsistent;
		this.recomputedObjective = recomputedObjective;
		this.issues = new ArrayList<String>(issues);
	}

	public boolean isFeasible() {
		return feasible;
	}

	public boolean isObjectiveConsistent() {
		return objectiveConsistent;
	}

	public double getRecomputedObjective() {
		return recomputedObjective;
	}

	public List<String> getIssues() {
		return Collections.unmodifiableList(issues);
	}

}
