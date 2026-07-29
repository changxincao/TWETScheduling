package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import Basic.Data;
import TWETBPC.CUT.SubsetRowCutEvaluator;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Model.TWETCut;
import TWETBPC.Model.TWETCutType;

/** 对拍 SRI posting 候选完整性，覆盖重复访问、full/node/arc memory 和增量追加。 */
public final class SubsetRowColumnPostingIndexTest {

	private SubsetRowColumnPostingIndexTest() {
	}

	public static void main(String[] args) throws Exception {
		Data data = new Data("data/40-2/wet040_001_2m.dat", false, true);
		data.n = 8;
		Pool pool = new Pool(data);
		ArrayList<Integer> columnIds = new ArrayList<Integer>();
		Random random = new Random(20260729L);
		for (int sample = 0; sample < 800; sample++) {
			int length = 1 + random.nextInt(10);
			ArrayList<Integer> sequence = new ArrayList<Integer>(length);
			for (int idx = 0; idx < length; idx++) {
				sequence.add(Integer.valueOf(1 + random.nextInt(data.n)));
			}
			int columnId = pool.addColumn(sequence, sample, ColumnSource.PRICING_EXACT, false);
			if (!columnIds.contains(Integer.valueOf(columnId))) {
				columnIds.add(Integer.valueOf(columnId));
			}
		}

		SubsetRowColumnPostingIndex index = new SubsetRowColumnPostingIndex(data.n);
		index.rebuild(columnIds, pool);
		List<Integer> scope = Arrays.asList(Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3));
		List<TWETCut> cuts = Arrays.asList(
				new TWETCut(-1, TWETCutType.SUBSET_ROW, scope, 1.0, "full"),
				new TWETCut(-1, TWETCutType.SUBSET_ROW, scope,
						Arrays.asList(Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3),
								Integer.valueOf(4)),
						0.5, 1.0, "node-memory"),
				new TWETCut(-1, TWETCutType.SUBSET_ROW, scope, null,
						Arrays.asList(Long.valueOf(SubsetRowCutEvaluator.arcKey(0, 1)),
								Long.valueOf(SubsetRowCutEvaluator.arcKey(1, 2)),
								Long.valueOf(SubsetRowCutEvaluator.arcKey(2, 3)),
								Long.valueOf(SubsetRowCutEvaluator.arcKey(3, 1))),
						0.5, 1.0, "arc-memory"),
				new TWETCut(-1, TWETCutType.SUBSET_ROW, scope, null, 1.0 / 3.0, 1.0, "generic"));

		for (TWETCut cut : cuts) {
			BitSet candidates = index.candidatePositions(cut);
			for (int position = 0; position < columnIds.size(); position++) {
				TWETColumn column = pool.getColumn(columnIds.get(position).intValue());
				int coefficient = SubsetRowCutEvaluator.coefficient(cut, column.getSequence(), data.n);
				if (coefficient > 0 && !candidates.get(position)) {
					throw new AssertionError("posting omitted a positive coefficient: cut="
							+ cut.getDescription() + ", sequence=" + column.getSequence());
				}
				if ("full".equals(cut.getDescription())
						&& candidates.get(position) != (coefficient > 0)) {
					throw new AssertionError("half-multiplier full posting is not exact: sequence="
							+ column.getSequence() + ", coefficient=" + coefficient);
				}
			}
		}

		List<Integer> repeatedSequence = Arrays.asList(Integer.valueOf(8), Integer.valueOf(1),
				Integer.valueOf(1), Integer.valueOf(7), Integer.valueOf(8));
		int appendedId = pool.addColumn(repeatedSequence, 0.0, ColumnSource.PRICING_EXACT, false);
		if (columnIds.contains(Integer.valueOf(appendedId))) {
			throw new AssertionError("incremental test sequence unexpectedly existed");
		}
		int appendedPosition = columnIds.size();
		columnIds.add(Integer.valueOf(appendedId));
		index.append(appendedPosition, pool.getColumn(appendedId));
		if (!index.candidatePositions(cuts.get(0)).get(appendedPosition)) {
			throw new AssertionError("repeated single-scope visit was omitted after incremental append");
		}

		System.out.println("SubsetRowColumnPostingIndexTest passed, columns=" + columnIds.size());
	}
}
