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
        WorldAnchor a = (anchor != null) ? anchor : (place != null ? place.anchor : null);
        if (a != null) {
            // A place is a rectangle; arriving means reaching its centre within a tolerance wide
            // enough to cover its own extent, or a big storage area would never read as "arrived".
            double tol = tolerance;
            Coord2d centre = (place != null) ? place.centre(ctx.gui) : a.resolve(ctx.gui);
            if (centre == null)
                return Outcome.failed(what + " is not on this part of the map");
            if (place != null) {
                if (place.contains(ctx.gui, ctx.player().rc))
                    return Outcome.ok();
                tol = Math.max(tol, Math.max(place.w, place.h) * haven.MCache.tilesz.x / 2.0);
            }
            ctx.status("Travelling to " + what + ".");
            return ctx.nav.travelTo(centre, tol) ? Outcome.ok()
                : Outcome.blocked("couldn't walk to " + what);
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
