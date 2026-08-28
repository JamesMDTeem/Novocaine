package haven.automated.survey;

import haven.Area;
import haven.Coord;
import haven.GameUI;
import haven.Gob;
import haven.IMeter;
import haven.Label;
import haven.Loading;
import haven.MCache;
import haven.Widget;
import haven.Window;
import haven.automated.nbots.core.NLog;

import java.awt.Color;
import java.util.Arrays;

import static haven.MCache.tilesz;

/**
 * Instrumentation for the land-survey levelling problem, behind the {@code :surv} console command.
 *
 * These are probes, not a tool. Each one answers a question that has to be settled before a
 * planner is worth writing, and the answers all land in {@code logs/survey.log}.
 *
 * <p>The arithmetic being checked is the server resource's own. {@code res/ui/surv} ships its
 * preprocessed source, and {@code LandSurvey.updmap} computes the three numbers the window shows
 * as, over every vertex of the survey:
 *
 * <pre>
 *     vz = round(map.getfz(vc) * gran)   // current ground, quantised
 *     tz = data.dz[...]                  // the target the survey is set to
 *     sd += tz - vz                      // "soil required" when &gt;= 0, "left over" when &lt; 0
 *     hn += max(0, vz - tz)              // "units of soil to dig"
 * </pre>
 *
 * <p>Two consequences drive everything here. {@code sd} is exactly linear in a flat target level -
 * {@code sd(t) = n*t - sum(vz)} - so it is zero precisely at the mean, which is why the window's
 * own "Ground plane" button (and the default state of a fresh survey) is already the cheapest
 * flat plane for a single survey. And {@code hn} counts only the positive excess, which confirms
 * that filling is fed out of the dig rather than paid for separately - the reason the optimum is
 * the mean and not the median.
 *
 * <p>Over a region that has to end up as one flat plane, the target level is forced (the region's
 * own mean), so the total dig is fixed and cannot be optimised at all. What is left to optimise is
 * the partition into surveys: soil that finds its low spot inside its own survey is placed by the
 * survey itself, and soil that cannot has to be stockpiled and carried. How much that is worth
 * depends on what carrying costs, which is a feel for the game rather than something derivable
 * here, so {@link #plan} sweeps the exchange rate instead of assuming one.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code :surv dump} - the player's grid as a vertex height field, with statistics.
 *   <li>{@code :surv inspect} - reflect into an open survey window and cross-check our
 *       reimplementation of {@code updmap} against the three labels it is actually showing.
 *   <li>{@code :surv plan [maxSide]} - best partition at each carrying-cost exchange rate.
 *   <li>{@code :surv write [level]} - write a target plane into an open survey and send it.
 *   <li>{@code :surv cost [on|off]} - sample meters and survey labels over time.
 * </ul>
 */
public class SurveyProbe {
    private static final String LOG = "survey.log";

    /** The survey window's title, as {@code LandSurvey} constructs it. */
    private static final String WINDOW = "Land survey";

    /**
     * Vertical quantisation as read off a live survey window, or 0 before {@code :surv inspect}
     * has seen one. The resource computes it as {@code args[2] / 11} from a server-supplied
     * number, so it cannot be derived client-side - it has to be observed once. Everything that
     * only ranks partitions works in raw client z and does not need it.
     */
    private static volatile float gran = 0f;

    private static volatile Thread sampler = null;

    /**
     * The level the last {@code :surv write} asked for, so {@code :surv inspect} can say whether it
     * survived. Acceptance is only really proven by closing the survey window and reopening it -
     * that re-reads the target from the server rather than from the array we just wrote.
     */
    private static volatile Integer lastWrite = null;

    /**
     * The survey window's widget id at the moment of that write. The server issues a fresh id per
     * open, so an id that has changed since the write is proof the window was torn down and rebuilt
     * from {@code mkwidget} - which reads the target out of the server's own message rather than out
     * of the array we wrote. That is the difference between "our mutation stuck locally" and "the
     * server stored it".
     */
    private static volatile int lastWriteWid = -1;

