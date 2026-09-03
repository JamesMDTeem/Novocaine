package haven.combat.log;

/**
 * The combat log event schema. Each factory returns one JSONL line, without its newline.
 *
 * These strings are the contract the offline analysis reads. Changing a key name breaks every
 * log already collected, so the checks in tools/CombatLogCheck.java pin them exactly.
 *
 * Note there is no "openings after" on a move event. The client records what it saw and when;
 * pairing a move with the state before and after it is done offline, so no settle window is
 * ever baked irreversibly into a log.
 */
public final class CombatEvent {
    private CombatEvent() {}

    /** Bumped whenever a key is added, renamed or given a new meaning. Logs below 2 have no header. */
    public static final int SCHEMA = 10;

    /**
     * The header line, first in every file. Without it a log is unlabelled: it says nothing about
     * who fought, what they fought, or with which stats - and those are exactly the "given our
     * stats" half of every estimate the offline analysis makes. attrComp is what the server
     * fights with (gear and buffs applied); attrBase is the unmodified sheet.
     */
    public static String begin(long t, long wall, int schema, String charName, long meGob,
                               long foeGob, String foeRes,
                               java.util.SortedMap<String, Integer> attrBase,
                               java.util.SortedMap<String, Integer> attrComp,
                               int armHard, int armSoft) {
        return(new JsonObj()
               .put("ev", "begin")
               .put("t", t)
               .put("wall", wall)
               .put("schema", schema)
               .put("char", charName)
               .put("megob", meGob)
               .put("foegob", foeGob)
               .put("foeres", foeRes)
               .raw("attrb", attrs(attrBase))
               .raw("attr", attrs(attrComp))
               .put("hard", armHard)
               .put("soft", armSoft)
               .end());
    }

    private static String attrs(java.util.SortedMap<String, Integer> m) {
        JsonObj o = new JsonObj();
        if(m != null) {
            for(java.util.Map.Entry<String, Integer> e : m.entrySet())
                o.put(e.getKey(), (long)e.getValue().intValue());
        }
        return(o.end());
    }

    /** One per equipped item, emitted immediately after begin. Absent slots emit nothing. */
    public static String gear(long t, int slot, String res, double ql, int hard, int soft,
                              boolean broken) {
        return(new JsonObj()
               .put("ev", "gear")
               .put("t", t)
               .put("slot", slot)
               .put("res", res)
               .put("ql", ql)
               .put("hard", hard)
               .put("soft", soft)
               .put("broken", broken)
               .end());
    }

    /**
     * An opponent entering, leaving, or becoming the one the state events describe.
     *
     * The header names exactly one opponent, which is a lie in any fight against more than one.
     * The client samples whichever relation is current, and switching targets makes consecutive
     * state events describe different creatures - so an opening that appears to leap is really
     * the sampler moving to a fresh opponent. One logged fight switches three times across ninety
     * seconds while its header names only the first.
     *
     * The state and damage events already carry a gob, so the switch is recoverable after the
     * fact; what is not recoverable is what the other opponents WERE. That is what this event
     * adds.
     *
     * @param how "new" when a relation appears, "current" when the sampled one changes, "del"
     *            when one leaves, and "name" when a resource that was still loading resolves.
     *            The last matters more than it sounds: a relation that arrives before its gob
     *            does is logged with a null res, which is how one whole fight came to record no
     *            opponent at all.
     */
    public static String foe(long t, long gob, String res, String how) {
        return(new JsonObj()
               .put("ev", "foe")
               .put("t", t)
               .put("gob", gob)
               .put("res", res)
               .put("how", how)
               .end());
    }

    /**
     * The terminal line. Its absence is itself information: a file with no end event was cut off
     * by a crash or a kill, and must not be treated as a complete fight.
     *
     * It also carries the writer's health at the moment the fight ended. A full queue drops
     * lines silently, and a dropped move - unlike a dropped state - leaves no guard behind:
     * brackets() stops at moves it can see, so a move it cannot see lets its gain merge
     * into a neighbour's. A file that lost lines must say so here rather than read as
     * complete.
     *
     * @param dropped lines the queue shed on a full buffer over this fight's life
     * @param failed  true when the drain thread had already died, so lines stopped
     *                reaching disk before the end was written
     */
    public static String end(long t, String reason, int dropped, boolean failed) {
        return(new JsonObj()
               .put("ev", "end")
               .put("t", t)
               .put("reason", reason)
               .put("dropped", (long)dropped)
               .put("failed", failed)
               .end());
    }

