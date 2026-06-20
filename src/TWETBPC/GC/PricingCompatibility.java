package TWETBPC.GC;

import java.util.List;

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
}
