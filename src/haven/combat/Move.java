package haven.combat;

/**
 * One combat move, as the game describes it.
 *
 * Every field here corresponds to a line the character sheet actually prints under "Martial Arts
 * and Combat Schools", and the client's own deck dump is parsed straight into this shape by
 * {@code tools/combat/parse_deck.py}. Nothing is inferred: a move whose sheet does not mention
 * initiative has {@link #ipCost} zero because the sheet is silent, not because zero was assumed.
 *
 * Immutable, and built through {@link Builder} - a nineteen-argument constructor would be
 * unreadable at every call site and impossible to extend without breaking all of them.
 *
 * Per ADR-0002 this imports nothing from {@code haven}.
 */
public final class Move {
    /** What the sheet's own section headings call these. */
    public enum Kind {ATTACK, MANEUVER, RESTORATION}

    /**
     * Which of the character's skills feeds the move's attack weight.
     *
     * The sheet writes this as an icon followed by the multipliers - "Melee * mu",
     * "Unarmed * 75% * mu" - and closes with the note that "unless otherwise specified, weapons
     * will use your character's value in Melee Combat". WEAPON is that default; MELEE and UNARMED
     * are moves that name a skill regardless of what is held.
     */
    public enum Weight {MELEE, UNARMED, WEAPON, NONE}

    public final String res, name;
    public final Kind kind;

    /**
     * The colour of the move's own attack type, which is the opening its damage reads.
     *
     * Not the combined opening across all four colours. An earlier fit of this corpus used the
     * combined value and produced a damage coefficient that appeared to rise with Melee Combat -
     * an artefact entirely, caused by another colour standing open. -1 for a move with no attack
     * type.
     */
    public final int school;

    /**
     * Every colour this move's attack types name, which for most moves is just
     * {@link #school} again.
     *
     * Some moves carry two. Full Circle is both Sweeping and Oppressive, and Sting is
     * both Striking and Backhanded, so "the attack's own colour" is not by itself a
     * complete rule. This model reads the combined opening across a move's own types -
     * which reduces to exactly the single-colour case when there is one, and is why the
     * one-colour findings did not have to be revisited.
     *
     * Not verified for the two-colour case. Both logged Full Circles landed with their
     * Sweeping colour at zero, where combining and not combining give the same answer:
     * 35.0 predicted against 35 observed, and 38.4 against 40. Separating the two
     * readings needs a Full Circle thrown with yellow standing.
     */
    public final int[] schools;

    /** Percentage points this move opens on its target, per colour. Sheet: "Openings: +20% ...". */
    public final double[] openings;
    /** Percentage points it opens on its user. Some moves list openings against yourself. */
    public final double[] openingsSelf;

    /**
     * Percentage points it opens on the opponent WHEN THE OPPONENT SWINGS, not when played.
     *
     * Parry is the whole of this in the corpus - "When attacked: Openings: +10% Dizzy",
     * and only with a sword in hand. It is a block-weight card that sits on the bar and
     * answers a blow, so it belongs nowhere near {@link #openings}: read as one of those,
     * the optimizer put Parry into decks as an attack that opens blue for nothing.
     *
     * It lands on ONE opponent rather than the crowd, which is measured rather than
     * assumed: across 762 steps where blue rose on any of several opponents at once, it
     * rose on exactly one in 753 of them.
     */
    public final double[] whenAttackedOpens;

    /**
     * Whether this card is a STANCE - held on the bar rather than thrown, and exactly one.
     *
     * Seven cards carry a Block weight line and no card carries one alongside an Attack
     * weight: Bloodlust, Chin Up, Combat Meditation, Oak Stance, Parry, Shield Up, To Arms.
     * The decks are the reason to believe that is the signature - across 792 dumps holding
     * any cards at all, 782 hold exactly one of the seven and none holds two.
     *
     * `blockMult` is what holding it does to the block weight it grants: 2.5 for Shield Up,
     * 0.8 for Parry, 0.75 for Bloodlust. `blockSkill` is which skill that weight reads.
     */
    public final boolean stance;
    public final double blockMult;
    /**
     * What the stance needs in hand for {@link #blockMult} to be the real figure, and
     * what it falls to without it. Null and NaN when the stance asks for nothing.
     *
     * Shield Up and nothing else: 250% of the block weight holding a shield, 50% without.
     * That is a factor of five on the one number the stance exists to set, so a model
     * that reads only the headline figure prices an unshielded character as though they
     * were carrying a tower.
     */
    public final String blockRequires;
    public final double blockMultWithout;
    public final Weight blockSkill;

