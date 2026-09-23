package haven.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Plans a fight for SEVERAL of us against ONE creature - a party taking down a mammoth.
 *
 * WHY IT IS NOT {@link Optimizer}. That search holds one of us against any number of
 * opponents, and every piece of its state is shaped that way: one fighter, one clock of ours,
 * one set of openings on us. A party breaks each of those at once, and bolting allies onto it
 * would have touched every line the live advice depends on. This is opt-in and runs from
 * tools/CombatPartySearch only; nothing on a default path calls it.
 *
 * WHAT A PARTY CHANGES, and each one is measured or stated rather than assumed:
 *
 *   one set of openings on the creature. James and Shade's six 2v1 cave angler fights on
 *     2026-09-15 read the SAME peaks from both clients - g57 b0 y59 r64 on each side - so
 *     openings are the creature's, not per relation. Two of us opening two colours are
 *     building one state, and damage goes as its square.
 *   one card, one victim. The creature swings at whoever is in front of it (James: animals
 *     attack whoever is closest or in front, so a party can choose who that is). Across
 *     about a thousand hits in group fights every card landed on one person; a card is only
 *     spread when the caller names it as an area card.
 *   our own clocks. Each of us acts when our own cooldown ends, so the search branches on
 *     ONE person's deck at a time - whoever is due next - rather than on every combination
 *     of four decks, which would be the deck size to the fourth power per step.
 *   our own initiative against it, carried per person, the way Optimizer carries it per
 *     opponent. The creature's own initiative is one number; the corpus keeps it per
 *     relation and this does not, which is a simplification stated here rather than hidden.
 *
 * The beam and the frontier are Optimizer's, for the same reasons given there: three ways of
 * ranking a partial line (rate, hitpoints kept, openings standing) so that no end of the
 * frontier is pruned before it pays, and plans kept that nothing beats on every axis at once.
 */
public final class PartyPlanner {
    private PartyPlanner() {
    }

    /** One of us: the fighter as the stance makes them, and the cards they throw. */
    public static final class Member {
        public final String name;
        public final Combatant fighter;
        public final List<Move> deck;
        /** What their stance or deck opens when the creature swings at them - Parry. */
        final double[] trigger;

        public Member(String name, Combatant fighter, List<Move> deck) {
            this.name = name;
            this.fighter = fighter;
            this.deck = deck;
            double[] t = new double[4];
            boolean any = false;
            for(Move m : deck) {
                for(int c = 0; c < 4; c++) {
                    if(m.whenAttackedOpens[c] > 0) {
                        t[c] += m.whenAttackedOpens[c];
                        any = true;
                    }
                }
            }
            if(!any) {
                for(int c = 0; c < 4; c++) {
                    if(fighter.whenAttacked[c] > 0) {
                        t[c] += fighter.whenAttacked[c];
                        any = true;
                    }
                }
            }
            this.trigger = any ? t : null;
        }
    }

    /** One card thrown by one of us. */
    public static final class Step {
        public final int who;
        public final Move move;
        public final long tick;

        Step(int who, Move move, long tick) {
            this.who = who;
            this.move = move;
            this.tick = tick;
        }
    }

    /** A finished line. */
    public static final class Plan {
        public final List<Step> steps;
        public final long ticks;
        /** Soft hitpoints each of us lost, indexed like the party. */
        public final double[] hpLost;
        public final double totalLost;
        public final boolean killed;
        /** Hitpoints the creature had left, 0 on a kill. */
        public final double foeHp;
        /** Whoever went down, indexed like the party. */
        public final boolean[] down;
        /** What each of us had soaked by armour along the plan - see Combatant.soaked. */
        public final double[] soaked;

        Plan(List<Step> steps, long ticks, double[] hpLost, boolean killed, double foeHp,
             boolean[] down) {
            this(steps, ticks, hpLost, killed, foeHp, down, new double[hpLost.length]);
        }

        Plan(List<Step> steps, long ticks, double[] hpLost, boolean killed, double foeHp,
             boolean[] down, double[] soaked) {
            this.soaked = soaked;
            this.steps = steps;
            this.ticks = ticks;
            this.hpLost = hpLost;
            double t = 0;
            for(double h : hpLost)
                t += h;
            this.totalLost = t;
            this.killed = killed;
            this.foeHp = foeHp;
            this.down = down;
        }

        /** Cards thrown by one member, in order. */
        public List<Move> movesOf(int who) {
            List<Move> out = new ArrayList<Move>();
            for(Step s : steps) {
                if(s.who == who)
                    out.add(s.move);
            }
            return(out);
        }
    }

