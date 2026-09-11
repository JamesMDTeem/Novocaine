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

    public BeastMove(String name, double[] openings, double damageCoef, long cooldown,
                     double[] restores, double grievous, double soaked) {
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

    public String toString() {
        return(name);
    }
}
