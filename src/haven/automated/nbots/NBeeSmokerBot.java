package haven.automated.nbots;

import haven.Button;
import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.Loader;
import haven.Loading;
import haven.MCache;
import haven.Makewindow;
import haven.MapView;
import haven.MenuGrid;
import haven.ResDrawable;
import haven.Text;
import haven.Resource;
import haven.UI;
import haven.Widget;
import haven.automated.helpers.HitBoxes;
import haven.automated.nbots.core.Carried;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Widgets;
import haven.automated.nbots.task.Drink;
import haven.automated.nbots.task.Upkeep;
import haven.automated.nbots.task.WorkGob;
import haven.automated.nbots.world.BotNav;
import haven.automated.nbots.world.MovementCommand;
import haven.automated.nbots.world.Reach;
import haven.automated.nbots.world.TravelResult;
import haven.automated.nbots.world.WorldAnchor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static haven.OCache.posres;

/**
 * Smokes a round of wild beehives, then comes back and raids them.
 *
 * <p>Wild beehives (as opposed to the domestic skeps a beekeeper builds) can only be raided
 * after they have been pacified with smoke. The ritual is: stand a lit Bough Pyre under the
 * hive, wait for the pyre to burn down and the swarm to leave, then raid. nurgling2's BoughBee
 * only does the last step, and only for one hive; it expects the player to have already built
 * and lit the pyre by hand.
 *
 * <p>The shift is three phases rather than one hive at a time, which is the whole point of the
 * bot. A pyre takes about fifteen minutes to burn down, and the version this replaces spent
 * every one of those minutes standing still next to the hive that was burning. So instead:
 *
 * <ol>
 *   <li><b>Build.</b> Walk the hives in sight, standing and lighting a pyre under each. The
 *       first one lit starts a fifteen-minute clock and marks the spot to come back to.</li>
 *   <li><b>Return.</b> When the clock runs out - or the water does - go back to that spot.</li>
 *   <li><b>Raid.</b> Take the hives in the order they were lit, which is the order they come
 *       ready in, waiting for each to finish burning before raiding it.</li>
 * </ol>
 *
 * <p>The strings that name menu options and flower-menu petals ("Bough Pyre", "Firebrand",
 * "Take bough", "Light", "Raid!") are owned by the server and shipped to us through the menu
 * grid, not by the client, so they are collected here as constants and may need checking
 * against the live game if the server renames them.
 */
public class NBeeSmokerBot extends NBot {
    private static final String HIVE_RES = "wildbees/wildbeehive";
    private static final String BOUGH = "Bough";
    private static final String BRANCH = "Branch";
    private static final String FIREBRAND = "Firebrand";
    private static final String TREE_RES = "gfx/terobjs/tree";
    private static final String BRANCH_RES = "items/branch";
    private static final String BOUGH_PETAL = "Take bough";
    private static final String PYRE_MENU = "Bough Pyre";
    private static final String FIREBRAND_MENU = "Firebrand";
    private static final String LIGHT_PETAL = "Light";
    private static final String RAID_PETAL = "Raid!";

    /** How close (world units, ~2 tiles) a pyre must be to a hive to count as "under" it. */
    private static final double PYRE_RANGE = 22.0;
    /**
     * How long after the first pyre is lit before the first hive is worth coming back for.
     *
     * Wall-clock rather than a tick count, because it has to survive the bot doing other things -
     * walking, gathering, waiting on a craft - for the whole of it. Tick counts in this class
     * measure how long to sit in one poll loop; this measures the shift.
     */
    private static final long BUILD_WINDOW_MS = 15 * 60 * 1000L;
    /** Below this fraction of what the carried vessels hold, the water has run out. */
    private static final double WATER_SPENT = 0.05;
    /** How long to wait for one smoked hive to burn down and go quiet, in ticks (~25ms each). */
    private static final int READY_TIMEOUT = 12000;
    /** How wide a bough pyre is taken to be when its own footprint can't be read, in world units. */
    private static final double PYRE_FOOTPRINT = 5.5;
    /** Air left between the pyre's footprint and anything already standing, in world units. */
    private static final double SPOT_CLEARANCE = 1.0;
    /** How far from the hive to look for somewhere to stand the pyre, in tiles. */
    private static final int SPOT_TILES = 3;
    /** How long to give the construction window to open, in ticks (~25ms each). */
    private static final int BUILD_WAIT = 120;
    /** Near enough to a hive that walking there is {@link BotNav#approach}'s job, not a journey. */
    private static final double WALKABLE = 100.0;