    /**
     * The SHARE of a standing opening this move removes from its user, per colour, 0..1.
     *
     * A fraction, not percentage points, and the corpus is unambiguous about it. Zig-Zag
     * Ruse is listed "Reduces: 50% - mu Sweeping, 50% - mu Oppressive" and at level 1 it
     * took a standing Cornered of 55 to 27, 44 to 22, 66 to 33, 26 to 13 and 32 to 16 -
     * half of whatever was there, never a flat 50 points. Reading these the way the
     * openings field is read would have subtracted 50 points from a 26 and floored it.
     *
     * Every one of the ten reduction terms in the sheet is written "N% - mu", so mu scales
     * the share directly and LINEARLY. That is the distinction that made the mu chain
     * dangerous: for an attack mu enters the weight and its effect on the recovered
     * defence weight is cubed, and here it is not.
     */
    public final double[] reduces;

    /** Fraction of the weapon's base damage, from "Damage: According to weapon * 25%". */
    public final double damageShare;
    /** Flat damage, from a sheet that prints a number: Knock Its Teeth Out's "Damage: 30". */
    public final double flatDamage;
    /** Fraction dealt as hard hitpoints, from "Grievous damage: 25%". */
    public final double grievous;

    /**
     * How many opponents the attack lands on, and what each of them takes.
     *
     * Three cards in the sheet say outright that they hit more than one. Full Circle
     * "attacks your main target and all other opponents in range", Punch 'em Both "attacks
     * both your primary target and also one other opponent in range", and Storm of Swords
     * "will attack up to five opponents in range, starting with your main target. The
     * targets will receive 100%, 125%, 150%, 175% and 200%, respectively, of the weapon's
     * damage."
     *
     * Measured, not taken on the sheet's word. Across the corpus, counting only throws made
     * with two or more opponents standing, a single-target attack raises an opening on more
     * than one of them 10 times in 566 - the background rate of somebody else swinging.
     * Full Circle does it 46 times in 143, and reaches three, four and five at once, which
     * nothing single-target can do:
     *
     *   card            throws   opened 1   opened 2+   most at once
     *   Quick Barrage      566        541          10              2
     *   Full Circle        143         53          46              5
     *
     * {@link #TARGETS_IN_RANGE} is "all of them", which is Full Circle. A bounded card
     * names its own number. {@link #targetScale} is the damage each target takes, indexed
     * from the main one, and is 1.0 everywhere except Storm of Swords - whose LATER targets
     * take more, so a crowd is worth more to it than one opponent is, twice over.
     *
     * The half this does not carry is range: Full Circle opened only one opponent in 53 of
     * the 99 throws that opened anything, which is the rest of the crowd standing too far
     * away. Nothing in this model knows where anybody is, so a multi-target card here hits
     * everything still alive - an upper bound, and it is called out as one wherever a crowd
     * is fought.
     */
    public final int targets;
    /** See {@link #targets}. Per target, from the main one; the last entry repeats. */
    public final double[] targetDamage;

    /** {@link #targets} for a card that hits every opponent in range rather than a count. */
    public static final int TARGETS_IN_RANGE = Integer.MAX_VALUE;

    /**
     * Share by which this card multiplies the opponent's single GREATEST opening, or 0.
     *
     * Opportunity Knocks and nothing else. It is kept apart from {@link #openings} because
     * it obeys neither of the two rules an openings line obeys: the cube-root weight ratio
     * and the {@code (1 - Oc)} falloff. The guide states both exclusions outright - "most
     * attacks open less the higher the opponent's openings are, but this does the
     * opposite", and "it doesn't matter what you or your opponent's UA or MC are".
     *
     * Which makes it the one card whose value RISES with the opening it finds, so it wants
     * to be thrown last in a sequence rather than first - the opposite of every other
     * opener in the deck. Filed separately so that nothing iterating openings can apply
     * the two factors it does not take.
     */
    public final double boostGreatest;

