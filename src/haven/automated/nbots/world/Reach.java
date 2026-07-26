package haven.automated.nbots.world;

import haven.Coord2d;
import haven.Gob;
import haven.Resource;
import haven.automated.helpers.HitBoxes;

/**
 * How close a bot can actually expect to get to something.
 *
 * Every "walk up to it and act on it" in this package used to carry its own flat number, and a flat
 * number is wrong for a reason that shows up the moment you use one: pathing stops at the edge of
 * the target's COLLISION BOX, not at its centre, and the boxes differ by an order of magnitude.
 * Asking to be within eleven units of a barrel's centre is asking to stand inside the barrel - the
 * walk finishes as close as it can, the distance check says no, and whatever comes next re-issues
 * itself from a position that will never satisfy it. That is the barrel stutter: each fill was a
 * fresh use-attempt from just outside range, and the game's own click-to-walk answered each one with
 * another step.
 *
 * So reach is asked of the object. The box used is the same {@link HitBoxes} data the local
 * pathfinder blocks on, which is what makes the two agree about where "next to it" is.
 */
public class Reach {
    /** What is added to an object's own half-width to get a place to stand. About a tile and a half. */
    private static final double MARGIN = 11 * 1.5;
    /** Used when an object has no box on record. The old flat guess, kept as the floor. */
    private static final double UNKNOWN = 11 * 2.0;

    private Reach() {}

    /**
     * Half the width of a gob's collision box, in world units, or 0 if it hasn't got one.
     *
     * The furthest corner rather than the mean: a palisade gate is long and thin, and averaging
     * would report a radius that fits neither end of it. Rotation is deliberately ignored - we want
     * an upper bound on how far out the thing extends in any direction, and the unrotated corner
     * distance is exactly that, whichever way it is turned.
     */
    public static double radius(Gob g) {
        HitBoxes.CollisionBoxSecondary[] boxes;
        try {
            Resource res = (g == null) ? null : g.getres();
            boxes = (res == null) ? null : HitBoxes.collisionBoxMap.get(res.name);
        } catch (RuntimeException e) {
            // Includes Loading: no resource yet means no box yet.
            return 0;
        }
        if (boxes == null)
            return 0;
        double best = 0;
        for (HitBoxes.CollisionBoxSecondary box : boxes) {
            if ((box == null) || (box.coords == null))
                continue;
            for (Coord2d c : box.coords)
                best = Math.max(best, Math.hypot(c.x, c.y));
        }
        return best;
    }

    /**
     * A distance from an object's centre that a bot can realistically stand at and act from.
     *
     * Never below {@link #UNKNOWN}, so an object with no box on record behaves the way everything
     * did before this existed rather than becoming unreachable.
     */
    public static double toActOn(Gob g) {
        return Math.max(UNKNOWN, radius(g) + MARGIN);
    }
}
