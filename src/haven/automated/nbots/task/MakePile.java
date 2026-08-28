package haven.automated.nbots.task;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.WItem;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.Stockpile;
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
            if (tried++ >= MAX_SPOTS)
                break;
            Coord tile = Stockpile.tileOf(ctx.gui, spot);
            if (tile == null)
                continue;
            String key = Stockpile.spotKey(seg, tile);
            if (!WorkClaims.claim(key))
                continue;   // another client is already on its way to this square
            try {
                Outcome o = placeAt(ctx, spot);
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
     * Walks onto one square and tries to stand a pile there.
     *
     * The item goes to the cursor AFTER the walk, not before. Walking with a full hand is what
     * makes an interrupted trip messy - a right-click anywhere on the way drops it - and the walk
     * is by far the longest part of this.
     */
    private Outcome placeAt(BotCtx ctx, Coord2d spot) throws InterruptedException {
        ctx.status("Starting a new pile.");
        /* Aim BESIDE the square rather than at it. A character standing where a pile is going is
         * standing in its footprint, and the placement is refused with nothing to say why - which
         * from here is indistinguishable from any other refusal, so it would cost a walk per
         * square and end with "no free ground". nurgling2's PileMaker gets this right by pathing
         * to a dummy gob wearing the pile's own hitbox, i.e. to the edge of where the pile goes;
         * one tile back along the way we came is the same idea without needing the hitbox. */
        Gob me = ctx.player();
        Coord2d stand = spot;
        // A third of a tile, not a hair: norm() on a near-zero vector is NaN, and an aim of NaN is
        // a walk to nowhere that fails silently.
        if ((me != null) && (me.rc.dist(spot) > MCache.tilesz.x * 0.34))
            stand = spot.add(me.rc.sub(spot).norm(MCache.tilesz.x * 1.2));
        ctx.nav.stepTo(stand, MCache.tilesz.x);
        me = ctx.player();
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
            Gob pile = found(ctx, spot);
            if (pile == null)
                return Outcome.blocked("the placement was refused");
            made = pile;
            /* Let the fill that shift just started finish before anyone reads the pack again. The
             * caller decides what to do next from how much is still carried, and reading that
             * mid-transfer would have it building a second pile for soil already on its way into
             * this one. */
            ctx.nav.waitUntil(() -> PileTransfer.carrying(ctx, itemRes) <= 0, FILL_TICKS);
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
