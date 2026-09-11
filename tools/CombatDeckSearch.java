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
    /**
     * The card limit is measured; the other two are read from the client's own dump.
     *
     * Ten cards and five on any one card come from the dumps themselves - 558 of 791 hold
     * ten and none holds more. The points and the number of saved decks the client states
     * outright in the sheet, and these were literals copying it. A copy of a fact does
     * not move when the fact does, and an audit that asks "does anything read this" is
     * exactly how a stale copy gets found.
     */
    static final int MAX_CARDS = 10, MAX_PER_CARD = 5;
    static int MAX_POINTS = 30, SAVED_DECKS = 5;
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
        /* A deck with no stance is not a legal deck - one is always up - so it is scored
         * as unable rather than as a fast deck that happens to skip the block weight. */
        if(!hasStance(d, sheet))
            return(NO_KILL * 2);
        List<Optimizer.Plan> front = Optimizer.search(withStance(me, d, sheet), foe, deck,
                                                      model, BEAM, HORIZON);
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
    /**
     * How good a deck is, by whatever standard the caller is applying. Lower is better.
     *
     * The greedy below does not care what the number means, only that it can be compared,
     * so pulling it behind an interface lets the same search answer a different question.
     * It was written for "how fast does this kill that animal"; a duel between two people
     * asks "how does this fare against what they are actually likely to bring", which is
     * an expectation over an opponent's mixed strategy and not a kill time at all.
     */
    interface Scorer {
        double score(Deck d);
    }

    static Deck build(Map<String, Move> sheet, Combatant me, Combatant foe, FoeModel model,
                      Advisor.Aim aim) {
        final Map<String, Move> sh = sheet;
        final Combatant m = me, f = foe;
        final FoeModel md = model;
        final Advisor.Aim am = aim;
        return(build(sheet, new Scorer() {
            public double score(Deck d) {
                return(CombatDeckSearch.score(d, sh, m, f, md, am));
            }
        }));
    }

    static Deck build(Map<String, Move> sheet, Scorer sc) {
        Deck cur = new Deck();
        double floor = Double.POSITIVE_INFINITY;
        for(int spent = 0; spent < MAX_POINTS; spent++) {
            List<Deck> cand = new ArrayList<Deck>();
            List<Double> scores = new ArrayList<Double>();
            for(String res : sheet.keySet()) {
                Deck t = plus(cur, res, sheet);
                if(t == null)
                    continue;
                cand.add(t);
                scores.add(sc.score(t));
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
                    Deck t2 = plus(cand.get(ix), res, sheet);
                    if(t2 == null)
                        continue;
                    if(sc.score(t2) < takeScore) {
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
        cur = topUp(cur, sheet, sc, floor);
        cur.score = sc.score(cur);
        cur.score = Math.min(cur.score, floor);
        return(cur);
    }

    /**
     * Spend what the greedy left behind.
     *
     * The greedy takes a point only when it STRICTLY improves, and it has to - the floor
     * is what keeps an incomplete beam from walking the deck backwards. But strict
     * improvement is the wrong test for the last points, because the score is mostly
     * integers: ticks, and hitpoints lost. A point that lifts a card from mu 1.0 to 1.125
     * very often lands on the same tick, reads as a tie, and is refused. The five decks
     * this produced spent four to six points of thirty and put every card at level one,
     * which is not a deck anyone would build.
     *
     * A tie is not a reason to leave a point unspent. Levels only ever help: mu multiplies
     * the attack weight upward, and on the three cards whose text divides by it - Dash,
     * Take Aim, Think - it shortens the cooldown as well. So the rule here is
     * NON-WORSENING rather than improving, and the remaining budget goes to whichever
     * point scores best even when that equals what we already had.
     *
     * The one card this is not true of is Yield Ground, which opens its own user and so
     * opens them harder at a higher level. Testing the score rather than assuming
     * monotonicity covers it without a special case: a point that reads worse is refused
     * whatever the reason.
     */
    static Deck topUp(Deck cur, Map<String, Move> sheet, Scorer sc, double floor) {
        double best = Math.min(floor, sc.score(cur));
        while(cur.points() < MAX_POINTS) {
            Deck take = null;
            double takeScore = Double.POSITIVE_INFINITY;
            for(String res : sheet.keySet()) {
                Deck t = plus(cur, res, sheet);
                if(t == null)
                    continue;
                double v = sc.score(t);
                if(v < takeScore) {
                    takeScore = v;
                    take = t;
                }
            }
            /* EPS, because these are doubles carrying integer ticks. An exact tie is the
             * common case and has to be accepted, or nothing is spent at all. */
            if((take == null) || (takeScore > (best + 1e-9)))
                break;
            cur = take;
            best = Math.min(best, takeScore);
        }
        return(cur);
    }

    /**
     * One more point on `res`, or null when the limits forbid it.
     *
     * ONE STANCE, AND NEVER TWO. A stance is held on the bar rather than thrown and the
     * decks are unanimous: across 792 dumps holding any cards at all, 782 hold exactly one
     * of the seven and none holds two. So a second is not a worse deck, it is not a deck.
     */
    static Deck plus(Deck d, String res, Map<String, Move> sheet) {
        Integer have = d.levels.get(res);
        int at = (have == null) ? 0 : have.intValue();
        if(at >= MAX_PER_CARD)
            return(null);
        if((at == 0) && (d.levels.size() >= MAX_CARDS))
            return(null);
        if(d.points() >= MAX_POINTS)
            return(null);
        Move m = sheet.get(res);
        if((m != null) && m.stance) {
            /* ONE STANCE, AND IT COSTS EXACTLY ONE POINT. A stance is held rather than
             * thrown, and it does not level: across 990 dumps a stance slot is 0 or 1 and
             * never higher, over 980 held slots. So a second point on one is not a worse
             * deck, it is not a deck - and the search was spending up to five, four of
             * which bought nothing at all while the cards that would have used them went
             * without. */
            if(at >= 1)
                return(null);
            if(hasStance(d, sheet))
                return(null);
        }
        Deck t = d.copy();
        t.levels.put(res, at + 1);
        return(t);
    }

    static boolean hasStance(Deck d, Map<String, Move> sheet) {
        return(stanceOf(d, sheet) != null);
    }

    /** The stance this deck holds, or null while it has not chosen one yet. */
    static Move stanceOf(Deck d, Map<String, Move> sheet) {
        for(String res : d.levels.keySet()) {
            Move m = sheet.get(res);
            if((m != null) && m.stance)
                return(m);
        }
        return(null);
    }

    /**
     * Our side as the deck's stance makes it.
     *
     * A stance decides the block weight - 2.5 for Shield Up, 0.8 for Parry - and two of
     * them decide the attack weight as well, Combat Meditation at a quarter and Oak Stance
     * at a half. Scoring a deck without applying its stance prices a fight against a
     * character that cannot exist, since one stance is always up.
     */
    /**
     * Whether the character being searched for is holding a shield.
     *
     * A static because it is a property of the run, not of any one deck, and threading it
     * through every scorer would touch a dozen signatures to say one thing. Set once from
     * the character; true is the right default because every character in the corpus with
     * armour worth the name is carrying a round shield.
     */
    static boolean HELD_SHIELD = true;

    static Combatant withStance(Combatant base, Deck d, Map<String, Move> sheet) {
        return(withStance(base, d, sheet, HELD_SHIELD));
    }

    /**
     * @param shield whether a shield is in hand, which Shield Up alone cares about - and
     *               cares about by a factor of five, 250% of the block weight with one
     *               against 50% without. Priced at the headline figure regardless, an
     *               unshielded character reads as carrying a tower.
     */
    static Combatant withStance(Combatant base, Deck d, Map<String, Move> sheet,
                                boolean shield) {
        Move st = stanceOf(d, sheet);
        if(st == null)
            return(base);
        Combatant c = base.copy();
        c.blockMult = st.blockMult;
        if((st.blockRequires != null) && !shield && !Double.isNaN(st.blockMultWithout))
            c.blockMult = st.blockMultWithout;
        if(st.blockSkill != null)
            c.blockSkill = c.skill(st.blockSkill);
        c.attackMult = st.attackMult;
        /* Parry's answer to a blow. It hangs off the fighter rather than the card because
         * the thing that has to read it is the blow landing, and the blow is resolved
         * without any idea which stance the defender chose. */
        for(int i = 0; i < 4; i++)
            c.whenAttacked[i] = st.whenAttackedOpens[i];
        return(c);
    }

    public static void main(String[] argv) throws Exception {
        int copies = 1;
        Advisor.Aim aim = Advisor.Aim.FASTEST;
        String only = null;
        /* The character we actually play. Others are in the file and can be named for a
         * comparison, but a run is always ONE of them. */
        String charName = "ZzxcuV3";
        /* creature | player | unknown. Three populations, and a run answers one. */
        String kind = "creature";
        /* Restrict the search to cards the character has actually learned. Off by
         * default, which asks "what would be best if I had everything" - a fair question
         * and the one the tool has always answered, but not the same as "what can I put
         * on the bar tonight". For ZzxcuV3 the two runs are identical: they own all 41.
         * For Shade they are not - Shade has never learned Parry, Oak Stance or Combat
         * Meditation, and the walrus deck below is built on Oak Stance. */
        boolean ownedOnly = false;
        int slotOverride = -1;
        for(int i = 0; i < argv.length; i++) {
            if("-n".equals(argv[i]) && ((i + 1) < argv.length))
                copies = Integer.parseInt(argv[++i]);
            else if("-char".equals(argv[i]) && ((i + 1) < argv.length))
                charName = argv[++i];
            else if("-pvp".equals(argv[i]))
                kind = "player";
            else if("-kind".equals(argv[i]) && ((i + 1) < argv.length))
                kind = argv[++i];
            else if("-owned".equals(argv[i]))
                ownedOnly = true;
            /* The game saves five, but they are not all for creatures - reserving one for
             * players changes which four the greedy picks, because it has to cover the
             * whole roster with fewer and stops being able to afford a specialist. */
            else if("-slots".equals(argv[i]) && ((i + 1) < argv.length))
                slotOverride = Integer.parseInt(argv[++i]);
            else if("-aim".equals(argv[i]) && ((i + 1) < argv.length))
                aim = Advisor.Aim.valueOf(argv[++i].toUpperCase());
            else
                only = argv[i];
        }
        Path root = Paths.get("data", "combat");
        Map<String, Move> sheet = byRes(Pack.moves(root.resolve("moves_sheet.json")));
        // reassigned below when -owned narrows it to what the character has learned
        Map<String, Pack.Opponent> foes = Pack.opponents(root.resolve("opponents.json"));

        /* ONE CHARACTER, AND A REAL ONE. This was six literals - str 195, agi 192,
         * unarmed 149, melee 243, hp 303 - and they were nobody's: melee and unarmed are
         * Shade's exactly, the rest belong to no character in the corpus. So every deck
         * this ever recommended was optimal for someone who does not exist, and the
         * output gave no way to tell, because the numbers had no name on them.
         *
         * Attributes decide the attack and block weights, and those decide which cards
         * are worth points, so this is not a detail - a deck built for 243 melee is not
         * the deck for 158. Mixing two characters does not average them, it invents a
         * third. */
        Map<String, Pack.Fighter> chars = Pack.characters(root.resolve("characters.json"));
        Pack.Fighter who = chars.get(charName);
        if(who == null) {
            System.out.printf("no character named %s. known: %s%n", charName, chars.keySet());
            return;
        }
        Pack.DeckRules rules = Pack.deckRules(root.resolve("moves_sheet.json"));
        MAX_POINTS = rules.maxPoints;
        SAVED_DECKS = (slotOverride > 0) ? slotOverride : rules.saved;
        HELD_SHIELD = who.shield;
        Combatant me = who.combatant();

        if(ownedOnly) {
            Map<String, Move> mine = new LinkedHashMap<String, Move>();
            for(Map.Entry<String, Move> e : sheet.entrySet()) {
                if(who.knows(e.getValue().name))
                    mine.put(e.getKey(), e.getValue());
            }
            System.out.printf("restricted to the %d card(s) %s has learned, of %d%n",
                              mine.size(), who.name, sheet.size());
            sheet = mine;
        }

        System.out.printf("deck limits: %d cards, %d points, %d per card;"
                          + " %d decks saved (points and slots as the client states them)%n",
                          MAX_CARDS, MAX_POINTS, MAX_PER_CARD, SAVED_DECKS);
        System.out.printf("as: %s  (str %.0f, agi %.0f, unarmed %.0f, melee %.0f, hp %.0f, %s)%n",
                          who.name, who.str, who.agi, who.unarmed, who.melee, who.hp,
                          (who.weapon == null) ? "bare-handed"
                              : String.format("%s q%.1f", who.weapon, who.weaponQl));
        System.out.printf("against: %ss%n", kind);
        System.out.printf("aim: %s   opponents at once: %d%n%n", aim, copies);
        beamNote();
        if(copies > 1)
            crowdNote();

        List<String> names = new ArrayList<String>();
        int setAside = 0;
        for(Map.Entry<String, Pack.Opponent> e : foes.entrySet()) {
            Pack.Opponent o = e.getValue();
            if((only != null) && !only.equals(e.getKey()))
                continue;
            if(!o.simulable() || (o.threat == null))
                continue;
            /* PEOPLE AND ANIMALS ARE DIFFERENT PROBLEMS, so a run answers one of them.
             * A player holds a deck at levels with a stance somebody chose, and can
             * change all of it between fights; an animal throws from a fixed list. One
             * deck offered as the answer to "fox, walrus and a person" is not an answer,
             * and the five-deck cover below was quietly building exactly that.
             *
             * The unidentified are a third population and not a rounding error in the
             * second. Most throw animal cards and are creatures whose resource never
             * reached the log, but the pack cannot say which, and a deck recommended
             * for "?#257907829" is unusable anyway - there is nothing to look up in
             * game. Set aside and counted rather than folded into the species. */
            if(!kind.equals(o.kind)) {
                if("unknown".equals(o.kind))
                    setAside++;
                continue;
            }
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
        if(setAside > 0)
            System.out.printf("  %d unidentified opponent(s) set aside - not a species,"
                              + " and nothing to look up in game.%n", setAside);
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
        System.out.println("So the greedy below stops improving early, because a third card often");
        System.out.println("measures worse than two when it is not. The budget is then spent out on");
        System.out.println("whatever does not measure WORSE, since levels only ever help and a tie is");
        System.out.println("no reason to leave a point behind. Which cards are chosen is the finding;");
        System.out.println("the last few levels are the cheapest reading of a tie, not a result.");
        System.out.println();
    }

    static void crowdNote() {
        System.out.println("MORE THAN ONE OF THEM IS A MEAN-FIELD APPROXIMATION, MEASURED. The");
        System.out.println("simulator is one against one, so N of them are modelled as one opponent");
        System.out.println("with N times the health on a clock running (N+1)/2 times as fast - the");
        System.out.println("average number still alive while they are killed one at a time.");
        System.out.println();
        System.out.println("Checked against the corpus, counting opponents that actually SWUNG");
        System.out.println("rather than opponents in view - five ants on screen is not five ants");
        System.out.println("swinging, and that was the first thing the measurement got wrong:");
        System.out.println();
        System.out.println("  attackers   fights   their acts/s   this model");
        System.out.println("  1           2621     1.00x          1.00");
        System.out.println("  2            239     1.50x          1.50");
        System.out.println("  3            144     2.00x          2.00");
        System.out.println("  4             69     1.87x          2.50");
        System.out.println("  5             83     1.61x          3.00");
        System.out.println();
        System.out.println("Exact at two and three, and optimistic past that - a crowd of five hits");
        System.out.println("about 1.6 times as often as one, not three. Read -n 4 and -n 5 as an");
        System.out.println("upper bound on the trouble rather than an estimate of it.");
        System.out.println();
        System.out.println("The openings they land do NOT scale at all: 1.71, 1.50, 1.84, 1.79 and");
        System.out.println("1.53 points a second from one attacker through five. That is the falloff");
        System.out.println("term saturating - each attack opens a share of what is still closed - and");
        System.out.println("the simulator has that term, so it reproduces the flatness on its own.");
        System.out.println();
        System.out.println("What the model still gets wrong is the endgame: a real crowd gets quieter");
        System.out.println("as it dies and this one does not.");
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
        System.out.printf("%d DECKS, CHOSEN FOR WHAT THEY COVER BETWEEN THEM%n",
                          SAVED_DECKS);
        System.out.println("  the game saves five, so the question is not one deck per creature");
        System.out.println("  but a handful that are good enough everywhere. Greedy: the best");
        System.out.println("  total first, then whatever most improves what it was worst at.");
        System.out.println();
        List<String> chosen = new ArrayList<String>();
        Map<String, Double> bestSoFar = new LinkedHashMap<String, Double>();
        for(String a : owners)
            bestSoFar.put(a, Double.POSITIVE_INFINITY);
        for(int pick = 0; pick < SAVED_DECKS; pick++) {
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
        System.out.printf("  %d of %d opponents are killed by one of those.%n",
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
