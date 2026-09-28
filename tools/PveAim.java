import haven.automated.combat.Prediction;
import haven.combat.Advisor;
import haven.combat.Combatant;
import haven.combat.FoeModel;
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
 * Which AIM plays a creature fight best, played one card at a time in the model, beside the line the
 * character actually threw.
 *
 *   python tools\combat\solo_lines.py --out JOBS.jsonl
 *   javac -nowarn -d OUT -sourcepath "src;tools" tools\PveAim.java
 *   (copy data\combat\*.json into OUT\haven\combat\data)
 *   java -cp OUT PveAim JOBS.jsonl [species,species,...]
 *
 * NOT PART OF ANY DEFAULT RUN.
 *
 * WHY (James, 2026-09-27): "optimizing TTK was with the idea that the enemy being killed quicker
 * would lead us to taking less damage; now that we can optimize for that, evaluate optimizing for
 * lowest absolute damage taken to us ... if we just dodge forever we'll never kill it and take
 * infinite damage, over-dodging for small percents of opening will be thrown out because we'll
 * end up being hit for chips." And: "none of our runs should come up with a route through cards
 * that ends up WORSE than what was actually thrown."
 *
 * Each clean solo kill (solo_lines.py) is staged exactly as StrategyVsPlayer stages it - the
 * individual at the damage that killed it, its measured agility - and played by Optimizer.play with
 * each policy re-planning every card from the state it is in:
 *
 *   fastest   the fastest kill (the old reading of "kill it quicker, take less")
 *   live      what the client does now: SURVIVE keeping 75% of the bar, then the wear guard
 *   least     the plan that costs least in hitpoints AND armour (one for one) among those that kill
 *             inside the planning horizon - "lowest absolute damage within a sane horizon"
 *   least+g   least, then the wear guard
 *
 * and beside them the character's own line, stepped through the same model (Optimizer.follow).
 * A policy is WORSE than the thrown line when it wears more AND takes no less time, or fails to
 * kill where the line killed; that count is the sanity check and should be zero.
 */
public class PveAim {
    static final int BEAM = 60;
    static final long PLAN = 2500, FIGHT = 6000;

    public static void main(String[] args) throws Exception {
        Set<String> only = (args.length > 1) ? new HashSet<>(Arrays.asList(args[1].split(","))) : null;
        StrategyVsPlayer.OPP = haven.combat.data.Pack.opponentsFromJar();
        String[] names = {"fastest", "live", "least", "least-commit", "commit+g"};
        double[] wear = new double[names.length], time = new double[names.length];
        int[] worse = new int[names.length], kills = new int[names.length];
        double pw = 0, pt = 0, lw = 0;
        int n = 0;
        System.out.printf("%-12s %-11s | %-22s | %-22s | %s%n", "species", "char", "logged (ticks, wear)",
                          "thrown line in model", "fastest / live / least / least+g   (ticks wear)");
        for(String s : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            if(s.trim().isEmpty())
                continue;
            JSONObject j = new JSONObject(s);
            if(j.has("foes") || j.has("members"))
                continue;
            if((only != null) && !only.contains(j.getString("species")))
                continue;
            Stage st = stage(j);
            if(st == null)
                continue;
            int[] skipped = {0};
            Optimizer.Plan line = Optimizer.follow(st.s.a, st.s.foes, st.s.models, st.s.deck, st.line, FIGHT,
                                                   st.ip0, skipped);
            Optimizer.Plan[] got = new Optimizer.Plan[names.length];
            for(int k = 0; k < names.length; k++)
                got[k] = Optimizer.play(st.s.a, st.s.foes, st.s.models, st.s.deck, policy(names[k], st), FIGHT,
                                        st.ip0, null);
            JSONObject lg = j.getJSONObject("logged");
            double lwear = lg.optDouble("soft", 0) + lg.optDouble("soaked", 0);
            StringBuilder row = new StringBuilder();
            for(int k = 0; k < names.length; k++) {
                Optimizer.Plan p = got[k];
                row.append(String.format("%5d %6.1f%s  ", p.ticks, w(p), p.killed ? "" : "!"));
                boolean bad = (line.killed && !p.killed)
                    || ((w(p) > w(line) + 1.0) && (p.ticks >= line.ticks));
                if(bad)
                    worse[k]++;
                if(p.killed)
                    kills[k]++;
                wear[k] += w(p);
                time[k] += p.ticks;
            }
            System.out.printf("%-12s %-11s | %5d t %7.1f wear    | %5d t %7.1f%s        | %s%n", j.getString("species"),
                              j.getString("char"), lg.optInt("ticks"), lwear, line.ticks, w(line),
                              line.killed ? "" : "!", row);
            pw += w(line);
            pt += line.ticks;
            lw += lwear;
            n++;
        }
        System.out.printf("%n%d fights. Thrown line in the model: %.0f ticks, %.1f wear per fight (logged %.1f).%n",
                          n, pt / Math.max(1, n), pw / Math.max(1, n), lw / Math.max(1, n));
        for(int k = 0; k < names.length; k++)
            System.out.printf("  %-8s %6.0f ticks (%4.1f s) %7.1f wear per fight, %d/%d kills, WORSE than the thrown line in %d%n",
                              names[k], time[k] / Math.max(1, n), time[k] / Math.max(1, n) * Formulas.TICK_SECONDS,
                              wear[k] / Math.max(1, n), kills[k], n, worse[k]);
    }

