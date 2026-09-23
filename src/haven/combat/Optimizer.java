package haven.combat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What to throw, and in what order.
 *
 * WHAT THIS OPTIMIZES, AND WHY IT IS NOT ONE NUMBER.
 *
 * The obvious objective is "kill it fastest, because a live opponent keeps hitting me". That
 * is a good instinct and it is not sufficient, for a reason the damage formula makes exact:
 * damage scales with the SQUARE of the opening it reads, and a defensive card cuts an opening
 * by a share of it rather than a fixed amount. Spending 25 ticks on Quick Dodge to halve a
 * standing green quarters every green hit for the rest of the fight. Whether that pays back
 * depends on how much fight is left, so time and damage are coupled and cannot be optimized
 * one after the other.
 *
 * They also are not commensurable. Collapsing them into {@code hp - lambda * ticks} needs a
 * lambda that nobody can defend: it is worth spending a minute to avoid a scratch when
 * hunting alone, and worth taking a real wound to finish in ten seconds with company coming.
 * So this returns the PARETO FRONTIER - every plan that is not beaten on both axes at once -
 * and leaves the trade to whoever is holding the character. That is the same choice this
 * project makes everywhere else: report the interval, do not invent the number inside it.
 *
 * COMBAT IS NOT TURN-BASED, and the search reflects that. Each side has its own clock; a move
 * costs its cooldown, which is the opportunity cost of everything not thrown during it, and
 * cooldowns differ per move and shift with the agility gap. So the search advances to
 * whichever clock fires next rather than alternating, and a plan is a sequence of moves with
 * the times they land at rather than a list of turns.
 *
 * Per ADR-0002 this imports nothing from {@code haven}.
 */
public final class Optimizer {
    private Optimizer() {}

    /** One candidate line of play, and what it cost. */
    /**
     * Hitpoints one point of armour wear is worth, when plans are ranked on what they cost.
     *
     * ZERO REPRODUCES EVERY PLAN THIS PROJECT HAS EVER MADE, and that is the control: with it
     * {@link Plan#cost()} is exactly {@link Plan#hpLost}, so the suite at zero checks that the
     * change priced nothing it should not have. Above zero, the soaked share of each blow -
     * 80-82% of what creatures land on us - stops being free.
     *
     * A POLICY SETTING AND NOT A MEASUREMENT. The logs do not yet say what a point soaked
     * costs in durability (the recorder reads Wear and keeps only "broken"; COMBAT.md §3.8
     * D1), so this is James's exchange rate until they do.
     */
    public static volatile double armourWeight = 0.0;

    public static final class Plan {
        public final List<Move> moves;
        /** Ticks from the first move to the kill, or to the horizon if there was none. */
        public final long ticks;
        /** Soft hitpoints we lost. NaN when the foe model cannot say. */
        public final double hpLost;
        public final boolean killed;
        /** What the opponent had left when we stopped. */
        public final double foeHp;
        /**
         * The hard hitpoints this plan takes off the opponents - the lasting wound, as
         * opposed to the soft hitpoints a fight knocks down. Zero for a plan with no
         * grievous card in it.
         */
        public final double wounds;
        /**
         * What is left of the opponents' HARD hitpoints, summed over those whose pool is
         * known, or NaN when no opponent's is. Against a creature it is NaN; against a
         * person it says how close the plan came to more than a knockdown.
         */
        public final double foeHhp;
        /** Every opponent whose hard pool is known ended with none of it - a kill, not a knockdown. */
        public final boolean lethal;
        /**
         * What our armour stopped along this plan - the part of every blow that wore the armour
         * rather than reaching {@link #hpLost}. See {@link Combatant#soaked}.
         */
        public final double soaked;
        /* Fixed when the plan is made, at the weight it was searched under - see cost(). */
        private final double cost;

        Plan(List<Move> moves, long ticks, double hpLost, boolean killed, double foeHp) {
            this(moves, ticks, hpLost, killed, foeHp, 0, Double.NaN, false, 0);
        }

        Plan(List<Move> moves, long ticks, double hpLost, boolean killed, double foeHp,
             double wounds, double foeHhp, boolean lethal, double soaked) {
            this.soaked = soaked;
            this.cost = Optimizer.cost(hpLost, soaked);
            this.moves = Collections.unmodifiableList(new ArrayList<Move>(moves));
            this.ticks = ticks;
            this.hpLost = hpLost;
            this.killed = killed;
            this.foeHp = foeHp;
            this.wounds = wounds;
            this.foeHhp = foeHhp;
            this.lethal = lethal;
        }

        /**
         * What this plan costs us: the hitpoints it takes, and the armour wear it causes at
         * {@link #armourWeight} hitpoints per point soaked. NaN where {@link #hpLost} is.
         *
         * Every comparison that ranks plans by what they cost reads this rather than
         * {@link #hpLost}. Whether we SURVIVE a plan is still {@link #hpLost} alone - armour
         * wear does not knock us down - so a budget of hitpoints is never tested against it.
         *
         * PRICED AT THE WEIGHT THE PLAN WAS SEARCHED UNDER, not at whatever the weight is when
         * someone asks. Read live, a plan made at weight 0 and compared after the weight moved
         * reported the second weight's price - CombatAudit's own probe did exactly that.
         */
        public double cost() {
            return(cost);
        }

        public String toString() {
            StringBuilder b = new StringBuilder();
            for(Move m : moves) {
                if(b.length() > 0)
                    b.append(", ");
                b.append(m.name);
            }
            return(b.toString());
        }
    }

