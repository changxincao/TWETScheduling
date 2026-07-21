package TWETBPC.LP;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Basic.Data;
import Output.BPCTraceSummary;
import TWETBPC.TWETBPCConfig;
import TWETBPC.TWETBPCContext;
import TWETBPC.BP.StrongBranchingCandidate;
import TWETBPC.LP.PC.StrongBranchingTrialResult;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETMasterSolution;
import TWETBPC.Model.TWETOutsourcingColumn;

/**
 * 固定同一父节点、分支侧和列池快照，按 old/Phase-I/Phase-I/old 隔离复放 strong repair。
 * 仅在显式设置 twet.bpc.strongRepairReplayParentNode 时触发，不进入默认求解路径。
 */
final class StrongRepairFixedSideReplay {

	private static boolean completed;

	private StrongRepairFixedSideReplay() {
	}
	static synchronized void runIfRequested(Data data, TWETBPCConfig baseConfig, Pool sourcePool,
			OutsourcingPool sourceOutsourcingPool, CutPool sourceCutPool, LP parentLp,
			StrongBranchingCandidate candidate, String side, Node preparedChild,
			boolean domainRepair, boolean lightweightRepair, double incumbentCost) {
		if (completed || !matches(parentLp, candidate, side)) {
			return;
		}
		ArrayList<ReplayResult> results = new ArrayList<ReplayResult>();
		ReplayResult firstOld = runOnce(data, baseConfig, sourcePool, sourceOutsourcingPool, sourceCutPool,
				preparedChild, domainRepair, lightweightRepair, incumbentCost, 1, "old", false);
		if (!firstOld.usedRepair()) {
			String requestedCandidate = System.getProperty("twet.bpc.strongRepairReplayCandidate", "").trim();
			if (!requestedCandidate.isEmpty()) {
				completed = true;
				System.out.println("[StrongRepairReplay] matched side did not enter repair: "
						+ candidate.getDescription() + " " + side);
			}
			return;
		}
		completed = true;
		results.add(firstOld);
		String[] remainingModes = { "phase1", "phase1", "old" };
		for (int run = 0; run < remainingModes.length; run++) {
			String mode = remainingModes[run];
			results.add(runOnce(data, baseConfig, sourcePool, sourceOutsourcingPool, sourceCutPool,
					preparedChild, domainRepair, lightweightRepair, incumbentCost, run + 2, mode,
					"phase1".equals(mode)));
		}

		Node parent = parentLp.getNode();
		Path output = replayOutputPath();
		writeLine(output, String.format(Locale.US,
				"replay.start parent=%d candidate=%s side=%s incumbent=%.9f parentRestricted=%d parentHash=%s "
						+ "seed=%d seedHash=%s outsourcingSeed=%d outsourcingSeedHash=%s pool=%d outsourcingPool=%d cutPool=%d "
						+ "domainRepair=%s lightweightRepair=%s basePhaseOne=%s",
				parent.id, candidate.getDescription(), side, incumbentCost,
				parentLp.getRestrictedColumnIds().size(), fingerprint(parentLp.getRestrictedColumnIds()),
				preparedChild.seedColumnIds.size(), fingerprint(preparedChild.seedColumnIds),
				preparedChild.seedOutsourcingColumnIds.size(), fingerprint(preparedChild.seedOutsourcingColumnIds),
				sourcePool.size(), sourceOutsourcingPool.size(), sourceCutPool.size(),
				Boolean.toString(domainRepair), Boolean.toString(lightweightRepair),
				Boolean.toString(baseConfig.enableStrongBranchingPhaseOneRepair)));
		for (ReplayResult result : results) {
			String line = result.format();
			writeLine(output, line);
			System.out.println("[StrongRepairReplay] " + line);
		}
		writeLine(output, aggregate(results, "old"));
		writeLine(output, aggregate(results, "phase1"));
		writeLine(output, "replay.done output=" + output.toAbsolutePath());
		System.out.println("[StrongRepairReplay] done output=" + output.toAbsolutePath());
	}
	private static ReplayResult runOnce(Data data, TWETBPCConfig baseConfig, Pool sourcePool,
			OutsourcingPool sourceOutsourcingPool, CutPool sourceCutPool, Node preparedChild,
			boolean domainRepair, boolean lightweightRepair, double incumbentCost,
			int order, String mode, boolean phaseOne) {
		TWETBPCConfig config = baseConfig.copy();
		config.enableStrongBranchingPhaseOneRepair = phaseOne;
		config.enableBPCConsoleOutput = false;
		config.writeBPCResultFiles = false;
		config.liveTraceLogPath = "";
		config.diagnosticNodeProgressSummary = false;
		TWETBPCContext context = new TWETBPCContext(data, config);
		copyPools(sourcePool, sourceOutsourcingPool, sourceCutPool, context);
		Node child = preparedChild.copy();
		LP trial = new LP(data, context.pool, context.cutPool, config, context.outsourcingPool);
		long buildStart = System.nanoTime();
		trial.construct(child, child.seedColumnIds);
		long buildNanos = System.nanoTime() - buildStart;
		context.pc.prepareStandaloneStrongBranchingTrial(incumbentCost);
		int initialPoolSize = context.pool.size();
		int initialOutsourcingPoolSize = context.outsourcingPool.size();
		long start = System.nanoTime();
		try {
			StrongBranchingTrialResult trialResult = context.pc.solveStrongBranchingRmpTrial(
					trial, domainRepair, lightweightRepair);
			long wallNanos = System.nanoTime() - start;
			TWETMasterSolution solution = trialResult.getSolution();
			return new ReplayResult(order, mode, wallNanos, buildNanos,
					trialResult, solution, trial.getRestrictedColumnIds().size(),
					trial.getRestrictedOutsourcingColumnIds().size(),
					context.pool.size() - initialPoolSize,
					context.outsourcingPool.size() - initialOutsourcingPoolSize,
					trial.isNoSlack(), trial.branchImpliedPenaltyValue(), context.traceSummary);
		} finally {
			trial.closeModel();
		}
	}

