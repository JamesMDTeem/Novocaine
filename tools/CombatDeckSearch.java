/*
 * Which deck to take, and which five to keep.
 *
 *   javac -d %TEMP%\decksearch -sourcepath src src\haven\combat\data\Pack.java tools\CombatDeckSearch.java
 *   java -cp %TEMP%\decksearch CombatDeckSearch                 (every opponent, one deck each)
 *   java -cp %TEMP%\decksearch CombatDeckSearch -n 5            (five of each at once)
 *   java -cp %TEMP%\decksearch CombatDeckSearch -aim safest
 *
 * NOT part of the client build.
 *
 * THE OPTIMIZER ANSWERS "WHICH CARD", AND THIS ASKS "WHICH CARDS". A deck is ten cards and
 * thirty points with at most five on any one - confirmed against 791 dumps, where 558 hold
 * the full ten and 336 spend all thirty - so the deck is a choice made before the fight and
 * the plan is a choice made during it. Nothing here searched the first one.
 *
 * Greedy on points, not on cards. Thirty rounds, each spending one point wherever it buys
 * the most, which is 41 candidates a round rather than every subset of 41 cards. Greedy is
 * not optimal and this says so; what it is, is honest about its objective and cheap enough
 * to run over every opponent.
 *
 * THE FIVE-DECK QUESTION IS A COVERAGE PROBLEM. The game saves five decks, so the useful
 * answer is not one deck per creature but a handful that between them are good enough
 * everywhere. Having built the per-opponent best, this picks five by greedy max-coverage:
 * take the deck with the best total, then the one that most improves what it was worst at.
 * Greedy coverage is within a known factor of optimal and, more to the point, it is
 * legible - every pick comes with the list of what it was picked for.
 */