    /**
     * Initiative the move spends, from the sheet's "Initiative points: N".
     *
     * That line is a cost, not a gain, and the corpus settles it: every Knock Its Teeth Out
     * (listed at 1) drops the user's initiative by exactly one, and an opponent's Cleave (listed
     * "4+2") took them from 7 to 3. Gains are never written this way - they appear as prose,
     * which is {@link #ipGain} below.
     *
     * The second number in a "4+2" is carried separately, as {@link #ipExtra}. It is not a gain
     * to the user and not a gain to the opponent, and one observation is not enough to say what
     * it is - but it is not nothing, and dropping the line wholesale (which is what used to
     * happen) recorded Cleave and Go for the Jugular as costing no initiative at all.
     */
    public final int ipCost;
    /** Initiative the move grants its user, from prose: "gains you 1 Point of Initiative". */
    public final int ipGain;
    /** Initiative it grants the opponent, from "Opponents' initiative points: +2". */
    public final int foeIpGain;

    /**
     * Colour whose opening must exceed {@link #gainAbove} for {@link #ipGain} to apply, or -1
     * when the gain is unconditional.
     *
     * Quick Barrage is the reason this exists: "if your opponent has more than 25% of Oppressive
     * openings, Quick Barrage also gains you 1 Point of Initiative". The corpus shows exactly
     * that - a run of Quick Barrages granting nothing, then granting one apiece from the moment
     * the opponent's red crossed the threshold.
     */
    public final int gainColour;
    /** Threshold for {@link #gainColour}, as a fraction 0..1. */
    public final double gainAbove;

    public final double cooldownBase;
    /** Whether the sheet writes the cooldown as "N / mu" rather than a bare number. */
    public final boolean cooldownMu;
    /** Extra fraction of the base per initiative point held. Take Aim's "increases by 20%". */
    public final double ipScale;

    public final Weight weight;
    /** The move's own multiplier on attack weight - the "75%" in "Unarmed * 75% * mu". */
    public final double weightMu;

    /**
     * The deck weighting for THIS card, at the level its owner has it.
     *
     * mu belongs to a card, not to a character. A deck holds Quick Barrage at one level
     * and Sting at another, and the game weights each by its own level - so a single
     * per-character mu can only ever be right when every card sits at the same level,
     * which is a coincidence rather than a rule. It used to live on {@link Combatant},
     * where it silently applied one card's weighting to every card in the deck.
     *
     * 1.0 here is measured, not assumed: Take Aim - whose cooldown divides by mu - reports
     * its listed 30 exactly at level 1. The devs state the range as 1.0 to 1.5 rising with
     * the points put in, and the curve between is not known, so a levelled card cannot be
     * simulated to a point value. {@link data.Pack} therefore leaves this at 1.0, and a
     * caller that knows a card's level says so with {@link #withMu}. The Python estimator
     * carries the same fact as an interval rather than a number, deliberately.
     */
    public final double mu;

    /**
     * The trailing number of an initiative line written "4+2", or 0.
     *
     * Cleave and Go for the Jugular both write their initiative this way. The leading
     * number is the cost - an opponent's Cleave took them from 7 to 3 - and this one has
     * no established meaning, so it is carried rather than folded into {@link #ipCost} or
     * dropped. It used to be dropped: the whole line failed to parse and both moves
     * recorded a cost of zero.
     */
    public final int ipExtra;

    /**
     * If this move is a stance, what holding it does to its user's attack weight.
     *
     * 1.0 for everything that is not a stance, and for the stances that leave attacks
     * alone. Read by whoever sets {@link Combatant#attackMult}; a move does not apply this
     * to itself.
     */
    public final double attackMult;

