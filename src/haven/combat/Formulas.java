package haven.combat;

/**
 * The combat model's arithmetic, and the authoritative implementation of it.
 *
 * Per ADR-0002 this package imports nothing from {@code haven}: it is the side that has to
 * match the live game, so it defines correct behaviour, and the Python evaluator under
 * {@code tools/combat/} follows it. Everything here is pure and allocation-free, because the
 * bot will call it inside a frame budget.
 *
 * Every formula below was measured against logged fights before it was written down, and the
 * evidence is recorded on each one. Where the wiki and the game disagreed, the game won; where
 * the wiki's prose and its own worked example disagreed, the example won. Constants that are
 * still uncertain say so rather than pretending to a precision the data does not support.
 */
public final class Formulas {
    private Formulas() {}

    /**
     * Armour penetration shared by every unarmed attack, as a fraction.
     *
     * Stated twice and consistently: "Unarmed attacks usually have around 30%" (Jorb, quoted
     * on the wiki's Combat moves page) and "UA attacks have a set 30% Armor penetration
     * value" (DDDsDD999's combat guide). A weapon's own figure is per-weapon and lives in
     * the data pack; this is the one number that is not.
     */
    public static final double UNARMED_ARMPEN = 0.30;

    /** Four openings, in the order used everywhere in this project. */
    public static final int GREEN = 0, BLUE = 1, YELLOW = 2, RED = 3;

    /**
     * The share of the OTHER opponents that a sweeping attack actually lands on.
     *
     * Full Circle's text is "your main target and all other opponents in range", and the
     * model has no idea where anybody is standing - so it used to give the card everyone
     * still alive. That made a crowd free: eleven of thirteen species took exactly as long
     * to clear at five opponents as at three, because every extra animal died alongside
     * the first for nothing.
     *
     * Measured instead. Across every Full Circle in the corpus thrown with two or more
     * opponents standing AND landing on at least one of them, counting how many of the
     * others it raised an opening on:
     *
     *   others standing   throws   others reached
     *   1                     55             0.33
     *   2                     28             0.79
     *   3                     10             1.80
     *   4 or more              6             2.50
     *
     * which is 70 of 174 bystanders, 0.40. The control says the signal is real: Quick
     * Barrage, single target, reaches 0.01 of the others over 551 throws in the same
     * conditions.
     *
     * NOT SATURATION. An opponent already fully open gains nothing from being in range and
     * would read as out of it, so that was checked separately: of the bystanders a Full
     * Circle did not reach, every one stood under 90 green and most under 50. There was
     * something left to open on all of them.
     *
     * This is a mean field over POSITION, which is the one thing the corpus could not
     * resolve per opponent - the distance was recorded for the sampled opponent only until
     * schema 16. It is applied to the marginal target rather than to all of them (see
     * Optimizer.step), so the expectation is preserved without pretending to know which
     * animal was where. The honest reading of a crowd number is therefore "this is what it
     * costs if they crowd in the way the corpus says they do", and a later corpus carrying
     * per-relation range replaces it with something that knows.
     *
     * Punch 'em Both and Storm of Swords take the same figure, which is an assumption:
     * neither has ever been thrown in this corpus.
     */
    public static final double SWEEP_REACH = 0.40;

    /**
     * How far an UNARMED attack reaches, in the world units a log measures distance in.
     *
     * A weapon declares a range and the figure is a multiple of this one: a sword is 1.2,
     * a stone axe is 1.0, and an unarmed attack is the 1.0 case with nothing in hand. The
     * corpus says so. Taking the distance at the state sample that CLOSED a landed attack
     * - not the one before it, which is where the swing set off from, since an attack
     * closes the gap before it strikes:
     *
     *   what swung                              n     p90     max
     *   an unarmed card, sword in hand         27    18.6    21.4
     *   a weapon card, stone axe (range 1.0)   59    18.8    23.5
     *   a weapon card, sword (range 1.2)     3044    21.4   123.5
     *
     * The first two agree to a fifth of a unit, which is the claim: range 1.0 IS the
     * unarmed reach. The sword then reaches 1.14 times as far where its figure says 1.20.
     * A constant offset explains the gap - the distance is measured centre to centre, so
     * two body radii sit inside every reading - and two weapon ranges cannot separate a
     * multiplier from an offset. Fitting both as an affine rule gives 5.8 + 13 x range,
     * which fits by construction and predicts nothing; the multiplicative form is the one
     * the game states, so it is the one used, and it runs about 5% long for a sword.
     *
     * The long tail is approach, not reach: the sampler is gated on change, so a swing
     * thrown while running in can close its state at whatever distance the run had reached.
     * That is why the ninetieth percentile is quoted rather than the maximum.
     */
    public static final double UNARMED_REACH = 18.7;

