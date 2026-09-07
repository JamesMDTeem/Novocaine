package haven.automated.survey;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.GameUI;
import haven.MCache;
import haven.MapView;
import haven.Material;
import haven.Text;
import haven.Utils;
import haven.automated.nbots.core.NLog;
import haven.render.BaseColor;
import haven.render.States;

import java.awt.Color;
import java.awt.Font;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Painting the plan onto the ground, in the order it is meant to be worked.
 *
 * The plan window can only say where a survey goes as a pair of absolute tile coordinates, and
 * nothing in the game shows a player those. So the list was an instruction nobody could follow:
 * you could read "(-987, -1043), 25x25" and still have no idea which corner of the field to start
 * dragging from. This closes that gap the way {@code PlaceOverlay} closed it for bot work areas -
 * the client already tints ground rectangles for its own drag-select box, through
 * {@link MCache.RectOverlay} and an {@link MCache.OverlayInfo} that says what colour to use, so
 * showing sixteen of them needs nothing new from the renderer.
 *
 * <p>Two things the tint alone cannot do, which is why this is more than a copy of
 * {@code PlaceOverlay}:
 *
 * <p><b>Telling neighbours apart.</b> The surveys tile the region exactly - one ends where the
 * next begins - so a single colour across all of them reads as one undivided blob, and a player
 * still cannot see where to stop dragging. The pending rectangles therefore alternate between two
 * shades of the same hue, which makes every boundary visible without introducing a colour that
 * means anything.
 *
 * <p><b>Saying which one is next.</b> Colour carries the state - done, next, still to do - but not
 * the number, and "do this one, then that one" is the whole point. A {@link MCache.RectOverlay} is
 * a flat tint with nowhere to put a label, so the digits are drawn separately in 2D from
 * {@link MapView#draw}, projecting each rectangle's centre through {@link MapView#screenxf}. The
 * number shown is {@link SurveyPlan#step}, the work-order position, and the window list shows the
 * same one - if those two ever disagree the highlight is worse than no highlight at all.
 */
public class SurveyOverlay {
    /** Seconds between overlay refreshes. */
    private static final double PERIOD = 0.5;

    /** How far off the widget's edges a number may sit before it stops being worth drawing. */
    private static final int MARGIN = 40;

    private static final Text.Foundry NUMFND =
        new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 16).aa(true);

    /**
     * Still to do, in two shades of one hue.
     *
     * The two exist only so that neighbouring rectangles are distinguishable; they carry no
     * meaning between them, which is why they are one colour at two strengths rather than two
     * colours. Alpha is in the same range as the client's own selection box - ground you can still
     * see the tiles through, because the point is to place a survey on that ground.
     */
    private static final MCache.OverlayInfo PENDING_A = tint(90, 150, 255, 30);
    private static final MCache.OverlayInfo PENDING_B = tint(150, 200, 255, 46);
    /** The one to draw next. Deliberately the only warm colour, and the only bright one. */
    private static final MCache.OverlayInfo NEXT = tint(255, 190, 40, 90);
    /** Finished. Faint enough to read as background, so the eye goes to the next one. */
    private static final MCache.OverlayInfo DONE = tint(80, 255, 120, 22);

    /**
     * Where stockpiles go: ground two surveys can both reach.
     *
     * A hue nothing else uses, and a stronger alpha than the survey tints because it is drawn ON
     * TOP of one - a pile band always lies across the surveys it joins, so at the same strength it
     * would read as a slightly different shade of the rectangle underneath rather than as its own
     * thing.
     */
    private static final MCache.OverlayInfo PILE = tint(230, 100, 255, 72);

    private static MCache.OverlayInfo tint(int r, int g, int b, int a) {
        return new MCache.OverlayInfo() {
            final Material mat = new Material(new BaseColor(r, g, b, a), States.maskdepth);

            public Collection<String> tags() {
                return Arrays.asList("show");
            }

            public Material mat() {
                return mat;
            }
        };
    }

    /** What a survey is currently drawn as. Kept so that a colour change can be noticed. */
    private static final class Shown {
        final MCache.RectOverlay ol;
        final MCache.OverlayInfo info;

        Shown(MCache.RectOverlay ol, MCache.OverlayInfo info) {
            this.ol = ol;
            this.info = info;
        }
    }

    /** Survey index -> the rectangle drawn for it. */
    private static final Map<Integer, Shown> shown = new HashMap<>();
    /** Transfer position -> the stockpile band drawn for it. */
    private static final Map<Integer, MCache.RectOverlay> zones = new HashMap<>();
    /** State and number -> the label to draw for it, built once and kept. */
    private static final Map<String, Text.Line> labels = new HashMap<>();

    private static volatile SurveyPlan plan;
    private static volatile boolean enabled = Utils.getprefb("surveyShowPlan", true);
    private static volatile boolean piles = Utils.getprefb("surveyShowPiles", true);
    /** The done set as of the last refresh; draw runs every frame and must not touch the disk. */
    private static volatile Set<Integer> done = new HashSet<>();
    /** The survey to do next, or -1. Recomputed on the tick alongside the done set. */
    private static volatile int next = -1;
    /** Chequerboard phase per survey index; see {@link #parityOf}. Rebuilt with the plan. */
    private static volatile Map<Integer, Boolean> parity = new HashMap<>();
    private static double nextTick = 0;

    private SurveyOverlay() {}

    /** Hands the overlay the plan to draw. */
    public static void show(SurveyPlan p) {
        plan = p;
        parity = parityOf(p);
        /* Deliberately NOT clearing the maps here. They are the only record of what has been
         * registered with MCache, and dropping them without removing the overlays first strands
         * every rectangle of the old plan on the ground for the rest of the session - which is
         * exactly the "replan that did not take" this is supposed to prevent, made permanent.
         * Reconciliation belongs to the tick, which has an MCache to remove things from; all that
         * is needed here is to make it happen at once. */
        nextTick = 0;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void enabled(boolean on) {
        enabled = on;
        Utils.setprefb("surveyShowPlan", on);
    }

    public static boolean piles() {
        return piles;
    }

    public static void piles(boolean on) {
        piles = on;
        Utils.setprefb("surveyShowPiles", on);
    }

    /**
     * Brings the drawn rectangles in line with the plan and its progress.
     *
     * Called from the game window's tick, and never throws: this runs on the UI thread, where an
     * escaping exception does not log an error, it ends the client.
     */
    public static void tick(GameUI gui) {
        try {
            if (gui == null || gui.map == null || gui.ui == null || gui.ui.sess == null)
                return;
            double now = Utils.rtime();
            if (now < nextTick)
                return;
            nextTick = now + PERIOD;

            MCache mcache = gui.ui.sess.glob.map;
            SurveyPlan p = plan;
            Set<Integer> fin = SurveyPlanStore.done(p);
            done = fin;
            next = nextOf(p, fin);

            Set<Integer> wanted = new HashSet<>();
            if (enabled && p != null) {
                for (SurveyPlan.SurveySpec s : p.surveys) {
                    wanted.add(s.index);
                    MCache.OverlayInfo want = infoFor(s, fin);
                    Shown cur = shown.get(s.index);
                    /* An overlay's colour is fixed when it is built, so a state change means a
                     * remove and a rebuild rather than an update. Only the rectangle can be
                     * updated in place, and rectangles never move within one plan. */
                    if (cur == null || cur.info != want) {
                        if (cur != null)
                            mcache.remove(cur.ol);
                        MCache.RectOverlay ol = mcache.new RectOverlay(want, s.tiles);
                        mcache.add(ol);
                        shown.put(s.index, new Shown(ol, want));
                    } else {
                        /* Rectangles do not move within one plan, but they certainly move BETWEEN
                         * plans - a replan, or a rebase into a new session's coordinates - and an
                         * overlay reused across that keeps its old area unless it is told. */
                        cur.ol.update(s.tiles);
                    }
                }
            }
            shown.entrySet().removeIf(e -> {
                if (wanted.contains(e.getKey()))
                    return false;
                mcache.remove(e.getValue().ol);
                return true;
            });

            /* The stockpile bands. Keyed by position in the transfer list rather than by the pair
             * of surveys, because the flow can pair one surplus with several deficits and a pair
             * key would collapse them. */
            Set<Integer> wantzones = new HashSet<>();
            if (enabled && piles && p != null) {
                for (int i = 0; i < p.transfers.size(); i++) {
                    Area z = p.reachZone(p.transfers.get(i));
                    // Null for surveys that do not touch: there is no ground both can reach, and
                    // drawing the source's own edge would claim a saving that is not on offer.
                    if (z == null)
                        continue;
                    wantzones.add(i);
                    MCache.RectOverlay ol = zones.get(i);
                    if (ol == null) {
                        ol = mcache.new RectOverlay(PILE, z);
                        mcache.add(ol);
                        zones.put(i, ol);
                    } else {
                        ol.update(z);
                    }
                }
            }
            zones.entrySet().removeIf(e -> {
                if (wantzones.contains(e.getKey()))
                    return false;
                mcache.remove(e.getValue());
                return true;
            });
        } catch (RuntimeException e) {
            NLog.crash("survey overlay tick", e);
        }
    }

    /**
     * The first survey in the work order nobody has finished, or -1 when the plan is done.
     *
     * Claims are deliberately not consulted. A survey somebody else is standing in is still the
     * next one to do from where this client sits - a crew splitting up takes different rows off
     * the list, which the window already enforces - and dimming a claimed survey here would make
     * the bright rectangle move under a player for reasons happening on someone else's screen.
     */
    private static int nextOf(SurveyPlan p, Set<Integer> fin) {
        if (p == null)
            return -1;
        for (SurveyPlan.SurveySpec s : p.order()) {
            if (!fin.contains(s.index))
                return s.index;
        }
        return -1;
    }

    /** Which tint a survey should be wearing. */
    private static MCache.OverlayInfo infoFor(SurveyPlan.SurveySpec s, Set<Integer> fin) {
        if (fin.contains(s.index))
            return DONE;
        if (s.index == next)
            return NEXT;
        return Boolean.TRUE.equals(parity.get(s.index)) ? PENDING_A : PENDING_B;
    }

    /**
     * The chequerboard phase of every survey, by index.
     *
     * Worked out from the rectangles rather than from the index, which is row-major and would give
     * every row the same phase - stripes, which tell you nothing, instead of a chequer, which
     * shows every boundary. It cannot be worked out from tile coordinates either, because the
     * planner's cuts are uneven and a survey is not a whole number of anything. So the columns and
     * rows are ranked by their left and top edges, and the phase is the parity of that rank pair.
     */
    private static Map<Integer, Boolean> parityOf(SurveyPlan p) {
        Map<Integer, Boolean> out = new HashMap<>();
        if (p == null)
            return out;
        Map<Integer, Integer> cols = rank(p, true);
        Map<Integer, Integer> rows = rank(p, false);
        for (SurveyPlan.SurveySpec s : p.surveys) {
            int c = cols.getOrDefault(s.tiles.ul.x, 0);
            int r = rows.getOrDefault(s.tiles.ul.y, 0);
            out.put(s.index, ((c + r) & 1) == 0);
        }
        return out;
    }

    /** Distinct left (or top) edges in order, mapped to their position in that order. */
    private static Map<Integer, Integer> rank(SurveyPlan p, boolean x) {
        TreeSet<Integer> edges = new TreeSet<>();
        for (SurveyPlan.SurveySpec s : p.surveys)
            edges.add(x ? s.tiles.ul.x : s.tiles.ul.y);
        Map<Integer, Integer> out = new HashMap<>();
        int i = 0;
        for (int e : edges)
            out.put(e, i++);
        return out;
    }

    /**
     * Draws each survey's work-order number over its middle.
     *
     * Runs from {@link MapView#draw} every frame, so it touches nothing but memory - the done set
     * and the next survey are both snapshots the tick left behind. Anything that cannot be
     * projected is skipped rather than defaulted: a number drawn in the wrong place is worse than
     * no number, because it points at the wrong ground.
     */
    public static void draw(GOut g, MapView mv) {
        try {
            SurveyPlan p = plan;
            if (!enabled || p == null || mv == null || mv.ui == null || mv.ui.sess == null)
                return;
            MCache mcache = mv.ui.sess.glob.map;
            /* Reset the colour first. Drawing an image modulates it by whatever chcolor was last
             * set to, and the path-drawing blocks that run just before this in MapView.draw leave
             * theirs behind - without this the numbers come out tinted by the pathfinder. */
            g.chcolor();
            Set<Integer> fin = done;
            int nx = next;
            for (SurveyPlan.SurveySpec s : p.surveys) {
                Coord sc = centre(mv, mcache, s.tiles);
                if (sc == null)
                    continue;
                if (sc.x < -MARGIN || sc.y < -MARGIN
                    || sc.x > mv.sz.x + MARGIN || sc.y > mv.sz.y + MARGIN)
                    continue;
                Text.Line line = label(p.step(s.index),
                    fin.contains(s.index) ? "done" : (s.index == nx) ? "next" : "todo");
                g.aimage(line.tex(), sc, 0.5, 0.5);
            }
        } catch (RuntimeException e) {
            NLog.crash("survey overlay draw", e);
        }
    }

    /** Where a rectangle's middle lands on screen, or null if it cannot be worked out yet. */
    private static Coord centre(MapView mv, MCache mcache, Area tiles) {
        try {
            Coord mid = tiles.ul.add(tiles.br).div(2);
            Coord2d wc = Coord2d.of(mid.x + 0.5, mid.y + 0.5).mul(MCache.tilesz);
            Coord3f sc = mv.screenxf(new Coord3f((float) wc.x, (float) wc.y, mcache.getzp(wc).z));
            return (sc == null) ? null : sc.round2();
        } catch (RuntimeException e) {
            /* Loading, most often: ground nobody has walked near yet has no height to put a number
             * on. It draws a frame or two later, and a Loading escaping into MapView.draw would
             * blank the whole map view behind a "Loading..." panel. */
            return null;
        }
    }

    private static Text.Line label(int step, String state) {
        String k = state + step;
        Text.Line line = labels.get(k);
        if (line == null) {
            Color col = "next".equals(state) ? new Color(255, 210, 90)
                : "done".equals(state) ? new Color(150, 190, 155)
                : Color.WHITE;
            line = NUMFND.renderstroked(String.valueOf(step), col, Color.BLACK);
            labels.put(k, line);
        }
        return line;
    }

    /** Drops every drawn rectangle. For the window closing, or the plan going away. */
    public static void clear(GameUI gui) {
        try {
            plan = null;
            if (gui == null || gui.ui == null || gui.ui.sess == null) {
                shown.clear();
                zones.clear();
                return;
            }
            MCache mcache = gui.ui.sess.glob.map;
            for (Shown s : shown.values())
                mcache.remove(s.ol);
            shown.clear();
            for (MCache.RectOverlay ol : zones.values())
                mcache.remove(ol);
            zones.clear();
        } catch (RuntimeException e) {
            NLog.crash("survey overlay clear", e);
        }
    }
}
