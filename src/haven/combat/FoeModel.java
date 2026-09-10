package haven.combat;

/**
 * What an opponent does to us, as a thing the optimizer can plan against.
 *
 * The simulator has always been one-sided: {@link Sim} applies a move and says what it did,
 * and nothing ever made the opponent act. That is fine for measuring - every estimator in
 * this project reads our attacks on them - and useless for optimizing, because "how much
 * damage do I take" is the whole question and it has no answer without a foe that swings
 * back.
 *
 * Every field here is measured from logs or is explicitly absent. Nothing is a guess with a
 * plausible default, because a plausible default for the opponent's damage would silently
 * decide the matchup the optimizer exists to decide.
 *
 * Per ADR-0002 this imports nothing from {@code haven}.
 */
public final class FoeModel {
    /** Ticks between the opponent's actions. Measured as the median gap between its moves. */
    public final long period;

    /**
     * Percentage points this opponent opens on us per action, per colour, before falloff.
     *
     * This is the opening PRESSURE measured in {@code estimate.py}: gain / (1 - Oc), which
     * is fully observed and needs nothing about the creature's own weight. It is measured
     * against a particular defence weight of ours - see {@link #pressureAgainst} - and
     * scales as the cube root when ours changes, which {@link #openingsOn} applies.
     */
    public final double[] pressure;

    /** Our block weight when {@link #pressure} was measured, so it can be rescaled. */
    public final double pressureAgainst;

    /**
     * Damage coefficient: SHP through our armour per unit of squared opening.
     *
     * Their damage follows the same shape ours does - proportional to the square of the
     * opening it reads - so one coefficient captures it without needing their strength or
     * their weapon, neither of which a log records. NaN when the corpus never saw this
     * creature land a hit, and the optimizer then refuses to report damage taken rather
     * than reporting zero.
     */
    public final double damageCoef;

    /** How many observations each figure rests on, for the report to carry. */
    public final int nGaps, nHits;

    /**
     * The distinct clocks this creature was seen acting on, shortest first, or empty.
     *
     * {@link #period} is a mean, which is the right single number for a rate - over T
     * ticks it acts T/period times, and that identity holds however its cooldowns are
     * distributed. But cattle acts on two clocks, 22 ticks and 38, and a lone "33.3" is a
     * number it never once exhibited. Nothing computes with this; it exists so a report
     * that prints one number can say when that number is a blend, rather than letting a
     * reader assume a creature is regular because the field it was given had room for
     * only one value.
     */
    public final int[] modes;

    /**
     * The share of its hitpoints below which it runs instead of fighting.
     *
     * An animal that has taken enough extends an olive branch and flees, and a fleeing
     * animal STOPS FIGHTING BACK. That is not a detail: every point of initiative built
     * after that moment, and every opening closed, is spent on nothing. A plan that keeps
     * defending into a flight is buying protection from an opponent that has stopped
     * swinging.
     *
     * It is visible in a log - the aggression state's second bit is the opponent's olive
     * branch, which schema 7 records - so this is measurable rather than assumed. NaN when
     * the corpus has not seen this species flee, and the model then fights it to zero.
     */
    public final double fleesBelow;

    /**
     * The SHARE of its own standing openings it takes back, per action. NaN if unmeasured.
     *
     * ITS CARDS RESTORE AND NOTHING HERE KNEW. Six of them do it and the rest do not:
     * Unstoppable closes 25.2 points a use, Bristle 16.4, Swift Evasion 11.9, Rampant Rage
     * 7.5, Careful Approach 4.9, Roar of the Wild 3.4 - against 0.04 across 412399 brackets
     * where nothing acted at all, which is what decay is worth. Attacking cards sit at 0.1.
     *
     * Left out, the model keeps a creature more open than it really is for the whole fight,
     * and every opening feeds a damage term that squares it. That is optimism in the one
     * direction a matchup must not be optimistic in.
     *
     * A SHARE, not a number of points, which is the shape our own reductions already take.
     * Splitting each restoring card by how much was standing when it landed settles it:
     * Bristle takes 1.0, 9.0 and 21.0 points as the standing total rises through the bands
     * 1-25, 25-60 and 60+, and 0.17, 0.20 and 0.20 of it. The points climb; the share does
     * not.
     *
     * Averaged over every action, matching the period and the pressure beside it: a
     * creature that spends a third of its turns on Bristle undoes rather more than one
     * that never throws it, and the mix is already in that average.
     */
    public final double restores;