    private static final class Node {
        final Combatant[] us;
        final Combatant foe;
        final List<Step> path;
        final long tick;
        final long foeNext;
        final int foeActs;
        final int[] foeThrown;
        final int[] ip;
        final double[] hpLost;

        Node(Combatant[] us, Combatant foe, List<Step> path, long tick, long foeNext,
             int foeActs, int[] foeThrown, int[] ip, double[] hpLost) {
            this.us = us;
            this.foe = foe;
            this.path = path;
            this.tick = tick;
            this.foeNext = foeNext;
            this.foeActs = foeActs;
            this.foeThrown = foeThrown;
            this.ip = ip;
            this.hpLost = hpLost;
        }

        double lost() {
            double t = 0;
            for(double h : hpLost)
                t += h;
            return(t);
        }

        boolean anyStanding() {
            for(Combatant c : us) {
                if(c.alive())
                    return(true);
            }
            return(false);
        }

        Plan plan(boolean killed) {
            boolean[] down = new boolean[us.length];
            double[] soaked = new double[us.length];
            for(int i = 0; i < us.length; i++) {
                down[i] = !us[i].alive();
                soaked[i] = us[i].soaked;
            }
            return(new Plan(path, tick, hpLost.clone(), killed, Math.max(0, foe.hp), down, soaked));
        }
    }

    /**
     * The plans on the frontier for this party against this creature.
     *
     * @param front who the creature attacks while they stand; the next one standing after them
     *              once they fall, since whoever is left is now the closest
     * @param area  names of the creature's cards that land on everyone standing, or null
     */
    public static List<Plan> search(Member[] party, Combatant foe, FoeModel model, int front,
                                    Set<String> area, int beam, long maxTicks) {
        return(search(party, foe, model, front, area, beam, maxTicks, null, null));
    }

    /**
     * The same, also offering a party's own logged lines - each member's queue, joining at
     * {@code start[i]} - stepped by {@link #follow} beside the searched ones. The frontier then keeps
     * a plan at least as fast and as cheap as that line, so the planner never answers worse than a
     * party has already shown it (see Optimizer.search with lines). Null offers nothing.
     */
    public static List<Plan> search(Member[] party, Combatant foe, FoeModel model, int front,
                                    Set<String> area, int beam, long maxTicks,
                                    List<List<Move>> lines, long[] start) {
        Combatant[] us0 = new Combatant[party.length];
        int[] ip0 = new int[party.length];
        for(int i = 0; i < party.length; i++) {
            us0[i] = party[i].fighter.copy();
            us0[i].soaked = 0;
            ip0[i] = us0[i].ip;
        }
        int cards = ((model.cards == null) || !model.cards.usable()) ? 0 : model.cards.tallySize();
        long next0 = Optimizer.firstAct(model, foe);
        Combatant f0 = foe.copy();
        double foeHp0 = f0.hp;
        List<Node> live = new ArrayList<Node>();
        live.add(new Node(us0, f0, new ArrayList<Step>(), 0, next0, 0, new int[cards], ip0,
                          new double[party.length]));
        List<Plan> done = new ArrayList<Plan>();
        while(!live.isEmpty()) {
            List<Node> next = new ArrayList<Node>();
            for(Node n : live) {
                int who = due(n);
                if(who < 0) {
                    done.add(n.plan(false));
                    continue;
                }
                for(Move m : party[who].deck) {
                    Node s = step(n, who, m, party, model, front, area, maxTicks);
                    if(s == null)
                        continue;
                    if(!s.foe.alive())
                        done.add(s.plan(true));
                    else if(!s.anyStanding() || (s.tick >= maxTicks))
                        done.add(s.plan(false));
                    else
                        next.add(s);
                }
            }
            if(next.isEmpty())
                break;
            live = prune(next, foeHp0, beam);
        }
        done.addAll(seeds(party, foe, model, front, area, maxTicks));
        if(lines != null) {
            Plan p = follow(party, foe, model, front, area, lines, start, maxTicks, null);
            if(p.killed)
                done.add(p);
        }
        return(frontier(done));
    }

