package haven.automated;

import haven.Coord2d;
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
 *   <li><b>The camera ticks but does not keep up.</b> Caught by the travel measurement below,
 *       which is indifferent to the cause.</li>
 * </ul>
 *
 * The travel measurement is the load-bearing part, and it compares how far the camera moved against
 * how far the player moved. Camera travel is read by watching a fixed world point slide through the
 * view: the anchor does not move, so everything that happens to it is the camera's own motion. A
 * camera that is following goes wherever the player goes, so the ratio sits near 1; a pinned one
 * sits near 0.
 *
 * It is worth knowing why it is not the more obvious measurement. The first version compared the
 * player's drift across the view against the ground they covered - which cannot tell a stuck camera
 * from a player pacing back and forth, because walking A-B-A banks path length in both numbers while
 * the camera smoothly lags. That reads as a mid-range ratio on a perfectly healthy client, and
 * pacing indoors is exactly the situation this was being reported from. Comparing camera travel to
 * player travel is immune: an oscillating player produces an oscillating camera and the two cancel.
 *
 * Everything here is measured in view space rather than screen space - screen coordinates carry a
 * perspective divide, and a point near the near plane produces garbage that swamps an accumulated
 * total.
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

    /** Camera travel per unit of player travel below which the camera is not keeping up.
     *  A camera that tracks goes wherever the player goes, so this sits near 1; a pinned
     *  camera sits near 0. */
    private static final double KEEPUP_RATIO = 0.3;

    /** A one-frame view jump this large is a deliberate camera snap, not a tracking failure.
     *  Matches the threshold the cameras themselves snap at. */
    private static final double SNAP_UNITS = 250.0;

    /** How often the unconditional state line is printed. */
    private static final long BEAT_MS = 15000;

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

    private Coord2d prevrc = null;
    private Coord3f prevworld = null;
    private double rcmoved = 0;
    private double worldmoved = 0;
    private Matrix4f prevcam = null;
    private boolean cammoved = false;
    private Coord3f anchor = null;
    private Coord3f prevanchor = null;
    private double cammovedby = 0;
    private long windowstart = 0;
    private boolean stuckreported = false;
    private long lastbeat = 0;

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
        heartbeat(mv, pl);
        if (pl != null) {
            resolved(mv, pl);
            follow(mv, pl);
            return;
        }
        unresolved(mv);
    }

    /**
     * One unconditional line of raw state every {@link #BEAT_MS}, whatever else is or is not
     * happening.
     *
     * Every other check here is behind a threshold or a guard, and twice now a report of a broken
     * camera has come back with an empty log - once because the check only started measuring after
     * the player had covered ground the client no longer believed they were covering, and once
     * because a position that throws while loading looks the same as a position that is not moving.
     * A quiet log was then consistent with the client being fine and with it being thoroughly
     * broken, which is the one thing a diagnostic may not be.
     *
     * So this samples and prints regardless. It is the only line here that cannot be reasoned away,
     * and at four lines a minute it costs nothing to leave on.
     */
    private void heartbeat(MapView mv, Gob pl) {
        long now = System.currentTimeMillis();
        if ((lastbeat != 0) && (now - lastbeat < BEAT_MS))
            return;
        lastbeat = now;

        String rc = "-";
        String got = "-";
        if (pl != null) {
            Coord2d prc = pl.rc;
            rc = (prc == null) ? "null" : String.format("(%.1f, %.1f)", prc.x, prc.y);
            /* What getc() does is the single most useful fact in here: it is what the camera and
             * click-to-move read, and it can return a position, sit frozen on one, or spend the
             * whole episode throwing while it waits for map data. Those look identical on screen
             * and want telling apart. */
            try {
                Coord3f c = pl.getc();
                got = (c == null) ? "null" : String.format("(%.1f, %.1f)", c.x, c.y);
            } catch (Loading l) {
                got = "<loading: " + l.getMessage() + ">";
            } catch (RuntimeException e) {
                got = "<" + e + ">";
            }
        }
        Matrix4f cam = camxf(mv);
        NLog.log(LOG, String.format(
            "beat id=%d player=%s rc=%s getc=%s camera=%s cam=%s getcc=%s"
                + " entered=%d reached=%d drawn=%d",
            mv.plgob, (pl == null) ? "NULL" : "ok", rc, got,
            (mv.camera == null) ? "null" : mv.camera.getClass().getSimpleName(),
            (cam == null) ? "null" : Integer.toHexString(java.util.Arrays.hashCode(cam.m)),
            where(mv), entered, reached, drawnat));
    }

    /**
     * Watches the symptom itself rather than any one cause of it: the player covering real ground
     * while their position on screen slides away means the camera is not tracking them, whatever
     * the reason. Reported once per episode with everything needed to tell the causes apart.
     */
    private void follow(MapView mv, Gob pl) {
        /* Two positions, deliberately. rc is what the server says - it is written straight from
         * the movement deltas and keeps advancing whatever else is wrong. getc() is what the
         * client computes from it, and it is what the camera, click-to-move and every range check
         * actually read.
         *
         * Gating on rc rather than getc() is the whole point of measuring both. If getc() ever
         * froze while the character kept running, an earlier version of this check would have gone
         * completely silent - it only accumulated getc() travel, so a frozen position looked
         * identical to standing still, and standing still is not worth reporting. That is exactly
         * the shape of "the camera is locked and the client thinks I am the mine hole": the
         * character runs around on screen because it is drawn from its own placement, while
         * everything that asks where the player is gets the stale answer. */
        Coord2d rc = pl.rc;
        Coord3f world = null;
        Matrix4f cam;
        try {
            world = pl.getc();
        } catch (Loading l) {
            // A position that is still loading is not a frozen one; just do not count this frame.
        } catch (RuntimeException e) {
            // As above.
        }
        try {
            cam = camxf(mv);
        } catch (RuntimeException e) {
            return;
        }
        if ((rc == null) || (cam == null)) {
            prevrc = null;
            prevworld = null;
            prevanchor = null;
            return;
        }

        /* All of this is in view space rather than screen space on purpose. Screen coordinates go
         * through the perspective divide, so a point near or behind the near plane produces
         * enormous garbage that swamps an accumulated total - an early version of this check read
         * a ratio of 640 on a camera that was tracking perfectly well. */

        /* How far the CAMERA travelled, measured by watching a fixed world point slide through the
         * view. The anchor does not move, so all of this motion is the camera's own.
         *
         * This is the measurement that matters, and it replaced a comparison of the player's drift
         * across the view against the ground they covered. That earlier one could not tell a stuck
         * camera from a player pacing back and forth: walking A-B-A banks path length in both
         * numbers while the camera smoothly lags, which reads as a mid-range ratio on a perfectly
         * healthy client - and pacing indoors is exactly when this was being reported. Comparing
         * camera travel against player travel is indifferent to that, because an oscillating player
         * makes an oscillating camera and the two cancel. Following reads near 1, pinned reads
         * near 0. */
        /* The anchor is taken from rc so it exists even on a frame where getc() would not give
         * one. It is a fixed world point either way, which is all this needs. */
        if (anchor == null)
            anchor = new Coord3f((float)rc.x, (float)rc.y, 0);
        Coord3f anchorview = cam.mul4(new Coord3f(anchor.x, -anchor.y, anchor.z));

        /* A transform that never changes at all is exact: the camera is not being ticked. */
        if ((prevcam != null) && !cam.equals(prevcam))
            cammoved = true;
        prevcam = cam;

        if ((prevrc == null) || (prevanchor == null)) {
            prevrc = rc;
            prevworld = world;
            prevanchor = anchorview;
            return;
        }
        double drc = Math.hypot(rc.x - prevrc.x, rc.y - prevrc.y);
        double dcam = Math.hypot(anchorview.x - prevanchor.x, anchorview.y - prevanchor.y);
        double dworld = ((world != null) && (prevworld != null))
            ? Math.hypot(world.x - prevworld.x, world.y - prevworld.y) : 0;
        prevrc = rc;
        prevanchor = anchorview;
        if (world != null)
            prevworld = world;

        /* Cameras jump on purpose: every one of them snaps outright when the player ends up more
         * than 250 units away, which is what teleporting, hearthing and changing map instance all
         * look like. A single frame of that is not the camera failing to follow, but accumulated
         * blind it would look exactly like it, so treat it as the discontinuity it is and start
         * measuring again on the far side. */
        if ((drc > SNAP_UNITS) || (dcam > SNAP_UNITS) || (dworld > SNAP_UNITS)) {
            reset();
            return;
        }
        rcmoved += drc;
        worldmoved += dworld;
        cammovedby += dcam;

        if (rcmoved < MOVED_ENOUGH)
            return;
        double keepup = cammovedby / rcmoved;
        double tracks = worldmoved / rcmoved;
        boolean posfrozen = (tracks < KEEPUP_RATIO);
        if ((keepup >= KEEPUP_RATIO) && !posfrozen && cammoved) {
            /* Tracking normally. Start a fresh window rather than letting good frames bank
             * credit against a stall that begins later. */
            reset();
            stuckreported = false;
            return;
        }
        if (!stuckreported) {
            stuckreported = true;
            NLog.log(LOG, String.format(
                "camera is not following: server moved the player %.0f units, the client's own"
                    + " position moved %.0f (%s), the camera moved %.0f (keepup %.2f),"
                    + " transform %s - id %d (%s), camera %s, entered=%d reached=%d drawn=%d,"
                    + " position reads %s",
                rcmoved, worldmoved, posfrozen ? "POSITION FROZEN" : "tracking",
                cammovedby, keepup, cammoved ? "moving" : "FROZEN",
                mv.plgob, resname(pl),
                (mv.camera == null) ? "null" : mv.camera.getClass().getSimpleName(),
                entered, reached, drawnat, where(mv)));
        }
        reset();
    }

    private void reset() {
        rcmoved = 0;
        worldmoved = 0;
        cammovedby = 0;
        cammoved = false;
        anchor = null;
        prevanchor = null;
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
