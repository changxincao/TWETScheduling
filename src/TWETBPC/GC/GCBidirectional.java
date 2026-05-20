package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.PriorityQueue;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.LP.LP;
import TWETBPC.LP.Node;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.PackedBitSet;
import TWETBPC.Util.SequenceSignature;

/**
 * no-cut 双向 labeling 定价器。
 * <p>
 * 2026-05-20: 该实现按旧 VRP 双向 GC 的框架组织为 FWExtend、BWExtend 和 Join：
 * forward label 从虚拟起点向后扩展，backward label 从虚拟终点向前扩展，二者在同一个中间
 * job 上拼接生成完整列。当前版本暂不接 SRI cut、ng-route、DSSR，也不改原 forward exact GC。
 * <p>
 * TWET 的列成本依赖 setup time/cost 和分段线性完成时间惩罚。为了避免第一版双向拼接在动态
 * H_ij、反向函数定义域或 endpoint discontinuity 上引入错误，Join 后统一用项目现有
 * {@link TWETColumnEvaluator} 重新评价完整序列，并按当前 LP dual 精确计算 reduced cost。
 * 因此这个类主要复刻旧 VRP 的双向搜索流程；成本计算细节仍复用当前 TWET 的权威评价口径。
 */
public class GCBidirectional {

	private static final double REDUCED_COST_TOLERANCE = -1e-6;

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;
	private PriorityQueue<BiLabel> FWUL;
	private PriorityQueue<BiLabel> BWUL;
	private ArrayList<ArrayList<BiLabel>> FWTL;
	private ArrayList<ArrayList<BiLabel>> BWTL;
	private ArrayList<TWETColumn> generatedColumns;
	private HashSet<SequenceSignature> generatedSignatures;
	private HashSet<SequenceSignature> activeColumnSignatures;
	private String lastMessage = "Bidirectional pricing not executed";

