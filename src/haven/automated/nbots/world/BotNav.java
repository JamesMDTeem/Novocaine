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
     * How far one travel hop aims when there is nothing better to go on.
     *
     * Also the figure used for the coarse "is this within one hop" tests, which want a fixed answer
     * rather than one that changes with what happens to be loaded.
     */
    private static final double HOP = 11 * 25.0;
    /**
     * The furthest a hop may ever aim.
     *
     * MapView clamps any pathfinder click beyond forty tiles back to the edge of that circle
     * ({@code MAX_TILE_RANGE}), so asking for more does not fail - it silently lands somewhere
     * else, which is worse. Four tiles of margin keeps the hop honestly inside the clamp.
     */
    private static final double HOP_MAX = 11 * 36.0;
    /**
     * The shortest a hop may aim. Below this the re-planning overhead per hop outweighs the
     * accuracy, and a bot in an empty field would inch along a tile at a time.
     */
    private static final double HOP_MIN = 11 * 12.0;
    /**
     * How near a waypoint counts as reached.
     *
     * Router waypoints are the middle of a block of tiles, not a place anything needs to be stood
     * on, so insisting on arriving exactly would spend a pathfinder run per waypoint correcting a
     * few units that the next leg is about to undo anyway.
     */
    private static final double LEG_TOL = 11 * 3.0;
    /** How many times a failed leg is worth re-routing before falling back to walking at it. */
    private static final int MAX_REPLANS = 3;
    /** How many gateways one journey may go through before that stops being plausible. */
    private static final int MAX_GATES = 4;
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
    private static final double STOP_SLACK = 11 * 1.0;
    /** How far back off an occupied spot to look for standable ground, and in what steps. */
    private static final double CLEAR_STEP = 11 * 0.5;
    private static final double CLEAR_MAX = 11 * 3.0;
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

    /**
     * Set when the last {@link #stepTo} click never became a walk at all.
     *
     * The distinction travel could not previously make, and the one that matters most: "we walked
     * and got no nearer" is evidence about walls, and "the pathfinder would not accept the click"
     * is evidence about the click. Reading the second as the first is what sent a bot standing
     * inside its own base out through a gateway to reach a barrel twelve tiles away.
     */
    /** The last point we printed the full evidence for, so a re-plan doesn't print it again. */
    private Coord2d whined = null;

    private boolean stepRefused = false;

    /** Which way the last {@link #stepTo} was refused, or null if it was not. */
    private Pathfinder.Refusal stepRefusal = null;

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
        try {
            Coord2d aim = standable(me.rc, dest);
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
            } else {
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
            }
        } finally {
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
         * swim. Keep-outs are dropped before this runs, deliberately: they exist to shape the
         * pathfinder's search, whereas {@link Walk#lineClear} checks hazards along the line
         * itself, and a bot standing inside a ring must still be able to walk out of it. */
        if (stepRefused) {
            if (Walk.lineClear(gui, me.rc, dest)) {
                NLog.log(log, "pathfinder refused " + Gates.fmt(dest) + " (" + why
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
                NLog.log(log, "pathfinder refused " + Gates.fmt(dest) + " (" + why
                    + ") and the straight line is not clear either"
                    + (fresh ? "" : " [same point]"));
                if (fresh) {
                    whined = dest;
                    NLog.log(log, "  destination: " + Probe.explain(gui, dest));
                    NLog.log(log, "  the way there: " + Probe.line(gui, me.rc, dest));
                    NLog.log(log, Probe.map(gui, dest, 12));
                }
            }
        }
        Gob now = player();
        return now != null && now.rc.dist(dest) <= tol;
    }

    /**
     * The nearest point to {@code aim} on the line back towards {@code from} that is not inside
     * something solid, or {@code aim} itself when it already is not.
     *
     * Backwards along the line rather than in any free direction on purpose: pulling towards the
     * caller shortens the walk without changing where it was going, so the arrival test the caller
     * is about to apply still means what it meant. Bounded at {@link #CLEAR_MAX}, since a
     * destination buried three tiles deep in solid ground is not a destination that was nudged
     * wrong - it is one the caller should hear about, which it does through {@link #stepRefused}.
     */
    private Coord2d standable(Coord2d from, Coord2d aim) {
        List<Gob> gobs = solids(gui);
        if (!inside(gobs, aim))
            return aim;
        Coord2d back = from.sub(aim);
        double len = back.abs();
        if (len < 1.0)
            return aim;
        back = back.div(len);
        for (double d = CLEAR_STEP; (d <= CLEAR_MAX) && (d < len); d += CLEAR_STEP) {
            Coord2d t = aim.add(back.mul(d));
            if (!inside(gobs, t))
                return t;
        }
        return aim;
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
                    if (Gates.isGate(g) && Gates.isOpen(g))
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
     * @return true if we ended within {@code tol} of the destination.
     */
    public boolean travelTo(Coord2d dest, double tol) throws InterruptedException {
        if (dest == null)
            return false;
        /* Record whatever walls are in sight before planning, so the route about to be chosen
         * benefits from this trip rather than only the next one. */
        Barriers.learn(gui);
        refusedGates.clear();

        List<Coord2d> route = plan(dest);
        /* The waypoints themselves, not just how many. A count says nothing about the shape of a
         * route, and the shape is the thing that goes wrong - whether it turns towards a gateway,
         * whether it doubles back, whether the one waypoint it produced is anywhere near the way.
         * Several rounds of this have been spent inferring that from distances in failure
         * messages, which is guesswork when it could be a fact. */
        NLog.log(log, "travel to " + Gates.fmt(dest) + " from " + Gates.fmt(me())
            + ": " + ((route == null) ? "no route" : (route.size() + " waypoint(s) " + fmt(route)))
            + " then the destination");
        List<Coord2d> legs = itinerary(route, dest);
        int replans = 0;
        int gates = 0;
        for (int i = 0; i < legs.size(); i++) {
            if (!abort.running())
                return false;
            /* The destination is the last leg and is the only one that has to be arrived at
             * properly; the rest are waypoints to pass near. */
            boolean last = (i == (legs.size() - 1));
            blockingGate = 0;
            if (walkStraight(legs.get(i), last ? tol : LEG_TOL))
                continue;

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
            if ((gates++ < MAX_GATES)
                && Gates.pass(this, gui, leg, blockingGate, refusedGates, log)) {
                Barriers.learn(gui);
                List<Coord2d> after = plan(dest);
                /* Logged like every other re-plan. This one used to be silent, and its silence is
                 * why a bot that stepped through a gate and then walked fifty tiles the wrong way
                 * looked like a gate fault: the route it was following afterwards never appeared
                 * anywhere. */
                NLog.log(log, "re-planned from the gateway, at " + Gates.fmt(me()) + ": "
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
                NLog.log(log, "route to " + Gates.fmt(dest) + " failed " + replans
                    + " times; giving up " + (int) shortfall(dest) + "u short");
                return arrived(dest, tol);
            }
            /* The route was wrong about something - almost always a wall learned since, or
             * one that was never in view when the map file recorded the tiles. Re-plan from
             * where we actually are and start the itinerary again. */
            Barriers.learn(gui);
            List<Coord2d> again = plan(dest);
            NLog.log(log, "re-planned after a failed leg, from " + Gates.fmt(me()) + ": "
                + ((again == null) ? "no route" : (again.size() + " waypoint(s) " + fmt(again))));
            legs = itinerary(again, dest);
            i = -1;
        }
        return arrived(dest, tol);
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
            sb.append(Gates.fmt(c));
        }
        return sb.append(']').toString();
    }

    /** How far we still are from a destination, for logging. */
    private double shortfall(Coord2d dest) {
        Gob me = player();
        return (me == null) ? -1 : me.rc.dist(dest);
    }

    /** Whether we are close enough to call it arrival. */
    private boolean arrived(Coord2d dest, double tol) {
        Gob me = player();
        return (me != null) && (me.rc.dist(dest) <= tol);
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
        /* Said apart, because the three of them want quite different things done and the logs
         * could only ever say "no route" for all of them. Two are about the map file not being
         * able to place something and are nothing to do with the terrain; only the third is the
         * router actually failing to find a way, and that is the one worth reading a log over. */
        if ((me == null) || (here == null) || (there == null)) {
            NLog.log(log, "cannot plan to " + Gates.fmt(dest)
                + ": the map file can't place " + ((here == null) ? "us" : "it") + " yet");
            return null;
        }
        if (here.seg != there.seg) {
            NLog.log(log, "cannot plan to " + Gates.fmt(dest)
                + ": it is in map segment " + there.seg + " and we are in " + here.seg);
            return null;
        }
        Coord fromTile = here.sc.floor(MCache.tilesz), toTile = there.sc.floor(MCache.tilesz);
        List<Coord> nodes = Router.route(gui, here.seg, fromTile, toTile);
        if (nodes == null)
            return null;
        /* What the route is made of, not just where it goes. The interesting number is how much of
         * it crosses ground nobody has looked at and how far out that starts, because a route
         * through the unknown is a guess - and the reports of walking through palisades and across
         * rivers all describe it happening just past the edge of what was on screen, which is
         * exactly where the record stops and the guessing begins. */
        NLog.log(log, "  route " + Router.describe(gui, here.seg, fromTile, toTile, nodes));
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
        while (!out.isEmpty() && (out.get(0).dist(me.rc) <= LEG_TOL))
            out.remove(0);
        /* Likewise the last one: {@link #itinerary} puts the destination itself on the end of
         * every route, so a waypoint already inside the final approach is that same walk done
         * twice. Dropping it here is safe only BECAUSE the destination is re-appended there - it
         * is not this method's job to leave something for travel to walk to. */
        while (!out.isEmpty() && (out.get(out.size() - 1).dist(dest) <= LEG_TOL))
            out.remove(out.size() - 1);
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
                refused = 0;
            } else if (++stalled > TRAVEL_STALL_LIMIT) {
                NLog.log(log, "walk gave up " + (int) dist + "u short of " + Gates.fmt(dest)
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
            if (((stalled > 0) || wasBlocked) && !wasStuck) {
                Gob shut = Gates.blocking(gui, dest, refusedGates);
                if (shut != null) {
                    NLog.log(log, "no headway towards " + Gates.fmt(dest) + " and shut gateway #"
                        + shut.id + " at " + Gates.fmt(shut.rc) + " is in the way - opening it");
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
            Coord2d aim = me.rc.add(dir.mul(span));
            stepTo(aim, 11 * 2.0);
            wasBlocked = stepRefused && (stepRefusal == Pathfinder.Refusal.NO_ROUTE);
            /* Anything that is not the search having looked and found nothing is treated as being
             * wedged, including a click that never reached a search at all - because the recovery
             * for all of those is the same, and because being wrong in this direction costs one
             * sidestep while being wrong in the other costs an unwanted trip to a gate. */
            wasStuck = stepRefused && !wasBlocked;
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
                    NLog.log(log, "wedged " + (int) dist + "u short of " + Gates.fmt(dest)
                        + " with every click refused - stepped clear and carrying on");
                    refused = 0;
                    best = Double.MAX_VALUE;
                    stalled = 0;
                    wasStuck = false;
                    continue;
                }
                NLog.log(log, "nothing walkable at " + Gates.fmt(aim) + " on the way to "
                    + Gates.fmt(dest) + " - " + refused + " clicks refused, "
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
