package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.OCache;
import haven.Resource;
import haven.automated.nbots.core.NLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures how far away the world actually arrives, and writes the numbers down.
 *
 * Every constant that decides how far a bot may plan is a claim about this, and until now all of
 * them were inherited rather than measured: another client's twenty-five tiles, this client's
 * forty-four (which is the TILE window, a different quantity), a player's recollection of about
 * thirty-nine. Those cannot all be right, and being wrong in either direction is expensive - short
 * throws away ground that is in plain view, long records empty tiles where something simply had not
 * loaded yet. So this measures it.
 *
 * MEASURED AT THE TRANSITION, not by sampling. Asking "how far is the furthest gob I can see" every
 * second answers a different and much weaker question, because the server decides when to drop an
 * object and keeps ones you have walked away from: the furthest gob loaded is an upper bound on
 * what is retained, not the distance at which things appear. The distance at which a gob is ADDED
 * is exactly the radius at which the server starts sending, and the distance at which one is
 * REMOVED is exactly where it stops. Both are events, so both are definitive.
 *
 * Two things are deliberately excluded, because both would report a large number that means
 * nothing. Logging in, hearthing and teleporting deliver the whole neighbourhood as a burst of adds
 * from wherever we now stand, and the client re-bases its coordinates on a long journey, which
 * moves everything at once without anything having loaded. Both look like our own position jumping,
 * so measurement pauses for a moment whenever it does.
 *
 * Terrain is surveyed rather than watched, since grids arrive without an event this can hook. It is
 * reported per direction on purpose: grids stay resident after you walk away, so the reading behind
 * a moving character is its own trail and only the SMALLEST of the eight is a radius you could plan
 * against.
 */
public class Sight {
    private static final String LOG = "sight.log";

    /** How long to ignore everything after our own position jumps. */
    private static final long SETTLE_MS = 6000;
    /** A move bigger than this in one tick is a teleport or a coordinate re-base, not walking. */
    private static final double JUMP = 11 * 100.0;
    /**
     * Beyond this, a sample is not a load event.
     *
     * Set well above any plausible answer rather than near it: its job is to drop the artefacts the
     * settle window misses, not to cap the result. Anything it does drop is counted and reported, so
     * a ceiling set too low would announce itself instead of quietly truncating the answer.
     */
    private static final double SANE_TILES = 300;

    /** How often the survey line goes into the log even when nothing broke a record. */
    private static final long REPORT_MS = 60_000;

