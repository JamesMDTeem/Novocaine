package haven.automated.nbots;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.UI;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.task.TravelTo;
import haven.automated.nbots.world.BotNav;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;

import java.util.ArrayList;
import java.util.List;

import static haven.OCache.posres;

/**
 * Ploughs a field, one furrow at a time.
 *
 * <p>Ported from nurgling2's Plower, which is a four-line bot standing on a 150-line
 * {@code PatrolArea} that does the actual driving. Almost none of that came across literally: it
 * reaches nurgling's context, area, finder, pathfinder and task layers, and porting it as written
 * would have meant importing some four thousand lines to run a hundred. What survives is the
 * pattern, which is the part worth having.
 *
 * <p>The pattern is a boustrophedon sweep - down one column of tiles, one step across, up the
 * next - because that is how you plough without lifting the plough. Each leg is a plain
 * {@code click} one tile at a time and deliberately NOT pathfound: a route that dodges round an
 * obstacle is exactly wrong here, since the furrow is drawn by where the plough is dragged, and a
 * detour leaves an unploughed streak behind it.
 *
 * <p>Improvements on the source, all of them things a run without them ends badly:
 * <ul>
 *   <li>The first move has a timeout. nurgling's waits on distance alone and hangs for ever if
 *       the plough cannot reach the starting corner.</li>
 *   <li>Upkeep between legs instead of four copy-pasted stamina checks, so the character eats as
 *       well as drinks and comes back to where it left off.</li>
 *   <li>A bounded number of legs, so a misread area cannot drive for ever.</li>
 *   <li>The plough is put back down at the end rather than abandoned wherever it stopped.</li>
 * </ul>
 */
public class NPlowBot extends NBot {
    private static final String LOG = "nbot-plow.log";
    /** The plough, by resource-name fragment. */
    private static final Alias PLOW = new Alias("plow", "vehicle/plow");
    /**
     * The pose the character takes while holding something over its head.
     *
     * "banzai" is what this client actually observes for a lifted object - see
     * WagonNearestLiftable, which is the working lift in this tree. nurgling waits on
     * "borka/carry" instead; matched as a substring so either spelling satisfies it.
     */
    private static final String LIFTED_POSE = "banzai";
    /** How long to wait for a lift or a set-down to take, in ticks (~25ms each). */
    private static final int LIFT_TICKS = 120;
    /** How long to give one tile of driving before deciding the plough is stuck. */
    private static final int STEP_TICKS = 200;
    /** Close enough to a target tile to call the step done, in world units. */
    private static final double STEP_TOL = 2.0;
    /**
     * Hard ceiling on tiles driven in one shift.
     *
     * A field is w*h tiles plus the turns; this only has to be larger than any field somebody
     * would actually draw. It exists because the loop's real exit is geometric, and a geometry bug
     * with no counter behind it is a bot that drives until the client is killed.
     */
    private static final int MAX_STEPS = 20000;

    public NPlowBot(GameUI gui) {
        super(gui, "NPlowBot", "Plower (crew)", LOG, UI.scale(240, 96));
        settings.places("field", "Which field", PlaceRoles.WORK);
        settings.flag("putaway", "Put the plough down when finished", true);
        settings.layout(this, UI.scale(10, 22), 1, UI.scale(120));
        pack();
    }

    @Override
    protected String title() {
        return "Plower";
    }

    @Override
    protected Outcome work() throws InterruptedException {
        /* Named a field that has since gone? Say so. Falling through to field() would pick
         * "the one I am standing in, else the nearest", which is a different field. */
        if (settings.pinnedMissing("field"))
            return Outcome.failed("\"" + settings.place("field") + "\" no longer exists"
                + " - pick a field again");
        Place field = field();
        if (field == null)
            return Outcome.failed("no field to plough - draw an area, tag it 'work', and pick it above");

        /* Two bots ploughing one field do not halve the work: they cross each other's furrows,
         * and the one that arrives second drags its plough over ground the first is still on. */
        if (!Places.claim(field, true))
            return Outcome.blocked("another bot is already working " + field.name);
        try {
            return plough(field);
        } finally {
            Places.releaseClaim(field, true);
        }
    }

