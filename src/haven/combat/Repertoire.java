package haven.combat;

/**
 * A creature's cards and how often it throws each, given the state it is in.
 *
 * THIS IS THE PIECE THE MODEL WAS MISSING. What tells you anything about a creature is
 * not a colour profile bolted to the creature - that is an average over whatever cards it
 * happened to throw, and it describes nothing. What tells you something is which card it
 * is likely to throw right now, and what that card does.
 *
 * The conditional half was already measured and already thrown away. The learned rule in
 * the data pack carries the card counts on each side of its split - an ant at distance
 * reads {Ant Spit 255, Fell Scratch 5} - and the loader read only the per-colour pressure
 * summary beside it, which is the same averaging one level down.
 *
 * DETERMINISTIC, BY LARGEST DEFICIT. The search has to be repeatable, so the card
 * cannot be drawn at random; and taking the mix-weighted average of every card's effect
 * would be the aggregate again, with a Chomp's single heavy blow smeared into a
 * permanent drizzle. So the cards are dealt out in proportion, each action going to
 * whichever card is furthest behind its share. Over a fight the proportions come out at
 * the measured mix, and each individual action is a real card with its own numbers.
 */
public final class Repertoire {
    public final BeastMove[] cards;
    /** Unconditional share of actions, summing to 1 over cards. */
    public final double[] mix;

    /**
     * The learned split, where one was found: which feature, at what value, and the two
     * mixes either side of it. Null feature means no rule and {@link #mix} is used
     * throughout.
     */
    public final String condFeature;
    public final double condCut;
    public final double[] whenMix, elseMix;

    /**
     * The threshold gates the corpus reproduced, or null.
     *
     * A gate is a measured reading, not a guess: the estimator writes one per move that is
     * chosen more often once a colour's standing opening passes a cut, and marks
     * {@code reproduces} only where the split survives data it was not chosen on. Read them
     * through {@link #mixNow}, never directly - it applies only the vouched-for ones, and
     * only in the state the fight is actually in.
     */
    public final Gate[] gates;

    /**
     * What it throws given the fight as it stands, where the corpus supports it; null otherwise.
     *
     * A small decision tree over both sides' openings and initiative, published by the estimator
     * only where it beat the in-reach mix on decisions it was not grown on. Read through
     * {@link #mixNow}, which falls back to the learned split and the mix when it is absent.
     */
    public final StateTree tree;

    /**
     * A decision tree over the state a simulated fight carries.
     *
     * Nodes are flat arrays: an interior node tests one feature against a cut and goes to
     * {@code above} or {@code below}; a leaf carries a mix over this repertoire's cards. The
     * features are the estimator's STATE_FEATURES, in points for openings and in points of
     * initiative - OUR initiative against the creature and ITS against us, per relation.
     */
    public static final class StateTree {
        public static final String[] FEATURES = {"our_g", "our_b", "our_y", "our_r",
                                                 "its_g", "its_b", "its_y", "its_r",
                                                 "our_ip", "its_ip",
                                                 "our_max", "our_sum", "our_open",
                                                 "its_max", "its_sum", "its_open"};
        /** An opening counted as standing for our_open / its_open - the estimator's STATE_OPEN. */
        public static final double OPEN = 20.0;
        /** Feature index per node, or -1 for a leaf. */
        public final int[] feature;
        public final double[] cut;
        public final int[] above, below;
        /** A leaf's mix over the repertoire's cards, null on an interior node. */
        public final double[][] leafMix;

        public StateTree(int[] feature, double[] cut, int[] above, int[] below, double[][] leafMix) {
            this.feature = feature;
            this.cut = cut;
            this.above = above;
            this.below = below;
            this.leafMix = leafMix;
        }

        /**
         * The least initiative its path through this tree already says the creature holds, 0 if none:
         * a mix read under "its_ip above 1" was measured where it held 2 or more.
         */
        public int itsIpFloor(Combatant me, Combatant self) {
            if((me == null) || (self == null))
                return(0);
            int n = 0, floor = 0;
            while(feature[n] >= 0) {
                boolean up = value(feature[n], me, self) > cut[n];
                if(up && (feature[n] == 9))
                    floor = Math.max(floor, (int)Math.floor(cut[n]) + 1);
                n = up ? above[n] : below[n];
            }
            return(floor);
        }

        /** The leaf's mix for this state, or null when a side is missing. Node 0 is the root. */
        public double[] eval(Combatant me, Combatant self) {
            if((me == null) || (self == null))
                return(null);
            int n = 0;
            while(feature[n] >= 0) {
                n = (value(feature[n], me, self) > cut[n]) ? above[n] : below[n];
            }
            return(leafMix[n]);
        }

