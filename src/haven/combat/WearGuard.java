package haven.combat;

import java.util.List;

/**
 * When to stop hitting and close an opening instead, priced in what our ARMOUR wears.
 *
 * WHY THIS EXISTS (James, 2026-09-27): "on easier fights we're keeping 40/50+ openings into mobs
 * which is resulting in mass amounts of armor damage. I'm fine with fights taking a bit longer at
 * the saving of multiples of armor damage." The logs agree: in cave louse and green ooze fights,
 * blows landing while our largest opening stood at 30 or more were a fifth of the blows and over
 * half of all the armour soaked, and armour per blow roughly triples from the 10s to the 40s -
 * damage goes as the square of the opening.
 *
 * WHY NOT THE PLANNER. The live advice plans a whole fight and throws its first card. Against three
 * lice that plan is ninety seconds long, a restoration moves its total by a few percent, and the
 * first card of the fastest line inside the budget is almost never one. Pricing armour in the plan
 * (Optimizer.armourWeight) with a slower line allowed, and a fourth beam end keeping the lines least
 * open on us, were both tried and changed nothing, or made it worse, in closed-loop runs
 * (Optimizer.play, 2026-09-27) - and were taken back out.
 *
 * WHY NOT A THRESHOLD. A fixed "restore past 40-50% in the colours they hit" halved the wear against
 * three lice (Quick Barrage alone 972 armour in 95 s; Quick Dodge whenever green passed 50, 475 in
 * 107 s) and made five of them WORSE, longer and more worn: five lice reopen us faster than a dodge
 * closes, so the dodge only moved the kill later while they kept swinging. Whether closing an opening
 * pays depends on how fast it comes back and how much fight is left.
 *
 * THE RULE: WALK THE FIGHT BOTH WAYS ({@link #walk}) - the plan's own line from here, and the same
 * line with a restoration thrown first - and take the restoration when the share of the fight's wear
 * it saves is at least {@link #RATIO} times the share of time it adds. One step of policy improvement
 * over the plan: its line is the base policy, only the first card is varied, and it costs one walk
 * per restoration on the bar rather than a search. A card closing a colour nobody hits saves nothing
 * and is never taken - against lice, which open green, Quick Dodge answers and Zig-Zag Ruse does not.
 *
 * Measured closed-loop against Shade's 2026-09-26 louse-fight character (Optimizer.play, the live
 * advice re-planning every card, with and without this; creature clocks carried between re-plans):
 * 2 lice 40% less wear for 33% more time, 3 lice 12% for 13%, 2 wolves 23% for 5%; no change against
 * four oozes or a polar bear (short fights, nothing to save); five lice 1.3% MORE wear at the same
 * time - they reopen us faster than a dodge closes, and the walk's one-step view misses it by a hair.
 * At ratio 1.5 four oozes came out 5% worse, so 2 it is.
 *
 * Pure: the model's own combatants and cards. Prediction.adviseLive applies it against creatures, and
 * tools run the same method in closed-loop fights.
 */
public final class WearGuard {
    private WearGuard() {}

    /** How open we must stand to what they throw, 0..1, before the walks are paid for. */
    public static final double OPEN = 0.20;
    /** How many times the share of time added the share of wear saved must be - see the class notes. */
    public static final double RATIO = 2.0;

    /** The answer: the card to throw, or null to go on with the plan, and the figures behind it. */
    public static final class Call {
        public final Move move;
        /** Wear (hitpoints plus armour) over the rest of the fight, on the plan's line and with the card. */
        public final double plan, with;
        /** How open we stand to the worst of them, 0..1, as FoeModel.expectedSwing reads it. */
        public final double open;

        Call(Move move, double plan, double with, double open) {
            this.move = move;
            this.plan = plan;
            this.with = with;
            this.open = open;
        }
    }