import haven.combat.Advisor;
import haven.combat.Combatant;
import haven.combat.FoeModel;
import haven.combat.Move;
import haven.combat.Optimizer;
import haven.combat.data.Pack;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CombatDeckSearch {
    static final int MAX_CARDS = 10, MAX_POINTS = 30, MAX_PER_CARD = 5;
    /* Beam for the inner search. 20 costs 1.5 ms against 4 at 60, and this runs it tens of
     * thousands of times; the frontier it loses is not one a deck comparison can see. */
    static final int BEAM = 20;
    static final long HORIZON = 2500;

    static final class Deck {
        final Map<String, Integer> levels = new LinkedHashMap<String, Integer>();
        double score = Double.NaN;

        int points() {
            int n = 0;
            for(int v : levels.values())
                n += v;
            return(n);
        }

        List<Move> moves(Map<String, Move> sheet) {
            List<Move> out = new ArrayList<Move>();
            for(Map.Entry<String, Integer> e : levels.entrySet()) {
                Move m = sheet.get(e.getKey());
                if(m == null)
                    continue;
                out.add((e.getValue() > 1) ? m.withMu(mu(e.getValue())) : m);
            }
            return(out);
        }

        Deck copy() {
            Deck d = new Deck();
            d.levels.putAll(levels);
            return(d);
        }
    }

    /** Linear across the five levels, the curve the corpus settled on. */
    static double mu(int level) {
        return(1.0 + (0.125 * (level - 1)));
    }

    /* Not killing is worse than any kill, and among non-kills getting closer is better. */
    static final double NO_KILL = 1e9;

    /**
     * How good a deck is against one opponent. Lower is better.
     *
     * IT HAS TO BE CONTINUOUS OR THE GREEDY STOPS AFTER TWO POINTS. Ticks are integers, so
     * a point that raises a card from mu 1.0 to 1.125 usually leaves the tick count exactly
     * where it was - the search sees no improvement, takes nothing, and stops with a
     * two-card deck and twenty-eight points unspent. That is what the first run did.
     *
     * So the score is layered. A kill beats any non-kill; among kills the aim decides which
     * term dominates and the other breaks ties; among non-kills, the opponent left with
     * less health is closer. Every point spent then has somewhere to show up.
     */
    static double score(Deck d, Map<String, Move> sheet, Combatant me, Combatant foe,
                        FoeModel model, Advisor.Aim aim) {
        List<Move> deck = d.moves(sheet);
        if(deck.isEmpty())
            return(Double.POSITIVE_INFINITY);
        List<Optimizer.Plan> front = Optimizer.search(me, foe, deck, model, BEAM, HORIZON);
        Optimizer.Plan best = Advisor.choose(front, aim, Double.MAX_VALUE);
        if(best == null)
            return(Double.POSITIVE_INFINITY);
        if(!best.killed)
            return(NO_KILL + Math.max(0, best.foeHp));
        double hp = Double.isNaN(best.hpLost) ? 0 : best.hpLost;
        return((aim == Advisor.Aim.SAFEST) ? ((hp * 1000.0) + best.ticks)
                                           : ((best.ticks * 1000.0) + hp));
    }

    /** The score as a person reads it: ticks for FASTEST, hitpoints for SAFEST. */
    static double headline(double score, Advisor.Aim aim) {
        if(score >= NO_KILL)
            return(Double.NaN);
        return(Math.floor(score / 1000.0));
    }

    /**
     * Whether the search can be believed for this deck against this opponent.
     *
     * ADDING A CARD CAN NEVER MAKE THE OPTIMUM WORSE - the plan that ignores it is still
     * available - so if dropping a card IMPROVES the score, the beam lost the winning line
     * and the number above it is not the deck's, it is the search's.
     *
     * It is not a hypothetical. Against the cave angler, ticks by deck size run 736, 705,
     * 705, 884 at beam 20 and 736, 661, 674, 669, 683, 692 at beam 2000 - still rising, a
     * hundredfold beam later. Against the ants every beam from 20 up is monotone. The
     * difference is the length of the fight: a 38-tick kill is two cards deep and a
     * 700-tick one is thirty, and a beam prunes at every one of those depths.
     *
     * So this is reported per opponent rather than assumed away. The same argument the
     * optimizer's own beamWasEnough makes about the initiative curve.
     */
    static boolean beamHeld(Deck d, Map<String, Move> sheet, Combatant me, Combatant foe,
                            FoeModel model, Advisor.Aim aim, double score) {
        for(String res : new ArrayList<String>(d.levels.keySet())) {
            Deck t = d.copy();
            t.levels.remove(res);
            if(t.levels.isEmpty())
                continue;
            if(score(t, sheet, me, foe, model, aim) < score)
                return(false);
        }
        return(true);
    }

    /* How many of the round's best candidates get a second point tried on top of them.
     * One ply stops the moment a card is worth nothing on its own, and cards come in
     * pairs - a setup and the attack that spends it. Two plies at five candidates costs
     * six times a round rather than forty. */
    static final int LOOKAHEAD = 5;

    /**
     * Thirty rounds, each spending one point wherever it buys the most, two plies deep.
     *
     * TWO THINGS ARE GOING ON AND BOTH ARE ADMITTED. A single point is often worth nothing
     * by itself - a card that opens is worth what the card that spends the opening then
     * collects - so one-ply greedy stops with a two-card deck and twenty-eight points
     * unspent. That is a property of decks and the lookahead answers it.
     *
     * The other is the search underneath. Adding a card cannot make the true optimum worse,
     * and the beam breaks that on long fights - so a candidate can MEASURE worse while
     * being better. The floor is therefore proven rather than searched: a deck is never
     * scored above the best of the decks it contains, because it can always play their
     * plan. That corrects a known-incomplete search with a fact about the problem, which
     * is a different thing from hiding the incompleteness - the beam report above says how
     * bad it gets and CombatOptimizerCheck holds the invariant where the search is deep
     * enough to satisfy it honestly.
     */
    static Deck build(Map<String, Move> sheet, Combatant me, Combatant foe, FoeModel model,
                      Advisor.Aim aim) {
        Deck cur = new Deck();
        double floor = Double.POSITIVE_INFINITY;
        for(int spent = 0; spent < MAX_POINTS; spent++) {
            List<Deck> cand = new ArrayList<Deck>();
            List<Double> scores = new ArrayList<Double>();
            for(String res : sheet.keySet()) {
                Deck t = plus(cur, res);
                if(t == null)
                    continue;
                cand.add(t);
                scores.add(score(t, sheet, me, foe, model, aim));
            }
            if(cand.isEmpty())
                break;
            /* The best few by one ply, then one more point on each, so a card that only
             * pays once something spends it is still reachable. */
            List<Integer> order = new ArrayList<Integer>();
            for(int i = 0; i < cand.size(); i++)
                order.add(i);
            Collections.sort(order, (x, y) -> Double.compare(scores.get(x), scores.get(y)));
            Deck take = null;
            double takeScore = floor;
            for(int i = 0; (i < LOOKAHEAD) && (i < order.size()); i++) {
                int ix = order.get(i);
                double one = scores.get(ix);
                if(one < takeScore) {
                    takeScore = one;
                    take = cand.get(ix);
                }
                if(cand.get(ix).points() >= MAX_POINTS)
                    continue;
                for(String res : sheet.keySet()) {
                    Deck t2 = plus(cand.get(ix), res);
                    if(t2 == null)
                        continue;
                    if(score(t2, sheet, me, foe, model, aim) < takeScore) {
                        /* the PAIR is what paid, so take the first half and let the next
                         * round buy the second - the floor keeps it from going backwards */
                        takeScore = Math.min(takeScore, one);
                        take = cand.get(ix);
                    }
                }
            }
            if(take == null)
                break;
            cur = take;
            floor = Math.min(floor, takeScore);
        }
        cur.score = floor;
        return(cur);
    }

    /** One more point on `res`, or null when the limits forbid it. */
    static Deck plus(Deck d, String res) {
        Integer have = d.levels.get(res);
        int at = (have == null) ? 0 : have.intValue();
        if(at >= MAX_PER_CARD)
            return(null);
        if((at == 0) && (d.levels.size() >= MAX_CARDS))
            return(null);
        if(d.points() >= MAX_POINTS)
            return(null);
        Deck t = d.copy();
        t.levels.put(res, at + 1);
        return(t);
    }

    public static void main(String[] argv) throws Exception {
        int copies = 1;
        Advisor.Aim aim = Advisor.Aim.FASTEST;
        String only = null;
        for(int i = 0; i < argv.length; i++) {
            if("-n".equals(argv[i]) && ((i + 1) < argv.length))
                copies = Integer.parseInt(argv[++i]);
            else if("-aim".equals(argv[i]) && ((i + 1) < argv.length))
                aim = Advisor.Aim.valueOf(argv[++i].toUpperCase());
            else
                only = argv[i];
        }
        Path root = Paths.get("data", "combat");
        Map<String, Move> sheet = byRes(Pack.moves(root.resolve("moves_sheet.json")));
        Map<String, Pack.Opponent> foes = Pack.opponents(root.resolve("opponents.json"));

        Combatant me = new Combatant("me");
        me.str = 195; me.agi = 192; me.unarmed = 149; me.melee = 243;
        me.weaponDamage = 90; me.weaponQl = 30.4; me.weaponPen = 0.125;
        me.hp = me.maxHp = 303;

        System.out.printf("deck limits: %d cards, %d points, %d per card%n",
                          MAX_CARDS, MAX_POINTS, MAX_PER_CARD);
        System.out.printf("aim: %s   opponents at once: %d%n%n", aim, copies);
        beamNote();
        if(copies > 1)
            crowdNote();

        List<String> names = new ArrayList<String>();
        for(Map.Entry<String, Pack.Opponent> e : foes.entrySet()) {
            Pack.Opponent o = e.getValue();
            if((only != null) && !only.equals(e.getKey()))
                continue;
            if(!o.simulable() || (o.threat == null))
                continue;
            names.add(e.getKey());
        }
        Collections.sort(names);
        Map<String, Deck> best = new LinkedHashMap<String, Deck>();
        int unsure = 0;
        System.out.printf("  %-16s %-8s %-8s %s%n", "opponent",
                          (aim == Advisor.Aim.SAFEST) ? "hp lost" : "ticks",
                          "points", "deck");
        for(String n : names) {
            Pack.Opponent o = foes.get(n);
            Deck d = build(sheet, me, scaled(o, copies), faster(o.threat, copies), aim);
            if(d.score >= NO_KILL)
                continue;
            boolean held = beamHeld(d, sheet, me, scaled(o, copies),
                                    faster(o.threat, copies), aim, d.score);
            if(held)
                best.put(n, d);
            else
                unsure++;
            System.out.printf("  %-16s %-8.0f %-8d %-52s %s%n",
                              n.substring(0, Math.min(16, n.length())),
                              headline(d.score, aim), d.points(),
                              shorten(d, sheet),
                              held ? "" : "<- beam lost the line; not counted");
        }
        System.out.println();
        System.out.printf("  %d opponent(s) had a deck the search can stand behind;"
                          + " %d did not.%n", best.size(), unsure);
        if(best.size() < 2) {
            System.out.println();
            System.out.println("not enough opponents to choose a set of decks from.");
            return;
        }
        cover(best, sheet, me, foes, aim, copies);
    }

    static void beamNote() {
        System.out.println("READ THE DECKS AND NOT THE POINT COUNTS. Adding a card can never make");
        System.out.println("the true optimum worse - the plan that ignores it is still there - and");
        System.out.println("the optimizer breaks that on long fights. Ticks by deck size against the");
        System.out.println("cave angler run 736, 705, 705, 884 at beam 20 and 736, 661, 674, 669,");
        System.out.println("683, 692 at beam 2000: still rising, a hundredfold beam later. Against");
        System.out.println("the ants every beam from 20 up is monotone. The difference is length - a");
        System.out.println("38-tick kill is two cards deep and a 700-tick one is thirty, and a beam");
        System.out.println("prunes at every one of those depths.");
        System.out.println();
        System.out.println("So the greedy below stops early, because a third card often measures");
        System.out.println("worse than two when it is not. Which cards are chosen, and which decks");
        System.out.println("cover which opponents, is worth reading. How many points they spend is");
        System.out.println("not: it is the beam's answer, not the deck's.");
        System.out.println();
    }

    static void crowdNote() {
        System.out.println("MORE THAN ONE OF THEM IS A MEAN-FIELD APPROXIMATION, and this says");
        System.out.println("so rather than hiding it. The simulator is one against one. N of them");
        System.out.println("are modelled as one opponent with N times the health on a clock running");
        System.out.println("(N+1)/2 times as fast - the average number still alive while they are");
        System.out.println("killed one at a time. That gets the ratio which decides a deck right");
        System.out.println("and the endgame wrong: a real crowd gets quieter as it dies and this");
        System.out.println("one does not.");
        System.out.println();
    }

    /** The opponent, N of them, as one - see crowdNote. */
    static Combatant scaled(Pack.Opponent o, int copies) {
        Combatant c = o.toughest();
        if(copies > 1)
            c.hp = c.maxHp = c.maxHp * copies;
        return(c);
    }

    static FoeModel faster(FoeModel m, int copies) {
        if(copies <= 1)
            return(m);
        long period = Math.max(1, Math.round(m.period / ((copies + 1) / 2.0)));
        return(new FoeModel(period, m.pressure, m.pressureAgainst, m.damageCoef,
                            m.nGaps, m.nHits, m.fleesBelow, m.modes));
    }

    /** Five decks, chosen greedily for what they cover between them. */
    static void cover(Map<String, Deck> best, Map<String, Move> sheet, Combatant me,
                      Map<String, Pack.Opponent> foes, Advisor.Aim aim, int copies) {
        List<String> owners = new ArrayList<String>(best.keySet());
        Map<String, Map<String, Double>> grid = new LinkedHashMap<String, Map<String, Double>>();
        for(String owner : owners) {
            Map<String, Double> row = new LinkedHashMap<String, Double>();
            for(String against : owners) {
                Pack.Opponent o = foes.get(against);
                row.put(against, score(best.get(owner), sheet, me, scaled(o, copies),
                                       faster(o.threat, copies), aim));
            }
            grid.put(owner, row);
        }
        System.out.println();
        System.out.println("FIVE DECKS, CHOSEN FOR WHAT THEY COVER BETWEEN THEM");
        System.out.println("  the game saves five, so the question is not one deck per creature");
        System.out.println("  but a handful that are good enough everywhere. Greedy: the best");
        System.out.println("  total first, then whatever most improves what it was worst at.");
        System.out.println();
        List<String> chosen = new ArrayList<String>();
        Map<String, Double> bestSoFar = new LinkedHashMap<String, Double>();
        for(String a : owners)
            bestSoFar.put(a, Double.POSITIVE_INFINITY);
        for(int pick = 0; pick < 5; pick++) {
            String take = null;
            double takeTotal = Double.POSITIVE_INFINITY;
            for(String owner : owners) {
                if(chosen.contains(owner))
                    continue;
                double tot = 0;
                for(String a : owners)
                    tot += Math.min(bestSoFar.get(a), grid.get(owner).get(a));
                if(tot < takeTotal) {
                    takeTotal = tot;
                    take = owner;
                }
            }
            if((take == null) || (takeTotal >= (NO_KILL * owners.size())))
                break;
            List<String> won = new ArrayList<String>();
            for(String a : owners) {
                double v = grid.get(take).get(a);
                if(v < bestSoFar.get(a)) {
                    bestSoFar.put(a, v);
                    won.add(a);
                }
            }
            chosen.add(take);
            System.out.printf("  %d. the %s deck - best against %d of %d%n",
                              pick + 1, take, won.size(), owners.size());
            System.out.printf("     %s%n", shorten(best.get(take), sheet));
            System.out.printf("     carries: %s%n", join(won, 10));
        }
        int covered = 0;
        for(String a : owners) {
            if(bestSoFar.get(a) < NO_KILL)
                covered++;
        }
        System.out.println();
        System.out.printf("  %d of %d opponents are killed by one of those five.%n",
                          covered, owners.size());
    }

    static String join(List<String> xs, int max) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; (i < xs.size()) && (i < max); i++) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(xs.get(i));
        }
        if(xs.size() > max)
            sb.append(" and ").append(xs.size() - max).append(" more");
        return(sb.toString());
    }

    static String shorten(Deck d, Map<String, Move> sheet) {
        StringBuilder sb = new StringBuilder();
        for(Map.Entry<String, Integer> e : d.levels.entrySet()) {
            Move m = sheet.get(e.getKey());
            if(sb.length() > 0)
                sb.append(", ");
            sb.append((m == null) ? e.getKey() : m.name).append(' ').append(e.getValue());
        }
        return(sb.toString());
    }

    static Map<String, Move> byRes(Map<String, Move> named) {
        Map<String, Move> out = new LinkedHashMap<String, Move>();
        for(Move m : named.values()) {
            if(m.res != null)
                out.put(m.res, m);
        }
        return(out);
    }
}
