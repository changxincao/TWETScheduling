import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** 关闭分项计时后的低频主线程栈采样对照；样本比例不是精确墙钟时间。 */
public final class JoinJfrSummary {
    private static final String OWNER = "TWETBPC.GC.GCNGBBStyleBidirectionalNgDssr";

    public static void main(String[] args) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String key : List.of("group_bound", "bitmap_or", "remaining_labels", "prefilter_other", "other")) {
            counts.put(key, 0L);
        }
        long mainSamples = 0, joinSamples = 0, functionSamples = 0;
        try (RecordingFile recording = new RecordingFile(Path.of(args[0]));
                BufferedWriter trace = Files.newBufferedWriter(Path.of(args[1]))) {
            trace.write("time\tcategory\tstack\n");
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample")) continue;
                RecordedThread thread = event.getThread("sampledThread");
                if (thread == null || !thread.getJavaName().equals("main") || event.getStackTrace() == null) continue;
                mainSamples++;
                List<RecordedFrame> frames = event.getStackTrace().getFrames();
                boolean join = frames.stream().anyMatch(f -> f.getMethod().getType().getName().equals(OWNER)
                        && f.getMethod().getName().startsWith("joinAllForwardTerminalGroups"));
                if (!join) continue;
                joinSamples++;
                String category;
                if (has(frames, OWNER, "canPruneJoinEnvelopeGroupPair")
                        || has(frames, OWNER + "$JoinEnvelopeIndex", "canPruneGroup")) {
                    category = "group_bound";
                    if (has(frames, OWNER, "findMinimalShiftedTracedSum")) functionSamples++;
                } else if (has(frames, "java.util.BitSet", "or")
                        && has(frames, OWNER + "$JoinEnvelopeIndex", "prunedForwardIndices")) {
                    category = "bitmap_or";
                } else if (has(frames, OWNER + "$JoinEnvelopeIndex", "prunedForwardIndices")) {
                    category = "prefilter_other";
                } else if (has(frames, OWNER, "joinForwardGroupWithBackward") || has(frames, OWNER, "tryJoin")) {
                    category = "remaining_labels";
                } else {
                    category = "other";
                }
                counts.put(category, counts.get(category) + 1);
                trace.write(event.getStartTime() + "\t" + category + "\t" + frames.stream()
                        .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
                        .collect(Collectors.joining(";")) + "\n");
            }
        }
        if (joinSamples == 0) throw new AssertionError("No join samples");
        System.err.printf(Locale.US, "mainSamples=%d joinSamples=%d pwlfSamplesWithinBound=%d%n",
                mainSamples, joinSamples, functionSamples);
        System.out.println("category,samples,percentOfJoin");
        for (var entry : counts.entrySet()) {
            System.out.printf(Locale.US, "%s,%d,%.6f%n", entry.getKey(), entry.getValue(),
                    100.0 * entry.getValue() / joinSamples);
        }
    }

    private static boolean has(List<RecordedFrame> frames, String type, String name) {
        return frames.stream().anyMatch(f -> f.getMethod().getType().getName().equals(type)
                && f.getMethod().getName().equals(name));
    }
}
