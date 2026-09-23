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
     * HARD hitpoints - the pool a grievous blow takes from - or NaN where nobody knows it.
     *
     * Soft hitpoints are what a fight knocks down; hard hitpoints are the lasting wound, and
     * soft can never stand above hard. A creature's pool is not measured, so it stays NaN and
     * nothing about a creature fight changes. A player's is the `hhp` the character sheet
     * reports, and against a person it is the difference between knocking them down and
     * leaving them hurt.
     */
    public double hhp = Double.NaN;

    /**
     * What our armour stopped, summed over every blow this combatant has taken - the part of
     * each swing that never reached {@link #hp} and wore the armour instead.
     *
     * Armour takes 80-82% of every blow a creature lands on us (4,335 fights, 2026-09-21), and
     * a plan priced on {@link #hp} alone sees a fifth of what hit us. Armour has durability,
     * so the rest is not free: {@link Optimizer.Plan#cost()} is where it is charged.
     */
    public double soaked;

    /**
     * For an OPPONENT: ticks from the start of the plan to its first action, or NaN for a full
     * period - the old reading, and the default.
     *
     * A full period was optimistic both ways the planner is asked. At the first card of a fight
     * the creature's first action lands a median 0.05 of its period later (1,087 solo fights,
     * 2026-09-21) - it answers at once, having closed in during our free phase - so a two-card bat
     * fight was planned with no bat action in it at all, where the log has 725 across 727 fights
     * and the model had 118. Mid-fight, at one of our cards, the wait is a median 0.58 (mean 0.68).
     * The live advice knows how long ago the creature last acted and sets this from it
     * (Prediction.firstAct); the offline tools set the engagement figure.
     */
    public double firstAct = Double.NaN;

    /**
     * For an OPPONENT: the share of its attacks that land on US, 0..1. 1 alone, and the default.
     *
     * In a party, a creature in our fight list is also in everyone else's, and swings at whoever
     * it is on. Planning the whole bat-dungeon room as though every bat swung at us predicted 3-7
     * times the soft damage that landed; planning four of them predicted half of it. The opponent
     * acts on its own clock and only this share of its actions are aimed at us, so its gaps are
     * divided by it (Optimizer.step). Live, LiveAdvice counts its cards that were followed by a
     * blow on us (COMBAT.md §3.12).
     */
    public double onUs = 1.0;

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
     * They fade while nothing lands - see {@link #decay} and Formulas.OPENING_DECAY_PER_TICK,
     * where the rate that would not resolve here was finally measured. It would not resolve
     * because it was being fitted as a time constant, and it is a rate.
     */
    public final double[] openings = new double[4];

    /**
     * How fast this combatant's openings fade, in points per tick.
     *
     * The measured rate by default. A field rather than the constant because it is a property of
     * what this combatant is doing: a combatant on the move does not decay at all (0.006 points a
     * second theirs and 0.069 ours while moving, against 0.48 standing), and a check that isolates
     * the search from decay says so here rather than in a global.
     */
    public double decayPerTick = Formulas.OPENING_DECAY_PER_TICK;

    /**
     * Openings fading over this many ticks in which nothing landed on this combatant.
     *
     * Linear and per colour, stopping at zero - see Formulas.OPENING_DECAY_PER_TICK. A caller
     * advances this across the gaps it simulates; nothing here knows what time it is.
     */
    public void decay(long ticks) {
        if((ticks <= 0) || !(decayPerTick > 0))
            return;
        double d = decayPerTick * ticks;
        for(int c = 0; c < 4; c++)
            openings[c] = (openings[c] > d) ? (openings[c] - d) : 0.0;
    }

    /**
     * What this combatant's held stance opens on whoever attacks it.
     *
     * Parry and nothing else in the sheet: it answers a blow rather than being thrown, so
     * it cannot live in a card's own openings, and the stance is not an action either. It
     * has to hang off the fighter, because the thing that needs to read it is the blow
     * landing - and the blow is resolved by {@link Sim}, which has the two fighters and
     * the attacker's card, and no idea what stance the defender chose.
     *
     * This is why it went unapplied in duels for as long as it did. Optimizer carries its
     * own copy, applied when the opponent acts, because there the opponent acts through a
     * FoeModel and never touches Sim at all. In a duel both sides go through Sim, so the
     * Optimizer's copy never ran and Parry was worth nothing but its block weight.
     */
    public final double[] whenAttacked = new double[4];

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

    /**
     * Whether a weapon is in hand, which some cards require to do anything at all.
     *
     * Parry is the case: "You need a sword equipped for Parry to inflict its effect upon
     * your opponents." The sheet keeps that in the card's notes rather than in a field, so
     * this is the nearest thing the model has to reading it.
     */
    /**
     * The range figure of whatever is in hand - 1.2 for a sword, 1.0 for a stone axe.
     *
     * NaN for bare hands, which is the same reach as 1.0: the game's range figure is a
     * multiple of the unarmed reach and the corpus agrees to a fifth of a unit. See
     * Formulas.UNARMED_REACH.
     *
     * Collected per weapon since the pack was first built and read by nothing until now,
     * which is the failure this project keeps finding: the number is there, the parser
     * stores it, and no consumer exists.
     */
    public double weaponRange = Double.NaN;

    /**
     * How far away this combatant is standing, in world units, or NaN when unknown.
     *
     * Unknown is the normal case offline and it has to stay expressible. A corpus written
     * before schema 16 measured the distance to the SAMPLED opponent only, so a crowd has
     * no positions at all in it - and a model that silently defaulted them to zero would
     * put every animal in reach of everything, which is exactly the error that made a
     * crowd free.
     */
    public double distance = Double.NaN;

    /** How far this combatant's swing reaches, in world units. */
    public double reach() {
        return(Formulas.reach(armed() ? weaponRange : Double.NaN));
    }

    public boolean armed() {
        return(weaponDamage > 0);
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

    /**
     * Damage-dealing gloves worn - Lynx Claw Gloves, Cutthroat Knuckles - as a base damage and
     * quality; 0 when none are.
     *
     * An unarmed blow in them carries the gloves' own term on top of the card's, priced the way
     * a weapon is at the gloves' quality: {@code base * sqrt(sqrt(ql * str) / 10)}, which is the
     * wiki's {@code 4 * sqrt(sqrt(strength * q_gloves) / 10)} for the lynx claws. Without it,
     * Shade's Punches in those gloves read 1.23-1.28x the model; with it the model predicts
     * 1.27-1.29x the bare card. A weapon card never reads the gloves.
     */
    public double gloveDamage, gloveQl;

    /**
     * Raw damage of one blow before armour, at this opening - the card's term and, for an
     * unarmed card, the gloves'. The one place both are summed, so Sim and FoeModel cannot
     * price the same blow two ways.
     */
    public double rawDamage(Move m, double opening) {
        double raw = Formulas.rawDamage(damageBase(m), damageShare(m), damageQuality(m), str,
                                        opening);
        if((m.damageShare <= 0) && (m.flatDamage > 0) && (gloveDamage > 0))
            raw += Formulas.rawDamage(gloveDamage, 1.0, gloveQl, str, opening);
        return(raw);
    }

    public boolean alive() {
        return(hp > 0);
    }

    public Combatant copy() {
        Combatant c = new Combatant(name);
        c.str = str; c.agi = agi; c.unarmed = unarmed; c.melee = melee;
        c.weaponDamage = weaponDamage; c.weaponQl = weaponQl; c.weaponPen = weaponPen;
        c.weaponRange = weaponRange; c.distance = distance;
        c.gloveDamage = gloveDamage; c.gloveQl = gloveQl;
        c.armHard = armHard; c.armSoft = armSoft;
        c.penetrable = penetrable;
        c.hp = hp; c.maxHp = maxHp; c.hhp = hhp;
        c.soaked = soaked;
        c.firstAct = firstAct;
        c.onUs = onUs;
        c.blockSkill = blockSkill; c.blockMult = blockMult;
        c.attackMult = attackMult;
        c.ip = ip;
        c.readyAt = readyAt;
        c.decayPerTick = decayPerTick;
        System.arraycopy(openings, 0, c.openings, 0, 4);
        System.arraycopy(whenAttacked, 0, c.whenAttacked, 0, 4);
        return(c);
    }

    public String toString() {
        return((name != null) ? name : "?");
    }
}