	private static void copyPools(Pool sourcePool, OutsourcingPool sourceOutsourcingPool,
			CutPool sourceCutPool, TWETBPCContext target) {
		for (TWETColumn column : sourcePool.getColumns()) {
			int copiedId = target.pool.addColumn(column.getSequence(), column.getCost(), column.getSource(),
					column.isSeedColumn());
			if (copiedId != column.getId()) {
				throw new IllegalStateException("Internal pool snapshot changed column id " + column.getId()
						+ " -> " + copiedId);
			}
		}
		for (TWETOutsourcingColumn column : sourceOutsourcingPool.getColumns()) {
			int copiedId = target.outsourcingPool.addColumn(column);
			if (copiedId != column.getId()) {
				throw new IllegalStateException("Outsourcing pool snapshot changed column id " + column.getId()
						+ " -> " + copiedId);
			}
		}
		int expectedCutId = 0;
		for (TWETCut cut : sourceCutPool.getCuts()) {
			int copiedId = target.cutPool.addCut(cut);
			if (copiedId != expectedCutId) {
				throw new IllegalStateException("Cut pool snapshot changed cut id " + expectedCutId
						+ " -> " + copiedId);
			}
			expectedCutId++;
		}
		if (target.pool.size() != sourcePool.size()
				|| target.outsourcingPool.size() != sourceOutsourcingPool.size()
				|| target.cutPool.size() != sourceCutPool.size()) {
			throw new IllegalStateException("Strong repair replay pool snapshot size mismatch");
		}
	}

	private static boolean matches(LP parentLp, StrongBranchingCandidate candidate, String side) {
		String nodeProperty = System.getProperty("twet.bpc.strongRepairReplayParentNode", "").trim();
		if (nodeProperty.isEmpty() || parentLp == null || parentLp.getNode() == null || candidate == null) {
			return false;
		}
		int requestedNode;
		try {
			requestedNode = Integer.parseInt(nodeProperty);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Invalid twet.bpc.strongRepairReplayParentNode=" + nodeProperty, ex);
		}
		String requestedCandidate = System.getProperty("twet.bpc.strongRepairReplayCandidate", "").trim();
		String requestedSide = System.getProperty("twet.bpc.strongRepairReplaySide", "").trim();
		return parentLp.getNode().id == requestedNode
				&& (requestedCandidate.isEmpty() || requestedCandidate.equals(candidate.getDescription()))
				&& (requestedSide.isEmpty() || requestedSide.equalsIgnoreCase(side));
	}

	private static Path replayOutputPath() {
		String configured = System.getProperty("twet.bpc.strongRepairReplayOutput", "").trim();
		return Paths.get(configured.isEmpty()
				? "test-results/bpc/strong-repair-fixed-side-replay.log" : configured);
	}

	private static void writeLine(Path output, String line) {
		try {
			Path parent = output.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				writer.write(line);
				writer.newLine();
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to write strong repair replay output: " + output, ex);
		}
	}

