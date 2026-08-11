package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.automated.helpers.HitBoxes;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.pathfinder.Map;
import haven.automated.pathfinder.Pathfinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * One tile, in world units.
     *
     * Every distance in this class is really a count of tiles, because tiles are the unit the
     * things being reasoned about are measured in: a palisade is one tile thick, a gateway three
     * tiles wide, and {@link Observed} records the world a tile at a time. They were spelt
     * {@code 11 * n}, which reads as arithmetic rather than as the tile count it is.
     *
     * Taken from {@link MCache#tilesz} rather than written out again, since the rest of this file
     * already divides by it to report distances in tiles - two spellings of one quantity is the
     * drift this is here to stop.
     */
    private static final double TILE = MCache.tilesz.x;

    /** Close enough to right-click a gob and have its menu open. About two tiles. */
    public static final double REACH = TILE * 2.0;
    /** How far a target may drift from where we aimed before re-pathing is worth it. */
    private static final double DRIFT = TILE * 1.0;
    /** Give up chasing after this many re-paths that don't close the distance. */
    private static final int NO_PROGRESS_LIMIT = 10;
    /** How many times one approach may stop to back out of a beast's ring before giving up. */
    private static final int RETREAT_LIMIT = 3;
    private static final double RETREAT_MARGIN = 30.0;

    /**
     * How far one travel hop aims when there is nothing better to go on.
     *
     * Also the figure used for the coarse "is this within one hop" tests, which want a fixed answer
     * rather than one that changes with what happens to be loaded.
     */
    private static final double HOP = TILE * 25.0;
    /**
     * The furthest a hop may ever aim.
     *
     * MapView clamps any pathfinder click beyond forty tiles back to the edge of that circle
     * ({@code MAX_TILE_RANGE}), so asking for more does not fail - it silently lands somewhere
     * else, which is worse. Four tiles of margin keeps the hop honestly inside the clamp.
     */
    private static final double HOP_MAX = TILE * 36.0;
    /**
     * The shortest a hop may aim. Below this the re-planning overhead per hop outweighs the
     * accuracy, and a bot in an empty field would inch along a tile at a time.
     */
    private static final double HOP_MIN = TILE * 12.0;
    /**
     * How near a waypoint counts as reached.
     *
     * Router waypoints are the middle of a block of tiles, not a place anything needs to be stood
     * on, so insisting on arriving exactly would spend a pathfinder run per waypoint correcting a
     * few units that the next leg is about to undo anyway.
     */
    private static final double LEG_TOL = TILE * 3.0;
    /** How many times a failed leg is worth re-routing before falling back to walking at it. */
    private static final int MAX_REPLANS = 3;

    /**
     * How far off a waypoint a leg may finish before the rest of the route stops being trustworthy.
     *
     * One tile. {@link #LEG_TOL} is deliberately looser - a waypoint is a corner to pass, not a
     * place to stand - but the router only ever checked the lines between the waypoints themselves,
     * so anything further off than this and the next leg is a line nobody has examined.
     */
    private static final double LEG_SLACK = TILE * 1.0;

    /**
     * Close enough to be standing ON a waypoint rather than merely near it.
     *
     * A third of a tile, which is what it takes to guarantee being in the waypoint's own TILE: a
     * waypoint is a tile centre, so anything inside this radius rounds to the same tile whichever
     * way it is offset. That is the only property being bought - not precision for its own sake,
     * but the difference between the gap and the wall beside it.
     *
     * Used solely by the drift correction in {@code travelTo}, never as a general leg tolerance.
     * Held to as a rule it would be the two-tile hop dead band all over again: {@code stepTo} pulls
     * its aim back off anything solid, so a waypoint next to a stockpile can never be stood on this
     * exactly and a leg that demanded it would never finish.
     */
    private static final double ON_WAYPOINT = TILE * 0.34;

    /**
     * How far from a leg's starting point the walk may get, as a multiple of the leg's own length,
     * before it is a detour rather than a leg.
     *
     * One and a half, plus a few tiles of slack so that short legs - where going round a single
     * cart is a large fraction of the distance - are not tripped by ordinary weaving.
     */
    private static final double WANDER = 1.5;
    private static final double WANDER_SLACK = TILE * 6.0;
    /** How many gateways one journey may go through before that stops being plausible. */
    private static final int MAX_GATES = 4;

    /**
     * How many times one journey may go back and finish walking onto a waypoint it drifted off.
     *
     * Separate from {@link #MAX_REPLANS} and larger, because these are corrections rather than
     * retries - see the counter's own comment in {@code travelTo}. Bounded at all only so that a
     * waypoint which cannot be stood on cannot be walked at for ever.
     */
    private static final int MAX_DRIFTS = 6;
    /**
     * How many exploration hops toward a destination the clamped router can see but not reach.
     *
     * Each hop extends the observed region by up to {@link #HOP_MAX} tiles. Twelve is enough for
     * ~400 tiles of unknown ground - far more than any base-to-base journey. Bounded so that a
     * genuinely unreachable destination doesn't trap the bot in an endless explore loop.
     */
    private static final int MAX_EXPLORE = 12;
    /** Travel gives up after this many hops that don't get closer. */
    private static final int TRAVEL_STALL_LIMIT = 6;
    /**
     * Slop allowed on top of a target's reach and its own bulk before a stopped walk stops
     * counting as arrival.
     *
     * One tile. The honest bound is reach plus the thing's own size, since that is where the
     * pathfinder stops; this only covers it halting a step early. Anything more generous starts
     * excusing real failures - the log had a barrel filled from forty units away with a reach of
     * twenty-two, and two tiles of slop would still have called that an arrival.
     */
    private static final double STOP_SLACK = TILE * 1.0;
    /** How far back off an occupied spot to look for standable ground, and in what steps. */
    private static final double CLEAR_STEP = TILE * 0.5;
    private static final double CLEAR_MAX = TILE * 3.0;
    /**
     * How far to aim when the only thing we know is that something is touching us.
     *
     * Three tiles: past whatever we are against, and short enough that the client is being asked to
     * go round one obstacle it can see rather than to hold a straight line across a wood.
     */
    private static final double STEP_OFF = TILE * 3.0;
    /** How many refused clicks in a row mean the destination is not somewhere we can be. */
    private static final int REFUSE_LIMIT = 3;
    /**
     * How many times one walk may step clear of being wedged before that stops being the problem.
     *
     * Two. Once is the ordinary case - backed up against a gate, a barrel, a cart - and a second
     * covers stepping out of one thing into another. A third would be a bot shuffling around a
     * yard rather than one recovering from a corner.
     */
    private static final int UNSTICK_LIMIT = 2;

    private final GameUI gui;
    private final Abort abort;
    private final String log;

    /**
     * Gateways this journey has already tried and got nothing from.
     *
     * Cleared per journey rather than kept: a gate that would not open because somebody was
     * standing in it is worth another go on the next trip, and a gate that is genuinely locked
     * costs one attempt to find that out again. What it must not do is cost one attempt per
     * re-plan, which without this it does - the scoring is deterministic, so the gate that just
     * failed is the same gate the next re-plan picks.
     */
    private final Set<Long> refusedGates = new HashSet<>();

    /**
     * The gateway {@link #walkStraight} stopped for, handed to {@link Gates#pass} rather than left
     * for it to work out again. Zero when the leg failed for some other reason.
     */
    private long blockingGate = 0;

    /** The last point we printed the full evidence for, so a re-plan doesn't print it again. */
    private Coord2d whined = null;

    /**
     * Set when the last {@link #stepTo} click never became a walk at all.
     *
     * The distinction travel could not previously make, and the one that matters most: "we walked
     * and got no nearer" is evidence about walls, and "the pathfinder would not accept the click"
     * is evidence about the click. Reading the second as the first is what sent a bot standing
     * inside its own base out through a gateway to reach a barrel twelve tiles away.
     */
    private boolean stepRefused = false;

    /**
     * The keep-out circles {@link #publishKeepouts} last put in force, kept after they are dropped
     * from the shared map so {@link #ringedOff} can still ask what they were.
     */
    private Map.Keepout[] lastKeepouts = new Map.Keepout[0];

    /** Which way the last {@link #stepTo} was refused, or null if it was not. */
    private Pathfinder.Refusal stepRefusal = null;

    /**
     * Whether the last {@link #plan} could only reach the observed edge, not the destination.
     *
     * The clamped router ({@link Router#routeClamped}) treats UNSEEN ground as impassable and,
     * when the destination lies beyond the observed edge, returns a route that stops at the
     * nearest reachable observed tile instead. That route is not walkable as-is - its final leg
     * (the destination) would be walked straight at through unseen ground, which is the very
     * wall-hiding behaviour the clamp exists to stop. So {@link #travelTo} reads this flag to
     * decide it must explore forward (a hop, observing more) and re-plan before walking on.
     */
    private boolean planClamped = false;

    /**
     * The active route waypoints from the last {@link #plan}, stored so corridor checks can
     * compare keep-out circles against the full planned path, not just the current leg.
     *
     * Updated by {@link #plan} each time a route is computed. Read by {@link #checkPathBlocked}
     * to decide whether a moving entity has drifted onto the route between legs.
     */
    private List<Coord2d> currentRoute = null;

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

    /**
     * Whether the character is going anywhere.
     *
     * The server's own answer first - see {@link Walk#moving} - because it is the only one that is
     * not an inference. {@code getv() > 0} asked the same attribute for its speed, which reads a
     * standing character and a character the server has stopped identically to one whose velocity
     * has not been filled in yet. The pathfinder thread still counts: while it is alive there are
     * more legs of its route to come, so the character is between moves rather than finished.
     */
    public boolean walking() {
        return Walk.moving(gui)
            || ((gui.map != null) && (gui.map.pfthread != null) && gui.map.pfthread.isAlive());
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

    /**
     * How far a gob's own solid part reaches from its middle.
     *
     * The pathfinder walks to the EDGE of a collision box, so "how close did we get" has to be
     * read against the thing's size or every large tree reads as unreached. Taken from the same
     * box data the pathfinder blocks on, so the two agree about how big things are; anything with
     * no box recorded is a point, which is the safe way to be wrong here.
     */
    /**
     * How far a point is from the NEAREST part of a gob's solid box, rather than from a circle
     * drawn round it.
     *
     * {@link #bulk} is the CIRCUMSCRIBED radius - the furthest corner - and for anything round that
     * is the same answer. For a felled log it is not remotely: a log's box is a long thin
     * rectangle, so its circumscribed circle is set by half the LENGTH, and standing beside one
     * puts a character deep inside that circle while still a tile clear of the timber. Subtracting
     * bulk from a centre distance then reports zero or less, and every caller that reads it as "we
     * are touching it" is wrong by most of a tile - in the direction that matters, because it is
     * the direction the character actually approaches from when the log lies across its path.
     *
     * Exact rather than approximate, and cheaply so. The box data is axis-aligned in the gob's own
     * frame and the gob carries its rotation, so instead of rotating four corners into the world
     * this rotates the QUERY POINT back into the box's frame - one sin and one cos - and then the
     * nearest point on an axis-aligned rectangle is two subtractions. The lossy step everything
     * else in this stack takes, quantising a rotated shape to an axis-aligned box and that box to
     * tiles, is skipped entirely.
     *
     * Zero when the point is inside the box. A gob with no box recorded is a point, which is what
     * {@link #bulk} does and the safe way to be wrong.
     */
    public static double faceGap(Gob g, Coord2d from) {
        try {
            if ((g == null) || (from == null))
                return 0;
            Resource res = g.getres();
            HitBoxes.CollisionBoxSecondary[] boxes = (res == null) ? null
                : HitBoxes.collisionBoxMap.get(res.name);
            if (boxes == null)
                return from.dist(g.rc);
            // Into the gob's frame: translate to its origin, then un-rotate by its angle.
            Coord2d rel = from.sub(g.rc);
            double cos = Math.cos(-g.a), sin = Math.sin(-g.a);
            double lx = (rel.x * cos) - (rel.y * sin);
            double ly = (rel.x * sin) + (rel.y * cos);
            double best = Double.MAX_VALUE;
            for (HitBoxes.CollisionBoxSecondary box : boxes) {
                if ((box == null) || (box.coords == null) || (box.coords.length == 0))
                    continue;
                double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
                double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
                for (Coord2d c : box.coords) {
                    minx = Math.min(minx, c.x);
                    miny = Math.min(miny, c.y);
                    maxx = Math.max(maxx, c.x);
                    maxy = Math.max(maxy, c.y);
                }
                // Nearest point on the rectangle, which is zero on every axis the point is inside.
                double dx = Math.max(0, Math.max(minx - lx, lx - maxx));
                double dy = Math.max(0, Math.max(miny - ly, ly - maxy));
                best = Math.min(best, Math.hypot(dx, dy));
            }
            return (best == Double.MAX_VALUE) ? from.dist(g.rc) : best;
        } catch (RuntimeException e) {
            // Includes Loading: an unresolved gob is a point, same as bulk treats it.
            return (from == null) ? 0 : from.dist(g.rc);
        }
    }

    public static double bulk(Gob g) {
        try {
            Resource res = (g == null) ? null : g.getres();
            HitBoxes.CollisionBoxSecondary[] boxes = (res == null) ? null
                : HitBoxes.collisionBoxMap.get(res.name);
            if (boxes == null)
                return 0;
            double far = 0;
            for (HitBoxes.CollisionBoxSecondary box : boxes) {
                if ((box == null) || (box.coords == null))
                    continue;
                for (Coord2d c : box.coords)
                    far = Math.max(far, Math.hypot(c.x, c.y));
            }
            return far;
        } catch (RuntimeException e) {
            // Includes Loading: treat an unresolved gob as a point rather than guessing big.
            return 0;
        }
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
        Map.Keepout[] beasts = Hazards.keepouts(gui, from);
        Map.Keepout[] people = NBotConfig.on(NBotConfig.Key.avoidOthers)
            ? Crowd.keepouts(gui, from) : new Map.Keepout[0];
        Map.Keepout[] all = Crowd.merge(beasts, people);
        /* Kept past the clear below so a refusal can be checked against the circles that were in
         * force when it happened - see learnRefusal, which must not file a tile as furniture when
         * one of our own rings is what the search actually objected to. */
        lastKeepouts = (all == null) ? new Map.Keepout[0] : all;
        Map.keepout(all);
    }

    /** Drops every keep-out. Must be called on every exit path - see {@link #approach}. */
    public void clearKeepouts() {
        Map.keepout(null);
    }

    /**
     * True if one of the circles we published could itself be the reason a search gave up short of
     * {@code dest}, either by sitting on the destination or by lying across the way to it.
     *
     * A keep-out is invisible to every other record: {@link Observed} does not hold it, the router
     * does not consult it, and the tile under it stays honestly open. So a bear wandering past a
     * doorway makes the pathfinder refuse a perfectly good tile, and without this test that tile is
     * filed as blocked and stays that way long after the bear has gone - which is how open ground
     * inside a walled base ended up unreachable.
     */
    private boolean ringedOff(Coord2d from, Coord2d dest) {
        for (Map.Keepout k : lastKeepouts) {
            if (k == null)
                continue;
            if (dest.dist(k.c) <= k.r)
                return true;
            Coord2d span = dest.sub(from);
            double len2 = (span.x * span.x) + (span.y * span.y);
            Coord2d rel = k.c.sub(from);
            /* Nearest point on the segment to the circle's middle; the clamp keeps that point
             * between the two ends rather than out along the infinite line. */
            double t = (len2 < 1e-6) ? 0.0
                : Math.max(0.0, Math.min(1.0, ((rel.x * span.x) + (rel.y * span.y)) / len2));
            if (from.add(span.mul(t)).dist(k.c) <= k.r)
                return true;
        }
        return false;
    }

    /**
     * True if a keep-out circle now intersects the corridor from {@code from} to {@code to}.
     *
     * Called proactively at the start of each leg in {@link #travelTo} to catch a beast or
     * character that has drifted onto the planned path between legs. The reactive check inside
     * {@link #walkStraight} catches one that steps onto the leg DURING the walk; this catches
     * one already in place when the leg begins, which is the case the user reported.
     *
     * Uses the same segment-circle intersection math as {@link #ringedOff}, checking every
     * circle currently in {@link #lastKeepouts}.
     *
     * @param from the current position
     * @param to   the leg destination (next waypoint, or the final destination on the last leg)
     * @return true if any keep-out circle intersects the segment
     */
    private boolean checkPathBlocked(Coord2d from, Coord2d to) {
        if (from == null || to == null)
            return false;
        return ringedOff(from, to);
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
        int unsticks = 0;
        // The search behind the last path issued, so arrival can be told from never having set off.
        Pathfinder walk = null;

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
            Gob atTarget = Hazards.within(gui, target.rc, Hazards.KEEPOUT);
            if (atTarget != null) {
                hazardBlocked = true;
                cancelWalk();
                return false;
            }

            // One that has closed on US. It can't be handed to the pathfinder as a no-go circle
            // while we're standing inside it - the search would have no legal first move - so step
            // out of it first and re-path on the next pass.
            Gob onUs = Hazards.within(gui, me.rc, Hazards.PATH_CLEARANCE);
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
                walk = gui.map.pf;
                aimed = target.rc;
                /* Refused outright, and from here it will go on being refused: the commonest
                 * reason is that we are standing inside a collision box, which is exactly where
                 * walking up to something leaves us. Step clear and re-path rather than spending
                 * the whole attempt budget re-issuing a click nothing will act on. A gate is the
                 * case that matters - a bot wedged against one cannot approach it to open it, and
                 * cannot walk away from it either. */
                if ((walk != null) && (walk.refusal == Pathfinder.Refusal.STUCK)
                    && (++unsticks <= UNSTICK_LIMIT)) {
                    NLog.log(log, "cannot path to #" + id + " (" + walk.why()
                        + ") - stepping clear and trying again");
                    Walk.unstick(this, gui, target.rc);
                    aimed = null;
                    continue;
                }
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

            /* The walk finished and the target hasn't moved: as close as pathing will get us, so
             * count it as arrival even though we are further out than `reach`. pfRightClick paths
             * to the edge of the gob's HITBOX, and a big tree's trunk is wider than two tiles, so
             * a strict distance test would leave a bot circling every large thing it approached.
             *
             * BUT ONLY WITHIN THE TARGET'S OWN BULK. Without that bound this said "arrived"
             * wherever the walk happened to stop, which is a completely different claim - the walk
             * stops when the pathfinder can find no way, and the commonest reason for that is
             * water. So a bot stood on a river bank was told it had arrived, and then did the
             * thing it does on arrival: right-clicked a gob sixty tiles away, which the SERVER
             * obligingly walked it to, straight through the river. Every "it swam across for an
             * apple" report is this line. The log has the tamer version of the same fault too -
             * filling from a barrel forty units away with a reach of twenty-two.
             *
             * AND ONLY IF A WALK ACTUALLY HAPPENED, which is the exact form of that same bound. A
             * search that finds no way returns an empty path, so not one move is issued and `mc`
             * is never set: the character has not walked as far as it can, it has not moved. The
             * distance test can only approximate that, and approximates it worst where it matters
             * - across a narrow river, where the far bank is genuinely near. */
            Gob now = gob(id);
            Gob here = player();
            if (!walking() && now != null && here != null && aimed != null
                && walk != null && walk.mc != null
                && aimed.dist(now.rc) <= DRIFT
                && here.rc.dist(now.rc) <= (reach + bulk(now) + STOP_SLACK))
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
        Coord2d dest = beast.rc.add(away.mul(Hazards.PATH_CLEARANCE + RETREAT_MARGIN));

        clearKeepouts();
        gui.map.pfLeftClick(dest.floor(), null);
        waitUntil(() -> {
            Gob p = player();
            Gob b = gob(beast.id);
            if (p == null || b == null)
                return true;
            return p.rc.dist(b.rc) > Hazards.PATH_CLEARANCE + RETREAT_MARGIN;
        }, 200);
        cancelWalk();
    }

    // ------------------------------------------------------------------ point travel

    /**
     * Walks to a fixed point that is already inside the pathfinder's window.
     *
     * Two things happen here that did not used to, and between them they are the difference
     * between a bot that walks to a barrel and one that opens a gate to get away from it.
     *
     * THE AIM IS MOVED OFF ANYTHING SOLID. A click inside a collision box is not a short walk that
     * stops early - it is nothing at all. {@code Map.main} builds a visibility graph and A*s to the
     * destination vertex, and a vertex inside a box can see nothing, so the search returns an EMPTY
     * path, {@link Pathfinder} issues no clicks, and the thread exits. The character never moves and
     * nothing anywhere says why. That is the normal case for a destination rather than an odd one:
     * a place is a rectangle the player drew AROUND things, so the centre of it is the likeliest
     * spot in it to have a barrel standing on it. This character's water place centre landed on one
     * the moment the rectangle was redrawn, and every trip to the water failed instantly from then
     * on.
     *
     * AND A REFUSED CLICK IS RECORDED AS SUCH. See {@link #stepRefused}.
     *
     * @return true if we got within {@code tol} of it.
     */
    public boolean stepTo(Coord2d dest, double tol) throws InterruptedException {
        stepRefused = false;
        stepRefusal = null;
        if (dest == null)
            return false;
        Gob me = player();
        if (me == null)
            return false;
        String why = "the click was thrown away before a search started";
        /* True once a fresh search is actually running for this click. A click that never got
         * this far is not a verdict on the ground - it is a click that was thrown away - and it
         * must not be allowed to reach the direct-walk fallback below, which exists to answer
         * refusals the search itself produced. */
        boolean started = false;
        /* Where the click ACTUALLY went, which is not always where the caller asked.
         *
         * Every refusal line below names {@code dest}, and for a nudged aim that is the one
         * coordinate the client was never asked about. It made a whole class of failure unreadable:
         * a log saying "pathfinder refused <the gate>" is equally consistent with the aim having
         * been left on the gate and with it having been moved a tile off and refused there, and
         * those want opposite fixes. Two rounds were spent on the shut-gate refusal without being
         * able to tell whether the nudge had fired at all. */
        Coord2d aimed = dest;
        try {
            Coord2d aim = standable(me.rc, dest);
            aimed = aim;
            if (aim.dist(dest) > 1.0)
                NLog.log(log, "  aiming at " + GateManager.fmt(aim) + " instead of "
                    + GateManager.fmt(dest) + " - the record says there is no standing where"
                    + " we were asked to go");
            publishKeepouts(me.rc);
            Thread was = gui.map.pfthread;
            gui.map.pfLeftClick(aim.floor(), null);
            /* No settling pause before this is read. pfLeftClick starts the thread inside itself,
             * before it returns, so a new thread here is already alive and a thread that is still
             * the old one means the click was thrown away - both facts are true immediately. The
             * quarter-second this replaces was covering a race that does not exist, and it was
             * being paid on every hop of every journey. */
            Pathfinder walk = gui.map.pf;
            Thread pft = gui.map.pfthread;
            if ((pft == null) || (pft == was) || (walk == null)) {
                stepRefused = true;
                /* The click started no search. The map knows which of several quite different
                 * faults that was - a missing player, a target off the edge of the window, a
                 * thrown exception - and until it was asked, all of them read here as the same
                 * unhelpful sentence. */
                if (gui.map.pfrefusal != null)
                    why = gui.map.pfrefusal;
                /* And the search that WAS running is still aimed at its old target. pfLeftClick
                 * deliberately does not cancel it on a refused click (that was the dead-stop the
                 * caller is about to recover from), but while it lives it keeps issuing clicks
                 * toward the old place as this leg re-plans for the new one - two movement
                 * streams fighting. Stop it here, where the new click is known not to have
                 * superseded it.
                 *
                 * Terminate the CAPTURED search ({@code walk}), not whatever the shared slot now
                 * holds. In the throwaway branch the slot was not replaced by this click, but a
                 * concurrent clicker - a second bot window, or a human using the client - could
                 * have replaced it between the captures above, and terminating that one would
                 * stop a walk this bot does not own. */
                if ((walk != null) && (was != null) && was.isAlive()) {
                    walk.terminate = true;
                    was.interrupt();
                }
            } else {
                started = true;
                waitUntil(() -> {
                    Gob p = player();
                    return p == null || p.rc.dist(dest) <= tol || !pft.isAlive();
                }, 400);
                /* mc is set from the first edge the path yields, so a null one after the walk has
                 * finished means the search produced no edges at all - the destination was
                 * unreachable from the start rather than merely far off. */
                stepRefused = (walk.mc == null);
                stepRefusal = walk.refusal;
                if (walk.why() != null)
                    why = walk.why();
                /* The wait above returns on its timeout as well as on arrival, so the search can
                 * still be running here. That does not stop the finally below clearing the
                 * circles: the search reads them ONCE, at the top of pathfind - Map.initGeography
                 * samples the static into a local before the A* runs - so a clear here cannot
                 * rewrite a route that has already been planned around them. The only re-read is
                 * the step-aside re-path when the origin is blocked, a handful of tiles; losing a
                 * circle for that one segment is nothing next to the alternative. */
            }
        } finally {
            /* Clear whatever this step published, on every exit path. Holding the circles while a
             * long search still ran - the previous behaviour - leaked them: travel and its
             * helpers never clear on the way out, so a hop whose search outlived the wait left a
             * stale ring standing, and every later click, the bot's and a human's, was rerouted
             * through where a bear used to stand. Each step republishes fresh circles before its
             * own click, so clearing here only ever removes the previous step's stale set. */
            clearKeepouts();
        }
        /* The pathfinder declined, so ask the SERVER, which does not decline. See {@link Walk}:
         * every way the client pathfinder can refuse ends in the character not moving and nothing
         * being logged, and one of them - our own position being inside a collision box - is a
         * deadlock, because the move that would take us back out is refused along with the rest.
         * That is what a bot standing against a shut gate is in, and it is why "runs face-first
         * into the wall" is a fair description of what it looks like.
         *
         * The line has to be proved first, since server movement is linear and the server will
         * swim. Keep-outs are still up when this runs - they are cleared only in the finally,
         * after this fallback - which is fine: they exist to shape the pathfinder's search,
         * whereas {@link Walk#lineClear} checks hazards along the line itself, and a bot standing
         * inside a ring must still be able to walk out of it. */
        if (stepRefused) {
            /* The two cases the fallback must NOT answer with a direct walk, both of which explain
             * the refusal without any verdict on the ground: a keep-out ring is a bear that will
             * move on, and a click that never became a search says nothing at all about the tile
             * it aimed at. Direct-walking either is the shape of the give-up loop this method was
             * built to end - the log has it walking a clear line seven times over, no progress. */
            if (ringedOff(me.rc, dest)) {
                NLog.log(log, "pathfinder refused " + GateManager.fmt(dest)
                    + " and one of our own keep-out circles was across the way"
                    + " - not walking the line directly");
            } else if (!started) {
                NLog.log(log, "the click for " + GateManager.fmt(dest) + " never became a search ("
                    + why + ") - not walking the line unplanned");
            } else if (Walk.lineClear(gui, me.rc, dest)) {
                NLog.log(log, "pathfinder refused " + GateManager.fmt(aimed)
                    + (aimed.equals(dest) ? "" : (" (aimed for " + GateManager.fmt(dest) + ")"))
                    + " (" + why
                    + ") - walking there directly, the line is clear");
                Walk.straightTo(this, gui, dest, tol);
                stepRefused = false;
            } else {
                /* The evidence, not just the verdict - and ONCE per destination, because the hop
                 * loop re-issues this seven times a leg and travel re-plans four times, so the same
                 * refusal used to fill eight hundred lines with the one fact already known. What is
                 * wanted is the other four records' opinion of the same point, which is what
                 * actually differs between "the destination is on a barrel", "it is in a lake" and
                 * "there is a wall in front of it". */
                boolean fresh = (whined == null) || (whined.dist(dest) > MCache.tilesz.x);
                NLog.log(log, "pathfinder refused " + GateManager.fmt(aimed)
                    + (aimed.equals(dest) ? "" : (" (aimed for " + GateManager.fmt(dest) + ")"))
                    + " (" + why
                    + ") and the straight line is not clear either"
                    + (fresh ? "" : " [same point]"));
                if (fresh) {
                    whined = dest;
                    NLog.log(log, "  destination: " + Probe.explain(gui, dest));
                    NLog.log(log, "  the way there: " + Probe.line(gui, me.rc, dest));
                    NLog.log(log, Probe.map(gui, dest, 12));
                    /* The refusal dump proper: the loaded gobs and their real boxes around the
                     * refused point, each with the record's verdict on the tiles its box covers.
                     * This is the comparison the record cannot make for itself - see Probe. */
                    NLog.log(log, Probe.objectsNear(gui, dest, 8));
                }
                learnRefusal(me.rc, dest, fresh);
            }
        }
        Gob now = player();
        return now != null && now.rc.dist(dest) <= tol;
    }

    /**
     * Believes the client over our own record, once the two have been shown to disagree.
     *
     * The whole value is in the CONDITION, not the recording. A refusal on its own says almost
     * nothing - the commonest cause by far is a shut gateway, and noting one of those would teach
     * the router to plan around the only place a wall is meant to be crossed. What is worth
     * learning from is the CONTRADICTION: the search ran and found nothing, and our own record says
     * the destination tile is neither solid nor a gateway and the whole line to it is walkable. Then
     * the only remaining explanation is something standing there that {@link Observed} declined to
     * record - which is exactly what its centre rule does to a stockpile or barrel sitting off to
     * one side of a tile, near enough to be in the way and not near enough to the middle to count.
     *
     * STUCK is not evidence and is excluded by the caller passing only the NO_ROUTE case: being
     * unable to leave where we stand says nothing whatever about where we were going.
     *
     * Without this the re-plan after the failed leg reads the same unchanged record and returns the
     * same route - four times, then the journey gives up, which is the shape the log kept showing at
     * one particular tile with a stockpile on it.
     */
    private void learnRefusal(Coord2d from, Coord2d dest, boolean fresh) {
        if (stepRefusal != Pathfinder.Refusal.NO_ROUTE)
            return;
        if (ringedOff(from, dest)) {
            if (fresh)
                NLog.log(log, "  not learning that tile - one of our own keep-out circles"
                    + " was across the way, so the refusal says nothing about the ground");
            return;
        }
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if (here == null)
            return;
        Coord2d off = here.sc.sub(from);
        Coord tile = dest.add(off).floor(MCache.tilesz);
        if (Observed.solid(here.seg, tile) || Observed.gate(here.seg, tile))
            return;   // the refusal is already explained; nothing to learn
        if (!Router.walkable(gui, here.seg, here.sc.floor(MCache.tilesz), tile))
            return;   // our record objects to the line too, so the client is not telling us anything
        /* A gateway ANYWHERE on the line explains the refusal on its own, and this has to be tested
         * separately because Router.walkable cannot do it: gate tiles are passable to the router by
         * design, so a line straight through a shut gateway comes back walkable. Without this the
         * far side of every gateway would be learned as blocked the first time one was shut, and the
         * router would stop planning through gates at all - the one thing this must never cause.
         * Deliberately reading the tile record rather than asking Gates, so it holds whether or not
         * a gate gob has loaded and whether or not gate handling is switched on. */
        Coord2d segFrom = here.sc, segTo = dest.add(off);
        int steps = Math.max(1, (int) Math.ceil((segFrom.dist(segTo) / MCache.tilesz.x) * 2));
        for (int i = 0; i <= steps; i++) {
            Coord2d at = segFrom.add(segTo.sub(segFrom).mul((double) i / steps));
            if (Observed.gate(here.seg, at.floor(MCache.tilesz)))
                return;
        }
        Refused.note(here.seg, tile);
        if (fresh)
            NLog.log(log, "  our record calls that tile clear and the client will not walk to it"
                + " - treating " + tile + " as blocked for now"
                + " (" + Refused.count(here.seg) + " such tile(s) in this segment)");
    }

    /**
     * The nearest point to {@code aim} on the line back towards {@code from} that is not inside
     * something solid, or {@code aim} itself when it already is not.
     *
     * Backwards along the line FIRST, because pulling towards the caller shortens the walk without
     * changing where it was going, so the arrival test the caller is about to apply still means what
     * it meant. Bounded at {@link #CLEAR_MAX}, since a destination buried three tiles deep in solid
     * ground is not a destination that was nudged wrong - it is one the caller should hear about,
     * which it does through {@link #stepRefused}.
     *
     * SIDEWAYS when backing off cannot help, which "on purpose, never sideways" quietly ruled out
     * and an orchard proves is wrong. Trees planted on a two-tile lattice leave a one-tile lane
     * between them; a hop down that lane aims at a point inside a trunk, and every point behind it
     * on the same line is inside the trunk in front of it. The whole ring of retreat positions is
     * occupied, so the nudge gives up, the aim goes to the client unchanged, and the click is thrown
     * away BEFORE A SEARCH STARTS - which is not the pathfinder saying "no way through", it is the
     * pathfinder saying "that is not a place". Seven of those and the leg dies. The client could
     * have threaded that lane perfectly well; it was never given a target it could accept.
     *
     * A step to one side is still the same hop - it is a point at nearly the same distance in nearly
     * the same direction, which is all the caller's arrival test cares about - so the reason for
     * preferring backwards is a tiebreak, not a prohibition. Try backwards, then widen.
     */
    private Coord2d standable(Coord2d from, Coord2d aim) {
        List<Gob> gobs = solids(gui);
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        /* A SHUT gateway is solid to the client and invisible to every test above.
         *
         * `solids` leaves gateways out on purpose - the router must be free to plan through them,
         * since a gateway is the one place a wall is meant to be crossed - and `Observed.solid`
         * makes the same exception. Which means that when the gate layer walks up to a gate to open
         * it, the aim lands on the gate's own tile, nothing here objects, and the click is thrown
         * away by the client before a search starts. Every gate crossing in the log pays one of
         * those, and from the outside it looks exactly like the bot trying to walk through the wall
         * before finding the gate. See shutGateTiles for why this is a tile test and not a box one. */
        Set<Coord> shutGates = shutGateTiles(gui, here);
        if (!blockedThere(gobs, shutGates, here, from, aim))
            return aim;
        Coord2d back = from.sub(aim);
        double len = back.abs();
        if (len < 1.0)
            return aim;
        back = back.div(len);
        for (double d = CLEAR_STEP; (d <= CLEAR_MAX) && (d < len); d += CLEAR_STEP) {
            Coord2d t = aim.add(back.mul(d));
            if (!blockedThere(gobs, shutGates, here, from, t))
                return t;
        }
        /* Rings outward from the aim, nearest first, so the answer is the least the aim can be moved
         * and still be somewhere a character can be. Both offsets are swept together rather than
         * ring-by-ring in one axis, because the lane out of a lattice is diagonal as often as not. */
        Coord2d side = new Coord2d(-back.y, back.x);
        for (double d = CLEAR_STEP; d <= CLEAR_MAX; d += CLEAR_STEP) {
            for (int s = -1; s <= 1; s += 2) {
                for (double along = 0; along <= d; along += CLEAR_STEP) {
                    Coord2d t = aim.add(side.mul(d * s)).add(back.mul(along));
                    if (!blockedThere(gobs, shutGates, here, from, t))
                        return t;
                }
            }
        }
        return aim;
    }

    /**
     * True if anything blocks a live world point - either loaded and boxed, or merely remembered.
     *
     * The loaded-gob half alone was the bug. {@link #solids} can only see what is in the object
     * cache, so a palisade corner post that has scrolled out of view is not in it, and an aim
     * landing on that post was handed to the pathfinder unchanged: the bot walked at a wall it had
     * already seen and stood there refusing until the leg died. The record kept by {@link Observed}
     * outlives the gob, and {@link Probe} was already reading it - which is why the diagnostics
     * could name the corner post ("seen=wall") in the very log line that says we aimed at it. This
     * closes that gap by asking the same record the diagnostics ask.
     *
     * {@code here} converts between live and segment coordinates the way {@link #learnRefusal}
     * does: it holds where we are in both systems, so the difference carries any nearby point
     * across, which is sound over the few tiles an aim is ever nudged. A null one means the map
     * cannot place us, leaving only the live test - the old behaviour, and the right fallback,
     * since a remembered tile we cannot locate is worse than no answer at all.
     *
     * {@link Observed#solid} counts walls but deliberately not gateways, so this keeps the
     * open-gate exception {@link #solids} makes rather than fighting it - except for the gateways
     * in {@code shutGates}, which are exceptions to that exception. See {@link #shutGateTiles}.
     */
    private static boolean blockedThere(List<Gob> gobs, Set<Coord> shutGates, WorldAnchor here,
                                        Coord2d from, Coord2d wc) {
        if (inside(gobs, wc))
            return true;
        if (here == null)
            return false;
        Coord tile = wc.add(here.sc.sub(from)).floor(MCache.tilesz);
        if (shutGates.contains(tile))
            return true;
        return Observed.solid(here.seg, tile);
    }

    /**
     * The tiles live gateways are standing SHUT on, in segment-tile space.
     *
     * A tile test rather than a box test, and that is the whole point of it. The obvious way to
     * keep an aim off a shut gate is to put the gate gobs into the list {@link #inside} checks -
     * which was tried, shipped, and did nothing at all: {@code inside} resolves through
     * {@link haven.automated.pathfinder.Pathfinder#isInsideBoundBox}, which only reports a hit when
     * the resource carries a {@code hitAble} collision box, and gateway resources do not. Five gate
     * crossings in a logged run, five clicks thrown away on the gate's own tile, no change.
     *
     * So do not ask the hitbox data. A gateway occupies its tile by definition, the live gob is the
     * only thing that knows whether it is standing open, and those two facts together answer the
     * question without consulting anything that might not have an entry.
     *
     * Open ones are deliberately absent: an open gateway is a doorway, aiming through it is correct,
     * and blocking it here would stop the bot walking through its own front gate.
     */
    private static Set<Coord> shutGateTiles(GameUI gui, WorldAnchor here) {
        Set<Coord> out = new HashSet<>();
        if ((gui == null) || (gui.map == null) || (here == null))
            return out;
        Gob me = gui.map.player();
        if (me == null)
            return out;
        Coord2d off = here.sc.sub(me.rc);
        for (Gob g : GateManager.loaded(gui)) {
            if (!GateManager.isOpen(g))
                out.add(g.rc.add(off).floor(MCache.tilesz));
        }
        return out;
    }

    /**
     * True if a live world point is inside something solid.
     *
     * Answered from the very box test {@link Pathfinder} runs, so the two cannot disagree about
     * what blocks - which is the entire point, since the question being asked is "will the
     * pathfinder accept a click here" and no independent notion of solidity can answer that.
     */
    public static boolean occupied(GameUI gui, Coord2d wc) {
        return (wc != null) && inside(solids(gui), wc);
    }

    /**
     * Everything loaded that is not us and is not an OPEN gateway. Snapshotted, so a probe of
     * several points locks once.
     *
     * The gateway exception matters more than it looks. A gate's collision box is stored per
     * resource and does not change when the gate swings, so this test - which is exactly the local
     * pathfinder's isInsideBoundBox - calls an open gateway solid across the full three tiles of
     * its opening. The pathfinder itself does not: it reads the gate's state and only adds the
     * blocking slab when it is shut.
     *
     * So every aim into an open gateway was being nudged sideways by `standable`, off the centre
     * line and towards a post, and every straight line through one was refused by lineClear. That
     * is stepping through a gate you have just opened and clipping the corner post beside it.
     */
    private static List<Gob> solids(GameUI gui) {
        List<Gob> out = new ArrayList<>();
        if (gui == null || gui.map == null)
            return out;
        try {
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob g : gui.ui.sess.glob.oc) {
                    if (g.isPlgob(gui))
                        continue;
                    if (GateManager.isGate(g) && GateManager.isOpen(g))
                        continue;
                    out.add(g);
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

    private static boolean inside(List<Gob> gobs, Coord2d wc) {
        Coord p = wc.floor();
        for (Gob g : gobs) {
            try {
                Resource res = g.getres();
                if ((res != null) && Pathfinder.isInsideBoundBox(g.rc.floor(), g.a, res.name, p))
                    return true;
            } catch (RuntimeException e) {
                // Includes Loading: a gob whose resource hasn't arrived cannot be tested, and
                // guessing solid would refuse ground that is almost certainly fine.
            }
        }
        return false;
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
     * @return a {@link TravelResult} describing how the journey ended: {@link TravelResult#arrived}
     *         if we got within {@code tol} of the destination, or a blocked/failed/aborted result
     *         explaining why not.
     */
    public TravelResult travelTo(Coord2d dest, double tol) throws InterruptedException {
        if (dest == null)
            return TravelResult.failed(null, "null destination");
        /* Record whatever walls are in sight before planning, so the route about to be chosen
         * benefits from this trip rather than only the next one. */
        Observed.observe(gui);
        refusedGates.clear();

        /* NO ROUTE MUST NOT MEAN BLIND HOP.
         *
         * `itinerary` puts the destination on the end of every route, and when there is no route
         * that is the WHOLE journey: one leg, aimed straight at somewhere that may be a hundred and
         * seventy tiles away, walked by a hop loop that aims up to thirty-six tiles at a time. Every
         * check this class has gained lives inside the routed path, and this leg never enters it.
         *
         * That is the whole of "it paths through things just out of render", and it is NOT a memory
         * problem: replayed against botmap.json, the 176-tile leg at 10:30 crosses EIGHTEEN
         * remembered WALL tiles and twenty-three solid ones. The record had every one of them, and
         * every fix aimed at reading that record better was aimed at a code path this journey never
         * took. The router was simply never asked - `WorldAnchor.capture` cannot place a destination
         * that far off, so `plan` gives up with "the map file can't place it yet". Twenty-eight
         * journeys in one log ran with no route at all.
         *
         * The handler below walks ONE short hop, learns whatever is there, and asks the router
         * again. That is the only fallback that does not bypass the router: a longer hop would
         * walk around whatever walls the hop could not see, the loop below would re-plan from
         * the far side having learned nothing, and the next hop would do the same. */
        // First, try to get a route. If WorldAnchor can't place dest, fall back to walking ONE
        // hop straight at it and asking again - not because wall-following the old way was wrong
        // in isolation, but because the loop below owns re-planning and giving it a hop that
        // crossed 12 walls would mean re-planning on the far side of those walls, learning
        // nothing about them, and re-planning again on the next hop for the same reason.
        List<Coord2d> route = plan(dest);
        /* When the router cannot place the destination at all, the old code ran a "local corridor"
         * loop that aimed 25 tiles at a time and re-planned each time. Twenty hops of that is the
         * distance from any reasonable gate to a far work site, and "aim 25 tiles at the goal"
         * with no router in the way is "walk 25 tiles at the goal" - which is what produced
         * journeys that crossed eighteen remembered WALL tiles while still believing the route
         * was empty. The behaviour that replaces it is the same loop the router uses for any
         * ordinary failure: walk one hop short enough that the only thing that can stop it is a
         * wall, learn whatever's there, and ask the router again. */
        if (route == null) {
            /* A single hop, never to the destination itself, so the worst case is failing in
             * front of a wall that this hop saw and a normal walkStraight failure cannot
             * bypass. */
            if (!hopToward(dest))
                return TravelResult.blocked(me(),
                    "hop toward " + GateManager.fmt(dest) + " stopped short - a wall is in the way");
            route = plan(dest);
            /* Still nothing. The router knows nothing more than it did a moment ago, which means
             * the destination is genuinely unreachable from here - handing the loop a single
             * waypoint (the destination) is exactly the bypass this method exists to stop. Give
             * up and let the caller decide what an unreachable destination means for its task. */
            if (route == null) {
                NLog.log(log, "no route to " + GateManager.fmt(dest) + " from "
                    + GateManager.fmt(me()) + " after a one-hop probe - giving up");
                return finish(dest, tol, true,
                    "no route to " + GateManager.fmt(dest) + " after a one-hop probe");
            }
        }

        /* A route clamped at the observed edge is not walkable as-is.
         *
         * {@link #plan} asked the clamped router, so a route that stops at the vision edge rather
         * than at the destination means the destination is beyond what has been observed. Walking
         * that route means walking its last leg - the destination - straight at a target nobody has
         * seen, through whatever invisible wall may sit there, which is exactly what the clamp
         * exists to stop. So a clamped route is a cue to walk a hop forward, see more, and ask
         * again. Each hop extends the observed region by up to {@link #HOP_MAX} tiles, so twelve
         * covers far more than any base-to-base gap.
         *
         * Bounded at all only so that a destination which is genuinely out of reach - a place
         * nobody can stand, or another segment - does not walk the bot away from base for ever. */
        int explores = 0;
        while (planClamped && (explores++ < MAX_EXPLORE)) {
            NLog.log(log, "route clamped " + (int) shortfall(dest) + "u short of "
                + GateManager.fmt(dest) + " - exploring forward (hop " + explores + "/"
                + MAX_EXPLORE + ")");
            if (!hopToward(dest))
                return TravelResult.blocked(me(), "exploration hop toward " + GateManager.fmt(dest)
                    + " stopped short - a wall is in the way");
            Observed.observe(gui);
            route = plan(dest);
            if (route == null) {
                NLog.log(log, "no route to " + GateManager.fmt(dest) + " after exploring - giving up");
                return finish(dest, tol, true,
                    "no route to " + GateManager.fmt(dest) + " after exploring");
            }
        }
        if (planClamped) {
            NLog.log(log, "destination " + GateManager.fmt(dest) + " still beyond the observed edge after "
                + MAX_EXPLORE + " exploration hops - giving up");
            return TravelResult.failed(me(),
                "destination " + GateManager.fmt(dest) + " beyond observed ground after " + MAX_EXPLORE
                    + " hops");
        }

        /* The waypoints themselves, not just how many. A count says nothing about the shape of a
         * route, and the shape is the thing that goes wrong - whether it turns towards a gateway,
         * whether it doubles back, whether the one waypoint it produced is anywhere near the way.
         * Several rounds of this have been spent inferring that from distances in failure
         * messages, which is guesswork when it could be a fact. */
        NLog.log(log, "travel to " + GateManager.fmt(dest) + " from " + GateManager.fmt(me())
            + ": " + ((route == null) ? "no route" : (route.size() + " waypoint(s) " + fmt(route)))
            + " then the destination");
        List<Coord2d> legs = itinerary(route, dest);
        int replans = 0;
        int gates = 0;
        /* Its own budget, not MAX_REPLANS'.
         *
         * A drift correction is a different animal from a failure re-plan: we know exactly where we
         * need to be and it is a few units away, so spending a walk on it is productive. Failure
         * re-plans are the ones that tend to return the identical route, which is what MAX_REPLANS
         * is small for. Sharing the counter meant a couple of legitimate corrections used up the
         * budget that a real failure later in the journey needed, and the journey gave up hundreds
         * of units short. */
        int drifts = 0;
        /* And its own budget again, because this one had NONE.
         *
         * A keep-out ring is not in the router's world - it is a live circle round a beast or
         * another character, while plan() reads the observed grid - so a re-plan provoked by one
         * has no reason whatever to come back different, and generally does not. With i = -1 and
         * no counter that is an unbounded loop: re-plan, same route, still blocked, re-plan. Seen
         * at the start of a session re-planning the identical 141-tile route every twelve
         * milliseconds, which is the bot "failing to path anywhere".
         *
         * Three, then give up on re-planning and walk the leg. This check is an OPTIMISATION - its
         * own comment says the pathfinder inside walkStraight catches the same thing reactively and
         * this only saves the hop budget - so declining to use it costs a wasted leg, which is
         * exactly what the unbounded version cost every twelve milliseconds instead. */
        int blocked = 0;
        final int MAX_BLOCKED = 3;
        for (int i = 0; i < legs.size(); i++) {
            if (!abort.running())
                return TravelResult.aborted(me());
            /* The destination is the last leg and is the only one that has to be arrived at
             * properly; the rest are waypoints to pass near. */
            boolean last = (i == (legs.size() - 1));
            blockingGate = 0;
            /* If a keep-out circle (beast aggro ring, other character's personal space) has
             * drifted onto the current leg's corridor since the route was planned, re-plan
             * before wasting a walk on a leg that is now blocked. The local pathfinder inside
             * walkStraight will also catch this and abort mid-leg, but catching it here saves
             * the hop budget and gets a fresh route drawn around the new obstacle sooner.
             *
             * This is proactive rather than reactive: ringedOff inside walkStraight catches a
             * beast that steps onto the leg DURING the walk; this catches one that was already
             * there when the leg started, which is the case the user asked about. */
            if (checkPathBlocked(me(), legs.get(i)) && (blocked < MAX_BLOCKED)) {
                blocked++;
                NLog.log(log, "keep-out circle now blocks leg corridor to " + GateManager.fmt(legs.get(i))
                    + " from " + GateManager.fmt(me()) + " - re-planning"
                    + " (" + blocked + " of " + MAX_BLOCKED + ")");
                Observed.observe(gui);
                List<Coord2d> again = plan(dest);
                if (again == null) {
                    return finish(dest, tol, true,
                        "re-plan failed - keep-out blocking corridor to " + GateManager.fmt(legs.get(i)));
                }
                legs = itinerary(again, dest);
                i = -1;
                continue;
            }
            if (blocked == MAX_BLOCKED) {
                /* Said once, not once per leg, so the give-up is on the record without becoming the
                 * spam it replaces. Incremented past the cap to make it once. */
                blocked++;
                NLog.log(log, "keep-out re-planning gave the same route " + MAX_BLOCKED
                    + " times - walking the legs anyway and letting the pathfinder handle the ring");
            }
            /* Every leg, whether it works or not.
             *
             * The failure paths have all been well logged for some time and the SUCCESS path has
             * not, which turns out to be the wrong way round for the one symptom left: a bot that
             * paces about near a wall is not failing legs, it is walking them. The local pathfinder
             * goes round whatever is in front of it and reports honest progress, so a route being
             * walked badly - a leg that ends four tiles from where it aimed, then another, then a
             * re-plan back to the first - produced no line at all. Where a leg started, where it
             * aimed, and where it actually stopped is the whole of that picture, and it is one line
             * per waypoint rather than one per hop. */
            Coord2d began = me();
            /* A waypoint is reached within a TILE, not within three.
             *
             * Three was the same mistake as the trim, one layer down, and fixing the trim alone
             * only moved it: walkStraight returns as soon as it is inside the tolerance, so a
             * waypoint two tiles away was "arrived at" without the character moving at all. The
             * waypoint that threads the air lock survives the trim now and was then skipped here
             * for exactly the reason it used to be deleted there - so the next leg still began from
             * the wrong side of the post, and the log still showed a leg reading "0t of 1t".
             *
             * A waypoint is a corner the route turns at. Passing three tiles wide of a corner is
             * not passing it. */
            boolean got = walkStraight(legs.get(i), last ? tol : LEG_SLACK);
            Coord2d ended = me();
            NLog.log(log, String.format("  leg %d/%d %s -> %s: %s at %s (%dt of %dt)",
                i + 1, legs.size(), GateManager.fmt(began), GateManager.fmt(legs.get(i)),
                got ? "arrived" : "stopped", GateManager.fmt(ended),
                (int) ((began == null || ended == null) ? -1
                    : began.dist(ended) / MCache.tilesz.x),
                (int) ((began == null) ? -1 : began.dist(legs.get(i)) / MCache.tilesz.x)));
            /* Arriving now MEANS arriving, so there is nothing left to correct for here.
             *
             * The re-plan that used to sit in this branch was a workaround for the loose tolerance
             * above, and with the tolerance right it is not merely redundant but harmful: a leg
             * inside the old three-tile tolerance returned without moving, the drift check saw it
             * standing a tile off, re-planned, got the identical route back, and did it again -
             * six times, until the bound stopped it. Re-planning cannot fix being in the wrong
             * place. Walking can. */
            if (got) {
                /* Arrived - but the route was certified between WAYPOINTS, and the next leg is
                 * walked from wherever THIS one actually stopped. Those are not the same line, and a
                 * tolerance's worth of drift is enough to make them different in the one place it
                 * matters most.
                 *
                 * A gateway is three tiles wide and `simplify` will happily put the turn on its EDGE
                 * tile. Arriving a tile to the side of that waypoint is well inside LEG_SLACK, so it
                 * counts as arrived and travel walks on - along a line that now misses the gap and
                 * crosses the WALL beside it. Nothing downstream can recover: `GateManager.onRoute` is
                 * asked about the line we are actually walking and answers, correctly, that no
                 * gateway is on it, so the local pathfinder is left to go round - which it does, the
                 * long way, and the WANDER guard catches it thirty-six tiles later pointing at a
                 * gateway on the far side of the base. From the log: leg 3 aimed at (-10521,-10455)
                 * and ended at (-10513,-10459), nine units east; leg 4 then ran from there and the
                 * bot wandered 36 tiles on a 7-tile leg.
                 *
                 * So ask the same question an arrival is supposed to have settled: is the rest of
                 * the route still walkable FROM HERE? Clear, and the drift cost nothing. Not clear,
                 * and the answer is NOT a new route - see below, that was tried and it loops - it is
                 * to finish walking to the waypoint we stopped a tile short of.
                 *
                 * This is also the angled-approach case - coming at a gateway from along the wall
                 * rather than square to it puts the line across the wall for the same reason. */
                if (last || !driftedIntoWall(legs, i))
                    continue;
                /* WALK THE REST OF THE WAY ONTO IT. Re-planning here cannot work, and the log says
                 * so in one unmistakable shape - seven of these, eight milliseconds apart:
                 *
                 *   leg 1/4 (-10512,-10456) -> (-10521,-10455): arrived at (-10512,-10456) (0t of 0t)
                 *   ...arrived, but from here the next leg cuts through a wall the route went round
                 *   re-planned ...: 3 waypoint(s) [(-10521,-10455) (-10521,-10378) (-10444,-10345)]
                 *
                 * The re-plan hands back the IDENTICAL route, and it is right to: keeping that
                 * waypoint is exactly what stops the next leg crossing the wall, which is why
                 * `plan` no longer trims it. Then travel walks the leg again, and `walkStraight`
                 * returns true without issuing a click because we are already inside LEG_SLACK of
                 * it. Nothing moves. Six goes at that, and then the wall-crossing leg is walked
                 * anyway: 36 tiles of wander on a 7-tile leg, out through the wrong gateway, and
                 * the whole trip back. 128 of these no-op arrivals in one log.
                 *
                 * The route was never wrong. We are nine units east of the mouth of a three-tile
                 * gap, at tile (1044,1149) when the waypoint is (1043,1149), and row 1150 is WALL
                 * at x=1044 and GATE at x=1043. One tile west is the entire fix, and one tile is
                 * inside the tolerance that says we have arrived. So close it - a waypoint whose
                 * job is to line us up is not passed by coming within a tile of it.
                 *
                 * ON_WAYPOINT and not LEG_SLACK only here, where the drift check has already shown
                 * that this particular tile matters. Demanding it of every waypoint is the dead
                 * band again - most of them cannot be stood on that exactly and do not need to be. */
                if (drifts < MAX_DRIFTS) {
                    drifts++;
                    NLog.log(log, "  ...arrived, but the next leg cuts through a wall from here and"
                        + " not from " + GateManager.fmt(legs.get(i)) + " - walking onto it");
                    walkStraight(legs.get(i), ON_WAYPOINT);
                    ended = me();
                    if (!driftedIntoWall(legs, i)) {
                        NLog.log(log, "  ...on it at " + GateManager.fmt(ended)
                            + " and the next leg is clear from here");
                        continue;
                    }
                }
                /* Could not get onto it, so this leg did NOT arrive - fall through and let it be
                 * handled as the failure it is. The gate check below is what actually rescues this,
                 * and in the log it does: the identical case at 09:29:52 spent its drift budget on
                 * no-op re-plans, and the moment the budget ran out `onRoute` found the shut gateway
                 * and the bot went through it. Getting there without wasting six re-plans first is
                 * the only difference. */
                NLog.log(log, "  ...could not get onto " + GateManager.fmt(legs.get(i))
                    + " and the next leg still cuts a wall - counting the leg as failed");
            }

            /* Not reaching a waypoint is not the same as the route having failed.
             *
             * A waypoint is asked for within a tile, deliberately, so that the character actually
             * goes to the corner instead of declaring three tiles away good enough. But some of
             * them cannot be stood on to within a tile: stepTo pulls its aim back off anything
             * solid, so a waypoint beside a stockpile is walked to and stopped at two tiles out,
             * every time, for ever - which is what the log filled with once the tolerance was
             * tightened, seven refused hops a leg and a leg that never ends.
             *
             * The question worth asking is not how far short it stopped, it is whether the REST of
             * the route still works from here - and that is a question about a line, which the
             * router can answer with the same test it used to place the waypoint in the first
             * place. Clear, and stopping short cost nothing: carry on. Not clear, and we are in the
             * case this tolerance exists for - beside a gateway, on the wrong side of a post - so
             * fall through to the gate check and the re-plan below.
             *
             * Bounded to LEG_TOL as well, so that "the line happens to be clear from here" cannot
             * excuse a leg that gave up a long way from where it was going. */
            if (!last && (ended != null) && (ended.dist(legs.get(i)) <= LEG_TOL)
                    && restIsWalkable(legs, i)) {
                NLog.log(log, "  ...stopped short, but the line on from here is clear - carrying on");
                continue;
            }

            /* A leg that stops short in front of a wall is nearly always a shut gateway: the
             * route was planned through the gap, correctly, and the gap happens to have a gate
             * in it that is solid until somebody opens it. Try that before deciding the route
             * was wrong, because re-planning cannot help - the router already thinks this is
             * the way, and it is right.
             *
             * Budgeted, because "went through a gate" is reported by getting to the far side
             * and not by making progress: a bot pushed back through, or one whose route keeps
             * choosing the same gateway, would otherwise walk through it for ever. Four is
             * more gates than any real journey between a work site and a barrel crosses. */
            /* The LEG, not the journey's end. A gateway is dealt with in order to get to the next
             * waypoint, and which side of it we want to come out on is decided by where that
             * waypoint is - not by where we are eventually going, which can be anywhere.
             *
             * Handing over the final destination put the bot back out through the gate it had just
             * come in by, repeatedly. Its route went north through an air lock's inner gate and
             * then round to a water place that happens to sit two tiles SOUTH of that gate's row,
             * so "which side is the destination on" answered south while "which side is the next
             * waypoint on" answered north. The step-through took the destination's answer, walked
             * the bot back into the chamber, and the whole thing began again. */
            Coord2d leg = legs.get(i);
            /* Count gateways PASSED, which is what the budget is for, not legs that failed.
             *
             * `gates++` in the left operand ran on every failed leg - whether a gateway was even
             * looked at, and whether using it worked - so a journey spent its whole gate budget on
             * failures and then walked the rest of the way with gate handling silently switched
             * off. In the logged run the budget was gone by the third gate operation, and the two
             * legs after it have no `gate:` line of any kind before the journey gives up. Failed
             * legs already have their own bound in MAX_REPLANS just below; this one exists to stop
             * a bot being pushed back and forth through the same gateway for ever. */
            if ((gates < MAX_GATES)
                && GateManager.pass(this, gui, leg, blockingGate, refusedGates, log)) {
                gates++;
                Observed.observe(gui);
                List<Coord2d> after = plan(dest);
                /* Logged like every other re-plan. This one used to be silent, and its silence is
                 * why a bot that stepped through a gate and then walked fifty tiles the wrong way
                 * looked like a gate fault: the route it was following afterwards never appeared
                 * anywhere. */
                NLog.log(log, "re-planned from the gateway, at " + GateManager.fmt(me()) + ": "
                    + ((after == null) ? "no route" : (after.size() + " waypoint(s) " + fmt(after))));
                legs = itinerary(after, dest);
                i = -1;
                continue;
            }
            /* Re-planning from the same spot tends to produce the same route, so an unbounded
             * retry is not a retry - it is a bot walking the same failing leg for ever, which
             * from outside looks like pacing back and forth near the destination. Give up on
             * routing after a few and let the straight walk have its go. */
            if (++replans > MAX_REPLANS) {
                NLog.log(log, "route to " + GateManager.fmt(dest) + " failed " + replans
                    + " times; giving up " + (int) shortfall(dest) + "u short");
                return finish(dest, tol, true,
                    "route failed " + replans + " times");
            }
            /* The route was wrong about something - almost always a wall learned since, or
             * one that was never in view when the map file recorded the tiles. Re-plan from
             * where we actually are and start the itinerary again. */
            Observed.observe(gui);
            List<Coord2d> again = plan(dest);
            NLog.log(log, "re-planned after a failed leg, from " + GateManager.fmt(me()) + ": "
                + ((again == null) ? "no route" : (again.size() + " waypoint(s) " + fmt(again))));
            legs = itinerary(again, dest);
            i = -1;
        }
        return finish(dest, tol, false, "could not reach " + GateManager.fmt(dest));
    }

    /**
     * A route as the list of places to actually walk to, which always ENDS AT THE DESTINATION.
     *
     * Appending the destination is not tidiness; it is what makes the rest of travel work. The
     * router's last waypoint is the centre of the block the destination sits in, so {@link #plan}
     * quite rightly drops it as redundant - and on any journey the router saw no reason to turn on,
     * that was the only waypoint there was. Travel was therefore handed an EMPTY list on the great
     * majority of trips, walked past the whole leg loop without entering it once, and finished on a
     * bare straight walk. Every gate attempt and every re-plan lives inside that loop, so neither
     * had ever run: the logs show "0 waypoint(s)" on every single trip and no gate line at all.
     *
     * With the destination as a leg in its own right there is exactly one path through travel, and
     * the last stretch of a journey gets the same gate handling as the middle of one - which is the
     * stretch that needs it most, since the gateway into a base is the last thing between a bot and
     * the barrel it was sent for.
     */
    private List<Coord2d> itinerary(List<Coord2d> waypoints, Coord2d dest) {
        List<Coord2d> out = new ArrayList<>();
        if (waypoints != null)
            out.addAll(waypoints);
        out.add(dest);
        return out;
    }

    /** Where we are, for logging. */
    private Coord2d me() {
        Gob p = player();
        return (p == null) ? null : p.rc;
    }

    /** A route as a readable list, for the log. */
    private static String fmt(List<Coord2d> route) {
        StringBuilder sb = new StringBuilder("[");
        for (Coord2d c : route) {
            if (sb.length() > 1)
                sb.append(' ');
            sb.append(GateManager.fmt(c));
        }
        return sb.append(']').toString();
    }

    /** How far we still are from a destination, for logging. */
    private double shortfall(Coord2d dest) {
        Gob me = player();
        return (me == null) ? -1 : me.rc.dist(dest);
    }

    /**
     * Whether we are close enough to call it arrival - AND on the same side of the wall as it.
     *
     * This was a bare distance, and a bare distance is not arrival. Tolerances here run from two
     * tiles for a drawn place up to four for a bookmarked spot, and a palisade is one tile thick,
     * so a bot stopped dead against the outside of a wall was entitled to report that it had got
     * to somewhere three tiles beyond it. Nothing downstream re-checks that: the caller is told the
     * journey succeeded and gets on with what it went there for, which for the work tasks means
     * right-clicking a gob - and a right-click is a SERVER walk, which goes in a straight line
     * through whatever is in the way. It is the same shape as the {@code walk.mc} bound in
     * {@link #approach}, which exists because that exact hand-off swam a character across a river.
     *
     * WALL and not solid, and only strictly BETWEEN the two points. Furniture is not a reason to
     * disown an arrival - stopping two tiles from a barrel because a crate is in the way is a
     * perfectly good arrival - and the ends have to be allowed to be against something, or standing
     * next to the palisade you were sent to would never count.
     *
     * It also answers the question the router already answers at its own level and nothing answered
     * at this one: something walled in on every side has no reachable neighbour, so
     * {@code Router.search} returns null for it - but travel then falls back to a straight walk, and
     * the straight walk finishing four tiles short on the wrong side used to be success.
     */
    private boolean arrived(Coord2d dest, double tol) {
        Gob me = player();
        if ((me == null) || (me.rc.dist(dest) > tol))
            return false;
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        if (here == null)
            return true;   // cannot tell, so do not invent a failure
        return !wallBetween(here.seg, here.sc, dest.add(here.sc.sub(me.rc)));
    }

    /**
     * The end-of-journey judgement: a {@link TravelResult} for whichever of arrived/blocked/failed
     * actually happened.
     *
     * Travel's ending returns all used to be the same boolean - "did {@link #arrived} hold?" - which
     * threw away why it didn't. The two non-arrival flavours are told apart here: a journey that was
     * walked to the end without getting there is {@link TravelResult#blocked} (something in the way,
     * worth retrying), while one that could never be routed is {@link TravelResult#failed} (routing
     * again is likely to produce the same nothing).
     *
     * @param permanent whether the reason is "routing could not find a way" (failed) rather than
     *                  "walked but did not arrive" (blocked)
     */
    private TravelResult finish(Coord2d dest, double tol, boolean permanent, String reason) {
        Coord2d here = me();
        if (arrived(dest, tol))
            return TravelResult.arrived(here);
        return permanent ? TravelResult.failed(here, reason) : TravelResult.blocked(here, reason);
    }

    /**
     * Whether the next leg of an itinerary is still walkable from where we are actually standing.
     *
     * The route's guarantee is about lines between waypoints; this asks the same question of the
     * line we are really on. True when there is no next leg, since then nothing is being promised.
     */
    /**
     * True if the next leg was drawn THROUGH a gateway and, from where we are actually standing, no
     * longer goes through it.
     *
     * The narrow form of "have we drifted off the route", and it has to be narrow. The obvious test -
     * is the rest of the route still walkable from here - fires constantly and wrongly: {@link
     * Router#along} sweeps the character's six-unit width at quarter-tile steps against eleven-unit
     * tiles, so a line drawn from the tile we are standing in rather than from the waypoint clips
     * corners the certified line missed. Anywhere near a wall - which is exactly where routes thread
     * gateways - one tile of difference flips the answer. Run as a general check it re-planned
     * seventeen times in a two-minute session, on arrivals as good as four units off, burning the
     * re-plan budget and turning working journeys into wandering ones.
     *
     * What actually breaks is specific and worth catching on its own. A gateway is three tiles wide
     * and {@code simplify} will put the turn on its EDGE tile, so a tolerance's worth of drift moves
     * the next leg's line off the gap and onto the WALL beside it - and then everything downstream is
     * asked about the line being walked and answers honestly that no gateway is on it, leaving the
     * local pathfinder to go round. Measured from the log: nine units of drift, thirty-six tiles of
     * wander on a seven-tile leg.
     *
     * So compare the two lines on the one thing that decides it: does the line we would actually
     * walk cross a WALL that the certified one did not? Replayed against botmap.json and the logged
     * coordinates, that fires on the real case - the drifted line meets wall tiles (1044,1150) and
     * (1044,1151), one column east of the gap - and stays silent on the long legs out in the open
     * that the general test was re-planning for no reason.
     *
     * WALL and not SOLID, deliberately. A palisade is a thing the local pathfinder cannot get past
     * and the router plans around; furniture it walks round by itself, and re-planning for every
     * barrel a line happens to clip is how the general form of this check went wrong.
     *
     * Testing for the GATEWAY instead does not work, and it is worth saying why so nobody tries it
     * again: the drifted line still clips one of the air lock's two gate rows, just not the one it
     * needs, so "was drawn through a gateway and no longer is" is false on the very case this
     * exists for.
     */
    private boolean driftedIntoWall(List<Coord2d> legs, int i) {
        if ((i + 1) >= legs.size())
            return false;
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        Gob me = player();
        if ((here == null) || (me == null))
            return false;   // cannot tell, so do not invent a detour
        Coord2d off = here.sc.sub(me.rc);
        Coord2d next = legs.get(i + 1).add(off);
        return crossesWall(here.seg, here.sc, next)
            && !crossesWall(here.seg, legs.get(i).add(off), next);
    }

    /**
     * How far along a heading a hop may aim before it would cross an impassable tile we have learned.
     *
     * Returns {@code span} unchanged when the line is clear. Checks WALL, SOLID, deep water, and shallow
     * water (when BLOCK_WATER is on). Only blocks on tiles the CLIENT CANNOT SEE - visible obstacles
     * are handled by the local pathfinder.
     *
     * A GATEWAY on the line forgives walls ONLY within 1 tile of the gateway posts (reduced from 2).
     * This prevents corner-post forgiveness from letting a line slip through a palisade corner.
     *
     * Stops a TILE short of the obstacle rather than against it.
     */
    private double clearSpan(Coord2d from, Coord2d dir, double span) {
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        Gob me = player();
        if ((here == null) || (me == null))
            return span;   // cannot tell, so do not invent an obstacle
        Coord2d off = here.sc.sub(me.rc);
        int steps = Math.max(1, (int) Math.ceil((span / MCache.tilesz.x) * 2));
        /* WHERE the gateway is on this line, not merely whether there is one. */
        double gateFrom = -1, gateTo = -1;
        for (int i = 0; i <= steps; i++) {
            double d = (span * i) / steps;
            Coord t = from.add(dir.mul(d)).add(off).floor(MCache.tilesz);
            if (Observed.gate(here.seg, t)) {
                if (gateFrom < 0)
                    gateFrom = d;
                gateTo = d;
            }
        }
        double postSlack = MCache.tilesz.x; // REDUCED: 1 tile (was 2) - only forgive actual gateway posts
        // From one sample in: the tile we are standing in is allowed to be against a wall.
        for (int i = 1; i <= steps; i++) {
            double d = (span * i) / steps;
            Coord2d p = from.add(dir.mul(d));
            Coord tile = p.add(off).floor(MCache.tilesz);
            byte obs = Observed.at(here.seg, tile);
            // Check ALL impassable types, not just WALL
            boolean isBlocking = (obs == Observed.WALL) || (obs == Observed.SOLID);
            // Check water via Terrain - look up the tile's water class from the map file
            Coord gc = Terrain.floorDiv(tile, MCache.cmaps);
            Coord in = tile.sub(gc.mul(MCache.cmaps));
            byte[] classes = Terrain.classes(gui, here.seg, gc);
            if (classes != null) {
                byte w = classes[(in.y * MCache.cmaps.x) + in.x];
                // BLOCKED (rock/cave/nil) belongs with DEEP, not with the optional water: a hop that
                // aims across a rock face is refused on arrival whatever the water setting says.
                if (w == Terrain.DEEP || w == Terrain.BLOCKED || (w == Terrain.SHALLOW && Map.BLOCK_WATER))
                    isBlocking = true;
            }
            if (!isBlocking)
                continue;
            // Gateway post forgiveness - ONLY for actual gateway tiles ± 1 tile
            if ((gateFrom >= 0) && (d >= (gateFrom - postSlack)) && (d <= (gateTo + postSlack)))
                continue;
            // Visible objects are handled by local PF - only block on UNKNOWN walls
            if (occupied(gui, p))
                continue;
            // Stop 1 tile before the obstacle
            return Math.max(0, d - MCache.tilesz.x);
        }
        return span;
    }

    /** Whether the straight line between two points in segment coordinates meets a wall tile. */
    private static boolean crossesWall(long seg, Coord2d from, Coord2d to) {
        return wallOn(seg, from, to, true);
    }

    /**
     * As {@link #crossesWall}, but allowing either end to be standing against one.
     *
     * The distinction is the difference between "this line goes through a wall" and "a wall is
     * between these two places". Route legs want the first, since a leg drawn onto a wall tile is
     * wrong however it got there; {@link #arrived} wants the second, because being up against a
     * palisade is where a lot of perfectly good destinations are.
     */
    private static boolean wallBetween(long seg, Coord2d from, Coord2d to) {
        return wallOn(seg, from, to, false);
    }

    private static boolean wallOn(long seg, Coord2d from, Coord2d to, boolean ends) {
        int steps = Math.max(1, (int) Math.ceil((from.dist(to) / MCache.tilesz.x) * 2));
        for (int i = (ends ? 0 : 1); i <= (ends ? steps : (steps - 1)); i++) {
            Coord t = from.add(to.sub(from).mul((double) i / steps)).floor(MCache.tilesz);
            if (Observed.at(seg, t) == Observed.WALL)
                return true;
        }
        return false;
    }

    private boolean restIsWalkable(List<Coord2d> legs, int i) {
        if ((i + 1) >= legs.size())
            return true;
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        Gob me = player();
        if ((here == null) || (me == null))
            return true;   // cannot tell, so do not invent a failure
        Coord2d off = here.sc.sub(me.rc);
        return Router.walkable(gui, here.seg, here.sc.floor(MCache.tilesz),
            legs.get(i + 1).add(off).floor(MCache.tilesz));
    }

    /**
     * Turns a destination into waypoints, or null if there is no useful route to plan.
     *
     * Null covers three cases that all want the same answer: the map file cannot place us yet, the
     * destination is in another segment, or the router found nothing. In each the caller should
     * simply walk at the target - which is what it did before any of this existed.
     */
    private List<Coord2d> plan(Coord2d dest) {
        Gob me = player();
        WorldAnchor here = WorldAnchor.capturePlayer(gui);
        WorldAnchor there = WorldAnchor.capture(gui, dest);
        /* The destination's own grid is usually NOT loaded, and that used to end the journey.
         *
         * capture() asks the map file which segment a point is in, which needs that point's grid
         * streamed in and written to gridinfo. For anywhere further off than the next screen it
         * simply is not, so this returned null, plan gave up, travel probed one hop and abandoned
         * the trip - permanently, for a condition that clears itself the moment we walk that way.
         * It is the single biggest thing in the logs: twenty-four give-ups in one session, the
         * cleanup bot idle for half an hour with nothing unreachable about any of its targets.
         *
         * We do not need the map file to answer this. We are standing in the same continuous
         * coordinate space as the destination, so offsetting from OUR anchor - whose grid is loaded
         * by definition - places it exactly. capture() still goes first, because it is the only one
         * that can tell us the target is in a different segment; this only fills in for the case
         * where the map file has not caught up yet. */
        if ((there == null) && (me != null) && (here != null))
            there = here.offsetTo(me.rc, dest);
        /* Said apart, because the two of them want quite different things done and the logs could
         * only ever say "no route" for both. This one is the map file not being able to place US,
         * which is nothing to do with the terrain; the router actually failing to find a way is the
         * one worth reading a log over, and it is reported below. */
        if ((me == null) || (here == null) || (there == null)) {
            NLog.log(log, "cannot plan to " + GateManager.fmt(dest)
                + ": the map file can't place us yet");
            return null;
        }
        if (here.seg != there.seg) {
            NLog.log(log, "cannot plan to " + GateManager.fmt(dest)
                + ": it is in map segment " + there.seg + " and we are in " + here.seg);
            return null;
        }
        Coord fromTile = here.sc.floor(MCache.tilesz), toTile = there.sc.floor(MCache.tilesz);
        /* Use the clamped router: UNSEEN ground is treated as impassable, so the route stays
         * within observed territory. If the destination is beyond the observed edge, the router
         * returns the path to the observed-tile nearest the destination. The caller (travelTo)
         * detects this and explores forward to extend observation before re-planning. */
        List<Coord> nodes = Router.routeClamped(gui, here.seg, fromTile, toTile);
        /* Track whether the route was clamped (the router could not reach the destination tile
         * through observed ground). Detect this by checking if the last tile differs from the
         * destination tile — a normal route always ends at toTile; a clamped fallback ends at the
         * closest reachable observed tile. */
        boolean clamped = (nodes != null) && !nodes.isEmpty()
            && !nodes.get(nodes.size() - 1).equals(toTile);
        if ((nodes == null) || clamped) {
            /* Clamped means "I cannot get there over ground I have looked at", and walking to the
             * nearest observed tile to the destination is only the right answer when the unlooked-at
             * ground is OPEN COUNTRY - somewhere another few steps of vision will resolve.
             *
             * Going INTO a walled base it is the wrong answer entirely, and this is the asymmetry
             * that made gates work leaving and fail arriving. From inside, the outside is already
             * observed, so the clamped route reaches the destination and crosses the gateway like
             * any other gap. From outside, the interior is unseen: the nearest observed tile to a
             * destination indoors is the OUTSIDE FACE OF THE WALL, so the route ends there, and
             * "explore forward to see more" means walking into the wall - which reveals nothing,
             * because what is behind it is only ever visible from the gateway. The gate is never
             * considered, and the bot ends up nosing at unrendered ground next to a wall.
             *
             * So when the clamped answer falls short, ask again with unseen ground treated as
             * passable-but-expensive. Walls stay solid in both modes and a gateway is passable in
             * both, so the optimistic search finds the one gap in the wall - the gate - and routes
             * through it, which is precisely the route a person would take.
             *
             * The optimism is the same bargain the rest of the router already makes: a route is a
             * hypothesis, each leg is re-checked by the local pathfinder on arrival, and a leg that
             * turns out to be wrong fails and re-plans against a record that has since been filled
             * in. Only the caller who needs a FINAL answer refuses to guess - see Router.reachable. */
            List<Coord> optimistic = Router.route(gui, here.seg, fromTile, toTile);
            if ((optimistic != null)
                && (optimistic.isEmpty() || optimistic.get(optimistic.size() - 1).equals(toTile))) {
                nodes = optimistic;
                clamped = false;
            }
        }
        planClamped = clamped;
        if (nodes == null) {
            planClamped = false;
            return null;
        }
        /* What the route is made of, not just where it goes. The interesting number is how much of
         * it crosses ground nobody has looked at and how far out that starts, because a route
         * through the unknown is a guess - and the reports of walking through palisades and across
         * rivers all describe it happening just past the edge of what was on screen, which is
         * exactly where the record stops and the guessing begins. */
        NLog.log(log, "  route " + Router.describe(gui, here.seg, fromTile, toTile, nodes)
            + (planClamped ? " [CLAMPED]" : ""));
        /* Segment coordinates back into live world ones. The player is in both spaces at once, so
         * the difference between the two readings of where WE are is the offset for everything
         * else - no second map-file lookup per waypoint. */
        Coord2d origin = me.rc.sub(here.sc);
        List<Coord2d> out = new ArrayList<>();
        for (Coord t : nodes)
            out.add(origin.add(t.mul(MCache.tilesz)).add(MCache.tilesz.div(2)));

        /* Drop any leading waypoint we are effectively already standing on.
         *
         * It used to drop every leading waypoint that was not CLOSER TO THE DESTINATION than we
         * are, which quietly threw away the whole point of routing. Going around a wall means
         * walking away from the destination first, so every waypoint of the detour failed that
         * test and was deleted - leaving a route that starts on the far side of the obstacle,
         * which the bot then walked straight at. That is the pacing back and forth in front of a
         * palisade: the router had found the way round and travel had just deleted it. */
        /* Only the one we are STANDING on, and this bound is load-bearing.
         *
         * It used to drop every leading waypoint within LEG_TOL - three tiles - which sounds like
         * the same thing and is not. The router emits a waypoint wherever the straight line stops
         * being clear, so waypoints are far apart in the open and packed tightly exactly where the
         * geometry is fiddly: threading an air lock produces three or four of them a tile apart.
         * All of those are within three tiles, so all of them were deleted, and a route that said
         * "west one tile, then south through the gap, then on" arrived at travel as one leg aimed
         * at a point on the far side of the wall.
         *
         * Which is then handed to the local pathfinder, whose job is to get to a point and which
         * will go round a palisade to do it. That is the forty-six tile detour on a seven tile leg
         * in the log, and the whole of "it goes the other way instead of through the gate beside
         * it": nothing had asked it to go through the gate, because the waypoint that would have
         * taken it there had been trimmed. */
        /* ...unless dropping it would aim the next leg through a WALL.
         *
         * This is the third form of the same bug. First the trim dropped waypoints for not being
         * closer to the destination; then it dropped everything within three tiles; and now it drops
         * the one waypoint that lines the bot up with a gateway, because the bot is standing a tile
         * short of it.
         *
         * The drift re-plan made the two faults compound into a loop. Drifting to nine units off that
         * waypoint is inside LEG_SLACK, so the arrival counts; the drift check correctly sees the next
         * leg would cut the wall and re-plans; and plan() then deletes the very waypoint it had
         * re-planned to reach. Replayed on the logged case: the route it handed back ran from tile
         * (1044,1149) straight to (1043,1156) across FIVE wall tiles, which is the 36-tile wander
         * being produced by the fix for the 36-tile wander.
         *
         * Testing for a gate TILE does not work here and it is worth saying why, because it is the
         * obvious guess and it is wrong: the waypoint that matters is (1043,1149), one tile NORTH of
         * the gateway and plain open ground. It is the alignment point, not the gap. So test the harm
         * directly - would the first leg cross a wall that keeping this waypoint avoids - which is the
         * same question {@link #driftedIntoWall} asks one layer up, and needs no guess about which
         * waypoints are special. Verified on the logged case: keeping it, leg one crosses nothing and
         * leg two runs down the gap through both gate rows clean. */
        while (!out.isEmpty() && (out.get(0).dist(me.rc) <= LEG_SLACK)) {
            Coord2d onward = (out.size() >= 2) ? out.get(1).sub(origin) : there.sc;
            if (crossesWall(here.seg, here.sc, onward)
                    && !crossesWall(here.seg, out.get(0).sub(origin), onward))
                break;
            out.remove(0);
        }
        /* Likewise the last one: {@link #itinerary} puts the destination itself on the end of
         * every route, so a waypoint already inside the final approach is that same walk done
         * twice. Dropping it here is safe only BECAUSE the destination is re-appended there - it
         * is not this method's job to leave something for travel to walk to. */
        while (!out.isEmpty() && (out.get(out.size() - 1).dist(dest) <= LEG_TOL))
            out.remove(out.size() - 1);
        /* Store the route so the travel loop can check each leg's corridor against live
         * keep-out circles before walking it. A beast or other character that drifts onto
         * the planned corridor between legs is invisible to the router and would otherwise
         * be walked into rather than routed around. */
        currentRoute = out;
        return out;
    }

    /**
     * How far a single hop should aim right now, from how much of the world the client actually has.
     *
     * The point is that a hop is only as good as the terrain it was planned against. The local
     * pathfinder builds its grid from LOADED objects, so aiming thirty tiles into ground that has
     * not streamed in yet plans a route around obstacles it cannot see, walks into them, and calls
     * that a stall. Aiming ten tiles when the whole valley is loaded is the opposite waste - a
     * pathfinder run per ten tiles, each one re-deriving what the last already knew.
     *
     * The furthest loaded object is the honest measure of both: it is set by the same view distance
     * that decides how much terrain the client asked the server for. Clamped at both ends, because
     * the measure degrades in the two obvious ways - an empty plain has no distant objects to
     * measure and would collapse the hop to nothing, and a very long view would ask for more than
     * MapView will actually click at.
     */
    private double hop() {
        Gob me = player();
        if (me == null)
            return HOP;
        double far = 0;
        try {
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob g : gui.ui.sess.glob.oc)
                    far = Math.max(far, me.rc.dist(g.rc));
            }
        } catch (RuntimeException e) {
            return HOP;
        }
        if (far <= 0)
            return HOP;
        return Math.max(HOP_MIN, Math.min(HOP_MAX, far));
    }

    /**
     * One short hop straight at a destination the router cannot yet place.
     *
     * Used only when {@link #plan} has nothing to say - usually because the destination is too far
     * for {@code WorldAnchor.capture} to anchor. The point of a single hop is the only thing this
     * helper is allowed to optimise for: it is short enough that the only obstacle it can fail on
     * is a wall, which is precisely the information {@link #plan} needs to draw a real route on
     * the next attempt. Anything longer is wall-following, which is the failure mode this method
     * exists to replace.
     *
     * Aims well short of the destination, so the hop cannot overshoot or arrive and is forced to
     * fail honestly - arriving at the destination with no router in the loop would let the
     * caller mark the journey done.
     *
     * @return true if the hop made progress; false if it could not or stopped short, in which
     *         case any wall in the way has been learned and the caller should re-plan.
     */
    private boolean hopToward(Coord2d dest) throws InterruptedException {
        Gob me = player();
        if ((me == null) || (dest == null))
            return false;
        double dist = me.rc.dist(dest);
        if (dist <= LEG_TOL)
            return true;
        /* Min hop, not max. A minimum hop is the smallest thing that can move the character, and
         * the smallest movement is the one most likely to fit in any pocket the loaded terrain
         * has left. Aim at half the distance so a successful hop halves the gap and cannot
         * succeed without reaching its own tolerance; if it can, the destination is genuinely
         * close enough to hand to walkStraight. */
        Coord2d dir = dest.sub(me.rc);
        double len = dir.abs();
        /* Half the distance, never more than HOP_MAX and never past the destination. The far
         * case matters more than the near one: hopToward exists because the router could not
         * anchor this destination, and an unclamped half of a far destination would be a blind
         * un-routed walk toward it - exactly what this method exists to avoid. Capping at HOP_MAX
         * keeps it one short hop no matter how far away the destination is. For a destination
         * closer than a minimum hop the unclamped aim landed PAST the destination - HOP_MIN is
         * twelve tiles - so walkStraight overran it by up to eleven tiles, which is the shape of
         * walking into the wall that sits just past a near destination. Clamping a near
         * destination to ninety percent keeps the aim short of it so the hop can only fail short
         * of it, never past it. */
        double aimLen = Math.min(len * 0.9, Math.min(HOP_MAX, Math.max(HOP_MIN, len * 0.5)));
        Coord2d aim = me.rc.add(dir.div(len).mul(aimLen));
        if (!walkStraight(aim, LEG_TOL))
            return false;
        Coord2d after = me();
        if (after == null)
            return false;
        /* Reaching the aim point is not the same as making headway - both stalls and detours end
         * there. A hop that did not move us closer is no better than one that did not run. */
        return after.dist(dest) < dist - MCache.tilesz.x;
    }

    /**
     * The old greedy walk, now used only between waypoints and as the fallback.
     *
     * Its blind sideways swings are still here on purpose: over the short, mostly-clear stretch
     * between two waypoints they handle the boulder or cart the router is too coarse to see, and
     * that is the job they were always suited to. What they could never do is choose which side of
     * a palisade to be on, which is why {@link #travelTo} no longer asks them to.
     */
    private boolean walkStraight(Coord2d dest, double tol) throws InterruptedException {
        if (dest == null)
            return false;
        double best = Double.MAX_VALUE;
        int stalled = 0;
        int refused = 0;
        int unsticks = 0;
        /* The two refusals that matter here, kept apart because they want opposite things done.
         * Lumping them together as "refused" is what stopped gates being opened at all: the
         * pathfinder saying "no way from here to there" is the strongest evidence available that
         * something is in the way, and it was being read as a reason NOT to look for a gate. */
        boolean wasStuck = false;
        boolean wasBlocked = false;

        Coord2d began = me();
        double leg = (began == null) ? 0 : began.dist(dest);

        for (int hop = 0; hop < 120; hop++) {
            Gob me = player();
            if (me == null)
                return false;
            double dist = me.rc.dist(dest);
            if (dist <= tol)
                return true;

            /* A leg that has taken us further from where it started than the leg is LONG is not a
             * leg being walked, it is a detour being taken, and it is not ours to take.
             *
             * The stall test above cannot catch this, because a detour is not a stall: every hop
             * genuinely closes on the target for a while. The local pathfinder is doing its job -
             * it was given a point on the far side of a wall and it goes round the wall, which is
             * what it is for. The log has it walking forty-six tiles west on a seven tile leg,
             * quietly, for thirty seconds.
             *
             * But a leg is supposed to be a short straight run the ROUTER has certified, so a
             * detour of that size means the leg is wrong and the route needs re-planning from
             * wherever we now are - which is a decision for travel, one layer up, and one it can
             * only make if this stops and says so. Judged from the start of the leg rather than by
             * distance travelled, so ordinary weaving around a tree does not trip it. */
            if ((began != null) && (began.dist(me.rc) > (leg * WANDER) + WANDER_SLACK)) {
                NLog.log(log, "walk wandered " + (int) (began.dist(me.rc) / MCache.tilesz.x)
                    + "t from the start of a " + (int) (leg / MCache.tilesz.x)
                    + "t leg to " + GateManager.fmt(dest) + " - that is a detour, not a leg");
                cancelWalk();
                return false;
            }

            if (dist < best - 5.0) {
                best = dist;
                stalled = 0;
                refused = 0;
            } else if (++stalled > TRAVEL_STALL_LIMIT) {
                NLog.log(log, "walk gave up " + (int) dist + "u short of " + GateManager.fmt(dest)
                    + " after " + stalled + " hops without progress");
                cancelWalk();
                return false;
            }

            /* Three things have to be true before the walk is abandoned for a gateway, and each
             * one is here because leaving it out broke something.
             *
             * We must have STALLED. The first version asked before walking at all, on the argument
             * that the local pathfinder goes round a shut gate rather than failing at it - true,
             * but it made the check overrule the route. The way past an air lock is usually beside
             * its side stubs, so the router quite properly goes round; the straight line to the
             * next waypoint still crosses the gate, and acting on that walked the bot twenty tiles
             * to a gateway nothing had asked for and shut it in the chamber. Having tried a hop and
             * got no nearer is the difference between a gate on the line and a gate in the way.
             *
             * It must be NEAR, so this is a gateway we have plainly arrived at rather than one
             * somewhere along the wall - and it must lie between us and the leg, which is what
             * {@link Gates#blocking} settles.
             *
             * One stalled hop is enough. The old budget of seven was spent wandering, and the
             * wandering carried the bot out of range of the very gate it needed. */
            /* Asked as soon as the search says there is no way, rather than only after a walk has
             * stalled. "No way from here to there" is the pathfinder having looked at the
             * eighty-eight tiles around us and found nothing - which, between a bot and somewhere
             * it has routed to, is nearly always a shut gate. Waiting for a stall instead spent
             * seven hops wandering first, and the wandering carried the bot out of range of the
             * gateway it needed.
             *
             * Still never when we are WEDGED, which is the distinction that matters: being unable
             * to move because of where we are standing says nothing whatever about what is between
             * us and the destination, and acting on it sends a bot off to open a gate it was
             * already on the right side of. */
            /* Before anything else: is this leg routed THROUGH a gateway that is shut? That does
             * not need a stall to establish and must not wait for one, because a stall never comes.
             * A shut gate is an ordinary solid to the local pathfinder, so it walks around it and
             * goes on making headway - up and down the inside of the wall, indefinitely, while the
             * test below waits for a stall that the pathfinder is busy preventing. */
            Gob routed = GateManager.onRoute(gui, me.rc, dest, refusedGates);
            if ((routed != null) && !wasStuck) {
                NLog.log(log, "the route to " + GateManager.fmt(dest) + " goes through shut gateway #"
                    + routed.id + " at " + GateManager.fmt(routed.rc) + " - opening it");
                blockingGate = routed.id;
                cancelWalk();
                return false;
            }
            if (((stalled > 0) || wasBlocked) && !wasStuck) {
                Gob shut = GateManager.blocking(gui, dest, refusedGates);
                if (shut != null) {
                    NLog.log(log, "no headway towards " + GateManager.fmt(dest) + " and shut gateway #"
                        + shut.id + " at " + GateManager.fmt(shut.rc) + " is in the way - opening it");
                    blockingGate = shut.id;
                    cancelWalk();
                    return false;
                }
            }

            Coord2d dir = dest.sub(me.rc);
            double len = dir.abs();
            if (len < 1.0)
                return true;
            dir = dir.div(len);

            // Re-measured per hop rather than per walk: what is loaded changes as we move, and a
            // hop planned against a view that has since opened up is leaving distance on the table.
            double reach = hop();
            /* Never aim past the destination.
             *
             * A hop of at least half the reach was once forced here on the reasoning that tiny
             * hops are wasteful. That overshot marked areas, so it was cut back to the distance
             * remaining - except for detours, which kept a twelve-tile floor on the argument that
             * a detour is aimed elsewhere and so has no destination to overshoot. That argument is
             * wrong. The destination is still there; a detour is meant to get around something in
             * the way of it, not to leave.
             *
             * What it produced: a bot eight tiles from its waypoint, stalled, aiming twelve tiles
             * off at a rotating angle - which cannot get closer, so it stalls again, and the angle
             * widens. The log has it stepping through its own gate and then travelling FIFTY-ONE
             * tiles east of a waypoint eight tiles away. Seven hops of twelve tiles is eighty-four
             * tiles of wander, and "gave up 924u short" is the same thing having wandered first.
             *
             * Bounding it by the distance remaining was the first attempt at that and is a
             * runaway: a detour that drifts makes the target further away, which raises the bound,
             * which makes the next drift longer. The log has the whole progression - a waypoint a
             * hundred and seventeen units off, then hops of 117, 215, 395, and four more at the
             * full reach, ending a hundred and thirty-two tiles away and "1521u short".
             *
             * The swings themselves are gone now, which is the proper end of that story. They were
             * a blind wall-follow standing in for a router that could not see anything smaller
             * than four tiles; the router works at tile resolution over everything the character
             * has seen, so going around things is its job and it is equipped for it. A hop that
             * gets nowhere now fails its leg, and travel re-plans - against a world model that the
             * tick has been updating all along, so the thing that stopped us has been recorded by
             * the time the new route is drawn. Re-planning finally produces a different answer,
             * which is what makes giving up on the swings possible. */
            double span = Math.min(len, reach);
            /* NOBODY WAS CHECKING THE LINE BEFORE CLICKING.
             *
             * `stepTo` tests that the AIM POINT is not inside a collision box and then hands the
             * click to the client pathfinder. Both of those only know about LOADED gobs, so a hop
             * aimed twelve tiles into ground the server has not sent objects for is planned against
             * nothing, and server movement is linear: the character walks into the palisade. A wall
             * is one tile thick and the ground either side of it is perfectly standable, so a point
             * test cannot tell the two sides apart - it says yes on both.
             *
             * The wall was in `Observed` the whole time. `Walk.lineClear` reads it, but only as the
             * FALLBACK after the client has already refused, which is precisely the case where the
             * client could see the obstacle. Where it could not see it there is no refusal, so
             * nothing ever asked. That is "it paths through solid objects just outside its render
             * range", and it is also why it does it repeatedly in the same place: each re-plan is
             * made from a spot where the wall is again out of view, so each one looks new.
             *
             * So shorten the hop to the last point before the wall rather than aiming past it. The
             * shortened hop still makes progress, and the leg failing at the wall is what gets the
             * gate layer and the router involved - both of which can do something about it. */
            double open = clearSpan(me.rc, dir, span);
            if (open < span) {
                if (open < MCache.tilesz.x) {
                    /* An obstruction AT OUR FEET is not a reason to stand still - it is the one
                     * case where standing still is fatal, because the step that would take us clear
                     * of it is refused along with every other. The bot deadlocked in exactly this
                     * shape: parked against some potter's wheels and chests with the whole base open
                     * around it, "runs into a wall we have learned, 0u out", re-plan, identical
                     * route, same refusal, for ever. Same story as a bot wedged on the edge of a
                     * rock.
                     *
                     * And it is the case where this check has least right to an opinion. The check
                     * exists for obstacles OUTSIDE render, which the client pathfinder cannot see
                     * and would walk straight into; something touching us is loaded by definition,
                     * so the pathfinder can see it, route around it, and refuse if it truly cannot -
                     * and a refusal is already handled, with evidence, further down. Vetoing here
                     * overrules the one party that can actually deal with it.
                     *
                     * So take the hop and let the client have its say. Walking into it costs a
                     * failed leg and a re-plan from somewhere new, which is progress; refusing costs
                     * the entire journey and always will.
                     *
                     * A SHORT hop, though, not the whole span, and that distinction was missing.
                     * "Let the client route around it" was written for one thing at our feet with
                     * open ground beyond, and it kept the full thirty-six tile aim - so inside an
                     * orchard, where there is a trunk against us and another every two tiles all the
                     * way to the horizon, it aimed the entire span through the lot of them. Give it
                     * a target a few tiles out instead: far enough to get off whatever we are
                     * touching, near enough that the client is being asked to go round ONE thing it
                     * can see rather than to cross a wood in a straight line. */
                    span = Math.min(span, STEP_OFF);
                    NLog.log(log, "something we have learned is right against us on the way to "
                        + GateManager.fmt(dest) + " (" + (int) open + "u out) - stepping off "
                        + (int) (span / MCache.tilesz.x) + "t anyway"
                        + " and letting the pathfinder route around it");
                } else {
                    span = open;
                }
            }
            Coord2d aim = me.rc.add(dir.mul(span));
            /* A hop that IS the whole leg cannot be allowed a looser standard than the leg.
             *
             * `stepTo` stops waiting at two tiles. The loop above accepts the leg at `tol`, which
             * for an intermediate leg is LEG_SLACK - one tile. So when the aim is the leg's own
             * destination, the hop reports itself arrived at two tiles, the loop re-measures
             * against one, and hops again from where it already stands: seven hops in twenty
             * milliseconds, no movement, leg failed. Anything left between one and two tiles is
             * unreachable by construction.
             *
             * The log carries the fingerprint either side of the change that made LEG_SLACK the
             * intermediate tolerance. Before it, with a three-tile leg tolerance, 155 give-ups and
             * NOT ONE between one and two tiles - three tiles is looser than the hop's two, so the
             * band did not exist. After it, 57 give-ups and 78% of them inside that band, the
             * smallest exactly eleven units. That is not a stall, a wall, or a refused click, and
             * chasing it as any of those is what cost the last three rounds.
             *
             * A partial hop keeps the two tiles: its aim is a point along the way, not the leg's
             * end, so arriving near it is all that is being asked. */
            /* Tighter than the leg's own tolerance, and that is the whole point.
             *
             * This tolerance does not decide where the click goes - the click already aims at the
             * point - it decides when to STOP WAITING for it. Set equal to the leg tolerance, as it
             * was, the wait ends the instant the character is one tile out and still walking; the
             * loop above then re-measures against the same tile, agrees, and the leg is declared
             * arrived from wherever the character happened to be at that moment. Measured over the
             * log: 360 of 363 intermediate legs finished eight units or more from the waypoint they
             * aimed at, with a hard wall at eleven. NOT ONE ever reached its waypoint.
             *
             * That is the systematic form of the drift the wall check downstream keeps catching. A
             * route is a chain of lines the router certified BETWEEN waypoints, so a travel that
             * stops a tile short of every one of them walks every leg after the first along a line
             * nobody validated - and one tile is the difference between the gap and the wall.
             *
             * There is no dead band this way round, which is what the equality was there to avoid:
             * this is now STRICTLY TIGHTER than what the loop accepts, so anything the wait gives up
             * on is still accepted a moment later by the loop. A waypoint that genuinely cannot be
             * stood on - beside a stockpile, where `stepTo` pulls its aim back off the box - behaves
             * exactly as it did before, because the wait was never what stopped the character. */
            stepTo(aim, (span < len) ? (TILE * 2.0) : Math.min(ON_WAYPOINT, tol));
            wasBlocked = stepRefused && (stepRefusal == Pathfinder.Refusal.NO_ROUTE);
            /* Anything that is not the search having looked and found nothing is treated as being
             * wedged, including a click that never reached a search at all - because the recovery
             * for all of those is the same, and because being wrong in this direction costs one
             * sidestep while being wrong in the other costs an unwanted trip to a gate. */
            wasStuck = stepRefused && !wasBlocked;
            /* If a beast's keep-out circle now blocks the remaining leg corridor, stop and let
             * travel re-plan. The local pathfinder handled this hop by going around the beast,
             * but the router's subsequent legs may now be invalid - the route was certified
             * before the beast moved into it, and the router does not consult keep-out circles.
             * Re-planning from here gives the router a fresh chance to route around the new
             * position. */
            if (ringedOff(me.rc, dest)) {
                NLog.log(log, "keep-out circle blocks remaining leg to " + GateManager.fmt(dest)
                    + " from " + GateManager.fmt(me.rc) + " - will re-plan");
                cancelWalk();
                return false;
            }
            /* Refused over and over means the aim really is somewhere we cannot be, and no amount
             * of swinging sideways from the same spot will change that. Say so rather than burning
             * the hop budget wandering, which is the shape this failure used to take. */
            if (wasStuck && (++refused > REFUSE_LIMIT)) {
                /* WEDGED, not blocked, and the difference decides what to do about it. Nothing
                 * will be walkable from here for as long as we are standing where we are - the
                 * commonest cause is our own position being inside a collision box, which refuses
                 * every path including the one back out - so the answer is to move first and judge
                 * afterwards. Bounded, because if stepping clear does not help twice over then it
                 * is not what was wrong. */
                if ((++unsticks <= UNSTICK_LIMIT) && Walk.unstick(this, gui, dest)) {
                    NLog.log(log, "wedged " + (int) dist + "u short of " + GateManager.fmt(dest)
                        + " with every click refused - stepped clear and carrying on");
                    refused = 0;
                    best = Double.MAX_VALUE;
                    stalled = 0;
                    wasStuck = false;
                    continue;
                }
                NLog.log(log, "nothing walkable at " + GateManager.fmt(aim) + " on the way to "
                    + GateManager.fmt(dest) + " - " + refused + " clicks refused, "
                    + (int) dist + "u short");
                cancelWalk();
                return false;
            }
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
     * @return a {@link TravelResult} describing how the journey ended.
     */
    public TravelResult travelTo(WorldAnchor anchor, double tol) throws InterruptedException {
        if (anchor == null)
            return TravelResult.failed(null, "null anchor");
        for (int hop = 0; hop < 120; hop++) {
            Coord2d dest = anchor.resolve(gui);
            if (dest == null) {
                NLog.log(log, "cannot resolve " + anchor + " - different map segment?");
                return TravelResult.failed(me(), "cannot resolve " + anchor);
            }
            Gob me = player();
            if (me == null)
                return TravelResult.failed(null, "player is unavailable");
            double dist = me.rc.dist(dest);
            if (dist <= tol)
                return TravelResult.arrived(me.rc);
            // Inside one hop of the target: hand the rest to travelTo, which owns the stall and
            // detour bookkeeping, and let it finish the job.
            if (dist <= HOP)
                return travelTo(dest, tol);
            TravelResult r = travelTo(dest, HOP * 0.75);
            if (!r.isArrived())
                return r;
        }
        return TravelResult.failed(me(), "gave up after 120 hops");
    }
}