    private Outcome plough(Place field) throws InterruptedException {
        setStatus("Going to " + field.name + "...");
        Outcome there = new TravelTo(field).run(ctx);
        if (!there.isOk())
            return there;

        Gob plow = nearest(PLOW);
        if (plow == null)
            return Outcome.failed("no plough in sight - leave one near the field");

        Outcome o = lift(plow);
        if (!o.isOk())
            return o;

        List<Coord2d> furrow = furrow(field);
        if (furrow.isEmpty())
            return Outcome.failed(field.name + " is not on this part of the map");

        setStatus("Setting the plough down...");
        o = setDown(furrow.get(0));
        if (!o.isOk())
            return o;

        /* Right-click takes the handles. Until this happens the plough is an object standing in a
         * field and driving simply walks the character away from it. */
        Gob live = nearest(PLOW);
        if (live == null)
            return Outcome.blocked("lost track of the plough after setting it down");
        o = takeHandles(live);
        if (!o.isOk())
            return o;

        int steps = 0;
        int done = 0;
        for (int i = 1; i < furrow.size() && running() && steps < MAX_STEPS; i++) {
            steps++;
            /* Between legs, never mid-furrow: walking off to drink halfway down a column leaves
             * the furrow half drawn, and coming back does not resume it. */
            if (!upkeep())
                return Outcome.failed(fatalStop);
            if (!ctx.poseContains(LIFTED_POSE) && !holdingPlow())
                takeHandles(nearest(PLOW));
            if (drive(furrow.get(i)))
                done++;
            if ((i % 10) == 0)
                setStatus("Ploughing (" + done + "/" + (furrow.size() - 1) + " tiles)");
        }

        if (settings.on("putaway"))
            putAway();

        report("ploughed " + done + " of " + (furrow.size() - 1) + " tiles in " + field.name);
        setStatus("Done: " + done + " tiles.");
        return Outcome.ok();
    }

    // ------------------------------------------------------------------ the sweep

    /**
     * Tile centres in the order the plough should be dragged over them.
     *
     * Down the first column, one step east, up the next, and so on - so consecutive points are
     * always one tile apart and the plough never has to be lifted mid-field. The list starts at
     * the corner the plough is set down on, which is why the caller can use element 0 as the
     * placement spot and drive to everything after it.
     */
    private List<Coord2d> furrow(Place field) {
        List<Coord2d> out = new ArrayList<>();
        Coord2d nw = field.nw(gui);
        if (nw == null)
            return out;
        int w = Math.max(field.w, 1);
        int h = Math.max(field.h, 1);
        Coord2d half = new Coord2d(MCache.tilesz.x / 2.0, MCache.tilesz.y / 2.0);
        for (int tx = 0; tx < w; tx++) {
            for (int j = 0; j < h; j++) {
                // Odd columns run north, even columns south - the turn at the end of each.
                int ty = ((tx % 2) == 0) ? j : (h - 1 - j);
                out.add(nw.add(tx * MCache.tilesz.x, ty * MCache.tilesz.y).add(half));
            }
        }
        return out;
    }

    /**
     * Drags the plough one tile.
     *
     * A raw click, not {@link BotNav#travelTo} and not {@code approach}: both of those are free to
     * route round whatever is in the way, and a furrow is drawn by where the plough went, so a
     * detour is a gap in the field rather than a clever recovery.
     *
     * Gives up on the tile rather than the shift when the plough does not arrive. Something in the
     * way of one tile - a boulder, a tree the field was drawn around - should cost that tile and
     * nothing else, and the next leg starts from where we actually are.
     */
    private boolean drive(Coord2d to) throws InterruptedException {
        gui.map.wdgmsg("click", Coord.z, to.floor(posres), 1, 0);
        nav.waitUntil(() -> {
            Gob me = nav.player();
            return (me != null) && (me.rc.dist(to) <= STEP_TOL);
        }, STEP_TICKS);
        Gob me = nav.player();
        return (me != null) && (me.rc.dist(to) <= STEP_TOL);
    }

    // ------------------------------------------------------------------ handling the plough

