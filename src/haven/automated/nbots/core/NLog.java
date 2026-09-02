package haven.automated.nbots.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Minimal append-only file logging, for diagnosing problems that only show up in the running
 * client - crashes, freezes, and bot behaviour. Everything lands under a "logs" folder in the
 * client's working directory (bin\ when launched via Play.bat), next to the other data files the
 * client writes there (hitboxes.db, alchemy-book-dump.json).
 *
 * Each launch opens every log with a banner line that records the timestamp and the git revision
 * the client was built from (read from the /buildinfo classpath resource the build writes). That
 * makes a log file a forensic artefact: you can tell at a glance which build produced a given run
 * and whether it was a dirty checkout. When a file is opened for the first time in a launch, it is
 * trimmed down to the last few launch blocks so a long-running client doesn't grow logs without
 * bound.
 *
 * Thread-safe by a single lock: bot threads, the UI thread and the watchdog all write here.
 */
public class NLog {
    /**
     * Whether diagnostic logging is on. Off unless somebody has asked for it.
     *
     * The distinction being drawn is between logs that are part of using the client and logs
     * that only exist to answer a question somebody is currently asking. Bot activity, hearth
     * travel, survey results, crashes and the output of console commands are the first kind and
     * always write. The frame sampler, the stall watchdog, the camera trace, the shader-cache
     * timings and the sight measurements are the second kind: they cost work every frame and
     * every second, they write megabytes an hour, and outside an active investigation nobody
     * reads them. Those go behind this.
     *
     * Cached rather than read from prefs per call - these are on per-frame paths, and
     * Preferences is a synchronized lookup, not a field read. {@link #diag(boolean)} keeps it
     * current when the setting is toggled.
     */
    private static volatile boolean diag = haven.Utils.getprefb("diagnosticLogging", false);

    /** Whether diagnostic logging is currently on. */
    public static boolean diag() {
        return diag;
    }

    /** Called by the setting when it changes, so the cached flag does not go stale. */
    public static void diag(boolean on) {
        diag = on;
    }

    /** Logs only when diagnostic logging is on. */
    public static void diag(String file, String line) {
        if (diag)
            log(file, line);
    }

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile boolean handlerInstalled = false;

    /** Banners to keep from the previous run when trimming a log at launch. */
    private static final int KEEP_LAUNCHES = 3;

    /** Files already opened (and trimmed/bannered) this launch. Per-JVM, so trimming is once per run. */
    private static final Set<String> bootstrapped = new HashSet<>();

    private static volatile String rev;

    private static Path dir() {
        return Paths.get("logs");
    }

    /**
     * The git identity of the running build, from the /buildinfo resource the build drops on the
     * classpath. Returns "unknown" when it can't be read (e.g. running from an IDE without a
     * build). Appends "(dirty)" when the checkout had uncommitted changes at build time - a log
     * from a half-edited tree otherwise looks identical to a clean one.
     */
    private static String gitRev() {
        String r = rev;
        if (r == null) {
            synchronized (LOCK) {
                if (rev == null)
                    rev = loadGitRev();
                r = rev;
            }
        }
        return r;
    }

    private static String loadGitRev() {
        try (InputStream in = NLog.class.getResourceAsStream("/buildinfo")) {
            if (in == null)
                return "unknown";
            Properties p = new Properties();
            p.load(in);
            String rev = p.getProperty("git-rev", "unknown").trim();
            if ("true".equals(p.getProperty("git-dirty-flag", "false").trim()))
                rev += " (dirty)";
            return rev.isEmpty() ? "unknown" : rev;
        } catch (IOException ignore) {
            return "unknown";
        }
    }

    /**
     * Cuts a log file down to its last {@link #KEEP_LAUNCHES} launch blocks, so the current launch's
     * banner lands right after them and older runs fall off the end. A launch block is everything
     * between two banner lines; banner lines are the ones starting with {@link #banner()}'s prefix.
     */
    private static void trimToRecentLaunches(Path file) {
        if (!Files.exists(file))
            return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Integer> banners = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++)
                if (lines.get(i).startsWith("-- launch"))
                    banners.add(i);
            int extra = banners.size() - KEEP_LAUNCHES;
            if (extra > 0) {
                int from = banners.get(banners.size() - KEEP_LAUNCHES);
                Files.write(file, lines.subList(from, lines.size()), StandardCharsets.UTF_8);
            }
        } catch (IOException ignore) {
            // Trimming is best-effort; a locked/read-only log should not stop the client.
        }
    }

    private static String banner() {
        return "-- launch " + STAMP.format(new Date()) + "  git " + gitRev();
    }

    private static void append(String file, String line) {
        synchronized (LOCK) {
            try {
                Path dir = dir();
                Files.createDirectories(dir);
                Path f = dir.resolve(file);
                if (bootstrapped.add(file)) {
                    trimToRecentLaunches(f);
                    Files.write(f, (banner() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                Files.write(f, (STAMP.format(new Date()) + " " + line + System.lineSeparator())
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
