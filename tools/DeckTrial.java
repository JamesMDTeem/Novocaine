import haven.automated.combat.Prediction;
import haven.combat.Advisor;
import haven.combat.Combatant;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Optimizer;
import haven.combat.WearGuard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Decks tried the way the client would fight them: the live advice, card by card, in the model.
 *
 *   javac -nowarn -d OUT -sourcepath "src;tools" tools\DeckTrial.java
 *   (copy data\combat\*.json into OUT\haven\combat\data)
 *   java -cp OUT DeckTrial CHAR SPECIES "Name=res:lvl,res:lvl;Name2=..." [hard]
 *
 * NOT PART OF ANY DEFAULT RUN.
 *
 * WHY (James, 2026-09-27): "generate some PVE combat decks making sure it takes into account all of
 * the new changes ... 1v1 Polar Bears, Bears, Moose ... and how much damage we'd end up taking."
 * CombatDeckSearch ranks a deck by the plan it makes against a creature - open loop, and hitpoints
 * only. This plays each candidate CLOSED LOOP (Optimizer.play) with the policy the client runs -
 * SURVIVE keeping 75% of the bar, then the wear guard - and reports what the fight cost: time,
 * hitpoints, armour soaked (the wear), and the biggest single blow.
 *
 * The character is read from characters.json, which carries each character's COMBAT SET (its most
 * armoured log of the last 14 days, attributes with that set's gildings - estimate.write_characters);
 * the stance is the deck's own (Shield Up with the shield in hand, Parry with the sword). The creature
 * is staged as the live advice plans it (Prediction.creature: the median real individual), or with
 * "hard" the largest individual the pack holds.
 */
public class DeckTrial {
    public static void main(String[] args) throws Exception {
        String who = args[0], species = args[1];
        boolean hard = (args.length > 3) && "hard".equals(args[3]);
        JSONObject ch = character(who);
        System.out.println(describe(ch) + "; vs " + species + (hard ? " (hardest)" : ""));
        System.out.printf("  %-26s %-58s %6s %6s %7s %7s %6s %6s%n", "deck", "cards", "secs", "hp", "armour", "wear", "worst",
                          "wounds");
        for(String spec : args[2].split(";")) {
            String name = spec.substring(0, spec.indexOf('='));
            Map<String, Integer> deck = new LinkedHashMap<>();
            for(String c : spec.substring(spec.indexOf('=') + 1).split(","))
                deck.put(c.split(":")[0], Integer.parseInt(c.split(":")[1]));
            Result r = trial(ch, species, deck, hard, 60);
            if(r.refused != null) {
                System.out.println("  " + name + ": " + r.refused);
                continue;
            }
            System.out.printf("  %-26s %-58s %6.1f %6.1f %7.1f %7.1f %6.1f %6.1f%s%n", name, cards(deck),
                              r.plan.ticks * Formulas.TICK_SECONDS, r.plan.hpLost, r.plan.soaked,
                              r.wear(), r.worst, r.plan.wounded, r.plan.killed ? "" : "  NO KILL");
        }
    }

    /** One closed-loop fight: the plan it came to, the biggest single blow, or why it could not run. */
    static final class Result {
        Optimizer.Plan plan;
        double worst;
        String refused;

        double wear() {
            return(plan.hpLost + plan.soaked);
        }
    }

    /** The character's row of characters.json - its combat set. */
    static JSONObject character(String who) throws Exception {
        JSONArray all = new JSONObject(new String(Files.readAllBytes(Paths.get("data", "combat", "characters.json")),
                                                  StandardCharsets.UTF_8)).getJSONArray("characters");
        for(int i = 0; i < all.length(); i++)
            if(who.equals(all.getJSONObject(i).optString("name")))
                return(all.getJSONObject(i));
        throw(new IllegalArgumentException("no character " + who));
    }

    static SortedMap<String, Integer> attrs(JSONObject ch) {
        SortedMap<String, Integer> attrs = new TreeMap<>();
        for(String k : new String[] {"str", "agi", "unarmed", "melee", "hp", "hhp", "con"})
            if(!ch.isNull(k))
                attrs.put(k, ch.getInt(k));
        return(attrs);
    }

    static String describe(JSONObject ch) {
        SortedMap<String, Integer> a = attrs(ch);
        JSONObject arm = ch.getJSONObject("armour");
        JSONObject wp = ch.optJSONObject("weapon");
        return(String.format("%s in the combat set: armour %d/%d, str %s agi %s melee %s unarmed %s hp %s, %s q%.0f%s",
                             ch.getString("name"), arm.getInt("hard"), arm.getInt("soft"), a.get("str"), a.get("agi"),
                             a.get("melee"), a.get("unarmed"), a.get("hp"), (wp == null) ? "no weapon" : wp.getString("name"),
                             (wp == null) ? 0 : wp.optDouble("ql", 0), ch.optBoolean("shield") ? " + shield" : ""));
    }

