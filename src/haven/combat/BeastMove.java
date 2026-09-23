package haven.combat;

/**
 * One card a creature throws, with what that card does.
 *
 * WHY THIS EXISTS AT ALL. Our side has always been modelled card by card and the other
 * side was a single averaged action - one clock, one per-colour pressure, one damage
 * coefficient, one restoration share. That was not a simplification anyone chose; it was
 * all the data supported, because an animal's card has no published percentages.
 *
 * It is no longer all the data supports, and every quantity it flattened turns out to
 * vary by more than the average can carry:
 *
 *   damage        Shredding Paw 143 against Vampirism 9, sixteen times over
 *   restoration   aimed, not spread - Roar of the Wild takes back yellow and red and
 *                 leaves green and blue completely alone, Careful Approach the opposite
 *                 halves, and only Bristle is even across all four
 *   grievous      three cards leave a lasting wound and every other reads exactly zero
 *   armour        most are soaked at 0.80 to 0.83 and Ant Spit alone at 0.50
 *
 * A creature-level average of any of those describes an animal that does not exist. The
 * aimed restoration is the worst of them, because it defends colours the creature does
 * not defend - which points an attacker away from the colour it should be using, rather
 * than merely blurring the answer.
 *
 * WHAT IS MEASURED AND WHAT IS NOT. The damage, the cooldown, the restoration and the
 * soak are all measured from the corpus in their own units. The openings are RATIOS: the
 * fit that separates a card from the creature throwing it has a gauge freedom, so
 * multiplying every card's percentage by a constant and dividing every species factor by
 * the same constant fits identically. They are scaled by the creature's own factor at
 * load, which puts them back on the scale the pressure was measured in.
 */
public final class BeastMove {
    public final String name;
    /** Percentage points it opens on us, per colour, before the falloff. */
    public final double[] openings;
    /** Whole-swing damage per unit of squared combined opening. NaN when unmeasured. */
    public final double damageCoef;
    /** Ticks before this card can come round again, or 0 where unmeasured. */
    public final long cooldown;
    /** The share of ITS OWN standing openings it takes back, per colour. */
    public final double[] restores;
    /** Hard hitpoints per soft hitpoint - the lasting wound. */
    public final double grievous;
    /** The share of its swing our armour stopped, where the corpus could match it. */
    public final double soaked;
    /**
     * The colours of ours its damage reads, or null where nobody knows - then all four are read.
     *
     * An attack's damage comes off the opening in ITS colours only. Of 1886 animal hits in the
     * corpus none landed with nothing open in the card's colours, and read against all four a
     * card's blow scatters: the cave angler's Shredding Paw sits at a 90th percentile 1.65 times
     * its median on all four colours and 1.04 on blue alone (2026-09-16).
     */
    public final boolean[] attackColours;

    /**
     * Its cooldown at an agility factor of one, or NaN where the corpus could not divide ours out.
     *
     * ITS ATTACKS RUN ON OUR AGILITY as ours run on its (2026-09-23): the same Fell Scratch comes
     * round in 40 ticks against a slow character and 49 against a fast one, and 44 at factor one
     * in every band. {@link #cooldown} is the blend over whoever fought it; this is the card.
     */
    public final double cooldownBase;
    /** Whether {@link #cooldownBase} rides the agility rule - an attack does, a maneuver does not. */
    public final boolean agilityScaled;
    /**
     * Initiative this card adds to its thrower's pool against us, and what it spends from it.
     *
     * CREATURES PAY FOR THEIR BIG CARDS (2026-09-23). Fell Scratch earns a point and Bear Down two;
     * Bristle spends three, Tail Splash four, Chomp two - and a spender is never thrown short of
     * its cost (Bristle below 3 in 3% of 755 throws, the stale state after an update). So the cost
     * is also the requirement, and a card the creature cannot pay for is not in its hand.
     */
    public final int ipGain, ipCost;

    public BeastMove(String name, double[] openings, double damageCoef, long cooldown,
                     double[] restores, double grievous, double soaked) {
        this(name, openings, damageCoef, cooldown, restores, grievous, soaked, null);
    }

    public BeastMove(String name, double[] openings, double damageCoef, long cooldown,
                     double[] restores, double grievous, double soaked, boolean[] attackColours) {
        this(name, openings, damageCoef, cooldown, restores, grievous, soaked, attackColours,
             Double.NaN, false, 0, 0);
    }

    public BeastMove(String name, double[] openings, double damageCoef, long cooldown,
                     double[] restores, double grievous, double soaked, boolean[] attackColours,
                     double cooldownBase, boolean agilityScaled, int ipGain, int ipCost) {
        this.cooldownBase = cooldownBase;
        this.agilityScaled = agilityScaled;
        this.ipGain = Math.max(0, ipGain);
        this.ipCost = Math.max(0, ipCost);
        this.attackColours = attackColours;
        this.name = name;
        this.openings = (openings == null) ? new double[4] : openings;
        this.damageCoef = damageCoef;
        this.cooldown = cooldown;
        this.restores = (restores == null) ? new double[4] : restores;
        this.grievous = grievous;
        this.soaked = soaked;
    }

    /**
     * Whether this card is known to do anything at all - including to ITSELF.
     *
     * A restoring card opens nothing and deals nothing, and testing only for those two
     * dropped every one of them out of the repertoire. That is not a small omission: more
     * than half a boreworm's actions are Roar of the Wild, so a mix without it has the
     * creature attacking on turns it actually spends defending, and reads as roughly
     * twice as aggressive as it is.
     *
     * A card that does none of the three is one we know the name of and nothing else, and
     * including it would have the creature spending real turns doing nothing measurable -
     * which is a different lie in the other direction.
     */
    public boolean acts() {
        for(int c = 0; c < 4; c++) {
            if((openings[c] > 0) || (restores[c] > 0))
                return(true);
        }
        return(!Double.isNaN(damageCoef) && (damageCoef > 0));
    }

    /**
     * The gap this card sets when thrown by a creature of agility {@code agiIt} at us at {@code agiUs}.
     *
     * The measured base scaled by clamp(agiUs/agiIt, 1/2, 2)^(1/7) and rounded, as our own attacks
     * are ({@link Formulas#cooldownTicks}); the blended {@link #cooldown} wherever either agility or
     * the base is unknown, or the card is a maneuver. 0 means unmeasured, as for {@link #cooldown}.
     */
    public long cooldownAgainst(double agiUs, double agiIt) {
        if(!agilityScaled || !(cooldownBase > 0) || !(agiUs > 0) || !(agiIt > 0))
            return(cooldown);
        return(Math.round(cooldownBase * Formulas.agilityCooldownFactor(agiIt, agiUs)));
    }

    /** Whether a thrower holding {@code ip} initiative against us can pay for this card. */
    public boolean affordable(double ip) {
        return(ipCost <= 0 || ip >= ipCost);
    }

    public String toString() {
        return(name);
    }
}
