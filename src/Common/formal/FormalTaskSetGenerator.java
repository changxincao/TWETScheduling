package Common.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import Common.formal.FormalTaskSet.Job;

/** 固定随机选择Tanaka父实例，并构造20/40及60/80/100嵌套任务集合。 */
public final class FormalTaskSetGenerator {
	private final Path dataRoot;
	private final int casesPerSize;

	public FormalTaskSetGenerator(Path dataRoot, int casesPerSize) {
		this.dataRoot = dataRoot;
		this.casesPerSize = casesPerSize;
	}

	public List<FormalTaskSet> generate(int[] requestedSizes) throws IOException {
		FormalExperimentDesign.validateSizes(requestedSizes);
		Map<Integer, List<Integer>> sizesByParent = new LinkedHashMap<Integer, List<Integer>>();
		for (int size : requestedSizes) {
			sizesByParent.computeIfAbsent(FormalExperimentDesign.parentSize(size), ignored -> new ArrayList<Integer>())
					.add(Integer.valueOf(size));
		}
		ArrayList<FormalTaskSet> result = new ArrayList<FormalTaskSet>();
		for (Map.Entry<Integer, List<Integer>> entry : sizesByParent.entrySet()) {
			int parentSize = entry.getKey().intValue();
			List<Path> selectedSources = selectSources(parentSize);
			for (int caseIndex = 0; caseIndex < selectedSources.size(); caseIndex++) {
				Path source = selectedSources.get(caseIndex);
				List<Job> sourceJobs = readSource(source, parentSize);
				double parentWorkload = sourceJobs.stream().mapToDouble(Job::processing).sum();
				long permutationSeed = parentSize == 50 ? 0L
						: mixSeed(FormalExperimentDesign.TASK_PERMUTATION_SEED,
								parentSize, caseIndex + 1, source.getFileName().toString().hashCode());
				ArrayList<Job> ordered = new ArrayList<Job>(sourceJobs);
				if (parentSize != 50) {
					Collections.shuffle(ordered, new Random(permutationSeed));
				}
				for (int targetSize : entry.getValue()) {
					String id = String.format(Locale.ROOT, "n%03d-set%02d", targetSize, caseIndex + 1);
					result.add(new FormalTaskSet(id, targetSize, parentSize, caseIndex + 1, source,
							selectionSeed(parentSize), permutationSeed, parentWorkload,
							ordered.subList(0, targetSize)));
				}
			}
		}
		result.sort(Comparator.comparingInt(FormalTaskSet::size).thenComparingInt(FormalTaskSet::caseIndex));
		return result;
	}

	private List<Path> selectSources(int parentSize) throws IOException {
		Path sourceDir = dataRoot.resolve(parentSize + "-1");
		if (!Files.isDirectory(sourceDir)) {
			throw new IOException("Missing Tanaka source directory: " + sourceDir);
		}
		ArrayList<Path> sources;
		try (var stream = Files.list(sourceDir)) {
			sources = new ArrayList<Path>(stream
					.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dat"))
					.sorted().toList());
		}
		if (sources.size() < casesPerSize) {
			throw new IOException("Need " + casesPerSize + " sources in " + sourceDir + ", found " + sources.size());
		}
		Collections.shuffle(sources, new Random(selectionSeed(parentSize)));
		return List.copyOf(sources.subList(0, casesPerSize));
	}

	private static List<Job> readSource(Path source, int expectedSize) throws IOException {
		List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
		if (lines.isEmpty()) {
			throw new IOException("Empty Tanaka source: " + source);
		}
		int size = Integer.parseInt(lines.get(0).trim().split("\\s+")[0]);
		if (size != expectedSize || lines.size() < size + 1) {
			throw new IOException("Unexpected Tanaka source size in " + source + ": " + size);
		}
		ArrayList<Job> jobs = new ArrayList<Job>(size);
		for (int index = 1; index <= size; index++) {
			String[] tokens = lines.get(index).trim().split("\\s+");
			if (tokens.length < 4) {
				throw new IOException("Malformed Tanaka row " + index + " in " + source);
			}
			jobs.add(new Job(index, Integer.parseInt(tokens[0]), Integer.parseInt(tokens[1]),
					Integer.parseInt(tokens[2]), Integer.parseInt(tokens[3])));
		}
		return jobs;
	}

	private static long selectionSeed(int parentSize) {
		return mixSeed(FormalExperimentDesign.SOURCE_SELECTION_SEED, parentSize, 0, 0);
	}

	static long mixSeed(long base, int first, int second, int third) {
		long value = base;
		value = 31L * value + first;
		value = 31L * value + second;
		value = 31L * value + third;
		return value;
	}
}
