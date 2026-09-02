package haven.automated;

import java.lang.management.ManagementFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.MapView;
import haven.UI;
import haven.automated.nbots.core.NLog;
import haven.render.Environment;
import haven.render.gl.GLEnvironment;

/**
 * [LEAKDBG] GPU/CPU leak diagnostic sampler — Phase 1 of the underground/surface diagnosis.
 *
 * This is instrumentation, not a feature: every line it writes is tagged {@code [LEAKDBG-xxxx]}
 * so the whole class can be removed with a single grep once the leak is found. It must never
 * change gameplay behaviour or block a thread that matters.
 *
 * How it works:
 * <ul>
 *   <li>{@link #tick(UI)} is called once per frame from {@code UILoop.run()} (UI thread). It only
 *       stores a UI reference, bumps a frame counter, and lazily starts the sampler — no
 *       formatting, no I/O, nothing that could cost a frame.</li>
 *   <li>The sampler thread (daemon, ~1 s cadence) reads the GL stats, the JVM heap, a frame rate
 *       derived from the counter, and where the character is standing, and appends one line to
 *       {@code logs/vmem.log} via {@link NLog}. {@code GLEnvironment.memstats()} and
 *       {@code numprogs()} are plain field reads, safe off the GL thread.</li>
 *   <li>{@link #transition(String)} is called from the map-teardown and light-rebuild sites
 *       ({@code MCache.invalblob/trim/trimall}, {@code MapView.lights()}) to bracket GL-heavy
 *       transitions with a tagged line and a delta against the previous transition.</li>
 *   <li>The watchdog fires when total GPU bytes exceed 1 GB, or exceed 2x the first nonzero
 *       session baseline, or the texture object count passes 2000 — dumping the last N samples
 *       so a rare incident self-captures even if nobody was watching.</li>
 * </ul>
 */
public class LeakDbg {
    private static final String LOG = "vmem.log";
    private static final long SAMPLE_MS = 1000L;
    private static final int RING = 64;
    private static final long WATCH_TOTAL_BYTES = 1L << 30;
    private static final double WATCH_BASELINE_MULT = 2.0;
    private static final int WATCH_TEXTURE_OBJECTS = 2000;
    private static final long WATCH_REARM_MS = 10_000L;
    private static final long WATCH_ARM_MS = 30_000L;
    private static final int WATCH_ARM_TEXTURES = 500;
    private static final long CAP_TEXTURE_BYTES = 1L << 31;
    private static final int CAP_CONSECUTIVE = 5;
    private static final int MAP_REFRESH_PERIOD = 1024;

    /**
     * GL texture allocations per frame that count as a draw path leaking, and how many consecutive
     * seconds of it to tolerate before saying so.
     *
     * A healthy frame allocates almost nothing: two, for the light grid, which are disposed. The
     * friend's log sat at 3-6 and collapsed to 30 fps at fifty, so sixteen is comfortably above
     * anything legitimate and well below the level that hurts. This is the line that would have
     * named the bug on day one instead of after a 45 MB log and a per-frame reconstruction, which
     * is the whole reason it exists.
     */
    private static final long CHURN_PER_FRAME = 16;
    private static final int CHURN_CONSECUTIVE = 10;

    /* GLEnvironment.MemStats pool indices: INDICES, VERTICES, TEXTURES, VAOS, FBOS. */
    private static final int I_TEXTURES = 2;

    /**
     * Heap class histogram cadence, in seconds, from {@code -Dleakdbg.heaphist=<seconds>}.
     * Zero or absent disables it. Off by default on purpose: the dump forces a full GC, so it
     * hitches, and every released client writes to this same log.
     */
    private static final long HEAP_HIST_MS = heapHistPeriod();
    private static final int HEAP_HIST_LINES = 40;

    /**
     * Process identity. Several clients run out of one install directory and share
     * {@code logs/vmem.log}, and separating the friend's two interleaved sessions by hand took a
     * nearest-neighbour tracker over texture bytes. This makes it a grep.
     */
    private static final String PID = pid();

    private static String pid() {
        try {
            return (" p" + ProcessHandle.current().pid());
        } catch (Throwable t) {
            return ("");
        }
    }

