package haven.automated.survey;

import haven.Area;
import haven.Coord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Works out which surveys to draw to level a region flat, and what it will cost to do it.
 *
 * The whole class is pure computation over a {@link Heights} field - no {@code GameUI}, no widgets,
 * no resource loading - so it can be run against a captured grid with no game attached. That is
 * what {@link SurveyPlannerCheck} does, and it is the only reason the numbers here are trustworthy:
 * they are checked against terrain the game actually produced.
 *
 * <h2>Why the target level is not a free variable</h2>
 *
 * A survey places dug soil into low spots inside its own area, and the survey window's "units of
 * soil required / left over" is exactly linear in a flat target level, so a single survey is
 * cheapest at its own mean. Extend that to a whole region that must finish as ONE flat plane and
 * the level stops being a choice: no soil comes from outside, so the plane must sit at the region's
 * own mean. That fixes the total dig - on the measured grid, some 151,896 units - and no partition
 * can reduce it by a single unit.
 *
 * <p>It also means every survey gets the SAME target, the region mean. Levelling each survey to its
 * own mean - which is what the window's "Ground plane" button does, and what a freshly placed
 * survey already sits at - produces a terraced result, not a flat one.
 *
 * <h2>What is left to optimise</h2>
 *
 * Only the carrying. Soil that finds its low spot inside its own survey is placed by the survey
 * itself; soil that cannot has to be stockpiled, carried and dropped. So with per-survey net
 * {@code sd_j}, the soil that must be carried is the sum of the positive nets, and moving a unit
 * from survey i to survey j costs {@code 1 + w * hops(i, j)} - one pickup plus the walk. Minimising
 * that is a transportation problem, which {@link #carrying} solves exactly.
 *
 * <p>{@code w} is the exchange rate between a pickup and a survey-hop of walking, and it is a feel
 * for the game rather than anything derivable here. It was swept rather than assumed: on real
 * terrain the answer converges by {@code w = 1} and stops moving, so the plan is insensitive to it
 * across the whole plausible range. Only "walking is free" gives a different partition.
 */
public class SurveyPlanner {

    /**
     * Per-survey net soil over a DISJOINT assignment of vertices: positive needs soil, negative has
     * a surplus, and the whole array sums to zero.
     *
     * Disjoint on purpose, and this is the subtle part. A survey's own vertex area shares its
     * boundary row and column with its neighbour - an NxN tile survey owns (N+1)x(N+1) vertices -
     * so summing each survey over its own area counts those vertices twice and the nets stop
     * balancing. For deciding what has to be carried where, every vertex must belong to exactly one
     * survey, so each block takes its left and top edge and the last block in each direction takes
     * the far edge.
     *
     * <p>Indexed row-major, {@code j * nx + i}, matching the order {@link SurveyPlan#surveys} is
     * built in.
     */
    public static double[] nets(Heights hs, int[] xc, int[] yc, double t) {
        int nx = xc.length - 1, ny = yc.length - 1;
        double[] sd = new double[nx * ny];
        for (int j = 0; j < ny; j++) {
            int y0 = yc[j], y1 = (j + 1 < ny) ? yc[j + 1] - 1 : hs.h - 1;
            for (int i = 0; i < nx; i++) {
                int x0 = xc[i], x1 = (i + 1 < nx) ? xc[i + 1] - 1 : hs.w - 1;
                double n = (y1 - y0 + 1) * (double) (x1 - x0 + 1);
                sd[j * nx + i] = n * t - hs.sum(x0, y0, x1, y1);
            }
        }
        return sd;
    }

    /** Soil that cannot be placed inside its own survey, and so has to be carried. */
    public static double carried(double[] sd) {
        double acc = 0;
        for (double v : sd)
            if (v > 0)
                acc += v;
        return acc;
    }

    /**
     * The least it can cost to move every surplus into the surveys that need it.
     *
     * One unit moved from survey i to survey j costs {@code 1 + w * hops(i, j)}: one pickup, plus
     * the walk priced at {@code w} per survey-hop. At {@code w = 0} every routing costs the same
     * and this reduces to {@link #carried}; as {@code w} grows it starts trading extra handling for
     * shorter walks.
     */
    public static double carrying(double[] sd, int nx, int ny, double w) {
        int n = nx * ny;
        int s = n, t = n + 1;
        MinCostFlow f = new MinCostFlow(n + 2, n * n + 2 * n + 8);
        for (int a = 0; a < n; a++) {
            if (sd[a] < 0) {
                f.edge(s, a, -sd[a], 0);
                for (int b = 0; b < n; b++) {
                    if (sd[b] > 0) {
                        int hops = Math.abs(a % nx - b % nx) + Math.abs(a / nx - b / nx);
                        f.edge(a, b, MinCostFlow.INF, 1 + w * hops);
                    }
                }
            } else if (sd[a] > 0) {
                f.edge(a, t, sd[a], 0);
            }
        }
        return f.mincost(s, t);
    }

    /**
     * Hop-optimal total unit-hops for a partition.
     *
     * The total flow is fixed once the partition is, so minimising {@code 1 + 1*hops} per unit
     * minimises the hop count exactly - the constant term cannot change the routing. That makes the
     * hop count readable off a single solve.
     */
    public static double hops(double[] sd, int nx, int ny) {
        return carrying(sd, nx, ny, 1.0) - carried(sd);
    }

    /** Cuts splitting {@code span} tiles into the fewest equal parts that each fit {@code max}. */
    public static int[] even(int span, int max) {
        int parts = (span + max - 1) / max;
        int[] c = new int[parts + 1];
        for (int i = 0; i <= parts; i++)
            c[i] = (int) Math.round(i * span / (double) parts);
        return c;
    }

    /** Whether every part is at least one tile and no wider than the game's survey cap. */
    public static boolean valid(int[] c, int max) {
        for (int i = 0; i + 1 < c.length; i++) {
            int d = c[i + 1] - c[i];
            if (d < 1 || d > max)
                return false;
        }
        return true;
    }

    /**
     * The best partition a hill-climb can find for one exchange rate; returns {xcuts, ycuts}.
     *
     * Starts from the even split and also from random restarts, because the objective is not
     * convex in the cut positions - a partition can be locally best and still be well short. The
     * seed is fixed so a plan is reproducible: the same terrain gives the same answer twice, which
     * is what lets {@link SurveyPlannerCheck} assert a bound on the result at all.
     */
    public static int[][] optimise(Heights hs, int maxSide, double t, double w) {
        int span = hs.w - 1;
        int[] bx = even(span, maxSide), by = even(span, maxSide);
        double best = carrying(nets(hs, bx, by, t), bx.length - 1, by.length - 1, w);
        Random rnd = new Random(12345);
        for (int restart = 0; restart < 24; restart++) {
            int[] x = (restart == 0) ? even(span, maxSide) : random(span, maxSide, rnd);
            int[] y = (restart == 0) ? even(span, maxSide) : random(span, maxSide, rnd);
            double cur = carrying(nets(hs, x, y, t), x.length - 1, y.length - 1, w);
            boolean moved = true;
            while (moved) {
                moved = false;
                for (int axis = 0; axis < 2; axis++) {
                    int[] c = (axis == 0) ? x : y;
                    for (int i = 1; i < c.length - 1; i++) {
                        int was = c[i];
                        for (int d = -4; d <= 4; d++) {
                            if (d == 0)
                                continue;
                            c[i] = was + d;
                            if (!valid(c, maxSide)) {
                                c[i] = was;
                                continue;
                            }
                            double v = carrying(nets(hs, x, y, t), x.length - 1, y.length - 1, w);
                            if (v < cur - 1e-6) {
                                cur = v;
                                was = c[i];
                                moved = true;
                            }
                            c[i] = was;
                        }
                    }
                }
            }
            if (cur < best - 1e-6) {
                best = cur;
                bx = x.clone();
                by = y.clone();
            }
        }
        return new int[][] {bx, by};
    }

    /** A random valid cut vector, for a restart that is not the even split. */
    private static int[] random(int span, int max, Random rnd) {
        int parts = (span + max - 1) / max;
        while (true) {
            int[] c = new int[parts + 1];
            c[0] = 0;
            c[parts] = span;
            for (int i = 1; i < parts; i++)
                c[i] = 1 + rnd.nextInt(span - 1);
            Arrays.sort(c);
            if (valid(c, max))
                return c;
        }
    }

    /**
     * The whole plan for a region: one target level, the surveys to draw, and their balances.
     *
     * The target level is FROZEN here and never recomputed. Levelling conserves soil in principle,
     * so the mean should not move as work proceeds - but rounding, and whatever the game gains or
     * loses for its own reasons, will drift it. A plan whose target shifts under the crew is worse
     * than one that is slightly stale: the last survey has to mean the same thing as the first, or
     * the region does not come out flat.
     */
    public static SurveyPlan compute(Heights hs, int maxSide, double w) {
        double t = hs.mean();
        int[][] cuts = optimise(hs, maxSide, t, w);
        int[] xc = cuts[0], yc = cuts[1];
        double[] sd = nets(hs, xc, yc, t);
        int nx = xc.length - 1, ny = yc.length - 1;
        List<SurveyPlan.SurveySpec> surveys = new ArrayList<>(nx * ny);
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                Area tiles = Area.corn(hs.ul.add(xc[i], yc[j]), hs.ul.add(xc[i + 1], yc[j + 1]));
                surveys.add(new SurveyPlan.SurveySpec(j * nx + i, tiles, sd[j * nx + i]));
            }
        }
        return new SurveyPlan(hs.ul, t, surveys, transfers(sd, nx, ny, w, surveys));
    }

    /**
     * Reads the pairing back out of the solved flow.
     *
     * {@link #carrying} only ever returns a total, which is all the partition search needs. The
     * work list needs more than that: which surplus actually feeds which shortfall, so a character
     * knows where to take the soil. This rebuilds the same graph, solves it again and reads each
     * edge's flow.
     *
     * <p>Edge indices are recorded as the graph is built because {@link MinCostFlow} numbers edges
     * in the order they were added and nothing else identifies them afterwards.
     */
    private static List<SurveyPlan.Transfer> transfers(double[] sd, int nx, int ny, double w,
                                                       List<SurveyPlan.SurveySpec> surveys) {
        int n = nx * ny;
        int s = n, t = n + 1;
        MinCostFlow f = new MinCostFlow(n + 2, n * n + 2 * n + 8);
        List<int[]> pairs = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            if (sd[a] < 0) {
                f.edge(s, a, -sd[a], 0);
                for (int b = 0; b < n; b++) {
                    if (sd[b] > 0) {
                        int hops = Math.abs(a % nx - b % nx) + Math.abs(a / nx - b / nx);
                        pairs.add(new int[] {f.edgeCount(), a, b});
                        f.edge(a, b, MinCostFlow.INF, 1 + w * hops);
                    }
                }
            } else if (sd[a] > 0) {
                f.edge(a, t, sd[a], 0);
            }
        }
        f.mincost(s, t);

        List<SurveyPlan.Transfer> out = new ArrayList<>();
        for (int[] pr : pairs) {
            double amount = f.flowOn(pr[0]);
            /* The graph offers every surplus a route to every shortfall; most carry nothing, and a
             * list full of zero-unit trips would be noise in the work list. */
            if (amount < 1e-6)
                continue;
            out.add(new SurveyPlan.Transfer(pr[1], pr[2], amount,
                edgeTile(surveys.get(pr[1]).tiles, surveys.get(pr[2]).tiles)));
        }
        return out;
    }

    /**
     * Where to pile the soil: inside {@code from}, as near {@code to} as its own boundary allows.
     *
     * Putting it against that edge is what makes the relay work. The next survey can be drawn to
     * take in the strip holding the pile, and its own levelling then consumes it - nobody has to
     * carry that soil a second time.
     */
    private static Coord edgeTile(Area from, Area to) {
        Coord c = to.ul.add(to.br).div(2);
        return Coord.of(Math.max(from.ul.x, Math.min(from.br.x - 1, c.x)),
                        Math.max(from.ul.y, Math.min(from.br.y - 1, c.y)));
    }
}