    /* A node in the beam. Mutable state plus the path that reached it. */
    private static final class Node {
        final Combatant me;
        /**
         * EVERY opponent, not one, and the dead stay in the array.
         *
         * A crowd used to be modelled as a single opponent with N times the health on a
         * faster clock, and openings do not work that way. Opening one bee does not open
         * the others, damage goes as the SQUARE of the opening, and so five bees each a
         * fifth open is nothing like one bee fully open - the pooled version let our
         * openings accumulate against the whole crowd as though it were one animal, and
         * overstated every plan that pries a target open before hitting it.
         *
         * It was wrong in the other direction too, and that half is easier to miss. Three
         * cards in the sheet hit more than one opponent - see Move.targets - and against a
         * pooled crowd they landed once, so the model priced Full Circle against five
         * animals exactly as it priced it against one. The two errors do not cancel; they
         * are simply both there, one flattering single-target openers and one penalising
         * the cards that exist for crowds.
         *
         * The dead are kept in place so that indices line up with the models and the clocks
         * beside them, and because a plan's shape - which one we were hitting when - is
         * lost if the array is compacted underneath it.
         */
        final Combatant[] foes;
        final List<Move> path;
        final long tick;
        /** Each opponent's own next action, on its own clock. */
        final long[] foeNext;
        /**
         * How many actions each opponent has taken along this line.
         *
         * Needed because an opponent now throws real cards rather than one averaged
         * action, and which card comes next depends on how many it has already thrown -
         * they are dealt out in proportion to the measured mix. Without this every node
         * would replay the creature's FIRST action, so a boreworm would open with Roar of
         * the Wild forever and never get to Fell Scratch.
         *
         * It lives on the node rather than in the model because the search explores many
         * lines at once and each has its own history; a counter on the shared model would
         * be advanced by whichever branch happened to be expanded last.
         */
        final int[] foeActs;
        /**
         * How many of each of its own cards each opponent has thrown along this line.
         *
         * `foeActs` above says how many actions an opponent has taken; this says WHICH.
         * {@link Repertoire#pick} deals by largest deficit against this tally, so without
         * it the creature would replay one card forever - the same failure the action
         * counter fixes, one level down. One row per opponent, sized to that opponent's
         * repertoire; an empty row where the opponent still throws the averaged action.
         *
         * Per node, like `foeActs`, because each line the search explores has its own
         * history and a shared tally would be advanced by whichever branch ran last.
         */
        final int[][] foeThrown;
        /**
         * OUR INITIATIVE, HELD AGAINST EACH OPPONENT SEPARATELY.
         *
         * The game keeps initiative per relation: points built against one animal are not
         * points against the next, and a re-aggro forfeits the pool against that one alone
         * (25 of 26 logged boundaries reset it to 0). One number on the Combatant let a
         * crowd fight spend points built on the first animal against the second, which
         * prices every initiative card in a crowd as though the whole crowd were one
         * relation. `me.ip` is loaded from here before each use against a relation and
         * written back after, so Sim and FoeModel keep reading the one field they know.
         */
        final int[] myIp;
        final double hpLost;
        /** Hard hitpoints taken off the opponents along this line. See Plan.wounds. */
        final double wounds;

        Node(Combatant me, Combatant[] foes, List<Move> path, long tick, long[] foeNext,
             double hpLost, int[] foeActs, int[][] foeThrown, int[] myIp, double wounds) {
            this.me = me;
            this.myIp = myIp;
            this.wounds = wounds;
            this.foes = foes;
            this.path = path;
            this.tick = tick;
            this.foeNext = foeNext;
            this.hpLost = hpLost;
            this.foeActs = foeActs;
            this.foeThrown = foeThrown;
        }

        /** What this line has cost so far - see {@link Plan#cost()}. */
        double cost() {
            return((armourWeight == 0) ? hpLost : (hpLost + (armourWeight * me.soaked)));
        }

        /** The one we are hitting: the first still standing. See Optimizer.step. */
        int main() {
            for(int i = 0; i < foes.length; i++) {
                if(foes[i].alive())
                    return(i);
            }
            return(-1);
        }

        boolean anyAlive() {
            return(main() >= 0);
        }

        double foeHp() {
            double hp = 0;
            for(Combatant f : foes)
                hp += Math.max(0, f.hp);
            return(hp);
        }

        /** Hard hitpoints left over the opponents whose pool is known, or NaN if none is. */
        double foeHhp() {
            double h = 0;
            boolean any = false;
            for(Combatant f : foes) {
                if(Double.isNaN(f.hhp))
                    continue;
                any = true;
                h += Math.max(0, f.hhp);
            }
            return(any ? h : Double.NaN);
        }

        Plan plan(boolean killed) {
            double h = foeHhp();
            return(new Plan(path, tick, hpLost, killed, foeHp(), wounds, h,
                            !Double.isNaN(h) && (h <= 0), me.soaked));
        }
    }

    /**
     * Searches for the plans on the frontier.
     *
     * Beam search rather than exhaustive: a ten-card deck over a thirty-move fight is 10^30
     * lines, and rather than an arbitrary cut this keeps the best `beam` partial states at
     * each depth. It can keep up to 1.5x that, because {@link #prune} takes three ends of
     * `beam/2` (best rate, best hitpoints kept, best openings standing) and the three lists
     * need not overlap. Deterministic, so no averaging over rollouts - the model has no
     * random element and fits of the logged damage leave no room for one.
     *
     * The beam is ranked on damage dealt per tick spent, which is the only scalar that is
     * defensible here: it is the rate the fight is actually being won at, and it does not
     * presume how much a tick is worth against a hitpoint. Plans that defend are kept by the
     * frontier at the end rather than by the ranking during, because their payback arrives
     * later than the beam can see.
     *
     * @param maxTicks the horizon. A plan that has not killed by then is reported as not
     *                 killing rather than extrapolated.
     */
    public static List<Plan> search(Combatant me, Combatant foe, List<Move> deck,
                                    FoeModel model, int beam, long maxTicks) {
        return(search(me, new Combatant[] {foe}, deck, new FoeModel[] {model}, beam, maxTicks));
    }

    /**
     * The same search against a CROWD, each opponent with its own health and its own clock.
     *
     * One against one is the special case of this with an array of one, which is how the
     * overload above is served. What the crowd changes is not only arithmetic:
     *
     * - Openings are per opponent. Prying one animal open buys nothing against the next,
     *   which is what the old pooled model got wrong and why it flattered every opener.
     * - Each opponent swings on its own clock, so the rate the crowd hits us at falls as
     *   they die rather than holding at its opening value to the last animal.
     * - A card that hits several of them - Full Circle, Punch 'em Both, Storm of Swords -
     *   actually hits several of them here, which against a pooled opponent it could not.
     *
     * WE FOCUS ONE AT A TIME, killing down the array in order, which is what a player does
     * and is not free of consequence: it is why the crowd's rate falls, and it is why a
     * splash card's openings are worth keeping - they are still standing on the next one
     * when we get to it.
     *
     * What is still missing is range. Nothing here knows where anybody stands, so a
     * multi-target card reaches every opponent still alive, and the corpus says that is an
     * upper bound - Full Circle opened only one opponent in 53 of the 99 throws that opened
     * anything at all.
     */
    public static List<Plan> search(Combatant me, Combatant[] foes, List<Move> deck,
                                    FoeModel[] models, int beam, long maxTicks) {
        return(search(me, foes, deck, models, beam, maxTicks, null));
    }

