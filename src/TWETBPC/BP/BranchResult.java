package TWETBPC.BP;

import TWETBPC.LP.Node;

/**
 * 一次分支尝试的结果对象。
 * <p>
 * 它把“是否成功分支”“左右子节点是什么”“为什么这么分”包装到一起，
 * 让 Tree 只需要按统一方式处理不同分支规则的输出。
 */
public final class BranchResult {

	/** 当前规则是否成功生成了子节点。 */
	private final boolean branched;
	/** 左子节点，通常表示“禁用/上界”一侧。 */
	private final Node leftNode;
	/** 右子节点，通常表示“要求/下界”一侧。 */
	private final Node rightNode;
	/** 说明信息。 */
	private final String message;

	/**
	 * 构造一个分支结果对象。
	 */
	public BranchResult(boolean branched, Node leftNode, Node rightNode, String message) {
		this.branched = branched;
		this.leftNode = leftNode;
		this.rightNode = rightNode;
		this.message = message;
	}

	/**
	 * 返回“本规则无法分支”的标准结果。
	 */
	public static BranchResult none(String message) {
		return new BranchResult(false, null, null, message);
	}

	/** @return 是否已分支 */
	public boolean isBranched() {
		return branched;
	}

	/** @return 左子节点 */
	public Node getLeftNode() {
		return leftNode;
	}

	/** @return 右子节点 */
	public Node getRightNode() {
		return rightNode;
	}

	/** @return 说明信息 */
	public String getMessage() {
		return message;
	}

}
