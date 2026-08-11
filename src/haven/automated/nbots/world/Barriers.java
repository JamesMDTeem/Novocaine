package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walls, reduced to the one question that is actually about walls.
 *
 * This class used to own a set of wall tiles, learned during travel by matching resource names and
 * written to a file of its own. That store is gone: {@link Observed} now records everything solid
 * the character has seen, densely and continuously, which is a better answer to every question the
 * store was being asked. What is left here is the question {@link Observed} cannot answer on its
 * own, because it is about walls rather than about solids - is that thing inside somebody's base?
 *
 * The name test stays, and has to, because it is the only way to tell three kinds apart that want
 * quite different treatment. A GATE is passable the moment somebody opens it, which routing must
 * know or it walks around the outside of a palisade instead of through it. A WALL is part of an
 * enclosure. Everything else is merely solid, and there is a great deal of it.
 *
 * Detection is by resource PATH rather than an enumerated list of names. Guessing exact names
 * produces a list that silently rots the next time a wall type is added, and the failure mode of
 * that list is precisely the bug this exists to prevent.
 */
public class Barriers {
    private static final Object LOCK = new Object();

    /** Wall words. Only consulted for resources under an arch/ path - see {@link #kind}. */
    private static final String[] WALLWORDS = {"palisade", "wall", "fence", "pole"};

    /**
     * How much of a side of a barrier's bounding box has to be built on before that side counts as
     * a wall of an enclosure rather than a coincidence.
     */
    private static final double SIDE_COVER = 0.6;
    /** Smallest enclosure worth inferring, in tiles each way. Below this it is a pen, not a base. */
    private static final int MIN_RING = 16;

    /** segment -> enclosures inferred from partly-seen walls. */
    private static final Map<Long, List<Ring>> rings = new HashMap<>();
    /** How many barrier tiles each inference was drawn from, so it is redone when more turn up. */
    private static final Map<Long, Integer> drawnFrom = new HashMap<>();

    private Barriers() {}

    /** A wall known only in part, completed into the enclosure it is evidently part of. */
    public static final class Ring {
        public final Coord lo, hi;

        Ring(Coord lo, Coord hi) {
            this.lo = lo;
            this.hi = hi;
        }
    }

    public enum Kind {WALL, GATE}

    /**
     * What this resource is, or null for anything that is not part of a barrier.
     *
     * Gates are tested first because every gate name also contains a wall word - a palisade gate is
     * "palisade" plus "gate" - so the ordering is what keeps gateways from being recorded as solid.
     */
    public static Kind kind(String resname) {
        if (resname == null)
            return null;
        String n = resname.toLowerCase();
        if (!n.contains("/arch/"))
            return null;
        if (n.contains("gate"))
            return Kind.GATE;
        for (String w : WALLWORDS) {
            if (n.contains(w))
                return Kind.WALL;
        }
        return null;
    }

    /** Forgets everything seen. For when a base has been torn down and rebuilt. */
    public static void reset() {
        Observed.reset();
        synchronized (LOCK) {
            rings.clear();
            drawnFrom.clear();
        }
    }

    // ------------------------------------------------------------------ enclosure

    /**
     * True if this live world point stands inside an enclosure that the player does not.
     *
     * Not the same question as "can it be reached", and asked separately for that reason: routing
     * treats a gateway as passable, because opening one is somebody else's job, so everything
     * inside a base IS reachable and always will be. What this asks is whether getting there means
     * going through somebody's wall - and for a bot that collects things, the answer being yes is a
     * reason not to bother. What grows inside a palisade was put there; it is the base's timber and
     * the base's berries, not forage.
     *
     * Both sides are asked, so a bot standing in its own base still works there.
     */
    public static boolean walledOffFrom(GameUI gui, Coord2d point) {
        Gob me = ((gui == null) || (gui.map == null)) ? null : gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if ((me == null) || (here == null) || (point == null))
            return false;
        // The player is in both spaces at once, so one subtraction converts the other point.
        Coord there = point.add(here.sc.sub(me.rc)).floor(MCache.tilesz);
        Coord mine = here.sc.floor(MCache.tilesz);
        synchronized (LOCK) {
            Ring theirs = ringAt(here.seg, there);
            return (theirs != null) && (ringAt(here.seg, mine) != theirs);
        }
    }

