package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.automated.pathfinder.Map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Where to stand to work one object, worked out on a fine grid of the ground around it.
 *
 * Built for the smelter bot after four runs in one mine yard, where every cheaper answer failed
 * in its own way:
 *
 * <ul>
 *   <li>A ring of spots round the object's CENTRE ({@link WorkSlots}) is a circle laid over a row
 *       of rectangles. Round a smelter in a packed row most of the ring lands inside the
 *       neighbours or a mine support, and the one spot that did work - just west of the support,
 *       under the smelter's south face - was not on the ring at all.</li>
 *   <li>The local pathfinder and the straight-line fallback judged a gap narrower than the
 *       character as passable, walked into it, and wedged: every path from there failed on its
 *       first step, and the bot never moved again.</li>
 *   <li>Standing "in reach" is not enough. A right-click makes the SERVER walk the character
 *       straight at the object, and a mine support on that line stops it short with no window.</li>
 * </ul>
 *
 * So this maps it properly. The ground within a few tiles of us and the target is cut into
 * cells ({@link #pitch} units); a cell is standable when a character-sized disc on it touches no
 * collision box and no rock, water or cave tile; the standable cells are flooded from where we
 * stand, so "reachable" means reachable and not merely clear. Every reachable cell near the target
 * is then scored by what the server will actually do from it: a straight line to the target that
 * meets nothing else on the way is a spot that works, and the nearest of those comes first.
 *
 * The grid is live collision boxes only, no remembered record, and it is local - a few tiles
 * round the two ends - so it is exact where it is used and cheap: a few thousand cells, each
 * tested against only the handful of objects that could touch it.
 */
public final class WorkSpot {
    /**
     * Grid pitch: two fifths of the character's half-width, and never coarser than 2 units. A gap
     * only a little wider than the character is found only by a grid fine enough to put a cell
     * centre in the middle of it. Replayed against the test yard, the corridor under the north-east
     * smelter is found at half-width 2.5 on a 1-unit grid and missed on a 1.25-unit one.
     */
    static double pitch(double clearance) {
        return Math.min(2.0, clearance / 2.5);
    }
    /** How far past us and the target the grid extends, for the way round. */
    private static final double MARGIN = MCache.tilesz.x * 4;
    /** Larger than this and the caller should get closer by other means first. */
    private static final double MAX_SPAN = MCache.tilesz.x * 60;
    /** How far out from the target's edge a standing spot may be. */
    private static final double NEAR = MCache.tilesz.x * 3;
    /** Two spots offered closer together than this are the same spot. */
    private static final double APART = 6.0;
    /**
     * Room to spare that a standing spot is preferred for having. A spot a character only just
     * fits - the pocket between a mine support and the smelter beside it was one - is one the
     * server lets you into and does not always let you out of.
     */
    private static final double SPARE = 1.5;
    /** How far to look for a standable cell when the one we are on is not. */
    private static final int SEED_RADIUS = 4;

    private WorkSpot() {}

    /** A place to stand, and how to get there from where we are. */
    public static final class Spot {
        public final Coord2d at;
        /** Straight legs from our position to {@link #at}; each is clear of everything. */
        public final List<Coord2d> legs;
        /** The straight line from here to the target meets nothing else. */
        public final boolean clear;
        /** Distance from here to the target's edge along that line. */
        public final double gap;

        Spot(Coord2d at, List<Coord2d> legs, boolean clear, double gap) {
            this.at = at;
            this.legs = legs;
            this.clear = clear;
            this.gap = gap;
        }

        public String toString() {
            return String.format("(%.0f,%.0f) %s %.0fu from its edge, %d leg(s)", at.x, at.y,
                clear ? "clear line," : "BLOCKED line,", gap, legs.size());
        }
    }

    /**
     * Up to {@code max} places to stand to work {@code target}, best first: spots with a clear line
     * to it before spots without, and among each, the nearest to walk to and to the target's edge.
     * Empty when the grid would be too large or nothing near it is reachable.
     *
     * @param clearance the character's half-width. {@link haven.automated.pathfinder.World#HALFWIDTH}
     *                  is what the client pathfinder uses.
     */
    public static List<Spot> find(GameUI gui, Gob target, double clearance, int max) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if ((me == null) || (target == null))
            return Collections.emptyList();
        double x0 = Math.min(me.rc.x, target.rc.x) - MARGIN, y0 = Math.min(me.rc.y, target.rc.y) - MARGIN;
        double x1 = Math.max(me.rc.x, target.rc.x) + MARGIN, y1 = Math.max(me.rc.y, target.rc.y) + MARGIN;
        if ((x1 - x0 > MAX_SPAN) || (y1 - y0 > MAX_SPAN))
            return Collections.emptyList();
        Grid g = new Grid(gui, target, x0, y0, x1, y1, clearance, pitch(clearance));

        int seed = g.seed(me.rc);
        if (seed < 0)
            return Collections.emptyList();
        int[] parent = g.flood(seed);

        double reach = Reach.radius(target) + NEAR;
        List<double[]> cand = new ArrayList<>();   // {cell, clear?0:1, gap, steps}
        for (int i = 0; i < g.n; i++) {
            if (parent[i] == -1)
                continue;
            Coord2d c = g.centre(i);
            if (c.dist(target.rc) > reach)
                continue;
            double[] ray = g.ray(c);
            if (ray == null)
                continue;
            cand.add(new double[] {i, ray[1], ray[0], g.steps(parent, i), g.roomy(i, clearance + SPARE) ? 0 : 1});
        }
        cand.sort((a, b) -> {
            if (a[1] != b[1])
                return Double.compare(a[1], b[1]);
            if (a[4] != b[4])
                return Double.compare(a[4], b[4]);
            // Walking distance and gap together: a spot a step further away that the server walks
            // straight into the target from beats a nearer one it has further to go from.
            return Double.compare(a[3] * g.cell + 3 * a[2], b[3] * g.cell + 3 * b[2]);
        });

        List<Spot> out = new ArrayList<>();
        for (double[] c : cand) {
            if (out.size() >= max)
                break;
            Coord2d at = g.centre((int) c[0]);
            boolean near = false;
            for (Spot s : out)
                near |= s.at.dist(at) < APART;
            if (near)
                continue;
            out.add(new Spot(at, g.legs(parent, seed, (int) c[0], me.rc), c[1] == 0, c[2]));
        }
        return out;
    }

    /**
     * Straight legs from where we stand to {@code dest} through standable ground, or null when
     * {@code dest} cannot be reached that way. For going back to a spot known to work.
     */
    public static List<Coord2d> route(GameUI gui, Coord2d dest, double clearance) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if ((me == null) || (dest == null))
            return null;
        double x0 = Math.min(me.rc.x, dest.x) - MARGIN, y0 = Math.min(me.rc.y, dest.y) - MARGIN;
        double x1 = Math.max(me.rc.x, dest.x) + MARGIN, y1 = Math.max(me.rc.y, dest.y) + MARGIN;
        if ((x1 - x0 > MAX_SPAN) || (y1 - y0 > MAX_SPAN))
            return null;
        Grid g = new Grid(gui, null, x0, y0, x1, y1, clearance, pitch(clearance));
        int seed = g.seed(me.rc);
        int to = g.cell(dest);
        if ((seed < 0) || (to < 0))
            return null;
        int[] parent = g.flood(seed);
        if (parent[to] == -1)
            return null;
        List<Coord2d> legs = g.legs(parent, seed, to, me.rc);
        legs.add(dest);
        return legs;
    }

    /**
     * The grid round a target as text, for the log: {@code #} solid, {@code o} standable and
     * reachable from us, {@code .} standable but cut off, {@code T} the target, {@code @} us.
     * One character per cell, north up.
     */
    public static String picture(GameUI gui, Gob target, double clearance) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if ((me == null) || (target == null))
            return "(no picture)";
        double span = Reach.radius(target) + NEAR + MARGIN / 2;
        double x0 = target.rc.x - span, y0 = target.rc.y - span;
        Grid g = new Grid(gui, target, x0, y0, target.rc.x + span, target.rc.y + span, clearance, 1.0);
        int seed = g.seed(me.rc);
        int[] parent = (seed < 0) ? null : g.flood(seed);
        int us = g.cell(me.rc);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("grid round #%d at %.0fu half-width, 1u cells, x %.0f.., y %.0f..%s%n",
            target.id, clearance, x0, y0, (seed < 0) ? " - we are outside it, so nothing is marked reachable" : ""));
        for (int y = 0; y < g.h; y++) {
            for (int x = 0; x < g.w; x++) {
                int i = y * g.w + x;
                char c;
                if (i == us)
                    c = '@';
                else if (BotNav.occupied(g.targetOnly, g.centre(i)))
                    c = 'T';
                else if (!g.free[i])
                    c = '#';
                else
                    c = ((parent != null) && (parent[i] != -1)) ? 'o' : '.';
                sb.append(c);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Whether the server's straight walk from {@code from} to {@code target} - the line a click on
     * it takes - meets nothing else on the way.
     */
    public static boolean lineTo(GameUI gui, Gob target, Coord2d from, double clearance) {
        if ((gui == null) || (target == null) || (from == null))
            return false;
        double x0 = Math.min(from.x, target.rc.x) - MARGIN, y0 = Math.min(from.y, target.rc.y) - MARGIN;
        double x1 = Math.max(from.x, target.rc.x) + MARGIN, y1 = Math.max(from.y, target.rc.y) + MARGIN;
        if ((x1 - x0 > MAX_SPAN) || (y1 - y0 > MAX_SPAN))
            return false;
        Grid g = new Grid(gui, target, x0, y0, x1, y1, clearance, pitch(clearance));
        double[] ray = g.ray(from);
        return (ray != null) && (ray[1] == 0);
    }

    /** How far round a planned pile to look for ground it would cut off. */
    private static final double SEAL_REACH = MCache.tilesz.x * 6;
    /** Cut-off cells tolerated as grid noise - a corner cell the diagonal rule strands. */
    private static final int SEAL_NOISE = 4;

    /**
     * Whether a solid square of half-width {@code half} at {@code at} would cut any standable ground
     * off from the rest - a pocket, or the far side of a lane it closes.
     *
     * Built after the smelter bot shut itself in: it walked into a gap to place a bar pile, and the
     * pile closed the gap behind it. "Open ground on three sides" did not see it - the pocket was
     * seven units by nine, far smaller than the pile. So this does not measure: it floods the ground
     * round the square twice, from where we stand and from the edge of the area looked at, once as
     * it is and once with the square in place, and refuses the square if any cell that stays
     * standable stops being reachable. That is exactly a pocket, whatever its size, and exactly a
     * lane closed, whatever its length.
     */
    public static boolean cutsOff(GameUI gui, Coord2d at, double half, double clearance) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if ((me == null) || (at == null))
            return true;
        double x0 = at.x - SEAL_REACH, y0 = at.y - SEAL_REACH, x1 = at.x + SEAL_REACH, y1 = at.y + SEAL_REACH;
        double c = pitch(clearance);
        Grid now = new Grid(gui, null, x0, y0, x1, y1, clearance, c);
        Grid then = new Grid(gui, null, x0, y0, x1, y1, clearance, c,
            new double[] {at.x - half, at.y - half, at.x + half, at.y + half});
        boolean[] a = now.outside(me.rc), b = then.outside(me.rc);
        int lost = 0;
        for (int i = 0; i < now.n; i++) {
            if (a[i] && !b[i] && then.free[i])
                lost++;
        }
        return lost > SEAL_NOISE;
    }

    /** Standable ground reachable from where we stand within {@code tiles} tiles, in square tiles. */
    public static double roomAround(GameUI gui, double clearance, double tiles) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if (me == null)
            return 0;
        double r = MCache.tilesz.x * tiles, c = pitch(clearance);
        Grid g = new Grid(gui, null, me.rc.x - r, me.rc.y - r, me.rc.x + r, me.rc.y + r, clearance, c);
        int seed = g.seed(me.rc);
        if (seed < 0)
            return 0;
        int[] parent = g.flood(seed);
        int n = 0;
        for (int p : parent) {
            if (p != -1)
                n++;
        }
        return n * c * c / (MCache.tilesz.x * MCache.tilesz.y);
    }

    /**
     * The way from where we stand to the nearest open ground, walking as a character of half-width
     * {@code narrow}: ground that at half-width {@code wide} is part of a stretch at least
     * {@code open} square tiles across, within {@code tiles} tiles of us. Empty when we are on open
     * ground already, null when there is no way to any.
     *
     * Open ground is judged by what it is joined to, not by how much room is round us. The
     * north-east smelter's corridor is a few square tiles and perfectly fine to be in; the pocket by
     * the mine support is about the same size and is a trap. What tells them apart is that the one
     * has a way out at the width the character can squeeze through - which is this.
     */
    public static List<Coord2d> escape(GameUI gui, double wide, double narrow, double tiles, double open) {
        Gob me = (gui == null || gui.map == null) ? null : gui.map.player();
        if (me == null)
            return null;
        double r = MCache.tilesz.x * tiles;
        double x0 = me.rc.x - r, y0 = me.rc.y - r, x1 = me.rc.x + r, y1 = me.rc.y + r;
        Grid gw = new Grid(gui, null, x0, y0, x1, y1, wide, pitch(wide));
        // Label the wide grid's connected stretches and measure each.
        int[] comp = new int[gw.n];
        java.util.Arrays.fill(comp, -1);
        List<Double> area = new ArrayList<>();
        double cellArea = gw.cell * gw.cell / (MCache.tilesz.x * MCache.tilesz.y);
        for (int i = 0; i < gw.n; i++) {
            if (!gw.free[i] || (comp[i] != -1))
                continue;
            int label = area.size();
            int count = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            comp[i] = label;
            q.add(i);
            while (!q.isEmpty()) {
                int c = q.poll();
                count++;
                for (int j : gw.steps8(c)) {
                    if (comp[j] == -1) {
                        comp[j] = label;
                        q.add(j);
                    }
                }
            }
            area.add(count * cellArea);
        }
        int here = gw.seed(me.rc);
        if ((here >= 0) && (area.get(comp[here]) >= open))
            return Collections.emptyList();
        // Walk out at the narrow width to the first cell of an open stretch.
        Grid gn = new Grid(gui, null, x0, y0, x1, y1, narrow, pitch(narrow));
        int seed = gn.seed(me.rc, 12);
        if (seed < 0)
            return null;
        int[] parent = gn.flood(seed);
        int best = -1, bestSteps = Integer.MAX_VALUE;
        for (int i = 0; i < gn.n; i++) {
            if (parent[i] == -1)
                continue;
            int w = gw.cell(gn.centre(i));
            if ((w < 0) || !gw.free[w] || (area.get(comp[w]) < open))
                continue;
            int st = gn.steps(parent, i);
            if (st < bestSteps) {
                bestSteps = st;
                best = i;
            }
        }
        return (best < 0) ? null : gn.legs(parent, seed, best, me.rc);
    }

    /** The standable cells in one rectangle, and the objects that decided them. */
    private static final class Grid {
        final GameUI gui;
        final Gob target;
        final double x0, y0, clearance, cell;
        /** An extra solid box {x0, y0, x1, y1} that is not there yet - a pile about to be placed. */
        final double[] extra;
        final int w, h, n;
        final boolean[] free;
        /** Per cell, the solids whose box could reach it. Mostly null. */
        final List<List<Gob>> near;
        final List<Gob> targetOnly;
        final HashMap<Coord, Boolean> ground = new HashMap<>();

        Grid(GameUI gui, Gob target, double x0, double y0, double x1, double y1, double clearance, double cell) {
            this(gui, target, x0, y0, x1, y1, clearance, cell, null);
        }

        Grid(GameUI gui, Gob target, double x0, double y0, double x1, double y1, double clearance, double cell,
             double[] extra) {
            this.extra = extra;
            this.gui = gui;
            this.target = target;
            this.x0 = x0;
            this.y0 = y0;
            this.clearance = clearance;
            this.cell = cell;
            this.w = (int) Math.ceil((x1 - x0) / cell);
            this.h = (int) Math.ceil((y1 - y0) / cell);
            this.n = w * h;
            this.targetOnly = (target == null) ? Collections.<Gob>emptyList() : Collections.singletonList(target);
            this.near = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
                near.add(null);
            for (Gob s : BotNav.solids(gui)) {
                double r = Reach.radius(s);
                if (r <= 0)
                    continue;
                double reach = r + clearance + cell;
                int cx0 = cx(s.rc.x - reach), cx1 = cx(s.rc.x + reach);
                int cy0 = cy(s.rc.y - reach), cy1 = cy(s.rc.y + reach);
                if ((cx1 < 0) || (cy1 < 0) || (cx0 >= w) || (cy0 >= h))
                    continue;
                for (int y = Math.max(0, cy0); y <= Math.min(h - 1, cy1); y++) {
                    for (int x = Math.max(0, cx0); x <= Math.min(w - 1, cx1); x++) {
                        int i = y * w + x;
                        if (near.get(i) == null)
                            near.set(i, new ArrayList<>(2));
                        near.get(i).add(s);
                    }
                }
            }
            this.free = new boolean[n];
            for (int i = 0; i < n; i++)
                free[i] = standable(i);
        }

        int cx(double wx) {
            return (int) Math.floor((wx - x0) / cell);
        }

        int cy(double wy) {
            return (int) Math.floor((wy - y0) / cell);
        }

        int cell(Coord2d p) {
            int x = cx(p.x), y = cy(p.y);
            return ((x < 0) || (y < 0) || (x >= w) || (y >= h)) ? -1 : (y * w + x);
        }

        Coord2d centre(int i) {
            return new Coord2d(x0 + ((i % w) + 0.5) * cell, y0 + ((i / w) + 0.5) * cell);
        }

        /** A character-sized disc here touches nothing solid and stands on walkable ground. */
        boolean standable(int i) {
            Coord2d c = centre(i);
            if (!groundAt(c))
                return false;
            if ((extra != null) && (c.x > extra[0] - clearance) && (c.x < extra[2] + clearance)
                && (c.y > extra[1] - clearance) && (c.y < extra[3] + clearance))
                return false;
            List<Gob> l = near.get(i);
            if (l == null)
                return true;
            double d = clearance, e = clearance * 0.7071;
            Coord2d[] probe = {c, c.add(d, 0), c.add(-d, 0), c.add(0, d), c.add(0, -d),
                c.add(e, e), c.add(e, -e), c.add(-e, e), c.add(-e, -e)};
            for (Coord2d p : probe) {
                if (BotNav.occupied(l, p))
                    return false;
            }
            return true;
        }

        /** Whether a wider disc of radius {@code wide} would also stand here. */
        boolean roomy(int i, double wide) {
            List<Gob> l = near.get(i);
            if (l == null)
                return true;
            Coord2d c = centre(i);
            double e = wide * 0.7071;
            Coord2d[] probe = {c.add(wide, 0), c.add(-wide, 0), c.add(0, wide), c.add(0, -wide),
                c.add(e, e), c.add(e, -e), c.add(-e, e), c.add(-e, -e)};
            for (Coord2d p : probe) {
                if (BotNav.occupied(l, p))
                    return false;
            }
            return true;
        }

        /** Rock faces, cave mouths, deep water - and shallow water when the bots are keeping dry. */
        boolean groundAt(Coord2d wc) {
            Coord tc = wc.floor(MCache.tilesz);
            Boolean known = ground.get(tc);
            if (known != null)
                return known;
            boolean ok = true;
            try {
                MCache map = gui.ui.sess.glob.map;
                Resource r = map.tilesetr(map.gettile(tc));
                String name = (r == null) ? null : r.name;
                ok = !(Map.isDeep(name) || Map.isImpassableGround(name)
                       || (Map.BLOCK_WATER && Map.isShallow(name)));
            } catch (Loading | NullPointerException e) {
                // Not loaded: say walkable, as the pathfinder does. It is local, so it will be soon.
            }
            ground.put(tc, ok);
            return ok;
        }

        /** The standable cell nearest {@code p}, or -1. We may be wedged on a cell that is not. */
        int seed(Coord2d p) {
            return seed(p, SEED_RADIUS);
        }

        /** The same, looking up to {@code radius} cells out - a wedged character is further off. */
        int seed(Coord2d p, int radius) {
            int c = cell(p);
            if (c < 0)
                return -1;
            if (free[c])
                return c;
            int best = -1;
            double bd = Double.MAX_VALUE;
            int px = c % w, py = c / w;
            for (int y = py - radius; y <= py + radius; y++) {
                for (int x = px - radius; x <= px + radius; x++) {
                    if ((x < 0) || (y < 0) || (x >= w) || (y >= h) || !free[y * w + x])
                        continue;
                    double d = centre(y * w + x).dist(p);
                    if (d < bd) {
                        bd = d;
                        best = y * w + x;
                    }
                }
            }
            return best;
        }

        /**
         * Floods the standable cells from {@code seed}, 8-connected with no corner cutting - a
         * diagonal step between two blocked cells is the same slip through a gap as a straight one.
         *
         * @return each cell's parent on the way back to the seed, -1 if unreachable, itself for the seed.
         */
        int[] flood(int seed) {
            int[] parent = new int[n];
            java.util.Arrays.fill(parent, -1);
            parent[seed] = seed;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(seed);
            while (!q.isEmpty()) {
                int i = q.poll();
                int x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((dx == 0) && (dy == 0))
                            continue;
                        int nx = x + dx, ny = y + dy;
                        if ((nx < 0) || (ny < 0) || (nx >= w) || (ny >= h))
                            continue;
                        int j = ny * w + nx;
                        if (!free[j] || (parent[j] != -1))
                            continue;
                        if ((dx != 0) && (dy != 0) && (!free[y * w + nx] || !free[ny * w + x]))
                            continue;
                        parent[j] = i;
                        q.add(j);
                    }
                }
            }
            return parent;
        }

        /**
         * Every cell reachable from {@code from} or from the grid's edge - "the outside" - so a
         * region that stays connected to either counts as not cut off.
         */
        boolean[] outside(Coord2d from) {
            boolean[] seen = new boolean[n];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            int s = seed(from);
            if (s >= 0) {
                seen[s] = true;
                q.add(s);
            }
            for (int i = 0; i < n; i++) {
                int x = i % w, y = i / w;
                if (free[i] && !seen[i] && ((x == 0) || (y == 0) || (x == w - 1) || (y == h - 1))) {
                    seen[i] = true;
                    q.add(i);
                }
            }
            while (!q.isEmpty()) {
                int i = q.poll();
                int x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((dx == 0) && (dy == 0))
                            continue;
                        int nx = x + dx, ny = y + dy;
                        if ((nx < 0) || (ny < 0) || (nx >= w) || (ny >= h))
                            continue;
                        int j = ny * w + nx;
                        if (!free[j] || seen[j])
                            continue;
                        if ((dx != 0) && (dy != 0) && (!free[y * w + nx] || !free[ny * w + x]))
                            continue;
                        seen[j] = true;
                        q.add(j);
                    }
                }
            }
            return seen;
        }

        /** The free cells one step from {@code i}, with no corner cutting - the flood's own rule. */
        List<Integer> steps8(int i) {
            List<Integer> out = new ArrayList<>(8);
            int x = i % w, y = i / w;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx == 0) && (dy == 0))
                        continue;
                    int nx = x + dx, ny = y + dy;
                    if ((nx < 0) || (ny < 0) || (nx >= w) || (ny >= h))
                        continue;
                    int j = ny * w + nx;
                    if (!free[j])
                        continue;
                    if ((dx != 0) && (dy != 0) && (!free[y * w + nx] || !free[ny * w + x]))
                        continue;
                    out.add(j);
                }
            }
            return out;
        }

        int steps(int[] parent, int i) {
            int s = 0;
            while ((parent[i] != i) && (s < n)) {
                i = parent[i];
                s++;
            }
            return s;
        }

        /**
         * Walks the straight line from {@code from} towards the target's centre, the line the server
         * takes on a right-click. Returns {gap to the target's edge, 0 if nothing else is on the way
         * else 1}, or null when the target's box is never met.
         */
        double[] ray(Coord2d from) {
            double len = from.dist(target.rc);
            if (len < 0.5)
                return null;
            Coord2d dir = target.rc.sub(from).mul(1.0 / len);
            Coord2d side = new Coord2d(-dir.y, dir.x).mul(clearance);
            boolean blocked = false;
            for (double d = 0; d <= len; d += 1.0) {
                Coord2d p = from.add(dir.mul(d));
                if (BotNav.occupied(targetOnly, p))
                    return new double[] {d, blocked ? 1 : 0};
                if (!blocked) {
                    int c = cell(p);
                    List<Gob> l = (c < 0) ? null : near.get(c);
                    if (l != null) {
                        for (Gob s : l) {
                            if (s.id == target.id)
                                continue;
                            List<Gob> one = Collections.singletonList(s);
                            if (BotNav.occupied(one, p) || BotNav.occupied(one, p.add(side))
                                || BotNav.occupied(one, p.sub(side))) {
                                blocked = true;
                                break;
                            }
                        }
                    }
                }
            }
            return null;
        }

        /** The path from us to cell {@code to} as straight legs, each clear of every blocked cell. */
        List<Coord2d> legs(int[] parent, int seed, int to, Coord2d from) {
            List<Integer> chain = new ArrayList<>();
            for (int i = to; ; i = parent[i]) {
                chain.add(i);
                if (parent[i] == i)
                    break;
            }
            Collections.reverse(chain);
            List<Coord2d> out = new ArrayList<>();
            Coord2d at = from;
            int k = 0;
            // The first leg may start off-grid (we may be wedged on a blocked cell), so it goes to the seed.
            if (cell(from) != seed) {
                at = centre(seed);
                out.add(at);
            }
            while (k < chain.size() - 1) {
                int far = k + 1;
                for (int j = chain.size() - 1; j > k + 1; j--) {
                    if (clearLine(at, centre(chain.get(j)))) {
                        far = j;
                        break;
                    }
                }
                at = centre(chain.get(far));
                out.add(at);
                k = far;
            }
            return out;
        }

        boolean clearLine(Coord2d a, Coord2d b) {
            double len = a.dist(b);
            for (double d = 0; d <= len; d += cell * 0.5) {
                int c = cell(a.add(b.sub(a).mul(d / Math.max(len, 1e-6))));
                if ((c < 0) || !free[c])
                    return false;
            }
            return true;
        }
    }
}