    /**
     * One hive we have stood a lit pyre under.
     *
     * Ids rather than gobs: between lighting a pyre and coming back for it the bot walks far
     * enough that both gobs unload, and a held Gob reference then names an object that is no
     * longer in the cache. The position is kept so the ash the pyre leaves can be looked for.
     */
    private static final class Smoked {
        final long hive;
        final long pyre;
        final Coord2d where;

        Smoked(long hive, long pyre, Coord2d where) {
            this.hive = hive;
            this.pyre = pyre;
            this.where = where;
        }
    }

    /**
     * Construction sites this run placed, by gob id.
     *
     * The version this replaces adopted any {@code consobj} within {@link #PYRE_RANGE} of a hive
     * on the grounds that one standing that close "is one of ours". It is not: a player building
     * anything at all near a wild hive got their site finished with our boughs. Only sites this
     * run put down are ours to finish.
     */
    private final Set<Long> ourSites = new HashSet<>();

    public NBeeSmokerBot(GameUI gui) {
        super(gui, "NBeeSmokerBot", "Bee Smoker (crew)", "nbot-bee.log", UI.scale(300, 170));
        settings.number("boughs", "Boughs per pyre", 4);
        settings.number("branches", "Branches per firebrand", 2);
        settings.flag("collect", "Gather missing materials", true);
        settings.layout(this, UI.scale(10, 22), 1, UI.scale(150));
        pack();
    }

    @Override
    protected String title() {
        return "Bee Smoker";
    }

    /**
     * Deliberately does not top up water before starting.
     *
     * This bot is asked to work a stand of hives and come back, not to run errands: a trip to the
     * water place before the first pyre goes up delays the fifteen-minute clock that everything
     * else is timed against, and the shift is bounded by that clock rather than by how much work
     * is left. Running dry stops it building more pyres - see {@link #work} - which is the
     * intended end of the build phase, not a failure.
     */
    @Override
    protected void stock() {
    }

    @Override
    protected Outcome work() throws InterruptedException {
        ourSites.clear();
        List<Smoked> smoked = new ArrayList<>();
        Set<Long> handled = new HashSet<>();
        WorldAnchor home = null;
        long deadline = 0;
        String ended = "ran out of hives";

        // ---------------------------------------------------------------- build
        while (running()) {
            if ((deadline != 0) && (System.currentTimeMillis() >= deadline)) {
                ended = "the fifteen minutes are up";
                break;
            }
            if (spentWater()) {
                /* Out of water is the end of the build phase, not the end of the shift. Every
                 * pyre already lit is fifteen minutes of burning we have paid for, and walking
                 * away from those to go and find a barrel wastes all of it. */
                ended = "the water ran out";
                break;
            }
            Gob hive = nextHive(handled);
            if (hive == null)
                break;
            handled.add(hive.id);
            setStatus("Smoking hive " + (smoked.size() + 1) + "...");
            Outcome o = approachHive(hive);
            if (o.isOk())
                o = smoke(hive);
            if (!o.isOk()) {
                report("skipped a hive: " + o.reason);
                continue;
            }
            Gob pyre = pyreNear(hive);
            smoked.add(new Smoked(hive.id, (pyre == null) ? -1 : pyre.id, hive.rc));
            if (deadline == 0) {
                home = WorldAnchor.capturePlayer(gui);
                deadline = System.currentTimeMillis() + BUILD_WINDOW_MS;
                report("first pyre lit - building on for fifteen minutes, then coming back here");
            }
        }

        if (smoked.isEmpty())
            return Outcome.failed("no wild beehive could be smoked (" + ended + ")");
        report("lit " + smoked.size() + " pyre(s); " + ended);

        // ---------------------------------------------------------------- return
        if ((home != null) && running()) {
            setStatus("Going back to the first hive...");
            TravelResult r = nav.travelTo(home, PYRE_RANGE);
            if (!r.isArrived())
                report("couldn't get back to where the first pyre was lit: " + r.reason());
        }

        // ---------------------------------------------------------------- raid
        int raided = 0;
        for (int i = 0; i < smoked.size() && running(); i++) {
            Smoked s = smoked.get(i);
            setStatus("Waiting on hive " + (i + 1) + " of " + smoked.size() + "...");
            Outcome o = awaitReady(s);
            if (!o.isOk()) {
                report("hive " + (i + 1) + " never came ready: " + o.reason);
                continue;
            }
            o = raid(s);
            if (o.isOk())
                raided++;
            else
                report("couldn't raid hive " + (i + 1) + ": " + o.reason);
        }

        report("raided " + raided + " of " + smoked.size() + " smoked hive(s)");
        setStatus("Done: raided " + raided + " of " + smoked.size() + ".");
        return Outcome.ok();
    }

