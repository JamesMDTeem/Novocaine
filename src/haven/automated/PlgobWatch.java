package haven.automated;

import haven.Coord2d;
import haven.Coord3f;
import haven.Gob;
import haven.Loading;
import haven.Matrix4f;
import haven.MapView;
import haven.OptWnd;
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

    /* One serial per MapView ever built this run. If two views ever coexist - the old one
     * surviving an instance change while a new one is created - their beats interleave under
     * different serials, and the one being watched need not be the one on screen. That has been
     * a live theory throughout and nothing so far could confirm or kill it. */
    private static final java.util.concurrent.atomic.AtomicInteger SERIAL =
        new java.util.concurrent.atomic.AtomicInteger();
    private final int serial = SERIAL.incrementAndGet();

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

    /** Snaps detailed per beat interval. A handful shows the shape; a whole run of them would
     *  bury everything else. */
    private static final int SNAP_DETAIL = 3;

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

    /** Written once per launch by the first beat. See {@link #logenv}. */
    private static volatile boolean envlogged = false;

    /* Peaks carried between beats. Reset when a beat prints them, never by the window logic -
     * the whole point is that they outlive the once-a-second sample. */
    private double pvpeak = 0;
    private int camjumps = 0;

    /* Frame timing as the camera actually sees it. Every camera here eases toward its target by
     * a factor derived from dt, so a dt that does not match real elapsed time starves the
     * convergence and the camera falls behind until it snaps - which is what "lags, then
     * re-centres in jumps" looks like from the inside. Kept as min/mean/max because an average
     * alone hides the stalls. */
    private double dtmin = Double.MAX_VALUE;
    private double dtmax = 0;
    private double dtsum = 0;
    private int dtn = 0;

    /* How far the camera's own centre trails the point it is chasing. This is the quantity the
     * cameras themselves snap on at 250 units, so its peak says whether the snap is being reached
     * or the camera is merely trailing. */
    private double lag = 0;
    private double lagpeak = 0;

    /* Where the character is DRAWN, against where the client says it is.
     *
     * Everything above measures the camera against getc() - the logical position - and by that
     * measure the camera tracks tightly. But the screen shows the drawn character, and Gob.Placed
     * builds that from its own Placement, which autotick abandons whenever it throws Loading:
     *
     *     try { np = new Placement(); } catch(Loading l) { return; }
     *
     * so a Placement waiting on tile data leaves the character rendered at a stale spot while
     * getc() carries on. The camera then follows getc() perfectly - lag stays near zero, nothing
     * snaps, every number here looks healthy - while what is actually on screen is a character
     * sliding away from the centre and jumping back when the placement finally catches up. That
     * is indistinguishable, to someone watching, from the camera failing to track and snapping. */
    private double dgap = 0;
    private double dgappeak = 0;
    private double pvdrawnpeak = 0;

    /* Where the character lands ON SCREEN, in pixels, and how far that is from the middle.
     *
     * This is the measurement the complaint is actually phrased in - "the character should never
     * leave the middle of the screen" - and it is the one thing that was never logged. View space
     * was used everywhere instead, after an early version read nonsense from the perspective
     * divide; that was an over-correction. The divide only misbehaves for a point at or behind the
     * near plane, and the player sits hundreds of units in front of the camera, so for this one
     * point it is perfectly well conditioned.
     *
     * It is not a restatement of the view-space numbers either. screenxf goes through
     * basic.state() - the render pipe state that the frame is actually drawn with - rather than
     * the camera object read straight off the field, and it applies the projection. If those ever
     * disagree, only this sees it. */
    private double scroff = 0;
    private double scroffpeak = 0;

    /* The gap between the server's position and the one the client interpolates toward it.
     *
     * rc is written straight from the movement deltas; getc() is what LinMove eases toward it, and
     * what the camera then follows. A camera that visibly trails the character outdoors while
     * turning works normally is the other reported symptom, and it would show up here as getc()
     * falling behind rc - which is upstream of the camera entirely, and invisible to every
     * camera-side measurement in this file. */
    private double rcgap = 0;
    private double rcgappeak = 0;

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
    public void tick(MapView mv, double dt) {
        reached++;
        if (dt > 0) {
            dtmin = Math.min(dtmin, dt);
            dtmax = Math.max(dtmax, dt);
            dtsum += dt;
            dtn++;
        }
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
    /**
     * Where this client is actually installed and running from, written once per launch.
     *
     * Three logs in a row came back built from a revision that had been superseded days earlier,
     * including one launched after two intervening restarts, so the builds carrying the newer
     * diagnostics were never running. Nothing in the log said which copy of the client produced
     * it beyond the git revision in the banner, and that cannot distinguish "did not update" from
     * "updated, but a second install is the one being launched".
     *
     * It matters beyond the update problem. The reported fault appears only on alt accounts, and a
     * separate install would be a plain explanation: a different directory means a different
     * Workshop item id and potentially an older client carrying bugs already fixed here. gameDir
     * is the one that settles it, because under Steam it is walked up from the working directory
     * into the Workshop item and so names the item actually supplying resources.
     *
     * Preferences are NOT part of that story, which is worth recording so it is not re-guessed:
     * Config.localdir() resolves to %APPDATA%\Haven and Hearth on Windows, so Hurricane-prefs.xml
     * is shared by every install under one Windows user. Two installs cannot disagree about a
     * setting unless they run as different users. It is logged anyway, to show that rather than
     * assert it.
     */
    private static void logenv(MapView mv) {
        if (envlogged)
            return;
        envlogged = true;
        String jar;
        try {
            java.security.CodeSource cs = PlgobWatch.class.getProtectionDomain().getCodeSource();
            jar = (cs == null) ? "-" : String.valueOf(cs.getLocation());
        } catch (RuntimeException e) {
            jar = "<" + e + ">";
        }
        String local;
        try {
            java.nio.file.Path p = haven.Config.localdir();
            local = (p == null) ? "-" : p.toString();
        } catch (RuntimeException e) {
            local = "<" + e + ">";
        }
        NLog.log(LOG, String.format(
            "env cwd=%s | gameDir=%s | localdir=%s | prefs=%s | jar=%s | steam=%s | java=%s",
            System.getProperty("user.dir", "-"),
            (haven.Client.gameDir == null) ? "null" : ("".equals(haven.Client.gameDir)
                                                       ? "<empty - not under Steam>"
                                                       : haven.Client.gameDir),
            local, "-".equals(local) ? "<system node>" : (local + java.io.File.separator
                                                          + "Hurricane-prefs.xml"),
            jar, haven.Client.runningThroughSteam, System.getProperty("java.version", "-")));
    }

    private void heartbeat(MapView mv, Gob pl) {
        long now = System.currentTimeMillis();
        logenv(mv);
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
            "beat mv#%d chr=%s id=%d plid=%s player=%s rc=%s getc=%s camera=%s cam=%s eye=%s tgt=%s"
                + " lag=%.1f lagpeak=%.1f dpos=%s dgap=%.1f dgappeak=%.1f"
                + " rcgap=%.1f rcgappeak=%.1f"
                + " scroff=%.0fpx scroffpeak=%.0fpx vp=%s pipecam=%s pipeproj=%s"
                + " depth=%s camdist=%s projsc=%s"
                + " pv=%s pvpeak=%.1f pvdrawnpeak=%.1f camjumps=%d"
                + " isMe=%s culled=%s cullopt=%s dt=%.4f/%.4f/%.4f n=%d [%s] in{%s} getcc=%s entered=%d reached=%d drawn=%d"
                + " bodies=%s",
            serial, chr(mv), mv.plgob, plid(mv), (pl == null) ? "NULL" : "ok", rc, got,
            (mv.camera == null) ? "null" : mv.camera.getClass().getSimpleName(),
            (cam == null) ? "null" : Integer.toHexString(java.util.Arrays.hashCode(cam.m)),
            eye, fmt(camtarget(mv)), lag, lagpeak, fmt(drawnpos(pl)), dgap, dgappeak,
            rcgap, rcgappeak,
            scroff, scroffpeak, (mv.sz == null) ? "-" : (mv.sz.x + "x" + mv.sz.y),
            pipecamsame(mv, cam), pipeproj(mv),
            depth(cam, pl), camdist(mv, pl), projscale(mv),
            pv, pvpeak, pvdrawnpeak, camjumps,
            (pl == null) ? "-" : String.valueOf(pl.isMe),
            (pl == null) ? "-" : String.valueOf(pl.culled), cullopt(),
            (dtmin == Double.MAX_VALUE) ? 0 : dtmin, (dtn == 0) ? 0 : dtsum / dtn, dtmax, dtn,
            camparams(mv), input(mv), where(mv), entered, reached, drawnat, bodies(mv)));
        pvpeak = 0;
        pvdrawnpeak = 0;
        dgappeak = 0;
        scroffpeak = 0;
        rcgappeak = 0;
        camjumps = 0;
        lagpeak = 0;
        dtmin = Double.MAX_VALUE;
        dtmax = 0;
        dtsum = 0;
        dtn = 0;
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
        /* How far the client's own position trails the server's. */
        if (world != null) {
            rcgap = Math.hypot(rc.x - world.x, rc.y - world.y);
            if (rcgap > rcgappeak)
                rcgappeak = rcgap;
        }

        /* Pixels from the middle of the viewport, which is the form the report takes. */
        Coord3f scr = screenpos(mv, (drawnpos(pl) != null) ? drawnpos(pl) : world);
        if ((scr != null) && (mv.sz != null)) {
            double ox = scr.x - (mv.sz.x / 2.0), oy = scr.y - (mv.sz.y / 2.0);
            double off = Math.hypot(ox, oy);
            /* A point at or behind the near plane projects to nonsense. The player never is, so a
             * reading many screens away is the startup frame before the camera has been ticked, or
             * a view with no render state behind it - not a character that has wandered off. Bound
             * it against the viewport rather than a fixed number, so the limit means something. */
            double sane = 10.0 * Math.max(mv.sz.x, mv.sz.y);
            if ((off < sane) && !Double.isNaN(off)) {
                scroff = off;
                if (off > scroffpeak)
                    scroffpeak = off;
            }
        }

        /* The gap between drawn and logical position, and how far off centre the DRAWN character
         * actually is - which is what a person watching the screen is reporting. */
        Coord3f drawn = drawnpos(pl);
        if ((drawn != null) && (world != null)) {
            dgap = Math.hypot(world.x - drawn.x, world.y - drawn.y);
            if (dgap > dgappeak)
                dgappeak = dgap;
            Coord3f dv = cam.mul4(new Coord3f(drawn.x, -drawn.y, drawn.z));
            double off = Math.hypot(dv.x, dv.y);
            if (off > pvdrawnpeak)
                pvdrawnpeak = off;
        }

        /* How far the camera's centre trails what it is chasing - the exact quantity every camera
         * snaps on at 250 units. Sampled every frame, since the snap cycle is faster than a beat. */
        Coord3f tgt = camtarget(mv);
        if ((tgt != null) && (world != null)) {
            lag = Math.hypot(world.x - tgt.x, world.y - tgt.y);
            if (lag > lagpeak)
                lagpeak = lag;
        }

        /* Counted before the discontinuity check below returns, or the snaps this is meant to
         * count would be exactly the ones discarded. Logged as they happen too: a snap is a
         * discrete event and its surrounding numbers are what say why the camera got that far
         * behind, which a per-second average would smear away. */
        if (dcam > SNAP_UNITS) {
            camjumps++;
            if (camjumps <= SNAP_DETAIL) {
                NLog.log(LOG, String.format(
                    "camera SNAP: jumped %.0f in one frame, was trailing %.0f (peak %.0f)"
                        + " - dt %.4f/%.4f/%.4f min/mean/max over %d frames, camera %s %s",
                    dcam, lag, lagpeak, dtmin == Double.MAX_VALUE ? 0 : dtmin,
                    (dtn == 0) ? 0 : dtsum / dtn, dtmax, dtn,
                    (mv.camera == null) ? "null" : mv.camera.getClass().getSimpleName(),
                    camparams(mv)));
            }
        }
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

    /** The point the camera is currently centred on, or null for cameras that keep no such state. */
    private static Coord3f camtarget(MapView mv) {
        try {
            MapView.Camera cam = mv.camera;
            return((cam == null) ? null : cam.target());
        } catch (RuntimeException e) {
            return(null);
        }
    }

    /**
     * The state of the mouse path into the map view.
     *
     * Camera rotation is a middle-mouse drag, so an angle that never moves has two very different
     * explanations: the player did not rotate, or they did and the drag never arrived. A grab held
     * by another widget intercepts pointer events before they reach whatever is under the cursor,
     * and nothing else in this log would show that. The original report opened with someone
     * guessing their middle mouse button had broken, which is what this looks like from the outside.
     */
    private static String input(MapView mv) {
        try {
            return(mv.inputstate());
        } catch (RuntimeException e) {
            return("<" + e + ">");
        }
    }

    /**
     * How far in front of the camera the frame actually puts the player, in view space.
     *
     * On-screen offset scales as pv * sz.x / (2 * field * depth). The healthy ratio measures 6.4
     * pixels per view-unit at sz.x=2560, field=0.5 and depth=400, and it has been observed sitting
     * at 19.1 with dist, elev and pv all constant. Something in that denominator is a third of what
     * the camera believes. Depth is half of it: a value far short of dist means the transform being
     * drawn with puts the camera much nearer the player than the camera thinks it is.
     */
    private static String depth(Matrix4f cam, Gob pl) {
        try {
            if ((cam == null) || (pl == null))
                return("-");
            Coord3f w = pl.getc();
            if (w == null)
                return("-");
            Coord3f v = cam.mul4(new Coord3f(w.x, -w.y, w.z));
            return(String.format("%.0f", -v.z));
        } catch (Loading l) {
            return("-");
        } catch (RuntimeException e) {
            return("-");
        }
    }

    /** The camera's own distance to the player in world units, against what dist claims. */
    private static String camdist(MapView mv, Gob pl) {
        try {
            Matrix4f cam = camxf(mv);
            if ((cam == null) || (pl == null))
                return("-");
            Coord3f w = pl.getc();
            if (w == null)
                return("-");
            Coord3f e = cam.invert().mul4(Coord3f.o);
            double dx = e.x - w.x, dy = (-e.y) - w.y, dz = e.z - w.z;
            return(String.format("%.0f", Math.sqrt((dx * dx) + (dy * dy) + (dz * dz))));
        } catch (Loading l) {
            return("-");
        } catch (RuntimeException e) {
            return("-");
        }
    }

    /**
     * The projection's horizontal and vertical scale, straight off the matrix the frame is drawn
     * with - the other half of that denominator.
     *
     * These cameras build a symmetric frustum with a near plane of 1, so the two values are
     * 1/field and 1/(aspect*field). Anything other than the usual pair says the projection itself
     * changed, which no camera field would show.
     */
    private static String projscale(MapView mv) {
        try {
            haven.render.Projection p = mv.basic.state().get(haven.render.Homo3D.prj);
            if (p == null)
                return("-");
            Matrix4f m = p.fin(Matrix4f.id);
            return(String.format("%.3f/%.3f", m.m[0], m.m[5]));
        } catch (RuntimeException e) {
            return("-");
        }
    }

    /** Whether the pipe's view transform matches the camera object's, and its hash either way. */
    private static String pipecamsame(MapView mv, Matrix4f cam) {
        Matrix4f p = pipecamxf(mv);
        if (p == null)
            return("-");
        String h = Integer.toHexString(java.util.Arrays.hashCode(p.m));
        if ((cam != null) && !p.equals(cam))
            return(h + "-DIFFERS");
        return(h);
    }

    /** The camera's own eased state - distances and angles - as it reports it. */
    private static String camparams(MapView mv) {
        try {
            MapView.Camera cam = mv.camera;
            return((cam == null) ? "" : cam.params());
        } catch (RuntimeException e) {
            return("");
        }
    }

    /**
     * The view transform resolved out of the render pipe state, rather than off the camera field.
     *
     * These two should be the same transform. A long alt session says they are not: with distance,
     * elevation and the player's view-space position all constant and the character standing still,
     * the pixels-per-view-unit ratio held a rock-steady 6.3 for most of the run and then sat at 19.1
     * for fourteen seconds. Only the projection or the view behind screenxf can do that, and both
     * come from here. Logging the two side by side turns "they disagree" from an inference off a
     * ratio into something a single line states outright.
     */
    private static Matrix4f pipecamxf(MapView mv) {
        try {
            haven.render.Camera c = mv.basic.state().get(haven.render.Homo3D.cam);
            return((c == null) ? null : c.fin(Matrix4f.id));
        } catch (RuntimeException e) {
            return(null);
        }
    }

    /** The projection the frame is drawn with, which scales view-space offsets into pixels. */
    private static String pipeproj(MapView mv) {
        try {
            haven.render.Projection p = mv.basic.state().get(haven.render.Homo3D.prj);
            return((p == null) ? "-"
                   : Integer.toHexString(java.util.Arrays.hashCode(p.fin(Matrix4f.id).m)));
        } catch (RuntimeException e) {
            return("-");
        }
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
        NLog.log(LOG, String.format("player id %d -> %s  chr=%s", mv.plgob, res, chr(mv)));
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
    /**
     * The character name this session is playing, from GameUI.
     *
     * Without it a log cannot say which character produced it, and the whole report turns on that:
     * the main works and the alts do not. A session read as evidence that the camera tracks fine
     * may simply have been the character where it does. Every measurement in this file is
     * ambiguous until the name is beside it.
     */
    private static String chr(MapView mv) {
        try {
            if ((mv.ui == null) || (mv.ui.gui == null))
                return("-");
            String n = mv.ui.gui.chrid;
            return(((n == null) || n.isEmpty()) ? "-" : n);
        } catch (RuntimeException e) {
            return("-");
        }
    }

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

    /**
     * Whether the experimental camera-visibility culling is switched on.
     *
     * It drops any object more than 50px outside the viewport from the render tree, and until
     * this build neither copy of the predicate reliably exempted the player - so with it on, the
     * character itself could stop being drawn whenever it left frame, and reappear on the way
     * back. It is a per-install preference, which is one of the few things that can differ
     * between a main and an alt run from separate directories.
     */
    private static String cullopt() {
        try {
            return((OptWnd.onlyRenderCameraVisibleObjectsCheckBox == null) ? "?"
                   : String.valueOf(OptWnd.onlyRenderCameraVisibleObjectsCheckBox.a));
        } catch (RuntimeException e) {
            return("?");
        }
    }

    /**
     * The character's position on screen in pixels, through the same path the client draws with.
     *
     * screenxf resolves the camera from basic.state(), the render pipe state, rather than from the
     * camera field - so this reflects what was rendered even if the two ever disagree.
     */
    private static Coord3f screenpos(MapView mv, Coord3f world) {
        try {
            return((world == null) ? null : mv.screenxf(world));
        } catch (Loading l) {
            return(null);
        } catch (RuntimeException e) {
            return(null);
        }
    }

    /** Where the character is actually rendered - the Placement the render tree is using, which is
     *  not the same thing as getc() whenever the placement is stalled waiting on map data. */
    private static Coord3f drawnpos(Gob pl) {
        try {
            return((pl == null) ? null : pl.placed.getc());
        } catch (RuntimeException e) {
            return(null);
        }
    }

    private static String fmt(Coord3f c) {
        return((c == null) ? "-" : String.format("(%.1f, %.1f)", c.x, c.y));
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