    /**
     * The same, starting from the initiative we already hold against EACH opponent.
     *
     * {@code myIp0[i]} is our initiative against {@code foes[i]}; null, or an array of the
     * wrong length, starts every relation at {@code me.ip}, which is the one-opponent case
     * and what a caller that only knows the sampled relation can say.
     */
    public static List<Plan> search(Combatant me, Combatant[] foes, List<Move> deck,
                                    FoeModel[] models, int beam, long maxTicks,
                                    int[] myIp0) {
        return(search(me, foes, deck, models, beam, maxTicks, myIp0, null));
    }

    /**
     * The same, also handing back EVERY finished plan in {@code all}, dominated ones included.
     *
     * The frontier keeps one plan per trade-off, so a card a hair slower than the best is not on
     * it at all - and "how much worse is the card we are already showing?" is a question about
     * exactly those plans. See Prediction's HELD card. Null skips the copy.
     */
    public static List<Plan> search(Combatant me, Combatant[] foes, List<Move> deck,
                                    FoeModel[] models, int beam, long maxTicks,
                                    int[] myIp0, List<Plan> all) {
        return(search(me, foes, deck, models, beam, maxTicks, myIp0, all, null));
    }

    /** The same, with each opponent joining at its own tick - see {@link #follow}. */
    public static List<Plan> search(Combatant me, Combatant[] foes, List<Move> deck,
                                    FoeModel[] models, int beam, long maxTicks,
                                    int[] myIp0, List<Plan> all, long[] arrive) {
        return(search(me, foes, deck, models, beam, maxTicks, myIp0, all, arrive, null));
    }

    /**
     * The same, also offering each of {@code lines} - lines people have actually thrown - to the
     * frontier beside the searched ones.
     *
     * THE PLANNER MUST NEVER DO WORSE THAN A LINE A PLAYER HAS SHOWN IT (James, 2026-09-22). A beam
     * cannot promise that: it prunes by the damage done so far, and against the characters' own
     * solo fights it lost to the player in 2% of them, in party fights 16% - each time a line the
     * search had cut early (Quick Barrage x6 then Full Circle, into openings the barrages built).
     * Offered here, a player's line is stepped by the same code as the searched ones, from the same
     * state, and the frontier then keeps whichever is not dominated. So for every line given, the
     * frontier holds a plan at least as fast, as cheap and as wounding - and the aims pick from the
     * frontier, so none picks a plan a given line beats on its own measure. A line that runs out
     * before the kill is repeated from its start, which is what a person does; one that still does
     * not kill is dropped, as the rhythm seeds are.
     */
    public static List<Plan> search(Combatant me, Combatant[] foes, List<Move> deck,
                                    FoeModel[] models, int beam, long maxTicks,
                                    int[] myIp0, List<Plan> all, long[] arrive,
                                    List<List<Move>> lines) {
        double[] trigger = trigger(deck, me);
        Combatant[] f0 = new Combatant[foes.length];
        long[] next0 = new long[foes.length];
        int[][] thrown0 = new int[foes.length][];
        double hp0 = 0;
        for(int i = 0; i < foes.length; i++) {
            f0[i] = foes[i].copy();
            next0[i] = firstAct(models[i], f0[i]);
            if((arrive != null) && (i < arrive.length) && (next0[i] != Long.MAX_VALUE))
                next0[i] += Math.max(0, arrive[i]);
            thrown0[i] = new int[cardCount(models[i])];
            hp0 += f0[i].hp;
        }
        List<Node> live = new ArrayList<Node>();
        int[] ip0 = new int[foes.length];
        for(int i = 0; i < foes.length; i++)
            ip0[i] = ((myIp0 != null) && (myIp0.length == foes.length)) ? myIp0[i] : me.ip;
        /* A plan's wear is what THAT plan takes, not whatever the caller's combatant carried in. */
        Combatant root = me.copy();
        root.soaked = 0;
        live.add(new Node(root, f0, new ArrayList<Move>(), 0, next0, 0,
                          new int[foes.length], thrown0, ip0, 0));
        List<Plan> done = new ArrayList<Plan>();

        while(!live.isEmpty()) {
            List<Node> next = new ArrayList<Node>();
            for(Node n : live) {
                for(Move m : deck) {
                    Node s = step(n, m, models, maxTicks, trigger);
                    if(s == null)
                        continue;
                    if(!s.anyAlive()) {
                        done.add(s.plan(true));
                    } else if(!s.me.alive() || (s.tick >= maxTicks)) {
                        done.add(s.plan(false));
                    } else {
                        next.add(s);
                    }
                }
            }
            if(next.isEmpty())
                break;
            live = prune(next, hp0, beam);
        }
        done.addAll(seeds(me, foes, models, deck, maxTicks, myIp0, arrive));
        if(lines != null) {
            for(List<Move> l : lines) {
                if((l == null) || l.isEmpty())
                    continue;
                List<Move> line = new ArrayList<Move>(SEED_LEN);
                while(line.size() < SEED_LEN)
                    line.addAll(l);
                Plan p = follow(me, foes, models, deck, line, maxTicks, myIp0, null, arrive);
                if(p.killed)
                    done.add(p);
            }
        }
        if(all != null)
            all.addAll(done);
        return(frontier(done));
    }

