package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Resource;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.pathfinder.Map;
import haven.automated.pathfinder.Pathfinder;
import haven.automated.pathfinder.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walking, for bots. One place for the things every bot in this package needs to do with the
 * pathfinder, so the four of them can't drift into four subtly different notions of "get there".
 *
 * This facade owns the shared state and the bot-facing API; the machinery lives behind the
 * movement seam, split into the classes this constructor wires up:
 *
 * - {@link MovementCommand}: the waiting primitives and geometry ({@link #player}, {@link #pause},
 *   {@link #faceGap}, {@link #bulk}) - pure, holds no journey state.
 * - {@link Approach}: keep-out circles and {@link #approach}, walking up to a gob that may be
 *   MOVING, re-issuing the path whenever the target drifts. Intercepts rather than follows.
 * - {@link Travel}: {@link #travelTo}, the journey in hops with the router in the loop.
 * - {@link Walkers}: {@link #stepTo} and the record-reading passability it shares with the
 *   planner - what stands where, what the record says about a line, the greedy leg walk.
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

    /**
     * One tile, in world units.
     *
     * Every distance in this class is really a count of tiles, because tiles are the unit the
     * things being reasoned about are measured in: a palisade is one tile thick, a gateway three
     * tiles wide, and {@link Observed} records the world a tile at a time. They were spelt
     * {@code 11 * n}, which reads as arithmetic rather than as the tile count it is.
     *
     * Taken from the {@link World} seam rather than written out again, since the rest of this file
     * already divides by it to report distances in tiles - two spellings of one quantity is the
     * drift this is here to stop.
     */
    static final double TILE = World.TILE;

    /** Close enough to right-click a gob and have its menu open. About two tiles. */
    public static final double REACH = TILE * 2.0;
    /** How far a target may drift from where we aimed before re-pathing is worth it. */
    static final double DRIFT = TILE * 1.0;
    /** Give up chasing after this many re-paths that don't close the distance. */
    static final int NO_PROGRESS_LIMIT = 10;
    /** How many times one approach may stop to back out of a beast's ring before giving up. */
    static final int RETREAT_LIMIT = 3;
    static final double RETREAT_MARGIN = 30.0;

    /**
     * How far one travel hop aims when there is nothing better to go on.
     *
     * Also the figure used for the coarse "is this within one hop" tests, which want a fixed answer
     * rather than one that changes with what happens to be loaded.
     */
    static final double HOP = TILE * 25.0;
    /**
     * The furthest a hop may ever aim.
     *
     * MapView clamps any pathfinder click beyond forty tiles back to the edge of that circle
     * ({@code MAX_TILE_RANGE}), so asking for more does not fail - it silently lands somewhere
     * else, which is worse. Four tiles of margin keeps the hop honestly inside the clamp.
     */
    static final double HOP_MAX = TILE * 36.0;
    /**
     * The shortest a hop may aim. Below this the re-planning overhead per hop outweighs the
     * accuracy, and a bot in an empty field would inch along a tile at a time.
     */
    static final double HOP_MIN = TILE * 12.0;
    /**
     * How near a waypoint counts as reached.
     *
     * Router waypoints are the middle of a block of tiles, not a place anything needs to be stood
     * on, so insisting on arriving exactly would spend a pathfinder run per waypoint correcting a
     * few units that the next leg is about to undo anyway.
     */
    static final double LEG_TOL = TILE * 3.0;
    /** How many times a failed leg is worth re-routing before falling back to walking at it. */
    static final int MAX_REPLANS = 3;

    /**
     * How far off a waypoint a leg may finish before the rest of the route stops being trustworthy.
     *
     * One tile. {@link #LEG_TOL} is deliberately looser - a waypoint is a corner to pass, not a
     * place to stand - but the router only ever checked the lines between the waypoints themselves,
     * so anything further off than this and the next leg is a line nobody has examined.
     */
    static final double LEG_SLACK = TILE * 1.0;

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
    static final double ON_WAYPOINT = TILE * 0.34;

    /**
     * How far from a leg's starting point the walk may get, as a multiple of the leg's own length,
     * before it is a detour rather than a leg.
     *
     * One and a half, plus a few tiles of slack so that short legs - where going round a single
     * cart is a large fraction of the distance - are not tripped by ordinary weaving.
     */
    static final double WANDER = 1.5;
    static final double WANDER_SLACK = TILE * 6.0;
    /** How many gateways one journey may go through before that stops being plausible. */
    static final int MAX_GATES = 4;

    /**
     * How many times one journey may go back and finish walking onto a waypoint it drifted off.
     *
     * Separate from {@link #MAX_REPLANS} and larger, because these are corrections rather than
     * retries - see the counter's own comment in {@code travelTo}. Bounded at all only so that a
     * waypoint which cannot be stood on cannot be walked at for ever.
     */
    static final int MAX_DRIFTS = 6;
    /**
     * How many exploration hops toward a destination the clamped router can see but not reach.
     *
     * Each hop extends the observed region by up to {@link #HOP_MAX} tiles. Twelve is enough for
     * ~400 tiles of unknown ground - far more than any base-to-base journey. Bounded so that a
     * genuinely unreachable destination doesn't trap the bot in an endless explore loop.
     */
    static final int MAX_EXPLORE = 12;
    /** Travel gives up after this many hops that don't get closer. */
    static final int TRAVEL_STALL_LIMIT = 6;
    /**
     * Slop allowed on top of a target's reach and its own bulk before a stopped walk stops
     * counting as arrival.
     *
     * One tile. The honest bound is reach plus the thing's own size, since that is where the
     * pathfinder stops; this only covers it halting a step early. Anything more generous starts
     * excusing real failures - the log had a barrel filled from forty units away with a reach of
     * twenty-two, and two tiles of slop would still have called that an arrival.
     */
    static final double STOP_SLACK = TILE * 1.0;
    /** How far back off an occupied spot to look for standable ground, and in what steps. */
    static final double CLEAR_STEP = TILE * 0.5;
    static final double CLEAR_MAX = TILE * 3.0;
    /**
     * How far to aim when the only thing we know is that something is touching us.
     *
     * Three tiles: past whatever we are against, and short enough that the client is being asked to
     * go round one obstacle it can see rather than to hold a straight line across a wood.
     */
    static final double STEP_OFF = TILE * 3.0;
    /** How many refused clicks in a row mean the destination is not somewhere we can be. */
    static final int REFUSE_LIMIT = 3;
    /**
     * How many times one walk may step clear of being wedged before that stops being the problem.
     *
     * Two. Once is the ordinary case - backed up against a gate, a barrel, a cart - and a second
     * covers stepping out of one thing into another. A third would be a bot shuffling around a
     * yard rather than one recovering from a corner.
     */
    static final int UNSTICK_LIMIT = 2;

    final GameUI gui;
    final Abort abort;
    private final String log;
    private final MovementCommand cmd;
    final Approach appr;
    private final Travel trav;
    private final Walkers wlk;

    /**
     * Gateways this journey has already tried and got nothing from.
     *
     * Cleared per journey rather than kept: a gate that would not open because somebody was
     * standing in it is worth another go on the next trip, and a gate that is genuinely locked
     * costs one attempt to find that out again. What it must not do is cost one attempt per
     * re-plan, which without this it does - the scoring is deterministic, so the gate that just
     * failed is the same gate the next re-plan picks.
     */
    final Set<Long> refusedGates = new HashSet<>();

    /**
     * The gateway {@link #walkStraight} stopped for, handed to {@link Gates#pass} rather than left
     * for it to work out again. Zero when the leg failed for some other reason.
     */
    long blockingGate = 0;

    /** The last point we printed the full evidence for, so a re-plan doesn't print it again. */
    Coord2d whined = null;

    /**
     * Set when the last {@link #stepTo} click never became a walk at all.
     *
     * The distinction travel could not previously make, and the one that matters most: "we walked
     * and got no nearer" is evidence about walls, and "the pathfinder would not accept the click"
     * is evidence about the click. Reading the second as the first is what sent a bot standing
     * inside its own base out through a gateway to reach a barrel twelve tiles away.
     */
    boolean stepRefused = false;

    /** Which way the last {@link #stepTo} was refused, or null if it was not. */
    Pathfinder.Refusal stepRefusal = null;

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
    boolean planClamped = false;

    /**
     * The active route waypoints from the last {@link #plan}, stored so corridor checks can
     * compare keep-out circles against the full planned path, not just the current leg.
     *
     * Updated by {@link #plan} each time a route is computed. Read by {@link #checkPathBlocked}
     * to decide whether a moving entity has drifted onto the route between legs.
     */
    List<Coord2d> currentRoute = null;

    /**
     * Set when an approach was abandoned because of wildlife rather than because the target can't
     * be reached. Callers use the distinction to decide whether to give up on a target for good or
     * only for a while - a bear moves, a cliff doesn't.
     */
    public boolean hazardBlocked = false;

    public BotNav(GameUI gui, Abort abort, String log) {
        this(gui, abort, log, Approach::defaultKeepouts);
    }

    public BotNav(GameUI gui, Abort abort, String log, Approach.KeepoutSource keepouts) {
        this.gui = gui;
        this.abort = abort;
        this.log = log;
        this.cmd = new MovementCommand(gui, abort);
        this.appr = new Approach(this, log, keepouts);
        this.trav = new Travel(this, log);
        this.wlk = new Walkers(this, log);
    }

    // ------------------------------------------------------------------ waiting

    /**
     * Sleep-polling wait. Checks first (so an already-true condition costs nothing), then every
     * POLL_MS up to maxTicks. Throws as soon as the bot is stopped, which is what makes Stop feel
     * immediate rather than "after the current wait".
     */
    public void waitUntil(Cond cond, int maxTicks) throws InterruptedException {
        cmd.waitUntil(cond, maxTicks);
    }

    /** Plain delay that still honours Stop. */
    public void pause(int ticks) throws InterruptedException {
        cmd.pause(ticks);
    }

    // ------------------------------------------------------------------ state

    public Gob player() {
        return cmd.player();
    }

    public Gob gob(long id) {
        return cmd.gob(id);
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
        return cmd.walking();
    }

    /** Move-to-self: the standard way to interrupt a repeating in-place action. */
    public void stopAction() {
        cmd.stopAction();
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
        cmd.cancelWalk();
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
        return MovementCommand.faceGap(g, from);
    }

    public static double bulk(Gob g) {
        return MovementCommand.bulk(g);
    }

    // ------------------------------------------------------------------ approach

    /**
     * Walks to within {@code reach} of a gob, re-pathing as either of us moves, routing around
     * wildlife and other characters.
     *
     * @return true if we ended up close enough to act on it.
     */
    public boolean approach(Gob gob, double reach) throws InterruptedException {
        return appr.approach(gob, reach);
    }

    /**
     * True if a keep-out circle now intersects the corridor from {@code from} to {@code to}.
     *
     * Called proactively at the start of each leg in {@link #travelTo} to catch a beast or
     * character that has drifted onto the planned path between legs. The reactive check inside
     * {@link #walkStraight} catches one that steps onto the leg DURING the walk; this catches
     * one already in place when the leg begins, which is the case the user reported.
     *
     * @param from the current position
     * @param to   the leg destination (next waypoint, or the final destination on the last leg)
     * @return true if any keep-out circle intersects the segment
     */
    boolean checkPathBlocked(Coord2d from, Coord2d to) {
        return appr.checkPathBlocked(from, to);
    }

    /** Drops every keep-out. Must be called on every exit path - see {@link #approach}. */
    public void clearKeepouts() {
        appr.clearKeepouts();
    }

    // ------------------------------------------------------------------ point travel

/**
 * Walks to a fixed point that is already inside the pathfinder's window.
 *
 * @see Walkers#stepTo(Coord2d, double)
 */
public boolean stepTo(Coord2d dest, double tol) throws InterruptedException {
    return wlk.stepTo(dest, tol);
}

/**
 * True if a live world point is inside something solid.
 *
 * @see Walkers#occupied(GameUI, Coord2d)
 */
public static boolean occupied(GameUI gui, Coord2d wc) {
    return Walkers.occupied(gui, wc);
}

/** Whether the straight line between two points in segment coordinates meets a wall tile. */
static boolean crossesWall(Router.World w, Coord2d from, Coord2d to) {
    return Walkers.crossesWall(w, from, to);
}

/**
 * As {@link #crossesWall}, but allowing either end to be standing against one.
 *
 * @see Walkers#wallBetween(Router.World, Coord2d, Coord2d)
 */
static boolean wallBetween(Router.World w, Coord2d from, Coord2d to) {
    return Walkers.wallBetween(w, from, to);
}

boolean restIsWalkable(List<Coord2d> legs, int i) {
    return wlk.restIsWalkable(legs, i);
}

/**
 * Turns a destination into waypoints, or null if there is no useful route to plan.
 *
 * @see Walkers#plan(Coord2d)
 */
List<Coord2d> plan(Coord2d dest) {
    return wlk.plan(dest);
}

/**
 * One short hop straight at a destination the router cannot yet place.
 *
 * @see Walkers#hopToward(Coord2d)
 */
boolean hopToward(Coord2d dest) throws InterruptedException {
    return wlk.hopToward(dest);
}

/**
 * The old greedy walk, now used only between waypoints and as the fallback.
 *
 * @see Walkers#walkStraight(Coord2d, double)
 */
boolean walkStraight(Coord2d dest, double tol) throws InterruptedException {
    return wlk.walkStraight(dest, tol);
}









    /**
     * Walks to a point however far away it is, in hops.
     *
     * @see Travel#travelTo(Coord2d, double)
     */
    public TravelResult travelTo(Coord2d dest, double tol) throws InterruptedException {
        return trav.travelTo(dest, tol);
    }


















    /**
     * Travels to a segment-anchored position, re-resolving it every hop.
     *
     * @see Travel#travelTo(WorldAnchor, double)
     */
    public TravelResult travelTo(WorldAnchor anchor, double tol) throws InterruptedException {
        return trav.travelTo(anchor, tol);
    }

}
