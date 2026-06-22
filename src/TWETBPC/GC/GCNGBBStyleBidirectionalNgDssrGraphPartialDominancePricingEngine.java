package TWETBPC.GC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import Basic.Data;
import Common.Utility;
import TWETBPC.TWETBPCConfig;
import TWETBPC.LP.LP;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.SequenceSignature;

/**
 * 基于 ng-DSSR 主体的 graph partial dominance 实验入口。
 *
 * 2026-06-11: 不复制 labeling 主流程，只切换 dominance store。ng-set 设为满集时，该入口可按
 * elementary 口径测试 graph partial dominance 的正确性与效率。
 */
public class GCNGBBStyleBidirectionalNgDssrGraphPartialDominancePricingEngine implements PricingEngine {

	private final Data data;
	private final TWETBPCConfig config;
	private CompletionBoundSubtreeArcEliminator.PreparedBounds lastReusableSubtreeArcEliminationBounds;
	private final HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse> midpointProbeReuseByNode;

	public GCNGBBStyleBidirectionalNgDssrGraphPartialDominancePricingEngine(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.midpointProbeReuseByNode = new HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse>();
	}

	@Override
	public PricingResult price(LP lp) {
		lastReusableSubtreeArcEliminationBounds = null;
		if (!config.enableBidirectionalPricing || !config.useGCNGBBStyleNgDssrGraphPartialDominancePricing) {
			return PricingResult.noImprovement("GCNGBB-style ng-DSSR graph partial-dominance pricing disabled");
		}
		GCNGBBStyleBidirectionalNgDssr gc = new GCNGBBStyleBidirectionalNgDssr(data, config,
				midpointProbeReuseByNode, true);
		ArrayList<TWETColumn> columns = gc.solve(lp);
		if (config.diagnosticCrossCheckPartialDominance && shouldRunSameStateCrossCheck(lp, !columns.isEmpty())) {
			String message = gc.getLastMessage() + runSameStateCrossCheck(lp, columns);
			if (columns.isEmpty()) {
				lastReusableSubtreeArcEliminationBounds = gc.reusableSubtreeArcEliminationBounds();
				return PricingResult.noImprovement(message)
						.withCertifiedInternalReducedCost(gc.getLastRelaxedRoundBestReducedCost());
			}
			return new PricingResult(columns, true, message)
					.withCertifiedInternalReducedCost(gc.getLastRelaxedRoundBestReducedCost());
		}
		if (columns.isEmpty()) {
			lastReusableSubtreeArcEliminationBounds = gc.reusableSubtreeArcEliminationBounds();
			return PricingResult.noImprovement(gc.getLastMessage())
					.withCertifiedInternalReducedCost(gc.getLastRelaxedRoundBestReducedCost());
		}
		return new PricingResult(columns, true, gc.getLastMessage())
				.withCertifiedInternalReducedCost(gc.getLastRelaxedRoundBestReducedCost());
	}

	/**
	 * 2026-06-12: graph-partial 返回空列时，用完全相同的 LP dual/节点/列池复跑其他 ng-DSSR
	 * dominance 后端。这里只写诊断信息，不把 cross-check 找到的列加入主问题，避免改变正式求解路径。
	 */
	private boolean shouldRunSameStateCrossCheck(LP lp, boolean graphImproved) {
		int targetNode = Integer.getInteger("twet.bpc.graphPartialCrossCheckNode",
				Integer.getInteger("twet.bpc.fullDomainCompare.graphPartialCrossCheckNode", -1));
		if (targetNode >= 0 && (lp == null || lp.getNode() == null || lp.getNode().id != targetNode)) {
			return false;
		}
		if (!graphImproved) {
			return true;
		}
		return Boolean.getBoolean("twet.bpc.graphPartialCrossCheckEveryCall")
				|| Boolean.getBoolean("twet.bpc.fullDomainCompare.graphPartialCrossCheckEveryCall");
	}

	private String runSameStateCrossCheck(LP lp, List<TWETColumn> graphColumns) {
		StringBuilder summary = new StringBuilder();
		HashSet<SequenceSignature> graphSignatures = signaturesOf(graphColumns);
		summary.append(" | ngDssrSameStateCrossCheck graph{cols=").append(graphColumns.size())
				.append(",bestRC=").append(bestReducedCost(graphColumns, lp))
				.append(",sample=").append(sampleColumns(graphColumns, lp)).append("}");
		appendBackendCheck(summary, "paper", lp, graphSignatures,
				GCNGBBStyleBidirectionalNgDssr.DominanceBackend.PAPER);
		appendBackendCheck(summary, "listPartial", lp, graphSignatures,
				GCNGBBStyleBidirectionalNgDssr.DominanceBackend.LIST_PARTIAL);
		return summary.toString();
	}

