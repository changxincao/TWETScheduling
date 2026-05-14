package TWETBPC;

import java.nio.file.Paths;

import Basic.Data;
import Output.BPCResultWriter;
import Output.BPCSolutionValidator;
import Output.ValidationResult;

/**
 * TWET 特化 BPC 骨架的最外层入口。
 */
public class TWETBPCSolver {

	private final TWETBPCContext context;

	public TWETBPCSolver(Data data) {
		this(data, new TWETBPCConfig());
	}

	public TWETBPCSolver(Data data, TWETBPCConfig config) {
		this.context = new TWETBPCContext(data, config);
	}

	public TWETBPCContext getContext() {
		return context;
	}

	public TWETSolveResult solve() {
		long startNano = System.nanoTime();
		context.traceSink.onSolveStarted(context.resolveInstanceName());
		TWETSolveResult result = context.tree.solve();
		double solveSeconds = (System.nanoTime() - startNano) / 1_000_000_000.0;
		context.traceSink.onSolveFinished(result, solveSeconds);
		if (context.config.enableBPCConsoleOutput) {
			context.consoleReporter.printFinalSummary(context.traceSummary, result);
		}
		if (context.config.writeBPCResultFiles) {
			try {
				ValidationResult validation = BPCSolutionValidator.validate(context.data, context.pool, result);
				BPCResultWriter.write(Paths.get(context.config.bpcOutputRoot), context.config.bpcMethodName,
						context.resolveInstanceName(), context, result, validation, context.traceSummary);
			} catch (Exception ex) {
				throw new IllegalStateException("Failed to write BPC result artifacts", ex);
			}
		}
		return result;
	}

}
