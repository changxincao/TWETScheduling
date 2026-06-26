package TWETBPC.BP;

import TWETBPC.LP.LP;

/**
 * 强分支候选。候选只负责描述一个可分支对象，并按当前正式分支语义构造左右 child。
 */
public abstract class StrongBranchingCandidate {

	private final String type;
	private final String description;
	private final double value;
	private final double distanceToHalf;
	private final int order;

	protected StrongBranchingCandidate(String type, String description, double value) {
		this(type, description, value, 0);
	}

	protected StrongBranchingCandidate(String type, String description, double value, int order) {
		this.type = type;
		this.description = description;
		this.value = value;
		this.distanceToHalf = Math.abs(value - 0.5);
		this.order = order;
	}

	public String getType() {
		return type;
	}

	public String getDescription() {
		return description;
	}

	public double getValue() {
		return value;
	}

	public double getDistanceToHalf() {
		return distanceToHalf;
	}

	public int getOrder() {
		return order;
	}

	public abstract BranchResult createBranchResult(LP lp);
}