    private static long heapHistPeriod() {
        try {
            long s = Long.parseLong(System.getProperty("leakdbg.heaphist", "0").trim());
            return ((s <= 0) ? 0 : s * 1000L);
        } catch (RuntimeException e) {
            return (0);
        }
    }

    /** One tag builder, so the pid cannot get left off a site. */
    private static String tag(String name) {
        return ("[LEAKDBG-" + name + PID + "]");
    }

    private static volatile UI ui;
    private static volatile long frames;
    private static volatile MapView mapRef;
    private static volatile long[] lastMemBytes;
    private static volatile int[] lastMemObjs;
    private static volatile long baselineTotal = -1;
    private static volatile long lastWatchTotal = -1;
    private static volatile long lastWatchAt = 0;
    private static volatile long firstNonzeroAt = -1;
    private static volatile boolean watchArmed = false;
    private static volatile int capStreak = 0;
    private static volatile int churnStreak = 0;
    private static volatile long prevSampleFrames;
    private static volatile double prevSampleTime = -1;
    /* Volatile: transition() runs on the Connection worker and on the UI thread. */
    private static volatile long[] prevTransBytes;
    private static volatile int[] prevTransObjs;
    private static final long[] ringT = new long[RING];
    private static final String[] ringL = new String[RING];
    private static int ringHead = 0, ringLen = 0;
    private static volatile Thread sampler;

    /* Per-desc texture accounting: desc -> {bytes, count}. Fed from the GL thread by
     * GLTexture.create/delete via textureAlloc/textureFree; read by the sampler. */
    private static final Map<String, long[]> texHist = new ConcurrentHashMap<>();
    private static final Deque<String> texAllocLog = new ConcurrentLinkedDeque<>();
    private static final int TEX_ALLOC_LOG = 256;
    private static volatile long texAllocCount;
    private static long prevTexAllocCount;
    private static long prevTexAllocAt;

    private LeakDbg() {}

    /**
     * One line, per frame, from the UI thread. Stores references and counts frames only.
     */
    public static void tick(UI ui) {
        /* Gated at the very top rather than at the log call: the cost of this probe is not the
         * writing, it is the sampler and the watchdog running for the whole session, and a
         * client nobody is investigating should not be paying it at all. Checked every frame
         * so the setting takes effect without a restart - it is one volatile read. */
        if (!NLog.diag())
            return;
        LeakDbg.ui = ui;
        frames++;
        if (mapRef == null || (frames & (MAP_REFRESH_PERIOD - 1)) == 0) {
            try {
                mapRef = ui.root.findchild(MapView.class);
            } catch (Throwable ignore) {
                // UI not ready, or the widget tree is mid-teardown; retry next period.
            }
        }
        synchronized (LeakDbg.class) {
            if (sampler == null) {
                sampler = new Thread(LeakDbg::run, "leakdbg-sampler");
                sampler.setDaemon(true);
                sampler.start();
            }
            if (watchdog == null) {
                watchdog = new Thread(LeakDbg::watch, "leakdbg-stall");
                watchdog.setDaemon(true);
                watchdog.start();
            }
        }
    }

    /**
     * Tags a GL-heavy transition (map switch, grid trim, light rebuild). Thread-safe: reads the
     * latest sampler snapshot and logs through NLog's lock. Cheap enough for invalblob's rate.
     */
    public static void transition(String name) {
        if (!NLog.diag())
            return;
        StringBuilder sb = new StringBuilder(tag(name));
        /* Read the GL counters HERE rather than reusing the sampler's snapshot. invalblob and trim
         * fire in the same millisecond, so both used to see the identical once-per-second array and
         * every delta printed "+0B/+0T" - all 42 trimall lines in the friend's log were zero, which
         * looked like a finding and was an artefact. memBytes/memObjects are plain field reads and
         * are safe off the GL thread. */
        long[] mb = null;
        int[] mo = null;
        UI u = ui;
        Environment env = (u == null) ? null : u.getenv();
        if (env instanceof GLEnvironment) {
            GLEnvironment gl = (GLEnvironment) env;
            mb = gl.memBytes();
            mo = gl.memObjects();
        }
        if (mb == null) {
            mb = lastMemBytes;
            mo = lastMemObjs;
        }
        if (mb == null) {
            sb.append(" (no sample yet)");
        } else {
            appendMem(sb, mb, mo);
            if (prevTransBytes != null) {
                sb.append(" d=");
                sb.append(String.format("%+,d", mb[I_TEXTURES] - prevTransBytes[I_TEXTURES]));
                sb.append("B/+");
                sb.append(mo[I_TEXTURES] - prevTransObjs[I_TEXTURES]);
                sb.append('T');
            }
            prevTransBytes = mb;
            prevTransObjs = mo;
        }
        NLog.log(LOG, sb.toString());
    }