    /**
     * The lines players actually throw, offered to the frontier beside the searched ones.
     *
     * THE RHYTHM THE PARTIES USE: the quickest damaging card k times, to build openings, then the
     * heaviest, which lands on them - damage goes as the square of the opening. Against the
     * characters' own polar bears (COMBAT.md §3.10) a party throwing 14 Quick Barrages and 2 Full
     * Circles killed in 242 ticks in the model, where plain Quick Barrage needs 580 and the searched
     * lines 352-390: the searched lines threw Full Circle first, into nothing, because the beam
     * keeps lines by the damage done so far and a big hit looks best the moment it lands. Plain
     * repetition of every damaging card is offered too (k infinite).
     *
     * Seeding cannot make a plan worse - the frontier still decides, so a seed survives only if
     * nothing searched beats it. It costs a few Optimizer.follow walks per search.
     */
    private static List<Plan> seeds(Combatant me, Combatant[] foes, FoeModel[] models, List<Move> deck,
                                    long maxTicks, int[] myIp0, long[] arrive) {
        /* ONLY SEEDS THAT KILL go to the frontier. A seed whose finisher the model refuses
         * (Cleave short of initiative) stops a few cards in, costs almost nothing, and was picked
         * at low health as the "least damage" line - a moose fight answered with a plan that never
         * finished it (2026-09-22). */
        List<Plan> out = new ArrayList<Plan>();
        Move quick = null;
        for(Move m : deck) {
            if(m.stance || !m.deals())
                continue;
            if((quick == null) || (m.cooldownBase < quick.cooldownBase))
                quick = m;
            List<Move> line = new ArrayList<Move>(SEED_LEN);
            for(int i = 0; i < SEED_LEN; i++)
                line.add(m);
            Plan p = follow(me, foes, models, deck, line, maxTicks, myIp0, null, arrive);
            if(p.killed)
                out.add(p);
        }
        /* EVERY DAMAGING CARD AS THE FINISHER, not only the one with the largest share. The largest
         * is often Cleave (1.5, 80 ticks, 4 initiative), and "Quick Barrage k times then Cleave"
         * is not the line anyone throws: parties of two beat the planner on red deer in 58% of 33
         * fights with Quick Barrage x3-4 then Full Circle, which no seed offered (2026-09-21). */
        if(quick != null) {
            for(Move heavy : deck) {
                if(heavy.stance || !heavy.deals() || (heavy == quick))
                    continue;
                for(int k = 1; k <= SEED_RHYTHM; k++) {
                    Plan p = follow(me, foes, models, deck, rhythm(quick, heavy, k), maxTicks, myIp0, null, arrive);
                    if(p.killed)
                        out.add(p);
                    Plan b = follow(me, foes, models, deck, burst(quick, heavy, k), maxTicks, myIp0, null, arrive);
                    if(b.killed)
                        out.add(b);
                }
            }
        }
        return(out);
    }

    /**
     * {@code quick} k times, then {@code heavy} for the rest of the line - BUILD, THEN BURST. What
     * the polar bear parties threw (2026-09-22): Quick Barrage six or nine times, then Full Circle
     * three times running, not the build-and-cash rhythm repeated. Once the openings stand, every
     * heavy blow cashes them, and a rebuild between them gives the decay the time it wants.
     */
    static List<Move> burst(Move quick, Move heavy, int k) {
        List<Move> line = new ArrayList<Move>(SEED_LEN);
        for(int i = 0; i < SEED_LEN; i++)
            line.add((i < k) ? quick : heavy);
        return(line);
    }

    /** {@code quick} k times, then {@code heavy}, repeated to SEED_LEN cards. */
    static List<Move> rhythm(Move quick, Move heavy, int k) {
        List<Move> line = new ArrayList<Move>(SEED_LEN);
        while(line.size() < SEED_LEN) {
            for(int i = 0; (i < k) && (line.size() < SEED_LEN); i++)
                line.add(quick);
            if(line.size() < SEED_LEN)
                line.add(heavy);
        }
        return(line);
    }

    /* The longest opener run a rhythm seed tries before its finisher. */
    static final int SEED_RHYTHM = 8;

    /* Long enough for any seed to run to the kill or the horizon: a line stops at either. */
    private static final int SEED_LEN = 400;

    /** What the deck, or failing that the stance we hold, opens when the opponent swings. */
    private static double[] trigger(List<Move> deck, Combatant me) {
        /* Everything the deck opens when the OPPONENT swings, summed once. A deck holds
         * at most one such card in the corpus - Parry - but summing costs nothing and
         * assumes nothing about that staying true. */
        double[] trigger = new double[4];
        boolean anyTrigger = false;
        for(Move m : deck) {
            for(int c = 0; c < 4; c++) {
                if(m.whenAttackedOpens[c] > 0) {
                    trigger[c] += m.whenAttackedOpens[c];
                    anyTrigger = true;
                }
            }
        }
        /* OR FROM THE STANCE WE HOLD, when the deck carries none. A stance is held, not thrown,
         * and a caller that leaves it out of the deck - which it should, because a stance in the
         * deck is a card the search keeps trying and never throws, and it crowded Shield Up decks
         * out of their best line (a bear at 725 ticks with it, 515 without) - puts Parry's answer on
         * the fighter instead (Combatant.whenAttacked, set by CombatDeckSearch.withStance and
         * Prediction.applyStance). Read only when the deck has none, so a deck still carrying the
         * card is not counted twice. */
        if(!anyTrigger) {
            for(int c = 0; c < 4; c++) {
                if(me.whenAttacked[c] > 0) {
                    trigger[c] += me.whenAttacked[c];
                    anyTrigger = true;
                }
            }
        }
        return(anyTrigger ? trigger : null);
    }

    /**
     * Where a GIVEN line of cards ends up - the search's own step, walked down one sequence
     * instead of branched.
     *
     * WHY. The planner's lines are only worth something against what a person actually threw,
     * and the only fair comparison is inside the same model: the logged line and the searched
     * line stepped by the same code against the same opponent from the same start. A card the
     * model refuses at that point (initiative short, say) is skipped and counted in
     * {@code skipped[0]}; the line stops at a kill, at our death or at {@code maxTicks}, and a
     * line that runs out of cards first is reported as it stands, not killed.
     */
    public static Plan follow(Combatant me, Combatant[] foes, FoeModel[] models, List<Move> deck,
                              List<Move> line, long maxTicks, int[] myIp0, int[] skipped) {
        return(follow(me, foes, models, deck, line, maxTicks, myIp0, skipped, null));
    }

