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
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.task.TravelTo;
import haven.automated.nbots.world.BotNav;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;
import haven.automated.nbots.world.Walk;

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
 * next - because that is how you plough without lifting the plough. Each leg is ONE plain
 * {@code click} at the far end of the column, and deliberately NOT pathfound: a route that dodges
 * round an obstacle is exactly wrong here, since the furrow is drawn by where the plough is
 * dragged, and a detour leaves an unploughed streak behind it. A raw click is walked in a straight
 * line whatever its length, so one order draws the whole leg.
 *
 * <p>Improvements on the source, all of them things a run without them ends badly:
 * <ul>
 *   <li>The first move has a timeout. nurgling's waits on distance alone and hangs for ever if
 *       the plough cannot reach the starting corner.</li>
 *   <li>Upkeep between legs instead of four copy-pasted stamina checks, so the character eats as
 *       well as drinks and comes back to where it left off.</li>
 *   <li>A bounded number of legs, so a misread area cannot drive for ever.</li>
 *   <li>The plough is put back down on every way out of the shift, not only the successful one.
 *       A run that ends early is exactly the run that would otherwise leave a plough on the
 *       character's back, where nothing else the player does will work until they take it off.</li>
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
     * "borka/carry" instead, and that spelling cannot be used here at all: {@code Composite}
     * stores {@code Resource.basename()}, so the poses this client can see are bare names with no
     * path on them and any test containing a slash matches nothing.
     */
    private static final String LIFTED_POSE = "banzai";
    /** How long to wait for a lift or a set-down to take, in ticks (~25ms each). */
    private static final int LIFT_TICKS = 120;
    /**
     * How long to give ONE TILE of driving, in ticks, before deciding the plough is stuck.
     *
     * The whole allowance for a move is this times its length in tiles, plus one tile's worth of
     * slack, because a leg is a whole column now and a fixed budget sized for one tile would time
     * out in the middle of every one of them. It is only ever a ceiling: {@link #drive} stops
     * waiting the moment the server says the character is no longer moving.
     */
    private static final int STEP_TICKS = 200;
    /** Polls to wait for the server to acknowledge a move order by starting one. */
    private static final int START_TICKS = 12;
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
    /**
     * How far to look for the plough, in world units.
     *
     * The search used to be unbounded, so "no plough in sight - leave one near the field" was a
     * message the bot could not actually produce: it would happily pick a plough three villages
     * away and spend the shift walking to it.
     */
    private static final double PLOW_RANGE = MCache.tilesz.x * 40;
    /** How many times to re-send a put-down before believing the server has refused it. */
    private static final int SETDOWN_TRIES = 3;
    /** Consecutive tiles we may fail to get hold of the plough before giving up on the field. */
    private static final int LOST_LIMIT = 3;

    /**
     * The plough this shift is working, by gob id. -1 before one is chosen.
     *
     * By id rather than "the nearest plough", because that is not a stable answer: a field
     * ploughed towards a shed with spare ploughs standing in it ends with one of those nearer than
     * the one in our hands, and right-clicking that one lets go of nothing.
     */
    private long plowId = -1;

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

        plowId = -1;
        Gob plow = nearest(PLOW);
        if (plow == null)
            return Outcome.failed("no plough in sight - leave one near the field");
        plowId = plow.id;

        /* The put-away is a finally rather than a last statement. Every return below it leaves the
         * plough in a state the player has to undo by hand - on the character's back, or dragging
         * behind it - and those are precisely the returns a run that went wrong takes. */
        try {
            return sweep(field, plow);
        } finally {
            putAway();
        }
    }

    private Outcome sweep(Place field, Gob plow) throws InterruptedException {
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
        Gob live = plow();
        if (live == null)
            return Outcome.blocked("lost track of the plough after setting it down");
        o = takeHandles(live);
        if (!o.isOk())
            return o;

        /*
         * One leg is one column of the sweep, and the whole column is ONE click.
         *
         * It used to be one click per tile, which is the same path chopped up: the server stops
         * the character dead on the tile it was sent to, the bot only notices on its next poll,
         * and the next order only goes out after that. The result is a plough that walks a tile,
         * stands still, walks a tile - visibly, and for the length of the field. A raw click is
         * walked in a straight line whatever its length (that is exactly why this does not use the
         * pathfinder), so a click at the far end of the column draws the same furrow in one
         * continuous move. The tile list is still what says WHERE the corners are; it just is not
         * a list of things to click any more.
         */
        int legLen = Math.max(field.h, 1);
        int cols = Math.min(Math.max(field.w, 1), Math.max(1, MAX_STEPS / legLen));
        int total = cols * legLen;
        int done = 0;
        int lost = 0;
        for (int col = 0; (col < cols) && running(); col++) {
            int entry = col * legLen;
            int exit = Math.min(entry + legLen - 1, furrow.size() - 1);
            if (entry > exit)
                break;

            /* Between legs, never mid-furrow: walking off to drink halfway down a column leaves
             * the furrow half drawn, and coming back does not resume it. A leg is now the unit the
             * loop turns on, so this is simply where the loop starts. */
            if (!upkeep())
                return Outcome.failed(fatalStop);
            /* An upkeep trip lets go of the handles, and a set-down the server refused leaves the
             * plough overhead. Both have to be undone before the next leg or the rest of the sweep
             * draws nothing at all - and being overhead is emphatically not a reason to skip
             * re-taking the handles, which is what the old guard here did. */
            if (!regrip()) {
                if (++lost >= LOST_LIMIT)
                    return Outcome.blocked("lost hold of the plough and couldn't get it back");
            } else {
                lost = 0;
            }

            /* One tile sideways into the column, then the length of it. Column 0 needs no step
             * across - the plough was set down on its first tile. */
            if (col > 0)
                drive(furrow.get(entry));
            if (exit > entry)
                drive(furrow.get(exit));
            done += reached(furrow.get(exit), legLen);
            setStatus("Ploughing (" + done + "/" + total + " tiles)");
        }

        report("ploughed " + done + " of " + total + " tiles in " + field.name);
        setStatus("Done: " + done + " tiles.");
        /* Driving the whole field without reaching a single tile is not a successful shift, and
         * reporting it as one is how a bot that ploughed nothing gets left running all night. */
        if ((done == 0) && (total > 0))
            return Outcome.blocked("drove " + field.name + " without ploughing a tile");
        return Outcome.ok();
    }

    // ------------------------------------------------------------------ the sweep

    /**
     * Tile centres in the order the plough should be dragged over them.
     *
     * Down the first column, one step east, up the next, and so on - so consecutive points are
     * always one tile apart and the plough never has to be lifted mid-field. The list starts at
     * the corner the plough is set down on, which is why the caller can use element 0 as the
     * placement spot.
     *
     * It is a list of PLACES, not of orders. The sweep clicks only the two ends of each column -
     * see the loop in {@link #sweep} - and reads the tiles between them out of here for the
     * arithmetic. Column {@code c} occupies {@code [c*h, c*h + h - 1]}, entry first, exit last.
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
     * Drags the plough in a straight line to a point, however far away it is.
     *
     * A raw click, not {@link BotNav#travelTo} and not {@code approach}: both of those are free to
     * route round whatever is in the way, and a furrow is drawn by where the plough went, so a
     * detour is a gap in the field rather than a clever recovery. The server walks a click in a
     * straight line whatever its length, which is what lets a whole column be one order.
     *
     * The wait watches the server's own {@code Moving} attribute rather than only the clock, in
     * the two-stage shape {@link Walk#straightTo} uses: wait for the move to START, so the gap
     * before the server's reply is not read as "stopped, therefore arrived", then wait for it to
     * either arrive or stop. Stopping short is what a boulder or a tree the field was drawn around
     * looks like, and noticing it immediately is what stops one blocked leg costing the timeout.
     *
     * Gives up on the leg rather than the shift. The next one starts from where we actually are.
     */
    private boolean drive(Coord2d to) throws InterruptedException {
        Gob me = nav.player();
        if (me == null)
            return false;
        int span = (int) Math.ceil(me.rc.dist(to) / MCache.tilesz.x);
        gui.map.wdgmsg("click", Coord.z, to.floor(posres), 1, 0);
        nav.waitUntil(() -> Walk.moving(gui), START_TICKS);
        nav.waitUntil(() -> arrived(to) || !Walk.moving(gui), STEP_TICKS * (span + 1));
        return arrived(to);
    }

    private boolean arrived(Coord2d to) {
        Gob me = nav.player();
        return (me != null) && (me.rc.dist(to) <= STEP_TOL);
    }

    /**
     * How many of a leg's tiles the character actually got down, from where it ended up.
     *
     * Measured rather than counted, because a leg is one move now and "did it arrive" is too
     * coarse to report on: a column stopped three tiles from the end has ploughed the rest of it,
     * and calling that zero would misreport the shift as having done nothing.
     */
    private int reached(Coord2d exit, int legLen) {
        Gob me = nav.player();
        if (me == null)
            return 0;
        int missed = (int) Math.floor(me.rc.dist(exit) / MCache.tilesz.y);
        return Math.max(0, Math.min(legLen, legLen - missed));
    }

    /**
     * Gets the plough back into our hands before a leg, whatever state the last one left it in.
     *
     * An upkeep trip lets go of the handles and a refused set-down leaves the plough overhead;
     * both read as "not ploughing", and driving a leg in either state draws nothing.
     */
    private boolean regrip() throws InterruptedException {
        if (ctx.poseContains(LIFTED_POSE)) {
            Gob me = nav.player();
            if ((me == null) || !dropUntilDown(me.rc))
                return false;
        }
        return holdingPlow() || takeHandles(plow()).isOk();
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

    /**
     * Puts whatever is on the character's back down at {@code spot}.
     *
     * Button 3, and the button is the whole of it. A LEFT click is how you WALK while carrying
     * something - it has to be, or a lifted object could never be moved anywhere - so the button-1
     * click this used to send was just another move order and the plough stayed up for the rest of
     * the shift. The right click is what the server reads as "put the load here": aimed at a gob
     * it loads that gob, which is how {@link haven.automated.WagonNearestLiftable} - the working
     * lift in this tree - fills a wagon, and aimed at bare ground it sets the load down there.
     */
    private void dropLifted(Coord2d spot) {
        gui.map.wdgmsg("click", Coord.z, spot.floor(posres), 3, 0);
    }

    /**
     * Re-sends the put-down until the plough is off our back, or the tries run out.
     *
     * More than one attempt because a single refusal is common and recoverable - the spot is
     * occupied, or the character was still sliding into place when the click landed - and because
     * every caller's alternative to succeeding here is leaving the player with a plough they have
     * to put down by hand.
     */
    private boolean dropUntilDown(Coord2d spot) throws InterruptedException {
        for (int i = 0; (i < SETDOWN_TRIES) && ctx.poseContains(LIFTED_POSE); i++) {
            dropLifted(spot);
            nav.waitUntil(() -> !ctx.poseContains(LIFTED_POSE), LIFT_TICKS);
        }
        return !ctx.poseContains(LIFTED_POSE);
    }

    /** Carries the plough to a spot and puts it down there. */
    private Outcome setDown(Coord2d spot) throws InterruptedException {
        if (!drive(spot) && (nav.player() != null) && (nav.player().rc.dist(spot) > MCache.tilesz.x))
            return Outcome.blocked("couldn't carry the plough to the corner of the field");
        if (!dropUntilDown(spot))
            return Outcome.blocked("the plough wouldn't go down");
        return Outcome.ok();
    }

    /** Right-clicks the plough to take hold of its handles. */
    private Outcome takeHandles(Gob plow) throws InterruptedException {
        if (plow == null)
            return Outcome.blocked("no plough to take hold of");
        if (!nav.approach(plow, BotNav.REACH))
            return Outcome.blocked("couldn't get to the plough");
        rightClick(plow);
        nav.waitUntil(this::holdingPlow, LIFT_TICKS);
        return holdingPlow() ? Outcome.ok()
            : Outcome.blocked("couldn't get hold of the plough's handles");
    }

    private void rightClick(Gob g) {
        gui.map.wdgmsg("click", Coord.z, g.rc.floor(posres), 3, 0, 0, (int) g.id,
            g.rc.floor(posres), 0, -1);
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

    /**
     * Leaves the plough on the ground rather than on our back or dragging behind us.
     *
     * Runs on every way out of the shift, the failures included, which is the point: the states
     * worth cleaning up are exactly the ones a run that went wrong leaves behind.
     *
     * A plough still overhead comes down whatever the setting says, because that is a stuck state
     * rather than a choice - the character cannot work, and the player has to find the put-down
     * gesture by hand before anything else they do will take. Only letting go of the HANDLES is
     * optional, since "leave it standing where the last furrow ended" is a reasonable thing to
     * want and is what the setting is actually asking about.
     */
    private void putAway() {
        try {
            if (ctx.poseContains(LIFTED_POSE)) {
                setStatus("Putting the plough down...");
                Gob me = nav.player();
                if (me != null)
                    dropUntilDown(me.rc);
            }
            if (!settings.on("putaway") || !holdingPlow())
                return;
            Gob plow = plow();
            if (plow == null)
                return;
            setStatus("Letting go of the plough...");
            rightClick(plow);
            nav.waitUntil(() -> !holdingPlow(), LIFT_TICKS);
        } catch (InterruptedException e) {
            /* Stop was pressed, or the shift is already unwinding from one - every wait in BotNav
             * throws the moment the bot stops. The put-down has been SENT either way, which is the
             * part that matters. Swallowed rather than rethrown because this runs from a finally,
             * where throwing would replace the shift's own outcome; and the interrupt flag is
             * deliberately not restored, because NBot's run loop sleeps after a shift and a live
             * flag there kills the bot thread outright. */
            NLog.log(log, "put-away interrupted before it finished: " + e);
        }
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

    /**
     * The plough this shift is working: the one we found, by id, else the nearest.
     *
     * Every use of "the nearest plough" after the first is a bug waiting for a second plough to
     * exist. Ploughing a field that ends beside a shed puts a spare plough nearer than the one in
     * our hands, and right-clicking a spare lets go of nothing.
     */
    private Gob plow() {
        Gob g = (plowId >= 0) ? ctx.gob(plowId) : null;
        return (g != null) ? g : nearest(PLOW);
    }

    /** The nearest match within {@link #PLOW_RANGE}, or null. */
    private Gob nearest(Alias what) {
        Gob me = nav.player();
        if (me == null)
            return null;
        Gob best = null;
        double bestd = PLOW_RANGE;
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