    /**
     * Accounts a GL texture alloc by its description. Called from the GL thread inside
     * {@code GLTexture}'s prepare lambdas (create paths). desc is the texture's {@code data.desc},
     * the same string that goes to glObjectLabel.
     */
    public static void textureAlloc(String desc, long bytes) {
        /* Gated even though it writes nothing: the histogram and the ring exist only for the
         * sampler to read, so with the sampler off this is a ConcurrentHashMap update and a
         * deque push per texture created, on the GL thread, for nobody. */
        if (!NLog.diag())
            return;
        String key = histkey(desc);
        long[] a = texHist.computeIfAbsent(key, k -> new long[2]);
        synchronized (a) {
            a[0] += bytes;
            a[1]++;
        }
        texAllocCount++;
        /* The ring keeps the FULL desc, not the collapsed key: telling "one cached texture
         * re-uploaded over and over" from "a new object every frame" needs the identity hash,
         * and 256 entries cannot leak. Only the histogram collapses. */
        texAllocLog.addLast((desc == null || desc.isEmpty()) ? "<anon>" : desc);
        while (texAllocLog.size() > TEX_ALLOC_LOG)
            texAllocLog.pollFirst();
    }

    /**
     * Accounts a GL texture free. Called from the GL thread via {@code GLTexture.delete}.
     */
    public static void textureFree(String desc, long bytes) {
        if (!NLog.diag())
            return;
        String key = histkey(desc);
        long[] a = texHist.get(key);
        if (a != null) {
            boolean empty;
            synchronized (a) {
                a[0] -= bytes;
                if (a[0] < 0)
                    a[0] = 0;
                a[1]--;
                if (a[1] < 0)
                    a[1] = 0;
                empty = (a[0] == 0) && (a[1] == 0);
            }
            /* Drop the entry once nothing of its kind is live. Without this the map only ever
             * grows, which is how the sampler became a suspect in its own heap histogram. */
            if (empty)
                texHist.remove(key, a);
        }
    }

    /**
     * Histogram key for a texture description.
     *
     * A texture with no descriptor of its own is labelled by its wrapper's {@code toString()}, and
     * the common wrappers do not override it - so every single {@code TexI} arrives as a distinct
     * {@code haven.TexI@1a2b3c4d}. Keyed raw, the histogram grew one permanent entry per texture
     * ever created (tens of thousands per minute at the observed churn), leaked ~1-2 MB/min, and
     * was rescanned in full every second. Collapsing a trailing bare identity hash to
     * {@code haven.TexI@} bounds the map by KIND of texture, which is the question the histogram
     * is actually asked, and makes the count column read as "how many of these are live".
     *
     * Only a tail that is genuinely a bare hex identity hash is collapsed; resource descriptors
     * such as {@code #<texr gfx/tiles/thicket-tex(1)>} contain no '@' and survive intact.
     */
    private static String histkey(String desc) {
        if (desc == null || desc.isEmpty())
            return ("<anon>");
        int at = desc.lastIndexOf('@');
        if ((at < 0) || (at == desc.length() - 1))
            return (desc);
        for (int i = at + 1; i < desc.length(); i++) {
            char c = desc.charAt(i);
            if (((c < '0') || (c > '9')) && ((c < 'a') || (c > 'f')))
                return (desc);
        }
        return (desc.substring(0, at + 1));
    }

    /** One entry's byte/count reading, taken before any sorting happens. */
    private static final class Snap {
        final String key;
        final long bytes, count;

        Snap(String key, long bytes, long count) {
            this.key = key;
            this.bytes = bytes;
            this.count = count;
        }
    }

