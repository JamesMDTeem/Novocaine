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
                if(less(p.hpLost, best.hpLost)
                   || (eq(p.hpLost, best.hpLost) && (p.ticks < best.ticks)))
                    best = p;
            } else {
                if((p.ticks < best.ticks)
                   || ((p.ticks == best.ticks) && less(p.hpLost, best.hpLost)))
                    best = p;
            }
        }
        return(best);
    }

    /**
     * The fastest plan inside the budget, else the cheapest - see {@link Aim#SURVIVE}.
     *
     * Two passes over the same pool rather than a filter and then a fallback search, so the
     * cheapest plan is known whether or not anything fits.
     */
    private static Optimizer.Plan survive(List<Optimizer.Plan> pool, double budget) {
        Optimizer.Plan fast = null, safe = null;
        for(Optimizer.Plan p : pool) {
            if(Double.isNaN(p.hpLost) || (p.hpLost <= budget)) {
                if((fast == null) || (p.ticks < fast.ticks)
                   || ((p.ticks == fast.ticks) && less(p.hpLost, fast.hpLost)))
                    fast = p;
            }
            if((safe == null) || less(p.hpLost, safe.hpLost)
               || (eq(p.hpLost, safe.hpLost) && (p.ticks < safe.ticks)))
                safe = p;
        }
        return((fast != null) ? fast : safe);
    }

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
