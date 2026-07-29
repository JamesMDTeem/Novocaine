package haven.automated.nbots.task;

import haven.Coord;
import haven.Coord2d;
import haven.Equipory;
import haven.Gob;
import haven.Inventory;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.Tiler;
import haven.WItem;
import haven.automated.RefillWaterContainers;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;
import haven.automated.nbots.world.Reach;
import haven.resutil.WaterTile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static haven.OCache.posres;

/**
 * Fills the character's carried water containers from a place tagged for water.
 *
 * The mechanic this exists to respect: you DRINK from containers you are carrying, and you FILL
 * those from a barrel or a body of fresh water. So an empty waterskin is not the end of a run, it
 * is a trip - which is why this is a task a bot can compose rather than a stopping condition.
 *
 * Works from a {@link PlaceRoles#WATER} place rather than a stored coordinate, so several bots at
 * one site share one definition, and a crew working two sites can define one at each and each bot
 * takes its own nearest.
 *
 * Salt water is never a source. Filling up with something the character cannot drink would be
 * worse than failing, since the bot would then believe it had water and stop again immediately.
 */
public class FillWater implements Task {
    /** Fresh water you can fill from by standing in it. Salt water is deliberately absent. */
    private static final Alias FRESH = new Alias("fresh water",
        "gfx/tiles/water", "gfx/tiles/deep");
    private static final Alias BARREL = new Alias("barrel", "gfx/terobjs/barrel");

    /**
     * How many barrels one visit will walk between.
     *
     * There is no useful upper bound on how many barrels a player may line up in a cellar, and a
     * bot that tours forty of them to top off one skin has stopped doing the errand it was sent on.
     */
    private static final int MAX_BARRELS = 4;
    /** Polls (of 25ms) to let the gobs inside a place stream in before believing an empty scan. */
    private static final int SETTLE_TICKS = 40;
    /**
     * Polls to give the server a chance to walk us in to a container it thought we were too far
     * from. A ceiling on noticing that it did, not a delay - a character already at the barrel
     * never moves and this is spent in full, which is the price of not missing the step that does
     * come.
     */
    private static final int STEP_TICKS = 8;

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        Place place = Places.nearest(ctx.gui, PlaceRoles.WATER);
        if (place == null)
            return Outcome.failed("no place is tagged for water - define one in Bot Places");

        Outcome t = new TravelTo(place).run(ctx);
        if (!t.isOk())
            return t;