    private static String topTexHist(int max) {
        StringBuilder sb = new StringBuilder();
        /* Snapshot every value BEFORE sorting. Sorting a list whose comparator re-reads the live
         * long[] lets the GL thread move the keys mid-merge, and TimSort answers that with
         * "Comparison method violates its general contract!" - which cost us ten whole samples in
         * the friend's log, each one thrown away by the catch in run(). */
        java.util.List<Snap> live = new java.util.ArrayList<>();
        for (Map.Entry<String, long[]> e : texHist.entrySet()) {
            long[] a = e.getValue();
            long bytes, count;
            synchronized (a) {
                bytes = a[0];
                count = a[1];
            }
            if (bytes > 0)
                live.add(new Snap(e.getKey(), bytes, count));
        }
        live.sort((x, y) -> Long.compare(y.bytes, x.bytes));
        for (Snap s : live) {
            sb.append(s.key).append('=').append(String.format("%,d", s.bytes)).append('B')
              .append('(').append(s.count).append(");");
        }
        if (max > 0 && sb.length() > max)
            return (sb.substring(0, max));
        return (sb.toString());
    }

    private static String lastAllocs() {
        StringBuilder sb = new StringBuilder();
        for (String s : texAllocLog)
            sb.append(s).append(',');
        return (sb.toString());
    }

    /**
     * Dumps a live-object class histogram into the log.
     *
     * The sampler reports totals - {@code totalMemory() - freeMemory()} and the GL pool bytes -
     * which is enough to see a heap leak and useless for naming it. This is the probe that names
     * it: HotSpot's own {@code GC.class_histogram}, reached through the DiagnosticCommand MBean so
     * it needs no JDK, no jcmd, and no cooperation from the person running the client. Diffing two
     * dumps taken half an hour apart points straight at the growing class.
     *
     * It forces a full GC, which is exactly what makes the numbers mean "live" rather than
     * "allocated", and also why it is off unless {@code -Dleakdbg.heaphist=<seconds>} asks for it.
     */
    private static void heapHistogram() {
        try {
            Object out = ManagementFactory.getPlatformMBeanServer().invoke(
                new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram",
                new Object[] {new String[0]},
                new String[] {String[].class.getName()});
            String all = String.valueOf(out);
            StringBuilder sb = new StringBuilder(tag("heapclass"))
                .append(" live objects after a forced GC, top ").append(HEAP_HIST_LINES)
                .append(" by retained bytes\n");
            int n = 0;
            for (String ln : all.split("\n")) {
                sb.append("  ").append(ln.strip()).append('\n');
                /* Two of these are the column header and its underline, so keep two extra. */
                if (++n > HEAP_HIST_LINES + 2)
                    break;
            }
            NLog.log(LOG, sb.toString());
        } catch (Throwable t) {
            /* An unsupported JVM, a security manager, or a renamed MBean. Report once-ish and
             * carry on - the rest of the sampler is still worth having. */
            NLog.log(LOG, tag("heapclass") + " unavailable: " + t);
        }
    }

    /**
     * Frame-stall watchdog: catches a slow frame while it is still running and takes the
     * UI thread's stack.
     *
     * The per-phase timings say which phase a stall lands in, and for utick that answer is
     * "the widget tree", which is where the trail goes cold - the tree is walked twice per
     * frame and every widget in it is a candidate. A stack trace names the method instead
     * of the phase. The UI thread cannot take its own while it is stuck in one, so this
     * runs beside it.
     *
     * Cadence is well under the stalls being chased (observed maxima run from several
     * hundred milliseconds to nine seconds), so a stall is sampled several times over and
     * a repeated frame is only reported once - the interesting output is one stack per
     * stall, not one per poll. Cost is a volatile read every {@value #STALL_POLL_MS} ms
     * and, only while a frame is genuinely overrunning, one getStackTrace.
     */
    private static final long STALL_POLL_MS = 120L;
    private static final double STALL_MS = 400.0;
    private static final long STALL_QUIET_MS = 3000L;
    private static final int STALL_FRAMES = 24;
    private static volatile Thread watchdog;
    private static double lastStallFrame = -1;
    private static long lastStallAt = 0;

