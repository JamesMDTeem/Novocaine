package haven.automated.survey;

import java.nio.file.Path;
import java.nio.file.Paths;

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
        check(back.ul.equals(plan.ul), "the region origin survives a round trip");
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

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
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
