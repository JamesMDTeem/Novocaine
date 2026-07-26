package haven.automated.nbots;

import haven.Coord;
import haven.Coord2d;
import haven.Equipory;
import haven.GameUI;
import haven.Gob;
import haven.Inventory;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.Tiler;
import haven.WItem;
import haven.automated.RefillWaterContainers;
import haven.automated.lp.NLog;
import haven.automated.pathfinder.Map;
import haven.resutil.WaterTile;

import java.util.Optional;

import static haven.OCache.posres;

/**
 * Going and getting water when the character has drunk everything they're carrying.
 *
 * The mechanic this has to respect, and the reason it isn't just "walk to water and drink": you
 * drink from CONTAINERS on your person - waterskin, flask, glass jug - and you fill those from a
 * barrel or a body of fresh water. So running dry mid-job isn't a reason to stop, it's a round
 * trip: leave the work site, go to the source, fill everything, come back, carry on.
 *
 * The hard part is that the trip is long enough for the destination not to exist as far as the
 * client is concerned. Gobs only exist while they're rendered, so the barrel back at base is not a
 * Gob you can hold on to, and even its raw coordinates are relative to a map origin that can move.
 * {@link WorldAnchor} is the answer - the source is remembered the same way the client's own map
 * markers are, as a position inside a map segment - and {@link BotNav#travelTo} walks it in hops
 * because the pathfinder's window is only 88 tiles across.
 *
 * The source can be learned (stand next to a barrel or on fresh water and the bots remember it),
 * set explicitly, or pointed at a named custom map marker. Salt water is never a source: you can't
 * drink it, and quietly filling up with it would be worse than failing.
 */
public class WaterService {
    /** Fresh water you can fill from by standing in it. Salt water is deliberately absent. */
    private static final java.util.Set<String> FRESH = new java.util.HashSet<>(java.util.Arrays.asList(
        "gfx/tiles/water", "gfx/tiles/deep"));

    /** How close we need to be to a barrel to fill from it. */
    private static final double BARREL_REACH = 11 * 1.0;
    /** How close travel has to get to the source anchor before we start looking around for it. */
    private static final double ARRIVE_TOL = 11 * 3.0;
    /** How close the return trip has to get before the bot resumes work. */
    private static final double RETURN_TOL = 11 * 4.0;

    public enum Result {
        OK(null),
        NO_SOURCE("no water source known - stand by a barrel or fresh water and press "
            + "\"Set water source here\", or name a map marker in the bot settings."),
        UNREACHABLE("the water source is on a different part of the map and can't be walked to."),
        NOT_FOUND("walked to the water source but found no barrel or fresh water there."),
        BLOCKED("couldn't walk to the water source."),
        LOST("refilled, but couldn't find the way back to the work site.");

        public final String message;

        Result(String message) {
            this.message = message;
        }
    }

    private final GameUI gui;
    private final BotNav nav;
    private final String log;
    private final RefillWaterContainers containers;

    public WaterService(GameUI gui, BotNav nav, String log) {
        this.gui = gui;
        this.nav = nav;
        this.log = log;
        // Reused purely for its container scanning - it already knows every water vessel, which
        // inventory/belt/pouch each lives in, and how full each one is. Its own run() is not used:
        // it loops until every container is full with no bound, which is fine for a menu button a
        // player pressed but not for something a bot calls unattended.
        this.containers = new RefillWaterContainers(gui);
    }

    // ------------------------------------------------------------------ the source

    /** The source to use: an explicitly named map marker if set, otherwise the remembered one. */
    public WorldAnchor source() {
        String marker = NBotConfig.waterMarker();
        if (marker != null && !marker.isEmpty()) {
            WorldAnchor m = WorldAnchor.ofMarker(gui, marker);
            if (m != null)
                return m;
            // Fall through rather than fail: a typo in the marker name shouldn't strand a bot that
            // also has a perfectly good remembered source.
            NLog.log(log, "no map marker named '" + marker + "' - using the remembered source");
        }
        return NBotConfig.waterSource();
    }

