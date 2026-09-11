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
        final Combatant me;
        /**
         * EVERY opponent, not one, and the dead stay in the array.
         *
         * A crowd used to be modelled as a single opponent with N times the health on a
         * faster clock, and openings do not work that way. Opening one bee does not open
         * the others, damage goes as the SQUARE of the opening, and so five bees each a
         * fifth open is nothing like one bee fully open - the pooled version let our
         * openings accumulate against the whole crowd as though it were one animal, and
         * overstated every plan that pries a target open before hitting it.
         *
         * It was wrong in the other direction too, and that half is easier to miss. Three
         * cards in the sheet hit more than one opponent - see Move.targets - and against a
         * pooled crowd they landed once, so the model priced Full Circle against five
         * animals exactly as it priced it against one. The two errors do not cancel; they
         * are simply both there, one flattering single-target openers and one penalising
         * the cards that exist for crowds.
         *
         * The dead are kept in place so that indices line up with the models and the clocks
         * beside them, and because a plan's shape - which one we were hitting when - is
         * lost if the array is compacted underneath it.
         */
        final Combatant[] foes;
        final List<Move> path;
        final long tick;
        /** Each opponent's own next action, on its own clock. */
        final long[] foeNext;
        /**
         * How many actions each opponent has taken along this line.
         *
         * Needed because an opponent now throws real cards rather than one averaged
         * action, and which card comes next depends on how many it has already thrown -
         * they are dealt out in proportion to the measured mix. Without this every node
         * would replay the creature's FIRST action, so a boreworm would open with Roar of
         * the Wild forever and never get to Fell Scratch.
         *
         * It lives on the node rather than in the model because the search explores many
         * lines at once and each has its own history; a counter on the shared model would
         * be advanced by whichever branch happened to be expanded last.
         */
        final int[] foeActs;
        final double hpLost;

        Node(Combatant me, Combatant[] foes, List<Move> path, long tick, long[] foeNext,
             double hpLost, int[] foeActs) {
            this.me = me;
            this.foes = foes;
            this.path = path;
            this.tick = tick;
            this.foeNext = foeNext;
            this.hpLost = hpLost;
            this.foeActs = foeActs;
        }

        /** The one we are hitting: the first still standing. See Optimizer.step. */
        int main() {
            for(int i = 0; i < foes.length; i++) {
                if(foes[i].alive())
                    return(i);
            }
            return(-1);
        }

        boolean anyAlive() {
            return(main() >= 0);
        }

        double foeHp() {
            double hp = 0;
            for(Combatant f : foes)
                hp += Math.max(0, f.hp);
            return(hp);
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
        return(search(me, new Combatant[] {foe}, deck, new FoeModel[] {model}, beam, maxTicks));
    }

    /**
     * The same search against a CROWD, each opponent with its own health and its own clock.
     *
     * One against one is the special case of this with an array of one, which is how the
     * overload above is served. What the crowd changes is not only arithmetic:
     *
     * - Openings are per opponent. Prying one animal open buys nothing against the next,
     *   which is what the old pooled model got wrong and why it flattered every opener.
     * - Each opponent swings on its own clock, so the rate the crowd hits us at falls as
     *   they die rather than holding at its opening value to the last animal.
     * - A card that hits several of them - Full Circle, Punch 'em Both, Storm of Swords -
     *   actually hits several of them here, which against a pooled opponent it could not.
     *
     * WE FOCUS ONE AT A TIME, killing down the array in order, which is what a player does
     * and is not free of consequence: it is why the crowd's rate falls, and it is why a
     * splash card's openings are worth keeping - they are still standing on the next one
     * when we get to it.
     *
     * What is still missing is range. Nothing here knows where anybody stands, so a
     * multi-target card reaches every opponent still alive, and the corpus says that is an
     * upper bound - Full Circle opened only one opponent in 53 of the 99 throws that opened
     * anything at all.
     */
    public static List<Plan> search(Combatant me, Combatant[] foes, List<Move> deck,
                                    FoeModel[] models, int beam, long maxTicks) {
        /* Everything the deck opens when the OPPONENT swings, summed once. A deck holds
         * at most one such card in the corpus - Parry - but summing costs nothing and
         * assumes nothing about that staying true. */
        double[] trigger = new double[4];
        boolean anyTrigger = false;
        for(Move m : deck) {
            for(int c = 0; c < 4; c++) {
                if(m.whenAttackedOpens[c] > 0) {
                    trigger[c] += m.whenAttackedOpens[c];
                    anyTrigger = true;
                }
            }
        }
        if(!anyTrigger)
            trigger = null;
        Combatant[] f0 = new Combatant[foes.length];
        long[] next0 = new long[foes.length];
        double hp0 = 0;
        for(int i = 0; i < foes.length; i++) {
            f0[i] = foes[i].copy();
            next0[i] = (models[i].period == Long.MAX_VALUE) ? Long.MAX_VALUE : models[i].period;
            hp0 += f0[i].hp;
        }
        List<Node> live = new ArrayList<Node>();
        live.add(new Node(me.copy(), f0, new ArrayList<Move>(), 0, next0, 0,
                          new int[foes.length]));
        List<Plan> done = new ArrayList<Plan>();

        while(!live.isEmpty()) {
            List<Node> next = new ArrayList<Node>();
            for(Node n : live) {
                for(Move m : deck) {
                    Node s = step(n, m, models, maxTicks, trigger);
                    if(s == null)
                        continue;
                    if(!s.anyAlive()) {
                        done.add(new Plan(s.path, s.tick, s.hpLost, true, s.foeHp()));
                    } else if(!s.me.alive() || (s.tick >= maxTicks)) {
                        done.add(new Plan(s.path, s.tick, s.hpLost, false, s.foeHp()));
                    } else {
                        next.add(s);
                    }
                }
            }
            if(next.isEmpty())
                break;
            live = prune(next, hp0, beam);
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
     * AND A THIRD END, WHICH IS SETUP. Both of the above rank on value already realised -
     * damage done, hitpoints kept - and an opening is value NOT yet realised. Damage goes
     * as the square of the opening, so a line that has spent three cards prying a target
     * open has done little damage, kept no more hitpoints than anyone else, and is holding
     * the largest payment in the search. Ranked on either of the first two it is pruned one
     * step before it collects.
     *
     * That is what broke deck comparison. Adding a card to a deck cannot make the true
     * optimum worse, because the plan that ignores it is still there - and ticks by deck
     * size against the cave angler ran 736, 705, 705, 884 at beam 20, still rising at beam
     * 2000. A wider beam does not fix a ranking that discards the winning shape; it only
     * discards it more slowly.
     *
     * So the beam is three ways: best by rate, best by hitpoints kept, best by the openings
     * standing. A frontier search has to carry every end of the frontier while it searches,
     * or it can only ever find the ends it carried.
     */
    private static List<Node> prune(List<Node> next, double foeHp0, int beam) {
        List<Node> byRate = new ArrayList<Node>(next);
        Collections.sort(byRate, (a, b) -> {
            double ra = (foeHp0 - a.foeHp()) / Math.max(1, a.tick);
            double rb = (foeHp0 - b.foeHp()) / Math.max(1, b.tick);
            return(Double.compare(rb, ra));
        });
        List<Node> byHp = new ArrayList<Node>(next);
        Collections.sort(byHp, (a, b) -> {
            int c = Double.compare(a.hpLost, b.hpLost);
            return((c != 0) ? c : Double.compare(a.foeHp(), b.foeHp()));
        });
        List<Node> bySetup = new ArrayList<Node>(next);
        Collections.sort(bySetup, (a, b) -> {
            int c = Double.compare(open(b), open(a));
            return((c != 0) ? c : Double.compare(a.foeHp(), b.foeHp()));
        });
        /* ADDED TO THE OTHER TWO, NOT CARVED OUT OF THEM. Taking a third of the beam for
         * setup was the obvious way and it broke the check next door: the initiative curve
         * stopped being monotone, because the hitpoint end had been cut in half to pay for
         * it and the least-damage line went with it. The two ends that were already there
         * were already load-bearing. */
        int half = Math.max(1, beam / 2);
        List<Node> out = new ArrayList<Node>();
        for(int i = 0; (i < half) && (i < byRate.size()); i++)
            out.add(byRate.get(i));
        for(int i = 0; (i < half) && (i < byHp.size()); i++) {
            Node n = byHp.get(i);
            if(!out.contains(n))
                out.add(n);
        }
        for(int i = 0; (i < half) && (i < bySetup.size()); i++) {
            Node n = bySetup.get(i);
            if(!out.contains(n))
                out.add(n);
        }
        return(out);
    }

    /**
     * How much is standing open on the one we are hitting, which is damage not yet collected.
     *
     * The one we are hitting rather than the crowd's total, because that is the payment the
     * next swing collects. Summing the crowd would rank a line that has lightly opened five
     * animals above one that has pried a single animal wide, and the square in the damage
     * formula says the opposite.
     */
    private static double open(Node n) {
        int i = n.main();
        if(i < 0)
            return(0);
        double[] all = new double[4];
        for(int c = 0; c < 4; c++)
            all[c] = n.foes[i].opening(c);
        return(Formulas.combined(all));
    }

    /**
     * Applies one of our moves, letting every opponent act for the clock ticks it owns.
     *
     * The waiting half is where "combat is not turn-based" lives, and with a crowd it is
     * also where the crowd's pressure comes from: each opponent has its own period and its
     * own place in its own rotation, so the window our cooldown opens is filled by whichever
     * of them happens to come up in it, not by an average.
     */
    private static Node step(Node n, Move m, FoeModel[] models, long maxTicks,
                             double[] trigger) {
        Combatant me = n.me.copy();
        Combatant[] foes = new Combatant[n.foes.length];
        for(int i = 0; i < foes.length; i++)
            foes[i] = n.foes[i].copy();
        long[] foeNext = n.foeNext.clone();
        int[] acts = n.foeActs.clone();
        long tick = n.tick;
        double hpLost = n.hpLost;

        /* Wait until we may act, and let the opponents act on their own clocks meanwhile.
         * A long cooldown is not merely slow, it is a window they get to swing in, and a
         * short one is not. */
        long ready = Math.max(tick, me.readyAt);
        while(me.alive()) {
            /* Whichever of them is due first. A dead one is due never, which is the whole
             * of why a crowd gets quieter as it dies - the old pooled model kept swinging
             * at five-sixths of full rate down to the last animal. */
            int who = -1;
            for(int i = 0; i < foes.length; i++) {
                if(!foes[i].alive())
                    continue;
                if((who < 0) || (foeNext[i] < foeNext[who]))
                    who = i;
            }
            if((who < 0) || (foeNext[who] > ready) || (foeNext[who] >= maxTicks))
                break;
            /* A fleeing opponent has stopped fighting back, so this window costs nothing -
             * and every reduction or point of initiative bought during it buys nothing
             * either. The frontier sorts that out on its own once the damage stops: a plan
             * that keeps defending simply arrives later for the same hitpoints, and is
             * dominated. */
            hpLost += models[who].act(me, me.defenceWeight(), foes[who], acts[who], null);
            acts[who]++;
            /* AND WHAT WE HOLD THAT ANSWERS A SWING. Parry opens the opponent when the
             * opponent attacks, not when it is played, so it lands here rather than in
             * use() - and it lands on THE ONE THAT SWUNG, which is measured: across 762
             * steps where blue rose on any of several opponents at once, it rose on
             * exactly one in 753. A sword is required, which is why this reads armed. */
            if((trigger != null) && me.armed())
                Sim.trigger(foes[who], trigger);
            foeNext[who] += models[who].period;
        }
        tick = ready;
        if(tick > maxTicks)
            return(null);

        /* WE DIED WAITING, AND THAT IS A RESULT RATHER THAN A DEAD END.
         *
         * The swing below would be refused for being dead, and a refusal returns null,
         * which drops the line out of the search entirely. One against one that merely
         * lost a few hopeless plans off the frontier. Against a crowd it lost ALL of them:
         * two animals that kill us before we kill them made every line end this way, so
         * the search returned nothing at all and the caller read "no plan" where the
         * answer was "this fight kills you".
         *
         * So the node comes back with the path it arrived with - the move was never thrown
         * - and the caller records it as a plan that did not kill. */
        if(!me.alive())
            return(new Node(me, foes, n.path, tick, foeNext, hpLost, acts));

        int main = -1;
        for(int i = 0; i < foes.length; i++) {
            if(foes[i].alive()) {
                main = i;
                break;
            }
        }
        if(main < 0)
            return(new Node(me, foes, n.path, tick, foeNext, hpLost, acts));

        Sim sim = new Sim(me, foes[main]);
        sim.advanceTo(tick);
        Sim.Result r = sim.use(me, m);
        if(!r.ok)
            return(null);
        /* AND EVERYONE ELSE IT REACHES. Three cards hit more than the one in front of us -
         * see Move.targets - and what they do to the rest is the reason to hold one: the
         * damage, and openings that are still standing on the next animal when this one
         * falls. Nothing here knows about range, so they reach everything still alive,
         * which is an upper bound rather than an estimate. */
        if(m.splashes()) {
            int idx = 1;
            for(int i = 0; (i < foes.length) && (idx < m.targets); i++) {
                if((i == main) || !foes[i].alive())
                    continue;
                sim.splash(me, m, foes[i], idx);
                idx++;
            }
        }
        List<Move> path = new ArrayList<Move>(n.path);
        path.add(m);
        return(new Node(me, foes, path, tick, foeNext, hpLost, acts));
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
