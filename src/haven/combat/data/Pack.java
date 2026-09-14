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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        JSONObject doc = new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        checkFormat(doc, p.getFileName().toString());
        return(doc);
    }

    /**
     * The pack format this client understands.
     *
     * A renamed or retyped key used to be absorbed silently: every field is read through an
     * opt* accessor with a default, so a pack written by a newer estimator loads as though
     * the field were merely missing. A `format` block (and the `generated` stamp beside it)
     * is the one additive key that lets Pack SAY it does not understand a file instead of
     * quietly substituting defaults. Absent is not an error - older packs have none - so
     * the reader only complains when a file advertises a version above this constant.
     */
    public static final int FORMAT = 1;

    private static boolean formatWarned;
    private static String formatSeen = "(none declared)";

    private static void checkFormat(JSONObject doc, String what) {
        if(doc == null)
            return;
        JSONObject block = doc.optJSONObject("format");
        int v = (block != null) ? block.optInt("version", -1) : doc.optInt("format", -1);
        if(v < 0)
            return;
        String gen = (block != null) ? block.optString("generated", null) : null;
        formatSeen = what + " declares format " + v
            + ((gen == null) ? "" : (", generated " + gen));
        if((v > FORMAT) && !formatWarned) {
            formatWarned = true;
            System.err.println("[combat pack] " + what + " declares format " + v
                + " but this client understands at most " + FORMAT
                + " - unknown fields are ignored, not defaulted safely");
        }
    }

    /** What the last-loaded pack file said about its own format, for a check to print. */
    public static String formatStamp() {
        return(formatSeen);
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
        if(doc == null)
            return(new LinkedHashMap<String, Move>());
        JSONObject j = new JSONObject(doc);
        checkFormat(j, "moves_sheet.json");
        return(moves(j));
    }

    /** Every opponent the corpus knows, from the jar, with their cards where shipped. */
    public static Map<String, Opponent> opponentsFromJar() {
        String doc = slurp("opponents.json");
        if(doc == null)
            return(new LinkedHashMap<String, Opponent>());
        JSONObject foes = new JSONObject(doc);
        checkFormat(foes, "opponents.json");
        /* The card file rides in the jar beside the opponents. A client built from a pack
         * that predates it simply gets the averaged action back, which is what it had. */
        String cd = slurp("animal_moves_measured.json");
        Cards lib = (cd == null) ? null : new Cards(new JSONObject(cd));
        /* AND OUR OWN SHEET, which the path loader has always loaded and this one never did.
         * Without it `ours` is null, every opponent's repertoire comes back null, and the
         * live advisor has only the single averaged action - so the shipped pack did not
         * drive the card-by-card simulation it was built for. See opponents(Path), which
         * does exactly this; the two paths must agree. */
        String sheet = slurp("moves_sheet.json");
        Map<String, Move> ours =
            (sheet == null) ? null : moves(new JSONObject(sheet));
        Map<String, Opponent> out = opponents(foes, lib, ours);
        /* The per-individual rows, where the build shipped them, so a client prices the
         * hardest REAL creature rather than the pooled chimera. Absent is not fatal: the
         * loaders then fall back to toughest() and say so. */
        String ind = slurp("individuals.json");
        if(ind != null)
            attach(out, individuals(new JSONObject(ind)));
        return(out);
    }

    /** Every measured individual the jar carries, by species name; empty when not shipped. */
    public static Map<String, List<Individual>> individualsFromJar() {
        String doc = slurp("individuals.json");
        if(doc == null)
            return(new LinkedHashMap<String, List<Individual>>());
        JSONObject j = new JSONObject(doc);
        checkFormat(j, "individuals.json");
        return(individuals(j));
    }

    /** Hands each opponent its own per-creature rows, where the corpus measured any. */
    private static void attach(Map<String, Opponent> foes,
                               Map<String, List<Individual>> rows) {
        for(Map.Entry<String, List<Individual>> e : rows.entrySet()) {
            Opponent o = foes.get(e.getKey());
            if(o != null)
                o.useIndividuals(e.getValue());
        }
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
            /* "Initiative points: N" is what the move SPENDS - see Move.ipCost. For an
             * "N+M" line the trailing number comes through separately as the EXTRA
             * initiative the user must hold before the move can begin, which Move.ipExtra
             * documents and Move.ipRequirement sums with the cost. It is a precondition,
             * not a second charge. */
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
         * structured fields and are read from the notes. The number is read from the gain
         * sentence itself; a move whose sentence does not say it is left at zero rather than
         * guessed, and will simply under-report its initiative. */
        JSONArray notes = j.optJSONArray("notes");
        StringBuilder prose = new StringBuilder();
        for(int i = 0; (notes != null) && (i < notes.length()); i++)
            prose.append(notes.getString(i)).append(' ');
        String text = prose.toString();
        /* The gain is its OWN sentence - "gains you N Points of Initiative" - and not any
         * "Point of Initiative" in the notes. Think separated the two: its gain sentence is
         * plural ("2 Points") while the cooldown sentence under it is singular ("for each
         * Point of Initiative you have"), so a substring test read the cooldown line and
         * pinned the gain at one. Steal Thunder's sentence entangles the gain with a steal
         * the model has no field for ("take 3 ... and gain you 2 of them"), so it does not
         * name Initiative in the gain clause and stays at zero rather than guessed. */
        Matcher gain = IP_GAIN.matcher(text);
        if(gain.find()) {
            b.ipGain(Integer.parseInt(gain.group(1)));
            /* Quick Barrage's threshold, which the corpus separated across 28 uses without a
             * single ambiguity: it gains at 27% Cornered and above, never at 25% or below, and
             * the test is taken before its own opening lands. */
            if(text.contains("25%") && text.contains("Oppressive"))
                b.gainWhenAbove(Formulas.RED, 0.25);
        }
        targets(b, text);
        /* Dash's whole effect, which lives in prose like the gains above. */
        if(text.contains("completely removes your slightest opening"))
            b.clearsLeast(true);
        /* Feigned Dodge's attacking half. The sheet gives it a reduction line like any
         * defensive card and then says in prose that the opponent gets twice whatever it
         * took - so read as the structured fields alone it is free defence, which is how
         * it came to be in nearly every recommended deck. */
        if(text.contains("twice that amount is given to the opponent"))
            b.reduceToFoe(2.0);
        return(b.build());
    }

    /* "up to five opponents", and the shares those targets take. */
    private static final Pattern UPTO =
        Pattern.compile("attack up to (\\w+) opponents");
    private static final Pattern SHARES =
        Pattern.compile("targets will receive ([^.]*?) of the");
    private static final Pattern PCT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)%");
    /* "gains you 1 Point of Initiative" / "gains you 2 Points of Initiative", case- and
     * plural-tolerant, anchored on the gain verb so a COOLDOWN sentence that merely
     * mentions a Point of Initiative cannot supply the number - see Pack.move. */
    private static final Pattern IP_GAIN =
        Pattern.compile("gains?\\s+you\\s+(\\d+)\\s+points?\\s+of\\s+initiative",
                        Pattern.CASE_INSENSITIVE);
    private static final String[] WORDS =
        {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};

    /**
     * How many opponents an attack lands on, read from the sheet's own prose.
     *
     * Prose because that is where the sheet puts it - there is no structured field for it,
     * the way there is none for the initiative gains read just above. Three cards say it
     * and each says it differently, so each sentence is matched rather than paraphrased:
     * Full Circle's "all other opponents in range", Punch 'em Both's "one other opponent in
     * range", and Storm of Swords' "attack up to five opponents in range" together with the
     * escalating shares that follow it.
     *
     * Silence means one, which is right for the other thirty-eight cards and is the same
     * default the game gives an attack. A sheet that reworded one of these would quietly
     * lose its extra targets rather than crash, so {@code tools/CombatPackCheck.java} holds
     * the three to their numbers.
     */
    private static void targets(Move.Builder b, String text) {
        if(text.contains("all other opponents in range")) {
            b.targets(Move.TARGETS_IN_RANGE);
            return;
        }
        Matcher m = UPTO.matcher(text);
        if(m.find()) {
            int n = -1;
            for(int i = 0; i < WORDS.length; i++) {
                if(WORDS[i].equals(m.group(1).toLowerCase()))
                    n = i;
            }
            if(n < 0) {
                try {
                    n = Integer.parseInt(m.group(1));
                } catch(NumberFormatException e) {
                    n = -1;
                }
            }
            if(n > 1) {
                b.targets(n, shares(text));
                return;
            }
        }
        /* "attacks BOTH your primary target and also one other opponent in range" - the
         * only one of the three that names no count, because two is the whole of it. */
        if(text.contains("one other opponent in range"))
            b.targets(2);
    }

    /**
     * "The targets will receive 100%, 125%, 150%, 175% and 200% ... of the weapon's damage."
     *
     * Storm of Swords and nothing else. The escalation is the point of the card: its fifth
     * target takes twice what its first does, so a crowd is worth more to it than one
     * opponent is - which is the opposite of how every aggregate model has priced it.
     */
    private static double[] shares(String text) {
        Matcher m = SHARES.matcher(text);
        if(!m.find())
            return(null);
        List<Double> out = new ArrayList<Double>();
        Matcher p = PCT.matcher(m.group(1));
        while(p.find())
            out.add(Double.valueOf(Double.parseDouble(p.group(1)) / 100.0));
        if(out.isEmpty())
            return(null);
        double[] a = new double[out.size()];
        for(int i = 0; i < a.length; i++)
            a[i] = out.get(i).doubleValue();
        return(a);
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
        /**
         * Bounds, or NaN where the corpus could not constrain the value at all.
         *
         * Agility is the exception: an open side is a direction rather than an absence, so
         * a missing floor is 0 and a missing ceiling is +infinity - see the constructor.
         */
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
         * The same model at the top of its measured damage, or null where there is no top.
         *
         * Read {@link #threat} for the expected fight and this one for the worst the corpus
         * allows. A page or a matchup that reports one number for damage taken is reporting
         * the median of a wide interval as though it were a measurement.
         */
        public final FoeModel threatHi;

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

        /**
         * How many observations stand behind each interval, where the pack published one.
         *
         * `n` was written by the estimator and parsed by nothing, so a reading built on one
         * sample looked exactly like a reading built on four hundred. These are read off
         * the same objects the bounds come from and are reported, never used to weight: the
         * corpus decides its own support and a consumer only needs to be able to SAY how
         * thin it is. Zero means the pack published none (older packs), not "no data".
         */
        public final int dwN, agiN, speedN, periodN;

        /**
         * How many choices the measured card mixture (`policy.mix`) rests on, and how
         * many of those were solo fights. Zero means the pack published no policy.
         *
         * The mixture itself is read by repertoire(); these are its support, so a reader
         * can say a two-card mix rests on eleven throws or on four hundred. They are
         * reported, never used to weight - the corpus decides its own support.
         */
        public final int policyN, policySoloN;

        /**
         * Whether the threat block's damage coefficient was measured BEFORE armour soaked
         * any of it.
         *
         * `threat.damage.before_armour` is a flag beside the coefficient (coef/hi/lo/n),
         * written by the estimator and read by nothing. It says the coefficient is a
         * pre-soak figure, which is worth being able to state; NaN would be a lie here,
         * so this is a boolean. False on a pack that does not publish it.
         */
        public final boolean threatBeforeArmour;

        /**
         * The estimator's remaining published diagnostics, read here so a rename is not
         * silent rather than because the sim prices them.
         *
         * `blended` names the axes along which a species' reading is a POOLED blend (the
         * audit's chimera concern in the estimator's own words); `defence_weight_late` and
         * `skill_slope` are alternative readings published BESIDE the ones the sim uses;
         * `fought_in` counts the sites the corpus came from; and the `policy.ip_*` /
         * `attack_share_*` pair says whether the card mixture only holds under one
         * initiative condition and by how much the attack share splits. None of these
         * drives a fight - they are read, reported, and left to the reader to weigh.
         * `defence_weight_late` is an interval, so it is carried as its two ends; the slope
         * is the `slope` member of its object.
         */
        public final List<String> blended;
        public final double defenceWeightLateLo, defenceWeightLateHi, skillSlope;
        public final int foughtInSites;
        public final boolean policyIpConditioned, policyIpGroupContaminated;
        public final int policyIpN;
        public final double policyAttackShareAt0, policyAttackShareAbove0;

        /**
         * The per-creature rows, or null where the pack ships none.
         *
         * Set by the loaders, not the constructor, so an Opponent built by hand (a test
         * fixture) simply has none. See hardestReal()/weakestReal(), which sweep these.
         */
        private List<Individual> people;

        private void useIndividuals(List<Individual> rows) {
            this.people = rows;
        }

        /** The per-creature rows this opponent carries, or an empty list when none. */
        public List<Individual> individuals() {
            return((people == null) ? new ArrayList<Individual>() : people);
        }

        Opponent(JSONObject j, Cards lib) {
            this(j, lib, null);
        }

        Opponent(JSONObject j, Cards lib, Map<String, Move> ours) {
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
            JSONObject dwo = j.optJSONObject("defence_weight");
            this.dwN = (dwo == null) ? 0 : dwo.optInt("n", 0);
            this.dwLo = dw[0];
            this.dwHi = dw[1];
            double[] ag = range(j, "agility");
            JSONObject ago = j.optJSONObject("agility");
            this.agiN = (ago == null) ? 0 : ago.optInt("n", 0);
            /* An open side is a DIRECTION and not a missing measurement, and collapsing it
             * onto the other bound inverted it: twelve species record only a floor ("at
             * least this agile", whose pessimistic end is +infinity - the cooldown factor
             * clamps it) and thirty-one only a ceiling (whose optimistic end is zero).
             * range() keeps NaN for the genuinely unconstrained cases elsewhere. */
            this.agiLo = Double.isNaN(ag[0]) ? 0.0 : ag[0];
            this.agiHi = Double.isNaN(ag[1]) ? Double.POSITIVE_INFINITY : ag[1];
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
            JSONObject tperiod = (j.optJSONObject("threat") == null) ? null
                : j.optJSONObject("threat").optJSONObject("period");
            this.periodN = (tperiod == null) ? 0 : tperiod.optInt("n", 0);
            JSONObject pol = j.optJSONObject("policy");
            this.policyN = (pol == null) ? 0 : pol.optInt("n", 0);
            this.policySoloN = (pol == null) ? 0 : pol.optInt("solo_n", 0);
            JSONObject tdmg = (j.optJSONObject("threat") == null) ? null
                : j.optJSONObject("threat").optJSONObject("damage");
            this.threatBeforeArmour = (tdmg != null)
                && tdmg.optBoolean("before_armour", false);
            List<String> bl = new ArrayList<String>();
            JSONArray ba = j.optJSONArray("blended");
            for(int i = 0; (ba != null) && (i < ba.length()); i++)
                bl.add(ba.getString(i));
            this.blended = bl;
            /* BOTH ARE OBJECTS, not numbers. defence_weight_late is {against, agrees, depth,
             * lo, hi, n} and skill_slope is {flat, slope, n, measurable, ...}; reading either
             * with optDouble returned NaN for every opponent, and the check that counted them
             * then printed "0 carry a late defence weight, 0 a skill slope" over a pack that
             * published 51 and 39. */
            JSONObject late = j.optJSONObject("defence_weight_late");
            this.defenceWeightLateLo = (late == null) ? Double.NaN
                : late.optDouble("lo", Double.NaN);
            this.defenceWeightLateHi = (late == null) ? Double.NaN
                : late.optDouble("hi", Double.NaN);
            JSONObject slope = j.optJSONObject("skill_slope");
            this.skillSlope = (slope == null) ? Double.NaN
                : slope.optDouble("slope", Double.NaN);
            JSONObject fi = j.optJSONObject("fought_in");
            this.foughtInSites = (fi == null) ? 0 : fi.keySet().size();
            this.policyIpConditioned = (pol != null)
                && pol.optBoolean("ip_conditioned", false);
            this.policyIpGroupContaminated = (pol != null)
                && pol.optBoolean("ip_group_contaminated", false);
            JSONArray ipn = (pol == null) ? null : pol.optJSONArray("ip_n");
            int ips = 0;
            for(int i = 0; (ipn != null) && (i < ipn.length()); i++)
                ips += ipn.optInt(i, 0);
            this.policyIpN = ips;
            this.policyAttackShareAt0 = (pol == null) ? Double.NaN
                : pol.optDouble("attack_share_at_0_ip", Double.NaN);
            this.policyAttackShareAbove0 = (pol == null) ? Double.NaN
                : pol.optDouble("attack_share_above_0_ip", Double.NaN);
            this.threat = threat(j.optJSONObject("threat"), j, lib, ours,
                                 j.optString("name", "?"), "coef");
            /* THE SAME OPPONENT AT THE TOP OF ITS MEASURED DAMAGE. Every other stat on
             * this class is an interval and every matchup runs both ends of it; damage was
             * the one exception, because Pack read `coef` and dropped the `lo`/`hi` beside
             * it. The badger's coefficient is 27.6 with an interval of 8.9 to 123.5, a
             * factor of fourteen, so a plan priced at the median is not a plan priced at
             * the worst the corpus allows - and with a hard soak in the eighties the
             * difference is the whole answer, since nothing gets through until the raw
             * swing clears it. Null when the block carries no upper bound. */
            this.threatHi = threat(j.optJSONObject("threat"), j, lib, ours,
                                   j.optString("name", "?"), "hi");
            JSONObject sp = j.optJSONObject("relative_speed");
            if(sp == null) {
                this.speedLo = this.speedHi = Double.NaN;
                this.speedMedian = this.ourTop = Double.NaN;
                this.weOutrunIt = this.speedMeasured = false;
                this.speedN = 0;
            } else {
                this.speedMeasured = sp.optBoolean("measured", false);
                this.speedLo = sp.optDouble("lo", Double.NaN);
                this.speedHi = sp.optDouble("hi", Double.NaN);
                this.speedMedian = sp.optDouble("median", Double.NaN);
                this.ourTop = sp.optDouble("our_top", Double.NaN);
                this.weOutrunIt = sp.optBoolean("we_outrun_it", false);
                this.speedN = sp.optInt("n", 0);
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
                                       Map<String, Move> ours, String species,
                                       String coefKey) {
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
            /* The share of its swing our armour stopped, over all its cards - the averaged
             * action's penetration, as BeastMove.soaked is a card's. NaN where unmeasured. */
            double soak = Double.NaN;
            JSONObject dm = t.optJSONObject("damage");
            if(dm != null) {
                coef = dm.optDouble(coefKey, Double.NaN);
                nHits = dm.optInt("n", 0);
                soak = dm.optDouble("soaked_share", Double.NaN);
            }
            /* No upper bound means no pessimistic model, rather than one that quietly
             * falls back to the median and looks like a second opinion. */
            if(!"coef".equals(coefKey) && Double.isNaN(coef))
                return(null);
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
                                 * Null leaves the averaged action in place. Measured: 58
                                 * of the 61 modelled opponents take the card path, two
                                 * creatures (mammoth, troll) still need the average, and
                                 * six have no model at all. */
                                repertoire(j, lib, ours, species, coefKey), soak));
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
         * The hardest reading that is a REAL creature, or the pooled toughest() when the
         * pack ships no per-individual rows.
         *
         * toughest() takes an independent extreme on each axis and builds an animal that is
         * simultaneously the most defended, fastest, largest and strongest ever logged -
         * which the corpus contains none of. Where rows exist, each candidate is built from
         * ONE creature (individual()), so a consumer that cannot afford the deck-aware sweep
         * in CombatMatchup.hardest() still prices a real animal. The ordering is the same
         * one toughest() maximises - skill, then agility, then health, then armour - applied
         * to whole creatures instead of to each axis separately.
         */
        public Combatant hardestReal() {
            return(real(true));
        }

        /** The easiest real creature, or the pooled weakest() when none are shipped. */
        public Combatant weakestReal() {
            return(real(false));
        }

        private Combatant real(boolean hard) {
            Combatant best = null;
            for(Individual ind : individuals()) {
                Combatant c = individual(ind);
                if((c == null) || !(c.hp > 0))
                    continue;
                if((best == null) || (hard ? harder(c, best) : harder(best, c)))
                    best = c;
            }
            if(best != null)
                return(best);
            return(hard ? toughest() : weakest());
        }

        /** Whether a is the harder opponent in the ordering real() ranks on. */
        private static boolean harder(Combatant a, Combatant b) {
            if(more(a.blockSkill, b.blockSkill))
                return(true);
            if(less(a.blockSkill, b.blockSkill))
                return(false);
            if(more(a.agi, b.agi))
                return(true);
            if(less(a.agi, b.agi))
                return(false);
            if(more(a.hp, b.hp))
                return(true);
            if(less(a.hp, b.hp))
                return(false);
            return((a.armHard + a.armSoft) > (b.armHard + b.armSoft));
        }

        private static boolean more(double a, double b) {
            return(rank(a) > rank(b));
        }

        private static boolean less(double a, double b) {
            return(rank(a) < rank(b));
        }

        private static double rank(double v) {
            return(Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v);
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

        /**
         * One real creature of this species, as a Combatant, or null if it constrains nothing.
         *
         * THE POINT OF THIS IS THAT toughest() DESCRIBES NOBODY. It takes an independent
         * extreme on each of four axes, so the creature it builds is simultaneously the most
         * defended, fastest, largest and strongest ever seen - and the corpus contains no such
         * animal. Measured in the factor the simulator actually uses, the chimera's cooldown
         * factor sits a median 0.057 from the toughest individual really observed and 0.122 at
         * the tail, against a band 0.200 wide, always in the direction of an opponent harder
         * than any that was fought.
         *
         * So a caller that wants the hardest fight sweeps the individuals and takes the worst
         * OUTCOME, rather than assembling a worst INPUT on every axis at once.
         *
         * WHAT COMES FROM THE CREATURE AND WHAT DOES NOT, exactly, because a half-filled
         * individual is a smaller chimera and saying so is the only thing that stops it being
         * one silently:
         *
         *   agility     the creature's own, where it has one - the top of its interval, since
         *               a faster opponent lengthens OUR cooldowns. Usually a CAP rather than
         *               a reading: see Individual.agiCapped. Still far tighter than the
         *               species envelope, which is set by whichever observation was made at
         *               the highest agility and therefore loosens as the character trains
         *   skill       the species', always - see estimate.py's individuals() for why a
         *               per-creature skill cannot be published yet
         *   hitpoints   the damage that actually killed it; NaN where it survived, because a
         *               survivor's total is a floor on its hitpoints and not a reading
         *   armour      always the species reading - armour is fitted from pooled soak and
         *               the corpus does not separate it per creature
         *
         * Anything NaN falls back to the species entry, which is the same pooled extreme
         * toughest() would have used. So this is not a whole creature; it is a real animal
         * on the axes the corpus separated and its species on the rest, which is strictly
         * less of a chimera than four independent maxima and is said plainly rather than
         * implied.
         */
        public Combatant individual(Individual ind) {
            if(ind == null)
                return(null);
            double agi = Double.isNaN(ind.agiHi) ? ind.agiLo : ind.agiHi;
            double hp = Double.isNaN(ind.hp) ? planHpHi() : ind.hp;
            return(build(Double.isNaN(ind.skill) ? pick(skillHi, skill) : ind.skill,
                         Double.isNaN(agi) ? pick(agiHi, agiLo) : agi,
                         hp, pick(armHi, armLo)));
        }

        private Combatant build(double dw, double agi, double hp, double arm) {
            Combatant c = new Combatant(name);
            /* A skill, because that is what the corpus can actually recover - see
             * estimate.py's foe_skill_from. An animal holds no stance, so its multiplier
             * is 1 and its block weight is its skill. */
            c.blockSkill = dw;
            c.blockMult = 1.0;
            /* The endpoints arrive already resolved: the constructor turns an open agility
             * side into +infinity or zero rather than letting a NaN pick the WRONG end of
             * the interval in toughest()/weakest(). */
            c.agi = agi;
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
        /* And our own sheet, because a player opponent throws OUR cards and we have their
         * exact numbers. Feeding a player through the averaged action while the better
         * data sits loaded next to it was the same mistake one population over. */
        Map<String, Move> ours = null;
        try {
            Path sheet = path.resolveSibling("moves_sheet.json");
            if(Files.exists(sheet))
                ours = moves(sheet);
        } catch(IOException e) {
            ours = null;
        }
        Map<String, Opponent> out = opponents(read(path), lib, ours);
        /* The per-creature rows, where the corpus measured any. `hardest()` sweeps them;
         * anyone else who wants the hardest REAL opponent reads them off the Opponent.
         * Absence is not failure - a checkout whose corpus has not been regenerated has no
         * individuals.json and every reading falls back to the pooled one. */
        try {
            Path side = path.resolveSibling("individuals.json");
            if(Files.exists(side))
                attach(out, individuals(side));
        } catch(IOException e) {
            /* Per-individual rows are an optimisation, not a requirement. */
        }
        return(out);
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
         * How far the weapon reaches, as a multiple of the unarmed reach.
         *
         * A sword is 1.2 and a stone axe 1.0, and the corpus puts an unarmed swing and a
         * 1.0 weapon at the same 18.7 world units - see Formulas.UNARMED_REACH. NaN for a
         * character with nothing in hand, which is the same reach as 1.0.
         */
        public final double weaponRange;
        /**
         * The cards this character knows, by display name, and the level each sits at.
         *
         * THE VALUE IS THE LOADOUT, AND THIS DOCSTRING USED TO DENY IT. It said a level of
         * 0 meant "known but not currently on the bar - so this is ownership, not a
         * loadout"; the number the estimator writes here is the dump's `decklevel`, which
         * is exactly the bar. {@link #known} is the other one, the dump's `maxlevel`, and
         * the two differ by a lot: ZzxcuV3 has learned all 41 cards and slots 10 of them
         * for all 30 of 30 points.
         *
         * Which to read depends on the question. What do I throw RIGHT NOW ranges over
         * this map's non-zero entries; what deck should I BUILD ranges over {@link #known}
         * inside the point budget. Points are re-assignable freely inside the thirty, so a
         * search that ranges over levels is realistic and not a fantasy - the only thing a
         * character cannot do is play a card they have never learned.
         */
        public final Map<String, Integer> owned;

        /**
         * How far each card has been LEARNED, which is the set a deck can be built from.
         *
         * Empty on a pack written before this was published, and {@link #knows} then falls
         * back to {@link #owned}'s key set, which is what it always read.
         */
        public final Map<String, Integer> known;
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

        /**
         * How many gear rows the pack counted on the character, and its constitution and
         * maximum hitpoints, or 0 / NaN where the pack published none.
         *
         * `armour.pieces`, `con` and `hhp` were written by the recorder and read by
         * nothing. The piece count separates "no armour measured" from "no armour worn";
         * `hhp` is the pool grievous damage eats (the audit's M3), kept so a check can
         * name it even though the sim does not price it yet.
         */
        public final int armourPieces;
        public final double con, hhp;

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
            this.weaponRange = (w == null) ? Double.NaN
                : w.optDouble("range", Double.NaN);
            Map<String, Integer> own = new LinkedHashMap<String, Integer>();
            JSONObject od = j.optJSONObject("owned");
            if(od != null) {
                for(String k : od.keySet())
                    own.put(k, od.optInt(k, 0));
            }
            this.owned = own;
            Map<String, Integer> kn = new LinkedHashMap<String, Integer>();
            JSONObject kd = j.optJSONObject("known");
            if(kd != null) {
                for(String k : kd.keySet()) {
                    int v = kd.optInt(k, 0);
                    if(v > 0)
                        kn.put(k, v);
                }
            }
            this.known = kn;
            JSONObject arm = j.optJSONObject("armour");
            this.armHard = (arm == null) ? 0 : arm.optDouble("hard", 0);
            this.armSoft = (arm == null) ? 0 : arm.optDouble("soft", 0);
            this.armourPieces = (arm == null) ? 0 : arm.optInt("pieces", 0);
            this.con = j.optDouble("con", Double.NaN);
            this.hhp = j.optDouble("hhp", Double.NaN);
            this.shield = j.optBoolean("shield", false);
        }

        /**
         * Whether this character has learned the card at all - NOT whether it is slotted.
         *
         * Reads {@link #known} where the pack carries it. The fallback is {@link #owned}'s
         * key set, which is what this always read: the dump lists every card, so key
         * presence is a weaker version of the same question and was right by accident.
         */
        public boolean knows(String cardName) {
            if(!known.isEmpty())
                return(known.containsKey(cardName));
            return(owned.isEmpty() || owned.containsKey(cardName));
        }

        /** How many points of this card are slotted right now, zero if it is not. */
        public int slotted(String cardName) {
            Integer v = owned.get(cardName);
            return((v == null) ? 0 : v.intValue());
        }

        /** This character as the simulator takes them. */
        public Combatant combatant() {
            Combatant c = new Combatant(name);
            c.str = str; c.agi = agi; c.unarmed = unarmed; c.melee = melee;
            c.hp = c.maxHp = hp;
            c.weaponDamage = weaponDamage;
            c.weaponQl = (weaponQl > 0) ? weaponQl : 10;
            c.weaponPen = weaponPen;
            c.weaponRange = weaponRange;
            c.armHard = armHard;
            c.armSoft = armSoft;
            /* A PLAYER'S ARMOUR IS PENETRABLE, and Combatant defaults it false because
             * every armoured opponent in the corpus is an animal and the one that could
             * be tested turned out immune. Against a person the weapon's penetration is
             * exactly what it says it is. */
            c.penetrable = true;
            /* The hard pool, which the character sheet reports and grievous damage eats. */
            if(hhp > 0)
                c.hhp = hhp;
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

    /**
     * One creature, measured on its own rather than pooled with its species.
     *
     * Written by estimate.py's individuals() into data/combat/individuals.json, beside the
     * pack and not inside it: the running client parses opponents.json at startup and has no
     * use for 1,647 rows that only the offline sweep reads.
     *
     * Every field is that ONE creature's. agiLo/agiHi is the interval its own cooldowns
     * allow, skill its own median defence weight, hp the damage that actually killed it -
     * NaN where it survived, because a survivor's total is a floor on its hitpoints and
     * dressing a floor as a value is how the pooled entry came to describe an animal nobody
     * killed.
     */
    public static final class Individual {
        public final long gob;
        public final double agiLo, agiHi, skill, hp;
        /**
         * Whether this creature's agility ceiling is a measurement or the observer's limit.
         *
         * The cooldown factor is 1 - 0.1*clamp(log2(agiMe/agiFoe), -1, 1), so once an animal
         * is slower than half our agility the cooldown stops moving and every slower animal
         * reports the same ticks. Such an observation says "at most half OUR agility" and
         * nothing about how much less - a fact about the observer.
         *
         * It is most of the corpus: 1,417 of 1,647 per-creature agility readings are capped,
         * and the corpus was recorded while our own agility rose from 58 to 283, so the same
         * animal's ceiling reads four times looser at the end than at the start. A consumer
         * that treats it as a measurement watches every creature get faster as the character
         * trains.
         *
         * It is still a true upper bound and still worth using - the tightest per-creature
         * bound runs about four times tighter than the pooled species one (badger 36.3
         * against 161.1, bat 31.6 against 148.1) - but it is a bound, and anything reporting
         * it as a speed should say so.
         */
        public final boolean agiCapped;

        Individual(JSONObject j) {
            this.gob = j.optLong("gob", -1);
            JSONObject a = j.optJSONObject("agility");
            this.agiLo = ((a == null) || a.isNull("lo")) ? Double.NaN : a.optDouble("lo");
            this.agiHi = ((a == null) || a.isNull("hi")) ? Double.NaN : a.optDouble("hi");
            this.agiCapped = (a != null) && a.optBoolean("capped", false);
            /* Deliberately absent from the file today, and read anyway so that shipping it
             * later needs no change here. A per-creature skill is not safe to publish while
             * the equalization branch can differ between one animal's rows and its species':
             * the two answers then sit a factor apart with nothing to choose between them.
             * See estimate.py's individuals(). Until then this is NaN and the species skill
             * fills in, which is what the field below does for every unmeasured axis. */
            JSONObject d = j.optJSONObject("skill");
            this.skill = ((d == null) || d.isNull("value")) ? Double.NaN : d.optDouble("value");
            JSONObject h = j.optJSONObject("hitpoints");
            this.hp = ((h == null) || h.isNull("value")) ? Double.NaN : h.optDouble("value");
        }

        public String toString() {
            return("gob " + gob + " agi " + agiLo + "-" + agiHi + (agiCapped ? " (at the cap)" : "")
                   + " skill " + skill + (Double.isNaN(hp) ? " (survived)" : " hp " + hp));
        }
    }

    /** Every measured individual, by species name. See Individual. */
    public static Map<String, List<Individual>> individuals(Path path) throws IOException {
        return(individuals(read(path)));
    }

    /** The same, already parsed - the jar path parses from a slurped String. */
    static Map<String, List<Individual>> individuals(JSONObject doc0) {
        Map<String, List<Individual>> out = new LinkedHashMap<String, List<Individual>>();
        JSONObject doc = doc0.optJSONObject("species");
        if(doc == null)
            return(out);
        for(String name : doc.keySet()) {
            JSONArray arr = doc.optJSONArray(name);
            if(arr == null)
                continue;
            List<Individual> rows = new ArrayList<Individual>();
            for(int i = 0; i < arr.length(); i++)
                rows.add(new Individual(arr.getJSONObject(i)));
            out.put(name, rows);
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
     * A card OUR sheet knows, as an opponent's card.
     *
     * A player opponent throws the same cards we do, and we have their exact numbers -
     * the openings in percentage points rather than the fitted ratios an animal's card
     * comes as, the cooldown, the grievous share, and the reductions. There is no reason
     * to feed a player through the averaged action when the better data is already
     * loaded.
     *
     * Damage is the one thing the sheet cannot give, because it depends on their weapon
     * and their strength and a log records neither. What a log does record is how hard
     * they actually hit, so the player's measured coefficient is handed to the cards that
     * deal damage, in proportion to the share of the weapon each one swings. A card that
     * deals none gets none.
     *
     * @param coef the player's measured whole-swing coefficient, or NaN when unmeasured.
     * @param norm the mix-weighted mean damage share, so the split preserves the total.
     */
    public static BeastMove fromOurCard(Move m, double coef, double norm) {
        if(m == null)
            return(null);
        double[] op = new double[4];
        for(int c = 0; c < 4; c++)
            op[c] = m.openings[c];
        double[] rest = new double[4];
        for(int c = 0; c < 4; c++)
            rest[c] = m.reduces[c];
        double mine = Double.NaN;
        if(!Double.isNaN(coef) && (norm > 0)) {
            double share = (m.damageShare > 0) ? m.damageShare
                : ((m.flatDamage > 0) ? 1.0 : 0.0);
            if(share > 0)
                mine = coef * (share / norm);
        }
        return(new BeastMove(m.name, op, mine, Math.round(m.cooldownBase), rest,
                             m.grievous, Double.NaN));
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
        return(repertoire(j, lib, null, species));
    }

    public static Repertoire repertoire(JSONObject j, Cards lib, Map<String, Move> ours,
                                        String species) {
        return(repertoire(j, lib, ours, species, "coef"));
    }

    /**
     * @param coefKey which end of the measured damage interval to split across the cards -
     *                "coef" for the expected fight, "hi" for the worst the corpus allows.
     */
    public static Repertoire repertoire(JSONObject j, Cards lib, Map<String, Move> ours,
                                        String species, String coefKey) {
        if((lib == null) && (ours == null))
            return(null);
        JSONObject pol = j.optJSONObject("policy");
        JSONArray mixa = (pol == null) ? null : pol.optJSONArray("mix");
        if((mixa == null) || (mixa.length() == 0))
            return(null);
        List<BeastMove> cards = new ArrayList<BeastMove>();
        List<Double> share = new ArrayList<Double>();
        double tot = 0;
        /* A player's measured hitting power, to be split across the cards that hit. */
        JSONObject th = j.optJSONObject("threat");
        JSONObject dm = (th == null) ? null : th.optJSONObject("damage");
        double coef = (dm == null) ? Double.NaN : dm.optDouble(coefKey, Double.NaN);
        double norm = 0;
        if((ours != null) && !Double.isNaN(coef)) {
            for(int i = 0; i < mixa.length(); i++) {
                JSONArray row = mixa.getJSONArray(i);
                Move om = ours.get(row.getString(0));
                if(om == null)
                    continue;
                double sh = (om.damageShare > 0) ? om.damageShare
                    : ((om.flatDamage > 0) ? 1.0 : 0.0);
                norm += sh * row.getDouble(1);
            }
        }
        for(int i = 0; i < mixa.length(); i++) {
            JSONArray row = mixa.getJSONArray(i);
            String nm = row.getString(0);
            /* OUR SHEET FIRST, WHERE IT KNOWS THE CARD. The two libraries overlap on the
             * cards a player throws at us: those land in the measured animal file too,
             * because a foe move is a foe move whoever threw it - and the fit there never
             * estimated their openings, so Quick Barrage came through opening nothing
             * while our own sheet had its exact ten points of red sitting loaded.
             *
             * The overlap is only ever our cards. An animal's Fell Scratch is not in our
             * sheet, so it falls through to the measured file as before. */
            BeastMove m = (ours == null) ? null : fromOurCard(ours.get(nm), coef, norm);
            if((m == null) && (lib != null))
                m = lib.move(nm, species);
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
        /* THE MEASURED MIXTURE, WHERE THE CORPUS IS THICK ENOUGH TO HAVE ONE. policy_model
         * is null under its own min_n sampled choices, and its weights only replace
         * policy.mix at or above that floor; below it the shipped mixture stands as before. */
        double[] weights = policyWeights(j, cards);
        if(weights != null)
            mix = weights;
        Repertoire.Gate[] gates = policyGates(j, cards);

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
        return(new Repertoire(cards.toArray(new BeastMove[0]), mix, feat, cut, when, other,
                              gates));
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

    /**
     * The measured mixture, or null when the corpus is too thin to beat the shipped one.
     *
     * policy_model carries its own floor in min_n; below it the sampled choices are too few
     * to estimate a mixture from, so the caller keeps policy.mix. Above it, weights are the
     * same card order and shape as policy.mix, matched here onto the cards we kept.
     */
    private static double[] policyWeights(JSONObject j, List<BeastMove> cards) {
        JSONObject pm = j.optJSONObject("policy_model");
        if(pm == null)
            return(null);
        if(pm.optInt("n", 0) < pm.optInt("min_n", 0))
            return(null);
        JSONArray arr = pm.optJSONArray("weights");
        if((arr == null) || (arr.length() == 0))
            return(null);
        double[] out = new double[cards.size()];
        double tot = 0;
        for(int i = 0; i < arr.length(); i++) {
            JSONArray row = arr.optJSONArray(i);
            if(row == null)
                continue;
            String nm = row.optString(0, null);
            for(int k = 0; k < cards.size(); k++) {
                if(cards.get(k).name.equals(nm)) {
                    double w = row.optDouble(1, 0);
                    out[k] += w;
                    tot += w;
                }
            }
        }
        if(tot <= 0)
            return(null);
        for(int i = 0; i < out.length; i++)
            out[i] /= tot;
        return(out);
    }

    /**
     * The threshold gates the corpus reproduced, or null.
     *
     * Only rows whose reproduces flag is true become gates - the estimator sets it from a
     * held-out test on rows the cut was not chosen on (estimate.threshold_test), and `lift`
     * is the HELD-OUT lift, so the multiplier applied is the one that survived. A row
     * flagged false is a reading the estimator recorded and declined to licence, and that
     * includes the wiki's bat claim unless the corpus bears it out.
     * A row is also dropped if its card was not kept, its colour is unknown, or its lift is
     * not a positive factor.
     */
    private static Repertoire.Gate[] policyGates(JSONObject j, List<BeastMove> cards) {
        JSONObject pm = j.optJSONObject("policy_model");
        JSONArray arr = (pm == null) ? null : pm.optJSONArray("thresholds");
        if(arr == null)
            return(null);
        List<Repertoire.Gate> gates = new ArrayList<Repertoire.Gate>();
        for(int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if((row == null) || !row.optBoolean("reproduces", false))
                continue;
            String nm = row.optString("move", null);
            int card = -1;
            for(int k = 0; k < cards.size(); k++) {
                if(cards.get(k).name.equals(nm)) {
                    card = k;
                    break;
                }
            }
            int colour = colourIndex(row.opt("colour"));
            double lift = row.optDouble("lift", 1.0);
            if((card < 0) || (colour < 0) || !(lift > 0))
                continue;
            boolean onTarget = !"self".equals(row.optString("on", "target"));
            gates.add(new Repertoire.Gate(card, colour, onTarget,
                                          row.optDouble("cut", 0), lift));
        }
        if(gates.isEmpty())
            return(null);
        return(gates.toArray(new Repertoire.Gate[0]));
    }

    /** A threshold's colour as an index, or -1: the file names colours, our arrays order them. */
    private static int colourIndex(Object c) {
        if(c instanceof Number)
            return(((Number)c).intValue());
        String s = String.valueOf(c);
        if("green".equals(s))
            return(0);
        if("blue".equals(s))
            return(1);
        if("yellow".equals(s))
            return(2);
        if("red".equals(s))
            return(3);
        return(-1);
    }

    private static Map<String, Opponent> opponents(JSONObject doc, Cards lib) {
        return(opponents(doc, lib, null));
    }

    private static Map<String, Opponent> opponents(JSONObject doc, Cards lib,
                                                   Map<String, Move> ours) {
        Map<String, Opponent> out = new LinkedHashMap<String, Opponent>();
        JSONArray arr = doc.getJSONArray("opponents");
        for(int i = 0; i < arr.length(); i++) {
            Opponent o = new Opponent(arr.getJSONObject(i), lib, ours);
            out.put(o.name, o);
        }
        return(out);
    }
}