    /**
     * Records where the player is standing as the water source, if there's actually water here.
     *
     * @return true if a source was recorded.
     */
    public boolean learnHere() {
        Gob me = (gui.map == null) ? null : gui.map.player();
        if (me == null)
            return false;
        Gob barrel = nearestWaterBarrel(me.rc, 11 * 3.0);
        Coord2d spot = (barrel != null) ? standingSpotFor(barrel) : (onFreshWater(me.rc) ? me.rc : null);
        if (spot == null)
            return false;
        WorldAnchor a = WorldAnchor.capture(gui, spot);
        if (a == null)
            return false;
        NBotConfig.waterSource(a);
        NLog.log(log, "learned water source " + a + (barrel != null ? " (barrel)" : " (fresh water)"));
        return true;
    }

    /**
     * A spot next to a barrel rather than inside it. Aimed at the barrel's own tile centre offset
     * one tile towards where we are, so the walk ends adjacent rather than trying to path into a
     * solid object and stopping short at a random side.
     */
    private Coord2d standingSpotFor(Gob barrel) {
        Gob me = gui.map.player();
        Coord2d dir = (me == null) ? new Coord2d(1, 0) : me.rc.sub(barrel.rc);
        double d = dir.abs();
        dir = (d < 1.0) ? new Coord2d(1, 0) : dir.div(d);
        return barrel.rc.add(dir.mul(11.0));
    }

    // ------------------------------------------------------------------ the trip

    /**
     * The whole round trip: remember where we are, go to the source, fill everything, come back.
     *
     * Returning is treated as part of the job rather than a nicety. A bot that refilled and then
     * carried on from wherever the water was would start working a completely different patch of
     * forest, which is both surprising and - with several bots sharing a site - a good way to have
     * them all wander off in different directions.
     */
    public Result refill() throws InterruptedException {
        WorldAnchor src = source();
        if (src == null) {
            // Last chance: we might be standing at a source right now (a bot started at base), in
            // which case there's nothing to travel to and everything to learn.
            if (learnHere())
                src = source();
            if (src == null)
                return Result.NO_SOURCE;
        }
        if (src.resolve(gui) == null)
            return Result.UNREACHABLE;

        WorldAnchor home = WorldAnchor.capturePlayer(gui);
        NLog.log(log, "water run: " + home + " -> " + src);

        if (!nav.travelTo(src, ARRIVE_TOL)) {
            NLog.log(log, "water run: couldn't reach the source");
            return Result.BLOCKED;
        }

        Result fill = fillHere();
        if (fill != Result.OK)
            return fill;

        if (home == null) {
            // We refilled, which is the important half; we just can't say where to go back to.
            // Reported rather than silently ignored, because the bot will now be working wherever
            // the water is instead of where it was sent.
            return Result.LOST;
        }
        if (!nav.travelTo(home, RETURN_TOL)) {
            NLog.log(log, "water run: refilled but couldn't get back to " + home);
            return Result.LOST;
        }
        NLog.log(log, "water run: complete");
        return Result.OK;
    }

    /**
     * Fills every carried container from whatever is here. Barrel first, since if there's one at
     * the source that's what was meant; otherwise wade into the fresh water.
     */
    private Result fillHere() throws InterruptedException {
        Gob me = gui.map.player();
        if (me == null)
            return Result.NOT_FOUND;

        Gob barrel = nearestWaterBarrel(me.rc, 11 * 8.0);
        if (barrel != null) {
            if (me.rc.dist(barrel.rc) > BARREL_REACH && !nav.approach(barrel, BARREL_REACH))
                return Result.BLOCKED;
            fillFrom(null, barrel);
            return Result.OK;
        }

        if (!onFreshWater(me.rc)) {
            Coord2d wet = nearestFreshWater(me.rc, 10);
            if (wet == null)
                return Result.NOT_FOUND;
            // The run-wide water avoidance is exactly wrong for the last few steps here: filling
            // from a lake means standing IN it. Lifted just for the approach and put straight back,
            // so the return trip still routes around water like the rest of the run.
            boolean prev = Map.BLOCK_WATER;
            Map.BLOCK_WATER = false;
            try {
                if (!nav.stepTo(wet, 11 * 1.5))
                    return Result.BLOCKED;
            } finally {
                Map.BLOCK_WATER = prev;
            }
            me = gui.map.player();
            if (me == null || !onFreshWater(me.rc))
                return Result.NOT_FOUND;
        }
        fillFrom(gui.map.player().rc, null);
        return Result.OK;
    }