    /**
     * The reach of a swing, in world units, for whatever is in hand.
     *
     * @param weaponRange the weapon's own range figure, or 0/NaN for bare hands - which is
     *                    the same as 1.0, because that is what the unarmed reach is.
     */
    public static double reach(double weaponRange) {
        if(Double.isNaN(weaponRange) || (weaponRange <= 0))
            return(UNARMED_REACH);
        return(UNARMED_REACH * weaponRange);
    }

    /**
     * Raw damage before armour.
     *
     * {@code basedmg * share * sqrt(sqrt(ql * str) / 10) * opening^2}
     *
     * Verified end to end against a sparring corpus: a Bronze Sword (base damage 90) at
     * quality 28.68 wielded at strength 82, using Quick Barrage (listed at 25% of weapon
     * damage), predicts a coefficient of 49.55. Fits of five separate fights returned 48.6,
     * 49.7, 49.8, 49.8 and 51.6. The four inputs come from four independent places - the
     * data pack, the character sheet, the client's gear dump and the log header - so the
     * agreement is not circular.
     *
     * The exponent on the opening is 2. The wiki's worked example computes a fourth root
     * somewhere in its damage term; fits with the exponent fixed at 2 return R^2 of 0.9966
     * or better on every clean fight, which no other exponent comes close to.
     *
     * @param opening the opening in the attack's own school, 0..1 - NOT the combined
     *                opening across all four colours. Reading the combined value inflates
     *                the opening whenever another colour happens to be up, and understates
     *                the coefficient badly.
     */
    public static double rawDamage(double basedmg, double share, double ql, double str,
                                   double opening) {
        return(basedmg * share * Math.sqrt(Math.sqrt(ql * str) / 10.0) * opening * opening);
    }

    /**
     * Damage actually dealt, after armour penetration and soak.
     *
     * From Jorb's Fighting Quail notes: penetrating damage "will apply a portion of their
     * damage directly to the target before any other armor calculations are done"; hard soak
     * then comes off the top; and soft soak ramps in, with the applied share being
     * {@code 1 - (1 - x)^2} over an interval of twice the soft soak.
     *
     * The wiki's prose puts that interval at the soft soak rather than twice it. Its own
     * worked example - 110 damage against 75 hard and 35 soft, dealing 9 - only reproduces
     * with the doubling, and the page itself notes the inconsistency further down. Confirmed
     * independently here: a partner wearing a known 5 hard and 8 soft, struck with a weapon
     * of known 12.5% penetration, fits this to a residual of 0.46 of a hitpoint, which is
     * the rounding on the numbers themselves.
     *
     * @param armpen fraction, 0..1 - a weapon listed at "12.5" is 0.125 here.
     */
    public static double dealtDamage(double raw, double hard, double soft, double armpen) {
        double pen = raw * armpen;
        double r = Math.max(0.0, (raw - pen) - hard);
        if(soft <= 0)
            return(pen + r);
        double x = Math.min(1.0, r / (2.0 * soft));
        return(pen + r - (soft * (1.0 - ((1.0 - x) * (1.0 - x)))));
    }

    /**
     * EQUALIZATION. The ratio two combat skills are actually compared at.
     *
     * "If two combatants have UA/MC within a factor of 2, they are considered equal. For
     * example, if I have 100 UA, my UA attacks will generate the same openings on someone
     * with 50 UA, and someone with 200 UA. But my UA attacks against an opponent with less
     * than 50 UA will do more openings, and my attacks against an opponent with more than
     * 200 UA will do less openings." - DDDsDD999's combat guide.
     *
     * A dead zone, in other words, and it is the most consequential thing this project has
     * learned. Every defence weight recovered from an opening gain assumed the skill ratio
     * was free to take any value; inside the band it is pinned to 1, so the inversion hands
     * back the attacker's own weight and calls it the defender's.
     *
     * The corpus shows this outright. Against a boar, Knock Its Teeth Out at attack weight
     * 58 "measured" 51-73 and Quick Barrage at 111 "measured" 111-158 - the same creature
     * reading as two different numbers, each equal to OUR weight for the move that read it.
     * That was recorded as the corpus's one unresolved anomaly for weeks. Against a bee
     * swarm, where the skills are far apart, three moves at weights 58, 112 and 125 all
     * agree on about 30, which is what a real measurement looks like.
     *
     * Piecewise and continuous: exactly 1 across the band, and outside it the excess only.
     *
     * @return the factor the skills contribute to Wa/Wd, 1.0 when they are equalized
     */
    public static double equalize(double skillMe, double skillFoe) {
        if((skillMe <= 0) || (skillFoe <= 0))
            return(1.0);
        if(skillFoe < (skillMe / 2.0))
            return(skillMe / (2.0 * skillFoe));
        if(skillFoe > (skillMe * 2.0))
            return((2.0 * skillMe) / skillFoe);
        return(1.0);
    }

