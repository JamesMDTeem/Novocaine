package haven.automated.nbots;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.automated.lp.LpPlanner;
import haven.automated.lp.LpTargets;
import haven.automated.lp.NLog;
import haven.automated.pathfinder.Map;
import haven.automated.pathfinder.Pathfinder;

import static haven.OCache.posres;

/**
 * Walking, for bots. One place for the things every bot in this package needs to do with the
 * pathfinder, so the three of them can't drift into three subtly different notions of "get there".
 *
 * Three kinds of movement, deliberately distinct:
 *
 * - {@link #approach}: walk up to a gob that may be MOVING, re-issuing the path whenever the target
 *   drifts. Intercepts rather than follows. (The same argument as AutoLpBot's walkTo, which this
 *   generalises: waiting out a whole path towards where a critter used to be never converges.)
 * - {@link #stepTo}: walk to a fixed point that is within the pathfinder's own window.
 * - {@link #travelTo}: walk to a fixed point that ISN'T - which is the whole reason this class
 *   exists rather than everyone calling pfLeftClick. See below.
 *
 * Why travel needs its own method: the pathfinder builds a grid centred on the player that is 88
 * tiles across ({@code Map.origin} * 2), and MapView clamps any click beyond 40 tiles back to the
 * edge of that. Hand it a destination a few hundred tiles away - a water barrel back at base,
 * which is exactly the case the auto-refill is for - and it silently walks 40 tiles in roughly the
 * right direction and stops. Travel therefore walks the distance as a series of hops, re-aiming
 * after each one, which also means terrain that has streamed in since the last hop is taken into
 * account instead of being planned around blind.
 *
 * Every method here throws InterruptedException when the owning bot has been stopped, so a bot
 * never keeps walking after its Stop button was pressed.
 */
public class BotNav {
    /** Asked before every wait; false means the bot was stopped and movement must unwind. */
    public interface Abort {
        boolean running();
    }

    public interface Cond {
        boolean check() throws InterruptedException;
    }

    private static final int POLL_MS = 25;

    /** Close enough to right-click a gob and have its menu open. About two tiles. */
    public static final double REACH = 22.0;
    /** How far a target may drift from where we aimed before re-pathing is worth it. */
    private static final double DRIFT = 11.0;
    /** Give up chasing after this many re-paths that don't close the distance. */
    private static final int NO_PROGRESS_LIMIT = 10;
    /** How many times one approach may stop to back out of a beast's ring before giving up. */
    private static final int RETREAT_LIMIT = 3;
    private static final double RETREAT_MARGIN = 30.0;

    /**
     * How far one travel hop aims. Comfortably inside MapView's 40-tile clamp so the hop lands
     * where we asked rather than at a clamped point, and inside the region the client will have
     * streamed in, so the terrain the search plans against is real.
     */
    private static final double HOP = 11 * 25.0;
    /** Travel gives up after this many hops that don't get closer. */
    private static final int TRAVEL_STALL_LIMIT = 6;

    private final GameUI gui;
    private final Abort abort;
    private final String log;

    /**
     * Set when an approach was abandoned because of wildlife rather than because the target can't
     * be reached. Callers use the distinction to decide whether to give up on a target for good or
     * only for a while - a bear moves, a cliff doesn't.
     */
    public boolean hazardBlocked = false;

    public BotNav(GameUI gui, Abort abort, String log) {
        this.gui = gui;
        this.abort = abort;
        this.log = log;
    }

    // ------------------------------------------------------------------ waiting

    /**
     * Sleep-polling wait. Checks first (so an already-true condition costs nothing), then every
     * POLL_MS up to maxTicks. Throws as soon as the bot is stopped, which is what makes Stop feel
     * immediate rather than "after the current wait".
     */
    public void waitUntil(Cond cond, int maxTicks) throws InterruptedException {
        for (int i = 0; i < maxTicks; i++) {
            if (!abort.running() || Thread.interrupted())
                throw new InterruptedException();
            try {
                if (cond.check())
                    return;
            } catch (Loading l) {
                // Not resolvable this tick; keep waiting.
            }
            Thread.sleep(POLL_MS);
        }
    }

