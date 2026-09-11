package haven.combat;

/**
 * A creature's cards and how often it throws each, given the state it is in.
 *
 * THIS IS THE PIECE THE MODEL WAS MISSING. What tells you anything about a creature is
 * not a colour profile bolted to the creature - that is an average over whatever cards it
 * happened to throw, and it describes nothing. What tells you something is which card it
 * is likely to throw right now, and what that card does.
 *
 * The conditional half was already measured and already thrown away. The learned rule in
 * the data pack carries the card counts on each side of its split - an ant at distance
 * reads {Ant Spit 255, Fell Scratch 5} - and the loader read only the per-colour pressure
 * summary beside it, which is the same averaging one level down.
 *
 * DETERMINISTIC, BY LARGEST REMAINDER. The search has to be repeatable, so the card
 * cannot be drawn at random; and taking the mix-weighted average of every card's effect
 * would be the aggregate again, with a Chomp's single heavy blow smeared into a
 * permanent drizzle. So the cards are dealt out in proportion, each action going to
 * whichever card is furthest behind its share. Over a fight the proportions come out at
 * the measured mix, and each individual action is a real card with its own numbers.
 */
public final class Repertoire {
    public final BeastMove[] cards;
    /** Unconditional share of actions, summing to 1 over cards. */
    public final double[] mix;

    /**
     * The learned split, where one was found: which feature, at what value, and the two
     * mixes either side of it. Null feature means no rule and {@link #mix} is used
     * throughout.
     */
    public final String condFeature;
    public final double condCut;
    public final double[] whenMix, elseMix;

    public Repertoire(BeastMove[] cards, double[] mix, String condFeature, double condCut,
                      double[] whenMix, double[] elseMix) {
        this.cards = cards;
        this.mix = mix;
        this.condFeature = condFeature;
        this.condCut = condCut;
        this.whenMix = whenMix;
        this.elseMix = elseMix;
    }

    public boolean usable() {
        return((cards != null) && (cards.length > 0) && (mix != null));
    }

    /**
     * The mix in force for the state the fight is actually in.
     *
     * Falls back to the pooled mix whenever the rule cannot be read - no rule, or a rule
     * that splits on something a Combatant does not carry. Distance is the one that
     * costs: it decides several of the learned rules and there is no notion of standing
     * apart anywhere in this simulator, so those creatures fall back to their average
     * behaviour and the report should say so rather than pretending the rule fired.
     */
    public double[] mixNow(Combatant me, Combatant self) {
        if((condFeature == null) || (whenMix == null) || (elseMix == null))
            return(mix);
        double v;
        if("foe_ip".equals(condFeature))
            v = (self == null) ? -1 : self.ip;
        else if("my_ip".equals(condFeature))
            v = (me == null) ? -1 : me.ip;
        else if("my_open".equals(condFeature))
            v = (me == null) ? -1 : (biggest(me) * 100.0);
        else if("foe_open".equals(condFeature))
            v = (self == null) ? -1 : (biggest(self) * 100.0);
        else
            return(mix);
        if(v < 0)
            return(mix);
        return((v > condCut) ? whenMix : elseMix);
    }

    /**
     * Which card it throws on action number `step`, in the state it is in.
     *
     * Largest remainder: after `step` actions each card is owed step*share of them, and
     * the one furthest short of what it is owed goes next. Ties break on the card's own
     * order so the answer never depends on how the array was built.
     */
    public int pick(Combatant me, Combatant self, int step, int[] thrown) {
        double[] m = mixNow(me, self);
        /* STATELESS, BECAUSE THE CALLER HAS NO GOOD PLACE TO KEEP THE COUNT. The first
         * version took a running tally and the search had nowhere to put one, so it
         * passed null - and with no tally every card is equally owed at every step, the
         * first one always wins, and the creature threw its opening card for the whole
         * fight. The restructure was inert and looked fine.
         *
         * So the deal is computed from the step number alone. Allocating n actions by
         * largest remainder is exact and needs no history: give each card floor(share*n),
         * then hand the leftovers to the largest fractional parts. The card thrown at
         * step n is whichever one gains a seat between n and n+1. */
        int[] at = allocate(m, step);
        int[] next = allocate(m, step + 1);
        for(int i = 0; i < cards.length; i++) {
            if(next[i] > at[i])
                return(i);
        }
        return(0);
    }

    /** Largest-remainder allocation of n actions across the mix. */
    private static int[] allocate(double[] m, int n) {
        int[] out = new int[m.length];
        double[] rem = new double[m.length];
        int given = 0;
        for(int i = 0; i < m.length; i++) {
            double exact = m[i] * n;
            out[i] = (int)Math.floor(exact);
            rem[i] = exact - out[i];
            given += out[i];
        }
        /* Hand out what rounding down left over, largest fractional part first. Ties go
         * to the earlier card so the sequence never depends on array order beyond what
         * the mix itself says. */
        for(; given < n; given++) {
            int best = -1;
            for(int i = 0; i < m.length; i++) {
                if((m[i] > 0) && ((best < 0) || (rem[i] > rem[best])))
                    best = i;
            }
            if(best < 0)
                break;
            out[best]++;
            rem[best] -= 1.0;
        }
        return(out);
    }

    private static double biggest(Combatant c) {
        double out = 0;
        for(int i = 0; i < 4; i++)
            out = Math.max(out, c.opening(i));
        return(out);
    }
}