	public GCBidirectional(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	public ArrayList<TWETColumn> solve(LP lp) {
		initialize(lp);
		while (canContinue()) {
			if (!FWUL.isEmpty()) {
				forwardExtend(lp);
			}
			if (canContinue() && !BWUL.isEmpty()) {
				backwardExtend(lp);
			}
			if (FWUL.isEmpty() && BWUL.isEmpty()) {
				break;
			}
		}
		lastMessage = "Bidirectional no-cut labeling generated " + generatedColumns.size() + " columns";
		return generatedColumns;
	}

	public String getLastMessage() {
		return lastMessage;
	}

	private void initialize(LP lp) {
		FWUL = new PriorityQueue<BiLabel>();
		BWUL = new PriorityQueue<BiLabel>();
		FWTL = new ArrayList<ArrayList<BiLabel>>(data.n + 1);
		BWTL = new ArrayList<ArrayList<BiLabel>>(data.n + 1);
		for (int i = 0; i <= data.n; i++) {
			FWTL.add(new ArrayList<BiLabel>());
			BWTL.add(new ArrayList<BiLabel>());
		}
		generatedColumns = new ArrayList<TWETColumn>();
		generatedSignatures = new HashSet<SequenceSignature>();
		activeColumnSignatures = new HashSet<SequenceSignature>();
		for (int columnId : lp.getRestrictedColumnIds()) {
			activeColumnSignatures.add(lp.getPool().getColumn(columnId).getSignature());
		}
		PackedBitSet forwardVisited = new PackedBitSet(data.n + 2);
		forwardVisited.add(0);
		FWUL.add(BiLabel.source(forwardVisited));

		PackedBitSet backwardVisited = new PackedBitSet(data.n + 2);
		backwardVisited.add(lp.getNode().sinkId());
		BWUL.add(BiLabel.sink(lp.getNode().sinkId(), backwardVisited));
	}

	private boolean canContinue() {
		return generatedColumns.size() < config.maxExactPricingColumns;
	}

	private void forwardExtend(LP lp) {
		BiLabel label = FWUL.poll();
		if (label.isDominated) {
			return;
		}
		Node node = lp.getNode();
		if (!label.sequence.isEmpty()) {
			tryGenerateColumn(label.sequence, lp);
			joinForward(label, lp);
		}
		if (!shouldExpandForward(label)) {
			return;
		}
		int from = label.jid;
		for (int job = 1; job <= data.n && canContinue(); job++) {
			if (label.visited.contains(job) || node.isArcForbidden(from, job)) {
				continue;
			}
			double nextDuration = label.sourceDuration + data.getSetUp(from, job) + data.getProcessT(job);
			if (Utility.compareGt(nextDuration, data.CmaxH)) {
				continue;
			}
			BiLabel child = label.append(job, nextDuration);
			child.dominanceReducedCost = dominanceReducedCost(child.sequence, lp);
			addForwardLabel(child, lp);
		}
	}

	private void backwardExtend(LP lp) {
		BiLabel label = BWUL.poll();
		if (label.isDominated) {
			return;
		}
		Node node = lp.getNode();
		if (!label.sequence.isEmpty()) {
			tryGenerateColumn(label.sequence, lp);
			joinBackward(label, lp);
		}
		if (!shouldExpandBackward(label)) {
			return;
		}
		int first = label.sequence.isEmpty() ? node.sinkId() : label.jid;
		for (int job = 1; job <= data.n && canContinue(); job++) {
			if (label.visited.contains(job) || node.isArcForbidden(job, first)) {
				continue;
			}
			double nextInternalDuration = label.sequence.isEmpty() ? data.getProcessT(job)
					: data.getProcessT(job) + data.getSetUp(job, first) + label.internalDuration;
			double nextSourceDuration = data.getSetUp(0, job) + nextInternalDuration;
			if (Utility.compareGt(nextSourceDuration, data.CmaxH)) {
				continue;
			}
			BiLabel child = label.prepend(job, nextInternalDuration, nextSourceDuration);
			child.dominanceReducedCost = dominanceReducedCost(child.sequence, lp);
			addBackwardLabel(child, lp);
		}
	}

	/**
	 * forward 半程截断对应旧 VRP 中的 m_time < max_time/2。
	 * <p>
	 * 这里用最小 processing+setup duration 做资源代理；它只控制双向搜索规模，不承担最终
	 * reduced-cost 正确性判断，完整列仍由 tryGenerateColumn 重新评价。
	 */
	private boolean shouldExpandForward(BiLabel label) {
		return label.sequence.isEmpty() || Utility.compareLt(label.sourceDuration, data.CmaxH * 0.5);
	}

	/**
	 * backward 侧使用从当前首 job 到后缀末端的内部 duration 做半程截断。
	 * 这和旧 VRP 的 backward resource 截断作用一致：控制后向标签只覆盖路线后半段。
	 */
	private boolean shouldExpandBackward(BiLabel label) {
		return label.sequence.isEmpty() || Utility.compareLt(label.internalDuration, data.CmaxH * 0.5);
	}

	private void addForwardLabel(BiLabel label, LP lp) {
		if (isDominatedAndInsert(label, FWTL.get(label.jid))) {
			return;
		}
		FWUL.add(label);
		tryGenerateColumn(label.sequence, lp);
		joinForward(label, lp);
	}

	private void addBackwardLabel(BiLabel label, LP lp) {
		if (isDominatedAndInsert(label, BWTL.get(label.jid))) {
			return;
		}
		BWUL.add(label);
		tryGenerateColumn(label.sequence, lp);
		joinBackward(label, lp);
	}

	/**
	 * 2026-05-20: 双向 pricing 的第一层旧 VRP 风格占优。
	 * <p>
	 * 旧 VRP 的 FW/BW label 入表前会比较 reduced cost、time/load 和 memory 集合，删除被支配 label。
	 * 当前 TWET 双向第一版尚未把分段 reduced-cost 函数下沉到 label 层，因此这里先做一个保守的标量占优：
	 * 同一端点上，若已有 label 的已访问集合不大于新 label，且 duration 与当前 partial reduced-cost bound
	 * 都不差，则新 label 不需要继续扩展；反过来，新 label 也会删除表中被它支配的旧 label。
	 * 后续若实现函数型 forward/backward dominance，应替换这里的标量 bound。
	 */
	private boolean isDominatedAndInsert(BiLabel label, ArrayList<BiLabel> table) {
		for (int i = 0; i < table.size(); i++) {
			BiLabel existing = table.get(i);
			if (dominates(existing, label)) {
				label.isDominated = true;
				return true;
			}
			if (dominates(label, existing)) {
				existing.isDominated = true;
				table.remove(i);
				i--;
			}
		}
		table.add(label);
		return false;
	}

	private boolean dominates(BiLabel a, BiLabel b) {
		if (!a.visited.isSubsetOf(b.visited)) {
			return false;
		}
		return !Utility.compareGt(a.sourceDuration, b.sourceDuration)
				&& !Utility.compareGt(a.internalDuration, b.internalDuration)
				&& !Utility.compareGt(a.dominanceReducedCost, b.dominanceReducedCost);
	}

	/**
	 * 对应旧 VRP Join：forward 和 backward 在同一个中间 job 上拼接。
	 */
	private void joinForward(BiLabel forward, LP lp) {
		if (generatedColumns.size() >= config.maxExactPricingColumns || forward.sequence.isEmpty()) {
			return;
		}
		for (BiLabel backward : BWTL.get(forward.jid)) {
			if (backward.isDominated) {
				continue;
			}
			tryJoin(forward, backward, lp);
			if (generatedColumns.size() >= config.maxExactPricingColumns) {
				return;
			}
		}
	}

	/**
	 * 新 backward label 入表后，反向检查已有 forward label，保持 FW/BW 两侧对称。
	 */
	private void joinBackward(BiLabel backward, LP lp) {
		if (generatedColumns.size() >= config.maxExactPricingColumns || backward.sequence.isEmpty()) {
			return;
		}
		for (BiLabel forward : FWTL.get(backward.jid)) {
			if (forward.isDominated) {
				continue;
			}
			tryJoin(forward, backward, lp);
			if (generatedColumns.size() >= config.maxExactPricingColumns) {
				return;
			}
		}
	}

	private void tryJoin(BiLabel forward, BiLabel backward, LP lp) {
		if (forward.sequence.isEmpty() || backward.sequence.isEmpty() || forward.jid != backward.jid) {
			return;
		}
		for (int i = 1; i < backward.sequence.size(); i++) {
			if (forward.visited.contains(backward.sequence.get(i).intValue())) {
				return;
			}
		}
		double joinedDuration = forward.sourceDuration + backward.internalDuration - data.getProcessT(forward.jid);
		if (Utility.compareGt(joinedDuration, data.CmaxH)) {
			return;
		}
		ArrayList<Integer> sequence = new ArrayList<Integer>(forward.sequence);
		for (int i = 1; i < backward.sequence.size(); i++) {
			sequence.add(backward.sequence.get(i));
		}
		tryGenerateColumn(sequence, lp);
	}

	private void tryGenerateColumn(ArrayList<Integer> sequence, LP lp) {
		if (sequence.isEmpty() || generatedColumns.size() >= config.maxExactPricingColumns) {
			return;
		}
		Node node = lp.getNode();
		if (!isSequenceCompatible(sequence, node)) {
			return;
		}
		SequenceSignature signature = new SequenceSignature(sequence);
		if (activeColumnSignatures.contains(signature) || !generatedSignatures.add(signature)) {
			return;
		}
		double cost = evaluator.evaluate(sequence);
		if (Utility.isBigMValue(cost)) {
			return;
		}
		double reducedCost = reducedCost(sequence, cost, lp);
		if (Utility.compareLt(reducedCost, REDUCED_COST_TOLERANCE)) {
			generatedColumns.add(new TWETColumn(-1, sequence, data.n, cost, ColumnSource.PRICING_EXACT, false));
		}
	}

	private boolean isSequenceCompatible(ArrayList<Integer> sequence, Node node) {
		if (node.isArcForbidden(0, sequence.get(0).intValue())) {
			return false;
		}
		for (int i = 1; i < sequence.size(); i++) {
			if (node.isArcForbidden(sequence.get(i - 1).intValue(), sequence.get(i).intValue())) {
				return false;
			}
		}
		return !node.isArcForbidden(sequence.get(sequence.size() - 1).intValue(), node.sinkId());
	}

	private double reducedCost(ArrayList<Integer> sequence, double cost, LP lp) {
		double reducedCost = cost - lp.getMachineDual();
		int prev = 0;
		for (int job : sequence) {
			reducedCost -= lp.getJobDual(job);
			reducedCost -= lp.getArcDual(prev, job);
			prev = job;
		}
		reducedCost -= lp.getArcDual(prev, lp.getNode().sinkId());
		return reducedCost;
	}

	private double dominanceReducedCost(ArrayList<Integer> sequence, LP lp) {
		if (sequence.isEmpty()) {
			return 0.0;
		}
		double cost = evaluator.evaluate(sequence);
		if (Utility.isBigMValue(cost)) {
			return Utility.big_M;
		}
		double reducedCost = cost - lp.getMachineDual();
		int prev = 0;
		for (int job : sequence) {
			reducedCost -= lp.getJobDual(job);
			reducedCost -= lp.getArcDual(prev, job);
			prev = job;
		}
		return reducedCost;
	}

	private static final class BiLabel implements Comparable<BiLabel> {
		final int jid;
		final PackedBitSet visited;
		final ArrayList<Integer> sequence;
		final double sourceDuration;
		final double internalDuration;
		double dominanceReducedCost;
		boolean isDominated;

		private BiLabel(int jid, PackedBitSet visited, ArrayList<Integer> sequence, double sourceDuration,
				double internalDuration) {
			this.jid = jid;
			this.visited = visited;
			this.sequence = sequence;
			this.sourceDuration = sourceDuration;
			this.internalDuration = internalDuration;
			this.dominanceReducedCost = 0.0;
			this.isDominated = false;
		}

		static BiLabel source(PackedBitSet visited) {
			return new BiLabel(0, visited, new ArrayList<Integer>(), 0.0, 0.0);
		}

		static BiLabel sink(int sinkId, PackedBitSet visited) {
			return new BiLabel(sinkId, visited, new ArrayList<Integer>(), 0.0, 0.0);
		}

		BiLabel append(int job, double nextSourceDuration) {
			PackedBitSet nextVisited = visited.copy();
			nextVisited.add(job);
			ArrayList<Integer> nextSequence = new ArrayList<Integer>(sequence);
			nextSequence.add(Integer.valueOf(job));
			return new BiLabel(job, nextVisited, nextSequence, nextSourceDuration, nextSourceDuration);
		}

		BiLabel prepend(int job, double nextInternalDuration, double nextSourceDuration) {
			PackedBitSet nextVisited = visited.copy();
			nextVisited.add(job);
			ArrayList<Integer> nextSequence = new ArrayList<Integer>(sequence.size() + 1);
			nextSequence.add(Integer.valueOf(job));
			nextSequence.addAll(sequence);
			return new BiLabel(job, nextVisited, nextSequence, nextSourceDuration, nextInternalDuration);
		}

		@Override
		public int compareTo(BiLabel other) {
			if (Utility.compareLt(sourceDuration, other.sourceDuration)) {
				return -1;
			}
			if (Utility.compareGt(sourceDuration, other.sourceDuration)) {
				return 1;
			}
			return Integer.compare(sequence.size(), other.sequence.size());
		}
	}
}
