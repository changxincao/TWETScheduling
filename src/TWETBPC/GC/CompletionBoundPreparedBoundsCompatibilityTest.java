package TWETBPC.GC;

/** 验证紧 horizon completion bound 仅在显式标记为子树安全时允许复用。 */
public final class CompletionBoundPreparedBoundsCompatibilityTest {
	private CompletionBoundPreparedBoundsCompatibilityTest() {
	}

	public static void main(String[] args) {
		CompletionBoundCalculator.Bounds bounds = new CompletionBoundCalculator.Bounds(2, 40.0);
		CompletionBoundSubtreeArcEliminator.PreparedBounds legacy =
				new CompletionBoundSubtreeArcEliminator.PreparedBounds(bounds, 40.0,
						CompletionBoundCalculator.Relaxation.ALL_CYCLES,
						CompletionBoundCalculator.QueueOrdering.FIFO);
		CompletionBoundSubtreeArcEliminator.PreparedBounds subtreeSafe =
				new CompletionBoundSubtreeArcEliminator.PreparedBounds(bounds, 40.0,
						CompletionBoundCalculator.Relaxation.ALL_CYCLES,
						CompletionBoundCalculator.QueueOrdering.FIFO, true);

		assertCompatible(!legacy.isCompatible(100.0, CompletionBoundCalculator.Relaxation.ALL_CYCLES,
				CompletionBoundCalculator.QueueOrdering.FIFO), "旧路径不能自动复用紧 horizon");
		assertCompatible(subtreeSafe.isCompatible(100.0, CompletionBoundCalculator.Relaxation.ALL_CYCLES,
				CompletionBoundCalculator.QueueOrdering.FIFO), "子树安全的 compact horizon 应允许复用");
		assertCompatible(!subtreeSafe.isCompatible(100.0, CompletionBoundCalculator.Relaxation.TWO_CYCLE,
				CompletionBoundCalculator.QueueOrdering.FIFO), "relaxation 不同不能复用");
		assertCompatible(!subtreeSafe.isCompatible(100.0, CompletionBoundCalculator.Relaxation.ALL_CYCLES,
				CompletionBoundCalculator.QueueOrdering.REDUCED_COST), "queue ordering 不同不能复用");
		assertCompatible(!subtreeSafe.isCompatible(30.0, CompletionBoundCalculator.Relaxation.ALL_CYCLES,
				CompletionBoundCalculator.QueueOrdering.FIFO), "超出目标 horizon 的 bound 不能复用");

		System.out.println("CompletionBoundPreparedBoundsCompatibilityTest passed");
	}

	private static void assertCompatible(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
