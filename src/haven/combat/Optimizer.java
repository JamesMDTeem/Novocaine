package haven.combat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What to throw, and in what order.
 *
 * WHAT THIS OPTIMIZES, AND WHY IT IS NOT ONE NUMBER.
 *
 * The obvious objective is "kill it fastest, because a live opponent keeps hitting me". That
 * is a good instinct and it is not sufficient, for a reason the damage formula makes exact:
 * damage scales with the SQUARE of the opening it reads, and a defensive card cuts an opening
 * by a share of it rather than a fixed amount. Spending 25 ticks on Quick Dodge to halve a
 * standing green quarters every green hit for the rest of the fight. Whether that pays back
 * depends on how much fight is left, so time and damage are coupled and cannot be optimized
 * one after the other.
 *
 * They also are not commensurable. Collapsing them into {@code hp - lambda * ticks} needs a
 * lambda that nobody can defend: it is worth spending a minute to avoid a scratch when
 * hunting alone, and worth taking a real wound to finish in ten seconds with company coming.
 * So this returns the PARETO FRONTIER - every plan that is not beaten on both axes at once -
 * and leaves the trade to whoever is holding the character. That is the same choice this
 * project makes everywhere else: report the interval, do not invent the number inside it.
 *
 * COMBAT IS NOT TURN-BASED, and the search reflects that. Each side has its own clock; a move
 * costs its cooldown, which is the opportunity cost of everything not thrown during it, and
 * cooldowns differ per move and shift with the agility gap. So the search advances to
 * whichever clock fires next rather than alternating, and a plan is a sequence of moves with
 * the times they land at rather than a list of turns.
 *
 * Per ADR-0002 this imports nothing from {@code haven}.
 */
public final class Optimizer {
    private Optimizer() {}

    /** One candidate line of play, and what it cost. */
    public static final class Plan {
        public final List<Move> moves;
        /** Ticks from the first move to the kill, or to the horizon if there was none. */
        public final long ticks;
        /** Soft hitpoints we lost. NaN when the foe model cannot say. */
        public final double hpLost;
        public final boolean killed;
        /** What the opponent had left when we stopped. */
        public final double foeHp;

        Plan(List<Move> moves, long ticks, double hpLost, boolean killed, double foeHp) {
            this.moves = Collections.unmodifiableList(new ArrayList<Move>(moves));
            this.ticks = ticks;
            this.hpLost = hpLost;
            this.killed = killed;
            this.foeHp = foeHp;
        }

        public String toString() {
            StringBuilder b = new StringBuilder();
            for(Move m : moves) {
                if(b.length() > 0)
                    b.append(", ");
                b.append(m.name);
            }
            return(b.toString());
        }
    }

    /* A node in the beam. Mutable state plus the path that reached it. */
    private static final class Node {
        final Combatant me, foe;
        final List<Move> path;
        final long tick, foeNext;
        final double hpLost;

        Node(Combatant me, Combatant foe, List<Move> path, long tick, long foeNext,
             double hpLost) {
            this.me = me;
            this.foe = foe;
            this.path = path;
            this.tick = tick;
            this.foeNext = foeNext;
            this.hpLost = hpLost;
        }
    }

    /**
     * Searches for the plans on the frontier.
     *
     * Beam search rather than exhaustive: a ten-card deck over a thirty-move fight is 10^30
     * lines, and rather than an arbitrary cut this keeps the best `beam` partial states at
     * each depth. Deterministic, so no averaging over rollouts - the model has no random
     * element and fits of the logged damage leave no room for one.
     *
     * The beam is ranked on damage dealt per tick spent, which is the only scalar that is
     * defensible here: it is the rate the fight is actually being won at, and it does not
     * presume how much a tick is worth against a hitpoint. Plans that defend are kept by the
     * frontier at the end rather than by the ranking during, because their payback arrives
     * later than the beam can see.
     *
     * @param maxTicks the horizon. A plan that has not killed by then is reported as not
     *                 killing rather than extrapolated.
     */
    public static List<Plan> search(Combatant me, Combatant foe, List<Move> deck,
                                    FoeModel model, int beam, long maxTicks) {
        List<Node> live = new ArrayList<Node>();
        live.add(new Node(me.copy(), foe.copy(), new ArrayList<Move>(), 0,
                          (model.period == Long.MAX_VALUE) ? Long.MAX_VALUE : model.period,
                          0));
        List<Plan> done = new ArrayList<Plan>();

        while(!live.isEmpty()) {
            List<Node> next = new ArrayList<Node>();
            for(Node n : live) {
                for(Move m : deck) {
                    Node s = step(n, m, model, maxTicks);
                    if(s == null)
                        continue;
                    if(!s.foe.alive()) {
                        done.add(new Plan(s.path, s.tick, s.hpLost, true, s.foe.hp));
                    } else if(!s.me.alive() || (s.tick >= maxTicks)) {
                        done.add(new Plan(s.path, s.tick, s.hpLost, false, s.foe.hp));
                    } else {
                        next.add(s);
                    }
                }
            }
            if(next.isEmpty())
                break;
            live = prune(next, foe.hp, beam);
        }
        return(frontier(done));
    }

