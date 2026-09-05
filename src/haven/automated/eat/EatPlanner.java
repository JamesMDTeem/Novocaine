package haven.automated.eat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out which foods, and how many of each, get a character from its current attributes to a
 * set of target attribute points - the eating equivalent of {@code StudyPlanner}.
 *
 * This class deliberately imports nothing from {@code haven}. No Widget, no Coord, no GItem.
 * Every type it needs (Dish, CharState, Goal, Plan) is plain data defined here, for the same
 * reason {@code StudyPlanner} does it: the arithmetic is the part that can actually be wrong, and
 * it should be reasoned about - and eventually tested - without standing up a UI.
 *
 * <h2>The model, and what settled it</h2>
 *
 * A bar of capacity {@code cap} resolves the moment its accumulated <i>raw</i> FEP reaches
 * {@code cap}. One entry then wins, weighted by its share of everything accumulated, and the
 * character gains that entry's tier (+1, +2, ...) in that entry's stat. The bar is then emptied -
 * <b>anything above {@code cap} is discarded, not banked</b>.
 *
 * That last clause is the whole ballgame and it is measured, not assumed. Replaying the local
 * {@code EatObserver} logs (517 {@code food} pushes across four characters) shows the same shape
 * every time: {@code cap 26 -> 20.74 -> trig -> cap 26}, with the bar reading empty at every
 * single push. Those characters were eating q19 Blueberry Pie into a cap-26 bar, so each bite
 * cleared the bar on its own by a wide margin - and none of that excess ever appears in the next
 * push. If overflow carried, it would.
 *
 * Three consequences follow, and all three are why this class does not look like a flat ranking:
 * <ul>
 * <li><b>Expected points divide by what the bar actually accumulated, not by {@code cap}.</b> For
 *     one bar that resolves at total {@code A >= cap}, the expected gain for stat {@code s} is
 *     {@code sum(fep_e * tier_e for e in s) / A}. Using {@code cap} instead is exactly a factor
 *     {@code A/cap} too generous - a 2x overestimate for a dish carrying twice the cap in one
 *     bite, which is the normal case for anything in the beebread class.</li>
 * <li><b>Overfilling is waste, so the biggest dish is often not the best one.</b> Ranking on raw
 *     FEP-per-hunger silently assumes every point lands. It doesn't.</li>
 * <li><b>Tier weighting belongs in the numerator only.</b> A "+2" line is one entry that pays
 *     double when it wins; it does not fill the bar any faster than a "+1" line of the same
 *     value. {@code BAttrWnd.FoodMeter} accumulates raw {@code el.a} against {@code cap}, and
 *     {@code FoodInfo.tipimg} computes its "will fill your bar to X%" from the unweighted sum -
 *     the client already keeps these two quantities apart.</li>
 * </ul>
 *
 * <h2>Cap movement</h2>
 *
 * The settled cap tracks the character's highest base attribute one-for-one: the same logs step
 * {@code 11 -> 12 -> 13} in lockstep with each Will level-up, and {@code 26 -> 27 -> 29} on a
 * second character. So a level-up in the current top stat raises the base cap by one, and nothing
 * else moves it.
 *
 * Variety pulls it back down for the duration of a bar, by an amount that is now known exactly
 * rather than estimated. For the {@code m}-th <i>distinct</i> food eaten in the current bar:
 *
 * <pre>
 *   reduction += sqrt(0.4 * gmod * topStat / m)
 *   cap        = topStat - sum(reduction)      // reset when the bar resolves
 * </pre>
 *
 * where {@code topStat} is the character's highest base attribute and {@code gmod} is the FEP
 * multiplier at the moment that food is eaten. Replaying the server's pooled eat log settles this
 * to float precision: <b>717 of 718 cap-decrease events match for an integer m</b>, mean absolute
 * error 4.75e-4, and over 584 fresh-bar ({@code m=1}) events the constant
 * {@code dcap^2 / (gmod * topStat)} has median 0.399632 and maximum 0.400001 - it is 0.4, and the
 * downward bias is {@code cap} and {@code gmod} arriving as float32 against an integer top stat.
 *
 * Two things this replaces. The wiki's Hunger table is <i>not</i> a table of independent
 * measurements: its top two rows are {@code sqrt(0.4 * gmod)} to three decimals (1.097 vs 1.09545,
 * 0.894 vs 0.89443), and the rows below it carry values that belong to a different gmod than the
 * one they are labelled with - its "1.5" row is the {@code gmod = 1.0} value, its "0.9" row is the
 * {@code gmod = 0.5} value. And the old {@code sqrt(n)} scaling past the first food was not, as
 * previously believed, inside the noise: the true series is {@code sum(1/sqrt(i))}, which is
 * 1.7071 at n=2 and 2.2845 at n=3 against {@code sqrt(n)}'s 1.4142 and 1.7321 - 17% and 24% low.
 *
 * <h2>Hunger drift</h2>
 *
 * {@code gmod} is a closed form of the hunger meter, verified against 2214 distinct
 * {@code (glut, gmod)} pairs spanning {@code glut} 0 to 0.578 with a maximum absolute error of
 * 1.5e-7 (float32 rounding):
 *
 * <pre>
 *   gmod = 3^(1 - 2*glut)
 * </pre>
 *
 * So the simulation advances {@code glut} by each bite's hunger cost and re-reads {@code gmod} from
 * it, rather than holding the multiplier fixed at its starting value. That matters in both
 * directions: FEP payout falls as the meter fills, and so does the variety reduction each new food
 * buys. See {@link #gmodFor}.
 *
 * <h2>What this still cannot promise</h2>
 *
 * Level-ups are probabilistic, so the output is <i>expected</i> points, not a guarantee - a plan
 * that expects +5 CON is not a plan that yields +5 CON.
 *
 * Satiation is joined exactly, by live satiation-entry key, but it is still a <i>snapshot</i>: it does
 * not rise as the plan eats, because nothing measures the per-eat increment yet. A plan that leans
 * hard on one dish will therefore underperform, and says so through {@link Plan#warnings} rather
 * than pretending otherwise. The bite-by-bite greedy is a heuristic, not an exact solve of what is
 * genuinely a knapsack with a moving capacity.
 */
public final class EatPlanner {
    private EatPlanner() {}

    public enum QualityMode { Q10, PERCENT_OF_MAX }

    /** One FEP line of a dish, at q10. */
    public static final class Fep {
        public final String stat;
        public final int tier;
        public final double value;

        public Fep(String stat, int tier, double value) {
            this.stat = stat;
            this.tier = tier;
            this.value = value;
        }
    }

    /** One catalog entry, reduced to the numbers the plan is made of. */
    public static final class Dish {
        public final String name;
        public final List<Fep> feps;
        /** Hunger cost per bite, at q10, in per-mille of a full hunger bar. */
        public final double hunger;
        /**
         * Keys of the live satiation entries this dish drains, in the form
         * {@code EatObserver.resolveSatiationKey} produces - so they match what
         * {@code EatHelperWindow.readLiveSatiation} publishes for the character's current state.
         *
         * Not the tooltip's "Food types:" resources. Those are thirteen category resources under
         * {@code gfx/invobjs/food/}, while satiation entries are keyed by a representative dish
         * icon ({@code gfx/invobjs/steaktuber}); the namespaces share nothing, so joining on the
         * type resource matches nothing and silently prices every dish as unsatiated.
         */
        public final List<String> satiationKeys;
        /** Highest quality any client has reported eating this dish, or null if never tagged. */
        public final Double maxQualitySeen;

        public Dish(String name, List<Fep> feps, double hunger, List<String> satiationKeys,
                    Double maxQualitySeen) {
            this.name = name;
            this.feps = feps;
            this.hunger = hunger;
            this.satiationKeys = satiationKeys;
            this.maxQualitySeen = maxQualitySeen;
        }
    }

    /** The character state the plan is run against - a snapshot, not a live handle. */
    public static final class CharState {
        /** Current base attribute value per stat abbreviation (STR, AGI, INT, CON, PER, CHA, DEX, WILL, PSY). */
        public final Map<String, Integer> attrs;
        /** FEP multiplier from current hunger level (BAttrWnd.GlutMeter.gmod). */
        public final double hungerMod;
        /**
         * Satiation penalty [0,1) per live satiation entry key; a key not present means
         * unsatiated. Keyed to match {@link Dish#satiationKeys}, so the join is exact rather
         * than inferred.
         */
        public final Map<String, Double> satiationPenalty;
        public final double accountMult;
        public final double tableFoodEventBonus;
        public final double tableHungerMod;
        /** The live FEP cap, which may already carry this bar's variety reduction. */
        public final double startCap;
        /** Live hunger meter reading; 1.0 is one full bar, matching {@code BAttrWnd.GlutMeter.glut}. */
        public final double glut;

        public CharState(Map<String, Integer> attrs, double hungerMod, Map<String, Double> satiationPenalty,
                          double accountMult, double tableFoodEventBonus, double tableHungerMod,
                          double startCap, double glut) {
            this.attrs = attrs;
            this.hungerMod = hungerMod;
            this.satiationPenalty = satiationPenalty;
            this.accountMult = accountMult;
            this.tableFoodEventBonus = tableFoodEventBonus;
            this.tableHungerMod = tableHungerMod;
            this.startCap = startCap;
            this.glut = glut;
        }
    }

    /** Target attribute points, keyed by stat abbreviation. Stats not present are not goals. */
    public static final class Goal {
        public final Map<String, Double> targetPoints;

        public Goal(Map<String, Double> targetPoints) {
            this.targetPoints = targetPoints;
        }
    }

    /** One dish's aggregated role across the whole plan. */
    public static final class PlanRow {
        public final String dish;
        public final int bites;
        public final double totalHunger;
        /** Expected points this dish alone contributed, per goal stat. */
        public final Map<String, Double> expectedPoints;
        /** True if this dish has no recorded quality and PERCENT_OF_MAX fell back to q10 for it. */
        public final boolean qualityFallback;

        PlanRow(String dish, int bites, double totalHunger, Map<String, Double> expectedPoints,
                boolean qualityFallback) {
            this.dish = dish;
            this.bites = bites;
            this.totalHunger = totalHunger;
            this.expectedPoints = expectedPoints;
            this.qualityFallback = qualityFallback;
        }
    }

    /**
     * One dish scored on its own against the goal, from the character's starting state - the
     * "what else would do" list. The plan above it says what is optimal; this says what is
     * <i>available</i>, which is the question that actually gets asked when the best dish is not
     * in the cellar.
     */
    public static final class Candidate {
        public final String dish;
        public final double hungerPerBite;
        /** Raw (untiered) FEP one bite delivers, after every multiplier. */
        public final double rawFepPerBite;
        /** Expected points per bite, per goal stat, with bar overflow already priced in. */
        public final Map<String, Double> goalPointsPerBite;
        /** Ranking key: summed goal points per bite divided by that bite's hunger cost. */
        public final double goalPointsPerHunger;
        /** Bites of this dish alone to meet every goal stat, or -1 when it cannot cover them all. */
        public final int bitesAlone;
        /**
         * Fraction of a bite's FEP thrown away by overfilling the bar, [0,1). A dish carrying
         * twice the cap in one bite wastes half of itself no matter how good its numbers look.
         */
        public final double overflowWaste;
        public final boolean qualityFallback;
        /** Combined satiation multiplier currently applied to this dish; 1.0 means unsatiated. */
        public final double satiationMod;

        Candidate(String dish, double hungerPerBite, double rawFepPerBite,
                  Map<String, Double> goalPointsPerBite, double goalPointsPerHunger, int bitesAlone,
                  double overflowWaste, boolean qualityFallback, double satiationMod) {
            this.dish = dish;
            this.hungerPerBite = hungerPerBite;
            this.rawFepPerBite = rawFepPerBite;
            this.goalPointsPerBite = goalPointsPerBite;
            this.goalPointsPerHunger = goalPointsPerHunger;
            this.bitesAlone = bitesAlone;
            this.overflowWaste = overflowWaste;
            this.qualityFallback = qualityFallback;
            this.satiationMod = satiationMod;
        }
    }

    public static final class Plan {
        /** Ranked by total hunger spent on each dish, most first. */
        public final List<PlanRow> rows;
        /** Every dish that advances the goal at all, best goal-points-per-hunger first. */
        public final List<Candidate> candidates;
        /** Expected points gained per goal stat - not a guarantee, see the class doc. */
        public final Map<String, Double> expectedPoints;
        public final double totalHunger;
        public final int barsSimulated;
        /** True if every goal stat's expected points met its target within {@code maxBars}. */
        public final boolean goalMet;
        /** True if the catalog ran out of dishes that could advance the goal at all. */
        public final boolean stalled;
        /** Assumptions the caller should see stated rather than buried - see {@link #warn}. */
        public final List<String> warnings;

        Plan(List<PlanRow> rows, List<Candidate> candidates, Map<String, Double> expectedPoints,
             double totalHunger, int barsSimulated, boolean goalMet, boolean stalled,
             List<String> warnings) {
            this.rows = rows;
            this.candidates = candidates;
            this.expectedPoints = expectedPoints;
            this.totalHunger = totalHunger;
            this.barsSimulated = barsSimulated;
            this.goalMet = goalMet;
            this.stalled = stalled;
            this.warnings = warnings;
        }
    }

    /**
     * Resolved per-dish numbers for one simulation run - quality settled, formulas applied once.
     * FEP here is at <b>unit gmod</b>: the hunger multiplier is the one input that moves during the
     * run, so it is multiplied in at each use site instead of being folded in once.
     */
    private static final class Resolved {
        final Dish dish;
        /** Tier already multiplied in - this is the expected-points numerator, never the bar fill. */
        final Map<String, Double> weightedFepByStat;
        /** Untiered sum across every stat, at unit gmod: what actually fills the bar. */
        final double rawFep;
        final double hungerCost;
        final double satMod;
        final boolean qualityFallback;
        /** True if any of this dish's FEP lands on a goal stat at all. */
        final boolean touchesGoal;

        Resolved(Dish dish, Map<String, Double> weightedFepByStat, double rawFep, double hungerCost,
                 double satMod, boolean qualityFallback, boolean touchesGoal) {
            this.dish = dish;
            this.weightedFepByStat = weightedFepByStat;
            this.rawFep = rawFep;
            this.hungerCost = hungerCost;
            this.satMod = satMod;
            this.qualityFallback = qualityFallback;
            this.touchesGoal = touchesGoal;
        }
    }

    private static final int MAX_BARS_SAFETY = 2000;

    /**
     * Backstop on total bites. A plan needing more than this is not a plan a player will follow,
     * and the loop must terminate even if the catalog is degenerate (a dish with FEP but no
     * goal-relevant FEP can never satisfy the goal check).
     */
    private static final int MAX_BITES_SAFETY = 20000;

    /** The cap never usefully falls below one point of FEP however much variety is bought. */
    private static final double CAP_FLOOR = 1.0;

    /** One full hunger bar, in the per-mille units dish hunger costs are recorded in. */
    public static final double HUNGER_PER_FULL_BAR = 1000.0;

    /**
     * The constant in {@code reduction = sqrt(VARIETY_CONST * gmod * topStat / m)}. Measured, not
     * assumed - see the class doc for the sample and the residual. Exposed so the server-side
     * residual check and this planner cannot drift apart silently.
     */
    public static final double VARIETY_CONST = 0.4;

    /**
     * The FEP multiplier at a given hunger-meter reading: {@code gmod = 3^(1 - 2*glut)}. Exact to
     * float precision across every observation we have (class doc). {@code glut} is in bar units,
     * so 0 is empty, 0.5 puts the multiplier at 1.0, and values above 1 are real - the meter wraps
     * rather than clamping, which is why {@code BAttrWnd.GlutMeter.draw} renders
     * {@code glut - floor(glut)}.
     */
    public static double gmodFor(double glut) {
        return Math.pow(3.0, 1.0 - 2.0 * glut);
    }

    /**
     * The cap reduction the {@code m}-th distinct food of a bar buys, at the hunger multiplier in
     * force when it is eaten. {@code m} is one-based: the first food of a fresh bar is {@code m=1}.
     */
    public static double varietyStep(double gmod, double topStat, int m) {
        if (m <= 0 || gmod <= 0 || topStat <= 0)
            return 0;
        return Math.sqrt(VARIETY_CONST * gmod * topStat / m);
    }

    /**
     * Above this share of a plan's bites on a single dish, the un-modelled rise in that dish's own
     * satiation stops being a rounding error and starts being the dominant term - worth saying out
     * loud rather than shipping a number that quietly assumes bite 400 pays like bite 1.
     */
    private static final double SINGLE_DISH_WARN_SHARE = 0.75;
    private static final int SINGLE_DISH_WARN_MIN_BITES = 20;

    /** How many candidate rows are worth ranking; past this it is a catalog dump, not advice. */
    private static final int MAX_CANDIDATES = 12;

    public static Plan plan(List<Dish> catalog, CharState state, Goal goal,
                             QualityMode qualityMode, double qualityPct, int maxBars) {
        int barCap = Math.min(maxBars, MAX_BARS_SAFETY);
        List<String> warnings = new ArrayList<>();

        List<Resolved> resolved = new ArrayList<>(catalog.size());
        for (Dish d : catalog) {
            Resolved r = resolve(d, state, goal, qualityMode, qualityPct);
            if (r != null)
                resolved.add(r);
        }

        Map<String, Integer> initialAttrs = state.attrs;
        int initialTop = 0;
        for (int v : initialAttrs.values())
            initialTop = Math.max(initialTop, v);

        // A cap of 0 is a real state, not a impossible one - it is what BAttrWnd reports before the
        // server has pushed the FEP bar, and eat records logged in exactly that state are already
        // in the wild. Falling back to the top attribute is sound because the settled cap tracks it
        // one-for-one; leaving it at 0 would make every dish look like it overflows infinitely.
        double liveCap = state.startCap > 0 ? state.startCap : Math.max(CAP_FLOOR, initialTop);

        // Hunger is simulated, not frozen: glut advances with every bite and gmod is read back off
        // it. The live gmod seeds it rather than being recomputed, because the server sends both
        // and its value is authoritative for the starting bite even if the two ever disagree.
        double glut = state.glut;
        double gmod = state.hungerMod > 0 ? state.hungerMod : gmodFor(glut);

        List<Candidate> candidates = rankCandidates(resolved, liveCap, gmod, goal);

        // The base cap is the top base attribute outright. Across all 2638 pooled eat records the
        // cap never exceeds it and equals it exactly whenever no variety has been bought, so there
        // is nothing to infer here - the old settled-cap reconstruction was recovering a number the
        // character sheet already states.
        double baseCap = initialTop;
        // The live cap may already be part-way through this bar's variety reduction. That spend is
        // measured directly rather than inverted; only the *count* has to be inferred, and it is
        // needed solely as the divisor for the next food.
        double reduction = Math.max(0, baseCap - liveCap);
        int varietyN = inferVarietyN(reduction, gmod, baseCap);
        double cap = capOf(baseCap, reduction);

        Map<String, Double> settledGain = new LinkedHashMap<>();
        Map<String, Double> barWeighted = new LinkedHashMap<>();
        // Weighted FEP this bar, split by the dish that contributed it - drives per-dish credit.
        Map<String, Map<String, Double>> barWeightedByDish = new LinkedHashMap<>();
        Set<String> eatenThisBar = new HashSet<>();
        double barRaw = 0;

        Map<String, Integer> bites = new LinkedHashMap<>();
        Map<String, Double> hungerByDish = new LinkedHashMap<>();
        Map<String, Map<String, Double>> pointsByDish = new LinkedHashMap<>();
        Map<String, Boolean> fallbackByDish = new LinkedHashMap<>();

        double totalHunger = 0;
        int barsSimulated = 0;
        int totalBites = 0;
        boolean stalled = false;

        while (barsSimulated < barCap && totalBites < MAX_BITES_SAFETY
                && !goalMet(goal, settledGain, barWeighted, barRaw, cap)) {
            Resolved best = null;
            double bestScore = 0;
            boolean bestIsNew = false;

            for (Resolved r : resolved) {
                if (r.hungerCost <= 0 || !r.touchesGoal)
                    continue;
                boolean isNew = !eatenThisBar.contains(r.dish.name);
                double nCap = isNew
                        ? capOf(baseCap, reduction + varietyStep(gmod, baseCap, varietyN + 1))
                        : cap;
                // What this bar will actually resolve at if this bite is the one that fills it -
                // which is also what the expected-points denominator will be. A bite that
                // overshoots by 2x therefore scores half as well as its raw numbers suggest.
                double projected = Math.max(nCap, barRaw + r.rawFep * gmod);
                double gain = 0;
                for (Map.Entry<String, Double> e : goal.targetPoints.entrySet()) {
                    Double w = r.weightedFepByStat.get(e.getKey());
                    if (w == null || w <= 0)
                        continue;
                    double rem = remaining(e.getKey(), e.getValue(), settledGain, barWeighted, barRaw, cap);
                    if (rem <= 0)
                        continue;
                    // Credit is capped at what is still wanted, so a dish stops being attractive
                    // the moment its stat is satisfied and the next goal stat takes over.
                    gain += Math.min(w * gmod / projected, rem);
                }
                if (gain <= 0)
                    continue;
                double score = gain / r.hungerCost;
                if (best == null || score > bestScore) {
                    best = r;
                    bestScore = score;
                    bestIsNew = isNew;
                }
            }

            if (best == null) {
                stalled = true;
                break;
            }

            if (bestIsNew) {
                eatenThisBar.add(best.dish.name);
                varietyN++;
                reduction += varietyStep(gmod, baseCap, varietyN);
                cap = capOf(baseCap, reduction);
            }
            barRaw += best.rawFep * gmod;
            Map<String, Double> mine = barWeightedByDish
                    .computeIfAbsent(best.dish.name, k -> new LinkedHashMap<>());
            for (Map.Entry<String, Double> e : best.weightedFepByStat.entrySet()) {
                barWeighted.merge(e.getKey(), e.getValue() * gmod, Double::sum);
                mine.merge(e.getKey(), e.getValue() * gmod, Double::sum);
            }
            totalHunger += best.hungerCost;
            totalBites++;
            // The bite is eaten, so the meter has moved - every later bite is priced at the lower
            // multiplier this leaves behind, and so is every later variety step.
            glut += best.hungerCost / HUNGER_PER_FULL_BAR;
            gmod = gmodFor(glut);
            bites.merge(best.dish.name, 1, Integer::sum);
            hungerByDish.merge(best.dish.name, best.hungerCost, Double::sum);
            fallbackByDish.putIfAbsent(best.dish.name, best.qualityFallback);

            if (barRaw >= cap) {
                // The bar resolves at everything accumulated, not at cap; the excess is discarded
                // rather than carried, so it dilutes this bar's odds and then vanishes.
                double resolvedAt = barRaw;
                for (Map.Entry<String, Double> e : barWeighted.entrySet())
                    settledGain.merge(e.getKey(), e.getValue() / resolvedAt, Double::sum);
                creditBar(pointsByDish, barWeightedByDish, goal, resolvedAt);

                barRaw = 0;
                barWeighted.clear();
                barWeightedByDish.clear();
                eatenThisBar.clear();
                varietyN = 0;
                reduction = 0;
                barsSimulated++;

                double top = initialTop;
                for (Map.Entry<String, Integer> e : initialAttrs.entrySet())
                    top = Math.max(top, e.getValue() + settledGain.getOrDefault(e.getKey(), 0.0));
                baseCap = top;
                cap = capOf(baseCap, 0);
            }
        }

        // Whatever is still sitting in an unresolved bar is real expectation - the bar will finish
        // eventually - so it is counted, at the total it is currently projected to resolve at.
        if (barRaw > 0) {
            double projected = Math.max(cap, barRaw);
            for (Map.Entry<String, Double> e : barWeighted.entrySet())
                settledGain.merge(e.getKey(), e.getValue() / projected, Double::sum);
            creditBar(pointsByDish, barWeightedByDish, goal, projected);
        }

        List<PlanRow> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : bites.entrySet()) {
            rows.add(new PlanRow(e.getKey(), e.getValue(), hungerByDish.getOrDefault(e.getKey(), 0.0),
                    pointsByDish.getOrDefault(e.getKey(), Collections.emptyMap()),
                    fallbackByDish.getOrDefault(e.getKey(), false)));
        }
        rows.sort((a, b) -> Double.compare(b.totalHunger, a.totalHunger));

        Map<String, Double> expectedPoints = new LinkedHashMap<>();
        boolean met = true;
        for (Map.Entry<String, Double> e : goal.targetPoints.entrySet()) {
            double got = settledGain.getOrDefault(e.getKey(), 0.0);
            expectedPoints.put(e.getKey(), got);
            if (got < e.getValue())
                met = false;
        }

        addWarnings(warnings, state, goal, resolved, rows, totalHunger, totalBites, stalled,
                barsSimulated >= barCap, totalBites >= MAX_BITES_SAFETY, qualityMode);

        return new Plan(rows, candidates, expectedPoints, totalHunger, barsSimulated, met, stalled,
                warnings);
    }

    /**
     * Splits a resolved bar's expected points back out per dish, in proportion to the weighted FEP
     * each dish put into it. Reporting "this dish bought you 2.1 CON" only means anything if the
     * attribution matches how the bar actually paid out, which is by share of the accumulated
     * total - the same denominator the bar itself resolved at.
     */
    private static void creditBar(Map<String, Map<String, Double>> pointsByDish,
                                   Map<String, Map<String, Double>> barWeightedByDish, Goal goal,
                                   double resolvedAt) {
        if (resolvedAt <= 0)
            return;
        for (Map.Entry<String, Map<String, Double>> dish : barWeightedByDish.entrySet()) {
            Map<String, Double> acc = pointsByDish
                    .computeIfAbsent(dish.getKey(), k -> new LinkedHashMap<>());
            for (String stat : goal.targetPoints.keySet()) {
                Double w = dish.getValue().get(stat);
                if (w == null || w <= 0)
                    continue;
                acc.merge(stat, w / resolvedAt, Double::sum);
            }
        }
    }

    /**
     * A settled cap is integral - that is how {@code EatLogService} detects one server-side, and
     * the local logs bear it out (26, 27, 29 settled; 20.73, 20.74 mid-bar). A fractional live cap
     * is therefore a settled cap with variety credit already spent against it, and the settled
     * value it came from is the next integer up.
     */
    /** The cap the character reads at, given how much variety this bar has already bought. */
    private static double capOf(double baseCap, double reduction) {
        return Math.max(CAP_FLOOR, baseCap - Math.max(0, reduction));
    }

    /**
     * How many distinct foods the live cap looks like it has already paid for this bar.
     *
     * Only the count is inferred, never the spend - the spend is {@code topStat - cap} and is
     * already exact. The count is needed for one thing: the divisor {@code m} of the <i>next</i>
     * food. It is approximate by nature, because each earlier food was charged at whatever
     * {@code gmod} was in force when it was eaten and the log of that is gone by the time a plan
     * runs; pricing them all at the current multiplier is the closest recoverable answer, and it
     * errs by at most one step of an already-decaying series.
     */
    private static int inferVarietyN(double reduction, double gmod, double baseCap) {
        if (reduction <= 0)
            return 0;
        double spent = 0;
        for (int n = 1; n <= MAX_VARIETY_N; n++) {
            double next = varietyStep(gmod, baseCap, n);
            if (next <= 0)
                return n - 1;
            // Stop at the count whose cumulative spend is nearest the measured one, rather than
            // the first that exceeds it - overshooting by a whisker should not cost a whole food.
            if (spent + next > reduction)
                return (reduction - spent < spent + next - reduction) ? n - 1 : n;
            spent += next;
        }
        return MAX_VARIETY_N;
    }

    /** Nothing observed has come near this; it exists so the inference above always terminates. */
    private static final int MAX_VARIETY_N = 64;

    /** Points still wanted for one goal stat, counting the bar currently in progress. */
    private static double remaining(String stat, double target, Map<String, Double> settledGain,
                                     Map<String, Double> barWeighted, double barRaw, double cap) {
        double got = settledGain.getOrDefault(stat, 0.0);
        Double pending = barWeighted.get(stat);
        if (pending != null && pending > 0)
            got += pending / Math.max(cap, barRaw);
        return target - got;
    }

    private static boolean goalMet(Goal goal, Map<String, Double> settledGain,
                                    Map<String, Double> barWeighted, double barRaw, double cap) {
        for (Map.Entry<String, Double> e : goal.targetPoints.entrySet()) {
            if (remaining(e.getKey(), e.getValue(), settledGain, barWeighted, barRaw, cap) > 0)
                return false;
        }
        return true;
    }

    /**
     * Every dish that advances the goal at all, scored on its own from the character's starting
     * state and ranked by goal points per unit hunger. This is deliberately independent of the
     * simulated plan: the plan answers "what is best", this answers "what would work", and the
     * second question is the one that gets asked when the first answer is not in the cellar.
     */
    private static List<Candidate> rankCandidates(List<Resolved> resolved, double cap, double gmod,
                                                   Goal goal) {
        List<Candidate> out = new ArrayList<>();
        for (Resolved r : resolved) {
            if (!r.touchesGoal || r.hungerCost <= 0)
                continue;
            // Resolved carries FEP at unit gmod so the simulation can re-price it per bite; this
            // list is "one bite, right now", so it is priced at the starting multiplier.
            double rawFep = r.rawFep * gmod;
            double projected = Math.max(cap, rawFep);
            Map<String, Double> perBite = new LinkedHashMap<>();
            double sum = 0;
            boolean coversAll = true;
            double worstBites = 0;
            for (Map.Entry<String, Double> e : goal.targetPoints.entrySet()) {
                Double w = r.weightedFepByStat.get(e.getKey());
                double points = (w == null) ? 0 : w * gmod / projected;
                perBite.put(e.getKey(), points);
                sum += points;
                if (points <= 0)
                    coversAll = false;
                else
                    worstBites = Math.max(worstBites, e.getValue() / points);
            }
            if (sum <= 0)
                continue;
            double waste = rawFep > cap ? 1.0 - (cap / rawFep) : 0.0;
            int bitesAlone = coversAll ? (int) Math.ceil(worstBites) : -1;
            out.add(new Candidate(r.dish.name, r.hungerCost, rawFep, perBite, sum / r.hungerCost,
                    bitesAlone, waste, r.qualityFallback, r.satMod));
        }
        out.sort((a, b) -> Double.compare(b.goalPointsPerHunger, a.goalPointsPerHunger));
        if (out.size() > MAX_CANDIDATES)
            return new ArrayList<>(out.subList(0, MAX_CANDIDATES));
        return out;
    }

    /**
     * States the assumptions the numbers above are standing on. Every one of these is a place the
     * model is knowingly simpler than the game; a plan that hides them reads as more certain than
     * it is, which is the failure mode worth designing against here.
     */
    private static void addWarnings(List<String> warnings, CharState state, Goal goal,
                                     List<Resolved> resolved, List<PlanRow> rows, double totalHunger,
                                     int totalBites, boolean stalled, boolean hitBarCap,
                                     boolean hitBiteCap, QualityMode qualityMode) {
        if (stalled) {
            StringBuilder sb = new StringBuilder("No dish in the catalog provides ");
            List<String> missing = new ArrayList<>();
            for (String stat : goal.targetPoints.keySet()) {
                boolean found = false;
                for (Resolved r : resolved) {
                    Double w = r.weightedFepByStat.get(stat);
                    if (w != null && w > 0) {
                        found = true;
                        break;
                    }
                }
                if (!found)
                    missing.add(stat);
            }
            if (missing.isEmpty())
                sb = new StringBuilder("Ran out of dishes that still advance the goal.");
            else
                sb.append(String.join(", ", missing)).append(" - upload a dish that does.");
            warnings.add(sb.toString());
        }
        if (hitBiteCap)
            warnings.add("Stopped at the " + MAX_BITES_SAFETY + "-bite safety limit; the goal needs more than one sitting.");
        else if (hitBarCap)
            warnings.add("Stopped at the bar limit before the goal was met.");

        if (totalHunger > HUNGER_PER_FULL_BAR) {
            // The drift is modelled now, so this states what the plan already priced in rather
            // than apologising for holding gmod fixed. It stays a warning because the size of the
            // fall is the thing worth seeing before committing to a multi-bar sitting.
            double endGlut = state.glut + (totalHunger / HUNGER_PER_FULL_BAR);
            warnings.add(String.format(
                    "Costs %.0f‰ - about %.1f full hunger bars, taking you from %.2f to roughly %.2f. Your FEP multiplier falls from %.2fx to %.2fx over that, which this plan has priced in.",
                    totalHunger, totalHunger / HUNGER_PER_FULL_BAR, state.glut, endGlut,
                    state.hungerMod, gmodFor(endGlut)));
        }

        if (!rows.isEmpty() && totalBites >= SINGLE_DISH_WARN_MIN_BITES) {
            int topBites = 0;
            for (PlanRow r : rows)
                topBites = Math.max(topBites, r.bites);
            if (topBites >= totalBites * SINGLE_DISH_WARN_SHARE) {
                warnings.add(String.format(
                        "%d of %d bites are one dish. Its own satiation will climb as you eat it, which is not modelled - see the ranked list for substitutes.",
                        topBites, totalBites));
            }
        }

        int unpriced = 0;
        for (Resolved r : resolved) {
            if (r.touchesGoal && r.dish.satiationKeys.isEmpty())
                unpriced++;
        }
        if (unpriced > 0) {
            warnings.add(unpriced + " goal-relevant dishes have no satiation data recorded yet - hover them in-game once, with the character sheet open, to upload theirs.");
        }
        if (state.satiationPenalty.isEmpty()) {
            warnings.add("No live satiation to apply - planning as if unsatiated.");
        }

        if (qualityMode == QualityMode.PERCENT_OF_MAX) {
            int fallback = 0;
            for (PlanRow r : rows) {
                if (r.qualityFallback)
                    fallback++;
            }
            if (fallback > 0)
                warnings.add(fallback + " dishes in the plan have no recorded quality and were priced at q10.");
        }
    }

    private static Resolved resolve(Dish dish, CharState state, Goal goal,
                                     QualityMode qualityMode, double qualityPct) {
        boolean qualityFallback = false;
        double q;
        if (qualityMode == QualityMode.Q10) {
            q = 10;
        } else {
            if (dish.maxQualitySeen == null) {
                q = 10;
                qualityFallback = true;
            } else {
                q = Math.max(10, qualityPct * dish.maxQualitySeen);
            }
        }

        // Each type a dish carries multiplies in its own live penalty. Satiation in this game is
        // per food type, and a dish frequently *is* its own type - 113 of the 197 foods in the
        // live catalog have a resource name that is itself a satiation key - so what used to be
        // treated as an unmodelled "same-dish" extra on top of category penalties is simply the
        // ordinary case, handled by the same lookup.
        double satMod = 1.0;
        for (String key : dish.satiationKeys)
            satMod *= (1.0 - state.satiationPenalty.getOrDefault(key, 0.0));

        // Deliberately without state.hungerMod: gmod moves as the plan eats, so it is applied per
        // bite by the simulation rather than baked in here. Everything on this line is fixed for
        // the run.
        double fepMult = satMod * state.accountMult * state.tableFoodEventBonus;
        double qFepFactor = Math.sqrt(q / 10.0);
        double qHungerFactor = Math.pow(q / 10.0, 0.25);

        double hungerCost = dish.hunger * qHungerFactor * satMod * state.tableHungerMod;

        Map<String, Double> weightedByStat = new LinkedHashMap<>();
        double rawFep = 0;
        boolean touchesGoal = false;
        for (Fep f : dish.feps) {
            double fep = f.value * qFepFactor * fepMult;
            // Raw fills the bar; tier only multiplies the payout when this entry wins the roll.
            rawFep += fep;
            weightedByStat.merge(f.stat, fep * f.tier, Double::sum);
            if (goal.targetPoints.containsKey(f.stat) && fep > 0)
                touchesGoal = true;
        }

        return new Resolved(dish, weightedByStat, rawFep, hungerCost, satMod, qualityFallback,
                touchesGoal);
    }
}
