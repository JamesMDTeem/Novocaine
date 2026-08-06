package haven.automated.pathfinder;

import haven.Coord;
import haven.automated.nbots.world.Router;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Stateless A* over the {@link Router.World} tile grid.
 *
 * This was an LPA* (Lifelong Planning A*) that kept {@code g}/{@code rhs} cost maps between calls
 * and repaired them incrementally when a tile changed. That machinery was removed because it never
 * did anything: {@link Router} re-plans from the player's CURRENT tile, so the start moved on almost
 * every call, which discards the cached state and forces a full search anyway - and worse, it was a
 * single {@code static} instance mutated from both a bot thread (searching) and the UI thread
 * (learning walls), a data race waiting to corrupt the cost maps. A full octile-heuristic search
 * over a few hundred tiles settles in a few milliseconds, which is nothing next to the walk it
 * plans, so there was no cost worth that risk.
 *
 * The result is the RAW tile path, one tile per step, {@code from} first and {@code to} last.
 * Waypoint thinning is deliberately NOT done here: {@link Router#simplify} is the single authority
 * on that, and it is the one that checks a line at quarter-tile resolution against the character's
 * half-width. A coarser thinning pass here would drop the very corner waypoints that check exists
 * to keep - see the note on {@code Router.along}.
 *
 * Every call is independent: no fields, no shared state, safe to call from any thread. The world is
 * read fresh through {@link Router.World#passable}/{@link Router.World#cost} each time.
 */
public final class GridAStar {

    /** Infinity, large enough that g + cost never overflows but small enough to be safe. */
    private static final int INF = Integer.MAX_VALUE / 4;

    /** Step costs, same scale as {@link Router#STRAIGHT} and {@link Router#DIAGONAL}. */
    private static final int STRAIGHT = 10, DIAGONAL = 14;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    private GridAStar() {}

    /**
     * Shortest tile path from {@code from} to {@code to}, or null when there is none.
     *
     * @param w       the walkability map
     * @param from    segment tile where the search starts
     * @param to      segment tile where the search ends
     * @param clamped when true, and no full path exists, return the best partial path to the
     *                reachable tile nearest the goal rather than null (the "vision edge" toward the
     *                destination); when false, an unreachable goal returns null
     * @return the raw tile path ({@code from} first, {@code to} last), or null
     */
    public static List<Coord> search(Router.World w, Coord from, Coord to, boolean clamped) {
        if (from.equals(to))
            return new ArrayList<>();

        Map<Coord, Coord> came = new HashMap<>();
        Map<Coord, Integer> dist = new HashMap<>();
        Set<Coord> done = new HashSet<>();
        PriorityQueue<Step> open = new PriorityQueue<>((a, b) -> Integer.compare(a.f, b.f));
        dist.put(from, 0);
        open.add(new Step(from, h(from, to)));

        /* The closest to the goal anything got, measured by the HEURISTIC alone.
         *
         * It used to be measured by f - g plus h, the whole estimated journey - and that made the
         * clamped answer unreachable code. With an admissible, consistent heuristic and every step
         * costing something, f never decreases along a search: the first node's f is the smallest
         * there will ever be, so `< bestF` could not fire once, `bestAt` stayed at `from`, and the
         * `!bestAt.equals(from)` guard below turned every clamped search into a plain null. Nothing
         * downstream of it had ever run - not routeClamped, not the explore-forward loop that walks
         * toward the vision edge to see more, both of which the comments describe at length.
         *
         * h alone is the right measure anyway, and not merely the working one: what the caller wants
         * from a failed search is the reachable tile NEAREST THE DESTINATION, to walk at and look
         * again from. How expensive that tile was to reach is the wrong tiebreak - it would prefer a
         * cheap tile behind us to a dear one that actually made progress. */
        Coord bestAt = from;
        int bestH = h(from, to);
        int seen = 0;

        while (!open.isEmpty() && seen++ < Router.MAX_TILES) {
            Coord cur = open.poll().at;
            if (cur.equals(to))
                return traceFrom(came, cur);
            if (!done.add(cur))
                continue;

            int ch = h(cur, to);
            if (ch < bestH) {
                bestH = ch;
                bestAt = cur;
            }

            int cg = dist.getOrDefault(cur, INF);
            for (int i = 0; i < 8; i++) {
                Coord nb = cur.add(DX[i], DY[i]);
                if (done.contains(nb)) continue;
                if (!w.passable(nb)) continue;
                boolean diag = (DX[i] != 0) && (DY[i] != 0);
                // No corner-cutting: a diagonal step needs both orthogonal neighbours open.
                if (diag && (!w.passable(cur.add(DX[i], 0)) || !w.passable(cur.add(0, DY[i]))))
                    continue;
                int ng = cg + ((diag ? DIAGONAL : STRAIGHT) * w.cost(nb));
                if (ng < dist.getOrDefault(nb, INF)) {
                    dist.put(nb, ng);
                    came.put(nb, cur);
                    open.add(new Step(nb, ng + h(nb, to)));
                }
            }
        }

        if (clamped && !bestAt.equals(from))
            return traceFrom(came, bestAt);
        return null;
    }

    /** Octile heuristic, same scale as the step costs. */
    private static int h(Coord a, Coord b) {
        int dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y);
        return (STRAIGHT * Math.max(dx, dy)) + ((DIAGONAL - STRAIGHT) * Math.min(dx, dy));
    }

    private static List<Coord> traceFrom(Map<Coord, Coord> came, Coord end) {
        Deque<Coord> out = new ArrayDeque<>();
        for (Coord c = end; c != null; c = came.get(c))
            out.addFirst(c);
        return new ArrayList<>(out);
    }

    /** One entry in the open set, carrying the f-score it was queued with. */
    private static final class Step {
        final Coord at;
        final int f;

        Step(Coord at, int f) {
            this.at = at;
            this.f = f;
        }
    }
}