    /**
     * The same, with each opponent joining at its own tick - {@code arrive[i]}, or at once where
     * null. A crowd does not start on us together: the bat dungeon's adds came in for a minute,
     * and staged all at tick zero every one of them swings from the first blow. An opponent that
     * has not arrived does not act; we can still reach it, which is the caller's to order - the
     * line hits the first opponent standing, so order them as they were killed.
     */
    public static Plan follow(Combatant me, Combatant[] foes, FoeModel[] models, List<Move> deck,
                              List<Move> line, long maxTicks, int[] myIp0, int[] skipped,
                              long[] arrive) {
        return(follow(me, foes, models, deck, line, maxTicks, myIp0, skipped, arrive, null));
    }

    /**
     * The same, with each card aimed at {@code targets[i]} - the creature the log says it hit - where
     * that creature still stands. Null aims every card at the first standing.
     */
    public static Plan follow(Combatant me, Combatant[] foes, FoeModel[] models, List<Move> deck,
                              List<Move> line, long maxTicks, int[] myIp0, int[] skipped,
                              long[] arrive, int[] targets) {
        double[] trigger = trigger(deck, me);
        Combatant[] f0 = new Combatant[foes.length];
        long[] next0 = new long[foes.length];
        int[][] thrown0 = new int[foes.length][];
        for(int i = 0; i < foes.length; i++) {
            f0[i] = foes[i].copy();
            next0[i] = firstAct(models[i], f0[i]);
            if((arrive != null) && (i < arrive.length) && (next0[i] != Long.MAX_VALUE))
                next0[i] += Math.max(0, arrive[i]);
            thrown0[i] = new int[cardCount(models[i])];
        }
        int[] ip0 = new int[foes.length];
        for(int i = 0; i < foes.length; i++)
            ip0[i] = ((myIp0 != null) && (myIp0.length == foes.length)) ? myIp0[i] : me.ip;
        Combatant root = me.copy();
        root.soaked = 0;
        Node n = new Node(root, f0, new ArrayList<Move>(), 0, next0, 0,
                          new int[foes.length], thrown0, ip0, 0);
        for(int k = 0; k < line.size(); k++) {
            Move m = line.get(k);
            int aim = ((targets != null) && (k < targets.length)) ? targets[k] : -1;
            Node s = step(n, m, models, maxTicks, trigger, aim);
            if(s == null) {
                if(skipped != null)
                    skipped[0]++;
                continue;
            }
            n = s;
            if(!n.anyAlive())
                return(n.plan(true));
            if(!n.me.alive() || (n.tick >= maxTicks))
                return(n.plan(false));
        }
        return(n.plan(false));
    }

    /**
     * Keeps a beam that is diverse along BOTH objectives, which a single ranking cannot.
     *
     * Ranking on damage per tick alone is the obvious choice and it is self-defeating here:
     * a defensive move deals nothing, so it always lowers the rate, so every line that
     * defends is pruned one step after it defends - long before the reduced openings pay
     * back. The search then reports that defending never helps, which is not a finding
     * about combat but an artefact of the pruning.
     *
     * It showed up exactly that way: a deck with Quick Dodge and one without came out at
     * the same 63.5 hitpoints over a long fight, when the whole reason the frontier exists
     * is that they should differ.
     *
     * AND A THIRD END, WHICH IS SETUP. Both of the above rank on value already realised -
     * damage done, hitpoints kept - and an opening is value NOT yet realised. Damage goes
     * as the square of the opening, so a line that has spent three cards prying a target
     * open has done little damage, kept no more hitpoints than anyone else, and is holding
     * the largest payment in the search. Ranked on either of the first two it is pruned one
     * step before it collects.
     *
     * That is what broke deck comparison. Adding a card to a deck cannot make the true
     * optimum worse, because the plan that ignores it is still there - and ticks by deck
     * size against the cave angler ran 736, 705, 705, 884 at beam 20, still rising at beam
     * 2000. A wider beam does not fix a ranking that discards the winning shape; it only
     * discards it more slowly.
     *
     * So the beam is three ways: best by rate, best by hitpoints kept, best by the openings
     * standing. A frontier search has to carry every end of the frontier while it searches,
     * or it can only ever find the ends it carried.
     */
    private static List<Node> prune(List<Node> next, double foeHp0, int beam) {
        List<Node> byRate = new ArrayList<Node>(next);
        Collections.sort(byRate, (a, b) -> {
            double ra = (foeHp0 - a.foeHp()) / Math.max(1, a.tick);
            double rb = (foeHp0 - b.foeHp()) / Math.max(1, b.tick);
            return(Double.compare(rb, ra));
        });
        List<Node> byHp = new ArrayList<Node>(next);
        Collections.sort(byHp, (a, b) -> {
            int c = Double.compare(a.cost(), b.cost());
            return((c != 0) ? c : Double.compare(a.foeHp(), b.foeHp()));
        });
        List<Node> bySetup = new ArrayList<Node>(next);
        Collections.sort(bySetup, (a, b) -> {
            int c = Double.compare(open(b), open(a));
            return((c != 0) ? c : Double.compare(a.foeHp(), b.foeHp()));
        });
        /* ADDED TO THE OTHER TWO, NOT CARVED OUT OF THEM. Taking a third of the beam for
         * setup was the obvious way and it broke the check next door: the initiative curve
         * stopped being monotone, because the hitpoint end had been cut in half to pay for
         * it and the least-damage line went with it. The two ends that were already there
         * were already load-bearing. */
        int half = Math.max(1, beam / 2);
        List<Node> out = new ArrayList<Node>();
        for(int i = 0; (i < half) && (i < byRate.size()); i++)
            out.add(byRate.get(i));
        for(int i = 0; (i < half) && (i < byHp.size()); i++) {
            Node n = byHp.get(i);
            if(!out.contains(n))
                out.add(n);
        }
        for(int i = 0; (i < half) && (i < bySetup.size()); i++) {
            Node n = bySetup.get(i);
            if(!out.contains(n))
                out.add(n);
        }
        return(out);
    }