    private static void watch() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                double started = haven.UILoop.statframe;
                if (started > 0) {
                    double age = (haven.Utils.rtime() - started) * 1000.0;
                    long now = System.currentTimeMillis();
                    /* Keyed on the frame's own start time so a stall that spans several
                     * polls reports once; the quiet period then keeps a run of separate
                     * bad frames from filling the log with near-identical stacks. */
                    if ((age >= STALL_MS) && (started != lastStallFrame)
                        && ((now - lastStallAt) >= STALL_QUIET_MS)) {
                        lastStallFrame = started;
                        lastStallAt = now;
                        dumpStall(age);
                    }
                }
                Thread.sleep(STALL_POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                /* Never let the watchdog take the client down; it is a probe. */
                try {
                    Thread.sleep(STALL_POLL_MS);
                } catch (InterruptedException e2) {
                    return;
                }
            }
        }
    }

    private static void dumpStall(double age) {
        Thread ui = haven.UILoop.statuithread;
        if (ui == null)
            return;
        int pi = haven.UILoop.statphase;
        String pn = ((pi >= 0) && (pi < haven.UILoop.PHASES.length)) ? haven.UILoop.PHASES[pi] : "?";
        StackTraceElement[] st = ui.getStackTrace();
        StringBuilder sb = new StringBuilder(tag("STALL"));
        sb.append(String.format(" frame running %.0fms, in phase %s - UI thread stack:", age, pn));
        int n = 0;
        for (StackTraceElement e : st) {
            sb.append("\n    ").append(e);
            if (++n >= STALL_FRAMES) {
                sb.append("\n    ... (").append(st.length - n).append(" more)");
                break;
            }
        }
        if (st.length == 0)
            sb.append("\n    (no stack - thread not running Java code)");
        NLog.log(LOG, sb.toString());
    }

    private static void run() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        NLog.log(LOG, tag("jvmargs") + " " + String.join(" ", args));
        long next = System.currentTimeMillis();
        long nextHeapHist = (HEAP_HIST_MS > 0) ? System.currentTimeMillis() : Long.MAX_VALUE;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                sample();
                if (System.currentTimeMillis() >= nextHeapHist) {
                    heapHistogram();
                    nextHeapHist = System.currentTimeMillis() + HEAP_HIST_MS;
                }
            } catch (Throwable t) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                NLog.log(LOG, tag("err") + " " + sw);
            }
            next += SAMPLE_MS;
            long delay = next - System.currentTimeMillis();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /**
     * Allocation rate, per thread, for the threads doing most of it.
     *
     * The heap total already in the sample line says how much is live; it says nothing
     * about churn, and churn is what buys GC pauses. A session measured at a steady 130fps
     * still allocated around 5MB per frame and collected roughly nine times a minute, each
     * collection a 0.25-0.5s hitch - with no leak anywhere and the heap flat across the
     * sawtooth. The total alone cannot distinguish that from a healthy client.
     *
     * Per thread rather than in total because the answer is only actionable if it names
     * something: allocation on the render thread is a per-frame cost in the draw path,
     * allocation on a connection worker is message handling, and the two are not fixed in
     * the same place. Only the busiest few are printed - the rest is noise at this cadence.
     *
     * Costs one getThreadAllocatedBytes call per live thread per sample, which is a cheap
     * read of a counter HotSpot maintains anyway.
     */
    private static com.sun.management.ThreadMXBean allocBean = null;
    private static boolean allocBeanTried = false;
    private static final Map<Long, Long> prevThreadAlloc = new ConcurrentHashMap<>();
    private static long prevAllocAt = 0;

    private static void appendAlloc(StringBuilder sb, long now) {
        try {
            if (!allocBeanTried) {
                allocBeanTried = true;
                /* Says why when it cannot run. The first version of this went
                 * quiet instead, and the reason turned out to be that the JRE
                 * we ship is jlinked without jdk.management, so the class is
                 * simply absent - which is exactly the kind of thing a silent
                 * probe will never tell you, on exactly the runtime whose logs
                 * you are reading. */
                String why = null;
                try {
                    java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean();
                    if (!(b instanceof com.sun.management.ThreadMXBean)) {
                        why = "ThreadMXBean is " + b.getClass().getName() + ", not com.sun.management";
                    } else {
                        com.sun.management.ThreadMXBean sb2 = (com.sun.management.ThreadMXBean) b;
                        if (!sb2.isThreadAllocatedMemorySupported()) {
                            why = "thread allocation counters unsupported by this JVM";
                        } else {
                            if (!sb2.isThreadAllocatedMemoryEnabled())
                                sb2.setThreadAllocatedMemoryEnabled(true);
                            allocBean = sb2;
                        }
                    }
                } catch (Throwable t) {
                    why = t.getClass().getSimpleName() + ": " + t.getMessage()
                        + " (jdk.management missing from the runtime?)";
                }
                if (why != null)
                    NLog.log(LOG, tag("alloc") + " per-thread allocation unavailable - " + why);
            }
            if (allocBean == null)
                return;
            long dt = (prevAllocAt == 0) ? 0 : (now - prevAllocAt);
            prevAllocAt = now;
            long[] ids = allocBean.getAllThreadIds();
            long[] bytes = allocBean.getThreadAllocatedBytes(ids);
            java.util.List<long[]> top = new java.util.ArrayList<>();
            long total = 0;
            for (int i = 0; i < ids.length; i++) {
                if (bytes[i] < 0)
                    continue;
                Long prev = prevThreadAlloc.put(ids[i], bytes[i]);
                if (prev == null || dt <= 0)
                    continue;
                long d = bytes[i] - prev;
                if (d <= 0)
                    continue;
                total += d;
                top.add(new long[] {d, ids[i]});
            }
            if (dt <= 0)
                return;
            sb.append(" alloc=").append(String.format("%.1fMB/s", total * 1000.0 / dt / (1024 * 1024)));
            top.sort((x, y) -> Long.compare(y[0], x[0]));
            int n = 0;
            for (long[] e : top) {
                if (n++ >= ALLOC_TOP_THREADS)
                    break;
                java.lang.management.ThreadInfo ti = allocBean.getThreadInfo(e[1]);
                String nm = (ti == null) ? ("#" + e[1]) : ti.getThreadName();
                /* Spaces out of the thread name: nearly every one of ours has
                 * them ("Haven UI thread"), and a space-separated field inside
                 * a space-separated line cannot be picked back out. */
                sb.append(n == 1 ? " allocby=" : ";")
                  .append(nm.replace(' ', '_')).append('=')
                  .append(String.format("%.1f", e[0] * 1000.0 / dt / (1024 * 1024)));
            }
        } catch (Throwable t) {
            allocBean = null;
        }
    }

    private static final int ALLOC_TOP_THREADS = 4;

    private static void sample() {
        UI u = ui;
        long now = System.currentTimeMillis();
        long f = frames;
        long framesDelta = f - prevSampleFrames;
        double fps = 0.0;
        if (prevSampleTime >= 0) {
            double dt = (now - prevSampleTime) / 1000.0;
            if (dt > 0.0)
                fps = framesDelta / dt;
        }
        prevSampleTime = now;
        prevSampleFrames = f;

        StringBuilder sb = new StringBuilder(tag("samp"));
        long totalBytes = -1;
        int texObjs = -1;
        Environment env = (u == null) ? null : u.getenv();
        if (env instanceof GLEnvironment) {
            GLEnvironment gl = (GLEnvironment) env;
            long[] mb = gl.memBytes();
            int[] mo = gl.memObjects();
            lastMemBytes = mb;
            lastMemObjs = mo;
            totalBytes = mb[0] + mb[1] + mb[2] + mb[3] + mb[4];
            texObjs = mo[I_TEXTURES];
            appendMem(sb, mb, mo);
            sb.append(" progs=").append(gl.numprogs());
        } else {
            sb.append(" gl=<none>");
        }

        Runtime rt = Runtime.getRuntime();
        sb.append(" heap=").append(String.format("%,d", rt.totalMemory() - rt.freeMemory()))
          .append('/').append(String.format("%,d", rt.maxMemory()))
          .append(" fps=").append(String.format("%.1f", fps));
        /* fps alone cannot say why it is what it is. idle separates a loop that
         * is being held back from one that has too much to do; lag says whether
         * the GPU is the one behind. */
        try {
            sb.append(" idle=").append(String.format("%.0f%%", haven.UILoop.statidle * 100.0))
              .append(" gpulag=").append(String.format("%.1fms", haven.UILoop.statlag * 1000.0));
        } catch (Throwable t) {
        }
        /* Which phase of the frame the time went to, mean/max ms over the sample.
         * idle and gpulag narrow a slowdown to "the UI thread has too much to do";
         * only this says what it is doing. Drains the accumulator, so it must be
         * read exactly once per sample. */
        try {
            String ph = haven.UILoop.phasestats();
            if (!ph.isEmpty())
                sb.append(" ph=").append(ph);
        } catch (Throwable t) {
        }
        appendAlloc(sb, now);

        if (prevTexAllocAt == 0)
            prevTexAllocAt = now;
        long allocDelta = texAllocCount - prevTexAllocCount;
        long allocDt = now - prevTexAllocAt;
        prevTexAllocCount = texAllocCount;
        prevTexAllocAt = now;
        sb.append(" tex=").append(String.format("%d/s", allocDt <= 0 ? 0 : allocDelta * 1000L / allocDt));
        /* Per FRAME, not per second, because that is the number that actually tracks the damage:
         * in the friend's log it predicted framerate monotonically (0-4/frame at 128 fps, 50+ at
         * 30) while allocations per second did not, being a product of the rate and the framerate
         * it was destroying. */
        long perFrame = (framesDelta > 0) ? (allocDelta / framesDelta) : 0;
        sb.append(" tex/f=").append(perFrame);
        appendProbes(sb, u);
        String top = topTexHist(300);
        if (!top.isEmpty())
            sb.append(" top=").append(top);

        try {
            MapView map = mapRef;
            if (map != null) {
                Gob pl = map.player();
                if (pl != null) {
                    Coord2d rc = pl.rc;
                    sb.append(" at=").append(String.format("(%.0f,%.0f)", rc.x, rc.y));
                    if (u != null && u.sess != null && u.sess.glob != null) {
                        MCache mcache = u.sess.glob.map;
                        try {
                            String tn = mcache.tileTypeName(mcache.gettile(rc.floor()));
                            sb.append(" tile=").append(tn == null ? "<unmapped>" : tn);
                        } catch (Throwable ignore) {
                            // Loading or an unmapped tile; "at=" alone is enough context.
                        }
                    }
                } else {
                    sb.append(" at=<no player>");
                }
            }
        } catch (Throwable ignore) {
            // The map can be mid-teardown; location is context, never fatal.
        }

        String line = sb.toString();
        pushRing(now, line);
        NLog.log(LOG, line);

        /* A draw path building textures instead of reusing them. Named separately from the VRAM
         * watchdog because it is a different bug with a different fix: the watchdog asks "is too
         * much resident", this asks "is something re-uploading every frame", and a client can fail
         * the second while comfortably passing the first. lastAllocs() is the payload - the descs
         * in it are the allocator. */
        if ((framesDelta > 0) && (perFrame >= CHURN_PER_FRAME)) {
            if (++churnStreak >= CHURN_CONSECUTIVE) {
                churnStreak = 0;
                NLog.log(LOG, String.format(
                    "%s %d GL texture allocs/frame for %d s (fps %.1f) — a draw path is building "
                    + "textures per frame instead of caching them; last allocs name it",
                    tag("CHURN"), perFrame, CHURN_CONSECUTIVE, fps));
                NLog.log(LOG, tag("last") + " " + lastAllocs());
            }
        } else {
            churnStreak = 0;
        }

        if (totalBytes <= 0)
            return;
        if (firstNonzeroAt < 0)
            firstNonzeroAt = now;
        if (baselineTotal <= 0) {
            baselineTotal = totalBytes;
            return;
        }
        if (!watchArmed && ((now - firstNonzeroAt) >= WATCH_ARM_MS || texObjs >= WATCH_ARM_TEXTURES)) {
            watchArmed = true;
            baselineTotal = totalBytes;
            lastWatchTotal = totalBytes;
            lastWatchAt = now;
        }
        boolean watch = totalBytes > WATCH_TOTAL_BYTES
            || (watchArmed && (totalBytes > (long) (baselineTotal * WATCH_BASELINE_MULT)
                || texObjs > WATCH_TEXTURE_OBJECTS));
        if (watch && totalBytes > lastWatchTotal && (now - lastWatchAt) > WATCH_REARM_MS) {
            lastWatchTotal = totalBytes;
            lastWatchAt = now;
            NLog.log(LOG, String.format(
                tag("WATCH") + " gl total=%,d bytes, T=%d objects (session baseline=%,d) — dumping ring",
                totalBytes, texObjs, baselineTotal));
            dumpRing();
            NLog.log(LOG, tag("hist") + " " + topTexHist(0));
            NLog.log(LOG, tag("last") + " " + lastAllocs());
        }

        if (texObjs >= 0 && texBytes() > CAP_TEXTURE_BYTES) {
            capStreak++;
        } else {
            capStreak = 0;
        }
        if (capStreak >= CAP_CONSECUTIVE) {
            capStreak = 0;
            NLog.log(LOG, String.format(
                tag("CAP") + " textures %,dB exceeded %,dB for %d consecutive samples — dumping ring + histogram",
                texBytes(), CAP_TEXTURE_BYTES, CAP_CONSECUTIVE));
            dumpRing();
            NLog.log(LOG, tag("hist") + " " + topTexHist(0));
            NLog.log(LOG, tag("last") + " " + lastAllocs());
            System.gc();
        }
    }

    /**
     * Sizes of the collections that grow for the whole session, appended to every sample as
     * {@code probe=gobs:N,netinfo:N,rescache:N,seen:N,botmap:N}.
     *
     * Cheap enough to run every second (five field reads), and between them they turn a heap slope
     * into a named suspect without waiting on a class histogram. The pair that matters is
     * {@code gobs} against {@code netinfo}: the first is what is live, the second is what OCache
     * still holds, and a gap that widens all session is the leak.
     */
    private static void appendProbes(StringBuilder sb, UI u) {
        StringBuilder p = new StringBuilder();
        try {
            if ((u != null) && (u.sess != null) && (u.sess.glob != null) && (u.sess.glob.oc != null)) {
                p.append("gobs:").append(u.sess.glob.oc.objsz());
                p.append(",netinfo:").append(u.sess.glob.oc.netinfosz());
            }
            if ((u != null) && (u.sess != null))
                p.append(",rescache:").append(u.sess.rescachesz());
        } catch (Throwable ignore) {
            // Mid-teardown or not connected yet; the probes are context, never fatal.
        }
        try {
            haven.automated.mapper.MappingClient mc = haven.automated.mapper.MappingClient.getInstance();
            if (mc != null)
                p.append(",seen:").append(mc.seenmasksz());
        } catch (Throwable ignore) {
            // Mapper never initialised in this session.
        }
        try {
            p.append(",botmap:").append(haven.automated.nbots.world.Observed.gridsz());
        } catch (Throwable ignore) {
            // Botmap not loaded.
        }
        if (p.length() > 0)
            sb.append(" probe=").append(p);
    }

    private static long texBytes() {
        long[] last = lastMemBytes;
        return (last == null) ? -1 : last[I_TEXTURES];
    }

    private static void appendMem(StringBuilder sb, long[] mb, int[] mo) {
        sb.append(" gl=");
        for (int i = 0; i < mb.length; i++) {
            if (i > 0)
                sb.append('/');
            sb.append(String.format("%,d", mb[i])).append("B(")
              .append(String.format("%,d", mo[i])).append(')');
        }
    }

    private static void pushRing(long t, String line) {
        ringT[ringHead] = t;
        ringL[ringHead] = line;
        ringHead = (ringHead + 1) % RING;
        if (ringLen < RING)
            ringLen++;
    }

    private static void dumpRing() {
        StringBuilder sb = new StringBuilder(tag("ring") + " last samples:\n");
        for (int i = 0; i < ringLen; i++) {
            int idx = (ringHead - ringLen + i + RING) % RING;
            sb.append("  ").append(ringT[idx]).append(' ').append(ringL[idx]).append('\n');
        }
        NLog.log(LOG, sb.toString());
    }
}
