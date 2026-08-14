package haven.automated.nbots;

import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.UI;
import haven.WItem;
import haven.automated.helpers.HearthTravel;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Widgets;
import haven.automated.nbots.task.Deposit;
import haven.automated.nbots.world.TravelResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static haven.OCache.posres;

/**
 * Dragonfly catcher that works a swamp from a dugout.
 *
 * The plan is the one a player follows by hand: pick up the nearest dugout, carry it to the nearest
 * swamp it can see, put it in the water, get in, and sweep the whole connected chunk of swamp within
 * sight - catching every emerald and ruby dragonfly that is over water, ignoring any that has flown
 * over land. Once the chunk is swept clean it rows back to the edge, gets out, picks the boat back
 * up, hearths home, puts the boat down, and files the catch at whichever storage zone is tagged for
 * dragonflies.
 *
 * Every game string below is a server-owned name (flower-menu petals, tile and kritter resources,
 * the boat's mounting state) and is a named constant rather than scattered through the logic: these
 * are the only lines that should ever need touching if the server renames something.
 */
public class NDragonflyBot extends NBot {
    private static final String LOG = "nbot-dragonfly.log";

    /** Bog, fen and swamp - the walkable "wet ground" a boat can be placed in and rowed across. */
    private static final Set<String> SWAMP_TILES = new HashSet<>(List.of(
        "gfx/tiles/bog", "gfx/tiles/bogwater", "gfx/tiles/fen",
        "gfx/tiles/fenwater", "gfx/tiles/swamp", "gfx/tiles/swampwater"));

    private static final String DUGOUT_RES = "gfx/terobjs/vehicle/dugout";
    private static final String DRAGONFLY = "dragonfly";           // res fragment (gfx/kritter/dragonfly/dragonfly)
    private static final String BOARD_PETAL = "Into the blue yonder!";
    private static final String PICKUP_PETAL = "Pick up";
    private static final String CATCH_PETAL = "Catch";

    /** How far, in tiles, the bot can "see" - bounds both the swamp search and the sweep chunk. */
    private static final int SIGHT_TILES = 44;
    /** Tiles of swamp to flood-fill at most, so a coast-to-coast swamp does not run the bot forever. */
    private static final int MAX_CHUNK = 4000;
    /** Sweep cycles with no dragonfly and no unvisited tile before calling the chunk done. */
    private static final int IDLE_LIMIT = 4;
    /** Ticks to wait for the boat to arrive at a clicked tile before trying again. */
    private static final int ARRIVE_TICKS = 240;
    /** How close (world units) counts as "arrived" - about one tile. */
    private static final double ARRIVE_TOL = 11.0;

    private final MCache mcache;

    public NDragonflyBot(GameUI gui) {
        super(gui, "NDragonflyBot", "Dragonfly (crew)", LOG, UI.scale(300, 128));
        this.mcache = gui.map.glob.map;
        settings.flag("hearth", "Hearth home when done", true);
        settings.layout(this, UI.scale(10, 22), 1, UI.scale(150));
        pack();
    }

    @Override
    protected String title() {
        return "Dragonfly";
    }

    // ------------------------------------------------------------------ the run

    @Override
    protected Outcome work() throws InterruptedException {
        Coord2d start = playerPos();
        if (start == null)
            return Outcome.failed("no player position");

        if (!upkeep())
            return Outcome.failed(fatalStop);

        // 1. A boat, in hand or on the water.
        if (findDugoutItem() == null && !acquireDugout())
            return Outcome.failed("no dugout in inventory and none in sight to pick up");

        // 2. The nearest swamp it can see.
        Coord swampTile = nearestSwampTile(start);
        if (swampTile == null)
            return Outcome.failed("no swamp in sight");

        // 3. Carry the boat to the water and put it down.
        Outcome t = walkTo(tileCenter(swampTile));
        if (!t.isOk())
            return t;
        if (!dropDugout())
            return Outcome.blocked("could not put the dugout down on the water");
        Gob boat = awaitBoatNear(playerPos());
        if (boat == null)
            return Outcome.blocked("dugout did not appear on the water");

        // 4. Get in.
        if (!board(boat))
            return Outcome.blocked("could not board the dugout");

        // 5. Sweep the chunk.
        sweepCatch(tileCenter(swampTile));

        // 6. Row to the edge, get out, pick the boat up.
        if (!exitBoat())
            report("could not recover the dugout; leaving it on the water");

        // 7. Home.
        if (settings.on("hearth") && HearthTravel.canTravel()) {
            setStatus("Hearth home.");
            HearthTravel.travel(gui);
        }

        // 8. Boat back on the ground at home.
        dropDugout();

        // 9. File the catch.
        Outcome d = new Deposit(new Alias(DRAGONFLY)).run(ctx);
        if (!d.isOk())
            return d;
        return Outcome.ok();
    }

    // ------------------------------------------------------------------ the boat

