package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Resource;
import haven.automated.nbots.core.NLog;
import haven.automated.pathfinder.Pathfinder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The walkers: the record-reading passability and the greedy leg walk.
 *
 * Split from {@link BotNav} so the mover and the journey planner work from one
 * movement seam. Holds the per-step machinery: what stands where, what the
 * record says about a line, and the hop loop that walks a short straight leg.
 */
public class Walkers {
    private final BotNav nav;
    private final String log;

    public Walkers(BotNav nav, String log) {
        this.nav = nav;
        this.log = log;
    }

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
     * AND A REFUSED CLICK IS RECORDED AS SUCH. See {@link BotNav#stepRefused}.
     *
     * @return true if we got within {@code tol} of it.
     */
    public boolean stepTo(Coord2d dest, double tol) throws InterruptedException {
        nav.stepRefused = false;
        nav.stepRefusal = null;
        if (dest == null)
            return false;
        Gob me = nav.player();
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
            nav.appr.publishKeepouts(me.rc);
            Thread was = nav.gui.map.pfthread;
            nav.gui.map.pfLeftClick(aim.floor(), null);
            /* No settling pause before this is read. pfLeftClick starts the thread inside itself,
             * before it returns, so a new thread here is already alive and a thread that is still
             * the old one means the click was thrown away - both facts are true immediately. The
             * quarter-second this replaces was covering a race that does not exist, and it was
             * being paid on every hop of every journey. */
            Pathfinder walk = nav.gui.map.pf;
            Thread pft = nav.gui.map.pfthread;
            if ((pft == null) || (pft == was) || (walk == null)) {
                nav.stepRefused = true;
                /* The click started no search. The map knows which of several quite different
                 * faults that was - a missing player, a target off the edge of the window, a
                 * thrown exception - and until it was asked, all of them read here as the same
                 * unhelpful sentence. */
                if (nav.gui.map.pfrefusal != null)
                    why = nav.gui.map.pfrefusal;
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
                nav.waitUntil(() -> {
                    Gob p = nav.player();
                    return p == null || p.rc.dist(dest) <= tol || !pft.isAlive();
                }, 400);
                /* mc is set from the first edge the path yields, so a null one after the walk has
                 * finished means the search produced no edges at all - the destination was
                 * unreachable from the start rather than merely far off. */
                nav.stepRefused = (walk.mc == null);
                nav.stepRefusal = walk.refusal;
                if (walk.why() != null)
                    why = walk.why();
                /* The default `why` above says the click was thrown away before a search started,
                 * which is only true on the OTHER branch. Here a search DID start - and if it
                 * finished with no edges at all and no reason of its own, the honest reason is
                 * that the destination was unreachable from the start (the near-branch click
                 * whose target is already under us or under a just-crossed box is the usual
                 * case). Never let the throwaway text ride along on this branch. */
                if ((walk.mc == null) && (walk.why() == null))
                    why = "the search ran and found no path from here";
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
            nav.appr.clearKeepouts();
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
        if (nav.stepRefused) {
            /* The two cases the fallback must NOT answer with a direct walk, both of which explain
             * the refusal without any verdict on the ground: a keep-out ring is a bear that will
             * move on, and a click that never became a search says nothing at all about the tile
             * it aimed at. Direct-walking either is the shape of the give-up loop this method was
             * built to end - the log has it walking a clear line seven times over, no progress. */
            if (nav.appr.ringedOff(me.rc, dest)) {
                NLog.log(log, "pathfinder refused " + GateManager.fmt(dest)
                    + " and one of our own keep-out circles was across the way"
                    + " - not walking the line directly");
            } else if (!started) {
                NLog.log(log, "the click for " + GateManager.fmt(dest) + " never became a search ("
                    + why + ") - not walking the line unplanned");
            } else if (Walk.lineClear(nav.gui, me.rc, dest)) {
                NLog.log(log, "pathfinder refused " + GateManager.fmt(aimed)
                    + (aimed.equals(dest) ? "" : (" (aimed for " + GateManager.fmt(dest) + ")"))
                    + " (" + why
                    + ") - walking there directly, the line is clear");
                Walk.straightTo(nav, nav.gui, dest, tol);
                nav.stepRefused = false;
            } else {
                /* The evidence, not just the verdict - and ONCE per destination, because the hop
                 * loop re-issues this seven times a leg and travel re-plans four times, so the same
                 * refusal used to fill eight hundred lines with the one fact already known. What is
                 * wanted is the other four records' opinion of the same point, which is what
                 * actually differs between "the destination is on a barrel", "it is in a lake" and
                 * "there is a wall in front of it". */
                boolean fresh = (nav.whined == null) || (nav.whined.dist(dest) > MCache.tilesz.x);
                NLog.log(log, "pathfinder refused " + GateManager.fmt(aimed)
                    + (aimed.equals(dest) ? "" : (" (aimed for " + GateManager.fmt(dest) + ")"))
                    + " (" + why
                    + ") and the straight line is not clear either"
                    + (fresh ? "" : " [same point]"));
                if (fresh) {
                    nav.whined = dest;
                    NLog.log(log, "  destination: " + Probe.explain(nav.gui, dest));
                    NLog.log(log, "  the way there: " + Probe.line(nav.gui, me.rc, dest));
                    NLog.log(log, Probe.map(nav.gui, dest, 12));
                    /* The refusal dump proper: the loaded gobs and their real boxes around the
                     * refused point, each with the record's verdict on the tiles its box covers.
                     * This is the comparison the record cannot make for itself - see Probe. */
                    NLog.log(log, Probe.objectsNear(nav.gui, dest, 8));
                }
                learnRefusal(me.rc, dest, fresh);
            }
        }
        Gob now = nav.player();
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
        if (nav.stepRefusal != Pathfinder.Refusal.NO_ROUTE)
            return;
        if (nav.appr.ringedOff(from, dest)) {
            if (fresh)
                NLog.log(log, "  not learning that tile - one of our own keep-out circles"
                    + " was across the way, so the refusal says nothing about the ground");
            return;
        }
        WorldAnchor here = WorldAnchor.capturePlayer(nav.gui);
        if (here == null)
            return;
        Router.World w = new Router.World(nav.gui, here.seg, false, true);
        Coord2d off = here.sc.sub(from);
        Coord tile = dest.add(off).floor(MCache.tilesz);
        if (w.recordSolid(tile) || w.recordGate(tile))
            return;   // the refusal is already explained; nothing to learn
        if (!Router.walkable(nav.gui, here.seg, here.sc.floor(MCache.tilesz), tile))
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
            if (w.recordGate(at.floor(MCache.tilesz)))
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
     * it meant. Bounded at {@link BotNav#CLEAR_MAX}, since a destination buried three tiles deep in solid
     * ground is not a destination that was nudged wrong - it is one the caller should hear about,
     * which it does through {@link BotNav#stepRefused}.
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
        List<Gob> gobs = solids(nav.gui);
        WorldAnchor here = WorldAnchor.capturePlayer(nav.gui);
        /* A SHUT gateway is solid to the client and invisible to every test above.
         *
         * `solids` leaves gateways out on purpose - the router must be free to plan through them,
         * since a gateway is the one place a wall is meant to be crossed - and `Observed.solid`
         * makes the same exception. Which means that when the gate layer walks up to a gate to open
         * it, the aim lands on the gate's own tile, nothing here objects, and the click is thrown
         * away by the client before a search starts. Every gate crossing in the log pays one of
         * those, and from the outside it looks exactly like the bot trying to walk through the wall
         * before finding the gate. See shutGateTiles for why this is a tile test and not a box one. */
        Set<Coord> shutGates = shutGateTiles(nav.gui, here);
        Router.World w = (here == null) ? null : new Router.World(nav.gui, here.seg, false, true);
        if (!blockedThere(w, gobs, shutGates, here, from, aim))
            return aim;
        Coord2d back = from.sub(aim);
        double len = back.abs();
        if (len < 1.0)
            return aim;
        back = back.div(len);
        for (double d = nav.CLEAR_STEP; (d <= nav.CLEAR_MAX) && (d < len); d += nav.CLEAR_STEP) {
            Coord2d t = aim.add(back.mul(d));
            if (!blockedThere(w, gobs, shutGates, here, from, t))
                return t;
        }
        /* Rings outward from the aim, nearest first, so the answer is the least the aim can be moved
         * and still be somewhere a character can be. Both offsets are swept together rather than
         * ring-by-ring in one axis, because the lane out of a lattice is diagonal as often as not. */
        Coord2d side = new Coord2d(-back.y, back.x);
        for (double d = nav.CLEAR_STEP; d <= nav.CLEAR_MAX; d += nav.CLEAR_STEP) {
            for (int s = -1; s <= 1; s += 2) {
                for (double along = 0; along <= d; along += nav.CLEAR_STEP) {
                    Coord2d t = aim.add(side.mul(d * s)).add(back.mul(along));
                    if (!blockedThere(w, gobs, shutGates, here, from, t))
                        return t;
                }
            }
        }
        return aim;
    }

    /** Where we are, for logging. */
    private Coord2d me() {
        Gob p = nav.player();
        return (p == null) ? null : p.rc;
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
        WorldAnchor here = WorldAnchor.capturePlayer(nav.gui);
        Gob me = nav.player();
        if ((here == null) || (me == null))
            return span;   // cannot tell, so do not invent an obstacle
        Router.World w = new Router.World(nav.gui, here.seg, false, true);
        Coord2d off = here.sc.sub(me.rc);
        int steps = Math.max(1, (int) Math.ceil((span / MCache.tilesz.x) * 2));
        /* WHERE the gateway is on this line, not merely whether there is one. */
        double gateFrom = -1, gateTo = -1;
        for (int i = 0; i <= steps; i++) {
            double d = (span * i) / steps;
            Coord t = from.add(dir.mul(d)).add(off).floor(MCache.tilesz);
            if (w.recordGate(t)) {
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
            // Check ALL impassable types, not just WALL - via the grid adapter, which answers from
            // the same record and map file the planner reads.
            boolean isBlocking = w.recordBlocking(tile) || w.waterBlocks(tile);
            if (!isBlocking)
                continue;
            // Gateway post forgiveness - ONLY for actual gateway tiles ± 1 tile
            if ((gateFrom >= 0) && (d >= (gateFrom - postSlack)) && (d <= (gateTo + postSlack)))
                continue;
            // Visible objects are handled by local PF - only block on UNKNOWN walls
            if (occupied(nav.gui, p))
                continue;
            // Stop 1 tile before the obstacle
            return Math.max(0, d - MCache.tilesz.x);
        }
        return span;
    }

    boolean restIsWalkable(List<Coord2d> legs, int i) {
        if ((i + 1) >= legs.size())
            return true;
        WorldAnchor here = WorldAnchor.capturePlayer(nav.gui);
        Gob me = nav.player();
        if ((here == null) || (me == null))
            return true;   // cannot tell, so do not invent a failure
        Coord2d off = here.sc.sub(me.rc);
        return Router.walkable(nav.gui, here.seg, here.sc.floor(MCache.tilesz),
            legs.get(i + 1).add(off).floor(MCache.tilesz));
    }

    /**
     * Turns a destination into waypoints, or null if there is no useful route to plan.
     *
     * Null covers three cases that all want the same answer: the map file cannot place us yet, the
     * destination is in another segment, or the router found nothing. In each the caller should
     * simply walk at the target - which is what it did before any of this existed.
     */
    List<Coord2d> plan(Coord2d dest) {
        Gob me = nav.player();
        WorldAnchor here = WorldAnchor.capturePlayer(nav.gui);
        WorldAnchor there = WorldAnchor.capture(nav.gui, dest);
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
        List<Coord> nodes = Router.routeClamped(nav.gui, here.seg, fromTile, toTile);
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
            List<Coord> optimistic = Router.route(nav.gui, here.seg, fromTile, toTile);
            if ((optimistic != null)
                && (optimistic.isEmpty() || optimistic.get(optimistic.size() - 1).equals(toTile))) {
                nodes = optimistic;
                clamped = false;
            }
        }
        nav.planClamped = clamped;
        if (nodes == null) {
            nav.planClamped = false;
            return null;
        }
        /* What the route is made of, not just where it goes. The interesting number is how much of
         * it crosses ground nobody has looked at and how far out that starts, because a route
         * through the unknown is a guess - and the reports of walking through palisades and across
         * rivers all describe it happening just past the edge of what was on screen, which is
         * exactly where the record stops and the guessing begins. */
        NLog.log(log, "  route " + Router.describe(nav.gui, here.seg, fromTile, toTile, nodes)
            + (nav.planClamped ? " [CLAMPED]" : ""));
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
         * It used to drop every leading waypoint within nav.LEG_TOL - three tiles - which sounds like
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
         * same question {@link Travel#driftedIntoWall} asks one layer up, and needs no guess about which
         * waypoints are special. Verified on the logged case: keeping it, leg one crosses nothing and
         * leg two runs down the gap through both gate rows clean. */
        Router.World w = new Router.World(nav.gui, here.seg, false, true);
        while (!out.isEmpty() && (out.get(0).dist(me.rc) <= nav.LEG_SLACK)) {
            Coord2d onward = (out.size() >= 2) ? out.get(1).sub(origin) : there.sc;
            if (crossesWall(w, here.sc, onward)
                    && !crossesWall(w, out.get(0).sub(origin), onward))
                break;
            out.remove(0);
        }
        /* Likewise the last one: {@link Travel#itinerary} puts the destination itself on the end of
         * every route, so a waypoint already inside the final approach is that same walk done
         * twice. Dropping it here is safe only BECAUSE the destination is re-appended there - it
         * is not this method's job to leave something for travel to walk to. */
        while (!out.isEmpty() && (out.get(out.size() - 1).dist(dest) <= nav.LEG_TOL))
            out.remove(out.size() - 1);
        /* Store the route so the travel loop can check each leg's corridor against live
         * keep-out circles before walking it. A beast or other character that drifts onto
         * the planned corridor between legs is invisible to the router and would otherwise
         * be walked into rather than routed around. */
        nav.currentRoute = out;
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
        Gob me = nav.player();
        if (me == null)
            return nav.HOP;
        double far = 0;
        try {
            synchronized (nav.gui.ui.sess.glob.oc) {
                for (Gob g : nav.gui.ui.sess.glob.oc)
                    far = Math.max(far, me.rc.dist(g.rc));
            }
        } catch (RuntimeException e) {
            return nav.HOP;
        }
        if (far <= 0)
            return nav.HOP;
        return Math.max(nav.HOP_MIN, Math.min(nav.HOP_MAX, far));
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
    boolean hopToward(Coord2d dest) throws InterruptedException {
        Gob me = nav.player();
        if ((me == null) || (dest == null))
            return false;
        double dist = me.rc.dist(dest);
        if (dist <= nav.LEG_TOL)
            return true;
        /* Min hop, not max. A minimum hop is the smallest thing that can move the character, and
         * the smallest movement is the one most likely to fit in any pocket the loaded terrain
         * has left. Aim at half the distance so a successful hop halves the gap and cannot
         * succeed without reaching its own tolerance; if it can, the destination is genuinely
         * close enough to hand to walkStraight. */
        Coord2d dir = dest.sub(me.rc);
        double len = dir.abs();
        /* Half the distance, never more than nav.HOP_MAX and never past the destination. The far
         * case matters more than the near one: hopToward exists because the router could not
         * anchor this destination, and an unclamped half of a far destination would be a blind
         * un-routed walk toward it - exactly what this method exists to avoid. Capping at nav.HOP_MAX
         * keeps it one short hop no matter how far away the destination is. For a destination
         * closer than a minimum hop the unclamped aim landed PAST the destination - nav.HOP_MIN is
         * twelve tiles - so walkStraight overran it by up to eleven tiles, which is the shape of
         * walking into the wall that sits just past a near destination. Clamping a near
         * destination to ninety percent keeps the aim short of it so the hop can only fail short
         * of it, never past it. */
        double aimLen = Math.min(len * 0.9, Math.min(nav.HOP_MAX, Math.max(nav.HOP_MIN, len * 0.5)));
        Coord2d aim = me.rc.add(dir.div(len).mul(aimLen));
        if (!walkStraight(aim, nav.LEG_TOL))
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
     * a palisade to be on, which is why {@link Travel#travelTo} no longer asks them to.
     */
    boolean walkStraight(Coord2d dest, double tol) throws InterruptedException {
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
            Gob me = nav.player();
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
            if ((began != null) && (began.dist(me.rc) > (leg * nav.WANDER) + nav.WANDER_SLACK)) {
                NLog.log(log, "walk wandered " + (int) (began.dist(me.rc) / MCache.tilesz.x)
                    + "t from the start of a " + (int) (leg / MCache.tilesz.x)
                    + "t leg to " + GateManager.fmt(dest) + " - that is a detour, not a leg");
                nav.cancelWalk();
                return false;
            }

            if (dist < best - 5.0) {
                best = dist;
                stalled = 0;
                refused = 0;
            } else if (++stalled > nav.TRAVEL_STALL_LIMIT) {
                NLog.log(log, "walk gave up " + (int) dist + "u short of " + GateManager.fmt(dest)
                    + " after " + stalled + " hops without progress");
                nav.cancelWalk();
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
            Gob routed = GateManager.onRoute(nav.gui, me.rc, dest, nav.refusedGates);
            if ((routed != null) && !wasStuck) {
                NLog.log(log, "the route to " + GateManager.fmt(dest) + " goes through shut gateway #"
                    + routed.id + " at " + GateManager.fmt(routed.rc) + " - opening it");
                nav.blockingGate = routed.id;
                nav.cancelWalk();
                return false;
            }
            if (((stalled > 0) || wasBlocked) && !wasStuck) {
                Gob shut = GateManager.blocking(nav.gui, dest, nav.refusedGates);
                if (shut != null) {
                    NLog.log(log, "no headway towards " + GateManager.fmt(dest) + " and shut gateway #"
                        + shut.id + " at " + GateManager.fmt(shut.rc) + " is in the way - opening it");
                    nav.blockingGate = shut.id;
                    nav.cancelWalk();
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
                    span = Math.min(span, nav.STEP_OFF);
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
            stepTo(aim, (span < len) ? (nav.TILE * 2.0) : Math.min(nav.ON_WAYPOINT, tol));
            wasBlocked = nav.stepRefused && (nav.stepRefusal == Pathfinder.Refusal.NO_ROUTE);
            /* Anything that is not the search having looked and found nothing is treated as being
             * wedged, including a click that never reached a search at all - because the recovery
             * for all of those is the same, and because being wrong in this direction costs one
             * sidestep while being wrong in the other costs an unwanted trip to a gate. */
            wasStuck = nav.stepRefused && !wasBlocked;
            /* If a beast's keep-out circle now blocks the remaining leg corridor, stop and let
             * travel re-plan. The local pathfinder handled this hop by going around the beast,
             * but the router's subsequent legs may now be invalid - the route was certified
             * before the beast moved into it, and the router does not consult keep-out circles.
             * Re-planning from here gives the router a fresh chance to route around the new
             * position. */
            if (nav.appr.ringedOff(me.rc, dest)) {
                NLog.log(log, "keep-out circle blocks remaining leg to " + GateManager.fmt(dest)
                    + " from " + GateManager.fmt(me.rc) + " - will re-plan");
                nav.cancelWalk();
                return false;
            }
            /* Refused over and over means the aim really is somewhere we cannot be, and no amount
             * of swinging sideways from the same spot will change that. Say so rather than burning
             * the hop budget wandering, which is the shape this failure used to take. */
            if (wasStuck && (++refused > nav.REFUSE_LIMIT)) {
                /* WEDGED, not blocked, and the difference decides what to do about it. Nothing
                 * will be walkable from here for as long as we are standing where we are - the
                 * commonest cause is our own position being inside a collision box, which refuses
                 * every path including the one back out - so the answer is to move first and judge
                 * afterwards. Bounded, because if stepping clear does not help twice over then it
                 * is not what was wrong. */
                if ((++unsticks <= nav.UNSTICK_LIMIT) && Walk.unstick(nav, nav.gui, dest)) {
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
                nav.cancelWalk();
                return false;
            }
        }
        nav.cancelWalk();
        Gob now = nav.player();
        return now != null && now.rc.dist(dest) <= tol;
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
     * The record half is asked of the grid adapter ({@code w}) rather than read directly, which
     * is what keeps the mover and the planner on the same record engine. {@code w} is null
     * exactly when {@code here} is, so the fallback above still holds.
     *
     * The record counts walls but deliberately not gateways, so this keeps the
     * open-gate exception {@link #solids} makes rather than fighting it - except for the gateways
     * in {@code shutGates}, which are exceptions to that exception. See {@link #shutGateTiles}.
     */
    private static boolean blockedThere(Router.World w, List<Gob> gobs, Set<Coord> shutGates,
                                        WorldAnchor here, Coord2d from, Coord2d wc) {
        if (inside(gobs, wc))
            return true;
        if ((here == null) || (w == null))
            return false;
        Coord tile = wc.add(here.sc.sub(from)).floor(MCache.tilesz);
        if (shutGates.contains(tile))
            return true;
        return w.recordSolid(tile);
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
                if ((res != null) && Pathfinder.isInsideBoundBox(g, p))
                    return true;
            } catch (RuntimeException e) {
                // Includes Loading: a gob whose resource hasn't arrived cannot be tested, and
                // guessing solid would refuse ground that is almost certainly fine.
            }
        }
        return false;
    }

    /** Whether the straight line between two points in segment coordinates meets a wall tile. */
    static boolean crossesWall(Router.World w, Coord2d from, Coord2d to) {
        return wallOn(w, from, to, true);
    }

    /**
     * As {@link #crossesWall}, but allowing either end to be standing against one.
     *
     * The distinction is the difference between "this line goes through a wall" and "a wall is
     * between these two places". Route legs want the first, since a leg drawn onto a wall tile is
     * wrong however it got there; {@link Travel#arrived} wants the second, because being up against a
     * palisade is where a lot of perfectly good destinations are.
     */
    static boolean wallBetween(Router.World w, Coord2d from, Coord2d to) {
        return wallOn(w, from, to, false);
    }

    private static boolean wallOn(Router.World w, Coord2d from, Coord2d to, boolean ends) {
        int steps = Math.max(1, (int) Math.ceil((from.dist(to) / MCache.tilesz.x) * 2));
        for (int i = (ends ? 0 : 1); i <= (ends ? steps : (steps - 1)); i++) {
            Coord t = from.add(to.sub(from).mul((double) i / steps)).floor(MCache.tilesz);
            if (w.recordWall(t))
                return true;
        }
        return false;
    }
}
