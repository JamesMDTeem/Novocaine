package haven.automated.combat;

import haven.combat.Combatant;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Sim;
import haven.combat.data.Pack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;

/**
 * What the model expects a move to do, computed in the client at the moment it is thrown.
 *
 * WHY THIS EXISTS. Everything else in this project measures the game and then, separately,
 * asks the model what it would have said. That is enough to find errors and it is not enough
 * to track them: recomputing an old fight against today's data pack silently rewrites the
 * history, so a fix can never be shown to have helped, because the "before" number moves with
 * it. A prediction written into the log at the time is a fact about what the model believed on
 * the day, and it stays one.
 *
 * WHY IT RUNS ON HUMAN PLAY. The obvious version of this loop has a bot fight, compares
 * expected against actual, and feeds the difference back. That fails in a way that looks like
 * success: a model that also CHOOSES the fights only ever gets asked about the cases it
 * already gets right, so moves it dislikes are never thrown, their errors are never measured,
 * and it goes on disliking them - while the residuals fall. Predicting against a person's
 * choices is what keeps the sample honest, and it costs nothing to collect.
 *
 * IT REFUSES MORE OFTEN THAN IT ANSWERS, on purpose. Every input it cannot resolve - an
 * unrecognised weapon, an opponent whose skill the corpus never recovered, a move not in the
 * sheet - produces no prediction rather than a prediction from a default. A default here would
 * enter the residuals as a large error by the model, when it is really a gap in the inputs,
 * and the one thing this file exists to produce is a residual that means what it says.
 *
 * This is the executor half: it touches {@code haven} only through {@link CombatRecorder}'s
 * already-collected numbers, and the model it drives imports nothing from the client.
 */
public final class Prediction {
    /* Loaded once. A client that was built without the data pack simply never predicts. */
    private static volatile Map<String, Move> moves = null;
    private static volatile Map<String, Pack.Opponent> foes = null;
    private static volatile Map<String, double[]> weapons = null;
    private static volatile Map<String, Move> byRes = null;
    private static volatile boolean loaded = false;
    private static volatile String stamp = null;

    private Prediction() {}

    private static synchronized void load() {
        if(loaded)
            return;
        loaded = true;
        try {
            moves = Pack.movesFromJar();
            foes = Pack.opponentsFromJar();
            weapons = Pack.weaponsFromJar();
            Map<String, Move> ix = new LinkedHashMap<String, Move>();
            for(Move m : moves.values()) {
                if(m.res != null)
                    ix.put(m.res, m);
            }
            byRes = ix;
            /* Identifies the data a prediction came from. Sizes rather than a hash: it costs
             * nothing, it changes whenever the pack does, and it is legible in a log without
             * a lookup table. */
            stamp = moves.size() + "m/" + foes.size() + "f/" + weapons.size() + "w";
        } catch(Exception e) {
            moves = null;
            foes = null;
            weapons = null;
            byRes = null;
        }
    }

    /**
     * The deck weighting a card at this level carries.
     *
     * Linear across the five levels - 1.0, 1.125, 1.25, 1.375, 1.5. Settled by a ladder of
     * eighteen consecutive Take Aims: the card's cooldown is base over the weighting,
     * floored, and linear reproduces all twenty-eight readings in the corpus where the
     * square-root curve this project used for a week reproduces eight.
     *
     * It matters here twice over. The weighting divides a card's cooldown and multiplies
     * its attack weight, so predicting a levelled card at 1.0 - which is what happened
     * until the deck levels were passed in - reports a Take Aim cooldown of 42 ticks where
     * the game gives 33, and understates the opening every levelled card makes.
     */
    public static double muAt(int level) {
        if(level < 1)
            return(1.0);
        return(1.0 + (0.5 * (Math.min(level, 5) - 1) / 4.0));
    }

    /** Which data pack a prediction came from, or null when none is loaded. */
    public static String pack() {
        load();
        return(stamp);
    }

    /**
     * Our own side of the fight, assembled once when a combat starts.
     *
     * Held as a snapshot rather than read per move, because the header is already built from
     * these same numbers and a prediction that disagreed with the header would be describing a
     * different character than the log says fought.
     */
    public static final class Me {
        final double str, agi, unarmed, melee, armHard, armSoft;
        final double weaponDamage, weaponQl, weaponPen;
        final boolean armed;
        /* Card resource -> the level it sits at in the deck we are fighting with. */
        final Map<String, Integer> levels;

