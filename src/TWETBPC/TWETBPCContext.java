package TWETBPC;

import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Output.BPCCompositeTraceSink;
import Output.BPCConsoleReporter;
import Output.BPCTraceSink;
import Output.BPCTraceSummary;
import TWETBPC.BP.ArcBrancher;
import TWETBPC.BP.Brancher;
import TWETBPC.CUT.CutGenerator;
import TWETBPC.CUT.NoOpCutGenerator;
import TWETBPC.CUT.SubsetRowCutGenerator;
import TWETBPC.GC.BidirectionalPricingEngine;
import TWETBPC.GC.ExactPricingEngine;
import TWETBPC.GC.GCBBAsymmetricBidirectionalPricingEngine;
import TWETBPC.GC.GCBBStyleBidirectionalFullDomainNodeJoinPricingEngine;
import TWETBPC.GC.GCBBStyleBidirectionalFullDomainPricingEngine;
import TWETBPC.GC.GCNGBBStyleBidirectionalPricingEngine;
import TWETBPC.GC.HeuristicPricingEngine;
import TWETBPC.GC.InitialColumnBuilder;
import TWETBPC.GC.PaperDominanceExactPricingEngine;
import TWETBPC.GC.PricingEngine;
import TWETBPC.IO.HeuristicSeedProvider;
import TWETBPC.LP.CutPool;
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
	public final CutPool cutPool;
	public final HeuristicSeedProvider seedProvider;
	public final InitialColumnBuilder initialColumnBuilder;
	public final List<PricingEngine> pricingEngines;
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
		this.cutPool = new CutPool();
		this.seedProvider = new HeuristicSeedProvider(data, config);
		this.initialColumnBuilder = new InitialColumnBuilder(data, config, pool, seedProvider);

		this.pricingEngines = new ArrayList<PricingEngine>();
		pricingEngines.add(new HeuristicPricingEngine(data, config));
		// 2026-05-20: exact pricing 层二选一。打开双向时不再顺序调用单向 forward，
		// 关闭双向时才按 usePaperDominancePricing 选择原有单向实现。
		if (config.enableBidirectionalPricing) {
			if (config.useGCBBAsymmetricBidirectionalPricing) {
				pricingEngines.add(new GCBBAsymmetricBidirectionalPricingEngine(data, config));
			} else if (config.useGCBBFullDomainNodeJoinBidirectionalPricing) {
				pricingEngines.add(new GCBBStyleBidirectionalFullDomainNodeJoinPricingEngine(data, config));
			} else if (config.useGCBBFullDomainBidirectionalPricing) {
				pricingEngines.add(new GCBBStyleBidirectionalFullDomainPricingEngine(data, config));
			} else if (config.useGCNGBBStyleBidirectionalPricing) {
				pricingEngines.add(new GCNGBBStyleBidirectionalPricingEngine(data, config));
			} else {
				pricingEngines.add(new BidirectionalPricingEngine(data, config));
			}
		} else if (config.usePaperDominancePricing) {
			pricingEngines.add(new PaperDominanceExactPricingEngine(data, config));
		} else {
			pricingEngines.add(new ExactPricingEngine(data, config));
		}

		this.cutGenerators = new ArrayList<CutGenerator>();
		cutGenerators.add(new NoOpCutGenerator());
		cutGenerators.add(new SubsetRowCutGenerator());

		this.branchers = new ArrayList<Brancher>();
		branchers.add(new TWETBPC.BP.TariffSegmentBrancher(config.branchingTolerance));
		branchers.add(new TWETBPC.BP.MachineCountBrancher(config.branchingTolerance));
		branchers.add(new ArcBrancher(config.branchingTolerance));

		this.traceSummary = new BPCTraceSummary();
		this.consoleReporter = new BPCConsoleReporter();
		ArrayList<BPCTraceSink> sinks = new ArrayList<BPCTraceSink>();
		sinks.add(traceSummary);
		if (config.enableBPCConsoleOutput) {
			sinks.add(consoleReporter);
		}
		this.traceSink = new BPCCompositeTraceSink(sinks);

		this.pc = new PC(config, pricingEngines, cutGenerators, traceSink);
		this.tree = new Tree(data, config, pool, cutPool, initialColumnBuilder, pc, branchers, traceSink);
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
