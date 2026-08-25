package haven.automated;

import haven.Coord3f;
import haven.Gob;
import haven.Loading;
import haven.Matrix4f;
import haven.MapView;
import haven.Resource;
import haven.automated.nbots.core.NLog;

import java.util.Collection;

/**
 * Records why the camera has stopped following the player, into {@code logs/plgob.log}.
 *
 * Why this exists: a player reported the camera locking to the point they entered a map instance -
 * their spawn on login, the mine hole when they climbed down, and once on switching character -
 * while their character walked around unfollowed and click-to-move went with it. Several unrelated
 * faults produce that same picture, so this watches the symptom directly and records enough beside
 * it to say which one happened.
 *
 * <ul>
 *   <li><b>The player id resolves to nothing.</b> Objects are keyed by id in a MultiMap whose get()
 *       returns null when more than one object shares the key, which happens for a moment whenever
 *       an instance change reissues the player object. {@link MapView#getcc()} then falls back to
 *       the MapView's own {@code cc} - the entry point - for as long as the ambiguity lasts.
 *       Reported with the object count, which is 0 or 2+ in that case.</li>
 *   <li><b>The id resolves to the wrong object.</b> plgob is stale and now names something else in
 *       the new instance, so the client faithfully follows a mine hole. Reported as a resolved
 *       object whose resource is not a player body.</li>
 *   <li><b>The camera is never reached.</b> Ticking stops, or aborts partway down
 *       {@link MapView#tick} before the camera, while drawing carries on - so the world still
 *       renders around a camera that has stopped. Counted by {@link #enter} against {@link #tick}
 *       and noticed from {@link #drawn}, since drawing is the only thing still running.</li>
 *   <li><b>The camera ticks but tracks the wrong thing.</b> Caught by the drift measurement
 *       below, which is indifferent to the cause.</li>
 * </ul>
 *
 * The drift measurement is the load-bearing part: the player's position is projected through the
 * camera's own transform, and a camera that is tracking holds them near one spot in its view however
 * far they walk. Ground covered versus drift across the view therefore separates "following" from
 * "not following" without needing to know why. It is deliberately measured in view space rather
 * than screen space - screen coordinates carry a perspective divide, and a point near the near
 * plane produces garbage that swamps an accumulated total.
 *
 * Cost is a field compare per frame in the common case, and a log line only when an answer changes
 * or stops arriving - never per frame, or a stuck client would bury its own evidence.
 */
public class PlgobWatch {
    private static final String LOG = "plgob.log";

    /** How long the player id may be unresolvable before it stops being an ordinary handover. */
    private static final long GRACE_MS = 2000;

    /** How far the player must travel before the camera's stillness means anything. */
    private static final double MOVED_ENOUGH = 200.0;

    /** View-space drift per unit of world travel above which the camera is not keeping up.
     *  A camera that tracks holds the player near one spot in its own view however far they
     *  walk, so this ratio sits near zero; a pinned camera lets them drift roughly in step
     *  with their own movement, which measures about 0.87 in the harness. */
    private static final double SLIDE_RATIO = 0.3;

    /** A one-frame view jump this large is a deliberate camera snap, not a tracking failure.
     *  Matches the threshold the cameras themselves snap at. */
    private static final double SNAP_UNITS = 250.0;

    /** How long the client may go without reaching the camera before that is worth a line. */
    private static final long TICK_GAP_MS = 2000;

    private long lastid = Long.MIN_VALUE;
    private String lastres = null;
    private long lostsince = 0;
    private boolean reported = false;

    private long entered = 0;
    private long reached = 0;
    private long drawnat = 0;
    private long lastentered = -1;
    private long lastreached = -1;
    private boolean gapreported = false;

    private Coord3f prevworld = null;
    private Coord3f prevview = null;
    private double worldmoved = 0;
    private double viewmoved = 0;
    private Matrix4f prevcam = null;
    private boolean cammoved = false;
    private long windowstart = 0;
    private boolean stuckreported = false;

    /**
     * Stamped as the very first thing {@link MapView#tick} does, before anything that could throw.
     *
     * The first version of this class only logged from partway down tick(), which made its silence
     * useless: "no lines" could mean the player id was healthy, or it could mean tick never got
     * that far. Counting entry separately from arrival tells those apart.
     */
    public void enter() {
        entered++;
    }

    /**
     * Called once per frame from {@link MapView#tick}, just before the camera is ticked.
     *
     * @param mv the map view being watched
     */
    public void tick(MapView mv) {
        reached++;
        /* This runs on the UI thread inside MapView.tick. A diagnostic that can break a frame is
         * worse than the bug it was added to find, so nothing in here is allowed out. */
        try {
            check(mv);
        } catch (RuntimeException e) {
            // Nothing useful to do about it, and saying so every frame would be its own problem.
        }
    }

    /**
     * Called from {@link MapView#draw}, which keeps running even when ticking does not - the world
     * still rendering while the camera sits still is exactly the reported symptom, so drawing is
     * the one place from which a stalled tick can be noticed at all.
     */
    public void drawn(MapView mv) {
        drawnat++;
        try {
            gapcheck();
        } catch (RuntimeException e) {
            // As above.
        }
    }

