package haven.automated.nbots.world;

import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.automated.nbots.core.NLog;
import java.util.ArrayList;
import java.util.List;

/**
 * The journey half of {@link BotNav}: getting from here to a destination in hops.
 *
 * <p>Walks the route {@link BotNav#plan} produced - or, when there is no route, walks one short
 * hop and asks again - through the itinerary loop that owns gate handling, drift correction and
 * the re-plan budget. Split out of {@code BotNav} in 2026-08 so the movement machinery reads as
 * one seam: {@link MovementCommand} owns waiting and state, {@link Approach} owns closing with a
 * gob, this owns the journey, and the walker half (step, hop, clear span) stays on
 * {@code BotNav} for the moment.
 */
public class Travel {

    private final BotNav nav;
    private final String log;

    public Travel(BotNav nav, String log) {
        this.nav = nav;
        this.log = log;
    }

    /**
     * Walks to a point however far away it is, in hops.
     *
     * <p>Each hop aims at most {@link BotNav#HOP} along the straight line to the destination and then
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
        Observed.observe(nav.gui);
        nav.refusedGates.clear();

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
        List<Coord2d> route = nav.plan(dest);
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
            if (!nav.hopToward(dest))
                return TravelResult.blocked(me(),
                    "hop toward " + GateManager.fmt(dest) + " stopped short - a wall is in the way");
            route = nav.plan(dest);
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
         * {@link BotNav#plan} asked the clamped router, so a route that stops at the vision edge
         * rather than at the destination means the destination is beyond what has been observed.
         * Walking that route means walking its last leg - the destination - straight at a target
         * nobody has seen, through whatever invisible wall may sit there, which is exactly what
         * the clamp exists to stop. So a clamped route is a cue to walk a hop forward, see more,
         * and ask again. Each hop extends the observed region by up to {@link BotNav#HOP_MAX}
         * tiles, so twelve covers far more than any base-to-base gap.
         *
         * Bounded at all only so that a destination which is genuinely out of reach - a place
         * nobody can stand, or another segment - does not walk the bot away from base for ever. */
        int explores = 0;
        while (nav.planClamped && (explores++ < nav.MAX_EXPLORE)) {
            NLog.log(log, "route clamped " + (int) shortfall(dest) + "u short of "
                + GateManager.fmt(dest) + " - exploring forward (hop " + explores + "/"
                + nav.MAX_EXPLORE + ")");
            if (!nav.hopToward(dest))
                return TravelResult.blocked(me(), "exploration hop toward " + GateManager.fmt(dest)
                    + " stopped short - a wall is in the way");
            Observed.observe(nav.gui);
            route = nav.plan(dest);
            if (route == null) {
                NLog.log(log, "no route to " + GateManager.fmt(dest) + " after exploring - giving up");
                return finish(dest, tol, true,
                    "no route to " + GateManager.fmt(dest) + " after exploring");
            }
        }
        if (nav.planClamped) {
            NLog.log(log, "destination " + GateManager.fmt(dest) + " still beyond the observed edge after "
                + nav.MAX_EXPLORE + " exploration hops - giving up");
            return TravelResult.failed(me(),
                "destination " + GateManager.fmt(dest) + " beyond observed ground after " + nav.MAX_EXPLORE
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
            if (!nav.abort.running())
                return TravelResult.aborted(me());
            /* The destination is the last leg and is the only one that has to be arrived at
             * properly; the rest are waypoints to pass near. */
            boolean last = (i == (legs.size() - 1));
            nav.blockingGate = 0;
            /* If a keep-out circle (beast aggro ring, other character's personal space) has
             * drifted onto the current leg's corridor since the route was planned, re-plan
             * before wasting a walk on a leg that is now blocked. The local pathfinder inside
             * walkStraight will also catch this and abort mid-leg, but catching it here saves
             * the hop budget and gets a fresh route drawn around the new obstacle sooner.
             *
             * This is proactive rather than reactive: ringedOff inside walkStraight catches a
             * beast that steps onto the leg DURING the walk; this catches one that was already
             * there when the leg started, which is the case the user asked about. */
            if (nav.appr.checkPathBlocked(me(), legs.get(i)) && (blocked < MAX_BLOCKED)) {
                blocked++;
                NLog.log(log, "keep-out circle now blocks leg corridor to " + GateManager.fmt(legs.get(i))
                    + " from " + GateManager.fmt(me()) + " - re-planning"
                    + " (" + blocked + " of " + MAX_BLOCKED + ")");
                Observed.observe(nav.gui);
                List<Coord2d> again = nav.plan(dest);
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
            boolean got = nav.walkStraight(legs.get(i), last ? tol : nav.LEG_SLACK);
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
                if (drifts < nav.MAX_DRIFTS) {
                    drifts++;
                    NLog.log(log, "  ...arrived, but the next leg cuts through a wall from here and"
                        + " not from " + GateManager.fmt(legs.get(i)) + " - walking onto it");
                    nav.walkStraight(legs.get(i), nav.ON_WAYPOINT);
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
            if (!last && (ended != null) && (ended.dist(legs.get(i)) <= nav.LEG_TOL)
                    && nav.restIsWalkable(legs, i)) {
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
            if ((gates < nav.MAX_GATES)
                && GateManager.pass(nav, nav.gui, leg, nav.blockingGate, nav.refusedGates, log)) {
                gates++;
                Observed.observe(nav.gui);
                List<Coord2d> after = nav.plan(dest);
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
            if (++replans > nav.MAX_REPLANS) {
                NLog.log(log, "route to " + GateManager.fmt(dest) + " failed " + replans
                    + " times; giving up " + (int) shortfall(dest) + "u short");
                return finish(dest, tol, true,
                    "route failed " + replans + " times");
            }
            /* The route was wrong about something - almost always a wall learned since, or
             * one that was never in view when the map file recorded the tiles. Re-plan from
             * where we actually are and start the itinerary again. */
            Observed.observe(nav.gui);
            List<Coord2d> again = nav.plan(dest);
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
     * router's last waypoint is the centre of the block the destination sits in, so {@link
     * BotNav#plan} quite rightly drops it as redundant - and on any journey the router saw no
     * reason to turn on, that was the only waypoint there was. Travel was therefore handed an
     * EMPTY list on the great majority of trips, walked past the whole leg loop without entering
     * it once, and finished on a bare straight walk. Every gate attempt and every re-plan lives
     * inside that loop, so neither had ever run: the logs show "0 waypoint(s)" on every single
     * trip and no gate line at all.
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
        Gob p = nav.player();
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
        Gob me = nav.player();
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
     * {@link BotNav#approach}, which exists because that exact hand-off swam a character across a
     * river.
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
        Gob me = nav.player();
        if ((me == null) || (me.rc.dist(dest) > tol))
            return false;
        WorldAnchor here = WorldAnchor.capturePlayer(nav.gui);
        if (here == null)
            return true;   // cannot tell, so do not invent a failure
        Router.World w = new Router.World(nav.gui, here.seg, false, true);
        return !nav.wallBetween(w, here.sc, dest.add(here.sc.sub(me.rc)));
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
        WorldAnchor here = WorldAnchor.capturePlayer(nav.gui);
        Gob me = nav.player();
        if ((here == null) || (me == null))
            return false;   // cannot tell, so do not invent a detour
        Coord2d off = here.sc.sub(me.rc);
        Coord2d next = legs.get(i + 1).add(off);
        Router.World w = new Router.World(nav.gui, here.seg, false, true);
        return nav.crossesWall(w, here.sc, next)
            && !nav.crossesWall(w, legs.get(i).add(off), next);
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
            Coord2d dest = anchor.resolve(nav.gui);
            if (dest == null) {
                NLog.log(log, "cannot resolve " + anchor + " - different map segment?");
                return TravelResult.failed(me(), "cannot resolve " + anchor);
            }
            Gob me = nav.player();
            if (me == null)
                return TravelResult.failed(null, "player is unavailable");
            double dist = me.rc.dist(dest);
            if (dist <= tol)
                return TravelResult.arrived(me.rc);
            // Inside one hop of the target: hand the rest to travelTo, which owns the stall and
            // detour bookkeeping, and let it finish the job.
            if (dist <= nav.HOP)
                return travelTo(dest, tol);
            TravelResult r = travelTo(dest, nav.HOP * 0.75);
            if (!r.isArrived())
                return r;
        }
        return TravelResult.failed(me(), "gave up after 120 hops");
    }
}
