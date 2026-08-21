package haven.automated.nbots.task;

import haven.Coord2d;
import haven.automated.helpers.HearthTravel;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.TravelResult;
import haven.automated.nbots.world.WorldAnchor;

/**
 * Goes somewhere, however far away it is.
 *
 * Three constructors for the three ways a destination gets expressed - a {@link Place} the player
 * defined, a bare {@link WorldAnchor} (a spot a bot bookmarked for itself), or live coordinates -
 * because all three arrive at the same walk and there is no reason for callers to convert.
 *
 * The distinction that matters is anchored versus live. An anchor is re-resolved on every hop, so a
 * journey long enough for the client to re-base its coordinates still ends in the right place; live
 * coordinates are only meaningful right now, which is fine for "step over there" and wrong for
 * "go back to base".
 */
public class TravelTo implements Task {
    private final Place place;
    private final WorldAnchor anchor;
    private final Coord2d point;
    private final double tolerance;
    private final String what;

    private static final double DEFAULT_TOL = 11 * 4.0;

    /**
     * How near the chosen tile counts as being on it.
     *
     * Two tiles: enough that a walk stopping against something on the way in still counts, and small
     * enough that the circle it describes stays inside any place worth having drawn.
     */
    private static final double ARRIVE_TOL = 11 * 2.0;
    /**
     * How much nearer the hearth has to land before hearthing in is worth a travel, in world
     * units (ten tiles). Only consulted once walking has already failed - see hearthReaches.
     */
    private static final double HEARTH_MARGIN = 11 * 10.0;

    public TravelTo(Place place) {
        this(place, null, null, DEFAULT_TOL, place == null ? "nowhere" : place.name);
    }

    public TravelTo(WorldAnchor anchor) {
        this(null, anchor, null, DEFAULT_TOL, "a remembered spot");
    }

    public TravelTo(Coord2d point, double tolerance) {
        this(null, null, point, tolerance, "a spot");
    }

    private TravelTo(Place place, WorldAnchor anchor, Coord2d point, double tol, String what) {
        this.place = place;
        this.anchor = anchor;
        this.point = point;
        this.tolerance = tol;
        this.what = what;
    }

    public TravelTo within(double tol) {
        return new TravelTo(place, anchor, point, tol, what);
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        /* Top up before setting off, not after arriving.
         *
         * Every journey in this tree comes through here, and none of it drinks: BotNav has no idea
         * what stamina is, and the loop that does run upkeep runs it between TARGETS. So a bot sent
         * to the far side of the base on half a meter walks the whole way on a falling one, arrives
         * at walking pace because the server has taken the top gear away, and only then gets a
         * chance to drink from the skin it was carrying the entire time. A mouthful before setting
         * off costs a second and is the difference between arriving able to work and arriving
         * needing another errand first. Carried water only - see the method. */
        Drink.sipIfCarried(ctx);
        WorldAnchor a = (anchor != null) ? anchor : (place != null ? place.anchor : null);
        if (a != null) {
            double tol = tolerance;
            Coord2d aim;
            if (place != null) {
                if (ctx.player() == null)
                    return Outcome.failed("player position is unavailable");
                if (place.contains(ctx.gui, ctx.player().rc))
                    return Outcome.ok();
                /* A tile inside the place that can be stood on, rather than the geometric centre,
                 * and a TIGHT tolerance around it rather than one widened to cover the rectangle.
                 *
                 * Widening it was the obvious way to stop a big storage area reading as never
                 * reached, and it quietly turned the destination into a disc that does not fit
                 * inside the place. Two tiles inside a palisade with a four-tile tolerance, the
                 * ground on the far side of that palisade satisfies the journey - so the bot is
                 * entitled to arrive by standing outside the wall, and a router asked to get it
                 * there will happily take it round through the gate to do so. Arriving is what
                 * `contains` says it is; the tolerance only has to be loose enough to allow for
                 * where a walk actually stops. */
                aim = place.destination(ctx.gui, ctx.player().rc);
                tol = ARRIVE_TOL;
            } else {
                aim = a.resolve(ctx.gui);
            }
            if (aim == null)
                return Outcome.failed(what + " is not on this part of the map");
            ctx.status("Travelling to " + what + ".");
            return maybeHearth(ctx, aim, tol);
        }
        if (point == null)
            return Outcome.failed("no destination");
        return maybeHearth(ctx, point, tolerance);
    }

