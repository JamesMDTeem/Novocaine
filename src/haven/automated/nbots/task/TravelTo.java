package haven.automated.nbots.task;

import haven.Coord2d;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.Place;
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
            if (ctx.nav.travelTo(aim, tol))
                return Outcome.ok();
            // The walk may still have put us in the place even if it stopped short of the tile.
            return ((place != null) && place.contains(ctx.gui, ctx.player().rc))
                ? Outcome.ok() : Outcome.blocked("couldn't walk to " + what);
        }
        if (point == null)
            return Outcome.failed("no destination");
        return ctx.nav.travelTo(point, tolerance) ? Outcome.ok()
            : Outcome.blocked("couldn't walk to " + what);
    }

    @Override
    public String label() {
        return "travel to " + what;
    }
}
