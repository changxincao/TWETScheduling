package TWETBPC.BP;

import java.util.Collections;
import java.util.List;

import TWETBPC.LP.LP;

/**
 * 分支器接口。普通求解仍调用 {@link #branch(LP)}；strong branching 只额外读取候选，不改变旧语义。
 */
public interface Brancher {

	BranchResult branch(LP lp);

	/**
	 * 2026-06-26: strong branching 只需要当前层级的一批候选；默认返回空，避免影响旧分支器。
	 */
	default List<StrongBranchingCandidate> collectStrongBranchingCandidates(LP lp, int limit) {
		return Collections.emptyList();
	}

	String getName();
}
