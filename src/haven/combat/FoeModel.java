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

    public FoeModel(long period, double[] pressure, double pressureAgainst,
                    double damageCoef, int nGaps, int nHits) {
        this.period = period;
        this.pressure = pressure;
        this.pressureAgainst = pressureAgainst;
        this.damageCoef = damageCoef;
        this.nGaps = nGaps;
        this.nHits = nHits;
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

    /** An opponent that never acts - for asking "how fast could I kill it if it stood still". */
    public static FoeModel inert() {
        return(new FoeModel(Long.MAX_VALUE, new double[4], 0, Double.NaN, 0, 0));
    }
}
