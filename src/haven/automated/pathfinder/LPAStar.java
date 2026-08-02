package haven.automated.pathfinder;

import haven.*;
import haven.automated.nbots.world.Router;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Lifelong Planning A* — incremental shortest-path search over a tile grid.
 *
 * Unlike a fresh A* run, LPA* remembers the previous search's cost estimates and only
 * recomputes the parts of the path that are affected by changes. When a wall is learned,
 * a gate opens, or a refused tile expires, this avoids re-scanning the entire segment.
 *
 * The algorithm maintains two cost estimates per tile:
 *
 * - {@code g[t]} — the actual shortest-path cost from {@link #start} to {@code t}, as found
 *   by the most recent complete search.
 * - {@code rhs[t]} — the one-step lookahead cost, i.e. the cheapest known way to reach
 *   {@code t} through any single neighbour. When {@code g[t] == rhs[t]} the tile is
 *   locally consistent; when they differ the tile is inconsistent and must be repaired.
 *
 * A priority queue ordered by {@code min(g, rhs) + h} drives the repair.  On the first call
 * to {@link #search} the queue is empty and the search falls back to a full A*; on every
 * subsequent call with the same start, goal and world it resumes from the cached state and
 * only processes tiles whose neighbours changed.
 *
 * The caller is responsible for feeding tile-level changes back in via {@link #updateTile}
 * before calling {@link #search} again.  Tiles whose passability or cost has not changed
 * are skipped automatically.
 */
public class LPAStar {

    /** Infinity, large enough that g + cost never overflows but small enough to be safe. */
    private static final int INF = Integer.MAX_VALUE / 4;

    /** Step costs, same scale as {@link Router#STRAIGHT} and {@link Router#DIAGONAL}. */
    private static final int STRAIGHT = 10, DIAGONAL = 14;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    private Router.World world;
    private Coord start, goal;
    private Map<Coord, Integer> g = new HashMap<>();
    private Map<Coord, Integer> rhs = new HashMap<>();
    private PriorityQueue<Step> open = new PriorityQueue<>();

    /** One entry in the open set, carrying the priority it was queued with. */
    private static final class Step {
        final Coord at;
        final int key;

        Step(Coord at, int key) {
            this.at = at;
            this.key = key;
        }
    }

    /**
     * First search (or re-search after start/goal/world changed).
     *
     * When the start, goal or world differs from the previous call the cached state is
     * discarded and a full A* is run, seeding {@code g} with the result.  Subsequent calls
     * with the same parameters will resume incrementally.
     *
     * @param w      the walkability map
     * @param from   segment tile where the search starts
     * @param to     segment tile where the search ends
     * @param clamped whether unseen ground is impassable
     * @return the tile path, or null when no path exists
     */
    public java.util.List<Coord> search(Router.World w, Coord from, Coord to, boolean clamped) {
        boolean changed = (world != w) || !start.equals(from) || !goal.equals(to);
        if (changed) {
            this.world = w;
            this.start = from;
            this.goal = to;
            this.g = new HashMap<>();
            this.rhs = new HashMap<>();
            this.open = new PriorityQueue<>();
            return aStar(w, from, to, clamped);
        }
        computeShortestPath();
        return trace();
    }

    /**
     * Notify that a single tile's cost or passability has changed.
     *
     * Call this for every tile that was added to or removed from {@link Router.World}
     * since the last search.  The change is not applied to the world itself — the caller
     * must have already updated its own data source.  This method only marks the tile so
     * the next {@link #search} call will re-evaluate it.
     *
     * @param t the segment tile that changed
     */
    public void updateTile(Coord t) {
        if (t == null || world == null) return;
        updateVertex(t);
    }

    /**
     * Notify that multiple tiles changed.  Equivalent to calling {@link #updateTile} on each.
     */
    public void updateTiles(java.util.Set<Coord> tiles) {
        if (tiles == null || world == null) return;
        for (Coord t : tiles) {
            updateVertex(t);
        }
    }

    // ----------------------------------------------------------------------- internals

    /** Full A* search — used for the first call and when the problem has changed. */
    private java.util.List<Coord> aStar(Router.World w, Coord from, Coord to, boolean clamped) {
        if (from.equals(to))
            return new java.util.ArrayList<>();

        Map<Coord, Coord> came = new HashMap<>();
        Map<Coord, Integer> dist = new HashMap<>();
        Set<Coord> done = new HashSet<>();
        PriorityQueue<AStarStep> open = new PriorityQueue<>((a, b) -> Integer.compare(a.f, b.f));
        dist.put(from, 0);
        open.add(new AStarStep(from, h(from, to)));

        Coord bestAt = from;
        int bestF = h(from, to);
        int seen = 0;

        while (!open.isEmpty() && seen++ < Router.MAX_TILES) {
            Coord cur = open.poll().at;
            if (cur.equals(to))
                return simplify(w, traceFrom(came, cur));
            if (!done.add(cur))
                continue;

            int cf = dist.getOrDefault(cur, INF) + h(cur, to);
            if (cf < bestF) {
                bestF = cf;
                bestAt = cur;
            }

            int cg = dist.getOrDefault(cur, INF);
            for (int i = 0; i < 8; i++) {
                Coord nb = cur.add(DX[i], DY[i]);
                if (done.contains(nb)) continue;
                if (!w.passable(nb)) continue;
                boolean diag = (DX[i] != 0) && (DY[i] != 0);
                if (diag && (!w.passable(cur.add(DX[i], 0)) || !w.passable(cur.add(0, DY[i]))))
                    continue;
                int ng = cg + ((diag ? DIAGONAL : STRAIGHT) * w.cost(nb));
                if (ng < dist.getOrDefault(nb, INF)) {
                    dist.put(nb, ng);
                    came.put(nb, cur);
                    open.add(new AStarStep(nb, ng + h(nb, to)));
                }
            }
        }

        if (clamped && !bestAt.equals(from)) {
            return simplify(w, traceFrom(came, bestAt));
        }
        return null;
    }

    /** Standard LPA* repair loop. */
    private void computeShortestPath() {
        while (!open.isEmpty()) {
            Step uStep = open.poll();
            Coord u = uStep.at;

            // Skip stale entries
            int currentMin = Math.min(g.getOrDefault(u, INF), rhs.getOrDefault(u, INF));
            if (uStep.key > currentMin + h(u, goal))
                continue;

            if (g.getOrDefault(u, INF) > rhs.getOrDefault(u, INF)) {
                // u is over-consistent: g > rhs.  Set g = rhs and update successors.
                g.put(u, rhs.get(u));
                for (int i = 0; i < 8; i++) {
                    Coord nb = u.add(DX[i], DY[i]);
                    if (world.passable(nb))
                        updateVertex(nb);
                }
            } else {
                // u is under-consistent or exact: g <= rhs.
                if (g.getOrDefault(u, INF) != INF) {
                    for (int i = 0; i < 8; i++) {
                        Coord nb = u.add(DX[i], DY[i]);
                        if (world.passable(nb))
                            updateVertex(nb);
                    }
                }
                g.put(u, INF);
                for (int i = 0; i < 8; i++) {
                    Coord nb = u.add(DX[i], DY[i]);
                    if (world.passable(nb))
                        updateVertex(nb);
                }
            }
        }
    }

    /**
     * Re-compute {@code rhs[u]} and insert/update {@code u} in the open queue as needed.
     *
     * This is the core of LPA*: when a neighbour's g value changes, the rhs of every
     * adjacent tile may need to be recomputed.
     */
    private void updateVertex(Coord u) {
        if (u.equals(start)) {
            rhs.put(u, 0);
            return;
        }

        int oldRhs = rhs.getOrDefault(u, INF);
        int newRhs = INF;
        for (int i = 0; i < 8; i++) {
            Coord nb = u.add(DX[i], DY[i]);
            if (!world.passable(nb)) continue;
            boolean diag = (DX[i] != 0) && (DY[i] != 0);
            int cost = (diag ? DIAGONAL : STRAIGHT) * world.cost(nb);
            int val = g.getOrDefault(nb, INF) + cost;
            if (val < newRhs)
                newRhs = val;
        }

        rhs.put(u, newRhs);
        if (newRhs == oldRhs) return;  // nothing changed

        // Re-insert with updated key.  Old entries remain in the queue but are skipped
        // by the staleness check in computeShortestPath.
        int key = Math.min(g.getOrDefault(u, INF), newRhs) + h(u, goal);
        open.add(new Step(u, key));
    }

    /** Reconstruct the path from {@link #start} to {@link #goal} using g-values. */
    private java.util.List<Coord> trace() {
        if (!rhs.containsKey(goal) && g.getOrDefault(goal, INF) == INF)
            return null;
        java.util.List<Coord> path = new java.util.ArrayList<>();
        Coord cur = goal;
        while (!cur.equals(start)) {
            path.add(cur);
            Coord best = null;
            int bestG = INF;
            for (int i = 0; i < 8; i++) {
                Coord nb = cur.add(DX[i], DY[i]);
                if (!world.passable(nb)) continue;
                Integer ng = g.get(nb);
                if (ng != null && ng < bestG) {
                    bestG = ng;
                    best = nb;
                }
            }
            if (best == null) break;
            cur = best;
        }
        if (cur != start) return null;
        java.util.Collections.reverse(path);
        return path;
    }

    /** Octile heuristic, same scale as the step costs. */
    private static int h(Coord a, Coord b) {
        int dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y);
        return (STRAIGHT * Math.max(dx, dy)) + ((DIAGONAL - STRAIGHT) * Math.min(dx, dy));
    }

    // ----------------------------------------------------------------------- helpers

    private static java.util.List<Coord> traceFrom(Map<Coord, Coord> came, Coord end) {
        java.util.Deque<Coord> out = new java.util.ArrayDeque<>();
        for (Coord c = end; c != null; c = came.get(c))
            out.addFirst(c);
        return new java.util.ArrayList<>(out);
    }

    private static java.util.List<Coord> simplify(Router.World w, java.util.List<Coord> path) {
        java.util.List<Coord> out = new java.util.ArrayList<>();
        if (path.size() < 2) return out;
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

    private static boolean clear(Router.World w, Coord a, Coord b) {
        for (Coord t : along(a, b)) {
            if (!w.passable(t)) return false;
        }
        return true;
    }

    private static java.util.List<Coord> along(Coord a, Coord b) {
        java.util.List<Coord> out = new java.util.ArrayList<>();
        int dx = b.x - a.x, dy = b.y - a.y;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) {
            out.add(a);
            return out;
        }
        double xInc = (double) dx / steps, yInc = (double) dy / steps;
        double x = a.x, y = a.y;
        for (int i = 0; i <= steps; i++) {
            out.add(new Coord((int) Math.round(x), (int) Math.round(y)));
            x += xInc;
            y += yInc;
        }
        return out;
    }

    /** A* open-set step, kept separate to avoid mixing algorithms. */
    private static final class AStarStep {
        final Coord at;
        final int f;

        AStarStep(Coord at, int f) {
            this.at = at;
            this.f = f;
        }
    }
}
