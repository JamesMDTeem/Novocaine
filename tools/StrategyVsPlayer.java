import haven.automated.combat.Prediction;
import haven.combat.Advisor;
import haven.combat.Combatant;
import haven.combat.data.Pack;
import haven.combat.Move;
import haven.combat.Optimizer;
import haven.combat.PartyPlanner;
import haven.combat.PartyCrowd;
import haven.combat.FoeModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The planner against the lines the characters actually threw, fight by fight, in one model.
 *
 *   python tools\combat\solo_lines.py --out JOBS.jsonl
 *   javac -nowarn -d OUT -sourcepath src tools\StrategyVsPlayer.java
 *   (copy data\combat\*.json into OUT\haven\combat\data - the pack loads off the classpath)
 *   java -cp OUT StrategyVsPlayer JOBS.jsonl RESULTS.jsonl
 *   python tools\combat\strategy_report.py RESULTS.jsonl
 *
 * NOT PART OF ANY DEFAULT RUN.
 *
 * WHY (James, 2026-09-21): "sanity check our generated strategies against the ones used in those
 * fights ... to see if they actually beat them, or how, in what metrics (speed/damage/HP taken to
 * us, Armor taken to us), and see if we can't learn off player strategy (and beat them)".
 *
 * THE COMPARISON IS INSIDE THE MODEL. Each clean solo kill is staged exactly as the live advice
 * stages a fight (Prediction.stage): the character's attributes, gear and deck at the fight's
 * start, the creature fresh. Then two lines are stepped by the same code against it:
 *
 *   player    the cards thrown in the log, in order (Optimizer.follow)
 *   planner   the search's frontier - its fastest line, its cheapest, and the one the live
 *             advice would pick (SURVIVE with a quarter of the bar to spend)
 *
 * The player's line also has the log's own figures beside it, which is the model's calibration:
 * where the modelled player line and the logged fight disagree, the model is wrong about that
 * matchup, and a planner win there is a win in a fight that does not exist.
 */
public class StrategyVsPlayer {
    /* 60, the live advice's beam; -Dsolobeam=N asks whether a tie is only the search's ceiling. */
    static final int BEAM = Integer.getInteger("solobeam", 60);
    static final long HORIZON = 6000;
    static Map<String, Pack.Opponent> OPP;

