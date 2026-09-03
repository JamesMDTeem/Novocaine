package haven.automated;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import haven.automated.nbots.core.NLog;

/**
 * [LEAKDBG] Where the widget tick actually goes, by widget class.
 *
 * The 2026-09-03 capture narrowed the framerate problem to one phase and then stopped being able
 * to say anything more. {@code wtick} - {@code UI.tick()}, the whole widget tree - was 65-75% of
 * every frame, 4.6 ms at 550 gobs and 10.4 ms at 2042, while {@code draw} never passed 2.6 ms and
 * {@code gtick} sat at 0.3. So the cost is in the widget tree and scales with what is on screen,
 * and the phase counter cannot say which widget, because it times the tree as one thing.
 *
 * This times each widget's OWN tick, exclusive of its children, and totals by class. Exclusive is
 * the point: inclusive timing makes the root and GameUI account for ~100% of the frame every time
 * and names nothing. Subtracting the children means the class that shows up is the class doing the
 * work.
 *
 * Off unless diagnostic logging is on, and gated at the call site by a plain field read so a
 * client nobody is investigating pays one predictable branch per widget. With it on the cost is a
 * pair of {@code nanoTime} calls and a map lookup per widget per frame - around a quarter of a
 * percent of a frame at a thousand widgets and 100 fps, which is well under what it is measuring.
 *
 * Deliberately keyed by class rather than by instance: "which of the four hundred ItemInfo widgets
 * is slow" is not the question, and holding widget references in a diagnostic map would keep
 * destroyed windows alive - the exact failure this instrumentation exists to hunt.
 */
public class TickProf {
    /** {bufferNanos, calls} per class name. Written by the UI thread, drained by the sampler. */
    private static final Map<String, long[]> acc = new ConcurrentHashMap<>();

    /**
     * Children's time, so a widget can be charged only for its own.
     *
     * A plain static rather than a ThreadLocal: tick dispatch runs on the UI thread and nowhere
     * else, and a ThreadLocal lookup per widget would cost more than the measurement.
     */
    private static long childNanos = 0;

    /** How many classes to name in a sample line. Enough to see a culprit and its runners-up. */
    private static final int TOP = 6;

    private TickProf() {}

    /** Mirrors the diagnostic-logging setting; see {@link NLog#diag}. */
    public static boolean on() {
        return NLog.diag();
    }

    /** Opens a measurement. The return value must be passed to {@link #close}. */
    public static long open() {
        long saved = childNanos;
        childNanos = 0;
        return saved;
    }

    /**
     * Closes a measurement opened by {@link #open}, charging the widget its exclusive time and
     * folding its inclusive time into its parent's child total.
     */
    public static void close(Class<?> cls, long saved, long startNanos) {
        long total = System.nanoTime() - startNanos;
        long self = total - childNanos;
        if (self < 0)
            self = 0;
        childNanos = saved + total;
        long[] e = acc.computeIfAbsent(cls.getSimpleName(), k -> new long[2]);
        /* Plain increments, not atomics: one writer, and a lost sample in a profiler that runs a
         * hundred times a second changes nothing anybody would read differently. */
        e[0] += self;
        e[1]++;
    }

    /**
     * The top classes by exclusive tick time since the last call, and resets.
     *
     * Returned as {@code Class=totalMs/calls}, per second, so the number is directly comparable
     * with the {@code ph=} means beside it in the same line.
     */
    public static String drain() {
        if (acc.isEmpty())
            return "";
        List<Map.Entry<String, long[]>> all = new ArrayList<>(acc.entrySet());
        acc.clear();
        all.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        long other = 0;
        for (Map.Entry<String, long[]> e : all) {
            if (n < TOP) {
                if (n > 0)
                    sb.append(';');
                sb.append(e.getKey()).append('=')
                  .append(String.format("%.2f", e.getValue()[0] / 1e6))
                  .append('/').append(e.getValue()[1]);
                n++;
            } else {
                other += e.getValue()[0];
            }
        }
        if (other > 0)
            sb.append(";+").append(all.size() - TOP).append("more=")
              .append(String.format("%.2f", other / 1e6));
        return sb.toString();
    }
}