    /**
     * Keeps a beam that is diverse along BOTH objectives, which a single ranking cannot.
     *
     * Ranking on damage per tick alone is the obvious choice and it is self-defeating here:
     * a defensive move deals nothing, so it always lowers the rate, so every line that
     * defends is pruned one step after it defends - long before the reduced openings pay
     * back. The search then reports that defending never helps, which is not a finding
     * about combat but an artefact of the pruning.
     *
     * It showed up exactly that way: a deck with Quick Dodge and one without came out at
     * the same 63.5 hitpoints over a long fight, when the whole reason the frontier exists
     * is that they should differ.
     *
     * So half the beam is the best by rate and half is the best by hitpoints kept, ties
     * broken on damage done. A frontier search has to carry both ends of the frontier while
     * it searches, or it can only ever find one of them.
     */
    private static List<Node> prune(List<Node> next, double foeHp0, int beam) {
        List<Node> byRate = new ArrayList<Node>(next);
        Collections.sort(byRate, (a, b) -> {
            double ra = (foeHp0 - a.foe.hp) / Math.max(1, a.tick);
            double rb = (foeHp0 - b.foe.hp) / Math.max(1, b.tick);
            return(Double.compare(rb, ra));
        });
        List<Node> byHp = new ArrayList<Node>(next);
        Collections.sort(byHp, (a, b) -> {
            int c = Double.compare(a.hpLost, b.hpLost);
            return((c != 0) ? c : Double.compare(a.foe.hp, b.foe.hp));
        });
        int half = Math.max(1, beam / 2);
        List<Node> out = new ArrayList<Node>();
        for(int i = 0; (i < half) && (i < byRate.size()); i++)
            out.add(byRate.get(i));
        for(int i = 0; (i < half) && (i < byHp.size()); i++) {
            Node n = byHp.get(i);
            if(!out.contains(n))
                out.add(n);
        }
        return(out);
    }

    /** Applies one of our moves, letting the opponent act for every clock tick it owns. */
    private static Node step(Node n, Move m, FoeModel model, long maxTicks) {
        Combatant me = n.me.copy(), foe = n.foe.copy();
        long tick = n.tick, foeNext = n.foeNext;
        double hpLost = n.hpLost;

        /* Wait until we may act, and let the opponent act on its own clock meanwhile. This
         * is where the not-turn-based part lives: a long cooldown is not merely slow, it is
         * a window the opponent gets to swing in, and a short one is not. */
        long ready = Math.max(tick, me.readyAt);
        while((foeNext <= ready) && (foeNext < maxTicks)) {
            /* A fleeing opponent has stopped fighting back, so this window costs nothing -
             * and every reduction or point of initiative bought during it buys nothing
             * either. The frontier sorts that out on its own once the damage stops: a plan
             * that keeps defending simply arrives later for the same hitpoints, and is
             * dominated. */
            hpLost += model.act(me, me.defenceWeight(), foe);
            foeNext += model.period;
            if(!me.alive())
                break;
        }
        tick = ready;
        if(tick > maxTicks)
            return(null);

        Sim sim = new Sim(me, foe);
        sim.advanceTo(tick);
        Sim.Result r = sim.use(me, m);
        if(!r.ok)
            return(null);
        List<Move> path = new ArrayList<Move>(n.path);
        path.add(m);
        return(new Node(me, foe, path, tick, foeNext, hpLost));
    }