    /**
     * @param mySpeed  our own movement speed in world units per second, 0 when standing
     * @param foeSpeed the opponent's, the same figure the client draws in white under it
     */
    public static String state(long t, Openings mine, Openings foe, int myIp, int foeIp,
                               int hpf, double stam, double energy, double dist, long gobId,
                               double mySpeed, double foeSpeed, int gst, String tile) {
        return(new JsonObj()
               .put("ev", "state")
               .put("t", t)
               .put("gob", gobId)
               .raw("mine", mine.toJson())
               .raw("foe", foe.toJson())
               .put("myip", myIp)
               .put("foeip", foeIp)
               .put("hpf", hpf)
               .put("stam", stam)
               .put("energy", energy)
               .put("dist", dist)
               /* Speed, straight from Gob.gobSpeed - the Moving attribute's own velocity,
                * which is what the client already renders under every moving object. It
                * replaces inferring a speed from how fast the gap opened, which could not
                * tell "this creature is fast" from "we never backed away from it" and
                * reported a fox as keeping pace with us because we always stood and
                * fought them. Creature speed is randomised per individual within a
                * species range, so this is a distribution rather than a constant. */
               .put("myspd", mySpeed)
               .put("foespd", foeSpeed)
               /* The aggression state, which is how a flight shows up. Bit 1 is our olive
                * branch and bit 2 is theirs, matching the colours the client draws - and an
                * animal extends one when it has taken enough damage and starts to run. A
                * fleeing animal stops fighting back, so every point of initiative and every
                * reduction bought after this moment is spent on nothing. */
               .put("gst", gst)
               /* The tile underfoot. Terrain gates our own speed - forest holds us to a run
                * where grassland allows a sprint - so a speed reading means nothing without
                * knowing what we were standing on. */
               .put("tile", tile)
               .end());
    }

    /**
     * Every opponent's openings, not only the sampled one's.
     *
     * The game draws opening pips over every creature in the fight, and the client sees all
     * of them - but only the current target was ever recorded. That single omission is what
     * makes a group fight unmeasurable: when another player attacks our boar, the boar's
     * openings jump and nothing in the log says whether we or they did it. Our own moves are
     * logged and theirs are not, so the only way to tell is to watch creatures we are NOT
     * hitting. A rise on one of those is proof that someone else is swinging, and its timing
     * bounds when.
     *
     * `o` is a flat array of gob, green, blue, yellow, red per relation, which keeps the line
     * short in a file that already writes one state per sample.
     *
     * @param packed gob and four openings per relation, five entries each
     */
    public static String foes(long t, long[] packed) {
        StringBuilder b = new StringBuilder("[");
        for(int i = 0; i < packed.length; i += 5) {
            if(i > 0)
                b.append(',');
            b.append('[').append(packed[i]);
            for(int j = 1; j < 5; j++)
                b.append(',').append(packed[i + j]);
            b.append(']');
        }
        b.append(']');
        return(new JsonObj()
               .put("ev", "foes")
               .put("t", t)
               .raw("o", b.toString())
               .end());
    }

    /**
     * An overlay that appeared on a player's body - a candidate move announcement.
     *
     * `gob` and `gobres` say who, `res` says which overlay. Recorded broadly on purpose:
     * the resource carrying a move's icon is not documented, and one logged group fight
     * names it more reliably than reading render code does.
     */
    public static String overlay(long t, long gobId, String gobRes, String olRes) {
        return(new JsonObj()
               .put("ev", "overlay")
               .put("t", t)
               .put("gob", gobId)
               .put("gobres", gobRes)
               .put("res", olRes)
               .end());
    }

    /**
     * The buffs standing on one combatant - which is how a STANCE becomes visible.
     *
     * An opponent's defence weight is their skill times their stance's block multiplier
     * times their mu, and the wiki's own worked example spells that out: "a player who is
     * in lvl 4 Chin Up defense mode ... defense weight will be 50 * 1.4". So a player's Wd
     * is not one number. Ours reads from 62 in Shield Up without a shield to 313 with one,
     * and this corpus has players measured anywhere from 3 to 393 - a factor of seventy
     * being reported as if it were a property of the person.
     *
     * The client has always had this. readOpenings walks exactly this list and keeps the
     * four opening paginae, so the stance was being seen and thrown away on every sample.
     */
    public static String buffs(long t, long gobId, String who, String[] res) {
        StringBuilder b = new StringBuilder("[");
        for(int i = 0; i < res.length; i++) {
            if(i > 0)
                b.append(',');
            b.append('"').append(JsonObj.esc(res[i])).append('"');
        }
        b.append(']');
        return(new JsonObj()
               .put("ev", "buffs")
               .put("t", t)
               .put("gob", gobId)
               .put("who", who)
               .raw("res", b.toString())
               .end());
    }

