import haven.combat.Combatant;
import haven.combat.FoeModel;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.PartyPlanner;
import haven.combat.data.Pack;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * A plan for SEVERAL of us against one big creature - see haven.combat.PartyPlanner.
 *
 *   javac -d %TEMP%\party -sourcepath src src\haven\combat\data\Pack.java ^
 *         src\haven\combat\PartyPlanner.java tools\CombatDeckSearch.java tools\CombatPartySearch.java
 *   java -cp %TEMP%\party CombatPartySearch mammoth -hp 4000
 *
 * NOT PART OF ANY DEFAULT RUN. A party multiplies the search by its size, and a 4000-hitpoint
 * creature is a long fight; it answers a question somebody asks on purpose.
 *
 * Options:
 *   -party "Santa Samus,ZzxcuV3,BonkiDonki,Shade"   who fights, as characters.json names them
 *   -hp N         the creature's hitpoints, where the pack cannot pin them (James: ~4000 is a
 *                 safe start for a mammoth)
 *   -area "Crush Underfoot,Stampede"   cards that land on everyone standing. Nothing in the
 *                 corpus has seen a card hit two people, so the run is made with and without
 *   -beam N       default 20, CombatDeckSearch's
 *   -front NAME   only this one in front; by default each of the party takes a turn
 *   -horizon SEC  how long a plan may run, default 360. A "no kill" at the default is not
 *                 a fact about the fight until this has been raised and the answer held
 *   -pack DIR     read the pack from DIR instead of data/combat, which is how a creature the
 *                 corpus has never met gets planned against: tools/combat/synth_opponent.py
 *                 writes a derived pack whose new entry is an existing creature's whole entry
 *                 with its hitpoints and armour replaced. Borrowing one is stated in the
 *                 header the generator writes, because every card, period, pressure and
 *                 damage figure in that run belongs to the creature it was copied from.
 *
 * WHICH DECK EACH PERSON BRINGS: one they have actually carried, not one the search invented -
 * from their most recent fight against THIS creature, else against any big creature, else their
 * most recent fight of all. "Most recent of all" alone handed Shade a Punch/Jump bar from some
 * other fight. A party deck search is a larger question.
 */
public class CombatPartySearch {
    /**
     * How long a plan may run, in ticks - 6000 is six minutes, and -horizon changes it.
     *
     * It was a constant because every creature in the corpus dies well inside six minutes.
     * A creature with tens of thousands of hitpoints does not, and the failure is quiet in
     * the worst way: the run reports "no kill" and a plan that simply ran out of clock is
     * indistinguishable from a fight the party cannot win. Anything planned past the pack's
     * own creatures should set this and check that the answer stops moving when it rises.
     */
    static long HORIZON = 6000;
    /** Which creature each person's deck was carried against, for the header. */
    static final Map<String, String> FROM = new HashMap<String, String>();

