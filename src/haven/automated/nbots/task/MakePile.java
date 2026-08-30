package haven.automated.nbots.task;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.WItem;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.BotNav;
import haven.automated.nbots.world.Crowd;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.Stockpile;
import haven.automated.nbots.world.Walk;
import haven.automated.nbots.world.WorkClaims;

import java.util.List;

import static haven.OCache.posres;

/**
 * Stands a new stockpile on free ground inside a place, next to the ones already there.
 *
 * This is what a bot does when every pile in the destination area is full - which, with a crew
 * working one yard, is a thing that happens between deciding to unload and arriving to unload. So
 * it is a normal step in the round trip and not a recovery path.
 *
 * <h2>Two bots must not place on the same square</h2>
 *
 * Nothing observable separates two idle bots that pick the same empty square: they are both a long
 * way off at the moment they choose, and the square is empty until one of them arrives. That is the
 * same gap {@link WorkClaims} closes for standing positions, so this uses the same registry, keyed
 * on the map SEGMENT tile ({@link Stockpile#spotKey}) so every client agrees which square is meant.
 * The claim is held across the walk - which is the whole point, since the walk is the window - and
 * released once the pile exists and is therefore visible to everyone.
 *
 * Candidates are tried in turn rather than one being chosen, because a square can fail for reasons
 * no client can see in advance: the server has its own idea of what a pile's footprint needs, and
 * refuses the placement by simply not creating anything. A refused square costs one attempt and the
 * next is tried.
 *
 * <h2>Stockpile form takes movement away</h2>
 *
 * Once the held item has been turned into its stockpile form, the character will not walk - so the
 * whole build has to be done from a standing start, in a straight line to the square, with nothing
 * in between that asks the pathfinder for anything. That inverts the obvious order: arriving is a
 * PRECONDITION checked strictly before the cursor is touched, not something to attempt and then
 * make the best of. A walk that stops short must abandon the square rather than toggle from out of
 * range, because the refusal that follows leaves a bot that cannot walk out of its own mistake.
 *
 * <h2>The hand is never left holding anything</h2>
 *
 * Placing needs the item ON THE CURSOR, and every failure path from that point leaves it there. A
 * bot that walks away with a full hand cannot open a container, cannot take a slot, and drops the
 * item at the first right-click it makes - which is why unwinding it is in a finally rather than at
 * the ends of the happy path. nurgling2's PileMaker returns FAIL from three places with the item
 * still on the cursor.
 */
public class MakePile implements Task {
    /** Polls to wait for the item to land on the cursor after asking for it. */
    private static final int TAKE_TICKS = 40;
    /** Polls to wait for the server to arm a placement after the ground click. */
    private static final int ARM_TICKS = 80;
    /** Polls to wait for the new pile to appear after committing the placement. */
    private static final int PLACE_TICKS = 120;
    /**
     * Polls to wait for the shift-place fill to drain the pack.
     *
     * Expiry is an ordinary outcome, not a timeout to worry about: a pack holding more than one
     * pile's worth SHOULD still have items in it afterwards, and the caller reads how many and
     * builds another. The wait is only there to stop it reading the number too early.
     */
    private static final int FILL_TICKS = 200;
    /** How near the aimed square a new gob has to be to count as the one we just placed. */
    private static final double PLACED_NEAR = MCache.tilesz.x * 1.2;
    /** How many squares to try before giving up on this trip. */
    private static final int MAX_SPOTS = 6;
    /** How far back from the square to stand while placing on it. See {@link #approachFrom}. */
    private static final double STAND_BACK = MCache.tilesz.x * 1.2;
    /** How many headings around the square to consider standing on, and how far apart they are. */
    private static final int STAND_HEADINGS = 8;
    private static final double STAND_FAN = Math.PI / 4;
    /**
     * How near the square counts as standing ON it, for picking a direction to step back in.
     *
     * A third of a tile, not a hair: {@code norm()} on a near-zero vector is NaN, and an aim of
     * NaN is a walk to nowhere that fails silently.
     */
    private static final double ON_SQUARE = MCache.tilesz.x * 0.34;

    private final Place place;
    private final String itemRes;
    private final String pileRes;
    private final Coord2d near;