    private Move(Builder b) {
        this.res = b.res;
        this.name = b.name;
        this.kind = b.kind;
        this.school = b.school;
        this.schools = b.schools();
        this.openings = b.openings;
        this.openingsSelf = b.openingsSelf;
        this.whenAttackedOpens = b.whenAttackedOpens;
        this.stance = b.stance;
        this.blockMult = b.blockMult;
        this.blockRequires = b.blockRequires;
        this.blockMultWithout = b.blockMultWithout;
        this.blockSkill = b.blockSkill;
        this.reduces = b.reduces;
        this.damageShare = b.damageShare;
        this.flatDamage = b.flatDamage;
        this.grievous = b.grievous;
        this.boostGreatest = b.boostGreatest;
        this.targets = b.targets;
        this.targetDamage = b.targetDamage;
        this.ipCost = b.ipCost;
        this.ipGain = b.ipGain;
        this.foeIpGain = b.foeIpGain;
        this.gainColour = b.gainColour;
        this.gainAbove = b.gainAbove;
        this.cooldownBase = b.cooldownBase;
        this.cooldownMu = b.cooldownMu;
        this.ipScale = b.ipScale;
        this.weight = b.weight;
        this.weightMu = b.weightMu;
        this.mu = b.mu;
        this.ipExtra = b.ipExtra;
        this.attackMult = b.attackMult;
    }

    /** Copy constructor for {@link #withMu}, which is the only field that varies by owner. */
    private Move(Move o, double mu) {
        this.res = o.res; this.name = o.name; this.kind = o.kind;
        this.school = o.school; this.schools = o.schools;
        this.openings = o.openings; this.openingsSelf = o.openingsSelf;
        this.whenAttackedOpens = o.whenAttackedOpens;
        this.stance = o.stance; this.blockMult = o.blockMult;
        this.blockRequires = o.blockRequires; this.blockMultWithout = o.blockMultWithout;
        this.blockSkill = o.blockSkill;
        this.reduces = o.reduces;
        this.damageShare = o.damageShare; this.flatDamage = o.flatDamage;
        this.grievous = o.grievous;
        this.boostGreatest = o.boostGreatest;
        this.targets = o.targets; this.targetDamage = o.targetDamage;
        this.ipCost = o.ipCost; this.ipGain = o.ipGain; this.foeIpGain = o.foeIpGain;
        this.gainColour = o.gainColour; this.gainAbove = o.gainAbove;
        this.cooldownBase = o.cooldownBase; this.cooldownMu = o.cooldownMu;
        this.ipScale = o.ipScale;
        this.weight = o.weight; this.weightMu = o.weightMu;
        this.ipExtra = o.ipExtra;
        this.attackMult = o.attackMult;
        this.mu = mu;
    }

    /** The same move as held by a deck that weights it differently. */
    public Move withMu(double mu) {
        return((mu == this.mu) ? this : new Move(this, mu));
    }

    /**
     * Whether the move takes the relative-agility cooldown modifier.
     *
     * Declaring an attack type is sufficient but not necessary: Opportunity Knocks has
     * none and takes the modifier anyway. What decides it is whether the card has an
     * attack skill at all - see Pack.move, which is where the two are read.
     */
    public boolean isAttack() {
        return(kind == Kind.ATTACK);
    }

    /** Whether this attack lands on anybody but the main target. */
    public boolean splashes() {
        return(targets > 1);
    }

    /**
     * The damage multiplier for the i'th target, counting the main one as zero.
     *
     * The last entry repeats, so a card that hits everything for a full swing needs one
     * entry rather than one per possible opponent.
     */
    public double targetScale(int i) {
        if((targetDamage == null) || (targetDamage.length == 0))
            return(1.0);
        return(targetDamage[Math.min(i, targetDamage.length - 1)]);
    }

    /** Whether the move deals damage at all - a maneuver that only opens has neither term set. */
    public boolean deals() {
        return((damageShare > 0) || (flatDamage > 0));
    }

    public String toString() {
        return((name != null) ? name : res);
    }

    public static Builder of(String name) {
        return(new Builder(name));
    }

    public static final class Builder {
        private String res, name;
        private Kind kind = Kind.ATTACK;
        private int school = -1;
        private int[] extra = null;
        private final double[] openings = new double[4];
        private final double[] openingsSelf = new double[4];
        private final double[] whenAttackedOpens = new double[4];
        private boolean stance = false;
        private double blockMult = 1.0;
        private String blockRequires = null;
        private double blockMultWithout = Double.NaN;
        private Weight blockSkill = null;
        private final double[] reduces = new double[4];
        private double damageShare = 0, flatDamage = 0, grievous = 0;
        private double boostGreatest = 0;
        private int targets = 1;
        private double[] targetDamage = {1.0};
        private int ipCost = 0, ipGain = 0, foeIpGain = 0, gainColour = -1;
        private double gainAbove = 0;
        private double cooldownBase = 0, ipScale = 0, weightMu = 1.0, mu = 1.0;
        private int ipExtra = 0;
        private double attackMult = 1.0;
        private boolean cooldownMu = false;
        private Weight weight = Weight.WEAPON;