    /**
     * How much is standing open on the one we are hitting, which is damage not yet collected.
     *
     * The one we are hitting rather than the crowd's total, because that is the payment the
     * next swing collects. Summing the crowd would rank a line that has lightly opened five
     * animals above one that has pried a single animal wide, and the square in the damage
     * formula says the opposite.
     */
    private static double open(Node n) {
        int i = n.main();
        if(i < 0)
            return(0);
        double[] all = new double[4];
        for(int c = 0; c < 4; c++)
            all[c] = n.foes[i].opening(c);
        return(Formulas.combined(all));
    }

    /** How many cards this opponent's repertoire holds, or 0 when it throws an average. */
    private static int cardCount(FoeModel m) {
        return(((m == null) || (m.cards == null) || !m.cards.usable())
               ? 0 : m.cards.tallySize());
    }

    /**
     * Applies one of our moves, letting every opponent act for the clock ticks it owns.
     *
     * The waiting half is where "combat is not turn-based" lives, and with a crowd it is
     * also where the crowd's pressure comes from: each opponent has its own period and its
     * own place in its own rotation, so the window our cooldown opens is filled by whichever
     * of them happens to come up in it, not by an average.
     */
    /**
     * Sees every card a step throws: the state it is thrown into, and what it did. For tools that
     * set a model's forward run beside a log (tools/OpeningDrift.java); null, and never set, in the
     * client. Not thread-safe by design - a tool sets it around a single-threaded follow().
     */
    public interface Trace {
        void before(Move m, long tick, Combatant me, Combatant foe);
        void after(Move m, long tick, Combatant me, Combatant foe, Sim.Result r);
        /** An opponent acted, at this tick. */
        default void foeActed(int who, long tick) {
        }
    }

    public static volatile Trace trace = null;

    /** A gap between an opponent's actions AT US - its own gap over the share aimed at us. */
    static long onUs(long gap, Combatant foe) {
        if((foe == null) || !(foe.onUs > 0) || (foe.onUs >= 1.0) || (gap == Long.MAX_VALUE))
            return(gap);
        return(Math.round(gap / Math.max(ON_US_FLOOR, foe.onUs)));
    }

    /* The least share of its attacks a creature in our fight list is read as aiming at us. */
    static final double ON_US_FLOOR = 0.1;

    /** The tick an opponent first acts: its own Combatant.firstAct where set, else a period. */
    static long firstAct(FoeModel m, Combatant foe) {
        if(m.period == Long.MAX_VALUE)
            return(Long.MAX_VALUE);
        if((foe != null) && !Double.isNaN(foe.firstAct))
            return(Math.max(0, Math.round(foe.firstAct)));
        return(m.period);
    }

    private static Node step(Node n, Move m, FoeModel[] models, long maxTicks,
                             double[] trigger) {
        return(step(n, m, models, maxTicks, trigger, -1));
    }