    static double w(Optimizer.Plan p) {
        return(p.hpLost + p.soaked);
    }

    static final class Stage {
        Prediction.Staged s;
        List<Move> line;
        int[] ip0;
        final Map<String, List<Move>> rest = new HashMap<>();
        double mhp;
    }

    static Stage stage(JSONObject j) {
        SortedMap<String, Integer> attrs = new TreeMap<>();
        JSONObject at = j.getJSONObject("attr");
        for(String k : at.keySet())
            attrs.put(k, at.optInt(k));
        Map<String, Integer> deck = new LinkedHashMap<>();
        JSONObject d = j.getJSONObject("deck");
        for(String k : d.keySet())
            deck.put(k, d.getInt(k));
        JSONArray h = j.getJSONArray("hand"), q = j.getJSONArray("ql");
        String[] hand = {h.isNull(0) ? null : h.getString(0), h.isNull(1) ? null : h.getString(1)};
        double[] ql = {q.optDouble(0, 0), q.optDouble(1, 0)};
        Prediction.Me me = StrategyVsPlayer.held(Prediction.me(attrs, j.optInt("hard"), j.optInt("soft"), hand, ql, deck), j, hand);
        if(me == null)
            return(null);
        double mhp = at.optDouble("hp", 300);
        JSONArray mi = j.getJSONArray("mine");
        int[] mine = {mi.getInt(0), mi.getInt(1), mi.getInt(2), mi.getInt(3)};
        Prediction.Staged st = Prediction.stage(me, null, mine, mhp, mhp, Collections.singletonList(
            new Prediction.Seen(1, j.getString("res"), new int[] {0, 0, 0, 0}, j.optInt("myip"), 0, Double.NaN, 0, null, true)));
        if((st.refused != null) || (st.proxied > 0))
            return(null);
        StrategyVsPlayer.stageIndividual(st.foes[0], j);
        StrategyVsPlayer.engage(st.foes, st.models);
        StrategyVsPlayer.stageAgility(st.foes[0], st.a, j);
        Stage out = new Stage();
        out.s = st;
        out.mhp = mhp;
        out.ip0 = new int[] {j.optInt("myip")};
        out.line = StrategyVsPlayer.lineOf(j, st.deck, new int[1]);
        return(out);
    }

    /** One policy, re-planned from the state each card is thrown into. */
    static Optimizer.Policy policy(String name, Stage st) {
        return((me, foes, ip, tick) -> {
            Combatant a = me.copy();
            a.readyAt = Math.max(0, me.readyAt - tick);
            a.soaked = 0;
            Combatant[] f = new Combatant[foes.length];
            for(int i = 0; i < f.length; i++) {
                f[i] = foes[i].copy();
            }
            double ow = Optimizer.armourWeight;
            boolean least = name.startsWith("least") || name.startsWith("commit");
            boolean commit = name.contains("commit");
            Optimizer.armourWeight = least ? 1.0 : 0.0;
            try {
                /* COMMITTED: the rest of the plan this policy chose last time, and the line the
                 * character threw, go in as seeds - a re-plan may replace them only with something
                 * it predicts is no worse. Without it, re-picking the cheapest-looking plan every
                 * card never follows any of them through. */
                List<List<Move>> seeds = null;
                if(commit) {
                    seeds = new ArrayList<>();
                    List<Move> rest = st.rest.get(name);
                    if((rest != null) && !rest.isEmpty())
                        seeds.add(rest);
                    if(!st.line.isEmpty())
                        seeds.add(st.line);
                }
                List<Optimizer.Plan> front = Optimizer.search(a, f, st.s.deck, st.s.models, BEAM, PLAN, ip.clone(),
                                                              null, null, seeds);
                Optimizer.Plan p;
                if(name.equals("fastest"))
                    p = Advisor.choose(front, Advisor.Aim.FASTEST, 0);
                else if(least)
                    p = Advisor.choose(front, Advisor.Aim.SAFEST, 0);
                else
                    p = Advisor.choose(front, Advisor.Aim.SURVIVE, me.hp - (Prediction.RESERVE_PVE * st.mhp));
                if((p == null) || p.moves.isEmpty())
                    return(null);
                if(commit)
                    st.rest.put(name, new ArrayList<>(p.moves.subList(1, p.moves.size())));
                if(name.equals("live") || name.endsWith("+g")) {
                    Combatant us = a.copy();
                    us.ip = ip[0];
                    WearGuard.Call c = WearGuard.walk(us, f, st.s.models, st.s.deck, ip.clone(), p.moves,
                                                      WearGuard.OPEN, WearGuard.RATIO, PLAN);
                    if(c.move != null)
                        return(c.move);
                }
                return(p.moves.get(0));
            } finally {
                Optimizer.armourWeight = ow;
            }
        });
    }
}