        static double value(int f, Combatant me, Combatant self) {
            if(f < 4)
                return(me.openings[f]);
            if(f < 8)
                return(self.openings[f - 4]);
            if(f < 10)
                return((f == 8) ? me.ip : self.ip);
            /* The whole side at once - greatest, total, how many colours stand. See the estimator's
             * STATE_FEATURES for why these are offered beside the colours. */
            double[] o = (f < 13) ? me.openings : self.openings;
            double max = 0, sum = 0, open = 0;
            for(int c = 0; c < 4; c++) {
                max = Math.max(max, o[c]);
                sum += o[c];
                if(o[c] >= OPEN)
                    open++;
            }
            int k = (f - 10) % 3;
            return((k == 0) ? max : ((k == 1) ? sum : open));
        }
    }

    /** A reproduced threshold: boost {@code card}'s share by {@code lift} once past the cut. */
    public static final class Gate {
        public final int card, colour;
        public final boolean onTarget;
        public final double cut, lift;

        public Gate(int card, int colour, boolean onTarget, double cut, double lift) {
            this.card = card;
            this.colour = colour;
            this.onTarget = onTarget;
            this.cut = cut;
            this.lift = lift;
        }
    }

    public Repertoire(BeastMove[] cards, double[] mix, String condFeature, double condCut,
                      double[] whenMix, double[] elseMix) {
        this(cards, mix, condFeature, condCut, whenMix, elseMix, null);
    }

    public Repertoire(BeastMove[] cards, double[] mix, String condFeature, double condCut,
                      double[] whenMix, double[] elseMix, Gate[] gates) {
        this(cards, mix, condFeature, condCut, whenMix, elseMix, gates, null);
    }

    public Repertoire(BeastMove[] cards, double[] mix, String condFeature, double condCut,
                      double[] whenMix, double[] elseMix, Gate[] gates, StateTree tree) {
        this.tree = tree;
        this.cards = cards;
        this.mix = mix;
        this.condFeature = condFeature;
        this.condCut = condCut;
        this.whenMix = whenMix;
        this.elseMix = elseMix;
        this.gates = gates;
    }

    public boolean usable() {
        return((cards != null) && (cards.length > 0) && (mix != null));
    }

    /**
     * The mix in force for the state the fight is actually in.
     *
     * Falls back to the pooled mix whenever the rule cannot be read - no rule, or a rule
     * that splits on something a Combatant does not carry. Distance is the one that
     * costs: it decides several of the learned rules and there is no notion of standing
     * apart anywhere in this simulator, so those creatures fall back to their average
     * behaviour and the report should say so rather than pretending the rule fired.
     */
    public double[] mixNow(Combatant me, Combatant self) {
        return(affordable(gated(me, self), self, ipFloor(me, self)));
    }

    /**
     * The least initiative the mix in force was read at, from a tree path or a rule on its own
     * initiative - the cave angler's tree splits at 1 and 3, and seven species' rules at "holds a
     * point". Such a mix is already conditioned on paying, and dividing it again would count twice.
     */
    private int ipFloor(Combatant me, Combatant self) {
        if(self == null)
            return(0);
        if(tree != null) {
            double[] t = tree.eval(me, self);
            if((t != null) && (t.length == mix.length))
                return(tree.itsIpFloor(me, self));
        }
        if("foe_ip".equals(condFeature) && (whenMix != null) && (elseMix != null) && (self.ip > condCut))
            return((int)Math.floor(condCut) + 1);
        return(0);
    }

    /**
     * The mix with every card its thrower cannot pay for taken out - see BeastMove.ipCost.
     *
     * The game's own gate, not a learned one: a spender is never thrown short of its cost in the
     * corpus. What is left keeps its proportions. Where nothing would be left the mix stands as it
     * was, since an empty hand is a pool this model got wrong rather than a creature that stops.
     */
    private double[] affordable(double[] m, Combatant self, int floor) {
        if((self == null) || (cards == null) || FoeModel.legacy)
            return(m);
        double[] out = m.clone();
        boolean moved = false;
        double tot = 0;
        for(int i = 0; i < out.length; i++) {
            BeastMove c = (i < cards.length) ? cards[i] : null;
            if((c != null) && (c.ipCost > 0) && (out[i] > 0)) {
                /* Held back when it cannot pay; when it can, its share of EVERY decision over the
                 * share of decisions at which it could - the weight the corpus shows it thrown at. */
                out[i] = c.affordable(self.ip) ? (out[i] / canPay(c.ipCost, floor)) : 0.0;
                moved = true;
            }
            tot += Math.max(0.0, out[i]);
        }
        if(!moved || !(tot > 0))
            return(m);
        for(int i = 0; i < out.length; i++)
            out[i] = Math.max(0.0, out[i]) / tot;
        return(out);
    }

