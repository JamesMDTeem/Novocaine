import haven.automated.combat.Prediction;
import haven.combat.Combatant;
import haven.combat.Duel;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Sim;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Our live advice against scripted and searching PEOPLE, both sides staged from real logs.
 *
 *   javac -nowarn -d OUT -sourcepath "src;tools" tools\PvpArena.java
 *   (copy data\combat\*.json into OUT\haven\combat\data - the pack loads off the classpath)
 *   java -cp OUT PvpArena OUR_LOG.jsonl THEIR_LOG.jsonl
 *
 * NOT PART OF ANY DEFAULT RUN.
 *
 * WHY (James, 2026-09-27): "PVP is almost completely unoptimized, as we didn't drop at all, and
 * that resulted in us getting cleaved for our whole HP." The three spars with Dunki on 2026-09-26
 * are the only fights in the pool where BOTH sides logged - attributes, gear, deck, stance - so a
 * person can be staged as they really were, not as a copy of us. This plays our side exactly as
 * the client does (Prediction.adviseLive, seeing only what the client sees: their openings, their
 * initiative, the weapon in their hands, the stance and Bloodlust meter on their buffs, the cards
 * they have thrown so far, the damage we have drawn on them) against:
 *
 *   spam      Quick Barrage until six points, then Cleave - Dunki's line in the Bloodlust spar
 *   aim       Take Aim to six, Flex and Knock Its Teeth Out to open blue and red, then Cleave -
 *             his line in the To Arms spar, and the guide's Red/Blue UA deck
 *   search    Duel's minimax over his whole deck and ours - a person who reads the fight
 *
 * each with a restoration when one of their own colours passes 50. Every fight is played twice,
 * with each side acting first, because who acts first on tick zero is worth a lot (Duel.payoff).
 *
 * THE CONTROL is -Dpvpprior=false: the advice as it was before 2026-09-27, pricing a person from
 * the cards seen alone (Prediction.PVP_PRIOR).
 *
 * WHAT IT DOES NOT MODEL: movement (both stand in reach, so Take Aim is thrown in reach), Bloodlust
 * bleeding away between blows, and our own knowledge of their hitpoints (the advice stages them at
 * ours, as live).
 */
public class PvpArena {
    static final long HORIZON = 6000;

    static final class Side {
        final String name;
        final Prediction.Me me;
        final Combatant c;
        final Map<String, Move> deck = new LinkedHashMap<>();
        final String weapon, stance;
        final List<Move> line = new ArrayList<>();

        Side(String name, Prediction.Me me, Combatant c, List<Move> deck, String weapon, String stance) {
            this.name = name;
            this.me = me;
            this.c = c;
            for(Move m : deck)
                this.deck.put(m.res, m);
            this.weapon = weapon;
            this.stance = stance;
        }
    }

    interface Chooser {
        Move choose(Side self, Combatant us, Combatant them, Map<String, Integer> seen, long tick);
    }

    public static void main(String[] args) throws Exception {
        Side ours = side(args[0]), theirs = side(args[1]);
        System.out.printf("us:   %s hp %.0f arm %.0f/%.0f stance %s weapon %s deck %s%n", ours.name, ours.c.maxHp,
                          ours.c.armHard, ours.c.armSoft, ours.stance, ours.weapon, ours.deck.keySet());
        System.out.printf("them: %s hp %.0f arm %.0f/%.0f stance %s weapon %s deck %s%n", theirs.name, theirs.c.maxHp,
                          theirs.c.armHard, theirs.c.armSoft, theirs.stance, theirs.weapon, theirs.deck.keySet());
        Map<String, Chooser> opp = new LinkedHashMap<>();
        opp.put("spam", PvpArena::spam);
        opp.put("aim", PvpArena::aim);
        opp.put("search", (self, us, them, seen, tick) -> search(self, ours, us, them));
        for(Map.Entry<String, Chooser> e : opp.entrySet()) {
            for(boolean weFirst : new boolean[] {true, false}) {
                long t0 = System.currentTimeMillis();
                String r = fight(ours, theirs, e.getValue(), weFirst);
                System.out.printf("%-7s %-10s %s  [%d s]%n", e.getKey(), weFirst ? "we open" : "they open", r,
                                  (System.currentTimeMillis() - t0) / 1000);
            }
        }
    }

