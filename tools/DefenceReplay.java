import haven.automated.combat.Prediction;
import haven.combat.Advisor;
import haven.combat.Move;
import haven.combat.Optimizer;
import haven.combat.data.Pack;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.*;
import java.util.*;

/**
 * What the live advice would have thrown in a logged fight, at a given price on armour wear.
 *
 *   javac -nowarn -d %TEMP%\dr -sourcepath src tools\DefenceReplay.java
 *   (copy data\combat\*.json into %TEMP%\dr\haven\combat\data - the pack loads off the
 *    classpath, the way check-combat.ps1 stages it for LiveAdviceCheck)
 *   java -cp %TEMP%\dr DefenceReplay [-every MS] [-set W:T ...] log.jsonl ...
 *
 * NOT PART OF ANY DEFAULT RUN. It plans once per decision point per setting, at the live beam
 * and horizon, so a long fight at five settings is minutes.
 *
 * WHY IT EXISTS (COMBAT.md §3.8 D5). James: the advice "is undervaluing defense ... we're
 * routinely getting to high openings on ourself", and armour has durability. The planner's cost
 * was the soft hitpoints that got through, which armour keeps to a fifth of what lands. Two knobs
 * now price the rest - {@link Optimizer#armourWeight} (hitpoints per point of wear) and
 * {@link Advisor#costTolerance} (how much a plan may cost over the cheapest and still be thrown
 * for speed). At 0 and infinity they reproduce the old advice exactly, and that pair is always
 * the first setting run, so every other column is read against the advice James actually had.
 *
 * Each setting is given as W:T - weight, then tolerance, "inf" for none. The default grid is
 * 0:inf, 0.25:inf, 0.25:2, 0.5:2, 1:2.
 *
 * WHAT IT REPORTS, per log and setting: decision points planned; the share whose pick is a
 * restoration (a card that closes our own openings, Move.reduces); the share that differ from
 * the 0:inf pick; and the plan's predicted soft hitpoints at the pick. Beside them, what we
 * really did: the share of our logged cards that were restorations, our peak opening, and the
 * armour and soft damage the log recorded.
 *
 * Replay reads the fight the way LiveAdvice does: the sampled relation as the target, the others
 * from the `foes` rows, our openings and hitpoints from `state`. One plan per `-every` ms of fight
 * time (default 1000) rather than one per state change, which is the cost of running settings
 * side by side and does not change what a setting prefers.
 */
public class DefenceReplay {
    static final int BEAM = Integer.getInteger("beam", 60);
    static final long HORIZON = 2500;
    /* How many opponents each decision point hands the planner - LiveAdvice's CROWD, 32 since
     * 2026-09-21 (it was 4). -crowd changes it, to ask what a smaller room would have predicted. */
    static int CROWD = 32;

    public static void main(String[] args) throws Exception {
        long every = 1000;
        List<double[]> sets = new ArrayList<>();
        List<Path> logs = new ArrayList<>();
        for(int i = 0; i < args.length; i++) {
            if("-every".equals(args[i]))
                every = Long.parseLong(args[++i]);
            else if("-crowd".equals(args[i]))
                CROWD = Integer.parseInt(args[++i]);
            else if("-set".equals(args[i])) {
                String[] wt = args[++i].split(":");
                sets.add(new double[] {Double.parseDouble(wt[0]),
                        "inf".equals(wt[1]) ? Double.POSITIVE_INFINITY : Double.parseDouble(wt[1])});
            } else
                logs.add(Paths.get(args[i]));
        }
        if(sets.isEmpty()) {
            sets.add(new double[] {0, Double.POSITIVE_INFINITY});
            sets.add(new double[] {0.25, Double.POSITIVE_INFINITY});
            sets.add(new double[] {0.25, 2});
            sets.add(new double[] {0.5, 2});
            sets.add(new double[] {1, 2});
        } else if((sets.get(0)[0] != 0) || !Double.isInfinite(sets.get(0)[1])) {
            sets.add(0, new double[] {0, Double.POSITIVE_INFINITY});
        }
        Map<String, Move> sheet = new HashMap<>();
        for(Move m : Pack.moves(Paths.get("data", "combat", "moves_sheet.json")).values())
            sheet.put(m.res, m);
        for(Path p : logs)
            run(p, every, sets, sheet);
    }

