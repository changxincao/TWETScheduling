package TWETBPC.BP;

import TWETBPC.LP.LP;

/**
 * 分支器接口。
 * <p>
 * 它对应旧 BPC 中 BranchA / BranchB / BranchC / BranchD 这类模块的统一抽象。
 * 不同之处在于这里把“一个分支规则”显式做成接口，
 * 便于后续组合多种规则并按顺序尝试。
 */
public interface Brancher {

	/**
	 * 对当前 LP 解尝试执行一次分支。
	 *
	 * @param lp 当前节点的 LP 对象
	 * @return 分支结果；若无法在本规则下分支，返回 not branched 的结果
	 */
	BranchResult branch(LP lp);

	/** @return 分支器名称 */
	String getName();

}