    /** Whether two skills fall inside the equalization band, where no gain can measure them. */
    public static boolean equalized(double skillMe, double skillFoe) {
        return((skillMe > 0) && (skillFoe > 0)
               && (skillFoe >= (skillMe / 2.0)) && (skillFoe <= (skillMe * 2.0)));
    }

    /**
     * Opening growth with the skills equalized and the multipliers not.
     *
     * The skill comparison passes through {@link #equalize}; everything else - the move's
     * own multiplier, the deck weighting, the defender's block multiplier - is a plain
     * factor on top. The guide's own worked figures are this: with the skills equal, Shield
     * Up's 250% block weight gives cbrt(1/2.5) = 0.7368, "so Shield Up has 26.42% less
     * openings", and Parry's 80% gives cbrt(1/0.8) = 1.0772, "7.72% more".
     *
     * @param multMe  the attacker's multipliers - move multiplier x mu x any stance factor
     * @param multFoe the defender's - block multiplier x their mu
     */
    public static double openingGainEq(double skillMe, double multMe,
                                       double skillFoe, double multFoe,
                                       double ob, double oc) {
        if((multFoe <= 0) || (multMe <= 0))
            return(0);
        double r = equalize(skillMe, skillFoe) * (multMe / multFoe);
        return(Math.cbrt(r) * ob * (1.0 - oc));
    }

    /**
     * How much a move raises one of the defender's openings.
     *
     * {@code cbrt(Wa / Wd) * Ob * (1 - Oc)}, where Ob is the move's listed opening for that
     * colour and Oc is the opening already standing there.
     *
     * The linear falloff is well supported: Knock Its Teeth Out, listed at +20% Cornered,
     * produced +24, +19, +14 and +11 from standing openings of 0, 24, 42 and 56 against one
     * opponent, which this reproduces to within a point once the weight term is fixed by the
     * first observation.
     *
     * @param oc standing opening in that colour, 0..1
     * @param ob the move's listed opening for that colour, as a percentage (20 for +20%)
     * @return the gain in the same percentage units as {@code ob}
     */
    public static double openingGain(double wa, double wd, double ob, double oc) {
        if(wd <= 0)
            return(0);
        return(Math.cbrt(wa / wd) * ob * (1.0 - oc));
    }

    /**
     * The defender's defence weight, recovered from an observed opening gain.
     *
     * The inverse of {@link #openingGain}. This is the estimator: one clean attack into a
     * fresh opening, with the move's listed Ob known from the character sheet, gives the
     * opponent's Wd outright - no fitting, no model search.
     *
     * @return the defence weight, or 0 when the observation cannot constrain it
     */
    public static double defenceWeight(double wa, double gain, double ob, double oc) {
        double denom = ob * (1.0 - oc);
        if((denom <= 0) || (gain <= 0))
            return(0);
        double k = gain / denom;
        return(wa / (k * k * k));
    }

    /** Openings combine as {@code 1 - product(1 - o_i)}. Values are fractions, 0..1. */
    public static double combined(double[] openings) {
        double p = 1.0;
        for(int i = 0; i < openings.length; i++)
            p *= (1.0 - openings[i]);
        return(1.0 - p);
    }