    /**
     * Empties every carried water container into the source and puts each one back where it came
     * from.
     *
     * Bounded on passes rather than looping until everything reads full: a container that for any
     * reason never registers as filled (an item whose info won't resolve, a source that turns out
     * not to be usable) would otherwise spin here forever on a bot thread, unattended. Three passes
     * is more than enough for a full belt, and stopping early just means the bot tries again when
     * it next runs dry.
     */
    private void fillFrom(Coord2d tile, Gob barrel) throws InterruptedException {
        Inventory belt = containers.returnBelt();
        Equipory equipory = gui.getequipory();

        for (int pass = 0; pass < 3; pass++) {
            boolean any = false;

            for (java.util.Map.Entry<WItem, Coord> e : containers.getInventoryContainers().entrySet()) {
                any |= fillOne(e.getKey(), tile, barrel, () -> gui.maininv.wdgmsg("drop", e.getValue()));
            }
            if (belt != null) {
                for (java.util.Map.Entry<WItem, Coord> e : containers.getBeltContainers().entrySet()) {
                    Inventory b = belt;
                    any |= fillOne(e.getKey(), tile, barrel, () -> b.wdgmsg("drop", e.getValue()));
                }
            }
            if (equipory != null) {
                for (java.util.Map.Entry<WItem, Integer> e : containers.getEquiporyPouchContainers().entrySet()) {
                    any |= fillOne(e.getKey(), tile, barrel, () -> equipory.wdgmsg("drop", e.getValue()));
                }
            }

            if (!any)
                return;
            // Let the server catch up before re-reading how full everything is, or the next pass
            // re-fills containers that are already done.
            nav.pause(12);
        }
    }

    private boolean fillOne(WItem item, Coord2d tile, Gob barrel, Runnable putBack)
            throws InterruptedException {
        if (item == null)
            return false;
        item.item.wdgmsg("take", Coord.z);
        nav.waitUntil(() -> gui.vhand != null, 20);
        if (gui.vhand == null)
            return false;

        if (barrel == null)
            gui.map.wdgmsg("itemact", Coord.z, tile.floor(posres), 0);
        else
            gui.map.wdgmsg("itemact", Coord.z, barrel.rc.floor(posres), 4, 0, (int) barrel.id,
                barrel.rc.floor(posres), 0, -1);

        nav.pause(3);
        putBack.run();
        nav.waitUntil(() -> gui.vhand == null, 20);
        return true;
    }

    // ------------------------------------------------------------------ finding water

    /** The nearest barrel whose contents overlay says it holds water. */
    private Gob nearestWaterBarrel(Coord2d from, double radius) {
        Gob best = null;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                try {
                    Resource res = g.getres();
                    if (res == null || !res.name.startsWith("gfx/terobjs/barrel"))
                        continue;
                    double d = g.rc.dist(from);
                    if (d > radius || (best != null && d >= best.rc.dist(from)))
                        continue;
                    // A barrel advertises what's in it with an overlay sprite named
                    // gfx/terobjs/barrel-<contents>. No overlay means empty.
                    Optional<String> contents = g.ols.stream()
                        .map(Gob.Overlay::getSprResName)
                        .filter(n -> n != null && n.startsWith("gfx/terobjs/barrel-"))
                        .map(n -> n.substring(n.lastIndexOf('-') + 1))
                        .findAny();
                    if (contents.isPresent() && "water".equals(contents.get()))
                        best = g;
                } catch (Loading | NullPointerException ignored) {
                }
            }
        }
        return best;
    }

    private boolean onFreshWater(Coord2d wc) {
        try {
            MCache mcache = gui.ui.sess.glob.map;
            int t = mcache.gettile(wc.floor(MCache.tilesz));
            Tiler tl = mcache.tiler(t);
            if (!(tl instanceof WaterTile))
                return false;
            Resource res = mcache.tilesetr(t);
            return res != null && FRESH.contains(res.name);
        } catch (Loading | NullPointerException e) {
            return false;
        }
    }

    /**
     * The nearest fresh-water tile within {@code tiles}, searched outwards in rings so the first
     * hit is genuinely the closest rather than merely the first in scan order.
     */
    private Coord2d nearestFreshWater(Coord2d from, int tiles) {
        Coord base = from.floor(MCache.tilesz);
        for (int r = 0; r <= tiles; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r)
                        continue;
                    Coord tc = base.add(dx, dy);
                    Coord2d wc = tc.mul(MCache.tilesz).add(MCache.tilesz.div(2));
                    if (onFreshWater(wc))
                        return wc;
                }
            }
        }
        return null;
    }
}