    static String fight(Side ours, Side theirs, Chooser opp, boolean weFirst) {
        Combatant a = ours.c.copy(), b = theirs.c.copy();
        a.readyAt = weFirst ? 0 : 1;
        b.readyAt = weFirst ? 1 : 0;
        Sim s = new Sim(a, b);
        Map<String, Integer> seen = new LinkedHashMap<>();
        Map<String, Integer> ourCards = new TreeMap<>(), theirCards = new TreeMap<>();
        double worstOnUs = 0, restores = 0, cards = 0;
        int cardsThrown = 0;
        long last = 0;
        while(a.alive() && b.alive() && (s.tick <= HORIZON)) {
            Combatant actor = (a.readyAt <= b.readyAt) ? a : b;
            long at = Math.max(s.tick, actor.readyAt);
            decay(a, at - last);
            decay(b, at - last);
            last = at;
            s.advanceTo(at);
            Move m = (actor == a) ? (THROWN ? thrown(ours, cardsThrown) : advise(ours, theirs, a, b, seen))
                : opp.choose(theirs, b, a, seen, at);
            if((actor == a) && THROWN && (m != null) && (s.refuse(actor, m) == null))
                cardsThrown++;
            if((m == null) || (s.refuse(actor, m) != null)) {
                actor.readyAt = at + 1;
                continue;
            }
            double hp0 = a.hp;
            s.use(actor, m);
            if(actor == a) {
                ourCards.merge(m.name, 1, Integer::sum);
                cards++;
                for(double x : m.reduces)
                    if(x > 0) {
                        restores++;
                        break;
                    }
            } else {
                theirCards.merge(m.name, 1, Integer::sum);
                seen.put(m.res, 1);
                worstOnUs = Math.max(worstOnUs, hp0 - a.hp);
            }
        }
        String who = !b.alive() ? "WE WIN " : (!a.alive() ? "we lose" : "no kill");
        return(String.format("%s at %5.1fs  our hp %3.0f%% theirs %3.0f%%  biggest blow on us %4.0f  restorations %2.0f%%  ours %s  theirs %s",
                             who, s.tick * Formulas.TICK_SECONDS, 100 * Math.max(0, a.hp) / a.maxHp,
                             100 * Math.max(0, b.hp) / b.maxHp, worstOnUs, 100 * restores / Math.max(1, cards),
                             ourCards, theirCards));
    }

    /**
     * THE SANITY CONTROL (James, 2026-09-27: "none of our runs should come up with a route through
     * cards that ends up WORSE than what was actually thrown"): -Dours=thrown plays our side with the
     * cards the log says we threw, in order and then round again, against the same opponent - so the
     * advice's result can be set beside what we did, in the same model.
     */
    static final boolean THROWN = "thrown".equals(System.getProperty("ours"));

    static Move thrown(Side ours, int k) {
        if(ours.line.isEmpty())
            return(null);
        return(ours.line.get(k % ours.line.size()));
    }

    /** Our side, as the client plays it: the live advice from what the client can see. */
    static Move advise(Side ours, Side theirs, Combatant a, Combatant b, Map<String, Integer> seen) {
        int[] mine = new int[4], open = new int[4];
        for(int c = 0; c < 4; c++) {
            mine[c] = (int)Math.floor(a.opening(c) * 100);
            open[c] = (int)Math.floor(b.opening(c) * 100);
        }
        Prediction.Seen s = new Prediction.Seen(1, "gfx/borka/body", open, a.ip, b.ip, 5,
                                                b.maxHp - b.hp, new LinkedHashMap<>(seen), true)
            .wielding(theirs.weapon, (theirs.stance == null) ? null : new String[] {theirs.stance})
            .charged(b.charges ? b.charge : Double.NaN);
        s.acted(0);
        Prediction.Live l = Prediction.adviseLive(ours.me, null, mine, a.hp, a.maxHp,
                                                  Collections.singletonList(s), 60, 2500);
        Move m = (l.moveRes == null) ? null : ours.deck.get(l.moveRes);
        return((m != null) ? m : ours.deck.get("paginae/atk/barrage"));
    }

    /** A restoration for our own worst colour past 50, else null. */
    static Move restore(Side self, Combatant us) {
        int worst = 0;
        for(int c = 1; c < 4; c++)
            if(us.opening(c) > us.opening(worst))
                worst = c;
        if(us.opening(worst) < 0.5)
            return(null);
        Move best = null;
        for(Move m : self.deck.values()) {
            if((m.reduces[worst] > 0) && ((best == null) || (m.reduces[worst] > best.reduces[worst])))
                best = m;
        }
        return(best);
    }

    static Move spam(Side self, Combatant us, Combatant them, Map<String, Integer> seen, long tick) {
        Move cleave = self.deck.get("paginae/atk/cleave");
        if((cleave != null) && (us.ip >= cleave.ipRequirement()))
            return(cleave);
        Move r = restore(self, us);
        return((r != null) ? r : self.deck.get("paginae/atk/barrage"));
    }

