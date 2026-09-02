package TWETBPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import Basic.Data;
import Output.BPCCompositeTraceSink;
import Output.BPCConsoleReporter;
import Output.BPCStreamingTraceSink;
import Output.BPCTraceSink;
import Output.BPCTraceSummary;
import TWETBPC.BP.ArcBrancher;
import TWETBPC.BP.Brancher;
import TWETBPC.BP.OutsourcingMembershipBrancher;
import TWETBPC.BP.StructuredArcFlowBrancher;
import TWETBPC.BP.UndirectedAdjacencyBrancher;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.CUT.NoOpCutGenerator;
import TWETBPC.CUT.SubsetRowCutGenerator;
import TWETBPC.GC.BidirectionalPricingEngine;
import TWETBPC.GC.ExactPricingEngine;
import TWETBPC.GC.GCBBAsymmetricBidirectionalPricingEngine;
import TWETBPC.GC.GCBBStyleBidirectionalFullDomainNodeJoinPricingEngine;
import TWETBPC.GC.GCBBStyleBidirectionalFullDomainPricingEngine;
import TWETBPC.GC.GCNGBBStyleBidirectionalNgDssrGraphPartialDominancePricingEngine;
import TWETBPC.GC.GCNGBBStyleBidirectionalNgDssrPartialDominancePricingEngine;
import TWETBPC.GC.GCNGBBStyleBidirectionalNgDssrPricingEngine;
import TWETBPC.GC.GCNGBBStyleBidirectionalPartialDominancePricingEngine;
import TWETBPC.GC.GCNGBBStyleBidirectionalPricingEngine;
import TWETBPC.GC.HeuristicPricingEngine;
import TWETBPC.GC.InitialColumnBuilder;
import TWETBPC.GC.OutsourcingPricingEngine;
import TWETBPC.GC.PaperDominanceExactPricingEngine;
import TWETBPC.GC.PricingEngine;
import TWETBPC.GC.PricingMode;
import TWETBPC.GC.TimeIndexedGraphPricingEngine;
import TWETBPC.GC.TimeIndexedGraphRank1CutPricingEngine;
import TWETBPC.IO.HeuristicSeedProvider;
import TWETBPC.LP.CutPool;
import TWETBPC.LP.OutsourcingPool;
import TWETBPC.LP.PC;
import TWETBPC.LP.Pool;
import TWETBPC.LP.Tree;

/**
 * TWET-BPC 骨架的装配容器。
 */
public class TWETBPCContext {

	public final Data data;
	public final TWETBPCConfig config;
	public final Pool pool;
	public final OutsourcingPool outsourcingPool;
	public final CutPool cutPool;
	public final HeuristicSeedProvider seedProvider;
	public final InitialColumnBuilder initialColumnBuilder;
	public final List<PricingEngine> pricingEngines;
	public final PricingMode pricingMode;
	public final List<CutGenerator> cutGenerators;
	public final List<Brancher> branchers;
	public final BPCTraceSummary traceSummary;
	public final BPCConsoleReporter consoleReporter;
	public final BPCTraceSink traceSink;
	public final PC pc;
	public final Tree tree;