    /**
     * Every combination of each member throwing one damaging card again and again - the lines the
     * parties actually used - stepped by {@link #follow} and handed to the frontier. See
     * Optimizer.seeds for why the beam misses them: against 2026-09-19's polar bears a
     * Quick-Barrage party killed in 240-280 ticks where the searched Full Circle mixes took
     * 264-352 and cost more hitpoints and armour. At most SEED_CARDS cards per member, so a party
     * of four is at most 3^4 = 81 short walks.
     */
    private static List<Plan> seeds(Member[] party, Combatant foe, FoeModel model, int front,
                                    Set<String> area, long maxTicks) {
        List<List<Move>> per = new ArrayList<List<Move>>();
        for(Member m : party) {
            List<Move> cs = new ArrayList<Move>();
            for(Move mv : m.deck) {
                if(!mv.stance && mv.deals())
                    cs.add(mv);
            }
            /* Spam is a short-cooldown strategy: the quickest cards are the ones worth repeating. */
            Collections.sort(cs, (x, y) -> Double.compare(x.cooldownBase, y.cooldownBase));
            if(cs.size() > SEED_CARDS)
                cs = new ArrayList<Move>(cs.subList(0, SEED_CARDS));
            if(cs.isEmpty())
                return(new ArrayList<Plan>());
            per.add(cs);
        }
        List<Plan> out = new ArrayList<Plan>();
        int[] pick = new int[party.length];
        while(true) {
            List<List<Move>> lines = new ArrayList<List<Move>>();
            for(int i = 0; i < party.length; i++) {
                List<Move> line = new ArrayList<Move>(SEED_LEN);
                for(int k = 0; k < SEED_LEN; k++)
                    line.add(per.get(i).get(pick[i]));
                lines.add(line);
            }
            Plan p = follow(party, foe, model, front, area, lines, null, maxTicks, null);
            if(!p.steps.isEmpty())
                out.add(p);
            int i = 0;
            while((i < party.length) && (++pick[i] >= per.get(i).size())) {
                pick[i] = 0;
                i++;
            }
            if(i >= party.length)
                break;
        }
        /* THE RHYTHM - each member's quickest card k times then their heaviest, the same k for all
         * (see Optimizer.seeds). This is what the parties that beat the planner on polar bears threw. */
        Move[] quick = new Move[party.length], heavy = new Move[party.length];
        java.util.LinkedHashSet<String> finishers = new java.util.LinkedHashSet<String>();
        for(int i = 0; i < party.length; i++) {
            for(Move mv : party[i].deck) {
                if(mv.stance || !mv.deals())
                    continue;
                if((quick[i] == null) || (mv.cooldownBase < quick[i].cooldownBase))
                    quick[i] = mv;
                if((heavy[i] == null) || (mv.damageShare > heavy[i].damageShare))
                    heavy[i] = mv;
            }
        }
        /* Every damaging card anyone holds is tried as the finisher, for everyone holding it -
         * see Optimizer.seeds for why the heaviest alone was the wrong one. A member without that
         * card finishes with their heaviest. */
        for(int i = 0; i < party.length; i++) {
            for(Move mv : party[i].deck) {
                if(!mv.stance && mv.deals() && (mv != quick[i]))
                    finishers.add(mv.name);
            }
        }
        for(String fin : finishers) {
            Move[] with = new Move[party.length];
            for(int i = 0; i < party.length; i++) {
                with[i] = heavy[i];
                for(Move mv : party[i].deck) {
                    if(fin.equals(mv.name) && (mv != quick[i]))
                        with[i] = mv;
                }
            }
            for(int k = 1; k <= Optimizer.SEED_RHYTHM; k++) {
                /* In step, and STAGGERED - member i building k + i before finishing. The parties
                 * of two that still beat the planner on polar bears (2026-09-22) finished one at a
                 * time: a second finisher on the same tick spends its long cooldown on openings the
                 * first has just cashed. */
                for(int stagger = 0; stagger <= ((party.length > 1) ? 1 : 0); stagger++) {
                    /* Both shapes - the rhythm, and build-then-burst (Optimizer.burst). */
                    for(int shape = 0; shape < 2; shape++) {
                        List<List<Move>> lines = new ArrayList<List<Move>>();
                        for(int i = 0; i < party.length; i++) {
                            int ki = k + stagger * i;
                            lines.add((shape == 0) ? Optimizer.rhythm(quick[i], with[i], ki)
                                      : Optimizer.burst(quick[i], with[i], ki + stagger * i * 2));
                        }
                        Plan p = follow(party, foe, model, front, area, lines, null, maxTicks, null);
                        if(!p.steps.isEmpty())
                            out.add(p);
                    }
                }
            }
        }
        return(out);
    }

    private static final int SEED_CARDS = 3, SEED_LEN = 400;

