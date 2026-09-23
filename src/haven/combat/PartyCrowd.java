package haven.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plans a fight for SEVERAL of us against SEVERAL creatures - a party clearing a wolf pack, or
 * the bat dungeon's denmother with her bats (COMBAT.md §3.12).
 *
 * WHY A THIRD PLANNER. Optimizer holds one of us against a crowd and PartyPlanner a party against
 * one creature; each is shaped by its one side throughout, and the two shapes do not merge by
 * adding a loop. 114 party-against-crowd fights in the pool can be rebuilt from the members' own
 * logs - 27 wolf packs against three of us, 14 bat-dungeon rooms against four - and neither
 * planner can be asked about any of them. This is opt-in and runs from tools/StrategyVsPlayer
 * only; nothing on a default path calls it.
 *
 * What it keeps from the other two, each for the reason given there:
 *
 *   one set of openings per creature, which every one of us builds on (PartyPlanner);
 *   one victim per creature card - each creature swings at the member it is on (its aggro,
 *     from the log), then at the next one standing once they fall (PartyPlanner.target);
 *   our clocks, so the search branches on whoever of us is due next (PartyPlanner), and our
 *     initiative per member AND per creature, since it is held against one opponent and not
 *     another (Optimizer, per relation);
 *   the creatures' own clocks, the earliest acting first, each joining at the tick it arrived
 *     and never before (Optimizer.search's arrive);
 *   a sweeping card reaching Formulas.sweepReach of the bystanders, whole swings first and the
 *     fraction on the last (Optimizer.step).
 *
 * What it adds is a TARGET per card: each branch is one card at one creature. The branching is
 * capped at TARGETS creatures per member - the one it hit last, whoever is swinging at it, and
 * the weakest standing - so a room of eight bats does not multiply the beam by eight.
 */
public final class PartyCrowd {
    private PartyCrowd() {
    }

    /* How many creatures one member's next card is tried against. */
    static final int TARGETS = 3;

    /** One card thrown by one of us at one creature. */
    public static final class Step {
        public final int who, at;
        public final Move move;
        public final long tick;

        Step(int who, int at, Move move, long tick) {
            this.who = who;
            this.at = at;
            this.move = move;
            this.tick = tick;
        }
    }

    /** A finished line. */
    public static final class Plan {
        public final List<Step> steps;
        public final long ticks;
        public final double[] hpLost, soaked;
        public final double totalLost;
        /** Every creature dead. */
        public final boolean killed;
        /** Hitpoints the creatures had left between them, 0 on a clear. */
        public final double foeHp;
        public final boolean[] down;

        Plan(List<Step> steps, long ticks, double[] hpLost, double[] soaked, boolean killed,
             double foeHp, boolean[] down) {
            this.steps = steps;
            this.ticks = ticks;
            this.hpLost = hpLost;
            this.soaked = soaked;
            double t = 0;
            for(double h : hpLost)
                t += h;
            this.totalLost = t;
            this.killed = killed;
            this.foeHp = foeHp;
            this.down = down;
        }
    }

    private static final class Node {
        final Combatant[] us, foes;
        final List<Step> path;
        final long tick;
        final long[] foeNext;
        final int[] foeActs;
        final int[][] foeThrown;
        /** Our initiative, [member][creature]. */
        final int[][] ip;
        final double[] hpLost;
        /** The creature each of us last threw at, or -1. */
        final int[] last;

        Node(Combatant[] us, Combatant[] foes, List<Step> path, long tick, long[] foeNext,
             int[] foeActs, int[][] foeThrown, int[][] ip, double[] hpLost, int[] last) {
            this.us = us;
            this.foes = foes;
            this.path = path;
            this.tick = tick;
            this.foeNext = foeNext;
            this.foeActs = foeActs;
            this.foeThrown = foeThrown;
            this.ip = ip;
            this.hpLost = hpLost;
            this.last = last;
        }

        double lost() {
            double t = 0;
            for(double h : hpLost)
                t += h;
            return(t);
        }

        double foeHp() {
            double t = 0;
            for(Combatant f : foes)
                t += Math.max(0, f.hp);
            return(t);
        }

        boolean cleared() {
            for(Combatant f : foes) {
                if(f.alive())
                    return(false);
            }
            return(true);
        }

        boolean anyStanding() {
            for(Combatant c : us) {
                if(c.alive())
                    return(true);
            }
            return(false);
        }

        Plan plan() {
            boolean[] down = new boolean[us.length];
            double[] soaked = new double[us.length];
            for(int i = 0; i < us.length; i++) {
                down[i] = !us[i].alive();
                soaked[i] = us[i].soaked;
            }
            return(new Plan(path, tick, hpLost.clone(), soaked, cleared(), foeHp(), down));
        }
    }

    /** The fight as it stands before anyone acts. */
    private static Node root(PartyPlanner.Member[] party, Combatant[] foes, FoeModel[] models,
                             long[] arrive, long[] start) {
        Combatant[] us = new Combatant[party.length];
        int[][] ip = new int[party.length][foes.length];
        for(int i = 0; i < party.length; i++) {
            us[i] = party[i].fighter.copy();
            us[i].soaked = 0;
            if((start != null) && (i < start.length))
                us[i].readyAt = Math.max(us[i].readyAt, start[i]);
            for(int f = 0; f < foes.length; f++)
                ip[i][f] = (f == 0) ? us[i].ip : 0;
        }
        Combatant[] fs = new Combatant[foes.length];
        long[] next = new long[foes.length];
        int[][] thrown = new int[foes.length][];
        for(int f = 0; f < foes.length; f++) {
            fs[f] = foes[f].copy();
            long a = ((arrive != null) && (f < arrive.length)) ? arrive[f] : 0;
            FoeModel m = models[f];
            long first = Optimizer.firstAct(m, fs[f]);
            next[f] = (first == Long.MAX_VALUE) ? Long.MAX_VALUE : (a + first);
            thrown[f] = new int[((m.cards == null) || !m.cards.usable()) ? 0 : m.cards.tallySize()];
        }
        int[] last = new int[party.length];
        java.util.Arrays.fill(last, -1);
        return(new Node(us, fs, new ArrayList<Step>(), 0, next, new int[foes.length], thrown, ip,
                        new double[party.length], last));
    }

    /**
     * The plans on the frontier.
     *
     * @param aggro  the member each creature is on when it arrives
     * @param arrive the tick each creature joins; before it, it neither acts nor can be hit
     */
    public static List<Plan> search(PartyPlanner.Member[] party, Combatant[] foes, FoeModel[] models,
                                    int[] aggro, long[] arrive, int beam, long maxTicks) {
        return(search(party, foes, models, aggro, arrive, beam, maxTicks, null, null, null));
    }

    /**
     * The same, also offering a party's own logged lines ({@link #follow}'s lines, targets and
     * starts) beside the searched ones, so the frontier holds a plan at least as good as what the
     * party showed - see Optimizer.search with lines. Null offers nothing.
     */
    public static List<Plan> search(PartyPlanner.Member[] party, Combatant[] foes, FoeModel[] models,
                                    int[] aggro, long[] arrive, int beam, long maxTicks,
                                    List<List<Move>> lines, int[][] lineAt, long[] start) {
        Node r = root(party, foes, models, arrive, null);
        double hp0 = r.foeHp();
        List<Node> live = new ArrayList<Node>();
        live.add(r);
        List<Plan> done = new ArrayList<Plan>();
        while(!live.isEmpty()) {
            List<Node> next = new ArrayList<Node>();
            for(Node n : live) {
                int who = due(n);
                if(who < 0) {
                    done.add(n.plan());
                    continue;
                }
                long ready = Math.max(n.tick, n.us[who].readyAt);
                int[] ts = targets(n, who, aggro, arrive, ready);
                if(ts.length == 0) {
                    /* Nobody standing has arrived yet: wait for the next one rather than end. */
                    Node w = waitFor(n, who, arrive);
                    if(w == null)
                        done.add(n.plan());
                    else
                        next.add(w);
                    continue;
                }
                for(int at : ts) {
                    for(Move m : party[who].deck) {
                        Node s = step(n, who, at, m, party, models, aggro, arrive, maxTicks);
                        if(s == null)
                            continue;
                        if(s.cleared() || !s.anyStanding() || (s.tick >= maxTicks))
                            done.add(s.plan());
                        else
                            next.add(s);
                    }
                }
            }
            if(next.isEmpty())
                break;
            live = prune(next, hp0, beam);
        }
        done.addAll(seeds(party, foes, models, aggro, arrive, maxTicks));
        if(lines != null)
            done.add(follow(party, foes, models, aggro, arrive, lines, lineAt, start, maxTicks, null));
        return(frontier(done));
    }

    /**
     * The lines players throw, offered beside the searched ones: every member spamming their
     * quickest card, and the rhythm - quickest k times, then each damaging card as the finisher
     * (see Optimizer.seeds) - all at one creature at a time, the first standing in kill order.
     */
    private static List<Plan> seeds(PartyPlanner.Member[] party, Combatant[] foes, FoeModel[] models,
                                    int[] aggro, long[] arrive, long maxTicks) {
        List<Plan> out = new ArrayList<Plan>();
        Move[] quick = new Move[party.length];
        java.util.LinkedHashSet<String> fins = new java.util.LinkedHashSet<String>();
        for(int i = 0; i < party.length; i++) {
            for(Move mv : party[i].deck) {
                if(mv.stance || !mv.deals())
                    continue;
                if((quick[i] == null) || (mv.cooldownBase < quick[i].cooldownBase))
                    quick[i] = mv;
            }
            if(quick[i] == null)
                return(out);
        }
        for(int i = 0; i < party.length; i++) {
            for(Move mv : party[i].deck) {
                if(!mv.stance && mv.deals() && (mv != quick[i]))
                    fins.add(mv.name);
            }
        }
        List<List<Move>> spam = new ArrayList<List<Move>>();
        for(int i = 0; i < party.length; i++)
            spam.add(Optimizer.rhythm(quick[i], quick[i], Integer.MAX_VALUE));
        out.add(follow(party, foes, models, aggro, arrive, spam, null, null, maxTicks, null));
        for(String fin : fins) {
            for(int k = 1; k <= Optimizer.SEED_RHYTHM; k++) {
                List<List<Move>> lines = new ArrayList<List<Move>>();
                for(int i = 0; i < party.length; i++) {
                    Move with = quick[i];
                    for(Move mv : party[i].deck) {
                        if(fin.equals(mv.name))
                            with = mv;
                    }
                    lines.add(Optimizer.rhythm(quick[i], with, k));
                }
                out.add(follow(party, foes, models, aggro, arrive, lines, null, null, maxTicks, null));
            }
        }
        return(out);
    }

    /**
     * Where the party's logged lines end up: each member's cards in order, each at the creature the
     * log names ({@code at[i][k]}, an index into foes, or -1), thrown as soon as that member may,
     * joining at {@code start[i]}. A card whose creature is down or not yet there goes at the first
     * one standing that has arrived; a card the model refuses is skipped and counted.
     */
    public static Plan follow(PartyPlanner.Member[] party, Combatant[] foes, FoeModel[] models,
                              int[] aggro, long[] arrive, List<List<Move>> lines, int[][] at,
                              long[] start, long maxTicks, int[] skipped) {
        Node n = root(party, foes, models, arrive, start);
        int[] k = new int[party.length];
        while(true) {
            int who = -1;
            for(int i = 0; i < n.us.length; i++) {
                if(!n.us[i].alive() || (k[i] >= lines.get(i).size()))
                    continue;
                if((who < 0) || (Math.max(n.tick, n.us[i].readyAt) < Math.max(n.tick, n.us[who].readyAt)))
                    who = i;
            }
            if(who < 0)
                return(n.plan());
            long ready = Math.max(n.tick, n.us[who].readyAt);
            int want = ((at != null) && (who < at.length) && (k[who] < at[who].length)) ? at[who][k[who]] : -1;
            int aim = aimable(n, want, arrive, ready) ? want : firstStanding(n, arrive, ready);
            if(aim < 0) {
                Node w = waitFor(n, who, arrive);
                if(w == null)
                    return(n.plan());
                n = w;
                continue;
            }
            Move m = lines.get(who).get(k[who]++);
            Node s = step(n, who, aim, m, party, models, aggro, arrive, maxTicks);
            if(s == null) {
                if(skipped != null)
                    skipped[0]++;
                continue;
            }
            n = s;
            if(n.cleared() || !n.anyStanding() || (n.tick >= maxTicks))
                return(n.plan());
        }
    }

    private static boolean arrived(long[] arrive, int f, long t) {
        return((arrive == null) || (f >= arrive.length) || (arrive[f] <= t));
    }

    private static boolean aimable(Node n, int f, long[] arrive, long t) {
        return((f >= 0) && (f < n.foes.length) && n.foes[f].alive() && arrived(arrive, f, t));
    }

    private static int firstStanding(Node n, long[] arrive, long t) {
        for(int f = 0; f < n.foes.length; f++) {
            if(aimable(n, f, arrive, t))
                return(f);
        }
        return(-1);
    }

    /** The member moved on to the next arrival, or null when no creature is left to come. */
    private static Node waitFor(Node n, int who, long[] arrive) {
        long soonest = Long.MAX_VALUE;
        for(int f = 0; (arrive != null) && (f < n.foes.length); f++) {
            if(n.foes[f].alive() && (f < arrive.length) && (arrive[f] > n.tick))
                soonest = Math.min(soonest, arrive[f]);
        }
        if(soonest == Long.MAX_VALUE)
            return(null);
        Combatant[] us = new Combatant[n.us.length];
        for(int i = 0; i < us.length; i++)
            us[i] = n.us[i].copy();
        us[who].readyAt = Math.max(us[who].readyAt, soonest);
        return(new Node(us, n.foes, n.path, n.tick, n.foeNext, n.foeActs, n.foeThrown, n.ip, n.hpLost, n.last));
    }

    /** The creatures this member's next card is tried against - see TARGETS. */
    private static int[] targets(Node n, int who, int[] aggro, long[] arrive, long t) {
        List<Integer> out = new ArrayList<Integer>();
        if(aimable(n, n.last[who], arrive, t))
            out.add(n.last[who]);
        for(int f = 0; (f < n.foes.length) && (out.size() < TARGETS); f++) {
            if(aimable(n, f, arrive, t) && !out.contains(f) && (victim(n.us, aggro, f) == who))
                out.add(f);
        }
        List<Integer> rest = new ArrayList<Integer>();
        for(int f = 0; f < n.foes.length; f++) {
            if(aimable(n, f, arrive, t) && !out.contains(f))
                rest.add(f);
        }
        Collections.sort(rest, (a, b) -> Double.compare(n.foes[a].hp, n.foes[b].hp));
        for(int f : rest) {
            if(out.size() >= TARGETS)
                break;
            out.add(f);
        }
        int[] r = new int[out.size()];
        for(int i = 0; i < r.length; i++)
            r[i] = out.get(i);
        return(r);
    }

    /** Whoever of us is ready soonest, lowest index on a tie; -1 when nobody stands. */
    private static int due(Node n) {
        int who = -1;
        for(int i = 0; i < n.us.length; i++) {
            if(!n.us[i].alive())
                continue;
            if((who < 0) || (Math.max(n.tick, n.us[i].readyAt) < Math.max(n.tick, n.us[who].readyAt)))
                who = i;
        }
        return(who);
    }

    /** The member a creature swings at: its aggro while they stand, then the next one standing. */
    private static int victim(Combatant[] us, int[] aggro, int f) {
        int front = ((aggro != null) && (f < aggro.length) && (aggro[f] >= 0)) ? aggro[f] : 0;
        for(int k = 0; k < us.length; k++) {
            int i = (front + k) % us.length;
            if(us[i].alive())
                return(i);
        }
        return(-1);
    }

    private static Node step(Node n, int who, int at, Move m, PartyPlanner.Member[] party,
                             FoeModel[] models, int[] aggro, long[] arrive, long maxTicks) {
        Combatant[] us = new Combatant[n.us.length];
        for(int i = 0; i < us.length; i++)
            us[i] = n.us[i].copy();
        Combatant[] foes = new Combatant[n.foes.length];
        for(int f = 0; f < foes.length; f++)
            foes[f] = n.foes[f].copy();
        int[][] ip = new int[n.ip.length][];
        for(int i = 0; i < ip.length; i++)
            ip[i] = n.ip[i].clone();
        int[][] thrown = new int[n.foeThrown.length][];
        for(int f = 0; f < thrown.length; f++)
            thrown[f] = n.foeThrown[f].clone();
        long[] foeNext = n.foeNext.clone();
        int[] acts = n.foeActs.clone();
        double[] lost = n.hpLost.clone();
        long ready = Math.max(n.tick, us[who].readyAt);

        /* The creatures act on their own clocks while we wait, earliest first. */
        double[] weights = new double[us.length];
        long[] gap = new long[1];
        long clock = n.tick, lastAct = n.tick;
        while(us[who].alive()) {
            int f = -1;
            for(int g = 0; g < foes.length; g++) {
                if(foes[g].alive() && ((f < 0) || (foeNext[g] < foeNext[f])))
                    f = g;
            }
            if((f < 0) || (foeNext[f] > ready) || (foeNext[f] >= maxTicks))
                break;
            int t = victim(us, aggro, f);
            if(t < 0)
                break;
            fade(us, foes, foeNext[f] - clock);
            clock = Math.max(clock, foeNext[f]);
            for(int i = 0; i < us.length; i++)
                weights[i] = us[i].defenceWeight();
            double[] before = new double[us.length];
            for(int i = 0; i < us.length; i++)
                before[i] = us[i].hp;
            us[t].ip = ip[t][f];
            models[f].actParty(us, weights, t, null, foes[f], acts[f], thrown[f], gap, null);
            ip[t][f] = us[t].ip;
            for(int i = 0; i < us.length; i++)
                lost[i] += Math.max(0, before[i] - us[i].hp);
            acts[f]++;
            if((party[t].trigger != null) && us[t].armed())
                Sim.trigger(foes[f], party[t].trigger);
            lastAct = Math.max(lastAct, foeNext[f]);
            foeNext[f] += Math.max(1, gap[0]);
        }
        if(ready > maxTicks)
            return(null);
        fade(us, foes, ready - clock);
        if(!us[who].alive() || !foes[at].alive())
            return(new Node(us, foes, n.path, Math.max(n.tick, lastAct), foeNext, acts, thrown, ip, lost, n.last));

        Sim sim = new Sim(us[who], foes[at]);
        sim.advanceTo(ready);
        us[who].ip = ip[who][at];
        Sim.Result r = sim.use(us[who], m);
        if(!r.ok)
            return(null);
        ip[who][at] = us[who].ip;
        /* The bystanders a sweep reaches, as Optimizer.step prices it without positions. */
        if(m.splashes()) {
            int others = 0;
            for(int g = 0; g < foes.length; g++) {
                if((g != at) && foes[g].alive() && arrived(arrive, g, ready))
                    others++;
            }
            double expect = Math.min(Formulas.sweepReach(others), (double)(m.targets - 1));
            int whole = (int)Math.floor(expect);
            double part = expect - whole;
            int idx = 1;
            for(int g = 0; (g < foes.length) && (idx <= whole + ((part > 0) ? 1 : 0)); g++) {
                if((g == at) || !foes[g].alive() || !arrived(arrive, g, ready))
                    continue;
                sim.splash(us[who], m, foes[g], idx, (idx <= whole) ? 1.0 : part);
                idx++;
            }
        }
        int[] last = n.last.clone();
        last[who] = at;
        List<Step> path = new ArrayList<Step>(n.path);
        path.add(new Step(who, at, m, ready));
        return(new Node(us, foes, path, ready, foeNext, acts, thrown, ip, lost, last));
    }

    private static void fade(Combatant[] us, Combatant[] foes, long ticks) {
        if(ticks <= 0)
            return;
        for(Combatant c : us)
            c.decay(ticks);
        for(Combatant f : foes)
            f.decay(ticks);
    }

    /** PartyPlanner.prune's three ends: damage rate, hitpoints kept, openings standing. */
    private static List<Node> prune(List<Node> next, double hp0, int beam) {
        List<Node> byRate = new ArrayList<Node>(next);
        Collections.sort(byRate, (a, b) -> Double.compare((hp0 - b.foeHp()) / Math.max(1, b.tick),
                                                          (hp0 - a.foeHp()) / Math.max(1, a.tick)));
        List<Node> byHp = new ArrayList<Node>(next);
        Collections.sort(byHp, (a, b) -> {
            int c = Double.compare(a.lost(), b.lost());
            return((c != 0) ? c : Double.compare(a.foeHp(), b.foeHp()));
        });
        List<Node> bySetup = new ArrayList<Node>(next);
        Collections.sort(bySetup, (a, b) -> {
            int c = Double.compare(open(b), open(a));
            return((c != 0) ? c : Double.compare(a.foeHp(), b.foeHp()));
        });
        int third = Math.max(1, beam / 3);
        List<Node> out = new ArrayList<Node>();
        for(List<Node> end : java.util.Arrays.asList(byRate, byHp, bySetup)) {
            for(int i = 0; (i < third) && (i < end.size()); i++) {
                Node x = end.get(i);
                if(!out.contains(x))
                    out.add(x);
            }
        }
        return(out);
    }

    /* The most open creature standing: the setup a finisher would cash. */
    private static double open(Node n) {
        double best = 0;
        for(Combatant f : n.foes) {
            if(!f.alive())
                continue;
            double[] all = new double[4];
            for(int c = 0; c < 4; c++)
                all[c] = f.opening(c);
            best = Math.max(best, Formulas.combined(all));
        }
        return(best);
    }

    /** Clears beaten on neither ticks nor the party's total hitpoints; the closest tries if none clear. */
    private static double sum(double[] v) {
        double t = 0;
        for(double x : v)
            t += x;
        return(t);
    }

    static List<Plan> frontier(List<Plan> all) {
        List<Plan> kills = new ArrayList<Plan>();
        for(Plan p : all) {
            if(p.killed)
                kills.add(p);
        }
        if(kills.isEmpty()) {
            List<Plan> sorted = new ArrayList<Plan>(all);
            Collections.sort(sorted, (a, b) -> Double.compare(a.foeHp, b.foeHp));
            return(sorted.subList(0, Math.min(3, sorted.size())));
        }
        List<Plan> out = new ArrayList<Plan>();
        for(Plan p : kills) {
            boolean dominated = false;
            for(Plan q : kills) {
                /* Optimizer.dominates, the one rule: a party's own line leaves only to a plan at
                 * least as good on time, cost AND wear. */
                if((q != p) && Optimizer.dominates(q.ticks, Optimizer.cost(q.totalLost, sum(q.soaked)), 0,
                                                   sum(q.soaked), p.ticks,
                                                   Optimizer.cost(p.totalLost, sum(p.soaked)), 0, sum(p.soaked))) {
                    dominated = true;
                    break;
                }
            }
            boolean seen = false;
            for(Plan q : out) {
                if((q.ticks == p.ticks) && (Math.abs(q.totalLost - p.totalLost) < 1e-9)
                   && (Math.abs(sum(q.soaked) - sum(p.soaked)) < 1e-9))
                    seen = true;
            }
            if(!dominated && !seen)
                out.add(p);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.ticks, b.ticks));
        return(out);
    }
}