	public TWETBPCContext(Data data, TWETBPCConfig config) {
		this.data = data;
		this.config = config;
		this.pool = new Pool(data);
		this.outsourcingPool = new OutsourcingPool(data);
		this.cutPool = new CutPool();
		this.seedProvider = new HeuristicSeedProvider(data, config);
		this.initialColumnBuilder = new InitialColumnBuilder(data, config, pool, seedProvider);

		this.pricingEngines = new ArrayList<PricingEngine>();
		// 2026-06-28: time-indexed 实验线只使用图定价器本身；rank1 cut 时由图定价器内部先跑 bucket heuristic。
		if (!config.useTimeIndexedGraphPricing) {
			pricingEngines.add(new HeuristicPricingEngine(data, config));
		}
		// 2026-05-20: exact pricing 层二选一。打开双向时不再顺序调用单向 forward，
		// 关闭双向时才按 usePaperDominancePricing 选择原有单向实现。
		// 2026-07-30: mode 必须在同一分支中确定；下游不能再解释可能被更高优先级覆盖的原始 flag。
		PricingMode selectedPricingMode;
		if (config.useTimeIndexedGraphPricing) {
			if (config.useTimeIndexedGraphRank1CutPricing) {
				pricingEngines.add(new TimeIndexedGraphRank1CutPricingEngine(data, config));
				selectedPricingMode = PricingMode.TIME_INDEXED_RANK1;
			} else {
				pricingEngines.add(new TimeIndexedGraphPricingEngine(data, config));
				selectedPricingMode = PricingMode.TIME_INDEXED;
			}
		} else if (config.enableBidirectionalPricing) {
			if (config.useGCBBAsymmetricBidirectionalPricing) {
				pricingEngines.add(new GCBBAsymmetricBidirectionalPricingEngine(data, config));
				selectedPricingMode = PricingMode.OTHER;
			} else if (config.useGCBBFullDomainNodeJoinBidirectionalPricing) {
				pricingEngines.add(new GCBBStyleBidirectionalFullDomainNodeJoinPricingEngine(data, config));
				selectedPricingMode = PricingMode.OTHER;
			} else if (config.useGCBBFullDomainBidirectionalPricing) {
				pricingEngines.add(new GCBBStyleBidirectionalFullDomainPricingEngine(data, config));
				selectedPricingMode = PricingMode.OTHER;
			} else if (config.useGCNGBBStyleNgDssrPartialDominancePricing) {
				pricingEngines.add(new GCNGBBStyleBidirectionalNgDssrPartialDominancePricingEngine(data, config));
				selectedPricingMode = PricingMode.NG_DSSR_PARTIAL;
			} else if (config.useGCNGBBStyleNgDssrGraphPartialDominancePricing) {
				pricingEngines.add(new GCNGBBStyleBidirectionalNgDssrGraphPartialDominancePricingEngine(data, config));
				selectedPricingMode = PricingMode.NG_DSSR_GRAPH_PARTIAL;
			} else if (config.useGCNGBBStyleNgDssrPricing) {
				pricingEngines.add(new GCNGBBStyleBidirectionalNgDssrPricingEngine(data, config));
				selectedPricingMode = PricingMode.NG_DSSR;
			} else if (config.useGCNGBBStylePartialDominancePricing) {
				pricingEngines.add(new GCNGBBStyleBidirectionalPartialDominancePricingEngine(data, config));
				selectedPricingMode = PricingMode.OTHER;
			} else if (config.useGCNGBBStyleBidirectionalPricing) {
				pricingEngines.add(new GCNGBBStyleBidirectionalPricingEngine(data, config));
				selectedPricingMode = PricingMode.OTHER;
			} else {
				pricingEngines.add(new BidirectionalPricingEngine(data, config));
				selectedPricingMode = PricingMode.OTHER;
			}
			if (config.diagnosticCrossCheckPartialDominance
					&& config.useGCNGBBStyleBidirectionalPricing
					&& !config.useGCNGBBStyleNgDssrPartialDominancePricing
					&& !config.useGCNGBBStyleNgDssrGraphPartialDominancePricing
					&& !config.useGCNGBBStyleNgDssrPricing
					&& !config.useGCBBAsymmetricBidirectionalPricing
					&& !config.useGCBBFullDomainNodeJoinBidirectionalPricing
					&& !config.useGCBBFullDomainBidirectionalPricing) {
				// 主定价器有列时 PC 会立即重解；只有主定价器返回空列，另一套 dominance
				// 才会在完全相同的 RMP 与 dual 上运行，因此可直接检查停止条件是否一致。
				if (config.useGCNGBBStylePartialDominancePricing) {
					pricingEngines.add(new GCNGBBStyleBidirectionalPricingEngine(data, config));
				} else {
					pricingEngines.add(new GCNGBBStyleBidirectionalPartialDominancePricingEngine(data, config, true));
				}
			}
		} else if (config.usePaperDominancePricing) {
			pricingEngines.add(new PaperDominanceExactPricingEngine(data, config));
			selectedPricingMode = PricingMode.OTHER;
		} else {
			pricingEngines.add(new ExactPricingEngine(data, config));
			selectedPricingMode = PricingMode.OTHER;
		}
		this.pricingMode = selectedPricingMode;
		if (shouldAddTimeIndexedPreHeuristic()) {
			pricingEngines.add(0, TimeIndexedGraphPricingEngine.preHeuristic(data, config));
		}
		if (config.useColumnizedOutsourcing()) {
			// 2026-06-22: 外包集合列放在内部 exact 后；dual-bound pruning 开启时，PC 会用同一套 dual
			// 把内部 exact 和外包 exact 绑定执行，避免只证明一个列族。
			pricingEngines.add(new OutsourcingPricingEngine(data, config));
		}
		this.cutGenerators = new ArrayList<CutGenerator>();
		cutGenerators.add(new NoOpCutGenerator());
		boolean enableSelectedSubsetRowCuts =
				(config.enableSubsetRowCutsForPartialDominance && pricingMode.supportsPartialNgSubsetRowCuts())
				|| (config.enableSubsetRowCutsForTimeIndexedGraph && pricingMode.supportsTimeIndexedRank1Cuts());
		if (enableSelectedSubsetRowCuts) {
			// 2026-09-02: 对原问题的精确覆盖排程，SRI 只需约束内部调度列；显式外包变量和
			// 列化外包列的系数均为 0。它可以切掉 covering master 中重复覆盖的人工整数点，
			// 但不会切掉任何任务恰好内部加工一次或外包一次的原问题可行解。
			cutGenerators.add(new SubsetRowCutGenerator(config, pricingMode));
		}

		this.branchers = new ArrayList<Brancher>();
		if (config.useColumnizedOutsourcing()) {
			// 2026-06-20: SP1 外包集合列会产生大量 membership 分数性；先切全局机器数和机器侧结构，最后再切外包集合成员。
			branchers.add(new TWETBPC.BP.MachineCountBrancher(config.branchingTolerance));
			if (config.enableUndirectedAdjacencyBranching) {
				branchers.add(new UndirectedAdjacencyBrancher(config.branchingTolerance));
			}
			branchers.add(createArcFlowBrancher());
			branchers.add(new OutsourcingMembershipBrancher(config.branchingTolerance));
		} else {
			branchers.add(new TWETBPC.BP.TariffSegmentBrancher(config.branchingTolerance));
			branchers.add(new TWETBPC.BP.MachineCountBrancher(config.branchingTolerance));
			if (config.enableUndirectedAdjacencyBranching) {
				branchers.add(new UndirectedAdjacencyBrancher(config.branchingTolerance));
			}
			branchers.add(createArcFlowBrancher());
		}

		this.traceSummary = new BPCTraceSummary(config);
		this.consoleReporter = new BPCConsoleReporter();
		ArrayList<BPCTraceSink> sinks = new ArrayList<BPCTraceSink>();
		sinks.add(traceSummary);
		if (config.liveTraceLogPath != null && !config.liveTraceLogPath.trim().isEmpty()) {
			sinks.add(new BPCStreamingTraceSink(java.nio.file.Path.of(config.liveTraceLogPath.trim())));
		}
		if (config.enableBPCConsoleOutput) {
			sinks.add(consoleReporter);
		}
		this.traceSink = new BPCCompositeTraceSink(sinks);

		this.pc = new PC(config, pricingMode, pricingEngines, cutGenerators, traceSink);
		this.tree = new Tree(data, config, pricingMode, pool, outsourcingPool, cutPool, initialColumnBuilder, pc,
				branchers, traceSink);
	}