        private Builder(String name) {
            this.name = name;
        }

        public Builder res(String v) {this.res = v; return(this);}
        public Builder kind(Kind v) {this.kind = v; return(this);}
        public Builder school(int v) {this.school = v; return(this);}

        /** A second (or third) attack type, for a move whose sheet lists more than one. */
        public Builder alsoSchool(int v) {
            int[] n = new int[(extra == null) ? 1 : extra.length + 1];
            if(extra != null)
                System.arraycopy(extra, 0, n, 0, extra.length);
            n[n.length - 1] = v;
            this.extra = n;
            return(this);
        }

        private int[] schools() {
            if(school < 0)
                return(new int[0]);
            if(extra == null)
                return(new int[] {school});
            int[] n = new int[extra.length + 1];
            n[0] = school;
            System.arraycopy(extra, 0, n, 1, extra.length);
            return(n);
        }
        public Builder damageShare(double v) {this.damageShare = v; return(this);}
        public Builder flatDamage(double v) {this.flatDamage = v; return(this);}
        public Builder grievous(double v) {this.grievous = v; return(this);}
        public Builder boostGreatest(double v) {this.boostGreatest = v; return(this);}

        /** See Move.targets - how many it lands on, and what each takes. */
        public Builder targets(int n, double... scale) {
            this.targets = n;
            this.targetDamage = ((scale == null) || (scale.length == 0))
                ? new double[] {1.0} : scale;
            return(this);
        }
        public Builder ipCost(int v) {this.ipCost = v; return(this);}
        public Builder ipGain(int v) {this.ipGain = v; return(this);}
        public Builder foeIpGain(int v) {this.foeIpGain = v; return(this);}
        public Builder cooldown(double v) {this.cooldownBase = v; return(this);}
        public Builder cooldownMu(boolean v) {this.cooldownMu = v; return(this);}
        public Builder ipScale(double v) {this.ipScale = v; return(this);}
        public Builder weight(Weight v) {this.weight = v; return(this);}
        public Builder weightMu(double v) {this.weightMu = v; return(this);}
        public Builder mu(double v) {this.mu = v; return(this);}
        public Builder ipExtra(int v) {this.ipExtra = v; return(this);}
        public Builder attackMult(double v) {this.attackMult = v; return(this);}

        /** Percentage points opened on the target in one colour. */
        public Builder opens(int colour, double pct) {
            this.openings[colour] = pct;
            return(this);
        }

        /** The share of a standing opening this move removes from the user, 0..1. */
        public Builder reduces(int colour, double frac) {
            this.reduces[colour] = frac;
            return(this);
        }

        /** Percentage points opened on the user in one colour. */
        public Builder opensSelf(int colour, double pct) {
            this.openingsSelf[colour] = pct;
            return(this);
        }

        /** What the stance needs in hand, and the multiplier it falls to without it. */
        public Builder blockNeeds(String what, double without) {
            this.blockRequires = what;
            this.blockMultWithout = without;
            return(this);
        }

        /** See Move.stance - held on the bar, one at a time, one always. */
        public Builder stance(boolean v, double mult, Weight skill) {
            this.stance = v;
            this.blockMult = mult;
            this.blockSkill = skill;
            return(this);
        }

        /** See Move.whenAttackedOpens - what it opens when the OPPONENT swings. */
        public Builder whenAttackedOpens(int colour, double pct) {
            this.whenAttackedOpens[colour] = pct;
            return(this);
        }

        /** Makes {@link #ipGain} conditional on the target's opening in one colour. */
        public Builder gainWhenAbove(int colour, double frac) {
            this.gainColour = colour;
            this.gainAbove = frac;
            return(this);
        }

        public Move build() {
            return(new Move(this));
        }
    }
}
