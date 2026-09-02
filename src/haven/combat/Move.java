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

    /** Fraction of the weapon's base damage, from "Damage: According to weapon * 25%". */
    public final double damageShare;
    /** Flat damage, from a sheet that prints a number: Knock Its Teeth Out's "Damage: 30". */
    public final double flatDamage;
    /** Fraction dealt as hard hitpoints, from "Grievous damage: 25%". */
    public final double grievous;

    /**
     * Initiative the move spends, from the sheet's "Initiative points: N".
     *
     * That line is a cost, not a gain, and the corpus settles it: every Knock Its Teeth Out
     * (listed at 1) drops the user's initiative by exactly one, and an opponent's Cleave (listed
     * "4+2") took them from 7 to 3. Gains are never written this way - they appear as prose,
     * which is {@link #ipGain} below.
     *
     * The second number in a "4+2" is not accounted for here. It is not a gain to the user and
     * not a gain to the opponent, and one observation is not enough to say what it is.
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

    private Move(Builder b) {
        this.res = b.res;
        this.name = b.name;
        this.kind = b.kind;
        this.school = b.school;
        this.schools = b.schools();
        this.openings = b.openings;
        this.openingsSelf = b.openingsSelf;
        this.damageShare = b.damageShare;
        this.flatDamage = b.flatDamage;
        this.grievous = b.grievous;
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
    }

    /** Whether the move takes the relative-agility cooldown modifier. Attacks do; maneuvers do not. */
    public boolean isAttack() {
        return(kind == Kind.ATTACK);
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
        private double damageShare = 0, flatDamage = 0, grievous = 0;
        private int ipCost = 0, ipGain = 0, foeIpGain = 0, gainColour = -1;
        private double gainAbove = 0;
        private double cooldownBase = 0, ipScale = 0, weightMu = 1.0;
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
        public Builder ipCost(int v) {this.ipCost = v; return(this);}
        public Builder ipGain(int v) {this.ipGain = v; return(this);}
        public Builder foeIpGain(int v) {this.foeIpGain = v; return(this);}
        public Builder cooldown(double v) {this.cooldownBase = v; return(this);}
        public Builder cooldownMu(boolean v) {this.cooldownMu = v; return(this);}
        public Builder ipScale(double v) {this.ipScale = v; return(this);}
        public Builder weight(Weight v) {this.weight = v; return(this);}
        public Builder weightMu(double v) {this.weightMu = v; return(this);}

        /** Percentage points opened on the target in one colour. */
        public Builder opens(int colour, double pct) {
            this.openings[colour] = pct;
            return(this);
        }

        /** Percentage points opened on the user in one colour. */
        public Builder opensSelf(int colour, double pct) {
            this.openingsSelf[colour] = pct;
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
