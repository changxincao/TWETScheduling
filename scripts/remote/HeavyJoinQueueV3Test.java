import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 只测交接控制，不启动求解器或子进程。 */
public final class HeavyJoinQueueV3Test {
    private static final long MIN = 100_000_000L;
    private static final String PAIR = "[NG join optimization pair] node=6 dssrRound=14 variant=SURVIVORS"
            + " first=old oldMs=1.00 newMs=0.80 oldChecks=100000000 newChecks=100 groupPwlf=2 labelPairs=123";

    public static void main(String[] args) throws Exception {
        testOutput();
        testOldBatchStatus();
        testWaitForExit();
        System.out.println("HeavyJoinQueueV3Test passed");
    }

    private static void testOutput() throws Exception {
        Path path = Files.createTempFile("heavy-join-log-test-", ".log");
        try (RandomAccessFile output = new RandomAccessFile(path.toFile(), "rw");
                RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
            output.writeBytes(PAIR.replace("100000000", "99999999") + "\n");
            output.writeBytes(PAIR.replace("SURVIVORS", "NONE") + "\r\n");
            output.writeBytes("noise\r" + PAIR + "\n");
            output.writeBytes(PAIR.replace("labelPairs=123", "labelPairs=12"));
            require(HeavyJoinQueueV3.readPair(input, MIN, 0L) == null, "partial line and below-threshold row rejected");
            output.writeBytes("3\r");
            require(HeavyJoinQueueV3.readPair(input, MIN, 0L) == null, "CR alone is not a completed record");
            output.writeBytes("\n");
            require(HeavyJoinQueueV3.readPair(input, MIN, System.nanoTime() - 1L) == null,
                    "expired scan must leave the file position unchanged");
            require(PAIR.equals(HeavyJoinQueueV3.readPair(input, MIN, 0L)), "final scan finds a completed late row");
            require(HeavyJoinQueueV3.readPair(input, MIN, 0L) == null, "row not returned twice");
            output.writeBytes(PAIR + "\n");
            require(PAIR.equals(HeavyJoinQueueV3.readPair(input, MIN, 0L)), "LF also accepted");
        } finally {
            Files.delete(path);
        }
    }

    private static void testOldBatchStatus() throws Exception {
        Path root = Files.createTempDirectory("heavy-join-old-status-");
        Path runs = root.resolve("runs");
        String m3 = "extension-shadow-n060-set02-m3-family-high-wwide-f3";
        String m4 = "extension-shadow-n060-set02-m4-family-high-wwide-f3";
        Path p3 = runs.resolve(m3).resolve("status.txt");
        Path p4 = runs.resolve(m4).resolve("status.txt");
        Instant schedulerStart = Instant.now().minusSeconds(2L);
        String recent = "\nupdatedAt=" + Instant.now() + "\n";
        String startFailed = "\nstate=FAILED" + recent + "exitCode=-3\n";
        try {
            Files.createDirectories(p3.getParent());
            Files.createDirectories(p4.getParent());
            Files.writeString(p3, "runId=" + m3 + "\nstate=SUCCEEDED" + recent + "pid=2147483647\n");
            require(!HeavyJoinQueueV3.extensionFinished(root, schedulerStart), "missing worker status is not drained");
            Files.writeString(p4, "runId=" + m4 + "\nstate=RUNNING" + recent);
            require(!HeavyJoinQueueV3.extensionFinished(root, schedulerStart), "running worker is not drained");
            Files.writeString(p4, "runId=" + m3 + "\nstate=FAILED" + recent);
            require(!HeavyJoinQueueV3.extensionFinished(root, schedulerStart), "wrong worker ID is not a valid status");
            Files.writeString(p4, "runId=" + m4 + "\nstate=FAILED\nexitCode=-3\nupdatedAt=" + schedulerStart.minusSeconds(1L));
            require(!HeavyJoinQueueV3.extensionFinished(root, schedulerStart), "stale terminal state is not drained");
            Files.writeString(p4, "runId=" + m4 + "\nstate=FAILED" + recent + "pid=" + ProcessHandle.current().pid());
            require(!HeavyJoinQueueV3.extensionFinished(root, schedulerStart), "terminal state with a live PID is not drained");
            Files.writeString(p4, "runId=" + m4 + "\nstate=SUCCEEDED" + recent);
            require(!HeavyJoinQueueV3.extensionFinished(root, schedulerStart), "partial success missing PID is not drained");
            Files.writeString(p4, "runId=" + m4 + startFailed);
            require(HeavyJoinQueueV3.extensionFinished(root, schedulerStart), "both current terminal states accepted");
        } finally {
            for (Path path : new Path[] {p3, p4, p3.getParent(), p4.getParent(), runs, root}) Files.deleteIfExists(path);
        }
    }

    private static void testWaitForExit() throws Exception {
        var child = new SlowExitProcess();
        Path path = Files.createTempFile("heavy-join-last-scan-", ".log");
        try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
            var result = new AtomicReference<String>();
            var failure = new AtomicReference<Exception>();
            Thread guard = new Thread(() -> {
                try { result.set(HeavyJoinQueueV3.completePair(child, input, null)); }
                catch (Exception ex) { failure.set(ex); }
            });
            guard.start();
            try {
                require(child.waiting.await(2, TimeUnit.SECONDS), "guard waits after the stop request");
                require(child.stopRequested && child.isAlive(), "do not return while child is alive");
                Files.writeString(path, PAIR + "\r\n");
                guard.interrupt();
                require(child.waitAgain.await(2, TimeUnit.SECONDS), "interrupt must lead back to waiting");
                require(result.get() == null && guard.isAlive(), "do not report a pair before the child exits");
            } finally {
                child.exit.countDown();
                guard.join(2000);
            }
            require(!guard.isAlive() && guard.isInterrupted() && !child.isAlive() && failure.get() == null
                    && PAIR.equals(result.get()), "after exit the final scan finds the late row and preserves interruption");
        } finally {
            Files.delete(path);
        }
    }

    private static final class SlowExitProcess extends Process {
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch waitAgain = new CountDownLatch(1);
        final CountDownLatch exit = new CountDownLatch(1);
        volatile boolean stopRequested;

        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public boolean isAlive() { return exit.getCount() != 0L; }
        @Override public void destroy() { stopRequested = true; }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public int waitFor() throws InterruptedException {
            if (waiting.getCount() == 0L) waitAgain.countDown();
            waiting.countDown();
            exit.await();
            return 0;
        }
        @Override public int exitValue() {
            if (isAlive()) throw new IllegalThreadStateException();
            return 0;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
