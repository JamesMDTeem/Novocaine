package haven.automated.nbots.world;

import haven.Coord;
import haven.GameUI;

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
     * Waypoints from one segment tile to another, or null if no route was found.
     *
     * A null return is not a failure to be reported - it is the caller's cue to fall back on
     * walking straight at the target, which is what it would have done anyway. Unexplored ground
     * counts as walkable (see {@link Terrain}), so null really does mean "known to be enclosed".
     */
    public static List<Coord> route(GameUI gui, long seg, Coord fromTile, Coord toTile) {
        Coord start = node(fromTile);
        Coord goal = node(toTile);
        if (start.equals(goal))
            return new ArrayList<>();

        Map<Coord, Coord> from = new HashMap<>();
        Map<Coord, Integer> g = new HashMap<>();
        Set<Coord> done = new HashSet<>();
        PriorityQueue<Coord> open = new PriorityQueue<>((a, b) ->
            Integer.compare(g.getOrDefault(a, Integer.MAX_VALUE) + h(a, goal),
                g.getOrDefault(b, Integer.MAX_VALUE) + h(b, goal)));
        g.put(start, 0);
        open.add(start);

        int seen = 0;
        while (!open.isEmpty() && (seen++ < MAX_NODES)) {
            Coord cur = open.poll();
            if (cur.equals(goal))
                return simplify(rebuild(from, cur), gui, seg);
            if (!done.add(cur))
                continue;
            int cg = g.getOrDefault(cur, Integer.MAX_VALUE);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx == 0) && (dy == 0))
                        continue;
                    Coord nb = cur.add(dx, dy);
                    if (done.contains(nb) || !open(gui, seg, nb))
                        continue;
                    /* Diagonals cost about sqrt(2), scaled up so the whole search stays in
                     * integers - a float heuristic here buys nothing but rounding trouble. */
                    int step = ((dx != 0) && (dy != 0)) ? 14 : 10;
                    int ng = cg + step;
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
     * A node is open if its centre tile is walkable.
     *
     * Sampling one tile per node rather than all sixteen is a deliberate trade: it is four times
     * cheaper and the local pathfinder re-checks every tile on the way anyway. The case it gets
     * wrong is a gap narrower than the stride, which the fallback to straight-line walking covers.
     */
    private static boolean open(GameUI gui, long seg, Coord node) {
        return Terrain.walkable(gui, seg, centre(node));
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
    private static List<Coord> simplify(List<Coord> nodes, GameUI gui, long seg) {
        List<Coord> out = new ArrayList<>();
        if (nodes.isEmpty())
            return out;
        int anchor = 0;
        for (int i = 2; i < nodes.size(); i++) {
            if (!clear(gui, seg, nodes.get(anchor), nodes.get(i))) {
                out.add(centre(nodes.get(i - 1)));
                anchor = i - 1;
            }
        }
        out.add(centre(nodes.get(nodes.size() - 1)));
        return out;
    }

    /** Whether every node on the straight line between two nodes is open. */
    private static boolean clear(GameUI gui, long seg, Coord a, Coord b) {
        int steps = Math.max(Math.abs(b.x - a.x), Math.abs(b.y - a.y));
        for (int i = 1; i < steps; i++) {
            int x = a.x + (((b.x - a.x) * i) / steps);
            int y = a.y + (((b.y - a.y) * i) / steps);
            if (!open(gui, seg, new Coord(x, y)))
                return false;
        }
        return true;
    }

    /** The unmodifiable empty route, for callers that want to distinguish it from null. */
    public static final List<Coord> HERE = Collections.unmodifiableList(new ArrayList<>());
}
