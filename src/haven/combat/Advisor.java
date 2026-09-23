package haven.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * What to throw NOW, given the fight as it actually stands.
 *
 * THE OPTIMIZER RETURNS A SCRIPT AND A FIGHT IS NOT A SCRIPT. {@link Optimizer#search} plans
 * a whole sequence from one starting state, which is the right thing to compare plans with
 * and the wrong thing to execute. Openings decay by a rule this corpus cannot see, the
 * opponent acts on its own clock, and a cooldown depends on an agility we only know as an
 * interval - so a forward run drifts, and the further into a plan the more it has drifted.
 * `replay.py` refuses to run a fight forward for exactly this reason and replays each move
 * against the state observed before it.
 *
 * So this re-plans. Every time we are ready to act it searches from the CURRENT state and
 * returns the first move of the plan it picks, which is the only move it is entitled to be
 * confident about. Drift stops accumulating because nothing is carried between decisions.
 *
 * Pure: no UI, no widgets, no game state beyond the two Combatants and the deck. An
 * executor drives it, and can be replaced without touching anything here.
 */
public final class Advisor {
    private Advisor() {}

    /** How a caller wants the frontier resolved into a single answer. */
    public enum Aim {
        /** Kill soonest, whatever it costs. */
        FASTEST,
        /** Take the least damage, however long it takes. */
        SAFEST,
        /** Kill soonest among the plans that stay inside a hitpoint budget. */
        BUDGET,
        /**
         * Leave the deepest lasting wound among the plans that win - the hard hitpoints a
         * plan takes, not the soft ones every win knocks down. Against a person it is the
         * difference between a knockdown and a kill; ties go to the quicker plan.
         */
        WOUNDING,
        /**
         * Kill soonest while staying on our feet: the fastest plan whose damage fits the
         * budget - the hitpoints we can spare - and, when no plan does, the one that costs
         * least. This is the aim that reaches for a restoration: with hitpoints to spare it
         * is FASTEST, and as they run short the plans that close our openings are the only
         * ones left inside the budget. A NaN cost is an opponent whose damage is unknown, and
         * such a plan is judged on its speed alone.
         */
        SURVIVE
    }

    /**
     * The decision, and the reasoning that produced it, so a log can hold both.
     *
     * `move` is null when nothing is worth throwing - no legal move, or no plan that
     * satisfies the aim. A bot must be able to tell "wait" from "no idea", so `why` says
     * which.
     */
    public static final class Advice {
        public final Move move;
        public final Optimizer.Plan plan;
        public final String why;

        Advice(Move move, Optimizer.Plan plan, String why) {
            this.move = move;
            this.plan = plan;
            this.why = why;
        }
    }

    /**
     * Picks the next card from the fight as it stands.
     *
     * @param budget hitpoints we are willing to lose, for {@link Aim#BUDGET}. Ignored
     *               otherwise.
     */
    public static Advice next(Combatant me, Combatant foe, List<Move> deck, FoeModel model,
                              Aim aim, double budget, int beam, long horizon) {
        return(next(me, new Combatant[] {foe}, deck, new FoeModel[] {model}, aim, budget,
                    beam, horizon));
    }

    /**
     * The same, against everything that is actually on us.
     *
     * Which card to throw depends on how many of them there are, and not gently: a card
     * that hits every opponent in range is the best thing in the deck against five and an
     * ordinary attack against one. Advising from the one opponent we happen to be aimed at
     * gives the second answer in both fights.
     *
     * The one we are aimed at goes first - the search kills down the array in order.
     */
    public static Advice next(Combatant me, Combatant[] foes, List<Move> deck,
                              FoeModel[] models, Aim aim, double budget, int beam,
                              long horizon) {
        return(next(me, foes, deck, models, aim, budget, beam, horizon, null));
    }

    /**
     * The same, with the initiative we hold against EACH of them - see
     * {@link Optimizer#search(Combatant, Combatant[], List, FoeModel[], int, long, int[])}.
     * Null starts every relation at {@code me.ip}.
     */
    public static Advice next(Combatant me, Combatant[] foes, List<Move> deck,
                              FoeModel[] models, Aim aim, double budget, int beam,
                              long horizon, int[] myIp) {
        if((me == null) || (foes == null) || (foes.length == 0) || (deck == null)
           || deck.isEmpty())
            return(new Advice(null, null, "nothing to plan with"));
        boolean any = false;
        for(Combatant f : foes)
            any |= (f != null) && (f.hp > 0);
        if(!any)
            return(new Advice(null, null, "it is already dead"));
        List<Optimizer.Plan> front = Optimizer.search(me, foes, deck, models, beam, horizon,
                                                      myIp);
        if(front.isEmpty())
            return(new Advice(null, null, "no plan reached the horizon"));
        Optimizer.Plan pick = choose(front, aim, budget);
        if(pick == null)
            return(new Advice(null, null, "no plan stays inside the budget"));
        if(pick.moves.isEmpty())
            return(new Advice(null, pick, "the best plan is to throw nothing"));
        return(new Advice(pick.moves.get(0), pick, aim.name().toLowerCase()));
    }

    /**
     * Resolves a frontier to one plan.
     *
     * The frontier is the point of the optimizer and picking from it is a policy question,
     * not a modelling one - which is why it is a parameter rather than a constant. A plan
     * that does not kill inside the horizon loses to one that does, whatever it cost:
     * "took no damage" is not an achievement if the fight is still going.
     */
    public static Optimizer.Plan choose(List<Optimizer.Plan> front, Aim aim, double budget) {
        List<Optimizer.Plan> kills = new ArrayList<Optimizer.Plan>();
        for(Optimizer.Plan p : front) {
            if(p.killed)
                kills.add(p);
        }
        List<Optimizer.Plan> pool = kills.isEmpty() ? front : kills;
        if(aim == Aim.SURVIVE)
            return(survive(pool, budget));
        Optimizer.Plan best = null;
        for(Optimizer.Plan p : pool) {
            if((aim == Aim.BUDGET) && !(p.hpLost <= budget))
                continue;
            if(best == null) {
                best = p;
                continue;
            }
            if(aim == Aim.WOUNDING) {
                if((p.wounds > best.wounds + 1e-9)
                   || ((Math.abs(p.wounds - best.wounds) <= 1e-9) && (p.ticks < best.ticks)))
                    best = p;
            } else if(aim == Aim.SAFEST) {
                /* Least damage, and among equals the quicker. A NaN cost is unknown, not
                 * free, so it never beats a measured one. */
                if(less(p.cost(), best.cost())
                   || (eq(p.cost(), best.cost()) && (p.ticks < best.ticks)))
                    best = p;
            } else {
                if((p.ticks < best.ticks)
                   || ((p.ticks == best.ticks) && less(p.cost(), best.cost())))
                    best = p;
            }
        }
        if((best == null) || (aim == Aim.WOUNDING))
            return(best);
        /* WITHIN WHAT THE MODEL CAN TELL APART, THE OTHER MEASURES DECIDE (2026-09-22). A plan a
         * tick faster at twice the armour, or a hundredth of a hitpoint cheaper at twice the time,
         * is not better - a tick is 60 ms and the damage model's own error is several hitpoints.
         * Seeded with each player's own line, the planner still "lost" to it in 3 of 1,044 solo
         * fights on FASTEST and in 200 on SAFEST, every one this shape. So the aim's own measure is
         * read to TICK_EPS / HP_EPS, and among the plans inside that the next measure picks, armour
         * last. With the player's line on the frontier (Optimizer.search with lines) the pick is
         * then never worse than it on the aim's measure beyond that resolution, and never worse on
         * the others where the aim's measure ties. */
        List<Optimizer.Plan> near = new ArrayList<Optimizer.Plan>();
        for(Optimizer.Plan p : pool) {
            if((aim == Aim.BUDGET) && !(p.hpLost <= budget))
                continue;
            boolean close = (aim == Aim.SAFEST) ? within(p.cost(), best.cost(), HP_EPS)
                : (p.ticks <= best.ticks + TICK_EPS);
            if(close)
                near.add(p);
        }
        return(settle((aim == Aim.SAFEST) ? quickest(near) : cheapest(near), near(pool, aim, budget)));
    }

    /** The plans an aim may choose from: all of them, or for BUDGET those inside it. */
    private static List<Optimizer.Plan> near(List<Optimizer.Plan> pool, Aim aim, double budget) {
        if(aim != Aim.BUDGET)
            return(pool);
        List<Optimizer.Plan> out = new ArrayList<Optimizer.Plan>();
        for(Optimizer.Plan p : pool) {
            if(p.hpLost <= budget)
                out.add(p);
        }
        return(out);
    }

    /**
     * Whether {@code x} beats {@code q}: no worse on ticks, hitpoints or armour soaked beyond
     * TICK_EPS / HP_EPS, and better beyond them on at least one.
     */
    public static boolean beats(Optimizer.Plan x, Optimizer.Plan q) {
        if(!x.killed && q.killed)
            return(false);
        if(x.killed && !q.killed)
            return(true);
        boolean noWorse = (x.ticks <= q.ticks + TICK_EPS) && !worse(x.hpLost, q.hpLost)
            && (x.soaked <= q.soaked + HP_EPS);
        boolean better = (x.ticks < q.ticks - TICK_EPS) || clearlyLess(x.hpLost, q.hpLost)
            || (x.soaked < q.soaked - HP_EPS);
        return(noWorse && better);
    }

    private static boolean worse(double a, double b) {
        if(Double.isNaN(a) || Double.isNaN(b))
            return(Double.isNaN(a) && !Double.isNaN(b));
        return(a > b + HP_EPS);
    }

    private static boolean clearlyLess(double a, double b) {
        if(Double.isNaN(a) || Double.isNaN(b))
            return(!Double.isNaN(a) && Double.isNaN(b));
        return(a < b - HP_EPS);
    }

    /**
     * NOTHING THE AIM COULD HAVE PICKED BEATS WHAT IT PICKED (2026-09-22). Each aim reads its own
     * measure to a resolution and lets the next decide inside it, and tolerances chained that way
     * are not transitive: SAFEST took half a hitpoint as a tie, then the plan a tick faster, and
     * that plan soaked 0.56 more armour than the player's own line beside it - a loss on the
     * one measure it never looked at. So the pick is walked to whatever beats it, until nothing
     * does. With every line a player has shown on the frontier (Optimizer.search with lines, and
     * soaked a frontier axis), the answer is then never beaten by one of them. Bounded: each step
     * is better beyond resolution on some measure and no worse beyond it on the others.
     */
    static Optimizer.Plan settle(Optimizer.Plan q, List<Optimizer.Plan> pool) {
        for(int i = 0; (q != null) && (i < pool.size()); i++) {
            Optimizer.Plan next = null;
            for(Optimizer.Plan x : pool) {
                if((x != q) && beats(x, q) && ((next == null) || beats(x, next)
                                               || (!beats(next, x) && (x.ticks < next.ticks))))
                    next = x;
            }
            if(next == null)
                break;
            q = next;
        }
        return(q);
    }

    /** A tick is 60 ms: nothing a plan can promise to within one. */
    public static final long TICK_EPS = 1;
    /** Half a hitpoint: below the damage model's resolution by an order of magnitude. */
    public static final double HP_EPS = 0.5;

    private static boolean within(double a, double b, double eps) {
        if(Double.isNaN(a) || Double.isNaN(b))
            return(Double.isNaN(a) && Double.isNaN(b));
        return(a <= b + eps);
    }

    /** The cheapest, then the quicker, then the one that soaks less. */
    private static Optimizer.Plan cheapest(List<Optimizer.Plan> near) {
        Optimizer.Plan out = null;
        for(Optimizer.Plan p : near) {
            if((out == null) || less(p.cost(), out.cost())
               || (eq(p.cost(), out.cost()) && ((p.ticks < out.ticks)
                   || ((p.ticks == out.ticks) && (p.soaked < out.soaked)))))
                out = p;
        }
        return(out);
    }

    /** The quickest, then the cheaper, then the one that soaks less. */
    private static Optimizer.Plan quickest(List<Optimizer.Plan> near) {
        Optimizer.Plan out = null;
        for(Optimizer.Plan p : near) {
            if((out == null) || (p.ticks < out.ticks)
               || ((p.ticks == out.ticks) && (less(p.cost(), out.cost())
                   || (eq(p.cost(), out.cost()) && (p.soaked < out.soaked)))))
                out = p;
        }
        return(out);
    }

    /**
     * The fastest plan inside the budget, else the cheapest - see {@link Aim#SURVIVE}.
     *
     * Two passes over the same pool rather than a filter and then a fallback search, so the
     * cheapest plan is known whether or not anything fits.
     */
    private static Optimizer.Plan survive(List<Optimizer.Plan> pool, double budget) {
        /* SURVIVAL FIRST. When nothing fits the budget the answer is the plan that loses the
         * fewest HITPOINTS - not the cheapest by cost. Ranked on cost, a quicker kill that soaks
         * less armour undercut a restoration plan that kept us standing: replaying Santa Samus's
         * 2026-09-19 denmother fight, a price on wear cut the restorations from 42% of decisions
         * to 4-8% and raised the hitpoints lost from 316 to 350. Armour wear is worth pricing
         * only among plans we survive. */
        Optimizer.Plan safe = null;
        for(Optimizer.Plan p : pool) {
            if((safe == null) || less(p.hpLost, safe.hpLost)
               || (eq(p.hpLost, safe.hpLost) && (p.ticks < safe.ticks)))
                safe = p;
        }
        /* The same resolution as choose(): within HP_EPS of the fewest hitpoints, the quickest. */
        if(safe != null) {
            List<Optimizer.Plan> near = new ArrayList<Optimizer.Plan>();
            for(Optimizer.Plan p : pool) {
                if(within(p.hpLost, safe.hpLost, HP_EPS))
                    near.add(p);
            }
            safe = quickest(near);
        }
        /* HOW MUCH SPEED MAY COST, among the plans the budget allows: a candidate costs at most
         * costTolerance more than the cheapest of them. Infinite - the default - admits every
         * one, which is the rule this aim always had: the fastest plan the budget allows. */
        double cheapest = Double.NaN;
        for(Optimizer.Plan p : pool) {
            if((Double.isNaN(p.hpLost) || (p.hpLost <= budget)) && !Double.isNaN(p.cost())
               && (Double.isNaN(cheapest) || (p.cost() < cheapest)))
                cheapest = p.cost();
        }
        double cap = Double.isNaN(cheapest) ? Double.POSITIVE_INFINITY : (cheapest + costTolerance);
        Optimizer.Plan fast = null;
        for(Optimizer.Plan p : pool) {
            if(!(Double.isNaN(p.hpLost) || (p.hpLost <= budget)))
                continue;
            if(!Double.isNaN(p.cost()) && (p.cost() > cap))
                continue;
            if((fast == null) || (p.ticks < fast.ticks)
               || ((p.ticks == fast.ticks) && less(p.cost(), fast.cost())))
                fast = p;
        }
        if(fast != null) {
            /* Within TICK_EPS of the fastest the budget allows, the cheapest - see choose(). */
            List<Optimizer.Plan> near = new ArrayList<Optimizer.Plan>();
            for(Optimizer.Plan p : pool) {
                if(!(Double.isNaN(p.hpLost) || (p.hpLost <= budget)))
                    continue;
                if(!Double.isNaN(p.cost()) && (p.cost() > cap))
                    continue;
                if(p.ticks <= fast.ticks + TICK_EPS)
                    near.add(p);
            }
            fast = cheapest(near);
        }
        if(fast != null) {
            List<Optimizer.Plan> allowed = new ArrayList<Optimizer.Plan>();
            for(Optimizer.Plan p : pool) {
                if((Double.isNaN(p.hpLost) || (p.hpLost <= budget))
                   && (Double.isNaN(p.cost()) || (p.cost() <= cap)))
                    allowed.add(p);
            }
            return(settle(fast, allowed));
        }
        return(settle(safe, pool));
    }

    /**
     * The most a plan may cost over the cheapest and still be thrown for being faster, in
     * {@link Optimizer.Plan#cost()} units - see {@link Aim#SURVIVE}.
     *
     * Infinite reproduces the aim as it always was: the fastest plan inside the hitpoint budget,
     * which spends up to the whole budget - a quarter of our bar against creatures - to finish a
     * few ticks sooner. James, 2026-09-21: that undervalues defence. A finite tolerance says how
     * much we will pay for speed and no more.
     */
    public static volatile double costTolerance = Double.POSITIVE_INFINITY;

    /** NaN is unknown and never wins, which is the difference between "free" and "unmeasured". */
    private static boolean less(double a, double b) {
        if(Double.isNaN(a))
            return(false);
        if(Double.isNaN(b))
            return(true);
        return(a < b);
    }

    private static boolean eq(double a, double b) {
        return((Double.isNaN(a) && Double.isNaN(b)) || (a == b));
    }
}
