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
 * DETERMINISTIC, BY LARGEST DEFICIT. The search has to be repeatable, so the card
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
     * LARGEST DEFICIT, AGAINST THE TALLY THE CALLER CARRIES. After this action each card
     * is owed mix[i]*(step+1) of them, and the one furthest short of what it is owed goes
     * next. Ties break on the card's own order so the answer never depends on how the
     * array was built.
     *
     * WHY NOT LARGEST REMAINDER. The first version was stateless - it recomputed the deal
     * from the step number alone, allocating n actions by largest remainder (Hamilton) and
     * taking whichever card gained a seat between n and n+1. Largest remainder is not
     * house-monotone: the Alabama paradox lets a card hold a seat at n and lose it at n+1,
     * and the lost seat is never represented. The smallest counterexample is a [1/7, 3/7,
     * 3/7] mix, whose allocation is [1, 1, 1] at n=3 and [0, 2, 2] at n=4: card 1 loses
     * its seat, so it is never thrown, and 200 draws come out [57, 86, 57] against a
     * nominal [28.6, 85.7, 85.7]. At corpus scale the bear's Fell Scratch is dealt 14% of
     * actions where the measured mix says 29%. No stateless Hamilton schedule is
     * house-monotone, so the count is carried rather than re-derived.
     *
     * A null (or too-short) tally is replayed from this same rule, so a caller that cannot
     * carry one still deals the identical, deterministic sequence.
     */
    public int pick(Combatant me, Combatant self, int step, int[] thrown) {
        double[] m = mixNow(me, self);
        int[] counts = ((thrown != null) && (thrown.length >= m.length))
            ? thrown : replay(m, step);
        int best = -1;
        double bestDeficit = 0;
        for(int i = 0; (i < cards.length) && (i < m.length); i++) {
            if(m[i] <= 0)
                continue;
            double deficit = (m[i] * (step + 1)) - counts[i];
            if((best < 0) || (deficit > bestDeficit)) {
                best = i;
                bestDeficit = deficit;
            }
        }
        return((best < 0) ? 0 : best);
    }

    /**
     * The tally a caller that cannot carry one would have produced.
     *
     * Runs the deficit deal from zero for `step` actions. This is O(step) and is only
     * reached by callers that pass a null tally; the optimizer carries the real count on
     * its search node and never comes here.
     */
    private static int[] replay(double[] m, int step) {
        int[] counts = new int[m.length];
        for(int s = 0; s < step; s++) {
            int best = -1;
            double bestDeficit = 0;
            for(int i = 0; i < m.length; i++) {
                if(m[i] <= 0)
                    continue;
                double deficit = (m[i] * (s + 1)) - counts[i];
                if((best < 0) || (deficit > bestDeficit)) {
                    best = i;
                    bestDeficit = deficit;
                }
            }
            if(best < 0)
                break;
            counts[best]++;
        }
        return(counts);
    }

    private static double biggest(Combatant c) {
        double out = 0;
        for(int i = 0; i < 4; i++)
            out = Math.max(out, c.opening(i));
        return(out);
    }
}
