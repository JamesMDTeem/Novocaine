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
 * Variety pulls it back down for the duration of a bar. The server's own calibration defines the
 * coefficient as {@code reduction / sqrt(settledCap)} (see {@code EatLogService}), and the same
 * log replay confirms both the shape and the magnitude: at {@code gmod ~= 3.0} the measured
 * coefficient for a single unique food is <b>1.117 +/- 0.113 over 128 samples</b>, against the
 * wiki table's 1.097. The scaling past the first unique food is the part that is <i>not</i>
 * settled - the same replay gives 1.53 at n=2 and 1.57 at n=3 for that bucket, on 27 and 5
 * samples - so {@link #capFor} uses {@code sqrt(n)}, which reproduces the well-sampled n=1 case
 * exactly (and so stays consistent with the server's definition) and is inside the noise at n=2.
 * Treat n>=3 as a guess with the sample count attached, not as knowledge.
 *
 * <h2>What this still cannot promise</h2>
 *
 * Level-ups are probabilistic, so the output is <i>expected</i> points, not a guarantee - a plan
 * that expects +5 CON is not a plan that yields +5 CON.
 *
 * Satiation is joined exactly, by live satiation-entry key, but it is still a <i>snapshot</i>: it does
 * not rise as the plan eats, because nothing measures the per-eat increment yet. A plan that leans
 * hard on one dish will therefore underperform, and says so through {@link Plan#warnings} rather
 * than pretending otherwise. The hunger multiplier {@code gmod} is likewise frozen at its current
 * value even though eating drives it down. The bite-by-bite greedy is a heuristic, not an exact
 * solve of what is genuinely a knapsack with a moving capacity.
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
        /**
         * Variety coefficient for the current hunger level, in the server's units: the cap
         * reduction one unique food buys is {@code coefficient * sqrt(settledCap)}. See the class
         * doc for the measured values - this is a rate, never an absolute number of FEP.
         */
        public final double varietyCoefficient;
        /** Live hunger meter reading; 1.0 is one full bar, matching {@code BAttrWnd.GlutMeter.glut}. */
        public final double glut;

        public CharState(Map<String, Integer> attrs, double hungerMod, Map<String, Double> satiationPenalty,
                          double accountMult, double tableFoodEventBonus, double tableHungerMod,
                          double startCap, double varietyCoefficient, double glut) {
            this.attrs = attrs;
            this.hungerMod = hungerMod;
            this.satiationPenalty = satiationPenalty;
            this.accountMult = accountMult;
            this.tableFoodEventBonus = tableFoodEventBonus;
            this.tableHungerMod = tableHungerMod;
            this.startCap = startCap;
            this.varietyCoefficient = varietyCoefficient;
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

    /** Resolved per-dish numbers for one simulation run - quality settled, formulas applied once. */
    private static final class Resolved {
        final Dish dish;
        /** Tier already multiplied in - this is the expected-points numerator, never the bar fill. */
        final Map<String, Double> weightedFepByStat;
        /** Untiered sum across every stat: what actually fills the bar. */
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

        List<Candidate> candidates = rankCandidates(resolved, liveCap, goal);

        // The live cap may already be part-way through this bar's variety reduction. Recover the
        // settled cap the same way EatLogService detects one - a settled cap is integral - and
        // infer how much variety credit is already spent so the first bar continues from where the
        // character actually is instead of restarting it.
        double startBaseCap = settledBase(liveCap);
        double baseCap = startBaseCap;
        int varietyN = inferVarietyN(startBaseCap, liveCap, state.varietyCoefficient);
        double cap = capFor(baseCap, varietyN, state.varietyCoefficient);

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
            double bestCap = cap;
            boolean bestIsNew = false;

            for (Resolved r : resolved) {
                if (r.hungerCost <= 0 || !r.touchesGoal)
                    continue;
                boolean isNew = !eatenThisBar.contains(r.dish.name);
                double nCap = isNew ? capFor(baseCap, varietyN + 1, state.varietyCoefficient) : cap;
                // What this bar will actually resolve at if this bite is the one that fills it -
                // which is also what the expected-points denominator will be. A bite that
                // overshoots by 2x therefore scores half as well as its raw numbers suggest.
                double projected = Math.max(nCap, barRaw + r.rawFep);
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
                    gain += Math.min(w / projected, rem);
                }
                if (gain <= 0)
                    continue;
                double score = gain / r.hungerCost;
                if (best == null || score > bestScore) {
                    best = r;
                    bestScore = score;
                    bestCap = nCap;
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
                cap = bestCap;
            }
            barRaw += best.rawFep;
            Map<String, Double> mine = barWeightedByDish
                    .computeIfAbsent(best.dish.name, k -> new LinkedHashMap<>());
            for (Map.Entry<String, Double> e : best.weightedFepByStat.entrySet()) {
                barWeighted.merge(e.getKey(), e.getValue(), Double::sum);
                mine.merge(e.getKey(), e.getValue(), Double::sum);
            }
            totalHunger += best.hungerCost;
            totalBites++;
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
                barsSimulated++;

                double top = initialTop;
                for (Map.Entry<String, Integer> e : initialAttrs.entrySet())
                    top = Math.max(top, e.getValue() + settledGain.getOrDefault(e.getKey(), 0.0));
                baseCap = startBaseCap + Math.max(0, top - initialTop);
                cap = capFor(baseCap, 0, state.varietyCoefficient);
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
    private static double settledBase(double liveCap) {
        if (liveCap <= 0)
            return CAP_FLOOR;
        double rounded = Math.round(liveCap);
        if (Math.abs(liveCap - rounded) < 1e-6)
            return rounded;
        return Math.ceil(liveCap);
    }

    /** How many unique foods the live cap looks like it has already paid for this bar. */
    private static int inferVarietyN(double baseCap, double liveCap, double coefficient) {
        if (coefficient <= 0 || baseCap <= liveCap)
            return 0;
        double unit = coefficient * Math.sqrt(baseCap);
        if (unit <= 0)
            return 0;
        double ratio = (baseCap - liveCap) / unit;
        int n = (int) Math.round(ratio * ratio);
        return Math.max(0, Math.min(n, 64));
    }

    /**
     * The effective cap after {@code n} unique foods this bar. See the class doc: the coefficient
     * is a rate against {@code sqrt(settledCap)}, matching the server's own definition at n=1,
     * with {@code sqrt(n)} carrying it past there.
     */
    private static double capFor(double baseCap, int n, double coefficient) {
        if (n <= 0 || coefficient <= 0)
            return Math.max(CAP_FLOOR, baseCap);
        double reduction = coefficient * Math.sqrt(baseCap) * Math.sqrt(n);
        return Math.max(CAP_FLOOR, baseCap - reduction);
    }

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
    private static List<Candidate> rankCandidates(List<Resolved> resolved, double cap, Goal goal) {
        List<Candidate> out = new ArrayList<>();
        for (Resolved r : resolved) {
            if (!r.touchesGoal || r.hungerCost <= 0)
                continue;
            double projected = Math.max(cap, r.rawFep);
            Map<String, Double> perBite = new LinkedHashMap<>();
            double sum = 0;
            boolean coversAll = true;
            double worstBites = 0;
            for (Map.Entry<String, Double> e : goal.targetPoints.entrySet()) {
                Double w = r.weightedFepByStat.get(e.getKey());
                double points = (w == null) ? 0 : w / projected;
                perBite.put(e.getKey(), points);
                sum += points;
                if (points <= 0)
                    coversAll = false;
                else
                    worstBites = Math.max(worstBites, e.getValue() / points);
            }
            if (sum <= 0)
                continue;
            double waste = r.rawFep > cap ? 1.0 - (cap / r.rawFep) : 0.0;
            int bitesAlone = coversAll ? (int) Math.ceil(worstBites) : -1;
            out.add(new Candidate(r.dish.name, r.hungerCost, r.rawFep, perBite, sum / r.hungerCost,
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
            // gmod falls as the hunger meter fills, and nothing here models that curve - so a plan
            // spanning more than a bar is quoted at a multiplier it will not hold for. Saying where
            // the meter starts and where this would push it is the honest version of that.
            double endGlut = state.glut + (totalHunger / HUNGER_PER_FULL_BAR);
            warnings.add(String.format(
                    "Costs %.0f‰ - about %.1f full hunger bars, taking you from %.2f to roughly %.2f. Your FEP multiplier falls as the meter fills, and this plan holds it fixed at %.2fx.",
                    totalHunger, totalHunger / HUNGER_PER_FULL_BAR, state.glut, endGlut, state.hungerMod));
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

        double fepMult = state.hungerMod * satMod * state.accountMult * state.tableFoodEventBonus;
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
