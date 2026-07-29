package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A route across a segment, planned tile by tile over ground the character has actually seen.
 *
 * Travel used to be greedy - aim at the destination, and on failing, swing blindly sideways and
 * widen the swing. Against anything longer than the swing, a lake shore or a palisade, that is a
 * bot pacing back and forth. No amount of tuning the local pathfinder helps, because it is only
 * ever shown an eighty-eight tile window with the target inside it: it answers its question
 * correctly, and the wrong question was being asked.
 *
 * WHY THIS IS AT TILE RESOLUTION. The first version searched over blocks of four tiles, on the
 * reasoning that a coarse grid is cheap and the point is only to choose the right side of the
 * lake. Every serious routing bug since came out of that choice, because a palisade is ONE TILE
 * THICK and a four-tile block cannot describe it. A block with a wall through it is neither open
 * nor closed: treat it as closed and every gateway in the base is sealed, treat it as open and the
 * route goes through the wall. The workarounds - sample the centre for ground but every tile for
 * walls, exempt the goal block, then exempt it only from sides worked out at tile resolution
 * anyway, then do the same for the start block - were each correct and each covering for the same
 * missing tile of resolution. At tile resolution none of them is needed and none of them exists: a
 * wall is a line of blocked tiles, a gateway is a gap in it, and A* walks through the gap.
 *
 * The cost is a bigger search, and it is not much of a cost. A three-hundred-tile journey with an
 * octile heuristic settles in a few milliseconds, which is nothing next to the walk it plans.
 *
 * WHAT IT PLANS ON. {@link Observed} for objects - dense, recorded from what the character has
 * seen, self-correcting - and {@link Terrain} for water, read from the map file so that ground
 * walked past hours ago is still answerable offline. Unseen ground stays passable but expensive,
 * because a route is a hypothesis the local pathfinder re-checks on arrival; the one caller whose
 * answer is FINAL asks for it to be refused instead. See {@link #reachable}.
 */
public class Router {
    /** Search ceiling, in tiles. A route needing more than this is a bad question. */
    private static final int MAX_TILES = 250000;

    /**
     * What a step across never-seen ground costs, as a multiple of a step across seen ground.
     *
     * Three, so a bot will go about three times as far to stay on ground it has looked at, and no
     * further. Higher rules out crossings that are perfectly ordinary - the corner of a field
     * nobody has walked over - and lower is not enough to turn a route aside from a straight line
     * that happens to run through the unknown.
     */
    private static final int UNKNOWN = 3;

    /** Straight and diagonal step costs, scaled so the whole search stays in integers. */
    private static final int STRAIGHT = 10, DIAGONAL = 14;

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    private Router() {}

    /**
     * Whether the player could walk to a live world point at all.
     *
     * For callers that CHOOSE targets rather than travel to them - the LP assistant is the one that
     * needed it. It picks whatever is nearest and undiscovered and then chases it with the local
     * pathfinder, which sees eighty-eight tiles and re-aims every few seconds, so it cannot hold a
     * detour; a target it cannot reach in a roughly straight line is one it will walk at until its
     * attempt budget runs out. It did exactly that at an apple across a river, for several evenings.
     *
     * OVER SEEN GROUND ONLY, and that is the whole difference from every other caller. Everywhere
     * else unseen ground is passable, and rightly: the local pathfinder still gets its veto tile by
     * tile when the bot arrives, so optimism costs a detour. Here there is no second opinion
     * coming - the answer IS the decision - and optimism makes the test vacuous, because past the
     * edge of what has been seen there is nothing left to say no and a search that wanders far
     * enough always finds a way round through country nobody has looked at. There was no way round
     * that river; there was only no evidence.
     *
     * Bounded by AREA rather than by a node count. A budget sounds equivalent and is not: proving
     * something unreachable means exhausting everywhere that is, which in open country is more
     * than any budget worth spending - so the search always ran out, "ran out" answered yes, and
     * the check passed everything. Confined to a box around the two ends, running out of BOX is a
     * real answer.
     */
    public static boolean reachable(GameUI gui, Coord2d target, int margin) {
        Gob me = ((gui == null) || (gui.map == null)) ? null : gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        WorldAnchor there = WorldAnchor.capture(gui, target);
        if ((me == null) || (here == null) || (there == null) || (here.seg != there.seg))
            return true;
        Coord from = here.sc.floor(MCache.tilesz), to = there.sc.floor(MCache.tilesz);
        Coord lo = new Coord(Math.min(from.x, to.x), Math.min(from.y, to.y)).sub(margin, margin);
        Coord hi = new Coord(Math.max(from.x, to.x), Math.max(from.y, to.y)).add(margin, margin);
        return search(new World(gui, here.seg, true), from, to, lo, hi) != null;
    }

    /**
     * Whether {@link #reachable} is in a position to mean anything about this target.
     *
     * It answers yes to anything it cannot work out, which is the right default and makes a yes
     * ambiguous. Asked separately so a log can say which, because "there is a way" and "no idea"
     * point at completely different things.
     */
    public static boolean answerable(GameUI gui, Coord2d target) {
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        WorldAnchor there = WorldAnchor.capture(gui, target);
        return (here != null) && (there != null) && (here.seg == there.seg);
    }

    /**
     * Waypoints from one segment tile to another, or null if there is no route.
     *
     * A null return is not a failure to report - it is the caller's cue to fall back on walking
     * straight at the target, which is what it would have done anyway.
     */
    public static List<Coord> route(GameUI gui, long seg, Coord fromTile, Coord toTile) {
        return search(new World(gui, seg, false), fromTile, toTile, null, null);
    }

    // ------------------------------------------------------------------ the search

    private static List<Coord> search(World w, Coord from, Coord to, Coord lo, Coord hi) {
        if (from.equals(to))
            return new ArrayList<>();

        Map<Coord, Coord> came = new HashMap<>();
        Map<Coord, Integer> g = new HashMap<>();
        Set<Coord> done = new HashSet<>();
        /* The priority is carried WITH the entry rather than read back out of the cost map by a
         * comparator. Those are not the same thing: a comparator that reads a map the search is
         * still writing to changes the ordering of items already in the queue, which is exactly
         * what a heap may not have happen underneath it. It mostly survives, because the closed
         * set catches the stale entries - but "mostly survives" is not a property to build a
         * router on, and carrying the number costs nothing. */
        PriorityQueue<Step> open = new PriorityQueue<>((a, b) -> Integer.compare(a.f, b.f));
        g.put(from, 0);
        open.add(new Step(from, h(from, to)));

        int seen = 0;
        while (!open.isEmpty() && (seen++ < MAX_TILES)) {
            Coord cur = open.poll().at;
            if (cur.equals(to))
                return simplify(w, trace(came, cur));
            if (!done.add(cur))
                continue;
            int cg = g.getOrDefault(cur, Integer.MAX_VALUE);
            for (int i = 0; i < 8; i++) {
                Coord nb = cur.add(DX[i], DY[i]);
                if (done.contains(nb))
                    continue;
                if ((lo != null)
                    && ((nb.x < lo.x) || (nb.y < lo.y) || (nb.x > hi.x) || (nb.y > hi.y)))
                    continue;
                /* The destination is entered whatever stands on it, because a caller has already
                 * decided it wants to be there and destinations sit against things - a barrel, a
                 * tree, the wall a water place is drawn up to. This needs none of the old
                 * which-side machinery: the goal is ONE tile now, so it can only be entered from a
                 * neighbour that is itself reachable, and something genuinely walled in has no
                 * such neighbour. */
                if (!nb.equals(to) && !w.passable(nb))
                    continue;
                boolean diag = (DX[i] != 0) && (DY[i] != 0);
                /* No cutting corners. A diagonal between two blocked orthogonals is a step through
                 * the join between two walls, which looks fine on a grid and is solid in the game
                 * - and it is exactly how an eight-connected search slips through a palisade at
                 * every corner post. */
                if (diag && (!w.passable(new Coord(cur.x + DX[i], cur.y))
                    || !w.passable(new Coord(cur.x, cur.y + DY[i]))))
                    continue;
                int ng = cg + ((diag ? DIAGONAL : STRAIGHT) * w.cost(nb));
                if (ng < g.getOrDefault(nb, Integer.MAX_VALUE)) {
                    g.put(nb, ng);
                    came.put(nb, cur);
                    open.add(new Step(nb, ng + h(nb, to)));
                }
            }
        }
        return null;
    }

    /** One tile in the open set, carrying the priority it was queued with. */
    private static final class Step {
        final Coord at;
        final int f;

        Step(Coord at, int f) {
            this.at = at;
            this.f = f;
        }
    }

    /** Octile distance, in the same units as the step costs. */
    private static int h(Coord a, Coord b) {
        int dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y);
        return (STRAIGHT * Math.max(dx, dy)) + ((DIAGONAL - STRAIGHT) * Math.min(dx, dy));
    }

    private static List<Coord> trace(Map<Coord, Coord> came, Coord end) {
        Deque<Coord> out = new ArrayDeque<>();
        for (Coord c = end; c != null; c = came.get(c))
            out.addFirst(c);
        return new ArrayList<>(out);
    }

    /**
     * Drops the waypoints that add nothing.
     *
     * A raw tile path is one waypoint per step, nearly all of them in a straight line, and handing
     * those to travel would reintroduce the stutter this exists to remove - every waypoint costs a
     * pathfinder run. So a waypoint is kept only where the straight line from the last kept one
     * stops being clear, which is precisely where the route turns around something.
     *
     * This also gives travel the guarantee it needs: consecutive waypoints have a clear straight
     * line between them over ground we have seen. The server walks in straight lines, so a leg
     * that is straight and clear is one it can simply be told to walk.
     */
    private static List<Coord> simplify(World w, List<Coord> path) {
        List<Coord> out = new ArrayList<>();
        if (path.size() < 2)
            return out;
        int anchor = 0;
        for (int i = 2; i < path.size(); i++) {
            if (!clear(w, path.get(anchor), path.get(i))) {
                out.add(path.get(i - 1));
                anchor = i - 1;
            }
        }
        out.add(path.get(path.size() - 1));
        return out;
    }

    /** Whether every tile the straight line between two tiles crosses is passable. */
    private static boolean clear(World w, Coord a, Coord b) {
        int steps = Math.max(Math.abs(b.x - a.x), Math.abs(b.y - a.y)) * 2;
        for (int i = 1; i < steps; i++) {
            int x = a.x + (((b.x - a.x) * i) / steps);
            int y = a.y + (((b.y - a.y) * i) / steps);
            if (!w.passable(new Coord(x, y)))
                return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the world, cached

    /**
     * One segment's walkability, cached per grid for the duration of a search.
     *
     * Worth the class. A tile search asks hundreds of thousands of questions, and both sources
     * behind them take a lock and divide down to a grid on every call; going through that per tile
     * turns a few milliseconds of arithmetic into a second of lock traffic. Here each grid is
     * fetched once.
     */
    private static final class World {
        private final GameUI gui;
        private final long seg;
        /** Refuse ground nobody has seen, rather than charging extra for it. */
        private final boolean strict;
        private final Map<Coord, byte[]> obs = new HashMap<>();
        private final Map<Coord, Object> water = new HashMap<>();
        private static final Object NONE = new Object();

        World(GameUI gui, long seg, boolean strict) {
            this.gui = gui;
            this.seg = seg;
            this.strict = strict;
        }

        private byte state(Coord t) {
            Coord gc = Terrain.floorDiv(t, MCache.cmaps);
            byte[] g = obs.get(gc);
            if (g == null) {
                g = Observed.gridOf(seg, gc);
                if (g == null)
                    g = new byte[MCache.cmaps.x * MCache.cmaps.y];
                obs.put(gc, g);
            }
            Coord in = t.sub(gc.mul(MCache.cmaps));
            return g[(in.y * MCache.cmaps.x) + in.x];
        }

        /** The water class of a tile, or -1 where the map file cannot say. */
        private int wet(Coord t) {
            Coord gc = Terrain.floorDiv(t, MCache.cmaps);
            Object o = water.get(gc);
            if (o == null) {
                byte[] g = Terrain.classes(gui, seg, gc);
                water.put(gc, o = (g == null) ? NONE : g);
            }
            if (o == NONE)
                return -1;
            Coord in = t.sub(gc.mul(MCache.cmaps));
            return ((byte[]) o)[(in.y * MCache.cmaps.x) + in.x];
        }

        boolean passable(Coord t) {
            byte s = state(t);
            /* BOTH of them. Observed keeps walls apart from other solids so that the enclosure
             * inference can reason about walls alone - see Observed.WALL - and routing must not
             * inherit that distinction, because to a character they stop it identically.
             *
             * Testing only SOLID here made every palisade in the world invisible to the router
             * while looking entirely correct: walls were being recorded, the file had them, every
             * other consumer read them through Observed.solid which covers both. Only this one
             * inlined the test and dropped a case. What it produced was a route straight through
             * the south wall with its first waypoint ON a wall tile, which the local pathfinder
             * then refused - correctly - on every hop, which travel read as "a wall is in the way",
             * which sent the gate check off to open an air lock the route never needed. Opening
             * gates, walking into the chamber, shutting itself in, failing to reach water twelve
             * tiles away: all of it downstream of this line. */
            if ((s == Observed.SOLID) || (s == Observed.WALL))
                return false;
            /* A gateway settles it before the ground is looked at, since a gate is the one place a
             * wall is meant to be walked through. Whether it is open right now is the task layer's
             * problem, not the route's. */
            if (s == Observed.GATE)
                return true;
            if (strict && (s == Observed.UNSEEN))
                return false;
            int w = wet(t);
            if (w == Terrain.DEEP)
                return false;
            if ((w == Terrain.SHALLOW) && haven.automated.pathfinder.Map.BLOCK_WATER)
                return false;
            /* Ground the map file cannot answer for, touching water it can, is taken to be more of
             * the same. Water is contiguous and a river does not end where a character stopped
             * looking, so a bot that walks down one bank until the record runs out and then turns
             * across it is not finding a crossing - it is finding the edge of its own knowledge.
             * One tile of dilation, no more: enough to close that edge, not enough to invent a lake.
             *
             * Keyed on the WATER record being missing, not on the tile being unseen. Those came
             * apart the moment observation became dense: Observed marks everything within sight as
             * open, water included, since it records what stands on the ground rather than what the
             * ground is. So a river in plain view is "seen", and if the map file has not been
             * written for that grid yet - it is written behind the client, so the newest ground is
             * exactly the ground it lacks - the dilation was skipped on the very tiles it existed
             * for, and the crossing came back passable. */
            if (w < 0) {
                for (int i = 0; i < 8; i++) {
                    if (wet(t.add(DX[i], DY[i])) == Terrain.DEEP)
                        return false;
                }
            }
            return true;
        }

        int cost(Coord t) {
            return (state(t) == Observed.UNSEEN) ? UNKNOWN : 1;
        }
    }
}