	private static String aggregate(List<ReplayResult> results, String mode) {
		double wall = 0.0;
		double pricing = 0.0;
		double master = 0.0;
		double build = 0.0;
		double poolAdded = 0.0;
		int count = 0;
		for (ReplayResult result : results) {
			if (!mode.equals(result.mode)) {
				continue;
			}
			count++;
			wall += result.wallNanos;
			pricing += result.pricingNanos;
			master += result.masterNanos;
			build += result.buildNanos;
			poolAdded += result.poolAdded + result.outsourcingPoolAdded;
		}
		return String.format(Locale.US,
				"replay.aggregate mode=%s runs=%d meanWallMs=%.3f meanPricingMs=%.3f meanMasterMs=%.3f "
						+ "meanBuildMs=%.3f meanPoolAdded=%.3f",
				mode, count, millis(wall / Math.max(1, count)), millis(pricing / Math.max(1, count)),
				millis(master / Math.max(1, count)), millis(build / Math.max(1, count)),
				poolAdded / Math.max(1, count));
	}

	private static String fingerprint(List<Integer> ids) {
		long hash = 0xcbf29ce484222325L;
		for (Integer id : ids) {
			hash ^= id == null ? -1 : id.intValue();
			hash *= 0x100000001b3L;
		}
		return Long.toUnsignedString(hash, 16);
	}

	private static long sum(Map<String, Long> values) {
		long total = 0L;
		for (Long value : values.values()) {
			total += value.longValue();
		}
		return total;
	}

	private static double millis(double nanos) {
		return nanos / 1_000_000.0;
	}

	private static final class ReplayResult {
		final int order;
		final String mode;
		final long wallNanos;
		final long buildNanos;
		final long pricingNanos;
		final long masterNanos;
		final StrongBranchingTrialResult trial;
		final TWETMasterSolution solution;
		final int restricted;
		final int outsourcingRestricted;
		final int poolAdded;
		final int outsourcingPoolAdded;
		final boolean noSlack;
		final double penaltyValue;
		final BPCTraceSummary trace;

		ReplayResult(int order, String mode, long wallNanos, long buildNanos,
				StrongBranchingTrialResult trial, TWETMasterSolution solution,
				int restricted, int outsourcingRestricted, int poolAdded, int outsourcingPoolAdded,
				boolean noSlack, double penaltyValue, BPCTraceSummary trace) {
			this.order = order;
			this.mode = mode;
			this.wallNanos = wallNanos;
			this.buildNanos = buildNanos;
			this.trial = trial;
			this.solution = solution;
			this.restricted = restricted;
			this.outsourcingRestricted = outsourcingRestricted;
			this.poolAdded = poolAdded;
			this.outsourcingPoolAdded = outsourcingPoolAdded;
			this.noSlack = noSlack;
			this.penaltyValue = penaltyValue;
			this.trace = trace;
			this.pricingNanos = sum(trace.getPricingTimeNanos());
			this.masterNanos = sum(trace.getMasterLpTimeNanos());
		}

		boolean usedRepair() {
			Map<String, Integer> calls = trace.getMasterLpCallCount();
			return calls.containsKey("repair_slack_initial")
					|| calls.containsKey("strong_branching_phase_one_initial")
					|| calls.containsKey("strong_branching_domain_repair_initial");
		}

		String format() {
			String status = solution == null ? "null" : solution.getStatus().toString();
			double objective = solution == null ? Double.NaN : solution.getObjectiveValue();
			return String.format(Locale.US,
					"replay.run order=%d mode=%s wallMs=%.3f buildMs=%.3f pricingMs=%.3f masterMs=%.3f "
							+ "status=%s bound=%.9f objective=%.9f infeasible=%s dualPruned=%s timeLimited=%s "
							+ "restricted=%d outsourcingRestricted=%d poolAdded=%d outsourcingPoolAdded=%d "
							+ "noSlack=%s penaltyValue=%.9f pricingCalls=%s pricingColumns=%s masterCalls=%s message=%s",
					order, mode, millis(wallNanos), millis(buildNanos), millis(pricingNanos), millis(masterNanos),
					status, trial.getBound(), objective, Boolean.toString(trial.isInfeasible()),
					Boolean.toString(trial.isDualBoundPruned()), Boolean.toString(trial.isTimeLimited()),
					restricted, outsourcingRestricted, poolAdded, outsourcingPoolAdded,
					Boolean.toString(noSlack), penaltyValue, trace.getPricingCallCount(),
					trace.getPricingColumnCount(), trace.getMasterLpCallCount(), sanitize(trial.getMessage()));
		}

		private static String sanitize(String value) {
			return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
		}
	}
}