        ctx.status("Filling water.");
        return fillHere(ctx, place);
    }

    /**
     * Fills from whatever is here. Barrels first, since if the place has one that is plainly what
     * was meant; otherwise wade into the fresh water.
     *
     * Barrels are worked through in order rather than one being chosen and trusted. A barrel is a
     * finite amount of water and a crew shares it, so the one nearest the gate is routinely the one
     * that has just been drained - and filling three of a belt's five skins from it and calling the
     * task done sends the bot back out believing it has water, to stop again within the minute.
     * Moving on when a barrel stops yielding is the difference between "there was water here" and
     * "we have water".
     */
    private Outcome fillHere(BotCtx ctx, Place place) throws InterruptedException {
        List<Gob> barrels = waterBarrelsIn(ctx, place);
        /* Arriving and looking is not the same as arriving and seeing. The gobs inside a place
         * stream in over the moment or two after the walk ends, so a scan taken the instant travel
         * returned reports an empty base - which is the intermittent "no water barrel or fresh
         * water inside Water" against a place that works on the next run. Look again before
         * believing it. */
        if (barrels.isEmpty()) {
            // Waited out until one APPEARS rather than for a fixed moment: the same ceiling, but a
            // base that streams in promptly costs nothing instead of always costing the worst case.
            ctx.nav.waitUntil(() -> !waterBarrelsIn(ctx, place).isEmpty(), SETTLE_TICKS);
            barrels = waterBarrelsIn(ctx, place);
        }

        int tried = 0;
        for (Gob barrel : barrels) {
            if (!thirsty(ctx))
                return Outcome.ok();
            if (++tried > MAX_BARRELS) {
                ctx.log("stopping after " + MAX_BARRELS + " barrels in " + place.name);
                break;
            }
            // Re-read rather than trusting the scan: a crewmate may have emptied this one while we
            // walked to it, and an empty barrel is a wasted approach at best.
            Gob live = ctx.nav.gob(barrel.id);
            if (live == null || !holdsWater(live))
                continue;
            // Asked of the barrel rather than assumed: a flat eleven units is inside the barrel's
            // own collision box, so the approach could never satisfy it and every fill afterwards
            // was a use-attempt from just out of range. See Reach.
            double reach = Reach.toActOn(live);
            /* A SIDE of the barrel, reserved, rather than "walk at the barrel". Two bots sent for
             * water at the same moment otherwise aim at the same point, and the second arrives
             * behind the first and wedges it against the barrel: neither can then path anywhere,
             * because our own pathfinder blocks on every collision box including a character's, and
             * the one that is stuck is the one that was going to finish and leave. Which side each
             * takes is settled before either sets off - it cannot be settled by watching, since two
             * bots deciding are both idle and both far away. */
            TakeWorkSlot spot = new TakeWorkSlot(live, reach);
            try {
                Outcome a = spot.run(ctx);
                if (!a.isOk()) {
                    ctx.log("barrel #" + live.id + ": " + a.reason + " - trying the next one");
                    continue;
                }
                Gob me = ctx.player();
                ctx.log("filling from barrel #" + live.id + " at "
                    + (int) ((me == null) ? -1 : me.rc.dist(live.rc)) + "u (reach " + (int) reach
                    + "u, box " + (int) Reach.radius(live) + "u)");
                fill(ctx, null, live, spot);
            } finally {
                // Held for the whole fill, not just the walk - the point of the reservation is that
                // nobody else stands here WHILE we work, and a fill is the long part.
                spot.release();
            }
        }
        if (!thirsty(ctx))
            return Outcome.ok();
        /* Still short. Falling through to the water rather than reporting the barrels is on
         * purpose: a place with a barrel AND a lake in it has two sources and the player marked
         * both, so having drained the barrels is a reason to wade in, not a reason to give up. */

        boolean hadBarrels = !barrels.isEmpty();
        Gob me = ctx.player();
        if (me == null)
            return Outcome.blocked("no character");
        if (!onFreshWater(ctx, me.rc)) {
            Coord2d wet = nearestFreshWater(ctx, me.rc, 12);
            if (wet == null) {
                /* Two different answers, because they call for two different things from the
                 * player: an empty place needs a barrel or a different rectangle, whereas drained
                 * barrels just need filling. Reporting both as "nothing here" sent people looking
                 * for a mistake in the place they had defined correctly. */
                return hadBarrels
                    ? Outcome.blocked("every barrel in " + place.name + " is empty")
                    : Outcome.failed("no water barrel or fresh water inside " + place.name);
            }
            // The run-wide water avoidance is exactly wrong for the last few steps: filling from a
            // lake means standing IN it. Lifted just for the approach and put straight back, so the
            // journey home still routes around water like the rest of the run.
            haven.automated.pathfinder.Map.wade(this, true);
            try {
                if (!ctx.nav.stepTo(wet, 11 * 1.5))
                    return Outcome.blocked("couldn't wade in to the water");
            } finally {
                haven.automated.pathfinder.Map.wade(this, false);
            }
            me = ctx.player();
            if (me == null || !onFreshWater(ctx, me.rc))
                return Outcome.blocked("didn't end up standing in the water");
        }
        // Nothing to reserve at a lakeside: the shore is as long as the place is wide, so bots
        // spread along it by arriving at different points rather than by agreeing on one.
        fill(ctx, ctx.player().rc, null, null);
        return Outcome.ok();
    }

    // Reused purely for its container scanning: it already knows every water vessel, which
    // inventory/belt/pouch each lives in, and how full each one is. Its own run() is not used - it
    // loops until every container is full with no bound, which is fine for a menu button a player
    // pressed and not for something a bot calls unattended.
    private RefillWaterContainers containers(BotCtx ctx) {
        return new RefillWaterContainers(ctx.gui);
    }

    /**
     * True if anything we are carrying has room for more water.
     *
     * The three scans only ever report vessels that are NOT full - that is what the original loops
     * until - so a non-empty result is exactly "there is water to be fetched". Used at the start of
     * a shift, where setting out half-full only means breaking off sooner.
     */
    public static boolean thirsty(BotCtx ctx) {
        try {
            RefillWaterContainers scan = new RefillWaterContainers(ctx.gui);
            return !scan.getInventoryContainers().isEmpty()
                || !scan.getBeltContainers().isEmpty()
                || !scan.getEquiporyPouchContainers().isEmpty();
        } catch (RuntimeException e) {
            // Includes Loading. Not knowing is not a reason to make the trip.
            return false;
        }
    }

    /**
     * Empties every carried container into the source and puts each one back where it came from.
     *
     * Bounded on passes rather than looping until everything reads full: a container that for any
     * reason never registers as filled would otherwise spin here forever on a bot thread,
     * unattended. Three passes covers a full belt, and stopping early only means the bot fills up
     * again next time it runs dry.
     */
    private void fill(BotCtx ctx, Coord2d tile, Gob barrel, TakeWorkSlot spot)
            throws InterruptedException {
        RefillWaterContainers scan = containers(ctx);
        Inventory belt = scan.returnBelt();
        Equipory equipory = ctx.gui.getequipory();

        for (int pass = 0; pass < 3; pass++) {
            boolean any = false;
            // A full belt takes longer than a reservation stands, so say we still want it. Cheap
            // enough to call every pass; it does nothing until the renewal interval is up.
            if (spot != null)
                spot.renew();

            for (java.util.Map.Entry<WItem, Coord> e : scan.getInventoryContainers().entrySet()) {
                any |= fillOne(ctx, e.getKey(), tile, barrel,
                    () -> ctx.gui.maininv.wdgmsg("drop", e.getValue()));
            }
            if (belt != null) {
                for (java.util.Map.Entry<WItem, Coord> e : scan.getBeltContainers().entrySet()) {
                    any |= fillOne(ctx, e.getKey(), tile, barrel,
                        () -> belt.wdgmsg("drop", e.getValue()));
                }
            }
            if (equipory != null) {
                for (java.util.Map.Entry<WItem, Integer> e : scan.getEquiporyPouchContainers().entrySet()) {
                    any |= fillOne(ctx, e.getKey(), tile, barrel,
                        () -> equipory.wdgmsg("drop", e.getValue()));
                }
            }

            if (!any)
                return;
            // Let the server catch up before re-reading how full everything is, or the next pass
            // re-fills containers that are already done.
            ctx.nav.pause(12);
        }
    }

    private boolean fillOne(BotCtx ctx, WItem item, Coord2d tile, Gob barrel, Runnable putBack)
            throws InterruptedException {
        if (item == null)
            return false;
        item.item.wdgmsg("take", Coord.z);
        ctx.nav.waitUntil(() -> ctx.gui.vhand != null, 20);
        if (ctx.gui.vhand == null)
            return false;

        if (barrel == null)
            ctx.gui.map.wdgmsg("itemact", Coord.z, tile.floor(posres), 0);
        else
            ctx.gui.map.wdgmsg("itemact", Coord.z, barrel.rc.floor(posres), 4, 0, (int) barrel.id,
                barrel.rc.floor(posres), 0, -1);

        /* If the game decided we were too far and walked us in, let that finish before the next
         * container goes in. Without this, every container in the belt issues its own step and they
         * queue up on each other - which is the stutter, seen from the outside. Bounded, because a
         * character who is already close enough never moves at all and must not wait for it.
         *
         * Whether it decided to is the server's answer, so it is waited FOR rather than guessed at.
         * The fixed pause this replaces got it wrong in both directions: a character standing at
         * the barrel paid it for nothing, and one whose step began a moment later than it had that
         * step read as already over - which is precisely the queueing the wait exists to stop. */
        ctx.nav.waitUntil(() -> ctx.nav.walking(), STEP_TICKS);
        ctx.nav.waitUntil(() -> !ctx.nav.walking(), 40);
        putBack.run();
        ctx.nav.waitUntil(() -> ctx.gui.vhand == null, 20);
        return true;
    }

    // ------------------------------------------------------------------ finding water

    /** Every barrel inside the place that says it holds water, nearest first. */
    private List<Gob> waterBarrelsIn(BotCtx ctx, Place place) {
        Gob me = ctx.player();
        List<Gob> out = new ArrayList<>();
        for (Gob g : place.gobsWithin(ctx.gui, BARREL)) {
            if (holdsWater(g))
                out.add(g);
        }
        if (me != null)
            out.sort(Comparator.comparingDouble(g -> g.rc.dist(me.rc)));
        return out;
    }

    /**
     * Whether a barrel is advertising water.
     *
     * A barrel says what is in it with an overlay sprite named gfx/terobjs/barrel-&lt;contents&gt;,
     * and drops the overlay when it empties - so this is also the "has it run dry" test, which is
     * why it is read again per barrel rather than once per trip.
     */
    private boolean holdsWater(Gob g) {
        if (g == null)
            return false;
        try {
            Optional<String> contents = g.ols.stream()
                .map(Gob.Overlay::getSprResName)
                .filter(n -> n != null && n.startsWith("gfx/terobjs/barrel-"))
                .map(n -> n.substring(n.lastIndexOf('-') + 1))
                .findAny();
            return contents.isPresent() && "water".equals(contents.get());
        } catch (Loading | NullPointerException e) {
            return false;
        }
    }

    private boolean onFreshWater(BotCtx ctx, Coord2d wc) {
        try {
            MCache mcache = ctx.gui.ui.sess.glob.map;
            int t = mcache.gettile(wc.floor(MCache.tilesz));
            Tiler tl = mcache.tiler(t);
            if (!(tl instanceof WaterTile))
                return false;
            Resource res = mcache.tilesetr(t);
            return res != null && FRESH.matches(res.name);
        } catch (Loading | NullPointerException e) {
            return false;
        }
    }

    /**
     * The nearest fresh-water tile, searched outwards in rings so the first hit is genuinely the
     * closest rather than merely the first in scan order.
     */
    private Coord2d nearestFreshWater(BotCtx ctx, Coord2d from, int tiles) {
        Coord base = from.floor(MCache.tilesz);
        for (int r = 0; r <= tiles; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r)
                        continue;
                    Coord2d wc = base.add(dx, dy).mul(MCache.tilesz).add(MCache.tilesz.div(2));
                    if (onFreshWater(ctx, wc))
                        return wc;
                }
            }
        }
        return null;
    }

    @Override
    public String label() {
        return "fill water";
    }
}