    /**
     * Whether to throw a restoration before going on with the plan - see the class notes.
     *
     * Wear is hitpoints plus armour, one for one (James: a slower fight "at the saving of multiples
     * of armor damage" is a trade he takes). {@code open} still gates it, so a fight where we stand
     * closed never pays for the walks.
     *
     * @param us    our side as it stands
     * @param foes  everything that may swing at us, the plan's target first; running ones act never
     * @param myIp  our initiative against each of {@code foes}
     * @param line  the plan's line from here; it is cycled so that a line delayed by the
     *              restoration still runs to the kill
     * @param ratio how many times the time share the wear share must be: 1 takes 10% longer for 10%
     *              less wear
     */
    public static Call walk(Combatant us, Combatant[] foes, FoeModel[] models, List<Move> deck,
                            int[] myIp, List<Move> line, double open, double ratio, long maxTicks) {
        double[] rms = new double[1];
        double worst = 0, swing = 0;
        for(int i = 0; i < foes.length; i++) {
            if((foes[i] == null) || !foes[i].alive() || (models[i] == null))
                continue;
            swing += share(foes[i]) * models[i].expectedSwing(us, us.defenceWeight(), foes[i], rms);
            worst = Math.max(worst, rms[0]);
        }
        if((line == null) || line.isEmpty() || !(swing > 0) || (worst < open))
            return(new Call(null, swing, swing, worst));
        List<Move> base = cycled(null, line);
        Optimizer.Plan b = Optimizer.follow(us, foes, models, deck, base, maxTicks, myIp, null);
        double wb = wear(b);
        /* ONLY WHERE THE PLAN WINS. A line that does not kill ends at our death or the horizon, and
         * its "wear" is then how long we lasted: a restoration that keeps us up longer soaks more and
         * reads worse. A fight we are losing is the hitpoint reserve's question, not this one. */
        if(Double.isNaN(wb) || !b.killed)
            return(new Call(null, swing, swing, worst));
        Move best = null;
        double bestGain = 0, bestWear = wb;
        for(Move m : deck) {
            /* ONLY A CARD THAT CLOSES SOMETHING STANDING. One that closes nothing still "saves" in
             * the walk - it spends its cooldown standing still, so our openings decay and the swings
             * land later - and without this Zig-Zag Ruse was thrown at lice with our yellow and red
             * at zero. Waiting is not a card. */
            if(!closesStanding(m, us))
                continue;
            Optimizer.Plan a = Optimizer.follow(us, foes, models, deck, cycled(m, line), maxTicks, myIp, null);
            if(a.moves.isEmpty() || (a.moves.get(0) != m) || !a.killed)
                continue;
            double wa = wear(a);
            if(Double.isNaN(wa) || !(wa < wb - 1.0))
                continue;
            double saved = (wb - wa) / Math.max(1e-9, wb);
            double slower = (double)(a.ticks - b.ticks) / Math.max(1, b.ticks);
            if((slower > 0) && (saved < ratio * slower))
                continue;
            double gain = saved - (ratio * Math.max(0, slower));
            if((best == null) || (gain > bestGain)) {
                best = m;
                bestGain = gain;
                bestWear = wa;
            }
        }
        return(new Call(best, wb, bestWear, worst));
    }

    /* Long enough for any walk to reach the kill or the horizon; follow stops at either. */
    private static final int WALK_LEN = 400;

    private static List<Move> cycled(Move first, List<Move> line) {
        List<Move> out = new java.util.ArrayList<Move>(WALK_LEN + 1);
        if(first != null)
            out.add(first);
        while(out.size() < WALK_LEN)
            out.addAll(line);
        return(out);
    }

    private static double wear(Optimizer.Plan p) {
        return(p.hpLost + p.soaked);
    }

    /** The share of its swings aimed at us - see Combatant.onUs. */
    private static double share(Combatant f) {
        return(((f.onUs > 0) && (f.onUs < 1.0)) ? f.onUs : 1.0);
    }

    /** Whether a card closes at least a point of an opening standing on {@code us}. */
    static boolean closesStanding(Move m, Combatant us) {
        for(int c = 0; c < 4; c++) {
            if((m.reduces[c] > 0) && (us.opening(c) >= 0.01))
                return(true);
        }
        return(false);
    }

    /** Whether a card closes any of our own openings. */
    public static boolean restores(Move m) {
        for(int c = 0; c < 4; c++) {
            if(m.reduces[c] > 0)
                return(true);
        }
        return(false);
    }
}
