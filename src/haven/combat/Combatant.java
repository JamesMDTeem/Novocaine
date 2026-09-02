package haven.combat;

/**
 * One side of a fight: the stats a move reads, and the state a move changes.
 *
 * Mutable, because a fight is a sequence of mutations and copying the whole world on every move
 * would dominate an optimizer's search. {@link #copy()} exists for the callers that do want to
 * branch.
 *
 * Units are the units the game shows, not the units the formulas want. Openings are 0..100 here
 * because that is what the character sheet prints, what the combat UI displays and what the
 * telemetry log records; {@link #opening(int)} is the single place that converts to the 0..1
 * fraction {@link Formulas} takes. Keeping that conversion in one method is deliberate - mixing
 * the two units silently is the error that cost this project its first damage fit.
 *
 * Per ADR-0002 this imports nothing from {@code haven}.
 */
public final class Combatant {
    public String name;

    /* Attributes as the server fights with them: gear and buffs already applied, which is the
     * "attr" map in a log header rather than the unmodified "attrb" sheet. */
    public double str, agi, unarmed, melee;

    /** Base damage, quality and armour penetration (0..1) of the weapon in hand. */
    public double weaponDamage, weaponQl, weaponPen;

    /** Armour soak, hard and soft, as the equipment window totals them. */
    public double armHard, armSoft;

    public double hp, maxHp;

    /**
     * The weight the defender opposes an attacker's weight with.
     *
     * This is an input, not something derived from the attributes above: no formula relating a
     * creature's stats to its defence weight has been established. For an animal it comes from
     * {@link Formulas#defenceWeight} applied to one clean logged attack; for a player it comes
     * from whichever defensive stance they are holding.
     */
    public double defenceWeight;

    /**
     * Openings standing on this combatant, 0..100, indexed by {@link Formulas#GREEN} and friends.
     *
     * Not decayed here, and that is a gap rather than a finding. Openings plainly do decay: the
     * bee-swarm log drops 43 to 42 and 67 to 66 mid-fight, and drops the attacker's own 7 to 6,
     * 12 to 11 and 18 to 17. But the rate will not resolve. Those five drops imply anything from
     * 0.9 to 4.1 points per second with no consistent dependence on the standing value, and the
     * badger log flatly contradicts all of them by holding 75 and 42 unchanged for 6.1 seconds
     * and a boar log by holding 25, 24 and 17 for 3.7.
     *
     * So there is a rule here that this corpus cannot see - most likely a condition on when the
     * timer runs at all rather than a rate. Simulating no decay is wrong in a knowable direction:
     * it overstates a long fight's accumulated openings, and therefore overstates damage late in
     * one. Guessing a constant would be wrong in an unknowable direction, which is worse.
     */
    public final double[] openings = new double[4];

    public int ip;

    /**
     * The tick at which this combatant may act again.
     *
     * One gate for the whole deck, not one per card: the server sends a single cooldown with each
     * use, which the client keeps as one interval in {@code Fightview.atkcs}/{@code atkct}, and
     * the corpus agrees - a run of Quick Barrages arrives every 18 ticks with nothing else
     * interleaved.
     */
    public long readyAt;

    public Combatant(String name) {
        this.name = name;
    }

    /** The opening in one colour as the 0..1 fraction the formulas take. */
    public double opening(int colour) {
        return(openings[colour] / 100.0);
    }

    /** Raises one opening by a number of percentage points, clamped at a fully open 100. */
    public void open(int colour, double pct) {
        double v = openings[colour] + pct;
        openings[colour] = (v > 100.0) ? 100.0 : ((v < 0.0) ? 0.0 : v);
    }

    /** The skill feeding a move's attack weight. */
    public double skill(Move.Weight w) {
        switch(w) {
        case UNARMED:
            return(unarmed);
        case MELEE:
        case WEAPON:
            /* "Unless otherwise specified, weapons will use your character's value in Melee
             * Combat for their attack weight." */
            return(melee);
        default:
            return(0);
        }
    }

    /**
     * A move's attack weight: the skill it names, times its own multiplier, times its mu.
     *
     * The deck weighting is read off the MOVE, not off this combatant. It is a property of a
     * card at the level its owner holds it, so two cards in one deck can carry different ones;
     * keeping it here applied whichever card was measured last to the whole deck.
     */
    public double attackWeight(Move m) {
        return(skill(m.weight) * m.weightMu * m.mu);
    }

    /**
     * The quality term in the damage formula, which is not always a quality.
     *
     * A move that deals a share of the weapon's damage uses the weapon's quality. A move that
     * lists a flat number is unarmed and has no weapon to read, and the game substitutes the
     * character's strength: the damage term collapses from {@code sqrt(sqrt(ql * str) / 10)} to
     * {@code sqrt(str / 10)}. Measured, not assumed - Knock Its Teeth Out at a listed 30 damage
     * and strength 82 predicts a coefficient of 85.9, against nine logged observations spanning a
     * bee swarm, a fox and a boar that fit 86.1, 90.7, 86.8 and, on the boar's four, 85.7 to 86.0.
     */
    public double damageQuality(Move m) {
        return((m.damageShare > 0) ? weaponQl : str);
    }

    /** The base damage a move starts from: the weapon's, or the flat number the move lists. */
    public double damageBase(Move m) {
        return((m.damageShare > 0) ? weaponDamage : m.flatDamage);
    }

    /** The share of that base the move applies. A flat-damage move applies all of it. */
    public double damageShare(Move m) {
        return((m.damageShare > 0) ? m.damageShare : 1.0);
    }

    public boolean alive() {
        return(hp > 0);
    }

    public Combatant copy() {
        Combatant c = new Combatant(name);
        c.str = str; c.agi = agi; c.unarmed = unarmed; c.melee = melee;
        c.weaponDamage = weaponDamage; c.weaponQl = weaponQl; c.weaponPen = weaponPen;
        c.armHard = armHard; c.armSoft = armSoft;
        c.hp = hp; c.maxHp = maxHp;
        c.defenceWeight = defenceWeight;
        c.ip = ip;
        c.readyAt = readyAt;
        System.arraycopy(openings, 0, c.openings, 0, 4);
        return(c);
    }

    public String toString() {
        return((name != null) ? name : "?");
    }
}
