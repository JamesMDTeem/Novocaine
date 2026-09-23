import haven.automated.combat.Prediction;
import haven.combat.Combatant;
import haven.combat.Move;
import haven.combat.Optimizer;
import haven.combat.Sim;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The model's forward run set beside the log, card by card (COMBAT.md §3.11).
 *
 *   java OpeningDrift JOBS.jsonl [CHAR] [SPECIES]
 *
 * JOBS is tools/combat/solo_lines.py output, whose "trace" holds, per card of the player's line,
 * the creature's openings the log saw before it and the soft hitpoints it took off. Each job is
 * staged exactly as StrategyVsPlayer stages it, the creature given hitpoints enough that every card
 * is thrown, and the line walked with Optimizer.follow while Optimizer.trace records the state each
 * card was thrown into and what it dealt.
 *
 * replay.py tests each step against the OBSERVED state; this tests the accumulation - decay, the
 * creature's own reductions - which replay cannot see, and which is where a fight that builds
 * openings for seven cards and then lands one Cleave can go wrong while every step agrees.
 *
 * Prints, per card position: the model's and the log's summed openings on the creature before the
 * card, and the damage each says it did.
 */
public class OpeningDrift {
    public static void main(String[] args) throws Exception {
        String who = (args.length > 1) ? args[1] : null, what = (args.length > 2) ? args[2] : null;
        /* -Dlegacyfoe=true: the creature before 2026-09-23 - see FoeModel.legacy. */
        haven.combat.FoeModel.legacy = Boolean.parseBoolean(System.getProperty("legacyfoe", "false"));
        haven.combat.Repertoire.quotaDeal = !"deficit".equals(System.getProperty("deal"));
        int N = Integer.getInteger("cards", 16);
        double[] mOpen = new double[N], lOpen = new double[N], mDealt = new double[N], lDealt = new double[N];
        int[] n = new int[N];
        double[][] mCol = new double[N][4], lCol = new double[N][4];
        double[] mMine = new double[N], lMine = new double[N];
        int fights = 0;
        java.util.Map<String, double[]> pace = new java.util.TreeMap<>();
        try(BufferedReader in = new BufferedReader(new FileReader(args[0]))) {
            String s;
            while((s = in.readLine()) != null) {
                JSONObject j = new JSONObject(s);
                if(((who != null) && !who.equals(j.getString("char")))
                   || ((what != null) && !what.equals(j.getString("species"))) || !j.has("trace"))
                    continue;
                Prediction.Me me = StrategyVsPlayer.meOf(j);
                double mhp = j.getJSONObject("attr").optDouble("hp", 300);
                JSONArray mi = j.getJSONArray("mine");
                int[] mine = {mi.getInt(0), mi.getInt(1), mi.getInt(2), mi.getInt(3)};
                List<Prediction.Seen> seen = Arrays.asList(new Prediction.Seen(1, j.getString("res"),
                    new int[] {0, 0, 0, 0}, j.optInt("myip"), 0, Double.NaN, 0, null, true));
                Prediction.Staged st = Prediction.stage(me, null, mine, mhp, mhp, seen);
                if((st.refused != null) || (st.proxied > 0))
                    continue;
                StrategyVsPlayer.stageIndividual(st.foes[0], j);
                StrategyVsPlayer.stageAgility(st.foes[0], st.a, j);
                StrategyVsPlayer.engage(st.foes, st.models);
                /* Enough hitpoints that the whole line is thrown - the kill is not the question. */
                st.foes[0].hp = st.foes[0].maxHp = 1e6;
                int[] unk = {0};
                List<Move> line = StrategyVsPlayer.lineOf(j, st.deck, unk);
                if(unk[0] > 0)
                    continue;
                List<double[]> seenOpen = new ArrayList<>(), seenMine = new ArrayList<>();
                List<Double> dealt = new ArrayList<>();
                long[] acted = {0};
                Optimizer.trace = new Optimizer.Trace() {
                    public void before(Move m, long tick, Combatant a, Combatant foe) {
                        seenOpen.add(foe.openings.clone());
                        seenMine.add(a.openings.clone());
                    }
                    public void after(Move m, long tick, Combatant a, Combatant foe, Sim.Result r) {
                        dealt.add(r.dealt);
                    }
                    public void foeActed(int who, long tick) {
                        acted[0]++;
                    }
                };
                Optimizer.follow(st.a, st.foes, st.models, st.deck, line, 1_000_000, new int[] {j.optInt("myip")},
                                 new int[] {0});
                Optimizer.trace = null;
                JSONArray tr = j.getJSONArray("trace");
                if(seenOpen.size() != tr.length())
                    continue;
                fights++;
                double[] pc = pace.computeIfAbsent(j.getString("species"), x -> new double[3]);
                pc[0] += acted[0];
                pc[1] += j.optInt("foe_acts", -1);
                pc[2]++;
                for(int k = 0; k < Math.min(N, tr.length()); k++) {
                    JSONObject t = tr.getJSONObject(k);
                    JSONArray lo = t.getJSONArray("open");
                    double[] mo = seenOpen.get(k);
                    for(int c = 0; c < 4; c++) {
                        mOpen[k] += mo[c];
                        lOpen[k] += lo.optDouble(c, 0);
                        mCol[k][c] += mo[c];
                        lCol[k][c] += lo.optDouble(c, 0);
                    }
                    JSONArray lm = t.optJSONArray("mine");
                    double[] mm = seenMine.get(k);
                    for(int c = 0; c < 4; c++) {
                        mMine[k] += mm[c];
                        lMine[k] += (lm == null) ? 0 : lm.optDouble(c, 0);
                    }
                    mDealt[k] += (k < dealt.size()) ? dealt.get(k) : 0;
                    lDealt[k] += t.optDouble("shp", 0);
                    n[k]++;
                }
            }
        }
        System.out.println(fights + " fights walked");
        for(java.util.Map.Entry<String, double[]> e : pace.entrySet()) {
            double[] pc = e.getValue();
            if(pc[2] >= 3)
                System.out.println(String.format("  pace %-14s fights %3.0f  creature actions model %6.0f  log %6.0f  log/model %.2f",
                                                 e.getKey(), pc[2], pc[0], pc[1], pc[1] / Math.max(1, pc[0])));
        }
        System.out.println(" card   n   open model/log (g b y r, means)                     dealt model/log   OURS model/log");
        for(int k = 0; k < N; k++) {
            if(n[k] == 0)
                continue;
            StringBuilder col = new StringBuilder();
            for(int c = 0; c < 4; c++)
                col.append(String.format(" %3.0f/%-3.0f", mCol[k][c] / n[k], lCol[k][c] / n[k]));
            System.out.println(String.format(" %4d %3d  %5.1f/%-5.1f %s   %6.1f/%-6.1f   %5.1f/%-5.1f", k + 1, n[k],
                                             mOpen[k] / n[k], lOpen[k] / n[k], col, mDealt[k] / n[k],
                                             lDealt[k] / n[k], mMine[k] / n[k], lMine[k] / n[k]));
        }
    }
}