    public static void main(String[] argv) throws Exception {
        String foeName = "mammoth";
        List<String> party = new ArrayList<String>(
            Arrays.asList("Santa Samus", "ZzxcuV3", "BonkiDonki", "Shade"));
        double hp = Double.NaN;
        Set<String> area = new LinkedHashSet<String>();
        int beam = 20;
        String onlyFront = null;
        String packDir = null;
        for(int i = 0; i < argv.length; i++) {
            if("-party".equals(argv[i]))
                party = new ArrayList<String>(Arrays.asList(argv[++i].split("\\s*,\\s*")));
            else if("-hp".equals(argv[i]))
                hp = Double.parseDouble(argv[++i]);
            else if("-area".equals(argv[i]))
                area.addAll(Arrays.asList(argv[++i].split("\\s*,\\s*")));
            else if("-beam".equals(argv[i]))
                beam = Integer.parseInt(argv[++i]);
            else if("-front".equals(argv[i]))
                onlyFront = argv[++i];
            else if("-pack".equals(argv[i]))
                packDir = argv[++i];
            else if("-horizon".equals(argv[i]))
                HORIZON = Math.round(Formulas.secondsToTicks(Double.parseDouble(argv[++i])));
            else
                foeName = argv[i];
        }

        Path root = (packDir == null) ? Paths.get("data", "combat") : Paths.get(packDir);
        Map<String, Move> sheet = CombatDeckSearch.byRes(Pack.moves(root.resolve("moves_sheet.json")));
        Map<String, Pack.Opponent> foes = Pack.opponents(root.resolve("opponents.json"));
        Map<String, Pack.Fighter> chars = Pack.characters(root.resolve("characters.json"));
        Pack.Opponent o = foes.get(foeName);
        if((o == null) || (o.threat == null)) {
            System.out.printf("no creature %s with a behaviour model in the pack%n", foeName);
            return;
        }

        Map<String, Map<String, Integer>> decks = latestDecks(root.resolve("pool"), party, foeName);
        PartyPlanner.Member[] members = new PartyPlanner.Member[party.size()];
        System.out.printf("party against %s%n", foeName);
        for(int i = 0; i < party.size(); i++) {
            String n = party.get(i);
            Pack.Fighter who = chars.get(n);
            Map<String, Integer> lv = decks.get(n);
            if(who == null) {
                System.out.printf("no character named %s. known: %s%n", n, chars.keySet());
                return;
            }
            if((lv == null) || lv.isEmpty()) {
                System.out.printf("no logged deck for %s%n", n);
                return;
            }
            CombatDeckSearch.Deck d = new CombatDeckSearch.Deck();
            d.levels.putAll(lv);
            Combatant c = CombatDeckSearch.withStance(who.combatant(), d, sheet, who.shield);
            List<Move> cards = new ArrayList<Move>();
            for(Move m : d.moves(sheet)) {
                if(!m.stance)
                    cards.add(m);
            }
            members[i] = new PartyPlanner.Member(n, c, cards);
            Move st = CombatDeckSearch.stanceOf(d, sheet);
            System.out.printf("  %-12s hp %4.0f  agi %3.0f  melee %3.0f  armour %3.0f/%-3.0f  %s  stance %s%n",
                              n, c.maxHp, c.agi, c.melee, c.armHard, c.armSoft,
                              (who.weapon == null) ? "bare-handed" : who.weapon.replaceAll(".*/", ""),
                              (st == null) ? "none" : st.name);
            System.out.printf("  %-12s deck (carried against %s): %s%n", "", FROM.get(n), describe(d, sheet));
        }

        System.out.printf("%ncreature: skill %s, measured hp %.0f-%.0f%s; %d engagements%n",
                          o.simulable() ? String.format("%.0f", o.skill)
                              : String.format("only bounded to %.0f-%.0f", o.skillLo, o.skillHi),
                          o.hpLo, o.hpHi,
                          Double.isNaN(hp) ? "" : String.format(", planned at %.0f", hp),
                          o.engagements);
        cardsNote(o.threat);

        List<Set<String>> areas = new ArrayList<Set<String>>();
        areas.add(Collections.<String>emptySet());
        if(!area.isEmpty())
            areas.add(area);
        String[] ends = {"hardest", "easiest"};
        for(String end : ends) {
            Combatant foe = "hardest".equals(end) ? o.hardestReal() : o.weakestReal();
            if(!Double.isNaN(hp))
                foe.hp = foe.maxHp = hp;
            System.out.printf("%n== %s end: skill %.0f, agility %.0f, hp %.0f, armour %.0f/%.0f%n",
                              end, foe.blockSkill, foe.agi, foe.hp, foe.armHard, foe.armSoft);
            for(Set<String> a : areas) {
                System.out.printf("   %s%n", a.isEmpty() ? "every card lands on the one in front"
                                  : ("area cards land on everyone: " + String.join(", ", a)));
                System.out.printf("   %-12s %-7s %-8s %-9s %s%n", "in front", "kill", "seconds",
                                  "hp lost", "per person (down = X)");
                for(int f = 0; f < members.length; f++) {
                    if((onlyFront != null) && !onlyFront.equals(members[f].name))
                        continue;
                    long t0 = System.currentTimeMillis();
                    List<PartyPlanner.Plan> front = PartyPlanner.search(members, foe, o.threat, f, a,
                                                                        beam, HORIZON);
                    long ms = System.currentTimeMillis() - t0;
                    if(front.isEmpty()) {
                        System.out.printf("   %-12s no plan (%d ms)%n", members[f].name, ms);
                        continue;
                    }
                    PartyPlanner.Plan fast = front.get(0);
                    PartyPlanner.Plan safe = front.get(0);
                    for(PartyPlanner.Plan p : front) {
                        if(p.totalLost < safe.totalLost)
                            safe = p;
                    }
                    row(members[f].name, "fastest", fast, members);
                    if(safe != fast)
                        row("", "safest", safe, members);
                    System.out.printf("   %-12s (%d plans on the frontier, %d ms)%n", "", front.size(), ms);
                    if((f == 0) || (onlyFront != null)) {
                        usage("fastest", fast, members);
                        if(safe != fast)
                            usage("safest", safe, members);
                    }
                }
            }
        }
    }