    /**
     * The relative-agility multiplier applied to an attack's cooldown.
     *
     * {@code 1 - 0.1 * clamp(log2(agiMe / agiFoe), -1, +1)} - a band of plus or minus ten
     * percent, reaching its limit at a factor-two agility gap.
     *
     * Measured across two sparring partners of known agility: against the slower one, Knock
     * Its Teeth Out, Full Circle and Quick Barrage reported 33, 38 and 19 ticks against bases
     * of 35, 40 and 20; against the faster one, 38, 43 and 22. This reproduces five of those
     * six exactly and misses the sixth by one tick, wanting a multiplier 0.0013 higher than
     * it produces. The shape and the cap are confirmed; the leading constant is not pinned to
     * better than a fifth of a percent and wants a third partner at a different agility.
     *
     * Applies to attacks only. Zig-Zag Ruse, a maneuver, reported 50 ticks against both
     * partners and against a badger - three opponents spanning the whole range - and
     * {@code Coolmod}'s own tip string calls itself "Attack cooldown".
     */
    public static double agilityCooldownFactor(double agiMe, double agiFoe) {
        if((agiMe <= 0) || (agiFoe <= 0))
            return(1.0);
        double l = Math.log(agiMe / agiFoe) / Math.log(2.0);
        if(l > 1.0)
            l = 1.0;
        else if(l < -1.0)
            l = -1.0;
        return(1.0 - (0.1 * l));
    }

    /**
     * A move's cooldown in server ticks.
     *
     * Base, divided by mu where the move's own text says so, scaled by initiative where it
     * says so, and finally scaled by relative agility for attacks. Rounded, because the
     * server reports whole ticks.
     *
     * The initiative term is Take Aim's: its text reads "increases by 20% for each Point of
     * Initiative you have", giving 30, 36, 42, 48, 54 and 60 at 0 through 5 points. Seventeen
     * of nineteen observations across five fights match exactly; both misses lag by one point,
     * which is the state sample racing the move message rather than the model.
     *
     * @param muDivides   whether the move's text reads "cooldown / mu"
     * @param ipScale     extra fraction of the base per initiative point, 0 for most moves
     * @param isAttack    maneuvers do not take the agility modifier
     */
    /**
     * A move's cooldown in whole server ticks.
     *
     * It FLOORS, and it floors TWICE. Both were wrong here until a run of eighteen Take Aims
     * at one level settled them, and the two errors together had put the deck weighting on
     * the wrong curve entirely.
     *
     * The card's own cooldown is an integer: base divided by the deck weighting and floored,
     * which is the number the card displays. Initiative then scales THAT integer, and the
     * result is floored again. The two steps are not interchangeable with one - at deck
     * weighting 1.125 the base is 30/1.125 = 26.667, and a single floor at the end gives
     * 32 ticks at one initiative where the game gives 31. Flooring the base to 26 first
     * gives 31.2, and so 31.
     *
     * The evidence is a ladder of eighteen consecutive Take Aims from 0 to 17 initiative,
     * plus every earlier Take Aim in the corpus - twenty-eight readings across three card
     * levels. This rule matches all twenty-eight. Rounding half up matches eighteen, and
     * flooring only at the end matches twenty-seven.
     *
     * The agility factor is applied last and ROUNDS rather than floors, which is not a
     * guess either - flooring it costs a tick on every attack the corpus has measured
     * against a faster opponent. Knock Its Teeth Out reads 38 where flooring gives 37, and
     * Full Circle 43 where flooring gives 42. So the two subsystems behave differently:
     * the card's own cooldown is an integer the game floors into shape, and the matchup
     * modifier is applied to that integer and rounded.
     */
    public static long cooldownTicks(double base, boolean muDivides, double mu,
                                     double ipScale, int ip, boolean isAttack,
                                     double agiMe, double agiFoe) {
        double cd = base;
        if(muDivides && (mu > 0))
            cd = Math.floor(cd / mu);
        cd = Math.floor(cd * (1.0 + (ipScale * ip)));
        if(isAttack)
            return(Math.round(cd * agilityCooldownFactor(agiMe, agiFoe)));
        return((long)cd);
    }

    /**
     * The deck weighting, read from a cooldown rather than fitted.
     *
     * A move whose text reads "cooldown / mu" reports a shorter cooldown as its level rises,
     * so mu falls straight out of the ratio. This is why the mu curve needs no card-counting
     * experiment: it needs the same move logged at two levels.
     *
     * @return mu, or 0 when the observation cannot constrain it
     */
    public static double muFromCooldown(double base, double observedTicks, double ipScale,
                                        int ip) {
        /* observed = (base / mu) * (1 + ipScale * ip), so the initiative term comes off
         * the observation before the ratio is taken, not after. */
        double ipf = 1.0 + (ipScale * ip);
        if((observedTicks <= 0) || (ipf <= 0))
            return(0);
        return(base / (observedTicks / ipf));
    }

    /** Server ticks are 0.06 seconds. Confirmed against observed gaps between repeated moves. */
    public static double ticksToSeconds(double ticks) {
        return(ticks * 0.06);
    }
}