    /**
     * P(its initiative >= k) at the decisions its mix was read from, per k from 1; null where the
     * pack does not carry it. A spender's share is a share of every decision and it can be thrown
     * only at those where the creature held its cost, so {@link #mixNow} divides by this when it
     * can pay - a cave angler throws Bristle at 0.129 of all its actions and 0.268 of those holding
     * 3, and that rate is flat from 3 held to 7 (2026-09-23).
     */
    public double[] ipAtLeast;

    /**
     * The share of decisions at which it held {@code cost}, among those at which it held at least
     * {@code floor}; floored so a rare one is not blown up.
     */
    private double canPay(int cost, int floor) {
        if((ipAtLeast == null) || (cost <= floor))
            return(1.0);
        double p = atLeast(cost), q = atLeast(floor);
        if(!(q > 0))
            return(1.0);
        return(Math.max(CAN_PAY_FLOOR, Math.min(1.0, p / q)));
    }

    private double atLeast(int k) {
        if(k <= 0)
            return(1.0);
        return(ipAtLeast[Math.min(k, ipAtLeast.length) - 1]);
    }

    /** Below this a spender's affordability is too thin to scale its share by. */
    static final double CAN_PAY_FLOOR = 0.1;

    /** The state mix with the reproduced gates applied. */
    private double[] gated(Combatant me, Combatant self) {
        double[] base = baseMix(me, self);
        if((gates == null) || (gates.length == 0))
            return(base);
        double[] out = null;
        for(int g = 0; g < gates.length; g++) {
            Gate gate = gates[g];
            Combatant side = gate.onTarget ? me : self;
            if((side == null) || (gate.card < 0) || (gate.card >= base.length))
                continue;
            double standing = side.opening(gate.colour) * 100.0;
            if(standing > gate.cut) {
                if(out == null)
                    out = base.clone();
                out[gate.card] *= gate.lift;
            }
        }
        if(out == null)
            return(base);
        double tot = 0;
        for(int i = 0; i < out.length; i++)
            tot += Math.max(0.0, out[i]);
        if(tot <= 0)
            return(base);
        for(int i = 0; i < out.length; i++)
            out[i] = Math.max(0.0, out[i]) / tot;
        return(out);
    }

    /** The state tree, else the unconditional or learned split, before any reproduced gate. */
    private double[] baseMix(Combatant me, Combatant self) {
        if(tree != null) {
            double[] t = tree.eval(me, self);
            if((t != null) && (t.length == mix.length))
                return(t);
        }
        if((condFeature == null) || (whenMix == null) || (elseMix == null))
            return(mix);
        double v = ruleValue(condFeature, me, self);
        if(v < 0)
            return(mix);
        return((v > condCut) ? whenMix : elseMix);
    }

    /**
     * What a learned rule's feature reads in this state, or -1 where it cannot be read - a feature
     * the simulator does not carry (distance), or a side not handed in. THE ONE READER for the card
     * mix here and the pooled pressure in FoeModel.pressureNow, which each kept a copy.
     */
    public static double ruleValue(String feature, Combatant me, Combatant self) {
        if("foe_ip".equals(feature))
            return((self == null) ? -1 : self.ip);
        if("my_ip".equals(feature))
            return((me == null) ? -1 : me.ip);
        if("my_open".equals(feature))
            return((me == null) ? -1 : (biggest(me) * 100.0));
        if("foe_open".equals(feature))
            return((self == null) ? -1 : (biggest(self) * 100.0));
        return(-1);
    }