    private Gob made;

    /**
     * @param place   where the pile is allowed to go
     * @param itemRes the resource of the item to make it from - one is taken to hand
     * @param pileRes the pile resource the new one should join a row of, or null for anywhere free.
     *                Supplied by the caller rather than derived from {@code itemRes}, because an
     *                item does not name its pile: nurgling2 needs a thirteen-branch table for that
     *                mapping and still has no answer for a pile it has not been taught about. The
     *                caller always knows it, having just come from a pile of exactly this kind.
     * @param near    somewhere to prefer being close to, normally the pile that just filled up
     */
    public MakePile(Place place, String itemRes, String pileRes, Coord2d near) {
        this.place = place;
        this.itemRes = itemRes;
        this.pileRes = pileRes;
        this.near = near;
    }

    /** The pile that was created, or null if none was. */
    public Gob made() {
        return made;
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        if ((place == null) || (itemRes == null))
            return Outcome.failed("nowhere to put a new pile");
        if (PileTransfer.carrying(ctx, itemRes) <= 0)
            return Outcome.failed("nothing to make a pile out of");

        List<Coord2d> spots = Stockpile.spotsIn(ctx.gui, place, pileRes, near);
        if (spots.isEmpty())
            return Outcome.blocked("no free ground left in " + place.name);

        long seg = Stockpile.segment(ctx.gui);
        int tried = 0;
        for (Coord2d spot : spots) {
            if (!ctx.running())
                throw new InterruptedException();
            if (tried >= MAX_SPOTS)
                break;
            Coord tile = Stockpile.tileOf(ctx.gui, spot);
            if (tile == null)
                continue;
            String key = Stockpile.spotKey(seg, tile);
            /* Counted only once we are actually going to walk somewhere.
             *
             * The increment used to happen before this, so a square a crewmate had already claimed
             * spent one of the six attempts without a step being taken - and claimed squares come
             * in runs, since both bots sort the same candidate list the same way. Six of those and
             * the trip reported "couldn't find ground" standing in an empty yard. */
            if (!WorkClaims.claim(key))
                continue;   // another client is already on its way to this square
            tried++;
            try {
                Outcome o = placeAt(ctx, spot, key);
                if (o.isOk())
                    return o;
                ctx.log("couldn't start a pile at " + tile + ": " + o.reason);
            } finally {
                WorkClaims.release(key);
            }
        }
        return Outcome.blocked("couldn't find ground for a new pile in " + place.name);
    }

    /**
     * Somewhere to stand while placing on {@code spot}, with a clear straight line to it.
     *
     * Three things have to hold and only the first was ever checked. The standing position must be
     * off the square - a character inside a pile's footprint has its placement refused with nothing
     * said - it must be ground we could actually be on, and THE LINE BETWEEN THE TWO MUST BE CLEAR.
     *
     * The last is the one that was missing, and it is the one that cannot be recovered from.
     * {@link Stockpile#spotsIn} validates the square a pile is going ON and says nothing about the
     * ground beside it or what lies between; the old code took a single position one tile back
     * along the way we had come and committed to it. When something was in the way - the pile we
     * had just filled, a crewmate, a fence corner - the walk stopped short, the distance gate
     * refused, and the square was written off although it was perfectly good. When something was
     * in the way but the walk still finished, worse: the toggle happened, the placement was
     * refused through the obstacle, and the character was left in stockpile form, which is the one
     * state it cannot walk out of.
     *
     * So the standing position is chosen rather than derived. Straight back the way we came first,
     * because that is the shortest walk and usually right, then fanning to either side. Each
     * candidate is tested for something standing on it and for a clear line to the square, and the
     * first that passes both is the one we walk to.
     *
     * Null - no side of the square is usable - is BLOCKED and not a retirement. Every reason for it
     * is something that moves: a crewmate on one side and the pile we just filled on the other is a
     * yard doing exactly what it should, and it will read differently in a minute. Only the server
     * refusing the placement itself says anything durable about the ground.
     */
    private static Coord2d approachFrom(BotCtx ctx, Coord2d spot) {
        Gob me = ctx.player();
        if (me == null)
            return null;
        /* The way we came, or due east when we are standing on the square itself. norm() on a
         * near-zero vector is NaN, and an aim of NaN is a walk to nowhere that fails silently. */
        Coord2d back = me.rc.sub(spot);
        back = (back.abs() < ON_SQUARE) ? new Coord2d(STAND_BACK, 0) : back.norm(STAND_BACK);
        List<Gob> solids = BotNav.solids(ctx.gui);
        List<Gob> crowd = Crowd.others(ctx.gui);
        for (int i = 0; i < STAND_HEADINGS; i++) {
            // Turns of 0, -45, +45, -90, +90 ... so the way we came is tried first, either side
            // of it next, and the far side of the square last.
            double turn = ((i + 1) / 2) * STAND_FAN * (((i % 2) == 0) ? 1 : -1);
            Coord2d at = spot.add(back.rot(turn));
            if (BotNav.occupied(solids, at))
                continue;
            if (Crowd.occupied(crowd, at, Crowd.PERSONAL_SPACE))
                continue;
            if (!Walk.lineClear(ctx.gui, at, spot))
                continue;
            return at;
        }
        return null;
    }