    /** Plain delay that still honours Stop. */
    public void pause(int ticks) throws InterruptedException {
        waitUntil(() -> false, ticks);
    }

    // ------------------------------------------------------------------ state

    public Gob player() {
        return (gui.map == null) ? null : gui.map.player();
    }

    public Gob gob(long id) {
        return gui.ui.sess.glob.oc.getgob(id);
    }

    public boolean walking() {
        Gob me = player();
        return me != null
            && ((gui.map.pfthread != null && gui.map.pfthread.isAlive()) || me.getv() > 0);
    }

    /** Move-to-self: the standard way to interrupt a repeating in-place action. */
    public void stopAction() {
        Gob p = player();
        if (p != null)
            gui.map.wdgmsg("click", Coord.z, p.rc.floor(posres), 1, 0);
    }

    /**
     * Stops walking for good, pathfinder included.
     *
     * stopAction() alone ends the current MOVE, but the Pathfinder thread is still alive and simply
     * issues the next leg - so a bot that only called that would keep walking towards whatever it
     * was trying to walk away from. pfLeftClick/pfRightClick do this teardown themselves before
     * starting a new search, which is why re-pathing needs no equivalent; only abandoning does.
     */
    public void cancelWalk() {
        synchronized (Pathfinder.class) {
            if (gui.map.pf != null) {
                gui.map.pf.terminate = true;
                if (gui.map.pfthread != null)
                    gui.map.pfthread.interrupt();
            }
        }
        stopAction();
    }

    // ------------------------------------------------------------------ keep-outs

    /**
     * Publishes the circles the next searches must route around: dangerous wildlife (reusing the LP
     * assistant's rings, so both features agree on what counts as dangerous and how much room it
     * needs) and, when enabled, the other characters' personal space.
     *
     * Refreshed per re-path rather than once per walk, because both kinds of obstacle move: a stale
     * ring routes us around where a bear used to be and straight through where it is now.
     */
    private void publishKeepouts(Coord2d from) {
        Map.Keepout[] beasts = LpPlanner.keepouts(gui, from);
        Map.Keepout[] people = NBotConfig.on(NBotConfig.Key.avoidOthers)
            ? Crowd.keepouts(gui, from) : new Map.Keepout[0];
        Map.keepout(Crowd.merge(beasts, people));
    }

    /** Drops every keep-out. Must be called on every exit path - see {@link #approach}. */
    public void clearKeepouts() {
        Map.keepout(null);
    }

    // ------------------------------------------------------------------ approach

    /**
     * Walks to within {@code reach} of a gob, re-pathing as either of us moves, routing around
     * wildlife and other characters.
     *
     * @return true if we ended up close enough to act on it.
     */
    public boolean approach(Gob gob, double reach) throws InterruptedException {
        hazardBlocked = false;
        try {
            return approach0(gob, reach);
        } finally {
            // Keep-outs are a process-wide setting and the player's own clicks go through the same
            // pathfinder, so a leftover ring would silently reroute them long after the bot stopped
            // caring. Never leave one standing.
            clearKeepouts();
        }
    }

