package haven.automated.combat;

import haven.combat.Advisor;
import haven.combat.Combatant;
import haven.combat.FoeModel;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Optimizer;
import haven.combat.Sim;
import haven.combat.data.Pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        /**
         * The range figure of whatever is in hand - 1.2 for a sword, 1.0 for a stone axe.
         *
         * A multiple of the unarmed reach, which the corpus confirms to a fifth of a unit:
         * see Formulas.UNARMED_REACH. It comes from the item's own tooltip rather than the
         * wiki table, because the tooltip is what the game is actually swinging with and
         * the table has no range column at all.
         */
        final double weaponRange;
        final boolean armed;
        /* Card resource -> the level it sits at in the deck we are fighting with. */
        final Map<String, Integer> levels;
        /**
         * The who=me buff resources as the recorder last sampled them, and whether a shield
         * is in hand. Both are set live by {@link CombatRecorder}, because neither is a
         * fight-start constant the way the attributes are.
         *
         * The held stance is the one Move among these buff resources that is a stance -
         * exactly one card is, per Move.stance - and it decides the block weight (Shield Up
         * 2.5x, Parry 0.8x) and, for two of them, the attack weight. Without it a prediction
         * prices a character who cannot exist: the live model read blockSkill 0, blockMult 1
         * and attackMult 1 (Combatant.java:66,91) while the offline tools applied the stance
         * as a Combatant property (CombatDeckSearch.withStance, Duel.java:138-139,
         * FoeModel.java:478-479). The shield is only for Shield Up, whose 2.5x falls to 0.5x
         * without one (Move.blockRequires).
         *
         * volatile: written from the message loop, read from wherever the advisor runs. */
        volatile String[] buffs = null;
        volatile boolean shield = false;
        /* Damage-dealing gloves worn at fight start, as a base and a quality - see
         * Combatant.gloveDamage. 0 when none are, or when the recorder did not say. */
        double gloveDamage = 0, gloveQl = 0;
        /* The resource of whatever resolved as the weapon, for the log to name it - null when
         * nothing did, which is the case that plans every weapon card away. */
        String weaponRes = null;

        /** What resolved in hand, or null bare-handed. */
        public String weapon() {
            return(weaponRes);
        }

        Me(double str, double agi, double unarmed, double melee,
           double armHard, double armSoft,
           double weaponDamage, double weaponQl, double weaponPen, boolean armed,
           Map<String, Integer> levels) {
            this(str, agi, unarmed, melee, armHard, armSoft, weaponDamage, weaponQl,
                 weaponPen, Double.NaN, armed, levels);
        }

        Me(double str, double agi, double unarmed, double melee,
           double armHard, double armSoft,
           double weaponDamage, double weaponQl, double weaponPen, double weaponRange,
           boolean armed, Map<String, Integer> levels) {
            this.weaponRange = weaponRange;
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
     *             null when none were read. Only ARMOUR PENETRATION is taken from it - see
     *             the loop below, where the reason the damage figure is not is measured
     *             rather than argued.
     */
    public static Me me(SortedMap<String, Integer> attrs, int armHard, int armSoft,
                        String[] handRes, double[] handQl, Map<String, Integer> levels,
                        java.util.List<Map<String, Double>> live) {
        load();
        if(attrs == null)
            return(null);
        double str = num(attrs, "str"), agi = num(attrs, "agi");
        double ua = num(attrs, "unarmed"), mc = num(attrs, "melee");
        double dmg = 0, pen = 0, weaponQl = 0, range = Double.NaN;
        String wres = null;
        boolean armed = false;
        /* Both hands, and whichever one resolves to a weapon wins. A shield or a tool in
         * the off hand finds nothing and is simply passed over - which is the point, since
         * scanning only the first occupied hand meant a shield in slot 6 hid the sword in
         * slot 7 and silently disabled every weapon prediction.
         *
         * PENETRATION FROM THE ITEM, DAMAGE FROM THE TABLE, and the split is measured.
         *
         * Penetration is a pure fraction of the weapon and nothing else scales it. The
         * item and the wiki agree exactly where both exist - 0.125 against the table's
         * "12.5" - so taking it from the item is free, and it rescues the four of
         * twenty-six weapons whose penetration the scraper never recorded.
         *
         * Damage is NOT interchangeable, and using it cost a factor of two before this
         * was caught. The tooltip's figure is the weapon's damage AT ITS QUALITY: a bronze
         * sword of quality 38.06 reports 176 against the table's base of 90, and
         * 90*sqrt(38.06/10) = 175.6. Formulas.rawDamage takes a BASE and applies quality
         * itself, through sqrt(sqrt(ql*str)/10) - a different functional form again - so
         * feeding it the tooltip figure applies quality twice. Three logged hits predicted
         * 2.18x the damage actually dealt, against a double-count of 176/90 = 1.96x.
         *
         * The base is probably recoverable as dmg/sqrt(ql/10) - that reproduces 90.2
         * against a table value of 90. One weapon at one quality cannot tell that form
         * from its neighbours, so it is not used; the figure is logged in the wpn event
         * and the experiment that would settle it is a second quality of any catalogued
         * weapon. Until then a weapon absent from the table still declines to predict,
         * which is the honest failure rather than a doubled one. */
        for(int i = 0; (handRes != null) && (i < handRes.length); i++) {
            if(handRes[i] == null)
                continue;
            Map<String, Double> got = ((live != null) && (i < live.size())) ? live.get(i) : null;
            Double lPen = (got == null) ? null : got.get("armpen");
            String base = handRes[i].substring(handRes[i].lastIndexOf('/') + 1);
            double[] w = (weapons == null) ? null : weapons.get(Pack.key(base));
            if(w == null)
                continue;
            /* armorpen is absent on four of the twenty-six weapons and the scraper keeps
             * that as null. An absent penetration is not a zero one - but the item knows
             * it even when the table does not, so the table's gap only stops us when the
             * tooltip has not loaded either. */
            double p = (lPen != null) ? lPen.doubleValue() : w[1];
            if(Double.isNaN(p))
                continue;
            /* And its reach, which only the item knows - the wiki table has no range
             * column. Absent leaves it NaN, which reads as the unarmed reach rather than
             * as a weapon that cannot touch anything. */
            Double lRange = (got == null) ? null : got.get("range");
            range = (lRange == null) ? Double.NaN : lRange.doubleValue();
            dmg = w[0];
            pen = p;
            weaponQl = ((handQl != null) && (i < handQl.length)) ? handQl[i] : 0;
            wres = handRes[i];
            armed = true;
            break;
        }
        /* Armour of -1 means the equipment widget could not be read, which is not the same
         * fact as wearing none. */
        Me out = new Me(str, agi, ua, mc, Math.max(0, armHard), Math.max(0, armSoft),
                        dmg, weaponQl, pen, range, armed,
                        (levels == null) ? new LinkedHashMap<String, Integer>() : levels);
        out.weaponRes = wres;
        return(out);
    }

    /** Glove resources whose damage adds to an unarmed blow; each has a row in the weapon table. */
    private static final String[] GLOVES = {"lynxclawgloves", "cutthroatknuckles"};

    /**
     * As above, with everything worn, as {res, ql} pairs in slot order - read for gloves.
     *
     * The weapon join above only ever looks in the hands; damage-dealing gloves are worn, and an
     * unarmed blow in them adds their own term (Combatant.gloveDamage). The same table carries
     * them - Lynx Claw Gloves at base 4, Cutthroat Knuckles at 5 - so the join is the same one.
     */
    public static Me me(SortedMap<String, Integer> attrs, int armHard, int armSoft,
                        String[] handRes, double[] handQl, Map<String, Integer> levels,
                        java.util.List<Map<String, Double>> live, String[] worn) {
        Me m = me(attrs, armHard, armSoft, handRes, handQl, levels, live);
        if((m == null) || (worn == null) || (weapons == null))
            return(m);
        for(int i = 0; (i + 1) < worn.length; i += 2) {
            if(worn[i] == null)
                continue;
            String base = worn[i].substring(worn[i].lastIndexOf('/') + 1);
            for(String g : GLOVES) {
                double[] w = g.equals(base) ? weapons.get(Pack.key(base)) : null;
                if(w == null)
                    continue;
                try {
                    double ql = Double.parseDouble(worn[i + 1]);
                    if(ql > 0) {
                        m.gloveDamage = w[0];
                        m.gloveQl = ql;
                        return(m);
                    }
                } catch(Exception e) {
                    /* a quality we could not read prices no gloves rather than guessed ones */
                }
            }
        }
        return(m);
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
        return(of(me, foeRes, moveRes, foeOpen, myIp, false));
    }

    /**
     * @param live for the fight view's damage numbers rather than the log: a creature whose skill is
     *             only bounded is priced at the hard end of its band, the way the live advice plans
     *             it. The logged prediction keeps refusing it - a bound is not a measurement.
     */
    public static Expect of(Me me, String foeRes, String moveRes, int[] foeOpen, int myIp,
                            boolean live) {
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
        if(needsWeapon(m) && !me.armed)
            return(null);

        Pack.Opponent o = find(foeRes);
        if((o == null) || !(o.simulable() || (live && !o.isPlayer() && bounded(o))))
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
        a.weaponRange = me.weaponRange;
        a.gloveDamage = me.gloveDamage;
        a.gloveQl = me.gloveQl;
        a.hp = a.maxHp = 100;
        a.ip = myIp;
        applyStance(a, me);

        /* The hardest reading the corpus allows, and where it measured the creature, the
         * hardest REAL one: toughest() assembles an animal that is simultaneously the most
         * defended, fastest, largest and strongest ever logged, and no such creature exists.
         * hardestReal() falls back to exactly that reading when the pack ships no rows, so a
         * prediction is never silently widened. */
        Combatant b = o.hardestReal();
        for(int c = 0; c < 4; c++) {
            if(foeOpen[c] > 0)
                b.open(c, shown(foeOpen[c]));
        }

        Sim sim = new Sim(a, b);
        Sim.Result r = sim.predict(a, m);
        if(!r.ok)
            return(null);
        return(new Expect(r.opened, r.dealt, r.grievous, r.cooldown, stamp));
    }

    /** What the model would have thrown, and the plan behind it. */
    public static final class Advised {
        public final String moveRes;
        public final long ticks;
        public final double hpLost;
        public final boolean killed;
        public final int frontier;
        public final String pack;

        Advised(String moveRes, long ticks, double hpLost, boolean killed, int frontier,
                String pack) {
            this.moveRes = moveRes;
            this.ticks = ticks;
            this.hpLost = hpLost;
            this.killed = killed;
            this.frontier = frontier;
            this.pack = pack;
        }
    }

    /**
     * What the model would have thrown into this state, WITHOUT throwing it.
     *
     * The same argument that made prediction logging worth building. A bot that cannot be
     * audited against what it expected is a bot whose failures are invisible - so before
     * anything acts on the advisor, the advisor's answer goes in the log beside the card a
     * person actually chose. Every disagreement is then a fact about the day rather than
     * something recomputed later against a pack that has since moved.
     *
     * It costs a beam search, measured at 4 ms at beam 60 against a wolf with a ten-card
     * deck, and it runs once per card thrown rather than once per frame.
     *
     * Null whenever anything needed is unknown, on the same terms as {@link #of}.
     */
    public static Advised advise(Me me, String foeRes, int[] foeOpen, int myIp,
                                 int beam, long horizon) {
        return(advise(me, new String[] {foeRes}, new int[][] {foeOpen}, null, myIp,
                      beam, horizon));
    }

    public static Advised advise(Me me, String[] foeRes, int[][] foeOpen, int myIp,
                                 int beam, long horizon) {
        return(advise(me, foeRes, foeOpen, null, myIp, beam, horizon));
    }

    /**
     * The same, against the whole crowd rather than the one we are aimed at.
     *
     * Which card is best depends on how many of them there are. Full Circle hits every
     * opponent in range and is an ordinary attack against one; advising from the sampled
     * opponent alone answers the one-opponent question in every fight, including the ones
     * where it is the wrong question.
     *
     * The sampled opponent comes FIRST, because the search kills down the array in order
     * and that is the one we are actually swinging at. A companion the pack cannot
     * simulate is left out of the fight rather than guessed at - the plan is then made
     * against fewer opponents than are really there, which is the same silence the
     * one-opponent version had, and better than a made-up animal. If the one we are aimed
     * at is the unknown one there is nothing to advise on at all.
     */
    public static Advised advise(Me me, String[] foeRes, int[][] foeOpen,
                                 double[] foeDist, int myIp, int beam, long horizon) {
        return(advise(me, foeRes, foeOpen, foeDist, null, myIp, beam, horizon));
    }

    /**
     * The same, with the initiative we hold against EACH opponent, aligned with foeRes.
     *
     * The game keeps initiative per relation, so points built on the first animal are not
     * points against the second. Null, or a negative entry, is read as {@code myIp} - which
     * is what a caller that only saw the sampled relation can honestly say.
     */
    public static Advised advise(Me me, String[] foeRes, int[][] foeOpen, double[] foeDist,
                                 int[] foeMyIp, int myIp, int beam, long horizon) {
        load();
        if((me == null) || !me.usable() || (byRes == null) || (foes == null))
            return(null);
        if((foeRes == null) || (foeRes.length == 0) || (foeOpen == null)
           || (foeOpen.length != foeRes.length))
            return(null);
        if((foeOpen[0] == null) || (foeOpen[0].length < 4))
            return(null);
        Pack.Opponent o = find(foeRes[0]);
        if((o == null) || !o.simulable() || (o.threat == null))
            return(null);

        List<Move> deck = ourDeck(me);
        if(deck == null)
            return(null);
        Combatant a = ourSide(me, myIp);

        List<Combatant> bs = new ArrayList<Combatant>();
        List<FoeModel> ms = new ArrayList<FoeModel>();
        List<Integer> ips = new ArrayList<Integer>();
        for(int i = 0; i < foeRes.length; i++) {
            Pack.Opponent oi = (i == 0) ? o : find(foeRes[i]);
            if((oi == null) || !oi.simulable() || (oi.threat == null)
               || (foeOpen[i] == null) || (foeOpen[i].length < 4))
                continue;
            Combatant bi = oi.hardestReal();
            /* WHERE IT IS STANDING, which decides whether a sweeping card reaches it. NaN
             * when the caller does not know, and that has to stay expressible: a model
             * that defaulted an unknown position to zero would put every animal inside
             * every swing, which is the error that made a crowd free. */
            bi.distance = ((foeDist != null) && (i < foeDist.length))
                ? foeDist[i] : Double.NaN;
            for(int c = 0; c < 4; c++) {
                if(foeOpen[i][c] > 0)
                    bi.open(c, shown(foeOpen[i][c]));
            }
            bs.add(bi);
            ms.add(oi.threat);
            ips.add(Integer.valueOf(((foeMyIp != null) && (i < foeMyIp.length)
                                     && (foeMyIp[i] >= 0)) ? foeMyIp[i] : myIp));
        }
        Combatant[] bb = bs.toArray(new Combatant[0]);
        FoeModel[] mm = ms.toArray(new FoeModel[0]);
        int[] ia = new int[ips.size()];
        for(int i = 0; i < ia.length; i++)
            ia[i] = ips.get(i).intValue();
        /* ONE SEARCH. This used to search, and then ask Advisor.next - which searches the same
         * thing again - on the message loop, once per card thrown. The pick is the same. */
        List<Optimizer.Plan> front = Optimizer.search(a, bb, deck, mm, beam, horizon, ia);
        Optimizer.Plan pick = Advisor.choose(front, Advisor.Aim.FASTEST, 0);
        if((pick == null) || pick.moves.isEmpty())
            return(null);
        return(new Advised(pick.moves.get(0).res, pick.ticks, pick.hpLost, pick.killed,
                           front.size(), stamp));
    }

    /**
     * The deck AS HELD: the cards at a level above zero, each at its own weighting, or null when
     * none resolves. A card the sheet does not know costs that card, not the deck.
     */
    private static List<Move> ourDeck(Me me) {
        List<Move> deck = new ArrayList<Move>();
        if(me.levels != null) {
            for(Map.Entry<String, Integer> e : me.levels.entrySet()) {
                if((e.getValue() == null) || (e.getValue() <= 0))
                    continue;
                Move m = byRes.get(e.getKey());
                if(m == null)
                    continue;
                /* A STANCE IS HELD, NOT THROWN (Move.stance). The offline tools apply the
                 * stance as a Combatant property and skip it in every deck walk
                 * (CombatDeckSearch.withStance; Duel.java:138-139 and :192-193;
                 * FoeModel.java:478-479); leaving it in the live deck let Optimizer and Sim
                 * plan to throw one, which no fight can do. It is applied to our side
                 * instead, by applyStance. */
                if(m.stance)
                    continue;
                if(needsWeapon(m) && !me.armed)
                    continue;
                deck.add((e.getValue() > 1) ? m.withMu(muAt(e.getValue())) : m);
            }
        }
        return(deck.isEmpty() ? null : deck);
    }

    /** Our side of a live plan, from the snapshot the recorder took, with the held stance. */
    private static Combatant ourSide(Me me, int myIp) {
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
        a.weaponRange = me.weaponRange;
        a.gloveDamage = me.gloveDamage;
        a.gloveQl = me.gloveQl;
        a.hp = a.maxHp = 100;
        a.ip = myIp;
        applyStance(a, me);
        return(a);
    }

    /**
     * What to throw against a PLAYER, from the cards we have SEEN them throw.
     *
     * A player's deck is never shown to us, and the pack holds no species for a person, so the
     * opponent's side is built from what they have actually thrown - in this fight and, for a
     * memorised player, in every fight before it (CombatRecorder.seenDeck).
     *
     * THEIR BODY IS PRICED AS OURS, and that is an assumption, stated rather than hidden. The
     * client shows us a person's cards as they throw them but never their skills, strength or
     * gear, so the opponent is modelled as a copy of our own character holding the cards seen.
     * The advice is therefore "against someone like us who fights with these cards", and the
     * pack stamp carries the number of cards it was built from so a log can say how thin that
     * was. Null until at least one of their cards resolves in the sheet.
     */
    public static Advised adviseAgainstPlayer(Me me, Map<String, Integer> seen, int[] foeOpen,
                                              int myIp, int beam, long horizon) {
        load();
        if((me == null) || !me.usable() || (byRes == null) || (seen == null) || seen.isEmpty()
           || (foeOpen == null) || (foeOpen.length < 4))
            return(null);
        List<Move> deck = ourDeck(me);
        if(deck == null)
            return(null);
        List<Move> theirs = new ArrayList<Move>();
        for(String res : seen.keySet()) {
            Move mv = byRes.get(res);
            if((mv != null) && !mv.stance)
                theirs.add(mv);
        }
        if(theirs.isEmpty())
            return(null);
        Combatant a = ourSide(me, myIp);
        Combatant b = ourSide(me, 0);
        a.penetrable = true;
        b.penetrable = true;
        for(int c = 0; c < 4; c++) {
            if(foeOpen[c] > 0)
                b.open(c, shown(foeOpen[c]));
        }
        FoeModel model = FoeModel.fromDeck(theirs, b, a.defenceWeight());
        List<Optimizer.Plan> front = Optimizer.search(a, b, deck, model, beam, horizon);
        Optimizer.Plan pick = Advisor.choose(front, Advisor.Aim.FASTEST, 0);
        if((pick == null) || pick.moves.isEmpty())
            return(null);
        return(new Advised(pick.moves.get(0).res, pick.ticks, pick.hpLost, pick.killed,
                           front.size(), stamp + "/seen" + theirs.size()));
    }

    /** One opponent as the live client sees it now. The first one handed to adviseLive is our target. */
    public static final class Seen {
        /** Its gob, which the caller aims at; 0 when the caller has none. */
        public final long gob;
        public final String res;
        /** Its openings in percentage points, as the client shows them. */
        public final int[] open;
        /** The initiative we hold against it. */
        public final int myIp;
        /** The initiative it holds against us - what a big blow of its own is paid with. */
        public final int foeIp;
        /** How far away it stands, in world units, or NaN. */
        public final double dist;
        /** Soft hitpoints already drawn as taken off it, from anyone. */
        public final double taken;
        /** For a player, the cards seen from them, or null. */
        public final Map<String, Integer> seen;
        /** Whether it may be aimed at - false once we have offered it peace. */
        public final boolean targetable;

        public Seen(String res, int[] open, int myIp, double dist, double taken,
                    Map<String, Integer> seen) {
            this(0, res, open, myIp, 0, dist, taken, seen, true);
        }

        public Seen(long gob, String res, int[] open, int myIp, int foeIp, double dist,
                    double taken, Map<String, Integer> seen, boolean targetable) {
            this.gob = gob;
            this.res = res;
            this.open = open;
            this.myIp = myIp;
            this.foeIp = foeIp;
            this.dist = dist;
            this.taken = taken;
            this.seen = seen;
            this.targetable = targetable;
        }
    }

    /** The live answer, and what it had to stand in for to give one. */
    public static final class Live {
        /** The card to throw, or null - and then {@link #why} says why not. */
        public final String moveRes;
        public final String why;
        public final long ticks;
        public final double hpLost, budget;
        public final boolean killed;
        /** Opponents planned against, how many of them through a stand-in, and how many were people. */
        public final int planned, proxied, players;
        /** Index, into the opponents handed in, of the one to aim at. 0 keeps the current target. */
        public final int target;
        /**
         * The worst single blow any opponent could land on our openings as they stand, the cap
         * it is held to, and the index of the opponent it comes from. NaN and -1 when our
         * hitpoints are unknown.
         */
        public final double danger, dangerCap;
        public final int threat;
        /**
         * Hitpoints the cheapest plan saves over the fastest against the chosen target, or NaN.
         * At or under {@link #NEGLIGIBLE_HP} there is nothing to defend for, and the advice plays
         * the fastest kill whatever the reserve or the next blow say.
         */
        public final double trade;
        /** The share of our maximum hitpoints the plan kept in hand. */
        public final double reserve;

        Live(String moveRes, String why, Optimizer.Plan plan, double budget, int planned,
             int proxied, int players, int target, double danger, double dangerCap, int threat,
             double trade, double reserve) {
            this.moveRes = moveRes;
            this.why = why;
            this.ticks = (plan == null) ? 0 : plan.ticks;
            this.hpLost = (plan == null) ? Double.NaN : plan.hpLost;
            this.killed = (plan != null) && plan.killed;
            this.budget = budget;
            this.planned = planned;
            this.proxied = proxied;
            this.players = players;
            this.target = target;
            this.danger = danger;
            this.dangerCap = dangerCap;
            this.threat = threat;
            this.trade = trade;
            this.reserve = reserve;
        }

        static Live none(String why) {
            return(new Live(null, why, null, Double.NaN, 0, 0, 0, 0, Double.NaN, Double.NaN, -1,
                            Double.NaN, Double.NaN));
        }
    }

    /**
     * The share of our maximum soft hitpoints a live plan keeps in hand, against creatures and
     * against people.
     *
     * A plan may cost what we have above the reserve and no more; when the fastest kill costs
     * more, a slower plan that closes our openings is thrown instead (Advisor.Aim.SURVIVE).
     * POLICY FIGURES, NOT MEASURED ONES, and they differ on purpose (James, 2026-09-15): a
     * hunt that ends at 30% health has gone badly in a game where the next thing can be along
     * any moment, while a fight with a person is expected to be traded down. So a creature fight
     * spends a quarter of the bar at most and a player fight can spend most of it.
     */
    public static final double RESERVE_PVE = 0.75, RESERVE_PVP = 0.30;

    /**
     * The largest single blow, as a share of maximum soft hitpoints, the live advice lets stand
     * without answering it.
     *
     * The plan's budget is a TOTAL over the fight and cannot see one blow: thirty points open in
     * several colours against a strong creature holding initiative is a single swing that takes a
     * fifth of the bar, and a plan that averages it over a long fight calls that fine. So the
     * worst card each opponent could throw next is priced against our openings as they stand -
     * at the top of its measured damage when it holds initiative - and past this share the
     * advice restores first.
     */
    public static final double HIT_CAP_PVE = 0.12, HIT_CAP_PVP = 0.25;

    /**
     * Hitpoints not worth slowing a kill down for.
     *
     * The reserve and the next-blow guard exist for fights where the fastest line and the
     * cheapest line really differ - a cave angler, where they are 155 hitpoints apart. In an
     * ordinary fight they are a hitpoint or so apart, and "taking 1~ damage on regular fights is
     * acceptable" (James, 2026-09-15): defending there buys nothing but time. So when the
     * cheapest plan saves this much or less over the fastest, the fastest is thrown, whatever
     * the reserve or the next blow say.
     */
    public static final double NEGLIGIBLE_HP = 2.0;

    /* How much better another target has to be before the advice changes who we are hitting:
     * a plan 15% quicker, or one that costs 5% of our bar less. Switching is not free - openings
     * built on the current target stay there, and a recommendation that flickered between two
     * near-equal targets would be useless to follow and worse for a bot to act on. */
    static final double SWITCH_TICKS = 0.85, SWITCH_HP_SHARE = 0.05;

    /* A restoration worth throwing into a threatened blow when nothing better is available
     * takes at least this share off it (0.90 = a tenth). */
    static final double RESTORE_HELPS = 0.90;

    /* An opponent further away than this cannot swing at us before we act again. */
    static final double THREAT_RANGE = 45.0;

    private static final class Built {
        final int at;
        final Seen s;
        final Combatant b;
        final FoeModel model, hard;

        Built(int at, Seen s, Combatant b, FoeModel model, FoeModel hard) {
            this.at = at;
            this.s = s;
            this.b = b;
            this.model = model;
            this.hard = hard;
        }
    }

    /**
     * What to throw now, in ANY fight - the question the live client has to answer.
     *
     * {@link #advise} is the audited question and stays exactly as it is: known opponents only,
     * fastest kill, from the deck at fight start, because its answer is logged and scored against
     * what a person threw. A recommendation on screen and a bot cannot go quiet whenever the pack
     * lacks a creature, and cannot ignore the state we are in, so this differs, each way stated:
     *
     * - AN UNKNOWN CREATURE IS PLANNED AS A STAND-IN, the median creature the pack can simulate by
     *   hitpoints (see {@link #proxy}), counted in {@link Live#proxied}.
     * - A PLAYER is planned from the cards seen from them, as {@link #adviseAgainstPlayer} does, and
     *   from our own deck when none have been seen - "someone like us", as before.
     * - THE DECK IS THE BAR, whatever is on it, each card at the level the fight window holds.
     * - OUR OWN STATE COUNTS: our openings and hitpoints, the damage already on each opponent.
     * - IT KEEPS A RESERVE: the fastest kill that leaves {@link #RESERVE_PVE} of our bar against
     *   creatures, {@link #RESERVE_PVP} once a person is in the fight (Advisor.Aim.SURVIVE).
     * - IT PICKS THE TARGET: the plan is searched with each opponent we may aim at taken first,
     *   and another target wins only when it is clearly better (SWITCH_TICKS, SWITCH_HP_SHARE).
     * - IT WATCHES THE NEXT BLOW: past {@link #HIT_CAP_PVE} it throws the restoration that shrinks
     *   that blow most. It never moves us: a creature follows wherever we go.
     * - AND ONLY WHERE IT PAYS: when the cheapest plan saves {@link #NEGLIGIBLE_HP} or less over the
     *   fastest, the fastest is thrown and neither the reserve nor the guard is consulted.
     *
     * @param bar  card resource to level for every card on the action bar; null or empty uses
     *             the deck at fight start
     * @param mine our openings in percentage points
     * @param shp  our soft hitpoints now, or NaN
     * @param mhp  our maximum soft hitpoints, or NaN
     * @param foes the current target first, then anyone else on us
     */
    public static Live adviseLive(Me me, Map<String, Integer> bar, int[] mine, double shp,
                                  double mhp, List<Seen> foes, int beam, long horizon) {
        return(adviseLive(me, bar, mine, shp, mhp, foes, beam, horizon, 0));
    }

    /**
     * The same, planned for the moment our cooldown ends rather than for now.
     *
     * The auto-fighter picks the next card WHILE the cooldown runs, so the game swings it the
     * instant the cooldown ends. A plan that assumed we could act at once would leave out every
     * swing the opponents get in before then - and those swings are what decide whether the next
     * card should be a restoration. With our side not ready for {@code readyIn} ticks the search
     * lets the opponents act first, exactly as it does between any two of our cards.
     */
    public static Live adviseLive(Me me, Map<String, Integer> bar, int[] mine, double shp,
                                  double mhp, List<Seen> foes, int beam, long horizon,
                                  long readyIn) {
        return(adviseLive(me, bar, mine, shp, mhp, foes, beam, horizon, readyIn, null));
    }

    /**
     * The same, planning with only {@code planCards} of the bar - see {@link #distill} - and every
     * restoration on it. Null plans with the whole bar. The next-blow guard always looks at the
     * whole bar's restorations.
     */
    public static Live adviseLive(Me me, Map<String, Integer> bar, int[] mine, double shp,
                                  double mhp, List<Seen> foes, int beam, long horizon,
                                  long readyIn, java.util.Set<String> planCards) {
        return(adviseLive(me, bar, mine, shp, mhp, foes, beam, horizon, readyIn, planCards, null));
    }

    /**
     * The same, holding {@code held} - the card the last answer showed against this target - unless
     * another is clearly better from the state as it now stands.
     *
     * WHY A HELD CARD. Every answer was chosen from scratch, and the best two first cards are often
     * a tick or two apart. In a party the target's openings move several times a second under
     * other people's cards, so each re-plan landed on the other side of that tie: replayed through
     * this method, three of James's party fights on 2026-09-16 changed the pick 20-29 times each,
     * a third of those changes went straight back within a second and a half, and one pick in five
     * flipped when a single colour of the target moved by one point. Nothing better was found by
     * any of that - the two cards were worth the same to the model - but a recommendation that
     * cannot be read is not followed, a combo whose second card keeps changing is not a combo, and
     * the bot re-presses on every change. So the card on screen stays unless the new best is
     * clearly better by the rule a target switch already has to meet (clearlyBetter), and the
     * next-blow guard below still overrides it, because a blow about to land is not a tie.
     */
    public static Live adviseLive(Me me, Map<String, Integer> bar, int[] mine, double shp,
                                  double mhp, List<Seen> foes, int beam, long horizon,
                                  long readyIn, java.util.Set<String> planCards, String held) {
        Setup su = new Setup();
        Live fail = prepare(su, me, bar, mine, shp, mhp, foes, readyIn);
        if(fail != null)
            return(fail);
        Seen t = foes.get(0);
        Combatant a = su.a;
        List<Built> built = su.built;
        List<Move> deck = su.deck;
        List<Move> planDeck = narrowed(deck, planCards);
        boolean hpKnown = su.hpKnown;
        int proxied = su.proxied, players = su.players;
        String standIn = su.standIn;
        boolean pvp = players > 0;
        double reserve = pvp ? RESERVE_PVP : RESERVE_PVE;
        double budget = hpKnown ? (shp - (reserve * mhp)) : Double.POSITIVE_INFINITY;

        /* WHO TO HIT FIRST. The search kills down the array in order, so each opponent we may aim
         * at is tried at the front and the plans compared on the same aim. */
        List<Optimizer.Plan> picks = new ArrayList<Optimizer.Plan>();
        List<List<Optimizer.Plan>> fronts = new ArrayList<List<Optimizer.Plan>>();
        List<List<Optimizer.Plan>> everys = new ArrayList<List<Optimizer.Plan>>();
        List<Integer> pickAt = new ArrayList<Integer>();
        Optimizer.Plan cur = null;
        for(int k = 0; k < built.size(); k++) {
            if((k > 0) && !built.get(k).s.targetable)
                continue;
            List<Built> order = new ArrayList<Built>(built.size());
            order.add(built.get(k));
            for(int j = 0; j < built.size(); j++) {
                if(j != k)
                    order.add(built.get(j));
            }
            Combatant[] bb = new Combatant[order.size()];
            FoeModel[] mm = new FoeModel[order.size()];
            int[] ia = new int[order.size()];
            for(int j = 0; j < order.size(); j++) {
                bb[j] = order.get(j).b;
                mm[j] = order.get(j).model;
                ia[j] = order.get(j).s.myIp;
            }
            List<Optimizer.Plan> every = (held == null) ? null : new ArrayList<Optimizer.Plan>();
            List<Optimizer.Plan> front = Optimizer.search(a, bb, planDeck, mm, beam, horizon, ia, every);
            Optimizer.Plan p = Advisor.choose(front, Advisor.Aim.SURVIVE, budget);
            if(p == null)
                continue;
            /* Nothing worth defending for against this order: the fastest line, whatever the
             * reserve said. */
            if(tradeOf(front) <= NEGLIGIBLE_HP)
                p = Advisor.choose(front, Advisor.Aim.FASTEST, 0);
            picks.add(p);
            fronts.add(front);
            everys.add(every);
            pickAt.add(Integer.valueOf(k));
            if(k == 0)
                cur = p;
        }
        if(picks.isEmpty())
            return(new Live(null, "no plan reached the horizon", null, budget, built.size(),
                            proxied, players, 0, Double.NaN, Double.NaN, -1, Double.NaN, reserve));
        Optimizer.Plan best = Advisor.choose(picks, Advisor.Aim.SURVIVE, budget);
        int bi = picks.indexOf(best);
        int bestK = pickAt.get(bi).intValue();
        if((bestK != 0) && (cur != null) && t.targetable
           && !clearlyBetter(best, cur, hpKnown ? mhp : 100)) {
            bi = pickAt.indexOf(Integer.valueOf(0));
            best = cur;
            bestK = 0;
        }
        List<Optimizer.Plan> front = fronts.get(bi);
        double trade = tradeOf(front);

        /* THE HELD CARD - see the javadoc. Its best line is chosen exactly as the pick was, from the
         * plans that open with it, and it stays unless the pick is clearly better. */
        Optimizer.Plan pick = best;
        boolean kept = false;
        if((held != null) && !best.moves.isEmpty() && !held.equals(best.moves.get(0).res)
           && (everys.get(bi) != null)) {

            List<Optimizer.Plan> opens = new ArrayList<Optimizer.Plan>();
            for(Optimizer.Plan q : everys.get(bi)) {
                if(!q.moves.isEmpty() && held.equals(q.moves.get(0).res))
                    opens.add(q);
            }
            if(!opens.isEmpty()) {
                List<Optimizer.Plan> hf = Optimizer.frontier(opens);
                Optimizer.Plan keep = (trade <= NEGLIGIBLE_HP)
                    ? Advisor.choose(hf, Advisor.Aim.FASTEST, 0)
                    : Advisor.choose(hf, Advisor.Aim.SURVIVE, budget);
                if((keep != null) && !cardClearlyBetter(best, keep, hpKnown ? mhp : 100)) {
                    best = keep;
                    kept = true;
                }
            }
        }

        String move = best.moves.isEmpty() ? null : best.moves.get(0).res;
        /* Which answer this is: the fastest kill outright, a slower one the reserve forced, or -
         * nothing fitting - the cheapest. */
        Optimizer.Plan fastest = Advisor.choose(front, Advisor.Aim.FASTEST, 0);
        String why;
        if(move == null)
            why = "the best plan throws nothing";
        else if((best == fastest) || (kept && (fastest != null) && (best.ticks <= fastest.ticks)))
            why = "fastest kill";
        else if(!Double.isNaN(best.hpLost) && (best.hpLost > budget))
            why = "least damage";
        else
            why = "fastest kill that keeps the reserve";
        if(kept && (move != null))
            why = why + " (held: " + pick.moves.get(0).name + " is not clearly better)";
        if(bestK != 0)
            why = "switch to " + shortName(built.get(bestK).s.res) + ": " + why;
        if(proxied > 0)
            why = why + ", " + proxied + " unknown planned as " + standIn;
        /* SAID, NOT SILENT. With nothing resolved in hand every weapon card is out of the deck and
         * the plan is built from the unarmed ones alone - which is a different fight, and used to
         * look like the advice simply preferring a weak card. */
        if(!me.armed)
            why = why + " (planning unarmed)";

        /* THE NEXT BLOW. Only with our hitpoints known - a cap is a share of them - and only
         * acted on where defending saves more than a negligible amount. */
        double danger = Double.NaN, cap = Double.NaN;
        int threat = -1;
        if(hpKnown) {
            cap = (pvp ? HIT_CAP_PVP : HIT_CAP_PVE) * mhp;
            double[] w = worstHits(a, built);
            danger = 0;
            for(int i = 0; i < w.length; i++) {
                if(w[i] > danger) {
                    danger = w[i];
                    threat = built.get(i).at;
                }
            }
            if((danger > cap) && (trade > NEGLIGIBLE_HP)) {
                Built tb = built.get(bestK);
                Move fix = null;
                double fixed = danger;
                for(Move m : deck) {
                    if(!reducesOurs(m))
                        continue;
                    Combatant ac = a.copy();
                    ac.ip = tb.s.myIp;
                    ac.readyAt = 0;
                    Sim sim = new Sim(ac, tb.b.copy());
                    if(!sim.use(ac, m).ok)
                        continue;
                    double d2 = max(worstHits(ac, built));
                    if(d2 < fixed) {
                        fixed = d2;
                        fix = m;
                    }
                }
                String big = "a " + Math.round(danger) + " hp blow is possible";
                /* A restoration that takes at least a tenth off the blow, the best of them. It
                 * need not bring the blow under the cap: openings spread over every colour are
                 * closed by no one card, and the best of them still beats swinging into it. The
                 * advice never moves us - a creature follows wherever we go, and moving only
                 * stops our openings falling. */
                if((fix != null) && (fixed <= (RESTORE_HELPS * danger))) {
                    move = fix.res;
                    why = big + " - " + fix.name + " first";
                    bestK = 0;
                } else {
                    why = why + "; " + big + " and nothing on the bar answers it";
                }
            }
        }
        return(new Live(move, why, best, budget, built.size(), proxied, players,
                        built.get(bestK).at, danger, cap, threat, trade, reserve));
    }

    /** Our side, every opponent as the model sees it, and the bar as a deck - built once per ask. */
    private static final class Setup {
        Combatant a;
        List<Built> built;
        List<Move> deck;
        boolean hpKnown;
        int proxied, players;
        String standIn;
    }

    /** Fills {@code su} for this fight; returns the refusal when it cannot be planned, else null. */
    private static Live prepare(Setup su, Me me, Map<String, Integer> bar, int[] mine, double shp,
                                double mhp, List<Seen> foes, long readyIn) {
        load();
        if((me == null) || !me.usable() || (byRes == null) || (foes == null) || foes.isEmpty())
            return(Live.none("nothing to plan with"));
        List<Move> deck = liveDeck(me, bar);
        if(deck == null)
            return(Live.none("no card on the bar is in the move sheet"));
        Seen t = foes.get(0);
        if((t == null) || (t.open == null) || (t.open.length < 4))
            return(Live.none("the target's openings are not known"));
        boolean hpKnown = (shp > 0) && (mhp > 0);
        Combatant a = ourSide(me, t.myIp);
        a.readyAt = Math.max(0, readyIn);
        if(hpKnown) {
            a.hp = shp;
            a.maxHp = mhp;
        }
        for(int c = 0; (mine != null) && (mine.length >= 4) && (c < 4); c++) {
            if(mine[c] > 0)
                a.open(c, shown(mine[c]));
        }

        List<Built> built = new ArrayList<Built>();
        int proxied = 0, players = 0;
        String standIn = null;
        for(int i = 0; i < foes.size(); i++) {
            Seen s = foes.get(i);
            if((s == null) || (s.open == null) || (s.open.length < 4))
                continue;
            Combatant b;
            FoeModel model, hard;
            if(isPlayerRes(s.res)) {
                b = ourSide(me, 0);
                b.hp = b.maxHp = hpKnown ? mhp : 100;
                b.penetrable = true;
                a.penetrable = true;
                List<Move> theirs = new ArrayList<Move>();
                for(String r : (s.seen == null) ? java.util.Collections.<String>emptySet() : s.seen.keySet()) {
                    Move mv = byRes.get(r);
                    if((mv != null) && !mv.stance)
                        theirs.add(mv);
                }
                model = hard = FoeModel.fromDeck(theirs.isEmpty() ? deck : theirs, b,
                                                 a.defenceWeight());
                players++;
            } else {
                Pack.Opponent o = known(s.res);
                if(o == null) {
                    o = proxy();
                    if(o == null) {
                        if(i == 0)
                            return(Live.none("the pack has no creature to stand in for this one"));
                        continue;
                    }
                    proxied++;
                    standIn = o.toString();
                    model = hard = o.threat;
                } else {
                    model = o.threat;
                    /* The top of its measured damage, for the one blow that has to be survived.
                     * The plan prices the fight at the median; one swing is priced at the worst. */
                    hard = (o.threatHi != null) ? o.threatHi : o.threat;
                }
                b = o.hardestReal();
            }
            b.distance = s.dist;
            for(int c = 0; c < 4; c++) {
                if(s.open[c] > 0)
                    b.open(c, shown(s.open[c]));
            }
            /* What has already been taken off it. Never to zero: the relation is still there,
             * so whatever is left of it is still standing. */
            if((s.taken > 0) && (b.hp > 0))
                b.hp = Math.max(1, b.hp - s.taken);
            built.add(new Built(i, s, b, model, hard));
        }
        if(built.isEmpty() || (built.get(0).at != 0))
            return(Live.none("the target could not be planned"));
        su.a = a;
        su.built = built;
        su.deck = deck;
        su.hpKnown = hpKnown;
        su.proxied = proxied;
        su.players = players;
        su.standIn = standIn;
        return(null);
    }

    /** The beam distill() searches its candidate subsets at. */
    static final int DISTILL_BEAM = 20;

    /**
     * The cards of the bar worth planning this fight with, or null when the whole bar plans as well.
     *
     * A BIG BAR PLANS WORSE THAN A SMALL ONE IN A LONG FIGHT, and the search is the reason, not the
     * cards. A deck holding every card cannot truly do worse than one holding three of them, yet
     * with Full Circle added to Shield Up, Sideswipe and Uppercut a bear plans at 832 ticks against
     * 510 at beam 20 and 728 at beam 60, and only a beam of 1000 finds its way back to 510. Full
     * Circle's lines look good early and fill the beam; the line that wins is pruned before it pays.
     * Tuning the ranking was tried (charging a card's cooldown to the rate) and moved nothing.
     *
     * So the cards are chosen per fight, by FORWARD SELECTION: start from none, add whichever card
     * gives the quickest kill (fewer hitpoints on a tie), and stop when no card improves it. The
     * subsets searched are small, so this is cheap - 0.1 to 0.25 s once per matchup - and over ten
     * matchups it recovered the specialist deck's plan every time (bear 688 -> 510 ticks with a
     * ten-card bar, moose 646 -> 484, wolf 618 -> 489, cave angler 1050 -> 822). The subset is kept
     * only if it plans no worse than the whole bar at the planning beam; the planner adds every
     * restoration back, so the reserve and the guard still have them.
     */
    public static java.util.Set<String> distill(Me me, Map<String, Integer> bar, int[] mine,
                                                double shp, double mhp, List<Seen> foes, int beam,
                                                long horizon) {
        Setup su = new Setup();
        if(prepare(su, me, bar, mine, shp, mhp, foes, 0) != null)
            return(null);
        int n = su.built.size();
        Combatant[] bb = new Combatant[n];
        FoeModel[] mm = new FoeModel[n];
        int[] ia = new int[n];
        for(int j = 0; j < n; j++) {
            bb[j] = su.built.get(j).b;
            mm[j] = su.built.get(j).model;
            ia[j] = su.built.get(j).s.myIp;
        }
        List<Move> chosen = new ArrayList<Move>();
        Optimizer.Plan have = null;
        while(chosen.size() < su.deck.size()) {
            Move add = null;
            Optimizer.Plan addPlan = have;
            for(Move m : su.deck) {
                if(chosen.contains(m))
                    continue;
                List<Move> trial = new ArrayList<Move>(chosen);
                trial.add(m);
                Optimizer.Plan p = Advisor.choose(Optimizer.search(su.a, bb, trial, mm, DISTILL_BEAM,
                                                                   horizon, ia),
                                                  Advisor.Aim.FASTEST, 0);
                if(quicker(p, addPlan)) {
                    add = m;
                    addPlan = p;
                }
            }
            if(add == null)
                break;
            chosen.add(add);
            have = addPlan;
        }
        if(chosen.isEmpty() || (chosen.size() == su.deck.size()))
            return(null);
        java.util.Set<String> cards = new java.util.LinkedHashSet<String>();
        for(Move m : chosen)
            cards.add(m.res);
        Optimizer.Plan sub = Advisor.choose(Optimizer.search(su.a, bb, narrowed(su.deck, cards), mm,
                                                             beam, horizon, ia),
                                            Advisor.Aim.FASTEST, 0);
        Optimizer.Plan whole = Advisor.choose(Optimizer.search(su.a, bb, su.deck, mm, beam, horizon,
                                                               ia),
                                              Advisor.Aim.FASTEST, 0);
        return(quicker(whole, sub) ? null : cards);
    }

    /** The bar narrowed to these cards and every restoration on it; the whole bar for null or nothing. */
    private static List<Move> narrowed(List<Move> deck, java.util.Set<String> cards) {
        if((cards == null) || cards.isEmpty())
            return(deck);
        List<Move> out = new ArrayList<Move>();
        for(Move m : deck) {
            if(cards.contains(m.res) || reducesOurs(m))
                out.add(m);
        }
        return(out.isEmpty() ? deck : out);
    }

    /** A kill beats no kill; then fewer ticks; then fewer hitpoints. */
    private static boolean quicker(Optimizer.Plan a, Optimizer.Plan b) {
        if(a == null)
            return(false);
        if(b == null)
            return(true);
        if(a.killed != b.killed)
            return(a.killed);
        if(a.ticks != b.ticks)
            return(a.ticks < b.ticks);
        return(!Double.isNaN(a.hpLost) && !Double.isNaN(b.hpLost) && (a.hpLost < (b.hpLost - 1e-9)));
    }

    /** Hitpoints the cheapest plan on this frontier saves over the fastest; 0 when unknown. */
    private static double tradeOf(List<Optimizer.Plan> front) {
        Optimizer.Plan fastest = Advisor.choose(front, Advisor.Aim.FASTEST, 0);
        Optimizer.Plan cheapest = Advisor.choose(front, Advisor.Aim.SAFEST, 0);
        if((fastest == null) || (cheapest == null) || Double.isNaN(fastest.hpLost)
           || Double.isNaN(cheapest.hpLost))
            return(0);
        return(Math.max(0, fastest.hpLost - cheapest.hpLost));
    }

    /**
     * Whether a plan opening with another card is worth leaving the held card for: the target
     * rule, and where neither line kills inside the horizon - a cave angler, often - the one that
     * leaves the opponent clearly lower, or costs us clearly less. The target rule alone calls two
     * unkilled lines equal, which would hold a card forever where nothing kills.
     */
    private static boolean cardClearlyBetter(Optimizer.Plan other, Optimizer.Plan cur, double scale) {
        if(!other.killed && !cur.killed) {
            if(other.foeHp <= (SWITCH_TICKS * cur.foeHp))
                return(true);
            return(!Double.isNaN(other.hpLost) && !Double.isNaN(cur.hpLost)
                   && (other.hpLost <= (cur.hpLost - Math.max(3.0, SWITCH_HP_SHARE * scale))));
        }
        return(clearlyBetter(other, cur, scale));
    }

    /** Whether another target's plan is worth leaving the current one for. */
    private static boolean clearlyBetter(Optimizer.Plan other, Optimizer.Plan cur, double scale) {
        if(other.killed && !cur.killed)
            return(true);
        if(!other.killed)
            return(false);
        if(other.ticks <= (SWITCH_TICKS * cur.ticks))
            return(true);
        /* NO SLOWER AND CHEAPER is no trade at all, so it needs no margin beyond a hitpoint. A
         * nearly dead heavy hitter beside a fresh fox is exactly this: the whole crowd dies in
         * the same time either way, and the order that drops the hitter first stops its swings. */
        if((other.ticks <= cur.ticks) && !Double.isNaN(other.hpLost) && !Double.isNaN(cur.hpLost)
           && (other.hpLost < (cur.hpLost - 1.0)))
            return(true);
        return(!Double.isNaN(other.hpLost) && !Double.isNaN(cur.hpLost)
               && (other.hpLost <= (cur.hpLost - Math.max(3.0, SWITCH_HP_SHARE * scale))));
    }

    /**
     * The worst blow each opponent could land next on this version of us - at the top of its
     * measured damage when it holds initiative against us, and nothing from one out of reach.
     */
    private static double[] worstHits(Combatant us, List<Built> built) {
        double[] out = new double[built.size()];
        for(int i = 0; i < out.length; i++) {
            Built x = built.get(i);
            if(!Double.isNaN(x.s.dist) && (x.s.dist > THREAT_RANGE))
                continue;
            FoeModel m = (x.s.foeIp > 0) ? x.hard : x.model;
            if(m == null)
                continue;
            out[i] = m.worstHit(us, us.defenceWeight(), x.b);
        }
        return(out);
    }

    private static double max(double[] v) {
        double out = 0;
        for(double d : v)
            out = Math.max(out, d);
        return(out);
    }

    /** Whether a card closes any of our own openings - a restoration. */
    static boolean reducesOurs(Move m) {
        for(int c = 0; c < 4; c++) {
            if(m.reduces[c] > 0)
                return(true);
        }
        return(false);
    }

    /** "gfx/kritter/wolf/wolf" as "wolf", for a reason a person reads. */
    public static String shortName(String res) {
        if(res == null)
            return("?");
        if(isPlayerRes(res))
            return("the player");
        return(res.substring(res.lastIndexOf('/') + 1));
    }

    /** The bar as a deck - see {@link #adviseLive}. Null when nothing on it can be planned. */
    private static List<Move> liveDeck(Me me, Map<String, Integer> bar) {
        if((bar == null) || bar.isEmpty())
            return(ourDeck(me));
        List<Move> deck = new ArrayList<Move>();
        for(Map.Entry<String, Integer> e : bar.entrySet()) {
            Move m = byRes.get(e.getKey());
            if((m == null) || m.stance)
                continue;
            if(needsWeapon(m) && !me.armed)
                continue;
            int lvl = (e.getValue() == null) ? 0 : e.getValue().intValue();
            if(lvl <= 0) {
                Integer held = (me.levels == null) ? null : me.levels.get(e.getKey());
                lvl = ((held == null) || (held.intValue() <= 0)) ? 1 : held.intValue();
            }
            deck.add((lvl > 1) ? m.withMu(muAt(lvl)) : m);
        }
        return(deck.isEmpty() ? null : deck);
    }

    /**
     * Whether this card cannot be thrown with nothing in hand.
     *
     * NOT {@code weight == WEAPON}, which is what the three gates here tested and which misses
     * the weapon cards that name a skill. The sheet says "Damage: According to weapon x N" for
     * exactly the cards that need one - Chop, Cleave, Full Circle, Quick Barrage, Raven's Bite,
     * Sideswipe, Sting, Storm of Swords - and every unarmed card prints a flat number instead, so
     * the damage share IS the requirement. Full Circle is the one the old test let through: it
     * names the melee skill, so it read as an unarmed-legal card and was planned bare-handed with
     * a zero-damage weapon. Found while replaying a fight where the advice looked wrong; it is a
     * separate fault from that one.
     */
    static boolean needsWeapon(Move m) {
        return(m.damageShare > 0);
    }

    static boolean isPlayerRes(String res) {
        return((res != null) && (res.indexOf("borka/body") >= 0));
    }

    /** The pack entry for a creature, only where a fight against it can be simulated. */
    private static Pack.Opponent known(String res) {
        if(isPlayerRes(res))
            return(null);
        Pack.Opponent o = find(res);
        if((o == null) || o.isPlayer() || (o.threat == null))
            return(null);
        return((o.simulable() || bounded(o)) ? o : null);
    }

    /**
     * A creature whose skill the corpus bounds on both sides without naming - bear, wolf, moose,
     * lynx, narwhal and more. Pack.simulable() refuses them, and that is right for a logged
     * prediction; the live advice used to plan them as the stand-in beaver instead, which is far
     * worse than planning them as themselves at the HARD end of the band. hardestReal() already
     * builds that end (the skill's upper bound), which is what CombatDeckSearch -bounded runs.
     */
    static boolean bounded(Pack.Opponent o) {
        return(o.hasSkill && o.hpBounded() && !Double.isNaN(o.skillLo) && !Double.isNaN(o.skillHi)
               && (o.skillLo > 0) && (o.skillHi >= o.skillLo));
    }

    private static volatile Pack.Opponent proxy = null;

    /**
     * The creature an unknown one is planned as: the median, by the hitpoints it is planned
     * with, of every creature the pack can simulate.
     *
     * A STAND-IN, NOT AN ESTIMATE. Nothing about the creature in front of us enters it except
     * its openings, its distance and the damage already on it. The median rather than the
     * hardest because the hardest creature on record is a mammoth-sized answer to a question
     * that is usually about something the size of a fox, and a plan against it would defend
     * against blows that never come. The count of opponents planned this way is reported with
     * every answer, so nothing downstream mistakes it for a measurement.
     */
    static Pack.Opponent proxy() {
        load();
        Pack.Opponent p = proxy;
        if((p != null) || (foes == null))
            return(p);
        List<Pack.Opponent> ok = new ArrayList<Pack.Opponent>();
        for(Pack.Opponent o : foes.values()) {
            if(!o.isPlayer() && o.simulable() && (o.threat != null) && !Double.isNaN(o.planHpHi()))
                ok.add(o);
        }
        if(ok.isEmpty())
            return(null);
        java.util.Collections.sort(ok, (x, y) -> Double.compare(x.planHpHi(), y.planHpHi()));
        proxy = p = ok.get(ok.size() / 2);
        return(p);
    }

    /**
     * Applies the held stance to our side of a prediction, by the rule the offline search uses.
     *
     * The same three fields {@code CombatDeckSearch.withStance} sets: the block skill the
     * stance names, its multiplier (with Shield Up's no-shield fallback), and the attack factor
     * two of the stances carry. See {@link Me#buffs} for why the stance is read from the buffs
     * the recorder sampled rather than from the deck.
     *
     * AND PARRY'S ANSWER TO A BLOW, on the fighter. Parry "when attacked" opens whoever swung.
     * Optimizer used to source that only from the cards in the thrown deck, and a stance is
     * filtered out of the deck, so a held Parry got its 0.8x block weight and none of the openings
     * it answers a swing with. Optimizer.search now reads Combatant.whenAttacked when the deck
     * carries no such card, so it is set here, as CombatDeckSearch.withStance sets it.
     */
    private static void applyStance(Combatant a, Me me) {
        String[] names = me.buffs;
        if((names == null) || (byRes == null))
            return;
        Move st = null;
        for(String r : names) {
            Move m = byRes.get(r);
            if((m != null) && m.stance) {
                st = m;
                break;
            }
        }
        if(st == null)
            return;
        a.blockMult = st.blockMult;
        if((st.blockRequires != null) && !me.shield && !Double.isNaN(st.blockMultWithout))
            a.blockMult = st.blockMultWithout;
        if(st.blockSkill != null)
            a.blockSkill = a.skill(st.blockSkill);
        a.attackMult = st.attackMult;
        for(int c = 0; c < 4; c++)
            a.whenAttacked[c] = st.whenAttackedOpens[c];
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
        playerUnsupported(res);
        return(null);
    }

    private static boolean playerNotice;

    /**
     * Names the one res that will never resolve, once, instead of failing silently.
     *
     * A player is stored under res gfx/borka/body but named body#&lt;gob&gt;, and `find` keys on
     * the resource segment or the full name, so the two never meet. The caller then gets a
     * null prediction that is indistinguishable from "no measurement of this creature",
     * which is the reading a user would draw from it. That silent null is the defect; this
     * is the explicit statement of it, printed once per session.
     */
    private static void playerUnsupported(String res) {
        if(playerNotice || (res == null) || (res.indexOf("borka/body") < 0))
            return;
        playerNotice = true;
        System.err.println("combat pack: live PvP advice is UNSUPPORTED for " + res
            + " - a player is stored as body#<gob>, which this resource cannot name;"
            + " the advisor reports no prediction for it");
    }

    /**
     * The opening a displayed percentage most likely stands at: its midpoint, not its floor.
     *
     * The client reads an opening as floor(fraction * 100) - CombatRecorder.readOpenings does
     * exactly that - so a shown 55 is anywhere in [55, 56). Damage squares the opening, so the
     * floor under-prices every blow by about 2 * 0.5 / o of it. Over the corpus the midpoint
     * takes the replayed damage bias from +0.47 to -0.02 points and drawn killing blows from
     * +2.15 to +0.18. A shown 0 stays 0: nothing is standing. The recorder still LOGS what the
     * client showed; only the model reads the midpoint.
     */
    static double shown(int pct) {
        return((pct > 0) ? (pct + 0.5) : 0.0);
    }

    /** Colour order, so a caller can build foeOpen without importing Formulas. */
    public static final int GREEN = Formulas.GREEN, BLUE = Formulas.BLUE,
        YELLOW = Formulas.YELLOW, RED = Formulas.RED;
}