        Me(double str, double agi, double unarmed, double melee,
           double armHard, double armSoft,
           double weaponDamage, double weaponQl, double weaponPen, boolean armed,
           Map<String, Integer> levels) {
            this.levels = levels;
            this.str = str;
            this.agi = agi;
            this.unarmed = unarmed;
            this.melee = melee;
            this.armHard = armHard;
            this.armSoft = armSoft;
            this.weaponDamage = weaponDamage;
            this.weaponQl = weaponQl;
            this.weaponPen = weaponPen;
            this.armed = armed;
        }

        /** Whether the stats needed for any prediction at all are present. */
        public boolean usable() {
            return((str > 0) && (agi > 0) && ((unarmed > 0) || (melee > 0)));
        }
    }

    /**
     * Builds our side from the attributes and gear the recorder already read.
     *
     * The weapon is joined from its resource BASENAME to the wiki's weapon table -
     * "gfx/invobjs/bronzesword" against "Bronze Sword" - which is a real join that can miss.
     * A miss leaves us unarmed rather than armed with a guess, and every weapon move then
     * declines to predict while the unarmed ones carry on.
     */
    public static Me me(SortedMap<String, Integer> attrs, int armHard, int armSoft,
                        String[] handRes, double[] handQl, Map<String, Integer> levels) {
        return(me(attrs, armHard, armSoft, handRes, handQl, levels, null));
    }

    /**
     * @param live the game's OWN figures per hand, from the item's weapon tooltips, or
     *             null when none were read. Preferred over the wiki table wherever it has
     *             both damage and penetration - see the loop below for why that ordering
     *             is the point rather than a convenience.
     */
    public static Me me(SortedMap<String, Integer> attrs, int armHard, int armSoft,
                        String[] handRes, double[] handQl, Map<String, Integer> levels,
                        java.util.List<Map<String, Double>> live) {
        load();
        if(attrs == null)
            return(null);
        double str = num(attrs, "str"), agi = num(attrs, "agi");
        double ua = num(attrs, "unarmed"), mc = num(attrs, "melee");
        double dmg = 0, pen = 0, weaponQl = 0;
        boolean armed = false;
        /* Both hands, and whichever one resolves to a weapon wins. A shield or a tool in
         * the off hand finds nothing and is simply passed over - which is the point, since
         * scanning only the first occupied hand meant a shield in slot 6 hid the sword in
         * slot 7 and silently disabled every weapon prediction.
         *
         * THE ITEM FIRST, THE TABLE SECOND. The wiki table is joined on the resource
         * basename, which is a real join that misses silently, and it has no penetration
         * at all for four of its twenty-six weapons - so a perfectly ordinary weapon could
         * decline to predict for want of a number the item in hand was carrying. Asking
         * the item removes both failures, and a weapon nobody has ever catalogued works
         * the same as one that has. The table stays as the fallback for a tooltip that had
         * not loaded yet. */
        for(int i = 0; (handRes != null) && (i < handRes.length); i++) {
            if(handRes[i] == null)
                continue;
            Map<String, Double> got = ((live != null) && (i < live.size())) ? live.get(i) : null;
            Double lDmg = (got == null) ? null : got.get("damage");
            Double lPen = (got == null) ? null : got.get("armpen");
            if((lDmg != null) && (lPen != null)) {
                dmg = lDmg.doubleValue();
                pen = lPen.doubleValue();
                weaponQl = ((handQl != null) && (i < handQl.length)) ? handQl[i] : 0;
                armed = true;
                break;
            }
            String base = handRes[i].substring(handRes[i].lastIndexOf('/') + 1);
            double[] w = (weapons == null) ? null : weapons.get(Pack.key(base));
            if(w == null)
                continue;
            /* armorpen is absent on four of the twenty-six weapons and the scraper keeps
             * that as null. An absent penetration is not a zero one, so a weapon whose
             * penetration nobody recorded cannot be predicted with. */
            if(Double.isNaN(w[1]))
                continue;
            dmg = w[0];
            pen = w[1];
            weaponQl = ((handQl != null) && (i < handQl.length)) ? handQl[i] : 0;
            armed = true;
            break;
        }
        /* Armour of -1 means the equipment widget could not be read, which is not the same
         * fact as wearing none. */
        return(new Me(str, agi, ua, mc, Math.max(0, armHard), Math.max(0, armSoft),
                      dmg, weaponQl, pen, armed,
                      (levels == null) ? new LinkedHashMap<String, Integer>() : levels));
    }

