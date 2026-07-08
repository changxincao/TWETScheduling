package TWETBPC.GC;

import java.util.List;

import TWETBPC.LP.LP;
import TWETBPC.LP.Node;

/**
 * 内部机器 pricing 的节点兼容性辅助判断。
 */
final class PricingCompatibility {

	private PricingCompatibility() {
	}

	static boolean isRequiredOutsourcedJob(Node node, int job) {
		return node != null && job > 0 && node.getOutsourcingJobState(job) == Node.OUTSOURCE_REQUIRED;
	}

	static boolean containsRequiredOutsourcedJob(Node node, List<Integer> sequence) {
		if (node == null || sequence == null) {
			return false;
		}
		for (int job : sequence) {
			if (isRequiredOutsourcedJob(node, job)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * dual profitable window 只适用于未被分支、pricing-only fixing 或 time-indexed
	 * compact window 缩域的 no-cut root pricing。预处理后的 root 已经带有额外禁弧/硬窗证据时，
	 * 继续叠加 dual window 会把“本轮最优列搜索”错用成“缩域后列族证书”，因此统一在这里自动关闭。
	 */
	static boolean canUseDualProfitableWindow(LP lp) {
		if (lp == null || lp.getNode() == null) {
			return false;
		}
		Node node = lp.getNode();
		if (node.depth != 0 || !lp.getActiveCutIds().isEmpty()) {
			return false;
		}
		return node.countRequiredArcStates() == 0
				&& node.countForbiddenArcStates() == 0
				&& node.countBranchImpliedForbiddenArcs() == 0
				&& node.countPricingOnlyForbiddenArcs() == 0
				&& node.countTimeIndexedPricingOnlyForbiddenArcs() == 0
				&& node.countTimeIndexedPricingWindowTightenedJobs() == 0
				&& node.countRequiredAdjacencyPairs() == 0
				&& node.countForbiddenAdjacencyPairs() == 0;
	}
}
