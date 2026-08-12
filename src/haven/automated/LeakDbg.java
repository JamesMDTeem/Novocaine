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

    /* GLEnvironment.MemStats pool indices: INDICES, VERTICES, TEXTURES, VAOS, FBOS. */
    private static final int I_TEXTURES = 2;

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
    private static volatile long prevSampleFrames;
    private static volatile double prevSampleTime = -1;
    private static long[] prevTransBytes;
    private static int[] prevTransObjs;
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
        }
    }

    /**
     * Tags a GL-heavy transition (map switch, grid trim, light rebuild). Thread-safe: reads the
     * latest sampler snapshot and logs through NLog's lock. Cheap enough for invalblob's rate.
     */
    public static void transition(String tag) {
        StringBuilder sb = new StringBuilder("[LEAKDBG-").append(tag).append(']');
        long[] mb = lastMemBytes;
        int[] mo = lastMemObjs;
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
        String key = (desc == null || desc.isEmpty()) ? "<anon>" : desc;
        long[] a = texHist.computeIfAbsent(key, k -> new long[2]);
        synchronized (a) {
            a[0] += bytes;
            a[1]++;
        }
        texAllocCount++;
        texAllocLog.addLast(key);
        while (texAllocLog.size() > TEX_ALLOC_LOG)
            texAllocLog.pollFirst();
    }

    /**
     * Accounts a GL texture free. Called from the GL thread via {@code GLTexture.delete}.
     */
    public static void textureFree(String desc, long bytes) {
        String key = (desc == null || desc.isEmpty()) ? "<anon>" : desc;
        long[] a = texHist.get(key);
        if (a != null) {
            synchronized (a) {
                a[0] -= bytes;
                if (a[0] < 0)
                    a[0] = 0;
                a[1]--;
                if (a[1] < 0)
                    a[1] = 0;
            }
        }
    }

    private static String topTexHist(int max) {
        StringBuilder sb = new StringBuilder();
        java.util.List<Map.Entry<String, long[]>> live = new java.util.ArrayList<>();
        for (Map.Entry<String, long[]> e : texHist.entrySet()) {
            long[] a = e.getValue();
            synchronized (a) {
                if (a[0] > 0)
                    live.add(e);
            }
        }
        live.sort((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]));
        for (Map.Entry<String, long[]> e : live) {
            long[] a = e.getValue();
            sb.append(e.getKey()).append('=').append(String.format("%,d", a[0])).append('B')
              .append('(').append(a[1]).append(");");
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

    private static void run() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        NLog.log(LOG, "[LEAKDBG-jvmargs] " + String.join(" ", args));
        long next = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                sample();
            } catch (Throwable t) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                NLog.log(LOG, "[LEAKDBG-err] " + sw);
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

    private static void sample() {
        UI u = ui;
        long now = System.currentTimeMillis();
        long f = frames;
        double fps = 0.0;
        if (prevSampleTime >= 0) {
            double dt = (now - prevSampleTime) / 1000.0;
            if (dt > 0.0)
                fps = (f - prevSampleFrames) / dt;
        }
        prevSampleTime = now;
        prevSampleFrames = f;

        StringBuilder sb = new StringBuilder("[LEAKDBG-samp]");
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

        if (prevTexAllocAt == 0)
            prevTexAllocAt = now;
        long allocDelta = texAllocCount - prevTexAllocCount;
        long allocDt = now - prevTexAllocAt;
        prevTexAllocCount = texAllocCount;
        prevTexAllocAt = now;
        sb.append(" tex=").append(String.format("%d/s", allocDt <= 0 ? 0 : allocDelta * 1000L / allocDt));
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
                "[LEAKDBG-WATCH] gl total=%,d bytes, T=%d objects (session baseline=%,d) — dumping ring",
                totalBytes, texObjs, baselineTotal));
            dumpRing();
            NLog.log(LOG, "[LEAKDBG-hist] " + topTexHist(0));
            NLog.log(LOG, "[LEAKDBG-last] " + lastAllocs());
        }

        if (texObjs >= 0 && texBytes() > CAP_TEXTURE_BYTES) {
            capStreak++;
        } else {
            capStreak = 0;
        }
        if (capStreak >= CAP_CONSECUTIVE) {
            capStreak = 0;
            NLog.log(LOG, String.format(
                "[LEAKDBG-CAP] textures %,dB exceeded %,dB for %d consecutive samples — dumping ring + histogram",
                texBytes(), CAP_TEXTURE_BYTES, CAP_CONSECUTIVE));
            dumpRing();
            NLog.log(LOG, "[LEAKDBG-hist] " + topTexHist(0));
            NLog.log(LOG, "[LEAKDBG-last] " + lastAllocs());
            System.gc();
        }
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
        StringBuilder sb = new StringBuilder("[LEAKDBG-ring] last samples:\n");
        for (int i = 0; i < ringLen; i++) {
            int idx = (ringHead - ringLen + i + RING) % RING;
            sb.append("  ").append(ringT[idx]).append(' ').append(ringL[idx]).append('\n');
        }
        NLog.log(LOG, sb.toString());
    }
}
