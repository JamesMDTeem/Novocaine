package haven.automated;

import haven.Coord3f;
import haven.Gob;
import haven.Loading;
import haven.MapView;
import haven.Resource;
import haven.automated.nbots.core.NLog;

import java.util.Collection;

/**
 * Records how the client's idea of "which object am I" behaves, into {@code logs/plgob.log}.
 *
 * Why this exists: a player reported the camera locking to the point they entered a map instance -
 * at their spawn on login, on the mine hole when they climbed down - with click-to-move going wrong
 * in the same breath, "the client thinks I'm the mine hole". Everything that asks where the player
 * is goes through {@link MapView#getcc()}, which reads {@code glob.oc.getgob(plgob)}. That lookup
 * has two distinct ways to be wrong, they produce the same symptom on screen, and no existing log
 * separates them:
 *
 * <ul>
 *   <li>The id resolves to <em>nothing</em>. Objects are keyed by id in a MultiMap whose get()
 *       returns null when more than one object shares the key, which happens for a moment whenever
 *       an instance change reissues the player object. getcc() then falls back to the MapView's own
 *       {@code cc} - the entry point - and stays there for as long as the ambiguity lasts.</li>
 *   <li>The id resolves to the <em>wrong object</em>. plgob is stale and now names something else in
 *       the new instance, so the client faithfully follows a mine hole.</li>
 * </ul>
 *
 * One line of log tells the two apart: the first reports a gob count of 0 or 2+, the second reports
 * a resolved object whose resource is not a player body. That is the whole purpose of this class,
 * so it deliberately logs the object count and the resource name rather than just "player was null".
 *
 * Cost is a field compare per frame in the common case, and a log line only when the answer changes
 * or stops arriving - not per frame, or a stuck client would bury its own evidence.
 */
public class PlgobWatch {
    private static final String LOG = "plgob.log";

    /** How long the player id may be unresolvable before it stops being an ordinary handover. */
    private static final long GRACE_MS = 2000;

    private long lastid = Long.MIN_VALUE;
    private String lastres = null;
    private long lostsince = 0;
    private boolean reported = false;

    /**
     * Called once per frame from {@link MapView#tick}.
     *
     * @param mv the map view whose player resolution is being watched
     */
    public void tick(MapView mv) {
        /* This runs on the UI thread inside MapView.tick. A diagnostic that can break a frame is
         * worse than the bug it was added to find, so nothing in here is allowed out. */
        try {
            check(mv);
        } catch (RuntimeException e) {
            // Nothing useful to do about it, and saying so every frame would be its own problem.
        }
    }

    private void check(MapView mv) {
        Gob pl = mv.player();
        if (pl != null) {
            resolved(mv, pl);
            return;
        }
        unresolved(mv);
    }

    private void resolved(MapView mv, Gob pl) {
        if (reported) {
            NLog.log(LOG, String.format("player id %d resolves again after %dms -> %s",
                mv.plgob, System.currentTimeMillis() - lostsince, resname(pl)));
            reported = false;
        }
        lostsince = 0;

        /* The identity itself is the other half of the evidence. Logging it whenever the id changes
         * catches the stale-plgob case, where the lookup succeeds and hands back the wrong thing.
         *
         * Once an id's name is on record there is nothing more to learn from it, so the steady state
         * costs one field compare a frame and never touches the resource. */
        if (mv.plgob == lastid && lastres != null)
            return;
        if (mv.plgob != lastid) {
            lastid = mv.plgob;
            lastres = null;
        }
        /* An object arrives before its resource does, so a missing name means "not yet" rather than
         * "nameless" - wait for a later frame instead of recording a blank identity. */
        String res = resname(pl);
        if (res == null)
            return;
        lastres = res;
        NLog.log(LOG, String.format("player id %d -> %s", mv.plgob, res));
    }

    private void unresolved(MapView mv) {
        long now = System.currentTimeMillis();
        if (lostsince == 0) {
            lostsince = now;
            return;
        }
        if (reported || (now - lostsince < GRACE_MS))
            return;
        reported = true;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("player id %d unresolved for %dms", mv.plgob, now - lostsince));
        try {
            Collection<Gob> sharing = mv.glob.oc.getgobs(mv.plgob);
            sb.append("; ").append(sharing.size()).append(" object(s) hold that id");
            if (!sharing.isEmpty()) {
                sb.append(" [");
                boolean first = true;
                for (Gob g : sharing) {
                    if (!first)
                        sb.append(", ");
                    first = false;
                    String n = resname(g);
                    sb.append((n == null) ? "?" : n);
                }
                sb.append(']');
            }
        } catch (RuntimeException e) {
            sb.append("; could not read the object cache: ").append(e);
        }
        sb.append("; position now reads ").append(where(mv));
        NLog.log(LOG, sb.toString());
    }

    /** Where the client currently believes the player is - the value the camera and clicks use. */
    private static String where(MapView mv) {
        try {
            Coord3f c = mv.getcc();
            return String.format("(%.1f, %.1f)", c.x, c.y);
        } catch (Loading l) {
            return "<loading>";
        } catch (RuntimeException e) {
            return "<" + e + ">";
        }
    }

    /** Null while the resource has not loaded yet, which is not the same as having no resource. */
    private static String resname(Gob g) {
        try {
            Resource res = g.getres();
            return (res == null) ? null : res.name;
        } catch (Loading l) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
