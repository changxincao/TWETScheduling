package Common.formal;

import java.util.List;
import java.util.Random;

import Common.formal.FormalTimeScaleGenerator.Scale;

/** 逐任务生成并冻结 zero、narrow 和 wide 三档 due-window 半宽。 */
public final class FormalWindowGenerator {

	public List<Window> generate(FormalTaskSet taskSet, Scale scale) {
		int nominal = scale.nominalMultiplier();
		return List.of(
				create(taskSet, scale, "zero", 0, 0, 0x5a45524fL),
				create(taskSet, scale, "narrow", 80 * nominal, 120 * nominal, 0x4e415252L),
				create(taskSet, scale, "wide", 280 * nominal, 320 * nominal, 0x57494445L));
	}

	private static Window create(FormalTaskSet taskSet, Scale scale, String level,
			int minimum, int maximum, long salt) {
		long seed = FormalTaskSetGenerator.mixSeed(FormalExperimentDesign.WINDOW_SEED,
				taskSet.size(), taskSet.caseIndex(), (int) (salt ^ scale.level().hashCode()));
		int[] halfWidths = new int[taskSet.size() + 1];
		if (maximum > 0) {
			Random random = new Random(seed);
			for (int job = 1; job <= taskSet.size(); job++) {
				halfWidths[job] = minimum + random.nextInt(maximum - minimum + 1);
			}
		}
		return new Window(level, minimum, maximum, seed, halfWidths,
				FormalTimeScaleGenerator.fingerprint(halfWidths));
	}

	public record Window(String level, int minimum, int maximum, long seed,
			int[] halfWidths, String fingerprint) {
		public Window {
			halfWidths = halfWidths.clone();
		}

		@Override
		public int[] halfWidths() {
			return halfWidths.clone();
		}

		public int halfWidth(int job) {
			return halfWidths[job];
		}

		public String values() {
			StringBuilder result = new StringBuilder();
			for (int job = 1; job < halfWidths.length; job++) {
				if (job > 1) {
					result.append(',');
				}
				result.append(halfWidths[job]);
			}
			return result.toString();
		}
	}
}