    /** The inferred enclosure a segment tile lies within, or null. Caller holds {@link #LOCK}. */
    private static Ring ringAt(long seg, Coord tile) {
        for (Ring r : ringsIn(seg)) {
            if ((tile.x >= r.lo.x) && (tile.x <= r.hi.x)
                && (tile.y >= r.lo.y) && (tile.y <= r.hi.y))
                return r;
        }
        return null;
    }

    /**
     * The enclosures the seen walls imply, redone whenever more wall has turned up.
     *
     * A base is a castle: the wall goes all the way round. A bot, though, only ever sees the
     * stretches it walks past, and one that works in a corner sees a corner. To an inference drawn
     * strictly from what is recorded, a south wall and an east wall are not a base - they are two
     * walls with open country above and beside them, and the shortest way in is round the top.
     *
     * So a barrier that has built out two ADJACENT sides of its own bounding box is taken to
     * enclose that box. Two adjacent sides is the least evidence that means anything: one side is a
     * plain wall between two fields and implies nothing, while a corner is only a corner if
     * something turns it.
     *
     * Keyed on how many barrier tiles it was drawn from rather than recomputed on a timer, so
     * walking a new stretch of somebody's wall is noticed at the next question and nothing is
     * recomputed while nothing has changed.
     */
    private static List<Ring> ringsIn(long seg) {
        Set<Coord> all = Observed.barriersIn(seg);
        Integer was = drawnFrom.get(seg);
        List<Ring> hit = rings.get(seg);
        if ((hit == null) || (was == null) || (was != all.size())) {
            hit = infer(all);
            rings.put(seg, hit);
            drawnFrom.put(seg, all.size());
        }
        return hit;
    }

    private static List<Ring> infer(Set<Coord> all) {
        List<Ring> out = new ArrayList<>();
        if (all.isEmpty())
            return out;
        Set<Coord> seen = new HashSet<>();
        for (Coord start : all) {
            if (!seen.add(start))
                continue;
            // One barrier, taken as everything touching it - a palisade's segments, posts and
            // gates are separate gobs standing shoulder to shoulder.
            List<Coord> comp = new ArrayList<>();
            List<Coord> stack = new ArrayList<>();
            stack.add(start);
            while (!stack.isEmpty()) {
                Coord c = stack.remove(stack.size() - 1);
                comp.add(c);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        Coord n = c.add(dx, dy);
                        if (all.contains(n) && seen.add(n))
                            stack.add(n);
                    }
                }
            }
            Coord lo = comp.get(0), hi = comp.get(0);
            for (Coord c : comp) {
                lo = new Coord(Math.min(lo.x, c.x), Math.min(lo.y, c.y));
                hi = new Coord(Math.max(hi.x, c.x), Math.max(hi.y, c.y));
            }
            if ((((hi.x - lo.x) + 1) < MIN_RING) || (((hi.y - lo.y) + 1) < MIN_RING))
                continue;
            Set<Coord> tiles = new HashSet<>(comp);
            double north = cover(tiles, lo.x, hi.x, lo.y, true);
            double south = cover(tiles, lo.x, hi.x, hi.y, true);
            double west = cover(tiles, lo.y, hi.y, lo.x, false);
            double east = cover(tiles, lo.y, hi.y, hi.x, false);
            boolean corner =
                   ((north >= SIDE_COVER) && (east >= SIDE_COVER))
                || ((east >= SIDE_COVER) && (south >= SIDE_COVER))
                || ((south >= SIDE_COVER) && (west >= SIDE_COVER))
                || ((west >= SIDE_COVER) && (north >= SIDE_COVER));
            if (corner)
                out.add(new Ring(lo, hi));
        }
        return out;
    }

    /**
     * How much of one side of a bounding box is built on, as a fraction.
     *
     * Counted within a tile either way, because a wall does not run exactly along the extreme it
     * defines - it wanders a tile as it turns a corner or takes in a gateway, and demanding the
     * exact line would score a solid wall at nearly nothing.
     */
    private static double cover(Set<Coord> tiles, int from, int to, int at, boolean horizontal) {
        int on = 0;
        for (int i = from; i <= to; i++) {
            boolean any = false;
            for (int d = -1; (d <= 1) && !any; d++)
                any = tiles.contains(horizontal ? new Coord(i, at + d) : new Coord(at + d, i));
            if (any)
                on++;
        }
        return (double) on / Math.max(1, (to - from) + 1);
    }
}