    /** Directions the terrain survey probes, and how far out in tiles it is willing to look. */
    private static final int[] PROBE_X = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] PROBE_Y = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int PROBE_STEP = 10;
    private static final int PROBE_MAX = 400;

    private static final Object LOCK = new Object();

    private static OCache watching = null;
    private static Watcher watcher = null;   // strong: OCache holds its callbacks weakly

    /** Gobs added since the last tick, measured one tick late - see {@link #drain}. */
    private static final Map<Long, Long> pending = new LinkedHashMap<>();

    private static Coord2d was = null;
    private static long settleAt = 0;
    private static long reported = 0;

    /**
     * How many events landed at each whole tile of on-axis distance, for adds and for removes.
     *
     * A maximum was not enough and reporting only one was a mistake with consequences: the sweep's
     * radius was raised to just inside the largest distance ever seen, on the assumption that the
     * boundary sits there always. If objects instead arrive in chunks - a band appearing at the
     * leading edge as you cross a tile rather than a smooth slide - then the boundary moves within
     * a range, the maximum is its far end, and a radius set there is outside it most of the time.
     *
     * A histogram says which. A hard boundary puts every removal in one or two buckets; a chunked
     * one spreads them over the width of a chunk, and the LOW end of that spread is the only figure
     * anything may safely be set to.
     */
    private static final int BUCKETS = 80;
    private static final int[] inAt = new int[BUCKETS], outAt = new int[BUCKETS];

    private static double inMax = 0, outMax = 0;
    /**
     * The furthest a gob has appeared along each axis on its own.
     *
     * Because a maximum DISTANCE cannot tell a circle from a square, and the difference decides
     * what {@link Observed#SEES} may safely be. If the region the server sends is a square of
     * half-width W, its furthest corner is W times root two - so a measured 61 would mean W is
     * about 43, and wiping a square wider than that would record ground as empty in the corners
     * where nothing had loaded. If it is a circle, 61 is the radius and there is far more room.
     * Two numbers that come out near 43 mean a square; near 61, a circle.
     */
    private static double inDx = 0, inDy = 0;
    private static String inWhat = "?", outWhat = "?";
    private static long inCount = 0, outCount = 0, dropped = 0;
    /** This pass's readings, and the best each has ever been. Four numbers, not two - see below. */
    private static int terrainNow = 0, terrainNowFar = 0, terrainBest = 0, terrainFar = 0;

    private Sight() {}

    /**
     * One measurement pass. Driven from {@link Observed#tick}, which the client already calls once a
     * second, rather than from a timer of its own - the two want the same cadence and the same
     * "there is a world to look at" checks.
     */
    public static void tick(GameUI gui) {
        if ((gui == null) || (gui.map == null) || (gui.ui == null) || (gui.ui.sess == null))
            return;
        Gob me = gui.map.player();
        if (me == null)
            return;
        attach(gui);

        long now = System.currentTimeMillis();
        // Our own position moving impossibly far means the world was handed to us afresh; nothing
        // that arrives around it has been "loaded at" any distance.
        if ((was == null) || (was.dist(me.rc) > JUMP))
            settleAt = now + SETTLE_MS;
        was = me.rc;

        boolean news = drain(gui, me, now);
        news |= survey(gui, me);

        if (news || ((now - reported) > REPORT_MS)) {
            reported = now;
            report();
        }
    }

    /**
     * Registration happens OUTSIDE the lock. The object cache's callback list is guarded by the
     * cache's own monitor, and its callbacks run holding a gob's - so a lock order of
     * ours-then-theirs here, against theirs-then-ours on every add, is a deadlock waiting for the
     * two to coincide. Nothing below is called often enough for the racier bookkeeping to cost
     * anything: it is once per session.
     */
    private static void attach(GameUI gui) {
        OCache oc = gui.ui.sess.glob.oc;
        OCache old;
        Watcher drop, add;
        synchronized (LOCK) {
            if (watching == oc)
                return;
            old = watching;
            drop = watcher;
            add = new Watcher();
            watcher = add;
            watching = oc;
            // A new object cache is a new session: everything about to arrive is the initial burst.
            settleAt = System.currentTimeMillis() + SETTLE_MS;
        }
        if ((old != null) && (drop != null))
            old.uncallback(drop);
        oc.callback(add);
    }

    // ------------------------------------------------------------------ gobs

    /**
     * Measures the gobs that appeared since the last pass.
     *
     * Deferred by one tick rather than measured inside the callback because a gob is put in the
     * cache before the message that positions it has been applied, so its coordinates there are not
     * yet its own. One tick of slack is about a tile at running speed, which cannot move an answer
     * that is being quoted to the nearest tile.
     */
    private static boolean drain(GameUI gui, Gob me, long now) {
        List<Long> ids;
        synchronized (LOCK) {
            if (pending.isEmpty())
                return false;
            ids = new ArrayList<>(pending.keySet());
            pending.clear();
        }
        if (now < settleAt)
            return false;
        boolean news = false;
        for (long id : ids) {
            Gob g = gui.ui.sess.glob.oc.getgob(id);
            if (g == null)
                continue;      // came and went inside a second; nothing to learn from it
            double dx = Math.abs(g.rc.x - me.rc.x) / MCache.tilesz.x;
            double dy = Math.abs(g.rc.y - me.rc.y) / MCache.tilesz.y;
            news |= record(true, me.rc.dist(g.rc) / MCache.tilesz.x, name(g));
            synchronized (LOCK) {
                if (Math.max(dx, dy) <= SANE_TILES) {
                    inDx = Math.max(inDx, dx);
                    inDy = Math.max(inDy, dy);
                    bucket(inAt, Math.max(dx, dy));
                }
            }
        }
        return news;
    }

    /**
     * Caller is on the object-cache's thread, holding the gob's own monitor. So everything that
     * touches the gob is read BEFORE the lock is taken - see {@link #attach} for why that direction
     * matters.
     */
    private static void sawRemoved(Gob g) {
        long now = System.currentTimeMillis();
        Coord2d here = was;
        if ((here == null) || (g == null) || (now < settleAt))
            return;
        double tiles = here.dist(g.rc) / MCache.tilesz.x;
        double axis = Math.max(Math.abs(g.rc.x - here.x), Math.abs(g.rc.y - here.y))
            / MCache.tilesz.x;
        String what = name(g);
        record(false, tiles, what);
        synchronized (LOCK) {
            if (axis <= SANE_TILES)
                bucket(outAt, axis);
        }
    }

    /** Caller holds {@link #LOCK}. */
    private static void bucket(int[] hist, double tiles) {
        int i = (int) Math.floor(tiles);
        if ((i >= 0) && (i < hist.length))
            hist[i]++;
    }

    /**
     * The distance below which only {@code share} of events fall.
     *
     * The low tail is the whole point. Where the boundary sits at its NEAREST is the only figure a
     * sweep may be set to, because that is the one it is inside all of the time.
     */
    private static int percentile(int[] hist, double share) {
        long total = 0;
        for (int n : hist)
            total += n;
        if (total == 0)
            return -1;
        long want = (long) Math.ceil(total * share), seen = 0;
        for (int i = 0; i < hist.length; i++) {
            seen += hist[i];
            if (seen >= want)
                return i;
        }
        return hist.length - 1;
    }

    /**
     * Where the edge of the region sat, over every event of one kind.
     *
     * Percentiles over the WHOLE range, with no window. The first version reported a "nearest"
     * taken from a window twenty buckets below the maximum, which meant the number it printed was
     * the edge of the window whenever anything at all fell there - a statistic that reports the
     * shape of its own filter. Having just been caught setting a constant from a maximum, printing
     * a fabricated minimum next to it would have been the same mistake with the sign flipped.
     *
     * So the low percentiles are honest and need reading with one thing in mind: objects also
     * appear and vanish nearby for reasons that are not the boundary - an item picked up, a tree
     * felled, a deer butchered. Those live in the low buckets. It is the 25th percentile upwards
     * that describes the edge, and the gap between the 25th and the maximum that says whether the
     * edge is a line or a band.
     */
    private static String spread(int[] hist) {
        int top = percentile(hist, 1.0);
        if (top < 0)
            return "no events";
        return String.format("5%% %dt, 25%% %dt, half %dt, 75%% %dt, furthest %dt",
            percentile(hist, 0.05), percentile(hist, 0.25), percentile(hist, 0.5),
            percentile(hist, 0.75), top);
    }

    /** @return true if this sample set a new record, which is what is worth logging immediately. */
    private static boolean record(boolean in, double tiles, String what) {
        synchronized (LOCK) {
            if (tiles > SANE_TILES) {
                dropped++;
                return false;
            }
            if (in) {
                inCount++;
                if (tiles <= inMax)
                    return false;
                inMax = tiles;
                inWhat = what;
            } else {
                outCount++;
                if (tiles <= outMax)
                    return false;
                outMax = tiles;
                outWhat = what;
            }
            return true;
        }
    }

    private static String name(Gob g) {
        try {
            Resource res = g.getres();
            return (res == null) ? "?" : res.name;
        } catch (RuntimeException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ terrain

    /**
     * How far the map itself reaches, in each of eight directions.
     *
     * Walks outward until the first grid that is not here and stops, so the answer is the loaded
     * neighbourhood rather than every grid the session has ever touched. Uses
     * {@link MCache#gridloaded} rather than reading a tile, because every ordinary way of asking
     * about an absent grid REQUESTS it, and a survey that pulled in four hundred tiles' worth of map
     * in every direction once a second would be measuring its own footprint.
     */
    private static boolean survey(GameUI gui, Gob me) {
        MCache mc;
        try {
            mc = gui.ui.sess.glob.map;
        } catch (RuntimeException e) {
            return false;
        }
        if (mc == null)
            return false;
        Coord tile = me.rc.floor(MCache.tilesz);
        int min = Integer.MAX_VALUE, max = 0;
        for (int d = 0; d < PROBE_X.length; d++) {
            int reach = 0;
            for (int r = PROBE_STEP; r <= PROBE_MAX; r += PROBE_STEP) {
                Coord at = tile.add(PROBE_X[d] * r, PROBE_Y[d] * r);
                if (!mc.gridloaded(Terrain.floorDiv(at, MCache.cmaps)))
                    break;
                reach = r;
            }
            min = Math.min(min, reach);
            max = Math.max(max, reach);
        }
        if (min == Integer.MAX_VALUE)
            min = 0;
        synchronized (LOCK) {
            terrainNow = min;
            terrainNowFar = max;
            boolean news = (min > terrainBest) || (max > terrainFar);
            terrainBest = Math.max(terrainBest, min);
            terrainFar = Math.max(terrainFar, max);
            return news;
        }
    }

    // ------------------------------------------------------------------ reporting

    /**
     * The gob figures are session maxima, since a maximum is what "how far can it reach" means for
     * an event that either happens or doesn't.
     *
     * The terrain figures are not, and are reported as two different things because a running
     * maximum of the tightest direction would answer a question nobody asked - it would climb to
     * whatever the best-loaded moment of the session was and stay there, which is not a radius
     * anything could be planned against. So: what the survey sees RIGHT NOW, which is the honest
     * reading, and the best it has ever been, which is what the client is capable of when nothing
     * is still streaming.
     */
    private static void report() {
        String line;
        synchronized (LOCK) {
            line = String.format(
                "gobs appear out to %.1ft (%s, furthest on one axis alone %.1f x %.1f),"
                + " disappear out to %.1ft (%s) over %d in / %d out;"
                + " EDGE arriving [%s], leaving [%s];"
                + " terrain now %dt in the tightest direction and %dt in the widest,"
                + " best ever %dt / %dt%s",
                inMax, inWhat, inDx, inDy, outMax, outWhat, inCount, outCount,
                spread(inAt), spread(outAt),
                terrainNow, terrainNowFar, terrainBest, terrainFar,
                (dropped == 0) ? "" : ("; " + dropped + " samples over " + (int) SANE_TILES
                    + "t discarded as re-bases"));
        }
        NLog.diag(LOG, line);
    }

    /** The measured radius so far, in tiles, or 0 before anything has been seen to arrive. */
    public static double gobRadius() {
        synchronized (LOCK) {
            return inMax;
        }
    }

    private static class Watcher implements OCache.ChangeCallback {
        @Override
        public void added(Gob ob) {
            synchronized (LOCK) {
                // Position is not settled yet; all this can record is that it is worth measuring.
                pending.put(ob.id, System.currentTimeMillis());
            }
        }

        @Override
        public void removed(Gob ob) {
            synchronized (LOCK) {
                pending.remove(ob.id);
            }
            sawRemoved(ob);
        }
    }
}
