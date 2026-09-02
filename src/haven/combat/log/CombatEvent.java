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
    public static final int SCHEMA = 3;

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
     */
    public static String end(long t, String reason) {
        return(new JsonObj()
               .put("ev", "end")
               .put("t", t)
               .put("reason", reason)
               .end());
    }

    public static String state(long t, Openings mine, Openings foe, int myIp, int foeIp,
                               int hpf, double stam, double energy, double dist, long gobId) {
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