    /** A deck fought closed loop by the live advice (SURVIVE, then the wear guard) at this beam. */
    static Result trial(JSONObject ch, String species, Map<String, Integer> deck, boolean hard, int beam) {
        Result out = new Result();
        SortedMap<String, Integer> attrs = attrs(ch);
        JSONObject arm = ch.getJSONObject("armour");
        JSONObject wp = ch.optJSONObject("weapon");
        String weapon = (wp == null) ? null : res(wp.getString("name"));
        double wql = (wp == null) ? 0 : wp.optDouble("ql", 0);
        boolean shield = ch.optBoolean("shield");
        double mhp = attrs.getOrDefault("hp", 300);
        String stance = null;
        for(String r : deck.keySet())
            if(STANCES.contains(r))
                stance = r;
        String[] hand = {weapon, shield ? "gfx/invobjs/small/roundshield" : null};
        Prediction.Me me = Prediction.me(attrs, arm.getInt("hard"), arm.getInt("soft"), hand, new double[] {wql, 30}, deck);
        if(me == null) {
            out.refused = "the character cannot be staged";
            return(out);
        }
        if(stance != null)
            me = me.holding(new String[] {stance}, shield);
        Prediction.Staged st = Prediction.stage(me, null, new int[4], mhp, mhp, Collections.singletonList(
            new Prediction.Seen(1, "gfx/kritter/" + dir(species) + "/" + species, new int[4], 0, 0, 8, 0, null, true)));
        if(st.refused != null) {
            out.refused = st.refused;
            return(out);
        }
        if(hard) {
            double top = 0;
            haven.combat.data.Pack.Opponent o = haven.combat.data.Pack.opponentsFromJar().get(species);
            if(o != null)
                for(haven.combat.data.Pack.Individual ind : o.individuals())
                    top = Math.max(top, ind.hp);
            if(top > 0)
                st.foes[0].hp = st.foes[0].maxHp = top;
        }
        if(Boolean.getBoolean("showdeck")) {
            List<String> nm = new ArrayList<>();
            for(Move m : st.deck)
                nm.add(m.name + "@" + m.mu);
            System.out.println("    staged: " + nm);
        }
        final double[] worst = {0};
        final double[] lastHp = {mhp};
        Optimizer.Policy live = (a0, foes, ip, tick) -> {
            worst[0] = Math.max(worst[0], lastHp[0] - a0.hp);
            lastHp[0] = a0.hp;
            Combatant a = a0.copy();
            a.readyAt = Math.max(0, a0.readyAt - tick);
            a.soaked = 0;
            Combatant[] f = new Combatant[foes.length];
            for(int i = 0; i < f.length; i++)
                f[i] = foes[i].copy();
            List<Optimizer.Plan> front = Optimizer.search(a, f, st.deck, st.models, beam, 2500, ip.clone());
            Optimizer.Plan p = Advisor.choose(front, Advisor.Aim.SURVIVE, a0.hp - (Prediction.RESERVE_PVE * mhp));
            if((p == null) || p.moves.isEmpty())
                return(null);
            Combatant us = a.copy();
            us.ip = ip[0];
            WearGuard.Call c = WearGuard.walk(us, f, st.models, st.deck, ip.clone(), p.moves, WearGuard.OPEN,
                                              WearGuard.RATIO, 2500);
            return((c.move != null) ? c.move : p.moves.get(0));
        };
        out.plan = Optimizer.play(st.a, st.foes, st.models, st.deck, live, 6000, null, null);
        out.worst = worst[0];
        return(out);
    }

    static String cards(Map<String, Integer> d) {
        StringBuilder b = new StringBuilder();
        for(Map.Entry<String, Integer> e : d.entrySet())
            b.append(e.getKey().substring(e.getKey().lastIndexOf('/') + 1)).append(' ').append(e.getValue()).append(", ");
        return(b.length() > 2 ? b.substring(0, b.length() - 2) : "");
    }

    static String dir(String sp) {
        return(sp.equals("polarbear") ? "bear" : sp);
    }

    static String res(String weaponName) {
        switch(weaponName) {
        case "Hirdsman's Sword": return("gfx/invobjs/small/hirdsword");
        case "Bronze Sword": return("gfx/invobjs/small/bronzesword");
        case "Fyrdsman's Sword": return("gfx/invobjs/small/fyrdsword");
        case "Battleaxe of the Twelfth Bay": return("gfx/invobjs/small/b12axe");
        default: return("gfx/invobjs/small/" + weaponName.toLowerCase().replaceAll("[^a-z0-9]", ""));
        }
    }

    static final Set<String> STANCES = new HashSet<>(Arrays.asList(
        "paginae/atk/bloodlust", "paginae/atk/chinup", "paginae/atk/combmed", "paginae/atk/oakstance",
        "paginae/atk/parry", "paginae/atk/shield", "paginae/atk/toarms"));
}