    // ------------------------------------------------------------------ dispatch

    public static void run(GameUI gui, String[] args) {
        String cmd = (args.length > 1) ? args[1] : "help";
        try {
            switch (cmd) {
                case "dump":
                    dump(gui);
                    break;
                case "inspect":
                    inspect(gui);
                    break;
                case "plan":
                    plan(gui, (args.length > 2) ? Integer.parseInt(args[2]) : 31);
                    break;
                case "write":
                    write(gui, (args.length > 2) ? Integer.valueOf(Integer.parseInt(args[2])) : null);
                    break;
                case "cost":
                    cost(gui, (args.length > 2) ? args[2] : "toggle");
                    break;
                default:
                    say(gui, "usage: :surv dump | inspect | plan [maxSide] | write [level] "
                        + "| cost [on|off]");
            }
        } catch (Loading l) {
            say(gui, "surv " + cmd + ": terrain still loading, try again");
        } catch (Exception e) {
            NLog.crash("SurveyProbe." + cmd, e);
            say(gui, "surv " + cmd + " failed: " + e);
        }
    }

    private static void say(GameUI gui, String text) {
        gui.msg(text, Color.WHITE);
    }

    private static void log(String line) {
        NLog.log(LOG, line);
    }

    private static void dump(GameUI gui) {
        Heights hs = Heights.read(gui);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : hs.z) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double t = hs.mean();
        double cut = hs.dig(t);
        double fill = cut;   // equal at the mean by construction; see the log line below
        log("== dump: grid at " + hs.ul + ", " + hs.w + "x" + hs.h + " vertices, "
            + hs.missing + " unloaded ==");
        log(String.format("raw client z: min %.4f  max %.4f  mean %.4f  span %.4f",
            min, max, t, max - min));
        log(String.format("at the mean: cut %.2f  fill %.2f  (equal by construction; both in raw z, "
            + "multiply by gran for the window's units)", cut, fill));
        log("gran " + (gran > 0 ? Float.toString(gran) : "unknown - run ':surv inspect' on an open survey"));
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < hs.h; y++) {
            sb.setLength(0);
            for (int x = 0; x < hs.w; x++) {
                if (x > 0)
                    sb.append('\t');
                sb.append(String.format("%.4f", hs.z[y * hs.w + x]));
            }
            log("z[" + y + "] " + sb);
        }
        say(gui, String.format("surv dump: %dx%d vertices, mean %.3f, span %.3f -> logs/%s",
            hs.w, hs.h, t, max - min, LOG));
    }

    // ------------------------------------------------------------------ partition study

    /**
     * Measures how much of the levelling work is addressable by choosing survey boundaries.
     *
     * With the region required to finish as one flat plane, the target level {@code t} is not a
     * free variable: no soil comes from outside, so {@code t} is the region's own mean. That fixes
     * the total cut - splitting the region into surveys only splits that sum, it does not change
     * it. The digging therefore cannot be optimised at all, and everything left to win is in the
     * carrying.
     *
     * <p>What the carrying costs is NOT simply how much soil leaves each survey. Soil can only be
     * placed inside the survey that is being filled, so a surplus has to be stockpiled and carried
     * to wherever it is wanted - possibly several surveys away, and re-handled on the way if the
     * work is done survey by survey. The cost is amount times distance, which makes the objective
     * a transportation problem over the lattice of surveys:
     *
     * <pre>
     *     minimise  sum over pairs  moved(i -&gt; j) * hops(i, j)
     * </pre>
     *
     * <p>That is emphatically not the same as minimising {@code sum |sd_j|}, the per-survey
     * imbalance, and on real terrain the two disagree: a partition that reduces how much soil
     * leaves its survey can easily send what does leave a great deal further. Both numbers are
     * reported so the disagreement stays visible, but the hill-climb optimises the transport cost,
     * which is the one that maps to work.
     *
     * <p>Ranking partitions needs no vertical quantisation, so this works in raw client z.
     */
    private static void plan(GameUI gui, int maxSide) {
        Heights hs = Heights.read(gui);
        if (hs.missing > 0)
            log("WARNING: " + hs.missing + " unloaded vertices read as 0; numbers below are wrong");
        Thread t = new Thread(() -> search(gui, hs, maxSide), "survey-plan");
        t.setDaemon(true);
        t.start();
        say(gui, "surv plan: searching, result into logs/" + LOG);
    }

    /**
     * Sweeps the one judgement call rather than making it.
     *
     * How much a partition is worth depends on what carrying soil actually costs, and that is a
     * feel for the game rather than something derivable from the client. The two ends of the range
     * give genuinely different answers - at one end you only care how much soil has to be carried
     * at all, at the other only how far it goes, and on real terrain the best partition for one is
     * measurably worse for the other. So this reports the best partition at several exchange rates
     * and lets the reader pick the row that matches the work, instead of baking in a guess.
     *
     * <p>Runs off the UI thread: a few thousand min-cost flows is not instant.
     */
    private static void search(GameUI gui, Heights hs, int maxSide) {
        int span = hs.w - 1;
        double t = hs.mean();
        double dig = hs.dig(t);

        log("== plan: region at " + hs.ul + ", " + span + "x" + span + " tiles, max survey side "
            + maxSide + " ==");
        log(String.format("target level (region mean) %.4f; total dig %,.0f units - fixed, the "
            + "digging cannot be optimised once the result must be flat", t, dig));
        log("");
        log("w is what one survey-hop of distance is worth in pickups: w=0 counts only how much");
        log("soil must be carried, large w counts only how far it travels. 'carried' is soil that");
        log("cannot be placed inside its own survey; 'unit-hops' is that soil times the survey");
        log("boundaries it crosses, routed as well as the partition allows.");
        log(String.format("  %-6s %-44s %12s %12s", "w", "cuts", "carried", "unit-hops"));
        row(hs, "even", SurveyPlanner.even(span, maxSide), SurveyPlanner.even(span, maxSide), t, dig);
        for (double w : WEIGHTS) {
            int[][] best = SurveyPlanner.optimise(hs, maxSide, t, w);
            row(hs, String.format("%.2f", w), best[0], best[1], t, dig);
        }
        say(gui, "surv plan: swept " + WEIGHTS.length + " distance weights -> logs/" + LOG);
    }

    /** Exchange rates between one pickup and one survey-hop of carrying, low to high. */
    private static final double[] WEIGHTS = {0, 0.25, 1, 4};

    private static void row(Heights hs, String tag, int[] xc, int[] yc, double t, double dig) {
        double[] sd = SurveyPlanner.nets(hs, xc, yc, t);
        double carried = SurveyPlanner.carried(sd);
        /* Total flow is fixed for a partition, so minimising (1 + 1*hops) per unit minimises the
         * hop count exactly - the constant term cannot change the routing. That makes hops
         * readable straight off a single solve. */
        double hops = SurveyPlanner.hops(sd, xc.length - 1, yc.length - 1);
        log(String.format("  %-6s %-44s %12s %12s", tag,
            Arrays.toString(xc) + " " + Arrays.toString(yc),
            String.format("%,.0f", carried), String.format("%,.0f", hops)));
        log(String.format("  %-6s %-44s %11.0f%% of the dig, %.2f hops each", "", "",
            (dig <= 0) ? 0.0 : 100.0 * carried / dig, (carried <= 0) ? 0.0 : hops / carried));
    }

    // ------------------------------------------------------------------ live survey window

    private static Object field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    /**
     * Reflects into an open survey window and re-derives its three labels from {@link MCache}.
     *
     * This is the probe that validates everything else: if our reimplementation of {@code updmap}
     * reproduces the numbers the window is showing, digit for digit, then the model the planner is
     * built on is the game's own. If it does not, the planner would be optimising a fiction.
     * Reflection rather than a direct call because {@code haven.res.ui.surv.LandSurvey} is loaded
     * by the resource class loader and cannot be compiled against.
     */
    private static void inspect(GameUI gui) throws Exception {
        Window w = gui.getwnd(WINDOW);
        if (w == null) {
            say(gui, "surv inspect: no '" + WINDOW + "' window open");
            return;
        }

        Area area = (Area) field(w, "area");
        Object data = field(w, "data");
        Area varea = (Area) field(data, "varea");
        float g = ((Number) field(data, "gran")).floatValue();
        int[] dz = (int[]) field(data, "dz");
        float[] wz = (float[]) field(data, "wz");
        gran = g;

        MCache map = gui.ui.sess.glob.map;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        long sd = 0, hn = 0, vsum = 0;
        int n = 0;
        for (Coord vc : varea) {
            int vz = Math.round((float) map.getfz(vc) * g);
            int tz = dz[varea.ridx(vc)];
            min = Math.min(min, vz);
            max = Math.max(max, vz);
            sd += tz - vz;
            if (vz > tz)
                hn += vz - tz;
            vsum += vz;
            n++;
        }

        int tmin = Integer.MAX_VALUE, tmax = Integer.MIN_VALUE;
        for (int v : dz) {
            tmin = Math.min(tmin, v);
            tmax = Math.max(tmax, v);
        }

        int opt = Math.round(vsum / (float) n);
        long osd = 0, ohn = 0;
        for (Coord vc : varea) {
            int vz = Math.round((float) map.getfz(vc) * g);
            osd += opt - vz;
            if (vz > opt)
                ohn += vz - opt;
        }

        log("== inspect ==");
        log("tile area " + area.ul + ".." + area.br + " = " + area.area() + " m^2, sides "
            + (area.br.x - area.ul.x) + "x" + (area.br.y - area.ul.y));
        log("vertex area " + varea.ul + ".." + varea.br + " = " + varea.area() + " vertices");
        log("gran " + g + "  (the window prints peak-to-trough as dz/10 metres)");
        log("target dz: min " + tmin + " max " + tmax + (tmin == tmax ? " (flat)" : " (NOT flat)"));
        if (lastWrite != null) {
            boolean kept = (tmin == tmax) && (tmin == lastWrite);
            boolean reopened = ((Widget) w).wdgid() != lastWriteWid;
            log("last ':surv write' asked for dz " + lastWrite + " - the target "
                + (kept ? "reads that now" : "does NOT read that") + "; window id "
                + lastWriteWid + " -> " + ((Widget) w).wdgid()
                + (reopened
                   ? (kept ? " (REOPENED and kept: the server stored it)"
                           : " (REOPENED and lost: the server rejected it)")
                   : " (same window - this is just the array we wrote being read back; close the "
                     + "survey and reopen it to settle it)"));
        }
        log("ground dz: min " + min + " max " + max);
        log("recomputed:  peak-to-trough " + String.format("%.1f m", (max - min) / 10.0)
            + " | soil " + (sd >= 0 ? "required " + sd : "left over " + (-sd))
            + " | to dig " + hn);
        log("window says: " + text(w, "zdlbl") + " | " + text(w, "wlbl") + " | " + text(w, "dlbl"));
        log("optimal flat level for THIS survey: dz " + opt + " (the window's 'Ground plane' button "
            + "already does this) -> soil " + (osd >= 0 ? "required " + osd : "left over " + (-osd))
            + ", to dig " + ohn);
        log("wz[0]=" + (wz.length > 0 ? Float.toString(wz[0]) : "-")
            + " dz[0]=" + (dz.length > 0 ? Integer.toString(dz[0]) : "-"));
        say(gui, "surv inspect: recomputed sd=" + sd + " hn=" + hn + " vs the labels -> logs/" + LOG);
    }

    private static String text(Window w, String fieldName) {
        try {
            Label l = (Label) field(w, fieldName);
            return (l == null || l.texts == null) ? "?" : l.texts;
        } catch (Exception e) {
            return "?(" + e + ")";
        }
    }

    /**
     * Writes a flat target plane into an open survey and sends it, the way a drag would.
     *
     * This is the go/no-go probe for automating anything: {@code LandSurvey.send} is
     * {@code wdgmsg("data", data.encode())} and nothing more, so if writing {@code wz}/{@code dz}
     * and sending that message is accepted by the server, a planner can drive a survey to any
     * target it likes without synthesising mouse drags. If the server rejects it, only hand-dragging
     * remains and the whole idea is off.
     *
     * <p>Runs on the UI thread (console commands do), which is the thread {@code Mover} mutates
     * these arrays on, so no extra locking is needed.
     */
    private static void write(GameUI gui, Integer level) throws Exception {
        Window w = gui.getwnd(WINDOW);
        if (w == null) {
            say(gui, "surv write: no '" + WINDOW + "' window open");
            return;
        }
        Object data = field(w, "data");
        int[] dz = (int[]) field(data, "dz");
        int target = (level != null) ? level : meanDz(gui, w);

        int wasMin = Integer.MAX_VALUE, wasMax = Integer.MIN_VALUE;
        for (int v : dz) {
            wasMin = Math.min(wasMin, v);
            wasMax = Math.max(wasMax, v);
        }

        log("== write: setting every vertex to dz " + target
            + (level == null ? " (computed mean)" : " (given)") + " ==");
        log("target was dz " + wasMin + ".." + wasMax + " -> " + target);
        log("before: " + text(w, "wlbl") + " | " + text(w, "dlbl"));

        /* A fresh survey already sits at its own mean - the resource's tick() calls initplane()
         * on the first frame - so writing the mean into an untouched survey changes nothing and
         * proves nothing about whether the server accepts our message. Say so rather than letting
         * an unchanged label read as success. */
        if (wasMin == wasMax && wasMin == target) {
            log("NO-OP: the survey was already flat at dz " + target + ". This tells us nothing "
                + "about server acceptance - write a different level to actually test it.");
            say(gui, "surv write: already at dz " + target + " - no-op. Try ':surv write "
                + (target + 5) + "', then close and reopen the survey and re-inspect.");
        }
        lastWrite = target;
        lastWriteWid = ((Widget) w).wdgid();

        setLevel(gui, target);
        log("sent; re-run ':surv inspect' to see whether the server kept it");
        say(gui, "surv write: sent target dz " + target + "; run ':surv inspect' to verify");
    }

    /** The open survey's own mean ground level, quantised the way the window quantises it. */
    private static int meanDz(GameUI gui, Window w) throws Exception {
        Object data = field(w, "data");
        Area varea = (Area) field(data, "varea");
        float g = ((Number) field(data, "gran")).floatValue();
        MCache map = gui.ui.sess.glob.map;
        long vsum = 0;
        int n = 0;
        for (Coord vc : varea) {
            vsum += Math.round((float) map.getfz(vc) * g);
            n++;
        }
        return Math.round(vsum / (float) n);
    }

    // ------------------------------------------------------------------ driving a survey

    /*
     * The three methods below are the only verified way to set a survey's level, and they are
     * package-private rather than private so SurveyPlanWindow can use them instead of growing a
     * second copy. LandSurvey.send is wdgmsg("data", data.encode()) and nothing more, which is why
     * writing the arrays and sending that message is enough - confirmed against a live server by
     * closing and reopening the survey and finding the level still there.
     */

    /**
     * Sets every vertex of the open survey to {@code dz} and sends it. False when none is open.
     *
     * Runs on the caller's thread, which for both the console command and a button press is the UI
     * thread - the same thread the resource's own drag handler mutates these arrays on, so no extra
     * locking is needed.
     */
    static boolean setLevel(GameUI gui, int dz) {
        Window w = gui.getwnd(WINDOW);
        if (w == null)
            return false;
        try {
            Object data = field(w, "data");
            int[] tz = (int[]) field(data, "dz");
            float[] wz = (float[]) field(data, "wz");
            for (int i = 0; i < wz.length; i++) {
                wz[i] = dz;
                tz[i] = dz;
            }
            java.lang.reflect.Field seq = data.getClass().getField("seq");
            seq.setAccessible(true);
            seq.setInt(data, seq.getInt(data) + 1);
            try {
                java.lang.reflect.Field upd = w.getClass().getDeclaredField("upd");
                upd.setAccessible(true);
                upd.setBoolean(w, true);
            } catch (Exception e) {
                log("could not force a label refresh (" + e + "); it will catch up on the next map change");
            }
            Object[] payload = (Object[]) data.getClass().getMethod("encode").invoke(data);
            ((Widget) w).wdgmsg("data", payload);
            return true;
        } catch (Exception e) {
            NLog.crash("SurveyProbe.setLevel", e);
            return false;
        }
    }

    /** The open survey's tile area, or null when none is open. */
    static Area openArea(GameUI gui) {
        Window w = gui.getwnd(WINDOW);
        if (w == null)
            return null;
        try {
            return (Area) field(w, "area");
        } catch (Exception e) {
            return null;
        }
    }

    /** The open survey's vertical quantisation, or 0 when none is open. */
    static float openGran(GameUI gui) {
        Window w = gui.getwnd(WINDOW);
        if (w == null)
            return 0f;
        try {
            return ((Number) field(w.getClass().getField("data").get(w), "gran")).floatValue();
        } catch (Exception e) {
            return 0f;
        }
    }

    // ------------------------------------------------------------------ cost sampling

    /**
     * Samples meters and survey labels once a second so the per-unit costs can be measured.
     *
     * The cost ordering the planner assumes - digging dearest, then hauling, then filling from a
     * stockpile, then in-survey autofill - is a game-mechanics claim and is not derivable from the
     * client source. This makes it measurable: run one activity at a time and the log gives units
     * per second and stamina per unit for each.
     */
    private static void cost(GameUI gui, String mode) {
        Thread cur = sampler;
        boolean want = mode.equals("on") || (mode.equals("toggle") && cur == null);
        if (!want) {
            sampler = null;
            if (cur != null)
                cur.interrupt();
            log("== cost sampling stopped ==");
            say(gui, "surv cost: off");
            return;
        }
        if (cur != null) {
            say(gui, "surv cost: already running");
            return;
        }
        Thread t = new Thread(() -> {
            log("== cost sampling started: t_ms, stam, nrj, pos, soil-required, soil-to-dig ==");
            long t0 = System.currentTimeMillis();
            try {
                while (sampler == Thread.currentThread()) {
                    Window w = gui.getwnd(WINDOW);
                    String req = (w == null) ? "-" : text(w, "wlbl");
                    String dig = (w == null) ? "-" : text(w, "dlbl");
                    Gob me = gui.map.player();
                    log(String.format("%d\t%.3f\t%.3f\t%s\t%s\t%s",
                        System.currentTimeMillis() - t0,
                        meter(gui, "stam"), meter(gui, "nrj"),
                        (me == null) ? "-" : me.rc.toString(), req, dig));
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                NLog.crash("SurveyProbe.cost", e);
            }
            log("== cost sampling ended ==");
        }, "survey-cost-sampler");
        t.setDaemon(true);
        sampler = t;
        t.start();
        say(gui, "surv cost: on, one line a second into logs/" + LOG);
    }

    private static double meter(GameUI gui, String name) {
        IMeter.Meter m = gui.getmeter(name, 0);
        return (m == null) ? -1 : m.a;
    }
}