    // ------------------------------------------------------------------ build phase

    /** The nearest wild beehive not already dealt with that has no pyre under it yet. */
    private Gob nextHive(Set<Long> handled) {
        List<Gob> out = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (!handled.contains(g.id) && resname(g).contains(HIVE_RES))
                    out.add(g);
            }
        }
        /* Nearest-first is a preference, not a requirement: with no player gob to measure from -
         * which happens for a tick or two around a load screen - the unsorted list is still a
         * correct list of hives, and is a better answer than dying on the sort. */
        Gob me = nav.player();
        if (me != null)
            out.sort((a, b) -> Double.compare(a.rc.dist(me.rc), b.rc.dist(me.rc)));
        for (Gob g : out) {
            if (pyreNear(g) == null)
                return g;
        }
        return null;
    }

    /**
     * Gets within working distance of a hive, routing rather than walking when it is far.
     *
     * {@link BotNav#approach} plans around what it can see and gives up at anything larger; it is
     * the right tool for the last few tiles and the wrong one for crossing a valley. The version
     * this replaces used it for both, which is why a hive on the far side of anything at all was
     * reported as unreachable while the character stood looking at a wall.
     */
    private Outcome approachHive(Gob hive) throws InterruptedException {
        Gob me = nav.player();
        if ((me != null) && (me.rc.dist(hive.rc) > WALKABLE)) {
            TravelResult r = nav.travelTo(hive.rc, PYRE_RANGE);
            if (r.isAborted())
                return Outcome.blocked("stopped on the way to the hive");
            if (!r.isArrived())
                return Outcome.blocked("couldn't get to the hive: " + r.reason());
        }
        return Outcome.ok();
    }

    /** Stands a pyre under the hive if there isn't one, then lights it. */
    private Outcome smoke(Gob hive) throws InterruptedException {
        report("smoking hive @" + hive.rc);
        Gob pyre = pyreNear(hive);
        if (pyre == null) {
            Outcome o;
            Gob site = ourSiteNear(hive);
            if (site == null) {
                o = buildPyre(hive);
            } else {
                /* A site this run placed and did not finish - the build click was refused, or the
                 * run was stopped between placing and building. Finishing it costs the boughs it
                 * was going to cost anyway; placing a second one leaves the first as litter. */
                o = ensureBoughs();
                if (o.isOk())
                    o = finishBuild(site);
            }
            if (!o.isOk())
                return o;
            pyre = pyreNear(hive);
            if (pyre == null)
                return Outcome.blocked("the pyre never appeared near the hive");
        }
        Outcome o = craftFirebrand();
        if (!o.isOk())
            return o;
        return light(pyre);
    }

    /**
     * Enough boughs on hand to build a pyre.
     *
     * Its own step because building is reached two ways - a fresh placement and an unfinished site
     * picked up earlier in the run - and a Build button clicked with an empty inventory does
     * nothing at all, which reads downstream as a pyre that never appeared.
     */
    private Outcome ensureBoughs() throws InterruptedException {
        if (!settings.on("collect") || count(BOUGH) >= settings.num("boughs"))
            return Outcome.ok();
        if (!gather(BOUGH, TREE_RES, BOUGH_PETAL, settings.num("boughs")))
            return Outcome.blocked("can't gather " + settings.num("boughs") + " boughs for the pyre");
        return Outcome.ok();
    }

    /** Builds a fresh pyre under the hive, gathering boughs first if configured to. */
    private Outcome buildPyre(Gob hive) throws InterruptedException {
        Outcome boughs = ensureBoughs();
        if (!boughs.isOk())
            return boughs;
        MenuGrid.Pagina pag = pagina(PYRE_MENU);
        if (pag == null)
            return Outcome.failed("no 'Bough Pyre' option in the build menu");
        /* Placement mode first: the plob carries the pyre's own resource, and its collision box is
         * the honest answer to how much room the thing needs. */
        if (!enterPlacing(pag))
            return Outcome.blocked("build menu never entered placement mode");
        List<Coord> spots = candidateSpots(hive, placingFootprint());
        if (spots.isEmpty()) {
            cancelPlacing();
            return Outcome.blocked("nowhere clear of other objects to stand a pyre near the hive");
        }
        for (Coord spot : spots) {
            if (!enterPlacing(pag))
                return Outcome.blocked("build menu never entered placement mode");
            gui.map.wdgmsg("place", spot, 0, 1, 0);
            nav.waitUntil(() -> gobAtSpot(spot, "consobj") != null, 40);
            Gob consobj = gobAtSpot(spot, "consobj");
            if (consobj == null)
                continue; // the server rejected that tile anyway; try the next one out
            ourSites.add(consobj.id);
            return finishBuild(consobj);
        }
        cancelPlacing();
        return Outcome.blocked("every spot near the hive was refused ("
                + spots.size() + " tried)");
    }

    /**
     * Takes the pyre back off the cursor.
     *
     * Right-clicking is what cancels a placement, and a place message carrying button 3 is what a
     * right-click sends, so that is the cancel. Worth doing on every way out of placement mode:
     * leaving a plob stuck to the cursor hands the player a client that has to be clicked out of
     * before it will do anything else.
     */
    private void cancelPlacing() {
        Gob me = nav.player();
        if ((gui.map.placing() != null) && (me != null))
            gui.map.wdgmsg("place", me.rc.floor(posres), 0, 3, 0);
    }

    /**
     * How much room to leave around a placement, in world units: half the width of the thing being
     * placed, from its own collision box while it is on the cursor.
     *
     * Falls back to {@link #PYRE_FOOTPRINT} rather than to zero, because a zero footprint means
     * "fits anywhere", which is the answer that put the pyre inside a tree in the first place.
     */
    private double placingFootprint() {
        try {
            Loader.Future<MapView.Plob> f = gui.map.placing();
            if ((f != null) && f.done()) {
                MapView.Plob plob = f.get();
                if (Reach.radius(plob) <= 0)
                    HitBoxes.addHitBox(plob); // plobs live outside the object cache, so nothing else has
                double r = Reach.radius(plob);
                if (r > 0)
                    return r;
            }
        } catch (RuntimeException e) {
            // Includes Loading: the preview hasn't resolved, so use the guess.
        }
        return PYRE_FOOTPRINT;
    }

    private Outcome finishBuild(Gob consobj) throws InterruptedException {
        Coord2d spot = consobj.rc;
        if (!reach(consobj))
            return Outcome.blocked("couldn't get to the pyre site to build it");
        rclick(consobj);
        nav.waitUntil(() -> findButton(gui.ui.root, "Build") != null, BUILD_WAIT);
        Button build = findButton(gui.ui.root, "Build");
        if (build == null) {
            /* One more, because the first right-click can go out while the character is still
             * coming to a stop, and a click the server ignores looks exactly like a site with no
             * Build button on it. */
            rclick(consobj);
            nav.waitUntil(() -> findButton(gui.ui.root, "Build") != null, BUILD_WAIT);
            build = findButton(gui.ui.root, "Build");
        }
        if (build == null)
            return Outcome.blocked("no 'Build' button on the construction");
        build.click();
        nav.waitUntil(() -> resNear(spot, "pyre") != null || resNear(spot, "bonfire") != null, 400);
        return (resNear(spot, "pyre") != null || resNear(spot, "bonfire") != null)
                ? Outcome.ok()
                : Outcome.blocked("the pyre never appeared after building");
    }

    /**
     * Crafts a firebrand, and confirms one actually came out.
     *
     * The version this replaces returned OK whether or not the craft produced anything, so a
     * failed craft - no branches, wrong station, window closed early - surfaced two steps later
     * as an unexplained "no 'Light' option on the pyre". Either a firebrand appeared or branches
     * were spent making one; neither means the craft worked.
     */
    private Outcome craftFirebrand() throws InterruptedException {
        if (count(FIREBRAND) > 0)
            return Outcome.ok();
        if (settings.on("collect") && count(BRANCH) < settings.num("branches")) {
            if (!gather(BRANCH, BRANCH_RES, null, settings.num("branches")))
                return Outcome.blocked("can't gather " + settings.num("branches") + " branches for a firebrand");
        }
        MenuGrid.Pagina pag = pagina(FIREBRAND_MENU);
        if (pag == null)
            return Outcome.failed("no 'Firebrand' recipe in the crafting menu");
        pag.button().use(new MenuGrid.Interaction(1, 0));
        nav.waitUntil(() -> makewnd() != null, 80);
        Makewindow mw = makewnd();
        if (mw == null)
            return Outcome.blocked("crafting window didn't open for Firebrand");
        int branches = count(BRANCH);
        mw.wdgmsg("make", 0);
        nav.waitUntil(() -> ctx.onProgress(), 80);
        nav.waitUntil(() -> !ctx.onProgress(), 400);
        nav.waitUntil(() -> count(FIREBRAND) > 0 || count(BRANCH) < branches, 80);
        if ((count(FIREBRAND) <= 0) && (count(BRANCH) >= branches))
            return Outcome.blocked("the firebrand craft produced nothing");
        return Outcome.ok();
    }

    private Outcome light(Gob pyre) throws InterruptedException {
        if (!reach(pyre))
            return Outcome.blocked("couldn't get to the pyre to light it");
        rclick(pyre);
        FlowerMenu fm = Widgets.awaitFlowerMenu(gui.ui.root, this::running);
        if (fm == null)
            return Outcome.blocked("no flower menu when lighting the pyre");
        if (pickPetal(fm, LIGHT_PETAL))
            return Outcome.ok();
        fm.wdgmsg("cl", -1);
        return Outcome.blocked("no 'Light' option on the pyre (got: " + petalNames(fm) + ")");
    }

    // ------------------------------------------------------------------ raid phase

    /**
     * Waits for one smoked hive to be worth raiding.
     *
     * Ready is the pyre gone and the hive's swarm marker clear, not the clock: a pyre lit late in
     * the build phase has not finished burning when the fifteen minutes are up, and the hives come
     * ready in the order they were lit, which is the order this walks them in. The clock only
     * decides when to stop building and come back.
     */
    private Outcome awaitReady(Smoked s) throws InterruptedException {
        boolean sawPyreGo = false;
        for (int i = 0; i < READY_TIMEOUT; i++) {
            if (!running())
                return Outcome.blocked("stopped while waiting for the hive");
            if (i % 200 == 0) {
                /* Carried water only. Walking off to a barrel here would abandon every other
                 * smoked hive on the list, all of which are burning down on their own clock. */
                Drink.sipIfCarried(ctx);
                Upkeep.resume(ctx);
            }
            if (!sawPyreGo && (s.pyre >= 0) && (nav.gob(s.pyre) == null)) {
                sawPyreGo = true;
                noteAsh(s);
            }
            if (ready(s))
                return Outcome.ok();
            nav.pause(1);
        }
        return Outcome.blocked("hive still swarming after " + (READY_TIMEOUT / 40) + " seconds");
    }

    /** The pyre has burned away and the hive is no longer swarming. */
    private boolean ready(Smoked s) {
        Gob hive = nav.gob(s.hive);
        if (hive == null)
            return false;
        if ((s.pyre >= 0) && (nav.gob(s.pyre) != null))
            return false;
        return quiet(hive);
    }

    /**
     * Logs whatever is standing where the pyre was.
     *
     * A burnt-out bough pyre leaves an ash pile behind, and nothing in this tree knows its
     * resource name - there is no constant for it anywhere, and guessing one would give the bot a
     * readiness test that silently never fires. So the name is not hardcoded: readiness is "the
     * pyre gob is gone", and this records what replaced it so the name can be read out of the log
     * and used properly later.
     */
    private void noteAsh(Smoked s) {
        StringBuilder sb = new StringBuilder();
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (g.rc.dist(s.where) > MCache.tilesz.x)
                    continue;
                String r = resname(g);
                if (r.isEmpty() || r.startsWith("gfx/borka/"))
                    continue;
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(r);
            }
        }
        NLog.log(log, "pyre burned out @" + s.where + "; what is there now: "
                + (sb.length() == 0 ? "(nothing)" : sb));
    }

    /**
     * Raids one smoked hive.
     *
     * Through {@link WorkGob} rather than this class's own right-click-and-pick, because that is
     * the shared version that waits for the menu to actually close, tells "the menu never opened"
     * apart from "the menu had nothing we know on it", and re-issues the action when the server
     * drops it. No work slot: the hives were claimed by being smoked, and a run that got this far
     * has already spent the boughs.
     */
    private Outcome raid(Smoked s) throws InterruptedException {
        Gob hive = nav.gob(s.hive);
        if (hive == null)
            return Outcome.ok(); // already gone - somebody raided it, which is still the job done
        Outcome o = WorkGob.menu(hive, null, RAID_PETAL).run(ctx);
        if (o.isOk())
            report("raided hive @" + s.where);
        return o;
    }

    // ------------------------------------------------------------------ gathering

    /** Gathers {@code needed} of an item from the wild until satisfied or the sources run out. */
    private boolean gather(String itemName, String sourceRes, String petal, int needed)
            throws InterruptedException {
        if (count(itemName) >= needed)
            return true;
        report("gathering " + needed + "x " + itemName);
        Set<Long> tried = new HashSet<>();
        while (count(itemName) < needed && running()) {
            Gob source = nearest(sourceRes, tried);
            if (source == null) {
                report("no more " + itemName + " in sight");
                return false;
            }
            tried.add(source.id);
            if (!reach(source))
                continue; // couldn't get to that one; it's marked tried, so try the next
            int before = count(itemName);
            if (petal == null) {
                lclick(source.rc);
            } else {
                rclick(source);
                FlowerMenu fm = Widgets.awaitFlowerMenu(gui.ui.root, this::running);
                if (fm == null)
                    continue;
                if (!pickPetal(fm, petal)) {
                    fm.wdgmsg("cl", -1);
                    report("no '" + petal + "' option on " + resname(source) + " (got: " + petalNames(fm) + ")");
                    return false;
                }
            }
            nav.waitUntil(() -> count(itemName) > before || ctx.onProgress(), 80);
            nav.waitUntil(() -> !ctx.onProgress(), 200);
        }
        return count(itemName) >= needed;
    }

    private int count(String name) {
        return gui.maininv.getItemsPartial(name).size();
    }

    /** True once the carried vessels are effectively dry. */
    private boolean spentWater() {
        try {
            return Carried.waterFraction(gui) < WATER_SPENT;
        } catch (RuntimeException e) {
            // Inventory not readable this tick - assume there is water rather than end the phase.
            return false;
        }
    }

    // ------------------------------------------------------------------ ui plumbing

    /**
     * The menu-grid entry with this name.
     *
     * Synchronised on the set because {@code paginae} is a plain HashSet that MenuGrid itself
     * guards everywhere it touches it, and this runs on the bot thread: iterating it bare raced
     * the server pushing a menu update and threw ConcurrentModificationException out through the
     * whole shift.
     */
    private MenuGrid.Pagina pagina(String name) {
        synchronized (gui.menu.paginae) {
            for (MenuGrid.Pagina pag : gui.menu.paginae) {
                try {
                    if (name.equalsIgnoreCase(pag.button().name()))
                        return pag;
                } catch (Loading e) {
                    // button resource not loaded yet; skip this pagina
                }
            }
        }
        return null;
    }

    private boolean enterPlacing(MenuGrid.Pagina pag) throws InterruptedException {
        for (int i = 0; i < 3 && gui.map.placing() == null; i++) {
            pag.button().use(new MenuGrid.Interaction(1, 0));
            nav.waitUntil(() -> gui.map.placing() != null, 40);
        }
        return gui.map.placing() != null;
    }

    private boolean pickPetal(FlowerMenu fm, String name) {
        for (FlowerMenu.Petal p : fm.opts) {
            if (name.equals(p.name)) {
                fm.wdgmsg("cl", p.num, 0);
                return true;
            }
        }
        return false;
    }

    private String petalNames(FlowerMenu fm) {
        StringBuilder sb = new StringBuilder();
        for (FlowerMenu.Petal p : fm.opts) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(p.name);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ world queries

    /** A pyre (built or lit) already standing close enough to smoke the given hive. */
    private Gob pyreNear(Gob ref) {
        Gob pyre = resNear(ref.rc, "pyre");
        return (pyre != null) ? pyre : resNear(ref.rc, "bonfire");
    }

    /** An unfinished construction site THIS RUN placed, standing near the hive. */
    private Gob ourSiteNear(Gob hive) {
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (ourSites.contains(g.id) && resname(g).contains("consobj")
                        && g.rc.dist(hive.rc) <= PYRE_RANGE)
                    return g;
            }
        }
        return null;
    }

    private Gob resNear(Coord2d wc, String fragment) {
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (resname(g).contains(fragment) && g.rc.dist(wc) <= PYRE_RANGE)
                    return g;
            }
        }
        return null;
    }

    /**
     * A gob of the given kind standing on the tile a placement was sent to.
     *
     * Within half a tile of the spot rather than exactly on it: what comes back is the server's
     * own idea of where the object goes, and holding it to the posres unit we happened to send
     * would read a successful build as a refusal on any rounding at all.
     */
    private Gob gobAtSpot(Coord spot, String fragment) {
        Coord2d wc = spot.mul(posres);
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (g.rc.dist(wc) <= MCache.tilesz.x / 2 && resname(g).contains(fragment))
                    return g;
            }
        }
        return null;
    }

    private boolean quiet(Gob hive) {
        try {
            ResDrawable rd = hive.getattr(ResDrawable.class);
            return rd == null || rd.sdtnum() == 0;
        } catch (Loading e) {
            return false;
        }
    }

    /**
     * The nearest gob matching {@code fragment} that isn't excluded.
     *
     * Null when the player gob is missing, which happens for a tick or two around a load screen.
     * The version this replaces read {@code me.rc} straight out and died there.
     */
    private Gob nearest(String fragment, Set<Long> exclude) {
        Gob me = nav.player();
        if (me == null)
            return null;
        Gob best = null;
        double bestd = Double.MAX_VALUE;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (exclude.contains(g.id))
                    continue;
                String r = resname(g);
                if (!r.contains(fragment))
                    continue;
                if (fragment.equals(TREE_RES) && (r.contains("stump") || r.contains("log") || r.contains("oldtrunk")))
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

    /**
     * Tile centres near the hive that a pyre would actually fit on, closest to the hive first.
     *
     * The version this replaces was written in the wrong unit twice over. Placement coordinates go
     * out in posres units, of which there are 1024 to a tile, so stepping the hive's posres
     * position by 1..3 moved a hundredth of a tile: all two dozen "candidates" were the same point,
     * inside the hive's own hitbox, and the server answered "that site is occupied" to every one of
     * them. And placement with no modifier held snaps to tile centres (MapView.StdPlace), so tile
     * centres are the only positions worth offering in the first place.
     *
     * Spots whose footprint would overlap something already standing are dropped here rather than
     * sent and refused - a refusal costs a round trip plus the wait for a consobj that is never
     * going to arrive - and what survives is ordered by distance, so the pyre ends up as close to
     * the hive as the objects around it allow.
     */
    private List<Coord> candidateSpots(Gob hive, double footprint) {
        List<Gob> blockers = blockersNear(hive);
        List<Coord2d> spots = new ArrayList<>();
        Coord ht = hive.rc.floor(MCache.tilesz);
        for (int dy = -SPOT_TILES; dy <= SPOT_TILES; dy++) {
            for (int dx = -SPOT_TILES; dx <= SPOT_TILES; dx++) {
                Coord2d c = ht.add(dx, dy).mul(MCache.tilesz).add(MCache.tilesz.div(2));
                /* Further out than this and the pyre would not count as being under the hive, so
                 * building it there would waste the boughs and then fail the next check anyway. */
                if (c.dist(hive.rc) > PYRE_RANGE - SPOT_CLEARANCE)
                    continue;
                if (clear(c, footprint, blockers))
                    spots.add(c);
            }
        }
        spots.sort(Comparator.comparingDouble(c -> c.dist(hive.rc)));
        List<Coord> out = new ArrayList<>();
        for (Coord2d c : spots)
            out.add(c.floor(posres));
        return out;
    }

    /** True if nothing standing near the hive comes within {@code footprint} of this spot. */
    private boolean clear(Coord2d spot, double footprint, List<Gob> blockers) {
        for (Gob g : blockers) {
            if (MovementCommand.faceGap(g, spot) < footprint + SPOT_CLEARANCE)
                return false;
        }
        return true;
    }

    /**
     * What a placement near the hive has to miss.
     *
     * Anything that walks is deliberately left out. By the time the bot places a pyre it is
     * standing next to the hive itself, and treating its own body as an obstacle vetoes the spots
     * closest to the hive - the ones we most want. The server cares what is built on a tile, not
     * who is standing on it.
     *
     * Boxes are read straight out of {@link HitBoxes#collisionBoxMap}, which Gob fills in for
     * every gob it sets up, so nothing here has to touch the resource layer or the database.
     */
    private List<Gob> blockersNear(Gob hive) {
        List<Gob> out = new ArrayList<>();
        double range = PYRE_RANGE + (SPOT_TILES * MCache.tilesz.x);
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (g.rc.dist(hive.rc) > range)
                    continue;
                String r = resname(g);
                if (r.isEmpty() || r.startsWith("gfx/borka/") || r.startsWith("gfx/kritter/"))
                    continue;
                out.add(g);
            }
        }
        return out;
    }

    private Makewindow makewnd() {
        return (gui.makewnd == null) ? null : gui.makewnd.makeWidget;
    }

    private Button findButton(Widget root, String label) {
        if (root == null)
            return null;
        for (Widget w = root.child; w != null; w = w.next) {
            /* An image button has no text at all, and there are several in the tree between here
             * and the construction window - reading .text.text off one of those threw an NPE out
             * through the whole run. */
            if (w instanceof Button) {
                Text t = ((Button) w).text;
                if (t != null && label.equals(t.text))
                    return (Button) w;
            }
            Button deep = findButton(w, label);
            if (deep != null)
                return deep;
        }
        return null;
    }

    /**
     * Empty for anything that cannot answer right now, so every caller can go straight to
     * {@code .contains(...)}.
     *
     * Gob.getres() has two ways of not having a name yet, and only one of them throws: a gob whose
     * resource is still arriving raises {@link Loading}, but one that has no Drawable attribute at
     * all - which is normal for a gob the server has announced and not yet described - simply
     * returns null. Reading .name off that killed a run mid-smoke.
     */
    private String resname(Gob g) {
        try {
            Resource res = g.getres();
            return (res == null) ? "" : res.name;
        } catch (Loading e) {
            return "";
        }
    }

    /**
     * Walks to a gob and confirms we got there, before anything is clicked on it.
     *
     * {@link BotNav#approach} returns false when it gave up, and every call here used to throw that
     * answer away and click anyway. A right-click sent from across the clearing is not refused, it
     * is silently ignored - so what followed was a short wait for a window that was never coming,
     * and a hive abandoned with its half-built pyre still standing on it.
     *
     * Two tries, because approach re-plans internally but can still lose a race with terrain that
     * is still streaming in, and the second path is routinely the better one.
     */
    private boolean reach(Gob g) throws InterruptedException {
        if (g == null)
            return false;
        for (int i = 0; i < 2 && running(); i++) {
            if (nav.approach(g, BotNav.REACH))
                return true;
        }
        // approach also answers false when the player gob blinked out from under it, so take the
        // distance as the last word rather than the attempt.
        Gob me = nav.player();
        return (me != null) && (me.rc.dist(g.rc) <= BotNav.REACH);
    }

    private void rclick(Gob g) {
        gui.map.wdgmsg("click", Coord.z, g.rc.floor(posres), 3, 0, 0, (int) g.id, g.rc.floor(posres), 0, -1);
    }

    private void lclick(Coord2d wc) {
        gui.map.wdgmsg("click", Coord.z, wc.floor(posres), 1, 0);
    }
}
