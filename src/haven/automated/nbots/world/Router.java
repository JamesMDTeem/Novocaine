package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A coarse route across a segment, in segment tiles.
 *
 * This exists because travel used to be greedy. It aimed each hop straight at the destination and
 * only reacted after failing, by swinging blindly sideways and widening the swing each time - which
 * against anything longer than the swing, a lake shore or a palisade, produces a bot that paces
 * back and forth and then gives up. No amount of tuning the local pathfinder helps, because the
 * local pathfinder is only ever shown an 88-tile window with the target inside it. It is answering
 * a question correctly; the wrong question was being asked.
 *
 * So the route is planned first, over {@link Terrain} - which reads the map FILE and therefore
 * knows about ground the client unloaded hours ago - and the local pathfinder is then asked only to
 * get from one waypoint to the next, which is what it is good at.
 *
 * Deliberately coarse. Nodes are {@link #STRIDE}-tile blocks, so a five-hundred-tile trip is a
 * search over a few thousand nodes rather than a hundred thousand, and the result is a handful of
 * waypoints rather than a tile-by-tile path that the local pathfinder would immediately re-derive
 * anyway. The point is to choose the right side of the lake, not to choose the footsteps.
 */
public class Router {
    /** Tiles per routing node. Four tiles is 44 world units - finer than any real detour needs. */
    public static final int STRIDE = 4;

    /** Search ceiling. A route this long is a sign of a bad question, not a hard problem. */
    private static final int MAX_NODES = 40000;

    /**
     * What a step across never-explored ground costs, as a multiple of a step across seen ground.
     *
     * Three, so a bot will go about three times as far to stay on ground it has actually walked
     * past, and no further. Higher would rule out crossings that are perfectly ordinary - the
     * corner of a field nobody has bothered to walk over - and lower is not enough to turn a route
     * aside from a straight line that happens to run through the unknown.
     */
    private static final int UNKNOWN = 3;

    /**
     * Whether the player could walk to a live world point at all.
     *
     * For callers that CHOOSE targets rather than travel to them, and the LP bot is the one that
     * needed it. It picks whatever is nearest and undiscovered and then chases it with the local
     * pathfinder, which sees 88 tiles and re-aims every few seconds - so it cannot be given a
     * detour, and a target it cannot reach in a straight-ish line is a target it will walk at until
     * its attempt budget runs out. It did exactly that at an apple across a river, twice.
     *
     * Asking the router is better than testing for water on the straight line, which was the first
     * attempt at this: a river bends, so the line can be dry while every way round is not. And it
     * answers the other half of the same question for nothing - a thing inside somebody's palisade
     * is unreachable for as long as the walls say so, which is the LP bot's cue to leave the base's
     * own timber alone.
     *
     * Bounded by AREA rather than by a node count, which is the version of this that works. A node
     * budget sounds equivalent and is not: proving something unreachable means exhausting
     * everywhere that is, and in open country that is far more than any budget worth spending - so
     * the search always ran out, "ran out" answered yes, and the check passed everything. It was a
     * no-op wearing the shape of a test. Confined to a box around the two ends instead, running out
     * of BOX is a real answer: there is no way there that is not an enormous detour, and an
     * enormous detour is not something a chase can walk anyway.
     */
    /**
     * Whether {@link #reachable} is in a position to mean anything about this target.
     *
     * It answers YES to everything it cannot work out - which is the right default, since refusing
     * a target because the map file has not caught up would be worse than walking at one. But that
     * makes a yes ambiguous, and a caller acting on it has no way to tell "there is a way" from
     * "no idea". Asked separately so the log can say which, because the two want completely
     * different things looked at next.
     */
    public static boolean answerable(GameUI gui, Coord2d target) {
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        WorldAnchor there = WorldAnchor.capture(gui, target);
        return (here != null) && (there != null) && (here.seg == there.seg);
    }

    public static boolean reachable(GameUI gui, Coord2d target, int margin) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        WorldAnchor there = WorldAnchor.capture(gui, target);
        if ((me == null) || (here == null) || (there == null) || (here.seg != there.seg))
            return true;
        Coord from = here.sc.floor(MCache.tilesz), to = there.sc.floor(MCache.tilesz);
        Coord lo = node(new Coord(Math.min(from.x, to.x), Math.min(from.y, to.y))).sub(margin, margin);
        Coord hi = node(new Coord(Math.max(from.x, to.x), Math.max(from.y, to.y))).add(margin, margin);
        /* OVER GROUND WE HAVE ACTUALLY SEEN, which is the difference between this question and
         * every other one the router is asked.
         *
         * Unexplored ground counts as walkable everywhere else, and rightly: a route is a plan the
         * local pathfinder still gets to veto tile by tile when the bot arrives, so optimism there
         * costs a detour at worst. Here there is no second opinion coming. The answer IS the
         * decision, and optimism makes the whole test vacuous - past the edge of what has been
         * explored there is nothing left to say no, so a search that wanders far enough always
         * finds a way round, through country nobody has ever looked at.
         *
         * That is not a subtle failure. A bot was told an apple tree across a river was reachable,
         * every single pass, because forty tiles up the bank the map file simply stops and beyond
         * that line everything is passable by assumption. There was no way round; there was only
         * no evidence. The player put it plainly: if there is no visible path, we should not be
         * seeing one. */
        return route(gui, here.seg, from, to, MAX_NODES, lo, hi, true) != null;
    }

    /**
     * Waypoints from one segment tile to another, or null if no route was found.
     *
     * A null return is not a failure to be reported - it is the caller's cue to fall back on
     * walking straight at the target, which is what it would have done anyway. Unexplored ground
     * counts as walkable (see {@link Terrain}), so null really does mean "known to be enclosed".
     */
    public static List<Coord> route(GameUI gui, long seg, Coord fromTile, Coord toTile) {
        return route(gui, seg, fromTile, toTile, MAX_NODES, null, null, false);
    }

    /**
     * As above, confined to nodes within [lo, hi] when those are given.
     *
     * @param seenOnly refuse ground the map file has never recorded, instead of assuming it is
     *                 walkable. For callers whose answer is final - see {@link #reachable}.
     */
    public static List<Coord> route(GameUI gui, long seg, Coord fromTile, Coord toTile,
                                    int budget, Coord lo, Coord hi, boolean seenOnly) {
        Coord start = node(fromTile);
        Coord goal = node(toTile);
        if (start.equals(goal))
            return new ArrayList<>();
        /* Both ends need this and for the same reason. The start is entered by nobody, so it is
         * never tested for being open - which is right, since a bot standing a tile from its own
         * palisade is in a node with wall tiles in it and has to be able to set off. But "set off"
         * was then allowed in every direction, including straight through the wall: leaving the
         * water place, whose node the south wall runs through, the first step out of it was over
         * the palisade and the whole gateway was skipped. The trip INTO the base used a gate
         * correctly on the same run, which is what made it look like a gate bug. */
        Set<Coord> approach = approaches(gui, seg, goal, toTile);
        Set<Coord> exits = approaches(gui, seg, start, fromTile);

        Map<Coord, Coord> from = new HashMap<>();
        Map<Coord, Integer> g = new HashMap<>();
        Set<Coord> done = new HashSet<>();
        PriorityQueue<Coord> open = new PriorityQueue<>((a, b) ->
            Integer.compare(g.getOrDefault(a, Integer.MAX_VALUE) + h(a, goal),
                g.getOrDefault(b, Integer.MAX_VALUE) + h(b, goal)));
        g.put(start, 0);
        open.add(start);

        int seen = 0;
        while (!open.isEmpty() && (seen++ < budget)) {
            Coord cur = open.poll();
            if (cur.equals(goal))
                return simplify(rebuild(from, cur), gui, seg, seenOnly);
            if (!done.add(cur))
                continue;
            int cg = g.getOrDefault(cur, Integer.MAX_VALUE);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx == 0) && (dy == 0))
                        continue;
                    Coord nb = cur.add(dx, dy);
                    if (done.contains(nb))
                        continue;
                    if ((lo != null)
                        && ((nb.x < lo.x) || (nb.y < lo.y) || (nb.x > hi.x) || (nb.y > hi.y)))
                        continue;
                    if (cur.equals(start) && !exits.isEmpty() && !exits.contains(nb))
                        continue;
                    if (nb.equals(goal)) {
                        /* The GOAL is entered whatever is in it, BUT ONLY FROM ITS OWN SIDE.
                         *
                         * Entering it regardless is what makes a destination reachable at all: a
                         * caller has already decided it wants to be there, and destinations sit
                         * against things. This character's water place ends on the row the palisade
                         * stands on, so its node holds wall tiles, and refusing the node meant
                         * refusing the trip - the search had nowhere to end, spent its whole
                         * forty-thousand-node budget proving it, and said "no route".
                         *
                         * Entering it from ANY side, though, was worse in a new way. A node is four
                         * tiles across and the wall runs through the middle of this one, so from
                         * outside the base the cheapest way into the goal's node was through the
                         * wall - and the bot walked to a spot on the far side of the palisade and
                         * pushed at it, in the same place, every run.
                         *
                         * So which neighbours may be entered from is settled at tile resolution
                         * first: whatever is actually walk-connected to the destination tile. That
                         * is a question about a dozen tiles and it has an exact answer, unlike
                         * anything the four-tile grid can say about a one-tile wall. */
                        if (!approach.isEmpty() && !approach.contains(cur))
                            continue;
                    } else if (!open(gui, seg, nb, seenOnly)) {
                        continue;
                    }
                    /* Diagonals cost about sqrt(2), scaled up so the whole search stays in
                     * integers - a float heuristic here buys nothing but rounding trouble. */
                    int step = ((dx != 0) && (dy != 0)) ? 14 : 10;
                    int ng = cg + (step * cost(gui, seg, nb));
                    if (ng < g.getOrDefault(nb, Integer.MAX_VALUE)) {
                        g.put(nb, ng);
                        from.put(nb, cur);
                        open.add(nb);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Which neighbouring nodes the goal may be entered from: those actually walk-connected to the
     * destination TILE, worked out over the twelve tiles either way rather than on the node grid.
     *
     * Four-connected on purpose. A palisade is one tile thick, and eight-connectivity slips
     * through it diagonally at every corner - which here would put the outside of the base back on
     * the approved list and undo the whole point.
     *
     * An empty answer means the destination tile is itself unwalkable, or walled in on all sides
     * within this small window. Both are cases where refusing every approach would refuse the trip,
     * so the caller reads empty as "no opinion" and lets any neighbour in.
     */
    private static Set<Coord> approaches(GameUI gui, long seg, Coord goal, Coord goalTile) {
        Set<Coord> out = new HashSet<>();
        Coord lo = goal.sub(1, 1).mul(STRIDE);
        Coord hi = goal.add(2, 2).mul(STRIDE).sub(1, 1);
        if ((goalTile.x < lo.x) || (goalTile.x > hi.x)
            || (goalTile.y < lo.y) || (goalTile.y > hi.y) || !walkTile(gui, seg, goalTile))
            return out;
        Set<Coord> seen = new HashSet<>();
        Deque<Coord> queue = new ArrayDeque<>();
        seen.add(goalTile);
        queue.add(goalTile);
        int[] dxs = {1, -1, 0, 0}, dys = {0, 0, 1, -1};
        while (!queue.isEmpty()) {
            Coord t = queue.remove();
            Coord n = Terrain.floorDiv(t, new Coord(STRIDE, STRIDE));
            if (!n.equals(goal))
                out.add(n);
            for (int i = 0; i < 4; i++) {
                Coord q = t.add(dxs[i], dys[i]);
                if ((q.x < lo.x) || (q.x > hi.x) || (q.y < lo.y) || (q.y > hi.y))
                    continue;
                if (!seen.add(q))
                    continue;
                if (walkTile(gui, seg, q))
                    queue.add(q);
            }
        }
        return out;
    }

    /** One tile, at tile resolution: real walls and real water only, nothing inferred. */
    private static boolean walkTile(GameUI gui, long seg, Coord t) {
        return !Barriers.blocks(seg, t) && Terrain.ground(gui, seg, t);
    }

    /** Octile distance, in the same units as the step costs above. */
    private static int h(Coord a, Coord b) {
        int dx = Math.abs(a.x - b.x);
        int dy = Math.abs(a.y - b.y);
        return (10 * Math.max(dx, dy)) + (4 * Math.min(dx, dy));
    }

    private static Coord node(Coord segTile) {
        return Terrain.floorDiv(segTile, new Coord(STRIDE, STRIDE));
    }

    /** The tile at the middle of a node - what a waypoint actually aims at. */
    private static Coord centre(Coord node) {
        return node.mul(STRIDE).add(STRIDE / 2, STRIDE / 2);
    }

    /**
     * A node is open if its ground is walkable and no wall crosses it.
     *
     * The two halves are asked differently, and the difference is the whole point.
     *
     * GROUND is sampled at the centre tile only. A lake or a cliff is far bigger than four tiles,
     * so one sample is representative, and sixteen would only cost more to learn the same thing.
     *
     * WALLS are checked on every tile, because a palisade is ONE TILE THICK. Sampled at the centre
     * it crosses a node's centre only where it happens to line up - measured against the walls
     * this client had actually learned, a forty-five-node palisade came out with eight holes in
     * it, and a search only needs one. That is why routes came back as a straight line through the
     * side of a base: the router was not ignoring the wall, it was looking between the posts.
     *
     * A node holding any part of a GATEWAY stays open even when the rest of it is wall, which is
     * the case that makes the strict test safe - every gate in that same base sits in a node with
     * wall tiles either side of it, so blocking on "any wall tile" without this would seal every
     * entrance and leave no route at all.
     *
     * A wall the client has only WORKED OUT is as solid here as one it has seen. See
     * {@link Barriers#classify}: a base is a castle, and a bot that has learned two sides of one
     * has learned enough to stop walking round the other two looking for a way in.
     */
    private static boolean open(GameUI gui, long seg, Coord node, boolean seenOnly) {
        /* Ground nobody has looked at is not evidence of a way through. Only asked for by callers
         * whose answer is final; see {@link #reachable} for why the distinction has to exist. */
        if (seenOnly && !Terrain.known(gui, seg, centre(node)))
            return false;
        /* GROUND only, deliberately not Terrain.walkable: that folds the wall test into the same
         * answer and does it for the centre tile alone, so a gateway whose node happens to have a
         * wall tile at its middle was sealed before the gate below could be looked at. Which tile
         * of a node the middle is has nothing to do with whether you can walk through it. */
        Barriers.Block block = Barriers.classify(seg, node.mul(STRIDE),
            node.mul(STRIDE).add(STRIDE - 1, STRIDE - 1));
        /* A gateway settles it before the ground is even looked at, since a gate is the one place
         * a wall is meant to be walked through. */
        if (block == Barriers.Block.GATE)
            return true;
        if ((block == Barriers.Block.WALL) || (block == Barriers.Block.RING))
            return false;
        if (!Terrain.ground(gui, seg, centre(node)))
            return false;
        /* DEEP water gets every tile looked at, like a wall does, and for the same reason: it is
         * not always bigger than a node. The class comment's "a lake is far bigger than four
         * tiles" is true of lakes and false of rivers, and a river four tiles across sampled at
         * one tile is a river the route does not know about - which is how a bot came to plan
         * straight over one, twice, and why widening the sample matters more than any amount of
         * costing the unknown. Shallow stays a centre sample: it is passable when the run allows
         * it, and being strict there narrows every shoreline by four tiles. */
        Coord base = node.mul(STRIDE);
        for (int dy = 0; dy < STRIDE; dy++) {
            for (int dx = 0; dx < STRIDE; dx++) {
                if (Terrain.deep(gui, seg, base.add(dx, dy)))
                    return false;
            }
        }
        return !probablyWater(gui, seg, node);
    }

    /**
     * Whether an unexplored node is, on the evidence, the far half of water we can already see.
     *
     * Charging extra for the unknown ({@link #cost}) was not enough on its own, and the reason is
     * that the unknown next to a river is not merely unproven - it is the river. A bot sent across
     * the map walked down one bank, kept going until the map file ran out, and then turned across
     * the water, because past that line there was nothing left to say no: three times a short
     * crossing still beat going round. It did the same thing twice in two evenings.
     *
     * Water is contiguous and a river does not end where a character stopped looking, so a node
     * nobody has explored, touching one known to be deep, is taken to be deep as well. That is one
     * node of dilation, no more - enough to close the map edge itself, which is where the guess is
     * actually made, without inventing a lake out in open country. Beyond that ring the unknown
     * stays passable and merely expensive, which is the right answer: there really is no evidence
     * out there.
     *
     * Known ground is never touched by this. {@link Terrain#deep} answers false for the unexplored,
     * so the ring cannot grow itself outwards a second time.
     */
    private static boolean probablyWater(GameUI gui, long seg, Coord node) {
        if (Terrain.known(gui, seg, centre(node)))
            return false;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx == 0) && (dy == 0))
                    continue;
                if (Terrain.deep(gui, seg, centre(node.add(dx, dy))))
                    return true;
            }
        }
        return false;
    }

    /**
     * What a step onto this node is worth, as a multiplier on the plain step cost.
     *
     * Ground the map file has never recorded is passable - see {@link Terrain} - but it is passable
     * on an assumption, and the assumption is wrong in the one direction that matters: unexplored
     * ground next to water is usually more water. A bot took exactly that route, around a river and
     * onto ground it had never loaded, and swam the rest of the way.
     *
     * So unexplored ground costs several times what seen ground does. That is not the same as
     * refusing it: where the only route is across a strip of unknown, the search still takes it,
     * because {@link #UNKNOWN} times a short crossing is still cheaper than a long way round that
     * does not exist. What it stops is the shortcut being chosen merely because nothing was there
     * to argue against it.
     *
     * The heuristic is untouched and still assumes the cheapest possible step, so it stays
     * admissible and the search stays correct.
     */
    private static int cost(GameUI gui, long seg, Coord node) {
        return Terrain.known(gui, seg, centre(node)) ? 1 : UNKNOWN;
    }

    private static List<Coord> rebuild(Map<Coord, Coord> from, Coord end) {
        Deque<Coord> out = new ArrayDeque<>();
        for (Coord c = end; c != null; c = from.get(c))
            out.addFirst(c);
        return new ArrayList<>(out);
    }

    /**
     * Drops waypoints that add nothing.
     *
     * A raw A* result is one node per step, most of them in a straight line. Handing all of those
     * to travel would reintroduce the stutter this is meant to remove - every waypoint costs a
     * fresh pathfinder run - so a waypoint is kept only where the route actually turns, or where
     * the straight line between the kept neighbours would cross something unwalkable.
     */
    private static List<Coord> simplify(List<Coord> nodes, GameUI gui, long seg, boolean seenOnly) {
        List<Coord> out = new ArrayList<>();
        if (nodes.isEmpty())
            return out;
        int anchor = 0;
        for (int i = 2; i < nodes.size(); i++) {
            if (!clear(gui, seg, nodes.get(anchor), nodes.get(i), seenOnly)) {
                out.add(centre(nodes.get(i - 1)));
                anchor = i - 1;
            }
        }
        out.add(centre(nodes.get(nodes.size() - 1)));
        return out;
    }

    /** Whether every node on the straight line between two nodes is open. */
    private static boolean clear(GameUI gui, long seg, Coord a, Coord b, boolean seenOnly) {
        int steps = Math.max(Math.abs(b.x - a.x), Math.abs(b.y - a.y));
        for (int i = 1; i < steps; i++) {
            int x = a.x + (((b.x - a.x) * i) / steps);
            int y = a.y + (((b.y - a.y) * i) / steps);
            if (!open(gui, seg, new Coord(x, y), seenOnly))
                return false;
        }
        return true;
    }

    /** The unmodifiable empty route, for callers that want to distinguish it from null. */
    public static final List<Coord> HERE = Collections.unmodifiableList(new ArrayList<>());
}