    static void row(String front, String label, PartyPlanner.Plan p, PartyPlanner.Member[] m) {
        StringBuilder per = new StringBuilder();
        for(int i = 0; i < m.length; i++) {
            if(per.length() > 0)
                per.append("  ");
            per.append(String.format("%s %.0f%s", shortName(m[i].name), p.hpLost[i], p.down[i] ? " X" : ""));
        }
        System.out.printf("   %-12s %-7s %-8s %-9.0f %s   [%s]%n", front,
                          p.killed ? "yes" : "no",
                          String.format("%.0f", Formulas.ticksToSeconds(p.ticks)),
                          p.totalLost, per,
                          p.killed ? label : String.format("%s; %.0f hp left on it", label, p.foeHp));
    }

    static void usage(String label, PartyPlanner.Plan p, PartyPlanner.Member[] m) {
        System.out.printf("   %-12s cards thrown in the %s line:%n", "", label);
        for(int i = 0; i < m.length; i++) {
            Map<String, Integer> n = new LinkedHashMap<String, Integer>();
            for(Move mv : p.movesOf(i))
                n.merge(mv.name, 1, Integer::sum);
            StringBuilder b = new StringBuilder();
            for(Map.Entry<String, Integer> e : n.entrySet()) {
                if(b.length() > 0)
                    b.append(", ");
                b.append(e.getKey()).append(" x").append(e.getValue());
            }
            System.out.printf("   %-12s   %-12s %s%n", "", shortName(m[i].name), b);
        }
    }

    static String shortName(String n) {
        return(n.length() <= 8 ? n : n.substring(0, 8));
    }

    static String describe(CombatDeckSearch.Deck d, Map<String, Move> sheet) {
        StringBuilder b = new StringBuilder();
        for(Map.Entry<String, Integer> e : d.levels.entrySet()) {
            Move m = sheet.get(e.getKey());
            if(b.length() > 0)
                b.append(", ");
            b.append((m == null) ? e.getKey().replaceAll(".*/", "") : m.name).append(' ').append(e.getValue());
        }
        return(b.toString());
    }

    /** Which of the creature's cards the model can actually throw, and which it only has a name for. */
    static void cardsNote(FoeModel model) {
        if((model.cards == null) || !model.cards.usable()) {
            System.out.println("  it throws the averaged action - no per-card repertoire");
            return;
        }
        StringBuilder b = new StringBuilder();
        for(int i = 0; i < model.cards.cards.length; i++) {
            if(b.length() > 0)
                b.append(", ");
            b.append(String.format("%s %.0f%%", model.cards.cards[i].name, 100 * model.cards.mix[i]));
        }
        System.out.printf("  the cards it throws in the model: %s%n", b);
    }

    /**
     * Each person's bar in their most recent logged fight, from the begin row's deck.
     *
     * The begin row is the first line of a log and carries the deck as card resource to level,
     * so only first lines are read - the pool holds thousands of files.
     */
    /** Big creatures, whose decks are the ones worth bringing to another big creature. */
    static final Set<String> BIG = new HashSet<String>(Arrays.asList(
        "mammoth", "bear", "moose", "narwhal", "orca", "caveangler", "walrus", "troll",
        "wolf", "boar", "wildgoat", "lynx"));

    static Map<String, Map<String, Integer>> latestDecks(Path pool, List<String> who, String species)
        throws IOException {
        Map<String, long[]> when = new HashMap<String, long[]>();
        Map<String, Map<String, Integer>> out = new HashMap<String, Map<String, Integer>>();
        Set<String> want = new HashSet<String>(who);
        try(DirectoryStream<Path> ds = Files.newDirectoryStream(pool, "*.jsonl")) {
            for(Path p : ds) {
                String line;
                try(BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    line = r.readLine();
                } catch(IOException e) {
                    continue;
                }
                if((line == null) || !line.startsWith("{\"ev\":\"begin\""))
                    continue;
                JSONObject b;
                try {
                    b = new JSONObject(line);
                } catch(Exception e) {
                    continue;
                }
                String c = b.optString("char", null);
                if((c == null) || !want.contains(c))
                    continue;
                JSONObject deck = b.optJSONObject("deck");
                if((deck == null) || (deck.length() == 0))
                    continue;
                long w = b.optLong("wall");
                String res = b.optString("foeres", "");
                String sp = res.substring(res.lastIndexOf('/') + 1);
                long rank = species.equals(sp) ? 2 : (BIG.contains(sp) ? 1 : 0);
                long[] had = when.get(c);
                if((had != null) && ((had[0] > rank) || ((had[0] == rank) && (had[1] >= w))))
                    continue;
                Map<String, Integer> lv = new LinkedHashMap<String, Integer>();
                for(String k : deck.keySet())
                    lv.put(k, deck.getInt(k));
                when.put(c, new long[] {rank, w});
                FROM.put(c, sp);
                out.put(c, lv);
            }
        }
        return(out);
    }
}
