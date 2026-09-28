import haven.combat.Formulas;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * A deck built from EVERY card the character has learned, scored on what the fight actually costs.
 *
 *   javac -nowarn -d OUT -sourcepath "src;tools" tools\DeckGreedy.java
 *   (copy data\combat\*.json into OUT\haven\combat\data)
 *   java -cp OUT DeckGreedy CHAR SPECIES [beam]
 *
 * NOT PART OF ANY DEFAULT RUN.
 *
 * WHY (James, 2026-09-27): "Is everything considering all cards that character has learned and not
 * just ones currently in decks?" CombatDeckSearch -owned does range over every learned card, but it
 * ranks a deck by the plan it makes - open loop, hitpoints only - and on bear and moose that picked
 * Parry decks that wear twice what a Shield Up deck does once the fight is played (DeckTrial). This
 * builds the deck by the closed-loop result itself: for each stance the character knows, start from the
 * stance alone and add, one at a time, whichever learned card (at its learned level) most lowers the
 * WEAR of the fight played by the live advice (DeckTrial.trial: SURVIVE, then the wear guard, the
 * character in its combat set). A deck that kills beats one that does not; among kills, less wear, then
 * less time. It stops when no card helps by more than half a point, or at the game's limits: ten cards,
 * thirty points, five on one card.
 *
 * Greedy, so it can miss a pair of cards that only pay together; the search is a beam of candidates
 * played in parallel, and the final deck is replayed at the live beam against the median and the
 * largest individual.
 */
public class DeckGreedy {
    static final int CARDS = 10, POINTS = 30;

    public static void main(String[] args) throws Exception {
        String who = args[0], species = args[1];
        int beam = (args.length > 2) ? Integer.parseInt(args[2]) : 20;
        JSONObject ch = DeckTrial.character(who);
        Map<String, String> resByName = new HashMap<>();
        org.json.JSONArray mv = new JSONObject(new String(Files.readAllBytes(Paths.get("data", "combat", "moves_sheet.json")),
                                                           StandardCharsets.UTF_8)).getJSONArray("moves");
        for(int i = 0; i < mv.length(); i++)
            resByName.put(mv.getJSONObject(i).getString("name"), mv.getJSONObject(i).getString("res"));
        Map<String, Integer> learned = new LinkedHashMap<>();
        JSONObject known = ch.getJSONObject("known");
        for(String name : known.keySet()) {
            String r = resByName.get(name);
            if(r != null)
                learned.put(r, Math.min(5, known.getInt(name)));
        }
        List<String> stances = new ArrayList<>(), cards = new ArrayList<>();
        for(String r : learned.keySet())
            (DeckTrial.STANCES.contains(r) ? stances : cards).add(r);
        System.out.println(DeckTrial.describe(ch) + "; vs " + species + "; " + cards.size() + " learned cards, stances "
                           + short_(stances) + "; search beam " + beam);
        /* The pack loads on first use; load it here, once, before any worker can race it. */
        if(!stances.isEmpty())
            DeckTrial.trial(ch, species, Collections.singletonMap(stances.get(0), 1), false, beam);
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        Map<String, Integer> best = null;
        DeckTrial.Result bestR = null;
        for(String st : stances) {
            Map<String, Integer> deck = new LinkedHashMap<>();
            deck.put(st, 1);
            DeckTrial.Result cur = null;
            while(deck.size() < CARDS) {
                int used = 0;
                for(int v : deck.values())
                    used += v;
                List<Map<String, Integer>> tries = new ArrayList<>();
                for(String c : cards) {
                    if(deck.containsKey(c))
                        continue;
                    int lv = Math.min(learned.get(c), POINTS - used);
                    if(lv <= 0)
                        continue;
                    Map<String, Integer> t = new LinkedHashMap<>(deck);
                    t.put(c, lv);
                    tries.add(t);
                }
                List<Future<DeckTrial.Result>> fs = new ArrayList<>();
                for(Map<String, Integer> t : tries)
                    fs.add(ex.submit(() -> DeckTrial.trial(ch, species, t, false, beam)));
                Map<String, Integer> pick = null;
                DeckTrial.Result pr = null;
                for(int i = 0; i < tries.size(); i++) {
                    DeckTrial.Result r = fs.get(i).get();
                    if((r.refused != null) || (r.plan == null))
                        continue;
                    if((pr == null) || better(r, pr)) {
                        pr = r;
                        pick = tries.get(i);
                    }
                }
                if((pick == null) || ((cur != null) && !improves(pr, cur)))
                    break;
                deck = pick;
                cur = pr;
            }
            if(cur == null)
                continue;
            System.out.printf("  %-12s %-70s %s%n", short_(Collections.singletonList(st)), short_(deck.keySet()) + " " + deck.values(), fmt(cur));
            if((bestR == null) || better(cur, bestR)) {
                bestR = cur;
                best = deck;
            }
        }
        ex.shutdown();
        if(best == null) {
            System.out.println("  no deck could be staged");
            return;
        }
        System.out.println("BEST: " + best);
        DeckTrial.Result med = DeckTrial.trial(ch, species, best, false, 60);
        DeckTrial.Result top = DeckTrial.trial(ch, species, best, true, 60);
        System.out.println("  at the live beam, median individual:  " + fmt(med));
        System.out.println("  at the live beam, largest individual: " + fmt(top));
    }

    /** A kill beats no kill; then less wear beyond half a point; then fewer ticks. */
    static boolean better(DeckTrial.Result a, DeckTrial.Result b) {
        if(a.plan.killed != b.plan.killed)
            return(a.plan.killed);
        if(!a.plan.killed)
            return(a.plan.foeHp < b.plan.foeHp);
        if(Math.abs(a.wear() - b.wear()) > 0.5)
            return(a.wear() < b.wear());
        return(a.plan.ticks < b.plan.ticks);
    }

    /** Whether adding the card was worth it: a first kill, or clearly less wear, or a faster kill at the same. */
    static boolean improves(DeckTrial.Result a, DeckTrial.Result cur) {
        if(a.plan.killed && !cur.plan.killed)
            return(true);
        if(!a.plan.killed)
            return(a.plan.foeHp < cur.plan.foeHp - 1);
        if(a.wear() < cur.wear() - 0.5)
            return(true);
        return((Math.abs(a.wear() - cur.wear()) <= 0.5) && (a.plan.ticks < cur.plan.ticks - 10));
    }

    static String fmt(DeckTrial.Result r) {
        return(String.format("%5.1f s, %5.1f hp, %6.1f armour, wear %6.1f%s", r.plan.ticks * Formulas.TICK_SECONDS,
                             r.plan.hpLost, r.plan.soaked, r.wear(), r.plan.killed ? "" : " NO KILL"));
    }

    static String short_(Collection<String> rs) {
        List<String> o = new ArrayList<>();
        for(String r : rs)
            o.add(r.substring(r.lastIndexOf('/') + 1));
        return(o.toString());
    }
}
