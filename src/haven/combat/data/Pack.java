package haven.combat.data;

import haven.combat.BeastMove;
import haven.combat.Combatant;
import haven.combat.FoeModel;
import haven.combat.Formulas;
import haven.combat.Move;
import haven.combat.Repertoire;

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

    /** Every opponent the corpus knows, from the jar, with their cards where shipped. */
    public static Map<String, Opponent> opponentsFromJar() {
        String doc = slurp("opponents.json");
        if(doc == null)
            return(new LinkedHashMap<String, Opponent>());
        /* The card file rides in the jar beside the opponents. A client built from a pack
         * that predates it simply gets the averaged action back, which is what it had. */
        String cd = slurp("animal_moves_measured.json");
        Cards lib = (cd == null) ? null : new Cards(new JSONObject(cd));
        return(opponents(new JSONObject(doc), lib));
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

    /** "unarmed" or "melee" as the model spells it, or null when the sheet is silent. */
    private static Move.Weight weight(String s) {
        if(s == null)
            return(null);
        if("unarmed".equalsIgnoreCase(s))
            return(Move.Weight.UNARMED);
        if("melee".equalsIgnoreCase(s))
            return(Move.Weight.MELEE);
        return(null);
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

        /* What it opens when the OPPONENT swings, which is not the same list and must not
         * be merged into it - see Move.whenAttackedOpens. Percentage points, like the
         * openings above and unlike the reductions below. */
        JSONArray trig = j.optJSONArray("when_attacked_openings");
        for(int i = 0; (trig != null) && (i < trig.length()); i++) {
            JSONObject o = trig.getJSONObject(i);
            Integer c = colour(o.optString("colour", null));
            if(c != null)
                b.whenAttackedOpens(c, o.optDouble("pct", 0));
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
            .attackMult(dbl(j, "attack_mult", 1.0))
            /* A stance is held rather than thrown, and exactly one is - see Move.stance.
             * blockSkill is null where the sheet does not name one, which leaves the
             * caller to keep whatever it already had rather than guess. */
            .stance(j.optBoolean("stance", false), dbl(j, "block_mult", 1.0),
                    weight(j.optString("block_skill", null)))
            /* Shield Up, and nothing else in the sheet: 250% holding a shield and 50%
             * without. Parsed since the sheet was first read and never consumed, so
             * every model so far has priced an unshielded character at five times the
             * block weight they would actually have. */
            .blockNeeds(j.optString("block_requires", null),
                        dbl(j, "block_mult_without", Double.NaN));

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
        /**
         * "player", "creature", or "unknown" - and they are not interchangeable.
         *
         * A player holds a deck of the cards we hold, at levels, with mu and a stance
         * chosen by somebody; an animal throws from a fixed list and has none of that.
         * Pooling them offers one deck as the answer to "fox, walrus and a person",
         * which is not an answer to anything. "unknown" is its own value rather than a
         * guess: twenty entries have no resource, and most but not all are creatures.
         */
        public final String kind;
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

        /**
         * Whether we can leave, and how much faster than it we move.
         *
         * Measured and then thrown away until now, which made the matchup report answer
         * half a question. "Can I take this thing" and "can I get out if I am wrong" are
         * different questions with different answers, and the second one is the only
         * mitigation a losing matchup has. The badger is outrun on 8440 samples; the wolf
         * is not, on 166.
         *
         * `speedMeasured` is false where the corpus only ever watched us stand and fight,
         * which reads as a speed of zero and means nothing. NaN elsewhere.
         */
        public final double speedLo, speedHi, speedMedian, ourTop;
        public final boolean weOutrunIt, speedMeasured;

        /**
         * The hitpoint band from individuals a kill actually PINNED, and how many did.
         *
         * hpLo/hpHi above is the envelope of every individual ever seen, and it is honest
         * rather than useful: a killing blow that removes most of the bar brackets its
         * creature at [almost nothing, total], which is true and says nothing, and the
         * envelope's floor is the minimum over all of them - so one such kill sets it.
         * The corpus holds 503 one-shot kills across 49 species and the reindeer reads
         * 1 to 287 because of them.
         *
         * This is the same band over the individuals whose last hit took a quarter of them
         * or less. It runs about half the width across the species that have enough, and
         * the reindeer's is a tenth: 140 to 167. NaN where too few did.
         */
        public final double hpPinLo, hpPinHi;
        public final int hpPinN;

        Opponent(JSONObject j, Cards lib) {
            this.name = j.optString("name", "?");
            this.res = j.optString("res", null);
            this.kind = j.optString("kind", "creature");
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
            JSONObject hpo = j.optJSONObject("hitpoints");
            if(hpo == null) {
                this.hpPinLo = this.hpPinHi = Double.NaN;
                this.hpPinN = 0;
            } else {
                this.hpPinLo = hpo.isNull("pinned_lo") ? Double.NaN
                    : hpo.optDouble("pinned_lo", Double.NaN);
                this.hpPinHi = hpo.isNull("pinned_hi") ? Double.NaN
                    : hpo.optDouble("pinned_hi", Double.NaN);
                this.hpPinN = hpo.optInt("pinned_n", 0);
            }
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
            this.threat = threat(j.optJSONObject("threat"), j, lib,
                                 j.optString("name", "?"));
            JSONObject sp = j.optJSONObject("relative_speed");
            if(sp == null) {
                this.speedLo = this.speedHi = Double.NaN;
                this.speedMedian = this.ourTop = Double.NaN;
                this.weOutrunIt = this.speedMeasured = false;
            } else {
                this.speedMeasured = sp.optBoolean("measured", false);
                this.speedLo = sp.optDouble("lo", Double.NaN);
                this.speedHi = sp.optDouble("hi", Double.NaN);
                this.speedMedian = sp.optDouble("median", Double.NaN);
                this.ourTop = sp.optDouble("our_top", Double.NaN);
                this.weOutrunIt = sp.optBoolean("we_outrun_it", false);
            }
        }

        /** Whether a kill has ever pinned this creature's size rather than merely bounding it. */
        public boolean hpPinned() {
            return(!Double.isNaN(this.hpPinLo) && !Double.isNaN(this.hpPinHi));
        }

        /** Whether withdrawal is an option this opponent cannot answer. */
        public boolean canDisengage() {
            return(this.speedMeasured && this.weOutrunIt);
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
        private static FoeModel threat(JSONObject t, JSONObject j, Cards lib,
                                       String species) {
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
            /* What its own cards take back off its openings. Null where too few of its
             * actions were watched to mean anything - see estimate.restores. */
            double back = t.isNull("restores") ? Double.NaN
                : t.optDouble("restores", Double.NaN);
            /* And the same split by colour, which is how the cards actually behave -
             * see FoeModel.restoresByColour. Absent where the corpus could not split it,
             * and the scalar above then carries it. */
            double[] byCol = null;
            JSONObject rb = t.optJSONObject("restores_by_colour");
            JSONObject rbc = (rb == null) ? null : rb.optJSONObject("by_colour");
            if(rbc != null) {
                byCol = new double[4];
                for(String k : rbc.keySet()) {
                    Integer ix = COLOUR.get(k);
                    if(ix != null)
                        byCol[ix.intValue()] = rbc.optDouble(k, 0);
                }
            }
            Object[] rule = policyRule(j);
            return(new FoeModel(period, pressure, against, coef,
                                per.optInt("n", 0), nHits, flees, modes, back,
                                rule[0] == null ? null : (String)rule[0],
                                (rule[1] == null) ? 0 : ((Double)rule[1]).doubleValue(),
                                (double[])rule[2], (double[])rule[3], byCol,
                                /* Its actual cards, where the corpus can name them.
                                 * Null leaves the averaged action in place, which is
                                 * what eight of the forty-nine creatures still need. */
                                repertoire(j, lib, species)));
        }

        /**
         * The one state split the corpus can hold up for this species, as the model wants it.
         *
         * Returns {feature, cut, whenPressure, elsePressure}, all null when there is no rule
         * or when the rule splits on something a Combatant does not carry. Distance is the
         * one that costs - it decides four of the fourteen and a Combatant has no position.
         */
        private static Object[] policyRule(JSONObject j) {
            Object[] none = new Object[] {null, null, null, null};
            JSONObject r = j.optJSONObject("policy_rule");
            if((r == null) || r.isNull("sim_feature"))
                return(none);
            double[] a = colours(r.optJSONObject("when_pressure"));
            double[] b = colours(r.optJSONObject("otherwise_pressure"));
            if((a == null) || (b == null))
                return(none);
            return(new Object[] {r.getString("sim_feature"),
                                 Double.valueOf(r.optDouble("cut", 0)), a, b});
        }

        private static double[] colours(JSONObject o) {
            if(o == null)
                return(null);
            double[] out = new double[4];
            for(Map.Entry<String, Integer> e : COLOUR.entrySet()) {
                if(e.getValue() < 4)
                    out[e.getValue()] = o.optDouble(e.getKey(), 0.0);
            }
            return(out);
        }

        private static double[] range(JSONObject j, String key) {
            JSONObject o = j.optJSONObject(key);
            if(o == null)
                return(new double[] {Double.NaN, Double.NaN});
            return(new double[] {o.isNull("lo") ? Double.NaN : o.optDouble("lo"),
                                 o.isNull("hi") ? Double.NaN : o.optDouble("hi")});
        }

        /** A person, whose cards and levels are chosen, not a species constant. */
        public boolean isPlayer() {
            return("player".equals(kind));
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
            return(build(pick(skillHi, skill), pick(agiHi, agiLo), pick(planHpHi(), hpLo),
                         pick(armHi, armLo)));
        }

        /** The easiest fight the corpus allows. */
        public Combatant weakest() {
            return(build(pick(skillLo, skill), pick(agiLo, agiHi), pick(planHpLo(), hpHi),
                         pick(armLo, armHi)));
        }

        /**
         * The hitpoints to PLAN against, which is the pinned band wherever there is one.
         *
         * hpLo/hpHi is the envelope of every individual ever seen and it is honest, but a
         * killing blow that removes most of the bar brackets its creature at [almost
         * nothing, total] - so the envelope's floor is often a fact about our damage
         * rather than about the creature. Planning the easiest fight against a reindeer of
         * 1 hitpoint is not a matchup, it is an artefact: the same species pins to 140-167
         * once the kills that pinned nothing are set aside.
         *
         * The envelope stays on the class and stays reported. This is only what a
         * simulation should open with.
         */
        public double planHpLo() {
            return(hpPinned() ? hpPinLo : hpLo);
        }

        public double planHpHi() {
            return(hpPinned() ? hpPinHi : hpHi);
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
        /* The measured card file sits beside the opponents. A creature without it is
         * still a creature - it falls back to its averaged action - so this is absent
         * rather than fatal, because the pack has shipped without it and older ones will.
         */
        Cards lib = null;
        try {
            Path side = path.resolveSibling("animal_moves_measured.json");
            if(Files.exists(side))
                lib = cards(side);
        } catch(IOException e) {
            lib = null;
        }
        return(opponents(read(path), lib));
    }

    /**
     * One character's numbers, as the corpus last saw them.
     *
     * Named Fighter rather than Character so it does not shadow java.lang.Character,
     * which this file already uses.
     *
     * A deck is built for ONE character. Attributes decide the attack weight and the
     * block weight, and those decide which cards are worth points, so a deck built for
     * 243 melee is not the deck for 158. These were literals in the search tool once,
     * and they were nobody's - part Shade, part invention.
     */
    public static final class Fighter {
        public final String name;
        public final int logs;
        public final double str, agi, unarmed, melee, hp;
        /** The last weapon the character was seen holding, or null bare-handed. */
        public final String weapon;
        public final double weaponDamage, weaponQl, weaponPen;
        /**
         * The cards this character knows, by display name, and the level each sits at.
         *
         * A level of 0 means known but not currently on the bar - the dump lists every
         * card the character has, slotted or not - so this is ownership, not a loadout.
         * Points are re-assignable freely inside the thirty, which is why a search that
         * ranges over levels is realistic and not a fantasy: the only thing a character
         * cannot do is play a card they have never learned.
         */
        public final Map<String, Integer> owned;
        /**
         * What the character is wearing, and whether a shield is in hand.
         *
         * A duel fought naked is not this character's duel. ZzxcuV3 carries 79 hard and
         * 67 soft against a Bronze Sword listed at 90, so leaving it out roughly doubles
         * how fast everything dies - which is what handed every full-deck pairing to
         * whoever swung first. The client writes hard and soft on each gear row, so
         * these are measured rather than matched against the wiki.
         *
         * The shield is here because Shield Up is 250% of the block weight holding one
         * and 50% without, a factor of five on the single number that stance exists to
         * set.
         */
        public final double armHard, armSoft;
        public final boolean shield;

        private Fighter(JSONObject j) {
            this.name = j.optString("name", "?");
            this.logs = j.optInt("logs", 0);
            this.str = j.optDouble("str", 0);
            this.agi = j.optDouble("agi", 0);
            this.unarmed = j.optDouble("unarmed", 0);
            this.melee = j.optDouble("melee", 0);
            this.hp = j.optDouble("hp", 0);
            JSONObject w = j.optJSONObject("weapon");
            this.weapon = (w == null) ? null : w.optString("name", null);
            this.weaponDamage = (w == null) ? 0 : w.optDouble("base_damage", 0);
            this.weaponQl = (w == null) ? 0 : w.optDouble("ql", 10);
            this.weaponPen = (w == null) ? 0 : w.optDouble("armour_pen", 0);
            Map<String, Integer> own = new LinkedHashMap<String, Integer>();
            JSONObject od = j.optJSONObject("owned");
            if(od != null) {
                for(String k : od.keySet())
                    own.put(k, od.optInt(k, 0));
            }
            this.owned = own;
            JSONObject arm = j.optJSONObject("armour");
            this.armHard = (arm == null) ? 0 : arm.optDouble("hard", 0);
            this.armSoft = (arm == null) ? 0 : arm.optDouble("soft", 0);
            this.shield = j.optBoolean("shield", false);
        }

        /** Whether this character has learned the card at all. */
        public boolean knows(String cardName) {
            return(owned.isEmpty() || owned.containsKey(cardName));
        }

        /** This character as the simulator takes them. */
        public Combatant combatant() {
            Combatant c = new Combatant(name);
            c.str = str; c.agi = agi; c.unarmed = unarmed; c.melee = melee;
            c.hp = c.maxHp = hp;
            c.weaponDamage = weaponDamage;
            c.weaponQl = (weaponQl > 0) ? weaponQl : 10;
            c.weaponPen = weaponPen;
            c.armHard = armHard;
            c.armSoft = armSoft;
            /* A PLAYER'S ARMOUR IS PENETRABLE, and Combatant defaults it false because
             * every armoured opponent in the corpus is an animal and the one that could
             * be tested turned out immune. Against a person the weapon's penetration is
             * exactly what it says it is. */
            c.penetrable = true;
            return(c);
        }
    }

    /**
     * The deck rules, as the client itself states them.
     *
     * These were literals in the search tool - thirty points, five saved decks - and the
     * client has been dumping both in the sheet the whole time. A literal is a copy of a
     * fact, and a copy does not move when the fact does: if the budget ever changes, the
     * data says so and a hard-coded thirty does not.
     *
     * The card limit is not in the dump, so it stays where it is measured: 558 of 791
     * dumps hold ten cards and none holds more.
     */
    public static final class DeckRules {
        /** Points a deck may spend, and how many decks the game saves. */
        public final int maxPoints, saved;

        private DeckRules(int maxPoints, int saved) {
            this.maxPoints = maxPoints;
            this.saved = saved;
        }
    }

    public static DeckRules deckRules(Path path) throws IOException {
        JSONObject doc = read(path);
        return(new DeckRules(doc.optInt("maxpoints", 30), doc.optInt("nsave", 5)));
    }

    /**
     * What the client calls each colour, so the code's own ordering can be checked.
     *
     * Formulas numbers them green, blue, yellow, red, which is not the order anyone
     * assumes - and assuming wrong relabels every colour in a report without changing a
     * single number, which is exactly how a probe once read Cleave as striking green
     * when it strikes blue. The dump names them, so the assumption is checkable.
     */
    public static Map<String, String> schools(Path path) throws IOException {
        return(colourField(path, "school"));
    }

    /**
     * What the game calls each opening on screen - Cornered, Dizzy, Off Balance, Reeling.
     *
     * A report that says "red" and a game that says "Cornered" are describing the same
     * thing in two languages, and every translation between them is a chance to get it
     * wrong. The card text names the opening, so a reduction line reading "50% Sweeping"
     * has to be routed to yellow by this mapping rather than by memory.
     */
    public static Map<String, String> openings(Path path) throws IOException {
        return(colourField(path, "opening"));
    }

    private static Map<String, String> colourField(Path path, String field)
        throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        JSONObject cols = read(path).optJSONObject("colours");
        if(cols != null) {
            for(String k : cols.keySet()) {
                JSONObject c = cols.optJSONObject(k);
                if(c != null)
                    out.put(k, c.optString(field, null));
            }
        }
        return(out);
    }

    public static Map<String, Fighter> characters(Path path) throws IOException {
        Map<String, Fighter> out = new LinkedHashMap<String, Fighter>();
        JSONArray arr = read(path).getJSONArray("characters");
        for(int i = 0; i < arr.length(); i++) {
            Fighter c = new Fighter(arr.getJSONObject(i));
            out.put(c.name, c);
        }
        return(out);
    }

    /**
     * The measured per-card file, which is what turns a creature into its cards.
     *
     * Kept beside the opponents rather than inside them because a card is shared: Fell
     * Scratch is thrown by twenty-five species and is measured once, across all of them.
     * That sharing is the whole reason the card can be separated from the creature at all.
     */
    public static final class Cards {
        private final Map<String, JSONObject> byName = new LinkedHashMap<String, JSONObject>();
        private final Map<String, Double> factor = new LinkedHashMap<String, Double>();

        private Cards(JSONObject doc) {
            JSONArray arr = doc.optJSONArray("moves");
            for(int i = 0; (arr != null) && (i < arr.length()); i++) {
                JSONObject m = arr.getJSONObject(i);
                byName.put(m.optString("name", "?"), m);
            }
            JSONObject sf = doc.optJSONObject("species_factor");
            for(String k : (sf == null) ? new java.util.HashSet<String>() : sf.keySet())
                factor.put(k, sf.optDouble(k, 1.0));
        }

        /**
         * One card as a species throws it.
         *
         * The openings are ratios - the fit separating card from creature has a gauge
         * freedom - so they are multiplied by the species factor here, which is the other
         * half of the same fit and puts them back on the scale the pressure was measured
         * in. Everything else is already in its own units and is taken as it stands.
         */
        public BeastMove move(String name, String species) {
            JSONObject m = byName.get(name);
            if(m == null)
                return(null);
            double f = factor.containsKey(species) ? factor.get(species).doubleValue() : 1.0;
            double[] op = new double[4];
            JSONObject o = m.optJSONObject("openings");
            for(String k : (o == null) ? new java.util.HashSet<String>() : o.keySet()) {
                Integer ix = COLOUR.get(k);
                if(ix != null)
                    op[ix.intValue()] = o.optDouble(k, 0) * f;
            }
            JSONObject d = m.optJSONObject("damage");
            double coef = (d == null) ? Double.NaN : d.optDouble("coef", Double.NaN);
            JSONObject c = m.optJSONObject("cooldown");
            long cd = (c == null) ? 0 : Math.round(c.optDouble("ticks", 0));
            double[] rest = new double[4];
            JSONObject r = m.optJSONObject("restores");
            JSONObject rb = (r == null) ? null : r.optJSONObject("by_colour");
            for(String k : (rb == null) ? new java.util.HashSet<String>() : rb.keySet()) {
                Integer ix = COLOUR.get(k);
                if(ix != null)
                    rest[ix.intValue()] = rb.optDouble(k, 0);
            }
            JSONObject g = m.optJSONObject("grievous");
            double grev = (g == null) ? 0 : g.optDouble("per_soft", 0);
            JSONObject a = m.optJSONObject("armour");
            double soak = (a == null) ? Double.NaN : a.optDouble("soaked_share", Double.NaN);
            return(new BeastMove(name, op, coef, cd, rest, grev, soak));
        }
    }

    public static Cards cards(Path path) throws IOException {
        return(new Cards(read(path)));
    }

    /**
     * A creature's repertoire: its cards, and how often it throws each.
     *
     * Built from three things the pack already carried and one it did not use. The card
     * list and the mix are read straight off the opponent; the conditional rule carries
     * the card counts on each side of its split and the loader had been reading only the
     * per-colour pressure summary beside them, which is the same averaging one level
     * down. An ant at distance reads {Ant Spit 255, Fell Scratch 5} and that is a policy,
     * where "blue 4.07, green 4.25" is a shadow of one.
     *
     * Returns null when there is nothing to build from, and the caller then keeps the
     * aggregate. A creature we have never seen throw its own card is not a creature we
     * can simulate card by card, and pretending otherwise would be worse than the
     * average.
     */
    public static Repertoire repertoire(JSONObject j, Cards lib, String species) {
        if(lib == null)
            return(null);
        JSONObject pol = j.optJSONObject("policy");
        JSONArray mixa = (pol == null) ? null : pol.optJSONArray("mix");
        if((mixa == null) || (mixa.length() == 0))
            return(null);
        List<BeastMove> cards = new ArrayList<BeastMove>();
        List<Double> share = new ArrayList<Double>();
        double tot = 0;
        for(int i = 0; i < mixa.length(); i++) {
            JSONArray row = mixa.getJSONArray(i);
            BeastMove m = lib.move(row.getString(0), species);
            if((m == null) || !m.acts())
                continue;               /* a card we know the name of and nothing else */
            cards.add(m);
            double w = row.getDouble(1);
            share.add(w);
            tot += w;
        }
        if(cards.isEmpty() || (tot <= 0))
            return(null);
        double[] mix = new double[cards.size()];
        for(int i = 0; i < mix.length; i++)
            mix[i] = share.get(i) / tot;

        /* The learned split, as CARD COUNTS rather than as the pressure they average to. */
        String feat = null;
        double cut = 0;
        double[] when = null, other = null;
        JSONObject rule = j.optJSONObject("policy_rule");
        if(rule != null) {
            feat = rule.optString("sim_feature", null);
            cut = rule.optDouble("cut", 0);
            when = counts(rule.optJSONArray("when"), cards);
            other = counts(rule.optJSONArray("otherwise"), cards);
            if((feat == null) || (when == null) || (other == null)) {
                feat = null;
                when = other = null;
            }
        }
        return(new Repertoire(cards.toArray(new BeastMove[0]), mix, feat, cut, when, other));
    }

    /** A [[card, count], ...] side of a rule, as shares over the cards we kept. */
    private static double[] counts(JSONArray arr, List<BeastMove> cards) {
        if(arr == null)
            return(null);
        double[] out = new double[cards.size()];
        double tot = 0;
        for(int i = 0; i < arr.length(); i++) {
            JSONArray row = arr.getJSONArray(i);
            String nm = row.getString(0);
            for(int k = 0; k < cards.size(); k++) {
                if(cards.get(k).name.equals(nm)) {
                    out[k] += row.getDouble(1);
                    tot += row.getDouble(1);
                }
            }
        }
        if(tot <= 0)
            return(null);
        for(int i = 0; i < out.length; i++)
            out[i] /= tot;
        return(out);
    }

    private static Map<String, Opponent> opponents(JSONObject doc, Cards lib) {
        Map<String, Opponent> out = new LinkedHashMap<String, Opponent>();
        JSONArray arr = doc.getJSONArray("opponents");
        for(int i = 0; i < arr.length(); i++) {
            Opponent o = new Opponent(arr.getJSONObject(i), lib);
            out.put(o.name, o);
        }
        return(out);
    }
}