    private WItem findDugoutItem() {
        if (gui.vhand != null && isDugout(gui.vhand))
            return gui.vhand;
        WItem wi = gui.maininv.getItemPartial("Dugout");
        if (wi != null)
            return wi;
        if (gui.getequipory() != null) {
            for (WItem w : gui.getequipory().slots) {
                if (w != null && isDugout(w))
                    return w;
            }
        }
        return null;
    }

    private static boolean isDugout(WItem w) {
        try {
            return w.item.getname().contains("Dugout");
        } catch (Loading l) {
            return false;
        }
    }

    /** Picks up the nearest dugout already on the water, so it can be carried to another swamp. */
    private boolean acquireDugout() throws InterruptedException {
        Gob boat = nearestBoatGob(playerPos());
        if (boat == null)
            return false;
        nav.approach(boat, 6);
        rightClick(boat);
        FlowerMenu fm = Widgets.awaitFlowerMenu(gui.ui.root, this::running);
        if (fm == null)
            return false;
        choose(fm, PICKUP_PETAL);
        nav.waitUntil(() -> findDugoutItem() != null, 120);
        return findDugoutItem() != null;
    }

    private boolean dropDugout() throws InterruptedException {
        WItem wi = findDugoutItem();
        if (wi == null)
            return false;
        wi.item.wdgmsg("drop", new Coord(wi.item.sz.x / 2, wi.item.sz.y / 2));
        nav.pause(8);
        return true;
    }

    private boolean board(Gob boat) throws InterruptedException {
        nav.approach(boat, 4);
        for (int attempt = 0; attempt < 3 && !riding(); attempt++) {
            rightClick(boat);
            FlowerMenu fm = Widgets.awaitFlowerMenu(gui.ui.root, this::running);
            if (fm == null)
                return false;
            choose(fm, BOARD_PETAL);
            nav.waitUntil(this::riding, 120);
        }
        return riding();
    }

    /** Rows to a land-adjacent swamp tile, gets out and picks the boat up. */
    private boolean exitBoat() throws InterruptedException {
        Coord2d me = playerPos();
        if (me == null)
            return false;
        Coord edge = nearestEdgeTile(me);
        if (edge != null)
            rowTo(tileCenter(edge));
        Gob boat = nearestBoatGob(playerPos());
        if (boat == null)
            return false;
        rightClick(boat);
        FlowerMenu fm = Widgets.awaitFlowerMenu(gui.ui.root, this::running);
        if (fm == null)
            return false;
        choose(fm, PICKUP_PETAL);
        nav.waitUntil(() -> !riding() && findDugoutItem() != null, 240);
        return findDugoutItem() != null;
    }

    private boolean riding() {
        return ctx.poseContains("dugout") || ctx.poseContains("coracle");
    }

    // ------------------------------------------------------------------ the sweep

    private void sweepCatch(Coord2d start) throws InterruptedException {
        Set<Coord> chunk = floodSwamp(start);
        Set<Coord> visited = new HashSet<>();
        visited.add(playerPos().floor(MCache.tilesz));
        int idle = 0;

        while (running() && idle < IDLE_LIMIT) {
            if (!upkeep())
                return;
            Gob fly = nearestDragonfly();
            if (fly != null) {
                catchDragonfly(fly);
                idle = 0;
            } else {
                Coord next = nearestUnvisited(chunk, visited);
                if (next == null)
                    break;
                rowTo(tileCenter(next));
                visited.add(next);
                idle++;
            }
        }
    }

