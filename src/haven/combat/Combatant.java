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

    /**
     * Whether armour penetration works against this combatant's armour at all.
     *
     * "Note that some animals have armor but armor penetration doesn't work on them. Their
     * armor also doesn't break." - DDDsDD999's combat guide.
     *
     * The corpus caught this before the guide named it, and the two together are what make
     * it safe to model. Unarmed attacks are stated twice to have a flat 30% penetration,
     * but a boar soaked EXACTLY 15 from a Knock Its Teeth Out across four logged hits of
     * raw 18, 26, 35 and 42 - which is a flat hard soak with nothing bypassing it. At 30%
     * penetration the raw-18 hit would have soaked 12.6 and dealt 5.4; it dealt 3.
     *
     * So neither source was wrong and neither is sufficient: the 30% is real and this
     * boar's armour is immune to it. Defaults to false, because every armoured opponent in
     * this corpus is an animal and the one that could be tested is immune. A player's
     * armour is penetrable and the caller sets this.
     */
    public boolean penetrable = false;

    public double hp, maxHp;

    /**
     * The combat skill this combatant BLOCKS with, and the multiplier its stance puts on it.
     *
     * Split, because only the skill half equalizes. Two skills within a factor of two are
     * compared as if equal ({@link Formulas#equalize}), and the stance multiplier is not part
     * of that comparison at all - it is a plain factor on top. Lumping them into one "defence
     * weight" made the two indistinguishable, which is how a boar came to be measured as two
     * different creatures by two different moves.
     *
     * For a player, blockSkill is their Unarmed or Melee Combat, whichever their stance names,
     * and blockMult is the stance's own multiplier - 2.5 for Shield Up with a shield, 0.75 for
     * Bloodlust. For an animal, blockMult is 1: nothing in the logs shows an animal holding a
     * stance, and animals have no visible one.
     */
    public double blockSkill, blockMult = 1.0;

    /**
     * The lumped product, for reporting and for the old lumped call sites.
     *
     * Do not compute openings with this. It cannot be right on its own, because the skill half
     * of it has to pass through equalization first and the multiplier half must not.
     */
    public double defenceWeight() {
        return(blockSkill * blockMult);
    }

    /**
     * What the stance being held does to THIS combatant's own attack weight, as a factor.
     *
     * A maneuver is not only a block weight. Combat Meditation cuts every attack to 25% of
     * its normal weight while it is active, Oak Stance to 50%, and Bloodlust raises it by
     * four times its charge - so a stance is a trade, defence bought with offence, and the
     * model had no term for the offence half at all.
     *
     * 1.0 is "no stance, or a stance that does not touch attack weight", which is every
     * card in this deck today - all three sit at deck level 0, which is the only reason
     * this was harmless. It is the same shape as the bug that had mu living on the
     * character instead of the card: a real term with nowhere to go.
     */
    public double attackMult = 1.0;

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

    /**
     * Removes a share of one standing opening - what a defensive card does.
     *
     * A share of what is there, not a number of points: see {@link Move#reduces}. Nothing
     * about the attacker enters, which is why a reduction is the one combat quantity that
     * can be read straight off a log without knowing anything about the opponent.
     */
    public void close(int colour, double share) {
        if(share <= 0)
            return;
        openings[colour] *= (1.0 - ((share > 1.0) ? 1.0 : share));
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
     *
     * {@link #attackMult} is the other way round and belongs here: a stance is held by the
     * COMBATANT and scales every attack it makes.
     */
    public double attackWeight(Move m) {
        return(skill(m.weight) * m.weightMu * m.mu * attackMult);
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
        c.penetrable = penetrable;
        c.hp = hp; c.maxHp = maxHp;
        c.blockSkill = blockSkill; c.blockMult = blockMult;
        c.attackMult = attackMult;
        c.ip = ip;
        c.readyAt = readyAt;
        System.arraycopy(openings, 0, c.openings, 0, 4);
        return(c);
    }

    public String toString() {
        return((name != null) ? name : "?");
    }
}
