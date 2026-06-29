package TWETBPC.LP;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import Basic.Data;
import Common.PiecewiseLinearFunction;
import Common.PiecewiseLinearFunction.Direction;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.GC.CompletionBoundSubtreeArcEliminator;
import TWETBPC.IO.TWETColumnEvaluator;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETOutsourcingColumn;
import TWETBPC.Util.PackedBitSet;
import TWETBPC.Util.SequenceSignature;

/**
 * 节点 LP 完全定价闭合后的 route enumeration。
 * <p>
 * 该实现只在没有 active SRI cut、非 time-indexed pricing、gap 足够小时启用。内部机器列按 elementary
 * full-domain 序列枚举；列化外包模式会同步枚举外包列。枚举达到上限时不关闭节点。
 */
public final class RouteEnumerationEngine {

	private static final double REDUCED_COST_TOLERANCE = 1e-8;

	private final Data data;
	private final TWETBPCConfig config;
	private final TWETColumnEvaluator evaluator;

	public RouteEnumerationEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.evaluator = new TWETColumnEvaluator(data);
	}

	public RouteEnumerationResult enumerate(LP lp, double incumbentCost, double nodeLowerBound,
			CompletionBoundSubtreeArcEliminator.PreparedBounds preparedBounds,
			CompletionBoundSubtreeArcEliminator.Result currentNodeArcElimination) {
		long start = System.nanoTime();
		String skipReason = skipReason(lp, incumbentCost, nodeLowerBound);
		if (skipReason != null) {
			return RouteEnumerationResult.skipped(skipReason);
		}

		double gap = incumbentCost - nodeLowerBound;
		Node node = lp.getNode();
		boolean[][] currentFixedArcs = buildCurrentFixedArcMask(node, currentNodeArcElimination);
		LP.PricingDualSnapshot dual = lp.captureTruePricingDuals();
		LinkedHashSet<Integer> finiteIds = new LinkedHashSet<Integer>(lp.getRestrictedColumnIds());
		LinkedHashSet<Integer> finiteOutsourcingIds =
				new LinkedHashSet<Integer>(lp.getRestrictedOutsourcingColumnIds());
		if (finiteIds.size() + finiteOutsourcingIds.size() > config.routeEnumerationColumnLimit) {
			return RouteEnumerationResult.incomplete("active RMP column count exceeds limit",
					new ArrayList<Integer>(finiteIds), new ArrayList<Integer>(finiteOutsourcingIds), 0, 0, 0, 0, 0,
					0, 0, 0, 0, System.nanoTime() - start);
		}
		HashSet<Integer> activeIds = new HashSet<Integer>(lp.getRestrictedColumnIds());
		HashSet<Integer> activeOutsourcingIds = new HashSet<Integer>(lp.getRestrictedOutsourcingColumnIds());
		HashSet<SequenceSignature> runSignatures = new HashSet<SequenceSignature>();
		HashSet<SequenceSignature> outsourcingRunSignatures = new HashSet<SequenceSignature>();
		ArrayList<PendingColumn> pending = new ArrayList<PendingColumn>();
		ArrayList<TWETOutsourcingColumn> pendingOutsourcing = new ArrayList<TWETOutsourcingColumn>();
		boolean useCompactWindow = config.routeEnumerationUseTimeIndexedWindow
				&& node.countTimeIndexedPricingWindowTightenedJobs() > 0;
		PiecewiseLinearFunction[] jobPenalties = buildJobPenaltyCache(node, useCompactWindow);
		long nextSerial = 0L;
		State root = buildRootState(node, dual, jobPenalties, nextSerial++);
		if (root == null) {
			return RouteEnumerationResult.incomplete("empty source frontier", new ArrayList<Integer>(finiteIds),
					new ArrayList<Integer>(finiteOutsourcingIds), 0, 0, 0, 0, 0, 0, 0, 0, 0,
					System.nanoTime() - start);
		}
		ArrayDeque<State> queue = new ArrayDeque<State>();
		queue.add(root);

		int states = 0;
		int candidates = 0;
		int outsourcingCandidates = 0;
		int newColumns = 0;
		int newOutsourcingColumns = 0;
		int duplicateActive = 0;
		int duplicatePool = 0;
		int duplicateRun = 0;
		int cbPruned = 0;

		while (!queue.isEmpty()) {
			State state = queue.poll();
			states++;
			if (!state.sequence.isEmpty() && canUseArc(node, currentFixedArcs, state.lastJob, node.sinkId())) {
				double reducedCost = completeReducedCost(state, dual, node.sinkId());
				ColumnCheck check = checkColumn(lp, state.sequence, reducedCost, dual, gap, activeIds,
						runSignatures, useCompactWindow);
				if (check.negativeEnough) {
					candidates++;
					if (check.activeDuplicate) {
						duplicateActive++;
					} else if (check.existingColumnId >= 0) {
						if (finiteIds.add(Integer.valueOf(check.existingColumnId))) {
							duplicatePool++;
						}
					} else if (check.runDuplicate) {
						duplicateRun++;
					} else {
						pending.add(new PendingColumn(state.sequence, check.cost));
						newColumns++;
					}
					if (finiteIds.size() + pending.size() + finiteOutsourcingIds.size()
							+ pendingOutsourcing.size() > config.routeEnumerationColumnLimit) {
						return RouteEnumerationResult.incomplete("column limit exceeded",
								new ArrayList<Integer>(finiteIds), new ArrayList<Integer>(finiteOutsourcingIds),
								states, candidates, outsourcingCandidates, newColumns, newOutsourcingColumns,
								duplicateActive, duplicatePool, duplicateRun, cbPruned, System.nanoTime() - start);
					}
				}
			}

			for (int job = state.reachableSet.nextSetBit(1); job > 0 && job <= data.n;
					job = state.reachableSet.nextSetBit(job + 1)) {
				if (!canUseArc(node, currentFixedArcs, state.lastJob, job)) {
					continue;
				}
				State child = extendState(state, job, nextSerial++, dual, jobPenalties, node);
				if (child == null || Utility.isBigMValue(child.minReducedCost)) {
					continue;
				}
				if (canPruneByCompletionBound(preparedBounds, child, gap)) {
					cbPruned++;
					continue;
				}
				queue.add(child);
			}
		}

		if (lp.isColumnizedOutsourcing()) {
			OutsourcingEnumeration outsourcing = enumerateOutsourcingColumns(lp, dual, gap, activeOutsourcingIds,
					outsourcingRunSignatures);
			cbPruned += outsourcing.suffixBoundPruned;
			outsourcingCandidates = outsourcing.candidates;
			newOutsourcingColumns = outsourcing.newColumns.size();
			if (outsourcing.incomplete) {
				return RouteEnumerationResult.incomplete(outsourcing.message,
						new ArrayList<Integer>(finiteIds), new ArrayList<Integer>(finiteOutsourcingIds), states,
						candidates, outsourcingCandidates, newColumns, newOutsourcingColumns, duplicateActive,
						duplicatePool, duplicateRun, cbPruned, System.nanoTime() - start);
			}
			finiteOutsourcingIds.addAll(outsourcing.existingIds);
			pendingOutsourcing.addAll(outsourcing.newColumns);
			if (finiteIds.size() + pending.size() + finiteOutsourcingIds.size()
					+ pendingOutsourcing.size() > config.routeEnumerationColumnLimit) {
				return RouteEnumerationResult.incomplete("column limit exceeded after outsourcing enumeration",
						new ArrayList<Integer>(finiteIds), new ArrayList<Integer>(finiteOutsourcingIds), states,
						candidates, outsourcingCandidates, newColumns, newOutsourcingColumns, duplicateActive,
						duplicatePool, duplicateRun, cbPruned, System.nanoTime() - start);
			}
		}

		for (PendingColumn column : pending) {
			int id = lp.getPool().addColumn(column.sequence, column.cost, ColumnSource.ROUTE_ENUMERATION, false);
			finiteIds.add(Integer.valueOf(id));
		}
		for (TWETOutsourcingColumn column : pendingOutsourcing) {
			int id = lp.getOutsourcingPool().addColumn(column);
			if (id >= 0) {
				finiteOutsourcingIds.add(Integer.valueOf(id));
			}
		}
		return RouteEnumerationResult.complete("enumeration complete, gap=" + gap, new ArrayList<Integer>(finiteIds),
				new ArrayList<Integer>(finiteOutsourcingIds), states, candidates, outsourcingCandidates, newColumns,
				newOutsourcingColumns, duplicateActive, duplicatePool, duplicateRun, cbPruned,
				System.nanoTime() - start);
	}

	private String skipReason(LP lp, double incumbentCost, double nodeLowerBound) {
		if (!config.enableRouteEnumeration) {
			return "disabled";
		}
		if (lp.getNode() == null) {
			return "no node";
		}
		if (!Double.isFinite(incumbentCost) || !Double.isFinite(nodeLowerBound)) {
			return "no finite incumbent or lower bound";
		}
		if (config.useTimeIndexedGraphPricing) {
			return "time-indexed graph pricing uses non-elementary columns";
		}
		double gap = incumbentCost - nodeLowerBound;
		double triggerGap = effectiveAbsoluteGapThreshold();
		if (Utility.compareLe(gap, 0.0) || Utility.compareGe(gap, triggerGap)) {
			return "gap outside trigger: " + gap + " >= " + triggerGap;
		}
		if (!lp.getActiveCutIds().isEmpty()) {
			return "active cuts are incompatible";
		}
		return null;
	}

	private double effectiveAbsoluteGapThreshold() {
		// 2026-06-26: 50 任务及以上实例上，gap=8~10 仍可能在 route enumeration
		// 的 completion-bound PWLF 检查中产生百万级临时函数段，先收紧触发阈值做实验。
		if (data.n >= 50) {
			return Math.min(config.routeEnumerationAbsoluteGapThreshold, 5.0);
		}
		return config.routeEnumerationAbsoluteGapThreshold;
	}

	private OutsourcingEnumeration enumerateOutsourcingColumns(LP lp, LP.PricingDualSnapshot dual, double gap,
			HashSet<Integer> activeOutsourcingIds, HashSet<SequenceSignature> outsourcingRunSignatures) {
		Node node = lp.getNode();
		OutsourcingEnumeration result = new OutsourcingEnumeration();
		// 2026-06-25: 外包枚举保留所有覆盖集合；required job 只作为公共常数，最终合并进候选列。
		ArrayList<Integer> requiredJobs = new ArrayList<Integer>();
		double requiredBaseline = 0.0;
		double requiredProfit = 0.0;
		ArrayList<Integer> freeJobs = new ArrayList<Integer>();
		for (int job = 1; job <= data.n; job++) {
			if (!lp.getOutsourcingPool().isOutsourceable(job)) {
				continue;
			}
			byte state = node.getOutsourcingJobState(job);
			if (state == Node.OUTSOURCE_FORBIDDEN) {
				continue;
			}
			if (state == Node.OUTSOURCE_REQUIRED) {
				requiredJobs.add(Integer.valueOf(job));
				requiredBaseline += data.outsourcingCost[job];
				requiredProfit += dual.jobDual[job];
			} else {
				freeJobs.add(Integer.valueOf(job));
			}
		}
		double[] freeBaselinePrefix = buildOutsourcingFreeBaselinePrefix(freeJobs);
		double[] cheapSuffixBound = config.routeEnumerationUseCompletionBound
				? buildOutsourcingCheapSuffixBound(requiredBaseline, freeJobs, freeBaselinePrefix, dual)
				: null;

		ArrayList<OutsourcingLabel> labels = new ArrayList<OutsourcingLabel>();
		labels.add(new OutsourcingLabel());
		for (int idx = 0; idx < freeJobs.size(); idx++) {
			int job = freeJobs.get(idx).intValue();
			ArrayList<OutsourcingLabel> next = new ArrayList<OutsourcingLabel>(labels.size() * 2);
			for (OutsourcingLabel label : labels) {
				addOutsourcingEnumerationLabel(next, label, requiredBaseline, requiredProfit, idx + 1, freeJobs,
						freeBaselinePrefix, cheapSuffixBound, dual, gap, result);
				OutsourcingLabel included = label.include(job, data.outsourcingCost[job], dual.jobDual[job]);
				addOutsourcingEnumerationLabel(next, included, requiredBaseline, requiredProfit, idx + 1, freeJobs,
						freeBaselinePrefix, cheapSuffixBound, dual, gap, result);
			}
			if (next.size() > config.routeEnumerationColumnLimit) {
				result.incomplete = true;
				result.message = "outsourcing enumeration label limit exceeded";
				return result;
			}
			labels = next;
		}

		for (OutsourcingLabel label : labels) {
			if (requiredJobs.isEmpty() && label.jobs.isEmpty()) {
				continue;
			}
			double baseline = requiredBaseline + label.baseline;
			double profit = requiredProfit + label.profit;
			double cost = data.evaluateOutsourcingCost(baseline);
			if (Utility.isBigMValue(cost)) {
				continue;
			}
			double reducedCost = cost - profit - dual.outsourcingColumnDual;
			if (!Utility.compareLt(reducedCost, gap - REDUCED_COST_TOLERANCE)) {
				continue;
			}
			result.candidates++;
			ArrayList<Integer> jobs = mergeOutsourcingJobs(requiredJobs, label.jobs);
			SequenceSignature signature = new SequenceSignature(jobs);
			int existingId = lp.getOutsourcingPool().getColumnIdBySignature(signature);
			if (existingId >= 0) {
				if (!activeOutsourcingIds.contains(Integer.valueOf(existingId))) {
					result.existingIds.add(Integer.valueOf(existingId));
				}
				continue;
			}
			if (!outsourcingRunSignatures.add(signature)) {
				continue;
			}
			result.newColumns.add(new TWETOutsourcingColumn(-1, jobs, data.n, baseline, cost,
					ColumnSource.ROUTE_ENUMERATION, false));
		}
		return result;
	}

	private double[] buildOutsourcingFreeBaselinePrefix(List<Integer> freeJobs) {
		double[] prefix = new double[freeJobs.size() + 1];
		for (int idx = 0; idx < freeJobs.size(); idx++) {
			prefix[idx + 1] = prefix[idx] + data.outsourcingCost[freeJobs.get(idx).intValue()];
		}
		return prefix;
	}

	private double[] buildOutsourcingCheapSuffixBound(double requiredBaseline, List<Integer> freeJobs,
			double[] freeBaselinePrefix, LP.PricingDualSnapshot dual) {
		double[] suffix = new double[freeJobs.size() + 1];
		for (int idx = freeJobs.size() - 1; idx >= 0; idx--) {
			int job = freeJobs.get(idx).intValue();
			double maxStartBeforeJob = requiredBaseline + freeBaselinePrefix[idx];
			double before = data.evaluateOutsourcingCost(maxStartBeforeJob);
			double after = data.evaluateOutsourcingCost(maxStartBeforeJob + data.outsourcingCost[job]);
			double contribution = 0.0;
			if (!Utility.isBigMValue(before) && !Utility.isBigMValue(after)) {
				// 2026-06-25: cheap bound 对每个 job 只预处理一次。用“它前面的 free job 全已外包”
				// 得到最靠后的加入位置；在 G 边际斜率不增时，这是该 job 最乐观的成本增量。
				contribution = after - before - dual.jobDual[job];
				if (Utility.compareGt(contribution, 0.0)) {
					contribution = 0.0;
				}
			}
			suffix[idx] = suffix[idx + 1] + contribution;
		}
		return suffix;
	}

	private void addOutsourcingEnumerationLabel(ArrayList<OutsourcingLabel> next, OutsourcingLabel label,
			double requiredBaseline, double requiredProfit, int nextIndex, List<Integer> freeJobs,
			double[] freeBaselinePrefix, double[] cheapSuffixBound, LP.PricingDualSnapshot dual, double gap,
			OutsourcingEnumeration result) {
		if (canPruneOutsourcingBySuffixBound(label, requiredBaseline, requiredProfit, nextIndex, freeJobs,
				freeBaselinePrefix, cheapSuffixBound, dual, gap)) {
			result.suffixBoundPruned++;
			return;
		}
		next.add(label);
	}

	private boolean canPruneOutsourcingBySuffixBound(OutsourcingLabel label, double requiredBaseline,
			double requiredProfit, int nextIndex, List<Integer> freeJobs, double[] freeBaselinePrefix,
			double[] cheapSuffixBound, LP.PricingDualSnapshot dual, double gap) {
		if (!config.routeEnumerationUseCompletionBound) {
			return false;
		}
		if (cheapSuffixBound == null) {
			return false;
		}
		double baseline = requiredBaseline + label.baseline;
		double profit = requiredProfit + label.profit;
		double currentCost = data.evaluateOutsourcingCost(baseline);
		if (Utility.isBigMValue(currentCost)) {
			return false;
		}
		double currentReducedCost = currentCost - profit - dual.outsourcingColumnDual;
		double cheapLowerBound = currentReducedCost + cheapSuffixBound[nextIndex];
		if (Utility.compareGe(cheapLowerBound, gap - REDUCED_COST_TOLERANCE)) {
			return true;
		}
		if (!config.routeEnumerationUseExactOutsourcingSuffixBound) {
			return false;
		}
		double lowerBound = currentReducedCost;
		for (int idx = nextIndex; idx < freeJobs.size(); idx++) {
			int job = freeJobs.get(idx).intValue();
			double previousFreeBaseline = freeBaselinePrefix[idx] - freeBaselinePrefix[nextIndex];
			double marginalStart = baseline + previousFreeBaseline;
			double before = data.evaluateOutsourcingCost(marginalStart);
			double after = data.evaluateOutsourcingCost(marginalStart + data.outsourcingCost[job]);
			if (Utility.isBigMValue(before) || Utility.isBigMValue(after)) {
				return false;
			}
			// 2026-06-25: G 单调且边际斜率不增时，把前序剩余 job 全放在 job 前面得到安全的最小边际成本。
			double optimisticContribution = after - before - dual.jobDual[job];
			if (Utility.compareLt(optimisticContribution, 0.0)) {
				lowerBound += optimisticContribution;
			}
		}
		return Utility.compareGe(lowerBound, gap - REDUCED_COST_TOLERANCE);
	}

	private ArrayList<Integer> mergeOutsourcingJobs(List<Integer> requiredJobs, List<Integer> freeLabelJobs) {
		ArrayList<Integer> merged = new ArrayList<Integer>(requiredJobs.size() + freeLabelJobs.size());
		int requiredIdx = 0;
		int freeIdx = 0;
		while (requiredIdx < requiredJobs.size() || freeIdx < freeLabelJobs.size()) {
			if (freeIdx >= freeLabelJobs.size()) {
				merged.add(requiredJobs.get(requiredIdx++));
			} else if (requiredIdx >= requiredJobs.size()) {
				merged.add(freeLabelJobs.get(freeIdx++));
			} else {
				int requiredJob = requiredJobs.get(requiredIdx).intValue();
				int freeJob = freeLabelJobs.get(freeIdx).intValue();
				if (requiredJob < freeJob) {
					merged.add(Integer.valueOf(requiredJob));
					requiredIdx++;
				} else if (freeJob < requiredJob) {
					merged.add(Integer.valueOf(freeJob));
					freeIdx++;
				} else {
					merged.add(Integer.valueOf(requiredJob));
					requiredIdx++;
					freeIdx++;
				}
			}
		}
		return merged;
	}

	private State buildRootState(Node node, LP.PricingDualSnapshot dual, PiecewiseLinearFunction[] jobPenalties,
			long serial) {
		PiecewiseLinearFunction frontier = jobPenalties[0].copy();
		if (frontier.head == null) {
			return null;
		}
		frontier.shiftYInPlace(-dual.machineDual);
		frontier.normalize(Direction.FORWARD);
		if (frontier.head == null) {
			return null;
		}
		boolean[] used = new boolean[data.n + 1];
		PackedBitSet reachableSet = buildForwardReachableSet(0, used, node, frontier, jobPenalties);
		return State.root(frontier, forwardEndpointMin(frontier), serial, used, reachableSet);
	}

	private State extendState(State state, int job, long serial, LP.PricingDualSnapshot dual,
			PiecewiseLinearFunction[] jobPenalties, Node node) {
		double delay = data.getSetUp(state.lastJob, job) + data.getProcessT(job);
		PiecewiseLinearFunction shifted = state.frontier.shiftX(delay);
		if (shifted.head == null) {
			return null;
		}
		PiecewiseLinearFunction jobPenalty = jobPenalties[job];
		if (jobPenalty.head == null) {
			return null;
		}
		PiecewiseLinearFunction nextFrontier = shifted.add(jobPenalty);
		if (nextFrontier.head == null) {
			return null;
		}
		double fixedReducedCost = data.getSetupCost(state.lastJob, job) - dual.jobDual[job]
				- dual.arcDual[state.lastJob][job];
		nextFrontier.shiftYInPlace(fixedReducedCost);
		nextFrontier.normalize(Direction.FORWARD);
		if (nextFrontier.head == null) {
			return null;
		}
		boolean[] nextUsed = state.used.clone();
		nextUsed[job] = true;
		PackedBitSet reachableSet = buildForwardReachableSetFromParent(state, job, nextUsed, node, nextFrontier,
				jobPenalties);
		return state.extend(job, nextUsed, reachableSet, nextFrontier, forwardEndpointMin(nextFrontier), serial);
	}

	private PackedBitSet buildForwardReachableSet(int fromJob, boolean[] used, Node node,
			PiecewiseLinearFunction frontier, PiecewiseLinearFunction[] jobPenalties) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		for (int job = 1; job <= data.n; job++) {
			if (!used[job] && !isRequiredOutsourced(node, job)
					&& isDirectForwardExtensionTimeFeasible(frontier, fromJob, job, jobPenalties)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private PackedBitSet buildForwardReachableSetFromParent(State parent, int fromJob, boolean[] used, Node node,
			PiecewiseLinearFunction frontier, PiecewiseLinearFunction[] jobPenalties) {
		PackedBitSet reachable = new PackedBitSet(data.n + 2);
		for (int job = parent.reachableSet.nextSetBit(1); job > 0 && job <= data.n;
				job = parent.reachableSet.nextSetBit(job + 1)) {
			if (!used[job] && !isRequiredOutsourced(node, job)
					&& isDirectForwardExtensionTimeFeasible(frontier, fromJob, job, jobPenalties)) {
				reachable.add(job);
			}
		}
		return reachable;
	}

	private boolean isDirectForwardExtensionTimeFeasible(PiecewiseLinearFunction frontier, int prevJob, int nextJob,
			PiecewiseLinearFunction[] jobPenalties) {
		if (frontier == null || frontier.head == null) {
			return false;
		}
		PiecewiseLinearFunction jobPenalty = jobPenalties[nextJob];
		if (jobPenalty == null || jobPenalty.head == null || jobPenalty.tail == null) {
			return false;
		}
		double earliestCompletion = Math.max(
				frontier.head.start + data.getSetUp(prevJob, nextJob) + data.getProcessT(nextJob),
				jobPenalty.head.start);
		return !Utility.compareGt(earliestCompletion, jobPenalty.tail.end);
	}

	private double completeReducedCost(State state, LP.PricingDualSnapshot dual, int sink) {
		return state.minReducedCost - dual.arcDual[state.lastJob][sink];
	}

	private PiecewiseLinearFunction[] buildJobPenaltyCache(Node node, boolean useCompactWindow) {
		PiecewiseLinearFunction[] penalties = new PiecewiseLinearFunction[data.n + 1];
		for (int job = 0; job <= data.n; job++) {
			penalties[job] = buildEnumerationPenalty(job, node, useCompactWindow);
		}
		return penalties;
	}

	private PiecewiseLinearFunction buildEnumerationPenalty(int job, Node node, boolean useCompactWindow) {
		PiecewiseLinearFunction base = data.penaltyFunction[job];
		if (!useCompactWindow || job == 0 || node == null || !node.hasTimeIndexedPricingWindow(job)) {
			return cropToHorizon(base);
		}
		double hStart = Math.max(data.hardWindowStart[job], node.getTimeIndexedPricingWindowStart(job));
		double hEnd = Math.min(data.hardWindowEnd[job], node.getTimeIndexedPricingWindowEnd(job));
		// 2026-06-29: 只把 node 继承的 compact window 用作枚举剪枝窗口。
		// 新列入池前仍按真实 objective 重算成本，避免把窗口内受限成本写成全局列成本。
		if (Utility.compareGt(hStart, hEnd)) {
			PiecewiseLinearFunction empty = new PiecewiseLinearFunction();
			empty.resetDomain(0.0, data.CmaxH);
			return empty;
		}
		return cropToHorizon(base.setDomain(hStart, hEnd, true));
	}

	private PiecewiseLinearFunction cropToHorizon(PiecewiseLinearFunction function) {
		PiecewiseLinearFunction cropped = new PiecewiseLinearFunction();
		cropped.resetDomain(0.0, data.CmaxH);
		if (function == null || function.head == null) {
			return cropped;
		}
		for (PiecewiseLinearFunction.Segment segment = function.head; segment != null; segment = segment.next) {
			double start = Math.max(segment.start, 0.0);
			double end = Math.min(segment.end, data.CmaxH);
			if (Utility.compareLt(start, end)) {
				cropped.addSegment(start, end, segment.slope, segment.intercept);
			}
		}
		return cropped;
	}

	private static double forwardEndpointMin(PiecewiseLinearFunction frontier) {
		if (frontier == null || frontier.tail == null) {
			return Utility.big_M;
		}
		return frontier.tail.getValue(frontier.tail.end);
	}

	private ColumnCheck checkColumn(LP lp, List<Integer> sequence, double reducedCost,
			LP.PricingDualSnapshot dual, double gap, HashSet<Integer> activeIds,
			HashSet<SequenceSignature> runSignatures, boolean windowedEnumeration) {
		if (!Utility.compareLt(reducedCost, gap - REDUCED_COST_TOLERANCE)) {
			return ColumnCheck.newColumn(false, Double.NaN);
		}
		SequenceSignature signature = new SequenceSignature(sequence);
		int existingId = lp.getPool().getColumnIdBySignature(signature);
		if (existingId >= 0) {
			if (activeIds.contains(Integer.valueOf(existingId))) {
				return ColumnCheck.activeDuplicate(true);
			}
			return ColumnCheck.existing(true, existingId);
		}
		if (!runSignatures.add(signature)) {
			return ColumnCheck.runDuplicate();
		}
		double cost = objectiveCostFromReducedCost(sequence, reducedCost, dual, lp.getNode());
		if (windowedEnumeration) {
			cost = evaluator.evaluate(sequence);
			if (Utility.isBigMValue(cost)) {
				return ColumnCheck.newColumn(false, Double.NaN);
			}
		}
		return ColumnCheck.newColumn(true, cost);
	}

	private double objectiveCostFromReducedCost(List<Integer> sequence, double reducedCost,
			LP.PricingDualSnapshot dual, Node node) {
		double cost = reducedCost + dual.machineDual;
		int prev = 0;
		for (int job : sequence) {
			cost += dual.jobDual[job];
			cost += dual.arcDual[prev][job];
			prev = job;
		}
		cost += dual.arcDual[prev][node.sinkId()];
		return cost;
	}

	private boolean canUseArc(Node node, boolean[][] currentFixedArcs, int from, int to) {
		return !node.isArcForbidden(from, to) && !node.isPricingOnlyArcForbidden(from, to)
				&& !isCurrentNodeFixedArc(currentFixedArcs, from, to);
	}

	private boolean[][] buildCurrentFixedArcMask(Node node,
			CompletionBoundSubtreeArcEliminator.Result currentNodeArcElimination) {
		if (node == null || currentNodeArcElimination == null || !currentNodeArcElimination.isAvailable()) {
			return null;
		}
		List<int[]> fixedArcs = currentNodeArcElimination.getFixedArcs();
		if (fixedArcs.isEmpty()) {
			return null;
		}
		boolean[][] mask = new boolean[data.n + 2][data.n + 2];
		for (int[] arc : fixedArcs) {
			int from = arc[0];
			int to = arc[1];
			if (from < 0 || from >= mask.length || to < 0 || to >= mask[from].length) {
				continue;
			}
			if (node.getArcState(from, to) == Node.ARC_REQUIRED
					|| node.getAdjacencyPairState(from, to) == Node.ADJACENCY_REQUIRED
					|| node.isArcForbidden(from, to) || node.isPricingOnlyArcForbidden(from, to)) {
				continue;
			}
			mask[from][to] = true;
		}
		return mask;
	}

	private boolean isCurrentNodeFixedArc(boolean[][] currentFixedArcs, int from, int to) {
		return currentFixedArcs != null && from >= 0 && from < currentFixedArcs.length
				&& to >= 0 && to < currentFixedArcs[from].length && currentFixedArcs[from][to];
	}

	private boolean canPruneByCompletionBound(CompletionBoundSubtreeArcEliminator.PreparedBounds preparedBounds,
			State state, double gap) {
		if (!config.routeEnumerationUseCompletionBound || preparedBounds == null || state.lastJob <= 0) {
			return false;
		}
		return preparedBounds.canPruneForwardLabel(state.lastJob, state.frontier, state.minReducedCost, gap,
				config.bidirectionalCompletionBoundScalarPruning);
	}

	private boolean isRequiredOutsourced(Node node, int job) {
		return node.getOutsourcingJobState(job) == Node.OUTSOURCE_REQUIRED;
	}

	private static final class State {
		final int lastJob;
		final ArrayList<Integer> sequence;
		final boolean[] used;
		final PackedBitSet reachableSet;
		final PiecewiseLinearFunction frontier;
		final double minReducedCost;
		final long serial;

		static State root(PiecewiseLinearFunction frontier, double minReducedCost, long serial, boolean[] used,
				PackedBitSet reachableSet) {
			return new State(0, new ArrayList<Integer>(), used, reachableSet, frontier, minReducedCost, serial);
		}

		State(int lastJob, ArrayList<Integer> sequence, boolean[] used, PackedBitSet reachableSet,
				PiecewiseLinearFunction frontier, double minReducedCost, long serial) {
			this.lastJob = lastJob;
			this.sequence = sequence;
			this.used = used;
			this.reachableSet = reachableSet;
			this.frontier = frontier;
			this.minReducedCost = minReducedCost;
			this.serial = serial;
		}

		State extend(int job, boolean[] nextUsed, PackedBitSet reachableSet, PiecewiseLinearFunction frontier,
				double minReducedCost, long serial) {
			ArrayList<Integer> nextSequence = new ArrayList<Integer>(sequence);
			nextSequence.add(Integer.valueOf(job));
			return new State(job, nextSequence, nextUsed, reachableSet, frontier, minReducedCost, serial);
		}
	}

	private static final class OutsourcingEnumeration {
		final ArrayList<Integer> existingIds = new ArrayList<Integer>();
		final ArrayList<TWETOutsourcingColumn> newColumns = new ArrayList<TWETOutsourcingColumn>();
		int candidates;
		int suffixBoundPruned;
		boolean incomplete;
		String message;
	}

	private static final class OutsourcingLabel {
		final ArrayList<Integer> jobs;
		final double baseline;
		final double profit;

		OutsourcingLabel() {
			this.jobs = new ArrayList<Integer>();
			this.baseline = 0.0;
			this.profit = 0.0;
		}

		OutsourcingLabel(ArrayList<Integer> jobs, double baseline, double profit) {
			this.jobs = jobs;
			this.baseline = baseline;
			this.profit = profit;
		}

		OutsourcingLabel include(int job, double baselineDelta, double profitDelta) {
			ArrayList<Integer> nextJobs = new ArrayList<Integer>(jobs);
			nextJobs.add(Integer.valueOf(job));
			return new OutsourcingLabel(nextJobs, baseline + baselineDelta, profit + profitDelta);
		}

	}

	private static final class PendingColumn {
		final ArrayList<Integer> sequence;
		final double cost;

		PendingColumn(List<Integer> sequence, double cost) {
			this.sequence = new ArrayList<Integer>(sequence);
			this.cost = cost;
		}
	}

	private static final class ColumnCheck {
		final boolean negativeEnough;
		final boolean activeDuplicate;
		final boolean runDuplicate;
		final int existingColumnId;
		final double cost;

		static ColumnCheck activeDuplicate(boolean negativeEnough) {
			return new ColumnCheck(negativeEnough, true, false, -1, Double.NaN);
		}

		static ColumnCheck runDuplicate() {
			return new ColumnCheck(true, false, true, -1, Double.NaN);
		}

		static ColumnCheck existing(boolean negativeEnough, int existingColumnId) {
			return new ColumnCheck(negativeEnough, false, false, existingColumnId, Double.NaN);
		}

		static ColumnCheck newColumn(boolean negativeEnough, double cost) {
			return new ColumnCheck(negativeEnough, false, false, -1, cost);
		}

		ColumnCheck(boolean negativeEnough, boolean activeDuplicate, boolean runDuplicate, int existingColumnId,
				double cost) {
			this.negativeEnough = negativeEnough;
			this.activeDuplicate = activeDuplicate;
			this.runDuplicate = runDuplicate;
			this.existingColumnId = existingColumnId;
			this.cost = cost;
		}
	}
}