    private boolean approach0(Gob gob, double reach) throws InterruptedException {
        if (gob == null)
            return false;
        long id = gob.id;
        Coord2d aimed = null;
        double best = Double.MAX_VALUE;
        int stalled = 0;
        int retreats = 0;

        for (int i = 0; i < 60; i++) {
            Gob target = gob(id);
            Gob me = player();
            if (target == null || me == null)
                return false;

            double dist = me.rc.dist(target.rc);
            if (dist <= reach)
                return true;

            // A beast that has wandered onto the target since it was chosen. Standing there to work
            // is what the keep-out margin exists to prevent, so stop - but the caller should defer
            // rather than discard it, since beasts move on.
            Gob atTarget = LpPlanner.hazardWithin(gui, target.rc, LpTargets.DANGER_KEEPOUT);
            if (atTarget != null) {
                hazardBlocked = true;
                cancelWalk();
                return false;
            }

            // One that has closed on US. It can't be handed to the pathfinder as a no-go circle
            // while we're standing inside it - the search would have no legal first move - so step
            // out of it first and re-path on the next pass.
            Gob onUs = LpPlanner.hazardWithin(gui, me.rc, LpTargets.DANGER_PATH_CLEARANCE);
            if (onUs != null) {
                if (++retreats > RETREAT_LIMIT) {
                    hazardBlocked = true;
                    cancelWalk();
                    return false;
                }
                retreatFrom(onUs);
                aimed = null;  // we've moved; whatever we aimed at is stale
                continue;
            }

            if (dist < best - 1.0) {
                best = dist;
                stalled = 0;
            }

            if (aimed == null || aimed.dist(target.rc) > DRIFT) {
                if (aimed != null && ++stalled > NO_PROGRESS_LIMIT) {
                    NLog.log(log, "giving up approach to #" + id + ": " + stalled
                        + " re-paths without closing (still " + (int) dist + "u)");
                    cancelWalk();
                    return false;
                }
                publishKeepouts(me.rc);
                // clickb=1 walks without acting on arrival; what to do there is the caller's call.
                gui.map.pfRightClick(target, -1, 1, 0, null);
                aimed = target.rc;
            }

            // Let a freshly-issued path get going before judging whether we're still walking -
            // otherwise the gap between the click and the pathfinder thread starting reads as
            // "stopped, so we must have arrived" and the loop spins at poll speed.
            pause(10);

            // Then wait a slice rather than the whole path, so a target that moves is noticed while
            // we're still walking. Ends early on arrival or when the walk is over.
            waitUntil(() -> {
                Gob g = gob(id);
                Gob p = player();
                if (g == null || p == null)
                    return true;
                if (p.rc.dist(g.rc) <= reach)
                    return true;
                return !walking();
            }, 12);

            // The walk finished and the target hasn't moved: this is as close as pathing will get
            // us, so treat it as arrival even if we're further out than `reach`. Distance alone
            // can't decide it - pfRightClick paths to the edge of the gob's HITBOX, and a big
            // tree's trunk is wider than two tiles.
            Gob now = gob(id);
            if (!walking() && now != null && aimed != null && aimed.dist(now.rc) <= DRIFT)
                return true;
        }
        Gob me = player(), target = gob(id);
        if (me != null && target != null && me.rc.dist(target.rc) <= reach)
            return true;
        cancelWalk();
        return false;
    }

    /**
     * Walks directly away from a beast until we're outside the ring the pathfinder has to treat as
     * a no-go area. Uses pfLeftClick rather than a raw move so the retreat still goes around trees
     * and water, but with no keep-out published - by definition we are inside the one that matters.
     */
    private void retreatFrom(Gob beast) throws InterruptedException {
        Gob me = player();
        if (me == null || beast == null)
            return;
        Coord2d away = me.rc.sub(beast.rc);
        double d = away.abs();
        // Dead-centre on the beast has no "away" direction; any heading beats standing still.
        away = (d < 1.0) ? new Coord2d(1, 0) : away.div(d);
        Coord2d dest = beast.rc.add(away.mul(LpTargets.DANGER_PATH_CLEARANCE + RETREAT_MARGIN));

        clearKeepouts();
        gui.map.pfLeftClick(dest.floor(), null);
        waitUntil(() -> {
            Gob p = player();
            Gob b = gob(beast.id);
            if (p == null || b == null)
                return true;
            return p.rc.dist(b.rc) > LpTargets.DANGER_PATH_CLEARANCE + RETREAT_MARGIN;
        }, 200);
        cancelWalk();
    }

    // ------------------------------------------------------------------ point travel