    /**
     * Picks the plough up onto the character's back.
     *
     * The sequence is this client's own working one (WagonNearestLiftable): the bare "carry" act
     * arms the lift cursor, and the left-click that follows names what to lift. nurgling sends the
     * same pair through its own wrapper.
     */
    private Outcome lift(Gob plow) throws InterruptedException {
        if (ctx.poseContains(LIFTED_POSE))
            return Outcome.ok();   // already carrying something - assume it is the plough
        setStatus("Picking the plough up...");
        if (!nav.approach(plow, BotNav.REACH))
            return Outcome.blocked("couldn't get to the plough");
        gui.wdgmsg("act", "carry");
        gui.map.wdgmsg("click", Coord.z, plow.rc.floor(posres), 1, 0, 0, (int) plow.id,
            plow.rc.floor(posres), 0, -1);
        nav.waitUntil(() -> ctx.poseContains(LIFTED_POSE), LIFT_TICKS);
        if (!ctx.poseContains(LIFTED_POSE))
            return Outcome.blocked("the plough never came up onto our back");
        return Outcome.ok();
    }

    /** Carries the plough to a spot and puts it down there. */
    private Outcome setDown(Coord2d spot) throws InterruptedException {
        if (!drive(spot) && (nav.player() != null) && (nav.player().rc.dist(spot) > MCache.tilesz.x))
            return Outcome.blocked("couldn't carry the plough to the corner of the field");
        gui.map.wdgmsg("click", Coord.z, spot.floor(posres), 1, 0);
        nav.waitUntil(() -> !ctx.poseContains(LIFTED_POSE), LIFT_TICKS);
        if (ctx.poseContains(LIFTED_POSE))
            return Outcome.blocked("the plough wouldn't go down");
        return Outcome.ok();
    }

    /** Right-clicks the plough to take hold of its handles. */
    private Outcome takeHandles(Gob plow) throws InterruptedException {
        if (plow == null)
            return Outcome.blocked("no plough to take hold of");
        if (!nav.approach(plow, BotNav.REACH))
            return Outcome.blocked("couldn't get to the plough");
        gui.map.wdgmsg("click", Coord.z, plow.rc.floor(posres), 3, 0, 0, (int) plow.id,
            plow.rc.floor(posres), 0, -1);
        nav.waitUntil(this::holdingPlow, LIFT_TICKS);
        return holdingPlow() ? Outcome.ok()
            : Outcome.blocked("couldn't get hold of the plough's handles");
    }

    /**
     * Whether the character is currently dragging the plough.
     *
     * By pose, because there is nothing else to ask: the plough is a separate gob either way, and
     * its position alone cannot tell "standing next to it" from "holding it". The pose string is
     * server-owned, hence the substring match rather than an equality test.
     */
    private boolean holdingPlow() {
        return ctx.poseContains("carry") || ctx.poseContains("plow");
    }

    /** Lets go, and leaves the plough standing where the last furrow ended. */
    private void putAway() throws InterruptedException {
        Gob plow = nearest(PLOW);
        if ((plow == null) || !holdingPlow())
            return;
        setStatus("Putting the plough down...");
        gui.map.wdgmsg("click", Coord.z, plow.rc.floor(posres), 3, 0, 0, (int) plow.id,
            plow.rc.floor(posres), 0, -1);
        nav.waitUntil(() -> !holdingPlow(), LIFT_TICKS);
    }

    // ------------------------------------------------------------------ finding things

    /** The field to work: the pinned one, else the one we are standing in, else the nearest. */
    private Place field() {
        Place pinned = settings.pinnedPlace("field");
        if (pinned != null)
            return pinned;
        Place here = Places.containing(gui, PlaceRoles.WORK);
        return (here != null) ? here : Places.nearest(gui, PlaceRoles.WORK);
    }

    private Gob nearest(Alias what) {
        Gob me = nav.player();
        if (me == null)
            return null;
        Gob best = null;
        double bestd = Double.MAX_VALUE;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (!what.matchesPart(resname(g)))
                    continue;
                double d = g.rc.dist(me.rc);
                if (d < bestd) {
                    bestd = d;
                    best = g;
                }
            }
        }
        return best;
    }

    /** Empty for anything that cannot answer right now, so callers can match without guarding. */
    private String resname(Gob g) {
        try {
            Resource res = g.getres();
            return (res == null) ? "" : res.name;
        } catch (Loading e) {
            return "";
        }
    }
}
