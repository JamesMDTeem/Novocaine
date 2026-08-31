package haven.automated.nbots.world;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.automated.helpers.HitBoxes;
import haven.automated.pathfinder.Map;

import java.util.ArrayList;
import java.util.List;

/**
 * The standing positions around a workable object, and how they're numbered.
 *
 * The unit of contention between bots is a SPOT, not an object. Several characters can chip the
 * same boulder or clear the same stump at once provided each has somewhere to stand, and a design
 * that let one bot lock a whole boulder would leave the others walking past perfectly good work.
 * So a target is modelled as a ring of positions around it, and {@link WorkClaims} hands out
 * individual positions.
 *
 * How many positions an object offers is derived from its own collision box: a lone granite
 * bumling gets a couple, a mature oak's trunk gets the full eight. That falls out of dividing the
 * ring's circumference by roughly the space one character needs, so it needs no per-resource table
 * and adapts on its own to whatever upstream changes an object's size to.
 *
 * Slot NUMBERING has to be identical in every client or the claims mean nothing, so it is derived
 * only from things every client agrees on - the gob's resource (hence its collision box) and its
 * world position - and never from anything local like which direction we happen to be approaching
 * from. Slot 0 is due east of the object's centre and they run counter-clockwise from there.
 */
public class WorkSlots {
    /** Clearance from the object's own footprint out to where a character stands to work it. */
    private static final double APPROACH_GAP = 9.0;
    /** Roughly the room one character needs on the ring without crowding its neighbours. */
    private static final double SLOT_SPACING = 13.0;
    private static final int MAX_SLOTS = 8;

    /**
     * Fallback ring radius for an object with no usable collision box. Small objects (herbs, dropped
     * items) genuinely have none, and a too-small ring is safe - the pathfinder stops us at the
     * hitbox edge anyway - whereas a too-large one would have us standing out of reach.
     */
    private static final double DEFAULT_EXTENT = 4.0;

    public final long gobid;
    public final Coord2d centre;
    public final double radius;
    public final int count;

    private WorkSlots(long gobid, Coord2d centre, double radius, int count) {
        this.gobid = gobid;
        this.centre = centre;
        this.radius = radius;
        this.count = count;
    }

    public static WorkSlots around(Gob gob) {
        if (gob == null)
            return null;
        double extent = halfExtent(gob);
        double r = extent + APPROACH_GAP;
        int n = (int) Math.floor((2 * Math.PI * r) / SLOT_SPACING);
        n = Math.max(1, Math.min(MAX_SLOTS, n));
        return new WorkSlots(gob.id, gob.rc, r, n);
    }

    /** Where slot {@code i} is, in world coordinates. */
    public Coord2d at(int i) {
        double a = (2 * Math.PI * i) / count;
        return centre.add(Math.cos(a) * radius, Math.sin(a) * radius);
    }

    /**
     * Slot indices ordered by how close they are to {@code from}, so a bot naturally takes the
     * side of the object it is already walking in from rather than crossing to the far side.
     */
    public List<Integer> nearestFirst(Coord2d from) {
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            order.add(i);
        if (from != null)
            order.sort((a, b) -> Double.compare(from.dist(at(a)), from.dist(at(b))));
        return order;
    }

    /**
     * True if a character could stand at slot {@code i} and get to it: not on water, not across a
     * cliff edge, and NOT INSIDE SOMETHING.
     *
     * The last of those was missing, and in a stockpile yard it is the one that decides. This
     * asked only about the ground, so a pile in the corner of a block - with its neighbours hard
     * against two of its sides - offered eight slots of which several stood inside those
     * neighbours. Every one of them looked fine here, got claimed, and was walked to; the
     * pathfinder then refused, {@link haven.automated.nbots.task.TakeWorkSlot} released the slot
     * and tried the next, and the trip was spent walking at a pile it could not reach from any of
     * the sides it kept choosing. Rejecting them costs one distance test each and turns that into
     * an immediate "no way in".
     *
     * Stockpiles in the way are measured at their FULL size ({@link Stockpile#FOOTPRINT}), because
     * the pile whose slot this is may be filling up while we walk to it - and so may the ones
     * around it. See {@link Walkers#occupied(List, Coord2d)}.
     *
     * Still a pre-filter and not a reachability proof: the honest answer needs a full path search,
     * far too expensive once per slot per candidate. A slot that passes here and then turns out to
     * be unwalkable simply fails to be reached, and the caller tries the next one.
     *
     * @param solids one object-cache snapshot for the whole ring, from {@link BotNav#solids}. Null
     *               to skip the object test - for a caller that only has terrain to go on.
     */
    public boolean plausible(GameUI gui, List<Gob> solids, int i) {
        if ((solids != null) && BotNav.occupied(solids, at(i)))
            return false;
        if (gui == null)
            return true;
        try {
            MCache mcache = gui.ui.sess.glob.map;
            Coord tc = at(i).floor(MCache.tilesz);
            Resource res = mcache.tilesetr(mcache.gettile(tc));
            if (res == null)
                return false;
            // The cave/nil/rocks trio was spelled out here too - a fourth copy, and the one most
            // likely to drift, being furthest from the routing code. It is Map.isImpassableGround
            // now, which is also what the click path and the map-file record ask.
            if (Map.isWater(res.name) || Map.isImpassableGround(res.name))
                return false;
            return !haven.resutil.Ridges.brokenp(mcache, tc);
        } catch (Loading e) {
            // Not loaded yet: don't rule it out on missing information. Worst case we walk over and
            // find out, which is the same cost as any other unreachable slot.
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Half the object's largest footprint dimension, from the collision box the pathfinder itself
     * uses. Taking the larger of the two axes rather than the exact rotated outline keeps the ring
     * circular: a ring that hugged an elongated hitbox would put some slots inside the object.
     */
    private static double halfExtent(Gob gob) {
        try {
            Resource res = gob.getres();
            if (res == null)
                return DEFAULT_EXTENT;
            /* A pile that is about to be filled is bigger than the box says, and the ring has to
             * clear it at that size or its own slots end up inside it. A FLOOR, not a
             * replacement - a resource whose box is larger keeps the larger one. */
            double floor = Stockpile.is(gob) ? Stockpile.FOOTPRINT : 0;
            HitBoxes.CollisionBoxSecondary[] boxes = HitBoxes.collisionBoxMap.get(res.name);
            if (boxes == null)
                return Math.max(floor, DEFAULT_EXTENT);
            double max = 0;
            for (HitBoxes.CollisionBoxSecondary box : boxes) {
                if (!box.hitAble || box.coords == null)
                    continue;
                for (Coord2d c : box.coords)
                    max = Math.max(max, Math.max(Math.abs(c.x), Math.abs(c.y)));
            }
            return Math.max(floor, (max > 0) ? max : DEFAULT_EXTENT);
        } catch (Loading | NullPointerException e) {
            return Math.max(Stockpile.is(gob) ? Stockpile.FOOTPRINT : 0, DEFAULT_EXTENT);
        }
    }

    public String toString() {
        return "slots(#" + gobid + " x" + count + " r" + (int) radius + ")";
    }
}
