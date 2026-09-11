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
     *
     * THROUGH OUR ARMOUR, AND THAT IS TWO LIMITATIONS WORTH NAMING.
     *
     * Armour is not bypassed - the game applies it and the logs show it plainly. Of 3098
     * blows creatures landed on us, 2623 carry a soak figure, and for most moves the soak
     * is nearly the whole swing: Fell Scratch, Low Horn Swipe, Mule Kick and Wingbeat all
     * sit at a median soak share of 1.00, Chomp and Blood and Gore at 0.85. So the number
     * fitted here is what got through, and applying armour again in the simulator would
     * subtract it twice. That is why nothing here touches armHard or armSoft.
     *
     * The first limitation is that it is fitted to the armour we happened to be wearing.
     * Change the gear and this coefficient describes a fight that no longer happens. It
     * is a measurement of a matchup, not of the creature.
     *
     * The second is that penetration is a property of the MOVE and this is a property of
     * the creature. Matched on swing size so that a fixed soak cannot explain it - every
     * blow between 4 and 7 points - the moves cluster tightly and one does not:
     *
     *   Ant Spit         35 hits   soak share 0.50   half of it gets through
     *   Mule Kick        23        0.80
     *   Thunder Over     27        0.80
     *   Tail Splash      10        0.82
     *   Bear Down        27        0.83
     *   Chomp            10        0.83
     *   Fell Scratch    315        0.83
     *   Low Horn Swipe   37        0.83
     *
     * So Ant Spit penetrates about three times better than anything else measured, and
     * the rest are indistinguishable at these counts. Averaging a spitting ant and a
     * scratching one into a single coefficient hides that, and the hiding is invisible
     * until the armour changes. Modelling it properly needs penetration per move, which
     * the pack has no field for.
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

    /**
     * The same, split by colour, which is how the cards actually work.
     *
     * The scalar above is one share taken off everything, and four of the six restoring
     * cards in the corpus do not do that. Roar of the Wild takes back yellow and red and
     * leaves green and blue entirely alone; Careful Approach does the opposite halves;
     * Unstoppable takes green and red. Only Bristle is even across all four.
     *
     * Applying the flat share made every creature holding one of those look harder to
     * open in colours it does not defend at all - which is precisely the colour an
     * attacker should be aiming at, so the error pointed the optimizer away from the
     * right answer rather than merely blurring it.
     *
     * Null where the corpus could not split it, and {@link #restore} then falls back to
     * the scalar rather than restoring nothing.
     */
    public final double[] restoresByColour;

    /**
     * What it does depends on the state, and this is the one split the corpus can hold up.
     *
     * A creature is not a fixed mix. Fitting one split per species - chosen on the first
     * half of its cards by information gain and measured on the second - gives fourteen
     * that survive data they were not chosen on, and the branches are not close: a cave
     * angler puts 4.45 points of green on us per action while it is opened and 1.14 while
     * it is not, and a bat 4.13 of yellow with initiative in hand against 1.18 without.
     * Pooling those into one number describes a creature that never exists.
     *
     * `condFeature` is null when there is no rule, or when the rule splits on something a
     * Combatant does not carry. Distance is the one that costs: it decides four of the
     * fourteen and the simulator has no notion of standing apart.
     */
    public final String condFeature;
    public final double condCut;
    public final double[] whenPressure, elsePressure;

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
        this(period, pressure, pressureAgainst, damageCoef, nGaps, nHits, fleesBelow,
             modes, restores, null, 0, null, null);
    }

    public FoeModel(long period, double[] pressure, double pressureAgainst,
                    double damageCoef, int nGaps, int nHits, double fleesBelow,
                    int[] modes, double restores, String condFeature, double condCut,
                    double[] whenPressure, double[] elsePressure) {
        this(period, pressure, pressureAgainst, damageCoef, nGaps, nHits, fleesBelow,
             modes, restores, condFeature, condCut, whenPressure, elsePressure, null);
    }

    public FoeModel(long period, double[] pressure, double pressureAgainst,
                    double damageCoef, int nGaps, int nHits, double fleesBelow,
                    int[] modes, double restores, String condFeature, double condCut,
                    double[] whenPressure, double[] elsePressure, double[] byColour) {
        this.condFeature = condFeature;
        this.condCut = condCut;
        this.whenPressure = whenPressure;
        this.elsePressure = elsePressure;
        this.modes = (modes == null) ? new int[0] : modes;
        this.fleesBelow = fleesBelow;
        this.restores = restores;
        this.restoresByColour = byColour;
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
        return(act(me, myBlockWeight, pressureNow(me, self)));
    }

    /**
     * The pressure this creature applies in the state it is actually in.
     *
     * Falls back to the pooled figure whenever the rule cannot be read - no rule, a rule on
     * something a Combatant does not carry, or no opponent handed in. Falling back is not a
     * failure: the pooled number is what the corpus says about the creature on average, and
     * it is what this used before there were any rules at all.
     */
    public double[] pressureNow(Combatant me, Combatant self) {
        if((condFeature == null) || (whenPressure == null) || (elsePressure == null))
            return(pressure);
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
            return(pressure);
        if(v < 0)
            return(pressure);
        return((v > condCut) ? whenPressure : elsePressure);
    }

    private static double biggest(Combatant c) {
        double out = 0;
        for(int i = 0; i < 4; i++)
            out = Math.max(out, c.opening(i));
        return(out);
    }

    /** Its own openings, after the card it just threw took some of them back. */
    public void restore(Combatant self) {
        if(self == null)
            return;
        if((restoresByColour == null) && (Double.isNaN(restores) || (restores <= 0)))
            return;
        /* PER COLOUR WHERE THE CORPUS CAN SPLIT IT. The comment that used to sit here
         * said no restoring card names a colour so there was nothing to aim it at, and
         * that is no longer true - measured per colour, Roar of the Wild takes back
         * yellow and red only, Careful Approach green and blue only, Unstoppable green
         * and red. A flat share defends colours the creature does not defend, which
         * steers an attacker away from exactly the colour it should be using.
         *
         * The scalar is the fallback, not the default: where a creature was watched too
         * little to split, one share off everything is still better than nothing. */
        if(restoresByColour != null) {
            for(int c = 0; c < 4; c++) {
                double s = restoresByColour[c];
                if(s > 0)
                    self.close(c, (s > 1.0) ? 1.0 : s);
            }
            return;
        }
        double share = (restores > 1.0) ? 1.0 : restores;
        for(int c = 0; c < 4; c++)
            self.close(c, share);
    }

    public double act(Combatant me, double myBlockWeight) {
        return(act(me, myBlockWeight, pressure));
    }

    private double act(Combatant me, double myBlockWeight, double[] press) {
        double scale = (pressureAgainst > 0 && myBlockWeight > 0)
            ? Math.cbrt(pressureAgainst / myBlockWeight) : 1.0;
        for(int c = 0; c < 4; c++) {
            if(press[c] > 0)
                me.open(c, press[c] * scale * (1.0 - me.opening(c)));
        }
        if(!knowsDamage())
            return(0);
        double[] o = new double[4];
        for(int c = 0; c < 4; c++)
            o[c] = me.opening(c);
        double combined = Formulas.combined(o);
        /* THE COEFFICIENT IS THE WHOLE SWING NOW, so our armour has to come off it here.
         * It used to be fitted to the soft hitpoints that got through, which made it a
         * measurement of one matchup - this creature against the gear worn that day - and
         * meant a change of armour was silently ignored. Fitted to the swing instead, it
         * describes the creature, and the soaking belongs where the defender is known.
         *
         * Penetration is taken as zero because a creature's is unmeasured per move and
         * varies: matched on swing size, most of their moves are soaked at 0.80 to 0.83
         * while Ant Spit alone sits at 0.50. Zero is the conservative end for everything
         * except the spitters, and inventing a per-creature figure from the one exception
         * would be worse than admitting the gap. */
        double raw = damageCoef * combined * combined;
        double dealt = Formulas.dealtDamage(raw, me.armHard, me.armSoft, 0.0);
        me.hp -= dealt;
        return(dealt);
    }

    /** Whether the one period is standing in for more than one clock. */
    public boolean multiClock() {
        return(modes.length > 1);
    }

    /**
     * A person playing a deck, summarised the way a creature is measured.
     *
     * WHY A SUMMARY AND NOT A CARD-BY-CARD OPPONENT. Everything downstream - Optimizer,
     * Advisor, the deck search - takes an opponent as a clock, a per-colour pressure and
     * a damage coefficient, because that is what a corpus of fights can actually yield
     * about an animal. Deriving the same three numbers from a deck puts a player on
     * exactly the same footing, so a matchup against a person runs through the code that
     * is already checked rather than a second path that is not.
     *
     * It costs the sequencing. A real opponent picks a card for the state they are in and
     * this one throws the deck's average, so it will not set up a combination or hold a
     * finisher. That makes it a lower bound on a competent player, and the right kind of
     * wrong: a deck that survives the average is not thereby proven against the good line,
     * and nothing here should be read as saying it is.
     *
     * The three numbers, in the units the measured models use:
     *   period    mean cooldown of the deck, at no initiative - their action clock
     *   pressure  points of each colour one average action opens, at a reference block
     *             weight, with the (1 - Oc) falloff left for act() to apply
     *   damage    the coefficient of combined-opening squared, which is what rawDamage
     *             reduces to once the opening is divided out
     *
     * @param refBlock the block weight the pressure is quoted against, normally the
     *                 defender's, which makes the scale factor in act() exactly one.
     */
    public static FoeModel fromDeck(java.util.List<Move> deck, Combatant owner,
                                    double refBlock) {
        if((deck == null) || deck.isEmpty() || (owner == null) || !(refBlock > 0))
            return(inert());
        double[] press = new double[4];
        double cd = 0, dmg = 0;
        int acts = 0, hitters = 0;
        for(Move m : deck) {
            if(m.stance)
                continue;               /* held, not thrown - it is not in the rotation */
            acts++;
            cd += Formulas.cooldownTicks(m.cooldownBase, m.cooldownMu, m.mu, m.ipScale, 0,
                                         m.isAttack(), owner.agi, owner.agi);
            for(int c = 0; c < 4; c++) {
                if(m.openings[c] > 0) {
                    press[c] += Formulas.openingGainEq(
                        owner.skill(m.weight), m.weightMu * m.mu * owner.attackMult,
                        refBlock, 1.0, m.openings[c], 0.0);
                }
            }
            if(m.damageShare > 0) {
                hitters++;
                dmg += Formulas.rawDamage(owner.damageBase(m), owner.damageShare(m),
                                          owner.damageQuality(m), owner.str, 1.0);
            }
        }
        if(acts == 0)
            return(inert());
        for(int c = 0; c < 4; c++)
            press[c] /= acts;
        /* Damage is averaged over EVERY action, not over the attacks alone. A deck that is
         * half setup swings half as often, and averaging over the hitters would price it as
         * though every tick landed a blow. */
        double coef = (hitters > 0) ? (dmg / acts) : Double.NaN;
        return(new FoeModel(Math.max(1, Math.round(cd / acts)), press, refBlock, coef,
                            acts, hitters));
    }

    /** An opponent that never acts - for asking "how fast could I kill it if it stood still". */
    public static FoeModel inert() {
        return(new FoeModel(Long.MAX_VALUE, new double[4], 0, Double.NaN, 0, 0));
    }
}