    /**
     * Whether to hearth-travel before walking, and then walk anyway.
     *
     * Off by default ({@code NBotConfig.Key.hearthTravel}); when on, the decision is
     * {@code HearthTravel.betterThanWalking} - the full three-way comparison (walk-here-to-target
     * against channel plus walk-hearth-to-target plus margin, budget-respecting, hearth-known
     * only). Hearth travel is never an alternative to walking, only a way of covering part of the
     * distance faster: the journey still ends by walking to the destination from wherever the
     * hearth dropped us.
     */
    private Outcome maybeHearth(BotCtx ctx, Coord2d dest, double tol) throws InterruptedException {
        if (NBotConfig.on(NBotConfig.Key.hearthTravel)
                && HearthTravel.betterThanWalking(ctx.gui, dest, (int) tol)) {
            ctx.log("hearth travel beats walking to " + what + " - teleporting");
            HearthTravel.travel(ctx.gui);
        }
        return arrive(ctx, dest, tol);
    }

    /**
     * Actually walks there, and reports what happened.
     *
     * This used to BE {@code GateManager.pass(...)} - the whole of "travel to a place" was "find a
     * gateway between here and there and go through it", with its boolean answer returned as the
     * journey's outcome. Nothing in it ever planned a route or walked to the destination, which is
     * why {@link BotNav#travelTo} - the router, the segment-wide plan, the leg-by-leg walk, all of
     * it - had no callers at all outside BotNav's own file.
     *
     * What that produced was a bot that walked to whatever gateway happened to lie near the line to
     * its destination and then reported SUCCESS, because passing a gate returned true. So a trip to
     * the water place ended at a gate, and the fill-from-barrel step then ran where it stood - at a
     * fence, with no barrel - and blamed the place for being empty.
     *
     * Gates are still handled, in the place that always meant to handle them: {@link BotNav} opens a
     * shut gateway when a leg of the route is blocked by one, and carries on to the destination
     * afterwards. A gateway is a thing crossed on the way somewhere, not a destination.
     */
    private Outcome arrive(BotCtx ctx, Coord2d dest, double tol) throws InterruptedException {
        TravelResult r = ctx.nav.travelTo(dest, tol);
        if (r.isArrived())
            return Outcome.ok();
        if (r.isAborted())
            return Outcome.blocked("stopped on the way to " + what);
        /* No walking route, but the hearth might be on the far side of whatever is in the way.
         *
         * This is the one-directional gate case above all: a slave key will not open a gate from
         * outside, so the router correctly reports that a walled compound cannot be entered on
         * foot - and hearthing lands us inside it regardless. Unreachable by road is not the same
         * as unreachable, and retiring the destination here would have a bot give up on its own
         * base. Deliberately only on isFailed(): a BLOCKED leg is a transient obstacle that will
         * clear on its own, and burning a hearth travel on it would be waste. */
        if (r.isFailed() && hearthReaches(ctx, dest)) {
            ctx.log("no walking route to " + what + " - hearthing in and walking from there");
            HearthTravel.travel(ctx.gui);
            TravelResult again = ctx.nav.travelTo(dest, tol);
            if (again.isArrived())
                return Outcome.ok();
            if (again.isAborted())
                return Outcome.blocked("stopped on the way to " + what);
            r = again;
        }
        // Blocked is a transient obstacle and worth another go later; failed means no route exists,
        // which retrying from the same spot cannot mend. Passing the distinction on rather than
        // flattening both to "couldn't walk there" is what lets a caller tell "try again in a
        // minute" from "this destination is wrong".
        String why = (r.reason() == null) ? "couldn't walk to " + what
            : "couldn't walk to " + what + ": " + r.reason();
        return r.isBlocked() ? Outcome.blocked(why) : Outcome.failed(why);
    }

    /**
     * Whether hearthing would put us meaningfully nearer the destination than we are now.
     *
     * Distance is the only test available and it is the right one here. The pre-walk comparison
     * ({@link HearthTravel#betterThanWalking}) weighs a teleport against a walk that WORKS; by the
     * time this is asked there is no such walk, so anything that lands closer is strictly better
     * than failing. The margin keeps a hearth that is barely nearer from spending one of the
     * run's travels for a few tiles.
     *
     * Still behind the hearthTravel setting: a player who has told the crew not to teleport means
     * it whether or not walking has run out.
     */
    private boolean hearthReaches(BotCtx ctx, Coord2d dest) {
        if (!NBotConfig.on(NBotConfig.Key.hearthTravel) || !HearthTravel.canTravel())
            return false;
        WorldAnchor h = HearthTravel.hearth(ctx.gui);
        if (h == null)
            return false;
        Coord2d at = h.resolve(ctx.gui);
        if ((at == null) || (ctx.player() == null))
            return false;
        return at.dist(dest) + HEARTH_MARGIN < ctx.player().rc.dist(dest);
    }

    @Override
    public String label() {
        return "travel to " + what;
    }
}