    /**
     * A move event carries both identifiers. `move` is the resource name the server sends;
     * `name` is the tooltip the client renders for it. The two have no derivable relation
     * ("knockteeth" against "Knock Its Teeth Out"), and the wiki-derived data pack is keyed
     * on the second, so recording only the first would leave every logged fight unjoinable.
     * `name` is null when the resource has no tooltip layer.
     */
    public static String move(long t, String actor, String moveRes, String moveName,
                              double cooldownTicks, long gobId) {
        return(new JsonObj()
               .put("ev", "move")
               .put("t", t)
               .put("actor", actor)
               .put("gob", gobId)
               .put("move", moveRes)
               .put("name", moveName)
               .put("cd", cooldownTicks)
               .end());
    }

    /**
     * What the model expected this move to do, written at the moment it was thrown.
     *
     * THE POINT IS THAT IT IS WRITTEN DOWN. A residual can always be recomputed later by
     * running today's model over an old log, and that is a different and worse thing: every
     * change to the data pack silently rewrites the history, so a fix can never be shown to
     * have helped because the "before" number moves with it. A prediction in the file is a
     * fact about what the model believed on the day, and it stays that way.
     *
     * {@code pack} identifies the data the prediction came from, for the same reason.
     *
     * This is deliberately written on OUR moves during ordinary play, not on a bot's. A model
     * that also chooses the fights only ever gets asked about the cases it already gets right:
     * moves it dislikes are never thrown, so their errors are never measured, so it goes on
     * disliking them - and the residuals FALL while that happens. Logging predictions against
     * a human's choices is what keeps the sample honest.
     *
     * Absent for a move the model cannot predict - an unresolved weapon, an opponent whose
     * skill the corpus never recovered - because a prediction of zero would enter the
     * residuals as a large error by the model rather than as a gap in the inputs.
     */
    public static String predict(long t, long gobId, String moveRes, String pack,
                                 double[] openings, double dealt, double grievous,
                                 long cooldown) {
        StringBuilder b = new StringBuilder("[");
        for(int i = 0; i < openings.length; i++) {
            if(i > 0)
                b.append(',');
            b.append(JsonObj.num(openings[i]));
        }
        b.append(']');
        return(new JsonObj()
               .put("ev", "predict")
               .put("t", t)
               .put("gob", gobId)
               .put("move", moveRes)
               .put("pack", pack)
               .raw("opened", b.toString())
               .put("dealt", dealt)
               .put("grievous", grievous)
               .put("cd", cooldown)
               .end());
    }

    /**
     * Who is in our party when the fight starts.
     *
     * This does NOT change a gate today, and saying so is the point. Whether our offence
     * can be measured turns on somebody else hitting OUR opponent, and whether our defence
     * can be measured turns on more than one thing hitting US; neither question cares
     * whether the third party is a friend or a stranger, so both stay spoiled either way.
     *
     * It is recorded because it is the only thing that makes the question ANSWERABLE. The
     * corpus cannot currently tell a fight a friend joined from one a stranger interfered
     * in, so it cannot test whether the two behave differently - and a distinction that
     * cannot be tested gets assumed instead. One line per fight settles it either way.
     *
     * @param gobs party members' gob ids, ours included
     */
    public static String party(long t, long[] gobs) {
        StringBuilder b = new StringBuilder("[");
        for(int i = 0; i < gobs.length; i++) {
            if(i > 0)
                b.append(',');
            b.append(gobs[i]);
        }
        b.append(']');
        return(new JsonObj()
               .put("ev", "party")
               .put("t", t)
               .raw("gobs", b.toString())
               .end());
    }