	private void appendBackendCheck(StringBuilder summary, String label, LP lp, HashSet<SequenceSignature> graphSignatures,
			GCNGBBStyleBidirectionalNgDssr.DominanceBackend backend) {
		long begin = System.nanoTime();
		GCNGBBStyleBidirectionalNgDssr checker = new GCNGBBStyleBidirectionalNgDssr(data, config,
				new HashMap<Integer, GCNGBBStyleBidirectionalNgDssr.MidpointProbeNodeReuse>(), backend);
		ArrayList<TWETColumn> columns = checker.solve(lp);
		double elapsedMs = (System.nanoTime() - begin) / 1_000_000.0;
		summary.append(" ").append(label).append("{cols=").append(columns.size())
				.append(",bestRC=").append(bestReducedCost(columns, lp))
				.append(",missingFromGraph=").append(countMissing(columns, graphSignatures))
				.append(",missingSample=").append(sampleMissingColumns(columns, graphSignatures, lp))
				.append(",ms=").append(elapsedMs)
				.append(",sample=").append(sampleColumns(columns, lp))
				.append(",msg=").append(checker.getLastMessage()).append("}");
	}

	private HashSet<SequenceSignature> signaturesOf(List<TWETColumn> columns) {
		HashSet<SequenceSignature> signatures = new HashSet<SequenceSignature>();
		for (TWETColumn column : columns) {
			signatures.add(column.getSignature());
		}
		return signatures;
	}

	private int countMissing(List<TWETColumn> columns, HashSet<SequenceSignature> graphSignatures) {
		int missing = 0;
		for (TWETColumn column : columns) {
			if (!graphSignatures.contains(column.getSignature())) {
				missing++;
			}
		}
		return missing;
	}

	private double bestReducedCost(List<TWETColumn> columns, LP lp) {
		double best = Utility.big_M;
		for (TWETColumn column : columns) {
			double reducedCost = reducedCost(column, lp);
			if (Utility.compareLt(reducedCost, best)) {
				best = reducedCost;
			}
		}
		return best;
	}

	private String sampleColumns(List<TWETColumn> columns, LP lp) {
		StringBuilder sample = new StringBuilder("[");
		int limit = Math.min(3, columns.size());
		for (int i = 0; i < limit; i++) {
			if (i > 0) {
				sample.append(";");
			}
			TWETColumn column = columns.get(i);
			sample.append("rc=").append(reducedCost(column, lp)).append(":").append(column.getSequence());
		}
		return sample.append("]").toString();
	}

	private String sampleMissingColumns(List<TWETColumn> columns, HashSet<SequenceSignature> graphSignatures, LP lp) {
		StringBuilder sample = new StringBuilder("[");
		int limit = Integer.getInteger("twet.bpc.graphPartialCrossCheckSample",
				Integer.getInteger("twet.bpc.fullDomainCompare.graphPartialCrossCheckSample", 3));
		int count = 0;
		for (TWETColumn column : columns) {
			if (graphSignatures.contains(column.getSignature())) {
				continue;
			}
			if (count > 0) {
				sample.append(";");
			}
			sample.append("rc=").append(reducedCost(column, lp)).append(":").append(column.getSequence());
			count++;
			if (count >= limit) {
				break;
			}
		}
		return sample.append("]").toString();
	}

	private double reducedCost(TWETColumn column, LP lp) {
		double reducedCost = column.getCost() - lp.getMachineDual();
		int previous = 0;
		for (int job : column.getSequence()) {
			reducedCost -= lp.getJobDual(job);
			reducedCost -= lp.getArcDual(previous, job);
			previous = job;
		}
		reducedCost -= lp.getArcDual(previous, lp.getNode().sinkId());
		return reducedCost;
	}

	@Override
	public CompletionBoundSubtreeArcEliminator.PreparedBounds getReusableSubtreeArcEliminationBounds() {
		return lastReusableSubtreeArcEliminationBounds;
	}

	@Override
	public void reset() {
		lastReusableSubtreeArcEliminationBounds = null;
		if (!config.bidirectionalMidpointProbeReuseWithinNode) {
			midpointProbeReuseByNode.clear();
		}
	}

	@Override
	public String getName() {
		return "GCNGBBStyleNgDssrGraphPartialDominancePricing";
	}
}
