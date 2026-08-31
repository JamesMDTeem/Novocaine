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

    public static String state(long t, Openings mine, Openings foe, int myIp, int foeIp,
                               int hp, double stam, double energy, double dist, long gobId) {
        return(new JsonObj()
               .put("ev", "state")
               .put("t", t)
               .put("gob", gobId)
               .raw("mine", mine.toJson())
               .raw("foe", foe.toJson())
               .put("myip", myIp)
               .put("foeip", foeIp)
               .put("hp", hp)
               .put("stam", stam)
               .put("energy", energy)
               .put("dist", dist)
               .end());
    }

    public static String move(long t, String actor, String moveRes, double cooldownTicks, long gobId) {
        return(new JsonObj()
               .put("ev", "move")
               .put("t", t)
               .put("actor", actor)
               .put("gob", gobId)
               .put("move", moveRes)
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