    /**
     * The same, aimed at {@code force} while it stands, else at the first opponent standing as
     * always. Only Optimizer.follow aims: a logged line names the creature each card hit, and
     * "first standing" moved cards onto the wrong one - three cards at a 6 hp bat left the third
     * landing on a fresh bat with nothing open, and crowds read a third short (2026-09-21).
     */
    private static Node step(Node n, Move m, FoeModel[] models, long maxTicks,
                             double[] trigger, int force) {
        Combatant me = n.me.copy();
        Combatant[] foes = new Combatant[n.foes.length];
        for(int i = 0; i < foes.length; i++)
            foes[i] = n.foes[i].copy();
        long[] foeNext = n.foeNext.clone();
        int[] acts = n.foeActs.clone();
        int[][] thrown = new int[n.foeThrown.length][];
        for(int i = 0; i < thrown.length; i++)
            thrown[i] = (n.foeThrown[i] == null) ? new int[0] : n.foeThrown[i].clone();
        long tick = n.tick;
        double hpLost = n.hpLost;
        int[] myIp = n.myIp.clone();
        double wounds = n.wounds;

        /* Wait until we may act, and let the opponents act on their own clocks meanwhile.
         * A long cooldown is not merely slow, it is a window they get to swing in, and a
         * short one is not.
         *
         * SAME-TICK ATTACKERS ARE SERIALISED, AND THE LATER ONE BENEFITS. The loop picks
         * the earliest foeNext[who] and lets it act before advancing that foe's clock. When
         * two foes are due on the same tick, the tie in the pick below breaks by array
         * index, so the second one acts against the openings the first one just applied
         * rather than against the state both swung into. The error runs one way: the later
         * same-tick attacker reads a defender the earlier one opened, so a same-tick crowd
         * OVERSTATES its damage. N6 N-14 registered this as structural (Optimizer.java:
         * 381-409); the corpus cannot settle simultaneity, and Duel.java:34-36 says so
         * outright - real simultaneous resolution is not something the corpus settles. The
         * serialisation is deliberately left in place; this records the hypothesis and its
         * direction rather than changing behaviour. */
        long ready = Math.max(tick, me.readyAt);
        /* OPENINGS FADE ACROSS EVERY GAP the clock crosses - before each of their actions and
         * before ours - on everybody, since each standing opening decays on its own. See
         * Formulas.OPENING_DECAY_PER_TICK. */
        long clock = tick;
        while(me.alive()) {
            /* Whichever of them is due first. A dead one is due never, which is the whole
             * of why a crowd gets quieter as it dies - the old pooled model kept swinging
             * at five-sixths of full rate down to the last animal. */
            int who = -1;
            for(int i = 0; i < foes.length; i++) {
                if(!foes[i].alive())
                    continue;
                if((who < 0) || (foeNext[i] < foeNext[who]))
                    who = i;
            }
            if((who < 0) || (foeNext[who] > ready) || (foeNext[who] >= maxTicks))
                break;
            /* A fleeing opponent has stopped fighting back, so this window costs nothing -
             * and every reduction or point of initiative bought during it buys nothing
             * either. The frontier sorts that out on its own once the damage stops: a plan
             * that keeps defending simply arrives later for the same hitpoints, and is
             * dominated. */
            long[] gap = new long[1];
            decay(me, foes, foeNext[who] - clock);
            clock = Math.max(clock, foeNext[who]);
            /* The relation that is acting is the one whose initiative a rule on ours reads. */
            me.ip = myIp[who];
            hpLost += models[who].act(me, me.defenceWeight(), foes[who], acts[who],
                                      thrown[who], gap);
            Trace tr0 = trace;
            if(tr0 != null)
                tr0.foeActed(who, clock);
            myIp[who] = me.ip;
            acts[who]++;
            /* AND WHAT WE HOLD THAT ANSWERS A SWING. Parry opens the opponent when the
             * opponent attacks, not when it is played, so it lands here rather than in
             * use() - and it lands on THE ONE THAT SWUNG, which is measured: across 762
             * steps where blue rose on any of several opponents at once, it rose on
             * exactly one in 753. A sword is required, which is why this reads armed. */
            if((trigger != null) && me.armed())
                Sim.trigger(foes[who], trigger);
            /* PER-CARD COOLDOWNS WHERE THE CARD HAS ONE. The gap the model reports is the
             * thrown card's own measured cooldown, falling back to the creature's single
             * period when the card has none. Scheduling every action on the period collapsed
             * a creature that throws a fast card and a slow one onto one clock neither of
             * them kept. AND ONLY ITS SHARE AIMED AT US - Combatant.onUs. */
            foeNext[who] += onUs(gap[0], foes[who]);
        }
        tick = ready;
        if(tick > maxTicks)
            return(null);
        decay(me, foes, ready - clock);

        /* WE DIED WAITING, AND THAT IS A RESULT RATHER THAN A DEAD END.
         *
         * The swing below would be refused for being dead, and a refusal returns null,
         * which drops the line out of the search entirely. One against one that merely
         * lost a few hopeless plans off the frontier. Against a crowd it lost ALL of them:
         * two animals that kill us before we kill them made every line end this way, so
         * the search returned nothing at all and the caller read "no plan" where the
         * answer was "this fight kills you".
         *
         * So the node comes back with the path it arrived with - the move was never thrown
         * - and the caller records it as a plan that did not kill. */
        if(!me.alive())
            return(new Node(me, foes, n.path, tick, foeNext, hpLost, acts, thrown, myIp,
                            wounds));

        int main = -1;
        if((force >= 0) && (force < foes.length) && foes[force].alive())
            main = force;
        for(int i = 0; (main < 0) && (i < foes.length); i++) {
            if(foes[i].alive()) {
                main = i;
                break;
            }
        }
        if(main < 0)
            return(new Node(me, foes, n.path, tick, foeNext, hpLost, acts, thrown, myIp,
                            wounds));

        Sim sim = new Sim(me, foes[main]);
        sim.advanceTo(tick);
        /* Against THIS relation, with what we hold against it: legality, the initiative-
         * scaled cooldown and the cost all read and write the one we are swinging at. */
        me.ip = myIp[main];
        Trace tr = trace;
        if(tr != null)
            tr.before(m, tick, me, foes[main]);
        Sim.Result r = sim.use(me, m);
        if((tr != null) && r.ok)
            tr.after(m, tick, me, foes[main], r);
        if(!r.ok)
            return(null);
        myIp[main] = me.ip;
        wounds += r.grievous;
        /* AND EVERYONE ELSE IT REACHES. Three cards hit more than the one in front of us -
         * see Move.targets - and what they do to the rest is the reason to hold one: the
         * damage, and openings that are still standing on the next animal when this one
         * falls. Nothing here knows about range, so they reach everything still alive,
         * which is an upper bound rather than an estimate. */
        if(m.splashes()) {
            /* HOW MANY OF THEM IT REACHES, which is measured rather than "all of them".
             * Full Circle lands on 0.40 of the bystanders in the corpus - see
             * Formulas.sweepReach - and giving it every one of them was what made a crowd
             * free: five animals died in the same time as three. The per-size measurement is
             * super-linear, so sweepReach interpolates it rather than drawing a straight line
             * through it.
             *
             * The fractional part goes on the MARGINAL target rather than being spread
             * over all of them. With one bystander standing, the card reaches it 0.4 of
             * the time; spreading that as "0.4 of a blow to everybody" would be a mean
             * field over the whole crowd, which is the aggregate this model has spent its
             * time removing. Put on the last one it preserves the same expectation while
             * every target the card definitely reaches takes a real, whole swing. */
            int others = 0, inReach = 0;
            boolean positions = true;
            double sweep = me.reach();
            for(int i = 0; i < foes.length; i++) {
                if((i == main) || !foes[i].alive())
                    continue;
                others++;
                if(Double.isNaN(foes[i].distance))
                    positions = false;
                else if(foes[i].distance <= sweep)
                    inReach++;
            }
            /* WHERE THEY ACTUALLY STAND, when anybody knows. A weapon's range figure is a
             * multiple of the unarmed reach - see Formulas.UNARMED_REACH - so a swing
             * covers a real number of world units and an opponent is either inside it or
             * not. That is the whole mechanic, and it needs positions: the live client has
             * them, and a corpus written from schema 16 onwards has them.
             *
             * Without them the measured share stands in, because the alternative readings
             * are both wrong in a known direction: everybody in reach makes a crowd free,
             * and nobody in reach deletes a card that demonstrably lands on up to five. */
            double expect = positions ? inReach : Formulas.sweepReach(others);
            expect = Math.min(expect, (double)(m.targets - 1));
            int whole = (int)Math.floor(expect);
            double part = expect - whole;
            int idx = 1;
            for(int i = 0; (i < foes.length) && (idx <= whole + ((part > 0) ? 1 : 0)); i++) {
                if((i == main) || !foes[i].alive())
                    continue;
                /* The ones actually inside the swing, where we know where they are. With
                 * no positions the count is an expectation and any of them will do, since
                 * the model has nothing that distinguishes them. */
                if(positions && !(foes[i].distance <= sweep))
                    continue;
                Sim.Result sr = sim.splash(me, m, foes[i], idx, (idx <= whole) ? 1.0 : part);
                if(sr.ok)
                    wounds += sr.grievous;
                idx++;
            }
        }
        List<Move> path = new ArrayList<Move>(n.path);
        path.add(m);
        return(new Node(me, foes, path, tick, foeNext, hpLost, acts, thrown, myIp, wounds));
    }

    /** Everyone's openings, faded across a gap of this many ticks. */
    static void decay(Combatant me, Combatant[] foes, long ticks) {
        if(ticks <= 0)
            return;
        me.decay(ticks);
        for(Combatant f : foes)
            f.decay(ticks);
    }