    static int[] ints(JSONArray a, int from) {
        return new int[] {a.getInt(from), a.getInt(from + 1), a.getInt(from + 2), a.getInt(from + 3)};
    }

    static boolean restores(Map<String, Move> sheet, String res) {
        Move m = (res == null) ? null : sheet.get(res);
        if((m == null) || (m.reduces == null))
            return(false);
        for(double r : m.reduces)
            if(r > 0)
                return(true);
        return(false);
    }

    /** One decision point: everything adviseLive is handed. */
    static final class Point {
        final int[] mine; final double shp, mhp; final List<Prediction.Seen> seen; final long t;
        Point(int[] mine, double shp, double mhp, List<Prediction.Seen> seen, long t) {
            this.mine = mine; this.shp = shp; this.mhp = mhp; this.seen = seen; this.t = t;
        }
    }

    /* The server's tick, in ms, as the log's timestamps count it (solo_lines.TICK_MS). */
    static final double TICK_MS = 60.0;

    static void run(Path p, long every, List<double[]> sets, Map<String, Move> sheet) throws Exception {
        List<String> lines = Files.readAllLines(p);
        JSONObject begin = null;
        String[] hand = new String[2];
        double[] ql = new double[2];
        Map<Long, String> names = new HashMap<>();
        Map<Long, Double> taken = new HashMap<>();
        Map<Long, int[]> open = new LinkedHashMap<>(), ips = new HashMap<>();
        Prediction.Me me = null;
        double mhp = Double.NaN;
        long me_gob = 0, nextAt = 0;
        List<Point> points = new ArrayList<>();
        int ourCards = 0, ourRestores = 0, peak = 0;
        long arm = 0, soft = 0;
        /* Our soft hitpoints lost, when - to set each plan's prediction beside what landed. */
        List<long[]> softAt = new ArrayList<>();
        /* Each creature's last action, for Seen.sinceAct as LiveAdvice fills it. */
        Map<Long, Long> lastAct = new HashMap<>();
        /* When each other person last threw a card - LiveAdvice's share, the same rule. */
        Map<Long, Long> playerCard = new HashMap<>();
        for(String l : lines) {
            JSONObject r;
            try { r = new JSONObject(l); } catch(Exception e) { continue; }
            String ev = r.optString("ev");
            long t = r.optLong("t");
            if(ev.equals("begin")) {
                begin = r;
                me_gob = r.optLong("megob");
                if(r.has("foegob") && !r.isNull("foeres"))
                    names.put(r.getLong("foegob"), r.getString("foeres"));
            } else if(ev.equals("gear")) {
                int slot = r.optInt("slot");
                if((slot == 6) || (slot == 7)) {
                    hand[slot - 6] = r.isNull("res") ? null : r.optString("res");
                    ql[slot - 6] = r.optDouble("ql", 0);
                }
            } else if(ev.equals("foe")) {
                if(!r.isNull("res"))
                    names.put(r.getLong("gob"), r.getString("res"));
            } else if(ev.equals("move")) {
                if("foe".equals(r.optString("actor")))
                    lastAct.put(r.optLong("gob"), t);
                if("me".equals(r.optString("actor"))) {
                    ourCards++;
                    if(restores(sheet, r.optString("move", null)))
                        ourRestores++;
                }
            } else if(ev.equals("dmg")) {
                long g = r.optLong("gob");
                String ch = r.optString("ch");
                if(g == me_gob) {
                    if("ARM".equals(ch)) arm += r.optInt("v");
                    else if("SHP".equals(ch)) {
                        soft += r.optInt("v");
                        softAt.add(new long[] {t, r.optInt("v")});
                    }
                } else if("SHP".equals(ch)) {
                    taken.merge(g, (double)r.optInt("v"), Double::sum);
                }
            } else if(ev.equals("overlay")) {
                String gr = r.optString("gobres", ""), ol = r.optString("res", "");
                if(gr.contains("borka") && ol.startsWith("gfx/fx/fight/") && (r.optLong("gob") != me_gob))
                    playerCard.put(r.optLong("gob"), t);
            } else if(ev.equals("foes")) {
                JSONArray o = r.getJSONArray("o");
                JSONArray ip = r.optJSONArray("ip"), oip = r.optJSONArray("oip");
                for(int i = 0; i < o.length(); i++) {
                    JSONArray row = o.getJSONArray(i);
                    long g = row.getLong(0);
                    open.put(g, ints(row, 1));
                    if((ip != null) && (oip != null))
                        ips.put(g, new int[] {ip.getInt(i), oip.getInt(i)});
                }
            } else if(ev.equals("state") && (begin != null)) {
                int[] mine = ints(r.getJSONArray("mine"), 0);
                /* Where we stand, as LiveAdvice reads it - Pack.Opponent.hpByTile. */
                if((me != null) && !r.isNull("tile"))
                    me.at(r.optString("tile", null));
                for(int v : mine)
                    peak = Math.max(peak, v);
                if(me == null) {
                    SortedMap<String, Integer> attrs = new TreeMap<>();
                    JSONObject at = begin.getJSONObject("attr");
                    for(String k : at.keySet())
                        attrs.put(k, at.getInt(k));
                    Map<String, Integer> deck = new LinkedHashMap<>();
                    JSONObject d = begin.optJSONObject("deck");
                    if(d != null)
                        for(String k : d.keySet())
                            deck.put(k, d.getInt(k));
                    me = Prediction.me(attrs, begin.optInt("hard"), begin.optInt("soft"), hand, ql, deck);
                    mhp = at.optInt("hp", 300);
                }
                if(t < nextAt)
                    continue;
                long gob = r.getLong("gob");
                String res = names.get(gob);
                if(res == null)
                    continue;
                nextAt = t + every;
                int[] fo = ints(r.getJSONArray("foe"), 0);
                double shp = r.optDouble("hpf", 10000) / 10000.0 * mhp;
                List<Prediction.Seen> seen = new ArrayList<>();
                int allies = 0;
                for(long when : playerCard.values()) {
                    if(t - when <= ON_US_WINDOW_MS)
                        allies++;
                }
                double onUs = 1.0 / (1 + allies);
                seen.add(new Prediction.Seen(gob, res, fo, r.optInt("myip"), r.optInt("foeip"),
                                             r.optDouble("dist", Double.NaN),
                                             taken.getOrDefault(gob, 0.0), null, true)
                         .acted(lastAct.containsKey(gob) ? (t - lastAct.get(gob)) / 1000.0 : -1)
                         .aimed(share(onUs)));
                for(Map.Entry<Long, int[]> e : open.entrySet()) {
                    if((e.getKey() == gob) || !names.containsKey(e.getKey()) || (seen.size() >= CROWD))
                        continue;
                    int[] ip = ips.getOrDefault(e.getKey(), new int[] {0, 0});
                    seen.add(new Prediction.Seen(e.getKey(), names.get(e.getKey()), e.getValue(), ip[0], ip[1],
                                                 Double.NaN, taken.getOrDefault(e.getKey(), 0.0), null, true)
                             .acted(lastAct.containsKey(e.getKey()) ? (t - lastAct.get(e.getKey())) / 1000.0 : -1)
                             .aimed(share(onUs)));
                }
                points.add(new Point(mine, shp, mhp, seen, t));
                if(Boolean.getBoolean("debugshare") && (points.size() % 5 == 1))
                    System.out.println("    t=" + t + " others fighting " + allies + ", share on us " + onUs);
            }
        }
        if((me == null) || points.isEmpty()) {
            System.out.printf("%s: nothing to replay%n", p.getFileName());
            return;
        }
        String foe = (begin.isNull("foeres") ? "?" : begin.optString("foeres")).replaceAll(".*/", "");
        System.out.printf("%n%s  %s vs %s  (%d decision points)%n", p.getFileName(),
                          begin.optString("char"), foe, points.size());
        System.out.printf("  logged: %d of our cards, %.0f%% restorations; peak opening on us %d; "
                          + "armour took %d, soft hp %d%n",
                          ourCards, (ourCards == 0) ? 0 : 100.0 * ourRestores / ourCards, peak, arm, soft);
        System.out.printf("  %-12s %8s %12s %14s %14s %14s%n", "weight:tol", "planned", "restoration",
                          "differs 0:inf", "pred. soft hp", "landed, same span");
        String[] base = null;
        double w0 = Optimizer.armourWeight, t0 = Advisor.costTolerance;
        try {
            for(double[] s : sets) {
                Optimizer.armourWeight = s[0];
                Advisor.costTolerance = s[1];
                String[] picks = new String[points.size()];
                int planned = 0, rest = 0, differ = 0;
                double hp = 0, landed = 0; int hpn = 0;
                long nanos = 0, agreeNanos = 0;
                int agreeN = 0, agreeSame = 0;
                for(int i = 0; i < points.size(); i++) {
                    Point pt = points.get(i);
                    long p0 = System.nanoTime();
                    Prediction.Live live = Prediction.adviseLive(me, null, pt.mine, pt.shp, pt.mhp,
                                                                 pt.seen, BEAM, HORIZON);
                    nanos += System.nanoTime() - p0;
                    picks[i] = (live == null) ? null : live.moveRes;
                    if(Boolean.getBoolean("picks"))
                        System.out.printf("    t=%-6d now: %-26s (plan %s ticks, %s)%n", pt.t,
                                          (live == null) ? "-" : live.moveRes, (live == null) ? "-" : live.ticks,
                                          (live == null) ? "" : live.why);
                    /* -Dagree=B: plan the same point at beam B and count whether the pick holds. */
                    int ab = Integer.getInteger("agree", 0);
                    if((ab > 0) && (picks[i] != null)) {
                        long q0 = System.nanoTime();
                        Prediction.Live other = Prediction.adviseLive(me, null, pt.mine, pt.shp, pt.mhp, pt.seen, ab, HORIZON);
                        agreeNanos += System.nanoTime() - q0;
                        agreeN++;
                        if((other != null) && picks[i].equals(other.moveRes))
                            agreeSame++;
                    }
                    if(picks[i] == null)
                        continue;
                    planned++;
                    if(restores(sheet, picks[i]))
                        rest++;
                    if((base != null) && !picks[i].equals(base[i]))
                        differ++;
                    if(!Double.isNaN(live.hpLost)) {
                        hp += live.hpLost;
                        hpn++;
                        /* What really landed on us over the span the plan covers. */
                        long until = pt.t + Math.round(live.ticks * TICK_MS);
                        for(long[] h : softAt) {
                            if((h[0] > pt.t) && (h[0] <= until))
                                landed += h[1];
                        }
                    }
                }
                if(agreeN > 0)
                    System.out.printf("  %-12s same pick at beam %d: %d of %d (%.0f%%), %.0f ms/plan there%n", "",
                                      Integer.getInteger("agree", 0), agreeSame, agreeN, 100.0 * agreeSame / agreeN,
                                      agreeNanos / 1e6 / agreeN);
                if(base == null)
                    base = picks;
                System.out.printf("  %-12s %8d %11.0f%% %13s %14s %14s   %6.0f ms/plan%n",
                                  fmt(s[0]) + ":" + (Double.isInfinite(s[1]) ? "inf" : fmt(s[1])),
                                  planned, (planned == 0) ? 0 : 100.0 * rest / planned,
                                  (picks == base) ? "-" : String.format("%.0f%%", (planned == 0) ? 0 : 100.0 * differ / planned),
                                  (hpn == 0) ? "-" : String.format("%.1f", hp / hpn),
                                  (hpn == 0) ? "-" : String.format("%.1f", landed / hpn),
                                  (points.isEmpty() ? 0 : nanos / 1e6 / points.size()));
            }
        } finally {
            Optimizer.armourWeight = w0;
            Advisor.costTolerance = t0;
        }
    }

    /* LiveAdvice.ON_US_WINDOW_MS. */
    static final long ON_US_WINDOW_MS = 10000;

    /* -Dshare fixes it, to ask what another share would have predicted. */
    static double share(double live) {
        String fixed = System.getProperty("share");
        return((fixed != null) ? Double.parseDouble(fixed) : live);
    }

    static String fmt(double v) {
        return((v == Math.rint(v)) ? String.format("%.0f", v) : String.valueOf(v));
    }
}