    private Gob nearestDragonfly() {
        Coord2d me = playerPos();
        if (me == null)
            return null;
        Gob best = null;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (g.id == gui.map.plgob)
                    continue;
                String res = resname(g);
                if (res == null || !res.contains(DRAGONFLY))
                    continue;
                // A dragonfly that has flown onto land is disregarded.
                if (!onSwamp(g.rc))
                    continue;
                if (best == null || g.rc.dist(me) < best.rc.dist(me))
                    best = g;
            }
        }
        return best;
    }

    private void catchDragonfly(Gob fly) throws InterruptedException {
        rowTo(fly.rc);
        rightClick(fly);
        FlowerMenu fm = Widgets.awaitFlowerMenu(gui.ui.root, this::running);
        if (fm == null)
            return;
        choose(fm, CATCH_PETAL);
        nav.waitUntil(() -> ctx.gob(fly.id) == null, 120);
        nav.pause(4);
    }

    // ------------------------------------------------------------------ terrain

    private boolean onSwamp(Coord2d wc) {
        return isSwampTile(wc.floor(MCache.tilesz));
    }

    private boolean isSwampTile(Coord tile) {
        try {
            String name = mcache.tileTypeName(mcache.gettile(tile));
            return name != null && SWAMP_TILES.contains(name);
        } catch (Loading l) {
            return false;
        }
    }

    private Coord nearestSwampTile(Coord2d from) {
        Coord origin = from.floor(MCache.tilesz);
        Coord best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SIGHT_TILES; dx <= SIGHT_TILES; dx++) {
            for (int dy = -SIGHT_TILES; dy <= SIGHT_TILES; dy++) {
                Coord t = new Coord(origin.x + dx, origin.y + dy);
                if (!isSwampTile(t))
                    continue;
                double d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = t;
                }
            }
        }
        return best;
    }

    /** The connected swamp region around {@code from}, bounded to sight and {@link #MAX_CHUNK}. */
    private Set<Coord> floodSwamp(Coord2d from) {
        Set<Coord> seen = new HashSet<>();
        ArrayDeque<Coord> queue = new ArrayDeque<>();
        Coord origin = from.floor(MCache.tilesz);
        if (isSwampTile(origin))
            queue.add(origin);
        int[] offs = {-1, 1, -1, 1};
        while (!queue.isEmpty() && seen.size() < MAX_CHUNK) {
            Coord t = queue.poll();
            if (!seen.add(t))
                continue;
            if (Math.abs(t.x - origin.x) > SIGHT_TILES || Math.abs(t.y - origin.y) > SIGHT_TILES)
                continue;
            if (isSwampTile(new Coord(t.x - 1, t.y)))
                queue.add(new Coord(t.x - 1, t.y));
            if (isSwampTile(new Coord(t.x + 1, t.y)))
                queue.add(new Coord(t.x + 1, t.y));
            if (isSwampTile(new Coord(t.x, t.y - 1)))
                queue.add(new Coord(t.x, t.y - 1));
            if (isSwampTile(new Coord(t.x, t.y + 1)))
                queue.add(new Coord(t.x, t.y + 1));
        }
        return seen;
    }

    private Coord nearestUnvisited(Set<Coord> chunk, Set<Coord> visited) {
        Coord2d me = playerPos();
        if (me == null)
            return null;
        Coord here = me.floor(MCache.tilesz);
        Coord best = null;
        double bestD = Double.MAX_VALUE;
        for (Coord t : chunk) {
            if (visited.contains(t))
                continue;
            double d = (t.x - here.x) * (t.x - here.x) + (t.y - here.y) * (t.y - here.y);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    /** A swamp tile with at least one non-swamp neighbour, used as an exit point. */
    private Coord nearestEdgeTile(Coord2d from) {
        Coord origin = from.floor(MCache.tilesz);
        Coord best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SIGHT_TILES; dx <= SIGHT_TILES; dx++) {
            for (int dy = -SIGHT_TILES; dy <= SIGHT_TILES; dy++) {
                Coord t = new Coord(origin.x + dx, origin.y + dy);
                if (!isSwampTile(t))
                    continue;
                if (isSwampTile(new Coord(t.x - 1, t.y)) && isSwampTile(new Coord(t.x + 1, t.y))
                    && isSwampTile(new Coord(t.x, t.y - 1)) && isSwampTile(new Coord(t.x, t.y + 1)))
                    continue;
                double d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = t;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ movement

    /** On foot: through the pathfinder. In a boat: a direct click, no pathfinding on water. */
    private void rowTo(Coord2d target) throws InterruptedException {
        gui.map.wdgmsg("click", Coord.z, target.floor(posres), 1, 0);
        nav.waitUntil(() -> playerPos() != null && playerPos().dist(target) < ARRIVE_TOL, ARRIVE_TICKS);
    }

    private Outcome walkTo(Coord2d target) throws InterruptedException {
        TravelResult r = nav.travelTo(target, 8.0);
        if (r.isArrived())
            return Outcome.ok();
        return Outcome.failed("could not reach the swamp: " + r.reason());
    }

    private Gob awaitBoatNear(Coord2d near) throws InterruptedException {
        nav.waitUntil(() -> nearestBoatGob(playerPos()) != null, 120);
        return nearestBoatGob(playerPos());
    }

    private Gob nearestBoatGob(Coord2d near) {
        if (near == null)
            return null;
        Gob best = null;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                String res = resname(g);
                if (res == null || !res.startsWith(DUGOUT_RES))
                    continue;
                if (best == null || g.rc.dist(near) < best.rc.dist(near))
                    best = g;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ small helpers

    private void rightClick(Gob g) {
        gui.map.wdgmsg("click", Coord.z, g.rc.floor(posres), 3, 0, 0, (int) g.id,
            g.rc.floor(posres), 0, -1);
    }

    private void choose(FlowerMenu fm, String petalName) {
        for (FlowerMenu.Petal p : fm.opts) {
            if (p != null && petalName.equals(p.name)) {
                fm.wdgmsg("cl", p.num, 0);
                return;
            }
        }
        // Petal not present (renamed server-side) - take the first rather than stall.
        if (fm.opts.length > 0)
            fm.wdgmsg("cl", fm.opts[0].num, 0);
    }

    private Coord2d playerPos() {
        Gob me = (gui.map == null) ? null : gui.map.player();
        return (me == null) ? null : me.rc;
    }

    private static Coord2d tileCenter(Coord tile) {
        return new Coord2d((tile.x + 0.5) * MCache.tilesz.x, (tile.y + 0.5) * MCache.tilesz.y);
    }

    private static String resname(Gob g) {
        try {
            Resource res = g.getres();
            return (res == null) ? null : res.name;
        } catch (Loading | NullPointerException e) {
            return null;
        }
    }
}