    /**
     * The plans not beaten on both axes at once.
     *
     * A plan is dominated when another kills at least as fast AND costs no more hitpoints.
     * What survives is the actual choice: the fastest line, the cheapest line, and whatever
     * genuinely trades between them. Anything else is a worse version of one of those.
     */
    /**
     * Whether a plan at (qTicks, qCost, qWounds, qSoaked) dominates one at (p...): at least as good on
     * every axis and better on one. THE ONE RULE for Optimizer, PartyPlanner and PartyCrowd (seams
     * audit, 2026-09-23) - each kept its own copy, and the party pair never gained the armour weight
     * or the wounds axis this one did.
     *
     * Time and cost as they always were. AND WOUNDS AT LEAST AS DEEPLY: a plan that is slower or
     * costlier but takes more of the opponent's HARD hitpoints is a different answer - the one that
     * kills a person rather than knocking them down. AND WEARS OUR ARMOUR NO MORE (2026-09-22): wear is
     * what our armour pays, and cost sees it only when armourWeight is set, so without this axis a
     * plan a tick faster that soaks twice the armour pruned a player's line that soaked half.
     */
    public static boolean dominates(long qTicks, double qCost, double qWounds, double qSoaked,
                                    long pTicks, double pCost, double pWounds, double pSoaked) {
        return((qTicks <= pTicks) && !(qCost > pCost) && (qWounds >= pWounds - 1e-9)
               && (qSoaked <= pSoaked + 1e-9)
               && ((qTicks < pTicks) || (qCost < pCost) || (qWounds > pWounds + 1e-9)
                   || (qSoaked < pSoaked - 1e-9)));
    }

    /** What hitpoints and wear cost together at the armour weight in force - see Plan.cost. */
    public static double cost(double hpLost, double soaked) {
        return((armourWeight == 0) ? hpLost : (hpLost + (armourWeight * soaked)));
    }

    public static List<Plan> frontier(List<Plan> all) {
        List<Plan> kills = new ArrayList<Plan>();
        for(Plan p : all) {
            if(p.killed)
                kills.add(p);
        }
        /* Nothing killed inside the horizon: report the closest tries rather than nothing,
         * because "it does not die" is itself the answer to the matchup question. */
        List<Plan> pool = kills.isEmpty() ? all : kills;
        List<Plan> out = new ArrayList<Plan>();
        for(Plan p : pool) {
            boolean dominated = false;
            for(Plan q : pool) {
                if(q == p)
                    continue;
                if(dominates(q.ticks, q.cost(), q.wounds, q.soaked,
                             p.ticks, p.cost(), p.wounds, p.soaked)) {
                    dominated = true;
                    break;
                }
            }
            if(!dominated)
                out.add(p);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.ticks, b.ticks));
        /* Ties on both axes are the same plan by any measure that matters; keep one. */
        List<Plan> uniq = new ArrayList<Plan>();
        for(Plan p : out) {
            boolean seen = false;
            for(Plan q : uniq) {
                if((q.ticks == p.ticks) && (Math.abs(q.cost() - p.cost()) < 1e-9)
                   && (Math.abs(q.wounds - p.wounds) < 1e-9) && (Math.abs(q.soaked - p.soaked) < 1e-9)) {
                    seen = true;
                    break;
                }
            }
            if(!seen)
                uniq.add(p);
        }
        return(uniq);
    }

    /**
     * What a point of initiative brought to the fight is worth.
     *
     * Take Aim and its kin are thrown at range BEFORE the fight starts - kited in, building
     * a pool while the opponent cannot reach us. That time is nearly free, so the question
     * is not what a point costs but how many are worth collecting before engaging, and the
     * answer is a diminishing return that has to be measured rather than guessed: a deck
     * with one initiative-spender wants exactly as many points as it will throw, and a
     * further point buys nothing at all.
     *
     * Runs the search from each starting pool and reports the cheapest killing plan at each.
     * The point to stop at is where the column stops improving.
     *
     * NON-MONOTONICITY IS A BUG SIGNAL, and this reports it rather than hiding it. Bringing
     * a point of initiative cannot make a fight worse: every plan available with two points
     * is still available with four, since the extra simply goes unspent. So if the curve
     * ever rises, the SEARCH failed to find a line it should have - the beam was too narrow
     * for the wider branching that more initiative allows. Observed exactly: at a beam of 40
     * this reads 95.4 at four points against 63.5 at two, and at 120 it reads 63.5
     * throughout. Taking a running minimum would produce the right numbers and conceal an
     * under-resourced search, so the raw result is returned and {@link #beamWasEnough} says
     * whether to trust it.
     *
     * @return hitpoints lost by the cheapest killing plan at 0, 1, ... maxIp starting
     *         initiative; NaN where nothing killed
     */
    public static double[] valueOfStartingIp(Combatant me, Combatant foe, List<Move> deck,
                                             FoeModel model, int beam, long maxTicks,
                                             int maxIp) {
        double[] out = new double[maxIp + 1];
        for(int ip = 0; ip <= maxIp; ip++) {
            Combatant m = me.copy();
            m.ip = ip;
            double best = Double.NaN;
            for(Plan p : search(m, foe, deck, model, beam, maxTicks)) {
                if(p.killed && (Double.isNaN(best) || (p.hpLost < best)))
                    best = p.hpLost;
            }
            out[ip] = best;
        }
        return(out);
    }

    /**
     * Whether a starting-initiative curve is monotone, and so whether the beam sufficed.
     *
     * More initiative cannot cost hitpoints. A curve that rises has been produced by a
     * search that missed a plan, and every number on it is suspect - not only the one that
     * rose. Widen the beam and run it again.
     */
    public static boolean beamWasEnough(double[] curve) {
        double best = Double.MAX_VALUE;
        for(double v : curve) {
            if(Double.isNaN(v))
                continue;
            if(v > (best + 1e-9))
                return(false);
            best = Math.min(best, v);
        }
        return(true);
    }

    /** The deck as a list, for callers holding a pack. */
    public static List<Move> deck(Move... moves) {
        return(Arrays.asList(moves));
    }
}