    /**
     * The plans not beaten on both axes at once.
     *
     * A plan is dominated when another kills at least as fast AND costs no more hitpoints.
     * What survives is the actual choice: the fastest line, the cheapest line, and whatever
     * genuinely trades between them. Anything else is a worse version of one of those.
     */
    public static List<Plan> frontier(List<Plan> all) {
        List<Plan> kills = new ArrayList<Plan>();
        for(Plan p : all) {
            if(p.killed)
                kills.add(p);
        }
        /* Nothing killed inside the horizon: report the closest tries rather than nothing,
         * because "it does not die" is itself the answer to the matchup question. */
        List<Plan> pool = kills.isEmpty() ? all : kills;
        List<Plan> out = new ArrayList<Plan>();
        for(Plan p : pool) {
            boolean dominated = false;
            for(Plan q : pool) {
                if(q == p)
                    continue;
                boolean faster = q.ticks <= p.ticks;
                boolean cheaper = !(q.hpLost > p.hpLost);
                boolean better = (q.ticks < p.ticks) || (q.hpLost < p.hpLost);
                if(faster && cheaper && better) {
                    dominated = true;
                    break;
                }
            }
            if(!dominated)
                out.add(p);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.ticks, b.ticks));
        /* Ties on both axes are the same plan by any measure that matters; keep one. */
        List<Plan> uniq = new ArrayList<Plan>();
        for(Plan p : out) {
            boolean seen = false;
            for(Plan q : uniq) {
                if((q.ticks == p.ticks) && (Math.abs(q.hpLost - p.hpLost) < 1e-9)) {
                    seen = true;
                    break;
                }
            }
            if(!seen)
                uniq.add(p);
        }
        return(uniq);
    }

    /**
     * What a point of initiative brought to the fight is worth.
     *
     * Take Aim and its kin are thrown at range BEFORE the fight starts - kited in, building
     * a pool while the opponent cannot reach us. That time is nearly free, so the question
     * is not what a point costs but how many are worth collecting before engaging, and the
     * answer is a diminishing return that has to be measured rather than guessed: a deck
     * with one initiative-spender wants exactly as many points as it will throw, and a
     * further point buys nothing at all.
     *
     * Runs the search from each starting pool and reports the cheapest killing plan at each.
     * The point to stop at is where the column stops improving.
     *
     * NON-MONOTONICITY IS A BUG SIGNAL, and this reports it rather than hiding it. Bringing
     * a point of initiative cannot make a fight worse: every plan available with two points
     * is still available with four, since the extra simply goes unspent. So if the curve
     * ever rises, the SEARCH failed to find a line it should have - the beam was too narrow
     * for the wider branching that more initiative allows. Observed exactly: at a beam of 40
     * this reads 95.4 at four points against 63.5 at two, and at 120 it reads 63.5
     * throughout. Taking a running minimum would produce the right numbers and conceal an
     * under-resourced search, so the raw result is returned and {@link #beamWasEnough} says
     * whether to trust it.
     *
     * @return hitpoints lost by the cheapest killing plan at 0, 1, ... maxIp starting
     *         initiative; NaN where nothing killed
     */
    public static double[] valueOfStartingIp(Combatant me, Combatant foe, List<Move> deck,
                                             FoeModel model, int beam, long maxTicks,
                                             int maxIp) {
        double[] out = new double[maxIp + 1];
        for(int ip = 0; ip <= maxIp; ip++) {
            Combatant m = me.copy();
            m.ip = ip;
            double best = Double.NaN;
            for(Plan p : search(m, foe, deck, model, beam, maxTicks)) {
                if(p.killed && (Double.isNaN(best) || (p.hpLost < best)))
                    best = p.hpLost;
            }
            out[ip] = best;
        }
        return(out);
    }

    /**
     * Whether a starting-initiative curve is monotone, and so whether the beam sufficed.
     *
     * More initiative cannot cost hitpoints. A curve that rises has been produced by a
     * search that missed a plan, and every number on it is suspect - not only the one that
     * rose. Widen the beam and run it again.
     */
    public static boolean beamWasEnough(double[] curve) {
        double best = Double.MAX_VALUE;
        for(double v : curve) {
            if(Double.isNaN(v))
                continue;
            if(v > (best + 1e-9))
                return(false);
            best = Math.min(best, v);
        }
        return(true);
    }

    /** The deck as a list, for callers holding a pack. */
    public static List<Move> deck(Move... moves) {
        return(Arrays.asList(moves));
    }
}
