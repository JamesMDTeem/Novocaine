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
     * scales as the cube root when ours changes, which {@link #act} applies.
     */
    public final double[] pressure;

    /** Our block weight when {@link #pressure} was measured, so it can be rescaled. */
    public final double pressureAgainst;

    /**
     * Damage coefficient: the WHOLE swing, before our armour, per unit of squared opening.
     *
     * Their damage follows the same shape ours does - proportional to the square of the
     * opening it reads - so one coefficient captures it without needing their strength or
     * their weapon, neither of which a log records. NaN when the corpus never saw this
     * creature land a hit, and the optimizer then refuses to report damage taken rather
     * than reporting zero.
     *
     * Fitted to SHP + ARM (the estimator's `before_armour` flag), so it describes the
     * creature rather than the gear worn that day, and our armour is applied in act() and
     * play() where the defender is known. (This comment used to describe the older fit to
     * what got through, and said the per-card soak went unread; both stopped being true.)
     */
    public final double damageCoef;

    /**
     * The share of this creature's swing our armour stopped, over all its cards, or NaN.
     *
     * Its complement is the penetration the averaged action applies. Measured on hits our
     * armour touched, it does not depend on the size of the blow: across every animal hit
     * in the corpus the median share through is about 15% for swings of 4-7, 8-15, 16-30,
     * 31-60 and 61+ alike (2026-09-14), which is penetration against armour bigger than the
     * swing - exactly what {@link Formulas#dealtDamage} models - and not a fixed soak or a
     * one-point floor. The card path reads the same thing per card, {@link BeastMove#soaked}.
     */
    public final double soakedShare;

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
     * Its actual cards, and which one it throws when.
     *
     * When this is present the model stops being an average and starts being a creature:
     * each action is one real card, applying that card's openings, its own damage, its own
     * aimed restoration and its own grievous share. Everything above - the pooled
     * pressure, the single coefficient, the flat restoration - is the fallback for
     * creatures the corpus cannot break down, and stays because some cannot.
     *
     * The CLOCK follows the card thrown: {@link #act} reports that card's measured cooldown
     * as the gap to this creature's next action, falling back to the period. That needs no
     * timer per card, because a combatant has ONE cooldown and the card thrown sets its
     * length (Fightsess draws a single atkcs/atkct) - there are no independent card timers
     * to carry.
     */
    public final Repertoire cards;

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
        this(period, pressure, pressureAgainst, damageCoef, nGaps, nHits, fleesBelow,
             modes, restores, condFeature, condCut, whenPressure, elsePressure, byColour,
             null);
    }

    public FoeModel(long period, double[] pressure, double pressureAgainst,
                    double damageCoef, int nGaps, int nHits, double fleesBelow,
                    int[] modes, double restores, String condFeature, double condCut,
                    double[] whenPressure, double[] elsePressure, double[] byColour,
                    Repertoire rep) {
        this(period, pressure, pressureAgainst, damageCoef, nGaps, nHits, fleesBelow,
             modes, restores, condFeature, condCut, whenPressure, elsePressure, byColour,
             rep, Double.NaN);
    }

    public FoeModel(long period, double[] pressure, double pressureAgainst,
                    double damageCoef, int nGaps, int nHits, double fleesBelow,
                    int[] modes, double restores, String condFeature, double condCut,
                    double[] whenPressure, double[] elsePressure, double[] byColour,
                    Repertoire rep, double soakedShare) {
        this.soakedShare = soakedShare;
        this.condFeature = condFeature;
        this.condCut = condCut;
        this.whenPressure = whenPressure;
        this.elsePressure = elsePressure;
        this.modes = (modes == null) ? new int[0] : modes;
        this.fleesBelow = fleesBelow;
        this.restores = restores;
        this.restoresByColour = byColour;
        this.cards = rep;
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
        return(act(me, myBlockWeight, self, 0, null));
    }

    /**
     * One action, throwing a real card where the corpus knows its cards.
     *
     * @param step   how many actions this creature has already taken, so the card dealt
     *               out follows the measured mix over the fight rather than being drawn
     *               at random. The search must be repeatable.
     * @param thrown running count per card, which the deal reads and this increments. Null
     *               when the caller cannot carry one: {@link Repertoire#pick} then replays
     *               the same deficit deal from `step`, so the sequence is unchanged.
     */
    public double act(Combatant me, double myBlockWeight, Combatant self, int step,
                      int[] thrown) {
        return(act(me, myBlockWeight, self, step, thrown, null));
    }

    /**
     * The same, reporting the gap to this creature's next action through {@code gapOut}.
     *
     * PER-CARD COOLDOWNS WHERE THE CORPUS MEASURED THEM. A creature that throws Bristle and
     * then Fell Scratch is not acting on one clock: the cards are back at different times,
     * and the median gap between its actions is a blend of them. Where the card it just
     * threw carries a measured cooldown, that is the gap; where it does not - a card path
     * built from our own sheet, or a creature the corpus never timed per card - the single
     * measured period stands in. The gap is written into a caller-owned slot rather than a
     * field, so this stays a function of the search node and nothing is stored on the model
     * the whole beam shares.
     */
    public double act(Combatant me, double myBlockWeight, Combatant self, int step,
                      int[] thrown, long[] gapOut) {
        if((self != null) && fleeing(self)) {
            if((gapOut != null) && (gapOut.length > 0))
                gapOut[0] = period;
            return(0);
        }
        if((cards != null) && cards.usable()) {
            int i = cards.pick(me, self, step, thrown);
            if((thrown != null) && (i < thrown.length))
                thrown[i]++;
            if((gapOut != null) && (gapOut.length > 0))
                gapOut[0] = gapFor(i);
            return(play(cards.cards[i], me, myBlockWeight, self));
        }
        if((gapOut != null) && (gapOut.length > 0))
            gapOut[0] = period;
        restore(self);
        return(act(me, myBlockWeight, pressureNow(me, self)));
    }

    /**
     * One action against a PARTY - several of us fighting this one creature together.
     *
     * The creature still has one clock and throws one card. What a party changes is who it
     * lands on. It swings at whoever is in FRONT of it (James: animals attack whoever is
     * closest or in front, so a party can choose who that is), and a card named in
     * {@code area} lands on everyone still standing. Nothing in the corpus measures a card
     * hitting two people: across about a thousand hits in group fights every card landed on
     * exactly one, and Trumpeting Fury's 2 throws hitting 3 people is the only exception - so
     * the area set is a question a caller asks, not a fact this model holds.
     *
     * The card is dealt as {@link #act} deals it, against the one in front, since that is
     * the relation whose state a learned rule reads. Its restoration lands once.
     *
     * @param dealtOut damage added per party member, indexed like {@code party}; may be null
     * @return the index of the card thrown, or -1 for the averaged action or when fleeing
     */
    public int actParty(Combatant[] party, double[] blockWeights, int front,
                        java.util.Set<String> area, Combatant self, int step, int[] thrown,
                        long[] gapOut, double[] dealtOut) {
        if((self != null) && fleeing(self)) {
            if((gapOut != null) && (gapOut.length > 0))
                gapOut[0] = period;
            return(-1);
        }
        Combatant t = party[front];
        if((cards != null) && cards.usable()) {
            int i = cards.pick(t, self, step, thrown);
            if((thrown != null) && (i < thrown.length))
                thrown[i]++;
            if((gapOut != null) && (gapOut.length > 0))
                gapOut[0] = gapFor(i);
            BeastMove m = cards.cards[i];
            restoreFrom(m, self);
            boolean wide = (area != null) && area.contains(m.name);
            for(int k = 0; k < party.length; k++) {
                if(!party[k].alive() || ((k != front) && !wide))
                    continue;
                double d = strike(m, party[k], blockWeights[k]);
                if(dealtOut != null)
                    dealtOut[k] += d;
            }
            return(i);
        }
        if((gapOut != null) && (gapOut.length > 0))
            gapOut[0] = period;
        restore(self);
        double d = act(t, blockWeights[front], pressureNow(t, self));
        if(dealtOut != null)
            dealtOut[front] += d;
        return(-1);
    }

    /** The thrown card's own measured cooldown, or the creature's single period otherwise. */
    private long gapFor(int i) {
        if((cards != null) && (i >= 0) && (i < cards.cards.length)) {
            long cd = cards.cards[i].cooldown;
            if(cd > 0)
                return(cd);
        }
        return(period);
    }

    /**
     * What one named card does, which is the whole point of having them.
     *
     * Its own openings, its own restoration aimed at its own colours, its own damage
     * through our armour, its own grievous share. A creature that throws Bristle and then
     * Chomp does two quite different things, and the average of them is a third thing that
     * never happens.
     */
    private double play(BeastMove m, Combatant me, double myBlockWeight, Combatant self) {
        restoreFrom(m, self);
        return(strike(m, me, myBlockWeight));
    }

    /** The card's own restoration, on the creature that threw it. Once per throw. */
    private static void restoreFrom(BeastMove m, Combatant self) {
        /* AIMED, AND ONLY WHERE THE CARD AIMS. Roar of the Wild takes back yellow and red
         * and leaves green and blue exactly where they were. */
        if(self != null) {
            for(int c = 0; c < 4; c++) {
                if(m.restores[c] > 0)
                    self.close(c, (m.restores[c] > 1.0) ? 1.0 : m.restores[c]);
            }
        }
    }

    /**
     * The card's openings and damage, on one person it lands on. Split from the restoration
     * so a card that hits several people restores its thrower once, not once per victim.
     */
    private double strike(BeastMove m, Combatant me, double myBlockWeight) {
        double scale = ((pressureAgainst > 0) && (myBlockWeight > 0))
            ? Math.cbrt(pressureAgainst / myBlockWeight) : 1.0;
        for(int c = 0; c < 4; c++) {
            if(m.openings[c] > 0)
                me.open(c, m.openings[c] * scale * (1.0 - me.opening(c)));
        }
        if(Double.isNaN(m.damageCoef) || (m.damageCoef <= 0))
            return(0);
        double[] o = new double[4];
        for(int c = 0; c < 4; c++)
            o[c] = me.opening(c);
        double combined = Formulas.combined(o);
        double raw = m.damageCoef * combined * combined;
        /* PER-CARD PENETRATION, MEASURED AND NOW READ. {@link BeastMove#soaked} is the share
         * of this card's swing OUR armour stopped, so its penetration is the complement -
         * which is exactly the fraction {@link Formulas#dealtDamage} expects in its
         * {@code armpen} argument. Ant Spit's measured 0.50 stops the swing half way where
         * Fell Scratch and the rest sit at 0.80-0.86, and treating every card as fully
         * soaked priced the spitter like everyone else. A card with no measured share (NaN
         * from a pack that never carried one, or 0 from our own sheet via fromOurCard)
         * keeps the old reading of fully soaked rather than inventing a penetration. */
        double armpen = ((m.soaked > 0) && (m.soaked <= 1.0)) ? (1.0 - m.soaked) : 0.0;
        double dealt = Formulas.dealtDamage(raw, me.armHard, me.armSoft, armpen);
        me.hp -= dealt;
        return(dealt);
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

    /**
     * The most one action of this opponent could take off us right now, without taking it.
     *
     * Every card it is currently throwing, each played on copies against our openings as they
     * stand, and the worst of them; the averaged action where it has no cards. A fleeing one
     * swings at nothing. Nothing passed in is changed.
     *
     * A plan's hitpoints are a total over a fight, and a total cannot see one blow: thirty
     * points open in two colours against a heavy hitter is a single swing worth a fifth of a
     * bar, spread across a long plan as though it were a drizzle. This is the one-blow view
     * the live advice holds that against.
     */
    public double worstHit(Combatant me, double myBlockWeight, Combatant self) {
        if((me == null) || ((self != null) && fleeing(self)))
            return(0);
        if((cards != null) && cards.usable()) {
            double[] mix = cards.mixNow(me, self);
            double worst = 0;
            for(int i = 0; i < cards.cards.length; i++) {
                if((i < mix.length) && !(mix[i] > 0))
                    continue;
                worst = Math.max(worst, play(cards.cards[i], me.copy(), myBlockWeight,
                                             (self == null) ? null : self.copy()));
            }
            return(worst);
        }
        return(act(me.copy(), myBlockWeight, pressureNow(me, self)));
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
         * PENETRATION IS THE CREATURE'S OWN MEASURED SHARE, where it has one. This path is
         * the average over its cards, so the soak share averaged over the same cards is
         * the consistent figure - the objection that the exception is per MOVE applies to
         * the card path, which reads BeastMove.soaked per card. Taking zero here priced
         * every creature without a card repertoire at exactly nothing, since a whole swing
         * against armour bigger than it is fully soaked. No measured share keeps zero. */
        double armpen = ((soakedShare > 0) && (soakedShare <= 1.0)) ? (1.0 - soakedShare) : 0.0;
        double raw = damageCoef * combined * combined;
        double dealt = Formulas.dealtDamage(raw, me.armHard, me.armSoft, armpen);
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
            if((m.damageShare > 0) || (m.flatDamage > 0)) {
                hitters++;
                dmg += owner.rawDamage(m, 1.0);
            }
        }
        if(acts == 0)
            return(inert());
        for(int c = 0; c < 4; c++)
            press[c] /= acts;
        /* Damage is averaged over EVERY action, not over the attacks alone. A deck that is
         * half setup swings half as often, and averaging over the hitters would price it as
         * though every tick landed a blow: act() deals this coefficient on every action of
         * the clock above, and that clock is the mean over every action too. The expected
         * damage of an action IS a fraction of a blow when some actions do not hit - the
         * estimator averages pressure over every action for the same reason.
         *
         * `hitters` gates on FLAT-damage cards as well as weapon-share ones. The share-only
         * test dropped a flat-damage card from the numerator entirely, so a deck mixing the
         * two read the share cards alone; that part of the 2026-09-13 change was right and
         * stays. Reverting the denominator to `hitters` was not (2026-09-14 review). */
        double coef = (hitters > 0) ? (dmg / acts) : Double.NaN;
        return(new FoeModel(Math.max(1, Math.round(cd / acts)), press, refBlock, coef,
                            acts, hitters));
    }

    /** An opponent that never acts - for asking "how fast could I kill it if it stood still". */
    public static FoeModel inert() {
        return(new FoeModel(Long.MAX_VALUE, new double[4], 0, Double.NaN, 0, 0));
    }
}
