import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.tools.attach.VirtualMachine;

/** 仅切换调度器未来NG子进程的classpath，不修改求解器JVM。 */
public final class NgV6SchedulerSwitch {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("pid agentJar settingsFile");
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); }
        finally { vm.detach(); }
    }

    public static void agentmain(String settingsFile, Instrumentation instrumentation) throws Exception {
        var settings = new java.util.Properties();
        try (var reader = Files.newBufferedReader(Path.of(settingsFile))) { settings.load(reader); }
        String command = System.getProperty("sun.java.command", "");
        if (!command.startsWith("HEU.ExperimentBatchScheduler ")
                || !command.replace('\\', '/').contains(settings.getProperty("manifest").replace('\\', '/'))) {
            throw new IllegalStateException("Not the expected scheduler: " + command);
        }
        String nextCp = settings.getProperty("classpath");
        for (String part : nextCp.split(java.io.File.pathSeparator)) {
            if (!Files.isRegularFile(Path.of(part))) throw new IllegalStateException("Missing classpath: " + part);
        }
        Class<?> scheduler = Class.forName("HEU.ExperimentBatchScheduler");
        byte[] replacement = Files.readAllBytes(Path.of(settings.getProperty("replacement")));
        instrumentation.redefineClasses(new ClassDefinition(scheduler, replacement));
        System.setProperty("twet.scheduler.ngV6Classpath", nextCp);
        if(settings.getProperty("retryRunId") != null) {
            System.setProperty("twet.scheduler.retryManifest", settings.getProperty("manifest"));
            System.setProperty("twet.scheduler.retryNgOnce", settings.getProperty("retryRunId"));
        }
        Files.writeString(Path.of(settings.getProperty("receipt")),
                "pid=" + ProcessHandle.current().pid() + "\ncommand=" + command
                + "\nngClasspath=" + nextCp + "\nappliedAt=" + java.time.Instant.now() + "\n");
    }
}