    /** Notices ticking that stops, or that stops short of the camera. */
    private void gapcheck() {
        long now = System.currentTimeMillis();
        if (reached != lastreached) {
            /* The camera path is alive, which is the only thing that clears the alarm. */
            if (gapreported) {
                NLog.log(LOG, String.format("ticking resumed (entered=%d reached=%d drawn=%d)",
                    entered, reached, drawnat));
                gapreported = false;
            }
            lastentered = entered;
            lastreached = reached;
            windowstart = now;
            return;
        }
        if (gapreported || (windowstart == 0) || (now - windowstart < TICK_GAP_MS))
            return;
        gapreported = true;
        /* Whether tick() is still being entered is the whole distinction. Entered climbing while
         * reached stands still means something between the top of tick() and the camera throws
         * every frame - which freezes the camera while the world carries on drawing. */
        boolean entering = (entered != lastentered);
        NLog.log(LOG, String.format(
            "MapView.tick has not reached the camera for %dms (entered=%d reached=%d drawn=%d) - %s",
            now - windowstart, entered, reached, drawnat,
            entering ? "tick() is entered but aborts before the camera"
                     : "tick() is not being called at all"));
        lastentered = entered;
    }

    private void check(MapView mv) {
        Gob pl = mv.player();
        if (pl != null) {
            resolved(mv, pl);
            follow(mv, pl);
            return;
        }
        unresolved(mv);
    }

    /**
     * Watches the symptom itself rather than any one cause of it: the player covering real ground
     * while their position on screen slides away means the camera is not tracking them, whatever
     * the reason. Reported once per episode with everything needed to tell the causes apart.
     */
    private void follow(MapView mv, Gob pl) {
        Coord3f world;
        Matrix4f cam;
        try {
            world = pl.getc();
            cam = camxf(mv);
        } catch (Loading l) {
            return;
        } catch (RuntimeException e) {
            return;
        }
        if ((world == null) || (cam == null)) {
            prevworld = null;
            prevview = null;
            return;
        }

        /* Measured in view space rather than screen space on purpose. Screen coordinates go
         * through the perspective divide, so a point near or behind the near plane produces
         * enormous garbage that swamps an accumulated total - the first version of this check
         * read a ratio of 640 on a camera that was tracking perfectly well. View space is the
         * same measurement without the division, and it is well behaved everywhere. */
        Coord3f view = cam.mul4(new Coord3f(world.x, -world.y, world.z));

        /* The camera's own transform standing still while the player walks is exact: it means the
         * camera is not being ticked at all. Kept alongside the drift ratio because a camera that
         * ticks but tracks the wrong thing shows one and not the other. */
        if ((prevcam != null) && !cam.equals(prevcam))
            cammoved = true;
        prevcam = cam;

        if ((prevworld == null) || (prevview == null)) {
            prevworld = world;
            prevview = view;
            return;
        }
        double dworld = Math.hypot(world.x - prevworld.x, world.y - prevworld.y);
        double dview = Math.hypot(view.x - prevview.x, view.y - prevview.y);
        prevworld = world;
        prevview = view;

        /* Cameras jump on purpose: every one of them snaps outright when the player ends up more
         * than 250 units away, which is what teleporting, hearthing and changing map instance all
         * look like. A single frame of that is not the camera failing to follow, but accumulated
         * blind it would look exactly like it, so treat it as the discontinuity it is and start
         * measuring again on the far side. */
        if (dview > SNAP_UNITS) {
            reset();
            return;
        }
        worldmoved += dworld;
        viewmoved += dview;

        if (worldmoved < MOVED_ENOUGH)
            return;
        double ratio = viewmoved / worldmoved;
        boolean drifting = (ratio > SLIDE_RATIO);
        if (!drifting && cammoved) {
            /* Tracking normally. Start a fresh window rather than letting good frames bank
             * credit against a stall that begins later. */
            reset();
            stuckreported = false;
            return;
        }
        if (!stuckreported) {
            stuckreported = true;
            NLog.log(LOG, String.format(
                "camera is not following: player moved %.0f units and drifted %.0f across the view"
                    + " (ratio %.2f), camera transform %s - id %d (%s), camera %s,"
                    + " entered=%d reached=%d drawn=%d, position reads %s",
                worldmoved, viewmoved, ratio, cammoved ? "moving" : "FROZEN",
                mv.plgob, resname(pl),
                (mv.camera == null) ? "null" : mv.camera.getClass().getSimpleName(),
                entered, reached, drawnat, where(mv)));
        }
        reset();
    }

    private void reset() {
        worldmoved = 0;
        viewmoved = 0;
        cammoved = false;
    }

    private static Matrix4f camxf(MapView mv) {
        try {
            MapView.Camera cam = mv.camera;
            return((cam == null) ? null : cam.viewxf());
        } catch (RuntimeException e) {
            return(null);
        }
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