    private static double num(SortedMap<String, Integer> a, String k) {
        Integer v = a.get(k);
        return((v == null) ? 0 : v.doubleValue());
    }

    /** What the model expects, or null when any input is missing. */
    public static final class Expect {
        public final double[] opened;
        public final double dealt, grievous;
        public final long cooldown;
        public final String pack;

        Expect(double[] opened, double dealt, double grievous, long cooldown, String pack) {
            this.opened = opened;
            this.dealt = dealt;
            this.grievous = grievous;
            this.cooldown = cooldown;
            this.pack = pack;
        }
    }

    /**
     * Runs the move against the state the fight is actually in.
     *
     * @param foeRes    the opponent's resource, for the pack lookup
     * @param moveRes   the card thrown
     * @param foeOpen   the opponent's four openings in percentage POINTS, as the client shows
     * @param myIp      our initiative before the move
     * @return null whenever anything needed is unknown
     */
    public static Expect of(Me me, String foeRes, String moveRes, int[] foeOpen, int myIp) {
        load();
        if((me == null) || !me.usable() || (byRes == null) || (foes == null))
            return(null);
        if((moveRes == null) || (foeOpen == null) || (foeOpen.length < 4))
            return(null);
        Move m = byRes.get(moveRes);
        if(m == null)
            return(null);
        /* The card AS LEVELLED. The pack's sheet is character-free and so carries no deck
         * levels; without this every prediction runs a levelled card at weighting 1.0. */
        Integer lvl = (me.levels == null) ? null : me.levels.get(moveRes);
        if((lvl != null) && (lvl > 1))
            m = m.withMu(muAt(lvl));
        /* A weapon move with no resolved weapon has no damage and no attack weight. Predicting
         * it as if unarmed would be a different move. */
        if((m.weight == Move.Weight.WEAPON) && !me.armed)
            return(null);

        Pack.Opponent o = find(foeRes);
        if((o == null) || !o.simulable())
            return(null);

        Combatant a = new Combatant("me");
        a.str = me.str;
        a.agi = me.agi;
        a.unarmed = me.unarmed;
        a.melee = me.melee;
        a.armHard = me.armHard;
        a.armSoft = me.armSoft;
        a.weaponDamage = me.weaponDamage;
        a.weaponQl = me.weaponQl;
        a.weaponPen = me.weaponPen;
        a.hp = a.maxHp = 100;
        a.ip = myIp;

        /* The toughest reading the corpus allows. Every opponent number is an interval, and a
         * prediction has to pick one end or report two; picking the pessimistic end means a
         * residual that comes out negative is the interesting direction. */
        Combatant b = o.toughest();
        for(int c = 0; c < 4; c++) {
            if(foeOpen[c] > 0)
                b.open(c, foeOpen[c]);
        }

        Sim sim = new Sim(a, b);
        Sim.Result r = sim.predict(a, m);
        if(!r.ok)
            return(null);
        return(new Expect(r.opened, r.dealt, r.grievous, r.cooldown, stamp));
    }

    /**
     * The pack entry for a resource, by the same rule the estimator buckets on.
     *
     * Animals are keyed on the last path segment and players are kept apart by gob, so a
     * player's entry is never found here - which is correct: those entries describe one
     * individual from one session and say nothing about the person in front of us now.
     */
    private static Pack.Opponent find(String res) {
        if((res == null) || (foes == null))
            return(null);
        String last = res.substring(res.lastIndexOf('/') + 1);
        Pack.Opponent o = foes.get(last);
        if(o != null)
            return(o);
        /* "gfx/kritter/wildbees/beeswarm" is the swarm the corpus calls beeswarm, but some
         * creatures are named by their directory instead. Trying each segment costs nothing. */
        String[] parts = res.split("/");
        for(int i = parts.length - 1; i >= 0; i--) {
            o = foes.get(parts[i]);
            if(o != null)
                return(o);
        }
        return(null);
    }

    /** Colour order, so a caller can build foeOpen without importing Formulas. */
    public static final int GREEN = Formulas.GREEN, BLUE = Formulas.BLUE,
        YELLOW = Formulas.YELLOW, RED = Formulas.RED;
}
