package TWETBPC.IO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import TWETBPC.GC.FixedInitialColumnSeed;

/**
 * 跨进程共享固定初始列的确定性文本格式。
 * <p>
 * 快照只保存任务序列；每个目标实例仍用自己的 evaluator 重算列成本。
 */
public final class FixedInitialSeedSnapshotIO {

	private static final String HEADER = "TWET_FIXED_INITIAL_SEED_V1";

	private FixedInitialSeedSnapshotIO() {
	}

	public static void write(Path path, String reference, FixedInitialColumnSeed seed) throws IOException {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add(HEADER);
		lines.add("reference\t" + reference);
		lines.add("fingerprint\t" + fingerprint(seed));
		writeSection(lines, "initial", seed.getInitialSequences());
		writeSection(lines, "incumbent", seed.getIncumbentSequences());
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(path, lines, StandardCharsets.UTF_8);
	}

	public static FixedInitialColumnSeed read(Path path) throws IOException {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		int cursor = 0;
		if (lines.isEmpty() || !HEADER.equals(lines.get(cursor++))) {
			throw new IllegalArgumentException("Unsupported fixed initial seed snapshot: " + path);
		}
		valueAfterTab(lines.get(cursor++), "reference");
		String storedFingerprint = valueAfterTab(lines.get(cursor++), "fingerprint");
		Section initial = readSection(lines, cursor, "initial", path);
		cursor = initial.nextCursor;
		Section incumbent = readSection(lines, cursor, "incumbent", path);
		cursor = incumbent.nextCursor;
		if (cursor != lines.size()) {
			throw new IllegalArgumentException("Unexpected trailing data in fixed initial seed snapshot: " + path);
		}
		FixedInitialColumnSeed seed = new FixedInitialColumnSeed(initial.sequences, incumbent.sequences);
		String actualFingerprint = fingerprint(seed);
		if (!storedFingerprint.equals(actualFingerprint)) {
			throw new IllegalArgumentException("Fixed initial seed fingerprint mismatch: stored="
					+ storedFingerprint + " actual=" + actualFingerprint + " snapshot=" + path);
		}
		return seed;
	}

	public static String fingerprint(FixedInitialColumnSeed seed) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateFingerprint(digest, "initial", seed.getInitialSequences());
			updateFingerprint(digest, "incumbent", seed.getIncumbentSequences());
			StringBuilder value = new StringBuilder(64);
			for (byte item : digest.digest()) {
				value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
			}
			return value.toString();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static void writeSection(List<String> lines, String name, List<List<Integer>> sequences) {
		lines.add(name + "\t" + sequences.size());
		for (List<Integer> sequence : sequences) {
			lines.add(encode(sequence));
		}
	}

	private static Section readSection(List<String> lines, int cursor, String name, Path path) {
		if (cursor >= lines.size()) {
			throw new IllegalArgumentException("Missing " + name + " section in " + path);
		}
		int count = Integer.parseInt(valueAfterTab(lines.get(cursor++), name));
		ArrayList<List<Integer>> sequences = new ArrayList<List<Integer>>(count);
		for (int index = 0; index < count; index++) {
			if (cursor >= lines.size()) {
				throw new IllegalArgumentException("Truncated " + name + " section in " + path);
			}
			sequences.add(decode(lines.get(cursor++)));
		}
		return new Section(sequences, cursor);
	}

	private static String valueAfterTab(String line, String key) {
		String prefix = key + "\t";
		if (!line.startsWith(prefix)) {
			throw new IllegalArgumentException("Expected " + key + " in fixed initial seed snapshot");
		}
		return line.substring(prefix.length());
	}

	private static String encode(List<Integer> sequence) {
		StringBuilder encoded = new StringBuilder();
		for (int index = 0; index < sequence.size(); index++) {
			if (index > 0) {
				encoded.append(',');
			}
			encoded.append(sequence.get(index).intValue());
		}
		return encoded.toString();
	}

	private static List<Integer> decode(String encoded) {
		ArrayList<Integer> sequence = new ArrayList<Integer>();
		if (!encoded.isEmpty()) {
			for (String token : encoded.split(",")) {
				sequence.add(Integer.valueOf(token));
			}
		}
		return sequence;
	}

	private static void updateFingerprint(MessageDigest digest, String name, List<List<Integer>> sequences) {
		digest.update(name.getBytes(StandardCharsets.UTF_8));
		for (List<Integer> sequence : sequences) {
			digest.update((byte) '[');
			for (int job : sequence) {
				digest.update(Integer.toString(job).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) ',');
			}
			digest.update((byte) ']');
		}
	}

	private static final class Section {
		private final List<List<Integer>> sequences;
		private final int nextCursor;

		private Section(List<List<Integer>> sequences, int nextCursor) {
			this.sequences = sequences;
			this.nextCursor = nextCursor;
		}
	}
}
