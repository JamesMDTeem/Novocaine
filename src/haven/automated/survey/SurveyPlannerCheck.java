package haven.automated.survey;

import haven.Area;
import haven.Coord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Offline verification for the planner, run as a plain main() because this tree has no test
 * framework and adding one would be a bigger change than the thing being tested.
 *
 * Every assertion runs against tools/survey-fixtures/grid--1000--1000.tsv - a real 101x101 grid
 * captured from a live session with ':surv dump' - so the numbers below are what the game actually
 * produced rather than invented fixtures. Exits non-zero if anything fails.
 *
 * <p>Run it from the repo root, against whatever the last build produced:
 *
 * <pre>
 *     java -cp build/classes haven.automated.survey.SurveyPlannerCheck
 * </pre>
 */
public class SurveyPlannerCheck {
    private static final Path FIXTURE = Paths.get("tools", "survey-fixtures", "grid--1000--1000.tsv");
    private static int failures = 0;

    public static void main(String[] args) {
        Heights hs = Heights.load(FIXTURE);
        check(hs.w == 101 && hs.h == 101, "fixture is 101x101, got " + hs.w + "x" + hs.h);
        check(hs.missing == 0, "fixture has no missing vertices");
        eq(hs.mean(), 114.1103, 1e-3, "fixture mean");
        eq(hs.dig(hs.mean()), 151895.73, 1.0, "total dig at the mean");

        // The prefix-sum shortcut must agree with the obvious loop, or every later number is wrong.
        double brute = 0;
        for (int y = 10; y <= 40; y++)
            for (int x = 5; x <= 35; x++)
                brute += hs.z[y * hs.w + x];
        eq(hs.sum(5, 10, 35, 40), brute, 1e-6, "sum() against a brute-force loop");

        // Two sources, two sinks, deliberately asymmetric: the cheap pairing is 0->2 and 1->3 at
        // cost 1 each, total 20, not the crossed one at cost 3 each.
        MinCostFlow f = new MinCostFlow(6, 8);
        int fs = 4, ft = 5;
        f.edge(fs, 0, 10, 0);
        f.edge(fs, 1, 10, 0);
        f.edge(0, 2, MinCostFlow.INF, 1);
        f.edge(0, 3, MinCostFlow.INF, 3);
        f.edge(1, 2, MinCostFlow.INF, 3);
        f.edge(1, 3, MinCostFlow.INF, 1);
        f.edge(2, ft, 10, 0);
        f.edge(3, ft, 10, 0);
        eq(f.mincost(fs, ft), 20.0, 1e-9, "min-cost flow picks the cheap pairing");
        eq(f.flowOn(2), 10.0, 1e-9, "flowOn reports the cheap 0->2 edge carrying everything");
        eq(f.flowOn(3), 0.0, 1e-9, "flowOn reports the dear 0->3 edge carrying nothing");

        // A capacity bottleneck forces the expensive route for the remainder.
        MinCostFlow g = new MinCostFlow(4, 4);
        g.edge(2, 0, 10, 0);
        g.edge(0, 1, 4, 1);
        g.edge(0, 1, MinCostFlow.INF, 5);
        g.edge(1, 3, 10, 0);
        eq(g.mincost(2, 3), 4 * 1 + 6 * 5, 1e-9, "cheap edge saturates, remainder pays the dear one");

        int[] ev = SurveyPlanner.even(100, 31);
        check(java.util.Arrays.equals(ev, new int[] {0, 25, 50, 75, 100}),
            "even(100,31) splits into four, got " + java.util.Arrays.toString(ev));
        check(SurveyPlanner.valid(ev, 31), "even cuts are within the cap");
        check(!SurveyPlanner.valid(new int[] {0, 32, 100}, 31), "a 32-wide part is rejected");

        double t = hs.mean();
        double[] sd = SurveyPlanner.nets(hs, ev, ev, t);
        check(sd.length == 16, "four by four cuts give sixteen surveys, got " + sd.length);
        double netTotal = 0;
        for (double v : sd)
            netTotal += v;
        eq(netTotal, 0.0, 1e-6, "disjoint nets sum to zero");

        // Known values for this fixture under the even split, from the verified in-game run.
        eq(SurveyPlanner.carried(sd), 121332, 50, "soil carried under the even split");
        eq(SurveyPlanner.hops(sd, 4, 4), 378195, 200, "unit-hops under the even split");

        // With the walk free, cost is exactly one pickup per carried unit.
        eq(SurveyPlanner.carrying(sd, 4, 4, 0.0), SurveyPlanner.carried(sd), 1e-6,
            "at w=0 carrying cost is one per carried unit");
        // And at w=1 the surplus over that is exactly the hop count.
        eq(SurveyPlanner.carrying(sd, 4, 4, 1.0) - SurveyPlanner.carried(sd),
            SurveyPlanner.hops(sd, 4, 4), 1.0, "at w=1 the surplus over carried() is the hop count");

        SurveyPlan plan = SurveyPlanner.compute(hs, 31, 1.0);
        eq(plan.targetZ, 114.1103, 1e-3, "plan target is the region mean");
        check(plan.surveys.size() == 16, "sixteen surveys, got " + plan.surveys.size());
        for (SurveyPlan.SurveySpec sp : plan.surveys)
            check(sp.tiles.br.x - sp.tiles.ul.x <= 31 && sp.tiles.br.y - sp.tiles.ul.y <= 31,
                "survey " + sp.index + " is within the 31-tile cap");
        double planNet = 0;
        for (SurveyPlan.SurveySpec sp : plan.surveys)
            planNet += sp.net;
        eq(planNet, 0.0, 1e-6, "plan nets sum to zero");
        eq(plan.targetDz(1.0f), 114, 0, "targetDz rounds the mean at gran=1");

        double[] planSd = new double[plan.surveys.size()];
        for (SurveyPlan.SurveySpec sp : plan.surveys)
            planSd[sp.index] = sp.net;
        check(SurveyPlanner.hops(planSd, 4, 4) < SurveyPlanner.hops(sd, 4, 4),
            "the search improves on the even split it starts from");
        check(SurveyPlanner.hops(planSd, 4, 4) < 310000,
            "the search lands near the known optimum, got " + SurveyPlanner.hops(planSd, 4, 4));

        check(!plan.transfers.isEmpty(), "the plan pairs surpluses with deficits");
        double movedTotal = 0;
        for (SurveyPlan.Transfer tr : plan.transfers) {
            movedTotal += tr.amount;
            check(tr.amount > 0, "every transfer moves something");
            check(plan.surveys.get(tr.from).net < 0, "transfers leave a survey with a surplus");
            check(plan.surveys.get(tr.to).net > 0, "transfers arrive at a survey that needs soil");
            check(plan.surveys.get(tr.from).tiles.contains(tr.stockpile),
                "the stockpile sits inside the survey that produces the soil");
        }
        eq(movedTotal, SurveyPlanner.carried(planSd), 1.0, "transfers move exactly the carried total");

        java.util.List<SurveyPlan.SurveySpec> ord = plan.order();
        check(ord.size() == plan.surveys.size(), "the order covers every survey");
        boolean seenDeficit = false;
        for (SurveyPlan.SurveySpec sp : ord) {
            if (sp.net > 0)
                seenDeficit = true;
            else
                check(!seenDeficit, "no surplus survey is scheduled after a deficit one");
        }

        String json = SurveyPlanStore.toJson(plan);
        SurveyPlan back = SurveyPlanStore.fromJson(json);
        eq(back.targetZ, plan.targetZ, 1e-9, "target survives a round trip");
        check(back.region.equals(plan.region), "the region survives a round trip");
        check(back.surveys.size() == plan.surveys.size(), "every survey survives a round trip");
        check(back.transfers.size() == plan.transfers.size(), "every transfer survives a round trip");
        check(back.surveys.get(3).tiles.equals(plan.surveys.get(3).tiles),
            "survey rectangles survive a round trip");
        eq(back.surveys.get(3).net, plan.surveys.get(3).net, 1e-6, "survey nets survive a round trip");
        check(back.transfers.get(0).stockpile.equals(plan.transfers.get(0).stockpile),
            "stockpile hints survive a round trip");
        check(back.transfers.get(0).from == plan.transfers.get(0).from
            && back.transfers.get(0).to == plan.transfers.get(0).to,
            "transfer endpoints survive a round trip");

        SurveyPlan rect = rectangular(hs);
        corners();
        ordering(plan);
        reachZones(plan);
        rebasing(plan);
        doneMarks(plan, rect);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * A region that is neither square nor grid-aligned.
     *
     * The planner took one span for both axes for as long as a plan was always exactly one grid,
     * which is a bug you cannot see while every region is 100x100. Cropping the fixture to 60x100
     * tiles at an arbitrary origin is the cheapest thing that would have caught it.
     */
    private static SurveyPlan rectangular(Heights hs) {
        int rw = 61, rh = 101;
        double[] rz = new double[rw * rh];
        for (int y = 0; y < rh; y++)
            System.arraycopy(hs.z, y * hs.w, rz, y * rw, rw);
        Coord origin = Coord.of(-987, 1043);
        Heights rect = new Heights(origin, rw, rh, rz, 0);

        SurveyPlan p = SurveyPlanner.compute(rect, 31, 1.0);
        check(p.region.equals(Area.corn(origin, origin.add(rw - 1, rh - 1))),
            "a 60x100 region plans as 60x100, got " + p.region);
        // 60 tiles needs two parts, 100 needs four; anything else means a span was reused.
        check(p.surveys.size() == 8, "60x100 at a 31 cap is eight surveys, got " + p.surveys.size());

        int area = 0;
        for (SurveyPlan.SurveySpec s : p.surveys) {
            Coord sz = s.tiles.sz();
            check(sz.x >= 1 && sz.x <= 31 && sz.y >= 1 && sz.y <= 31,
                "survey " + s.index + " is " + sz + ", outside the 31-tile cap");
            check(p.region.contains(s.tiles), "survey " + s.index + " sits inside the region");
            area += sz.x * sz.y;
        }
        Coord rsz = p.region.sz();
        check(area == rsz.x * rsz.y,
            "the surveys tile the region exactly - covered " + area + " of " + (rsz.x * rsz.y));

        double net = 0;
        for (SurveyPlan.SurveySpec s : p.surveys)
            net += s.net;
        eq(net, 0.0, 1e-6, "a rectangular region's nets sum to zero");
        return p;
    }

    /**
     * Marking corners two at a time, over several selections.
     *
     * A complete pair is a FINISHED selection, not one corner short of a new one. Getting that
     * backwards meant the first press of a fresh pair combined where the player was standing with
     * a corner set for a previous region - and since the second corner plans automatically, it
     * planned that nonsense region immediately, on what the player experienced as the FIRST press.
     */
    private static void corners() {
        Coord p1 = Coord.of(10, 10), p2 = Coord.of(40, 50), p3 = Coord.of(100, 7);
        Corners c = new Corners();

        c.press(true, p1);
        check(p1.equals(c.a) && c.b == null, "pressing A first sets only A");
        check(c.partial() && !c.complete(), "one corner is a selection part-way through");
        check(c.region() == null, "an incomplete pair describes no region");

        c.press(false, p2);
        check(p1.equals(c.a) && p2.equals(c.b), "pressing B then completes the pair");
        check(c.complete() && !c.partial(), "two corners is a finished selection");
        // Inclusive of BOTH marked tiles: standing on a corner means that tile is in the region.
        check(c.region().equals(Area.corn(Coord.of(10, 10), Coord.of(41, 51))),
            "the region covers both marked tiles, got " + c.region());

        // Pressing A against a complete pair starts over rather than re-pairing with the old B.
        c.press(true, p3);
        check(p3.equals(c.a) && c.b == null,
            "pressing A on a complete pair starts a new selection, got A=" + c.a + " B=" + c.b);

        // And the same from the B side.
        Corners d = new Corners();
        d.press(true, p1);
        d.press(false, p2);
        d.press(false, p3);
        check(d.a == null && p3.equals(d.b),
            "pressing B on a complete pair starts a new selection, got A=" + d.a + " B=" + d.b);

        // Pressing the same corner twice corrects it instead of completing anything.
        Corners e = new Corners();
        e.press(true, p1);
        e.press(true, p2);
        check(p2.equals(e.a) && e.b == null, "pressing A twice moves A and leaves B unset");

        // B first is just as valid as A first, and the corners may be marked in any order.
        Corners f = new Corners();
        f.press(false, p2);
        f.press(true, p1);
        check(p1.equals(f.a) && p2.equals(f.b), "B first then A completes a pair too");
        check(f.region().equals(c0(p1, p2)), "and describes the same region either way");

        // A pair marked from the opposite diagonal describes the same rectangle.
        Corners g = new Corners();
        g.press(true, Coord.of(40, 10));
        g.press(false, Coord.of(10, 50));
        check(g.region().equals(c0(p1, p2)),
            "the other diagonal gives the same region, got " + g.region());

        Corners h = new Corners();
        h.press(true, p1);
        h.clear();
        check(h.a == null && h.b == null && !h.partial(), "clear drops both marks");
    }

    /** The region two corner tiles describe, both inclusive. */
    private static Area c0(Coord a, Coord b) {
        return Area.corn(a.min(b), a.max(b).add(1, 1));
    }

    /**
     * A stockpile band really is ground both surveys can reach.
     *
     * This is the whole claim the violet highlight makes, and it is the kind of claim that is easy
     * to get subtly wrong by an off-by-one and impossible to notice in game - a band one tile too
     * far over still looks perfectly reasonable, and the soil simply cannot be picked up from the
     * other side. So it is checked against the reach rule directly: every tile of every band must
     * be inside the survey or within one tile of its boundary, for BOTH surveys.
     */
    private static void reachZones(SurveyPlan plan) {
        int adjacent = 0;
        for (SurveyPlan.Transfer t : plan.transfers) {
            Area from = plan.surveys.get(t.from).tiles, to = plan.surveys.get(t.to).tiles;
            Area z = plan.reachZone(t);
            if (z == null) {
                check(!touching(from, to),
                    "surveys " + t.from + " and " + t.to + " touch but got no band");
                continue;
            }
            adjacent++;
            check(z.sz().x > 0 && z.sz().y > 0, "a band is not empty");
            for (Coord c : z) {
                check(reaches(from, c),
                    "band tile " + c + " is out of survey " + t.from + "'s reach");
                check(reaches(to, c),
                    "band tile " + c + " is out of survey " + t.to + "'s reach");
            }
        }
        check(adjacent > 0, "at least some transfers are between neighbours with a shared band");

        // Two neighbours sharing a vertical edge: the band is the two columns either side of it.
        Area a = Area.corn(Coord.of(0, 0), Coord.of(10, 10));
        Area b = Area.corn(Coord.of(10, 0), Coord.of(20, 10));
        Area z = SurveyPlan.reachZone(a, b);
        check(z != null && z.equals(Area.corn(Coord.of(9, -1), Coord.of(11, 11))),
            "neighbours share a two-wide band along their edge, got " + z);

        // A gap of one tile still leaves a band, because reach is one tile outside each.
        Area c = Area.corn(Coord.of(11, 0), Coord.of(21, 10));
        check(SurveyPlan.reachZone(a, c) != null, "a one-tile gap is still within reach of both");

        // Two tiles apart is out of reach of one another, and must produce nothing.
        Area d = Area.corn(Coord.of(12, 0), Coord.of(22, 10));
        check(SurveyPlan.reachZone(a, d) == null,
            "surveys two tiles apart share no ground, got " + SurveyPlan.reachZone(a, d));
    }

    /** Whether a tile is inside a survey or within its one-tile reach. */
    private static boolean reaches(Area survey, Coord c) {
        return c.x >= survey.ul.x - SurveyPlan.REACH && c.x < survey.br.x + SurveyPlan.REACH
            && c.y >= survey.ul.y - SurveyPlan.REACH && c.y < survey.br.y + SurveyPlan.REACH;
    }

    /** Whether two rectangles are near enough that a stockpile could serve both. */
    private static boolean touching(Area a, Area b) {
        return a.ul.x - SurveyPlan.REACH < b.br.x + SurveyPlan.REACH
            && b.ul.x - SurveyPlan.REACH < a.br.x + SurveyPlan.REACH
            && a.ul.y - SurveyPlan.REACH < b.br.y + SurveyPlan.REACH
            && b.ul.y - SurveyPlan.REACH < a.br.y + SurveyPlan.REACH;
    }

    /**
     * Translating a plan into another session's coordinates changes coordinates and nothing else.
     *
     * The case this stands in for is a relog: absolute tile coordinates are relative to a floating
     * map origin, so a plan read off disk describes the right rectangles at the wrong numbers, and
     * the whole overlay lands on ground the player has never seen.
     */
    private static void rebasing(SurveyPlan plan) {
        Coord d = Coord.of(517, -283);
        SurveyPlan moved = plan.rebase(plan.region.ul.add(d));

        check(moved.region.equals(Area.corn(plan.region.ul.add(d), plan.region.br.add(d))),
            "the region translates, got " + moved.region);
        check(moved.surveys.size() == plan.surveys.size(), "every survey survives a rebase");
        eq(moved.targetZ, plan.targetZ, 1e-9, "the target level is untouched by a rebase");

        for (int i = 0; i < plan.surveys.size(); i++) {
            SurveyPlan.SurveySpec a = plan.surveys.get(i), b = moved.surveys.get(i);
            check(b.index == a.index, "survey indices are untouched by a rebase");
            check(b.tiles.equals(Area.corn(a.tiles.ul.add(d), a.tiles.br.add(d))),
                "survey " + a.index + " translates by exactly the offset");
            eq(b.net, a.net, 1e-9, "survey " + a.index + "'s balance is untouched by a rebase");
            check(moved.step(a.index) == plan.step(a.index), "the work order is untouched");
        }
        for (int i = 0; i < plan.transfers.size(); i++) {
            SurveyPlan.Transfer a = plan.transfers.get(i), b = moved.transfers.get(i);
            check(b.stockpile.equals(a.stockpile.add(d)),
                "stockpile hints move with their surveys - a hint that stayed put would point at "
                + "ground in the old frame");
            check(moved.surveys.get(b.from).tiles.contains(b.stockpile),
                "and still land inside the survey that produces the soil");
        }
        check(plan.rebase(plan.region.ul) == plan, "rebasing to where it already is changes nothing");
    }

    /** Every survey has exactly one place in the work order, and the order is 1..n. */
    private static void ordering(SurveyPlan plan) {
        boolean[] seen = new boolean[plan.surveys.size() + 1];
        for (SurveyPlan.SurveySpec s : plan.surveys) {
            int st = plan.step(s.index);
            check(st >= 1 && st <= plan.surveys.size(),
                "survey " + s.index + " has step " + st + ", outside 1.." + plan.surveys.size());
            if (st >= 1 && st < seen.length) {
                check(!seen[st], "step " + st + " is claimed by two surveys");
                seen[st] = true;
            }
        }
        java.util.List<SurveyPlan.SurveySpec> ord = plan.order();
        for (int i = 0; i < ord.size(); i++)
            check(plan.step(ord.get(i).index) == i + 1,
                "step " + plan.step(ord.get(i).index) + " disagrees with order() position " + (i + 1));
        check(plan.step(-1) == 0, "a survey this plan does not contain has no step");
    }

    /**
     * Done-marks round-trip, and do not leak across a replan.
     *
     * The second half is the one that matters. A done-mark is permanent where a claim expires, so
     * a mark surviving into a plan whose rectangles have moved would tell a crew that work nobody
     * has done is finished - which is worse than showing no progress at all.
     */
    private static void doneMarks(SurveyPlan plan, SurveyPlan other) {
        try {
            Path dir = Files.createTempDirectory("surveydone");
            System.setProperty("novocaine.surveydonefile", dir.resolve("done.json").toString());
        } catch (IOException e) {
            check(false, "could not make a temp directory for the done-mark checks: " + e);
            return;
        }
        check(SurveyPlanStore.done(plan).isEmpty(), "nothing is marked done to begin with");

        SurveyPlanStore.setDone(plan, 3, true);
        SurveyPlanStore.setDone(plan, 7, true);
        Set<Integer> got = SurveyPlanStore.done(plan);
        check(got.contains(3) && got.contains(7) && got.size() == 2,
            "both marks are recorded, got " + got);

        SurveyPlanStore.setDone(plan, 3, false);
        got = SurveyPlanStore.done(plan);
        check(got.contains(7) && !got.contains(3), "unmarking takes one off and leaves the rest");

        /* The relog case, and the reason the stamp holds no absolute coordinates. The same plan
         * restated in another session's numbers is the same work, so a crew's whole progress
         * record has to come back with it. Keyed on coordinates this returned empty and the plan
         * reported nothing done. */
        SurveyPlan moved = plan.rebase(plan.region.ul.add(517, -283));
        check(SurveyPlanStore.done(moved).contains(7),
            "marks survive a rebase into another session's coordinates");

        // A genuinely different partition must not inherit them.
        check(SurveyPlanStore.done(other).isEmpty(),
            "marks do not carry over to a plan with different rectangles");
        check(SurveyPlanStore.done(plan).contains(7),
            "and the original plan's marks are still there");
    }

    static void check(boolean cond, String what) {
        if (!cond) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    static void eq(double got, double want, double tol, String what) {
        if (!(Math.abs(got - want) <= tol)) {
            System.out.println("FAIL: " + what + " - got " + got + ", wanted " + want + " +/- " + tol);
            failures++;
        }
    }
}