    /**
     * Which card it throws on action number `step`, in the state it is in.
     *
     * LARGEST DEFICIT, AGAINST THE TALLY THE CALLER CARRIES. After this action each card
     * is owed mix[i]*(step+1) of them, and the one furthest short of what it is owed goes
     * next. Ties break on the card's own order so the answer never depends on how the
     * array was built.
     *
     * WHY NOT LARGEST REMAINDER. The first version was stateless - it recomputed the deal
     * from the step number alone, allocating n actions by largest remainder (Hamilton) and
     * taking whichever card gained a seat between n and n+1. Largest remainder is not
     * house-monotone: the Alabama paradox lets a card hold a seat at n and lose it at n+1,
     * and the lost seat is never represented. The smallest counterexample is a [1/7, 3/7,
     * 3/7] mix, whose allocation is [1, 1, 1] at n=3 and [0, 2, 2] at n=4: card 1 loses
     * its seat, so it is never thrown, and 200 draws come out [57, 86, 57] against a
     * nominal [28.6, 85.7, 85.7]. At corpus scale the bear's Fell Scratch is dealt 14% of
     * actions where the measured mix says 29%. No stateless Hamilton schedule is
     * house-monotone, so the count is carried rather than re-derived.
     *
     * A null (or too-short) tally is replayed from this same rule, so a caller that cannot
     * carry one still deals the identical, deterministic sequence.
     */
    public int pick(Combatant me, Combatant self, int step, int[] thrown) {
        double[] m = mixNow(me, self);
        /* OWED ACTION BY ACTION, WHEN THE CALLER CARRIES IT. A tally twice the card count holds,
         * past the counts, what each card is owed so far in fixed point - each action adds the
         * share of the mix IN FORCE AT THAT ACTION. The plain rule below owes a card
         * mix * (step + 1), today's share applied to every action already taken, and once the mix
         * moves with the state that is a debt nobody ran up: a bat that threw no Wingbeat while we
         * stood shut would owe it 35% of the whole fight the moment green opened, and throw
         * nothing else until it caught up. For a mix that never moves the two are the same deal. */
        if((thrown != null) && (thrown.length >= 2 * m.length)) {
            int n = m.length;
            int best = -1;
            double bestDeficit = 0;
            for(int i = 0; (i < cards.length) && (i < n); i++) {
                thrown[n + i] += (int)Math.round(m[i] * OWED_SCALE);
                if(m[i] <= 0)
                    continue;
                double deficit = claim(thrown[n + i] / OWED_SCALE, thrown[i]);
                if((best < 0) || (deficit > bestDeficit)) {
                    best = i;
                    bestDeficit = deficit;
                }
            }
            return((best < 0) ? 0 : best);
        }
        int[] counts = ((thrown != null) && (thrown.length >= m.length))
            ? thrown : replay(m, step);
        int best = -1;
        double bestDeficit = 0;
        for(int i = 0; (i < cards.length) && (i < m.length); i++) {
            if(m[i] <= 0)
                continue;
            double deficit = claim(m[i] * (step + 1), counts[i]);
            if((best < 0) || (deficit > bestDeficit)) {
                best = i;
                bestDeficit = deficit;
            }
        }
        return((best < 0) ? 0 : best);
    }

    /**
     * Deal a card by what it is owed per throw it would then have made - owed / (thrown + 1) - rather
     * than by owed minus thrown. A quota deal (D'Hondt) and still house-monotone.
     *
     * WHY (2026-09-23). A creature draws its cards; a card of share p first comes up after 1/p actions
     * on average. The deficit rule deals it once p(k) passes what the leader is owed over its count,
     * near k = 1/(2p): a red deer at no initiative throws Low Horn Swipe 29% of the time, so the
     * first comes at the third or fourth action, and the deficit rule dealt it at the second. That
     * did not matter while a creature's initiative never moved. Now the swipe's point moves it onto
     * its other, quicker mix, and a card early is a branch early. The quota rule deals that swipe at
     * the third action; a mix that never moves comes out at the same proportions either way.
     */
    public static volatile boolean quotaDeal = true;

    private static double claim(double owed, int thrown) {
        return((quotaDeal && !FoeModel.legacy) ? (owed / (thrown + 1.0)) : (owed - thrown));
    }

    /**
     * The tally a caller that cannot carry one would have produced.
     *
     * Runs the deficit deal from zero for `step` actions. This is O(step) and is only
     * reached by callers that pass a null tally; the optimizer carries the real count on
     * its search node and never comes here.
     */
    private static int[] replay(double[] m, int step) {
        int[] counts = new int[m.length];
        for(int s = 0; s < step; s++) {
            int best = -1;
            double bestDeficit = 0;
            for(int i = 0; i < m.length; i++) {
                if(m[i] <= 0)
                    continue;
                double deficit = claim(m[i] * (s + 1), counts[i]);
                if((best < 0) || (deficit > bestDeficit)) {
                    best = i;
                    bestDeficit = deficit;
                }
            }
            if(best < 0)
                break;
            counts[best]++;
        }
        return(counts);
    }

    /** Fixed-point scale for what a card is owed; a fight of 20000 actions still fits an int. */
    static final double OWED_SCALE = 100000.0;

    /** The tally a planner carries for this repertoire: counts, then what each card is owed. */
    public int tallySize() {
        return(usable() ? (2 * cards.length) : 0);
    }

    private static double biggest(Combatant c) {
        double out = 0;
        for(int i = 0; i < 4; i++)
            out = Math.max(out, c.opening(i));
        return(out);
    }
}
