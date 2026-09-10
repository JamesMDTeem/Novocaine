package haven.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * Two players, both trying to win.
 *
 * The rest of this package answers "how do I kill that thing", where the thing does not
 * adapt: {@link FoeModel} is a clock, a per-colour pressure and a damage coefficient, and
 * {@link Optimizer} plans against it as a fixed schedule. That is the right shape for an
 * animal, because it is what a corpus of fights can actually yield about one.
 *
 * It is the wrong shape for a person. A player picks the card that suits the state they
 * are in, holds a finisher until the openings are there, and spends initiative when it
 * buys something. An opponent that throws its deck's average action is not a weak player,
 * it is not a player at all, and a deck measured against one has been measured against
 * nobody. So this runs the real {@link Sim} with BOTH sides choosing.
 *
 * NOT TURN-BASED, and the loop is built around that. Cards carry their own cooldowns, so
 * the sides do not alternate: whoever is ready first acts, which may be the same side
 * twice running when it is holding something short against something long. The search
 * below therefore asks "who is ready next" at every ply rather than flipping between
 * them, and a Cleave really does buy the opponent several actions.
 *
 * WHAT IS STILL A HEURISTIC, because it decides the answers and should not be buried:
 *
 *   The leaf evaluation. A cut-off search has to score a position nobody has won yet, and
 *   {@link #eval} does it with health difference plus a discounted opening difference.
 *   Openings are in there because damage goes as the square of the combined opening, so a
 *   position with them standing is worth more than the health says. The weight on that is
 *   a judgement, not a measurement.
 *
 *   Simultaneity. When both sides come ready on the same tick, one of them resolves
 *   first, and this gives it to whoever has been waiting longer, then to side A. Real
 *   simultaneous resolution is not something the corpus settles.
 */
public final class Duel {
    /** How much a standing opening is worth against a share of health, at the leaves. */
    public static final double OPEN_WEIGHT = 0.6;

    /** Bigger than any health-and-openings score, so a kill dominates every position. */
    private static final double WIN = 1000.0;

    public static final class Outcome {
        /** +1 if A put B down, -1 if B put A down, 0 if neither did inside the horizon. */
        public final int winner;
        /** Tick the fight ended on, or the horizon. */
        public final long ticks;
        /** Health each side had left, as a share of its maximum. */
        public final double aLeft, bLeft;

        Outcome(int winner, long ticks, double aLeft, double bLeft) {
            this.winner = winner;
            this.ticks = ticks;
            this.aLeft = aLeft;
            this.bLeft = bLeft;
        }
    }

    /**
     * The position, from A's point of view, when nobody has won yet.
     *
     * Health as a share rather than in points, so the two sides are comparable when their
     * pools differ. Openings enter discounted because they are damage that has not
     * happened: a fight where they are standing on the opponent is better than the health
     * alone says, and worse when they are standing on us.
     */
    static double eval(Combatant a, Combatant b) {
        if(!b.alive())
            return(WIN);
        if(!a.alive())
            return(-WIN);
        double ah = (a.maxHp > 0) ? (a.hp / a.maxHp) : 0;
        double bh = (b.maxHp > 0) ? (b.hp / b.maxHp) : 0;
        double ao = combined(a), bo = combined(b);
        return((ah - bh) + (OPEN_WEIGHT * (bo - ao)));
    }

    private static double combined(Combatant c) {
        double[] o = new double[4];
        for(int i = 0; i < 4; i++)
            o[i] = c.opening(i);
        return(Formulas.combined(o));
    }

    /** Whose turn it is, given that cards have their own cooldowns and do not alternate. */
    private static Combatant next(Sim s) {
        return((s.a.readyAt <= s.b.readyAt) ? s.a : s.b);
    }

    private static Sim clone(Sim s) {
        Sim t = new Sim(s.a.copy(), s.b.copy());
        t.tick = s.tick;
        return(t);
    }

    /**
     * Minimax on the joint state, with A maximising and B minimising one evaluation.
     *
     * Written with a single fixed sign rather than as negamax, because the sides do not
     * alternate - the same side can be to act twice, and negating at every ply would then
     * flip a score that never changed hands.
     */
    static double search(Sim s, List<Move> da, List<Move> db, int depth, long horizon,
                         double alpha, double beta) {
        if(!s.a.alive() || !s.b.alive())
            return(eval(s.a, s.b));
        if((depth <= 0) || (s.tick > horizon))
            return(eval(s.a, s.b));
        Combatant actor = next(s);
        boolean maxing = (actor == s.a);
        List<Move> deck = maxing ? da : db;
        /* Advance to when that side can actually act. Doing this on the shared tick is
         * what makes a long cooldown cost real time rather than one notional turn. */
        long at = Math.max(s.tick, actor.readyAt);
        if(at > horizon)
            return(eval(s.a, s.b));
        double best = maxing ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        boolean any = false;
        for(Move m : deck) {
            if(m.stance)
                continue;               /* held on the bar, never thrown */
            Sim t = clone(s);
            t.tick = at;
            Combatant ta = (actor == s.a) ? t.a : t.b;
            if(t.refuse(ta, m) != null)
                continue;
            any = true;
            t.use(ta, m);
            double v = search(t, da, db, depth - 1, horizon, alpha, beta);
            if(maxing) {
                best = Math.max(best, v);
                alpha = Math.max(alpha, v);
            } else {
                best = Math.min(best, v);
                beta = Math.min(beta, v);
            }
            if(beta <= alpha)
                break;
        }
        if(!any) {
            /* Nothing legal - every card on cooldown or unaffordable. Waiting is an
             * action too, and a fight where one side must wait is exactly the fight a
             * cheap deck loses, so it has to cost ticks rather than be skipped. */
            Sim t = clone(s);
            t.tick = at + 1;
            if(actor == s.a)
                t.a.readyAt = t.tick;
            else
                t.b.readyAt = t.tick;
            return(search(t, da, db, depth - 1, horizon, alpha, beta));
        }
        return(best);
    }

    /** The move that side would actually throw here, or null when it can throw none. */
    public static Move choose(Sim s, Combatant actor, List<Move> da, List<Move> db,
                              int depth, long horizon) {
        boolean maxing = (actor == s.a);
        List<Move> deck = maxing ? da : db;
        Move best = null;
        double bestV = maxing ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        long at = Math.max(s.tick, actor.readyAt);
        for(Move m : deck) {
            if(m.stance)
                continue;
            Sim t = clone(s);
            t.tick = at;
            Combatant ta = (actor == s.a) ? t.a : t.b;
            if(t.refuse(ta, m) != null)
                continue;
            t.use(ta, m);
            double v = search(t, da, db, depth - 1, horizon,
                              Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            if((best == null) || (maxing ? (v > bestV) : (v < bestV))) {
                best = m;
                bestV = v;
            }
        }
        return(best);
    }

    /**
     * Play it out, both sides choosing every time they come ready.
     *
     * @param depth plies of lookahead each side gets. Both get the same, so neither is
     *              handed a better brain than the other - the decks are what differ.
     */
    public static Outcome play(Combatant a, List<Move> da, Combatant b, List<Move> db,
                               int depth, long horizon) {
        Sim s = new Sim(a.copy(), b.copy());
        s.a.readyAt = s.b.readyAt = 0;
        while(s.a.alive() && s.b.alive() && (s.tick <= horizon)) {
            Combatant actor = next(s);
            s.tick = Math.max(s.tick, actor.readyAt);
            if(s.tick > horizon)
                break;
            Move m = choose(s, actor, da, db, depth, horizon);
            if(m == null) {
                /* Nothing legal: wait a tick and let cooldowns run down. */
                actor.readyAt = s.tick + 1;
                s.tick += 1;
                continue;
            }
            s.use(actor, m);
        }
        int winner = !s.b.alive() ? 1 : (!s.a.alive() ? -1 : 0);
        return(new Outcome(winner, s.tick,
                           (s.a.maxHp > 0) ? Math.max(0, s.a.hp / s.a.maxHp) : 0,
                           (s.b.maxHp > 0) ? Math.max(0, s.b.hp / s.b.maxHp) : 0));
    }

    /**
     * The payoff to A with A moving first, in [-1, 1].
     *
     * A win is 1 and a loss -1. A fight neither side finishes is scored by how much
     * health each had left, so a deck that was clearly ahead when the clock ran out is
     * not filed as identical to one that was clearly behind. That keeps the payoff
     * matrix from being mostly zeroes, which is what makes an equilibrium over it mean
     * anything.
     *
     * MOVING FIRST IS WORTH A LOT, so this is not the number to compare decks on - see
     * {@link #payoff}. Kept public because the size of that advantage is worth being able
     * to look at rather than only to correct for.
     */
    public static double payoffFirst(Combatant a, List<Move> da, Combatant b, List<Move> db,
                                     int depth, long horizon) {
        Outcome o = play(a, da, b, db, depth, horizon);
        /* GRADED, NOT WIN-OR-LOSE, and the reason is the tempo above. At full deck size
         * both sides kill fast enough that whoever opens usually wins, so a binary result
         * gives +1 and +1, the average is zero, and every pair of decks looks identical.
         * That is not a finding, it is a measurement thrown away: winning with three
         * quarters of your health left is not the same as winning with a sliver, and the
         * difference between those two is exactly what separates the decks once the first
         * swing has stopped being the question.
         *
         * A kill still outranks any survival, so the scale is in two bands rather than
         * one: a kill lands in [0.5, 1] by how much health it was bought with, and a
         * fight nobody finished lands in [-0.5, 0.5] by who was ahead. Nothing that
         * failed to kill can outscore something that did.
         */
        if(o.winner > 0)
            return(0.5 + (0.5 * clamp(o.aLeft)));
        if(o.winner < 0)
            return(-(0.5 + (0.5 * clamp(o.bLeft))));
        return(0.5 * clamp(o.aLeft - o.bLeft));
    }

    private static double clamp(double v) {
        return(Math.max(-1.0, Math.min(1.0, v)));
    }

    /**
     * The payoff to deck A against deck B, with the tempo taken back out.
     *
     * WHOEVER ACTS FIRST WINS A LOT OF FIGHTS THAT THE DECK DID NOT. Both sides start
     * ready on tick zero and ties go to one of them, so in a mirror - identical decks,
     * identical character, identical gear - side A took five of six and one of them with
     * half its health untouched. Nothing about the deck produced that. It is the harness
     * handing one side a half-tempo every exchange, and a payoff matrix built on it would
     * rank decks by the slot they were listed in.
     *
     * So a comparison plays it both ways round and averages. A mirror then scores exactly
     * zero by construction, which is the point: the number that survives is the part that
     * came from the cards. A deck that only wins when it moves first now scores zero too,
     * and says so honestly rather than looking dominant.
     */
    public static double payoff(Combatant a, List<Move> da, Combatant b, List<Move> db,
                                int depth, long horizon) {
        double ab = payoffFirst(a, da, b, db, depth, horizon);
        double ba = payoffFirst(b, db, a, da, depth, horizon);
        return((ab - ba) / 2.0);
    }

    private Duel() {}

    /** Convenience for a deck given as an array. */
    public static List<Move> deck(Move... ms) {
        List<Move> out = new ArrayList<Move>();
        for(Move m : ms)
            out.add(m);
        return(out);
    }
}