    public static void main(String[] args) throws Exception {
        List<String> jobs = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        /* -Dlegacyfoe=true: the creature before its cooldowns rode our agility and it paid for its
         * cards (FoeModel.legacy) - the control for that change. */
        FoeModel.legacy = Boolean.parseBoolean(System.getProperty("legacyfoe", "false"));
        haven.combat.Repertoire.quotaDeal = !"deficit".equals(System.getProperty("deal"));
        OPP = Pack.opponentsFromJar();
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        /* The pack loads lazily on first use; load it here, once, before any worker can race it. */
        for(String j : jobs) {
            if(!j.trim().isEmpty()) {
                try {
                    one(new JSONObject(j));
                } catch(Exception e) {
                    /* reported per fight below */
                }
                break;
            }
        }
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future<String>> out = new ArrayList<>();
        for(String j : jobs) {
            if(j.trim().isEmpty())
                continue;
            out.add(ex.submit(() -> one(new JSONObject(j))));
        }
        int n = 0;
        Map<String, Integer> why = new TreeMap<>();
        try(BufferedWriter w = Files.newBufferedWriter(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            for(Future<String> f : out) {
                String r;
                try {
                    r = f.get();
                } catch(ExecutionException e) {
                    Throwable c = e.getCause();
                    r = "error: " + c.getClass().getSimpleName() + ": " + c.getMessage();
                }
                if(r.startsWith("error: ") || r.startsWith("skip: ")) {
                    why.merge(r.length() > 140 ? r.substring(0, 140) : r, 1, Integer::sum);
                    continue;
                }
                w.write(r);
                w.newLine();
                n++;
            }
        }
        ex.shutdown();
        System.out.printf("%d fights compared -> %s%n", n, args[1]);
        for(Map.Entry<String, Integer> e : why.entrySet())
            System.out.printf("  %5d  %s%n", e.getValue(), e.getKey());
    }

    static JSONObject plan(Optimizer.Plan p) {
        JSONObject o = new JSONObject();
        if(p == null)
            return(o.put("none", true));
        List<String> names = new ArrayList<>();
        for(Move m : p.moves)
            names.add(m.name);
        return(o.put("ticks", p.ticks).put("hp", p.hpLost).put("soaked", p.soaked)
               .put("killed", p.killed).put("foeHp", p.foeHp).put("cards", p.moves.size())
               .put("line", names.subList(0, Math.min(12, names.size()))));
    }

    static Prediction.Me meOf(JSONObject j) {
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
        Prediction.Me me = Prediction.me(attrs, j.optInt("hard"), j.optInt("soft"), hand, ql, deck);
        return(held(me, j, hand));
    }

    /* The stance held into the fight, from the log's own `buffs` row for us. */
    static Prediction.Me held(Prediction.Me me, JSONObject j, String[] hand) {
        JSONArray b = j.optJSONArray("buffs");
        if((me == null) || (b == null))
            return(me);
        String[] names = new String[b.length()];
        for(int i = 0; i < names.length; i++)
            names[i] = b.getString(i);
        boolean shield = false;
        for(String h : hand)
            shield |= (h != null) && h.contains("shield");
        return(me.holding(names, shield));
    }

    static List<Move> lineOf(JSONObject j, List<Move> deck, int[] unknown) {
        Map<String, Move> byRes = new HashMap<>();
        for(Move m : deck)
            byRes.put(m.res, m);
        List<Move> line = new ArrayList<>();
        JSONArray lj = j.getJSONArray("line");
        for(int i = 0; i < lj.length(); i++) {
            Move m = byRes.get(lj.getString(i));
            if(m == null)
                unknown[0]++;
            else
                line.add(m);
        }
        return(line);
    }

    /**
     * One of us against several creatures (COMBAT.md §3.10). Each creature is staged at the
     * hitpoints that killed it and joins at the tick it arrived, in the order they died - the
     * model hits the first one standing, so that order is the player's target order.
     */
    static String crowd(JSONObject j) {
        Prediction.Me me = meOf(j);
        double mhp = j.getJSONObject("attr").optDouble("hp", 300);
        JSONArray mi = j.getJSONArray("mine");
        int[] mine = {mi.getInt(0), mi.getInt(1), mi.getInt(2), mi.getInt(3)};
        JSONArray fj = j.getJSONArray("foes");
        List<Prediction.Seen> seen = new ArrayList<>();
        for(int i = 0; i < fj.length(); i++) {
            JSONObject f = fj.getJSONObject(i);
            JSONArray ag = f.optJSONArray("agi");
            seen.add(new Prediction.Seen(f.getLong("gob"), f.getString("res"), new int[] {0, 0, 0, 0},
                                         (i == 0) ? j.optInt("myip") : 0, 0, Double.NaN, 0, null, true,
                                         (ag == null) ? 0 : ag.optDouble(0, 0), (ag == null) ? 2 : ag.optDouble(1, 2)));
        }
        Prediction.Staged st = Prediction.stage(me, null, mine, mhp, mhp, seen);
        if(st.refused != null)
            return("skip: the live advice would not plan it - " + st.refused);
        if(st.proxied > 0)
            return("skip: the pack does not know one of " + j.getString("species"));
        if(st.foes.length != fj.length())
            return("skip: not every creature could be staged");
        long[] arrive = new long[st.foes.length];
        double total = 0;
        for(int i = 0; i < st.foes.length; i++) {
            JSONObject f = fj.getJSONObject(i);
            st.foes[i].hp = st.foes[i].maxHp = f.getDouble("hp");
            arrive[i] = f.optLong("arrive", 0);
            total += st.foes[i].maxHp;
        }
        engage(st.foes, st.models);
        int[] ip0 = new int[st.foes.length];
        ip0[0] = j.optInt("myip");
        /* Each card at the creature it hit, as the log says - dropping the targets of any card the
         * deck does not hold, so the two stay aligned. */
        Map<String, Move> byRes = new HashMap<>();
        for(Move m : st.deck)
            byRes.put(m.res, m);
        List<Move> line = new ArrayList<>();
        List<Integer> aims = new ArrayList<>();
        int[] unknown = {0};
        JSONArray lj = j.getJSONArray("line"), tj = j.optJSONArray("targets");
        for(int i = 0; i < lj.length(); i++) {
            Move m = byRes.get(lj.getString(i));
            if(m == null) {
                unknown[0]++;
                continue;
            }
            line.add(m);
            aims.add((tj == null) ? -1 : tj.optInt(i, -1));
        }
        int[] targets = new int[aims.size()];
        for(int i = 0; i < targets.length; i++)
            targets[i] = aims.get(i);

        List<List<Move>> shown = seeded(line);
        List<Optimizer.Plan> front = Optimizer.search(st.a, st.foes, st.deck, st.models, BEAM, HORIZON,
                                                      ip0, null, arrive, shown);
        int[] skipped = {0};
        Optimizer.Plan player = Optimizer.follow(st.a, st.foes, st.models, st.deck, line, HORIZON,
                                                 ip0, skipped, arrive, targets);

        /* CALIBRATED: every creature scaled by the share of the crowd's hitpoints the player's line
         * took off in the model, so that line clears the room about where it did in the log. */
        JSONObject cal = new JSONObject();
        double took = total - player.foeHp;
        if((took > 0) && (total > 0)) {
            double c = took / total;
            double[] hp0 = new double[st.foes.length];
            for(int i = 0; i < st.foes.length; i++) {
                hp0[i] = st.foes[i].maxHp;
                st.foes[i].hp = st.foes[i].maxHp = hp0[i] * c * CAL_SLACK;
            }
            List<Optimizer.Plan> cf = Optimizer.search(st.a, st.foes, st.deck, st.models, BEAM, HORIZON,
                                                       ip0, null, arrive, shown);
            int[] sk2 = {0};
            Optimizer.Plan cp = Optimizer.follow(st.a, st.foes, st.models, st.deck, line, HORIZON,
                                                 ip0, sk2, arrive, targets);
            cal.put("credit", c).put("player", plan(cp))
                .put("fast", plan(Advisor.choose(cf, Advisor.Aim.FASTEST, 0)))
                .put("safe", plan(Advisor.choose(cf, Advisor.Aim.SAFEST, 0)))
                .put("live", plan(Advisor.choose(cf, Advisor.Aim.SURVIVE, 0.25 * mhp)));
            for(int i = 0; i < st.foes.length; i++)
                st.foes[i].hp = st.foes[i].maxHp = hp0[i];
        }
        JSONObject o = new JSONObject();
        o.put("shape", "1vN").put("path", j.getString("path")).put("char", j.getString("char"))
            .put("species", j.getString("species")).put("nfoes", fj.length()).put("mhp", mhp)
            .put("foeHp0", total).put("staged", "dealt").put("ip", j.optInt("myip"))
            .put("logged", j.getJSONObject("logged"))
            .put("player", plan(player).put("skipped", skipped[0]).put("unknown", unknown[0]))
            .put("fast", plan(Advisor.choose(front, Advisor.Aim.FASTEST, 0)))
            .put("safe", plan(Advisor.choose(front, Advisor.Aim.SAFEST, 0)))
            .put("live", plan(Advisor.choose(front, Advisor.Aim.SURVIVE, 0.25 * mhp)))
            .put("cal", cal);
        return(o.toString());
    }

    /* 20 by default; -Dbeam=N to ask whether a loss is the beam. */
    /* THE CALIBRATED CREATURE HAS 1% LESS THAN THE PLAYER'S LINE DEALT, not a hair less. At a hair,
     * a planner line matching the player's card for card but resolving two same-tick cards in the
     * other order deals a fraction of a point less and needs another full cycle: 36 ticks against
     * the player's 22 on a vulture bee, scored as a loss (2026-09-21). Both lines get the slack. */
    static final double CAL_SLACK = Double.parseDouble(System.getProperty("calslack", "0.99"));

    /**
     * Every creature starts the way it does at a fight's first card: acting at once, a
     * Prediction.FIRST_SHARE of its period in, rather than a full period later (Combatant.firstAct).
     * -Dfirst=period keeps the old reading, for comparison.
     */
    static void engage(Combatant[] foes, FoeModel[] models) {
        if("period".equals(System.getProperty("first")))
            return;
        for(int i = 0; (i < foes.length) && (i < models.length); i++)
            foes[i].firstAct = Prediction.firstAct(models[i].period, -1);
    }

    static final int PARTY_BEAM = Integer.getInteger("beam", 20);

    /**
     * WHETHER THE PLANNER IS SHOWN THE PLAYER'S OWN LINE (James, 2026-09-22: "it should never have a
     * worse outcome than a player displayed one"). On by default: the search is offered the logged
     * line beside its own (Optimizer.search with lines, and the party planners' equivalent), so a
     * "loses" row is a bug, not a reading. -Down=false is the control - the search alone, which lost
     * 2% of solo fights and 16% of party ones before the line was offered.
     */
    static final boolean OWN = !"false".equals(System.getProperty("own"));

    static List<List<Move>> seeded(List<Move> line) {
        return((OWN && !line.isEmpty()) ? java.util.Collections.singletonList(line) : null);
    }

    static JSONArray arr(double[] v) {
        JSONArray a = new JSONArray();
        for(double x : v)
            a.put(x);
        return(a);
    }

    static JSONObject partyPlan(PartyPlanner.Plan p) {
        JSONObject o = new JSONObject();
        if(p == null)
            return(o.put("none", true));
        double soaked = 0;
        for(double x : p.soaked)
            soaked += x;
        List<String> names = new ArrayList<>();
        for(PartyPlanner.Step st : p.steps) {
            if(names.size() >= 16)
                break;
            names.add(st.who + ":" + st.move.name);
        }
        return(o.put("ticks", p.ticks).put("hp", p.totalLost).put("soaked", soaked)
               .put("killed", p.killed).put("foeHp", p.foeHp).put("cards", p.steps.size())
               .put("line", names).put("perHp", arr(p.hpLost)).put("perSoaked", arr(p.soaked)));
    }

    /* The party picks read to the same resolution as Advisor.choose: a tick and half a hitpoint
     * are ties, and inside them the other measure decides, armour last (2026-09-22). */
    static PartyPlanner.Plan fastest(List<PartyPlanner.Plan> front) {
        return(partyPick(front, true));
    }

    static PartyPlanner.Plan safest(List<PartyPlanner.Plan> front) {
        return(partyPick(front, false));
    }

    static double sum(double[] v) {
        double t = 0;
        for(double x : v)
            t += x;
        return(t);
    }

    static PartyPlanner.Plan partyPick(List<PartyPlanner.Plan> front, boolean fast) {
        List<PartyPlanner.Plan> k = new ArrayList<>();
        for(PartyPlanner.Plan p : front)
            if(p.killed)
                k.add(p);
        if(k.isEmpty())
            return(null);
        long t0 = Long.MAX_VALUE;
        double h0 = Double.POSITIVE_INFINITY;
        for(PartyPlanner.Plan p : k) {
            t0 = Math.min(t0, p.ticks);
            h0 = Math.min(h0, p.totalLost);
        }
        PartyPlanner.Plan best = null;
        for(PartyPlanner.Plan p : k) {
            if(fast ? (p.ticks > t0 + Advisor.TICK_EPS) : (p.totalLost > h0 + Advisor.HP_EPS))
                continue;
            if(best == null) {
                best = p;
                continue;
            }
            int c = fast ? Double.compare(p.totalLost, best.totalLost) : Long.compare(p.ticks, best.ticks);
            if(c == 0)
                c = fast ? Long.compare(p.ticks, best.ticks) : Double.compare(p.totalLost, best.totalLost);
            if(c == 0)
                c = Double.compare(sum(p.soaked), sum(best.soaked));
            if(c < 0)
                best = p;
        }
        return(best);
    }

    /**
     * A party against one creature (COMBAT.md §3.10), joined across the members' own logs by
     * tools/combat/party_lines.py. Each member is staged exactly as the live advice would stage
     * them against that creature; the creature once, at the hitpoints that killed it. The logged
     * lines go through PartyPlanner.follow, each member joining at their own tick; the planner is
     * PartyPlanner.search from the same start with the same front.
     */
    static String party(JSONObject j) {
        JSONArray mj = j.getJSONArray("members");
        int n = mj.length();
        PartyPlanner.Member[] party = new PartyPlanner.Member[n];
        List<List<Move>> lines = new ArrayList<>();
        long[] start = new long[n];
        Combatant foe = null;
        FoeModel model = null;
        int unknown = 0;
        for(int i = 0; i < n; i++) {
            JSONObject m = mj.getJSONObject(i);
            Prediction.Me me = meOf(m);
            double mhp = m.getJSONObject("attr").optDouble("hp", 300);
            List<Prediction.Seen> seen = Arrays.asList(new Prediction.Seen(j.getLong("gob"), j.getString("res"),
                new int[] {0, 0, 0, 0}, m.optInt("myip"), 0, Double.NaN, 0, null, true));
            Prediction.Staged st = Prediction.stage(me, null, new int[] {0, 0, 0, 0}, mhp, mhp, seen);
            if(st.refused != null)
                return("skip: the live advice would not plan " + m.getString("char") + " - " + st.refused);
            if(st.proxied > 0)
                return("skip: the pack does not know " + j.getString("species"));
            party[i] = new PartyPlanner.Member(m.getString("char"), st.a, st.deck);
            int[] unk = {0};
            lines.add(lineOf(m, st.deck, unk));
            unknown += unk[0];
            start[i] = m.optLong("start", 0);
            if(foe == null) {
                foe = st.foes[0];
                model = st.models[0];
            }
        }
        double hp0 = j.getDouble("hp");
        foe.hp = foe.maxHp = hp0;
        engage(new Combatant[] {foe}, new FoeModel[] {model});
        int front = j.optInt("front", 0);
        int[] skipped = {0};
        PartyPlanner.Plan player = PartyPlanner.follow(party, foe, model, front, null, lines, start,
                                                       HORIZON, skipped);
        List<PartyPlanner.Plan> fr = PartyPlanner.search(party, foe, model, front, null, PARTY_BEAM, HORIZON,
                                                         OWN ? lines : null, start);

        JSONObject cal = new JSONObject();
        double took = hp0 - player.foeHp;
        if(!player.killed && (took > 0)) {
            foe.hp = foe.maxHp = took * CAL_SLACK;
            int[] sk2 = {0};
            PartyPlanner.Plan cp = PartyPlanner.follow(party, foe, model, front, null, lines, start, HORIZON, sk2);
            List<PartyPlanner.Plan> cf = PartyPlanner.search(party, foe, model, front, null, PARTY_BEAM, HORIZON,
                                                             OWN ? lines : null, start);
            cal.put("player", partyPlan(cp)).put("fast", partyPlan(fastest(cf)))
                .put("safe", partyPlan(safest(cf))).put("live", partyPlan(fastest(cf)));
            foe.hp = foe.maxHp = hp0;
        } else if(player.killed) {
            cal.put("player", partyPlan(player)).put("fast", partyPlan(fastest(fr)))
                .put("safe", partyPlan(safest(fr))).put("live", partyPlan(fastest(fr)));
        }
        JSONObject o = new JSONObject();
        o.put("shape", "Nv1").put("path", j.getString("path")).put("char", j.getString("char"))
            .put("species", j.getString("species")).put("foeHp0", hp0).put("staged", "dealt")
            .put("front", front).put("logged", j.getJSONObject("logged"))
            .put("player", partyPlan(player).put("skipped", skipped[0]).put("unknown", unknown))
            .put("fast", partyPlan(fastest(fr))).put("safe", partyPlan(safest(fr)))
            .put("live", partyPlan(fastest(fr))).put("cal", cal);
        return(o.toString());
    }

    /**
     * The creature's agility from the client's own bracket for this fight, where it logged one: our
     * agility times the geometric middle of the bracket, clipped to the [0.5, 2] the cooldown factor
     * is clamped to. Without it the species' agility cap is staged - "faster than us" - and every
     * card of ours ran at 1.1 of its base against bats, foxes and swans the log shows at 0.9.
     */
    static boolean stageAgility(Combatant foe, Combatant me, JSONObject j) {
        JSONArray b = j.optJSONArray("agi");
        if((b == null) || (me.agi <= 0) || !Boolean.parseBoolean(System.getProperty("bracket", "true")))
            return(false);
        double agi = Prediction.agilityFrom(me.agi, b.optDouble(0, 0), b.optDouble(1, 2));
        if(Double.isNaN(agi))
            return(false);
        foe.agi = agi;
        return(true);
    }

    static JSONObject crowdPlan(PartyCrowd.Plan p) {
        JSONObject o = new JSONObject();
        if(p == null)
            return(o.put("none", true));
        double soaked = 0;
        for(double x : p.soaked)
            soaked += x;
        List<String> names = new ArrayList<>();
        for(PartyCrowd.Step st : p.steps) {
            if(names.size() >= 20)
                break;
            names.add(st.who + ":" + st.move.name + "@" + st.at);
        }
        return(o.put("ticks", p.ticks).put("hp", p.totalLost).put("soaked", soaked)
               .put("killed", p.killed).put("foeHp", p.foeHp).put("cards", p.steps.size())
               .put("line", names).put("perHp", arr(p.hpLost)).put("perSoaked", arr(p.soaked)));
    }

    static PartyCrowd.Plan crowdFastest(List<PartyCrowd.Plan> front) {
        return(crowdPick(front, true));
    }

    static PartyCrowd.Plan crowdSafest(List<PartyCrowd.Plan> front) {
        return(crowdPick(front, false));
    }

    static PartyCrowd.Plan crowdPick(List<PartyCrowd.Plan> front, boolean fast) {
        List<PartyCrowd.Plan> k = new ArrayList<>();
        for(PartyCrowd.Plan p : front)
            if(p.killed)
                k.add(p);
        if(k.isEmpty())
            return(null);
        long t0 = Long.MAX_VALUE;
        double h0 = Double.POSITIVE_INFINITY;
        for(PartyCrowd.Plan p : k) {
            t0 = Math.min(t0, p.ticks);
            h0 = Math.min(h0, p.totalLost);
        }
        PartyCrowd.Plan best = null;
        for(PartyCrowd.Plan p : k) {
            if(fast ? (p.ticks > t0 + Advisor.TICK_EPS) : (p.totalLost > h0 + Advisor.HP_EPS))
                continue;
            if(best == null) {
                best = p;
                continue;
            }
            int c = fast ? Double.compare(p.totalLost, best.totalLost) : Long.compare(p.ticks, best.ticks);
            if(c == 0)
                c = fast ? Long.compare(p.ticks, best.ticks) : Double.compare(p.totalLost, best.totalLost);
            if(c == 0)
                c = Double.compare(sum(p.soaked), sum(best.soaked));
            if(c < 0)
                best = p;
        }
        return(best);
    }

    static final int NVN_BEAM = Integer.getInteger("nvnbeam", 24);

    /**
     * A party against several creatures (COMBAT.md §3.12), joined by tools/combat/nvn_lines.py and
     * planned by haven.combat.PartyCrowd. Each member is staged as the live advice would stage them
     * against the whole room, the creatures once, at the hitpoints that killed them, each joining
     * when it was first seen and going first for the member it hit first. The calibrated pass
     * scales every creature by the share of the room the logged lines cleared in the model.
     */
    static String nvn(JSONObject j) {
        JSONArray mj = j.getJSONArray("members"), fj = j.getJSONArray("foes");
        int n = mj.length(), nf = fj.length();
        PartyPlanner.Member[] party = new PartyPlanner.Member[n];
        List<List<Move>> lines = new ArrayList<>();
        int[][] at = new int[n][];
        long[] start = new long[n];
        Combatant[] foes = null;
        FoeModel[] models = null;
        int unknown = 0;
        for(int i = 0; i < n; i++) {
            JSONObject m = mj.getJSONObject(i);
            Prediction.Me me = meOf(m);
            double mhp = m.getJSONObject("attr").optDouble("hp", 300);
            List<Prediction.Seen> seen = new ArrayList<>();
            for(int k = 0; k < nf; k++) {
                JSONObject f = fj.getJSONObject(k);
                seen.add(new Prediction.Seen(f.getLong("gob"), f.getString("res"), new int[] {0, 0, 0, 0},
                                             (k == 0) ? m.optInt("myip") : 0, 0, Double.NaN, 0, null, true));
            }
            Prediction.Staged st = Prediction.stage(me, null, new int[] {0, 0, 0, 0}, mhp, mhp, seen);
            if(st.refused != null)
                return("skip: the live advice would not plan " + m.getString("char") + " - " + st.refused);
            if(st.proxied > 0)
                return("skip: the pack does not know one of " + j.getString("species"));
            if(st.foes.length != nf)
                return("skip: not every creature could be staged");
            party[i] = new PartyPlanner.Member(m.getString("char"), st.a, st.deck);
            Map<String, Move> byRes = new HashMap<>();
            for(Move mv : st.deck)
                byRes.put(mv.res, mv);
            List<Move> line = new ArrayList<>();
            List<Integer> aims = new ArrayList<>();
            JSONArray lj = m.getJSONArray("line"), aj = m.getJSONArray("at");
            for(int k = 0; k < lj.length(); k++) {
                Move mv = byRes.get(lj.getString(k));
                if(mv == null) {
                    unknown++;
                    continue;
                }
                line.add(mv);
                aims.add(aj.optInt(k, -1));
            }
            lines.add(line);
            at[i] = new int[aims.size()];
            for(int k = 0; k < at[i].length; k++)
                at[i][k] = aims.get(k);
            start[i] = m.optLong("start", 0);
            if(foes == null) {
                foes = st.foes;
                models = st.models;
            }
        }
        int[] aggro = new int[nf];
        long[] arrive = new long[nf];
        double total = 0;
        for(int k = 0; k < nf; k++) {
            JSONObject f = fj.getJSONObject(k);
            foes[k].hp = foes[k].maxHp = f.getDouble("hp");
            aggro[k] = f.optInt("aggro", 0);
            arrive[k] = f.optLong("arrive", 0);
            total += foes[k].maxHp;
        }
        engage(foes, models);
        int[] skipped = {0};
        PartyCrowd.Plan player = PartyCrowd.follow(party, foes, models, aggro, arrive, lines, at, start,
                                                   HORIZON, skipped);
        List<PartyCrowd.Plan> fr = PartyCrowd.search(party, foes, models, aggro, arrive, NVN_BEAM, HORIZON,
                                                     OWN ? lines : null, at, start);
        JSONObject cal = new JSONObject();
        double took = total - player.foeHp;
        if((took > 0) && (total > 0)) {
            double c = took / total;
            double[] hp0 = new double[nf];
            for(int k = 0; k < nf; k++) {
                hp0[k] = foes[k].maxHp;
                foes[k].hp = foes[k].maxHp = hp0[k] * c * CAL_SLACK;
            }
            PartyCrowd.Plan cp = PartyCrowd.follow(party, foes, models, aggro, arrive, lines, at, start,
                                                   HORIZON, new int[1]);
            List<PartyCrowd.Plan> cf = PartyCrowd.search(party, foes, models, aggro, arrive, NVN_BEAM, HORIZON,
                                                         OWN ? lines : null, at, start);
            cal.put("player", crowdPlan(cp)).put("fast", crowdPlan(crowdFastest(cf)))
                .put("safe", crowdPlan(crowdSafest(cf))).put("live", crowdPlan(crowdFastest(cf)));
            for(int k = 0; k < nf; k++)
                foes[k].hp = foes[k].maxHp = hp0[k];
        }
        JSONObject o = new JSONObject();
        o.put("shape", "NvN").put("path", j.getString("path")).put("char", j.getString("char"))
            .put("species", j.getString("species")).put("foeHp0", total).put("staged", "dealt")
            .put("logged", j.getJSONObject("logged"))
            .put("player", crowdPlan(player).put("skipped", skipped[0]).put("unknown", unknown))
            .put("fast", crowdPlan(crowdFastest(fr))).put("safe", crowdPlan(crowdSafest(fr)))
            .put("live", crowdPlan(crowdFastest(fr))).put("cal", cal);
        return(o.toString());
    }

    static String stageIndividual(Combatant foe, JSONObject j) {
        Pack.Opponent o = (OPP == null) ? null : OPP.get(j.getString("species"));
        long gob = j.optLong("gob", 0);
        if((o != null) && (gob != 0)) {
            for(Pack.Individual ind : o.individuals()) {
                if(ind.gob != gob)
                    continue;
                Combatant c = o.individual(ind);
                if((c != null) && (c.hp > 0)) {
                    foe.hp = foe.maxHp = c.hp;
                    foe.agi = c.agi;
                    /* Its own skill only where one was measured for it; otherwise the species'
                     * estimate the live advice stages (Prediction.planned), not the band's top. */
                    if(!Double.isNaN(ind.skill))
                        foe.blockSkill = c.blockSkill;
                    return("individual");
                }
            }
        }
        double dealt = j.getJSONObject("logged").optDouble("dealt", 0);
        if(dealt > 0) {
            foe.hp = foe.maxHp = dealt;
            return("dealt");
        }
        return("hardest");
    }

    static String one(JSONObject j) {
        if("NvN".equals(j.optString("shape")))
            return(nvn(j));
        if(j.has("foes"))
            return(crowd(j));
        if(j.has("members"))
            return(party(j));
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
        Prediction.Me me = held(Prediction.me(attrs, j.optInt("hard"), j.optInt("soft"), hand, ql, deck), j, hand);
        double mhp = at.optDouble("hp", 300);
        JSONArray mi = j.getJSONArray("mine");
        int[] mine = {mi.getInt(0), mi.getInt(1), mi.getInt(2), mi.getInt(3)};
        List<Prediction.Seen> foe = Arrays.asList(new Prediction.Seen(1, j.getString("res"),
            new int[] {0, 0, 0, 0}, j.optInt("myip"), 0, Double.NaN, 0, null, true));
        Prediction.Staged st = Prediction.stage(me, null, mine, mhp, mhp, foe);
        if(st.refused != null)
            return("skip: the live advice would not plan it - " + st.refused);
        if(st.proxied > 0)
            return("skip: the pack does not know " + j.getString("species"));
        /* THE CREATURE THAT WAS FOUGHT, NOT THE HARDEST OF ITS KIND. The live advice plans every
         * creature as the hardest individual ever logged, because it cannot see the one in front
         * of it - and bats run from 24 to 340 hitpoints. Scored against that, the player's line
         * never killed a single bat: it faced a 31-point bat and the model a 340-point one. So the
         * individual's own row is staged where the pack has it (the hitpoints it died at, and its
         * agility and skill where they were measured), else the damage the log says killed it. */
        String how = stageIndividual(st.foes[0], j);
        engage(st.foes, st.models);
        if(stageAgility(st.foes[0], st.a, j))
            how += "+agi";

        /* The initiative banked before the fight engaged, which both lines start with. */
        int[] ip0 = {j.optInt("myip")};
        Map<String, Move> byRes = new HashMap<>();
        for(Move m : st.deck)
            byRes.put(m.res, m);
        List<Move> line = new ArrayList<>();
        int unknown = 0;
        JSONArray lj = j.getJSONArray("line");
        for(int i = 0; i < lj.length(); i++) {
            Move m = byRes.get(lj.getString(i));
            if(m == null)
                unknown++;
            else
                line.add(m);
        }
        List<List<Move>> shown = seeded(line);
        List<Optimizer.Plan> front = Optimizer.search(st.a, st.foes, st.deck, st.models, BEAM, HORIZON,
                                                      ip0, null, null, shown);
        Optimizer.Plan fast = Advisor.choose(front, Advisor.Aim.FASTEST, 0);
        Optimizer.Plan safe = Advisor.choose(front, Advisor.Aim.SAFEST, 0);
        Optimizer.Plan live = Advisor.choose(front, Advisor.Aim.SURVIVE, 0.25 * mhp);
        int[] skipped = {0};
        Optimizer.Plan player = Optimizer.follow(st.a, st.foes, st.models, st.deck, line, HORIZON,
                                                 ip0, skipped);

        /* CALIBRATED TO THIS FIGHT. The model credits a line with roughly a tenth less damage than
         * it did, so the player's own cards often leave the real creature on a sliver - and the
         * planner, fighting that same under-credited creature, has to add a whole card to finish
         * it. Its "losses" were largely that deficit, paid in whole cards while the player's was
         * extrapolated as a fraction. So the creature is staged a second time at exactly the damage
         * the player's line did to it in the model: that line then kills on its last card, as it did
         * in the log, and the planner faces the same creature in the same units. */
        JSONObject cal = new JSONObject();
        double dealtModel = st.foes[0].maxHp - player.foeHp;
        if(!player.killed && (dealtModel > 0)) {
            double hp0 = st.foes[0].maxHp;
            st.foes[0].hp = st.foes[0].maxHp = dealtModel * CAL_SLACK;
            List<Optimizer.Plan> cf = Optimizer.search(st.a, st.foes, st.deck, st.models, BEAM, HORIZON,
                                                       ip0, null, null, shown);
            int[] sk2 = {0};
            Optimizer.Plan cp = Optimizer.follow(st.a, st.foes, st.models, st.deck, line, HORIZON, ip0, sk2);
            cal.put("foeHp", st.foes[0].maxHp).put("player", plan(cp))
                .put("fast", plan(Advisor.choose(cf, Advisor.Aim.FASTEST, 0)))
                .put("safe", plan(Advisor.choose(cf, Advisor.Aim.SAFEST, 0)))
                .put("live", plan(Advisor.choose(cf, Advisor.Aim.SURVIVE, 0.25 * mhp)));
            st.foes[0].hp = st.foes[0].maxHp = hp0;
        } else if(player.killed) {
            cal.put("foeHp", st.foes[0].maxHp).put("player", plan(player)).put("fast", plan(fast))
                .put("safe", plan(safe)).put("live", plan(live));
        }

        JSONObject o = new JSONObject();
        o.put("cal", cal).put("ip", j.optInt("myip")).put("free", j.optJSONArray("free"));
        o.put("path", j.getString("path")).put("char", j.getString("char")).put("staged", how)
            .put("foeHp0", st.foes[0].maxHp)
            .put("species", j.getString("species")).put("mhp", mhp)
            .put("logged", j.getJSONObject("logged"))
            .put("player", plan(player).put("skipped", skipped[0]).put("unknown", unknown))
            .put("fast", plan(fast)).put("safe", plan(safe)).put("live", plan(live));
        return(o.toString());
    }
}