    /**
     * Where the party's LOGGED lines end up - each member's own cards, in their own order, each
     * thrown as soon as that member's clock allows, against the same creature and the same step
     * the search uses (COMBAT.md §3.10). A member joins at {@code start[i]} ticks, since a party
     * does not all begin on the first blow; a card the model refuses is skipped and counted in
     * {@code skipped[0]}; a member whose line is spent throws nothing more. It stops at the kill,
     * when nobody stands, at {@code maxTicks}, or when every line is spent.
     */
    public static Plan follow(Member[] party, Combatant foe, FoeModel model, int front,
                              Set<String> area, List<List<Move>> lines, long[] start,
                              long maxTicks, int[] skipped) {
        Combatant[] us0 = new Combatant[party.length];
        int[] ip0 = new int[party.length];
        for(int i = 0; i < party.length; i++) {
            us0[i] = party[i].fighter.copy();
            us0[i].soaked = 0;
            if((start != null) && (i < start.length))
                us0[i].readyAt = Math.max(us0[i].readyAt, start[i]);
            ip0[i] = us0[i].ip;
        }
        int cards = ((model.cards == null) || !model.cards.usable()) ? 0 : model.cards.tallySize();
        long next0 = Optimizer.firstAct(model, foe);
        Node n = new Node(us0, foe.copy(), new ArrayList<Step>(), 0, next0, 0, new int[cards], ip0,
                          new double[party.length]);
        int[] at = new int[party.length];
        while(true) {
            int who = -1;
            for(int i = 0; i < n.us.length; i++) {
                if(!n.us[i].alive() || (at[i] >= lines.get(i).size()))
                    continue;
                if((who < 0) || (Math.max(n.tick, n.us[i].readyAt) < Math.max(n.tick, n.us[who].readyAt)))
                    who = i;
            }
            if(who < 0)
                return(n.plan(false));
            Move m = lines.get(who).get(at[who]++);
            Node s = step(n, who, m, party, model, front, area, maxTicks);
            if(s == null) {
                if(skipped != null)
                    skipped[0]++;
                continue;
            }
            n = s;
            if(!n.foe.alive())
                return(n.plan(true));
            if(!n.anyStanding() || (n.tick >= maxTicks))
                return(n.plan(false));
        }
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

    /** Who the creature is swinging at: `front` while they stand, then the next one standing. */
    private static int target(Combatant[] us, int front) {
        for(int k = 0; k < us.length; k++) {
            int i = (front + k) % us.length;
            if(us[i].alive())
                return(i);
        }
        return(-1);
    }

    private static Node step(Node n, int who, Move m, Member[] party, FoeModel model, int front,
                             Set<String> area, long maxTicks) {
        Combatant[] us = new Combatant[n.us.length];
        for(int i = 0; i < us.length; i++)
            us[i] = n.us[i].copy();
        Combatant foe = n.foe.copy();
        int[] ip = n.ip.clone();
        double[] lost = n.hpLost.clone();
        int[] thrown = n.foeThrown.clone();
        long foeNext = n.foeNext;
        int acts = n.foeActs;
        long ready = Math.max(n.tick, us[who].readyAt);

        /* The creature acts on its own clock while we wait - see Optimizer.step. */
        double[] weights = new double[us.length];
        long[] gap = new long[1];
        long lastAct = n.tick;
        /* Openings fade across every gap, as in Optimizer.step. */
        long clock = n.tick;
        while(us[who].alive() && foe.alive() && (foeNext <= ready) && (foeNext < maxTicks)) {
            int t = target(us, front);
            if(t < 0)
                break;
            for(int i = 0; i < us.length; i++)
                weights[i] = us[i].defenceWeight();
            /* The relation that is acting is the one a rule on OUR initiative reads. */
            fade(us, foe, foeNext - clock);
            clock = Math.max(clock, foeNext);
            us[t].ip = ip[t];
            double[] before = new double[us.length];
            for(int i = 0; i < us.length; i++)
                before[i] = us[i].hp;
            model.actParty(us, weights, t, area, foe, acts, thrown, gap, null);
            lastAct = Math.max(lastAct, foeNext);
            for(int i = 0; i < us.length; i++)
                lost[i] += Math.max(0, before[i] - us[i].hp);
            ip[t] = us[t].ip;
            acts++;
            if((party[t].trigger != null) && us[t].armed())
                Sim.trigger(foe, party[t].trigger);
            foeNext += Math.max(1, gap[0]);
        }
        if(ready > maxTicks)
            return(null);
        fade(us, foe, ready - clock);
        /* Whoever was waiting went down in the wait: the line goes on without them from the
         * blow that dropped them, and the card was never thrown. Resuming at the time they
         * WOULD have acted would cost everyone else the gap. */
        if(!us[who].alive() || !foe.alive())
            return(new Node(us, foe, n.path, lastAct, foeNext, acts, thrown, ip, lost));

        Sim sim = new Sim(us[who], foe);
        sim.advanceTo(ready);
        us[who].ip = ip[who];
        Sim.Result r = sim.use(us[who], m);
        if(!r.ok)
            return(null);
        ip[who] = us[who].ip;
        List<Step> path = new ArrayList<Step>(n.path);
        path.add(new Step(who, m, ready));
        return(new Node(us, foe, path, ready, foeNext, acts, thrown, ip, lost));
    }

    private static void fade(Combatant[] us, Combatant foe, long ticks) {
        if(ticks <= 0)
            return;
        for(Combatant c : us)
            c.decay(ticks);
        foe.decay(ticks);
    }

    /** Optimizer.prune's three ends, over the party's total. */
    private static List<Node> prune(List<Node> next, double foeHp0, int beam) {
        List<Node> byRate = new ArrayList<Node>(next);
        Collections.sort(byRate, (a, b) -> Double.compare(
                             (foeHp0 - b.foe.hp) / Math.max(1, b.tick),
                             (foeHp0 - a.foe.hp) / Math.max(1, a.tick)));
        List<Node> byHp = new ArrayList<Node>(next);
        Collections.sort(byHp, (a, b) -> {
            int c = Double.compare(a.lost(), b.lost());
            return((c != 0) ? c : Double.compare(a.foe.hp, b.foe.hp));
        });
        List<Node> bySetup = new ArrayList<Node>(next);
        Collections.sort(bySetup, (a, b) -> {
            int c = Double.compare(open(b.foe), open(a.foe));
            return((c != 0) ? c : Double.compare(a.foe.hp, b.foe.hp));
        });
        int half = Math.max(1, beam / 2);
        List<Node> out = new ArrayList<Node>();
        for(List<Node> end : java.util.Arrays.asList(byRate, byHp, bySetup)) {
            for(int i = 0; (i < half) && (i < end.size()); i++) {
                Node n = end.get(i);
                if(!out.contains(n))
                    out.add(n);
            }
        }
        return(out);
    }

    private static double open(Combatant foe) {
        double[] all = new double[4];
        for(int c = 0; c < 4; c++)
            all[c] = foe.opening(c);
        return(Formulas.combined(all));
    }

    private static double sum(double[] v) {
        double t = 0;
        for(double x : v)
            t += x;
        return(t);
    }

    /** Kills beaten on neither ticks nor the party's total hitpoints; the closest tries if none kill. */
    static List<Plan> frontier(List<Plan> all) {
        List<Plan> kills = new ArrayList<Plan>();
        for(Plan p : all) {
            if(p.killed)
                kills.add(p);
        }
        List<Plan> pool = kills.isEmpty() ? all : kills;
        if(kills.isEmpty()) {
            List<Plan> sorted = new ArrayList<Plan>(pool);
            Collections.sort(sorted, (a, b) -> Double.compare(a.foeHp, b.foeHp));
            return(sorted.subList(0, Math.min(3, sorted.size())));
        }
        List<Plan> out = new ArrayList<Plan>();
        for(Plan p : pool) {
            boolean dominated = false;
            for(Plan q : pool) {
                if(q == p)
                    continue;
                /* Optimizer.dominates, the one rule: a party's own line leaves only to a plan at
                 * least as good on time, cost AND wear. */
                if(Optimizer.dominates(q.ticks, Optimizer.cost(q.totalLost, sum(q.soaked)), 0, sum(q.soaked),
                                       p.ticks, Optimizer.cost(p.totalLost, sum(p.soaked)), 0, sum(p.soaked))) {
                    dominated = true;
                    break;
                }
            }
            if(dominated)
                continue;
            boolean seen = false;
            for(Plan q : out) {
                if((q.ticks == p.ticks) && (Math.abs(q.totalLost - p.totalLost) < 1e-9)
                   && (Math.abs(sum(q.soaked) - sum(p.soaked)) < 1e-9))
                    seen = true;
            }
            if(!seen)
                out.add(p);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.ticks, b.ticks));
        return(out);
    }
}