    /**
     * Walks to a fixed point that is already inside the pathfinder's window.
     *
     * @return true if we got within {@code tol} of it.
     */
    public boolean stepTo(Coord2d dest, double tol) throws InterruptedException {
        if (dest == null)
            return false;
        Gob me = player();
        if (me == null)
            return false;
        try {
            publishKeepouts(me.rc);
            gui.map.pfLeftClick(dest.floor(), null);
            pause(10);
            waitUntil(() -> {
                Gob p = player();
                return p == null || p.rc.dist(dest) <= tol || !walking();
            }, 400);
        } finally {
            clearKeepouts();
        }
        Gob now = player();
        return now != null && now.rc.dist(dest) <= tol;
    }

    /**
     * Walks to a point however far away it is, in hops.
     *
     * Each hop aims at most {@link #HOP} along the straight line to the destination and then
     * re-evaluates, so the route is re-planned against terrain that has streamed in since. When a
     * hop makes no headway - the usual cause is a lake or a cliff line straddling the straight
     * line, which a single search can only see the near edge of - the next one is aimed off to one
     * side instead, alternating sides. That is a crude wall-follow rather than real long-range
     * planning, but the client simply has no global navigation mesh to plan against, and it gets
     * around the local obstructions that actually occur between a work site and a water barrel.
     *
     * @return true if we ended within {@code tol} of the destination.
     */
    public boolean travelTo(Coord2d dest, double tol) throws InterruptedException {
        if (dest == null)
            return false;
        double best = Double.MAX_VALUE;
        int stalled = 0;
        int detour = 0;

        for (int hop = 0; hop < 120; hop++) {
            Gob me = player();
            if (me == null)
                return false;
            double dist = me.rc.dist(dest);
            if (dist <= tol)
                return true;

            if (dist < best - 5.0) {
                best = dist;
                stalled = 0;
                detour = 0;
            } else if (++stalled > TRAVEL_STALL_LIMIT) {
                NLog.log(log, "travel gave up " + (int) dist + "u short of " + dest
                    + " after " + stalled + " hops without progress");
                cancelWalk();
                return false;
            }

            Coord2d dir = dest.sub(me.rc);
            double len = dir.abs();
            if (len < 1.0)
                return true;
            dir = dir.div(len);

            // Alternate sides on successive stalls, and swing wider each time.
            if (stalled > 0) {
                double angle = ((detour % 2 == 0) ? 1 : -1) * (Math.PI / 4) * (1 + detour / 2);
                detour++;
                double cos = Math.cos(angle), sin = Math.sin(angle);
                dir = new Coord2d(dir.x * cos - dir.y * sin, dir.x * sin + dir.y * cos);
            }

            Coord2d aim = me.rc.add(dir.mul(Math.min(HOP, Math.max(len, HOP / 2))));
            stepTo(aim, 11 * 2.0);
        }
        cancelWalk();
        Gob now = player();
        return now != null && now.rc.dist(dest) <= tol;
    }

    /**
     * Travels to a segment-anchored position, re-resolving it every hop.
     *
     * Re-resolving matters: the anchor is fixed in SEGMENT space, but the live world coordinates it
     * maps to are only valid relative to where the player is now, and the client can re-base those
     * as you travel. Resolving once at the start and walking to that Coord2d would drift.
     *
     * @return true on arrival; false if the anchor can't be resolved (different segment, or the map
     *         file doesn't know where we are) or the walk failed.
     */
    public boolean travelTo(WorldAnchor anchor, double tol) throws InterruptedException {
        if (anchor == null)
            return false;
        for (int hop = 0; hop < 120; hop++) {
            Coord2d dest = anchor.resolve(gui);
            if (dest == null) {
                NLog.log(log, "cannot resolve " + anchor + " - different map segment?");
                return false;
            }
            Gob me = player();
            if (me == null)
                return false;
            double dist = me.rc.dist(dest);
            if (dist <= tol)
                return true;
            // Inside one hop of the target: hand the rest to travelTo, which owns the stall and
            // detour bookkeeping, and let it finish the job.
            if (dist <= HOP)
                return travelTo(dest, tol);
            if (!travelTo(dest, HOP * 0.75))
                return false;
        }
        return false;
    }
}