	private Brancher createArcFlowBrancher() {
		return config.enableCutSetBranching || config.enableClusterBranching
				? new StructuredArcFlowBrancher(data, config)
				: new ArcBrancher(config.branchingTolerance);
	}

	private boolean shouldAddTimeIndexedPreHeuristic() {
		return config.enableTimeIndexedPreHeuristicPricing
				&& data.isExactIntegerTimeInstance()
				&& config.enableBidirectionalPricing
				&& pricingMode.usesNgDssrPricing();
	}

	/**
	 * 汇总本次 run 的实际配置。这里同时记录最终装配出的组件和 JVM 属性覆盖项，避免只看结果时无法复原实验口径。
	 */

	public List<String> runConfigurationLines() {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("run.instance=" + resolveInstanceName());
		lines.add("run.data.n=" + data.n);
		lines.add("run.data.m=" + data.m);
		lines.add("run.data.CmaxH=" + data.CmaxH);
		lines.add("run.components.pricingEngines=" + classNames(pricingEngines));
		lines.add("run.effective.pricingMode=" + pricingMode);
		lines.add("run.components.cutGenerators=" + classNames(cutGenerators));
		lines.add("run.components.branchers=" + classNames(branchers));
		if (pricingMode.usesNgDssrPricing()) {
			lines.add("run.effective.ngDssrMidpointProbe="
					+ GCNGBBStyleBidirectionalNgDssrPricingEngine.effectiveMidpointProbeConfiguration(config));
		}
		lines.add("run.system.javaVersion=" + System.getProperty("java.version"));
		lines.add("run.system.availableProcessors=" + Runtime.getRuntime().availableProcessors());
		lines.addAll(config.snapshotLines());
		lines.addAll(twetSystemPropertyLines());
		return lines;
	}

	private static List<String> twetSystemPropertyLines() {
		ArrayList<String> lines = new ArrayList<String>();
		Properties properties = System.getProperties();
		for (String name : properties.stringPropertyNames()) {
			if (name.startsWith("twet.bpc.") || name.startsWith("twet.data.")) {
				lines.add("systemProperty." + name + "=" + properties.getProperty(name));
			}
		}
		Collections.sort(lines);
		return lines;
	}

	private static String classNames(List<?> objects) {
		ArrayList<String> names = new ArrayList<String>();
		for (Object object : objects) {
			names.add(object instanceof PricingEngine ? ((PricingEngine) object).getName()
					: object.getClass().getSimpleName());
		}
		// 2026-07-29: pricing、cut 和 branching 组件均按列表顺序执行，日志必须保留该顺序。
		// 只有下方无执行顺序语义的 JVM 属性继续排序。
		return names.toString();
	}

	/**
	 * 返回当前实例用于输出的名字。
	 */
	public String resolveInstanceName() {
		if (config.instanceName != null && !config.instanceName.trim().isEmpty()) {
			return config.instanceName.trim();
		}
		return "n" + data.n + "_m" + data.m;
	}

}
