package haven.combat.data;

import haven.combat.Combatant;
import haven.combat.FoeModel;
import haven.combat.Formulas;
import haven.combat.Move;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the versioned combat data into the model's own types.
 *
 * ADR-0002 keeps the constants in JSON and the model in Java, and this is the join. It reads
 * {@code data/combat/moves_sheet.json} - the character-free half of a client deck dump, so a
 * move's weight, openings, damage and cooldown, with nothing about who dumped it - and
 * {@code data/combat/opponents.json}, which {@code tools/combat/estimate.py} writes from the
 * logged corpus.
 *
 * This package may depend on a JSON parser; {@code haven.combat} itself may not, which is why
 * the loading lives here and not there. Neither depends on anything in {@code haven}.
 *
 * Every opponent value is an interval, and this preserves that all the way to the simulator.
 * {@link Opponent#toughest()} and {@link Opponent#weakest()} are the two ends, and a matchup
 * that is only winnable against one of them is a matchup whose answer is "not known", not
 * "yes".
 */
public final class Pack {
    private Pack() {}

    private static final Map<String, Integer> COLOUR = new LinkedHashMap<String, Integer>();
    static {
        COLOUR.put("green", Formulas.GREEN);
        COLOUR.put("blue", Formulas.BLUE);
        COLOUR.put("yellow", Formulas.YELLOW);
        COLOUR.put("red", Formulas.RED);
    }

    private static JSONObject read(Path p) throws IOException {
        return(new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
    }

    /**
     * The same files, from the classpath, for a client that has no repository around it.
     *
     * build.xml copies data/combat into the jar beside these classes, so the running game
     * carries the pack it was built with. Returns null rather than throwing when a file is
     * absent: a client built without the pack must lose the prediction, not the fight.
     */
    private static String slurp(String name) {
        try(java.io.InputStream in = Pack.class.getResourceAsStream(name)) {
            if(in == null)
                return(null);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for(int n = in.read(buf); n > 0; n = in.read(buf))
                bo.write(buf, 0, n);
            return(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        } catch(IOException e) {
            return(null);
        }
    }

    /** Every move in the packed sheet, or an empty map when the jar carries no pack. */
    public static Map<String, Move> movesFromJar() {
        String doc = slurp("moves_sheet.json");
        return((doc == null) ? new LinkedHashMap<String, Move>()
               : moves(new JSONObject(doc)));
    }

    /** Every opponent the corpus knows, from the jar. */
    public static Map<String, Opponent> opponentsFromJar() {
        String doc = slurp("opponents.json");
        return((doc == null) ? new LinkedHashMap<String, Opponent>()
               : opponents(new JSONObject(doc)));
    }

    /**
     * Weapons by their resource BASENAME - "bronzesword" for gfx/invobjs/bronzesword.
     *
     * The wiki names a weapon and the client knows only its resource, so the two are joined on
     * the name with everything but letters and digits removed. That is a real join and it can
     * miss: a weapon whose article title does not reduce to its resource name simply will not
     * be found, and the caller then declines to predict rather than predicting with a default
     * weapon, which would be a fabricated number wearing a measurement's clothes.
     */
    public static Map<String, double[]> weaponsFromJar() {
        Map<String, double[]> out = new LinkedHashMap<String, double[]>();
        String doc = slurp("weapons.json");
        if(doc == null)
            return(out);
        JSONArray arr = new JSONArray(doc);
        for(int i = 0; i < arr.length(); i++) {
            JSONObject w = arr.getJSONObject(i);
            String name = w.optString("name", null);
            if(name == null)
                continue;
            JSONObject dmg = w.optJSONObject("basedmg");
            JSONObject pen = w.optJSONObject("armorpen");
            if((dmg == null) || dmg.isNull("value"))
                continue;
            /* armorpen is genuinely absent on four of the twenty-six, and the scraper writes
             * null rather than zero there for exactly this reason. NaN carries that through -
             * a zero would be a claim that the weapon pierces nothing. */
            out.put(key(name), new double[] {
                dmg.getDouble("value"),
                ((pen == null) || pen.isNull("value")) ? Double.NaN
                    : (pen.getDouble("value") / 100.0)});
        }
        overlaySeen(out);
        return(out);
    }

    /**
     * What the client itself said about a weapon we have actually held, laid over the scrape.
     *
     * The wiki table can be wrong and is: it gives the stone axe 10% armour penetration and
     * the live {@code WeaponInfo} reads 0.20. The bronze sword agrees exactly at 12.5%, so it
     * is one wrong number rather than a units mismatch on our side, and there is no way to
     * tell which of the twenty-six others are wrong the same way.
     *
     * A weapon we have held needs no scraper, so where the two disagree the item wins. The
     * damage is the only fiddly part: the tooltip gives it QUALITY-SCALED, and the base is
     * recovered by dividing sqrt(ql/10) back out. That recovers the wiki's own base to within
     * a quarter of a percent on both weapons the corpus has - 90.21 against 90, and 29.93
     * against 30 - which is what makes the penetration disagreement a finding rather than a
     * sign that the arithmetic is off.
     *
     * Absent, this changes nothing: a client built without the file keeps the scrape.
     */
    private static void overlaySeen(Map<String, double[]> out) {
        String doc = slurp("weapons_seen.json");
        if(doc == null)
            return;
        JSONObject w = new JSONObject(doc).optJSONObject("weapons");
        if(w == null)
            return;
        for(String base : w.keySet()) {
            JSONObject e = w.optJSONObject(base);
            if(e == null)
                continue;
            JSONObject rb = e.optJSONObject("recovered_base");
            JSONArray pen = e.optJSONArray("armpen");
            double[] have = out.get(key(base));
            double dmg = (rb == null) ? Double.NaN : rb.optDouble("lo", Double.NaN);
            double p = ((pen == null) || (pen.length() == 0)) ? Double.NaN
                : pen.getDouble(0);
            /* A weapon read at two qualities that do not agree on the base is not overlaid:
             * that would mean the quality division is wrong, and the scrape is then the more
             * trustworthy of the two. */
            if((rb != null) && (Math.abs(rb.optDouble("hi", dmg) - dmg) > 0.5))
                dmg = Double.NaN;
            if(Double.isNaN(dmg) && Double.isNaN(p))
                continue;
            out.put(key(base), new double[] {
                Double.isNaN(dmg) ? ((have == null) ? Double.NaN : have[0]) : dmg,
                Double.isNaN(p) ? ((have == null) ? Double.NaN : have[1]) : p});
        }
    }

    /** A weapon or resource name reduced to letters and digits, for the join above. */
    public static String key(String s) {
        if(s == null)
            return(null);
        StringBuilder b = new StringBuilder();
        for(int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if(((c >= 'a') && (c <= 'z')) || ((c >= '0') && (c <= '9')))
                b.append(c);
        }
        return(b.toString());
    }

    /** Every move the sheet describes, by its display name. */
    public static Map<String, Move> moves(Path path) throws IOException {
        return(moves(read(path)));
    }

    private static Map<String, Move> moves(JSONObject doc) {
        Map<String, Move> out = new LinkedHashMap<String, Move>();
        JSONArray arr = doc.getJSONArray("moves");
        for(int i = 0; i < arr.length(); i++) {
            Move m = move(arr.getJSONObject(i));
            if(m != null)
                out.put(m.name, m);
        }
        return(out);
    }

    private static Integer colour(String name) {
        return(COLOUR.get(name));
    }

    private static double dbl(JSONObject o, String key, double dflt) {
        return(o.isNull(key) ? dflt : o.getDouble(key));
    }

    private static int integer(JSONObject o, String key, int dflt) {
        return(o.isNull(key) ? dflt : o.getInt(key));
    }

    private static Move move(JSONObject j) {
        String name = j.optString("name", null);
        if((name == null) || name.isEmpty())
            return(null);
        JSONArray types = j.optJSONArray("attack_types");
        /* An attack for COOLDOWN purposes, which is the only thing Kind.ATTACK decides -
         * see Move.isAttack. Having attack types is not the whole test, and the corpus
         * says so: Opportunity Knocks declares no attack type at all and its cooldown
         * still moves with the opponent. Base 45, it reports 41 ticks against ants, fox,
         * boar, badger, wolverine and adder - every one of them at the bottom of the
         * agility band - and 45 against a wildgoat, the one creature measured at roughly
         * our own agility. round(45 * 0.9) is 41 and round(45 * 1.0) is 45.
         *
         * What those two have that the true maneuvers do not is an attack SKILL. Zig-Zag
         * Ruse, Sidestep, Quick Dodge, Jump and Dash all declare neither, and none of
         * them has ever moved a tick across the whole corpus.
         *
         * Watch Its Moves is the untested half of this: same shape as Opportunity Knocks,
         * no attack type and an unarmed skill, but never once thrown at anything. It is
         * predicted to take the modifier, not measured to. */
        boolean attack = ((types != null) && (types.length() > 0))
            || !j.optString("attack_skill", "").isEmpty();

        Move.Builder b = Move.of(name).res(j.optString("res", null))
            .kind(attack ? Move.Kind.ATTACK : Move.Kind.MANEUVER);

        for(int i = 0; (types != null) && (i < types.length()); i++) {
            Integer c = colour(types.getJSONObject(i).optString("colour", null));
            if(c == null)
                continue;
            if(i == 0)
                b.school(c);
            else
                b.alsoSchool(c);
        }

        JSONArray ops = j.optJSONArray("openings");
        for(int i = 0; (ops != null) && (i < ops.length()); i++) {
            JSONObject o = ops.getJSONObject(i);
            Integer c = colour(o.optString("colour", null));
            if(c != null)
                b.opens(c, o.optDouble("pct", 0));
        }
        JSONArray self = j.optJSONArray("openings_on_self");
        for(int i = 0; (self != null) && (i < self.length()); i++) {
            JSONObject o = self.getJSONObject(i);
            Integer c = colour(o.optString("colour", null));
            if(c != null)
                b.opensSelf(c, o.optDouble("pct", 0));
        }

        /* "Reduces: 50% - mu Sweeping" is a SHARE of what is standing, not fifty points -
         * Zig-Zag Ruse took a Cornered of 55 to 27 and one of 26 to 13. Divided by 100 here
         * for that reason, where the openings above are left in percentage points. */
        JSONArray red = j.optJSONArray("reduces");
        for(int i = 0; (red != null) && (i < red.length()); i++) {
            JSONObject o = red.getJSONObject(i);
            Integer c = colour(o.optString("colour", null));
            if(c != null)
                b.reduces(c, o.optDouble("pct", 0) / 100.0);
        }

        b.damageShare(dbl(j, "damage_share", 0)).flatDamage(dbl(j, "damage_flat", 0))
            .grievous(dbl(j, "grievous_pct", 0) / 100.0)
            .boostGreatest(j.isNull("boost_greatest") ? 0.0 : dbl(j, "boost_greatest", 0))
            /* "Initiative points: N" is what the move SPENDS - see Move.ipCost. The trailing
             * number of a "4+2" comes through separately and unresolved. */
            .ipCost(integer(j, "initiative", 0))
            .ipExtra(integer(j, "initiative_extra", 0))
            .foeIpGain(integer(j, "opponent_initiative", 0))
            .cooldown(dbl(j, "cooldown", 0))
            .cooldownMu(j.optBoolean("cooldown_mu", false))
            /* Take Aim's "increases by 20% for each Point of Initiative". Omitting this
             * used to leave every packed move at zero, so Take Aim reported its base 30 at
             * any initiative while the logs show it reaching 60. */
            .ipScale(dbl(j, "ip_scale", 0))
            .weightMu(dbl(j, "weight_mult", 1.0))
            /* What holding this maneuver does to its user's own attacks - Combat
             * Meditation's 25%, Oak Stance's 50%. Applied through Combatant.attackMult by
             * whoever decides which stance is up, not by the move that is being thrown. */
            .attackMult(dbl(j, "attack_mult", 1.0));

        String skill = j.optString("attack_skill", null);
        b.weight("unarmed".equals(skill) ? Move.Weight.UNARMED
                 : "melee".equals(skill) ? Move.Weight.MELEE
                 : attack ? Move.Weight.WEAPON : Move.Weight.NONE);

        /* Gains and their conditions are written as prose on the sheet, so they are not in the
         * structured fields and are read from the notes. Only the two the corpus actually
         * pinned are handled; anything else is left at zero rather than guessed, and the move
         * will simply under-report its initiative. */
        JSONArray notes = j.optJSONArray("notes");
        StringBuilder prose = new StringBuilder();
        for(int i = 0; (notes != null) && (i < notes.length()); i++)
            prose.append(notes.getString(i)).append(' ');
        String text = prose.toString();
        if(text.contains("Point of Initiative")) {
            b.ipGain(1);
            /* Quick Barrage's threshold, which the corpus separated across 28 uses without a
             * single ambiguity: it gains at 27% Cornered and above, never at 25% or below, and
             * the test is taken before its own opening lands. */
            if(text.contains("25%") && text.contains("Oppressive"))
                b.gainWhenAbove(Formulas.RED, 0.25);
        }
        return(b.build());
    }

    /** What the corpus knows about one opponent. Every quantity is an interval or absent. */
    public static final class Opponent {
        public final String name, res;
        public final int engagements;
        /** Bounds, or NaN where the corpus could not constrain the value at all. */
        public final double dwLo, dwHi, agiLo, agiHi, hpLo, hpHi;

        /**
         * The opponent's own combat skill, which is what a fight can actually recover.
         *
         * `defence_weight` above is what the corpus literally observed - the naive
         * inversion of an opening gain - and it only equals the opponent's block weight
         * when the two skills sit OUTSIDE the equalization band. Inside it the naive figure
         * is our own attack weight handed back, and skillEqualized says so. When it is set,
         * skillLo and skillHi are a bound rather than a measurement, and a simulator should
         * run both ends rather than pick one.
         */
        public final double skill, skillLo, skillHi;
        public final boolean skillEqualized, hasSkill;

        /**
         * Set when some moves equalized and the rest disagree with the bound they imply.
         *
         * Not an average waiting to be taken. A creature sitting near our own skill is
         * where the branch test is least stable, so both the estimate and the bound are
         * suspect - the badger reads 22 and 39 from two moves while four others bound it
         * to 56-116. Treated as unmeasured rather than resolved.
         */
        public final boolean skillDisputed;
        public final double armLo, armHi;
        /** True when the hard/soft split is identified rather than only the total. */
        public final boolean armSplit;
        public final double armHard, armSoft;
        public final List<String> moves;

        /**
         * What it does to US, or null when the corpus has never seen it act on us.
         *
         * Every other field on this class is our attacks on it, because that is the side a
         * log can attribute. Without this one the optimizer has no second term: a frontier
         * trades damage taken against time spent, and both are zero against an opponent
         * that never swings.
         *
         * Null rather than an inert model, deliberately. An inert FoeModel is a real and
         * useful thing - it answers "how fast could I kill this if it stood still" - but it
         * is a DIFFERENT question, and handing it back here would let a matchup report a
         * flawless plan against a creature we simply have no defensive data for.
         */
        public final FoeModel threat;

        Opponent(JSONObject j) {
            this.name = j.optString("name", "?");
            this.res = j.optString("res", null);
            this.engagements = j.optInt("engagements", 0);
            JSONObject sk = j.optJSONObject("skill");
            if(sk == null) {
                this.skill = this.skillLo = this.skillHi = Double.NaN;
                this.skillEqualized = false;
                this.skillDisputed = false;
                this.hasSkill = false;
            } else {
                this.skill = sk.isNull("value") ? Double.NaN : sk.getDouble("value");
                this.skillLo = sk.optDouble("lo", Double.NaN);
                this.skillHi = sk.optDouble("hi", Double.NaN);
                this.skillEqualized = sk.optBoolean("equalized", false);
                this.skillDisputed = sk.optBoolean("disputed", false);
                this.hasSkill = true;
            }
            double[] dw = range(j, "defence_weight");
            this.dwLo = dw[0];
            this.dwHi = dw[1];
            double[] ag = range(j, "agility");
            this.agiLo = ag[0];
            this.agiHi = ag[1];
            double[] hp = range(j, "hitpoints");
            this.hpLo = hp[0];
            this.hpHi = hp[1];
            JSONObject arm = j.optJSONObject("armour");
            if(arm == null) {
                this.armLo = this.armHi = this.armHard = this.armSoft = Double.NaN;
                this.armSplit = false;
            } else {
                this.armLo = arm.optDouble("total_lo", Double.NaN);
                this.armHi = arm.optDouble("total_hi", Double.NaN);
                this.armSplit = arm.optBoolean("identified", false);
                this.armHard = arm.isNull("hard") ? Double.NaN : arm.optDouble("hard");
                this.armSoft = arm.isNull("soft") ? Double.NaN : arm.optDouble("soft");
            }
            List<String> mv = new ArrayList<String>();
            JSONArray a = j.optJSONArray("moves");
            for(int i = 0; (a != null) && (i < a.length()); i++)
                mv.add(a.getString(i));
            this.moves = mv;
            this.threat = threat(j.optJSONObject("threat"));
        }

        /**
         * The opponent's own model, from the pack's threat block.
         *
         * Returns null unless the block carries a PERIOD. Pressure and damage are both
         * optional - a creature we have watched act but never been hit by is worth
         * modelling, and FoeModel already refuses to report damage it has not measured -
         * but a period is not optional, because it is the clock. Without it there is no
         * answer to how often any of the rest gets applied, and any default would be
         * choosing the matchup's answer rather than computing it.
         */
        private static FoeModel threat(JSONObject t) {
            if(t == null)
                return(null);
            JSONObject per = t.optJSONObject("period");
            if((per == null) || per.isNull("ticks"))
                return(null);
            long period = Math.round(per.optDouble("ticks"));
            if(period <= 0)
                return(null);

            double[] pressure = new double[4];
            JSONObject pr = t.optJSONObject("pressure");
            if(pr != null) {
                for(Map.Entry<String, Integer> e : COLOUR.entrySet()) {
                    if(e.getValue() < 4)
                        pressure[e.getValue()] = pr.optDouble(e.getKey(), 0.0);
                }
            }
            double against = t.isNull("pressure_against") ? 0.0
                : t.optDouble("pressure_against", 0.0);

            double coef = Double.NaN;
            int nHits = 0;
            JSONObject dm = t.optJSONObject("damage");
            if(dm != null) {
                coef = dm.optDouble("coef", Double.NaN);
                nHits = dm.optInt("n", 0);
            }
            double flees = t.isNull("flees_below") ? Double.NaN
                : t.optDouble("flees_below", Double.NaN);
            JSONArray md = per.optJSONArray("modes");
            int[] modes = new int[(md == null) ? 0 : md.length()];
            for(int i = 0; i < modes.length; i++)
                modes[i] = md.getInt(i);
            return(new FoeModel(period, pressure, against, coef,
                                per.optInt("n", 0), nHits, flees, modes));
        }

        private static double[] range(JSONObject j, String key) {
            JSONObject o = j.optJSONObject(key);
            if(o == null)
                return(new double[] {Double.NaN, Double.NaN});
            return(new double[] {o.isNull("lo") ? Double.NaN : o.optDouble("lo"),
                                 o.isNull("hi") ? Double.NaN : o.optDouble("hi")});
        }

        /** Whether enough is known to simulate a fight against this opponent at all. */
        public boolean simulable() {
            /* A skill, not a defence weight. An equalized entry carries only a bound, and
             * simulating against a bound's midpoint would be inventing the very number the
             * corpus declined to produce. */
            return(hasSkill && !skillEqualized && !skillDisputed && !Double.isNaN(skill)
                   && !Double.isNaN(hpLo));
        }

        /**
         * Whether anything caps this opponent's hitpoints.
         *
         * An opponent we only ever survived has a floor and no ceiling: it took what we
         * gave it and walked away, so the honest answer to "how long to kill it" is that
         * we do not know. Simulating against the floor would answer a question nobody
         * asked - how long to kill the smallest one it could possibly have been.
         */
        public boolean hpBounded() {
            return(!Double.isNaN(hpHi));
        }

        /**
         * The hardest fight the corpus allows: most defence, most armour, most hitpoints, and
         * the agility that lengthens our cooldowns most.
         *
         * Higher agility on the opponent lengthens OUR attack cooldowns, so the pessimistic
         * end of an agility interval is its top.
         */
        public Combatant toughest() {
            return(build(pick(skillHi, skill), pick(agiHi, agiLo), pick(hpHi, hpLo),
                         pick(armHi, armLo)));
        }

        /** The easiest fight the corpus allows. */
        public Combatant weakest() {
            return(build(pick(skillLo, skill), pick(agiLo, agiHi), pick(hpLo, hpHi),
                         pick(armLo, armHi)));
        }

        private static double pick(double first, double fallback) {
            return(Double.isNaN(first) ? fallback : first);
        }

        private Combatant build(double dw, double agi, double hp, double arm) {
            Combatant c = new Combatant(name);
            /* A skill, because that is what the corpus can actually recover - see
             * estimate.py's foe_skill_from. An animal holds no stance, so its multiplier
             * is 1 and its block weight is its skill. */
            c.blockSkill = dw;
            c.blockMult = 1.0;
            c.agi = Double.isNaN(agi) ? 0 : agi;
            c.hp = c.maxHp = Double.isNaN(hp) ? 0 : hp;
            if(armSplit && !Double.isNaN(armHard)) {
                c.armHard = armHard;
                c.armSoft = armSoft;
            } else {
                /* Only the total is known. Charging it all as hard soak is the pessimistic
                 * reading - hard soak comes off every hit in full, where soft soak ramps in
                 * and takes less from small ones. */
                c.armHard = Double.isNaN(arm) ? 0 : arm;
                c.armSoft = 0;
            }
            return(c);
        }

        public String toString() {
            return(name + " (" + engagements + " engagement(s))");
        }
    }

    /** Every opponent the corpus has met, by name. */
    public static Map<String, Opponent> opponents(Path path) throws IOException {
        return(opponents(read(path)));
    }

    private static Map<String, Opponent> opponents(JSONObject doc) {
        Map<String, Opponent> out = new LinkedHashMap<String, Opponent>();
        JSONArray arr = doc.getJSONArray("opponents");
        for(int i = 0; i < arr.length(); i++) {
            Opponent o = new Opponent(arr.getJSONObject(i));
            out.put(o.name, o);
        }
        return(out);
    }
}
