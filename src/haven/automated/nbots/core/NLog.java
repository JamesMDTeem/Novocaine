package haven.automated.nbots.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
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
 * Thread-safe: bot threads, the UI thread and the watchdog all log here, and none of them waits
 * for the disk - lines are queued and one writer thread puts them in their files (see append).
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
     * Upper bound on how much of a log is ever read in order to trim it. A full
     * read of a hundred-megabyte log on the calling thread froze the UI for half
     * a second (measured 483ms inside wtick: PlgobWatch.heartbeat -> log ->
     * append -> trim, reading a 164MB vmem.log via Files.readAllLines), so
     * trimming only ever looks at the tail of the file.
     */
    private static final int TRIM_TAIL_CAP = 2 * 1024 * 1024;

    /** Banner prefix, kept in one place so the trim scan cannot drift from {@link #banner()}. */
    private static final byte[] BANNER_PREFIX = "-- launch".getBytes(StandardCharsets.US_ASCII);

    /**
     * Cuts a log file down to its last {@link #KEEP_LAUNCHES} launch blocks, so the current launch's
     * banner lands right after them and older runs fall off the end. A launch block is everything
     * between two banner lines; banner lines are the ones starting with {@link #banner()}'s prefix.
     *
     * Only the last {@link #TRIM_TAIL_CAP} bytes are ever read. When the tail holds at least
     * {@link #KEEP_LAUNCHES} banners their positions are exact, so trimming from the oldest of
     * them keeps precisely the last few launches. When it holds fewer (one launch block larger
     * than the cap, or a small file) trimming is skipped for this launch rather than guessing -
     * an untrimmed log is harmless, a guessed cut could eat the current run.
     */
    private static void trimToRecentLaunches(Path file) {
        if (!Files.exists(file))
            return;
        try {
            long size = Files.size(file);
            long start = Math.max(0, size - TRIM_TAIL_CAP);
            byte[] tail = new byte[(int) (size - start)];
            try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
                ch.position(start);
                ByteBuffer buf = ByteBuffer.wrap(tail);
                while (buf.hasRemaining() && (ch.read(buf) != -1)) {
                }
            }
            List<Integer> banners = new ArrayList<>();
            for (int i = 0; i + BANNER_PREFIX.length <= tail.length; i++) {
                if ((i > 0) && (tail[i - 1] != '\n'))
                    continue;
                boolean match = true;
                for (int j = 0; j < BANNER_PREFIX.length; j++) {
                    if (tail[i + j] != BANNER_PREFIX[j]) {
                        match = false;
                        break;
                    }
                }
                if (match)
                    banners.add(i);
            }
            int extra = banners.size() - KEEP_LAUNCHES;
            int from = -1;
            if (extra > 0) {
                from = banners.get(banners.size() - KEEP_LAUNCHES);
            } else if (start > 0) {
                // Fewer launches than we keep fit in the tail, so a single launch wrote more than
                // the cap. This used to skip the trim, which is how vmem.log reached 371 MB. It is
                // safe to cut: this runs before the current launch writes its banner, so nothing
                // of the current run is in the file yet. Keep the tail from a line boundary.
                from = 0;
                while (from < tail.length && tail[from] != '\n')
                    from++;
                from++;
            }
            if (from >= 0 && from < tail.length) {
                byte[] keep = new byte[tail.length - from];
                System.arraycopy(tail, from, keep, 0, keep.length);
                Files.write(file, keep, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException ignore) {
            // Trimming is best-effort; a locked/read-only log should not stop the client.
        }
    }

    private static String banner() {
        String now;
        synchronized (STAMP) {
            now = STAMP.format(new Date());
        }
        return "-- launch " + now + "  git " + gitRev();
    }

    /**
     * One line waiting for the writer thread, or a flush request (file == null).
     */
    private static final class Pending {
        final String file, line;
        final java.util.concurrent.CountDownLatch done;

        Pending(String file, String line, java.util.concurrent.CountDownLatch done) {
            this.file = file;
            this.line = line;
            this.done = done;
        }
    }

    private static final java.util.concurrent.LinkedBlockingQueue<Pending> queue = new java.util.concurrent.LinkedBlockingQueue<>();
    private static volatile Thread writer = null;

    /**
     * Queues a line; the "nlog-writer" thread puts it in the file. Callers never touch the disk.
     *
     * Every write used to happen on the caller's thread, under one lock shared by every caller:
     * open the file, append a line, close it. On Windows that is an open and a close per line, each
     * of which a virus scanner may inspect, and the UI thread logs from its tick - so a UI-thread line
     * waited behind whatever a bot thread was writing, a thread dump included, and paid its own
     * open. A stall capture on 2026-09-12 caught the frame 498 ms inside this method. Order within a
     * file is kept, since one thread writes everything; the timestamp is taken here, when the event
     * happened, not when it reached the disk.
     */
    private static void append(String file, String line) {
        String stamped;
        synchronized (STAMP) {
            stamped = STAMP.format(new Date()) + " " + line;
        }
        startWriter();
        queue.add(new Pending(file, stamped, null));
    }

    private static void startWriter() {
        if (writer != null)
            return;
        synchronized (LOCK) {
            if (writer != null)
                return;
            Thread t = new Thread(NLog::drain, "nlog-writer");
            t.setDaemon(true);
            t.start();
            writer = t;
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> flush(2000), "nlog-flush"));
            } catch (IllegalStateException e) {
                /* already exiting */
            }
        }
    }

    /**
     * Blocks until everything queued before the call is in its file, or the timeout passes. For
     * shutdown and for harnesses that read a log back; nothing on a frame path should call it.
     */
    public static void flush(long timeoutMs) {
        if (writer == null)
            return;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        queue.add(new Pending(null, null, done));
        try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void flush() {
        flush(10000);
    }

    /** The writer: takes whatever is queued, writes each file's lines in one append, repeats. */
    private static void drain() {
        List<Pending> batch = new ArrayList<>();
        while (true) {
            try {
                batch.add(queue.take());
            } catch (InterruptedException e) {
                continue;
            }
            queue.drainTo(batch);
            Map<String, StringBuilder> byFile = new java.util.LinkedHashMap<>();
            List<java.util.concurrent.CountDownLatch> flushed = new ArrayList<>();
            for (Pending p : batch) {
                if (p.file == null)
                    flushed.add(p.done);
                else
                    byFile.computeIfAbsent(p.file, k -> new StringBuilder()).append(p.line).append(System.lineSeparator());
            }
            batch.clear();
            for (Map.Entry<String, StringBuilder> e : byFile.entrySet())
                write(e.getKey(), e.getValue().toString());
            for (java.util.concurrent.CountDownLatch l : flushed)
                l.countDown();
        }
    }

    private static void write(String file, String text) {
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            Path f = dir.resolve(file);
            if (bootstrapped.add(file)) {
                trimToRecentLaunches(f);
                text = banner() + System.lineSeparator() + text;
            }
            Files.write(f, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignore) {
            // Logging must never be the thing that brings the client down.
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