    /**
     * Walks onto one square and tries to stand a pile there.
     *
     * The item goes to the cursor AFTER the walk, not before. Walking with a full hand is what
     * makes an interrupted trip messy - a right-click anywhere on the way drops it - and the walk
     * is by far the longest part of this.
     */
    private Outcome placeAt(BotCtx ctx, Coord2d spot, String key) throws InterruptedException {
        ctx.status("Starting a new pile.");
        /* Stand BESIDE the square, on ground with a clear line to it. A character standing where a
         * pile is going is standing in its footprint, and the placement is refused with nothing to
         * say why - which from here is indistinguishable from any other refusal, so it would cost a
         * walk per square and end with "no free ground". nurgling2's PileMaker gets this right by
         * pathing to a dummy gob wearing the pile's own hitbox, i.e. to the edge of where the pile
         * goes; a tile back from it is the same idea without needing the hitbox. Which tile back is
         * {@link #approachFrom}'s to choose - see there for why it is a choice and not a formula. */
        Coord2d stand = approachFrom(ctx, spot);
        if (stand == null)
            return Outcome.blocked("nowhere clear to stand beside the square");
        ctx.nav.stepTo(stand, MCache.tilesz.x);
        WorkClaims.renew(key);
        Gob me = ctx.player();
        double gap = (me == null) ? -1 : me.rc.dist(spot);
        /* BE THERE BEFORE TOUCHING THE CURSOR, and be strict about it.
         *
         * Turning the held item into its stockpile form takes movement away: while the cursor is
         * in that form the character will not walk, so there is no correcting the position
         * afterwards and no pathing between the toggle and the click. Everything from here to the
         * commit has to happen from where we are already standing, in a straight line to the
         * square - which makes arriving a precondition rather than something to attempt.
         *
         * The gate used to be four tiles, which is not "arrived" by any reading; it was chosen to
         * let a walk that stopped a little short carry on anyway. That is the right instinct for a
         * target you can walk at again and exactly the wrong one here: stopping short meant
         * toggling from out of range, the commit being refused, and the bot standing in placement
         * form unable to walk - one wedged bot per candidate square. Two tiles covers standing
         * beside the square (the aim above is 1.2 tiles off it) and nothing more.
         *
         * Blocked, not failed: the square is fine, we simply are not on it. The caller tries the
         * next one and this one comes round again. */
        if ((me == null) || (gap > MCache.tilesz.x * 2)) {
            ctx.log("not close enough to start a pile: " + (int) gap + "u from the square"
                + " (need " + (int) (MCache.tilesz.x * 2) + "u) - leaving the cursor alone");
            return Outcome.blocked("couldn't get to the spot");
        }
        /* Re-checked from WHERE WE ACTUALLY ARE, not from where we meant to be.
         *
         * The line was cleared for the position approachFrom() picked, and a walk does not always
         * finish on it: it can stop a little short, or be nudged around a crewmate who arrived
         * while we were coming. Both leave us inside the distance gate above with something now
         * between us and the square. This is the last moment that can be checked, because the next
         * gesture takes movement away for good. */
        if (!Walk.lineClear(ctx.gui, me.rc, spot)) {
            ctx.log("something moved between us and the square - not toggling from here");
            return Outcome.blocked("the line to the spot isn't clear");
        }

        List<WItem> have = PileTransfer.carried(ctx, itemRes);
        if (have.isEmpty())
            return Outcome.failed("nothing left to make a pile out of");

        /* From here to the finally, something is on the cursor - and a bot that walks away from
         * this method still holding it cannot move at all, because every step it takes is a map
         * click and a map click with a full cursor drops the item instead of walking. So the
         * unwind is a finally around the WHOLE of the rest, and it is {@link StowHand} rather
         * than an inline check: the item may still be in flight when a failure path is taken, and
         * a placement ghost has to come down before the server will give the item back. */
        try {
            have.get(0).item.wdgmsg("take", Coord.z);
            ctx.nav.waitUntil(() -> StowHand.held(ctx), TAKE_TICKS);
            if (!StowHand.held(ctx))
                return Outcome.blocked("couldn't pick the item up");

            /* Right-clicking the ground with the item held is what turns it into stockpile form;
             * the server answers with the ghost, and only then is there anything to commit.
             *
             * MOVEMENT ENDS HERE. Nothing between this line and the commit below may walk, ask the
             * pathfinder for anything, or wait on a position - the character cannot move while the
             * cursor is in this form, so anything that waits for it to get somewhere waits for
             * ever. The distance gate above is what earns the right to be here. */
            ctx.gui.map.wdgmsg("itemact", Coord.z, spot.floor(posres), 0);
            ctx.nav.waitUntil(() -> StowHand.armed(ctx), ARM_TICKS);
            WorkClaims.renew(key);
            if (!StowHand.armed(ctx))
                return Outcome.blocked("the game didn't offer to place anything");

            /* Shift-place, so the new pile is filled from the pack on creation.
             *
             * A plain place makes a pile out of the one clod on the cursor and leaves the other
             * thirty in the pack, which then need a second gesture - and, before this, a whole
             * second visit to a pile that had only just been built two paces away. Shift folds the
             * build and the fill into the one click. */
            Stockpile.placeAll(ctx, spot);
            ctx.nav.waitUntil(() -> found(ctx, spot) != null, PLACE_TICKS);
            WorkClaims.renew(key);
            Gob pile = found(ctx, spot);
            if (pile == null) {
                /* The server said no by saying nothing, and there is no reading of that to act on.
                 * Nothing the client can see about this ground has changed, so the next trip's
                 * candidate list - which is deterministic and sorted the same way - would offer
                 * this square first again and walk to it again. Remembering the refusal for the
                 * retire TTL is the only thing that makes the next attempt different. */
                Coord tile = Stockpile.tileOf(ctx.gui, spot);
                if (tile != null)
                    Stockpile.retireSpot(Stockpile.spotKey(Stockpile.segment(ctx.gui), tile));
                return Outcome.blocked("the placement was refused");
            }
            made = pile;
            /* Let the fill that shift just started finish before anyone reads the pack again. The
             * caller decides what to do next from how much is still carried, and reading that
             * mid-transfer would have it building a second pile for soil already on its way into
             * this one. */
            ctx.nav.waitUntil(() -> PileTransfer.carrying(ctx, itemRes) <= 0, FILL_TICKS);
            WorkClaims.renew(key);
            ctx.log("started a new " + Stockpile.kind(pile) + " pile in " + place.name
                + "; " + PileTransfer.carrying(ctx, itemRes) + " left in the pack");
            return Outcome.ok();
        } finally {
            new StowHand().run(ctx);
        }
    }

    /** A stockpile standing on the square we just aimed at, if one has appeared. */
    private static Gob found(BotCtx ctx, Coord2d spot) {
        synchronized (ctx.gui.map.glob.oc) {
            for (Gob g : ctx.gui.map.glob.oc) {
                if (Stockpile.is(g) && (g.rc.dist(spot) <= PLACED_NEAR))
                    return g;
            }
        }
        return null;
    }

    @Override
    public String label() {
        return "make a pile";
    }
}