    public FoeModel(long period, double[] pressure, double pressureAgainst,
                    double damageCoef, int nGaps, int nHits) {
        this(period, pressure, pressureAgainst, damageCoef, nGaps, nHits, Double.NaN);
    }

    public FoeModel(long period, double[] pressure, double pressureAgainst,
                    double damageCoef, int nGaps, int nHits, double fleesBelow) {
        this(period, pressure, pressureAgainst, damageCoef, nGaps, nHits, fleesBelow,
             new int[0]);
    }

    public FoeModel(long period, double[] pressure, double pressureAgainst,
                    double damageCoef, int nGaps, int nHits, double fleesBelow,
                    int[] modes) {
        this(period, pressure, pressureAgainst, damageCoef, nGaps, nHits, fleesBelow,
             modes, Double.NaN);
    }

    public FoeModel(long period, double[] pressure, double pressureAgainst,
                    double damageCoef, int nGaps, int nHits, double fleesBelow,
                    int[] modes, double restores) {
        this.modes = (modes == null) ? new int[0] : modes;
        this.fleesBelow = fleesBelow;
        this.restores = restores;
        this.period = period;
        this.pressure = pressure;
        this.pressureAgainst = pressureAgainst;
        this.damageCoef = damageCoef;
        this.nGaps = nGaps;
        this.nHits = nHits;
    }

    /** Whether this opponent has given up and is running, and so has stopped hitting us. */
    public boolean fleeing(Combatant foe) {
        return(!Double.isNaN(fleesBelow) && (foe.maxHp > 0)
               && ((foe.hp / foe.maxHp) < fleesBelow));
    }

    /** Whether this model can say anything about damage taken. */
    public boolean knowsDamage() {
        return(!Double.isNaN(damageCoef) && (nHits > 0));
    }

    /**
     * Applies one of the opponent's actions to us.
     *
     * Openings first, then damage against the openings that are now standing - the same
     * order {@link Sim#use} does not use, and deliberately. Our own moves read the opening
     * BEFORE they open further, because Take Aim's ladder settles the sequencing for us.
     * Nothing settles it for the opponent, so this takes the pessimistic reading: their
     * damage benefits from the opening they just made. An optimizer that is wrong here is
     * wrong towards caution.
     */
    public double act(Combatant me, double myBlockWeight, Combatant self) {
        if((self != null) && fleeing(self))
            return(0);
        restore(self);
        return(act(me, myBlockWeight));
    }

    /** Its own openings, after the card it just threw took some of them back. */
    public void restore(Combatant self) {
        if((self == null) || Double.isNaN(restores) || (restores <= 0))
            return;
        /* The same share off every colour, which is what a share of the standing total
         * means and what close() already does for one colour on our own side. None of the
         * restoring cards names a colour in the corpus, so there is nothing to aim it at. */
        double share = (restores > 1.0) ? 1.0 : restores;
        for(int c = 0; c < 4; c++)
            self.close(c, share);
    }

    public double act(Combatant me, double myBlockWeight) {
        double scale = (pressureAgainst > 0 && myBlockWeight > 0)
            ? Math.cbrt(pressureAgainst / myBlockWeight) : 1.0;
        for(int c = 0; c < 4; c++) {
            if(pressure[c] > 0)
                me.open(c, pressure[c] * scale * (1.0 - me.opening(c)));
        }
        if(!knowsDamage())
            return(0);
        double[] o = new double[4];
        for(int c = 0; c < 4; c++)
            o[c] = me.opening(c);
        double combined = Formulas.combined(o);
        double dealt = damageCoef * combined * combined;
        me.hp -= dealt;
        return(dealt);
    }

    /** Whether the one period is standing in for more than one clock. */
    public boolean multiClock() {
        return(modes.length > 1);
    }

    /** An opponent that never acts - for asking "how fast could I kill it if it stood still". */
    public static FoeModel inert() {
        return(new FoeModel(Long.MAX_VALUE, new double[4], 0, Double.NaN, 0, 0));
    }
}
