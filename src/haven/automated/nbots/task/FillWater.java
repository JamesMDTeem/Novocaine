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
import haven.resutil.WaterTile;

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

    private static final double BARREL_REACH = 11 * 1.0;

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
     * Fills from whatever is here. A barrel first, since if the place has one that is plainly what
     * was meant; otherwise wade into the fresh water.
     */
    private Outcome fillHere(BotCtx ctx, Place place) throws InterruptedException {
        Gob barrel = waterBarrelIn(ctx, place);
        if (barrel != null) {
            Gob me = ctx.player();
            if (me != null && me.rc.dist(barrel.rc) > BARREL_REACH) {
                Outcome a = new Approach(barrel, BARREL_REACH).run(ctx);
                if (!a.isOk())
                    return a;
            }
            fill(ctx, null, barrel);
            return Outcome.ok();
        }

        Gob me = ctx.player();
        if (me == null)
            return Outcome.blocked("no character");
        if (!onFreshWater(ctx, me.rc)) {
            Coord2d wet = nearestFreshWater(ctx, me.rc, 12);
            if (wet == null)
                return Outcome.failed("no water barrel or fresh water inside " + place.name);
            // The run-wide water avoidance is exactly wrong for the last few steps: filling from a
            // lake means standing IN it. Lifted just for the approach and put straight back, so the
            // journey home still routes around water like the rest of the run.
            boolean prev = haven.automated.pathfinder.Map.BLOCK_WATER;
            haven.automated.pathfinder.Map.BLOCK_WATER = false;
            try {
                if (!ctx.nav.stepTo(wet, 11 * 1.5))
                    return Outcome.blocked("couldn't wade in to the water");
            } finally {
                haven.automated.pathfinder.Map.BLOCK_WATER = prev;
            }
            me = ctx.player();
            if (me == null || !onFreshWater(ctx, me.rc))
                return Outcome.blocked("didn't end up standing in the water");
        }
        fill(ctx, ctx.player().rc, null);
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
    private void fill(BotCtx ctx, Coord2d tile, Gob barrel) throws InterruptedException {
        RefillWaterContainers scan = containers(ctx);
        Inventory belt = scan.returnBelt();
        Equipory equipory = ctx.gui.getequipory();

        for (int pass = 0; pass < 3; pass++) {
            boolean any = false;

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

        ctx.nav.pause(3);
        putBack.run();
        ctx.nav.waitUntil(() -> ctx.gui.vhand == null, 20);
        return true;
    }

    // ------------------------------------------------------------------ finding water

    /** The nearest barrel inside the place whose contents overlay says it holds water. */
    private Gob waterBarrelIn(BotCtx ctx, Place place) {
        Gob me = ctx.player();
        Gob best = null;
        for (Gob g : place.gobsWithin(ctx.gui, BARREL)) {
            try {
                // A barrel advertises what's in it with an overlay sprite named
                // gfx/terobjs/barrel-<contents>. No overlay means empty.
                Optional<String> contents = g.ols.stream()
                    .map(Gob.Overlay::getSprResName)
                    .filter(n -> n != null && n.startsWith("gfx/terobjs/barrel-"))
                    .map(n -> n.substring(n.lastIndexOf('-') + 1))
                    .findAny();
                if (!contents.isPresent() || !"water".equals(contents.get()))
                    continue;
                if (best == null || (me != null && g.rc.dist(me.rc) < best.rc.dist(me.rc)))
                    best = g;
            } catch (Loading | NullPointerException ignored) {
            }
        }
        return best;
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
