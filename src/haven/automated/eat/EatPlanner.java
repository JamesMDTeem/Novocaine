package haven.automated.eat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out which foods, and how many of each, get a character from its current attributes to a
 * set of target attribute points - the eating equivalent of {@code StudyPlanner}.
 *
 * This class deliberately imports nothing from {@code haven}. No Widget, no Coord, no GItem.
 * Every type it needs (Dish, CharState, Goal, Plan) is plain data defined here, for the same
 * reason {@code StudyPlanner} does it: the arithmetic is the part that can actually be wrong, and
 * it should be reasoned about - and eventually tested - without standing up a UI.
 *
 * The objective, worked through in full in the plan this mirrors: a bar of capacity {@code cap}
 * resolves once its accumulated (tier-weighted) FEP reaches {@code cap}, and the odds of any one
 * stat winning that bar are its share of the accumulated total. Composed with "bars filled =
 * totalFEP / cap", the {@code totalFEP} term cancels and the expected points a stat gains from
 * one bar is simply {@code weightedFep(stat) / cap} - independent of how much *other* FEP was
 * mixed in. Off-target FEP therefore never helps or hurts a goal stat, it only costs hunger, which
 * is why the ranking key below is goal-weighted FEP per unit hunger and not raw efficiency.
 *
 * Two things stop this from being a flat one-pass ranking:
 * <ul>
 * <li>Variety reduction only has value while the bar would not already fill without it - once a
 *     single available dish's FEP alone clears the (already-reduced) cap, buying more variety is
 *     moot, since everything above cap is lost on overflow, not banked. So each bar runs two
 *     phases: buy cheap distinct dishes purely to ratchet the cap down for as long as that is
 *     still worth it, then finish the bar on goal-weighted FEP per hunger.</li>
 * <li>The cap itself moves: it drops as unique foods are eaten this bar, and rises again (to a
 *     new, larger value) once a goal stat's growth pushes it past the character's other
 *     attributes. Both have to be simulated bar by bar, not solved for in one shot.</li>
 * </ul>
 *
 * What this cannot promise: level-ups are probabilistic, so the output is *expected* points, not
 * a guarantee. Bar resolution advances every attribute's running expectation by its
 * {@code weightedFep(stat) / cap} share (not just the goal stats - a bar's off-target FEP still
 * competes for the shared cap and can, in expectation, be the one that raises the character's
 * ceiling), and the next bar's cap is that expectation's current maximum - a continuous
 * approximation of what is really a sequence of discrete, randomly-timed jumps. The two-phase
 * greedy is a heuristic, like {@code StudyPlanner}'s single-pass sort, not an exact solve of what
 * is genuinely a knapsack with a moving capacity - checkable by eye, not guaranteed optimal.
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
        /** Hunger cost per bite, at q10. */
        public final double hunger;
        /** Satiation groups (category names) this dish drains. */
        public final List<String> satiationGroups;
        /** Highest quality any client has reported eating this dish, or null if never tagged. */
        public final Double maxQualitySeen;

        public Dish(String name, List<Fep> feps, double hunger, List<String> satiationGroups,
                    Double maxQualitySeen) {
            this.name = name;
            this.feps = feps;
            this.hunger = hunger;
            this.satiationGroups = satiationGroups;
            this.maxQualitySeen = maxQualitySeen;
        }
    }

    /** The character state the plan is run against - a snapshot, not a live handle. */
    public static final class CharState {
        /** Current base attribute value per stat abbreviation (STR, AGI, INT, CON, PER, CHA, DEX, WILL, PSY). */
        public final Map<String, Integer> attrs;
        /** FEP multiplier from current hunger level (BAttrWnd.GlutMeter.gmod). */
        public final double hungerMod;
        /** Satiation penalty [0,1) per group name; a group not present means unsatiated. */
        public final Map<String, Double> satiationPenalty;
        public final double accountMult;
        public final double tableFoodEventBonus;
        public final double tableHungerMod;
        /** The live, already variety-reduced FEP cap - used only for the very first bar simulated. */
        public final double startCap;
        /**
         * Measured (or wiki-fallback) cap reduction per unique food eaten this bar, at the
         * current hunger level. See the plan's "First real session" section: this is not a
         * constant across hunger levels, and should be re-measured rather than hardcoded.
         */
        public final double varietyReductionPerUniqueFood;

        public CharState(Map<String, Integer> attrs, double hungerMod, Map<String, Double> satiationPenalty,
                          double accountMult, double tableFoodEventBonus, double tableHungerMod,
                          double startCap, double varietyReductionPerUniqueFood) {
            this.attrs = attrs;
            this.hungerMod = hungerMod;
            this.satiationPenalty = satiationPenalty;
            this.accountMult = accountMult;
            this.tableFoodEventBonus = tableFoodEventBonus;
            this.tableHungerMod = tableHungerMod;
            this.startCap = startCap;
            this.varietyReductionPerUniqueFood = varietyReductionPerUniqueFood;
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
        /** True if this dish has no recorded quality and PERCENT_OF_MAX fell back to q10 for it. */
        public final boolean qualityFallback;

        PlanRow(String dish, int bites, double totalHunger, boolean qualityFallback) {
            this.dish = dish;
            this.bites = bites;
            this.totalHunger = totalHunger;
            this.qualityFallback = qualityFallback;
        }
    }

    public static final class Plan {
        /** Ranked by total hunger spent on each dish, most first. */
        public final List<PlanRow> rows;
        /** Expected points gained per goal stat - not a guarantee, see the class doc. */
        public final Map<String, Double> expectedPoints;
        public final double totalHunger;
        public final int barsSimulated;
        /** True if every goal stat's expected points met its target within {@code maxBars}. */
        public final boolean goalMet;
        /** True if the catalog ran out of eligible dishes before the goal (or maxBars) was reached. */
        public final boolean stalled;

        Plan(List<PlanRow> rows, Map<String, Double> expectedPoints, double totalHunger,
             int barsSimulated, boolean goalMet, boolean stalled) {
            this.rows = rows;
            this.expectedPoints = expectedPoints;
            this.totalHunger = totalHunger;
            this.barsSimulated = barsSimulated;
            this.goalMet = goalMet;
            this.stalled = stalled;
        }
    }

    /** Resolved per-dish numbers for one simulation run - quality settled, formulas applied once. */
    private static final class Resolved {
        final Dish dish;
        final Map<String, Double> weightedFepByStat; // tier already multiplied in
        final double totalWeightedFep;                // summed across every stat, not just goal ones
        final double hungerCost;
        final double score;                            // goal-weighted FEP per unit hunger
        final boolean qualityFallback;

        Resolved(Dish dish, Map<String, Double> weightedFepByStat, double totalWeightedFep,
                 double hungerCost, double score, boolean qualityFallback) {
            this.dish = dish;
            this.weightedFepByStat = weightedFepByStat;
            this.totalWeightedFep = totalWeightedFep;
            this.hungerCost = hungerCost;
            this.score = score;
            this.qualityFallback = qualityFallback;
        }
    }

    private static final int MAX_BARS_SAFETY = 2000;

    public static Plan plan(List<Dish> catalog, CharState state, Goal goal,
                             QualityMode qualityMode, double qualityPct, int maxBars) {
        int barCap = Math.min(maxBars, MAX_BARS_SAFETY);

        List<Resolved> resolved = new ArrayList<>(catalog.size());
        for (Dish d : catalog) {
            Resolved r = resolve(d, state, goal, qualityMode, qualityPct);
            if (r != null)
                resolved.add(r);
        }

        Map<String, Double> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : state.attrs.entrySet())
            attrs.put(e.getKey(), (double) e.getValue());
        Map<String, Double> initial = new LinkedHashMap<>(attrs);

        Map<String, Integer> bites = new LinkedHashMap<>();
        Map<String, Double> hungerByDish = new LinkedHashMap<>();
        Map<String, Boolean> fallbackByDish = new LinkedHashMap<>();
        double totalHunger = 0;
        int barsSimulated = 0;
        boolean stalled = false;

        double cap = state.startCap;

        while (barsSimulated < barCap && !goalMet(attrs, initial, goal)) {
            if (resolved.isEmpty()) {
                stalled = true;
                break;
            }

            java.util.Set<String> eatenThisBar = new java.util.HashSet<>();
            Map<String, Double> barFep = new LinkedHashMap<>();
            double barWeightedTotal = 0;

            // Phase 1: buy cheap distinct dishes purely for the variety credit, for as long as a
            // unique bite is cheaper than the reduction it buys, and as long as buying more is
            // not already moot because some available dish could clear the cap alone.
            while (true) {
                if (bestSingleWeighted(resolved) >= cap)
                    break; // one dish alone would already clear it - nothing left to buy

                Resolved cheapestNew = null;
                for (Resolved r : resolved) {
                    if (eatenThisBar.contains(r.dish.name))
                        continue;
                    if (cheapestNew == null || r.hungerCost < cheapestNew.hungerCost)
                        cheapestNew = r;
                }
                if (cheapestNew == null || cheapestNew.hungerCost >= state.varietyReductionPerUniqueFood)
                    break;

                eatenThisBar.add(cheapestNew.dish.name);
                cap = Math.max(1.0, cap - state.varietyReductionPerUniqueFood);
                barWeightedTotal += applyBite(cheapestNew, barFep, bites, hungerByDish, fallbackByDish);
                totalHunger += cheapestNew.hungerCost;
            }

            // Phase 2: finish the bar on goal-weighted FEP per hunger.
            int guard = 0;
            while (barWeightedTotal < cap && guard++ < 100000) {
                Resolved best = null;
                for (Resolved r : resolved) {
                    if (r.hungerCost <= 0)
                        continue;
                    if (best == null || r.score > best.score)
                        best = r;
                }
                if (best == null) {
                    stalled = true;
                    break;
                }
                eatenThisBar.add(best.dish.name);
                barWeightedTotal += applyBite(best, barFep, bites, hungerByDish, fallbackByDish);
                totalHunger += best.hungerCost;
            }
            if (guard >= 100000)
                stalled = true;

            for (Map.Entry<String, Double> e : barFep.entrySet())
                attrs.merge(e.getKey(), e.getValue() / cap, Double::sum);

            // Next bar starts fresh (no variety credit carries over) at the character's current
            // expected ceiling - which may have just grown if a bar's off-target FEP happened, in
            // expectation, to be what pushed some attribute past the others.
            double newCap = cap;
            if (!attrs.isEmpty()) {
                newCap = Double.NEGATIVE_INFINITY;
                for (double v : attrs.values())
                    newCap = Math.max(newCap, v);
            }
            cap = newCap;

            barsSimulated++;
            if (stalled)
                break;
        }

        List<PlanRow> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : bites.entrySet()) {
            rows.add(new PlanRow(e.getKey(), e.getValue(), hungerByDish.getOrDefault(e.getKey(), 0.0),
                    fallbackByDish.getOrDefault(e.getKey(), false)));
        }
        rows.sort((a, b) -> Double.compare(b.totalHunger, a.totalHunger));

        Map<String, Double> expectedPoints = new LinkedHashMap<>();
        for (String stat : goal.targetPoints.keySet())
            expectedPoints.put(stat, attrs.getOrDefault(stat, 0.0) - initial.getOrDefault(stat, 0.0));

        return new Plan(rows, expectedPoints, totalHunger, barsSimulated,
                goalMet(attrs, initial, goal), stalled);
    }

    private static double applyBite(Resolved r, Map<String, Double> barFep, Map<String, Integer> bites,
                                     Map<String, Double> hungerByDish, Map<String, Boolean> fallbackByDish) {
        for (Map.Entry<String, Double> e : r.weightedFepByStat.entrySet())
            barFep.merge(e.getKey(), e.getValue(), Double::sum);
        bites.merge(r.dish.name, 1, Integer::sum);
        hungerByDish.merge(r.dish.name, r.hungerCost, Double::sum);
        fallbackByDish.putIfAbsent(r.dish.name, r.qualityFallback);
        return r.totalWeightedFep;
    }

    private static double bestSingleWeighted(List<Resolved> resolved) {
        double best = 0;
        for (Resolved r : resolved)
            best = Math.max(best, r.totalWeightedFep);
        return best;
    }

    private static boolean goalMet(Map<String, Double> attrs, Map<String, Double> initial, Goal goal) {
        for (Map.Entry<String, Double> e : goal.targetPoints.entrySet()) {
            double gained = attrs.getOrDefault(e.getKey(), 0.0) - initial.getOrDefault(e.getKey(), 0.0);
            if (gained < e.getValue())
                return false;
        }
        return true;
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

        double satMod = 1.0;
        for (String group : dish.satiationGroups)
            satMod *= (1.0 - state.satiationPenalty.getOrDefault(group, 0.0));
        // Same-dish satiation (see the plan's "First real session" finding: a specific dish's own
        // satiation stacks with its category groups) has no server-side source yet, so ships as a
        // no-op factor here rather than pretending it is accounted for.
        double dishPenalty = 0.0;
        satMod *= (1.0 - dishPenalty);

        double fepMult = state.hungerMod * satMod * state.accountMult * state.tableFoodEventBonus;
        double qFepFactor = Math.sqrt(q / 10.0);
        double qHungerFactor = Math.pow(q / 10.0, 0.25);

        double hungerCost = dish.hunger * qHungerFactor * satMod * state.tableHungerMod;

        Map<String, Double> weightedByStat = new LinkedHashMap<>();
        double totalWeighted = 0;
        double goalWeighted = 0;
        for (Fep f : dish.feps) {
            double fep = f.value * qFepFactor * fepMult;
            double weighted = fep * f.tier;
            weightedByStat.merge(f.stat, weighted, Double::sum);
            totalWeighted += weighted;
            Double target = goal.targetPoints.get(f.stat);
            if (target != null)
                goalWeighted += weighted;
        }

        double score = hungerCost > 0 ? goalWeighted / hungerCost : 0;

        return new Resolved(dish, weightedByStat, totalWeighted, hungerCost, score, qualityFallback);
    }
}
