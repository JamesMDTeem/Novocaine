package haven.automated.nbots.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * Minimal append-only file logging, for diagnosing problems that only show up in the running
 * client - crashes, freezes, and bot behaviour. Everything lands under a "logs" folder in the
 * client's working directory (bin\ when launched via Play.bat), next to the other data files the
 * client writes there (hitboxes.db, alchemy-book-dump.json).
 *
 * Thread-safe by a single lock: bot threads, the UI thread and the watchdog all write here.
 */
public class NLog {
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile boolean handlerInstalled = false;

    private static Path dir() {
        return Paths.get("logs");
    }

    private static void append(String file, String line) {
        synchronized (LOCK) {
            try {
                Path dir = dir();
                Files.createDirectories(dir);
                Files.write(dir.resolve(file),
                    (STAMP.format(new Date()) + " " + line + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignore) {
                // Logging must never be the thing that brings the client down.
            }
        }
    }

    /** General-purpose line into a named log file (e.g. "autolp.log"). */
    public static void log(String file, String message) {
        append(file, "[" + Thread.currentThread().getName() + "] " + message);
    }

    /** Records an exception, with its full stack, into crash.log. */
    public static void crash(String context, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        append("crash.log", "EXCEPTION in " + context + " on ["
            + Thread.currentThread().getName() + "]\n" + sw);
    }

    /**
     * Dumps every live thread's stack into crash.log. This is the one useful artefact when the
     * client FREEZES rather than throws - a deadlock produces no exception, so the only way to see
     * which two threads are stuck on each other is a full dump taken while they're stuck.
     */
    public static void dumpAllThreads(String reason) {
        StringBuilder sb = new StringBuilder("THREAD DUMP: ").append(reason).append('\n');
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread th = e.getKey();
            sb.append("  \"").append(th.getName()).append("\" ").append(th.getState())
              .append(th.isDaemon() ? " (daemon)" : "").append('\n');
            for (StackTraceElement el : e.getValue())
                sb.append("      at ").append(el).append('\n');
        }
        append("crash.log", sb.toString());
    }

    /**
     * Installs a process-wide handler so an exception that kills any thread (a bot thread dying
     * uncaught, say) is recorded instead of vanishing to a console nobody is watching. Idempotent.
     */
    public static void installUncaughtHandler() {
        if (handlerInstalled)
            return;
        handlerInstalled = true;
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            crash("uncaught on thread " + thread.getName(), ex);
            if (prev != null)
                prev.uncaughtException(thread, ex);
        });
    }
}