    /**
     * The client's OWN agility bracket for an opponent, narrowed as the fight goes on.
     *
     * Not a measurement this project made. Fightsess already infers it: every attack's
     * reported cooldown against that card's base pins a ratio between our agility and the
     * opponent's, and the bracket tightens with each attack (Fightview.Relation.minAgi and
     * maxAgi, narrowed through Config.attackCooldownNumbers).
     *
     * Which makes it the one thing this corpus is short of - an INDEPENDENT reading of a
     * quantity the estimators also recover. Two methods that agree are a control; two that
     * disagree name a bug in one of them. Every mu error this project has made survived
     * because the quantity had no second opinion, so recording one costs a line per
     * narrowing and buys the check that would have caught them.
     *
     * Logged only when the bracket moves. It starts at (0, 2) meaning "unknown".
     */
    public static String agility(long t, long gob, double min, double max) {
        return(new JsonObj()
               .put("ev", "agi")
               .put("t", t)
               .put("gob", gob)
               .put("min", min)
               .put("max", max)
               .end());
    }

    /**
     * Three resources the server sends about the exchange, whose meaning is not documented.
     *
     * "blk", and "atk" carrying two, arrive on the fight widget and are read into fields
     * that NOTHING in this client consumes - they are set and dropped. Their names suggest
     * a block and a pair of attacks, and that guess is not worth writing into a model.
     *
     * So this records them the same way overlays are recorded: broadly, without
     * interpretation, on the grounds that one logged fight identifies them more reliably
     * than reading render code does - and unlike render code they cannot be recovered
     * retroactively, because nothing stores them.
     *
     * @param blk  the "blk" resource
     * @param batk the first resource of "atk"
     * @param iatk the second
     */
    public static String atkres(long t, String blk, String batk, String iatk) {
        return(new JsonObj()
               .put("ev", "atkres")
               .put("t", t)
               .put("blk", blk)
               .put("batk", batk)
               .put("iatk", iatk)
               .end());
    }

    /**
     * A weapon's own figures, as the server states them on the item.
     *
     * The data pack's weapon table is scraped from the wiki and joined on the resource
     * BASENAME, which misses silently and leaves four of twenty-six weapons with no
     * recorded penetration - so those declined to predict for want of a number the item
     * was carrying all along. This records what the item itself says, so a log can be
     * read back without the table and a weapon nobody has catalogued is still usable.
     *
     * Keys are the tooltip class in lower case - damage, armpen, coolmod, grievous,
     * range. Penetration and grievous arrive as 0..1 fractions, already divided by the
     * hundred, which is the form Formulas wants; damage is a flat integer.
     */
    public static String weapon(long t, int slot, String res, java.util.Map<String, Double> stats) {
        JsonObj o = new JsonObj()
            .put("ev", "wpn")
            .put("t", t)
            .put("slot", slot)
            .put("res", res);
        JsonObj v = new JsonObj();
        if(stats != null) {
            for(java.util.Map.Entry<String, Double> e : stats.entrySet())
                v.put(e.getKey(), e.getValue().doubleValue());
        }
        return(o.raw("v", v.end()).end());
    }

    /**
     * A combatant's health, as the server states it.
     *
     * Everything this corpus knows about an opponent's hitpoints is otherwise accumulated
     * from damage numbers, which needs a KILL to close the interval - three species in the
     * pack have no ceiling because nothing has died yet, and a survivor can only ever give
     * a lower bound. This arrives unprompted, from the same object channel the damage
     * floats do, and it bounds a live opponent.
     *
     * COARSE ON PURPOSE, and the log says so by carrying the raw quarter rather than a
     * prettier number: the server sends a uint8 that the client divides by four, so the
     * only distinguishable values are 0, 1, 2, 3 and 4. That is enough to bound a maximum
     * and enough to time a flight, and it is not enough to read a single hit off.
     *
     * Its other use is FoeModel.fleesBelow, which the model asserts and no logged fight has
     * ever measured: gst says an animal extended its olive branch, and this says at what
     * fraction of its health it decided to.
     *
     * @param quarters the server's own figure, 0 to 4 - not a fraction, so that a reader
     *                 cannot mistake the resolution for something finer than it is
     */
    public static String health(long t, long gobId, int quarters) {
        return(new JsonObj()
               .put("ev", "hp")
               .put("t", t)
               .put("gob", gobId)
               .put("q", quarters)
               .end());
    }

    public static String damage(long t, long gobId, String channel, int value) {
        return(new JsonObj()
               .put("ev", "dmg")
               .put("t", t)
               .put("gob", gobId)
               .put("ch", channel)
               .put("v", value)
               .end());
    }
}