    static Move aim(Side self, Combatant us, Combatant them, Map<String, Integer> seen, long tick) {
        Move cleave = self.deck.get("paginae/atk/cleave");
        Move r = restore(self, us);
        if(r != null)
            return(r);
        if(us.ip < 6)
            return(self.deck.get("paginae/atk/takeaim"));
        double both = Formulas.combined(new double[] {0, them.opening(Formulas.BLUE), 0, them.opening(Formulas.RED)});
        if((cleave != null) && (both >= 0.45))
            return(cleave);
        if(them.opening(Formulas.BLUE) < 0.4)
            return(self.deck.get("paginae/atk/flex"));
        return(self.deck.get("paginae/atk/knockteeth"));
    }

    /** Duel's minimax over both whole decks: a person who reads the fight as the model does. */
    static Move search(Side self, Side other, Combatant us, Combatant them) {
        Sim s = new Sim(us.copy(), them.copy());
        s.tick = Math.max(us.readyAt, 0);
        return(Duel.choose(s, s.a, new ArrayList<>(self.deck.values()), new ArrayList<>(other.deck.values()), 3, HORIZON));
    }

    static void decay(Combatant c, long ticks) {
        if(ticks <= 0)
            return;
        for(int k = 0; k < 4; k++) {
            double o = c.opening(k) * 100;
            if(o <= 0)
                continue;
            double left = Math.max(0, o - (ticks * Formulas.OPENING_DECAY_PER_TICK));
            c.close(k, 1.0 - (left / o));
        }
    }

    static Side side(String path) throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        for(String s : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8))
            rows.add(new JSONObject(s));
        JSONObject begin = rows.get(0);
        JSONObject job = new JSONObject();
        job.put("attr", begin.getJSONObject("attr")).put("deck", begin.getJSONObject("deck"))
            .put("hard", begin.optInt("hard")).put("soft", begin.optInt("soft"));
        String[] hand = {null, null};
        double[] ql = {0, 0};
        JSONArray buffs = new JSONArray();
        String stance = null;
        for(JSONObject r : rows) {
            String ev = r.getString("ev");
            if(ev.equals("gear") && (r.getLong("t") == 0) && ((r.getInt("slot") == 6) || (r.getInt("slot") == 7))) {
                hand[r.getInt("slot") - 6] = r.getString("res");
                ql[r.getInt("slot") - 6] = r.getDouble("ql");
            }
            if(ev.equals("buffs") && "me".equals(r.optString("who")) && (buffs.length() == 0)) {
                JSONArray b = r.getJSONArray("res");
                for(int i = 0; i < b.length(); i++) {
                    buffs.put(b.getString(i));
                    if(STANCES.contains(b.getString(i)))
                        stance = b.getString(i);
                }
            }
        }
        /* A two-hander fills both hand slots; one weapon, not two. */
        if((hand[0] != null) && hand[0].equals(hand[1])) {
            hand[1] = null;
            ql[1] = 0;
        }
        job.put("hand", new JSONArray(Arrays.asList(hand[0], hand[1])))
            .put("ql", new JSONArray(new double[] {ql[0], ql[1]})).put("buffs", buffs);
        Prediction.Me me = StrategyVsPlayer.meOf(job);
        double mhp = begin.getJSONObject("attr").optDouble("hp", 300);
        Prediction.Staged st = Prediction.stage(me, null, new int[4], mhp, mhp,
            Collections.singletonList(new Prediction.Seen(9, "gfx/kritter/fox/fox", new int[4], 0, 0, 5, 0, null, true)));
        Combatant c = st.a.copy();
        c.hp = c.maxHp = mhp;
        c.penetrable = true;
        String weapon = null;
        for(String h : hand)
            if(h != null)
                weapon = h.substring(h.lastIndexOf('/') + 1);
        Side side = new Side(begin.optString("char"), me, c, st.deck, weapon, stance);
        for(JSONObject r : rows)
            if("move".equals(r.getString("ev")) && "me".equals(r.optString("actor")) && side.deck.containsKey(r.getString("move")))
                side.line.add(side.deck.get(r.getString("move")));
        return(side);
    }

    static final Set<String> STANCES = new HashSet<>(Arrays.asList(
        "paginae/atk/bloodlust", "paginae/atk/chinup", "paginae/atk/combmed", "paginae/atk/oakstance",
        "paginae/atk/parry", "paginae/atk/shield", "paginae/atk/toarms"));
}
