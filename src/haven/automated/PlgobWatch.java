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

    /** Drift across the view per unit of player travel above which the player is genuinely
     *  sliding out of frame. A pinned camera reads near 1. */
    private static final double DRIFT_RATIO = 0.5;

    /** A one-frame view jump this large is a deliberate camera snap, not a tracking failure.
     *  Matches the threshold the cameras themselves snap at. */
    private static final double SNAP_UNITS = 250.0;

    /** How often the unconditional state line is printed.
     *
     *  One second, because the failure this is chasing is usually there the moment the character
     *  logs in and a test run is over in ten or twenty. Anything slower turns a quick check into
     *  one or two samples, which is not enough to see whether a value is frozen or merely slow.
     *  It costs roughly 60 lines a minute; NLog keeps the last few launches and drops the rest,
     *  so a long session does not grow without bound. */
    private static final long BEAT_MS = 1000;

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
    private Coord3f prevview = null;
    private double rcmoved = 0;
    private double worldmoved = 0;
    private double viewmoved = 0;
    private Matrix4f prevcam = null;
    private boolean cammoved = false;
    private Coord3f anchor = null;
    private Coord3f prevanchor = null;
    private double cammovedby = 0;
    private long windowstart = 0;
    private boolean stuckreported = false;
    private long lastbeat = 0;

    /* Peaks carried between beats. Reset when a beat prints them, never by the window logic -
     * the whole point is that they outlive the once-a-second sample. */
    private double pvpeak = 0;
    private int camjumps = 0;

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
     * So this samples and prints regardless. It is the only line here that cannot be reasoned away.
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
        /* Where the camera actually is, and where the player sits in its view.
         *
         * The hash alone was not enough, and reading a clean-looking log without these cost a
         * round trip: it showed the transform changing on every frame the player moved, which
         * says the camera is being updated but says nothing at all about what it is pointed at.
         * A camera can update every frame and still not be following anyone.
         *
         * eye is the camera's own world position, from inverting the view transform. Held against
         * getcc() it says whether the camera is anywhere near where it is supposed to be looking.
         * pv is the player's position in view space, which is the symptom itself: a camera that
         * tracks holds this near one value however far the player walks, and one that does not
         * lets it slide away in step with them. Neither needs a threshold to read. */
        Matrix4f cam = camxf(mv);
        String eye = "-";
        String pv = "-";
        if (cam != null) {
            try {
                Coord3f e = cam.invert().mul4(Coord3f.o);
                eye = String.format("(%.1f, %.1f, %.1f)", e.x, -e.y, e.z);
            } catch (RuntimeException e) {
                eye = "<" + e + ">";
            }
            if ((pl != null) && got.startsWith("(")) {
                try {
                    Coord3f c = pl.getc();
                    Coord3f v = cam.mul4(new Coord3f(c.x, -c.y, c.z));
                    pv = String.format("(%.1f, %.1f)", v.x, v.y);
                } catch (RuntimeException e) {
                    pv = "<" + e + ">";
                }
            }
        }
        NLog.log(LOG, String.format(
            "beat id=%d plid=%s player=%s rc=%s getc=%s camera=%s cam=%s eye=%s pv=%s"
                + " pvpeak=%.1f camjumps=%d getcc=%s entered=%d reached=%d drawn=%d bodies=%s",
            mv.plgob, plid(mv), (pl == null) ? "NULL" : "ok", rc, got,
            (mv.camera == null) ? "null" : mv.camera.getClass().getSimpleName(),
            (cam == null) ? "null" : Integer.toHexString(java.util.Arrays.hashCode(cam.m)),
            eye, pv, pvpeak, camjumps, where(mv), entered, reached, drawnat, bodies(mv)));
        pvpeak = 0;
        camjumps = 0;
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
            prevview = null;
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
        Coord3f view = (world == null) ? null : cam.mul4(new Coord3f(world.x, -world.y, world.z));

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
        double dview = ((view != null) && (prevview != null))
            ? Math.hypot(view.x - prevview.x, view.y - prevview.y) : 0;
        prevrc = rc;
        prevanchor = anchorview;
        if (world != null)
            prevworld = world;
        if (view != null)
            prevview = view;

        /* Cameras jump on purpose: every one of them snaps outright when the player ends up more
         * than 250 units away, which is what teleporting, hearthing and changing map instance all
         * look like. A single frame of that is not the camera failing to follow, but accumulated
         * blind it would look exactly like it, so treat it as the discontinuity it is and start
         * measuring again on the far side. */
        /* Counted before the discontinuity check below returns, or the snaps this is meant to
         * count would be exactly the ones discarded. */
        if (dcam > SNAP_UNITS)
            camjumps++;
        if (view != null) {
            double off = Math.hypot(view.x, view.y);
            if (off > pvpeak)
                pvpeak = off;
        }

        if ((drc > SNAP_UNITS) || (dcam > SNAP_UNITS) || (dworld > SNAP_UNITS)
            || (dview > SNAP_UNITS)) {
            reset();
            return;
        }
        rcmoved += drc;
        worldmoved += dworld;
        cammovedby += dcam;
        viewmoved += dview;


        if (rcmoved < MOVED_ENOUGH)
            return;
        double keepup = cammovedby / rcmoved;
        double tracks = worldmoved / rcmoved;
        double drift = viewmoved / rcmoved;
        boolean posfrozen = (tracks < KEEPUP_RATIO);

        /* Both numbers have to agree before this says anything, because each one alone is fooled
         * by a player pacing back and forth - and in opposite directions.
         *
         * Camera travel is fooled low: the camera smooths an oscillation, so its path is genuinely
         * shorter than the player's even while tracking perfectly. A real run showed keepup 0.25
         * for a camera that was holding the player 15 units from where it started over 200 units of
         * walking, which is excellent tracking, and it was reported as a failure.
         *
         * Drift across the view is fooled high: pacing banks path length in the difference between
         * player and camera, so a lagging-but-fine camera reads a mid-range ratio.
         *
         * A camera that has actually stopped fails both at once - the player slides across the view
         * in step with their own movement while the camera goes nowhere - so requiring both is what
         * separates the real thing from someone walking in circles indoors. */
        boolean drifting = (drift > DRIFT_RATIO);
        boolean stuck = (keepup < KEEPUP_RATIO) && drifting;
        if (!stuck && !posfrozen && cammoved) {
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
                    + " position moved %.0f (%s), the camera moved %.0f (keepup %.2f), the player"
                    + " drifted %.0f across the view (drift %.2f), transform %s"
                    + " - id %d (%s), camera %s, entered=%d reached=%d drawn=%d, position reads %s",
                rcmoved, worldmoved, posfrozen ? "POSITION FROZEN" : "tracking",
                cammovedby, keepup, viewmoved, drift, cammoved ? "moving" : "FROZEN",
                mv.plgob, resname(pl),
                (mv.camera == null) ? "null" : mv.camera.getClass().getSimpleName(),
                entered, reached, drawnat, where(mv)));
        }
        reset();
    }

    private void reset() {
        rcmoved = 0;
        worldmoved = 0;
        viewmoved = 0;
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

    /**
     * The player object id the GameUI was created with, which the server sends separately from the
     * one the MapView is told to follow.
     *
     * Two independent answers to "which object am I", and they should never disagree. If they do,
     * the camera is anchored to something that is not the character - which is the one shape of
     * this failure that every check so far would call healthy, because it is perfectly consistent:
     * the wrong object has a position, the camera follows it faithfully, and nothing is frozen or
     * null anywhere.
     */
    private static String plid(MapView mv) {
        try {
            if ((mv.ui == null) || (mv.ui.gui == null))
                return("-");
            long plid = mv.ui.gui.plid;
            return((plid == mv.plgob) ? String.valueOf(plid) : (plid + " MISMATCH"));
        } catch (RuntimeException e) {
            return("-");
        }
    }

    /**
     * Every player body in the object cache, with the one the camera is following marked.
     *
     * A log showing the followed object standing perfectly still proves the camera is tracking it
     * correctly; it does not prove it is the right object. If another body is walking about while
     * the followed one stands still, the client is watching the wrong character - and a resource
     * name cannot tell them apart, because every player shares gfx/borka/body.
     */
    private static String bodies(MapView mv) {
        try {
            StringBuilder sb = new StringBuilder("[");
            int n = 0;
            synchronized (mv.glob.oc) {
                for (Gob g : mv.glob.oc) {
                    if (!"gfx/borka/body".equals(resname(g)))
                        continue;
                    if (n++ > 0)
                        sb.append(' ');
                    if (n > 6) {
                        sb.append("...");
                        break;
                    }
                    Coord2d c = g.rc;
                    sb.append(String.format("%s%d@(%.0f,%.0f)", (g.id == mv.plgob) ? "*" : "",
                        g.id, (c == null) ? 0.0 : c.x, (c == null) ? 0.0 : c.y));
                }
            }
            return(sb.append(']').toString());
        } catch (RuntimeException e) {
            return("<" + e + ">");
        }
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
